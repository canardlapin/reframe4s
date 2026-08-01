package reframe4s.benchmark.motion

final class MotionMetricScorerSuite extends munit.FunSuite:
  test("identity poses and truth-valued output produce exact zero metrics"):
    val result = right(MotionMetricScorer.score(fixture()))

    assertEquals(result.frames.size, 3)
    assertEquals(result.summary.physicalLandmarkDisplacementP95Mm, 0.0)
    assertEquals(result.summary.correctedImageNrmse, 0.0)
    assertEquals(result.summary.relativeRotationErrorP95Degrees, 0.0)
    assertEquals(result.summary.framewiseDisplacementErrorP95Mm, 0.0)
    assertEquals(result.summary.boundaryShellNrmse, 0.0)
    assertEquals(result.summary.temporalDifferenceNrmse, 0.0)
    assertEquals(result.summary.edgeEnergyLogError, 0.0)
    assertEquals(result.summary.ringingFraction, 0.0)

  test("physical pose scores use moving-to-fixed polarity exactly once"):
    val oneMillimeter = translation(1.0, 0.0, 0.0)
    val twoMillimeters = translation(2.0, 0.0, 0.0)
    val input =
      fixture().copy(
        estimatedPoses =
          Vector(Matrix4.identity, oneMillimeter, twoMillimeters)
      )
    val result = right(MotionMetricScorer.score(input))

    assertEqualsDouble(
      result.summary.physicalLandmarkDisplacementP95Mm,
      2.0,
      1e-12
    )
    assertEqualsDouble(
      result.summary.framewiseDisplacementErrorP95Mm,
      1.0,
      1e-12
    )
    assertEquals(result.summary.relativeRotationErrorP95Degrees, 0.0)

  test("three-voxel inner shell excludes a center-only image error"):
    val input = fixture()
    val corrected = input.corrected.clone()
    val center = linear(4, 4, 4, 1, 9, 9, 3)
    corrected(center) += 1.0
    val result =
      right(MotionMetricScorer.score(input.copy(corrected = corrected)))

    assert(result.summary.correctedImageNrmse > 0.0)
    assertEquals(result.summary.boundaryShellNrmse, 0.0)
    assert(result.summary.temporalDifferenceNrmse > 0.0)

  test("physical-gradient and ringing diagnostics expose image scaling"):
    val input = fixture()
    val corrected = input.truth.map(_ * 2.0)
    val result =
      right(MotionMetricScorer.score(input.copy(corrected = corrected)))

    assertEqualsDouble(
      result.summary.edgeEnergyLogError,
      math.log(4.0),
      1e-12
    )
    assert(result.summary.ringingFraction > 0.0)

  private def fixture(): MotionMetricInput =
    val nx = 9
    val ny = 9
    val nz = 9
    val nt = 3
    val truth =
      Array.tabulate(nx * ny * nz * nt) { index =>
        val frame = index % nt
        val spatial = index / nt
        val k = spatial % nz
        val j = (spatial / nz) % ny
        val i = spatial / (ny * nz)
        i.toDouble +
          2.0 * j.toDouble +
          3.0 * k.toDouble +
          frame.toDouble * (1.0 + i.toDouble * 0.1)
      }
    MotionMetricInput(
      shape = Vector(nx, ny, nz, nt),
      corrected = truth.clone(),
      truth = truth,
      mask = Array.fill(nx * ny * nz)(true),
      indexToWorld =
        matrix(
          Vector(
            1.7, -0.2, 0.1, -10.0,
            0.3, 2.1, -0.1, 5.0,
            0.0, 0.4, 2.8, -3.0,
            0.0, 0.0, 0.0, 1.0
          )
        ),
      estimatedPoses = Vector.fill(nt)(Matrix4.identity),
      truthPoses = Vector.fill(nt)(Matrix4.identity),
      landmarksRas =
        Vector(
          Vector(0.0, 0.0, 0.0),
          Vector(12.0, -4.0, 7.0),
          Vector(-3.0, 9.0, 2.0)
        ),
      referenceIndex = 0
    )

  private def translation(x: Double, y: Double, z: Double): Matrix4 =
    matrix(
      Vector(
        1.0, 0.0, 0.0, x,
        0.0, 1.0, 0.0, y,
        0.0, 0.0, 1.0, z,
        0.0, 0.0, 0.0, 1.0
      )
    )

  private def matrix(values: Vector[Double]): Matrix4 =
    right(Matrix4.create(values))

  private def linear(
      i: Int,
      j: Int,
      k: Int,
      frame: Int,
      ny: Int,
      nz: Int,
      nt: Int
  ): Int =
    ((i * ny + j) * nz + k) * nt + frame

  private def right[A](value: Either[RunnerError, A]): A =
    value.fold(error => fail(error.message), identity)
