package reframe4s.flashalign

class AffineBudgetRecoverySuite extends munit.FunSuite:
  private val evidence = FlashalignFailureDiagnostics(
    FlashalignWorkCounts.Zero.copy(dataLinearizations = 3, uniqueInterpolations = 17L), Vector.empty, None, None)
  private val exhausted = FlashalignError.NoCaptureCandidateConverged(Vector("linearization limit"), evidence)

  test("successful primary fits and non-convergence-unrelated failures never execute a retry"):
    val value = new Object
    val primary = Right(value)
    val outcome = AffineBudgetRecovery(primary, Some("recovery"))(
      fail("a successful primary was retried")
    )((_, _, _) => fail("successful primary diagnostics were replaced"))
    assert(outcome eq primary)
    for error <- Vector[FlashalignError](FlashalignError.WorkspaceInUse,
      FlashalignError.WorkspacePlanMismatch, FlashalignError.AutomaticInitialization("capture failed"),
      FlashalignError.AutomaticLinearFailure(LinearEngineError.WorkspacePlanMismatch, evidence),
      FlashalignError.EngineUnavailable(FlashalignModel.Affine)) do
      val result = AffineBudgetRecovery[Object](Left(error), Some("recovery"))(
        fail("a non-retryable error was retried")
      )((_, _, _) => fail("unexpected successful retry"))
      assertEquals(result, Left(error))

  test("only an enabled complete capture failure retries, once, retaining the primary evidence"):
    var attempts = 0
    val disabled = AffineBudgetRecovery[Int](Left(exhausted), None)(
      { attempts += 1; Right(7) }
    )((v, _, _) => Right(v))
    assertEquals(disabled, Left(exhausted))
    assertEquals(attempts, 0)
    val recovered = AffineBudgetRecovery[Int](Left(exhausted), Some("recovery"))(
      { attempts += 1; Right(7) }
    ) { (value, previous, policy) =>
      assertEquals(previous, evidence)
      assertEquals(policy, "recovery")
      Right(value)
    }
    assertEquals(recovered, Right(7))
    assertEquals(attempts, 1)

  test("an exhausted recovery remains a failure and preserves both attempts"):
    val result = AffineBudgetRecovery[Int](Left(exhausted), Some("recovery"))(Left(exhausted))(
      (_, _, _) => fail("failed checkpoint admitted"))
    result match
      case Left(error: FlashalignError.AffineRecoveryFailed) =>
        assertEquals(error.primary, exhausted)
        assertEquals(error.recovery, exhausted)
        assertEquals(error.failureDiagnostics.get.previousAttempts, Vector(evidence))
        assertEquals(error.failureDiagnostics.get.recoveryPolicyId, Some("recovery"))
        assertEquals(error.failureDiagnostics.get.work.dataLinearizations, 6)
        assertEquals(error.failureDiagnostics.get.work.uniqueInterpolations, 34L)
      case other => fail(s"expected retained failure: $other")

  test("automatic within-modality defaults preserve the primary budget and bound recovery"):
    val automatic = FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    val config = FlashalignConfig.forPreset(FlashalignPreset.WithinModality, automatic)
    assertEquals(config.policy.affineTrust.maximumLinearizations, 100)
    assertEquals(config.affineRecoveryPolicy.map(_.affineTrust.maximumLinearizations), Some(400))
    assertEquals(config.policy.affineTrust.maximumTrialAttempts, 8)
    assertEquals(config.affineRecoveryPolicy.map(_.affineTrust.maximumTrialAttempts), Some(16))
    assertEquals(FlashalignConfig.create(config.policy, automatic).affineRecoveryPolicy, None)
    assertEquals(FlashalignConfig.forPreset(FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.SuppliedWorldTransform).affineRecoveryPolicy, None)
    for preset <- Vector(FlashalignPreset.EpiToT1, FlashalignPreset.EpiToT2, FlashalignPreset.SlabToAnatomy) do
      assertEquals(FlashalignConfig.forPreset(preset, automatic).affineRecoveryPolicy, None)
    intercept[IllegalArgumentException]:
      FlashalignConfig.create(config.policy, automatic, Some(config.policy))

  test("recovery can extend either budget but cannot loosen convergence or reduce another budget"):
    val base = LinearPresetPolicies.WithinModality
    val automatic = FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    def policy(trust: PresetTrustPolicy): LinearPresetPolicy =
      LinearPresetPolicy.create("test-recovery", "1.0.0", base.preset, base.patch, base.sampling,
        base.preparation, base.capture, base.rigidTrust, trust, base.qc, base.calibration)
        .fold(error => fail(error.message), identity)
    val trialsOnly = policy(base.affineTrust.copy(maximumTrialAttempts = 16))
    assertEquals(FlashalignConfig.create(base, automatic, Some(trialsOnly)).affineRecoveryPolicy, Some(trialsOnly))
    for trust <- Vector(
      base.affineTrust.copy(maximumLinearizations = 400, maximumTrialAttempts = 4),
      base.affineTrust.copy(maximumLinearizations = 50, maximumTrialAttempts = 16),
      base.affineTrust.copy(maximumLinearizations = 400, objectiveTolerance = 1e-4)
    ) do
      intercept[IllegalArgumentException](FlashalignConfig.create(base, automatic, Some(policy(trust))))
