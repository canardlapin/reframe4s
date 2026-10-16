package reframe4s.halfflow

import gale.linalg.DMat
import gale.linalg.{DVec, DoubleLinearOperator, MutableDVec, MutableVec}
import gale.solvers.{IterativeSolvers, Preconditioner, SolverConfig, ToleranceMode}
import reframe4s.halfflow.internal.*

opaque type SmoothLengthMm = Double

object SmoothLengthMm:
  def from(value: Double): Either[RegistrationError, SmoothLengthMm] =
    if value.isFinite && value >= 0.0 then Right(value)
    else Left(RegistrationError.InvalidFlowParameter("smooth length mm", value))

  def apply(value: Double): SmoothLengthMm =
    from(value).fold(error => throw new IllegalArgumentException(error.message), identity)

  extension (value: SmoothLengthMm)
    inline def toDouble: Double = value

final case class SobolevConfig private (
    length: SmoothLengthMm,
    power: Int,
    relativeTolerance: Double,
    maximumIterations: Int,
    maximumDisplacementMm: Double,
    maximumStrain: Double
)

object SobolevConfig:
  def make(
      length: SmoothLengthMm,
      power: Int = 2,
      relativeTolerance: Double = 1e-6,
      maximumIterations: Int = 80,
      maximumDisplacementMm: Double = 2.0,
      maximumStrain: Double = 0.35
  ): Either[RegistrationError, SobolevConfig] =
    if power <= 0 then Left(RegistrationError.InvalidConfiguration("Sobolev power"))
    else if !relativeTolerance.isFinite || relativeTolerance <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("Sobolev relative tolerance"))
    else if maximumIterations <= 0 then
      Left(RegistrationError.InvalidConfiguration("Sobolev maximum iterations"))
    else if !maximumDisplacementMm.isFinite || maximumDisplacementMm <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("Sobolev displacement cap"))
    else if !maximumStrain.isFinite || maximumStrain <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("Sobolev strain cap"))
    else
      Right(
        new SobolevConfig(
          length,
          power,
          relativeTolerance,
          maximumIterations,
          maximumDisplacementMm,
          maximumStrain
        )
      )

final case class SobolevDiagnostics(
    power: Int,
    linearSolves: Int,
    totalIterations: Int,
    maximumRelativeResidual: Double,
    activeVoxels: Int,
    maximumNormBeforeCapMm: Double,
    maximumNormAfterCapMm: Double,
    maximumStrainBeforeCap: Double,
    maximumStrainAfterCap: Double,
    displacementScale: Double,
    strainScale: Double
)

final case class SobolevResult[A](
    velocity: Velocity[A],
    validity: FieldValidity,
    diagnostics: SobolevDiagnostics
)

final class SobolevBuffer[A] private (
    val frame: RegistrationFrame[A],
    private[halfflow] val values: Array[Double],
    private[halfflow] val valid: Array[Boolean],
    val ownedVelocityBuffers: Int,
    val ownedValidityBuffers: Int
)

object SobolevBuffer:
  def apply[A](frame: RegistrationFrame[A]): SobolevBuffer[A] =
    new SobolevBuffer(
      frame,
      PrimitiveBuffers.ofSize[Double](frame.grid.nVoxels * 3),
      PrimitiveBuffers.ofSize[Boolean](frame.grid.nVoxels),
      ownedVelocityBuffers = 1,
      ownedValidityBuffers = 1
    )

  private[halfflow] def reusing[A](source: LocalLmBuffer[A]): SobolevBuffer[A] =
    new SobolevBuffer(
      source.frame,
      source.step,
      source.valid,
      ownedVelocityBuffers = 0,
      ownedValidityBuffers = 0
    )

final class SobolevWorkspace[A] private (
    val frame: RegistrationFrame[A],
    private[halfflow] val rhs: MutableDVec,
    private[halfflow] val inverseDiagonal: Array[Double],
    private[halfflow] val inverseAffine: DMat,
    val ownedOperatorScalarBuffers: Int
):
  val ownedGaleScalarBuffers: Int = 1

object SobolevWorkspace:
  def apply[A](frame: RegistrationFrame[A]): SobolevWorkspace[A] =
    make(frame, PrimitiveBuffers.ofSize[Double](frame.grid.nVoxels), ownedOperatorScalarBuffers = 1)

  private def make[A](
      frame: RegistrationFrame[A],
      inverseDiagonal: Array[Double],
      ownedOperatorScalarBuffers: Int
  ): SobolevWorkspace[A] =
    val inverse = frame.grid.inverseAffine
    val rhs = MutableDVec.zeros(frame.grid.nVoxels)
    new SobolevWorkspace(
      frame,
      rhs,
      inverseDiagonal,
      inverse,
      ownedOperatorScalarBuffers
    )

private[halfflow] final class MaskedHelmholtzOperator(
    val grid: GridSpec,
    val active: Array[Boolean],
    val lengthMm: Double
) extends DoubleLinearOperator:
  private val nx = grid.shape(0)
  private val ny = grid.shape(1)
  private val nz = grid.shape(2)
  private val plane = nx * ny
  private val spacing = grid.spacing
  private val ax = lengthMm * lengthMm / (spacing(0) * spacing(0))
  private val ay = lengthMm * lengthMm / (spacing(1) * spacing(1))
  private val az = lengthMm * lengthMm / (spacing(2) * spacing(2))
  override val rows: Int = grid.nVoxels
  override val cols: Int = rows

  override def applyTo(input: DVec, output: MutableDVec): Unit =
    require(input.length == cols && output.length == rows, "Helmholtz vector length mismatch")
    var z = 0
    while z < nz do
      var y = 0
      while y < ny do
        var x = 0
        while x < nx do
          val index = x + nx * y + plane * z
          if !active(index) then output(index) = input(index)
          else
            val center = input(index)
            var value = center
            if x > 0 && active(index - 1) then value += ax * (center - input(index - 1))
            if x + 1 < nx && active(index + 1) then value += ax * (center - input(index + 1))
            if y > 0 && active(index - nx) then value += ay * (center - input(index - nx))
            if y + 1 < ny && active(index + nx) then value += ay * (center - input(index + nx))
            if z > 0 && active(index - plane) then value += az * (center - input(index - plane))
            if z + 1 < nz && active(index + plane) then value += az * (center - input(index + plane))
            output(index) = value
          x += 1
        y += 1
      z += 1

  def prepareInverseDiagonal(destination: Array[Double]): Unit =
    require(destination.length == rows, "Helmholtz diagonal length mismatch")
    var z = 0
    while z < nz do
      var y = 0
      while y < ny do
        var x = 0
        while x < nx do
          val index = x + nx * y + plane * z
          var diagonal = 1.0
          if active(index) then
            if x > 0 && active(index - 1) then diagonal += ax
            if x + 1 < nx && active(index + 1) then diagonal += ax
            if y > 0 && active(index - nx) then diagonal += ay
            if y + 1 < ny && active(index + nx) then diagonal += ay
            if z > 0 && active(index - plane) then diagonal += az
            if z + 1 < nz && active(index + plane) then diagonal += az
          destination(index) = 1.0 / diagonal
          x += 1
        y += 1
      z += 1

object SobolevShaper:
  def shape[A](
      raw: Velocity[A],
      validity: FieldValidity,
      config: SobolevConfig
  ): Either[RegistrationError, SobolevResult[A]] =
    shapeWith(raw, validity, config, SobolevWorkspace(raw.frame))

  def shapeWith[A](
      raw: Velocity[A],
      validity: FieldValidity,
      config: SobolevConfig,
      workspace: SobolevWorkspace[A]
  ): Either[RegistrationError, SobolevResult[A]] =
    shapeInto(raw, validity, config, workspace, SobolevBuffer(raw.frame))

  /** Shapes into caller-owned output. The returned velocity borrows `destination`. */
  def shapeInto[A](
      raw: Velocity[A],
      validity: FieldValidity,
      config: SobolevConfig,
      workspace: SobolevWorkspace[A],
      destination: SobolevBuffer[A]
  ): Either[RegistrationError, SobolevResult[A]] =
    val frame = raw.frame
    val grid = frame.grid
    if workspace.frame.domain != frame.domain then
      Left(RegistrationError.FrameMismatch("Sobolev workspace", frame.domain, workspace.frame.domain))
    else if workspace.frame.grid != grid then Left(RegistrationError.GridMismatch("Sobolev workspace"))
    else if destination.frame.domain != frame.domain then
      Left(RegistrationError.FrameMismatch("Sobolev destination", frame.domain, destination.frame.domain))
    else if destination.frame.grid != grid then Left(RegistrationError.GridMismatch("Sobolev destination"))
    else if !validitySizeMatches(validity, grid.nVoxels) then
      Left(RegistrationError.InvalidField("Sobolev validity"))
    else compute(raw, validity, config, workspace, destination)

  private def compute[A](
      raw: Velocity[A],
      validity: FieldValidity,
      config: SobolevConfig,
      workspace: SobolevWorkspace[A],
      destination: SobolevBuffer[A]
  ): Either[RegistrationError, SobolevResult[A]] =
    val grid = raw.frame.grid
    val n = grid.nVoxels
    var activeCount = 0
    var index = 0
    while index < n do
      val active = validityAt(validity, index)
      destination.valid(index) = active
      if active then activeCount += 1
      index += 1
    if activeCount == 0 then Left(RegistrationError.InsufficientSupport("Sobolev", 0, 1))
    else
      val operator = new MaskedHelmholtzOperator(grid, destination.valid, config.length.toDouble)
      operator.prepareInverseDiagonal(workspace.inverseDiagonal)
      val preconditioner = new Preconditioner:
        override def solve(residual: DVec, into: MutableVec[Double]): Unit =
          var index = 0
          while index < workspace.inverseDiagonal.length do
            into(index) = workspace.inverseDiagonal(index) * residual(index)
            index += 1
      val solverConfig = SolverConfig(config.relativeTolerance, config.maximumIterations)
      var totalIterations = 0
      var maximumRelativeResidual = 0.0
      var component = 0
      var failure: Option[RegistrationError] = None
      while component < 3 && failure.isEmpty do
        loadComponent(raw.field, component, destination.valid, workspace.rhs)
        var pass = 0
        var currentRhs = workspace.rhs.toVec
        var solution = currentRhs
        while pass < config.power && failure.isEmpty do
          val rhsNorm = currentRhs.norm2
          val result = IterativeSolvers.cg(
            operator,
            currentRhs,
            solverConfig,
            preconditioner,
            initial = Some(currentRhs),
            toleranceMode = ToleranceMode.RelativeToRhs
          )
          solution = result.x
          val relative = if rhsNorm == 0.0 then 0.0 else result.residual / rhsNorm
          totalIterations += result.iterations
          maximumRelativeResidual = math.max(maximumRelativeResidual, relative)
          if !result.converged || !relative.isFinite || relative > config.relativeTolerance * 1.01 then
            failure = Some(
              RegistrationError.SmoothingDidNotConverge(
                component,
                pass,
                result.iterations,
                relative
              )
            )
          else if pass + 1 < config.power then currentRhs = solution
          pass += 1
        if failure.isEmpty then copySolution(solution, component * n, destination.values)
        component += 1
      failure match
        case Some(error) => Left(error)
        case None =>
          val beforeNorm = maximumNorm(destination.values, destination.valid, n)
          val displacementScale = math.min(1.0, config.maximumDisplacementMm / math.max(beforeNorm, 1e-300))
          if displacementScale < 1.0 then scaleActive(destination.values, destination.valid, displacementScale, n)
          val beforeStrain = maximumGradient(destination.values, destination.valid, grid, workspace.inverseAffine)
          val strainScale = math.min(1.0, config.maximumStrain / math.max(beforeStrain, 1e-300))
          if strainScale < 1.0 then scaleActive(destination.values, destination.valid, strainScale, n)
          val afterNorm = maximumNorm(destination.values, destination.valid, n)
          val afterStrain = maximumGradient(destination.values, destination.valid, grid, workspace.inverseAffine)
          val field =
            DenseVectorField.fromLegacyPlanar(
              grid,
              destination.values,
              DenseVectorFieldKind.Displacement
            )
          Velocity.make(raw.frame, field).map: velocity =>
            SobolevResult(
              velocity,
              FieldValidity.copyMask(destination.valid),
              SobolevDiagnostics(
                config.power,
                config.power * 3,
                totalIterations,
                maximumRelativeResidual,
                activeCount,
                beforeNorm,
                afterNorm,
                beforeStrain,
                afterStrain,
                displacementScale,
                strainScale
              )
            )

  private def loadComponent(
      source: DenseVectorField,
      component: Int,
      valid: Array[Boolean],
      destination: MutableDVec
  ): Unit =
    var index = 0
    while index < valid.length do
      destination(index) =
        if valid(index) then source.linearComponent(index, component)
        else 0.0
      index += 1

  private def copySolution(source: DVec, offset: Int, destination: Array[Double]): Unit =
    var index = 0
    while index < source.length do
      destination(offset + index) = source(index)
      index += 1

  private def scaleActive(values: Array[Double], valid: Array[Boolean], scale: Double, n: Int): Unit =
    var index = 0
    while index < n do
      if valid(index) then
        values(index) *= scale
        values(index + n) *= scale
        values(index + 2 * n) *= scale
      else
        values(index) = 0.0
        values(index + n) = 0.0
        values(index + 2 * n) = 0.0
      index += 1

  private def maximumNorm(values: Array[Double], valid: Array[Boolean], n: Int): Double =
    var maximum = 0.0
    var index = 0
    while index < n do
      if valid(index) then
        val x = values(index)
        val y = values(index + n)
        val z = values(index + 2 * n)
        maximum = math.max(maximum, math.sqrt(x * x + y * y + z * z))
      index += 1
    maximum

  private def maximumGradient(
      values: Array[Double],
      valid: Array[Boolean],
      grid: GridSpec,
      inverse: DMat
  ): Double =
    val nx = grid.shape(0)
    val ny = grid.shape(1)
    val nz = grid.shape(2)
    val plane = nx * ny
    val n = grid.nVoxels
    var maximum = 0.0
    var z = 1
    while z < nz - 1 do
      var y = 1
      while y < ny - 1 do
        var x = 1
        while x < nx - 1 do
          val index = x + nx * y + plane * z
          val stencilValid =
            valid(index) && valid(index - 1) && valid(index + 1) &&
              valid(index - nx) && valid(index + nx) && valid(index - plane) && valid(index + plane)
          if stencilValid then
            var squared = 0.0
            var component = 0
            while component < 3 do
              val offset = component * n
              val dx = 0.5 * (values(offset + index + 1) - values(offset + index - 1))
              val dy = 0.5 * (values(offset + index + nx) - values(offset + index - nx))
              val dz = 0.5 * (values(offset + index + plane) - values(offset + index - plane))
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

  private def validitySizeMatches(validity: FieldValidity, n: Int): Boolean =
    validity match
      case FieldValidity.All => true
      case FieldValidity.Mask(values) => values.size == n

  private def validityAt(validity: FieldValidity, index: Int): Boolean =
    validity match
      case FieldValidity.All => true
      case FieldValidity.Mask(values) => values(index)
