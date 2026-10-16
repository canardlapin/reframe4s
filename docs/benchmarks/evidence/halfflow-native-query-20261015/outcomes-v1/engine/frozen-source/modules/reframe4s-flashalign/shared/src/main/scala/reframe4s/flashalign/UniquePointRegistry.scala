package reframe4s.flashalign

import image4s.SampleSpace
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import reframe4s.resample.LinearValueGradientSampler3
import reframe4s.resample.ResamplingError
import reframe4s.resample.SparseSamplingCounters
import reframe4s.resample.SparseValueGradientBuffer3

import scala.collection.mutable

private[flashalign] sealed trait UniquePointRegistryError derives CanEqual:
  def message: String

private[flashalign] object UniquePointRegistryError:
  final case class PatchBufferSizeMismatch(expected: Int, actual: Int)
      extends UniquePointRegistryError:
    val message: String =
      s"patch gather/scatter requires $expected values, got $actual"

  final case class UniqueBufferSizeMismatch(expected: Int, actual: Int)
      extends UniquePointRegistryError:
    val message: String =
      s"unique-point gather/scatter requires at least $expected values, got $actual"

private[flashalign] final case class UniquePointRegistryDiagnostics(
    distinctPatchEntries: Int,
    drawPatchOccurrences: Int,
    distinctPatchSampleReferences: Int,
    drawSampleReferences: Long,
    uniquePoints: Int,
    deduplicatedReferences: Int,
    retainedPrimitiveBytes: Long,
    setupNanoseconds: Long
)

/**
 * Exact moving-space sample registry for one immutable sample set.
 *
 * Equality uses the bit representation of coordinates constructed from a
 * patch centre and its shared stencil offset. No transformed or approximately
 * equal coordinate is ever merged.
 */
private[flashalign] final class UniquePointRegistry3 private (
    val samples: PatchSampleSet3,
    val stencil: PhysicalStencil3,
    private[flashalign] val movingX: Array[Double],
    private[flashalign] val movingY: Array[Double],
    private[flashalign] val movingZ: Array[Double],
    private val patchToUnique: Array[Int],
    private[flashalign] val ownerPatchEntry: Array[Int],
    private[flashalign] val ownerStencilSample: Array[Int],
    val diagnostics: UniquePointRegistryDiagnostics
):
  val uniquePointCount: Int = movingX.length
  val patchEntryCount: Int = samples.entries.size
  val patchSampleCount: Int = stencil.sampleCount

  def uniqueIndex(patchEntry: Int, sample: Int): Int =
    patchToUnique(patchEntry * patchSampleCount + sample)

  def gather(
      uniqueValues: Array[Double],
      patchEntry: Int,
      destination: Array[Double]
  ): Either[UniquePointRegistryError, Unit] =
    if destination.length != patchSampleCount then
      Left(
        UniquePointRegistryError.PatchBufferSizeMismatch(
          patchSampleCount,
          destination.length
        )
      )
    else if uniqueValues.length < uniquePointCount then
      Left(
        UniquePointRegistryError.UniqueBufferSizeMismatch(
          uniquePointCount,
          uniqueValues.length
        )
      )
    else
      gatherUnchecked(uniqueValues, patchEntry, destination)
      Right(())

  /** Allocation-free gather after compile-time buffer validation. */
  private[flashalign] def gatherUnchecked(
      uniqueValues: Array[Double],
      patchEntry: Int,
      destination: Array[Double]
  ): Unit =
    var sample = 0
    while sample < patchSampleCount do
      destination(sample) = uniqueValues(uniqueIndex(patchEntry, sample))
      sample += 1

  def scatterAdd(
      patchValues: Array[Double],
      patchEntry: Int,
      uniqueValues: Array[Double],
      includeDrawMultiplicity: Boolean
  ): Either[UniquePointRegistryError, Unit] =
    if patchValues.length != patchSampleCount then
      Left(
        UniquePointRegistryError.PatchBufferSizeMismatch(
          patchSampleCount,
          patchValues.length
        )
      )
    else if uniqueValues.length < uniquePointCount then
      Left(
        UniquePointRegistryError.UniqueBufferSizeMismatch(
          uniquePointCount,
          uniqueValues.length
        )
      )
    else
      val multiplier =
        if includeDrawMultiplicity then
          samples.entries(patchEntry).multiplicity.toDouble
        else 1.0
      var sample = 0
      while sample < patchSampleCount do
        val unique = uniqueIndex(patchEntry, sample)
        uniqueValues(unique) += multiplier * patchValues(sample)
        sample += 1
      Right(())

private[flashalign] object UniquePointRegistry3:
  def compile(
      samples: PatchSampleSet3,
      stencil: PhysicalStencil3
  ): UniquePointRegistry3 =
    val started = System.nanoTime()
    val indexByKey = mutable.HashMap.empty[ExactWorldPointKey3, Int]
    val x = mutable.ArrayBuffer.empty[Double]
    val y = mutable.ArrayBuffer.empty[Double]
    val z = mutable.ArrayBuffer.empty[Double]
    val ownerEntry = mutable.ArrayBuffer.empty[Int]
    val ownerSample = mutable.ArrayBuffer.empty[Int]
    val references = samples.entries.size * stencil.sampleCount
    val patchToUnique = new Array[Int](references)
    var entryIndex = 0
    while entryIndex < samples.entries.size do
      val patch = samples.entries(entryIndex).patch
      var sample = 0
      while sample < stencil.sampleCount do
        val key = patch.samplePointKey(stencil, sample)
        val unique =
          indexByKey.get(key) match
            case Some(existing) => existing
            case None =>
              val created = x.size
              indexByKey.update(key, created)
              x += java.lang.Double.longBitsToDouble(key.xBits)
              y += java.lang.Double.longBitsToDouble(key.yBits)
              z += java.lang.Double.longBitsToDouble(key.zBits)
              ownerEntry += entryIndex
              ownerSample += sample
              created
        patchToUnique(entryIndex * stencil.sampleCount + sample) = unique
        sample += 1
      entryIndex += 1
    val movingX = x.toArray
    val movingY = y.toArray
    val movingZ = z.toArray
    val owners = ownerEntry.toArray
    val ownerSamples = ownerSample.toArray
    val drawOccurrences = samples.entries.map(_.multiplicity).sum
    val retainedBytes =
      movingX.length.toLong * 3L * 8L +
        patchToUnique.length.toLong * 4L +
        owners.length.toLong * 4L +
        ownerSamples.length.toLong * 4L
    val diagnostics =
      UniquePointRegistryDiagnostics(
        samples.entries.size,
        drawOccurrences,
        references,
        drawOccurrences.toLong * stencil.sampleCount.toLong,
        movingX.length,
        references - movingX.length,
        retainedBytes,
        System.nanoTime() - started
      )
    new UniquePointRegistry3(
      samples,
      stencil,
      movingX,
      movingY,
      movingZ,
      patchToUnique,
      owners,
      ownerSamples,
      diagnostics
    )

private[flashalign] enum AffinePatchTraversalMode derives CanEqual:
  case TransformUniquePoints
  case TransformPatchCentersAndSharedOffsets

private[flashalign] sealed trait AffinePatchTraversalError derives CanEqual:
  def message: String

private[flashalign] object AffinePatchTraversalError:
  case object WorkspacePlanMismatch extends AffinePatchTraversalError:
    val message: String =
      "affine patch traversal workspace belongs to a different plan"

private[flashalign] final case class AffinePatchTraversalDiagnostics(
    mode: AffinePatchTraversalMode,
    uniqueInterpolations: Int,
    affinePointTransforms: Int,
    affineLinearOffsetTransforms: Int,
    vectorAdditions: Int,
    estimatedArithmeticOperations: Long
)

/** Caller-owned transformed coordinates and reusable affine scratch. */
private[flashalign] final class AffinePatchTraversalWorkspace3 private[flashalign] (
    private val owner: AnyRef,
    val transformedX: Array[Double],
    val transformedY: Array[Double],
    val transformedZ: Array[Double],
    private[flashalign] val warpedOffsetX: Array[Double],
    private[flashalign] val warpedOffsetY: Array[Double],
    private[flashalign] val warpedOffsetZ: Array[Double],
    private[flashalign] val transformedCenterX: Array[Double],
    private[flashalign] val transformedCenterY: Array[Double],
    private[flashalign] val transformedCenterZ: Array[Double]
):
  private[flashalign] def belongsTo(candidate: AnyRef): Boolean =
    owner eq candidate

private[flashalign] final class CompiledAffinePatchTraversal3 private (
    val registry: UniquePointRegistry3,
    val mode: AffinePatchTraversalMode,
    private val ownerEntries: Array[Int],
    private val ownerSlotByUnique: Array[Int],
    val diagnostics: AffinePatchTraversalDiagnostics
):
  def newWorkspace(): AffinePatchTraversalWorkspace3 =
    new AffinePatchTraversalWorkspace3(
      this,
      new Array[Double](registry.uniquePointCount),
      new Array[Double](registry.uniquePointCount),
      new Array[Double](registry.uniquePointCount),
      new Array[Double](registry.stencil.sampleCount),
      new Array[Double](registry.stencil.sampleCount),
      new Array[Double](registry.stencil.sampleCount),
      new Array[Double](ownerEntries.length),
      new Array[Double](ownerEntries.length),
      new Array[Double](ownerEntries.length)
    )

  def transform(
      affine: Affine[D3],
      workspace: AffinePatchTraversalWorkspace3
  ): Either[AffinePatchTraversalError, AffinePatchTraversalDiagnostics] =
    if !workspace.belongsTo(this) then
      Left(AffinePatchTraversalError.WorkspacePlanMismatch)
    else
      mode match
        case AffinePatchTraversalMode.TransformUniquePoints =>
          transformUnique(affine.rowMajor, workspace)
        case AffinePatchTraversalMode.TransformPatchCentersAndSharedOffsets =>
          transformCentersAndOffsets(affine.rowMajor, workspace)
      Right(diagnostics)

  def sampleFixed[
      F <: Frame[D3],
      S <: SampleSpace[F, D3]
  ](
      sampler: LinearValueGradientSampler3[F, S],
      workspace: AffinePatchTraversalWorkspace3,
      output: SparseValueGradientBuffer3
  ): Either[AffinePatchSamplingError, SparseSamplingCounters] =
    if !workspace.belongsTo(this) then
      Left(
        AffinePatchSamplingError.Traversal(
          AffinePatchTraversalError.WorkspacePlanMismatch
        )
      )
    else
      output.resetCounters()
      sampler
        .sampleFullSupportWorld(
          workspace.transformedX,
          workspace.transformedY,
          workspace.transformedZ,
          registry.uniquePointCount,
          output
        )
        .left
        .map(AffinePatchSamplingError.Resampling.apply)
        .map(_ => output.counters)

  private def transformUnique(
      matrix: Vector[Double],
      workspace: AffinePatchTraversalWorkspace3
  ): Unit =
    var unique = 0
    while unique < registry.uniquePointCount do
      val x = registry.movingX(unique)
      val y = registry.movingY(unique)
      val z = registry.movingZ(unique)
      workspace.transformedX(unique) =
        matrix(0) * x + matrix(1) * y + matrix(2) * z + matrix(3)
      workspace.transformedY(unique) =
        matrix(4) * x + matrix(5) * y + matrix(6) * z + matrix(7)
      workspace.transformedZ(unique) =
        matrix(8) * x + matrix(9) * y + matrix(10) * z + matrix(11)
      unique += 1

  private def transformCentersAndOffsets(
      matrix: Vector[Double],
      workspace: AffinePatchTraversalWorkspace3
  ): Unit =
    var sample = 0
    while sample < registry.stencil.sampleCount do
      val x = registry.stencil.offsetX(sample)
      val y = registry.stencil.offsetY(sample)
      val z = registry.stencil.offsetZ(sample)
      workspace.warpedOffsetX(sample) =
        matrix(0) * x + matrix(1) * y + matrix(2) * z
      workspace.warpedOffsetY(sample) =
        matrix(4) * x + matrix(5) * y + matrix(6) * z
      workspace.warpedOffsetZ(sample) =
        matrix(8) * x + matrix(9) * y + matrix(10) * z
      sample += 1
    var slot = 0
    while slot < ownerEntries.length do
      val patch = registry.samples.entries(ownerEntries(slot)).patch
      workspace.transformedCenterX(slot) =
        matrix(0) * patch.centerX +
          matrix(1) * patch.centerY +
          matrix(2) * patch.centerZ +
          matrix(3)
      workspace.transformedCenterY(slot) =
        matrix(4) * patch.centerX +
          matrix(5) * patch.centerY +
          matrix(6) * patch.centerZ +
          matrix(7)
      workspace.transformedCenterZ(slot) =
        matrix(8) * patch.centerX +
          matrix(9) * patch.centerY +
          matrix(10) * patch.centerZ +
          matrix(11)
      slot += 1
    var unique = 0
    while unique < registry.uniquePointCount do
      val ownerSlot = ownerSlotByUnique(unique)
      val stencilSample = registry.ownerStencilSample(unique)
      workspace.transformedX(unique) =
        workspace.transformedCenterX(ownerSlot) +
          workspace.warpedOffsetX(stencilSample)
      workspace.transformedY(unique) =
        workspace.transformedCenterY(ownerSlot) +
          workspace.warpedOffsetY(stencilSample)
      workspace.transformedZ(unique) =
        workspace.transformedCenterZ(ownerSlot) +
          workspace.warpedOffsetZ(stencilSample)
      unique += 1

private[flashalign] object CompiledAffinePatchTraversal3:
  def compile(
      registry: UniquePointRegistry3
  ): CompiledAffinePatchTraversal3 =
    val slotByEntry = mutable.HashMap.empty[Int, Int]
    val entries = mutable.ArrayBuffer.empty[Int]
    val ownerSlotByUnique = new Array[Int](registry.uniquePointCount)
    var unique = 0
    while unique < registry.uniquePointCount do
      val entry = registry.ownerPatchEntry(unique)
      val slot =
        slotByEntry.get(entry) match
          case Some(existing) => existing
          case None =>
            val created = entries.size
            entries += entry
            slotByEntry.update(entry, created)
            created
      ownerSlotByUnique(unique) = slot
      unique += 1
    val ownerEntries = entries.toArray
    val uniqueCost = registry.uniquePointCount.toLong * 12L
    val centerOffsetCost =
      registry.stencil.sampleCount.toLong * 9L +
        ownerEntries.length.toLong * 12L +
        registry.uniquePointCount.toLong * 3L
    val mode =
      if uniqueCost <= centerOffsetCost then
        AffinePatchTraversalMode.TransformUniquePoints
      else
        AffinePatchTraversalMode.TransformPatchCentersAndSharedOffsets
    val diagnostics =
      mode match
        case AffinePatchTraversalMode.TransformUniquePoints =>
          AffinePatchTraversalDiagnostics(
            mode,
            registry.uniquePointCount,
            registry.uniquePointCount,
            0,
            0,
            uniqueCost
          )
        case AffinePatchTraversalMode.TransformPatchCentersAndSharedOffsets =>
          AffinePatchTraversalDiagnostics(
            mode,
            registry.uniquePointCount,
            ownerEntries.length,
            registry.stencil.sampleCount,
            registry.uniquePointCount,
            centerOffsetCost
          )
    new CompiledAffinePatchTraversal3(
      registry,
      mode,
      ownerEntries,
      ownerSlotByUnique,
      diagnostics
    )

private[flashalign] sealed trait AffinePatchSamplingError derives CanEqual:
  def message: String

private[flashalign] object AffinePatchSamplingError:
  final case class Traversal(error: AffinePatchTraversalError)
      extends AffinePatchSamplingError:
    val message: String = error.message

  final case class Resampling(error: ResamplingError)
      extends AffinePatchSamplingError:
    val message: String = error.message
