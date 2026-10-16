package reframe4s.flashalign

import image4s.SampleSpace
import image4s.geometry.D3
import image4s.geometry.Frame
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.lie.Rigid3
import reframe4s.resample.LinearValueGradientSampler3
import reframe4s.resample.ResamplingError
import reframe4s.resample.SparseSamplingCounters
import reframe4s.resample.SparseValueGradientBuffer3

private[flashalign] enum RigidObjectiveExecutionShape derives CanEqual:
  case SpecializedSixParameterStreaming

private[flashalign] sealed trait RigidObjectiveLinearizationError
    derives CanEqual:
  def message: String

private[flashalign] object RigidObjectiveLinearizationError:
  case object WorkspacePlanMismatch extends RigidObjectiveLinearizationError:
    val message: String =
      "rigid linearization workspace belongs to a different compiled objective"

  final case class FixedFrameOwnerMismatch(
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends RigidObjectiveLinearizationError:
    val message: String =
      s"fixed sampler owner $actual does not match rigid model owner $expected"

  final case class PatchSampleCountMismatch(
      patchId: Int,
      expected: Int,
      actual: Int
  ) extends RigidObjectiveLinearizationError:
    val message: String =
      s"patch $patchId has $actual moving samples but the stencil has $expected"

  final case class RigidModel(error: RigidModelError)
      extends RigidObjectiveLinearizationError:
    val message: String = error.message

  final case class Traversal(error: AffinePatchTraversalError)
      extends RigidObjectiveLinearizationError:
    val message: String = error.message

  final case class Sampling(error: AffinePatchSamplingError)
      extends RigidObjectiveLinearizationError:
    val message: String = error.message

  final case class Resampling(error: ResamplingError)
      extends RigidObjectiveLinearizationError:
    val message: String = error.message

  final case class Patch(error: PatchObjectiveError)
      extends RigidObjectiveLinearizationError:
    val message: String = error.message

  final case class Statistics(error: PatchLinearizationError)
      extends RigidObjectiveLinearizationError:
    val message: String = error.message

  final case class ActivePatchLostLinearization(patchId: Int)
      extends RigidObjectiveLinearizationError:
    val message: String =
      s"active objective patch $patchId produced an inactive linearization"

  final case class NonFiniteAggregate(
      component: String,
      index: Int,
      value: Double
  ) extends RigidObjectiveLinearizationError:
    val message: String =
      s"rigid aggregate $component value $index must be finite, got $value"

private[flashalign] final case class RigidLinearizationCacheId(
    objectiveId: FrozenPatchObjectiveId,
    geometryFingerprint: Long,
    movingToFixedFingerprint: Long
)

private[flashalign] final case class RigidObjectiveCounters(
    distinctPatchEntries: Int,
    drawPatchOccurrences: Int,
    uniqueInterpolations: Long,
    fullSupportInterpolations: Long,
    rejectedInterpolations: Long,
    sourceVoxelReads: Long,
    activePatches: Int,
    invalidSupportPatches: Int,
    invalidContrastPatches: Int,
    objectiveWeight: Double,
    activeObjectiveWeight: Double,
    invalidSupportWeight: Double,
    invalidContrastWeight: Double,
    posteriorOutlierWeight: Double,
    posteriorPositiveWeight: Double,
    posteriorNegativeWeight: Double,
    centeredStatisticRecomputations: Long
)

private[flashalign] final case class RigidDataInformationDiagnostics(
    gradientEuclideanNorm: Double,
    curvatureTrace: Double,
    curvatureFrobeniusNorm: Double,
    minimumDiagonal: Double,
    maximumDiagonal: Double,
    diagonal: Vector[Double]
)

/**
 * Workspace-backed six-parameter data objective. Its arrays are overwritten
 * by the next call made with the owning workspace.
 */
private[flashalign] final class RigidObjectiveLinearizationResult private (
    val gradient: Array[Double],
    val curvatureUpper: Array[Double],
    private var currentObjective: Double,
    private var currentCacheId: RigidLinearizationCacheId,
    private var currentCounters: RigidObjectiveCounters,
    private var currentInformation: RigidDataInformationDiagnostics
):
  def objective: Double = currentObjective
  def cacheId: RigidLinearizationCacheId = currentCacheId
  def counters: RigidObjectiveCounters = currentCounters
  def dataInformation: RigidDataInformationDiagnostics = currentInformation

  def curvature(row: Int, column: Int): Double =
    curvatureUpper(PackedSymmetric.index(row, column))

  private[flashalign] def update(
      objective: Double,
      cacheId: RigidLinearizationCacheId,
      counters: RigidObjectiveCounters,
      information: RigidDataInformationDiagnostics
  ): Unit =
    currentObjective = objective
    currentCacheId = cacheId
    currentCounters = counters
    currentInformation = information

private[flashalign] object RigidObjectiveLinearizationResult:
  def create(
      objectiveId: FrozenPatchObjectiveId,
      geometryFingerprint: Long
  ): RigidObjectiveLinearizationResult =
    new RigidObjectiveLinearizationResult(
      new Array[Double](6),
      new Array[Double](PackedSymmetric.size(6)),
      0.0,
      RigidLinearizationCacheId(objectiveId, geometryFingerprint, 0L),
      RigidObjectiveCounters(
        0,
        0,
        0L,
        0L,
        0L,
        0L,
        0,
        0,
        0,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        0.0,
        0L
      ),
      RigidDataInformationDiagnostics(
        0.0,
        0.00,
        0.0,
        0.0,
        0.0,
        Vector.fill(6)(0.0)
      )
    )

private[flashalign] final class RigidPatchSampleSource private (
    val sampleCount: Int,
    val parameterCount: Int,
    private val values: Array[Double],
    private val jacobian: Array[Double]
) extends PatchSampleSource:
  def value(sample: Int): Double = values(sample)

  def derivative(sample: Int, parameter: Int): Double =
    jacobian(sample * parameterCount + parameter)

  private[flashalign] def bind[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      patchEntry: Int,
      registry: UniquePointRegistry3,
      model: RigidModel3[Moving, Fixed],
      transformed: AffinePatchTraversalWorkspace3,
      sampled: SparseValueGradientBuffer3
  ): Boolean =
    var completeSupport = true
    var sample = 0
    while sample < sampleCount do
      val unique = registry.uniqueIndex(patchEntry, sample)
      values(sample) = sampled.values(unique)
      if !sampled.fullSupport(unique) then completeSupport = false
      sample += 1
    if completeSupport then
      sample = 0
      while sample < sampleCount do
        val unique = registry.uniqueIndex(patchEntry, sample)
        model.writeIntensityJacobianUnchecked(
          transformed.transformedX(unique),
          transformed.transformedY(unique),
          transformed.transformedZ(unique),
          sampled.gradientX(unique),
          sampled.gradientY(unique),
          sampled.gradientZ(unique),
          jacobian,
          sample * parameterCount
        )
        sample += 1
    completeSupport

private[flashalign] object RigidPatchSampleSource:
  def create(sampleCount: Int): RigidPatchSampleSource =
    new RigidPatchSampleSource(
      sampleCount,
      parameterCount = 6,
      new Array[Double](sampleCount),
      new Array[Double](sampleCount * 6)
    )

private[flashalign] final class RigidObjectiveLinearizationWorkspace3 private[flashalign] (
    private val owner: AnyRef,
    private[flashalign] val traversal: AffinePatchTraversalWorkspace3,
    private[flashalign] val sampled: SparseValueGradientBuffer3,
    private[flashalign] val patchSource: RigidPatchSampleSource,
    private[flashalign] val patchLinearization: PatchLinearizationWorkspace,
    private[flashalign] val patchObjective: PatchObjectiveScratch,
    private[flashalign] val result: RigidObjectiveLinearizationResult
):
  private var currentWork = LinearDataWorkCounts.Zero

  private[flashalign] def belongsTo(candidate: AnyRef): Boolean =
    owner eq candidate

  private[flashalign] def resetLastWork(): Unit =
    sampled.resetCounters()
    currentWork = LinearDataWorkCounts.Zero

  private[flashalign] def recordLastWork(value: LinearDataWorkCounts): Unit =
    currentWork = value

  private[flashalign] def lastWorkSnapshot: LinearDataWorkCounts =
    val sampling = sampled.counters
    currentWork.copy(
      uniqueInterpolations = math.max(
        currentWork.uniqueInterpolations,
        sampling.requestedSamples
      ),
      gradientEvaluations = math.max(
        currentWork.gradientEvaluations,
        sampling.fullSupportSamples
      ),
      sourceVoxelReads = math.max(
        currentWork.sourceVoxelReads,
        sampling.sourceVoxelReads
      )
    )

/**
 * Compiled rigid objective for one frozen patch draw and one fixed sampler.
 *
 * This path has exactly six parameters. It owns no deformation basis, dense
 * warp, Krylov state, or generic geometry-model dispatch.
 */
private[flashalign] final class CompiledRigidObjective3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3],
    S <: SampleSpace[Fixed, D3]
] private (
    val model: RigidModel3[Moving, Fixed],
    val objective: FrozenPatchObjective3,
    val registry: UniquePointRegistry3,
    val traversal: CompiledAffinePatchTraversal3,
    val fixedSampler: LinearValueGradientSampler3[Fixed, S],
    val geometryFingerprint: Long
):
  val parameterCount: Int = 6
  val executionShape: RigidObjectiveExecutionShape =
    RigidObjectiveExecutionShape.SpecializedSixParameterStreaming

  def newWorkspace(): Either[
    RigidObjectiveLinearizationError,
    RigidObjectiveLinearizationWorkspace3
  ] =
    for
      sampled <- SparseValueGradientBuffer3
        .create(registry.uniquePointCount)
        .left
        .map(RigidObjectiveLinearizationError.Resampling.apply)
      patchLinearization <- PatchLinearizationWorkspace
        .create(parameterCount)
        .left
        .map(RigidObjectiveLinearizationError.Statistics.apply)
    yield
      new RigidObjectiveLinearizationWorkspace3(
        this,
        traversal.newWorkspace(),
        sampled,
        RigidPatchSampleSource.create(registry.patchSampleCount),
        patchLinearization,
        PatchObjectiveScratch.create,
        RigidObjectiveLinearizationResult.create(
          objective.id,
          geometryFingerprint
        )
      )

  def linearize(
      movingToFixed: Rigid3[Moving, Fixed],
      workspace: RigidObjectiveLinearizationWorkspace3
  ): Either[
    RigidObjectiveLinearizationError,
    RigidObjectiveLinearizationResult
  ] =
    if !workspace.belongsTo(this) then
      Left(RigidObjectiveLinearizationError.WorkspacePlanMismatch)
    else
      workspace.resetLastWork()
      for
        _ <- model
          .validateMovingToFixed(movingToFixed)
          .left
          .map(RigidObjectiveLinearizationError.RigidModel.apply)
        _ <- traversal
          .transform(movingToFixed.operator, workspace.traversal)
          .left
          .map(RigidObjectiveLinearizationError.Traversal.apply)
        sampling <- traversal
          .sampleFixed(
            fixedSampler,
            workspace.traversal,
            workspace.sampled
          )
          .left
          .map(RigidObjectiveLinearizationError.Sampling.apply)
        _ = workspace.recordLastWork(
          LinearDataWorkCounts(
            sampling.requestedSamples,
            sampling.fullSupportSamples,
            sampling.sourceVoxelReads,
            0L,
            0L
          )
        )
        result <- accumulate(movingToFixed, sampling, workspace)
      yield result

  private def accumulate(
      movingToFixed: Rigid3[Moving, Fixed],
      sampling: SparseSamplingCounters,
      workspace: RigidObjectiveLinearizationWorkspace3
  ): Either[
    RigidObjectiveLinearizationError,
    RigidObjectiveLinearizationResult
  ] =
    java.util.Arrays.fill(workspace.result.gradient, 0.0)
    java.util.Arrays.fill(workspace.result.curvatureUpper, 0.0)
    val diagnosticsBefore = workspace.patchLinearization.diagnostics
    var totalObjective = 0.0
    var activePatches = 0
    var invalidSupportPatches = 0
    var invalidContrastPatches = 0
    var objectiveWeight = 0.0
    var activeWeight = 0.0
    var invalidSupportWeight = 0.0
    var invalidContrastWeight = 0.0
    var posteriorOutlierWeight = 0.0
    var posteriorPositiveWeight = 0.0
    var posteriorNegativeWeight = 0.0
    var evaluatedDrawOccurrences = 0
    var entryIndex = 0
    while entryIndex < objective.samples.entries.size do
      val entry = objective.samples.entries(entryIndex)
      val attemptedDrawOccurrences =
        evaluatedDrawOccurrences + entry.multiplicity
      workspace.recordLastWork(
        LinearDataWorkCounts(
          sampling.requestedSamples,
          sampling.fullSupportSamples,
          sampling.sourceVoxelReads,
          entryIndex.toLong + 1L,
          attemptedDrawOccurrences.toLong
        )
      )
      val completeSupport = workspace.patchSource.bind(
        entryIndex,
        registry,
        model,
        workspace.traversal,
        workspace.sampled
      )
      val linear = workspace.patchLinearization.result
      if !completeSupport then
        objective.invalidInto(
          PatchInvalidReason.IncompleteInterpolationSupport,
          workspace.patchObjective
        )
      else
        val statisticsError = PatchLinearization.computeInto(
          entry.patch.moving,
          workspace.patchSource,
          objective.minimumContrastEnergy,
          workspace.patchLinearization
        )
        if statisticsError != null then
          return Left(
            RigidObjectiveLinearizationError.Statistics(
              statisticsError.asInstanceOf[PatchLinearizationError]
            )
          )
        if !linear.active then
          objective.invalidInto(
            PatchInvalidReason.InsufficientContrast,
            workspace.patchObjective
          )
        else
          val objectiveError = objective.evaluateCorrelationInto(
            linear.correlation,
            workspace.patchObjective
          )
          if objectiveError != null then
            return Left(
              RigidObjectiveLinearizationError.Patch(
                objectiveError.asInstanceOf[PatchObjectiveError]
              )
            )
      val value = workspace.patchObjective
      val weight = entry.objectiveWeight
      totalObjective += weight * value.loss
      objectiveWeight += weight
      posteriorOutlierWeight += weight * value.posteriorOutlier
      posteriorPositiveWeight += weight * value.posteriorPositive
      posteriorNegativeWeight += weight * value.posteriorNegative
      value.invalidReason match
        case PatchInvalidReason.IncompleteInterpolationSupport =>
          invalidSupportPatches += 1
          invalidSupportWeight += weight
        case PatchInvalidReason.InsufficientContrast =>
          invalidContrastPatches += 1
          invalidContrastWeight += weight
        case null =>
          activePatches += 1
          activeWeight += weight
          var parameter = 0
          while parameter < parameterCount do
            workspace.result.gradient(parameter) +=
              weight * -value.signedWeight * linear.jtu(parameter)
            var other = 0
            while other <= parameter do
              val packed = PackedSymmetric.index(other, parameter)
              workspace.result.curvatureUpper(packed) +=
                weight * value.inlierWeight * linear.jtjUpper(packed)
              other += 1
            parameter += 1
      evaluatedDrawOccurrences = attemptedDrawOccurrences
      entryIndex += 1
    validateAggregate(
      totalObjective,
      workspace.result.gradient,
      workspace.result.curvatureUpper
    ).map { _ =>
      val diagnosticsAfter = workspace.patchLinearization.diagnostics
      val counters =
        RigidObjectiveCounters(
          registry.patchEntryCount,
          registry.diagnostics.drawPatchOccurrences,
          sampling.requestedSamples,
          sampling.fullSupportSamples,
          sampling.rejectedSamples,
          sampling.sourceVoxelReads,
          activePatches,
          invalidSupportPatches,
          invalidContrastPatches,
          objectiveWeight,
          activeWeight,
          invalidSupportWeight,
          invalidContrastWeight,
          posteriorOutlierWeight,
          posteriorPositiveWeight,
          posteriorNegativeWeight,
          diagnosticsAfter.centeredRecomputations -
            diagnosticsBefore.centeredRecomputations
        )
      val information = informationOf(
        workspace.result.gradient,
        workspace.result.curvatureUpper
      )
      val cacheId =
        RigidLinearizationCacheId(
          objective.id,
          geometryFingerprint,
          transformFingerprint(movingToFixed)
        )
      workspace.result.update(
        totalObjective,
        cacheId,
        counters,
        information
      )
      workspace.result
    }

  private def validateAggregate(
      objectiveValue: Double,
      gradient: Array[Double],
      curvature: Array[Double]
  ): Either[RigidObjectiveLinearizationError, Unit] =
    if !objectiveValue.isFinite then
      Left(
        RigidObjectiveLinearizationError.NonFiniteAggregate(
          "objective",
          0,
          objectiveValue
        )
      )
    else
      firstNonFinite(gradient) match
        case Some((index, value)) =>
          Left(
            RigidObjectiveLinearizationError.NonFiniteAggregate(
              "gradient",
              index,
              value
            )
          )
        case None =>
          firstNonFinite(curvature) match
            case Some((index, value)) =>
              Left(
                RigidObjectiveLinearizationError.NonFiniteAggregate(
                  "curvature",
                  index,
                  value
                )
              )
            case None => Right(())

  private def informationOf(
      gradient: Array[Double],
      curvature: Array[Double]
  ): RigidDataInformationDiagnostics =
    var gradientSquares = 0.0
    var trace = 0.0
    var frobeniusSquares = 0.0
    var minimumDiagonal = Double.PositiveInfinity
    var maximumDiagonal = Double.NegativeInfinity
    val diagonal = Vector.newBuilder[Double]
    var row = 0
    while row < parameterCount do
      gradientSquares += gradient(row) * gradient(row)
      val value = curvature(PackedSymmetric.index(row, row))
      trace += value
      minimumDiagonal = math.min(minimumDiagonal, value)
      maximumDiagonal = math.max(maximumDiagonal, value)
      diagonal += value
      var column = 0
      while column < parameterCount do
        val element = curvature(PackedSymmetric.index(row, column))
        frobeniusSquares += element * element
        column += 1
      row += 1
    RigidDataInformationDiagnostics(
      math.sqrt(gradientSquares),
      trace,
      math.sqrt(frobeniusSquares),
      minimumDiagonal,
      maximumDiagonal,
      diagonal.result()
    )

  private def transformFingerprint(
      movingToFixed: Rigid3[Moving, Fixed]
  ): Long =
    movingToFixed.operator.rowMajor.foldLeft(0x243f6a8885a308d3L) {
      (state, value) =>
        mix64(state ^ java.lang.Double.doubleToLongBits(value))
    }

  private def firstNonFinite(
      values: Array[Double]
  ): Option[(Int, Double)] =
    var index = 0
    while index < values.length do
      if !values(index).isFinite then return Some(index -> values(index))
      index += 1
    None

  private def mix64(value: Long): Long =
    var z = value
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)

private[flashalign] object CompiledRigidObjective3:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      S <: SampleSpace[Fixed, D3]
  ](
      model: RigidModel3[Moving, Fixed],
      objective: FrozenPatchObjective3,
      stencil: PhysicalStencil3,
      fixedSampler: LinearValueGradientSampler3[Fixed, S]
  ): Either[
    RigidObjectiveLinearizationError,
    CompiledRigidObjective3[Moving, Fixed, S]
  ] =
    if !model.fixed.sameRuntimeOwnerAs(fixedSampler.image.frame) then
      Left(
        RigidObjectiveLinearizationError.FixedFrameOwnerMismatch(
          FrameOwnerDescriptor.of(model.fixed),
          FrameOwnerDescriptor.of(fixedSampler.image.frame)
        )
      )
    else
      objective.samples.entries.collectFirst {
        case entry if entry.patch.moving.size != stencil.sampleCount =>
          RigidObjectiveLinearizationError.PatchSampleCountMismatch(
            entry.patch.id,
            stencil.sampleCount,
            entry.patch.moving.size
          )
      } match
        case Some(error) => Left(error)
        case None =>
          val registry = UniquePointRegistry3.compile(
            objective.samples,
            stencil
          )
          val traversal = CompiledAffinePatchTraversal3.compile(registry)
          val geometryFingerprint = geometryFingerprintOf(model, stencil)
          Right(
            new CompiledRigidObjective3(
              model,
              objective,
              registry,
              traversal,
              fixedSampler,
              geometryFingerprint
            )
          )

  private def geometryFingerprintOf[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      model: RigidModel3[Moving, Fixed],
      stencil: PhysicalStencil3
  ): Long =
    val values = Vector(
      model.pivotX,
      model.pivotY,
      model.pivotZ,
      stencil.spacingMillimetres,
      stencil.sampleCount.toDouble,
      6.0
    )
    values.foldLeft(0x13198a2e03707344L) { (state, value) =>
      mix64(state ^ java.lang.Double.doubleToLongBits(value))
    }

  private def mix64(value: Long): Long =
    var z = value
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)
