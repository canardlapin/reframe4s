package reframe4s.resample

import image4s.BoundaryPolicy
import image4s.Continuous
import image4s.ContinuousImage
import image4s.SampleSpace
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import reframe4s.core.AffineMap
import reframe4s.core.SpatialMap
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex

/** Intensity modulation by the volume change of a pullback `phi`.
  *
  * With `J(y) = |det D phi(y)|`, `Jacobian` multiplies by `J` and preserves
  * the integral of a density; `SqrtJacobian` multiplies by `sqrt(J)` and
  * preserves the squared L2 norm of an amplitude. The laws differ, so the
  * modes are not interchangeable.
  */
enum VolumeModulation derives CanEqual:
  case Unmodulated
  case Jacobian
  case SqrtJacobian

/** The determinant range a modulation used. Orientation-reversing points
  * (`det < 0`) are modulated by `|det|`; singular points (`det == 0`) by zero.
  */
final case class ModulationDiagnostics(
    minimumDeterminant: Double,
    maximumDeterminant: Double,
    orientationReversingPoints: Long,
    singularPoints: Long
) derives CanEqual

/** A continuous pull-resampling plan whose output is scaled per target
  * point by a modulation factor fixed at compilation.
  *
  * Determinants of an affine pull are exact. For any other pull they are
  * central differences (one-sided on the target boundary) of the pulled-back
  * physical coordinates, divided by the target grid's affine determinant.
  */
final class ModulatedResamplingPlan[
    SourceFrame <: Frame[D],
    TargetFrame <: Frame[D],
    D <: Dim,
    R <: AnyRank
] private (
    val plan: ResamplingPlan[SourceFrame, TargetFrame, D, Continuous, R],
    val modulation: VolumeModulation,
    val diagnostics: ModulationDiagnostics,
    private val factors: Array[Double]
):
  def newWorkspace(): ResamplingWorkspace[D] =
    plan.newWorkspace()

  /** The factor applied at one target lattice index. */
  def factorAt(index: Vector[Int]): Option[Double] =
    val shape = plan.target.shape
    if index.length != shape.length ||
      index.indices.exists(axis => index(axis) < 0 || index(axis) >= shape(axis))
    then None
    else Some(factors(index.indices.foldLeft(0)((linear, axis) => linear * shape(axis) + index(axis))))

  def run(
      workspace: ResamplingWorkspace[D]
  ): Either[ResamplingError, ResamplingResult[TargetFrame, D, Continuous]] =
    plan.run(workspace).flatMap(modulate)

  private def modulate(
      result: ResamplingResult[TargetFrame, D, Continuous]
  ): Either[ResamplingError, ResamplingResult[TargetFrame, D, Continuous]] =
    if modulation == VolumeModulation.Unmodulated then Right(result)
    else
      val image = result.image
      val trailing = image.data.size / factors.length
      val scaled =
        NDArray.build[Double, result.R](image.data.shape): builder =>
          var linear = 0
          image.data.foreachElement: value =>
            builder.writeLinear(linear, value * factors(linear / trailing))
            linear += 1
      image
        .replaceDataChecked[Double, Continuous, result.R](scaled)
        .left
        .map(ResamplingError.Image.apply)
        .map(modulated =>
          new ResamplingResultImpl[TargetFrame, D, Continuous, result.S, result.R](
            modulated,
            result.validity
          )
        )

object ModulatedResamplingPlan:
  def compile[
      SourceFrame <: Frame[D],
      TargetFrame <: Frame[D],
      D <: Dim,
      S <: SampleSpace[SourceFrame, D],
      R <: AnyRank
  ](
      source: ContinuousImage[S, Double, R],
      target: Grid[TargetFrame, D],
      pull: SpatialMap[TargetFrame, SourceFrame, D],
      interpolation: Interpolation[Continuous],
      modulation: VolumeModulation,
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using
      dimension: Dimension[D]
  ): Either[
    ResamplingError,
    ModulatedResamplingPlan[SourceFrame, TargetFrame, D, R]
  ] =
    for
      plan <- ResamplingPlan.mapped(
        source,
        target,
        pull,
        interpolation,
        boundary
      )
      determinants <- pull match
        case affinePull: AffineMap[TargetFrame, SourceFrame, D] @unchecked =>
          val rank = dimension.rank
          val linear = Array.tabulate(rank * rank)(entry =>
            affinePull.operator.matrix(entry / rank, entry % rank)
          )
          Right(Array.fill(target.shape.product)(determinant(linear, rank)))
        case _ =>
          finiteDifferenceDeterminants(target, pull)
    yield
      val factors = determinants.map(value =>
        modulation match
          case VolumeModulation.Unmodulated  => 1.0
          case VolumeModulation.Jacobian     => math.abs(value)
          case VolumeModulation.SqrtJacobian => math.sqrt(math.abs(value))
      )
      new ModulatedResamplingPlan(
        plan,
        modulation,
        ModulationDiagnostics(
          determinants.min,
          determinants.max,
          determinants.count(_ < 0.0).toLong,
          determinants.count(_ == 0.0).toLong
        ),
        factors
      )

  private def finiteDifferenceDeterminants[
      SourceFrame <: Frame[D],
      TargetFrame <: Frame[D],
      D <: Dim
  ](
      target: Grid[TargetFrame, D],
      pull: SpatialMap[TargetFrame, SourceFrame, D]
  )(using dimension: Dimension[D]): Either[ResamplingError, Array[Double]] =
    val rank = dimension.rank
    val shape = target.shape
    shape.indices.find(axis => shape(axis) < 2) match
      case Some(axis) =>
        Left(ResamplingError.DegenerateModulationAxis(axis, shape(axis)))
      case None =>
        val count = shape.product
        val pulled = new Array[Double](count * rank)
        val index = new Array[Int](rank)
        var linear = 0
        var failure = Option.empty[ResamplingError]
        while linear < count && failure.isEmpty do
          val resolved =
            for
              lattice <- LatticeIndex
                .fromVector[D](index.toVector)
                .left
                .map(ResamplingError.Geometry.apply)
              point <- target
                .pointAt(lattice)
                .left
                .map(ResamplingError.Geometry.apply)
              mapped <- pull(point).left.map(ResamplingError.Map.apply)
            yield mapped.coordinates
          resolved match
            case Left(error) => failure = Some(error)
            case Right(coordinates) =>
              var component = 0
              while component < rank do
                pulled(linear * rank + component) = coordinates(component)
                component += 1
          linear += 1
          advance(index, shape)
        failure.toLeft(differentiate(pulled, target, rank))

  private def differentiate[D <: Dim](
      pulled: Array[Double],
      target: Grid[?, D],
      rank: Int
  ): Array[Double] =
    val shape = target.shape
    val basis = Array.tabulate(rank * rank)(entry =>
      target.indexToFrame.matrix(entry / rank, entry % rank)
    )
    val basisDeterminant = determinant(basis, rank)
    val strides = new Array[Int](rank)
    var stride = 1
    var axis = rank - 1
    while axis >= 0 do
      strides(axis) = stride
      stride *= shape(axis)
      axis -= 1
    val count = shape.product
    val result = new Array[Double](count)
    val derivative = new Array[Double](rank * rank)
    val index = new Array[Int](rank)
    var linear = 0
    while linear < count do
      var column = 0
      while column < rank do
        val position = index(column)
        val last = shape(column) - 1
        val lower = if position == 0 then linear else linear - strides(column)
        val upper = if position == last then linear else linear + strides(column)
        val step = if position == 0 || position == last then 1.0 else 2.0
        var row = 0
        while row < rank do
          derivative(row * rank + column) =
            (pulled(upper * rank + row) - pulled(lower * rank + row)) / step
          row += 1
        column += 1
      result(linear) = determinant(derivative, rank) / basisDeterminant
      linear += 1
      advance(index, shape)
    result

  private def determinant(values: Array[Double], rank: Int): Double =
    if rank == 2 then values(0) * values(3) - values(1) * values(2)
    else
      values(0) * (values(4) * values(8) - values(5) * values(7)) -
        values(1) * (values(3) * values(8) - values(5) * values(6)) +
        values(2) * (values(3) * values(7) - values(4) * values(6))

  private def advance(index: Array[Int], shape: Vector[Int]): Unit =
    var axis = index.length - 1
    var carry = true
    while carry && axis >= 0 do
      index(axis) += 1
      if index(axis) < shape(axis) then carry = false
      else
        index(axis) = 0
        axis -= 1
