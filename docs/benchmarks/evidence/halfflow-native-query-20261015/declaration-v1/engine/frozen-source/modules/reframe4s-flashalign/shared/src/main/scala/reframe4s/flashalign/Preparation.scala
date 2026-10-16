package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import reframe4s.multiscale.SupportAwarePyramidLevel3
import reframe4s.resample.LinearValueGradientSampler3
import reframe4s.resample.ResamplingError
import reframe4s.resample.ScalarValueGradient3

import scala.collection.mutable

private[flashalign] enum PreparationParameter derives CanEqual:
  case StencilSpacingMillimetres
  case WorldCellSizeMillimetres
  case MinimumSampleSupport
  case TargetCandidates
  case MaximumCentersToScreen
  case MaximumCandidatesPerCell

private[flashalign] sealed trait PreparationError derives CanEqual:
  def message: String

private[flashalign] object PreparationError:
  final case class InvalidPositiveScalar(
      parameter: PreparationParameter,
      value: Double
  ) extends PreparationError:
    val message: String =
      s"$parameter must be finite and positive, got $value"

  final case class InvalidUnitInterval(
      parameter: PreparationParameter,
      value: Double
  ) extends PreparationError:
    val message: String =
      s"$parameter must be finite and in (0, 1], got $value"

  final case class InvalidPositiveCount(
      parameter: PreparationParameter,
      value: Int
  ) extends PreparationError:
    val message: String = s"$parameter must be positive, got $value"

  final case class CandidateTargetOutsideProductionRange(
      value: Int,
      minimum: Int,
      maximum: Int
  ) extends PreparationError:
    val message: String =
      s"target candidate count must be within [$minimum, $maximum], got $value"

  final case class ScreeningBudgetBelowTarget(
      target: Int,
      maximumCenters: Int
  ) extends PreparationError:
    val message: String =
      s"maximum screened centers $maximumCenters cannot be below target candidates $target"

  final case class Sampling(error: ResamplingError) extends PreparationError:
    val message: String = error.message

  final case class Patch(error: PatchObjectiveError) extends PreparationError:
    val message: String = error.message

/** Fixed 3 x 3 x 3 stencil expressed in physical millimetres. */
private[flashalign] final class PhysicalStencil3 private (
    val spacingMillimetres: Double,
    private val worldOffsets: Array[Double]
):
  val sampleCount: Int = 27

  def offsetX(sample: Int): Double = worldOffsets(3 * sample)
  def offsetY(sample: Int): Double = worldOffsets(3 * sample + 1)
  def offsetZ(sample: Int): Double = worldOffsets(3 * sample + 2)

private[flashalign] object PhysicalStencil3:
  def create(spacingMillimetres: Double): PhysicalStencil3 =
    val offsets = new Array[Double](27 * 3)
    var sample = 0
    var i = -1
    while i <= 1 do
      var j = -1
      while j <= 1 do
        var k = -1
        while k <= 1 do
          offsets(3 * sample) = i.toDouble * spacingMillimetres
          offsets(3 * sample + 1) = j.toDouble * spacingMillimetres
          offsets(3 * sample + 2) = k.toDouble * spacingMillimetres
          sample += 1
          k += 1
        j += 1
      i += 1
    new PhysicalStencil3(spacingMillimetres, offsets)

/** Exact bit identity of one constructed world-space sample point. */
private[flashalign] final case class ExactWorldPointKey3(
    xBits: Long,
    yBits: Long,
    zBits: Long
)

private[flashalign] final case class WorldCell3(
    i: Long,
    j: Long,
    k: Long
)

private[flashalign] final class PopulationPatch3 private[flashalign] (
    val id: Int,
    val centerLinearIndex: Int,
    val centerX: Double,
    val centerY: Double,
    val centerZ: Double,
    val cell: WorldCell3,
    val moving: PreparedMovingPatch,
    val qualityWeight: Double,
    val minimumStencilSupport: Double
):
  def samplePointKey(
      stencil: PhysicalStencil3,
      sample: Int
  ): ExactWorldPointKey3 =
    ExactWorldPointKey3(
      java.lang.Double.doubleToLongBits(centerX + stencil.offsetX(sample)),
      java.lang.Double.doubleToLongBits(centerY + stencil.offsetY(sample)),
      java.lang.Double.doubleToLongBits(centerZ + stencil.offsetZ(sample))
    )

private[flashalign] final case class PatchPopulationDiagnostics(
    levelOrdinal: Int,
    targetCandidateCount: Int,
    totalLatticeCenters: Long,
    centerScanStride: Int,
    screenedCenters: Int,
    rejectedIncompleteGeometry: Int,
    rejectedInsufficientSupport: Int,
    rejectedInsufficientContrast: Int,
    eligibleBeforeCellQuota: Int,
    rejectedByCellQuota: Int,
    retainedCandidates: Int,
    candidateBudgetShortfall: Int,
    occupiedWorldCells: Int,
    stencilSpacingMillimetres: Double,
    worldCellSizeMillimetres: Double,
    coefficientStorage: String,
    normalizedDuringPreparation: Boolean
)

/** Immutable transform-independent source patch before sample-set drawing. */
private[flashalign] final class PatchPopulation3[
    F <: Frame[D3],
    +C
] private[flashalign] (
    val sourceLevel: SupportAwarePyramidLevel3[F, C],
    val stencil: PhysicalStencil3,
    val patches: Vector[PopulationPatch3],
    val diagnostics: PatchPopulationDiagnostics
):
  def size: Int = patches.size

private[flashalign] final class PatchPopulationConfig private (
    val stencilSpacingMillimetres: Double,
    val worldCellSizeMillimetres: Double,
    val minimumSampleSupport: Double,
    val targetCandidateCount: Int,
    val maximumCentersToScreen: Int,
    val maximumCandidatesPerCell: Int
)

private[flashalign] object PatchPopulationConfig:
  val MinimumProductionCandidates: Int = 10000
  val MaximumProductionCandidates: Int = 30000
  val DefaultTargetCandidates: Int = 20000
  val DefaultMaximumCentersToScreen: Int = 120000
  val DefaultMaximumCandidatesPerCell: Int = 16

  def create(
      stencilSpacingMillimetres: Double,
      worldCellSizeMillimetres: Double = 12.0,
      minimumSampleSupport: Double = 1.0 - 1e-12,
      targetCandidateCount: Int = DefaultTargetCandidates,
      maximumCentersToScreen: Int = DefaultMaximumCentersToScreen,
      maximumCandidatesPerCell: Int = DefaultMaximumCandidatesPerCell
  ): Either[PreparationError, PatchPopulationConfig] =
    if
      !stencilSpacingMillimetres.isFinite ||
        stencilSpacingMillimetres <= 0.0
    then
      Left(
        PreparationError.InvalidPositiveScalar(
          PreparationParameter.StencilSpacingMillimetres,
          stencilSpacingMillimetres
        )
      )
    else if
      !worldCellSizeMillimetres.isFinite || worldCellSizeMillimetres <= 0.0
    then
      Left(
        PreparationError.InvalidPositiveScalar(
          PreparationParameter.WorldCellSizeMillimetres,
          worldCellSizeMillimetres
        )
      )
    else if
      !minimumSampleSupport.isFinite || minimumSampleSupport <= 0.0 ||
        minimumSampleSupport > 1.0
    then
      Left(
        PreparationError.InvalidUnitInterval(
          PreparationParameter.MinimumSampleSupport,
          minimumSampleSupport
        )
      )
    else if
      targetCandidateCount < MinimumProductionCandidates ||
        targetCandidateCount > MaximumProductionCandidates
    then
      Left(
        PreparationError.CandidateTargetOutsideProductionRange(
          targetCandidateCount,
          MinimumProductionCandidates,
          MaximumProductionCandidates
        )
      )
    else if maximumCentersToScreen <= 0 then
      Left(
        PreparationError.InvalidPositiveCount(
          PreparationParameter.MaximumCentersToScreen,
          maximumCentersToScreen
        )
      )
    else if maximumCentersToScreen < targetCandidateCount then
      Left(
        PreparationError.ScreeningBudgetBelowTarget(
          targetCandidateCount,
          maximumCentersToScreen
        )
      )
    else if maximumCandidatesPerCell <= 0 then
      Left(
        PreparationError.InvalidPositiveCount(
          PreparationParameter.MaximumCandidatesPerCell,
          maximumCandidatesPerCell
        )
      )
    else
      Right(
        new PatchPopulationConfig(
          stencilSpacingMillimetres,
          worldCellSizeMillimetres,
          minimumSampleSupport,
          targetCandidateCount,
          maximumCentersToScreen,
          maximumCandidatesPerCell
        )
      )

private[flashalign] object Preparation:
  def buildPopulation[
      F <: Frame[D3],
      C
  ](
      level: SupportAwarePyramidLevel3[F, C],
      config: PatchPopulationConfig,
      objectiveConfig: PatchObjectiveConfig
  )(using Dimension[D3]): Either[
    PreparationError,
    PatchPopulation3[F, C]
  ] =
    for
      imageSampler <- LinearValueGradientSampler3
        .compile(level.image)
        .left
        .map(PreparationError.Sampling.apply)
      supportSampler <- LinearValueGradientSampler3
        .compile(level.support)
        .left
        .map(PreparationError.Sampling.apply)
      population <- screen(
        level,
        imageSampler,
        supportSampler,
        config,
        objectiveConfig
      )
    yield population

  private final class ScreenCounters:
    var screened = 0
    var geometry = 0
    var support = 0
    var contrast = 0
    var eligible = 0
    var quota = 0

  private final class StencilIndexOffsets(
      val x: Array[Double],
      val y: Array[Double],
      val z: Array[Double]
  )

  private def screen[
      F <: Frame[D3],
      C,
      ImageSpace <: image4s.SampleSpace[F, D3],
      SupportSpace <: image4s.SampleSpace[F, D3]
  ](
      level: SupportAwarePyramidLevel3[F, C],
      imageSampler: LinearValueGradientSampler3[F, ImageSpace],
      supportSampler: LinearValueGradientSampler3[F, SupportSpace],
      config: PatchPopulationConfig,
      objectiveConfig: PatchObjectiveConfig
  ): Either[PreparationError, PatchPopulation3[F, C]] =
    val stencil = PhysicalStencil3.create(config.stencilSpacingMillimetres)
    val offsets = indexOffsets(level.image.grid.indexToFrame.inverse.rowMajor, stencil)
    val values = new Array[Double](stencil.sampleCount)
    val imageSample = ScalarValueGradient3.create
    val supportSample = ScalarValueGradient3.create
    val cells = mutable.HashMap.empty[WorldCell3, mutable.ArrayBuffer[PopulationPatch3]]
    val counters = new ScreenCounters
    val shape = level.image.grid.shape
    val total = shape(0).toLong * shape(1).toLong * shape(2).toLong
    val stride = scanStride(shape, config.maximumCentersToScreen)
    val affine = level.image.grid.indexToFrame.rowMajor
    var fatal = Option.empty[PreparationError]
    var i = 0
    while i < shape(0) && fatal.isEmpty do
      var j = 0
      while j < shape(1) && fatal.isEmpty do
        var k = 0
        while k < shape(2) && fatal.isEmpty do
          counters.screened += 1
          samplePatch(
            i,
            j,
            k,
            offsets,
            values,
            imageSampler,
            supportSampler,
            imageSample,
            supportSample,
            config.minimumSampleSupport
          ) match
            case PatchScreen.IncompleteGeometry =>
              counters.geometry += 1
            case PatchScreen.InsufficientSupport =>
              counters.support += 1
            case PatchScreen.Complete(minimumSupport) =>
              PatchObjective.prepareMoving(values, objectiveConfig) match
                case Left(_: PatchObjectiveError.InsufficientContrast) =>
                  counters.contrast += 1
                case Left(error) =>
                  fatal = Some(PreparationError.Patch(error))
                case Right(prepared) =>
                  counters.eligible += 1
                  val centerX =
                    affine(0) * i + affine(1) * j + affine(2) * k + affine(3)
                  val centerY =
                    affine(4) * i + affine(5) * j + affine(6) * k + affine(7)
                  val centerZ =
                    affine(8) * i + affine(9) * j + affine(10) * k + affine(11)
                  val cell =
                    WorldCell3(
                      math.floor(centerX / config.worldCellSizeMillimetres).toLong,
                      math.floor(centerY / config.worldCellSizeMillimetres).toLong,
                      math.floor(centerZ / config.worldCellSizeMillimetres).toLong
                    )
                  val linear = (i * shape(1) + j) * shape(2) + k
                  val quality =
                    prepared.originalContrastNorm /
                      math.sqrt(stencil.sampleCount.toDouble) *
                      minimumSupport
                  val patch =
                    new PopulationPatch3(
                      linear,
                      linear,
                      centerX,
                      centerY,
                      centerZ,
                      cell,
                      prepared,
                      quality,
                      minimumSupport
                    )
                  val bucket = cells.getOrElseUpdate(cell, mutable.ArrayBuffer.empty)
                  retainWithinCell(
                    bucket,
                    patch,
                    config.maximumCandidatesPerCell,
                    counters
                  )
          k += stride
        j += stride
      i += stride
    fatal match
      case Some(error) => Left(error)
      case None =>
        val selected =
          selectSpatiallyBalanced(cells, config.targetCandidateCount)
        val diagnostics =
          PatchPopulationDiagnostics(
            level.level.ordinal,
            config.targetCandidateCount,
            total,
            stride,
            counters.screened,
            counters.geometry,
            counters.support,
            counters.contrast,
            counters.eligible,
            counters.quota,
            selected.size,
            math.max(0, config.targetCandidateCount - selected.size),
            cells.size,
            config.stencilSpacingMillimetres,
            config.worldCellSizeMillimetres,
            coefficientStorage = "Double",
            normalizedDuringPreparation = true
          )
        Right(new PatchPopulation3(level, stencil, selected, diagnostics))

  private enum PatchScreen:
    case IncompleteGeometry
    case InsufficientSupport
    case Complete(minimumSupport: Double)

  private def samplePatch[
      F <: Frame[D3],
      ImageSpace <: image4s.SampleSpace[F, D3],
      SupportSpace <: image4s.SampleSpace[F, D3]
  ](
      centerI: Int,
      centerJ: Int,
      centerK: Int,
      offsets: StencilIndexOffsets,
      values: Array[Double],
      imageSampler: LinearValueGradientSampler3[F, ImageSpace],
      supportSampler: LinearValueGradientSampler3[F, SupportSpace],
      imageSample: ScalarValueGradient3,
      supportSample: ScalarValueGradient3,
      minimumRequiredSupport: Double
  ): PatchScreen =
    var sample = 0
    var complete = true
    var supported = true
    var minimumSupport = 1.0
    while sample < values.length && complete && supported do
      val x = centerI.toDouble + offsets.x(sample)
      val y = centerJ.toDouble + offsets.y(sample)
      val z = centerK.toDouble + offsets.z(sample)
      val imageComplete =
        imageSampler.sampleFullSupportContinuousIndex(x, y, z, imageSample)
      val supportComplete =
        supportSampler.sampleFullSupportContinuousIndex(x, y, z, supportSample)
      complete = imageComplete && supportComplete
      if complete then
        val supportValue = supportSample.value
        supported =
          supportValue.isFinite && supportValue >= minimumRequiredSupport
        if supported then
          values(sample) = imageSample.value
          minimumSupport = math.min(minimumSupport, supportValue)
      sample += 1
    if !complete then PatchScreen.IncompleteGeometry
    else if !supported then PatchScreen.InsufficientSupport
    else PatchScreen.Complete(minimumSupport)

  private def indexOffsets(
      frameToIndex: Vector[Double],
      stencil: PhysicalStencil3
  ): StencilIndexOffsets =
    val x = new Array[Double](stencil.sampleCount)
    val y = new Array[Double](stencil.sampleCount)
    val z = new Array[Double](stencil.sampleCount)
    var sample = 0
    while sample < stencil.sampleCount do
      val worldX = stencil.offsetX(sample)
      val worldY = stencil.offsetY(sample)
      val worldZ = stencil.offsetZ(sample)
      x(sample) =
        frameToIndex(0) * worldX +
          frameToIndex(1) * worldY +
          frameToIndex(2) * worldZ
      y(sample) =
        frameToIndex(4) * worldX +
          frameToIndex(5) * worldY +
          frameToIndex(6) * worldZ
      z(sample) =
        frameToIndex(8) * worldX +
          frameToIndex(9) * worldY +
          frameToIndex(10) * worldZ
      sample += 1
    new StencilIndexOffsets(x, y, z)

  private def scanStride(shape: Vector[Int], maximum: Int): Int =
    var stride = 1
    def count: Long =
      shape.foldLeft(1L) { (product, extent) =>
        product * ((extent.toLong + stride.toLong - 1L) / stride.toLong)
      }
    while count > maximum.toLong do stride += 1
    stride

  private def retainWithinCell(
      bucket: mutable.ArrayBuffer[PopulationPatch3],
      candidate: PopulationPatch3,
      maximum: Int,
      counters: ScreenCounters
  ): Unit =
    bucket += candidate
    bucket.sortInPlaceWith(isBetter)
    if bucket.size > maximum then
      bucket.remove(bucket.size - 1)
      counters.quota += 1

  private def isBetter(
      left: PopulationPatch3,
      right: PopulationPatch3
  ): Boolean =
    left.qualityWeight > right.qualityWeight ||
      (left.qualityWeight == right.qualityWeight && left.id < right.id)

  private def selectSpatiallyBalanced(
      cells: mutable.HashMap[
        WorldCell3,
        mutable.ArrayBuffer[PopulationPatch3]
      ],
      target: Int
  ): Vector[PopulationPatch3] =
    val orderedCells =
      cells.toVector.sortBy { case (cell, _) =>
        (cell.i, cell.j, cell.k)
      }
    val selected = Vector.newBuilder[PopulationPatch3]
    var count = 0
    var rank = 0
    var available = true
    while count < target && available do
      available = false
      var cellIndex = 0
      while cellIndex < orderedCells.size && count < target do
        val bucket = orderedCells(cellIndex)._2
        if rank < bucket.size then
          selected += bucket(rank)
          count += 1
          available = true
        cellIndex += 1
      rank += 1
    selected.result()
