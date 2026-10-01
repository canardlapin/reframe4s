package reframe4s.benchmark.flashalign

class LinearAutomaticConfirmationCourtSuite extends munit.FunSuite:
  test("sealed automatic confirmation court contains 48 rows"):
    assertEquals(
      LinearAutomaticConfirmationCourt.sealedCaseCount,
      Right(48)
    )

  test("each sealed cohort and model contains six subjects"):
    val counts = LinearAutomaticConfirmationCourt.sealedCohortCounts
      .fold(error => fail(error.message), value => value)
    val expected =
      for
        cohort <- PairCohort.values.toVector
        model <- Vector("rigid", "affine")
      yield (cohort -> model) -> 6
    assertEquals(counts, expected.toMap)

  test("output evidence counts non-finite voxels without emitting non-finite metrics"):
    val summary = OutputEvidence.summarize(
      Iterator(1.0, Double.NaN, 2.0, Double.PositiveInfinity, -0.5),
      Iterator(1.0, Double.NaN, 0.5, 0.0, 1.0)
    )
    assertEquals(summary.voxels, 5L)
    assertEqualsDouble(summary.finiteValueSum, 2.5, 0.0)
    assertEquals(summary.nonfiniteVoxels, 2L)
    assertEqualsDouble(summary.meanValidity, 0.625, 0.0)
    assertEquals(summary.nonfiniteValidityWeights, 1L)
