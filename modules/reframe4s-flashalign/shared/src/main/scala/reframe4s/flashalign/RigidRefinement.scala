package reframe4s.flashalign

import image4s.SampleSpace
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Point
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.lie.Rigid3
import scala.util.control.NonFatal

private[flashalign] sealed trait RigidRefinementError derives CanEqual:
  def message: String

private[flashalign] object RigidRefinementError:
  case object WorkspacePlanMismatch extends RigidRefinementError:
    val message: String = "rigid refinement workspace belongs to a different plan"

  final case class InvalidProbeSet(detail: String) extends RigidRefinementError:
    val message: String = detail

  final case class ProbeFrameOwnerMismatch(
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends RigidRefinementError:
    val message: String = s"probe owner $actual does not match $expected"

  final case class GeometryMismatch(
      optimizationFingerprint: Long,
      selectionFingerprint: Long
  ) extends RigidRefinementError:
    val message: String =
      s"optimization geometry $optimizationFingerprint differs from selection geometry $selectionFingerprint"

  final case class Linearization(error: RigidObjectiveLinearizationError)
      extends RigidRefinementError:
    val message: String = error.message

  final case class Optimizer(error: ProjectedPatchOptimizerError)
      extends RigidRefinementError:
    val message: String = error.message

private[flashalign] final class RigidFixedProbeSet3[+Fixed <: Frame[D3]] private (
    val fixed: Fixed,
    private[flashalign] val x: Array[Double],
    private[flashalign] val y: Array[Double],
    private[flashalign] val z: Array[Double]
):
  val size: Int = x.length

private[flashalign] object RigidFixedProbeSet3:
  def create[Fixed <: Frame[D3]](
      fixed: Fixed,
      points: Vector[Point[Fixed, D3]]
  ): Either[RigidRefinementError, RigidFixedProbeSet3[Fixed]] =
    if points.isEmpty then
      Left(RigidRefinementError.InvalidProbeSet("at least one probe is required"))
    else
      val x = new Array[Double](points.size)
      val y = new Array[Double](points.size)
      val z = new Array[Double](points.size)
      var index = 0
      while index < points.size do
        val point = points(index)
        if !point.belongsTo(fixed) then
          return Left(
            RigidRefinementError.ProbeFrameOwnerMismatch(
              FrameOwnerDescriptor.of(fixed),
              FrameOwnerDescriptor.of(point.frame)
            )
          )
        val coordinates = point.coordinates
        if coordinates.exists(value => !value.isFinite) then
          return Left(
            RigidRefinementError.InvalidProbeSet(
              s"probe $index contains a nonfinite coordinate"
            )
          )
        x(index) = coordinates(0)
        y(index) = coordinates(1)
        z(index) = coordinates(2)
        index += 1
      Right(new RigidFixedProbeSet3(fixed, x, y, z))

private[flashalign] final class RigidRefinementWorkspace3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private[flashalign] (
    private val owner: AnyRef,
    private[flashalign] val optimizer: ProjectedPatchOptimizer[
      Rigid3[Moving, Fixed]
    ],
    private[flashalign] val optimizerWorkspace: ProjectedPatchOptimizerWorkspace,
    private val dataWork: LinearDataWorkTracker
):
  private[flashalign] def belongsTo(candidate: AnyRef): Boolean = owner eq candidate
  private[flashalign] def dataWorkSnapshot: LinearDataWorkCounts = dataWork.snapshot
  private[flashalign] def optimizerFailureCountersSnapshot
      : ProjectedPatchWorkCounters = optimizerWorkspace.failureCountersSnapshot
  private[flashalign] def resetDataWork(): Unit = dataWork.reset()

/** Six-parameter streaming rigid optimizer. It deliberately has no deformation
  * basis, field certificate, Krylov state or HalfFlow dependency.
  */
private[flashalign] final class RigidRefinementPlan3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3],
    OptimizationSpace <: SampleSpace[Fixed, D3],
    SelectionSpace <: SampleSpace[Fixed, D3]
] private (
    val optimization: CompiledRigidObjective3[
      Moving,
      Fixed,
      OptimizationSpace
    ],
    val selection: CompiledRigidObjective3[
      Moving,
      Fixed,
      SelectionSpace
    ],
    val probes: RigidFixedProbeSet3[Fixed],
    val optimizerConfig: ProjectedPatchOptimizerConfig
):
  private val optimizationTrials = CompiledRigidTrialObjective3.compile(optimization)
  private val selectionTrials = CompiledRigidTrialObjective3.compile(selection)

  def newWorkspace(): Either[
    RigidRefinementError,
    RigidRefinementWorkspace3[Moving, Fixed]
  ] =
    optimization
      .newWorkspace()
      .left
      .map(RigidRefinementError.Linearization.apply)
      .flatMap { linearizationWorkspace =>
        val dataWork = new LinearDataWorkTracker
        val data = new RigidDataAdapter(
          optimization,
          linearizationWorkspace,
          optimizationTrials,
          optimizationTrials.newWorkspace(),
          selectionTrials,
          selectionTrials.newWorkspace(),
          dataWork
        )
        val prior = new ZeroRigidPrior
        val geometry = new RigidGeometryAdapter(
          optimization.model,
          probes,
          physicalMetric(optimization.model, probes)
        )
        ProjectedPatchOptimizer
          .compile(data, prior, geometry, optimizerConfig)
          .left
          .map(RigidRefinementError.Optimizer.apply)
          .map { optimizer =>
            new RigidRefinementWorkspace3(
              this,
              optimizer,
              optimizer.newWorkspace(),
              dataWork
            )
          }
      }

  def optimize(
      initial: Rigid3[Moving, Fixed],
      workspace: RigidRefinementWorkspace3[Moving, Fixed]
  ): Either[
    RigidRefinementError,
    ProjectedPatchOptimizationResult[Rigid3[Moving, Fixed]]
  ] =
    if !workspace.belongsTo(this) then
      Left(RigidRefinementError.WorkspacePlanMismatch)
    else
      workspace.resetDataWork()
      workspace.optimizer
        .optimize(initial, workspace.optimizerWorkspace)
        .left
        .map(RigidRefinementError.Optimizer.apply)

  private final class RigidDataAdapter(
      compiled: CompiledRigidObjective3[Moving, Fixed, OptimizationSpace],
      linearizationWorkspace: RigidObjectiveLinearizationWorkspace3,
      trials: CompiledRigidTrialObjective3[Moving, Fixed, OptimizationSpace],
      trialWorkspace: RigidTrialObjectiveWorkspace3,
      selectionEvaluator: CompiledRigidTrialObjective3[
        Moving,
        Fixed,
        SelectionSpace
      ],
      selectionWorkspace: RigidTrialObjectiveWorkspace3,
      dataWork: LinearDataWorkTracker
  ) extends ProjectedPatchDataProblem[Rigid3[Moving, Fixed]]:
    val parameterCount: Int = 6
    val optimizationObjectiveId: Long =
      objectiveIdentity(
        compiled.objective.id,
        compiled.geometryFingerprint,
        0x510e527fade682d1L
      )
    val selectionObjectiveId: Long =
      objectiveIdentity(
        selectionEvaluator.fullObjective.objective.id,
        selectionEvaluator.fullObjective.geometryFingerprint,
        0x9b05688c2b3e6c1fL
      )

    def linearize(
        state: Rigid3[Moving, Fixed],
        output: ProjectedPatchQuadraticBuffer
    ): Either[ProjectedPatchOptimizerError, Unit] =
      try
        compiled
          .linearize(state, linearizationWorkspace) match
          case Left(error) =>
            dataWork.add(linearizationWorkspace.lastWorkSnapshot)
            Left(evaluation(ProjectedPatchStage.DataLinearization, error.message))
          case Right(result) =>
            dataWork.add(result.counters)
            output.setObjective(result.objective)
            copy(result.gradient, output.gradient)
            copy(result.curvatureUpper, output.curvatureUpper)
            Right(())
      catch
        case NonFatal(error) =>
          dataWork.add(linearizationWorkspace.lastWorkSnapshot)
          Left(
            evaluation(
              ProjectedPatchStage.DataLinearization,
              Option(error.getMessage).getOrElse(error.getClass.getName)
            )
          )

    def trialDataObjective(
        state: Rigid3[Moving, Fixed],
        acceptanceLimit: Double
    ): Either[ProjectedPatchOptimizerError, ProjectedPatchTrialData] =
      try
        trials
          .evaluate(state, acceptanceLimit, trialWorkspace) match
          case Left(error) =>
            dataWork.add(trialWorkspace.lastWorkSnapshot)
            Left(evaluation(ProjectedPatchStage.TrialDataObjective, error.message))
          case Right(value: RigidTrialObjectiveValue.Complete) =>
            dataWork.add(value.counters)
            Right(ProjectedPatchTrialData.Complete(value.value))
          case Right(rejected: RigidTrialObjectiveValue.RejectedEarly) =>
            dataWork.add(rejected.counters)
            Right(
              ProjectedPatchTrialData.RejectedEarly(
                rejected.conservativeLowerBound
              )
            )
      catch
        case NonFatal(error) =>
          dataWork.add(trialWorkspace.lastWorkSnapshot)
          Left(
            evaluation(
              ProjectedPatchStage.TrialDataObjective,
              Option(error.getMessage).getOrElse(error.getClass.getName)
            )
          )

    def selectionObjective(
        state: Rigid3[Moving, Fixed]
    ): Either[ProjectedPatchOptimizerError, ProjectedPatchSelection] =
      try
        selectionEvaluator
          .evaluate(state, Double.PositiveInfinity, selectionWorkspace) match
          case Left(error) =>
            dataWork.add(selectionWorkspace.lastWorkSnapshot)
            Left(evaluation(ProjectedPatchStage.SelectionObjective, error.message))
          case Right(value: RigidTrialObjectiveValue.Complete) =>
            dataWork.add(value.counters)
            Right(ProjectedPatchSelection(selectionObjectiveId, value.value))
          case Right(rejected: RigidTrialObjectiveValue.RejectedEarly) =>
            dataWork.add(rejected.counters)
            Left(
              evaluation(
                ProjectedPatchStage.SelectionObjective,
                s"unbounded selection evaluation rejected at ${rejected.conservativeLowerBound}"
              )
            )
      catch
        case NonFatal(error) =>
          dataWork.add(selectionWorkspace.lastWorkSnapshot)
          Left(
            evaluation(
              ProjectedPatchStage.SelectionObjective,
              Option(error.getMessage).getOrElse(error.getClass.getName)
            )
          )

  private final class ZeroRigidPrior
      extends ProjectedPatchPrior[Rigid3[Moving, Fixed]]:
    def linearize(
        state: Rigid3[Moving, Fixed],
        output: ProjectedPatchQuadraticBuffer
    ): Either[ProjectedPatchOptimizerError, Unit] =
      val _ = state
      output.setObjective(0.0)
      Right(())

    def value(
        state: Rigid3[Moving, Fixed]
    ): Either[ProjectedPatchOptimizerError, Double] =
      val _ = state
      Right(0.0)

  private final class RigidGeometryAdapter(
      model: RigidModel3[Moving, Fixed],
      fixedProbes: RigidFixedProbeSet3[Fixed],
      metric: Array[Double]
  ) extends ProjectedPatchGeometry[Rigid3[Moving, Fixed]]:
    def writePhysicalMetric(
        state: Rigid3[Moving, Fixed],
        outputUpper: Array[Double]
    ): Either[ProjectedPatchOptimizerError, Unit] =
      val _ = state
      copy(metric, outputUpper)
      Right(())

    def propose(
        state: Rigid3[Moving, Fixed],
        step: Array[Double]
    ): Either[
      ProjectedPatchOptimizerError,
      ProjectedPatchProposalResult[Rigid3[Moving, Fixed]]
    ] =
      model.propose(state, step) match
        case Right(candidate) =>
          Right(
            ProjectedPatchProposalResult.Valid(
              ProjectedPatchProposal(candidate, step.clone())
            )
          )
        case Left(error) =>
          Left(evaluation(ProjectedPatchStage.Proposal, error.message))

    def maximumDisplacement(
        before: Rigid3[Moving, Fixed],
        after: Rigid3[Moving, Fixed]
    ): Either[ProjectedPatchOptimizerError, Double] =
      before.operator.inverse
        .andThen(after.operator)
        .left
        .map(error => evaluation(ProjectedPatchStage.MaximumDisplacement, error.message))
        .flatMap { relative =>
          var maximum = 0.0
          var failure = Option.empty[ProjectedPatchOptimizerError]
          var index = 0
          while index < fixedProbes.size && failure.isEmpty do
            val original = Vector(
              fixedProbes.x(index),
              fixedProbes.y(index),
              fixedProbes.z(index)
            )
            relative(original) match
              case Left(error) =>
                failure = Some(
                  evaluation(ProjectedPatchStage.MaximumDisplacement, error.message)
                )
              case Right(transformed) =>
                val dx = transformed(0) - original(0)
                val dy = transformed(1) - original(1)
                val dz = transformed(2) - original(2)
                maximum = math.max(maximum, math.sqrt(dx * dx + dy * dy + dz * dz))
            index += 1
          failure.toLeft(maximum)
        }

  private def physicalMetric(
      model: RigidModel3[Moving, Fixed],
      fixedProbes: RigidFixedProbeSet3[Fixed]
  ): Array[Double] =
    val metric = new Array[Double](PackedSymmetric.size(6))
    val columns = Array.fill(6)(new Array[Double](3))
    var probe = 0
    while probe < fixedProbes.size do
      val rx = fixedProbes.x(probe) - model.pivotX
      val ry = fixedProbes.y(probe) - model.pivotY
      val rz = fixedProbes.z(probe) - model.pivotZ
      columns.foreach(java.util.Arrays.fill(_, 0.0))
      columns(0)(0) = 1.0
      columns(1)(1) = 1.0
      columns(2)(2) = 1.0
      columns(3)(1) = -rz
      columns(3)(2) = ry
      columns(4)(0) = rz
      columns(4)(2) = -rx
      columns(5)(0) = -ry
      columns(5)(1) = rx
      var row = 0
      while row < 6 do
        var column = row
        while column < 6 do
          metric(PackedSymmetric.index(row, column)) +=
            columns(row)(0) * columns(column)(0) +
              columns(row)(1) * columns(column)(1) +
              columns(row)(2) * columns(column)(2)
          column += 1
        row += 1
      probe += 1
    var index = 0
    while index < metric.length do
      metric(index) /= fixedProbes.size.toDouble
      index += 1
    metric

  private def objectiveIdentity(
      id: FrozenPatchObjectiveId,
      geometryFingerprint: Long,
      seed: Long
  ): Long =
    var value = seed ^ id.hashCode().toLong ^ geometryFingerprint
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
    value ^ (value >>> 31)

  private def copy(source: Array[Double], destination: Array[Double]): Unit =
    java.lang.System.arraycopy(source, 0, destination, 0, source.length)

  private def evaluation(
      stage: ProjectedPatchStage,
      detail: String
  ): ProjectedPatchOptimizerError =
    ProjectedPatchOptimizerError.EvaluationFailure(stage, detail)

private[flashalign] object RigidRefinementPlan3:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      OptimizationSpace <: SampleSpace[Fixed, D3],
      SelectionSpace <: SampleSpace[Fixed, D3]
  ](
      optimization: CompiledRigidObjective3[
        Moving,
        Fixed,
        OptimizationSpace
      ],
      selection: CompiledRigidObjective3[
        Moving,
        Fixed,
        SelectionSpace
      ],
      probes: RigidFixedProbeSet3[Fixed],
      optimizerConfig: ProjectedPatchOptimizerConfig
  ): Either[
    RigidRefinementError,
    RigidRefinementPlan3[Moving, Fixed, OptimizationSpace, SelectionSpace]
  ] =
    if optimization.geometryFingerprint != selection.geometryFingerprint then
      Left(
        RigidRefinementError.GeometryMismatch(
          optimization.geometryFingerprint,
          selection.geometryFingerprint
        )
      )
    else if !optimization.model.fixed.sameRuntimeOwnerAs(probes.fixed) then
      Left(
        RigidRefinementError.ProbeFrameOwnerMismatch(
          FrameOwnerDescriptor.of(optimization.model.fixed),
          FrameOwnerDescriptor.of(probes.fixed)
        )
      )
    else if optimizerConfig.parameterCount != optimization.parameterCount then
      Left(
        RigidRefinementError.Optimizer(
          ProjectedPatchOptimizerError.InvalidConfiguration(
            s"rigid refinement requires ${optimization.parameterCount} parameters, got ${optimizerConfig.parameterCount}"
          )
        )
      )
    else Right(new RigidRefinementPlan3(optimization, selection, probes, optimizerConfig))
