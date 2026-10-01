package reframe4s.flashalign

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError

final class PeFieldOptimizerSuite extends munit.FunSuite:
  test("representable PE truth is recovered with certified inverse and complete work evidence"):
    val fixture = makeFixture("representable")
    val truth = state(fixture, Vector(0.24, -0.16))
    val initial = pe(fixture.model.zeroState(fixture.identityPose))
    val objective = new AnalyticPeObjective(fixture, truth, Set.empty)
    val optimizer = optimizerFor(fixture, objective)
    val result = optimizer.fit(initial, fixture.identityPose)

    val fitted = result.nonlinearState.getOrElse(fail(s"missing nonlinear state: ${result.status}: ${result.detail}; ${result.work}"))
    assert(
      result.status == PeFieldFitStatus.Converged || result.status == PeFieldFitStatus.IterationLimit,
      s"unexpected fit status ${result.status}: ${result.detail}"
    )
    assertVectorClose(fitted.field.coefficientsMm, truth.field.coefficientsMm, 4e-2)
    assert(result.dataObjective.isFinite)
    assert(result.priorObjective.isFinite)
    assert(result.selectionObjective.isFinite)
    assertEquals(result.nominalCoefficientCount, 2)
    assertEquals(result.effectiveCoefficientRank, 2)
    val certificate = result.certificate.getOrElse(fail("missing PE certificate"))
    assert(certificate.valid)
    assert(certificate.certifiedJacobianMargin > 0.0)
    assert(result.work.linearizations > 0)
    assert(result.work.poseBlockFactorizations > 0)
    assert(result.work.solverProducts > 0L)
    assert(result.work.trialEvaluations > 0)
    assert(result.work.trialPatchesEvaluated > 0L)
    assert(result.work.certificateChecks >= result.work.trialEvaluations)
    assert(result.work.imageInterpolationsAtLinearization > 0L)
    assertEquals(result.work.imageInterpolationsInKrylovProducts, 0L)
    assertEquals(result.work.imageGradientEvaluationsInKrylovProducts, 0L)
    assertEquals(result.fieldUnits, "millimetres")
    assert(result.fieldSemantics.contains("not an off-resonance field"))
    assert(!result.dropoutRecoveryClaimed)

    val geometry = GeometryPointOperator3.peField(fixture.model, fixture.geometryConfig)
    val mapped = output(fixture.fixed, fixture.probes.size)
    val restored = GeometryOutputBuffer3.allocate(fixture.moving, fixture.probes.size)
      .fold(error => fail(error.message), identity)
    geometryResult(geometry.map(fitted, fixture.probes, mapped, geometry.newWorkspace()))
    val mappedPoints = WorldPointBatch3.create(fixture.fixed, mapped.snapshot.toArray)
      .fold(error => fail(error.message), identity)
    geometryResult(geometry.inverseMap(fitted, mappedPoints, restored, geometry.newWorkspace()))
    val inverseResidual = rmsDifference(restored.snapshot, fixture.probes.packed.toVector)
    assert(inverseResidual <= fixture.geometryConfig.inverse.residualToleranceMm * 1.1)

  test("zero PE coefficients reproduce the rigid mapping exactly"):
    val fixture = makeFixture("zero")
    val zero = pe(fixture.model.zeroState(fixture.identityPose))
    val peGeometry = GeometryPointOperator3.peField(fixture.model, fixture.geometryConfig)
    val rigidGeometry = GeometryPointOperator3.rigid(fixture.model.rigid)
    val peMapped = output(fixture.fixed, fixture.probes.size)
    val rigidMapped = output(fixture.fixed, fixture.probes.size)
    geometryResult(peGeometry.map(zero, fixture.probes, peMapped, peGeometry.newWorkspace()))
    geometryResult(rigidGeometry.map(fixture.identityPose, fixture.probes, rigidMapped, rigidGeometry.newWorkspace()))
    assertVectorClose(peMapped.snapshot, rigidMapped.snapshot, 0.0)

  test("no-information input falls back without physical overclaim"):
    val fixture = makeFixture("no-information")
    val initial = pe(fixture.model.zeroState(fixture.identityPose))
    val objective = new AnalyticPeObjective(fixture, initial, fixture.patchIndices.indices.toSet)
    val result = optimizerFor(fixture, objective).fit(initial, fixture.identityPose)

    assertEquals(result.status, PeFieldFitStatus.InsufficientInformation)
    assertEquals(result.nonlinearState, None)
    assertEquals(result.validatedRigidFallback.operator.rowMajor, fixture.identityPose.operator.rowMajor)
    assert(result.dataObjective.isNaN)
    assert(result.priorObjective.isNaN)
    assertEquals(result.certificate, None)
    assert(result.fieldSemantics.contains("not an off-resonance field"))
    assert(!result.dropoutRecoveryClaimed)
    assertEquals(result.work.trialEvaluations, 0)

  test("dropout stays a bounded outlier cost and never becomes recovered signal"):
    val fixture = makeFixture("dropout")
    val truth = state(fixture, Vector(0.18, -0.10))
    val initial = pe(fixture.model.zeroState(fixture.identityPose))
    val dropout = Set(1, 4)
    val objective = new AnalyticPeObjective(fixture, truth, dropout)
    val result = optimizerFor(fixture, objective).fit(initial, fixture.identityPose)

    assert(result.work.linearizations > 0)
    assert(!result.dropoutRecoveryClaimed)
    assert(result.fieldSemantics.contains("anatomically guided inverse displacement"))
    result.nonlinearState.foreach { fitted =>
      val evaluation = objective.completeDataObjective(fitted)
      assert(evaluation.isFinite)
      assert(evaluation >= dropout.size.toDouble * fixture.patchConfig.outlierCost)
    }

  test("out-of-basis PE distortion remains measurable instead of being labeled exact"):
    val fixture = makeFixture("out-of-basis")
    val rigidTruth = pe(fixture.model.zeroState(fixture.identityPose))
    val omitted = (x: Double, y: Double, z: Double) =>
      val _ = x
      0.45 * math.sin(0.61 * y) * math.cos(0.37 * z)
    val objective = new AnalyticPeObjective(fixture, rigidTruth, Set.empty, omitted)
    val result = optimizerFor(fixture, objective).fit(rigidTruth, fixture.identityPose)

    val fitted = result.nonlinearState.getOrElse(fail(s"out-of-basis case produced no validated model: ${result.detail}"))
    val geometry = GeometryPointOperator3.peField(fixture.model, fixture.geometryConfig)
    val mapped = output(fixture.fixed, fixture.probes.size)
    geometryResult(geometry.map(fitted, fixture.probes, mapped, geometry.newWorkspace()))
    val actual = mapped.snapshot
    val expected = fixture.probes.packed.grouped(3).flatMap { point =>
      Vector(point(0), point(1) + omitted(point(0), point(1), point(2)), point(2))
    }.toVector
    assert(rmsDifference(actual, expected) > 0.05)
    assert(result.selectionObjective.isFinite)
    assert(!result.dropoutRecoveryClaimed)
    assert(result.fieldSemantics.contains("not an off-resonance field"))

  private final case class Fixture[Moving <: Frame[D3], Fixed <: Frame[D3]](
      moving: Moving,
      fixed: Fixed,
      model: PeFieldModel3[Moving, Fixed],
      identityPose: Rigid3[Moving, Fixed],
      geometryConfig: PeGeometryConfig,
      probes: WorldPointBatch3[Moving],
      patchIndices: Vector[Vector[Int]],
      patchConfig: PatchObjectiveConfig
  )

  private final class AnalyticPeObjective[Moving <: Frame[D3], Fixed <: Frame[D3]](
      fixture: Fixture[Moving, Fixed],
      truth: PeFieldState3[Moving, Fixed],
      invalidPatchIds: Set[Int],
      unmodeledTargetDisplacement: (Double, Double, Double) => Double = (_, _, _) => 0.0
  ) extends PeProjectedPatchObjective3[Moving, Fixed]:
    private val geometry = GeometryPointOperator3.peField(fixture.model, fixture.geometryConfig)
    private val targetValues = targetValuesAt(truth)
    private val movingPatches = fixture.patchIndices.map(indices =>
      patchResult(PatchObjective.prepareMoving(indices.map(targetValues).toArray, fixture.patchConfig))
    )
    private val sampleId = PatchSampleSetId(0x50654669656c64L, 0x4f7074696d697a65L, PatchSampleRole.Optimization, 0, 17L)
    private var stateOrdinal = 0L

    def linearize(
        candidate: PeFieldState3[Moving, Fixed],
        suppliedGeometry: GeometryPointOperator3[PeFieldState3[Moving, Fixed], Moving, Fixed]
    ): Either[String, PeFieldObjectiveLinearization3[Moving, Fixed]] =
      if suppliedGeometry.modelId != geometry.modelId || suppliedGeometry.basisId != geometry.basisId then
        Left("optimizer supplied the wrong PE geometry")
      else
        val (values, gradients) = valuesAt(candidate)
        buildPatches(values).flatMap { evaluated =>
          stateOrdinal += 1L
          MatrixFreePatchLinearization3.create(
            stateOrdinal,
            sampleId,
            suppliedGeometry,
            candidate,
            fixture.probes,
            values,
            gradients,
            evaluated.map(_._1),
            basisTableBytes = fixture.probes.size.toLong * fixture.model.fieldParameterCount.toLong * 8L
          ).left.map(_.message).flatMap { cache =>
            diagonal(cache).map { dataDiagonal =>
              PeFieldObjectiveLinearization3(
                evaluated.map(_._2.loss).sum,
                cache,
                dataDiagonal,
                evaluated.count(_._2.active),
                evaluated.count(!_._2.active)
              )
            }
          }
        }

    def trialDataObjective(
        candidate: PeFieldState3[Moving, Fixed],
        dataAcceptanceLimit: Double
    ): Either[String, PeTrialEvaluation] =
      val values = valuesAt(candidate)._1
      var total = 0.0
      var patch = 0
      while patch < fixture.patchIndices.length do
        val evaluated = evaluatePatch(patch, values)
        evaluated match
          case Left(detail) => return Left(detail)
          case Right(value) => total += value.loss
        patch += 1
        if total > dataAcceptanceLimit then
          return Right(PeTrialEvaluation(PeTrialDisposition.RejectedEarly, total, patch, fixture.patchIndices.length))
      Right(PeTrialEvaluation(PeTrialDisposition.Complete, total, patch, fixture.patchIndices.length))

    def selectionDataObjective(candidate: PeFieldState3[Moving, Fixed]): Either[String, Double] =
      Right(completeDataObjective(candidate))

    def completeDataObjective(candidate: PeFieldState3[Moving, Fixed]): Double =
      val values = valuesAt(candidate)._1
      fixture.patchIndices.indices.map(index => evaluatePatch(index, values).fold(detail => fail(detail), _.loss)).sum

    private def valuesAt(candidate: PeFieldState3[Moving, Fixed]): (Array[Double], Array[Double]) =
      val mapped = output(fixture.fixed, fixture.probes.size)
      geometryResult(geometry.map(candidate, fixture.probes, mapped, geometry.newWorkspace()))
      val coordinates = mapped.snapshot
      val values = new Array[Double](fixture.probes.size)
      val gradients = new Array[Double](fixture.probes.size * 3)
      var point = 0
      while point < fixture.probes.size do
        val offset = point * 3
        values(point) = analyticValue(coordinates(offset), coordinates(offset + 1), coordinates(offset + 2))
        val gradient = analyticGradient(coordinates(offset), coordinates(offset + 1), coordinates(offset + 2))
        gradients(offset) = gradient(0)
        gradients(offset + 1) = gradient(1)
        gradients(offset + 2) = gradient(2)
        point += 1
      values -> gradients

    private def targetValuesAt(candidate: PeFieldState3[Moving, Fixed]): Array[Double] =
      val mapped = output(fixture.fixed, fixture.probes.size)
      geometryResult(geometry.map(candidate, fixture.probes, mapped, geometry.newWorkspace()))
      val coordinates = mapped.snapshot
      Array.tabulate(fixture.probes.size) { point =>
        val offset = point * 3
        val x = coordinates(offset)
        val y = coordinates(offset + 1)
        val z = coordinates(offset + 2)
        analyticValue(x, y + unmodeledTargetDisplacement(x, y, z), z)
      }

    private def buildPatches(values: Array[Double]): Either[String, Vector[(CachedProjectedPatch3, PatchObjectiveValue)]] =
      fixture.patchIndices.indices.foldLeft[Either[String, Vector[(CachedProjectedPatch3, PatchObjectiveValue)]]](Right(Vector.empty)) {
        case (acc, patchId) =>
          for
            prior <- acc
            evaluated <- evaluatePatch(patchId, values)
          yield
            val indices = fixture.patchIndices(patchId)
            val fixed = indices.map(values).toArray
            val normalized = normalize(fixed).getOrElse(movingPatches(patchId).normalizedCopy.toVector -> 1.0)
            prior :+ (
              CachedProjectedPatch3(
                indices,
                movingPatches(patchId).normalizedCopy.toVector,
                normalized._1,
                normalized._2,
                evaluated.correlation,
                objectiveWeight = 1.0,
                evaluated.posterior.inlierWeight,
                evaluated.posterior.signedWeight,
                evaluated.invalidReason
              ) -> evaluated
            )
      }

    private def evaluatePatch(patchId: Int, values: Array[Double]): Either[String, PatchObjectiveValue] =
      PatchObjective.evaluate(
        movingPatches(patchId),
        fixture.patchIndices(patchId).map(values).toArray,
        completeInterpolationSupport = !invalidPatchIds.contains(patchId),
        fixture.patchConfig
      ).left.map(_.message)

    private def diagonal(
        cache: MatrixFreePatchLinearization3[PeFieldState3[Moving, Fixed], Moving, Fixed]
    ): Either[String, Vector[Double]] =
      val workspace = cache.newWorkspace()
      val input = new Array[Double](fixture.model.parameterCount)
      val product = new Array[Double](fixture.model.parameterCount)
      val result = new Array[Double](fixture.model.parameterCount)
      var parameter = 0
      var failure = Option.empty[String]
      while parameter < result.length && failure.isEmpty do
        java.util.Arrays.fill(input, 0.0)
        input(parameter) = 1.0
        GeometryDirection3.create(geometry.modelId, geometry.basisId, input) match
          case Left(error) => failure = Some(error.message)
          case Right(direction) =>
            cache.curvatureProduct(direction, product, workspace) match
              case Left(error) => failure = Some(error.message)
              case Right(()) => result(parameter) = math.max(0.0, product(parameter))
        parameter += 1
      failure.toLeft(result.toVector)

  private def makeFixture(label: String): Fixture[? <: Frame[D3], ? <: Frame[D3]] =
    val moving = frame(Frame.named[D3](s"pe-optimizer-moving-$label"))
    val fixed = frame(Frame.named[D3](s"pe-optimizer-fixed-$label"))
    val identityPose = rigid(
      Rigid3.fromAffine[moving.type, fixed.type](moving, fixed)(Affine.identity[D3])
    )
    val rigidModel = rigidModelValue(RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(0.0, 0.0, 0.0))
    val domain = PhysicalSpectralDomain3.create(
      Vector(-12.0, -12.0, -12.0),
      Vector(Vector(1.0, 0.0, 0.0), Vector(0.0, 1.0, 0.0), Vector(0.0, 0.0, 1.0)),
      Vector(24.0, 24.0, 24.0),
      Vector(3.0, 3.0, 3.0),
      s"pe-optimizer-periodic-$label-v1"
    ).fold(error => fail(error.message), identity)
    val gaugePoints = (for
      x <- Vector(-9.0, -3.0, 3.0, 9.0)
      y <- Vector(-9.0, -3.0, 3.0, 9.0)
      z <- Vector(-9.0, -3.0, 3.0, 9.0)
    yield Vector(x, y, z)).toVector
    val gauge = GeometryGaugeMeasure3.create(
      s"pe-optimizer-gauge-$label-v1",
      gaugePoints,
      Vector.fill(gaugePoints.length)(1.0)
    ).fold(error => fail(error.message), identity)
    val basis = PhysicalSpectralBasis3.fromWavevectors(domain, Vector(IntegerWave3(1, 0, 0)), gauge)
      .fold(error => fail(error.message), identity)
    val direction = pe(
      PhaseEncodingDirection3.resolve(
        Vector(AcquisitionPhaseEncoding3(1, 1)),
        Affine.identity[D3]
      )
    )
    val model = pe(
      PeFieldModel3.compile(
        rigidModel,
        basis,
        direction,
        SpectralPriorWeights(1e-6, 1e-6, 1e-7),
        pe(PeFieldModelConfig.create(2, 8, 2))
      )
    )
    val sourceDomain = PeWorldDomain3.fromSpectralDomain(s"pe-source-$label", domain)
      .fold(error => fail(error.message), identity)
    val fixedDomain = PeWorldDomain3.create(
      s"pe-fixed-$label",
      Vector(-20.0, -20.0, -20.0),
      domain.axes,
      Vector(0.0, 0.0, 0.0),
      Vector(40.0, 40.0, 40.0)
    ).fold(error => fail(error.message), identity)
    val geometryConfig = PeGeometryConfig(
      PeCertificateConfig.create(0.2, 5.0).fold(error => fail(error.message), identity),
      PeInverseConfig.create(1e-8, 64).fold(error => fail(error.message), identity),
      sourceDomain,
      fixedDomain
    )
    val centers = Vector(
      Vector(-7.0, -7.0, -6.0), Vector(-6.0, 5.0, -3.0),
      Vector(-2.0, -4.0, 6.0), Vector(1.0, 7.0, 4.0),
      Vector(5.0, -6.0, 2.0), Vector(7.0, 3.0, -5.0),
      Vector(3.0, 1.0, 7.0), Vector(-5.0, 7.0, 7.0)
    )
    val offsets = for
      x <- Vector(-0.8, 0.0, 0.8)
      y <- Vector(-0.8, 0.0, 0.8)
      z <- Vector(-0.8, 0.0, 0.8)
    yield Vector(x, y, z)
    val points = centers.flatMap(center => offsets.map(offset => center.zip(offset).map(_ + _)))
    val packed = points.flatten.toArray
    val probes = WorldPointBatch3.create[moving.type](moving, packed).fold(error => fail(error.message), identity)
    val patchIndices = centers.indices.map(patch => Vector.tabulate(offsets.length)(sample => patch * offsets.length + sample)).toVector
    val patchConfig = PatchObjectiveConfig.create(1.0, 0.5, 1e-4, 1e-10)
      .fold(error => fail(error.message), identity)
    Fixture(moving, fixed, model, identityPose, geometryConfig, probes, patchIndices, patchConfig)

  private def optimizerFor[Moving <: Frame[D3], Fixed <: Frame[D3]](
      fixture: Fixture[Moving, Fixed],
      objective: PeProjectedPatchObjective3[Moving, Fixed]
  ): PeFieldOptimizer3[Moving, Fixed] =
    val solver = GalePcgConfig.create(1e-8, 80).fold(error => fail(error.message), identity)
    val config = PeFieldOptimizerConfig.create(
      maximumLinearizations = 35,
      maximumTrialAttempts = 8,
      initialDamping = 1e-2,
      trustRadiusRmsMm = 0.8,
      maximumProbeDisplacementMm = 1.5,
      objectiveTolerance = 1e-10,
      stepToleranceRmsMm = 1e-6,
      solver = solver
    ).fold(error => fail(error.message), identity)
    PeFieldOptimizer3.compile(fixture.model, fixture.geometryConfig, objective, fixture.probes, config)
      .fold(error => fail(error.message), identity)

  private def state[Moving <: Frame[D3], Fixed <: Frame[D3]](
      fixture: Fixture[Moving, Fixed],
      coefficients: Vector[Double]
  ): PeFieldState3[Moving, Fixed] =
    PeFieldState3(
      fixture.identityPose,
      fixture.model.basis.coefficientState(coefficients).fold(error => fail(error.message), identity)
    )

  private def analyticValue(x: Double, y: Double, z: Double): Double =
    math.sin(0.17 * x) + math.cos(0.13 * y) + 0.3 * math.sin(0.11 * z) + 0.002 * x * y + 0.001 * y * z

  private def analyticGradient(x: Double, y: Double, z: Double): Vector[Double] =
    Vector(
      0.17 * math.cos(0.17 * x) + 0.002 * y,
      -0.13 * math.sin(0.13 * y) + 0.002 * x + 0.001 * z,
      0.033 * math.cos(0.11 * z) + 0.001 * y
    )

  private def normalize(values: Array[Double]): Option[(Vector[Double], Double)] =
    val origin = values(0)
    val shifted = values.map(_ - origin)
    val mean = shifted.sum / shifted.length.toDouble
    val centered = shifted.map(_ - mean)
    val norm = math.sqrt(centered.map(value => value * value).sum)
    Option.when(norm > 0.0)(centered.map(_ / norm).toVector -> norm)

  private def rmsDifference(left: Vector[Double], right: Vector[Double]): Double =
    math.sqrt(left.indices.map(index => math.pow(left(index) - right(index), 2.0)).sum / (left.length / 3).toDouble)

  private def assertVectorClose(actual: Vector[Double], expected: Vector[Double], tolerance: Double): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) => assertEqualsDouble(left, right, tolerance) }

  private def frame(value: Either[GeometryError, Frame[D3]]): Frame[D3] = value.fold(error => fail(error.message), identity)
  private def rigid[A](value: Either[RigidError, A]): A = value.fold(error => fail(error.message), identity)
  private def rigidModelValue[A](value: Either[RigidModelError, A]): A = value.fold(error => fail(error.message), identity)
  private def pe[A](value: Either[PeFieldError, A]): A = value.fold(error => fail(error.message), identity)
  private def patchResult[A](value: Either[PatchObjectiveError, A]): A = value.fold(error => fail(error.message), identity)
  private def geometryResult[A](value: Either[GeometryOperatorError, A]): A = value.fold(error => fail(error.message), identity)
  private def output[F <: Frame[D3]](frame: F, size: Int): GeometryOutputBuffer3[F] =
    GeometryOutputBuffer3.allocate(frame, size).fold(error => fail(error.message), identity)
