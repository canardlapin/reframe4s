package reframe4s.halfflow

import scala.util.boundary
import scala.util.boundary.break
import reframe4s.halfflow.internal.*

final case class RegistrationImage[A] private (
    frame: Frame[A],
    volume: NeuroVol[Double],
    validity: FieldValidity
)

object RegistrationImage:
  def make[A](
      frame: Frame[A],
      volume: NeuroVol[Double],
      validity: FieldValidity = FieldValidity.All
  ): Either[RegistrationError, RegistrationImage[A]] =
    val grid = GridSpec.fromSpace(volume.space)
    val validSize = validity match
      case FieldValidity.All => true
      case FieldValidity.Mask(values) => values.size == grid.nVoxels
    if frame.grid != grid then Left(RegistrationError.GridMismatch("registration image"))
    else if !validSize then Left(RegistrationError.InvalidField("registration image validity"))
    else Right(new RegistrationImage(frame, volume, validity))

final case class HalfFlowLevel private (
    shrink: Int,
    pyramidSigmaMm: Double,
    feature: T1FeatureConfig,
    localLm: LocalLmConfig,
    sobolev: SobolevConfig,
    flow: FlowConfig,
    guard: GuardConfig,
    trust: TrustConfig,
    minimumUsefulVelocityMm: Double
)

object HalfFlowLevel:
  def make(
      shrink: Int,
      pyramidSigmaMm: Double,
      feature: T1FeatureConfig = T1FeatureConfig.default,
      localLm: LocalLmConfig = LocalLmConfig.default,
      sobolev: SobolevConfig,
      flow: FlowConfig = FlowConfig(),
      guard: GuardConfig = GuardConfig(),
      trust: TrustConfig = TrustConfig.default,
      minimumUsefulVelocityMm: Double = 1e-4
  ): Either[RegistrationError, HalfFlowLevel] =
    if shrink < 1 then Left(RegistrationError.InvalidConfiguration("level shrink"))
    else if !pyramidSigmaMm.isFinite || pyramidSigmaMm < 0.0 then
      Left(RegistrationError.InvalidConfiguration("level pyramid sigma"))
    else if !minimumUsefulVelocityMm.isFinite || minimumUsefulVelocityMm < 0.0 then
      Left(RegistrationError.InvalidConfiguration("level minimum useful velocity"))
    else
      Right(
        new HalfFlowLevel(
          shrink,
          pyramidSigmaMm,
          feature,
          localLm,
          sobolev,
          flow,
          guard,
          trust,
          minimumUsefulVelocityMm
        )
      )

final case class HalfFlowPlan private (levels: Vector[HalfFlowLevel])

object HalfFlowPlan:
  def make(levels: Vector[HalfFlowLevel]): Either[RegistrationError, HalfFlowPlan] =
    val ordered = levels.indices.drop(1).forall(index => levels(index - 1).shrink > levels(index).shrink)
    if levels.isEmpty then Left(RegistrationError.InvalidConfiguration("registration levels are empty"))
    else if !ordered || levels.last.shrink != 1 then
      Left(RegistrationError.InvalidConfiguration("registration levels must descend strictly and end at shrink 1"))
    else Right(new HalfFlowPlan(levels))

enum LevelTermination:
  case AcceptedStepBudget
  case AttemptBudget
  case MaximumDamping
  case Stationary

final case class HalfFlowAttemptDiagnostics(
    attempt: Int,
    accepted: Boolean,
    currentValue: Double,
    candidateValue: Double,
    actualDrop: Double,
    predictedDrop: Double,
    gainRatio: Double,
    rejection: Option[TrustRejection],
    dampingBefore: Double,
    dampingAfter: Double,
    maximumVelocityMm: Double,
    maximumStrain: Double,
    localSafe: Boolean,
    accumulatedSafe: Boolean
)

final case class HalfFlowLevelDiagnostics(
    shrink: Int,
    grid: GridSpec,
    initialValue: Double,
    finalValue: Double,
    trust: TrustState,
    termination: LevelTermination,
    attempts: Vector[HalfFlowAttemptDiagnostics]
)

final case class RegistrationDiagnostics(levels: Vector[HalfFlowLevelDiagnostics]):
  val attemptedSteps: Int = levels.map(_.trust.attempts).sum
  val acceptedSteps: Int = levels.map(_.trust.acceptedSteps).sum

final case class RegistrationResult[F, M](
    transform: InversePair[F, M],
    diagnostics: RegistrationDiagnostics,
    guard: PairGuardReport
)

private final case class LevelImages[A](image: RegistrationImage[A], sampler: DenseFieldSampler)

private final class WarpBuffer private (
    val values: Array[Double],
    val valid: Array[Boolean]
)

private object WarpBuffer:
  def apply(size: Int): WarpBuffer =
    new WarpBuffer(PrimitiveBuffers.ofSize[Double](size), PrimitiveBuffers.ofSize[Boolean](size))

object HalfFlowLm:
  def register[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      initial: Midpoint[W, F, M],
      plan: HalfFlowPlan
  ): Either[RegistrationError, RegistrationResult[F, M]] =
    validateInitial(fixed, moving, initial).flatMap: _ =>
      val needsPyramid = plan.levels.exists(level => level.shrink != 1 || level.pyramidSigmaMm > 1e-12)
      val pyramidWorkspace =
        if needsPyramid then Some(PyramidWorkspace(math.max(fixed.frame.grid.nVoxels, moving.frame.grid.nVoxels)))
        else None
      var state = initial
      val diagnostics = Vector.newBuilder[HalfFlowLevelDiagnostics]
      var levelIndex = 0
      var failure: Option[RegistrationError] = None
      while levelIndex < plan.levels.length && failure.isEmpty do
        val level = plan.levels(levelIndex)
        val workFrame = Frame[W](
          initial.work.domain,
          HalfFlowKernels.pyramidGrid(initial.work.grid, level.shrink)
        )
        val fixedLevel = pyramid(fixed, level, pyramidWorkspace)
        val movingLevel = pyramid(moving, level, pyramidWorkspace)
        (fixedLevel, movingLevel) match
          case (Right(nextFixed), Right(nextMoving)) =>
            val regriddedState =
              if
                state.work == workFrame && state.fixed.endpoint == nextFixed.image.frame &&
                  state.moving.endpoint == nextMoving.image.frame
              then Right(state)
              else state.regrid(workFrame, nextFixed.image.frame, nextMoving.image.frame)
            regriddedState match
              case Left(error) => failure = Some(error)
              case Right(regridded) =>
                val regridGuardScratch = TopologyGuardScratch.forGrids(
                  Vector(regridded.work.grid)
                )
                val fixedGuard = TopologyGuard
                  .evaluateWith(
                    regridded.fixed.residual,
                    TopologyGuardWorkspace.using(regridded.fixed.residual, regridGuardScratch),
                    regridded.fixed.residualGuardConfig(level.guard)
                  )
                  .fold(error => throw new IllegalArgumentException(error.message), identity)
                val movingGuard = TopologyGuard
                  .evaluateWith(
                    regridded.moving.residual,
                    TopologyGuardWorkspace.using(regridded.moving.residual, regridGuardScratch),
                    regridded.moving.residualGuardConfig(level.guard)
                  )
                  .fold(error => throw new IllegalArgumentException(error.message), identity)
                val relative = regridded.relativeResidual.fold(
                  error => throw new IllegalArgumentException(error.message),
                  identity
                )
                val relativeGuard = TopologyGuard
                  .evaluateWith(
                    relative,
                    TopologyGuardWorkspace.using(relative, regridGuardScratch),
                    regridded.relativeResidualGuardConfig(level.guard)
                  )
                  .fold(error => throw new IllegalArgumentException(error.message), identity)
                if !fixedGuard.safe || !movingGuard.safe || !relativeGuard.safe then
                  val reasons =
                    fixedGuard.reasons.map(reason => s"fixed: $reason") ++
                      movingGuard.reasons.map(reason => s"moving: $reason") ++
                      relativeGuard.reasons.map(reason => s"result: $reason")
                  failure = Some(
                    RegistrationError.UnsafeTransform(
                      s"regridded midpoint (${reasons.mkString("; ")})"
                    )
                  )
                else
                  refineLevel(nextFixed, nextMoving, regridded, level) match
                    case Left(error) => failure = Some(error)
                    case Right((nextState, levelDiagnostics)) =>
                      state = nextState
                      diagnostics += levelDiagnostics
          case (Left(error), _) => failure = Some(error)
          case (_, Left(error)) => failure = Some(error)
        levelIndex += 1
      failure match
        case Some(error) => Left(error)
        case None =>
          state.result.flatMap: pair =>
            val guard = TopologyGuard.evaluate(pair, plan.levels.last.guard)
            if guard.safe then
              Right(RegistrationResult(pair, RegistrationDiagnostics(diagnostics.result()), guard))
            else
              Left(
                RegistrationError.UnsafeTransform(
                  s"final result (${guard.reasons.mkString("; ")})"
                )
              )

  private def refineLevel[W, F, M](
      fixed: LevelImages[F],
      moving: LevelImages[M],
      start: Midpoint[W, F, M],
      level: HalfFlowLevel
  ): Either[RegistrationError, (Midpoint[W, F, M], HalfFlowLevelDiagnostics)] =
    val work = start.work
    val n = work.grid.nVoxels
    val fixedCurrentWarp = WarpBuffer(n)
    val movingCurrentWarp = WarpBuffer(n)
    val fixedCandidateWarp = WarpBuffer(n)
    val movingCandidateWarp = WarpBuffer(n)
    val fixedCurrentFeatureBuffer = T1FeatureBuffer(work, level.feature)
    val movingCurrentFeatureBuffer = T1FeatureBuffer(work, level.feature)
    val fixedCandidateFeatureBuffer = T1FeatureBuffer(work, level.feature)
    val movingCandidateFeatureBuffer = T1FeatureBuffer(work, level.feature)
    val featureWorkspace = T1FeatureWorkspace(work, level.feature.radii.length)
    val localWorkspace = LocalLmWorkspace(work)
    val localBuffer = LocalLmBuffer(work)
    val sobolevWorkspace = SobolevWorkspace(work)
    val sobolevBuffer = SobolevBuffer.reusing(localBuffer)
    val flowWorkspace = PairedFlowWorkspace(work)
    val identityPair = InversePair.identity(work)
    val guardScratch = TopologyGuardScratch.forGrids(Vector(work.grid))
    val localGuardWorkspace = TopologyGuardWorkspace.using(identityPair, guardScratch)
    val relativeGuardWorkspace = TopologyGuardWorkspace.using(identityPair, guardScratch)
    val relativeGuardConfig = start.relativeResidualGuardConfig(level.guard)
    val relativeBuffer = SelfPairComposeBuffer(work)

    var state = start
    var currentFixedWarp = fixedCurrentWarp
    var currentMovingWarp = movingCurrentWarp
    var candidateFixedWarp = fixedCandidateWarp
    var candidateMovingWarp = movingCandidateWarp
    var currentFixedBuffer = fixedCurrentFeatureBuffer
    var currentMovingBuffer = movingCurrentFeatureBuffer
    var candidateFixedBuffer = fixedCandidateFeatureBuffer
    var candidateMovingBuffer = movingCandidateFeatureBuffer
    var currentFixed = Option.empty[T1FeatureVolume[W]]
    var currentMoving = Option.empty[T1FeatureVolume[W]]
    var currentAdvanceBuffer = Option.empty[MidpointAdvanceBuffer[W, F, M]]
    var candidateAdvanceBuffer: Option[MidpointAdvanceBuffer[W, F, M]] = Some(MidpointAdvanceBuffer(start))

    featuresFor(
      fixed,
      moving,
      state,
      level.feature,
      currentFixedWarp,
      currentMovingWarp,
      featureWorkspace,
      currentFixedBuffer,
      currentMovingBuffer
    ) match
      case Left(error) => Left(error)
      case Right((firstFixed, firstMoving)) => boundary:
        currentFixed = Some(firstFixed)
        currentMoving = Some(firstMoving)
        var trust = TrustState.initial(level.trust)
        var initialValue = Double.NaN
        var currentValue = Double.NaN
        var termination = Option.empty[LevelTermination]
        val attempts = Vector.newBuilder[HalfFlowAttemptDiagnostics]
        while termination.isEmpty do
          trust.terminal(level.trust) match
            case Some(TrustTermination.AcceptedStepBudget) => termination = Some(LevelTermination.AcceptedStepBudget)
            case Some(TrustTermination.AttemptBudget) => termination = Some(LevelTermination.AttemptBudget)
            case Some(TrustTermination.MaximumDamping) => termination = Some(LevelTermination.MaximumDamping)
            case None =>
              val damped = level.localLm.withDamping(trust.damping).fold(error => break(Left(error)), identity)
              val local = LocalLm
                .solveInto(currentFixed.get, currentMoving.get, damped, localWorkspace, localBuffer)
                .fold(error => break(Left(error)), identity)
              currentValue = local.model.value
              if !initialValue.isFinite then initialValue = currentValue
              if local.summary.maximumRawVelocityMm <= level.minimumUsefulVelocityMm then
                termination = Some(LevelTermination.Stationary)
              else
                val shaped = SobolevShaper
                  .shapeInto(local.rawVelocity, local.rawValidity, level.sobolev, sobolevWorkspace, sobolevBuffer)
                  .fold(error => break(Left(error)), identity)
                if shaped.diagnostics.maximumNormAfterCapMm <= level.minimumUsefulVelocityMm then
                  termination = Some(LevelTermination.Stationary)
                else
                  val predicted = LocalLm
                    .predictedDrop(currentFixed.get, currentMoving.get, shaped.velocity, shaped.validity, local.model)
                    .fold(error => break(Left(error)), identity)
                  val flow = PairedScalingAndSquaring
                    .expHalfPairWith(shaped.velocity, flowWorkspace, level.flow)
                    .fold(error => break(Left(error)), identity)
                  val localGuard = TopologyGuard
                    .evaluateWith(flow.pair, localGuardWorkspace, level.guard)
                    .fold(error => break(Left(error)), identity)
                  if !localGuard.safe then
                    val outcome = TrustModel.evaluate(
                      trust,
                      TrustAttempt(state, state, currentValue, currentValue, predicted, localSafe = false, accumulatedSafe = true),
                      level.trust
                    )
                    attempts += attemptDiagnostics(
                      outcome,
                      currentValue,
                      currentValue,
                      shaped,
                      localSafe = false,
                      accumulatedSafe = true
                    )
                    trust = outcome.decision.after
                  else
                    val advanceBuffer = candidateAdvanceBuffer.getOrElse:
                      val fresh = MidpointAdvanceBuffer(state)
                      candidateAdvanceBuffer = Some(fresh)
                      fresh
                    val candidate = state
                      .advanceInto(flow.pair, advanceBuffer)
                      .fold(error => break(Left(error)), identity)
                    val relative = candidate
                      .relativeResidualInto(relativeBuffer)
                      .fold(error => break(Left(error)), identity)
                    val relativeGuard = TopologyGuard
                      .evaluateWith(relative, relativeGuardWorkspace, relativeGuardConfig)
                      .fold(error => break(Left(error)), identity)
                    val accumulatedSafe = relativeGuard.safe
                    if !accumulatedSafe then
                      val outcome = TrustModel.evaluate(
                        trust,
                        TrustAttempt(state, candidate, currentValue, currentValue, predicted, localSafe = true, accumulatedSafe = false),
                        level.trust
                      )
                      attempts += attemptDiagnostics(
                        outcome,
                        currentValue,
                        currentValue,
                        shaped,
                        localSafe = true,
                        accumulatedSafe = false
                      )
                      trust = outcome.decision.after
                    else
                      val candidateFeatures = featuresFor(
                        fixed,
                        moving,
                        candidate,
                        level.feature,
                        candidateFixedWarp,
                        candidateMovingWarp,
                        featureWorkspace,
                        candidateFixedBuffer,
                        candidateMovingBuffer
                      )
                      val candidateValue = candidateFeatures.flatMap: pair =>
                        LocalLm.candidateValue(currentFixed.get, currentMoving.get, pair._1, pair._2, local.model)
                      val finiteCandidate = candidateValue.getOrElse(Double.NaN)
                      val outcome = TrustModel.evaluate(
                        trust,
                        TrustAttempt(state, candidate, currentValue, finiteCandidate, predicted, localSafe = true, accumulatedSafe = true),
                        level.trust
                      )
                      attempts += attemptDiagnostics(
                        outcome,
                        currentValue,
                        finiteCandidate,
                        shaped,
                        localSafe = true,
                        accumulatedSafe = true
                      )
                      trust = outcome.decision.after
                      if outcome.decision.accepted then
                        val acceptedFeatures = candidateFeatures.fold(error => break(Left(error)), identity)
                        state = candidate
                        candidateAdvanceBuffer = currentAdvanceBuffer
                        currentAdvanceBuffer = Some(advanceBuffer)
                        currentValue = finiteCandidate
                        val oldFixedWarp = currentFixedWarp
                        val oldMovingWarp = currentMovingWarp
                        currentFixedWarp = candidateFixedWarp
                        currentMovingWarp = candidateMovingWarp
                        candidateFixedWarp = oldFixedWarp
                        candidateMovingWarp = oldMovingWarp
                        val oldFixedBuffer = currentFixedBuffer
                        val oldMovingBuffer = currentMovingBuffer
                        currentFixedBuffer = candidateFixedBuffer
                        currentMovingBuffer = candidateMovingBuffer
                        candidateFixedBuffer = oldFixedBuffer
                        candidateMovingBuffer = oldMovingBuffer
                        currentFixed = Some(acceptedFeatures._1)
                        currentMoving = Some(acceptedFeatures._2)
        val finalTermination = termination.get
        val finalValue = if currentValue.isFinite then currentValue else initialValue
        Right(
          (
            state,
            HalfFlowLevelDiagnostics(
              level.shrink,
              work.grid,
              initialValue,
              finalValue,
              trust,
              finalTermination,
              attempts.result()
            )
          )
        )

  private def featuresFor[W, F, M](
      fixed: LevelImages[F],
      moving: LevelImages[M],
      state: Midpoint[W, F, M],
      config: T1FeatureConfig,
      fixedWarp: WarpBuffer,
      movingWarp: WarpBuffer,
      workspace: T1FeatureWorkspace[W],
      fixedBuffer: T1FeatureBuffer[W],
      movingBuffer: T1FeatureBuffer[W]
  ): Either[RegistrationError, (T1FeatureVolume[W], T1FeatureVolume[W])] =
    HalfFlowKernels.pullScalarAffineInto(
      fixed.image.volume,
      state.fixed.residual.forward.sourceCoordinates,
      state.fixed.affine.transform.matrix,
      fixedWarp.values,
      fixedWarp.valid,
      fixed.sampler,
      state.fixed.residual.forward.validity,
      fixed.image.validity,
      0.0
    )
    HalfFlowKernels.pullScalarAffineInto(
      moving.image.volume,
      state.moving.residual.forward.sourceCoordinates,
      state.moving.affine.transform.matrix,
      movingWarp.values,
      movingWarp.valid,
      moving.sampler,
      state.moving.residual.forward.validity,
      moving.image.validity,
      0.0
    )
    for
      fixedFeatures <- T1Features.computeInto(
        state.work,
        fixedWarp.values,
        FieldValidity.copyMask(fixedWarp.valid),
        config,
        workspace,
        fixedBuffer
      )
      movingFeatures <- T1Features.computeInto(
        state.work,
        movingWarp.values,
        FieldValidity.copyMask(movingWarp.valid),
        config,
        workspace,
        movingBuffer
      )
    yield (fixedFeatures, movingFeatures)

  private def pyramid[A](
      source: RegistrationImage[A],
      level: HalfFlowLevel,
      workspace: Option[PyramidWorkspace]
  ): Either[RegistrationError, LevelImages[A]] =
    val targetGrid = HalfFlowKernels.pyramidGrid(source.frame.grid, level.shrink)
    if targetGrid == source.frame.grid && level.pyramidSigmaMm <= 1e-12 then
      Right(LevelImages(source, DenseFieldSampler(source.frame.grid)))
    else
      val scratch = workspace.getOrElse:
        throw new IllegalStateException("pyramid workspace missing for resampled level")
      val values = PrimitiveBuffers.ofSize[Double](targetGrid.nVoxels)
      val valid = PrimitiveBuffers.ofSize[Boolean](targetGrid.nVoxels)
      HalfFlowKernels.buildPyramidLevelInto(
        source.volume,
        targetGrid,
        level.pyramidSigmaMm,
        scratch,
        values,
        valid,
        source.validity
      )
      val frame = Frame[A](source.frame.domain, targetGrid)
      val volume = NeuroVol.fromLinear[Double](values, targetGrid.toNeuroSpace, source.volume.label)
      RegistrationImage.make(frame, volume, FieldValidity.copyMask(valid)).map: image =>
        LevelImages(image, DenseFieldSampler(targetGrid))

  private def attemptDiagnostics[S, A](
      outcome: TrustOutcome[S],
      currentValue: Double,
      candidateValue: Double,
      shaped: SobolevResult[A],
      localSafe: Boolean,
      accumulatedSafe: Boolean
  ): HalfFlowAttemptDiagnostics =
    val decision = outcome.decision
    HalfFlowAttemptDiagnostics(
      decision.after.attempts,
      decision.accepted,
      currentValue,
      candidateValue,
      decision.actualDrop,
      decision.predictedDrop,
      decision.gainRatio,
      decision.rejection,
      decision.before.damping,
      decision.after.damping,
      shaped.diagnostics.maximumNormAfterCapMm,
      shaped.diagnostics.maximumStrainAfterCap,
      localSafe,
      accumulatedSafe
    )

  private def validateInitial[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      initial: Midpoint[W, F, M]
  ): Either[RegistrationError, Unit] =
    if initial.fixed.endpoint.domain != fixed.frame.domain then
      Left(RegistrationError.FrameMismatch("initial fixed", fixed.frame.domain, initial.fixed.endpoint.domain))
    else if initial.moving.endpoint.domain != moving.frame.domain then
      Left(RegistrationError.FrameMismatch("initial moving", moving.frame.domain, initial.moving.endpoint.domain))
    else if initial.fixed.endpoint.grid != fixed.frame.grid then Left(RegistrationError.GridMismatch("initial fixed"))
    else if initial.moving.endpoint.grid != moving.frame.grid then Left(RegistrationError.GridMismatch("initial moving"))
    else Right(())
