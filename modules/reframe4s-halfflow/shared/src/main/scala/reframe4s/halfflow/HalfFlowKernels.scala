package reframe4s.halfflow

import ravel.ArrayBuilder
import ravel.MutableCanonicalArray
import ravel.MutableNDArray as MutableRavelArray
import ravel.NDArray as RavelArray
import ravel.Rank
import ravel.Shape
import reframe4s.halfflow.internal.*

/** Behavior when coordinate-map composition queries outside the sampled grid.
  *
  * `Identity` is an explicit extrapolation contract for near-identity self
  * maps: outside their finite sampled support, the map returns its query.
  * Invalid samples inside the grid remain invalid.
  */
enum CoordinateMapOutside:
  case Invalid
  case Identity
  /** Continue the outermost trilinear cell. Requires at least two samples per
    * axis for extrapolation. Preserves affine maps across the grid boundary;
    * invalid contributing samples remain invalid. Used by residual export.
    */
  case BoundaryLinear

final case class ScalarPullResult(
    values: NeuroVol[Double],
    valid: NeuroVol[Boolean]
)

final case class ChannelPullResult(
    grid: GridSpec,
    channels: Int,
    values: RavelArray[Double, Rank[4]],
    valid: Array[Boolean]
):
  require(channels > 0, "channels must be positive")
  require(
    values.shape == Shape(grid.dims(0), grid.dims(1), grid.dims(2), channels),
    "channel pull shape mismatch"
  )
  require(valid.length == grid.nVoxels, "channel pull validity length mismatch")

final case class DensePullResult(
    field: DenseVectorField,
    valid: Array[Boolean]
):
  require(valid.length == field.grid.nVoxels, "dense pull validity length mismatch")

final case class JacobianSummary(
    evaluated: Int,
    skipped: Int,
    nonPositive: Int,
    minimum: Option[Double],
    maximum: Option[Double],
    mean: Option[Double]
)

final case class InverseErrorSummary(
    evaluated: Int,
    skipped: Int,
    rmsMm: Option[Double],
    maximumMm: Option[Double],
    rmsVox: Option[Double],
    maximumVox: Option[Double]
)

final case class InversePairSummary(
    forwardThenBackward: InverseErrorSummary,
    backwardThenForward: InverseErrorSummary
)

/** Reusable primitive destination for a Jacobian reduction. */
final class JacobianReduction private ():
  private[halfflow] var evaluatedValue: Int = 0
  private[halfflow] var skippedValue: Int = 0
  private[halfflow] var nonPositiveValue: Int = 0
  private[halfflow] var minimumValue: Double = Double.NaN
  private[halfflow] var maximumValue: Double = Double.NaN
  private[halfflow] var meanValue: Double = Double.NaN

  def evaluated: Int = evaluatedValue
  def skipped: Int = skippedValue
  def nonPositive: Int = nonPositiveValue
  def minimumOrNaN: Double = minimumValue
  def maximumOrNaN: Double = maximumValue
  def meanOrNaN: Double = meanValue

  def snapshot: JacobianSummary =
    JacobianSummary(
      evaluatedValue,
      skippedValue,
      nonPositiveValue,
      if evaluatedValue == 0 then None else Some(minimumValue),
      if evaluatedValue == 0 then None else Some(maximumValue),
      if evaluatedValue == 0 then None else Some(meanValue)
    )

object JacobianReduction:
  def apply(): JacobianReduction = new JacobianReduction()

/** Reusable primitive destination for an inverse-composition reduction. */
final class InverseErrorReduction private ():
  private[halfflow] var evaluatedValue: Int = 0
  private[halfflow] var skippedValue: Int = 0
  private[halfflow] var rmsMmValue: Double = Double.NaN
  private[halfflow] var maximumMmValue: Double = Double.NaN
  private[halfflow] var rmsVoxValue: Double = Double.NaN
  private[halfflow] var maximumVoxValue: Double = Double.NaN

  def evaluated: Int = evaluatedValue
  def skipped: Int = skippedValue
  def rmsMmOrNaN: Double = rmsMmValue
  def maximumMmOrNaN: Double = maximumMmValue
  def rmsVoxOrNaN: Double = rmsVoxValue
  def maximumVoxOrNaN: Double = maximumVoxValue

  def snapshot: InverseErrorSummary =
    InverseErrorSummary(
      evaluatedValue,
      skippedValue,
      if evaluatedValue == 0 then None else Some(rmsMmValue),
      if evaluatedValue == 0 then None else Some(maximumMmValue),
      if evaluatedValue == 0 then None else Some(rmsVoxValue),
      if evaluatedValue == 0 then None else Some(maximumVoxValue)
    )

object InverseErrorReduction:
  def apply(): InverseErrorReduction = new InverseErrorReduction()

final case class PyramidLevelResult(
    values: NeuroVol[Double],
    valid: NeuroVol[Boolean]
)

/** Reusable scratch for mask-normalized separable pyramid filtering. */
final class PyramidWorkspace private (
    private[halfflow] val numeratorA: Array[Double],
    private[halfflow] val numeratorB: Array[Double],
    private[halfflow] val denominatorA: Array[Double],
    private[halfflow] val denominatorB: Array[Double]
):
  val capacity: Int = numeratorA.length
  private var sigmaX: Double = Double.NaN
  private var sigmaY: Double = Double.NaN
  private var sigmaZ: Double = Double.NaN
  private var weightsX: Array[Double] = Array.empty[Double]
  private var weightsY: Array[Double] = Array.empty[Double]
  private var weightsZ: Array[Double] = Array.empty[Double]

  private[halfflow] def requireCapacity(size: Int): Unit =
    require(capacity >= size, s"pyramid workspace capacity $capacity < required size $size")

  private[halfflow] def gaussianWeights(axis: Int, sigmaVox: Double): Array[Double] =
    axis match
      case 0 =>
        if sigmaVox != sigmaX then
          sigmaX = sigmaVox
          weightsX = PyramidWorkspace.makeGaussianWeights(sigmaVox)
        weightsX
      case 1 =>
        if sigmaVox != sigmaY then
          sigmaY = sigmaVox
          weightsY = PyramidWorkspace.makeGaussianWeights(sigmaVox)
        weightsY
      case 2 =>
        if sigmaVox != sigmaZ then
          sigmaZ = sigmaVox
          weightsZ = PyramidWorkspace.makeGaussianWeights(sigmaVox)
        weightsZ
      case _ => throw new IllegalArgumentException(s"pyramid axis $axis is outside [0, 2]")

object PyramidWorkspace:
  def apply(capacity: Int): PyramidWorkspace =
    require(capacity > 0, "pyramid workspace capacity must be positive")
    new PyramidWorkspace(
      PrimitiveBuffers.ofSize[Double](capacity),
      PrimitiveBuffers.ofSize[Double](capacity),
      PrimitiveBuffers.ofSize[Double](capacity),
      PrimitiveBuffers.ofSize[Double](capacity)
    )

  private def makeGaussianWeights(sigmaVox: Double): Array[Double] =
    if sigmaVox <= 1e-12 then Array(1.0)
    else
      val radius = math.max(1, math.ceil(3.0 * sigmaVox).toInt)
      val weights = Array.ofDim[Double](2 * radius + 1)
      val denominator = 2.0 * sigmaVox * sigmaVox
      var sum = 0.0
      var i = -radius
      while i <= radius do
        val weight = math.exp(-(i.toDouble * i.toDouble) / denominator)
        weights(i + radius) = weight
        sum += weight
        i += 1
      i = 0
      while i < weights.length do
        weights(i) /= sum
        i += 1
      weights

/** Reusable inverse-grid coefficients and vector-sample scratch.
  *
  * A sampler is tied to one grid and is intentionally mutable and
  * single-threaded. Create one per worker and reuse it across leaf calls.
  */
final class DenseFieldSampler private (
    val grid: GridSpec,
    private[halfflow] val inverse: DMat
):
  private[halfflow] var x: Double = 0.0
  private[halfflow] var y: Double = 0.0
  private[halfflow] var z: Double = 0.0
  private[halfflow] var valid: Boolean = false
  private[halfflow] var insideSupport: Boolean = false

object DenseFieldSampler:
  def apply(grid: GridSpec): DenseFieldSampler =
    val inverse =
      DMat.invert(grid.affine).fold(
        reason => throw new IllegalArgumentException(s"grid affine is singular: $reason"),
        matrix => matrix
      )
    new DenseFieldSampler(grid, inverse)

/** Primitive, structure-of-arrays kernels for physical-coordinate pull maps.
  *
  * Allocation-owning methods provide checked convenience APIs. Methods ending
  * in `Into` write to caller-owned buffers and allocate no full-volume
  * temporaries.
  */
private[halfflow] object HalfFlowKernels:

  private trait ChannelDestination:
    def write(targetIndex: Int, channel: Int, value: Double): Unit

  private final class LegacyChannelDestination(
      values: Array[Double],
      targetSize: Int
  ) extends ChannelDestination:
    def write(targetIndex: Int, channel: Int, value: Double): Unit =
      values(targetIndex + channel * targetSize) = value

  private final class RavelChannelDestination(
      builder: ArrayBuilder[Double],
      shape: SpatialDims,
      channels: Int
  ) extends ChannelDestination:
    def write(targetIndex: Int, channel: Int, value: Double): Unit =
      val x = targetIndex % shape.x
      val yz = targetIndex / shape.x
      val y = yz % shape.y
      val z = yz / shape.y
      val ravelIndex =
        channel + channels * (z + shape.z * (y + shape.y * x))
      builder.writeLinear(ravelIndex, value)

  def identity(grid: GridSpec): DensePullResult =
    val values = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    val valid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    identityInto(grid, values, valid)
    DensePullResult(
      DenseVectorField.fromLegacyPlanar(grid, values, DenseVectorFieldKind.SourceCoordinates),
      valid
    )

  def identityInto(
      grid: GridSpec,
      destination: Array[Double],
      destinationValid: Array[Boolean]
  ): Unit =
    require(destination.length >= grid.nVoxels * 3, "identity destination is too small")
    require(destinationValid.length >= grid.nVoxels, "identity validity destination is too small")
    val n = grid.nVoxels
    val nx = grid.extentX
    val ny = grid.extentY
    var index = 0
    while index < n do
      val x = index % nx
      val yz = index / nx
      val y = yz % ny
      val z = yz / ny
      val xd = x.toDouble
      val yd = y.toDouble
      val zd = z.toDouble
      destination(index) = gridAffineCoordinate(grid, 0, xd, yd, zd)
      destination(index + n) = gridAffineCoordinate(grid, 1, xd, yd, zd)
      destination(index + 2 * n) = gridAffineCoordinate(grid, 2, xd, yd, zd)
      destinationValid(index) = true
      index += 1

  def pullScalar(
      source: NeuroVol[Double],
      sourceCoordinates: DenseVectorField,
      mapValidity: FieldValidity = FieldValidity.All,
      sourceValidity: FieldValidity = FieldValidity.All,
      outside: Double = 0.0
  ): ScalarPullResult =
    val destination = PrimitiveBuffers.ofSize[Double](sourceCoordinates.grid.nVoxels)
    val valid = PrimitiveBuffers.ofSize[Boolean](sourceCoordinates.grid.nVoxels)
    pullScalarInto(source, sourceCoordinates, destination, valid, mapValidity, sourceValidity, outside)
    val targetSpace = sourceCoordinates.grid.toNeuroSpace
    ScalarPullResult(
      NeuroVol.fromLinear(destination, targetSpace, source.label),
      NeuroVol.fromLinear(valid, targetSpace, s"${source.label}-valid")
    )

  def pullScalarInto(
      source: NeuroVol[Double],
      sourceCoordinates: DenseVectorField,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      mapValidity: FieldValidity = FieldValidity.All,
      sourceValidity: FieldValidity = FieldValidity.All,
      outside: Double = 0.0
  ): Unit =
    val sourceGrid = GridSpec.fromSpace(source.space)
    pullScalarInto(
      source,
      sourceCoordinates,
      destination,
      destinationValid,
      DenseFieldSampler(sourceGrid),
      mapValidity,
      sourceValidity,
      outside
    )

  def pullScalarInto(
      source: NeuroVol[Double],
      sourceCoordinates: DenseVectorField,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      sourceSampler: DenseFieldSampler,
      mapValidity: FieldValidity,
      sourceValidity: FieldValidity,
      outside: Double
  ): Unit =
    pullChannelsIntoKnownChannels(
      source.values,
      sourceSampler.grid,
      sourceCoordinates,
      destination,
      destinationValid,
      sourceSampler,
      mapValidity,
      sourceValidity,
      outside,
      channels = 1,
      worldToVoxel = sourceSampler.inverse
    )

  /** Pull a scalar image through `coordinateAffine(sourceCoordinates(x))`.
    *
    * The affine is fused into the world-to-voxel sampling matrix, so callers
    * can retain an exact global affine without materializing or interpolating
    * a second dense coordinate field.
    */
  def pullScalarAffineInto(
      source: NeuroVol[Double],
      sourceCoordinates: DenseVectorField,
      coordinateAffine: DMat,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      sourceSampler: DenseFieldSampler,
      mapValidity: FieldValidity,
      sourceValidity: FieldValidity,
      outside: Double
  ): Unit =
    require(coordinateAffine.rows == 4 && coordinateAffine.cols == 4, "coordinate affine must be 4x4")
    pullChannelsIntoKnownChannels(
      source.values,
      sourceSampler.grid,
      sourceCoordinates,
      destination,
      destinationValid,
      sourceSampler,
      mapValidity,
      sourceValidity,
      outside,
      channels = 1,
      worldToVoxel = Affine.multiply(sourceSampler.inverse, coordinateAffine)
    )

  def pullScalarNearest(
      source: NeuroVol[Double],
      sourceCoordinates: DenseVectorField,
      mapValidity: FieldValidity = FieldValidity.All,
      sourceValidity: FieldValidity = FieldValidity.All,
      outside: Double = 0.0
  ): ScalarPullResult =
    val destination = PrimitiveBuffers.ofSize[Double](sourceCoordinates.grid.nVoxels)
    val valid = PrimitiveBuffers.ofSize[Boolean](sourceCoordinates.grid.nVoxels)
    pullScalarNearestInto(
      source,
      sourceCoordinates,
      destination,
      valid,
      mapValidity,
      sourceValidity,
      outside
    )
    val targetSpace = sourceCoordinates.grid.toNeuroSpace
    ScalarPullResult(
      NeuroVol.fromLinear(destination, targetSpace, source.label),
      NeuroVol.fromLinear(valid, targetSpace, s"${source.label}-valid")
    )

  def pullScalarNearestInto(
      source: NeuroVol[Double],
      sourceCoordinates: DenseVectorField,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      mapValidity: FieldValidity = FieldValidity.All,
      sourceValidity: FieldValidity = FieldValidity.All,
      outside: Double = 0.0
  ): Unit =
    val sourceGrid = GridSpec.fromSpace(source.space)
    pullScalarNearestInto(
      source,
      sourceCoordinates,
      destination,
      destinationValid,
      DenseFieldSampler(sourceGrid),
      mapValidity,
      sourceValidity,
      outside
    )

  /** Nearest-neighbour scalar pull through physical source coordinates.
    *
    * This is the label/mask counterpart to `pullScalarInto`. It writes through
    * caller-owned buffers and performs no full-volume allocation.
    */
  def pullScalarNearestInto(
      source: NeuroVol[Double],
      sourceCoordinates: DenseVectorField,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      sourceSampler: DenseFieldSampler,
      mapValidity: FieldValidity,
      sourceValidity: FieldValidity,
      outside: Double
  ): Unit =
    requireSourceCoordinates(sourceCoordinates)
    val sourceGrid = GridSpec.fromSpace(source.space)
    require(sourceSampler.grid == sourceGrid, "nearest scalar pull sampler/source grid mismatch")
    require(outside.isFinite, "outside value must be finite")
    val targetN = sourceCoordinates.grid.nVoxels
    val sourceN = sourceGrid.nVoxels
    require(destination.length >= targetN, "nearest scalar pull destination is too small")
    require(destinationValid.length >= targetN, "nearest scalar pull validity destination is too small")
    mapValidity.requireSize(targetN)
    sourceValidity.requireSize(sourceN)
    val inverse = sourceSampler.inverse
    val nx = sourceGrid.extentX
    val ny = sourceGrid.extentY
    val nz = sourceGrid.extentZ

    var targetIndex = 0
    while targetIndex < targetN do
      val worldX = sourceCoordinates.linearComponent(targetIndex, 0)
      val worldY = sourceCoordinates.linearComponent(targetIndex, 1)
      val worldZ = sourceCoordinates.linearComponent(targetIndex, 2)
      var ok =
        mapValidity.contains(targetIndex) &&
          worldX.isFinite && worldY.isFinite && worldZ.isFinite
      var value = outside
      if ok then
        val voxelX = snapVoxel(affineCoordinate(inverse, 0, worldX, worldY, worldZ))
        val voxelY = snapVoxel(affineCoordinate(inverse, 1, worldX, worldY, worldZ))
        val voxelZ = snapVoxel(affineCoordinate(inverse, 2, worldX, worldY, worldZ))
        ok = voxelX.isFinite && voxelY.isFinite && voxelZ.isFinite
        if ok then
          val x = math.round(voxelX).toInt
          val y = math.round(voxelY).toInt
          val z = math.round(voxelZ).toInt
          ok = inBounds(x, y, z, nx, ny, nz)
          if ok then
            val sourceIndex = x + y * nx + z * nx * ny
            ok = sourceValidity.contains(sourceIndex)
            if ok then
              value = source(x, y, z)
              ok = value.isFinite
      destination(targetIndex) = if ok then value else outside
      destinationValid(targetIndex) = ok
      targetIndex += 1

  def pullChannels(
      source: RavelArray[Double, Rank[4]],
      sourceGrid: GridSpec,
      sourceCoordinates: DenseVectorField,
      mapValidity: FieldValidity = FieldValidity.All,
      sourceValidity: FieldValidity = FieldValidity.All,
      outside: Double = 0.0
  ): ChannelPullResult =
    val channels = channelCount(source, sourceGrid)
    val valid = PrimitiveBuffers.ofSize[Boolean](sourceCoordinates.grid.nVoxels)
    val targetShape = sourceCoordinates.grid.shape
    val sourceSampler = DenseFieldSampler(sourceGrid)
    val values =
      RavelArray.build[Double, Rank[4]](
        Shape(targetShape.x, targetShape.y, targetShape.z, channels)
      ): builder =>
        pullChannelsIntoKnownChannels(
          source,
          sourceGrid,
          sourceCoordinates,
          new RavelChannelDestination(builder, targetShape, channels),
          valid,
          sourceSampler,
          mapValidity,
          sourceValidity,
          outside,
          channels,
          sourceSampler.inverse
        )
    ChannelPullResult(
      sourceCoordinates.grid,
      channels,
      values,
      valid
    )

  def pullChannelsInto(
      source: RavelArray[Double, Rank[4]],
      sourceGrid: GridSpec,
      sourceCoordinates: DenseVectorField,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      mapValidity: FieldValidity = FieldValidity.All,
      sourceValidity: FieldValidity = FieldValidity.All,
      outside: Double = 0.0
  ): Unit =
    pullChannelsInto(
      source,
      sourceGrid,
      sourceCoordinates,
      destination,
      destinationValid,
      DenseFieldSampler(sourceGrid),
      mapValidity,
      sourceValidity,
      outside
    )

  def pullChannelsInto(
      source: RavelArray[Double, Rank[4]],
      sourceGrid: GridSpec,
      sourceCoordinates: DenseVectorField,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      sourceSampler: DenseFieldSampler,
      mapValidity: FieldValidity,
      sourceValidity: FieldValidity,
      outside: Double
  ): Unit =
    val channels = channelCount(source, sourceGrid)
    val targetN = sourceCoordinates.grid.nVoxels
    require(destination.length >= targetN * channels, "channel pull destination is too small")
    pullChannelsIntoKnownChannels(
      source,
      sourceGrid,
      sourceCoordinates,
      new LegacyChannelDestination(destination, targetN),
      destinationValid,
      sourceSampler,
      mapValidity,
      sourceValidity,
      outside,
      channels,
      sourceSampler.inverse
    )

  private def pullChannelsIntoKnownChannels(
      source: RavelArray[Double, Rank[3]],
      sourceGrid: GridSpec,
      sourceCoordinates: DenseVectorField,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      sourceSampler: DenseFieldSampler,
      mapValidity: FieldValidity,
      sourceValidity: FieldValidity,
      outside: Double,
      channels: Int,
      worldToVoxel: DMat
  ): Unit =
    require(channels == 1, "rank-3 scalar image must have one channel")
    requireSourceCoordinates(sourceCoordinates)
    require(sourceSampler.grid == sourceGrid, "channel pull sampler/source grid mismatch")
    require(outside.isFinite, "outside value must be finite")
    val targetN = sourceCoordinates.grid.nVoxels
    val sourceN = sourceGrid.nVoxels
    require(destination.length >= targetN, "scalar pull destination is too small")
    require(destinationValid.length >= targetN, "scalar pull validity destination is too small")
    mapValidity.requireSize(targetN)
    sourceValidity.requireSize(sourceN)
    val inverse = worldToVoxel
    val nx = sourceGrid.extentX
    val ny = sourceGrid.extentY
    val nz = sourceGrid.extentZ

    var targetIndex = 0
    while targetIndex < targetN do
      val worldX = sourceCoordinates.linearComponent(targetIndex, 0)
      val worldY = sourceCoordinates.linearComponent(targetIndex, 1)
      val worldZ = sourceCoordinates.linearComponent(targetIndex, 2)
      var ok =
        mapValidity.contains(targetIndex) &&
          worldX.isFinite && worldY.isFinite && worldZ.isFinite
      var voxelX = 0.0
      var voxelY = 0.0
      var voxelZ = 0.0
      if ok then
        voxelX = snapVoxel(
          affineCoordinate(inverse, 0, worldX, worldY, worldZ)
        )
        voxelY = snapVoxel(
          affineCoordinate(inverse, 1, worldX, worldY, worldZ)
        )
        voxelZ = snapVoxel(
          affineCoordinate(inverse, 2, worldX, worldY, worldZ)
        )
        ok = voxelX.isFinite && voxelY.isFinite && voxelZ.isFinite

      val x0 = math.floor(voxelX).toInt
      val y0 = math.floor(voxelY).toInt
      val z0 = math.floor(voxelZ).toInt
      val fx = voxelX - x0.toDouble
      val fy = voxelY - y0.toDouble
      val fz = voxelZ - z0.toDouble

      if ok then
        var dz = 0
        while dz <= 1 && ok do
          val wz = if dz == 0 then 1.0 - fz else fz
          var dy = 0
          while dy <= 1 && ok do
            val wy = if dy == 0 then 1.0 - fy else fy
            var dx = 0
            while dx <= 1 && ok do
              val wx = if dx == 0 then 1.0 - fx else fx
              val weight = wx * wy * wz
              if weight != 0.0 then
                val x = x0 + dx
                val y = y0 + dy
                val z = z0 + dz
                if !inBounds(x, y, z, nx, ny, nz) then ok = false
                else
                  val sourceIndex = x + y * nx + z * nx * ny
                  if !sourceValidity.contains(sourceIndex) then ok = false
              dx += 1
            dy += 1
          dz += 1

      var sum = 0.0
      if ok then
        var dz = 0
        while dz <= 1 do
          val wz = if dz == 0 then 1.0 - fz else fz
          val z = z0 + dz
          var dy = 0
          while dy <= 1 do
            val wy = if dy == 0 then 1.0 - fy else fy
            val y = y0 + dy
            var dx = 0
            while dx <= 1 do
              val wx = if dx == 0 then 1.0 - fx else fx
              val weight = wx * wy * wz
              if weight != 0.0 then
                val x = x0 + dx
                val value = source(x, y, z)
                if !value.isFinite then ok = false
                sum += weight * value
              dx += 1
            dy += 1
          dz += 1
      destination(targetIndex) = if ok then sum else outside
      destinationValid(targetIndex) = ok
      targetIndex += 1

  private def pullChannelsIntoKnownChannels(
      source: RavelArray[Double, Rank[4]],
      sourceGrid: GridSpec,
      sourceCoordinates: DenseVectorField,
      destination: ChannelDestination,
      destinationValid: Array[Boolean],
      sourceSampler: DenseFieldSampler,
      mapValidity: FieldValidity,
      sourceValidity: FieldValidity,
      outside: Double,
      channels: Int,
      worldToVoxel: DMat
  ): Unit =
    requireSourceCoordinates(sourceCoordinates)
    require(sourceSampler.grid == sourceGrid, "channel pull sampler/source grid mismatch")
    require(outside.isFinite, "outside value must be finite")
    val targetN = sourceCoordinates.grid.nVoxels
    val sourceN = sourceGrid.nVoxels
    require(destinationValid.length >= targetN, "channel pull validity destination is too small")
    mapValidity.requireSize(targetN)
    sourceValidity.requireSize(sourceN)
    val inverse = worldToVoxel
    val nx = sourceGrid.extentX
    val ny = sourceGrid.extentY
    val nz = sourceGrid.extentZ

    var targetIndex = 0
    while targetIndex < targetN do
      val worldX = sourceCoordinates.linearComponent(targetIndex, 0)
      val worldY = sourceCoordinates.linearComponent(targetIndex, 1)
      val worldZ = sourceCoordinates.linearComponent(targetIndex, 2)
      var ok =
        mapValidity.contains(targetIndex) &&
          worldX.isFinite && worldY.isFinite && worldZ.isFinite
      var voxelX = 0.0
      var voxelY = 0.0
      var voxelZ = 0.0
      if ok then
        voxelX = snapVoxel(affineCoordinate(inverse, 0, worldX, worldY, worldZ))
        voxelY = snapVoxel(affineCoordinate(inverse, 1, worldX, worldY, worldZ))
        voxelZ = snapVoxel(affineCoordinate(inverse, 2, worldX, worldY, worldZ))
        ok = voxelX.isFinite && voxelY.isFinite && voxelZ.isFinite

      val x0 = math.floor(voxelX).toInt
      val y0 = math.floor(voxelY).toInt
      val z0 = math.floor(voxelZ).toInt
      val fx = voxelX - x0.toDouble
      val fy = voxelY - y0.toDouble
      val fz = voxelZ - z0.toDouble

      if ok then
        var dz = 0
        while dz <= 1 && ok do
          val wz = if dz == 0 then 1.0 - fz else fz
          var dy = 0
          while dy <= 1 && ok do
            val wy = if dy == 0 then 1.0 - fy else fy
            var dx = 0
            while dx <= 1 && ok do
              val wx = if dx == 0 then 1.0 - fx else fx
              val weight = wx * wy * wz
              if weight != 0.0 then
                val x = x0 + dx
                val y = y0 + dy
                val z = z0 + dz
                if !inBounds(x, y, z, nx, ny, nz) then ok = false
                else
                  val sourceIndex = x + y * nx + z * nx * ny
                  if !sourceValidity.contains(sourceIndex) then ok = false
              dx += 1
            dy += 1
          dz += 1

      var channel = 0
      while channel < channels do
        var sum = 0.0
        if ok then
          var dz = 0
          while dz <= 1 do
            val wz = if dz == 0 then 1.0 - fz else fz
            val z = z0 + dz
            var dy = 0
            while dy <= 1 do
              val wy = if dy == 0 then 1.0 - fy else fy
              val y = y0 + dy
              var dx = 0
              while dx <= 1 do
                val wx = if dx == 0 then 1.0 - fx else fx
                val weight = wx * wy * wz
                if weight != 0.0 then
                  val x = x0 + dx
                  val value = source(x, y, z, channel)
                  if !value.isFinite then ok = false
                  sum += weight * value
                dx += 1
              dy += 1
            dz += 1
        destination.write(targetIndex, channel, if ok then sum else outside)
        channel += 1
      destinationValid(targetIndex) = ok
      if !ok then
        channel = 0
        while channel < channels do
          destination.write(targetIndex, channel, outside)
          channel += 1
      targetIndex += 1

  /** Compose absolute pull maps: `left` is sampled through `right`. */
  def composePull(
      left: DenseVectorField,
      right: DenseVectorField,
      leftValidity: FieldValidity = FieldValidity.All,
      rightValidity: FieldValidity = FieldValidity.All,
      outside: CoordinateMapOutside = CoordinateMapOutside.Invalid
  ): DensePullResult =
    val destination = PrimitiveBuffers.ofSize[Double](left.grid.nVoxels * 3)
    val valid = PrimitiveBuffers.ofSize[Boolean](left.grid.nVoxels)
    composePullInto(left, right, destination, valid, leftValidity, rightValidity, outside)
    DensePullResult(
      DenseVectorField.fromLegacyPlanar(left.grid, destination, DenseVectorFieldKind.SourceCoordinates),
      valid
    )

  def composePullInto(
      left: DenseVectorField,
      right: DenseVectorField,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      leftValidity: FieldValidity = FieldValidity.All,
      rightValidity: FieldValidity = FieldValidity.All,
      outside: CoordinateMapOutside = CoordinateMapOutside.Invalid
  ): Unit =
    composePullInto(
      left,
      right,
      destination,
      destinationValid,
      DenseFieldSampler(right.grid),
      leftValidity,
      rightValidity,
      outside
    )

  def composePullInto(
      left: DenseVectorField,
      right: DenseVectorField,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      rightSampler: DenseFieldSampler,
      leftValidity: FieldValidity,
      rightValidity: FieldValidity
  ): Unit =
    composePullInto(
      left,
      right,
      destination,
      destinationValid,
      rightSampler,
      leftValidity,
      rightValidity,
      CoordinateMapOutside.Invalid
    )

  def composePullInto(
      left: DenseVectorField,
      right: DenseVectorField,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      rightSampler: DenseFieldSampler,
      leftValidity: FieldValidity,
      rightValidity: FieldValidity,
      outside: CoordinateMapOutside
  ): Unit =
    requireSourceCoordinates(left)
    requireSourceCoordinates(right)
    require(rightSampler.grid == right.grid, "composition sampler/right grid mismatch")
    val targetN = left.grid.nVoxels
    require(destination.length >= targetN * 3, "composition destination is too small")
    require(destinationValid.length >= targetN, "composition validity destination is too small")
    leftValidity.requireSize(targetN)
    rightValidity.requireSize(right.grid.nVoxels)
    val nx = left.grid.extentX
    val ny = left.grid.extentY
    val rightNx = right.grid.extentX
    val rightNy = right.grid.extentY
    val rightNz = right.grid.extentZ

    var index = 0
    while index < targetN do
      val x = index % nx
      val yz = index / nx
      val y = yz % ny
      val z = yz / ny
      val xd = x.toDouble
      val yd = y.toDouble
      val zd = z.toDouble
      val targetX = gridAffineCoordinate(left.grid, 0, xd, yd, zd)
      val targetY = gridAffineCoordinate(left.grid, 1, xd, yd, zd)
      val targetZ = gridAffineCoordinate(left.grid, 2, xd, yd, zd)
      val queryX = fieldValue(left, x, y, z, 0, ny, left.grid.extentZ)
      val queryY = fieldValue(left, x, y, z, 1, ny, left.grid.extentZ)
      val queryZ = fieldValue(left, x, y, z, 2, ny, left.grid.extentZ)
      val leftOk =
        leftValidity.contains(index) &&
          queryX.isFinite && queryY.isFinite && queryZ.isFinite
      if leftOk then
        sampleVectorAtWorldInto(
          right,
          rightNx,
          rightNy,
          rightNz,
          rightValidity,
          rightSampler,
          queryX,
          queryY,
          queryZ,
          outside
        )
      else
        rightSampler.valid = false
        rightSampler.insideSupport = false
      val identityOutside =
        leftOk && outside == CoordinateMapOutside.Identity && !rightSampler.insideSupport
      if rightSampler.valid then
        destination(index) = rightSampler.x
        destination(index + targetN) = rightSampler.y
        destination(index + 2 * targetN) = rightSampler.z
      else if identityOutside then
        destination(index) = queryX
        destination(index + targetN) = queryY
        destination(index + 2 * targetN) = queryZ
      else
        destination(index) = targetX
        destination(index + targetN) = targetY
        destination(index + 2 * targetN) = targetZ
      destinationValid(index) = rightSampler.valid || identityOutside
      index += 1

  /** Allocation-free self-composition for reusable Ravel workspace storage.
    *
    * This specialized path avoids introducing a second public field
    * representation: mutable arrays exist only as registration scratch, and
    * results are frozen into the canonical immutable component image.
    */
  def composeSelfPullInto(
      grid: GridSpec,
      source: MutableRavelArray[Double, Rank[1]],
      sourceValid: Array[Boolean],
      destination: MutableRavelArray[Double, Rank[1]],
      destinationValid: Array[Boolean],
      sampler: DenseFieldSampler,
      outside: CoordinateMapOutside
  ): Unit =
    val expectedSize = grid.nVoxels * 3
    require(source.size == expectedSize, "self-composition source shape mismatch")
    require(destination.size == expectedSize, "self-composition destination shape mismatch")
    require(!(source eq destination), "self-composition requires distinct source and destination")
    require(sourceValid.length == grid.nVoxels, "self-composition source validity mismatch")
    require(destinationValid.length == grid.nVoxels, "self-composition destination validity mismatch")
    require(sampler.grid == grid, "self-composition sampler/grid mismatch")
    val sourceValues = MutableCanonicalArray.require(source)
    val destinationValues = MutableCanonicalArray.require(destination)
    val nx = grid.extentX
    val ny = grid.extentY
    val nz = grid.extentZ
    val targetN = grid.nVoxels

    var index = 0
    while index < targetN do
      val x = index % nx
      val yz = index / nx
      val y = yz % ny
      val z = yz / ny
      val xd = x.toDouble
      val yd = y.toDouble
      val zd = z.toDouble
      val targetX = gridAffineCoordinate(grid, 0, xd, yd, zd)
      val targetY = gridAffineCoordinate(grid, 1, xd, yd, zd)
      val targetZ = gridAffineCoordinate(grid, 2, xd, yd, zd)
      val sourceBase = 3 * (z + nz * (y + ny * x))
      val queryX = sourceValues(sourceBase)
      val queryY = sourceValues(sourceBase + 1)
      val queryZ = sourceValues(sourceBase + 2)
      val sourceOk =
        sourceValid(index) &&
          queryX.isFinite && queryY.isFinite && queryZ.isFinite
      if sourceOk then
        sampleMutableVectorAtWorldInto(
          sourceValues,
          nx,
          ny,
          nz,
          sourceValid,
          sampler,
          queryX,
          queryY,
          queryZ,
          outside
        )
      else
        sampler.valid = false
        sampler.insideSupport = false
      val identityOutside =
        sourceOk && outside == CoordinateMapOutside.Identity && !sampler.insideSupport
      if sampler.valid then
        destinationValues(sourceBase) = sampler.x
        destinationValues(sourceBase + 1) = sampler.y
        destinationValues(sourceBase + 2) = sampler.z
      else if identityOutside then
        destinationValues(sourceBase) = queryX
        destinationValues(sourceBase + 1) = queryY
        destinationValues(sourceBase + 2) = queryZ
      else
        destinationValues(sourceBase) = targetX
        destinationValues(sourceBase + 1) = targetY
        destinationValues(sourceBase + 2) = targetZ
      destinationValid(index) = sampler.valid || identityOutside
      index += 1

  def regridPull(
      source: DenseVectorField,
      targetGrid: GridSpec,
      sourceValidity: FieldValidity = FieldValidity.All,
      outside: CoordinateMapOutside = CoordinateMapOutside.Invalid
  ): DensePullResult =
    val destination = PrimitiveBuffers.ofSize[Double](targetGrid.nVoxels * 3)
    val valid = PrimitiveBuffers.ofSize[Boolean](targetGrid.nVoxels)
    regridPullInto(source, targetGrid, destination, valid, sourceValidity, outside)
    DensePullResult(
      DenseVectorField.fromLegacyPlanar(targetGrid, destination, DenseVectorFieldKind.SourceCoordinates),
      valid
    )

  def regridPullInto(
      source: DenseVectorField,
      targetGrid: GridSpec,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      sourceValidity: FieldValidity = FieldValidity.All,
      outside: CoordinateMapOutside = CoordinateMapOutside.Invalid
  ): Unit =
    regridPullInto(
      source,
      targetGrid,
      destination,
      destinationValid,
      DenseFieldSampler(source.grid),
      sourceValidity,
      outside
    )

  def regridPullInto(
      source: DenseVectorField,
      targetGrid: GridSpec,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      sourceSampler: DenseFieldSampler,
      sourceValidity: FieldValidity
  ): Unit =
    regridPullInto(
      source,
      targetGrid,
      destination,
      destinationValid,
      sourceSampler,
      sourceValidity,
      CoordinateMapOutside.Invalid
    )

  def regridPullInto(
      source: DenseVectorField,
      targetGrid: GridSpec,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      sourceSampler: DenseFieldSampler,
      sourceValidity: FieldValidity,
      outside: CoordinateMapOutside
  ): Unit =
    requireSourceCoordinates(source)
    require(sourceSampler.grid == source.grid, "regrid sampler/source grid mismatch")
    require(destination.length >= targetGrid.nVoxels * 3, "regrid destination is too small")
    require(destinationValid.length >= targetGrid.nVoxels, "regrid validity destination is too small")
    sourceValidity.requireSize(source.grid.nVoxels)
    val targetN = targetGrid.nVoxels
    val nx = targetGrid.extentX
    val ny = targetGrid.extentY
    val sourceNx = source.grid.extentX
    val sourceNy = source.grid.extentY
    val sourceNz = source.grid.extentZ

    var index = 0
    while index < targetN do
      val x = index % nx
      val yz = index / nx
      val y = yz % ny
      val z = yz / ny
      val xd = x.toDouble
      val yd = y.toDouble
      val zd = z.toDouble
      val worldX = gridAffineCoordinate(targetGrid, 0, xd, yd, zd)
      val worldY = gridAffineCoordinate(targetGrid, 1, xd, yd, zd)
      val worldZ = gridAffineCoordinate(targetGrid, 2, xd, yd, zd)
      sampleVectorAtWorldInto(
        source,
        sourceNx,
        sourceNy,
        sourceNz,
        sourceValidity,
        sourceSampler,
        worldX,
        worldY,
        worldZ,
        outside
      )
      val identityOutside =
        outside == CoordinateMapOutside.Identity && !sourceSampler.insideSupport
      if sourceSampler.valid then
        destination(index) = sourceSampler.x
        destination(index + targetN) = sourceSampler.y
        destination(index + 2 * targetN) = sourceSampler.z
      else
        destination(index) = worldX
        destination(index + targetN) = worldY
        destination(index + 2 * targetN) = worldZ
      destinationValid(index) = sourceSampler.valid || identityOutside
      index += 1

  /** Write central finite-difference determinants in physical coordinates. */
  def jacobianDeterminantsInto(
      field: DenseVectorField,
      determinants: Array[Double],
      determinantValid: Array[Boolean],
      fieldValidity: FieldValidity = FieldValidity.All
  ): JacobianSummary =
    jacobianDeterminantsInto(
      field,
      determinants,
      determinantValid,
      DenseFieldSampler(field.grid),
      fieldValidity
    )

  def jacobianDeterminantsInto(
      field: DenseVectorField,
      determinants: Array[Double],
      determinantValid: Array[Boolean],
      fieldSampler: DenseFieldSampler,
      fieldValidity: FieldValidity
  ): JacobianSummary =
    val reduction = JacobianReduction()
    jacobianDeterminantsReduceInto(
      field,
      determinants,
      determinantValid,
      fieldSampler,
      fieldValidity,
      reduction
    )
    reduction.snapshot

  def jacobianDeterminantsReduceInto(
      field: DenseVectorField,
      determinants: Array[Double],
      determinantValid: Array[Boolean],
      fieldSampler: DenseFieldSampler,
      fieldValidity: FieldValidity,
      reduction: JacobianReduction,
      interiorMargin: Int = 1
  ): Unit =
    requireSourceCoordinates(field)
    require(fieldSampler.grid == field.grid, "Jacobian sampler/field grid mismatch")
    require(interiorMargin >= 1, "Jacobian interior margin must be positive")
    val n = field.grid.nVoxels
    require(determinants.length >= n, "Jacobian determinant destination is too small")
    require(determinantValid.length >= n, "Jacobian validity destination is too small")
    fieldValidity.requireSize(n)
    val inverse = fieldSampler.inverse
    val nx = field.grid.extentX
    val ny = field.grid.extentY
    val nz = field.grid.extentZ
    val xy = nx * ny
    var index = 0
    while index < n do
      determinants(index) = Double.NaN
      determinantValid(index) = false
      index += 1

    var count = 0
    var nonPositive = 0
    var min = Double.PositiveInfinity
    var max = Double.NegativeInfinity
    var sum = 0.0
    var z = interiorMargin
    while z < nz - interiorMargin do
      var y = interiorMargin
      while y < ny - interiorMargin do
        var x = interiorMargin
        while x < nx - interiorMargin do
          index = x + y * nx + z * xy
          val xm = index - 1
          val xp = index + 1
          val ym = index - nx
          val yp = index + nx
          val zm = index - xy
          val zp = index + xy
          val valid =
            fieldValidity.contains(index) &&
              fieldValidity.contains(xm) && fieldValidity.contains(xp) &&
              fieldValidity.contains(ym) && fieldValidity.contains(yp) &&
              fieldValidity.contains(zm) && fieldValidity.contains(zp)
          if valid then
            val d00 =
              0.5 * (
                fieldValue(field, x + 1, y, z, 0, ny, nz) -
                  fieldValue(field, x - 1, y, z, 0, ny, nz)
              )
            val d01 =
              0.5 * (
                fieldValue(field, x, y + 1, z, 0, ny, nz) -
                  fieldValue(field, x, y - 1, z, 0, ny, nz)
              )
            val d02 =
              0.5 * (
                fieldValue(field, x, y, z + 1, 0, ny, nz) -
                  fieldValue(field, x, y, z - 1, 0, ny, nz)
              )
            val d10 =
              0.5 * (
                fieldValue(field, x + 1, y, z, 1, ny, nz) -
                  fieldValue(field, x - 1, y, z, 1, ny, nz)
              )
            val d11 =
              0.5 * (
                fieldValue(field, x, y + 1, z, 1, ny, nz) -
                  fieldValue(field, x, y - 1, z, 1, ny, nz)
              )
            val d12 =
              0.5 * (
                fieldValue(field, x, y, z + 1, 1, ny, nz) -
                  fieldValue(field, x, y, z - 1, 1, ny, nz)
              )
            val d20 =
              0.5 * (
                fieldValue(field, x + 1, y, z, 2, ny, nz) -
                  fieldValue(field, x - 1, y, z, 2, ny, nz)
              )
            val d21 =
              0.5 * (
                fieldValue(field, x, y + 1, z, 2, ny, nz) -
                  fieldValue(field, x, y - 1, z, 2, ny, nz)
              )
            val d22 =
              0.5 * (
                fieldValue(field, x, y, z + 1, 2, ny, nz) -
                  fieldValue(field, x, y, z - 1, 2, ny, nz)
              )
            val j00 = d00 * inverse(0, 0) + d01 * inverse(1, 0) + d02 * inverse(2, 0)
            val j01 = d00 * inverse(0, 1) + d01 * inverse(1, 1) + d02 * inverse(2, 1)
            val j02 = d00 * inverse(0, 2) + d01 * inverse(1, 2) + d02 * inverse(2, 2)
            val j10 = d10 * inverse(0, 0) + d11 * inverse(1, 0) + d12 * inverse(2, 0)
            val j11 = d10 * inverse(0, 1) + d11 * inverse(1, 1) + d12 * inverse(2, 1)
            val j12 = d10 * inverse(0, 2) + d11 * inverse(1, 2) + d12 * inverse(2, 2)
            val j20 = d20 * inverse(0, 0) + d21 * inverse(1, 0) + d22 * inverse(2, 0)
            val j21 = d20 * inverse(0, 1) + d21 * inverse(1, 1) + d22 * inverse(2, 1)
            val j22 = d20 * inverse(0, 2) + d21 * inverse(1, 2) + d22 * inverse(2, 2)
            val determinant =
              j00 * (j11 * j22 - j12 * j21) -
                j01 * (j10 * j22 - j12 * j20) +
                j02 * (j10 * j21 - j11 * j20)
            if determinant.isFinite then
              determinants(index) = determinant
              determinantValid(index) = true
              count += 1
              if determinant <= 0.0 then nonPositive += 1
              if determinant < min then min = determinant
              if determinant > max then max = determinant
              sum += determinant
          x += 1
        y += 1
      z += 1

    reduction.evaluatedValue = count
    reduction.skippedValue = n - count
    reduction.nonPositiveValue = nonPositive
    reduction.minimumValue = if count == 0 then Double.NaN else min
    reduction.maximumValue = if count == 0 then Double.NaN else max
    reduction.meanValue = if count == 0 then Double.NaN else sum / count.toDouble

  def inverseErrorReduce(
      first: DenseVectorField,
      second: DenseVectorField,
      firstValidity: FieldValidity = FieldValidity.All,
      secondValidity: FieldValidity = FieldValidity.All
  ): InverseErrorSummary =
    inverseErrorReduce(
      first,
      second,
      DenseFieldSampler(first.grid),
      DenseFieldSampler(second.grid),
      firstValidity,
      secondValidity
    )

  def inverseErrorReduce(
      first: DenseVectorField,
      second: DenseVectorField,
      firstSampler: DenseFieldSampler,
      secondSampler: DenseFieldSampler,
      firstValidity: FieldValidity,
      secondValidity: FieldValidity
  ): InverseErrorSummary =
    val reduction = InverseErrorReduction()
    inverseErrorReduceInto(
      first,
      second,
      firstSampler,
      secondSampler,
      firstValidity,
      secondValidity,
      reduction
    )
    reduction.snapshot

  def inverseErrorReduceInto(
      first: DenseVectorField,
      second: DenseVectorField,
      firstSampler: DenseFieldSampler,
      secondSampler: DenseFieldSampler,
      firstValidity: FieldValidity,
      secondValidity: FieldValidity,
      reduction: InverseErrorReduction
  ): Unit =
    requireSourceCoordinates(first)
    requireSourceCoordinates(second)
    require(firstSampler.grid == first.grid, "inverse-error sampler/first grid mismatch")
    require(secondSampler.grid == second.grid, "inverse-error sampler/second grid mismatch")
    val n = first.grid.nVoxels
    firstValidity.requireSize(n)
    secondValidity.requireSize(second.grid.nVoxels)
    val inverseFirst = firstSampler.inverse
    val nx = first.grid.extentX
    val ny = first.grid.extentY
    val secondNx = second.grid.extentX
    val secondNy = second.grid.extentY
    val secondNz = second.grid.extentZ
    var count = 0
    var sumSquaredMm = 0.0
    var maxMm = 0.0
    var sumSquaredVox = 0.0
    var maxVox = 0.0
    var index = 0
    while index < n do
      val x = index % nx
      val yz = index / nx
      val y = yz % ny
      val z = yz / ny
      val xd = x.toDouble
      val yd = y.toDouble
      val zd = z.toDouble
      val targetX = gridAffineCoordinate(first.grid, 0, xd, yd, zd)
      val targetY = gridAffineCoordinate(first.grid, 1, xd, yd, zd)
      val targetZ = gridAffineCoordinate(first.grid, 2, xd, yd, zd)
      val queryX = fieldValue(first, x, y, z, 0, ny, first.grid.extentZ)
      val queryY = fieldValue(first, x, y, z, 1, ny, first.grid.extentZ)
      val queryZ = fieldValue(first, x, y, z, 2, ny, first.grid.extentZ)
      if firstValidity.contains(index) && queryX.isFinite && queryY.isFinite && queryZ.isFinite then
        sampleVectorAtWorldInto(
          second,
          secondNx,
          secondNy,
          secondNz,
          secondValidity,
          secondSampler,
          queryX,
          queryY,
          queryZ
        )
      else secondSampler.valid = false
      if secondSampler.valid then
        val errorX = secondSampler.x - targetX
        val errorY = secondSampler.y - targetY
        val errorZ = secondSampler.z - targetZ
        val squaredMm = errorX * errorX + errorY * errorY + errorZ * errorZ
        val voxelX = inverseFirst(0, 0) * errorX + inverseFirst(0, 1) * errorY + inverseFirst(0, 2) * errorZ
        val voxelY = inverseFirst(1, 0) * errorX + inverseFirst(1, 1) * errorY + inverseFirst(1, 2) * errorZ
        val voxelZ = inverseFirst(2, 0) * errorX + inverseFirst(2, 1) * errorY + inverseFirst(2, 2) * errorZ
        val squaredVox = voxelX * voxelX + voxelY * voxelY + voxelZ * voxelZ
        val errorMm = math.sqrt(squaredMm)
        val errorVox = math.sqrt(squaredVox)
        count += 1
        sumSquaredMm += squaredMm
        sumSquaredVox += squaredVox
        if errorMm > maxMm then maxMm = errorMm
        if errorVox > maxVox then maxVox = errorVox
      index += 1

    reduction.evaluatedValue = count
    reduction.skippedValue = n - count
    reduction.rmsMmValue = if count == 0 then Double.NaN else math.sqrt(sumSquaredMm / count.toDouble)
    reduction.maximumMmValue = if count == 0 then Double.NaN else maxMm
    reduction.rmsVoxValue = if count == 0 then Double.NaN else math.sqrt(sumSquaredVox / count.toDouble)
    reduction.maximumVoxValue = if count == 0 then Double.NaN else maxVox

  def inversePairError(
      forward: DenseVectorField,
      backward: DenseVectorField,
      forwardValidity: FieldValidity = FieldValidity.All,
      backwardValidity: FieldValidity = FieldValidity.All
  ): InversePairSummary =
    inversePairError(
      forward,
      backward,
      DenseFieldSampler(forward.grid),
      DenseFieldSampler(backward.grid),
      forwardValidity,
      backwardValidity
    )

  def inversePairError(
      forward: DenseVectorField,
      backward: DenseVectorField,
      forwardSampler: DenseFieldSampler,
      backwardSampler: DenseFieldSampler,
      forwardValidity: FieldValidity,
      backwardValidity: FieldValidity
  ): InversePairSummary =
    val forwardReduction = InverseErrorReduction()
    val backwardReduction = InverseErrorReduction()
    inversePairErrorInto(
      forward,
      backward,
      forwardSampler,
      backwardSampler,
      forwardValidity,
      backwardValidity,
      forwardReduction,
      backwardReduction
    )
    InversePairSummary(forwardReduction.snapshot, backwardReduction.snapshot)

  def inversePairErrorInto(
      forward: DenseVectorField,
      backward: DenseVectorField,
      forwardSampler: DenseFieldSampler,
      backwardSampler: DenseFieldSampler,
      forwardValidity: FieldValidity,
      backwardValidity: FieldValidity,
      forwardReduction: InverseErrorReduction,
      backwardReduction: InverseErrorReduction
  ): Unit =
    inverseErrorReduceInto(
      forward,
      backward,
      forwardSampler,
      backwardSampler,
      forwardValidity,
      backwardValidity,
      forwardReduction
    )
    inverseErrorReduceInto(
      backward,
      forward,
      backwardSampler,
      forwardSampler,
      backwardValidity,
      forwardValidity,
      backwardReduction
    )

  def pyramidGrid(source: GridSpec, shrink: Int): GridSpec =
    require(shrink >= 1, "pyramid shrink must be at least one")
    val dims = Vector(
      (source.shape.x - 1) / shrink + 1,
      (source.shape.y - 1) / shrink + 1,
      (source.shape.z - 1) / shrink + 1
    )
    val a = source.affine
    val scale = shrink.toDouble
    val affine = DMat.fromRows(
      Vector(
        Vector(a(0, 0) * scale, a(0, 1) * scale, a(0, 2) * scale, a(0, 3)),
        Vector(a(1, 0) * scale, a(1, 1) * scale, a(1, 2) * scale, a(1, 3)),
        Vector(a(2, 0) * scale, a(2, 1) * scale, a(2, 2) * scale, a(2, 3)),
        Vector(0.0, 0.0, 0.0, 1.0)
      )
    )
    GridSpec(dims, affine)

  def buildPyramidLevel(
      source: NeuroVol[Double],
      shrink: Int,
      sigmaMm: Double,
      sourceValidity: FieldValidity = FieldValidity.All,
      minimumWeight: Double = 1e-8
  ): PyramidLevelResult =
    val sourceGrid = GridSpec.fromSpace(source.space)
    val targetGrid = pyramidGrid(sourceGrid, shrink)
    val destination = PrimitiveBuffers.ofSize[Double](targetGrid.nVoxels)
    val valid = PrimitiveBuffers.ofSize[Boolean](targetGrid.nVoxels)
    val workspace = PyramidWorkspace(sourceGrid.nVoxels)
    buildPyramidLevelInto(
      source,
      targetGrid,
      sigmaMm,
      workspace,
      destination,
      valid,
      sourceValidity,
      minimumWeight
    )
    val targetSpace = targetGrid.toNeuroSpace
    PyramidLevelResult(
      NeuroVol.fromLinear(destination, targetSpace, source.label),
      NeuroVol.fromLinear(valid, targetSpace, s"${source.label}-valid")
    )

  /** Mask-normalized Gaussian prefilter followed by physical-grid sampling. */
  def buildPyramidLevelInto(
      source: NeuroVol[Double],
      targetGrid: GridSpec,
      sigmaMm: Double,
      workspace: PyramidWorkspace,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      sourceValidity: FieldValidity = FieldValidity.All,
      minimumWeight: Double = 1e-8
  ): Unit =
    val sourceGrid = GridSpec.fromSpace(source.space)
    buildPyramidLevelInto(
      source,
      targetGrid,
      sigmaMm,
      workspace,
      destination,
      destinationValid,
      DenseFieldSampler(sourceGrid),
      sourceValidity,
      minimumWeight
    )

  def buildPyramidLevelInto(
      source: NeuroVol[Double],
      targetGrid: GridSpec,
      sigmaMm: Double,
      workspace: PyramidWorkspace,
      destination: Array[Double],
      destinationValid: Array[Boolean],
      sourceSampler: DenseFieldSampler,
      sourceValidity: FieldValidity,
      minimumWeight: Double
  ): Unit =
    require(sigmaMm >= 0.0 && sigmaMm.isFinite, "pyramid sigma must be finite and non-negative")
    require(minimumWeight > 0.0 && minimumWeight.isFinite, "minimum pyramid weight must be positive and finite")
    val sourceGrid = sourceSampler.grid
    val sourceSpace = source.space
    require(
      sourceSpace.dims.length >= 3 &&
        sourceSpace.dims(0) == sourceGrid.extentX &&
        sourceSpace.dims(1) == sourceGrid.extentY &&
        sourceSpace.dims(2) == sourceGrid.extentZ &&
        sourceSpace.trans == sourceGrid.affine,
      "pyramid sampler/source grid mismatch"
    )
    val sourceN = sourceGrid.nVoxels
    val targetN = targetGrid.nVoxels
    workspace.requireCapacity(sourceN)
    sourceValidity.requireSize(sourceN)
    require(destination.length >= targetN, "pyramid destination is too small")
    require(destinationValid.length >= targetN, "pyramid validity destination is too small")
    var index = 0
    while index < sourceN do
      val value = source.linear(index)
      val valid = sourceValidity.contains(index) && value.isFinite
      workspace.numeratorA(index) = if valid then value else 0.0
      workspace.denominatorA(index) = if valid then 1.0 else 0.0
      index += 1

    val sourceAffine = sourceGrid.affine
    val spacingX = math.sqrt(
      sourceAffine(0, 0) * sourceAffine(0, 0) +
        sourceAffine(1, 0) * sourceAffine(1, 0) +
        sourceAffine(2, 0) * sourceAffine(2, 0)
    )
    val spacingY = math.sqrt(
      sourceAffine(0, 1) * sourceAffine(0, 1) +
        sourceAffine(1, 1) * sourceAffine(1, 1) +
        sourceAffine(2, 1) * sourceAffine(2, 1)
    )
    val spacingZ = math.sqrt(
      sourceAffine(0, 2) * sourceAffine(0, 2) +
        sourceAffine(1, 2) * sourceAffine(1, 2) +
        sourceAffine(2, 2) * sourceAffine(2, 2)
    )
    val wx = workspace.gaussianWeights(0, sigmaMm / spacingX)
    val wy = workspace.gaussianWeights(1, sigmaMm / spacingY)
    val wz = workspace.gaussianWeights(2, sigmaMm / spacingZ)
    convolveAxis(workspace.numeratorA, workspace.numeratorB, sourceGrid.shape, wx, 0)
    convolveAxis(workspace.denominatorA, workspace.denominatorB, sourceGrid.shape, wx, 0)
    convolveAxis(workspace.numeratorB, workspace.numeratorA, sourceGrid.shape, wy, 1)
    convolveAxis(workspace.denominatorB, workspace.denominatorA, sourceGrid.shape, wy, 1)
    convolveAxis(workspace.numeratorA, workspace.numeratorB, sourceGrid.shape, wz, 2)
    convolveAxis(workspace.denominatorA, workspace.denominatorB, sourceGrid.shape, wz, 2)

    val inverseSource = sourceSampler.inverse
    val targetAffine = targetGrid.affine
    val targetNx = targetGrid.extentX
    val targetNy = targetGrid.extentY
    index = 0
    while index < targetN do
      val x = index % targetNx
      val yz = index / targetNx
      val y = yz % targetNy
      val z = yz / targetNy
      val xd = x.toDouble
      val yd = y.toDouble
      val zd = z.toDouble
      val worldX = affineCoordinate(targetAffine, 0, xd, yd, zd)
      val worldY = affineCoordinate(targetAffine, 1, xd, yd, zd)
      val worldZ = affineCoordinate(targetAffine, 2, xd, yd, zd)
      val voxelX = affineCoordinate(inverseSource, 0, worldX, worldY, worldZ)
      val voxelY = affineCoordinate(inverseSource, 1, worldX, worldY, worldZ)
      val voxelZ = affineCoordinate(inverseSource, 2, worldX, worldY, worldZ)
      val numerator = samplePartialLinear(workspace.numeratorB, sourceGrid.shape, voxelX, voxelY, voxelZ)
      val denominator = samplePartialLinear(workspace.denominatorB, sourceGrid.shape, voxelX, voxelY, voxelZ)
      val valid = denominator.isFinite && denominator >= minimumWeight && numerator.isFinite
      destination(index) = if valid then numerator / denominator else 0.0
      destinationValid(index) = valid
      index += 1

  private def channelCount(
      source: RavelArray[Double, Rank[4]],
      grid: GridSpec
  ): Int =
    val channels = source.shape(3)
    require(
      source.shape(0) == grid.dims(0) &&
        source.shape(1) == grid.dims(1) &&
        source.shape(2) == grid.dims(2) &&
        channels > 0,
      s"source shape ${source.shape} must be grid dims ${grid.dims} plus a positive channel dimension"
    )
    channels

  private def requireSourceCoordinates(field: DenseVectorField): Unit =
    require(
      field.kind == DenseVectorFieldKind.SourceCoordinates,
      "dense field kernel requires absolute source coordinates"
    )

  private def sampleVectorAtWorldInto(
      field: DenseVectorField,
      nx: Int,
      ny: Int,
      nz: Int,
      validity: FieldValidity,
      sampler: DenseFieldSampler,
      worldX: Double,
      worldY: Double,
      worldZ: Double,
      outside: CoordinateMapOutside = CoordinateMapOutside.Invalid
  ): Unit =
    val inverse = sampler.inverse
    val voxelX = snapVoxel(affineCoordinate(inverse, 0, worldX, worldY, worldZ))
    val voxelY = snapVoxel(affineCoordinate(inverse, 1, worldX, worldY, worldZ))
    val voxelZ = snapVoxel(affineCoordinate(inverse, 2, worldX, worldY, worldZ))
    val extrapolate = outside == CoordinateMapOutside.BoundaryLinear && nx >= 2 && ny >= 2 && nz >= 2
    val x0 =
      if extrapolate then math.max(0, math.min(nx - 2, math.floor(voxelX).toInt))
      else math.floor(voxelX).toInt
    val y0 =
      if extrapolate then math.max(0, math.min(ny - 2, math.floor(voxelY).toInt))
      else math.floor(voxelY).toInt
    val z0 =
      if extrapolate then math.max(0, math.min(nz - 2, math.floor(voxelZ).toInt))
      else math.floor(voxelZ).toInt
    val fx = voxelX - x0.toDouble
    val fy = voxelY - y0.toDouble
    val fz = voxelZ - z0.toDouble
    var sumX = 0.0
    var sumY = 0.0
    var sumZ = 0.0
    val finiteVoxel = voxelX.isFinite && voxelY.isFinite && voxelZ.isFinite
    sampler.insideSupport =
      finiteVoxel &&
        voxelX >= 0.0 && voxelX <= nx.toDouble - 1.0 &&
        voxelY >= 0.0 && voxelY <= ny.toDouble - 1.0 &&
        voxelZ >= 0.0 && voxelZ <= nz.toDouble - 1.0
    var ok = finiteVoxel
    var dz = 0
    while dz <= 1 && ok do
      val wz = if dz == 0 then 1.0 - fz else fz
      val z = z0 + dz
      var dy = 0
      while dy <= 1 && ok do
        val wy = if dy == 0 then 1.0 - fy else fy
        val y = y0 + dy
        var dx = 0
        while dx <= 1 && ok do
          val wx = if dx == 0 then 1.0 - fx else fx
          val weight = wx * wy * wz
          if weight != 0.0 then
            val x = x0 + dx
            if !inBounds(x, y, z, nx, ny, nz) then ok = false
            else
              val index = x + y * nx + z * nx * ny
              if !validity.contains(index) then ok = false
              else
                val valueX = fieldValue(field, x, y, z, 0, ny, nz)
                val valueY = fieldValue(field, x, y, z, 1, ny, nz)
                val valueZ = fieldValue(field, x, y, z, 2, ny, nz)
                if !valueX.isFinite || !valueY.isFinite || !valueZ.isFinite then ok = false
                else
                  sumX += weight * valueX
                  sumY += weight * valueY
                  sumZ += weight * valueZ
          dx += 1
        dy += 1
      dz += 1
    sampler.x = sumX
    sampler.y = sumY
    sampler.z = sumZ
    sampler.valid = ok && sumX.isFinite && sumY.isFinite && sumZ.isFinite

  private def sampleMutableVectorAtWorldInto(
      field: MutableCanonicalArray[Double, Rank[1]],
      nx: Int,
      ny: Int,
      nz: Int,
      validity: Array[Boolean],
      sampler: DenseFieldSampler,
      worldX: Double,
      worldY: Double,
      worldZ: Double,
      outside: CoordinateMapOutside
  ): Unit =
    val inverse = sampler.inverse
    val voxelX = snapVoxel(affineCoordinate(inverse, 0, worldX, worldY, worldZ))
    val voxelY = snapVoxel(affineCoordinate(inverse, 1, worldX, worldY, worldZ))
    val voxelZ = snapVoxel(affineCoordinate(inverse, 2, worldX, worldY, worldZ))
    val extrapolate = outside == CoordinateMapOutside.BoundaryLinear && nx >= 2 && ny >= 2 && nz >= 2
    val x0 =
      if extrapolate then math.max(0, math.min(nx - 2, math.floor(voxelX).toInt))
      else math.floor(voxelX).toInt
    val y0 =
      if extrapolate then math.max(0, math.min(ny - 2, math.floor(voxelY).toInt))
      else math.floor(voxelY).toInt
    val z0 =
      if extrapolate then math.max(0, math.min(nz - 2, math.floor(voxelZ).toInt))
      else math.floor(voxelZ).toInt
    val fx = voxelX - x0.toDouble
    val fy = voxelY - y0.toDouble
    val fz = voxelZ - z0.toDouble
    var sumX = 0.0
    var sumY = 0.0
    var sumZ = 0.0
    val finiteVoxel = voxelX.isFinite && voxelY.isFinite && voxelZ.isFinite
    sampler.insideSupport =
      finiteVoxel &&
        voxelX >= 0.0 && voxelX <= nx.toDouble - 1.0 &&
        voxelY >= 0.0 && voxelY <= ny.toDouble - 1.0 &&
        voxelZ >= 0.0 && voxelZ <= nz.toDouble - 1.0
    var ok = finiteVoxel
    var dz = 0
    while dz <= 1 && ok do
      val wz = if dz == 0 then 1.0 - fz else fz
      val z = z0 + dz
      var dy = 0
      while dy <= 1 && ok do
        val wy = if dy == 0 then 1.0 - fy else fy
        val y = y0 + dy
        var dx = 0
        while dx <= 1 && ok do
          val wx = if dx == 0 then 1.0 - fx else fx
          val weight = wx * wy * wz
          if weight != 0.0 then
            val x = x0 + dx
            if !inBounds(x, y, z, nx, ny, nz) then ok = false
            else
              val index = x + y * nx + z * nx * ny
              if !validity(index) then ok = false
              else
                val storageBase = 3 * (z + nz * (y + ny * x))
                val valueX = field(storageBase)
                val valueY = field(storageBase + 1)
                val valueZ = field(storageBase + 2)
                if !valueX.isFinite || !valueY.isFinite || !valueZ.isFinite then ok = false
                else
                  sumX += weight * valueX
                  sumY += weight * valueY
                  sumZ += weight * valueZ
          dx += 1
        dy += 1
      dz += 1
    sampler.x = sumX
    sampler.y = sumY
    sampler.z = sumZ
    sampler.valid = ok && sumX.isFinite && sumY.isFinite && sumZ.isFinite

  private def convolveAxis(
      source: Array[Double],
      destination: Array[Double],
      dims: SpatialDims,
      weights: Array[Double],
      axis: Int
  ): Unit =
    val nx = dims.x
    val ny = dims.y
    val nz = dims.z
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
            if inBounds(xx, yy, zz, nx, ny, nz) then
              sum += weights(offset + radius) * source(xx + yy * nx + zz * nx * ny)
            offset += 1
          destination(x + y * nx + z * nx * ny) = sum
          x += 1
        y += 1
      z += 1

  private def samplePartialLinear(
      source: Array[Double],
      dims: SpatialDims,
      x: Double,
      y: Double,
      z: Double
  ): Double =
    if !x.isFinite || !y.isFinite || !z.isFinite then Double.NaN
    else
      val sx = snapVoxel(x)
      val sy = snapVoxel(y)
      val sz = snapVoxel(z)
      val x0 = math.floor(sx).toInt
      val y0 = math.floor(sy).toInt
      val z0 = math.floor(sz).toInt
      val fx = sx - x0.toDouble
      val fy = sy - y0.toDouble
      val fz = sz - z0.toDouble
      var sum = 0.0
      var dz = 0
      while dz <= 1 do
        val wz = if dz == 0 then 1.0 - fz else fz
        val zz = z0 + dz
        var dy = 0
        while dy <= 1 do
          val wy = if dy == 0 then 1.0 - fy else fy
          val yy = y0 + dy
          var dx = 0
          while dx <= 1 do
            val wx = if dx == 0 then 1.0 - fx else fx
            val xx = x0 + dx
            val weight = wx * wy * wz
            if weight != 0.0 && inBounds(xx, yy, zz, dims.x, dims.y, dims.z) then
              sum += weight * source(xx + yy * dims.x + zz * dims.x * dims.y)
            dx += 1
          dy += 1
        dz += 1
      sum

  private inline def affineCoordinate(
      matrix: DMat,
      row: Int,
      x: Double,
      y: Double,
      z: Double
  ): Double =
    matrix(row, 3) + matrix(row, 0) * x + matrix(row, 1) * y + matrix(row, 2) * z

  private inline def gridAffineCoordinate(
      grid: GridSpec,
      row: Int,
      x: Double,
      y: Double,
      z: Double
  ): Double =
    grid.affineElement(row, 3) +
      grid.affineElement(row, 0) * x +
      grid.affineElement(row, 1) * y +
      grid.affineElement(row, 2) * z

  private inline def fieldValue(
      field: DenseVectorField,
      x: Int,
      y: Int,
      z: Int,
      component: Int,
      ny: Int,
      nz: Int
  ): Double =
    field.flatValue(component + 3 * (z + nz * (y + ny * x)))

  private inline def snapVoxel(value: Double): Double =
    val nearest = math.rint(value)
    if math.abs(value - nearest) <= 1e-10 then nearest else value

  private inline def inBounds(
      x: Int,
      y: Int,
      z: Int,
      nx: Int,
      ny: Int,
      nz: Int
  ): Boolean =
    x >= 0 && x < nx && y >= 0 && y < ny && z >= 0 && z < nz
