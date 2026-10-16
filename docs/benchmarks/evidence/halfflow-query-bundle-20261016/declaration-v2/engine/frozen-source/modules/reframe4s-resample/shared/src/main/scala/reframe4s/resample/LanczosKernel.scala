package reframe4s.resample

/**
 * Shared numerical definition for the production Lanczos-5 policy.
 *
 * The ten taps cover `floor(x) - 4` through `floor(x) + 5`. We normalize the
 * finite window so constants are reproduced exactly. This object owns no image
 * or geometry semantics; callers retain boundary and validity policy.
 */
private[resample] object LanczosKernel:
  val Radius: Int = 5
  val TapCount: Int = Radius * 2
  val WeightTolerance: Double = 1e-15

  /**
   * Whether every index in the ten-tap stencil can be represented as an Int.
   */
  def hasRepresentableStencil(coordinate: Double): Boolean =
    val base = math.floor(coordinate)
    coordinate.isFinite &&
    base >= Int.MinValue.toDouble + Radius.toDouble - 1.0 &&
    base <= Int.MaxValue.toDouble - Radius.toDouble

  /**
   * Fill one reusable ten-element lane and return its first source index.
   *
   * The coordinate must satisfy [[hasRepresentableStencil]].
   */
  def fillNormalized(
      coordinate: Double,
      weights: Array[Double]
  ): Int =
    val base = math.floor(coordinate).toInt
    val first = base - Radius + 1
    var sum = 0.0
    var tap = 0
    while tap < TapCount do
      val value = weight(coordinate - (first + tap).toDouble)
      weights(tap) = value
      sum += value
      tap += 1

    tap = 0
    while tap < TapCount do
      weights(tap) /= sum
      tap += 1
    first

  def absoluteSum(weights: Array[Double]): Double =
    var total = 0.0
    var tap = 0
    while tap < TapCount do
      total += math.abs(weights(tap))
      tap += 1
    total

  private def weight(distance: Double): Double =
    val absolute = math.abs(distance)
    if absolute >= Radius.toDouble then 0.0
    else if distance == 0.0 then 1.0
    else if distance == math.rint(distance) then 0.0
    else
      sinc(distance) * sinc(distance / Radius.toDouble)

  private def sinc(value: Double): Double =
    val radians = math.Pi * value
    math.sin(radians) / radians
