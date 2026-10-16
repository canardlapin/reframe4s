package reframe4s.field

import image4s.BoundaryPolicy
import image4s.Continuous
import image4s.ContinuousImage
import image4s.SampleSpace
import image4s.Validity
import ravel.AnyRank
import reframe4s.core.MapError
import reframe4s.core.FrameErasedMap
import reframe4s.core.SpatialMap
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point
import reframe4s.resample.Interpolation
import reframe4s.resample.SampledInterpolator

import scala.util.hashing.MurmurHash3

/** Boundary behavior for an absolute coordinate map.
  *
  * `PreserveSource` is the identity extension used by finite pullback fields:
  * outside sampled support, the map returns the query coordinate itself.
  */
enum CoordinateBoundaryPolicy derives CanEqual:
  case Reject
  case Constant(coordinates: Vector[Double])
  case PreserveSource

/** How one successful map evaluation related to the map's sampled support.
  *
  * A rejected evaluation is not an outcome: it is `MapError.OutsideDomain`.
  */
enum SupportOutcome derives CanEqual:
  case Covered
  case ConstantFilled
  case SourcePreserved

final case class CoveredPoint[F <: Frame[D], D <: Dim](
    point: Point[F, D],
    outcome: SupportOutcome
)

/** A spatial map that states, per evaluation, whether its boundary policy
  * supplied the result. Composition reports the first stage that left its
  * support, so filled values are never mistaken for sampled ones.
  */
trait CoverageReportingMap[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim
] extends SpatialMap[From, To, D]:
  def applyWithCoverage(
      point: Point[From, D]
  ): Either[MapError, CoveredPoint[To, D]]

  def apply(point: Point[From, D]): Either[MapError, Point[To, D]] =
    applyWithCoverage(point).map(_.point)

object CoverageReportingMap:
  /** Use a map's own coverage report, or treat every successful evaluation
    * of an ordinary map as covered.
    */
  def lift[From <: Frame[D], To <: Frame[D], D <: Dim](
      map: SpatialMap[From, To, D]
  ): CoverageReportingMap[From, To, D] =
    map match
      case reporting: CoverageReportingMap[From, To, D] @unchecked =>
        reporting
      case _ =>
        new ExactlyCovered(map)

  def compose[
      A <: Frame[D],
      B <: Frame[D],
      C <: Frame[D],
      D <: Dim
  ](
      first: SpatialMap[A, B, D],
      second: SpatialMap[B, C, D]
  )(using Dimension[D]): CoverageReportingMap[A, C, D] =
    new Composite(lift(first), lift(second))

  private final class ExactlyCovered[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](map: SpatialMap[From, To, D])
      extends CoverageReportingMap[From, To, D]:
    val source: From = map.source
    val target: To = map.target

    def applyWithCoverage(
        point: Point[From, D]
    ): Either[MapError, CoveredPoint[To, D]] =
      map(point).map(CoveredPoint(_, SupportOutcome.Covered))

  private final class Composite[
      A <: Frame[D],
      B <: Frame[D],
      C <: Frame[D],
      D <: Dim
  ](
      first: CoverageReportingMap[A, B, D],
      second: CoverageReportingMap[B, C, D]
  )(using @scala.annotation.unused dimension: Dimension[D]) extends CoverageReportingMap[A, C, D]:
    val source: A = first.source
    val target: C = second.target

    def applyWithCoverage(
        point: Point[A, D]
    ): Either[MapError, CoveredPoint[C, D]] =
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        intermediate <- first.applyWithCoverage(point)
        _ <- SpatialMap.validateResultPoint(first.target, intermediate.point)
        rebound <- Frame
          .alignOwners[D, B, B](first.target, second.source)
          .flatMap(_.pointToRight(intermediate.point))
          .left
          .map(MapError.Geometry.apply)
        result <- second.applyWithCoverage(rebound)
        _ <- SpatialMap.validateResultPoint(target, result.point)
      yield CoveredPoint(
        result.point,
        if intermediate.outcome == SupportOutcome.Covered then result.outcome
        else intermediate.outcome
      )

/** An absolute target-coordinate field sampled on a source-frame grid.
  *
  * This is an ordinary spatial map. Piecewise interpolation and finite
  * topology checks do not manufacture `SmoothMap` or `SmoothIso` capability.
  */
final class DenseMap[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim,
    R <: AnyRank
] private (
    val coordinates: ContinuousImage[
      ? <: SampleSpace[From, D],
      Double,
      R
    ],
    val target: To,
    val interpolation: Interpolation[Continuous],
    val boundary: CoordinateBoundaryPolicy
)(using private val dimension: Dimension[D])
    extends CoverageReportingMap[From, To, D]:
  val source: From = coordinates.frame
  val grid = coordinates.grid

  /** Evaluate and report whether the boundary policy contributed. With
    * `Reject` every success is covered; `Constant` and `PreserveSource`
    * report any evaluation whose stencil reached outside the samples.
    */
  def applyWithCoverage(
      point: Point[From, D]
  ): Either[MapError, CoveredPoint[To, D]] =
    for
      _ <- SpatialMap.validateSourcePoint(source, point)
      interpolated <- interpolate(point)
      (values, covered) = interpolated
      result <- Point
        .fromVector(target, values)
        .left
        .map(MapError.Geometry.apply)
      alignment <- Frame
        .alignOwners[D, target.type, To](target, target)
        .left
        .map(MapError.Geometry.apply)
      rebound <- alignment
        .pointToRight(result)
        .left
        .map(MapError.Geometry.apply)
    yield CoveredPoint(rebound, outcome(covered))

  private def outcome(covered: Boolean): SupportOutcome =
    if covered then SupportOutcome.Covered
    else
      boundary match
        case CoordinateBoundaryPolicy.PreserveSource =>
          SupportOutcome.SourcePreserved
        case _ =>
          SupportOutcome.ConstantFilled

  private def interpolate(
      point: Point[From, D]
  ): Either[MapError, (Vector[Double], Boolean)] =
    val values = Vector.newBuilder[Double]
    var covered = true
    var component = 0
    var failure = Option.empty[MapError]
    while component < dimension.rank && failure.isEmpty do
      SampledInterpolator.at(
        coordinates,
        point,
        Vector(component),
        interpolation,
        componentBoundary(point, component)
      ) match
        case Right(sample) =>
          values += sample.value
          covered = covered && sample.validity == Validity.Full
        case Left(_) =>
          failure = Some(MapError.OutsideDomain(point.coordinates))
      component += 1
    failure.toLeft(values.result() -> covered)

  /** Allocation-free linear evaluation for hot loops, present only when
    * this map interpolates linearly. It reproduces `applyWithCoverage`
    * arithmetic exactly.
    */
  private[field] lazy val linearSampler: Option[LinearCoordinateSampler] =
    if interpolation != Interpolation.Linear then None
    else
      val rank = dimension.rank
      val values = new Array[Double](coordinates.data.size)
      var flat = 0
      coordinates.data.foreachElement: value =>
        values(flat) = value
        flat += 1
      val inverse = grid.indexToFrame.inverse.matrix
      val frameToIndex = Array.tabulate(rank * (rank + 1))(entry =>
        inverse(entry / (rank + 1), entry % (rank + 1))
      )
      Some(new LinearCoordinateSampler(rank, grid.shape.toArray, values, frameToIndex, boundary))

  private def componentBoundary(
      point: Point[From, D],
      component: Int
  ): BoundaryPolicy[Double] =
    boundary match
      case CoordinateBoundaryPolicy.Reject =>
        BoundaryPolicy.Reject
      case CoordinateBoundaryPolicy.Constant(values) =>
        BoundaryPolicy.Constant(values(component))
      case CoordinateBoundaryPolicy.PreserveSource =>
        BoundaryPolicy.Constant(point.coordinates(component))

object DenseMap:
  /** Stable structural fingerprint for provider-owned dense maps, including
    * maps whose endpoint refinements have been safely erased.
    */
  def fingerprint[D <: Dim](
      map: SpatialMap[Frame[D], Frame[D], D]
  ): Option[String] =
    underlyingDense(map).map: dense =>
      var hash = MurmurHash3.stringHash(
        s"dense-map-v1|${dense.interpolation}|${dense.boundary}|${dense.grid.shape}"
      )
      val affine = dense.grid.indexToFrame.rowMajor.toArray
      var affineIndex = 0
      while affineIndex < affine.length do
        hash = MurmurHash3.mix(hash, affine(affineIndex).hashCode)
        affineIndex += 1
      var count = affine.length
      dense.coordinates.data.foreachElement: value =>
        hash = MurmurHash3.mix(hash, value.hashCode)
        count += 1
      s"dense-map-v1:${java.lang.Integer.toHexString(MurmurHash3.finalizeHash(hash, count))}"

  def isDense[D <: Dim](
      map: SpatialMap[Frame[D], Frame[D], D]
  ): Boolean =
    underlyingDense(map).nonEmpty

  private def underlyingDense[D <: Dim](
      map: SpatialMap[Frame[D], Frame[D], D]
  ): Option[DenseMap[?, ?, D, ?]] =
    map match
      case dense: DenseMap[?, ?, D @unchecked, ?] => Some(dense)
      case erased: FrameErasedMap[D @unchecked] =>
        erased.underlying match
          case dense: DenseMap[?, ?, D @unchecked, ?] => Some(dense)
          case _ => None
      case _ => None

  def fromCoordinates[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      coordinates: ContinuousImage[
        ? <: SampleSpace[From, D],
        Double,
        R
      ],
      target: To,
      interpolation: Interpolation[Continuous] = Interpolation.Linear,
      boundary: CoordinateBoundaryPolicy = CoordinateBoundaryPolicy.Reject
  )(using dimension: Dimension[D])
      : Either[FieldError, DenseMap[From, To, D, R]] =
    for
      _ <- DenseField.create[From, D, DisplacementKind, R](coordinates)
      _ <- validateBoundary(boundary, dimension.rank)
    yield new DenseMap(
      coordinates,
      target,
      interpolation,
      boundary
    )

  private[field] def validateBoundary(
      boundary: CoordinateBoundaryPolicy,
      rank: Int
  ): Either[FieldError, Unit] =
    boundary match
      case CoordinateBoundaryPolicy.Constant(values)
          if values.length != rank =>
        Left(FieldError.InvalidBoundaryCoordinates(rank, values.length))
      case CoordinateBoundaryPolicy.Constant(values) =>
        values.zipWithIndex.collectFirst {
          case (value, component) if !value.isFinite =>
            FieldError.NonFiniteBoundaryCoordinate(component, value)
        }.toLeft(())
      case _ => Right(())

/** Primitive linear sampler over a dense map's absolute coordinates. */
private[field] final class LinearCoordinateSampler(
    rank: Int,
    shape: Array[Int],
    values: Array[Double],
    frameToIndex: Array[Double],
    boundary: CoordinateBoundaryPolicy
):
  private val continuous = new Array[Double](rank)
  private val lower = new Array[Int](rank)
  private val fraction = new Array[Double](rank)

  /** Evaluate at physical `point` into `out`. Returns `None` when the
    * `Reject` policy rejects the point, otherwise its support outcome.
    * Not thread-safe: the sampler owns scratch arrays.
    */
  def sample(point: Array[Double], out: Array[Double]): Option[SupportOutcome] =
    var row = 0
    var finite = true
    while row < rank do
      var sum = frameToIndex(row * (rank + 1) + rank)
      var column = 0
      while column < rank do
        sum += frameToIndex(row * (rank + 1) + column) * point(column)
        column += 1
      finite = finite && sum.isFinite
      continuous(row) = sum
      lower(row) = math.floor(sum).toInt
      fraction(row) = sum - lower(row)
      row += 1
    if !finite then None
    else
      java.util.Arrays.fill(out, 0.0)
      var insideWeight = 0.0
      var rejected = false
      var corner = 0
      while corner < (1 << rank) && !rejected do
        var weight = 1.0
        var inside = true
        var linear = 0
        var axis = 0
        while axis < rank do
          val upper = ((corner >> axis) & 1) == 1
          weight *= (if upper then fraction(axis) else 1.0 - fraction(axis))
          val index = lower(axis) + (if upper then 1 else 0)
          inside = inside && index >= 0 && index < shape(axis)
          linear = linear * shape(axis) + index
          axis += 1
        if weight > 0.0 then
          if inside then
            var component = 0
            while component < rank do
              out(component) += weight * values(linear * rank + component)
              component += 1
            insideWeight += weight
          else
            boundary match
              case CoordinateBoundaryPolicy.Reject => rejected = true
              case CoordinateBoundaryPolicy.Constant(fill) =>
                var component = 0
                while component < rank do
                  out(component) += weight * fill(component)
                  component += 1
              case CoordinateBoundaryPolicy.PreserveSource =>
                var component = 0
                while component < rank do
                  out(component) += weight * point(component)
                  component += 1
        corner += 1
      if rejected then None
      else if insideWeight >= 1.0 - 1e-12 then Some(SupportOutcome.Covered)
      else
        boundary match
          case CoordinateBoundaryPolicy.PreserveSource =>
            Some(SupportOutcome.SourcePreserved)
          case _ => Some(SupportOutcome.ConstantFilled)
