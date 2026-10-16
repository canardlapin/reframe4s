package reframe4s.flow

import image4s.Axis
import image4s.AxisKind
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import reframe4s.core.MapError
import reframe4s.field.FieldError
import reframe4s.field.Velocity
import image4s.geometry.Affine
import image4s.geometry.D2
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.Point

final class ExplicitEulerSuite extends munit.FunSuite:
  test("explicit Euler keeps a zero velocity endpoint at identity"):
    val frame = geometryRight(Frame.named[D2]("euler-zero"))
    val grid =
      geometryRight(Grid.in(frame)(Vector(3, 3), Affine.identity[D2]))
    val velocity = constantVelocity(grid, 0.0, 0.0)
    val settings = flowRight(IntegrationSettings.create(steps = 4))
    val result =
      flowRight(ExplicitEuler.compile(velocity, settings).run())
    val point = geometryRight(Point.in[D2](frame)(1.25, 0.75))
    val mapped = mapRight(result.endpoint(point))

    assertEqualsDouble(mapped.coordinates(0), 1.25, 1e-12)
    assertEqualsDouble(mapped.coordinates(1), 0.75, 1e-12)
    assertEqualsDouble(result.diagnostics.maximumStepLength, 0.0, 0.0)
    assertEquals(result.diagnostics.evaluatedPoints, 9L)

  test("one explicit Euler step integrates a constant physical translation"):
    val frame = geometryRight(Frame.named[D2]("euler-translation"))
    val grid =
      geometryRight(Grid.in(frame)(Vector(3, 3), Affine.identity[D2]))
    val velocity = constantVelocity(grid, 0.25, -0.5)
    val settings =
      flowRight(IntegrationSettings.create(steps = 1, duration = 2.0))
    val result =
      flowRight(ExplicitEuler.compile(velocity, settings).run())
    val point = geometryRight(Point.in[D2](frame)(1.0, 1.0))
    val mapped = mapRight(result.endpoint(point))

    assertEqualsDouble(mapped.coordinates(0), 1.5, 1e-12)
    assertEqualsDouble(mapped.coordinates(1), 0.0, 1e-12)
    assertEqualsDouble(
      result.diagnostics.maximumStepLength,
      math.sqrt(1.25),
      1e-12
    )

  test("explicit Euler classifies velocity support loss"):
    val frame = geometryRight(Frame.named[D2]("euler-support"))
    val grid =
      geometryRight(Grid.in(frame)(Vector(3, 3), Affine.identity[D2]))
    val velocity = constantVelocity(grid, 2.0, 0.0)
    val settings =
      flowRight(IntegrationSettings.create(steps = 2, duration = 1.0))

    ExplicitEuler.compile(velocity, settings).run() match
      case Left(FlowError.InsufficientSupport(index, step)) =>
        assertEquals(index, Vector(2, 0))
        assertEquals(step, 1)
      case other =>
        fail(s"expected typed support failure, got $other")

  private def constantVelocity[F <: Frame[D2]](
      grid: Grid[F, D2],
      x: Double,
      y: Double
  ): Velocity[F, D2, Rank[3]] =
    val axis = imageRight(Axis.create("component", 2, AxisKind.Direction))
    val axes = imageRight(NonSpatialAxes.from(Vector(axis)))
    val values =
      Vector.tabulate(grid.shape.product * 2) { index =>
        if index % 2 == 0 then x else y
      }
    val samples =
      imageRight(
        Sampled.continuous(
          grid,
          axes,
          NDArray.fromSeq(
            Shape(grid.shape(0), grid.shape(1), 2),
            values
          )
        )
      )
    fieldRight(Velocity.from(samples))

  private def geometryRight[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def imageRight[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def fieldRight[A](value: Either[FieldError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def flowRight[A](value: Either[FlowError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def mapRight[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)
