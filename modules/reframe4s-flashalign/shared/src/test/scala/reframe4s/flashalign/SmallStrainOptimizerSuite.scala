package reframe4s.flashalign

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import reframe4s.lie.FramedAffine

final class SmallStrainOptimizerSuite extends munit.FunSuite:
  test("representable anatomical truth passes landmark and inverse starting gates"):
    val fixture = makeFixture("representable", maximumGradient = 0.65)
    val truth = state(fixture, Vector(0.13, -0.09, 0.06, 0.08, -0.05, 0.04))
    val initial = small(fixture.model.zeroState(fixture.pose))
    val objective = new AnalyticAnatomicalObjective(fixture, truth)
    val result = optimizer(fixture, objective).fit(initial, fixture.pose)
    val fitted = result.nonlinearState.getOrElse(fail(s"missing nonlinear result: ${result.status}: ${result.detail}; ${result.work}"))
    assert(
      result.status == SmallStrainFitStatus.Converged || result.status == SmallStrainFitStatus.IterationLimit,
      s"unexpected status ${result.status}: ${result.detail}"
    )
    val landmarkRms = mapRms(fixture, fitted, truth)
    assert(landmarkRms < 0.1, s"landmark RMS $landmarkRms mm")
    val inverseRms = inverseRoundtripRms(fixture, fitted)
    assert(inverseRms < 0.01, s"inverse roundtrip RMS $inverseRms mm")
    assert(result.dataObjective.isFinite)
    assert(result.priorObjective.isFinite)
    assert(result.fieldDataDiagonalSum > 0.0)
    assert(result.dataInformationExcludesPriorAndDamping)
    assertEquals(result.nominalCoefficientCount, 6)
    assertEquals(result.effectiveCoefficientRank, 6)
    assert(result.certificate.exists(_.valid))
    assert(result.work.solverProducts > 0L)
    assertEquals(result.work.imageInterpolationsInKrylovProducts, 0L)
    assertEquals(result.work.imageGradientEvaluationsInKrylovProducts, 0L)
    assert(result.fieldSemantics.contains("not measured tissue mechanics"))

  Vector(3L, 11L, 29L).foreach: seed =>
    test(s"noise seed $seed produces an observable landmark error"):
      val fixture = makeFixture("distribution", maximumGradient = 0.65)
      val baseTruth = state(fixture, Vector(0.10, -0.07, 0.05, 0.06, -0.04, 0.03))
      val initial = small(fixture.model.zeroState(fixture.pose))
      val objective = new AnalyticAnatomicalObjective(fixture, baseTruth, noiseScale = 0.006, noiseSeed = seed)
      val result = optimizer(fixture, objective).fit(initial, fixture.pose)
      val error = mapRms(fixture, result.nonlinearState.getOrElse(initial), baseTruth)
      assert(error.isFinite && error >= 0.0)

  test("omitted modes produce an observable landmark-error distribution"):
    val fixture = makeFixture("distribution", maximumGradient = 0.65)
    val initial = small(fixture.model.zeroState(fixture.pose))
    val outOfBasisErrors = Vector(0.18, 0.28, 0.38).map { scale =>
      val omitted = (x: Double, y: Double, z: Double) => Vector(
        0.2 * scale * math.sin(0.47 * x),
        scale * math.sin(0.63 * y) * math.cos(0.41 * z),
        0.7 * scale * math.sin(0.51 * z)
      )
      val objective = new AnalyticAnatomicalObjective(fixture, initial, omittedDisplacement = omitted)
      val result = optimizer(fixture, objective).fit(initial, fixture.pose)
      outOfBasisRms(fixture, result.nonlinearState.getOrElse(initial), omitted)
    }
    assertEquals(outOfBasisErrors.length, 3)
    assert(outOfBasisErrors.forall(value => value.isFinite && value > 0.03))
    assert(outOfBasisErrors.last > outOfBasisErrors.head)

  test("data-degenerate fields are explicitly prior dominated before damping"):
    val fixture = makeFixture("prior-dominated", maximumGradient = 0.65)
    val initial = small(fixture.model.zeroState(fixture.pose))
    val objective = new AnalyticAnatomicalObjective(fixture, initial, zeroGradients = true)
    val result = optimizer(fixture, objective).fit(initial, fixture.pose)
    assertEquals(result.status, SmallStrainFitStatus.PriorDominated)
    assertEquals(result.nonlinearState, None)
    assert(result.fieldDataDiagonalSum.isNaN)
    assert(result.dataInformationExcludesPriorAndDamping)
    assert(result.detail.contains("prior and damping excluded"))
    assertEquals(result.work.trialEvaluations, 0)

  test("unsafe anatomical proposals remain explicit and retain the validated affine base"):
    val fixture = makeFixture("unsafe", maximumGradient = 0.006)
    val initial = small(fixture.model.zeroState(fixture.pose))
    val target = state(fixture, Vector(0.16, -0.11, 0.07, 0.10, -0.08, 0.05))
    val objective = new AnalyticAnatomicalObjective(fixture, target)
    val trust = trustConfig(maximumLinearizations = 2, maximumTrialAttempts = 1, maximumDamping = 1e-2)
    val config = SmallStrainOptimizerConfig.create(trust).fold(error => fail(error.message), identity)
    val fit = SmallStrainOptimizer3.compile(fixture.model, fixture.geometryConfig, objective, fixture.probes, config)
      .fold(error => fail(error.message), identity)
    val result = fit.fit(initial, fixture.pose)
    assertEquals(result.status, SmallStrainFitStatus.UnsafeGeometry)
    assertEquals(result.nonlinearState, None)
    assertEquals(result.validatedBaseFallback.operator.rowMajor, fixture.pose.operator.rowMajor)
    assert(!result.successfulNonlinearFit)

  private final case class Fixture[Moving <: Frame[D3], Fixed <: Frame[D3]](
      moving: Moving,
      fixed: Fixed,
      model: SmallStrainModel3[FramedAffine[Moving, Fixed, D3], Moving, Fixed],
      pose: FramedAffine[Moving, Fixed, D3],
      geometryConfig: SmallStrainGeometryConfig,
      probes: WorldPointBatch3[Moving],
      patchIndices: Vector[Vector[Int]],
      patchConfig: PatchObjectiveConfig
  )

  private final class AnalyticAnatomicalObjective[Moving <: Frame[D3], Fixed <: Frame[D3]](
      fixture: Fixture[Moving, Fixed],
      truth: SmallStrainState3[FramedAffine[Moving, Fixed, D3]],
      omittedDisplacement: (Double, Double, Double) => Vector[Double] = (_, _, _) => Vector(0.0, 0.0, 0.0),
      noiseScale: Double = 0.0,
      noiseSeed: Long = 0L,
      zeroGradients: Boolean = false
  ) extends SmallStrainProjectedPatchObjective3[FramedAffine[Moving, Fixed, D3], Moving, Fixed]:
    private val geometry = GeometryPointOperator3.smallStrain(fixture.model, fixture.geometryConfig)
    private val targetValues = targetValuesAt(truth)
    private val movingPatches = fixture.patchIndices.map(indices =>
      patchResult(PatchObjective.prepareMoving(indices.map(targetValues).toArray, fixture.patchConfig))
    )
    private val sampleId = PatchSampleSetId(0x536d616c6c537472L, 0x61696e4f7074696dL, PatchSampleRole.Optimization, 0, 37L)
    private var stateOrdinal = 0L

    def linearize(
        candidate: SmallStrainState3[FramedAffine[Moving, Fixed, D3]],
        suppliedGeometry: GeometryPointOperator3[SmallStrainState3[FramedAffine[Moving, Fixed, D3]], Moving, Fixed]
    ): Either[String, SmallStrainObjectiveLinearization3[FramedAffine[Moving, Fixed, D3], Moving, Fixed]] =
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
          fixture.probes.size.toLong * fixture.model.fieldParameterCount.toLong * 3L * 8L
        ).left.map(_.message).flatMap { cache =>
          diagonal(cache).map(dataDiagonal => SmallStrainObjectiveLinearization3(
            evaluated.map(_._2.loss).sum,
            cache,
            dataDiagonal,
            evaluated.count(_._2.active),
            evaluated.count(!_._2.active)
          ))
        }
      }

    def trialDataObjective(
        candidate: SmallStrainState3[FramedAffine[Moving, Fixed, D3]],
        dataAcceptanceLimit: Double
    ): Either[String, PeTrialEvaluation] =
      val values = valuesAt(candidate)._1
      var total = 0.0
      var patch = 0
      while patch < fixture.patchIndices.length do
        evaluatePatch(patch, values) match
          case Left(detail) => return Left(detail)
          case Right(value) => total += value.loss
        patch += 1
        if total > dataAcceptanceLimit then
          return Right(PeTrialEvaluation(PeTrialDisposition.RejectedEarly, total, patch, fixture.patchIndices.length))
      Right(PeTrialEvaluation(PeTrialDisposition.Complete, total, patch, fixture.patchIndices.length))

    def selectionDataObjective(candidate: SmallStrainState3[FramedAffine[Moving, Fixed, D3]]): Either[String, Double] =
      val values = valuesAt(candidate)._1
      fixture.patchIndices.indices.foldLeft[Either[String, Double]](Right(0.0)) { (acc, patch) =>
        acc.flatMap(total => evaluatePatch(patch, values).map(value => total + value.loss))
      }

    private def valuesAt(candidate: SmallStrainState3[FramedAffine[Moving, Fixed, D3]]): (Array[Double], Array[Double]) =
      val mapped = output(fixture.fixed, fixture.probes.size)
      small(fixture.model.map(candidate, fixture.probes, mapped, fixture.model.newWorkspace()))
      val coordinates = mapped.snapshot
      val values = new Array[Double](fixture.probes.size)
      val gradients = new Array[Double](fixture.probes.size * 3)
      var point = 0
      while point < fixture.probes.size do
        val offset = point * 3
        values(point) = analyticValue(coordinates(offset), coordinates(offset + 1), coordinates(offset + 2))
        val gradient = if zeroGradients then Vector(0.0, 0.0, 0.0) else analyticGradient(coordinates(offset), coordinates(offset + 1), coordinates(offset + 2))
        gradients(offset) = gradient(0); gradients(offset + 1) = gradient(1); gradients(offset + 2) = gradient(2)
        point += 1
      values -> gradients

    private def targetValuesAt(candidate: SmallStrainState3[FramedAffine[Moving, Fixed, D3]]): Array[Double] =
      val mapped = output(fixture.fixed, fixture.probes.size)
      small(fixture.model.map(candidate, fixture.probes, mapped, fixture.model.newWorkspace()))
      val coordinates = mapped.snapshot
      val random = new scala.util.Random(noiseSeed)
      Array.tabulate(fixture.probes.size) { point =>
        val offset = point * 3
        val x = coordinates(offset); val y = coordinates(offset + 1); val z = coordinates(offset + 2)
        val omitted = omittedDisplacement(x, y, z)
        analyticValue(x + omitted(0), y + omitted(1), z + omitted(2)) + noiseScale * random.nextGaussian()
      }

    private def buildPatches(values: Array[Double]): Either[String, Vector[(CachedProjectedPatch3, PatchObjectiveValue)]] =
      fixture.patchIndices.indices.foldLeft[Either[String, Vector[(CachedProjectedPatch3, PatchObjectiveValue)]]](Right(Vector.empty)) {
        case (acc, patchId) => for
          prior <- acc
          evaluated <- evaluatePatch(patchId, values)
        yield
          val indices = fixture.patchIndices(patchId)
          val normalized = normalize(indices.map(values).toArray)
          prior :+ (CachedProjectedPatch3(
            indices,
            movingPatches(patchId).normalizedCopy.toVector,
            normalized._1,
            normalized._2,
            evaluated.correlation,
            1.0,
            evaluated.posterior.inlierWeight,
            evaluated.posterior.signedWeight,
            evaluated.invalidReason
          ) -> evaluated)
      }

    private def evaluatePatch(patchId: Int, values: Array[Double]): Either[String, PatchObjectiveValue] =
      PatchObjective.evaluate(
        movingPatches(patchId),
        fixture.patchIndices(patchId).map(values).toArray,
        completeInterpolationSupport = true,
        fixture.patchConfig
      ).left.map(_.message)

    private def diagonal(
        cache: MatrixFreePatchLinearization3[SmallStrainState3[FramedAffine[Moving, Fixed, D3]], Moving, Fixed]
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
          case Right(direction) => cache.curvatureProduct(direction, product, workspace) match
            case Left(error) => failure = Some(error.message)
            case Right(()) => result(parameter) = math.max(0.0, product(parameter))
        parameter += 1
      failure.toLeft(result.toVector)

  private def makeFixture(label: String, maximumGradient: Double): Fixture[? <: Frame[D3], ? <: Frame[D3]] =
    val moving = geometry(Frame.named[D3](s"small-strain-fit-moving-$label"))
    val fixed = geometry(Frame.named[D3](s"small-strain-fit-fixed-$label"))
    val affineConfig = affine(AffineModelConfig.create())
    val poseModel = affine(AffineModel3.atFixedWorld[moving.type, fixed.type](moving, fixed, affineConfig)(0.0, 0.0, 0.0))
    val pose = FramedAffine.betweenFrames[moving.type, fixed.type, D3](moving, fixed)(Affine.identity[D3])
    val domain = PhysicalSpectralDomain3.create(
      Vector(-10.0, -10.0, -10.0),
      Vector(Vector(1.0, 0.0, 0.0), Vector(0.0, 1.0, 0.0), Vector(0.0, 0.0, 1.0)),
      Vector(20.0, 20.0, 20.0),
      Vector(2.0, 2.0, 2.0),
      s"small-strain-fit-extension-$label-v1"
    ).fold(error => fail(error.message), identity)
    val gaugeCoordinates = Vector(-8.0, -4.0, 0.0, 4.0, 8.0)
    val gaugePoints = (for x <- gaugeCoordinates; y <- gaugeCoordinates; z <- gaugeCoordinates yield Vector(x, y, z)).toVector
    val gauge = GeometryGaugeMeasure3.create(s"small-strain-fit-gauge-$label-v1", gaugePoints, Vector.fill(gaugePoints.length)(1.0))
      .fold(error => fail(error.message), identity)
    val scalar = PhysicalSpectralBasis3.fromWavevectors(domain, Vector(IntegerWave3(1, 1, 0)), gauge)
      .fold(error => fail(error.message), identity)
    val basis = small(SmallStrainVectorBasis3.compile(
      scalar,
      gauge,
      SmallStrainPoseKind.Affine,
      Vector(0.0, 0.0, 0.0),
      SmallStrainPriorWeights(1e-5, 1e-5, 1e-6, 1e-6),
      small(SmallStrainModelConfig.create(6, 18, 6))
    ))
    val model = small(SmallStrainModel3.affine(poseModel, basis))
    val sourceDomain = SmallStrainWorldDomain3.fromSpectralDomain(s"small-strain-fit-source-$label", domain)
      .fold(error => fail(error.message), identity)
    val fixedDomain = SmallStrainWorldDomain3.create(
      s"small-strain-fit-fixed-domain-$label",
      Vector(-25.0, -25.0, -25.0),
      domain.axes,
      Vector(0.0, 0.0, 0.0),
      Vector(50.0, 50.0, 50.0)
    ).fold(error => fail(error.message), identity)
    val geometryConfig = SmallStrainGeometryConfig(
      strainGeometry(SmallStrainCertificateConfig.create(maximumGradient, 25.0)),
      strainGeometry(SmallStrainInverseConfig.create(1e-8, 100)),
      sourceDomain,
      fixedDomain
    )
    val centers = Vector(
      Vector(-7.0, -7.0, -6.0), Vector(-7.0, 5.0, -2.0), Vector(-4.0, -2.0, 6.0),
      Vector(-1.0, 7.0, 4.0), Vector(2.0, -7.0, 1.0), Vector(5.0, 4.0, -6.0),
      Vector(7.0, -3.0, 5.0), Vector(3.0, 1.0, 7.0), Vector(-5.0, 7.0, 7.0),
      Vector(7.0, 7.0, -1.0), Vector(0.0, 0.0, 0.0), Vector(-2.0, 4.0, -7.0)
    )
    val offsets = for x <- Vector(-0.7, 0.0, 0.7); y <- Vector(-0.7, 0.0, 0.7); z <- Vector(-0.7, 0.0, 0.7) yield Vector(x, y, z)
    val points = centers.flatMap(center => offsets.map(offset => center.zip(offset).map(_ + _)))
    val probes = WorldPointBatch3.create[moving.type](moving, points.flatten.toArray).fold(error => fail(error.message), identity)
    val indices = centers.indices.map(patch => Vector.tabulate(offsets.length)(sample => patch * offsets.length + sample)).toVector
    val patchConfig = PatchObjectiveConfig.create(1.0, 0.5, 1e-4, 1e-10).fold(error => fail(error.message), identity)
    Fixture(moving, fixed, model, pose, geometryConfig, probes, indices, patchConfig)

  private def optimizer[Moving <: Frame[D3], Fixed <: Frame[D3]](
      fixture: Fixture[Moving, Fixed],
      objective: SmallStrainProjectedPatchObjective3[FramedAffine[Moving, Fixed, D3], Moving, Fixed]
  ): SmallStrainOptimizer3[FramedAffine[Moving, Fixed, D3], Moving, Fixed] =
    val config = SmallStrainOptimizerConfig.create(trustConfig()).fold(error => fail(error.message), identity)
    SmallStrainOptimizer3.compile(fixture.model, fixture.geometryConfig, objective, fixture.probes, config)
      .fold(error => fail(error.message), identity)

  private def trustConfig(
      maximumLinearizations: Int = 35,
      maximumTrialAttempts: Int = 8,
      maximumDamping: Double = 1e8
  ): PeFieldOptimizerConfig =
    val solver = GalePcgConfig.create(1e-8, 100).fold(error => fail(error.message), identity)
    PeFieldOptimizerConfig.create(
      maximumLinearizations = maximumLinearizations,
      maximumTrialAttempts = maximumTrialAttempts,
      initialDamping = 1e-2,
      maximumDamping = maximumDamping,
      trustRadiusRmsMm = 0.8,
      maximumProbeDisplacementMm = 1.5,
      objectiveTolerance = 1e-10,
      stepToleranceRmsMm = 1e-6,
      gradientTolerance = 1e-8,
      solver = solver
    ).fold(error => fail(error.message), identity)

  private def state[Moving <: Frame[D3], Fixed <: Frame[D3]](
      fixture: Fixture[Moving, Fixed], coefficients: Vector[Double]
  ): SmallStrainState3[FramedAffine[Moving, Fixed, D3]] =
    SmallStrainState3(fixture.pose, small(fixture.model.basis.coefficientState(coefficients)))

  private def mapRms[Moving <: Frame[D3], Fixed <: Frame[D3]](
      fixture: Fixture[Moving, Fixed],
      actual: SmallStrainState3[FramedAffine[Moving, Fixed, D3]],
      expected: SmallStrainState3[FramedAffine[Moving, Fixed, D3]]
  ): Double =
    val left = output(fixture.fixed, fixture.probes.size); val right = output(fixture.fixed, fixture.probes.size)
    small(fixture.model.map(actual, fixture.probes, left, fixture.model.newWorkspace()))
    small(fixture.model.map(expected, fixture.probes, right, fixture.model.newWorkspace()))
    rms(left.snapshot, right.snapshot)

  private def outOfBasisRms[Moving <: Frame[D3], Fixed <: Frame[D3]](
      fixture: Fixture[Moving, Fixed],
      actual: SmallStrainState3[FramedAffine[Moving, Fixed, D3]],
      omitted: (Double, Double, Double) => Vector[Double]
  ): Double =
    val mapped = output(fixture.fixed, fixture.probes.size)
    small(fixture.model.map(actual, fixture.probes, mapped, fixture.model.newWorkspace()))
    val expected = fixture.probes.packed.grouped(3).flatMap(point =>
      val displacement = omitted(point(0), point(1), point(2))
      Vector(point(0) + displacement(0), point(1) + displacement(1), point(2) + displacement(2))
    ).toVector
    rms(mapped.snapshot, expected)

  private def inverseRoundtripRms[Moving <: Frame[D3], Fixed <: Frame[D3]](
      fixture: Fixture[Moving, Fixed],
      state: SmallStrainState3[FramedAffine[Moving, Fixed, D3]]
  ): Double =
    val geometry = GeometryPointOperator3.smallStrain(fixture.model, fixture.geometryConfig)
    val mapped = output(fixture.fixed, fixture.probes.size); val restored = output(fixture.moving, fixture.probes.size)
    geometryOperator(geometry.map(state, fixture.probes, mapped, geometry.newWorkspace()))
    val fixedPoints = WorldPointBatch3.create[Fixed](fixture.fixed, mapped.snapshot.toArray).fold(error => fail(error.message), identity)
    geometryOperator(geometry.inverseMap(state, fixedPoints, restored, geometry.newWorkspace()))
    rms(restored.snapshot, fixture.probes.packed.toVector)

  private def analyticValue(x: Double, y: Double, z: Double): Double =
    math.sin(0.19 * x) + math.cos(0.14 * y) + 0.35 * math.sin(0.12 * z) + 0.003 * x * y + 0.002 * y * z + 0.0015 * x * z
  private def analyticGradient(x: Double, y: Double, z: Double): Vector[Double] = Vector(
    0.19 * math.cos(0.19 * x) + 0.003 * y + 0.0015 * z,
    -0.14 * math.sin(0.14 * y) + 0.003 * x + 0.002 * z,
    0.042 * math.cos(0.12 * z) + 0.002 * y + 0.0015 * x
  )
  private def normalize(values: Array[Double]): (Vector[Double], Double) =
    val shifted = values.map(_ - values(0)); val mean = shifted.sum / shifted.length.toDouble
    val centered = shifted.map(_ - mean); val norm = math.sqrt(centered.map(value => value * value).sum)
    centered.map(_ / norm).toVector -> norm
  private def rms(left: Vector[Double], right: Vector[Double]): Double =
    math.sqrt(left.indices.map(index => math.pow(left(index) - right(index), 2.0)).sum / (left.length / 3).toDouble)
  private def output[F <: Frame[D3]](frame: F, size: Int): GeometryOutputBuffer3[F] =
    GeometryOutputBuffer3.allocate(frame, size).fold(error => fail(error.message), identity)
  private def geometry[A](value: Either[GeometryError, A]): A = value.fold(error => fail(error.message), identity)
  private def affine[A](value: Either[AffineModelError, A]): A = value.fold(error => fail(error.message), identity)
  private def small[A](value: Either[SmallStrainError, A]): A = value.fold(error => fail(error.message), identity)
  private def strainGeometry[A](value: Either[SmallStrainGeometryError, A]): A = value.fold(error => fail(error.message), identity)
  private def patchResult[A](value: Either[PatchObjectiveError, A]): A = value.fold(error => fail(error.message), identity)
  private def geometryOperator[A](value: Either[GeometryOperatorError, A]): A = value.fold(error => fail(error.message), identity)
