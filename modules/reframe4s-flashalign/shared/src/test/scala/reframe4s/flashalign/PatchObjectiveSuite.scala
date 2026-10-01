package reframe4s.flashalign

final class PatchObjectiveSuite extends munit.FunSuite:
  test("production loss and local model match the explicit C/P/J oracle"):
    val moving = signal(27, phase = 0.17)
    val fixed = signal(27, phase = -0.31)
    val intensityJacobian = jacobian(27, 5)
    val config = objectiveConfig(0.1, 0.55, 0.02)
    val prepared = patch(PatchObjective.prepareMoving(moving, config))
    val objective = patch(
      PatchObjective.evaluate(prepared, fixed, true, config)
    )
    val reference = PatchObjectiveReference.evaluate(
      moving,
      fixed,
      intensityJacobian,
      0.1,
      0.55,
      0.02
    )
    val linear = patch(
      PatchObjective.linearize(
        objective,
        reference.jtu,
        reference.jtj,
        parameterCount = 5
      )
    )

    assertEqualsDouble(objective.loss, reference.loss, 2e-14)
    assertEqualsDouble(objective.correlation, reference.correlation, 2e-14)
    assertEqualsDouble(
      objective.posterior.outlier,
      reference.outlierWeight,
      2e-14
    )
    assertEqualsDouble(
      objective.posterior.positive,
      reference.positiveWeight,
      2e-14
    )
    assertEqualsDouble(
      objective.posterior.negative,
      reference.negativeWeight,
      2e-14
    )
    assertArrayClose(linear.gradient, reference.gradient, 2e-14)
    assertArrayClose(linear.curvatureRowMajor, reference.curvature, 2e-14)
    assertPositiveSemidefinite(linear.curvatureRowMajor, 5)

  test("mixture gradient passes finite differences across priors tau and floors"):
    val moving = signal(27, phase = 0.07)
    val fixed = signal(27, phase = -0.23)
    val intensityJacobian = jacobian(27, 4)
    val settings =
      for
        prior <- Vector(0.0, 0.1, 0.5, 0.9, 1.0)
        tau <- Vector(0.35, 0.55, 0.9)
        floor <- Vector(0.005, 0.02, 0.15)
      yield (prior, tau, floor)

    settings.foreach { case (prior, tau, floor) =>
      val config = objectiveConfig(prior, tau, floor)
      val prepared = patch(PatchObjective.prepareMoving(moving, config))
      val objective = patch(PatchObjective.evaluate(prepared, fixed, true, config))
      val reference = PatchObjectiveReference.evaluate(
        moving,
        fixed,
        intensityJacobian,
        prior,
        tau,
        floor
      )
      val linear = patch(
        PatchObjective.linearize(objective, reference.jtu, reference.jtj, 4)
      )
      var parameter = 0
      while parameter < 4 do
        val measurements = Vector(1e-3, 3e-4, 1e-4, 3e-5, 1e-5).map { step =>
          val plus = perturb(fixed, intensityJacobian, parameter, step)
          val minus = perturb(fixed, intensityJacobian, parameter, -step)
          val numeric =
            (loss(prepared, plus, config) - loss(prepared, minus, config)) /
              (2.0 * step)
          (relativeError(linear.gradient(parameter), numeric), numeric)
        }
        val matches = measurements.exists { case (relative, numeric) =>
          math.abs(linear.gradient(parameter) - numeric) <= 1e-11 ||
          relative <= 1e-6
        }
        assert(
          matches,
          s"gradient failed for prior=$prior tau=$tau floor=$floor parameter=$parameter analytic=${linear.gradient(parameter)} measurements=$measurements"
        )
        parameter += 1
    }

  test("gain offset and large represented offsets preserve the objective"):
    val moving = Array.tabulate(27)(index => ((index * 7) % 13).toDouble * 0.25)
    val fixed = Array.tabulate(27)(index => ((index * 5 + 3) % 17).toDouble * 0.5)
    val config = objectiveConfig(0.3, 0.6, 0.04)
    val baseMoving = patch(PatchObjective.prepareMoving(moving, config))
    val base = patch(PatchObjective.evaluate(baseMoving, fixed, true, config))
    val shiftedMoving = moving.map(value => 4.0 * value + 1e12)
    val shiftedFixed = fixed.map(value => 2.0 * value - 1e12)
    val shiftedPrepared = patch(
      PatchObjective.prepareMoving(shiftedMoving, config)
    )
    val shifted = patch(
      PatchObjective.evaluate(shiftedPrepared, shiftedFixed, true, config)
    )

    assertEqualsDouble(shifted.correlation, base.correlation, 2e-14)
    assertEqualsDouble(shifted.loss, base.loss, 2e-14)
    assertEqualsDouble(
      sum(shiftedPrepared.normalized),
      0.0,
      2e-15
    )
    assertEqualsDouble(
      sumSquares(shiftedPrepared.normalized),
      1.0,
      2e-15
    )

  test("degenerate and invalid dynamic patches retain finite outlier cost"):
    val config = objectiveConfig(0.1, 0.55, 0.02)
    PatchObjective.prepareMoving(Array.fill(27)(4.0), config) match
      case Left(PatchObjectiveError.InsufficientContrast(0.0, _)) => ()
      case other => fail(s"expected static contrast rejection, got $other")

    val prepared = patch(
      PatchObjective.prepareMoving(signal(27, phase = 0.0), config)
    )
    val lowContrast = patch(
      PatchObjective.evaluate(prepared, Array.fill(27)(9.0), true, config)
    )
    val unsupported = patch(
      PatchObjective.evaluate(prepared, Array.emptyDoubleArray, false, config)
    )
    Vector(lowContrast, unsupported).foreach { value =>
      assert(!value.active)
      assertEqualsDouble(value.loss, config.outlierCost, 0.0)
      assertEqualsDouble(value.posterior.outlier, 1.0, 0.0)
      val linear = patch(
        PatchObjective.linearize(value, Array.fill(3)(2.0), Array.fill(9)(3.0), 3)
      )
      assert(linear.gradient.forall(_ == 0.0))
      assert(linear.curvatureRowMajor.forall(_ == 0.0))
    }

  test("nonfinite samples and a zero outlier floor fail closed"):
    PatchObjectiveConfig.create(0.1, 0.55, 0.0, 1e-12) match
      case Left(
            PatchObjectiveError.InvalidOpenProbability(
              PatchObjectiveParameter.OutlierFloor,
              0.0
            )
          ) => ()
      case other => fail(s"expected strict floor rejection, got $other")

    val config = objectiveConfig(0.1, 0.55, 0.02)
    val prepared = patch(
      PatchObjective.prepareMoving(signal(27, phase = 0.0), config)
    )
    val nonfinite = signal(27, phase = 0.2)
    nonfinite(11) = Double.NaN
    PatchObjective.evaluate(prepared, nonfinite, true, config) match
      case Left(PatchObjectiveError.NonFiniteSample(11, value)) =>
        assert(value.isNaN)
      case other => fail(s"expected nonfinite rejection, got $other")

  test("correlations at the numerical boundary remain valid"):
    val values = signal(27, phase = 0.11)
    val config = objectiveConfig(0.8, 0.45, 0.03)
    val prepared = patch(PatchObjective.prepareMoving(values, config))
    val same = patch(PatchObjective.evaluate(prepared, values, true, config))
    val opposite = patch(
      PatchObjective.evaluate(prepared, values.map(-_), true, config)
    )

    assertEqualsDouble(same.correlation, 1.0, 1e-14)
    assertEqualsDouble(opposite.correlation, -1.0, 1e-14)
    assert(same.loss >= 0.0 && same.loss <= config.outlierCost)
    assert(opposite.loss >= 0.0 && opposite.loss <= config.outlierCost)
    assertEqualsDouble(
      same.posterior.outlier +
        same.posterior.positive +
        same.posterior.negative,
      1.0,
      2e-15
    )

  test("the softened-norm legacy projector curvature is detectably wrong"):
    val sampleCount = 27
    val parameterCount = 4
    val fixed = signal(sampleCount, phase = 0.3)
    val intensityJacobian = jacobian(sampleCount, parameterCount)
    val centeredFixed = centered(fixed)
    val softenedEnergy = sumSquares(centeredFixed) + 2.0
    val softenedNorm = math.sqrt(softenedEnergy)
    val v = centeredFixed.map(_ / softenedNorm)
    val projector = Array.tabulate(sampleCount, sampleCount) { (row, column) =>
      (if row == column then 1.0 else 0.0) -
        1.0 / sampleCount.toDouble -
        v(row) * v(column)
    }
    val j = multiply(projector, intensityJacobian).map(
      _.map(_ / softenedNorm)
    )
    val actual = transposeProduct(j)
    val wrong = scale(
      sandwich(intensityJacobian, projector),
      1.0 / softenedEnergy
    )
    val error = relativeArrayError(wrong, actual)

    assert(error > 1e-3, s"legacy softened curvature was not falsified: $error")

  private def objectiveConfig(
      prior: Double,
      tau: Double,
      floor: Double
  ): PatchObjectiveConfig =
    patch(
      PatchObjectiveConfig.create(
        prior,
        tau,
        floor,
        minimumContrastEnergy = 1e-20
      )
    )

  private def signal(size: Int, phase: Double): Array[Double] =
    Array.tabulate(size) { index =>
      math.sin(index * 0.37 + phase) +
        0.31 * math.cos(index * 0.13 - 0.7 * phase) +
        0.015 * index.toDouble
    }

  private def jacobian(
      samples: Int,
      parameters: Int
  ): Array[Array[Double]] =
    Array.tabulate(samples, parameters) { (sample, parameter) =>
      math.sin((sample + 1) * (parameter + 2) * 0.071) +
        0.2 * math.cos(sample * 0.17 - parameter * 0.29)
    }

  private def perturb(
      values: Array[Double],
      intensityJacobian: Array[Array[Double]],
      parameter: Int,
      step: Double
  ): Array[Double] =
    Array.tabulate(values.length) { index =>
      values(index) + step * intensityJacobian(index)(parameter)
    }

  private def loss(
      moving: PreparedMovingPatch,
      fixed: Array[Double],
      config: PatchObjectiveConfig
  ): Double =
    patch(PatchObjective.evaluate(moving, fixed, true, config)).loss

  private def assertPositiveSemidefinite(
      matrix: Array[Double],
      size: Int
  ): Unit =
    Vector.tabulate(13) { probe =>
      Array.tabulate(size)(index =>
        math.sin((probe + 1) * (index + 1) * 0.41)
      )
    }.foreach { direction =>
      var quadratic = 0.0
      var row = 0
      while row < size do
        var column = 0
        while column < size do
          quadratic +=
            direction(row) * matrix(row * size + column) * direction(column)
          column += 1
        row += 1
      assert(quadratic >= -1e-12, s"curvature is not PSD: $quadratic")
    }

  private def centered(values: Array[Double]): Array[Double] =
    val mean = sum(values) / values.length.toDouble
    values.map(_ - mean)

  private def sum(values: Array[Double]): Double =
    var result = 0.0
    var index = 0
    while index < values.length do
      result += values(index)
      index += 1
    result

  private def sumSquares(values: Array[Double]): Double =
    var result = 0.0
    var index = 0
    while index < values.length do
      result += values(index) * values(index)
      index += 1
    result

  private def multiply(
      left: Array[Array[Double]],
      right: Array[Array[Double]]
  ): Array[Array[Double]] =
    Array.tabulate(left.length, right(0).length) { (row, column) =>
      var result = 0.0
      var inner = 0
      while inner < right.length do
        result += left(row)(inner) * right(inner)(column)
        inner += 1
      result
    }

  private def transposeProduct(
      matrix: Array[Array[Double]]
  ): Array[Double] =
    val columns = matrix(0).length
    Array.tabulate(columns * columns) { flat =>
      val first = flat / columns
      val second = flat % columns
      var result = 0.0
      var row = 0
      while row < matrix.length do
        result += matrix(row)(first) * matrix(row)(second)
        row += 1
      result
    }

  private def sandwich(
      design: Array[Array[Double]],
      projector: Array[Array[Double]]
  ): Array[Double] =
    val projected = multiply(projector, design)
    val parameters = design(0).length
    Array.tabulate(parameters * parameters) { flat =>
      val first = flat / parameters
      val second = flat % parameters
      var result = 0.0
      var row = 0
      while row < design.length do
        result += design(row)(first) * projected(row)(second)
        row += 1
      result
    }

  private def scale(values: Array[Double], factor: Double): Array[Double] =
    values.map(_ * factor)

  private def relativeError(left: Double, right: Double): Double =
    math.abs(left - right) / math.max(math.abs(right), 1e-12)

  private def relativeArrayError(
      left: Array[Double],
      right: Array[Double]
  ): Double =
    math.sqrt(sumSquares(left.zip(right).map { case (a, b) => a - b })) /
      math.max(math.sqrt(sumSquares(right)), 1e-30)

  private def assertArrayClose(
      actual: Array[Double],
      expected: Array[Double],
      tolerance: Double
  ): Unit =
    assertEquals(actual.length, expected.length)
    actual.indices.foreach { index =>
      assertEqualsDouble(actual(index), expected(index), tolerance)
    }

  private def patch[A](result: Either[PatchObjectiveError, A]): A =
    result.fold(error => fail(error.message), identity)
