package reframe4s.motion

import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Point

final class HeadRadius private (
    val millimeters: Double
)

object HeadRadius:
  val default: HeadRadius =
    new HeadRadius(50.0)

  def create(value: Double): Either[MotionError, HeadRadius] =
    if value.isFinite && value > 0.0 then Right(new HeadRadius(value))
    else Left(MotionError.InvalidHeadRadius(value))

final class FramewiseDisplacement private (
    val previousIndex: Int,
    val currentIndex: Int,
    val millimeters: Double
)

object FramewiseDisplacement:
  private[motion] def measured(
      previousIndex: Int,
      currentIndex: Int,
      millimeters: Double
  ): FramewiseDisplacement =
    new FramewiseDisplacement(previousIndex, currentIndex, millimeters)

final class PoseDisplacementSummary private (
    val meanMillimeters: Double,
    val medianMillimeters: Double,
    val p95Millimeters: Double,
    val maximumMillimeters: Double
)

object PoseDisplacementSummary:
  private[motion] def measured(
      meanMillimeters: Double,
      medianMillimeters: Double,
      p95Millimeters: Double,
      maximumMillimeters: Double
  ): PoseDisplacementSummary =
    new PoseDisplacementSummary(
      meanMillimeters,
      medianMillimeters,
      p95Millimeters,
      maximumMillimeters
    )

object MotionMetrics:
  /**
   * Power-style framewise displacement computed from relative SE(3) logs.
   *
   * The first sample has no predecessor and is represented by zero.
   */
  def framewiseDisplacement[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      series: PoseSeries[Moving, Fixed],
      radius: HeadRadius = HeadRadius.default
  ): Either[MotionError, Vector[Double]] =
    framewiseDisplacementPairs(series, radius).map { pairs =>
      val values = Array.fill(series.size)(0.0)
      pairs.foreach(pair => values(pair.currentIndex) = pair.millimeters)
      values.toVector
    }

  def framewiseDisplacementPairs[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      series: PoseSeries[Moving, Fixed],
      radius: HeadRadius = HeadRadius.default
  ): Either[MotionError, Vector[FramewiseDisplacement]] =
    val result = Vector.newBuilder[FramewiseDisplacement]
    var index = 1
    var failure = Option.empty[MotionError]
    while index < series.size && failure.isEmpty do
      val previous = series.poses(index - 1)
      val current = series.poses(index)
      previous.movingToFixed.targetDeltaTo(current.movingToFixed) match
        case Left(error) =>
          failure = Some(MotionError.Rigid(error))
        case Right(delta) =>
          val translation =
            delta.linear.iterator.map(math.abs).sum
          val rotation =
            delta.angular.iterator.map(math.abs).sum
          result += FramewiseDisplacement.measured(
            index - 1,
            index,
            translation + radius.millimeters * rotation
          )
      index += 1
    failure.toLeft(result.result())

  def pointDisplacements[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      pose: RigidPose[Moving, Fixed],
      reference: RigidPose[Moving, Fixed],
      support: Vector[Point[Moving, D3]]
  ): Either[MotionError, Vector[Double]] =
    if support.isEmpty then Left(MotionError.EmptyDisplacementSupport)
    else
      val result = Vector.newBuilder[Double]
      var index = 0
      var failure = Option.empty[MotionError]
      while index < support.size && failure.isEmpty do
        val point = support(index)
        (
          pose.movingToFixed(point),
          reference.movingToFixed(point)
        ) match
          case (Right(actual), Right(expected)) =>
            val squared =
              actual.coordinates
                .zip(expected.coordinates)
                .iterator
                .map { case (left, right) =>
                  val difference = left - right
                  difference * difference
                }
                .sum
            result += math.sqrt(squared)
          case (Left(error), _) =>
            failure = Some(MotionError.Mapping(error))
          case (_, Left(error)) =>
            failure = Some(MotionError.Mapping(error))
        index += 1
      failure.toLeft(result.result())

  def displacementSummary[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      pose: RigidPose[Moving, Fixed],
      reference: RigidPose[Moving, Fixed],
      support: Vector[Point[Moving, D3]]
  ): Either[MotionError, PoseDisplacementSummary] =
    pointDisplacements(pose, reference, support).flatMap { values =>
      val sorted = values.sorted
      sorted.lastOption
        .map(maximum =>
          PoseDisplacementSummary.measured(
            meanMillimeters = values.sum / values.size.toDouble,
            medianMillimeters = quantile(sorted, 0.5),
            p95Millimeters = quantile(sorted, 0.95),
            maximumMillimeters = maximum
          )
        )
        .toRight(MotionError.EmptyDisplacementSupport)
    }

  private def quantile(
      sorted: Vector[Double],
      probability: Double
  ): Double =
    if sorted.size == 1 then sorted(0)
    else
      val position = (sorted.size - 1).toDouble * probability
      val lower = math.floor(position).toInt
      val upper = math.ceil(position).toInt
      if lower == upper then sorted(lower)
      else
        val fraction = position - lower.toDouble
        sorted(lower) * (1.0 - fraction) + sorted(upper) * fraction
