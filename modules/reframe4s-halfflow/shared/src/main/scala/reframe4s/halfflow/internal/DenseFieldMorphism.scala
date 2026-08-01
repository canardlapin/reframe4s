package reframe4s.halfflow.internal

import ravel.NDArray as RavelArray
import ravel.Rank
import ravel.Shape

object Resample:
  enum Method:
    case Nearest, Linear, Cubic

enum DenseFieldKind:
  case Displacement, AbsoluteCoordinates

enum MorphismError:
  case InvalidField(reason: String)

  def message: String =
    this match
      case InvalidField(reason) => reason

enum DenseFieldOutside:
  case Zero, QueryPoint

final class DenseFieldInterpolationPlan private (
    grid: GridSpec,
    points: Vector[Vector[Double]],
    method: Resample.Method
):
  def sample(
      field: RavelArray[Double, Rank[4]],
      outside: DenseFieldOutside
  ): Either[MorphismError, Vector[Vector[Double]]] =
    DenseFieldMorphism
      .coordinates(
        SpatialDomainId("interpolation-source"),
        SpatialDomainId("interpolation-target"),
        grid,
        field,
        method
      )
      .map: morphism =>
        points.map: coordinates =>
          val point = WorldPoint.unsafeFromVector(coordinates, "dense field query")
          val sampled = morphism.transform(point)
          if outside == DenseFieldOutside.Zero && sampled == point && !inside(point) then
            Vector(0.0, 0.0, 0.0)
          else sampled.toVector

  private def inside(point: WorldPoint): Boolean =
    val voxel =
      DMat
        .invert(grid.affine)
        .fold(reason => throw new IllegalArgumentException(reason), inverse => Affine.applyAffine(inverse, point.toVector))
    voxel(0) >= 0.0 && voxel(0) <= grid.shape.x - 1.0 &&
      voxel(1) >= 0.0 && voxel(1) <= grid.shape.y - 1.0 &&
      voxel(2) >= 0.0 && voxel(2) <= grid.shape.z - 1.0

object DenseFieldInterpolationPlan:
  def make(
      grid: GridSpec,
      points: Vector[Vector[Double]],
      method: Resample.Method
  ): Either[MorphismError, DenseFieldInterpolationPlan] =
    points.zipWithIndex.collectFirst {
      case (point, index) if point.length != 3 || !point.forall(_.isFinite) =>
        MorphismError.InvalidField(s"dense field query $index must contain three finite coordinates")
    } match
      case Some(error) => Left(error)
      case None        => Right(new DenseFieldInterpolationPlan(grid, points, method))

/** Legacy export view retained inside the experimental HalfFlow boundary.
  *
  * Storage remains the canonical Ravel component array also owned by the
  * HalfFlow `DenseVectorField`; this class adds only domain labels and sampling.
  */
final class DenseFieldMorphism private (
    val source: SpatialDomainId,
    val target: SpatialDomainId,
    val grid: GridSpec,
    val field: RavelArray[Double, Rank[4]],
    val fieldKind: DenseFieldKind,
    val interpolation: Resample.Method,
    val cost: Double,
    val methodTag: String,
    inverseGridAffine: DMat
):
  def transform(point: WorldPoint): WorldPoint =
    val voxel = Affine.applyAffine(inverseGridAffine, point.toVector)
    interpolation match
      case Resample.Method.Nearest =>
        sampleNearest(point, voxel(0), voxel(1), voxel(2))
      case Resample.Method.Linear =>
        sampleLinear(point, voxel(0), voxel(1), voxel(2))
      case Resample.Method.Cubic =>
        sampleLinear(point, voxel(0), voxel(1), voxel(2))

  private def sampleNearest(
      point: WorldPoint,
      x: Double,
      y: Double,
      z: Double
  ): WorldPoint =
    val i = math.round(x).toInt
    val j = math.round(y).toInt
    val k = math.round(z).toInt
    if inside(i, j, k) then fromComponents(point, field(i, j, k, 0), field(i, j, k, 1), field(i, j, k, 2))
    else point

  private def sampleLinear(
      point: WorldPoint,
      x: Double,
      y: Double,
      z: Double
  ): WorldPoint =
    val i0 = math.floor(x).toInt
    val j0 = math.floor(y).toInt
    val k0 = math.floor(z).toInt
    val fx = x - i0
    val fy = y - j0
    val fz = z - k0
    var component = 0
    val sampled = Array.ofDim[Double](3)
    while component < 3 do
      var value = 0.0
      var dz = 0
      while dz <= 1 do
        val wz = if dz == 0 then 1.0 - fz else fz
        var dy = 0
        while dy <= 1 do
          val wy = if dy == 0 then 1.0 - fy else fy
          var dx = 0
          while dx <= 1 do
            val wx = if dx == 0 then 1.0 - fx else fx
            val weight = wx * wy * wz
            val i = i0 + dx
            val j = j0 + dy
            val k = k0 + dz
            if inside(i, j, k) then
              value += weight * field(i, j, k, component)
            else if fieldKind == DenseFieldKind.AbsoluteCoordinates then
              value += weight * point.toVector(component)
            dx += 1
          dy += 1
        dz += 1
      sampled(component) = value
      component += 1
    fromComponents(point, sampled(0), sampled(1), sampled(2))

  private def fromComponents(
      point: WorldPoint,
      x: Double,
      y: Double,
      z: Double
  ): WorldPoint =
    fieldKind match
      case DenseFieldKind.AbsoluteCoordinates => WorldPoint(x, y, z)
      case DenseFieldKind.Displacement =>
        WorldPoint(point.x + x, point.y + y, point.z + z)

  private inline def inside(i: Int, j: Int, k: Int): Boolean =
    i >= 0 && i < grid.shape.x &&
      j >= 0 && j < grid.shape.y &&
      k >= 0 && k < grid.shape.z

object DenseFieldMorphism:
  def coordinates(
      source: SpatialDomainId,
      target: SpatialDomainId,
      grid: GridSpec,
      field: RavelArray[Double, Rank[4]],
      interpolation: Resample.Method = Resample.Method.Linear,
      cost: Double = 10.0,
      methodTag: String = "dense-coordinate"
  ): Either[MorphismError, DenseFieldMorphism] =
    make(
      source,
      target,
      grid,
      field,
      DenseFieldKind.AbsoluteCoordinates,
      interpolation,
      cost,
      methodTag
    )

  private def make(
      source: SpatialDomainId,
      target: SpatialDomainId,
      grid: GridSpec,
      field: RavelArray[Double, Rank[4]],
      fieldKind: DenseFieldKind,
      interpolation: Resample.Method,
      cost: Double,
      methodTag: String
  ): Either[MorphismError, DenseFieldMorphism] =
    val expected = Shape(grid.shape.x, grid.shape.y, grid.shape.z, 3)
    if field.shape != expected then
      Left(MorphismError.InvalidField(s"dense field shape ${field.shape} != $expected"))
    else if !cost.isFinite || cost < 0.0 then
      Left(MorphismError.InvalidField("dense field cost must be finite and non-negative"))
    else
      DMat
        .invert(grid.affine)
        .left
        .map(reason => MorphismError.InvalidField(s"dense field grid is singular: $reason"))
        .map: inverse =>
          new DenseFieldMorphism(
            source,
            target,
            grid,
            field,
            fieldKind,
            interpolation,
            cost,
            methodTag,
            inverse
          )
