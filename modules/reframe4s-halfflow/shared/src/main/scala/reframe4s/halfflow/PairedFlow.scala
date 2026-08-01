package reframe4s.halfflow

import ravel.MutableNDArray as MutableRavelArray
import ravel.Rank
import ravel.Shape
import reframe4s.halfflow.internal.*

final case class Velocity[A] private (
    frame: Frame[A],
    field: DenseVectorField
)

object Velocity:
  def make[A](frame: Frame[A], field: DenseVectorField): Either[RegistrationError, Velocity[A]] =
    if field.grid != frame.grid then Left(RegistrationError.GridMismatch("velocity"))
    else if field.kind != DenseVectorFieldKind.Displacement then
      Left(RegistrationError.InvalidField("velocity kind"))
    else if !allFinite(field) then Left(RegistrationError.InvalidField("velocity"))
    else Right(new Velocity(frame, field))

  private def allFinite(field: DenseVectorField): Boolean =
    var finite = true
    var index = 0
    while index < field.grid.nVoxels && finite do
      var component = 0
      while component < 3 && finite do
        finite = field.linearComponent(index, component).isFinite
        component += 1
      index += 1
    finite

final case class FlowConfig(
    maximumInitialDisplacementMm: Double = 0.4,
    maximumInitialGradient: Double = 0.2,
    minimumSquaringDepth: Int = 0,
    maximumSquaringDepth: Int = 12
):
  require(maximumInitialDisplacementMm.isFinite && maximumInitialDisplacementMm > 0.0)
  require(maximumInitialGradient.isFinite && maximumInitialGradient > 0.0)
  require(minimumSquaringDepth >= 0 && maximumSquaringDepth >= minimumSquaringDepth)

final case class FlowDiagnostics(
    squaringDepth: Int,
    compositionCalls: Int,
    maximumVelocityNormMm: Double,
    maximumVelocityGradientBound: Double,
    initialDisplacementBoundMm: Double,
    initialGradientBound: Double,
    plusValid: Int,
    minusValid: Int,
    voxels: Int
)

final case class PairedFlow[A](
    pair: InversePair[A, A],
    diagnostics: FlowDiagnostics
)

final class PairedFlowWorkspace[A] private[halfflow] (
    val frame: Frame[A],
    private[halfflow] val plus: PairedFlowBuffers,
    private[halfflow] val minus: PairedFlowBuffers,
    private[halfflow] val inverseAffine: DMat
):
  val ownedCoordinateBuffers: Int = 4
  val ownedValidityBuffers: Int = 4

object PairedFlowWorkspace:
  def apply[A](frame: Frame[A]): PairedFlowWorkspace[A] =
    val inverse = DMat.invert(frame.grid.affine).fold(
      reason => throw new IllegalArgumentException(s"velocity grid affine is singular: $reason"),
      identity
    )
    new PairedFlowWorkspace(frame, buffers(frame.grid), buffers(frame.grid), inverse)

  private def buffers(grid: GridSpec): PairedFlowBuffers =
    val shape = Shape(grid.nVoxels * 3)
    PairedFlowBuffers(
      MutableRavelArray.zeros[Double, Rank[1]](shape),
      PrimitiveBuffers.ofSize[Boolean](grid.nVoxels),
      MutableRavelArray.zeros[Double, Rank[1]](shape),
      PrimitiveBuffers.ofSize[Boolean](grid.nVoxels),
      DenseFieldSampler(grid)
    )

private[halfflow] final case class PairedFlowBuffers(
    first: MutableRavelArray[Double, Rank[1]],
    firstValid: Array[Boolean],
    second: MutableRavelArray[Double, Rank[1]],
    secondValid: Array[Boolean],
    sampler: DenseFieldSampler
)

object PairedScalingAndSquaring:
  def expHalfPair[A](
      velocity: Velocity[A],
      config: FlowConfig = FlowConfig()
  ): Either[RegistrationError, PairedFlow[A]] =
    expHalfPairWith(velocity, PairedFlowWorkspace(velocity.frame), config)

  /** Reuses canonical Ravel ping-pong storage while returning independently
    * owned immutable maps.
    */
  def expHalfPairWith[A](
      velocity: Velocity[A],
      workspace: PairedFlowWorkspace[A],
      config: FlowConfig = FlowConfig()
  ): Either[RegistrationError, PairedFlow[A]] =
    if velocity.frame.domain != workspace.frame.domain then
      Left(
        RegistrationError.FrameMismatch(
          "paired flow workspace",
          velocity.frame.domain,
          workspace.frame.domain
        )
      )
    else if velocity.frame.grid != workspace.frame.grid then
      Left(RegistrationError.GridMismatch("paired flow workspace"))
    else compute(velocity, workspace, config)

  private def compute[A](
      velocity: Velocity[A],
      workspace: PairedFlowWorkspace[A],
      config: FlowConfig
  ): Either[RegistrationError, PairedFlow[A]] =
    val grid = velocity.frame.grid
    val maximumNorm = maximumVectorNorm(velocity.field)
    val maximumGradient = maximumGradientBound(velocity.field, workspace.inverseAffine)
    val displacementDepth = requiredDepth(0.5 * maximumNorm, config.maximumInitialDisplacementMm)
    val gradientDepth = requiredDepth(0.5 * maximumGradient, config.maximumInitialGradient)
    val depth = math.max(config.minimumSquaringDepth, math.max(displacementDepth, gradientDepth))
    if depth > config.maximumSquaringDepth then
      Left(RegistrationError.SquaringDepthExceeded(depth, config.maximumSquaringDepth))
    else
      val scale = 1.0 / math.pow(2.0, depth.toDouble + 1.0)
      initialMapInto(grid, velocity.field, scale, workspace.plus)
      initialMapInto(grid, velocity.field, -scale, workspace.minus)
      val squaredPlus = square(grid, workspace.plus, depth)
      val squaredMinus = square(grid, workspace.minus, depth)
      val plusPull = DensePull.unsafe(
        velocity.frame,
        velocity.frame,
        squaredPlus.field,
        FieldValidity.copyMask(squaredPlus.valid)
      )
      val minusPull = DensePull.unsafe(
        velocity.frame,
        velocity.frame,
        squaredMinus.field,
        FieldValidity.copyMask(squaredMinus.valid)
      )
      val pair = InversePair.unsafe(plusPull, minusPull)
      Right(
        PairedFlow(
          pair,
          FlowDiagnostics(
            depth,
            depth * 2,
            maximumNorm,
            maximumGradient,
            0.5 * maximumNorm / math.pow(2.0, depth.toDouble),
            0.5 * maximumGradient / math.pow(2.0, depth.toDouble),
            countValid(squaredPlus.valid),
            countValid(squaredMinus.valid),
            grid.nVoxels
          )
        )
      )

  private def initialMapInto(
      grid: GridSpec,
      velocity: DenseVectorField,
      scale: Double,
      buffers: PairedFlowBuffers
  ): Unit =
    val values = buffers.first
    val nx = grid.extentX
    val ny = grid.extentY
    val nz = grid.extentZ
    var z = 0
    while z < nz do
      var y = 0
      while y < ny do
        var x = 0
        while x < nx do
          val voxelBase = 3 * (z + nz * (y + ny * x))
          var component = 0
          while component < 3 do
            val identity =
              grid.affineElement(component, 0) * x.toDouble +
                grid.affineElement(component, 1) * y.toDouble +
                grid.affineElement(component, 2) * z.toDouble +
                grid.affineElement(component, 3)
            values(voxelBase + component) =
              identity + scale * velocity.flatValue(voxelBase + component)
            component += 1
          x += 1
        y += 1
      z += 1
    var index = 0
    while index < grid.nVoxels do
      buffers.firstValid(index) = true
      index += 1

  private def square(
      grid: GridSpec,
      buffers: PairedFlowBuffers,
      depth: Int
  ): DensePullResult =
    var currentValues = buffers.first
    var currentValid = buffers.firstValid
    var destinationValues = buffers.second
    var destinationValid = buffers.secondValid
    var iteration = 0
    while iteration < depth do
      HalfFlowKernels.composeSelfPullInto(
        grid,
        currentValues,
        currentValid,
        destinationValues,
        destinationValid,
        buffers.sampler,
        CoordinateMapOutside.Identity
      )
      val previousValues = currentValues
      val previousValid = currentValid
      currentValues = destinationValues
      currentValid = destinationValid
      destinationValues = previousValues
      destinationValid = previousValid
      iteration += 1
    val immutableValues =
      currentValues
        .freezeCopy()
        .reshapeView(
          Shape(grid.extentX, grid.extentY, grid.extentZ, 3)
        )
    val immutableValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    var index = 0
    while index < grid.nVoxels do
      immutableValid(index) = currentValid(index)
      index += 1
    DensePullResult(
      DenseVectorField(grid, immutableValues, DenseVectorFieldKind.SourceCoordinates),
      immutableValid
    )

  private def requiredDepth(value: Double, limit: Double): Int =
    var depth = 0
    var scaled = value
    while scaled > limit * (1.0 + 1e-12) do
      scaled *= 0.5
      depth += 1
    depth

  private def maximumVectorNorm(field: DenseVectorField): Double =
    var maximum = 0.0
    var index = 0
    while index < field.grid.nVoxels do
      val x = field.linearComponent(index, 0)
      val y = field.linearComponent(index, 1)
      val z = field.linearComponent(index, 2)
      maximum = math.max(maximum, math.sqrt(x * x + y * y + z * z))
      index += 1
    maximum

  /** Frobenius norm is a conservative upper bound on the local operator norm. */
  private def maximumGradientBound(field: DenseVectorField, inverse: DMat): Double =
    val grid = field.grid
    if grid.shape.x < 3 || grid.shape.y < 3 || grid.shape.z < 3 then 0.0
    else
      val nx = grid.shape.x
      val ny = grid.shape.y
      val nz = grid.shape.z
      var maximum = 0.0
      var z = 1
      while z < nz - 1 do
        var y = 1
        while y < ny - 1 do
          var x = 1
          while x < nx - 1 do
            var squared = 0.0
            var component = 0
            while component < 3 do
              val dx = 0.5 * (field(x + 1, y, z, component) - field(x - 1, y, z, component))
              val dy = 0.5 * (field(x, y + 1, z, component) - field(x, y - 1, z, component))
              val dz = 0.5 * (field(x, y, z + 1, component) - field(x, y, z - 1, component))
              var axis = 0
              while axis < 3 do
                val derivative = dx * inverse(0, axis) + dy * inverse(1, axis) + dz * inverse(2, axis)
                squared += derivative * derivative
                axis += 1
              component += 1
            maximum = math.max(maximum, math.sqrt(squared))
            x += 1
          y += 1
        z += 1
      maximum

  private def countValid(values: Array[Boolean]): Int =
    var count = 0
    var index = 0
    while index < values.length do
      if values(index) then count += 1
      index += 1
    count
