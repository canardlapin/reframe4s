package reframe4s.field

import image4s.BoundaryPolicy
import image4s.ContinuousImage
import image4s.SampleSpace
import ravel.AnyRank
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point
import reframe4s.resample.Interpolation
import reframe4s.resample.SampledInterpolator

/** An absolute target-coordinate field sampled on a source-frame grid.
  *
  * This is an ordinary spatial map. Piecewise interpolation and finite
  * topology checks do not manufacture `SmoothMap` or `SmoothIso` capability.
  */
final class DenseMap[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim,
    R <: AnyRank
] private (
    val coordinates: ContinuousImage[
      ? <: SampleSpace[From, D],
      Double,
      R
    ],
    val target: To,
    val boundary: BoundaryPolicy[Double]
)(using private val dimension: Dimension[D])
    extends SpatialMap[From, To, D]:
  val source: From = coordinates.frame
  val grid = coordinates.grid

  def apply(point: Point[From, D]): Either[MapError, Point[To, D]] =
    for
      _ <- SpatialMap.validateSourcePoint(source, point)
      values <- interpolate(point)
      result <- Point
        .fromVector(target, values)
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

  private def interpolate(
      point: Point[From, D]
  ): Either[MapError, Vector[Double]] =
    val values = Vector.newBuilder[Double]
    var component = 0
    var failure = Option.empty[MapError]
    while component < dimension.rank && failure.isEmpty do
      SampledInterpolator.at(
        coordinates,
        point,
        Vector(component),
        Interpolation.Linear,
        boundary
      ) match
        case Right(sample) => values += sample.value
        case Left(_) =>
          failure = Some(MapError.OutsideDomain(point.coordinates))
      component += 1
    failure.toLeft(values.result())

object DenseMap:
  def fromCoordinates[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      coordinates: ContinuousImage[
        ? <: SampleSpace[From, D],
        Double,
        R
      ],
      target: To,
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using dimension: Dimension[D])
      : Either[FieldError, DenseMap[From, To, D, R]] =
    DenseField
      .create[From, D, DisplacementKind, R](coordinates)
      .map(_ => new DenseMap(coordinates, target, boundary))
