package reframe4s.halfflow

import reframe4s.halfflow.internal.*

opaque type FeatureRadiusMm = Double

object FeatureRadiusMm:
  def from(value: Double): Either[RegistrationError, FeatureRadiusMm] =
    if value.isFinite && value > 0.0 then Right(value)
    else Left(RegistrationError.InvalidFlowParameter("feature radius mm", value))

  def apply(value: Double): FeatureRadiusMm =
    from(value).fold(error => throw new IllegalArgumentException(error.message), identity)

  extension (value: FeatureRadiusMm)
    inline def toDouble: Double = value

final case class T1FeatureConfig private (
    radii: Vector[FeatureRadiusMm],
    epsilonScaleFraction: Double,
    minimumEpsilon: Double,
    minimumValidWindowFraction: Double,
    minimumActiveVoxels: Int
)

object T1FeatureConfig:
  def make(
      radii: Vector[FeatureRadiusMm],
      epsilonScaleFraction: Double = 0.05,
      minimumEpsilon: Double = 1e-6,
      minimumValidWindowFraction: Double = 0.80,
      minimumActiveVoxels: Int = 64
  ): Either[RegistrationError, T1FeatureConfig] =
    if radii.isEmpty then Left(RegistrationError.InvalidConfiguration("T1 feature radii are empty"))
    else if !epsilonScaleFraction.isFinite || epsilonScaleFraction <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("T1 feature epsilon scale fraction"))
    else if !minimumEpsilon.isFinite || minimumEpsilon <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("T1 feature minimum epsilon"))
    else if
      !minimumValidWindowFraction.isFinite ||
        minimumValidWindowFraction <= 0.0 || minimumValidWindowFraction > 1.0
    then Left(RegistrationError.InvalidConfiguration("T1 feature valid-window fraction"))
    else if minimumActiveVoxels <= 0 then
      Left(RegistrationError.InvalidConfiguration("T1 feature minimum active voxels"))
    else
      Right(
        new T1FeatureConfig(
          radii,
          epsilonScaleFraction,
          minimumEpsilon,
          minimumValidWindowFraction,
          minimumActiveVoxels
        )
      )

  val default: T1FeatureConfig =
    make(Vector(FeatureRadiusMm(2.0), FeatureRadiusMm(4.0), FeatureRadiusMm(8.0)))
      .fold(error => throw new IllegalStateException(error.message), identity)

final case class T1FeatureVolume[A] private (
    frame: RegistrationFrame[A],
    radiiMm: Vector[Double],
    radiiVox: Vector[VoxelWindowRadius],
    epsilonPerChannel: Vector[Double],
    values: Array[Double],
    gradients: Array[Double],
    valid: Array[Boolean]
):
  val channels: Int = radiiVox.length

object T1FeatureVolume:
  def make[A](
      frame: RegistrationFrame[A],
      radiiMm: Vector[Double],
      radiiVox: Vector[VoxelWindowRadius],
      epsilonPerChannel: Vector[Double],
      values: Array[Double],
      gradients: Array[Double],
      valid: Array[Boolean]
  ): Either[RegistrationError, T1FeatureVolume[A]] =
    val channels = radiiVox.length
    val scalarSize = frame.grid.nVoxels * channels
    if channels == 0 || radiiMm.length != channels || epsilonPerChannel.length != channels then
      Left(RegistrationError.InvalidField("T1 feature metadata"))
    else if values.length != scalarSize || gradients.length != scalarSize * 3 || valid.length != scalarSize then
      Left(RegistrationError.InvalidField("T1 feature storage"))
    else if !finiteWhereValid(values, gradients, valid, frame.grid.nVoxels, channels) then
      Left(RegistrationError.InvalidField("T1 feature values"))
    else Right(new T1FeatureVolume(frame, radiiMm, radiiVox, epsilonPerChannel, values, gradients, valid))

  private def finiteWhereValid(
      values: Array[Double],
      gradients: Array[Double],
      valid: Array[Boolean],
      n: Int,
      channels: Int
  ): Boolean =
    var finite = true
    var channel = 0
    while channel < channels && finite do
      var index = 0
      while index < n && finite do
        val scalar = channel * n + index
        val gradient = channel * 3 * n
        if valid(scalar) then
          finite =
            values(scalar).isFinite && gradients(gradient + index).isFinite &&
              gradients(gradient + n + index).isFinite && gradients(gradient + 2 * n + index).isFinite
        index += 1
      channel += 1
    finite

final class T1FeatureBuffer[A] private (
    val frame: RegistrationFrame[A],
    val radiiMm: Vector[Double],
    val radiiVox: Vector[VoxelWindowRadius],
    private[halfflow] val values: Array[Double],
    private[halfflow] val gradients: Array[Double],
    private[halfflow] val valid: Array[Boolean],
    private[halfflow] val normalizedValid: Array[Boolean]
):
  val channels: Int = radiiVox.length
  val ownedValueBuffers: Int = 2
  val ownedValidityBuffers: Int = 2

object T1FeatureBuffer:
  def apply[A](frame: RegistrationFrame[A], config: T1FeatureConfig): T1FeatureBuffer[A] =
    val radiiVox = T1Features.voxelRadii(frame.grid, config.radii)
    val scalarSize = frame.grid.nVoxels * config.radii.length
    new T1FeatureBuffer(
      frame,
      config.radii.map(_.toDouble),
      radiiVox,
      PrimitiveBuffers.ofSize[Double](scalarSize),
      PrimitiveBuffers.ofSize[Double](scalarSize * 3),
      PrimitiveBuffers.ofSize[Boolean](scalarSize),
      PrimitiveBuffers.ofSize[Boolean](scalarSize)
    )

final class T1FeatureWorkspace[A] private (
    val frame: RegistrationFrame[A],
    val channels: Int,
    private[halfflow] val localStats: MaskedLocalStatsWorkspace,
    private[halfflow] val robustScratch: Array[Double]
):
  val ownedScalarBuffers: Int = localStats.ownedScalarBuffers + 1

object T1FeatureWorkspace:
  def apply[A](frame: RegistrationFrame[A], channels: Int): T1FeatureWorkspace[A] =
    require(channels > 0, "T1 feature workspace channel count must be positive")
    new T1FeatureWorkspace(
      frame,
      channels,
      MaskedLocalStatsWorkspace(frame.grid),
      new Array[Double](frame.grid.nVoxels)
    )

object T1Features:
  def compute[A](
      frame: RegistrationFrame[A],
      source: Array[Double],
      sourceValidity: FieldValidity,
      config: T1FeatureConfig = T1FeatureConfig.default
  ): Either[RegistrationError, T1FeatureVolume[A]] =
    computeWith(frame, source, sourceValidity, config, T1FeatureWorkspace(frame, config.radii.length))

  def computeWith[A](
      frame: RegistrationFrame[A],
      source: Array[Double],
      sourceValidity: FieldValidity,
      config: T1FeatureConfig,
      workspace: T1FeatureWorkspace[A]
  ): Either[RegistrationError, T1FeatureVolume[A]] =
    computeInto(frame, source, sourceValidity, config, workspace, T1FeatureBuffer(frame, config))

  /** Writes through caller-owned output and scratch buffers.
    *
    * The returned feature volume borrows `destination` until its next reuse.
    */
  def computeInto[A](
      frame: RegistrationFrame[A],
      source: Array[Double],
      sourceValidity: FieldValidity,
      config: T1FeatureConfig,
      workspace: T1FeatureWorkspace[A],
      destination: T1FeatureBuffer[A]
  ): Either[RegistrationError, T1FeatureVolume[A]] =
    val grid = frame.grid
    if workspace.frame.domain != frame.domain then
      Left(RegistrationError.FrameMismatch("T1 feature workspace", frame.domain, workspace.frame.domain))
    else if workspace.frame.grid != grid || workspace.channels != config.radii.length then
      Left(RegistrationError.GridMismatch("T1 feature workspace"))
    else if destination.frame.domain != frame.domain then
      Left(RegistrationError.FrameMismatch("T1 feature destination", frame.domain, destination.frame.domain))
    else if
      destination.frame.grid != grid || destination.channels != config.radii.length ||
        destination.radiiVox != voxelRadii(grid, config.radii)
    then Left(RegistrationError.GridMismatch("T1 feature destination"))
    else if source.length != grid.nVoxels then Left(RegistrationError.InvalidField("T1 source length"))
    else
      val scale = robustScale(source, sourceValidity, workspace.robustScratch)
      if !scale.isFinite then Left(RegistrationError.InvalidField("T1 robust intensity scale"))
      else
        val epsilon = math.max(config.minimumEpsilon, config.epsilonScaleFraction * scale)
        val epsilons = Vector.fill(config.radii.length)(epsilon)
        MaskedLocalStats.normalizeChannelsInto(
          source,
          sourceValidity,
          grid,
          destination.radiiVox,
          epsilons,
          config.minimumValidWindowFraction,
          destination.values,
          destination.normalizedValid,
          workspace.localStats
        )
        val summary = MaskedLocalStats.physicalGradientChannelsInto(
          destination.values,
          destination.normalizedValid,
          grid,
          config.radii.length,
          destination.gradients,
          destination.valid,
          workspace.localStats
        )
        val minimum = summary.validPerChannel.min
        if minimum < config.minimumActiveVoxels then
          Left(RegistrationError.InsufficientSupport("T1 feature", minimum, config.minimumActiveVoxels))
        else
          T1FeatureVolume.make(
            frame,
            destination.radiiMm,
            destination.radiiVox,
            epsilons,
            destination.values,
            destination.gradients,
            destination.valid
          )

  private[halfflow] def voxelRadii(
      grid: GridSpec,
      radii: Vector[FeatureRadiusMm]
  ): Vector[VoxelWindowRadius] =
    val spacing = grid.spacing
    radii.map { radius =>
      VoxelWindowRadius(
        math.max(1, math.ceil(radius.toDouble / spacing(0)).toInt),
        math.max(1, math.ceil(radius.toDouble / spacing(1)).toInt),
        math.max(1, math.ceil(radius.toDouble / spacing(2)).toInt)
      )
    }

  private def robustScale(
      source: Array[Double],
      validity: FieldValidity,
      scratch: Array[Double]
  ): Double =
    var count = 0
    var index = 0
    while index < source.length do
      if validityAt(validity, index) && source(index).isFinite then
        scratch(count) = source(index)
        count += 1
      index += 1
    if count < 2 then Double.NaN
    else
      val median = InPlaceMedian(scratch, count)
      index = 0
      while index < count do
        scratch(index) = math.abs(scratch(index) - median)
        index += 1
      val mad = InPlaceMedian(scratch, count)
      math.max(math.ulp(math.abs(median) + 1.0), 1.4826 * mad)

  private def validityAt(validity: FieldValidity, index: Int): Boolean =
    validity match
      case FieldValidity.All => true
      case FieldValidity.Mask(values) => values(index)
