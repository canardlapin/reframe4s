package reframe4s.halfflow

import reframe4s.halfflow.internal.*

/** Mutable scalar buffers shared by the HalfFlow objective and BasinBridge. */
private[halfflow] final class CcWarpBuffer private (
    val values: Array[Double],
    val valid: Array[Boolean]
)

private[halfflow] object CcWarpBuffer:
  def apply(size: Int): CcWarpBuffer =
    new CcWarpBuffer(
      PrimitiveBuffers.ofSize[Double](size),
      PrimitiveBuffers.ofSize[Boolean](size)
    )

/** Shared image warping and frozen-support construction for true Neighborhood CC. */
private[halfflow] object HalfFlowCcSupport:
  def warpNativeInto[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      state: ForwardMidpoint[W, F, M],
      fixedDestination: CcWarpBuffer,
      movingDestination: CcWarpBuffer
  ): Unit =
    require(fixedDestination.values.length == state.work.grid.nVoxels)
    require(movingDestination.values.length == state.work.grid.nVoxels)
    HalfFlowKernels.pullScalarAffineInto(
      fixed.volume,
      state.fixed.residual.sourceCoordinates,
      state.fixed.affine.transform.matrix,
      fixedDestination.values,
      fixedDestination.valid,
      DenseFieldSampler(fixed.frame.grid),
      state.fixed.residual.validity,
      fixed.validity,
      0.0
    )
    HalfFlowKernels.pullScalarAffineInto(
      moving.volume,
      state.moving.residual.sourceCoordinates,
      state.moving.affine.transform.matrix,
      movingDestination.values,
      movingDestination.valid,
      DenseFieldSampler(moving.frame.grid),
      state.moving.residual.validity,
      moving.validity,
      0.0
    )

  def buildSupport(
      fixed: CcWarpBuffer,
      moving: CcWarpBuffer,
      grid: GridSpec,
      maximumStepMm: Double,
      supportSigmaMm: Double,
      source: Array[Double],
      destination: Array[Double],
      workspace: GaussianWorkspace
  ): Unit =
    require(source.length >= grid.nVoxels)
    require(destination.length >= grid.nVoxels)
    val minimumSpacingMm = grid.spacing.min
    val safeMargin = math.max(1, math.ceil(0.5 * maximumStepMm / minimumSpacingMm).toInt)
    val nx = grid.shape(0)
    val ny = grid.shape(1)
    val nz = grid.shape(2)
    var index = 0
    while index < grid.nVoxels do
      val x = index % nx
      val yz = index / nx
      val y = yz % ny
      val z = yz / ny
      var safe = true
      var dz = -safeMargin
      while dz <= safeMargin && safe do
        var dy = -safeMargin
        while dy <= safeMargin && safe do
          var dx = -safeMargin
          while dx <= safeMargin && safe do
            val sx = x + dx
            val sy = y + dy
            val sz = z + dz
            safe = sx >= 0 && sx < nx && sy >= 0 && sy < ny && sz >= 0 && sz < nz
            if safe then
              val sample = sx + nx * sy + nx * ny * sz
              safe = fixed.valid(sample) && moving.valid(sample)
            dx += 1
          dy += 1
        dz += 1
      source(index) = if safe then 1.0 else 0.0
      index += 1
    Gaussian3D.smoothInto(
      source,
      grid,
      supportSigmaMm,
      destination,
      workspace,
      GaussianBoundary.Zero
    )
    index = 0
    while index < grid.nVoxels do
      if source(index) == 0.0 then destination(index) = 0.0
      else destination(index) = math.max(0.0, math.min(1.0, destination(index)))
      index += 1
