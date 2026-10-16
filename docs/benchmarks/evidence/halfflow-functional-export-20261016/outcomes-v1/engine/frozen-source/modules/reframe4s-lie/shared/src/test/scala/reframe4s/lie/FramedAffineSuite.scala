package reframe4s.lie

import reframe4s.core.MapError
import image4s.geometry.Affine
import image4s.geometry.D2
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Point

final class FramedAffineSuite extends munit.FunSuite:
  test("framed affine wraps the authoritative operator without copying"):
    val source = right(Frame.named[D2]("source"))
    val target = right(Frame.named[D2]("target"))
    val operator = right(
      Affine.fromRowMajor[D2](
        Vector(
          2.0,
          0.0,
          4.0,
          0.0,
          3.0,
          -2.0,
          0.0,
          0.0,
          1.0
        )
      )
    )
    val map = FramedAffine.between(source, target)(operator)
    val point = right(Point.in[D2](source)(1.0, 2.0))
    val mapped = rightMap(map(point))

    assert(map.operator eq operator)
    assertEquals(mapped.coordinates, Vector(6.0, 4.0))

  test("exact affine inverse satisfies both pointwise inverse laws"):
    val source = right(Frame.named[D2]("source"))
    val target = right(Frame.named[D2]("target"))
    val map = right(
      FramedAffine.translation(source, target)(3.0, -4.0)
    )
    val point = right(Point.in[D2](source)(1.5, 2.5))
    val forward = rightMap(map(point))
    val roundTrip = rightMap(map.inverse(forward))

    assertEqualsDouble(roundTrip.coordinates(0), 1.5, 1e-12)
    assertEqualsDouble(roundTrip.coordinates(1), 2.5, 1e-12)

  test("noncommuting affine composition preserves declared order"):
    val first = right(Frame.named[D2]("first"))
    val middle = right(Frame.named[D2]("middle"))
    val last = right(Frame.named[D2]("last"))
    val translate = right(
      FramedAffine.translation(first, middle)(1.0, 0.0)
    )
    val scale = FramedAffine.between(middle, last)(
      right(
        Affine.fromRowMajor[D2](
          Vector(
            2.0,
            0.0,
            0.0,
            0.0,
            3.0,
            0.0,
            0.0,
            0.0,
            1.0
          )
        )
      )
    )
    val composed = translate.andThenIso(scale)
    val point = right(Point.in[D2](first)(2.0, 4.0))
    val result = rightMap(composed(point))

    assertEquals(result.coordinates, Vector(6.0, 12.0))

  test("affine jet is constant and equals the operator linear part"):
    val source = right(Frame.named[D2]("source"))
    val target = right(Frame.named[D2]("target"))
    val map = FramedAffine.between(source, target)(
      right(
        Affine.fromRowMajor[D2](
          Vector(
            2.0,
            1.0,
            3.0,
            -1.0,
            4.0,
            5.0,
            0.0,
            0.0,
            1.0
          )
        )
      )
    )
    val first = rightMap(
      map.jet1At(right(Point.in[D2](source)(0.0, 0.0)))
    )
    val second = rightMap(
      map.jet1At(right(Point.in[D2](source)(7.0, -2.0)))
    )

    assertEqualsDouble(first.differential(0, 0), 2.0, 0.0)
    assertEqualsDouble(first.differential(0, 1), 1.0, 0.0)
    assertEqualsDouble(first.differential(1, 0), -1.0, 0.0)
    assertEqualsDouble(first.differential(1, 1), 4.0, 0.0)
    assertEqualsDouble(second.differential(0, 0), 2.0, 0.0)

  private def right[A](value: Either[GeometryError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def rightMap[A](value: Either[MapError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)
