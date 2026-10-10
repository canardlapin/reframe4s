package reframe4s.benchmark.flashalign

import image4s.ContinuousImage
import image4s.SampleSpace
import image4s.geometry.D3
import image4s.geometry.Frame as CanonicalFrame
import ravel.Rank
import reframe4s.flashalign.AffineFlashalignResult
import reframe4s.flashalign.Flashalign
import reframe4s.flashalign.FlashalignConfig
import reframe4s.flashalign.FlashalignError
import reframe4s.flashalign.FlashalignInitializationPolicy
import reframe4s.flashalign.FlashalignPreset
import reframe4s.flashalign.FlashalignWorkCounts
import reframe4s.halfflow.BasinBridgeBlockSearchConfig
import reframe4s.halfflow.BasinBridgeCcObjectiveConfig
import reframe4s.halfflow.BasinBridgePoint
import reframe4s.halfflow.BasinBridgeProjectorConfig
import reframe4s.halfflow.BasinBridgeRound
import reframe4s.halfflow.BasinBridgeRoundConfig
import reframe4s.halfflow.DensePull
import reframe4s.halfflow.RegistrationFrame as HalfFlowFrame
import reframe4s.halfflow.ForwardMidpoint
import reframe4s.halfflow.ForwardMidpointExporter
import reframe4s.halfflow.HalfFlowCc
import reframe4s.halfflow.HalfFlowCcControlConfig
import reframe4s.halfflow.HalfFlowCcLevel
import reframe4s.halfflow.HalfFlowCcPlan
import reframe4s.halfflow.HalfFlowCcAction
import reframe4s.halfflow.NeighborhoodCcConfig
import reframe4s.halfflow.RegistrationImage
import reframe4s.halfflow.ResidualInverseConfig
import reframe4s.halfflow.SuppliedAffineInitialization
import reframe4s.halfflow.internal.FieldValidity
import reframe4s.halfflow.internal.GridSpec
import reframe4s.halfflow.internal.NeuroVol
import reframe4s.halfflow.internal.SpatialDomainId
import reframe4s.halfflow.internal.VoxelWindowRadius

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** The two evidence candidates produced by one Flashalign-to-HalfFlow run.
  *
  * The affine candidate is captured before the downstream stage starts. A
  * HalfFlow failure must therefore be represented by `downstream` and cannot
  * erase or replace `flashalignAffine`.
  */
final case class FlashalignHalfFlowCandidates(
    flashalignAffine: CrossMethodCandidate,
    downstream: CrossMethodCandidate
)

/** One execution that owns the complete affine-to-residual handoff.
  *
  * Implementations must return downstream method failures as a non-success
  * `CrossMethodCandidate`. `Left` is reserved for an adapter contract failure
  * that prevents either evidence row from being constructed.
  */
trait FlashalignHalfFlowPipeline[A]:
  def run(
      pair: PairCase,
      inputs: A
  ): Either[EvidenceError, FlashalignHalfFlowCandidates]

/** Presents one staged execution to the C0-C7 runner as separate C1 and C5
  * adapters.
  *
  * The pair shares a single cache because [[CrossMethodEvidenceRunner]] loads
  * one pair once and invokes lanes in declared order. The pipeline executes on
  * the first of C1/C5 and the other lane reads its already materialized
  * candidate. This keeps the upstream affine as its own row and includes its
  * cost in the downstream candidate without rerunning Flashalign.
  */
object FlashalignHalfFlowAdapters:
  def make[A](
      flashalign: LanePlan,
      downstream: LanePlan,
      pipeline: FlashalignHalfFlowPipeline[A]
  ): Either[CrossMethodEvidenceError, Vector[CrossMethodLaneAdapter[A]]] =
    if flashalign.lane != ComparisonLane.C1FlashalignLinear then
      Left(
        CrossMethodEvidenceError.InvalidPlan(
          s"Flashalign handoff requires C1 upstream, got ${flashalign.lane.id}"
        )
      )
    else if downstream.lane != ComparisonLane.C5HalfFlowFlashalignInitialization then
      Left(
        CrossMethodEvidenceError.InvalidPlan(
          s"Flashalign handoff requires C5 downstream, got ${downstream.lane.id}"
        )
      )
    else if downstream.mapInterpretation != MapInterpretation.CompleteMovingToFixed then
      Left(
        CrossMethodEvidenceError.InvalidPlan(
          "C5 must expose HalfFlow's complete endpoint transform; residual interpretation would apply the supplied affine twice"
        )
      )
    else
      val shared = new SharedPipeline[A](pipeline)
      Right(
        Vector(
          shared.adapter(
            flashalign.lane,
            flashalign.method,
            _.flashalignAffine
          ),
          shared.adapter(
            downstream.lane,
            downstream.method,
            _.downstream
          )
        )
      )

  private final class SharedPipeline[A](pipeline: FlashalignHalfFlowPipeline[A]):
    private var cached = Option.empty[
      (String, Either[EvidenceError, FlashalignHalfFlowCandidates])
    ]

    def adapter(
        adapterLane: ComparisonLane,
        adapterIdentity: MethodIdentity,
        select: FlashalignHalfFlowCandidates => CrossMethodCandidate
    ): CrossMethodLaneAdapter[A] = new CrossMethodLaneAdapter[A]:
      val lane: ComparisonLane = adapterLane
      val identity: MethodIdentity = adapterIdentity

      def run(
          pair: PairCase,
          inputs: A
      ): Either[EvidenceError, CrossMethodCandidate] =
        evaluate(pair, inputs).map(select)

    private def evaluate(
        pair: PairCase,
        inputs: A
    ): Either[EvidenceError, FlashalignHalfFlowCandidates] =
      cached match
        case Some((caseId, result)) if caseId == pair.id => result
        case Some((caseId, _)) =>
          Left(
            EvidenceError.InvalidCandidate(
              s"one Flashalign/HalfFlow adapter pair cannot be reused for $caseId and ${pair.id}"
            )
          )
        case None =>
          val result = pipeline.run(pair, inputs)
          cached = Some(pair.id -> result)
          result

/** Actual benchmark-owned C1 -> C5 implementation.
  *
  * Flashalign remains unaware of HalfFlow. This unpublished consumer owns the
  * conversion of the shared image pair, the canonical supplied-affine adapter,
  * the frozen recipient stages, and the separate evidence candidates.
  */
object FlashalignHalfFlowImagePipeline:
  val ConfigurationContract: String =
    "flashalign-affine-within-modality|initialization=coherent-header-world-identity-then-capture|public-entry=Flashalign.affine-plan.run-workspace"

final class FlashalignHalfFlowImagePipeline(
    candidateRevision: String
) extends FlashalignHalfFlowPipeline[NiftiPair]:
  def run(
      pair: PairCase,
      inputs: NiftiPair
  ): Either[EvidenceError, FlashalignHalfFlowCandidates] =
    if candidateRevision.trim.isEmpty then
      Left(EvidenceError.EmptyField("flashalign candidate revision"))
    else if pair.id.trim.isEmpty then
      Left(EvidenceError.EmptyField("pair.id"))
    else inputs.moving.fold(
      _ => Right(rankFailure("moving")),
      movingD3 =>
        inputs.fixed.fold(
          _ => Right(rankFailure("fixed")),
          fixedD3 =>
            for
              moving <- movingD3.value
                .requireDataRank[3]
                .left
                .map(error => EvidenceError.InputFailure("moving", error.message))
              fixed <- fixedD3.value
                .requireDataRank[3]
                .left
                .map(error => EvidenceError.InputFailure("fixed", error.message))
              result <- runTyped(moving, fixed)
            yield result
        )
    )

  private def runTyped[
      Moving <: CanonicalFrame[D3],
      Fixed <: CanonicalFrame[D3],
      MovingSpace <: SampleSpace[Moving, D3],
      FixedSpace <: SampleSpace[Fixed, D3]
  ](
      moving: ContinuousImage[MovingSpace, Double, Rank[3]],
      fixed: ContinuousImage[FixedSpace, Double, Rank[3]]
  ): Either[EvidenceError, FlashalignHalfFlowCandidates] =
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )
    val preparationStarted = System.nanoTime()
    val compiled = Flashalign.affine(moving, fixed, config)
    val preparationMillis = elapsed(preparationStarted)
    compiled match
      case Left(error) => Right(upstreamFailure(error, preparationMillis, 0.0))
      case Right(plan) =>
        val executionStarted = System.nanoTime()
        val outcome = plan.run(plan.newWorkspace())
        val executionMillis = elapsed(executionStarted)
        outcome match
          case Left(error) =>
            Right(upstreamFailure(error, preparationMillis, executionMillis))
          case Right(result) =>
            val upstream = flashalignCandidate(
              result,
              preparationMillis,
              executionMillis
            )
            val downstream =
              try
                FrozenHalfFlowRecipient.runSupplied(
                  moving,
                  fixed,
                  result,
                  upstream
                )
              catch
                case NonFatal(error) => downstreamException(upstream, error)
            Right(FlashalignHalfFlowCandidates(upstream, downstream))

  private def flashalignCandidate[
      Moving <: CanonicalFrame[D3],
      Fixed <: CanonicalFrame[D3]
  ](
      result: AffineFlashalignResult[Moving, Fixed],
      preparationMillis: Double,
      executionMillis: Double
  ): CrossMethodCandidate =
    val matrix = result.movingToFixed.operator.rowMajor
    val capture = result.diagnostics.capture
    val stages = capture match
      case None =>
        AlgorithmStageTimes(
          preparationMillis,
          0.0,
          executionMillis,
          0.0,
          0.0
        )
      case Some(value) =>
        AlgorithmStageTimes(
          preparationMillis,
          nanosToMillis(value.captureElapsedNanoseconds),
          nanosToMillis(value.refinementAndSelectionElapsedNanoseconds),
          nanosToMillis(value.finalAuditElapsedNanoseconds),
          0.0
        )
    CrossMethodCandidate(
      RunStatus.Success,
      accepted = true,
      None,
      Some(AffineEvidenceMap(matrix)),
      Some(EvidenceHash.matrix(matrix)),
      Map(
        "projected_patch_objective" -> Some(result.report.finalObjective),
        "audit_overlap_fraction" -> Some(result.diagnostics.overlapFraction),
        "upstream_affine_completed" -> Some(1.0),
        "output_resamplings" -> Some(0.0)
      ),
      workCounts(result.diagnostics.work),
      LaneCostEvidence(
        preparationMillis,
        preparationMillis,
        stages,
        0L
      )
    )

  private def upstreamFailure(
      error: FlashalignError,
      preparationMillis: Double,
      executionMillis: Double
  ): FlashalignHalfFlowCandidates =
    val status = flashalignStatus(error)
    val upstream = failedCandidate(
      status,
      "flashalign-affine",
      error.message,
      AlgorithmStageTimes(
        preparationMillis,
        0.0,
        executionMillis,
        0.0,
        0.0
      ),
      Map("upstream_affine_completed" -> Some(0.0))
    )
    val downstream = failedCandidate(
      RunStatus.Unavailable,
      "flashalign-affine-prerequisite",
      s"HalfFlow was not invoked because Flashalign failed: ${error.message}",
      upstream.costs.methodStagesMs,
      Map(
        "upstream_affine_completed" -> Some(0.0),
        "halfflow_invoked" -> Some(0.0),
        "output_resamplings" -> Some(0.0)
      )
    )
    FlashalignHalfFlowCandidates(upstream, downstream)

  private def rankFailure(label: String): FlashalignHalfFlowCandidates =
    val upstream = failedCandidate(
      RunStatus.InvalidGeometry,
      s"$label-rank",
      s"expected D3 $label image",
      AlgorithmStageTimes.Zero,
      Map("upstream_affine_completed" -> Some(0.0))
    )
    FlashalignHalfFlowCandidates(
      upstream,
      failedCandidate(
        RunStatus.Unavailable,
        "flashalign-affine-prerequisite",
        s"HalfFlow was not invoked because the $label image is not D3",
        AlgorithmStageTimes.Zero,
        Map(
          "upstream_affine_completed" -> Some(0.0),
          "halfflow_invoked" -> Some(0.0),
          "output_resamplings" -> Some(0.0)
        )
      )
    )

  private def downstreamException(
      upstream: CrossMethodCandidate,
      error: Throwable
  ): CrossMethodCandidate = failedCandidate(
    RunStatus.NumericalFailure,
    "halfflow-unhandled",
    Option(error.getMessage).getOrElse(error.getClass.getName),
    upstream.costs.methodStagesMs,
    Map(
      "upstream_affine_completed" -> Some(1.0),
      "halfflow_invoked" -> Some(1.0),
      "output_resamplings" -> Some(0.0)
    ),
    upstream.work,
    upstream.costs.coldStandaloneInputAndPreparationMs,
    upstream.costs.sharedPreparationMs
  )

  private def flashalignStatus(error: FlashalignError): RunStatus =
    val message = error.message
    if message.contains("overlap") then RunStatus.InsufficientOverlap
    else if message.contains("RankDeficient") || message.contains("information") then
      RunStatus.InsufficientInformation
    else if message.contains("LinearizationLimit") then RunStatus.IterationLimit
    else if message.contains("TrialAttemptLimit") || message.contains("DampingLimited") then
      RunStatus.Stalled
    else if message.contains("geometry") || message.contains("Geometry") then
      RunStatus.InvalidGeometry
    else RunStatus.NumericalFailure

  private def workCounts(value: FlashalignWorkCounts): WorkCounts =
    WorkCounts(
      value.uniqueInterpolations,
      value.gradientEvaluations,
      value.dataLinearizations.toLong + value.priorLinearizations.toLong,
      value.trialEvaluations,
      value.rejectedSteps,
      value.earlyRejectedTrials,
      value.curvatureProducts.toLong
    )

  private def elapsed(started: Long): Double =
    nanosToMillis(System.nanoTime() - started)

  private def nanosToMillis(value: Long): Double =
    math.max(0L, value).toDouble / 1000000.0

/** C4 adapter for the same receiver with its existing identity midpoint. */
final class HalfFlowExistingPipelineAdapter(
    val identity: MethodIdentity
) extends CrossMethodLaneAdapter[NiftiPair]:
  val lane: ComparisonLane = ComparisonLane.C4HalfFlowExistingPipeline

  def run(
      pair: PairCase,
      inputs: NiftiPair
  ): Either[EvidenceError, CrossMethodCandidate] =
    if pair.id.trim.isEmpty then Left(EvidenceError.EmptyField("pair.id"))
    else inputs.moving.fold(
      _ => Right(rankFailure("moving")),
      movingD3 =>
        inputs.fixed.fold(
          _ => Right(rankFailure("fixed")),
          fixedD3 =>
            for
              moving <- movingD3.value
                .requireDataRank[3]
                .left
                .map(error => EvidenceError.InputFailure("moving", error.message))
              fixed <- fixedD3.value
                .requireDataRank[3]
                .left
                .map(error => EvidenceError.InputFailure("fixed", error.message))
            yield FrozenHalfFlowRecipient.runIdentity(moving, fixed)
        )
    )

  private def rankFailure(label: String): CrossMethodCandidate =
    failedCandidate(
      RunStatus.InvalidGeometry,
      s"$label-rank",
      s"expected D3 $label image",
      AlgorithmStageTimes.Zero,
      Map(
        "halfflow_invoked" -> Some(0.0),
        "output_resamplings" -> Some(0.0)
      )
    )

private[flashalign] object FrozenHalfFlowRecipient:
  sealed trait Work
  sealed trait Fixed
  sealed trait Moving

  val ControlContract: String =
    "halfflow-basinbridge-frozen-transfer-v1|all-finite-fixed-support|anchors=18|patch=1|search=8|min-valid=0.75|projector=default|cc-objective=default|levels=2:1.0:2:10.0:1.0:10:24,1:0.0:2:6.0:0.6:10:24|cc=0.15:0.7:1e-7:1e-5:1e-7|control=1e-4:1e-8:1.0:6:8:5|integration-inverse=0.03|export=2,1:50:0.2:0.2:6|action=symmetric-midpoint"

  def runIdentity[
      MovingFrame <: CanonicalFrame[D3],
      FixedFrame <: CanonicalFrame[D3],
      MovingSpace <: SampleSpace[MovingFrame, D3],
      FixedSpace <: SampleSpace[FixedFrame, D3]
  ](
      moving: ContinuousImage[MovingSpace, Double, Rank[3]],
      fixed: ContinuousImage[FixedSpace, Double, Rank[3]]
  ): CrossMethodCandidate =
    val preparationStarted = System.nanoTime()
    prepare(moving, fixed) match
      case Left(detail) =>
        failedCandidate(
          RunStatus.InvalidGeometry,
          "halfflow-prepare",
          detail,
          AlgorithmStageTimes(elapsed(preparationStarted), 0.0, 0.0, 0.0, 0.0),
          baseMetrics(upstreamComplete = false, invoked = false)
        )
      case Right(input) =>
        val initial = ForwardMidpoint
          .identity(input.work, input.fixed.frame, input.moving.frame)
          .left
          .map(_.message)
        val preparationMillis = elapsed(preparationStarted)
        runRecipient(
          input,
          initial,
          initializationMillis = 0.0,
          upstreamStages = AlgorithmStageTimes.Zero,
          upstreamWork = WorkCounts.Zero,
          upstreamComplete = false,
          preparationMillis
        )

  def runSupplied[
      MovingFrame <: CanonicalFrame[D3],
      FixedFrame <: CanonicalFrame[D3],
      MovingSpace <: SampleSpace[MovingFrame, D3],
      FixedSpace <: SampleSpace[FixedFrame, D3]
  ](
      moving: ContinuousImage[MovingSpace, Double, Rank[3]],
      fixed: ContinuousImage[FixedSpace, Double, Rank[3]],
      affine: AffineFlashalignResult[MovingFrame, FixedFrame],
      upstream: CrossMethodCandidate
  ): CrossMethodCandidate =
    val preparationStarted = System.nanoTime()
    prepare(moving, fixed) match
      case Left(detail) =>
        downstreamFailure(
          RunStatus.InvalidGeometry,
          "halfflow-prepare",
          detail,
          upstream,
          preparationMillis = elapsed(preparationStarted)
        )
      case Right(input) =>
        val preparationMillis = elapsed(preparationStarted)
        val initializationStarted = System.nanoTime()
        val supplied = SuppliedAffineInitialization
          .fromMovingToFixed(
            input.fixed,
            input.moving,
            input.work,
            affine.movingToFixed
          )
          .left
          .map(_.message)
        val initializationMillis = elapsed(initializationStarted)
        runRecipient(
          input,
          supplied.map(_.initial),
          initializationMillis,
          upstream.costs.methodStagesMs,
          upstream.work,
          upstreamComplete = true,
          preparationMillis
        )

  private def runRecipient(
      input: RecipientInput,
      initial: Either[String, ForwardMidpoint[Work, Fixed, Moving]],
      initializationMillis: Double,
      upstreamStages: AlgorithmStageTimes,
      upstreamWork: WorkCounts,
      upstreamComplete: Boolean,
      preparationMillis: Double
  ): CrossMethodCandidate =
    initial match
      case Left(detail) =>
        failed(
          RunStatus.InvalidGeometry,
          "halfflow-affine-adapter",
          detail,
          upstreamStages,
          upstreamWork,
          upstreamComplete,
          preparationMillis,
          initializationMillis,
          bridgeMillis = 0.0,
          fineMillis = 0.0,
          exportMillis = 0.0
        )
      case Right(state) =>
        val bridgeStarted = System.nanoTime()
        val bridge = BasinBridgeRound.run(
          input.fixed,
          input.moving,
          state,
          input.anchors,
          roundConfig
        )
        val bridgeMillis = elapsed(bridgeStarted)
        bridge match
          case Left(error) =>
            failed(
              RunStatus.SearchExhausted,
              "halfflow-bridge",
              error.message,
              upstreamStages,
              upstreamWork,
              upstreamComplete,
              preparationMillis,
              initializationMillis,
              bridgeMillis,
              fineMillis = 0.0,
              exportMillis = 0.0
            )
          case Right(bridged) =>
            val fineStarted = System.nanoTime()
            val fine = HalfFlowCc.optimize(
              input.fixed,
              input.moving,
              bridged.state,
              finePlan
            )
            val fineMillis = elapsed(fineStarted)
            fine match
              case Left(error) =>
                failed(
                  halfFlowStatus(error.message),
                  "halfflow-fine",
                  error.message,
                  upstreamStages,
                  upstreamWork,
                  upstreamComplete,
                  preparationMillis,
                  initializationMillis,
                  bridgeMillis,
                  fineMillis,
                  exportMillis = 0.0,
                  bridge = Some(bridged)
                )
              case Right(optimized) =>
                val exportStarted = System.nanoTime()
                val exported = ForwardMidpointExporter.build(
                  optimized.state,
                  finePlan.exportConfig
                )
                val exportMillis = elapsed(exportStarted)
                exported match
                  case Left(error) =>
                    failed(
                      RunStatus.InverseFailure,
                      "halfflow-export",
                      error.message,
                      upstreamStages,
                      upstreamWork,
                      upstreamComplete,
                      preparationMillis,
                      initializationMillis,
                      bridgeMillis,
                      fineMillis,
                      exportMillis,
                      bridge = Some(bridged),
                      acceptedSteps = optimized.diagnostics.acceptedSteps,
                      attempts = optimized.diagnostics.attempts
                    )
                  case Right(result) =>
                    val outputStarted = System.nanoTime()
                    val output = materializeOutput(
                      result.transform.forward,
                      input.moving.volume
                    )
                    val outputMillis = elapsed(outputStarted)
                    val completeMap = DensePullEvidenceMap(result.transform.backward)
                    val mapHash = densePullHash(result.transform.backward)
                    val metrics = baseMetrics(upstreamComplete, invoked = true) ++ Map(
                      "halfflow_affine_adapter_ms" -> Some(initializationMillis),
                      "halfflow_bridge_ms" -> Some(bridgeMillis),
                      "halfflow_fine_ms" -> Some(fineMillis),
                      "halfflow_export_ms" -> Some(exportMillis),
                      "halfflow_bridge_initial_match_mm" -> Some(
                        bridged.assimilation.initialObjective.weightedMatchErrorMm
                      ),
                      "halfflow_bridge_final_match_mm" -> Some(
                        bridged.assimilation.finalObjective.weightedMatchErrorMm
                      ),
                      "halfflow_accepted_steps" -> Some(
                        optimized.diagnostics.acceptedSteps.toDouble
                      ),
                      "halfflow_attempts" -> Some(
                        optimized.diagnostics.attempts.toDouble
                      ),
                      "output_resamplings" -> Some(1.0),
                      "output_voxels" -> Some(output.voxels.toDouble),
                      "output_finite_voxels" -> Some(output.finiteVoxels.toDouble),
                      "output_finite_value_sum" -> Some(output.finiteValueSum)
                    )
                    CrossMethodCandidate(
                      RunStatus.Success,
                      accepted = true,
                      None,
                      Some(completeMap),
                      Some(mapHash),
                      metrics,
                      upstreamWork.copy(
                        trialEvaluations = upstreamWork.trialEvaluations +
                          optimized.diagnostics.attempts.toLong,
                        rejectedTrials = upstreamWork.rejectedTrials +
                          (optimized.diagnostics.attempts -
                            optimized.diagnostics.acceptedSteps).toLong
                      ),
                      costs(
                        upstreamStages,
                        preparationMillis,
                        initializationMillis,
                        bridgeMillis,
                        fineMillis,
                        exportMillis,
                        outputMillis
                      )
                    )

  private def prepare[
      MovingFrame <: CanonicalFrame[D3],
      FixedFrame <: CanonicalFrame[D3],
      MovingSpace <: SampleSpace[MovingFrame, D3],
      FixedSpace <: SampleSpace[FixedFrame, D3]
  ](
      moving: ContinuousImage[MovingSpace, Double, Rank[3]],
      fixed: ContinuousImage[FixedSpace, Double, Rank[3]]
  ): Either[String, RecipientInput] =
    try
      val movingGrid = grid(moving)
      val fixedGrid = grid(fixed)
      val movingFrame = HalfFlowFrame[Moving](
        SpatialDomainId("flashalign-transfer-moving"),
        movingGrid
      )
      val fixedFrame = HalfFlowFrame[Fixed](
        SpatialDomainId("flashalign-transfer-fixed"),
        fixedGrid
      )
      val work = HalfFlowFrame[Work](
        SpatialDomainId("flashalign-transfer-work"),
        fixedGrid
      )
      val movingVolume = NeuroVol.fromRavel(
        moving.data,
        movingGrid,
        "flashalign-transfer-moving"
      )
      val fixedVolume = NeuroVol.fromRavel(
        fixed.data,
        fixedGrid,
        "flashalign-transfer-fixed"
      )
      for
        movingImage <- RegistrationImage
          .make(movingFrame, movingVolume, FieldValidity.All)
          .left
          .map(_.message)
        fixedImage <- RegistrationImage
          .make(fixedFrame, fixedVolume, FieldValidity.All)
          .left
          .map(_.message)
      yield RecipientInput(
        movingImage,
        fixedImage,
        work,
        anchors(fixedImage)
      )
    catch
      case error: IllegalArgumentException => Left(error.getMessage)

  private def grid[
      F <: CanonicalFrame[D3],
      S <: SampleSpace[F, D3]
  ](
      image: ContinuousImage[S, Double, Rank[3]]
  ): GridSpec =
    GridSpec.fromGrid(image.grid)

  private def anchors(reference: RegistrationImage[Fixed]): Vector[BasinBridgePoint] =
    final case class Candidate(world: Vector[Double])
    val grid = reference.frame.grid
    val fractions = Vector(0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80)
    def positions(extent: Int): Vector[Int] =
      fractions
        .map(fraction => math.round((extent - 1).toDouble * fraction).toInt)
        .distinct
    val candidates = (for
      x <- positions(grid.shape(0))
      y <- positions(grid.shape(1))
      z <- positions(grid.shape(2))
    yield Candidate(grid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble)))).toVector
    val center = Candidate(
      grid.voxelToWorld(
        Vector(
          (grid.shape(0) - 1).toDouble / 2.0,
          (grid.shape(1) - 1).toDouble / 2.0,
          (grid.shape(2) - 1).toDouble / 2.0
        )
      )
    )
    def squaredDistance(left: Candidate, right: Candidate): Double =
      left.world.zip(right.world).map { case (a, b) =>
        val delta = a - b
        delta * delta
      }.sum
    var selected = Vector(candidates.minBy(value => squaredDistance(value, center)))
    val target = math.min(18, candidates.length)
    while selected.length < target do
      val next = candidates
        .filterNot(selected.contains)
        .maxBy(candidate =>
          selected.map(existing => squaredDistance(candidate, existing)).min
        )
      selected = selected :+ next
    selected.map(candidate =>
      BasinBridgePoint.unsafe(
        candidate.world(0),
        candidate.world(1),
        candidate.world(2)
      )
    )

  private lazy val roundConfig: BasinBridgeRoundConfig =
    val search = BasinBridgeBlockSearchConfig
      .make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(8, 8, 8),
        minimumValidFraction = 0.75
      )
      .fold(error => throw new IllegalStateException(error.message), identity)
    BasinBridgeRoundConfig.make(
      search,
      projector = BasinBridgeProjectorConfig.default,
      cc = BasinBridgeCcObjectiveConfig.default
    )

  private lazy val finePlan: HalfFlowCcPlan =
    val cc = NeighborhoodCcConfig
      .make(
        radius = VoxelWindowRadius(2, 2, 2),
        minimumSupportFraction = 0.15,
        fullSupportFraction = 0.7,
        minimumVarianceFraction = 1e-7,
        fullVarianceFraction = 1e-5,
        denominatorEpsilonFraction = 1e-7
      )
      .fold(error => throw new IllegalStateException(error.message), identity)
    val levels = Vector(
      HalfFlowCcLevel
        .make(
          shrink = 2,
          pyramidSigmaMm = 1.0,
          cc = cc,
          smoothSigmaMm = 10.0,
          maximumStepMm = 1.0,
          targetAcceptedSteps = 10,
          maximumAttempts = 24
        )
        .fold(error => throw new IllegalStateException(error.message), identity),
      HalfFlowCcLevel
        .make(
          shrink = 1,
          pyramidSigmaMm = 0.0,
          cc = cc,
          smoothSigmaMm = 6.0,
          maximumStepMm = 0.6,
          targetAcceptedSteps = 10,
          maximumAttempts = 24
        )
        .fold(error => throw new IllegalStateException(error.message), identity)
    )
    val control = HalfFlowCcControlConfig
      .make(
        initialDamping = 1e-4,
        minimumDamping = 1e-8,
        maximumDamping = 1.0,
        maximumObjectiveRetries = 6,
        maximumGeometryRetries = 8,
        maximumIntegrationRetries = 5
      )
      .fold(error => throw new IllegalStateException(error.message), identity)
    val exportConfig = ResidualInverseConfig
      .make(
        shrinks = Vector(2, 1),
        iterationsPerLevel = 50,
        maximumInteriorErrorMm = 0.2,
        maximumInteriorErrorVox = 0.2,
        interiorMargin = 6
      )
      .fold(error => throw new IllegalStateException(error.message), identity)
    HalfFlowCcPlan
      .make(
        levels,
        supportSigmaMm = 1.5,
        minimumUsefulStepMm = 1e-6,
        maximumIntegrationInverseErrorMm = 0.03,
        control = control,
        exportConfig = exportConfig,
        action = HalfFlowCcAction.SymmetricMidpoint
      )
      .fold(error => throw new IllegalStateException(error.message), identity)

  private def failed(
      status: RunStatus,
      kind: String,
      detail: String,
      upstreamStages: AlgorithmStageTimes,
      upstreamWork: WorkCounts,
      upstreamComplete: Boolean,
      preparationMillis: Double,
      initializationMillis: Double,
      bridgeMillis: Double,
      fineMillis: Double,
      exportMillis: Double,
      bridge: Option[reframe4s.halfflow.BasinBridgeRoundResult[Work, Fixed, Moving]] = None,
      acceptedSteps: Int = 0,
      attempts: Int = 0
  ): CrossMethodCandidate =
    val metrics = baseMetrics(upstreamComplete, invoked = true) ++ Map(
      "halfflow_affine_adapter_ms" -> Some(initializationMillis),
      "halfflow_bridge_ms" -> Some(bridgeMillis),
      "halfflow_fine_ms" -> Some(fineMillis),
      "halfflow_export_ms" -> Some(exportMillis),
      "halfflow_bridge_initial_match_mm" -> bridge.map(
        _.assimilation.initialObjective.weightedMatchErrorMm
      ),
      "halfflow_bridge_final_match_mm" -> bridge.map(
        _.assimilation.finalObjective.weightedMatchErrorMm
      ),
      "halfflow_accepted_steps" -> Some(acceptedSteps.toDouble),
      "halfflow_attempts" -> Some(attempts.toDouble),
      "output_resamplings" -> Some(0.0)
    )
    failedCandidate(
      status,
      kind,
      detail,
      costs(
        upstreamStages,
        preparationMillis,
        initializationMillis,
        bridgeMillis,
        fineMillis,
        exportMillis,
        outputMillis = 0.0
      ).methodStagesMs,
      metrics,
      upstreamWork.copy(
        trialEvaluations = upstreamWork.trialEvaluations + attempts.toLong,
        rejectedTrials = upstreamWork.rejectedTrials +
          (attempts - acceptedSteps).toLong
      ),
      coldPreparationMillis = upstreamStages.prepare + preparationMillis,
      sharedPreparationMillis = preparationMillis
    )

  private def downstreamFailure(
      status: RunStatus,
      kind: String,
      detail: String,
      upstream: CrossMethodCandidate,
      preparationMillis: Double
  ): CrossMethodCandidate =
    failedCandidate(
      status,
      kind,
      detail,
      upstream.costs.methodStagesMs.copy(
        prepare = upstream.costs.methodStagesMs.prepare + preparationMillis
      ),
      baseMetrics(upstreamComplete = true, invoked = true) +
        ("output_resamplings" -> Some(0.0)),
      upstream.work,
      coldPreparationMillis = upstream.costs.coldStandaloneInputAndPreparationMs +
        preparationMillis,
      sharedPreparationMillis = preparationMillis
    )

  private def costs(
      upstream: AlgorithmStageTimes,
      preparationMillis: Double,
      initializationMillis: Double,
      bridgeMillis: Double,
      fineMillis: Double,
      exportMillis: Double,
      outputMillis: Double
  ): LaneCostEvidence =
    LaneCostEvidence(
      upstream.prepare + preparationMillis,
      preparationMillis,
      AlgorithmStageTimes(
        upstream.prepare + preparationMillis + initializationMillis,
        upstream.capture,
        upstream.optimize + bridgeMillis + fineMillis,
        upstream.validate + exportMillis,
        outputMillis
      ),
      0L
    )

  private def baseMetrics(
      upstreamComplete: Boolean,
      invoked: Boolean
  ): Map[String, Option[Double]] = Map(
    "upstream_affine_completed" -> Some(if upstreamComplete then 1.0 else 0.0),
    "halfflow_invoked" -> Some(if invoked then 1.0 else 0.0)
  )

  private def halfFlowStatus(message: String): RunStatus =
    if message.contains("Topology") || message.contains("Jacobian") then
      RunStatus.InvalidGeometry
    else if message.contains("support") then RunStatus.InsufficientOverlap
    else if message.contains("attempt") || message.contains("Controller") then
      RunStatus.IterationLimit
    else RunStatus.NumericalFailure

  private def materializeOutput(
      fixedToMoving: DensePull[Fixed, Moving],
      moving: NeuroVol[Double]
  ): MaterializedOutput =
    val values = new Array[Double](fixedToMoving.from.grid.nVoxels)
    var finite = 0L
    var sum = 0.0
    var compensation = 0.0
    var index = 0
    while index < values.length do
      val world = Vector(
        fixedToMoving.sourceCoordinates.linearComponent(index, 0),
        fixedToMoving.sourceCoordinates.linearComponent(index, 1),
        fixedToMoving.sourceCoordinates.linearComponent(index, 2)
      )
      val value =
        if fieldValid(fixedToMoving.validity, index) then
          sampleScalar(moving, world).getOrElse(Double.NaN)
        else Double.NaN
      values(index) = value
      if value.isFinite then
        val corrected = value - compensation
        val updated = sum + corrected
        compensation = (updated - sum) - corrected
        sum = updated
        finite += 1L
      index += 1
    MaterializedOutput(values.length.toLong, finite, sum)

  private def sampleScalar(
      volume: NeuroVol[Double],
      world: Vector[Double]
  ): Option[Double] =
    volume.space.indexToFrame.inverse(world).toOption.flatMap: voxel =>
      trilinear(volume.space, voxel) { index => volume.linear(index) }

  private def elapsed(started: Long): Double =
    math.max(0L, System.nanoTime() - started).toDouble / 1000000.0

  private final case class RecipientInput(
      moving: RegistrationImage[Moving],
      fixed: RegistrationImage[Fixed],
      work: HalfFlowFrame[Work],
      anchors: Vector[BasinBridgePoint]
  )

  private final case class MaterializedOutput(
      voxels: Long,
      finiteVoxels: Long,
      finiteValueSum: Double
  )

/** Executes the frozen C04 transfer court.
  *
  * The selected cases are the six exact-core affine rows from the final v5
  * Flashalign fixture set. The manifest is byte-verified before any image is
  * loaded. C1, C4, and C5 execute; all other declared lanes remain explicit
  * unavailable rows in the fixed C0-C7 denominator.
  */
object FlashalignHalfFlowComparisonCourt:
  val CourtId = "flashalign-halfflow-affine-transfer-v1"
  val FlashalignRevision =
    "df4a4ddf84a63e5b7ae714a29b226d15639ce32bf8d583e3f08b931196442089"
  val HalfFlowRevision =
    "dae5bac9521388a97159dfd1c0b85d32b5193eaf80e97a4632e622ba91f11172"
  val CaseTable: Path = Paths.get(
    "benchmarks/flashalign/fixtures/linear-automatic-release-confirmation-v5.cases.tsv"
  )
  val CaseTableSha256: Sha256 = Sha256.unsafe(
    "01b19237dc07a90bcce37b13e47c88e6eeb856d15f523ce2cfcd0d2d575f4fd6"
  )

  private val Identity = Vector(
    1.0, 0.0, 0.0, 0.0,
    0.0, 1.0, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
  )

  def main(arguments: Array[String]): Unit =
    val result = arguments.toVector match
      case Vector(raw, manifest, manifestSha256) =>
        for
          seal <- Sha256.parse(manifestSha256)
          _ <- run(Paths.get(raw), Paths.get(manifest), seal)
        yield ()
      case _ =>
        Left(EvidenceError.InvalidCandidate(
          "usage: FlashalignHalfFlowComparisonCourt <raw-jsonl> <manifest> <manifest-sha256>"
        ))
    result.fold(error => throw new IllegalStateException(error.message), identity)

  def run(
      rawOutput: Path,
      manifest: Path,
      manifestSha256: Sha256
  ): Either[EvidenceError, Unit] =
    for
      _ <- verifySeal(manifest, manifestSha256)
      _ <- verifySeal(CaseTable, CaseTableSha256)
      cases <- readCases(CaseTable)
      _ <-
        if cases.size == 6 then Right(())
        else Left(EvidenceError.InvalidCandidate(
          s"C04 requires six exact-core affine cases, got ${cases.size}"
        ))
      plan = comparisonPlan
      courts <- cases.zipWithIndex.foldLeft[
        Either[EvidenceError, Vector[CrossMethodCourt]]
      ](Right(Vector.empty)) { case (accumulated, (pair, index)) =>
        accumulated.flatMap { completed =>
          for
            handoff <- FlashalignHalfFlowAdapters
              .make(
                plan.lanes.find(_.lane == ComparisonLane.C1FlashalignLinear).get,
                plan.lanes.find(_.lane == ComparisonLane.C5HalfFlowFlashalignInitialization).get,
                new FlashalignHalfFlowImagePipeline(FlashalignRevision)
              )
              .left
              .map(error => EvidenceError.InvalidCandidate(error.message))
            c4Plan = plan.lanes.find(_.lane == ComparisonLane.C4HalfFlowExistingPipeline).get
            court <- CrossMethodEvidenceRunner
              .run(
                s"$CourtId-${pair.id}",
                plan,
                pair,
                Image4sNiftiPairLoader,
                handoff :+ new HalfFlowExistingPipelineAdapter(c4Plan.method)
              )
              .left
              .map(error => EvidenceError.AdapterFailure(error.message))
          yield
            println(
              s"C04 case ${index + 1}/${cases.size}: ${pair.id} -> " +
                court.rows
                  .filter(row => Set("C1", "C4", "C5")(row.lane.id))
                  .map(row => s"${row.lane.id}:${row.status.id}")
                  .mkString(",")
            )
            completed :+ court
        }
      }
      _ <- writeCourts(rawOutput, courts)
    yield ()

  private[flashalign] def comparisonPlan: CrossMethodPlan =
    val recipientControls = EvidenceHash.utf8(FrozenHalfFlowRecipient.ControlContract)
    val flashConfig = EvidenceHash.utf8(
      FlashalignHalfFlowImagePipeline.ConfigurationContract
    )
    val c5Config = EvidenceHash.utf8(
      s"flashalign-halfflow-c5-v1\n${flashConfig.hex}\n${recipientControls.hex}\ncomplete-moving-to-fixed\n"
    )
    val compositeRevision = EvidenceHash.utf8(
      s"$FlashalignRevision\n$HalfFlowRevision\n${recipientControls.hex}\n"
    ).hex
    val lanes = ComparisonLane.All.map {
      case lane @ ComparisonLane.C1FlashalignLinear => LanePlan(
        lane,
        MethodIdentity(
          "flashalign-linear",
          "affine",
          FlashalignRevision,
          flashConfig,
          None,
          None
        ),
        MapInterpretation.CompleteMovingToFixed,
        LaneControls(
          "flashalign-projected-patch-linear",
          "flashalign-support-aware-pyramid-v1",
          "all-finite-synthetic-support-v1",
          "12-parameter-affine",
          "coherent-header-world-identity-then-capture",
          None
        ),
        None
      )
      case lane @ ComparisonLane.C4HalfFlowExistingPipeline => LanePlan(
        lane,
        MethodIdentity(
          "halfflow-existing-pipeline",
          "frozen-halfflow-basinbridge-complete",
          HalfFlowRevision,
          recipientControls,
          None,
          None
        ),
        MapInterpretation.CompleteMovingToFixed,
        recipientLaneControls("recipient-existing-identity-midpoint", recipientControls),
        None
      )
      case lane @ ComparisonLane.C5HalfFlowFlashalignInitialization => LanePlan(
        lane,
        MethodIdentity(
          "halfflow-flashalign-initialization",
          "frozen-halfflow-basinbridge-complete",
          compositeRevision,
          c5Config,
          None,
          None
        ),
        MapInterpretation.CompleteMovingToFixed,
        recipientLaneControls("flashalign-affine-v5", recipientControls),
        None
      )
      case lane =>
        val interpretation = lane match
          case ComparisonLane.C0CommonAffine | ComparisonLane.C7ReverseTransfer =>
            MapInterpretation.CompleteMovingToFixed
          case _ => MapInterpretation.ResidualAfterSuppliedAffine
        LanePlan(
          lane,
          MethodIdentity(
            s"unexecuted-${lane.id.toLowerCase}",
            "outside-c04-affine-transfer-scope",
            "unexecuted",
            EvidenceHash.utf8(s"$CourtId-unexecuted-${lane.id}"),
            None,
            None
          ),
          interpretation,
          LaneControls(
            "outside-c04-scope",
            "not-executed",
            "not-executed",
            "not-executed",
            "not-executed",
            None
          ),
          Some(unavailableReason(lane))
        )
    }
    CrossMethodPlan(
      CourtId,
      lanes,
      IndependentScorePolicy(
        Vector("landmark_rms_mm", "landmark_p95_mm", "landmark_maximum_mm"),
        Vector(
          "projected_patch_objective",
          "halfflow_bridge_initial_match_mm",
          "halfflow_bridge_final_match_mm"
        ),
        candidateLossIsGroundTruth = false
      ),
      ComparisonLane.All.size
    )

  private def recipientLaneControls(
      initialization: String,
      recipientControls: Sha256
  ): LaneControls = LaneControls(
    "halfflow-frozen-recipient-objective",
    "all-finite-synthetic-support-v1",
    "all-finite-synthetic-support-v1",
    "recipient-frozen-complete-pipeline",
    initialization,
    Some(recipientControls)
  )

  private def unavailableReason(lane: ComparisonLane): String = lane match
    case ComparisonLane.C0CommonAffine =>
      "identity control is outside the C04 C1/C4/C5 transfer estimand"
    case ComparisonLane.C2FlashalignSmallStrain =>
      "image-backed Flashalign nonlinear objective is not qualified"
    case ComparisonLane.C3HalfFlowCommonAffine =>
      "canonical supplied-affine adapter is qualified but this common-affine lane is outside C04"
    case ComparisonLane.C6FlashalignThenHalfFlowResidual =>
      "constrained-warp to HalfFlow composition awaits nonlinear qualification"
    case ComparisonLane.C7ReverseTransfer =>
      "no justified reverse transfer was identified by C08"
    case other => s"${other.id} is executed in C04"

  private def verifySeal(path: Path, expected: Sha256): Either[EvidenceError, Unit] =
    if !Files.isRegularFile(path) then
      Left(EvidenceError.InputFailure("seal", s"missing $path"))
    else
      val actual = EvidenceHash.file(path)
      if actual == expected then Right(())
      else Left(EvidenceError.InputHashMismatch(path.toString, expected, actual))

  private def readCases(path: Path): Either[EvidenceError, Vector[PairCase]] =
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector
    lines.drop(1).filter(_.trim.nonEmpty).foldLeft[
      Either[EvidenceError, Vector[PairCase]]
    ](Right(Vector.empty)) { (accumulated, line) =>
      val fields = line.split("\\t", -1).toVector
      if fields.size != 10 then
        Left(EvidenceError.InvalidCandidate(
          s"expected 10 v5 case fields, got ${fields.size}"
        ))
      else if fields(2) != "exact-core" || fields(3) != "affine" then accumulated
      else accumulated.flatMap(values => parseCase(fields, line).map(values :+ _))
    }

  private def parseCase(
      fields: Vector[String],
      sourceRow: String
  ): Either[EvidenceError, PairCase] =
    for
      movingHash <- Sha256.parse(fields(6))
      fixedHash <- Sha256.parse(fields(7))
      truth <- parseMatrix(fields(8))
      _ <-
        if fields(9) == "interpolant-exact" then Right(())
        else Left(EvidenceError.InvalidCandidate(
          s"${fields(0)} must use interpolant-exact truth"
        ))
    yield PairCase(
      fields(0),
      fields(1),
      PairCohort.OrdinarySameSubject,
      LicensedArtifact(
        Paths.get(fields(4)),
        movingHash,
        "flashalign-linear-automatic-release-confirmation-v5 sealed analytic-synthetic fixture",
        "Apache-2.0"
      ),
      LicensedArtifact(
        Paths.get(fields(5)),
        fixedHash,
        "flashalign-linear-automatic-release-confirmation-v5 sealed analytic-synthetic fixture",
        "Apache-2.0"
      ),
      InitializationIdentity(
        "world-identity-v1",
        Identity,
        EvidenceHash.matrix(Identity)
      ),
      EvidenceHash.utf8(sourceRow),
      landmarks(truth)
    )

  private def parseMatrix(value: String): Either[EvidenceError, Vector[Double]] =
    try
      val parsed = value.split(",", -1).toVector.map(_.toDouble)
      if parsed.size != 16 then
        Left(EvidenceError.InvalidMatrixLength("truth", parsed.size))
      else if parsed.exists(number => !number.isFinite) then
        Left(EvidenceError.NonFinite("truth"))
      else Right(parsed)
    catch
      case NonFatal(error) => Left(EvidenceError.InvalidCandidate(
        Option(error.getMessage).getOrElse(error.getClass.getName)
      ))

  private def landmarks(truth: Vector[Double]): Vector[LandmarkTruth] =
    val xy = Vector(
      (-14.0, -9.0),
      (12.0, -8.0),
      (-9.0, 11.0),
      (11.0, 10.0),
      (0.0, 0.0)
    )
    (for
      z <- Vector(-14.0, -7.0, 0.0, 7.0, 14.0)
      (x, y) <- xy
    yield Vector(x, y, z)).zipWithIndex.map { case (moving, index) =>
      LandmarkTruth(
        f"landmark-$index%02d",
        moving,
        applyMatrix(truth, moving)
      )
    }

  private def writeCourts(
      output: Path,
      courts: Vector[CrossMethodCourt]
  ): Either[EvidenceError, Unit] =
    try
      Option(output.getParent).foreach(Files.createDirectories(_))
      val payload = courts.map(CrossMethodEvidenceJson.render).mkString("", "\n", "\n")
      Files.writeString(
        output,
        payload,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
      Right(())
    catch
      case NonFatal(error) => Left(EvidenceError.InputFailure(
        "raw-output",
        Option(error.getMessage).getOrElse(error.getClass.getName)
      ))

private final class AffineEvidenceMap(matrix: Vector[Double])
    extends EvidenceWorldMap3:
  def movingToFixed(
      worldMm: Vector[Double]
  ): Either[String, Vector[Double]] = Right(applyMatrix(matrix, worldMm))

private final class DensePullEvidenceMap[A, B](pull: DensePull[A, B])
    extends EvidenceWorldMap3:
  def movingToFixed(
      worldMm: Vector[Double]
  ): Either[String, Vector[Double]] =
    pull.from.grid.indexToFrame.inverse(worldMm).left.map(_.message)
      .flatMap: voxel =>
        trilinear(pull.from.grid, voxel) { index =>
          if fieldValid(pull.validity, index) then
            Vector(
              pull.sourceCoordinates.linearComponent(index, 0),
              pull.sourceCoordinates.linearComponent(index, 1),
              pull.sourceCoordinates.linearComponent(index, 2)
            )
          else Vector(Double.NaN, Double.NaN, Double.NaN)
        }.toRight("dense moving-to-fixed map has no valid trilinear support")

private def applyMatrix(
    matrix: Vector[Double],
    point: Vector[Double]
): Vector[Double] = Vector.tabulate(3)(row =>
  matrix(row * 4) * point(0) + matrix(row * 4 + 1) * point(1) +
    matrix(row * 4 + 2) * point(2) + matrix(row * 4 + 3)
)

private def fieldValid(validity: FieldValidity, index: Int): Boolean =
  validity match
    case FieldValidity.All          => true
    case FieldValidity.Mask(values) => values(index)

private def trilinear[A](
    grid: GridSpec,
    voxel: Vector[Double]
)(read: Int => A)(using numeric: TrilinearValue[A]): Option[A] =
  if voxel.length != 3 || voxel.exists(value => !value.isFinite) then None
  else
    val x = voxel(0)
    val y = voxel(1)
    val z = voxel(2)
    if x < 0.0 || y < 0.0 || z < 0.0 ||
      x > grid.shape(0).toDouble - 1.0 ||
      y > grid.shape(1).toDouble - 1.0 ||
      z > grid.shape(2).toDouble - 1.0
    then None
    else
      val x0 = math.floor(x).toInt
      val y0 = math.floor(y).toInt
      val z0 = math.floor(z).toInt
      val x1 = math.min(grid.shape(0) - 1, x0 + 1)
      val y1 = math.min(grid.shape(1) - 1, y0 + 1)
      val z1 = math.min(grid.shape(2) - 1, z0 + 1)
      val fx = x - x0.toDouble
      val fy = y - y0.toDouble
      val fz = z - z0.toDouble
      val samples = Vector.newBuilder[(A, Double)]
      var dz = 0
      while dz <= 1 do
        val iz = if dz == 0 then z0 else z1
        val wz = if dz == 0 then 1.0 - fz else fz
        var dy = 0
        while dy <= 1 do
          val iy = if dy == 0 then y0 else y1
          val wy = if dy == 0 then 1.0 - fy else fy
          var dx = 0
          while dx <= 1 do
            val ix = if dx == 0 then x0 else x1
            val wx = if dx == 0 then 1.0 - fx else fx
            val weight = wx * wy * wz
            if weight != 0.0 then
              samples += read(ix + grid.shape(0) * (iy + grid.shape(1) * iz)) -> weight
            dx += 1
          dy += 1
        dz += 1
      numeric.combine(samples.result())

private trait TrilinearValue[A]:
  def combine(values: Vector[(A, Double)]): Option[A]

private object TrilinearValue:
  given TrilinearValue[Double] with
    def combine(values: Vector[(Double, Double)]): Option[Double] =
      if values.exists(value => !value._1.isFinite) then None
      else Some(values.map(value => value._1 * value._2).sum)

  given TrilinearValue[Vector[Double]] with
    def combine(
        values: Vector[(Vector[Double], Double)]
    ): Option[Vector[Double]] =
      if values.exists(value =>
          value._1.length != 3 || value._1.exists(number => !number.isFinite)
        )
      then None
      else
        Some(Vector.tabulate(3)(component =>
          values.map(value => value._1(component) * value._2).sum
        ))

private def densePullHash[A, B](pull: DensePull[A, B]): Sha256 =
  val digest = MessageDigest.getInstance("SHA-256")
  def add(value: String): Unit =
    digest.update(value.getBytes(StandardCharsets.UTF_8))
    digest.update('\n'.toByte)
  pull.from.grid.dims.foreach(value => add(value.toString))
  pull.from.grid.indexToFrame.rowMajor.foreach(value => add(java.lang.Double.toHexString(value)))
  var index = 0
  while index < pull.from.grid.nVoxels do
    add(if fieldValid(pull.validity, index) then "1" else "0")
    var component = 0
    while component < 3 do
      add(java.lang.Double.toHexString(
        pull.sourceCoordinates.linearComponent(index, component)
      ))
      component += 1
    index += 1
  Sha256.unsafe(digest.digest().map(byte => f"${byte & 0xff}%02x").mkString)

private def failedCandidate(
    status: RunStatus,
    kind: String,
    detail: String,
    stages: AlgorithmStageTimes,
    metrics: Map[String, Option[Double]],
    work: WorkCounts = WorkCounts.Zero,
    coldPreparationMillis: Double = 0.0,
    sharedPreparationMillis: Double = 0.0
): CrossMethodCandidate = CrossMethodCandidate(
  status,
  accepted = false,
  Some(FailureDetail(kind, detail)),
  None,
  None,
  metrics,
  work,
  LaneCostEvidence(
    coldPreparationMillis,
    sharedPreparationMillis,
    stages,
    0L
  )
)
