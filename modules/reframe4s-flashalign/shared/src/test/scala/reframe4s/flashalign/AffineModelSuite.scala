package reframe4s.flashalign

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Point
import reframe4s.core.MapError
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.lie.Twist6

import scala.compiletime.testing.typeCheckErrors

final class AffineModelSuite extends munit.FunSuite:
  test("all 12 declared affine columns pass fixed-world finite differences"):
    val moving = geometry(Frame.named[D3]("affine-jacobian-moving"))
    val fixed = geometry(Frame.named[D3]("affine-jacobian-fixed"))
    val model = affineModel(
      AffineModel3.atFixedWorld[moving.type, fixed.type](
        moving,
        fixed,
        permissiveConfig
      )(2.0, -1.0, 3.0)
    )
    val current = framed[moving.type, fixed.type](
      moving,
      fixed,
      Vector(
        1.08, 0.04, -0.02, 0.7,
        -0.03, 0.94, 0.05, -0.4,
        0.02, 0.01, 1.12, 0.2,
        0.0, 0.0, 0.0, 1.0
      )
    )
    val point = geometry(Point.in[D3](moving)(4.0, 5.0, 6.0))
    val fixedPoint = mapped(current(point))
    val gradient = Vector(0.7, -1.1, 0.35)
    val analytic = new Array[Double](12)
    affineModel(
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
    while parameter < model.parameterCount do
      val epsilon = if parameter < 3 then 1e-6 else 1e-7
      val plusStep = Array.fill(model.parameterCount)(0.0)
      val minusStep = Array.fill(model.parameterCount)(0.0)
      plusStep(parameter) = epsilon
      minusStep(parameter) = -epsilon
      val plus = mapped(affineModel(model.propose(current, plusStep))(point))
      val minus = mapped(affineModel(model.propose(current, minusStep))(point))
      val numeric =
        (linearFixedValue(plus.coordinates, gradient) -
          linearFixedValue(minus.coordinates, gradient)) /
          (2.0 * epsilon)
      assertEqualsDouble(analytic(parameter), numeric, 3e-8)
      parameter += 1

  test("pivoted local composition, scale, shear, and inverse retain exact direction"):
    val moving = geometry(Frame.named[D3]("affine-compose-moving"))
    val fixed = geometry(Frame.named[D3]("affine-compose-fixed"))
    val pivot = geometry(Point.in[D3](fixed)(3.0, -2.0, 1.0))
    val model = affineModel(
      AffineModel3.atPivot[moving.type, fixed.type](
        moving,
        fixed,
        pivot,
        permissiveConfig
      )
    )
    val current = framed[moving.type, fixed.type](
      moving,
      fixed,
      Vector(
        1.1, 0.0, 0.0, 0.4,
        0.0, 0.9, 0.0, -0.2,
        0.0, 0.0, 1.05, 0.7,
        0.0, 0.0, 0.0, 1.0
      )
    )
    val increment = Array(
      0.2, -0.3, 0.1,
      0.04, -0.03, 0.02,
      0.08, -0.06, 0.03,
      0.05, -0.02, 0.04
    )
    val candidate = affineModel(model.propose(current, increment))
    val point = geometry(Point.in[D3](moving)(5.0, 4.0, -1.0))
    val before = mapped(current(point)).coordinates
    val expected = applyDeclaredIncrement(before, pivot.coordinates, increment)
    val after = mapped(candidate(point))
    val roundTrip = mapped(candidate.inverse(after))

    assertVectorClose(after.coordinates, expected, 2e-12)
    assertVectorClose(roundTrip.coordinates, point.coordinates, 3e-12)
    assert(candidate.source.sameRuntimeOwnerAs(moving), "source owner")
    assert(candidate.target.sameRuntimeOwnerAs(fixed), "target owner")

  test("typed geometry checks reject strain, determinant, and singular-value violations"):
    val moving = geometry(Frame.named[D3]("affine-guard-moving"))
    val fixed = geometry(Frame.named[D3]("affine-guard-fixed"))
    val looseStrain = affineConfig(
      AffineModelConfig.create(
        minimumIncrementSingularValue = 0.01,
        maximumIncrementSingularValue = 4.0,
        minimumCandidateSingularValue = 0.1,
        maximumCandidateSingularValue = 2.0,
        maximumAbsoluteStrainIncrement = 3.0
      )
    )
    val model = affineModel(
      AffineModel3.atFixedWorld[moving.type, fixed.type](
        moving,
        fixed,
        looseStrain
      )(0.0, 0.0, 0.0)
    )
    val identity = FramedAffine.betweenFrames[moving.type, fixed.type, D3](
      moving,
      fixed
    )(Affine.identity[D3])

    val singular = Array.fill(12)(0.0)
    singular(6) = -1.0
    model.propose(identity, singular) match
      case Left(
            AffineModelError.NonPositiveDeterminant(
              AffineGeometryKind.Increment,
              _,
              _
            )
          ) => ()
      case other => fail(s"expected singular-increment rejection, got $other")

    val reflection = Array.fill(12)(0.0)
    reflection(6) = -2.0
    model.propose(identity, reflection) match
      case Left(
            AffineModelError.NonPositiveDeterminant(
              AffineGeometryKind.Increment,
              _,
              _
            )
          ) => ()
      case other => fail(s"expected reflection rejection, got $other")

    val strictStrain = affineModel(
      AffineModel3.atFixedWorld[moving.type, fixed.type](
        moving,
        fixed,
        affineConfig(
          AffineModelConfig.create(maximumAbsoluteStrainIncrement = 0.2)
        )
      )(0.0, 0.0, 0.0)
    )
    val excessiveStrain = Array.fill(12)(0.0)
    excessiveStrain(9) = 0.21
    strictStrain.propose(identity, excessiveStrain) match
      case Left(AffineModelError.StrainIncrementOutOfBounds(9, _, 0.2)) => ()
      case other => fail(s"expected strain-coordinate rejection, got $other")

    val nearLimit = framed[moving.type, fixed.type](
      moving,
      fixed,
      Vector(
        1.9, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      )
    )
    val overCandidateLimit = Array.fill(12)(0.0)
    overCandidateLimit(6) = 0.1
    model.propose(nearLimit, overCandidateLimit) match
      case Left(
            AffineModelError.SingularValueOutOfBounds(
              AffineGeometryKind.Candidate,
              _,
              _,
              _,
              _
            )
          ) => ()
      case other => fail(s"expected candidate-bound rejection, got $other")

  test("Green-strain prior supplies nonzero gradient and exact local curvature"):
    val moving = geometry(Frame.named[D3]("affine-prior-moving"))
    val fixed = geometry(Frame.named[D3]("affine-prior-fixed"))
    val model = affineModel(
      AffineModel3.atFixedWorld[moving.type, fixed.type](
        moving,
        fixed,
        permissiveConfig
      )(1.0, 2.0, -1.0)
    )
    val reference = FramedAffine.betweenFrames[moving.type, fixed.type, D3](
      moving,
      fixed
    )(Affine.identity[D3])
    val state = affineModel(
      model.propose(
        reference,
        Array(
          0.7, -0.2, 0.4,
          0.03, -0.02, 0.04,
          0.12, -0.08, 0.05,
          0.04, -0.03, 0.02
        )
      )
    )
    val prior = affineModel(AffineStrainPrior3.create(model, reference, 3.2))
    val terms = affineModel(prior.evaluate(state))

    assert(terms.value > 0.0)
    assert(terms.gradient.drop(6).exists(value => math.abs(value) > 1e-4))
    terms.gradient.take(6).foreach(value => assertEqualsDouble(value, 0.0, 0.0))

    var column = 0
    while column < model.parameterCount do
      val epsilon = 2e-6
      val plusStep = Array.fill(model.parameterCount)(0.0)
      val minusStep = Array.fill(model.parameterCount)(0.0)
      plusStep(column) = epsilon
      minusStep(column) = -epsilon
      val plusState = affineModel(model.propose(state, plusStep))
      val minusState = affineModel(model.propose(state, minusStep))
      val plusValue = affineModel(prior.value(plusState))
      val minusValue = affineModel(prior.value(minusState))
      val numericGradient = (plusValue - minusValue) / (2.0 * epsilon)
      assertEqualsDouble(terms.gradient(column), numericGradient, 2e-9)
      column += 1

    val curvatureEpsilon = 2e-4
    var row = 0
    while row < model.parameterCount do
      var column = row
      while column < model.parameterCount do
        val numericCurvature =
          if row == column then
            val plus = Array.fill(model.parameterCount)(0.0)
            val minus = Array.fill(model.parameterCount)(0.0)
            plus(row) = curvatureEpsilon
            minus(row) = -curvatureEpsilon
            (affineModel(prior.value(affineModel(model.propose(state, plus)))) -
              2.0 * terms.value +
              affineModel(prior.value(affineModel(model.propose(state, minus))))) /
              (curvatureEpsilon * curvatureEpsilon)
          else
            def corner(rowSign: Double, columnSign: Double): Double =
              val step = Array.fill(model.parameterCount)(0.0)
              step(row) = rowSign * curvatureEpsilon
              step(column) = columnSign * curvatureEpsilon
              affineModel(prior.value(affineModel(model.propose(state, step))))
            (corner(1.0, 1.0) - corner(1.0, -1.0) -
              corner(-1.0, 1.0) + corner(-1.0, -1.0)) /
              (4.0 * curvatureEpsilon * curvatureEpsilon)
        assertEqualsDouble(
          terms.curvatureUpper(PackedSymmetric.index(row, column)),
          numericCurvature,
          2e-7
        )
        column += 1
      row += 1

    val descent = Array.tabulate(12)(index => -1e-3 * terms.gradient(index))
    val descended = affineModel(model.propose(state, descent))
    assert(affineModel(prior.value(descended)) < terms.value)

  test("exact rigid relative motion has zero strain penalty"):
    val moving = geometry(Frame.named[D3]("affine-rigid-moving"))
    val fixed = geometry(Frame.named[D3]("affine-rigid-fixed"))
    val model = affineModel(
      AffineModel3.atFixedWorld[moving.type, fixed.type](
        moving,
        fixed,
        permissiveConfig
      )(0.0, 0.0, 0.0)
    )
    val reference = FramedAffine.betweenFrames[moving.type, fixed.type, D3](
      moving,
      fixed
    )(Affine.identity[D3])
    val rigidIncrement = rigid(
      Twist6
        .create(fixed)(0.5, -0.3, 0.2, 0.1, -0.08, 0.04)
        .flatMap(Rigid3.exp)
    )
    val rigidState = FramedAffine.betweenFrames[moving.type, fixed.type, D3](
      moving,
      fixed
    )(rigidIncrement.operator)
    val prior = affineModel(AffineStrainPrior3.create(model, reference, 2.0))
    val terms = affineModel(prior.evaluate(rigidState))

    assertEqualsDouble(terms.value, 0.0, 3e-30)
    assertVectorClose(terms.gradient.toVector, Vector.fill(12)(0.0), 3e-15)

  test("static direction and erased runtime owners remain checked"):
    val wrongDirection = typeCheckErrors(
      """
import image4s.geometry.*
import reframe4s.flashalign.*
import reframe4s.lie.*
val moving = Frame.named[D3]("m").toOption.get
val fixed = Frame.named[D3]("f").toOption.get
val config = AffineModelConfig.create().toOption.get
val model = AffineModel3.atFixedWorld[moving.type, fixed.type](moving, fixed, config)(0.0, 0.0, 0.0).toOption.get
val fixedToMoving = FramedAffine.betweenFrames[fixed.type, moving.type, D3](fixed, moving)(Affine.identity[D3])
model.propose(fixedToMoving, Array.fill(12)(0.0))
"""
    )
    assert(wrongDirection.nonEmpty)

    val modelMoving: Frame[D3] = geometry(Frame.named[D3]("affine-model-moving"))
    val modelFixed: Frame[D3] = geometry(Frame.named[D3]("affine-model-fixed"))
    val otherMoving: Frame[D3] = geometry(Frame.named[D3]("affine-other-moving"))
    val otherFixed: Frame[D3] = geometry(Frame.named[D3]("affine-other-fixed"))
    val model = affineModel(
      AffineModel3.atFixedWorld[Frame[D3], Frame[D3]](
        modelMoving,
        modelFixed,
        permissiveConfig
      )(0.0, 0.0, 0.0)
    )
    val wrong = FramedAffine.betweenFrames[Frame[D3], Frame[D3], D3](
      otherMoving,
      otherFixed
    )(Affine.identity[D3])
    model.propose(wrong, Array.fill(12)(0.0)) match
      case Left(
            AffineModelError.FrameOwnerMismatch(
              RigidModelEndpoint.Moving,
              _,
              _
            )
          ) => ()
      case other => fail(s"expected runtime owner rejection, got $other")

  private def permissiveConfig: AffineModelConfig =
    affineConfig(
      AffineModelConfig.create(
        minimumIncrementSingularValue = 0.1,
        maximumIncrementSingularValue = 3.0,
        minimumCandidateSingularValue = 0.1,
        maximumCandidateSingularValue = 5.0,
        maximumAbsoluteStrainIncrement = 1.0
      )
    )

  private def framed[Moving <: Frame[D3], Fixed <: Frame[D3]](
      moving: Moving,
      fixed: Fixed,
      rowMajor: Vector[Double]
  ): FramedAffine[Moving, Fixed, D3] =
    FramedAffine.betweenFrames(moving, fixed)(
      geometry(Affine.fromRowMajor[D3](rowMajor))
    )

  private def applyDeclaredIncrement(
      before: Vector[Double],
      pivot: Vector[Double],
      increment: Array[Double]
  ): Vector[Double] =
    val relative = before.zip(pivot).map(_ - _)
    val rx = relative(0)
    val ry = relative(1)
    val rz = relative(2)
    Vector(
      pivot(0) + increment(0) +
        (1.0 + increment(6)) * rx +
        (increment(9) - increment(5)) * ry +
        (increment(10) + increment(4)) * rz,
      pivot(1) + increment(1) +
        (increment(9) + increment(5)) * rx +
        (1.0 + increment(7)) * ry +
        (increment(11) - increment(3)) * rz,
      pivot(2) + increment(2) +
        (increment(10) - increment(4)) * rx +
        (increment(11) + increment(3)) * ry +
        (1.0 + increment(8)) * rz
    )

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
    value.fold(error => fail(error.message), identity)

  private def mapped[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def affineConfig[A](value: Either[AffineModelError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def affineModel[A](value: Either[AffineModelError, A]): A =
    value.fold(error => fail(error.message), identity)
