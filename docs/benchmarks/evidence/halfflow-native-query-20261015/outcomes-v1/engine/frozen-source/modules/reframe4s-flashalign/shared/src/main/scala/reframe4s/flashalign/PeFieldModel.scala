package reframe4s.flashalign

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import reframe4s.lie.Rigid3

private[flashalign] final case class AcquisitionPhaseEncoding3(
    voxelAxis: Int,
    polarity: Int
)

private[flashalign] final case class PhaseEncodingDirection3(
    voxelAxis: Int,
    polarity: Int,
    unitMovingWorld: Vector[Double]
)

private[flashalign] object PhaseEncodingDirection3:
  /** Resolve one acquisition PE axis and polarity through the complete linear
    * part of the observed EPI index-to-world affine. Translation is correctly
    * irrelevant for a vector; scale, obliquity, shear and reflection are not.
    */
  def resolve(
      candidates: Vector[AcquisitionPhaseEncoding3],
      indexToMovingWorld: Affine[D3],
      unitTolerance: Double = 1e-12
  ): Either[PeFieldError, PhaseEncodingDirection3] =
    if candidates.isEmpty then Left(PeFieldError.MissingPhaseEncodingMetadata)
    else if candidates.distinct.length != 1 then
      Left(PeFieldError.AmbiguousPhaseEncodingMetadata(candidates.distinct.length))
    else if !unitTolerance.isFinite || unitTolerance <= 0.0 then
      Left(PeFieldError.InvalidUnitTolerance(unitTolerance))
    else
      val metadata = candidates.head
      if metadata.voxelAxis < 0 || metadata.voxelAxis >= 3 then
        Left(PeFieldError.InvalidPhaseEncodingAxis(metadata.voxelAxis))
      else if math.abs(metadata.polarity) != 1 then
        Left(PeFieldError.InvalidPhaseEncodingPolarity(metadata.polarity))
      else
        val matrix = indexToMovingWorld.rowMajor
        val raw = Vector.tabulate(3)(row => matrix(row * 4 + metadata.voxelAxis) * metadata.polarity.toDouble)
        val length = math.sqrt(raw.map(value => value * value).sum)
        if !length.isFinite || length <= unitTolerance then Left(PeFieldError.DegenerateWorldDirection(length))
        else
          val unit = raw.map(_ / length)
          val residual = math.abs(math.sqrt(unit.map(value => value * value).sum) - 1.0)
          if residual > unitTolerance then Left(PeFieldError.NonUnitWorldDirection(residual, unitTolerance))
          else Right(PhaseEncodingDirection3(metadata.voxelAxis, metadata.polarity, unit))

private[flashalign] final case class PeFieldModelConfig(
    minimumCoefficients: Int,
    maximumCoefficients: Int,
    recommendedInitialCoefficients: Int
)

private[flashalign] object PeFieldModelConfig:
  def create(
      minimumCoefficients: Int = 32,
      maximumCoefficients: Int = 256,
      recommendedInitialCoefficients: Int = 64
  ): Either[PeFieldError, PeFieldModelConfig] =
    if minimumCoefficients <= 0 || maximumCoefficients < minimumCoefficients then
      Left(PeFieldError.InvalidCoefficientRange(minimumCoefficients, maximumCoefficients))
    else if recommendedInitialCoefficients < minimumCoefficients ||
        recommendedInitialCoefficients > maximumCoefficients
    then Left(PeFieldError.InvalidRecommendedCoefficientCount(recommendedInitialCoefficients))
    else Right(PeFieldModelConfig(minimumCoefficients, maximumCoefficients, recommendedInitialCoefficients))

private[flashalign] final case class PeFieldState3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    pose: Rigid3[Moving, Fixed],
    field: SpectralCoefficientState
)

private[flashalign] final case class PeFieldPriorEvaluation(
    value: Double,
    gradient: Vector[Double],
    curvatureUpper: Vector[Double]
)

private[flashalign] final class PeFieldWorkspace3 private[flashalign] (
    private val owner: AnyRef,
    private[flashalign] val basisValues: Array[Double],
    private[flashalign] val mappedPoint: Array[Double]
):
  private var active = false

  private[flashalign] def acquire(candidate: AnyRef): Either[PeFieldError, Unit] =
    this.synchronized {
      if !(owner eq candidate) then Left(PeFieldError.WorkspaceModelMismatch)
      else if active then Left(PeFieldError.WorkspaceInUse)
      else
        active = true
        Right(())
    }

  private[flashalign] def release(candidate: AnyRef): Unit =
    this.synchronized {
      if owner eq candidate then active = false
    }

/** Rigid pose plus a scalar displacement field expressed in observed moving
  * world millimetres and restricted to the acquisition PE direction.
  *
  * This model owns map/JVP/VJP/prior algebra. Its global monotonicity
  * certificate and bracketed inverse are added by the following admission node.
  */
private[flashalign] final class PeFieldModel3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val rigid: RigidModel3[Moving, Fixed],
    val basis: PhysicalSpectralBasis3,
    val phaseEncoding: PhaseEncodingDirection3,
    val priorPrecision: DenseOperator,
    val config: PeFieldModelConfig
):
  val modelId = "flashalign-rigid-pe-field-v1"
  val basisId: String = basis.id
  val poseParameterCount = 6
  val fieldParameterCount: Int = basis.nominalSize
  val parameterCount: Int = poseParameterCount + fieldParameterCount
  val nominalFieldParameterCount: Int = basis.nominalSize
  val effectiveFieldParameterCount: Int = basis.gaugeEffectiveRank
  val gaugeConventionId: String = basis.gaugeId

  def newWorkspace(): PeFieldWorkspace3 =
    new PeFieldWorkspace3(this, new Array[Double](fieldParameterCount), new Array[Double](3))

  def zeroState(pose: Rigid3[Moving, Fixed]): Either[PeFieldError, PeFieldState3[Moving, Fixed]] =
    basis.coefficientState(Vector.fill(fieldParameterCount)(0.0))
      .left.map(PeFieldError.Basis.apply)
      .flatMap(field => validateState(PeFieldState3(pose, field)).map(_ => PeFieldState3(pose, field)))

  def map(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      workspace: PeFieldWorkspace3
  ): Either[PeFieldError, Unit] = withWorkspace(workspace) {
    validateState(state).flatMap(_ => validateBatches(points, output)).map { _ =>
      mapUncheckedWithScratch(state, points, output, workspace.basisValues)
    }
  }

  def jvp(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      workspace: PeFieldWorkspace3
  ): Either[PeFieldError, Unit] = withWorkspace(workspace) {
    for
      _ <- validateState(state)
      _ <- validateBatches(points, output)
      _ <- validateDirection(direction)
    yield jvpUncheckedWithScratch(state, points, direction, output, workspace.basisValues)
  }

  def vjp(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: PeFieldWorkspace3
  ): Either[PeFieldError, Unit] = withWorkspace(workspace) {
    for
      _ <- validateState(state)
      _ <- validateForceBatches(points, forces)
      _ <- validateOutput(output)
    yield vjpUncheckedWithScratch(
      state,
      points,
      forces,
      output,
      workspace.basisValues,
      workspace.mappedPoint
    )
  }

  /** Writes the exact per-sample intensity Jacobian. Field columns share the
    * one scalar directional image derivative `gradient dot (A e)`.
    */
  def writeIntensityJacobian(
      state: PeFieldState3[Moving, Fixed],
      movingWorld: Vector[Double],
      fixedWorldGradient: Vector[Double],
      output: Array[Double],
      workspace: PeFieldWorkspace3
  ): Either[PeFieldError, Unit] = withWorkspace(workspace) {
    if movingWorld.length != 3 || movingWorld.exists(value => !value.isFinite) then
      Left(PeFieldError.InvalidWorldPoint)
    else if fixedWorldGradient.length != 3 || fixedWorldGradient.exists(value => !value.isFinite) then
      Left(PeFieldError.InvalidFixedGradient)
    else
      for
        _ <- validateState(state)
        _ <- validateOutput(output)
        mapped <- mapPoint(state, movingWorld, workspace)
        _ <- rigid.writeIntensityJacobianAtFixedWorld(
          mapped(0), mapped(1), mapped(2),
          fixedWorldGradient(0), fixedWorldGradient(1), fixedWorldGradient(2),
          output
        ).left.map(error => PeFieldError.Rigid(error.message))
      yield
        writeBasisValues(movingWorld(0), movingWorld(1), movingWorld(2), workspace.basisValues)
        val ae = rotatedPe(state.pose)
        val directional = dot3(fixedWorldGradient, ae)
        var coefficient = 0
        while coefficient < fieldParameterCount do
          output(poseParameterCount + coefficient) = directional * workspace.basisValues(coefficient)
          coefficient += 1
  }

  def propose(
      state: PeFieldState3[Moving, Fixed],
      direction: Array[Double]
  ): Either[PeFieldError, (PeFieldState3[Moving, Fixed], Array[Double])] =
    for
      _ <- validateState(state)
      _ <- validateDirection(direction)
      pose <- rigid.propose(state.pose, direction.take(poseParameterCount)).left.map(error => PeFieldError.Rigid(error.message))
      coefficients = state.field.coefficientsMm.indices.map(index =>
        state.field.coefficientsMm(index) + direction(poseParameterCount + index)
      ).toVector
      field <- basis.coefficientState(coefficients).left.map(PeFieldError.Basis.apply)
      candidate = PeFieldState3(pose, field)
      _ <- validateState(candidate)
    yield candidate -> direction.clone()

  def prior(state: PeFieldState3[Moving, Fixed]): Either[PeFieldError, PeFieldPriorEvaluation] =
    validateState(state).map { _ =>
      val coefficients = state.field.coefficientsMm
      val fieldGradient = Vector.tabulate(fieldParameterCount)(row =>
        var sum = 0.0
        var column = 0
        while column < fieldParameterCount do
          sum += priorPrecision(row, column) * coefficients(column)
          column += 1
        sum
      )
      val fullGradient = Vector.fill(poseParameterCount)(0.0) ++ fieldGradient
      val upper = new Array[Double](PackedSymmetric.size(parameterCount))
      var row = 0
      while row < fieldParameterCount do
        var column = row
        while column < fieldParameterCount do
          upper(PackedSymmetric.index(poseParameterCount + row, poseParameterCount + column)) =
            priorPrecision(row, column)
          column += 1
        row += 1
      PeFieldPriorEvaluation(
        0.5 * dot(coefficients, fieldGradient),
        fullGradient,
        upper.toVector
      )
    }

  private[flashalign] def mapUncheckedWithScratch(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      basisValues: Array[Double]
  ): Unit =
    val matrix = state.pose.operator.rowMajor
    var point = 0
    while point < points.size do
      val offset = point * 3
      val x = points.packed(offset)
      val y = points.packed(offset + 1)
      val z = points.packed(offset + 2)
      val displacement = fieldValue(state.field.coefficientsMm, x, y, z, basisValues)
      applyPose(
        matrix,
        x + phaseEncoding.unitMovingWorld(0) * displacement,
        y + phaseEncoding.unitMovingWorld(1) * displacement,
        z + phaseEncoding.unitMovingWorld(2) * displacement,
        output.packed,
        offset
      )
      point += 1

  private[flashalign] def jvpUncheckedWithScratch(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      basisValues: Array[Double]
  ): Unit =
    val matrix = state.pose.operator.rowMajor
    val ae = rotatedPe(state.pose)
    var point = 0
    while point < points.size do
      val offset = point * 3
      val x = points.packed(offset)
      val y = points.packed(offset + 1)
      val z = points.packed(offset + 2)
      val displacement = fieldValue(state.field.coefficientsMm, x, y, z, basisValues)
      val qx = x + phaseEncoding.unitMovingWorld(0) * displacement
      val qy = y + phaseEncoding.unitMovingWorld(1) * displacement
      val qz = z + phaseEncoding.unitMovingWorld(2) * displacement
      applyPose(matrix, qx, qy, qz, output.packed, offset)
      val fixedX = output.packed(offset)
      val fixedY = output.packed(offset + 1)
      val fixedZ = output.packed(offset + 2)
      val rx = fixedX - rigid.pivotX
      val ry = fixedY - rigid.pivotY
      val rz = fixedZ - rigid.pivotZ
      val fieldStep = dotFieldDirection(direction, basisValues)
      output.packed(offset) = direction(0) + direction(4) * rz - direction(5) * ry + ae(0) * fieldStep
      output.packed(offset + 1) = direction(1) + direction(5) * rx - direction(3) * rz + ae(1) * fieldStep
      output.packed(offset + 2) = direction(2) + direction(3) * ry - direction(4) * rx + ae(2) * fieldStep
      point += 1

  private[flashalign] def vjpUncheckedWithScratch(
      state: PeFieldState3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      basisValues: Array[Double],
      mapped: Array[Double]
  ): Unit =
    java.util.Arrays.fill(output, 0.0)
    val matrix = state.pose.operator.rowMajor
    val ae = rotatedPe(state.pose)
    var point = 0
    while point < points.size do
      val offset = point * 3
      val x = points.packed(offset)
      val y = points.packed(offset + 1)
      val z = points.packed(offset + 2)
      val displacement = fieldValue(state.field.coefficientsMm, x, y, z, basisValues)
      applyPose(
        matrix,
        x + phaseEncoding.unitMovingWorld(0) * displacement,
        y + phaseEncoding.unitMovingWorld(1) * displacement,
        z + phaseEncoding.unitMovingWorld(2) * displacement,
        mapped,
        0
      )
      val rx = mapped(0) - rigid.pivotX
      val ry = mapped(1) - rigid.pivotY
      val rz = mapped(2) - rigid.pivotZ
      val fx = forces.packed(offset)
      val fy = forces.packed(offset + 1)
      val fz = forces.packed(offset + 2)
      output(0) += fx
      output(1) += fy
      output(2) += fz
      output(3) += ry * fz - rz * fy
      output(4) += rz * fx - rx * fz
      output(5) += rx * fy - ry * fx
      val directionalForce = ae(0) * fx + ae(1) * fy + ae(2) * fz
      var coefficient = 0
      while coefficient < fieldParameterCount do
        output(poseParameterCount + coefficient) += basisValues(coefficient) * directionalForce
        coefficient += 1
      point += 1

  private def mapPoint(
      state: PeFieldState3[Moving, Fixed],
      point: Vector[Double],
      workspace: PeFieldWorkspace3
  ): Either[PeFieldError, Vector[Double]] =
    val displacement = fieldValue(
      state.field.coefficientsMm,
      point(0), point(1), point(2),
      workspace.basisValues
    )
    val output = new Array[Double](3)
    applyPose(
      state.pose.operator.rowMajor,
      point(0) + phaseEncoding.unitMovingWorld(0) * displacement,
      point(1) + phaseEncoding.unitMovingWorld(1) * displacement,
      point(2) + phaseEncoding.unitMovingWorld(2) * displacement,
      output,
      0
    )
    Right(output.toVector)

  private[flashalign] def fieldValue(
      coefficients: Vector[Double],
      x: Double,
      y: Double,
      z: Double,
      basisValues: Array[Double]
  ): Double =
    writeBasisValues(x, y, z, basisValues)
    var value = 0.0
    var coefficient = 0
    while coefficient < fieldParameterCount do
      value += coefficients(coefficient) * basisValues(coefficient)
      coefficient += 1
    value

  private[flashalign] def writeFieldGradient(
      coefficients: Vector[Double],
      x: Double,
      y: Double,
      z: Double,
      output: Array[Double]
  ): Unit =
    output(0) = 0.0
    output(1) = 0.0
    output(2) = 0.0
    val dx = x - basis.domain.originMm(0)
    val dy = y - basis.domain.originMm(1)
    val dz = z - basis.domain.originMm(2)
    var index = 0
    while index < fieldParameterCount do
      val mode = basis.modes(index)
      val wave = mode.angularWaveWorldPerMm
      val angle = wave(0) * dx + wave(1) * dy + wave(2) * dz
      val scale = mode.phase match
        case RealSpectralPhase.Cosine => -math.sqrt(2.0) * math.sin(angle)
        case RealSpectralPhase.Sine   => math.sqrt(2.0) * math.cos(angle)
      val coefficient = coefficients(index) * scale
      output(0) += coefficient * wave(0)
      output(1) += coefficient * wave(1)
      output(2) += coefficient * wave(2)
      index += 1

  private[flashalign] def writeBasisValues(x: Double, y: Double, z: Double, output: Array[Double]): Unit =
    val dx = x - basis.domain.originMm(0)
    val dy = y - basis.domain.originMm(1)
    val dz = z - basis.domain.originMm(2)
    var index = 0
    while index < fieldParameterCount do
      val mode = basis.modes(index)
      val wave = mode.angularWaveWorldPerMm
      val angle = wave(0) * dx + wave(1) * dy + wave(2) * dz
      val raw = mode.phase match
        case RealSpectralPhase.Cosine => math.sqrt(2.0) * math.cos(angle)
        case RealSpectralPhase.Sine   => math.sqrt(2.0) * math.sin(angle)
      output(index) = raw - mode.gaugeMean
      index += 1

  private def dotFieldDirection(direction: Array[Double], basisValues: Array[Double]): Double =
    var value = 0.0
    var coefficient = 0
    while coefficient < fieldParameterCount do
      value += direction(poseParameterCount + coefficient) * basisValues(coefficient)
      coefficient += 1
    value

  private[flashalign] def rotatedPe(pose: Rigid3[Moving, Fixed]): Vector[Double] =
    val matrix = pose.operator.rowMajor
    Vector.tabulate(3)(row =>
      matrix(row * 4) * phaseEncoding.unitMovingWorld(0) +
        matrix(row * 4 + 1) * phaseEncoding.unitMovingWorld(1) +
        matrix(row * 4 + 2) * phaseEncoding.unitMovingWorld(2)
    )

  private def applyPose(
      matrix: Vector[Double],
      x: Double,
      y: Double,
      z: Double,
      output: Array[Double],
      offset: Int
  ): Unit =
    output(offset) = matrix(0) * x + matrix(1) * y + matrix(2) * z + matrix(3)
    output(offset + 1) = matrix(4) * x + matrix(5) * y + matrix(6) * z + matrix(7)
    output(offset + 2) = matrix(8) * x + matrix(9) * y + matrix(10) * z + matrix(11)

  private[flashalign] def validateState(state: PeFieldState3[Moving, Fixed]): Either[PeFieldError, Unit] =
    rigid.validateMovingToFixed(state.pose).left.map(error => PeFieldError.Rigid(error.message)).flatMap { _ =>
      basis.requireState(state.field).left.map(PeFieldError.Basis.apply).map(_ => ())
    }

  private def validateDirection(direction: Array[Double]): Either[PeFieldError, Unit] =
    if direction.length != parameterCount then Left(PeFieldError.ParameterCountMismatch(parameterCount, direction.length))
    else if direction.exists(value => !value.isFinite) then Left(PeFieldError.NonFiniteDirection)
    else Right(())

  private def validateOutput(output: Array[Double]): Either[PeFieldError, Unit] =
    if output.length != parameterCount then Left(PeFieldError.ParameterCountMismatch(parameterCount, output.length))
    else Right(())

  private def validateBatches(
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed]
  ): Either[PeFieldError, Unit] =
    if !points.frame.sameRuntimeOwnerAs(rigid.moving) then Left(PeFieldError.MovingFrameMismatch)
    else if !output.frame.sameRuntimeOwnerAs(rigid.fixed) then Left(PeFieldError.FixedFrameMismatch)
    else if points.size != output.size then Left(PeFieldError.PointCountMismatch(points.size, output.size))
    else Right(())

  private def validateForceBatches(
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed]
  ): Either[PeFieldError, Unit] =
    if !points.frame.sameRuntimeOwnerAs(rigid.moving) then Left(PeFieldError.MovingFrameMismatch)
    else if !forces.frame.sameRuntimeOwnerAs(rigid.fixed) then Left(PeFieldError.FixedFrameMismatch)
    else if points.size != forces.size then Left(PeFieldError.PointCountMismatch(points.size, forces.size))
    else Right(())

  private def withWorkspace[A](workspace: PeFieldWorkspace3)(
      operation: => Either[PeFieldError, A]
  ): Either[PeFieldError, A] =
    workspace.acquire(this).flatMap { _ =>
      try operation
      finally workspace.release(this)
    }

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

  private def dot3(left: Vector[Double], right: Vector[Double]): Double =
    left(0) * right(0) + left(1) * right(1) + left(2) * right(2)

private[flashalign] object PeFieldModel3:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      rigid: RigidModel3[Moving, Fixed],
      basis: PhysicalSpectralBasis3,
      phaseEncoding: PhaseEncodingDirection3,
      priorWeights: SpectralPriorWeights,
      config: PeFieldModelConfig
  ): Either[PeFieldError, PeFieldModel3[Moving, Fixed]] =
    val direction = phaseEncoding.unitMovingWorld
    val norm = math.sqrt(direction.map(value => value * value).sum)
    if direction.length != 3 || direction.exists(value => !value.isFinite) || math.abs(norm - 1.0) > 1e-10 then
      Left(PeFieldError.InvalidCompiledWorldDirection(norm))
    else if basis.nominalSize < config.minimumCoefficients || basis.nominalSize > config.maximumCoefficients then
      Left(
        PeFieldError.CoefficientCountOutsideRange(
          basis.nominalSize,
          config.minimumCoefficients,
          config.maximumCoefficients
        )
      )
    else if basis.gaugeEffectiveRank != basis.nominalSize then
      Left(PeFieldError.GaugeRankMismatch(basis.nominalSize, basis.gaugeEffectiveRank))
    else
      basis.precision(priorWeights).left.map(PeFieldError.Basis.apply).map { precision =>
        new PeFieldModel3(rigid, basis, phaseEncoding, precision, config)
      }

private[flashalign] sealed trait PeFieldError derives CanEqual:
  def message: String

private[flashalign] object PeFieldError:
  case object MissingPhaseEncodingMetadata extends PeFieldError:
    val message = "phase-encoding axis and polarity metadata are required"
  final case class AmbiguousPhaseEncodingMetadata(candidates: Int) extends PeFieldError:
    val message = s"phase-encoding metadata has $candidates distinct candidates"
  final case class InvalidPhaseEncodingAxis(actual: Int) extends PeFieldError:
    val message = s"phase-encoding voxel axis must be 0, 1 or 2, got $actual"
  final case class InvalidPhaseEncodingPolarity(actual: Int) extends PeFieldError:
    val message = s"phase-encoding polarity must be -1 or +1, got $actual"
  final case class InvalidUnitTolerance(actual: Double) extends PeFieldError:
    val message = s"PE unit tolerance must be finite and positive, got $actual"
  final case class DegenerateWorldDirection(norm: Double) extends PeFieldError:
    val message = s"phase-encoding affine column is degenerate with norm $norm"
  final case class NonUnitWorldDirection(residual: Double, tolerance: Double) extends PeFieldError:
    val message = s"phase-encoding unit residual $residual exceeds $tolerance"
  final case class InvalidCompiledWorldDirection(norm: Double) extends PeFieldError:
    val message = s"compiled phase-encoding direction must be a finite world triple of unit norm, got $norm"
  final case class InvalidCoefficientRange(minimum: Int, maximum: Int) extends PeFieldError:
    val message = s"invalid PE coefficient range [$minimum, $maximum]"
  final case class InvalidRecommendedCoefficientCount(actual: Int) extends PeFieldError:
    val message = s"recommended PE coefficient count $actual lies outside the configured range"
  final case class CoefficientCountOutsideRange(actual: Int, minimum: Int, maximum: Int) extends PeFieldError:
    val message = s"PE basis has $actual coefficients outside [$minimum, $maximum]"
  final case class GaugeRankMismatch(nominal: Int, effective: Int) extends PeFieldError:
    val message = s"PE gauge retained rank $effective of $nominal coefficients"
  final case class Basis(error: SpectralBasisError) extends PeFieldError:
    val message = error.message
  final case class Rigid(detail: String) extends PeFieldError:
    val message = detail
  final case class ParameterCountMismatch(expected: Int, actual: Int) extends PeFieldError:
    val message = s"PE model requires $expected parameters, got $actual"
  case object NonFiniteDirection extends PeFieldError:
    val message = "PE model direction must be finite"
  case object InvalidWorldPoint extends PeFieldError:
    val message = "moving-world point must be a finite triple"
  case object InvalidFixedGradient extends PeFieldError:
    val message = "fixed-world image gradient must be a finite triple"
  case object MovingFrameMismatch extends PeFieldError:
    val message = "PE point batch does not belong to the observed moving-world frame"
  case object FixedFrameMismatch extends PeFieldError:
    val message = "PE output or force batch does not belong to the fixed-world frame"
  final case class PointCountMismatch(expected: Int, actual: Int) extends PeFieldError:
    val message = s"PE point count mismatch: expected $expected, got $actual"
  case object WorkspaceModelMismatch extends PeFieldError:
    val message = "PE workspace belongs to another compiled model"
  case object WorkspaceInUse extends PeFieldError:
    val message = "PE workspace is already in use"
