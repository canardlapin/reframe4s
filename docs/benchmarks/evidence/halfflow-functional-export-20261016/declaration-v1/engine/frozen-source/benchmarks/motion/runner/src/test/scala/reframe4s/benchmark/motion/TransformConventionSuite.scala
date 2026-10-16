package reframe4s.benchmark.motion

final class TransformConventionSuite extends munit.FunSuite:
  test("AFNI base-to-input LPS decoding has canonical polarity"):
    val raw =
      matrix(
        Vector(
          1.0, 0.0, 0.0, 2.0,
          0.0, 1.0, 0.0, -3.0,
          0.0, 0.0, 1.0, -4.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    val canonical =
      right(
        TransformConvention
          .afniBaseToInputLpsToMovingToFixedRas(raw)
      )

    assertLandmark(
      canonical,
      Vector(7.0, 11.0, 13.0),
      Vector(9.0, 8.0, 17.0)
    )
    assertEqualsDouble(canonical(0, 3), 2.0, 0.0)
    assertEqualsDouble(canonical(1, 3), -3.0, 0.0)
    assertEqualsDouble(canonical(2, 3), 4.0, 0.0)

  test("nifreeze pull matrices are inverted exactly once"):
    val fixedToMoving =
      matrix(
        Vector(
          1.0, 0.0, 0.0, -1.0,
          0.0, 1.0, 0.0, -2.0,
          0.0, 0.0, 1.0, 3.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    val movingToFixed =
      right(
        TransformConvention
          .nifreezePullToMovingToFixed(fixedToMoving)
      )

    assertLandmark(
      movingToFixed,
      Vector(4.0, 5.0, 6.0),
      Vector(5.0, 7.0, 3.0)
    )

  test("FLIRT scaled-mm decoding honors handedness and complete affines"):
    val inputIndexToRas =
      matrix(
        Vector(
          0.0, -2.0, 0.0, 10.0,
          3.0, 0.0, 0.0, -20.0,
          0.0, 0.0, 4.0, 5.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    val referenceIndexToRas =
      matrix(
        Vector(
          -1.5, 0.2, 0.0, 30.0,
          0.0, 2.5, 0.0, -10.0,
          0.0, 0.0, 3.5, 7.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    val rawFlirt =
      matrix(
        Vector(
          -0.25243279790790696,
          0.97091589879885354,
          0.0,
          19.860572203643095,
          -0.98795411167698932,
          -0.17420296559051712,
          0.0,
          12.716210376844421,
          0.0,
          0.0,
          1.0,
          -0.50000000000000056,
          0.0,
          0.0,
          0.0,
          1.0
        )
      )
    val canonical =
      right(
        TransformConvention.flirtToMovingToFixedRas(
          rawFlirt,
          inputIndexToRas,
          Vector(9, 7, 5),
          referenceIndexToRas,
          Vector(11, 8, 6)
        )
      )

    assertLandmark(
      canonical,
      Vector(0.0, 0.0, 0.0),
      Vector(2.0, -3.0, 1.5)
    )
    assertLandmark(
      canonical,
      Vector(3.5, -2.25, 4.0),
      Vector(
        5.8375355352933216,
        -4.6080488224432123,
        5.5
      )
    )
    assertLandmark(
      canonical,
      Vector(10.0, 20.0, -5.0),
      Vector(
        8.375113976783473,
        18.432636836913463,
        -3.5
      )
    )
    assertEqualsDouble(canonical.determinant3, 1.0, 1e-12)

  private def assertLandmark(
      matrix: Matrix4,
      point: Vector[Double],
      expected: Vector[Double]
  ): Unit =
    val actual = right(matrix.transform(point))
    actual.zip(expected).foreach { case (found, wanted) =>
      assertEqualsDouble(found, wanted, 1e-10)
    }

  private def matrix(values: Vector[Double]): Matrix4 =
    right(Matrix4.create(values))

  private def right[A](value: Either[RunnerError, A]): A =
    value.fold(error => fail(error.message), identity)
