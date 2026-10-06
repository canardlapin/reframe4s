package reframe4s.flashalign

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Point
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.lie.FramedAffine

private[flashalign] enum AffineGeometryKind derives CanEqual:
  case Increment, Candidate

private[flashalign] enum AffineModelQuantity derives CanEqual:
  case Increment, FixedCoordinate, FixedGradient

private[flashalign] sealed trait AffineModelError derives CanEqual:
  def message: String

private[flashalign] object AffineModelError:
  final case class FrameOwnerMismatch(
      endpoint: RigidModelEndpoint,
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends AffineModelError:
    val message: String = s"$endpoint owner $actual does not match $expected"

  final case class InvalidIncrementLength(actual: Int) extends AffineModelError:
    val message: String =
      s"an affine Flashalign increment requires 12 values, got $actual"

  final case class NonFiniteValue(
      quantity: AffineModelQuantity,
      index: Int,
      value: Double
  ) extends AffineModelError:
    val message: String = s"$quantity value $index must be finite, got $value"

  final case class InvalidJacobianSlice(offset: Int, capacity: Int)
      extends AffineModelError:
    val message: String =
      s"cannot write 12 affine Jacobian values at offset $offset into capacity $capacity"

  final case class InvalidConfiguration(detail: String) extends AffineModelError:
    val message: String = detail

  final case class StrainIncrementOutOfBounds(
      parameter: Int,
      value: Double,
      maximumAbsoluteValue: Double
  ) extends AffineModelError:
    val message: String =
      s"strain increment $parameter value $value exceeds +/-$maximumAbsoluteValue"

  final case class NonPositiveDeterminant(
      kind: AffineGeometryKind,
      determinant: Double,
      minimum: Double
  ) extends AffineModelError:
    val message: String =
      s"$kind determinant $determinant does not exceed $minimum"

  final case class SingularValueOutOfBounds(
      kind: AffineGeometryKind,
      observedMinimum: Double,
      observedMaximum: Double,
      requiredMinimum: Double,
      requiredMaximum: Double
  ) extends AffineModelError:
    val message: String =
      s"$kind singular values [$observedMinimum, $observedMaximum] are outside [$requiredMinimum, $requiredMaximum]"

  final case class InvalidPriorWeight(value: Double) extends AffineModelError:
    val message: String =
      s"affine strain-prior weight must be finite and non-negative, got $value"

  final case class InvalidPriorOutputDimension(actual: Int)
      extends AffineModelError:
    val message: String =
      s"affine strain prior requires a 12-parameter output, got $actual"

  object Geometry:
    def apply(error: GeometryError | spatial4s.SpatialError): Geometry =
      new Geometry(image4s.geometry.GeometryError.fromCoordinate(error))

  final case class Geometry(error: GeometryError) extends AffineModelError:
    val message: String = error.message

private[flashalign] final class AffineModelConfig private (
    val minimumIncrementSingularValue: Double,
    val maximumIncrementSingularValue: Double,
    val minimumCandidateSingularValue: Double,
    val maximumCandidateSingularValue: Double,
    val maximumAbsoluteStrainIncrement: Double,
    val minimumDeterminant: Double
)

private[flashalign] object AffineModelConfig:
  def create(
      minimumIncrementSingularValue: Double = 0.5,
      maximumIncrementSingularValue: Double = 1.5,
      minimumCandidateSingularValue: Double = 0.25,
      maximumCandidateSingularValue: Double = 4.0,
      maximumAbsoluteStrainIncrement: Double = 0.5,
      minimumDeterminant: Double = 1e-8
  ): Either[AffineModelError, AffineModelConfig] =
    if !validBounds(
        minimumIncrementSingularValue,
        maximumIncrementSingularValue
      )
    then
      invalid("increment singular-value bounds must be finite, positive, and contain one")
    else if !validBounds(
        minimumCandidateSingularValue,
        maximumCandidateSingularValue
      )
    then
      invalid("candidate singular-value bounds must be finite, positive, and contain one")
    else if
      !maximumAbsoluteStrainIncrement.isFinite ||
        maximumAbsoluteStrainIncrement <= 0.0
    then invalid("maximum absolute strain increment must be finite and positive")
    else if !minimumDeterminant.isFinite || minimumDeterminant <= 0.0 then
      invalid("minimum determinant must be finite and positive")
    else
      Right(
        new AffineModelConfig(
          minimumIncrementSingularValue,
          maximumIncrementSingularValue,
          minimumCandidateSingularValue,
          maximumCandidateSingularValue,
          maximumAbsoluteStrainIncrement,
          minimumDeterminant
        )
      )

  private def validBounds(minimum: Double, maximum: Double): Boolean =
    minimum.isFinite && maximum.isFinite && minimum > 0.0 &&
      minimum <= 1.0 && maximum >= 1.0 && maximum >= minimum

  private def invalid(detail: String): Either[AffineModelError, AffineModelConfig] =
    Left(AffineModelError.InvalidConfiguration(detail))

/**
 * Checked 12-coordinate affine model around one frozen fixed-world pivot.
 *
 * Coordinates are `[dt, omega, sxx, syy, szz, sxy, sxz, syz]`. Off-diagonal
 * strain coordinates place the coefficient in both symmetric entries without
 * a factor of two or square-root normalization. The fixed-frame local update
 * is `o + dt + (I + skew(omega) + S)(y - o)` and is composed through the
 * canonical image4s [[Affine]] held by [[FramedAffine]].
 */
private[flashalign] final class AffineModel3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val moving: Moving,
    val fixed: Fixed,
    private[flashalign] val pivotX: Double,
    private[flashalign] val pivotY: Double,
    private[flashalign] val pivotZ: Double,
    val config: AffineModelConfig
):
  val parameterCount: Int = 12

  def propose(
      movingToFixed: FramedAffine[Moving, Fixed, D3],
      increment: Array[Double]
  ): Either[AffineModelError, FramedAffine[Moving, Fixed, D3]] =
    for
      _ <- validateMovingToFixed(movingToFixed)
      _ <- validateIncrement(increment)
      localLinear = localLinearFrom(increment)
      _ <- validateLinear(
        AffineGeometryKind.Increment,
        localLinear,
        config.minimumIncrementSingularValue,
        config.maximumIncrementSingularValue
      )
      local <- localAffine(localLinear, increment)
      candidateOperator <- movingToFixed.operator
        .andThen(local)
        .left
        .map(AffineModelError.Geometry.apply)
      _ <- validateLinear(
        AffineGeometryKind.Candidate,
        linearOf(candidateOperator),
        config.minimumCandidateSingularValue,
        config.maximumCandidateSingularValue
      )
    yield FramedAffine.betweenFrames(moving, fixed)(candidateOperator)

  def writeIntensityJacobianAtFixedWorld(
      fixedX: Double,
      fixedY: Double,
      fixedZ: Double,
      gradientX: Double,
      gradientY: Double,
      gradientZ: Double,
      output: Array[Double],
      outputOffset: Int = 0
  ): Either[AffineModelError, Unit] =
    if outputOffset < 0 || outputOffset > output.length - parameterCount then
      Left(AffineModelError.InvalidJacobianSlice(outputOffset, output.length))
    else
      firstNonFinite(fixedX, fixedY, fixedZ) match
        case Some((index, value)) =>
          Left(
            AffineModelError.NonFiniteValue(
              AffineModelQuantity.FixedCoordinate,
              index,
              value
            )
          )
        case None =>
          firstNonFinite(gradientX, gradientY, gradientZ) match
            case Some((index, value)) =>
              Left(
                AffineModelError.NonFiniteValue(
                  AffineModelQuantity.FixedGradient,
                  index,
                  value
                )
              )
            case None =>
              writeIntensityJacobianUnchecked(
                fixedX,
                fixedY,
                fixedZ,
                gradientX,
                gradientY,
                gradientZ,
                output,
                outputOffset
              )
              Right(())

  private[flashalign] def writeIntensityJacobianUnchecked(
      fixedX: Double,
      fixedY: Double,
      fixedZ: Double,
      gradientX: Double,
      gradientY: Double,
      gradientZ: Double,
      output: Array[Double],
      outputOffset: Int
  ): Unit =
    val rx = fixedX - pivotX
    val ry = fixedY - pivotY
    val rz = fixedZ - pivotZ
    output(outputOffset) = gradientX
    output(outputOffset + 1) = gradientY
    output(outputOffset + 2) = gradientZ
    output(outputOffset + 3) = ry * gradientZ - rz * gradientY
    output(outputOffset + 4) = rz * gradientX - rx * gradientZ
    output(outputOffset + 5) = rx * gradientY - ry * gradientX
    output(outputOffset + 6) = gradientX * rx
    output(outputOffset + 7) = gradientY * ry
    output(outputOffset + 8) = gradientZ * rz
    output(outputOffset + 9) = gradientX * ry + gradientY * rx
    output(outputOffset + 10) = gradientX * rz + gradientZ * rx
    output(outputOffset + 11) = gradientY * rz + gradientZ * ry

  private[flashalign] def validateMovingToFixed(
      movingToFixed: FramedAffine[Moving, Fixed, D3]
  ): Either[AffineModelError, Unit] =
    if !moving.sameRuntimeOwnerAs(movingToFixed.source) then
      Left(
        AffineModelError.FrameOwnerMismatch(
          RigidModelEndpoint.Moving,
          FrameOwnerDescriptor.of(moving),
          FrameOwnerDescriptor.of(movingToFixed.source)
        )
      )
    else if !fixed.sameRuntimeOwnerAs(movingToFixed.target) then
      Left(
        AffineModelError.FrameOwnerMismatch(
          RigidModelEndpoint.Fixed,
          FrameOwnerDescriptor.of(fixed),
          FrameOwnerDescriptor.of(movingToFixed.target)
        )
      )
    else
      validateLinear(
        AffineGeometryKind.Candidate,
        linearOf(movingToFixed.operator),
        config.minimumCandidateSingularValue,
        config.maximumCandidateSingularValue
      )

  private def validateIncrement(
      increment: Array[Double]
  ): Either[AffineModelError, Unit] =
    if increment.length != parameterCount then
      Left(AffineModelError.InvalidIncrementLength(increment.length))
    else
      var index = 0
      while index < increment.length do
        val value = increment(index)
        if !value.isFinite then
          return Left(
            AffineModelError.NonFiniteValue(
              AffineModelQuantity.Increment,
              index,
              value
            )
          )
        index += 1
      index = 6
      while index < increment.length do
        if math.abs(increment(index)) > config.maximumAbsoluteStrainIncrement
        then
          return Left(
            AffineModelError.StrainIncrementOutOfBounds(
              index,
              increment(index),
              config.maximumAbsoluteStrainIncrement
            )
          )
        index += 1
      Right(())

  private def localLinearFrom(increment: Array[Double]): Array[Double] =
    val wx = increment(3)
    val wy = increment(4)
    val wz = increment(5)
    val xx = increment(6)
    val yy = increment(7)
    val zz = increment(8)
    val xy = increment(9)
    val xz = increment(10)
    val yz = increment(11)
    Array(
      1.0 + xx,
      xy - wz,
      xz + wy,
      xy + wz,
      1.0 + yy,
      yz - wx,
      xz - wy,
      yz + wx,
      1.0 + zz
    )

  private def localAffine(
      linear: Array[Double],
      increment: Array[Double]
  ): Either[AffineModelError, Affine[D3]] =
    val translatedX =
      pivotX + increment(0) -
        (linear(0) * pivotX + linear(1) * pivotY + linear(2) * pivotZ)
    val translatedY =
      pivotY + increment(1) -
        (linear(3) * pivotX + linear(4) * pivotY + linear(5) * pivotZ)
    val translatedZ =
      pivotZ + increment(2) -
        (linear(6) * pivotX + linear(7) * pivotY + linear(8) * pivotZ)
    Affine
      .fromRowMajor[D3](
        Vector(
          linear(0), linear(1), linear(2), translatedX,
          linear(3), linear(4), linear(5), translatedY,
          linear(6), linear(7), linear(8), translatedZ,
          0.0, 0.0, 0.0, 1.0
        )
      )
      .left
      .map(AffineModelError.Geometry.apply)

  private def validateLinear(
      kind: AffineGeometryKind,
      linear: Array[Double],
      minimumSingularValue: Double,
      maximumSingularValue: Double
  ): Either[AffineModelError, Unit] =
    val determinant = AffineModel3.determinant(linear)
    if !determinant.isFinite || determinant <= config.minimumDeterminant then
      Left(
        AffineModelError.NonPositiveDeterminant(
          kind,
          determinant,
          config.minimumDeterminant
        )
      )
    else
      val singular = AffineModel3.singularValueExtrema(linear)
      if
        singular._1 < minimumSingularValue || singular._2 > maximumSingularValue
      then
        Left(
          AffineModelError.SingularValueOutOfBounds(
            kind,
            singular._1,
            singular._2,
            minimumSingularValue,
            maximumSingularValue
          )
        )
      else Right(())

  private def linearOf(operator: Affine[D3]): Array[Double] =
    Array(
      operator.matrix(0, 0),
      operator.matrix(0, 1),
      operator.matrix(0, 2),
      operator.matrix(1, 0),
      operator.matrix(1, 1),
      operator.matrix(1, 2),
      operator.matrix(2, 0),
      operator.matrix(2, 1),
      operator.matrix(2, 2)
    )

  private def firstNonFinite(
      x: Double,
      y: Double,
      z: Double
  ): Option[(Int, Double)] =
    if !x.isFinite then Some(0 -> x)
    else if !y.isFinite then Some(1 -> y)
    else if !z.isFinite then Some(2 -> z)
    else None

private[flashalign] object AffineModel3:
  def atPivot[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: Moving,
      fixed: Fixed,
      pivot: Point[Fixed, D3],
      config: AffineModelConfig
  ): Either[AffineModelError, AffineModel3[Moving, Fixed]] =
    if !pivot.belongsTo(fixed) then
      Left(
        AffineModelError.FrameOwnerMismatch(
          RigidModelEndpoint.Pivot,
          FrameOwnerDescriptor.of(fixed),
          FrameOwnerDescriptor.of(pivot.frame)
        )
      )
    else
      val coordinates = pivot.coordinates
      atFixedWorld(moving, fixed, config)(
        coordinates(0),
        coordinates(1),
        coordinates(2)
      )

  def atFixedWorld[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: Moving,
      fixed: Fixed,
      config: AffineModelConfig
  )(
      pivotX: Double,
      pivotY: Double,
      pivotZ: Double
  ): Either[AffineModelError, AffineModel3[Moving, Fixed]] =
    val coordinates = Array(pivotX, pivotY, pivotZ)
    var index = 0
    while index < coordinates.length do
      if !coordinates(index).isFinite then
        return Left(
          AffineModelError.NonFiniteValue(
            AffineModelQuantity.FixedCoordinate,
            index,
            coordinates(index)
          )
        )
      index += 1
    Right(new AffineModel3(moving, fixed, pivotX, pivotY, pivotZ, config))

  private[flashalign] def determinant(matrix: Array[Double]): Double =
    matrix(0) * (matrix(4) * matrix(8) - matrix(5) * matrix(7)) -
      matrix(1) * (matrix(3) * matrix(8) - matrix(5) * matrix(6)) +
      matrix(2) * (matrix(3) * matrix(7) - matrix(4) * matrix(6))

  private[flashalign] def singularValueExtrema(
      matrix: Array[Double]
  ): (Double, Double) =
    val a00 = matrix(0) * matrix(0) + matrix(3) * matrix(3) +
      matrix(6) * matrix(6)
    val a01 = matrix(0) * matrix(1) + matrix(3) * matrix(4) +
      matrix(6) * matrix(7)
    val a02 = matrix(0) * matrix(2) + matrix(3) * matrix(5) +
      matrix(6) * matrix(8)
    val a11 = matrix(1) * matrix(1) + matrix(4) * matrix(4) +
      matrix(7) * matrix(7)
    val a12 = matrix(1) * matrix(2) + matrix(4) * matrix(5) +
      matrix(7) * matrix(8)
    val a22 = matrix(2) * matrix(2) + matrix(5) * matrix(5) +
      matrix(8) * matrix(8)
    val offDiagonalEnergy = a01 * a01 + a02 * a02 + a12 * a12
    val eigenvalues =
      if offDiagonalEnergy == 0.0 then Array(a00, a11, a22)
      else
        val mean = (a00 + a11 + a22) / 3.0
        val centeredEnergy =
          (a00 - mean) * (a00 - mean) +
            (a11 - mean) * (a11 - mean) +
            (a22 - mean) * (a22 - mean) +
            2.0 * offDiagonalEnergy
        val scale = math.sqrt(centeredEnergy / 6.0)
        val b00 = (a00 - mean) / scale
        val b01 = a01 / scale
        val b02 = a02 / scale
        val b11 = (a11 - mean) / scale
        val b12 = a12 / scale
        val b22 = (a22 - mean) / scale
        val halfDeterminant =
          0.5 * (
            b00 * (b11 * b22 - b12 * b12) -
              b01 * (b01 * b22 - b12 * b02) +
              b02 * (b01 * b12 - b11 * b02)
          )
        val phase = math.acos(math.max(-1.0, math.min(1.0, halfDeterminant))) / 3.0
        val largest = mean + 2.0 * scale * math.cos(phase)
        val smallest =
          mean + 2.0 * scale * math.cos(phase + 2.0 * math.Pi / 3.0)
        Array(largest, 3.0 * mean - largest - smallest, smallest)
    java.util.Arrays.sort(eigenvalues)
    (
      math.sqrt(math.max(0.0, eigenvalues(0))),
      math.sqrt(math.max(0.0, eigenvalues(2)))
    )

private[flashalign] final case class AffinePriorTerms(
    value: Double,
    gradient: Array[Double],
    curvatureUpper: Array[Double]
)

/** Rotation-invariant reference-relative Green-strain penalty. */
private[flashalign] final class AffineStrainPrior3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val model: AffineModel3[Moving, Fixed],
    val reference: FramedAffine[Moving, Fixed, D3],
    val weight: Double
):
  def evaluate(
      state: FramedAffine[Moving, Fixed, D3]
  ): Either[AffineModelError, AffinePriorTerms] =
    val output = ProjectedPatchQuadraticBuffer.create(model.parameterCount)
    linearize(state, output).map { _ =>
      AffinePriorTerms(
        output.objective,
        output.gradient.clone(),
        output.curvatureUpper.clone()
      )
    }

  def value(
      state: FramedAffine[Moving, Fixed, D3]
  ): Either[AffineModelError, Double] =
    model.validateMovingToFixed(state).map { _ =>
      val relative = relativeLinear(state)
      val strain = greenStrain(relative)
      0.5 * weight * inner(strain, strain)
    }

  def linearize(
      state: FramedAffine[Moving, Fixed, D3],
      output: ProjectedPatchQuadraticBuffer
  ): Either[AffineModelError, Unit] =
    if output.parameterCount != model.parameterCount then
      Left(AffineModelError.InvalidPriorOutputDimension(output.parameterCount))
    else
      model.validateMovingToFixed(state).map { _ =>
        output.clear()
        val relative = relativeLinear(state)
        val strain = greenStrain(relative)
        val first = Array.fill(model.parameterCount)(new Array[Double](9))
        var parameter = 0
        while parameter < model.parameterCount do
          first(parameter) = firstStrainDerivative(relative, parameter)
          output.gradient(parameter) =
            weight * inner(strain, first(parameter))
          parameter += 1
        output.setObjective(0.5 * weight * inner(strain, strain))
        var row = 0
        while row < model.parameterCount do
          var column = row
          while column < model.parameterCount do
            val second = secondStrainDerivative(relative, row, column)
            output.setCurvature(
              row,
              column,
              weight * (
                inner(first(row), first(column)) + inner(strain, second)
              )
            )
            column += 1
          row += 1
      }

  private def relativeLinear(
      state: FramedAffine[Moving, Fixed, D3]
  ): Array[Double] =
    val current = linearOf(state.operator)
    val referenceInverse = linearOf(reference.operator.inverse)
    multiply(current, referenceInverse)

  private def greenStrain(relative: Array[Double]): Array[Double] =
    val gram = multiply(transpose(relative), relative)
    gram(0) = 0.5 * (gram(0) - 1.0)
    gram(4) = 0.5 * (gram(4) - 1.0)
    gram(8) = 0.5 * (gram(8) - 1.0)
    var index = 0
    while index < gram.length do
      if index != 0 && index != 4 && index != 8 then gram(index) *= 0.5
      index += 1
    gram

  private def firstStrainDerivative(
      relative: Array[Double],
      parameter: Int
  ): Array[Double] =
    val basis = localBasis(parameter)
    val symmetric = add(transpose(basis), basis)
    scale(
      multiply(transpose(relative), multiply(symmetric, relative)),
      0.5
    )

  private def secondStrainDerivative(
      relative: Array[Double],
      firstParameter: Int,
      secondParameter: Int
  ): Array[Double] =
    val firstBasis = localBasis(firstParameter)
    val secondBasis = localBasis(secondParameter)
    val symmetricProduct = add(
      multiply(transpose(firstBasis), secondBasis),
      multiply(transpose(secondBasis), firstBasis)
    )
    scale(
      multiply(transpose(relative), multiply(symmetricProduct, relative)),
      0.5
    )

  private def localBasis(parameter: Int): Array[Double] =
    val basis = new Array[Double](9)
    parameter match
      case 3 =>
        basis(5) = -1.0
        basis(7) = 1.0
      case 4 =>
        basis(2) = 1.0
        basis(6) = -1.0
      case 5 =>
        basis(1) = -1.0
        basis(3) = 1.0
      case 6 => basis(0) = 1.0
      case 7 => basis(4) = 1.0
      case 8 => basis(8) = 1.0
      case 9 =>
        basis(1) = 1.0
        basis(3) = 1.0
      case 10 =>
        basis(2) = 1.0
        basis(6) = 1.0
      case 11 =>
        basis(5) = 1.0
        basis(7) = 1.0
      case _ => ()
    basis

  private def linearOf(operator: Affine[D3]): Array[Double] =
    Array(
      operator.matrix(0, 0), operator.matrix(0, 1), operator.matrix(0, 2),
      operator.matrix(1, 0), operator.matrix(1, 1), operator.matrix(1, 2),
      operator.matrix(2, 0), operator.matrix(2, 1), operator.matrix(2, 2)
    )

  private def multiply(left: Array[Double], right: Array[Double]): Array[Double] =
    val result = new Array[Double](9)
    var row = 0
    while row < 3 do
      var column = 0
      while column < 3 do
        var sum = 0.0
        var innerIndex = 0
        while innerIndex < 3 do
          sum += left(row * 3 + innerIndex) * right(innerIndex * 3 + column)
          innerIndex += 1
        result(row * 3 + column) = sum
        column += 1
      row += 1
    result

  private def transpose(matrix: Array[Double]): Array[Double] =
    Array(
      matrix(0), matrix(3), matrix(6),
      matrix(1), matrix(4), matrix(7),
      matrix(2), matrix(5), matrix(8)
    )

  private def add(left: Array[Double], right: Array[Double]): Array[Double] =
    Array.tabulate(9)(index => left(index) + right(index))

  private def scale(matrix: Array[Double], factor: Double): Array[Double] =
    Array.tabulate(9)(index => factor * matrix(index))

  private def inner(left: Array[Double], right: Array[Double]): Double =
    var sum = 0.0
    var index = 0
    while index < 9 do
      sum += left(index) * right(index)
      index += 1
    sum

private[flashalign] object AffineStrainPrior3:
  def create[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      model: AffineModel3[Moving, Fixed],
      reference: FramedAffine[Moving, Fixed, D3],
      weight: Double
  ): Either[AffineModelError, AffineStrainPrior3[Moving, Fixed]] =
    if !weight.isFinite || weight < 0.0 then
      Left(AffineModelError.InvalidPriorWeight(weight))
    else
      model
        .validateMovingToFixed(reference)
        .map(_ => new AffineStrainPrior3(model, reference, weight))
