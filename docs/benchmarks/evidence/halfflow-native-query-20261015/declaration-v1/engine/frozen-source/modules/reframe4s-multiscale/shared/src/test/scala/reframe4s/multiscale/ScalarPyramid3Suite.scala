package reframe4s.multiscale

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

final class ScalarPyramid3Suite extends munit.FunSuite:
  test("preserves constants across an awkward affine grid tower"):
    val frame = geometry(Frame.named[D3]("constant-pyramid"))
    val affine =
      geometry(
        Affine.fromRowMajor[D3](
          Vector(
            2.0, 0.5, 0.0, 10.0,
            0.0, 3.0, 0.25, -4.0,
            0.1, 0.0, 4.0, 7.0,
            0.0, 0.0, 0.0, 1.0
          )
        )
      )
    val grid =
      geometry(
        Grid.forFrame(frame)(Vector(9, 10, 8), affine)
      )
    val source =
      image(
        Sampled.continuous(
          grid,
          NonSpatialAxes.empty,
          NDArray.fill[Double, Rank[3]](
            Shape(9, 10, 8),
            3.25
          )
        )
      )
    val tower =
      multiscale(
        GridTower.build(
          grid,
          schedule(
            sigmaPhysical = Vector(2.0, 1.5, 1.0)
          )
        )
      )
    val workspace = ScalarPyramidWorkspace.create
    val pyramid =
      multiscale(ScalarPyramid3.build(source, tower, workspace))

    assertEquals(pyramid.size, 2)
    assertEquals(pyramid.levels.head.image.grid.shape, Vector(5, 6, 5))
    pyramid.levels.foreach { level =>
      level.image.data.iterator.foreach(value =>
        assertEqualsDouble(value, 3.25, 1e-12)
      )
    }
    assert(pyramid.levels.last.image.data eq source.data)

    assert(workspace.acquire())
    assertEquals(
      ScalarPyramid3.build(source, tower, workspace),
      Left(MultiscaleError.ScalarPyramidWorkspaceInUse)
    )
    workspace.release()

  test("physical sigma scales with affine column length"):
    val shape = Vector(9, 7, 5)
    val data =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) =>
          if i == 4 && j == 2 && k == 2 then 1.0 else 0.0
      }
    val unit = PyramidFixture(
      "unit-spacing",
      shape,
      Vector(1.0, 1.0, 1.0),
      Vector(1.0, 0.0, 0.0),
      data
    )
    val doubled = PyramidFixture(
      "double-spacing",
      shape,
      Vector(2.0, 2.0, 2.0),
      Vector(2.0, 0.0, 0.0),
      data
    )

    val unitPyramid =
      multiscale(
        ScalarPyramid3.build(
          unit.source,
          unit.tower,
          ScalarPyramidWorkspace.create
        )
      )
    val doubledPyramid =
      multiscale(
        ScalarPyramid3.build(
          doubled.source,
          doubled.tower,
          ScalarPyramidWorkspace.create
        )
      )

    assertEquals(
      unitPyramid.levels.head.image.data.iterator.toVector,
      doubledPyramid.levels.head.image.data.iterator.toVector
    )
    assert(
      unitPyramid.levels.head.image.data.iterator.exists(value =>
        value > 0.0 && value < 1.0
      )
    )

  private final class PyramidFixture(
      name: String,
      shape: Vector[Int],
      spacing: Vector[Double],
      sigma: Vector[Double],
      data: NDArray[Double, Rank[3]]
  ):
    val frame = geometry(Frame.named[D3](name))
    private val affine =
      geometry(
        Affine.fromOriginSpacingDirection[D3](
          Vector(0.0, 0.0, 0.0),
          spacing,
          Vector(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
          )
        )
      )
    val grid =
      geometry(Grid.forFrame(frame)(shape, affine))
    val source =
      image(
        Sampled.continuous(
          grid,
          NonSpatialAxes.empty,
          data
        )
      )
    val tower =
      multiscale(
        GridTower.build(
          grid,
          schedule(sigma)
        )
      )

  private def schedule(
      sigmaPhysical: Vector[Double]
  ): ScaleSchedule[D3, String] =
    val coarse =
      multiscale(
        ScaleSpec.create[D3](
          Vector(2, 2, 2),
          sigmaPhysical
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
          ScaleLevel(coarse, "coarse"),
          ScaleLevel(native, "native")
        )
      )
    )

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def image[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def multiscale[A](value: Either[MultiscaleError, A]): A =
    value.fold(error => fail(error.message), identity)
