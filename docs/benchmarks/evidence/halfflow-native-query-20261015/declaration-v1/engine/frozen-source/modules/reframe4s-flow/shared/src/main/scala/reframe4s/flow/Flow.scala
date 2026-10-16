package reframe4s.flow

import image4s.BoundaryPolicy
import image4s.Sampled
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import reframe4s.core.TopologyCertificate
import reframe4s.core.TopologyScope
import reframe4s.field.DenseMap
import reframe4s.field.FieldError
import reframe4s.field.TopologyAssessmentError
import reframe4s.field.TopologyAssessor
import reframe4s.field.Velocity
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.LatticeIndex
import image4s.geometry.Point
import image4s.geometry.Vec

sealed trait FlowError derives CanEqual:
  def message: String

object FlowError:
  final case class InvalidStepCount(value: Int) extends FlowError:
    val message: String = s"integration step count must be positive, got $value"

  final case class InvalidDuration(value: Double) extends FlowError:
    val message: String =
      s"integration duration must be finite and non-negative, got $value"

  final case class Field(error: FieldError) extends FlowError:
    val message: String = error.message

  object Geometry:
    def apply(error: GeometryError | spatial4s.SpatialError): Geometry =
      new Geometry(image4s.geometry.GeometryError.fromCoordinate(error))

  final case class Geometry(error: GeometryError) extends FlowError:
    val message: String = error.message

  final case class InsufficientSupport(
      spatialIndex: Vector[Int],
      step: Int
  ) extends FlowError:
    val message: String =
      s"velocity support ended at ${spatialIndex.mkString("(", ",", ")")} " +
        s"during integration step $step"

  final case class NumericalDivergence(
      spatialIndex: Vector[Int],
      step: Int
  ) extends FlowError:
    val message: String =
      s"flow integration diverged at ${spatialIndex.mkString("(", ",", ")")} " +
        s"during step $step"

  final case class InvalidTopology(error: TopologyAssessmentError)
      extends FlowError:
    val message: String = error.message

final class IntegrationSettings private (
    val steps: Int,
    val duration: Double
)

object IntegrationSettings:
  def create(
      steps: Int,
      duration: Double = 1.0
  ): Either[FlowError, IntegrationSettings] =
    if steps <= 0 then Left(FlowError.InvalidStepCount(steps))
    else if !duration.isFinite || duration < 0.0 then
      Left(FlowError.InvalidDuration(duration))
    else Right(new IntegrationSettings(steps, duration))

final case class IntegrationDiagnostics(
    steps: Int,
    duration: Double,
    evaluatedPoints: Long,
    maximumStepLength: Double
) derives CanEqual

sealed trait TopologyAssessment[
    F <: Frame[D],
    D <: Dim
]

object TopologyAssessment:
  final class NotRequested[F <: Frame[D], D <: Dim]
      extends TopologyAssessment[F, D]

  final case class Certified[F <: Frame[D], D <: Dim](
      certificate: TopologyCertificate[F, D]
  ) extends TopologyAssessment[F, D]

final case class FlowResult[
    F <: Frame[D],
    D <: Dim,
    R <: AnyRank
](
    endpoint: DenseMap[F, F, D, R],
    diagnostics: IntegrationDiagnostics,
    topology: TopologyAssessment[F, D]
)

/** Immutable explicit-Euler plan for a stationary physical velocity field.
  *
  * This intentionally names the implemented numerical method. More accurate
  * scaling-and-squaring and time-varying integrators can implement the same
  * result contract without upgrading numerical endpoints to `SmoothIso`.
  */
final class ExplicitEuler[
    F <: Frame[D],
    D <: Dim,
    R <: AnyRank
] private (
    val velocity: Velocity[F, D, R],
    val settings: IntegrationSettings
)(using private val dimension: Dimension[D]):
  def run(
      topologyScope: Option[TopologyScope[F, D]] = None
  ): Either[FlowError, FlowResult[F, D, R]] =
    val grid = velocity.grid
    val rank = dimension.rank
    var failure = Option.empty[FlowError]
    var maximumStepLength = 0.0
    val values =
      NDArray.build[Double, R](velocity.samples.data.shape) { builder =>
        var spatialLinear = 0
        val spatialSize = grid.shape.product
        while spatialLinear < spatialSize && failure.isEmpty do
          val spatialIndex = decode(spatialLinear, grid.shape)
          val start =
            for
              index <- LatticeIndex
                .fromVector[D](spatialIndex)
                .left
                .map(FlowError.Geometry.apply)
              point <- grid
                .pointAt(index)
                .left
                .map(FlowError.Geometry.apply)
            yield point
          start match
            case Left(error) =>
              failure = Some(error)
            case Right(point) =>
              integratePoint(point, spatialIndex) match
                case Left(error) =>
                  failure = Some(error)
                case Right((coordinates, localMaximum)) =>
                  maximumStepLength =
                    math.max(maximumStepLength, localMaximum)
                  var component = 0
                  while component < rank do
                    builder.writeLinear(
                      spatialLinear * rank + component,
                      coordinates(component)
                    )
                    component += 1
          spatialLinear += 1
      }
    failure match
      case Some(error) => Left(error)
      case None =>
        for
          components <- Sampled
            .continuous(grid, velocity.samples.nonSpatialAxes, values)
            .left
            .map(error => FlowError.Field(FieldError.Image(error)))
          endpoint <- DenseMap
            .fromCoordinates(components, velocity.frame)
            .left
            .map(FlowError.Field.apply)
          assessment <- topologyScope match
            case None =>
              Right(new TopologyAssessment.NotRequested[F, D])
            case Some(scope) =>
              TopologyAssessor
                .certify(endpoint, scope)
                .left
                .map(FlowError.InvalidTopology.apply)
                .map(TopologyAssessment.Certified.apply)
        yield FlowResult(
          endpoint,
          IntegrationDiagnostics(
            settings.steps,
            settings.duration,
            grid.shape.product.toLong,
            maximumStepLength
          ),
          assessment
        )

  private def integratePoint(
      start: Point[F, D],
      spatialIndex: Vector[Int]
  ): Either[FlowError, (Vector[Double], Double)] =
    var point = start
    var step = 0
    var maximumStepLength = 0.0
    val deltaTime = settings.duration / settings.steps.toDouble
    var failure = Option.empty[FlowError]
    while step < settings.steps && failure.isEmpty do
      velocity.at(point, BoundaryPolicy.Reject) match
        case Left(_) =>
          failure = Some(FlowError.InsufficientSupport(spatialIndex, step))
        case Right(vector) =>
          val increment =
            vector.coordinates.map(_ * deltaTime)
          val length =
            math.sqrt(increment.iterator.map(value => value * value).sum)
          maximumStepLength = math.max(maximumStepLength, length)
          if increment.exists(value => !value.isFinite) then
            failure = Some(
              FlowError.NumericalDivergence(spatialIndex, step)
            )
          else
            Vec.fromVector(point.frame, increment) match
              case Left(_) =>
                failure = Some(
                  FlowError.NumericalDivergence(spatialIndex, step)
                )
              case Right(delta) =>
                point.addChecked(delta) match
                  case Left(_) =>
                    failure = Some(
                      FlowError.NumericalDivergence(spatialIndex, step)
                    )
                  case Right(next) =>
                    point = next
      step += 1
    failure.toLeft(point.coordinates -> maximumStepLength)

  private def decode(linear: Int, shape: Vector[Int]): Vector[Int] =
    val result = Array.fill(shape.length)(0)
    var remainder = linear
    var axis = shape.length - 1
    while axis >= 0 do
      result(axis) = remainder % shape(axis)
      remainder /= shape(axis)
      axis -= 1
    result.toVector

object ExplicitEuler:
  def compile[
      F <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      velocity: Velocity[F, D, R],
      settings: IntegrationSettings
  )(using Dimension[D]): ExplicitEuler[F, D, R] =
    new ExplicitEuler(velocity, settings)
