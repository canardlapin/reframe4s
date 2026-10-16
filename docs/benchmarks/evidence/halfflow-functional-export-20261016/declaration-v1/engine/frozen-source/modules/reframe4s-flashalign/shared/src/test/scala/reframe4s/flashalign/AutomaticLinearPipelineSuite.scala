package reframe4s.flashalign

import image4s.BoundaryPolicy
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid
import ravel.DType.given
import ravel.NDArray
import reframe4s.resample.Interpolation

final class AutomaticLinearPipelineSuite extends munit.FunSuite:
  test("public rigid run captures and refines a displaced image pair"):
    val fixture = new PairFixture("automatic-rigid")
    val truth = affine(
      Affine.fromRowMajor[D3](
        Vector(
          1.0, 0.0, 0.0, 6.0,
          0.0, 1.0, 0.0, -6.0,
          0.0, 0.0, 1.0, 0.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )
    val moving = fixture.moving(truth)
    val plan = flashalign(Flashalign.rigid(moving, fixture.fixed, config))

    val result = flashalign(plan.run(plan.newWorkspace()))
    val actual = result.movingToFixed.operator.rowMajor
    assertEqualsDouble(actual(3), 6.0, 0.35, actual)
    assertEqualsDouble(actual(7), -6.0, 0.35, actual)
    assertEqualsDouble(actual(11), 0.0, 0.35, actual)
    assertCaptureDiagnostics(result.diagnostics, expectedExpandedCapture = true)
    val outputPlan = resampling(
      FlashalignOutput.rigidPlan(
        moving,
        fixture.fixedGrid,
        result,
        Interpolation.Linear,
        BoundaryPolicy.Constant(0.0)
      )
    )
    assertEquals(outputPlan.structure.materializedCoordinateCount, 0)
    val output = resampling(outputPlan.run(outputPlan.newWorkspace()))
    assertEquals(output.image.grid.shape, fixture.fixedGrid.shape)

  test("public affine run uses rigid capture before affine refinement"):
    val fixture = new PairFixture("automatic-affine")
    val truth = affine(
      Affine.fromRowMajor[D3](
        Vector(
          1.012, 0.006, -0.003, 6.0,
          0.004, 0.990, 0.005, -6.0,
          -0.002, 0.004, 1.008, 0.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )
    val plan = flashalign(
      Flashalign.affine(fixture.moving(truth), fixture.fixed, config)
    )

    val result = flashalign(plan.run(plan.newWorkspace()))
    val actual = result.movingToFixed.operator.rowMajor
    val landmarks = Vector(
      Vector(5.0, 5.0, 5.0),
      Vector(30.0, 6.0, 25.0),
      Vector(7.0, 29.0, 28.0),
      Vector(27.0, 28.0, 8.0),
      Vector(18.0, 18.0, 18.0)
    )
    val errors = landmarks.map { point =>
      val observed = applyAffine(actual, point)
      val expected = applyAffine(truth.rowMajor, point)
      math.sqrt(observed.zip(expected).map { case (left, right) =>
        val difference = left - right
        difference * difference
      }.sum)
    }
    assert(errors.max < 0.9, (errors, actual, truth.rowMajor))
    assertCaptureDiagnostics(result.diagnostics, expectedExpandedCapture = true)

  test("adequate coherent identity skips expanded structural capture"):
    val fixture = new PairFixture("automatic-identity")
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )
    val plan = flashalign(
      Flashalign.rigid(fixture.fixedAsMoving, fixture.fixed, config)
    )

    val result = flashalign(plan.run(plan.newWorkspace()))
    val capture = result.diagnostics.capture.getOrElse(
      fail("missing identity capture diagnostics")
    )
    assert(capture.identityAdequate, capture)
    assert(!capture.expandedCaptureExecuted, capture)
    assertEquals(capture.evaluatedRotations, 1)
    assertEquals(capture.candidates.size, 1)
    assertEquals(
      (capture.candidates.head.lagX, capture.candidates.head.lagY,
        capture.candidates.head.lagZ),
      (0, 0, 0)
    )
    val actual = result.movingToFixed.operator.rowMajor
    assertEqualsDouble(actual(3), 0.0, 0.05, actual)
    assertEqualsDouble(actual(7), 0.0, 0.05, actual)
    assertEqualsDouble(actual(11), 0.0, 0.05, actual)

  test("automatic run is unavailable under the supplied-transform policy"):
    val fixture = new PairFixture("automatic-policy")
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.SuppliedWorldTransform
    )
    val plan = flashalign(
      Flashalign.rigid(fixture.fixedAsMoving, fixture.fixed, config)
    )
    plan.run(plan.newWorkspace()) match
      case Left(FlashalignError.InitializationPolicyMismatch(required, actual)) =>
        assertEquals(
          required,
          FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
        )
        assertEquals(actual, FlashalignInitializationPolicy.SuppliedWorldTransform)
      case other => fail(s"expected initialization-policy mismatch, got $other")

  test("automatic capture rejection retains coarse diagnostics"):
    val fixture = new PairFixture("automatic-no-information")
    val config = FlashalignConfig.create(
      noInformationPolicy,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )
    val plan = flashalign(
      Flashalign.rigid(
        fixture.fixedAsMoving,
        fixture.fixed,
        config
      )
    )

    plan.run(plan.newWorkspace()) match
      case Left(error: FlashalignError.CaptureRejected) =>
        assertEquals(
          error.failure,
          FlashalignCaptureFailure.InsufficientOverlap
        )
        val evidence = error.failureDiagnostics.getOrElse(
          fail("missing capture failure diagnostics")
        )
        val capture = evidence.capture.getOrElse(
          fail("missing failed capture stages")
        )
        assertEqualsDouble(capture.identityOverlapFraction, 0.0, 0.0)
        assertEquals(capture.identityStructuralScore, None)
        assert(capture.expandedCaptureExecuted, capture)
        assert(capture.evaluatedRotations > 0, capture)
        assertEquals(evidence.work.dataLinearizations, 0)
        assertEquals(evidence.lastCheckpoint, None)
      case other => fail(s"expected capture rejection, got $other")

  test("exhausted capture candidates retain work and their last checkpoints"):
    val fixture = new PairFixture("automatic-exhausted")
    val truth = affine(
      Affine.fromRowMajor[D3](
        Vector(
          1.0, 0.0, 0.0, 6.0,
          0.0, 1.0, 0.0, -6.0,
          0.0, 0.0, 1.0, 0.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    val config = FlashalignConfig.create(
      failurePolicy,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )
    val plan = flashalign(
      Flashalign.rigid(fixture.moving(truth), fixture.fixed, config)
    )

    plan.run(plan.newWorkspace()) match
      case Left(error: FlashalignError.NoCaptureCandidateConverged) =>
        val evidence = error.failureDiagnostics.getOrElse(
          fail("missing exhausted-candidate diagnostics")
        )
        val capture = evidence.capture.getOrElse(
          fail("missing capture diagnostics")
        )
        assertEquals(error.failures.size, capture.candidates.size)
        assert(capture.candidates.nonEmpty, capture)
        assert(capture.candidates.forall(_.failureMessage.nonEmpty), capture)
        assert(
          capture.candidates.forall(_.work.dataLinearizations > 0),
          capture
        )
        assert(evidence.work.dataLinearizations >= capture.candidates.size)
        assert(evidence.work.selectionEvaluations >= capture.candidates.size)
        assertEquals(evidence.work.auditEvaluations, 0)
        assert(evidence.levels.nonEmpty, evidence)
        assert(evidence.lastCheckpoint.nonEmpty, evidence)
        assert(capture.captureElapsedNanoseconds > 0L, capture)
        assert(capture.refinementAndSelectionElapsedNanoseconds > 0L, capture)
        assertEquals(capture.finalAuditElapsedNanoseconds, 0L)
      case other => fail(s"expected exhausted candidates, got $other")

  private def assertCaptureDiagnostics(
      diagnostics: FlashalignDiagnostics,
      expectedExpandedCapture: Boolean
  ): Unit =
    val capture = diagnostics.capture.getOrElse(fail("missing capture diagnostics"))
    assertEquals(diagnostics.levels.map(_.levelIndex), Vector(0, 1, 2))
    assertEquals(
      diagnostics.levels.map(_.effectiveResolutionMillimetres),
      Vector(6.0, 3.0, 1.5)
    )
    assert(capture.evaluatedRotations > 0, capture)
    assert(capture.candidates.nonEmpty, capture)
    assert(
      capture.selectedCandidateIndex >= 0 &&
        capture.selectedCandidateIndex < capture.candidates.size,
      capture
    )
    assert(
      capture.candidates(capture.selectedCandidateIndex).finalObjective.nonEmpty,
      capture
    )
    assertEquals(diagnostics.work.auditEvaluations, 1)
    assert(diagnostics.work.selectionEvaluations >= capture.candidates.size)
    assertEquals(
      diagnostics.work.dataLinearizations,
      capture.candidates.map(_.work.dataLinearizations).sum
    )
    assertEquals(
      diagnostics.work.trialEvaluations,
      capture.candidates.map(_.work.trialEvaluations).sum
    )
    assert(capture.preparationElapsedNanoseconds >= 0L)
    assert(capture.captureElapsedNanoseconds >= 0L)
    assert(capture.refinementAndSelectionElapsedNanoseconds >= 0L)
    assert(capture.finalAuditElapsedNanoseconds >= 0L)
    assertEquals(capture.expandedCaptureExecuted, expectedExpandedCapture)
    assertEquals(capture.identityAdequate, !expectedExpandedCapture)

  private final class PairFixture(label: String):
    val movingFrame = geometry(Frame.named[D3](s"$label-moving"))
    val fixedFrame = geometry(Frame.named[D3](s"$label-fixed"))
    private val movingGrid = geometry(
      Grid.in(movingFrame)(Vector(37, 37, 37), Affine.identity[D3])
    )
    val fixedGrid = geometry(
      Grid.in(fixedFrame)(Vector(37, 37, 37), Affine.identity[D3])
    )
    val fixed: FlashalignImage[fixedFrame.type] = sampled(
      Sampled.continuous(
        fixedGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](37, 37, 37)((i, j, k) => signal(i, j, k))
      )
    )
    val fixedAsMoving: FlashalignImage[movingFrame.type] = sampled(
      Sampled.continuous(
        movingGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](37, 37, 37)((i, j, k) => signal(i, j, k))
      )
    )

    def moving(transform: Affine[D3]): FlashalignImage[movingFrame.type] =
      val matrix = transform.rowMajor
      sampled(
        Sampled.continuous(
          movingGrid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](37, 37, 37) { (i, j, k) =>
            val mapped = applyAffine(
              matrix,
              Vector(i.toDouble, j.toDouble, k.toDouble)
            )
            signal(mapped(0), mapped(1), mapped(2))
          }
        )
      )

    private def signal(x: Double, y: Double, z: Double): Double =
      def blob(
          centerX: Double,
          centerY: Double,
          centerZ: Double,
          scaleX: Double,
          scaleY: Double,
          scaleZ: Double
      ): Double =
        val dx = (x - centerX) / scaleX
        val dy = (y - centerY) / scaleY
        val dz = (z - centerZ) / scaleZ
        math.exp(-0.5 * (dx * dx + dy * dy + dz * dz))
      2.0 * blob(9.0, 12.0, 17.0, 3.0, 5.0, 4.0) -
        1.4 * blob(27.0, 9.0, 24.0, 5.0, 3.0, 4.0) +
        1.1 * blob(19.0, 29.0, 8.0, 4.0, 3.0, 5.0) +
        0.8 * blob(30.0, 27.0, 31.0, 3.0, 4.0, 2.5) +
        0.23 * math.sin(0.17 * x + 0.11 * y + 0.07 * z) +
        0.16 * math.cos(0.013 * x * y - 0.009 * y * z + 0.006 * x * z)

  private def geometry[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def sampled[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def affine[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def flashalign[A](result: Either[FlashalignError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def resampling[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def failurePolicy: LinearPresetPolicy =
    val base = LinearPresetPolicies.WithinModality
    val trust = base.rigidTrust.copy(
      maximumLinearizations = 1,
      maximumTrialAttempts = 1,
      objectiveTolerance = 0.0,
      gradientTolerance = 0.0,
      stepToleranceRmsMillimetres = 0.0,
      conditionLimit = 1.000001
    )
    LinearPresetPolicy
      .create(
        "flashalign-linear-automatic-failure-test-v1",
        base.version,
        base.preset,
        base.patch,
        base.sampling,
        base.preparation,
        base.capture,
        trust,
        base.affineTrust,
        base.qc,
        base.calibration
      )
      .fold(error => fail(error.message), identity)

  private def noInformationPolicy: LinearPresetPolicy =
    val base = LinearPresetPolicies.WithinModality
    LinearPresetPolicy
      .create(
        "flashalign-linear-automatic-no-information-test-v1",
        base.version,
        base.preset,
        base.patch,
        base.sampling,
        base.preparation,
        base.capture.copy(minimumStructuralEnergy = 1e100),
        base.rigidTrust,
        base.affineTrust,
        base.qc,
        base.calibration
      )
      .fold(error => fail(error.message), identity)

  private def applyAffine(
      matrix: Vector[Double],
      point: Vector[Double]
  ): Vector[Double] =
    Vector.tabulate(3)(row =>
      matrix(row * 4) * point(0) +
        matrix(row * 4 + 1) * point(1) +
        matrix(row * 4 + 2) * point(2) +
        matrix(row * 4 + 3)
    )
