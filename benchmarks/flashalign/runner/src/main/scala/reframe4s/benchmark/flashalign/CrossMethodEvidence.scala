package reframe4s.benchmark.flashalign

import scala.util.control.NonFatal

enum ComparisonLane(val id: String) derives CanEqual:
  case C0CommonAffine extends ComparisonLane("C0")
  case C1FlashalignLinear extends ComparisonLane("C1")
  case C2FlashalignSmallStrain extends ComparisonLane("C2")
  case C3HalfFlowCommonAffine extends ComparisonLane("C3")
  case C4HalfFlowExistingPipeline extends ComparisonLane("C4")
  case C5HalfFlowFlashalignInitialization extends ComparisonLane("C5")
  case C6FlashalignThenHalfFlowResidual extends ComparisonLane("C6")
  case C7ReverseTransfer extends ComparisonLane("C7")

object ComparisonLane:
  val All: Vector[ComparisonLane] = ComparisonLane.values.toVector

enum MapInterpretation(val id: String) derives CanEqual:
  case CompleteMovingToFixed extends MapInterpretation("complete-moving-to-fixed")
  case ResidualAfterSuppliedAffine extends MapInterpretation("residual-after-supplied-affine")

final case class LaneControls(
    objectiveId: String,
    supportPolicyId: String,
    maskPolicyId: String,
    modelFreedomId: String,
    initializationSourceId: String,
    frozenRecipientControlsSha256: Option[Sha256]
)

final case class LanePlan(
    lane: ComparisonLane,
    method: MethodIdentity,
    mapInterpretation: MapInterpretation,
    controls: LaneControls,
    unavailableReason: Option[String]
)

final case class IndependentScorePolicy(
    primaryMetrics: Vector[String],
    secondaryCandidateMetrics: Vector[String],
    candidateLossIsGroundTruth: Boolean
)

final case class CrossMethodPlan(
    id: String,
    lanes: Vector[LanePlan],
    scorePolicy: IndependentScorePolicy,
    expectedRowsPerPair: Int
)

object CrossMethodPlan:
  def validate(plan: CrossMethodPlan): Either[CrossMethodEvidenceError, CrossMethodPlan] =
    val laneSet = plan.lanes.map(_.lane).toSet
    val duplicateLane = plan.lanes.groupBy(_.lane).collectFirst { case (lane, values) if values.size != 1 => lane }
    val emptyControl = plan.lanes.collectFirst {
      case lane if Vector(
            lane.method.id,
            lane.method.model,
            lane.method.revision,
            lane.controls.objectiveId,
            lane.controls.supportPolicyId,
            lane.controls.maskPolicyId,
            lane.controls.modelFreedomId,
            lane.controls.initializationSourceId
          ).exists(_.trim.isEmpty) => lane.lane
    }
    val emptyUnavailable = plan.lanes.collectFirst {
      case lane if lane.unavailableReason.exists(_.trim.isEmpty) => lane.lane
    }
    if plan.id.trim.isEmpty then Left(CrossMethodEvidenceError.InvalidPlan("plan id is empty"))
    else if duplicateLane.nonEmpty then
      Left(CrossMethodEvidenceError.InvalidPlan(s"duplicate lane ${duplicateLane.get.id}"))
    else if laneSet != ComparisonLane.All.toSet then
      val missing = ComparisonLane.All.filterNot(laneSet).map(_.id).mkString(",")
      Left(CrossMethodEvidenceError.InvalidPlan(s"C0-C7 must each occur exactly once; missing=$missing"))
    else if plan.expectedRowsPerPair != ComparisonLane.All.size then
      Left(CrossMethodEvidenceError.InvalidPlan("expected rows must equal the complete C0-C7 denominator"))
    else if plan.scorePolicy.primaryMetrics.isEmpty then
      Left(CrossMethodEvidenceError.InvalidPlan("independent primary metrics are empty"))
    else if plan.scorePolicy.candidateLossIsGroundTruth then
      Left(CrossMethodEvidenceError.InvalidPlan("a method objective cannot be independent ground truth"))
    else if emptyControl.nonEmpty then
      Left(CrossMethodEvidenceError.InvalidPlan(s"lane ${emptyControl.get.id} has an empty identity/control"))
    else if emptyUnavailable.nonEmpty then
      Left(CrossMethodEvidenceError.InvalidPlan(s"lane ${emptyUnavailable.get.id} has an empty unavailable reason"))
    else Right(plan)

trait EvidenceWorldMap3:
  def movingToFixed(worldMm: Vector[Double]): Either[String, Vector[Double]]

final case class LaneCostEvidence(
    coldStandaloneInputAndPreparationMs: Double,
    sharedPreparationMs: Double,
    methodStagesMs: AlgorithmStageTimes,
    peakRetainedBytes: Long
):
  def valid: Boolean =
    coldStandaloneInputAndPreparationMs.isFinite && coldStandaloneInputAndPreparationMs >= 0.0 &&
      sharedPreparationMs.isFinite && sharedPreparationMs >= 0.0 &&
      methodStagesMs.valid && peakRetainedBytes >= 0L

object LaneCostEvidence:
  val Zero: LaneCostEvidence = LaneCostEvidence(0.0, 0.0, AlgorithmStageTimes.Zero, 0L)

final case class CrossMethodCandidate(
    status: RunStatus,
    accepted: Boolean,
    failure: Option[FailureDetail],
    map: Option[EvidenceWorldMap3],
    serializedMapSha256: Option[Sha256],
    candidateMetrics: Map[String, Option[Double]],
    work: WorkCounts,
    costs: LaneCostEvidence
)

trait CrossMethodLaneAdapter[A]:
  def lane: ComparisonLane
  def identity: MethodIdentity
  def run(pair: PairCase, inputs: A): Either[EvidenceError, CrossMethodCandidate]

final case class CrossMethodLaneRecord(
    lane: ComparisonLane,
    method: MethodIdentity,
    controls: LaneControls,
    mapInterpretation: MapInterpretation,
    status: RunStatus,
    accepted: Boolean,
    failure: Option[FailureDetail],
    serializedMapSha256: Option[Sha256],
    effectiveMapSha256: Option[Sha256],
    metrics: PairMetrics,
    work: WorkCounts,
    costs: LaneCostEvidence
)

final case class SharedInputCost(
    readAndHashMs: Double,
    decompressMs: Double,
    loadInvocations: Int
)

final case class CrossMethodCourt(
    schemaVersion: String,
    runId: String,
    planId: String,
    pair: PairCase,
    sharedInputCost: SharedInputCost,
    rows: Vector[CrossMethodLaneRecord]
)

object CrossMethodEvidenceRunner:
  val SchemaVersion = "1.0.0"

  def run[A](
      runId: String,
      plan: CrossMethodPlan,
      pair: PairCase,
      loader: PairInputLoader[A],
      adapters: Vector[CrossMethodLaneAdapter[A]],
      clock: NanoClock = NanoClock.SystemClock
  ): Either[CrossMethodEvidenceError, CrossMethodCourt] =
    for
      checkedPlan <- CrossMethodPlan.validate(plan)
      _ <- PairCase.validate(pair).left.map(CrossMethodEvidenceError.Base.apply)
      _ <- validateInitialization(pair)
      checkedAdapters <- validateAdapters(checkedPlan, adapters)
      result <- execute(runId, checkedPlan, pair, loader, checkedAdapters, clock)
    yield result

  private def execute[A](
      runId: String,
      plan: CrossMethodPlan,
      pair: PairCase,
      loader: PairInputLoader[A],
      adapters: Map[ComparisonLane, CrossMethodLaneAdapter[A]],
      clock: NanoClock
  ): Either[CrossMethodEvidenceError, CrossMethodCourt] =
    if runId.trim.isEmpty then Left(CrossMethodEvidenceError.InvalidPlan("run id is empty"))
    else
      val loadStarted = clock.now()
      val loaded = catchBase(loader.load(pair))
      val loadFinished = clock.now()
      val shared = SharedInputCost(
        readAndHashMs = math.max(0L, loadFinished - loadStarted).toDouble / 1000000.0 -
          loaded.toOption.map(_.decompressMillis).getOrElse(0.0),
        decompressMs = loaded.toOption.map(_.decompressMillis).getOrElse(0.0),
        loadInvocations = 1
      )
      val normalizedShared = shared.copy(readAndHashMs = math.max(0.0, shared.readAndHashMs))
      val rows = plan.lanes.map { lanePlan =>
        lanePlan.unavailableReason match
          case Some(reason) => unavailable(lanePlan, "unavailable-lane", reason)
          case None => loaded match
            case Left(error) => unavailable(lanePlan, "input", error.message)
            case Right(input) =>
              val adapter = adapters(lanePlan.lane)
              catchBase(adapter.run(pair, input.value)) match
                case Left(error) => unavailable(lanePlan, "adapter", error.message, RunStatus.NumericalFailure)
                case Right(candidate) => evaluateCandidate(pair, lanePlan, candidate)
      }
      sequence(rows).map(records =>
        CrossMethodCourt(SchemaVersion, runId, plan.id, pair, normalizedShared, records)
      )

  private def evaluateCandidate(
      pair: PairCase,
      plan: LanePlan,
      candidate: CrossMethodCandidate
  ): Either[CrossMethodEvidenceError, CrossMethodLaneRecord] =
    validateCandidate(candidate).flatMap { checked =>
      val effectiveMap = checked.map.map { raw =>
        plan.mapInterpretation match
          case MapInterpretation.CompleteMovingToFixed => raw
          case MapInterpretation.ResidualAfterSuppliedAffine =>
            val affine = pair.initialization.movingToFixed
            new EvidenceWorldMap3:
              def movingToFixed(worldMm: Vector[Double]): Either[String, Vector[Double]] =
                raw.movingToFixed(applyAffine(affine, worldMm))
      }
      IndependentCrossMethodScorer.score(pair.landmarks, effectiveMap, checked.candidateMetrics).map { metrics =>
        val effectiveHash = checked.serializedMapSha256.map { rawHash =>
          plan.mapInterpretation match
            case MapInterpretation.CompleteMovingToFixed => rawHash
            case MapInterpretation.ResidualAfterSuppliedAffine =>
              EvidenceHash.utf8(
                s"residual-after-supplied-affine-v1\n${pair.initialization.sha256.hex}\n${rawHash.hex}\n"
              )
        }
        CrossMethodLaneRecord(
          plan.lane,
          plan.method,
          plan.controls,
          plan.mapInterpretation,
          checked.status,
          checked.accepted,
          checked.failure,
          checked.serializedMapSha256,
          effectiveHash,
          metrics,
          checked.work,
          checked.costs
        )
      }
    }

  private def validateCandidate(
      candidate: CrossMethodCandidate
  ): Either[CrossMethodEvidenceError, CrossMethodCandidate] =
    val success = candidate.status == RunStatus.Success && candidate.accepted
    val mapIdentityMatches = candidate.map.isDefined == candidate.serializedMapSha256.isDefined
    if (candidate.status == RunStatus.Success) != candidate.accepted then
      Left(CrossMethodEvidenceError.InvalidCandidate("success status and acceptance must agree"))
    else if success && candidate.map.isEmpty then
      Left(CrossMethodEvidenceError.InvalidCandidate("accepted success requires a map and serialized identity"))
    else if success && candidate.failure.nonEmpty then
      Left(CrossMethodEvidenceError.InvalidCandidate("successful result cannot carry failure detail"))
    else if candidate.status != RunStatus.Success && candidate.failure.isEmpty then
      Left(CrossMethodEvidenceError.InvalidCandidate("non-success requires failure detail"))
    else if !mapIdentityMatches then
      Left(CrossMethodEvidenceError.InvalidCandidate("map and serialized identity must be present together"))
    else if !candidate.work.nonnegative then
      Left(CrossMethodEvidenceError.InvalidCandidate("work counts are negative"))
    else if !candidate.costs.valid then
      Left(CrossMethodEvidenceError.InvalidCandidate("cold/shared cost evidence is invalid"))
    else if candidate.candidateMetrics.values.flatten.exists(value => !value.isFinite) then
      Left(CrossMethodEvidenceError.InvalidCandidate("candidate metric is non-finite"))
    else Right(candidate)

  private def validateInitialization(pair: PairCase): Either[CrossMethodEvidenceError, Unit] =
    val actual = EvidenceHash.matrix(pair.initialization.movingToFixed)
    if actual == pair.initialization.sha256 then Right(())
    else Left(CrossMethodEvidenceError.Base(
      EvidenceError.InputHashMismatch("initialization", pair.initialization.sha256, actual)
    ))

  private def validateAdapters[A](
      plan: CrossMethodPlan,
      adapters: Vector[CrossMethodLaneAdapter[A]]
  ): Either[CrossMethodEvidenceError, Map[ComparisonLane, CrossMethodLaneAdapter[A]]] =
    val grouped = adapters.groupBy(_.lane)
    val required = plan.lanes.filter(_.unavailableReason.isEmpty)
    required.collectFirst {
      case lane if grouped.get(lane.lane).forall(_.size != 1) => lane.lane
      case lane if grouped(lane.lane).head.identity != lane.method => lane.lane
    } match
      case Some(lane) => Left(CrossMethodEvidenceError.InvalidPlan(
        s"available lane ${lane.id} requires exactly one adapter with the frozen method identity"
      ))
      case None => Right(grouped.view.mapValues(_.head).toMap)

  private def unavailable(
      plan: LanePlan,
      kind: String,
      reason: String,
      status: RunStatus = RunStatus.Unavailable
  ): Either[CrossMethodEvidenceError, CrossMethodLaneRecord] =
    Right(CrossMethodLaneRecord(
      plan.lane,
      plan.method,
      plan.controls,
      plan.mapInterpretation,
      status,
      accepted = false,
      Some(FailureDetail(kind, reason)),
      None,
      None,
      PairMetrics(None, None, None, Map.empty),
      WorkCounts.Zero,
      LaneCostEvidence.Zero
    ))

  private def catchBase[A](operation: => Either[EvidenceError, A]): Either[EvidenceError, A] =
    try operation
    catch
      case NonFatal(error) => Left(EvidenceError.AdapterFailure(
        Option(error.getMessage).getOrElse(error.getClass.getName)
      ))

  private def applyAffine(matrix: Vector[Double], point: Vector[Double]): Vector[Double] =
    Vector.tabulate(3)(row =>
      matrix(row * 4) * point(0) + matrix(row * 4 + 1) * point(1) +
        matrix(row * 4 + 2) * point(2) + matrix(row * 4 + 3)
    )

  private def sequence[A](values: Vector[Either[CrossMethodEvidenceError, A]]): Either[CrossMethodEvidenceError, Vector[A]] =
    values.foldLeft[Either[CrossMethodEvidenceError, Vector[A]]](Right(Vector.empty)) {
      case (acc, value) => acc.flatMap(items => value.map(items :+ _))
    }

object IndependentCrossMethodScorer:
  def score(
      landmarks: Vector[LandmarkTruth],
      map: Option[EvidenceWorldMap3],
      candidateMetrics: Map[String, Option[Double]]
  ): Either[CrossMethodEvidenceError, PairMetrics] =
    map match
      case None => Right(PairMetrics(None, None, None, candidateMetrics))
      case Some(worldMap) =>
        val evaluated = landmarks.map(landmark =>
          worldMap.movingToFixed(landmark.movingWorldMm).flatMap { predicted =>
            if predicted.length != 3 || predicted.exists(value => !value.isFinite) then
              Left(s"map returned an invalid point for ${landmark.id}")
            else Right(math.sqrt(predicted.zip(landmark.fixedWorldMm).map { case (a, b) =>
              val delta = a - b
              delta * delta
            }.sum))
          }
        )
        evaluated.collectFirst { case Left(detail) => detail } match
          case Some(detail) => Left(CrossMethodEvidenceError.Scoring(detail))
          case None =>
            val errors = evaluated.collect { case Right(value) => value }.sorted
            if errors.isEmpty then Right(PairMetrics(None, None, None, candidateMetrics))
            else
              val rms = math.sqrt(errors.map(value => value * value).sum / errors.length.toDouble)
              val p95 = math.min(errors.length - 1, math.ceil(0.95 * errors.length).toInt - 1)
              Right(PairMetrics(Some(rms), Some(errors(p95)), Some(errors.last), candidateMetrics))

object CrossMethodEvidenceJson:
  def render(court: CrossMethodCourt): String =
    obj(
      "schema_version" -> string(court.schemaVersion),
      "run_id" -> string(court.runId),
      "plan_id" -> string(court.planId),
      "case_id" -> string(court.pair.id),
      "subject_id" -> string(court.pair.subjectId),
      "cohort" -> string(court.pair.cohort.id),
      "inputs" -> obj(
        "moving" -> string(court.pair.moving.sha256.hex),
        "fixed" -> string(court.pair.fixed.sha256.hex)
      ),
      "provenance" -> obj(
        "moving" -> renderArtifact(court.pair.moving),
        "fixed" -> renderArtifact(court.pair.fixed)
      ),
      "configuration" -> obj(
        "sample_ids" -> string(court.pair.immutableSampleIdsSha256.hex)
      ),
      "initialization" -> obj(
        "id" -> string(court.pair.initialization.id),
        "moving_to_fixed" -> numericArray(court.pair.initialization.movingToFixed),
        "sha256" -> string(court.pair.initialization.sha256.hex)
      ),
      "landmarks" -> array(court.pair.landmarks.map(renderLandmark)),
      "shared_input_cost" -> obj(
        "read_and_hash_ms" -> number(court.sharedInputCost.readAndHashMs),
        "decompress_ms" -> number(court.sharedInputCost.decompressMs),
        "load_invocations" -> court.sharedInputCost.loadInvocations.toString
      ),
      "rows" -> array(court.rows.map(renderRow))
    )

  private def renderArtifact(value: LicensedArtifact): String = obj(
    "path" -> string(value.path.toString),
    "sha256" -> string(value.sha256.hex),
    "provenance" -> string(value.provenance),
    "license" -> string(value.license)
  )

  private def renderLandmark(value: LandmarkTruth): String = obj(
    "id" -> string(value.id),
    "moving_world_mm" -> numericArray(value.movingWorldMm),
    "fixed_world_mm" -> numericArray(value.fixedWorldMm)
  )

  private def renderRow(row: CrossMethodLaneRecord): String =
    obj(
      "lane" -> string(row.lane.id),
      "method" -> obj(
        "id" -> string(row.method.id),
        "model" -> string(row.method.model),
        "revision" -> string(row.method.revision),
        "configuration_sha256" -> string(row.method.configurationSha256.hex)
      ),
      "controls" -> obj(
        "objective_id" -> string(row.controls.objectiveId),
        "support_policy_id" -> string(row.controls.supportPolicyId),
        "mask_policy_id" -> string(row.controls.maskPolicyId),
        "model_freedom_id" -> string(row.controls.modelFreedomId),
        "initialization_source_id" -> string(row.controls.initializationSourceId),
        "recipient_controls_sha256" -> optionalHash(row.controls.frozenRecipientControlsSha256)
      ),
      "map_interpretation" -> string(row.mapInterpretation.id),
      "status" -> string(row.status.id),
      "accepted" -> row.accepted.toString,
      "failure" -> row.failure.map(value => obj(
        "kind" -> string(value.kind),
        "message" -> string(value.message)
      )).getOrElse("null"),
      "serialized_map_sha256" -> optionalHash(row.serializedMapSha256),
      "effective_map_sha256" -> optionalHash(row.effectiveMapSha256),
      "metrics" -> obj(
        "landmark_rms_mm" -> optionalNumber(row.metrics.landmarkRmsMm),
        "landmark_p95_mm" -> optionalNumber(row.metrics.landmarkP95Mm),
        "landmark_maximum_mm" -> optionalNumber(row.metrics.landmarkMaximumMm),
        "candidate_secondary" -> obj(row.metrics.candidateMetrics.toVector.sortBy(_._1).map {
          case (key, value) => key -> optionalNumber(value)
        }*)
      ),
      "work" -> obj(
        "unique_interpolations" -> row.work.uniqueInterpolations.toString,
        "gradient_evaluations" -> row.work.gradientEvaluations.toString,
        "linearizations" -> row.work.linearizations.toString,
        "trial_evaluations" -> row.work.trialEvaluations.toString,
        "rejected_trials" -> row.work.rejectedTrials.toString,
        "early_rejections" -> row.work.earlyRejections.toString,
        "curvature_products" -> row.work.curvatureProducts.toString
      ),
      "costs" -> obj(
        "cold_standalone_input_and_preparation_ms" -> number(row.costs.coldStandaloneInputAndPreparationMs),
        "shared_preparation_ms" -> number(row.costs.sharedPreparationMs),
        "prepare_ms" -> number(row.costs.methodStagesMs.prepare),
        "capture_ms" -> number(row.costs.methodStagesMs.capture),
        "optimize_ms" -> number(row.costs.methodStagesMs.optimize),
        "validate_ms" -> number(row.costs.methodStagesMs.validate),
        "output_ms" -> number(row.costs.methodStagesMs.output),
        "peak_retained_bytes" -> row.costs.peakRetainedBytes.toString
      )
    )

  private def obj(fields: (String, String)*): String =
    fields.map { case (key, value) => s"${string(key)}:$value" }.mkString("{", ",", "}")

  private def array(values: Vector[String]): String = values.mkString("[", ",", "]")

  private def numericArray(values: Vector[Double]): String =
    array(values.map(number))

  private def optionalHash(value: Option[Sha256]): String =
    value.map(hash => string(hash.hex)).getOrElse("null")

  private def optionalNumber(value: Option[Double]): String =
    value.map(number).getOrElse("null")

  private def number(value: Double): String = java.lang.Double.toString(value)

  private def string(value: String): String =
    val escaped = value.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case character if character < ' ' => f"\\u${character.toInt}%04x"
      case character => character.toString
    }
    s"\"$escaped\""

sealed trait CrossMethodEvidenceError derives CanEqual:
  def message: String

object CrossMethodEvidenceError:
  final case class InvalidPlan(detail: String) extends CrossMethodEvidenceError:
    val message = s"invalid cross-method plan: $detail"

  final case class InvalidCandidate(detail: String) extends CrossMethodEvidenceError:
    val message = s"invalid cross-method candidate: $detail"

  final case class Scoring(detail: String) extends CrossMethodEvidenceError:
    val message = s"independent cross-method scoring failed: $detail"

  final case class Base(error: EvidenceError) extends CrossMethodEvidenceError:
    val message = error.message
