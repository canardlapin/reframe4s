package reframe4s.flashalign

final class ExceptionalLinearFailureEvidenceSuite extends munit.FunSuite:
  test("typed exceptional termination reaches public failure diagnostics intact"):
    val best = translation(1.5, -0.75, 0.25)
    val lastValid = translation(1.75, -0.5, 0.125)
    val work = FlashalignWorkCounts.Zero.copy(
      uniqueInterpolations = 173L,
      gradientEvaluations = 161L,
      sourceVoxelReads = 1288L,
      patchEntryEvaluations = 7L,
      patchOccurrenceEvaluations = 11L,
      dataLinearizations = 3,
      priorLinearizations = 2,
      linearSolverCalls = 2,
      trialEvaluations = 2,
      acceptedSteps = 1,
      selectionEvaluations = 2
    )
    val checkpoint = FlashalignLinearCheckpointDiagnostics(
      best,
      lastValid,
      selectionObjective = 0.42,
      acceptedSteps = 1,
      selectionObjectiveId = 917L,
      initialOptimizationObjective = 1.2,
      lastOptimizationObjective = 0.5
    )
    val levels = Vector(
      FlashalignLinearLevelDiagnostics(0, 6.0, 20, 8, 0.8, work),
      FlashalignLinearLevelDiagnostics(1, 3.0, 24, 10, 0.42, work)
    )
    val providerError = ProjectedPatchOptimizerError.EvaluationFailure(
      ProjectedPatchStage.TrialDataObjective,
      "deliberate provider error after partial sampling"
    )
    val engineError = LinearEngineError.FitRejected(
      ProjectedPatchTermination.ExceptionalFailure(providerError),
      LinearEngineFailureEvidence(work, levels, checkpoint, 24, 10)
    )
    val publicError: FlashalignError =
      FlashalignError.LinearEngine(engineError)
    val diagnostics = publicError.failureDiagnostics.getOrElse(
      fail("expected public failure diagnostics")
    )

    assert(publicError.message.contains("ExceptionalFailure"), publicError.message)
    assertEquals(diagnostics.work, work)
    assertEquals(diagnostics.levels, levels)
    assertEquals(diagnostics.lastCheckpoint, Some(checkpoint))
    assertNotEquals(
      diagnostics.lastCheckpoint.get.movingToFixedRowMajor,
      diagnostics.lastCheckpoint.get.lastValidMovingToFixedRowMajor
    )

  test("pyramid aggregation retains completed and failed-level work"):
    val checkpoint = FlashalignLinearCheckpointDiagnostics(
      translation(0.5, 0.0, 0.0),
      translation(0.75, 0.0, 0.0),
      selectionObjective = 0.3,
      acceptedSteps = 1,
      selectionObjectiveId = 19L,
      initialOptimizationObjective = 0.9,
      lastOptimizationObjective = 0.4
    )
    val completedWork = FlashalignWorkCounts.Zero.copy(
      uniqueInterpolations = 100L,
      dataLinearizations = 2
    )
    val failedWork = FlashalignWorkCounts.Zero.copy(
      uniqueInterpolations = 60L,
      dataLinearizations = 2,
      priorLinearizations = 1,
      trialEvaluations = 1,
      selectionEvaluations = 2
    )
    val completedLevel = FlashalignLinearLevelDiagnostics(
      0,
      6.0,
      18,
      7,
      0.6,
      completedWork
    )
    val providerError = ProjectedPatchOptimizerError.EvaluationFailure(
      ProjectedPatchStage.DataLinearization,
      "deliberate failed fine-level evaluation"
    )
    val original = LinearEngineError.FitRejected(
      ProjectedPatchTermination.ExceptionalFailure(providerError),
      LinearEngineFailureEvidence(
        failedWork,
        Vector.empty,
        checkpoint,
        optimizationPatchEntries = 22,
        selectionPatchEntries = 9
      )
    )
    val aggregated = LinearEngine3.withPyramidFailureEvidence(
      original,
      completedWork,
      Vector(completedLevel),
      Some(checkpoint),
      levelIndex = 1,
      effectiveResolutionMillimetres = 3.0
    )

    aggregated match
      case LinearEngineError.FitRejected(termination, evidence) =>
        assertEquals(
          termination,
          ProjectedPatchTermination.ExceptionalFailure(providerError)
        )
        assertEquals(evidence.work.uniqueInterpolations, 160L)
        assertEquals(evidence.work.dataLinearizations, 4)
        assertEquals(evidence.lastCheckpoint, checkpoint)
        assertEquals(evidence.levels.size, 2)
        assertEquals(evidence.levels.head, completedLevel)
        assertEquals(evidence.levels.last.levelIndex, 1)
        assertEquals(evidence.levels.last.work, failedWork)
        assertEquals(evidence.levels.last.finalSelectionObjective, 0.3)
      case other => fail(s"expected aggregated fit rejection, got $other")

  test("pre-checkpoint failure on a later level retains the previous level"):
    val checkpoint = FlashalignLinearCheckpointDiagnostics(
      translation(0.5, 0.0, 0.0),
      translation(0.75, 0.0, 0.0),
      selectionObjective = 0.3,
      acceptedSteps = 1,
      selectionObjectiveId = 19L,
      initialOptimizationObjective = 0.9,
      lastOptimizationObjective = 0.4
    )
    val completedWork = FlashalignWorkCounts.Zero.copy(
      uniqueInterpolations = 100L,
      dataLinearizations = 2
    )
    val completedLevel = FlashalignLinearLevelDiagnostics(
      0,
      6.0,
      18,
      7,
      0.3,
      completedWork
    )
    val cause = LinearEngineError.Failed(
      LinearEngineStage.Optimizer,
      "deliberate provider failure before the next checkpoint"
    )
    val aggregated = LinearEngine3.withPyramidFailureEvidence(
      cause,
      completedWork,
      Vector(completedLevel),
      Some(checkpoint),
      levelIndex = 1,
      effectiveResolutionMillimetres = 3.0
    )

    aggregated match
      case LinearEngineError.PyramidStageFailed(actualCause, evidence) =>
        assertEquals(actualCause, cause)
        assertEquals(evidence.work, completedWork)
        assertEquals(evidence.levels, Vector(completedLevel))
        assertEquals(evidence.lastCheckpoint, checkpoint)
      case other => fail(s"expected evidence-bearing pyramid failure, got $other")

  test("first-level pre-checkpoint failure exposes work without inventing a checkpoint"):
    val work = FlashalignWorkCounts.Zero.copy(
      uniqueInterpolations = 31L,
      gradientEvaluations = 19L,
      sourceVoxelReads = 152L,
      dataLinearizations = 1
    )
    val publicError: FlashalignError = FlashalignError.LinearEngine(
      LinearEngineError.UncheckpointedEvaluationFailure(
        "DataLinearization failed: deliberate provider error",
        work
      )
    )
    val diagnostics = publicError.failureDiagnostics.getOrElse(
      fail("expected uncheckpointed work diagnostics")
    )

    assertEquals(diagnostics.work, work)
    assertEquals(diagnostics.levels, Vector.empty)
    assertEquals(diagnostics.lastCheckpoint, None)
    assert(publicError.message.contains("before checkpoint"), publicError.message)

  private def translation(x: Double, y: Double, z: Double): Vector[Double] =
    Vector(
      1.0, 0.0, 0.0, x,
      0.0, 1.0, 0.0, y,
      0.0, 0.0, 1.0, z,
      0.0, 0.0, 0.0, 1.0
    )
