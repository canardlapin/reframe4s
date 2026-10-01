package reframe4s.halfflow

import reframe4s.halfflow.internal.*

class BasinBridgeProjectorSuite extends munit.FunSuite:
  test("sparse identical translations fill the full domain with the background"):
    val grid = GridSpec.identity(Vector(15, 13, 11))
    val tangent = point(12.0, 0.0, 0.0)
    val correspondences = Vector(
      correspondence(point(2.0, 2.0, 2.0), tangent, 1.0),
      correspondence(point(12.0, 10.0, 8.0), tangent, 1.0),
      correspondence(point(3.0, 10.0, 7.0), tangent, 0.7),
      correspondence(point(11.0, 3.0, 5.0), tangent, 0.9)
    )
    val projection = right(BasinBridgeProjector.project(correspondences, grid))

    assertEqualsDouble(projection.summary.background.x, 12.0, 1e-12)
    assertEqualsDouble(projection.summary.background.y, 0.0, 1e-12)
    assertEqualsDouble(projection.summary.background.z, 0.0, 1e-12)
    var index = 0
    while index < grid.nVoxels do
      assertEqualsDouble(projection.field.linearComponent(index, 0), 12.0, 1e-11)
      assertEqualsDouble(projection.field.linearComponent(index, 1), 0.0, 1e-11)
      assertEqualsDouble(projection.field.linearComponent(index, 2), 0.0, 1e-11)
      index += 1

  test("one strong seed supplies background motion in missing regions"):
    val grid = GridSpec.identity(Vector(9, 9, 9))
    val strong = correspondence(point(4.0, 4.0, 4.0), point(12.0, 0.0, 0.0), 1.0)
    val weak = correspondence(point(4.0, 4.0, 4.0), point(-30.0, 1.0, 0.0), 0.001)
    val projection = right(BasinBridgeProjector.project(Vector(strong, weak), grid))

    assert(projection.summary.background.x > 11.9)
    assert(projection.summary.background.x < 12.1)
    val farCorner = projection.field.linearComponent(grid.nVoxels - 1, 0)
    assertEqualsDouble(farCorner, projection.summary.background.x, 1e-10)

  test("one Cauchy reweight limits an isolated high-confidence outlier"):
    val grid = GridSpec.identity(Vector(11, 11, 11))
    val good = Vector(
      point(2.0, 2.0, 2.0),
      point(8.0, 2.0, 2.0),
      point(2.0, 8.0, 2.0),
      point(8.0, 8.0, 2.0),
      point(2.0, 2.0, 8.0),
      point(8.0, 2.0, 8.0),
      point(2.0, 8.0, 8.0),
      point(8.0, 8.0, 8.0)
    ).map(position => correspondence(position, point(12.0, 0.0, 0.0), 1.0))
    val outlier = correspondence(point(5.0, 5.0, 5.0), point(80.0, 0.0, 0.0), 1.0)
    val config = right(BasinBridgeProjectorConfig.make(cauchyScaleMm = 4.0))
    val projection = right(BasinBridgeProjector.project(good :+ outlier, grid, config))

    assert(projection.summary.background.x > 11.5)
    assert(projection.summary.background.x < 13.5)
    assert(projection.summary.background.x < 20.0)

  test("projection is invariant to correspondence order and common coordinate translation"):
    val baseGrid = GridSpec.identity(Vector(9, 9, 9))
    val translatedGrid = GridSpec(
      Vector(9, 9, 9),
      DMat.fromRows(
        Vector(
          Vector(1.0, 0.0, 0.0, 31.0),
          Vector(0.0, 1.0, 0.0, -12.0),
          Vector(0.0, 0.0, 1.0, 7.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )
      )
    )
    val observations = Vector(
      (point(1.0, 1.0, 1.0), point(4.0, -1.0, 0.5), 1.0),
      (point(4.0, 3.0, 2.0), point(-2.0, 3.0, 1.0), 0.8),
      (point(6.0, 7.0, 5.0), point(0.5, 1.0, -2.0), 0.6)
    )
    val base = observations.map { case (position, tangent, confidence) =>
      correspondence(position, tangent, confidence)
    }
    val shift = point(31.0, -12.0, 7.0)
    val translated = observations.map { case (position, tangent, confidence) =>
      correspondence(position + shift, tangent, confidence)
    }
    val config = right(
      BasinBridgeProjectorConfig.make(
        cauchyScaleMm = 100.0,
        zeroResidualPriorWeight = 0.01,
        denseResidualToleranceMm = 0.0
      )
    )
    val first = right(BasinBridgeProjector.project(base, baseGrid, config))
    val reversed = right(BasinBridgeProjector.project(base.reverse, baseGrid, config))
    val shifted = right(BasinBridgeProjector.project(translated, translatedGrid, config))

    var index = 0
    while index < baseGrid.nVoxels do
      var component = 0
      while component < 3 do
        val expected = first.field.linearComponent(index, component)
        assertEqualsDouble(reversed.field.linearComponent(index, component), expected, 1e-12)
        assertEqualsDouble(shifted.field.linearComponent(index, component), expected, 1e-12)
        component += 1
      index += 1

  test("dense small affine residuals do not acquire reflected-boundary strain"):
    val grid = GridSpec.identity(Vector(9, 9, 9))
    val omega = 0.01
    val center = point(4.0, 4.0, 4.0)
    val correspondences = Vector.tabulate(grid.nVoxels): index =>
      val x = (index % grid.shape.x).toDouble
      val yz = index / grid.shape.x
      val y = (yz % grid.shape.y).toDouble
      val z = (yz / grid.shape.y).toDouble
      val position = point(x, y, z)
      val tangent = point(
        -omega * (y - center.y),
        omega * (x - center.x),
        0.0
      )
      correspondence(position, tangent, 1.0)
    val projection = right(BasinBridgeProjector.project(correspondences, grid))

    var index = 0
    while index < grid.nVoxels do
      val x = (index % grid.shape.x).toDouble
      val yz = index / grid.shape.x
      val y = (yz % grid.shape.y).toDouble
      val expected = point(-omega * (y - center.y), omega * (x - center.x), 0.0)
      assertEqualsDouble(projection.field.linearComponent(index, 0), expected.x, 1e-10)
      assertEqualsDouble(projection.field.linearComponent(index, 1), expected.y, 1e-10)
      assertEqualsDouble(projection.field.linearComponent(index, 2), expected.z, 1e-10)
      index += 1

  test("fast normalized projection agrees with a direct reflected-Gaussian reference"):
    val grid = GridSpec.identity(Vector(5, 5, 5))
    val correspondences = Vector(
      correspondenceAtMidpoint(point(1.0, 1.0, 1.0), point(4.0, 0.0, 0.0), 1.0),
      correspondenceAtMidpoint(point(3.0, 3.0, 3.0), point(0.0, 4.0, 0.0), 1.0)
    )
    val config = right(
      BasinBridgeProjectorConfig.make(
        sigmaMm = 1.0,
        minimumGaussianWeight = 1e-12,
        cauchyScaleMm = 1e9,
        zeroResidualPriorWeight = 0.1,
        denseSupportThreshold = 2.0,
        denseResidualToleranceMm = 0.0
      )
    )
    val projection = right(BasinBridgeProjector.project(correspondences, grid, config))
    val expected = directReference(correspondences, grid, config)

    var index = 0
    while index < grid.nVoxels do
      var component = 0
      while component < 3 do
        assertEqualsDouble(
          projection.field.linearComponent(index, component),
          expected(index + component * grid.nVoxels),
          1e-10
        )
        component += 1
      index += 1

  test("invalid projector controls fail closed"):
    assert(BasinBridgeProjectorConfig.make(sigmaMm = -1.0).isLeft)
    assert(BasinBridgeProjectorConfig.make(cauchyScaleMm = 0.0).isLeft)
    assert(BasinBridgeProjectorConfig.make(zeroResidualPriorWeight = Double.NaN).isLeft)
    assert(BasinBridgeProjector.project(Vector.empty, GridSpec.identity(Vector(3, 3, 3))).isLeft)

  private def correspondence(
      midpoint: BasinBridgePoint,
      tangent: BasinBridgePoint,
      confidence: Double
  ): BasinBridgeCorrespondence =
    correspondenceAtMidpoint(midpoint, tangent, confidence)

  private def correspondenceAtMidpoint(
      midpoint: BasinBridgePoint,
      tangent: BasinBridgePoint,
      confidence: Double
  ): BasinBridgeCorrespondence =
    right(
      BasinBridgeCorrespondence.make(
        midpoint - tangent * 0.5,
        midpoint + tangent * 0.5,
        confidence
      )
    )

  private def point(x: Double, y: Double, z: Double): BasinBridgePoint =
    right(BasinBridgePoint.make(x, y, z))

  private def directReference(
      correspondences: Vector[BasinBridgeCorrespondence],
      grid: GridSpec,
      config: BasinBridgeProjectorConfig
  ): Array[Double] =
    val n = grid.nVoxels
    val support = Array.fill(n)(config.zeroResidualPriorWeight)
    val numeratorX = Array.fill(n)(0.0)
    val numeratorY = Array.fill(n)(0.0)
    val numeratorZ = Array.fill(n)(0.0)
    var backgroundX = 0.0
    var backgroundY = 0.0
    var backgroundZ = 0.0
    var index = 0
    while index < correspondences.length do
      val correspondence = correspondences(index)
      backgroundX += correspondence.tangent.x
      backgroundY += correspondence.tangent.y
      backgroundZ += correspondence.tangent.z
      index += 1
    backgroundX /= correspondences.length.toDouble
    backgroundY /= correspondences.length.toDouble
    backgroundZ /= correspondences.length.toDouble
    index = 0
    while index < correspondences.length do
      val correspondence = correspondences(index)
      val voxel = linearIndex(grid, correspondence.midpoint)
      val residualX = correspondence.tangent.x - backgroundX
      val residualY = correspondence.tangent.y - backgroundY
      val residualZ = correspondence.tangent.z - backgroundZ
      support(voxel) += correspondence.confidence
      numeratorX(voxel) += correspondence.confidence * residualX
      numeratorY(voxel) += correspondence.confidence * residualY
      numeratorZ(voxel) += correspondence.confidence * residualZ
      index += 1
    index = 0
    while index < n do
      numeratorX(index) /= support(index)
      numeratorY(index) /= support(index)
      numeratorZ(index) /= support(index)
      index += 1

    val result = Array.ofDim[Double](n * 3)
    val radius = math.ceil(3.0 * config.sigmaMm).toInt
    val kernel = gaussianKernel(config.sigmaMm, radius)
    var z = 0
    while z < grid.shape.z do
      var y = 0
      while y < grid.shape.y do
        var x = 0
        while x < grid.shape.x do
          var sumX = 0.0
          var sumY = 0.0
          var sumZ = 0.0
          var denominator = 0.0
          var dz = -radius
          while dz <= radius do
            var dy = -radius
            while dy <= radius do
              var dx = -radius
              while dx <= radius do
                val sourceX = reflect(x + dx, grid.shape.x)
                val sourceY = reflect(y + dy, grid.shape.y)
                val sourceZ = reflect(z + dz, grid.shape.z)
                val source = sourceX + grid.shape.x * sourceY + grid.shape.x * grid.shape.y * sourceZ
                val weight = kernel(dx + radius) * kernel(dy + radius) * kernel(dz + radius)
                sumX += weight * support(source) * numeratorX(source)
                sumY += weight * support(source) * numeratorY(source)
                sumZ += weight * support(source) * numeratorZ(source)
                denominator += weight * support(source)
                dx += 1
              dy += 1
            dz += 1
          val target = x + grid.shape.x * y + grid.shape.x * grid.shape.y * z
          result(target) = backgroundX + sumX / denominator
          result(target + n) = backgroundY + sumY / denominator
          result(target + 2 * n) = backgroundZ + sumZ / denominator
          x += 1
        y += 1
      z += 1
    result

  private def gaussianKernel(sigma: Double, radius: Int): Array[Double] =
    val values = Array.ofDim[Double](2 * radius + 1)
    val denominator = 2.0 * sigma * sigma
    var sum = 0.0
    var index = -radius
    while index <= radius do
      val value = math.exp(-(index.toDouble * index.toDouble) / denominator)
      values(index + radius) = value
      sum += value
      index += 1
    index = 0
    while index < values.length do
      values(index) /= sum
      index += 1
    values

  private def reflect(index: Int, size: Int): Int =
    if size == 1 then 0
    else
      var reflected = index
      while reflected < 0 || reflected >= size do
        if reflected < 0 then reflected = -reflected - 1
        else reflected = 2 * size - reflected - 1
      reflected

  private def linearIndex(grid: GridSpec, point: BasinBridgePoint): Int =
    point.x.toInt + grid.shape.x * point.y.toInt + grid.shape.x * grid.shape.y * point.z.toInt

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(error.toString)
