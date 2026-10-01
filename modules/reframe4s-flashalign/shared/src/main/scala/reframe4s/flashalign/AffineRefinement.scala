package reframe4s.flashalign

import image4s.SampleSpace
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Point
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.lie.FramedAffine
import scala.util.control.NonFatal

private[flashalign] sealed trait AffineRefinementError derives CanEqual:
  def message: String

private[flashalign] object AffineRefinementError:
  case object WorkspacePlanMismatch extends AffineRefinementError:
    val message: String =
      "affine refinement workspace belongs to a different plan"

  final case class InvalidProbeSet(detail: String) extends AffineRefinementError:
    val message: String = detail

  final case class ProbeFrameOwnerMismatch(
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends AffineRefinementError:
    val message: String = s"probe owner $actual does not match $expected"

  final case class GeometryMismatch(
      optimizationFingerprint: Long,
      selectionFingerprint: Long
  ) extends AffineRefinementError:
    val message: String =
      s"optimization geometry $optimizationFingerprint differs from selection geometry $selectionFingerprint"

  case object PriorModelMismatch extends AffineRefinementError:
    val message: String =
      "affine strain prior must use the optimization geometry model"

  final case class Linearization(error: AffineObjectiveLinearizationError)
      extends AffineRefinementError:
    val message: String = error.message

  final case class Optimizer(error: ProjectedPatchOptimizerError)
      extends AffineRefinementError:
    val message: String = error.message

private[flashalign] final class AffineFixedProbeSet3[+Fixed <: Frame[D3]] private (
    val fixed: Fixed,
    private[flashalign] val x: Array[Double],
    private[flashalign] val y: Array[Double],
    private[flashalign] val z: Array[Double]
):
  val size: Int = x.length

private[flashalign] object AffineFixedProbeSet3:
  def create[Fixed <: Frame[D3]](
      fixed: Fixed,
      points: Vector[Point[Fixed, D3]]
  ): Either[AffineRefinementError, AffineFixedProbeSet3[Fixed]] =
    if points.isEmpty then
      Left(AffineRefinementError.InvalidProbeSet("at least one probe is required"))
    else
      val x = new Array[Double](points.size)
      val y = new Array[Double](points.size)
      val z = new Array[Double](points.size)
      var index = 0
      while index < points.size do
        val point = points(index)
        if !point.belongsTo(fixed) then
          return Left(
            AffineRefinementError.ProbeFrameOwnerMismatch(
              FrameOwnerDescriptor.of(fixed),
              FrameOwnerDescriptor.of(point.frame)
            )
          )
        val coordinates = point.coordinates
        if coordinates.exists(value => !value.isFinite) then
          return Left(
            AffineRefinementError.InvalidProbeSet(
              s"probe $index contains a nonfinite coordinate"
            )
          )
        x(index) = coordinates(0)
        y(index) = coordinates(1)
        z(index) = coordinates(2)
        index += 1
      Right(new AffineFixedProbeSet3(fixed, x, y, z))

private[flashalign] final class AffineRefinementWorkspace3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private[flashalign] (
    private val owner: AnyRef,
    private[flashalign] val optimizer: ProjectedPatchOptimizer[
      FramedAffine[Moving, Fixed, D3]
    ],
    private[flashalign] val optimizerWorkspace: ProjectedPatchOptimizerWorkspace,
    private val dataWork: LinearDataWorkTracker
):
  private[flashalign] def belongsTo(candidate: AnyRef): Boolean = owner eq candidate
  private[flashalign] def dataWorkSnapshot: LinearDataWorkCounts = dataWork.snapshot
  private[flashalign] def optimizerFailureCountersSnapshot
      : ProjectedPatchWorkCounters = optimizerWorkspace.failureCountersSnapshot
  private[flashalign] def resetDataWork(): Unit = dataWork.reset()

/**
 * Explicit-workspace adapter from the affine streaming objective to the shared
 * bounded small-parameter optimizer.
 */
private[flashalign] final class AffineRefinementPlan3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3],
    OptimizationSpace <: SampleSpace[Fixed, D3],
    SelectionSpace <: SampleSpace[Fixed, D3]
] private (
    val optimization: CompiledAffineObjective3[
      Moving,
      Fixed,
      OptimizationSpace
    ],
    val selection: CompiledAffineObjective3[
      Moving,
      Fixed,
      SelectionSpace
    ],
    val prior: AffineStrainPrior3[Moving, Fixed],
    val probes: AffineFixedProbeSet3[Fixed],
    val optimizerConfig: ProjectedPatchOptimizerConfig
):
  private val optimizationTrials =
    CompiledAffineTrialObjective3.compile(optimization)
  private val selectionTrials = CompiledAffineTrialObjective3.compile(selection)

  def newWorkspace(): Either[
    AffineRefinementError,
    AffineRefinementWorkspace3[Moving, Fixed]
  ] =
    optimization
      .newWorkspace()
      .left
      .map(AffineRefinementError.Linearization.apply)
      .flatMap { linearizationWorkspace =>
        val dataWork = new LinearDataWorkTracker
        val data = new AffineDataAdapter(
          optimization,
          linearizationWorkspace,
          optimizationTrials,
          optimizationTrials.newWorkspace(),
          selectionTrials,
          selectionTrials.newWorkspace(),
          dataWork
        )
        val priorAdapter = new AffinePriorAdapter(prior)
        val geometry = new AffineGeometryAdapter(
          optimization.model,
          probes,
          physicalMetric(optimization.model, probes)
        )
        ProjectedPatchOptimizer
          .compile(data, priorAdapter, geometry, optimizerConfig)
          .left
          .map(AffineRefinementError.Optimizer.apply)
          .map { optimizer =>
            new AffineRefinementWorkspace3(
              this,
              optimizer,
              optimizer.newWorkspace(),
              dataWork
            )
          }
      }

  def optimize(
      initial: FramedAffine[Moving, Fixed, D3],
      workspace: AffineRefinementWorkspace3[Moving, Fixed]
  ): Either[
    AffineRefinementError,
    ProjectedPatchOptimizationResult[FramedAffine[Moving, Fixed, D3]]
  ] =
    if !workspace.belongsTo(this) then
      Left(AffineRefinementError.WorkspacePlanMismatch)
    else
      workspace.resetDataWork()
      workspace.optimizer
        .optimize(initial, workspace.optimizerWorkspace)
        .left
        .map(AffineRefinementError.Optimizer.apply)

  private final class AffineDataAdapter(
      compiled: CompiledAffineObjective3[Moving, Fixed, OptimizationSpace],
      linearizationWorkspace: AffineObjectiveLinearizationWorkspace3,
      trials: CompiledAffineTrialObjective3[Moving, Fixed, OptimizationSpace],
      trialWorkspace: AffineTrialObjectiveWorkspace3,
      selectionEvaluator: CompiledAffineTrialObjective3[
        Moving,
        Fixed,
        SelectionSpace
      ],
      selectionWorkspace: AffineTrialObjectiveWorkspace3,
      dataWork: LinearDataWorkTracker
  ) extends ProjectedPatchDataProblem[FramedAffine[Moving, Fixed, D3]]:
    val parameterCount: Int = 12
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
        state: FramedAffine[Moving, Fixed, D3],
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
        state: FramedAffine[Moving, Fixed, D3],
        acceptanceLimit: Double
    ): Either[ProjectedPatchOptimizerError, ProjectedPatchTrialData] =
      try
        trials
          .evaluate(state, acceptanceLimit, trialWorkspace) match
          case Left(error) =>
            dataWork.add(trialWorkspace.lastWorkSnapshot)
            Left(evaluation(ProjectedPatchStage.TrialDataObjective, error.message))
          case Right(value: AffineTrialObjectiveValue.Complete) =>
            dataWork.add(value.counters)
            Right(ProjectedPatchTrialData.Complete(value.value))
          case Right(rejected: AffineTrialObjectiveValue.RejectedEarly) =>
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
        state: FramedAffine[Moving, Fixed, D3]
    ): Either[ProjectedPatchOptimizerError, ProjectedPatchSelection] =
      try
        selectionEvaluator
          .evaluate(state, Double.PositiveInfinity, selectionWorkspace) match
          case Left(error) =>
            dataWork.add(selectionWorkspace.lastWorkSnapshot)
            Left(evaluation(ProjectedPatchStage.SelectionObjective, error.message))
          case Right(value: AffineTrialObjectiveValue.Complete) =>
            dataWork.add(value.counters)
            Right(ProjectedPatchSelection(selectionObjectiveId, value.value))
          case Right(rejected: AffineTrialObjectiveValue.RejectedEarly) =>
            dataWork.add(rejected.counters)
            Left(
              evaluation(
                ProjectedPatchStage.SelectionObjective,
                "an infinite selection threshold rejected an incomplete value"
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

  private final class AffinePriorAdapter(
      affinePrior: AffineStrainPrior3[Moving, Fixed]
  ) extends ProjectedPatchPrior[FramedAffine[Moving, Fixed, D3]]:
    def linearize(
        state: FramedAffine[Moving, Fixed, D3],
        output: ProjectedPatchQuadraticBuffer
    ): Either[ProjectedPatchOptimizerError, Unit] =
      affinePrior
        .linearize(state, output)
        .left
        .map(error => evaluation(ProjectedPatchStage.PriorLinearization, error.message))

    def value(
        state: FramedAffine[Moving, Fixed, D3]
    ): Either[ProjectedPatchOptimizerError, Double] =
      affinePrior
        .value(state)
        .left
        .map(error => evaluation(ProjectedPatchStage.PriorValue, error.message))

  private final class AffineGeometryAdapter(
      model: AffineModel3[Moving, Fixed],
      fixedProbes: AffineFixedProbeSet3[Fixed],
      metric: Array[Double]
  ) extends ProjectedPatchGeometry[FramedAffine[Moving, Fixed, D3]]:
    def writePhysicalMetric(
        state: FramedAffine[Moving, Fixed, D3],
        outputUpper: Array[Double]
    ): Either[ProjectedPatchOptimizerError, Unit] =
      model
        .validateMovingToFixed(state)
        .left
        .map(error => evaluation(ProjectedPatchStage.PhysicalMetric, error.message))
        .map(_ => copy(metric, outputUpper))

    def propose(
        state: FramedAffine[Moving, Fixed, D3],
        step: Array[Double]
    ): Either[
      ProjectedPatchOptimizerError,
      ProjectedPatchProposalResult[FramedAffine[Moving, Fixed, D3]]
    ] =
      model.propose(state, step) match
        case Right(candidate) =>
          Right(
            ProjectedPatchProposalResult.Valid(
              ProjectedPatchProposal(candidate, step.clone())
            )
          )
        case Left(_: AffineModelError.StrainIncrementOutOfBounds) =>
          Right(ProjectedPatchProposalResult.InvalidGeometry)
        case Left(_: AffineModelError.NonPositiveDeterminant) =>
          Right(ProjectedPatchProposalResult.InvalidGeometry)
        case Left(_: AffineModelError.SingularValueOutOfBounds) =>
          Right(ProjectedPatchProposalResult.InvalidGeometry)
        case Left(error) =>
          Left(evaluation(ProjectedPatchStage.Proposal, error.message))

    def maximumDisplacement(
        before: FramedAffine[Moving, Fixed, D3],
        after: FramedAffine[Moving, Fixed, D3]
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
                maximum = math.max(
                  maximum,
                  math.sqrt(dx * dx + dy * dy + dz * dz)
                )
            index += 1
          failure.toLeft(maximum)
        }

  private def physicalMetric(
      model: AffineModel3[Moving, Fixed],
      fixedProbes: AffineFixedProbeSet3[Fixed]
  ): Array[Double] =
    val metric = new Array[Double](PackedSymmetric.size(12))
    val columns = Array.fill(12)(new Array[Double](3))
    var probe = 0
    while probe < fixedProbes.size do
      val rx = fixedProbes.x(probe) - model.pivotX
      val ry = fixedProbes.y(probe) - model.pivotY
      val rz = fixedProbes.z(probe) - model.pivotZ
      columns(0)(0) = 1.0
      columns(1)(1) = 1.0
      columns(2)(2) = 1.0
      columns(3)(1) = -rz
      columns(3)(2) = ry
      columns(4)(0) = rz
      columns(4)(2) = -rx
      columns(5)(0) = -ry
      columns(5)(1) = rx
      columns(6)(0) = rx
      columns(7)(1) = ry
      columns(8)(2) = rz
      columns(9)(0) = ry
      columns(9)(1) = rx
      columns(10)(0) = rz
      columns(10)(2) = rx
      columns(11)(1) = rz
      columns(11)(2) = ry
      var row = 0
      while row < 12 do
        var column = row
        while column < 12 do
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

private[flashalign] object AffineRefinementPlan3:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      OptimizationSpace <: SampleSpace[Fixed, D3],
      SelectionSpace <: SampleSpace[Fixed, D3]
  ](
      optimization: CompiledAffineObjective3[
        Moving,
        Fixed,
        OptimizationSpace
      ],
      selection: CompiledAffineObjective3[
        Moving,
        Fixed,
        SelectionSpace
      ],
      prior: AffineStrainPrior3[Moving, Fixed],
      probes: AffineFixedProbeSet3[Fixed],
      optimizerConfig: ProjectedPatchOptimizerConfig
  ): Either[
    AffineRefinementError,
    AffineRefinementPlan3[
      Moving,
      Fixed,
      OptimizationSpace,
      SelectionSpace
    ]
  ] =
    if optimization.geometryFingerprint != selection.geometryFingerprint then
      Left(
        AffineRefinementError.GeometryMismatch(
          optimization.geometryFingerprint,
          selection.geometryFingerprint
        )
      )
    else if !(prior.model eq optimization.model) then
      Left(AffineRefinementError.PriorModelMismatch)
    else if !optimization.model.fixed.sameRuntimeOwnerAs(probes.fixed) then
      Left(
        AffineRefinementError.ProbeFrameOwnerMismatch(
          FrameOwnerDescriptor.of(optimization.model.fixed),
          FrameOwnerDescriptor.of(probes.fixed)
        )
      )
    else if optimizerConfig.parameterCount != optimization.parameterCount then
      Left(
        AffineRefinementError.Optimizer(
          ProjectedPatchOptimizerError.InvalidConfiguration(
            s"affine refinement requires ${optimization.parameterCount} parameters, got ${optimizerConfig.parameterCount}"
          )
        )
      )
    else
      Right(
        new AffineRefinementPlan3(
          optimization,
          selection,
          prior,
          probes,
          optimizerConfig
        )
      )
