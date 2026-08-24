package reframe4s.field

import image4s.BoundaryPolicy
import image4s.Continuous
import image4s.ContinuousImage
import image4s.SampleSpace
import ravel.AnyRank
import reframe4s.core.MapError
import reframe4s.core.FrameErasedMap
import reframe4s.core.SpatialMap
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point
import reframe4s.resample.Interpolation
import reframe4s.resample.SampledInterpolator

import scala.util.hashing.MurmurHash3

/** Boundary behavior for an absolute coordinate map.
  *
  * `PreserveSource` is the identity extension used by finite pullback fields:
  * outside sampled support, the map returns the query coordinate itself.
  */
enum CoordinateBoundaryPolicy derives CanEqual:
  case Reject
  case Constant(coordinates: Vector[Double])
  case PreserveSource

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
    val interpolation: Interpolation[Continuous],
    val boundary: CoordinateBoundaryPolicy
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
        interpolation,
        componentBoundary(point, component)
      ) match
        case Right(sample) => values += sample.value
        case Left(_) =>
          failure = Some(MapError.OutsideDomain(point.coordinates))
      component += 1
    failure.toLeft(values.result())

  private def componentBoundary(
      point: Point[From, D],
      component: Int
  ): BoundaryPolicy[Double] =
    boundary match
      case CoordinateBoundaryPolicy.Reject =>
        BoundaryPolicy.Reject
      case CoordinateBoundaryPolicy.Constant(values) =>
        BoundaryPolicy.Constant(values(component))
      case CoordinateBoundaryPolicy.PreserveSource =>
        BoundaryPolicy.Constant(point.coordinates(component))

object DenseMap:
  /** Stable structural fingerprint for provider-owned dense maps, including
    * maps whose endpoint refinements have been safely erased.
    */
  def fingerprint[D <: Dim](
      map: SpatialMap[Frame[D], Frame[D], D]
  ): Option[String] =
    underlyingDense(map).map: dense =>
      var hash = MurmurHash3.stringHash(
        s"dense-map-v1|${dense.interpolation}|${dense.boundary}|${dense.grid.shape}"
      )
      val affine = dense.grid.indexToFrame.rowMajor.toArray
      var affineIndex = 0
      while affineIndex < affine.length do
        hash = MurmurHash3.mix(hash, affine(affineIndex).hashCode)
        affineIndex += 1
      var count = affine.length
      dense.coordinates.data.foreachElement: value =>
        hash = MurmurHash3.mix(hash, value.hashCode)
        count += 1
      s"dense-map-v1:${java.lang.Integer.toHexString(MurmurHash3.finalizeHash(hash, count))}"

  def isDense[D <: Dim](
      map: SpatialMap[Frame[D], Frame[D], D]
  ): Boolean =
    underlyingDense(map).nonEmpty

  private def underlyingDense[D <: Dim](
      map: SpatialMap[Frame[D], Frame[D], D]
  ): Option[DenseMap[?, ?, D, ?]] =
    map match
      case dense: DenseMap[?, ?, D @unchecked, ?] => Some(dense)
      case erased: FrameErasedMap[D @unchecked] =>
        erased.underlying match
          case dense: DenseMap[?, ?, D @unchecked, ?] => Some(dense)
          case _ => None
      case _ => None

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
      interpolation: Interpolation[Continuous] = Interpolation.Linear,
      boundary: CoordinateBoundaryPolicy = CoordinateBoundaryPolicy.Reject
  )(using dimension: Dimension[D])
      : Either[FieldError, DenseMap[From, To, D, R]] =
    for
      _ <- DenseField.create[From, D, DisplacementKind, R](coordinates)
      _ <- validateBoundary(boundary, dimension.rank)
    yield new DenseMap(
      coordinates,
      target,
      interpolation,
      boundary
    )

  private def validateBoundary(
      boundary: CoordinateBoundaryPolicy,
      rank: Int
  ): Either[FieldError, Unit] =
    boundary match
      case CoordinateBoundaryPolicy.Constant(values)
          if values.length != rank =>
        Left(FieldError.InvalidBoundaryCoordinates(rank, values.length))
      case CoordinateBoundaryPolicy.Constant(values) =>
        values.zipWithIndex.collectFirst {
          case (value, component) if !value.isFinite =>
            FieldError.NonFiniteBoundaryCoordinate(component, value)
        }.toLeft(())
      case _ => Right(())
