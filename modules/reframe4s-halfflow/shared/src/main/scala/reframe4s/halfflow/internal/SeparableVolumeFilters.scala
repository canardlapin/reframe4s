package reframe4s.halfflow.internal

import gale.linalg.DMat

enum BoxBoundary:
  /** Sum only samples inside the finite lattice. */
  case Truncate

final class BoxSumWorkspace private (
    val grid: GridSpec,
    private[internal] val first: Array[Double],
    private[internal] val second: Array[Double]
):
  val ownedScalarBuffers: Int = 2

object BoxSumWorkspace:
  def apply(grid: GridSpec): BoxSumWorkspace =
    new BoxSumWorkspace(
      grid,
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    )

/** Allocation-controlled rectangular sums on a finite 3D lattice. */
object BoxSum3D:
  def sumInto(
      source: Array[Double],
      grid: GridSpec,
      radius: VoxelWindowRadius,
      destination: Array[Double],
      workspace: BoxSumWorkspace,
      boundary: BoxBoundary = BoxBoundary.Truncate
  ): Unit =
    require(workspace.grid == grid, "box-sum workspace/grid mismatch")
    require(source.length >= grid.nVoxels, "box-sum source is too small")
    require(destination.length >= grid.nVoxels, "box-sum destination is too small")
    boundary match
      case BoxBoundary.Truncate =>
        sumX(source, workspace.first, grid.shape, radius.x)
        sumY(workspace.first, workspace.second, grid.shape, radius.y)
        sumZ(workspace.second, destination, grid.shape, radius.z)

  /** Independent O(N r^3) oracle for tests and tiny diagnostic volumes. */
  private[halfflow] def referenceInto(
      source: Array[Double],
      grid: GridSpec,
      radius: VoxelWindowRadius,
      destination: Array[Double],
      boundary: BoxBoundary = BoxBoundary.Truncate
  ): Unit =
    require(source.length >= grid.nVoxels, "reference box-sum source is too small")
    require(destination.length >= grid.nVoxels, "reference box-sum destination is too small")
    boundary match
      case BoxBoundary.Truncate =>
        val nx = grid.shape(0)
        val ny = grid.shape(1)
        val nz = grid.shape(2)
        val plane = nx * ny
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
              val x0 = math.max(0, x - radius.x)
              val x1 = math.min(nx - 1, x + radius.x)
              var sum = 0.0
              var zz = z0
              while zz <= z1 do
                var yy = y0
                while yy <= y1 do
                  var xx = x0
                  while xx <= x1 do
                    sum += source(xx + nx * yy + plane * zz)
                    xx += 1
                  yy += 1
                zz += 1
              destination(x + nx * y + plane * z) = sum
              x += 1
            y += 1
          z += 1

  private def sumX(
      source: Array[Double],
      destination: Array[Double],
      dims: Vector[Int],
      radius: Int
  ): Unit =
    val nx = dims(0)
    val ny = dims(1)
    val nz = dims(2)
    val plane = nx * ny
    var z = 0
    while z < nz do
      var y = 0
      while y < ny do
        val base = nx * y + plane * z
        var sum = 0.0
        var add = 0
        val initialEnd = math.min(nx - 1, radius)
        while add <= initialEnd do
          sum += source(base + add)
          add += 1
        var x = 0
        while x < nx do
          destination(base + x) = sum
          val remove = x - radius
          if remove >= 0 then sum -= source(base + remove)
          add = x + radius + 1
          if add < nx then sum += source(base + add)
          x += 1
        y += 1
      z += 1

  private def sumY(
      source: Array[Double],
      destination: Array[Double],
      dims: Vector[Int],
      radius: Int
  ): Unit =
    val nx = dims(0)
    val ny = dims(1)
    val nz = dims(2)
    val plane = nx * ny
    var z = 0
    while z < nz do
      var x = 0
      while x < nx do
        val base = x + plane * z
        var sum = 0.0
        var add = 0
        val initialEnd = math.min(ny - 1, radius)
        while add <= initialEnd do
          sum += source(base + nx * add)
          add += 1
        var y = 0
        while y < ny do
          destination(base + nx * y) = sum
          val remove = y - radius
          if remove >= 0 then sum -= source(base + nx * remove)
          add = y + radius + 1
          if add < ny then sum += source(base + nx * add)
          y += 1
        x += 1
      z += 1

  private def sumZ(
      source: Array[Double],
      destination: Array[Double],
      dims: Vector[Int],
      radius: Int
  ): Unit =
    val nx = dims(0)
    val ny = dims(1)
    val nz = dims(2)
    val plane = nx * ny
    var y = 0
    while y < ny do
      var x = 0
      while x < nx do
        val base = x + nx * y
        var sum = 0.0
        var add = 0
        val initialEnd = math.min(nz - 1, radius)
        while add <= initialEnd do
          sum += source(base + plane * add)
          add += 1
        var z = 0
        while z < nz do
          destination(base + plane * z) = sum
          val remove = z - radius
          if remove >= 0 then sum -= source(base + plane * remove)
          add = z + radius + 1
          if add < nz then sum += source(base + plane * add)
          z += 1
        x += 1
      y += 1

enum GaussianBoundary:
  /** Values outside the lattice are zero. */
  case Zero

  /** Half-sample symmetric reflection, equivalent to a no-flux boundary. */
  case Reflect

final case class GaussianSummary(validVoxels: Int, minimumWeight: Double, maximumWeight: Double)

final class GaussianReduction private ():
  private[internal] var validValue = 0
  private[internal] var minimumValue = Double.NaN
  private[internal] var maximumValue = Double.NaN

  def validVoxels: Int = validValue
  def minimumWeightOrNaN: Double = minimumValue
  def maximumWeightOrNaN: Double = maximumValue

  def snapshot: GaussianSummary =
    GaussianSummary(validValue, minimumValue, maximumValue)

object GaussianReduction:
  def apply(): GaussianReduction = new GaussianReduction()

final class GaussianWorkspace private (
    val grid: GridSpec,
    private[internal] val numeratorA: Array[Double],
    private[internal] val numeratorB: Array[Double],
    private[internal] val denominatorA: Array[Double],
    private[internal] val denominatorB: Array[Double]
):
  private var sigmaX = Double.NaN
  private var sigmaY = Double.NaN
  private var sigmaZ = Double.NaN
  private var weightsX = Array(1.0)
  private var weightsY = Array(1.0)
  private var weightsZ = Array(1.0)

  val ownedScalarBuffers: Int = 4

  private[internal] def weights(axis: Int, sigmaVox: Double): Array[Double] =
    axis match
      case 0 =>
        if sigmaX != sigmaVox then
          sigmaX = sigmaVox
          weightsX = GaussianWorkspace.makeWeights(sigmaVox)
        weightsX
      case 1 =>
        if sigmaY != sigmaVox then
          sigmaY = sigmaVox
          weightsY = GaussianWorkspace.makeWeights(sigmaVox)
        weightsY
      case 2 =>
        if sigmaZ != sigmaVox then
          sigmaZ = sigmaVox
          weightsZ = GaussianWorkspace.makeWeights(sigmaVox)
        weightsZ
      case _ => throw new IllegalArgumentException(s"Gaussian axis $axis is outside [0, 2]")

object GaussianWorkspace:
  def apply(grid: GridSpec): GaussianWorkspace =
    new GaussianWorkspace(
      grid,
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    )

  private def makeWeights(sigmaVox: Double): Array[Double] =
    if sigmaVox <= 1e-12 then Array(1.0)
    else
      val radius = math.max(1, math.ceil(3.0 * sigmaVox).toInt)
      val values = new Array[Double](2 * radius + 1)
      val denominator = 2.0 * sigmaVox * sigmaVox
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

/** Physical-scale separable Gaussian smoothing on caller-owned buffers. */
object Gaussian3D:
  def smoothInto(
      source: Array[Double],
      grid: GridSpec,
      sigmaMm: Double,
      destination: Array[Double],
      workspace: GaussianWorkspace,
      boundary: GaussianBoundary = GaussianBoundary.Reflect
  ): Unit =
    requireInputs(source, grid, sigmaMm, destination, workspace)
    var index = 0
    while index < grid.nVoxels do
      val value = source(index)
      require(value.isFinite, s"Gaussian source contains a non-finite value at $index")
      workspace.numeratorA(index) = value
      index += 1
    val wx = workspace.weights(0, sigmaMm / columnNorm(grid.affine, 0))
    val wy = workspace.weights(1, sigmaMm / columnNorm(grid.affine, 1))
    val wz = workspace.weights(2, sigmaMm / columnNorm(grid.affine, 2))
    convolveAxis(workspace.numeratorA, workspace.numeratorB, grid.shape, wx, 0, boundary)
    convolveAxis(workspace.numeratorB, workspace.numeratorA, grid.shape, wy, 1, boundary)
    convolveAxis(workspace.numeratorA, destination, grid.shape, wz, 2, boundary)

  def normalizedInto(
      source: Array[Double],
      support: Array[Double],
      grid: GridSpec,
      sigmaMm: Double,
      minimumWeight: Double,
      destination: Array[Double],
      destinationWeight: Array[Double],
      workspace: GaussianWorkspace,
      boundary: GaussianBoundary = GaussianBoundary.Reflect
  ): GaussianSummary =
    val reduction = GaussianReduction()
    normalizedInto(
      source,
      support,
      grid,
      sigmaMm,
      minimumWeight,
      destination,
      destinationWeight,
      workspace,
      boundary,
      reduction
    )
    reduction.snapshot

  def normalizedInto(
      source: Array[Double],
      support: Array[Double],
      grid: GridSpec,
      sigmaMm: Double,
      minimumWeight: Double,
      destination: Array[Double],
      destinationWeight: Array[Double],
      workspace: GaussianWorkspace,
      boundary: GaussianBoundary,
      reduction: GaussianReduction
  ): Unit =
    requireInputs(source, grid, sigmaMm, destination, workspace)
    require(support.length >= grid.nVoxels, "Gaussian support is too small")
    require(destinationWeight.length >= grid.nVoxels, "Gaussian weight destination is too small")
    require(minimumWeight.isFinite && minimumWeight > 0.0, "minimum Gaussian weight must be finite and positive")
    var index = 0
    while index < grid.nVoxels do
      val weight = support(index)
      require(weight.isFinite && weight >= 0.0, s"Gaussian support is invalid at $index")
      val value = source(index)
      val activeWeight = if value.isFinite then weight else 0.0
      workspace.numeratorA(index) = if activeWeight > 0.0 then activeWeight * value else 0.0
      workspace.denominatorA(index) = activeWeight
      index += 1
    val wx = workspace.weights(0, sigmaMm / columnNorm(grid.affine, 0))
    val wy = workspace.weights(1, sigmaMm / columnNorm(grid.affine, 1))
    val wz = workspace.weights(2, sigmaMm / columnNorm(grid.affine, 2))
    convolveAxis(workspace.numeratorA, workspace.numeratorB, grid.shape, wx, 0, boundary)
    convolveAxis(workspace.denominatorA, workspace.denominatorB, grid.shape, wx, 0, boundary)
    convolveAxis(workspace.numeratorB, workspace.numeratorA, grid.shape, wy, 1, boundary)
    convolveAxis(workspace.denominatorB, workspace.denominatorA, grid.shape, wy, 1, boundary)
    convolveAxis(workspace.numeratorA, workspace.numeratorB, grid.shape, wz, 2, boundary)
    convolveAxis(workspace.denominatorA, workspace.denominatorB, grid.shape, wz, 2, boundary)

    var valid = 0
    var minimum = Double.PositiveInfinity
    var maximum = 0.0
    index = 0
    while index < grid.nVoxels do
      val weight = workspace.denominatorB(index)
      destinationWeight(index) = weight
      if weight.isFinite && weight >= minimumWeight then
        val value = workspace.numeratorB(index) / weight
        destination(index) = if value.isFinite then value else 0.0
        if value.isFinite then
          valid += 1
          minimum = math.min(minimum, weight)
          maximum = math.max(maximum, weight)
      else destination(index) = 0.0
      index += 1
    reduction.validValue = valid
    reduction.minimumValue = if valid > 0 then minimum else Double.NaN
    reduction.maximumValue = if valid > 0 then maximum else Double.NaN

  private def requireInputs(
      source: Array[Double],
      grid: GridSpec,
      sigmaMm: Double,
      destination: Array[Double],
      workspace: GaussianWorkspace
  ): Unit =
    require(workspace.grid == grid, "Gaussian workspace/grid mismatch")
    require(source.length >= grid.nVoxels, "Gaussian source is too small")
    require(destination.length >= grid.nVoxels, "Gaussian destination is too small")
    require(sigmaMm.isFinite && sigmaMm >= 0.0, "Gaussian sigma must be finite and non-negative")

  private def columnNorm(matrix: DMat, column: Int): Double =
    val norm = math.sqrt(
      matrix(0, column) * matrix(0, column) +
        matrix(1, column) * matrix(1, column) +
        matrix(2, column) * matrix(2, column)
    )
    require(norm.isFinite && norm > 0.0, s"Gaussian grid axis $column has invalid spacing")
    norm

  private def convolveAxis(
      source: Array[Double],
      destination: Array[Double],
      dims: Vector[Int],
      weights: Array[Double],
      axis: Int,
      boundary: GaussianBoundary
  ): Unit =
    val nx = dims(0)
    val ny = dims(1)
    val nz = dims(2)
    val radius = weights.length / 2
    var z = 0
    while z < nz do
      var y = 0
      while y < ny do
        var x = 0
        while x < nx do
          var sum = 0.0
          var offset = -radius
          while offset <= radius do
            val xx = if axis == 0 then x + offset else x
            val yy = if axis == 1 then y + offset else y
            val zz = if axis == 2 then z + offset else z
            boundary match
              case GaussianBoundary.Zero =>
                if xx >= 0 && xx < nx && yy >= 0 && yy < ny && zz >= 0 && zz < nz then
                  sum += weights(offset + radius) * source(xx + nx * yy + nx * ny * zz)
              case GaussianBoundary.Reflect =>
                val rx = reflect(xx, nx)
                val ry = reflect(yy, ny)
                val rz = reflect(zz, nz)
                sum += weights(offset + radius) * source(rx + nx * ry + nx * ny * rz)
            offset += 1
          destination(x + nx * y + nx * ny * z) = sum
          x += 1
        y += 1
      z += 1

  private def reflect(index: Int, size: Int): Int =
    if size == 1 then 0
    else
      var reflected = index
      while reflected < 0 || reflected >= size do
        if reflected < 0 then reflected = -reflected - 1
        else reflected = 2 * size - reflected - 1
      reflected
