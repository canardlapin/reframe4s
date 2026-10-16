package reframe4s.lie

import gale.linalg.DMat
import reframe4s.core.AffineMap
import reframe4s.core.Jet1
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import image4s.geometry.Affine
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Point

final class FramedAffine[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim
] private (
    val source: From,
    val target: To,
    val operator: Affine[D]
)(using dimension: Dimension[D]) extends AffineMap[From, To, D]:
  def apply(point: Point[From, D]): Either[MapError, Point[To, D]] =
    for
      _ <- SpatialMap.validateSourcePoint(source, point)
      coordinates <- operator(point.coordinates).left.map(MapError.Geometry.apply)
      result <- Point
        .fromVector(target, coordinates)
        .left
        .map(MapError.Geometry.apply)
      alignment <- Frame
        .alignOwners[D, target.type, To](target, target)
        .left
        .map(MapError.Geometry.apply)
      rebound <- alignment
        .pointToRight(result)
        .left
        .map(MapError.Geometry.apply)
    yield rebound

  def jet1At(
      point: Point[From, D]
  ): Either[MapError, Jet1[From, To, D]] =
    apply(point).flatMap(value =>
      Jet1.create[From, To, D](value, linearPart)
    )

  def inverse: FramedAffine[To, From, D] =
    new FramedAffine(target, source, operator.inverse)

  private def linearPart: DMat =
    DMat.dense(
      dimension.rank,
      dimension.rank,
      Vector.tabulate(dimension.rank * dimension.rank) { flat =>
        val row = flat / dimension.rank
        val column = flat % dimension.rank
        operator.matrix(row, column)
      }
    )

object FramedAffine:
  def betweenFrames[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      source: From,
      target: To
  )(
      operator: Affine[D]
  )(using Dimension[D]): FramedAffine[From, To, D] =
    new FramedAffine[From, To, D](source, target, operator)

  def between[D <: Dim](
      source: Frame[D],
      target: Frame[D]
  )(
      operator: Affine[D]
  )(using Dimension[D]): FramedAffine[source.type, target.type, D] =
    betweenFrames[source.type, target.type, D](source, target)(operator)

  def identity[D <: Dim](
      frame: Frame[D]
  )(using Dimension[D]): FramedAffine[frame.type, frame.type, D] =
    between(frame, frame)(Affine.identity[D])

  def translation[D <: Dim](
      source: Frame[D],
      target: Frame[D]
  )(
      offset: Double*
  )(using dimension: Dimension[D])
      : Either[
        GeometryError,
        FramedAffine[source.type, target.type, D]
      ] =
    translationBetween[source.type, target.type, D](source, target)(offset*)

  def translationBetween[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      source: From,
      target: To
  )(
      offset: Double*
  )(using dimension: Dimension[D])
      : Either[
        GeometryError,
        FramedAffine[From, To, D]
      ] =
    val values = offset.toVector
    if values.length != dimension.rank then
      Left(GeometryError.DimensionMismatch(dimension.rank, values.length))
    else if values.exists(value => !value.isFinite) then
      val axis = values.indexWhere(value => !value.isFinite)
      Left(GeometryError.NonFiniteCoordinate(axis, values(axis)))
    else
      val size = dimension.rank + 1
      val homogeneous = Vector.tabulate(size * size) { flat =>
        val row = flat / size
        val column = flat % size
        if row < dimension.rank && row == column then 1.0
        else if row < dimension.rank && column == dimension.rank then
          values(row)
        else if row == dimension.rank && column == dimension.rank then 1.0
        else 0.0
      }
      Affine
        .fromRowMajor[D](homogeneous)
        .map(operator => betweenFrames(source, target)(operator))
