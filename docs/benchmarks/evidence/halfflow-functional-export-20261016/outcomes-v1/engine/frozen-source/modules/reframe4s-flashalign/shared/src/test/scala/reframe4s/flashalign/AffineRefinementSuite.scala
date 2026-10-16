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
import reframe4s.lie.FramedAffine
import reframe4s.resample.LinearValueGradientSampler3
import reframe4s.resample.ResamplingError
import reframe4s.resample.ScalarValueGradient3

final class AffineRefinementSuite extends munit.FunSuite:
  test("known translation rotation scale and shear recover from independent patches"):
    val fixture = new RecoveryFixture
    val plan = fixture.plan(fixture.optimizerConfig())
    val workspace = refinement(plan.newWorkspace())
    val result = refinement(plan.optimize(fixture.identity, workspace))

    assert(result.termination.converged, result.termination)
    assert(result.counters.acceptedSteps > 0, result.counters)
    assert(result.counters.dataLinearizations > 1, result.counters)
    assert(result.counters.selectionEvaluations > 1, result.counters)
    assert(
      result.attempts.forall(_.maximumDisplacement <= 1.0 + 1e-12),
      result.attempts
    )
    assertAffineClose(result.state, fixture.truth, 8e-3)
    val landmarkError = fixture.landmarks.map { point =>
      val actual = mapped(result.state(point)).coordinates
      val expected = mapped(fixture.truth(point)).coordinates
      distance(actual, expected)
    }
    assert(landmarkError.max < 0.025, landmarkError)

  test("affine maximum-displacement rejection reuses one data linearization"):
    val fixture = new RecoveryFixture
    val plan = fixture.plan(
      fixture.optimizerConfig(
        maximumLinearizations = 3,
        maximumTrialAttempts = 3,
        maximumDisplacement = 1e-12
      )
    )
    val result = refinement(
      plan.optimize(fixture.identity, refinement(plan.newWorkspace()))
    )

    assertEquals(result.termination, ProjectedPatchTermination.TrialAttemptLimit)
    assertEquals(result.counters.dataLinearizations, 1)
    assertEquals(result.counters.priorLinearizations, 1)
    assertEquals(result.counters.trialEvaluations, 0)
    assertEquals(result.counters.rejectedSteps, 3)
    assert(
      result.attempts.forall(
        _.rejection.contains(ProjectedPatchRejection.MaximumDisplacement)
      ),
      result.attempts
    )
    assertAffineClose(result.state, fixture.identity, 0.0)

  test("unsupported affine patches terminate with truthful zero data rank"):
    val fixture = new RecoveryFixture(allOutside = true)
    val plan = fixture.plan(fixture.optimizerConfig())
    val result = refinement(
      plan.optimize(fixture.identity, refinement(plan.newWorkspace()))
    )

    assertEquals(
      result.termination,
      ProjectedPatchTermination.RankDeficient(0, 12)
    )
    assertEquals(result.counters.linearSolverCalls, 0)
    assertEquals(result.counters.trialEvaluations, 0)
    val linearWorkspace = objective(fixture.optimization.newWorkspace())
    val linear = objective(
      fixture.optimization.linearize(fixture.identity, linearWorkspace)
    )
    assertEquals(linear.counters.activePatches, 0)
    assertEquals(
      linear.counters.invalidSupportPatches,
      linear.counters.distinctPatchEntries
    )
    assertEqualsDouble(linear.dataInformation.curvatureTrace, 0.0, 0.0)

  private final class RecoveryFixture(allOutside: Boolean = false):
    val moving = geometry(Frame.named[D3]("affine-recovery-moving"))
    val fixed = geometry(Frame.named[D3]("affine-recovery-fixed"))
    val pivot = Vector(14.0, 14.0, 14.0)
    val stencil = PhysicalStencil3.create(1.0)
    val patchConfig = patch(
      PatchObjectiveConfig.create(
        positivePolarityPrior = 0.995,
        tau = 0.35,
        outlierFloor = 0.001,
        minimumContrastEnergy = 1e-12
      )
    )
    val grid = geometry(Grid.in(fixed)(Vector(29, 29, 29), Affine.identity[D3]))
    val image = sampled(
      Sampled.continuous(
        grid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](29, 29, 29) { (i, j, k) =>
          math.sin(0.31 * i.toDouble + 0.07 * j.toDouble) +
            0.73 * math.cos(0.23 * j.toDouble - 0.05 * k.toDouble) +
            0.51 * math.sin(0.19 * k.toDouble + 0.03 * i.toDouble) +
            0.011 * i.toDouble * j.toDouble -
            0.008 * j.toDouble * k.toDouble +
            0.006 * i.toDouble * k.toDouble +
            0.00031 * i.toDouble * j.toDouble * k.toDouble
        }
      )
    )
    val sampler = resampling(LinearValueGradientSampler3.compile(image))
    private val geometryConfig = affineModel(
      AffineModelConfig.create(
        minimumIncrementSingularValue = 0.65,
        maximumIncrementSingularValue = 1.35,
        minimumCandidateSingularValue = 0.5,
        maximumCandidateSingularValue = 1.8,
        maximumAbsoluteStrainIncrement = 0.3
      )
    )
    val model = affineModel(
      AffineModel3.atFixedWorld[moving.type, fixed.type](
        moving,
        fixed,
        geometryConfig
      )(pivot(0), pivot(1), pivot(2))
    )
    val identity = FramedAffine.betweenFrames[moving.type, fixed.type, D3](
      moving,
      fixed
    )(Affine.identity[D3])
    val truthStep = Array(
      0.18, -0.15, 0.12,
      0.006, -0.005, 0.004,
      0.012, -0.010, 0.008,
      0.006, -0.004, 0.005
    )
    val truth = affineModel(model.propose(identity, truthStep))
    private val optimizationCenters =
      if allOutside then Vector((40.0, 40.0, 40.0), (44.0, 42.0, 41.0))
      else cartesian(Vector(6.0, 10.0, 14.0, 18.0, 22.0))
    private val selectionCenters =
      if allOutside then Vector((42.0, 45.0, 43.0), (46.0, 44.0, 42.0))
      else cartesian(Vector(7.5, 12.0, 16.5, 21.0))
    val optimization = compileObjective(
      optimizationCenters,
      PatchSampleRole.Optimization,
      0x9182L
    )
    val selection = compileObjective(
      selectionCenters,
      PatchSampleRole.Selection,
      0xa293L
    )
    private val prior = affineModel(
      AffineStrainPrior3.create(model, identity, weight = 1e-5)
    )
    val landmarks: Vector[Point[moving.type, D3]] = Vector(
      point(moving)(5.0, 5.0, 5.0),
      point(moving)(23.0, 5.0, 18.0),
      point(moving)(6.0, 22.0, 20.0),
      point(moving)(21.0, 21.0, 7.0)
    )
    private val probes = probeSet(
      AffineFixedProbeSet3.create[fixed.type](
        fixed,
        Vector(
          point(fixed)(5.0, 5.0, 5.0),
          point(fixed)(23.0, 5.0, 5.0),
          point(fixed)(5.0, 23.0, 5.0),
          point(fixed)(5.0, 5.0, 23.0),
          point(fixed)(23.0, 23.0, 23.0),
          point(fixed)(14.0, 14.0, 14.0)
        )
      )
    )

    def optimizerConfig(
        maximumLinearizations: Int = 30,
        maximumTrialAttempts: Int = 8,
        maximumDisplacement: Double = 1.0
    ): ProjectedPatchOptimizerConfig =
      optimizer(
        ProjectedPatchOptimizerConfig.create(
          parameterCount = 12,
          maximumLinearizations = maximumLinearizations,
          maximumTrialAttempts = maximumTrialAttempts,
          initialDamping = 1e-3,
          minimumDamping = 1e-10,
          maximumDamping = 1e8,
          trustRadiusRms = 0.35,
          maximumDisplacement = maximumDisplacement,
          acceptanceRatio = 0.05,
          objectiveTolerance = 1e-12,
          gradientTolerance = 1e-8,
          stepToleranceRms = 1e-7,
          minimumDataRank = 12,
          conditionLimit = 1e14,
          checkpointInterval = 1
        )
      )

    def plan(
        config: ProjectedPatchOptimizerConfig
    ) = refinement(
      AffineRefinementPlan3.compile(
        optimization,
        selection,
        prior,
        probes,
        config
      )
    )

    private def compileObjective(
        centers: Vector[(Double, Double, Double)],
        role: PatchSampleRole,
        seed: Long
    ) =
      val entries = centers.zipWithIndex.map { case ((x, y, z), id) =>
        val movingValues = movingSignal(x, y, z)
        val prepared = patch(PatchObjective.prepareMoving(movingValues, patchConfig))
        val patchValue = new PopulationPatch3(
          id,
          id,
          x,
          y,
          z,
          WorldCell3(
            math.floor(x / 6.0).toLong,
            math.floor(y / 6.0).toLong,
            math.floor(z / 6.0).toLong
          ),
          prepared,
          1.0,
          1.0
        )
        new WeightedPatchDraw3(
          patchValue,
          1,
          1.0 / centers.size.toDouble,
          1.0 / centers.size.toDouble
        )
      }
      val samples = new PatchSampleSet3(
        PatchSampleSetId(seed, seed + 1L, role, 0, seed + 2L),
        entries,
        PatchSampleSetDiagnostics(
          role,
          entries.size,
          entries.size,
          entries.size,
          0,
          1.0 / entries.size.toDouble,
          1.0 / entries.size.toDouble,
          1.0
        )
      )
      objective(
        CompiledAffineObjective3.compile(
          model,
          FrozenPatchObjective3.create(samples, patchConfig),
          stencil,
          sampler
        )
      )

    private def movingSignal(x: Double, y: Double, z: Double): Array[Double] =
      if allOutside then Array.tabulate(stencil.sampleCount)(_.toDouble)
      else
        Array.tabulate(stencil.sampleCount) { sample =>
          val source = point(moving)(
            x + stencil.offsetX(sample),
            y + stencil.offsetY(sample),
            z + stencil.offsetZ(sample)
          )
          val target = mapped(truth(source))
          resampling(sampler.at(target, ScalarValueGradient3.create)).value
        }

    private def cartesian(values: Vector[Double]): Vector[(Double, Double, Double)] =
      for
        x <- values
        y <- values
        z <- values
      yield (x, y, z)

  private def assertAffineClose[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      actual: FramedAffine[Moving, Fixed, D3],
      expected: FramedAffine[Moving, Fixed, D3],
      tolerance: Double
  ): Unit =
    actual.operator.rowMajor.zip(expected.operator.rowMajor).foreach {
      case (left, right) => assertEqualsDouble(left, right, tolerance)
    }

  private def distance(left: Vector[Double], right: Vector[Double]): Double =
    math.sqrt(left.zip(right).map { case (a, b) =>
      val difference = a - b
      difference * difference
    }.sum)

  private def point[F <: Frame[D3]](
      frame: F
  )(x: Double, y: Double, z: Double): Point[frame.type, D3] =
    geometry(Point.in[D3](frame)(x, y, z))

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def mapped[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def sampled[A](value: Either[image4s.ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def resampling[A](value: Either[ResamplingError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def patch[A](value: Either[PatchObjectiveError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def affineModel[A](value: Either[AffineModelError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def objective[A](
      value: Either[AffineObjectiveLinearizationError, A]
  ): A = value.fold(error => fail(error.message), identity)

  private def optimizer[A](value: Either[ProjectedPatchOptimizerError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def refinement[A](value: Either[AffineRefinementError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def probeSet[A](value: Either[AffineRefinementError, A]): A =
    value.fold(error => fail(error.message), identity)
