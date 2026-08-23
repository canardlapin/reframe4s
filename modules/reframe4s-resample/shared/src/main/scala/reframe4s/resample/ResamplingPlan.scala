package reframe4s.resample

import image4s.BoundaryPolicy
import image4s.Categorical
import image4s.Continuous
import image4s.ContinuousImage
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.PartialWeight
import image4s.Sampled
import image4s.SampleSpace
import image4s.ValueSemantics
import image4s.Validity
import ravel.AnyRank
import ravel.ArrayBuilder
import ravel.CanonicalArray
import ravel.DType.given
import ravel.IntegralDType
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import reframe4s.core.AffineMap
import image4s.geometry.Affine
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid

/**
 * An interpolation policy whose semantic parameter prevents linear kernels
 * from being applied to categorical fields.
 */
sealed trait Interpolation[-Sem] derives CanEqual

object Interpolation:
  case object Nearest extends Interpolation[Any]
  case object Linear extends Interpolation[Continuous]
  case object Lanczos5 extends Interpolation[Continuous]

sealed trait ResamplingError derives CanEqual:
  def message: String

object ResamplingError:
  final case class Geometry(error: GeometryError) extends ResamplingError:
    val message: String = error.message

  final case class Image(error: ImageError) extends ResamplingError:
    val message: String = error.message

  final case class UnsupportedDataRank(
      spatialRank: Int,
      dataRank: Int,
      maximumDataRank: Int
  ) extends ResamplingError:
    val message: String =
      s"D$spatialRank resampling supports total data ranks from " +
        s"$spatialRank through $maximumDataRank, got $dataRank"

  final case class OutsideSource(
      targetIndex: Vector[Int],
      sourceContinuousIndex: Vector[Double]
  ) extends ResamplingError:
    val message: String =
      s"target index ${targetIndex.mkString("(", ", ", ")")} maps outside " +
        s"the source grid at ${sourceContinuousIndex.mkString("(", ", ", ")")}"

  final case class WorkspaceDimensionMismatch(
      expected: Int,
      actual: Int
  ) extends ResamplingError:
    val message: String =
      s"resampling plan requires a D$expected workspace, got D$actual"

  case object WorkspaceInUse extends ResamplingError:
    val message: String =
      "a resampling workspace cannot be shared by concurrent executions"

  case object BuilderProtocolViolation extends ResamplingError:
    val message: String =
      "Ravel returned from a nested build without producing the value array"

final class ValidityMask[
    S <: SampleSpace[?, ?],
    R <: AnyRank
] private[resample] (
    val weights: ContinuousImage[S, Double, R]
):
  def at(
      spatialIndex: Vector[Int],
      nonSpatialIndex: Vector[Int] = Vector.empty
  ): Either[ImageError, Validity] =
    weights
      .valueAt(spatialIndex, nonSpatialIndex)
      .flatMap(ValidityMask.decode)

object ValidityMask:
  private def decode(weight: Double): Either[ImageError, Validity] =
    if weight >= 1.0 - ResamplingPlan.ValidityTolerance then
      Right(Validity.Full)
    else if weight <= ResamplingPlan.ValidityTolerance then
      Right(Validity.Outside)
    else
      PartialWeight
        .from(weight)
        .map(Validity.Partial.apply)

sealed trait ResamplingResult[
    F <: Frame[D],
    D <: Dim,
    Sem
]:
  type S <: SampleSpace[F, D]
  type R <: AnyRank
  val image: Sampled[S, Double, Sem, R]
  val validity: ValidityMask[S, R]

sealed trait CategoricalResamplingResult[
    F <: Frame[D],
    D <: Dim,
    A
]:
  type S <: SampleSpace[F, D]
  type R <: AnyRank
  val image: Sampled[S, A, Categorical, R]
  val validity: ValidityMask[S, R]

private final class ResamplingResultImpl[
    F <: Frame[D],
    D <: Dim,
    Sem,
    Space <: SampleSpace[F, D],
    OutRank <: AnyRank
](
    val image: Sampled[Space, Double, Sem, OutRank],
    val validity: ValidityMask[Space, OutRank]
) extends ResamplingResult[F, D, Sem]:
  type S = Space
  type R = OutRank

private final class CategoricalResamplingResultImpl[
    F <: Frame[D],
    D <: Dim,
    A,
    Space <: SampleSpace[F, D],
    OutRank <: AnyRank
](
    val image: Sampled[Space, A, Categorical, OutRank],
    val validity: ValidityMask[Space, OutRank]
) extends CategoricalResamplingResult[F, D, A]:
  type S = Space
  type R = OutRank

final case class PlanStructure(
    spatialRank: Int,
    dataRank: Int,
    materializedCoordinateCount: Int
) derives CanEqual

/**
 * Primitive consumer for allocation-free traversal of a compiled plan.
 *
 * `outputLinearIndex` follows the canonical logical order of
 * `target.shape ++ source.nonSpatialAxes.shape`. Implementations may
 * accumulate mutable run-local state but must not retain the plan workspace.
 */
trait ResamplingSink:
  def accept(
      outputLinearIndex: Int,
      value: Double,
      validityWeight: Double
  ): Unit

/**
 * Mutable scratch state for one execution.
 *
 * A workspace is deliberately not thread-safe and must not be shared by
 * concurrent runs. Plans contain no mutable execution state and may be reused
 * concurrently with distinct workspaces.
 */
final class ResamplingWorkspace[D <: Dim] private (
    private[resample] val spatialRank: Int
):
  private var active = false

  private[resample] var source0 = 0.0
  private[resample] var source1 = 0.0
  private[resample] var source2 = 0.0
  private[resample] var sampledValue = 0.0
  private[resample] var insideWeight = 0.0
  private[resample] val lanczosWeights0 =
    new Array[Double](LanczosKernel.TapCount)
  private[resample] val lanczosWeights1 =
    new Array[Double](LanczosKernel.TapCount)
  private[resample] val lanczosWeights2 =
    new Array[Double](LanczosKernel.TapCount)
  private var failed = false
  private var failedTarget0 = 0
  private var failedTarget1 = 0
  private var failedTarget2 = 0
  private var failedSource0 = 0.0
  private var failedSource1 = 0.0
  private var failedSource2 = 0.0

  private[resample] def acquire(): Boolean =
    if active then false
    else
      active = true
      failed = false
      true

  private[resample] def release(): Unit =
    active = false

  private[resample] def hasFailed: Boolean =
    failed

  private[resample] def recordOutside(
      target0: Int,
      target1: Int,
      target2: Int
  ): Unit =
    if !failed then
      failed = true
      failedTarget0 = target0
      failedTarget1 = target1
      failedTarget2 = target2
      failedSource0 = source0
      failedSource1 = source1
      failedSource2 = source2

  private[resample] def outsideError: ResamplingError.OutsideSource =
    val target =
      if spatialRank == 2 then Vector(failedTarget0, failedTarget1)
      else Vector(failedTarget0, failedTarget1, failedTarget2)
    val source =
      if spatialRank == 2 then Vector(failedSource0, failedSource1)
      else Vector(failedSource0, failedSource1, failedSource2)
    ResamplingError.OutsideSource(target, source)

object ResamplingWorkspace:
  def create[D <: Dim](using dimension: Dimension[D]): ResamplingWorkspace[D] =
    new ResamplingWorkspace[D](dimension.rank)

/**
 * Immutable compiled affine pull-resampling plan.
 *
 * The fused coordinate operator is
 * `source.frameToIndex ∘ pull(Target, Source) ∘ target.indexToFrame`.
 * It is reduced to fixed primitive coefficients at compilation. No target or
 * source coordinate collection is materialized.
 */
final class ResamplingPlan[
    SourceFrame <: Frame[D],
    TargetFrame <: Frame[D],
    D <: Dim,
    Sem,
    R <: AnyRank
] private (
    val source: Sampled[
      ? <: SampleSpace[SourceFrame, D],
      Double,
      Sem,
      R
    ],
    val target: Grid[TargetFrame, D],
    val interpolation: Interpolation[Sem],
    val boundary: BoundaryPolicy[Double],
    private val kernel: AffineIndexKernel,
    private val reader: DataReader
)(using
    private val dimension: Dimension[D],
    private val semantics: ValueSemantics[Double, Sem]
):
  val structure: PlanStructure =
    PlanStructure(
      spatialRank = dimension.rank,
      dataRank = source.data.rank,
      materializedCoordinateCount = 0
    )

  def newWorkspace(): ResamplingWorkspace[D] =
    ResamplingWorkspace.create[D]

  def run(
      workspace: ResamplingWorkspace[D]
  ): Either[ResamplingError, ResamplingResult[TargetFrame, D, Sem]] =
    if workspace.spatialRank != dimension.rank then
      Left(
        ResamplingError.WorkspaceDimensionMismatch(
          dimension.rank,
          workspace.spatialRank
        )
      )
    else if !workspace.acquire() then Left(ResamplingError.WorkspaceInUse)
    else
      try execute(workspace)
      finally workspace.release()

  /**
   * Traverse resampled values without materializing output arrays.
   */
  def scan(
      workspace: ResamplingWorkspace[D],
      sink: ResamplingSink
  ): Either[ResamplingError, Unit] =
    if workspace.spatialRank != dimension.rank then
      Left(
        ResamplingError.WorkspaceDimensionMismatch(
          dimension.rank,
          workspace.spatialRank
        )
      )
    else if !workspace.acquire() then Left(ResamplingError.WorkspaceInUse)
    else
      try executeSink(workspace, sink)
      finally workspace.release()

  private def execute(
      workspace: ResamplingWorkspace[D]
  ): Either[ResamplingError, ResamplingResult[TargetFrame, D, Sem]] =
    val outputShape = target.shape ++ source.nonSpatialAxes.shape
    val built =
      outputShape.length match
        case 2 =>
          build(Shape(outputShape(0), outputShape(1)), workspace)
        case 3 =>
          build(
            Shape(outputShape(0), outputShape(1), outputShape(2)),
            workspace
          )
        case 4 =>
          build(
            Shape(
              outputShape(0),
              outputShape(1),
              outputShape(2),
              outputShape(3)
            ),
            workspace
          )
        case rank =>
          Left(
            ResamplingError.UnsupportedDataRank(
              dimension.rank,
              rank,
              ResamplingPlan.MaximumProductionRank
            )
          )

    built.flatMap(
      _.finish[TargetFrame, D, Sem](target, source.nonSpatialAxes)
    )

  private def build[OutRank <: AnyRank](
      shape: Shape[OutRank],
      workspace: ResamplingWorkspace[D]
  ): Either[
    ResamplingError,
    BuiltOutput[OutRank]
  ] =
    var completedValues = Option.empty[NDArray[Double, OutRank]]
    val validity =
      NDArray.build[Double, OutRank](shape) { validityBuilder =>
        val values =
          NDArray.build[Double, OutRank](shape) { valueBuilder =>
            executeKernel(valueBuilder, validityBuilder, workspace)
          }
        completedValues = Some(values)
      }
    if workspace.hasFailed then Left(workspace.outsideError)
    else
      completedValues
        .map(values => new BuiltOutput(values, validity))
        .toRight(ResamplingError.BuilderProtocolViolation)

  private def executeKernel(
      values: ArrayBuilder[Double],
      validity: ArrayBuilder[Double],
      workspace: ResamplingWorkspace[D]
  ): Unit =
    kernel match
      case affine: AffineIndexKernel.D2 =>
        executeD2(affine, values, validity, workspace)
      case affine: AffineIndexKernel.D3 =>
        executeD3(affine, values, validity, workspace)

  private def executeSink(
      workspace: ResamplingWorkspace[D],
      sink: ResamplingSink
  ): Either[ResamplingError, Unit] =
    kernel match
      case affine: AffineIndexKernel.D2 =>
        executeD2Sink(affine, sink, workspace)
      case affine: AffineIndexKernel.D3 =>
        executeD3Sink(affine, sink, workspace)
    if workspace.hasFailed then Left(workspace.outsideError)
    else Right(())

  private def executeD2Sink(
      affine: AffineIndexKernel.D2,
      sink: ResamplingSink,
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val target0 = target.shape(0)
    val target1 = target.shape(1)
    val extra0 =
      if source.nonSpatialAxes.size >= 1 then
        source.nonSpatialAxes.shape(0)
      else 1
    val extra1 =
      if source.nonSpatialAxes.size >= 2 then
        source.nonSpatialAxes.shape(1)
      else 1
    var i = 0
    var output = 0
    while i < target0 && !workspace.hasFailed do
      var j = 0
      while j < target1 && !workspace.hasFailed do
        affine.load(i, j, workspace)
        var a = 0
        while a < extra0 && !workspace.hasFailed do
          var b = 0
          while b < extra1 && !workspace.hasFailed do
            sample(i, j, 0, a, b, workspace)
            if !workspace.hasFailed then
              sink.accept(
                output,
                workspace.sampledValue,
                workspace.insideWeight
              )
              output += 1
            b += 1
          a += 1
        j += 1
      i += 1

  private def executeD3Sink(
      affine: AffineIndexKernel.D3,
      sink: ResamplingSink,
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val target0 = target.shape(0)
    val target1 = target.shape(1)
    val target2 = target.shape(2)
    val extra0 =
      if source.nonSpatialAxes.size == 1 then
        source.nonSpatialAxes.shape(0)
      else 1
    var i = 0
    var output = 0
    while i < target0 && !workspace.hasFailed do
      var j = 0
      while j < target1 && !workspace.hasFailed do
        var k = 0
        while k < target2 && !workspace.hasFailed do
          affine.load(i, j, k, workspace)
          var a = 0
          while a < extra0 && !workspace.hasFailed do
            sample(i, j, k, a, 0, workspace)
            if !workspace.hasFailed then
              sink.accept(
                output,
                workspace.sampledValue,
                workspace.insideWeight
              )
              output += 1
            a += 1
          k += 1
        j += 1
      i += 1

  private def executeD2(
      affine: AffineIndexKernel.D2,
      values: ArrayBuilder[Double],
      validity: ArrayBuilder[Double],
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val target0 = target.shape(0)
    val target1 = target.shape(1)
    val extra0 =
      if source.nonSpatialAxes.size >= 1 then
        source.nonSpatialAxes.shape(0)
      else 1
    val extra1 =
      if source.nonSpatialAxes.size >= 2 then
        source.nonSpatialAxes.shape(1)
      else 1
    var i = 0
    var output = 0
    while i < target0 && !workspace.hasFailed do
      var j = 0
      while j < target1 && !workspace.hasFailed do
        affine.load(i, j, workspace)
        var a = 0
        while a < extra0 && !workspace.hasFailed do
          var b = 0
          while b < extra1 && !workspace.hasFailed do
            sample(i, j, 0, a, b, workspace)
            if !workspace.hasFailed then
              values.writeLinear(output, workspace.sampledValue)
              validity.writeLinear(output, workspace.insideWeight)
              output += 1
            b += 1
          a += 1
        j += 1
      i += 1

  private def executeD3(
      affine: AffineIndexKernel.D3,
      values: ArrayBuilder[Double],
      validity: ArrayBuilder[Double],
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val target0 = target.shape(0)
    val target1 = target.shape(1)
    val target2 = target.shape(2)
    val extra0 =
      if source.nonSpatialAxes.size == 1 then
        source.nonSpatialAxes.shape(0)
      else 1
    var i = 0
    var output = 0
    while i < target0 && !workspace.hasFailed do
      var j = 0
      while j < target1 && !workspace.hasFailed do
        var k = 0
        while k < target2 && !workspace.hasFailed do
          affine.load(i, j, k, workspace)
          var a = 0
          while a < extra0 && !workspace.hasFailed do
            sample(i, j, k, a, 0, workspace)
            if !workspace.hasFailed then
              values.writeLinear(output, workspace.sampledValue)
              validity.writeLinear(output, workspace.insideWeight)
              output += 1
            a += 1
          k += 1
        j += 1
      i += 1

  private def sample(
      target0: Int,
      target1: Int,
      target2: Int,
      extra0: Int,
      extra1: Int,
      workspace: ResamplingWorkspace[D]
  ): Unit =
    interpolation match
      case Interpolation.Nearest =>
        sampleNearest(
          target0,
          target1,
          target2,
          extra0,
          extra1,
          workspace
        )
      case Interpolation.Linear =>
        sampleLinear(
          target0,
          target1,
          target2,
          extra0,
          extra1,
          workspace
        )
      case Interpolation.Lanczos5 =>
        sampleLanczos5(
          target0,
          target1,
          target2,
          extra0,
          extra1,
          workspace
        )

  private def sampleNearest(
      target0: Int,
      target1: Int,
      target2: Int,
      extra0: Int,
      extra1: Int,
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val i = math.floor(workspace.source0 + 0.5).toInt
    val j = math.floor(workspace.source1 + 0.5).toInt
    val k = math.floor(workspace.source2 + 0.5).toInt
    if contains(i, j, k) then
      workspace.sampledValue = read(i, j, k, extra0, extra1)
      workspace.insideWeight = 1.0
    else
      boundary match
        case BoundaryPolicy.Constant(value) =>
          workspace.sampledValue = value
          workspace.insideWeight = 0.0
        case BoundaryPolicy.Reject =>
          workspace.recordOutside(target0, target1, target2)

  private def sampleLinear(
      target0: Int,
      target1: Int,
      target2: Int,
      extra0: Int,
      extra1: Int,
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val lower0 = math.floor(workspace.source0).toInt
    val lower1 = math.floor(workspace.source1).toInt
    val lower2 = math.floor(workspace.source2).toInt
    val fraction0 = workspace.source0 - lower0.toDouble
    val fraction1 = workspace.source1 - lower1.toDouble
    val fraction2 = workspace.source2 - lower2.toDouble
    val cornerCount = 1 << dimension.rank
    var corner = 0
    var total = 0.0
    var inside = 0.0
    while corner < cornerCount && !workspace.hasFailed do
      val upper0 = corner & 1
      val upper1 = (corner >>> 1) & 1
      val upper2 =
        if dimension.rank == 3 then (corner >>> 2) & 1 else 0
      val weight0 = if upper0 == 1 then fraction0 else 1.0 - fraction0
      val weight1 = if upper1 == 1 then fraction1 else 1.0 - fraction1
      val weight2 =
        if dimension.rank == 3 then
          if upper2 == 1 then fraction2 else 1.0 - fraction2
        else 1.0
      val weight = weight0 * weight1 * weight2
      if weight > 0.0 then
        val i = lower0 + upper0
        val j = lower1 + upper1
        val k = lower2 + upper2
        if contains(i, j, k) then
          total += weight * read(i, j, k, extra0, extra1)
          inside += weight
        else
          boundary match
            case BoundaryPolicy.Reject =>
              workspace.recordOutside(target0, target1, target2)
            case BoundaryPolicy.Constant(value) =>
              total += weight * value
      corner += 1
    if !workspace.hasFailed then
      workspace.sampledValue = total
      workspace.insideWeight =
        if inside >= 1.0 - ResamplingPlan.ValidityTolerance then 1.0
        else if inside <= ResamplingPlan.ValidityTolerance then 0.0
        else inside

  private def sampleLanczos5(
      target0: Int,
      target1: Int,
      target2: Int,
      extra0: Int,
      extra1: Int,
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val representable =
      LanczosKernel.hasRepresentableStencil(workspace.source0) &&
        LanczosKernel.hasRepresentableStencil(workspace.source1) &&
        (
          dimension.rank == 2 ||
            LanczosKernel.hasRepresentableStencil(workspace.source2)
        )
    if representable then
      sampleLanczos5Representable(
        target0,
        target1,
        target2,
        extra0,
        extra1,
        workspace
      )
    else
      boundary match
        case BoundaryPolicy.Reject =>
          workspace.recordOutside(target0, target1, target2)
        case BoundaryPolicy.Constant(value) =>
          workspace.sampledValue = value
          workspace.insideWeight = 0.0

  private def sampleLanczos5Representable(
      target0: Int,
      target1: Int,
      target2: Int,
      extra0: Int,
      extra1: Int,
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val first0 =
      LanczosKernel.fillNormalized(
        workspace.source0,
        workspace.lanczosWeights0
      )
    val first1 =
      LanczosKernel.fillNormalized(
        workspace.source1,
        workspace.lanczosWeights1
      )
    val first2 =
      if dimension.rank == 3 then
        LanczosKernel.fillNormalized(
          workspace.source2,
          workspace.lanczosWeights2
        )
      else 0
    val taps2 =
      if dimension.rank == 3 then LanczosKernel.TapCount else 1
    val absoluteTotal =
      LanczosKernel.absoluteSum(workspace.lanczosWeights0) *
        LanczosKernel.absoluteSum(workspace.lanczosWeights1) *
        (
          if dimension.rank == 3 then
            LanczosKernel.absoluteSum(workspace.lanczosWeights2)
          else 1.0
        )
    var value = 0.0
    var absoluteInside = 0.0
    var tap0 = 0
    while tap0 < LanczosKernel.TapCount && !workspace.hasFailed do
      val weight0 = workspace.lanczosWeights0(tap0)
      var tap1 = 0
      while tap1 < LanczosKernel.TapCount && !workspace.hasFailed do
        val weight01 = weight0 * workspace.lanczosWeights1(tap1)
        var tap2 = 0
        while tap2 < taps2 && !workspace.hasFailed do
          val weight =
            if dimension.rank == 3 then
              weight01 * workspace.lanczosWeights2(tap2)
            else weight01
          if math.abs(weight) > LanczosKernel.WeightTolerance then
            val i = first0 + tap0
            val j = first1 + tap1
            val k = if dimension.rank == 3 then first2 + tap2 else 0
            if contains(i, j, k) then
              value += weight * read(i, j, k, extra0, extra1)
              absoluteInside += math.abs(weight)
            else
              boundary match
                case BoundaryPolicy.Reject =>
                  workspace.recordOutside(target0, target1, target2)
                case BoundaryPolicy.Constant(outside) =>
                  value += weight * outside
          tap2 += 1
        tap1 += 1
      tap0 += 1
    if !workspace.hasFailed then
      workspace.sampledValue = value
      val fraction =
        math.max(0.0, math.min(1.0, absoluteInside / absoluteTotal))
      workspace.insideWeight =
        if fraction >= 1.0 - ResamplingPlan.ValidityTolerance then 1.0
        else if fraction <= ResamplingPlan.ValidityTolerance then 0.0
        else fraction

  private def contains(i: Int, j: Int, k: Int): Boolean =
    i >= 0 &&
      i < source.grid.shape(0) &&
      j >= 0 &&
      j < source.grid.shape(1) &&
      (dimension.rank == 2 || (k >= 0 && k < source.grid.shape(2)))

  private def read(
      i: Int,
      j: Int,
      k: Int,
      extra0: Int,
      extra1: Int
  ): Double =
    reader.read(source.data, i, j, k, extra0, extra1)

/**
 * Immutable nearest-neighbor pull-resampling plan for categorical images.
 *
 * Keeping this plan integral-only makes label preservation structural: no
 * categorical value passes through floating-point interpolation or a lossy
 * numeric conversion.
 */
final class CategoricalResamplingPlan[
    SourceFrame <: Frame[D],
    TargetFrame <: Frame[D],
    D <: Dim,
    A,
    R <: AnyRank
] private[resample] (
    val source: Sampled[
      ? <: SampleSpace[SourceFrame, D],
      A,
      Categorical,
      R
    ],
    val target: Grid[TargetFrame, D],
    val boundary: BoundaryPolicy[A],
    private val kernel: AffineIndexKernel
)(using
    private val dimension: Dimension[D],
    private val dtype: IntegralDType[A],
    private val semantics: ValueSemantics[A, Categorical]
):
  private val canonicalData = CanonicalArray.require(source.data)

  val structure: PlanStructure =
    PlanStructure(
      spatialRank = dimension.rank,
      dataRank = source.data.rank,
      materializedCoordinateCount = 0
    )

  def newWorkspace(): ResamplingWorkspace[D] =
    ResamplingWorkspace.create[D]

  def run(
      workspace: ResamplingWorkspace[D]
  ): Either[
    ResamplingError,
    CategoricalResamplingResult[TargetFrame, D, A]
  ] =
    if workspace.spatialRank != dimension.rank then
      Left(
        ResamplingError.WorkspaceDimensionMismatch(
          dimension.rank,
          workspace.spatialRank
        )
      )
    else if !workspace.acquire() then Left(ResamplingError.WorkspaceInUse)
    else
      try execute(workspace)
      finally workspace.release()

  private def execute(
      workspace: ResamplingWorkspace[D]
  ): Either[
    ResamplingError,
    CategoricalResamplingResult[TargetFrame, D, A]
  ] =
    val outputShape = target.shape ++ source.nonSpatialAxes.shape
    val built =
      outputShape.length match
        case 2 => build(Shape(outputShape(0), outputShape(1)), workspace)
        case 3 =>
          build(
            Shape(outputShape(0), outputShape(1), outputShape(2)),
            workspace
          )
        case 4 =>
          build(
            Shape(
              outputShape(0),
              outputShape(1),
              outputShape(2),
              outputShape(3)
            ),
            workspace
          )
        case rank =>
          Left(
            ResamplingError.UnsupportedDataRank(
              dimension.rank,
              rank,
              ResamplingPlan.MaximumProductionRank
            )
          )
    built.flatMap(
      _.finish[TargetFrame, D](target, source.nonSpatialAxes)
    )

  private def build[OutRank <: AnyRank](
      shape: Shape[OutRank],
      workspace: ResamplingWorkspace[D]
  ): Either[ResamplingError, BuiltCategoricalOutput[A, OutRank]] =
    var completedValues = Option.empty[NDArray[A, OutRank]]
    val validity =
      NDArray.build[Double, OutRank](shape) { validityBuilder =>
        val values =
          NDArray.build[A, OutRank](shape) { valueBuilder =>
            executeKernel(valueBuilder, validityBuilder, workspace)
          }
        completedValues = Some(values)
      }
    if workspace.hasFailed then Left(workspace.outsideError)
    else
      completedValues
        .map(values => new BuiltCategoricalOutput(values, validity))
        .toRight(ResamplingError.BuilderProtocolViolation)

  private def executeKernel(
      values: ArrayBuilder[A],
      validity: ArrayBuilder[Double],
      workspace: ResamplingWorkspace[D]
  ): Unit =
    kernel match
      case affine: AffineIndexKernel.D2 =>
        executeD2(affine, values, validity, workspace)
      case affine: AffineIndexKernel.D3 =>
        executeD3(affine, values, validity, workspace)

  private def executeD2(
      affine: AffineIndexKernel.D2,
      values: ArrayBuilder[A],
      validity: ArrayBuilder[Double],
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val extra0 =
      if source.nonSpatialAxes.size >= 1 then source.nonSpatialAxes.shape(0)
      else 1
    val extra1 =
      if source.nonSpatialAxes.size >= 2 then source.nonSpatialAxes.shape(1)
      else 1
    var i = 0
    var output = 0
    while i < target.shape(0) && !workspace.hasFailed do
      var j = 0
      while j < target.shape(1) && !workspace.hasFailed do
        affine.load(i, j, workspace)
        var a = 0
        while a < extra0 && !workspace.hasFailed do
          var b = 0
          while b < extra1 && !workspace.hasFailed do
            writeNearest(i, j, 0, a, b, output, values, validity, workspace)
            if !workspace.hasFailed then output += 1
            b += 1
          a += 1
        j += 1
      i += 1

  private def executeD3(
      affine: AffineIndexKernel.D3,
      values: ArrayBuilder[A],
      validity: ArrayBuilder[Double],
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val extra0 =
      if source.nonSpatialAxes.size == 1 then source.nonSpatialAxes.shape(0)
      else 1
    var i = 0
    var output = 0
    while i < target.shape(0) && !workspace.hasFailed do
      var j = 0
      while j < target.shape(1) && !workspace.hasFailed do
        var k = 0
        while k < target.shape(2) && !workspace.hasFailed do
          affine.load(i, j, k, workspace)
          var a = 0
          while a < extra0 && !workspace.hasFailed do
            writeNearest(i, j, k, a, 0, output, values, validity, workspace)
            if !workspace.hasFailed then output += 1
            a += 1
          k += 1
        j += 1
      i += 1

  private def writeNearest(
      target0: Int,
      target1: Int,
      target2: Int,
      extra0: Int,
      extra1: Int,
      output: Int,
      values: ArrayBuilder[A],
      validity: ArrayBuilder[Double],
      workspace: ResamplingWorkspace[D]
  ): Unit =
    val i = math.floor(workspace.source0 + 0.5).toInt
    val j = math.floor(workspace.source1 + 0.5).toInt
    val k = math.floor(workspace.source2 + 0.5).toInt
    if contains(i, j, k) then
      values.writeLinear(output, read(i, j, k, extra0, extra1))
      validity.writeLinear(output, 1.0)
    else
      boundary match
        case BoundaryPolicy.Constant(value) =>
          values.writeLinear(output, value)
          validity.writeLinear(output, 0.0)
        case BoundaryPolicy.Reject =>
          workspace.recordOutside(target0, target1, target2)

  private def contains(i: Int, j: Int, k: Int): Boolean =
    i >= 0 &&
      i < source.grid.shape(0) &&
      j >= 0 &&
      j < source.grid.shape(1) &&
      (dimension.rank == 2 || (k >= 0 && k < source.grid.shape(2)))

  private def read(
      i: Int,
      j: Int,
      k: Int,
      extra0: Int,
      extra1: Int
  ): A =
    val indices =
      if dimension.rank == 2 then Vector(i, j, extra0, extra1)
      else Vector(i, j, k, extra0)
    var axis = 0
    var linear = 0
    while axis < source.data.rank do
      linear = linear * source.data.shape(axis) + indices(axis)
      axis += 1
    canonicalData.readLinear(linear)

object ResamplingPlan:
  private[resample] val MaximumProductionRank = 4
  private[resample] val ValidityTolerance = 1e-12

  def nearest[
      SourceFrame <: Frame[D],
      TargetFrame <: Frame[D],
      D <: Dim,
      A,
      S <: SampleSpace[SourceFrame, D],
      R <: AnyRank
  ](
      source: Sampled[S, A, Categorical, R],
      target: Grid[TargetFrame, D],
      pull: AffineMap[TargetFrame, SourceFrame, D],
      boundary: BoundaryPolicy[A] = BoundaryPolicy.Reject
  )(using
      dimension: Dimension[D],
      dtype: IntegralDType[A],
      semantics: ValueSemantics[A, Categorical]
  ): Either[
    ResamplingError,
    CategoricalResamplingPlan[SourceFrame, TargetFrame, D, A, R]
  ] =
    for
      _ <- validateDataRank(source.data.rank, dimension.rank)
      _ <- validateEndpoint(target.frame, pull.source)
      _ <- validateEndpoint(source.frame, pull.target)
      targetToSourceFrame <- target.indexToFrame
        .andThen(pull.operator)
        .left
        .map(ResamplingError.Geometry.apply)
      targetToSourceIndex <- targetToSourceFrame
        .andThen(source.grid.indexToFrame.inverse)
        .left
        .map(ResamplingError.Geometry.apply)
      kernel <- AffineIndexKernel
        .compile(targetToSourceIndex)
        .left
        .map(ResamplingError.Geometry.apply)
    yield new CategoricalResamplingPlan(
      source,
      target,
      boundary,
      kernel
    )

  def affine[
      SourceFrame <: Frame[D],
      TargetFrame <: Frame[D],
      D <: Dim,
      Sem,
      S <: SampleSpace[SourceFrame, D],
      R <: AnyRank
  ](
      source: Sampled[S, Double, Sem, R],
      target: Grid[TargetFrame, D],
      pull: AffineMap[TargetFrame, SourceFrame, D],
      interpolation: Interpolation[Sem],
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Reject
  )(using
      dimension: Dimension[D],
      semantics: ValueSemantics[Double, Sem]
  ): Either[
    ResamplingError,
    ResamplingPlan[SourceFrame, TargetFrame, D, Sem, R]
  ] =
    for
      reader <- DataReader.compile(source.data.rank, dimension.rank)
      _ <- validateEndpoint(target.frame, pull.source)
      _ <- validateEndpoint(source.frame, pull.target)
      targetToSourceFrame <- target.indexToFrame
        .andThen(pull.operator)
        .left
        .map(ResamplingError.Geometry.apply)
      targetToSourceIndex <- targetToSourceFrame
        .andThen(source.grid.indexToFrame.inverse)
        .left
        .map(ResamplingError.Geometry.apply)
      kernel <- AffineIndexKernel
        .compile(targetToSourceIndex)
        .left
        .map(ResamplingError.Geometry.apply)
    yield new ResamplingPlan(
      source,
      target,
      interpolation,
      boundary,
      kernel,
      reader
    )

  private def validateDataRank(
      dataRank: Int,
      spatialRank: Int
  ): Either[ResamplingError, Unit] =
    if dataRank >= spatialRank && dataRank <= MaximumProductionRank then
      Right(())
    else
      Left(
        ResamplingError.UnsupportedDataRank(
          spatialRank,
          dataRank,
          MaximumProductionRank
        )
      )

  private def validateEndpoint[D <: Dim](
      expected: Frame[D],
      actual: Frame[D]
  ): Either[ResamplingError, Unit] =
    image4s.geometry.Frame
      .align(expected, actual)
      .left
      .map(ResamplingError.Geometry.apply)
      .map(_ => ())

private sealed trait AffineIndexKernel

private final class BuiltOutput[R <: AnyRank](
    values: NDArray[Double, R],
    validityWeights: NDArray[Double, R]
):
  def finish[
      F <: Frame[D],
      D <: Dim,
      Sem
  ](
      target: Grid[F, D],
      nonSpatialAxes: NonSpatialAxes
  )(using ValueSemantics[Double, Sem])
      : Either[ResamplingError, ResamplingResult[F, D, Sem]] =
    val sampleSpace = SampleSpace.create(target, nonSpatialAxes)
    for
      image <- Sampled
        .create[Double, Sem, R](sampleSpace, values)
        .left
        .map(ResamplingError.Image.apply)
      weights <- Sampled
        .continuous[Double, R](sampleSpace, validityWeights)
        .left
        .map(ResamplingError.Image.apply)
    yield new ResamplingResultImpl(
      image,
      new ValidityMask(weights)
    )

private final class BuiltCategoricalOutput[A, R <: AnyRank](
    values: NDArray[A, R],
    validityWeights: NDArray[Double, R]
)(using ValueSemantics[A, Categorical]):
  def finish[F <: Frame[D], D <: Dim](
      target: Grid[F, D],
      nonSpatialAxes: NonSpatialAxes
  ): Either[ResamplingError, CategoricalResamplingResult[F, D, A]] =
    val sampleSpace = SampleSpace.create(target, nonSpatialAxes)
    for
      image <- Sampled
        .create[A, Categorical, R](sampleSpace, values)
        .left
        .map(ResamplingError.Image.apply)
      weights <- Sampled
        .continuous[Double, R](sampleSpace, validityWeights)
        .left
        .map(ResamplingError.Image.apply)
    yield new CategoricalResamplingResultImpl(
      image,
      new ValidityMask(weights)
    )

private object AffineIndexKernel:
  final class D2(
      m00: Double,
      m01: Double,
      m02: Double,
      m10: Double,
      m11: Double,
      m12: Double
  ) extends AffineIndexKernel:
    def load[D <: Dim](
        i: Int,
        j: Int,
        workspace: ResamplingWorkspace[D]
    ): Unit =
      workspace.source0 = m00 * i.toDouble + m01 * j.toDouble + m02
      workspace.source1 = m10 * i.toDouble + m11 * j.toDouble + m12
      workspace.source2 = 0.0

  final class D3(
      m00: Double,
      m01: Double,
      m02: Double,
      m03: Double,
      m10: Double,
      m11: Double,
      m12: Double,
      m13: Double,
      m20: Double,
      m21: Double,
      m22: Double,
      m23: Double
  ) extends AffineIndexKernel:
    def load[D <: Dim](
        i: Int,
        j: Int,
        k: Int,
        workspace: ResamplingWorkspace[D]
    ): Unit =
      workspace.source0 =
        m00 * i.toDouble + m01 * j.toDouble + m02 * k.toDouble + m03
      workspace.source1 =
        m10 * i.toDouble + m11 * j.toDouble + m12 * k.toDouble + m13
      workspace.source2 =
        m20 * i.toDouble + m21 * j.toDouble + m22 * k.toDouble + m23

  def compile[D <: Dim](
      affine: Affine[D]
  )(using dimension: Dimension[D]): Either[GeometryError, AffineIndexKernel] =
    val values = affine.rowMajor
    dimension.rank match
      case 2 =>
        Right(
          new D2(
            values(0),
            values(1),
            values(2),
            values(3),
            values(4),
            values(5)
          )
        )
      case 3 =>
        Right(
          new D3(
            values(0),
            values(1),
            values(2),
            values(3),
            values(4),
            values(5),
            values(6),
            values(7),
            values(8),
            values(9),
            values(10),
            values(11)
          )
        )
      case rank =>
        Left(GeometryError.UnsupportedSpatialRank(rank))

private sealed trait DataReader:
  def read(
      data: NDArray[Double, ? <: AnyRank],
      i: Int,
      j: Int,
      k: Int,
      extra0: Int,
      extra1: Int
  ): Double

private object DataReader:
  private object D2Rank2 extends DataReader:
    def read(
        data: NDArray[Double, ? <: AnyRank],
        i: Int,
        j: Int,
        k: Int,
        extra0: Int,
        extra1: Int
    ): Double =
      data.asInstanceOf[NDArray[Double, Rank[2]]](i, j)

  private object D2Rank3 extends DataReader:
    def read(
        data: NDArray[Double, ? <: AnyRank],
        i: Int,
        j: Int,
        k: Int,
        extra0: Int,
        extra1: Int
    ): Double =
      data.asInstanceOf[NDArray[Double, Rank[3]]](i, j, extra0)

  private object D2Rank4 extends DataReader:
    def read(
        data: NDArray[Double, ? <: AnyRank],
        i: Int,
        j: Int,
        k: Int,
        extra0: Int,
        extra1: Int
    ): Double =
      data
        .asInstanceOf[NDArray[Double, Rank[4]]](
          i,
          j,
          extra0,
          extra1
        )

  private object D3Rank3 extends DataReader:
    def read(
        data: NDArray[Double, ? <: AnyRank],
        i: Int,
        j: Int,
        k: Int,
        extra0: Int,
        extra1: Int
    ): Double =
      data.asInstanceOf[NDArray[Double, Rank[3]]](i, j, k)

  private object D3Rank4 extends DataReader:
    def read(
        data: NDArray[Double, ? <: AnyRank],
        i: Int,
        j: Int,
        k: Int,
        extra0: Int,
        extra1: Int
    ): Double =
      data.asInstanceOf[NDArray[Double, Rank[4]]](i, j, k, extra0)

  def compile(
      dataRank: Int,
      spatialRank: Int
  ): Either[ResamplingError, DataReader] =
    (spatialRank, dataRank) match
      case (2, 2) => Right(D2Rank2)
      case (2, 3) => Right(D2Rank3)
      case (2, 4) => Right(D2Rank4)
      case (3, 3) => Right(D3Rank3)
      case (3, 4) => Right(D3Rank4)
      case _ =>
        Left(
          ResamplingError.UnsupportedDataRank(
            spatialRank,
            dataRank,
            ResamplingPlan.MaximumProductionRank
          )
        )
