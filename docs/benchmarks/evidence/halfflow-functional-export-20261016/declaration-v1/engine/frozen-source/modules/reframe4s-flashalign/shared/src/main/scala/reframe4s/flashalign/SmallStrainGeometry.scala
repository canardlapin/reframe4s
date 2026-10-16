package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame

private[flashalign] final case class SmallStrainWorldDomain3(
    id: String,
    originWorldMm: Vector[Double],
    axes: Vector[Vector[Double]],
    lowerCoordinatesMm: Vector[Double],
    upperCoordinatesMm: Vector[Double],
    boundaryToleranceMm: Double
):
  def contains(x: Double, y: Double, z: Double): Boolean =
    val offset = Vector(x - originWorldMm(0), y - originWorldMm(1), z - originWorldMm(2))
    axes.indices.forall { axis =>
      val coordinate = dot(axes(axis), offset)
      coordinate >= lowerCoordinatesMm(axis) - boundaryToleranceMm &&
        coordinate <= upperCoordinatesMm(axis) + boundaryToleranceMm
    }

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

private[flashalign] object SmallStrainWorldDomain3:
  def create(
      id: String,
      originWorldMm: Vector[Double],
      axes: Vector[Vector[Double]],
      lowerCoordinatesMm: Vector[Double],
      upperCoordinatesMm: Vector[Double],
      boundaryToleranceMm: Double = 1e-9
  ): Either[SmallStrainGeometryError, SmallStrainWorldDomain3] =
    val vectors = Vector(originWorldMm, lowerCoordinatesMm, upperCoordinatesMm) ++ axes
    if id.trim.isEmpty then Left(SmallStrainGeometryError.InvalidDomain("domain ID is empty"))
    else if axes.length != 3 || vectors.exists(_.length != 3) then
      Left(SmallStrainGeometryError.InvalidDomain("coordinates and axes must be triples"))
    else if vectors.flatten.exists(value => !value.isFinite) then
      Left(SmallStrainGeometryError.InvalidDomain("domain coordinates must be finite"))
    else if lowerCoordinatesMm.indices.exists(axis => lowerCoordinatesMm(axis) >= upperCoordinatesMm(axis)) then
      Left(SmallStrainGeometryError.InvalidDomain("every lower bound must be below its upper bound"))
    else if !boundaryToleranceMm.isFinite || boundaryToleranceMm < 0.0 then
      Left(SmallStrainGeometryError.InvalidDomain("boundary tolerance must be finite and nonnegative"))
    else if !orthonormal(axes, 1e-10) then Left(SmallStrainGeometryError.InvalidDomain("domain axes must be orthonormal"))
    else Right(SmallStrainWorldDomain3(id, originWorldMm, axes, lowerCoordinatesMm, upperCoordinatesMm, boundaryToleranceMm))

  def fromSpectralDomain(
      id: String,
      domain: PhysicalSpectralDomain3,
      includePadding: Boolean = true
  ): Either[SmallStrainGeometryError, SmallStrainWorldDomain3] =
    val padding = if includePadding then domain.paddingMm else Vector.fill(3)(0.0)
    create(id, domain.originMm, domain.axes, padding.map(-_), domain.periodsMm.zip(padding).map(_ + _))

  private def orthonormal(axes: Vector[Vector[Double]], tolerance: Double): Boolean =
    axes.indices.forall(row => axes.indices.forall(column =>
      val expected = if row == column then 1.0 else 0.0
      val actual = axes(row).indices.map(index => axes(row)(index) * axes(column)(index)).sum
      math.abs(actual - expected) <= tolerance
    ))

private[flashalign] final case class SmallStrainCertificateConfig(
    maximumGradient: Double,
    maximumDisplacementMm: Double,
    minimumPoseDeterminant: Double,
    minimumPoseSingularValue: Double,
    maximumPoseSingularValue: Double,
    absoluteArithmeticAllowance: Double,
    relativeArithmeticAllowance: Double
)

private[flashalign] object SmallStrainCertificateConfig:
  def create(
      maximumGradient: Double = 0.5,
      maximumDisplacementMm: Double = 20.0,
      minimumPoseDeterminant: Double = 1e-8,
      minimumPoseSingularValue: Double = 0.25,
      maximumPoseSingularValue: Double = 4.0,
      absoluteArithmeticAllowance: Double = 1e-12,
      relativeArithmeticAllowance: Double = 1e-12
  ): Either[SmallStrainGeometryError, SmallStrainCertificateConfig] =
    if !maximumGradient.isFinite || maximumGradient <= 0.0 || maximumGradient >= 1.0 then
      Left(SmallStrainGeometryError.InvalidCertificateConfig("maximum gradient must lie in (0, 1)"))
    else if !maximumDisplacementMm.isFinite || maximumDisplacementMm <= 0.0 then
      Left(SmallStrainGeometryError.InvalidCertificateConfig("maximum displacement must be finite and positive"))
    else if !minimumPoseDeterminant.isFinite || minimumPoseDeterminant <= 0.0 then
      Left(SmallStrainGeometryError.InvalidCertificateConfig("minimum pose determinant must be finite and positive"))
    else if !minimumPoseSingularValue.isFinite || minimumPoseSingularValue <= 0.0 ||
        !maximumPoseSingularValue.isFinite || maximumPoseSingularValue < minimumPoseSingularValue
    then Left(SmallStrainGeometryError.InvalidCertificateConfig("pose singular-value bounds must be finite, positive and ordered"))
    else if !absoluteArithmeticAllowance.isFinite || absoluteArithmeticAllowance < 0.0 ||
        !relativeArithmeticAllowance.isFinite || relativeArithmeticAllowance < 0.0
    then Left(SmallStrainGeometryError.InvalidCertificateConfig("arithmetic allowances must be finite and nonnegative"))
    else Right(
      SmallStrainCertificateConfig(
        maximumGradient,
        maximumDisplacementMm,
        minimumPoseDeterminant,
        minimumPoseSingularValue,
        maximumPoseSingularValue,
        absoluteArithmeticAllowance,
        relativeArithmeticAllowance
      )
    )

private[flashalign] final case class SmallStrainInverseConfig(
    errorToleranceMm: Double,
    maximumIterations: Int
)

private[flashalign] object SmallStrainInverseConfig:
  def create(
      errorToleranceMm: Double = 1e-5,
      maximumIterations: Int = 64
  ): Either[SmallStrainGeometryError, SmallStrainInverseConfig] =
    if !errorToleranceMm.isFinite || errorToleranceMm <= 0.0 then
      Left(SmallStrainGeometryError.InvalidInverseConfig("error tolerance must be finite and positive"))
    else if maximumIterations <= 0 then Left(SmallStrainGeometryError.InvalidInverseConfig("maximum iterations must be positive"))
    else Right(SmallStrainInverseConfig(errorToleranceMm, maximumIterations))

private[flashalign] final case class SmallStrainGeometryConfig(
    certificate: SmallStrainCertificateConfig,
    inverse: SmallStrainInverseConfig,
    sourceDomain: SmallStrainWorldDomain3,
    fixedDomain: SmallStrainWorldDomain3
)

private[flashalign] final case class SmallStrainGeometryCertificate(
    valid: Boolean,
    coefficientHash: String,
    basisId: String,
    gaugeConventionId: String,
    extensionId: String,
    implementationRevision: String,
    rawGradientBound: Double,
    arithmeticGradientAllowance: Double,
    certifiedGradientBound: Double,
    maximumGradient: Double,
    gradientMargin: Double,
    rawAmplitudeBoundMm: Double,
    arithmeticAmplitudeAllowanceMm: Double,
    certifiedAmplitudeBoundMm: Double,
    maximumDisplacementMm: Double,
    poseDeterminant: Double,
    poseMinimumSingularValue: Double,
    poseMaximumSingularValue: Double,
    compositeMinimumSingularValueBound: Double,
    compositeMaximumSingularValueBound: Double,
    compositeDeterminantLowerBound: Double,
    evidenceKind: String
)

private[flashalign] object SmallStrainGeometryCertificate:
  val Revision = "small-strain-global-bound-v1"

  def evaluate[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: SmallStrainModel3[Pose, Moving, Fixed],
      state: SmallStrainState3[Pose],
      config: SmallStrainCertificateConfig
  ): Either[SmallStrainGeometryError, SmallStrainGeometryCertificate] =
    model.validateState(state).left.map(SmallStrainGeometryError.Model.apply).map { _ =>
      var rawGradient = 0.0
      var rawAmplitude = 0.0
      var mode = 0
      while mode < model.fieldParameterCount do
        val absolute = math.abs(state.field.coefficientsMm(mode))
        rawGradient += absolute * model.basis.modes(mode).derivativeBound
        rawAmplitude += absolute * model.basis.modes(mode).amplitudeBoundMmPerCoefficient
        mode += 1
      val gradientAllowance = allowance(rawGradient, model.fieldParameterCount, config)
      val amplitudeAllowance = allowance(rawAmplitude, model.fieldParameterCount, config)
      val certifiedGradient = rawGradient + gradientAllowance
      val certifiedAmplitude = rawAmplitude + amplitudeAllowance
      val linear = linear3(model.poseMatrix(state.pose))
      val determinant = AffineModel3.determinant(linear)
      val singular = AffineModel3.singularValueExtrema(linear)
      val validPose = determinant >= config.minimumPoseDeterminant &&
        singular._1 >= config.minimumPoseSingularValue && singular._2 <= config.maximumPoseSingularValue
      val valid = certifiedGradient <= config.maximumGradient &&
        certifiedAmplitude <= config.maximumDisplacementMm && validPose
      SmallStrainGeometryCertificate(
        valid,
        coefficientHash(model, state.field.coefficientsMm, config),
        model.basisId,
        model.gaugeConventionId,
        model.basis.extensionDescription,
        Revision,
        rawGradient,
        gradientAllowance,
        certifiedGradient,
        config.maximumGradient,
        config.maximumGradient - certifiedGradient,
        rawAmplitude,
        amplitudeAllowance,
        certifiedAmplitude,
        config.maximumDisplacementMm,
        determinant,
        singular._1,
        singular._2,
        singular._1 * (1.0 - certifiedGradient),
        singular._2 * (1.0 + certifiedGradient),
        determinant * math.pow(1.0 - certifiedGradient, 3.0),
        "global coefficient derivative/amplitude bounds plus checked affine; not sampled topology and not SmoothIso"
      )
    }

  private def allowance(raw: Double, terms: Int, config: SmallStrainCertificateConfig): Double =
    config.absoluteArithmeticAllowance + config.relativeArithmeticAllowance * raw + terms.toDouble * math.ulp(math.max(1.0, raw))

  private def linear3(matrix: Vector[Double]): Array[Double] =
    Array(matrix(0), matrix(1), matrix(2), matrix(4), matrix(5), matrix(6), matrix(8), matrix(9), matrix(10))

  private def coefficientHash[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: SmallStrainModel3[Pose, Moving, Fixed],
      coefficients: Vector[Double],
      config: SmallStrainCertificateConfig
  ): String =
    val text = Vector(
      model.basisId,
      model.gaugeConventionId,
      model.basis.extensionDescription,
      Revision,
      java.lang.Double.toHexString(config.maximumGradient),
      java.lang.Double.toHexString(config.maximumDisplacementMm)
    ).mkString("|") + coefficients.map(java.lang.Double.toHexString).mkString("|", ",", "")
    var hash = 0xcbf29ce484222325L
    var index = 0
    while index < text.length do
      hash = (hash ^ text.charAt(index).toLong) * 0x100000001b3L
      index += 1
    s"fnv1a64:${java.lang.Long.toUnsignedString(hash, 16)}"

private[flashalign] final case class SmallStrainInversePoint3(
    movingWorldMm: Vector[Double],
    iterations: Int,
    forwardResidualMm: Double,
    aPosterioriErrorBoundMm: Double,
    certificateHash: String,
    evidenceKind: String
)

private[flashalign] final class SmallStrainInverse3[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]] private (
    model: SmallStrainModel3[Pose, Moving, Fixed],
    state: SmallStrainState3[Pose],
    config: SmallStrainGeometryConfig,
    val certificate: SmallStrainGeometryCertificate
):
  def inversePoint(
      fixedX: Double,
      fixedY: Double,
      fixedZ: Double,
      workspace: SmallStrainWorkspace3
  ): Either[SmallStrainGeometryError, SmallStrainInversePoint3] =
    if !Vector(fixedX, fixedY, fixedZ).forall(_.isFinite) then Left(SmallStrainGeometryError.NonFiniteInversePoint)
    else if !config.fixedDomain.contains(fixedX, fixedY, fixedZ) then
      Left(SmallStrainGeometryError.OutsideFixedDomain(config.fixedDomain.id, Vector(fixedX, fixedY, fixedZ)))
    else
      val inverse = model.inversePoseMatrix(state.pose)
      val z = Array(
        inverse(0) * fixedX + inverse(1) * fixedY + inverse(2) * fixedZ + inverse(3),
        inverse(4) * fixedX + inverse(5) * fixedY + inverse(6) * fixedZ + inverse(7),
        inverse(8) * fixedX + inverse(9) * fixedY + inverse(10) * fixedZ + inverse(11)
      )
      val current = z.clone()
      val packed = new Array[Double](3)
      var iteration = 0
      var result = Option.empty[SmallStrainInversePoint3]
      var lastResidual = Double.PositiveInfinity
      var lastBound = Double.PositiveInfinity
      while iteration <= config.inverse.maximumIterations && result.isEmpty do
        packed(0) = current(0); packed(1) = current(1); packed(2) = current(2)
        model.fieldAt(state.field.coefficientsMm, packed, 0, workspace.fieldValue, workspace.modeValue)
        val rx = current(0) + workspace.fieldValue(0) - z(0)
        val ry = current(1) + workspace.fieldValue(1) - z(1)
        val rz = current(2) + workspace.fieldValue(2) - z(2)
        lastResidual = math.sqrt(rx * rx + ry * ry + rz * rz)
        lastBound = lastResidual / (1.0 - certificate.certifiedGradientBound)
        if lastBound <= config.inverse.errorToleranceMm then
          if !config.sourceDomain.contains(current(0), current(1), current(2)) then
            return Left(SmallStrainGeometryError.OutsideSourceDomain(config.sourceDomain.id, current.toVector))
          result = Some(
            SmallStrainInversePoint3(
              current.toVector,
              iteration,
              lastResidual,
              lastBound,
              certificate.coefficientHash,
              "contraction fixed-point inverse with residual/(1-kappa) bound; not negated displacement and not SmoothIso"
            )
          )
        else if iteration < config.inverse.maximumIterations then
          current(0) = z(0) - workspace.fieldValue(0)
          current(1) = z(1) - workspace.fieldValue(1)
          current(2) = z(2) - workspace.fieldValue(2)
        iteration += 1
      result.toRight(
        SmallStrainGeometryError.InverseIterationLimit(
          config.inverse.maximumIterations,
          lastResidual,
          lastBound,
          config.inverse.errorToleranceMm
        )
      )

private[flashalign] object SmallStrainInverse3:
  def compile[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: SmallStrainModel3[Pose, Moving, Fixed],
      state: SmallStrainState3[Pose],
      config: SmallStrainGeometryConfig
  ): Either[SmallStrainGeometryError, SmallStrainInverse3[Pose, Moving, Fixed]] =
    SmallStrainGeometryCertificate.evaluate(model, state, config.certificate).flatMap { certificate =>
      if certificate.valid then Right(new SmallStrainInverse3(model, state, config, certificate))
      else Left(SmallStrainGeometryError.InvalidCertificate(certificate))
    }

private[flashalign] final class SmallStrainGeometryPointOperator3[
    Pose,
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    val model: SmallStrainModel3[Pose, Moving, Fixed],
    val config: SmallStrainGeometryConfig
) extends GeometryPointOperator3[SmallStrainState3[Pose], Moving, Fixed]:
  val modelId: String = model.modelId
  val basisId: Option[String] = Some(model.basisId)
  val parameterCount: Int = model.parameterCount
  val moving: Moving = model.moving
  val fixed: Fixed = model.fixed

  protected def validateState(state: SmallStrainState3[Pose]): Either[GeometryOperatorError, Unit] =
    model.validateState(state).left.map(error => GeometryOperatorError.Nonlinear(error.message))

  override protected def validateForwardPoints(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Moving]
  ): Either[GeometryOperatorError, Unit] =
    certificate(state).flatMap(_ => firstOutside(points.packed, config.sourceDomain).toLeft(()))

  override protected def validateInversePoints(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Fixed]
  ): Either[GeometryOperatorError, Unit] =
    certificate(state).flatMap(_ => firstOutside(points.packed, config.fixedDomain).toLeft(()))

  protected def mapUnchecked(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    model.mapUnchecked(state, points, output, workspace.smallStrain())

  protected def inverseMapChecked(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Fixed],
      output: GeometryOutputBuffer3[Moving],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    SmallStrainInverse3.compile(model, state, config).left.map(error => GeometryOperatorError.Nonlinear(error.message)).flatMap { inverse =>
      var point = 0
      var failure = Option.empty[GeometryOperatorError]
      while point < points.size && failure.isEmpty do
        val offset = point * 3
        inverse.inversePoint(points.packed(offset), points.packed(offset + 1), points.packed(offset + 2), workspace.smallStrain()) match
          case Left(error) => failure = Some(GeometryOperatorError.Nonlinear(error.message))
          case Right(result) =>
            output.packed(offset) = result.movingWorldMm(0)
            output.packed(offset + 1) = result.movingWorldMm(1)
            output.packed(offset + 2) = result.movingWorldMm(2)
        point += 1
      failure.toLeft(())
    }

  protected def jvpUnchecked(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    model.jvpUnchecked(state, points, direction, output, workspace.smallStrain())

  protected def vjpUnchecked(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    model.vjpUnchecked(state, points, forces, output, workspace.smallStrain())

  protected def proposeChecked(
      state: SmallStrainState3[Pose],
      direction: Array[Double]
  ): Either[GeometryOperatorError, (SmallStrainState3[Pose], Array[Double])] =
    model.propose(state, direction).left.map(error => GeometryOperatorError.Nonlinear(error.message)).flatMap { proposal =>
      SmallStrainGeometryCertificate.evaluate(model, proposal._1, config.certificate)
        .left.map(error => GeometryOperatorError.Nonlinear(error.message))
        .flatMap(certificate =>
          if certificate.valid then Right(proposal)
          else Left(GeometryOperatorError.Nonlinear(SmallStrainGeometryError.InvalidCertificate(certificate).message))
        )
    }

  def priorTerms(state: SmallStrainState3[Pose]): Either[GeometryOperatorError, GeometryPriorTerms3] =
    model.prior(state).left.map(error => GeometryOperatorError.Nonlinear(error.message)).map(prior =>
      GeometryPriorTerms3(prior.value, prior.gradient, prior.curvatureUpper)
    )

  def certify(state: SmallStrainState3[Pose]): Either[GeometryOperatorError, GeometryCertificate3] =
    SmallStrainGeometryCertificate.evaluate(model, state, config.certificate)
      .left.map(error => GeometryOperatorError.Nonlinear(error.message))
      .map(value => GeometryCertificate3(
        modelId,
        basisId,
        value.valid,
        s"${value.evidenceKind}; hash=${value.coefficientHash}; gradient=${value.certifiedGradientBound}; " +
          s"composite singular bounds=[${value.compositeMinimumSingularValueBound},${value.compositeMaximumSingularValueBound}]"
      ))

  private def certificate(state: SmallStrainState3[Pose]): Either[GeometryOperatorError, SmallStrainGeometryCertificate] =
    SmallStrainGeometryCertificate.evaluate(model, state, config.certificate)
      .left.map(error => GeometryOperatorError.Nonlinear(error.message))
      .flatMap(value =>
        if value.valid then Right(value)
        else Left(GeometryOperatorError.Nonlinear(SmallStrainGeometryError.InvalidCertificate(value).message))
      )

  private def firstOutside(packed: Array[Double], domain: SmallStrainWorldDomain3): Option[GeometryOperatorError] =
    var point = 0
    while point < packed.length / 3 do
      val offset = point * 3
      if !domain.contains(packed(offset), packed(offset + 1), packed(offset + 2)) then
        return Some(GeometryOperatorError.Nonlinear(s"point $point lies outside declared small-strain domain ${domain.id}"))
      point += 1
    None

private[flashalign] sealed trait SmallStrainGeometryError derives CanEqual:
  def message: String

private[flashalign] object SmallStrainGeometryError:
  final case class InvalidDomain(detail: String) extends SmallStrainGeometryError:
    val message = s"invalid small-strain domain: $detail"
  final case class InvalidCertificateConfig(detail: String) extends SmallStrainGeometryError:
    val message = s"invalid small-strain certificate configuration: $detail"
  final case class InvalidInverseConfig(detail: String) extends SmallStrainGeometryError:
    val message = s"invalid small-strain inverse configuration: $detail"
  final case class Model(error: SmallStrainError) extends SmallStrainGeometryError:
    val message = error.message
  final case class InvalidCertificate(certificate: SmallStrainGeometryCertificate) extends SmallStrainGeometryError:
    val message = s"small-strain geometry is uncertified: gradient margin ${certificate.gradientMargin}, amplitude ${certificate.certifiedAmplitudeBoundMm}/${certificate.maximumDisplacementMm} mm, pose determinant ${certificate.poseDeterminant}"
  case object NonFiniteInversePoint extends SmallStrainGeometryError:
    val message = "small-strain inverse point must be finite"
  final case class OutsideFixedDomain(id: String, point: Vector[Double]) extends SmallStrainGeometryError:
    val message = s"fixed point $point lies outside small-strain reverse domain $id"
  final case class OutsideSourceDomain(id: String, point: Vector[Double]) extends SmallStrainGeometryError:
    val message = s"inverse point $point lies outside small-strain forward domain $id"
  final case class InverseIterationLimit(iterations: Int, residualMm: Double, errorBoundMm: Double, toleranceMm: Double)
      extends SmallStrainGeometryError:
    val message = s"small-strain inverse exhausted $iterations iterations with residual $residualMm mm and a posteriori bound $errorBoundMm mm > $toleranceMm mm"
