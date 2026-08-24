package reframe4s.field

import image4s.AxisKind
import image4s.BoundaryPolicy
import image4s.ContinuousImage
import image4s.ImageError
import image4s.SampleSpace
import ravel.AnyRank
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Point
import image4s.geometry.Vec
import reframe4s.resample.Interpolation
import reframe4s.resample.SampledInterpolator

sealed trait DenseFieldKind
sealed trait DisplacementKind extends DenseFieldKind
sealed trait VelocityKind extends DenseFieldKind
sealed trait MomentumKind extends DenseFieldKind

sealed trait FieldError derives CanEqual:
  def message: String

object FieldError:
  final case class Image(error: ImageError) extends FieldError:
    val message: String = error.message

  final case class Geometry(error: GeometryError) extends FieldError:
    val message: String = error.message

  final case class InvalidComponentAxes(
      expectedExtent: Int,
      actual: Vector[(AxisKind, Int)]
  ) extends FieldError:
    val message: String =
      "dense fields require exactly one Direction axis of extent " +
        s"$expectedExtent, got ${actual.mkString("[", ", ", "]")}"

  final case class NonFiniteComponent(flatIndex: Long, value: Double)
      extends FieldError:
    val message: String =
      s"dense field component $flatIndex must be finite, got $value"

  final case class InvalidBoundaryCoordinates(
      expected: Int,
      actual: Int
  ) extends FieldError:
    val message: String =
      s"coordinate boundary requires $expected values, got $actual"

  final case class NonFiniteBoundaryCoordinate(
      component: Int,
      value: Double
  ) extends FieldError:
    val message: String =
      s"coordinate boundary component $component must be finite, got $value"

final class DenseField[
    F <: Frame[D],
    D <: Dim,
    Kind <: DenseFieldKind,
    R <: AnyRank
] private[field] (
    val samples: ContinuousImage[
      ? <: SampleSpace[F, D],
      Double,
      R
    ]
):
  val grid = samples.grid
  val frame: F = samples.frame

  def at(
      point: Point[F, D],
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using dimension: Dimension[D]): Either[FieldError, Vec[F, D]] =
    atInterpolated(point, Interpolation.Linear, boundary)

  def atInterpolated(
      point: Point[F, D],
      interpolation: Interpolation[image4s.Continuous],
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using dimension: Dimension[D]): Either[FieldError, Vec[F, D]] =
    val values = Vector.newBuilder[Double]
    var component = 0
    var failure = Option.empty[FieldError]
    while component < dimension.rank && failure.isEmpty do
      SampledInterpolator.at(
        samples,
        point,
        Vector(component),
        interpolation,
        boundary
      ) match
        case Right(sample) => values += sample.value
        case Left(error)   => failure = Some(FieldError.Image(error))
      component += 1
    failure match
      case Some(error) => Left(error)
      case None =>
        for
          vector <- Vec
            .fromVector(frame, values.result())
            .left
            .map(FieldError.Geometry.apply)
          alignment <- Frame
            .alignOwners[D, frame.type, F](frame, frame)
            .left
            .map(FieldError.Geometry.apply)
          result <- alignment
            .vectorToRight(vector)
            .left
            .map(FieldError.Geometry.apply)
        yield result

type Displacement[
    F <: Frame[D],
    D <: Dim,
    R <: AnyRank
] = DenseField[F, D, DisplacementKind, R]

type Velocity[
    F <: Frame[D],
    D <: Dim,
    R <: AnyRank
] = DenseField[F, D, VelocityKind, R]

type Momentum[
    F <: Frame[D],
    D <: Dim,
    R <: AnyRank
] = DenseField[F, D, MomentumKind, R]

object DenseField:
  private[field] def create[
      F <: Frame[D],
      D <: Dim,
      Kind <: DenseFieldKind,
      R <: AnyRank
  ](
      samples: ContinuousImage[
        ? <: SampleSpace[F, D],
        Double,
        R
      ]
  )(using dimension: Dimension[D])
      : Either[FieldError, DenseField[F, D, Kind, R]] =
    val axes =
      samples.nonSpatialAxes.values.map(axis => axis.kind -> axis.extent)
    if axes != Vector(AxisKind.Direction -> dimension.rank) then
      Left(FieldError.InvalidComponentAxes(dimension.rank, axes))
    else
      var flatIndex = 0L
      var invalid = Option.empty[(Long, Double)]
      samples.data.foreachElement { value =>
        if invalid.isEmpty && !value.isFinite then
          invalid = Some(flatIndex -> value)
        flatIndex += 1L
      }
      invalid match
        case Some((index, value)) =>
          Left(FieldError.NonFiniteComponent(index, value))
        case None =>
          Right(new DenseField(samples))

object Displacement:
  def from[
      F <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      samples: ContinuousImage[
        ? <: SampleSpace[F, D],
        Double,
        R
      ]
  )(using Dimension[D]): Either[FieldError, Displacement[F, D, R]] =
    DenseField.create[F, D, DisplacementKind, R](samples)

  def asMap[
      F <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      displacement: Displacement[F, D, R],
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using Dimension[D]): SpatialMap[F, F, D] =
    asInterpolatedMap(displacement, Interpolation.Linear, boundary)

  def asInterpolatedMap[
      F <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      displacement: Displacement[F, D, R],
      interpolation: Interpolation[image4s.Continuous],
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using Dimension[D]): SpatialMap[F, F, D] =
    new SpatialMap[F, F, D]:
      val source: F = displacement.frame
      val target: F = displacement.frame

      def apply(point: Point[F, D]): Either[MapError, Point[F, D]] =
        for
          _ <- SpatialMap.validateSourcePoint(source, point)
          vector <- displacement
            .atInterpolated(point, interpolation, boundary)
            .left
            .map(_ => MapError.OutsideDomain(point.coordinates))
          result = point + vector
        yield result

object Velocity:
  def from[
      F <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      samples: ContinuousImage[
        ? <: SampleSpace[F, D],
        Double,
        R
      ]
  )(using Dimension[D]): Either[FieldError, Velocity[F, D, R]] =
    DenseField.create[F, D, VelocityKind, R](samples)

object Momentum:
  def from[
      F <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      samples: ContinuousImage[
        ? <: SampleSpace[F, D],
        Double,
        R
      ]
  )(using Dimension[D]): Either[FieldError, Momentum[F, D, R]] =
    DenseField.create[F, D, MomentumKind, R](samples)
