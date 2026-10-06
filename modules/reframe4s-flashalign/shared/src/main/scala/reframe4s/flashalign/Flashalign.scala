package reframe4s.flashalign

import image4s.ContinuousImage
import image4s.SampleSpace
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import ravel.Rank
import reframe4s.core.SpatialMap
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3
import reframe4s.register.BidirectionalRegistrationResult
import reframe4s.register.OptimizationReport
import reframe4s.register.RegistrationFailure
import reframe4s.register.RegistrationResult

/** A canonical spatial-only D3 scalar image accepted by Flashalign. */
type FlashalignImage[F <: Frame[D3]] = ContinuousImage[
  ? <: SampleSpace[F, D3],
  Double,
  Rank[3]
]

/** Opinionated acquisition/contrast presets. */
enum FlashalignPreset derives CanEqual:
  case EpiToT1
  case EpiToT2
  case WithinModality
  case SlabToAnatomy

/** Geometry represented by a compiled plan or result. */
enum FlashalignModel derives CanEqual:
  case Rigid
  case Affine

enum FlashalignEndpoint derives CanEqual:
  case Moving
  case Fixed

enum FlashalignConfigField derives CanEqual:
  case MinimumContrastEnergy
  case MaximumLinearizations

enum FlashalignCaptureFailure derives CanEqual:
  case InsufficientOverlap
  case InsufficientInformation
  case ExhaustedSchedule

/** Closed failures for plan compilation and execution. */
sealed trait FlashalignError derives CanEqual:
  def message: String
  def failureDiagnostics: Option[FlashalignFailureDiagnostics] = None

object FlashalignError:
  final case class InvalidPositiveScalar(
      field: FlashalignConfigField,
      value: Double
  ) extends FlashalignError:
    val message: String = s"$field must be finite and positive, got $value"

  final case class InvalidPositiveCount(
      field: FlashalignConfigField,
      value: Int
  ) extends FlashalignError:
    val message: String = s"$field must be positive, got $value"

  final case class InvalidPresetPolicy(error: LinearPresetError)
      extends FlashalignError:
    val message: String = error.message

  final case class NonSpatialAxes(
      endpoint: FlashalignEndpoint,
      count: Int
  ) extends FlashalignError:
    val message: String =
      s"$endpoint Flashalign image must be spatial-only, got $count axes"

  case object WorkspaceInUse extends FlashalignError:
    val message: String = "Flashalign workspace is already in use"

  case object WorkspacePlanMismatch extends FlashalignError:
    val message: String =
      "Flashalign workspace belongs to a different compiled plan"

  final case class EngineUnavailable(model: FlashalignModel)
      extends FlashalignError:
    val message: String = s"$model Flashalign engine is not implemented"

  final case class LinearEngine(error: LinearEngineError)
      extends FlashalignError:
    val message: String = error.message
    override val failureDiagnostics: Option[FlashalignFailureDiagnostics] =
      error match
        case failure: LinearEngineError.UncheckpointedEvaluationFailure =>
          Some(
            FlashalignFailureDiagnostics(
              failure.work,
              Vector.empty,
              None,
              None
            )
          )
        case _ => error.evidence.map(FlashalignFailureDiagnostics.fromLinear)

  final case class Registration(error: RegistrationFailure)
      extends FlashalignError:
    val message: String = error.message

  object Geometry:
    def apply(error: GeometryError | spatial4s.SpatialError): Geometry =
      new Geometry(image4s.geometry.GeometryError.fromCoordinate(error))

  final case class Geometry(error: GeometryError) extends FlashalignError:
    val message: String = error.message

  final case class AutomaticInitialization(detail: String)
      extends FlashalignError:
    val message: String = s"automatic initialization failed: $detail"

  final case class AutomaticLinearFailure(
      error: LinearEngineError,
      diagnostics: FlashalignFailureDiagnostics
  ) extends FlashalignError:
    val message: String = error.message
    override val failureDiagnostics: Option[FlashalignFailureDiagnostics] =
      Some(diagnostics)

  final case class InitializationPolicyMismatch(
      required: FlashalignInitializationPolicy,
      actual: FlashalignInitializationPolicy
  ) extends FlashalignError:
    val message: String =
      s"operation requires $required initialization, got $actual"

  final case class CaptureRejected(
      failure: FlashalignCaptureFailure,
      evaluatedRotations: Int,
      scheduledRotations: Int,
      bestRejectedScore: Option[Double],
      diagnostics: FlashalignFailureDiagnostics
  ) extends FlashalignError:
    val message: String =
      s"structural capture returned $failure after $evaluatedRotations of " +
        s"$scheduledRotations scheduled rotations" +
        bestRejectedScore.fold("")(score => s"; best rejected score was $score")
    override val failureDiagnostics: Option[FlashalignFailureDiagnostics] =
      Some(diagnostics)

  final case class NoCaptureCandidateConverged(
      failures: Vector[String],
      diagnostics: FlashalignFailureDiagnostics
  ) extends FlashalignError:
    val message: String =
      s"none of ${failures.size} structural capture candidates converged: " +
        failures.mkString("; ")
    override val failureDiagnostics: Option[FlashalignFailureDiagnostics] =
      Some(diagnostics)

/** Immutable settings shared by one compiled moving/fixed pair. */
final class FlashalignConfig private (
    val policy: LinearPresetPolicy,
    val initialization: FlashalignInitializationPolicy
):
  val preset: FlashalignPreset = policy.preset
  val minimumContrastEnergy: Double = policy.patch.minimumContrastEnergy
  val maximumLinearizations: Int = policy.rigidTrust.maximumLinearizations

object FlashalignConfig:
  def forPreset(
      preset: FlashalignPreset,
      initialization: FlashalignInitializationPolicy
  ): FlashalignConfig =
    new FlashalignConfig(
      LinearPresetPolicies.forPreset(preset),
      initialization
    )

  def create(
      policy: LinearPresetPolicy,
      initialization: FlashalignInitializationPolicy
  ): FlashalignConfig =
    new FlashalignConfig(policy, initialization)

/** Reusable scratch ownership for one compiled plan and one caller at a time. */
final class FlashalignWorkspace private (
    private val owner: AnyRef,
    private val engineWorkspace: Either[FlashalignError, AnyRef]
):
  private var active = false

  private[flashalign] def acquire(candidate: AnyRef): Either[
    FlashalignError,
    Unit
  ] =
    this.synchronized {
      if !(owner eq candidate) then
        Left(FlashalignError.WorkspacePlanMismatch)
      else if active then Left(FlashalignError.WorkspaceInUse)
      else
        active = true
        Right(())
    }

  private[flashalign] def release(candidate: AnyRef): Unit =
    this.synchronized {
      if owner eq candidate then active = false
    }

  private[flashalign] def payload(
      candidate: AnyRef
  ): Either[FlashalignError, AnyRef] =
    if owner eq candidate then engineWorkspace
    else Left(FlashalignError.WorkspacePlanMismatch)

object FlashalignWorkspace:
  private[flashalign] def forPlan(
      plan: AnyRef,
      engineWorkspace: Either[FlashalignError, AnyRef]
  ): FlashalignWorkspace =
    new FlashalignWorkspace(plan, engineWorkspace)

/** Immutable canonical inputs and settings for a reusable pair. */
sealed trait FlashalignPlan[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
]:
  def moving: FlashalignImage[Moving]
  def fixed: FlashalignImage[Fixed]
  def config: FlashalignConfig
  def model: FlashalignModel
  protected def createEngineWorkspace(): Either[FlashalignError, AnyRef]

  final def newWorkspace(): FlashalignWorkspace =
    FlashalignWorkspace.forPlan(this, createEngineWorkspace())

final class RigidFlashalignPlan[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val moving: FlashalignImage[Moving],
    val fixed: FlashalignImage[Fixed],
    val config: FlashalignConfig,
    private val engine: RigidLinearEngine3[Moving, Fixed],
    private val automatic: Option[AutomaticLinearInitialization3[Moving, Fixed]]
) extends FlashalignPlan[Moving, Fixed]:
  val model: FlashalignModel = FlashalignModel.Rigid

  protected def createEngineWorkspace(): Either[FlashalignError, AnyRef] =
    engine
      .newWorkspace()
      .left
      .map(FlashalignError.LinearEngine.apply)
      .map(engineWorkspace =>
        new LinearPlanWorkspace(
          engineWorkspace,
          automatic.map(_.newWorkspace())
        )
      )

  def runFrom(
      initialMovingToFixed: Rigid3[Moving, Fixed],
      workspace: FlashalignWorkspace
  ): Either[FlashalignError, RigidFlashalignResult[Moving, Fixed]] =
    Flashalign
      .validateInitialEndpoints(
        moving.frame,
        fixed.frame,
        initialMovingToFixed.source,
        initialMovingToFixed.target
      )
      .flatMap(_ => workspace.acquire(this))
      .flatMap { _ =>
        try
          workspace
            .payload(this)
            .flatMap(payload =>
              engine
                .fit(
                  initialMovingToFixed,
                  payload.asInstanceOf[LinearPlanWorkspace].engine
                )
                .left
                .map(FlashalignError.LinearEngine.apply)
            )
            .flatMap(outcome =>
              RigidFlashalignResult.create(
                outcome.state,
                outcome.report,
                FlashalignDiagnostics(
                  model = model,
                  preset = config.preset,
                  selectedPatches = outcome.optimizationPatchEntries,
                  auditPatches = outcome.auditPatchEntries,
                  overlapFraction = outcome.auditOverlapFraction,
                  selectionPatches = outcome.selectionPatchEntries,
                  work = outcome.work,
                  levels = outcome.levels
                )
              )
            )
        finally workspace.release(this)
      }

  def run(
      workspace: FlashalignWorkspace
  ): Either[FlashalignError, RigidFlashalignResult[Moving, Fixed]] =
    automatic match
      case None =>
        Left(
          FlashalignError.InitializationPolicyMismatch(
            FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture,
            config.initialization
          )
        )
      case Some(initialization) =>
        workspace.acquire(this).flatMap { _ =>
          try
            workspace.payload(this).flatMap { payload =>
              val planWorkspace = payload.asInstanceOf[LinearPlanWorkspace]
              planWorkspace.capture match
                case None =>
                  Left(
                    FlashalignError.AutomaticInitialization(
                      "capture workspace was not provisioned"
                    )
                  )
                case Some(captureWorkspace) =>
                  initialization.capture(captureWorkspace).flatMap { captured =>
                    val started = System.nanoTime()
                    val candidateDiagnostics = Vector.newBuilder[
                      FlashalignCaptureCandidateDiagnostics
                    ]
                    val successful = Vector.newBuilder[(
                      Int,
                      StructuralCaptureCandidate3,
                      LinearRefinementOutcome[Rigid3[Moving, Fixed]]
                    )]
                    val failures = Vector.newBuilder[String]
                    captured.candidates.zipWithIndex.foreach { case (candidate, index) =>
                      initialization
                        .rigid(candidate)
                        .flatMap(initial =>
                          engine
                            .refine(initial, planWorkspace.engine)
                            .left
                            .map(FlashalignError.LinearEngine.apply)
                        ) match
                        case Right(refined) =>
                          successful += ((index, candidate, refined))
                          candidateDiagnostics += captureCandidateDiagnostics(
                            candidate,
                            Some(refined.report.finalObjective),
                            None,
                            refined.work,
                            refined.levels,
                            Some(refined.lastCheckpoint)
                          )
                        case Left(error) =>
                          failures += error.message
                          val evidence = error.failureDiagnostics
                          candidateDiagnostics += captureCandidateDiagnostics(
                            candidate,
                            None,
                            Some(error.message),
                            evidence.map(_.work).getOrElse(
                              FlashalignWorkCounts.Zero
                            ),
                            evidence.map(_.levels).getOrElse(Vector.empty),
                            evidence.flatMap(_.lastCheckpoint)
                          )
                    }
                    val completed = successful.result()
                    val completedCandidateDiagnostics = candidateDiagnostics.result()
                    val completedFailures = failures.result()
                    if completed.isEmpty then
                      val refinementElapsedNanoseconds =
                        System.nanoTime() - started
                      val bestFailed = completedCandidateDiagnostics
                        .filter(_.lastCheckpoint.nonEmpty)
                        .sortBy(item =>
                          (
                            item.lastCheckpoint.get.selectionObjective,
                            item.rotationId,
                            item.lagX,
                            item.lagY,
                            item.lagZ
                          )
                        )
                        .headOption
                      val work = completedCandidateDiagnostics.foldLeft(
                        FlashalignWorkCounts.Zero
                      )((sum, item) => sum + item.work)
                      Left(
                        FlashalignError.NoCaptureCandidateConverged(
                          completedFailures,
                          FlashalignFailureDiagnostics(
                            work,
                            bestFailed.map(_.levels).getOrElse(Vector.empty),
                            Some(
                              captureDiagnostics(
                                initialization,
                                captured,
                                completedCandidateDiagnostics,
                                selectedIndex = -1,
                                ranked = Vector.empty,
                                refinementElapsedNanoseconds =
                                  refinementElapsedNanoseconds,
                                auditElapsedNanoseconds = 0L
                              )
                            ),
                            bestFailed.flatMap(_.lastCheckpoint)
                          )
                        )
                      )
                    else
                      val ranked = completed.sortBy { item =>
                        (
                          item._3.report.finalObjective,
                          item._2.rotationId,
                          item._2.lagX,
                          item._2.lagY,
                          item._2.lagZ
                        )
                      }
                      val selected = ranked.head
                      val accumulatedWork = completedCandidateDiagnostics.foldLeft(
                        FlashalignWorkCounts.Zero
                      )((sum, item) => sum + item.work)
                      val refinementElapsedNanoseconds =
                        System.nanoTime() - started
                      val auditStarted = System.nanoTime()
                      engine
                        .admit(selected._3, accumulatedWork)
                        .left
                        .map { error =>
                          val evidence = error.evidence
                          FlashalignError.AutomaticLinearFailure(
                            error,
                            FlashalignFailureDiagnostics(
                              evidence.map(_.work).getOrElse(accumulatedWork),
                              evidence
                                .map(_.levels)
                                .getOrElse(selected._3.levels),
                              Some(
                                captureDiagnostics(
                                  initialization,
                                  captured,
                                  completedCandidateDiagnostics,
                                  selected._1,
                                  ranked,
                                  refinementElapsedNanoseconds,
                                  System.nanoTime() - auditStarted
                                )
                              ),
                              evidence
                                .map(_.lastCheckpoint)
                                .orElse(Some(selected._3.lastCheckpoint))
                            )
                          )
                        }
                        .flatMap(outcome =>
                          RigidFlashalignResult.create(
                            outcome.state,
                            outcome.report,
                            FlashalignDiagnostics(
                              model = model,
                              preset = config.preset,
                              selectedPatches = outcome.optimizationPatchEntries,
                              auditPatches = outcome.auditPatchEntries,
                              overlapFraction = outcome.auditOverlapFraction,
                              selectionPatches = outcome.selectionPatchEntries,
                              work = outcome.work,
                              capture = Some(
                                captureDiagnostics(
                                  initialization,
                                  captured,
                                  completedCandidateDiagnostics,
                                  selected._1,
                                  ranked,
                                  refinementElapsedNanoseconds,
                                  System.nanoTime() - auditStarted
                                )
                              ),
                              levels = outcome.levels
                            )
                          )
                        )
                  }
            }
          finally workspace.release(this)
        }

  private def captureCandidateDiagnostics(
      candidate: StructuralCaptureCandidate3,
      objective: Option[Double],
      failure: Option[String],
      work: FlashalignWorkCounts,
      levels: Vector[FlashalignLinearLevelDiagnostics],
      lastCheckpoint: Option[FlashalignLinearCheckpointDiagnostics]
  ): FlashalignCaptureCandidateDiagnostics =
    FlashalignCaptureCandidateDiagnostics(
      candidate.rotationId,
      candidate.stage,
      candidate.lagX,
      candidate.lagY,
      candidate.lagZ,
      candidate.structuralScore,
      candidate.overlapFraction,
      objective,
      failure,
      work,
      levels,
      lastCheckpoint
    )

  private def captureDiagnostics(
      initialization: AutomaticLinearInitialization3[Moving, Fixed],
      captured: AutomaticCaptureCandidates3,
      candidates: Vector[FlashalignCaptureCandidateDiagnostics],
      selectedIndex: Int,
      ranked: Vector[(
        Int,
        StructuralCaptureCandidate3,
        LinearRefinementOutcome[Rigid3[Moving, Fixed]]
      )],
      refinementElapsedNanoseconds: Long,
      auditElapsedNanoseconds: Long
  ): FlashalignCaptureDiagnostics =
    val competing =
      ranked.size > 1 &&
        ranked(1)._3.report.finalObjective - ranked.head._3.report.finalObjective <=
          config.policy.qc.nearEqualObjectiveTolerance
    FlashalignCaptureDiagnostics(
      captured.diagnostics.scheduleVersion,
      captured.diagnostics.seed,
      captured.diagnostics.scheduledRotations,
      captured.diagnostics.evaluatedRotations,
      candidates,
      selectedIndex,
      competing,
      initialization.preparationElapsedNanoseconds,
      captured.elapsedNanoseconds,
      refinementElapsedNanoseconds,
      auditElapsedNanoseconds,
      captured.diagnostics.identityOverlapFraction,
      captured.diagnostics.identityStructuralScore,
      captured.diagnostics.identityAdequate,
      captured.diagnostics.expandedCaptureExecuted
    )

object RigidFlashalignPlan:
  private[flashalign] def create[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      config: FlashalignConfig,
      engine: RigidLinearEngine3[Moving, Fixed],
      automatic: Option[AutomaticLinearInitialization3[Moving, Fixed]]
  ): RigidFlashalignPlan[Moving, Fixed] =
    new RigidFlashalignPlan(moving, fixed, config, engine, automatic)

final class AffineFlashalignPlan[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val moving: FlashalignImage[Moving],
    val fixed: FlashalignImage[Fixed],
    val config: FlashalignConfig,
    private val engine: AffineLinearEngine3[Moving, Fixed],
    private val automatic: Option[AutomaticLinearInitialization3[Moving, Fixed]]
) extends FlashalignPlan[Moving, Fixed]:
  val model: FlashalignModel = FlashalignModel.Affine

  protected def createEngineWorkspace(): Either[FlashalignError, AnyRef] =
    engine
      .newWorkspace()
      .left
      .map(FlashalignError.LinearEngine.apply)
      .map(engineWorkspace =>
        new LinearPlanWorkspace(
          engineWorkspace,
          automatic.map(_.newWorkspace())
        )
      )

  def runFrom(
      initialMovingToFixed: FramedAffine[Moving, Fixed, D3],
      workspace: FlashalignWorkspace
  ): Either[FlashalignError, AffineFlashalignResult[Moving, Fixed]] =
    Flashalign
      .validateInitialEndpoints(
        moving.frame,
        fixed.frame,
        initialMovingToFixed.source,
        initialMovingToFixed.target
      )
      .flatMap(_ => workspace.acquire(this))
      .flatMap { _ =>
        try
          workspace
            .payload(this)
            .flatMap(payload =>
              engine
                .fit(
                  initialMovingToFixed,
                  payload.asInstanceOf[LinearPlanWorkspace].engine
                )
                .left
                .map(FlashalignError.LinearEngine.apply)
            )
            .flatMap(outcome =>
              AffineFlashalignResult.create(
                outcome.state,
                outcome.report,
                FlashalignDiagnostics(
                  model = model,
                  preset = config.preset,
                  selectedPatches = outcome.optimizationPatchEntries,
                  auditPatches = outcome.auditPatchEntries,
                  overlapFraction = outcome.auditOverlapFraction,
                  selectionPatches = outcome.selectionPatchEntries,
                  work = outcome.work,
                  levels = outcome.levels
                )
              )
            )
        finally workspace.release(this)
      }

  def run(
      workspace: FlashalignWorkspace
  ): Either[FlashalignError, AffineFlashalignResult[Moving, Fixed]] =
    automatic match
      case None =>
        Left(
          FlashalignError.InitializationPolicyMismatch(
            FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture,
            config.initialization
          )
        )
      case Some(initialization) =>
        workspace.acquire(this).flatMap { _ =>
          try
            workspace.payload(this).flatMap { payload =>
              val planWorkspace = payload.asInstanceOf[LinearPlanWorkspace]
              planWorkspace.capture match
                case None =>
                  Left(
                    FlashalignError.AutomaticInitialization(
                      "capture workspace was not provisioned"
                    )
                  )
                case Some(captureWorkspace) =>
                  initialization.capture(captureWorkspace).flatMap { captured =>
                    val started = System.nanoTime()
                    val candidateDiagnostics = Vector.newBuilder[
                      FlashalignCaptureCandidateDiagnostics
                    ]
                    val successful = Vector.newBuilder[(
                      Int,
                      StructuralCaptureCandidate3,
                      LinearRefinementOutcome[FramedAffine[Moving, Fixed, D3]]
                    )]
                    val failures = Vector.newBuilder[String]
                    captured.candidates.zipWithIndex.foreach { case (candidate, index) =>
                      initialization
                        .affine(candidate)
                        .flatMap(initial =>
                          engine
                            .refine(initial, planWorkspace.engine)
                            .left
                            .map(FlashalignError.LinearEngine.apply)
                        ) match
                        case Right(refined) =>
                          successful += ((index, candidate, refined))
                          candidateDiagnostics += captureCandidateDiagnostics(
                            candidate,
                            Some(refined.report.finalObjective),
                            None,
                            refined.work,
                            refined.levels,
                            Some(refined.lastCheckpoint)
                          )
                        case Left(error) =>
                          failures += error.message
                          val evidence = error.failureDiagnostics
                          candidateDiagnostics += captureCandidateDiagnostics(
                            candidate,
                            None,
                            Some(error.message),
                            evidence.map(_.work).getOrElse(
                              FlashalignWorkCounts.Zero
                            ),
                            evidence.map(_.levels).getOrElse(Vector.empty),
                            evidence.flatMap(_.lastCheckpoint)
                          )
                    }
                    val completed = successful.result()
                    val completedCandidateDiagnostics = candidateDiagnostics.result()
                    val completedFailures = failures.result()
                    if completed.isEmpty then
                      val refinementElapsedNanoseconds =
                        System.nanoTime() - started
                      val bestFailed = completedCandidateDiagnostics
                        .filter(_.lastCheckpoint.nonEmpty)
                        .sortBy(item =>
                          (
                            item.lastCheckpoint.get.selectionObjective,
                            item.rotationId,
                            item.lagX,
                            item.lagY,
                            item.lagZ
                          )
                        )
                        .headOption
                      val work = completedCandidateDiagnostics.foldLeft(
                        FlashalignWorkCounts.Zero
                      )((sum, item) => sum + item.work)
                      Left(
                        FlashalignError.NoCaptureCandidateConverged(
                          completedFailures,
                          FlashalignFailureDiagnostics(
                            work,
                            bestFailed.map(_.levels).getOrElse(Vector.empty),
                            Some(
                              captureDiagnostics(
                                initialization,
                                captured,
                                completedCandidateDiagnostics,
                                selectedIndex = -1,
                                ranked = Vector.empty,
                                refinementElapsedNanoseconds =
                                  refinementElapsedNanoseconds,
                                auditElapsedNanoseconds = 0L
                              )
                            ),
                            bestFailed.flatMap(_.lastCheckpoint)
                          )
                        )
                      )
                    else
                      val ranked = completed.sortBy { item =>
                        (
                          item._3.report.finalObjective,
                          item._2.rotationId,
                          item._2.lagX,
                          item._2.lagY,
                          item._2.lagZ
                        )
                      }
                      val selected = ranked.head
                      val accumulatedWork = completedCandidateDiagnostics.foldLeft(
                        FlashalignWorkCounts.Zero
                      )((sum, item) => sum + item.work)
                      val refinementElapsedNanoseconds =
                        System.nanoTime() - started
                      val auditStarted = System.nanoTime()
                      engine
                        .admit(selected._3, accumulatedWork)
                        .left
                        .map { error =>
                          val evidence = error.evidence
                          FlashalignError.AutomaticLinearFailure(
                            error,
                            FlashalignFailureDiagnostics(
                              evidence.map(_.work).getOrElse(accumulatedWork),
                              evidence
                                .map(_.levels)
                                .getOrElse(selected._3.levels),
                              Some(
                                captureDiagnostics(
                                  initialization,
                                  captured,
                                  completedCandidateDiagnostics,
                                  selected._1,
                                  ranked,
                                  refinementElapsedNanoseconds,
                                  System.nanoTime() - auditStarted
                                )
                              ),
                              evidence
                                .map(_.lastCheckpoint)
                                .orElse(Some(selected._3.lastCheckpoint))
                            )
                          )
                        }
                        .flatMap(outcome =>
                          AffineFlashalignResult.create(
                            outcome.state,
                            outcome.report,
                            FlashalignDiagnostics(
                              model = model,
                              preset = config.preset,
                              selectedPatches = outcome.optimizationPatchEntries,
                              auditPatches = outcome.auditPatchEntries,
                              overlapFraction = outcome.auditOverlapFraction,
                              selectionPatches = outcome.selectionPatchEntries,
                              work = outcome.work,
                              capture = Some(
                                captureDiagnostics(
                                  initialization,
                                  captured,
                                  completedCandidateDiagnostics,
                                  selected._1,
                                  ranked,
                                  refinementElapsedNanoseconds,
                                  System.nanoTime() - auditStarted
                                )
                              ),
                              levels = outcome.levels
                            )
                          )
                        )
                  }
            }
          finally workspace.release(this)
        }

  private def captureCandidateDiagnostics(
      candidate: StructuralCaptureCandidate3,
      objective: Option[Double],
      failure: Option[String],
      work: FlashalignWorkCounts,
      levels: Vector[FlashalignLinearLevelDiagnostics],
      lastCheckpoint: Option[FlashalignLinearCheckpointDiagnostics]
  ): FlashalignCaptureCandidateDiagnostics =
    FlashalignCaptureCandidateDiagnostics(
      candidate.rotationId,
      candidate.stage,
      candidate.lagX,
      candidate.lagY,
      candidate.lagZ,
      candidate.structuralScore,
      candidate.overlapFraction,
      objective,
      failure,
      work,
      levels,
      lastCheckpoint
    )

  private def captureDiagnostics(
      initialization: AutomaticLinearInitialization3[Moving, Fixed],
      captured: AutomaticCaptureCandidates3,
      candidates: Vector[FlashalignCaptureCandidateDiagnostics],
      selectedIndex: Int,
      ranked: Vector[(
        Int,
        StructuralCaptureCandidate3,
        LinearRefinementOutcome[FramedAffine[Moving, Fixed, D3]]
      )],
      refinementElapsedNanoseconds: Long,
      auditElapsedNanoseconds: Long
  ): FlashalignCaptureDiagnostics =
    val competing =
      ranked.size > 1 &&
        ranked(1)._3.report.finalObjective - ranked.head._3.report.finalObjective <=
          config.policy.qc.nearEqualObjectiveTolerance
    FlashalignCaptureDiagnostics(
      captured.diagnostics.scheduleVersion,
      captured.diagnostics.seed,
      captured.diagnostics.scheduledRotations,
      captured.diagnostics.evaluatedRotations,
      candidates,
      selectedIndex,
      competing,
      initialization.preparationElapsedNanoseconds,
      captured.elapsedNanoseconds,
      refinementElapsedNanoseconds,
      auditElapsedNanoseconds,
      captured.diagnostics.identityOverlapFraction,
      captured.diagnostics.identityStructuralScore,
      captured.diagnostics.identityAdequate,
      captured.diagnostics.expandedCaptureExecuted
    )

object AffineFlashalignPlan:
  private[flashalign] def create[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      config: FlashalignConfig,
      engine: AffineLinearEngine3[Moving, Fixed],
      automatic: Option[AutomaticLinearInitialization3[Moving, Fixed]]
  ): AffineFlashalignPlan[Moving, Fixed] =
    new AffineFlashalignPlan(moving, fixed, config, engine, automatic)

final case class FlashalignCaptureCandidateDiagnostics(
    rotationId: Int,
    stage: Int,
    lagX: Int,
    lagY: Int,
    lagZ: Int,
    structuralScore: Double,
    overlapFraction: Double,
    finalObjective: Option[Double],
    failureMessage: Option[String],
    work: FlashalignWorkCounts = FlashalignWorkCounts.Zero,
    levels: Vector[FlashalignLinearLevelDiagnostics] = Vector.empty,
    lastCheckpoint: Option[FlashalignLinearCheckpointDiagnostics] = None
)

final case class FlashalignCaptureDiagnostics(
    scheduleVersion: String,
    seed: Long,
    scheduledRotations: Int,
    evaluatedRotations: Int,
    candidates: Vector[FlashalignCaptureCandidateDiagnostics],
    selectedCandidateIndex: Int,
    competingAlignments: Boolean,
    preparationElapsedNanoseconds: Long,
    captureElapsedNanoseconds: Long,
    refinementAndSelectionElapsedNanoseconds: Long,
    finalAuditElapsedNanoseconds: Long,
    identityOverlapFraction: Double,
    identityStructuralScore: Option[Double],
    identityAdequate: Boolean,
    expandedCaptureExecuted: Boolean
)

final case class FlashalignLinearLevelDiagnostics(
    levelIndex: Int,
    effectiveResolutionMillimetres: Double,
    optimizationPatches: Int,
    selectionPatches: Int,
    finalSelectionObjective: Double,
    work: FlashalignWorkCounts
)

final case class FlashalignLinearCheckpointDiagnostics(
    movingToFixedRowMajor: Vector[Double],
    lastValidMovingToFixedRowMajor: Vector[Double],
    selectionObjective: Double,
    acceptedSteps: Int,
    selectionObjectiveId: Long,
    initialOptimizationObjective: Double,
    lastOptimizationObjective: Double
)

final case class FlashalignFailureDiagnostics(
    work: FlashalignWorkCounts,
    levels: Vector[FlashalignLinearLevelDiagnostics],
    capture: Option[FlashalignCaptureDiagnostics],
    lastCheckpoint: Option[FlashalignLinearCheckpointDiagnostics]
)

private[flashalign] object FlashalignFailureDiagnostics:
  def fromLinear(evidence: LinearEngineFailureEvidence): FlashalignFailureDiagnostics =
    FlashalignFailureDiagnostics(
      evidence.work,
      evidence.levels,
      None,
      Some(evidence.lastCheckpoint)
    )

/** Model-specific fit evidence retained alongside canonical maps. */
final case class FlashalignDiagnostics(
    model: FlashalignModel,
    preset: FlashalignPreset,
    selectedPatches: Int,
    auditPatches: Int,
    overlapFraction: Double,
    selectionPatches: Int = 0,
    work: FlashalignWorkCounts = FlashalignWorkCounts.Zero,
    capture: Option[FlashalignCaptureDiagnostics] = None,
    levels: Vector[FlashalignLinearLevelDiagnostics] = Vector.empty
)

/** Common exact linear-result capabilities. */
sealed trait FlashalignResult[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
]:
  type MovingToFixed <: SpatialMap[Moving, Fixed, D3]
  type FixedToMoving <: SpatialMap[Fixed, Moving, D3]

  def movingToFixed: MovingToFixed
  def fixedToMoving: FixedToMoving
  def registration: BidirectionalRegistrationResult[Fixed, Moving, D3]
  def report: OptimizationReport = registration.report
  def diagnostics: FlashalignDiagnostics

final class RigidFlashalignResult[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val movingToFixed: Rigid3[Moving, Fixed],
    val fixedToMoving: Rigid3[Fixed, Moving],
    val registration: BidirectionalRegistrationResult[Fixed, Moving, D3],
    val diagnostics: FlashalignDiagnostics
) extends FlashalignResult[Moving, Fixed]:
  type MovingToFixed = Rigid3[Moving, Fixed]
  type FixedToMoving = Rigid3[Fixed, Moving]

  def record(
      movingGrid: Grid[Moving, D3],
      fixedGrid: Grid[Fixed, D3]
  ): Either[FlashalignRecordError, FlashalignLinearResultRecord] =
    FlashalignLinearResultRecord.fromRigid(this, movingGrid, fixedGrid)

object RigidFlashalignResult:
  private[flashalign] def create[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      movingToFixed: Rigid3[Moving, Fixed],
      report: OptimizationReport,
      diagnostics: FlashalignDiagnostics
  ): Either[FlashalignError, RigidFlashalignResult[Moving, Fixed]] =
    val fixedToMoving = movingToFixed.inverse
    RegistrationResult
      .exact[Fixed, Moving, D3, Rigid3[Fixed, Moving]](
        fixedToMoving,
        report
      )
      .left
      .map(FlashalignError.Registration.apply)
      .map(registration =>
        new RigidFlashalignResult(
          movingToFixed,
          fixedToMoving,
          registration,
          diagnostics
        )
      )

final class AffineFlashalignResult[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val movingToFixed: FramedAffine[Moving, Fixed, D3],
    val fixedToMoving: FramedAffine[Fixed, Moving, D3],
    val registration: BidirectionalRegistrationResult[Fixed, Moving, D3],
    val diagnostics: FlashalignDiagnostics
) extends FlashalignResult[Moving, Fixed]:
  type MovingToFixed = FramedAffine[Moving, Fixed, D3]
  type FixedToMoving = FramedAffine[Fixed, Moving, D3]

  def record(
      movingGrid: Grid[Moving, D3],
      fixedGrid: Grid[Fixed, D3]
  ): Either[FlashalignRecordError, FlashalignLinearResultRecord] =
    FlashalignLinearResultRecord.fromAffine(this, movingGrid, fixedGrid)

object AffineFlashalignResult:
  private[flashalign] def create[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      movingToFixed: FramedAffine[Moving, Fixed, D3],
      report: OptimizationReport,
      diagnostics: FlashalignDiagnostics
  ): Either[FlashalignError, AffineFlashalignResult[Moving, Fixed]] =
    val fixedToMoving = movingToFixed.inverse
    RegistrationResult
      .exact[Fixed, Moving, D3, FramedAffine[Fixed, Moving, D3]](
        fixedToMoving,
        report
      )
      .left
      .map(FlashalignError.Registration.apply)
      .map(registration =>
        new AffineFlashalignResult(
          movingToFixed,
          fixedToMoving,
          registration,
          diagnostics
        )
      )

/** Validated entry points. No file or workflow policy enters this API. */
object Flashalign:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      preset: FlashalignPreset,
      initialization: FlashalignInitializationPolicy
  ): Either[FlashalignError, RigidFlashalignPlan[Moving, Fixed]] =
    rigid(moving, fixed, FlashalignConfig.forPreset(preset, initialization))

  def rigid[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      config: FlashalignConfig
  ): Either[FlashalignError, RigidFlashalignPlan[Moving, Fixed]] =
    for
      _ <- validatePair(moving, fixed)
      prepared <- LinearEngine3
        .preparePyramids(moving, fixed, config.policy)
        .left
        .map(FlashalignError.LinearEngine.apply)
      engine <- LinearEngine3
        .compileRigidPrepared(prepared, config.policy)
        .left
        .map(FlashalignError.LinearEngine.apply)
      automatic <- automaticInitialization(moving, fixed, config, prepared)
    yield RigidFlashalignPlan.create(
      moving,
      fixed,
      config,
      engine,
      automatic
    )

  def affine[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      config: FlashalignConfig
  ): Either[FlashalignError, AffineFlashalignPlan[Moving, Fixed]] =
    for
      _ <- validatePair(moving, fixed)
      prepared <- LinearEngine3
        .preparePyramids(moving, fixed, config.policy)
        .left
        .map(FlashalignError.LinearEngine.apply)
      engine <- LinearEngine3
        .compileAffinePrepared(prepared, config.policy)
        .left
        .map(FlashalignError.LinearEngine.apply)
      automatic <- automaticInitialization(moving, fixed, config, prepared)
    yield AffineFlashalignPlan.create(
      moving,
      fixed,
      config,
      engine,
      automatic
    )

  private def automaticInitialization[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      config: FlashalignConfig,
      prepared: PreparedLinearPyramids3[Moving, Fixed]
  ): Either[
    FlashalignError,
    Option[AutomaticLinearInitialization3[Moving, Fixed]]
  ] =
    config.initialization match
      case FlashalignInitializationPolicy.SuppliedWorldTransform => Right(None)
      case FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture =>
        AutomaticLinearInitialization3
          .compileFromPreparedPyramids(moving, fixed, config.policy, prepared)
          .map(Some(_))

  private def validatePair[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed]
  ): Either[FlashalignError, Unit] =
    if moving.nonSpatialAxes.size != 0 then
      Left(
        FlashalignError.NonSpatialAxes(
          FlashalignEndpoint.Moving,
          moving.nonSpatialAxes.size
        )
      )
    else if fixed.nonSpatialAxes.size != 0 then
      Left(
        FlashalignError.NonSpatialAxes(
          FlashalignEndpoint.Fixed,
          fixed.nonSpatialAxes.size
        )
      )
    else Right(())

  private[flashalign] def validateInitialEndpoints[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: Moving,
      fixed: Fixed,
      initialSource: Moving,
      initialTarget: Fixed
  ): Either[FlashalignError, Unit] =
    for
      _ <- Frame
        .align(moving, initialSource)
        .left
        .map(FlashalignError.Geometry.apply)
      _ <- Frame
        .align(fixed, initialTarget)
        .left
        .map(FlashalignError.Geometry.apply)
    yield ()
