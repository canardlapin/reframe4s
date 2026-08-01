package reframe4s.halfflow

import reframe4s.halfflow.internal.*

class NeighborhoodCcSuite extends munit.FunSuite:
  private sealed trait Work

  test("separable neighborhood CC matches direct overlapping windows"):
    val grid = GridSpec.identity(Vector(9, 8, 7))
    val randomFixed = values(grid)(index => pseudoRandom(index, 17) + 0.03 * (index % 9))
    val randomMoving = values(grid)(index => 0.7 * pseudoRandom(index, 29) - 0.02 * (index % 8))
    val anticorrelated = copyOf(randomFixed)
    var index = 0
    while index < anticorrelated.length do
      anticorrelated(index) = 4.0 - 1.8 * anticorrelated(index)
      index += 1
    val support = values(grid): index =>
      val x = index % grid.shape.x
      val y = (index / grid.shape.x) % grid.shape.y
      if x == 0 then 0.0
      else if y == 0 then 0.35
      else if (index % 11) == 0 then 0.6
      else 1.0
    val flatFixed = copyOf(randomFixed)
    val flatMoving = copyOf(randomMoving)
    fillBlock(grid, flatFixed, 2, 5, 2, 5, 1, 4, 3.0)
    fillBlock(grid, flatMoving, 2, 5, 2, 5, 1, 4, -2.0)
    val cases = Vector(
      (randomFixed, randomMoving, support),
      (randomFixed, anticorrelated, support),
      (flatFixed, flatMoving, support)
    )
    Vector(2, 3).foreach: radius =>
      val config = ccConfig(radius)
      cases.foreach: (fixed, moving, weights) =>
        val frozen = NeighborhoodCc
          .prepare(fixed, moving, weights, grid, config)
          .fold(error => fail(error.message), identity)
        val fast = NeighborhoodCc.value(fixed, moving, frozen).fold(error => fail(error.message), identity)
        val direct = naiveValue(fixed, moving, frozen)
        assertEqualsDouble(fast, direct, 2e-12)

  test("frozen CC gradient passes an interior central-difference ladder"):
    val grid = GridSpec.identity(Vector(11, 10, 9))
    val fixed = smoothSignal(grid, phase = 0.0)
    val moving = smoothSignal(grid, phase = 0.19)
    val support = values(grid)(_ => 1.0)
    val directionFixed = smoothDirection(grid, 0.07)
    val directionMoving = smoothDirection(grid, -0.11)
    assertDerivativeLadder(
      fixed,
      moving,
      support,
      grid,
      ccConfig(2),
      directionFixed,
      directionMoving,
      required = 1e-5
    )

  test("frozen CC gradient passes a whole-domain partial-support boundary ladder"):
    val grid = GridSpec.identity(Vector(10, 9, 8))
    val fixed = smoothSignal(grid, phase = 0.03)
    val moving = smoothSignal(grid, phase = -0.13)
    val support = values(grid): index =>
      val x = index % grid.shape.x
      val yz = index / grid.shape.x
      val y = yz % grid.shape.y
      val z = yz / grid.shape.y
      if x == 0 || z == grid.shape.z - 1 then 0.0
      else if y <= 1 || (index % 13) == 0 then 0.4
      else 1.0
    assertDerivativeLadder(
      fixed,
      moving,
      support,
      grid,
      ccConfig(3),
      smoothDirection(grid, 0.17),
      smoothDirection(grid, -0.23),
      required = 1e-3
    )

  test("paired half-flow warp-to-CC directional derivative passes at identity"):
    val grid = GridSpec.identity(Vector(11, 10, 9))
    val frame = Frame[Work](SpatialDomainId("cc-derivative-work"), grid)
    val fixed = smoothSignal(grid, phase = 0.02)
    val moving = smoothSignal(grid, phase = -0.16)
    val support = values(grid): index =>
      val x = index % grid.shape.x
      val yz = index / grid.shape.x
      val y = yz % grid.shape.y
      val z = yz / grid.shape.y
      if x <= 2 || x >= grid.shape.x - 3 || y <= 2 || y >= grid.shape.y - 3 ||
          z <= 2 || z >= grid.shape.z - 3
      then 0.0
      else 1.0
    val frozen = NeighborhoodCc
      .prepare(fixed, moving, support, grid, ccConfig(2))
      .fold(error => fail(error.message), identity)
    val evaluation = NeighborhoodCc
      .valueAndGradient(fixed, moving, frozen)
      .fold(error => fail(error.message), identity)
    val fixedSpatial = spatialGradient(fixed, grid)
    val movingSpatial = spatialGradient(moving, grid)
    val direction = velocityDirection(grid)
    var analytic = 0.0
    var component = 0
    while component < 3 do
      val offset = component * grid.nVoxels
      var index = 0
      while index < grid.nVoxels do
        val velocityGradient = 0.5 * (
          evaluation.fixedIntensityGradient(index) * fixedSpatial(offset + index) -
            evaluation.movingIntensityGradient(index) * movingSpatial(offset + index)
        )
        analytic += velocityGradient * direction(offset + index)
        index += 1
      component += 1
    val fixedVolume = NeuroVol.fromLinear[Double](fixed, grid.toNeuroSpace, "fixed")
    val movingVolume = NeuroVol.fromLinear[Double](moving, grid.toNeuroSpace, "moving")
    val epsilons = Vector(1e-2, 3e-3, 1e-3, 3e-4, 1e-4, 3e-5)
    val errors = epsilons.map: epsilon =>
      val plus = pairedWarpLoss(
        fixedVolume,
        movingVolume,
        frame,
        direction,
        epsilon,
        frozen
      )
      val minus = pairedWarpLoss(
        fixedVolume,
        movingVolume,
        frame,
        direction,
        -epsilon,
        frozen
      )
      relativeError((plus - minus) / (2.0 * epsilon), analytic)
    assert(errors.min <= 1e-5, s"paired half-flow derivative failed: errors=$errors analytic=$analytic")
    assert(errors.take(4).min < errors.head, s"paired half-flow ladder has no convergence region: $errors")

  test("CC is symmetric and equivariant to affine intensity gain and offset"):
    val grid = GridSpec.identity(Vector(9, 9, 8))
    val fixed = smoothSignal(grid, phase = 0.05)
    val moving = smoothSignal(grid, phase = -0.21)
    val support = values(grid)(index => if (index % 19) == 0 then 0.5 else 1.0)
    val config = ccConfig(2)
    val original = evaluate(fixed, moving, support, grid, config)
    val swapped = evaluate(moving, fixed, support, grid, config)
    assertEqualsDouble(original.loss, swapped.loss, 2e-12)
    assertArrayClose(original.fixedIntensityGradient, swapped.movingIntensityGradient, 2e-11, 2e-10)
    assertArrayClose(original.movingIntensityGradient, swapped.fixedIntensityGradient, 2e-11, 2e-10)

    val fixedGain = 2.3
    val movingGain = 0.7
    val transformedFixed = affineIntensity(fixed, fixedGain, 11.0)
    val transformedMoving = affineIntensity(moving, movingGain, -4.5)
    val transformed = evaluate(transformedFixed, transformedMoving, support, grid, config)
    assertEqualsDouble(original.loss, transformed.loss, 2e-11)
    assertScaledGradient(original.fixedIntensityGradient, transformed.fixedIntensityGradient, fixedGain, 2e-9)
    assertScaledGradient(original.movingIntensityGradient, transformed.movingIntensityGradient, movingGain, 2e-9)

  test("constant images are finite and inactive"):
    val grid = GridSpec.identity(Vector(8, 7, 6))
    val fixed = values(grid)(_ => 7.0)
    val moving = values(grid)(_ => -3.0)
    val support = values(grid)(_ => 1.0)
    val result = evaluate(fixed, moving, support, grid, ccConfig(2))
    assertEqualsDouble(result.loss, 0.0, 0.0)
    assertEquals(result.diagnostics.activeWindows, 0)
    assertEqualsDouble(result.diagnostics.support.activeFraction, 0.0, 0.0)
    assert(result.diagnostics.variance.fixedGlobalVariance >= 0.0)
    assert(result.diagnostics.variance.movingGlobalVariance >= 0.0)
    assertAllZero(result.fixedIntensityGradient)
    assertAllZero(result.movingIntensityGradient)

  test("support and reliability remain frozen throughout a trial"):
    val grid = GridSpec.identity(Vector(8, 8, 8))
    val fixed = smoothSignal(grid, phase = 0.0)
    val moving = smoothSignal(grid, phase = 0.27)
    val support = values(grid)(index => if (index % 7) == 0 then 0.5 else 1.0)
    val frozen = NeighborhoodCc
      .prepare(fixed, moving, support, grid, ccConfig(2))
      .fold(error => fail(error.message), identity)
    val before = NeighborhoodCc.value(fixed, moving, frozen).fold(error => fail(error.message), identity)
    var index = 0
    while index < support.length do
      support(index) = 0.0
      index += 1
    val after = NeighborhoodCc.value(fixed, moving, frozen).fold(error => fail(error.message), identity)
    assertEqualsDouble(before, after, 0.0)
    assert(frozen.supportDiagnostics.edgeBandActiveFraction > 0.0)
    assert(frozen.supportDiagnostics.minimumFraction < frozen.supportDiagnostics.maximumFraction)

  private def assertDerivativeLadder(
      fixed: Array[Double],
      moving: Array[Double],
      support: Array[Double],
      grid: GridSpec,
      config: NeighborhoodCcConfig,
      fixedDirection: Array[Double],
      movingDirection: Array[Double],
      required: Double
  ): Unit =
    val frozen = NeighborhoodCc
      .prepare(fixed, moving, support, grid, config)
      .fold(error => fail(error.message), identity)
    val workspace = NeighborhoodCcWorkspace(grid)
    val evaluation = NeighborhoodCc
      .valueAndGradientWith(fixed, moving, frozen, workspace, NeighborhoodCcBuffer(grid))
      .fold(error => fail(error.message), identity)
    val analytic =
      dot(evaluation.fixedIntensityGradient, fixedDirection) +
        dot(evaluation.movingIntensityGradient, movingDirection)
    val epsilons = Vector(1e-2, 3e-3, 1e-3, 3e-4, 1e-4, 3e-5, 1e-5, 3e-6, 1e-6)
    val errors = epsilons.map: epsilon =>
      val plusFixed = addScaled(fixed, fixedDirection, epsilon)
      val plusMoving = addScaled(moving, movingDirection, epsilon)
      val minusFixed = addScaled(fixed, fixedDirection, -epsilon)
      val minusMoving = addScaled(moving, movingDirection, -epsilon)
      val plus = NeighborhoodCc
        .valueWith(plusFixed, plusMoving, frozen, workspace)
        .fold(error => fail(error.message), identity)
      val minus = NeighborhoodCc
        .valueWith(minusFixed, minusMoving, frozen, workspace)
        .fold(error => fail(error.message), identity)
      val finiteDifference = (plus - minus) / (2.0 * epsilon)
      relativeError(finiteDifference, analytic)
    val best = errors.min
    assert(best <= required, s"best derivative error $best exceeds $required; ladder=$errors analytic=$analytic")
    assert(
      errors.take(4).min < errors.head,
      s"derivative ladder has no visible convergence region: $errors"
    )

  private def evaluate(
      fixed: Array[Double],
      moving: Array[Double],
      support: Array[Double],
      grid: GridSpec,
      config: NeighborhoodCcConfig
  ): NeighborhoodCcEvaluation =
    val frozen = NeighborhoodCc
      .prepare(fixed, moving, support, grid, config)
      .fold(error => fail(error.message), identity)
    NeighborhoodCc.valueAndGradient(fixed, moving, frozen).fold(error => fail(error.message), identity)

  /** Independent O(N r^3) overlapping-window oracle. */
  private def naiveValue(
      fixed: Array[Double],
      moving: Array[Double],
      frozen: FrozenCcWeights
  ): Double =
    val grid = frozen.grid
    val radius = frozen.config.radius
    val nx = grid.shape.x
    val ny = grid.shape.y
    val nz = grid.shape.z
    val plane = nx * ny
    var loss = 0.0
    var z = 0
    while z < nz do
      val z0 = math.max(0, z - radius.z)
      val z1 = math.min(nz - 1, z + radius.z)
      var y = 0
      while y < ny do
        val y0 = math.max(0, y - radius.y)
        val y1 = math.min(ny - 1, y + radius.y)
        var x = 0
        while x < nx do
          val center = x + nx * y + plane * z
          val omega = frozen.weight(center)
          if omega > 0.0 then
            val x0 = math.max(0, x - radius.x)
            val x1 = math.min(nx - 1, x + radius.x)
            var count = 0.0
            var sumFixed = 0.0
            var sumMoving = 0.0
            var sumFixedFixed = 0.0
            var sumMovingMoving = 0.0
            var sumFixedMoving = 0.0
            var zz = z0
            while zz <= z1 do
              var yy = y0
              while yy <= y1 do
                var xx = x0
                while xx <= x1 do
                  val index = xx + nx * yy + plane * zz
                  val weight = frozen.support(index)
                  val f = fixed(index)
                  val m = moving(index)
                  count += weight
                  sumFixed += weight * f
                  sumMoving += weight * m
                  sumFixedFixed += weight * f * f
                  sumMovingMoving += weight * m * m
                  sumFixedMoving += weight * f * m
                  xx += 1
                yy += 1
              zz += 1
            val covariance = sumFixedMoving - sumFixed * sumMoving / count
            val fixedScatter = math.max(0.0, sumFixedFixed - sumFixed * sumFixed / count)
            val movingScatter = math.max(0.0, sumMovingMoving - sumMoving * sumMoving / count)
            val denominator = fixedScatter * movingScatter + frozen.epsilon(center)
            loss += omega * (1.0 - covariance * covariance / denominator)
          x += 1
        y += 1
      z += 1
    loss

  private def ccConfig(radius: Int): NeighborhoodCcConfig =
    NeighborhoodCcConfig
      .make(
        radius = VoxelWindowRadius(radius, radius, radius),
        minimumSupportFraction = 0.15,
        fullSupportFraction = 0.70,
        minimumVarianceFraction = 1e-6,
        fullVarianceFraction = 1e-4,
        denominatorEpsilonFraction = 1e-7
      )
      .fold(error => fail(error.message), identity)

  private def smoothSignal(grid: GridSpec, phase: Double): Array[Double] =
    values(grid): index =>
      val x = index % grid.shape.x
      val yz = index / grid.shape.x
      val y = yz % grid.shape.y
      val z = yz / grid.shape.y
      4.0 + 0.8 * math.sin(0.31 * x + phase) + 0.5 * math.cos(0.27 * y - 0.3 * phase) +
        0.35 * math.sin(0.21 * z + 0.11 * x - phase)

  private def smoothDirection(grid: GridSpec, phase: Double): Array[Double] =
    values(grid): index =>
      val x = index % grid.shape.x
      val yz = index / grid.shape.x
      val y = yz % grid.shape.y
      val z = yz / grid.shape.y
      0.2 * math.sin(0.17 * x + 0.13 * y + phase) - 0.15 * math.cos(0.19 * z - phase)

  private def velocityDirection(grid: GridSpec): Array[Double] =
    val result = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    var index = 0
    while index < grid.nVoxels do
      val x = index % grid.shape.x
      val yz = index / grid.shape.x
      val y = yz % grid.shape.y
      val z = yz / grid.shape.y
      result(index) = 0.13 * math.sin(0.19 * x + 0.11 * y)
      result(index + grid.nVoxels) = -0.09 * math.cos(0.17 * y + 0.07 * z)
      result(index + 2 * grid.nVoxels) = 0.08 * math.sin(0.13 * z - 0.05 * x)
      index += 1
    result

  private def spatialGradient(source: Array[Double], grid: GridSpec): Array[Double] =
    val valid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    var index = 0
    while index < valid.length do
      valid(index) = true
      index += 1
    val destination = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    val destinationValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    MaskedLocalStats.physicalGradientChannelsInto(
      source,
      valid,
      grid,
      1,
      destination,
      destinationValid,
      MaskedLocalStatsWorkspace(grid)
    )
    destination

  private def pairedWarpLoss(
      fixed: NeuroVol[Double],
      moving: NeuroVol[Double],
      frame: Frame[Work],
      direction: Array[Double],
      scale: Double,
      frozen: FrozenCcWeights
  ): Double =
    val values = PrimitiveBuffers.ofSize[Double](direction.length)
    var index = 0
    while index < values.length do
      values(index) = scale * direction(index)
      index += 1
    val field = DenseVectorField.fromLegacyPlanar(
      frame.grid,
      values,
      DenseVectorFieldKind.Displacement
    )
    val velocity = Velocity.make(frame, field).fold(error => fail(error.message), identity)
    val flow = PairedScalingAndSquaring.expHalfPair(velocity).fold(error => fail(error.message), identity)
    val warpedFixed = HalfFlowKernels.pullScalar(
      fixed,
      flow.pair.forward.sourceCoordinates,
      flow.pair.forward.validity,
      FieldValidity.All,
      0.0
    )
    val warpedMoving = HalfFlowKernels.pullScalar(
      moving,
      flow.pair.backward.sourceCoordinates,
      flow.pair.backward.validity,
      FieldValidity.All,
      0.0
    )
    NeighborhoodCc
      .value(warpedFixed.values.copyLegacyLinear, warpedMoving.values.copyLegacyLinear, frozen)
      .fold(error => fail(error.message), identity)

  private def affineIntensity(source: Array[Double], gain: Double, offset: Double): Array[Double] =
    val result = PrimitiveBuffers.ofSize[Double](source.length)
    var index = 0
    while index < source.length do
      result(index) = gain * source(index) + offset
      index += 1
    result

  private def addScaled(source: Array[Double], direction: Array[Double], scale: Double): Array[Double] =
    val result = PrimitiveBuffers.ofSize[Double](source.length)
    var index = 0
    while index < source.length do
      result(index) = source(index) + scale * direction(index)
      index += 1
    result

  private def values(grid: GridSpec)(f: Int => Double): Array[Double] =
    val result = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var index = 0
    while index < result.length do
      result(index) = f(index)
      index += 1
    result

  private def copyOf(source: Array[Double]): Array[Double] =
    val result = PrimitiveBuffers.ofSize[Double](source.length)
    var index = 0
    while index < source.length do
      result(index) = source(index)
      index += 1
    result

  private def fillBlock(
      grid: GridSpec,
      destination: Array[Double],
      x0: Int,
      x1: Int,
      y0: Int,
      y1: Int,
      z0: Int,
      z1: Int,
      value: Double
  ): Unit =
    var z = z0
    while z <= z1 do
      var y = y0
      while y <= y1 do
        var x = x0
        while x <= x1 do
          destination(x + grid.shape.x * y + grid.shape.x * grid.shape.y * z) = value
          x += 1
        y += 1
      z += 1

  private def pseudoRandom(index: Int, seed: Int): Double =
    val mixed = ((index.toLong + 1L) * 1103515245L + seed.toLong * 12345L) & 0x7fffffffL
    mixed.toDouble / 2147483647.0

  private def dot(left: Array[Double], right: Array[Double]): Double =
    var total = 0.0
    var index = 0
    while index < left.length do
      total += left(index) * right(index)
      index += 1
    total

  private def relativeError(left: Double, right: Double): Double =
    math.abs(left - right) / math.max(1e-12, math.max(math.abs(left), math.abs(right)))

  private def assertArrayClose(
      actual: Array[Double],
      expected: Array[Double],
      absolute: Double,
      relative: Double
  ): Unit =
    var index = 0
    while index < actual.length do
      val tolerance = absolute + relative * math.abs(expected(index))
      assertEqualsDouble(actual(index), expected(index), tolerance)
      index += 1

  private def assertScaledGradient(
      original: Array[Double],
      transformed: Array[Double],
      gain: Double,
      tolerance: Double
  ): Unit =
    var index = 0
    while index < original.length do
      assertEqualsDouble(transformed(index), original(index) / gain, tolerance)
      index += 1

  private def assertAllZero(values: Array[Double]): Unit =
    var index = 0
    while index < values.length do
      assertEqualsDouble(values(index), 0.0, 0.0)
      index += 1
