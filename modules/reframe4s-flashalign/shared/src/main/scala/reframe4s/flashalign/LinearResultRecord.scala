package reframe4s.flashalign

import image4s.BoundaryPolicy
import image4s.Sampled
import image4s.SampleSpace
import image4s.ValueSemantics
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.FrameRecord
import image4s.geometry.FrameRegistry
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.GridRecord
import image4s.geometry.GridRegistry
import image4s.geometry.LengthUnit
import ravel.AnyRank
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.register.OptimizationReport
import reframe4s.register.RegistrationFailure
import reframe4s.register.Termination
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingError
import reframe4s.resample.ResamplingPlan

enum FlashalignTransformDirection derives CanEqual:
  case MovingWorldToFixedWorld

enum FlashalignTransformConvention derives CanEqual:
  case RowMajorYEqualsTransformXMillimetresV1

final case class FlashalignOptimizationRecord(
    initialObjective: Double,
    finalObjective: Double,
    iterations: Int,
    attempts: Int,
    acceptedSteps: Int,
    termination: Termination
) derives CanEqual

object FlashalignOptimizationRecord:
  private[flashalign] def from(
      report: OptimizationReport
  ): FlashalignOptimizationRecord =
    FlashalignOptimizationRecord(
      report.initialObjective,
      report.finalObjective,
      report.iterations,
      report.attempts,
      report.acceptedSteps,
      report.termination
    )

  private[flashalign] def restore(
      record: FlashalignOptimizationRecord
  ): Either[RegistrationFailure, OptimizationReport] =
    OptimizationReport.create(
      record.initialObjective,
      record.finalObjective,
      record.iterations,
      record.attempts,
      record.acceptedSteps,
      record.termination
    )

final case class FlashalignLinearResultRecord(
    version: Int,
    model: FlashalignModel,
    direction: FlashalignTransformDirection,
    convention: FlashalignTransformConvention,
    coordinateUnit: LengthUnit,
    movingFrame: FrameRecord,
    fixedFrame: FrameRecord,
    movingGrid: GridRecord,
    fixedGrid: GridRecord,
    movingToFixedRowMajor: Vector[Double],
    rigidValidationTolerance: Option[Double],
    optimization: FlashalignOptimizationRecord,
    diagnostics: FlashalignDiagnostics
) derives CanEqual

sealed trait FlashalignRecordError derives CanEqual:
  def message: String

object FlashalignRecordError:
  final case class PersistentFrameRequired(
      endpoint: FlashalignEndpoint,
      frame: FrameOwnerDescriptor
  ) extends FlashalignRecordError:
    val message: String = s"$endpoint result frame must have persistent identity: $frame"

  final case class PersistentGridRequired(endpoint: FlashalignEndpoint)
      extends FlashalignRecordError:
    val message: String = s"$endpoint result grid must have persistent identity"

  final case class GridFrameOwnerMismatch(endpoint: FlashalignEndpoint)
      extends FlashalignRecordError:
    val message: String = s"$endpoint grid does not belong to the result frame owner"

  final case class UnsupportedVersion(actual: Int, supported: Int)
      extends FlashalignRecordError:
    val message: String = s"Flashalign record version $actual is unsupported; expected $supported"

  final case class ModelMismatch(expected: FlashalignModel, actual: FlashalignModel)
      extends FlashalignRecordError:
    val message: String = s"expected $expected Flashalign record, got $actual"

  final case class DirectionMismatch(actual: FlashalignTransformDirection)
      extends FlashalignRecordError:
    val message: String = s"unsupported Flashalign transform direction $actual"

  final case class ConventionMismatch(actual: FlashalignTransformConvention)
      extends FlashalignRecordError:
    val message: String = s"unsupported Flashalign transform convention $actual"

  final case class UnitMismatch(endpoint: FlashalignEndpoint, actual: LengthUnit)
      extends FlashalignRecordError:
    val message: String = s"$endpoint Flashalign coordinates must be millimetres, got $actual"

  final case class MissingRigidTolerance(model: FlashalignModel)
      extends FlashalignRecordError:
    val message: String = s"$model record has no rigid validation tolerance"

  final case class UnexpectedRigidTolerance(model: FlashalignModel)
      extends FlashalignRecordError:
    val message: String = s"$model record unexpectedly contains a rigid validation tolerance"

  final case class DiagnosticsModelMismatch(
      transform: FlashalignModel,
      diagnostics: FlashalignModel
  ) extends FlashalignRecordError:
    val message: String =
      s"transform model $transform does not match diagnostics model $diagnostics"

  final case class Geometry(error: GeometryError) extends FlashalignRecordError:
    val message: String = error.message

  final case class Rigid(error: RigidError) extends FlashalignRecordError:
    val message: String = error.message

  final case class Registration(error: RegistrationFailure)
      extends FlashalignRecordError:
    val message: String = error.message

  final case class Result(error: FlashalignError) extends FlashalignRecordError:
    val message: String = error.message

final class RestoredRigidFlashalignResult private[flashalign] (
    val result: RigidFlashalignResult[Frame[D3], Frame[D3]],
    val movingGrid: Grid[Frame[D3], D3],
    val fixedGrid: Grid[Frame[D3], D3],
    val frameRegistry: FrameRegistry,
    val gridRegistry: GridRegistry
)

final class RestoredAffineFlashalignResult private[flashalign] (
    val result: AffineFlashalignResult[Frame[D3], Frame[D3]],
    val movingGrid: Grid[Frame[D3], D3],
    val fixedGrid: Grid[Frame[D3], D3],
    val frameRegistry: FrameRegistry,
    val gridRegistry: GridRegistry
)

object FlashalignLinearResultRecord:
  val CurrentVersion: Int = 1
  val CurrentDirection: FlashalignTransformDirection =
    FlashalignTransformDirection.MovingWorldToFixedWorld
  val CurrentConvention: FlashalignTransformConvention =
    FlashalignTransformConvention.RowMajorYEqualsTransformXMillimetresV1

  def fromRigid[Moving <: Frame[D3], Fixed <: Frame[D3]](
      result: RigidFlashalignResult[Moving, Fixed],
      movingGrid: Grid[Moving, D3],
      fixedGrid: Grid[Fixed, D3]
  ): Either[FlashalignRecordError, FlashalignLinearResultRecord] =
    build(
      result.movingToFixed.source,
      result.movingToFixed.target,
      movingGrid,
      fixedGrid,
      FlashalignModel.Rigid,
      result.movingToFixed.operator.rowMajor,
      Some(result.movingToFixed.validationTolerance),
      result.report,
      result.diagnostics
    )

  def fromAffine[Moving <: Frame[D3], Fixed <: Frame[D3]](
      result: AffineFlashalignResult[Moving, Fixed],
      movingGrid: Grid[Moving, D3],
      fixedGrid: Grid[Fixed, D3]
  ): Either[FlashalignRecordError, FlashalignLinearResultRecord] =
    build(
      result.movingToFixed.source,
      result.movingToFixed.target,
      movingGrid,
      fixedGrid,
      FlashalignModel.Affine,
      result.movingToFixed.operator.rowMajor,
      None,
      result.report,
      result.diagnostics
    )

  def restoreRigid(
      record: FlashalignLinearResultRecord,
      frames: FrameRegistry,
      grids: GridRegistry
  )(using Dimension[D3]): Either[FlashalignRecordError, RestoredRigidFlashalignResult] =
    for
      _ <- validateRecord(record, FlashalignModel.Rigid)
      tolerance <- record.rigidValidationTolerance.toRight(
        FlashalignRecordError.MissingRigidTolerance(record.model)
      )
      moving <- Frame
        .restore[D3](record.movingFrame, frames)
        .left
        .map(FlashalignRecordError.Geometry.apply)
      fixed <- Frame
        .restore[D3](record.fixedFrame, moving.registry)
        .left
        .map(FlashalignRecordError.Geometry.apply)
      movingGrid <- Grid
        .restore[D3, Frame[D3]](
          record.movingGrid,
          moving.frame,
          grids
        )
        .left
        .map(FlashalignRecordError.Geometry.apply)
      fixedGrid <- Grid
        .restore[D3, Frame[D3]](
          record.fixedGrid,
          fixed.frame,
          movingGrid.registry
        )
        .left
        .map(FlashalignRecordError.Geometry.apply)
      transform <- Rigid3
        .fromRowMajor(
          moving.frame,
          fixed.frame,
          record.movingToFixedRowMajor,
          tolerance
        )
        .left
        .map(FlashalignRecordError.Rigid.apply)
      report <- FlashalignOptimizationRecord
        .restore(record.optimization)
        .left
        .map(FlashalignRecordError.Registration.apply)
      result <- RigidFlashalignResult
        .create(transform, report, record.diagnostics)
        .left
        .map(FlashalignRecordError.Result.apply)
    yield
      new RestoredRigidFlashalignResult(
        result,
        movingGrid.grid,
        fixedGrid.grid,
        fixed.registry,
        fixedGrid.registry
      )

  def restoreAffine(
      record: FlashalignLinearResultRecord,
      frames: FrameRegistry,
      grids: GridRegistry
  )(using Dimension[D3]): Either[FlashalignRecordError, RestoredAffineFlashalignResult] =
    for
      _ <- validateRecord(record, FlashalignModel.Affine)
      _ <- Either.cond(
        record.rigidValidationTolerance.isEmpty,
        (),
        FlashalignRecordError.UnexpectedRigidTolerance(record.model)
      )
      moving <- Frame
        .restore[D3](record.movingFrame, frames)
        .left
        .map(FlashalignRecordError.Geometry.apply)
      fixed <- Frame
        .restore[D3](record.fixedFrame, moving.registry)
        .left
        .map(FlashalignRecordError.Geometry.apply)
      movingGrid <- Grid
        .restore[D3, Frame[D3]](record.movingGrid, moving.frame, grids)
        .left
        .map(FlashalignRecordError.Geometry.apply)
      fixedGrid <- Grid
        .restore[D3, Frame[D3]](
          record.fixedGrid,
          fixed.frame,
          movingGrid.registry
        )
        .left
        .map(FlashalignRecordError.Geometry.apply)
      operator <- Affine
        .fromRowMajor[D3](record.movingToFixedRowMajor)
        .left
        .map(FlashalignRecordError.Geometry.apply)
      report <- FlashalignOptimizationRecord
        .restore(record.optimization)
        .left
        .map(FlashalignRecordError.Registration.apply)
      transform = FramedAffine.betweenFrames(moving.frame, fixed.frame)(operator)
      result <- AffineFlashalignResult
        .create(transform, report, record.diagnostics)
        .left
        .map(FlashalignRecordError.Result.apply)
    yield
      new RestoredAffineFlashalignResult(
        result,
        movingGrid.grid,
        fixedGrid.grid,
        fixed.registry,
        fixedGrid.registry
      )

  private def build[Moving <: Frame[D3], Fixed <: Frame[D3]](
      moving: Moving,
      fixed: Fixed,
      movingGrid: Grid[Moving, D3],
      fixedGrid: Grid[Fixed, D3],
      model: FlashalignModel,
      rowMajor: Vector[Double],
      rigidTolerance: Option[Double],
      report: OptimizationReport,
      diagnostics: FlashalignDiagnostics
  ): Either[FlashalignRecordError, FlashalignLinearResultRecord] =
    if !movingGrid.frame.sameRuntimeOwnerAs(moving) then
      Left(FlashalignRecordError.GridFrameOwnerMismatch(FlashalignEndpoint.Moving))
    else if !fixedGrid.frame.sameRuntimeOwnerAs(fixed) then
      Left(FlashalignRecordError.GridFrameOwnerMismatch(FlashalignEndpoint.Fixed))
    else
      for
        _ <- validateMillimetres(FlashalignEndpoint.Moving, moving.unit)
        _ <- validateMillimetres(FlashalignEndpoint.Fixed, fixed.unit)
        movingFrame <- moving.record.left.map(_ =>
          FlashalignRecordError.PersistentFrameRequired(
            FlashalignEndpoint.Moving,
            FrameOwnerDescriptor.of(moving)
          )
        )
        fixedFrame <- fixed.record.left.map(_ =>
          FlashalignRecordError.PersistentFrameRequired(
            FlashalignEndpoint.Fixed,
            FrameOwnerDescriptor.of(fixed)
          )
        )
        movingGridRecord <- movingGrid.record.left.map(_ =>
          FlashalignRecordError.PersistentGridRequired(FlashalignEndpoint.Moving)
        )
        fixedGridRecord <- fixedGrid.record.left.map(_ =>
          FlashalignRecordError.PersistentGridRequired(FlashalignEndpoint.Fixed)
        )
        _ <- Either.cond(
          diagnostics.model == model,
          (),
          FlashalignRecordError.DiagnosticsModelMismatch(model, diagnostics.model)
        )
      yield
        FlashalignLinearResultRecord(
          CurrentVersion,
          model,
          CurrentDirection,
          CurrentConvention,
          LengthUnit.Millimeter,
          movingFrame,
          fixedFrame,
          movingGridRecord,
          fixedGridRecord,
          rowMajor,
          rigidTolerance,
          FlashalignOptimizationRecord.from(report),
          diagnostics
        )

  private def validateRecord(
      record: FlashalignLinearResultRecord,
      expectedModel: FlashalignModel
  ): Either[FlashalignRecordError, Unit] =
    if record.version != CurrentVersion then
      Left(FlashalignRecordError.UnsupportedVersion(record.version, CurrentVersion))
    else if record.model != expectedModel then
      Left(FlashalignRecordError.ModelMismatch(expectedModel, record.model))
    else if record.direction != CurrentDirection then
      Left(FlashalignRecordError.DirectionMismatch(record.direction))
    else if record.convention != CurrentConvention then
      Left(FlashalignRecordError.ConventionMismatch(record.convention))
    else if record.diagnostics.model != record.model then
      Left(
        FlashalignRecordError.DiagnosticsModelMismatch(
          record.model,
          record.diagnostics.model
        )
      )
    else
      for
        _ <- validateMillimetres(FlashalignEndpoint.Moving, record.coordinateUnit)
        _ <- validateMillimetres(FlashalignEndpoint.Moving, record.movingFrame.key.unit)
        _ <- validateMillimetres(FlashalignEndpoint.Fixed, record.fixedFrame.key.unit)
      yield ()

  private def validateMillimetres(
      endpoint: FlashalignEndpoint,
      unit: LengthUnit
  ): Either[FlashalignRecordError, Unit] =
    Either.cond(
      unit == LengthUnit.Millimeter,
      (),
      FlashalignRecordError.UnitMismatch(endpoint, unit)
    )

object FlashalignOutput:
  def rigidPlan[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      Sem,
      S <: SampleSpace[Moving, D3],
      R <: AnyRank
  ](
      originalMoving: Sampled[S, Double, Sem, R],
      fixedGrid: Grid[Fixed, D3],
      result: RigidFlashalignResult[Moving, Fixed],
      interpolation: Interpolation[Sem],
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using
      Dimension[D3],
      ValueSemantics[Double, Sem]
  ): Either[ResamplingError, ResamplingPlan[Moving, Fixed, D3, Sem, R]] =
    ResamplingPlan.affine(
      originalMoving,
      fixedGrid,
      result.fixedToMoving,
      interpolation,
      boundary
    )

  def affinePlan[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      Sem,
      S <: SampleSpace[Moving, D3],
      R <: AnyRank
  ](
      originalMoving: Sampled[S, Double, Sem, R],
      fixedGrid: Grid[Fixed, D3],
      result: AffineFlashalignResult[Moving, Fixed],
      interpolation: Interpolation[Sem],
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using
      Dimension[D3],
      ValueSemantics[Double, Sem]
  ): Either[ResamplingError, ResamplingPlan[Moving, Fixed, D3, Sem, R]] =
    ResamplingPlan.affine(
      originalMoving,
      fixedGrid,
      result.fixedToMoving,
      interpolation,
      boundary
    )
