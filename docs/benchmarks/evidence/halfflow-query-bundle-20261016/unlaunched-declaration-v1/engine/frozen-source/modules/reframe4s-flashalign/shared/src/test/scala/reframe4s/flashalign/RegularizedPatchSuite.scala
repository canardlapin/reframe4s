package reframe4s.flashalign

/** Independent augmented-unit-vector oracle at and across vanishing contrast. */
final class RegularizedPatchSuite extends munit.FunSuite:
  private val epsilon = 1e-12
  private val config = PatchObjectiveConfig.create(0.995, 0.4, 0.01, epsilon)
    .fold(error => fail(error.message), identity)
  private val movingValues = Array.tabulate(27)(i => math.sin(i * 0.37) + 0.2 * math.cos(i * 0.71))
  private val moving = PatchObjective.prepareMoving(movingValues, config)
    .fold(error => fail(error.message), identity)

  test("dynamic contrast crosses the former threshold continuously"):
    def value(scale: Double): Double =
      val fixed = moving.normalizedCopy.map(_ * math.sqrt(epsilon) * scale)
      PatchObjective.evaluate(moving, fixed, true, config).fold(error => fail(error.message), _.loss)
    val coarse = math.abs(value(1 + 1e-4) - value(1 - 1e-4))
    val fine = math.abs(value(1 + 1e-7) - value(1 - 1e-7))
    assert(fine < coarse * 0.002, s"threshold jump: coarse=$coarse fine=$fine")
    assert(fine < 1e-6)

  test("regularized statistics match explicit augmented Jacobians and finite differences"):
    for
      parameters <- Vector(6, 12)
      ratio <- Vector(0.0, 0.1, 0.999, 1.0, 1.001, 10.0)
      constantColumn <- Vector(false, true)
    do
      val fixed = moving.normalizedCopy.map(_ * math.sqrt(epsilon * ratio))
      val derivatives = Array.tabulate(fixed.length, parameters)((i, p) =>
        math.sqrt(epsilon) * (if constantColumn && p == 0 then 3.0 else math.sin((i + 1) * (p + 1) * 0.13)))
      val samples = new PatchSampleSource:
        val sampleCount: Int = fixed.length
        val parameterCount: Int = parameters
        def value(i: Int): Double = fixed(i)
        def derivative(i: Int, p: Int): Double = derivatives(i)(p)
      val workspace = PatchLinearizationWorkspace.create(parameters).fold(error => fail(error.message), identity)
      val result = PatchLinearization.compute(moving, samples, epsilon, workspace)
        .fold(error => fail(error.message), identity)
      assert(result.active, s"ratio=$ratio parameters=$parameters")
      val centered = fixed.map(_ - fixed.sum / fixed.length)
      val augmented = centered :+ math.sqrt(epsilon)
      val norm = math.sqrt(augmented.map(x => x * x).sum)
      val unit = augmented.map(_ / norm)
      val movingUnit = moving.normalizedCopy :+ 0.0
      val explicit = Array.ofDim[Double](fixed.length + 1, parameters)
      for p <- 0 until parameters do
        val mean = derivatives.map(_(p)).sum / fixed.length
        val column = derivatives.map(_(p) - mean) :+ 0.0
        val projection = unit.zip(column).map(_ * _).sum
        for i <- unit.indices do explicit(i)(p) = (column(i) - unit(i) * projection) / norm
      val objective = PatchObjective.evaluate(moving, fixed, true, config)
        .fold(error => fail(error.message), identity)
      assertEqualsDouble(result.correlation, movingUnit.zip(unit).map(_ * _).sum, 2e-14)
      for p <- 0 until parameters do
        val direction = explicit.indices.map(i => explicit(i)(p) * movingUnit(i)).sum
        assertEqualsDouble(result.jtu(p), direction, 2e-10)
        for q <- 0 until parameters do
          val gram = explicit.indices.map(i => explicit(i)(p) * explicit(i)(q)).sum
          assertEqualsDouble(result.curvature(p, q), gram, 2e-10)
        val step = 1e-5
        def perturbed(sign: Double): Double =
          val values = fixed.indices.map(i => fixed(i) + sign * step * derivatives(i)(p)).toArray
          PatchObjective.evaluate(moving, values, true, config).fold(error => fail(error.message), _.loss)
        val numeric = (perturbed(1) - perturbed(-1)) / (2 * step)
        assertEqualsDouble(-objective.posterior.signedWeight * result.jtu(p), numeric, 2e-8)
      if constantColumn then
        assertEqualsDouble(result.jtu(0), 0.0, 1e-12)
        assertEqualsDouble(result.curvature(0, 0), 0.0, 1e-12)
