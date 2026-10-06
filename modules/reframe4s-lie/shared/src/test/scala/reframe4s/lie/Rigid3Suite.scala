package reframe4s.lie

import reframe4s.core.MapError
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.FrameId
import image4s.geometry.FrameRegistry
import image4s.geometry.GeometryError
import image4s.geometry.Point

final class Rigid3Suite extends munit.FunSuite:
  test("checked affine import rejects scale, shear, and reflection"):
    val source = geometry(Frame.named[D3]("source"))
    val target = geometry(Frame.named[D3]("target"))
    val scale = affine(
      Vector(
        2.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      )
    )
    val shear = affine(
      Vector(
        1.0, 0.2, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      )
    )
    val reflection = affine(
      Vector(
        -1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      )
    )

    assert(Rigid3.fromAffine(source, target)(scale).isLeft)
    assert(Rigid3.fromAffine(source, target)(shear).isLeft)
    Rigid3.fromAffine(source, target)(reflection) match
      case Left(RigidError.ImproperRotation(determinant, _)) =>
        assertEqualsDouble(determinant, -1.0, 1e-12)
      case other =>
        fail(s"expected improper rotation, got $other")

  test("checked row-major import rejects malformed homogeneous matrices"):
    val source = geometry(Frame.named[D3]("source"))
    val target = geometry(Frame.named[D3]("target"))
    val malformedBottomRow =
      Vector(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.25, 1.0
      )
    val nonFinite =
      Vector(
        1.0, 0.0, 0.0, Double.NaN,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      )

    assert(Rigid3.fromRowMajor(source, target, malformedBottomRow).isLeft)
    assert(Rigid3.fromRowMajor(source, target, nonFinite).isLeft)

  test("exp and log round-trip translations and rotations"):
    val frame = geometry(Frame.named[D3]("frame"))
    val cases =
      Vector(
        twist(frame)(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        twist(frame)(1.2, -3.4, 0.7, 1e-10, -2e-10, 3e-10),
        twist(frame)(2.0, -1.0, 4.0, 0.2, -0.3, 0.4),
        twist(frame)(0.5, 0.25, -0.75, math.Pi - 1e-7, 0.0, 0.0)
      )

    cases.foreach { value =>
      val recovered = rigid(Rigid3.exp(value).flatMap(Rigid3.log(_)))
      assertVectorClose(recovered.linear, value.linear, 2e-9)
      assertVectorClose(recovered.angular, value.angular, 2e-9)
    }

  test("target retraction, relative log, and interpolation agree"):
    val moving = geometry(Frame.named[D3]("moving"))
    val fixed = geometry(Frame.named[D3]("fixed"))
    val base = rigid(Rigid3.translation(moving, fixed)(3.0, -2.0, 1.0))
    val increment =
      twist(fixed)(0.4, -0.2, 0.1, 0.0, 0.0, math.toRadians(8.0))
    val next = rigid(base.retractTarget(increment))
    val recovered = rigid(base.targetDeltaTo(next))
    val midpoint = rigid(base.interpolateTarget(next, 0.5))
    val expectedMidpoint =
      rigid(increment.scaled(0.5).flatMap(base.retractTarget))

    assertVectorClose(recovered.components, increment.components, 1e-10)
    assertMatrixClose(
      midpoint.operator.rowMajor,
      expectedMidpoint.operator.rowMajor,
      1e-11
    )

  test("interpolation follows the short group path across Euler wrapping"):
    val frame = geometry(Frame.named[D3]("frame"))
    val first =
      rigid(
        Rigid3.exp(
          twist(frame)(
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            math.toRadians(179.0)
          )
        )
      )
    val second =
      rigid(
        Rigid3.exp(
          twist(frame)(
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            math.toRadians(-179.0)
          )
        )
      )
    val midpoint = rigid(first.interpolateTarget(second, 0.5))
    val angle = rotationAngle(midpoint.operator.rowMajor)

    assertEqualsDouble(math.abs(angle), math.Pi, 1e-8)

  test("adjoint matches rigid conjugation"):
    val first = geometry(Frame.named[D3]("first"))
    val second = geometry(Frame.named[D3]("second"))
    val transform =
      rigid(
        Rigid3
          .exp(
            twist(first)(0.5, -0.7, 1.1, 0.2, -0.1, 0.3)
          )
          .flatMap(self =>
            Rigid3.fromAffine[first.type, second.type](
              first,
              second
            )(self.operator)
          )
      )
    val value: Twist6[first.type] =
      twist(first)(1.0, 2.0, -1.0, 0.05, -0.1, 0.2)
    val transported = rigid(transform.adjoint(value))
    val left = rigid(Rigid3.exp(transported))
    val right =
      rigid(
        transform.inverse
          .andThenRigid(rigid(Rigid3.exp(value)))
          .flatMap(_.andThenRigid(transform))
      )

    assertMatrixClose(
      left.operator.rowMajor,
      right.operator.rowMajor,
      1e-10
    )

  test("rigid inverse preserves typed endpoints and point values"):
    val source = geometry(Frame.named[D3]("source"))
    val target = geometry(Frame.named[D3]("target"))
    val map =
      rigid(
        Rigid3
          .exp(twist(source)(2.0, -1.0, 0.5, 0.1, 0.2, -0.15))
          .flatMap(self =>
            Rigid3.fromAffine[source.type, target.type](
              source,
              target
            )(self.operator)
          )
      )
    val point = geometry(Point.in[D3](source)(3.0, -2.0, 5.0))
    val mapped = mapValue(map(point))
    val roundTrip = mapValue(map.inverse(mapped))

    assertVectorClose(roundTrip.coordinates, point.coordinates, 1e-10)

  test("target retraction rebinds and preserves its declared live owner"):
    val frameId = geometry(FrameId.parse("target"))
    val seed =
      geometry(Frame.persistentNamed[D3](frameId, "target"))
    val record = geometry(seed.record.left.map(image4s.geometry.GeometryError.fromSpatial))
    val firstRegistry = FrameRegistry.empty
    val secondRegistry = FrameRegistry.empty
    val source: Frame[D3] = geometry(Frame.named[D3]("source"))
    val target: Frame[D3] =
      geometry(Frame.restore[D3](record, firstRegistry)).frame
    val impostor: Frame[D3] =
      geometry(Frame.restore[D3](record, secondRegistry)).frame
    val transform =
      rigid(
        Rigid3.translationBetween[Frame[D3], Frame[D3]](
          source,
          target
        )(0.0, 0.0, 0.0)
      )
    val increment: Twist6[Frame[D3]] =
      rigid(
        Twist6.createFor[Frame[D3]](impostor)(
          1.0,
          0.0,
          0.0,
          0.0,
          0.0,
          0.0
        )
      )
    val updated = rigid(transform.retractTarget(increment))

    assertEquals(target.persistentKey, impostor.persistentKey)
    assert(!(target eq impostor))
    assert(updated.target eq target)
    assert(!(updated.target eq impostor))
    assertEqualsDouble(updated.operator.rowMajor(3), 1.0, 1e-12)

  private def twist(
      frame: Frame[D3]
  )(
      vx: Double,
      vy: Double,
      vz: Double,
      wx: Double,
      wy: Double,
      wz: Double
  ): Twist6[frame.type] =
    rigid(Twist6.create(frame)(vx, vy, vz, wx, wy, wz))

  private def affine(values: Vector[Double]): Affine[D3] =
    geometry(Affine.fromRowMajor[D3](values))

  private def rotationAngle(rowMajor: Vector[Double]): Double =
    val trace = rowMajor(0) + rowMajor(5) + rowMajor(10)
    math.acos(math.max(-1.0, math.min(1.0, (trace - 1.0) / 2.0)))

  private def assertVectorClose(
      actual: Vector[Double],
      expected: Vector[Double],
      tolerance: Double
  ): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) =>
      assertEqualsDouble(left, right, tolerance)
    }

  private def assertMatrixClose(
      actual: Vector[Double],
      expected: Vector[Double],
      tolerance: Double
  ): Unit =
    assertVectorClose(actual, expected, tolerance)

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def mapValue[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)
