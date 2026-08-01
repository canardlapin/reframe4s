package reframe4s.halfflow

import reframe4s.core.CertifiedBidirectionalPair
import reframe4s.core.TopologyCertificate
import image4s.geometry.Dim
import image4s.geometry.Frame
import reframe4s.register.BidirectionalRegistrationResult
import reframe4s.register.OptimizationReport
import reframe4s.register.RegistrationFailure
import reframe4s.register.RegistrationResult

enum EvidenceStatus derives CanEqual:
  case NotRun
  case Failing
  case Passing

sealed trait ExperimentalError derives CanEqual:
  def message: String

object ExperimentalError:
  final case class InvalidPairCount(value: Int) extends ExperimentalError:
    val message: String =
      s"anatomical evidence pair count must be non-negative, got $value"

  final case class InvalidRequiredPairCount(value: Int)
      extends ExperimentalError:
    val message: String =
      s"required anatomical pair count must be positive, got $value"

  final case class EmptyDatasetId(value: String) extends ExperimentalError:
    val message: String =
      "anatomical evidence dataset id must be non-empty and trimmed"

  final case class EvidenceNotAdmissible(
      status: EvidenceStatus,
      observedPairs: Int,
      requiredPairs: Int
  ) extends ExperimentalError:
    val message: String =
      s"HalfFlow remains experimental: evidence is $status with " +
        s"$observedPairs/$requiredPairs required anatomical pairs"

  final case class Registration(error: RegistrationFailure)
      extends ExperimentalError:
    val message: String = error.message

final class ExperimentalCapabilityLedger private (
    val datasetId: String,
    val observedPairs: Int,
    val requiredPairs: Int,
    val status: EvidenceStatus
):
  def admitsStableExport: Boolean =
    status == EvidenceStatus.Passing && observedPairs >= requiredPairs

object ExperimentalCapabilityLedger:
  def create(
      datasetId: String,
      observedPairs: Int,
      requiredPairs: Int,
      status: EvidenceStatus
  ): Either[ExperimentalError, ExperimentalCapabilityLedger] =
    val normalized = datasetId.trim
    if normalized.isEmpty || normalized != datasetId then
      Left(ExperimentalError.EmptyDatasetId(datasetId))
    else if observedPairs < 0 then
      Left(ExperimentalError.InvalidPairCount(observedPairs))
    else if requiredPairs <= 0 then
      Left(ExperimentalError.InvalidRequiredPairCount(requiredPairs))
    else
      Right(
        new ExperimentalCapabilityLedger(
          datasetId,
          observedPairs,
          requiredPairs,
          status
        )
      )

/** Two independently integrated half-time endpoints.
  *
  * No inverse relationship is implied by this container.
  */
final case class ExperimentalHalfStep[Positive, Negative](
    positiveEndpoint: Positive,
    negativeEndpoint: Negative
)

final case class HalfFlowCandidate[
    Fixed <: Frame[D],
    Moving <: Frame[D],
    D <: Dim
](
    pair: CertifiedBidirectionalPair[Fixed, Moving, D],
    fixedTopology: TopologyCertificate[Fixed, D],
    movingTopology: TopologyCertificate[Moving, D],
    report: OptimizationReport
)

object HalfFlowAdmission:
  def admit[
      Fixed <: Frame[D],
      Moving <: Frame[D],
      D <: Dim
  ](
      candidate: HalfFlowCandidate[Fixed, Moving, D],
      ledger: ExperimentalCapabilityLedger
  ): Either[
        ExperimentalError,
        BidirectionalRegistrationResult[Fixed, Moving, D] {
          type FixedToMoving =
            reframe4s.core.SpatialMap[Fixed, Moving, D]
          type MovingToFixed =
            reframe4s.core.SpatialMap[Moving, Fixed, D]
        }
      ] =
    if !ledger.admitsStableExport then
      Left(
        ExperimentalError.EvidenceNotAdmissible(
          ledger.status,
          ledger.observedPairs,
          ledger.requiredPairs
        )
      )
    else
      RegistrationResult
        .certified(candidate.pair, candidate.report)
        .left
        .map(ExperimentalError.Registration.apply)
