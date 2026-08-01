package reframe4s.halfflow

enum MidpointArmName:
  case Fixed
  case Moving

enum GeometryVerdict:
  case Valid
  case IncrementJacobianTooSmall(minimum: Double)
  case AccumulatedJacobianTooSmall(arm: MidpointArmName, minimum: Double)

enum NumericalVerdict:
  case Accurate
  case IncreaseIntegrationDepth(errorMm: Double)
  case RefreshInverseCache(arm: MidpointArmName, errorMm: Double)

enum ObjectiveVerdict:
  case Accepted(actualDrop: Double)
  case Rejected(currentValue: Double, candidateValue: Double)

enum DampingResetPolicy:
  case Initial
  case Retain

enum ControlObservation:
  case Objective(verdict: ObjectiveVerdict)
  case Geometry(verdict: GeometryVerdict)
  case Numerical(verdict: NumericalVerdict)
  case LevelTransition

enum ControlAction:
  case AcceptObjective
  case RejectObjective
  case AcceptGeometry
  case ReduceStepScale
  case AcceptIntegration
  case IncreaseSquarings
  case RefreshInverseCache(arm: MidpointArmName)
  case ResetLevelDamping
  case RetainLevelDamping

final case class HalfFlowCcControlConfig private (
    initialDamping: Double,
    minimumDamping: Double,
    maximumDamping: Double,
    dampingIncrease: Double,
    dampingDecrease: Double,
    initialStepScale: Double,
    minimumStepScale: Double,
    maximumStepScale: Double,
    minimumSquarings: Int,
    maximumSquarings: Int,
    maximumObjectiveRetries: Int,
    maximumGeometryRetries: Int,
    maximumIntegrationRetries: Int,
    dampingReset: DampingResetPolicy
)

object HalfFlowCcControlConfig:
  def make(
      initialDamping: Double = 1e-2,
      minimumDamping: Double = 1e-7,
      maximumDamping: Double = 1e3,
      dampingIncrease: Double = 4.0,
      dampingDecrease: Double = 0.5,
      initialStepScale: Double = 1.0,
      minimumStepScale: Double = 1.0 / 64.0,
      maximumStepScale: Double = 1.0,
      minimumSquarings: Int = 0,
      maximumSquarings: Int = 12,
      maximumObjectiveRetries: Int = 4,
      maximumGeometryRetries: Int = 8,
      maximumIntegrationRetries: Int = 4,
      dampingReset: DampingResetPolicy = DampingResetPolicy.Initial
  ): Either[RegistrationError, HalfFlowCcControlConfig] =
    val dampingValid =
      minimumDamping.isFinite && initialDamping.isFinite && maximumDamping.isFinite &&
        minimumDamping > 0.0 && minimumDamping <= initialDamping && initialDamping < maximumDamping &&
        dampingIncrease.isFinite && dampingIncrease > 1.0 &&
        dampingDecrease.isFinite && dampingDecrease > 0.0 && dampingDecrease < 1.0
    val stepValid =
      minimumStepScale.isFinite && initialStepScale.isFinite && maximumStepScale.isFinite &&
        minimumStepScale > 0.0 && minimumStepScale < initialStepScale && initialStepScale <= maximumStepScale
    val depthValid = minimumSquarings >= 0 && maximumSquarings > minimumSquarings
    val retriesValid =
      maximumObjectiveRetries > 0 && maximumGeometryRetries > 0 && maximumIntegrationRetries > 0
    if !dampingValid then Left(RegistrationError.InvalidConfiguration("HalfFlow-CC damping control"))
    else if !stepValid then Left(RegistrationError.InvalidConfiguration("HalfFlow-CC step-scale control"))
    else if !depthValid then Left(RegistrationError.InvalidConfiguration("HalfFlow-CC integration-depth control"))
    else if !retriesValid then Left(RegistrationError.InvalidConfiguration("HalfFlow-CC retry budgets"))
    else
      Right(
        new HalfFlowCcControlConfig(
          initialDamping,
          minimumDamping,
          maximumDamping,
          dampingIncrease,
          dampingDecrease,
          initialStepScale,
          minimumStepScale,
          maximumStepScale,
          minimumSquarings,
          maximumSquarings,
          maximumObjectiveRetries,
          maximumGeometryRetries,
          maximumIntegrationRetries,
          dampingReset
        )
      )

  val default: HalfFlowCcControlConfig =
    make().fold(error => throw new IllegalStateException(error.message), identity)

final case class HalfFlowCcControlState(
    damping: Double,
    stepScale: Double,
    squarings: Int,
    objectiveRetries: Int,
    geometryRetries: Int,
    integrationRetries: Int
):
  def exhausted(config: HalfFlowCcControlConfig): Boolean =
    objectiveRetries >= config.maximumObjectiveRetries ||
      geometryRetries >= config.maximumGeometryRetries ||
      integrationRetries >= config.maximumIntegrationRetries ||
      damping >= config.maximumDamping || stepScale <= config.minimumStepScale ||
      squarings >= config.maximumSquarings

object HalfFlowCcControlState:
  def initial(config: HalfFlowCcControlConfig): HalfFlowCcControlState =
    HalfFlowCcControlState(
      config.initialDamping,
      config.initialStepScale,
      config.minimumSquarings,
      objectiveRetries = 0,
      geometryRetries = 0,
      integrationRetries = 0
    )

final case class ControlTransition(
    observation: ControlObservation,
    action: ControlAction,
    before: HalfFlowCcControlState,
    after: HalfFlowCcControlState
)

object HalfFlowCcControl:
  def observe(
      state: HalfFlowCcControlState,
      observation: ControlObservation,
      config: HalfFlowCcControlConfig
  ): ControlTransition =
    val (after, action) = observation match
      case ControlObservation.Objective(ObjectiveVerdict.Accepted(_)) =>
        (
          state.copy(
            damping = math.max(config.minimumDamping, state.damping * config.dampingDecrease),
            objectiveRetries = 0
          ),
          ControlAction.AcceptObjective
        )
      case ControlObservation.Objective(ObjectiveVerdict.Rejected(_, _)) =>
        (
          state.copy(
            damping = math.min(config.maximumDamping, state.damping * config.dampingIncrease),
            objectiveRetries = math.min(config.maximumObjectiveRetries, state.objectiveRetries + 1)
          ),
          ControlAction.RejectObjective
        )
      case ControlObservation.Geometry(GeometryVerdict.Valid) =>
        (
          state.copy(geometryRetries = 0),
          ControlAction.AcceptGeometry
        )
      case ControlObservation.Geometry(GeometryVerdict.IncrementJacobianTooSmall(_)) =>
        reduceStep(state, config)
      case ControlObservation.Geometry(GeometryVerdict.AccumulatedJacobianTooSmall(_, _)) =>
        reduceStep(state, config)
      case ControlObservation.Numerical(NumericalVerdict.Accurate) =>
        (state.copy(integrationRetries = 0), ControlAction.AcceptIntegration)
      case ControlObservation.Numerical(NumericalVerdict.IncreaseIntegrationDepth(_)) =>
        (
          state.copy(
            squarings = math.min(config.maximumSquarings, state.squarings + 1),
            integrationRetries = math.min(config.maximumIntegrationRetries, state.integrationRetries + 1)
          ),
          ControlAction.IncreaseSquarings
        )
      case ControlObservation.Numerical(NumericalVerdict.RefreshInverseCache(arm, _)) =>
        (state, ControlAction.RefreshInverseCache(arm))
      case ControlObservation.LevelTransition =>
        config.dampingReset match
          case DampingResetPolicy.Initial =>
            (
              state.copy(
                damping = config.initialDamping,
                objectiveRetries = 0,
                geometryRetries = 0,
                integrationRetries = 0
              ),
              ControlAction.ResetLevelDamping
            )
          case DampingResetPolicy.Retain =>
            (
              state.copy(objectiveRetries = 0, geometryRetries = 0, integrationRetries = 0),
              ControlAction.RetainLevelDamping
            )
    ControlTransition(observation, action, state, after)

  private def reduceStep(
      state: HalfFlowCcControlState,
      config: HalfFlowCcControlConfig
  ): (HalfFlowCcControlState, ControlAction) =
    (
      state.copy(
        stepScale = math.max(config.minimumStepScale, state.stepScale * 0.5),
        geometryRetries = math.min(config.maximumGeometryRetries, state.geometryRetries + 1)
      ),
      ControlAction.ReduceStepScale
    )
