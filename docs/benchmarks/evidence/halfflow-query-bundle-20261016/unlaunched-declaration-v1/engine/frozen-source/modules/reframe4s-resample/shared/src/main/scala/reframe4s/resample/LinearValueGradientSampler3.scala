package reframe4s.resample

import image4s.BoundaryPolicy
import image4s.ContinuousImage
import image4s.ImageError
import image4s.SampleSpace
import image4s.Validity
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point
import ravel.Rank

/** Caller-owned mutable output for one fused scalar sample. */
final class ScalarValueGradient3 private (
    private var currentValue: Double,
    private var currentGradientX: Double,
    private var currentGradientY: Double,
    private var currentGradientZ: Double,
    private var currentValidity: Validity
):
  def value: Double = currentValue
  def gradientX: Double = currentGradientX
  def gradientY: Double = currentGradientY
  def gradientZ: Double = currentGradientZ
  def validity: Validity = currentValidity

  private[resample] def update(
      value: Double,
      gradientX: Double,
      gradientY: Double,
      gradientZ: Double,
      validity: Validity
  ): Unit =
    currentValue = value
    currentGradientX = gradientX
    currentGradientY = gradientY
    currentGradientZ = gradientZ
    currentValidity = validity

object ScalarValueGradient3:
  def create: ScalarValueGradient3 =
    new ScalarValueGradient3(
      0.0,
      0.0,
      0.0,
      0.0,
      Validity.Outside
    )

/**
 * Compiled D3 trilinear sampler whose value and exact interpolant derivative
 * share the same eight source taps.
 *
 * Interior integer knots use the cell beginning at that knot. The final knot
 * on an axis uses the preceding cell, yielding the supported one-sided
 * derivative. Each source extent must therefore be at least two. The returned
 * gradient is expressed in world coordinates through the full inverse
 * transpose of the grid index-to-frame affine. Boundary validity is the same
 * value-weight validity used by [[SampledInterpolator]].
 */
final class LinearValueGradientSampler3[
    F <: Frame[D3],
    S <: SampleSpace[F, D3]
] private (
    val image: ContinuousImage[S, Double, Rank[3]]
)(using Dimension[D3]):
  private val shape0 = image.grid.shape(0)
  private val shape1 = image.grid.shape(1)
  private val shape2 = image.grid.shape(2)
  private val frameToIndex = image.grid.indexToFrame.inverse.matrix

  def at(
      point: Point[F, D3],
      output: ScalarValueGradient3,
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  ): Either[ResamplingError, ScalarValueGradient3] =
    image.grid
      .continuousIndexOf(point)
      .left
      .map(error => ResamplingError.Geometry(error))
      .flatMap(index =>
        sampleContinuousIndex(
          index.values(0),
          index.values(1),
          index.values(2),
          output,
          boundary
        )
      )

  private[reframe4s] def sampleContinuousIndex(
      x: Double,
      y: Double,
      z: Double,
      output: ScalarValueGradient3,
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  ): Either[ResamplingError, ScalarValueGradient3] =
    firstNonFinite(x, y, z) match
      case Some((axis, value)) =>
        Left(ResamplingError.NonFiniteContinuousIndex(axis, value))
      case None =>
        val lower0 = lowerCell(x, shape0)
        val lower1 = lowerCell(y, shape1)
        val lower2 = lowerCell(z, shape2)
        val fraction0 = x - lower0.toDouble
        val fraction1 = y - lower1.toDouble
        val fraction2 = z - lower2.toDouble
        val continuous = Vector(x, y, z)
        var sampledValue = 0.0
        var derivative0 = 0.0
        var derivative1 = 0.0
        var derivative2 = 0.0
        var insideWeight = 0.0
        var corner = 0
        while corner < 8 do
          val upper0 = corner & 1
          val upper1 = (corner >> 1) & 1
          val upper2 = (corner >> 2) & 1
          val index0 = lower0 + upper0
          val index1 = lower1 + upper1
          val index2 = lower2 + upper2
          val weight0 = if upper0 == 1 then fraction0 else 1.0 - fraction0
          val weight1 = if upper1 == 1 then fraction1 else 1.0 - fraction1
          val weight2 = if upper2 == 1 then fraction2 else 1.0 - fraction2
          val weight = weight0 * weight1 * weight2
          val inside =
            index0 >= 0 && index0 < shape0 &&
              index1 >= 0 && index1 < shape1 &&
              index2 >= 0 && index2 < shape2
          val tap =
            if inside then image.data(index0, index1, index2)
            else
              boundary match
                case BoundaryPolicy.Constant(value) => value
                case BoundaryPolicy.Reject =>
                  return Left(
                    ResamplingError.Image(
                      ImageError.OutsideGrid(continuous)
                    )
                  )
          sampledValue += weight * tap
          derivative0 +=
            (if upper0 == 1 then 1.0 else -1.0) * weight1 * weight2 * tap
          derivative1 +=
            weight0 * (if upper1 == 1 then 1.0 else -1.0) * weight2 * tap
          derivative2 +=
            weight0 * weight1 * (if upper2 == 1 then 1.0 else -1.0) * tap
          if inside then insideWeight += weight
          corner += 1
        SampledInterpolator
          .validity(insideWeight)
          .left
          .map(error => ResamplingError.Image(error))
          .map { validity =>
            val worldGradient0 =
              frameToIndex(0, 0) * derivative0 +
                frameToIndex(1, 0) * derivative1 +
                frameToIndex(2, 0) * derivative2
            val worldGradient1 =
              frameToIndex(0, 1) * derivative0 +
                frameToIndex(1, 1) * derivative1 +
                frameToIndex(2, 1) * derivative2
            val worldGradient2 =
              frameToIndex(0, 2) * derivative0 +
                frameToIndex(1, 2) * derivative1 +
                frameToIndex(2, 2) * derivative2
            output.update(
              sampledValue,
              worldGradient0,
              worldGradient1,
              worldGradient2,
              validity
            )
            output
          }

  /**
   * Allocation-free complete-support hot path used by sparse optimizers.
   * Returns false without mutating `output` when the coordinate is nonfinite
   * or its supported derivative cell crosses the image boundary.
   */
  private[reframe4s] def sampleFullSupportContinuousIndex(
      x: Double,
      y: Double,
      z: Double,
      output: ScalarValueGradient3
  ): Boolean =
    if !x.isFinite || !y.isFinite || !z.isFinite then false
    else
      val lower0 = lowerCell(x, shape0)
      val lower1 = lowerCell(y, shape1)
      val lower2 = lowerCell(z, shape2)
      if lower0 < 0 || lower0 + 1 >= shape0 ||
        lower1 < 0 || lower1 + 1 >= shape1 ||
        lower2 < 0 || lower2 + 1 >= shape2
      then false
      else
        val fraction0 = x - lower0.toDouble
        val fraction1 = y - lower1.toDouble
        val fraction2 = z - lower2.toDouble
        var sampledValue = 0.0
        var derivative0 = 0.0
        var derivative1 = 0.0
        var derivative2 = 0.0
        var corner = 0
        while corner < 8 do
          val upper0 = corner & 1
          val upper1 = (corner >> 1) & 1
          val upper2 = (corner >> 2) & 1
          val weight0 = if upper0 == 1 then fraction0 else 1.0 - fraction0
          val weight1 = if upper1 == 1 then fraction1 else 1.0 - fraction1
          val weight2 = if upper2 == 1 then fraction2 else 1.0 - fraction2
          val tap = image.data(
            lower0 + upper0,
            lower1 + upper1,
            lower2 + upper2
          )
          sampledValue += weight0 * weight1 * weight2 * tap
          derivative0 +=
            (if upper0 == 1 then 1.0 else -1.0) * weight1 * weight2 * tap
          derivative1 +=
            weight0 * (if upper1 == 1 then 1.0 else -1.0) * weight2 * tap
          derivative2 +=
            weight0 * weight1 * (if upper2 == 1 then 1.0 else -1.0) * tap
          corner += 1
        val worldGradient0 =
          frameToIndex(0, 0) * derivative0 +
            frameToIndex(1, 0) * derivative1 +
            frameToIndex(2, 0) * derivative2
        val worldGradient1 =
          frameToIndex(0, 1) * derivative0 +
            frameToIndex(1, 1) * derivative1 +
            frameToIndex(2, 1) * derivative2
        val worldGradient2 =
          frameToIndex(0, 2) * derivative0 +
            frameToIndex(1, 2) * derivative1 +
            frameToIndex(2, 2) * derivative2
        output.update(
          sampledValue,
          worldGradient0,
          worldGradient1,
          worldGradient2,
          Validity.Full
        )
        true

  def sampleFullSupport(
      xCoordinates: Array[Double],
      yCoordinates: Array[Double],
      zCoordinates: Array[Double],
      count: Int,
      output: SparseValueGradientBuffer3
  ): Either[ResamplingError, Unit] =
    if count < 0 ||
      count > xCoordinates.length ||
      count > yCoordinates.length ||
      count > zCoordinates.length ||
      count > output.capacity
    then
      Left(
        ResamplingError.InvalidSparseSamplingShape(
          count,
          xCoordinates.length,
          yCoordinates.length,
          zCoordinates.length,
          output.capacity
        )
      )
    else
      output.begin(count)
      var index = 0
      while index < count do
        val valid = sampleFullSupportContinuousIndex(
          xCoordinates(index),
          yCoordinates(index),
          zCoordinates(index),
          output.sample
        )
        output.record(index, valid)
        index += 1
      Right(())

  /**
   * Batch the same complete-support kernel from raw coordinates in this
   * sampler's world frame. This package-scoped path exists for typed geometry
   * engines that already preserve the frame owner outside primitive buffers.
   */
  private[reframe4s] def sampleFullSupportWorld(
      worldX: Array[Double],
      worldY: Array[Double],
      worldZ: Array[Double],
      count: Int,
      output: SparseValueGradientBuffer3
  ): Either[ResamplingError, Unit] =
    if count < 0 ||
      count > worldX.length ||
      count > worldY.length ||
      count > worldZ.length ||
      count > output.capacity
    then
      Left(
        ResamplingError.InvalidSparseSamplingShape(
          count,
          worldX.length,
          worldY.length,
          worldZ.length,
          output.capacity
        )
      )
    else
      output.begin(count)
      var index = 0
      while index < count do
        val x = worldX(index)
        val y = worldY(index)
        val z = worldZ(index)
        val continuousX =
          frameToIndex(0, 0) * x +
            frameToIndex(0, 1) * y +
            frameToIndex(0, 2) * z +
            frameToIndex(0, 3)
        val continuousY =
          frameToIndex(1, 0) * x +
            frameToIndex(1, 1) * y +
            frameToIndex(1, 2) * z +
            frameToIndex(1, 3)
        val continuousZ =
          frameToIndex(2, 0) * x +
            frameToIndex(2, 1) * y +
            frameToIndex(2, 2) * z +
            frameToIndex(2, 3)
        val valid =
          sampleFullSupportContinuousIndex(
            continuousX,
            continuousY,
            continuousZ,
            output.sample
          )
        output.record(index, valid)
        index += 1
      Right(())

  /**
   * Complete-support world-coordinate sampling for trial objectives that do
   * not require derivatives. The value uses the same eight taps and summation
   * order as the fused kernel while omitting all derivative arithmetic.
   */
  private[reframe4s] def sampleValuesFullSupportWorld(
      worldX: Array[Double],
      worldY: Array[Double],
      worldZ: Array[Double],
      count: Int,
      values: Array[Double],
      fullSupport: Array[Boolean]
  ): Either[ResamplingError, SparseSamplingCounters] =
    val outputCapacity = math.min(values.length, fullSupport.length)
    if count < 0 ||
      count > worldX.length ||
      count > worldY.length ||
      count > worldZ.length ||
      count > outputCapacity
    then
      Left(
        ResamplingError.InvalidSparseSamplingShape(
          count,
          worldX.length,
          worldY.length,
          worldZ.length,
          outputCapacity
        )
      )
    else
      val accepted = sampleValuesFullSupportWorldUnchecked(
        worldX,
        worldY,
        worldZ,
        count,
        values,
        fullSupport
      )
      Right(
        SparseSamplingCounters(
          count.toLong,
          accepted,
          count.toLong - accepted,
          accepted * 8L
        )
      )

  /**
   * Allocation-free value-only kernel for callers whose reusable buffers were
   * validated when their execution plan was compiled. Returns the number of
   * samples with complete trilinear support.
   */
  private[reframe4s] def sampleValuesFullSupportWorldUnchecked(
      worldX: Array[Double],
      worldY: Array[Double],
      worldZ: Array[Double],
      count: Int,
      values: Array[Double],
      fullSupport: Array[Boolean]
  ): Long =
    var accepted = 0L
    var index = 0
    while index < count do
        val x = worldX(index)
        val y = worldY(index)
        val z = worldZ(index)
        val continuousX =
          frameToIndex(0, 0) * x +
            frameToIndex(0, 1) * y +
            frameToIndex(0, 2) * z +
            frameToIndex(0, 3)
        val continuousY =
          frameToIndex(1, 0) * x +
            frameToIndex(1, 1) * y +
            frameToIndex(1, 2) * z +
            frameToIndex(1, 3)
        val continuousZ =
          frameToIndex(2, 0) * x +
            frameToIndex(2, 1) * y +
            frameToIndex(2, 2) * z +
            frameToIndex(2, 3)
        val finite =
          continuousX.isFinite && continuousY.isFinite && continuousZ.isFinite
        val lower0 = if finite then lowerCell(continuousX, shape0) else 0
        val lower1 = if finite then lowerCell(continuousY, shape1) else 0
        val lower2 = if finite then lowerCell(continuousZ, shape2) else 0
        val valid =
          finite &&
            lower0 >= 0 && lower0 + 1 < shape0 &&
            lower1 >= 0 && lower1 + 1 < shape1 &&
            lower2 >= 0 && lower2 + 1 < shape2
        if valid then
          val fraction0 = continuousX - lower0.toDouble
          val fraction1 = continuousY - lower1.toDouble
          val fraction2 = continuousZ - lower2.toDouble
          var sampledValue = 0.0
          var corner = 0
          while corner < 8 do
            val upper0 = corner & 1
            val upper1 = (corner >> 1) & 1
            val upper2 = (corner >> 2) & 1
            val weight0 =
              if upper0 == 1 then fraction0 else 1.0 - fraction0
            val weight1 =
              if upper1 == 1 then fraction1 else 1.0 - fraction1
            val weight2 =
              if upper2 == 1 then fraction2 else 1.0 - fraction2
            sampledValue +=
              weight0 * weight1 * weight2 *
                image.data(
                  lower0 + upper0,
                  lower1 + upper1,
                  lower2 + upper2
                )
            corner += 1
          values(index) = sampledValue
          fullSupport(index) = true
          accepted += 1L
        else
          values(index) = 0.0
          fullSupport(index) = false
        index += 1
    accepted

  private def lowerCell(coordinate: Double, extent: Int): Int =
    if coordinate == (extent - 1).toDouble then extent - 2
    else math.floor(coordinate).toInt

  private def firstNonFinite(
      x: Double,
      y: Double,
      z: Double
  ): Option[(Int, Double)] =
    if !x.isFinite then Some((0, x))
    else if !y.isFinite then Some((1, y))
    else if !z.isFinite then Some((2, z))
    else None

object LinearValueGradientSampler3:
  def compile[
      F <: Frame[D3],
      S <: SampleSpace[F, D3]
  ](
      image: ContinuousImage[S, Double, Rank[3]]
  )(using Dimension[D3]): Either[
    ResamplingError,
    LinearValueGradientSampler3[F, S]
  ] =
    if image.nonSpatialAxes.size != 0 then
      Left(
        ResamplingError.NonSpatialDerivativeAxes(
          image.nonSpatialAxes.size
        )
      )
    else
      image.grid.shape.zipWithIndex.collectFirst {
        case (extent, axis) if extent < 2 =>
          ResamplingError.DerivativeExtentTooSmall(axis, extent)
      } match
        case Some(error) => Left(error)
        case None => Right(new LinearValueGradientSampler3(image))
