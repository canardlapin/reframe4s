package reframe4s.flashalign

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import reframe4s.lie.FramedAffine

final class SmallStrainGeometrySuite extends munit.FunSuite:
  test("global coefficient certificate conservatively bounds field and composite affine Jacobians"):
    val fixture = makeFixture("composite", maximumGradient = 0.72)
    val coefficients = Vector(0.12, -0.08, 0.05, 0.04, -0.06, 0.03)
    val state = stateFor(fixture, coefficients)
    val certificate = geometryResult(
      SmallStrainGeometryCertificate.evaluate(fixture.model, state, fixture.config.certificate)
    )
    assert(certificate.valid)
    assert(certificate.gradientMargin > 0.0)
    assert(certificate.poseDeterminant > 0.0)
    assert(certificate.evidenceKind.contains("not sampled topology"))
    assert(certificate.evidenceKind.contains("not SmoothIso"))

    val a = linear3(state.pose.operator.rowMajor)
    val modeGradient = new Array[Double](9)
    fixture.gauge.pointsWorldMm.foreach { point =>
      val gradient = Array.fill(9)(0.0)
      coefficients.indices.foreach { mode =>
        fixture.model.basis.modeGradient(mode, point(0), point(1), point(2), modeGradient)
        modeGradient.indices.foreach(index => gradient(index) += coefficients(mode) * modeGradient(index))
      }
      val gradientNorm = AffineModel3.singularValueExtrema(gradient)._2
      assert(gradientNorm <= certificate.certifiedGradientBound * (1.0 + 1e-11))
      val identityPlus = gradient.clone()
      identityPlus(0) += 1.0; identityPlus(4) += 1.0; identityPlus(8) += 1.0
      val composite = multiply3(a, identityPlus)
      val singular = AffineModel3.singularValueExtrema(composite)
      assert(singular._1 + 2e-12 >= certificate.compositeMinimumSingularValueBound)
      assert(singular._2 <= certificate.compositeMaximumSingularValueBound + 2e-12)
      assert(AffineModel3.determinant(composite) + 2e-12 >= certificate.compositeDeterminantLowerBound)
    }

  test("certificate rejects a coefficient step above the global gradient limit"):
    val fixture = makeFixture("reject", maximumGradient = 0.35)
    val zero = small(fixture.model.zeroState(fixture.pose))
    val coefficient = 1.1 * fixture.config.certificate.maximumGradient /
      fixture.model.basis.modes.head.derivativeBound
    val rejected = stateFor(fixture, Vector(coefficient, 0.0, 0.0, 0.0, 0.0, 0.0))
    val certificate = geometryResult(
      SmallStrainGeometryCertificate.evaluate(fixture.model, rejected, fixture.config.certificate)
    )
    assert(!certificate.valid)
    assert(certificate.gradientMargin < 0.0)

    val operator = GeometryPointOperator3.smallStrain(fixture.model, fixture.config)
    val directionValues = Array.fill(operator.parameterCount)(0.0)
    directionValues(operator.parameterCount - fixture.model.fieldParameterCount) = coefficient
    val direction = geometryOperator(GeometryDirection3.create(operator.modelId, operator.basisId, directionValues))
    operator.propose(zero, direction) match
      case Left(GeometryOperatorError.Nonlinear(detail)) => assert(detail.contains("uncertified"))
      case other => fail(s"expected certificate rejection, got $other")

  test("contraction inverse closes near the configured gradient limit including the smooth extension"):
    val fixture = makeFixture("inverse", maximumGradient = 0.85)
    val coefficient = 0.72 * fixture.config.certificate.maximumGradient /
      fixture.model.basis.modes.head.derivativeBound
    val state = stateFor(fixture, Vector(coefficient, 0.0, 0.0, 0.0, 0.0, 0.0))
    val operator = GeometryPointOperator3.smallStrain(fixture.model, fixture.config)
    val source = pointBatch(fixture.moving, Array(
      -4.2, -3.1, 2.0,
      3.4, 2.7, -1.5,
      7.4, -0.5, 0.8
    ))
    val mapped = output(fixture.fixed, source.size)
    val restored = output(fixture.moving, source.size)
    geometryOperator(operator.map(state, source, mapped, operator.newWorkspace()))
    val fixedPoints = pointBatch(fixture.fixed, mapped.snapshot.toArray)
    geometryOperator(operator.inverseMap(state, fixedPoints, restored, operator.newWorkspace()))
    assertVectorClose(restored.snapshot, source.packed.toVector, 2.5e-8)

    val inverse = geometryResult(SmallStrainInverse3.compile(fixture.model, state, fixture.config))
    val one = geometryResult(
      inverse.inversePoint(mapped.snapshot(6), mapped.snapshot(7), mapped.snapshot(8), fixture.model.newWorkspace())
    )
    assert(one.aPosterioriErrorBoundMm <= fixture.config.inverse.errorToleranceMm)
    assert(one.evidenceKind.contains("not negated displacement"))
    assert(one.evidenceKind.contains("not SmoothIso"))

  test("inverse iteration exhaustion and domain failures are typed"):
    val fixture = makeFixture("inverse-failure", maximumGradient = 0.85)
    val coefficient = 0.70 * fixture.config.certificate.maximumGradient /
      fixture.model.basis.modes.head.derivativeBound
    val state = stateFor(fixture, Vector(coefficient, 0.0, 0.0, 0.0, 0.0, 0.0))
    val strict = fixture.config.copy(
      inverse = geometryResult(SmallStrainInverseConfig.create(1e-14, 1))
    )
    val source = pointBatch(fixture.moving, Array(2.8, -1.9, 0.7))
    val mapped = output(fixture.fixed, 1)
    val operator = GeometryPointOperator3.smallStrain(fixture.model, fixture.config)
    geometryOperator(operator.map(state, source, mapped, operator.newWorkspace()))
    val inverse = geometryResult(SmallStrainInverse3.compile(fixture.model, state, strict))
    inverse.inversePoint(mapped.snapshot(0), mapped.snapshot(1), mapped.snapshot(2), fixture.model.newWorkspace()) match
      case Left(_: SmallStrainGeometryError.InverseIterationLimit) => ()
      case other => fail(s"expected typed iteration exhaustion, got $other")
    inverse.inversePoint(100.0, 0.0, 0.0, fixture.model.newWorkspace()) match
      case Left(_: SmallStrainGeometryError.OutsideFixedDomain) => ()
      case other => fail(s"expected fixed-domain failure, got $other")

  private final case class Fixture[Moving <: Frame[D3], Fixed <: Frame[D3]](
      moving: Moving,
      fixed: Fixed,
      model: SmallStrainModel3[FramedAffine[Moving, Fixed, D3], Moving, Fixed],
      pose: FramedAffine[Moving, Fixed, D3],
      config: SmallStrainGeometryConfig,
      gauge: GeometryGaugeMeasure3
  )

  private def makeFixture(label: String, maximumGradient: Double): Fixture[? <: Frame[D3], ? <: Frame[D3]] =
    val moving = geometry(Frame.named[D3](s"small-strain-geometry-moving-$label"))
    val fixed = geometry(Frame.named[D3](s"small-strain-geometry-fixed-$label"))
    val modelConfig = affine(AffineModelConfig.create())
    val poseModel = affine(AffineModel3.atFixedWorld[moving.type, fixed.type](moving, fixed, modelConfig)(0.0, 0.0, 0.0))
    val pose = FramedAffine.betweenFrames[moving.type, fixed.type, D3](moving, fixed)(
      geometry(Affine.fromRowMajor[D3](Vector(
        1.08, 0.04, 0.0, 0.3,
        -0.02, 0.92, 0.03, -0.2,
        0.01, 0.02, 1.04, 0.1,
        0.0, 0.0, 0.0, 1.0
      )))
    )
    val domain = PhysicalSpectralDomain3.create(
      Vector(-6.0, -6.0, -6.0),
      Vector(Vector(1.0, 0.0, 0.0), Vector(0.0, 1.0, 0.0), Vector(0.0, 0.0, 1.0)),
      Vector(12.0, 12.0, 12.0),
      Vector(2.0, 2.0, 2.0),
      s"small-strain-geometry-extension-$label-v1"
    ).fold(error => fail(error.message), identity)
    val coordinates = Vector(-5.0, -3.0, -1.0, 1.0, 3.0, 5.0)
    val gaugePoints = (for x <- coordinates; y <- coordinates; z <- coordinates yield Vector(x, y, z)).toVector
    val gauge = GeometryGaugeMeasure3.create(
      s"small-strain-geometry-gauge-$label-v1",
      gaugePoints,
      Vector.fill(gaugePoints.length)(1.0)
    ).fold(error => fail(error.message), identity)
    val scalar = PhysicalSpectralBasis3.fromWavevectors(domain, Vector(IntegerWave3(1, 1, 0)), gauge)
      .fold(error => fail(error.message), identity)
    val basis = small(
      SmallStrainVectorBasis3.compile(
        scalar,
        gauge,
        SmallStrainPoseKind.Affine,
        Vector(0.0, 0.0, 0.0),
        SmallStrainPriorWeights(0.5, 0.25, 0.05, 0.1),
        small(SmallStrainModelConfig.create(6, 18, 6))
      )
    )
    val model = small(SmallStrainModel3.affine(poseModel, basis))
    val sourceDomain = SmallStrainWorldDomain3.fromSpectralDomain(s"small-strain-source-$label", domain)
      .fold(error => fail(error.message), identity)
    val fixedDomain = SmallStrainWorldDomain3.create(
      s"small-strain-fixed-$label",
      Vector(-30.0, -30.0, -30.0),
      domain.axes,
      Vector(0.0, 0.0, 0.0),
      Vector(60.0, 60.0, 60.0)
    ).fold(error => fail(error.message), identity)
    val config = SmallStrainGeometryConfig(
      geometryResult(SmallStrainCertificateConfig.create(maximumGradient, 50.0, maximumPoseSingularValue = 2.0)),
      geometryResult(SmallStrainInverseConfig.create(1e-8, 120)),
      sourceDomain,
      fixedDomain
    )
    Fixture(moving, fixed, model, pose, config, gauge)

  private def stateFor[Moving <: Frame[D3], Fixed <: Frame[D3]](
      fixture: Fixture[Moving, Fixed],
      coefficients: Vector[Double]
  ): SmallStrainState3[FramedAffine[Moving, Fixed, D3]] =
    SmallStrainState3(fixture.pose, small(fixture.model.basis.coefficientState(coefficients)))

  private def linear3(matrix: Vector[Double]): Array[Double] =
    Array(matrix(0), matrix(1), matrix(2), matrix(4), matrix(5), matrix(6), matrix(8), matrix(9), matrix(10))

  private def multiply3(left: Array[Double], right: Array[Double]): Array[Double] =
    Array.tabulate(9)(index =>
      val row = index / 3; val column = index % 3
      (0 until 3).map(inner => left(row * 3 + inner) * right(inner * 3 + column)).sum
    )

  private def pointBatch[F <: Frame[D3]](frame: F, values: Array[Double]): WorldPointBatch3[F] =
    WorldPointBatch3.create[F](frame, values).fold(error => fail(error.message), identity)
  private def output[F <: Frame[D3]](frame: F, size: Int): GeometryOutputBuffer3[F] =
    GeometryOutputBuffer3.allocate(frame, size).fold(error => fail(error.message), identity)
  private def assertVectorClose(actual: Vector[Double], expected: Vector[Double], tolerance: Double): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) => assertEqualsDouble(left, right, tolerance) }
  private def geometry[A](value: Either[GeometryError, A]): A = value.fold(error => fail(error.message), identity)
  private def affine[A](value: Either[AffineModelError, A]): A = value.fold(error => fail(error.message), identity)
  private def small[A](value: Either[SmallStrainError, A]): A = value.fold(error => fail(error.message), identity)
  private def geometryResult[A](value: Either[SmallStrainGeometryError, A]): A = value.fold(error => fail(error.message), identity)
  private def geometryOperator[A](value: Either[GeometryOperatorError, A]): A = value.fold(error => fail(error.message), identity)
