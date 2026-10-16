package reframe4s.benchmark.flashalign

import java.nio.file.Paths

final class CrossMethodEvidenceSuite extends munit.FunSuite:
  test("C0-C7 preserve denominator and distinguish complete from residual maps"):
    val pair = fixture()
    val plan = courtPlan()
    var loads = 0
    val loader = new PairInputLoader[String]:
      def load(value: PairCase): Either[EvidenceError, LoadedInput[String]] =
        assertEquals(value.id, pair.id)
        loads += 1
        Right(LoadedInput("shared-input", 2.0))
    val adapters = plan.lanes.filter(_.unavailableReason.isEmpty).map { lane =>
      val map = lane.mapInterpretation match
        case MapInterpretation.CompleteMovingToFixed => translationMap(2.0)
        case MapInterpretation.ResidualAfterSuppliedAffine => translationMap(1.0)
      successfulAdapter(lane, map)
    }
    val court = CrossMethodEvidenceRunner.run(
      "cross-method-analytic-v1",
      plan,
      pair,
      loader,
      adapters,
      sequenceClock(Vector(0L, 5000000L))
    ).fold(error => fail(error.message), identity)

    assertEquals(loads, 1)
    assertEquals(court.sharedInputCost.loadInvocations, 1)
    assertEqualsDouble(court.sharedInputCost.readAndHashMs, 3.0, 1e-12)
    assertEqualsDouble(court.sharedInputCost.decompressMs, 2.0, 1e-12)
    assertEquals(court.rows.size, 8)
    assertEquals(court.rows.map(_.lane), ComparisonLane.All)
    assertEquals(court.rows.count(_.status == RunStatus.Unavailable), 1)
    assertEquals(court.rows.last.lane, ComparisonLane.C7ReverseTransfer)
    assert(court.rows.last.failure.exists(_.kind == "unavailable-lane"), court.rows.last.failure)

    val complete = court.rows.find(_.lane == ComparisonLane.C1FlashalignLinear).get
    val residual = court.rows.find(_.lane == ComparisonLane.C2FlashalignSmallStrain).get
    assertEqualsDouble(complete.metrics.landmarkRmsMm.get, 0.0, 1e-12)
    assertEqualsDouble(residual.metrics.landmarkRmsMm.get, 0.0, 1e-12)
    assertEquals(complete.serializedMapSha256, complete.effectiveMapSha256)
    assertNotEquals(residual.serializedMapSha256, residual.effectiveMapSha256)
    assertEquals(residual.metrics.candidateMetrics("method_loss"), Some(999.0))
    assertEqualsDouble(residual.costs.coldStandaloneInputAndPreparationMs, 9.0, 1e-12)
    assertEqualsDouble(residual.costs.sharedPreparationMs, 1.0, 1e-12)
    val json = CrossMethodEvidenceJson.render(court)
    Vector(
      "\"lane\":\"C0\"",
      "\"lane\":\"C7\"",
      "\"inputs\":{\"moving\":",
      "\"initialization\":{\"id\":\"common-affine-v1\"",
      "\"landmarks\":[{\"id\":\"a\"",
      "\"map_interpretation\":\"residual-after-supplied-affine\"",
      "\"candidate_secondary\":{\"method_loss\":999.0}",
      "\"cold_standalone_input_and_preparation_ms\":9.0",
      "\"shared_preparation_ms\":1.0",
      "\"load_invocations\":1",
      "\"status\":\"unavailable\""
    ).foreach(fragment => assert(json.contains(fragment), clues(json, fragment)))

  test("method objectives cannot be declared independent ground truth"):
    val invalid = courtPlan().copy(
      scorePolicy = IndependentScorePolicy(Vector("method_loss"), Vector.empty, candidateLossIsGroundTruth = true)
    )
    CrossMethodPlan.validate(invalid) match
      case Left(error) => assert(error.message.contains("cannot be independent ground truth"), error.message)
      case Right(_)    => fail("candidate loss was admitted as ground truth")

  test("missing available adapter fails before execution"):
    val pair = fixture()
    val plan = courtPlan()
    CrossMethodEvidenceRunner.run(
      "missing-adapter-v1",
      plan,
      pair,
      (_: PairCase) => Right(LoadedInput("unused", 0.0)),
      Vector.empty
    ) match
      case Left(error) => assert(error.message.contains("requires exactly one adapter"), error.message)
      case Right(_)    => fail("court ran without required method adapters")

  private val identityMatrix = Vector(
    1.0, 0.0, 0.0, 0.0,
    0.0, 1.0, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
  )

  private def fixture(): PairCase =
    val initial = translationMatrix(1.0)
    PairCase(
      "cross-method-analytic-pair",
      "analytic-subject-1",
      PairCohort.OrdinarySameSubject,
      LicensedArtifact(Paths.get("moving.synthetic"), hash("moving"), "analytic", "Apache-2.0"),
      LicensedArtifact(Paths.get("fixed.synthetic"), hash("fixed"), "analytic", "Apache-2.0"),
      InitializationIdentity("common-affine-v1", initial, EvidenceHash.matrix(initial)),
      hash("immutable-samples"),
      Vector(
        landmark("a", Vector(0.0, 0.0, 0.0)),
        landmark("b", Vector(3.0, -2.0, 4.0)),
        landmark("c", Vector(-4.0, 5.0, 1.0))
      )
    )

  private def courtPlan(): CrossMethodPlan =
    val residual = Set(
      ComparisonLane.C2FlashalignSmallStrain,
      ComparisonLane.C3HalfFlowCommonAffine,
      ComparisonLane.C5HalfFlowFlashalignInitialization,
      ComparisonLane.C6FlashalignThenHalfFlowResidual,
      ComparisonLane.C7ReverseTransfer
    )
    val lanes = ComparisonLane.All.map { lane =>
      LanePlan(
        lane,
        method(lane),
        if residual(lane) then MapInterpretation.ResidualAfterSuppliedAffine
        else MapInterpretation.CompleteMovingToFixed,
        LaneControls(
          s"objective-${lane.id}",
          "support-v1",
          "mask-v1",
          s"freedom-${lane.id}",
          "common-affine-v1",
          if Set(ComparisonLane.C4HalfFlowExistingPipeline, ComparisonLane.C5HalfFlowFlashalignInitialization,
              ComparisonLane.C6FlashalignThenHalfFlowResidual, ComparisonLane.C7ReverseTransfer)(lane)
          then Some(hash("frozen-recipient-controls")) else None
        ),
        if lane == ComparisonLane.C7ReverseTransfer then Some("no qualified reverse transfer") else None
      )
    }
    CrossMethodPlan(
      "analytic-c0-c7-v1",
      lanes,
      IndependentScorePolicy(
        Vector("landmark_rms_mm", "landmark_p95_mm"),
        Vector("method_loss"),
        candidateLossIsGroundTruth = false
      ),
      expectedRowsPerPair = 8
    )

  private def successfulAdapter(
      plan: LanePlan,
      map: EvidenceWorldMap3
  ): CrossMethodLaneAdapter[String] = new CrossMethodLaneAdapter[String]:
    val lane: ComparisonLane = plan.lane
    val identity: MethodIdentity = plan.method
    def run(pair: PairCase, inputs: String): Either[EvidenceError, CrossMethodCandidate] =
      assertEquals(pair.id, "cross-method-analytic-pair")
      assertEquals(inputs, "shared-input")
      val mapHash = hash(s"serialized-map-${lane.id}")
      Right(CrossMethodCandidate(
        RunStatus.Success,
        accepted = true,
        None,
        Some(map),
        Some(mapHash),
        Map("method_loss" -> Some(999.0)),
        WorkCounts(10L, 10L, 2L, 3L, 1L, 0L, 5L),
        LaneCostEvidence(9.0, 1.0, AlgorithmStageTimes(1.0, 0.0, 2.0, 1.0, 1.0), 4096L)
      ))

  private def translationMap(x: Double): EvidenceWorldMap3 = new EvidenceWorldMap3:
    def movingToFixed(worldMm: Vector[Double]): Either[String, Vector[Double]] =
      Right(Vector(worldMm(0) + x, worldMm(1), worldMm(2)))

  private def landmark(id: String, moving: Vector[Double]): LandmarkTruth =
    LandmarkTruth(id, moving, Vector(moving(0) + 2.0, moving(1), moving(2)))

  private def method(lane: ComparisonLane): MethodIdentity = MethodIdentity(
    s"method-${lane.id}",
    s"model-${lane.id}",
    "analytic-revision",
    hash(s"configuration-${lane.id}"),
    None,
    None
  )

  private def translationMatrix(x: Double): Vector[Double] =
    identityMatrix.updated(3, x)

  private def hash(value: String): Sha256 = EvidenceHash.utf8(value)

  private def sequenceClock(values: Vector[Long]): NanoClock = new NanoClock:
    private var index = 0
    def now(): Long =
      val value = values(index)
      index += 1
      value
