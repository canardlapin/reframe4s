package reframe4s.halfflow

import gale.linalg.DMat
import reframe4s.halfflow.internal.*

/** Errors raised while projecting sparse BasinBridge observations. */
enum BasinBridgeProjectorError:
  case EmptyCorrespondences
  case InvalidSigma(value: Double)
  case InvalidMinimumGaussianWeight(value: Double)
  case InvalidCauchyScale(value: Double)
  case InvalidZeroResidualPrior(value: Double)
  case InvalidDenseSupportThreshold(value: Double)
  case InvalidDenseResidualTolerance(value: Double)
  case SingularGrid(reason: String)
  case InvalidDestination(expected: Int, actual: Int)
  case InvalidWorkspace
  case NonFiniteVoxelCoordinate(index: Int)
  case NonFiniteOutput(index: Int, component: Int)

  def message: String =
    this match
      case EmptyCorrespondences =>
        "BasinBridge projector requires at least one correspondence"
      case InvalidSigma(value) =>
        s"BasinBridge projector sigma must be finite and non-negative; got $value"
      case InvalidMinimumGaussianWeight(value) =>
        s"BasinBridge projector minimum Gaussian weight must be finite and positive; got $value"
      case InvalidCauchyScale(value) =>
        s"BasinBridge projector Cauchy scale must be finite and positive; got $value"
      case InvalidZeroResidualPrior(value) =>
        s"BasinBridge projector zero-residual prior must be finite and positive; got $value"
      case InvalidDenseSupportThreshold(value) =>
        s"BasinBridge projector dense-support threshold must be finite and non-negative; got $value"
      case InvalidDenseResidualTolerance(value) =>
        s"BasinBridge projector dense residual tolerance must be finite and non-negative; got $value"
      case SingularGrid(reason) =>
        s"BasinBridge projector grid is singular: $reason"
      case InvalidDestination(expected, actual) =>
        s"BasinBridge projector destination must contain at least $expected values; got $actual"
      case InvalidWorkspace =>
        "BasinBridge projector workspace belongs to a different grid"
      case NonFiniteVoxelCoordinate(index) =>
        s"BasinBridge correspondence midpoint $index maps to a non-finite voxel coordinate"
      case NonFiniteOutput(index, component) =>
        s"BasinBridge projector produced a non-finite output at voxel $index, component $component"

/** Accuracy controls for the topology-safe sparse-to-dense projector.
  *
  * The projector estimates one robust global translation, smooths only the
  * residual tangent field with the shared normalized Gaussian kernel, and
  * restores that translation at every output voxel. The dense-support guard
  * preserves small observed affine residuals at the finite-lattice boundary;
  * large residuals still pass through the robust smoothing path.
  */
final case class BasinBridgeProjectorConfig private (
    sigmaMm: Double,
    minimumGaussianWeight: Double,
    cauchyScaleMm: Double,
    zeroResidualPriorWeight: Double,
    denseSupportThreshold: Double,
    denseResidualToleranceMm: Double
)

object BasinBridgeProjectorConfig:
  def make(
      sigmaMm: Double = 2.0,
      minimumGaussianWeight: Double = 1e-8,
      cauchyScaleMm: Double = 4.0,
      zeroResidualPriorWeight: Double = 1e-3,
      denseSupportThreshold: Double = 0.999,
      denseResidualToleranceMm: Double = 0.25
  ): Either[BasinBridgeProjectorError, BasinBridgeProjectorConfig] =
    if !sigmaMm.isFinite || sigmaMm < 0.0 then
      Left(BasinBridgeProjectorError.InvalidSigma(sigmaMm))
    else if !minimumGaussianWeight.isFinite || minimumGaussianWeight <= 0.0 then
      Left(BasinBridgeProjectorError.InvalidMinimumGaussianWeight(minimumGaussianWeight))
    else if !cauchyScaleMm.isFinite || cauchyScaleMm <= 0.0 then
      Left(BasinBridgeProjectorError.InvalidCauchyScale(cauchyScaleMm))
    else if !zeroResidualPriorWeight.isFinite || zeroResidualPriorWeight <= 0.0 then
      Left(BasinBridgeProjectorError.InvalidZeroResidualPrior(zeroResidualPriorWeight))
    else if !denseSupportThreshold.isFinite || denseSupportThreshold < 0.0 then
      Left(BasinBridgeProjectorError.InvalidDenseSupportThreshold(denseSupportThreshold))
    else if !denseResidualToleranceMm.isFinite || denseResidualToleranceMm < 0.0 then
      Left(BasinBridgeProjectorError.InvalidDenseResidualTolerance(denseResidualToleranceMm))
    else
      Right(
        new BasinBridgeProjectorConfig(
          sigmaMm,
          minimumGaussianWeight,
          cauchyScaleMm,
          zeroResidualPriorWeight,
          denseSupportThreshold,
          denseResidualToleranceMm
        )
      )

  val default: BasinBridgeProjectorConfig =
    make().fold(error => throw new IllegalStateException(error.message), identity)

/** Caller-owned scratch for repeated BasinBridge projector calls on one grid. */
final class BasinBridgeProjectorWorkspace private (
    val grid: GridSpec,
    private[halfflow] val support: Array[Double],
    private[halfflow] val residualX: Array[Double],
    private[halfflow] val residualY: Array[Double],
    private[halfflow] val residualZ: Array[Double],
    private[halfflow] val smoothX: Array[Double],
    private[halfflow] val smoothY: Array[Double],
    private[halfflow] val smoothZ: Array[Double],
    private[halfflow] val smoothWeight: Array[Double],
    private[halfflow] val gaussian: GaussianWorkspace
):
  val ownedScalarBuffers: Int = 8

object BasinBridgeProjectorWorkspace:
  def apply(grid: GridSpec): BasinBridgeProjectorWorkspace =
    new BasinBridgeProjectorWorkspace(
      grid,
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      GaussianWorkspace(grid)
    )

/** Diagnostics from one sparse-to-dense projection. */
final case class BasinBridgeProjectionSummary(
    background: BasinBridgePoint,
    effectiveSeedWeight: Double,
    seedsInsideGrid: Int
)

/** One dense displacement field plus the robust global background estimate. */
final case class BasinBridgeProjection(
    field: DenseVectorField,
    summary: BasinBridgeProjectionSummary
)

/** Background-plus-residual BasinBridge projector. */
object BasinBridgeProjector:
  private final case class WeightedMean(point: BasinBridgePoint, totalWeight: Double)

  /** Allocation-owning convenience API. */
  def project(
      correspondences: Vector[BasinBridgeCorrespondence],
      grid: GridSpec,
      config: BasinBridgeProjectorConfig = BasinBridgeProjectorConfig.default
  ): Either[BasinBridgeProjectorError, BasinBridgeProjection] =
    val destination = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    val workspace = BasinBridgeProjectorWorkspace(grid)
    projectInto(correspondences, grid, config, destination, workspace).map: summary =>
      BasinBridgeProjection(
        DenseVectorField.fromLegacyPlanar(grid, destination, DenseVectorFieldKind.Displacement),
        summary
      )

  /** Allocation-controlled projection into planar x/y/z component buffers. */
  def projectInto(
      correspondences: Vector[BasinBridgeCorrespondence],
      grid: GridSpec,
      config: BasinBridgeProjectorConfig,
      destination: Array[Double],
      workspace: BasinBridgeProjectorWorkspace
  ): Either[BasinBridgeProjectorError, BasinBridgeProjectionSummary] =
    if correspondences.isEmpty then Left(BasinBridgeProjectorError.EmptyCorrespondences)
    else if destination.length < grid.nVoxels * 3 then
      Left(BasinBridgeProjectorError.InvalidDestination(grid.nVoxels * 3, destination.length))
    else if workspace.grid != grid then Left(BasinBridgeProjectorError.InvalidWorkspace)
    else
      val inverse = grid.inverseAffine
      val initial = weightedMean(correspondences, BasinBridgePoint.unsafe(0.0, 0.0, 0.0))
      val robust = robustMean(correspondences, initial.point, config.cauchyScaleMm)
      clearWorkspace(workspace, config.zeroResidualPriorWeight)

      var index = 0
      var seedsInsideGrid = 0
      var failure = Option.empty[BasinBridgeProjectorError]
      while index < correspondences.length && failure.isEmpty do
        val correspondence = correspondences(index)
        val tangentX = correspondence.moving.x - correspondence.fixed.x
        val tangentY = correspondence.moving.y - correspondence.fixed.y
        val tangentZ = correspondence.moving.z - correspondence.fixed.z
        val robustWeight =
          correspondence.confidence * cauchyWeight(
            tangentX,
            tangentY,
            tangentZ,
            initial.point,
            config.cauchyScaleMm
          )
        val midpointX = (correspondence.fixed.x + correspondence.moving.x) * 0.5
        val midpointY = (correspondence.fixed.y + correspondence.moving.y) * 0.5
        val midpointZ = (correspondence.fixed.z + correspondence.moving.z) * 0.5
        val residualX = tangentX - robust.point.x
        val residualY = tangentY - robust.point.y
        val residualZ = tangentZ - robust.point.z
        splat(
          index,
          midpointX,
          midpointY,
          midpointZ,
          residualX,
          residualY,
          residualZ,
          robustWeight,
          inverse,
          grid,
          workspace
        ) match
          case Left(error) => failure = Some(error)
          case Right(inside) => if inside then seedsInsideGrid += 1
        index += 1

      failure match
        case Some(error) => Left(error)
        case None =>
          normalizeSeedResiduals(workspace)
          Gaussian3D.normalizedInto(
            workspace.residualX,
            workspace.support,
            grid,
            config.sigmaMm,
            config.minimumGaussianWeight,
            workspace.smoothX,
            workspace.smoothWeight,
            workspace.gaussian,
            GaussianBoundary.Reflect
          )
          Gaussian3D.normalizedInto(
            workspace.residualY,
            workspace.support,
            grid,
            config.sigmaMm,
            config.minimumGaussianWeight,
            workspace.smoothY,
            workspace.smoothWeight,
            workspace.gaussian,
            GaussianBoundary.Reflect
          )
          Gaussian3D.normalizedInto(
            workspace.residualZ,
            workspace.support,
            grid,
            config.sigmaMm,
            config.minimumGaussianWeight,
            workspace.smoothZ,
            workspace.smoothWeight,
            workspace.gaussian,
            GaussianBoundary.Reflect
          )
          writeOutput(
            grid,
            robust.point,
            config,
            destination,
            workspace,
            failure
          ) match
            case Some(error) => Left(error)
            case None =>
              Right(
                BasinBridgeProjectionSummary(
                  robust.point,
                  robust.totalWeight,
                  seedsInsideGrid
                )
              )

  private def weightedMean(
      correspondences: Vector[BasinBridgeCorrespondence],
      fallback: BasinBridgePoint
  ): WeightedMean =
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var total = 0.0
    var index = 0
    while index < correspondences.length do
      val correspondence = correspondences(index)
      val weight = correspondence.confidence
      if weight > 0.0 then
        val tangentX = correspondence.moving.x - correspondence.fixed.x
        val tangentY = correspondence.moving.y - correspondence.fixed.y
        val tangentZ = correspondence.moving.z - correspondence.fixed.z
        x += weight * tangentX
        y += weight * tangentY
        z += weight * tangentZ
        total += weight
      index += 1
    if total > 0.0 && total.isFinite then
      WeightedMean(
        BasinBridgePoint.unsafe(x / total, y / total, z / total),
        total
      )
    else WeightedMean(fallback, 0.0)

  private def robustMean(
      correspondences: Vector[BasinBridgeCorrespondence],
      center: BasinBridgePoint,
      scaleMm: Double
  ): WeightedMean =
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var total = 0.0
    var index = 0
    while index < correspondences.length do
      val correspondence = correspondences(index)
      val tangentX = correspondence.moving.x - correspondence.fixed.x
      val tangentY = correspondence.moving.y - correspondence.fixed.y
      val tangentZ = correspondence.moving.z - correspondence.fixed.z
      val weight = correspondence.confidence * cauchyWeight(tangentX, tangentY, tangentZ, center, scaleMm)
      if weight > 0.0 then
        x += weight * tangentX
        y += weight * tangentY
        z += weight * tangentZ
        total += weight
      index += 1
    if total > 0.0 && total.isFinite then
      WeightedMean(
        BasinBridgePoint.unsafe(x / total, y / total, z / total),
        total
      )
    else WeightedMean(center, 0.0)

  private def cauchyWeight(
      tangentX: Double,
      tangentY: Double,
      tangentZ: Double,
      center: BasinBridgePoint,
      scaleMm: Double
  ): Double =
    val dx = (tangentX - center.x) / scaleMm
    val dy = (tangentY - center.y) / scaleMm
    val dz = (tangentZ - center.z) / scaleMm
    1.0 / (1.0 + dx * dx + dy * dy + dz * dz)

  private def clearWorkspace(
      workspace: BasinBridgeProjectorWorkspace,
      zeroResidualPriorWeight: Double
  ): Unit =
    var index = 0
    while index < workspace.grid.nVoxels do
      workspace.support(index) = zeroResidualPriorWeight
      workspace.residualX(index) = 0.0
      workspace.residualY(index) = 0.0
      workspace.residualZ(index) = 0.0
      index += 1

  private def splat(
      correspondenceIndex: Int,
      pointX: Double,
      pointY: Double,
      pointZ: Double,
      residualX: Double,
      residualY: Double,
      residualZ: Double,
      weight: Double,
      inverse: DMat,
      grid: GridSpec,
      workspace: BasinBridgeProjectorWorkspace
  ): Either[BasinBridgeProjectorError, Boolean] =
    if weight <= 0.0 then Right(false)
    else
      val voxelX =
        inverse(0, 0) * pointX + inverse(0, 1) * pointY + inverse(0, 2) * pointZ + inverse(0, 3)
      val voxelY =
        inverse(1, 0) * pointX + inverse(1, 1) * pointY + inverse(1, 2) * pointZ + inverse(1, 3)
      val voxelZ =
        inverse(2, 0) * pointX + inverse(2, 1) * pointY + inverse(2, 2) * pointZ + inverse(2, 3)
      if !voxelX.isFinite || !voxelY.isFinite || !voxelZ.isFinite then
        Left(BasinBridgeProjectorError.NonFiniteVoxelCoordinate(correspondenceIndex))
      else
        val x0 = math.floor(voxelX).toInt
        val y0 = math.floor(voxelY).toInt
        val z0 = math.floor(voxelZ).toInt
        val fx = voxelX - x0.toDouble
        val fy = voxelY - y0.toDouble
        val fz = voxelZ - z0.toDouble
        var normalization = 0.0
        var dx = 0
        while dx <= 1 do
          val x = x0 + dx
          if x >= 0 && x < grid.shape(0) then
            val wx = if dx == 0 then 1.0 - fx else fx
            var dy = 0
            while dy <= 1 do
              val y = y0 + dy
              if y >= 0 && y < grid.shape(1) then
                val wy = if dy == 0 then 1.0 - fy else fy
                var dz = 0
                while dz <= 1 do
                  val z = z0 + dz
                  if z >= 0 && z < grid.shape(2) then
                    val wz = if dz == 0 then 1.0 - fz else fz
                    normalization += wx * wy * wz
                  dz += 1
              dy += 1
          dx += 1
        if normalization <= 0.0 then Right(false)
        else
          dx = 0
          while dx <= 1 do
            val x = x0 + dx
            if x >= 0 && x < grid.shape(0) then
              val wx = if dx == 0 then 1.0 - fx else fx
              var dy = 0
              while dy <= 1 do
                val y = y0 + dy
                if y >= 0 && y < grid.shape(1) then
                  val wy = if dy == 0 then 1.0 - fy else fy
                  var dz = 0
                  while dz <= 1 do
                    val z = z0 + dz
                    if z >= 0 && z < grid.shape(2) then
                      val wz = if dz == 0 then 1.0 - fz else fz
                      val splatWeight = weight * wx * wy * wz / normalization
                      val index = x + grid.shape(0) * y + grid.shape(0) * grid.shape(1) * z
                      workspace.support(index) += splatWeight
                      workspace.residualX(index) += splatWeight * residualX
                      workspace.residualY(index) += splatWeight * residualY
                      workspace.residualZ(index) += splatWeight * residualZ
                    dz += 1
                dy += 1
            dx += 1
          Right(true)

  private def normalizeSeedResiduals(workspace: BasinBridgeProjectorWorkspace): Unit =
    var index = 0
    while index < workspace.grid.nVoxels do
      val support = workspace.support(index)
      workspace.residualX(index) /= support
      workspace.residualY(index) /= support
      workspace.residualZ(index) /= support
      index += 1

  private def writeOutput(
      grid: GridSpec,
      background: BasinBridgePoint,
      config: BasinBridgeProjectorConfig,
      destination: Array[Double],
      workspace: BasinBridgeProjectorWorkspace,
      failure: Option[BasinBridgeProjectorError]
  ): Option[BasinBridgeProjectorError] =
    var index = 0
    var error = failure
    while index < grid.nVoxels && error.isEmpty do
      val seedSupport = workspace.support(index) - config.zeroResidualPriorWeight
      val observedResidualX =
        if seedSupport > 0.0 then workspace.residualX(index) * workspace.support(index) / seedSupport
        else 0.0
      val observedResidualY =
        if seedSupport > 0.0 then workspace.residualY(index) * workspace.support(index) / seedSupport
        else 0.0
      val observedResidualZ =
        if seedSupport > 0.0 then workspace.residualZ(index) * workspace.support(index) / seedSupport
        else 0.0
      val residualNorm = math.sqrt(
        observedResidualX * observedResidualX +
          observedResidualY * observedResidualY +
          observedResidualZ * observedResidualZ
      )
      val useObservedResidual =
        seedSupport >= config.denseSupportThreshold &&
          residualNorm <= config.denseResidualToleranceMm
      val residualX = if useObservedResidual then observedResidualX else workspace.smoothX(index)
      val residualY = if useObservedResidual then observedResidualY else workspace.smoothY(index)
      val residualZ = if useObservedResidual then observedResidualZ else workspace.smoothZ(index)
      val x = background.x + residualX
      val y = background.y + residualY
      val z = background.z + residualZ
      if !x.isFinite then error = Some(BasinBridgeProjectorError.NonFiniteOutput(index, 0))
      else if !y.isFinite then error = Some(BasinBridgeProjectorError.NonFiniteOutput(index, 1))
      else if !z.isFinite then error = Some(BasinBridgeProjectorError.NonFiniteOutput(index, 2))
      else
        destination(index) = x
        destination(index + grid.nVoxels) = y
        destination(index + 2 * grid.nVoxels) = z
      index += 1
    error
