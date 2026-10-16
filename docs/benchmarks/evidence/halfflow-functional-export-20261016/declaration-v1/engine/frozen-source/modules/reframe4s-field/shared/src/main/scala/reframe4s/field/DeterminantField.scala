package reframe4s.field

import image4s.ContinuousImage
import image4s.MaskImage
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.SampleSpace
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Shape
import image4s.geometry.Dim
import image4s.geometry.Frame
import image4s.geometry.Grid

/** Which volume change a determinant field reports for a map `phi`.
  *
  * `Pull` is `det D phi(x)`. `Push` is `1 / det D phi(x)`: the determinant of
  * the inverse at `phi(x)`, still indexed by the lattice point `x`. No
  * inverse is computed, and folded points have no push value.
  */
enum DeterminantDirection derives CanEqual:
  case Pull
  case Push

/** A determinant at one lattice point. `Folded` carries the pull
  * determinant, which is non-positive.
  */
enum DeterminantValue derives CanEqual:
  case Regular(value: Double)
  case Folded(pullDeterminant: Double)

/** A log-determinant at one lattice point; never `NaN` or `-Inf`. */
enum LogDeterminant derives CanEqual:
  case Finite(value: Double)
  case NonPositive(pullDeterminant: Double)

/** Jacobian determinants of a dense map on its own lattice, in physical
  * units. Folds (`det <= 0`) are reported by mask and count; the value images
  * hold `0.0` there and must be read together with the masks.
  */
final class DeterminantField[F <: Frame[D], D <: Dim] private[field] (
    val grid: Grid[F, D],
    val direction: DeterminantDirection,
    private val pull: Array[Double],
    val values: ContinuousImage[? <: SampleSpace[F, D], Double, AnyRank],
    val foldMask: MaskImage[? <: SampleSpace[F, D], AnyRank],
    val foldCount: Long,
    val logDeterminant: LogDeterminantField[F, D]
):
  /** Smallest and largest pull determinant, folds included. */
  val pullRange: (Double, Double) =
    var minimum = Double.PositiveInfinity
    var maximum = Double.NegativeInfinity
    var index = 0
    while index < pull.length do
      minimum = math.min(minimum, pull(index))
      maximum = math.max(maximum, pull(index))
      index += 1
    minimum -> maximum

  def at(index: Vector[Int]): Option[DeterminantValue] =
    DeterminantField.linear(grid.shape, index).map { linear =>
      val determinant = pull(linear)
      if determinant <= 0.0 then DeterminantValue.Folded(determinant)
      else
        direction match
          case DeterminantDirection.Pull =>
            DeterminantValue.Regular(determinant)
          case DeterminantDirection.Push =>
            DeterminantValue.Regular(1.0 / determinant)
    }

final class LogDeterminantField[F <: Frame[D], D <: Dim] private[field] (
    val grid: Grid[F, D],
    val direction: DeterminantDirection,
    private val pull: Array[Double],
    val values: ContinuousImage[? <: SampleSpace[F, D], Double, AnyRank],
    val finiteMask: MaskImage[? <: SampleSpace[F, D], AnyRank],
    val nonPositiveCount: Long
):
  def at(index: Vector[Int]): Option[LogDeterminant] =
    DeterminantField.linear(grid.shape, index).map { linear =>
      val determinant = pull(linear)
      if determinant <= 0.0 then LogDeterminant.NonPositive(determinant)
      else
        direction match
          case DeterminantDirection.Pull =>
            LogDeterminant.Finite(math.log(determinant))
          case DeterminantDirection.Push =>
            LogDeterminant.Finite(-math.log(determinant))
    }

object DeterminantField:
  private[field] def create[F <: Frame[D], D <: Dim](
      grid: Grid[F, D],
      direction: DeterminantDirection,
      pull: Array[Double]
  ): Either[TopologyAssessmentError, DeterminantField[F, D]] =
    val count = pull.length
    val folded = new Array[Boolean](count)
    val directed = new Array[Double](count)
    val logs = new Array[Double](count)
    var folds = 0L
    var index = 0
    while index < count do
      val determinant = pull(index)
      if determinant <= 0.0 then
        folded(index) = true
        folds += 1L
      else
        direction match
          case DeterminantDirection.Pull =>
            directed(index) = determinant
            logs(index) = math.log(determinant)
          case DeterminantDirection.Push =>
            directed(index) = 1.0 / determinant
            logs(index) = -math.log(determinant)
      index += 1
    val finite = folded.map(!_)
    for
      shape <- Shape
        .from(grid.shape)
        .left
        .map(_ => TopologyAssessmentError.LatticeTooLarge(grid.shape))
      valueImage <- continuous(grid, shape, directed)
      logImage <- continuous(grid, shape, logs)
      foldImage <- mask(grid, shape, folded)
      finiteImage <- mask(grid, shape, finite)
    yield new DeterminantField(
      grid,
      direction,
      pull,
      valueImage,
      foldImage,
      folds,
      new LogDeterminantField(
        grid,
        direction,
        pull,
        logImage,
        finiteImage,
        folds
      )
    )

  private[field] def linear(
      shape: Vector[Int],
      index: Vector[Int]
  ): Option[Int] =
    if index.length != shape.length ||
      index.indices.exists(axis => index(axis) < 0 || index(axis) >= shape(axis))
    then None
    else
      var result = 0
      var axis = 0
      while axis < index.length do
        result = result * shape(axis) + index(axis)
        axis += 1
      Some(result)

  private def continuous[F <: Frame[D], D <: Dim](
      grid: Grid[F, D],
      shape: Shape[AnyRank],
      values: Array[Double]
  ) =
    val data =
      NDArray.build[Double, AnyRank](shape): builder =>
        var index = 0
        while index < values.length do
          builder.writeLinear(index, values(index))
          index += 1
    Sampled
      .continuous(grid, NonSpatialAxes.empty, data)
      .left
      .map(error => TopologyAssessmentError.Field(FieldError.Image(error)))

  private def mask[F <: Frame[D], D <: Dim](
      grid: Grid[F, D],
      shape: Shape[AnyRank],
      values: Array[Boolean]
  ) =
    val data =
      NDArray.build[Boolean, AnyRank](shape): builder =>
        var index = 0
        while index < values.length do
          builder.writeLinear(index, values(index))
          index += 1
    Sampled
      .mask(grid, NonSpatialAxes.empty, data)
      .left
      .map(error => TopologyAssessmentError.Field(FieldError.Image(error)))
