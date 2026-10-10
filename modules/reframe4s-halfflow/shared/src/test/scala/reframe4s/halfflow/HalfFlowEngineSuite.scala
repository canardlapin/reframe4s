package reframe4s.halfflow

import gale.linalg.DMat
import reframe4s.halfflow.internal.*

class HalfFlowEngineSuite extends munit.FunSuite:
  sealed trait Work
  sealed trait Fixed
  sealed trait Moving

  test("plans are strictly coarse-to-fine and end on the original grid") {
    val fine = level(shrink = 1, accepted = 1, attempts = 2)
    val coarse = level(shrink = 2, accepted = 1, attempts = 2)
    assert(HalfFlowPlan.make(Vector(coarse, fine)).isRight)
    assert(HalfFlowPlan.make(Vector(fine, coarse)).isLeft)
    assert(HalfFlowPlan.make(Vector(coarse)).isLeft)
  }

  test("identical images terminate stationary without changing either direction") {
    val data = fixture(side = 20, shift = 0.0)
    val result = HalfFlowLm
      .register(data.fixed, data.moving, data.initial, plan(Vector(level(1, 2, 4))))
      .fold(error => fail(error.message), identity)
    assertEquals(result.diagnostics.acceptedSteps, 0)
    assertEquals(result.diagnostics.levels.head.termination, LevelTermination.Stationary)
    assert(result.guard.safe, result.guard.reasons.mkString(", "))
    assertIdentity(result.transform.forward, 1e-12)
    assertIdentity(result.transform.backward, 1e-12)
  }

  test("two physical levels recover a smooth sub-voxel displacement and lower the objective") {
    val data = fixture(side = 28, shift = 0.35)
    val levels = Vector(level(2, 1, 5), level(1, 2, 8))
    val result = HalfFlowLm
      .register(data.fixed, data.moving, data.initial, plan(levels))
      .fold(error => fail(error.message), identity)
    assert(result.diagnostics.acceptedSteps >= 1)
    assert(result.diagnostics.levels.exists(level => level.finalValue < level.initialValue))
    val map = result.transform.forward.sourceCoordinates
    val grid = result.transform.forward.from.grid
    val center = grid.shape(0) / 2 + grid.shape(0) * (grid.shape(1) / 2) +
      grid.shape(0) * grid.shape(1) * (grid.shape(2) / 2)
    val identityX = grid.affine(0, 0) * (grid.shape(0) / 2).toDouble + grid.affine(0, 3)
    val recovered = map.linearComponent(center, 0) - identityX
    assert(recovered > 0.02, s"recovered fixed-to-moving shift=$recovered")
    assert(recovered < 0.8, s"recovered fixed-to-moving shift=$recovered")
    assert(result.guard.safe, result.guard.reasons.mkString(", "))
  }

  test("nonlinear registration is equivariant when fixed and moving are swapped") {
    val data = fixture(side = 22, shift = 0.25)
    val schedule = plan(Vector(level(1, 2, 8)))
    val forward = HalfFlowLm
      .register(data.fixed, data.moving, data.initial, schedule)
      .fold(error => fail(error.message), identity)
    val reverseInitial = Midpoint
      .identity(data.initial.work, data.moving.frame, data.fixed.frame)
      .fold(error => fail(error.message), identity)
    val reverse = HalfFlowLm
      .register(data.moving, data.fixed, reverseInitial, schedule)
      .fold(error => fail(error.message), identity)

    assertEquals(forward.diagnostics.acceptedSteps, reverse.diagnostics.acceptedSteps)
    assertPullClose(forward.transform.forward, reverse.transform.backward, 2e-9)
    assertPullClose(forward.transform.backward, reverse.transform.forward, 2e-9)
  }

  test("end-to-end trust rejection leaves the exact initial maps selected") {
    val data = fixture(side = 20, shift = 0.30)
    val rejectingTrust = TrustConfig
      .make(
        etaAccept = 100.0,
        lowGain = 101.0,
        highGain = 102.0,
        targetAcceptedSteps = 1,
        maximumAttempts = 2
      )
      .fold(error => fail(error.message), identity)
    val rejectingLevel = level(1, 1, 2, Some(rejectingTrust))
    val result = HalfFlowLm
      .register(data.fixed, data.moving, data.initial, plan(Vector(rejectingLevel)))
      .fold(error => fail(error.message), identity)
    assertEquals(result.diagnostics.acceptedSteps, 0)
    assertEquals(result.diagnostics.attemptedSteps, 2)
    assertEquals(result.diagnostics.levels.head.termination, LevelTermination.AttemptBudget)
    assertIdentity(result.transform.forward, 0.0)
    assertIdentity(result.transform.backward, 0.0)
  }

  test("independent native endpoint pyramids retain distinct physical grids"):
    val fixedGrid = GridSpec(
      Vector(13, 13, 13),
      DMat.dense(4, 4, (Vector(
          Vector(2.0, 0.2, 0.0, 0.0),
          Vector(0.0, 2.0, 0.1, 0.0),
          Vector(0.0, 0.0, 2.0, 0.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )).flatten)
    )
    val movingGrid = GridSpec(
      Vector(25, 25, 25),
      DMat.dense(4, 4, (Vector(
          Vector(1.0, 0.1, 0.0, 0.0),
          Vector(0.0, 1.0, 0.05, 0.0),
          Vector(0.0, 0.0, 1.0, 0.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )).flatten)
    )
    val work = RegistrationFrame[Work](SpatialDomainId("native-work"), fixedGrid)
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("native-fixed"), fixedGrid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("native-moving"), movingGrid)
    val fixedVolume = constantVolume(fixedGrid, "fixed-native", 12.0)
    val movingVolume = constantVolume(movingGrid, "moving-native", 12.0)
    val fixed = RegistrationImage.make(fixedFrame, fixedVolume).fold(error => fail(error.message), identity)
    val moving = RegistrationImage.make(movingFrame, movingVolume).fold(error => fail(error.message), identity)
    val initial = Midpoint.identity(work, fixedFrame, movingFrame).fold(error => fail(error.message), identity)
    val result = HalfFlowLm
      .register(fixed, moving, initial, plan(Vector(level(2, 1, 2), level(1, 1, 2))))
      .fold(error => fail(error.message), identity)

    assertEquals(result.diagnostics.acceptedSteps, 0)
    assertEquals(result.transform.forward.from.grid, fixedGrid)
    assertEquals(result.transform.forward.to.grid, movingGrid)
    assertEquals(result.transform.backward.from.grid, movingGrid)
    assertEquals(result.transform.backward.to.grid, fixedGrid)
    assertIdentity(result.transform.forward, 1e-12)
    assertIdentity(result.transform.backward, 1e-12)

  private final case class Fixture(
      fixed: RegistrationImage[Fixed],
      moving: RegistrationImage[Moving],
      initial: Midpoint[Work, Fixed, Moving]
  )

  private def fixture(side: Int, shift: Double): Fixture =
    val grid = GridSpec.identity(Vector(side, side, side))
    val work = RegistrationFrame[Work](SpatialDomainId("engine-work"), grid)
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("engine-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("engine-moving"), grid)
    val fixedValues = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val movingValues = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var z = 0
    while z < side do
      var y = 0
      while y < side do
        var x = 0
        while x < side do
          val index = x + side * y + side * side * z
          fixedValues(index) = signal(x.toDouble, y.toDouble, z.toDouble, side)
          movingValues(index) = signal(x.toDouble - shift, y.toDouble, z.toDouble, side)
          x += 1
        y += 1
      z += 1
    val fixedVol = NeuroVol.fromLinear[Double](fixedValues, grid.toNeuroSpace, "fixed")
    val movingVol = NeuroVol.fromLinear[Double](movingValues, grid.toNeuroSpace, "moving")
    Fixture(
      RegistrationImage.make(fixedFrame, fixedVol).fold(error => fail(error.message), identity),
      RegistrationImage.make(movingFrame, movingVol).fold(error => fail(error.message), identity),
      Midpoint.identity(work, fixedFrame, movingFrame).fold(error => fail(error.message), identity)
    )

  private def signal(x: Double, y: Double, z: Double, side: Int): Double =
    val scale = 2.0 * math.Pi / (side - 1).toDouble
    100.0 + 17.0 * math.sin(scale * x) + 9.0 * math.cos(2.0 * scale * y) +
      6.0 * math.sin(scale * (x + 0.4 * z)) + 4.0 * math.cos(scale * (y + z))

  private def constantVolume(grid: GridSpec, label: String, value: Double): NeuroVol[Double] =
    val values = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var index = 0
    while index < values.length do
      values(index) = value
      index += 1
    NeuroVol.fromLinear(values, grid.toNeuroSpace, label)

  private def plan(levels: Vector[HalfFlowLevel]): HalfFlowPlan =
    HalfFlowPlan.make(levels).fold(error => fail(error.message), identity)

  private def level(
      shrink: Int,
      accepted: Int,
      attempts: Int,
      trustOverride: Option[TrustConfig] = None
  ): HalfFlowLevel =
    val feature = T1FeatureConfig
      .make(
        Vector(FeatureRadiusMm(2.0), FeatureRadiusMm(4.0)),
        minimumValidWindowFraction = 0.45,
        minimumActiveVoxels = 32
      )
      .fold(error => fail(error.message), identity)
    val local = LocalLmConfig
      .make(damping = 0.08, minimumActiveVoxels = 32, variant = LocalLmVariant.MultiChannel)
      .fold(error => fail(error.message), identity)
    val sobolev = SobolevConfig
      .make(
        SmoothLengthMm(if shrink > 1 then 1.5 else 0.9),
        power = 2,
        relativeTolerance = 1e-6,
        maximumIterations = 80,
        maximumDisplacementMm = if shrink > 1 then 0.35 else 0.2,
        maximumStrain = 0.20
      )
      .fold(error => fail(error.message), identity)
    val trust = trustOverride.getOrElse:
      TrustConfig
        .make(targetAcceptedSteps = accepted, maximumAttempts = attempts)
        .fold(error => fail(error.message), identity)
    HalfFlowLevel
      .make(
        shrink,
        pyramidSigmaMm = if shrink == 1 then 0.0 else 0.8,
        feature = feature,
        localLm = local,
        sobolev = sobolev,
        flow = FlowConfig(maximumInitialDisplacementMm = 0.25, maximumInitialGradient = 0.15),
        guard = GuardConfig(maximumInverseErrorMm = 0.5, maximumInverseErrorVox = 0.5, minimumValidFraction = 0.85),
        trust = trust,
        minimumUsefulVelocityMm = 1e-7
      )
      .fold(error => fail(error.message), identity)

  private def assertIdentity[A, B](pull: DensePull[A, B], tolerance: Double): Unit =
    val expected = HalfFlowKernels.identity(pull.from.grid).field
    val actual = pull.sourceCoordinates
    var index = 0
    while index < pull.from.grid.nVoxels do
      var component = 0
      while component < 3 do
        assertEqualsDouble(
          actual.linearComponent(index, component),
          expected.linearComponent(index, component),
          tolerance
        )
        component += 1
      index += 1

  private def assertPullClose[A, B](actual: DensePull[A, B], expected: DensePull[A, B], tolerance: Double): Unit =
    val left = actual.sourceCoordinates
    val right = expected.sourceCoordinates
    var index = 0
    while index < actual.from.grid.nVoxels do
      assertEquals(
        validAt(actual.validity, index),
        validAt(expected.validity, index)
      )
      var component = 0
      while component < 3 do
        assertEqualsDouble(
          left.linearComponent(index, component),
          right.linearComponent(index, component),
          tolerance
        )
        component += 1
      index += 1

  private def validAt(validity: FieldValidity, index: Int): Boolean =
    validity match
      case FieldValidity.All => true
      case FieldValidity.Mask(values) => values(index)
