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
import reframe4s.lie.Rigid3
import reframe4s.multiscale.MultiscaleError
import reframe4s.multiscale.ScaleLevel
import reframe4s.multiscale.ScaleSchedule
import reframe4s.multiscale.ScaleSpec
import reframe4s.multiscale.ScalarPyramidWorkspace

final class MultiscaleRigidEstimatorSuite extends munit.FunSuite:
  test("continues a deterministic physical pose across filtered levels"):
    val fixture = MultiscaleFixture()
    val compileWorkspace = ScalarPyramidWorkspace.create
    val estimator =
      motion(
        CompiledMultiscaleRigidPairEstimator.compile(
          fixture.movingImage,
          fixture.fixedImage,
          optimizerSchedule,
          compileWorkspace
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

    assertEquals(estimator.size, 2)
    assertEquals(first.levelEstimates.size, 2)
    assertEquals(
      estimator.movingPyramid.levels.head.image.grid.shape,
      Vector(7, 5, 3)
    )
    assert(
      !(estimator.movingPyramid.levels.head.image.data eq
        fixture.movingImage.data)
    )
    assert(
      estimator.movingPyramid.levels.last.image.data eq
        fixture.movingImage.data
    )
    assertEqualsDouble(
      first.pose.movingToFixed.operator.rowMajor(3),
      3.0,
      0.12
    )
    assert(
      first.levelEstimates.forall(estimate =>
        estimate.report.finalObjective <=
          estimate.report.initialObjective
      )
    )
    assert(
      first.levelEstimates.forall(
        _.diagnostics.strategy ==
          RigidOptimizationStrategy.LevenbergMarquardt
      )
    )
    assertEquals(
      first.pose.movingToFixed.operator.rowMajor,
      second.pose.movingToFixed.operator.rowMajor
    )

    val workspace = estimator.newWorkspace()
    assert(workspace.acquire())
    assertEquals(
      estimator.run(workspace),
      Left(MotionError.MultiscaleEstimatorWorkspaceInUse)
    )
    workspace.release()

  private val coarseControl =
    motion(
      RigidOptimizerControl.create(
        maximumIterations = 32,
        huberThreshold = 1.5,
        initialTranslationStepMm = 4.0,
        initialRotationStepRadians = math.toRadians(2.0),
        minimumTranslationStepMm = 0.05,
        minimumRotationStepRadians = math.toRadians(0.05),
        objectiveTolerance = 1e-10,
        minimumOverlap = 0.25
      )
    )

  private val fineControl =
    motion(
      RigidOptimizerControl.create(
        maximumIterations = 24,
        huberThreshold = 1.5,
        initialTranslationStepMm = 1.0,
        initialRotationStepRadians = math.toRadians(1.0),
        minimumTranslationStepMm = 0.025,
        minimumRotationStepRadians = math.toRadians(0.025),
        objectiveTolerance = 1e-10,
        minimumOverlap = 0.25
      )
    )

  private val optimizerSchedule
      : ScaleSchedule[D3, RigidOptimizerControl] =
    val coarse =
      multiscale(
        ScaleSpec.create[D3](
          Vector(2, 2, 2),
          Vector(1.0, 1.0, 1.0)
        )
      )
    val native =
      multiscale(
        ScaleSpec.create[D3](
          Vector(1, 1, 1),
          Vector(0.0, 0.0, 0.0)
        )
      )
    multiscale(
      ScaleSchedule.create(
        Vector(
          ScaleLevel(coarse, coarseControl),
          ScaleLevel(native, fineControl)
        )
      )
    )

  private final class MultiscaleFixture:
    val movingFrame = geometry(Frame.named[D3]("multiscale-moving"))
    val fixedFrame = geometry(Frame.named[D3]("multiscale-fixed"))
    val shape = Vector(13, 9, 5)
    private val movingGrid =
      geometry(
        Grid.in[D3](movingFrame)(
          shape,
          Affine.identity[D3]
        )
      )
    private val fixedGrid =
      geometry(
        Grid.in[D3](fixedFrame)(
          shape,
          Affine.identity[D3]
        )
      )
    private val fixedData =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) =>
          field(i.toDouble, j.toDouble, k.toDouble)
      }
    private val movingData =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) =>
          field(i.toDouble + 3.0, j.toDouble, k.toDouble)
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

    private def field(
        x: Double,
        y: Double,
        z: Double
    ): Double =
      9.0 * gaussian(x, y, z, 5.0, 3.0, 1.5, 1.4) +
        4.0 * gaussian(x, y, z, 9.5, 6.2, 3.0, 0.9)

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
      math.exp(
        -(dx * dx + dy * dy + dz * dz) /
          (2.0 * width * width)
      )

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def image[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[reframe4s.lie.RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def multiscale[A](value: Either[MultiscaleError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def motion[A](value: Either[MotionError, A]): A =
    value.fold(error => fail(error.message), identity)
