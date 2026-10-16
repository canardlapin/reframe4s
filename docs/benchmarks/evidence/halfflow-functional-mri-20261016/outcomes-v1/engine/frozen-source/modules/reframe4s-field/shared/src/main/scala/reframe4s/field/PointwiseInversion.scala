package reframe4s.field

import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point
import reframe4s.core.AffineMap
import reframe4s.core.MapError
import reframe4s.core.SpatialMap

/** Terminal outcome of one query; no outcome implies global invertibility. */
enum PointwiseInverseStatus derives CanEqual:
  case Converged
  case MaxIterations
  case Diverged
  case LeftSupport
  case Failure(error: MapError)

/** An evaluated, covered iterate and its Euclidean full-map residual. */
final class PointwiseInverseSample[F <: Frame[D], D <: Dim] private[field] (
    val point: Point[F, D],
    val residual: Double
)

/** Evidence for one inverse query. `best` is absent if no finite, covered
  * residual was measured. On failure it is diagnostic, not a fallback.
  * `iterations` counts fixed-point updates, including an update that left
  * support; the initial evaluation uses zero updates.
  */
final class PointwiseInverseResult[S <: Frame[D], T <: Frame[D], D <: Dim] private[field] (
    val query: Point[S, D],
    val best: Option[PointwiseInverseSample[T, D]],
    val iterations: Int,
    val status: PointwiseInverseStatus
)

/** Query-local numerical estimates, deliberately not a SpatialMap or SmoothIso.
  * Scratch state is local to each call. Thread safety also requires the
  * supplied map to support concurrent evaluation.
  */
final class PointwiseInverse[S <: Frame[D], T <: Frame[D], D <: Dim] private[field] (
    val source: S,
    val target: T,
    val settings: InversionSettings,
    undoAffine: Vector[Double] => Either[MapError, Vector[Double]],
    evaluate: Point[T, D] => Either[MapError, (Point[S, D], Vector[Double])]
)(using dimension: Dimension[D]):
  /** Solve `pull(x) = query`, starting at the query with any declared affine
    * removed. No inverse lattice, warm start or automatic retry is used.
    */
  def at(query: Point[S, D]): PointwiseInverseResult[S, T, D] =
    var best = Option.empty[PointwiseInverseSample[T, D]]
    var iterations = 0
    var status = Option.empty[PointwiseInverseStatus]
    val prepared =
      SpatialMap.validateSourcePoint(source, query).flatMap(_ => undoAffine(query.coordinates))
    prepared match
      case Left(error) => status = Some(PointwiseInverseStatus.Failure(error))
      case Right(displacementTarget) =>
        var coordinates = displacementTarget
        var initialResidual = Option.empty[Double]
        while status.isEmpty do
          if coordinates.exists(value => !value.isFinite) then
            status = Some(PointwiseInverseStatus.Diverged)
          else
            val evaluated =
              for
                point <- Point.fromVector(target, coordinates)
                  .flatMap(value =>
                    Frame.alignOwners[D, target.type, T](target, target)
                      .flatMap(_.pointToRight(value))
                  ).left.map(MapError.Geometry.apply)
                value <- evaluate(point)
                _ <- SpatialMap.validateResultPoint(source, value._1)
              yield (point, value._1, value._2)
            evaluated match
              case Left(MapError.OutsideDomain(_)) =>
                status = Some(PointwiseInverseStatus.LeftSupport)
              case Left(error) => status = Some(PointwiseInverseStatus.Failure(error))
              case Right((point, mapped, displacementValue)) =>
                // hypot avoids overflow/underflow when squaring coordinates.
                var residual = 0.0
                var axis = 0
                while axis < dimension.rank do
                  residual = math.hypot(residual, mapped.coordinates(axis) - query.coordinates(axis))
                  axis += 1
                if initialResidual.isEmpty then initialResidual = Some(residual)
                if residual.isFinite && best.forall(_.residual > residual) then
                  best = Some(new PointwiseInverseSample(point, residual))
                if residual <= settings.tolerance then
                  status = Some(PointwiseInverseStatus.Converged)
                else if !residual.isFinite || residual / initialResidual.get > settings.divergenceRatio then
                  status = Some(PointwiseInverseStatus.Diverged)
                else if iterations >= settings.maximumIterations then
                  status = Some(PointwiseInverseStatus.MaxIterations)
                else
                  coordinates = Vector.tabulate(dimension.rank)(axis =>
                    coordinates(axis) - (displacementValue(axis) - displacementTarget(axis))
                  )
                  iterations += 1
    new PointwiseInverseResult(query, best, iterations, status.get)

/** Explicit fixed-point inversion declarations.
  *
  * The displacement stage must return absolute coordinates `x + d(x)`, in
  * the same coordinate basis and units as its input. This is a caller
  * declaration, not a tested contraction or an invertibility certificate.
  * Coverage must be reported explicitly: filled/extended samples never count
  * as convergence. Ordinary maps can be explicitly lifted by the caller when
  * every successful evaluation really is covered.
  */
object PointwiseInversion:
  /** Reuses NumericalInversion's settings and update `x <- x - (pull(x)-y)`.
    * Convergence depends on the displacement and identity initialization.
    */
  def displacement[T <: Frame[D], S <: Frame[D], D <: Dim](
      pull: CoverageReportingMap[T, S, D],
      settings: InversionSettings
  )(using Dimension[D]): PointwiseInverse[S, T, D] =
    new PointwiseInverse(
      pull.target,
      pull.source,
      settings,
      coordinates => Right(coordinates),
      point => covered(pull, point).map(value => (value, value.coordinates))
    )

  /** Declare `pull(x) = affine(displacement(x))`. Remove the affine
    * analytically and iterate only the displacement. Tolerance, divergence
    * and best-iterate selection use `|pull(x) - query|` in the final target's
    * coordinate units, including affine scale/shear. No inverse is sampled.
    */
  def displacementThenAffine[
      T <: Frame[D],
      U <: Frame[D],
      S <: Frame[D],
      D <: Dim
  ](
      displacement: CoverageReportingMap[T, U, D],
      affine: AffineMap[U, S, D],
      settings: InversionSettings
  )(using Dimension[D]): Either[MapError, PointwiseInverse[S, T, D]] =
    Frame.alignOwners[D, U, U](displacement.target, affine.source)
      .left.map(MapError.Geometry.apply).map(alignment =>
        val inverse = affine.operator.inverse
        new PointwiseInverse(
          affine.target,
          displacement.source,
          settings,
          coordinates => inverse(coordinates).left.map(MapError.Geometry.apply),
          point =>
            for
              value <- covered(displacement, point)
              rebound <- alignment.pointToRight(value).left.map(MapError.Geometry.apply)
              mapped <- affine(rebound)
            yield (mapped, value.coordinates)
        )
      )

  private def covered[T <: Frame[D], S <: Frame[D], D <: Dim](
      pull: CoverageReportingMap[T, S, D],
      point: Point[T, D]
  ): Either[MapError, Point[S, D]] =
    for
      _ <- SpatialMap.validateSourcePoint(pull.source, point)
      result <- pull.applyWithCoverage(point)
      _ <- SpatialMap.validateResultPoint(pull.target, result.point)
      value <-
        if result.outcome == SupportOutcome.Covered then Right(result.point)
        else Left(MapError.OutsideDomain(point.coordinates))
    yield value
