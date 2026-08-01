package reframe4s.halfflow

import reframe4s.halfflow.internal.*

import ravel.NDArray as RavelArray

class HalfFlowKernelsSuite extends munit.FunSuite:

  private val fixed = SpatialDomainId("fixed")
  private val moving = SpatialDomainId("moving")

  private def index(grid: GridSpec, x: Int, y: Int, z: Int): Int =
    x + y * grid.shape.x + z * grid.shape.x * grid.shape.y

  private def assertClose(actual: Double, expected: Double, tolerance: Double = 1e-10): Unit =
    assertEqualsDouble(actual, expected, tolerance)

  private def affineGrid(dims: Vector[Int]): GridSpec =
    GridSpec(
      dims,
      DMat.fromRows(
        Vector(
          Vector(1.5, 0.2, 0.0, 10.0),
          Vector(0.0, 2.0, 0.1, -4.0),
          Vector(0.0, 0.0, 2.5, 3.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )
      )
    )

  private def field(
      grid: GridSpec
  )(
      transform: WorldPoint => WorldPoint
  ): DenseVectorField =
    val values =
      RavelArray.tabulate[Double](
        grid.shape.x,
        grid.shape.y,
        grid.shape.z,
        3
      ) { (x, y, z, component) =>
          val world = grid.voxelToWorld(SpatialPoint(x.toDouble, y.toDouble, z.toDouble))
          val mapped = transform(WorldPoint.fromSpatialPoint(world))
          component match
            case 0 => mapped.x
            case 1 => mapped.y
            case _ => mapped.z
      }
    DenseVectorField(
      grid,
      values,
      DenseVectorFieldKind.SourceCoordinates
    )

  private def volume(
      grid: GridSpec,
      label: String = "source"
  )(
      value: WorldPoint => Double
  ): NeuroVol[Double] =
    val values = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var z = 0
    while z < grid.shape.z do
      var y = 0
      while y < grid.shape.y do
        var x = 0
        while x < grid.shape.x do
          val i = index(grid, x, y, z)
          val world = grid.voxelToWorld(SpatialPoint(x.toDouble, y.toDouble, z.toDouble))
          values(i) = value(WorldPoint.fromSpatialPoint(world))
          x += 1
        y += 1
      z += 1
    NeuroVol.fromLinear(values, grid.toNeuroSpace, label)

  private def allTrue(values: Array[Boolean]): Boolean =
    var result = true
    var i = 0
    while i < values.length do
      result = result && values(i)
      i += 1
    result

  test("identity and scalar pull preserve a physical world-linear volume") {
    val grid = affineGrid(Vector(4, 5, 3))
    val source = volume(grid)(p => 2.0 * p.x - 0.5 * p.y + 0.25 * p.z + 7.0)
    val identity = HalfFlowKernels.identity(grid)
    val pulled = HalfFlowKernels.pullScalar(
      source,
      identity.field,
      FieldValidity.copyMask(identity.valid)
    )

    assert(allTrue(identity.valid))
    var i = 0
    while i < grid.nVoxels do
      assertClose(pulled.values.linear(i), source.linear(i), 1e-9)
      assert(pulled.valid.linear(i))
      i += 1
  }

  test("channel pull shares interpolation support and preserves channel semantics") {
    val grid = GridSpec.identity(Vector(4, 3, 2))
    val channels = 2
    val source =
      RavelArray.tabulate[Double](
        grid.shape.x,
        grid.shape.y,
        grid.shape.z,
        channels
      ) { (x, y, z, channel) =>
        val linear = index(grid, x, y, z)
        if channel == 0 then linear.toDouble
        else 100.0 - 2.0 * linear.toDouble
      }
    val identity = HalfFlowKernels.identity(grid)
    val pulled = HalfFlowKernels.pullChannels(
      source,
      grid,
      identity.field
    )

    assertEquals(pulled.channels, 2)
    assert(allTrue(pulled.valid))
    var i = 0
    while i < grid.nVoxels do
      val x = i % grid.shape.x
      val yz = i / grid.shape.x
      val y = yz % grid.shape.y
      val z = yz / grid.shape.y
      assertClose(pulled.values(x, y, z, 0), i.toDouble)
      assertClose(
        pulled.values(x, y, z, 1),
        100.0 - 2.0 * i.toDouble
      )
      i += 1
  }

  test("pull validity rejects masked, non-finite, and outside interpolation support") {
    val grid = GridSpec.identity(Vector(3, 3, 3))
    val source = volume(grid)(p => p.x + p.y + p.z)
    val map =
      field(grid) { point =>
        if point == WorldPoint(0.0, 0.0, 0.0) then
          WorldPoint(0.5, 0.5, 0.5)
        else if point == WorldPoint(1.0, 0.0, 0.0) then
          WorldPoint(-0.25, 0.0, 0.0)
        else point
      }
    val sourceMask = PrimitiveBuffers.fillConst[Boolean](grid.nVoxels, true)
    sourceMask(index(grid, 1, 1, 1)) = false
    val mapMask = PrimitiveBuffers.fillConst[Boolean](grid.nVoxels, true)
    mapMask(2) = false
    val pulled = HalfFlowKernels.pullScalar(
      source,
      map,
      FieldValidity.copyMask(mapMask),
      FieldValidity.copyMask(sourceMask),
      outside = -99.0
    )

    assert(!pulled.valid.linear(0), clue = "masked interpolation corner must invalidate the sample")
    assert(!pulled.valid.linear(1), clue = "outside support must invalidate the sample")
    assert(!pulled.valid.linear(2), clue = "map validity must propagate")
    assertClose(pulled.values.linear(0), -99.0)
    assertClose(pulled.values.linear(1), -99.0)
    assertClose(pulled.values.linear(2), -99.0)
  }

  test("affine-fused scalar pull matches an explicitly transformed coordinate field") {
    val grid = affineGrid(Vector(5, 5, 5))
    val source = volume(grid)(p => 1.5 * p.x - 0.75 * p.y + 0.4 * p.z + 3.0)
    val residual = field(grid)(p => WorldPoint(p.x + 0.15, p.y - 0.10, p.z + 0.05))
    val transform = DMat.fromRows(
      Vector(
        Vector(1.0, 0.02, 0.0, 0.20),
        Vector(-0.01, 1.0, 0.0, -0.15),
        Vector(0.0, 0.0, 1.0, 0.10),
        Vector(0.0, 0.0, 0.0, 1.0)
      )
    )
    val explicit = field(grid): point =>
      val residualPoint = WorldPoint(point.x + 0.15, point.y - 0.10, point.z + 0.05)
      val mapped = Affine.applyAffine(transform, residualPoint.toVector)
      WorldPoint(mapped(0), mapped(1), mapped(2))
    val expected = HalfFlowKernels.pullScalar(source, explicit, outside = -1.0)
    val actual = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val actualValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)

    HalfFlowKernels.pullScalarAffineInto(
      source,
      residual,
      transform,
      actual,
      actualValid,
      DenseFieldSampler(grid),
      FieldValidity.All,
      FieldValidity.All,
      outside = -1.0
    )

    var i = 0
    while i < grid.nVoxels do
      assertEquals(actualValid(i), expected.valid.linear(i))
      assertClose(actual(i), expected.values.linear(i), 1e-10)
      i += 1
  }

  test("nearest scalar pull preserves labels on an oblique grid and propagates validity") {
    val grid = affineGrid(Vector(4, 4, 3))
    val geometry = grid.affine3D.fold(error => fail(error.message), identity)
    val source = volume(grid)(p =>
      val voxel = geometry.worldToVoxel(p)
      math.round(voxel.x).toDouble + 10.0 * math.round(voxel.y).toDouble +
        100.0 * math.round(voxel.z).toDouble
    )
    val first = index(grid, 0, 0, 0)
    val second = index(grid, 1, 0, 0)
    val third = index(grid, 2, 0, 0)
    val firstSource = grid.voxelToWorld(SpatialPoint(2.49, 1.49, 1.49))
    val secondSource = grid.voxelToWorld(SpatialPoint(3.0, 2.0, 1.0))
    val firstTarget =
      WorldPoint.fromSpatialPoint(
        grid.voxelToWorld(SpatialPoint(0.0, 0.0, 0.0))
      )
    val secondTarget =
      WorldPoint.fromSpatialPoint(
        grid.voxelToWorld(SpatialPoint(1.0, 0.0, 0.0))
      )
    val map =
      field(grid) { point =>
        if point == firstTarget then
          WorldPoint.fromSpatialPoint(firstSource)
        else if point == secondTarget then
          WorldPoint.fromSpatialPoint(secondSource)
        else point
      }
    val sourceValidity = PrimitiveBuffers.fillConst[Boolean](grid.nVoxels, true)
    sourceValidity(index(grid, 3, 2, 1)) = false
    val mapValidity = PrimitiveBuffers.fillConst[Boolean](grid.nVoxels, true)
    mapValidity(third) = false

    val pulled = HalfFlowKernels.pullScalarNearest(
      source,
      map,
      FieldValidity.copyMask(mapValidity),
      FieldValidity.copyMask(sourceValidity),
      outside = -1.0
    )

    assert(pulled.valid.linear(first))
    assertClose(pulled.values.linear(first), 112.0)
    assert(!pulled.valid.linear(second), clue = "source validity must reject the nearest label")
    assertClose(pulled.values.linear(second), -1.0)
    assert(!pulled.valid.linear(third), clue = "map validity must propagate")
    assertClose(pulled.values.linear(third), -1.0)
  }

  test("dense pull composition matches analytic affine composition and existing morphism sampling") {
    val grid = GridSpec.identity(Vector(7, 6, 5))
    val left = field(grid)(p => WorldPoint(p.x + 0.25, p.y + 0.5, p.z))
    val right = field(grid)(p => WorldPoint(1.2 * p.x + 0.3 * p.y + 0.1, 0.8 * p.y, 1.1 * p.z - 0.2))
    val composed = HalfFlowKernels.composePull(left, right)
    val rightMorphism =
      DenseFieldMorphism.coordinates(fixed, moving, grid, right.values)
        .fold(err => fail(err.message), value => value)

    var z = 1
    while z < grid.shape.z - 1 do
      var y = 1
      while y < grid.shape.y - 1 do
        var x = 1
        while x < grid.shape.x - 2 do
          val i = index(grid, x, y, z)
          val query = WorldPoint(x.toDouble + 0.25, y.toDouble + 0.5, z.toDouble)
          val expected = rightMorphism.transform(query)
          assert(composed.valid(i))
          assertClose(composed.field.linearComponent(i, 0), expected.x, 1e-10)
          assertClose(composed.field.linearComponent(i, 1), expected.y, 1e-10)
          assertClose(composed.field.linearComponent(i, 2), expected.z, 1e-10)
          x += 1
        y += 1
      z += 1
  }

  test("composition invalidity does not clamp and writes an identity fallback") {
    val grid = GridSpec.identity(Vector(3, 3, 3))
    val left = field(grid)(p => WorldPoint(p.x - 0.25, p.y, p.z))
    val right = field(grid)(p => WorldPoint(p.x + 10.0, p.y, p.z))
    val composed = HalfFlowKernels.composePull(left, right)
    val i = index(grid, 0, 1, 1)

    assert(!composed.valid(i))
    assertClose(composed.field.linearComponent(i, 0), 0.0)
    assertClose(composed.field.linearComponent(i, 1), 1.0)
    assertClose(composed.field.linearComponent(i, 2), 1.0)
  }

  test("identity extension preserves an out-of-grid self-map query") {
    val grid = GridSpec.identity(Vector(3, 3, 3))
    val left = field(grid)(p => WorldPoint(p.x - 0.25, p.y, p.z))
    val identity = HalfFlowKernels.identity(grid)
    val composed = HalfFlowKernels.composePull(
      left,
      identity.field,
      rightValidity = FieldValidity.copyMask(identity.valid),
      outside = CoordinateMapOutside.Identity
    )
    val i = index(grid, 0, 1, 1)

    assert(composed.valid(i))
    assertClose(composed.field.linearComponent(i, 0), -0.25)
    assertClose(composed.field.linearComponent(i, 1), 1.0)
    assertClose(composed.field.linearComponent(i, 2), 1.0)
  }

  test("linear regridding preserves an affine physical-coordinate field") {
    val coarseAffine = DMat.fromRows(
      Vector(
        Vector(2.0, 0.0, 0.0, 0.0),
        Vector(0.0, 2.0, 0.0, 0.0),
        Vector(0.0, 0.0, 2.0, 0.0),
        Vector(0.0, 0.0, 0.0, 1.0)
      )
    )
    val coarse = GridSpec(Vector(4, 4, 4), coarseAffine)
    val fine = GridSpec.identity(Vector(7, 7, 7))
    val source = field(coarse)(p => WorldPoint(1.1 * p.x + 0.2 * p.y, p.y - 0.3 * p.z, 0.9 * p.z + 2.0))
    val regridded = HalfFlowKernels.regridPull(source, fine)
    val reference =
      DenseFieldInterpolationPlan.make(coarse, fine.worldCoords, Resample.Method.Linear)
        .fold(err => fail(err.message), value => value)
        .sample(source.values, DenseFieldOutside.QueryPoint)
        .fold(err => fail(err.message), value => value)

    assert(allTrue(regridded.valid))
    var i = 0
    while i < fine.nVoxels do
      val point = fine.voxelToWorld(SpatialPoint(
        (i % fine.shape.x).toDouble,
        ((i / fine.shape.x) % fine.shape.y).toDouble,
        (i / (fine.shape.x * fine.shape.y)).toDouble
      ))
      val expected = WorldPoint(1.1 * point.x + 0.2 * point.y, point.y - 0.3 * point.z, 0.9 * point.z + 2.0)
      assertClose(regridded.field.linearComponent(i, 0), expected.x, 1e-10)
      assertClose(regridded.field.linearComponent(i, 1), expected.y, 1e-10)
      assertClose(regridded.field.linearComponent(i, 2), expected.z, 1e-10)
      assertClose(regridded.field.linearComponent(i, 0), reference(i)(0), 1e-10)
      i += 1
  }

  test("identity-extended self-map regridding remains valid beyond sampled support") {
    val sourceGrid = GridSpec.identity(Vector(3, 3, 3))
    val targetGrid = GridSpec.identity(Vector(5, 3, 3))
    val source = HalfFlowKernels.identity(sourceGrid)
    val strict = HalfFlowKernels.regridPull(source.field, targetGrid)
    val extended = HalfFlowKernels.regridPull(
      source.field,
      targetGrid,
      outside = CoordinateMapOutside.Identity
    )
    val outsideIndex = index(targetGrid, 4, 1, 1)

    assert(!strict.valid(outsideIndex))
    assert(extended.valid(outsideIndex))
    assertClose(extended.field.linearComponent(outsideIndex, 0), 4.0)
    assertClose(extended.field.linearComponent(outsideIndex, 1), 1.0)
    assertClose(extended.field.linearComponent(outsideIndex, 2), 1.0)
  }

  test("Jacobian determinants are physical-coordinate correct on an oblique grid") {
    val grid = affineGrid(Vector(6, 5, 4))
    val map = field(grid) { p =>
      WorldPoint(
        1.2 * p.x + 0.3 * p.y,
        0.8 * p.y + 0.2 * p.z,
        1.5 * p.z
      )
    }
    val determinants = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val valid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    val summary = HalfFlowKernels.jacobianDeterminantsInto(map, determinants, valid)
    val expected = 1.2 * 0.8 * 1.5

    assertEquals(summary.evaluated, (grid.shape.x - 2) * (grid.shape.y - 2) * (grid.shape.z - 2))
    assertEquals(summary.nonPositive, 0)
    assertClose(summary.minimum.getOrElse(fail("missing minimum")), expected, 1e-10)
    assertClose(summary.maximum.getOrElse(fail("missing maximum")), expected, 1e-10)
    assertClose(summary.mean.getOrElse(fail("missing mean")), expected, 1e-10)
    assert(!valid(index(grid, 0, 2, 2)), clue = "boundary voxels use no one-sided derivative")
  }

  test("Jacobian reduction detects a folded field and validity erosion") {
    val grid = GridSpec.identity(Vector(5, 5, 5))
    val folded = field(grid)(p => WorldPoint(-p.x, p.y, p.z))
    val fieldMask = PrimitiveBuffers.fillConst[Boolean](grid.nVoxels, true)
    fieldMask(index(grid, 2, 2, 2)) = false
    val determinants = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val valid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    val summary = HalfFlowKernels.jacobianDeterminantsInto(
      folded,
      determinants,
      valid,
      FieldValidity.copyMask(fieldMask)
    )

    assert(summary.evaluated > 0)
    assertEquals(summary.nonPositive, summary.evaluated)
    assertClose(summary.minimum.getOrElse(fail("missing minimum")), -1.0)
    assert(!valid(index(grid, 1, 2, 2)), clue = "central stencil touching an invalid voxel must be invalid")
    assert(!valid(index(grid, 2, 2, 2)))
  }

  test("paired inverse error is zero for opposite sub-voxel translations") {
    val grid = GridSpec.identity(Vector(8, 7, 6))
    val forward = field(grid)(p => WorldPoint(p.x + 0.25, p.y + 0.25, p.z))
    val backward = field(grid)(p => WorldPoint(p.x - 0.25, p.y - 0.25, p.z))
    val summary = HalfFlowKernels.inversePairError(forward, backward)

    assert(summary.forwardThenBackward.evaluated > 0)
    assert(summary.backwardThenForward.evaluated > 0)
    assertClose(summary.forwardThenBackward.rmsMm.getOrElse(fail("missing RMS")), 0.0, 1e-12)
    assertClose(summary.forwardThenBackward.maximumMm.getOrElse(fail("missing max")), 0.0, 1e-12)
    assertClose(summary.backwardThenForward.rmsVox.getOrElse(fail("missing voxel RMS")), 0.0, 1e-12)
  }

  test("inverse error reports a known physical and voxel-space bias") {
    val grid = GridSpec(
      Vector(6, 6, 6),
      DMat.fromRows(
        Vector(
          Vector(2.0, 0.0, 0.0, 0.0),
          Vector(0.0, 2.0, 0.0, 0.0),
          Vector(0.0, 0.0, 2.0, 0.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )
      )
    )
    val first = field(grid)(p => p)
    val second = field(grid)(p => WorldPoint(p.x + 0.2, p.y, p.z))
    val summary = HalfFlowKernels.inverseErrorReduce(first, second)

    assertClose(summary.rmsMm.getOrElse(fail("missing RMS")), 0.2, 1e-10)
    assertClose(summary.maximumMm.getOrElse(fail("missing max")), 0.2, 1e-10)
    assertClose(summary.rmsVox.getOrElse(fail("missing voxel RMS")), 0.1, 1e-10)
  }

  test("anti-aliased pyramid suppresses checkerboard energy") {
    val grid = GridSpec.identity(Vector(17, 17, 17))
    val sourceValues = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var z = 0
    while z < grid.shape.z do
      var y = 0
      while y < grid.shape.y do
        var x = 0
        while x < grid.shape.x do
          sourceValues(index(grid, x, y, z)) = if ((x + y + z) & 1) == 0 then 0.0 else 1.0
          x += 1
        y += 1
      z += 1
    val source = NeuroVol.fromLinear[Double](sourceValues, grid.toNeuroSpace, "checkerboard")
    val level = HalfFlowKernels.buildPyramidLevel(source, shrink = 2, sigmaMm = 1.0)

    var maxError = 0.0
    z = 2
    while z < level.values.space.spatialDims(2) - 2 do
      var y = 2
      while y < level.values.space.spatialDims(1) - 2 do
        var x = 2
        while x < level.values.space.spatialDims(0) - 2 do
          maxError = math.max(maxError, math.abs(level.values(x, y, z) - 0.5))
          assert(level.valid(x, y, z))
          x += 1
        y += 1
      z += 1
    assert(maxError < 1e-3, clue = s"checkerboard alias error $maxError")
  }

  test("mask-normalized pyramid preserves a constant without boundary dilution") {
    val grid = GridSpec.identity(Vector(13, 13, 13))
    val source = volume(grid, "constant")(_ => 7.0)
    val mask = PrimitiveBuffers.fillConst[Boolean](grid.nVoxels, false)
    var z = 4
    while z <= 8 do
      var y = 4
      while y <= 8 do
        var x = 4
        while x <= 8 do
          mask(index(grid, x, y, z)) = true
          x += 1
        y += 1
      z += 1
    val level = HalfFlowKernels.buildPyramidLevel(
      source,
      shrink = 2,
      sigmaMm = 1.0,
      sourceValidity = FieldValidity.copyMask(mask),
      minimumWeight = 1e-4
    )

    var validCount = 0
    var i = 0
    while i < level.values.copyLegacyLinear.length do
      if level.valid.linear(i) then
        assertClose(level.values.linear(i), 7.0, 1e-10)
        validCount += 1
      i += 1
    assert(validCount > 0)
    assert(validCount < level.values.copyLegacyLinear.length, clue = "far-outside voxels must remain invalid")
  }

  test("pyramid grids preserve origin and scale every physical basis column") {
    val source = affineGrid(Vector(10, 9, 8))
    val target = HalfFlowKernels.pyramidGrid(source, shrink = 3)

    assertEquals(target.dims, Vector(4, 3, 3))
    var row = 0
    while row < 3 do
      assertClose(target.affine(row, 3), source.affine(row, 3))
      var column = 0
      while column < 3 do
        assertClose(target.affine(row, column), 3.0 * source.affine(row, column))
        column += 1
      row += 1
  }

  test("prepared samplers preserve pull, Jacobian, inverse, and pyramid results") {
    val grid = affineGrid(Vector(5, 5, 5))
    val identity = HalfFlowKernels.identity(grid)
    val source = volume(grid)(p => p.x - 2.0 * p.y + 0.5 * p.z)
    val sampler = DenseFieldSampler(grid)
    val pulled = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val pullValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    HalfFlowKernels.pullScalarInto(
      source,
      identity.field,
      pulled,
      pullValid,
      sampler,
      FieldValidity.copyMask(identity.valid),
      FieldValidity.All,
      0.0
    )
    var i = 0
    while i < grid.nVoxels do
      assert(pullValid(i))
      assertClose(pulled(i), source.linear(i), 1e-9)
      i += 1

    val determinants = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val determinantValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    val jacobian = HalfFlowKernels.jacobianDeterminantsInto(
      identity.field,
      determinants,
      determinantValid,
      sampler,
      FieldValidity.copyMask(identity.valid)
    )
    assertClose(jacobian.minimum.getOrElse(fail("missing minimum")), 1.0, 1e-10)

    val inverse = HalfFlowKernels.inverseErrorReduce(
      identity.field,
      identity.field,
      sampler,
      sampler,
      FieldValidity.copyMask(identity.valid),
      FieldValidity.copyMask(identity.valid)
    )
    assertClose(inverse.maximumMm.getOrElse(fail("missing max")), 0.0, 1e-10)

    val target = HalfFlowKernels.pyramidGrid(grid, shrink = 2)
    val pyramid = PrimitiveBuffers.ofSize[Double](target.nVoxels)
    val pyramidValid = PrimitiveBuffers.ofSize[Boolean](target.nVoxels)
    HalfFlowKernels.buildPyramidLevelInto(
      source,
      target,
      sigmaMm = 0.0,
      PyramidWorkspace(grid.nVoxels),
      pyramid,
      pyramidValid,
      sampler,
      FieldValidity.All,
      minimumWeight = 1e-8
    )
    assert(allTrue(pyramidValid))
  }

  test("reusable reductions match immutable summaries and overwrite empty support") {
    val grid = affineGrid(Vector(5, 5, 5))
    val identity = HalfFlowKernels.identity(grid)
    val sampler = DenseFieldSampler(grid)
    val determinants = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val determinantValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    val jacobianReduction = JacobianReduction()
    HalfFlowKernels.jacobianDeterminantsReduceInto(
      identity.field,
      determinants,
      determinantValid,
      sampler,
      FieldValidity.All,
      jacobianReduction
    )
    val jacobian = jacobianReduction.snapshot
    assertEquals(jacobianReduction.evaluated, 27)
    assertClose(jacobianReduction.minimumOrNaN, 1.0)
    assertClose(jacobian.mean.getOrElse(fail("missing Jacobian mean")), 1.0)

    val firstInverse = InverseErrorReduction()
    val secondInverse = InverseErrorReduction()
    HalfFlowKernels.inversePairErrorInto(
      identity.field,
      identity.field,
      sampler,
      sampler,
      FieldValidity.All,
      FieldValidity.All,
      firstInverse,
      secondInverse
    )
    assertEquals(firstInverse.evaluated, grid.nVoxels)
    assertClose(firstInverse.maximumMmOrNaN, 0.0)
    assertClose(secondInverse.snapshot.rmsVox.getOrElse(fail("missing inverse RMS")), 0.0)

    val none = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    var i = 0
    while i < none.length do
      none(i) = false
      i += 1
    HalfFlowKernels.jacobianDeterminantsReduceInto(
      identity.field,
      determinants,
      determinantValid,
      sampler,
      FieldValidity.copyMask(none),
      jacobianReduction
    )
    assertEquals(jacobianReduction.evaluated, 0)
    assert(jacobianReduction.meanOrNaN.isNaN)
    assertEquals(jacobianReduction.snapshot.mean, None)
  }

  test("prepared samplers reject a mismatched field grid") {
    val small = GridSpec.identity(Vector(2, 2, 2))
    val large = GridSpec.identity(Vector(3, 3, 3))
    val smallIdentity = HalfFlowKernels.identity(small)
    val largeIdentity = HalfFlowKernels.identity(large)
    intercept[IllegalArgumentException] {
      HalfFlowKernels.composePullInto(
        smallIdentity.field,
        smallIdentity.field,
        PrimitiveBuffers.ofSize[Double](small.nVoxels * 3),
        PrimitiveBuffers.ofSize[Boolean](small.nVoxels),
        DenseFieldSampler(large),
        FieldValidity.All,
        FieldValidity.All
      )
    }
    intercept[IllegalArgumentException] {
      HalfFlowKernels.regridPullInto(
        largeIdentity.field,
        small,
        PrimitiveBuffers.ofSize[Double](small.nVoxels * 3),
        PrimitiveBuffers.ofSize[Boolean](small.nVoxels),
        DenseFieldSampler(small),
        FieldValidity.All
      )
    }
  }

  test("destination-writing kernels validate buffer contracts") {
    val grid = GridSpec.identity(Vector(2, 2, 2))
    val identity = HalfFlowKernels.identity(grid)
    intercept[IllegalArgumentException] {
      HalfFlowKernels.composePullInto(
        identity.field,
        identity.field,
        PrimitiveBuffers.ofSize[Double](3),
        PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
      )
    }
    intercept[IllegalArgumentException] {
      HalfFlowKernels.pullScalarInto(
        volume(grid)(_ => 1.0),
        identity.field,
        PrimitiveBuffers.ofSize[Double](grid.nVoxels),
        PrimitiveBuffers.ofSize[Boolean](grid.nVoxels),
        sourceValidity = FieldValidity.copyMask(PrimitiveBuffers.ofSize[Boolean](1))
      )
    }
  }
