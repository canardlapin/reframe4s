package reframe4s.benchmark.flashalign

import java.nio.file.Paths

final class FlashalignHalfFlowResidualSuite extends munit.FunSuite:
  test("downstream failure remains C5 while the completed Flashalign affine remains C1"):
    val plan = transferPlan()
    val pair = fixture()
    var executions = 0
    val pipeline = new FlashalignHalfFlowPipeline[String]:
      def run(
          observed: PairCase,
          inputs: String
      ): Either[EvidenceError, FlashalignHalfFlowCandidates] =
        executions += 1
        assertEquals(observed.id, pair.id)
        assertEquals(inputs, "shared-input")
        Right(
          FlashalignHalfFlowCandidates(
            success(translationMap(2.0), "flashalign-affine", output = false),
            failure(
              RunStatus.InvalidGeometry,
              "halfflow-fine",
              "deliberate downstream topology failure"
            )
          )
        )
    val adapters = FlashalignHalfFlowAdapters
      .make(
        lane(plan, ComparisonLane.C1FlashalignLinear),
        lane(plan, ComparisonLane.C5HalfFlowFlashalignInitialization),
        pipeline
      )
      .fold(error => fail(error.message), identity)
    val court = run(plan, pair, adapters)

    assertEquals(executions, 1)
    val affine = row(court, ComparisonLane.C1FlashalignLinear)
    val downstream = row(court, ComparisonLane.C5HalfFlowFlashalignInitialization)
    assertEquals(affine.status, RunStatus.Success)
    assertEquals(affine.accepted, true)
    assertEqualsDouble(affine.metrics.landmarkRmsMm.get, 0.0, 1e-12)
    assert(affine.serializedMapSha256.nonEmpty)
    assertEquals(downstream.status, RunStatus.InvalidGeometry)
    assertEquals(downstream.accepted, false)
    assertEquals(downstream.serializedMapSha256, None)
    assert(
      downstream.failure.exists(value =>
        value.kind == "halfflow-fine" &&
          value.message == "deliberate downstream topology failure"
      ),
      downstream.failure
    )

  test("C5 complete endpoint map is scored without composing the initializer twice"):
    val plan = transferPlan()
    val pair = fixture()
    val pipeline = new FlashalignHalfFlowPipeline[String]:
      def run(
          observed: PairCase,
          inputs: String
      ): Either[EvidenceError, FlashalignHalfFlowCandidates] =
        Right(
          FlashalignHalfFlowCandidates(
            success(translationMap(2.0), "flashalign-affine", output = false),
            success(translationMap(2.0), "halfflow-complete", output = true)
          )
        )
    val adapters = FlashalignHalfFlowAdapters
      .make(
        lane(plan, ComparisonLane.C1FlashalignLinear),
        lane(plan, ComparisonLane.C5HalfFlowFlashalignInitialization),
        pipeline
      )
      .fold(error => fail(error.message), identity)
    val court = run(plan, pair, adapters)
    val downstream = row(court, ComparisonLane.C5HalfFlowFlashalignInitialization)

    assertEquals(downstream.mapInterpretation, MapInterpretation.CompleteMovingToFixed)
    assertEqualsDouble(downstream.metrics.landmarkRmsMm.get, 0.0, 1e-12)
    assertEquals(downstream.serializedMapSha256, downstream.effectiveMapSha256)
    assertEquals(downstream.metrics.candidateMetrics("output_resamplings"), Some(1.0))

  test("residual C5 declaration is rejected before executing the handoff"):
    val plan = transferPlan()
    val residual = lane(plan, ComparisonLane.C5HalfFlowFlashalignInitialization)
      .copy(mapInterpretation = MapInterpretation.ResidualAfterSuppliedAffine)
    var executed = false
    val pipeline = new FlashalignHalfFlowPipeline[String]:
      def run(
          pair: PairCase,
          inputs: String
      ): Either[EvidenceError, FlashalignHalfFlowCandidates] =
        executed = true
        Left(EvidenceError.AdapterFailure("must not execute"))

    FlashalignHalfFlowAdapters.make(
      lane(plan, ComparisonLane.C1FlashalignLinear),
      residual,
      pipeline
    ) match
      case Left(error) => assert(error.message.contains("apply the supplied affine twice"))
      case Right(_)    => fail("residual C5 was accepted")
    assertEquals(executed, false)

  private def run(
      plan: CrossMethodPlan,
      pair: PairCase,
      adapters: Vector[CrossMethodLaneAdapter[String]]
  ): CrossMethodCourt =
    CrossMethodEvidenceRunner
      .run(
        "flashalign-halfflow-handoff-test",
        plan,
        pair,
        (_: PairCase) => Right(LoadedInput("shared-input", 0.0)),
        adapters
      )
      .fold(error => fail(error.message), identity)

  private def transferPlan(): CrossMethodPlan =
    val lanes = ComparisonLane.All.map: comparison =>
      val available = Set(
        ComparisonLane.C1FlashalignLinear,
        ComparisonLane.C5HalfFlowFlashalignInitialization
      )(comparison)
      LanePlan(
        comparison,
        MethodIdentity(
          s"method-${comparison.id}",
          s"model-${comparison.id}",
          "test-revision",
          EvidenceHash.utf8(s"configuration-${comparison.id}"),
          None,
          None
        ),
        MapInterpretation.CompleteMovingToFixed,
        LaneControls(
          s"objective-${comparison.id}",
          "support-v1",
          "mask-v1",
          s"freedom-${comparison.id}",
          if comparison == ComparisonLane.C5HalfFlowFlashalignInitialization then
            "flashalign-affine"
          else "none",
          if comparison == ComparisonLane.C5HalfFlowFlashalignInitialization then
            Some(EvidenceHash.utf8("frozen-recipient-controls"))
          else None
        ),
        if available then None else Some("outside narrow C1/C5 test")
      )
    CrossMethodPlan(
      "flashalign-halfflow-handoff-test-v1",
      lanes,
      IndependentScorePolicy(
        Vector("landmark_rms_mm"),
        Vector("output_resamplings"),
        candidateLossIsGroundTruth = false
      ),
      ComparisonLane.All.size
    )

  private def fixture(): PairCase =
    val initialization = translationMatrix(1.0)
    PairCase(
      "handoff-pair",
      "subject-1",
      PairCohort.OrdinarySameSubject,
      LicensedArtifact(Paths.get("moving.synthetic"), hash("moving"), "analytic", "Apache-2.0"),
      LicensedArtifact(Paths.get("fixed.synthetic"), hash("fixed"), "analytic", "Apache-2.0"),
      InitializationIdentity(
        "common-affine",
        initialization,
        EvidenceHash.matrix(initialization)
      ),
      hash("samples"),
      Vector(
        landmark("a", Vector(0.0, 0.0, 0.0)),
        landmark("b", Vector(2.0, -3.0, 4.0)),
        landmark("c", Vector(-4.0, 1.0, 3.0))
      )
    )

  private def success(
      map: EvidenceWorldMap3,
      label: String,
      output: Boolean
  ): CrossMethodCandidate =
    CrossMethodCandidate(
      RunStatus.Success,
      accepted = true,
      None,
      Some(map),
      Some(hash(label)),
      Map("output_resamplings" -> Some(if output then 1.0 else 0.0)),
      WorkCounts.Zero,
      LaneCostEvidence(
        0.0,
        0.0,
        AlgorithmStageTimes(1.0, 2.0, 3.0, 4.0, if output then 5.0 else 0.0),
        0L
      )
    )

  private def failure(
      status: RunStatus,
      kind: String,
      message: String
  ): CrossMethodCandidate =
    CrossMethodCandidate(
      status,
      accepted = false,
      Some(FailureDetail(kind, message)),
      None,
      None,
      Map("output_resamplings" -> Some(0.0)),
      WorkCounts.Zero,
      LaneCostEvidence.Zero
    )

  private def row(
      court: CrossMethodCourt,
      lane: ComparisonLane
  ): CrossMethodLaneRecord = court.rows.find(_.lane == lane).get

  private def lane(plan: CrossMethodPlan, lane: ComparisonLane): LanePlan =
    plan.lanes.find(_.lane == lane).get

  private def translationMap(delta: Double): EvidenceWorldMap3 =
    (worldMm: Vector[Double]) =>
      Right(Vector(worldMm(0) + delta, worldMm(1), worldMm(2)))

  private def landmark(id: String, moving: Vector[Double]): LandmarkTruth =
    LandmarkTruth(id, moving, Vector(moving(0) + 2.0, moving(1), moving(2)))

  private def translationMatrix(delta: Double): Vector[Double] = Vector(
    1.0, 0.0, 0.0, delta,
    0.0, 1.0, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
  )

  private def hash(value: String): Sha256 = EvidenceHash.utf8(value)
