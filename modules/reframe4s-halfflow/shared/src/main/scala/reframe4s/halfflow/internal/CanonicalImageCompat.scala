package reframe4s.halfflow.internal

import image4s.Axis
import image4s.AxisKind
import image4s.ContinuousImage
import image4s.ImageError
import image4s.ImageMetadata
import image4s.MaskImage
import image4s.NonSpatialAxes
import image4s.SampleSpace
import image4s.Sampled
import image4s.geometry.Affine as CanonicalAffine
import image4s.geometry.D3
import image4s.geometry.Frame as CanonicalFrame
import image4s.geometry.Grid as CanonicalGrid
import ravel.DType
import ravel.NDArray as RavelArray
import ravel.Rank

opaque type SpatialDomainId = String

object SpatialDomainId:
  def apply(value: String): SpatialDomainId =
    require(value.nonEmpty && value == value.trim, "spatial domain id must be non-empty and trimmed")
    value

  extension (id: SpatialDomainId)
    inline def value: String = id

/** HalfFlow's compatibility view over one canonical image4s D3 sample space.
  *
  * The adapter adds legacy x-fastest indexing helpers but owns no competing
  * geometry: `canonical` is the sole grid/sample-space authority.
  */
final class GridSpec private[internal] (
    val canonical: SampleSpace[? <: CanonicalFrame[D3], D3],
    val affine: DMat
):
  val shape: SpatialDims =
    SpatialDims.unsafeFromVector(canonical.grid.shape, "HalfFlow grid shape")

  val dims: Vector[Int] = canonical.grid.shape
  inline def spatialDims: Vector[Int] = dims

  inline def trans: DMat = affine

  inline def nVoxels: Int =
    shape.product

  private[halfflow] inline def extentX: Int = shape.x
  private[halfflow] inline def extentY: Int = shape.y
  private[halfflow] inline def extentZ: Int = shape.z

  private[halfflow] inline def affineElement(row: Int, column: Int): Double =
    affine(row, column)

  def affine3D: Either[Affine3DError, Affine3D] =
    Affine3D.make(affine)

  def voxelToWorld(voxel: Vector[Double]): Vector[Double] =
    Affine.applyAffine(affine, voxel)

  def voxelToWorld(voxel: SpatialPoint): SpatialPoint =
    SpatialPoint.unsafeFromVector(
      Affine.applyAffine(affine, voxel.toVector),
      "HalfFlow world coordinate"
    )

  def worldCoords: Vector[Vector[Double]] =
    val out = Vector.newBuilder[Vector[Double]]
    out.sizeHint(nVoxels)
    var z = 0
    while z < shape.z do
      var y = 0
      while y < shape.y do
        var x = 0
        while x < shape.x do
          out += voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))
          x += 1
        y += 1
      z += 1
    out.result()

  inline def toNeuroSpace: NeuroSpace =
    this

  override def equals(other: Any): Boolean =
    other match
      case that: GridSpec => dims == that.dims && affine == that.affine
      case _              => false

  override def hashCode(): Int =
    31 * dims.hashCode() + affine.hashCode()

object GridSpec:
  def apply(dims: Vector[Int], affine: DMat): GridSpec =
    val shape = SpatialDims.unsafeFromVector(dims, "HalfFlow grid shape")
    val frame =
      image4s.geometry.Frame
        .named[D3]("HalfFlow grid")
        .fold(error => throw new IllegalArgumentException(error.message), value => value)
    val canonicalAffine: CanonicalAffine[D3] =
      CanonicalAffine
        .fromRowMajor[D3](affine.data.toVector)
        .fold(error => throw new IllegalArgumentException(error.message), value => value)
    val grid =
      CanonicalGrid
        .in(frame)(shape.toVector, canonicalAffine)
        .fold(error => throw new IllegalArgumentException(error.message), value => value)
    new GridSpec(
      SampleSpace.create(grid, NonSpatialAxes.empty),
      DMat.fromRowMajorOwned(affine.rows, affine.cols, affine.data.clone())
    )

  def apply(dims: SpatialDims, affine: DMat): GridSpec =
    apply(dims.toVector, affine)

  def identity(dims: Vector[Int]): GridSpec =
    apply(dims, DMat.eye(4))

  def identity(dims: SpatialDims): GridSpec =
    apply(dims, DMat.eye(4))

  inline def fromSpace(space: NeuroSpace): GridSpec =
    space

type NeuroSpace = GridSpec

private sealed trait CanonicalVolume[A]:
  val data: RavelArray[A, Rank[3]]

sealed trait VolumeRole[A]:
  private[internal] def create(
      space: SampleSpace[? <: CanonicalFrame[D3], D3],
      data: RavelArray[A, Rank[3]],
      metadata: ImageMetadata
  ): Either[ImageError, CanonicalVolume[A]]

object VolumeRole:
  given VolumeRole[Double] with
    private[internal] def create(
        space: SampleSpace[? <: CanonicalFrame[D3], D3],
        data: RavelArray[Double, Rank[3]],
        metadata: ImageMetadata
    ): Either[ImageError, CanonicalVolume[Double]] =
      Sampled.continuous(space, data, metadata).map: image =>
        new CanonicalVolume[Double]:
          val sampled: ContinuousImage[space.type, Double, Rank[3]] = image
          val data: RavelArray[Double, Rank[3]] = sampled.data

  given VolumeRole[Boolean] with
    private[internal] def create(
        space: SampleSpace[? <: CanonicalFrame[D3], D3],
        data: RavelArray[Boolean, Rank[3]],
        metadata: ImageMetadata
    ): Either[ImageError, CanonicalVolume[Boolean]] =
      Sampled.mask(space, data, metadata).map: image =>
        new CanonicalVolume[Boolean]:
          val sampled: MaskImage[space.type, Rank[3]] = image
          val data: RavelArray[Boolean, Rank[3]] = sampled.data

/** Zero-copy legacy indexing facade over one canonical image4s Sampled value. */
final class NeuroVol[A] private[internal] (
    private val canonicalVolume: CanonicalVolume[A],
    val space: NeuroSpace,
    val label: String
):
  inline def values: RavelArray[A, Rank[3]] = canonicalVolume.data

  inline def apply(i: Int, j: Int, k: Int): A =
    values(i, j, k)

  inline def linear(index: Int): A =
    val nx = space.shape.x
    val ny = space.shape.y
    val x = index % nx
    val yz = index / nx
    val y = yz % ny
    val z = yz / ny
    values(x, y, z)

  def copyLegacyLinear(using scala.reflect.ClassTag[A]): Array[A] =
    val out = PrimitiveBuffers.ofSize[A](space.nVoxels)
    var index = 0
    while index < out.length do
      out(index) = linear(index)
      index += 1
    out

object NeuroVol:
  def fromLinear[A](
      data: Array[A],
      space: NeuroSpace,
      label: String = ""
  )(using dtype: DType[A], role: VolumeRole[A]): NeuroVol[A] =
    require(data.length == space.nVoxels, "volume data length must match grid")
    val nx = space.shape.x
    val ny = space.shape.y
    val values =
      RavelArray.tabulate[A](nx, ny, space.shape.z): (i, j, k) =>
        data(i + nx * (j + ny * k))
    fromRavel(values, space, label)

  def fromRavel[A](
      values: RavelArray[A, Rank[3]],
      space: NeuroSpace,
      label: String = ""
  )(using role: VolumeRole[A]): NeuroVol[A] =
    val canonical =
      role
        .create(space.canonical, values, ImageMetadata.named(label))
        .fold(error => throw new IllegalArgumentException(error.message), identity)
    new NeuroVol(canonical, space, label)

enum DenseVectorFieldKind:
  case SourceCoordinates, Displacement

private final class CanonicalVectorField(
    val sampled: ContinuousImage[
      ? <: SampleSpace[? <: CanonicalFrame[D3], D3],
      Double,
      Rank[4]
    ]
):
  inline def data: RavelArray[Double, Rank[4]] = sampled.data

/** Zero-copy HalfFlow facade over a canonical D3-plus-Direction Sampled. */
final class DenseVectorField private[internal] (
    val grid: GridSpec,
    private val canonicalField: CanonicalVectorField,
    val kind: DenseVectorFieldKind
):
  inline def values: RavelArray[Double, Rank[4]] = canonicalField.data
  private[halfflow] val flatValues = values.reshapeView(ravel.Shape(values.size))

  inline def apply(i: Int, j: Int, k: Int, component: Int): Double =
    values(i, j, k, component)

  def linearComponent(linearVoxel: Int, component: Int): Double =
    require(linearVoxel >= 0 && linearVoxel < grid.nVoxels, "linear voxel index out of bounds")
    require(component >= 0 && component < 3, "vector component out of bounds")
    val nx = grid.shape.x
    val ny = grid.shape.y
    val x = linearVoxel % nx
    val yz = linearVoxel / nx
    val y = yz % ny
    val z = yz / ny
    values(x, y, z, component)

  private[halfflow] inline def flatValue(storageIndex: Int): Double =
    flatValues(storageIndex)

  def copyLegacyPlanar: Array[Double] =
    val out = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    var voxel = 0
    while voxel < grid.nVoxels do
      var component = 0
      while component < 3 do
        out(voxel + component * grid.nVoxels) =
          linearComponent(voxel, component)
        component += 1
      voxel += 1
    out

object DenseVectorField:
  def apply(
      grid: GridSpec,
      values: RavelArray[Double, Rank[4]],
      kind: DenseVectorFieldKind
  ): DenseVectorField =
    require(
      values.shape == ravel.Shape(grid.shape.x, grid.shape.y, grid.shape.z, 3),
      "dense vector field must have grid dims plus three components"
    )
    val direction =
      Axis
        .create("direction", 3, AxisKind.Direction)
        .fold(error => throw new IllegalArgumentException(error.message), identity)
    val axes =
      NonSpatialAxes
        .from(Vector(direction))
        .fold(error => throw new IllegalArgumentException(error.message), identity)
    val space = SampleSpace.create(grid.canonical.grid, axes)
    val sampled =
      Sampled
        .continuous(space, values, ImageMetadata.named(kind.toString))
        .fold(error => throw new IllegalArgumentException(error.message), identity)
    new DenseVectorField(grid, new CanonicalVectorField(sampled), kind)

  def fromLegacyPlanar(
      grid: GridSpec,
      values: Array[Double],
      kind: DenseVectorFieldKind
  ): DenseVectorField =
    require(values.length == grid.nVoxels * 3, "dense vector field data length mismatch")
    val nx = grid.shape.x
    val ny = grid.shape.y
    val packed =
      RavelArray.tabulate[Double](nx, ny, grid.shape.z, 3): (i, j, k, component) =>
        values(i + nx * (j + ny * k) + component * grid.nVoxels)
    apply(grid, packed, kind)
