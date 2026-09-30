package reframe4s.field

import image4s.Axis
import image4s.AxisKind
import image4s.Continuous
import image4s.ImageError
import image4s.MaskImage
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.SampleSpace
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Shape
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex
import reframe4s.resample.Interpolation

/** Per-lattice-point outcome of materializing a map. */
enum MaterializedOutcome derives CanEqual:
  case Covered
  case ConstantFilled
  case SourcePreserved
  case Rejected

final case class CoverageCounts(
    covered: Long,
    constantFilled: Long,
    sourcePreserved: Long,
    rejected: Long
) derives CanEqual:
  def total: Long = covered + constantFilled + sourcePreserved + rejected

  def coveredFraction: Double =
    if total == 0L then 0.0 else covered.toDouble / total.toDouble

/** Where every stage of a materialized map was evaluated inside its sampled
  * support, and how many lattice points a boundary policy supplied instead.
  */
final class CompositionReport[F <: Frame[D], D <: Dim] private[field] (
    val grid: Grid[F, D],
    val counts: CoverageCounts,
    val coverageMask: MaskImage[? <: SampleSpace[F, D], AnyRank],
    val rejectedFill: CoordinateBoundaryPolicy,
    private val outcomes: Array[Byte]
):
  /** Outcome at a lattice index, or `None` outside the lattice. */
  def outcomeAt(index: Vector[Int]): Option[MaterializedOutcome] =
    if index.length != grid.shape.length ||
      index.indices.exists(axis =>
        index(axis) < 0 || index(axis) >= grid.shape(axis)
      )
    then None
    else
      var linear = 0
      var axis = 0
      while axis < index.length do
        linear = linear * grid.shape(axis) + index(axis)
        axis += 1
      Some(MaterializedOutcome.fromOrdinal(outcomes(linear).toInt))

sealed trait CompositionError derives CanEqual:
  def message: String

object CompositionError:
  final case class Map(error: MapError) extends CompositionError:
    val message: String = error.message

  final case class Field(error: FieldError) extends CompositionError:
    val message: String = error.message

  final case class Image(error: ImageError) extends CompositionError:
    val message: String = error.message

  final case class Geometry(error: GeometryError) extends CompositionError:
    val message: String = error.message

  final case class LatticeTooLarge(shape: Vector[Int])
      extends CompositionError:
    val message: String =
      s"lattice ${shape.mkString("x")} exceeds the addressable array size"

  /** Some lattice points were rejected and the fill policy was `Reject`. */
  final case class RejectedPoints(report: CompositionReport[?, ?])
      extends CompositionError:
    val message: String =
      s"${report.counts.rejected} of ${report.counts.total} lattice points " +
        "left the support of a rejecting stage"

final class ComposedField[From <: Frame[D], To <: Frame[D], D <: Dim] private[
  field
] (
    val map: DenseMap[From, To, D, AnyRank],
    val report: CompositionReport[From, D]
)

/** Materialize pullback compositions as absolute coordinate fields on a
  * chosen lattice (the `convertwarp` operation).
  */
object FieldComposition:
  /** Sample `map` at every point of `lattice`.
    *
    * Each point is classified by the first stage that left its sampled
    * support. Points rejected by a stage are filled by `rejectedFill`; the
    * default `Reject` turns any rejection into `RejectedPoints`, which still
    * carries the complete report.
    */
  def materialize[From <: Frame[D], To <: Frame[D], D <: Dim](
      map: SpatialMap[From, To, D],
      lattice: Grid[From, D],
      rejectedFill: CoordinateBoundaryPolicy = CoordinateBoundaryPolicy.Reject,
      interpolation: Interpolation[Continuous] = Interpolation.Linear
  )(using
      dimension: Dimension[D]
  ): Either[CompositionError, ComposedField[From, To, D]] =
    val reporting = CoverageReportingMap.lift(map)
    val rank = dimension.rank
    for
      _ <- SpatialMap
        .validateSourceFrame(map.source, lattice.frame)
        .left
        .map(CompositionError.Map.apply)
      _ <- DenseMap
        .validateBoundary(rejectedFill, rank)
        .left
        .map(CompositionError.Field.apply)
      count = lattice.shape.product
      outcomes = new Array[Byte](count)
      values = new Array[Double](count * rank)
      _ <- evaluate(reporting, lattice, rejectedFill, outcomes, values)
      report <- buildReport(lattice, outcomes, rejectedFill)
      _ <-
        if report.counts.rejected > 0L &&
          rejectedFill == CoordinateBoundaryPolicy.Reject
        then Left(CompositionError.RejectedPoints(report))
        else Right(())
      coordinates <- coordinateImage(lattice, values)
      dense <- DenseMap
        .fromCoordinates(coordinates, map.target, interpolation)
        .left
        .map(CompositionError.Field.apply)
    yield new ComposedField(dense, report)

  /** Materialize `second ∘ first`, sampled on a lattice of `first`'s source. */
  def compose[
      A <: Frame[D],
      B <: Frame[D],
      C <: Frame[D],
      D <: Dim
  ](
      first: SpatialMap[A, B, D],
      second: SpatialMap[B, C, D],
      lattice: Grid[A, D],
      rejectedFill: CoordinateBoundaryPolicy = CoordinateBoundaryPolicy.Reject,
      interpolation: Interpolation[Continuous] = Interpolation.Linear
  )(using Dimension[D]): Either[CompositionError, ComposedField[A, C, D]] =
    materialize(
      CoverageReportingMap.compose(first, second),
      lattice,
      rejectedFill,
      interpolation
    )

  private def evaluate[From <: Frame[D], To <: Frame[D], D <: Dim](
      map: CoverageReportingMap[From, To, D],
      lattice: Grid[From, D],
      rejectedFill: CoordinateBoundaryPolicy,
      outcomes: Array[Byte],
      values: Array[Double]
  )(using dimension: Dimension[D]): Either[CompositionError, Unit] =
    val rank = dimension.rank
    val index = new Array[Int](rank)
    val count = outcomes.length
    var linear = 0
    var failure = Option.empty[CompositionError]
    while linear < count && failure.isEmpty do
      val evaluated =
        for
          latticeIndex <- LatticeIndex
            .fromVector[D](index.toVector)
            .left
            .map(CompositionError.Geometry.apply)
          point <- lattice
            .pointAt(latticeIndex)
            .left
            .map(CompositionError.Geometry.apply)
        yield point -> map.applyWithCoverage(point)
      evaluated match
        case Left(error) => failure = Some(error)
        case Right((_, Right(covered))) =>
          outcomes(linear) = (covered.outcome match
            case SupportOutcome.Covered => MaterializedOutcome.Covered
            case SupportOutcome.ConstantFilled =>
              MaterializedOutcome.ConstantFilled
            case SupportOutcome.SourcePreserved =>
              MaterializedOutcome.SourcePreserved
          ).ordinal.toByte
          write(values, linear, covered.point.coordinates)
        case Right((point, Left(MapError.OutsideDomain(_)))) =>
          outcomes(linear) = MaterializedOutcome.Rejected.ordinal.toByte
          rejectedFill match
            case CoordinateBoundaryPolicy.Constant(fill) =>
              write(values, linear, fill)
            case _ =>
              write(values, linear, point.coordinates)
        case Right((_, Left(error))) =>
          failure = Some(CompositionError.Map(error))
      linear += 1
      advance(index, lattice.shape)
    failure.toLeft(())

  private def buildReport[F <: Frame[D], D <: Dim](
      lattice: Grid[F, D],
      outcomes: Array[Byte],
      rejectedFill: CoordinateBoundaryPolicy
  ): Either[CompositionError, CompositionReport[F, D]] =
    val tallies = new Array[Long](MaterializedOutcome.values.length)
    var linear = 0
    while linear < outcomes.length do
      tallies(outcomes(linear).toInt) += 1L
      linear += 1
    val counts =
      CoverageCounts(
        covered = tallies(MaterializedOutcome.Covered.ordinal),
        constantFilled = tallies(MaterializedOutcome.ConstantFilled.ordinal),
        sourcePreserved = tallies(MaterializedOutcome.SourcePreserved.ordinal),
        rejected = tallies(MaterializedOutcome.Rejected.ordinal)
      )
    for
      shape <- Shape
        .from(lattice.shape)
        .left
        .map(_ => CompositionError.LatticeTooLarge(lattice.shape))
      mask = NDArray.build[Boolean, AnyRank](shape): builder =>
        var index = 0
        while index < outcomes.length do
          builder.writeLinear(
            index,
            outcomes(index) == MaterializedOutcome.Covered.ordinal.toByte
          )
          index += 1
      image <- Sampled
        .mask(lattice, NonSpatialAxes.empty, mask)
        .left
        .map(CompositionError.Image.apply)
    yield new CompositionReport(lattice, counts, image, rejectedFill, outcomes)

  private def coordinateImage[F <: Frame[D], D <: Dim](
      lattice: Grid[F, D],
      values: Array[Double]
  )(using dimension: Dimension[D]) =
    for
      axis <- Axis
        .create("component", dimension.rank, AxisKind.Direction)
        .left
        .map(CompositionError.Image.apply)
      axes <- NonSpatialAxes
        .from(Vector(axis))
        .left
        .map(CompositionError.Image.apply)
      shape <- Shape
        .from(lattice.shape :+ dimension.rank)
        .left
        .map(_ => CompositionError.LatticeTooLarge(lattice.shape))
      data = NDArray.build[Double, AnyRank](shape): builder =>
        var index = 0
        while index < values.length do
          builder.writeLinear(index, values(index))
          index += 1
      image <- Sampled
        .continuous(lattice, axes, data)
        .left
        .map(CompositionError.Image.apply)
    yield image

  private def write(
      values: Array[Double],
      linear: Int,
      coordinates: Vector[Double]
  ): Unit =
    val rank = coordinates.length
    var component = 0
    while component < rank do
      values(linear * rank + component) = coordinates(component)
      component += 1

  private[field] def advance(index: Array[Int], shape: Vector[Int]): Unit =
    var axis = index.length - 1
    var carry = true
    while carry && axis >= 0 do
      index(axis) += 1
      if index(axis) < shape(axis) then carry = false
      else
        index(axis) = 0
        axis -= 1
