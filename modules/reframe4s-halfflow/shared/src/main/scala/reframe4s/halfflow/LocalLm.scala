package reframe4s.halfflow

import reframe4s.halfflow.internal.*

enum LocalLmVariant:
  case RankOne(channel: Int)
  case MultiChannel

final case class LocalLmConfig private (
    damping: Double,
    robustScaleFraction: Double,
    minimumRobustEpsilon: Double,
    pivotFloor: Double,
    maximumFailedFraction: Double,
    minimumActiveVoxels: Int,
    variant: LocalLmVariant
):
  def withDamping(value: Double): Either[RegistrationError, LocalLmConfig] =
    LocalLmConfig.make(
      value,
      robustScaleFraction,
      minimumRobustEpsilon,
      pivotFloor,
      maximumFailedFraction,
      minimumActiveVoxels,
      variant
    )

object LocalLmConfig:
  def make(
      damping: Double = 1e-2,
      robustScaleFraction: Double = 0.10,
      minimumRobustEpsilon: Double = 1e-4,
      pivotFloor: Double = 1e-10,
      maximumFailedFraction: Double = 0.01,
      minimumActiveVoxels: Int = 64,
      variant: LocalLmVariant = LocalLmVariant.MultiChannel
  ): Either[RegistrationError, LocalLmConfig] =
    if !damping.isFinite || damping <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("local LM damping"))
    else if !robustScaleFraction.isFinite || robustScaleFraction <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("local LM robust scale fraction"))
    else if !minimumRobustEpsilon.isFinite || minimumRobustEpsilon <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("local LM minimum robust epsilon"))
    else if !pivotFloor.isFinite || pivotFloor <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("local LM pivot floor"))
    else if !maximumFailedFraction.isFinite || maximumFailedFraction < 0.0 || maximumFailedFraction >= 1.0 then
      Left(RegistrationError.InvalidConfiguration("local LM maximum failed fraction"))
    else if minimumActiveVoxels <= 0 then
      Left(RegistrationError.InvalidConfiguration("local LM minimum active voxels"))
    else
      Right(
        new LocalLmConfig(
          damping,
          robustScaleFraction,
          minimumRobustEpsilon,
          pivotFloor,
          maximumFailedFraction,
          minimumActiveVoxels,
          variant
        )
      )

  val default: LocalLmConfig =
    make().fold(error => throw new IllegalStateException(error.message), identity)

final case class LocalLmModel(
    variant: LocalLmVariant,
    epsilonPerChannel: Vector[Double],
    activeVoxels: Int,
    value: Double
)

final case class LocalLmSummary(
    activeVoxels: Int,
    failedSolves: Int,
    value: Double,
    maximumRawVelocityMm: Double
)

final case class LocalLmResult[A](
    model: LocalLmModel,
    rawVelocity: Velocity[A],
    rawValidity: FieldValidity,
    summary: LocalLmSummary
)

final class LocalLmBuffer[A] private (
    val frame: RegistrationFrame[A],
    private[halfflow] val step: Array[Double],
    private[halfflow] val valid: Array[Boolean]
):
  val ownedVelocityBuffers: Int = 1
  val ownedValidityBuffers: Int = 1

object LocalLmBuffer:
  def apply[A](frame: RegistrationFrame[A]): LocalLmBuffer[A] =
    new LocalLmBuffer(
      frame,
      PrimitiveBuffers.ofSize[Double](frame.grid.nVoxels * 3),
      PrimitiveBuffers.ofSize[Boolean](frame.grid.nVoxels)
    )

final class LocalLmWorkspace[A] private (
    val frame: RegistrationFrame[A],
    private[halfflow] val residualScratch: Array[Double],
    private[halfflow] val channelScratch: LocalLmChannelScratch
):
  val ownedScalarBuffers: Int = 1

object LocalLmWorkspace:
  def apply[A](frame: RegistrationFrame[A]): LocalLmWorkspace[A] =
    new LocalLmWorkspace(frame, new Array[Double](frame.grid.nVoxels), new LocalLmChannelScratch())

private[halfflow] final class LocalLmChannelScratch:
  var residual: Double = 0.0
  var j0: Double = 0.0
  var j1: Double = 0.0
  var j2: Double = 0.0
  var weight: Double = 0.0
  var normSquared: Double = 0.0
  var loss: Double = 0.0

object LocalLm:
  def solve[A](
      fixed: T1FeatureVolume[A],
      moving: T1FeatureVolume[A],
      config: LocalLmConfig
  ): Either[RegistrationError, LocalLmResult[A]] =
    solveWith(fixed, moving, config, LocalLmWorkspace(fixed.frame))

  def solveWith[A](
      fixed: T1FeatureVolume[A],
      moving: T1FeatureVolume[A],
      config: LocalLmConfig,
      workspace: LocalLmWorkspace[A]
  ): Either[RegistrationError, LocalLmResult[A]] =
    solveInto(fixed, moving, config, workspace, LocalLmBuffer(fixed.frame))

  /** Writes the raw force through caller-owned storage.
    *
    * The returned velocity borrows `destination` until its next reuse.
    */
  def solveInto[A](
      fixed: T1FeatureVolume[A],
      moving: T1FeatureVolume[A],
      config: LocalLmConfig,
      workspace: LocalLmWorkspace[A],
      destination: LocalLmBuffer[A]
  ): Either[RegistrationError, LocalLmResult[A]] =
    compatible(fixed, moving).flatMap { _ =>
      if workspace.frame.domain != fixed.frame.domain then
        Left(RegistrationError.FrameMismatch("local LM workspace", fixed.frame.domain, workspace.frame.domain))
      else if workspace.frame.grid != fixed.frame.grid then Left(RegistrationError.GridMismatch("local LM workspace"))
      else if destination.frame.domain != fixed.frame.domain then
        Left(RegistrationError.FrameMismatch("local LM destination", fixed.frame.domain, destination.frame.domain))
      else if destination.frame.grid != fixed.frame.grid then Left(RegistrationError.GridMismatch("local LM destination"))
      else if !variantValid(config.variant, fixed.channels) then
        Left(RegistrationError.InvalidConfiguration("local LM rank-one channel"))
      else
        val active = countActive(fixed, moving, config.variant)
        if active < config.minimumActiveVoxels then
          Left(RegistrationError.InsufficientSupport("local LM", active, config.minimumActiveVoxels))
        else
          val epsilons = estimateEpsilons(fixed, moving, config, workspace.residualScratch)
          val summary = solveKernelInto(
            fixed,
            moving,
            config,
            epsilons,
            destination.step,
            destination.valid,
            workspace.channelScratch
          )
          if summary.failedSolves.toDouble / active.toDouble > config.maximumFailedFraction then
            Left(RegistrationError.ExcessiveSolveFailures(summary.failedSolves, active))
          else
            val field = DenseVectorField.fromLegacyPlanar(
              fixed.frame.grid,
              destination.step,
              DenseVectorFieldKind.Displacement
            )
            Velocity.make(fixed.frame, field).map { velocity =>
              LocalLmResult(
                LocalLmModel(config.variant, epsilons, active, summary.value),
                velocity,
                FieldValidity.copyMask(destination.valid),
                summary
              )
            }
    }

  /** Recomputes the undamped quadratic prediction at a shaped velocity. */
  def predictedDrop[A](
      fixed: T1FeatureVolume[A],
      moving: T1FeatureVolume[A],
      velocity: Velocity[A],
      velocityValidity: FieldValidity,
      model: LocalLmModel
  ): Either[RegistrationError, Double] =
    compatible(fixed, moving).flatMap { _ =>
      if velocity.frame.domain != fixed.frame.domain then
        Left(RegistrationError.FrameMismatch("predicted-drop velocity", fixed.frame.domain, velocity.frame.domain))
      else if velocity.frame.grid != fixed.frame.grid then Left(RegistrationError.GridMismatch("predicted-drop velocity"))
      else if !variantValid(model.variant, fixed.channels) || model.epsilonPerChannel.length != fixed.channels then
        Left(RegistrationError.InvalidConfiguration("predicted-drop local model"))
      else
        val result = predictedDropUnsafe(fixed, moving, velocity, velocityValidity, model)
        if result._2 != model.activeVoxels then
          Left(RegistrationError.InsufficientSupport("predicted-drop", result._2, model.activeVoxels))
        else if !result._1.isFinite then Left(RegistrationError.InvalidField("predicted-drop value"))
        else Right(result._1)
    }

  /** Evaluates a candidate on the current model's fixed active support. */
  def candidateValue[A](
      referenceFixed: T1FeatureVolume[A],
      referenceMoving: T1FeatureVolume[A],
      candidateFixed: T1FeatureVolume[A],
      candidateMoving: T1FeatureVolume[A],
      model: LocalLmModel
  ): Either[RegistrationError, Double] =
    for
      _ <- compatible(referenceFixed, referenceMoving)
      _ <- compatible(referenceFixed, candidateFixed)
      _ <- compatible(referenceFixed, candidateMoving)
      result <-
        if !variantValid(model.variant, referenceFixed.channels) ||
          model.epsilonPerChannel.length != referenceFixed.channels
        then Left(RegistrationError.InvalidConfiguration("candidate local model"))
        else evaluateCandidate(referenceFixed, referenceMoving, candidateFixed, candidateMoving, model)
    yield result

  private def evaluateCandidate[A](
      referenceFixed: T1FeatureVolume[A],
      referenceMoving: T1FeatureVolume[A],
      candidateFixed: T1FeatureVolume[A],
      candidateMoving: T1FeatureVolume[A],
      model: LocalLmModel
  ): Either[RegistrationError, Double] =
    val n = referenceFixed.frame.grid.nVoxels
    var loss = 0.0
    var used = 0
    var index = 0
    var missing = false
    while index < n && !missing do
      if activeAt(referenceFixed, referenceMoving, model.variant, index) then
        used += 1
        model.variant match
          case LocalLmVariant.RankOne(channel) =>
            val scalar = channel * n + index
            if !candidateFixed.valid(scalar) || !candidateMoving.valid(scalar) then missing = true
            else
              val residual = candidateFixed.values(scalar) - candidateMoving.values(scalar)
              val epsilon = model.epsilonPerChannel(channel)
              loss += math.sqrt(residual * residual + epsilon * epsilon) - epsilon
          case LocalLmVariant.MultiChannel =>
            var channel = 0
            while channel < referenceFixed.channels && !missing do
              val scalar = channel * n + index
              if !candidateFixed.valid(scalar) || !candidateMoving.valid(scalar) then missing = true
              else
                val residual = candidateFixed.values(scalar) - candidateMoving.values(scalar)
                val epsilon = model.epsilonPerChannel(channel)
                loss += math.sqrt(residual * residual + epsilon * epsilon) - epsilon
              channel += 1
      index += 1
    if missing then Left(RegistrationError.InsufficientSupport("candidate objective", used - 1, model.activeVoxels))
    else if used != model.activeVoxels then
      Left(RegistrationError.InsufficientSupport("candidate objective", used, model.activeVoxels))
    else if !loss.isFinite then Left(RegistrationError.InvalidField("candidate objective"))
    else Right(loss / used.toDouble)

  private def solveKernelInto[A](
      fixed: T1FeatureVolume[A],
      moving: T1FeatureVolume[A],
      config: LocalLmConfig,
      epsilons: Vector[Double],
      destination: Array[Double],
      destinationValid: Array[Boolean],
      terms: LocalLmChannelScratch
  ): LocalLmSummary =
    val n = fixed.frame.grid.nVoxels
    var active = 0
    var failed = 0
    var energy = 0.0
    var maximum = 0.0
    var index = 0
    while index < n do
      destination(index) = 0.0
      destination(index + n) = 0.0
      destination(index + 2 * n) = 0.0
      destinationValid(index) = false
      if activeAt(fixed, moving, config.variant, index) then
        active += 1
        destinationValid(index) = true
        config.variant match
          case LocalLmVariant.RankOne(channel) =>
            channelTermsInto(fixed, moving, channel, index, epsilons(channel), terms)
            energy += terms.loss
            val denominator = config.damping + terms.weight * terms.normSquared
            if denominator.isFinite && denominator > config.pivotFloor then
              val scale = -terms.weight * terms.residual / denominator
              val x = scale * terms.j0
              val y = scale * terms.j1
              val z = scale * terms.j2
              destination(index) = x
              destination(index + n) = y
              destination(index + 2 * n) = z
              maximum = math.max(maximum, math.sqrt(x * x + y * y + z * z))
            else failed += 1
          case LocalLmVariant.MultiChannel =>
            var h00 = 0.0
            var h01 = 0.0
            var h02 = 0.0
            var h11 = 0.0
            var h12 = 0.0
            var h22 = 0.0
            var b0 = 0.0
            var b1 = 0.0
            var b2 = 0.0
            var channel = 0
            while channel < fixed.channels do
              channelTermsInto(fixed, moving, channel, index, epsilons(channel), terms)
              energy += terms.loss
              h00 += terms.weight * terms.j0 * terms.j0
              h01 += terms.weight * terms.j0 * terms.j1
              h02 += terms.weight * terms.j0 * terms.j2
              h11 += terms.weight * terms.j1 * terms.j1
              h12 += terms.weight * terms.j1 * terms.j2
              h22 += terms.weight * terms.j2 * terms.j2
              b0 += terms.weight * terms.j0 * terms.residual
              b1 += terms.weight * terms.j1 * terms.residual
              b2 += terms.weight * terms.j2 * terms.residual
              channel += 1
            val a00 = h00 + config.damping
            val a11 = h11 + config.damping
            val a22 = h22 + config.damping
            val l00 = math.sqrt(a00)
            val l10 = h01 / l00
            val l20 = h02 / l00
            val p11 = a11 - l10 * l10
            val l11 = math.sqrt(p11)
            val l21 = (h12 - l20 * l10) / l11
            val p22 = a22 - l20 * l20 - l21 * l21
            val l22 = math.sqrt(p22)
            val pivotsOk =
              l00.isFinite && l11.isFinite && l22.isFinite &&
                l00 > config.pivotFloor && l11 > config.pivotFloor && l22 > config.pivotFloor
            if pivotsOk then
              val y0 = -b0 / l00
              val y1 = (-b1 - l10 * y0) / l11
              val y2 = (-b2 - l20 * y0 - l21 * y1) / l22
              val z = y2 / l22
              val y = (y1 - l21 * z) / l11
              val x = (y0 - l10 * y - l20 * z) / l00
              if x.isFinite && y.isFinite && z.isFinite then
                destination(index) = x
                destination(index + n) = y
                destination(index + 2 * n) = z
                maximum = math.max(maximum, math.sqrt(x * x + y * y + z * z))
              else failed += 1
            else failed += 1
      index += 1
    LocalLmSummary(active, failed, energy / active.toDouble, maximum)

  private def predictedDropUnsafe[A](
      fixed: T1FeatureVolume[A],
      moving: T1FeatureVolume[A],
      velocity: Velocity[A],
      velocityValidity: FieldValidity,
    model: LocalLmModel
  ): (Double, Int) =
    val n = fixed.frame.grid.nVoxels
    val v = velocity.field
    val terms = new LocalLmChannelScratch()
    var modeledChange = 0.0
    var used = 0
    var index = 0
    while index < n do
      if activeAt(fixed, moving, model.variant, index) && validityAt(velocityValidity, index) then
        used += 1
        val x = v.linearComponent(index, 0)
        val y = v.linearComponent(index, 1)
        val z = v.linearComponent(index, 2)
        model.variant match
          case LocalLmVariant.RankOne(channel) =>
            channelTermsInto(fixed, moving, channel, index, model.epsilonPerChannel(channel), terms)
            val projection = terms.j0 * x + terms.j1 * y + terms.j2 * z
            modeledChange += terms.weight * terms.residual * projection + 0.5 * terms.weight * projection * projection
          case LocalLmVariant.MultiChannel =>
            var channel = 0
            while channel < fixed.channels do
              channelTermsInto(fixed, moving, channel, index, model.epsilonPerChannel(channel), terms)
              val projection = terms.j0 * x + terms.j1 * y + terms.j2 * z
              modeledChange += terms.weight * terms.residual * projection +
                0.5 * terms.weight * projection * projection
              channel += 1
      index += 1
    (-modeledChange / model.activeVoxels.toDouble, used)

  private def channelTermsInto[A](
      fixed: T1FeatureVolume[A],
      moving: T1FeatureVolume[A],
      channel: Int,
      index: Int,
      epsilon: Double,
      destination: LocalLmChannelScratch
  ): Unit =
    val n = fixed.frame.grid.nVoxels
    val scalar = channel * n + index
    val gradient = channel * 3 * n
    val residual = fixed.values(scalar) - moving.values(scalar)
    val j0 = 0.5 * (fixed.gradients(gradient + index) + moving.gradients(gradient + index))
    val j1 = 0.5 * (fixed.gradients(gradient + n + index) + moving.gradients(gradient + n + index))
    val j2 = 0.5 * (fixed.gradients(gradient + 2 * n + index) + moving.gradients(gradient + 2 * n + index))
    val root = math.sqrt(residual * residual + epsilon * epsilon)
    destination.residual = residual
    destination.j0 = j0
    destination.j1 = j1
    destination.j2 = j2
    destination.weight = 1.0 / root
    destination.normSquared = j0 * j0 + j1 * j1 + j2 * j2
    destination.loss = root - epsilon

  private def estimateEpsilons[A](
      fixed: T1FeatureVolume[A],
      moving: T1FeatureVolume[A],
      config: LocalLmConfig,
      scratch: Array[Double]
  ): Vector[Double] =
    Vector.tabulate(fixed.channels) { channel =>
      if config.variant == LocalLmVariant.RankOne(channel) || config.variant == LocalLmVariant.MultiChannel then
        var count = 0
        var index = 0
        while index < fixed.frame.grid.nVoxels do
          if activeAt(fixed, moving, config.variant, index) then
            scratch(count) = math.abs(fixed.values(channel * fixed.frame.grid.nVoxels + index) - moving.values(channel * fixed.frame.grid.nVoxels + index))
            count += 1
          index += 1
        math.max(config.minimumRobustEpsilon, config.robustScaleFraction * InPlaceMedian(scratch, count))
      else config.minimumRobustEpsilon
    }

  private def countActive[A](fixed: T1FeatureVolume[A], moving: T1FeatureVolume[A], variant: LocalLmVariant): Int =
    var count = 0
    var index = 0
    while index < fixed.frame.grid.nVoxels do
      if activeAt(fixed, moving, variant, index) then count += 1
      index += 1
    count

  private def activeAt[A](
      fixed: T1FeatureVolume[A],
      moving: T1FeatureVolume[A],
      variant: LocalLmVariant,
      index: Int
  ): Boolean =
    val n = fixed.frame.grid.nVoxels
    variant match
      case LocalLmVariant.RankOne(channel) => fixed.valid(channel * n + index) && moving.valid(channel * n + index)
      case LocalLmVariant.MultiChannel =>
        var active = true
        var channel = 0
        while channel < fixed.channels && active do
          active = fixed.valid(channel * n + index) && moving.valid(channel * n + index)
          channel += 1
        active

  private def compatible[A](
      fixed: T1FeatureVolume[A],
      moving: T1FeatureVolume[A]
  ): Either[RegistrationError, Unit] =
    if fixed.frame.domain != moving.frame.domain then
      Left(RegistrationError.FrameMismatch("local LM features", fixed.frame.domain, moving.frame.domain))
    else if fixed.frame.grid != moving.frame.grid then Left(RegistrationError.GridMismatch("local LM features"))
    else if fixed.channels != moving.channels || fixed.radiiVox != moving.radiiVox then
      Left(RegistrationError.InvalidField("local LM feature compatibility"))
    else Right(())

  private def variantValid(variant: LocalLmVariant, channels: Int): Boolean =
    variant match
      case LocalLmVariant.RankOne(channel) => channel >= 0 && channel < channels
      case LocalLmVariant.MultiChannel => true

  private def validityAt(validity: FieldValidity, index: Int): Boolean =
    validity match
      case FieldValidity.All => true
      case FieldValidity.Mask(values) => values(index)
