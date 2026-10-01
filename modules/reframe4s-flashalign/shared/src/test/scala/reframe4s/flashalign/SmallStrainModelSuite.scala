package reframe4s.flashalign

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError

final class SmallStrainModelSuite extends munit.FunSuite:
  test("rigid small-strain map JVP and VJP match finite differences and adjoint identity"):
    val fixture = rigidFixture("derivatives")
    val state = stateFor(fixture.model, fixture.pose, Vector(0.08, -0.04, 0.03, 0.02, -0.05, 0.01))
    val direction = Array.tabulate(fixture.model.parameterCount)(index => 0.025 * math.sin(0.7 * (index + 1)))
    checkDerivatives(fixture.model, state, fixture.points, direction, 5e-8)

  test("affine small-strain map JVP and VJP match finite differences and adjoint identity"):
    val fixture = affineFixture("derivatives")
    val state = stateFor(fixture.model, fixture.pose, Vector(0.04, -0.03, 0.02, 0.05, -0.02, 0.01))
    val direction = Array.tabulate(fixture.model.parameterCount)(index => 0.012 * math.cos(0.43 * (index + 1)))
    checkDerivatives(fixture.model, state, fixture.points, direction, 8e-8)

  test("rigid and affine gauges remove their exact geometry spaces and retain local volume change"):
    val rigid = rigidFixture("gauge-rigid")
    val affine = affineFixture("gauge-affine")
    assertEquals(rigid.model.basis.poseKind, SmallStrainPoseKind.Rigid)
    assertEquals(affine.model.basis.poseKind, SmallStrainPoseKind.Affine)
    assert(rigid.model.basis.modes.forall(_.poseProjection.length == 6))
    assert(affine.model.basis.modes.forall(_.poseProjection.length == 12))
    assertGaugeOrthogonal(rigid.model.basis, rigid.gauge, 2e-11)
    assertGaugeOrthogonal(affine.model.basis, affine.gauge, 3e-11)

    val gradient = new Array[Double](9)
    val probe = Vector(1.7, -2.3, 0.9)
    val rigidDivergence = rigid.model.basis.modes.indices.map { mode =>
      rigid.model.basis.modeGradient(mode, probe(0), probe(1), probe(2), gradient)
      math.abs(gradient(0) + gradient(4) + gradient(8))
    }.max
    val affineDivergence = affine.model.basis.modes.indices.map { mode =>
      affine.model.basis.modeGradient(mode, probe(0), probe(1), probe(2), gradient)
      math.abs(gradient(0) + gradient(4) + gradient(8))
    }.max
    assert(rigidDivergence > 1e-3)
    assert(affineDivergence > 1e-3)
    assert(rigid.model.interpretation.contains("local expansion and contraction allowed"))
    assert(rigid.model.interpretation.contains("not measured tissue mechanics"))

  test("elastic prior gradient and curvature match independent finite differences"):
    val fixture = rigidFixture("prior")
    val coefficients = Vector(0.11, -0.07, 0.03, 0.06, -0.04, 0.02)
    val state = stateFor(fixture.model, fixture.pose, coefficients)
    val prior = small(fixture.model.prior(state))
    assert(prior.value > 0.0)
    val epsilon = 1e-6
    coefficients.indices.foreach { field =>
      val direction = Array.fill(fixture.model.parameterCount)(0.0)
      direction(fixture.model.poseParameterCount + field) = epsilon
      val plus = small(fixture.model.propose(state, direction))._1
      direction(fixture.model.poseParameterCount + field) = -epsilon
      val minus = small(fixture.model.propose(state, direction))._1
      val numericGradient = (small(fixture.model.prior(plus)).value - small(fixture.model.prior(minus)).value) / (2.0 * epsilon)
      val parameter = fixture.model.poseParameterCount + field
      assertEqualsDouble(prior.gradient(parameter), numericGradient, 2e-9)
      val numericCurvature = (small(fixture.model.prior(plus)).gradient(parameter) - small(fixture.model.prior(minus)).gradient(parameter)) / (2.0 * epsilon)
      assertEqualsDouble(prior.curvatureUpper(PackedSymmetric.index(parameter, parameter)), numericCurvature, 3e-9)
    }
    (0 until fixture.model.poseParameterCount).foreach(parameter => assertEquals(prior.gradient(parameter), 0.0))

  test("transported derivative bounds dominate sampled projected-mode gradients"):
    val fixture = affineFixture("bounds")
    val gradient = new Array[Double](9)
    fixture.model.basis.modes.indices.foreach { mode =>
      val sampled = fixture.gauge.pointsWorldMm.map { point =>
        fixture.model.basis.modeGradient(mode, point(0), point(1), point(2), gradient)
        spectralNorm3(gradient)
      }.max
      assert(sampled <= fixture.model.basis.modes(mode).derivativeBound * (1.0 + 1e-12))
    }
    assertEquals(fixture.model.coefficientUnits, "coefficients and displacement in millimetres; deformation gradients dimensionless")
    assertEquals(fixture.model.nominalFieldParameterCount, 6)
    assertEquals(fixture.model.effectiveFieldParameterCount, 6)

  private final case class Fixture[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: SmallStrainModel3[Pose, Moving, Fixed],
      pose: Pose,
      points: WorldPointBatch3[Moving],
      gauge: GeometryGaugeMeasure3
  )

  private def rigidFixture(label: String): Fixture[?, ?, ?] =
    val moving = geometry(Frame.named[D3](s"small-strain-rigid-moving-$label"))
    val fixed = geometry(Frame.named[D3](s"small-strain-rigid-fixed-$label"))
    val poseModel = rigidModel(RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(0.2, -0.3, 0.1))
    val pose = rigid(Rigid3.translationBetween[moving.type, fixed.type](moving, fixed)(0.3, -0.2, 0.1))
    val inputs = basisInputs(label)
    val basis = small(
      SmallStrainVectorBasis3.compile(
        inputs._1,
        inputs._2,
        SmallStrainPoseKind.Rigid,
        Vector(0.2, -0.3, 0.1),
        priorWeights,
        small(SmallStrainModelConfig.create(6, 18, 6))
      )
    )
    val model = small(SmallStrainModel3.rigid(poseModel, basis))
    Fixture(model, pose, pointBatch(moving), inputs._2)

  private def affineFixture(label: String): Fixture[?, ?, ?] =
    val moving = geometry(Frame.named[D3](s"small-strain-affine-moving-$label"))
    val fixed = geometry(Frame.named[D3](s"small-strain-affine-fixed-$label"))
    val config = affine(AffineModelConfig.create())
    val poseModel = affine(AffineModel3.atFixedWorld[moving.type, fixed.type](moving, fixed, config)(0.2, -0.3, 0.1))
    val pose = FramedAffine.betweenFrames[moving.type, fixed.type, D3](moving, fixed)(
      geometry(Affine.fromRowMajor[D3](Vector(
        1.01, 0.01, 0.0, 0.2,
        -0.01, 0.99, 0.02, -0.1,
        0.0, 0.01, 1.02, 0.15,
        0.0, 0.0, 0.0, 1.0
      )))
    )
    val inputs = basisInputs(label)
    val basis = small(
      SmallStrainVectorBasis3.compile(
        inputs._1,
        inputs._2,
        SmallStrainPoseKind.Affine,
        Vector(0.2, -0.3, 0.1),
        priorWeights,
        small(SmallStrainModelConfig.create(6, 18, 6))
      )
    )
    val model = small(SmallStrainModel3.affine(poseModel, basis))
    Fixture(model, pose, pointBatch(moving), inputs._2)

  private def basisInputs(label: String): (PhysicalSpectralBasis3, GeometryGaugeMeasure3) =
    val domain = PhysicalSpectralDomain3.create(
      Vector(-6.0, -6.0, -6.0),
      Vector(Vector(1.0, 0.0, 0.0), Vector(0.0, 1.0, 0.0), Vector(0.0, 0.0, 1.0)),
      Vector(12.0, 12.0, 12.0),
      Vector(2.0, 2.0, 2.0),
      s"small-strain-periodic-$label-v1"
    ).fold(error => fail(error.message), identity)
    val coordinates = Vector(-5.0, -3.0, -1.0, 1.0, 3.0, 5.0)
    val points = (for x <- coordinates; y <- coordinates; z <- coordinates yield Vector(x, y, z)).toVector
    val gauge = GeometryGaugeMeasure3.create(
      s"small-strain-gauge-$label-v1",
      points,
      Vector.fill(points.length)(1.0)
    ).fold(error => fail(error.message), identity)
    val scalar = PhysicalSpectralBasis3.fromWavevectors(domain, Vector(IntegerWave3(1, 1, 0)), gauge)
      .fold(error => fail(error.message), identity)
    scalar -> gauge

  private val priorWeights = SmallStrainPriorWeights(0.7, 0.35, 0.08, 0.12)

  private def pointBatch[F <: Frame[D3]](frame: F): WorldPointBatch3[F] =
    val values = Array(
      -3.1, -2.2, 1.4,
      2.7, -1.3, -2.0,
      1.1, 3.2, 0.7,
      -2.4, 2.1, -1.6,
      0.3, -0.8, 2.9
    )
    WorldPointBatch3.create[F](frame, values).fold(error => fail(error.message), identity)

  private def stateFor[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: SmallStrainModel3[Pose, Moving, Fixed],
      pose: Pose,
      coefficients: Vector[Double]
  ): SmallStrainState3[Pose] =
    SmallStrainState3(pose, small(model.basis.coefficientState(coefficients)))

  private def checkDerivatives[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: SmallStrainModel3[Pose, Moving, Fixed],
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      tolerance: Double
  ): Unit =
    val analytic = output(model.fixed, points.size)
    small(model.jvp(state, points, direction, analytic, model.newWorkspace()))
    val epsilon = 1e-6
    val plus = small(model.propose(state, direction.map(_ * epsilon)))._1
    val minus = small(model.propose(state, direction.map(_ * -epsilon)))._1
    val plusMapped = output(model.fixed, points.size)
    val minusMapped = output(model.fixed, points.size)
    small(model.map(plus, points, plusMapped, model.newWorkspace()))
    small(model.map(minus, points, minusMapped, model.newWorkspace()))
    val numeric = plusMapped.snapshot.zip(minusMapped.snapshot).map { case (left, right) => (left - right) / (2.0 * epsilon) }
    assertVectorClose(analytic.snapshot, numeric, tolerance)

    val forceValues = Array.tabulate(points.size * 3)(index => math.sin(0.31 * (index + 1)))
    val forces = WorldVectorBatch3.create(model.fixed, forceValues).fold(error => fail(error.message), identity)
    val transpose = new Array[Double](model.parameterCount)
    small(model.vjp(state, points, forces, transpose, model.newWorkspace()))
    assertEqualsDouble(dot(analytic.snapshot, forceValues.toVector), dot(direction.toVector, transpose.toVector), 2e-11)

  private def assertGaugeOrthogonal(basis: SmallStrainVectorBasis3, gauge: GeometryGaugeMeasure3, tolerance: Double): Unit =
    val value = new Array[Double](3)
    basis.modes.indices.foreach { mode =>
      (0 until basis.poseKind.parameterCount).foreach { pose =>
        val inner = gauge.pointsWorldMm.zip(gauge.normalizedWeights).map { case (point, weight) =>
          basis.modeValue(mode, point(0), point(1), point(2), value)
          weight * dot(value.toVector, poseField(basis.poseKind, basis.pivotWorldMm, point, pose))
        }.sum
        assertEqualsDouble(inner, 0.0, tolerance)
      }
    }

  private def poseField(kind: SmallStrainPoseKind, pivot: Vector[Double], point: Vector[Double], parameter: Int): Vector[Double] =
    val rx = point(0) - pivot(0); val ry = point(1) - pivot(1); val rz = point(2) - pivot(2)
    parameter match
      case 0 => Vector(1.0, 0.0, 0.0)
      case 1 => Vector(0.0, 1.0, 0.0)
      case 2 => Vector(0.0, 0.0, 1.0)
      case 3 => Vector(0.0, -rz, ry)
      case 4 => Vector(rz, 0.0, -rx)
      case 5 => Vector(-ry, rx, 0.0)
      case 6 if kind == SmallStrainPoseKind.Affine => Vector(rx, 0.0, 0.0)
      case 7 if kind == SmallStrainPoseKind.Affine => Vector(0.0, ry, 0.0)
      case 8 if kind == SmallStrainPoseKind.Affine => Vector(0.0, 0.0, rz)
      case 9 if kind == SmallStrainPoseKind.Affine => Vector(ry, rx, 0.0)
      case 10 if kind == SmallStrainPoseKind.Affine => Vector(rz, 0.0, rx)
      case 11 if kind == SmallStrainPoseKind.Affine => Vector(0.0, rz, ry)
      case _ => Vector(0.0, 0.0, 0.0)

  private def spectralNorm3(matrix: Array[Double]): Double =
    val gram = Array.tabulate(3, 3)((row, column) => (0 until 3).map(axis => matrix(axis * 3 + row) * matrix(axis * 3 + column)).sum)
    var vector = Array(1.0, -0.4, 0.7)
    (0 until 30).foreach { _ =>
      val next = Array.tabulate(3)(row => (0 until 3).map(column => gram(row)(column) * vector(column)).sum)
      val norm = math.sqrt(next.map(value => value * value).sum)
      if norm > 0.0 then vector = next.map(_ / norm)
    }
    val product = Array.tabulate(3)(row => (0 until 3).map(column => gram(row)(column) * vector(column)).sum)
    math.sqrt(math.max(0.0, vector.indices.map(index => vector(index) * product(index)).sum))

  private def output[F <: Frame[D3]](frame: F, size: Int): GeometryOutputBuffer3[F] =
    GeometryOutputBuffer3.allocate(frame, size).fold(error => fail(error.message), identity)
  private def dot(left: Vector[Double], right: Vector[Double]): Double = left.indices.map(index => left(index) * right(index)).sum
  private def assertVectorClose(actual: Vector[Double], expected: Vector[Double], tolerance: Double): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) => assertEqualsDouble(left, right, tolerance) }
  private def geometry[A](value: Either[GeometryError, A]): A = value.fold(error => fail(error.message), identity)
  private def rigid[A](value: Either[RigidError, A]): A = value.fold(error => fail(error.message), identity)
  private def rigidModel[A](value: Either[RigidModelError, A]): A = value.fold(error => fail(error.message), identity)
  private def affine[A](value: Either[AffineModelError, A]): A = value.fold(error => fail(error.message), identity)
  private def small[A](value: Either[SmallStrainError, A]): A = value.fold(error => fail(error.message), identity)
