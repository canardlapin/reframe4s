package reframe4s.motion

import scala.collection.mutable.ArrayBuffer

import ravel.AnyRank
import ravel.NDArray
import ravel.Rank
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid
import reframe4s.resample.ResamplingSink

enum RigidKernelInput derives CanEqual:
  case Residual, Validity

enum RigidSystemComponent derives CanEqual:
  case Gradient, NormalMatrix

sealed trait RigidKernelError derives CanEqual:
  def message: String

object RigidKernelError:
  final case class InvalidSampleCount(value: Int) extends RigidKernelError:
    val message: String =
      s"rigid stencil sample count must be positive, got $value"

  final case class InvalidBinCount(axis: Int, value: Int)
      extends RigidKernelError:
    val message: String =
      s"rigid stencil bin count for axis $axis must be positive, got $value"

  final case class SampleCountBelowBinCount(
      samples: Int,
      bins: Int
  ) extends RigidKernelError:
    val message: String =
      s"rigid stencil sample count $samples is below spatial bin count $bins"

  final case class InvalidStencilGamma(value: Double)
      extends RigidKernelError:
    val message: String =
      s"rigid stencil gamma must be finite and positive, got $value"

  final case class FixedImageHasNonSpatialAxes(count: Int)
      extends RigidKernelError:
    val message: String =
      s"rigid stencil fixed image must be spatial-only, got $count extra axes"

  final case class UnsupportedFixedRank(actual: Int)
      extends RigidKernelError:
    val message: String =
      s"rigid stencil fixed image must have rank 3, got $actual"

  final case class NonFiniteFixedValue(
      i: Int,
      j: Int,
      k: Int,
      value: Double
  ) extends RigidKernelError:
    val message: String =
      s"rigid stencil fixed voxel ($i,$j,$k) must be finite, got $value"

  final case class InvalidHuberThreshold(value: Double)
      extends RigidKernelError:
    val message: String =
      s"Huber threshold must be finite and positive, got $value"

  final case class InputLengthMismatch(
      input: RigidKernelInput,
      expected: Int,
      actual: Int
  ) extends RigidKernelError:
    val message: String =
      s"$input count $actual does not match rigid stencil size $expected"

  final case class NonFiniteResidual(index: Int, value: Double)
      extends RigidKernelError:
    val message: String =
      s"rigid residual $index must be finite, got $value"

  final case class InvalidValidity(index: Int, value: Double)
      extends RigidKernelError:
    val message: String =
      s"rigid validity $index must be finite and within [0, 1], got $value"

  final case class MissingStencilSamples(expected: Int, observed: Int)
      extends RigidKernelError:
    val message: String =
      s"rigid scan observed $observed of $expected fixed-domain samples"

  final case class InvalidDamping(value: Double) extends RigidKernelError:
    val message: String =
      s"rigid normal-equation damping must be finite and non-negative, got $value"

  final case class InvalidConditionLimit(value: Double)
      extends RigidKernelError:
    val message: String =
      s"rigid normal-equation condition limit must be finite and greater than one, got $value"

  final case class NonFiniteSystem(
      component: RigidSystemComponent,
      index: Int,
      value: Double
  ) extends RigidKernelError:
    val message: String =
      s"rigid $component entry $index must be finite, got $value"

  final case class SingularNormalMatrix(pivot: Int, value: Double)
      extends RigidKernelError:
    val message: String =
      s"rigid normal matrix is singular at pivot $pivot with value $value"

  final case class IllConditionedNormalMatrix(
      pivotRatio: Double,
      limit: Double
  ) extends RigidKernelError:
    val message: String =
      s"rigid normal matrix pivot ratio $pivotRatio exceeds $limit"

  final case class NonFiniteStep(index: Int, value: Double)
      extends RigidKernelError:
    val message: String =
      s"rigid optimizer step $index must be finite, got $value"

/**
 * Validated deterministic spatial-stencil policy.
 *
 * `sampleCount` includes all spatial bins, so an information-aware stencil
 * visits every occupied bin before taking a second point from any bin.
 */
final class RigidStencilControl private (
    val sampleCount: Int,
    val binsX: Int,
    val binsY: Int,
    val binsZ: Int,
    val gamma: Double
):
  val binCount: Int = binsX * binsY * binsZ

object RigidStencilControl:
  def create(
      sampleCount: Int,
      binsX: Int,
      binsY: Int,
      binsZ: Int,
      gamma: Double
  ): Either[RigidKernelError, RigidStencilControl] =
    if sampleCount <= 0 then
      Left(RigidKernelError.InvalidSampleCount(sampleCount))
    else
      Vector(binsX, binsY, binsZ).zipWithIndex.collectFirst {
        case (value, axis) if value <= 0 =>
          RigidKernelError.InvalidBinCount(axis, value)
      } match
        case Some(error) => Left(error)
        case None =>
          val totalBins = binsX.toLong * binsY.toLong * binsZ.toLong
          if totalBins > Int.MaxValue.toLong then
            Left(
              RigidKernelError.SampleCountBelowBinCount(
                sampleCount,
                Int.MaxValue
              )
            )
          else if sampleCount < totalBins.toInt then
            Left(
              RigidKernelError.SampleCountBelowBinCount(
                sampleCount,
                totalBins.toInt
              )
            )
          else if !gamma.isFinite || gamma <= 0.0 then
            Left(RigidKernelError.InvalidStencilGamma(gamma))
          else
            Right(
              new RigidStencilControl(
                sampleCount,
                binsX,
                binsY,
                binsZ,
                gamma
              )
            )

/**
 * Immutable primitive fixed-domain derivative state.
 *
 * Coordinates and Jacobians are expressed in the complete physical frame.
 * The six Jacobian columns differentiate
 * `fixed(exp(-delta) * x)` at zero for a target-frame twist ordered as
 * translation followed by rotation.
 */
private[motion] final class CompiledRigidStencil[
    F <: Frame[D3]
] private (
    val grid: Grid[F, D3],
    private[motion] val linearIndices: Array[Int],
    private[motion] val worldX: Array[Double],
    private[motion] val worldY: Array[Double],
    private[motion] val worldZ: Array[Double],
    private[motion] val fixedValues: Array[Double],
    private[motion] val gradientX: Array[Double],
    private[motion] val gradientY: Array[Double],
    private[motion] val gradientZ: Array[Double],
    private[motion] val jacobianValues: Array[Double],
    private[motion] val baseWeights: Array[Double],
    private[motion] val spatialBins: Array[Int],
    private[motion] val linearToSample: Array[Int],
    val coveredBinCount: Int,
    val declaredBinCount: Int
):
  val size: Int = linearIndices.length
  val fixedWeight: Double =
    var total = 0.0
    var index = 0
    while index < baseWeights.length do
      total += baseWeights(index)
      index += 1
    total

  def linearIndex(index: Int): Int = linearIndices(index)
  def x(index: Int): Double = worldX(index)
  def y(index: Int): Double = worldY(index)
  def z(index: Int): Double = worldZ(index)
  def fixedValue(index: Int): Double = fixedValues(index)
  def gradient(index: Int, axis: Int): Double =
    axis match
      case 0 => gradientX(index)
      case 1 => gradientY(index)
      case 2 => gradientZ(index)
      case _ =>
        throw new IndexOutOfBoundsException(s"gradient axis $axis")
  def jacobian(index: Int, column: Int): Double =
    jacobianValues(index * 6 + column)
  def baseWeight(index: Int): Double = baseWeights(index)
  def spatialBin(index: Int): Int = spatialBins(index)
  private[motion] def sampleAtLinearIndex(index: Int): Int =
    linearToSample(index)

  /**
   * A parameterization-independent checksum over physical stencil state.
   */
  def physicalChecksum: Double =
    var total = 0.0
    var index = 0
    while index < size do
      val offset = index * 6
      total +=
        worldX(index) * 0.03125 +
          worldY(index) * 0.0625 +
          worldZ(index) * 0.125 +
          fixedValues(index) * 0.25 +
          gradientX(index) * 0.5 +
          gradientY(index) * 0.75 +
          gradientZ(index) +
          jacobianValues(offset) * 0.0078125 +
          jacobianValues(offset + 1) * 0.015625 +
          jacobianValues(offset + 2) * 0.0234375 +
          jacobianValues(offset + 3) * 0.00390625 +
          jacobianValues(offset + 4) * 0.005859375 +
          jacobianValues(offset + 5) * 0.009765625
      index += 1
    total

private[motion] object CompiledRigidStencil:
  def dense[
      F <: Frame[D3],
      R <: AnyRank
  ](
      fixed: MotionScalarImage[F, D3, R]
  ): Either[RigidKernelError, CompiledRigidStencil[F]] =
    compileCandidates(fixed).map { candidates =>
      val sorted = candidates.sortWith(physicalBefore)
      fromCandidates(fixed.grid, sorted, coveredBins = 1, declaredBins = 1)
    }

  def informationAware[
      F <: Frame[D3],
      R <: AnyRank
  ](
      fixed: MotionScalarImage[F, D3, R],
      control: RigidStencilControl
  ): Either[RigidKernelError, CompiledRigidStencil[F]] =
    compileCandidates(fixed).map { raw =>
      val minimumX = raw.iterator.map(_.x).min
      val maximumX = raw.iterator.map(_.x).max
      val minimumY = raw.iterator.map(_.y).min
      val maximumY = raw.iterator.map(_.y).max
      val minimumZ = raw.iterator.map(_.z).min
      val maximumZ = raw.iterator.map(_.z).max
      val bins =
        Array.tabulate(control.binCount)(_ => ArrayBuffer.empty[Candidate])
      raw.foreach { candidate =>
        val binX =
          binOf(candidate.x, minimumX, maximumX, control.binsX)
        val binY =
          binOf(candidate.y, minimumY, maximumY, control.binsY)
        val binZ =
          binOf(candidate.z, minimumZ, maximumZ, control.binsZ)
        val bin =
          (binX * control.binsY + binY) * control.binsZ + binZ
        val information =
          math.pow(
            math.sqrt(
              candidate.gx * candidate.gx +
                candidate.gy * candidate.gy +
                candidate.gz * candidate.gz
            ),
            control.gamma
          )
        bins(bin) += candidate.copy(bin = bin, information = information)
      }
      bins.foreach(_.sortInPlaceWith(informationBefore))

      val target = math.min(control.sampleCount, raw.length)
      val positions = Array.fill(control.binCount)(0)
      val selected = ArrayBuffer.empty[Candidate]
      var progress = true
      while selected.length < target && progress do
        progress = false
        var bin = 0
        while bin < bins.length && selected.length < target do
          val position = positions(bin)
          if position < bins(bin).length then
            selected += bins(bin)(position)
            positions(bin) = position + 1
            progress = true
          bin += 1

      val sorted = selected.toVector.sortWith(physicalBefore)
      val covered = sorted.iterator.map(_.bin).toSet.size
      fromCandidates(
        fixed.grid,
        sorted,
        coveredBins = covered,
        declaredBins = control.binCount
      )
    }

  private final case class Candidate(
      linearIndex: Int,
      x: Double,
      y: Double,
      z: Double,
      value: Double,
      gx: Double,
      gy: Double,
      gz: Double,
      j0: Double,
      j1: Double,
      j2: Double,
      j3: Double,
      j4: Double,
      j5: Double,
      bin: Int,
      information: Double
  )

  private def compileCandidates[
      F <: Frame[D3],
      R <: AnyRank
  ](
      fixed: MotionScalarImage[F, D3, R]
  ): Either[RigidKernelError, Vector[Candidate]] =
    if fixed.nonSpatialAxes.size != 0 then
      Left(
        RigidKernelError.FixedImageHasNonSpatialAxes(
          fixed.nonSpatialAxes.size
        )
      )
    else
      fixed.data.requireRank[3] match
        case Left(_) =>
          Left(RigidKernelError.UnsupportedFixedRank(fixed.data.rank))
        case Right(data) =>
          buildCandidates(data, fixed.grid)

  private def buildCandidates[
      F <: Frame[D3]
  ](
      data: NDArray[Double, Rank[3]],
      grid: Grid[F, D3]
  ): Either[RigidKernelError, Vector[Candidate]] =
    val affine = grid.indexToFrame.rowMajor
    val frameToIndex = grid.indexToFrame.inverse.rowMajor
    val extent0 = grid.shape(0)
    val extent1 = grid.shape(1)
    val extent2 = grid.shape(2)
    val plane = extent1 * extent2
    val candidates =
      ArrayBuffer.empty[Candidate]
    var failure = Option.empty[RigidKernelError]
    var i = 0
    while i < extent0 && failure.isEmpty do
      var j = 0
      while j < extent1 && failure.isEmpty do
        var k = 0
        while k < extent2 && failure.isEmpty do
          val value = data(i, j, k)
          if !value.isFinite then
            failure =
              Some(RigidKernelError.NonFiniteFixedValue(i, j, k, value))
          else
            val derivative0 =
              indexDerivative(data, i, j, k, axis = 0, extent0)
            val derivative1 =
              indexDerivative(data, i, j, k, axis = 1, extent1)
            val derivative2 =
              indexDerivative(data, i, j, k, axis = 2, extent2)
            val gx =
              frameToIndex(0) * derivative0 +
                frameToIndex(4) * derivative1 +
                frameToIndex(8) * derivative2
            val gy =
              frameToIndex(1) * derivative0 +
                frameToIndex(5) * derivative1 +
                frameToIndex(9) * derivative2
            val gz =
              frameToIndex(2) * derivative0 +
                frameToIndex(6) * derivative1 +
                frameToIndex(10) * derivative2
            val x =
              affine(0) * i + affine(1) * j + affine(2) * k + affine(3)
            val y =
              affine(4) * i + affine(5) * j + affine(6) * k + affine(7)
            val z =
              affine(8) * i + affine(9) * j + affine(10) * k + affine(11)
            candidates +=
              Candidate(
                linearIndex = i * plane + j * extent2 + k,
                x,
                y,
                z,
                value,
                gx,
                gy,
                gz,
                j0 = -gx,
                j1 = -gy,
                j2 = -gz,
                j3 = gy * z - gz * y,
                j4 = gz * x - gx * z,
                j5 = gx * y - gy * x,
                bin = 0,
                information = 0.0
              )
          k += 1
        j += 1
      i += 1
    failure match
      case Some(error) => Left(error)
      case None        => Right(candidates.toVector)

  private def indexDerivative(
      data: NDArray[Double, Rank[3]],
      i: Int,
      j: Int,
      k: Int,
      axis: Int,
      extent: Int
  ): Double =
    if extent == 1 then 0.0
    else
      val coordinate =
        axis match
          case 0 => i
          case 1 => j
          case 2 => k
          case _ =>
            throw new IndexOutOfBoundsException(s"gradient axis $axis")
      if coordinate == 0 then
        sample(data, i, j, k, axis, 1) -
          sample(data, i, j, k, axis, 0)
      else if coordinate == extent - 1 then
        sample(data, i, j, k, axis, extent - 1) -
          sample(data, i, j, k, axis, extent - 2)
      else
        0.5 * (
          sample(data, i, j, k, axis, coordinate + 1) -
            sample(data, i, j, k, axis, coordinate - 1)
        )

  private def sample(
      data: NDArray[Double, Rank[3]],
      i: Int,
      j: Int,
      k: Int,
      axis: Int,
      coordinate: Int
  ): Double =
    axis match
      case 0 => data(coordinate, j, k)
      case 1 => data(i, coordinate, k)
      case 2 => data(i, j, coordinate)
      case _ =>
        throw new IndexOutOfBoundsException(s"gradient axis $axis")

  private def binOf(
      value: Double,
      minimum: Double,
      maximum: Double,
      bins: Int
  ): Int =
    if bins == 1 || maximum == minimum then 0
    else
      val scaled = (value - minimum) / (maximum - minimum)
      math.min(bins - 1, math.max(0, math.floor(scaled * bins).toInt))

  private def physicalBefore(
      left: Candidate,
      right: Candidate
  ): Boolean =
    if left.x != right.x then left.x < right.x
    else if left.y != right.y then left.y < right.y
    else if left.z != right.z then left.z < right.z
    else left.linearIndex < right.linearIndex

  private def informationBefore(
      left: Candidate,
      right: Candidate
  ): Boolean =
    if left.information != right.information then
      left.information > right.information
    else physicalBefore(left, right)

  private def fromCandidates[
      F <: Frame[D3]
  ](
      grid: Grid[F, D3],
      candidates: Vector[Candidate],
      coveredBins: Int,
      declaredBins: Int
  ): CompiledRigidStencil[F] =
    val size = candidates.length
    val linearIndices = new Array[Int](size)
    val worldX = new Array[Double](size)
    val worldY = new Array[Double](size)
    val worldZ = new Array[Double](size)
    val fixedValues = new Array[Double](size)
    val gradientX = new Array[Double](size)
    val gradientY = new Array[Double](size)
    val gradientZ = new Array[Double](size)
    val jacobians = new Array[Double](size * 6)
    val weights = Array.fill(size)(1.0)
    val bins = new Array[Int](size)
    val spatialSize =
      grid.shape(0) * grid.shape(1) * grid.shape(2)
    val linearToSample = Array.fill(spatialSize)(-1)
    var index = 0
    while index < size do
      val candidate = candidates(index)
      val offset = index * 6
      linearIndices(index) = candidate.linearIndex
      worldX(index) = candidate.x
      worldY(index) = candidate.y
      worldZ(index) = candidate.z
      fixedValues(index) = candidate.value
      gradientX(index) = candidate.gx
      gradientY(index) = candidate.gy
      gradientZ(index) = candidate.gz
      jacobians(offset) = candidate.j0
      jacobians(offset + 1) = candidate.j1
      jacobians(offset + 2) = candidate.j2
      jacobians(offset + 3) = candidate.j3
      jacobians(offset + 4) = candidate.j4
      jacobians(offset + 5) = candidate.j5
      bins(index) = candidate.bin
      linearToSample(candidate.linearIndex) = index
      index += 1
    new CompiledRigidStencil(
      grid,
      linearIndices,
      worldX,
      worldY,
      worldZ,
      fixedValues,
      gradientX,
      gradientY,
      gradientZ,
      jacobians,
      weights,
      bins,
      linearToSample,
      coveredBins,
      declaredBins
    )

/**
 * Closed robust residual policy used by both dense and stencil evaluation.
 */
final class RigidRobustLoss private (
    private[motion] val code: Int,
    private[motion] val threshold: Double
)

object RigidRobustLoss:
  val default: RigidRobustLoss =
    new RigidRobustLoss(code = 1, threshold = 1.5)

  val squared: RigidRobustLoss =
    new RigidRobustLoss(code = 0, threshold = Double.PositiveInfinity)

  private[motion] def sampleLoss(
      policy: RigidRobustLoss,
      residual: Double
  ): Double =
    val absolute = math.abs(residual)
    if policy.code == 0 || absolute <= policy.threshold then
      0.5 * residual * residual
    else
      policy.threshold * (absolute - 0.5 * policy.threshold)

  def huber(
      threshold: Double
  ): Either[RigidKernelError, RigidRobustLoss] =
    if !threshold.isFinite || threshold <= 0.0 then
      Left(RigidKernelError.InvalidHuberThreshold(threshold))
    else Right(new RigidRobustLoss(code = 1, threshold))

/**
 * Reusable primitive objective, gradient, normal-matrix, and solve storage.
 */
private[motion] final class RigidNormalWorkspace private ():
  private[motion] val gradientValues = new Array[Double](6)
  private[motion] val normalValues = new Array[Double](36)
  private[motion] val factorValues = new Array[Double](36)
  private[motion] val rightHandSide = new Array[Double](6)
  private[motion] val forwardValues = new Array[Double](6)
  private[motion] val stepValues = new Array[Double](6)
  private[motion] var objectiveValue = 0.0
  private[motion] var rawLossValue = 0.0
  private[motion] var overlapValue = 0.0
  private[motion] var supportValue = 0L
  private[motion] var normalizerValue = 0.0

  def objective: Double = objectiveValue
  def rawLoss: Double = rawLossValue
  def overlap: Double = overlapValue
  def support: Long = supportValue
  def normalizer: Double = normalizerValue
  def gradient(index: Int): Double = gradientValues(index)
  def normal(row: Int, column: Int): Double =
    normalValues(row * 6 + column)
  def proposedStep(index: Int): Double = stepValues(index)

  def checksum: Double =
    var total =
      objectiveValue +
        rawLossValue * 0.5 +
        overlapValue * 0.25 +
        supportValue.toDouble * 0.125 +
        normalizerValue * 0.0625
    var index = 0
    while index < 6 do
      total += gradientValues(index) * (index + 1).toDouble
      total += stepValues(index) * (index + 1).toDouble * 0.03125
      index += 1
    index = 0
    while index < 36 do
      total += normalValues(index) * (index + 1).toDouble * 0.0009765625
      index += 1
    total

  private[motion] def clear(): Unit =
    var index = 0
    while index < 6 do
      gradientValues(index) = 0.0
      rightHandSide(index) = 0.0
      forwardValues(index) = 0.0
      stepValues(index) = 0.0
      index += 1
    index = 0
    while index < 36 do
      normalValues(index) = 0.0
      factorValues(index) = 0.0
      index += 1
    objectiveValue = 0.0
    rawLossValue = 0.0
    overlapValue = 0.0
    supportValue = 0L
    normalizerValue = 0.0

private[motion] object RigidNormalWorkspace:
  def create(): RigidNormalWorkspace =
    new RigidNormalWorkspace

private[motion] object RigidNormalKernel:
  /**
   * Accumulate one fixed-domain objective into reusable storage.
   *
   * Validity contributes only to overlap and support. Every residual,
   * including a boundary-valued residual, contributes to the loss with the
   * stencil's fixed denominator.
   */
  def evaluateInto[
      F <: Frame[D3]
  ](
      stencil: CompiledRigidStencil[F],
      residuals: Array[Double],
      validity: Array[Double],
      loss: RigidRobustLoss,
      workspace: RigidNormalWorkspace
  ): Either[RigidKernelError, Unit] =
    if residuals.length != stencil.size then
      Left(
        RigidKernelError.InputLengthMismatch(
          RigidKernelInput.Residual,
          stencil.size,
          residuals.length
        )
      )
    else if validity.length != stencil.size then
      Left(
        RigidKernelError.InputLengthMismatch(
          RigidKernelInput.Validity,
          stencil.size,
          validity.length
        )
      )
    else
      begin(stencil.fixedWeight, workspace)
      var failure = Option.empty[RigidKernelError]
      var sample = 0
      while sample < stencil.size && failure.isEmpty do
        val residual = residuals(sample)
        val valid = validity(sample)
        if !residual.isFinite then
          failure =
            Some(RigidKernelError.NonFiniteResidual(sample, residual))
        else if !valid.isFinite || valid < 0.0 || valid > 1.0 then
          failure =
            Some(RigidKernelError.InvalidValidity(sample, valid))
        else
          accumulateUnchecked(
            stencil.jacobianValues,
            stencil.baseWeights,
            sample,
            residual,
            valid,
            loss.code,
            loss.threshold,
            workspace
          )
        sample += 1

      failure match
        case Some(error) => Left(error)
        case None =>
          finish(workspace)
          Right(())

  private[motion] def begin(
      normalizer: Double,
      workspace: RigidNormalWorkspace
  ): Unit =
    workspace.clear()
    workspace.normalizerValue = normalizer

  private[motion] def accumulateUnchecked(
      jacobians: Array[Double],
      baseWeights: Array[Double],
      sample: Int,
      residual: Double,
      validity: Double,
      lossCode: Int,
      lossThreshold: Double,
      workspace: RigidNormalWorkspace
  ): Unit =
    val absolute = math.abs(residual)
    val robustWeight =
      if lossCode == 0 || absolute <= lossThreshold then 1.0
      else lossThreshold / absolute
    val sampleLoss =
      if lossCode == 0 || absolute <= lossThreshold then
        0.5 * residual * residual
      else
        lossThreshold * (absolute - 0.5 * lossThreshold)
    val baseWeight = baseWeights(sample)
    val weightedInfluence = baseWeight * robustWeight
    workspace.rawLossValue += baseWeight * sampleLoss
    workspace.overlapValue += baseWeight * validity
    if validity > 0.0 then workspace.supportValue += 1L

    val offset = sample * 6
    var row = 0
    while row < 6 do
      val rowJacobian = jacobians(offset + row)
      workspace.gradientValues(row) +=
        weightedInfluence * residual * rowJacobian
      var column = 0
      while column < 6 do
        workspace.normalValues(row * 6 + column) +=
          weightedInfluence *
            rowJacobian *
            jacobians(offset + column)
        column += 1
      row += 1

  private[motion] def finish(workspace: RigidNormalWorkspace): Unit =
    val inverseWeight = 1.0 / workspace.normalizerValue
    workspace.objectiveValue =
      workspace.rawLossValue * inverseWeight
    workspace.overlapValue *= inverseWeight
    var index = 0
    while index < 6 do
      workspace.gradientValues(index) *= inverseWeight
      index += 1
    index = 0
    while index < 36 do
      workspace.normalValues(index) *= inverseWeight
      index += 1

/**
 * Reusable bridge from a dense resampling scan into a selected fixed-domain
 * normal system.
 *
 * The bridge stores only primitive arrays and fixed-size normal storage. It is
 * configured for a compiled stencil before each scan and allocates no objects
 * per output sample.
 */
private[motion] final class RigidNormalScanWorkspace private ()
    extends ResamplingSink:
  private val normalWorkspace = RigidNormalWorkspace.create()
  private var linearToSample = Array.empty[Int]
  private var fixedValues = Array.empty[Double]
  private var jacobians = Array.empty[Double]
  private var baseWeights = Array.empty[Double]
  private var lossCode = 0
  private var lossThreshold = Double.PositiveInfinity
  private var expectedSamples = 0
  private var observedSamples = 0
  private var failure = Option.empty[RigidKernelError]

  private[motion] def normal: RigidNormalWorkspace =
    normalWorkspace

  def prepare[F <: Frame[D3]](
      stencil: CompiledRigidStencil[F],
      loss: RigidRobustLoss
  ): Unit =
    linearToSample = stencil.linearToSample
    fixedValues = stencil.fixedValues
    jacobians = stencil.jacobianValues
    baseWeights = stencil.baseWeights
    lossCode = loss.code
    lossThreshold = loss.threshold
    expectedSamples = stencil.size
    observedSamples = 0
    failure = None
    RigidNormalKernel.begin(stencil.fixedWeight, normalWorkspace)

  def accept(
      outputLinearIndex: Int,
      value: Double,
      validityWeight: Double
  ): Unit =
    val sample = linearToSample(outputLinearIndex)
    if sample >= 0 && failure.isEmpty then
      val residual = value - fixedValues(sample)
      if !residual.isFinite then
        failure =
          Some(RigidKernelError.NonFiniteResidual(sample, residual))
      else if
        !validityWeight.isFinite ||
        validityWeight < 0.0 ||
        validityWeight > 1.0
      then
        failure =
          Some(RigidKernelError.InvalidValidity(sample, validityWeight))
      else
        RigidNormalKernel.accumulateUnchecked(
          jacobians,
          baseWeights,
          sample,
          residual,
          validityWeight,
          lossCode,
          lossThreshold,
          normalWorkspace
        )
        observedSamples += 1

  def result: Either[RigidKernelError, RigidNormalWorkspace] =
    failure match
      case Some(error) => Left(error)
      case None if observedSamples != expectedSamples =>
        Left(
          RigidKernelError.MissingStencilSamples(
            expectedSamples,
            observedSamples
          )
        )
      case None =>
        RigidNormalKernel.finish(normalWorkspace)
        Right(normalWorkspace)

private[motion] object RigidNormalScanWorkspace:
  def create(): RigidNormalScanWorkspace =
    new RigidNormalScanWorkspace

/**
 * Fixed-size step returned by the damped normal-equation solver.
 */
private[motion] final class RigidStep private[motion] (
    val dx: Double,
    val dy: Double,
    val dz: Double,
    val rx: Double,
    val ry: Double,
    val rz: Double,
    val pivotRatio: Double
):
  def apply(index: Int): Double =
    index match
      case 0 => dx
      case 1 => dy
      case 2 => dz
      case 3 => rx
      case 4 => ry
      case 5 => rz
      case _ => throw new IndexOutOfBoundsException(s"rigid step $index")

private[motion] object RigidSmallSystem:
  /**
   * Solve `(H + damping * I) step = -gradient` by Cholesky factorization.
   *
   * `conditionLimit` applies to the ratio of largest to smallest squared
   * Cholesky pivot. This is a deterministic conditioning diagnostic, not a
   * claim of an exact spectral condition number.
   */
  def solve(
      workspace: RigidNormalWorkspace,
      damping: Double,
      conditionLimit: Double
  ): Either[RigidKernelError, RigidStep] =
    if !damping.isFinite || damping < 0.0 then
      Left(RigidKernelError.InvalidDamping(damping))
    else if !conditionLimit.isFinite || conditionLimit <= 1.0 then
      Left(RigidKernelError.InvalidConditionLimit(conditionLimit))
    else
      var failure = Option.empty[RigidKernelError]
      var index = 0
      while index < 6 && failure.isEmpty do
        val value = workspace.gradientValues(index)
        if !value.isFinite then
          failure =
            Some(
              RigidKernelError.NonFiniteSystem(
                RigidSystemComponent.Gradient,
                index,
                value
              )
            )
        workspace.rightHandSide(index) = -value
        index += 1
      index = 0
      while index < 36 && failure.isEmpty do
        val value = workspace.normalValues(index)
        if !value.isFinite then
          failure =
            Some(
              RigidKernelError.NonFiniteSystem(
                RigidSystemComponent.NormalMatrix,
                index,
                value
              )
            )
        workspace.factorValues(index) =
          if index / 6 == index % 6 then value + damping else value
        index += 1

      var maximumDiagonal = 0.0
      index = 0
      while index < 6 do
        maximumDiagonal =
          math.max(
            maximumDiagonal,
            math.abs(workspace.factorValues(index * 6 + index))
          )
        index += 1
      val pivotTolerance =
        math.max(1e-15, maximumDiagonal * 1e-14)
      var minimumPivot = Double.PositiveInfinity
      var maximumPivot = 0.0
      var row = 0
      while row < 6 && failure.isEmpty do
        var column = 0
        while column <= row && failure.isEmpty do
          var value = workspace.factorValues(row * 6 + column)
          var inner = 0
          while inner < column do
            value -=
              workspace.factorValues(row * 6 + inner) *
                workspace.factorValues(column * 6 + inner)
            inner += 1
          if row == column then
            if !value.isFinite then
              failure =
                Some(
                  RigidKernelError.NonFiniteSystem(
                    RigidSystemComponent.NormalMatrix,
                    row * 6 + column,
                    value
                  )
                )
            else if value <= pivotTolerance then
              failure =
                Some(
                  RigidKernelError.SingularNormalMatrix(row, value)
                )
            else
              minimumPivot = math.min(minimumPivot, value)
              maximumPivot = math.max(maximumPivot, value)
              workspace.factorValues(row * 6 + column) =
                math.sqrt(value)
          else
            workspace.factorValues(row * 6 + column) =
              value / workspace.factorValues(column * 6 + column)
          column += 1
        row += 1

      val pivotRatio = maximumPivot / minimumPivot
      if failure.isEmpty && pivotRatio > conditionLimit then
        failure =
          Some(
            RigidKernelError.IllConditionedNormalMatrix(
              pivotRatio,
              conditionLimit
            )
          )

      if failure.isEmpty then
        row = 0
        while row < 6 do
          var value = workspace.rightHandSide(row)
          var column = 0
          while column < row do
            value -=
              workspace.factorValues(row * 6 + column) *
                workspace.forwardValues(column)
            column += 1
          workspace.forwardValues(row) =
            value / workspace.factorValues(row * 6 + row)
          row += 1

        row = 5
        while row >= 0 do
          var value = workspace.forwardValues(row)
          var column = row + 1
          while column < 6 do
            value -=
              workspace.factorValues(column * 6 + row) *
                workspace.stepValues(column)
            column += 1
          workspace.stepValues(row) =
            value / workspace.factorValues(row * 6 + row)
          if !workspace.stepValues(row).isFinite then
            failure =
              Some(
                RigidKernelError.NonFiniteStep(
                  row,
                  workspace.stepValues(row)
                )
              )
          row -= 1

      failure match
        case Some(error) => Left(error)
        case None =>
          Right(
            new RigidStep(
              workspace.stepValues(0),
              workspace.stepValues(1),
              workspace.stepValues(2),
              workspace.stepValues(3),
              workspace.stepValues(4),
              workspace.stepValues(5),
              pivotRatio
            )
          )
