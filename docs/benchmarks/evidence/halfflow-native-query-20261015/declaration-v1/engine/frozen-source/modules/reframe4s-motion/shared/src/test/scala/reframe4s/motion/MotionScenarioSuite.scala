package reframe4s.motion

import image4s.Axis
import image4s.AxisKind
import image4s.BoundaryPolicy
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import reframe4s.core.MapError
import reframe4s.core.FrameOwnerDescriptor
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.FrameId
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingError

final class MotionScenarioSuite extends munit.FunSuite:
  test(
    "physical centroid estimation honors anisotropic translated oblique grids"
  ):
    val fixture = PhysicalFixture()
    val (pose, diagnostics) =
      motionRight(
        PhysicalCentroidTranslation.estimate(
          fixture.movingImage,
          fixture.fixedImage
        )
      )
    val movingPoint =
      geometryRight(fixture.movingGrid.pointAt(fixture.movingIndex))
    val fixedPoint =
      geometryRight(fixture.fixedGrid.pointAt(fixture.fixedIndex))
    val mapped = mapRight(pose.movingToFixed(movingPoint))

    assertNotEquals(
      fixture.movingGrid.indexToFrame.rowMajor,
      Affine.identity[D3].rowMajor
    )
    assertNotEquals(
      fixture.fixedGrid.indexToFrame.rowMajor,
      Affine.identity[D3].rowMajor
    )
    assertClose(mapped.coordinates, fixedPoint.coordinates)
    assertEquals(diagnostics.support, 1L)
    assertEqualsDouble(
      diagnostics.translationMagnitudePhysical,
      euclideanDistance(movingPoint.coordinates, fixedPoint.coordinates),
      1e-10
    )

  test("motion application uses fixedToMoving as the affine pull map"):
    val fixture = PhysicalFixture()
    val (pose, _) =
      motionRight(
        PhysicalCentroidTranslation.estimate(
          fixture.movingImage,
          fixture.fixedImage
        )
      )
    val plan =
      motionRight(
        MotionApplication.planAt(
          fixture.movingImage,
          fixture.fixedGrid,
          pose,
          interpolation = Interpolation.Linear,
          boundary = BoundaryPolicy.Constant(0.0)
        )
      )
    val result = resamplingRight(plan.run(plan.newWorkspace()))

    var i = 0
    while i < fixture.shape(0) do
      var j = 0
      while j < fixture.shape(1) do
        var k = 0
        while k < fixture.shape(2) do
          val expected =
            if Vector(i, j, k) == fixture.fixedIndex.values then 7.0
            else 0.0
          val actual =
            imageRight(result.image.valueAt(Vector(i, j, k)))
          assertEqualsDouble(actual, expected, 1e-9)
          k += 1
        j += 1
      i += 1

    assertEquals(plan.structure.materializedCoordinateCount, 0)
    assert(pose.fixedToMoving.source eq fixture.fixedFrame)
    assert(pose.fixedToMoving.target eq fixture.movingFrame)

  test("timed scalar samples are a validated zero-copy view"):
    val fixture = PhysicalFixture()
    val time = imageRight(Axis.create("time", 2, AxisKind.Time))
    val axes = imageRight(NonSpatialAxes.from(Vector(time)))
    val data =
      NDArray.zeros[Double, Rank[4]](
        Shape(
          fixture.shape(0),
          fixture.shape(1),
          fixture.shape(2),
          2
        )
      )
    val sampled =
      imageRight(Sampled.continuous(fixture.movingGrid, axes, data))
    val times = motionRight(TimeAxis.create(Vector(0.0, 1.25)))
    val timed = motionRight(TimedScalarSamples.view(sampled, times))

    assert(timed.image eq sampled)
    assert(timed.image.data eq data)
    assertEquals(timed.timeAxisIndex, 0)
    assertEquals(timed.times.seconds, Vector(0.0, 1.25))
    val secondVolume = motionRight(timed.volumeAt(1))
    assertEquals(secondVolume.data.rank, 3)
    assertEquals(
      Vector.tabulate(secondVolume.data.rank)(secondVolume.data.shape.apply),
      fixture.shape
    )
    assert(!secondVolume.data.isContiguous)
    assertEquals(
      TimeAxis.create(Vector(0.0, 0.0)),
      Left(MotionError.NonIncreasingTime(0, 0.0, 0.0))
    )
    assert(
      TimeAxis
        .create(Vector(0.0, Double.NaN))
        .left
        .exists(_.isInstanceOf[MotionError.NonFiniteTime])
    )

    val missing =
      TimedScalarSamples.view(fixture.movingImage, times)
    assertEquals(missing, Left(MotionError.MissingTimeAxis))

    val wrongTimes = motionRight(TimeAxis.create(Vector(0.0)))
    assertEquals(
      TimedScalarSamples.view(sampled, wrongTimes),
      Left(MotionError.TimeExtentMismatch(2, 1))
    )

    val secondTime =
      imageRight(Axis.create("acquisition-time", 1, AxisKind.Time))
    val ambiguousAxes =
      imageRight(NonSpatialAxes.from(Vector(time, secondTime)))
    val ambiguousData =
      NDArray.fromSeq(
        dynamicShape(
          fixture.shape(0),
          fixture.shape(1),
          fixture.shape(2),
          2,
          1
        ),
        Vector.fill(fixture.shape.product * 2)(0.0)
      )
    val ambiguous =
      imageRight(
        Sampled.continuous(
          fixture.movingGrid,
          ambiguousAxes,
          ambiguousData
        )
      )
    assertEquals(
      TimedScalarSamples.view(ambiguous, times),
      Left(MotionError.AmbiguousTimeAxis(2))
    )

    val channel =
      imageRight(Axis.create("channel", 1, AxisKind.Channel))
    val timeAndChannelAxes =
      imageRight(NonSpatialAxes.from(Vector(time, channel)))
    val timeAndChannelData =
      NDArray.fromSeq(
        dynamicShape(
          fixture.shape(0),
          fixture.shape(1),
          fixture.shape(2),
          2,
          1
        ),
        Vector.fill(fixture.shape.product * 2)(0.0)
      )
    val timeAndChannel =
      imageRight(
        Sampled.continuous(
          fixture.movingGrid,
          timeAndChannelAxes,
          timeAndChannelData
        )
      )
    val multiAxisTimed =
      motionRight(TimedScalarSamples.view(timeAndChannel, times))
    assertEquals(
      multiAxisTimed.volumeAt(0),
      Left(MotionError.UnsupportedMotionSeriesAxes(2))
    )

  test(
    "pose-series records roundtrip and reject wrong frames or record counts"
  ):
    val fixture = PhysicalFixture()
    val (estimated, _) =
      motionRight(
        PhysicalCentroidTranslation.estimate(
          fixture.movingImage,
          fixture.fixedImage
        )
      )
    val second =
      RigidPose.fromMovingToFixed(
        rigidRight(
          Rigid3.translation(
            fixture.movingFrame,
            fixture.fixedFrame
          )(1.5, -2.0, 0.25)
        )
      )
    val times = motionRight(TimeAxis.create(Vector(0.0, 2.0)))
    val series =
      motionRight(PoseSeries.create(times, Vector(estimated, second)))
    val record = motionRight(series.record)
    val restored =
      motionRight(
        PoseSeries.restore(
          record,
          fixture.movingFrame,
          fixture.fixedFrame
        )
      )

    assertEquals(motionRight(restored.record), record)
    assertEquals(
      motionRight(restored.poseAt(0)).movingToFixed.operator.rowMajor,
      estimated.movingToFixed.operator.rowMajor
    )
    assertEquals(
      motionRight(restored.poseAt(1)).fixedToMoving.operator.rowMajor,
      second.fixedToMoving.operator.rowMajor
    )

    val otherMoving = geometryRight(Frame.named[D3]("moving"))
    PoseSeries.restore(record, otherMoving, fixture.fixedFrame) match
      case Left(MotionError.MovingEndpointMismatch(expected, actual)) =>
        assertEquals(expected, FrameOwnerDescriptor.of(otherMoving))
        assertEquals(actual, FrameOwnerDescriptor.of(fixture.movingFrame))
      case other =>
        fail(s"expected MovingEndpointMismatch, got $other")

    val otherFixed = geometryRight(Frame.named[D3]("fixed"))
    PoseSeries.restore(record, fixture.movingFrame, otherFixed) match
      case Left(MotionError.FixedEndpointMismatch(expected, actual)) =>
        assertEquals(expected, FrameOwnerDescriptor.of(otherFixed))
        assertEquals(actual, FrameOwnerDescriptor.of(fixture.fixedFrame))
      case other =>
        fail(s"expected FixedEndpointMismatch, got $other")

    val wrongCount =
      record.copy(movingToFixedRowMajor =
        record.movingToFixedRowMajor.dropRight(1)
      )
    assertEquals(
      PoseSeries.restore(
        wrongCount,
        fixture.movingFrame,
        fixture.fixedFrame
      ),
      Left(MotionError.RecordCountMismatch(2, 1))
    )

  private final class PhysicalFixture:
    val shape: Vector[Int] = Vector(4, 4, 3)
    private val movingFrameId =
      geometryRight(FrameId.parse("motion-scenario-moving"))
    private val fixedFrameId =
      geometryRight(FrameId.parse("motion-scenario-fixed"))
    val movingFrame =
      geometryRight(
        Frame.persistentNamed[D3](movingFrameId, "moving")
      )
    val fixedFrame =
      geometryRight(
        Frame.persistentNamed[D3](fixedFrameId, "fixed")
      )
    val movingIndex = geometryRight(LatticeIndex.of[D3](1, 2, 1))
    val fixedIndex = geometryRight(LatticeIndex.of[D3](2, 1, 1))

    private val angle = math.Pi / 6.0
    private val cosine = math.cos(angle)
    private val sine = math.sin(angle)
    private val direction =
      Vector(
        cosine,
        -sine,
        0.0,
        sine,
        cosine,
        0.0,
        0.0,
        0.0,
        1.0
      )
    private val spacing = Vector(2.0, 3.0, 4.0)
    private val movingAffine =
      geometryRight(
        Affine.fromOriginSpacingDirection[D3](
          origin = Vector(11.0, -7.0, 5.0),
          spacing = spacing,
          directionRowMajor = direction
        )
      )
    private val fixedAffine =
      geometryRight(
        Affine.fromOriginSpacingDirection[D3](
          origin = Vector(-4.0, 9.0, 2.0),
          spacing = spacing,
          directionRowMajor = direction
        )
      )

    val movingGrid =
      geometryRight(Grid.in[D3](movingFrame)(shape, movingAffine))
    val fixedGrid =
      geometryRight(Grid.in[D3](fixedFrame)(shape, fixedAffine))
    val movingData =
      NDArray.tabulate[Double](
        shape(0),
        shape(1),
        shape(2)
      )((i, j, k) =>
        if Vector(i, j, k) == movingIndex.values then 7.0 else 0.0
      )
    val fixedData =
      NDArray.tabulate[Double](
        shape(0),
        shape(1),
        shape(2)
      )((i, j, k) =>
        if Vector(i, j, k) == fixedIndex.values then 7.0 else 0.0
      )
    val movingImage =
      imageRight(
        Sampled.continuous(movingGrid, NonSpatialAxes.empty, movingData)
      )
    val fixedImage =
      imageRight(
        Sampled.continuous(fixedGrid, NonSpatialAxes.empty, fixedData)
      )

  private def euclideanDistance(
      left: Vector[Double],
      right: Vector[Double]
  ): Double =
    math.sqrt(
      left.zip(right).iterator.map { case (a, b) =>
        val difference = a - b
        difference * difference
      }.sum
    )

  private def dynamicShape(dimensions: Int*): Shape[AnyRank] =
    Shape.from(dimensions) match
      case Right(shape) => shape
      case Left(error)  => fail(error.toString)

  private def assertClose(
      actual: Vector[Double],
      expected: Vector[Double],
      tolerance: Double = 1e-10
  ): Unit =
    assertEquals(actual.size, expected.size)
    actual.zip(expected).foreach { case (left, right) =>
      assertEqualsDouble(left, right, tolerance)
    }

  private def geometryRight[A](
      value: Either[GeometryError, A]
  ): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def imageRight[A](value: Either[ImageError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def mapRight[A](value: Either[MapError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def motionRight[A](value: Either[MotionError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def rigidRight[A](value: Either[RigidError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def resamplingRight[A](
      value: Either[ResamplingError, A]
  ): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)
