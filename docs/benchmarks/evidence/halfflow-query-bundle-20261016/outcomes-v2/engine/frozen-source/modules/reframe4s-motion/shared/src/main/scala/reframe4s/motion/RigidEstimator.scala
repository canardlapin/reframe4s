package reframe4s.motion

import image4s.BoundaryPolicy
import ravel.AnyRank
import ravel.NDArray
import ravel.Rank
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Grid
import reframe4s.lie.Rigid3
import reframe4s.lie.Twist6
import reframe4s.register.OptimizationReport
import reframe4s.register.Termination
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingPlan
import reframe4s.resample.ResamplingSink
import reframe4s.resample.ResamplingWorkspace

enum EstimatorInput derives CanEqual:
  case Moving, Fixed

enum EstimatorScalar derives CanEqual:
  case HuberThreshold
  case InitialTranslationStep
  case InitialRotationStep
  case MinimumTranslationStep
  case MinimumRotationStep
  case ObjectiveTolerance
  case GradientTolerance
  case MinimumOverlap
  case InitialDamping
  case MinimumDamping
  case MaximumDamping
  case AcceptedDampingFactor
  case RejectedDampingFactor
  case ConditionLimit
  case CaptureRotation

enum EstimatorCount derives CanEqual:
  case MaximumIterations, MaximumRejectedSteps

enum EstimatorStep derives CanEqual:
  case Translation, Rotation

enum RigidOptimizationStrategy derives CanEqual:
  case LevenbergMarquardt
  case ReferenceCoordinateSearch

enum RigidExecutionPolicy derives CanEqual:
  /**
   * One deterministic reduction lane.
   *
   * Parallel frame scheduling belongs outside a compiled pair until a
   * deterministic multi-lane kernel is implemented and proven.
   */
  case DeterministicSerial

sealed trait RigidSamplingPolicy

object RigidSamplingPolicy:
  case object Dense extends RigidSamplingPolicy

  final class InformationAware private[motion] (
      val control: RigidStencilControl
  ) extends RigidSamplingPolicy

  def informationAware(
      control: RigidStencilControl
  ): RigidSamplingPolicy =
    new InformationAware(control)

sealed trait RigidCapturePolicy derives CanEqual:
  private[motion] def seeds: Vector[RigidCaptureSeed]

object RigidCapturePolicy:
  case object Disabled extends RigidCapturePolicy:
    private[motion] val seeds: Vector[RigidCaptureSeed] = Vector.empty

  final class AxialRotation private[motion] (
      val angleRadians: Double
  ) extends RigidCapturePolicy:
    private[motion] val seeds: Vector[RigidCaptureSeed] =
      Vector(
        RigidCaptureSeed(-angleRadians, 0.0, 0.0),
        RigidCaptureSeed(angleRadians, 0.0, 0.0),
        RigidCaptureSeed(0.0, -angleRadians, 0.0),
        RigidCaptureSeed(0.0, angleRadians, 0.0),
        RigidCaptureSeed(0.0, 0.0, -angleRadians),
        RigidCaptureSeed(0.0, 0.0, angleRadians)
      )

  val default: RigidCapturePolicy =
    new AxialRotation(math.toRadians(5.0))

  def axialRotation(
      angleRadians: Double
  ): Either[MotionError, RigidCapturePolicy] =
    if !angleRadians.isFinite || angleRadians <= 0.0 then
      Left(
        MotionError.InvalidEstimatorScalar(
          EstimatorScalar.CaptureRotation,
          angleRadians
        )
      )
    else Right(new AxialRotation(angleRadians))

private[motion] final case class RigidCaptureSeed(
    rx: Double,
    ry: Double,
    rz: Double
)

final class RigidDampingPolicy private (
    val initial: Double,
    val minimum: Double,
    val maximum: Double,
    val acceptedFactor: Double,
    val rejectedFactor: Double,
    val conditionLimit: Double,
    val maximumRejectedSteps: Int
)

object RigidDampingPolicy:
  val default: RigidDampingPolicy =
    new RigidDampingPolicy(
      initial = 1e-3,
      minimum = 1e-12,
      maximum = 1e12,
      acceptedFactor = 0.25,
      rejectedFactor = 10.0,
      conditionLimit = 1e12,
      maximumRejectedSteps = 12
    )

  def create(
      initial: Double,
      minimum: Double,
      maximum: Double,
      acceptedFactor: Double,
      rejectedFactor: Double,
      conditionLimit: Double,
      maximumRejectedSteps: Int
  ): Either[MotionError, RigidDampingPolicy] =
    if maximumRejectedSteps <= 0 then
      Left(
        MotionError.InvalidEstimatorCount(
          EstimatorCount.MaximumRejectedSteps,
          maximumRejectedSteps
        )
      )
    else
      RigidOptimizerControl.firstInvalidScalar(
        Vector(
          EstimatorScalar.InitialDamping ->
            (initial, initial > 0.0),
          EstimatorScalar.MinimumDamping ->
            (minimum, minimum > 0.0),
          EstimatorScalar.MaximumDamping ->
            (maximum, maximum > 0.0),
          EstimatorScalar.AcceptedDampingFactor ->
            (
              acceptedFactor,
              acceptedFactor > 0.0 && acceptedFactor < 1.0
            ),
          EstimatorScalar.RejectedDampingFactor ->
            (rejectedFactor, rejectedFactor > 1.0),
          EstimatorScalar.ConditionLimit ->
            (conditionLimit, conditionLimit > 1.0)
        )
      ) match
        case Some(error) => Left(error)
        case None if minimum > initial || initial > maximum =>
          Left(
            MotionError.InvalidDampingOrder(
              minimum,
              initial,
              maximum
            )
          )
        case None =>
          Right(
            new RigidDampingPolicy(
              initial,
              minimum,
              maximum,
              acceptedFactor,
              rejectedFactor,
              conditionLimit,
              maximumRejectedSteps
            )
          )

final class RigidConvergencePolicy private (
    val maximumIterations: Int,
    val initialTranslationStepMm: Double,
    val initialRotationStepRadians: Double,
    val minimumTranslationStepMm: Double,
    val minimumRotationStepRadians: Double,
    val objectiveTolerance: Double,
    val gradientTolerance: Double,
    val minimumOverlap: Double
):
  def withGradientTolerance(
      value: Double
  ): Either[MotionError, RigidConvergencePolicy] =
    RigidConvergencePolicy.create(
      maximumIterations,
      initialTranslationStepMm,
      initialRotationStepRadians,
      minimumTranslationStepMm,
      minimumRotationStepRadians,
      objectiveTolerance,
      value,
      minimumOverlap
    )

object RigidConvergencePolicy:
  val DefaultGradientTolerance: Double = 1e-8

  val default: RigidConvergencePolicy =
    new RigidConvergencePolicy(
      maximumIterations = 40,
      initialTranslationStepMm = 2.0,
      initialRotationStepRadians = math.toRadians(2.0),
      minimumTranslationStepMm = 0.01,
      minimumRotationStepRadians = math.toRadians(0.01),
      objectiveTolerance = 1e-8,
      gradientTolerance = DefaultGradientTolerance,
      minimumOverlap = 0.5
    )

  def create(
      maximumIterations: Int,
      initialTranslationStepMm: Double,
      initialRotationStepRadians: Double,
      minimumTranslationStepMm: Double,
      minimumRotationStepRadians: Double,
      objectiveTolerance: Double,
      gradientTolerance: Double,
      minimumOverlap: Double
  ): Either[MotionError, RigidConvergencePolicy] =
    if maximumIterations <= 0 then
      Left(
        MotionError.InvalidEstimatorCount(
          EstimatorCount.MaximumIterations,
          maximumIterations
        )
      )
    else
      RigidOptimizerControl.firstInvalidScalar(
        Vector(
          EstimatorScalar.InitialTranslationStep ->
            (initialTranslationStepMm, initialTranslationStepMm > 0.0),
          EstimatorScalar.InitialRotationStep ->
            (initialRotationStepRadians, initialRotationStepRadians > 0.0),
          EstimatorScalar.MinimumTranslationStep ->
            (minimumTranslationStepMm, minimumTranslationStepMm > 0.0),
          EstimatorScalar.MinimumRotationStep ->
            (minimumRotationStepRadians, minimumRotationStepRadians > 0.0),
          EstimatorScalar.ObjectiveTolerance ->
            (objectiveTolerance, objectiveTolerance >= 0.0),
          EstimatorScalar.GradientTolerance ->
            (gradientTolerance, gradientTolerance >= 0.0),
          EstimatorScalar.MinimumOverlap ->
            (
              minimumOverlap,
              minimumOverlap > 0.0 && minimumOverlap <= 1.0
            )
        )
      ) match
        case Some(error) => Left(error)
        case None if minimumTranslationStepMm > initialTranslationStepMm =>
          Left(
            MotionError.InvalidEstimatorStepOrder(
              EstimatorStep.Translation,
              minimumTranslationStepMm,
              initialTranslationStepMm
            )
          )
        case None if minimumRotationStepRadians > initialRotationStepRadians =>
          Left(
            MotionError.InvalidEstimatorStepOrder(
              EstimatorStep.Rotation,
              minimumRotationStepRadians,
              initialRotationStepRadians
            )
          )
        case None =>
          Right(
            new RigidConvergencePolicy(
              maximumIterations,
              initialTranslationStepMm,
              initialRotationStepRadians,
              minimumTranslationStepMm,
              minimumRotationStepRadians,
              objectiveTolerance,
              gradientTolerance,
              minimumOverlap
            )
          )

final class RigidOptimizerControl private (
    val convergence: RigidConvergencePolicy,
    val strategy: RigidOptimizationStrategy,
    val damping: RigidDampingPolicy,
    val capture: RigidCapturePolicy,
    val sampling: RigidSamplingPolicy,
    val robustLoss: RigidRobustLoss,
    val execution: RigidExecutionPolicy
):
  def maximumIterations: Int = convergence.maximumIterations
  def initialTranslationStepMm: Double =
    convergence.initialTranslationStepMm
  def initialRotationStepRadians: Double =
    convergence.initialRotationStepRadians
  def minimumTranslationStepMm: Double =
    convergence.minimumTranslationStepMm
  def minimumRotationStepRadians: Double =
    convergence.minimumRotationStepRadians
  def objectiveTolerance: Double = convergence.objectiveTolerance
  def gradientTolerance: Double = convergence.gradientTolerance
  def minimumOverlap: Double = convergence.minimumOverlap

  def withStrategy(
      value: RigidOptimizationStrategy
  ): RigidOptimizerControl =
    new RigidOptimizerControl(
      convergence,
      value,
      damping,
      capture,
      sampling,
      robustLoss,
      execution
    )

  def withDamping(value: RigidDampingPolicy): RigidOptimizerControl =
    new RigidOptimizerControl(
      convergence,
      strategy,
      value,
      capture,
      sampling,
      robustLoss,
      execution
    )

  def withCapture(value: RigidCapturePolicy): RigidOptimizerControl =
    new RigidOptimizerControl(
      convergence,
      strategy,
      damping,
      value,
      sampling,
      robustLoss,
      execution
    )

  def withConvergence(
      value: RigidConvergencePolicy
  ): RigidOptimizerControl =
    new RigidOptimizerControl(
      value,
      strategy,
      damping,
      capture,
      sampling,
      robustLoss,
      execution
    )

  def withSampling(value: RigidSamplingPolicy): RigidOptimizerControl =
    new RigidOptimizerControl(
      convergence,
      strategy,
      damping,
      capture,
      value,
      robustLoss,
      execution
    )

  def withRobustLoss(value: RigidRobustLoss): RigidOptimizerControl =
    new RigidOptimizerControl(
      convergence,
      strategy,
      damping,
      capture,
      sampling,
      value,
      execution
    )

  def withExecution(
      value: RigidExecutionPolicy
  ): RigidOptimizerControl =
    new RigidOptimizerControl(
      convergence,
      strategy,
      damping,
      capture,
      sampling,
      robustLoss,
      value
    )

  def withGradientTolerance(
      value: Double
  ): Either[MotionError, RigidOptimizerControl] =
    convergence
      .withGradientTolerance(value)
      .map(withConvergence)

object RigidOptimizerControl:
  val DefaultGradientTolerance: Double =
    RigidConvergencePolicy.DefaultGradientTolerance

  val default: RigidOptimizerControl =
    new RigidOptimizerControl(
      convergence = RigidConvergencePolicy.default,
      strategy = RigidOptimizationStrategy.LevenbergMarquardt,
      damping = RigidDampingPolicy.default,
      capture = RigidCapturePolicy.default,
      sampling = RigidSamplingPolicy.Dense,
      robustLoss = RigidRobustLoss.default,
      execution = RigidExecutionPolicy.DeterministicSerial
    )

  def create(
      maximumIterations: Int,
      huberThreshold: Double,
      initialTranslationStepMm: Double,
      initialRotationStepRadians: Double,
      minimumTranslationStepMm: Double,
      minimumRotationStepRadians: Double,
      objectiveTolerance: Double,
      minimumOverlap: Double
  ): Either[MotionError, RigidOptimizerControl] =
    for
      convergence <- RigidConvergencePolicy.create(
        maximumIterations,
        initialTranslationStepMm,
        initialRotationStepRadians,
        minimumTranslationStepMm,
        minimumRotationStepRadians,
        objectiveTolerance,
        DefaultGradientTolerance,
        minimumOverlap
      )
      robustLoss <- RigidRobustLoss
        .huber(huberThreshold)
        .left
        .map {
          case RigidKernelError.InvalidHuberThreshold(value) =>
            MotionError.InvalidEstimatorScalar(
              EstimatorScalar.HuberThreshold,
              value
            )
          case error => MotionError.RigidKernel(error)
        }
    yield
      new RigidOptimizerControl(
        convergence,
        RigidOptimizationStrategy.LevenbergMarquardt,
        RigidDampingPolicy.default,
        RigidCapturePolicy.default,
        RigidSamplingPolicy.Dense,
        robustLoss,
        RigidExecutionPolicy.DeterministicSerial
      )

  private[motion] def firstInvalidScalar(
      values: Vector[(EstimatorScalar, (Double, Boolean))]
  ): Option[MotionError.InvalidEstimatorScalar] =
    values.collectFirst {
      case (parameter, (value, valid)) if !value.isFinite || !valid =>
        MotionError.InvalidEstimatorScalar(parameter, value)
    }

final class RigidEstimatorWorkspace private (
    private[motion] val resampling: ResamplingWorkspace[D3],
    private[motion] val normalScan: RigidNormalScanWorkspace
):
  private var active = false

  private[motion] def acquire(): Boolean =
    if active then false
    else
      active = true
      true

  private[motion] def release(): Unit =
    active = false

object RigidEstimatorWorkspace:
  def create(using Dimension[D3]): RigidEstimatorWorkspace =
    new RigidEstimatorWorkspace(
      ResamplingWorkspace.create[D3],
      RigidNormalScanWorkspace.create()
    )

final case class RigidOptimizerDiagnostics(
    strategy: RigidOptimizationStrategy,
    captureCandidates: Int,
    selectedCaptureIndex: Int,
    rejectedSteps: Int,
    acceptedObjectives: Vector[Double],
    finalGradientNorm: Option[Double],
    finalTranslationStepMm: Option[Double],
    finalRotationStepRadians: Option[Double],
    finalDamping: Option[Double]
) derives CanEqual

final class RigidPairEstimate[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val pose: RigidPose[Moving, Fixed],
    val report: OptimizationReport,
    val overlap: Double,
    val supportedVoxels: Long,
    val diagnostics: RigidOptimizerDiagnostics
)

private object RigidPairEstimate:
  def measured[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      pose: RigidPose[Moving, Fixed],
      report: OptimizationReport,
      overlap: Double,
      supportedVoxels: Long,
      diagnostics: RigidOptimizerDiagnostics
  ): RigidPairEstimate[Moving, Fixed] =
    new RigidPairEstimate(
      pose,
      report,
      overlap,
      supportedVoxels,
      diagnostics
    )

/**
 * Immutable, deterministic pairwise rigid estimator.
 *
 * Every candidate is evaluated by the production affine pull-resampler on the
 * complete moving and fixed grid affines. The objective has a fixed target
 * domain: outside samples receive the declared constant boundary value rather
 * than disappearing from the loss.
 */
final class CompiledRigidPairEstimator[
    Moving <: Frame[D3],
    Fixed <: Frame[D3],
    RMoving <: AnyRank,
    RFixed <: AnyRank
] private (
    val moving: MotionScalarImage[Moving, D3, RMoving],
    val fixed: MotionScalarImage[Fixed, D3, RFixed],
    val control: RigidOptimizerControl,
    private val movingData: NDArray[Double, Rank[3]],
    private val fixedData: NDArray[Double, Rank[3]],
    private val stencil: CompiledRigidStencil[Fixed],
    private val robustLoss: RigidRobustLoss
)(using Dimension[D3]):
  val selectedSampleCount: Int = stencil.size
  val coveredSpatialBins: Int = stencil.coveredBinCount
  val declaredSpatialBins: Int = stencil.declaredBinCount

  def newWorkspace(): RigidEstimatorWorkspace =
    RigidEstimatorWorkspace.create

  def run(
      workspace: RigidEstimatorWorkspace
  ): Either[MotionError, RigidPairEstimate[Moving, Fixed]] =
    if !workspace.acquire() then Left(MotionError.EstimatorWorkspaceInUse)
    else
      try
        initialPose.flatMap(initial =>
          selectCapture(initial, workspace).flatMap(selection =>
            execute(
              selection.pose,
              workspace,
              selection.candidateCount,
              selection.selectedIndex
            )
          )
        )
      finally workspace.release()

  def runFrom(
      initial: RigidPose[Moving, Fixed],
      workspace: RigidEstimatorWorkspace
  ): Either[MotionError, RigidPairEstimate[Moving, Fixed]] =
    if !workspace.acquire() then Left(MotionError.EstimatorWorkspaceInUse)
    else
      try execute(initial, workspace, 1, 0)
      finally workspace.release()

  /**
   * Evaluate a declared pose without changing it.
   *
   * This is used for exact reference anchoring and diagnostics at externally
   * fixed poses.
   */
  def measureAt(
      pose: RigidPose[Moving, Fixed],
      workspace: RigidEstimatorWorkspace
  ): Either[MotionError, RigidPairEstimate[Moving, Fixed]] =
    if !workspace.acquire() then Left(MotionError.EstimatorWorkspaceInUse)
    else
      try measure(pose, workspace)
      finally workspace.release()

  private def measure(
      pose: RigidPose[Moving, Fixed],
      workspace: RigidEstimatorWorkspace
  ): Either[MotionError, RigidPairEstimate[Moving, Fixed]] =
    for
      objective <-
        control.strategy match
          case RigidOptimizationStrategy.LevenbergMarquardt =>
            evaluateSystem(pose, workspace)
          case RigidOptimizationStrategy.ReferenceCoordinateSearch =>
            evaluate(pose, workspace)
      _ <- requireOverlap(objective.overlap)
      report <- OptimizationReport
        .create(
          objective.value,
          objective.value,
          iterations = 0,
          attempts = 0,
          acceptedSteps = 0,
          termination = Termination.Stationary
        )
        .left
        .map(MotionError.Registration.apply)
    yield
      RigidPairEstimate.measured(
        pose,
        report,
        objective.overlap,
        objective.support,
        RigidOptimizerDiagnostics(
          control.strategy,
          captureCandidates = 1,
          selectedCaptureIndex = 0,
          rejectedSteps = 0,
          acceptedObjectives = Vector(objective.value),
          finalGradientNorm =
            if
              control.strategy ==
                RigidOptimizationStrategy.LevenbergMarquardt
            then Some(gradientNorm(workspace.normalScan.normal))
            else None,
          finalTranslationStepMm = None,
          finalRotationStepRadians = None,
          finalDamping = None
        )
      )

  private def selectCapture(
      initial: RigidPose[Moving, Fixed],
      workspace: RigidEstimatorWorkspace
  ): Either[MotionError, RigidCaptureSelection[Moving, Fixed]] =
    val seeds = control.capture.seeds
    evaluate(initial, workspace).flatMap { initialObjective =>
      var bestPose = initial
      var bestValue =
        if initialObjective.overlap >= control.minimumOverlap then
          initialObjective.value
        else Double.PositiveInfinity
      var selectedIndex =
        if bestValue.isFinite then 0 else -1
      var seedIndex = 0
      var failure = Option.empty[MotionError]
      while seedIndex < seeds.size && failure.isEmpty do
        captureCandidate(initial, seeds(seedIndex)) match
          case Left(error) =>
            failure = Some(error)
          case Right(candidatePose) =>
            evaluate(candidatePose, workspace) match
              case Left(error) =>
                failure = Some(error)
              case Right(candidateObjective) =>
                if
                  candidateObjective.overlap >= control.minimumOverlap &&
                  candidateObjective.value < bestValue
                then
                  bestPose = candidatePose
                  bestValue = candidateObjective.value
                  selectedIndex = seedIndex + 1
        seedIndex += 1

      failure match
        case Some(error) => Left(error)
        case None if selectedIndex < 0 =>
          Left(
            MotionError.InsufficientEstimatorOverlap(
              initialObjective.overlap,
              control.minimumOverlap
            )
          )
        case None =>
          Right(
            RigidCaptureSelection(
              bestPose,
              seeds.size + 1,
              selectedIndex
            )
          )
    }

  private def captureCandidate(
      pose: RigidPose[Moving, Fixed],
      seed: RigidCaptureSeed
  ): Either[MotionError, RigidPose[Moving, Fixed]] =
    for
      delta <- Twist6
        .createFor(fixed.frame)(
          0.0,
          0.0,
          0.0,
          seed.rx,
          seed.ry,
          seed.rz
        )
        .left
        .map(MotionError.Rigid.apply)
      transform <- pose.movingToFixed
        .retractTarget(delta)
        .left
        .map(MotionError.Rigid.apply)
    yield RigidPose.fromMovingToFixed(transform)

  private def execute(
      initialPose: RigidPose[Moving, Fixed],
      workspace: RigidEstimatorWorkspace,
      captureCandidates: Int,
      selectedCaptureIndex: Int
  ): Either[MotionError, RigidPairEstimate[Moving, Fixed]] =
    for
      initialObjective <-
        control.strategy match
          case RigidOptimizationStrategy.LevenbergMarquardt =>
            evaluateSystem(initialPose, workspace)
          case RigidOptimizationStrategy.ReferenceCoordinateSearch =>
            evaluate(initialPose, workspace)
      _ <- requireOverlap(initialObjective.overlap)
      optimized <-
        control.strategy match
          case RigidOptimizationStrategy.LevenbergMarquardt =>
            optimizeLevenbergMarquardt(
              initialPose,
              initialObjective,
              workspace
            )
          case RigidOptimizationStrategy.ReferenceCoordinateSearch =>
            optimizeReferenceCoordinateSearch(
              initialPose,
              initialObjective,
              workspace
            )
      report <- OptimizationReport
        .create(
          initialObjective.value,
          optimized.objective.value,
          optimized.iterations,
          optimized.attempts,
          optimized.acceptedSteps,
          optimized.termination
        )
        .left
        .map(MotionError.Registration.apply)
    yield
      RigidPairEstimate.measured(
        optimized.pose,
        report,
        optimized.objective.overlap,
        optimized.objective.support,
        optimized.diagnostics.copy(
          captureCandidates = captureCandidates,
          selectedCaptureIndex = selectedCaptureIndex
        )
      )

  private def optimizeLevenbergMarquardt(
      initialPose: RigidPose[Moving, Fixed],
      initialObjective: Objective,
      workspace: RigidEstimatorWorkspace
  ): Either[MotionError, Optimized[Moving, Fixed]] =
    val policy = control.damping
    val normal = workspace.normalScan.normal
    var pose = initialPose
    var objective = initialObjective
    var damping = policy.initial
    var iterations = 0
    var attempts = 0
    var acceptedSteps = 0
    var rejectedSteps = 0
    var consecutiveRejections = 0
    val acceptedObjectives = Vector.newBuilder[Double]
    acceptedObjectives += initialObjective.value
    var finalGradientNorm = gradientNorm(normal)
    var finalTranslationStep = Option.empty[Double]
    var finalRotationStep = Option.empty[Double]
    var termination = Option.empty[Termination]
    var failure = Option.empty[MotionError]

    while
      iterations < control.maximumIterations &&
      termination.isEmpty &&
      failure.isEmpty
    do
      finalGradientNorm = gradientNorm(normal)
      if finalGradientNorm <= control.gradientTolerance then
        termination = Some(Termination.GradientConverged)
      else
        RigidSmallSystem.solve(
          normal,
          damping,
          policy.conditionLimit
        ) match
          case Left(error: RigidKernelError.NonFiniteSystem) =>
            failure = Some(MotionError.RigidKernel(error))
          case Left(error: RigidKernelError.NonFiniteStep) =>
            failure = Some(MotionError.RigidKernel(error))
          case Left(_) =>
            attempts += 1
            rejectedSteps += 1
            consecutiveRejections += 1
            damping =
              math.min(policy.maximum, damping * policy.rejectedFactor)
            if
              consecutiveRejections >= policy.maximumRejectedSteps ||
              damping >= policy.maximum
            then termination = Some(Termination.RejectedStepLimit)
          case Right(step) =>
            val unscaledTranslation =
              math.sqrt(
                step.dx * step.dx +
                  step.dy * step.dy +
                  step.dz * step.dz
              )
            val unscaledRotation =
              math.sqrt(
                step.rx * step.rx +
                  step.ry * step.ry +
                  step.rz * step.rz
              )
            val translationScale =
              if
                unscaledTranslation > control.initialTranslationStepMm
              then
                control.initialTranslationStepMm / unscaledTranslation
              else 1.0
            val rotationScale =
              if
                unscaledRotation > control.initialRotationStepRadians
              then
                control.initialRotationStepRadians / unscaledRotation
              else 1.0
            val stepScale = math.min(translationScale, rotationScale)
            val translationStep = unscaledTranslation * stepScale
            val rotationStep = unscaledRotation * stepScale
            finalTranslationStep = Some(translationStep)
            finalRotationStep = Some(rotationStep)

            if
              translationStep <= control.minimumTranslationStepMm &&
              rotationStep <= control.minimumRotationStepRadians
            then termination = Some(Termination.StepConverged)
            else
              candidate(pose, step, stepScale) match
                case Left(error) =>
                  failure = Some(error)
                case Right(candidatePose) =>
                  evaluate(candidatePose, workspace) match
                    case Left(error) =>
                      failure = Some(error)
                    case Right(candidateObjective) =>
                      attempts += 1
                      val acceptable =
                        candidateObjective.overlap >=
                          control.minimumOverlap &&
                          candidateObjective.value < objective.value
                      if acceptable then
                        val reduction =
                          objective.value - candidateObjective.value
                        pose = candidatePose
                        objective = candidateObjective
                        acceptedSteps += 1
                        acceptedObjectives += candidateObjective.value
                        consecutiveRejections = 0
                        damping =
                          math.max(
                            policy.minimum,
                            damping * policy.acceptedFactor
                        )
                        iterations += 1
                        evaluateSystem(pose, workspace) match
                          case Left(error) =>
                            failure = Some(error)
                          case Right(currentObjective) =>
                            objective = currentObjective
                            if
                              reduction <= control.objectiveTolerance
                            then
                              termination =
                                Some(Termination.ObjectiveConverged)
                      else
                        rejectedSteps += 1
                        consecutiveRejections += 1
                        damping =
                          math.min(
                            policy.maximum,
                            damping * policy.rejectedFactor
                          )
                        if
                          consecutiveRejections >=
                            policy.maximumRejectedSteps ||
                          damping >= policy.maximum
                        then
                          termination =
                            Some(Termination.RejectedStepLimit)

    failure match
      case Some(error) => Left(error)
      case None =>
        finalGradientNorm = gradientNorm(normal)
        val outcome =
          termination.getOrElse(Termination.IterationLimit)
        Right(
          Optimized(
            pose,
            objective,
            iterations,
            attempts,
            acceptedSteps,
            outcome,
            RigidOptimizerDiagnostics(
              RigidOptimizationStrategy.LevenbergMarquardt,
              captureCandidates = 1,
              selectedCaptureIndex = 0,
              rejectedSteps = rejectedSteps,
              acceptedObjectives = acceptedObjectives.result(),
              finalGradientNorm = Some(finalGradientNorm),
              finalTranslationStepMm = finalTranslationStep,
              finalRotationStepRadians = finalRotationStep,
              finalDamping = Some(damping)
            )
          )
        )

  private def optimizeReferenceCoordinateSearch(
      initialPose: RigidPose[Moving, Fixed],
      initialObjective: Objective,
      workspace: RigidEstimatorWorkspace
  ): Either[MotionError, Optimized[Moving, Fixed]] =
    var pose = initialPose
    var objective = initialObjective
    var translationStep = control.initialTranslationStepMm
    var rotationStep = control.initialRotationStepRadians
    var iterations = 0
    var attempts = 0
    var acceptedSteps = 0
    val acceptedObjectives = Vector.newBuilder[Double]
    acceptedObjectives += initialObjective.value
    var stopped = false
    var failure = Option.empty[MotionError]

    while
      iterations < control.maximumIterations &&
      !stopped &&
      failure.isEmpty
    do
      var bestPose = pose
      var bestObjective = objective
      var axis = 0
      while axis < 6 && failure.isEmpty do
        var sign = -1
        while sign <= 1 && failure.isEmpty do
          if sign != 0 then
            candidate(
              pose,
              axis,
              sign.toDouble,
              translationStep,
              rotationStep
            ) match
              case Left(error) =>
                failure = Some(error)
              case Right(candidatePose) =>
                evaluate(candidatePose, workspace) match
                  case Left(error) =>
                    failure = Some(error)
                  case Right(candidateObjective) =>
                    attempts += 1
                    if
                      candidateObjective.overlap >= control.minimumOverlap &&
                      candidateObjective.value + control.objectiveTolerance <
                        bestObjective.value
                    then
                      bestPose = candidatePose
                      bestObjective = candidateObjective
          sign += 2
        axis += 1

      if failure.isEmpty then
        if bestPose eq pose then
          translationStep *= 0.5
          rotationStep *= 0.5
          if
            translationStep < control.minimumTranslationStepMm &&
            rotationStep < control.minimumRotationStepRadians
          then stopped = true
        else
          pose = bestPose
          objective = bestObjective
          acceptedSteps += 1
          acceptedObjectives += bestObjective.value
        iterations += 1

    failure match
      case Some(error) => Left(error)
      case None =>
        val termination =
          if stopped then Termination.Stationary
          else Termination.IterationLimit
        Right(
          Optimized(
            pose,
            objective,
            iterations,
            attempts,
            acceptedSteps,
            termination,
            RigidOptimizerDiagnostics(
              RigidOptimizationStrategy.ReferenceCoordinateSearch,
              captureCandidates = 1,
              selectedCaptureIndex = 0,
              rejectedSteps = attempts - acceptedSteps,
              acceptedObjectives = acceptedObjectives.result(),
              finalGradientNorm = None,
              finalTranslationStepMm = Some(translationStep),
              finalRotationStepRadians = Some(rotationStep),
              finalDamping = None
            )
          )
        )

  private def candidate(
      pose: RigidPose[Moving, Fixed],
      axis: Int,
      sign: Double,
      translationStep: Double,
      rotationStep: Double
  ): Either[MotionError, RigidPose[Moving, Fixed]] =
    val linear =
      if axis < 3 then
        Vector.tabulate(3)(index =>
          if index == axis then sign * translationStep else 0.0
        )
      else Vector.fill(3)(0.0)
    val angular =
      if axis >= 3 then
        Vector.tabulate(3)(index =>
          if index == axis - 3 then sign * rotationStep else 0.0
        )
      else Vector.fill(3)(0.0)
    for
      delta <- Twist6
        .fromVectors(fixed.frame)(linear, angular)
        .left
        .map(MotionError.Rigid.apply)
      transform <- pose.movingToFixed
        .retractTarget(delta)
        .left
        .map(MotionError.Rigid.apply)
    yield RigidPose.fromMovingToFixed(transform)

  private def candidate(
      pose: RigidPose[Moving, Fixed],
      step: RigidStep,
      scale: Double
  ): Either[MotionError, RigidPose[Moving, Fixed]] =
    for
      delta <- Twist6
        .createFor(fixed.frame)(
          step.dx * scale,
          step.dy * scale,
          step.dz * scale,
          step.rx * scale,
          step.ry * scale,
          step.rz * scale
        )
        .left
        .map(MotionError.Rigid.apply)
      transform <- pose.movingToFixed
        .retractTarget(delta)
        .left
        .map(MotionError.Rigid.apply)
    yield RigidPose.fromMovingToFixed(transform)

  private def evaluateSystem(
      pose: RigidPose[Moving, Fixed],
      workspace: RigidEstimatorWorkspace
  ): Either[MotionError, Objective] =
    workspace.normalScan.prepare(stencil, robustLoss)
    for
      plan <- ResamplingPlan
        .affine(
          moving,
          fixed.grid,
          pose.fixedToMoving,
          Interpolation.Linear,
          BoundaryPolicy.Constant(0.0)
        )
        .left
        .map(MotionError.Resampling.apply)
      _ <- plan
        .scan(workspace.resampling, workspace.normalScan)
        .left
        .map(MotionError.Resampling.apply)
      normal <- workspace.normalScan.result
        .left
        .map(MotionError.RigidKernel.apply)
      _ <-
        if normal.objective.isFinite then Right(())
        else
          Left(
            MotionError.Registration(
              reframe4s.register.RegistrationFailure
                .InvalidObjective(normal.objective)
            )
          )
    yield
      Objective(
        normal.objective,
        normal.overlap,
        normal.support
      )

  private def gradientNorm(workspace: RigidNormalWorkspace): Double =
    var squared = 0.0
    var index = 0
    while index < 6 do
      val value = workspace.gradient(index)
      squared += value * value
      index += 1
    math.sqrt(squared)

  private def evaluate(
      pose: RigidPose[Moving, Fixed],
      workspace: RigidEstimatorWorkspace
  ): Either[MotionError, Objective] =
    val count =
      fixed.grid.shape(0).toLong *
        fixed.grid.shape(1).toLong *
        fixed.grid.shape(2).toLong
    val sink =
      new RigidObjectiveSink(
        fixedData,
        robustLoss,
        count
      )
    for
      plan <- ResamplingPlan
        .affine(
          moving,
          fixed.grid,
          pose.fixedToMoving,
          Interpolation.Linear,
          BoundaryPolicy.Constant(0.0)
        )
        .left
        .map(MotionError.Resampling.apply)
      _ <- plan
        .scan(workspace.resampling, sink)
        .left
        .map(MotionError.Resampling.apply)
      objective <- sink.result
    yield objective

  def initialPose
      : Either[MotionError, RigidPose[Moving, Fixed]] =
    for
      movingCenter <- centroid(
        movingData,
        moving.grid,
        EstimatorInput.Moving
      )
      fixedCenter <- centroid(
        fixedData,
        fixed.grid,
        EstimatorInput.Fixed
      )
      transform <- Rigid3
        .translationBetween[Moving, Fixed](
          moving.frame,
          fixed.frame
        )(
          fixedCenter(0) - movingCenter(0),
          fixedCenter(1) - movingCenter(1),
          fixedCenter(2) - movingCenter(2)
        )
        .left
        .map(MotionError.Rigid.apply)
    yield RigidPose.fromMovingToFixed(transform)

  private def centroid[F <: Frame[D3]](
      data: NDArray[Double, Rank[3]],
      grid: Grid[F, D3],
      input: EstimatorInput
  ): Either[MotionError, Vector[Double]] =
    val matrix = grid.indexToFrame.rowMajor
    var totalWeight = 0.0
    var totalX = 0.0
    var totalY = 0.0
    var totalZ = 0.0
    var i = 0
    while i < grid.shape(0) do
      var j = 0
      while j < grid.shape(1) do
        var k = 0
        while k < grid.shape(2) do
          val weight = math.abs(data(i, j, k))
          if weight > 0.0 then
            totalWeight += weight
            totalX +=
              weight * (
                matrix(0) * i +
                  matrix(1) * j +
                  matrix(2) * k +
                  matrix(3)
              )
            totalY +=
              weight * (
                matrix(4) * i +
                  matrix(5) * j +
                  matrix(6) * k +
                  matrix(7)
              )
            totalZ +=
              weight * (
                matrix(8) * i +
                  matrix(9) * j +
                  matrix(10) * k +
                  matrix(11)
              )
          k += 1
        j += 1
      i += 1
    if totalWeight <= 0.0 then Left(MotionError.ZeroEstimatorSupport(input))
    else
      Right(
        Vector(
          totalX / totalWeight,
          totalY / totalWeight,
          totalZ / totalWeight
        )
      )

  private def requireOverlap(value: Double): Either[MotionError, Unit] =
    if value >= control.minimumOverlap then Right(())
    else
      Left(
        MotionError.InsufficientEstimatorOverlap(
          value,
          control.minimumOverlap
        )
      )

object CompiledRigidPairEstimator:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      RMoving <: AnyRank,
      RFixed <: AnyRank
  ](
      moving: MotionScalarImage[Moving, D3, RMoving],
      fixed: MotionScalarImage[Fixed, D3, RFixed],
      control: RigidOptimizerControl = RigidOptimizerControl.default
  )(using
      Dimension[D3]
  ): Either[
    MotionError,
    CompiledRigidPairEstimator[Moving, Fixed, RMoving, RFixed]
  ] =
    for
      _ <- requireSpatialOnly(moving, EstimatorInput.Moving)
      _ <- requireSpatialOnly(fixed, EstimatorInput.Fixed)
      movingData <- requireRankThree(moving, EstimatorInput.Moving)
      fixedData <- requireRankThree(fixed, EstimatorInput.Fixed)
      _ <- validateFinite(movingData, moving.grid, EstimatorInput.Moving)
      _ <- validateFinite(fixedData, fixed.grid, EstimatorInput.Fixed)
      stencil <- (
        control.sampling match
          case RigidSamplingPolicy.Dense =>
            CompiledRigidStencil.dense(fixed)
          case policy: RigidSamplingPolicy.InformationAware =>
            CompiledRigidStencil.informationAware(fixed, policy.control)
      )
        .left
        .map(MotionError.RigidKernel.apply)
    yield
      new CompiledRigidPairEstimator(
        moving,
        fixed,
        control,
        movingData,
        fixedData,
        stencil,
        control.robustLoss
      )

  private def requireSpatialOnly[
      F <: Frame[D3],
      R <: AnyRank
  ](
      image: MotionScalarImage[F, D3, R],
      input: EstimatorInput
  ): Either[MotionError, Unit] =
    if image.nonSpatialAxes.size == 0 then Right(())
    else
      Left(
        MotionError.EstimatorHasNonSpatialAxes(
          input,
          image.nonSpatialAxes.size
        )
      )

  private def requireRankThree[
      F <: Frame[D3],
      R <: AnyRank
  ](
      image: MotionScalarImage[F, D3, R],
      input: EstimatorInput
  ): Either[MotionError, NDArray[Double, Rank[3]]] =
    image.data
      .requireRank[3]
      .left
      .map(_ =>
        MotionError.UnsupportedSpatialDataRank(input, image.data.rank)
      )

  private def validateFinite[F <: Frame[D3]](
      data: NDArray[Double, Rank[3]],
      grid: Grid[F, D3],
      input: EstimatorInput
  ): Either[MotionError, Unit] =
    var failure = Option.empty[MotionError]
    var i = 0
    while i < grid.shape(0) && failure.isEmpty do
      var j = 0
      while j < grid.shape(1) && failure.isEmpty do
        var k = 0
        while k < grid.shape(2) && failure.isEmpty do
          val value = data(i, j, k)
          if !value.isFinite then
            failure = Some(
              MotionError.NonFiniteEstimatorVoxel(
                input,
                i,
                j,
                k,
                value
              )
            )
          k += 1
        j += 1
      i += 1
    failure.toLeft(())

private final case class Objective(
    value: Double,
    overlap: Double,
    support: Long
)

private final case class RigidCaptureSelection[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    pose: RigidPose[Moving, Fixed],
    candidateCount: Int,
    selectedIndex: Int
)

private final class RigidObjectiveSink(
    fixed: NDArray[Double, Rank[3]],
    robustLoss: RigidRobustLoss,
    count: Long
) extends ResamplingSink:
  private var loss = 0.0
  private var overlapWeight = 0.0
  private var support = 0L
  private val fixed1 = fixed.shape(1)
  private val fixed2 = fixed.shape(2)
  private val fixedPlane = fixed1 * fixed2

  def accept(
      outputLinearIndex: Int,
      value: Double,
      validityWeight: Double
  ): Unit =
    val target0 = outputLinearIndex / fixedPlane
    val remainder = outputLinearIndex - target0 * fixedPlane
    val target1 = remainder / fixed2
    val target2 = remainder - target1 * fixed2
    val residual =
      value - fixed(target0, target1, target2)
    loss += RigidRobustLoss.sampleLoss(robustLoss, residual)
    overlapWeight += validityWeight
    if validityWeight > 0.0 then support += 1L

  def result: Either[MotionError, Objective] =
    val value = loss / count.toDouble
    if !value.isFinite then
      Left(
        MotionError.Registration(
          reframe4s.register.RegistrationFailure.InvalidObjective(value)
        )
      )
    else
      Right(
        Objective(
          value,
          overlapWeight / count.toDouble,
          support
        )
      )

private final case class Optimized[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    pose: RigidPose[Moving, Fixed],
    objective: Objective,
    iterations: Int,
    attempts: Int,
    acceptedSteps: Int,
    termination: Termination,
    diagnostics: RigidOptimizerDiagnostics
)
