package reframe4s.motion

import image4s.Axis
import image4s.AxisKind
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid

final class RigidSeriesEstimatorSuite extends munit.FunSuite:
  test("fits outward from a declared reference on nonuniform times"):
    val fixture = SeriesFixture()
    val estimator =
      motion(
        CompiledRigidSeriesEstimator.compile(
          fixture.samples,
          referenceIndex = 1,
          optimizerControl
        )
      )
    val first = motion(estimator.run(estimator.newWorkspace()))
    val second = motion(estimator.run(estimator.newWorkspace()))

    assertEquals(first.referenceIndex, 1)
    assertEquals(first.poses.times.seconds, Vector(0.0, 0.7, 2.1))
    assertEquals(first.size, 3)
    assertEquals(first.frameEstimates.size, 3)
    assertEqualsDouble(translationX(first, 0), -1.0, 0.07)
    assertEqualsDouble(translationX(first, 1), 0.0, 1e-12)
    assertEqualsDouble(translationX(first, 2), 1.0, 0.07)

    val reference = motion(first.frameAt(1))
    assertEquals(reference.report.initialObjective, 0.0)
    assertEquals(reference.report.finalObjective, 0.0)
    assertEqualsDouble(reference.overlap, 1.0, 0.0)
    assert(first.frameEstimates.forall(_.supportedVoxels > 0L))
    assert(
      first.frameEstimates.forall(
        _.diagnostics.strategy ==
          RigidOptimizationStrategy.LevenbergMarquardt
      )
    )
    assertEquals(
      first.poses.poses.map(_.movingToFixed.operator.rowMajor),
      second.poses.poses.map(_.movingToFixed.operator.rowMajor)
    )
    first.frameEstimates.zip(second.frameEstimates).foreach {
      case (left, right) =>
        assertEqualsDouble(
          left.report.finalObjective,
          right.report.finalObjective,
          0.0
        )
    }

  test("rejects invalid references, shared workspaces, and bad frames"):
    val fixture = SeriesFixture()
    assertEquals(
      CompiledRigidSeriesEstimator.compile(
        fixture.samples,
        referenceIndex = -1,
        optimizerControl
      ),
      Left(MotionError.InvalidReferenceIndex(-1, 3))
    )
    assertEquals(
      CompiledRigidSeriesEstimator.compile(
        fixture.samples,
        referenceIndex = 3,
        optimizerControl
      ),
      Left(MotionError.InvalidReferenceIndex(3, 3))
    )

    val estimator =
      motion(
        CompiledRigidSeriesEstimator.compile(
          fixture.samples,
          referenceIndex = 1,
          optimizerControl
        )
      )
    val workspace = estimator.newWorkspace()
    assert(workspace.acquire())
    assertEquals(
      estimator.run(workspace),
      Left(MotionError.SeriesEstimatorWorkspaceInUse)
    )
    workspace.release()

    CompiledRigidSeriesEstimator.compile(
      SeriesFixture(nonFiniteFrame = Some(2)).samples,
      referenceIndex = 1,
      optimizerControl
    ) match
      case Left(
            MotionError.FrameEstimationFailed(
              2,
              MotionError.NonFiniteEstimatorVoxel(
                EstimatorInput.Moving,
                1,
                1,
                1,
                value
              )
            )
          ) =>
        assert(value.isNaN)
      case other =>
        fail(s"expected an indexed non-finite frame failure, got $other")

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

  private final class SeriesFixture(
      nonFiniteFrame: Option[Int] = None
  ):
    val frame = geometry(Frame.named[D3]("series-frame"))
    val shape = Vector(9, 7, 5)
    val shifts = Vector(-1.0, 0.0, 1.0)
    val grid =
      geometry(
        Grid.in[D3](frame)(shape, Affine.identity[D3])
      )
    private val timeAxis =
      image(Axis.create("time", shifts.size, AxisKind.Time))
    private val axes =
      image(NonSpatialAxes.from(Vector(timeAxis)))
    private val data =
      NDArray.build[Double, Rank[4]](
        Shape(shape(0), shape(1), shape(2), shifts.size)
      ) { builder =>
        var i = 0
        while i < shape(0) do
          var j = 0
          while j < shape(1) do
            var k = 0
            while k < shape(2) do
              var time = 0
              while time < shifts.size do
                val linear =
                  (((i * shape(1)) + j) * shape(2) + k) *
                    shifts.size + time
                val value =
                  if
                    nonFiniteFrame.contains(time) &&
                    i == 1 &&
                    j == 1 &&
                    k == 1
                  then Double.NaN
                  else
                    field(
                      i.toDouble + shifts(time),
                      j.toDouble,
                      k.toDouble
                    )
                builder.writeLinear(linear, value)
                time += 1
              k += 1
            j += 1
          i += 1
      }
    private val imageSeries =
      image(Sampled.continuous(grid, axes, data))
    private val times =
      motion(TimeAxis.create(Vector(0.0, 0.7, 2.1)))
    val samples =
      motion(TimedScalarSamples.view(imageSeries, times))

    private def field(
        x: Double,
        y: Double,
        z: Double
    ): Double =
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
      math.exp(
        -(dx * dx + dy * dy + dz * dz) /
          (2.0 * width * width)
      )

  private def translationX(
      estimate: RigidSeriesEstimate[?],
      index: Int
  ): Double =
    motion(estimate.poses.poseAt(index))
      .movingToFixed
      .operator
      .rowMajor(3)

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def image[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def motion[A](value: Either[MotionError, A]): A =
    value.fold(error => fail(error.message), identity)
