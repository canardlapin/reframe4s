package reframe4s.field

import image4s.Axis
import image4s.AxisKind
import image4s.ContinuousImage
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.SampleSpace
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Shape
import image4s.geometry.ContinuousIndex
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Grid

sealed trait BSplineError derives CanEqual:
  def message: String

object BSplineError:
  final case class RankMismatch(expected: Int, actual: Int)
      extends BSplineError:
    val message: String =
      s"B-spline field expects $expected spatial axes, got $actual"

  final case class InvalidKnotSpacing(axis: Int, value: Int)
      extends BSplineError:
    val message: String =
      s"knot spacing on axis $axis must be positive, got $value"

  final case class InvalidKnotOffset(axis: Int, value: Int)
      extends BSplineError:
    val message: String =
      s"knot offset on axis $axis must be non-negative, got $value"

  final case class InvalidCoefficientShape(
      components: Int,
      actual: Vector[Int]
  ) extends BSplineError:
    val message: String =
      "B-spline coefficients require positive spatial extents followed by " +
        s"one component axis of extent $components, got " +
        actual.mkString("[", ", ", "]")

  final case class NonFiniteCoefficient(flatIndex: Long, value: Double)
      extends BSplineError:
    val message: String =
      s"B-spline coefficient $flatIndex must be finite, got $value"

  final case class InvalidLatticeShape(shape: Vector[Int])
      extends BSplineError:
    val message: String =
      s"evaluation lattice extents must be positive, got ${shape.mkString("x")}"

  final case class Field(error: FieldError) extends BSplineError:
    val message: String = error.message

/** Uniform B-spline basis degree. Kernels are the standard centred
  * cardinal B-splines, whose integer translates form a partition of unity.
  */
enum BSplineOrder derives CanEqual:
  case Quadratic
  case Cubic

  /** Half-width of the kernel's open support. */
  def halfSupport: Double =
    this match
      case Quadratic => 1.5
      case Cubic     => 2.0

  def weight(t: Double): Double =
    val a = math.abs(t)
    this match
      case Quadratic =>
        if a < 0.5 then 0.75 - a * a
        else if a < 1.5 then
          val d = a - 1.5
          0.5 * d * d
        else 0.0
      case Cubic =>
        if a < 1.0 then 2.0 / 3.0 - a * a + 0.5 * a * a * a
        else if a < 2.0 then
          val d = 2.0 - a
          d * d * d / 6.0
        else 0.0

/** Integer knot spacing and knot offset per spatial axis.
  *
  * A lattice coordinate `u` meets coefficient `a` with weight
  * `B(u / spacing - a + offset)`.
  */
final class KnotLattice[D <: Dim] private (
    val spacing: Vector[Int],
    val offset: Vector[Int]
)

object KnotLattice:
  def create[D <: Dim](
      spacing: Vector[Int],
      offset: Vector[Int]
  )(using dimension: Dimension[D]): Either[BSplineError, KnotLattice[D]] =
    if spacing.length != dimension.rank then
      Left(BSplineError.RankMismatch(dimension.rank, spacing.length))
    else if offset.length != dimension.rank then
      Left(BSplineError.RankMismatch(dimension.rank, offset.length))
    else
      spacing.indices
        .collectFirst {
          case axis if spacing(axis) <= 0 =>
            BSplineError.InvalidKnotSpacing(axis, spacing(axis))
          case axis if offset(axis) < 0 =>
            BSplineError.InvalidKnotOffset(axis, offset(axis))
        }
        .toLeft(new KnotLattice[D](spacing, offset))

  /** FNIRT's convention: the first knot sits one spacing before lattice
    * coordinate zero whenever the spacing exceeds one voxel.
    */
  def fsl[D <: Dim](
      spacing: Vector[Int]
  )(using Dimension[D]): Either[BSplineError, KnotLattice[D]] =
    create[D](spacing, spacing.map(value => if value > 1 then 1 else 0))

/** A vector-valued tensor-product B-spline field over integer-spaced knots.
  *
  * Coefficients are unnormalised: the field is the plain weighted sum
  * `f(u) = sum_a C[a] prod_i B(u_i / k_i - a_i + o_i)`, with no prefiltering.
  * Coefficients outside the stored grid are zero, so evaluation is total.
  */
final class BSplineField[D <: Dim] private (
    val order: BSplineOrder,
    val knots: KnotLattice[D],
    val coefficientShape: Vector[Int],
    private val coefficients: Array[Double]
)(using dimension: Dimension[D]):
  private val rank = dimension.rank
  private val strides: Array[Int] =
    val result = new Array[Int](rank)
    var stride = rank
    var axis = rank - 1
    while axis >= 0 do
      result(axis) = stride
      stride *= coefficientShape(axis)
      axis -= 1
    result
  private val taps = order.halfSupport.toInt * 2 + 1

  /** Evaluate at one continuous lattice coordinate. */
  def at(index: ContinuousIndex[D]): Vector[Double] =
    val first = new Array[Int](rank)
    val weights = new Array[Double](rank * taps)
    var axis = 0
    while axis < rank do
      first(axis) = fillAxisWeights(axis, index.values(axis), weights, axis * taps)
      axis += 1
    val out = new Array[Double](rank)
    accumulate(first, weights, new Array[Int](rank), out)
    out.toVector

  /** Evaluate on every lattice point `0 <= u_i < shape_i`.
    *
    * The result has shape `shape :+ rank` in canonical order, the component
    * axis last. Per-axis kernel weights are computed once per lattice line.
    */
  def evaluateLattice(
      shape: Vector[Int]
  ): Either[BSplineError, NDArray[Double, AnyRank]] =
    if shape.length != rank then
      Left(BSplineError.RankMismatch(rank, shape.length))
    else if shape.exists(_ <= 0) then
      Left(BSplineError.InvalidLatticeShape(shape))
    else
      val firstByAxis = Array.tabulate(rank)(axis => new Array[Int](shape(axis)))
      val weightsByAxis =
        Array.tabulate(rank)(axis => new Array[Double](shape(axis) * taps))
      var axis = 0
      while axis < rank do
        var u = 0
        while u < shape(axis) do
          firstByAxis(axis)(u) =
            fillAxisWeights(axis, u.toDouble, weightsByAxis(axis), u * taps)
          u += 1
        axis += 1
      Shape
        .from(shape :+ rank)
        .left
        .map(_ => BSplineError.InvalidLatticeShape(shape))
        .map: outputShape =>
          NDArray.build[Double, AnyRank](outputShape): builder =>
            val lattice = new Array[Int](rank)
            val first = new Array[Int](rank)
            val weights = new Array[Double](rank * taps)
            val out = new Array[Double](rank)
            val tapIndex = new Array[Int](rank)
            val count = shape.product
            var linear = 0
            while linear < count do
              var copyAxis = 0
              while copyAxis < rank do
                val u = lattice(copyAxis)
                first(copyAxis) = firstByAxis(copyAxis)(u)
                System.arraycopy(
                  weightsByAxis(copyAxis),
                  u * taps,
                  weights,
                  copyAxis * taps,
                  taps
                )
                copyAxis += 1
              accumulate(first, weights, tapIndex, out)
              var component = 0
              while component < rank do
                builder.writeLinear(linear * rank + component, out(component))
                component += 1
              linear += 1
              advance(lattice, shape)

  /** Evaluate on the lattice of `grid` as a component-last continuous image.
    * Lattice coordinates are the grid's own voxel indices.
    */
  def sampleOn[F <: Frame[D]](
      grid: Grid[F, D]
  ): Either[BSplineError, ContinuousImage[
    ? <: SampleSpace[F, D],
    Double,
    AnyRank
  ]] =
    for
      values <- evaluateLattice(grid.shape)
      axis <- Axis
        .create("component", rank, AxisKind.Direction)
        .left
        .map(error => BSplineError.Field(FieldError.Image(error)))
      axes <- NonSpatialAxes
        .from(Vector(axis))
        .left
        .map(error => BSplineError.Field(FieldError.Image(error)))
      image <- Sampled
        .continuous(grid, axes, values)
        .left
        .map(error => BSplineError.Field(FieldError.Image(error)))
    yield image

  /** Evaluate on `grid` and validate the result as a displacement field. */
  def displacementOn[F <: Frame[D]](
      grid: Grid[F, D]
  ): Either[BSplineError, Displacement[F, D, AnyRank]] =
    sampleOn(grid).flatMap(image =>
      Displacement.from(image).left.map(BSplineError.Field.apply)
    )

  /** Returns the index of the first coefficient covered by `weights`. */
  private def fillAxisWeights(
      axis: Int,
      u: Double,
      weights: Array[Double],
      at: Int
  ): Int =
    val s = u / knots.spacing(axis).toDouble + knots.offset(axis).toDouble
    val first = math.floor(s - order.halfSupport).toInt + 1
    var tap = 0
    while tap < taps do
      weights(at + tap) = order.weight(s - (first + tap).toDouble)
      tap += 1
    first

  private def accumulate(
      first: Array[Int],
      weights: Array[Double],
      tapIndex: Array[Int],
      out: Array[Double]
  ): Unit =
    java.util.Arrays.fill(out, 0.0)
    java.util.Arrays.fill(tapIndex, 0)
    val combinations = math.pow(taps.toDouble, rank.toDouble).toInt
    var combination = 0
    while combination < combinations do
      var weight = 1.0
      var offset = 0
      var inside = true
      var axis = 0
      while axis < rank && inside do
        val coefficient = first(axis) + tapIndex(axis)
        if coefficient < 0 || coefficient >= coefficientShape(axis) then
          inside = false
        else
          weight *= weights(axis * taps + tapIndex(axis))
          offset += coefficient * strides(axis)
        axis += 1
      if inside && weight != 0.0 then
        var component = 0
        while component < rank do
          out(component) += weight * coefficients(offset + component)
          component += 1
      advance(tapIndex, taps)
      combination += 1

  private def advance(index: Array[Int], extent: Int): Unit =
    var axis = index.length - 1
    var carry = true
    while carry && axis >= 0 do
      index(axis) += 1
      if index(axis) < extent then carry = false
      else
        index(axis) = 0
        axis -= 1

  private def advance(index: Array[Int], shape: Vector[Int]): Unit =
    var axis = index.length - 1
    var carry = true
    while carry && axis >= 0 do
      index(axis) += 1
      if index(axis) < shape(axis) then carry = false
      else
        index(axis) = 0
        axis -= 1

object BSplineField:
  /** Validate a component-last coefficient array of shape
    * `coefficientExtents :+ rank`.
    */
  def create[D <: Dim, R <: AnyRank](
      order: BSplineOrder,
      knots: KnotLattice[D],
      coefficients: NDArray[Double, R]
  )(using dimension: Dimension[D]): Either[BSplineError, BSplineField[D]] =
    val shape = coefficients.shape.toIArray.toVector
    val rank = dimension.rank
    if shape.length != rank + 1 ||
      shape.last != rank ||
      shape.init.exists(_ <= 0)
    then Left(BSplineError.InvalidCoefficientShape(rank, shape))
    else
      val values = new Array[Double](coefficients.size)
      var flat = 0
      var invalid = Option.empty[BSplineError]
      coefficients.foreachElement: value =>
        if invalid.isEmpty && !value.isFinite then
          invalid = Some(BSplineError.NonFiniteCoefficient(flat.toLong, value))
        values(flat) = value
        flat += 1
      invalid.toLeft(new BSplineField[D](order, knots, shape.init, values))
