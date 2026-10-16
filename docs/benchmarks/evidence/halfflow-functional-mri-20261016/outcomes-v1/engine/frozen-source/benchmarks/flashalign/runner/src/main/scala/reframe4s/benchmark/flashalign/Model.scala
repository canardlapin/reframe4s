package reframe4s.benchmark.flashalign

import java.nio.file.Path

opaque type Sha256 = String

object Sha256:
  private val Pattern = "[0-9a-f]{64}".r

  def parse(value: String): Either[EvidenceError, Sha256] =
    if Pattern.matches(value) then Right(value)
    else Left(EvidenceError.InvalidSha256(value))

  def unsafe(value: String): Sha256 =
    parse(value).fold(error => throw new IllegalArgumentException(error.message), identity)

  extension (value: Sha256) def hex: String = value

enum PairCohort(val id: String) derives CanEqual:
  case OrdinarySameSubject extends PairCohort("ordinary-same-subject")
  case LargeInitializationError extends PairCohort("large-initialization-error")
  case PartialSlab extends PairCohort("partial-slab")
  case DropoutOrLowSignal extends PairCohort("dropout-or-low-signal")

final case class LicensedArtifact(
    path: Path,
    sha256: Sha256,
    provenance: String,
    license: String
)

final case class InitializationIdentity(
    id: String,
    movingToFixed: Vector[Double],
    sha256: Sha256
)

final case class LandmarkTruth(
    id: String,
    movingWorldMm: Vector[Double],
    fixedWorldMm: Vector[Double]
)

final case class PairCase(
    id: String,
    subjectId: String,
    cohort: PairCohort,
    moving: LicensedArtifact,
    fixed: LicensedArtifact,
    initialization: InitializationIdentity,
    immutableSampleIdsSha256: Sha256,
    landmarks: Vector[LandmarkTruth]
)

object PairCase:
  def validate(value: PairCase): Either[EvidenceError, PairCase] =
    val artifacts = Vector("moving" -> value.moving, "fixed" -> value.fixed)
    if value.id.trim.isEmpty then Left(EvidenceError.EmptyField("case.id"))
    else if value.subjectId.trim.isEmpty then Left(EvidenceError.EmptyField("case.subject_id"))
    else
      artifacts.collectFirst {
        case (label, artifact) if artifact.provenance.trim.isEmpty =>
          EvidenceError.EmptyField(s"$label.provenance")
        case (label, artifact) if artifact.license.trim.isEmpty =>
          EvidenceError.EmptyField(s"$label.license")
      } match
        case Some(error) => Left(error)
        case None =>
          validateInitialization(value.initialization).flatMap(_ =>
            value.landmarks.foldLeft[Either[EvidenceError, Unit]](Right(())) {
              case (acc, landmark) => acc.flatMap(_ => validateLandmark(landmark))
            }.map(_ => value)
          )

  private def validateInitialization(value: InitializationIdentity): Either[EvidenceError, Unit] =
    if value.id.trim.isEmpty then Left(EvidenceError.EmptyField("initialization.id"))
    else if value.movingToFixed.length != 16 then
      Left(EvidenceError.InvalidMatrixLength("initialization.moving_to_fixed", value.movingToFixed.length))
    else if value.movingToFixed.exists(number => !number.isFinite) then
      Left(EvidenceError.NonFinite("initialization.moving_to_fixed"))
    else Right(())

  private def validateLandmark(value: LandmarkTruth): Either[EvidenceError, Unit] =
    if value.id.trim.isEmpty then Left(EvidenceError.EmptyField("landmark.id"))
    else if value.movingWorldMm.length != 3 || value.fixedWorldMm.length != 3 then
      Left(EvidenceError.InvalidLandmark(value.id))
    else if (value.movingWorldMm ++ value.fixedWorldMm).exists(number => !number.isFinite) then
      Left(EvidenceError.NonFinite(s"landmark.${value.id}"))
    else Right(())

enum RunStatus(val id: String) derives CanEqual:
  case Success extends RunStatus("success")
  case Stalled extends RunStatus("stalled")
  case IterationLimit extends RunStatus("iteration-limit")
  case InsufficientOverlap extends RunStatus("insufficient-overlap")
  case InsufficientInformation extends RunStatus("insufficient-information")
  case Ambiguous extends RunStatus("ambiguous")
  case InvalidGeometry extends RunStatus("invalid-geometry")
  case NumericalFailure extends RunStatus("numerical-failure")
  case InverseFailure extends RunStatus("inverse-failure")
  case SolverBreakdown extends RunStatus("solver-breakdown")
  case SearchExhausted extends RunStatus("search-exhausted")
  case Unavailable extends RunStatus("unavailable")

final case class FailureDetail(kind: String, message: String)

final case class WorkCounts(
    uniqueInterpolations: Long,
    gradientEvaluations: Long,
    linearizations: Long,
    trialEvaluations: Long,
    rejectedTrials: Long,
    earlyRejections: Long,
    curvatureProducts: Long
):
  def nonnegative: Boolean =
    Vector(
      uniqueInterpolations,
      gradientEvaluations,
      linearizations,
      trialEvaluations,
      rejectedTrials,
      earlyRejections,
      curvatureProducts
    ).forall(_ >= 0L)

object WorkCounts:
  val Zero: WorkCounts = WorkCounts(0L, 0L, 0L, 0L, 0L, 0L, 0L)

final case class AlgorithmStageTimes(
    prepare: Double,
    capture: Double,
    optimize: Double,
    validate: Double,
    output: Double
):
  def values: Vector[Double] = Vector(prepare, capture, optimize, validate, output)
  def valid: Boolean = values.forall(value => value.isFinite && value >= 0.0)
  def total: Double = values.sum

object AlgorithmStageTimes:
  val Zero: AlgorithmStageTimes = AlgorithmStageTimes(0.0, 0.0, 0.0, 0.0, 0.0)

final case class EnvironmentRecord(os: String, cpu: String, jdk: String, threads: Int)

final case class MethodIdentity(
    id: String,
    model: String,
    revision: String,
    configurationSha256: Sha256,
    comparatorCommand: Option[String],
    comparatorVersion: Option[String]
)

final case class FailureCheckpoint(
    movingToFixed: Vector[Double],
    transformSha256: Sha256,
    lastValidMovingToFixed: Vector[Double],
    lastValidTransformSha256: Sha256,
    selectionObjective: Double,
    acceptedSteps: Int,
    selectionObjectiveId: Long,
    initialOptimizationObjective: Double,
    lastOptimizationObjective: Double
)

final case class CandidateRun(
    status: RunStatus,
    accepted: Boolean,
    failure: Option[FailureDetail],
    movingToFixed: Option[Vector[Double]],
    transformSha256: Option[Sha256],
    candidateMetrics: Map[String, Option[Double]],
    work: WorkCounts,
    timingMs: AlgorithmStageTimes,
    lastCheckpoint: Option[FailureCheckpoint] = None
)

final case class PairMetrics(
    landmarkRmsMm: Option[Double],
    landmarkP95Mm: Option[Double],
    landmarkMaximumMm: Option[Double],
    candidateMetrics: Map[String, Option[Double]]
)

final case class CompleteTiming(
    read: Double,
    decompress: Double,
    prepare: Double,
    capture: Double,
    optimize: Double,
    validate: Double,
    output: Double,
    total: Double
)

final case class RawPairResult(
    schemaVersion: String,
    runId: String,
    pair: PairCase,
    method: MethodIdentity,
    environment: EnvironmentRecord,
    candidate: CandidateRun,
    metrics: PairMetrics,
    timingMs: CompleteTiming
)

sealed trait EvidenceError derives CanEqual:
  def message: String

object EvidenceError:
  final case class EmptyField(field: String) extends EvidenceError:
    val message = s"$field must be non-empty"

  final case class InvalidSha256(value: String) extends EvidenceError:
    val message = s"invalid lowercase SHA-256: $value"

  final case class InvalidMatrixLength(field: String, actual: Int) extends EvidenceError:
    val message = s"$field requires 16 row-major values, got $actual"

  final case class InvalidLandmark(id: String) extends EvidenceError:
    val message = s"landmark $id requires three moving and three fixed world-mm coordinates"

  final case class NonFinite(field: String) extends EvidenceError:
    val message = s"$field contains a non-finite value"

  final case class InputHashMismatch(label: String, expected: Sha256, actual: Sha256)
      extends EvidenceError:
    val message = s"$label hash mismatch: expected ${expected.hex}, observed ${actual.hex}"

  final case class InputFailure(label: String, detail: String) extends EvidenceError:
    val message = s"$label input failed: $detail"

  final case class InvalidCandidate(detail: String) extends EvidenceError:
    val message = s"invalid candidate result: $detail"

  final case class AdapterFailure(detail: String) extends EvidenceError:
    val message = s"method adapter failed: $detail"
