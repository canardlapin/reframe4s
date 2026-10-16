package reframe4s.motion

import image4s.Axis
import image4s.AxisKind
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import ravel.Shape
import reframe4s.core.MapError
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError

final class RigidEstimatorSuite extends munit.FunSuite:
  test("deterministic optimizer recovers a physical translation"):
    val fixture = TranslationFixture()
    val estimator =
      motion(
        CompiledRigidPairEstimator.compile(
          fixture.movingImage,
          fixture.fixedImage,
          optimizerControl
        )
      )
    val initial =
      RigidPose.fromMovingToFixed(
        rigid(
          Rigid3.translation(
            fixture.movingFrame,
            fixture.fixedFrame
          )(0.0, 0.0, 0.0)
        )
      )
    val first =
      motion(estimator.runFrom(initial, estimator.newWorkspace()))
    val second =
      motion(estimator.runFrom(initial, estimator.newWorkspace()))

    assertEquals(
      estimator.control.strategy,
      RigidOptimizationStrategy.LevenbergMarquardt
    )
    assertEquals(
      first.diagnostics.strategy,
      RigidOptimizationStrategy.LevenbergMarquardt
    )
    assertEquals(
      first.report.rejectedSteps,
      first.diagnostics.rejectedSteps
    )
    first.diagnostics.acceptedObjectives.sliding(2).foreach {
      case Vector(previous, next) =>
        assert(
          next < previous,
          s"accepted objective did not decrease: $previous -> $next"
        )
      case _ => ()
    }
    assertEqualsDouble(
      first.diagnostics.acceptedObjectives.lastOption
        .getOrElse(fail("missing initial objective")),
      first.report.finalObjective,
      0.0
    )
    assertEqualsDouble(
      first.pose.movingToFixed.operator.rowMajor(3),
      1.0,
      0.06
    )
    assert(
      first.report.finalObjective <=
        first.report.initialObjective
    )
    assert(first.report.acceptedSteps > 0)
    assertEquals(
      first.pose.movingToFixed.operator.rowMajor,
      second.pose.movingToFixed.operator.rowMajor
    )
    assertEqualsDouble(
      first.report.finalObjective,
      second.report.finalObjective,
      0.0
    )

  test("coordinate search is retained only through its named reference policy"):
    val fixture = TranslationFixture()
    val referenceControl =
      optimizerControl.withStrategy(
        RigidOptimizationStrategy.ReferenceCoordinateSearch
      )
    val estimator =
      motion(
        CompiledRigidPairEstimator.compile(
          fixture.movingImage,
          fixture.fixedImage,
          referenceControl
        )
      )
    val result = motion(estimator.run(estimator.newWorkspace()))

    assertEquals(
      result.diagnostics.strategy,
      RigidOptimizationStrategy.ReferenceCoordinateSearch
    )
    assertEqualsDouble(
      result.pose.movingToFixed.operator.rowMajor(3),
      1.0,
      0.06
    )

  test("centroid initialization uses complete oblique physical affines"):
    val fixture = ObliqueImpulseFixture()
    val estimator =
      motion(
        CompiledRigidPairEstimator.compile(
          fixture.movingImage,
          fixture.fixedImage,
          optimizerControl
        )
      )
    val pose = motion(estimator.initialPose)
    val movingPoint =
      geometry(fixture.movingGrid.pointAt(fixture.movingIndex))
    val fixedPoint =
      geometry(fixture.fixedGrid.pointAt(fixture.fixedIndex))
    val mappedPoint =
      mapped(pose.movingToFixed(movingPoint))

    assertVectorClose(
      mappedPoint.coordinates,
      fixedPoint.coordinates,
      1e-10
    )

  test(
    "physical translation recovery is invariant to oblique, permuted, and reflected grids"
  ):
    val angle = math.toRadians(27.0)
    val oblique =
      Vector(
        math.cos(angle), -math.sin(angle), 0.0,
        math.sin(angle), math.cos(angle), 0.0,
        0.0, 0.0, 1.0
      )
    val permuted =
      Vector(
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
        1.0, 0.0, 0.0
      )
    val reflected =
      Vector(
        -1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0
      )
    val translation = Vector(2.25, -1.5, 0.75)
    val origin = Vector(31.0, -17.0, 8.0)
    val spacing = Vector(1.3, 2.1, 3.7)
    val shape = Vector(9, 7, 5)
    val data =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) =>
          val first =
            if
              i >= 2 && i <= 4 &&
              j >= 1 && j <= 3 &&
              k >= 1 && k <= 2
            then 4.0
            else 0.0
          val second =
            if i == 7 && j >= 4 && k == 3 then 9.0 else 0.0
          first + second
      }

    Vector(
      "oblique" -> oblique,
      "permuted" -> permuted,
      "reflected" -> reflected
    ).foreach { case (name, direction) =>
      val movingFrame =
        geometry(Frame.named[D3](s"$name-moving"))
      val fixedFrame =
        geometry(Frame.named[D3](s"$name-fixed"))
      val movingAffine =
        geometry(
          Affine.fromOriginSpacingDirection[D3](
            origin,
            spacing,
            direction
          )
        )
      val fixedAffine =
        geometry(
          Affine.fromOriginSpacingDirection[D3](
            origin.zip(translation).map { case (value, shift) =>
              value + shift
            },
            spacing,
            direction
          )
        )
      val movingGrid =
        geometry(Grid.in[D3](movingFrame)(shape, movingAffine))
      val fixedGrid =
        geometry(Grid.in[D3](fixedFrame)(shape, fixedAffine))
      val moving =
        image(
          Sampled.continuous(movingGrid, NonSpatialAxes.empty, data)
        )
      val fixed =
        image(
          Sampled.continuous(fixedGrid, NonSpatialAxes.empty, data)
        )
      val estimator =
        motion(
          CompiledRigidPairEstimator.compile(
            moving,
            fixed,
            optimizerControl
          )
        )
      val result = motion(estimator.run(estimator.newWorkspace()))
      val matrix = result.pose.movingToFixed.operator.rowMajor

      assertEqualsDouble(matrix(3), translation(0), 0.05, name)
      assertEqualsDouble(matrix(7), translation(1), 0.05, name)
      assertEqualsDouble(matrix(11), translation(2), 0.05, name)
      Vector(0, 5, 10).foreach(index =>
        assertEqualsDouble(matrix(index), 1.0, 1e-10, name)
      )
      Vector(1, 2, 4, 6, 8, 9).foreach(index =>
        assertEqualsDouble(matrix(index), 0.0, 1e-10, name)
      )
      assertEqualsDouble(result.report.finalObjective, 0.0, 1e-20, name)
    }

  test("noise-free analytic field recovers a six-degree-of-freedom pose"):
    val movingFrame = geometry(Frame.named[D3]("six-dof-moving"))
    val fixedFrame = geometry(Frame.named[D3]("six-dof-fixed"))
    val extent = 49
    val spacing = 0.5
    val origin = Vector(-12.0, -12.0, -12.0)
    val embedding =
      geometry(
        Affine.fromOriginSpacingDirection[D3](
          origin,
          Vector(spacing, spacing, spacing),
          Vector(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
          )
        )
      )
    val movingGrid =
      geometry(
        Grid.in[D3](movingFrame)(
          Vector(extent, extent, extent),
          embedding
        )
      )
    val fixedGrid =
      geometry(
        Grid.in[D3](fixedFrame)(
          Vector(extent, extent, extent),
          embedding
        )
      )
    val trueRotation =
      multiply3(
        rotationZ(math.toRadians(4.0)),
        multiply3(
          rotationY(math.toRadians(-3.0)),
          rotationX(math.toRadians(2.0))
        )
      )
    val translation = Vector(1.0, -0.5, 0.25)
    def field(x: Double, y: Double, z: Double): Double =
      8.0 * gaussianField(x, y, z, -3.0, 1.0, 2.0, 2.0) +
        5.0 * gaussianField(x, y, z, 4.0, -2.0, -1.0, 2.6) +
        3.0 * gaussianField(x, y, z, 1.0, 4.0, -3.0, 1.8)
    val fixedData =
      NDArray.tabulate[Double](extent, extent, extent)((i, j, k) =>
        field(
          i * spacing + origin(0),
          j * spacing + origin(1),
          k * spacing + origin(2)
        )
      )
    val movingData =
      NDArray.tabulate[Double](extent, extent, extent)((i, j, k) =>
        val x = i * spacing + origin(0)
        val y = j * spacing + origin(1)
        val z = k * spacing + origin(2)
        field(
          trueRotation(0) * x +
            trueRotation(1) * y +
            trueRotation(2) * z +
            translation(0),
          trueRotation(3) * x +
            trueRotation(4) * y +
            trueRotation(5) * z +
            translation(1),
          trueRotation(6) * x +
            trueRotation(7) * y +
            trueRotation(8) * z +
            translation(2)
        )
      )
    val moving =
      image(
        Sampled.continuous(movingGrid, NonSpatialAxes.empty, movingData)
      )
    val fixed =
      image(
        Sampled.continuous(fixedGrid, NonSpatialAxes.empty, fixedData)
      )
    val control =
      motion(
        RigidOptimizerControl.create(
          maximumIterations = 80,
          huberThreshold = 1.5,
          initialTranslationStepMm = 1.0,
          initialRotationStepRadians = math.toRadians(2.0),
          minimumTranslationStepMm = 0.005,
          minimumRotationStepRadians = math.toRadians(0.005),
          objectiveTolerance = 1e-12,
          minimumOverlap = 0.5
        )
      )
    val estimator =
      motion(
        CompiledRigidPairEstimator.compile(moving, fixed, control)
      )
    val result = motion(estimator.run(estimator.newWorkspace()))
    val replayed = motion(estimator.run(estimator.newWorkspace()))
    val withoutCaptureEstimator =
      motion(
        CompiledRigidPairEstimator.compile(
          moving,
          fixed,
          control.withCapture(RigidCapturePolicy.Disabled)
        )
      )
    val withoutCapture =
      motion(
        withoutCaptureEstimator.run(
          withoutCaptureEstimator.newWorkspace()
        )
      )
    val identityPose =
      RigidPose.fromMovingToFixed(
        rigid(
          Rigid3.translation(movingFrame, fixedFrame)(0.0, 0.0, 0.0)
        )
      )
    val truthTransform
        : Rigid3[movingFrame.type, fixedFrame.type] =
      rigid(
        Rigid3.fromRowMajor[movingFrame.type, fixedFrame.type](
          movingFrame,
          fixedFrame,
          Vector(
            trueRotation(0), trueRotation(1), trueRotation(2),
            translation(0),
            trueRotation(3), trueRotation(4), trueRotation(5),
            translation(1),
            trueRotation(6), trueRotation(7), trueRotation(8),
            translation(2),
            0.0, 0.0, 0.0, 1.0
          )
        )
      )
    val truthPose = RigidPose.fromMovingToFixed(truthTransform)
    val identityMeasurement =
      motion(estimator.measureAt(identityPose, estimator.newWorkspace()))
    val truthMeasurement =
      motion(estimator.measureAt(truthPose, estimator.newWorkspace()))
    val truthGradient =
      truthMeasurement.diagnostics.finalGradientNorm match
        case Some(value) => value
        case None        => fail("LM truth measurement omitted its gradient")
    val actual = result.pose.movingToFixed.operator.rowMajor
    val translationError =
      math.sqrt(
        math.pow(actual(3) - translation(0), 2) +
          math.pow(actual(7) - translation(1), 2) +
          math.pow(actual(11) - translation(2), 2)
      )
    val rotationCosine =
      (
        actual(0) * trueRotation(0) +
          actual(1) * trueRotation(1) +
          actual(2) * trueRotation(2) +
          actual(4) * trueRotation(3) +
          actual(5) * trueRotation(4) +
          actual(6) * trueRotation(5) +
          actual(8) * trueRotation(6) +
          actual(9) * trueRotation(7) +
          actual(10) * trueRotation(8) -
          1.0
      ) * 0.5
    val rotationError =
      math.acos(math.max(-1.0, math.min(1.0, rotationCosine)))
    val withoutCaptureMatrix =
      withoutCapture.pose.movingToFixed.operator.rowMajor
    val withoutCaptureTranslationError =
      math.sqrt(
        math.pow(withoutCaptureMatrix(3) - translation(0), 2) +
          math.pow(withoutCaptureMatrix(7) - translation(1), 2) +
          math.pow(withoutCaptureMatrix(11) - translation(2), 2)
      )
    val withoutCaptureRotationCosine =
      (
        withoutCaptureMatrix(0) * trueRotation(0) +
          withoutCaptureMatrix(1) * trueRotation(1) +
          withoutCaptureMatrix(2) * trueRotation(2) +
          withoutCaptureMatrix(4) * trueRotation(3) +
          withoutCaptureMatrix(5) * trueRotation(4) +
          withoutCaptureMatrix(6) * trueRotation(5) +
          withoutCaptureMatrix(8) * trueRotation(6) +
          withoutCaptureMatrix(9) * trueRotation(7) +
          withoutCaptureMatrix(10) * trueRotation(8) -
          1.0
      ) * 0.5
    val withoutCaptureRotationError =
      math.acos(
        math.max(-1.0, math.min(1.0, withoutCaptureRotationCosine))
      )

    assert(
      translationError <= 0.05,
      s"translation error $translationError exceeded 0.05 mm"
    )
    assert(
      rotationError <= math.toRadians(0.05),
      s"rotation error ${math.toDegrees(rotationError)} exceeded 0.05 deg; " +
        s"translation error was $translationError mm and recovered matrix " +
        actual.mkString("[", ", ", "]")
    )
    assert(
      result.report.finalObjective < result.report.initialObjective
    )
    assert(
      truthMeasurement.report.finalObjective <
        identityMeasurement.report.finalObjective
    )
    assert(
      result.report.finalObjective <
        identityMeasurement.report.finalObjective
    )
    val captureSuccess =
      translationError <= 0.05 &&
        rotationError <= math.toRadians(0.05)
    val baselineSuccess =
      withoutCaptureTranslationError <= 0.05 &&
        withoutCaptureRotationError <= math.toRadians(0.05)
    assert(
      (if captureSuccess then 1 else 0) >=
        (if baselineSuccess then 1 else 0),
      "deterministic capture reduced the registered recovery success rate"
    )
    assertEquals(result.diagnostics.captureCandidates, 7)
    assertEquals(withoutCapture.diagnostics.captureCandidates, 1)
    assertEquals(
      result.diagnostics.selectedCaptureIndex,
      replayed.diagnostics.selectedCaptureIndex
    )
    assertEquals(
      result.pose.movingToFixed.operator.rowMajor,
      replayed.pose.movingToFixed.operator.rowMajor
    )
    println(
      f"MIG-423 six-DOF diagnostic: " +
        f"E_identity=${identityMeasurement.report.finalObjective}%.12g, " +
        f"E_truth=${truthMeasurement.report.finalObjective}%.12g, " +
        f"E_recovered=${result.report.finalObjective}%.12g, " +
        f"overlap_identity=${identityMeasurement.overlap}%.12g, " +
        f"overlap_truth=${truthMeasurement.overlap}%.12g, " +
        f"overlap_recovered=${result.overlap}%.12g, " +
        f"gradient_norm_truth=$truthGradient%.12g, " +
        f"capture_candidates=${result.diagnostics.captureCandidates}%d, " +
        f"selected_capture=${result.diagnostics.selectedCaptureIndex}%d, " +
        f"translation_error_mm=$translationError%.12g, " +
        f"rotation_error_deg=${math.toDegrees(rotationError)}%.12g, " +
        f"baseline_translation_error_mm=$withoutCaptureTranslationError%.12g, " +
        f"baseline_rotation_error_deg=${math.toDegrees(withoutCaptureRotationError)}%.12g"
    )

  test("compile and workspace failures remain typed"):
    val fixture = TranslationFixture()
    val estimator =
      motion(
        CompiledRigidPairEstimator.compile(
          fixture.movingImage,
          fixture.fixedImage,
          optimizerControl
        )
      )
    val workspace = estimator.newWorkspace()
    assert(workspace.acquire())
    assertEquals(
      estimator.run(workspace),
      Left(MotionError.EstimatorWorkspaceInUse)
    )
    workspace.release()

    val nonFiniteData =
      NDArray.tabulate[Double](3, 3, 3)((i, j, k) =>
        if i == 1 && j == 1 && k == 1 then Double.NaN else 0.0
      )
    val nonFiniteGrid =
      geometry(
        Grid.in[D3](fixture.movingFrame)(
          Vector(3, 3, 3),
          Affine.identity[D3]
        )
      )
    val nonFinite =
      image(
        Sampled.continuous(
          nonFiniteGrid,
          NonSpatialAxes.empty,
          nonFiniteData
        )
      )
    CompiledRigidPairEstimator.compile(
      nonFinite,
      fixture.fixedImage,
      optimizerControl
    ) match
      case Left(
            MotionError.NonFiniteEstimatorVoxel(
              EstimatorInput.Moving,
              1,
              1,
              1,
              value
            )
          ) =>
        assert(value.isNaN)
      case other =>
        fail(s"expected non-finite moving voxel, got $other")

    val time = image(Axis.create("time", 1, AxisKind.Time))
    val axes = image(NonSpatialAxes.from(Vector(time)))
    val fourDimensional =
      NDArray.zeros[Double, ravel.Rank[4]](Shape(3, 3, 3, 1))
    val withTime =
      image(Sampled.continuous(nonFiniteGrid, axes, fourDimensional))
    assertEquals(
      CompiledRigidPairEstimator.compile(
        withTime,
        fixture.fixedImage,
        optimizerControl
      ),
      Left(
        MotionError.EstimatorHasNonSpatialAxes(
          EstimatorInput.Moving,
          1
        )
      )
    )

  test("optimizer controls reject invalid values and step order"):
    assertEquals(
      RigidOptimizerControl.create(
        maximumIterations = 0,
        huberThreshold = 1.0,
        initialTranslationStepMm = 1.0,
        initialRotationStepRadians = 0.1,
        minimumTranslationStepMm = 0.1,
        minimumRotationStepRadians = 0.01,
        objectiveTolerance = 0.0,
        minimumOverlap = 0.5
      ),
      Left(
        MotionError.InvalidEstimatorCount(
          EstimatorCount.MaximumIterations,
          0
        )
      )
    )
    assertEquals(
      RigidOptimizerControl.create(
        maximumIterations = 10,
        huberThreshold = 1.0,
        initialTranslationStepMm = 1.0,
        initialRotationStepRadians = 0.1,
        minimumTranslationStepMm = 2.0,
        minimumRotationStepRadians = 0.01,
        objectiveTolerance = 0.0,
        minimumOverlap = 0.5
      ),
      Left(
        MotionError.InvalidEstimatorStepOrder(
          EstimatorStep.Translation,
          2.0,
          1.0
        )
      )
    )
    RigidOptimizerControl.create(
      maximumIterations = 10,
      huberThreshold = 1.0,
      initialTranslationStepMm = 1.0,
      initialRotationStepRadians = 0.1,
      minimumTranslationStepMm = 0.1,
      minimumRotationStepRadians = 0.01,
      objectiveTolerance = 0.0,
      minimumOverlap = Double.NaN
    ) match
      case Left(
            MotionError.InvalidEstimatorScalar(
              EstimatorScalar.MinimumOverlap,
              value
            )
          ) =>
        assert(value.isNaN)
      case other =>
        fail(s"expected invalid minimum overlap, got $other")

    assertEquals(
      RigidDampingPolicy.create(
        initial = 1.0,
        minimum = 2.0,
        maximum = 3.0,
        acceptedFactor = 0.5,
        rejectedFactor = 2.0,
        conditionLimit = 1e6,
        maximumRejectedSteps = 4
      ),
      Left(MotionError.InvalidDampingOrder(2.0, 1.0, 3.0))
    )
    assertEquals(
      RigidDampingPolicy.create(
        initial = 1.0,
        minimum = 0.1,
        maximum = 3.0,
        acceptedFactor = 1.0,
        rejectedFactor = 2.0,
        conditionLimit = 1e6,
        maximumRejectedSteps = 4
      ),
      Left(
        MotionError.InvalidEstimatorScalar(
          EstimatorScalar.AcceptedDampingFactor,
          1.0
        )
      )
    )
    assertEquals(
      RigidCapturePolicy.axialRotation(-0.1),
      Left(
        MotionError.InvalidEstimatorScalar(
          EstimatorScalar.CaptureRotation,
          -0.1
        )
      )
    )
    assertEquals(
      optimizerControl.withGradientTolerance(-1.0),
      Left(
        MotionError.InvalidEstimatorScalar(
          EstimatorScalar.GradientTolerance,
          -1.0
        )
      )
    )
    val explicitGradientTolerance =
      motion(optimizerControl.withGradientTolerance(1e-6))
    assertEqualsDouble(
      explicitGradientTolerance.gradientTolerance,
      1e-6,
      0.0
    )

  test("typed policies drive stencil compilation and robust evaluation"):
    val fixture = TranslationFixture()
    val stencil =
      kernel(RigidStencilControl.create(8, 2, 2, 2, gamma = 0.5))
    val sampling = RigidSamplingPolicy.informationAware(stencil)
    val convergence =
      motion(
        RigidConvergencePolicy.create(
          maximumIterations = 12,
          initialTranslationStepMm = 1.0,
          initialRotationStepRadians = math.toRadians(1.0),
          minimumTranslationStepMm = 0.01,
          minimumRotationStepRadians = math.toRadians(0.01),
          objectiveTolerance = 1e-10,
          gradientTolerance = 1e-7,
          minimumOverlap = 0.25
        )
      )
    val smallHuber = kernel(RigidRobustLoss.huber(0.01))
    val huberControl =
      optimizerControl
        .withConvergence(convergence)
        .withSampling(sampling)
        .withRobustLoss(smallHuber)
        .withExecution(RigidExecutionPolicy.DeterministicSerial)
    val squaredControl =
      huberControl.withRobustLoss(RigidRobustLoss.squared)
    val huberEstimator =
      motion(
        CompiledRigidPairEstimator.compile(
          fixture.movingImage,
          fixture.fixedImage,
          huberControl
        )
      )
    val squaredEstimator =
      motion(
        CompiledRigidPairEstimator.compile(
          fixture.movingImage,
          fixture.fixedImage,
          squaredControl
        )
      )
    val identity =
      RigidPose.fromMovingToFixed(
        rigid(
          Rigid3.translation(
            fixture.movingFrame,
            fixture.fixedFrame
          )(0.0, 0.0, 0.0)
        )
      )
    val huber =
      motion(
        huberEstimator.measureAt(
          identity,
          huberEstimator.newWorkspace()
        )
      )
    val squared =
      motion(
        squaredEstimator.measureAt(
          identity,
          squaredEstimator.newWorkspace()
        )
      )

    assertEquals(huberEstimator.selectedSampleCount, 8)
    assertEquals(huberEstimator.declaredSpatialBins, 8)
    assertEquals(huberEstimator.coveredSpatialBins, 8)
    assertEquals(huberControl.maximumIterations, 12)
    assertEqualsDouble(huberControl.gradientTolerance, 1e-7, 0.0)
    assertEquals(
      huberControl.execution,
      RigidExecutionPolicy.DeterministicSerial
    )
    assert(
      squared.report.finalObjective > huber.report.finalObjective,
      s"squared=${squared.report.finalObjective}, huber=${huber.report.finalObjective}"
    )

  test(
    "fixed-domain objective cannot improve by discarding difficult samples"
  ):
    val movingFrame = geometry(Frame.named[D3]("overlap-moving"))
    val fixedFrame = geometry(Frame.named[D3]("overlap-fixed"))
    val shape = Vector(9, 7, 5)
    val movingGrid =
      geometry(Grid.in[D3](movingFrame)(shape, Affine.identity[D3]))
    val fixedGrid =
      geometry(Grid.in[D3](fixedFrame)(shape, Affine.identity[D3]))
    val moving =
      image(
        Sampled.continuous(
          movingGrid,
          NonSpatialAxes.empty,
          NDArray.zeros[Double](9, 7, 5)
        )
      )
    val fixed =
      image(
        Sampled.continuous(
          fixedGrid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](9, 7, 5)((_, _, _) => 1.0)
        )
      )
    val control =
      motion(
        RigidOptimizerControl.create(
          maximumIterations = 4,
          huberThreshold = 1.5,
          initialTranslationStepMm = 1.0,
          initialRotationStepRadians = math.toRadians(1.0),
          minimumTranslationStepMm = 0.25,
          minimumRotationStepRadians = math.toRadians(0.25),
          objectiveTolerance = 0.0,
          minimumOverlap = 0.2
        )
      )
    val estimator =
      motion(
        CompiledRigidPairEstimator.compile(moving, fixed, control)
      )
    val identity =
      RigidPose.fromMovingToFixed(
        rigid(
          Rigid3.translation(movingFrame, fixedFrame)(0.0, 0.0, 0.0)
        )
      )
    val partlyOutside =
      RigidPose.fromMovingToFixed(
        rigid(
          Rigid3.translation(movingFrame, fixedFrame)(2.0, 0.0, 0.0)
        )
      )
    val absent =
      RigidPose.fromMovingToFixed(
        rigid(
          Rigid3.translation(movingFrame, fixedFrame)(100.0, 0.0, 0.0)
        )
      )
    val full =
      motion(estimator.measureAt(identity, estimator.newWorkspace()))
    val partial =
      motion(estimator.measureAt(partlyOutside, estimator.newWorkspace()))

    assert(partial.overlap < full.overlap)
    assert(partial.supportedVoxels < full.supportedVoxels)
    assertEqualsDouble(
      partial.report.finalObjective,
      full.report.finalObjective,
      0.0
    )
    assertEquals(
      estimator.measureAt(absent, estimator.newWorkspace()),
      Left(MotionError.InsufficientEstimatorOverlap(0.0, 0.2))
    )

  private val optimizerControl: RigidOptimizerControl =
    motion(
      RigidOptimizerControl.create(
        maximumIterations = 24,
        huberThreshold = 1.5,
        initialTranslationStepMm = 2.0,
        initialRotationStepRadians = math.toRadians(1.0),
        minimumTranslationStepMm = 0.025,
        minimumRotationStepRadians = math.toRadians(0.025),
        objectiveTolerance = 1e-10,
        minimumOverlap = 0.25
      )
    )

  private final class TranslationFixture:
    val movingFrame = geometry(Frame.named[D3]("moving"))
    val fixedFrame = geometry(Frame.named[D3]("fixed"))
    val shape = Vector(9, 7, 5)
    val movingGrid =
      geometry(
        Grid.in[D3](movingFrame)(shape, Affine.identity[D3])
      )
    val fixedGrid =
      geometry(
        Grid.in[D3](fixedFrame)(shape, Affine.identity[D3])
      )
    val fixedData =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) => field(i.toDouble, j.toDouble, k.toDouble)
      }
    val movingData =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) => field(i.toDouble + 1.0, j.toDouble, k.toDouble)
      }
    val movingImage =
      image(
        Sampled.continuous(
          movingGrid,
          NonSpatialAxes.empty,
          movingData
        )
      )
    val fixedImage =
      image(
        Sampled.continuous(
          fixedGrid,
          NonSpatialAxes.empty,
          fixedData
        )
      )

    private def field(x: Double, y: Double, z: Double): Double =
      9.0 * gaussian(x, y, z, 3.5, 2.0, 1.5, 1.2) +
        4.0 * gaussian(x, y, z, 6.2, 4.5, 3.0, 0.8)

    private def gaussian(
        x: Double,
        y: Double,
        z: Double,
        cx: Double,
        cy: Double,
        cz: Double,
        width: Double
    ): Double =
      val dx = x - cx
      val dy = y - cy
      val dz = z - cz
      math.exp(-(dx * dx + dy * dy + dz * dz) / (2.0 * width * width))

  private final class ObliqueImpulseFixture:
    val movingFrame = geometry(Frame.named[D3]("moving-oblique"))
    val fixedFrame = geometry(Frame.named[D3]("fixed-oblique"))
    val movingShape = Vector(9, 7, 5)
    val fixedShape = Vector(6, 8, 4)
    val movingIndex = geometry(LatticeIndex.of[D3](4, 3, 2))
    val fixedIndex = geometry(LatticeIndex.of[D3](2, 4, 1))
    private val angle = math.toRadians(23.0)
    private val direction =
      Vector(
        math.cos(angle),
        -math.sin(angle),
        0.0,
        math.sin(angle),
        math.cos(angle),
        0.0,
        0.0,
        0.0,
        1.0
      )
    private val movingAffine =
      geometry(
        Affine.fromOriginSpacingDirection[D3](
          Vector(31.0, -17.0, 8.0),
          Vector(1.3, 2.1, 3.7),
          direction
        )
      )
    private val fixedAffine =
      geometry(
        Affine.fromOriginSpacingDirection[D3](
          Vector(-4.0, 9.0, 2.0),
          Vector(2.4, 1.1, 2.8),
          direction
        )
      )
    val movingGrid =
      geometry(
        Grid.in[D3](movingFrame)(movingShape, movingAffine)
      )
    val fixedGrid =
      geometry(
        Grid.in[D3](fixedFrame)(fixedShape, fixedAffine)
      )
    private val movingData =
      NDArray.tabulate[Double](
        movingShape(0),
        movingShape(1),
        movingShape(2)
      )((i, j, k) =>
        if Vector(i, j, k) == movingIndex.values then 7.0 else 0.0
      )
    private val fixedData =
      NDArray.tabulate[Double](
        fixedShape(0),
        fixedShape(1),
        fixedShape(2)
      )((i, j, k) =>
        if Vector(i, j, k) == fixedIndex.values then 7.0 else 0.0
      )
    val movingImage =
      image(
        Sampled.continuous(
          movingGrid,
          NonSpatialAxes.empty,
          movingData
        )
      )
    val fixedImage =
      image(
        Sampled.continuous(
          fixedGrid,
          NonSpatialAxes.empty,
          fixedData
        )
      )

  private def assertVectorClose(
      actual: Vector[Double],
      expected: Vector[Double],
      tolerance: Double
  ): Unit =
    assertEquals(actual.size, expected.size)
    actual.zip(expected).foreach { case (left, right) =>
      assertEqualsDouble(left, right, tolerance)
    }

  private def gaussianField(
      x: Double,
      y: Double,
      z: Double,
      cx: Double,
      cy: Double,
      cz: Double,
      width: Double
  ): Double =
    val dx = x - cx
    val dy = y - cy
    val dz = z - cz
    math.exp(-(dx * dx + dy * dy + dz * dz) / (2.0 * width * width))

  private def rotationX(angle: Double): Vector[Double] =
    val cosine = math.cos(angle)
    val sine = math.sin(angle)
    Vector(
      1.0, 0.0, 0.0,
      0.0, cosine, -sine,
      0.0, sine, cosine
    )

  private def rotationY(angle: Double): Vector[Double] =
    val cosine = math.cos(angle)
    val sine = math.sin(angle)
    Vector(
      cosine, 0.0, sine,
      0.0, 1.0, 0.0,
      -sine, 0.0, cosine
    )

  private def rotationZ(angle: Double): Vector[Double] =
    val cosine = math.cos(angle)
    val sine = math.sin(angle)
    Vector(
      cosine, -sine, 0.0,
      sine, cosine, 0.0,
      0.0, 0.0, 1.0
    )

  private def multiply3(
      left: Vector[Double],
      right: Vector[Double]
  ): Vector[Double] =
    Vector.tabulate(9) { flat =>
      val row = flat / 3
      val column = flat % 3
      var total = 0.0
      var inner = 0
      while inner < 3 do
        total += left(row * 3 + inner) * right(inner * 3 + column)
        inner += 1
      total
    }

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def image[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def motion[A](value: Either[MotionError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def kernel[A](value: Either[RigidKernelError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def mapped[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)
