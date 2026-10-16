package reframe4s.resample

/** Catmull-Rom interpolation weights for the four offsets -1 through 2. */
private[resample] object CubicKernel:
  val TapCount: Int = 4

  inline def weight(fraction: Double, tap: Int): Double =
    catmullRom(fraction - (tap - 1).toDouble)

  inline def catmullRom(distance: Double): Double =
    val absolute = math.abs(distance)
    if absolute <= 1.0 then
      (1.5 * absolute - 2.5) * absolute * absolute + 1.0
    else if absolute < 2.0 then
      ((-0.5 * absolute + 2.5) * absolute - 4.0) * absolute + 2.0
    else 0.0
