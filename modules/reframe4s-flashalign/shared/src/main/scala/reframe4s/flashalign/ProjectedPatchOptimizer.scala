package reframe4s.flashalign

import scala.util.control.NonFatal

private[flashalign] enum ProjectedPatchStage derives CanEqual:
  case DataLinearization
  case TrialDataObjective
  case SelectionObjective
  case PriorLinearization
  case PriorValue
  case PhysicalMetric
  case Proposal
  case MaximumDisplacement

private[flashalign] sealed trait ProjectedPatchOptimizerError derives CanEqual:
  def message: String

private[flashalign] object ProjectedPatchOptimizerError:
  final case class InvalidConfiguration(detail: String)
      extends ProjectedPatchOptimizerError:
    val message: String = s"invalid projected-patch optimizer configuration: $detail"

  final case class EvaluationFailure(
      stage: ProjectedPatchStage,
      detail: String
  ) extends ProjectedPatchOptimizerError:
    val message: String = s"$stage failed: $detail"

  case object WorkspacePlanMismatch extends ProjectedPatchOptimizerError:
    val message: String =
      "projected-patch optimizer workspace belongs to another plan"

  case object WorkspaceInUse extends ProjectedPatchOptimizerError:
    val message: String =
      "projected-patch optimizer workspace is already in use"

  final case class InvalidQuadratic(
      stage: ProjectedPatchStage,
      component: String,
      index: Int,
      value: Double
  ) extends ProjectedPatchOptimizerError:
    val message: String =
      s"$stage $component value $index must be finite, got $value"

  final case class SelectionObjectiveMismatch(expected: Long, actual: Long)
      extends ProjectedPatchOptimizerError:
    val message: String =
      s"selection objective identity changed from $expected to $actual"

  final case class ProposalChangedStep(
      parameter: Int,
      requested: Double,
      realized: Double
  ) extends ProjectedPatchOptimizerError:
    val message: String =
      s"proposal changed parameter $parameter from $requested to $realized"

private[flashalign] final class ProjectedPatchQuadraticBuffer private (
    val parameterCount: Int,
    val gradient: Array[Double],
    val curvatureUpper: Array[Double]
):
  private var currentObjective = 0.0

  def objective: Double = currentObjective

  def setObjective(value: Double): Unit =
    currentObjective = value

  def setCurvature(row: Int, column: Int, value: Double): Unit =
    curvatureUpper(PackedSymmetric.index(row, column)) = value

  def curvature(row: Int, column: Int): Double =
    curvatureUpper(PackedSymmetric.index(row, column))

  private[flashalign] def clear(): Unit =
    currentObjective = 0.0
    java.util.Arrays.fill(gradient, 0.0)
    java.util.Arrays.fill(curvatureUpper, 0.0)

private[flashalign] object ProjectedPatchQuadraticBuffer:
  def create(parameterCount: Int): ProjectedPatchQuadraticBuffer =
    new ProjectedPatchQuadraticBuffer(
      parameterCount,
      new Array[Double](parameterCount),
      new Array[Double](PackedSymmetric.size(parameterCount))
    )

private[flashalign] sealed trait ProjectedPatchTrialData derives CanEqual

private[flashalign] object ProjectedPatchTrialData:
  final case class Complete(value: Double) extends ProjectedPatchTrialData
  final case class RejectedEarly(lowerBound: Double)
      extends ProjectedPatchTrialData

private[flashalign] final case class ProjectedPatchSelection(
    objectiveId: Long,
    dataObjective: Double
)

private[flashalign] trait ProjectedPatchDataProblem[State]:
  def parameterCount: Int
  def optimizationObjectiveId: Long
  def selectionObjectiveId: Long

  def linearize(
      state: State,
      output: ProjectedPatchQuadraticBuffer
  ): Either[ProjectedPatchOptimizerError, Unit]

  def trialDataObjective(
      state: State,
      acceptanceLimit: Double
  ): Either[ProjectedPatchOptimizerError, ProjectedPatchTrialData]

  def selectionObjective(
      state: State
  ): Either[ProjectedPatchOptimizerError, ProjectedPatchSelection]

private[flashalign] trait ProjectedPatchPrior[State]:
  def linearize(
      state: State,
      output: ProjectedPatchQuadraticBuffer
  ): Either[ProjectedPatchOptimizerError, Unit]

  def value(state: State): Either[ProjectedPatchOptimizerError, Double]

private[flashalign] final case class ProjectedPatchProposal[State](
    state: State,
    realizedStep: Array[Double]
)

private[flashalign] sealed trait ProjectedPatchProposalResult[+State]

private[flashalign] object ProjectedPatchProposalResult:
  final case class Valid[State](proposal: ProjectedPatchProposal[State])
      extends ProjectedPatchProposalResult[State]
  case object InvalidGeometry extends ProjectedPatchProposalResult[Nothing]

private[flashalign] trait ProjectedPatchGeometry[State]:
  def writePhysicalMetric(
      state: State,
      outputUpper: Array[Double]
  ): Either[ProjectedPatchOptimizerError, Unit]

  def propose(
      state: State,
      step: Array[Double]
  ): Either[ProjectedPatchOptimizerError, ProjectedPatchProposalResult[State]]

  def maximumDisplacement(
      before: State,
      after: State
  ): Either[ProjectedPatchOptimizerError, Double]

private[flashalign] final class ProjectedPatchOptimizerConfig private (
    val parameterCount: Int,
    val maximumLinearizations: Int,
    val maximumTrialAttempts: Int,
    val initialDamping: Double,
    val minimumDamping: Double,
    val maximumDamping: Double,
    val rejectedDampingFactor: Double,
    val acceptedDampingFactor: Double,
    val trustRadiusRms: Double,
    val maximumDisplacement: Double,
    val acceptanceRatio: Double,
    val highGainRatio: Double,
    val objectiveTolerance: Double,
    val gradientTolerance: Double,
    val stepToleranceRms: Double,
    val rankRelativeTolerance: Double,
    val minimumDataRank: Int,
    val conditionLimit: Double,
    val checkpointInterval: Int,
    val realizedStepTolerance: Double
)

private[flashalign] object ProjectedPatchOptimizerConfig:
  def create(
      parameterCount: Int,
      maximumLinearizations: Int = 50,
      maximumTrialAttempts: Int = 8,
      initialDamping: Double = 1e-2,
      minimumDamping: Double = 1e-8,
      maximumDamping: Double = 1e8,
      rejectedDampingFactor: Double = 4.0,
      acceptedDampingFactor: Double = 0.5,
      trustRadiusRms: Double = 2.0,
      maximumDisplacement: Double = 4.0,
      acceptanceRatio: Double = 0.1,
      highGainRatio: Double = 0.75,
      objectiveTolerance: Double = 1e-8,
      gradientTolerance: Double = 1e-7,
      stepToleranceRms: Double = 1e-6,
      rankRelativeTolerance: Double = 1e-10,
      minimumDataRank: Int = -1,
      conditionLimit: Double = 1e12,
      checkpointInterval: Int = 1,
      realizedStepTolerance: Double = 1e-12
  ): Either[ProjectedPatchOptimizerError, ProjectedPatchOptimizerConfig] =
    val requiredRank =
      if minimumDataRank < 0 then parameterCount else minimumDataRank
    if parameterCount <= 0 || parameterCount > 12 then
      invalid("parameter count must be within [1, 12]")
    else if maximumLinearizations <= 0 || maximumTrialAttempts <= 0 then
      invalid("linearization and trial-attempt limits must be positive")
    else if
      !minimumDamping.isFinite || minimumDamping <= 0.0 ||
        !initialDamping.isFinite || initialDamping < minimumDamping ||
        !maximumDamping.isFinite || maximumDamping < initialDamping
    then invalid("damping must satisfy 0 < minimum <= initial <= maximum")
    else if !rejectedDampingFactor.isFinite || rejectedDampingFactor <= 1.0 then
      invalid("rejected damping factor must exceed one")
    else if
      !acceptedDampingFactor.isFinite || acceptedDampingFactor <= 0.0 ||
        acceptedDampingFactor >= 1.0
    then invalid("accepted damping factor must be within (0, 1)")
    else if !trustRadiusRms.isFinite || trustRadiusRms <= 0.0 then
      invalid("RMS trust radius must be finite and positive")
    else if !maximumDisplacement.isFinite || maximumDisplacement <= 0.0 then
      invalid("maximum displacement must be finite and positive")
    else if
      !acceptanceRatio.isFinite || acceptanceRatio <= 0.0 ||
        acceptanceRatio >= 1.0 || !highGainRatio.isFinite ||
        highGainRatio <= acceptanceRatio || highGainRatio > 1.0
    then invalid("gain ratios must satisfy 0 < acceptance < high <= 1")
    else if
      !objectiveTolerance.isFinite || objectiveTolerance < 0.0 ||
        !gradientTolerance.isFinite || gradientTolerance < 0.0 ||
        !stepToleranceRms.isFinite || stepToleranceRms < 0.0
    then invalid("convergence tolerances must be finite and non-negative")
    else if
      !rankRelativeTolerance.isFinite || rankRelativeTolerance <= 0.0
    then invalid("rank tolerance must be finite and positive")
    else if requiredRank < 0 || requiredRank > parameterCount then
      invalid("minimum data rank must be within the parameter dimension")
    else if !conditionLimit.isFinite || conditionLimit <= 1.0 then
      invalid("condition limit must be finite and greater than one")
    else if checkpointInterval <= 0 then
      invalid("checkpoint interval must be positive")
    else if !realizedStepTolerance.isFinite || realizedStepTolerance < 0.0 then
      invalid("realized-step tolerance must be finite and non-negative")
    else
      Right(
        new ProjectedPatchOptimizerConfig(
          parameterCount,
          maximumLinearizations,
          maximumTrialAttempts,
          initialDamping,
          minimumDamping,
          maximumDamping,
          rejectedDampingFactor,
          acceptedDampingFactor,
          trustRadiusRms,
          maximumDisplacement,
          acceptanceRatio,
          highGainRatio,
          objectiveTolerance,
          gradientTolerance,
          stepToleranceRms,
          rankRelativeTolerance,
          requiredRank,
          conditionLimit,
          checkpointInterval,
          realizedStepTolerance
        )
      )

  private def invalid(
      detail: String
  ): Either[ProjectedPatchOptimizerError, ProjectedPatchOptimizerConfig] =
    Left(ProjectedPatchOptimizerError.InvalidConfiguration(detail))

private[flashalign] enum ProjectedPatchRejection derives CanEqual:
  case NonPositivePrediction
  case SolverFailure
  case GeometryConstraint
  case MaximumDisplacement
  case EarlyObjectiveBound
  case InsufficientActualReduction

private[flashalign] final case class ProjectedPatchAttemptDiagnostics(
    linearization: Int,
    attempt: Int,
    damping: Double,
    rmsStep: Double,
    maximumDisplacement: Double,
    predictedReduction: Double,
    actualReduction: Option[Double],
    gainRatio: Option[Double],
    clipped: Boolean,
    accepted: Boolean,
    rejection: Option[ProjectedPatchRejection]
)

private[flashalign] final case class ProjectedPatchWorkCounters(
    dataLinearizations: Int,
    priorLinearizations: Int,
    linearSolverCalls: Int,
    trialEvaluations: Int,
    earlyRejectedTrials: Int,
    acceptedSteps: Int,
    rejectedSteps: Int,
    clippedSteps: Int,
    selectionEvaluations: Int
)

private[flashalign] object ProjectedPatchWorkCounters:
  val Zero: ProjectedPatchWorkCounters =
    ProjectedPatchWorkCounters(0, 0, 0, 0, 0, 0, 0, 0, 0)

private[flashalign] sealed trait ProjectedPatchTermination derives CanEqual:
  def converged: Boolean

private[flashalign] object ProjectedPatchTermination:
  case object GradientConverged extends ProjectedPatchTermination:
    val converged: Boolean = true

  case object ObjectiveConverged extends ProjectedPatchTermination:
    val converged: Boolean = true

  case object StepConverged extends ProjectedPatchTermination:
    val converged: Boolean = true

  final case class RankDeficient(observed: Int, required: Int)
      extends ProjectedPatchTermination:
    val converged: Boolean = false

  case object DampingLimited extends ProjectedPatchTermination:
    val converged: Boolean = false

  case object TrialAttemptLimit extends ProjectedPatchTermination:
    val converged: Boolean = false

  case object LinearizationLimit extends ProjectedPatchTermination:
    val converged: Boolean = false

  final case class NonFiniteSolve(component: String, index: Int, value: Double)
      extends ProjectedPatchTermination:
    val converged: Boolean = false

  final case class SolverFailure(detail: String)
      extends ProjectedPatchTermination:
    val converged: Boolean = false

  /** An evaluator or geometry provider failed after the initial selection
    * checkpoint existed. The optimizer returns this as a bounded result so
    * callers retain the last valid state, best checkpoint, attempts and work.
    */
  final case class ExceptionalFailure(error: ProjectedPatchOptimizerError)
      extends ProjectedPatchTermination:
    val converged: Boolean = false

private[flashalign] final case class ProjectedPatchCheckpoint[State](
    state: State,
    selectionObjective: Double,
    acceptedSteps: Int,
    selectionObjectiveId: Long
)

private[flashalign] final class ProjectedPatchOptimizationResult[State] private[flashalign] (
    val state: State,
    val lastValidState: State,
    val bestCheckpoint: ProjectedPatchCheckpoint[State],
    val initialOptimizationObjective: Double,
    val lastOptimizationObjective: Double,
    val termination: ProjectedPatchTermination,
    val counters: ProjectedPatchWorkCounters,
    val attempts: Vector[ProjectedPatchAttemptDiagnostics]
)

private[flashalign] final class ProjectedPatchOptimizerWorkspace private[flashalign] (
    private val owner: AnyRef,
    private val parameterCount: Int,
    private[flashalign] val data: ProjectedPatchQuadraticBuffer,
    private[flashalign] val prior: ProjectedPatchQuadraticBuffer,
    private[flashalign] val combinedGradient: Array[Double],
    private[flashalign] val rightHandSide: Array[Double],
    private[flashalign] val combinedCurvature: Array[Double],
    private[flashalign] val physicalMetric: Array[Double],
    private[flashalign] val step: Array[Double],
    private[flashalign] val factor: Array[Double],
    private[flashalign] val forward: Array[Double],
    private[flashalign] val rankMatrix: Array[Double]
):
  private var active = false
  private var failureCounters = ProjectedPatchWorkCounters.Zero

  private[flashalign] def acquire(candidate: AnyRef): Either[
    ProjectedPatchOptimizerError,
    Unit
  ] =
    this.synchronized {
      if !(owner eq candidate) then
        Left(ProjectedPatchOptimizerError.WorkspacePlanMismatch)
      else if active then Left(ProjectedPatchOptimizerError.WorkspaceInUse)
      else
        active = true
        Right(())
    }

  private[flashalign] def release(candidate: AnyRef): Unit =
    this.synchronized {
      if owner eq candidate then active = false
    }

  private[flashalign] def resetFailureCounters(): Unit =
    failureCounters = ProjectedPatchWorkCounters.Zero

  private[flashalign] def recordFailureCounters(
      value: ProjectedPatchWorkCounters
  ): Unit =
    failureCounters = value

  private[flashalign] def failureCountersSnapshot: ProjectedPatchWorkCounters =
    failureCounters

  private[flashalign] def dimension: Int = parameterCount

private[flashalign] final class ProjectedPatchOptimizer[State] private (
    val dataProblem: ProjectedPatchDataProblem[State],
    val priorModel: ProjectedPatchPrior[State],
    val geometry: ProjectedPatchGeometry[State],
    val config: ProjectedPatchOptimizerConfig
):
  def newWorkspace(): ProjectedPatchOptimizerWorkspace =
    val dimension = config.parameterCount
    new ProjectedPatchOptimizerWorkspace(
      this,
      dimension,
      ProjectedPatchQuadraticBuffer.create(dimension),
      ProjectedPatchQuadraticBuffer.create(dimension),
      new Array[Double](dimension),
      new Array[Double](dimension),
      new Array[Double](PackedSymmetric.size(dimension)),
      new Array[Double](PackedSymmetric.size(dimension)),
      new Array[Double](dimension),
      new Array[Double](dimension * dimension),
      new Array[Double](dimension),
      new Array[Double](dimension * dimension)
    )

  def optimize(
      initial: State,
      workspace: ProjectedPatchOptimizerWorkspace
  ): Either[
    ProjectedPatchOptimizerError,
    ProjectedPatchOptimizationResult[State]
  ] =
    workspace.acquire(this).flatMap { _ =>
      try
        workspace.resetFailureCounters()
        run(initial, workspace)
      finally workspace.release(this)
    }

  private def run(
      initial: State,
      workspace: ProjectedPatchOptimizerWorkspace
  ): Either[
    ProjectedPatchOptimizerError,
    ProjectedPatchOptimizationResult[State]
  ] =
    var dataLinearizations = 0
    var priorLinearizations = 0
    var solverCalls = 0
    var trialEvaluations = 0
    var earlyRejectedTrials = 0
    var acceptedSteps = 0
    var rejectedSteps = 0
    var clippedSteps = 0
    var selectionEvaluations = 0
    val attemptLog = Vector.newBuilder[ProjectedPatchAttemptDiagnostics]

    dataLinearizations += 1
    linearize(initial, workspace) match
      case Left(error) =>
        if priorLinearizationWasAttempted(error) then priorLinearizations += 1
        workspace.recordFailureCounters(
          ProjectedPatchWorkCounters(
            dataLinearizations,
            priorLinearizations,
            solverCalls,
            trialEvaluations,
            earlyRejectedTrials,
            acceptedSteps,
            rejectedSteps,
            clippedSteps,
            selectionEvaluations
          )
        )
        return Left(error)
      case Right(_) => priorLinearizations += 1
    val initialObjective = workspace.data.objective + workspace.prior.objective
    selectionEvaluations += 1
    checkpoint(initial, acceptedSteps) match
      case Left(error) =>
        workspace.recordFailureCounters(
          ProjectedPatchWorkCounters(
            dataLinearizations,
            priorLinearizations,
            solverCalls,
            trialEvaluations,
            earlyRejectedTrials,
            acceptedSteps,
            rejectedSteps,
            clippedSteps,
            selectionEvaluations
          )
        )
        return Left(error)
      case Right(value) =>
        var bestCheckpoint = value
        var state = initial
        var currentObjective = initialObjective
        var damping = config.initialDamping
        var linearization = 0
        def exceptionalFailure(
            error: ProjectedPatchOptimizerError
        ): Either[
          ProjectedPatchOptimizerError,
          ProjectedPatchOptimizationResult[State]
        ] =
          Right(
            finish(
              bestCheckpoint,
              state,
              initialObjective,
              currentObjective,
              ProjectedPatchTermination.ExceptionalFailure(error),
              dataLinearizations,
              priorLinearizations,
              solverCalls,
              trialEvaluations,
              earlyRejectedTrials,
              acceptedSteps,
              rejectedSteps,
              clippedSteps,
              selectionEvaluations,
              attemptLog.result()
            )
          )
        while linearization < config.maximumLinearizations do
          combine(workspace)
          val rank = SmallProjectedPatchSystem.numericalRank(
            workspace.data.curvatureUpper,
            config.parameterCount,
            config.rankRelativeTolerance,
            workspace.rankMatrix
          )
          if rank < config.minimumDataRank then
            return Right(
              finish(
                bestCheckpoint,
                state,
                initialObjective,
                currentObjective,
                ProjectedPatchTermination.RankDeficient(
                  rank,
                  config.minimumDataRank
                ),
                dataLinearizations,
                priorLinearizations,
                solverCalls,
                trialEvaluations,
                earlyRejectedTrials,
                acceptedSteps,
                rejectedSteps,
                clippedSteps,
                selectionEvaluations,
                attemptLog.result()
              )
            )
          val gradientNorm = euclideanNorm(workspace.combinedGradient)
          if gradientNorm <= config.gradientTolerance then
            return Right(
              finish(
                bestCheckpoint,
                state,
                initialObjective,
                currentObjective,
                ProjectedPatchTermination.GradientConverged,
                dataLinearizations,
                priorLinearizations,
                solverCalls,
                trialEvaluations,
                earlyRejectedTrials,
                acceptedSteps,
                rejectedSteps,
                clippedSteps,
                selectionEvaluations,
                attemptLog.result()
              )
            )
          safeEvaluation(ProjectedPatchStage.PhysicalMetric)(
            geometry.writePhysicalMetric(state, workspace.physicalMetric)
          ) match
            case Left(error) => return exceptionalFailure(error)
            case Right(_)    => ()
          validateArray(
            ProjectedPatchStage.PhysicalMetric,
            "curvature",
            workspace.physicalMetric
          ) match
            case Left(error) => return exceptionalFailure(error)
            case Right(_)    => ()

          var accepted = false
          var attempt = 0
          val trialEvaluationsBefore = trialEvaluations
          var lastSolverFailure = Option.empty[String]
          while attempt < config.maximumTrialAttempts && !accepted do
            solverCalls += 1
            SmallProjectedPatchSystem.solve(
              workspace.combinedCurvature,
              workspace.physicalMetric,
              workspace.rightHandSide,
              damping,
              config.parameterCount,
              config.conditionLimit,
              workspace.factor,
              workspace.forward,
              workspace.step
            ) match
              case Left(SmallProjectedPatchSolveError.NonFinite(
                    component,
                    index,
                    value
                  )) =>
                return Right(
                  finish(
                    bestCheckpoint,
                    state,
                    initialObjective,
                    currentObjective,
                    ProjectedPatchTermination.NonFiniteSolve(
                      component,
                      index,
                      value
                    ),
                    dataLinearizations,
                    priorLinearizations,
                    solverCalls,
                    trialEvaluations,
                    earlyRejectedTrials,
                    acceptedSteps,
                    rejectedSteps,
                    clippedSteps,
                    selectionEvaluations,
                    attemptLog.result()
                  )
                )
              case Left(error) =>
                lastSolverFailure = Some(error.message)
                rejectedSteps += 1
                attemptLog += ProjectedPatchAttemptDiagnostics(
                  linearization,
                  attempt,
                  damping,
                  0.0,
                  0.0,
                  0.0,
                  None,
                  None,
                  clipped = false,
                  accepted = false,
                  Some(ProjectedPatchRejection.SolverFailure)
                )
                damping = increasedDamping(damping)
              case Right(_) =>
                var rmsStep = metricNorm(
                  workspace.step,
                  workspace.physicalMetric,
                  config.parameterCount
                )
                val clipped = rmsStep > config.trustRadiusRms
                if clipped then
                  val scale = config.trustRadiusRms / rmsStep
                  scaleInPlace(workspace.step, scale)
                  rmsStep = metricNorm(
                    workspace.step,
                    workspace.physicalMetric,
                    config.parameterCount
                  )
                  clippedSteps += 1
                val predicted = predictedReduction(
                  workspace.rightHandSide,
                  workspace.combinedCurvature,
                  workspace.step,
                  config.parameterCount
                )
                val severeDamping =
                  damping >= 0.5 * config.maximumDamping
                if rmsStep <= config.stepToleranceRms then
                  val termination =
                    if severeDamping then
                      ProjectedPatchTermination.DampingLimited
                    else ProjectedPatchTermination.StepConverged
                  return Right(
                    finish(
                      bestCheckpoint,
                      state,
                      initialObjective,
                      currentObjective,
                      termination,
                      dataLinearizations,
                      priorLinearizations,
                      solverCalls,
                      trialEvaluations,
                      earlyRejectedTrials,
                      acceptedSteps,
                      rejectedSteps,
                      clippedSteps,
                      selectionEvaluations,
                      attemptLog.result()
                    )
                  )
                else if !predicted.isFinite || predicted <= 0.0 then
                  rejectedSteps += 1
                  attemptLog += ProjectedPatchAttemptDiagnostics(
                    linearization,
                    attempt,
                    damping,
                    rmsStep,
                    0.0,
                    predicted,
                    None,
                    None,
                    clipped,
                    accepted = false,
                    Some(ProjectedPatchRejection.NonPositivePrediction)
                  )
                  damping = increasedDamping(damping)
                else
                  safeEvaluation(ProjectedPatchStage.Proposal)(
                    geometry.propose(state, workspace.step)
                  ) match
                    case Left(error) => return exceptionalFailure(error)
                    case Right(ProjectedPatchProposalResult.InvalidGeometry) =>
                      rejectedSteps += 1
                      attemptLog += ProjectedPatchAttemptDiagnostics(
                        linearization,
                        attempt,
                        damping,
                        rmsStep,
                        0.0,
                        predicted,
                        None,
                        None,
                        clipped,
                        accepted = false,
                        Some(ProjectedPatchRejection.GeometryConstraint)
                      )
                      damping = increasedDamping(damping)
                    case Right(ProjectedPatchProposalResult.Valid(proposal)) =>
                      validateRealizedStep(
                        workspace.step,
                        proposal.realizedStep
                      ) match
                        case Left(error) => return exceptionalFailure(error)
                        case Right(_)    => ()
                      val maximum = safeEvaluation(
                        ProjectedPatchStage.MaximumDisplacement
                      )(
                        geometry.maximumDisplacement(state, proposal.state)
                      ) match
                        case Left(error) => return exceptionalFailure(error)
                        case Right(value) => value
                      if !maximum.isFinite || maximum < 0.0 then
                        return exceptionalFailure(
                          ProjectedPatchOptimizerError.EvaluationFailure(
                            ProjectedPatchStage.MaximumDisplacement,
                            s"expected finite non-negative displacement, got $maximum"
                          )
                        )
                      else if maximum > config.maximumDisplacement then
                        rejectedSteps += 1
                        attemptLog += ProjectedPatchAttemptDiagnostics(
                          linearization,
                          attempt,
                          damping,
                          rmsStep,
                          maximum,
                          predicted,
                          None,
                          None,
                          clipped,
                          accepted = false,
                          Some(ProjectedPatchRejection.MaximumDisplacement)
                        )
                        damping = increasedDamping(damping)
                      else
                        safeEvaluation(ProjectedPatchStage.PriorValue)(
                          priorModel.value(proposal.state)
                        ) match
                          case Left(error) => return exceptionalFailure(error)
                          case Right(candidatePrior) =>
                            if !candidatePrior.isFinite then
                              return exceptionalFailure(
                                ProjectedPatchOptimizerError.InvalidQuadratic(
                                  ProjectedPatchStage.PriorValue,
                                  "objective",
                                  0,
                                  candidatePrior
                                )
                              )
                            val acceptanceLimit =
                              currentObjective - config.acceptanceRatio * predicted
                            val dataLimit = acceptanceLimit - candidatePrior
                            trialEvaluations += 1
                            safeEvaluation(
                              ProjectedPatchStage.TrialDataObjective
                            )(
                              dataProblem.trialDataObjective(
                                proposal.state,
                                dataLimit
                              )
                            ) match
                              case Left(error) => return exceptionalFailure(error)
                              case Right(
                                    ProjectedPatchTrialData.RejectedEarly(_)
                                  ) =>
                                earlyRejectedTrials += 1
                                rejectedSteps += 1
                                attemptLog +=
                                  ProjectedPatchAttemptDiagnostics(
                                    linearization,
                                    attempt,
                                    damping,
                                    rmsStep,
                                    maximum,
                                    predicted,
                                    None,
                                    None,
                                    clipped,
                                    accepted = false,
                                    Some(
                                      ProjectedPatchRejection.EarlyObjectiveBound
                                    )
                                  )
                                damping = increasedDamping(damping)
                              case Right(
                                    ProjectedPatchTrialData.Complete(candidateData)
                                  ) =>
                                if !candidateData.isFinite then
                                  return exceptionalFailure(
                                    ProjectedPatchOptimizerError
                                      .InvalidQuadratic(
                                        ProjectedPatchStage.TrialDataObjective,
                                        "objective",
                                        0,
                                        candidateData
                                      )
                                  )
                                val candidateObjective =
                                  candidateData + candidatePrior
                                val actual = currentObjective - candidateObjective
                                val ratio = actual / predicted
                                if candidateObjective <= acceptanceLimit then
                                  accepted = true
                                  acceptedSteps += 1
                                  attemptLog +=
                                    ProjectedPatchAttemptDiagnostics(
                                      linearization,
                                      attempt,
                                      damping,
                                      rmsStep,
                                      maximum,
                                      predicted,
                                      Some(actual),
                                      Some(ratio),
                                      clipped,
                                      accepted = true,
                                      None
                                    )
                                  state = proposal.state
                                  currentObjective = candidateObjective
                                  damping =
                                    if ratio >= config.highGainRatio then
                                      math.max(
                                        config.minimumDamping,
                                        damping * config.acceptedDampingFactor
                                      )
                                    else damping
                                  if
                                    acceptedSteps % config.checkpointInterval == 0
                                  then
                                    selectionEvaluations += 1
                                    checkpoint(state, acceptedSteps) match
                                      case Left(error) =>
                                        return exceptionalFailure(error)
                                      case Right(candidateCheckpoint) =>
                                        if
                                          candidateCheckpoint.selectionObjective <
                                            bestCheckpoint.selectionObjective
                                        then bestCheckpoint = candidateCheckpoint
                                  val converged =
                                    if actual <= config.objectiveTolerance then
                                      Some(
                                        ProjectedPatchTermination
                                          .ObjectiveConverged
                                      )
                                    else if
                                      rmsStep <= config.stepToleranceRms &&
                                        !severeDamping
                                    then
                                      Some(
                                        ProjectedPatchTermination.StepConverged
                                      )
                                    else None
                                  converged match
                                    case Some(termination) =>
                                      if
                                        acceptedSteps %
                                          config.checkpointInterval != 0
                                      then
                                        selectionEvaluations += 1
                                        checkpoint(state, acceptedSteps) match
                                          case Left(error) =>
                                            return exceptionalFailure(error)
                                          case Right(candidateCheckpoint) =>
                                            if
                                              candidateCheckpoint.selectionObjective <
                                                bestCheckpoint.selectionObjective
                                            then bestCheckpoint = candidateCheckpoint
                                      return Right(
                                        finish(
                                          bestCheckpoint,
                                          state,
                                          initialObjective,
                                          currentObjective,
                                          termination,
                                          dataLinearizations,
                                          priorLinearizations,
                                          solverCalls,
                                          trialEvaluations,
                                          earlyRejectedTrials,
                                          acceptedSteps,
                                          rejectedSteps,
                                          clippedSteps,
                                          selectionEvaluations,
                                          attemptLog.result()
                                        )
                                      )
                                    case None => ()
                                else
                                  rejectedSteps += 1
                                  attemptLog +=
                                    ProjectedPatchAttemptDiagnostics(
                                      linearization,
                                      attempt,
                                      damping,
                                      rmsStep,
                                      maximum,
                                      predicted,
                                      Some(actual),
                                      Some(ratio),
                                      clipped,
                                      accepted = false,
                                      Some(
                                        ProjectedPatchRejection
                                          .InsufficientActualReduction
                                      )
                                    )
                                  damping = increasedDamping(damping)
            attempt += 1

          if !accepted then
            val termination =
              lastSolverFailure match
                case Some(detail)
                    if trialEvaluations == trialEvaluationsBefore =>
                  ProjectedPatchTermination.SolverFailure(detail)
                case _ => ProjectedPatchTermination.TrialAttemptLimit
            return Right(
              finish(
                bestCheckpoint,
                state,
                initialObjective,
                currentObjective,
                termination,
                dataLinearizations,
                priorLinearizations,
                solverCalls,
                trialEvaluations,
                earlyRejectedTrials,
                acceptedSteps,
                rejectedSteps,
                clippedSteps,
                selectionEvaluations,
                attemptLog.result()
              )
            )

          linearization += 1
          dataLinearizations += 1
          linearize(state, workspace) match
            case Left(error) =>
              if priorLinearizationWasAttempted(error) then
                priorLinearizations += 1
              return exceptionalFailure(error)
            case Right(_) =>
              priorLinearizations += 1
        Right(
          finish(
            bestCheckpoint,
            state,
            initialObjective,
            currentObjective,
            ProjectedPatchTermination.LinearizationLimit,
            dataLinearizations,
            priorLinearizations,
            solverCalls,
            trialEvaluations,
            earlyRejectedTrials,
            acceptedSteps,
            rejectedSteps,
            clippedSteps,
            selectionEvaluations,
            attemptLog.result()
          )
        )

  private def priorLinearizationWasAttempted(
      error: ProjectedPatchOptimizerError
  ): Boolean =
    error match
      case ProjectedPatchOptimizerError.EvaluationFailure(
            ProjectedPatchStage.PriorLinearization,
            _
          ) => true
      case ProjectedPatchOptimizerError.InvalidQuadratic(
            ProjectedPatchStage.PriorLinearization,
            _,
            _,
            _
          ) => true
      case _ => false

  private def linearize(
      state: State,
      workspace: ProjectedPatchOptimizerWorkspace
  ): Either[ProjectedPatchOptimizerError, Unit] =
    workspace.data.clear()
    workspace.prior.clear()
    for
      _ <- safeEvaluation(ProjectedPatchStage.DataLinearization)(
        dataProblem.linearize(state, workspace.data)
      )
      _ <- validateQuadratic(
        ProjectedPatchStage.DataLinearization,
        workspace.data
      )
      _ <- safeEvaluation(ProjectedPatchStage.PriorLinearization)(
        priorModel.linearize(state, workspace.prior)
      )
      _ <- validateQuadratic(
        ProjectedPatchStage.PriorLinearization,
        workspace.prior
      )
    yield ()

  private def combine(workspace: ProjectedPatchOptimizerWorkspace): Unit =
    var parameter = 0
    while parameter < config.parameterCount do
      workspace.combinedGradient(parameter) =
        workspace.data.gradient(parameter) + workspace.prior.gradient(parameter)
      workspace.rightHandSide(parameter) =
        -workspace.combinedGradient(parameter)
      parameter += 1
    var packed = 0
    while packed < workspace.combinedCurvature.length do
      workspace.combinedCurvature(packed) =
        workspace.data.curvatureUpper(packed) +
          workspace.prior.curvatureUpper(packed)
      packed += 1

  private def checkpoint(
      state: State,
      acceptedSteps: Int
  ): Either[
    ProjectedPatchOptimizerError,
    ProjectedPatchCheckpoint[State]
  ] =
    for
      selection <- safeEvaluation(ProjectedPatchStage.SelectionObjective)(
        dataProblem.selectionObjective(state)
      )
      _ <-
        if selection.objectiveId == dataProblem.selectionObjectiveId then
          Right(())
        else
          Left(
            ProjectedPatchOptimizerError.SelectionObjectiveMismatch(
              dataProblem.selectionObjectiveId,
              selection.objectiveId
            )
          )
      _ <- validateFiniteObjective(
        ProjectedPatchStage.SelectionObjective,
        selection.dataObjective
      )
      prior <- safeEvaluation(ProjectedPatchStage.PriorValue)(
        priorModel.value(state)
      )
      _ <- validateFiniteObjective(ProjectedPatchStage.PriorValue, prior)
    yield
      ProjectedPatchCheckpoint(
        state,
        selection.dataObjective + prior,
        acceptedSteps,
        selection.objectiveId
      )

  private def safeEvaluation[A](
      stage: ProjectedPatchStage
  )(
      operation: => Either[ProjectedPatchOptimizerError, A]
  ): Either[ProjectedPatchOptimizerError, A] =
    try operation
    catch
      case NonFatal(error) =>
        Left(
          ProjectedPatchOptimizerError.EvaluationFailure(
            stage,
            Option(error.getMessage).getOrElse(error.getClass.getName)
          )
        )

  private def validateQuadratic(
      stage: ProjectedPatchStage,
      buffer: ProjectedPatchQuadraticBuffer
  ): Either[ProjectedPatchOptimizerError, Unit] =
    validateFiniteObjective(stage, buffer.objective).flatMap { _ =>
      validateArray(stage, "gradient", buffer.gradient).flatMap { _ =>
        validateArray(stage, "curvature", buffer.curvatureUpper)
      }
    }

  private def validateFiniteObjective(
      stage: ProjectedPatchStage,
      value: Double
  ): Either[ProjectedPatchOptimizerError, Unit] =
    if value.isFinite then Right(())
    else
      Left(
        ProjectedPatchOptimizerError.InvalidQuadratic(
          stage,
          "objective",
          0,
          value
        )
      )

  private def validateArray(
      stage: ProjectedPatchStage,
      component: String,
      values: Array[Double]
  ): Either[ProjectedPatchOptimizerError, Unit] =
    var index = 0
    while index < values.length do
      if !values(index).isFinite then
        return Left(
          ProjectedPatchOptimizerError.InvalidQuadratic(
            stage,
            component,
            index,
            values(index)
          )
        )
      index += 1
    Right(())

  private def validateRealizedStep(
      requested: Array[Double],
      realized: Array[Double]
  ): Either[ProjectedPatchOptimizerError, Unit] =
    if realized.length != config.parameterCount then
      Left(
        ProjectedPatchOptimizerError.EvaluationFailure(
          ProjectedPatchStage.Proposal,
          s"realized step has ${realized.length} parameters"
        )
      )
    else
      var parameter = 0
      while parameter < requested.length do
        val tolerance =
          config.realizedStepTolerance *
            math.max(1.0, math.abs(requested(parameter)))
        if
          !realized(parameter).isFinite ||
            math.abs(realized(parameter) - requested(parameter)) > tolerance
        then
          return Left(
            ProjectedPatchOptimizerError.ProposalChangedStep(
              parameter,
              requested(parameter),
              realized(parameter)
            )
          )
        parameter += 1
      Right(())

  private def increasedDamping(value: Double): Double =
    math.min(config.maximumDamping, value * config.rejectedDampingFactor)

  private def finish(
      best: ProjectedPatchCheckpoint[State],
      lastValidState: State,
      initialObjective: Double,
      lastObjective: Double,
      termination: ProjectedPatchTermination,
      dataLinearizations: Int,
      priorLinearizations: Int,
      solverCalls: Int,
      trialEvaluations: Int,
      earlyRejectedTrials: Int,
      acceptedSteps: Int,
      rejectedSteps: Int,
      clippedSteps: Int,
      selectionEvaluations: Int,
      attempts: Vector[ProjectedPatchAttemptDiagnostics]
  ): ProjectedPatchOptimizationResult[State] =
    new ProjectedPatchOptimizationResult(
      best.state,
      lastValidState,
      best,
      initialObjective,
      lastObjective,
      termination,
      ProjectedPatchWorkCounters(
        dataLinearizations,
        priorLinearizations,
        solverCalls,
        trialEvaluations,
        earlyRejectedTrials,
        acceptedSteps,
        rejectedSteps,
        clippedSteps,
        selectionEvaluations
      ),
      attempts
    )

  private def euclideanNorm(values: Array[Double]): Double =
    math.sqrt(values.iterator.map(value => value * value).sum)

  private def metricNorm(
      step: Array[Double],
      metric: Array[Double],
      dimension: Int
  ): Double =
    var squared = 0.0
    var row = 0
    while row < dimension do
      var column = 0
      while column < dimension do
        squared +=
          step(row) * metric(PackedSymmetric.index(row, column)) * step(column)
        column += 1
      row += 1
    math.sqrt(math.max(0.0, squared))

  private def predictedReduction(
      rightHandSide: Array[Double],
      curvature: Array[Double],
      step: Array[Double],
      dimension: Int
  ): Double =
    var linear = 0.0
    var quadratic = 0.0
    var row = 0
    while row < dimension do
      linear += rightHandSide(row) * step(row)
      var column = 0
      while column < dimension do
        quadratic +=
          step(row) *
            curvature(PackedSymmetric.index(row, column)) *
            step(column)
        column += 1
      row += 1
    linear - 0.5 * quadratic

  private def scaleInPlace(values: Array[Double], factor: Double): Unit =
    var index = 0
    while index < values.length do
      values(index) *= factor
      index += 1

private[flashalign] object ProjectedPatchOptimizer:
  def compile[State](
      dataProblem: ProjectedPatchDataProblem[State],
      prior: ProjectedPatchPrior[State],
      geometry: ProjectedPatchGeometry[State],
      config: ProjectedPatchOptimizerConfig
  ): Either[ProjectedPatchOptimizerError, ProjectedPatchOptimizer[State]] =
    if dataProblem.parameterCount != config.parameterCount then
      Left(
        ProjectedPatchOptimizerError.InvalidConfiguration(
          s"data problem has ${dataProblem.parameterCount} parameters but configuration has ${config.parameterCount}"
        )
      )
    else Right(new ProjectedPatchOptimizer(dataProblem, prior, geometry, config))

private[flashalign] sealed trait SmallProjectedPatchSolveError derives CanEqual:
  def message: String

private[flashalign] object SmallProjectedPatchSolveError:
  final case class NonFinite(component: String, index: Int, value: Double)
      extends SmallProjectedPatchSolveError:
    val message: String = s"nonfinite $component value $value at $index"

  final case class NotPositiveDefinite(pivot: Int, value: Double)
      extends SmallProjectedPatchSolveError:
    val message: String =
      s"damped system is not positive definite at pivot $pivot: $value"

  final case class IllConditioned(ratio: Double, limit: Double)
      extends SmallProjectedPatchSolveError:
    val message: String = s"damped pivot ratio $ratio exceeds $limit"

private[flashalign] object SmallProjectedPatchSystem:
  def solve(
      curvature: Array[Double],
      metric: Array[Double],
      rightHandSide: Array[Double],
      damping: Double,
      dimension: Int,
      conditionLimit: Double,
      factor: Array[Double],
      forward: Array[Double],
      step: Array[Double]
  ): Either[SmallProjectedPatchSolveError, Double] =
    var row = 0
    while row < dimension do
      var column = 0
      while column < dimension do
        val packed = PackedSymmetric.index(row, column)
        val value = curvature(packed) + damping * metric(packed)
        if !value.isFinite then
          return Left(
            SmallProjectedPatchSolveError.NonFinite(
              "damped curvature",
              row * dimension + column,
              value
            )
          )
        factor(row * dimension + column) = value
        column += 1
      if !rightHandSide(row).isFinite then
        return Left(
          SmallProjectedPatchSolveError.NonFinite(
            "right-hand side",
            row,
            rightHandSide(row)
          )
        )
      row += 1

    var maximumDiagonal = 0.0
    row = 0
    while row < dimension do
      maximumDiagonal = math.max(
        maximumDiagonal,
        math.abs(factor(row * dimension + row))
      )
      row += 1
    val pivotTolerance = math.max(1e-15, maximumDiagonal * 1e-14)
    var minimumPivot = Double.PositiveInfinity
    var maximumPivot = 0.0
    row = 0
    while row < dimension do
      var column = 0
      while column <= row do
        var value = factor(row * dimension + column)
        var inner = 0
        while inner < column do
          value -=
            factor(row * dimension + inner) *
              factor(column * dimension + inner)
          inner += 1
        if !value.isFinite then
          return Left(
            SmallProjectedPatchSolveError.NonFinite(
              "factor",
              row * dimension + column,
              value
            )
          )
        if row == column then
          if value <= pivotTolerance then
            return Left(
              SmallProjectedPatchSolveError.NotPositiveDefinite(row, value)
            )
          minimumPivot = math.min(minimumPivot, value)
          maximumPivot = math.max(maximumPivot, value)
          factor(row * dimension + column) = math.sqrt(value)
        else
          factor(row * dimension + column) =
            value / factor(column * dimension + column)
        column += 1
      row += 1
    val pivotRatio = maximumPivot / minimumPivot
    if pivotRatio > conditionLimit then
      Left(
        SmallProjectedPatchSolveError.IllConditioned(
          pivotRatio,
          conditionLimit
        )
      )
    else
      row = 0
      while row < dimension do
        var value = rightHandSide(row)
        var column = 0
        while column < row do
          value -= factor(row * dimension + column) * forward(column)
          column += 1
        forward(row) = value / factor(row * dimension + row)
        row += 1
      row = dimension - 1
      while row >= 0 do
        var value = forward(row)
        var column = row + 1
        while column < dimension do
          value -= factor(column * dimension + row) * step(column)
          column += 1
        step(row) = value / factor(row * dimension + row)
        if !step(row).isFinite then
          return Left(
            SmallProjectedPatchSolveError.NonFinite(
              "step",
              row,
              step(row)
            )
          )
        row -= 1
      Right(pivotRatio)

  def numericalRank(
      curvature: Array[Double],
      dimension: Int,
      relativeTolerance: Double,
      matrix: Array[Double]
  ): Int =
    var row = 0
    while row < dimension do
      var column = 0
      while column < dimension do
        matrix(row * dimension + column) =
          curvature(PackedSymmetric.index(row, column))
        column += 1
      row += 1
    val maximumSweeps = 64 * dimension * dimension
    var sweep = 0
    var converged = false
    while sweep < maximumSweeps && !converged do
      var p = 0
      var q = 0
      var maximumOffDiagonal = 0.0
      row = 0
      while row < dimension do
        var column = row + 1
        while column < dimension do
          val value = math.abs(matrix(row * dimension + column))
          if value > maximumOffDiagonal then
            maximumOffDiagonal = value
            p = row
            q = column
          column += 1
        row += 1
      var diagonalScale = 0.0
      row = 0
      while row < dimension do
        diagonalScale = math.max(
          diagonalScale,
          math.abs(matrix(row * dimension + row))
        )
        row += 1
      if maximumOffDiagonal <= relativeTolerance * math.max(1.0, diagonalScale)
      then converged = true
      else
        val app = matrix(p * dimension + p)
        val aqq = matrix(q * dimension + q)
        val apq = matrix(p * dimension + q)
        val angle = 0.5 * math.atan2(2.0 * apq, aqq - app)
        val cosine = math.cos(angle)
        val sine = math.sin(angle)
        var index = 0
        while index < dimension do
          if index != p && index != q then
            val aip = matrix(index * dimension + p)
            val aiq = matrix(index * dimension + q)
            val updatedP = cosine * aip - sine * aiq
            val updatedQ = sine * aip + cosine * aiq
            matrix(index * dimension + p) = updatedP
            matrix(p * dimension + index) = updatedP
            matrix(index * dimension + q) = updatedQ
            matrix(q * dimension + index) = updatedQ
          index += 1
        matrix(p * dimension + p) =
          cosine * cosine * app - 2.0 * sine * cosine * apq +
            sine * sine * aqq
        matrix(q * dimension + q) =
          sine * sine * app + 2.0 * sine * cosine * apq +
            cosine * cosine * aqq
        matrix(p * dimension + q) = 0.0
        matrix(q * dimension + p) = 0.0
      sweep += 1
    var maximumEigenvalue = 0.0
    row = 0
    while row < dimension do
      maximumEigenvalue = math.max(
        maximumEigenvalue,
        math.abs(matrix(row * dimension + row))
      )
      row += 1
    val threshold = relativeTolerance * maximumEigenvalue
    var rank = 0
    row = 0
    while row < dimension do
      if matrix(row * dimension + row) > threshold then rank += 1
      row += 1
    rank
