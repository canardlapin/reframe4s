package reframe4s.core

import gale.linalg.DMat
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point

/** Provider-owned numerical differential for maps that do not claim smooth
  * capability.
  *
  * Central differences preserve the map's checked frame ownership and
  * propagate domain failures instead of manufacturing a smooth-map witness.
  */
object SpatialDifferential:
  def centralDifference[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      map: SpatialMap[From, To, D],
      point: Point[From, D],
      step: Double
  )(using dimension: Dimension[D])
      : Either[MapError, Jet1[From, To, D]] =
    if !step.isFinite || step <= 0.0 then
      Left(MapError.InvalidFiniteDifferenceStep(step))
    else
      for
        _ <- SpatialMap.validateSourcePoint(map.source, point)
        value <- map(point)
        columns <- differenceColumns(map, point, step)
        differential =
          DMat.dense(
            dimension.rank,
            dimension.rank,
            Vector.tabulate(dimension.rank * dimension.rank): flat =>
              val row = flat / dimension.rank
              val column = flat % dimension.rank
              columns(column)(row)
          )
        jet <- Jet1.create[From, To, D](value, differential)
      yield jet

  private def differenceColumns[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      map: SpatialMap[From, To, D],
      point: Point[From, D],
      step: Double
  )(using dimension: Dimension[D])
      : Either[MapError, Vector[Vector[Double]]] =
    var column = 0
    var failure = Option.empty[MapError]
    val result = Vector.newBuilder[Vector[Double]]
    while column < dimension.rank && failure.isEmpty do
      differenceColumn(map, point, column, step) match
        case Left(error)   => failure = Some(error)
        case Right(values) => result += values
      column += 1
    failure.toLeft(result.result())

  private def differenceColumn[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      map: SpatialMap[From, To, D],
      point: Point[From, D],
      column: Int,
      step: Double
  )(using dimension: Dimension[D])
      : Either[MapError, Vector[Double]] =
    val plusCoordinates = point.coordinates.updated(
      column,
      point.coordinates(column) + step
    )
    val minusCoordinates = point.coordinates.updated(
      column,
      point.coordinates(column) - step
    )
    for
      plusPoint <- sourcePoint(map.source, plusCoordinates)
      minusPoint <- sourcePoint(map.source, minusCoordinates)
      plus <- map(plusPoint)
      minus <- map(minusPoint)
    yield Vector.tabulate(dimension.rank): row =>
      (plus.coordinates(row) - minus.coordinates(row)) / (2.0 * step)

  private def sourcePoint[
      F <: Frame[D],
      D <: Dim
  ](
      frame: F,
      coordinates: Vector[Double]
  )(using Dimension[D]): Either[MapError, Point[F, D]] =
    for
      raw <- Point
        .fromVector(frame, coordinates)
        .left
        .map(MapError.Geometry.apply)
      alignment <- Frame
        .alignOwners[D, frame.type, F](frame, frame)
        .left
        .map(MapError.Geometry.apply)
      rebound <- alignment
        .pointToRight(raw)
        .left
        .map(MapError.Geometry.apply)
    yield rebound
