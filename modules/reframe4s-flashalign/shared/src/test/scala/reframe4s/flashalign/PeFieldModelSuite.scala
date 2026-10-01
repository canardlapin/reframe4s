package reframe4s.flashalign

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError

final class PeFieldModelSuite extends munit.FunSuite:
  test("acquisition axis and polarity resolve through an oblique sheared world affine"):
    val affine = geometry(
      Affine.fromRowMajor[D3](Vector(
        1.5, 0.2, 0.0, 10.0,
        0.1, 2.0, 0.3, -4.0,
        0.0, 0.4, 3.0, 7.0,
        0.0, 0.0, 0.0, 1.0
      ))
    )
    val direction = pe(
      PhaseEncodingDirection3.resolve(
        Vector(AcquisitionPhaseEncoding3(voxelAxis = 1, polarity = -1)),
        affine
      )
    )
    val scale = math.sqrt(0.2 * 0.2 + 2.0 * 2.0 + 0.4 * 0.4)
    assertVectorClose(direction.unitMovingWorld, Vector(-0.2 / scale, -2.0 / scale, -0.4 / scale), 2e-15)
    assertEquals(direction.voxelAxis, 1)
    assertEquals(direction.polarity, -1)

    PhaseEncodingDirection3.resolve(Vector.empty, affine) match
      case Left(PeFieldError.MissingPhaseEncodingMetadata) => ()
      case other => fail(s"expected missing PE metadata, got $other")
    PhaseEncodingDirection3.resolve(
      Vector(AcquisitionPhaseEncoding3(0, 1), AcquisitionPhaseEncoding3(1, 1)),
      affine
    ) match
      case Left(PeFieldError.AmbiguousPhaseEncodingMetadata(2)) => ()
      case other => fail(s"expected ambiguous PE metadata, got $other")

  test("rigid plus PE map JVP and VJP match finite differences and the adjoint identity"):
    val fixture = modelFixture()
    val model = fixture.model
    val state = fixture.state
    val points = pointBatch(fixture.moving, samplePoints)
    val direction = Array(0.08, -0.04, 0.03, 0.02, -0.015, 0.01, 0.12, -0.07)
    val workspace = model.newWorkspace()
    val analytic = output(fixture.fixed, points.size)
    pe(model.jvp(state, points, direction, analytic, workspace))

    val epsilon = 1e-6
    val plus = pe(model.propose(state, direction.map(_ * epsilon)))._1
    val minus = pe(model.propose(state, direction.map(_ * -epsilon)))._1
    val plusMapped = output(fixture.fixed, points.size)
    val minusMapped = output(fixture.fixed, points.size)
    pe(model.map(plus, points, plusMapped, workspace))
    pe(model.map(minus, points, minusMapped, workspace))
    val numeric = plusMapped.snapshot.zip(minusMapped.snapshot).map { case (left, right) =>
      (left - right) / (2.0 * epsilon)
    }
    assertVectorClose(analytic.snapshot, numeric, 3e-8)

    val forceValues = Array(
      0.7, -0.2, 0.4,
      -0.3, 0.8, -0.1,
      0.5, 0.2, -0.6,
      -0.4, 0.1, 0.9
    )
    val forces = vectorBatch(fixture.fixed, forceValues)
    val transpose = new Array[Double](model.parameterCount)
    pe(model.vjp(state, points, forces, transpose, workspace))
    assertEqualsDouble(dot(analytic.snapshot, forceValues.toVector), dot(direction.toVector, transpose.toVector), 3e-12)

  test("one fixed-world directional image derivative drives every PE coefficient column"):
    val fixture = modelFixture()
    val model = fixture.model
    val point = Vector(1.2, -0.7, 2.1)
    val gradient = Vector(0.3, -0.5, 0.8)
    val jacobian = new Array[Double](model.parameterCount)
    val workspace = model.newWorkspace()
    pe(model.writeIntensityJacobian(fixture.state, point, gradient, jacobian, workspace))

    val points = pointBatch(fixture.moving, point.toArray)
    (0 until model.parameterCount).foreach { parameter =>
      val direction = Array.fill(model.parameterCount)(0.0)
      direction(parameter) = 1.0
      val displacement = output(fixture.fixed, 1)
      pe(model.jvp(fixture.state, points, direction, displacement, workspace))
      assertEqualsDouble(jacobian(parameter), dot(gradient, displacement.snapshot), 3e-13)

      val epsilon = 1e-6
      val plus = pe(model.propose(fixture.state, direction.map(_ * epsilon)))._1
      val minus = pe(model.propose(fixture.state, direction.map(_ * -epsilon)))._1
      val plusMapped = output(fixture.fixed, 1)
      val minusMapped = output(fixture.fixed, 1)
      pe(model.map(plus, points, plusMapped, workspace))
      pe(model.map(minus, points, minusMapped, workspace))
      val numeric = (dot(gradient, plusMapped.snapshot) - dot(gradient, minusMapped.snapshot)) /
        (2.0 * epsilon)
      assertEqualsDouble(jacobian(parameter), numeric, 2e-9)
    }

  test("magnitude gradient and bending prior value gradient and curvature agree independently"):
    val fixture = modelFixture()
    val model = fixture.model
    val state = fixture.state
    val evaluated = pe(model.prior(state))
    assert(evaluated.value > 0.0)
    val epsilon = 1e-6
    (0 until model.fieldParameterCount).foreach { field =>
      val parameter = model.poseParameterCount + field
      val direction = Array.fill(model.parameterCount)(0.0)
      direction(parameter) = epsilon
      val plus = pe(model.propose(state, direction))._1
      direction(parameter) = -epsilon
      val minus = pe(model.propose(state, direction))._1
      val numericGradient = (pe(model.prior(plus)).value - pe(model.prior(minus)).value) / (2.0 * epsilon)
      assertEqualsDouble(evaluated.gradient(parameter), numericGradient, 2e-10)
      val diagonal = evaluated.curvatureUpper(PackedSymmetric.index(parameter, parameter))
      assert(diagonal > 0.0, s"magnitude precision must anchor field mode $field")
      val numericCurvature = (
        pe(model.prior(plus)).gradient(parameter) - pe(model.prior(minus)).gradient(parameter)
      ) / (2.0 * epsilon)
      assertEqualsDouble(diagonal, numericCurvature, 3e-9)
    }
    (0 until model.poseParameterCount).foreach(index => assertEquals(evaluated.gradient(index), 0.0))

  test("default coefficient policy admits the coarse 32-mode field and removes no rigid-incompatible variation"):
    val moving = geometry(Frame.named[D3]("pe-count-moving"))
    val fixed = geometry(Frame.named[D3]("pe-count-fixed"))
    val rigidModel = rigidModelValue(
      RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(0.0, 0.0, 0.0)
    )
    val basis = spectralBasis(maximumFrequency = 2, samplesPerAxis = 7)
    assertEquals(basis.nominalSize, 32)
    val config = pe(PeFieldModelConfig.create())
    assertEquals(config.recommendedInitialCoefficients, 64)
    val direction = pe(
      PhaseEncodingDirection3.resolve(
        Vector(AcquisitionPhaseEncoding3(0, 1)),
        geometry(Affine.fromRowMajor[D3](Vector(
          1.0, 0.2, 0.0, 0.0,
          0.1, 1.0, 0.0, 0.0,
          0.0, 0.0, 1.0, 0.0,
          0.0, 0.0, 0.0, 1.0
        )))
      )
    )
    val model = pe(
      PeFieldModel3.compile[moving.type, fixed.type](
        rigidModel,
        basis,
        direction,
        SpectralPriorWeights(0.2, 0.4, 0.03),
        config
      )
    )
    assertEquals(model.nominalFieldParameterCount, 32)
    assertEquals(model.effectiveFieldParameterCount, 32)
    assertEquals(model.gaugeConventionId, "pe-fixed-source-grid")

  private final case class Fixture[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: Moving,
      fixed: Fixed,
      model: PeFieldModel3[Moving, Fixed],
      state: PeFieldState3[Moving, Fixed]
  )

  private def modelFixture(): Fixture[? <: Frame[D3], ? <: Frame[D3]] =
    val moving = geometry(Frame.named[D3]("pe-model-moving"))
    val fixed = geometry(Frame.named[D3]("pe-model-fixed"))
    val rigidModel = rigidModelValue(
      RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(0.3, -0.4, 0.2)
    )
    val basis = spectralBasis(1, 5, Vector(IntegerWave3(1, 0, 0)))
    val config = pe(PeFieldModelConfig.create(2, 8, 2))
    val indexToWorld = geometry(Affine.fromRowMajor[D3](Vector(
      1.0, 0.3, 0.0, 2.0,
      0.2, 1.4, 0.1, -1.0,
      0.0, 0.2, 1.1, 0.5,
      0.0, 0.0, 0.0, 1.0
    )))
    val peDirection = pe(
      PhaseEncodingDirection3.resolve(Vector(AcquisitionPhaseEncoding3(1, -1)), indexToWorld)
    )
    val model = pe(
      PeFieldModel3.compile[moving.type, fixed.type](
        rigidModel,
        basis,
        peDirection,
        SpectralPriorWeights(0.7, 1.1, 0.08),
        config
      )
    )
    val angle = 0.23
    val cosine = math.cos(angle)
    val sine = math.sin(angle)
    val pose = rigid(
      Rigid3.fromAffine[moving.type, fixed.type](moving, fixed)(
        geometry(Affine.fromRowMajor[D3](Vector(
          cosine, -sine, 0.0, 0.6,
          sine, cosine, 0.0, -0.3,
          0.0, 0.0, 1.0, 0.2,
          0.0, 0.0, 0.0, 1.0
        )))
      )
    )
    val field = basis.coefficientState(Vector(0.18, -0.11)).fold(error => fail(error.message), identity)
    Fixture[moving.type, fixed.type](moving, fixed, model, PeFieldState3(pose, field))

  private val samplePoints = Array(
    -1.0, 2.0, 0.5,
    3.0, -0.4, 2.1,
    0.2, 1.7, -2.0,
    4.1, 0.3, 1.2
  )

  private def spectralBasis(
      maximumFrequency: Int,
      samplesPerAxis: Int,
      waves: Vector[IntegerWave3] = Vector.empty
  ): PhysicalSpectralBasis3 =
    val domain = PhysicalSpectralDomain3.create(
      originMm = Vector(-3.0, 2.0, 5.0),
      axes = Vector(
        Vector(0.8, 0.6, 0.0),
        Vector(-0.6, 0.8, 0.0),
        Vector(0.0, 0.0, 1.0)
      ),
      periodsMm = Vector(12.0, 14.0, 16.0),
      paddingMm = Vector(2.0, 2.0, 2.0),
      extensionId = "periodic-oblique-r3-v1"
    ).fold(error => fail(error.message), identity)
    val points = (for
      i <- 0 until samplesPerAxis
      j <- 0 until samplesPerAxis
      k <- 0 until samplesPerAxis
    yield
      val local = Vector(
        domain.periodsMm(0) * i.toDouble / samplesPerAxis,
        domain.periodsMm(1) * j.toDouble / samplesPerAxis,
        domain.periodsMm(2) * k.toDouble / samplesPerAxis
      )
      Vector.tabulate(3)(world =>
        domain.originMm(world) + domain.axes.indices.map(axis => domain.axes(axis)(world) * local(axis)).sum
      )
    ).toVector
    val gauge = GeometryGaugeMeasure3.create(
      "pe-fixed-source-grid",
      points,
      Vector.fill(points.length)(1.0)
    ).fold(error => fail(error.message), identity)
    val result =
      if waves.isEmpty then PhysicalSpectralBasis3.lowFrequency(domain, maximumFrequency, gauge)
      else PhysicalSpectralBasis3.fromWavevectors(domain, waves, gauge)
    result.fold(error => fail(error.message), identity)

  private def pointBatch[F <: Frame[D3]](frame: F, packed: Array[Double]): WorldPointBatch3[F] =
    WorldPointBatch3.create(frame, packed).fold(error => fail(error.message), identity)

  private def vectorBatch[F <: Frame[D3]](frame: F, packed: Array[Double]): WorldVectorBatch3[F] =
    WorldVectorBatch3.create(frame, packed).fold(error => fail(error.message), identity)

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
