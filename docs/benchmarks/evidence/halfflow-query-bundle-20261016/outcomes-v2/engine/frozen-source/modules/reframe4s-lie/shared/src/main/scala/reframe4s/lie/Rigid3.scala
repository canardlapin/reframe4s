package reframe4s.lie

import reframe4s.core.AffineMap
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.core.Jet1
import reframe4s.core.MapError
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Point

sealed trait RigidError derives CanEqual:
  def message: String

object RigidError:
  object Geometry:
    def apply(error: GeometryError | spatial4s.SpatialError): Geometry =
      new Geometry(image4s.geometry.GeometryError.fromCoordinate(error))

  final case class Geometry(error: GeometryError) extends RigidError:
    val message: String = error.message

  final case class InvalidTolerance(value: Double, maximum: Double)
      extends RigidError:
    val message: String =
      s"rigid validation tolerance $value must be finite and within " +
        s"[0, $maximum]"

  final case class RotationNotOrthonormal(
      maximumError: Double,
      tolerance: Double
  ) extends RigidError:
    val message: String =
      s"rotation orthonormality error $maximumError exceeds $tolerance"

  final case class ImproperRotation(
      determinant: Double,
      tolerance: Double
  ) extends RigidError:
    val message: String =
      s"rotation determinant $determinant differs from +1 by more than " +
        tolerance

  final case class TwistFrameMismatch(
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends RigidError:
    val message: String =
      s"twist frame $actual does not match $expected"

  final case class NonFiniteTwist(index: Int, value: Double)
      extends RigidError:
    val message: String =
      s"twist component $index must be finite, got $value"

  final case class InvalidInterpolationFraction(value: Double)
      extends RigidError:
    val message: String =
      s"interpolation fraction must be finite and within [0, 1], got $value"

/**
 * A body velocity expressed in one live frame.
 *
 * Components are ordered as linear velocity followed by angular velocity.
 */
final class Twist6[F <: Frame[D3]] private (
    val frame: F,
    val linear: Vector[Double],
    val angular: Vector[Double]
):
  def components: Vector[Double] =
    linear ++ angular

  def belongsTo(candidate: Frame[D3]): Boolean =
    frame eq candidate

  def scaled(factor: Double): Either[RigidError, Twist6[F]] =
    Twist6.fromVectors(frame)(
      linear.map(_ * factor),
      angular.map(_ * factor)
    )

object Twist6:
  def create(
      frame: Frame[D3]
  )(
      vx: Double,
      vy: Double,
      vz: Double,
      wx: Double,
      wy: Double,
      wz: Double
  ): Either[RigidError, Twist6[frame.type]] =
    createFor[frame.type](frame)(vx, vy, vz, wx, wy, wz)

  def createFor[F <: Frame[D3]](
      frame: F
  )(
      vx: Double,
      vy: Double,
      vz: Double,
      wx: Double,
      wy: Double,
      wz: Double
  ): Either[RigidError, Twist6[F]] =
    fromVectors(frame)(
      Vector(vx, vy, vz),
      Vector(wx, wy, wz)
    )

  def zero[F <: Frame[D3]](frame: F): Twist6[F] =
    new Twist6(frame, Vector.fill(3)(0.0), Vector.fill(3)(0.0))

  def fromVectors[F <: Frame[D3]](
      frame: F
  )(
      linear: Vector[Double],
      angular: Vector[Double]
  ): Either[RigidError, Twist6[F]] =
    val values = linear ++ angular
    if linear.length != 3 then
      Left(
        RigidError.Geometry(
          GeometryError.DimensionMismatch(3, linear.length)
        )
      )
    else if angular.length != 3 then
      Left(
        RigidError.Geometry(
          GeometryError.DimensionMismatch(3, angular.length)
        )
      )
    else
      values.zipWithIndex.collectFirst {
        case (value, index) if !value.isFinite =>
          RigidError.NonFiniteTwist(index, value)
      } match
        case Some(error) => Left(error)
        case None        => Right(new Twist6(frame, linear, angular))

/**
 * A validated proper rigid transform between two live three-dimensional
 * frames.
 *
 * `operator` is the same authoritative [[Affine]] used by geometry and
 * resampling. The additional capability proves that its linear part belongs to
 * SO(3).
 */
final class Rigid3[
    From <: Frame[D3],
    To <: Frame[D3]
] private (
    val source: From,
    val target: To,
    val operator: Affine[D3],
    val validationTolerance: Double
)(using Dimension[D3]) extends AffineMap[From, To, D3]:
  private val affine =
    FramedAffine.betweenFrames(source, target)(operator)

  def apply(point: Point[From, D3]): Either[MapError, Point[To, D3]] =
    affine(point)

  def jet1At(
      point: Point[From, D3]
  ): Either[MapError, Jet1[From, To, D3]] =
    affine.jet1At(point)

  def inverse: Rigid3[To, From] =
    new Rigid3(
      target,
      source,
      operator.inverse,
      validationTolerance
    )

  def andThenRigid[Next <: Frame[D3]](
      next: Rigid3[To, Next]
  ): Either[RigidError, Rigid3[From, Next]] =
    for
      _ <- Frame
        .align(target, next.source)
        .left
        .map(RigidError.Geometry.apply)
      composed <- operator
        .andThen(next.operator)
        .left
        .map(RigidError.Geometry.apply)
      result <- Rigid3.fromAffine(
        source,
        next.target,
        tolerance = math.max(
          validationTolerance,
          next.validationTolerance
        )
      )(composed)
    yield result

  /**
   * Return the target-frame group increment from this transform to `next`.
   */
  def targetDeltaTo(
      next: Rigid3[From, To]
  ): Either[RigidError, Twist6[To]] =
    for
      _ <- Frame
        .align(source, next.source)
        .left
        .map(RigidError.Geometry.apply)
      _ <- Frame
        .align(target, next.target)
        .left
        .map(RigidError.Geometry.apply)
      relative <- inverse.andThenRigid(next)
      delta <- Rigid3.log(relative)
    yield delta

  /**
   * Apply a target-frame increment: `exp(delta) * this`.
   */
  def retractTarget(
      delta: Twist6[To]
  ): Either[RigidError, Rigid3[From, To]] =
    for
      _ <- Frame
        .align(target, delta.frame)
        .left
        .map(_ =>
          RigidError.TwistFrameMismatch(
            FrameOwnerDescriptor.of(target),
            FrameOwnerDescriptor.of(delta.frame)
          )
        )
      rebound <- Twist6.fromVectors(target)(delta.linear, delta.angular)
      increment <- Rigid3.exp(rebound)
      result <- andThenRigid(increment)
    yield result

  def interpolateTarget(
      next: Rigid3[From, To],
      fraction: Double
  ): Either[RigidError, Rigid3[From, To]] =
    if !fraction.isFinite || fraction < 0.0 || fraction > 1.0 then
      Left(RigidError.InvalidInterpolationFraction(fraction))
    else
      for
        delta <- targetDeltaTo(next)
        portion <- delta.scaled(fraction)
        result <- retractTarget(portion)
      yield result

  /**
   * Change the coordinate frame of a twist through this rigid transform.
   */
  def adjoint(
      twist: Twist6[From]
  ): Either[RigidError, Twist6[To]] =
    Frame.align(source, twist.frame) match
      case Left(_) =>
        Left(
          RigidError.TwistFrameMismatch(
            FrameOwnerDescriptor.of(source),
            FrameOwnerDescriptor.of(twist.frame)
          )
        )
      case Right(_) =>
        val rotation = RigidMath.rotation(operator)
        val translation = RigidMath.translation(operator)
        val rotatedLinear = RigidMath.multiplyVector(rotation, twist.linear)
        val rotatedAngular = RigidMath.multiplyVector(rotation, twist.angular)
        val transported =
          RigidMath.add(
            rotatedLinear,
            RigidMath.cross(translation, rotatedAngular)
          )
        Twist6.fromVectors(target)(transported, rotatedAngular)

object Rigid3:
  val DefaultTolerance: Double = 1e-9
  val MaximumTolerance: Double = 1e-5

  def fromAffine[
      From <: Frame[D3],
      To <: Frame[D3]
  ](
      source: From,
      target: To,
      tolerance: Double = DefaultTolerance
  )(
      operator: Affine[D3]
  )(using Dimension[D3]): Either[RigidError, Rigid3[From, To]] =
    if !tolerance.isFinite ||
      tolerance < 0.0 ||
      tolerance > MaximumTolerance
    then Left(RigidError.InvalidTolerance(tolerance, MaximumTolerance))
    else
      val rotation = RigidMath.rotation(operator)
      val orthonormality = RigidMath.orthonormalityError(rotation)
      val determinant = RigidMath.determinant(rotation)
      if orthonormality > tolerance then
        Left(
          RigidError.RotationNotOrthonormal(
            orthonormality,
            tolerance
          )
        )
      else if math.abs(determinant - 1.0) > tolerance then
        Left(RigidError.ImproperRotation(determinant, tolerance))
      else
        Right(
          new Rigid3(
            source,
            target,
            operator,
            tolerance
          )
        )

  def fromRowMajor[
      From <: Frame[D3],
      To <: Frame[D3]
  ](
      source: From,
      target: To,
      values: IterableOnce[Double],
      tolerance: Double = DefaultTolerance
  )(using Dimension[D3]): Either[RigidError, Rigid3[From, To]] =
    for
      operator <- Affine
        .fromRowMajor[D3](values, tolerance)
        .left
        .map(RigidError.Geometry.apply)
      rigid <- fromAffine(source, target, tolerance)(operator)
    yield rigid

  def identity[F <: Frame[D3]](
      frame: F
  )(using Dimension[D3]): Rigid3[F, F] =
    new Rigid3(
      frame,
      frame,
      Affine.identity[D3],
      DefaultTolerance
    )

  def translation(
      source: Frame[D3],
      target: Frame[D3]
  )(
      x: Double,
      y: Double,
      z: Double
  )(using
      Dimension[D3]
  ): Either[
    RigidError,
    Rigid3[source.type, target.type]
  ] =
    translationBetween[source.type, target.type](
      source,
      target
    )(x, y, z)

  def translationBetween[
      From <: Frame[D3],
      To <: Frame[D3]
  ](
      source: From,
      target: To
  )(
      x: Double,
      y: Double,
      z: Double
  )(using Dimension[D3]): Either[RigidError, Rigid3[From, To]] =
    FramedAffine
      .translationBetween(source, target)(x, y, z)
      .left
      .map(RigidError.Geometry.apply)
      .flatMap(map => fromAffine(source, target)(map.operator))

  def exp[F <: Frame[D3]](
      twist: Twist6[F]
  )(using Dimension[D3]): Either[RigidError, Rigid3[F, F]] =
    val rotation = RigidMath.expRotation(twist.angular)
    val translation =
      RigidMath.multiplyVector(
        RigidMath.leftJacobian(twist.angular),
        twist.linear
      )
    val values =
      Vector(
        rotation(0),
        rotation(1),
        rotation(2),
        translation(0),
        rotation(3),
        rotation(4),
        rotation(5),
        translation(1),
        rotation(6),
        rotation(7),
        rotation(8),
        translation(2),
        0.0,
        0.0,
        0.0,
        1.0
      )
    fromRowMajor(twist.frame, twist.frame, values)

  def log[F <: Frame[D3]](
      transform: Rigid3[F, F]
  ): Either[RigidError, Twist6[F]] =
    val angular = RigidMath.logRotation(
      RigidMath.rotation(transform.operator)
    )
    val inverseJacobian =
      RigidMath.leftJacobianInverse(angular)
    val linear =
      RigidMath.multiplyVector(
        inverseJacobian,
        RigidMath.translation(transform.operator)
      )
    Twist6.fromVectors(transform.source)(linear, angular)

private object RigidMath:
  private val SmallAngleSquared = 1e-12

  def rotation(operator: Affine[D3]): Vector[Double] =
    Vector.tabulate(9) { flat =>
      operator.matrix(flat / 3, flat % 3)
    }

  def translation(operator: Affine[D3]): Vector[Double] =
    Vector(
      operator.matrix(0, 3),
      operator.matrix(1, 3),
      operator.matrix(2, 3)
    )

  def determinant(matrix: Vector[Double]): Double =
    matrix(0) * (matrix(4) * matrix(8) - matrix(5) * matrix(7)) -
      matrix(1) * (matrix(3) * matrix(8) - matrix(5) * matrix(6)) +
      matrix(2) * (matrix(3) * matrix(7) - matrix(4) * matrix(6))

  def orthonormalityError(matrix: Vector[Double]): Double =
    var maximum = 0.0
    var row = 0
    while row < 3 do
      var column = 0
      while column < 3 do
        var sum = 0.0
        var index = 0
        while index < 3 do
          sum += matrix(index * 3 + row) * matrix(index * 3 + column)
          index += 1
        val expected = if row == column then 1.0 else 0.0
        maximum = math.max(maximum, math.abs(sum - expected))
        column += 1
      row += 1
    maximum

  def expRotation(angular: Vector[Double]): Vector[Double] =
    val thetaSquared = dot(angular, angular)
    val (a, b) =
      if thetaSquared < SmallAngleSquared then
        val thetaFourth = thetaSquared * thetaSquared
        (
          1.0 - thetaSquared / 6.0 + thetaFourth / 120.0,
          0.5 - thetaSquared / 24.0 + thetaFourth / 720.0
        )
      else
        val theta = math.sqrt(thetaSquared)
        (
          math.sin(theta) / theta,
          (1.0 - math.cos(theta)) / thetaSquared
        )
    val skew = skewMatrix(angular)
    val skewSquared = multiplyMatrix(skew, skew)
    add(identity, add(scale(skew, a), scale(skewSquared, b)))

  def leftJacobian(angular: Vector[Double]): Vector[Double] =
    val thetaSquared = dot(angular, angular)
    val (b, c) =
      if thetaSquared < SmallAngleSquared then
        val thetaFourth = thetaSquared * thetaSquared
        (
          0.5 - thetaSquared / 24.0 + thetaFourth / 720.0,
          1.0 / 6.0 - thetaSquared / 120.0 + thetaFourth / 5040.0
        )
      else
        val theta = math.sqrt(thetaSquared)
        (
          (1.0 - math.cos(theta)) / thetaSquared,
          (theta - math.sin(theta)) / (thetaSquared * theta)
        )
    val skew = skewMatrix(angular)
    val skewSquared = multiplyMatrix(skew, skew)
    add(identity, add(scale(skew, b), scale(skewSquared, c)))

  def leftJacobianInverse(angular: Vector[Double]): Vector[Double] =
    val thetaSquared = dot(angular, angular)
    val coefficient =
      if thetaSquared < SmallAngleSquared then
        val thetaFourth = thetaSquared * thetaSquared
        1.0 / 12.0 + thetaSquared / 720.0 + thetaFourth / 30240.0
      else
        val theta = math.sqrt(thetaSquared)
        (
          1.0 -
            theta * math.cos(theta / 2.0) /
            (2.0 * math.sin(theta / 2.0))
        ) / thetaSquared
    val skew = skewMatrix(angular)
    val skewSquared = multiplyMatrix(skew, skew)
    add(
      identity,
      add(scale(skew, -0.5), scale(skewSquared, coefficient))
    )

  def logRotation(rotation: Vector[Double]): Vector[Double] =
    val quaternion = quaternionFromRotation(rotation)
    val vectorNorm =
      math.sqrt(
        quaternion(1) * quaternion(1) +
          quaternion(2) * quaternion(2) +
          quaternion(3) * quaternion(3)
      )
    if vectorNorm < 1e-15 then
      Vector(
        2.0 * quaternion(1),
        2.0 * quaternion(2),
        2.0 * quaternion(3)
      )
    else
      val angle = 2.0 * math.atan2(vectorNorm, quaternion(0))
      val factor = angle / vectorNorm
      Vector(
        quaternion(1) * factor,
        quaternion(2) * factor,
        quaternion(3) * factor
      )

  def multiplyVector(
      matrix: Vector[Double],
      vector: Vector[Double]
  ): Vector[Double] =
    Vector.tabulate(3) { row =>
      matrix(row * 3) * vector(0) +
        matrix(row * 3 + 1) * vector(1) +
        matrix(row * 3 + 2) * vector(2)
    }

  def multiplyMatrix(
      left: Vector[Double],
      right: Vector[Double]
  ): Vector[Double] =
    Vector.tabulate(9) { flat =>
      val row = flat / 3
      val column = flat % 3
      left(row * 3) * right(column) +
        left(row * 3 + 1) * right(3 + column) +
        left(row * 3 + 2) * right(6 + column)
    }

  def add(left: Vector[Double], right: Vector[Double]): Vector[Double] =
    left.zip(right).map(_ + _)

  def scale(values: Vector[Double], factor: Double): Vector[Double] =
    values.map(_ * factor)

  def cross(left: Vector[Double], right: Vector[Double]): Vector[Double] =
    Vector(
      left(1) * right(2) - left(2) * right(1),
      left(2) * right(0) - left(0) * right(2),
      left(0) * right(1) - left(1) * right(0)
    )

  private val identity =
    Vector(
      1.0,
      0.0,
      0.0,
      0.0,
      1.0,
      0.0,
      0.0,
      0.0,
      1.0
    )

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.zip(right).map(_ * _).sum

  private def skewMatrix(vector: Vector[Double]): Vector[Double] =
    Vector(
      0.0,
      -vector(2),
      vector(1),
      vector(2),
      0.0,
      -vector(0),
      -vector(1),
      vector(0),
      0.0
    )

  private def quaternionFromRotation(
      rotation: Vector[Double]
  ): Vector[Double] =
    val trace = rotation(0) + rotation(4) + rotation(8)
    val raw =
      if trace > 0.0 then
        val scale = 2.0 * math.sqrt(trace + 1.0)
        Vector(
          0.25 * scale,
          (rotation(7) - rotation(5)) / scale,
          (rotation(2) - rotation(6)) / scale,
          (rotation(3) - rotation(1)) / scale
        )
      else if rotation(0) >= rotation(4) && rotation(0) >= rotation(8) then
        val scale =
          2.0 * math.sqrt(
            math.max(0.0, 1.0 + rotation(0) - rotation(4) - rotation(8))
          )
        Vector(
          (rotation(7) - rotation(5)) / scale,
          0.25 * scale,
          (rotation(1) + rotation(3)) / scale,
          (rotation(2) + rotation(6)) / scale
        )
      else if rotation(4) >= rotation(8) then
        val scale =
          2.0 * math.sqrt(
            math.max(0.0, 1.0 + rotation(4) - rotation(0) - rotation(8))
          )
        Vector(
          (rotation(2) - rotation(6)) / scale,
          (rotation(1) + rotation(3)) / scale,
          0.25 * scale,
          (rotation(5) + rotation(7)) / scale
        )
      else
        val scale =
          2.0 * math.sqrt(
            math.max(0.0, 1.0 + rotation(8) - rotation(0) - rotation(4))
          )
        Vector(
          (rotation(3) - rotation(1)) / scale,
          (rotation(2) + rotation(6)) / scale,
          (rotation(5) + rotation(7)) / scale,
          0.25 * scale
        )
    val norm = math.sqrt(raw.map(value => value * value).sum)
    val normalized = raw.map(_ / norm)
    if normalized(0) < 0.0 then normalized.map(-_)
    else if normalized(0) == 0.0 then
      val firstNonZero = normalized.drop(1).find(_ != 0.0)
      if firstNonZero.exists(_ < 0.0) then normalized.map(-_) else normalized
    else normalized
