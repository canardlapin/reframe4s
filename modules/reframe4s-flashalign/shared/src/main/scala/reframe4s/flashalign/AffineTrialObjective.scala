package reframe4s.flashalign

import image4s.SampleSpace
import image4s.geometry.D3
import image4s.geometry.Frame
import reframe4s.lie.FramedAffine

private[flashalign] sealed trait AffineTrialObjectiveError derives CanEqual:
  def message: String

private[flashalign] object AffineTrialObjectiveError:
  case object WorkspacePlanMismatch extends AffineTrialObjectiveError:
    val message: String =
      "affine trial workspace belongs to a different compiled objective"

  final case class InvalidAcceptanceLimit(value: Double)
      extends AffineTrialObjectiveError:
    val message: String = s"trial acceptance limit must not be NaN, got $value"

  final case class AffineModel(error: AffineModelError)
      extends AffineTrialObjectiveError:
    val message: String = error.message

  final case class Traversal(error: AffinePatchTraversalError)
      extends AffineTrialObjectiveError:
    val message: String = error.message

  final case class Sampling(error: reframe4s.resample.ResamplingError)
      extends AffineTrialObjectiveError:
    val message: String = error.message

  final case class Registry(error: UniquePointRegistryError)
      extends AffineTrialObjectiveError:
    val message: String = error.message

  final case class Patch(error: PatchObjectiveError)
      extends AffineTrialObjectiveError:
    val message: String = error.message

  final case class NonFiniteWeightedCost(
      patchId: Int,
      weight: Double,
      loss: Double
  ) extends AffineTrialObjectiveError:
    val message: String =
      s"patch $patchId produced nonfinite weighted cost from weight $weight and loss $loss"

private[flashalign] final case class AffineTrialEvaluationId(
    objectiveId: FrozenPatchObjectiveId,
    geometryFingerprint: Long,
    movingToFixedFingerprint: Long,
    evaluationOrderFingerprint: Long
)

private[flashalign] final case class AffineLossOnlyCounters(
    uniqueSamples: Long,
    fullSupportSamples: Long,
    rejectedSamples: Long,
    sourceVoxelReads: Long,
    gradientEvaluations: Long,
    evaluatedPatchEntries: Int,
    totalPatchEntries: Int,
    evaluatedDrawOccurrences: Int,
    complete: Boolean
)

private[flashalign] sealed trait AffineTrialObjectiveValue derives CanEqual:
  def id: AffineTrialEvaluationId
  def counters: AffineLossOnlyCounters

private[flashalign] object AffineTrialObjectiveValue:
  final case class Complete(
      value: Double,
      id: AffineTrialEvaluationId,
      counters: AffineLossOnlyCounters
  ) extends AffineTrialObjectiveValue

  final case class RejectedEarly(
      conservativeLowerBound: Double,
      id: AffineTrialEvaluationId,
      counters: AffineLossOnlyCounters
  ) extends AffineTrialObjectiveValue

private[flashalign] final class AffineTrialObjectiveWorkspace3(
    private val owner: AnyRef,
    private[flashalign] val traversal: AffinePatchTraversalWorkspace3,
    private[flashalign] val uniqueValues: Array[Double],
    private[flashalign] val uniqueSupport: Array[Boolean],
    private[flashalign] val sampledUnique: Array[Boolean],
    private[flashalign] val patchValues: Array[Double],
    private[flashalign] val pendingUnique: Array[Int],
    private[flashalign] val pendingX: Array[Double],
    private[flashalign] val pendingY: Array[Double],
    private[flashalign] val pendingZ: Array[Double],
    private[flashalign] val pendingValues: Array[Double],
    private[flashalign] val pendingSupport: Array[Boolean],
    private[flashalign] val patchObjective: PatchObjectiveScratch
):
  private[flashalign] var lastRequestedSamples = 0
  private[flashalign] var lastAcceptedSamples = 0L
  private var currentWork = LinearDataWorkCounts.Zero

  private[flashalign] def belongsTo(candidate: AnyRef): Boolean =
    owner eq candidate

  private[flashalign] def resetLastWork(): Unit =
    currentWork = LinearDataWorkCounts.Zero

  private[flashalign] def recordLastWork(value: LinearDataWorkCounts): Unit =
    currentWork = value

  private[flashalign] def lastWorkSnapshot: LinearDataWorkCounts = currentWork

/** Dedicated value-only trial path for a frozen affine objective. */
private[flashalign] final class CompiledAffineTrialObjective3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3],
    S <: SampleSpace[Fixed, D3]
] private (
    val fullObjective: CompiledAffineObjective3[Moving, Fixed, S],
    val evaluationOrderFingerprint: Long
):
  private val objective = fullObjective.objective
  private val registry = fullObjective.registry
  private val traversal = fullObjective.traversal
  private val model = fullObjective.model
  private val fixedSampler = fullObjective.fixedSampler

  def newWorkspace(): AffineTrialObjectiveWorkspace3 =
    new AffineTrialObjectiveWorkspace3(
      this,
      traversal.newWorkspace(),
      new Array[Double](registry.uniquePointCount),
      new Array[Boolean](registry.uniquePointCount),
      new Array[Boolean](registry.uniquePointCount),
      new Array[Double](registry.patchSampleCount),
      new Array[Int](registry.patchSampleCount),
      new Array[Double](registry.patchSampleCount),
      new Array[Double](registry.patchSampleCount),
      new Array[Double](registry.patchSampleCount),
      new Array[Double](registry.patchSampleCount),
      new Array[Boolean](registry.patchSampleCount),
      PatchObjectiveScratch.create
    )

  def evaluate(
      movingToFixed: FramedAffine[Moving, Fixed, D3],
      acceptanceLimit: Double,
      workspace: AffineTrialObjectiveWorkspace3
  ): Either[AffineTrialObjectiveError, AffineTrialObjectiveValue] =
    if !workspace.belongsTo(this) then
      Left(AffineTrialObjectiveError.WorkspacePlanMismatch)
    else if acceptanceLimit.isNaN then
      Left(AffineTrialObjectiveError.InvalidAcceptanceLimit(acceptanceLimit))
    else
      workspace.resetLastWork()
      for
        _ <- model
          .validateMovingToFixed(movingToFixed)
          .left
          .map(AffineTrialObjectiveError.AffineModel.apply)
        _ <- traversal
          .transform(movingToFixed.operator, workspace.traversal)
          .left
          .map(AffineTrialObjectiveError.Traversal.apply)
        result <- accumulate(movingToFixed, acceptanceLimit, workspace)
      yield result

  private def accumulate(
      movingToFixed: FramedAffine[Moving, Fixed, D3],
      acceptanceLimit: Double,
      workspace: AffineTrialObjectiveWorkspace3
  ): Either[AffineTrialObjectiveError, AffineTrialObjectiveValue] =
    val id = evaluationId(movingToFixed)
    java.util.Arrays.fill(workspace.sampledUnique, false)
    var total = 0.0
    var requestedSamples = 0L
    var fullSupportSamples = 0L
    var rejectedSamples = 0L
    var sourceVoxelReads = 0L
    var evaluatedDrawOccurrences = 0
    var entryIndex = 0
    while entryIndex < objective.samples.entries.size do
      val entry = objective.samples.entries(entryIndex)
      sampleUnseenPoints(entryIndex, workspace)
      requestedSamples += workspace.lastRequestedSamples.toLong
      fullSupportSamples += workspace.lastAcceptedSamples
      rejectedSamples +=
        workspace.lastRequestedSamples.toLong - workspace.lastAcceptedSamples
      sourceVoxelReads += workspace.lastAcceptedSamples * 8L
      val attemptedDrawOccurrences =
        evaluatedDrawOccurrences + entry.multiplicity
      workspace.recordLastWork(
        LinearDataWorkCounts(
          uniqueInterpolations = requestedSamples,
          gradientEvaluations = 0L,
          sourceVoxelReads = sourceVoxelReads,
          patchEntryEvaluations = entryIndex.toLong + 1L,
          patchOccurrenceEvaluations = attemptedDrawOccurrences.toLong
        )
      )
      registry.gatherUnchecked(
        workspace.uniqueValues,
        entryIndex,
        workspace.patchValues
      )
      var completeSupport = true
      var sample = 0
      while sample < registry.patchSampleCount && completeSupport do
        if !workspace.uniqueSupport(registry.uniqueIndex(entryIndex, sample)) then
          completeSupport = false
        sample += 1
      val objectiveError = objective.evaluateInto(
        entry,
        workspace.patchValues,
        completeSupport,
        workspace.patchObjective
      )
      if objectiveError != null then
        return Left(
          AffineTrialObjectiveError.Patch(
            objectiveError.asInstanceOf[PatchObjectiveError]
          )
        )
      val loss = workspace.patchObjective.loss
      val weighted = entry.objectiveWeight * loss
      if !weighted.isFinite then
        return Left(
          AffineTrialObjectiveError.NonFiniteWeightedCost(
            entry.patch.id,
            entry.objectiveWeight,
            loss
          )
        )
      total += weighted
      evaluatedDrawOccurrences = attemptedDrawOccurrences
      entryIndex += 1
      val lowerBound = conservativeLowerBound(total, entryIndex)
      if lowerBound > acceptanceLimit then
        return Right(
          AffineTrialObjectiveValue.RejectedEarly(
            lowerBound,
            id,
            counters(
              requestedSamples,
              fullSupportSamples,
              rejectedSamples,
              sourceVoxelReads,
              entryIndex,
              evaluatedDrawOccurrences,
              complete = false
            )
          )
        )
    Right(
      AffineTrialObjectiveValue.Complete(
        total,
        id,
        counters(
          requestedSamples,
          fullSupportSamples,
          rejectedSamples,
          sourceVoxelReads,
          entryIndex,
          evaluatedDrawOccurrences,
          complete = true
        )
      )
    )

  private def counters(
      requestedSamples: Long,
      fullSupportSamples: Long,
      rejectedSamples: Long,
      sourceVoxelReads: Long,
      evaluatedPatches: Int,
      evaluatedDrawOccurrences: Int,
      complete: Boolean
  ): AffineLossOnlyCounters =
    AffineLossOnlyCounters(
      requestedSamples,
      fullSupportSamples,
      rejectedSamples,
      sourceVoxelReads,
      gradientEvaluations = 0L,
      evaluatedPatches,
      objective.samples.entries.size,
      evaluatedDrawOccurrences,
      complete
    )

  private def sampleUnseenPoints(
      patchEntry: Int,
      workspace: AffineTrialObjectiveWorkspace3
  ): Unit =
    var pendingCount = 0
    var sample = 0
    while sample < registry.patchSampleCount do
      val unique = registry.uniqueIndex(patchEntry, sample)
      if !workspace.sampledUnique(unique) then
        workspace.pendingUnique(pendingCount) = unique
        workspace.pendingX(pendingCount) = workspace.traversal.transformedX(unique)
        workspace.pendingY(pendingCount) = workspace.traversal.transformedY(unique)
        workspace.pendingZ(pendingCount) = workspace.traversal.transformedZ(unique)
        pendingCount += 1
      sample += 1
    workspace.lastRequestedSamples = pendingCount
    workspace.lastAcceptedSamples =
      fixedSampler.sampleValuesFullSupportWorldUnchecked(
        workspace.pendingX,
        workspace.pendingY,
        workspace.pendingZ,
        pendingCount,
        workspace.pendingValues,
        workspace.pendingSupport
      )
    var pending = 0
    while pending < pendingCount do
      val unique = workspace.pendingUnique(pending)
      workspace.uniqueValues(unique) = workspace.pendingValues(pending)
      workspace.uniqueSupport(unique) = workspace.pendingSupport(pending)
      workspace.sampledUnique(unique) = true
      pending += 1

  private def evaluationId(
      movingToFixed: FramedAffine[Moving, Fixed, D3]
  ): AffineTrialEvaluationId =
    AffineTrialEvaluationId(
      objective.id,
      fullObjective.geometryFingerprint,
      fingerprint(movingToFixed.operator.rowMajor),
      evaluationOrderFingerprint
    )

  private def conservativeLowerBound(total: Double, terms: Int): Double =
    if total.isInfinite then total
    else
      val unitRoundoff = 1.1102230246251565e-16
      val operations = 2 * terms + 1
      val product = operations.toDouble * unitRoundoff
      val gamma = product / (1.0 - product)
      val scaled = total / (1.0 + gamma)
      math.max(
        0.0,
        java.lang.Math.nextAfter(scaled, Double.NegativeInfinity)
      )

  private def fingerprint(values: Vector[Double]): Long =
    values.foldLeft(0xbb67ae8584caa73bL) { (state, value) =>
      mix64(state ^ java.lang.Double.doubleToLongBits(value))
    }

  private def mix64(value: Long): Long =
    var z = value
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)

private[flashalign] object CompiledAffineTrialObjective3:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      S <: SampleSpace[Fixed, D3]
  ](
      fullObjective: CompiledAffineObjective3[Moving, Fixed, S]
  ): CompiledAffineTrialObjective3[Moving, Fixed, S] =
    val fingerprint = fullObjective.objective.samples.entries.foldLeft(
      0x3c6ef372fe94f82bL
    ) { (state, entry) =>
      mix64(
        state ^ entry.patch.id.toLong ^
          java.lang.Double.doubleToLongBits(entry.objectiveWeight)
      )
    }
    new CompiledAffineTrialObjective3(fullObjective, fingerprint)

  private def mix64(value: Long): Long =
    var z = value
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)
