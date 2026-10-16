package reframe4s.halfflow

class TrustModelSuite extends munit.FunSuite:
  private val config = right(
    TrustConfig.make(
      initialDamping = 1.0,
      minimumDamping = 0.01,
      maximumDamping = 64.0,
      targetAcceptedSteps = 2,
      maximumAttempts = 4
    )
  )

  test("accepted high-gain candidate commits and lowers damping"):
    val outcome = evaluate(current = 10.0, candidate = 8.0, predicted = 2.0)
    assert(outcome.decision.accepted)
    assertEquals(outcome.state, "candidate")
    assertEqualsDouble(outcome.value, 8.0, 0.0)
    assertEqualsDouble(outcome.decision.gainRatio, 1.0, 0.0)
    assertEqualsDouble(outcome.decision.after.damping, 0.5, 0.0)
    assertEquals(outcome.decision.after.attempts, 1)
    assertEquals(outcome.decision.after.safeAttempts, 1)
    assertEquals(outcome.decision.after.acceptedSteps, 1)

  test("accepted low-gain candidate still increases damping"):
    val outcome = evaluate(current = 10.0, candidate = 9.6, predicted = 2.0)
    assert(outcome.decision.accepted)
    assertEqualsDouble(outcome.decision.gainRatio, 0.2, 1e-14)
    assertEqualsDouble(outcome.decision.after.damping, 4.0, 0.0)

  test("low gain and unsafe proposals rollback state and partition rejections"):
    val low = evaluate(current = 10.0, candidate = 9.9, predicted = 2.0)
    assert(!low.decision.accepted)
    assertEquals(low.state, "current")
    assertEqualsDouble(low.value, 10.0, 0.0)
    assertEquals(low.decision.rejection, Some(TrustRejection.LowGain))
    assertEquals(low.decision.after.rejections.lowGain, 1)

    val unsafe = TrustModel.evaluate(
      low.decision.after,
      TrustAttempt("current", "unsafe", 10.0, 8.0, 2.0, localSafe = true, accumulatedSafe = false),
      config
    )
    assert(!unsafe.decision.accepted)
    assertEquals(unsafe.state, "current")
    assertEquals(unsafe.decision.rejection, Some(TrustRejection.UnsafeAccumulated))
    assertEquals(unsafe.decision.after.attempts, 2)
    assertEquals(unsafe.decision.after.safeAttempts, 1)
    assertEquals(unsafe.decision.after.rejections.unsafeAccumulated, 1)
    assertEquals(unsafe.decision.after.rejections.total, 2)

  test("non-positive prediction is distinct and attempt termination counts rejections"):
    val first = evaluate(current = 10.0, candidate = 9.0, predicted = 0.0)
    assertEquals(first.decision.rejection, Some(TrustRejection.NonPositivePredictedDrop))
    val second = TrustModel.evaluate(
      first.decision.after,
      TrustAttempt("current", "candidate", 10.0, 11.0, 1.0, localSafe = true, accumulatedSafe = true),
      config
    )
    assertEquals(second.decision.rejection, Some(TrustRejection.NonPositiveActualDrop))
    val third = TrustModel.evaluate(
      second.decision.after,
      TrustAttempt("current", "candidate", 10.0, Double.NaN, 1.0, localSafe = true, accumulatedSafe = true),
      config
    )
    val fourth = TrustModel.evaluate(
      third.decision.after,
      TrustAttempt("current", "candidate", 10.0, 9.9, 2.0, localSafe = true, accumulatedSafe = true),
      config
    )
    assertEquals(fourth.decision.after.terminal(config), Some(TrustTermination.AttemptBudget))
    assertEquals(fourth.decision.after.acceptedSteps, 0)
    assertEquals(fourth.decision.after.attempts, 4)

  test("accepted-step and attempt counters terminate independently"):
    val first = evaluate(current = 10.0, candidate = 9.0, predicted = 1.0)
    val second = TrustModel.evaluate(
      first.decision.after,
      TrustAttempt("candidate", "second", 9.0, 8.0, 1.0, localSafe = true, accumulatedSafe = true),
      config
    )
    assertEquals(second.decision.after.terminal(config), Some(TrustTermination.AcceptedStepBudget))
    assertEquals(second.decision.after.attempts, 2)
    assertEquals(second.decision.after.acceptedSteps, 2)

  private def evaluate(current: Double, candidate: Double, predicted: Double): TrustOutcome[String] =
    TrustModel.evaluate(
      TrustState.initial(config),
      TrustAttempt("current", "candidate", current, candidate, predicted, localSafe = true, accumulatedSafe = true),
      config
    )

  private def right[L, R](value: Either[L, R]): R =
    value match
      case Right(result) => result
      case Left(error) => fail(error.toString)
