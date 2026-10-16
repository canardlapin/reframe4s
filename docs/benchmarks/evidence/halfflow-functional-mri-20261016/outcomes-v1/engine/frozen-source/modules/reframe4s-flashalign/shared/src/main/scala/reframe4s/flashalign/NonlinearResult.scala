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
import image4s.geometry.Point
import ravel.AnyRank
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import reframe4s.lie.FramedAffine
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingError
import reframe4s.resample.ResamplingPlan
import reframe4s.resample.ResamplingResult

enum FlashalignNonlinearModel derives CanEqual:
  case PhaseEncodingField
  case SmallStrainAnatomicalField

enum FlashalignNonlinearFieldKind derives CanEqual:
  case ScalarPhaseEncodingDisplacement
  case VectorSmallStrainDisplacement

enum FlashalignAffineComponentRole derives CanEqual:
  case GlobalPoseComponentOnly

final case class FlashalignAffineComponentRecord(
    role: FlashalignAffineComponentRole,
    rowMajor: Vector[Double]
) derives CanEqual

final case class FlashalignWorldDomainRecord(
    id: String,
    originWorldMm: Vector[Double],
    axes: Vector[Vector[Double]],
    lowerCoordinatesMm: Vector[Double],
    upperCoordinatesMm: Vector[Double],
    boundaryToleranceMm: Double
) derives CanEqual

final case class FlashalignPhaseEncodingRecord(
    voxelAxis: Int,
    polarity: Int,
    unitMovingWorld: Vector[Double]
) derives CanEqual

final case class FlashalignNonlinearFieldRecord(
    kind: FlashalignNonlinearFieldKind,
    basisId: String,
    coefficientsMm: Vector[Double],
    gaugeConventionId: String,
    extensionId: String,
    sourceDomain: FlashalignWorldDomainRecord,
    fixedDomain: FlashalignWorldDomainRecord,
    phaseEncoding: Option[FlashalignPhaseEncodingRecord]
) derives CanEqual

final case class FlashalignGeometryCertificateRecord(
    implementationRevision: String,
    coefficientHash: String,
    valid: Boolean,
    certifiedDerivativeBound: Double,
    minimumJacobianOrSingularValueBound: Double,
    certificateMargin: Double,
    maximumDisplacementMm: Double,
    evidenceKind: String
) derives CanEqual

final case class FlashalignInverseEvidenceRecord(
    implementationRevision: String,
    converged: Boolean,
    maximumResidualMm: Double,
    meanResidualMm: Double,
    sampleCount: Long,
    coveredFraction: Double,
    maximumIterations: Int,
    maximumResidualCriterionMm: Double,
    minimumCoveredFraction: Double,
    evidenceKind: String
) derives CanEqual:
  def admitted: Boolean =
    converged &&
      maximumResidualMm <= maximumResidualCriterionMm &&
      coveredFraction >= minimumCoveredFraction

final case class FlashalignNonlinearResultRecord(
    version: Int,
    model: FlashalignNonlinearModel,
    modelId: String,
    direction: FlashalignTransformDirection,
    convention: FlashalignTransformConvention,
    coordinateUnit: LengthUnit,
    movingFrame: FrameRecord,
    fixedFrame: FrameRecord,
    movingGrid: GridRecord,
    fixedGrid: GridRecord,
    affineComponent: FlashalignAffineComponentRecord,
    field: FlashalignNonlinearFieldRecord,
    geometryCertificate: FlashalignGeometryCertificateRecord,
    inverseEvidence: FlashalignInverseEvidenceRecord
) derives CanEqual:
  def completeMatrixExport: Either[FlashalignNonlinearExportError, Vector[Double]] =
    Left(FlashalignNonlinearExportError.UnsupportedCompleteMatrix(model))

  def namedAffineComponentExport: FlashalignAffineComponentRecord =
    affineComponent

sealed trait FlashalignNonlinearExportError derives CanEqual:
  def message: String

object FlashalignNonlinearExportError:
  final case class UnsupportedCompleteMatrix(model: FlashalignNonlinearModel)
      extends FlashalignNonlinearExportError:
    val message: String =
      s"$model is nonlinear and cannot be exported as a complete affine matrix; request GlobalPoseComponentOnly explicitly"

sealed trait FlashalignNonlinearRecordError derives CanEqual:
  def message: String

object FlashalignNonlinearRecordError:
  final case class UnsupportedVersion(actual: Int, supported: Int)
      extends FlashalignNonlinearRecordError:
    val message = s"Flashalign nonlinear record version $actual is unsupported; expected $supported"

  final case class InvalidMetadata(detail: String)
      extends FlashalignNonlinearRecordError:
    val message = detail

  final case class PersistentFrameRequired(endpoint: FlashalignEndpoint)
      extends FlashalignNonlinearRecordError:
    val message = s"$endpoint nonlinear result frame must have persistent identity"

  final case class PersistentGridRequired(endpoint: FlashalignEndpoint)
      extends FlashalignNonlinearRecordError:
    val message = s"$endpoint nonlinear result grid must have persistent identity"

  final case class GridFrameOwnerMismatch(endpoint: FlashalignEndpoint)
      extends FlashalignNonlinearRecordError:
    val message = s"$endpoint nonlinear result grid does not belong to the result frame owner"

  object Geometry:
    def apply(error: GeometryError | spatial4s.SpatialError): Geometry =
      new Geometry(image4s.geometry.GeometryError.fromCoordinate(error))

  final case class Geometry(error: GeometryError)
      extends FlashalignNonlinearRecordError:
    val message = error.message

final class RestoredFlashalignNonlinearRecord private[flashalign] (
    val record: FlashalignNonlinearResultRecord,
    val movingGrid: Grid[Frame[D3], D3],
    val fixedGrid: Grid[Frame[D3], D3],
    val affineComponent: FramedAffine[Frame[D3], Frame[D3], D3],
    val frameRegistry: FrameRegistry,
    val gridRegistry: GridRegistry
)

object FlashalignNonlinearResultRecord:
  val CurrentVersion = 1

  def restore(
      record: FlashalignNonlinearResultRecord,
      frames: FrameRegistry,
      grids: GridRegistry
  )(using Dimension[D3]): Either[
    FlashalignNonlinearRecordError,
    RestoredFlashalignNonlinearRecord
  ] =
    for
      _ <- validate(record)
      moving <- Frame
        .restore[D3](record.movingFrame, frames)
        .left
        .map(FlashalignNonlinearRecordError.Geometry.apply)
      fixed <- Frame
        .restore[D3](record.fixedFrame, moving.registry)
        .left
        .map(FlashalignNonlinearRecordError.Geometry.apply)
      movingGrid <- Grid
        .restore[D3, Frame[D3]](record.movingGrid, moving.frame, grids)
        .left
        .map(FlashalignNonlinearRecordError.Geometry.apply)
      fixedGrid <- Grid
        .restore[D3, Frame[D3]](
          record.fixedGrid,
          fixed.frame,
          movingGrid.registry
        )
        .left
        .map(FlashalignNonlinearRecordError.Geometry.apply)
      operator <- Affine
        .fromRowMajor[D3](record.affineComponent.rowMajor)
        .left
        .map(FlashalignNonlinearRecordError.Geometry.apply)
      component = FramedAffine.betweenFrames(moving.frame, fixed.frame)(operator)
    yield new RestoredFlashalignNonlinearRecord(
      record,
      movingGrid.grid,
      fixedGrid.grid,
      component,
      fixed.registry,
      fixedGrid.registry
    )

  private[flashalign] def build[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      model: FlashalignNonlinearModel,
      modelId: String,
      moving: Moving,
      fixed: Fixed,
      movingGrid: Grid[Moving, D3],
      fixedGrid: Grid[Fixed, D3],
      affineComponent: FlashalignAffineComponentRecord,
      field: FlashalignNonlinearFieldRecord,
      certificate: FlashalignGeometryCertificateRecord,
      inverse: FlashalignInverseEvidenceRecord
  ): Either[FlashalignNonlinearRecordError, FlashalignNonlinearResultRecord] =
    if !movingGrid.frame.sameRuntimeOwnerAs(moving) then
      Left(FlashalignNonlinearRecordError.GridFrameOwnerMismatch(FlashalignEndpoint.Moving))
    else if !fixedGrid.frame.sameRuntimeOwnerAs(fixed) then
      Left(FlashalignNonlinearRecordError.GridFrameOwnerMismatch(FlashalignEndpoint.Fixed))
    else
      for
        movingFrame <- moving.record.left.map(_ =>
          FlashalignNonlinearRecordError.PersistentFrameRequired(FlashalignEndpoint.Moving)
        )
        fixedFrame <- fixed.record.left.map(_ =>
          FlashalignNonlinearRecordError.PersistentFrameRequired(FlashalignEndpoint.Fixed)
        )
        movingGridRecord <- movingGrid.record.left.map(_ =>
          FlashalignNonlinearRecordError.PersistentGridRequired(FlashalignEndpoint.Moving)
        )
        fixedGridRecord <- fixedGrid.record.left.map(_ =>
          FlashalignNonlinearRecordError.PersistentGridRequired(FlashalignEndpoint.Fixed)
        )
        record = FlashalignNonlinearResultRecord(
          CurrentVersion,
          model,
          modelId,
          FlashalignTransformDirection.MovingWorldToFixedWorld,
          FlashalignTransformConvention.RowMajorYEqualsTransformXMillimetresV1,
          LengthUnit.Millimeter,
          movingFrame,
          fixedFrame,
          movingGridRecord,
          fixedGridRecord,
          affineComponent,
          field,
          certificate,
          inverse
        )
        _ <- validate(record)
      yield record

  private def validate(
      record: FlashalignNonlinearResultRecord
  ): Either[FlashalignNonlinearRecordError, Unit] =
    val field = record.field
    val inverse = record.inverseEvidence
    val vectors = Vector(
      field.sourceDomain.originWorldMm,
      field.sourceDomain.lowerCoordinatesMm,
      field.sourceDomain.upperCoordinatesMm,
      field.fixedDomain.originWorldMm,
      field.fixedDomain.lowerCoordinatesMm,
      field.fixedDomain.upperCoordinatesMm
    ) ++ field.sourceDomain.axes ++ field.fixedDomain.axes
    if record.version != CurrentVersion then
      Left(FlashalignNonlinearRecordError.UnsupportedVersion(record.version, CurrentVersion))
    else if record.direction != FlashalignTransformDirection.MovingWorldToFixedWorld ||
        record.convention != FlashalignTransformConvention.RowMajorYEqualsTransformXMillimetresV1
    then Left(FlashalignNonlinearRecordError.InvalidMetadata("unsupported transform direction or convention"))
    else if record.coordinateUnit != LengthUnit.Millimeter ||
        record.movingFrame.key.unit != LengthUnit.Millimeter ||
        record.fixedFrame.key.unit != LengthUnit.Millimeter
    then Left(FlashalignNonlinearRecordError.InvalidMetadata("all nonlinear coordinates must be world millimetres"))
    else if record.modelId.trim.isEmpty || field.basisId.trim.isEmpty ||
        field.gaugeConventionId.trim.isEmpty || field.extensionId.trim.isEmpty
    then Left(FlashalignNonlinearRecordError.InvalidMetadata("model, basis, gauge and extension identities must be nonempty"))
    else if field.coefficientsMm.isEmpty || field.coefficientsMm.exists(value => !value.isFinite) then
      Left(FlashalignNonlinearRecordError.InvalidMetadata("nonlinear coefficients must be nonempty and finite"))
    else if !validDomain(field.sourceDomain) || !validDomain(field.fixedDomain) ||
        vectors.exists(value => value.length != 3 || value.exists(v => !v.isFinite))
    then
      Left(FlashalignNonlinearRecordError.InvalidMetadata("domain coordinates and axes must be finite triples"))
    else if !validCertificate(record.geometryCertificate) then
      Left(FlashalignNonlinearRecordError.InvalidMetadata("nonlinear record requires a valid geometry certificate"))
    else if !validInverse(inverse) || !inverse.admitted then
      Left(FlashalignNonlinearRecordError.InvalidMetadata("nonlinear record requires admitted numerical inverse evidence"))
    else if record.model == FlashalignNonlinearModel.PhaseEncodingField &&
        field.kind != FlashalignNonlinearFieldKind.ScalarPhaseEncodingDisplacement
    then Left(FlashalignNonlinearRecordError.InvalidMetadata("PE model requires a scalar PE field"))
    else if record.model == FlashalignNonlinearModel.SmallStrainAnatomicalField &&
        field.kind != FlashalignNonlinearFieldKind.VectorSmallStrainDisplacement
    then Left(FlashalignNonlinearRecordError.InvalidMetadata("small-strain model requires a vector field"))
    else if field.kind == FlashalignNonlinearFieldKind.ScalarPhaseEncodingDisplacement &&
        !field.phaseEncoding.exists(validPhaseEncoding)
    then
      Left(FlashalignNonlinearRecordError.InvalidMetadata("PE field record requires acquisition phase-encoding metadata"))
    else if field.kind == FlashalignNonlinearFieldKind.VectorSmallStrainDisplacement && field.phaseEncoding.nonEmpty then
      Left(FlashalignNonlinearRecordError.InvalidMetadata("anatomical field record cannot carry PE metadata"))
    else
      Affine
        .fromRowMajor[D3](record.affineComponent.rowMajor)
        .left
        .map(FlashalignNonlinearRecordError.Geometry.apply)
        .map(_ => ())

  private def validDomain(value: FlashalignWorldDomainRecord): Boolean =
    value.id.trim.nonEmpty &&
      value.boundaryToleranceMm.isFinite && value.boundaryToleranceMm >= 0.0 &&
      value.originWorldMm.length == 3 && value.axes.length == 3 &&
      value.lowerCoordinatesMm.length == 3 && value.upperCoordinatesMm.length == 3 &&
      value.lowerCoordinatesMm.indices.forall(axis =>
        value.lowerCoordinatesMm(axis) < value.upperCoordinatesMm(axis)
      ) &&
      value.axes.indices.forall(row => value.axes.indices.forall(column =>
        val expected = if row == column then 1.0 else 0.0
        val actual = value.axes(row).indices.map(index =>
          value.axes(row)(index) * value.axes(column)(index)
        ).sum
        math.abs(actual - expected) <= 1e-10
      ))

  private def validCertificate(
      value: FlashalignGeometryCertificateRecord
  ): Boolean =
    value.valid &&
      value.implementationRevision.trim.nonEmpty &&
      value.coefficientHash.trim.nonEmpty &&
      value.evidenceKind.trim.nonEmpty &&
      value.certifiedDerivativeBound.isFinite && value.certifiedDerivativeBound >= 0.0 &&
      value.minimumJacobianOrSingularValueBound.isFinite &&
      value.minimumJacobianOrSingularValueBound > 0.0 &&
      value.certificateMargin.isFinite && value.certificateMargin >= 0.0 &&
      value.maximumDisplacementMm.isFinite && value.maximumDisplacementMm > 0.0

  private def validInverse(value: FlashalignInverseEvidenceRecord): Boolean =
    value.implementationRevision.trim.nonEmpty &&
      value.evidenceKind.trim.nonEmpty &&
      value.maximumResidualMm.isFinite && value.maximumResidualMm >= 0.0 &&
      value.meanResidualMm.isFinite && value.meanResidualMm >= 0.0 &&
      value.meanResidualMm <= value.maximumResidualMm &&
      value.sampleCount > 0L &&
      value.coveredFraction.isFinite &&
      value.coveredFraction >= 0.0 && value.coveredFraction <= 1.0 &&
      value.maximumIterations > 0 &&
      value.maximumResidualCriterionMm.isFinite && value.maximumResidualCriterionMm >= 0.0 &&
      value.minimumCoveredFraction.isFinite && value.minimumCoveredFraction >= 0.0 &&
      value.minimumCoveredFraction <= 1.0

  private def validPhaseEncoding(value: FlashalignPhaseEncodingRecord): Boolean =
    value.voxelAxis >= 0 && value.voxelAxis < 3 &&
      math.abs(value.polarity) == 1 &&
      value.unitMovingWorld.length == 3 &&
      value.unitMovingWorld.forall(_.isFinite) &&
      math.abs(math.sqrt(value.unitMovingWorld.map(x => x * x).sum) - 1.0) <= 1e-10

private[flashalign] sealed trait NonlinearResultError derives CanEqual:
  def message: String

private[flashalign] object NonlinearResultError:
  final case class Geometry(error: GeometryOperatorError)
      extends NonlinearResultError:
    val message = error.message

  final case class InvalidInverseEvidence(detail: String)
      extends NonlinearResultError:
    val message = detail

private[flashalign] final class NonlinearFlashalignResult3[
    State,
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val model: FlashalignNonlinearModel,
    val operator: GeometryPointOperator3[State, Moving, Fixed],
    val state: State,
    val affineComponent: FlashalignAffineComponentRecord,
    val field: FlashalignNonlinearFieldRecord,
    val geometryCertificate: FlashalignGeometryCertificateRecord,
    val inverseEvidence: FlashalignInverseEvidenceRecord
):
  def movingToFixed: SpatialMap[Moving, Fixed, D3] =
    GeometrySpatialMap3.forward(operator, state)

  def fixedToMoving: SpatialMap[Fixed, Moving, D3] =
    GeometrySpatialMap3.inverse(operator, state)

  def completeMatrixExport: Either[FlashalignNonlinearExportError, Vector[Double]] =
    Left(FlashalignNonlinearExportError.UnsupportedCompleteMatrix(model))

  def namedAffineComponentExport: FlashalignAffineComponentRecord = affineComponent

  def record(
      movingGrid: Grid[Moving, D3],
      fixedGrid: Grid[Fixed, D3]
  ): Either[FlashalignNonlinearRecordError, FlashalignNonlinearResultRecord] =
    FlashalignNonlinearResultRecord.build(
      model,
      operator.modelId,
      operator.moving,
      operator.fixed,
      movingGrid,
      fixedGrid,
      affineComponent,
      field,
      geometryCertificate,
      inverseEvidence
    )

private[flashalign] object NonlinearFlashalignResult3:
  def peField[Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: PeFieldModel3[Moving, Fixed],
      state: PeFieldState3[Moving, Fixed],
      config: PeGeometryConfig,
      inverseEvidence: FlashalignInverseEvidenceRecord
  ): Either[
    NonlinearResultError,
    NonlinearFlashalignResult3[PeFieldState3[Moving, Fixed], Moving, Fixed]
  ] =
    val operator = GeometryPointOperator3.peField(model, config)
    for
      certificate <- PeGeometryCertificate
        .evaluate(model, state, config.certificate)
        .left
        .map(error => NonlinearResultError.Geometry(GeometryOperatorError.Nonlinear(error.message)))
      _ <- requireResultEvidence(certificate.valid, inverseEvidence)
    yield
      val source = domainRecord(config.sourceDomain)
      val fixed = domainRecord(config.fixedDomain)
      val field = FlashalignNonlinearFieldRecord(
        FlashalignNonlinearFieldKind.ScalarPhaseEncodingDisplacement,
        model.basisId,
        state.field.coefficientsMm,
        model.gaugeConventionId,
        model.basis.domain.extensionId,
        source,
        fixed,
        Some(
          FlashalignPhaseEncodingRecord(
            model.phaseEncoding.voxelAxis,
            model.phaseEncoding.polarity,
            model.phaseEncoding.unitMovingWorld
          )
        )
      )
      val recordedCertificate = FlashalignGeometryCertificateRecord(
        certificate.implementationRevision,
        certificate.coefficientHash,
        certificate.valid,
        certificate.certifiedDirectionalDerivativeBound,
        certificate.minimumDirectionalJacobian,
        certificate.certifiedJacobianMargin,
        certificate.maximumDisplacementMm,
        certificate.evidenceKind
      )
      new NonlinearFlashalignResult3(
        FlashalignNonlinearModel.PhaseEncodingField,
        operator,
        state,
        FlashalignAffineComponentRecord(
          FlashalignAffineComponentRole.GlobalPoseComponentOnly,
          state.pose.operator.rowMajor
        ),
        field,
        recordedCertificate,
        inverseEvidence
      )

  def smallStrain[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: SmallStrainModel3[Pose, Moving, Fixed],
      state: SmallStrainState3[Pose],
      config: SmallStrainGeometryConfig,
      inverseEvidence: FlashalignInverseEvidenceRecord
  ): Either[
    NonlinearResultError,
    NonlinearFlashalignResult3[SmallStrainState3[Pose], Moving, Fixed]
  ] =
    val operator = GeometryPointOperator3.smallStrain(model, config)
    for
      certificate <- SmallStrainGeometryCertificate
        .evaluate(model, state, config.certificate)
        .left
        .map(error => NonlinearResultError.Geometry(GeometryOperatorError.Nonlinear(error.message)))
      _ <- requireResultEvidence(certificate.valid, inverseEvidence)
    yield
      val field = FlashalignNonlinearFieldRecord(
        FlashalignNonlinearFieldKind.VectorSmallStrainDisplacement,
        model.basisId,
        state.field.coefficientsMm,
        model.gaugeConventionId,
        model.basis.domain.extensionId,
        domainRecord(config.sourceDomain),
        domainRecord(config.fixedDomain),
        None
      )
      val recordedCertificate = FlashalignGeometryCertificateRecord(
        certificate.implementationRevision,
        certificate.coefficientHash,
        certificate.valid,
        certificate.certifiedGradientBound,
        certificate.compositeMinimumSingularValueBound,
        certificate.gradientMargin,
        certificate.maximumDisplacementMm,
        certificate.evidenceKind
      )
      new NonlinearFlashalignResult3(
        FlashalignNonlinearModel.SmallStrainAnatomicalField,
        operator,
        state,
        FlashalignAffineComponentRecord(
          FlashalignAffineComponentRole.GlobalPoseComponentOnly,
          model.poseMatrix(state.pose)
        ),
        field,
        recordedCertificate,
        inverseEvidence
      )

  private def requireResultEvidence(
      certificateValid: Boolean,
      inverse: FlashalignInverseEvidenceRecord
  ): Either[NonlinearResultError, Unit] =
    if !certificateValid then
      Left(NonlinearResultError.InvalidInverseEvidence("geometry certificate is invalid"))
    else if !validInverseNumbers(inverse) || !inverse.admitted then
      Left(NonlinearResultError.InvalidInverseEvidence("inverse evidence is invalid or fails its admission criteria"))
    else Right(())

  private def validInverseNumbers(value: FlashalignInverseEvidenceRecord): Boolean =
    value.implementationRevision.trim.nonEmpty &&
      value.evidenceKind.trim.nonEmpty &&
      value.maximumResidualMm.isFinite && value.maximumResidualMm >= 0.0 &&
      value.meanResidualMm.isFinite && value.meanResidualMm >= 0.0 &&
      value.meanResidualMm <= value.maximumResidualMm &&
      value.sampleCount > 0L &&
      value.coveredFraction.isFinite && value.coveredFraction >= 0.0 && value.coveredFraction <= 1.0 &&
      value.maximumIterations > 0 &&
      value.maximumResidualCriterionMm.isFinite && value.maximumResidualCriterionMm >= 0.0 &&
      value.minimumCoveredFraction.isFinite && value.minimumCoveredFraction >= 0.0 &&
      value.minimumCoveredFraction <= 1.0

  private def domainRecord(value: PeWorldDomain3): FlashalignWorldDomainRecord =
    FlashalignWorldDomainRecord(
      value.id,
      value.originWorldMm,
      value.axes,
      value.lowerCoordinatesMm,
      value.upperCoordinatesMm,
      value.boundaryToleranceMm
    )

  private def domainRecord(
      value: SmallStrainWorldDomain3
  ): FlashalignWorldDomainRecord =
    FlashalignWorldDomainRecord(
      value.id,
      value.originWorldMm,
      value.axes,
      value.lowerCoordinatesMm,
      value.upperCoordinatesMm,
      value.boundaryToleranceMm
    )

private final class GeometrySpatialMap3[
    State,
    Moving <: Frame[D3],
    Fixed <: Frame[D3],
    From <: Frame[D3],
    To <: Frame[D3]
] private (
    operator: GeometryPointOperator3[State, Moving, Fixed],
    state: State,
    val source: From,
    val target: To,
    inverse: Boolean
) extends SpatialMap[From, To, D3]:
  private val inputCoordinates = new Array[Double](3)
  private val outputCoordinates = new Array[Double](3)
  private val workspace = operator.newWorkspace()
  private val forwardInput = WorldPointBatch3.wrapOwned(operator.moving, inputCoordinates)
  private val inverseInput = WorldPointBatch3.wrapOwned(operator.fixed, inputCoordinates)
  private val forwardOutput = GeometryOutputBuffer3.wrapOwned(operator.fixed, outputCoordinates)
  private val inverseOutput = GeometryOutputBuffer3.wrapOwned(operator.moving, outputCoordinates)
  private var failure = Option.empty[GeometryOperatorError]

  private[flashalign] def lastFailure: Option[GeometryOperatorError] = failure

  def apply(point: Point[From, D3]): Either[MapError, Point[To, D3]] = this.synchronized {
    failure = None
    if !point.frame.sameRuntimeOwnerAs(source) then
      Left(MapError.SourceFrameOwnerMismatch(FrameOwnerDescriptor.of(source)))
    else
      var axis = 0
      while axis < 3 do
        inputCoordinates(axis) = point.coordinates(axis)
        axis += 1
      val mapped =
        if inverse then operator.inverseMap(state, inverseInput, inverseOutput, workspace)
        else operator.map(state, forwardInput, forwardOutput, workspace)
      mapped match
        case Left(error) =>
          failure = Some(error)
          Left(MapError.OutsideDomain(point.coordinates))
        case Right(()) =>
          Point
            .fromVector(target, outputCoordinates.toVector)
            .left
            .map(MapError.Geometry.apply)
            .flatMap { exact =>
              Frame
                .alignOwners[D3, target.type, To](target, target)
                .left
                .map(MapError.Geometry.apply)
                .flatMap(_.pointToRight(exact).left.map(MapError.Geometry.apply))
            }
  }

private object GeometrySpatialMap3:
  def forward[State, Moving <: Frame[D3], Fixed <: Frame[D3]](
      operator: GeometryPointOperator3[State, Moving, Fixed],
      state: State
  ): GeometrySpatialMap3[State, Moving, Fixed, Moving, Fixed] =
    new GeometrySpatialMap3(operator, state, operator.moving, operator.fixed, false)

  def inverse[State, Moving <: Frame[D3], Fixed <: Frame[D3]](
      operator: GeometryPointOperator3[State, Moving, Fixed],
      state: State
  ): GeometrySpatialMap3[State, Moving, Fixed, Fixed, Moving] =
    new GeometrySpatialMap3(operator, state, operator.fixed, operator.moving, true)

private[flashalign] final case class NonlinearOutputBudget(maximumIncrementalBytes: Long)

private[flashalign] object NonlinearOutputBudget:
  def create(maximumIncrementalBytes: Long): Either[NonlinearOutputError, NonlinearOutputBudget] =
    if maximumIncrementalBytes <= 0L then
      Left(NonlinearOutputError.InvalidBudget(maximumIncrementalBytes))
    else Right(NonlinearOutputBudget(maximumIncrementalBytes))

private[flashalign] final case class NonlinearOutputCost(
    targetVoxelCount: Long,
    outputElementCount: Long,
    coordinateArrayBytes: Long,
    outputBytes: Long,
    validityBytes: Long,
    estimatedIncrementalPeakBytes: Long,
    materializedCoordinateCount: Long,
    finalInterpolationPasses: Int,
    sourceInterpolationEvaluations: Long
) derives CanEqual

private[flashalign] sealed trait NonlinearOutputError derives CanEqual:
  def message: String

private[flashalign] object NonlinearOutputError:
  final case class InvalidBudget(value: Long) extends NonlinearOutputError:
    val message = s"nonlinear output memory budget must be positive, got $value"

  final case class SizeOverflow(component: String) extends NonlinearOutputError:
    val message = s"nonlinear output $component exceeds the signed 64-bit accounting range"

  final case class UnsupportedElementCount(component: String, count: Long)
      extends NonlinearOutputError:
    val message = s"nonlinear output $component count $count exceeds the materialized Int-indexed limit"

  final case class BudgetExceeded(required: Long, available: Long)
      extends NonlinearOutputError:
    val message = s"nonlinear output requires at least $required incremental bytes, budget is $available"

  final case class InverseMapping(error: GeometryOperatorError)
      extends NonlinearOutputError:
    val message = s"nonlinear inverse mapping failed before interpolation: ${error.message}"

  final case class Resampling(error: ResamplingError)
      extends NonlinearOutputError:
    val message = error.message

  final case class CoordinateCountMismatch(expected: Long, actual: Long)
      extends NonlinearOutputError:
    val message = s"mapped plan materialized $actual coordinates, expected $expected"

private[flashalign] final case class NonlinearOutputResult[
    Fixed <: Frame[D3],
    Sem
](
    output: ResamplingResult[Fixed, D3, Sem],
    cost: NonlinearOutputCost,
    validityPreserved: Boolean
)

private[flashalign] final class NonlinearOutputPlan3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3],
    Sem,
    S <: SampleSpace[Moving, D3],
    R <: AnyRank
] private (
    plan: ResamplingPlan[Moving, Fixed, D3, Sem, R],
    val cost: NonlinearOutputCost
):
  def run(): Either[NonlinearOutputError, NonlinearOutputResult[Fixed, Sem]] =
    plan
      .run(plan.newWorkspace())
      .left
      .map(NonlinearOutputError.Resampling.apply)
      .map(result => NonlinearOutputResult(result, cost, validityPreserved = true))

private[flashalign] object NonlinearOutputPlan3:
  def compile[
      State,
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      Sem,
      S <: SampleSpace[Moving, D3],
      R <: AnyRank
  ](
      originalMoving: Sampled[S, Double, Sem, R],
      fixedGrid: Grid[Fixed, D3],
      result: NonlinearFlashalignResult3[State, Moving, Fixed],
      interpolation: Interpolation[Sem],
      boundary: BoundaryPolicy[Double],
      budget: NonlinearOutputBudget
  )(using
      Dimension[D3],
      ValueSemantics[Double, Sem]
  ): Either[NonlinearOutputError, NonlinearOutputPlan3[Moving, Fixed, Sem, S, R]] =
    for
      targetVoxels <- product(fixedGrid.shape.map(_.toLong), "target voxel count")
      nonSpatial <- product(originalMoving.nonSpatialAxes.shape.map(_.toLong), "non-spatial element count")
      outputElements <- multiply(targetVoxels, nonSpatial, "output element count")
      _ <- requireMaterializable(targetVoxels, "target coordinate")
      _ <- requireMaterializable(outputElements, "output element")
      coordinateBytes <- multiply(targetVoxels, 24L, "coordinate arrays")
      outputBytes <- multiply(outputElements, 8L, "output values")
      validityBytes <- multiply(outputElements, 8L, "validity values")
      outputAndValidity <- add(outputBytes, validityBytes, "output and validity buffers")
      peak <- add(coordinateBytes, outputAndValidity, "incremental peak")
      _ <- Either.cond(
        peak <= budget.maximumIncrementalBytes,
        (),
        NonlinearOutputError.BudgetExceeded(peak, budget.maximumIncrementalBytes)
      )
      pull = GeometrySpatialMap3.inverse(result.operator, result.state)
      compiled <- ResamplingPlan
        .mapped(originalMoving, fixedGrid, pull, interpolation, boundary)
        .left
        .map(error =>
          pull.lastFailure match
            case Some(failure) => NonlinearOutputError.InverseMapping(failure)
            case None          => NonlinearOutputError.Resampling(error)
        )
      actual = compiled.structure.materializedCoordinateCount.toLong
      _ <- Either.cond(
        actual == targetVoxels,
        (),
        NonlinearOutputError.CoordinateCountMismatch(targetVoxels, actual)
      )
      cost = NonlinearOutputCost(
        targetVoxels,
        outputElements,
        coordinateBytes,
        outputBytes,
        validityBytes,
        peak,
        actual,
        finalInterpolationPasses = 1,
        sourceInterpolationEvaluations = outputElements
      )
    yield new NonlinearOutputPlan3(compiled, cost)

  private def product(
      values: Vector[Long],
      component: String
  ): Either[NonlinearOutputError, Long] =
    values.foldLeft[Either[NonlinearOutputError, Long]](Right(1L))((result, value) =>
      result.flatMap(current => multiply(current, value, component))
    )

  private def multiply(
      left: Long,
      right: Long,
      component: String
  ): Either[NonlinearOutputError, Long] =
    if left < 0L || right < 0L || (left != 0L && right > Long.MaxValue / left) then
      Left(NonlinearOutputError.SizeOverflow(component))
    else Right(left * right)

  private def add(
      left: Long,
      right: Long,
      component: String
  ): Either[NonlinearOutputError, Long] =
    if left < 0L || right < 0L || right > Long.MaxValue - left then
      Left(NonlinearOutputError.SizeOverflow(component))
    else Right(left + right)

  private def requireMaterializable(
      count: Long,
      component: String
  ): Either[NonlinearOutputError, Unit] =
    Either.cond(
      count <= Int.MaxValue.toLong,
      (),
      NonlinearOutputError.UnsupportedElementCount(component, count)
    )
