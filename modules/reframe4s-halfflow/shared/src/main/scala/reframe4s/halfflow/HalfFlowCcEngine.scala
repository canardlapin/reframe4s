package reframe4s.halfflow

import ravel.NDArray as RavelArray
import scala.util.boundary
import scala.util.boundary.break
import reframe4s.halfflow.internal.*

enum HalfFlowCcMetric:
  case TrueNeighborhoodCc
  case StandardizedCenter(feature: T1FeatureConfig)

final case class HalfFlowCcLevel private (
    shrink: Int,
    pyramidSigmaMm: Double,
    cc: NeighborhoodCcConfig,
    smoothSigmaMm: Double,
    maximumStepMm: Double,
    targetAcceptedSteps: Int,
    maximumAttempts: Int,
    metric: HalfFlowCcMetric
)

object HalfFlowCcLevel:
  def make(
      shrink: Int,
      pyramidSigmaMm: Double,
      cc: NeighborhoodCcConfig = NeighborhoodCcConfig.default,
      smoothSigmaMm: Double,
      maximumStepMm: Double,
      targetAcceptedSteps: Int,
      maximumAttempts: Int,
      metric: HalfFlowCcMetric = HalfFlowCcMetric.TrueNeighborhoodCc
  ): Either[RegistrationError, HalfFlowCcLevel] =
    if shrink <= 0 then Left(RegistrationError.InvalidConfiguration("HalfFlow-CC shrink"))
    else if !pyramidSigmaMm.isFinite || pyramidSigmaMm < 0.0 then
      Left(RegistrationError.InvalidConfiguration("HalfFlow-CC pyramid sigma"))
    else if !smoothSigmaMm.isFinite || smoothSigmaMm < 0.0 then
      Left(RegistrationError.InvalidConfiguration("HalfFlow-CC smooth sigma"))
    else if !maximumStepMm.isFinite || maximumStepMm <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("HalfFlow-CC maximum step"))
    else if targetAcceptedSteps <= 0 || maximumAttempts < targetAcceptedSteps then
      Left(RegistrationError.InvalidConfiguration("HalfFlow-CC step budgets"))
    else
      Right(
        new HalfFlowCcLevel(
          shrink,
          pyramidSigmaMm,
          cc,
          smoothSigmaMm,
          maximumStepMm,
          targetAcceptedSteps,
          maximumAttempts,
          metric
        )
      )

final case class HalfFlowCcPlan private (
    levels: Vector[HalfFlowCcLevel],
    supportSigmaMm: Double,
    minimumSupportWeight: Double,
    rankOneEnergyEpsilon: Double,
    minimumUsefulStepMm: Double,
    maximumIntegrationInverseErrorMm: Double,
    geometry: ForwardGeometryConfig,
    control: HalfFlowCcControlConfig,
    exportConfig: ResidualInverseConfig,
    action: HalfFlowCcAction
)

/** Experimental action axis used by the G5 architecture falsification.
  *
  * Both choices parameterize `v` as the relative fixed-to-moving update. The
  * midpoint action applies opposite half flows; the anchored action leaves the
  * fixed arm unchanged and applies the complete negative flow to the moving
  * arm. The production default remains symmetric midpoint.
  */
enum HalfFlowCcAction:
  case SymmetricMidpoint
  case FixedAnchor

object HalfFlowCcPlan:
  def make(
      levels: Vector[HalfFlowCcLevel],
      supportSigmaMm: Double = 1.5,
      minimumSupportWeight: Double = 1e-6,
      rankOneEnergyEpsilon: Double = 1e-12,
      minimumUsefulStepMm: Double = 1e-5,
      maximumIntegrationInverseErrorMm: Double = 0.02,
      geometry: ForwardGeometryConfig = ForwardGeometryConfig(),
      control: HalfFlowCcControlConfig = HalfFlowCcControlConfig.default,
      exportConfig: ResidualInverseConfig = ResidualInverseConfig.default,
      action: HalfFlowCcAction = HalfFlowCcAction.SymmetricMidpoint
  ): Either[RegistrationError, HalfFlowCcPlan] =
    val ordered = levels.nonEmpty && levels.last.shrink == 1 &&
      levels.indices.drop(1).forall(index => levels(index - 1).shrink > levels(index).shrink)
    val numeric =
      supportSigmaMm.isFinite && supportSigmaMm >= 0.0 &&
        minimumSupportWeight.isFinite && minimumSupportWeight > 0.0 &&
        rankOneEnergyEpsilon.isFinite && rankOneEnergyEpsilon > 0.0 &&
        minimumUsefulStepMm.isFinite && minimumUsefulStepMm >= 0.0 &&
        maximumIntegrationInverseErrorMm.isFinite && maximumIntegrationInverseErrorMm >= 0.0
    if !ordered then Left(RegistrationError.InvalidConfiguration("HalfFlow-CC levels"))
    else if !numeric then Left(RegistrationError.InvalidConfiguration("HalfFlow-CC numerical plan"))
    else
      Right(
        new HalfFlowCcPlan(
          levels,
          supportSigmaMm,
          minimumSupportWeight,
          rankOneEnergyEpsilon,
          minimumUsefulStepMm,
          maximumIntegrationInverseErrorMm,
          geometry,
          control,
          exportConfig,
          action
        )
      )

enum HalfFlowCcTermination:
  case AcceptedStepBudget
  case AttemptBudget
  case ControllerExhausted
  case Stationary

final case class HalfFlowCcAttemptDiagnostics(
    attempt: Int,
    accepted: Boolean,
    currentLoss: Double,
    candidateLoss: Double,
    maximumStepMm: Double,
    activeFraction: Double,
    edgeActiveFraction: Double,
    geometry: GeometryVerdict,
    numerical: NumericalVerdict,
    legacyInverseSafe: Option[Boolean],
    legacyInverseMaximumMm: Option[Double],
    controlTransitions: Vector[ControlTransition]
)

final case class HalfFlowCcLevelDiagnostics(
    shrink: Int,
    initialLoss: Double,
    finalLoss: Double,
    acceptedSteps: Int,
    attempts: Int,
    termination: HalfFlowCcTermination,
    trace: Vector[HalfFlowCcAttemptDiagnostics]
)

final case class HalfFlowCcDiagnostics(levels: Vector[HalfFlowCcLevelDiagnostics]):
  val acceptedSteps: Int = levels.map(_.acceptedSteps).sum
  val attempts: Int = levels.map(_.attempts).sum

final case class HalfFlowCcResult[F, M](
    transform: InversePair[F, M],
    diagnostics: HalfFlowCcDiagnostics,
    exportDiagnostics: ForwardMidpointExport[F, M]
)

final case class HalfFlowCcOptimization[W, F, M](
    state: ForwardMidpoint[W, F, M],
    diagnostics: HalfFlowCcDiagnostics
)

enum HalfFlowCcError:
  case Registration(error: RegistrationError)
  case Export(error: ForwardExportError)
  case MovingOptimizationMaskForbidden
  case LegacyInverseGateRequiresSymmetricMidpoint
  case RegriddedTopologyInvalid(shrink: Int, verdict: GeometryVerdict)

  def message: String =
    this match
      case Registration(error) => error.message
      case Export(error) => error.message
      case MovingOptimizationMaskForbidden =>
        "HalfFlow-CC moving validity must be full; held-out moving masks are evaluation-only"
      case LegacyInverseGateRequiresSymmetricMidpoint =>
        "the legacy accumulated-inverse gate is defined only for the symmetric-midpoint ablation"
      case RegriddedTopologyInvalid(shrink, verdict) =>
        s"HalfFlow-CC regrid at shrink $shrink violates accumulated topology: $verdict"

private final case class CcLevelImage[A](image: RegistrationImage[A], sampler: DenseFieldSampler)

private final case class LegacyInverseGate[W, F, M](
    state: Midpoint[W, F, M],
    config: GuardConfig
)

private final class CcWarpBuffer private (
    val values: Array[Double],
    val valid: Array[Boolean]
)

private object CcWarpBuffer:
  def apply(size: Int): CcWarpBuffer =
    new CcWarpBuffer(PrimitiveBuffers.ofSize[Double](size), PrimitiveBuffers.ofSize[Boolean](size))

private final class CcStepWorkspace[A] private (
    val frame: Frame[A],
    val spatial: MaskedLocalStatsWorkspace,
    val fixedSpatialGradient: Array[Double],
    val movingSpatialGradient: Array[Double],
    val fixedGradientValid: Array[Boolean],
    val movingGradientValid: Array[Boolean],
    val raw: Array[Double],
    val component: Array[Double],
    val componentSmoothed: Array[Double],
    val smoothedWeight: Array[Double],
    val velocity: Array[Double],
    val gaussian: GaussianWorkspace,
    val gaussianReduction: GaussianReduction
)

private object CcStepWorkspace:
  def apply[A](frame: Frame[A]): CcStepWorkspace[A] =
    val n = frame.grid.nVoxels
    new CcStepWorkspace(
      frame,
      MaskedLocalStatsWorkspace(frame.grid),
      PrimitiveBuffers.ofSize[Double](3 * n),
      PrimitiveBuffers.ofSize[Double](3 * n),
      PrimitiveBuffers.ofSize[Boolean](n),
      PrimitiveBuffers.ofSize[Boolean](n),
      PrimitiveBuffers.ofSize[Double](3 * n),
      PrimitiveBuffers.ofSize[Double](n),
      PrimitiveBuffers.ofSize[Double](n),
      PrimitiveBuffers.ofSize[Double](n),
      PrimitiveBuffers.ofSize[Double](3 * n),
      GaussianWorkspace(frame.grid),
      GaussianReduction()
    )

final case class PointwiseRankOneSummary(activeVoxels: Int, maximumNorm: Double)

object PointwiseRankOne:
  def solveInto(
      gradient: Array[Double],
      validity: Array[Boolean],
      voxels: Int,
      loss: Double,
      damping: Double,
      energyEpsilon: Double,
      scale: Double,
      destination: Array[Double]
  ): PointwiseRankOneSummary =
    require(gradient.length >= 3 * voxels && destination.length >= 3 * voxels)
    require(validity.length >= voxels)
    require(loss.isFinite && loss >= 0.0)
    require(damping.isFinite && damping > 0.0)
    require(energyEpsilon.isFinite && energyEpsilon > 0.0)
    require(scale.isFinite && scale > 0.0)
    val energyScale = 2.0 * loss + energyEpsilon
    var active = 0
    var maximum = 0.0
    var index = 0
    while index < voxels do
      if validity(index) then
        val g0 = gradient(index)
        val g1 = gradient(index + voxels)
        val g2 = gradient(index + 2 * voxels)
        val normSquared = g0 * g0 + g1 * g1 + g2 * g2
        val factor = -scale / (damping + normSquared / energyScale)
        val v0 = factor * g0
        val v1 = factor * g1
        val v2 = factor * g2
        destination(index) = v0
        destination(index + voxels) = v1
        destination(index + 2 * voxels) = v2
        maximum = math.max(maximum, math.sqrt(v0 * v0 + v1 * v1 + v2 * v2))
        active += 1
      else
        destination(index) = 0.0
        destination(index + voxels) = 0.0
        destination(index + 2 * voxels) = 0.0
      index += 1
    PointwiseRankOneSummary(active, maximum)

object HalfFlowCc:
  private trait ObjectiveSession[W]:
    def loss: Double
    def activeFraction: Double
    def edgeActiveFraction: Double
    def smoothingWeight: Array[Double]
    def initialize(fixed: CcWarpBuffer, moving: CcWarpBuffer): Either[RegistrationError, Unit]
    def candidateValue(fixed: CcWarpBuffer, moving: CcWarpBuffer): Either[RegistrationError, Double]
    def gradientInto(
        fixed: CcWarpBuffer,
        moving: CcWarpBuffer,
        workspace: CcStepWorkspace[W]
    ): Either[RegistrationError, Unit]

  private final class TrueCcSession[W](
      frame: Frame[W],
      level: HalfFlowCcLevel,
      plan: HalfFlowCcPlan
  ) extends ObjectiveSession[W]:
    private val supportSource = PrimitiveBuffers.ofSize[Double](frame.grid.nVoxels)
    private val support = PrimitiveBuffers.ofSize[Double](frame.grid.nVoxels)
    private val supportWorkspace = GaussianWorkspace(frame.grid)
    private val metricWorkspace = NeighborhoodCcWorkspace(frame.grid)
    private val metricBuffer = NeighborhoodCcBuffer(frame.grid)
    private var frozen: FrozenCcWeights = null
    private var evaluation: NeighborhoodCcEvaluation = null

    def loss: Double = evaluation.loss
    def activeFraction: Double = evaluation.diagnostics.support.activeFraction
    def edgeActiveFraction: Double = evaluation.diagnostics.support.edgeBandActiveFraction
    def smoothingWeight: Array[Double] = frozen.support

    def initialize(fixed: CcWarpBuffer, moving: CcWarpBuffer): Either[RegistrationError, Unit] =
      buildSupport(
        fixed,
        moving,
        frame.grid,
        level.maximumStepMm,
        plan,
        supportSource,
        support,
        supportWorkspace
      )
      for
        nextFrozen <- NeighborhoodCc.prepareWith(
          fixed.values,
          moving.values,
          support,
          frame.grid,
          level.cc,
          metricWorkspace
        )
        nextEvaluation <- NeighborhoodCc.valueAndGradientWith(
          fixed.values,
          moving.values,
          nextFrozen,
          metricWorkspace,
          metricBuffer
        )
      yield
        frozen = nextFrozen
        evaluation = nextEvaluation

    def candidateValue(fixed: CcWarpBuffer, moving: CcWarpBuffer): Either[RegistrationError, Double] =
      NeighborhoodCc.valueWith(fixed.values, moving.values, frozen, metricWorkspace)

    def gradientInto(
        fixed: CcWarpBuffer,
        moving: CcWarpBuffer,
        workspace: CcStepWorkspace[W]
    ): Either[RegistrationError, Unit] =
      spatialGradients(fixed, moving, frame, workspace)
      val n = frame.grid.nVoxels
      var index = 0
      while index < n do
        val active = workspace.fixedGradientValid(index) && workspace.movingGradientValid(index) &&
          frozen.support(index) > 0.0
        if active then
          var component = 0
          while component < 3 do
            val offset = component * n + index
            val movingGradient =
              evaluation.movingIntensityGradient(index) * workspace.movingSpatialGradient(offset)
            workspace.raw(offset) = plan.action match
              case HalfFlowCcAction.SymmetricMidpoint =>
                0.5 * (
                  evaluation.fixedIntensityGradient(index) * workspace.fixedSpatialGradient(offset) -
                    movingGradient
                )
              case HalfFlowCcAction.FixedAnchor => -movingGradient
            component += 1
          workspace.movingGradientValid(index) = true
        else
          workspace.raw(index) = 0.0
          workspace.raw(index + n) = 0.0
          workspace.raw(index + 2 * n) = 0.0
          workspace.movingGradientValid(index) = false
        index += 1
      Right(())

  private final class StandardizedCenterSession[W](
      frame: Frame[W],
      featureConfig: T1FeatureConfig,
      action: HalfFlowCcAction
  ) extends ObjectiveSession[W]:
    private val workspace = T1FeatureWorkspace(frame, featureConfig.radii.length)
    private val currentFixedBuffer = T1FeatureBuffer(frame, featureConfig)
    private val currentMovingBuffer = T1FeatureBuffer(frame, featureConfig)
    private val candidateFixedBuffer = T1FeatureBuffer(frame, featureConfig)
    private val candidateMovingBuffer = T1FeatureBuffer(frame, featureConfig)
    private val residualScratch = new Array[Double](frame.grid.nVoxels)
    private val weight = PrimitiveBuffers.ofSize[Double](frame.grid.nVoxels)
    private var currentFixed: T1FeatureVolume[W] = null
    private var currentMoving: T1FeatureVolume[W] = null
    private var epsilon = Double.NaN
    private var currentLoss = Double.NaN
    private var active = 0
    private var edgeActive = 0

    def loss: Double = currentLoss
    def activeFraction: Double = active.toDouble / frame.grid.nVoxels.toDouble
    def edgeActiveFraction: Double = edgeActive.toDouble / math.max(1, edgeVoxelCount(frame.grid)).toDouble
    def smoothingWeight: Array[Double] = weight

    def initialize(fixed: CcWarpBuffer, moving: CcWarpBuffer): Either[RegistrationError, Unit] =
      for
        nextFixed <- features(fixed, currentFixedBuffer)
        nextMoving <- features(moving, currentMovingBuffer)
        _ <- setCurrent(nextFixed, nextMoving)
      yield ()

    def candidateValue(fixed: CcWarpBuffer, moving: CcWarpBuffer): Either[RegistrationError, Double] =
      for
        nextFixed <- features(fixed, candidateFixedBuffer)
        nextMoving <- features(moving, candidateMovingBuffer)
        value <- valueOnFrozenSupport(nextFixed, nextMoving)
      yield value

    def gradientInto(
        fixed: CcWarpBuffer,
        moving: CcWarpBuffer,
        step: CcStepWorkspace[W]
    ): Either[RegistrationError, Unit] =
      val n = frame.grid.nVoxels
      var index = 0
      while index < n do
        val valid = currentFixed.valid(index) && currentMoving.valid(index)
        step.movingGradientValid(index) = valid
        if valid then
          val residual = currentFixed.values(index) - currentMoving.values(index)
          val derivative = residual / math.sqrt(residual * residual + epsilon * epsilon) / active.toDouble
          var component = 0
          while component < 3 do
            val offset = component * n + index
            val fixedGradient = currentFixed.gradients(offset)
            val movingGradient = currentMoving.gradients(offset)
            step.raw(offset) = action match
              case HalfFlowCcAction.SymmetricMidpoint => 0.5 * derivative * (fixedGradient + movingGradient)
              case HalfFlowCcAction.FixedAnchor => -derivative * movingGradient
            component += 1
        else
          step.raw(index) = 0.0
          step.raw(index + n) = 0.0
          step.raw(index + 2 * n) = 0.0
        index += 1
      Right(())

    private def features(
        source: CcWarpBuffer,
        destination: T1FeatureBuffer[W]
    ): Either[RegistrationError, T1FeatureVolume[W]] =
      T1Features.computeInto(
        frame,
        source.values,
        FieldValidity.copyMask(source.valid),
        featureConfig,
        workspace,
        destination
      )

    private def setCurrent(
        fixed: T1FeatureVolume[W],
        moving: T1FeatureVolume[W]
    ): Either[RegistrationError, Unit] =
      val n = frame.grid.nVoxels
      var count = 0
      var edge = 0
      var index = 0
      while index < n do
        if fixed.valid(index) && moving.valid(index) then
          weight(index) = 1.0
          residualScratch(count) = math.abs(fixed.values(index) - moving.values(index))
          count += 1
          if isEdge(frame.grid, index) then edge += 1
        else weight(index) = 0.0
        index += 1
      if count < featureConfig.minimumActiveVoxels then
        Left(RegistrationError.InsufficientSupport("standardized-center objective", count, featureConfig.minimumActiveVoxels))
      else
        epsilon = math.max(1e-4, 0.10 * InPlaceMedian(residualScratch, count))
        currentFixed = fixed
        currentMoving = moving
        active = count
        edgeActive = edge
        valueOnFrozenSupport(fixed, moving).map: value =>
          currentLoss = value

    private def valueOnFrozenSupport(
        fixed: T1FeatureVolume[W],
        moving: T1FeatureVolume[W]
    ): Either[RegistrationError, Double] =
      val n = frame.grid.nVoxels
      var value = 0.0
      var used = 0
      var missing = false
      var index = 0
      while index < n && !missing do
        if currentFixed == null || (currentFixed.valid(index) && currentMoving.valid(index)) then
          if !fixed.valid(index) || !moving.valid(index) then missing = true
          else
            val residual = fixed.values(index) - moving.values(index)
            value += math.sqrt(residual * residual + epsilon * epsilon) - epsilon
            used += 1
        index += 1
      if missing || used != active then
        Left(RegistrationError.InsufficientSupport("standardized-center candidate", used, active))
      else Right(value / active.toDouble)

  private def objectiveSession[W](
      frame: Frame[W],
      level: HalfFlowCcLevel,
      plan: HalfFlowCcPlan
  ): ObjectiveSession[W] =
    level.metric match
      case HalfFlowCcMetric.TrueNeighborhoodCc => TrueCcSession(frame, level, plan)
      case HalfFlowCcMetric.StandardizedCenter(feature) => StandardizedCenterSession(frame, feature, plan.action)

  def register[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      initial: ForwardMidpoint[W, F, M],
      plan: HalfFlowCcPlan
  ): Either[HalfFlowCcError, HalfFlowCcResult[F, M]] =
    optimize(fixed, moving, initial, plan).flatMap: optimized =>
      ForwardMidpointExporter.build(optimized.state, plan.exportConfig) match
        case Left(error) => Left(HalfFlowCcError.Export(error))
        case Right(exported) =>
          Right(HalfFlowCcResult(exported.transform, optimized.diagnostics, exported))

  def optimize[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      initial: ForwardMidpoint[W, F, M],
      plan: HalfFlowCcPlan
  ): Either[HalfFlowCcError, HalfFlowCcOptimization[W, F, M]] =
    optimizeInternal(fixed, moving, initial, plan, None)

  /** G5-only control that restores the previous inverse-tracked accumulated
    * gate while retaining the true-CC lane-A objective and step.
    *
    * The tracked midpoint is a shadow used solely to reject candidates. The
    * authoritative result remains the two-forward-map state.
    */
  def optimizeWithLegacyInverseGate[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      initial: Midpoint[W, F, M],
      plan: HalfFlowCcPlan,
      guard: GuardConfig
  ): Either[HalfFlowCcError, HalfFlowCcOptimization[W, F, M]] =
    if plan.action != HalfFlowCcAction.SymmetricMidpoint then
      Left(HalfFlowCcError.LegacyInverseGateRequiresSymmetricMidpoint)
    else
      optimizeInternal(
        fixed,
        moving,
        ForwardMidpoint.fromLegacy(initial),
        plan,
        Some(LegacyInverseGate(initial, guard))
      )

  private def optimizeInternal[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      initial: ForwardMidpoint[W, F, M],
      plan: HalfFlowCcPlan,
      initialLegacyGate: Option[LegacyInverseGate[W, F, M]]
  ): Either[HalfFlowCcError, HalfFlowCcOptimization[W, F, M]] =
    moving.validity match
      case FieldValidity.Mask(_) => Left(HalfFlowCcError.MovingOptimizationMaskForbidden)
      case FieldValidity.All =>
        validateInitial(fixed, moving, initial).flatMap: _ =>
          val needsPyramid = plan.levels.exists(level => level.shrink != 1 || level.pyramidSigmaMm > 0.0)
          val pyramidWorkspace =
            if needsPyramid then Some(PyramidWorkspace(math.max(fixed.frame.grid.nVoxels, moving.frame.grid.nVoxels)))
            else None
          var state = initial
          var legacyGate = initialLegacyGate
          var control = HalfFlowCcControlState.initial(plan.control)
          val diagnostics = Vector.newBuilder[HalfFlowCcLevelDiagnostics]
          var levelIndex = 0
          var failure = Option.empty[HalfFlowCcError]
          while levelIndex < plan.levels.length && failure.isEmpty do
            val level = plan.levels(levelIndex)
            val work = Frame[W](initial.work.domain, HalfFlowKernels.pyramidGrid(initial.work.grid, level.shrink))
            val fixedLevel = pyramid(fixed, level, pyramidWorkspace)
            val movingLevel = pyramid(moving, level, pyramidWorkspace)
            (fixedLevel, movingLevel) match
              case (Right(nextFixed), Right(nextMoving)) =>
                val nextState = state.regrid(work, nextFixed.image.frame, nextMoving.image.frame)
                val nextLegacy = legacyGate match
                  case None => Right(None)
                  case Some(previous) =>
                    previous.state
                      .regrid(work, nextFixed.image.frame, nextMoving.image.frame)
                      .map(state => Some(LegacyInverseGate(state, previous.config)))
                (nextState, nextLegacy) match
                  case (Left(error), _) => failure = Some(HalfFlowCcError.Registration(error))
                  case (_, Left(error)) => failure = Some(HalfFlowCcError.Registration(error))
                  case (Right(regridded), Right(regriddedLegacy)) =>
                    ForwardGeometry.accumulated(regridded, plan.geometry)._1 match
                      case verdict if verdict != GeometryVerdict.Valid =>
                        failure = Some(HalfFlowCcError.RegriddedTopologyInvalid(level.shrink, verdict))
                      case _ =>
                        if levelIndex > 0 then
                          control = HalfFlowCcControl
                            .observe(control, ControlObservation.LevelTransition, plan.control)
                            .after
                        refineLevel(nextFixed, nextMoving, regridded, level, plan, control, regriddedLegacy) match
                          case Left(error) => failure = Some(error)
                          case Right((accepted, levelDiagnostics, nextControl, nextLegacyGate)) =>
                            state = accepted
                            control = nextControl
                            legacyGate = nextLegacyGate
                            diagnostics += levelDiagnostics
              case (Left(error), _) => failure = Some(HalfFlowCcError.Registration(error))
              case (_, Left(error)) => failure = Some(HalfFlowCcError.Registration(error))
            levelIndex += 1
          failure match
            case Some(error) => Left(error)
            case None => Right(HalfFlowCcOptimization(state, HalfFlowCcDiagnostics(diagnostics.result())))

  private def refineLevel[W, F, M](
      fixed: CcLevelImage[F],
      moving: CcLevelImage[M],
      start: ForwardMidpoint[W, F, M],
      level: HalfFlowCcLevel,
      plan: HalfFlowCcPlan,
      initialControl: HalfFlowCcControlState,
      initialLegacyGate: Option[LegacyInverseGate[W, F, M]]
  ): Either[
    HalfFlowCcError,
    (ForwardMidpoint[W, F, M], HalfFlowCcLevelDiagnostics, HalfFlowCcControlState, Option[LegacyInverseGate[W, F, M]])
  ] = boundary:
    val n = start.work.grid.nVoxels
    val currentFixed = CcWarpBuffer(n)
    val currentMoving = CcWarpBuffer(n)
    val candidateFixed = CcWarpBuffer(n)
    val candidateMoving = CcWarpBuffer(n)
    val stepWorkspace = CcStepWorkspace(start.work)
    val identityPull = DensePull.identity(start.work)
    val metricSession = objectiveSession(start.work, level, plan)

    var state = start
    var legacyGate = initialLegacyGate
    var control = initialControl
    var accepted = 0
    var attempts = 0
    var termination = Option.empty[HalfFlowCcTermination]
    val trace = Vector.newBuilder[HalfFlowCcAttemptDiagnostics]
    warp(fixed, moving, state, currentFixed, currentMoving)
    metricSession
      .initialize(currentFixed, currentMoving)
      .fold(error => break(Left(HalfFlowCcError.Registration(error))), identity)
    val initialLoss = metricSession.loss

    while termination.isEmpty do
      if accepted >= level.targetAcceptedSteps then termination = Some(HalfFlowCcTermination.AcceptedStepBudget)
      else if attempts >= level.maximumAttempts then termination = Some(HalfFlowCcTermination.AttemptBudget)
      else if control.exhausted(plan.control) then termination = Some(HalfFlowCcTermination.ControllerExhausted)
      else
        val attemptTransitions = Vector.newBuilder[ControlTransition]
        val step = rankOneStep(
          currentFixed,
          currentMoving,
          metricSession,
          start.work,
          level,
          plan,
          control,
          stepWorkspace
        ).fold(error => break(Left(HalfFlowCcError.Registration(error))), identity)
        if step._2 <= plan.minimumUsefulStepMm then
          termination = Some(HalfFlowCcTermination.Stationary)
        else
          val flowConfig = FlowConfig(
            minimumSquaringDepth = control.squarings,
            maximumSquaringDepth = plan.control.maximumSquarings
          )
          val flow = PairedScalingAndSquaring
            .expHalfPair(step._1, flowConfig)
            .fold(error => break(Left(HalfFlowCcError.Registration(error))), identity)
          val integrated = HalfStep.fromPairedFlow(flow)
          val half = plan.action match
            case HalfFlowCcAction.SymmetricMidpoint => integrated
            case HalfFlowCcAction.FixedAnchor =>
              HalfStep
                .make(identityPull, flow.pair.backward)
                .fold(error => break(Left(HalfFlowCcError.Registration(error))), identity)
          // Integration accuracy is a property of the complete +/- pair even
          // when the ablation subsequently discards the positive map.
          val numerical = HalfStepNumerics.evaluate(integrated, plan.maximumIntegrationInverseErrorMm)
          val numericalTransition = HalfFlowCcControl.observe(
            control,
            ControlObservation.Numerical(numerical),
            plan.control
          )
          attemptTransitions += numericalTransition
          control = numericalTransition.after
          numerical match
            case NumericalVerdict.IncreaseIntegrationDepth(_) =>
              attempts += 1
              trace += attemptDiagnostic(
                attempts,
                accepted = false,
                metricSession,
                Double.NaN,
                step._2,
                GeometryVerdict.Valid,
                numerical,
                attemptTransitions.result()
              )
            case _ =>
              val incremental = ForwardGeometry.incremental(half, plan.geometry)._1
              val incrementalTransition = HalfFlowCcControl.observe(
                control,
                ControlObservation.Geometry(incremental),
                plan.control
              )
              attemptTransitions += incrementalTransition
              control = incrementalTransition.after
              incremental match
                case GeometryVerdict.Valid =>
                  val candidate = state
                    .advance(half)
                    .fold(error => break(Left(HalfFlowCcError.Registration(error))), identity)
                  val accumulated = ForwardGeometry.accumulated(candidate, plan.geometry)._1
                  if accumulated != GeometryVerdict.Valid then
                    val transition = HalfFlowCcControl.observe(
                      control,
                      ControlObservation.Geometry(accumulated),
                      plan.control
                    )
                    attemptTransitions += transition
                    control = transition.after
                    attempts += 1
                    trace += attemptDiagnostic(
                      attempts,
                      accepted = false,
                      metricSession,
                      Double.NaN,
                      step._2,
                      accumulated,
                      numerical,
                      attemptTransitions.result()
                    )
                  else
                    val legacyCandidate = legacyGate.map: current =>
                      val tracked = current.state
                        .advance(flow.pair)
                        .fold(error => break(Left(HalfFlowCcError.Registration(error))), identity)
                      val relative = tracked.relativeResidual
                        .fold(error => break(Left(HalfFlowCcError.Registration(error))), identity)
                      val report = TopologyGuard.evaluate(
                        relative,
                        tracked.relativeResidualGuardConfig(current.config)
                      )
                      (LegacyInverseGate(tracked, current.config), report)
                    val legacySafe = legacyCandidate.map(_._2.safe)
                    val legacyMaximum = legacyCandidate.map(pair => maximumInverseError(pair._2))
                    if legacySafe.contains(false) then
                      val transition = HalfFlowCcControl.observe(
                        control,
                        ControlObservation.Objective(ObjectiveVerdict.Rejected(metricSession.loss, Double.NaN)),
                        plan.control
                      )
                      attemptTransitions += transition
                      control = transition.after
                      attempts += 1
                      trace += attemptDiagnostic(
                        attempts,
                        accepted = false,
                        metricSession,
                        Double.NaN,
                        step._2,
                        GeometryVerdict.Valid,
                        numerical,
                        attemptTransitions.result(),
                        legacySafe,
                        legacyMaximum
                      )
                    else
                      warp(fixed, moving, candidate, candidateFixed, candidateMoving)
                      val candidateLoss = metricSession
                        .candidateValue(candidateFixed, candidateMoving)
                        .getOrElse(Double.NaN)
                      val objectiveVerdict =
                        if candidateLoss.isFinite && candidateLoss < metricSession.loss then
                          ObjectiveVerdict.Accepted(metricSession.loss - candidateLoss)
                        else ObjectiveVerdict.Rejected(metricSession.loss, candidateLoss)
                      val transition = HalfFlowCcControl.observe(
                        control,
                        ControlObservation.Objective(objectiveVerdict),
                        plan.control
                      )
                      attemptTransitions += transition
                      control = transition.after
                      attempts += 1
                      val isAccepted = objectiveVerdict.isInstanceOf[ObjectiveVerdict.Accepted]
                      trace += attemptDiagnostic(
                        attempts,
                        isAccepted,
                        metricSession,
                        candidateLoss,
                        step._2,
                        GeometryVerdict.Valid,
                        numerical,
                        attemptTransitions.result(),
                        legacySafe,
                        legacyMaximum
                      )
                      if isAccepted then
                        state = candidate
                        legacyGate = legacyCandidate.map(_._1)
                        accepted += 1
                        copy(candidateFixed, currentFixed)
                        copy(candidateMoving, currentMoving)
                        metricSession
                          .initialize(currentFixed, currentMoving)
                          .fold(error => break(Left(HalfFlowCcError.Registration(error))), identity)
                case _ =>
                  attempts += 1
                  trace += attemptDiagnostic(
                    attempts,
                    accepted = false,
                    metricSession,
                    Double.NaN,
                    step._2,
                    incremental,
                    numerical,
                    attemptTransitions.result()
                  )
    Right(
      (
        state,
        HalfFlowCcLevelDiagnostics(
          level.shrink,
          initialLoss,
          metricSession.loss,
          accepted,
          attempts,
          termination.get,
          trace.result()
        ),
        control,
        legacyGate
      )
    )

  private def rankOneStep[W](
      fixed: CcWarpBuffer,
      moving: CcWarpBuffer,
      objective: ObjectiveSession[W],
      frame: Frame[W],
      level: HalfFlowCcLevel,
      plan: HalfFlowCcPlan,
      control: HalfFlowCcControlState,
      workspace: CcStepWorkspace[W]
  ): Either[RegistrationError, (Velocity[W], Double)] =
    val n = frame.grid.nVoxels
    objective.gradientInto(fixed, moving, workspace).flatMap: _ =>
      PointwiseRankOne.solveInto(
        workspace.raw,
        workspace.movingGradientValid,
        n,
        objective.loss,
        control.damping,
        plan.rankOneEnergyEpsilon,
        control.stepScale,
        workspace.raw
      )
      var component = 0
      var index = 0
      while component < 3 do
        index = 0
        while index < n do
          workspace.component(index) = workspace.raw(component * n + index)
          index += 1
        Gaussian3D.normalizedInto(
          workspace.component,
          objective.smoothingWeight,
          frame.grid,
          level.smoothSigmaMm,
          plan.minimumSupportWeight,
          workspace.componentSmoothed,
          workspace.smoothedWeight,
          workspace.gaussian,
          GaussianBoundary.Zero,
          workspace.gaussianReduction
        )
        index = 0
        while index < n do
          workspace.velocity(component * n + index) = workspace.componentSmoothed(index)
          index += 1
        component += 1

      var maximum = maximumNorm(workspace.velocity, n)
      if maximum > level.maximumStepMm then
        val scale = level.maximumStepMm / maximum
        index = 0
        while index < workspace.velocity.length do
          workspace.velocity(index) *= scale
          index += 1
        maximum = level.maximumStepMm
      if plan.action == HalfFlowCcAction.FixedAnchor then
        index = 0
        while index < workspace.velocity.length do
          workspace.velocity(index) *= 2.0
          index += 1
      val nx = frame.grid.shape.x
      val ny = frame.grid.shape.y
      val field = DenseVectorField(
        frame.grid,
        RavelArray.tabulate[Double](
          nx,
          ny,
          frame.grid.shape.z,
          3
        ) { (x, y, z, component) =>
          workspace.velocity(
            x + nx * (y + ny * z) + component * n
          )
        },
        DenseVectorFieldKind.Displacement
      )
      Velocity.make(frame, field).map(_ -> maximum)

  private def warp[W, F, M](
      fixed: CcLevelImage[F],
      moving: CcLevelImage[M],
      state: ForwardMidpoint[W, F, M],
      fixedDestination: CcWarpBuffer,
      movingDestination: CcWarpBuffer
  ): Unit =
    HalfFlowKernels.pullScalarAffineInto(
      fixed.image.volume,
      state.fixed.residual.sourceCoordinates,
      state.fixed.affine.transform.matrix,
      fixedDestination.values,
      fixedDestination.valid,
      fixed.sampler,
      state.fixed.residual.validity,
      fixed.image.validity,
      0.0
    )
    HalfFlowKernels.pullScalarAffineInto(
      moving.image.volume,
      state.moving.residual.sourceCoordinates,
      state.moving.affine.transform.matrix,
      movingDestination.values,
      movingDestination.valid,
      moving.sampler,
      state.moving.residual.validity,
      moving.image.validity,
      0.0
    )

  private def buildSupport(
      fixed: CcWarpBuffer,
      moving: CcWarpBuffer,
      grid: GridSpec,
      maximumStepMm: Double,
      plan: HalfFlowCcPlan,
      source: Array[Double],
      destination: Array[Double],
      workspace: GaussianWorkspace
  ): Unit =
    val minimumSpacingMm = Affine.voxelSizes(grid.affine).min
    val safeMargin = math.max(1, math.ceil(0.5 * maximumStepMm / minimumSpacingMm).toInt)
    val nx = grid.shape.x
    val ny = grid.shape.y
    val nz = grid.shape.z
    var index = 0
    while index < grid.nVoxels do
      val x = index % nx
      val yz = index / nx
      val y = yz % ny
      val z = yz / ny
      var safe = true
      var dz = -safeMargin
      while dz <= safeMargin && safe do
        var dy = -safeMargin
        while dy <= safeMargin && safe do
          var dx = -safeMargin
          while dx <= safeMargin && safe do
            val sx = x + dx
            val sy = y + dy
            val sz = z + dz
            safe = sx >= 0 && sx < nx && sy >= 0 && sy < ny && sz >= 0 && sz < nz
            if safe then
              val sample = sx + nx * sy + nx * ny * sz
              safe = fixed.valid(sample) && moving.valid(sample)
            dx += 1
          dy += 1
        dz += 1
      source(index) = if safe then 1.0 else 0.0
      index += 1
    Gaussian3D.smoothInto(source, grid, plan.supportSigmaMm, destination, workspace, GaussianBoundary.Zero)
    index = 0
    while index < grid.nVoxels do
      if source(index) == 0.0 then destination(index) = 0.0
      else destination(index) = math.max(0.0, math.min(1.0, destination(index)))
      index += 1

  private def pyramid[A](
      source: RegistrationImage[A],
      level: HalfFlowCcLevel,
      workspace: Option[PyramidWorkspace]
  ): Either[RegistrationError, CcLevelImage[A]] =
    val grid = HalfFlowKernels.pyramidGrid(source.frame.grid, level.shrink)
    if grid == source.frame.grid && level.pyramidSigmaMm <= 0.0 then
      Right(CcLevelImage(source, DenseFieldSampler(grid)))
    else
      val values = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
      val valid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
      HalfFlowKernels.buildPyramidLevelInto(
        source.volume,
        grid,
        level.pyramidSigmaMm,
        workspace.get,
        values,
        valid,
        source.validity
      )
      val frame = Frame[A](source.frame.domain, grid)
      val volume = NeuroVol.fromLinear[Double](values, grid.toNeuroSpace, source.volume.label)
      RegistrationImage.make(frame, volume, FieldValidity.copyMask(valid)).map: image =>
        CcLevelImage(image, DenseFieldSampler(grid))

  private def validateInitial[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      state: ForwardMidpoint[W, F, M]
  ): Either[HalfFlowCcError, Unit] =
    if fixed.frame.domain != state.fixed.endpoint.domain then
      Left(HalfFlowCcError.Registration(RegistrationError.FrameMismatch(
        "HalfFlow-CC fixed endpoint",
        state.fixed.endpoint.domain,
        fixed.frame.domain
      )))
    else if moving.frame.domain != state.moving.endpoint.domain then
      Left(HalfFlowCcError.Registration(RegistrationError.FrameMismatch(
        "HalfFlow-CC moving endpoint",
        state.moving.endpoint.domain,
        moving.frame.domain
      )))
    else Right(())

  private def attemptDiagnostic(
      attempt: Int,
      accepted: Boolean,
      objective: ObjectiveSession[?],
      candidateLoss: Double,
      maximumStep: Double,
      geometry: GeometryVerdict,
      numerical: NumericalVerdict,
      transitions: Vector[ControlTransition],
      legacyInverseSafe: Option[Boolean] = None,
      legacyInverseMaximumMm: Option[Double] = None
  ): HalfFlowCcAttemptDiagnostics =
    HalfFlowCcAttemptDiagnostics(
      attempt,
      accepted,
      objective.loss,
      candidateLoss,
      maximumStep,
      objective.activeFraction,
      objective.edgeActiveFraction,
      geometry,
      numerical,
      legacyInverseSafe,
      legacyInverseMaximumMm,
      transitions
    )

  private def maximumInverseError(report: PairGuardReport): Double =
    Vector(
      report.inverse.forwardThenBackward.maximumMm,
      report.inverse.backwardThenForward.maximumMm
    ).flatten.maxOption.getOrElse(Double.PositiveInfinity)

  private def spatialGradients[W](
      fixed: CcWarpBuffer,
      moving: CcWarpBuffer,
      frame: Frame[W],
      workspace: CcStepWorkspace[W]
  ): Unit =
    val _ = MaskedLocalStats.physicalGradientChannelsInto(
      fixed.values,
      fixed.valid,
      frame.grid,
      1,
      workspace.fixedSpatialGradient,
      workspace.fixedGradientValid,
      workspace.spatial
    )
    val _ = MaskedLocalStats.physicalGradientChannelsInto(
      moving.values,
      moving.valid,
      frame.grid,
      1,
      workspace.movingSpatialGradient,
      workspace.movingGradientValid,
      workspace.spatial
    )

  private def isEdge(grid: GridSpec, index: Int): Boolean =
    val x = index % grid.shape.x
    val yz = index / grid.shape.x
    val y = yz % grid.shape.y
    val z = yz / grid.shape.y
    x == 0 || x == grid.shape.x - 1 || y == 0 || y == grid.shape.y - 1 ||
      z == 0 || z == grid.shape.z - 1

  private def edgeVoxelCount(grid: GridSpec): Int =
    val nx = grid.shape.x
    val ny = grid.shape.y
    val nz = grid.shape.z
    grid.nVoxels - math.max(0, nx - 2) * math.max(0, ny - 2) * math.max(0, nz - 2)

  private def maximumNorm(values: Array[Double], n: Int): Double =
    var maximum = 0.0
    var index = 0
    while index < n do
      val x = values(index)
      val y = values(index + n)
      val z = values(index + 2 * n)
      maximum = math.max(maximum, math.sqrt(x * x + y * y + z * z))
      index += 1
    maximum

  private def copy(source: CcWarpBuffer, destination: CcWarpBuffer): Unit =
    var index = 0
    while index < source.values.length do
      destination.values(index) = source.values(index)
      destination.valid(index) = source.valid(index)
      index += 1
