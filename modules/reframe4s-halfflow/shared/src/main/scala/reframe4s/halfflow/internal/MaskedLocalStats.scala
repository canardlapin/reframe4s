package reframe4s.halfflow.internal


final case class VoxelWindowRadius(x: Int, y: Int, z: Int):
  require(x >= 0 && y >= 0 && z >= 0, "voxel window radii must be non-negative")

final case class MaskedLocalStatsSummary(validPerChannel: Vector[Int], voxels: Int)

/** Reusable integral-image and physical-gradient workspace. */
final class MaskedLocalStatsWorkspace private (
    val grid: GridSpec,
    private[internal] val sum: Array[Double],
    private[internal] val sumSquares: Array[Double],
    private[internal] val weights: Array[Double],
    private[internal] val inverseAffine: DMat
):
  val ownedScalarBuffers: Int = 3

object MaskedLocalStatsWorkspace:
  def apply(grid: GridSpec): MaskedLocalStatsWorkspace =
    val padded = (grid.shape.x + 1) * (grid.shape.y + 1) * (grid.shape.z + 1)
    val inverse = DMat.invert(grid.affine).fold(
      reason => throw new IllegalArgumentException(s"local-statistics grid affine is singular: $reason"),
      identity
    )
    new MaskedLocalStatsWorkspace(
      grid,
      PrimitiveBuffers.ofSize[Double](padded),
      PrimitiveBuffers.ofSize[Double](padded),
      PrimitiveBuffers.ofSize[Double](padded),
      inverse
    )

object MaskedLocalStats:
  /** Writes channel-major local z scores for several voxel windows. */
  def normalizeChannelsInto(
      source: Array[Double],
      sourceValidity: FieldValidity,
      grid: GridSpec,
      radii: Vector[VoxelWindowRadius],
      epsilonPerChannel: Vector[Double],
      minimumValidFraction: Double,
      destination: Array[Double],
      destinationValidity: Array[Boolean],
      workspace: MaskedLocalStatsWorkspace
  ): MaskedLocalStatsSummary =
    require(workspace.grid == grid, "local-statistics workspace/grid mismatch")
    require(source.length >= grid.nVoxels, "local-statistics source is too small")
    sourceValidity.requireSize(grid.nVoxels)
    require(radii.nonEmpty, "at least one local-statistics window is required")
    require(epsilonPerChannel.length == radii.length, "one epsilon is required per local-statistics window")
    require(
      epsilonPerChannel.forall(value => value.isFinite && value > 0.0),
      "local-statistics epsilons must be finite and positive"
    )
    require(
      minimumValidFraction.isFinite && minimumValidFraction > 0.0 && minimumValidFraction <= 1.0,
      "minimum valid window fraction must be in (0, 1]"
    )
    val required = grid.nVoxels * radii.length
    require(destination.length >= required, "local-statistics destination is too small")
    require(destinationValidity.length >= required, "local-statistics validity destination is too small")
    buildIntegrals(source, sourceValidity, grid, workspace)
    val counts = Vector.newBuilder[Int]
    var channel = 0
    while channel < radii.length do
      counts += normalizeChannel(
        source,
        sourceValidity,
        grid,
        radii(channel),
        epsilonPerChannel(channel),
        minimumValidFraction,
        channel * grid.nVoxels,
        destination,
        destinationValidity,
        workspace
      )
      channel += 1
    MaskedLocalStatsSummary(counts.result(), grid.nVoxels)

  /** Writes physical covector gradients for channel-major scalar values. */
  def physicalGradientChannelsInto(
      values: Array[Double],
      valueValidity: Array[Boolean],
      grid: GridSpec,
      channels: Int,
      destination: Array[Double],
      destinationValidity: Array[Boolean],
      workspace: MaskedLocalStatsWorkspace
  ): MaskedLocalStatsSummary =
    require(workspace.grid == grid, "gradient workspace/grid mismatch")
    require(channels > 0, "gradient channel count must be positive")
    val scalarSize = grid.nVoxels * channels
    require(values.length >= scalarSize, "gradient source is too small")
    require(valueValidity.length >= scalarSize, "gradient source validity is too small")
    require(destination.length >= scalarSize * 3, "gradient destination is too small")
    require(destinationValidity.length >= scalarSize, "gradient validity destination is too small")
    val counts = Vector.newBuilder[Int]
    var channel = 0
    while channel < channels do
      counts += gradientChannel(
        values,
        valueValidity,
        grid,
        channel,
        destination,
        destinationValidity,
        workspace.inverseAffine
      )
      channel += 1
    MaskedLocalStatsSummary(counts.result(), grid.nVoxels)

  private def buildIntegrals(
      source: Array[Double],
      validity: FieldValidity,
      grid: GridSpec,
      workspace: MaskedLocalStatsWorkspace
  ): Unit =
    clear(workspace.sum)
    clear(workspace.sumSquares)
    clear(workspace.weights)
    val nx = grid.shape.x
    val ny = grid.shape.y
    val nz = grid.shape.z
    val px = nx + 1
    val py = ny + 1
    var z = 1
    while z <= nz do
      var y = 1
      while y <= ny do
        var x = 1
        while x <= nx do
          val sourceIndex = (x - 1) + nx * (y - 1) + nx * ny * (z - 1)
          val valid = validity.contains(sourceIndex) && source(sourceIndex).isFinite
          val value = if valid then source(sourceIndex) else 0.0
          val weight = if valid then 1.0 else 0.0
          val index = x + px * y + px * py * z
          val xm = index - 1
          val ym = index - px
          val zm = index - px * py
          val xym = ym - 1
          val xzm = zm - 1
          val yzm = zm - px
          val xyzm = yzm - 1
          workspace.sum(index) =
            value + workspace.sum(xm) + workspace.sum(ym) + workspace.sum(zm) -
              workspace.sum(xym) - workspace.sum(xzm) - workspace.sum(yzm) + workspace.sum(xyzm)
          workspace.sumSquares(index) =
            value * value + workspace.sumSquares(xm) + workspace.sumSquares(ym) + workspace.sumSquares(zm) -
              workspace.sumSquares(xym) - workspace.sumSquares(xzm) - workspace.sumSquares(yzm) +
              workspace.sumSquares(xyzm)
          workspace.weights(index) =
            weight + workspace.weights(xm) + workspace.weights(ym) + workspace.weights(zm) -
              workspace.weights(xym) - workspace.weights(xzm) - workspace.weights(yzm) + workspace.weights(xyzm)
          x += 1
        y += 1
      z += 1

  private def normalizeChannel(
      source: Array[Double],
      sourceValidity: FieldValidity,
      grid: GridSpec,
      radius: VoxelWindowRadius,
      epsilon: Double,
      minimumValidFraction: Double,
      offset: Int,
      destination: Array[Double],
      destinationValidity: Array[Boolean],
      workspace: MaskedLocalStatsWorkspace
  ): Int =
    val nx = grid.shape.x
    val ny = grid.shape.y
    val nz = grid.shape.z
    val nominal = (2 * radius.x + 1) * (2 * radius.y + 1) * (2 * radius.z + 1)
    val minimumCount = math.max(2, math.ceil(minimumValidFraction * nominal.toDouble).toInt)
    var validCount = 0
    var z = 0
    while z < nz do
      val z0 = math.max(0, z - radius.z)
      val z1 = math.min(nz, z + radius.z + 1)
      var y = 0
      while y < ny do
        val y0 = math.max(0, y - radius.y)
        val y1 = math.min(ny, y + radius.y + 1)
        var x = 0
        while x < nx do
          val x0 = math.max(0, x - radius.x)
          val x1 = math.min(nx, x + radius.x + 1)
          val index = x + nx * y + nx * ny * z
          val out = offset + index
          val count = box(workspace.weights, nx, ny, x0, x1, y0, y1, z0, z1)
          val ok = sourceValidity.contains(index) && source(index).isFinite && count >= minimumCount.toDouble
          if ok then
            val sum = box(workspace.sum, nx, ny, x0, x1, y0, y1, z0, z1)
            val sumSquares = box(workspace.sumSquares, nx, ny, x0, x1, y0, y1, z0, z1)
            val mean = sum / count
            val variance = math.max(0.0, sumSquares / count - mean * mean)
            val value = (source(index) - mean) / math.sqrt(variance + epsilon * epsilon)
            if value.isFinite then
              destination(out) = value
              destinationValidity(out) = true
              validCount += 1
            else
              destination(out) = 0.0
              destinationValidity(out) = false
          else
            destination(out) = 0.0
            destinationValidity(out) = false
          x += 1
        y += 1
      z += 1
    validCount

  private def gradientChannel(
      values: Array[Double],
      validity: Array[Boolean],
      grid: GridSpec,
      channel: Int,
      destination: Array[Double],
      destinationValidity: Array[Boolean],
      inverse: DMat
  ): Int =
    val nx = grid.shape.x
    val ny = grid.shape.y
    val nz = grid.shape.z
    val n = grid.nVoxels
    val plane = nx * ny
    val sourceOffset = channel * n
    val gradientOffset = channel * 3 * n
    var validCount = 0
    var index = 0
    while index < n do
      destination(gradientOffset + index) = 0.0
      destination(gradientOffset + n + index) = 0.0
      destination(gradientOffset + 2 * n + index) = 0.0
      destinationValidity(sourceOffset + index) = false
      index += 1
    var z = 1
    while z < nz - 1 do
      var y = 1
      while y < ny - 1 do
        var x = 1
        while x < nx - 1 do
          index = x + nx * y + plane * z
          val center = sourceOffset + index
          val ok =
            validity(center) && validity(center - 1) && validity(center + 1) &&
              validity(center - nx) && validity(center + nx) &&
              validity(center - plane) && validity(center + plane)
          if ok then
            val dx = 0.5 * (values(center + 1) - values(center - 1))
            val dy = 0.5 * (values(center + nx) - values(center - nx))
            val dz = 0.5 * (values(center + plane) - values(center - plane))
            destination(gradientOffset + index) = dx * inverse(0, 0) + dy * inverse(1, 0) + dz * inverse(2, 0)
            destination(gradientOffset + n + index) =
              dx * inverse(0, 1) + dy * inverse(1, 1) + dz * inverse(2, 1)
            destination(gradientOffset + 2 * n + index) =
              dx * inverse(0, 2) + dy * inverse(1, 2) + dz * inverse(2, 2)
            destinationValidity(center) = true
            validCount += 1
          x += 1
        y += 1
      z += 1
    validCount

  private def box(
      integral: Array[Double],
      nx: Int,
      ny: Int,
      x0: Int,
      x1: Int,
      y0: Int,
      y1: Int,
      z0: Int,
      z1: Int
  ): Double =
    val px = nx + 1
    val py = ny + 1
    val c000 = x0 + px * y0 + px * py * z0
    val c100 = x1 + px * y0 + px * py * z0
    val c010 = x0 + px * y1 + px * py * z0
    val c110 = x1 + px * y1 + px * py * z0
    val c001 = x0 + px * y0 + px * py * z1
    val c101 = x1 + px * y0 + px * py * z1
    val c011 = x0 + px * y1 + px * py * z1
    val c111 = x1 + px * y1 + px * py * z1
    integral(c111) - integral(c011) - integral(c101) - integral(c110) +
      integral(c001) + integral(c010) + integral(c100) - integral(c000)

  private def clear(values: Array[Double]): Unit =
    var index = 0
    while index < values.length do
      values(index) = 0.0
      index += 1
