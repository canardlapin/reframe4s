package reframe4s.flashalign

import image4s.SampleSpace
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point
import ravel.DType.given
import ravel.NDArray
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3
import reframe4s.multiscale.GridTower
import reframe4s.multiscale.IsotropicGaussianPsf3
import reframe4s.multiscale.ScaleLevel
import reframe4s.multiscale.ScaleSchedule
import reframe4s.multiscale.ScaleSpec
import reframe4s.multiscale.SupportAwarePyramid3
import reframe4s.multiscale.SupportAwarePyramidConfig3
import reframe4s.multiscale.SupportAwarePyramidLevel3
import reframe4s.multiscale.SupportAwarePyramidWorkspace3
import reframe4s.register.OptimizationReport
import reframe4s.register.Termination
import reframe4s.resample.LinearValueGradientSampler3
import scala.util.control.NonFatal

private[flashalign] enum LinearEngineStage derives CanEqual:
  case Configuration
  case Preparation
  case Sampling
  case Geometry
  case Objective
  case Optimizer
  case Audit
  case Report

private[flashalign] sealed trait LinearEngineError derives CanEqual:
  def message: String
  def evidence: Option[LinearEngineFailureEvidence] = None

private[flashalign] object LinearEngineError:
  final case class Failed(stage: LinearEngineStage, detail: String)
      extends LinearEngineError:
    val message: String = s"$stage failed: $detail"

  case object WorkspacePlanMismatch extends LinearEngineError:
    val message: String = "linear engine workspace belongs to another plan"

  final case class FitRejected(
      termination: ProjectedPatchTermination,
      failureEvidence: LinearEngineFailureEvidence
  )
      extends LinearEngineError:
    val message: String = s"linear fit rejected: $termination"
    override val evidence: Option[LinearEngineFailureEvidence] =
      Some(failureEvidence)

  final case class InsufficientAuditOverlap(
      observed: Double,
      required: Double,
      failureEvidence: LinearEngineFailureEvidence
  )
      extends LinearEngineError:
    val message: String =
      s"audit overlap $observed is below the required fraction $required"
    override val evidence: Option[LinearEngineFailureEvidence] =
      Some(failureEvidence)

  final case class FinalAuditFailed(
      detail: String,
      failureEvidence: LinearEngineFailureEvidence
  ) extends LinearEngineError:
    val message: String = s"final audit failed: $detail"
    override val evidence: Option[LinearEngineFailureEvidence] =
      Some(failureEvidence)

  final case class PyramidStageFailed(
      cause: LinearEngineError,
      failureEvidence: LinearEngineFailureEvidence
  ) extends LinearEngineError:
    val message: String = cause.message
    override val evidence: Option[LinearEngineFailureEvidence] =
      Some(failureEvidence)

  final case class UncheckpointedEvaluationFailure(
      detail: String,
      work: FlashalignWorkCounts
  ) extends LinearEngineError:
    val message: String = s"optimizer evaluation failed before checkpoint: $detail"

private[flashalign] final case class LinearEngineFailureEvidence(
    work: FlashalignWorkCounts,
    levels: Vector[FlashalignLinearLevelDiagnostics],
    lastCheckpoint: FlashalignLinearCheckpointDiagnostics,
    optimizationPatchEntries: Int,
    selectionPatchEntries: Int
)

private[flashalign] final case class LinearEngineOutcome[State](
    state: State,
    report: OptimizationReport,
    optimizationPatchEntries: Int,
    selectionPatchEntries: Int,
    auditPatchEntries: Int,
    auditOverlapFraction: Double,
    work: FlashalignWorkCounts,
    levels: Vector[FlashalignLinearLevelDiagnostics]
)

private[flashalign] final case class LinearRefinementOutcome[State](
    state: State,
    report: OptimizationReport,
    optimizationPatchEntries: Int,
    selectionPatchEntries: Int,
    work: FlashalignWorkCounts,
    levels: Vector[FlashalignLinearLevelDiagnostics],
    lastCheckpoint: FlashalignLinearCheckpointDiagnostics
)

private[flashalign] final case class LinearAuditOutcome(
    patchEntries: Int,
    overlapFraction: Double,
    dataWork: LinearDataWorkCounts
)

private[flashalign] final case class LinearAuditFailure(
    detail: String,
    work: FlashalignWorkCounts
)

private[flashalign] final class PreparedLinearPyramids3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    val moving: SupportAwarePyramid3[Moving, String],
    val fixed: SupportAwarePyramid3[Fixed, String]
)

private[flashalign] trait RigidLinearEngine3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
]:
  def newWorkspace(): Either[LinearEngineError, AnyRef]

  def refine(
      initial: Rigid3[Moving, Fixed],
      workspace: AnyRef
  ): Either[LinearEngineError, LinearRefinementOutcome[Rigid3[Moving, Fixed]]]

  def admit(
      refined: LinearRefinementOutcome[Rigid3[Moving, Fixed]],
      accumulatedWork: FlashalignWorkCounts
  ): Either[LinearEngineError, LinearEngineOutcome[Rigid3[Moving, Fixed]]]

  final def fit(
      initial: Rigid3[Moving, Fixed],
      workspace: AnyRef
  ): Either[LinearEngineError, LinearEngineOutcome[Rigid3[Moving, Fixed]]] =
    refine(initial, workspace).flatMap(refined =>
      admit(refined, refined.work)
    )

private[flashalign] trait AffineLinearEngine3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
]:
  def newWorkspace(): Either[LinearEngineError, AnyRef]

  def refine(
      initial: FramedAffine[Moving, Fixed, D3],
      workspace: AnyRef
  ): Either[
    LinearEngineError,
    LinearRefinementOutcome[FramedAffine[Moving, Fixed, D3]]
  ]

  def admit(
      refined: LinearRefinementOutcome[FramedAffine[Moving, Fixed, D3]],
      accumulatedWork: FlashalignWorkCounts
  ): Either[
    LinearEngineError,
    LinearEngineOutcome[FramedAffine[Moving, Fixed, D3]]
  ]

  final def fit(
      initial: FramedAffine[Moving, Fixed, D3],
      workspace: AnyRef
  ): Either[
    LinearEngineError,
    LinearEngineOutcome[FramedAffine[Moving, Fixed, D3]]
  ] =
    refine(initial, workspace).flatMap(refined =>
      admit(refined, refined.work)
    )

private[flashalign] object LinearEngine3:
  private final class OwnedWorkspace(
      val owner: AnyRef,
      val payload: AnyRef
  )

  private final case class RigidPyramidLevels[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      engines: Vector[RigidLinearEngine3[Moving, Fixed]],
      resolutions: Vector[Double]
  )

  private final case class AffinePyramidLevels[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      engines: Vector[AffineLinearEngine3[Moving, Fixed]],
      resolutions: Vector[Double]
  )

  def compileRigid[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      policy: LinearPresetPolicy
  )(using Dimension[D3]): Either[
    LinearEngineError,
    RigidLinearEngine3[Moving, Fixed]
  ] =
    preparePyramids(moving, fixed, policy).flatMap(prepared =>
      compileRigidPrepared(prepared, policy)
    )

  def preparePyramids[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      policy: LinearPresetPolicy
  )(using Dimension[D3]): Either[
    LinearEngineError,
    PreparedLinearPyramids3[Moving, Fixed]
  ] =
    for
      movingPyramid <- preparePyramid(moving, policy)
      fixedPyramid <- preparePyramid(fixed, policy)
    yield new PreparedLinearPyramids3(movingPyramid, fixedPyramid)

  def compileRigidPrepared[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      prepared: PreparedLinearPyramids3[Moving, Fixed],
      policy: LinearPresetPolicy
  ): Either[
    LinearEngineError,
    RigidLinearEngine3[Moving, Fixed]
  ] =
    for
      objectiveConfig <- patchConfig(policy)
      engines <- compileRigidLevels(
        prepared.moving.levels,
        prepared.fixed.levels,
        policy,
        objectiveConfig
      )
    yield new RigidPyramidEngine(
      engines.engines,
      engines.resolutions
    )

  def compileAffine[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      policy: LinearPresetPolicy
  )(using Dimension[D3]): Either[
    LinearEngineError,
    AffineLinearEngine3[Moving, Fixed]
  ] =
    preparePyramids(moving, fixed, policy).flatMap(prepared =>
      compileAffinePrepared(prepared, policy)
    )

  def compileAffinePrepared[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      prepared: PreparedLinearPyramids3[Moving, Fixed],
      policy: LinearPresetPolicy
  ): Either[
    LinearEngineError,
    AffineLinearEngine3[Moving, Fixed]
  ] =
    for
      objectiveConfig <- patchConfig(policy)
      engines <- compileAffineLevels(
        prepared.moving.levels,
        prepared.fixed.levels,
        policy,
        objectiveConfig
      )
    yield new AffinePyramidEngine(
      engines.engines,
      engines.resolutions
    )

  private def compileRigidLevels[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      movingLevels: Vector[SupportAwarePyramidLevel3[Moving, String]],
      fixedLevels: Vector[SupportAwarePyramidLevel3[Fixed, String]],
      policy: LinearPresetPolicy,
      objectiveConfig: PatchObjectiveConfig
  ): Either[
    LinearEngineError,
    RigidPyramidLevels[Moving, Fixed]
  ] =
    val engines = Vector.newBuilder[RigidLinearEngine3[Moving, Fixed]]
    val resolutions = Vector.newBuilder[Double]
    var index = 0
    while index < movingLevels.size do
      compileRigidLevel(
        movingLevels(index),
        fixedLevels(index),
        policy,
        objectiveConfig,
        policy.preparation.stencilSpacingMillimetres(index)
      ) match
        case Left(error) if index < movingLevels.size - 1 && skippableLevel(error) =>
          ()
        case Left(error) => return Left(error)
        case Right(value) =>
          engines += value
          resolutions += policy.preparation.effectiveResolutionMillimetres(index)
      index += 1
    Right(RigidPyramidLevels(engines.result(), resolutions.result()))

  private def compileAffineLevels[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      movingLevels: Vector[SupportAwarePyramidLevel3[Moving, String]],
      fixedLevels: Vector[SupportAwarePyramidLevel3[Fixed, String]],
      policy: LinearPresetPolicy,
      objectiveConfig: PatchObjectiveConfig
  ): Either[
    LinearEngineError,
    AffinePyramidLevels[Moving, Fixed]
  ] =
    val engines = Vector.newBuilder[AffineLinearEngine3[Moving, Fixed]]
    val resolutions = Vector.newBuilder[Double]
    var index = 0
    while index < movingLevels.size do
      compileAffineLevel(
        movingLevels(index),
        fixedLevels(index),
        policy,
        objectiveConfig,
        policy.preparation.stencilSpacingMillimetres(index)
      ) match
        case Left(error) if index < movingLevels.size - 1 && skippableLevel(error) =>
          ()
        case Left(error) => return Left(error)
        case Right(value) =>
          engines += value
          resolutions += policy.preparation.effectiveResolutionMillimetres(index)
      index += 1
    Right(AffinePyramidLevels(engines.result(), resolutions.result()))

  private def skippableLevel(error: LinearEngineError): Boolean =
    error match
      case LinearEngineError.Failed(LinearEngineStage.Sampling, detail) =>
        detail.startsWith("role partition requires at least") ||
          detail.endsWith("role has no eligible patches")
      case _ => false

  private def compileRigidLevel[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      movingLevel: SupportAwarePyramidLevel3[Moving, String],
      fixedLevel: SupportAwarePyramidLevel3[Fixed, String],
      policy: LinearPresetPolicy,
      objectiveConfig: PatchObjectiveConfig,
      stencilSpacingMillimetres: Double
  ): Either[LinearEngineError, RigidLinearEngine3[Moving, Fixed]] =
    for
      population <- preparePopulation(
        movingLevel,
        objectiveConfig,
        policy,
        stencilSpacingMillimetres
      )
      samplePlan <- samplePlan(population, policy)
      optimizationSamples <- samplePlan
        .refreshOptimization(0)
        .left
        .map(error => failed(LinearEngineStage.Sampling, error.message))
      fixedSampler <- LinearValueGradientSampler3
        .compile(fixedLevel.image)
        .left
        .map(error => failed(LinearEngineStage.Preparation, error.message))
      engine <- compileRigidWithSampler(
        movingLevel.image,
        fixedLevel.image,
        policy,
        objectiveConfig,
        samplePlan,
        optimizationSamples,
        fixedSampler
      )
    yield engine

  private def compileAffineLevel[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      movingLevel: SupportAwarePyramidLevel3[Moving, String],
      fixedLevel: SupportAwarePyramidLevel3[Fixed, String],
      policy: LinearPresetPolicy,
      objectiveConfig: PatchObjectiveConfig,
      stencilSpacingMillimetres: Double
  ): Either[LinearEngineError, AffineLinearEngine3[Moving, Fixed]] =
    for
      population <- preparePopulation(
        movingLevel,
        objectiveConfig,
        policy,
        stencilSpacingMillimetres
      )
      samplePlan <- samplePlan(population, policy)
      optimizationSamples <- samplePlan
        .refreshOptimization(0)
        .left
        .map(error => failed(LinearEngineStage.Sampling, error.message))
      fixedSampler <- LinearValueGradientSampler3
        .compile(fixedLevel.image)
        .left
        .map(error => failed(LinearEngineStage.Preparation, error.message))
      engine <- compileAffineWithSampler(
        movingLevel.image,
        fixedLevel.image,
        policy,
        objectiveConfig,
        samplePlan,
        optimizationSamples,
        fixedSampler
      )
    yield engine

  private def compileRigidWithSampler[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      S <: SampleSpace[Fixed, D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      policy: LinearPresetPolicy,
      objectiveConfig: PatchObjectiveConfig,
      samples: PatchSamplePlan3[Moving, String],
      optimizationSamples: PatchSampleSet3,
      fixedSampler: LinearValueGradientSampler3[Fixed, S]
  ): Either[LinearEngineError, RigidLinearEngine3[Moving, Fixed]] =
    val selectionSamples = samples.selection
    for
      pivot <- fixedPivot(fixed)
      model <- RigidModel3
        .atFixedWorld(moving.frame, fixed.frame)(pivot(0), pivot(1), pivot(2))
        .left
        .map(error => failed(LinearEngineStage.Geometry, error.message))
      optimization <- CompiledRigidObjective3
        .compile(
          model,
          FrozenPatchObjective3.create(optimizationSamples, objectiveConfig),
          samples.population.stencil,
          fixedSampler
        )
        .left
        .map(error => failed(LinearEngineStage.Objective, error.message))
      selection <- CompiledRigidObjective3
        .compile(
          model,
          FrozenPatchObjective3.create(selectionSamples, objectiveConfig),
          samples.population.stencil,
          fixedSampler
        )
        .left
        .map(error => failed(LinearEngineStage.Objective, error.message))
      probes <- rigidProbes(fixed)
      optimizer <- optimizerConfig(6, policy.rigidTrust)
      refinement <- RigidRefinementPlan3
        .compile(optimization, selection, probes, optimizer)
        .left
        .map(error => failed(LinearEngineStage.Optimizer, error.message))
    yield new RigidEngineImpl(
      policy,
      objectiveConfig,
      samples,
      fixedSampler,
      model,
      refinement,
      optimizationSamples.entries.size,
      selectionSamples.entries.size
    )

  private def compileAffineWithSampler[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      S <: SampleSpace[Fixed, D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      policy: LinearPresetPolicy,
      objectiveConfig: PatchObjectiveConfig,
      samples: PatchSamplePlan3[Moving, String],
      optimizationSamples: PatchSampleSet3,
      fixedSampler: LinearValueGradientSampler3[Fixed, S]
  ): Either[LinearEngineError, AffineLinearEngine3[Moving, Fixed]] =
    val selectionSamples = samples.selection
    for
      pivot <- fixedPivot(fixed)
      modelConfig <- AffineModelConfig
        .create()
        .left
        .map(error => failed(LinearEngineStage.Configuration, error.message))
      model <- AffineModel3
        .atFixedWorld(moving.frame, fixed.frame, modelConfig)(pivot(0), pivot(1), pivot(2))
        .left
        .map(error => failed(LinearEngineStage.Geometry, error.message))
      optimization <- CompiledAffineObjective3
        .compile(
          model,
          FrozenPatchObjective3.create(optimizationSamples, objectiveConfig),
          samples.population.stencil,
          fixedSampler
        )
        .left
        .map(error => failed(LinearEngineStage.Objective, error.message))
      selection <- CompiledAffineObjective3
        .compile(
          model,
          FrozenPatchObjective3.create(selectionSamples, objectiveConfig),
          samples.population.stencil,
          fixedSampler
        )
        .left
        .map(error => failed(LinearEngineStage.Objective, error.message))
      probes <- affineProbes(fixed)
      optimizer <- optimizerConfig(12, policy.affineTrust)
    yield new AffineEngineImpl(
      policy,
      objectiveConfig,
      samples,
      fixedSampler,
      model,
      optimization,
      selection,
      probes,
      optimizer,
      optimizationSamples.entries.size,
      selectionSamples.entries.size
    )

  private final class RigidPyramidEngine[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      engines: Vector[RigidLinearEngine3[Moving, Fixed]],
      resolutions: Vector[Double]
  ) extends RigidLinearEngine3[Moving, Fixed]:
    def newWorkspace(): Either[LinearEngineError, AnyRef] =
      workspaces(engines.map(_.newWorkspace())).map(values =>
        new OwnedWorkspace(this, values)
      )

    def refine(
        initial: Rigid3[Moving, Fixed],
        workspace: AnyRef
    ): Either[LinearEngineError, LinearRefinementOutcome[Rigid3[Moving, Fixed]]] =
      workspace match
        case owned: OwnedWorkspace if owned.owner eq this =>
          val levelWorkspaces = owned.payload.asInstanceOf[Vector[AnyRef]]
          var state = initial
          var totalWork = FlashalignWorkCounts.Zero
          var finalOutcome = Option.empty[
            LinearRefinementOutcome[Rigid3[Moving, Fixed]]
          ]
          val diagnostics = Vector.newBuilder[FlashalignLinearLevelDiagnostics]
          var index = 0
          while index < engines.size do
            engines(index).refine(state, levelWorkspaces(index)) match
              case Left(error) =>
                return Left(
                  withPyramidFailureEvidence(
                    error,
                    totalWork,
                    diagnostics.result(),
                    finalOutcome.map(_.lastCheckpoint),
                    index,
                    resolutions(index)
                  )
                )
              case Right(outcome) =>
                state = outcome.state
                totalWork = totalWork + outcome.work
                finalOutcome = Some(outcome)
                diagnostics += FlashalignLinearLevelDiagnostics(
                  index,
                  resolutions(index),
                  outcome.optimizationPatchEntries,
                  outcome.selectionPatchEntries,
                  outcome.report.finalObjective,
                  outcome.work
                )
            index += 1
          finalOutcome
            .toRight(failed(LinearEngineStage.Configuration, "empty rigid pyramid"))
            .map(outcome =>
              outcome.copy(work = totalWork, levels = diagnostics.result())
            )
        case _ => Left(LinearEngineError.WorkspacePlanMismatch)

    def admit(
        refined: LinearRefinementOutcome[Rigid3[Moving, Fixed]],
        accumulatedWork: FlashalignWorkCounts
    ): Either[LinearEngineError, LinearEngineOutcome[Rigid3[Moving, Fixed]]] =
      engines.last.admit(refined, accumulatedWork)

  private final class AffinePyramidEngine[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      engines: Vector[AffineLinearEngine3[Moving, Fixed]],
      resolutions: Vector[Double]
  ) extends AffineLinearEngine3[Moving, Fixed]:
    def newWorkspace(): Either[LinearEngineError, AnyRef] =
      workspaces(engines.map(_.newWorkspace())).map(values =>
        new OwnedWorkspace(this, values)
      )

    def refine(
        initial: FramedAffine[Moving, Fixed, D3],
        workspace: AnyRef
    ): Either[
      LinearEngineError,
      LinearRefinementOutcome[FramedAffine[Moving, Fixed, D3]]
    ] =
      workspace match
        case owned: OwnedWorkspace if owned.owner eq this =>
          val levelWorkspaces = owned.payload.asInstanceOf[Vector[AnyRef]]
          var state = initial
          var totalWork = FlashalignWorkCounts.Zero
          var finalOutcome = Option.empty[
            LinearRefinementOutcome[FramedAffine[Moving, Fixed, D3]]
          ]
          val diagnostics = Vector.newBuilder[FlashalignLinearLevelDiagnostics]
          var index = 0
          while index < engines.size do
            engines(index).refine(state, levelWorkspaces(index)) match
              case Left(error) =>
                return Left(
                  withPyramidFailureEvidence(
                    error,
                    totalWork,
                    diagnostics.result(),
                    finalOutcome.map(_.lastCheckpoint),
                    index,
                    resolutions(index)
                  )
                )
              case Right(outcome) =>
                state = outcome.state
                totalWork = totalWork + outcome.work
                finalOutcome = Some(outcome)
                diagnostics += FlashalignLinearLevelDiagnostics(
                  index,
                  resolutions(index),
                  outcome.optimizationPatchEntries,
                  outcome.selectionPatchEntries,
                  outcome.report.finalObjective,
                  outcome.work
                )
            index += 1
          finalOutcome
            .toRight(failed(LinearEngineStage.Configuration, "empty affine pyramid"))
            .map(outcome =>
              outcome.copy(work = totalWork, levels = diagnostics.result())
            )
        case _ => Left(LinearEngineError.WorkspacePlanMismatch)

    def admit(
        refined: LinearRefinementOutcome[FramedAffine[Moving, Fixed, D3]],
        accumulatedWork: FlashalignWorkCounts
    ): Either[
      LinearEngineError,
      LinearEngineOutcome[FramedAffine[Moving, Fixed, D3]]
    ] =
      engines.last.admit(refined, accumulatedWork)

  private def workspaces(
      values: Vector[Either[LinearEngineError, AnyRef]]
  ): Either[LinearEngineError, Vector[AnyRef]] =
    val result = Vector.newBuilder[AnyRef]
    var index = 0
    while index < values.size do
      values(index) match
        case Left(error)  => return Left(error)
        case Right(value) => result += value
      index += 1
    Right(result.result())

  private final class RigidEngineImpl[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      S <: SampleSpace[Fixed, D3]
  ](
      policy: LinearPresetPolicy,
      objectiveConfig: PatchObjectiveConfig,
      samples: PatchSamplePlan3[Moving, String],
      fixedSampler: LinearValueGradientSampler3[Fixed, S],
      model: RigidModel3[Moving, Fixed],
      refinement: RigidRefinementPlan3[Moving, Fixed, S, S],
      optimizationEntries: Int,
      selectionEntries: Int
  ) extends RigidLinearEngine3[Moving, Fixed]:
    def newWorkspace(): Either[LinearEngineError, AnyRef] =
      refinement
        .newWorkspace()
        .left
        .map(error => failed(LinearEngineStage.Optimizer, error.message))
        .map(workspace => new OwnedWorkspace(this, workspace))

    def refine(
        initial: Rigid3[Moving, Fixed],
        workspace: AnyRef
    ): Either[LinearEngineError, LinearRefinementOutcome[Rigid3[Moving, Fixed]]] =
      workspace match
        case owned: OwnedWorkspace if owned.owner eq this =>
          val refinementWorkspace =
            owned.payload.asInstanceOf[RigidRefinementWorkspace3[Moving, Fixed]]
          for
            optimized <- refinement
              .optimize(initial, refinementWorkspace)
              .left
              .map(error =>
                LinearEngineError.UncheckpointedEvaluationFailure(
                  error.message,
                  FlashalignWorkCounts.linear(
                    refinementWorkspace.dataWorkSnapshot,
                    refinementWorkspace.optimizerFailureCountersSnapshot,
                    auditEvaluations = 0
                  )
                )
              )
            optimizationDataWork = refinementWorkspace.dataWorkSnapshot
            work = FlashalignWorkCounts.linear(
              optimizationDataWork,
              optimized.counters,
              auditEvaluations = 0
            )
            report <- reportOf(optimized)
            evidence = LinearEngineFailureEvidence(
              work,
              Vector.empty,
              FlashalignLinearCheckpointDiagnostics(
                optimized.bestCheckpoint.state.operator.rowMajor,
                optimized.lastValidState.operator.rowMajor,
                optimized.bestCheckpoint.selectionObjective,
                optimized.bestCheckpoint.acceptedSteps,
                optimized.bestCheckpoint.selectionObjectiveId,
                optimized.initialOptimizationObjective,
                optimized.lastOptimizationObjective
              ),
              optimizationEntries,
              selectionEntries
            )
            _ <- requireConverged(optimized.termination, evidence)
          yield LinearRefinementOutcome(
            optimized.state,
            report,
            optimizationEntries,
            selectionEntries,
            work,
            Vector.empty,
            evidence.lastCheckpoint
          )
        case _ => Left(LinearEngineError.WorkspacePlanMismatch)

    def admit(
        refined: LinearRefinementOutcome[Rigid3[Moving, Fixed]],
        accumulatedWork: FlashalignWorkCounts
    ): Either[LinearEngineError, LinearEngineOutcome[Rigid3[Moving, Fixed]]] =
      val baseEvidence = LinearEngineFailureEvidence(
        accumulatedWork,
        refined.levels,
        refined.lastCheckpoint,
        refined.optimizationPatchEntries,
        refined.selectionPatchEntries
      )
      auditRigid(
        refined.state,
        samples,
        objectiveConfig,
        fixedSampler,
        model
      ) match
        case Left(failure) =>
          Left(
            LinearEngineError.FinalAuditFailed(
              failure.detail,
              baseEvidence.copy(work = accumulatedWork + failure.work)
            )
          )
        case Right(audit) =>
          val completeWork =
            accumulatedWork + FlashalignWorkCounts.audit(audit.dataWork)
          val failureEvidence = baseEvidence.copy(work = completeWork)
          requireAuditOverlap(
            audit.overlapFraction,
            policy.qc.minimumOverlapFraction,
            failureEvidence
          ).map(_ =>
            LinearEngineOutcome(
              refined.state,
              refined.report,
              refined.optimizationPatchEntries,
              refined.selectionPatchEntries,
              audit.patchEntries,
              audit.overlapFraction,
              completeWork,
              refined.levels
            )
          )

  private final class AffineEngineImpl[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      S <: SampleSpace[Fixed, D3]
  ](
      policy: LinearPresetPolicy,
      objectiveConfig: PatchObjectiveConfig,
      samples: PatchSamplePlan3[Moving, String],
      fixedSampler: LinearValueGradientSampler3[Fixed, S],
      model: AffineModel3[Moving, Fixed],
      optimization: CompiledAffineObjective3[Moving, Fixed, S],
      selection: CompiledAffineObjective3[Moving, Fixed, S],
      probes: AffineFixedProbeSet3[Fixed],
      optimizerConfig: ProjectedPatchOptimizerConfig,
      optimizationEntries: Int,
      selectionEntries: Int
  ) extends AffineLinearEngine3[Moving, Fixed]:
    private final class Marker

    def newWorkspace(): Either[LinearEngineError, AnyRef] =
      Right(new OwnedWorkspace(this, new Marker))

    def refine(
        initial: FramedAffine[Moving, Fixed, D3],
        workspace: AnyRef
    ): Either[
      LinearEngineError,
      LinearRefinementOutcome[FramedAffine[Moving, Fixed, D3]]
    ] =
      workspace match
        case owned: OwnedWorkspace if owned.owner eq this =>
          for
            prior <- AffineStrainPrior3
              .create(model, initial, weight = 1e-5)
              .left
              .map(error => failed(LinearEngineStage.Geometry, error.message))
            refinement <- AffineRefinementPlan3
              .compile(optimization, selection, prior, probes, optimizerConfig)
              .left
              .map(error => failed(LinearEngineStage.Optimizer, error.message))
            refinementWorkspace <- refinement
              .newWorkspace()
              .left
              .map(error => failed(LinearEngineStage.Optimizer, error.message))
            optimized <- refinement
              .optimize(initial, refinementWorkspace)
              .left
              .map(error =>
                LinearEngineError.UncheckpointedEvaluationFailure(
                  error.message,
                  FlashalignWorkCounts.linear(
                    refinementWorkspace.dataWorkSnapshot,
                    refinementWorkspace.optimizerFailureCountersSnapshot,
                    auditEvaluations = 0
                  )
                )
              )
            optimizationDataWork = refinementWorkspace.dataWorkSnapshot
            work = FlashalignWorkCounts.linear(
              optimizationDataWork,
              optimized.counters,
              auditEvaluations = 0
            )
            report <- reportOf(optimized)
            evidence = LinearEngineFailureEvidence(
              work,
              Vector.empty,
              FlashalignLinearCheckpointDiagnostics(
                optimized.bestCheckpoint.state.operator.rowMajor,
                optimized.lastValidState.operator.rowMajor,
                optimized.bestCheckpoint.selectionObjective,
                optimized.bestCheckpoint.acceptedSteps,
                optimized.bestCheckpoint.selectionObjectiveId,
                optimized.initialOptimizationObjective,
                optimized.lastOptimizationObjective
              ),
              optimizationEntries,
              selectionEntries
            )
            _ <- requireConverged(optimized.termination, evidence)
          yield LinearRefinementOutcome(
            optimized.state,
            report,
            optimizationEntries,
            selectionEntries,
            work,
            Vector.empty,
            evidence.lastCheckpoint
          )
        case _ => Left(LinearEngineError.WorkspacePlanMismatch)

    def admit(
        refined: LinearRefinementOutcome[FramedAffine[Moving, Fixed, D3]],
        accumulatedWork: FlashalignWorkCounts
    ): Either[
      LinearEngineError,
      LinearEngineOutcome[FramedAffine[Moving, Fixed, D3]]
    ] =
      val baseEvidence = LinearEngineFailureEvidence(
        accumulatedWork,
        refined.levels,
        refined.lastCheckpoint,
        refined.optimizationPatchEntries,
        refined.selectionPatchEntries
      )
      auditAffine(
        refined.state,
        samples,
        objectiveConfig,
        fixedSampler,
        model
      ) match
        case Left(failure) =>
          Left(
            LinearEngineError.FinalAuditFailed(
              failure.detail,
              baseEvidence.copy(work = accumulatedWork + failure.work)
            )
          )
        case Right(audit) =>
          val completeWork =
            accumulatedWork + FlashalignWorkCounts.audit(audit.dataWork)
          val failureEvidence = baseEvidence.copy(work = completeWork)
          requireAuditOverlap(
            audit.overlapFraction,
            policy.qc.minimumOverlapFraction,
            failureEvidence
          ).map(_ =>
            LinearEngineOutcome(
              refined.state,
              refined.report,
              refined.optimizationPatchEntries,
              refined.selectionPatchEntries,
              audit.patchEntries,
              audit.overlapFraction,
              completeWork,
              refined.levels
            )
          )

  private def auditRigid[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      S <: SampleSpace[Fixed, D3]
  ](
      state: Rigid3[Moving, Fixed],
      samples: PatchSamplePlan3[Moving, String],
      config: PatchObjectiveConfig,
      fixedSampler: LinearValueGradientSampler3[Fixed, S],
      model: RigidModel3[Moving, Fixed]
  ): Either[LinearAuditFailure, LinearAuditOutcome] =
    for
      auditSamples <- openAudit(samples).left.map(error =>
        LinearAuditFailure(error.message, FlashalignWorkCounts.Zero)
      )
      objective <- CompiledRigidObjective3
        .compile(
          model,
          FrozenPatchObjective3.create(auditSamples, config),
          samples.population.stencil,
          fixedSampler
        )
        .left
        .map(error => LinearAuditFailure(error.message, FlashalignWorkCounts.Zero))
      workspace <- objective
        .newWorkspace()
        .left
        .map(error => LinearAuditFailure(error.message, FlashalignWorkCounts.Zero))
      evaluated <-
        try
          objective
            .linearize(state, workspace)
            .left
            .map(error =>
              LinearAuditFailure(
                error.message,
                FlashalignWorkCounts.audit(workspace.lastWorkSnapshot)
              )
            )
        catch
          case NonFatal(error) =>
            Left(
              LinearAuditFailure(
                Option(error.getMessage).getOrElse(error.getClass.getName),
                FlashalignWorkCounts.audit(workspace.lastWorkSnapshot)
              )
            )
      overlap = overlapFraction(
        evaluated.counters.activeObjectiveWeight,
        evaluated.counters.objectiveWeight
      )
    yield LinearAuditOutcome(
      auditSamples.entries.size,
      overlap,
      LinearDataWorkCounts.from(evaluated.counters)
    )

  private def auditAffine[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      S <: SampleSpace[Fixed, D3]
  ](
      state: FramedAffine[Moving, Fixed, D3],
      samples: PatchSamplePlan3[Moving, String],
      config: PatchObjectiveConfig,
      fixedSampler: LinearValueGradientSampler3[Fixed, S],
      model: AffineModel3[Moving, Fixed]
  ): Either[LinearAuditFailure, LinearAuditOutcome] =
    for
      auditSamples <- openAudit(samples).left.map(error =>
        LinearAuditFailure(error.message, FlashalignWorkCounts.Zero)
      )
      objective <- CompiledAffineObjective3
        .compile(
          model,
          FrozenPatchObjective3.create(auditSamples, config),
          samples.population.stencil,
          fixedSampler
        )
        .left
        .map(error => LinearAuditFailure(error.message, FlashalignWorkCounts.Zero))
      workspace <- objective
        .newWorkspace()
        .left
        .map(error => LinearAuditFailure(error.message, FlashalignWorkCounts.Zero))
      evaluated <-
        try
          objective
            .linearize(state, workspace)
            .left
            .map(error =>
              LinearAuditFailure(
                error.message,
                FlashalignWorkCounts.audit(workspace.lastWorkSnapshot)
              )
            )
        catch
          case NonFatal(error) =>
            Left(
              LinearAuditFailure(
                Option(error.getMessage).getOrElse(error.getClass.getName),
                FlashalignWorkCounts.audit(workspace.lastWorkSnapshot)
              )
            )
      overlap = overlapFraction(
        evaluated.counters.activeObjectiveWeight,
        evaluated.counters.objectiveWeight
      )
    yield LinearAuditOutcome(
      auditSamples.entries.size,
      overlap,
      LinearDataWorkCounts.from(evaluated.counters)
    )

  private def openAudit[Moving <: Frame[D3]](
      samples: PatchSamplePlan3[Moving, String]
  ): Either[LinearEngineError, PatchSampleSet3] =
    val workspace = samples.newWorkspace()
    samples
      .markModelSelectionComplete(samples.selection.id, workspace)
      .left
      .map(error => failed(LinearEngineStage.Audit, error.message))
      .flatMap(_ =>
        samples
          .openAudit(workspace)
          .left
          .map(error => failed(LinearEngineStage.Audit, error.message))
      )

  private def preparePopulation[
      Moving <: Frame[D3]
  ](
      level: SupportAwarePyramidLevel3[Moving, String],
      objectiveConfig: PatchObjectiveConfig,
      policy: LinearPresetPolicy,
      stencilSpacingMillimetres: Double
  )(using Dimension[D3]): Either[
    LinearEngineError,
    PatchPopulation3[Moving, String]
  ] =
    for
      populationConfig <- PatchPopulationConfig
        .create(
          stencilSpacingMillimetres,
          policy.preparation.worldCellSizeMillimetres,
          targetCandidateCount = policy.preparation.targetCandidatePatches,
          maximumCentersToScreen = policy.preparation.maximumCentersToScreen,
          maximumCandidatesPerCell = policy.preparation.maximumCandidatesPerCell
        )
        .left
        .map(error => failed(LinearEngineStage.Configuration, error.message))
      population <- Preparation
        .buildPopulation(level, populationConfig, objectiveConfig)
        .left
        .map(error => failed(LinearEngineStage.Preparation, error.message))
    yield population

  private def preparePyramid[F <: Frame[D3]](
      image: FlashalignImage[F],
      policy: LinearPresetPolicy
  )(using Dimension[D3]): Either[
    LinearEngineError,
    SupportAwarePyramid3[F, String]
  ] =
    val shape = image.grid.shape
    val support = NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
      (i, j, k) => if image.data(i, j, k).isFinite then 1.0 else 0.0
    }
    val affine = image.grid.indexToFrame.rowMajor
    val scales = Vector.tabulate(3) { axis =>
      math.sqrt(
        affine(axis) * affine(axis) +
          affine(4 + axis) * affine(4 + axis) +
          affine(8 + axis) * affine(8 + axis)
      )
    }
    for
      levels <- scaleLevels(policy, scales)
      schedule <- ScaleSchedule
        .create(levels)
        .left
        .map(error => failed(LinearEngineStage.Preparation, error.message))
      tower <- GridTower
        .build(image.grid, schedule)
        .left
        .map(error => failed(LinearEngineStage.Preparation, error.message))
      sourcePsf = IsotropicGaussianPsf3.voxelCellApproximation(image.grid)
      targetPsfs = tower.levels.map(level =>
        IsotropicGaussianPsf3.voxelCellApproximation(level.grid)
      )
      pyramidConfig <- SupportAwarePyramidConfig3
        .create(sourcePsf, targetPsfs)
        .left
        .map(error => failed(LinearEngineStage.Preparation, error.message))
      pyramid <- SupportAwarePyramid3
        .build(
          image,
          support,
          tower,
          pyramidConfig,
          SupportAwarePyramidWorkspace3.create
        )
        .left
        .map(error => failed(LinearEngineStage.Preparation, error.message))
    yield pyramid

  private def scaleLevels(
      policy: LinearPresetPolicy,
      nativeAxisScales: Vector[Double]
  )(using Dimension[D3]): Either[
    LinearEngineError,
    Vector[ScaleLevel[D3, String]]
  ] =
    val result = Vector.newBuilder[ScaleLevel[D3, String]]
    var index = 0
    while index < policy.preparation.effectiveResolutionMillimetres.size do
      val resolution = policy.preparation.effectiveResolutionMillimetres(index)
      val shrink =
        if index == policy.preparation.effectiveResolutionMillimetres.size - 1 then
          Vector.fill(3)(1)
        else
          Vector.tabulate(3)(axis =>
            math.max(1, math.round(resolution / nativeAxisScales(axis)).toInt)
          )
      ScaleSpec.create[D3](shrink, Vector.fill(3)(0.0)) match
        case Left(error) =>
          return Left(failed(LinearEngineStage.Preparation, error.message))
        case Right(scale) =>
          result += ScaleLevel(scale, s"level-$index-$resolution-mm")
      index += 1
    Right(result.result())

  private def samplePlan[Moving <: Frame[D3]](
      population: PatchPopulation3[Moving, String],
      policy: LinearPresetPolicy
  ): Either[LinearEngineError, PatchSamplePlan3[Moving, String]] =
    for
      config <- PatchSamplingConfig
        .create(
          policy.sampling.selectionFraction,
          policy.sampling.auditFraction,
          policy.sampling.uniformProbabilityMixture,
          policy.sampling.optimizationDraws,
          policy.sampling.selectionDraws,
          policy.sampling.auditDraws,
          policy.sampling.seed
        )
        .left
        .map(error => failed(LinearEngineStage.Configuration, error.message))
      plan <- PatchSamplePlan3
        .compile(population, config)
        .left
        .map(error => failed(LinearEngineStage.Sampling, error.message))
    yield plan

  private def patchConfig(
      policy: LinearPresetPolicy
  ): Either[LinearEngineError, PatchObjectiveConfig] =
    PatchObjectiveConfig
      .create(
        policy.patch.positivePolarityPrior,
        policy.patch.tau,
        policy.patch.outlierFloor,
        policy.patch.minimumContrastEnergy,
        policy.patch.correlationRoundingTolerance
      )
      .left
      .map(error => failed(LinearEngineStage.Configuration, error.message))

  private def optimizerConfig(
      parameterCount: Int,
      trust: PresetTrustPolicy
  ): Either[LinearEngineError, ProjectedPatchOptimizerConfig] =
    ProjectedPatchOptimizerConfig
      .create(
        parameterCount,
        trust.maximumLinearizations,
        trust.maximumTrialAttempts,
        trust.initialDamping,
        trust.minimumDamping,
        trust.maximumDamping,
        trustRadiusRms = trust.trustRadiusRmsMillimetres,
        maximumDisplacement = trust.maximumDisplacementMillimetres,
        acceptanceRatio = trust.acceptanceRatio,
        objectiveTolerance = trust.objectiveTolerance,
        gradientTolerance = trust.gradientTolerance,
        stepToleranceRms = trust.stepToleranceRmsMillimetres,
        conditionLimit = trust.conditionLimit
      )
      .left
      .map(error => failed(LinearEngineStage.Configuration, error.message))

  private def fixedPivot[F <: Frame[D3]](
      image: FlashalignImage[F]
  ): Either[LinearEngineError, Vector[Double]] =
    val shape = image.grid.shape
    val index = Vector.tabulate(3)(axis => (shape(axis).toDouble - 1.0) * 0.5)
    applyAffine(image.grid.indexToFrame.rowMajor, index)

  private def rigidProbes[F <: Frame[D3]](
      image: FlashalignImage[F]
  ): Either[LinearEngineError, RigidFixedProbeSet3[F]] =
    probePoints(image).flatMap(points =>
      RigidFixedProbeSet3
        .create(image.frame, points)
        .left
        .map(error => failed(LinearEngineStage.Geometry, error.message))
    )

  private def affineProbes[F <: Frame[D3]](
      image: FlashalignImage[F]
  ): Either[LinearEngineError, AffineFixedProbeSet3[F]] =
    probePoints(image).flatMap(points =>
      AffineFixedProbeSet3
        .create(image.frame, points)
        .left
        .map(error => failed(LinearEngineStage.Geometry, error.message))
    )

  private def probePoints[F <: Frame[D3]](
      image: FlashalignImage[F]
  ): Either[LinearEngineError, Vector[Point[image.frame.type, D3]]] =
    val shape = image.grid.shape
    val low = Vector.fill(3)(0.5)
    val high = Vector.tabulate(3)(axis => math.max(0.5, shape(axis).toDouble - 1.5))
    val center = Vector.tabulate(3)(axis => (shape(axis).toDouble - 1.0) * 0.5)
    val indices = Vector(
      Vector(low(0), low(1), low(2)),
      Vector(high(0), low(1), low(2)),
      Vector(low(0), high(1), low(2)),
      Vector(low(0), low(1), high(2)),
      Vector(high(0), high(1), high(2)),
      center
    )
    indices.foldLeft[
      Either[LinearEngineError, Vector[Point[image.frame.type, D3]]]
    ](Right(Vector.empty)) {
      case (result, index) =>
        for
          points <- result
          world <- applyAffine(image.grid.indexToFrame.rowMajor, index)
          point <- Point
            .in[D3](image.frame)(world(0), world(1), world(2))
            .left
            .map(error => failed(LinearEngineStage.Geometry, error.message))
        yield points :+ point
    }

  private def applyAffine(
      matrix: Vector[Double],
      point: Vector[Double]
  ): Either[LinearEngineError, Vector[Double]] =
    val output = Vector.tabulate(3) { row =>
      matrix(row * 4) * point(0) +
        matrix(row * 4 + 1) * point(1) +
        matrix(row * 4 + 2) * point(2) +
        matrix(row * 4 + 3)
    }
    if output.forall(_.isFinite) then Right(output)
    else Left(failed(LinearEngineStage.Geometry, "grid produced a nonfinite world point"))

  private[flashalign] def withPyramidFailureEvidence(
      error: LinearEngineError,
      completedWork: FlashalignWorkCounts,
      completedLevels: Vector[FlashalignLinearLevelDiagnostics],
      lastCompletedCheckpoint: Option[FlashalignLinearCheckpointDiagnostics],
      levelIndex: Int,
      effectiveResolutionMillimetres: Double
  ): LinearEngineError =
    error match
      case LinearEngineError.FitRejected(termination, evidence) =>
        val failedLevel = FlashalignLinearLevelDiagnostics(
          levelIndex,
          effectiveResolutionMillimetres,
          evidence.optimizationPatchEntries,
          evidence.selectionPatchEntries,
          evidence.lastCheckpoint.selectionObjective,
          evidence.work
        )
        LinearEngineError.FitRejected(
          termination,
          evidence.copy(
            work = completedWork + evidence.work,
            levels = completedLevels ++ evidence.levels :+ failedLevel
          )
        )
      case other =>
        lastCompletedCheckpoint match
          case Some(checkpoint) =>
            val lastLevel = completedLevels.last
            val failedWork = other match
              case value: LinearEngineError.UncheckpointedEvaluationFailure =>
                value.work
              case _ => FlashalignWorkCounts.Zero
            LinearEngineError.PyramidStageFailed(
              other,
              LinearEngineFailureEvidence(
                completedWork + failedWork,
                completedLevels,
                checkpoint,
                lastLevel.optimizationPatches,
                lastLevel.selectionPatches
              )
            )
          case None => other

  private def requireConverged(
      termination: ProjectedPatchTermination,
      evidence: LinearEngineFailureEvidence
  ): Either[LinearEngineError, Unit] =
    if termination.converged then Right(())
    else Left(LinearEngineError.FitRejected(termination, evidence))

  private def requireAuditOverlap(
      observed: Double,
      required: Double,
      evidence: LinearEngineFailureEvidence
  ): Either[LinearEngineError, Unit] =
    if observed.isFinite && observed >= required then Right(())
    else
      Left(
        LinearEngineError.InsufficientAuditOverlap(observed, required, evidence)
      )

  private def overlapFraction(active: Double, total: Double): Double =
    if total > 0.0 && total.isFinite && active.isFinite then active / total
    else 0.0

  private def reportOf[State](
      result: ProjectedPatchOptimizationResult[State]
  ): Either[LinearEngineError, OptimizationReport] =
    val termination = result.termination match
      case ProjectedPatchTermination.GradientConverged  => Termination.GradientConverged
      case ProjectedPatchTermination.ObjectiveConverged => Termination.ObjectiveConverged
      case ProjectedPatchTermination.StepConverged      => Termination.StepConverged
      case _                                            => Termination.RejectedStepLimit
    OptimizationReport
      .create(
        result.initialOptimizationObjective,
        result.lastOptimizationObjective,
        result.counters.dataLinearizations,
        result.counters.acceptedSteps + result.counters.rejectedSteps,
        result.counters.acceptedSteps,
        termination
      )
      .left
      .map(error => failed(LinearEngineStage.Report, error.message))

  private def failed(stage: LinearEngineStage, detail: String): LinearEngineError =
    LinearEngineError.Failed(stage, detail)
