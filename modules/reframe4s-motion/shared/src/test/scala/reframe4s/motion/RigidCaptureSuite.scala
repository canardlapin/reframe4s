package reframe4s.motion

import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid

final class RigidCaptureSuite extends munit.FunSuite:
  test("deterministic axial seeds preserve the registered capture success rate"):
    val anglesDegrees = Vector(-12.0, -6.0, 0.0, 6.0, 12.0)
    var capturedSuccesses = 0
    var baselineSuccesses = 0
    var replayPose = Option.empty[Vector[Double]]
    var replaySeed = Option.empty[Int]
    var boundaryImproved = false
    val records = Vector.newBuilder[String]

    anglesDegrees.zipWithIndex.foreach { case (angleDegrees, scenario) =>
      val movingFrame =
        geometry(Frame.named[D3](s"capture-moving-$scenario"))
      val fixedFrame =
        geometry(Frame.named[D3](s"capture-fixed-$scenario"))
      val extent = 33
      val spacing = 0.75
      val origin = -12.0
      val embedding =
        geometry(
          Affine.fromOriginSpacingDirection[D3](
            Vector(origin, origin, origin),
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
      val angle = math.toRadians(angleDegrees)
      val cosine = math.cos(angle)
      val sine = math.sin(angle)
      val fixedData =
        NDArray.tabulate[Double](extent, extent, extent)((i, j, k) =>
          field(
            i.toDouble * spacing + origin,
            j.toDouble * spacing + origin,
            k.toDouble * spacing + origin
          )
        )
      val movingData =
        NDArray.tabulate[Double](extent, extent, extent)((i, j, k) =>
          val x = i.toDouble * spacing + origin
          val y = j.toDouble * spacing + origin
          val z = k.toDouble * spacing + origin
          field(
            cosine * x - sine * y,
            sine * x + cosine * y,
            z
          )
        )
      val moving =
        image(
          Sampled.continuous(
            movingGrid,
            NonSpatialAxes.empty,
            movingData
          )
        )
      val fixed =
        image(
          Sampled.continuous(
            fixedGrid,
            NonSpatialAxes.empty,
            fixedData
          )
        )
      val capturedEstimator =
        motion(
          CompiledRigidPairEstimator.compile(
            moving,
            fixed,
            optimizerControl
          )
        )
      val baselineEstimator =
        motion(
          CompiledRigidPairEstimator.compile(
            moving,
            fixed,
            optimizerControl.withCapture(RigidCapturePolicy.Disabled)
          )
        )
      val captured =
        motion(capturedEstimator.run(capturedEstimator.newWorkspace()))
      val baseline =
        motion(baselineEstimator.run(baselineEstimator.newWorkspace()))
      val capturedError =
        poseError(captured.pose.movingToFixed.operator.rowMajor, cosine, sine)
      val baselineError =
        poseError(baseline.pose.movingToFixed.operator.rowMajor, cosine, sine)

      if capturedError.success then capturedSuccesses += 1
      if baselineError.success then baselineSuccesses += 1
      if
        math.abs(angleDegrees) == 12.0 &&
        capturedError.rotationDegrees < baselineError.rotationDegrees
      then boundaryImproved = true
      records +=
        f"$angleDegrees%.1f:" +
          f"captured=${capturedError.translationMm}%.5fmm/" +
          f"${capturedError.rotationDegrees}%.5fdeg/" +
          s"seed=${captured.diagnostics.selectedCaptureIndex}," +
          f"baseline=${baselineError.translationMm}%.5fmm/" +
          f"${baselineError.rotationDegrees}%.5fdeg"

      assertEquals(captured.diagnostics.captureCandidates, 7)
      assertEquals(baseline.diagnostics.captureCandidates, 1)
      assert(
        captured.report.finalObjective <= captured.report.initialObjective
      )

      if scenario == anglesDegrees.size - 1 then
        val replay =
          motion(capturedEstimator.run(capturedEstimator.newWorkspace()))
        replayPose = Some(replay.pose.movingToFixed.operator.rowMajor)
        replaySeed = Some(replay.diagnostics.selectedCaptureIndex)
        assertEquals(
          replay.pose.movingToFixed.operator.rowMajor,
          captured.pose.movingToFixed.operator.rowMajor
        )
        assertEquals(
          replay.diagnostics.selectedCaptureIndex,
          captured.diagnostics.selectedCaptureIndex
        )
    }

    val scenarioRecords = records.result()
    assertEquals(
      capturedSuccesses,
      anglesDegrees.size,
      scenarioRecords.mkString("; ")
    )
    assert(
      capturedSuccesses >= baselineSuccesses,
      s"capture successes $capturedSuccesses fell below baseline " +
        s"$baselineSuccesses"
    )
    assert(
      boundaryImproved,
      "capture did not improve either registered 12-degree boundary case"
    )
    assert(replayPose.nonEmpty)
    assert(replaySeed.nonEmpty)
    println(
      s"MIG-423 capture court: scenarios=${anglesDegrees.size}, " +
        s"capturedSuccesses=$capturedSuccesses, " +
        s"baselineSuccesses=$baselineSuccesses, " +
        scenarioRecords.mkString("records=[", "; ", "]")
    )

  private final case class PoseError(
      translationMm: Double,
      rotationDegrees: Double
  ):
    val success: Boolean =
      translationMm <= 0.1 && rotationDegrees <= 0.2

  private def poseError(
      matrix: Vector[Double],
      truthCosine: Double,
      truthSine: Double
  ): PoseError =
    val translation =
      math.sqrt(
        matrix(3) * matrix(3) +
          matrix(7) * matrix(7) +
          matrix(11) * matrix(11)
      )
    val truth =
      Vector(
        truthCosine, -truthSine, 0.0,
        truthSine, truthCosine, 0.0,
        0.0, 0.0, 1.0
      )
    val rotationCosine =
      (
        matrix(0) * truth(0) +
          matrix(1) * truth(1) +
          matrix(2) * truth(2) +
          matrix(4) * truth(3) +
          matrix(5) * truth(4) +
          matrix(6) * truth(5) +
          matrix(8) * truth(6) +
          matrix(9) * truth(7) +
          matrix(10) * truth(8) -
          1.0
      ) * 0.5
    PoseError(
      translation,
      math.toDegrees(
        math.acos(math.max(-1.0, math.min(1.0, rotationCosine)))
      )
    )

  private def field(x: Double, y: Double, z: Double): Double =
    7.0 * gaussian(x, y, z, -4.0, 2.0, 1.5, 2.2) +
      5.0 * gaussian(x, y, z, 3.5, -3.0, -1.0, 2.8) +
      3.0 * gaussian(x, y, z, 1.0, 5.0, -4.0, 1.7) +
      0.15 * math.sin(0.31 * x + 0.17 * y - 0.11 * z)

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

  private val optimizerControl: RigidOptimizerControl =
    motion(
      RigidOptimizerControl.create(
        maximumIterations = 40,
        huberThreshold = 1.5,
        initialTranslationStepMm = 1.5,
        initialRotationStepRadians = math.toRadians(3.0),
        minimumTranslationStepMm = 0.01,
        minimumRotationStepRadians = math.toRadians(0.01),
        objectiveTolerance = 1e-10,
        minimumOverlap = 0.6
      )
    )

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def image[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def motion[A](value: Either[MotionError, A]): A =
    value.fold(error => fail(error.message), identity)
