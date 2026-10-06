package reframe4s.motion

import image4s.AxisKind
import image4s.BoundaryPolicy
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.Continuous
import ravel.AnyRank
import ravel.Rank
import ravel.select
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.core.MapError
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.FrameKey
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.multiscale.MultiscaleError
import reframe4s.register.RegistrationFailure
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingError
import reframe4s.resample.ResamplingPlan

enum MotionEndpoint:
  case Moving
  case Fixed

sealed trait MotionError derives CanEqual:
  def message: String

object MotionError:
  case object EmptySeries extends MotionError:
    val message: String = "a motion series must contain at least one pose"

  final case class TimeCountMismatch(times: Int, samples: Int)
      extends MotionError:
    val message: String =
      s"time count $times does not match sample count $samples"

  final case class NonFiniteTime(index: Int, value: Double)
      extends MotionError:
    val message: String = s"time $index must be finite, got $value"

  final case class NonIncreasingTime(
      previousIndex: Int,
      previous: Double,
      current: Double
  ) extends MotionError:
    val message: String =
      s"time ${previousIndex + 1} ($current) must be greater than " +
        s"time $previousIndex ($previous)"

  final case class SampleIndexOutOfBounds(index: Int, size: Int)
      extends MotionError:
    val message: String =
      s"motion sample index $index is outside [0, $size)"

  case object MissingTimeAxis extends MotionError:
    val message: String = "sampled motion data has no Time axis"

  final case class AmbiguousTimeAxis(count: Int) extends MotionError:
    val message: String = s"sampled motion data has $count Time axes"

  final case class TimeExtentMismatch(axisExtent: Int, times: Int)
      extends MotionError:
    val message: String =
      s"Time axis extent $axisExtent does not match $times time values"

  final case class MovingEndpointMismatch(
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends MotionError:
    val message: String =
      s"pose moving frame $actual does not match $expected"

  final case class FixedEndpointMismatch(
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends MotionError:
    val message: String =
      s"pose fixed frame $actual does not match $expected"

  final case class PersistentFrameRequired(
      endpoint: MotionEndpoint,
      frame: FrameOwnerDescriptor
  ) extends MotionError:
    val message: String =
      s"$endpoint motion frame $frame must have a persistent key before recording"

  final case class InsufficientSupport(
      sample: Int,
      available: Long,
      required: Long
  ) extends MotionError:
    val message: String =
      s"motion sample $sample has $available weighted voxels but requires $required"

  final case class InvalidMinimumSupport(value: Long) extends MotionError:
    val message: String =
      s"minimum motion support must be positive, got $value"

  final case class NonFiniteIntensity(
      spatialIndex: Vector[Int],
      value: Double
  ) extends MotionError:
    val message: String =
      s"motion intensity at ${spatialIndex.mkString("(", ",", ")")} " +
        s"must be finite, got $value"

  object Geometry:
    def apply(error: GeometryError | spatial4s.SpatialError): Geometry =
      new Geometry(image4s.geometry.GeometryError.fromCoordinate(error))

  final case class Geometry(error: GeometryError) extends MotionError:
    val message: String = error.message

  final case class Image(error: ImageError) extends MotionError:
    val message: String = error.message

  final case class Mapping(error: MapError) extends MotionError:
    val message: String = error.message

  final case class Registration(error: RegistrationFailure)
      extends MotionError:
    val message: String = error.message

  final case class Resampling(error: ResamplingError) extends MotionError:
    val message: String = error.message

  final case class Multiscale(error: MultiscaleError)
      extends MotionError:
    val message: String = error.message

  final case class RecordCountMismatch(
      times: Int,
      matrices: Int
  ) extends MotionError:
    val message: String =
      s"motion record has $times times and $matrices matrices"

  final case class Rigid(error: RigidError) extends MotionError:
    val message: String = error.message

  final case class RigidKernel(error: RigidKernelError)
      extends MotionError:
    val message: String = error.message

  final case class NonFiniteQueryTime(value: Double) extends MotionError:
    val message: String = s"motion query time must be finite, got $value"

  final case class QueryBeforeSeries(value: Double, earliest: Double)
      extends MotionError:
    val message: String =
      s"motion query time $value precedes earliest pose time $earliest"

  final case class QueryAfterSeries(value: Double, latest: Double)
      extends MotionError:
    val message: String =
      s"motion query time $value follows latest pose time $latest"

  final case class InvalidSliceExtent(value: Int) extends MotionError:
    val message: String =
      s"acquisition slice extent must be positive, got $value"

  final case class SliceOffsetCountMismatch(
      extent: Int,
      offsets: Int
  ) extends MotionError:
    val message: String =
      s"slice extent $extent does not match $offsets acquisition offsets"

  final case class NonFiniteAcquisitionOffset(
      index: Int,
      value: Double
  ) extends MotionError:
    val message: String =
      s"acquisition offset $index must be finite, got $value"

  final case class SliceIndexOutOfBounds(index: Int, extent: Int)
      extends MotionError:
    val message: String =
      s"acquisition slice $index is outside [0, $extent)"

  final case class DuplicateAcquisitionSlice(index: Int)
      extends MotionError:
    val message: String =
      s"acquisition slice $index occurs in more than one packet"

  final case class MissingAcquisitionSlices(indices: Vector[Int])
      extends MotionError:
    val message: String =
      s"acquisition schedule omits slices ${indices.mkString(",")}"

  final case class GridSliceExtentMismatch(
      axis: GridAxis,
      expected: Int,
      actual: Int
  ) extends MotionError:
    val message: String =
      s"${axis.toString} acquisition extent $expected does not match " +
        s"grid extent $actual"

  final case class DiagnosticCountMismatch(
      poses: Int,
      diagnostics: Int
  ) extends MotionError:
    val message: String =
      s"motion estimate has $poses poses and $diagnostics diagnostics"

  final case class UnsupportedMotionSeriesAxes(
      nonSpatialAxes: Int
  ) extends MotionError:
    val message: String =
      s"motion estimation requires exactly one non-spatial Time axis, got " +
        nonSpatialAxes

  final case class UnsupportedMotionDataRank(actual: Int)
      extends MotionError:
    val message: String =
      s"D3 plus Time motion data must have rank 4, got $actual"

  final case class InvalidHeadRadius(value: Double) extends MotionError:
    val message: String =
      s"head radius must be finite and positive, got $value"

  case object EmptyDisplacementSupport extends MotionError:
    val message: String =
      "pose displacement requires at least one physical support point"

  final case class InvalidEstimatorScalar(
      parameter: EstimatorScalar,
      value: Double
  ) extends MotionError:
    val message: String =
      s"$parameter has invalid value $value"

  final case class InvalidEstimatorCount(
      parameter: EstimatorCount,
      value: Int
  ) extends MotionError:
    val message: String =
      s"$parameter must be positive, got $value"

  final case class InvalidEstimatorStepOrder(
      step: EstimatorStep,
      minimum: Double,
      initial: Double
  ) extends MotionError:
    val message: String =
      s"minimum $step step $minimum exceeds initial step $initial"

  final case class InvalidDampingOrder(
      minimum: Double,
      initial: Double,
      maximum: Double
  ) extends MotionError:
    val message: String =
      s"rigid damping must satisfy minimum <= initial <= maximum, got " +
        s"$minimum <= $initial <= $maximum"

  final case class EstimatorHasNonSpatialAxes(
      input: EstimatorInput,
      count: Int
  ) extends MotionError:
    val message: String =
      s"$input estimator image must be spatial-only, got $count extra axes"

  final case class UnsupportedSpatialDataRank(
      input: EstimatorInput,
      actual: Int
  ) extends MotionError:
    val message: String =
      s"$input estimator image must have rank 3, got $actual"

  final case class NonFiniteEstimatorVoxel(
      input: EstimatorInput,
      i: Int,
      j: Int,
      k: Int,
      value: Double
  ) extends MotionError:
    val message: String =
      s"$input estimator voxel ($i,$j,$k) must be finite, got $value"

  final case class ZeroEstimatorSupport(input: EstimatorInput)
      extends MotionError:
    val message: String =
      s"$input estimator image has no non-zero intensity support"

  final case class InsufficientEstimatorOverlap(
      actual: Double,
      minimum: Double
  ) extends MotionError:
    val message: String =
      s"estimator overlap $actual is below required $minimum"

  case object EstimatorWorkspaceInUse extends MotionError:
    val message: String =
      "a rigid estimator workspace cannot be shared by concurrent runs"

  final case class InvalidReferenceIndex(index: Int, size: Int)
      extends MotionError:
    val message: String =
      s"motion reference index $index is outside [0, $size)"

  final case class FrameEstimationFailed(
      index: Int,
      cause: MotionError
  ) extends MotionError:
    val message: String =
      s"motion estimation failed at frame $index: ${cause.message}"

  case object SeriesEstimatorWorkspaceInUse extends MotionError:
    val message: String =
      "a rigid series estimator workspace cannot be shared by concurrent runs"

  final case class ScaleEstimationFailed(
      level: Int,
      cause: MotionError
  ) extends MotionError:
    val message: String =
      s"rigid estimation failed at scale level $level: ${cause.message}"

  case object MultiscaleEstimatorWorkspaceInUse extends MotionError:
    val message: String =
      "a multiscale rigid estimator workspace cannot be shared by concurrent runs"

  case object MultiscaleSeriesWorkspaceInUse extends MotionError:
    val message: String =
      "a multiscale rigid series workspace cannot be shared by concurrent runs"

  final case class InvalidTemplateRefreshPasses(
      value: Int,
      maximum: Int
  ) extends MotionError:
    val message: String =
      s"template refresh passes must be in [0, $maximum], got $value"

  case object TemplateEstimatorWorkspaceInUse extends MotionError:
    val message: String =
      "a template motion estimator workspace cannot be shared by concurrent runs"

  final case class TemplateRefreshFailed(
      pass: Int,
      cause: MotionError
  ) extends MotionError:
    val message: String =
      s"template refresh pass $pass failed: ${cause.message}"

  final case class PoseTimeMismatch(
      index: Int,
      sampleSeconds: Double,
      poseSeconds: Double
  ) extends MotionError:
    val message: String =
      s"sample time $index ($sampleSeconds) does not match pose time " +
        s"$index ($poseSeconds)"

  final case class MotionApplicationFailed(
      volume: Int,
      slice: Option[ApplicationSlice],
      cause: MotionError
  ) extends MotionError:
    val message: String =
      val location =
        slice.fold(s"volume $volume")(value =>
          s"volume $volume ${value.axis} slice ${value.index}"
        )
      s"motion application failed at $location: ${cause.message}"

  case object MotionApplicationWorkspaceInUse extends MotionError:
    val message: String =
      "a motion application workspace cannot be shared by concurrent runs"

  final case class UnsupportedApplicationOutputRank(actual: Int)
      extends MotionError:
    val message: String =
      s"motion application expected a rank-3 plan output, got rank $actual"

  case object ApplicationBuilderProtocolViolation extends MotionError:
    val message: String =
      "motion application did not complete its output builder protocol"

final class TimeAxis private (
    val seconds: Vector[Double],
    val startSeconds: Double,
    val endSeconds: Double
):
  def size: Int = seconds.size

object TimeAxis:
  def create(values: Vector[Double]): Either[MotionError, TimeAxis] =
    values.headOption match
      case None => Left(MotionError.EmptySeries)
      case Some(first) =>
        values.zipWithIndex.collectFirst {
          case (value, index) if !value.isFinite =>
            MotionError.NonFiniteTime(index, value)
        }.orElse(
          values.indices.drop(1).collectFirst {
            case index if values(index) <= values(index - 1) =>
              MotionError.NonIncreasingTime(
                index - 1,
                values(index - 1),
                values(index)
              )
          }
        ) match
          case Some(error) => Left(error)
          case None =>
            Right(
              new TimeAxis(
                values,
                first,
                values.lastOption.getOrElse(first)
              )
            )

final class TimedScalarSamples[
    F <: Frame[D3],
    R <: AnyRank
] private (
    val image: MotionScalarImage[F, D3, R],
    val times: TimeAxis,
    val timeAxisIndex: Int
):
  def volumeAt(
      index: Int
  ): Either[MotionError, MotionScalarImage[F, D3, Rank[3]]] =
    if image.nonSpatialAxes.size != 1 then
      Left(
        MotionError.UnsupportedMotionSeriesAxes(
          image.nonSpatialAxes.size
        )
      )
    else if index < 0 || index >= times.size then
      Left(MotionError.SampleIndexOutOfBounds(index, times.size))
    else
      for
        rankFour <- image.data
          .requireRank[4]
          .left
          .map(_ => MotionError.UnsupportedMotionDataRank(image.data.rank))
        volume = rankFour.select(3, index)
        sampled <- Sampled
          .continuous(image.grid, NonSpatialAxes.empty, volume)
          .left
          .map(MotionError.Image.apply)
      yield sampled

object TimedScalarSamples:
  def view[F <: Frame[D3], R <: AnyRank](
      image: MotionScalarImage[F, D3, R],
      times: TimeAxis
  ): Either[MotionError, TimedScalarSamples[F, R]] =
    val indices =
      image.nonSpatialAxes.values.zipWithIndex.collect {
        case (axis, index) if axis.kind == AxisKind.Time => index
      }
    indices match
      case Vector() => Left(MotionError.MissingTimeAxis)
      case Vector(index) =>
        val extent = image.nonSpatialAxes.values(index).extent
        if extent != times.size then
          Left(MotionError.TimeExtentMismatch(extent, times.size))
        else Right(new TimedScalarSamples(image, times, index))
      case multiple =>
        Left(MotionError.AmbiguousTimeAxis(multiple.size))

final class RigidPose[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val movingToFixed: Rigid3[Moving, Fixed]
):
  val fixedToMoving: Rigid3[Fixed, Moving] =
    movingToFixed.inverse

object RigidPose:
  def fromMovingToFixed[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      map: Rigid3[Moving, Fixed]
  ): RigidPose[Moving, Fixed] =
    new RigidPose(map)

final class PoseSeries[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val times: TimeAxis,
    val poses: Vector[RigidPose[Moving, Fixed]],
    val movingFrame: Moving,
    val fixedFrame: Fixed
):
  def size: Int = poses.size

  def poseAt(
      index: Int
  ): Either[MotionError, RigidPose[Moving, Fixed]] =
    poses
      .lift(index)
      .toRight(MotionError.SampleIndexOutOfBounds(index, poses.size))

  def record: Either[MotionError, PoseSeriesRecord] =
    for
      movingKey <- movingFrame.persistentKey.toRight(
        MotionError.PersistentFrameRequired(
          MotionEndpoint.Moving,
          FrameOwnerDescriptor.of(movingFrame)
        )
      )
      fixedKey <- fixedFrame.persistentKey.toRight(
        MotionError.PersistentFrameRequired(
          MotionEndpoint.Fixed,
          FrameOwnerDescriptor.of(fixedFrame)
        )
      )
    yield PoseSeriesRecord(
      movingKey,
      fixedKey,
      times.seconds,
      poses.map(_.movingToFixed.operator.rowMajor)
    )

object PoseSeries:
  def create[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      times: TimeAxis,
      poses: Vector[RigidPose[Moving, Fixed]]
  ): Either[MotionError, PoseSeries[Moving, Fixed]] =
    if poses.isEmpty then Left(MotionError.EmptySeries)
    else if times.size != poses.size then
      Left(MotionError.TimeCountMismatch(times.size, poses.size))
    else
      val first = poses.headOption.toRight(MotionError.EmptySeries)
      first.flatMap { head =>
        val moving = head.movingToFixed.source
        val fixed = head.movingToFixed.target
        poses.collectFirst {
          case pose
            if image4s.geometry.Frame
              .align(moving, pose.movingToFixed.source)
              .isLeft =>
            MotionError.MovingEndpointMismatch(
              FrameOwnerDescriptor.of(moving),
              FrameOwnerDescriptor.of(pose.movingToFixed.source)
            )
          case pose
            if image4s.geometry.Frame
              .align(fixed, pose.movingToFixed.target)
              .isLeft =>
            MotionError.FixedEndpointMismatch(
              FrameOwnerDescriptor.of(fixed),
              FrameOwnerDescriptor.of(pose.movingToFixed.target)
            )
        } match
          case Some(error) => Left(error)
          case None =>
            Right(new PoseSeries(times, poses, moving, fixed))
      }

  def restore[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      record: PoseSeriesRecord,
      moving: Moving,
      fixed: Fixed
  )(using Dimension[D3]): Either[MotionError, PoseSeries[Moving, Fixed]] =
    if !moving.persistentKey.contains(record.movingFrameKey) then
      Left(
        MotionError.MovingEndpointMismatch(
          FrameOwnerDescriptor.of(moving),
          FrameOwnerDescriptor(Some(record.movingFrameKey))
        )
      )
    else if !fixed.persistentKey.contains(record.fixedFrameKey) then
      Left(
        MotionError.FixedEndpointMismatch(
          FrameOwnerDescriptor.of(fixed),
          FrameOwnerDescriptor(Some(record.fixedFrameKey))
        )
      )
    else if record.timesSeconds.size != record.movingToFixedRowMajor.size then
      Left(
        MotionError.RecordCountMismatch(
          record.timesSeconds.size,
          record.movingToFixedRowMajor.size
        )
      )
    else
      for
        times <- TimeAxis.create(record.timesSeconds)
        poses <- sequence(
          record.movingToFixedRowMajor.map { values =>
            Rigid3
              .fromRowMajor(moving, fixed, values)
              .left
              .map(MotionError.Rigid.apply)
              .map(RigidPose.fromMovingToFixed)
          }
        )
        series <- create(times, poses)
      yield series

  private def sequence[A](
      values: Vector[Either[MotionError, A]]
  ): Either[MotionError, Vector[A]] =
    values.foldLeft[Either[MotionError, Vector[A]]](Right(Vector.empty)) {
      (accumulated, next) =>
        for
          values <- accumulated
          value <- next
        yield values :+ value
    }

final case class PoseSeriesRecord(
    movingFrameKey: FrameKey,
    fixedFrameKey: FrameKey,
    timesSeconds: Vector[Double],
    movingToFixedRowMajor: Vector[Vector[Double]]
) derives CanEqual

/**
 * A grid-index axis, deliberately distinct from an oriented physical axis.
 */
enum GridAxis(val index: Int):
  case I extends GridAxis(0)
  case J extends GridAxis(1)
  case K extends GridAxis(2)

enum ExtrapolationPolicy:
  case Reject
  case Clamp

final case class AcquisitionPacket(
    slices: Vector[Int],
    offsetSeconds: Double
) derives CanEqual

sealed trait AcquisitionSchedule:
  def sliceAxis: Option[GridAxis]

  def offsetSecondsAt(
      spatialIndex: LatticeIndex[D3]
  ): Either[MotionError, Double]

  def validateGrid[F <: Frame[D3]](
      grid: Grid[F, D3]
  ): Either[MotionError, Unit]

object AcquisitionSchedule:
  case object Volume extends AcquisitionSchedule:
    val sliceAxis: Option[GridAxis] = None

    def offsetSecondsAt(
        spatialIndex: LatticeIndex[D3]
    ): Either[MotionError, Double] =
      Right(0.0)

    def validateGrid[F <: Frame[D3]](
        grid: Grid[F, D3]
    ): Either[MotionError, Unit] =
      Right(())

  def slices(
      axis: GridAxis,
      offsetsSeconds: Vector[Double]
  ): Either[MotionError, AcquisitionSchedule] =
    checked(axis, offsetsSeconds)

  def packets(
      axis: GridAxis,
      extent: Int,
      packets: Vector[AcquisitionPacket]
  ): Either[MotionError, AcquisitionSchedule] =
    if extent <= 0 then Left(MotionError.InvalidSliceExtent(extent))
    else
      val offsets = Array.fill[Option[Double]](extent)(None)
      var packetIndex = 0
      var failure = Option.empty[MotionError]
      while packetIndex < packets.size && failure.isEmpty do
        val packet = packets(packetIndex)
        if !packet.offsetSeconds.isFinite then
          failure = Some(
            MotionError.NonFiniteAcquisitionOffset(
              packetIndex,
              packet.offsetSeconds
            )
          )
        else
          var memberIndex = 0
          while memberIndex < packet.slices.size && failure.isEmpty do
            val slice = packet.slices(memberIndex)
            if slice < 0 || slice >= extent then
              failure =
                Some(MotionError.SliceIndexOutOfBounds(slice, extent))
            else if offsets(slice).nonEmpty then
              failure = Some(MotionError.DuplicateAcquisitionSlice(slice))
            else offsets(slice) = Some(packet.offsetSeconds)
            memberIndex += 1
        packetIndex += 1
      failure match
        case Some(error) => Left(error)
        case None =>
          val missing =
            offsets.indices.filter(index => offsets(index).isEmpty).toVector
          if missing.nonEmpty then
            Left(MotionError.MissingAcquisitionSlices(missing))
          else checked(axis, offsets.iterator.collect { case Some(value) =>
            value
          }.toVector)

  private def checked(
      axis: GridAxis,
      offsetsSeconds: Vector[Double]
  ): Either[MotionError, AcquisitionSchedule] =
    if offsetsSeconds.isEmpty then Left(MotionError.InvalidSliceExtent(0))
    else
      offsetsSeconds.zipWithIndex.collectFirst {
        case (value, index) if !value.isFinite =>
          MotionError.NonFiniteAcquisitionOffset(index, value)
      } match
        case Some(error) => Left(error)
        case None =>
          Right(new SliceSchedule(axis, offsetsSeconds))

  private final class SliceSchedule(
      axis: GridAxis,
      offsetsSeconds: Vector[Double]
  ) extends AcquisitionSchedule:
    val sliceAxis: Option[GridAxis] = Some(axis)

    def offsetSecondsAt(
        spatialIndex: LatticeIndex[D3]
    ): Either[MotionError, Double] =
      val slice = spatialIndex.values(axis.index)
      offsetsSeconds
        .lift(slice)
        .toRight(
          MotionError.SliceIndexOutOfBounds(slice, offsetsSeconds.size)
        )

    def validateGrid[F <: Frame[D3]](
        grid: Grid[F, D3]
    ): Either[MotionError, Unit] =
      val actual = grid.shape(axis.index)
      if actual == offsetsSeconds.size then Right(())
      else
        Left(
          MotionError.GridSliceExtentMismatch(
            axis,
            offsetsSeconds.size,
            actual
          )
        )

final class PoseTrajectory[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val series: PoseSeries[Moving, Fixed]
):
  def poseAtSeconds(
      querySeconds: Double,
      extrapolation: ExtrapolationPolicy = ExtrapolationPolicy.Reject
  ): Either[MotionError, RigidPose[Moving, Fixed]] =
    if !querySeconds.isFinite then
      Left(MotionError.NonFiniteQueryTime(querySeconds))
    else
      val times = series.times.seconds
      val first = series.times.startSeconds
      val last = series.times.endSeconds
      if querySeconds < first then
        extrapolation match
          case ExtrapolationPolicy.Reject =>
            Left(MotionError.QueryBeforeSeries(querySeconds, first))
          case ExtrapolationPolicy.Clamp => series.poseAt(0)
      else if querySeconds > last then
        extrapolation match
          case ExtrapolationPolicy.Reject =>
            Left(MotionError.QueryAfterSeries(querySeconds, last))
          case ExtrapolationPolicy.Clamp => series.poseAt(series.size - 1)
      else
        val upper = firstAtOrAfter(times, querySeconds)
        if times(upper) == querySeconds || upper == 0 then
          series.poseAt(upper)
        else
          for
            before <- series.poseAt(upper - 1)
            after <- series.poseAt(upper)
            fraction =
              (querySeconds - times(upper - 1)) /
                (times(upper) - times(upper - 1))
            interpolated <- before.movingToFixed
              .interpolateTarget(after.movingToFixed, fraction)
              .left
              .map(MotionError.Rigid.apply)
          yield RigidPose.fromMovingToFixed(interpolated)

  def poseForAcquisition(
      volumeIndex: Int,
      spatialIndex: LatticeIndex[D3],
      schedule: AcquisitionSchedule,
      extrapolation: ExtrapolationPolicy = ExtrapolationPolicy.Reject
  ): Either[MotionError, RigidPose[Moving, Fixed]] =
    for
      volumeTime <- series.times.seconds
        .lift(volumeIndex)
        .toRight(
          MotionError.SampleIndexOutOfBounds(volumeIndex, series.size)
        )
      offset <- schedule.offsetSecondsAt(spatialIndex)
      pose <- poseAtSeconds(volumeTime + offset, extrapolation)
    yield pose

  private def firstAtOrAfter(
      times: Vector[Double],
      querySeconds: Double
  ): Int =
    var low = 0
    var high = times.size - 1
    while low < high do
      val middle = low + (high - low) / 2
      if times(middle) < querySeconds then low = middle + 1
      else high = middle
    low

object PoseTrajectory:
  def fromSeries[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      series: PoseSeries[Moving, Fixed]
  ): PoseTrajectory[Moving, Fixed] =
    new PoseTrajectory(series)

final case class FrameEstimateDiagnostics(
    index: Int,
    support: Long,
    translationMagnitudePhysical: Double
) derives CanEqual

final class MotionEstimate[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val poses: PoseSeries[Moving, Fixed],
    val diagnostics: Vector[FrameEstimateDiagnostics]
)

object MotionEstimate:
  def create[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      poses: PoseSeries[Moving, Fixed],
      diagnostics: Vector[FrameEstimateDiagnostics]
  ): Either[MotionError, MotionEstimate[Moving, Fixed]] =
    if poses.size != diagnostics.size then
      Left(
        MotionError.DiagnosticCountMismatch(
          poses.size,
          diagnostics.size
        )
      )
    else Right(new MotionEstimate(poses, diagnostics))

object PhysicalCentroidTranslation:
  def estimate[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      RMoving <: AnyRank,
      RFixed <: AnyRank
  ](
      moving: MotionScalarImage[Moving, D3, RMoving],
      fixed: MotionScalarImage[Fixed, D3, RFixed],
      minimumSupport: Long = 1L
  )(using Dimension[D3])
      : Either[
        MotionError,
        (RigidPose[Moving, Fixed], FrameEstimateDiagnostics)
      ] =
    if minimumSupport <= 0L then
      Left(MotionError.InvalidMinimumSupport(minimumSupport))
    else
      for
        movingCenter <- centroid(moving, minimumSupport, sample = 0)
        fixedCenter <- centroid(fixed, minimumSupport, sample = 0)
        offset =
          fixedCenter._1.zip(movingCenter._1).map((fixed, moving) =>
            fixed - moving
          )
        map <- Rigid3
          .translationBetween[Moving, Fixed](
            moving.frame,
            fixed.frame
          )(offset(0), offset(1), offset(2))
          .left
          .map(MotionError.Rigid.apply)
      yield
        val magnitude =
          math.sqrt(offset.iterator.map(value => value * value).sum)
        RigidPose.fromMovingToFixed(map) ->
          FrameEstimateDiagnostics(0, movingCenter._2, magnitude)

  def estimateSeries[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      RMoving <: AnyRank,
      RFixed <: AnyRank
  ](
      moving: Vector[MotionScalarImage[Moving, D3, RMoving]],
      fixed: MotionScalarImage[Fixed, D3, RFixed],
      times: TimeAxis,
      minimumSupport: Long = 1L
  )(using Dimension[D3])
      : Either[MotionError, MotionEstimate[Moving, Fixed]] =
    if moving.isEmpty then Left(MotionError.EmptySeries)
    else if moving.size != times.size then
      Left(MotionError.TimeCountMismatch(times.size, moving.size))
    else
      val poses = Vector.newBuilder[RigidPose[Moving, Fixed]]
      val diagnostics = Vector.newBuilder[FrameEstimateDiagnostics]
      var index = 0
      var failure = Option.empty[MotionError]
      while index < moving.size && failure.isEmpty do
        estimate(moving(index), fixed, minimumSupport) match
          case Left(MotionError.InsufficientSupport(_, available, required)) =>
            failure = Some(
              MotionError.InsufficientSupport(index, available, required)
            )
          case Left(error) =>
            failure = Some(error)
          case Right((pose, report)) =>
            poses += pose
            diagnostics += report.copy(index = index)
        index += 1
      for
        values <- failure.toLeft(poses.result())
        series <- PoseSeries.create(times, values)
        estimate <- MotionEstimate.create(series, diagnostics.result())
      yield estimate

  private def centroid[
      F <: Frame[D3],
      R <: AnyRank
  ](
      image: MotionScalarImage[F, D3, R],
      minimumSupport: Long,
      sample: Int
  )(using Dimension[D3])
      : Either[MotionError, (Vector[Double], Long)] =
    if image.nonSpatialAxes.size != 0 then
      Left(MotionError.InsufficientSupport(sample, 0L, minimumSupport))
    else
      var weightSum = 0.0
      var weighted = Vector(0.0, 0.0, 0.0)
      var support = 0L
      var failure = Option.empty[MotionError]
      image.data.foreachIndex { rawIndex =>
        if failure.isEmpty then
          val spatial = Vector(rawIndex(0), rawIndex(1), rawIndex(2))
          val intensity = image.data.at(rawIndex)
          if !intensity.isFinite then
            failure = Some(
              MotionError.NonFiniteIntensity(spatial, intensity)
            )
          else if intensity != 0.0 then
            val weight = math.abs(intensity)
            LatticeIndex.fromVector[D3](spatial).flatMap(image.grid.pointAt) match
              case Left(error) =>
                failure = Some(MotionError.Geometry(error))
              case Right(point) =>
                weighted = weighted.zip(point.coordinates).map {
                  case (total, coordinate) => total + weight * coordinate
                }
                weightSum += weight
                support += 1L
      }
      failure match
        case Some(error) => Left(error)
        case None if support < minimumSupport || weightSum <= 0.0 =>
          Left(
            MotionError.InsufficientSupport(
              sample,
              support,
              minimumSupport
            )
          )
        case None =>
          Right(weighted.map(_ / weightSum) -> support)

object MotionApplication:
  def planAt[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      R <: AnyRank
  ](
      moving: MotionScalarImage[Moving, D3, R],
      fixedGrid: Grid[Fixed, D3],
      pose: RigidPose[Moving, Fixed],
      interpolation: Interpolation[Continuous] = Interpolation.Linear,
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using Dimension[D3])
      : Either[
        MotionError,
        ResamplingPlan[Moving, Fixed, D3, Continuous, R]
      ] =
    ResamplingPlan
      .affine(
        moving,
        fixedGrid,
        pose.fixedToMoving,
        interpolation,
        boundary
      )
      .left
      .map(MotionError.Resampling.apply)
