package reframe4s.benchmark.flashalign

final class LinearAccuracyCourtSuite extends munit.FunSuite:
  test("sealed court admits all 32 balanced case definitions"):
    val count = LinearAccuracyCourt.sealedCaseCount.fold(error => fail(error.message), identity)
    assertEquals(count, 32)

  test("AFNI base-to-source RAI conversion returns source-to-base RAS"):
    val saved = Vector(
      1.0, 0.0, 0.0, 2.0,
      0.0, 1.0, 0.0, -3.0,
      0.0, 0.0, 1.0, -4.0
    )
    val actual = AfniMatrix
      .fromBaseToSourceRai(saved)
      .fold(error => fail(error.message), identity)
    val expected = Vector(
      1.0, 0.0, 0.0, 2.0,
      0.0, 1.0, 0.0, -3.0,
      0.0, 0.0, 1.0, 4.0,
      0.0, 0.0, 0.0, 1.0
    )
    actual.zip(expected).foreach { case (observed, reference) =>
      assertEqualsDouble(observed, reference, 1e-12)
    }

  test("AFNI matrix conversion rejects a malformed row"):
    AfniMatrix.fromBaseToSourceRai(Vector.fill(11)(0.0)) match
      case Left(EvidenceError.InvalidMatrixLength(_, 11)) => ()
      case other => fail(s"expected matrix-length failure, got $other")
