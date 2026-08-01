package reframe4s.halfflow

enum TrustRejection:
  case UnsafeLocal
  case UnsafeAccumulated
  case NonFinite
  case NonPositiveActualDrop
  case NonPositivePredictedDrop
  case LowGain

final case class RejectionCounts(
    unsafeLocal: Int = 0,
    unsafeAccumulated: Int = 0,
    nonFinite: Int = 0,
    nonPositiveActualDrop: Int = 0,
    nonPositivePredictedDrop: Int = 0,
    lowGain: Int = 0
):
  def increment(reason: TrustRejection): RejectionCounts =
    reason match
      case TrustRejection.UnsafeLocal => copy(unsafeLocal = unsafeLocal + 1)
      case TrustRejection.UnsafeAccumulated => copy(unsafeAccumulated = unsafeAccumulated + 1)
      case TrustRejection.NonFinite => copy(nonFinite = nonFinite + 1)
      case TrustRejection.NonPositiveActualDrop => copy(nonPositiveActualDrop = nonPositiveActualDrop + 1)
      case TrustRejection.NonPositivePredictedDrop =>
        copy(nonPositivePredictedDrop = nonPositivePredictedDrop + 1)
      case TrustRejection.LowGain => copy(lowGain = lowGain + 1)

  def total: Int =
    unsafeLocal + unsafeAccumulated + nonFinite + nonPositiveActualDrop + nonPositivePredictedDrop + lowGain

final case class TrustConfig private (
    initialDamping: Double,
    minimumDamping: Double,
    maximumDamping: Double,
    etaAccept: Double,
    lowGain: Double,
    highGain: Double,
    targetAcceptedSteps: Int,
    maximumAttempts: Int
)

object TrustConfig:
  def make(
      initialDamping: Double = 1e-2,
      minimumDamping: Double = 1e-7,
      maximumDamping: Double = 1e5,
      etaAccept: Double = 0.10,
      lowGain: Double = 0.25,
      highGain: Double = 0.75,
      targetAcceptedSteps: Int = 8,
      maximumAttempts: Int = 32
  ): Either[RegistrationError, TrustConfig] =
    val dampingValid =
      minimumDamping.isFinite && maximumDamping.isFinite && initialDamping.isFinite &&
        minimumDamping > 0.0 && minimumDamping <= initialDamping && initialDamping <= maximumDamping
    val gainsValid =
      etaAccept.isFinite && lowGain.isFinite && highGain.isFinite &&
        etaAccept > 0.0 && etaAccept <= lowGain && lowGain < highGain
    if !dampingValid then Left(RegistrationError.InvalidConfiguration("trust damping bounds"))
    else if !gainsValid then Left(RegistrationError.InvalidConfiguration("trust gain thresholds"))
    else if targetAcceptedSteps <= 0 || maximumAttempts < targetAcceptedSteps then
      Left(RegistrationError.InvalidConfiguration("trust attempt or accepted-step limits"))
    else
      Right(
        new TrustConfig(
          initialDamping,
          minimumDamping,
          maximumDamping,
          etaAccept,
          lowGain,
          highGain,
          targetAcceptedSteps,
          maximumAttempts
        )
      )

  val default: TrustConfig =
    make().fold(error => throw new IllegalStateException(error.message), identity)

enum TrustTermination:
  case AcceptedStepBudget
  case AttemptBudget
  case MaximumDamping

final case class TrustState(
    damping: Double,
    attempts: Int = 0,
    safeAttempts: Int = 0,
    acceptedSteps: Int = 0,
    rejections: RejectionCounts = RejectionCounts()
):
  def terminal(config: TrustConfig): Option[TrustTermination] =
    if acceptedSteps >= config.targetAcceptedSteps then Some(TrustTermination.AcceptedStepBudget)
    else if attempts >= config.maximumAttempts then Some(TrustTermination.AttemptBudget)
    else if damping >= config.maximumDamping then Some(TrustTermination.MaximumDamping)
    else None

object TrustState:
  def initial(config: TrustConfig): TrustState = TrustState(config.initialDamping)

final case class TrustAttempt[S](
    currentState: S,
    candidateState: S,
    currentValue: Double,
    candidateValue: Double,
    predictedDrop: Double,
    localSafe: Boolean,
    accumulatedSafe: Boolean
)

final case class TrustDecision(
    accepted: Boolean,
    actualDrop: Double,
    predictedDrop: Double,
    gainRatio: Double,
    rejection: Option[TrustRejection],
    before: TrustState,
    after: TrustState
)

final case class TrustOutcome[S](state: S, value: Double, decision: TrustDecision)

object TrustModel:
  /** Selects candidate or current state without mutating either one. */
  def evaluate[S](
      state: TrustState,
      attempt: TrustAttempt[S],
      config: TrustConfig
  ): TrustOutcome[S] =
    val actual = attempt.currentValue - attempt.candidateValue
    val ratio =
      if attempt.predictedDrop > 0.0 then actual / attempt.predictedDrop
      else Double.NegativeInfinity
    val rejection = classify(attempt, actual, ratio, config)
    val accepted = rejection.isEmpty
    val safe = attempt.localSafe && attempt.accumulatedSafe
    val nextDamping =
      if rejection.exists(_ != TrustRejection.LowGain) then
        math.min(config.maximumDamping, state.damping * 4.0)
      else if ratio < config.lowGain then math.min(config.maximumDamping, state.damping * 4.0)
      else if ratio > config.highGain then math.max(config.minimumDamping, state.damping * 0.5)
      else state.damping
    val next = state.copy(
      damping = nextDamping,
      attempts = state.attempts + 1,
      safeAttempts = state.safeAttempts + (if safe then 1 else 0),
      acceptedSteps = state.acceptedSteps + (if accepted then 1 else 0),
      rejections = rejection.fold(state.rejections)(state.rejections.increment)
    )
    val decision = TrustDecision(accepted, actual, attempt.predictedDrop, ratio, rejection, state, next)
    if accepted then TrustOutcome(attempt.candidateState, attempt.candidateValue, decision)
    else TrustOutcome(attempt.currentState, attempt.currentValue, decision)

  private def classify[S](
      attempt: TrustAttempt[S],
      actual: Double,
      ratio: Double,
      config: TrustConfig
  ): Option[TrustRejection] =
    if !attempt.localSafe then Some(TrustRejection.UnsafeLocal)
    else if !attempt.accumulatedSafe then Some(TrustRejection.UnsafeAccumulated)
    else if
      !attempt.currentValue.isFinite || !attempt.candidateValue.isFinite ||
        !actual.isFinite || !attempt.predictedDrop.isFinite
    then Some(TrustRejection.NonFinite)
    else if actual <= 0.0 then Some(TrustRejection.NonPositiveActualDrop)
    else if attempt.predictedDrop <= 0.0 then Some(TrustRejection.NonPositivePredictedDrop)
    else if !ratio.isFinite then Some(TrustRejection.NonFinite)
    else if ratio < config.etaAccept then Some(TrustRejection.LowGain)
    else None
