package reframe4s.flashalign

final class PatchLinearizationSuite extends munit.FunSuite:
  test("streamed six and twelve parameter statistics match explicit Jacobians"):
    Vector(6, 12).foreach { parameterCount =>
      val moving = signal(27, 0.19)
      val fixed = signal(27, -0.27).map(_ + 1e8)
      val derivatives = jacobian(27, parameterCount, offset = 0.0)
      val prepared = movingPatch(moving)
      val workspace = linearWorkspace(parameterCount)
      val result = linearize(
        prepared,
        new ArrayPatchSamples(fixed, derivatives),
        workspace
      )
      val reference = PatchObjectiveReference.evaluate(
        moving,
        fixed,
        derivatives,
        positivePrior = 0.3,
        tau = 0.6,
        outlierFloor = 0.04
      )

      assertEquals(result.mode, PatchStatisticsMode.Streamed)
      assertEqualsDouble(result.correlation, reference.correlation, 1e-10)
      assertArrayClose(result.jtu, reference.jtu, 1e-10, 1e-9)
      var first = 0
      while first < parameterCount do
        var second = 0
        while second < parameterCount do
          assertClose(
            result.curvature(first, second),
            reference.jtj(first * parameterCount + second),
            1e-10,
            1e-9
          )
          second += 1
        first += 1
    }

  test("one workspace reuses its result and packed storage"):
    val parameterCount = 6
    val prepared = movingPatch(signal(27, 0.11))
    val samples = new ArrayPatchSamples(
      signal(27, -0.17),
      jacobian(27, parameterCount, offset = 0.0)
    )
    val workspace = linearWorkspace(parameterCount)
    val first = linearize(prepared, samples, workspace)
    val firstDirection = first.jtu
    val firstCurvature = first.jtjUpper
    val second = linearize(prepared, samples, workspace)

    assert(first eq second)
    assert(firstDirection eq second.jtu)
    assert(firstCurvature eq second.jtjUpper)
    assertEquals(firstCurvature.length, 21)

  test("near-cancelled constant directions use an observable centred recomputation"):
    val parameterCount = 6
    val derivatives = jacobian(27, parameterCount, offset = 0.0)
    var sample = 0
    while sample < derivatives.length do
      derivatives(sample)(2) = 17.0
      sample += 1
    val prepared = movingPatch(signal(27, 0.05))
    val workspace = linearWorkspace(parameterCount)
    val result = linearize(
      prepared,
      new ArrayPatchSamples(signal(27, -0.13), derivatives),
      workspace
    )

    assertEquals(result.mode, PatchStatisticsMode.CenteredDiagnostic)
    assertEqualsDouble(result.curvature(2, 2), 0.0, 1e-12)
    assertEqualsDouble(result.jtu(2), 0.0, 1e-12)
    assertEquals(
      workspace.diagnostics,
      PatchLinearizationDiagnostics(1L, 1L, 0L)
    )

  test("material streamed cancellation yields to the explicit-J result"):
    val parameterCount = 12
    val moving = signal(27, 0.23)
    val fixed = signal(27, -0.09)
    val prepared = movingPatch(moving)
    val derivatives = jacobian(27, parameterCount, offset = 1e12)
    val workspace = linearWorkspace(parameterCount)
    val result = PatchLinearization.compute(
      prepared,
      new ArrayPatchSamples(fixed, derivatives),
      minimumContrastEnergy = 1e-20,
      workspace
    ).fold(error => fail(error.message), identity)
    val reference = PatchObjectiveReference.evaluate(
      moving,
      fixed,
      derivatives,
      positivePrior = 0.3,
      tau = 0.6,
      outlierFloor = 0.04
    )

    assertEquals(result.mode, PatchStatisticsMode.CenteredDiagnostic)
    assertArrayClose(result.jtu, reference.jtu, 1e-10, 1e-8)
    var first = 0
    while first < parameterCount do
      var second = 0
      while second < parameterCount do
        assertClose(
          result.curvature(first, second),
          reference.jtj(first * parameterCount + second),
          1e-10,
          1e-8
        )
        second += 1
      first += 1
    assertEquals(workspace.diagnostics.centeredRecomputations, 1L)
    assertEquals(workspace.diagnostics.acceptedCenteredRecomputations, 1L)
    assertEquals(workspace.diagnostics.failedCenteredRecomputations, 0L)

  test("explicit-J fallback matches the independent projector for poorly constrained columns"):
    Vector(6, 12).foreach { parameterCount =>
      val moving = signal(9, 0.07)
      val fixed = signal(9, -0.19).map(_ + 1e8)
      val derivatives = jacobian(9, parameterCount, offset = 1e6)
      var sample = 0
      while sample < derivatives.length do
        derivatives(sample)(1) = derivatives(sample)(0)
        sample += 1

      val workspace = linearWorkspace(parameterCount)
      val result = linearize(
        movingPatch(moving),
        new ArrayPatchSamples(fixed, derivatives),
        workspace
      )
      val reference = PatchObjectiveReference.evaluate(
        moving,
        fixed,
        derivatives,
        positivePrior = 0.3,
        tau = 0.6,
        outlierFloor = 0.04
      )

      assertEquals(result.mode, PatchStatisticsMode.CenteredDiagnostic)
      assertArrayClose(result.jtu, reference.jtu, 1e-10, 1e-8)
      var first = 0
      while first < parameterCount do
        var second = 0
        while second < parameterCount do
          assertClose(
            result.curvature(first, second),
            reference.jtj(first * parameterCount + second),
            1e-10,
            1e-8
          )
          second += 1
        first += 1
      assertClose(result.jtu(0), result.jtu(1), 1e-12, 1e-10)
      assertClose(
        result.curvature(0, 0) * result.curvature(1, 1) -
          result.curvature(0, 1) * result.curvature(0, 1),
        0.0,
        1e-12,
        1e-10
      )
    }

  test("explicit-J direction agrees with central finite differences"):
    Vector(6, 12).foreach { parameterCount =>
      val moving = signal(11, 0.13)
      val fixed = signal(11, -0.21)
      val derivatives = jacobian(11, parameterCount, offset = 1e6)
      val result = linearize(
        movingPatch(moving),
        new ArrayPatchSamples(fixed, derivatives),
        linearWorkspace(parameterCount)
      )
      val step = 1e-5

      assertEquals(result.mode, PatchStatisticsMode.CenteredDiagnostic)
      var parameter = 0
      while parameter < parameterCount do
        val plus = Array.tabulate(fixed.length)(sample =>
          fixed(sample) + step * derivatives(sample)(parameter)
        )
        val minus = Array.tabulate(fixed.length)(sample =>
          fixed(sample) - step * derivatives(sample)(parameter)
        )
        val finiteDifference =
          (normalizedCorrelation(moving, plus) -
            normalizedCorrelation(moving, minus)) /
            (2.0 * step)
        assertClose(result.jtu(parameter), finiteDifference, 2e-9, 2e-7)
        parameter += 1
    }

  test("near-perfect and near-constant fixed patches stay finite and distinct"):
    val moving = signal(9, 0.17)
    val derivatives = jacobian(9, 6, offset = 1e6)
    val nearlyPerfect = Array.tabulate(moving.length)(index =>
      1e8 + moving(index) + 1e-8 * math.cos(index * 0.29)
    )
    val perfectResult = linearize(
      movingPatch(moving),
      new ArrayPatchSamples(nearlyPerfect, derivatives),
      linearWorkspace(6)
    )

    assertEquals(perfectResult.mode, PatchStatisticsMode.CenteredDiagnostic)
    assert(perfectResult.correlation.isFinite)
    assert(perfectResult.correlation <= 1.0 + 1e-12)
    assert(perfectResult.correlation >= 1.0 - 1e-12)
    assert(perfectResult.jtu.forall(_.isFinite))
    assert(perfectResult.jtjUpper.forall(_.isFinite))

    val nearlyConstant = Array.tabulate(moving.length)(index =>
      7.0 + 1e-10 * math.sin(index * 0.31)
    )
    val lowContrastWorkspace = linearWorkspace(6)
    val lowContrast = PatchLinearization.compute(
      movingPatch(moving),
      new ArrayPatchSamples(nearlyConstant, derivatives),
      minimumContrastEnergy = 1e-16,
      lowContrastWorkspace
    ).fold(error => fail(error.message), identity)
    assertEquals(lowContrast.mode, PatchStatisticsMode.InvalidContrast)
    assert(lowContrast.jtu.forall(_ == 0.0))
    assert(lowContrast.jtjUpper.forall(_ == 0.0))

  test("invalid contrast is inactive and nonfinite derivatives fail"):
    val parameterCount = 6
    val prepared = movingPatch(signal(27, 0.15))
    val derivatives = jacobian(27, parameterCount, offset = 0.0)
    val workspace = linearWorkspace(parameterCount)
    val invalid = linearize(
      prepared,
      new ArrayPatchSamples(Array.fill(27)(4.0), derivatives),
      workspace
    )
    assert(!invalid.active)
    assertEquals(invalid.mode, PatchStatisticsMode.InvalidContrast)
    assert(invalid.jtu.forall(_ == 0.0))
    assert(invalid.jtjUpper.forall(_ == 0.0))

    derivatives(9)(4) = Double.PositiveInfinity
    PatchLinearization.compute(
      prepared,
      new ArrayPatchSamples(signal(27, -0.2), derivatives),
      minimumContrastEnergy = 1e-20,
      workspace
    ) match
      case Left(
            PatchLinearizationError.NonFiniteDerivative(
              9,
              4,
              Double.PositiveInfinity
            )
          ) => ()
      case other => fail(s"expected nonfinite derivative failure, got $other")

  private final class ArrayPatchSamples(
      values: Array[Double],
      derivatives: Array[Array[Double]]
  ) extends PatchSampleSource:
    val sampleCount: Int = values.length
    val parameterCount: Int = derivatives(0).length
    def value(sample: Int): Double = values(sample)
    def derivative(sample: Int, parameter: Int): Double =
      derivatives(sample)(parameter)

  private def movingPatch(values: Array[Double]): PreparedMovingPatch =
    val config = patch(
      PatchObjectiveConfig.create(
        positivePolarityPrior = 0.1,
        tau = 0.55,
        outlierFloor = 0.02,
        minimumContrastEnergy = 1e-20
      )
    )
    patch(PatchObjective.prepareMoving(values, config))

  private def linearWorkspace(
      parameterCount: Int
  ): PatchLinearizationWorkspace =
    linear(PatchLinearizationWorkspace.create(parameterCount))

  private def linearize(
      moving: PreparedMovingPatch,
      samples: PatchSampleSource,
      workspace: PatchLinearizationWorkspace
  ): PatchLinearizationResult =
    linear(
      PatchLinearization.compute(
        moving,
        samples,
        minimumContrastEnergy = 1e-20,
        workspace
      )
    )

  private def signal(size: Int, phase: Double): Array[Double] =
    Array.tabulate(size) { index =>
      math.sin(index * 0.31 + phase) +
        0.23 * math.cos(index * 0.17 - phase) +
        index * 0.012
    }

  private def jacobian(
      samples: Int,
      parameters: Int,
      offset: Double
  ): Array[Array[Double]] =
    Array.tabulate(samples, parameters) { (sample, parameter) =>
      offset +
        math.sin((sample + 1) * (parameter + 2) * 0.059) +
        0.17 * math.cos(sample * 0.11 - parameter * 0.37)
    }

  private def normalizedCorrelation(
      left: Array[Double],
      right: Array[Double]
  ): Double =
    val leftMean = left.sum / left.length.toDouble
    val rightMean = right.sum / right.length.toDouble
    var dot = 0.0
    var leftEnergy = 0.0
    var rightEnergy = 0.0
    var index = 0
    while index < left.length do
      val leftCentered = left(index) - leftMean
      val rightCentered = right(index) - rightMean
      dot += leftCentered * rightCentered
      leftEnergy += leftCentered * leftCentered
      rightEnergy += rightCentered * rightCentered
      index += 1
    dot / math.sqrt(leftEnergy * rightEnergy)

  private def assertArrayClose(
      actual: Array[Double],
      expected: Array[Double],
      absoluteTolerance: Double,
      relativeTolerance: Double
  ): Unit =
    assertEquals(actual.length, expected.length)
    actual.indices.foreach { index =>
      assertClose(
        actual(index),
        expected(index),
        absoluteTolerance,
        relativeTolerance
      )
    }

  private def assertClose(
      actual: Double,
      expected: Double,
      absoluteTolerance: Double,
      relativeTolerance: Double
  ): Unit =
    val error = math.abs(actual - expected)
    val scale = math.max(math.abs(actual), math.abs(expected))
    assert(
      error <= absoluteTolerance || error <= relativeTolerance * scale,
      s"$actual != $expected, absolute error $error at scale $scale"
    )

  private def patch[A](result: Either[PatchObjectiveError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def linear[A](result: Either[PatchLinearizationError, A]): A =
    result.fold(error => fail(error.message), identity)
