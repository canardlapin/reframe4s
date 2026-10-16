package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Point
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.lie.Twist6

private[flashalign] enum RigidModelEndpoint derives CanEqual:
  case Moving, Fixed, Pivot

private[flashalign] enum RigidModelQuantity derives CanEqual:
  case Increment, FixedCoordinate, FixedGradient

private[flashalign] sealed trait RigidModelError derives CanEqual:
  def message: String

private[flashalign] object RigidModelError:
  final case class FrameOwnerMismatch(
      endpoint: RigidModelEndpoint,
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends RigidModelError:
    val message: String =
      s"$endpoint owner $actual does not match $expected"

  final case class InvalidIncrementLength(actual: Int)
      extends RigidModelError:
    val message: String =
      s"a rigid Flashalign increment requires 6 values, got $actual"

  final case class NonFiniteValue(
      quantity: RigidModelQuantity,
      index: Int,
      value: Double
  ) extends RigidModelError:
    val message: String =
      s"$quantity value $index must be finite, got $value"

  final case class InvalidJacobianSlice(offset: Int, capacity: Int)
      extends RigidModelError:
    val message: String =
      s"cannot write 6 rigid Jacobian values at offset $offset into capacity $capacity"

  final case class Rigid(error: RigidError) extends RigidModelError:
    val message: String = error.message

/**
 * Flashalign's physical rigid parameterization around one frozen fixed-world
 * pivot.
 *
 * A local step is ordered as fixed-world translation followed by rotation.
 * The step is converted to an origin-centred target-frame [[Twist6]] and
 * applied through the canonical left retraction on [[Rigid3]]. Coordinates
 * and image gradients accepted by the hot Jacobian method are therefore
 * fixed-world millimetres and fixed-world intensity derivatives.
 */
private[flashalign] final class RigidModel3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val moving: Moving,
    val fixed: Fixed,
    private[flashalign] val pivotX: Double,
    private[flashalign] val pivotY: Double,
    private[flashalign] val pivotZ: Double
):
  val parameterCount: Int = 6

  /**
   * Apply a fixed-world pivot-centred target-frame increment to the current
   * moving-to-fixed transform.
   */
  def propose(
      movingToFixed: Rigid3[Moving, Fixed],
      increment: Array[Double]
  ): Either[RigidModelError, Rigid3[Moving, Fixed]] =
    for
      _ <- validateEndpoints(movingToFixed)
      twist <- pivotCenteredTwist(increment)
      candidate <- movingToFixed
        .retractTarget(twist)
        .left
        .map(RigidModelError.Rigid.apply)
    yield candidate

  /**
   * Convert `[dt, omega]` about the frozen pivot into the origin-centred twist
   * `[dt - omega cross pivot, omega]` required by `Rigid3.retractTarget`.
   */
  def pivotCenteredTwist(
      increment: Array[Double]
  ): Either[RigidModelError, Twist6[Fixed]] =
    validateIncrement(increment).flatMap { _ =>
      val dx = increment(0)
      val dy = increment(1)
      val dz = increment(2)
      val wx = increment(3)
      val wy = increment(4)
      val wz = increment(5)
      val crossX = wy * pivotZ - wz * pivotY
      val crossY = wz * pivotX - wx * pivotZ
      val crossZ = wx * pivotY - wy * pivotX
      Twist6
        .createFor[Fixed](fixed)(
          dx - crossX,
          dy - crossY,
          dz - crossZ,
          wx,
          wy,
          wz
        )
        .left
        .map(RigidModelError.Rigid.apply)
    }

  /**
   * Write `d F(y) / d [dt, omega]` for `y` in the fixed frame.
   *
   * The columns are `[gradient, (y - pivot) cross gradient]`. The method is
   * allocation-free so it can be used once per interpolated patch sample.
   */
  def writeIntensityJacobianAtFixedWorld(
      fixedX: Double,
      fixedY: Double,
      fixedZ: Double,
      gradientX: Double,
      gradientY: Double,
      gradientZ: Double,
      output: Array[Double],
      outputOffset: Int = 0
  ): Either[RigidModelError, Unit] =
    if outputOffset < 0 || outputOffset > output.length - parameterCount then
      Left(
        RigidModelError.InvalidJacobianSlice(outputOffset, output.length)
      )
    else
      firstNonFinite(fixedX, fixedY, fixedZ) match
        case Some((index, value)) =>
          Left(
            RigidModelError.NonFiniteValue(
              RigidModelQuantity.FixedCoordinate,
              index,
              value
            )
          )
        case None =>
          firstNonFinite(gradientX, gradientY, gradientZ) match
            case Some((index, value)) =>
              Left(
                RigidModelError.NonFiniteValue(
                  RigidModelQuantity.FixedGradient,
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

  private[flashalign] def validateMovingToFixed(
      movingToFixed: Rigid3[Moving, Fixed]
  ): Either[RigidModelError, Unit] =
    validateEndpoints(movingToFixed)

  private def validateEndpoints(
      movingToFixed: Rigid3[Moving, Fixed]
  ): Either[RigidModelError, Unit] =
    if !moving.sameRuntimeOwnerAs(movingToFixed.source) then
      Left(
        RigidModelError.FrameOwnerMismatch(
          RigidModelEndpoint.Moving,
          FrameOwnerDescriptor.of(moving),
          FrameOwnerDescriptor.of(movingToFixed.source)
        )
      )
    else if !fixed.sameRuntimeOwnerAs(movingToFixed.target) then
      Left(
        RigidModelError.FrameOwnerMismatch(
          RigidModelEndpoint.Fixed,
          FrameOwnerDescriptor.of(fixed),
          FrameOwnerDescriptor.of(movingToFixed.target)
        )
      )
    else Right(())

  private def validateIncrement(
      increment: Array[Double]
  ): Either[RigidModelError, Unit] =
    if increment.length != parameterCount then
      Left(RigidModelError.InvalidIncrementLength(increment.length))
    else
      var index = 0
      var failure = Option.empty[RigidModelError]
      while index < increment.length && failure.isEmpty do
        val value = increment(index)
        if !value.isFinite then
          failure = Some(
            RigidModelError.NonFiniteValue(
              RigidModelQuantity.Increment,
              index,
              value
            )
          )
        index += 1
      failure.toLeft(())

  private def firstNonFinite(
      x: Double,
      y: Double,
      z: Double
  ): Option[(Int, Double)] =
    if !x.isFinite then Some(0 -> x)
    else if !y.isFinite then Some(1 -> y)
    else if !z.isFinite then Some(2 -> z)
    else None

private[flashalign] object RigidModel3:
  def atPivot[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: Moving,
      fixed: Fixed,
      pivot: Point[Fixed, D3]
  ): Either[
    RigidModelError,
    RigidModel3[Moving, Fixed]
  ] =
    if !pivot.belongsTo(fixed) then
      Left(
        RigidModelError.FrameOwnerMismatch(
          RigidModelEndpoint.Pivot,
          FrameOwnerDescriptor.of(fixed),
          FrameOwnerDescriptor.of(pivot.frame)
        )
      )
    else
      val coordinates = pivot.coordinates
      atFixedWorld(moving, fixed)(
        coordinates(0),
        coordinates(1),
        coordinates(2)
      )

  def atFixedWorld[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: Moving,
      fixed: Fixed
  )(
      pivotX: Double,
      pivotY: Double,
      pivotZ: Double
  ): Either[
    RigidModelError,
    RigidModel3[Moving, Fixed]
  ] =
    val coordinates = Array(pivotX, pivotY, pivotZ)
    var index = 0
    var failure = Option.empty[RigidModelError]
    while index < coordinates.length && failure.isEmpty do
      val value = coordinates(index)
      if !value.isFinite then
        failure = Some(
          RigidModelError.NonFiniteValue(
            RigidModelQuantity.FixedCoordinate,
            index,
            value
          )
        )
      index += 1
    failure match
      case Some(error) => Left(error)
      case None =>
        Right(
          new RigidModel3(
            moving,
            fixed,
            pivotX,
            pivotY,
            pivotZ
          )
        )
