package reframe4s.spectral

final class Radix2FftSuite extends munit.FunSuite:
  test("forward conventions match direct DFT on even radix-two sizes"):
    Vector(2, 4, 8, 16, 32, 64).foreach { size =>
      val plan = spectral(Radix2FftPlan.create(size))
      val inputReal = Array.tabulate(size)(index =>
        math.sin(0.37 * index.toDouble) + 0.13 * index.toDouble
      )
      val inputImaginary = Array.tabulate(size)(index =>
        0.2 * math.cos(0.29 * index.toDouble)
      )
      val outputReal = new Array[Double](size)
      val outputImaginary = new Array[Double](size)
      spectral(
        plan.transform(
          inputReal,
          inputImaginary,
          outputReal,
          outputImaginary,
          FftDirection.Forward,
          plan.newWorkspace()
        )
      )
      val expected = directDft(inputReal, inputImaginary, inverse = false)
      assertArrayClose(outputReal, expected._1, 2e-12)
      assertArrayClose(outputImaginary, expected._2, 2e-12)
    }

  test("inverse is scaled by one over N and restores complex input"):
    val size = 8
    val plan = spectral(Radix2FftPlan.create(size))
    val workspace = plan.newWorkspace()
    val inputReal = Array.tabulate(size)(index => math.sin(0.23 * index))
    val inputImaginary = Array.tabulate(size)(index => math.cos(0.17 * index))
    val spectrumReal = new Array[Double](size)
    val spectrumImaginary = new Array[Double](size)
    val restoredReal = new Array[Double](size)
    val restoredImaginary = new Array[Double](size)
    spectral(
      plan.transform(
        inputReal,
        inputImaginary,
        spectrumReal,
        spectrumImaginary,
        FftDirection.Forward,
        workspace
      )
    )
    spectral(
      plan.transform(
        spectrumReal,
        spectrumImaginary,
        restoredReal,
        restoredImaginary,
        FftDirection.Inverse,
        workspace
      )
    )
    assertArrayClose(restoredReal, inputReal, 8e-16)
    assertArrayClose(restoredImaginary, inputImaginary, 8e-16)

  test("invalid sizes arrays values and workspace ownership fail explicitly"):
    assertEquals(
      Radix2FftPlan.create(6),
      Left(SpectralError.InvalidFftSize(6))
    )
    val plan = spectral(Radix2FftPlan.create(4))
    val workspace = plan.newWorkspace()
    plan.transform(
      Array.fill(3)(0.0),
      Array.fill(4)(0.0),
      Array.fill(4)(0.0),
      Array.fill(4)(0.0),
      FftDirection.Forward,
      workspace
    ) match
      case Left(SpectralError.InvalidArrayLength("input real", 4, 3)) => ()
      case other => fail(s"expected array-length failure, got $other")

    val nonfinite = Array(0.0, 1.0, Double.NaN, 3.0)
    plan.transform(
      nonfinite,
      Array.fill(4)(0.0),
      Array.fill(4)(0.0),
      Array.fill(4)(0.0),
      FftDirection.Forward,
      workspace
    ) match
      case Left(SpectralError.NonFiniteInput("input real", 2, value)) =>
        assert(value.isNaN)
      case other => fail(s"expected nonfinite failure, got $other")

    val otherPlan = spectral(Radix2FftPlan.create(4))
    otherPlan.transform(
      Array.fill(4)(0.0),
      Array.fill(4)(0.0),
      Array.fill(4)(0.0),
      Array.fill(4)(0.0),
      FftDirection.Forward,
      workspace
    ) match
      case Left(SpectralError.WorkspacePlanMismatch) => ()
      case other => fail(s"expected workspace-owner failure, got $other")

    spectral(workspace.acquire(plan))
    plan.transform(
      Array.fill(4)(0.0),
      Array.fill(4)(0.0),
      Array.fill(4)(0.0),
      Array.fill(4)(0.0),
      FftDirection.Forward,
      workspace
    ) match
      case Left(SpectralError.WorkspaceInUse) => ()
      case other => fail(s"expected active-workspace failure, got $other")
    workspace.release(plan)

  private def directDft(
      inputReal: Array[Double],
      inputImaginary: Array[Double],
      inverse: Boolean
  ): (Array[Double], Array[Double]) =
    val size = inputReal.length
    val outputReal = new Array[Double](size)
    val outputImaginary = new Array[Double](size)
    val sign = if inverse then 1.0 else -1.0
    val scale = if inverse then 1.0 / size.toDouble else 1.0
    var frequency = 0
    while frequency < size do
      var sample = 0
      while sample < size do
        val phase = sign * 2.0 * math.Pi * frequency * sample / size.toDouble
        val cosine = math.cos(phase)
        val sine = math.sin(phase)
        outputReal(frequency) +=
          inputReal(sample) * cosine - inputImaginary(sample) * sine
        outputImaginary(frequency) +=
          inputReal(sample) * sine + inputImaginary(sample) * cosine
        sample += 1
      outputReal(frequency) *= scale
      outputImaginary(frequency) *= scale
      frequency += 1
    outputReal -> outputImaginary

  private def assertArrayClose(
      actual: Array[Double],
      expected: Array[Double],
      tolerance: Double
  ): Unit =
    assertEquals(actual.length, expected.length)
    var index = 0
    while index < actual.length do
      assertEqualsDouble(actual(index), expected(index), tolerance)
      index += 1

  private def spectral[A](value: Either[SpectralError, A]): A =
    value.fold(error => fail(error.message), identity)
