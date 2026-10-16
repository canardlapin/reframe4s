package reframe4s.multiscale

import image4s.ImageError
import image4s.geometry.Affine
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import reframe4s.resample.ResamplingError

sealed trait MultiscaleError derives CanEqual:
  def message: String

object MultiscaleError:
  case object EmptySchedule extends MultiscaleError:
    val message: String = "a scale schedule must contain at least one level"

  final case class RankMismatch(expected: Int, actual: Int)
      extends MultiscaleError:
    val message: String =
      s"scale vector rank $actual does not match spatial rank $expected"

  final case class InvalidShrink(axis: Int, value: Int)
      extends MultiscaleError:
    val message: String =
      s"scale shrink on axis $axis must be positive, got $value"

  final case class InvalidSmoothing(axis: Int, value: Double)
      extends MultiscaleError:
    val message: String =
      s"physical smoothing width on axis $axis must be finite and non-negative, got $value"

  final case class NotCoarseToFine(
      coarseIndex: Int,
      fineIndex: Int,
      axis: Int
  ) extends MultiscaleError:
    val message: String =
      s"level $fineIndex is coarser than level $coarseIndex on axis $axis"

  final case class MissingNativeTerminal(shrink: Vector[Int])
      extends MultiscaleError:
    val message: String =
      s"the final level must use unit shrink, got ${shrink.mkString("x")}"

  object Geometry:
    def apply(error: GeometryError | spatial4s.SpatialError): Geometry =
      new Geometry(image4s.geometry.GeometryError.fromCoordinate(error))

  final case class Geometry(error: GeometryError) extends MultiscaleError:
    val message: String = error.message

  final case class Image(error: ImageError) extends MultiscaleError:
    val message: String = error.message

  final case class Resampling(error: ResamplingError)
      extends MultiscaleError:
    val message: String = error.message

  final case class UnsupportedScalarRank(actual: Int)
      extends MultiscaleError:
    val message: String =
      s"D3 scalar scale spaces require rank-3 data, got rank $actual"

  final case class NonSpatialScalarAxes(count: Int)
      extends MultiscaleError:
    val message: String =
      s"D3 scalar scale spaces require spatial-only data, got $count extra axes"

  final case class InvalidPhysicalAxisScale(axis: Int, value: Double)
      extends MultiscaleError:
    val message: String =
      s"grid physical scale on axis $axis must be finite and positive, got $value"

  final case class InvalidPsfWidth(value: Double, unit: GaussianWidthUnit)
      extends MultiscaleError:
    val message: String =
      s"Gaussian PSF width must be finite and non-negative, got $value $unit"

  final case class PsfLevelCountMismatch(expected: Int, actual: Int)
      extends MultiscaleError:
    val message: String =
      s"support-aware pyramid requires $expected target PSFs, got $actual"

  final case class InvalidSupportThreshold(value: Double)
      extends MultiscaleError:
    val message: String =
      s"support threshold must be finite and in (0, 1], got $value"

  final case class InvalidGaussianTruncation(value: Double)
      extends MultiscaleError:
    val message: String =
      s"Gaussian truncation must be finite and positive, got $value"

  final case class SupportShapeMismatch(
      expected: Vector[Int],
      actual: Vector[Int]
  ) extends MultiscaleError:
    val message: String =
      s"support shape ${actual.mkString("x")} does not match image shape ${expected.mkString("x")}"

  final case class InvalidSupportValue(linearIndex: Int, value: Double)
      extends MultiscaleError:
    val message: String =
      s"support at linear index $linearIndex must be finite and in [0, 1], got $value"

  final case class NonFiniteSupportedValue(linearIndex: Int, value: Double)
      extends MultiscaleError:
    val message: String =
      s"supported image value at linear index $linearIndex must be finite, got $value"

  case object EmptySourceSupport extends MultiscaleError:
    val message: String =
      "support-aware pyramid requires at least one positively supported source sample"

  case object SupportAwarePyramidWorkspaceInUse extends MultiscaleError:
    val message: String =
      "a support-aware pyramid workspace cannot be shared by concurrent builds"

  final case class InvalidPreparedSupport(linearIndex: Int, value: Double)
      extends MultiscaleError:
    val message: String =
      s"prepared support at linear index $linearIndex must be finite and in [0, 1] up to roundoff, got $value"

  final case class NonFinitePreparedNumerator(
      linearIndex: Int,
      value: Double
  ) extends MultiscaleError:
    val message: String =
      s"prepared weighted value at linear index $linearIndex must be finite, got $value"

  case object PyramidBuilderProtocolViolation extends MultiscaleError:
    val message: String =
      "Ravel returned from a nested pyramid build without producing both arrays"

  case object ScalarPyramidWorkspaceInUse extends MultiscaleError:
    val message: String =
      "a scalar pyramid workspace cannot be shared by concurrent builds"

  final case class Transfer(level: Int, error: TransferError)
      extends MultiscaleError:
    val message: String =
      s"transfer into level $level failed: ${error.message}"

  final case class Solve(level: Int, error: LevelFailure)
      extends MultiscaleError:
    val message: String =
      s"level $level failed: ${error.message}"

final class ScaleSpec[D <: Dim] private (
    val shrink: Vector[Int],
    val smoothingSigmaPhysical: Vector[Double]
)

object ScaleSpec:
  def create[D <: Dim](
      shrink: Vector[Int],
      smoothingSigmaPhysical: Vector[Double]
  )(using dimension: Dimension[D]): Either[MultiscaleError, ScaleSpec[D]] =
    if shrink.length != dimension.rank then
      Left(MultiscaleError.RankMismatch(dimension.rank, shrink.length))
    else if smoothingSigmaPhysical.length != dimension.rank then
      Left(
        MultiscaleError.RankMismatch(
          dimension.rank,
          smoothingSigmaPhysical.length
        )
      )
    else
      shrink.zipWithIndex.collectFirst {
        case (value, axis) if value <= 0 =>
          MultiscaleError.InvalidShrink(axis, value)
      }.orElse(
        smoothingSigmaPhysical.zipWithIndex.collectFirst {
          case (value, axis) if !value.isFinite || value < 0.0 =>
            MultiscaleError.InvalidSmoothing(axis, value)
        }
      ) match
        case Some(error) => Left(error)
        case None        => Right(new ScaleSpec(shrink, smoothingSigmaPhysical))

final case class ScaleLevel[D <: Dim, +C](
    scale: ScaleSpec[D],
    configuration: C
)

final class ScaleSchedule[D <: Dim, +C] private (
    val levels: Vector[ScaleLevel[D, C]]
):
  def size: Int = levels.size

object ScaleSchedule:
  def create[D <: Dim, C](
      levels: Vector[ScaleLevel[D, C]]
  )(using dimension: Dimension[D])
      : Either[MultiscaleError, ScaleSchedule[D, C]] =
    if levels.isEmpty then Left(MultiscaleError.EmptySchedule)
    else if
      levels.exists(_.scale.shrink.length != dimension.rank)
    then
      Left(
        MultiscaleError.RankMismatch(
          dimension.rank,
          levels
            .find(_.scale.shrink.length != dimension.rank)
            .map(_.scale.shrink.length)
            .getOrElse(0)
        )
      )
    else
      val orderingError =
        levels.indices.drop(1).iterator.flatMap { fineIndex =>
          val coarseIndex = fineIndex - 1
          levels(coarseIndex).scale.shrink.indices.iterator.collectFirst {
            case axis
                if levels(fineIndex).scale.shrink(axis) >
                  levels(coarseIndex).scale.shrink(axis) =>
              MultiscaleError.NotCoarseToFine(
                coarseIndex,
                fineIndex,
                axis
              )
          }
        }.nextOption()
      orderingError match
        case Some(error) => Left(error)
        case None =>
          levels.lastOption match
            case None => Left(MultiscaleError.EmptySchedule)
            case Some(last) =>
              val terminal = last.scale.shrink
              if terminal.exists(_ != 1) then
                Left(MultiscaleError.MissingNativeTerminal(terminal))
              else Right(new ScaleSchedule(levels))

final case class GridLevel[
    F <: Frame[D],
    D <: Dim,
    +C
](
    ordinal: Int,
    scale: ScaleSpec[D],
    configuration: C,
    grid: Grid[F, D]
)

final class GridTower[
    F <: Frame[D],
    D <: Dim,
    +C
] private (
    val native: Grid[F, D],
    val levels: Vector[GridLevel[F, D, C]]
)

object GridTower:
  def build[F <: Frame[D], D <: Dim, C](
      native: Grid[F, D],
      schedule: ScaleSchedule[D, C]
  )(using dimension: Dimension[D])
      : Either[MultiscaleError, GridTower[F, D, C]] =
    val result = Vector.newBuilder[GridLevel[F, D, C]]
    var index = 0
    var failure = Option.empty[MultiscaleError]
    while index < schedule.levels.length && failure.isEmpty do
      val level = schedule.levels(index)
      val gridResult =
        if index == schedule.levels.length - 1 then Right(native)
        else coarseGrid(native, level.scale)
      gridResult match
        case Left(error) => failure = Some(error)
        case Right(grid) =>
          result += GridLevel(index, level.scale, level.configuration, grid)
      index += 1
    failure.toLeft(new GridTower(native, result.result()))

  private def coarseGrid[F <: Frame[D], D <: Dim](
      native: Grid[F, D],
      spec: ScaleSpec[D]
  )(using dimension: Dimension[D])
      : Either[MultiscaleError, Grid[F, D]] =
    val shape = native.shape.indices.map { axis =>
      val extent = native.shape(axis)
      if extent == 1 then 1
      else
        math.ceil((extent - 1).toDouble / spec.shrink(axis).toDouble).toInt + 1
    }.toVector
    val size = dimension.rank + 1
    val rowMajor = native.indexToFrame.rowMajor.toArray
    var column = 0
    while column < dimension.rank do
      val nativeIntervals = native.shape(column) - 1
      val coarseIntervals = shape(column) - 1
      val factor =
        if nativeIntervals == 0 || coarseIntervals == 0 then 1.0
        else nativeIntervals.toDouble / coarseIntervals.toDouble
      var row = 0
      while row < dimension.rank do
        rowMajor(row * size + column) *= factor
        row += 1
      column += 1
    for
      affine <- Affine
        .fromRowMajor[D](rowMajor.toVector, native.indexToFrame.tolerance)
        .left
        .map(MultiscaleError.Geometry.apply)
      grid <- Grid
        .forFrame(native.frame)(shape, affine)
        .left
        .map(MultiscaleError.Geometry.apply)
    yield grid

sealed trait TransferError derives CanEqual:
  def message: String

object TransferError:
  final case class Unsupported(operation: TransferOperation)
      extends TransferError:
    val message: String = s"$operation is not supported for this state"

  object Geometry:
    def apply(error: GeometryError | spatial4s.SpatialError): Geometry =
      new Geometry(image4s.geometry.GeometryError.fromCoordinate(error))

  final case class Geometry(error: GeometryError) extends TransferError:
    val message: String = error.message

enum TransferOperation derives CanEqual:
  case Restrict, Prolong

trait Transfer[
    P,
    F <: Frame[D],
    D <: Dim
]:
  def restrict(
      value: P,
      from: Grid[F, D],
      to: Grid[F, D]
  ): Either[TransferError, P]

  def prolong(
      value: P,
      from: Grid[F, D],
      to: Grid[F, D]
  ): Either[TransferError, P]

trait LevelSolver[
    P,
    F <: Frame[D],
    D <: Dim,
    -C
]:
  def solve(
      level: GridLevel[F, D, C],
      initial: P
  ): Either[LevelFailure, P]

sealed trait LevelFailure derives CanEqual:
  def message: String

object LevelFailure:
  final case class Reported(message: String) extends LevelFailure

object Continuation:
  def run[P, F <: Frame[D], D <: Dim, C](
      tower: GridTower[F, D, C],
      initialAtCoarsest: P,
      transfer: Transfer[P, F, D],
      solver: LevelSolver[P, F, D, C]
  ): Either[MultiscaleError, P] =
    var current = initialAtCoarsest
    var levelIndex = 0
    var failure = Option.empty[MultiscaleError]
    while levelIndex < tower.levels.length && failure.isEmpty do
      val level = tower.levels(levelIndex)
      solver.solve(level, current) match
        case Left(error) =>
          failure = Some(MultiscaleError.Solve(levelIndex, error))
        case Right(solved) =>
          current = solved
          if levelIndex + 1 < tower.levels.length then
            transfer.prolong(
              current,
              level.grid,
              tower.levels(levelIndex + 1).grid
            ) match
              case Left(error) =>
                failure = Some(
                  MultiscaleError.Transfer(levelIndex + 1, error)
                )
              case Right(prolonged) =>
                current = prolonged
      levelIndex += 1
    failure.toLeft(current)
