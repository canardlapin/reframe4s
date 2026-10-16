package reframe4s.halfflow

/** Errors raised while adapting completed forward/reverse search observations. */
enum BasinBridgeSearchAdapterError:
  case EmptyObservations
  case InvalidObservation(index: Int, error: BasinBridgeError)

  def message: String =
    this match
      case EmptyObservations =>
        "BasinBridge search adapter requires at least one observation"
      case InvalidObservation(index, error) =>
        s"BasinBridge search observation $index is invalid: ${error.message}"

/**
  * One completed forward/reverse search result at a fixed physical point.
  *
  * `fixed` is q, `moving` is p, and `reverseFixed` is qBack from a search run
  * in the opposite direction. The cycle error is derived from qBack and q so
  * callers cannot report a cycle score that disagrees with the reverse point.
  * This value deliberately contains search output, not image or block-search
  * machinery; a native matcher can populate it without changing the
  * correspondence contract.
  */
final class BasinBridgeSearchObservation private (
    val fixed: BasinBridgePoint,
    val moving: BasinBridgePoint,
    val reverseFixed: BasinBridgePoint,
    val confidenceEvidence: BasinBridgeConfidenceEvidence
):
  def cycleErrorMm: Double = confidenceEvidence.cycleErrorMm

  def forwardDisplacementDistribution: Vector[Double] =
    confidenceEvidence.forwardDisplacementDistribution

  def reverseDisplacementDistribution: Vector[Double] =
    confidenceEvidence.reverseDisplacementDistribution

  def priorConfidence: Double = confidenceEvidence.priorConfidence

object BasinBridgeSearchObservation:
  /** Construct an observation and derive its cycle error from the reverse point. */
  def make(
      fixed: BasinBridgePoint,
      moving: BasinBridgePoint,
      reverseFixed: BasinBridgePoint,
      forwardDisplacementDistribution: Vector[Double],
      reverseDisplacementDistribution: Vector[Double],
      priorConfidence: Double = 1.0
  ): Either[BasinBridgeError, BasinBridgeSearchObservation] =
    BasinBridgeConfidenceEvidence
      .make(
        forwardDisplacementDistribution,
        reverseDisplacementDistribution,
        reverseFixed.distanceTo(fixed),
        priorConfidence
      )
      .map(evidence =>
        new BasinBridgeSearchObservation(fixed, moving, reverseFixed, evidence)
      )

/** Aggregate diagnostics kept separate from matcher-accuracy claims. */
final case class BasinBridgeSearchDiagnostics(
    inputCount: Int,
    emittedCount: Int,
    minimumEffectiveWeight: Double,
    meanEffectiveWeight: Double,
    maximumEffectiveWeight: Double,
    minimumCycleErrorMm: Double,
    meanCycleErrorMm: Double,
    maximumCycleErrorMm: Double
)

/** Correspondences and observations-only diagnostics from one adapter call. */
final case class BasinBridgeSearchResult(
    correspondences: Vector[BasinBridgeCorrespondence],
    diagnostics: BasinBridgeSearchDiagnostics
)

/**
  * Converts already-computed forward/reverse search outputs into the shared
  * BasinBridge correspondence contract.
  *
  * The native block matcher and external search implementations both enter at
  * this boundary. Keeping it typed preserves the same sign, cycle, and
  * confidence laws across sources. Every valid observation is emitted,
  * including weak ones.
  */
object BasinBridgeSearchAdapter:
  def fromForwardReverse(
      observations: Vector[BasinBridgeSearchObservation],
      config: BasinBridgeConfidenceConfig = BasinBridgeConfidenceConfig.default
  ): Either[BasinBridgeSearchAdapterError, BasinBridgeSearchResult] =
    if observations.isEmpty then Left(BasinBridgeSearchAdapterError.EmptyObservations)
    else
      val output = Vector.newBuilder[BasinBridgeCorrespondence]
      var failure = Option.empty[BasinBridgeSearchAdapterError]
      var index = 0
      var minimumWeight = Double.PositiveInfinity
      var meanWeight = 0.0
      var maximumWeight = Double.NegativeInfinity
      var minimumCycleError = Double.PositiveInfinity
      var meanCycleError = 0.0
      var maximumCycleError = Double.NegativeInfinity

      while index < observations.length && failure.isEmpty do
        val observation = observations(index)
        BasinBridgeCorrespondence.fromEvidence(
          observation.fixed,
          observation.moving,
          observation.confidenceEvidence,
          config
        ) match
          case Left(error) =>
            failure = Some(BasinBridgeSearchAdapterError.InvalidObservation(index, error))
          case Right(correspondence) =>
            output += correspondence
            val emitted = index + 1
            val weight = correspondence.confidence
            val cycleError = observation.cycleErrorMm
            minimumWeight = math.min(minimumWeight, weight)
            meanWeight += (weight - meanWeight) / emitted.toDouble
            maximumWeight = math.max(maximumWeight, weight)
            minimumCycleError = math.min(minimumCycleError, cycleError)
            meanCycleError += (cycleError - meanCycleError) / emitted.toDouble
            maximumCycleError = math.max(maximumCycleError, cycleError)
        index += 1

      failure match
        case Some(error) => Left(error)
        case None =>
          Right(
            BasinBridgeSearchResult(
              output.result(),
              BasinBridgeSearchDiagnostics(
                inputCount = observations.length,
                emittedCount = observations.length,
                minimumEffectiveWeight = minimumWeight,
                meanEffectiveWeight = meanWeight,
                maximumEffectiveWeight = maximumWeight,
                minimumCycleErrorMm = minimumCycleError,
                meanCycleErrorMm = meanCycleError,
                maximumCycleErrorMm = maximumCycleError
              )
            )
          )
