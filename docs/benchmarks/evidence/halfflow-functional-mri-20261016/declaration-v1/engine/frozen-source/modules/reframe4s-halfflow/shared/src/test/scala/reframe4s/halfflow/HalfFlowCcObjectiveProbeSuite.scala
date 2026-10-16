package reframe4s.halfflow

import reframe4s.halfflow.internal.*

class HalfFlowCcObjectiveProbeSuite extends munit.FunSuite:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)

  test("probe reproduces production initialization and retains frozen value after candidate evaluation"):
    val (fixed, moving, state, plan) = fixture()
    val probe = right(HalfFlowCc.probeObjectiveAtLevel(fixed, moving, state, plan.levels.head, plan))
    var recorded = Option.empty[HalfFlowCcObjectiveAudit]
    val observer = new HalfFlowCcObserver[Work, Fixed, Moving]:
      def wantsState(info: HalfFlowCcCheckpointInfo): Boolean = false
      def observe(checkpoint: HalfFlowCcCheckpoint[Work, Fixed, Moving]): Unit =
        if checkpoint.info.kind == HalfFlowCcCheckpointKind.LevelStart then recorded = Some(checkpoint.info.after)
    val _ = right(HalfFlowCc.optimizeObserved(fixed, moving, state, plan, HalfFlowCcObservation(observer, 0)))
    assertEquals(Some(probe.audit), recorded)
    val beforeFixed = state.fixed.residual.sourceCoordinates.copyLegacyPlanar.toVector
    val beforeMoving = state.moving.residual.sourceCoordinates.copyLegacyPlanar.toVector
    val direction = Array.fill(3 * state.work.grid.nVoxels)(0.0)
    java.util.Arrays.fill(direction, 0, state.work.grid.nVoxels, 0.1)
    val velocity = right(Velocity.make(state.work,
      DenseVectorField.fromLegacyPlanar(state.work.grid, direction, DenseVectorFieldKind.Displacement)))
    val candidate = right(state.advance(HalfStep.fromPairedFlow(right(PairedScalingAndSquaring.expHalfPair(velocity)))))
    val changed = right(probe.value(candidate))
    assert(math.abs(changed - probe.audit.loss) > 1e-8)
    assertEqualsDouble(right(probe.value(state)), probe.audit.loss, 0.0)
    assertEquals(state.fixed.residual.sourceCoordinates.copyLegacyPlanar.toVector, beforeFixed)
    assertEquals(state.moving.residual.sourceCoordinates.copyLegacyPlanar.toVector, beforeMoving)
    java.util.Arrays.fill(probe.gradient, 123.0)
    assertEqualsDouble(right(probe.value(candidate)), changed, 0.0)

  test("probe refuses mismatched endpoint frames"):
    val (fixed, moving, state, plan) = fixture()
    val wrongFrame = RegistrationFrame[Fixed](SpatialDomainId("unrelated-fixed"), fixed.frame.grid)
    val wrong = right(RegistrationImage.make(wrongFrame, fixed.volume, fixed.validity))
    assert(HalfFlowCc.probeObjectiveAtLevel(wrong, moving, state, plan.levels.head, plan).isLeft)

  test("diagnostic pyramid preparation retains the unsmoothed native image owners"):
    val (fixed, moving, _, plan) = fixture()
    val (nextFixed, nextMoving) = right(HalfFlowCc.prepareProbeLevel(fixed, moving, plan.levels.head))
    assert(nextFixed eq fixed)
    assert(nextMoving eq moving)

  test("masked coarse-level probe exactly reproduces the optimizer's captured initialization"):
    val (original, moving, state, nativePlan) = fixture()
    val grid = original.frame.grid
    val mask = Array.tabulate(grid.nVoxels): i =>
      val x = i % grid.extentX
      val y = (i / grid.extentX) % grid.extentY
      x >= 2 && x < grid.extentX - 2 && y >= 2 && y < grid.extentY - 2
    val fixed = right(RegistrationImage.make(original.frame, original.volume, FieldValidity.copyMask(mask)))
    val level = right(HalfFlowCcLevel.make(2, 1.0, smoothSigmaMm = 2.0,
      maximumStepMm = 0.2, targetAcceptedSteps = 1, maximumAttempts = 2))
    val plan = right(HalfFlowCcPlan.make(Vector(level, nativePlan.levels.head)))
    var captured = Option.empty[HalfFlowCcCheckpoint[Work, Fixed, Moving]]
    val observer = new HalfFlowCcObserver[Work, Fixed, Moving]:
      def wantsState(info: HalfFlowCcCheckpointInfo): Boolean =
        info.kind == HalfFlowCcCheckpointKind.LevelStart && info.levelIndex == 0
      def observe(checkpoint: HalfFlowCcCheckpoint[Work, Fixed, Moving]): Unit =
        if checkpoint.info.kind == HalfFlowCcCheckpointKind.LevelStart && checkpoint.info.levelIndex == 0 then
          captured = Some(checkpoint)
    val _ = right(HalfFlowCc.optimizeObserved(fixed, moving, state, plan, HalfFlowCcObservation(observer, 1)))
    val checkpoint = captured.getOrElse(fail("coarse initialization was not captured"))
    val coarse = checkpoint.state.getOrElse(fail("coarse state was not captured"))
    val (fixedLevel, movingLevel) = right(HalfFlowCc.prepareProbeLevel(fixed, moving, level))
    assert(coarse.work.grid.shape != grid.shape)
    assertEquals(coarse.fixed.endpoint, fixedLevel.frame)
    assertEquals(coarse.moving.endpoint, movingLevel.frame)
    val probe = right(HalfFlowCc.probeObjectiveAtLevel(fixedLevel, movingLevel, coarse, level, plan))
    assertEquals(probe.audit, checkpoint.info.after)
    assertEqualsDouble(right(probe.value(coarse)), checkpoint.info.after.loss, 0.0)
    assert(HalfFlowCc.probeObjectiveAtLevel(fixed, moving, coarse, level, plan).isLeft)

  test("sampled intensity linearization agrees with frozen-objective finite differences and is repeatable"):
    val (fixed, moving, state, plan) = fixture()
    val probe = right(HalfFlowCc.probeObjectiveAtLevel(fixed, moving, state, plan.levels.head, plan))
    val sampled = probe.sampledIntensity.get
    val initial = right(sampled(state))
    assertEqualsDouble(initial.loss, probe.audit.loss, 0.0)
    assertEqualsDouble(initial.linearizedChange, 0.0, 0.0)
    val grid = state.work.grid
    def sample(step: Double): HalfFlowSampledIntensityProbe =
      val direction = Array.fill(3 * grid.nVoxels)(0.0)
      java.util.Arrays.fill(direction, 0, grid.nVoxels, step)
      VelocityBoundaryTaper.inPlace(direction, grid, 3.0)
      val velocity = right(Velocity.make(state.work,
        DenseVectorField.fromLegacyPlanar(grid, direction, DenseVectorFieldKind.Displacement)))
      val candidate = right(state.advance(HalfStep.fromPairedFlow(right(PairedScalingAndSquaring.expHalfPair(velocity)))))
      val result = right(sampled(candidate))
      assertEqualsDouble(result.loss, right(probe.value(candidate)), 0.0)
      result
    val errors = Vector(0.02, 0.01, 0.005).map: epsilon =>
      val plus = sample(epsilon)
      val minus = sample(-epsilon)
      val finiteDifference = (plus.loss - minus.loss) / (2.0 * epsilon)
      val chain = (plus.linearizedChange - minus.linearizedChange) / (2.0 * epsilon)
      math.abs(chain - finiteDifference) / math.max(1e-12, math.abs(chain) + math.abs(finiteDifference))
    assert(errors.last < 0.005, errors.toString)
    assert(errors.sliding(2).forall(pair => pair(1) < pair(0)), errors.toString)
    assertEquals(right(sampled(state)), initial)
    java.util.Arrays.fill(probe.gradient, 123.0)
    assertEquals(right(sampled(state)), initial)
    val before = sample(0.01)
    val (fixedDerivative, movingDerivative) = probe.intensityGradient.get
    assertEquals(fixedDerivative.count(_ != 0.0), probe.spatialChain.get.fixed.nonzeroIntensityDerivatives)
    assertEquals(movingDerivative.count(_ != 0.0), probe.spatialChain.get.moving.nonzeroIntensityDerivatives)
    java.util.Arrays.fill(fixedDerivative, 123.0)
    java.util.Arrays.fill(movingDerivative, -123.0)
    assertEquals(sample(0.01), before)
    assertEquals(right(sampled(state)), initial)

  private def fixture(): (RegistrationImage[Fixed], RegistrationImage[Moving],
      ForwardMidpoint[Work, Fixed, Moving], HalfFlowCcPlan) =
    val grid = GridSpec.identity(Vector(17, 17, 17))
    val work = RegistrationFrame[Work](SpatialDomainId("probe-work"), grid)
    val ff = RegistrationFrame[Fixed](SpatialDomainId("probe-fixed"), grid)
    val mf = RegistrationFrame[Moving](SpatialDomainId("probe-moving"), grid)
    def volume(shift: Double): NeuroVol[Double] =
      val values = Array.tabulate(grid.nVoxels): i =>
        val x = i % 17 - shift
        val y = (i / 17) % 17
        val z = i / (17 * 17)
        math.sin(0.27 * x) + math.cos(0.21 * y) + 0.4 * math.sin(0.19 * (x + z))
      NeuroVol.fromLinear[Double](values, grid.toNeuroSpace, "probe-fixture")
    val fixed = right(RegistrationImage.make(ff, volume(0.0)))
    val moving = right(RegistrationImage.make(mf, volume(0.7)))
    val state = right(ForwardMidpoint.identity(work, ff, mf))
    val level = right(HalfFlowCcLevel.make(1, 0.0, smoothSigmaMm = 2.0,
      maximumStepMm = 0.2, targetAcceptedSteps = 1, maximumAttempts = 2))
    val plan = right(HalfFlowCcPlan.make(Vector(level)))
    (fixed, moving, state, plan)
