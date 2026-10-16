package reframe4s.halfflow

class HalfFlowCcControlSuite extends munit.FunSuite:
  test("objective observations change only damping"):
    val config = HalfFlowCcControlConfig.default
    val initial = HalfFlowCcControlState.initial(config).copy(stepScale = 0.5, squarings = 3)
    val rejected = HalfFlowCcControl.observe(
      initial,
      ControlObservation.Objective(ObjectiveVerdict.Rejected(1.0, 1.1)),
      config
    )
    assertEqualsDouble(rejected.after.damping, initial.damping * 4.0, 0.0)
    assertEqualsDouble(rejected.after.stepScale, initial.stepScale, 0.0)
    assertEquals(rejected.after.squarings, initial.squarings)
    assertEquals(rejected.action, ControlAction.RejectObjective)
    val accepted = HalfFlowCcControl.observe(
      rejected.after,
      ControlObservation.Objective(ObjectiveVerdict.Accepted(0.2)),
      config
    )
    assertEqualsDouble(accepted.after.damping, rejected.after.damping * 0.5, 0.0)
    assertEqualsDouble(accepted.after.stepScale, rejected.after.stepScale, 0.0)
    assertEquals(accepted.after.squarings, rejected.after.squarings)

  test("Jacobian observations change only step scale"):
    val config = HalfFlowCcControlConfig.default
    val initial = HalfFlowCcControlState.initial(config).copy(damping = 0.2, squarings = 2)
    val incremental = HalfFlowCcControl.observe(
      initial,
      ControlObservation.Geometry(GeometryVerdict.IncrementJacobianTooSmall(0.02)),
      config
    )
    assertEqualsDouble(incremental.after.stepScale, 0.5, 0.0)
    assertEqualsDouble(incremental.after.damping, initial.damping, 0.0)
    assertEquals(incremental.after.squarings, initial.squarings)
    val accumulated = HalfFlowCcControl.observe(
      incremental.after,
      ControlObservation.Geometry(GeometryVerdict.AccumulatedJacobianTooSmall(MidpointArmName.Moving, 0.03)),
      config
    )
    assertEqualsDouble(accumulated.after.stepScale, 0.25, 0.0)
    assertEqualsDouble(accumulated.after.damping, initial.damping, 0.0)
    assertEquals(accumulated.after.squarings, initial.squarings)
    assertEquals(accumulated.action, ControlAction.ReduceStepScale)
    val safe = HalfFlowCcControl.observe(
      accumulated.after,
      ControlObservation.Geometry(GeometryVerdict.Valid),
      config
    )
    assertEqualsDouble(safe.after.stepScale, accumulated.after.stepScale, 0.0)

  test("integration observations change only squaring depth"):
    val config = HalfFlowCcControlConfig.default
    val initial = HalfFlowCcControlState.initial(config).copy(damping = 0.3, stepScale = 0.4)
    val deeper = HalfFlowCcControl.observe(
      initial,
      ControlObservation.Numerical(NumericalVerdict.IncreaseIntegrationDepth(0.04)),
      config
    )
    assertEquals(deeper.after.squarings, initial.squarings + 1)
    assertEqualsDouble(deeper.after.damping, initial.damping, 0.0)
    assertEqualsDouble(deeper.after.stepScale, initial.stepScale, 0.0)
    assertEquals(deeper.action, ControlAction.IncreaseSquarings)

  test("inverse-cache drift requests repair without changing any controller"):
    val config = HalfFlowCcControlConfig.default
    val initial = HalfFlowCcControlState.initial(config).copy(damping = 0.4, stepScale = 0.3, squarings = 4)
    val refresh = HalfFlowCcControl.observe(
      initial,
      ControlObservation.Numerical(NumericalVerdict.RefreshInverseCache(MidpointArmName.Fixed, 0.08)),
      config
    )
    assertEquals(refresh.after, initial)
    assertEquals(refresh.action, ControlAction.RefreshInverseCache(MidpointArmName.Fixed))

  test("level damping reset policy and caps are explicit"):
    val reset = HalfFlowCcControlConfig.default
    val before = HalfFlowCcControlState.initial(reset).copy(
      damping = 8.0,
      stepScale = reset.minimumStepScale,
      squarings = reset.maximumSquarings,
      objectiveRetries = 3,
      geometryRetries = 2,
      integrationRetries = 1
    )
    val transition = HalfFlowCcControl.observe(before, ControlObservation.LevelTransition, reset)
    assertEqualsDouble(transition.after.damping, reset.initialDamping, 0.0)
    assertEqualsDouble(transition.after.stepScale, before.stepScale, 0.0)
    assertEquals(transition.after.squarings, before.squarings)
    assertEquals(transition.after.objectiveRetries, 0)
    assertEquals(transition.action, ControlAction.ResetLevelDamping)
    assert(before.exhausted(reset))

    val retain = HalfFlowCcControlConfig
      .make(dampingReset = DampingResetPolicy.Retain)
      .fold(error => fail(error.message), identity)
    val retained = HalfFlowCcControl.observe(before, ControlObservation.LevelTransition, retain)
    assertEqualsDouble(retained.after.damping, before.damping, 0.0)
    assertEquals(retained.action, ControlAction.RetainLevelDamping)

  test("controller values and retry counters stop exactly at configured caps"):
    val config = HalfFlowCcControlConfig
      .make(
        maximumDamping = 0.04,
        minimumStepScale = 0.25,
        maximumSquarings = 2,
        maximumObjectiveRetries = 2,
        maximumGeometryRetries = 2,
        maximumIntegrationRetries = 2
      )
      .fold(error => fail(error.message), identity)
    val initial = HalfFlowCcControlState.initial(config)
    val objective1 = HalfFlowCcControl.observe(
      initial,
      ControlObservation.Objective(ObjectiveVerdict.Rejected(1.0, 2.0)),
      config
    ).after
    val objective2 = HalfFlowCcControl.observe(
      objective1,
      ControlObservation.Objective(ObjectiveVerdict.Rejected(1.0, 2.0)),
      config
    ).after
    assertEqualsDouble(objective2.damping, 0.04, 0.0)
    assertEquals(objective2.objectiveRetries, 2)
    assert(objective2.exhausted(config))

    val geometry1 = HalfFlowCcControl.observe(
      initial,
      ControlObservation.Geometry(GeometryVerdict.IncrementJacobianTooSmall(0.01)),
      config
    ).after
    val geometry2 = HalfFlowCcControl.observe(
      geometry1,
      ControlObservation.Geometry(GeometryVerdict.IncrementJacobianTooSmall(0.01)),
      config
    ).after
    assertEqualsDouble(geometry2.stepScale, 0.25, 0.0)
    assertEquals(geometry2.geometryRetries, 2)

    val integration1 = HalfFlowCcControl.observe(
      initial,
      ControlObservation.Numerical(NumericalVerdict.IncreaseIntegrationDepth(0.2)),
      config
    ).after
    val integration2 = HalfFlowCcControl.observe(
      integration1,
      ControlObservation.Numerical(NumericalVerdict.IncreaseIntegrationDepth(0.2)),
      config
    ).after
    assertEquals(integration2.squarings, 2)
    assertEquals(integration2.integrationRetries, 2)
