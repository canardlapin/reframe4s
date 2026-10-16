package reframe4s.flashalign

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError

final class PeFieldGeometrySuite extends munit.FunSuite:
  test("global PE certificate bounds the determinant identity and scopes evidence by coefficient hash"):
    val fixture = makeFixture(Vector(0.35, -0.22))
    val certificate = peGeometry(
      PeGeometryCertificate.evaluate(fixture.model, fixture.state, fixture.config.certificate)
    )
    assert(certificate.valid)
    assert(certificate.certifiedDirectionalDerivativeBound > certificate.rawDirectionalDerivativeBound)
    assert(certificate.certifiedAmplitudeBoundMm > certificate.rawAmplitudeBoundMm)
    assert(certificate.evidenceKind.contains("global-analytic"))
    assert(certificate.evidenceKind.contains("not SmoothIso"))
    assertEquals(certificate.extensionId, fixture.model.basis.domain.extensionId)

    val coefficients = fixture.state.field.coefficientsMm
    val gradient = new Array[Double](3)
    val pe = fixture.model.phaseEncoding.unitMovingWorld
    val rotation = fixture.state.pose.operator.rowMajor
    var minimumSampled = Double.PositiveInfinity
    (0 until 101).foreach { index =>
      val fraction = index.toDouble / 100.0
      val x = fixture.model.basis.domain.originMm(0) + fixture.model.basis.domain.periodsMm(0) * fraction
      val y = fixture.model.basis.domain.originMm(1) + 1.7
      val z = fixture.model.basis.domain.originMm(2) + 2.3
      fixture.model.writeFieldGradient(coefficients, x, y, z, gradient)
      val u = Vector.tabulate(3, 3)((row, column) =>
        (if row == column then 1.0 else 0.0) + pe(row) * gradient(column)
      )
      val a = Vector.tabulate(3, 3)((row, column) => rotation(row * 4 + column))
      val spatial = multiply(a, u)
      val lemma = determinant(a) * (1.0 + dot(pe, gradient.toVector))
      assertEqualsDouble(determinant(spatial), lemma, 8e-16)
      minimumSampled = math.min(minimumSampled, 1.0 + dot(pe, gradient.toVector))
    }
    assert(minimumSampled >= certificate.minimumDirectionalJacobian - 2e-15)

    val changedState = fixture.state.copy(
      field = fixture.model.basis.coefficientState(Vector(0.35, -0.21)).fold(error => fail(error.message), identity)
    )
    val changed = peGeometry(
      PeGeometryCertificate.evaluate(fixture.model, changedState, fixture.config.certificate)
    )
    assertNotEquals(changed.coefficientHash, certificate.coefficientHash)

  test("between-sample near fold is rejected even when sparse determinant checks look safe"):
    val fixture = makeFixture(Vector(0.0, 0.0), peDirection = Vector(1.0, 0.0, 0.0))
    val sine = fixture.model.basis.modes(1)
    val wave = math.abs(sine.angularWaveWorldPerMm(0))
    val unsafeCoefficient = 1.1 / (math.sqrt(2.0) * wave)
    val unsafeState = fixture.state.copy(
      field = fixture.model.basis.coefficientState(Vector(0.0, unsafeCoefficient))
        .fold(error => fail(error.message), identity)
    )
    val certificate = peGeometry(
      PeGeometryCertificate.evaluate(fixture.model, unsafeState, fixture.config.certificate)
    )
    assert(!certificate.valid)
    assert(certificate.minimumDirectionalJacobian < 0.0)

    val origin = fixture.model.basis.domain.originMm
    val period = fixture.model.basis.domain.periodsMm(0)
    val gradient = new Array[Double](3)
    Vector(0.25, 0.75).foreach { phase =>
      fixture.model.writeFieldGradient(
        unsafeState.field.coefficientsMm,
        origin(0) + phase * period,
        origin(1),
        origin(2),
        gradient
      )
      val sampledJacobian = 1.0 + gradient(0)
      assertEqualsDouble(sampledJacobian, 1.0, 4e-15)
    }
    PeFieldInverse3.compile(fixture.model, unsafeState, fixture.config) match
      case Left(_: PeGeometryError.InvalidCertificate) => ()
      case other => fail(s"expected global certificate rejection, got $other")

  test("bracketed inverse round-trips oblique PE geometry near source margins with retained residual evidence"):
    val fixture = makeFixture(Vector(0.42, -0.18))
    val inverse = peGeometry(PeFieldInverse3.compile(fixture.model, fixture.state, fixture.config))
    val source = Vector(
      fixture.model.basis.domain.originMm(0) + 0.02,
      fixture.model.basis.domain.originMm(1) + 3.1,
      fixture.model.basis.domain.originMm(2) + 4.7
    )
    val points = pointBatch(fixture.moving, source.toArray)
    val fixed = output(fixture.fixed, 1)
    pe(fixture.model.map(fixture.state, points, fixed, fixture.model.newWorkspace()))
    val basisScratch = new Array[Double](fixture.model.fieldParameterCount)
    val gradientScratch = new Array[Double](3)
    val recovered = peGeometry(
      inverse.inversePoint(
        fixed.snapshot(0), fixed.snapshot(1), fixed.snapshot(2),
        basisScratch, gradientScratch
      )
    )
    assertVectorClose(recovered.movingWorldMm, source, 2e-8)
    assert(recovered.residualMm <= fixture.config.inverse.residualToleranceMm)
    assert(recovered.iterations <= fixture.config.inverse.maximumIterations)
    assert(recovered.newtonSteps + recovered.bisectionSteps <= recovered.iterations)
    assertEquals(recovered.certificateHash, inverse.certificate.coefficientHash)
    assert(recovered.evidenceKind.startsWith("bracketed scalar numerical inverse"))

  test("inverse domain, source support, and iteration exhaustion remain distinct typed failures"):
    val fixture = makeFixture(Vector(0.4, -0.17))
    val inverse = peGeometry(PeFieldInverse3.compile(fixture.model, fixture.state, fixture.config))
    inverse.inversePoint(500.0, 0.0, 0.0, new Array[Double](2), new Array[Double](3)) match
      case Left(_: PeGeometryError.OutsideFixedDomain) => ()
      case other => fail(s"expected reverse-domain failure, got $other")

    val outsideSource = Vector(
      fixture.model.basis.domain.originMm(0) - 10.0,
      fixture.model.basis.domain.originMm(1),
      fixture.model.basis.domain.originMm(2)
    )
    val outsideBatch = pointBatch(fixture.moving, outsideSource.toArray)
    val mapped = output(fixture.fixed, 1)
    pe(fixture.model.map(fixture.state, outsideBatch, mapped, fixture.model.newWorkspace()))
    inverse.inversePoint(
      mapped.snapshot(0), mapped.snapshot(1), mapped.snapshot(2),
      new Array[Double](2), new Array[Double](3)
    ) match
      case Left(_: PeGeometryError.OutsideSourceDomain) => ()
      case other => fail(s"expected forward-domain support failure, got $other")

    val exhaustedConfig = fixture.config.copy(
      inverse = peGeometry(PeInverseConfig.create(1e-16, maximumIterations = 1))
    )
    val exhausted = peGeometry(PeFieldInverse3.compile(fixture.model, fixture.state, exhaustedConfig))
    val central = Vector(2.0, 1.0, 3.0)
    val centralBatch = pointBatch(fixture.moving, central.toArray)
    pe(fixture.model.map(fixture.state, centralBatch, mapped, fixture.model.newWorkspace()))
    exhausted.inversePoint(
      mapped.snapshot(0), mapped.snapshot(1), mapped.snapshot(2),
      new Array[Double](2), new Array[Double](3)
    ) match
      case Left(PeGeometryError.InverseIterationLimit(1, _, 1e-16)) => ()
      case other => fail(s"expected inverse iteration limit, got $other")

  test("PE model satisfies the shared geometry interface and rejects uncertified proposals before mapping"):
    val fixture = makeFixture(Vector(0.31, -0.13))
    val operator = GeometryPointOperator3.peField(fixture.model, fixture.config)
    val source = Vector(1.0, 2.0, 3.0, -2.0, 1.5, 0.4)
    val points = pointBatch(fixture.moving, source.toArray)
    val mapped = output(fixture.fixed, 2)
    val roundTrip = output(fixture.moving, 2)
    val workspace = operator.newWorkspace()
    geometryOperator(operator.map(fixture.state, points, mapped, workspace))
    val fixedPoints = pointBatch(fixture.fixed, mapped.snapshot.toArray)
    geometryOperator(operator.inverseMap(fixture.state, fixedPoints, roundTrip, workspace))
    assertVectorClose(roundTrip.snapshot, source, 2e-8)
    val certificate = geometryOperator(operator.certify(fixture.state))
    assert(certificate.valid)
    assert(certificate.detail.contains("not SmoothIso"))

    val unsafe = Array.fill(operator.parameterCount)(0.0)
    unsafe(7) = 100.0
    val direction = geometryOperator(
      GeometryDirection3.create(operator.modelId, operator.basisId, unsafe)
    )
    operator.propose(fixture.state, direction) match
      case Left(_: GeometryOperatorError.Nonlinear) => ()
      case other => fail(s"expected uncertified proposal rejection, got $other")

    val outside = pointBatch(fixture.moving, Array(500.0, 0.0, 0.0))
    operator.map(fixture.state, outside, output(fixture.fixed, 1), workspace) match
      case Left(_: GeometryOperatorError.Nonlinear) => ()
      case other => fail(s"expected forward-domain geometry failure, got $other")

  private final case class Fixture(
      moving: Frame[D3],
      fixed: Frame[D3],
      model: PeFieldModel3[Frame[D3], Frame[D3]],
      state: PeFieldState3[Frame[D3], Frame[D3]],
      config: PeGeometryConfig
  )

  private def makeFixture(
      coefficients: Vector[Double],
      peDirection: Vector[Double] = normalized(Vector(0.6, -0.7, 0.3))
  ): Fixture =
    val moving: Frame[D3] = geometry(Frame.named[D3]("pe-geometry-moving"))
    val fixed: Frame[D3] = geometry(Frame.named[D3]("pe-geometry-fixed"))
    val domain = PhysicalSpectralDomain3.create(
      originMm = Vector(-5.0, -6.0, -7.0),
      axes = Vector(Vector(1.0, 0.0, 0.0), Vector(0.0, 1.0, 0.0), Vector(0.0, 0.0, 1.0)),
      periodsMm = Vector(20.0, 18.0, 16.0),
      paddingMm = Vector(2.0, 2.0, 2.0),
      extensionId = "periodic-global-r3-v1"
    ).fold(error => fail(error.message), identity)
    val gaugePoints = (for
      i <- 0 until 6
      j <- 0 until 5
      k <- 0 until 4
    yield Vector(
      domain.originMm(0) + domain.periodsMm(0) * i.toDouble / 6.0,
      domain.originMm(1) + domain.periodsMm(1) * j.toDouble / 5.0,
      domain.originMm(2) + domain.periodsMm(2) * k.toDouble / 4.0
    )).toVector
    val gauge = GeometryGaugeMeasure3.create(
      "pe-global-fixed-source",
      gaugePoints,
      Vector.fill(gaugePoints.length)(1.0)
    ).fold(error => fail(error.message), identity)
    val basis = PhysicalSpectralBasis3.fromWavevectors(
      domain,
      Vector(IntegerWave3(1, 0, 0)),
      gauge
    ).fold(error => fail(error.message), identity)
    val rigidModel = rigidModelValue(
      RigidModel3.atFixedWorld[Frame[D3], Frame[D3]](moving, fixed)(0.0, 0.0, 0.0)
    )
    val model = pe(
      PeFieldModel3.compile[Frame[D3], Frame[D3]](
        rigidModel,
        basis,
        PhaseEncodingDirection3(0, 1, peDirection),
        SpectralPriorWeights(0.6, 0.8, 0.04),
        pe(PeFieldModelConfig.create(2, 8, 2))
      )
    )
    val angle = 0.19
    val cosine = math.cos(angle)
    val sine = math.sin(angle)
    val pose = rigid(
      Rigid3.fromAffine[Frame[D3], Frame[D3]](moving, fixed)(
        geometry(Affine.fromRowMajor[D3](Vector(
          cosine, -sine, 0.0, 0.7,
          sine, cosine, 0.0, -0.4,
          0.0, 0.0, 1.0, 0.3,
          0.0, 0.0, 0.0, 1.0
        )))
      )
    )
    val state = PeFieldState3(
      pose,
      basis.coefficientState(coefficients).fold(error => fail(error.message), identity)
    )
    val source = peGeometry(PeWorldDomain3.fromSpectralDomain("pe-source-support", domain, includePadding = false))
    val fixedDomain = peGeometry(PeWorldDomain3.create(
      "pe-fixed-support",
      Vector(0.0, 0.0, 0.0),
      Vector(Vector(1.0, 0.0, 0.0), Vector(0.0, 1.0, 0.0), Vector(0.0, 0.0, 1.0)),
      Vector(-100.0, -100.0, -100.0),
      Vector(100.0, 100.0, 100.0)
    ))
    val config = PeGeometryConfig(
      peGeometry(PeCertificateConfig.create(0.2, 5.0, 1e-12, 1e-12)),
      peGeometry(PeInverseConfig.create(1e-9, 64)),
      source,
      fixedDomain
    )
    Fixture(moving, fixed, model, state, config)

  private def normalized(value: Vector[Double]): Vector[Double] =
    val norm = math.sqrt(dot(value, value))
    value.map(_ / norm)

  private def multiply(left: Vector[Vector[Double]], right: Vector[Vector[Double]]): Vector[Vector[Double]] =
    Vector.tabulate(3, 3)((row, column) =>
      (0 until 3).map(index => left(row)(index) * right(index)(column)).sum
    )

  private def determinant(matrix: Vector[Vector[Double]]): Double =
    matrix(0)(0) * (matrix(1)(1) * matrix(2)(2) - matrix(1)(2) * matrix(2)(1)) -
      matrix(0)(1) * (matrix(1)(0) * matrix(2)(2) - matrix(1)(2) * matrix(2)(0)) +
      matrix(0)(2) * (matrix(1)(0) * matrix(2)(1) - matrix(1)(1) * matrix(2)(0))

  private def pointBatch[F <: Frame[D3]](frame: F, packed: Array[Double]): WorldPointBatch3[F] =
    WorldPointBatch3.create(frame, packed).fold(error => fail(error.message), identity)

  private def output[F <: Frame[D3]](frame: F, size: Int): GeometryOutputBuffer3[F] =
    GeometryOutputBuffer3.allocate(frame, size).fold(error => fail(error.message), identity)

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

  private def assertVectorClose(actual: Vector[Double], expected: Vector[Double], tolerance: Double): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) => assertEqualsDouble(left, right, tolerance) }

  private def geometry[A](value: Either[GeometryError, A]): A = value.fold(error => fail(error.message), identity)
  private def rigid[A](value: Either[RigidError, A]): A = value.fold(error => fail(error.message), identity)
  private def rigidModelValue[A](value: Either[RigidModelError, A]): A =
    value.fold(error => fail(error.message), identity)
  private def pe[A](value: Either[PeFieldError, A]): A = value.fold(error => fail(error.message), identity)
  private def peGeometry[A](value: Either[PeGeometryError, A]): A =
    value.fold(error => fail(error.message), identity)
  private def geometryOperator[A](value: Either[GeometryOperatorError, A]): A =
    value.fold(error => fail(error.message), identity)

