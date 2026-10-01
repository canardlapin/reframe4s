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
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.resample.LinearValueGradientSampler3
import reframe4s.resample.ResamplingError
import reframe4s.resample.ScalarValueGradient3

final class RigidTrialObjectiveSuite extends munit.FunSuite:
  test("complete value-only trials equal full linearizations including invalid patches"):
    val fixture = new TrialFixture
    val fullWorkspace = full(fixture.compiled.newWorkspace())
    val trialWorkspace = fixture.trials.newWorkspace()
    val initialFull = full(
      fixture.compiled.linearize(fixture.identity, fullWorkspace)
    )
    val initialTrial = complete(
      trial(
        fixture.trials.evaluate(
          fixture.identity,
          Double.PositiveInfinity,
          trialWorkspace
        )
      )
    )

    assertEqualsDouble(initialTrial.value, initialFull.objective, 2e-13)
    assertEquals(initialTrial.id.objectiveId, initialFull.cacheId.objectiveId)
    assertEquals(
      initialTrial.id.geometryFingerprint,
      initialFull.cacheId.geometryFingerprint
    )
    assertEquals(initialTrial.counters.gradientEvaluations, 0L)
    assert(initialTrial.counters.complete)
    assertEquals(
      initialTrial.counters.uniqueSamples,
      fixture.compiled.registry.uniquePointCount.toLong
    )
    assertEquals(
      initialTrial.counters.fullSupportSamples +
        initialTrial.counters.rejectedSamples,
      initialTrial.counters.uniqueSamples
    )
    assertEquals(
      trialWorkspace.lastWorkSnapshot,
      LinearDataWorkCounts.from(initialTrial.counters)
    )

    val shifted = rigidModel(
      fixture.model.propose(
        fixture.identity,
        Array(4.0, 0.0, 0.0, 0.0, 0.0, 0.0)
      )
    )
    val shiftedFull = full(
      fixture.compiled.linearize(shifted, fullWorkspace)
    )
    val shiftedTrial = complete(
      trial(
        fixture.trials.evaluate(
          shifted,
          Double.PositiveInfinity,
          trialWorkspace
        )
      )
    )
    assertEqualsDouble(shiftedTrial.value, shiftedFull.objective, 2e-13)
    assertNotEquals(
      shiftedTrial.id.movingToFixedFingerprint,
      initialTrial.id.movingToFixedFingerprint
    )
    assertNotEquals(shiftedTrial.value, initialTrial.value)
    assert(
      shiftedTrial.counters.rejectedSamples >
        initialTrial.counters.rejectedSamples
    )
    assertEquals(shiftedTrial.counters.gradientEvaluations, 0L)

  test("conservative lower bounds never reject enumerated admissible trials"):
    val fixture = new TrialFixture
    val workspace = fixture.trials.newWorkspace()
    val translations = Vector(-1.0, -0.5, 0.0, 0.5, 1.0)
    translations.foreach { dx =>
      val state = rigidModel(
        fixture.model.propose(
          fixture.identity,
          Array(dx, 0.0, 0.0, 0.0, 0.0, 0.0)
        )
      )
      val exact = complete(
        trial(
          fixture.trials.evaluate(
            state,
            Double.PositiveInfinity,
            workspace
          )
        )
      )
      val admissible = trial(
        fixture.trials.evaluate(
          state,
          java.lang.Math.nextAfter(
            exact.value,
            Double.PositiveInfinity
          ),
          workspace
        )
      )
      admissible match
        case value: RigidTrialObjectiveValue.Complete =>
          assertEqualsDouble(value.value, exact.value, 0.0)
        case rejected: RigidTrialObjectiveValue.RejectedEarly =>
          fail(
            s"falsely rejected $dx at ${rejected.conservativeLowerBound} <= ${exact.value}"
          )

      val below = exact.value * 0.2
      trial(fixture.trials.evaluate(state, below, workspace)) match
        case value: RigidTrialObjectiveValue.Complete =>
          assert(value.value > below)
        case rejected: RigidTrialObjectiveValue.RejectedEarly =>
          assert(rejected.conservativeLowerBound > below)
          assert(!rejected.counters.complete)
    }

  test("early rejected attempts skip later samples and all gradients"):
    val fixture = new TrialFixture
    val fullWorkspace = full(fixture.compiled.newWorkspace())
    val firstFull = full(
      fixture.compiled.linearize(fixture.identity, fullWorkspace)
    )
    val firstCacheId = firstFull.cacheId
    val trialWorkspace = fixture.trials.newWorkspace()
    var attempts = 0
    var gradientEvaluations = 0L
    while attempts < 3 do
      val result = trial(
        fixture.trials.evaluate(
          fixture.identity,
          acceptanceLimit = -1.0,
          trialWorkspace
        )
      )
      result match
        case rejected: RigidTrialObjectiveValue.RejectedEarly =>
          assertEquals(rejected.counters.evaluatedPatchEntries, 1)
          assert(
            rejected.counters.uniqueSamples <
              fixture.compiled.registry.uniquePointCount.toLong
          )
          gradientEvaluations += rejected.counters.gradientEvaluations
        case value => fail(s"expected early rejection, got $value")
      attempts += 1

    assertEquals(gradientEvaluations, 0L)
    assertEquals(firstFull.cacheId, firstCacheId)
    assertEquals(
      fullWorkspace.patchLinearization.diagnostics.centeredRecomputations,
      firstFull.counters.centeredStatisticRecomputations
    )

    fixture.trials.evaluate(
      fixture.identity,
      0.0,
      new TrialFixture().trials.newWorkspace()
    ) match
      case Left(RigidTrialObjectiveError.WorkspacePlanMismatch) => ()
      case other => fail(s"expected workspace mismatch, got $other")

  private final class TrialFixture:
    val moving = geometry(Frame.named[D3]("trial-moving"))
    val fixed = geometry(Frame.named[D3]("trial-fixed"))
    val stencil: PhysicalStencil3 = PhysicalStencil3.create(0.5)
    val config: PatchObjectiveConfig = patch(
      PatchObjectiveConfig.create(
        positivePolarityPrior = 0.7,
        tau = 0.65,
        outlierFloor = 0.025,
        minimumContrastEnergy = 1e-14
      )
    )
    val grid = geometry(
      Grid.in(fixed)(Vector(20, 20, 20), Affine.identity[D3])
    )
    val image = sampled(
      Sampled.continuous(
        grid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](20, 20, 20) { (i, j, k) =>
          if i >= 14 then 5.0
          else
            math.sin(0.21 * i.toDouble) +
              0.6 * math.cos(0.16 * j.toDouble) +
              0.025 * i.toDouble * k.toDouble -
              0.015 * j.toDouble * k.toDouble
        }
      )
    )
    val sampler = resampling(LinearValueGradientSampler3.compile(image))
    val model = rigidModel(
      RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(
        8.0,
        8.0,
        8.0
      )
    )
    val identity: Rigid3[moving.type, fixed.type] = rigid(
      Rigid3.fromAffine[moving.type, fixed.type](moving, fixed)(
        Affine.identity[D3]
      )
    )
    private val rawMoving = Map(
      0 -> fixedSignalAt(5.0, 5.0, 5.0),
      1 -> sourceSignal(0.23),
      2 -> sourceSignal(-0.31)
    )
    private val entries = Vector(
      weightedPatch(0, 5.0, 5.0, 5.0, 0.60),
      weightedPatch(1, 16.0, 8.0, 8.0, 0.25),
      weightedPatch(2, 30.0, 30.0, 30.0, 0.15)
    )
    private val samples = new PatchSampleSet3(
      PatchSampleSetId(
        populationFingerprint = 0x7123L,
        samplingFingerprint = 0x8124L,
        PatchSampleRole.Optimization,
        refreshOrdinal = 1,
        drawSeed = 0x9125L
      ),
      entries,
      PatchSampleSetDiagnostics(
        PatchSampleRole.Optimization,
        poolPatches = entries.size,
        requestedDraws = entries.size,
        distinctDraws = entries.size,
        repeatedDraws = 0,
        minimumDrawProbability = 1.0 / entries.size.toDouble,
        maximumDrawProbability = 1.0 / entries.size.toDouble,
        uniformProbabilityMixture = 1.0
      )
    )
    private val frozen = FrozenPatchObjective3.create(samples, config)
    val compiled = full(
      CompiledRigidObjective3.compile(model, frozen, stencil, sampler)
    )
    val trials = CompiledRigidTrialObjective3.compile(compiled)

    private def weightedPatch(
        id: Int,
        x: Double,
        y: Double,
        z: Double,
        weight: Double
    ): WeightedPatchDraw3 =
      val movingPatch = patch(
        PatchObjective.prepareMoving(rawMoving(id), config)
      )
      val value = new PopulationPatch3(
        id,
        id,
        x,
        y,
        z,
        WorldCell3(math.floor(x / 12.0).toLong, 0L, 0L),
        movingPatch,
        qualityWeight = 1.0,
        minimumStencilSupport = 1.0
      )
      new WeightedPatchDraw3(
        value,
        multiplicity = 1,
        drawProbability = 1.0 / 3.0,
        objectiveWeight = weight
      )

    private def fixedSignalAt(
        x: Double,
        y: Double,
        z: Double
    ): Array[Double] =
      Array.tabulate(stencil.sampleCount) { sample =>
        val point = geometry(
          Point.in[D3](fixed)(
            x + stencil.offsetX(sample),
            y + stencil.offsetY(sample),
            z + stencil.offsetZ(sample)
          )
        )
        resampling(
          sampler.at(point, ScalarValueGradient3.create)
        ).value
      }

    private def sourceSignal(phase: Double): Array[Double] =
      Array.tabulate(stencil.sampleCount) { sample =>
        math.sin(0.24 * sample.toDouble + phase) +
          0.2 * math.cos(0.1 * sample.toDouble - phase)
      }

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigidModel[A](value: Either[RigidModelError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def sampled[A](value: Either[image4s.ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def resampling[A](value: Either[ResamplingError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def patch[A](value: Either[PatchObjectiveError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def full[A](
      value: Either[RigidObjectiveLinearizationError, A]
  ): A =
    value.fold(error => fail(error.message), identity)

  private def trial[A](value: Either[RigidTrialObjectiveError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def complete(
      value: RigidTrialObjectiveValue
  ): RigidTrialObjectiveValue.Complete =
    value match
      case result: RigidTrialObjectiveValue.Complete => result
      case other => fail(s"expected complete trial, got $other")
