package reframe4s.motion

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.FrameId
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.lie.Twist6

final class MotionTemporalSuite extends munit.FunSuite:
  test("pose-series persistence rejects ephemeral endpoint owners"):
    val ephemeralMoving = geometry(Frame.named[D3]("moving"))
    val ephemeralFixed = geometry(Frame.named[D3]("fixed"))
    val times = motion(TimeAxis.create(Vector(0.0)))
    val ephemeralSeries =
      motion(
        PoseSeries.create(
          times,
          Vector(
            RigidPose.fromMovingToFixed(
              rigid(
                Rigid3.translation(ephemeralMoving, ephemeralFixed)(
                  0.0,
                  0.0,
                  0.0
                )
              )
            )
          )
        )
      )

    assertEquals(
      ephemeralSeries.record,
      Left(
        MotionError.PersistentFrameRequired(
          MotionEndpoint.Moving,
          FrameOwnerDescriptor.of(ephemeralMoving)
        )
      )
    )

    val persistentMoving =
      geometry(
        Frame.persistentNamed[D3](
          geometry(FrameId.parse("temporal-persistent-moving")),
          "moving"
        )
      )
    val fixedEphemeralSeries =
      motion(
        PoseSeries.create(
          times,
          Vector(
            RigidPose.fromMovingToFixed(
              rigid(
                Rigid3.translation(persistentMoving, ephemeralFixed)(
                  0.0,
                  0.0,
                  0.0
                )
              )
            )
          )
        )
      )

    assertEquals(
      fixedEphemeralSeries.record,
      Left(
        MotionError.PersistentFrameRequired(
          MotionEndpoint.Fixed,
          FrameOwnerDescriptor.of(ephemeralFixed)
        )
      )
    )

  test("pose records reject matrices outside SE(3)"):
    val moving =
      geometry(
        Frame.persistentNamed[D3](
          geometry(FrameId.parse("temporal-moving")),
          "moving"
        )
      )
    val fixed =
      geometry(
        Frame.persistentNamed[D3](
          geometry(FrameId.parse("temporal-fixed")),
          "fixed"
        )
      )
    val times = Vector(0.0)
    val reflection =
      Vector(
        -1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      )
    val record =
      PoseSeriesRecord(
        moving.persistentKey.getOrElse(fail("missing moving key")),
        fixed.persistentKey.getOrElse(fail("missing fixed key")),
        times,
        Vector(reflection)
      )

    PoseSeries.restore(record, moving, fixed) match
      case Left(MotionError.Rigid(RigidError.ImproperRotation(_, _))) =>
        assert(true)
      case other =>
        fail(s"expected improper rigid rotation, got $other")

  test("trajectory interpolation uses the short SE(3) path"):
    val moving = geometry(Frame.named[D3]("moving"))
    val fixed = geometry(Frame.named[D3]("fixed"))
    val first = poseAtAngle(moving, fixed, math.toRadians(179.0))
    val second = poseAtAngle(moving, fixed, math.toRadians(-179.0))
    val times = motion(TimeAxis.create(Vector(0.0, 2.0)))
    val series =
      motion(PoseSeries.create(times, Vector(first, second)))
    val trajectory = PoseTrajectory.fromSeries(series)
    val midpoint = motion(trajectory.poseAtSeconds(1.0))
    val angle = rotationAngle(midpoint.movingToFixed.operator.rowMajor)

    assertEqualsDouble(math.abs(angle), math.Pi, 1e-8)
    assert(motion(trajectory.poseAtSeconds(0.0)) eq first)
    assert(motion(trajectory.poseAtSeconds(2.0)) eq second)

  test("trajectory honors nonuniform knots and explicit extrapolation"):
    val moving = geometry(Frame.named[D3]("moving"))
    val fixed = geometry(Frame.named[D3]("fixed"))
    val poses =
      Vector(0.0, 2.0, 5.0).map { translation =>
        RigidPose.fromMovingToFixed(
          rigid(
            Rigid3.translation(moving, fixed)(translation, 0.0, 0.0)
          )
        )
      }
    val times = motion(TimeAxis.create(Vector(0.0, 2.0, 5.0)))
    val series = motion(PoseSeries.create(times, poses))
    val trajectory = PoseTrajectory.fromSeries(series)
    val interpolated = motion(trajectory.poseAtSeconds(3.5))

    assertEqualsDouble(
      interpolated.movingToFixed.operator.rowMajor(3),
      3.5,
      1e-10
    )
    assertEquals(
      trajectory.poseAtSeconds(-0.1),
      Left(MotionError.QueryBeforeSeries(-0.1, 0.0))
    )
    assertEquals(
      trajectory.poseAtSeconds(5.1),
      Left(MotionError.QueryAfterSeries(5.1, 5.0))
    )
    assert(
      motion(
        trajectory.poseAtSeconds(
          -0.1,
          ExtrapolationPolicy.Clamp
        )
      ) eq poses.head
    )
    assert(
      trajectory
        .poseAtSeconds(Double.NaN)
        .left
        .exists(_.isInstanceOf[MotionError.NonFiniteQueryTime])
    )

  test("trajectory reproduces a constant target-frame twist"):
    val moving = geometry(Frame.named[D3]("moving"))
    val fixed = geometry(Frame.named[D3]("fixed"))
    val base =
      rigid(Rigid3.translation(moving, fixed)(1.0, -2.0, 0.5))
    val velocity: Twist6[fixed.type] =
      rigid(
        Twist6.create(fixed)(
          0.3,
          -0.2,
          0.1,
          0.04,
          -0.03,
          0.02
        )
      )
    val afterTwo =
      rigid(
        velocity
          .scaled(2.0)
          .flatMap(Rigid3.exp)
          .flatMap(base.andThenRigid)
      )
    val first = RigidPose.fromMovingToFixed(base)
    val second = RigidPose.fromMovingToFixed(afterTwo)
    val times = motion(TimeAxis.create(Vector(0.0, 2.0)))
    val series =
      motion(PoseSeries.create(times, Vector(first, second)))
    val trajectory = PoseTrajectory.fromSeries(series)
    val actual = motion(trajectory.poseAtSeconds(0.75))
    val expected =
      rigid(
        velocity
          .scaled(0.75)
          .flatMap(Rigid3.exp)
          .flatMap(base.andThenRigid)
      )

    assertVectorClose(
      actual.movingToFixed.operator.rowMajor,
      expected.operator.rowMajor,
      1e-10
    )

  test("slice and packet schedules validate exact spatial coverage"):
    val frame = geometry(Frame.named[D3]("frame"))
    val grid =
      geometry(
        Grid.in[D3](frame)(
          Vector(4, 3, 2),
          Affine.identity[D3]
        )
      )
    val sliceSchedule =
      motion(
        AcquisitionSchedule.slices(
          GridAxis.I,
          Vector(-0.25, 0.0, 0.25, 0.5)
        )
      )
    val packetSchedule =
      motion(
        AcquisitionSchedule.packets(
          GridAxis.I,
          4,
          Vector(
            AcquisitionPacket(Vector(0, 2), -0.1),
            AcquisitionPacket(Vector(1, 3), 0.1)
          )
        )
      )

    assertEquals(sliceSchedule.validateGrid(grid), Right(()))
    assertEquals(packetSchedule.validateGrid(grid), Right(()))
    assertEquals(
      motion(
        packetSchedule.offsetSecondsAt(
          geometry(LatticeIndex.of[D3](3, 0, 0))
        )
      ),
      0.1
    )
    assertEquals(
      AcquisitionSchedule.packets(
        GridAxis.I,
        4,
        Vector(
          AcquisitionPacket(Vector(0, 1), 0.0),
          AcquisitionPacket(Vector(1, 2, 3), 0.1)
        )
      ),
      Left(MotionError.DuplicateAcquisitionSlice(1))
    )
    assertEquals(
      AcquisitionSchedule.packets(
        GridAxis.I,
        4,
        Vector(AcquisitionPacket(Vector(0, 2), 0.0))
      ),
      Left(MotionError.MissingAcquisitionSlices(Vector(1, 3)))
    )
    val wrongExtent =
      motion(
        AcquisitionSchedule.slices(
          GridAxis.K,
          Vector(0.0, 0.1, 0.2)
        )
      )
    assertEquals(
      wrongExtent.validateGrid(grid),
      Left(MotionError.GridSliceExtentMismatch(GridAxis.K, 3, 2))
    )

  test("acquisition offsets query the same trajectory contract"):
    val moving = geometry(Frame.named[D3]("moving"))
    val fixed = geometry(Frame.named[D3]("fixed"))
    val first =
      RigidPose.fromMovingToFixed(
        rigid(Rigid3.translation(moving, fixed)(0.0, 0.0, 0.0))
      )
    val second =
      RigidPose.fromMovingToFixed(
        rigid(Rigid3.translation(moving, fixed)(2.0, 0.0, 0.0))
      )
    val times = motion(TimeAxis.create(Vector(0.0, 2.0)))
    val series = motion(PoseSeries.create(times, Vector(first, second)))
    val trajectory = PoseTrajectory.fromSeries(series)
    val schedule =
      motion(AcquisitionSchedule.slices(GridAxis.I, Vector(0.0, 1.0)))
    val sliceOne = geometry(LatticeIndex.of[D3](1, 0, 0))
    val acquired =
      motion(
        trajectory.poseForAcquisition(
          volumeIndex = 0,
          spatialIndex = sliceOne,
          schedule = schedule
        )
      )

    assertEqualsDouble(
      acquired.movingToFixed.operator.rowMajor(3),
      1.0,
      1e-10
    )

  private def poseAtAngle[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: Moving,
      fixed: Fixed,
      angle: Double
  ): RigidPose[Moving, Fixed] =
    val twist =
      rigid(
        Twist6.create(moving)(
          0.0,
          0.0,
          0.0,
          0.0,
          0.0,
          angle
        )
      )
    val local = rigid(Rigid3.exp(twist))
    RigidPose.fromMovingToFixed(
      rigid(
        Rigid3.fromAffine[Moving, Fixed](
          moving,
          fixed
        )(local.operator)
      )
    )

  private def rotationAngle(rowMajor: Vector[Double]): Double =
    val trace = rowMajor(0) + rowMajor(5) + rowMajor(10)
    math.acos(math.max(-1.0, math.min(1.0, (trace - 1.0) / 2.0)))

  private def assertVectorClose(
      actual: Vector[Double],
      expected: Vector[Double],
      tolerance: Double
  ): Unit =
    assertEquals(actual.size, expected.size)
    actual.zip(expected).foreach { case (left, right) =>
      assertEqualsDouble(left, right, tolerance)
    }

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def motion[A](value: Either[MotionError, A]): A =
    value.fold(error => fail(error.message), identity)
