package reframe4s.halfflow.internal

import gale.linalg.DMat

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

/** Execution view for HalfFlow's x-fastest scratch buffers.
  * Geometry and frame identity are owned by the canonical image4s sample space.
  */
final class GridSpec private (
    val canonical: SampleSpace[? <: CanonicalFrame[D3], D3]
):
  val shape: Vector[Int] = canonical.grid.shape
  val dims: Vector[Int] = shape
  inline def spatialDims: Vector[Int] = dims
  def indexToFrame: CanonicalAffine[D3] = canonical.grid.indexToFrame
  def affine: DMat = indexToFrame.matrix
  def inverseAffine: DMat = indexToFrame.inverse.matrix
  inline def trans: DMat = affine
  val nVoxels: Int =
    val size = shape.foldLeft(1L)(_ * _)
    require(size <= Int.MaxValue, "HalfFlow grid exceeds supported buffer size")
    size.toInt

  private[halfflow] inline def extentX: Int = shape(0)
  private[halfflow] inline def extentY: Int = shape(1)
  private[halfflow] inline def extentZ: Int = shape(2)
  private[halfflow] inline def affineElement(row: Int, column: Int): Double = affine(row, column)

  def voxelToWorld(voxel: Vector[Double]): Vector[Double] =
    indexToFrame(voxel).fold(error => throw new IllegalArgumentException(error.message), value => value)

  def spacing: Vector[Double] =
    Vector.tabulate(3)(column => math.sqrt((0 until 3).map(row => {
      val value = affine(row, column)
      value * value
    }).sum))

  def worldCoords: Vector[Vector[Double]] =
    Vector.tabulate(nVoxels): index =>
      val x = index % shape(0)
      val yz = index / shape(0)
      voxelToWorld(Vector(x.toDouble, (yz % shape(1)).toDouble, (yz / shape(1)).toDouble))

  /** A new lattice in the same physical frame, used by pyramid execution. */
  def withGeometry(dims: Vector[Int], operator: CanonicalAffine[D3]): GridSpec =
    val grid = CanonicalGrid.in(canonical.grid.frame)(dims, operator)
      .fold(error => throw new IllegalArgumentException(error.message), value => value)
    GridSpec.fromGrid(grid)

  inline def toNeuroSpace: NeuroSpace = this

  /** Execution compatibility requires the same live frame as well as geometry. */
  override def equals(other: Any): Boolean = other match
    case that: GridSpec =>
      canonical.grid.frame.sameRuntimeOwnerAs(that.canonical.grid.frame) &&
        dims == that.dims && indexToFrame.rowMajor == that.indexToFrame.rowMajor
    case _ => false

  override def hashCode(): Int =
    31 * dims.hashCode() + indexToFrame.rowMajor.hashCode()

object GridSpec:
  def fromGrid(grid: CanonicalGrid[? <: CanonicalFrame[D3], D3]): GridSpec =
    new GridSpec(SampleSpace.create(grid, NonSpatialAxes.empty))

  def apply(dims: Vector[Int], affine: DMat): GridSpec =
    require(affine.rows == 4 && affine.cols == 4, "HalfFlow grid affine must be 4x4")
    val operator = CanonicalAffine.fromRowMajor[D3](Vector.tabulate(16)(i => affine(i / 4, i % 4)))
      .fold(error => throw new IllegalArgumentException(error.message), value => value)
    val frame = CanonicalFrame.named[D3]("HalfFlow grid")
      .fold(error => throw new IllegalArgumentException(error.message), value => value)
    val grid = CanonicalGrid.in(frame)(dims, operator)
      .fold(error => throw new IllegalArgumentException(error.message), value => value)
    fromGrid(grid)

  def identity(dims: Vector[Int]): GridSpec = apply(dims, DMat.eye(4))
  inline def fromSpace(space: NeuroSpace): GridSpec = space

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
    val nx = space.shape(0)
    val ny = space.shape(1)
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
    val nx = space.shape(0)
    val ny = space.shape(1)
    val values =
      RavelArray.tabulate[A](nx, ny, space.shape(2)): (i, j, k) =>
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
        .fold(error => throw new IllegalArgumentException(error.message), value => value)
    new NeuroVol(canonical, space, label)

enum DenseVectorFieldKind:
  case SourceCoordinates, Displacement

private sealed trait CanonicalVectorField:
  type F <: CanonicalFrame[D3]
  val sampled: ContinuousImage[? <: SampleSpace[F, D3], Double, Rank[4]]

/** Zero-copy HalfFlow facade over a canonical D3-plus-Direction Sampled. */
final class DenseVectorField private[internal] (
    val grid: GridSpec,
    private val canonicalField: CanonicalVectorField,
    val kind: DenseVectorFieldKind
):
  private val sampled = canonicalField.sampled
  inline def values: RavelArray[Double, Rank[4]] = sampled.data

  def velocity: Either[reframe4s.field.FieldError, reframe4s.field.Velocity[?, D3, Rank[4]]] =
    reframe4s.field.Velocity.from[canonicalField.F, D3, Rank[4]](sampled)

  def toMap(target: CanonicalFrame[D3]): Either[reframe4s.field.FieldError, reframe4s.field.DenseMap[?, ?, D3, Rank[4]]] =
    reframe4s.field.DenseMap.fromCoordinates[canonicalField.F, CanonicalFrame[D3], D3, Rank[4]](
      sampled, target,
      boundary = reframe4s.field.CoordinateBoundaryPolicy.PreserveSource
    )
  private[halfflow] val flatValues = values.reshapeView(ravel.Shape(values.size))

  inline def apply(i: Int, j: Int, k: Int, component: Int): Double =
    values(i, j, k, component)

  def linearComponent(linearVoxel: Int, component: Int): Double =
    require(linearVoxel >= 0 && linearVoxel < grid.nVoxels, "linear voxel index out of bounds")
    require(component >= 0 && component < 3, "vector component out of bounds")
    val nx = grid.shape(0)
    val ny = grid.shape(1)
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
      values.shape == ravel.Shape(grid.shape(0), grid.shape(1), grid.shape(2), 3),
      "dense vector field must have grid dims plus three components"
    )
    val direction =
      Axis
        .create("direction", 3, AxisKind.Direction)
        .fold(error => throw new IllegalArgumentException(error.message), value => value)
    val axes =
      NonSpatialAxes
        .from(Vector(direction))
        .fold(error => throw new IllegalArgumentException(error.message), value => value)
    val space = SampleSpace.create(grid.canonical.grid, axes)
    val sampled =
      Sampled
        .continuous(space, values, ImageMetadata.named(kind.toString))
        .fold(error => throw new IllegalArgumentException(error.message), value => value)
    val image = sampled
    val canonicalField = new CanonicalVectorField:
      type F = space.F
      val sampled = image
    new DenseVectorField(grid, canonicalField, kind)

  def fromLegacyPlanar(
      grid: GridSpec,
      values: Array[Double],
      kind: DenseVectorFieldKind
  ): DenseVectorField =
    require(values.length == grid.nVoxels * 3, "dense vector field data length mismatch")
    val nx = grid.shape(0)
    val ny = grid.shape(1)
    val packed =
      RavelArray.tabulate[Double](nx, ny, grid.shape(2), 3): (i, j, k, component) =>
        values(i + nx * (j + ny * k) + component * grid.nVoxels)
    apply(grid, packed, kind)
