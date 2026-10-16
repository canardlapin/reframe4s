package reframe4s.halfflow

import reframe4s.halfflow.internal.*
import scala.collection.mutable.ArrayBuffer

class HalfFlowCcObservationSuite extends munit.FunSuite:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving

  private final class Recorder extends HalfFlowCcObserver[Work, Fixed, Moving]:
    val events = ArrayBuffer.empty[HalfFlowCcCheckpoint[Work, Fixed, Moving]]
    def wantsState(info: HalfFlowCcCheckpointInfo): Boolean = true
    def observe(checkpoint: HalfFlowCcCheckpoint[Work, Fixed, Moving]): Unit = events += checkpoint

  test("observation preserves exact optimizer results and records both objective definitions"):
    val (fixed, moving, initial) = fixture()
    val policy = plan()
    val plain = HalfFlowCc.optimize(fixed, moving, initial, policy).fold(e => fail(e.message), identity)
    val recorder = new Recorder
    val observed = HalfFlowCc.optimizeObserved(fixed, moving, initial, policy,
      HalfFlowCcObservation(recorder, 32)).fold(e => fail(e.message), identity)
    assertEquals(observed.diagnostics.toString, plain.diagnostics.toString)
    assertEquals(observed.state.fixed.residual.sourceCoordinates.copyLegacyPlanar.toVector,
      plain.state.fixed.residual.sourceCoordinates.copyLegacyPlanar.toVector)
    assertEquals(observed.state.moving.residual.sourceCoordinates.copyLegacyPlanar.toVector,
      plain.state.moving.residual.sourceCoordinates.copyLegacyPlanar.toVector)
    val starts = recorder.events.filter(_.info.kind == HalfFlowCcCheckpointKind.LevelStart)
    val ends = recorder.events.filter(_.info.kind == HalfFlowCcCheckpointKind.LevelEnd)
    assertEquals(starts.map(_.info.levelIndex).toVector, Vector(0, 1))
    assertEquals(ends.map(_.info.levelIndex).toVector, Vector(0, 1))
    assertEquals(recorder.events.count(_.info.trial.nonEmpty), observed.diagnostics.attempts)
    val accepted = recorder.events.filter(_.info.kind == HalfFlowCcCheckpointKind.Accepted)
    assert(accepted.nonEmpty, "fixture must exercise post-acceptance weight rebuilding")
    assertEquals(accepted.size, observed.diagnostics.acceptedSteps)
    accepted.foreach: event =>
      val trial = event.info.trial.get
      assertEquals(event.info.before.loss, trial.currentLoss)
      assert(trial.candidateLoss < event.info.before.loss)
      assert(event.info.after.loss.isFinite)
      assert(event.info.after.neighborhood.nonEmpty)
      assert(event.info.after.reliability.exists(_.nonFinite == 0))
    ends.zip(observed.diagnostics.levels).foreach: (event, level) =>
      assertEquals(event.info.after.loss, level.finalLoss)
      assertEquals(event.info.termination, Some(level.termination))

  test("the snapshot budget spans levels while all scalar events remain visible"):
    val (fixed, moving, initial) = fixture()
    val recorder = new Recorder
    val result = HalfFlowCc.optimizeObserved(fixed, moving, initial, plan(),
      HalfFlowCcObservation(recorder, 1)).fold(e => fail(e.message), identity)
    assertEquals(recorder.events.count(_.state.nonEmpty), 1)
    assertEquals(recorder.events.count(_.captureStatus == HalfFlowCcCaptureStatus.Captured), 1)
    assert(recorder.events.exists(_.captureStatus == HalfFlowCcCaptureStatus.BudgetExhausted))
    assertEquals(recorder.events.count(_.info.trial.nonEmpty), result.diagnostics.attempts)
    val snapshot = recorder.events.head.state.get
    assert(!(snapshot.fixed.residual.sourceCoordinates.values eq initial.fixed.residual.sourceCoordinates.values))
    assert(!(snapshot.moving.residual.sourceCoordinates.values eq initial.moving.residual.sourceCoordinates.values))

  test("rejected accumulated candidates are retained without becoming optimizer results"):
    val (fixed, moving, initial) = fixture()
    val recorder = new Recorder
    val level = HalfFlowCcLevel.make(1, 0.0, smoothSigmaMm = 2.0,
      maximumStepMm = 1.0, targetAcceptedSteps = 1, maximumAttempts = 2)
      .fold(e => fail(e.message), identity)
    val policy = HalfFlowCcPlan.make(Vector(level), minimumUsefulStepMm = 0.0,
      maximumIntegrationInverseErrorMm = 0.03,
      geometry = ForwardGeometryConfig(minimumAccumulatedJacobian = 0.99999999))
      .fold(e => fail(e.message), identity)
    val result = HalfFlowCc.optimizeObserved(fixed, moving, initial, policy,
      HalfFlowCcObservation(recorder, 8)).fold(e => fail(e.message), identity)
    val rejected = recorder.events.filter(_.info.kind == HalfFlowCcCheckpointKind.Rejected)
    assert(rejected.exists(_.state.nonEmpty), "fixture must construct a rejected candidate")
    assertEquals(result.diagnostics.acceptedSteps, 0)
    rejected.foreach: event =>
      assertEquals(event.info.before.loss, event.info.after.loss)
      assert(!event.info.trial.get.accepted)
    assertEquals(result.state.fixed.residual.sourceCoordinates.copyLegacyPlanar.toVector,
      initial.fixed.residual.sourceCoordinates.copyLegacyPlanar.toVector)
    assertEquals(result.state.moving.residual.sourceCoordinates.copyLegacyPlanar.toVector,
      initial.moving.residual.sourceCoordinates.copyLegacyPlanar.toVector)

  test("observer failures propagate instead of reporting a complete diagnostic run"):
    val (fixed, moving, initial) = fixture()
    val observer = new HalfFlowCcObserver[Work, Fixed, Moving]:
      def wantsState(info: HalfFlowCcCheckpointInfo): Boolean = false
      def observe(checkpoint: HalfFlowCcCheckpoint[Work, Fixed, Moving]): Unit =
        throw new IllegalStateException("checkpoint output unavailable")
    val error = intercept[IllegalStateException]:
      HalfFlowCc.optimizeObserved(fixed, moving, initial, plan(), HalfFlowCcObservation(observer, 0))
    assertEquals(error.getMessage, "checkpoint output unavailable")

  test("weight summaries preserve zero and nonfinite denominators"):
    val summary = HalfFlowCcWeightSummary.from(Array(0.0, 1.0, 3.0, Double.NaN))
    assertEquals(summary, HalfFlowCcWeightSummary(4, 1, 2, 4.0, 0.0, 3.0))

  test("unavailable candidates do not consume the budget and captured fields own their buffers"):
    val (_, _, initial) = fixture()
    val mask = Array.fill(initial.work.grid.nVoxels)(true)
    mask(0) = false
    val originalPull = initial.fixed.residual
    val maskedPull = DensePull.make(originalPull.from, originalPull.to, originalPull.sourceCoordinates,
      FieldValidity.copyMask(mask)).fold(e => fail(e.message), identity)
    val maskedArm = ForwardMidpointArm.make(maskedPull, initial.fixed.affine).fold(e => fail(e.message), identity)
    val state = ForwardMidpoint.make(maskedArm, initial.moving).fold(e => fail(e.message), identity)
    val summary = HalfFlowCcWeightSummary(1, 0, 1, 1.0, 1.0, 1.0)
    val audit = HalfFlowCcObjectiveAudit(0.5, 1.0, 1.0, summary, None, None, None)
    val info = HalfFlowCcCheckpointInfo(HalfFlowCcCheckpointKind.Rejected, 0, 1, 0, 1,
      audit, audit, HalfFlowCcControlState.initial(HalfFlowCcControlConfig.default), None, None, false)
    val recorder = new Recorder
    val run = new HalfFlowCcObservationRun(HalfFlowCcObservation(recorder, 1))
    run.emit(info, None)
    run.emit(info.copy(stateAvailable = true), Some(state))
    run.emit(info.copy(stateAvailable = true), Some(state))
    assertEquals(recorder.events.map(_.captureStatus).toVector,
      Vector(HalfFlowCcCaptureStatus.Unavailable, HalfFlowCcCaptureStatus.Captured, HalfFlowCcCaptureStatus.BudgetExhausted))
    val captured = recorder.events(1).state.get.fixed.residual
    assert(!(captured.sourceCoordinates.values eq maskedPull.sourceCoordinates.values))
    assertEquals(captured.sourceCoordinates.copyLegacyPlanar.toVector, maskedPull.sourceCoordinates.copyLegacyPlanar.toVector)
    (captured.validity, maskedPull.validity) match
      case (FieldValidity.Mask(copy), FieldValidity.Mask(original)) =>
        assert(!(copy eq original))
        assert(!copy(0))
        assert(copy(1))
      case other => fail(s"expected copied validity masks, got $other")

  private def plan(): HalfFlowCcPlan =
    val cc = NeighborhoodCcConfig.make(radius = VoxelWindowRadius(2, 2, 2),
      minimumSupportFraction = 0.15, fullSupportFraction = 0.7,
      minimumVarianceFraction = 1e-7, fullVarianceFraction = 1e-5,
      denominatorEpsilonFraction = 1e-7).fold(e => fail(e.message), identity)
    val levels = Vector((2, 1.0), (1, 0.0)).map: (shrink, sigma) =>
      HalfFlowCcLevel.make(shrink, sigma, cc, smoothSigmaMm = 3.0,
        maximumStepMm = 0.6, targetAcceptedSteps = 2, maximumAttempts = 8)
        .fold(e => fail(e.message), identity)
    HalfFlowCcPlan.make(levels, minimumUsefulStepMm = 1e-6,
      maximumIntegrationInverseErrorMm = 0.03).fold(e => fail(e.message), identity)

  private def fixture(): (RegistrationImage[Fixed], RegistrationImage[Moving], ForwardMidpoint[Work, Fixed, Moving]) =
    val grid = GridSpec.identity(Vector(25, 25, 25))
    val work = RegistrationFrame[Work](SpatialDomainId("observation-work"), grid)
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("observation-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("observation-moving"), grid)
    def signal(x: Double, y: Double, z: Double): Double =
      def bump(cx: Double, cy: Double, cz: Double, width: Double): Double =
        math.exp(-((x-cx)*(x-cx)+(y-cy)*(y-cy)+(z-cz)*(z-cz))/width)
      100.0*bump(10, 13, 12, 50) + 45.0*bump(7, 9, 14, 10) +
        65.0*bump(16, 15, 9, 16) + 3.0*math.sin(.31*x+.17*y) + 2.0*math.cos(.23*z-.11*x)
    def image[A](frame: RegistrationFrame[A], shift: Double): RegistrationImage[A] =
      val values = Array.tabulate(grid.nVoxels): index =>
        signal(index % 25 - shift, index / 25 % 25, index / 625)
      RegistrationImage.make(frame, NeuroVol.fromLinear[Double](values, grid.toNeuroSpace, "observation"))
        .fold(e => fail(e.message), identity)
    (image(fixedFrame, 0), image(movingFrame, 2),
      ForwardMidpoint.identity(work, fixedFrame, movingFrame).fold(e => fail(e.message), identity))
