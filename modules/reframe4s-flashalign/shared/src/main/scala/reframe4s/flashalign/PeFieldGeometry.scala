package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame

private[flashalign] final case class PeWorldDomain3(
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

private[flashalign] object PeWorldDomain3:
  def create(
      id: String,
      originWorldMm: Vector[Double],
      axes: Vector[Vector[Double]],
      lowerCoordinatesMm: Vector[Double],
      upperCoordinatesMm: Vector[Double],
      boundaryToleranceMm: Double = 1e-9
  ): Either[PeGeometryError, PeWorldDomain3] =
    val triples = Vector(originWorldMm, lowerCoordinatesMm, upperCoordinatesMm) ++ axes
    if id.trim.isEmpty then Left(PeGeometryError.InvalidDomain("domain ID is empty"))
    else if axes.length != 3 || triples.exists(_.length != 3) then
      Left(PeGeometryError.InvalidDomain("domain coordinates and axes must be triples"))
    else if triples.flatten.exists(value => !value.isFinite) then
      Left(PeGeometryError.InvalidDomain("domain coordinates must be finite"))
    else if lowerCoordinatesMm.indices.exists(axis => lowerCoordinatesMm(axis) >= upperCoordinatesMm(axis)) then
      Left(PeGeometryError.InvalidDomain("every lower bound must be less than its upper bound"))
    else if !boundaryToleranceMm.isFinite || boundaryToleranceMm < 0.0 then
      Left(PeGeometryError.InvalidDomain("boundary tolerance must be finite and nonnegative"))
    else if !orthonormal(axes, 1e-10) then Left(PeGeometryError.InvalidDomain("domain axes must be orthonormal"))
    else Right(PeWorldDomain3(id, originWorldMm, axes, lowerCoordinatesMm, upperCoordinatesMm, boundaryToleranceMm))

  def fromSpectralDomain(
      id: String,
      domain: PhysicalSpectralDomain3,
      includePadding: Boolean = true
  ): Either[PeGeometryError, PeWorldDomain3] =
    val padding = if includePadding then domain.paddingMm else Vector.fill(3)(0.0)
    create(
      id,
      domain.originMm,
      domain.axes,
      padding.map(-_),
      domain.periodsMm.zip(padding).map(_ + _)
    )

  private def orthonormal(axes: Vector[Vector[Double]], tolerance: Double): Boolean =
    axes.indices.forall(row =>
      axes.indices.forall(column =>
        val expected = if row == column then 1.0 else 0.0
        val actual = axes(row).indices.map(index => axes(row)(index) * axes(column)(index)).sum
        math.abs(actual - expected) <= tolerance
      )
    )

private[flashalign] final case class PeCertificateConfig(
    minimumDirectionalJacobian: Double,
    maximumDisplacementMm: Double,
    absoluteArithmeticAllowance: Double,
    relativeArithmeticAllowance: Double
)

private[flashalign] object PeCertificateConfig:
  def create(
      minimumDirectionalJacobian: Double = 0.2,
      maximumDisplacementMm: Double = 20.0,
      absoluteArithmeticAllowance: Double = 1e-12,
      relativeArithmeticAllowance: Double = 1e-12
  ): Either[PeGeometryError, PeCertificateConfig] =
    if !minimumDirectionalJacobian.isFinite || minimumDirectionalJacobian <= 0.0 ||
        minimumDirectionalJacobian >= 1.0
    then Left(PeGeometryError.InvalidCertificateConfig("minimum directional Jacobian must lie in (0, 1)"))
    else if !maximumDisplacementMm.isFinite || maximumDisplacementMm <= 0.0 then
      Left(PeGeometryError.InvalidCertificateConfig("maximum displacement must be finite and positive"))
    else if !absoluteArithmeticAllowance.isFinite || absoluteArithmeticAllowance < 0.0 ||
        !relativeArithmeticAllowance.isFinite || relativeArithmeticAllowance < 0.0
    then Left(PeGeometryError.InvalidCertificateConfig("arithmetic allowances must be finite and nonnegative"))
    else
      Right(
        PeCertificateConfig(
          minimumDirectionalJacobian,
          maximumDisplacementMm,
          absoluteArithmeticAllowance,
          relativeArithmeticAllowance
        )
      )

private[flashalign] final case class PeInverseConfig(
    residualToleranceMm: Double,
    maximumIterations: Int
)

private[flashalign] object PeInverseConfig:
  def create(
      residualToleranceMm: Double = 1e-5,
      maximumIterations: Int = 48
  ): Either[PeGeometryError, PeInverseConfig] =
    if !residualToleranceMm.isFinite || residualToleranceMm <= 0.0 then
      Left(PeGeometryError.InvalidInverseConfig("residual tolerance must be finite and positive"))
    else if maximumIterations <= 0 then
      Left(PeGeometryError.InvalidInverseConfig("maximum iterations must be positive"))
    else Right(PeInverseConfig(residualToleranceMm, maximumIterations))

private[flashalign] final case class PeGeometryConfig(
    certificate: PeCertificateConfig,
    inverse: PeInverseConfig,
    sourceDomain: PeWorldDomain3,
    fixedDomain: PeWorldDomain3
)

private[flashalign] final case class PeGeometryCertificate(
    valid: Boolean,
    coefficientHash: String,
    basisId: String,
    gaugeConventionId: String,
    extensionId: String,
    implementationRevision: String,
    normalization: String,
    rawDirectionalDerivativeBound: Double,
    arithmeticDirectionalAllowance: Double,
    certifiedDirectionalDerivativeBound: Double,
    minimumDirectionalJacobian: Double,
    certifiedJacobianMargin: Double,
    rawAmplitudeBoundMm: Double,
    arithmeticAmplitudeAllowanceMm: Double,
    certifiedAmplitudeBoundMm: Double,
    maximumDisplacementMm: Double,
    evidenceKind: String
)

private[flashalign] object PeGeometryCertificate:
  val Revision = "pe-global-bound-v1"

  def evaluate[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      model: PeFieldModel3[Moving, Fixed],
      state: PeFieldState3[Moving, Fixed],
      config: PeCertificateConfig
  ): Either[PeGeometryError, PeGeometryCertificate] =
    model.validateState(state).left.map(PeGeometryError.Model.apply).map { _ =>
      val coefficients = state.field.coefficientsMm
      val pe = model.phaseEncoding.unitMovingWorld
      var directional = 0.0
      var amplitude = 0.0
      var index = 0
      while index < model.fieldParameterCount do
        val mode = model.basis.modes(index)
        val wave = mode.angularWaveWorldPerMm
        val directionalMode = math.sqrt(2.0) * math.abs(
          pe(0) * wave(0) + pe(1) * wave(1) + pe(2) * wave(2)
        )
        directional += math.abs(coefficients(index)) * directionalMode
        amplitude += math.abs(coefficients(index)) * (math.sqrt(2.0) + math.abs(mode.gaugeMean))
        index += 1
      val directionalAllowance = allowance(directional, coefficients.length, config)
      val amplitudeAllowance = allowance(amplitude, coefficients.length, config)
      val certifiedDirectional = directional + directionalAllowance
      val certifiedAmplitude = amplitude + amplitudeAllowance
      val minimumJacobian = 1.0 - certifiedDirectional
      val valid =
        minimumJacobian >= config.minimumDirectionalJacobian &&
          certifiedAmplitude <= config.maximumDisplacementMm
      PeGeometryCertificate(
        valid,
        coefficientHash(model, coefficients, config),
        model.basis.id,
        model.gaugeConventionId,
        model.basis.domain.extensionId,
        Revision,
        "sqrt(2)-unit-rms-real-sine-cosine; fixed weighted-mean subtraction",
        directional,
        directionalAllowance,
        certifiedDirectional,
        minimumJacobian,
        minimumJacobian - config.minimumDirectionalJacobian,
        amplitude,
        amplitudeAllowance,
        certifiedAmplitude,
        config.maximumDisplacementMm,
        "global-analytic-directional-derivative-bound; numerical inverse; not SmoothIso"
      )
    }

  private def allowance(raw: Double, terms: Int, config: PeCertificateConfig): Double =
    config.absoluteArithmeticAllowance +
      config.relativeArithmeticAllowance * raw +
      terms.toDouble * math.ulp(math.max(1.0, raw))

  private def coefficientHash[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      model: PeFieldModel3[Moving, Fixed],
      coefficients: Vector[Double],
      config: PeCertificateConfig
  ): String =
    val text = Vector(
      model.basis.id,
      model.gaugeConventionId,
      model.basis.domain.extensionId,
      Revision,
      java.lang.Double.toHexString(config.minimumDirectionalJacobian),
      java.lang.Double.toHexString(config.maximumDisplacementMm)
    ).mkString("|") + coefficients.map(java.lang.Double.toHexString).mkString("|", ",", "")
    var hash = 0xcbf29ce484222325L
    var index = 0
    while index < text.length do
      hash = (hash ^ text.charAt(index).toLong) * 0x100000001b3L
      index += 1
    s"fnv1a64:${java.lang.Long.toUnsignedString(hash, 16)}"

private[flashalign] final case class PeInversePoint3(
    movingWorldMm: Vector[Double],
    iterations: Int,
    residualMm: Double,
    newtonSteps: Int,
    bisectionSteps: Int,
    certificateHash: String,
    evidenceKind: String
)

private[flashalign] final class PeFieldInverse3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    model: PeFieldModel3[Moving, Fixed],
    state: PeFieldState3[Moving, Fixed],
    config: PeGeometryConfig,
    val certificate: PeGeometryCertificate
):
  def inversePoint(
      fixedX: Double,
      fixedY: Double,
      fixedZ: Double,
      basisScratch: Array[Double],
      gradientScratch: Array[Double]
  ): Either[PeGeometryError, PeInversePoint3] =
    if !Vector(fixedX, fixedY, fixedZ).forall(_.isFinite) then Left(PeGeometryError.NonFiniteInversePoint)
    else if !config.fixedDomain.contains(fixedX, fixedY, fixedZ) then
      Left(PeGeometryError.OutsideFixedDomain(config.fixedDomain.id, Vector(fixedX, fixedY, fixedZ)))
    else if basisScratch.length < model.fieldParameterCount || gradientScratch.length < 3 then
      Left(PeGeometryError.InverseWorkspaceTooSmall)
    else
      val inverse = state.pose.inverse.operator.rowMajor
      val zx = inverse(0) * fixedX + inverse(1) * fixedY + inverse(2) * fixedZ + inverse(3)
      val zy = inverse(4) * fixedX + inverse(5) * fixedY + inverse(6) * fixedZ + inverse(7)
      val zz = inverse(8) * fixedX + inverse(9) * fixedY + inverse(10) * fixedZ + inverse(11)
      val pe = model.phaseEncoding.unitMovingWorld
      val bound = certificate.certifiedAmplitudeBoundMm
      var lower = -bound
      var upper = bound
      var lowerResidual = residual(zx, zy, zz, pe, lower, basisScratch)
      var upperResidual = residual(zx, zy, zz, pe, upper, basisScratch)
      if !lowerResidual.isFinite || !upperResidual.isFinite || lowerResidual > 0.0 || upperResidual < 0.0 then
        Left(PeGeometryError.InvalidInverseBracket(lowerResidual, upperResidual, bound))
      else
        var scalar = 0.0
        var iteration = 0
        var newton = 0
        var bisection = 0
        var result = Option.empty[PeInversePoint3]
        while iteration < config.inverse.maximumIterations && result.isEmpty do
          val x = zx + pe(0) * scalar
          val y = zy + pe(1) * scalar
          val z = zz + pe(2) * scalar
          val value = scalar + model.fieldValue(state.field.coefficientsMm, x, y, z, basisScratch)
          if math.abs(value) <= config.inverse.residualToleranceMm then
            if !config.sourceDomain.contains(x, y, z) then
              return Left(PeGeometryError.OutsideSourceDomain(config.sourceDomain.id, Vector(x, y, z)))
            result = Some(
              PeInversePoint3(
                Vector(x, y, z),
                iteration,
                math.abs(value),
                newton,
                bisection,
                certificate.coefficientHash,
                "bracketed scalar numerical inverse; not SmoothIso"
              )
            )
          else
            if value < 0.0 then
              lower = scalar
              lowerResidual = value
            else
              upper = scalar
              upperResidual = value
            model.writeFieldGradient(state.field.coefficientsMm, x, y, z, gradientScratch)
            val derivative = 1.0 + pe(0) * gradientScratch(0) + pe(1) * gradientScratch(1) + pe(2) * gradientScratch(2)
            val candidate = scalar - value / derivative
            if derivative.isFinite && derivative >= certificate.minimumDirectionalJacobian &&
                candidate > lower && candidate < upper
            then
              scalar = candidate
              newton += 1
            else
              scalar = 0.5 * (lower + upper)
              bisection += 1
          iteration += 1
        result.toRight(
          PeGeometryError.InverseIterationLimit(
            config.inverse.maximumIterations,
            math.min(math.abs(lowerResidual), math.abs(upperResidual)),
            config.inverse.residualToleranceMm
          )
        )

  private def residual(
      zx: Double,
      zy: Double,
      zz: Double,
      pe: Vector[Double],
      scalar: Double,
      basisScratch: Array[Double]
  ): Double =
    scalar + model.fieldValue(
      state.field.coefficientsMm,
      zx + pe(0) * scalar,
      zy + pe(1) * scalar,
      zz + pe(2) * scalar,
      basisScratch
    )

private[flashalign] object PeFieldInverse3:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      model: PeFieldModel3[Moving, Fixed],
      state: PeFieldState3[Moving, Fixed],
      config: PeGeometryConfig
  ): Either[PeGeometryError, PeFieldInverse3[Moving, Fixed]] =
    PeGeometryCertificate.evaluate(model, state, config.certificate).flatMap { certificate =>
      if certificate.valid then Right(new PeFieldInverse3(model, state, config, certificate))
      else Left(PeGeometryError.InvalidCertificate(certificate))
    }

private final class PeFieldGeometryPointOperator3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    val model: PeFieldModel3[Moving, Fixed],
    val config: PeGeometryConfig
) extends GeometryPointOperator3[PeFieldState3[Moving, Fixed], Moving, Fixed]:
  val modelId: String = model.modelId
  val basisId: Option[String] = Some(model.basisId)
  val parameterCount: Int = model.parameterCount
  val moving: Moving = model.rigid.moving
  val fixed: Fixed = model.rigid.fixed

  protected def validateState(state: PeFieldState3[Moving, Fixed]): Either[GeometryOperatorError, Unit] =
    model.validateState(state).left.map(error => GeometryOperatorError.Nonlinear(error.message))

  override protected def validateForwardPoints(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Moving]
  ): Either[GeometryOperatorError, Unit] =
    PeGeometryCertificate.evaluate(model, state, config.certificate)
      .left.map(error => GeometryOperatorError.Nonlinear(error.message))
      .flatMap { certificate =>
        if !certificate.valid then
          Left(GeometryOperatorError.Nonlinear(PeGeometryError.InvalidCertificate(certificate).message))
        else firstOutside(points.packed, config.sourceDomain).toLeft(())
      }

  override protected def validateInversePoints(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Fixed]
  ): Either[GeometryOperatorError, Unit] =
    PeGeometryCertificate.evaluate(model, state, config.certificate)
      .left.map(error => GeometryOperatorError.Nonlinear(error.message))
      .flatMap { certificate =>
        if !certificate.valid then
          Left(GeometryOperatorError.Nonlinear(PeGeometryError.InvalidCertificate(certificate).message))
        else firstOutside(points.packed, config.fixedDomain).toLeft(())
      }

  protected def mapUnchecked(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    model.mapUncheckedWithScratch(state, points, output, workspace.parameters(model.fieldParameterCount))

  protected def inverseMapChecked(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Fixed],
      output: GeometryOutputBuffer3[Moving],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    PeFieldInverse3.compile(model, state, config)
      .left.map(error => GeometryOperatorError.Nonlinear(error.message))
      .flatMap { inverse =>
        val basis = workspace.parameters(model.fieldParameterCount)
        val gradient = workspace.mapped(3)
        var point = 0
        var failure = Option.empty[GeometryOperatorError]
        while point < points.size && failure.isEmpty do
          val offset = point * 3
          inverse.inversePoint(
            points.packed(offset),
            points.packed(offset + 1),
            points.packed(offset + 2),
            basis,
            gradient
          ) match
            case Left(error) => failure = Some(GeometryOperatorError.Nonlinear(error.message))
            case Right(result) =>
              output.packed(offset) = result.movingWorldMm(0)
              output.packed(offset + 1) = result.movingWorldMm(1)
              output.packed(offset + 2) = result.movingWorldMm(2)
          point += 1
        failure.toLeft(())
      }

  protected def jvpUnchecked(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    model.jvpUncheckedWithScratch(
      state,
      points,
      direction,
      output,
      workspace.parameters(model.fieldParameterCount)
    )

  protected def vjpUnchecked(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    model.vjpUncheckedWithScratch(
      state,
      points,
      forces,
      output,
      workspace.parameters(model.fieldParameterCount),
      workspace.mapped(3)
    )

  protected def proposeChecked(
      state: PeFieldState3[Moving, Fixed],
      direction: Array[Double]
  ): Either[GeometryOperatorError, (PeFieldState3[Moving, Fixed], Array[Double])] =
    model.propose(state, direction).left.map(error => GeometryOperatorError.Nonlinear(error.message)).flatMap {
      proposal =>
        PeGeometryCertificate.evaluate(model, proposal._1, config.certificate)
          .left.map(error => GeometryOperatorError.Nonlinear(error.message))
          .flatMap { certificate =>
            if certificate.valid then Right(proposal)
            else Left(GeometryOperatorError.Nonlinear(PeGeometryError.InvalidCertificate(certificate).message))
          }
    }

  def priorTerms(state: PeFieldState3[Moving, Fixed]): Either[GeometryOperatorError, GeometryPriorTerms3] =
    model.prior(state).left.map(error => GeometryOperatorError.Nonlinear(error.message)).map { prior =>
      GeometryPriorTerms3(prior.value, prior.gradient, prior.curvatureUpper)
    }

  def certify(state: PeFieldState3[Moving, Fixed]): Either[GeometryOperatorError, GeometryCertificate3] =
    PeGeometryCertificate.evaluate(model, state, config.certificate)
      .left.map(error => GeometryOperatorError.Nonlinear(error.message))
      .map(certificate =>
        GeometryCertificate3(
          modelId,
          basisId,
          certificate.valid,
          s"${certificate.evidenceKind}; hash=${certificate.coefficientHash}; " +
            s"minimum directional Jacobian=${certificate.minimumDirectionalJacobian}; " +
            s"amplitude bound=${certificate.certifiedAmplitudeBoundMm} mm"
        )
      )

  private def firstOutside(
      packed: Array[Double],
      domain: PeWorldDomain3
  ): Option[GeometryOperatorError] =
    var point = 0
    while point < packed.length / 3 do
      val offset = point * 3
      if !domain.contains(packed(offset), packed(offset + 1), packed(offset + 2)) then
        return Some(
          GeometryOperatorError.Nonlinear(
            s"point $point lies outside declared geometry domain ${domain.id}"
          )
        )
      point += 1
    None

private[flashalign] sealed trait PeGeometryError derives CanEqual:
  def message: String

private[flashalign] object PeGeometryError:
  final case class InvalidDomain(detail: String) extends PeGeometryError:
    val message = s"invalid PE domain: $detail"
  final case class InvalidCertificateConfig(detail: String) extends PeGeometryError:
    val message = s"invalid PE certificate configuration: $detail"
  final case class InvalidInverseConfig(detail: String) extends PeGeometryError:
    val message = s"invalid PE inverse configuration: $detail"
  final case class Model(error: PeFieldError) extends PeGeometryError:
    val message = error.message
  final case class InvalidCertificate(certificate: PeGeometryCertificate) extends PeGeometryError:
    val message =
      s"PE geometry is uncertified: Jacobian margin ${certificate.certifiedJacobianMargin}, " +
        s"amplitude ${certificate.certifiedAmplitudeBoundMm}/${certificate.maximumDisplacementMm} mm"
  case object NonFiniteInversePoint extends PeGeometryError:
    val message = "PE inverse point must be finite"
  final case class OutsideFixedDomain(id: String, point: Vector[Double]) extends PeGeometryError:
    val message = s"fixed point $point lies outside PE reverse domain $id"
  final case class OutsideSourceDomain(id: String, point: Vector[Double]) extends PeGeometryError:
    val message = s"inverse point $point lies outside PE forward domain $id"
  case object InverseWorkspaceTooSmall extends PeGeometryError:
    val message = "PE inverse scratch buffers are too small"
  final case class InvalidInverseBracket(lowerResidual: Double, upperResidual: Double, boundMm: Double)
      extends PeGeometryError:
    val message = s"PE inverse bracket ±$boundMm mm has residuals $lowerResidual and $upperResidual"
  final case class InverseIterationLimit(iterations: Int, residualMm: Double, toleranceMm: Double)
      extends PeGeometryError:
    val message = s"PE inverse exhausted $iterations iterations with residual $residualMm mm > $toleranceMm mm"
