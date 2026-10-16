package reframe4s.halfflow

import reframe4s.halfflow.internal.*

/** Frozen true-Neighborhood-CC controls for one BasinBridge alpha search. */
final case class BasinBridgeCcObjectiveConfig private (
    cc: NeighborhoodCcConfig,
    supportSigmaMm: Double,
    maximumStepMm: Double
)

object BasinBridgeCcObjectiveConfig:
  def make(
      cc: NeighborhoodCcConfig = NeighborhoodCcConfig.default,
      supportSigmaMm: Double = 1.5,
      maximumStepMm: Double = 12.0
  ): Either[RegistrationError, BasinBridgeCcObjectiveConfig] =
    if !supportSigmaMm.isFinite || supportSigmaMm < 0.0 then
      Left(RegistrationError.InvalidConfiguration("BasinBridge CC support sigma"))
    else if !maximumStepMm.isFinite || maximumStepMm <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("BasinBridge CC maximum step"))
    else Right(new BasinBridgeCcObjectiveConfig(cc, supportSigmaMm, maximumStepMm))

  val default: BasinBridgeCcObjectiveConfig =
    make().fold(error => throw new IllegalStateException(error.message), identity)

/** Diagnostics for the frozen support used by one bridge round. */
final case class BasinBridgeCcObjectiveDiagnostics(
    baselineLoss: Double,
    activeWindows: Int,
    support: CcSupportDiagnostics,
    supportPreparations: Int,
    candidateEvaluations: Int
)

/** Both native images warped into the current midpoint work frame. */
final case class BasinBridgeWorkImages[W](
    fixed: RegistrationImage[W],
    moving: RegistrationImage[W]
)

/**
  * Frozen-support true-CC objective for one BasinBridge round.
  *
  * The support and IRLS weights are prepared once from the current state. Every
  * alpha candidate is evaluated by rewarping the native images against that
  * same snapshot. A caller creates a new instance only after an update is
  * accepted, which makes support refresh an explicit round boundary.
  */
final class BasinBridgeFrozenCcObjective[W, F, M] private (
    fixed: RegistrationImage[F],
    moving: RegistrationImage[M],
    state: ForwardMidpoint[W, F, M],
    private val frozen: FrozenCcWeights,
    private val metricWorkspace: NeighborhoodCcWorkspace,
    private val baseline: NeighborhoodCcEvaluation,
    private val currentFixed: CcWarpBuffer,
    private val currentMoving: CcWarpBuffer,
    private var evaluations: Int
):
  def baselineLoss: Double = baseline.loss

  def trueCcLoss(candidate: ForwardMidpoint[W, F, M]): Either[RegistrationError, Double] =
    validateState(candidate).flatMap: _ =>
      val candidateFixed = CcWarpBuffer(candidate.work.grid.nVoxels)
      val candidateMoving = CcWarpBuffer(candidate.work.grid.nVoxels)
      HalfFlowCcSupport.warpNativeInto(
        fixed,
        moving,
        candidate,
        candidateFixed,
        candidateMoving
      )
      evaluations += 1
      NeighborhoodCc
        .valueWith(
          candidateFixed.values,
          candidateMoving.values,
          frozen,
          metricWorkspace
        )

  def workImages: Either[RegistrationError, BasinBridgeWorkImages[W]] =
    RegistrationImage.make(
      state.work,
      NeuroVol.fromLinear[Double](
        currentFixed.values.clone,
        state.work.grid.toNeuroSpace,
        "basinbridge-fixed-work"
      ),
      FieldValidity.copyMask(currentFixed.valid)
    ).flatMap: fixedImage =>
      RegistrationImage
        .make(
          state.work,
          NeuroVol.fromLinear[Double](
            currentMoving.values.clone,
            state.work.grid.toNeuroSpace,
            "basinbridge-moving-work"
          ),
          FieldValidity.copyMask(currentMoving.valid)
        )
        .map(movingImage => BasinBridgeWorkImages(fixedImage, movingImage))

  def diagnostics: BasinBridgeCcObjectiveDiagnostics =
    BasinBridgeCcObjectiveDiagnostics(
      baselineLoss,
      frozen.activeWindows,
      frozen.supportDiagnostics,
      supportPreparations = 1,
      candidateEvaluations = evaluations
    )

  private def validateState(candidate: ForwardMidpoint[W, F, M]): Either[RegistrationError, Unit] =
    if candidate.work.domain != state.work.domain then
      Left(RegistrationError.FrameMismatch("BasinBridge CC work", state.work.domain, candidate.work.domain))
    else if candidate.work.grid != state.work.grid then
      Left(RegistrationError.GridMismatch("BasinBridge CC candidate"))
    else if candidate.fixed.endpoint.domain != fixed.frame.domain then
      Left(RegistrationError.FrameMismatch("BasinBridge CC fixed endpoint", fixed.frame.domain, candidate.fixed.endpoint.domain))
    else if candidate.moving.endpoint.domain != moving.frame.domain then
      Left(RegistrationError.FrameMismatch("BasinBridge CC moving endpoint", moving.frame.domain, candidate.moving.endpoint.domain))
    else Right(())

object BasinBridgeFrozenCcObjective:
  def make[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      state: ForwardMidpoint[W, F, M],
      config: BasinBridgeCcObjectiveConfig = BasinBridgeCcObjectiveConfig.default
  ): Either[RegistrationError, BasinBridgeFrozenCcObjective[W, F, M]] =
    if fixed.frame.domain != state.fixed.endpoint.domain then
      Left(RegistrationError.FrameMismatch("BasinBridge CC fixed endpoint", state.fixed.endpoint.domain, fixed.frame.domain))
    else if moving.frame.domain != state.moving.endpoint.domain then
      Left(RegistrationError.FrameMismatch("BasinBridge CC moving endpoint", state.moving.endpoint.domain, moving.frame.domain))
    else
      val currentFixed = CcWarpBuffer(state.work.grid.nVoxels)
      val currentMoving = CcWarpBuffer(state.work.grid.nVoxels)
      HalfFlowCcSupport.warpNativeInto(fixed, moving, state, currentFixed, currentMoving)
      val supportSource = PrimitiveBuffers.ofSize[Double](state.work.grid.nVoxels)
      val support = PrimitiveBuffers.ofSize[Double](state.work.grid.nVoxels)
      val supportWorkspace = GaussianWorkspace(state.work.grid)
      HalfFlowCcSupport.buildSupport(
        currentFixed,
        currentMoving,
        state.work.grid,
        config.maximumStepMm,
        config.supportSigmaMm,
        supportSource,
        support,
        supportWorkspace
      )
      val metricWorkspace = NeighborhoodCcWorkspace(state.work.grid)
      for
        frozen <- NeighborhoodCc.prepareWith(
          currentFixed.values,
          currentMoving.values,
          support,
          state.work.grid,
          config.cc,
          metricWorkspace
        )
        baseline <- NeighborhoodCc.valueAndGradientWith(
          currentFixed.values,
          currentMoving.values,
          frozen,
          metricWorkspace,
          NeighborhoodCcBuffer(state.work.grid)
        )
      yield
        new BasinBridgeFrozenCcObjective(
          fixed,
          moving,
          state,
          frozen,
          metricWorkspace,
          baseline,
          currentFixed,
          currentMoving,
          evaluations = 0
        )
