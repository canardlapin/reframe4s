package reframe4s.flashalign

final class LinearPresetSuite extends munit.FunSuite:
  test("frozen presets expose distinct modality policy and a common calibration identity"):
    val policies = Vector(
      LinearPresetPolicies.EpiToT1,
      LinearPresetPolicies.EpiToT2,
      LinearPresetPolicies.WithinModality,
      LinearPresetPolicies.SlabToAnatomy
    )

    assertEquals(policies.map(_.id).distinct.size, policies.size)
    assert(policies.forall(_.version == LinearPresetPolicies.Version))
    assert(policies.forall(_.calibration.receiptId == LinearPresetPolicies.CalibrationReceiptId))
    assert(policies.forall(_.calibration.receiptSha256.matches("[0-9a-f]{64}")))
    assert(LinearPresetPolicies.EpiToT1.patch.positivePolarityPrior < 0.5)
    assert(LinearPresetPolicies.EpiToT2.patch.positivePolarityPrior > 0.5)
    assert(LinearPresetPolicies.WithinModality.patch.positivePolarityPrior > 0.99)
    assert(
      LinearPresetPolicies.SlabToAnatomy.sampling.optimizationDraws >
        LinearPresetPolicies.EpiToT1.sampling.optimizationDraws
    )

  test("every frozen preset is accepted by the component-level validators"):
    Vector(
      LinearPresetPolicies.EpiToT1,
      LinearPresetPolicies.EpiToT2,
      LinearPresetPolicies.WithinModality,
      LinearPresetPolicies.SlabToAnatomy
    ).foreach(assertCompiles)

  test("custom policy validation rejects consequential invalid controls"):
    val valid = LinearPresetPolicies.EpiToT1
    createLike(valid, patch = valid.patch.copy(tau = Double.NaN)) match
      case Left(LinearPresetError.InvalidScalar("patch.tau", value, _)) => assert(value.isNaN)
      case other => fail(s"expected typed tau failure, got $other")

    createLike(
      valid,
      sampling = valid.sampling.copy(selectionFraction = 0.6, auditFraction = 0.5)
    ) match
      case Left(LinearPresetError.InvalidRelation(message)) =>
        assert(message.contains("optimization mass"), message)
      case other => fail(s"expected role-fraction failure, got $other")

    createLike(
      valid,
      preparation = valid.preparation.copy(
        effectiveResolutionMillimetres = Vector(3.0, 6.0),
        stencilSpacingMillimetres = Vector(3.0, 6.0)
      )
    ) match
      case Left(LinearPresetError.InvalidRelation(message)) =>
        assert(message.contains("coarse-to-fine"), message)
      case other => fail(s"expected resolution-order failure, got $other")

    createLike(
      valid,
      calibration = valid.calibration.copy(receiptSha256 = "unsealed")
    ) match
      case Left(LinearPresetError.InvalidSha256("calibration.receiptSha256", "unsealed")) => ()
      case other => fail(s"expected receipt-hash failure, got $other")

  private def assertCompiles(policy: LinearPresetPolicy): Unit =
    val patch = PatchObjectiveConfig.create(
      policy.patch.positivePolarityPrior,
      policy.patch.tau,
      policy.patch.outlierFloor,
      policy.patch.minimumContrastEnergy,
      policy.patch.correlationRoundingTolerance
    )
    assert(patch.isRight, patch)

    val sampling = PatchSamplingConfig.create(
      policy.sampling.selectionFraction,
      policy.sampling.auditFraction,
      policy.sampling.uniformProbabilityMixture,
      policy.sampling.optimizationDraws,
      policy.sampling.selectionDraws,
      policy.sampling.auditDraws,
      policy.sampling.seed
    )
    assert(sampling.isRight, sampling)

    policy.preparation.stencilSpacingMillimetres.foreach { spacing =>
      val population = PatchPopulationConfig.create(
        spacing,
        policy.preparation.worldCellSizeMillimetres,
        targetCandidateCount = policy.preparation.targetCandidatePatches,
        maximumCentersToScreen = policy.preparation.maximumCentersToScreen,
        maximumCandidatesPerCell = policy.preparation.maximumCandidatesPerCell
      )
      assert(population.isRight, population)
    }

    val capture = StructuralCaptureConfig3.create(
      policy.capture.stages.map(stage =>
        CaptureRotationStage3(stage.maximumAngleDegrees, stage.stepDegrees)
      ),
      policy.capture.seed,
      policy.capture.maximumRotations,
      policy.capture.maximumRetainedCandidates,
      policy.capture.peaksPerRotation,
      policy.capture.minimumCandidatesToStop,
      policy.capture.minimumOverlapFraction,
      policy.capture.minimumStructuralEnergy,
      policy.capture.minimumStructuralScore,
      minimumPeakSeparationMillimetres = policy.capture.minimumPeakSeparationMillimetres,
      minimumCandidateDisplacementMillimetres = policy.capture.minimumCandidateDisplacementMillimetres
    )
    assert(capture.isRight, capture)

    Vector(6 -> policy.rigidTrust, 12 -> policy.affineTrust).foreach { case (parameters, trust) =>
      val optimizer = ProjectedPatchOptimizerConfig.create(
        parameterCount = parameters,
        maximumLinearizations = trust.maximumLinearizations,
        maximumTrialAttempts = trust.maximumTrialAttempts,
        initialDamping = trust.initialDamping,
        minimumDamping = trust.minimumDamping,
        maximumDamping = trust.maximumDamping,
        trustRadiusRms = trust.trustRadiusRmsMillimetres,
        maximumDisplacement = trust.maximumDisplacementMillimetres,
        acceptanceRatio = trust.acceptanceRatio,
        objectiveTolerance = trust.objectiveTolerance,
        gradientTolerance = trust.gradientTolerance,
        stepToleranceRms = trust.stepToleranceRmsMillimetres,
        conditionLimit = trust.conditionLimit
      )
      assert(optimizer.isRight, optimizer)
    }

    val qc = LinearQcConfig3.create(
      policy.qc.minimumPatchInlierWeight,
      policy.qc.minimumOverlapFraction,
      policy.qc.minimumSpatialInlierCoverage,
      maximumDataConditionNumber = policy.qc.maximumDataConditionNumber,
      maximumRegionalRefitDisplacementMillimetres =
        policy.qc.maximumRegionalRefitDisplacementMillimetres,
      nearEqualObjectiveTolerance = policy.qc.nearEqualObjectiveTolerance
    )
    assert(qc.isRight, qc)

  private def createLike(
      value: LinearPresetPolicy,
      patch: PresetPatchPolicy = LinearPresetPolicies.EpiToT1.patch,
      sampling: PresetSamplingPolicy = LinearPresetPolicies.EpiToT1.sampling,
      preparation: PresetPreparationPolicy = LinearPresetPolicies.EpiToT1.preparation,
      capture: PresetCapturePolicy = LinearPresetPolicies.EpiToT1.capture,
      rigidTrust: PresetTrustPolicy = LinearPresetPolicies.EpiToT1.rigidTrust,
      affineTrust: PresetTrustPolicy = LinearPresetPolicies.EpiToT1.affineTrust,
      qc: PresetQcPolicy = LinearPresetPolicies.EpiToT1.qc,
      calibration: PresetCalibrationIdentity = LinearPresetPolicies.EpiToT1.calibration
  ): Either[LinearPresetError, LinearPresetPolicy] =
    LinearPresetPolicy.create(
      value.id,
      value.version,
      value.preset,
      patch,
      sampling,
      preparation,
      capture,
      rigidTrust,
      affineTrust,
      qc,
      calibration
    )
