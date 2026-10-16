package reframe4s.benchmark.flashalign

import java.nio.file.Paths

final class PairBenchmarkRunnerSuite extends munit.FunSuite:
  test("analytic pair scores world landmarks independently and renders complete evidence"):
    val pair = fixture()
    val candidateMatrix = translation(2.0, -1.0, 0.5)
    val adapter = successfulAdapter[String](candidateMatrix, Map("projected_patch_loss" -> Some(999.0)))
    val result = PairBenchmarkRunner
      .run(
        "analytic-run-1",
        pair,
        (_: PairCase) => Right(LoadedInput("loaded-analytic-fixture", 0.5)),
        adapter,
        environment,
        sequenceClock(Vector(0L, 1000000L, 3000000L, 12000000L))
      )
      .fold(error => fail(error.message), identity)

    assertEqualsDouble(result.metrics.landmarkRmsMm.getOrElse(fail("missing RMS")), 0.0, 1e-12)
    assertEquals(result.metrics.candidateMetrics("projected_patch_loss"), Some(999.0))
    assertEqualsDouble(result.timingMs.read, 1.5, 1e-12)
    assertEqualsDouble(result.timingMs.decompress, 0.5, 1e-12)
    assertEqualsDouble(result.timingMs.prepare, 1.0, 1e-12)
    assertEqualsDouble(result.timingMs.capture, 2.0, 1e-12)
    assertEqualsDouble(result.timingMs.optimize, 3.0, 1e-12)
    assertEqualsDouble(result.timingMs.validate, 4.0, 1e-12)
    assertEqualsDouble(result.timingMs.output, 5.0, 1e-12)
    assertEqualsDouble(result.timingMs.total, 17.0, 1e-12)
    val json = EvidenceJson.render(result)
    Vector(
      "\"provenance\"",
      "\"initialization\"",
      "\"result\"",
      "\"unique_interpolations\":42",
      "\"read\":1.5",
      "\"decompress\":0.5",
      "\"prepare\":1.0",
      "\"capture\":2.0",
      "\"optimize\":3.0",
      "\"validate\":4.0",
      "\"output\":5.0"
    ).foreach(fragment => assert(json.contains(fragment), clues(json, fragment)))

  test("loader failure remains a complete unavailable record in the denominator"):
    val pair = fixture()
    val result = PairBenchmarkRunner
      .run(
        "failed-input-run",
        pair,
        (_: PairCase) => Left[EvidenceError, LoadedInput[String]](
          EvidenceError.InputFailure("moving", "deliberate")
        ),
        successfulAdapter[String](identityMatrix, Map.empty),
        environment,
        sequenceClock(Vector(0L, 0L, 1L, 1L))
      )
      .fold(error => fail(error.message), identity)
    assertEquals(result.candidate.status, RunStatus.Unavailable)
    assertEquals(result.candidate.accepted, false)
    assert(result.candidate.failure.exists(_.kind == "input"), result.candidate.failure)
    assertEquals(result.metrics.landmarkRmsMm, None)
    assert(EvidenceJson.render(result).contains("\"status\":\"unavailable\""))

  test("failed candidate retains a separately hashed last checkpoint"):
    val pair = fixture()
    val checkpointMatrix = translation(1.5, -0.75, 0.25)
    val lastValidMatrix = translation(1.75, -0.5, 0.125)
    val checkpoint = FailureCheckpoint(
      checkpointMatrix,
      EvidenceHash.matrix(checkpointMatrix),
      lastValidMatrix,
      EvidenceHash.matrix(lastValidMatrix),
      selectionObjective = 0.42,
      acceptedSteps = 3,
      selectionObjectiveId = 917L,
      initialOptimizationObjective = 1.2,
      lastOptimizationObjective = 0.5
    )
    val adapter = new PairMethodAdapter[Unit]:
      val identity = method
      def run(pair: PairCase, inputs: Unit): Either[EvidenceError, CandidateRun] =
        val _ = pair
        val _ = inputs
        Right(
          CandidateRun(
            RunStatus.NumericalFailure,
            accepted = false,
            failure = Some(
              FailureDetail(
                "flashalign",
                "TrialDataObjective failed: deliberate provider error"
              )
            ),
            movingToFixed = None,
            transformSha256 = None,
            candidateMetrics = Map.empty,
            work = WorkCounts(80L, 40L, 2L, 4L, 4L, 0L, 0L),
            timingMs = AlgorithmStageTimes(1.0, 2.0, 3.0, 0.0, 0.0),
            lastCheckpoint = Some(checkpoint)
          )
        )
    val result = PairBenchmarkRunner
      .run(
        "failed-checkpoint-run",
        pair,
        (_: PairCase) => Right(LoadedInput((), 0.0)),
        adapter,
        environment
      )
      .fold(error => fail(error.message), identity)

    assertEquals(result.metrics.landmarkRmsMm, None)
    assertEquals(result.candidate.lastCheckpoint, Some(checkpoint))
    assertEquals(result.candidate.movingToFixed, None)
    assertEquals(result.candidate.status, RunStatus.NumericalFailure)
    val json = EvidenceJson.render(result)
    assert(json.contains("\"schema_version\":\"1.1.0\""), json)
    assert(json.contains("\"last_checkpoint\":{"), json)
    assert(json.contains(s"\"sha256\":\"${checkpoint.transformSha256.hex}\""), json)
    assert(
      json.contains(
        s"\"last_valid_sha256\":\"${checkpoint.lastValidTransformSha256.hex}\""
      ),
      json
    )
    assert(
      json.contains("\"result\":{\"moving_to_fixed\":null,\"sha256\":null}"),
      json
    )

  test("provenance initialization identity and result hashes fail closed"):
    val base = fixture()
    PairBenchmarkRunner.run(
      "bad-license",
      base.copy(moving = base.moving.copy(license = "")),
      (_: PairCase) => Right(LoadedInput((), 0.0)),
      successfulAdapter[Unit](identityMatrix, Map.empty),
      environment
    ) match
      case Left(EvidenceError.EmptyField("moving.license")) => ()
      case other => fail(s"expected license failure, got $other")

    PairBenchmarkRunner.run(
      "bad-init",
      base.copy(initialization = base.initialization.copy(sha256 = hash("wrong-init"))),
      (_: PairCase) => Right(LoadedInput((), 0.0)),
      successfulAdapter[Unit](identityMatrix, Map.empty),
      environment
    ) match
      case Left(EvidenceError.InputHashMismatch("initialization", _, _)) => ()
      case other => fail(s"expected initialization hash failure, got $other")

    val wrongHashAdapter = new PairMethodAdapter[Unit]:
      val identity = method
      def run(pair: PairCase, inputs: Unit): Either[EvidenceError, CandidateRun] =
        val _ = pair
        val _ = inputs
        Right(successCandidate(translation(2.0, -1.0, 0.5)).copy(transformSha256 = Some(hash("bad"))))
    PairBenchmarkRunner.run(
      "bad-result",
      base,
      (_: PairCase) => Right(LoadedInput((), 0.0)),
      wrongHashAdapter,
      environment
    ) match
      case Left(EvidenceError.InvalidCandidate(message)) => assert(message.contains("hash"))
      case other => fail(s"expected result hash failure, got $other")

    val badCheckpointAdapter = new PairMethodAdapter[Unit]:
      val identity = method
      def run(pair: PairCase, inputs: Unit): Either[EvidenceError, CandidateRun] =
        val _ = pair
        val _ = inputs
        Right(
          CandidateRun(
            RunStatus.Stalled,
            accepted = false,
            failure = Some(FailureDetail("deliberate", "bad checkpoint")),
            movingToFixed = None,
            transformSha256 = None,
            candidateMetrics = Map.empty,
            work = WorkCounts.Zero,
            timingMs = AlgorithmStageTimes.Zero,
            lastCheckpoint = Some(
              FailureCheckpoint(
                identityMatrix,
                hash("wrong-checkpoint"),
                identityMatrix,
                EvidenceHash.matrix(identityMatrix),
                1.0,
                0,
                1L,
                1.0,
                1.0
              )
            )
          )
        )
    PairBenchmarkRunner.run(
      "bad-checkpoint",
      base,
      (_: PairCase) => Right(LoadedInput((), 0.0)),
      badCheckpointAdapter,
      environment
    ) match
      case Left(EvidenceError.InvalidCandidate(message)) =>
        assert(message.contains("checkpoint"))
      case other => fail(s"expected checkpoint failure, got $other")

  private val identityMatrix = Vector(
    1.0, 0.0, 0.0, 0.0,
    0.0, 1.0, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
  )

  private val method = MethodIdentity(
    "flashalign-rigid",
    "rigid",
    "test-revision",
    hash("method-config"),
    None,
    None
  )

  private val environment = EnvironmentRecord("test-os", "test-cpu", "test-jdk", 1)

  private def fixture(): PairCase =
    val initial = identityMatrix
    PairCase(
      id = "analytic-pair",
      subjectId = "synthetic-subject-1",
      cohort = PairCohort.OrdinarySameSubject,
      moving = LicensedArtifact(Paths.get("moving.synthetic"), hash("moving"), "analytic-v1", "Apache-2.0"),
      fixed = LicensedArtifact(Paths.get("fixed.synthetic"), hash("fixed"), "analytic-v1", "Apache-2.0"),
      initialization = InitializationIdentity("identity-v1", initial, EvidenceHash.matrix(initial)),
      immutableSampleIdsSha256 = hash("sample-ids"),
      landmarks = Vector(
        landmark("a", Vector(0.0, 0.0, 0.0)),
        landmark("b", Vector(3.0, -2.0, 4.0)),
        landmark("c", Vector(-4.0, 5.0, 1.0))
      )
    )

  private def landmark(id: String, moving: Vector[Double]): LandmarkTruth =
    LandmarkTruth(
      id,
      moving,
      Vector(moving(0) + 2.0, moving(1) - 1.0, moving(2) + 0.5)
    )

  private def successfulAdapter[A](
      matrix: Vector[Double],
      metrics: Map[String, Option[Double]]
  ): PairMethodAdapter[A] = new PairMethodAdapter[A]:
    val identity = method
    def run(pair: PairCase, inputs: A): Either[EvidenceError, CandidateRun] =
      val _ = pair
      val _ = inputs
      Right(successCandidate(matrix).copy(candidateMetrics = metrics))

  private def successCandidate(matrix: Vector[Double]): CandidateRun =
    CandidateRun(
      RunStatus.Success,
      accepted = true,
      failure = None,
      movingToFixed = Some(matrix),
      transformSha256 = Some(EvidenceHash.matrix(matrix)),
      candidateMetrics = Map.empty,
      work = WorkCounts(42L, 42L, 3L, 5L, 2L, 1L, 0L),
      timingMs = AlgorithmStageTimes(1.0, 2.0, 3.0, 4.0, 5.0)
    )

  private def translation(x: Double, y: Double, z: Double): Vector[Double] =
    Vector(
      1.0, 0.0, 0.0, x,
      0.0, 1.0, 0.0, y,
      0.0, 0.0, 1.0, z,
      0.0, 0.0, 0.0, 1.0
    )

  private def hash(value: String): Sha256 = EvidenceHash.utf8(value)

  private def sequenceClock(values: Vector[Long]): NanoClock = new NanoClock:
    private var index = 0
    def now(): Long =
      val value = values(index)
      index += 1
      value
