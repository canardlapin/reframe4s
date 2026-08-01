package reframe4s.halfflow

import reframe4s.halfflow.internal.*

class ForwardMidpointSuite extends munit.FunSuite:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving

  test("opposite half steps advance only authoritative forward residuals"):
    val frames = fixture()
    val state = ForwardMidpoint
      .identity(frames.work, frames.fixed, frames.moving)
      .fold(error => fail(error.message), identity)
    val plus = affinePull(frames.work, scale = 1.0, translation = 0.4)
    val minus = affinePull(frames.work, scale = 1.0, translation = -0.4)
    val step = HalfStep.make(plus, minus).fold(error => fail(error.message), identity)
    val next = state.advance(step).fold(error => fail(error.message), identity)
    val center = at(frames.work.grid, 3, 3, 3)
    assertEqualsDouble(next.fixed.residual.sourceCoordinates.linearComponent(center, 0), 3.4, 1e-12)
    assertEqualsDouble(next.moving.residual.sourceCoordinates.linearComponent(center, 0), 2.6, 1e-12)
    assertEquals(next.fixed.residual.from, frames.work)
    assertEquals(next.moving.residual.from, frames.work)

  test("right-composed increments have the derived order and identity boundary extension"):
    val frames = fixture()
    val initial = ForwardMidpoint
      .identity(frames.work, frames.fixed, frames.moving)
      .fold(error => fail(error.message), identity)
    val scaleStep = HalfStep
      .make(
        affinePull(frames.work, scale = 1.1, translation = 0.0),
        affinePull(frames.work, scale = 0.9, translation = 0.0)
      )
      .fold(error => fail(error.message), identity)
    val translatedStep = HalfStep
      .make(
        affinePull(frames.work, scale = 1.0, translation = 0.5),
        affinePull(frames.work, scale = 1.0, translation = -0.5)
      )
      .fold(error => fail(error.message), identity)
    val next = initial
      .advance(scaleStep)
      .flatMap(_.advance(translatedStep))
      .fold(error => fail(error.message), identity)
    val center = at(frames.work.grid, 3, 3, 3)
    assertEqualsDouble(next.fixed.residual.sourceCoordinates.linearComponent(center, 0), 1.1 * 3.5, 1e-12)
    assertEqualsDouble(next.moving.residual.sourceCoordinates.linearComponent(center, 0), 0.9 * 2.5, 1e-12)
    val boundary = at(frames.work.grid, 6, 3, 3)
    assertEqualsDouble(next.fixed.residual.sourceCoordinates.linearComponent(boundary, 0), 6.5, 1e-12)

  test("exact affine factors remain separate and swap is structural"):
    val frames = fixture()
    val fixedAffine = AffineIso
      .make(frames.work, frames.fixed, affine(1.0, 10.0))
      .fold(error => fail(error.message), identity)
    val movingAffine = AffineIso
      .make(frames.work, frames.moving, affine(1.0, -4.0))
      .fold(error => fail(error.message), identity)
    val state = ForwardMidpoint
      .make(ForwardMidpointArm.identity(fixedAffine), ForwardMidpointArm.identity(movingAffine))
      .fold(error => fail(error.message), identity)
    val step = HalfStep
      .make(
        affinePull(frames.work, scale = 1.0, translation = 0.25),
        affinePull(frames.work, scale = 1.0, translation = -0.25)
      )
      .fold(error => fail(error.message), identity)
    val next = state.advance(step).fold(error => fail(error.message), identity)
    val fixedDense = next.fixed.denseForward.fold(error => fail(error.message), identity)
    val movingDense = next.moving.denseForward.fold(error => fail(error.message), identity)
    val center = at(frames.work.grid, 3, 3, 3)
    assertEqualsDouble(fixedDense.sourceCoordinates.linearComponent(center, 0), 13.25, 1e-12)
    assertEqualsDouble(movingDense.sourceCoordinates.linearComponent(center, 0), -1.25, 1e-12)
    val swapped = next.swap
    assertEquals(swapped.fixed.affine.transform, next.moving.affine.transform)
    assertEquals(swapped.moving.affine.transform, next.fixed.affine.transform)
    val exportConfig = ResidualInverseConfig
      .make(shrinks = Vector(1), iterationsPerLevel = 4, interiorMargin = 1)
      .fold(error => fail(error.message), identity)
    val exported = ForwardMidpointExporter.build(state, exportConfig).fold(error => fail(error.message), identity)
    assertEqualsDouble(exported.transform.forward.sourceCoordinates.linearComponent(center, 0), -11.0, 1e-12)
    assertEqualsDouble(exported.transform.backward.sourceCoordinates.linearComponent(center, 0), 17.0, 1e-12)

  test("smooth forward midpoint arms retain positive sampled Jacobians"):
    val frames = fixture()
    val state = ForwardMidpoint
      .identity(frames.work, frames.fixed, frames.moving)
      .fold(error => fail(error.message), identity)
    val step = HalfStep
      .make(
        affinePull(frames.work, scale = 1.05, translation = 0.1),
        affinePull(frames.work, scale = 0.95, translation = -0.1)
      )
      .fold(error => fail(error.message), identity)
    val next = state.advance(step).fold(error => fail(error.message), identity)
    assertPositiveJacobians(next.fixed.residual)
    assertPositiveJacobians(next.moving.residual)

  test("typed geometry verdicts reject only incremental or accumulated Jacobian failures"):
    val frames = fixture()
    val identityPull = affinePull(frames.work, scale = 1.0, translation = 0.0)
    val folded = affinePull(frames.work, scale = -1.0, translation = 6.0)
    val step = HalfStep.make(folded, identityPull).fold(error => fail(error.message), identity)
    val incremental = ForwardGeometry.incremental(step)
    assert(incremental._1.isInstanceOf[GeometryVerdict.IncrementJacobianTooSmall])
    val initial = ForwardMidpoint
      .identity(frames.work, frames.fixed, frames.moving)
      .fold(error => fail(error.message), identity)
    val candidate = initial.advance(step).fold(error => fail(error.message), identity)
    val accumulated = ForwardGeometry.accumulated(candidate)
    assertEquals(
      accumulated._1,
      GeometryVerdict.AccumulatedJacobianTooSmall(MidpointArmName.Fixed, -1.0)
    )

  test("paired-flow inverse error changes integration depth, not anatomical geometry"):
    val frames = fixture()
    val plus = affinePull(frames.work, scale = 1.0, translation = 0.25)
    val minus = affinePull(frames.work, scale = 1.0, translation = -0.25)
    val accurate = HalfStep.make(plus, minus).fold(error => fail(error.message), identity)
    assertEquals(HalfStepNumerics.evaluate(accurate, 1e-10), NumericalVerdict.Accurate)
    val inaccurate = HalfStep.make(plus, plus).fold(error => fail(error.message), identity)
    HalfStepNumerics.evaluate(inaccurate, 0.1) match
      case NumericalVerdict.IncreaseIntegrationDepth(error) => assert(error > 0.1)
      case other => fail(s"expected integration-depth request, got $other")

  test("multiresolution residual inversion exports a round-trip pair"):
    val grid = GridSpec.identity(Vector(13, 13, 13))
    val work = Frame[Work](SpatialDomainId("export-work"), grid)
    val fixed = Frame[Fixed](SpatialDomainId("export-fixed"), grid)
    val moving = Frame[Moving](SpatialDomainId("export-moving"), grid)
    val state = ForwardMidpoint.identity(work, fixed, moving).fold(error => fail(error.message), identity)
    val velocity = smoothVelocity(work, amplitude = 0.35)
    val flow = PairedScalingAndSquaring.expHalfPair(velocity).fold(error => fail(error.message), identity)
    val advanced = state
      .advance(HalfStep.fromPairedFlow(flow))
      .fold(error => fail(error.message), identity)
    val config = ResidualInverseConfig
      .make(
        shrinks = Vector(2, 1),
        iterationsPerLevel = 50,
        iterationToleranceMm = 1e-5,
        maximumInteriorErrorMm = 0.01,
        maximumInteriorErrorVox = 0.01,
        interiorMargin = 2
      )
      .fold(error => fail(error.message), identity)
    val exported = ForwardMidpointExporter.build(advanced, config).fold(error => fail(error.message), identity)
    assert(exported.fixedResidualInverse.iterationsByLevel.length == 2)
    assert(exported.movingResidualInverse.iterationsByLevel.length == 2)
    assert(exported.fixedResidualInverse.maximumInteriorMm <= 0.01)
    assert(exported.movingResidualInverse.maximumInteriorMm <= 0.01)
    assert(exported.fixedResidualInverse.forwardThenInverse.p99Mm <= 0.01)
    assert(exported.movingResidualInverse.inverseThenForward.p99Mm <= 0.01)
    assert(exported.endpointRoundTrip.forwardThenBackward.maximumMm.exists(_ <= 0.02))
    assert(exported.endpointRoundTrip.backwardThenForward.maximumMm.exists(_ <= 0.02))

  test("residual refinement enforces both inverse compositions after accumulated nonlinear updates"):
    val grid = GridSpec.identity(Vector(25, 25, 25))
    val work = Frame[Work](SpatialDomainId("two-sided-inverse-work"), grid)
    val fixed = Frame[Fixed](SpatialDomainId("two-sided-inverse-fixed"), grid)
    val moving = Frame[Moving](SpatialDomainId("two-sided-inverse-moving"), grid)
    var state = ForwardMidpoint.identity(work, fixed, moving).fold(error => fail(error.message), identity)
    var update = 0
    while update < 6 do
      val flow = PairedScalingAndSquaring
        .expHalfPair(smoothVelocity(work, amplitude = 0.55))
        .fold(error => fail(error.message), identity)
      state = state.advance(HalfStep.fromPairedFlow(flow)).fold(error => fail(error.message), identity)
      update += 1
    val config = ResidualInverseConfig
      .make(
        shrinks = Vector(2, 1),
        iterationsPerLevel = 60,
        iterationToleranceMm = 1e-5,
        maximumInteriorErrorMm = 0.05,
        maximumInteriorErrorVox = 0.05,
        interiorMargin = 3
      )
      .fold(error => fail(error.message), identity)
    val refined = ResidualInverseRefiner.refine(state.fixed.residual, config)
    assert(refined.report.forwardThenInverse.maximumMm <= 0.05, refined.report.toString)
    assert(refined.report.inverseThenForward.maximumMm <= 0.05, refined.report.toString)

  test("residual refinement updates every voxel without a parity checkerboard"):
    val grid = GridSpec.identity(Vector(11, 11, 11))
    val work = Frame[Work](SpatialDomainId("inverse-parity-work"), grid)
    val forward = affinePull(work, scale = 1.05, translation = 0.0)
    val config = ResidualInverseConfig
      .make(
        shrinks = Vector(1),
        iterationsPerLevel = 1,
        relaxation = 0.8,
        iterationToleranceMm = 1e-15,
        maximumInteriorErrorMm = 10.0,
        maximumInteriorErrorVox = 10.0,
        interiorMargin = 2
      )
      .fold(error => fail(error.message), identity)
    val inverse = ResidualInverseRefiner.refine(forward, config).inverse.sourceCoordinates
    val x2 = inverse.linearComponent(at(grid, 2, 5, 5), 0)
    val x3 = inverse.linearComponent(at(grid, 3, 5, 5), 0)
    val x4 = inverse.linearComponent(at(grid, 4, 5, 5), 0)
    val x5 = inverse.linearComponent(at(grid, 5, 5, 5), 0)
    assertEqualsDouble(x3 - x2, x4 - x3, 1e-10)
    assertEqualsDouble(x4 - x3, x5 - x4, 1e-10)

  test("export returns a typed failure when its inverse tolerance is impossible"):
    val grid = GridSpec.identity(Vector(11, 11, 11))
    val work = Frame[Work](SpatialDomainId("failure-work"), grid)
    val fixed = Frame[Fixed](SpatialDomainId("failure-fixed"), grid)
    val moving = Frame[Moving](SpatialDomainId("failure-moving"), grid)
    val state = ForwardMidpoint.identity(work, fixed, moving).fold(error => fail(error.message), identity)
    val flow = PairedScalingAndSquaring
      .expHalfPair(smoothVelocity(work, amplitude = 0.4))
      .fold(error => fail(error.message), identity)
    val advanced = state.advance(HalfStep.fromPairedFlow(flow)).fold(error => fail(error.message), identity)
    val impossible = ResidualInverseConfig
      .make(
        shrinks = Vector(1),
        iterationsPerLevel = 1,
        iterationToleranceMm = 1e-15,
        maximumInteriorErrorMm = 1e-15,
        maximumInteriorErrorVox = 1e-15,
        interiorMargin = 2
      )
      .fold(error => fail(error.message), identity)
    val candidate = ForwardMidpointExporter.inspect(advanced, impossible).fold(error => fail(error.message), identity)
    assert(candidate.transform.forward.sourceCoordinates.values.size > 0)
    ForwardMidpointExporter.admit(candidate, impossible) match
      case Left(ForwardExportError.InverseDidNotConverge(_, report)) =>
        assert(report.maximumInteriorMm > 1e-15)
      case other => fail(s"expected typed inverse failure, got $other")

  test("approximate inverse caches are explicitly refreshable numerical state"):
    val grid = GridSpec.identity(Vector(11, 11, 11))
    val work = Frame[Work](SpatialDomainId("cache-work"), grid)
    val flow = PairedScalingAndSquaring
      .expHalfPair(smoothVelocity(work, amplitude = 0.25))
      .fold(error => fail(error.message), identity)
    val config = ResidualInverseConfig
      .make(
        shrinks = Vector(2, 1),
        iterationsPerLevel = 30,
        maximumInteriorErrorMm = 0.01,
        maximumInteriorErrorVox = 0.01
      )
      .fold(error => fail(error.message), identity)
    val cache = ApproximateInverseCache.refresh(flow.pair.forward, config, previousRefreshes = 2)
    assertEquals(cache.refreshes, 3)
    assert(cache.measuredErrorMm <= 0.01)

  private final case class Frames(work: Frame[Work], fixed: Frame[Fixed], moving: Frame[Moving])

  private def fixture(): Frames =
    val grid = GridSpec.identity(Vector(7, 7, 7))
    Frames(
      Frame[Work](SpatialDomainId("forward-work"), grid),
      Frame[Fixed](SpatialDomainId("forward-fixed"), grid),
      Frame[Moving](SpatialDomainId("forward-moving"), grid)
    )

  private def affinePull[A](frame: Frame[A], scale: Double, translation: Double): DensePull[A, A] =
    val grid = frame.grid
    val n = grid.nVoxels
    val values = PrimitiveBuffers.ofSize[Double](3 * n)
    var index = 0
    while index < n do
      val x = index % grid.shape.x
      val yz = index / grid.shape.x
      val y = yz % grid.shape.y
      val z = yz / grid.shape.y
      values(index) = scale * x.toDouble + translation
      values(index + n) = y.toDouble
      values(index + 2 * n) = z.toDouble
      index += 1
    DensePull
      .make(
        frame,
        frame,
        DenseVectorField.fromLegacyPlanar(grid, values, DenseVectorFieldKind.SourceCoordinates)
      )
      .fold(error => fail(error.message), identity)

  private def affine(scale: Double, translation: Double): Affine3D =
    Affine3D
      .make(
        DMat.fromRows(
          Vector(
            Vector(scale, 0.0, 0.0, translation),
            Vector(0.0, 1.0, 0.0, 0.0),
            Vector(0.0, 0.0, 1.0, 0.0),
            Vector(0.0, 0.0, 0.0, 1.0)
          )
        )
      )
      .fold(error => fail(error.message), identity)

  private def smoothVelocity[A](frame: Frame[A], amplitude: Double): Velocity[A] =
    val grid = frame.grid
    val n = grid.nVoxels
    val values = PrimitiveBuffers.ofSize[Double](3 * n)
    val dx = (grid.shape.x - 1).toDouble
    val dy = (grid.shape.y - 1).toDouble
    val dz = (grid.shape.z - 1).toDouble
    var index = 0
    while index < n do
      val x = (index % grid.shape.x).toDouble
      val yz = index / grid.shape.x
      val y = (yz % grid.shape.y).toDouble
      val z = (yz / grid.shape.y).toDouble
      val envelope = math.sin(math.Pi * x / dx) * math.sin(math.Pi * y / dy) * math.sin(math.Pi * z / dz)
      values(index) = amplitude * envelope * math.sin(2.0 * math.Pi * y / dy)
      values(index + n) = 0.6 * amplitude * envelope * math.sin(2.0 * math.Pi * z / dz)
      values(index + 2 * n) = 0.4 * amplitude * envelope * math.sin(2.0 * math.Pi * x / dx)
      index += 1
    val field = DenseVectorField.fromLegacyPlanar(grid, values, DenseVectorFieldKind.Displacement)
    Velocity.make(frame, field).fold(error => fail(error.message), identity)

  private def assertPositiveJacobians[A](pull: DensePull[A, A]): Unit =
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
    assert(reduction.minimumOrNaN > 0.0)

  private def at(grid: GridSpec, x: Int, y: Int, z: Int): Int =
    x + grid.shape.x * y + grid.shape.x * grid.shape.y * z
