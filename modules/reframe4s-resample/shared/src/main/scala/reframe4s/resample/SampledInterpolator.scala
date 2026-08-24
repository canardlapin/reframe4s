package reframe4s.resample

import image4s.BoundaryPolicy
import image4s.Continuous
import image4s.ImageError
import image4s.PartialWeight
import image4s.Sample
import image4s.Sampled
import image4s.SampleSpace
import image4s.Validity
import ravel.AnyRank
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point

/** Pointwise interpolation shared by dense fields and reference evaluation.
  *
  * Production whole-grid affine resampling remains owned by
  * [[ResamplingPlan]]. This smaller primitive deliberately accepts only
  * continuous roles and returns explicit boundary validity.
  */
object SampledInterpolator:
  def at[
      F <: Frame[D],
      D <: Dim,
      Role <: Continuous,
      S <: SampleSpace[F, D],
      R <: AnyRank
  ](
      image: Sampled[S, Double, Role, R],
      point: Point[F, D],
      nonSpatialIndex: Vector[Int],
      interpolation: Interpolation[Role] = Interpolation.Linear,
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using dimension: Dimension[D]): Either[ImageError, Sample[Double]] =
    for
      _ <- validateNonSpatial(image, nonSpatialIndex)
      continuous <- image.grid
        .continuousIndexOf(point)
        .left
        .map(ImageError.Geometry.apply)
      result <-
        interpolation match
          case Interpolation.Nearest =>
            nearest(image, continuous.values, nonSpatialIndex, boundary)
          case Interpolation.Linear =>
            linear(image, continuous.values, nonSpatialIndex, boundary)
          case Interpolation.Cubic =>
            cubic(image, continuous.values, nonSpatialIndex, boundary)
          case Interpolation.Lanczos5 =>
            lanczos5(image, continuous.values, nonSpatialIndex, boundary)
    yield result

  private def validateNonSpatial[
      F <: Frame[D],
      D <: Dim,
      Role <: Continuous,
      S <: SampleSpace[F, D],
      R <: AnyRank
  ](
      image: Sampled[S, Double, Role, R],
      index: Vector[Int]
  ): Either[ImageError, Unit] =
    if index.length != image.nonSpatialAxes.size then
      Left(
        ImageError.NonSpatialIndexRankMismatch(
          image.nonSpatialAxes.size,
          index.length
        )
      )
    else
      image.nonSpatialAxes.values.zip(index).collectFirst {
        case (axis, coordinate)
            if coordinate < 0 || coordinate >= axis.extent =>
          ImageError.NonSpatialIndexOutOfBounds(
            axis.name,
            coordinate,
            axis.extent
          )
      }.toLeft(())

  private def nearest[
      F <: Frame[D],
      D <: Dim,
      Role <: Continuous,
      S <: SampleSpace[F, D],
      R <: AnyRank
  ](
      image: Sampled[S, Double, Role, R],
      continuous: Vector[Double],
      nonSpatial: Vector[Int],
      boundary: BoundaryPolicy[Double]
  ): Either[ImageError, Sample[Double]] =
    val index = continuous.map(value => math.floor(value + 0.5).toInt)
    if inside(index, image.grid.shape) then
      image.valueAt(index, nonSpatial).map(Sample(_, Validity.Full))
    else
      boundary match
        case BoundaryPolicy.Reject =>
          Left(ImageError.OutsideGrid(continuous))
        case BoundaryPolicy.Constant(value) =>
          Right(Sample(value, Validity.Outside))

  private def linear[
      F <: Frame[D],
      D <: Dim,
      Role <: Continuous,
      S <: SampleSpace[F, D],
      R <: AnyRank
  ](
      image: Sampled[S, Double, Role, R],
      continuous: Vector[Double],
      nonSpatial: Vector[Int],
      boundary: BoundaryPolicy[Double]
  )(using dimension: Dimension[D]): Either[ImageError, Sample[Double]] =
    val lower = continuous.map(math.floor(_).toInt)
    val fraction =
      continuous.indices.map(axis => continuous(axis) - lower(axis)).toVector
    val cornerCount = 1 << dimension.rank
    var corner = 0
    var value = 0.0
    var insideWeight = 0.0
    var failure = Option.empty[ImageError]
    while corner < cornerCount && failure.isEmpty do
      val index = Vector.tabulate(dimension.rank) { axis =>
        lower(axis) + ((corner >> axis) & 1)
      }
      var weight = 1.0
      var axis = 0
      while axis < dimension.rank do
        val upper = ((corner >> axis) & 1) == 1
        weight *= (
          if upper then fraction(axis)
          else 1.0 - fraction(axis)
        )
        axis += 1
      if weight > 0.0 then
        if inside(index, image.grid.shape) then
          image.valueAt(index, nonSpatial) match
            case Right(sample) =>
              value += weight * sample
              insideWeight += weight
            case Left(error) =>
              failure = Some(error)
        else
          boundary match
            case BoundaryPolicy.Reject =>
              failure = Some(ImageError.OutsideGrid(continuous))
            case BoundaryPolicy.Constant(outside) =>
              value += weight * outside
      corner += 1
    failure match
      case Some(error) => Left(error)
      case None =>
        validity(insideWeight).map(Sample(value, _))

  private def cubic[
      F <: Frame[D],
      D <: Dim,
      Role <: Continuous,
      S <: SampleSpace[F, D],
      R <: AnyRank
  ](
      image: Sampled[S, Double, Role, R],
      continuous: Vector[Double],
      nonSpatial: Vector[Int],
      boundary: BoundaryPolicy[Double]
  )(using dimension: Dimension[D]): Either[ImageError, Sample[Double]] =
    val lower = continuous.map(math.floor(_).toInt)
    val fraction =
      continuous.indices.map(axis => continuous(axis) - lower(axis)).toVector
    val tapCount =
      if dimension.rank == 2 then
        CubicKernel.TapCount * CubicKernel.TapCount
      else
        CubicKernel.TapCount * CubicKernel.TapCount * CubicKernel.TapCount
    var flat = 0
    var value = 0.0
    var absoluteInside = 0.0
    var absoluteTotal = 0.0
    var failure = Option.empty[ImageError]
    while flat < tapCount && failure.isEmpty do
      val tap0 = flat / (
        if dimension.rank == 2 then CubicKernel.TapCount
        else CubicKernel.TapCount * CubicKernel.TapCount
      )
      val remainder =
        if dimension.rank == 2 then flat - tap0 * CubicKernel.TapCount
        else flat - tap0 * CubicKernel.TapCount * CubicKernel.TapCount
      val tap1 =
        if dimension.rank == 2 then remainder
        else remainder / CubicKernel.TapCount
      val tap2 =
        if dimension.rank == 2 then 0
        else remainder - tap1 * CubicKernel.TapCount
      val weight =
        CubicKernel.weight(fraction(0), tap0) *
          CubicKernel.weight(fraction(1), tap1) *
          (
            if dimension.rank == 3 then
              CubicKernel.weight(fraction(2), tap2)
            else 1.0
          )
      if weight != 0.0 then
        val spatial =
          if dimension.rank == 2 then
            Vector(lower(0) + tap0 - 1, lower(1) + tap1 - 1)
          else
            Vector(
              lower(0) + tap0 - 1,
              lower(1) + tap1 - 1,
              lower(2) + tap2 - 1
            )
        absoluteTotal += math.abs(weight)
        if inside(spatial, image.grid.shape) then
          image.valueAt(spatial, nonSpatial) match
            case Right(sample) =>
              value += weight * sample
              absoluteInside += math.abs(weight)
            case Left(error) => failure = Some(error)
        else
          boundary match
            case BoundaryPolicy.Reject =>
              failure = Some(ImageError.OutsideGrid(continuous))
            case BoundaryPolicy.Constant(outside) =>
              value += weight * outside
      flat += 1
    failure match
      case Some(error) => Left(error)
      case None =>
        val insideWeight =
          if absoluteTotal == 0.0 then 0.0
          else absoluteInside / absoluteTotal
        validity(insideWeight).map(Sample(value, _))

  private def lanczos5[
      F <: Frame[D],
      D <: Dim,
      Role <: Continuous,
      S <: SampleSpace[F, D],
      R <: AnyRank
  ](
      image: Sampled[S, Double, Role, R],
      continuous: Vector[Double],
      nonSpatial: Vector[Int],
      boundary: BoundaryPolicy[Double]
  )(using dimension: Dimension[D]): Either[ImageError, Sample[Double]] =
    if continuous.forall(LanczosKernel.hasRepresentableStencil) then
      lanczos5Representable(image, continuous, nonSpatial, boundary)
    else
      boundary match
        case BoundaryPolicy.Reject =>
          Left(ImageError.OutsideGrid(continuous))
        case BoundaryPolicy.Constant(value) =>
          Right(Sample(value, Validity.Outside))

  private def lanczos5Representable[
      F <: Frame[D],
      D <: Dim,
      Role <: Continuous,
      S <: SampleSpace[F, D],
      R <: AnyRank
  ](
      image: Sampled[S, Double, Role, R],
      continuous: Vector[Double],
      nonSpatial: Vector[Int],
      boundary: BoundaryPolicy[Double]
  )(using dimension: Dimension[D]): Either[ImageError, Sample[Double]] =
    val weights =
      Array.fill(dimension.rank)(
        new Array[Double](LanczosKernel.TapCount)
      )
    val first =
      Array.tabulate(dimension.rank)(axis =>
        LanczosKernel.fillNormalized(continuous(axis), weights(axis))
      )
    var absoluteTotal = 1.0
    var axis = 0
    while axis < dimension.rank do
      absoluteTotal *= LanczosKernel.absoluteSum(weights(axis))
      axis += 1

    val tapCount =
      if dimension.rank == 2 then
        LanczosKernel.TapCount * LanczosKernel.TapCount
      else
        LanczosKernel.TapCount *
          LanczosKernel.TapCount *
          LanczosKernel.TapCount
    var flat = 0
    var value = 0.0
    var absoluteInside = 0.0
    var failure = Option.empty[ImageError]
    while flat < tapCount && failure.isEmpty do
      val tap0 = flat / (
        if dimension.rank == 2 then LanczosKernel.TapCount
        else LanczosKernel.TapCount * LanczosKernel.TapCount
      )
      val remainder =
        if dimension.rank == 2 then
          flat - tap0 * LanczosKernel.TapCount
        else
          flat - tap0 * LanczosKernel.TapCount * LanczosKernel.TapCount
      val tap1 =
        if dimension.rank == 2 then remainder
        else remainder / LanczosKernel.TapCount
      val tap2 =
        if dimension.rank == 2 then 0
        else remainder - tap1 * LanczosKernel.TapCount
      val weight =
        weights(0)(tap0) *
          weights(1)(tap1) *
          (if dimension.rank == 3 then weights(2)(tap2) else 1.0)
      if math.abs(weight) > LanczosKernel.WeightTolerance then
        val spatial =
          if dimension.rank == 2 then
            Vector(first(0) + tap0, first(1) + tap1)
          else
            Vector(
              first(0) + tap0,
              first(1) + tap1,
              first(2) + tap2
            )
        if inside(spatial, image.grid.shape) then
          image.valueAt(spatial, nonSpatial) match
            case Right(sample) =>
              value += weight * sample
              absoluteInside += math.abs(weight)
            case Left(error) =>
              failure = Some(error)
        else
          boundary match
            case BoundaryPolicy.Reject =>
              failure = Some(ImageError.OutsideGrid(continuous))
            case BoundaryPolicy.Constant(outside) =>
              value += weight * outside
      flat += 1
    failure match
      case Some(error) => Left(error)
      case None =>
        val fraction =
          math.max(0.0, math.min(1.0, absoluteInside / absoluteTotal))
        validity(fraction).map(Sample(value, _))

  private def inside(index: Vector[Int], shape: Vector[Int]): Boolean =
    index.indices.forall(axis =>
      index(axis) >= 0 && index(axis) < shape(axis)
    )

  private def validity(weight: Double): Either[ImageError, Validity] =
    if weight >= 1.0 - 1e-12 then Right(Validity.Full)
    else if weight <= 1e-12 then Right(Validity.Outside)
    else PartialWeight.from(weight).map(Validity.Partial.apply)
