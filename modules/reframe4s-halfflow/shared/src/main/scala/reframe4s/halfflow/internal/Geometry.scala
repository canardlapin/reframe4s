package reframe4s.halfflow.internal

import scala.annotation.targetName

enum GeometryError:
  case Expected3D(label: String, actual: Int)
  case NonPositiveDimension(axis: SpatialAxis, value: Int)
  case InvalidGridGeometry(reason: String)
  case InvalidSpatialAxis(value: Int)
  case NonFiniteCoordinate(label: String, axis: SpatialAxis, value: Double)
  case VoxelOutOfBounds(coord: VoxelCoord, dims: SpatialDims)
  case LinearIndexOutOfBounds(index: Int, size: Int)

  def message: String =
    this match
      case Expected3D(label, actual) =>
        s"$label must contain exactly 3 values; got $actual"
      case NonPositiveDimension(axis, value) =>
        s"${axis.label} dimension must be positive; got $value"
      case InvalidGridGeometry(reason) =>
        s"invalid grid geometry: $reason"
      case InvalidSpatialAxis(value) =>
        s"spatial axis must be 0, 1, or 2; got $value"
      case NonFiniteCoordinate(label, axis, value) =>
        s"$label ${axis.label} coordinate must be finite; got $value"
      case VoxelOutOfBounds(coord, dims) =>
        s"voxel ${coord.toVector} out of bounds for dims ${dims.toVector}"
      case LinearIndexOutOfBounds(index, size) =>
        s"linear index $index out of bounds for size $size"

enum SpatialAxis(val index: Int, val label: String):
  case X extends SpatialAxis(0, "x")
  case Y extends SpatialAxis(1, "y")
  case Z extends SpatialAxis(2, "z")

object SpatialAxis:
  val all: Vector[SpatialAxis] = Vector(X, Y, Z)

  def fromInt(value: Int): Either[GeometryError, SpatialAxis] =
    value match
      case 0 => Right(X)
      case 1 => Right(Y)
      case 2 => Right(Z)
      case other => Left(GeometryError.InvalidSpatialAxis(other))

  def unsafe(value: Int): SpatialAxis =
    fromInt(value).fold(err => throw new IllegalArgumentException(err.message), identity)

opaque type SpatialDimSize = Int

object SpatialDimSize:
  def make(axis: SpatialAxis, value: Int): Either[GeometryError, SpatialDimSize] =
    if value > 0 then Right(value)
    else Left(GeometryError.NonPositiveDimension(axis, value))

  def unsafe(axis: SpatialAxis, value: Int): SpatialDimSize =
    make(axis, value).fold(err => throw new IllegalArgumentException(err.message), identity)

  extension (size: SpatialDimSize)
    inline def value: Int = size

final class SpatialDims private (
  xSize: SpatialDimSize,
  ySize: SpatialDimSize,
  zSize: SpatialDimSize
):
  def x: Int = xSize.value
  def y: Int = ySize.value
  def z: Int = zSize.value

  def apply(axis: SpatialAxis): Int =
    axis match
      case SpatialAxis.X => x
      case SpatialAxis.Y => y
      case SpatialAxis.Z => z

  def apply(axis: Int): Int =
    apply(SpatialAxis.unsafe(axis))

  def product: Int = x * y * z
  def length: Int = 3
  def toVector: Vector[Int] = Vector(x, y, z)

  override def equals(other: Any): Boolean =
    other match
      case that: SpatialDims => this.toVector == that.toVector
      case _ => false

  override def hashCode(): Int =
    toVector.hashCode()

  override def toString: String =
    s"SpatialDims($x, $y, $z)"

object SpatialDims:
  def make(x: Int, y: Int, z: Int): Either[GeometryError, SpatialDims] =
    for
      xSize <- SpatialDimSize.make(SpatialAxis.X, x)
      ySize <- SpatialDimSize.make(SpatialAxis.Y, y)
      zSize <- SpatialDimSize.make(SpatialAxis.Z, z)
    yield new SpatialDims(xSize, ySize, zSize)

  @targetName("fromInts")
  def apply(x: Int, y: Int, z: Int): SpatialDims =
    make(x, y, z).fold(err => throw new IllegalArgumentException(err.message), identity)

  def fromVector(values: Vector[Int], label: String = "spatial dims"): Either[GeometryError, SpatialDims] =
    if values.length != 3 then Left(GeometryError.Expected3D(label, values.length))
    else make(values(0), values(1), values(2))

  def unsafeFromVector(values: Vector[Int], label: String = "spatial dims"): SpatialDims =
    fromVector(values, label).fold(err => throw new IllegalArgumentException(err.message), identity)

final case class VoxelCoord(x: Int, y: Int, z: Int):
  def apply(axis: SpatialAxis): Int =
    axis match
      case SpatialAxis.X => x
      case SpatialAxis.Y => y
      case SpatialAxis.Z => z

  def toVector: Vector[Int] = Vector(x, y, z)

object VoxelCoord:
  def fromVector(values: Vector[Int], label: String = "voxel coord"): Either[GeometryError, VoxelCoord] =
    if values.length != 3 then Left(GeometryError.Expected3D(label, values.length))
    else Right(VoxelCoord(values(0), values(1), values(2)))

  def unsafeFromVector(values: Vector[Int], label: String = "voxel coord"): VoxelCoord =
    fromVector(values, label).fold(err => throw new IllegalArgumentException(err.message), identity)

final case class SpatialPoint(x: Double, y: Double, z: Double):
  require(x.isFinite && y.isFinite && z.isFinite, "spatial point coordinates must be finite")

  def apply(axis: SpatialAxis): Double =
    axis match
      case SpatialAxis.X => x
      case SpatialAxis.Y => y
      case SpatialAxis.Z => z

  def toVector: Vector[Double] =
    Vector(x, y, z)

object SpatialPoint:
  val Origin: SpatialPoint =
    SpatialPoint(0.0, 0.0, 0.0)

  def make(x: Double, y: Double, z: Double, label: String = "spatial point"): Either[GeometryError, SpatialPoint] =
    val values = Vector(x, y, z)
    var i = 0
    var error = Option.empty[GeometryError]
    while i < values.length && error.isEmpty do
      if !values(i).isFinite then error = Some(GeometryError.NonFiniteCoordinate(label, SpatialAxis.all(i), values(i)))
      i += 1

    error match
      case Some(err) => Left(err)
      case None => Right(SpatialPoint(x, y, z))

  def fromVector(values: Vector[Double], label: String = "spatial point"): Either[GeometryError, SpatialPoint] =
    if values.length != 3 then Left(GeometryError.Expected3D(label, values.length))
    else make(values(0), values(1), values(2), label)

  def unsafeFromVector(values: Vector[Double], label: String = "spatial point"): SpatialPoint =
    fromVector(values, label).fold(err => throw new IllegalArgumentException(err.message), identity)

final case class VoxelPoint(x: Double, y: Double, z: Double):
  require(x.isFinite && y.isFinite && z.isFinite, "voxel point coordinates must be finite")

  def apply(axis: SpatialAxis): Double =
    axis match
      case SpatialAxis.X => x
      case SpatialAxis.Y => y
      case SpatialAxis.Z => z

  def toVector: Vector[Double] =
    Vector(x, y, z)

  def toSpatialPoint: SpatialPoint =
    SpatialPoint(x, y, z)

object VoxelPoint:
  val Origin: VoxelPoint =
    VoxelPoint(0.0, 0.0, 0.0)

  def make(x: Double, y: Double, z: Double, label: String = "voxel point"): Either[GeometryError, VoxelPoint] =
    SpatialPoint.make(x, y, z, label).map(point => VoxelPoint(point.x, point.y, point.z))

  def fromVector(values: Vector[Double], label: String = "voxel point"): Either[GeometryError, VoxelPoint] =
    SpatialPoint.fromVector(values, label).map(point => VoxelPoint(point.x, point.y, point.z))

  def unsafeFromVector(values: Vector[Double], label: String = "voxel point"): VoxelPoint =
    fromVector(values, label).fold(err => throw new IllegalArgumentException(err.message), identity)

  def fromSpatialPoint(point: SpatialPoint): VoxelPoint =
    VoxelPoint(point.x, point.y, point.z)

final case class WorldPoint(x: Double, y: Double, z: Double):
  require(x.isFinite && y.isFinite && z.isFinite, "world point coordinates must be finite")

  def +(delta: WorldVector): WorldPoint =
    WorldPoint(x + delta.x, y + delta.y, z + delta.z)

  def -(delta: WorldVector): WorldPoint =
    WorldPoint(x - delta.x, y - delta.y, z - delta.z)

  def -(other: WorldPoint): WorldVector =
    WorldVector.unsafe(x - other.x, y - other.y, z - other.z)

  def apply(axis: SpatialAxis): Double =
    axis match
      case SpatialAxis.X => x
      case SpatialAxis.Y => y
      case SpatialAxis.Z => z

  def toVector: Vector[Double] =
    Vector(x, y, z)

  def toSpatialPoint: SpatialPoint =
    SpatialPoint(x, y, z)

object WorldPoint:
  val Origin: WorldPoint =
    WorldPoint(0.0, 0.0, 0.0)

  def make(x: Double, y: Double, z: Double, label: String = "world point"): Either[GeometryError, WorldPoint] =
    SpatialPoint.make(x, y, z, label).map(point => WorldPoint(point.x, point.y, point.z))

  def fromVector(values: Vector[Double], label: String = "world point"): Either[GeometryError, WorldPoint] =
    SpatialPoint.fromVector(values, label).map(point => WorldPoint(point.x, point.y, point.z))

  def unsafeFromVector(values: Vector[Double], label: String = "world point"): WorldPoint =
    fromVector(values, label).fold(err => throw new IllegalArgumentException(err.message), identity)

  def fromSpatialPoint(point: SpatialPoint): WorldPoint =
    WorldPoint(point.x, point.y, point.z)

final case class WorldVector private (x: Double, y: Double, z: Double)

object WorldVector:
  def unsafe(x: Double, y: Double, z: Double): WorldVector =
    require(x.isFinite && y.isFinite && z.isFinite, "world vector components must be finite")
    new WorldVector(x, y, z)

  def fromSpatialPoint(point: SpatialPoint): WorldPoint =
    WorldPoint(point.x, point.y, point.z)
