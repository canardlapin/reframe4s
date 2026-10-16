package reframe4s.motion

import image4s.Axis
import image4s.AxisKind
import image4s.BoundaryPolicy
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
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
import reframe4s.resample.Interpolation

final class MotionApplicationSuite extends munit.FunSuite:
  test("applies one affine-correct production plan per volume"):
    val fixture = ApplicationFixture(VolumePattern)
    val compiled =
      motion(
        CompiledMotionApplication.compile(
          fixture.samples,
          fixture.grid,
          fixture.trajectory,
          schedule = AcquisitionSchedule.Volume,
          interpolation = Interpolation.Nearest,
          boundary = BoundaryPolicy.Constant(0.0)
        )
      )
    val first = motion(compiled.run(compiled.newWorkspace()))
    val second = motion(compiled.run(compiled.newWorkspace()))

    assertEquals(
      compiled.structure,
      MotionApplicationStructure(
        volumes = 2,
        compiledPlans = 2,
        materializedCoordinateCount = 0
      )
    )
    assertCorrected(first, fixture.shape)
    assertEquals(
      first.image.data.iterator.toVector,
      second.image.data.iterator.toVector
    )
    assertEqualsDouble(
      sampled(first.validityWeights, 0, 0, 0, 0),
      1.0,
      0.0
    )
    assertEqualsDouble(
      sampled(first.validityWeights, 0, 0, 0, 1),
      0.0,
      0.0
    )
    assertEqualsDouble(
      sampled(first.validityWeights, 1, 0, 0, 1),
      1.0,
      0.0
    )

    val workspace = compiled.newWorkspace()
    assert(workspace.acquire())
    assertEquals(
      compiled.run(workspace),
      Left(MotionError.MotionApplicationWorkspaceInUse)
    )
    workspace.release()

  test("applies interpolated poses on explicit slice packets"):
    val fixture = ApplicationFixture(PacketPattern)
    val schedule =
      motion(
        AcquisitionSchedule.slices(
          GridAxis.K,
          Vector(0.0, 0.5)
        )
      )
    val compiled =
      motion(
        CompiledMotionApplication.compile(
          fixture.samples,
          fixture.grid,
          fixture.trajectory,
          schedule,
          interpolation = Interpolation.Nearest,
          boundary = BoundaryPolicy.Constant(0.0),
          extrapolation = ExtrapolationPolicy.Clamp
        )
      )
    val result = motion(compiled.run(compiled.newWorkspace()))

    assertEquals(
      compiled.structure,
      MotionApplicationStructure(
        volumes = 2,
        compiledPlans = 4,
        materializedCoordinateCount = 0
      )
    )
    assertCorrected(result, fixture.shape)

  test("native motion application selects canonical Lanczos-5 resampling"):
    val fixture = ApplicationFixture(VolumePattern)
    val compiled =
      motion(
        CompiledMotionApplication.compile(
          fixture.samples,
          fixture.grid,
          fixture.trajectory,
          schedule = AcquisitionSchedule.Volume,
          interpolation = Interpolation.Lanczos5,
          boundary = BoundaryPolicy.Constant(0.0)
        )
      )
    val first = motion(compiled.run(compiled.newWorkspace()))
    val second = motion(compiled.run(compiled.newWorkspace()))

    assertEquals(compiled.interpolation, Interpolation.Lanczos5)
    assertEquals(compiled.structure.materializedCoordinateCount, 0)
    assert(first.image.data.iterator.forall(_.isFinite))
    assert(
      first.validityWeights.data.iterator.forall(weight =>
        weight.isFinite && weight >= 0.0 && weight <= 1.0
      )
    )
    assertEquals(
      first.image.data.iterator.toVector,
      second.image.data.iterator.toVector
    )
    assertEquals(
      first.validityWeights.data.iterator.toVector,
      second.validityWeights.data.iterator.toVector
    )

  test("requires the sample and pose time axes to agree exactly"):
    val fixture = ApplicationFixture(VolumePattern)
    val mismatchedTimes =
      motion(TimeAxis.create(Vector(0.0, 1.1)))
    val mismatchedSeries =
      motion(
        PoseSeries.create(
          mismatchedTimes,
          fixture.trajectory.series.poses
        )
      )
    assertEquals(
      CompiledMotionApplication.compile(
        fixture.samples,
        fixture.grid,
        PoseTrajectory.fromSeries(mismatchedSeries)
      ),
      Left(MotionError.PoseTimeMismatch(1, 1.0, 1.1))
    )

  test(
    "analytic linear field proves full-affine pull polarity for translation and rotation"
  ):
    val fixture = AsymmetricLinearFieldFixture()
    val cases =
      Vector(
        "translation" -> fixture.translationPose,
        "four-degree rotation" -> fixture.rotationPose
      )

    cases.foreach { case (label, pose) =>
      val plan =
        motion(
          MotionApplication.planAt(
            fixture.movingImage,
            fixture.fixedGrid,
            pose,
            interpolation = Interpolation.Linear,
            boundary = BoundaryPolicy.Constant(-73.0)
          )
        )
      val result =
        plan
          .run(plan.newWorkspace())
          .fold(error => fail(error.message), identity)
      var fullySupported = 0
      var i = 0
      while i < fixture.fixedShape(0) do
        var j = 0
        while j < fixture.fixedShape(1) do
          var k = 0
          while k < fixture.fixedShape(2) do
            val index = Vector(i, j, k)
            val weight =
              image(result.validity.weights.valueAt(index))
            if weight >= 1.0 - 1e-12 then
              val targetPoint =
                geometry(
                  fixture.fixedGrid.pointAt(
                    geometry(LatticeIndex.of[D3](i, j, k))
                  )
                )
              val sourcePoint =
                mapped(pose.fixedToMoving(targetPoint))
              val expected = fixture.field(sourcePoint.coordinates)
              val actual = image(result.image.valueAt(index))
              assertCombinedClose(
                actual,
                expected,
                absoluteTolerance = 1e-9,
                relativeTolerance = 1e-11,
                clue = s"$label at $index"
              )
              fullySupported += 1
            k += 1
          j += 1
        i += 1

      assert(
        fullySupported >= 24,
        s"$label produced only $fullySupported fully supported target voxels"
      )
      assertEquals(plan.structure.materializedCoordinateCount, 0)
      assert(pose.fixedToMoving.source eq fixture.fixedFrame)
      assert(pose.fixedToMoving.target eq fixture.movingFrame)
    }

  private sealed trait InputPattern
  private case object VolumePattern extends InputPattern
  private case object PacketPattern extends InputPattern

  private final class ApplicationFixture(pattern: InputPattern):
    val frame = geometry(Frame.named[D3]("application-frame"))
    val shape = Vector(7, 3, 2)
    val grid =
      geometry(
        Grid.in[D3](frame)(shape, Affine.identity[D3])
      )
    private val timeAxis =
      image(Axis.create("time", 2, AxisKind.Time))
    private val axes =
      image(NonSpatialAxes.from(Vector(timeAxis)))
    private val data =
      NDArray.build[Double, Rank[4]](
        Shape(shape(0), shape(1), shape(2), 2)
      ) { builder =>
        var i = 0
        while i < shape(0) do
          var j = 0
          while j < shape(1) do
            var k = 0
            while k < shape(2) do
              var time = 0
              while time < 2 do
                val impulse =
                  pattern match
                    case VolumePattern =>
                      if time == 0 then 3 else 2
                    case PacketPattern =>
                      if time == 0 then
                        if k == 0 then 3 else 2
                      else 1
                val value =
                  if i == impulse then 10.0 + j + 3.0 * k
                  else 0.0
                val linear =
                  (((i * shape(1)) + j) * shape(2) + k) * 2 + time
                builder.writeLinear(linear, value)
                time += 1
              k += 1
            j += 1
          i += 1
      }
    private val series =
      image(Sampled.continuous(grid, axes, data))
    private val times =
      motion(TimeAxis.create(Vector(0.0, 1.0)))
    val samples =
      motion(TimedScalarSamples.view(series, times))
    private val identityPose: RigidPose[frame.type, frame.type] =
      RigidPose.fromMovingToFixed(
        Rigid3.identity[frame.type](frame)
      )
    private val translatedPose: RigidPose[frame.type, frame.type] =
      RigidPose.fromMovingToFixed(
        rigid(
          Rigid3.translationBetween[frame.type, frame.type](frame, frame)(
            if pattern == VolumePattern then 1.0 else 2.0,
            0.0,
            0.0
          )
        )
      )
    private val poses =
      motion(
        PoseSeries.create(
          times,
          Vector(identityPose, translatedPose)
        )
      )
    val trajectory =
      PoseTrajectory.fromSeries(poses)

  private final class AsymmetricLinearFieldFixture:
    val movingFrame =
      geometry(Frame.named[D3]("analytic-asymmetric-moving"))
    val fixedFrame =
      geometry(Frame.named[D3]("analytic-asymmetric-fixed"))
    private val movingShape = Vector(9, 7, 5)
    val fixedShape = Vector(6, 8, 4)
    private val movingSpacing = Vector(1.3, 2.1, 3.7)
    private val fixedSpacing = Vector(2.4, 1.1, 2.8)
    private val movingOrigin = Vector(31.0, -17.0, 8.0)
    private val translation = Vector(2.25, -1.5, 0.75)
    private val movingDirection =
      multiply3(rotationZ(math.toRadians(27.0)), rotationY(math.toRadians(-13.0)))
    private val fixedDirection =
      multiply3(rotationY(math.toRadians(11.0)), rotationZ(math.toRadians(-19.0)))
    private val movingCenter =
      add3(
        movingOrigin,
        multiply3Vector(
          movingDirection,
          movingSpacing
            .zip(centerIndex(movingShape))
            .map { case (spacing, index) => spacing * index }
        )
      )
    private val fixedCenter = add3(movingCenter, translation)
    private val fixedOrigin =
      subtract3(
        fixedCenter,
        multiply3Vector(
          fixedDirection,
          fixedSpacing
            .zip(centerIndex(fixedShape))
            .map { case (spacing, index) => spacing * index }
        )
      )
    private val movingAffine =
      geometry(
        Affine.fromOriginSpacingDirection[D3](
          movingOrigin,
          movingSpacing,
          movingDirection
        )
      )
    private val fixedAffine =
      geometry(
        Affine.fromOriginSpacingDirection[D3](
          fixedOrigin,
          fixedSpacing,
          fixedDirection
        )
      )
    private val movingGrid =
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
        field(physicalAt(movingAffine, i, j, k))
      )
    val movingImage =
      image(
        Sampled.continuous(
          movingGrid,
          NonSpatialAxes.empty,
          movingData
        )
      )
    val translationPose
        : RigidPose[movingFrame.type, fixedFrame.type] =
      RigidPose.fromMovingToFixed(
        rigid(
          Rigid3.translation(
            movingFrame,
            fixedFrame
          )(translation(0), translation(1), translation(2))
        )
      )
    private val rotation = rotationZ(math.toRadians(4.0))
    private val rotatedTranslation =
      subtract3(fixedCenter, multiply3Vector(rotation, movingCenter))
    val rotationPose
        : RigidPose[movingFrame.type, fixedFrame.type] =
      RigidPose.fromMovingToFixed(
        rigid(
          Rigid3.fromRowMajor(
            movingFrame,
            fixedFrame,
            Vector(
              rotation(0), rotation(1), rotation(2), rotatedTranslation(0),
              rotation(3), rotation(4), rotation(5), rotatedTranslation(1),
              rotation(6), rotation(7), rotation(8), rotatedTranslation(2),
              0.0, 0.0, 0.0, 1.0
            )
          )
        )
      )

    def field(point: Vector[Double]): Double =
      2.0 + 0.1 * point(0) - 0.2 * point(1) + 0.05 * point(2)

  private def centerIndex(shape: Vector[Int]): Vector[Double] =
    shape.map(extent => (extent.toDouble - 1.0) * 0.5)

  private def physicalAt(
      affine: Affine[D3],
      i: Int,
      j: Int,
      k: Int
  ): Vector[Double] =
    val matrix = affine.rowMajor
    Vector(
      matrix(0) * i + matrix(1) * j + matrix(2) * k + matrix(3),
      matrix(4) * i + matrix(5) * j + matrix(6) * k + matrix(7),
      matrix(8) * i + matrix(9) * j + matrix(10) * k + matrix(11)
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

  private def multiply3Vector(
      matrix: Vector[Double],
      vector: Vector[Double]
  ): Vector[Double] =
    Vector.tabulate(3) { row =>
      matrix(row * 3) * vector(0) +
        matrix(row * 3 + 1) * vector(1) +
        matrix(row * 3 + 2) * vector(2)
    }

  private def add3(
      left: Vector[Double],
      right: Vector[Double]
  ): Vector[Double] =
    left.zip(right).map { case (a, b) => a + b }

  private def subtract3(
      left: Vector[Double],
      right: Vector[Double]
  ): Vector[Double] =
    left.zip(right).map { case (a, b) => a - b }

  private def assertCombinedClose(
      actual: Double,
      expected: Double,
      absoluteTolerance: Double,
      relativeTolerance: Double,
      clue: String
  ): Unit =
    val tolerance =
      absoluteTolerance +
        relativeTolerance * math.max(math.abs(actual), math.abs(expected))
    assert(
      math.abs(actual - expected) <= tolerance,
      s"$clue: expected $expected, got $actual (tolerance $tolerance)"
    )

  private def assertCorrected(
      result: MotionApplicationResult[?],
      shape: Vector[Int]
  ): Unit =
    var i = 0
    while i < shape(0) do
      var j = 0
      while j < shape(1) do
        var k = 0
        while k < shape(2) do
          var time = 0
          while time < 2 do
            val expected =
              if i == 3 then 10.0 + j + 3.0 * k else 0.0
            assertEqualsDouble(
              sampled(result.image, i, j, k, time),
              expected,
              0.0
            )
            time += 1
          k += 1
        j += 1
      i += 1

  private def sampled[F <: Frame[D3]](
      image: MotionScalarImage[F, D3, Rank[4]],
      i: Int,
      j: Int,
      k: Int,
      time: Int
  ): Double =
    this.image(image.valueAt(Vector(i, j, k), Vector(time)))

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def image[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def mapped[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def motion[A](value: Either[MotionError, A]): A =
    value.fold(error => fail(error.message), identity)
