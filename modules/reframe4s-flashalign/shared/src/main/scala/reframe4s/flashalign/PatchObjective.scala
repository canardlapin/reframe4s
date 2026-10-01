package reframe4s.flashalign

private[flashalign] enum PatchObjectiveParameter derives CanEqual:
  case PositivePolarityPrior
  case Tau
  case OutlierFloor
  case MinimumContrastEnergy
  case CorrelationRoundingTolerance

private[flashalign] enum PatchInvalidReason derives CanEqual:
  case IncompleteInterpolationSupport
  case InsufficientContrast

private[flashalign] sealed trait PatchObjectiveError derives CanEqual:
  def message: String

private[flashalign] object PatchObjectiveError:
  final case class InvalidClosedProbability(
      parameter: PatchObjectiveParameter,
      value: Double
  ) extends PatchObjectiveError:
    val message: String = s"$parameter must be finite and within [0, 1], got $value"

  final case class InvalidOpenProbability(
      parameter: PatchObjectiveParameter,
      value: Double
  ) extends PatchObjectiveError:
    val message: String = s"$parameter must be finite and within (0, 1), got $value"

  final case class InvalidPositive(
      parameter: PatchObjectiveParameter,
      value: Double
  ) extends PatchObjectiveError:
    val message: String = s"$parameter must be finite and positive, got $value"

  final case class InvalidRoundingTolerance(value: Double)
      extends PatchObjectiveError:
    val message: String =
      s"correlation rounding tolerance must be finite and within [0, ${PatchObjectiveConfig.MaximumRoundingTolerance}], got $value"

  final case class InsufficientSamples(actual: Int, minimum: Int)
      extends PatchObjectiveError:
    val message: String =
      s"normalized patch needs at least $minimum samples, got $actual"

  final case class SampleCountMismatch(expected: Int, actual: Int)
      extends PatchObjectiveError:
    val message: String =
      s"fixed patch has $actual samples but moving patch has $expected"

  final case class InsufficientContrast(energy: Double, minimum: Double)
      extends PatchObjectiveError:
    val message: String =
      s"moving patch contrast energy $energy does not exceed $minimum"

  final case class NonFiniteSample(index: Int, value: Double)
      extends PatchObjectiveError:
    val message: String = s"patch sample $index must be finite, got $value"

  final case class CorrelationOutOfRange(value: Double, tolerance: Double)
      extends PatchObjectiveError:
    val message: String =
      s"patch correlation $value exceeds [-1, 1] beyond tolerance $tolerance"

  final case class InvalidLoss(correlation: Double, value: Double)
      extends PatchObjectiveError:
    val message: String =
      s"patch loss must be finite and non-negative at correlation $correlation, got $value"

  final case class InvalidLinearizationShape(
      parameterCount: Int,
      directionCount: Int,
      curvatureCount: Int
  ) extends PatchObjectiveError:
    val message: String =
      s"$parameterCount parameters require $parameterCount direction and ${parameterCount * parameterCount} curvature values, got $directionCount and $curvatureCount"

  final case class NonFiniteLinearization(
      component: String,
      index: Int,
      value: Double
  ) extends PatchObjectiveError:
    val message: String =
      s"patch $component component $index must be finite, got $value"

private[flashalign] final class PatchObjectiveConfig private (
    val positivePolarityPrior: Double,
    val tau: Double,
    val outlierFloor: Double,
    val minimumContrastEnergy: Double,
    val correlationRoundingTolerance: Double
):
  val tauSquared: Double = tau * tau
  val outlierCost: Double = -tauSquared * math.log(outlierFloor)
  private[flashalign] val logOutlier: Double = math.log(outlierFloor)
  private[flashalign] val logInlier: Double = math.log1p(-outlierFloor)
  private[flashalign] val logPositivePrior: Double =
    if positivePolarityPrior == 0.0 then Double.NegativeInfinity
    else math.log(positivePolarityPrior)
  private[flashalign] val logNegativePrior: Double =
    if positivePolarityPrior == 1.0 then Double.NegativeInfinity
    else math.log1p(-positivePolarityPrior)

private[flashalign] object PatchObjectiveConfig:
  val MaximumRoundingTolerance: Double = 1e-8

  def create(
      positivePolarityPrior: Double,
      tau: Double,
      outlierFloor: Double,
      minimumContrastEnergy: Double,
      correlationRoundingTolerance: Double = 1e-12
  ): Either[PatchObjectiveError, PatchObjectiveConfig] =
    if !positivePolarityPrior.isFinite ||
      positivePolarityPrior < 0.0 ||
      positivePolarityPrior > 1.0
    then
      Left(
        PatchObjectiveError.InvalidClosedProbability(
          PatchObjectiveParameter.PositivePolarityPrior,
          positivePolarityPrior
        )
      )
    else if !tau.isFinite || tau <= 0.0 then
      Left(
        PatchObjectiveError.InvalidPositive(
          PatchObjectiveParameter.Tau,
          tau
        )
      )
    else if !outlierFloor.isFinite ||
      outlierFloor <= 0.0 ||
      outlierFloor >= 1.0
    then
      Left(
        PatchObjectiveError.InvalidOpenProbability(
          PatchObjectiveParameter.OutlierFloor,
          outlierFloor
        )
      )
    else if !minimumContrastEnergy.isFinite || minimumContrastEnergy <= 0.0 then
      Left(
        PatchObjectiveError.InvalidPositive(
          PatchObjectiveParameter.MinimumContrastEnergy,
          minimumContrastEnergy
        )
      )
    else if !correlationRoundingTolerance.isFinite ||
      correlationRoundingTolerance < 0.0 ||
      correlationRoundingTolerance > MaximumRoundingTolerance
    then
      Left(
        PatchObjectiveError.InvalidRoundingTolerance(
          correlationRoundingTolerance
        )
      )
    else
      Right(
        new PatchObjectiveConfig(
          positivePolarityPrior,
          tau,
          outlierFloor,
          minimumContrastEnergy,
          correlationRoundingTolerance
        )
      )

private[flashalign] final class PreparedMovingPatch private (
    private[flashalign] val normalized: Array[Double],
    val originalContrastNorm: Double
):
  val size: Int = normalized.length

  def normalizedCopy: Array[Double] = normalized.clone()

private[flashalign] object PreparedMovingPatch:
  private[flashalign] def create(
      normalized: Array[Double],
      originalContrastNorm: Double
  ): PreparedMovingPatch =
    new PreparedMovingPatch(normalized, originalContrastNorm)

private[flashalign] final case class PatchPosterior(
    outlier: Double,
    positive: Double,
    negative: Double
):
  val inlierWeight: Double = positive + negative
  val signedWeight: Double = positive - negative
  val conditionalPolarity: Double =
    if inlierWeight == 0.0 then 0.0 else signedWeight / inlierWeight

private[flashalign] final case class PatchObjectiveValue(
    loss: Double,
    correlation: Double,
    posterior: PatchPosterior,
    invalidReason: Option[PatchInvalidReason]
):
  val active: Boolean = invalidReason.isEmpty

/** Reusable primitive result for the patch hot loops. */
private[flashalign] final class PatchObjectiveScratch private ():
  private[flashalign] var loss = 0.0
  private[flashalign] var correlation = 0.0
  private[flashalign] var posteriorOutlier = 1.0
  private[flashalign] var posteriorPositive = 0.0
  private[flashalign] var posteriorNegative = 0.0
  private[flashalign] var invalidReason: PatchInvalidReason | Null = null

  def active: Boolean = invalidReason == null
  def inlierWeight: Double = posteriorPositive + posteriorNegative
  def signedWeight: Double = posteriorPositive - posteriorNegative

  private[flashalign] def snapshot: PatchObjectiveValue =
    PatchObjectiveValue(
      loss,
      correlation,
      PatchPosterior(
        posteriorOutlier,
        posteriorPositive,
        posteriorNegative
      ),
      if invalidReason == null then None
      else Some(invalidReason.asInstanceOf[PatchInvalidReason])
    )

private[flashalign] object PatchObjectiveScratch:
  def create: PatchObjectiveScratch = new PatchObjectiveScratch()

private[flashalign] final class PatchLinearTerms private (
    val objective: PatchObjectiveValue,
    val gradient: Array[Double],
    val curvatureRowMajor: Array[Double]
)

private[flashalign] object PatchLinearTerms:
  private[flashalign] def create(
      objective: PatchObjectiveValue,
      gradient: Array[Double],
      curvatureRowMajor: Array[Double]
  ): PatchLinearTerms =
    new PatchLinearTerms(objective, gradient, curvatureRowMajor)

private[flashalign] object PatchObjective:
  val MinimumSamples: Int = 4

  def prepareMoving(
      values: Array[Double],
      config: PatchObjectiveConfig
  ): Either[PatchObjectiveError, PreparedMovingPatch] =
    validateSamples(values).flatMap { _ =>
      centered(values) match
        case CenteredPatch(_, energy)
            if energy <= config.minimumContrastEnergy =>
          Left(
            PatchObjectiveError.InsufficientContrast(
              energy,
              config.minimumContrastEnergy
            )
          )
        case CenteredPatch(centeredValues, energy) =>
          val norm = math.sqrt(energy)
          var index = 0
          while index < centeredValues.length do
            centeredValues(index) /= norm
            index += 1
          Right(PreparedMovingPatch.create(centeredValues, norm))
    }

  def evaluate(
      moving: PreparedMovingPatch,
      fixedSamples: Array[Double],
      completeInterpolationSupport: Boolean,
      config: PatchObjectiveConfig
  ): Either[PatchObjectiveError, PatchObjectiveValue] =
    val scratch = PatchObjectiveScratch.create
    val error = evaluateInto(
      moving,
      fixedSamples,
      completeInterpolationSupport,
      config,
      scratch
    )
    if error == null then Right(scratch.snapshot)
    else Left(error.asInstanceOf[PatchObjectiveError])

  private[flashalign] def evaluateInto(
      moving: PreparedMovingPatch,
      fixedSamples: Array[Double],
      completeInterpolationSupport: Boolean,
      config: PatchObjectiveConfig,
      scratch: PatchObjectiveScratch
  ): PatchObjectiveError | Null =
    if !completeInterpolationSupport then
      invalidInto(
        config,
        PatchInvalidReason.IncompleteInterpolationSupport,
        scratch
      )
      null
    else if fixedSamples.length != moving.size then
      PatchObjectiveError.SampleCountMismatch(moving.size, fixedSamples.length)
    else
      val origin = fixedSamples(0)
      if !origin.isFinite then PatchObjectiveError.NonFiniteSample(0, origin)
      else
        var shiftedSum = 0.0
        var index = 0
        while index < fixedSamples.length do
          val value = fixedSamples(index)
          if !value.isFinite then
            return PatchObjectiveError.NonFiniteSample(index, value)
          shiftedSum += value - origin
          index += 1
        val shiftedMean = shiftedSum / fixedSamples.length.toDouble
        var energy = 0.0
        var dot = 0.0
        index = 0
        while index < fixedSamples.length do
          val centered = fixedSamples(index) - origin - shiftedMean
          energy += centered * centered
          dot += moving.normalized(index) * centered
          index += 1
        if energy <= config.minimumContrastEnergy then
          invalidInto(
            config,
            PatchInvalidReason.InsufficientContrast,
            scratch
          )
          null
        else
          evaluateCorrelationInto(dot / math.sqrt(energy), config, scratch)

  def linearize(
      objective: PatchObjectiveValue,
      jtu: Array[Double],
      jtjRowMajor: Array[Double],
      parameterCount: Int
  ): Either[PatchObjectiveError, PatchLinearTerms] =
    if parameterCount <= 0 ||
      jtu.length != parameterCount ||
      jtjRowMajor.length != parameterCount * parameterCount
    then
      Left(
        PatchObjectiveError.InvalidLinearizationShape(
          parameterCount,
          jtu.length,
          jtjRowMajor.length
        )
      )
    else
      firstNonFinite(jtu) match
        case Some((index, value)) =>
          Left(
            PatchObjectiveError.NonFiniteLinearization(
              "direction",
              index,
              value
            )
          )
        case None =>
          firstNonFinite(jtjRowMajor) match
            case Some((index, value)) =>
              Left(
                PatchObjectiveError.NonFiniteLinearization(
                  "curvature",
                  index,
                  value
                )
              )
            case None =>
              val gradient = new Array[Double](parameterCount)
              val curvature = new Array[Double](parameterCount * parameterCount)
              if objective.active then
                var parameter = 0
                while parameter < parameterCount do
                  gradient(parameter) =
                    -objective.posterior.signedWeight * jtu(parameter)
                  parameter += 1
                var element = 0
                while element < curvature.length do
                  curvature(element) =
                    objective.posterior.inlierWeight * jtjRowMajor(element)
                  element += 1
              Right(PatchLinearTerms.create(objective, gradient, curvature))

  private[flashalign] def evaluateCorrelation(
      correlation: Double,
      config: PatchObjectiveConfig
  ): Either[PatchObjectiveError, PatchObjectiveValue] =
    val scratch = PatchObjectiveScratch.create
    val error = evaluateCorrelationInto(correlation, config, scratch)
    if error == null then Right(scratch.snapshot)
    else Left(error.asInstanceOf[PatchObjectiveError])

  private[flashalign] def evaluateCorrelationInto(
      correlation: Double,
      config: PatchObjectiveConfig,
      scratch: PatchObjectiveScratch
  ): PatchObjectiveError | Null =
    if !correlation.isFinite ||
      correlation < -1.0 - config.correlationRoundingTolerance ||
      correlation > 1.0 + config.correlationRoundingTolerance
    then
      PatchObjectiveError.CorrelationOutOfRange(
        correlation,
        config.correlationRoundingTolerance
      )
    else
      mixtureInto(
        math.max(-1.0, math.min(1.0, correlation)),
        config,
        scratch
      )

  private final case class CenteredPatch(values: Array[Double], energy: Double)

  private def centered(values: Array[Double]): CenteredPatch =
    val shifted = new Array[Double](values.length)
    val origin = values(0)
    var sum = 0.0
    var index = 0
    while index < values.length do
      val value = values(index) - origin
      shifted(index) = value
      sum += value
      index += 1
    val mean = sum / values.length.toDouble
    var energy = 0.0
    index = 0
    while index < shifted.length do
      val value = shifted(index) - mean
      shifted(index) = value
      energy += value * value
      index += 1
    CenteredPatch(shifted, energy)

  private def mixtureInto(
      correlation: Double,
      config: PatchObjectiveConfig,
      scratch: PatchObjectiveScratch
  ): PatchObjectiveError | Null =
    val logOutlier = config.logOutlier
    val logInlier = config.logInlier
    val logPositive =
      if config.positivePolarityPrior == 0.0 then Double.NegativeInfinity
      else
        logInlier +
          config.logPositivePrior -
          (1.0 - correlation) / config.tauSquared
    val logNegative =
      if config.positivePolarityPrior == 1.0 then Double.NegativeInfinity
      else
        logInlier +
          config.logNegativePrior -
          (1.0 + correlation) / config.tauSquared
    val maximum = math.max(logOutlier, math.max(logPositive, logNegative))
    val scaled =
      math.exp(logOutlier - maximum) +
        math.exp(logPositive - maximum) +
        math.exp(logNegative - maximum)
    val logPartition = maximum + math.log(scaled)
    val rawLoss = -config.tauSquared * logPartition
    val loss =
      if rawLoss >= 0.0 then rawLoss
      else if rawLoss >= -config.correlationRoundingTolerance then 0.0
      else rawLoss
    if !loss.isFinite || loss < 0.0 then
      PatchObjectiveError.InvalidLoss(correlation, loss)
    else
      scratch.loss = loss
      scratch.correlation = correlation
      scratch.posteriorOutlier = math.exp(logOutlier - logPartition)
      scratch.posteriorPositive = math.exp(logPositive - logPartition)
      scratch.posteriorNegative = math.exp(logNegative - logPartition)
      scratch.invalidReason = null
      null

  private[flashalign] def invalid(
      config: PatchObjectiveConfig,
      reason: PatchInvalidReason
  ): PatchObjectiveValue =
    val scratch = PatchObjectiveScratch.create
    invalidInto(config, reason, scratch)
    scratch.snapshot

  private[flashalign] def invalidInto(
      config: PatchObjectiveConfig,
      reason: PatchInvalidReason,
      scratch: PatchObjectiveScratch
  ): Unit =
    scratch.loss = config.outlierCost
    scratch.correlation = 0.0
    scratch.posteriorOutlier = 1.0
    scratch.posteriorPositive = 0.0
    scratch.posteriorNegative = 0.0
    scratch.invalidReason = reason

  private def validateSamples(
      values: Array[Double]
  ): Either[PatchObjectiveError, Unit] =
    if values.length < MinimumSamples then
      Left(
        PatchObjectiveError.InsufficientSamples(
          values.length,
          MinimumSamples
        )
      )
    else validateFinite(values)

  private def validateFinite(
      values: Array[Double]
  ): Either[PatchObjectiveError, Unit] =
    firstNonFinite(values) match
      case Some((index, value)) =>
        Left(PatchObjectiveError.NonFiniteSample(index, value))
      case None => Right(())

  private def firstNonFinite(
      values: Array[Double]
  ): Option[(Int, Double)] =
    var index = 0
    while index < values.length do
      if !values(index).isFinite then return Some((index, values(index)))
      index += 1
    None
