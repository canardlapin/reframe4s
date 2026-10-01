package reframe4s.flashalign

import gale.linalg.DVec
import gale.linalg.DoubleLinearOperator
import gale.linalg.MutableDVec
import gale.linalg.MutableVec
import gale.solvers.CgWorkspace
import gale.solvers.IterativeSolvers
import gale.solvers.Preconditioner
import gale.solvers.SolverConfig
import gale.solvers.ToleranceMode

import scala.util.control.NonFatal

private[flashalign] trait ArraySymmetricOperator:
  def dimension: Int
  def apply(input: Array[Double], output: Array[Double]): Either[String, Unit]

private[flashalign] trait ArrayPreconditioner:
  def dimension: Int
  def solve(residual: Array[Double], output: Array[Double]): Either[String, Unit]
  def retainedBytes: Long

private[flashalign] final class PriorDataDiagonalPreconditioner private (
    private val inverseDiagonal: Array[Double]
) extends ArrayPreconditioner:
  val dimension: Int = inverseDiagonal.length
  val retainedBytes: Long = inverseDiagonal.length.toLong * 8L

  def solve(residual: Array[Double], output: Array[Double]): Either[String, Unit] =
    if residual.length != dimension || output.length != dimension then
      Left(s"diagonal preconditioner requires $dimension values")
    else
      var index = 0
      while index < dimension do
        output(index) = inverseDiagonal(index) * residual(index)
        index += 1
      Right(())

private[flashalign] object PriorDataDiagonalPreconditioner:
  def create(
      priorDiagonal: Vector[Double],
      dataDiagonal: Vector[Double],
      dampingDiagonal: Vector[Double]
  ): Either[GalePcgError, PriorDataDiagonalPreconditioner] =
    val size = priorDiagonal.length
    if dataDiagonal.length != size || dampingDiagonal.length != size then
      Left(GalePcgError.DimensionMismatch(size, math.max(dataDiagonal.length, dampingDiagonal.length)))
    else
      val inverse = new Array[Double](size)
      var index = 0
      while index < size do
        val prior = priorDiagonal(index)
        val data = dataDiagonal(index)
        val damping = dampingDiagonal(index)
        if !prior.isFinite || prior < 0.0 || !data.isFinite || data < 0.0 ||
            !damping.isFinite || damping < 0.0
        then return Left(GalePcgError.InvalidPreconditionerDiagonal(index, prior, data, damping))
        val total = prior + data + damping
        if !total.isFinite || total <= 0.0 then
          return Left(GalePcgError.InvalidPreconditionerDiagonal(index, prior, data, damping))
        inverse(index) = 1.0 / total
        index += 1
      Right(new PriorDataDiagonalPreconditioner(inverse))

private[flashalign] final class GalePcgConfig private (
    val relativeTolerance: Double,
    val maximumIterations: Int
)

private[flashalign] object GalePcgConfig:
  def create(
      relativeTolerance: Double = 1e-6,
      maximumIterations: Int = 100
  ): Either[GalePcgError, GalePcgConfig] =
    if !relativeTolerance.isFinite || relativeTolerance <= 0.0 then
      Left(GalePcgError.InvalidConfig("relative tolerance must be finite and positive"))
    else if maximumIterations <= 0 then Left(GalePcgError.InvalidConfig("maximum iterations must be positive"))
    else Right(new GalePcgConfig(relativeTolerance, maximumIterations))

private[flashalign] enum GalePcgTermination derives CanEqual:
  case Converged
  case IterationLimit
  case Breakdown
  case ResidualRejected

private[flashalign] final case class GalePcgDiagnostics(
    provider: String,
    iterations: Int,
    providerResidualNorm: Double,
    unpreconditionedResidualNorm: Double,
    requiredResidualNorm: Double,
    operatorProducts: Long,
    preconditionerApplications: Long,
    retainedWorkspaceBytes: Long,
    estimatedOwnedBytesPerSolve: Long
)

private[flashalign] final case class GalePcgDirection(
    values: Vector[Double],
    termination: GalePcgTermination,
    diagnostics: GalePcgDiagnostics
):
  def converged: Boolean = termination == GalePcgTermination.Converged

private[flashalign] final class GalePcgWorkspace private[flashalign] (
    private val owner: AnyRef,
    val providerWorkspace: CgWorkspace,
    private[flashalign] val operatorInput: Array[Double],
    private[flashalign] val operatorOutput: Array[Double],
    private[flashalign] val preconditionerInput: Array[Double],
    private[flashalign] val preconditionerOutput: Array[Double],
    private[flashalign] val residualScratch: Array[Double]
):
  private var active = false
  private var products = 0L
  private var preconditionerUses = 0L

  private[flashalign] def acquire(candidate: AnyRef): Either[GalePcgError, Unit] =
    this.synchronized {
      if !(owner eq candidate) then Left(GalePcgError.WorkspacePlanMismatch)
      else if active then Left(GalePcgError.WorkspaceInUse)
      else
        active = true
        products = 0L
        preconditionerUses = 0L
        Right(())
    }

  private[flashalign] def release(candidate: AnyRef): Unit =
    this.synchronized {
      if owner eq candidate then active = false
    }

  private[flashalign] def recordProduct(): Unit = products += 1L
  private[flashalign] def recordPreconditioner(): Unit = preconditionerUses += 1L
  private[flashalign] def productCount: Long = products
  private[flashalign] def preconditionerCount: Long = preconditionerUses

private[flashalign] final class GalePcgPlan private (
    val operator: ArraySymmetricOperator,
    val preconditioner: ArrayPreconditioner,
    val config: GalePcgConfig
):
  val dimension: Int = operator.dimension

  def newWorkspace(): GalePcgWorkspace =
    new GalePcgWorkspace(
      this,
      CgWorkspace(dimension),
      new Array[Double](dimension),
      new Array[Double](dimension),
      new Array[Double](dimension),
      new Array[Double](dimension),
      new Array[Double](dimension)
    )

  /** Returns an owned trial direction for every numerical provider outcome.
    * Only `Converged` satisfies the requested residual; every direction still
    * requires the original nonlinear objective's trial acceptance.
    */
  def solve(
      rightHandSide: Vector[Double],
      workspace: GalePcgWorkspace
  ): Either[GalePcgError, GalePcgDirection] =
    if rightHandSide.length != dimension then
      Left(GalePcgError.DimensionMismatch(dimension, rightHandSide.length))
    else if rightHandSide.exists(value => !value.isFinite) then Left(GalePcgError.NonFiniteRightHandSide)
    else
      workspace.acquire(this).flatMap { _ =>
        try solveAcquired(rightHandSide, workspace)
        finally workspace.release(this)
      }

  private def solveAcquired(
      rightHandSide: Vector[Double],
      workspace: GalePcgWorkspace
  ): Either[GalePcgError, GalePcgDirection] =
    val galeOperator = new DoubleLinearOperator:
      val rows: Int = dimension
      val cols: Int = dimension
      def applyTo(input: DVec, output: MutableDVec): Unit =
        copyFromGale(input, workspace.operatorInput)
        operator.apply(workspace.operatorInput, workspace.operatorOutput) match
          case Left(error) => throw new OperatorCallbackFailure(error)
          case Right(()) =>
            copyToGale(workspace.operatorOutput, output)
            workspace.recordProduct()

    val galePreconditioner = new Preconditioner:
      def solve(residual: DVec, output: MutableVec[Double]): Unit =
        copyFromGale(residual, workspace.preconditionerInput)
        preconditioner.solve(workspace.preconditionerInput, workspace.preconditionerOutput) match
          case Left(error) => throw new PreconditionerCallbackFailure(error)
          case Right(()) =>
            var index = 0
            while index < dimension do
              output(index) = workspace.preconditionerOutput(index)
              index += 1
            workspace.recordPreconditioner()

    try
      val rhs = DVec.tabulate(dimension)(rightHandSide)
      val provider = IterativeSolvers.cgWith(
        galeOperator,
        rhs,
        workspace.providerWorkspace,
        SolverConfig(config.relativeTolerance, config.maximumIterations),
        galePreconditioner,
        None,
        ToleranceMode.RelativeToRhs
      )
      val ownedSolution = provider.solution
      val solution = Vector.tabulate(dimension)(ownedSolution.apply)
      independentResidual(rightHandSide, solution, workspace).map { actualResidual =>
        val rhsNorm = math.sqrt(rightHandSide.map(value => value * value).sum)
        val required = config.relativeTolerance * rhsNorm
        val termination =
          if provider.converged && actualResidual <= required then GalePcgTermination.Converged
          else if provider.converged then GalePcgTermination.ResidualRejected
          else if provider.iterations >= config.maximumIterations then GalePcgTermination.IterationLimit
          else GalePcgTermination.Breakdown
        GalePcgDirection(
          solution,
          termination,
          GalePcgDiagnostics(
            provider = "gale.cgWith@83cac90a678d1b8a31c590e0c1b8fc8bf3427161",
            iterations = provider.iterations,
            providerResidualNorm = provider.residual,
            unpreconditionedResidualNorm = actualResidual,
            requiredResidualNorm = required,
            operatorProducts = workspace.productCount,
            preconditionerApplications = workspace.preconditionerCount,
            retainedWorkspaceBytes = dimension.toLong * 11L * 8L + preconditioner.retainedBytes,
            estimatedOwnedBytesPerSolve = dimension.toLong * 2L * 8L
          )
        )
      }
    catch
      case error: OperatorCallbackFailure => Left(GalePcgError.OperatorFailure(error.detail))
      case error: PreconditionerCallbackFailure => Left(GalePcgError.PreconditionerFailure(error.detail))
      case NonFatal(error) =>
        Left(GalePcgError.ProviderFailure(error.getClass.getName, Option(error.getMessage).getOrElse("")))

  private def independentResidual(
      rhs: Vector[Double],
      solution: Vector[Double],
      workspace: GalePcgWorkspace
  ): Either[GalePcgError, Double] =
    var index = 0
    while index < dimension do
      workspace.operatorInput(index) = solution(index)
      index += 1
    operator.apply(workspace.operatorInput, workspace.operatorOutput)
      .left
      .map(GalePcgError.OperatorFailure.apply)
      .flatMap { _ =>
        workspace.recordProduct()
        var squared = 0.0
        index = 0
        while index < dimension do
          val residual = rhs(index) - workspace.operatorOutput(index)
          workspace.residualScratch(index) = residual
          squared += residual * residual
          index += 1
        val norm = math.sqrt(squared)
        if norm.isFinite then Right(norm) else Left(GalePcgError.NonFiniteResidual)
      }

  private def copyFromGale(source: DVec, target: Array[Double]): Unit =
    var index = 0
    while index < target.length do
      target(index) = source(index)
      index += 1

  private def copyToGale(source: Array[Double], target: MutableDVec): Unit =
    var index = 0
    while index < source.length do
      target(index) = source(index)
      index += 1

private[flashalign] object GalePcgPlan:
  def create(
      operator: ArraySymmetricOperator,
      preconditioner: ArrayPreconditioner,
      config: GalePcgConfig
  ): Either[GalePcgError, GalePcgPlan] =
    if operator.dimension <= 0 then Left(GalePcgError.InvalidDimension(operator.dimension))
    else if preconditioner.dimension != operator.dimension then
      Left(GalePcgError.DimensionMismatch(operator.dimension, preconditioner.dimension))
    else Right(new GalePcgPlan(operator, preconditioner, config))

private final class OperatorCallbackFailure(val detail: String) extends RuntimeException(detail)
private final class PreconditionerCallbackFailure(val detail: String) extends RuntimeException(detail)

private[flashalign] sealed trait GalePcgError derives CanEqual:
  def message: String

private[flashalign] object GalePcgError:
  final case class InvalidConfig(detail: String) extends GalePcgError:
    val message = s"invalid PCG configuration: $detail"
  final case class InvalidDimension(actual: Int) extends GalePcgError:
    val message = s"PCG dimension must be positive, got $actual"
  final case class DimensionMismatch(expected: Int, actual: Int) extends GalePcgError:
    val message = s"PCG dimension mismatch: expected $expected, got $actual"
  final case class InvalidPreconditionerDiagonal(
      index: Int,
      prior: Double,
      data: Double,
      damping: Double
  ) extends GalePcgError:
    val message = s"invalid prior/data/damping diagonal at $index: $prior, $data, $damping"
  case object NonFiniteRightHandSide extends GalePcgError:
    val message = "PCG right-hand side must be finite"
  case object NonFiniteResidual extends GalePcgError:
    val message = "PCG unpreconditioned residual is non-finite"
  final case class OperatorFailure(detail: String) extends GalePcgError:
    val message = s"matrix-free operator failed: $detail"
  final case class PreconditionerFailure(detail: String) extends GalePcgError:
    val message = s"PCG preconditioner failed: $detail"
  final case class ProviderFailure(kind: String, detail: String) extends GalePcgError:
    val message = s"Gale PCG provider failed with $kind: $detail"
  case object WorkspacePlanMismatch extends GalePcgError:
    val message = "PCG workspace belongs to another plan"
  case object WorkspaceInUse extends GalePcgError:
    val message = "PCG workspace is already in use"
