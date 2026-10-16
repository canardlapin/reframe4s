package reframe4s.halfflow

import image4s.geometry.{Affine, D3, Frame, Point}
import reframe4s.core.{MapError, SpatialMap}
import reframe4s.halfflow.internal.*

enum ForwardQueryStage:
  case Frame, Workspace, Inverse, Forward, Affine, Geometry

final case class ForwardQueryGeometry(value: ResidualQueryValue, determinant: Double)

final case class ForwardQueryFailure(stage: ForwardQueryStage, failure: ResidualQueryFailure)

final class ForwardQueryWorkspace private[halfflow] (
    private[halfflow] val owner: AnyRef,
    private[halfflow] val fixed: ResidualQueryWorkspace,
    private[halfflow] val moving: ResidualQueryWorkspace,
    grid: GridSpec
):
  private var active = false
  private[halfflow] val sampler: DenseFieldSampler = DenseFieldSampler(grid)
  private[halfflow] val endpointDifferential: Array[Double] = new Array[Double](9)
  private[halfflow] def acquire(): Boolean = synchronized {
    if active then false else { active = true; true }
  }
  private[halfflow] def release(): Unit = synchronized { active = false }

/** Exact affine -> inverse query -> forward query -> exact affine.
  * Composing these evaluations never re-rasterizes the corrected inverse.
  * This opt-in API does not claim to satisfy the dense exporter contract.
  */
final class ForwardMidpointQuery[W, F, M] private (
    val state: ForwardMidpoint[W, F, M],
    val fixedInverse: ResidualInverseQuery[W],
    val movingInverse: ResidualInverseQuery[W],
    val fixedToWork: Affine[D3],
    val movingToWork: Affine[D3]
):
  val fixedFrame: RegistrationFrame[F] = state.fixed.endpoint
  val movingFrame: RegistrationFrame[M] = state.moving.endpoint

  def newWorkspace(): ForwardQueryWorkspace =
    new ForwardQueryWorkspace(this, fixedInverse.newWorkspace(), movingInverse.newWorkspace(), state.work.grid)

  def evaluate(direction: ExportDirection, point: Point[? <: Frame[D3], D3], workspace: ForwardQueryWorkspace)
      : Either[ForwardQueryFailure, ResidualQueryValue] =
    validatePoint(direction, point).flatMap(_ => evaluateCoordinates(direction, point.coordinates, workspace))

  /** Analytic determinant of the complete affine/residual query composition.
    * This is local sampled geometry, not a global invertibility certificate.
    */
  def evaluateGeometry(direction: ExportDirection, point: Point[? <: Frame[D3], D3], workspace: ForwardQueryWorkspace)
      : Either[ForwardQueryFailure, ForwardQueryGeometry] =
    validatePoint(direction, point).flatMap(_ => evaluateGeometryCoordinates(direction, point.coordinates, workspace))

  private def validatePoint(direction: ExportDirection, point: Point[? <: Frame[D3], D3])
      : Either[ForwardQueryFailure, Unit] =
    val expected = direction match
      case ExportDirection.FixedToMoving => fixedFrame.canonical
      case ExportDirection.MovingToFixed => movingFrame.canonical
    SpatialMap.validateSourcePoint(expected, point) match
      case Left(error) => Left(ForwardQueryFailure(ForwardQueryStage.Frame,
        ResidualQueryFailure(ResidualQueryError.Frame(error), ResidualQueryReport.empty)))
      case Right(_) => Right(())

  def fixedToMoving: SpatialMap[fixedFrame.canonical.type, movingFrame.canonical.type, D3] =
    new SpatialMap[fixedFrame.canonical.type, movingFrame.canonical.type, D3]:
      val source: fixedFrame.canonical.type = fixedFrame.canonical
      val target: movingFrame.canonical.type = movingFrame.canonical
      def apply(point: Point[fixedFrame.canonical.type, D3]): Either[MapError, Point[movingFrame.canonical.type, D3]] =
        evaluate(ExportDirection.FixedToMoving, point, newWorkspace())
          .left.map(e => ResidualInverseQuery.mapError(e.failure, point.coordinates))
          .flatMap(v => Point.fromVector(target, v.coordinates).left.map(MapError.Geometry.apply))

  def movingToFixed: SpatialMap[movingFrame.canonical.type, fixedFrame.canonical.type, D3] =
    new SpatialMap[movingFrame.canonical.type, fixedFrame.canonical.type, D3]:
      val source: movingFrame.canonical.type = movingFrame.canonical
      val target: fixedFrame.canonical.type = fixedFrame.canonical
      def apply(point: Point[movingFrame.canonical.type, D3]): Either[MapError, Point[fixedFrame.canonical.type, D3]] =
        evaluate(ExportDirection.MovingToFixed, point, newWorkspace())
          .left.map(e => ResidualInverseQuery.mapError(e.failure, point.coordinates))
          .flatMap(v => Point.fromVector(target, v.coordinates).left.map(MapError.Geometry.apply))

  private[halfflow] def evaluateCoordinates(direction: ExportDirection, query: Vector[Double], workspace: ForwardQueryWorkspace)
      : Either[ForwardQueryFailure, ResidualQueryValue] =
    evaluateCore(direction, query, workspace, geometry = false).map(_.value)

  private[halfflow] def evaluateGeometryCoordinates(direction: ExportDirection, query: Vector[Double], workspace: ForwardQueryWorkspace)
      : Either[ForwardQueryFailure, ForwardQueryGeometry] =
    evaluateCore(direction, query, workspace, geometry = true)

  private def evaluateCore(direction: ExportDirection, query: Vector[Double], workspace: ForwardQueryWorkspace, geometry: Boolean)
      : Either[ForwardQueryFailure, ForwardQueryGeometry] =
    def early(reason: ResidualQueryError) = Left(ForwardQueryFailure(ForwardQueryStage.Workspace,
      ResidualQueryFailure(reason, ResidualQueryReport.empty)))
    if workspace.owner ne this then early(ResidualQueryError.WorkspaceMismatch)
    else if !workspace.acquire() then early(ResidualQueryError.WorkspaceInUse)
    else
      try
        val (sourceAffine, inverse, inverseWorkspace, destination, destinationAffine) = direction match
          case ExportDirection.FixedToMoving =>
            (fixedToWork, fixedInverse, workspace.fixed,
              state.moving.residual, state.moving.affine.transform)
          case ExportDirection.MovingToFixed =>
            (movingToWork, movingInverse, workspace.moving,
              state.fixed.residual, state.fixed.affine.transform)
        sourceAffine(query).left.map(error => ForwardQueryFailure(ForwardQueryStage.Affine,
          ResidualQueryFailure(ResidualQueryError.Frame(MapError.Geometry(error)), ResidualQueryReport.empty)))
          .flatMap: coordinates =>
            inverse.evaluateCoordinates(coordinates(0), coordinates(1), coordinates(2), inverseWorkspace)
              .left.map(ForwardQueryFailure(ForwardQueryStage.Inverse, _))
          .flatMap: value =>
            val p = value.coordinates
            val sampler = workspace.sampler
            HalfFlowKernels.sampleCoordinateAtWorldInto(destination.sourceCoordinates, destination.validity,
              sampler, p(0), p(1), p(2), if geometry then workspace.endpointDifferential else null)
            if !sampler.valid then Left(ForwardQueryFailure(if geometry then ForwardQueryStage.Geometry else ForwardQueryStage.Forward,
              ResidualQueryFailure(ResidualQueryError.UnsupportedForward, value.report)))
            else
              destinationAffine(Vector(sampler.x, sampler.y, sampler.z))
                .left.map(error => ForwardQueryFailure(ForwardQueryStage.Affine,
                  ResidualQueryFailure(ResidualQueryError.Frame(MapError.Geometry(error)), value.report)))
                .map: result =>
                  val report = value.report.copy(forwardSamples = value.report.forwardSamples + 1,
                    derivativeSamples = value.report.derivativeSamples + (if geometry then 1 else 0))
                  val determinant = if !geometry then Double.NaN else
                    val j = workspace.endpointDifferential
                    val destinationDeterminant = j(0) * (j(4) * j(8) - j(5) * j(7)) -
                      j(1) * (j(3) * j(8) - j(5) * j(6)) + j(2) * (j(3) * j(7) - j(4) * j(6))
                    ForwardMidpointQuery.linearDeterminant(destinationAffine) * destinationDeterminant *
                      ForwardMidpointQuery.linearDeterminant(sourceAffine) / value.report.determinant
                  ForwardQueryGeometry(ResidualQueryValue(result, report), determinant)
      finally workspace.release()

object ForwardMidpointQuery:
  private[halfflow] def linearDeterminant(affine: Affine[D3]): Double =
    val m = affine.matrix
    m(0, 0) * (m(1, 1) * m(2, 2) - m(1, 2) * m(2, 1)) -
      m(0, 1) * (m(1, 0) * m(2, 2) - m(1, 2) * m(2, 0)) +
      m(0, 2) * (m(1, 0) * m(2, 1) - m(1, 1) * m(2, 0))

  def compile[W, F, M](state: ForwardMidpoint[W, F, M], fixedSeed: DensePull[W, W], movingSeed: DensePull[W, W],
      config: ResidualQueryConfig = ResidualQueryConfig.default): Either[RegistrationError, ForwardMidpointQuery[W, F, M]] =
    restore(state, fixedSeed, movingSeed, config, state.fixed.affine.transform.inverse, state.moving.affine.transform.inverse)

  /** Preserve both cached affine directions when restoring a saved evaluator.
    * Canonical affine operators still own evaluation and validation.
    */
  private[halfflow] def restore[W, F, M](state: ForwardMidpoint[W, F, M], fixedSeed: DensePull[W, W],
      movingSeed: DensePull[W, W], config: ResidualQueryConfig, fixedToWork: Affine[D3], movingToWork: Affine[D3])
      : Either[RegistrationError, ForwardMidpointQuery[W, F, M]] =
    def check(forward: Affine[D3], reverse: Affine[D3]): Either[RegistrationError, Unit] =
      for
        a <- forward.andThen(reverse).left.map(e => RegistrationError.InvalidAffine("query bundle inverse", e.message))
        b <- reverse.andThen(forward).left.map(e => RegistrationError.InvalidAffine("query bundle inverse", e.message))
        _ <- if Vector(a, b).forall(_.rowMajor.zipWithIndex.forall((v, i) =>
          math.abs(v - (if i / 4 == i % 4 then 1.0 else 0.0)) <= 1e-9)) then Right(())
          else Left(RegistrationError.InvalidAffine("query bundle inverse", "inconsistent affine directions"))
      yield ()
    for
      _ <- check(state.fixed.affine.transform, fixedToWork)
      _ <- check(state.moving.affine.transform, movingToWork)
      fixed <- ResidualInverseQuery.compile(state.fixed.residual, fixedSeed, config)
      moving <- ResidualInverseQuery.compile(state.moving.residual, movingSeed, config)
    yield new ForwardMidpointQuery(state, fixed, moving, fixedToWork, movingToWork)
