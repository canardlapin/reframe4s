package reframe4s.halfflow

import reframe4s.halfflow.internal.*

class HalfFlowCcEngineSuite extends munit.FunSuite:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving

  test("boundary identity survives half-flow integration, accumulation and pyramid transfer"):
    val grid = GridSpec.identity(Vector(17, 17, 17))
    val work = Frame[Work](SpatialDomainId("collar-work"), grid)
    val fixed = Frame[Fixed](SpatialDomainId("collar-fixed"), grid)
    val moving = Frame[Moving](SpatialDomainId("collar-moving"), grid)
    val values = Array.fill(3 * grid.nVoxels)(0.2)
    VelocityBoundaryTaper.inPlace(values, grid, 3.0)
    val velocity = Velocity.make(work, DenseVectorField.fromLegacyPlanar(
      grid, values, DenseVectorFieldKind.Displacement)).fold(e => fail(e.message), identity)
    val half = HalfStep.fromPairedFlow(PairedScalingAndSquaring.expHalfPair(velocity)
      .fold(e => fail(e.message), identity))
    var state = ForwardMidpoint.identity(work, fixed, moving).fold(e => fail(e.message), identity)
    for _ <- 0 until 3 do
      state = state.advance(half).fold(e => fail(e.message), identity)
    val fine = GridSpec(Vector(33, 33, 33), DMat.fromRows(Vector(
      Vector(0.5, 0.0, 0.0, 0.0), Vector(0.0, 0.5, 0.0, 0.0),
      Vector(0.0, 0.0, 0.5, 0.0), Vector(0.0, 0.0, 0.0, 1.0))))
    val transferred = state.regrid(Frame[Work](work.domain, fine), fixed, moving)
      .fold(e => fail(e.message), identity)
    for current <- Vector(state, transferred); pull <- Vector(current.fixed.residual, current.moving.residual) do
      val g = pull.from.grid
      val identityMap = DensePull.identity(pull.from)
      for index <- 0 until g.nVoxels do
        val x = index % g.shape.x
        val y = index / g.shape.x % g.shape.y
        val z = index / (g.shape.x * g.shape.y)
        if x <= 1 || x >= g.shape.x - 2 || y <= 1 || y >= g.shape.y - 2 || z <= 1 || z >= g.shape.z - 2 then
          for component <- 0 until 3 do
            assertEqualsDouble(pull.sourceCoordinates.linearComponent(index, component),
              identityMap.sourceCoordinates.linearComponent(index, component), 1e-12)

  test("velocity boundary collar has zero outer slope and uses physical face distance"):
    val grid = GridSpec(Vector(17, 17, 17), DMat.fromRows(Vector(
      Vector(2.0, 1.0, 0.0, 7.0), Vector(0.0, 2.0, 0.0, -4.0),
      Vector(0.0, 0.0, 3.0, 9.0), Vector(0.0, 0.0, 0.0, 1.0)
    )))
    val n = grid.nVoxels
    val velocity = Array.fill(3 * n)(2.0)
    // The x-face normal is parallel to (2,-1,0), so adjacent x planes
    // are 4/sqrt(5) mm apart. x=2 is half way through this taper.
    val width = 8.0 / math.sqrt(5.0)
    VelocityBoundaryTaper.inPlace(velocity, grid, width)
    for component <- 0 until 3 do
      for x <- Vector(0, 1, 15, 16) do
        assertEqualsDouble(velocity(x + 17 * (8 + 17 * 8) + component * n), 0.0, 0.0)
      assertEqualsDouble(velocity(2 + 17 * (8 + 17 * 8) + component * n), 1.0, 1e-14)
      assertEqualsDouble(velocity(8 + 17 * (8 + 17 * 8) + component * n), 2.0, 0.0)
    val unchanged = Array.tabulate(3 * n)(_.toDouble)
    val before = unchanged.toVector
    VelocityBoundaryTaper.inPlace(unchanged, grid, 0.0)
    assertEquals(unchanged.toVector, before)

  test("opposing boundary collars multiply smoothly when their widths overlap"):
    val grid = GridSpec.identity(Vector(9, 25, 25))
    val velocity = Array.fill(3 * grid.nVoxels)(1.0)
    VelocityBoundaryTaper.inPlace(velocity, grid, 6.0)
    // Both x faces contribute 1/2 at the midpoint. The other four faces
    // are beyond the collar. A nearest-face window would incorrectly give 1/2.
    assertEqualsDouble(velocity(4 + 9 * (12 + 25 * 12)), 0.25, 0.0)
    assertEqualsDouble(velocity(3 + 9 * (12 + 25 * 12)),
      velocity(5 + 9 * (12 + 25 * 12)), 0.0)

  test("velocity support taper matches an independent Gaussian impulse and vanishes in unsupported tails"):
    val grid = GridSpec.identity(Vector(11, 11, 11))
    val source = Array.fill(grid.nVoxels)(7.0)
    val support = Array.fill(grid.nVoxels)(0.0)
    support(5 + 11 * (5 + 11 * 5)) = 1.0
    val output = Array.fill(grid.nVoxels)(Double.NaN)
    val weight = new Array[Double](grid.nVoxels)
    val floor = 1e-3
    SupportedVelocitySmoothing(source, support, grid, 1.0, 1e-6, floor,
      output, weight, GaussianWorkspace(grid), GaussianReduction())
    // Direct separable convolution of a unit impulse, independent of filter code.
    val normalization = (-3 to 3).map(i => math.exp(-0.5 * i * i)).sum
    for x <- 0 until 11; y <- 0 until 11; z <- 0 until 11 do
      val index = x + 11 * (y + 11 * z)
      val offsets = Vector(x - 5, y - 5, z - 5)
      val w = if offsets.forall(i => math.abs(i) <= 3) then
        math.exp(-0.5 * offsets.map(i => i * i).sum) / math.pow(normalization, 3)
      else 0.0
      assertEqualsDouble(output(index), 7.0 * w / (w + floor), 2e-14)
    val tail = output(8 + 11 * (8 + 11 * 8))
    assert(tail > 0.0 && tail < 1e-3, s"tiny support retained an untapered velocity: $tail")
    val legacy = new Array[Double](grid.nVoxels)
    val legacyWeight = new Array[Double](grid.nVoxels)
    Gaussian3D.normalizedInto(source, support, grid, 1.0, 1e-6,
      legacy, legacyWeight, GaussianWorkspace(grid), GaussianBoundary.Zero, GaussianReduction())
    SupportedVelocitySmoothing(source, support, grid, 1.0, 1e-6, 0.0,
      output, weight, GaussianWorkspace(grid), GaussianReduction())
    assertEquals(output.toVector, legacy.toVector)
    assertEquals(weight.toVector, legacyWeight.toVector)

  test("pointwise rank-one step matches its scalar closed form"):
    val gradient = PrimitiveBuffers.ofSize[Double](6)
    gradient(0) = 3.0
    gradient(2) = 4.0
    val valid = PrimitiveBuffers.ofSize[Boolean](2)
    valid(0) = true
    valid(1) = false
    val destination = PrimitiveBuffers.ofSize[Double](6)
    val summary = PointwiseRankOne.solveInto(
      gradient,
      valid,
      voxels = 2,
      loss = 2.0,
      damping = 0.5,
      energyEpsilon = 1e-12,
      scale = 0.25,
      destination
    )
    val factor = -0.25 / (0.5 + 25.0 / (4.0 + 1e-12))
    assertEqualsDouble(destination(0), 3.0 * factor, 1e-14)
    assertEqualsDouble(destination(2), 4.0 * factor, 1e-14)
    assertEqualsDouble(destination(1), 0.0, 0.0)
    assertEquals(summary.activeVoxels, 1)
    assertEqualsDouble(summary.maximumNorm, 5.0 * math.abs(factor), 1e-14)

  test("moving optimization masks are rejected at the API boundary"):
    val fixture = translationFixture(side = 13, shiftMm = 1.0)
    val mask = PrimitiveBuffers.ofSize[Boolean](fixture.grid.nVoxels)
    var index = 0
    while index < mask.length do
      mask(index) = true
      index += 1
    val maskedMoving = RegistrationImage
      .make(fixture.moving.frame, fixture.moving.volume, FieldValidity.copyMask(mask))
      .fold(error => fail(error.message), identity)
    assertEquals(
      HalfFlowCc.register(fixture.fixed, maskedMoving, fixture.initial, compactPlan()),
      Left(HalfFlowCcError.MovingOptimizationMaskForbidden)
    )

  test("an invalid regridded starting map fails before unrepairable level retries"):
    val fixture = translationFixture(side = 13, shiftMm = 1.0)
    val folded = foldedPull(fixture.initial.work)
    val fixedArm = ForwardMidpointArm
      .make(folded, fixture.initial.fixed.affine)
      .fold(error => fail(error.message), identity)
    val invalid = ForwardMidpoint
      .make(fixedArm, fixture.initial.moving)
      .fold(error => fail(error.message), identity)
    HalfFlowCc.optimize(fixture.fixed, fixture.moving, invalid, compactPlan()) match
      case Left(HalfFlowCcError.RegriddedTopologyInvalid(_, verdict)) =>
        assert(verdict.isInstanceOf[GeometryVerdict.AccumulatedJacobianTooSmall])
      case other => fail(s"expected typed regrid topology failure, got $other")

  test("paired flow applies opposite physical half translations"):
    val grid = GridSpec.identity(Vector(9, 9, 9))
    val frame = Frame[Work](SpatialDomainId("cc-half-translation"), grid)
    val velocity = constantVelocity(frame, 2.0, 0.0, 0.0)
    val flow = PairedScalingAndSquaring.expHalfPair(velocity).fold(error => fail(error.message), identity)
    val center = 4 + 9 * 4 + 81 * 4
    val identityX = grid.voxelToWorld(SpatialPoint(4.0, 4.0, 4.0)).x
    val plusX = flow.pair.forward.sourceCoordinates.linearComponent(center, 0)
    val minusX = flow.pair.backward.sourceCoordinates.linearComponent(center, 0)
    assertEqualsDouble(plusX - identityX, 1.0, 1e-12)
    assertEqualsDouble(minusX - identityX, -1.0, 1e-12)
    assertEqualsDouble(minusX - plusX, -2.0, 1e-12)

  test("fixed-anchor action leaves the fixed arm unchanged while improving the same relative translation"):
    val fixture = translationFixture(side = 25, shiftMm = 2.0)
    val plan = compactPlan(HalfFlowCcAction.FixedAnchor)
    val optimization = HalfFlowCc
      .optimize(fixture.fixed, fixture.moving, fixture.initial, plan)
      .fold(error => fail(error.message), identity)
    val identityPull = DensePull.identity(optimization.state.work)
    assertCoordinatesEqual(
      optimization.state.fixed.residual.sourceCoordinates,
      identityPull.sourceCoordinates,
      1e-12
    )
    val error = midpointTranslationRms(optimization.state, fixture.shiftMm, margin = 6)
    assert(error <= 0.4, s"fixed-anchor lane left $error mm RMS")
    assert(optimization.diagnostics.acceptedSteps > 0)

  test("legacy accumulated-inverse gate rejects independently and is reported explicitly"):
    val fixture = translationFixture(side = 25, shiftMm = 2.0)
    val tracked = Midpoint
      .identity(fixture.initial.work, fixture.fixed.frame, fixture.moving.frame)
      .fold(error => fail(error.message), identity)
    val guard = GuardConfig(
      minimumJacobian = 0.001,
      maximumInverseErrorMm = 1e-12,
      maximumInverseErrorVox = 1e-12,
      minimumValidFraction = 0.5,
      minimumInverseValidFraction = Some(0.5)
    )
    val optimization = HalfFlowCc
      .optimizeWithLegacyInverseGate(fixture.fixed, fixture.moving, tracked, compactPlan(), guard)
      .fold(error => fail(error.message), identity)
    val traces = optimization.diagnostics.levels.flatMap(_.trace)
    assert(traces.exists(_.legacyInverseSafe.contains(false)), s"legacy gate did not reject: $traces")
    assert(
      traces.filter(_.legacyInverseSafe.contains(false)).forall(attempt => !attempt.accepted),
      s"legacy-unsafe candidate was accepted: $traces"
    )
    assert(traces.exists(_.legacyInverseMaximumMm.exists(_ > 1e-12)))

  test("standardized-center surrogate exposes frozen-support candidate invalidation without moving state"):
    val fixture = translationFixture(side = 25, shiftMm = 2.0)
    val optimization = HalfFlowCc
      .optimize(fixture.fixed, fixture.moving, fixture.initial, compactPlan(standardizedCenter = true))
      .fold(error => fail(error.message), identity)
    val error = midpointTranslationRms(optimization.state, fixture.shiftMm, margin = 6)
    assertEqualsDouble(error, fixture.shiftMm, 1e-12)
    assertEquals(optimization.diagnostics.acceptedSteps, 0)
    assert(optimization.diagnostics.levels.flatMap(_.trace).forall(_.candidateLoss.isNaN))
    assertNoFolds(optimization.state.fixed.residual)
    assertNoFolds(optimization.state.moving.residual)

  test("production directional derivative passes identity, nonidentity, swap, and pyramid spacings"):
    Vector(1.0, 2.0, 4.0).foreach: spacing =>
      val affine = DMat.fromRows(
        Vector(
          Vector(spacing, 0.0, 0.0, 0.0),
          Vector(0.0, spacing, 0.0, 0.0),
          Vector(0.0, 0.0, spacing, 0.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )
      )
      val fixture = derivativeFixture(GridSpec(Vector(13, 13, 13), affine), s"spacing-$spacing")
      assertProductionDerivative(fixture.fixed, fixture.moving, fixture.initial, s"identity-$spacing")
      val displaced = fixture.initial
        .advance(HalfStep.fromPairedFlow(
          PairedScalingAndSquaring
            .expHalfPair(constantVelocity(fixture.initial.work, 0.24, -0.11, 0.07))
            .fold(error => fail(error.message), identity)
        ))
        .fold(error => fail(error.message), identity)
      assertProductionDerivative(fixture.fixed, fixture.moving, displaced, s"nonidentity-$spacing")
      assertProductionDerivative(fixture.moving, fixture.fixed, displaced.swap, s"swapped-$spacing")

  test("production directional derivative passes on an anisotropic oblique grid"):
    val affine = DMat.fromRows(
      Vector(
        Vector(2.0, 0.3, 0.0, 10.0),
        Vector(0.0, 1.5, 0.2, -4.0),
        Vector(0.1, 0.0, 2.5, 3.0),
        Vector(0.0, 0.0, 0.0, 1.0)
      )
    )
    val fixture = derivativeFixture(GridSpec(Vector(13, 13, 13), affine), "oblique")
    assertProductionDerivative(fixture.fixed, fixture.moving, fixture.initial, "oblique")

  test("production lane removes at least eighty percent of two- and four-millimetre translations"):
    Vector((25, 2.0), (33, 4.0)).foreach: (side, shift) =>
      val capture = runCapture(side, shift)
      assert(
        capture.errorMm <= 0.2 * shift,
        s"$shift mm capture left ${capture.errorMm} mm RMS; diagnostics=${capture.optimization.diagnostics}"
      )
      if shift == 2.0 then assert(capture.exported.isRight, s"2 mm export was not admitted: ${capture.exported}")
      else assertTypedTopologyFailure(capture.exported)

  test("continuous export repairs the eight-millimetre case and retains the twelve-millimetre failure"):
    Vector((41, 8.0), (49, 12.0)).foreach: (side, shift) =>
      val capture = runCapture(side, shift)
      assert(capture.errorMm < shift, s"$shift mm capture did not improve: ${capture.errorMm} mm")
      // The 8 mm failure originated in discontinuous inverse boundary handling.
      // runCapture independently checks both endpoint Jacobians on admission.
      if shift == 8.0 then assert(capture.exported.isRight, s"8 mm export was not admitted: ${capture.exported}")
      else assertTypedTopologyFailure(capture.exported)

  private final case class TranslationFixture(
      grid: GridSpec,
      shiftMm: Double,
      fixed: RegistrationImage[Fixed],
      moving: RegistrationImage[Moving],
      initial: ForwardMidpoint[Work, Fixed, Moving]
  )

  private def derivativeFixture(grid: GridSpec, tag: String): TranslationFixture =
    val work = Frame[Work](SpatialDomainId(s"cc-derivative-work-$tag"), grid)
    val fixedFrame = Frame[Fixed](SpatialDomainId(s"cc-derivative-fixed-$tag"), grid)
    val movingFrame = Frame[Moving](SpatialDomainId(s"cc-derivative-moving-$tag"), grid)
    val fixedValues = values(grid): index =>
      val point = voxel(grid, index)
      3.0 + math.sin(0.17 * point.x + 0.09 * point.y) + 0.6 * math.cos(0.13 * point.z - 0.05 * point.x)
    val movingValues = values(grid): index =>
      val point = voxel(grid, index)
      2.0 + 1.2 * math.sin(0.17 * point.x + 0.09 * point.y + 0.23) +
        0.5 * math.cos(0.13 * point.z - 0.05 * point.x - 0.19)
    val fixedVolume = NeuroVol.fromLinear[Double](fixedValues, grid.toNeuroSpace, "fixed-derivative")
    val movingVolume = NeuroVol.fromLinear[Double](movingValues, grid.toNeuroSpace, "moving-derivative")
    val fixed = RegistrationImage.make(fixedFrame, fixedVolume).fold(error => fail(error.message), identity)
    val moving = RegistrationImage.make(movingFrame, movingVolume).fold(error => fail(error.message), identity)
    val initial = ForwardMidpoint.identity(work, fixedFrame, movingFrame).fold(error => fail(error.message), identity)
    TranslationFixture(grid, 0.0, fixed, moving, initial)

  private final case class CaptureResult(
      errorMm: Double,
      optimization: HalfFlowCcOptimization[Work, Fixed, Moving],
      exported: Either[ForwardExportError, ForwardMidpointExport[Fixed, Moving]]
  )

  private def runCapture(side: Int, shiftMm: Double): CaptureResult =
    val fixture = translationFixture(side, shiftMm)
    val plan = compactPlan()
    val optimization = HalfFlowCc
      .optimize(fixture.fixed, fixture.moving, fixture.initial, plan)
      .fold(error => fail(error.message), identity)
    val error = midpointTranslationRms(optimization.state, fixture.shiftMm, margin = 6)
    assert(optimization.diagnostics.acceptedSteps > 0)
    assert(optimization.diagnostics.levels.last.finalLoss < optimization.diagnostics.levels.head.initialLoss)
    assertNoFolds(optimization.state.fixed.residual)
    assertNoFolds(optimization.state.moving.residual)
    val exported = ForwardMidpointExporter.build(optimization.state, plan.exportConfig)
    exported.foreach: admitted =>
      assertNoFolds(admitted.transform.forward)
      assertNoFolds(admitted.transform.backward)
    CaptureResult(error, optimization, exported)

  private def assertTypedTopologyFailure(
      exported: Either[ForwardExportError, ForwardMidpointExport[Fixed, Moving]]
  ): Unit =
    exported match
      case Left(ForwardExportError.ExportedTopologyInvalid(_, _, nonPositive)) => assert(nonPositive > 0)
      case other => fail(s"expected typed export topology failure, obtained $other")

  private def translationFixture(side: Int, shiftMm: Double): TranslationFixture =
    val grid = GridSpec.identity(Vector(side, side, side))
    val work = Frame[Work](SpatialDomainId(s"cc-work-$side-$shiftMm"), grid)
    val fixedFrame = Frame[Fixed](SpatialDomainId(s"cc-fixed-$side-$shiftMm"), grid)
    val movingFrame = Frame[Moving](SpatialDomainId(s"cc-moving-$side-$shiftMm"), grid)
    val fixedValues = values(grid): index =>
      val point = voxel(grid, index)
      signal(point.x, point.y, point.z, side)
    val movingValues = values(grid): index =>
      val point = voxel(grid, index)
      signal(point.x - shiftMm, point.y, point.z, side)
    val fixedVolume = NeuroVol.fromLinear[Double](fixedValues, grid.toNeuroSpace, "fixed")
    val movingVolume = NeuroVol.fromLinear[Double](movingValues, grid.toNeuroSpace, "moving")
    val fixed = RegistrationImage.make(fixedFrame, fixedVolume).fold(error => fail(error.message), identity)
    val moving = RegistrationImage.make(movingFrame, movingVolume).fold(error => fail(error.message), identity)
    val initial = ForwardMidpoint.identity(work, fixedFrame, movingFrame).fold(error => fail(error.message), identity)
    TranslationFixture(grid, shiftMm, fixed, moving, initial)

  private def compactPlan(
      action: HalfFlowCcAction = HalfFlowCcAction.SymmetricMidpoint,
      standardizedCenter: Boolean = false
  ): HalfFlowCcPlan =
    val cc = NeighborhoodCcConfig
      .make(
        radius = VoxelWindowRadius(2, 2, 2),
        minimumSupportFraction = 0.15,
        fullSupportFraction = 0.7,
        minimumVarianceFraction = 1e-7,
        fullVarianceFraction = 1e-5,
        denominatorEpsilonFraction = 1e-7
      )
      .fold(error => fail(error.message), identity)
    val metric =
      if standardizedCenter then
        val feature = T1FeatureConfig
          .make(
            Vector(FeatureRadiusMm(3.0)),
            minimumValidWindowFraction = 0.60,
            minimumActiveVoxels = 64
          )
          .fold(error => fail(error.message), identity)
        HalfFlowCcMetric.StandardizedCenter(feature)
      else HalfFlowCcMetric.TrueNeighborhoodCc
    val levels = Vector(
      HalfFlowCcLevel
        .make(2, 1.0, cc, smoothSigmaMm = 10.0, maximumStepMm = 1.0, targetAcceptedSteps = 10, maximumAttempts = 24, metric = metric)
        .fold(error => fail(error.message), identity),
      HalfFlowCcLevel
        .make(1, 0.0, cc, smoothSigmaMm = 6.0, maximumStepMm = 0.6, targetAcceptedSteps = 10, maximumAttempts = 24, metric = metric)
        .fold(error => fail(error.message), identity)
    )
    val control = HalfFlowCcControlConfig
      .make(
        initialDamping = 1e-4,
        minimumDamping = 1e-8,
        maximumDamping = 1.0,
        maximumObjectiveRetries = 6,
        maximumGeometryRetries = 8,
        maximumIntegrationRetries = 5
      )
      .fold(error => fail(error.message), identity)
    val inverse = ResidualInverseConfig
      .make(
        shrinks = Vector(2, 1),
        iterationsPerLevel = 50,
        maximumInteriorErrorMm = 0.2,
        maximumInteriorErrorVox = 0.2,
        interiorMargin = 6
      )
      .fold(error => fail(error.message), identity)
    HalfFlowCcPlan
      .make(
        levels,
        supportSigmaMm = 1.5,
        minimumUsefulStepMm = 1e-6,
        maximumIntegrationInverseErrorMm = 0.03,
        control = control,
        exportConfig = inverse,
        action = action
      )
      .fold(error => fail(error.message), identity)

  private final case class TestWarp(values: Array[Double], valid: Array[Boolean])

  private def assertProductionDerivative[W0, F0, M0](
      fixed: RegistrationImage[F0],
      moving: RegistrationImage[M0],
      state: ForwardMidpoint[W0, F0, M0],
      clue: String
  ): Unit =
    val grid = state.work.grid
    val n = grid.nVoxels
    val currentFixed = warpNative(fixed, state.fixed)
    val currentMoving = warpNative(moving, state.moving)
    val support = values(grid): index =>
      val x = index % grid.shape.x
      val yz = index / grid.shape.x
      val y = yz % grid.shape.y
      val z = yz / grid.shape.y
      if x >= 3 && x < grid.shape.x - 3 && y >= 3 && y < grid.shape.y - 3 &&
          z >= 3 && z < grid.shape.z - 3 && currentFixed.valid(index) && currentMoving.valid(index)
      then 1.0
      else 0.0
    val config = compactPlan().levels.last.cc
    val frozen = NeighborhoodCc
      .prepare(currentFixed.values, currentMoving.values, support, grid, config)
      .fold(error => fail(error.message), identity)
    val evaluation = NeighborhoodCc
      .valueAndGradient(currentFixed.values, currentMoving.values, frozen)
      .fold(error => fail(error.message), identity)
    val fixedSpatial = spatialGradient(currentFixed, grid)
    val movingSpatial = spatialGradient(currentMoving, grid)
    val direction = constantDirection(grid, 0.73, -0.21, 0.16)
    var analytic = 0.0
    var component = 0
    while component < 3 do
      val offset = component * n
      var index = 0
      while index < n do
        val velocityGradient = 0.5 * (
          evaluation.fixedIntensityGradient(index) * fixedSpatial._1(offset + index) -
            evaluation.movingIntensityGradient(index) * movingSpatial._1(offset + index)
        )
        if fixedSpatial._2(index) && movingSpatial._2(index) && support(index) > 0.0 then
          analytic += velocityGradient * direction(offset + index)
        index += 1
      component += 1
    val epsilonScaleMm = Affine.voxelSizes(grid.affine).min
    val epsilons = Vector(1.0, 5e-1, 2e-1, 1e-1, 5e-2, 2e-2, 1e-2, 5e-3, 2e-3, 1e-3)
      .map(_ * epsilonScaleMm)
    val errors = epsilons.map: epsilon =>
      val plus = productionLoss(fixed, moving, state, direction, epsilon, frozen)
      val minus = productionLoss(fixed, moving, state, direction, -epsilon, frozen)
      val finiteDifference = (plus - minus) / (2.0 * epsilon)
      math.abs(finiteDifference - analytic) /
        math.max(1e-12, math.abs(finiteDifference) + math.abs(analytic))
    val hasConvergence = errors.sliding(3).exists:
      case Vector(first, second, third) => second < first && third < second
      case _ => false
    assert(errors.min <= 5e-3, s"$clue production derivative failed: errors=$errors analytic=$analytic")
    assert(hasConvergence, s"$clue production derivative has no two-step convergence region: $errors")

  private def productionLoss[W0, F0, M0](
      fixed: RegistrationImage[F0],
      moving: RegistrationImage[M0],
      state: ForwardMidpoint[W0, F0, M0],
      direction: Array[Double],
      epsilon: Double,
      frozen: FrozenCcWeights
  ): Double =
    val velocity = scaledVelocity(state.work, direction, epsilon)
    val half = HalfStep.fromPairedFlow(
      PairedScalingAndSquaring.expHalfPair(velocity).fold(error => fail(error.message), identity)
    )
    val candidate = state.advance(half).fold(error => fail(error.message), identity)
    val warpedFixed = warpNative(fixed, candidate.fixed)
    val warpedMoving = warpNative(moving, candidate.moving)
    NeighborhoodCc
      .value(warpedFixed.values, warpedMoving.values, frozen)
      .fold(error => fail(error.message), identity)

  private def warpNative[W0, E](
      source: RegistrationImage[E],
      arm: ForwardMidpointArm[W0, E]
  ): TestWarp =
    val pull = arm.denseForward.fold(error => fail(error.message), identity)
    val warped = HalfFlowKernels.pullScalar(
      source.volume,
      pull.sourceCoordinates,
      pull.validity,
      source.validity,
      0.0
    )
    TestWarp(warped.values.copyLegacyLinear, warped.valid.copyLegacyLinear)

  private def spatialGradient(source: TestWarp, grid: GridSpec): (Array[Double], Array[Boolean]) =
    val gradient = PrimitiveBuffers.ofSize[Double](3 * grid.nVoxels)
    val valid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    MaskedLocalStats.physicalGradientChannelsInto(
      source.values,
      source.valid,
      grid,
      1,
      gradient,
      valid,
      MaskedLocalStatsWorkspace(grid)
    )
    (gradient, valid)

  private def constantDirection(
      grid: GridSpec,
      x: Double,
      y: Double,
      z: Double
  ): Array[Double] =
    val n = grid.nVoxels
    val values = PrimitiveBuffers.ofSize[Double](3 * n)
    var index = 0
    while index < n do
      values(index) = x
      values(index + n) = y
      values(index + 2 * n) = z
      index += 1
    values

  private def constantVelocity[A](
      frame: Frame[A],
      x: Double,
      y: Double,
      z: Double
  ): Velocity[A] =
    scaledVelocity(frame, constantDirection(frame.grid, x, y, z), 1.0)

  private def scaledVelocity[A](
      frame: Frame[A],
      direction: Array[Double],
      scale: Double
  ): Velocity[A] =
    val values = PrimitiveBuffers.ofSize[Double](direction.length)
    var index = 0
    while index < direction.length do
      values(index) = scale * direction(index)
      index += 1
    val field = DenseVectorField.fromLegacyPlanar(
      frame.grid,
      values,
      DenseVectorFieldKind.Displacement
    )
    Velocity.make(frame, field).fold(error => fail(error.message), identity)

  private def signal(x: Double, y: Double, z: Double, side: Int): Double =
    val center = 0.5 * (side - 1).toDouble
    val broad = gaussian(x, y, z, center - 2.0, center + 1.0, center, 5.0)
    val left = gaussian(x, y, z, center - 5.0, center - 3.0, center + 2.0, 2.2)
    val right = gaussian(x, y, z, center + 4.0, center + 3.0, center - 3.0, 2.8)
    100.0 * broad + 45.0 * left + 65.0 * right +
      3.0 * math.sin(0.31 * x + 0.17 * y) + 2.0 * math.cos(0.23 * z - 0.11 * x)

  private def gaussian(
      x: Double,
      y: Double,
      z: Double,
      cx: Double,
      cy: Double,
      cz: Double,
      sigma: Double
  ): Double =
    val squared = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz)
    math.exp(-0.5 * squared / (sigma * sigma))

  private def midpointTranslationRms(
      state: ForwardMidpoint[Work, Fixed, Moving],
      shift: Double,
      margin: Int
  ): Double =
    val fixed = state.fixed.denseForward.fold(error => fail(error.message), identity)
    val moving = state.moving.denseForward.fold(error => fail(error.message), identity)
    val grid = state.work.grid
    val n = grid.nVoxels
    var sum = 0.0
    var count = 0
    var index = 0
    while index < n do
      val x = index % grid.shape.x
      val yz = index / grid.shape.x
      val y = yz % grid.shape.y
      val z = yz / grid.shape.y
      if x >= margin && x < grid.shape.x - margin && y >= margin && y < grid.shape.y - margin &&
          z >= margin && z < grid.shape.z - margin
      then
        val dx =
          moving.sourceCoordinates.linearComponent(index, 0) -
            fixed.sourceCoordinates.linearComponent(index, 0) -
            shift
        val dy =
          moving.sourceCoordinates.linearComponent(index, 1) -
            fixed.sourceCoordinates.linearComponent(index, 1)
        val dz =
          moving.sourceCoordinates.linearComponent(index, 2) -
            fixed.sourceCoordinates.linearComponent(index, 2)
        sum += dx * dx + dy * dy + dz * dz
        count += 1
      index += 1
    math.sqrt(sum / count.toDouble)

  private def assertNoFolds[A, B](pull: DensePull[A, B]): Unit =
    val grid = pull.from.grid
    val determinants = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val valid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    val reduction = JacobianReduction()
    HalfFlowKernels.jacobianDeterminantsReduceInto(
      pull.sourceCoordinates,
      determinants,
      valid,
      DenseFieldSampler(grid),
      pull.validity,
      reduction
    )
    assertEquals(reduction.nonPositive, 0)

  private def foldedPull[A](frame: Frame[A]): DensePull[A, A] =
    val grid = frame.grid
    val n = grid.nVoxels
    val coordinates = PrimitiveBuffers.ofSize[Double](3 * n)
    var index = 0
    while index < n do
      val point = voxel(grid, index)
      coordinates(index) = -point.x
      coordinates(index + n) = point.y
      coordinates(index + 2 * n) = point.z
      index += 1
    DensePull
      .make(
        frame,
        frame,
        DenseVectorField.fromLegacyPlanar(grid, coordinates, DenseVectorFieldKind.SourceCoordinates)
      )
      .fold(error => fail(error.message), identity)

  private def assertCoordinatesEqual(
      actual: DenseVectorField,
      expected: DenseVectorField,
      tolerance: Double
  ): Unit =
    assertEquals(actual.grid, expected.grid)
    var index = 0
    while index < actual.grid.nVoxels do
      var component = 0
      while component < 3 do
        assertEqualsDouble(
          actual.linearComponent(index, component),
          expected.linearComponent(index, component),
          tolerance
        )
        component += 1
      index += 1

  private def values(grid: GridSpec)(f: Int => Double): Array[Double] =
    val result = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var index = 0
    while index < result.length do
      result(index) = f(index)
      index += 1
    result

  private def voxel(grid: GridSpec, index: Int): SpatialPoint =
    val x = index % grid.shape.x
    val yz = index / grid.shape.x
    val y = yz % grid.shape.y
    val z = yz / grid.shape.y
    grid.voxelToWorld(SpatialPoint(x.toDouble, y.toDouble, z.toDouble))
