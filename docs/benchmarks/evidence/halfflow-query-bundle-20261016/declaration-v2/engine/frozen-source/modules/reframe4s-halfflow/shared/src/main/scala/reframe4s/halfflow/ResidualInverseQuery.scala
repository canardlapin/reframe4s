package reframe4s.halfflow

import image4s.geometry.{D3, Frame, Point}
import reframe4s.core.{MapError, SpatialMap}
import reframe4s.halfflow.internal.*

final class ResidualQueryConfig private (
    val maximumIterations: Int,
    val toleranceMm: Double,
    val maximumStepMm: Double,
    val maximumBacktracks: Int
)

object ResidualQueryConfig:
  def make(maximumIterations: Int = 8, toleranceMm: Double = 1e-8,
      maximumStepMm: Double = 4.0, maximumBacktracks: Int = 12): Either[RegistrationError, ResidualQueryConfig] =
    if maximumIterations < 0 || maximumIterations > 8 || maximumBacktracks < 1 || maximumBacktracks > 12 ||
        !toleranceMm.isFinite || toleranceMm <= 0.0 || !maximumStepMm.isFinite || maximumStepMm <= 0.0 || maximumStepMm > 4.0 then
      Left(RegistrationError.InvalidConfiguration("bounded residual query settings"))
    else Right(new ResidualQueryConfig(maximumIterations, toleranceMm, maximumStepMm, maximumBacktracks))

  val default: ResidualQueryConfig = make().fold(e => throw new IllegalStateException(e.message), identity)

enum ResidualQueryError:
  case Frame(error: MapError)
  case WorkspaceMismatch
  case WorkspaceInUse
  case NonFiniteQuery
  case UnsupportedSeed
  case UnsupportedForward
  case SingularJacobian
  case NonPositiveJacobian
  case NonFiniteStep
  case IterationLimit
  case Stalled

/** Failed estimates are diagnostics and must not become map values. */
final case class ResidualQueryReport(
    estimate: Option[Vector[Double]],
    residualMm: Double,
    determinant: Double,
    iterations: Int,
    forwardSamples: Int,
    derivativeSamples: Int,
    linearSolves: Int,
    backtrackingTrials: Int
)

object ResidualQueryReport:
  private[halfflow] def empty: ResidualQueryReport =
    ResidualQueryReport(None, Double.NaN, Double.NaN, 0, 0, 0, 0, 0)

final case class ResidualQueryFailure(reason: ResidualQueryError, report: ResidualQueryReport)
final case class ResidualQueryValue(coordinates: Vector[Double], report: ResidualQueryReport)

/** Constant-size per-worker scratch; the compiled fields remain immutable. */
final class ResidualQueryWorkspace private[halfflow] (private[halfflow] val owner: AnyRef, grid: GridSpec):
  private var active = false
  private[halfflow] val sampler: DenseFieldSampler = DenseFieldSampler(grid)
  private[halfflow] val differential: Array[Double] = new Array[Double](9)
  private[halfflow] def acquire(): Boolean = synchronized {
    if active then false else { active = true; true }
  }
  private[halfflow] def release(): Unit = synchronized { active = false }

/** Query-local inversion of the authoritative boundary-linear residual.
  * No returned success is a global invertibility or raster-export certificate.
  * A seed is only acceleration state; it is never an acceptance authority.
  */
final class ResidualInverseQuery[A] private (
    val forward: DensePull[A, A],
    val seed: DensePull[A, A],
    val config: ResidualQueryConfig
):
  val frame: RegistrationFrame[A] = forward.from
  def newWorkspace(): ResidualQueryWorkspace = new ResidualQueryWorkspace(this, frame.grid)

  def evaluate(point: Point[? <: Frame[D3], D3], workspace: ResidualQueryWorkspace)
      : Either[ResidualQueryFailure, ResidualQueryValue] =
    SpatialMap.validateSourcePoint(frame.canonical, point) match
      case Left(error) => Left(ResidualQueryFailure(ResidualQueryError.Frame(error), ResidualQueryReport.empty))
      case Right(_) =>
        val q = point.coordinates
        evaluateCoordinates(q(0), q(1), q(2), workspace)

  /** Standard canonical map composition keeps numerical correction at every
    * evaluation. Nonconverged queries are undefined, never filled or returned
    * as the seed. Use evaluate for their detailed typed evidence.
    */
  def asSpatialMap: SpatialMap[frame.canonical.type, frame.canonical.type, D3] =
    new SpatialMap[frame.canonical.type, frame.canonical.type, D3]:
      val source: frame.canonical.type = frame.canonical
      val target: frame.canonical.type = frame.canonical
      def apply(point: Point[frame.canonical.type, D3]): Either[MapError, Point[frame.canonical.type, D3]] =
        evaluate(point, newWorkspace()).left.map(f => ResidualInverseQuery.mapError(f, point.coordinates))
          .flatMap(v => Point.fromVector(target, v.coordinates).left.map(MapError.Geometry.apply))

  private[halfflow] def evaluateCoordinates(x: Double, y: Double, z: Double, workspace: ResidualQueryWorkspace)
      : Either[ResidualQueryFailure, ResidualQueryValue] =
    def early(error: ResidualQueryError) = Left(ResidualQueryFailure(error, ResidualQueryReport.empty))
    if workspace.owner ne this then early(ResidualQueryError.WorkspaceMismatch)
    else if !x.isFinite || !y.isFinite || !z.isFinite then early(ResidualQueryError.NonFiniteQuery)
    else if !workspace.acquire() then early(ResidualQueryError.WorkspaceInUse)
    else
      try solve(x, y, z, workspace)
      finally workspace.release()

  private def solve(tx: Double, ty: Double, tz: Double, workspace: ResidualQueryWorkspace)
      : Either[ResidualQueryFailure, ResidualQueryValue] =
    val sampler = workspace.sampler
    val j = workspace.differential
    var x = Double.NaN
    var y = Double.NaN
    var z = Double.NaN
    var residual = Double.NaN
    var determinant = Double.NaN
    var iterations = 0
    var samples = 0
    var derivatives = 0
    var solves = 0
    var trials = 0
    def report: ResidualQueryReport =
      ResidualQueryReport(if x.isFinite && y.isFinite && z.isFinite then Some(Vector(x, y, z)) else None,
        residual, determinant, iterations, samples, derivatives, solves, trials)
    def failed(error: ResidualQueryError) = Left(ResidualQueryFailure(error, report))
    HalfFlowKernels.sampleCoordinateAtWorldInto(seed.sourceCoordinates, seed.validity, sampler, tx, ty, tz)
    if !sampler.valid then return failed(ResidualQueryError.UnsupportedSeed)
    x = sampler.x
    y = sampler.y
    z = sampler.z
    while true do
      HalfFlowKernels.sampleCoordinateAtWorldInto(forward.sourceCoordinates, forward.validity, sampler, x, y, z, j)
      samples += 1
      derivatives += 1
      if !sampler.valid then return failed(ResidualQueryError.UnsupportedForward)
      val rx = sampler.x - tx
      val ry = sampler.y - ty
      val rz = sampler.z - tz
      residual = math.sqrt(rx * rx + ry * ry + rz * rz)
      determinant = j(0) * (j(4) * j(8) - j(5) * j(7)) -
        j(1) * (j(3) * j(8) - j(5) * j(6)) + j(2) * (j(3) * j(7) - j(4) * j(6))
      if !residual.isFinite || !determinant.isFinite then return failed(ResidualQueryError.NonFiniteStep)
      if residual <= config.toleranceMm then
        if determinant <= 0.0 then return failed(ResidualQueryError.NonPositiveJacobian)
        return Right(ResidualQueryValue(Vector(x, y, z), report))
      if iterations >= config.maximumIterations then return failed(ResidualQueryError.IterationLimit)
      if math.abs(determinant) <= 1e-12 then return failed(ResidualQueryError.SingularJacobian)
      var dx = (rx * (j(4) * j(8) - j(5) * j(7)) -
        j(1) * (ry * j(8) - j(5) * rz) + j(2) * (ry * j(7) - j(4) * rz)) / determinant
      var dy = (j(0) * (ry * j(8) - j(5) * rz) -
        rx * (j(3) * j(8) - j(5) * j(6)) + j(2) * (j(3) * rz - ry * j(6))) / determinant
      var dz = (j(0) * (j(4) * rz - ry * j(7)) -
        j(1) * (j(3) * rz - ry * j(6)) + rx * (j(3) * j(7) - j(4) * j(6))) / determinant
      val norm = math.sqrt(dx * dx + dy * dy + dz * dz)
      if !norm.isFinite then return failed(ResidualQueryError.NonFiniteStep)
      val scale = math.min(1.0, config.maximumStepMm / math.max(norm, 1e-300))
      dx *= scale
      dy *= scale
      dz *= scale
      solves += 1
      var accepted = false
      var backtrack = 0
      while backtrack < config.maximumBacktracks && !accepted do
        val alpha = math.pow(0.5, backtrack.toDouble)
        val qx = x - alpha * dx
        val qy = y - alpha * dy
        val qz = z - alpha * dz
        HalfFlowKernels.sampleCoordinateAtWorldInto(forward.sourceCoordinates, forward.validity, sampler, qx, qy, qz)
        samples += 1
        trials += 1
        val ex = sampler.x - tx
        val ey = sampler.y - ty
        val ez = sampler.z - tz
        val next = math.sqrt(ex * ex + ey * ey + ez * ez)
        if sampler.valid && next.isFinite && next < residual then
          x = qx
          y = qy
          z = qz
          accepted = true
        backtrack += 1
      if !accepted then return failed(ResidualQueryError.Stalled)
      iterations += 1
    failed(ResidualQueryError.IterationLimit)

object ResidualInverseQuery:
  def compile[A](forward: DensePull[A, A], seed: DensePull[A, A], config: ResidualQueryConfig = ResidualQueryConfig.default)
      : Either[RegistrationError, ResidualInverseQuery[A]] =
    for
      checkedForward <- DensePull.make(forward.from, forward.to, forward.sourceCoordinates, forward.validity)
      checkedSeed <- DensePull.make(seed.from, seed.to, seed.sourceCoordinates, seed.validity)
      result <- compileChecked(checkedForward, checkedSeed, config)
    yield result

  private def compileChecked[A](forward: DensePull[A, A], seed: DensePull[A, A], config: ResidualQueryConfig)
      : Either[RegistrationError, ResidualInverseQuery[A]] =
    if forward.from != forward.to || seed.from != forward.from || seed.to != forward.to then
      Left(RegistrationError.GridMismatch("residual query forward/seed frames"))
    else if forward.from.grid.shape.exists(_ < 2) then
      Left(RegistrationError.InvalidConfiguration("residual query requires complete 3D cells"))
    else if !TrilinearCellGeometry.evaluate(forward.sourceCoordinates, forward.validity).acceptable(0.05, 0.99) then
      Left(RegistrationError.UnsafeTransform("residual query forward complete-cell geometry"))
    else Right(new ResidualInverseQuery(forward, seed, config))

  private[halfflow] def mapError(failure: ResidualQueryFailure, query: Vector[Double]): MapError =
    failure.reason match
      case ResidualQueryError.Frame(error) => error
      case _ => MapError.OutsideDomain(query)
