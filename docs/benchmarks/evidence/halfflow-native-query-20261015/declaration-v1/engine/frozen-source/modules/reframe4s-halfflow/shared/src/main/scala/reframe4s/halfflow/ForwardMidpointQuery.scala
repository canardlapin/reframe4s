package reframe4s.halfflow

import image4s.geometry.{D3, Frame, Point}
import reframe4s.core.{MapError, SpatialMap}
import reframe4s.halfflow.internal.*

enum ForwardQueryStage:
  case Frame, Workspace, Inverse, Forward, Affine

final case class ForwardQueryFailure(stage: ForwardQueryStage, failure: ResidualQueryFailure)

final class ForwardQueryWorkspace private[halfflow] (
    private[halfflow] val owner: AnyRef,
    private[halfflow] val fixed: ResidualQueryWorkspace,
    private[halfflow] val moving: ResidualQueryWorkspace,
    grid: GridSpec
):
  private var active = false
  private[halfflow] val sampler: DenseFieldSampler = DenseFieldSampler(grid)
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
    val movingInverse: ResidualInverseQuery[W]
):
  val fixedFrame: RegistrationFrame[F] = state.fixed.endpoint
  val movingFrame: RegistrationFrame[M] = state.moving.endpoint

  def newWorkspace(): ForwardQueryWorkspace =
    new ForwardQueryWorkspace(this, fixedInverse.newWorkspace(), movingInverse.newWorkspace(), state.work.grid)

  def evaluate(direction: ExportDirection, point: Point[? <: Frame[D3], D3], workspace: ForwardQueryWorkspace)
      : Either[ForwardQueryFailure, ResidualQueryValue] =
    val expected = direction match
      case ExportDirection.FixedToMoving => fixedFrame.canonical
      case ExportDirection.MovingToFixed => movingFrame.canonical
    SpatialMap.validateSourcePoint(expected, point) match
      case Left(error) => Left(ForwardQueryFailure(ForwardQueryStage.Frame,
        ResidualQueryFailure(ResidualQueryError.Frame(error), ResidualQueryReport.empty)))
      case Right(_) => evaluateCoordinates(direction, point.coordinates, workspace)

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
    def early(reason: ResidualQueryError) = Left(ForwardQueryFailure(ForwardQueryStage.Workspace,
      ResidualQueryFailure(reason, ResidualQueryReport.empty)))
    if workspace.owner ne this then early(ResidualQueryError.WorkspaceMismatch)
    else if !workspace.acquire() then early(ResidualQueryError.WorkspaceInUse)
    else
      try
        val (sourceAffine, inverse, inverseWorkspace, destination, destinationAffine) = direction match
          case ExportDirection.FixedToMoving =>
            (state.fixed.affine.transform.inverse, fixedInverse, workspace.fixed,
              state.moving.residual, state.moving.affine.transform)
          case ExportDirection.MovingToFixed =>
            (state.moving.affine.transform.inverse, movingInverse, workspace.moving,
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
              sampler, p(0), p(1), p(2))
            if !sampler.valid then Left(ForwardQueryFailure(ForwardQueryStage.Forward,
              ResidualQueryFailure(ResidualQueryError.UnsupportedForward, value.report)))
            else
              destinationAffine(Vector(sampler.x, sampler.y, sampler.z))
                .left.map(error => ForwardQueryFailure(ForwardQueryStage.Affine,
                  ResidualQueryFailure(ResidualQueryError.Frame(MapError.Geometry(error)), value.report)))
                .map(result => ResidualQueryValue(result, value.report.copy(forwardSamples = value.report.forwardSamples + 1)))
      finally workspace.release()

object ForwardMidpointQuery:
  def compile[W, F, M](state: ForwardMidpoint[W, F, M], fixedSeed: DensePull[W, W], movingSeed: DensePull[W, W],
      config: ResidualQueryConfig = ResidualQueryConfig.default): Either[RegistrationError, ForwardMidpointQuery[W, F, M]] =
    for
      fixed <- ResidualInverseQuery.compile(state.fixed.residual, fixedSeed, config)
      moving <- ResidualInverseQuery.compile(state.moving.residual, movingSeed, config)
    yield new ForwardMidpointQuery(state, fixed, moving)
