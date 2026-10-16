package reframe4s.motion

import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Point
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.lie.Twist6

final class MotionMetricsSuite extends munit.FunSuite:
  test("framewise displacement uses a wrapped relative group increment"):
    val moving = geometry(Frame.named[D3]("moving"))
    val fixed = geometry(Frame.named[D3]("fixed"))
    val first = poseAtAngle(moving, fixed, math.toRadians(179.0))
    val second = poseAtAngle(moving, fixed, math.toRadians(-179.0))
    val times = motion(TimeAxis.create(Vector(0.0, 1.0)))
    val series =
      motion(PoseSeries.create(times, Vector(first, second)))
    val radius = motion(HeadRadius.create(50.0))
    val values =
      motion(MotionMetrics.framewiseDisplacement(series, radius))

    assertEqualsDouble(values(0), 0.0, 0.0)
    assertEqualsDouble(
      values(1),
      50.0 * math.toRadians(2.0),
      1e-10
    )

  test("physical point displacement has explicit support and units"):
    val moving = geometry(Frame.named[D3]("moving"))
    val fixed = geometry(Frame.named[D3]("fixed"))
    val reference =
      RigidPose.fromMovingToFixed(
        rigid(Rigid3.translation(moving, fixed)(0.0, 0.0, 0.0))
      )
    val translated =
      RigidPose.fromMovingToFixed(
        rigid(Rigid3.translation(moving, fixed)(3.0, 4.0, 0.0))
      )
    val support =
      Vector(
        geometry(Point.in[D3](moving)(0.0, 0.0, 0.0)),
        geometry(Point.in[D3](moving)(10.0, -2.0, 5.0))
      )
    val values =
      motion(
        MotionMetrics.pointDisplacements(
          translated,
          reference,
          support
        )
      )
    val summary =
      motion(
        MotionMetrics.displacementSummary(
          translated,
          reference,
          support
        )
      )

    assertEquals(values, Vector(5.0, 5.0))
    assertEqualsDouble(summary.meanMillimeters, 5.0, 1e-12)
    assertEqualsDouble(summary.medianMillimeters, 5.0, 1e-12)
    assertEqualsDouble(summary.p95Millimeters, 5.0, 1e-12)
    assertEqualsDouble(summary.maximumMillimeters, 5.0, 1e-12)
    assertEquals(
      MotionMetrics.pointDisplacements(
        translated,
        reference,
        Vector.empty
      ),
      Left(MotionError.EmptyDisplacementSupport)
    )
    assertEquals(
      HeadRadius.create(0.0),
      Left(MotionError.InvalidHeadRadius(0.0))
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

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def motion[A](value: Either[MotionError, A]): A =
    value.fold(error => fail(error.message), identity)
