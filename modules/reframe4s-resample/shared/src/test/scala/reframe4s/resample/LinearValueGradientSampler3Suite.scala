package reframe4s.resample

import image4s.BoundaryPolicy
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.Validity
import image4s.geometry.Affine
import image4s.geometry.ContinuousIndex
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid
import image4s.geometry.Point
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import ravel.Slice

import scala.compiletime.testing.typeCheckErrors

final class LinearValueGradientSampler3Suite extends munit.FunSuite:
  test("world gradients are exact on anisotropic oblique reflected and sheared grids"):
    val affines = Vector(
      Vector(
        2.0, 0.0, 0.0, 7.0,
        0.0, 3.0, 0.0, -5.0,
        0.0, 0.0, 4.0, 11.0,
        0.0, 0.0, 0.0, 1.0
      ),
      Vector(
        0.0, -2.0, 0.0, 3.0,
        1.5, 0.0, 0.0, -4.0,
        0.0, 0.0, 2.5, 8.0,
        0.0, 0.0, 0.0, 1.0
      ),
      Vector(
        -1.7, 0.0, 0.0, 12.0,
        0.0, 2.2, 0.0, -3.0,
        0.0, 0.0, 3.1, 5.0,
        0.0, 0.0, 0.0, 1.0
      ),
      Vector(
        1.8, 0.35, -0.12, 4.0,
        0.2, 2.4, 0.28, -9.0,
        0.0, -0.15, 2.9, 6.0,
        0.0, 0.0, 0.0, 1.0
      )
    )
    val expectedGradient = Vector(0.7, -1.1, 0.35)

    affines.zipWithIndex.foreach { case (rowMajor, caseIndex) =>
      val frame = geometry(Frame.named[D3](s"gradient-$caseIndex"))
      val affine = geometry(Affine.fromRowMajor[D3](rowMajor))
      val grid = geometry(Grid.in(frame)(Vector(5, 6, 7), affine))
      val data = NDArray.tabulate[Double](5, 6, 7) { (i, j, k) =>
        physicalValue(rowMajor, i.toDouble, j.toDouble, k.toDouble)
      }
      val image = sampled(
        Sampled.continuous(grid, NonSpatialAxes.empty, data)
      )
      val sampler = resampling(LinearValueGradientSampler3.compile(image))
      val index = geometry(ContinuousIndex.fromVector[D3](Vector(1.37, 2.19, 3.41)))
      val point = geometry(grid.pointAt(index))
      val output = ScalarValueGradient3.create
      val result = resampling(sampler.at(point, output))
      val pointwise = sampled(
        SampledInterpolator.at(
          image,
          point,
          Vector.empty,
          Interpolation.Linear,
          BoundaryPolicy.Reject
        )
      )

      assert(result eq output)
      assertEqualsDouble(result.value, pointwise.value, 2e-13)
      assertEquals(result.validity, pointwise.validity)
      assertEqualsDouble(result.gradientX, expectedGradient(0), 2e-12)
      assertEqualsDouble(result.gradientY, expectedGradient(1), 2e-12)
      assertEqualsDouble(result.gradientZ, expectedGradient(2), 2e-12)

      val coordinates = point.coordinates
      val analytic = Vector(result.gradientX, result.gradientY, result.gradientZ)
      var axis = 0
      while axis < 3 do
        val epsilon = 1e-5
        val plusCoordinates = coordinates.updated(axis, coordinates(axis) + epsilon)
        val minusCoordinates = coordinates.updated(axis, coordinates(axis) - epsilon)
        val plus = geometry(Point.fromVector(frame, plusCoordinates))
        val minus = geometry(Point.fromVector(frame, minusCoordinates))
        val numeric =
          (pointwiseValue(image, plus) - pointwiseValue(image, minus)) /
            (2.0 * epsilon)
        assertEqualsDouble(analytic(axis), numeric, 3e-9)
        axis += 1
    }

  test("integer knots use the right cell except for a left derivative at the final knot"):
    val frame = geometry(Frame.named[D3]("knot-policy"))
    val grid = geometry(
      Grid.in(frame)(Vector(5, 3, 3), Affine.identity[D3])
    )
    val data = NDArray.tabulate[Double](5, 3, 3) { (i, j, k) =>
      i.toDouble * i.toDouble + 2.0 * j.toDouble - k.toDouble
    }
    val image = sampled(
      Sampled.continuous(grid, NonSpatialAxes.empty, data)
    )
    val sampler = resampling(LinearValueGradientSampler3.compile(image))
    val interior = resampling(
      sampler.at(
        geometry(Point.in[D3](frame)(2.0, 1.0, 1.0)),
        ScalarValueGradient3.create
      )
    )
    val finalKnot = resampling(
      sampler.at(
        geometry(Point.in[D3](frame)(4.0, 1.0, 1.0)),
        ScalarValueGradient3.create
      )
    )

    assertEqualsDouble(interior.gradientX, 5.0, 0.0)
    assertEqualsDouble(finalKnot.gradientX, 7.0, 0.0)
    assertEquals(interior.validity, Validity.Full)
    assertEquals(finalKnot.validity, Validity.Full)

  test("strided views and invalid taps match existing pointwise value semantics"):
    val frame = geometry(Frame.named[D3]("strided"))
    val canonical = NDArray.tabulate[Double](10, 12, 14) { (i, j, k) =>
      100.0 * i.toDouble + 10.0 * j.toDouble + k.toDouble
    }
    val view = canonical
      .slice(0, Slice(0, 10, 2))
      .slice(1, Slice(0, 12, 2))
      .slice(2, Slice(0, 14, 2))
      .reverse(1)
    val grid = geometry(
      Grid.in(frame)(Vector(5, 6, 7), Affine.identity[D3])
    )
    val image = sampled(
      Sampled.continuous(grid, NonSpatialAxes.empty, view)
    )
    val sampler = resampling(LinearValueGradientSampler3.compile(image))
    val inside = geometry(Point.in[D3](frame)(1.2, 2.4, 3.1))
    val fused = resampling(
      sampler.at(inside, ScalarValueGradient3.create)
    )
    val pointwise = sampled(
      SampledInterpolator.at(
        image,
        inside,
        Vector.empty,
        Interpolation.Linear,
        BoundaryPolicy.Reject
      )
    )

    assert(!view.isCanonicalLayout)
    assertEqualsDouble(fused.value, pointwise.value, 1e-12)
    assertEquals(fused.validity, pointwise.validity)

    val outside = geometry(Point.in[D3](frame)(-0.25, 2.0, 2.0))
    val constantFused = resampling(
      sampler.at(
        outside,
        ScalarValueGradient3.create,
        BoundaryPolicy.Constant(-17.0)
      )
    )
    val constantPointwise = sampled(
      SampledInterpolator.at(
        image,
        outside,
        Vector.empty,
        Interpolation.Linear,
        BoundaryPolicy.Constant(-17.0)
      )
    )
    assertEqualsDouble(constantFused.value, constantPointwise.value, 1e-12)
    assertEquals(constantFused.validity, constantPointwise.validity)

    val pointwiseReject = SampledInterpolator.at(
      image,
      outside,
      Vector.empty,
      Interpolation.Linear,
      BoundaryPolicy.Reject
    )
    sampler.at(
      outside,
      ScalarValueGradient3.create,
      BoundaryPolicy.Reject
    ) match
      case Left(ResamplingError.Image(fusedError)) =>
        pointwiseReject match
          case Left(pointwiseError) => assertEquals(fusedError, pointwiseError)
          case Right(value) => fail(s"pointwise sampler unexpectedly returned $value")
      case other => fail(s"expected outside-source failure, got $other")

  test("derivative support requires at least two samples per spatial axis"):
    val frame = geometry(Frame.named[D3]("thin"))
    val grid = geometry(
      Grid.in(frame)(Vector(4, 1, 3), Affine.identity[D3])
    )
    val image = sampled(
      Sampled.continuous(
        grid,
        NonSpatialAxes.empty,
        NDArray.zeros[Double, Rank[3]](Shape(4, 1, 3))
      )
    )
    assertEquals(
      LinearValueGradientSampler3.compile(image),
      Left(ResamplingError.DerivativeExtentTooSmall(1, 1))
    )

  test("categorical rank and owner misuse do not compile"):
    val categorical = typeCheckErrors(
      """
import image4s.*
import image4s.geometry.*
import ravel.*
import reframe4s.resample.*
def invalid[
    F <: Frame[D3],
    S <: SampleSpace[F, D3]
](image: Sampled[S, Int, Categorical, Rank[3]]) =
  LinearValueGradientSampler3.compile(image)
"""
    )
    val rank = typeCheckErrors(
      """
import image4s.*
import image4s.geometry.*
import ravel.*
import reframe4s.resample.*
def invalid[
    F <: Frame[D3],
    S <: SampleSpace[F, D3]
](image: ContinuousImage[S, Double, Rank[4]]) =
  LinearValueGradientSampler3.compile(image)
"""
    )
    val owner = typeCheckErrors(
      """
import image4s.*
import image4s.geometry.*
import ravel.*
import reframe4s.resample.*
def invalid[
    F <: Frame[D3],
    G <: Frame[D3],
    S <: SampleSpace[F, D3]
](
    sampler: LinearValueGradientSampler3[F, S],
    point: Point[G, D3]
) = sampler.at(point, ScalarValueGradient3.create)
"""
    )

    assert(categorical.nonEmpty)
    assert(rank.nonEmpty)
    assert(owner.nonEmpty)

  private def physicalValue(
      affine: Vector[Double],
      i: Double,
      j: Double,
      k: Double
  ): Double =
    val x = affine(0) * i + affine(1) * j + affine(2) * k + affine(3)
    val y = affine(4) * i + affine(5) * j + affine(6) * k + affine(7)
    val z = affine(8) * i + affine(9) * j + affine(10) * k + affine(11)
    0.7 * x - 1.1 * y + 0.35 * z + 2.0

  private def pointwiseValue[
      F <: Frame[D3],
      S <: image4s.SampleSpace[F, D3]
  ](
      image: image4s.ContinuousImage[S, Double, Rank[3]],
      point: Point[F, D3]
  ): Double =
    sampled(
      SampledInterpolator.at(
        image,
        point,
        Vector.empty,
        Interpolation.Linear,
        BoundaryPolicy.Reject
      )
    ).value

  private def geometry[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def sampled[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def resampling[A](result: Either[ResamplingError, A]): A =
    result.fold(error => fail(error.message), identity)
