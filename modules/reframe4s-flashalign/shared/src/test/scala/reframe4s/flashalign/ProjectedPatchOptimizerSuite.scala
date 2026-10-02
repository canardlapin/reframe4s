package reframe4s.flashalign

final class ProjectedPatchOptimizerSuite extends munit.FunSuite:
  for early <- Vector(true, false) do
    test(s"objective rejection contracts the physical trial step even at minimum damping (early=$early)"):
      // Independent nonlinear least squares: F(x,y)=((x*x-1)^2+y*y)/2.
      // At x=.1, its exact Gauss-Newton proposal overshoots; damping-only retries
      // stay clipped to the same 1.5mm step. A .75mm step gives x=.85 and descends.
      val data = new ProjectedPatchDataProblem[Vector[Double]]:
        val parameterCount = 2
        val optimizationObjectiveId = 1L
        val selectionObjectiveId = 2L
        private def value(s: Vector[Double]): Double =
          val residual = s(0) * s(0) - 1.0
          0.5 * (residual * residual + s(1) * s(1))
        def linearize(s: Vector[Double], out: ProjectedPatchQuadraticBuffer)
            : Either[ProjectedPatchOptimizerError, Unit] =
          out.setObjective(value(s))
          out.gradient(0) = 2.0 * s(0) * (s(0) * s(0) - 1.0)
          out.gradient(1) = s(1)
          out.setCurvature(0, 0, 4.0 * s(0) * s(0))
          out.setCurvature(0, 1, 0.0)
          out.setCurvature(1, 1, 1.0)
          Right(())
        def trialDataObjective(s: Vector[Double], limit: Double)
            : Either[ProjectedPatchOptimizerError, ProjectedPatchTrialData] =
          val objective = value(s)
          Right(if early && objective > limit then ProjectedPatchTrialData.RejectedEarly(objective)
            else ProjectedPatchTrialData.Complete(objective))
        def selectionObjective(s: Vector[Double])
            : Either[ProjectedPatchOptimizerError, ProjectedPatchSelection] =
          Right(ProjectedPatchSelection(selectionObjectiveId, value(s)))
      val prior = new QuadraticPrior(matrix(0.0, 0.0, 0.0, 0.0), Vector(0.0, 0.0))
      val geometry = new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0))
      val optimizer = compiled(data, prior, geometry, optimizerConfig(
        maximumLinearizations = 1, maximumTrialAttempts = 8,
        initialDamping = 1e-8, minimumDamping = 1e-8,
        trustRadiusRms = 1.5, maximumDisplacement = 2.0,
        objectiveTolerance = 0.0, gradientTolerance = 0.0, stepToleranceRms = 0.0
      ))
      val result = optimized(optimizer.optimize(Vector(0.1, 0.0), optimizer.newWorkspace()))
      assertEquals(result.counters.acceptedSteps, 1)
      assertEquals(result.counters.rejectedSteps, 1)
      assertEquals(result.counters.linearSolverCalls, 2)
      assertEquals(result.counters.trialEvaluations, 2)
      assertEqualsDouble(result.lastValidState.head, 0.85, 1e-12)
      val accepted = result.attempts.find(_.accepted).get
      assertEqualsDouble(accepted.rmsStep, 0.75, 1e-12)
      assert(accepted.actualReduction.get > 0.45)
      assert(accepted.gainRatio.get >= optimizer.config.acceptanceRatio)

  test("a small step caused by damping alone is not convergence"):
    // Exact F(x,y) = (x*x + y*y)/2, one unit from its minimum.
    // lambda=1e7 makes the proposed step tiny without making the state stationary.
    val data = new QuadraticData(matrix(1.0, 0.0, 0.0, 1.0), Vector(0.0, 0.0))
    val prior = new QuadraticPrior(matrix(0.0, 0.0, 0.0, 0.0), Vector(0.0, 0.0))
    val geometry = new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0))
    val optimizer = compiled(data, prior, geometry, optimizerConfig(
      initialDamping = 1e7, maximumDamping = 1e8,
      stepToleranceRms = 1e-6, gradientTolerance = 1e-9
    ))
    val result = optimized(optimizer.optimize(Vector(1.0, 0.0), optimizer.newWorkspace()))
    assertEquals(result.termination, ProjectedPatchTermination.DampingLimited)
    assert(!result.termination.converged)
    assertEquals(result.lastValidState, Vector(1.0, 0.0))
    assertEquals(result.counters.acceptedSteps, 0)
    assertEquals(result.counters.linearSolverCalls, 2)
    assertEquals(result.counters.dataLinearizations, 1)
    assertEquals(result.counters.trialEvaluations, 0)

  test("stationarity reached on the last allowed update is recognized"):
    val data = new QuadraticData(matrix(1.0, 0.0, 0.0, 1.0), Vector(0.0, 0.0))
    val prior = new QuadraticPrior(matrix(0.0, 0.0, 0.0, 0.0), Vector(0.0, 0.0))
    val geometry = new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0))
    val optimizer = compiled(data, prior, geometry, optimizerConfig(
      maximumLinearizations = 1, initialDamping = 0.01,
      gradientTolerance = 0.01, objectiveTolerance = 0.0,
      stepToleranceRms = 1e-12, trustRadiusRms = 2.0, maximumDisplacement = 2.0
    ))
    val result = optimized(optimizer.optimize(Vector(1.0, 0.0), optimizer.newWorkspace()))
    assertEqualsDouble(result.lastValidState.head, 1.0 / 101.0, 1e-14)
    assertEquals(result.termination, ProjectedPatchTermination.GradientConverged)
    assertEquals(result.counters.acceptedSteps, 1)
    assertEquals(result.counters.dataLinearizations, 2)

  test("quadratic data and full prior converge with correct gradient signs"):
    val data = new QuadraticData(
      matrix(2.0, 0.0, 0.0, 4.0),
      Vector(3.0, -2.0)
    )
    val prior = new QuadraticPrior(
      matrix(1.0, 0.0, 0.0, 3.0),
      Vector(0.5, 1.0)
    )
    val geometry = new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0))
    val config = optimizerConfig(
      maximumLinearizations = 12,
      maximumTrialAttempts = 4,
      initialDamping = 1e-6,
      minimumDamping = 1e-12,
      maximumDamping = 1e6,
      trustRadiusRms = 20.0,
      maximumDisplacement = 40.0,
      objectiveTolerance = 1e-14,
      gradientTolerance = 1e-9,
      stepToleranceRms = 1e-10
    )
    val optimizer = compiled(data, prior, geometry, config)
    val result = optimized(
      optimizer.optimize(Vector(0.0, 0.0), optimizer.newWorkspace())
    )

    assert(result.termination.converged)
    assertVectorClose(
      result.state,
      Vector(6.5 / 3.0, -5.0 / 7.0),
      2e-7
    )
    assertEquals(result.state, result.bestCheckpoint.state)
    assertEquals(result.bestCheckpoint.selectionObjectiveId, data.selectionId)
    assertEquals(
      result.counters.dataLinearizations,
      result.counters.priorLinearizations
    )
    assertEquals(data.linearizationCalls, result.counters.dataLinearizations)
    assertEquals(data.trialCalls, result.counters.trialEvaluations)
    assertEquals(
      data.linearizationCalls,
      result.counters.acceptedSteps + 1
    )
    assert(result.attempts.exists(_.accepted))

  test("trust clipping recomputes undamped prediction from the actual step"):
    val dataH = matrix(3.0, 0.4, 0.4, 2.0)
    val priorH = matrix(0.7, -0.1, -0.1, 1.1)
    val data = new QuadraticData(dataH, Vector(2.0, -1.5))
    val prior = new QuadraticPrior(priorH, Vector(-0.5, 0.25))
    val metric = matrix(2.0, 0.5, 0.5, 1.5)
    val geometry = new VectorGeometry(metric)
    val damping = 0.3
    val config = optimizerConfig(
      maximumLinearizations = 1,
      maximumTrialAttempts = 2,
      initialDamping = damping,
      minimumDamping = 1e-6,
      maximumDamping = 1e6,
      trustRadiusRms = 0.25,
      maximumDisplacement = 10.0,
      objectiveTolerance = 0.0,
      gradientTolerance = 0.0,
      stepToleranceRms = 0.0
    )
    val optimizer = compiled(data, prior, geometry, config)
    val initial = Vector(0.0, 0.0)
    val result = optimized(
      optimizer.optimize(initial, optimizer.newWorkspace())
    )
    val attempt = result.attempts.find(_.accepted).getOrElse(
      fail("expected one accepted clipped attempt")
    )
    val step = result.lastValidState.zip(initial).map(_ - _)
    val combinedH = add(dataH, priorH)
    val b = negate(add(
      gradient(dataH, initial, data.target),
      gradient(priorH, initial, prior.target)
    ))
    val expectedPrediction =
      dot(b, step) - 0.5 * quadratic(combinedH, step)
    val dampedPrediction =
      expectedPrediction - 0.5 * damping * quadratic(metric, step)

    assertEquals(result.termination, ProjectedPatchTermination.LinearizationLimit)
    assert(attempt.clipped)
    assertEqualsDouble(attempt.rmsStep, config.trustRadiusRms, 2e-15)
    assertEqualsDouble(attempt.predictedReduction, expectedPrediction, 2e-14)
    assert(math.abs(attempt.predictedReduction - dampedPrediction) > 1e-4)
    assertEqualsDouble(attempt.gainRatio.getOrElse(Double.NaN), 1.0, 2e-13)
    assertEquals(result.counters.clippedSteps, 1)

  test("rejected trials reuse one linearization and preserve the last checkpoint"):
    val data = new QuadraticData(
      matrix(2.0, 0.0, 0.0, 1.0),
      Vector(1.0, -2.0),
      rejectEveryTrial = true
    )
    val prior = new QuadraticPrior(
      matrix(0.2, 0.0, 0.0, 0.3),
      Vector(0.0, 0.0)
    )
    val geometry = new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0))
    val config = optimizerConfig(
      maximumLinearizations = 5,
      maximumTrialAttempts = 3,
      initialDamping = 0.1,
      minimumDamping = 1e-6,
      maximumDamping = 100.0,
      trustRadiusRms = 10.0,
      maximumDisplacement = 20.0
    )
    val optimizer = compiled(data, prior, geometry, config)
    val initial = Vector(4.0, -5.0)
    val result = optimized(
      optimizer.optimize(initial, optimizer.newWorkspace())
    )

    assertEquals(result.termination, ProjectedPatchTermination.TrialAttemptLimit)
    assert(!result.termination.converged)
    assertEquals(result.state, initial)
    assertEquals(result.lastValidState, initial)
    assertEquals(result.bestCheckpoint.state, initial)
    assertEquals(data.linearizationCalls, 1)
    assertEquals(data.trialCalls, 3)
    assertEquals(result.counters.dataLinearizations, 1)
    assertEquals(result.counters.priorLinearizations, 1)
    assertEquals(result.counters.earlyRejectedTrials, 3)
    assertEquals(result.counters.acceptedSteps, 0)
    assertEquals(result.counters.selectionEvaluations, 1)

  test("rank deficiency severe damping and nonfinite solves are distinct"):
    val geometry = new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0))
    val zeroPrior = new QuadraticPrior(
      matrix(0.0, 0.0, 0.0, 0.0),
      Vector(0.0, 0.0)
    )

    val rankData = new QuadraticData(
      matrix(1.0, 0.0, 0.0, 0.0),
      Vector(1.0, 0.0)
    )
    val rankConfig = optimizerConfig(minimumDataRank = 2)
    val rankOptimizer = compiled(rankData, zeroPrior, geometry, rankConfig)
    val rankResult = optimized(
      rankOptimizer.optimize(Vector(0.0, 0.0), rankOptimizer.newWorkspace())
    )
    assertEquals(
      rankResult.termination,
      ProjectedPatchTermination.RankDeficient(1, 2)
    )
    assertEquals(rankResult.counters.linearSolverCalls, 0)

    val dampedData = new QuadraticData(
      matrix(1.0, 0.0, 0.0, 1.0),
      Vector(1.0, 1.0)
    )
    val dampedConfig = optimizerConfig(
      initialDamping = 1e20,
      minimumDamping = 1.0,
      maximumDamping = 1e20,
      stepToleranceRms = 1e-12,
      gradientTolerance = 0.0
    )
    val dampedOptimizer = compiled(
      dampedData,
      zeroPrior,
      geometry,
      dampedConfig
    )
    val dampedResult = optimized(
      dampedOptimizer.optimize(
        Vector(0.0, 0.0),
        dampedOptimizer.newWorkspace()
      )
    )
    assertEquals(
      dampedResult.termination,
      ProjectedPatchTermination.DampingLimited
    )
    assertEquals(dampedResult.counters.trialEvaluations, 0)

    val overflowData = new SyntheticLinearizationData(
      gradient = Vector(-1.0, 1.0),
      curvature = matrix(1e308, 0.0, 0.0, 1e308)
    )
    val overflowConfig = optimizerConfig(
      initialDamping = 1e308,
      minimumDamping = 1.0,
      maximumDamping = 1e308,
      minimumDataRank = 2,
      conditionLimit = 1e300
    )
    val overflowOptimizer = compiled(
      overflowData,
      zeroPrior,
      geometry,
      overflowConfig
    )
    val overflowResult = optimized(
      overflowOptimizer.optimize(
        Vector(0.0, 0.0),
        overflowOptimizer.newWorkspace()
      )
    )
    overflowResult.termination match
      case ProjectedPatchTermination.NonFiniteSolve(
            "damped curvature",
            _,
            value
          ) => assert(value.isInfinite)
      case other => fail(s"expected nonfinite solve, got $other")

  test("checkpoint failure after accepted work retains an evidence-bearing result"):
    val data = new QuadraticData(
      matrix(2.0, 0.0, 0.0, 2.0),
      Vector(1.0, -1.0),
      changeSelectionIdentityAfterFirst = true
    )
    val prior = new QuadraticPrior(
      matrix(0.1, 0.0, 0.0, 0.1),
      Vector(0.0, 0.0)
    )
    val geometry = new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0))
    val config = optimizerConfig(
      maximumLinearizations = 3,
      checkpointInterval = 1,
      trustRadiusRms = 10.0,
      maximumDisplacement = 20.0,
      objectiveTolerance = 0.0,
      gradientTolerance = 0.0,
      stepToleranceRms = 0.0
    )
    val optimizer = compiled(data, prior, geometry, config)
    val result = optimized(
      optimizer.optimize(Vector(0.0, 0.0), optimizer.newWorkspace())
    )
    result.termination match
      case ProjectedPatchTermination.ExceptionalFailure(
            ProjectedPatchOptimizerError.SelectionObjectiveMismatch(
              expected,
              actual
            )
          ) =>
        assertEquals(expected, data.selectionId)
        assertNotEquals(actual, expected)
      case other => fail(s"expected evidence-bearing identity failure, got $other")
    assertEquals(result.bestCheckpoint.state, Vector(0.0, 0.0))
    assertNotEquals(result.lastValidState, result.bestCheckpoint.state)
    assertEquals(result.counters.dataLinearizations, 1)
    assertEquals(result.counters.trialEvaluations, 1)
    assertEquals(result.counters.acceptedSteps, 1)
    assertEquals(result.counters.selectionEvaluations, 2)

  test("trial evaluation failure after initial checkpoint retains exact work"):
    val data = new QuadraticData(
      matrix(2.0, 0.0, 0.0, 2.0),
      Vector(1.0, -1.0),
      trialFailureAfter = 0
    )
    val prior = new QuadraticPrior(
      matrix(0.1, 0.0, 0.0, 0.1),
      Vector(0.0, 0.0)
    )
    val optimizer = compiled(
      data,
      prior,
      new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0)),
      optimizerConfig(
        objectiveTolerance = 0.0,
        gradientTolerance = 0.0,
        stepToleranceRms = 0.0
      )
    )
    val initial = Vector(0.0, 0.0)
    val result = optimized(optimizer.optimize(initial, optimizer.newWorkspace()))

    assertEquals(
      result.termination,
      ProjectedPatchTermination.ExceptionalFailure(
        ProjectedPatchOptimizerError.EvaluationFailure(
          ProjectedPatchStage.TrialDataObjective,
          "deliberate trial failure"
        )
      )
    )
    assertEquals(result.state, initial)
    assertEquals(result.lastValidState, initial)
    assertEquals(result.bestCheckpoint.state, initial)
    assertEquals(result.counters.dataLinearizations, 1)
    assertEquals(result.counters.priorLinearizations, 1)
    assertEquals(result.counters.linearSolverCalls, 1)
    assertEquals(result.counters.trialEvaluations, 1)
    assertEquals(result.counters.selectionEvaluations, 1)

  test("failed relinearization retains the accepted state and counts the attempt"):
    val data = new QuadraticData(
      matrix(2.0, 0.0, 0.0, 2.0),
      Vector(1.0, -1.0),
      linearizationFailureAfter = 1
    )
    val prior = new QuadraticPrior(
      matrix(0.1, 0.0, 0.0, 0.1),
      Vector(0.0, 0.0)
    )
    val optimizer = compiled(
      data,
      prior,
      new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0)),
      optimizerConfig(
        objectiveTolerance = 0.0,
        gradientTolerance = 0.0,
        stepToleranceRms = 0.0,
        checkpointInterval = 1
      )
    )
    val initial = Vector(0.0, 0.0)
    val result = optimized(optimizer.optimize(initial, optimizer.newWorkspace()))

    assertEquals(
      result.termination,
      ProjectedPatchTermination.ExceptionalFailure(
        ProjectedPatchOptimizerError.EvaluationFailure(
          ProjectedPatchStage.DataLinearization,
          "deliberate linearization failure"
        )
      )
    )
    assertNotEquals(result.lastValidState, initial)
    assertEquals(result.state, result.bestCheckpoint.state)
    assertEquals(result.lastValidState, result.bestCheckpoint.state)
    assertEquals(result.counters.dataLinearizations, 2)
    assertEquals(result.counters.priorLinearizations, 1)
    assertEquals(result.counters.trialEvaluations, 1)
    assertEquals(result.counters.acceptedSteps, 1)
    assertEquals(result.counters.selectionEvaluations, 2)

  test("thrown provider failure is typed after the initial checkpoint"):
    val data = new QuadraticData(
      matrix(2.0, 0.0, 0.0, 2.0),
      Vector(1.0, -1.0),
      throwTrialAfter = 0
    )
    val optimizer = compiled(
      data,
      new QuadraticPrior(
        matrix(0.1, 0.0, 0.0, 0.1),
        Vector(0.0, 0.0)
      ),
      new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0)),
      optimizerConfig(
        objectiveTolerance = 0.0,
        gradientTolerance = 0.0,
        stepToleranceRms = 0.0
      )
    )
    val result = optimized(
      optimizer.optimize(Vector(0.0, 0.0), optimizer.newWorkspace())
    )

    assertEquals(
      result.termination,
      ProjectedPatchTermination.ExceptionalFailure(
        ProjectedPatchOptimizerError.EvaluationFailure(
          ProjectedPatchStage.TrialDataObjective,
          "deliberate thrown provider failure"
        )
      )
    )
    assertEquals(result.counters.dataLinearizations, 1)
    assertEquals(result.counters.trialEvaluations, 1)
    assertEquals(result.counters.selectionEvaluations, 1)
    assertEquals(result.bestCheckpoint.state, Vector(0.0, 0.0))
    assertEquals(result.lastValidState, Vector(0.0, 0.0))

  test("initial evaluation failure records attempted work without a checkpoint"):
    val data = new QuadraticData(
      matrix(2.0, 0.0, 0.0, 2.0),
      Vector(1.0, -1.0),
      linearizationFailureAfter = 0
    )
    val optimizer = compiled(
      data,
      new QuadraticPrior(
        matrix(0.1, 0.0, 0.0, 0.1),
        Vector(0.0, 0.0)
      ),
      new VectorGeometry(matrix(1.0, 0.0, 0.0, 1.0)),
      optimizerConfig()
    )
    val workspace = optimizer.newWorkspace()
    val outcome = optimizer.optimize(Vector(0.0, 0.0), workspace)

    assertEquals(
      outcome,
      Left(
        ProjectedPatchOptimizerError.EvaluationFailure(
          ProjectedPatchStage.DataLinearization,
          "deliberate linearization failure"
        )
      )
    )
    assertEquals(data.linearizationCalls, 1)
    assertEquals(workspace.failureCountersSnapshot.dataLinearizations, 1)
    assertEquals(workspace.failureCountersSnapshot.priorLinearizations, 0)
    assertEquals(workspace.failureCountersSnapshot.selectionEvaluations, 0)

  private final class QuadraticData(
      hessian: Vector[Double],
      val target: Vector[Double],
      rejectEveryTrial: Boolean = false,
      changeSelectionIdentityAfterFirst: Boolean = false,
      trialFailureAfter: Int = Int.MaxValue,
      linearizationFailureAfter: Int = Int.MaxValue,
      throwTrialAfter: Int = Int.MaxValue
  ) extends ProjectedPatchDataProblem[Vector[Double]]:
    val parameterCount: Int = target.length
    val optimizationObjectiveId: Long = 0x1102L
    val selectionId: Long = 0x2203L
    val selectionObjectiveId: Long = selectionId
    var linearizationCalls = 0
    var trialCalls = 0
    private var selectionCalls = 0

    def linearize(
        state: Vector[Double],
        output: ProjectedPatchQuadraticBuffer
    ): Either[ProjectedPatchOptimizerError, Unit] =
      linearizationCalls += 1
      if linearizationCalls > linearizationFailureAfter then
        Left(
          ProjectedPatchOptimizerError.EvaluationFailure(
            ProjectedPatchStage.DataLinearization,
            "deliberate linearization failure"
          )
        )
      else
        output.setObjective(valueAt(state))
        val values = gradient(hessian, state, target)
        var row = 0
        while row < parameterCount do
          output.gradient(row) = values(row)
          var column = row
          while column < parameterCount do
            output.setCurvature(
              row,
              column,
              hessian(row * parameterCount + column)
            )
            column += 1
          row += 1
        Right(())

    def trialDataObjective(
        state: Vector[Double],
        acceptanceLimit: Double
    ): Either[ProjectedPatchOptimizerError, ProjectedPatchTrialData] =
      trialCalls += 1
      if trialCalls > throwTrialAfter then
        throw new IllegalStateException("deliberate thrown provider failure")
      else if trialCalls > trialFailureAfter then
        Left(
          ProjectedPatchOptimizerError.EvaluationFailure(
            ProjectedPatchStage.TrialDataObjective,
            "deliberate trial failure"
          )
        )
      else if rejectEveryTrial then
        val value = valueAt(state)
        Right(
          ProjectedPatchTrialData.RejectedEarly(
            math.max(value, acceptanceLimit + 1.0)
          )
        )
      else Right(ProjectedPatchTrialData.Complete(valueAt(state)))

    def selectionObjective(
        state: Vector[Double]
    ): Either[ProjectedPatchOptimizerError, ProjectedPatchSelection] =
      selectionCalls += 1
      val id =
        if changeSelectionIdentityAfterFirst && selectionCalls > 1 then
          selectionId + 1L
        else selectionId
      Right(ProjectedPatchSelection(id, valueAt(state)))

    private def valueAt(state: Vector[Double]): Double =
      val displacement = state.zip(target).map(_ - _)
      0.5 * quadratic(hessian, displacement)

  private final class SyntheticLinearizationData(
      gradient: Vector[Double],
      curvature: Vector[Double]
  ) extends ProjectedPatchDataProblem[Vector[Double]]:
    val parameterCount: Int = gradient.length
    val optimizationObjectiveId: Long = 0x3304L
    val selectionObjectiveId: Long = 0x4405L

    def linearize(
        state: Vector[Double],
        output: ProjectedPatchQuadraticBuffer
    ): Either[ProjectedPatchOptimizerError, Unit] =
      output.setObjective(0.0)
      var row = 0
      while row < parameterCount do
        output.gradient(row) = gradient(row)
        var column = row
        while column < parameterCount do
          output.setCurvature(
            row,
            column,
            curvature(row * parameterCount + column)
          )
          column += 1
        row += 1
      Right(())

    def trialDataObjective(
        state: Vector[Double],
        acceptanceLimit: Double
    ): Either[ProjectedPatchOptimizerError, ProjectedPatchTrialData] =
      Right(ProjectedPatchTrialData.Complete(0.0))

    def selectionObjective(
        state: Vector[Double]
    ): Either[ProjectedPatchOptimizerError, ProjectedPatchSelection] =
      Right(ProjectedPatchSelection(selectionObjectiveId, 0.0))

  private final class QuadraticPrior(
      hessian: Vector[Double],
      val target: Vector[Double]
  ) extends ProjectedPatchPrior[Vector[Double]]:
    def linearize(
        state: Vector[Double],
        output: ProjectedPatchQuadraticBuffer
    ): Either[ProjectedPatchOptimizerError, Unit] =
      output.setObjective(valueAt(state))
      val values = gradient(hessian, state, target)
      var row = 0
      while row < target.length do
        output.gradient(row) = values(row)
        var column = row
        while column < target.length do
          output.setCurvature(
            row,
            column,
            hessian(row * target.length + column)
          )
          column += 1
        row += 1
      Right(())

    def value(
        state: Vector[Double]
    ): Either[ProjectedPatchOptimizerError, Double] =
      Right(valueAt(state))

    private def valueAt(state: Vector[Double]): Double =
      val displacement = state.zip(target).map(_ - _)
      0.5 * quadratic(hessian, displacement)

  private final class VectorGeometry(metric: Vector[Double])
      extends ProjectedPatchGeometry[Vector[Double]]:
    def writePhysicalMetric(
        state: Vector[Double],
        outputUpper: Array[Double]
    ): Either[ProjectedPatchOptimizerError, Unit] =
      var row = 0
      while row < state.length do
        var column = row
        while column < state.length do
          outputUpper(PackedSymmetric.index(row, column)) =
            metric(row * state.length + column)
          column += 1
        row += 1
      Right(())

    def propose(
        state: Vector[Double],
        step: Array[Double]
    ): Either[
      ProjectedPatchOptimizerError,
      ProjectedPatchProposalResult[Vector[Double]]
    ] =
      val realized = step.clone()
      Right(
        ProjectedPatchProposalResult.Valid(
          ProjectedPatchProposal(
            state.zip(realized).map(_ + _),
            realized
          )
        )
      )

    def maximumDisplacement(
        before: Vector[Double],
        after: Vector[Double]
    ): Either[ProjectedPatchOptimizerError, Double] =
      Right(
        before.zip(after).map { case (left, right) =>
          math.abs(right - left)
        }.max
      )

  private def optimizerConfig(
      maximumLinearizations: Int = 5,
      maximumTrialAttempts: Int = 4,
      initialDamping: Double = 1e-2,
      minimumDamping: Double = 1e-8,
      maximumDamping: Double = 1e8,
      trustRadiusRms: Double = 2.0,
      maximumDisplacement: Double = 4.0,
      objectiveTolerance: Double = 1e-10,
      gradientTolerance: Double = 1e-8,
      stepToleranceRms: Double = 1e-8,
      minimumDataRank: Int = 2,
      conditionLimit: Double = 1e12,
      checkpointInterval: Int = 1
  ): ProjectedPatchOptimizerConfig =
    config(
      ProjectedPatchOptimizerConfig.create(
        parameterCount = 2,
        maximumLinearizations,
        maximumTrialAttempts,
        initialDamping,
        minimumDamping,
        maximumDamping,
        rejectedDampingFactor = 4.0,
        acceptedDampingFactor = 0.5,
        trustRadiusRms,
        maximumDisplacement,
        acceptanceRatio = 0.1,
        highGainRatio = 0.75,
        objectiveTolerance,
        gradientTolerance,
        stepToleranceRms,
        rankRelativeTolerance = 1e-12,
        minimumDataRank,
        conditionLimit,
        checkpointInterval,
        realizedStepTolerance = 1e-12
      )
    )

  private def matrix(
      a00: Double,
      a01: Double,
      a10: Double,
      a11: Double
  ): Vector[Double] =
    Vector(a00, a01, a10, a11)

  private def add(
      left: Vector[Double],
      right: Vector[Double]
  ): Vector[Double] =
    left.zip(right).map(_ + _)

  private def negate(values: Vector[Double]): Vector[Double] =
    values.map(-_)

  private def gradient(
      hessian: Vector[Double],
      state: Vector[Double],
      target: Vector[Double]
  ): Vector[Double] =
    val displacement = state.zip(target).map(_ - _)
    Vector(
      hessian(0) * displacement(0) + hessian(1) * displacement(1),
      hessian(2) * displacement(0) + hessian(3) * displacement(1)
    )

  private def quadratic(
      matrix: Vector[Double],
      value: Vector[Double]
  ): Double =
    value(0) * (matrix(0) * value(0) + matrix(1) * value(1)) +
      value(1) * (matrix(2) * value(0) + matrix(3) * value(1))

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.zip(right).map(_ * _).sum

  private def assertVectorClose(
      actual: Vector[Double],
      expected: Vector[Double],
      tolerance: Double
  ): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) =>
      assertEqualsDouble(left, right, tolerance)
    }

  private def config(
      value: Either[ProjectedPatchOptimizerError, ProjectedPatchOptimizerConfig]
  ): ProjectedPatchOptimizerConfig =
    value.fold(error => fail(error.message), identity)

  private def compiled(
      data: ProjectedPatchDataProblem[Vector[Double]],
      prior: ProjectedPatchPrior[Vector[Double]],
      geometry: ProjectedPatchGeometry[Vector[Double]],
      config: ProjectedPatchOptimizerConfig
  ): ProjectedPatchOptimizer[Vector[Double]] =
    ProjectedPatchOptimizer
      .compile(data, prior, geometry, config)
      .fold(error => fail(error.message), identity)

  private def optimized(
      value: Either[
        ProjectedPatchOptimizerError,
        ProjectedPatchOptimizationResult[Vector[Double]]
      ]
  ): ProjectedPatchOptimizationResult[Vector[Double]] =
    value.fold(error => fail(error.message), identity)
