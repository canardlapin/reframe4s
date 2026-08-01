package reframe4s.laws

import reframe4s.core.MapError
import reframe4s.core.SmoothIso
import reframe4s.core.SpatialMap
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point

object MapLaws:
  def leftIdentity[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      map: SpatialMap[From, To, D],
      point: Point[From, D],
      tolerance: Double
  )(using Dimension[D]): Either[MapError, Boolean] =
    val identity = SpatialMap.identity(map.source)
    for
      expected <- map(point)
      actual <- identity.andThen(map)(point)
    yield approximatelySame(actual, expected, tolerance)

  def rightIdentity[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      map: SpatialMap[From, To, D],
      point: Point[From, D],
      tolerance: Double
  )(using Dimension[D]): Either[MapError, Boolean] =
    val identity = SpatialMap.identity(map.target)
    for
      expected <- map(point)
      actual <- map.andThen(identity)(point)
    yield approximatelySame(actual, expected, tolerance)

  def leftInverse[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      map: SmoothIso[From, To, D],
      point: Point[From, D],
      tolerance: Double
  ): Either[MapError, Boolean] =
    map(point).flatMap(map.inverse.apply).map(
      approximatelySame(_, point, tolerance)
    )

  def rightInverse[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      map: SmoothIso[From, To, D],
      point: Point[To, D],
      tolerance: Double
  ): Either[MapError, Boolean] =
    map.inverse(point).flatMap(map.apply).map(
      approximatelySame(_, point, tolerance)
    )

  def associative[
      A <: Frame[D],
      B <: Frame[D],
      C <: Frame[D],
      E <: Frame[D],
      D <: Dim
  ](
      first: SpatialMap[A, B, D],
      second: SpatialMap[B, C, D],
      third: SpatialMap[C, E, D],
      point: Point[A, D],
      tolerance: Double
  )(using Dimension[D]): Either[MapError, Boolean] =
    val left = first.andThen(second).andThen(third)
    val right = first.andThen(second.andThen(third))
    for
      leftValue <- left(point)
      rightValue <- right(point)
    yield approximatelySame(leftValue, rightValue, tolerance)

  private def approximatelySame[F <: Frame[D], D <: Dim](
      left: Point[F, D],
      right: Point[F, D],
      tolerance: Double
  ): Boolean =
    left.belongsTo(right.frame) &&
      right.belongsTo(left.frame) &&
      left.coordinates
        .zip(right.coordinates)
        .forall((a, b) => math.abs(a - b) <= tolerance)
