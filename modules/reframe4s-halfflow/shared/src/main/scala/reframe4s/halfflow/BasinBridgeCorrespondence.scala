package reframe4s.halfflow

/** Errors raised while constructing the typed BasinBridge evidence model. */
enum BasinBridgeError:
  case EmptyCorrespondences(label: String)
  case MismatchedCorrespondenceCount(work: Int, endpoint: Int)
  case NonFinitePoint(label: String)
  case InvalidConfidence(value: Double)
  case InvalidCycleError(value: Double)
  case InvalidCycleScale(value: Double)
  case InvalidAmbiguityFloor(value: Double)
  case EmptyDistribution(label: String)
  case InvalidDistributionValue(label: String, index: Int, value: Double)
  case NonPositiveDistributionMass(label: String)
  case NonFiniteDistributionMass(label: String)

  def message: String =
    this match
      case EmptyCorrespondences(label) =>
        s"$label correspondence collection must not be empty"
      case MismatchedCorrespondenceCount(work, endpoint) =>
        s"work and endpoint correspondence counts differ: $work != $endpoint"
      case NonFinitePoint(label) =>
        s"$label correspondence point must have finite coordinates"
      case InvalidConfidence(value) =>
        s"correspondence confidence must be finite and in [0, 1]; got $value"
      case InvalidCycleError(value) =>
        s"cycle error must be finite and non-negative; got $value"
      case InvalidCycleScale(value) =>
        s"cycle scale must be finite and positive; got $value"
      case InvalidAmbiguityFloor(value) =>
        s"ambiguity floor must be finite and in [0, 1]; got $value"
      case EmptyDistribution(label) =>
        s"$label displacement distribution must not be empty"
      case InvalidDistributionValue(label, index, value) =>
        s"$label displacement distribution value $index must be finite and non-negative; got $value"
      case NonPositiveDistributionMass(label) =>
        s"$label displacement distribution must have positive mass"
      case NonFiniteDistributionMass(label) =>
        s"$label displacement distribution mass must be finite"

/** A physical coordinate in the BasinBridge work space. */
final case class BasinBridgePoint private (x: Double, y: Double, z: Double):
  def +(other: BasinBridgePoint): BasinBridgePoint =
    BasinBridgePoint.unsafe(x + other.x, y + other.y, z + other.z)

  def -(other: BasinBridgePoint): BasinBridgePoint =
    BasinBridgePoint.unsafe(x - other.x, y - other.y, z - other.z)

  def *(factor: Double): BasinBridgePoint =
    BasinBridgePoint.unsafe(x * factor, y * factor, z * factor)

  def norm: Double =
    math.sqrt(x * x + y * y + z * z)

  def distanceTo(other: BasinBridgePoint): Double =
    (this - other).norm

object BasinBridgePoint:
  def make(x: Double, y: Double, z: Double, label: String = "BasinBridge"): Either[BasinBridgeError, BasinBridgePoint] =
    if x.isFinite && y.isFinite && z.isFinite then Right(new BasinBridgePoint(x, y, z))
    else Left(BasinBridgeError.NonFinitePoint(label))

  def unsafe(x: Double, y: Double, z: Double): BasinBridgePoint =
    make(x, y, z).fold(error => throw new IllegalArgumentException(error.message), identity)

/** Parameters for the soft ambiguity/cycle confidence score. */
final case class BasinBridgeConfidenceConfig private (
    cycleScaleMm: Double,
    ambiguityFloor: Double
)

object BasinBridgeConfidenceConfig:
  def make(
      cycleScaleMm: Double = 2.0,
      ambiguityFloor: Double = 0.02
  ): Either[BasinBridgeError, BasinBridgeConfidenceConfig] =
    if !cycleScaleMm.isFinite || cycleScaleMm <= 0.0 then
      Left(BasinBridgeError.InvalidCycleScale(cycleScaleMm))
    else if !ambiguityFloor.isFinite || ambiguityFloor < 0.0 || ambiguityFloor > 1.0 then
      Left(BasinBridgeError.InvalidAmbiguityFloor(ambiguityFloor))
    else Right(new BasinBridgeConfidenceConfig(cycleScaleMm, ambiguityFloor))

  val default: BasinBridgeConfidenceConfig =
    make().fold(error => throw new IllegalStateException(error.message), identity)

/** Forward/reverse search evidence kept separate from the final correspondence. */
final case class BasinBridgeConfidenceEvidence private (
    forwardDisplacementDistribution: Vector[Double],
    reverseDisplacementDistribution: Vector[Double],
    cycleErrorMm: Double,
    priorConfidence: Double
)

object BasinBridgeConfidenceEvidence:
  def make(
      forwardDisplacementDistribution: Vector[Double],
      reverseDisplacementDistribution: Vector[Double],
      cycleErrorMm: Double,
      priorConfidence: Double = 1.0
  ): Either[BasinBridgeError, BasinBridgeConfidenceEvidence] =
    for
      _ <- validateDistribution(forwardDisplacementDistribution, "forward")
      _ <- validateDistribution(reverseDisplacementDistribution, "reverse")
      _ <-
        if cycleErrorMm.isFinite && cycleErrorMm >= 0.0 then Right(())
        else Left(BasinBridgeError.InvalidCycleError(cycleErrorMm))
      _ <-
        if priorConfidence.isFinite && priorConfidence >= 0.0 && priorConfidence <= 1.0 then Right(())
        else Left(BasinBridgeError.InvalidConfidence(priorConfidence))
    yield
      new BasinBridgeConfidenceEvidence(
        forwardDisplacementDistribution,
        reverseDisplacementDistribution,
        cycleErrorMm,
        priorConfidence
      )

  private def validateDistribution(
      values: Vector[Double],
      label: String
  ): Either[BasinBridgeError, Unit] =
    if values.isEmpty then Left(BasinBridgeError.EmptyDistribution(label))
    else
      var index = 0
      var mass = 0.0
      var failure = Option.empty[BasinBridgeError]
      while index < values.length && failure.isEmpty do
        val value = values(index)
        if !value.isFinite || value < 0.0 then
          failure = Some(BasinBridgeError.InvalidDistributionValue(label, index, value))
        else
          mass += value
        index += 1
      failure match
        case Some(error) => Left(error)
        case None if !mass.isFinite => Left(BasinBridgeError.NonFiniteDistributionMass(label))
        case None if mass <= 0.0 => Left(BasinBridgeError.NonPositiveDistributionMass(label))
        case None => Right(())

/** Diagnostic components of the soft correspondence weight. */
final case class BasinBridgeConfidence private (
    forwardNormalizedEntropy: Double,
    reverseNormalizedEntropy: Double,
    ambiguityQuality: Double,
    cycleConsistency: Double,
    priorConfidence: Double,
    effectiveWeight: Double
):
  def value: Double = effectiveWeight

object BasinBridgeConfidence:
  def direct(value: Double): Either[BasinBridgeError, BasinBridgeConfidence] =
    if value.isFinite && value >= 0.0 && value <= 1.0 then
      Right(new BasinBridgeConfidence(0.0, 0.0, 1.0, 1.0, value, value))
    else Left(BasinBridgeError.InvalidConfidence(value))

  def from(
      evidence: BasinBridgeConfidenceEvidence,
      config: BasinBridgeConfidenceConfig = BasinBridgeConfidenceConfig.default
  ): Either[BasinBridgeError, BasinBridgeConfidence] =
    for
      forwardEntropy <- normalizedEntropy(evidence.forwardDisplacementDistribution, "forward")
      reverseEntropy <- normalizedEntropy(evidence.reverseDisplacementDistribution, "reverse")
    yield
      val forwardQuality =
        config.ambiguityFloor + (1.0 - config.ambiguityFloor) * (1.0 - forwardEntropy)
      val reverseQuality =
        config.ambiguityFloor + (1.0 - config.ambiguityFloor) * (1.0 - reverseEntropy)
      val ambiguityQuality = math.sqrt(forwardQuality * reverseQuality)
      val cycleConsistency = math.exp(-evidence.cycleErrorMm / config.cycleScaleMm)
      val effectiveWeight =
        evidence.priorConfidence * ambiguityQuality * cycleConsistency
      new BasinBridgeConfidence(
        forwardEntropy,
        reverseEntropy,
        ambiguityQuality,
        cycleConsistency,
        evidence.priorConfidence,
        effectiveWeight
      )

  private def normalizedEntropy(
      values: Vector[Double],
      label: String
  ): Either[BasinBridgeError, Double] =
    BasinBridgeConfidenceEvidence
      .make(values, values, cycleErrorMm = 0.0)
      .flatMap: _ =>
        var mass = 0.0
        var index = 0
        while index < values.length do
          mass += values(index)
          index += 1
        val maximumEntropy = math.log(values.length.toDouble)
        if maximumEntropy == 0.0 then Right(0.0)
        else
          var entropy = 0.0
          index = 0
          while index < values.length do
            val probability = values(index) / mass
            if probability > 0.0 then entropy -= probability * math.log(probability)
            index += 1
          val normalized = entropy / maximumEntropy
          if normalized.isFinite then Right(math.max(0.0, math.min(1.0, normalized)))
          else Left(BasinBridgeError.NonFiniteDistributionMass(label))

/** A typed fixed/moving match in physical pull-map coordinates.
  *
  * fixed is q, moving is p, and the authoritative tangent is p - q.
  * Confidence is a soft weight: low-confidence matches remain inspectable and
  * are not deleted by this contract.
  */
final case class BasinBridgeCorrespondence private (
    fixed: BasinBridgePoint,
    moving: BasinBridgePoint,
    confidenceScore: BasinBridgeConfidence
):
  def confidence: Double = confidenceScore.effectiveWeight

  def confidenceEvidence: BasinBridgeConfidence = confidenceScore

  def midpoint: BasinBridgePoint =
    (fixed + moving) * 0.5

  def tangent: BasinBridgePoint =
    moving - fixed

  def p: BasinBridgePoint = moving

  def q: BasinBridgePoint = fixed

  def swapped: BasinBridgeCorrespondence =
    BasinBridgeCorrespondence.unsafe(moving, fixed, confidenceScore)

object BasinBridgeCorrespondence:
  def make(
      fixed: BasinBridgePoint,
      moving: BasinBridgePoint,
      confidence: Double
  ): Either[BasinBridgeError, BasinBridgeCorrespondence] =
    BasinBridgeConfidence.direct(confidence).map: score =>
      new BasinBridgeCorrespondence(fixed, moving, score)

  def fromConfidence(
      fixed: BasinBridgePoint,
      moving: BasinBridgePoint,
      confidence: BasinBridgeConfidence
  ): BasinBridgeCorrespondence =
    new BasinBridgeCorrespondence(fixed, moving, confidence)

  def fromEvidence(
      fixed: BasinBridgePoint,
      moving: BasinBridgePoint,
      evidence: BasinBridgeConfidenceEvidence,
      config: BasinBridgeConfidenceConfig = BasinBridgeConfidenceConfig.default
  ): Either[BasinBridgeError, BasinBridgeCorrespondence] =
    BasinBridgeConfidence.from(evidence, config).flatMap: confidence =>
      Right(fromConfidence(fixed, moving, confidence))

  private def unsafe(
      fixed: BasinBridgePoint,
      moving: BasinBridgePoint,
      confidence: BasinBridgeConfidence
  ): BasinBridgeCorrespondence =
    new BasinBridgeCorrespondence(fixed, moving, confidence)

/** Correspondences expressed in the common midpoint work frame. */
final case class BasinBridgeWorkMatches private (values: Vector[BasinBridgeCorrespondence])

object BasinBridgeWorkMatches:
  def fromVector(
      values: Vector[BasinBridgeCorrespondence]
  ): Either[BasinBridgeError, BasinBridgeWorkMatches] =
    if values.isEmpty then Left(BasinBridgeError.EmptyCorrespondences("work"))
    else Right(new BasinBridgeWorkMatches(values))

/** Correspondences expressed in the fixed and moving endpoint frames. */
final case class BasinBridgeEndpointMatches private (values: Vector[BasinBridgeCorrespondence])

object BasinBridgeEndpointMatches:
  def fromVector(
      values: Vector[BasinBridgeCorrespondence]
  ): Either[BasinBridgeError, BasinBridgeEndpointMatches] =
    if values.isEmpty then Left(BasinBridgeError.EmptyCorrespondences("endpoint"))
    else Right(new BasinBridgeEndpointMatches(values))

/** Work-space and endpoint evidence for one bridge round. */
final case class BasinBridgeRoundEvidence private (
    work: BasinBridgeWorkMatches,
    endpoint: BasinBridgeEndpointMatches
)

object BasinBridgeRoundEvidence:
  def make(
      work: BasinBridgeWorkMatches,
      endpoint: BasinBridgeEndpointMatches
  ): Either[BasinBridgeError, BasinBridgeRoundEvidence] =
    if work.values.length != endpoint.values.length then
      Left(
        BasinBridgeError.MismatchedCorrespondenceCount(
          work.values.length,
          endpoint.values.length
        )
      )
    else Right(new BasinBridgeRoundEvidence(work, endpoint))
