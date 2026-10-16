package reframe4s.multiscale

import image4s.BoundaryPolicy
import image4s.ContinuousImage
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.SampleSpace
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import reframe4s.core.AffineMap
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingPlan
import reframe4s.resample.ResamplingWorkspace

/**
 * One sampled level in a filtered D3 scalar scale space.
 */
final class ScalarPyramidLevel[
    F <: Frame[D3],
    +C
] private[multiscale] (
    val level: GridLevel[F, D3, C],
    val image: ContinuousImage[
      ? <: SampleSpace[F, D3],
      Double,
      Rank[3]
    ]
)

/**
 * Immutable filtered samples aligned with an authoritative [[GridTower]].
 */
final class ScalarPyramid3[
    F <: Frame[D3],
    +C
] private (
    val tower: GridTower[F, D3, C],
    val levels: Vector[ScalarPyramidLevel[F, C]]
):
  def size: Int = levels.size

/**
 * Reusable scratch state for one pyramid build at a time.
 */
final class ScalarPyramidWorkspace private (
    private[multiscale] val resampling: ResamplingWorkspace[D3]
):
  private var active = false

  private[multiscale] def acquire(): Boolean =
    if active then false
    else
      active = true
      true

  private[multiscale] def release(): Unit =
    active = false

object ScalarPyramidWorkspace:
  def create(using Dimension[D3]): ScalarPyramidWorkspace =
    new ScalarPyramidWorkspace(ResamplingWorkspace.create[D3])

object ScalarPyramid3:
  /**
   * Build each level by smoothing the native immutable samples in physical
   * units and then evaluating them on the level grid with the production
   * affine resampler.
   *
   * Gaussian filtering is separable along grid-index axes. Physical sigma is
   * converted with the norm of the corresponding complete affine column, so
   * translated, reflected, anisotropic, and oblique grids retain their true
   * axis scale. Edge taps use clamped extension.
   */
  def build[
      F <: Frame[D3],
      C
  ](
      source: ContinuousImage[
        ? <: SampleSpace[F, D3],
        Double,
        ? <: AnyRank
      ],
      tower: GridTower[F, D3, C],
      workspace: ScalarPyramidWorkspace
  )(using Dimension[D3]): Either[
    MultiscaleError,
    ScalarPyramid3[F, C]
  ] =
    if !workspace.acquire() then
      Left(MultiscaleError.ScalarPyramidWorkspaceInUse)
    else
      try execute(source, tower, workspace)
      finally workspace.release()

  private def execute[
      F <: Frame[D3],
      C
  ](
      source: ContinuousImage[
        ? <: SampleSpace[F, D3],
        Double,
        ? <: AnyRank
      ],
      tower: GridTower[F, D3, C],
      workspace: ScalarPyramidWorkspace
  )(using Dimension[D3]): Either[
    MultiscaleError,
    ScalarPyramid3[F, C]
  ] =
    for
      _ <-
        if source.nonSpatialAxes.size == 0 then Right(())
        else
          Left(
            MultiscaleError.NonSpatialScalarAxes(
              source.nonSpatialAxes.size
            )
          )
      data <- source.data
        .requireRank[3]
        .left
        .map(_ =>
          MultiscaleError.UnsupportedScalarRank(source.data.rank)
        )
      axisScale <- physicalAxisScale(source.grid.indexToFrame.rowMajor)
      levels <- buildLevels(
        source,
        data,
        tower,
        axisScale,
        workspace
      )
    yield new ScalarPyramid3(tower, levels)

  private def buildLevels[
      F <: Frame[D3],
      C
  ](
      source: ContinuousImage[
        ? <: SampleSpace[F, D3],
        Double,
        ? <: AnyRank
      ],
      data: NDArray[Double, Rank[3]],
      tower: GridTower[F, D3, C],
      axisScale: Vector[Double],
      workspace: ScalarPyramidWorkspace
  )(using Dimension[D3]): Either[
    MultiscaleError,
    Vector[ScalarPyramidLevel[F, C]]
  ] =
    val result =
      Vector.newBuilder[ScalarPyramidLevel[F, C]]
    var index = 0
    var failure = Option.empty[MultiscaleError]
    while index < tower.levels.size && failure.isEmpty do
      val level = tower.levels(index)
      val sigmaIndex =
        Vector.tabulate(3)(axis =>
          level.scale.smoothingSigmaPhysical(axis) /
            axisScale(axis)
        )
      val filtered = gaussian(data, sigmaIndex)
      val sampled =
        Sampled
          .continuous(
            source.grid,
            NonSpatialAxes.empty,
            filtered
          )
          .left
          .map(MultiscaleError.Image.apply)
      val built =
        sampled.flatMap { filteredSource =>
          if level.grid eq source.grid then
            Right(filteredSource)
          else
            for
              plan <- ResamplingPlan
                .affine(
                  filteredSource,
                  level.grid,
                  AffineMap.identity(source.frame),
                  Interpolation.Linear,
                  BoundaryPolicy.Constant(0.0)
                )
                .left
                .map(MultiscaleError.Resampling.apply)
              resampled <- plan
                .run(workspace.resampling)
                .left
                .map(MultiscaleError.Resampling.apply)
              rankThree <- resampled.image.data
                .requireRank[3]
                .left
                .map(_ =>
                  MultiscaleError.UnsupportedScalarRank(
                    resampled.image.data.rank
                  )
                )
              image <- Sampled
                .continuous(
                  level.grid,
                  NonSpatialAxes.empty,
                  rankThree
                )
                .left
                .map(MultiscaleError.Image.apply)
            yield image
        }
      built match
        case Left(error) =>
          failure = Some(error)
        case Right(image) =>
          result += new ScalarPyramidLevel(level, image)
      index += 1
    failure.toLeft(result.result())

  private def physicalAxisScale(
      affine: Vector[Double]
  ): Either[MultiscaleError, Vector[Double]] =
    val scale =
      Vector.tabulate(3) { column =>
        var squared = 0.0
        var row = 0
        while row < 3 do
          val value = affine(row * 4 + column)
          squared += value * value
          row += 1
        math.sqrt(squared)
      }
    scale.zipWithIndex.collectFirst {
      case (value, axis) if !value.isFinite || value <= 0.0 =>
        MultiscaleError.InvalidPhysicalAxisScale(axis, value)
    }.toLeft(scale)

  private def gaussian(
      source: NDArray[Double, Rank[3]],
      sigmaIndex: Vector[Double]
  ): NDArray[Double, Rank[3]] =
    var current = source
    var axis = 0
    while axis < 3 do
      if sigmaIndex(axis) > 1e-12 then
        current =
          convolveAxis(
            current,
            axis,
            gaussianKernel(sigmaIndex(axis))
          )
      axis += 1
    current

  private def gaussianKernel(sigma: Double): Vector[Double] =
    val radius = math.max(1, math.ceil(3.0 * sigma).toInt)
    val unnormalized =
      Vector.tabulate(2 * radius + 1) { index =>
        val offset = index - radius
        math.exp(
          -(offset.toDouble * offset.toDouble) /
            (2.0 * sigma * sigma)
        )
      }
    val total = unnormalized.sum
    unnormalized.map(_ / total)

  private def convolveAxis(
      source: NDArray[Double, Rank[3]],
      axis: Int,
      kernel: Vector[Double]
  ): NDArray[Double, Rank[3]] =
    val nx = source.shape(0)
    val ny = source.shape(1)
    val nz = source.shape(2)
    val radius = kernel.size / 2
    NDArray.build[Double, Rank[3]](Shape(nx, ny, nz)) { builder =>
      var i = 0
      while i < nx do
        var j = 0
        while j < ny do
          var k = 0
          while k < nz do
            var tap = 0
            var value = 0.0
            while tap < kernel.size do
              val offset = tap - radius
              val sourceI =
                if axis == 0 then clamp(i + offset, nx) else i
              val sourceJ =
                if axis == 1 then clamp(j + offset, ny) else j
              val sourceK =
                if axis == 2 then clamp(k + offset, nz) else k
              value +=
                kernel(tap) *
                  source(sourceI, sourceJ, sourceK)
              tap += 1
            val linear = (i * ny + j) * nz + k
            builder.writeLinear(linear, value)
            k += 1
          j += 1
        i += 1
    }

  private def clamp(index: Int, extent: Int): Int =
    if index < 0 then 0
    else if index >= extent then extent - 1
    else index
