package reframe4s.halfflow

import image4s.geometry.{D3, Frame, Point}
import reframe4s.core.{MapError, SpatialMap}
import reframe4s.halfflow.internal.*

final class ForwardQueryExportConfig private (val maximumCycleMm: Double, val maximumSamplesPerDirection: Int)
object ForwardQueryExportConfig:
  def make(maximumCycleMm: Double = 0.2, maximumSamplesPerDirection: Int = 20000000)
      : Either[ForwardQueryExportError, ForwardQueryExportConfig] =
    if !maximumCycleMm.isFinite || maximumCycleMm <= 0.0 || maximumCycleMm > 0.2 || maximumSamplesPerDirection <= 0 then
      Left(ForwardQueryExportError.InvalidConfiguration("cycle bound must be in (0,0.2] mm and sample budget positive"))
    else Right(new ForwardQueryExportConfig(maximumCycleMm, maximumSamplesPerDirection))
  val default: ForwardQueryExportConfig = make().fold(e => throw new IllegalStateException(e.toString), identity)

enum ForwardQueryCheckFailure:
  case Query(reverse: Boolean, failure: ForwardQueryFailure)
  case Jacobian(reverse: Boolean, determinant: Double)
  case Cycle(errorMm: Double, maximumMm: Double)

final case class ForwardQueryCycleValue(
    forward: ResidualQueryValue, backward: ResidualQueryValue,
    forwardJacobian: Double, backwardJacobian: Double, cycleErrorMm: Double
)
final case class ForwardQuerySample(
    direction: ExportDirection, index: Int, coordinates: Vector[Double], boundary: Boolean,
    result: Either[ForwardQueryCheckFailure, ForwardQueryCycleValue]
)
final case class ForwardQueryDirectionReport(
    direction: ExportDirection, eligible: Int, evaluated: Int, successful: Int,
    boundaryEligible: Int, boundarySuccessful: Int, minimumSuccessfulJacobian: Double, maximumSuccessfulCycleMm: Double,
    firstFailure: Option[ForwardQuerySample]
)
final case class ForwardQueryAdmissionReport(fixedToMoving: ForwardQueryDirectionReport, movingToFixed: ForwardQueryDirectionReport)

enum ForwardQueryExportError:
  case InvalidConfiguration(context: String)
  case Registration(error: RegistrationError)
  case SampleBudgetExceeded(direction: ExportDirection, eligible: Long, maximum: Int)
  case AdmissionFailed(report: ForwardQueryAdmissionReport)

/** Functional partial maps with a sampled admission report, not dense maps or
  * a global invertibility certificate. Every returned map evaluation also
  * checks both local analytic determinants and its complete numerical cycle.
  */
object ForwardQueryExporter:
  final class Candidate private[ForwardQueryExporter] (
      private[ForwardQueryExporter] val query: ForwardMidpointQuery[?, ?, ?],
      private[ForwardQueryExporter] val bytes: Array[Byte],
      private[ForwardQueryExporter] val config: ForwardQueryExportConfig,
      val report: ForwardQueryAdmissionReport
  ):
    def bundleBytes: Array[Byte] = bytes.clone()

  final class Admitted private[ForwardQueryExporter] (candidate: Candidate):
    private val query = candidate.query
    val fixedFrame: RegistrationFrame[?] = query.fixedFrame
    val movingFrame: RegistrationFrame[?] = query.movingFrame
    val report: ForwardQueryAdmissionReport = candidate.report
    def bundleBytes: Array[Byte] = candidate.bundleBytes
    def newWorkspace(): ForwardQueryWorkspace = query.newWorkspace()

    def evaluate(direction: ExportDirection, point: Point[? <: Frame[D3], D3], workspace: ForwardQueryWorkspace)
        : Either[ForwardQueryCheckFailure, ForwardQueryCycleValue] =
      query.evaluateGeometry(direction, point, workspace).left.map(ForwardQueryCheckFailure.Query(false, _))
        .flatMap(first => complete(query, direction, point.coordinates, first, workspace, candidate.config))

    def fixedToMoving: SpatialMap[fixedFrame.canonical.type, movingFrame.canonical.type, D3] =
      new SpatialMap[fixedFrame.canonical.type, movingFrame.canonical.type, D3]:
        val source: fixedFrame.canonical.type = fixedFrame.canonical
        val target: movingFrame.canonical.type = movingFrame.canonical
        def apply(point: Point[fixedFrame.canonical.type, D3]): Either[MapError, Point[movingFrame.canonical.type, D3]] =
          evaluate(ExportDirection.FixedToMoving, point, newWorkspace())
            .left.map(error => mapError(error, point.coordinates))
            .flatMap(v => Point.fromVector(target, v.forward.coordinates).left.map(MapError.Geometry.apply))

    def movingToFixed: SpatialMap[movingFrame.canonical.type, fixedFrame.canonical.type, D3] =
      new SpatialMap[movingFrame.canonical.type, fixedFrame.canonical.type, D3]:
        val source: movingFrame.canonical.type = movingFrame.canonical
        val target: fixedFrame.canonical.type = fixedFrame.canonical
        def apply(point: Point[movingFrame.canonical.type, D3]): Either[MapError, Point[fixedFrame.canonical.type, D3]] =
          evaluate(ExportDirection.MovingToFixed, point, newWorkspace())
            .left.map(error => mapError(error, point.coordinates))
            .flatMap(v => Point.fromVector(target, v.forward.coordinates).left.map(MapError.Geometry.apply))

  /** Snapshot before measurement: candidate state is privately owned and no
    * caller-supplied report can authorize admission. No scope mask or skipped
    * samples is accepted. Observer receives every original sample, failures too.
    */
  def inspect[W, F, M](plan: ForwardMidpointQuery[W, F, M], config: ForwardQueryExportConfig = ForwardQueryExportConfig.default,
      observe: ForwardQuerySample => Unit = _ => ()): Either[ForwardQueryExportError, Candidate] =
    for
      bytes <- ForwardQueryBundle.encode(plan).left.map(ForwardQueryExportError.Registration.apply)
      query <- ForwardQueryBundle.decode(bytes).left.map(ForwardQueryExportError.Registration.apply)
      _ <- if query.fixedInverse.config.toleranceMm <= 1e-8 then Right(())
        else Left(ForwardQueryExportError.InvalidConfiguration("query equation tolerance must be at most 1e-8 mm"))
      fixedCount <- sampleCount(query.fixedFrame.grid, ExportDirection.FixedToMoving, config)
      movingCount <- sampleCount(query.movingFrame.grid, ExportDirection.MovingToFixed, config)
    yield
      val fixed = scan(query, ExportDirection.FixedToMoving, query.fixedFrame.grid, fixedCount, config, observe)
      val moving = scan(query, ExportDirection.MovingToFixed, query.movingFrame.grid, movingCount, config, observe)
      new Candidate(query, bytes, config, ForwardQueryAdmissionReport(fixed, moving))

  def admit(candidate: Candidate): Either[ForwardQueryExportError, Admitted] =
    def passed(report: ForwardQueryDirectionReport): Boolean =
      report.eligible > 0 && report.evaluated == report.eligible && report.successful == report.eligible &&
        report.boundaryEligible == report.boundarySuccessful && report.firstFailure.isEmpty &&
        report.minimumSuccessfulJacobian.isFinite && report.minimumSuccessfulJacobian > 0.0 &&
        report.maximumSuccessfulCycleMm.isFinite && report.maximumSuccessfulCycleMm <= candidate.config.maximumCycleMm
    if passed(candidate.report.fixedToMoving) && passed(candidate.report.movingToFixed) then Right(new Admitted(candidate))
    else Left(ForwardQueryExportError.AdmissionFailed(candidate.report))

  def build[W, F, M](plan: ForwardMidpointQuery[W, F, M], config: ForwardQueryExportConfig = ForwardQueryExportConfig.default,
      observe: ForwardQuerySample => Unit = _ => ()): Either[ForwardQueryExportError, Admitted] =
    inspect(plan, config, observe).flatMap(admit)

  private def sampleCount(grid: GridSpec, direction: ExportDirection, config: ForwardQueryExportConfig)
      : Either[ForwardQueryExportError, Int] =
    if grid.shape.exists(_ < 2) then Left(ForwardQueryExportError.InvalidConfiguration("complete 3D endpoint lattice required"))
    else
      val count = grid.shape.foldLeft(BigInt(1))((n, s) => n * (2L * s - 1L))
      if count > config.maximumSamplesPerDirection then
        Left(ForwardQueryExportError.SampleBudgetExceeded(direction, count.min(BigInt(Long.MaxValue)).toLong, config.maximumSamplesPerDirection))
      else Right(count.toInt)

  private def scan(query: ForwardMidpointQuery[?, ?, ?], direction: ExportDirection, grid: GridSpec, count: Int,
      config: ForwardQueryExportConfig, observe: ForwardQuerySample => Unit): ForwardQueryDirectionReport =
    val dims = grid.shape.map(s => 2 * s - 1)
    val workspace = query.newWorkspace()
    var successful = 0
    var boundaryEligible = 0
    var boundarySuccessful = 0
    var minimumJacobian = Double.PositiveInfinity
    var maximumCycle = 0.0
    var firstFailure = Option.empty[ForwardQuerySample]
    var index = 0
    while index < count do
      val x = index % dims(0)
      val y = index / dims(0) % dims(1)
      val z = index / (dims(0) * dims(1))
      val boundary = x == 0 || y == 0 || z == 0 || x == dims(0)-1 || y == dims(1)-1 || z == dims(2)-1
      if boundary then boundaryEligible += 1
      val coordinates = grid.voxelToWorld(Vector(0.5 * x, 0.5 * y, 0.5 * z))
      val result = query.evaluateGeometryCoordinates(direction, coordinates, workspace)
        .left.map(ForwardQueryCheckFailure.Query(false, _))
        .flatMap(first => complete(query, direction, coordinates, first, workspace, config))
      val sample = ForwardQuerySample(direction, index, coordinates, boundary, result)
      result match
        case Right(value) =>
          successful += 1
          if boundary then boundarySuccessful += 1
          minimumJacobian = math.min(minimumJacobian, math.min(value.forwardJacobian, value.backwardJacobian))
          maximumCycle = math.max(maximumCycle, value.cycleErrorMm)
        case Left(_) => if firstFailure.isEmpty then firstFailure = Some(sample)
      observe(sample)
      index += 1
    ForwardQueryDirectionReport(direction, count, count, successful, boundaryEligible, boundarySuccessful,
      if successful == 0 then Double.NaN else minimumJacobian, if successful == 0 then Double.NaN else maximumCycle, firstFailure)

  private def complete(query: ForwardMidpointQuery[?, ?, ?], direction: ExportDirection, coordinates: Vector[Double],
      first: ForwardQueryGeometry, workspace: ForwardQueryWorkspace, config: ForwardQueryExportConfig)
      : Either[ForwardQueryCheckFailure, ForwardQueryCycleValue] =
    def geometry(value: ForwardQueryGeometry, reverse: Boolean): Either[ForwardQueryCheckFailure, Unit] =
      if value.determinant.isFinite && value.determinant > 0.0 then Right(())
      else Left(ForwardQueryCheckFailure.Jacobian(reverse, value.determinant))
    val reverse = if direction == ExportDirection.FixedToMoving then ExportDirection.MovingToFixed else ExportDirection.FixedToMoving
    for
      _ <- geometry(first, false)
      back <- query.evaluateGeometryCoordinates(reverse, first.value.coordinates, workspace)
        .left.map(ForwardQueryCheckFailure.Query(true, _))
      _ <- geometry(back, true)
      error = math.sqrt(back.value.coordinates.zip(coordinates).map((x, y) => (x-y)*(x-y)).sum)
      _ <- if error.isFinite && error <= config.maximumCycleMm then Right(())
        else Left(ForwardQueryCheckFailure.Cycle(error, config.maximumCycleMm))
    yield ForwardQueryCycleValue(first.value, back.value, first.determinant, back.determinant, error)

  private def mapError(error: ForwardQueryCheckFailure, coordinates: Vector[Double]): MapError = error match
    case ForwardQueryCheckFailure.Query(_, failure) => ResidualInverseQuery.mapError(failure.failure, coordinates)
    case _ => MapError.OutsideDomain(coordinates)
