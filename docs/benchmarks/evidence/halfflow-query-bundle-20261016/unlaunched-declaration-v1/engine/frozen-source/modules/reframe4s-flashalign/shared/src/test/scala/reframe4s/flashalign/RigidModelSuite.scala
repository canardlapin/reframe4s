package reframe4s.flashalign

import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.Point
import ravel.DType.given
import ravel.NDArray
import reframe4s.core.MapError
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.lie.Twist6
import reframe4s.resample.LinearValueGradientSampler3
import reframe4s.resample.ResamplingError
import reframe4s.resample.ScalarValueGradient3

import scala.compiletime.testing.typeCheckErrors

final class RigidModelSuite extends munit.FunSuite:
  test("target-frame updates recover known translation and pivot rotation"):
    val moving = geometry(Frame.named[D3]("rigid-known-moving"))
    val fixed = geometry(Frame.named[D3]("rigid-known-fixed"))
    val pivot = geometry(Point.in[D3](fixed)(10.0, 20.0, 0.0))
    val model = rigidModel(
      RigidModel3.atPivot[moving.type, fixed.type](moving, fixed, pivot)
    )
    val identity = rigid(
      Rigid3.fromAffine[moving.type, fixed.type](moving, fixed)(
        Affine.identity[D3]
      )
    )

    val translated = rigidModel(
      model.propose(identity, Array(10.0, -2.0, 3.0, 0.0, 0.0, 0.0))
    )
    val translatedOrigin = mapped(
      translated(geometry(Point.in[D3](moving)(0.0, 0.0, 0.0)))
    )
    assertVectorClose(
      translatedOrigin.coordinates,
      Vector(10.0, -2.0, 3.0),
      1e-12
    )

    val rotated = rigidModel(
      model.propose(
        identity,
        Array(0.0, 0.0, 0.0, 0.0, 0.0, math.Pi / 2.0)
      )
    )
    val rotatedPoint = mapped(
      rotated(geometry(Point.in[D3](moving)(11.0, 20.0, 0.0)))
    )
    assertVectorClose(rotatedPoint.coordinates, Vector(10.0, 21.0, 0.0), 1e-10)

  test("pivot conversion is the canonical origin-centred target twist"):
    val moving = geometry(Frame.named[D3]("twist-moving"))
    val fixed = geometry(Frame.named[D3]("twist-fixed"))
    val model = rigidModel(
      RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(
        2.0,
        -3.0,
        5.0
      )
    )
    val twist = rigidModel(
      model.pivotCenteredTwist(
        Array(0.4, -0.2, 0.7, 0.1, 0.3, -0.25)
      )
    )

    assert(twist.frame eq fixed)
    assertVectorClose(twist.angular, Vector(0.1, 0.3, -0.25), 0.0)
    assertVectorClose(
      twist.linear,
      Vector(-0.35, 0.8, 1.6),
      1e-15
    )

  test("analytic fixed-world intensity columns pass parameter finite differences"):
    val moving = geometry(Frame.named[D3]("jacobian-moving"))
    val fixed = geometry(Frame.named[D3]("jacobian-fixed"))
    val model = rigidModel(
      RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(
        2.0,
        -1.0,
        3.0
      )
    )
    val fixedIncrement: Rigid3[fixed.type, fixed.type] = rigid(
      Twist6
        .create(fixed)(0.7, -0.4, 0.2, 0.08, -0.05, 0.11)
        .flatMap(Rigid3.exp)
    )
    val current = rigid(
      Rigid3.fromAffine[moving.type, fixed.type](moving, fixed)(
        fixedIncrement.operator
      )
    )
    val movingPoint = geometry(Point.in[D3](moving)(4.0, 5.0, 6.0))
    val fixedPoint = mapped(current(movingPoint))
    val gradient = Vector(0.7, -1.1, 0.35)
    val analytic = new Array[Double](6)
    rigidModel(
      model.writeIntensityJacobianAtFixedWorld(
        fixedPoint.coordinates(0),
        fixedPoint.coordinates(1),
        fixedPoint.coordinates(2),
        gradient(0),
        gradient(1),
        gradient(2),
        analytic,
        0
      )
    )

    var parameter = 0
    while parameter < 6 do
      val epsilon = if parameter < 3 then 1e-6 else 1e-7
      val plusStep = Array.fill(6)(0.0)
      val minusStep = Array.fill(6)(0.0)
      plusStep(parameter) = epsilon
      minusStep(parameter) = -epsilon
      val plus = mapped(rigidModel(model.propose(current, plusStep))(movingPoint))
      val minus = mapped(rigidModel(model.propose(current, minusStep))(movingPoint))
      val numeric =
        (linearFixedValue(plus.coordinates, gradient) -
          linearFixedValue(minus.coordinates, gradient)) /
          (2.0 * epsilon)
      assertEqualsDouble(analytic(parameter), numeric, 2e-8)
      parameter += 1

  test("axis permutations reflections and origin rebasing preserve physical columns"):
    val moving = geometry(Frame.named[D3]("header-moving"))
    val fixed = geometry(Frame.named[D3]("header-fixed"))
    val model = rigidModel(
      RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(
        1.0,
        2.0,
        0.5
      )
    )
    val point = geometry(Point.in[D3](fixed)(2.2, 3.1, 1.7))
    val headers = Vector(
      Vector(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      ),
      Vector(
        0.0, 1.0, 0.0, 0.0,
        1.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      ),
      Vector(
        -1.0, 0.0, 0.0, 5.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      )
    )
    val gradient = Vector(0.7, -1.1, 0.35)
    val results = headers.map { rowMajor =>
      val affine = geometry(Affine.fromRowMajor[D3](rowMajor))
      val grid = geometry(Grid.in(fixed)(Vector(6, 6, 6), affine))
      val data = NDArray.tabulate[Double](6, 6, 6) { (i, j, k) =>
        val x = rowMajor(0) * i + rowMajor(1) * j + rowMajor(2) * k + rowMajor(3)
        val y = rowMajor(4) * i + rowMajor(5) * j + rowMajor(6) * k + rowMajor(7)
        val z = rowMajor(8) * i + rowMajor(9) * j + rowMajor(10) * k + rowMajor(11)
        linearFixedValue(Vector(x, y, z), gradient)
      }
      val image = sampled(
        Sampled.continuous(grid, NonSpatialAxes.empty, data)
      )
      val sampler = resampling(LinearValueGradientSampler3.compile(image))
      val sample = resampling(
        sampler.at(point, ScalarValueGradient3.create)
      )
      val columns = new Array[Double](6)
      rigidModel(
        model.writeIntensityJacobianAtFixedWorld(
          point.coordinates(0),
          point.coordinates(1),
          point.coordinates(2),
          sample.gradientX,
          sample.gradientY,
          sample.gradientZ,
          columns,
          0
        )
      )
      sample.value -> columns.toVector
    }

    results.tail.foreach { result =>
      assertEqualsDouble(result._1, results.head._1, 2e-12)
      assertVectorClose(result._2, results.head._2, 3e-12)
    }

  test("proposed transforms retain direction and inverse landmark behavior"):
    val moving = geometry(Frame.named[D3]("inverse-moving"))
    val fixed = geometry(Frame.named[D3]("inverse-fixed"))
    val model = rigidModel(
      RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(
        4.0,
        -2.0,
        1.0
      )
    )
    val initial = rigid(
      Rigid3.fromAffine[moving.type, fixed.type](moving, fixed)(
        Affine.identity[D3]
      )
    )
    val candidate = rigidModel(
      model.propose(
        initial,
        Array(0.3, -0.6, 1.1, 0.04, -0.08, 0.12)
      )
    )
    val landmark = geometry(Point.in[D3](moving)(7.0, 1.0, -3.0))
    val fixedLandmark = mapped(candidate(landmark))
    val roundTrip = mapped(candidate.inverse(fixedLandmark))

    assert(candidate.source.eq(moving))
    assert(candidate.target.eq(fixed))
    assertVectorClose(roundTrip.coordinates, landmark.coordinates, 2e-12)

  test("wrong static direction and wrong erased runtime owners fail"):
    val wrongDirection = typeCheckErrors(
      """
import image4s.geometry.*
import reframe4s.flashalign.*
import reframe4s.lie.*
val moving = Frame.named[D3]("m").toOption.get
val fixed = Frame.named[D3]("f").toOption.get
val model = RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(0.0, 0.0, 0.0).toOption.get
val fixedToMoving = Rigid3.fromAffine[fixed.type, moving.type](fixed, moving)(Affine.identity[D3]).toOption.get
model.propose(fixedToMoving, Array.fill(6)(0.0))
"""
    )
    assert(wrongDirection.nonEmpty)

    val modelMoving: Frame[D3] = geometry(Frame.named[D3]("runtime-model-moving"))
    val modelFixed: Frame[D3] = geometry(Frame.named[D3]("runtime-model-fixed"))
    val otherMoving: Frame[D3] = geometry(Frame.named[D3]("runtime-other-moving"))
    val otherFixed: Frame[D3] = geometry(Frame.named[D3]("runtime-other-fixed"))
    val model = rigidModel(
      RigidModel3.atFixedWorld[Frame[D3], Frame[D3]](
        modelMoving,
        modelFixed
      )(0.0, 0.0, 0.0)
    )
    val wrongRuntime = rigid(
      Rigid3.translationBetween[Frame[D3], Frame[D3]](
        otherMoving,
        otherFixed
      )(0.0, 0.0, 0.0)
    )
    model.propose(wrongRuntime, Array.fill(6)(0.0)) match
      case Left(
            RigidModelError.FrameOwnerMismatch(
              RigidModelEndpoint.Moving,
              _,
              _
            )
          ) => ()
      case other => fail(s"expected runtime moving-owner failure, got $other")

  private def linearFixedValue(
      coordinates: Vector[Double],
      gradient: Vector[Double]
  ): Double =
    4.0 + coordinates.zip(gradient).map(_ * _).sum

  private def assertVectorClose(
      actual: Vector[Double],
      expected: Vector[Double],
      tolerance: Double
  ): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) =>
      assertEqualsDouble(left, right, tolerance)
    }

  private def geometry[A](value: Either[GeometryError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def rigid[A](value: Either[RigidError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def rigidModel[A](value: Either[RigidModelError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def mapped[A](value: Either[MapError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def sampled[A](value: Either[image4s.ImageError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def resampling[A](value: Either[ResamplingError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)
