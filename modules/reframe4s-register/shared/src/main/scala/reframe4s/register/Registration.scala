package reframe4s.register

import reframe4s.core.CertifiedBidirectionalPair
import reframe4s.core.EvidenceError
import reframe4s.core.SmoothIso
import reframe4s.core.SpatialMap
import image4s.geometry.Dim
import image4s.geometry.Frame
import image4s.geometry.GeometryError

trait ValueProblem[-P]:
  def value(parameters: P): Either[RegistrationFailure, Double]

final case class ValueGradient[+Delta](
    value: Double,
    gradient: Delta
)

trait GradientProblem[-P, +Delta] extends ValueProblem[P]:
  def valueAndGradient(
      parameters: P
  ): Either[RegistrationFailure, ValueGradient[Delta]]

trait ResidualProblem[-P, Delta, Residual]:
  def residual(parameters: P): Either[RegistrationFailure, Residual]
  def jvp(
      parameters: P,
      delta: Delta
  ): Either[RegistrationFailure, Residual]
  def vjp(
      parameters: P,
      residual: Residual
  ): Either[RegistrationFailure, Delta]

trait Retraction[P, -Delta]:
  def retract(
      parameters: P,
      delta: Delta
  ): Either[RegistrationFailure, P]

enum Termination derives CanEqual:
  case Converged
  case Stationary
  case AcceptedStepLimit
  case AttemptLimit
  case ObjectiveConverged
  case StepConverged
  case GradientConverged
  case IterationLimit
  case RejectedStepLimit

enum ConvergenceFailure derives CanEqual:
  case IterationLimit
  case AttemptLimit
  case Diverged
  case NumericalFailure

sealed trait RegistrationFailure derives CanEqual:
  def message: String

object RegistrationFailure:
  final case class InvalidObjective(value: Double)
      extends RegistrationFailure:
    val message: String = s"objective must be finite, got $value"

  final case class InsufficientSupport(available: Long, required: Long)
      extends RegistrationFailure:
    val message: String =
      s"registration has $available supported samples but requires $required"

  final case class Convergence(outcome: ConvergenceFailure)
      extends RegistrationFailure:
    val message: String = s"registration failed to converge: $outcome"

  final case class InvalidTopology(error: EvidenceError)
      extends RegistrationFailure:
    val message: String = error.message

  object Geometry:
    def apply(error: GeometryError | spatial4s.SpatialError): Geometry =
      new Geometry(image4s.geometry.GeometryError.fromCoordinate(error))

  final case class Geometry(error: GeometryError)
      extends RegistrationFailure:
    val message: String = error.message

  final case class InvalidReportCount(field: ReportCount, value: Int)
      extends RegistrationFailure:
    val message: String = s"$field must be non-negative, got $value"

  final case class RejectedCandidate(reason: CandidateRejection)
      extends RegistrationFailure:
    val message: String = s"registration candidate rejected: $reason"

enum CandidateRejection derives CanEqual:
  case ObjectiveIncreased
  case InvalidTopology
  case InsufficientSupport
  case NumericalFailure

enum ReportCount derives CanEqual:
  case Iterations, Attempts, AcceptedSteps

final class OptimizationReport private (
    val initialObjective: Double,
    val finalObjective: Double,
    val iterations: Int,
    val attempts: Int,
    val acceptedSteps: Int,
    val termination: Termination
):
  val rejectedSteps: Int = attempts - acceptedSteps

object OptimizationReport:
  def create(
      initialObjective: Double,
      finalObjective: Double,
      iterations: Int,
      attempts: Int,
      acceptedSteps: Int,
      termination: Termination
  ): Either[RegistrationFailure, OptimizationReport] =
    if !initialObjective.isFinite then
      Left(RegistrationFailure.InvalidObjective(initialObjective))
    else if !finalObjective.isFinite then
      Left(RegistrationFailure.InvalidObjective(finalObjective))
    else if iterations < 0 then
      Left(
        RegistrationFailure.InvalidReportCount(
          ReportCount.Iterations,
          iterations
        )
      )
    else if attempts < 0 then
      Left(
        RegistrationFailure.InvalidReportCount(
          ReportCount.Attempts,
          attempts
        )
      )
    else if acceptedSteps < 0 || acceptedSteps > attempts then
      Left(
        RegistrationFailure.InvalidReportCount(
          ReportCount.AcceptedSteps,
          acceptedSteps
        )
      )
    else
      Right(
        new OptimizationReport(
          initialObjective,
          finalObjective,
          iterations,
          attempts,
          acceptedSteps,
          termination
        )
      )

sealed trait RegistrationResult[
    Fixed <: Frame[D],
    Moving <: Frame[D],
    D <: Dim
]:
  type FixedToMoving <: SpatialMap[Fixed, Moving, D]
  val fixedToMoving: FixedToMoving
  val report: OptimizationReport

sealed trait BidirectionalRegistrationResult[
    Fixed <: Frame[D],
    Moving <: Frame[D],
    D <: Dim
] extends RegistrationResult[Fixed, Moving, D]:
  type MovingToFixed <: SpatialMap[Moving, Fixed, D]
  val movingToFixed: MovingToFixed

object RegistrationResult:
  def oneWay[
      Fixed <: Frame[D],
      Moving <: Frame[D],
      D <: Dim,
      FM <: SpatialMap[Fixed, Moving, D]
  ](
      fixedToMoving: FM,
      report: OptimizationReport
  ): Either[
        RegistrationFailure,
        RegistrationResult[Fixed, Moving, D] {
          type FixedToMoving = FM
        }
      ] =
    Right(new OneWayResult(fixedToMoving, report))

  def exact[
      Fixed <: Frame[D],
      Moving <: Frame[D],
      D <: Dim,
      FM <: SmoothIso[Fixed, Moving, D]
  ](
      fixedToMoving: FM,
      report: OptimizationReport
  ): Either[
        RegistrationFailure,
        BidirectionalRegistrationResult[Fixed, Moving, D] {
          type FixedToMoving = FM
          type MovingToFixed = SmoothIso[Moving, Fixed, D]
        }
      ] =
    Right(new ExactResult(fixedToMoving, report))

  def independentlyEstimated[
      Fixed <: Frame[D],
      Moving <: Frame[D],
      D <: Dim,
      FM <: SpatialMap[Fixed, Moving, D],
      MF <: SpatialMap[Moving, Fixed, D]
  ](
      fixedToMoving: FM,
      movingToFixed: MF,
      report: OptimizationReport
  ): Either[
        RegistrationFailure,
        BidirectionalRegistrationResult[Fixed, Moving, D] {
          type FixedToMoving = FM
          type MovingToFixed = MF
        }
      ] =
    for
      _ <- image4s.geometry.Frame
        .align(fixedToMoving.source, movingToFixed.target)
        .left
        .map(RegistrationFailure.Geometry.apply)
      _ <- image4s.geometry.Frame
        .align(fixedToMoving.target, movingToFixed.source)
        .left
        .map(RegistrationFailure.Geometry.apply)
    yield new IndependentResult(
      fixedToMoving,
      movingToFixed,
      report
    )

  def certified[
      Fixed <: Frame[D],
      Moving <: Frame[D],
      D <: Dim
  ](
      pair: CertifiedBidirectionalPair[Fixed, Moving, D],
      report: OptimizationReport
  ): Either[
        RegistrationFailure,
        BidirectionalRegistrationResult[Fixed, Moving, D] {
          type FixedToMoving = SpatialMap[Fixed, Moving, D]
          type MovingToFixed = SpatialMap[Moving, Fixed, D]
        }
      ] =
    independentlyEstimated(pair.toTarget, pair.toSource, report)

  private final class OneWayResult[
      Fixed <: Frame[D],
      Moving <: Frame[D],
      D <: Dim,
      FM <: SpatialMap[Fixed, Moving, D]
  ](
      val fixedToMoving: FM,
      val report: OptimizationReport
  ) extends RegistrationResult[Fixed, Moving, D]:
    type FixedToMoving = FM

  private final class ExactResult[
      Fixed <: Frame[D],
      Moving <: Frame[D],
      D <: Dim,
      FM <: SmoothIso[Fixed, Moving, D]
  ](
      val fixedToMoving: FM,
      val report: OptimizationReport
  ) extends BidirectionalRegistrationResult[Fixed, Moving, D]:
    type FixedToMoving = FM
    type MovingToFixed = SmoothIso[Moving, Fixed, D]
    val movingToFixed: SmoothIso[Moving, Fixed, D] =
      fixedToMoving.inverse

  private final class IndependentResult[
      Fixed <: Frame[D],
      Moving <: Frame[D],
      D <: Dim,
      FM <: SpatialMap[Fixed, Moving, D],
      MF <: SpatialMap[Moving, Fixed, D]
  ](
      val fixedToMoving: FM,
      val movingToFixed: MF,
      val report: OptimizationReport
  ) extends BidirectionalRegistrationResult[Fixed, Moving, D]:
    type FixedToMoving = FM
    type MovingToFixed = MF
