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
import reframe4s.multiscale.MultiscaleError
import reframe4s.multiscale.ScaleLevel
import reframe4s.multiscale.ScaleSchedule
import reframe4s.multiscale.ScaleSpec

final class TemplateMotionEstimatorSuite extends munit.FunSuite:
  test("refreshes a validity-weighted template while preserving its anchor"):
    val fixture = TemplateFixture()
    val refresh =
      motion(
        TemplateRefreshControl.create(
          1,
          TemplateStatistic.ValidityWeightedMean
        )
      )
    val workspace = TemplateMotionEstimatorWorkspace.create
    val unrefreshed =
      motion(
        TemplateMotionEstimator.estimate(
          fixture.samples,
          MotionReference.Middle,
          optimizerSchedule,
          motion(TemplateRefreshControl.create(0)),
          TemplateMotionEstimatorWorkspace.create
        )
      )
    val result =
      motion(
        TemplateMotionEstimator.estimate(
          fixture.samples,
          MotionReference.Middle,
          optimizerSchedule,
          refresh,
          workspace
        )
      )

    assertEqualsDouble(translationX(unrefreshed, 0), -1.0, 0.12)
    assertEqualsDouble(translationX(unrefreshed, 1), 0.0, 0.0)
    assertEqualsDouble(translationX(unrefreshed, 2), 1.0, 0.12)
    assertEquals(result.referenceIndex, 1)
    assertEquals(result.refreshPasses, 1)
    assertEquals(
      result.templateStatistic,
      TemplateStatistic.ValidityWeightedMean
    )
    assertEquals(result.meanFinalObjectives.size, 2)
    assertEquals(result.estimate.size, 3)
    assertEqualsDouble(translationX(result, 0), -1.0, 0.12)
    assertEqualsDouble(translationX(result, 1), 0.0, 0.0)
    assertEqualsDouble(translationX(result, 2), 1.0, 0.12)
    assert(
      result.estimate.frameEstimates(1).levelEstimates.forall(
        estimate =>
          estimate.report.iterations == 0 &&
            estimate.report.initialObjective ==
              estimate.report.finalObjective
      )
    )
    assert(result.template.data.iterator.forall(_.isFinite))
    assert(
      templateMeanSquaredError(result.template.data, fixture) <
        rawMeanSquaredError(fixture)
    )

    assert(workspace.acquire())
    assertEquals(
      TemplateMotionEstimator.estimate(
        fixture.samples,
        MotionReference.Middle,
        optimizerSchedule,
        refresh,
        workspace
      ),
      Left(MotionError.TemplateEstimatorWorkspaceInUse)
    )
    workspace.release()

  test("bounds refresh passes and checks explicit frame references"):
    assertEquals(
      TemplateRefreshControl.create(-1),
      Left(
        MotionError.InvalidTemplateRefreshPasses(
          -1,
          TemplateRefreshControl.MaximumPasses
        )
      )
    )
    assertEquals(
      TemplateRefreshControl.create(
        TemplateRefreshControl.MaximumPasses + 1
      ),
      Left(
        MotionError.InvalidTemplateRefreshPasses(
          TemplateRefreshControl.MaximumPasses + 1,
          TemplateRefreshControl.MaximumPasses
        )
      )
    )
    assertEquals(
      MotionReference.Frame(3).resolve(3),
      Left(MotionError.InvalidReferenceIndex(3, 3))
    )

  private val coarseControl =
    motion(
      RigidOptimizerControl.create(
        maximumIterations = 40,
        huberThreshold = 1.5,
        initialTranslationStepMm = 2.0,
        initialRotationStepRadians = math.toRadians(1.0),
        minimumTranslationStepMm = 0.05,
        minimumRotationStepRadians = math.toRadians(0.05),
        objectiveTolerance = 1e-10,
        minimumOverlap = 0.25
      )
    )

  private val fineControl =
    motion(
      RigidOptimizerControl.create(
        maximumIterations = 60,
        huberThreshold = 1.5,
        initialTranslationStepMm = 1.0,
        initialRotationStepRadians = math.toRadians(0.5),
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
          Vector(0.75, 0.75, 0.75)
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

  private final class TemplateFixture:
    val frame = geometry(Frame.named[D3]("template-frame"))
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
    val data =
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
                builder.writeLinear(
                  linear,
                  field(
                    i.toDouble + shifts(time),
                    j.toDouble,
                    k.toDouble
                  )
                )
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

    def field(
        x: Double,
        y: Double,
        z: Double
    ): Double =
      9.0 * bump(x, y, z, 3.5, 2.0, 1.5, 2.0) +
        4.0 * bump(x, y, z, 6.2, 4.5, 3.0, 1.2)

    private def bump(
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
      val normalizedSquared =
        (dx * dx + dy * dy + dz * dz) / (width * width)
      if normalizedSquared >= 1.0 then 0.0
      else
        val remaining = 1.0 - normalizedSquared
        remaining * remaining

  private def translationX(
      result: TemplateMotionEstimate[?],
      index: Int
  ): Double =
    motion(result.estimate.poses.poseAt(index))
      .movingToFixed
      .operator
      .rowMajor(3)

  private def templateMeanSquaredError(
      template: NDArray[Double, Rank[3]],
      fixture: TemplateFixture
  ): Double =
    var squared = 0.0
    var count = 0
    var i = 1
    while i < fixture.shape(0) - 1 do
      var j = 0
      while j < fixture.shape(1) do
        var k = 0
        while k < fixture.shape(2) do
          val residual =
            template(i, j, k) -
              fixture.field(i.toDouble, j.toDouble, k.toDouble)
          squared += residual * residual
          count += 1
          k += 1
        j += 1
      i += 1
    squared / count.toDouble

  private def rawMeanSquaredError(
      fixture: TemplateFixture
  ): Double =
    var squared = 0.0
    var count = 0
    var i = 1
    while i < fixture.shape(0) - 1 do
      var j = 0
      while j < fixture.shape(1) do
        var k = 0
        while k < fixture.shape(2) do
          var sum = 0.0
          var time = 0
          while time < fixture.shifts.size do
            sum += fixture.data(i, j, k, time)
            time += 1
          val residual =
            sum / fixture.shifts.size.toDouble -
              fixture.field(i.toDouble, j.toDouble, k.toDouble)
          squared += residual * residual
          count += 1
          k += 1
        j += 1
      i += 1
    squared / count.toDouble

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def image[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def multiscale[A](value: Either[MultiscaleError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def motion[A](value: Either[MotionError, A]): A =
    value.fold(error => fail(error.message), identity)
