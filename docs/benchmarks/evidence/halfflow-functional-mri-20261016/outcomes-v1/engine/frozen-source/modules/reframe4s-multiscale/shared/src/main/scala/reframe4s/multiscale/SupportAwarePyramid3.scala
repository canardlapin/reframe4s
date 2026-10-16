package reframe4s.multiscale

import image4s.BoundaryPolicy
import image4s.ContinuousImage
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.SampleSpace
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Grid
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import reframe4s.core.AffineMap
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingPlan
import reframe4s.resample.ResamplingWorkspace

/** Unit attached to a declared Gaussian point-spread width. */
enum GaussianWidthUnit derives CanEqual:
  case SigmaMillimetres
  case FwhmMillimetres

/** Auditable origin of an isotropic Gaussian PSF estimate. */
sealed trait PsfProvenance3 derives CanEqual

object PsfProvenance3:
  final case class Declared(width: Double, unit: GaussianWidthUnit)
      extends PsfProvenance3

  /**
   * Trace-equivalent isotropic approximation to a uniform voxel cell.
   *
   * For grid linear part `A`, the uniform-cell covariance is `A A' / 12`.
   * This approximation retains one third of its trace as isotropic variance.
   */
  final case class VoxelCellTraceEquivalent(
      affineLinearRowMajor: Vector[Double]
  ) extends PsfProvenance3

/** Isotropic Gaussian PSF represented canonically as sigma in millimetres. */
final class IsotropicGaussianPsf3 private (
    val sigmaMillimetres: Double,
    val provenance: PsfProvenance3
)

object IsotropicGaussianPsf3:
  private val FwhmPerSigma = 2.0 * math.sqrt(2.0 * math.log(2.0))

  def declared(
      width: Double,
      unit: GaussianWidthUnit
  ): Either[MultiscaleError, IsotropicGaussianPsf3] =
    if !width.isFinite || width < 0.0 then
      Left(MultiscaleError.InvalidPsfWidth(width, unit))
    else
      val sigma =
        unit match
          case GaussianWidthUnit.SigmaMillimetres => width
          case GaussianWidthUnit.FwhmMillimetres  => width / FwhmPerSigma
      Right(
        new IsotropicGaussianPsf3(
          sigma,
          PsfProvenance3.Declared(width, unit)
        )
      )

  /**
   * Approximate a voxel's box PSF by the isotropic Gaussian with equal mean
   * marginal variance. The complete affine columns are used, including shear.
   */
  def voxelCellApproximation[F <: Frame[D3]](
      grid: Grid[F, D3]
  ): IsotropicGaussianPsf3 =
    val affine = grid.indexToFrame.rowMajor
    val linear =
      Vector.tabulate(9) { flat =>
        val row = flat / 3
        val column = flat % 3
        affine(row * 4 + column)
      }
    var squaredFrobenius = 0.0
    var index = 0
    while index < linear.size do
      squaredFrobenius += linear(index) * linear(index)
      index += 1
    new IsotropicGaussianPsf3(
      math.sqrt(squaredFrobenius / 36.0),
      PsfProvenance3.VoxelCellTraceEquivalent(linear)
    )

/** Explicit physical and support policy for one support-aware pyramid. */
final class SupportAwarePyramidConfig3 private (
    val sourcePsf: IsotropicGaussianPsf3,
    val targetPsfs: Vector[IsotropicGaussianPsf3],
    val minimumSupport: Double,
    val gaussianTruncationSigma: Double
)

object SupportAwarePyramidConfig3:
  def create(
      sourcePsf: IsotropicGaussianPsf3,
      targetPsfs: Vector[IsotropicGaussianPsf3],
      minimumSupport: Double = 1e-6,
      gaussianTruncationSigma: Double = 3.0
  ): Either[MultiscaleError, SupportAwarePyramidConfig3] =
    if
      !minimumSupport.isFinite || minimumSupport <= 0.0 ||
        minimumSupport > 1.0
    then Left(MultiscaleError.InvalidSupportThreshold(minimumSupport))
    else if
      !gaussianTruncationSigma.isFinite || gaussianTruncationSigma <= 0.0
    then
      Left(
        MultiscaleError.InvalidGaussianTruncation(
          gaussianTruncationSigma
        )
      )
    else
      Right(
        new SupportAwarePyramidConfig3(
          sourcePsf,
          targetPsfs,
          minimumSupport,
          gaussianTruncationSigma
        )
      )

enum PhysicalGaussianPolicy3 derives CanEqual:
  /** Evaluate an isotropic world Gaussian through the complete native affine. */
  case NativeGridFullAffine

enum SupportPropagationPolicy3 derives CanEqual:
  /**
   * Zero-pad outside the lattice, filter value times support and support with
   * the same kernel, then divide only above the declared support threshold.
   */
  case ZeroPaddedNormalizedConvolution

enum PreparationMaterialization3 derives CanEqual:
  case NativeWeightedValues
  case NativeSupport
  case PhysicalGaussianConvolution
  case AffineLevelSampling
  case SupportNormalization

/** Evidence for the exact preparation policy applied to one level. */
final case class SupportAwareLevelProvenance3(
    sourcePsf: IsotropicGaussianPsf3,
    targetPsf: IsotropicGaussianPsf3,
    addedSigmaMillimetres: Double,
    negativeRequiredVarianceClamped: Boolean,
    physicalGaussianPolicy: PhysicalGaussianPolicy3,
    supportPolicy: SupportPropagationPolicy3,
    gaussianTruncationSigma: Double,
    kernelTapCount: Int,
    builtDirectlyFromNative: Boolean,
    materializations: Vector[PreparationMaterialization3],
    nativePositiveSupportCount: Long,
    levelPositiveSupportCount: Long,
    levelFullSupportCount: Long
)

/** One prepared value/support pair on an authoritative pyramid level. */
final class SupportAwarePyramidLevel3[
    F <: Frame[D3],
    +C
] private[multiscale] (
    val level: GridLevel[F, D3, C],
    val image: ContinuousImage[
      ? <: SampleSpace[F, D3],
      Double,
      Rank[3]
    ],
    /** Fraction of the Gaussian kernel mass backed by source observations. */
    val support: ContinuousImage[
      ? <: SampleSpace[F, D3],
      Double,
      Rank[3]
    ],
    val provenance: SupportAwareLevelProvenance3
)

/** Support-aware physical scale space aligned with a canonical grid tower. */
final class SupportAwarePyramid3[
    F <: Frame[D3],
    +C
] private (
    val tower: GridTower[F, D3, C],
    val levels: Vector[SupportAwarePyramidLevel3[F, C]]
):
  def size: Int = levels.size

/** Reusable resampling state for one support-aware build at a time. */
final class SupportAwarePyramidWorkspace3 private (
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

object SupportAwarePyramidWorkspace3:
  def create(using Dimension[D3]): SupportAwarePyramidWorkspace3 =
    new SupportAwarePyramidWorkspace3(ResamplingWorkspace.create[D3])

object SupportAwarePyramid3:
  def build[
      F <: Frame[D3],
      C
  ](
      source: ContinuousImage[
        ? <: SampleSpace[F, D3],
        Double,
        ? <: AnyRank
      ],
      nativeSupport: NDArray[Double, Rank[3]],
      tower: GridTower[F, D3, C],
      config: SupportAwarePyramidConfig3,
      workspace: SupportAwarePyramidWorkspace3
  )(using Dimension[D3]): Either[
    MultiscaleError,
    SupportAwarePyramid3[F, C]
  ] =
    if !workspace.acquire() then
      Left(MultiscaleError.SupportAwarePyramidWorkspaceInUse)
    else
      try execute(source, nativeSupport, tower, config, workspace)
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
      nativeSupport: NDArray[Double, Rank[3]],
      tower: GridTower[F, D3, C],
      config: SupportAwarePyramidConfig3,
      workspace: SupportAwarePyramidWorkspace3
  )(using Dimension[D3]): Either[
    MultiscaleError,
    SupportAwarePyramid3[F, C]
  ] =
    for
      _ <- validateImageRole(source)
      data <- source.data
        .requireRank[3]
        .left
        .map(_ => MultiscaleError.UnsupportedScalarRank(source.data.rank))
      _ <- validateSupportShape(source.grid.shape, nativeSupport)
      nativeCount <- validateNativeSamples(data, nativeSupport)
      _ <-
        if config.targetPsfs.size == tower.levels.size then Right(())
        else
          Left(
            MultiscaleError.PsfLevelCountMismatch(
              tower.levels.size,
              config.targetPsfs.size
            )
          )
      levels <- buildLevels(
        source,
        data,
        nativeSupport,
        nativeCount,
        tower,
        config,
        workspace
      )
    yield new SupportAwarePyramid3(tower, levels)

  private def validateImageRole(
      source: ContinuousImage[?, Double, ? <: AnyRank]
  ): Either[MultiscaleError, Unit] =
    if source.nonSpatialAxes.size == 0 then Right(())
    else
      Left(
        MultiscaleError.NonSpatialScalarAxes(
          source.nonSpatialAxes.size
        )
      )

  private def validateSupportShape(
      expected: Vector[Int],
      support: NDArray[Double, Rank[3]]
  ): Either[MultiscaleError, Unit] =
    val actual = Vector.tabulate(3)(support.shape(_))
    if expected == actual then Right(())
    else Left(MultiscaleError.SupportShapeMismatch(expected, actual))

  private def validateNativeSamples(
      source: NDArray[Double, Rank[3]],
      support: NDArray[Double, Rank[3]]
  ): Either[MultiscaleError, Long] =
    val nx = source.shape(0)
    val ny = source.shape(1)
    val nz = source.shape(2)
    var positive = 0L
    var failure = Option.empty[MultiscaleError]
    var i = 0
    while i < nx && failure.isEmpty do
      var j = 0
      while j < ny && failure.isEmpty do
        var k = 0
        while k < nz && failure.isEmpty do
          val linear = (i * ny + j) * nz + k
          val weight = support(i, j, k)
          val value = source(i, j, k)
          if !weight.isFinite || weight < 0.0 || weight > 1.0 then
            failure = Some(
              MultiscaleError.InvalidSupportValue(linear, weight)
            )
          else if weight > 0.0 && !value.isFinite then
            failure = Some(
              MultiscaleError.NonFiniteSupportedValue(linear, value)
            )
          else if weight > 0.0 then positive += 1L
          k += 1
        j += 1
      i += 1
    failure match
      case Some(error) => Left(error)
      case None if positive == 0L => Left(MultiscaleError.EmptySourceSupport)
      case None                   => Right(positive)

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
      nativeSupport: NDArray[Double, Rank[3]],
      nativeCount: Long,
      tower: GridTower[F, D3, C],
      config: SupportAwarePyramidConfig3,
      workspace: SupportAwarePyramidWorkspace3
  )(using Dimension[D3]): Either[
    MultiscaleError,
    Vector[SupportAwarePyramidLevel3[F, C]]
  ] =
    val result =
      Vector.newBuilder[SupportAwarePyramidLevel3[F, C]]
    var levelIndex = 0
    var failure = Option.empty[MultiscaleError]
    while levelIndex < tower.levels.size && failure.isEmpty do
      val level = tower.levels(levelIndex)
      val targetPsf = config.targetPsfs(levelIndex)
      buildLevel(
        source,
        data,
        nativeSupport,
        nativeCount,
        level,
        config.sourcePsf,
        targetPsf,
        config.minimumSupport,
        config.gaussianTruncationSigma,
        workspace
      ) match
        case Left(error)  => failure = Some(error)
        case Right(built) => result += built
      levelIndex += 1
    failure.toLeft(result.result())

  private def buildLevel[
      F <: Frame[D3],
      C
  ](
      source: ContinuousImage[
        ? <: SampleSpace[F, D3],
        Double,
        ? <: AnyRank
      ],
      data: NDArray[Double, Rank[3]],
      nativeSupport: NDArray[Double, Rank[3]],
      nativeCount: Long,
      level: GridLevel[F, D3, C],
      sourcePsf: IsotropicGaussianPsf3,
      targetPsf: IsotropicGaussianPsf3,
      minimumSupport: Double,
      truncation: Double,
      workspace: SupportAwarePyramidWorkspace3
  )(using Dimension[D3]): Either[
    MultiscaleError,
    SupportAwarePyramidLevel3[F, C]
  ] =
    val requiredVariance =
      targetPsf.sigmaMillimetres * targetPsf.sigmaMillimetres -
        sourcePsf.sigmaMillimetres * sourcePsf.sigmaMillimetres
    val clamped = requiredVariance < 0.0
    val addedSigma =
      if requiredVariance > 0.0 then math.sqrt(requiredVariance)
      else 0.0
    val kernel =
      GaussianKernel3.create(
        source.grid,
        addedSigma,
        truncation
      )
    for
      filtered <- filterNative(data, nativeSupport, kernel)
      numerator <- sampleOnLevel(
        source,
        filtered.numerator,
        level.grid,
        workspace
      )
      backing <- sampleOnLevel(
        source,
        filtered.support,
        level.grid,
        workspace
      )
      normalized <- normalize(numerator, backing, minimumSupport)
      image <- Sampled
        .continuous(
          level.grid,
          NonSpatialAxes.empty,
          normalized.image
        )
        .left
        .map(MultiscaleError.Image.apply)
      support <- Sampled
        .continuous(
          level.grid,
          NonSpatialAxes.empty,
          normalized.support
        )
        .left
        .map(MultiscaleError.Image.apply)
    yield
      val materializations =
        Vector(
          PreparationMaterialization3.NativeWeightedValues,
          PreparationMaterialization3.NativeSupport
        ) ++
          (if kernel.tapCount > 1 then
             Vector(
               PreparationMaterialization3.PhysicalGaussianConvolution
             )
           else Vector.empty) ++
          (if level.grid eq source.grid then Vector.empty
           else
             Vector(
               PreparationMaterialization3.AffineLevelSampling
             )) ++
          Vector(PreparationMaterialization3.SupportNormalization)
      val provenance =
        SupportAwareLevelProvenance3(
          sourcePsf,
          targetPsf,
          addedSigma,
          clamped,
          PhysicalGaussianPolicy3.NativeGridFullAffine,
          SupportPropagationPolicy3.ZeroPaddedNormalizedConvolution,
          truncation,
          kernel.tapCount,
          builtDirectlyFromNative = true,
          materializations,
          nativeCount,
          normalized.positiveSupportCount,
          normalized.fullSupportCount
        )
      new SupportAwarePyramidLevel3(
        level,
        image,
        support,
        provenance
      )

  private def sampleOnLevel[
      F <: Frame[D3]
  ](
      source: ContinuousImage[
        ? <: SampleSpace[F, D3],
        Double,
        ? <: AnyRank
      ],
      data: NDArray[Double, Rank[3]],
      target: Grid[F, D3],
      workspace: SupportAwarePyramidWorkspace3
  )(using Dimension[D3]): Either[
    MultiscaleError,
    NDArray[Double, Rank[3]]
  ] =
    if target eq source.grid then Right(data)
    else
      for
        native <- Sampled
          .continuous(source.grid, NonSpatialAxes.empty, data)
          .left
          .map(MultiscaleError.Image.apply)
        plan <- ResamplingPlan
          .affine(
            native,
            target,
            AffineMap.identity(source.frame),
            Interpolation.Linear,
            BoundaryPolicy.Constant(0.0)
          )
          .left
          .map(MultiscaleError.Resampling.apply)
        result <- plan
          .run(workspace.resampling)
          .left
          .map(MultiscaleError.Resampling.apply)
        rankThree <- result.image.data
          .requireRank[3]
          .left
          .map(_ =>
            MultiscaleError.UnsupportedScalarRank(
              result.image.data.rank
            )
          )
      yield rankThree

  private final class FilteredNative(
      val numerator: NDArray[Double, Rank[3]],
      val support: NDArray[Double, Rank[3]]
  )

  private def filterNative(
      source: NDArray[Double, Rank[3]],
      nativeSupport: NDArray[Double, Rank[3]],
      kernel: GaussianKernel3
  ): Either[MultiscaleError, FilteredNative] =
    val nx = source.shape(0)
    val ny = source.shape(1)
    val nz = source.shape(2)
    var completedNumerator =
      Option.empty[NDArray[Double, Rank[3]]]
    val support =
      NDArray.build[Double, Rank[3]](Shape(nx, ny, nz)) {
        supportBuilder =>
          val numerator =
            NDArray.build[Double, Rank[3]](Shape(nx, ny, nz)) {
              numeratorBuilder =>
                var i = 0
                while i < nx do
                  var j = 0
                  while j < ny do
                    var k = 0
                    while k < nz do
                      var numeratorSum = 0.0
                      var supportSum = 0.0
                      var tapIndex = 0
                      while tapIndex < kernel.taps.size do
                        val tap = kernel.taps(tapIndex)
                        val sourceI = i + tap.i
                        val sourceJ = j + tap.j
                        val sourceK = k + tap.k
                        if
                          sourceI >= 0 && sourceI < nx &&
                            sourceJ >= 0 && sourceJ < ny &&
                            sourceK >= 0 && sourceK < nz
                        then
                          val observed =
                            nativeSupport(sourceI, sourceJ, sourceK)
                          val weighted = tap.weight * observed
                          supportSum += weighted
                          if observed > 0.0 then
                            numeratorSum +=
                              weighted *
                                source(sourceI, sourceJ, sourceK)
                        tapIndex += 1
                      val linear = (i * ny + j) * nz + k
                      numeratorBuilder.writeLinear(
                        linear,
                        numeratorSum
                      )
                      supportBuilder.writeLinear(linear, supportSum)
                      k += 1
                    j += 1
                  i += 1
            }
          completedNumerator = Some(numerator)
      }
    completedNumerator
      .map(numerator => new FilteredNative(numerator, support))
      .toRight(MultiscaleError.PyramidBuilderProtocolViolation)

  private final class NormalizedLevel(
      val image: NDArray[Double, Rank[3]],
      val support: NDArray[Double, Rank[3]],
      val positiveSupportCount: Long,
      val fullSupportCount: Long
  )

  private def normalize(
      numerator: NDArray[Double, Rank[3]],
      backing: NDArray[Double, Rank[3]],
      minimumSupport: Double
  ): Either[MultiscaleError, NormalizedLevel] =
    val nx = numerator.shape(0)
    val ny = numerator.shape(1)
    val nz = numerator.shape(2)
    validatePreparedArrays(numerator, backing).flatMap { _ =>
      var positive = 0L
      var full = 0L
      var completedImage = Option.empty[NDArray[Double, Rank[3]]]
      val support =
        NDArray.build[Double, Rank[3]](Shape(nx, ny, nz)) {
          supportBuilder =>
            val image =
              NDArray.build[Double, Rank[3]](Shape(nx, ny, nz)) {
                imageBuilder =>
                  var i = 0
                  while i < nx do
                    var j = 0
                    while j < ny do
                      var k = 0
                      while k < nz do
                        val linear = (i * ny + j) * nz + k
                        val rawSupport = backing(i, j, k)
                        val boundedSupport =
                          math.max(0.0, math.min(1.0, rawSupport))
                        if boundedSupport >= minimumSupport then
                          val value =
                            numerator(i, j, k) / boundedSupport
                          imageBuilder.writeLinear(linear, value)
                          supportBuilder.writeLinear(
                            linear,
                            boundedSupport
                          )
                          positive += 1L
                          if boundedSupport >= 1.0 - 1e-12 then
                            full += 1L
                        else
                          imageBuilder.writeLinear(linear, 0.0)
                          supportBuilder.writeLinear(linear, 0.0)
                        k += 1
                      j += 1
                    i += 1
              }
            completedImage = Some(image)
        }
      completedImage
        .map(image => new NormalizedLevel(image, support, positive, full))
        .toRight(MultiscaleError.PyramidBuilderProtocolViolation)
    }

  private def validatePreparedArrays(
      numerator: NDArray[Double, Rank[3]],
      support: NDArray[Double, Rank[3]]
  ): Either[MultiscaleError, Unit] =
    val tolerance = 1e-12
    val nx = numerator.shape(0)
    val ny = numerator.shape(1)
    val nz = numerator.shape(2)
    var failure = Option.empty[MultiscaleError]
    var i = 0
    while i < nx && failure.isEmpty do
      var j = 0
      while j < ny && failure.isEmpty do
        var k = 0
        while k < nz && failure.isEmpty do
          val linear = (i * ny + j) * nz + k
          val weight = support(i, j, k)
          val value = numerator(i, j, k)
          if
            !weight.isFinite || weight < -tolerance ||
              weight > 1.0 + tolerance
          then
            failure = Some(
              MultiscaleError.InvalidPreparedSupport(linear, weight)
            )
          else if !value.isFinite then
            failure = Some(
              MultiscaleError.NonFinitePreparedNumerator(linear, value)
            )
          k += 1
        j += 1
      i += 1
    failure.toLeft(())

  private final case class GaussianTap3(
      i: Int,
      j: Int,
      k: Int,
      weight: Double
  )

  private final class GaussianKernel3(
      val taps: Vector[GaussianTap3]
  ):
    val tapCount: Int = taps.size

  private object GaussianKernel3:
    def create[F <: Frame[D3]](
        grid: Grid[F, D3],
        sigmaMillimetres: Double,
        truncation: Double
    ): GaussianKernel3 =
      if sigmaMillimetres <= 1e-12 then
        new GaussianKernel3(Vector(GaussianTap3(0, 0, 0, 1.0)))
      else
        val forward = grid.indexToFrame.rowMajor
        val inverse = grid.indexToFrame.inverse.rowMajor
        val physicalRadius = truncation * sigmaMillimetres
        val radiusSquared = physicalRadius * physicalRadius
        val radii =
          Vector.tabulate(3) { row =>
            var squared = 0.0
            var column = 0
            while column < 3 do
              val value = inverse(row * 4 + column)
              squared += value * value
              column += 1
            math.ceil(physicalRadius * math.sqrt(squared)).toInt
          }
        val unnormalized = Vector.newBuilder[GaussianTap3]
        var total = 0.0
        var i = -radii(0)
        while i <= radii(0) do
          var j = -radii(1)
          while j <= radii(1) do
            var k = -radii(2)
            while k <= radii(2) do
              val x =
                forward(0) * i + forward(1) * j + forward(2) * k
              val y =
                forward(4) * i + forward(5) * j + forward(6) * k
              val z =
                forward(8) * i + forward(9) * j + forward(10) * k
              val squared = x * x + y * y + z * z
              if squared <= radiusSquared + 1e-12 then
                val weight =
                  math.exp(
                    -0.5 * squared /
                      (sigmaMillimetres * sigmaMillimetres)
                  )
                unnormalized += GaussianTap3(i, j, k, weight)
                total += weight
              k += 1
            j += 1
          i += 1
        val normalized =
          unnormalized.result().map(tap =>
            tap.copy(weight = tap.weight / total)
          )
        new GaussianKernel3(normalized)
