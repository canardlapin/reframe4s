package reframe4s.halfflow

import gale.linalg.DMat
import reframe4s.halfflow.internal.*

class SeparableVolumeFiltersSuite extends munit.FunSuite:
  test("separable box sums match the independent rectangular oracle"):
    val grid = GridSpec.identity(Vector(7, 6, 5))
    val source = values(grid)(index => 0.3 * index + math.sin(0.17 * index))
    val actual = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val expected = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val workspace = BoxSumWorkspace(grid)
    val radii = Vector(
      VoxelWindowRadius(0, 0, 0),
      VoxelWindowRadius(1, 2, 1),
      VoxelWindowRadius(8, 1, 3)
    )
    radii.foreach: radius =>
      BoxSum3D.sumInto(source, grid, radius, actual, workspace)
      BoxSum3D.referenceInto(source, grid, radius, expected)
      assertArrayClose(actual, expected, 1e-10, 1e-10)
    assertEquals(workspace.ownedScalarBuffers, 2)

  test("truncated box sums are self-adjoint"):
    val grid = GridSpec.identity(Vector(8, 7, 6))
    val left = values(grid)(index => math.sin(0.11 * index) + 0.2 * (index % 7))
    val right = values(grid)(index => math.cos(0.07 * index) - 0.1 * (index % 5))
    val boxLeft = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val boxRight = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val workspace = BoxSumWorkspace(grid)
    val radius = VoxelWindowRadius(2, 1, 3)
    BoxSum3D.sumInto(left, grid, radius, boxLeft, workspace)
    BoxSum3D.sumInto(right, grid, radius, boxRight, workspace)
    assertEqualsDouble(dot(boxLeft, right), dot(left, boxRight), 1e-10)

  test("physical Gaussian reflection preserves constants and anisotropic scale"):
    val affine = DMat.dense(4, 4, (Vector(
        Vector(-1.0, 0.2, 0.0, 12.0),
        Vector(0.0, 2.0, 0.1, -3.0),
        Vector(0.0, 0.0, 4.0, 5.0),
        Vector(0.0, 0.0, 0.0, 1.0)
      )).flatten)
    val grid = GridSpec(Vector(13, 11, 9), affine)
    val constant = values(grid)(_ => 7.25)
    val smooth = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val workspace = GaussianWorkspace(grid)
    Gaussian3D.smoothInto(constant, grid, 2.0, smooth, workspace)
    var index = 0
    while index < smooth.length do
      assertEqualsDouble(smooth(index), 7.25, 1e-12)
      index += 1

    val impulse = values(grid)(_ => 0.0)
    val cx = grid.shape(0) / 2
    val cy = grid.shape(1) / 2
    val cz = grid.shape(2) / 2
    val center = at(grid, cx, cy, cz)
    impulse(center) = 1.0
    Gaussian3D.smoothInto(impulse, grid, 2.0, smooth, workspace)
    assert(smooth(at(grid, cx + 1, cy, cz)) > smooth(at(grid, cx, cy + 1, cz)))
    assert(smooth(at(grid, cx, cy + 1, cz)) > smooth(at(grid, cx, cy, cz + 1)))
    assertEquals(workspace.ownedScalarBuffers, 4)

  test("support-normalized Gaussian propagates a constant without mask pinning"):
    val grid = GridSpec.identity(Vector(15, 13, 11))
    val source = values(grid)(_ => 9.0)
    val support = values(grid)(_ => 0.0)
    var z = 4
    while z <= 6 do
      var y = 5
      while y <= 7 do
        var x = 6
        while x <= 8 do
          support(at(grid, x, y, z)) = 1.0
          x += 1
        y += 1
      z += 1
    val destination = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val weights = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val summary = Gaussian3D.normalizedInto(
      source,
      support,
      grid,
      sigmaMm = 1.5,
      minimumWeight = 1e-8,
      destination,
      weights,
      GaussianWorkspace(grid),
      GaussianBoundary.Zero
    )
    assert(summary.validVoxels > 27)
    var index = 0
    while index < grid.nVoxels do
      if weights(index) >= 1e-8 then assertEqualsDouble(destination(index), 9.0, 1e-12)
      else assertEqualsDouble(destination(index), 0.0, 0.0)
      index += 1

  test("native oblique pyramid sampling preserves a world-linear field and gradient"):
    val affine = DMat.dense(4, 4, (Vector(
        Vector(-1.1, 0.2, 0.0, 20.0),
        Vector(0.1, 1.7, 0.25, -8.0),
        Vector(0.0, 0.15, 2.3, 4.0),
        Vector(0.0, 0.0, 0.0, 1.0)
      )).flatten)
    val sourceGrid = GridSpec(Vector(17, 15, 13), affine)
    val sourceValues = PrimitiveBuffers.ofSize[Double](sourceGrid.nVoxels)
    fillWorldLinear(sourceGrid, sourceValues)
    val source = NeuroVol.fromLinear[Double](sourceValues, sourceGrid.toNeuroSpace, "native-linear")
    val target = HalfFlowKernels.pyramidGrid(sourceGrid, 2)
    val sampled = PrimitiveBuffers.ofSize[Double](target.nVoxels)
    val valid = PrimitiveBuffers.ofSize[Boolean](target.nVoxels)
    HalfFlowKernels.buildPyramidLevelInto(
      source,
      target,
      sigmaMm = 0.0,
      PyramidWorkspace(sourceGrid.nVoxels),
      sampled,
      valid
    )
    var index = 0
    while index < target.nVoxels do
      assert(valid(index))
      val point = voxelPoint(target, index)
      assertEqualsDouble(sampled(index), linear(point), 1e-10)
      index += 1

    val gradients = PrimitiveBuffers.ofSize[Double](target.nVoxels * 3)
    val gradientValid = PrimitiveBuffers.ofSize[Boolean](target.nVoxels)
    MaskedLocalStats.physicalGradientChannelsInto(
      sampled,
      valid,
      target,
      1,
      gradients,
      gradientValid,
      MaskedLocalStatsWorkspace(target)
    )
    val center = at(target, target.shape(0) / 2, target.shape(1) / 2, target.shape(2) / 2)
    assert(gradientValid(center))
    assertEqualsDouble(gradients(center), 0.7, 1e-10)
    assertEqualsDouble(gradients(center + target.nVoxels), -0.4, 1e-10)
    assertEqualsDouble(gradients(center + 2 * target.nVoxels), 0.2, 1e-10)

  private def values(grid: GridSpec)(f: Int => Double): Array[Double] =
    val result = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var index = 0
    while index < result.length do
      result(index) = f(index)
      index += 1
    result

  private def fillWorldLinear(grid: GridSpec, destination: Array[Double]): Unit =
    var index = 0
    while index < grid.nVoxels do
      destination(index) = linear(voxelPoint(grid, index))
      index += 1

  private def voxelPoint(grid: GridSpec, index: Int): Vector[Double] =
    val x = index % grid.shape(0)
    val yz = index / grid.shape(0)
    val y = yz % grid.shape(1)
    val z = yz / grid.shape(1)
    grid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))

  private def linear(point: Vector[Double]): Double =
    1.3 + 0.7 * point(0) - 0.4 * point(1) + 0.2 * point(2)

  private def at(grid: GridSpec, x: Int, y: Int, z: Int): Int =
    x + grid.shape(0) * y + grid.shape(0) * grid.shape(1) * z

  private def dot(left: Array[Double], right: Array[Double]): Double =
    var total = 0.0
    var index = 0
    while index < left.length do
      total += left(index) * right(index)
      index += 1
    total

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
