package reframe4s.flashalign

/** Exact work performed by one completed rigid or affine Flashalign fit.
  *
  * Counts include the optimization, selection, and audit objectives. A fused
  * value/gradient interpolation contributes one interpolation and one gradient
  * evaluation only when the complete interpolation support is present.
  */
final case class FlashalignWorkCounts(
    uniqueInterpolations: Long,
    gradientEvaluations: Long,
    sourceVoxelReads: Long,
    patchEntryEvaluations: Long,
    patchOccurrenceEvaluations: Long,
    dataLinearizations: Int,
    priorLinearizations: Int,
    linearSolverCalls: Int,
    trialEvaluations: Int,
    earlyRejectedTrials: Int,
    acceptedSteps: Int,
    rejectedSteps: Int,
    clippedSteps: Int,
    selectionEvaluations: Int,
    auditEvaluations: Int,
    curvatureProducts: Int,
    nonlinearCoefficients: Int,
    parallelWorkers: Int
) derives CanEqual:
  private[flashalign] def +(other: FlashalignWorkCounts): FlashalignWorkCounts =
    FlashalignWorkCounts(
      uniqueInterpolations + other.uniqueInterpolations,
      gradientEvaluations + other.gradientEvaluations,
      sourceVoxelReads + other.sourceVoxelReads,
      patchEntryEvaluations + other.patchEntryEvaluations,
      patchOccurrenceEvaluations + other.patchOccurrenceEvaluations,
      dataLinearizations + other.dataLinearizations,
      priorLinearizations + other.priorLinearizations,
      linearSolverCalls + other.linearSolverCalls,
      trialEvaluations + other.trialEvaluations,
      earlyRejectedTrials + other.earlyRejectedTrials,
      acceptedSteps + other.acceptedSteps,
      rejectedSteps + other.rejectedSteps,
      clippedSteps + other.clippedSteps,
      selectionEvaluations + other.selectionEvaluations,
      auditEvaluations + other.auditEvaluations,
      curvatureProducts + other.curvatureProducts,
      nonlinearCoefficients + other.nonlinearCoefficients,
      math.max(parallelWorkers, other.parallelWorkers)
    )

object FlashalignWorkCounts:
  val Zero: FlashalignWorkCounts =
    FlashalignWorkCounts(
      uniqueInterpolations = 0L,
      gradientEvaluations = 0L,
      sourceVoxelReads = 0L,
      patchEntryEvaluations = 0L,
      patchOccurrenceEvaluations = 0L,
      dataLinearizations = 0,
      priorLinearizations = 0,
      linearSolverCalls = 0,
      trialEvaluations = 0,
      earlyRejectedTrials = 0,
      acceptedSteps = 0,
      rejectedSteps = 0,
      clippedSteps = 0,
      selectionEvaluations = 0,
      auditEvaluations = 0,
      curvatureProducts = 0,
      nonlinearCoefficients = 0,
      parallelWorkers = 1
    )

  private[flashalign] def linear(
      data: LinearDataWorkCounts,
      optimizer: ProjectedPatchWorkCounters,
      auditEvaluations: Int
  ): FlashalignWorkCounts =
    FlashalignWorkCounts(
      data.uniqueInterpolations,
      data.gradientEvaluations,
      data.sourceVoxelReads,
      data.patchEntryEvaluations,
      data.patchOccurrenceEvaluations,
      optimizer.dataLinearizations,
      optimizer.priorLinearizations,
      optimizer.linearSolverCalls,
      optimizer.trialEvaluations,
      optimizer.earlyRejectedTrials,
      optimizer.acceptedSteps,
      optimizer.rejectedSteps,
      optimizer.clippedSteps,
      optimizer.selectionEvaluations,
      auditEvaluations,
      curvatureProducts = 0,
      nonlinearCoefficients = 0,
      parallelWorkers = 1
    )

  private[flashalign] def audit(
      data: LinearDataWorkCounts
  ): FlashalignWorkCounts =
    Zero.copy(
      uniqueInterpolations = data.uniqueInterpolations,
      gradientEvaluations = data.gradientEvaluations,
      sourceVoxelReads = data.sourceVoxelReads,
      patchEntryEvaluations = data.patchEntryEvaluations,
      patchOccurrenceEvaluations = data.patchOccurrenceEvaluations,
      auditEvaluations = 1
    )

private[flashalign] final case class LinearDataWorkCounts(
    uniqueInterpolations: Long,
    gradientEvaluations: Long,
    sourceVoxelReads: Long,
    patchEntryEvaluations: Long,
    patchOccurrenceEvaluations: Long
):
  def +(other: LinearDataWorkCounts): LinearDataWorkCounts =
    LinearDataWorkCounts(
      uniqueInterpolations + other.uniqueInterpolations,
      gradientEvaluations + other.gradientEvaluations,
      sourceVoxelReads + other.sourceVoxelReads,
      patchEntryEvaluations + other.patchEntryEvaluations,
      patchOccurrenceEvaluations + other.patchOccurrenceEvaluations
    )

private[flashalign] object LinearDataWorkCounts:
  val Zero: LinearDataWorkCounts = LinearDataWorkCounts(0L, 0L, 0L, 0L, 0L)

  def from(counters: RigidObjectiveCounters): LinearDataWorkCounts =
    linearization(
      counters.uniqueInterpolations,
      counters.fullSupportInterpolations,
      counters.sourceVoxelReads,
      counters.distinctPatchEntries,
      counters.drawPatchOccurrences
    )

  def from(counters: AffineObjectiveCounters): LinearDataWorkCounts =
    linearization(
      counters.uniqueInterpolations,
      counters.fullSupportInterpolations,
      counters.sourceVoxelReads,
      counters.distinctPatchEntries,
      counters.drawPatchOccurrences
    )

  def from(counters: RigidLossOnlyCounters): LinearDataWorkCounts =
    lossOnly(
      counters.uniqueSamples,
      counters.gradientEvaluations,
      counters.sourceVoxelReads,
      counters.evaluatedPatchEntries,
      counters.evaluatedDrawOccurrences
    )

  def from(counters: AffineLossOnlyCounters): LinearDataWorkCounts =
    lossOnly(
      counters.uniqueSamples,
      counters.gradientEvaluations,
      counters.sourceVoxelReads,
      counters.evaluatedPatchEntries,
      counters.evaluatedDrawOccurrences
    )

  private def linearization(
      interpolations: Long,
      fullSupportInterpolations: Long,
      voxelReads: Long,
      patchEntries: Int,
      patchOccurrences: Int
  ): LinearDataWorkCounts =
    LinearDataWorkCounts(
      interpolations,
      fullSupportInterpolations,
      voxelReads,
      patchEntries.toLong,
      patchOccurrences.toLong
    )

  private def lossOnly(
      interpolations: Long,
      gradients: Long,
      voxelReads: Long,
      patchEntries: Int,
      patchOccurrences: Int
  ): LinearDataWorkCounts =
    LinearDataWorkCounts(
      interpolations,
      gradients,
      voxelReads,
      patchEntries.toLong,
      patchOccurrences.toLong
    )

private[flashalign] final class LinearDataWorkTracker:
  private var current = LinearDataWorkCounts.Zero

  def reset(): Unit = current = LinearDataWorkCounts.Zero

  def add(counters: RigidObjectiveCounters): Unit =
    current = current + LinearDataWorkCounts.from(counters)

  def add(counters: AffineObjectiveCounters): Unit =
    current = current + LinearDataWorkCounts.from(counters)

  def add(counters: RigidLossOnlyCounters): Unit =
    current = current + LinearDataWorkCounts.from(counters)

  def add(counters: AffineLossOnlyCounters): Unit =
    current = current + LinearDataWorkCounts.from(counters)

  def add(counts: LinearDataWorkCounts): Unit =
    current = current + counts

  def snapshot: LinearDataWorkCounts = current
