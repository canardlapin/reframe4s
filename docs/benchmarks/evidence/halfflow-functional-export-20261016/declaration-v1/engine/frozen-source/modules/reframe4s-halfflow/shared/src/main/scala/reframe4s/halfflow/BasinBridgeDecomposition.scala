package reframe4s.halfflow

/** The three frozen B4 decision lanes. */
enum BasinBridgeDecompositionLane:
  case OracleToBridge
  case OracleToBridgeToHalfFlow
  case BlockToBridgeToHalfFlow

  def id: String =
    this match
      case OracleToBridge => "oracle-to-bridge"
      case OracleToBridgeToHalfFlow => "oracle-to-bridge-to-halfflow"
      case BlockToBridgeToHalfFlow => "block-to-bridge-to-halfflow"

  def usesFineRefinement: Boolean = this != OracleToBridge

/** Weighted residual percentiles used by the decomposition receipt. */
final case class BasinBridgeResidualPercentiles(
    p50Mm: Double,
    p95Mm: Double,
    maximumMm: Double,
    weightedMeanMm: Double
)

object BasinBridgeResidualPercentiles:
  def from(
      residuals: Vector[BasinBridgeMatchResidual]
  ): Either[RegistrationError, BasinBridgeResidualPercentiles] =
    val usable = residuals.filter(item =>
      item.errorMm.isFinite && item.errorMm >= 0.0 &&
        item.confidence.isFinite && item.confidence > 0.0
    )
    if usable.isEmpty then
      Left(RegistrationError.InsufficientSupport("BasinBridge residual confidence", 0, 1))
    else
      val sorted = usable.sortBy(_.errorMm)
      val totalWeight = sorted.map(_.confidence).sum
      if !totalWeight.isFinite || totalWeight <= 0.0 then
        Left(RegistrationError.InsufficientSupport("BasinBridge residual weight", 0, 1))
      else
        def quantile(probability: Double): Double =
          val target = probability * totalWeight
          var cumulative = 0.0
          var index = 0
          var selected = sorted.head.errorMm
          while index < sorted.length && cumulative < target do
            cumulative += sorted(index).confidence
            selected = sorted(index).errorMm
            index += 1
          selected
        val weightedMean = sorted.map(item => item.errorMm * item.confidence).sum / totalWeight
        Right(
          BasinBridgeResidualPercentiles(
            quantile(0.50),
            quantile(0.95),
            sorted.last.errorMm,
            weightedMean
          )
        )

/** Sampled accumulated topology summary for one lane stage. */
final case class BasinBridgeTopologySummary(
    minimumJacobian: Double,
    nonPositiveJacobians: Int,
    validFraction: Double
)

final case class BasinBridgeExportSummary(
    fixedMaximumInteriorMm: Double,
    movingMaximumInteriorMm: Double,
    endpointForwardThenBackwardMaximumMm: Option[Double],
    endpointBackwardThenForwardMaximumMm: Option[Double]
)

enum BasinBridgeExportDiagnostics:
  case Admitted(summary: BasinBridgeExportSummary)
  case Rejected(error: ForwardExportError)

/** Measurements from one bridge or bridge-plus-fine stage. */
final case class BasinBridgeDecompositionMetrics(
    residuals: BasinBridgeResidualPercentiles,
    trueCcLoss: Double,
    ccSupport: CcSupportDiagnostics,
    topology: BasinBridgeTopologySummary,
    exportDiagnostics: BasinBridgeExportDiagnostics,
    acceptedAlpha: Option[Double],
    rematchRounds: Int,
    rejectionReasons: Vector[String],
    fineAcceptedSteps: Int,
    fineAttempts: Int
)

/** One deterministic decomposition input. */
final case class BasinBridgeDecompositionCase[W, F, M](
    id: String,
    fixed: RegistrationImage[F],
    moving: RegistrationImage[M],
    initial: ForwardMidpoint[W, F, M],
    anchors: Vector[BasinBridgePoint],
    oracleMatches: BasinBridgeWorkMatches
)

/** All policies that must be serialized before B4 is run. */
final case class BasinBridgeDecompositionConfig(
    round: BasinBridgeRoundConfig,
    finePlan: HalfFlowCcPlan,
    geometry: ForwardGeometryConfig = ForwardGeometryConfig()
)

enum BasinBridgeDecompositionError:
  case Round(error: BasinBridgeRoundError)
  case Registration(error: RegistrationError)

  def message: String =
    this match
      case Round(error) => error.message
      case Registration(error) => error.message

/** One lane result retaining both bridge and fine-refinement measurements. */
final case class BasinBridgeDecompositionResult[W, F, M](
    caseId: String,
    lane: BasinBridgeDecompositionLane,
    bridge: BasinBridgeRoundResult[W, F, M],
    bridgeMetrics: BasinBridgeDecompositionMetrics,
    finalState: ForwardMidpoint[W, F, M],
    finalMetrics: BasinBridgeDecompositionMetrics,
    fineDiagnostics: Option[HalfFlowCcDiagnostics],
    fineFailure: Option[HalfFlowCcError]
)

/** Executes the frozen B4 oracle/native decomposition without retuning. */
object BasinBridgeDecomposition:
  def runCase[W, F, M](
      input: BasinBridgeDecompositionCase[W, F, M],
      lane: BasinBridgeDecompositionLane,
      config: BasinBridgeDecompositionConfig
  ): Either[BasinBridgeDecompositionError, BasinBridgeDecompositionResult[W, F, M]] =
    val bridgeResult = lane match
      case BasinBridgeDecompositionLane.OracleToBridge | BasinBridgeDecompositionLane.OracleToBridgeToHalfFlow =>
        BasinBridgeRound
          .runWithWorkMatches(
            input.fixed,
            input.moving,
            input.initial,
            input.oracleMatches,
            config.round
          )
      case BasinBridgeDecompositionLane.BlockToBridgeToHalfFlow =>
        BasinBridgeRound.run(
          input.fixed,
          input.moving,
          input.initial,
          input.anchors,
          config.round
        )

    bridgeResult
      .left
      .map(BasinBridgeDecompositionError.Round.apply)
      .flatMap: bridge =>
        metrics(
          input.fixed,
          input.moving,
          bridge.state,
          bridge.evidence.endpoint.values,
          bridge,
          config,
          fineDiagnostics = None
        ).left
          .map(BasinBridgeDecompositionError.Registration.apply)
          .flatMap: bridgeMetrics =>
            val fineResult: Either[HalfFlowCcError, Option[HalfFlowCcOptimization[W, F, M]]] =
              if lane.usesFineRefinement then
                HalfFlowCc
                  .optimize(input.fixed, input.moving, bridge.state, config.finePlan)
                  .map(result => Some(result))
              else Right(None)
            val optimization = fineResult.toOption.flatten
            val finalState = optimization.map(_.state).getOrElse(bridge.state)
            metrics(
              input.fixed,
              input.moving,
              finalState,
              bridge.evidence.endpoint.values,
              bridge,
              config,
              optimization.map(_.diagnostics)
            ).left
              .map(BasinBridgeDecompositionError.Registration.apply)
              .map: finalMetrics =>
                BasinBridgeDecompositionResult(
                  input.id,
                  lane,
                  bridge,
                  bridgeMetrics,
                  finalState,
                  finalMetrics,
                  optimization.map(_.diagnostics),
                  fineResult.left.toOption
                )

  def runAll[W, F, M](
      input: BasinBridgeDecompositionCase[W, F, M],
      config: BasinBridgeDecompositionConfig
  ): Either[BasinBridgeDecompositionError, Vector[BasinBridgeDecompositionResult[W, F, M]]] =
    BasinBridgeDecompositionLane.values.toVector.foldLeft(
      Right(Vector.empty[BasinBridgeDecompositionResult[W, F, M]]): Either[
        BasinBridgeDecompositionError,
        Vector[BasinBridgeDecompositionResult[W, F, M]]
      ]
    ) { case (accumulator, lane) =>
      accumulator.flatMap(results => runCase(input, lane, config).map(results :+ _))
    }

  private def metrics[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      state: ForwardMidpoint[W, F, M],
      correspondences: Vector[BasinBridgeCorrespondence],
      bridge: BasinBridgeRoundResult[W, F, M],
      config: BasinBridgeDecompositionConfig,
      fineDiagnostics: Option[HalfFlowCcDiagnostics]
  ): Either[RegistrationError, BasinBridgeDecompositionMetrics] =
    for
      residuals <- BasinBridgeObjective.correspondenceResiduals(state, correspondences)
      percentiles <- BasinBridgeResidualPercentiles.from(residuals)
      frozenCc <- BasinBridgeFrozenCcObjective
        .make(fixed, moving, state, config.round.cc)
      topology <- topologySummary(state, config.geometry)
    yield
      val rejections = bridge.assimilation.trials.flatMap(_.rejection.map(_.message))
      BasinBridgeDecompositionMetrics(
        percentiles,
        frozenCc.baselineLoss,
        frozenCc.diagnostics.support,
        topology,
        exportDiagnostics(state, config),
        bridge.assimilation.acceptedAlpha,
        if bridge.rematch.nonEmpty then 1 else 0,
        rejections,
        fineDiagnostics.map(_.acceptedSteps).getOrElse(0),
        fineDiagnostics.map(_.attempts).getOrElse(0)
      )

  private def exportDiagnostics[W, F, M](
      state: ForwardMidpoint[W, F, M],
      config: BasinBridgeDecompositionConfig
  ): BasinBridgeExportDiagnostics =
    ForwardMidpointExporter.build(state, config.finePlan.exportConfig) match
      case Left(error) => BasinBridgeExportDiagnostics.Rejected(error)
      case Right(exported) =>
        BasinBridgeExportDiagnostics.Admitted(
          BasinBridgeExportSummary(
            exported.fixedResidualInverse.maximumInteriorMm,
            exported.movingResidualInverse.maximumInteriorMm,
            exported.endpointRoundTrip.forwardThenBackward.maximumMm,
            exported.endpointRoundTrip.backwardThenForward.maximumMm
          )
        )

  private def topologySummary[W, F, M](
      state: ForwardMidpoint[W, F, M],
      config: ForwardGeometryConfig
  ): Either[RegistrationError, BasinBridgeTopologySummary] =
    val (_, reports) = ForwardGeometry.accumulated(state, config)
    if reports.isEmpty then Left(RegistrationError.InsufficientSupport("BasinBridge topology", 0, 1))
    else
      val minimum = reports.map(_.minimum).min
      val nonPositive = reports.map(_.nonPositive).sum
      val evaluated = reports.map(_.evaluated).sum.toDouble
      val eligible = reports.map(_.eligible).sum.toDouble
      Right(
        BasinBridgeTopologySummary(
          minimum,
          nonPositive,
          if eligible > 0.0 then evaluated / eligible else 0.0
        )
      )
