package reframe4s.flashalign

/** Versioned initialization choice. Header-derived identity is never inferred
  * merely from equal numeric matrices or frame labels.
  */
enum FlashalignInitializationPolicy derives CanEqual:
  case SuppliedWorldTransform
  case CoherentHeaderWorldIdentityThenCapture

final case class PresetPatchPolicy(
    positivePolarityPrior: Double,
    tau: Double,
    outlierFloor: Double,
    minimumContrastEnergy: Double,
    correlationRoundingTolerance: Double
)

final case class PresetSamplingPolicy(
    selectionFraction: Double,
    auditFraction: Double,
    uniformProbabilityMixture: Double,
    optimizationDraws: Int,
    selectionDraws: Int,
    auditDraws: Int,
    seed: Long
)

final case class PresetPreparationPolicy(
    effectiveResolutionMillimetres: Vector[Double],
    stencilSpacingMillimetres: Vector[Double],
    worldCellSizeMillimetres: Double,
    targetCandidatePatches: Int,
    maximumCentersToScreen: Int,
    maximumCandidatesPerCell: Int
)

final case class PresetCaptureStage(
    maximumAngleDegrees: Double,
    stepDegrees: Double
)

final case class PresetCapturePolicy(
    stages: Vector[PresetCaptureStage],
    seed: Long,
    maximumRotations: Int,
    maximumRetainedCandidates: Int,
    peaksPerRotation: Int,
    minimumCandidatesToStop: Int,
    minimumOverlapFraction: Double,
    minimumStructuralEnergy: Double,
    minimumStructuralScore: Double,
    minimumPeakSeparationMillimetres: Double,
    minimumCandidateDisplacementMillimetres: Double
)

final case class PresetTrustPolicy(
    maximumLinearizations: Int,
    maximumTrialAttempts: Int,
    initialDamping: Double,
    minimumDamping: Double,
    maximumDamping: Double,
    trustRadiusRmsMillimetres: Double,
    maximumDisplacementMillimetres: Double,
    acceptanceRatio: Double,
    objectiveTolerance: Double,
    gradientTolerance: Double,
    stepToleranceRmsMillimetres: Double,
    conditionLimit: Double
)

final case class PresetQcPolicy(
    minimumPatchInlierWeight: Double,
    minimumOverlapFraction: Double,
    minimumSpatialInlierCoverage: Double,
    maximumDataConditionNumber: Double,
    maximumRegionalRefitDisplacementMillimetres: Double,
    nearEqualObjectiveTolerance: Double
)

final case class PresetCalibrationIdentity(
    receiptId: String,
    receiptPath: String,
    receiptSha256: String,
    evidenceScope: String
)

sealed trait LinearPresetError derives CanEqual:
  def message: String

object LinearPresetError:
  final case class Empty(field: String) extends LinearPresetError:
    val message: String = s"$field must be non-empty"

  final case class InvalidScalar(field: String, value: Double, requirement: String)
      extends LinearPresetError:
    val message: String = s"$field must be $requirement, got $value"

  final case class InvalidCount(field: String, value: Int, requirement: String)
      extends LinearPresetError:
    val message: String = s"$field must be $requirement, got $value"

  final case class InvalidRelation(detail: String) extends LinearPresetError:
    val message: String = detail

  final case class InvalidSha256(field: String, value: String)
      extends LinearPresetError:
    val message: String = s"$field must be a lowercase SHA-256, got $value"

/** All consequential linear policy selected by a named preset. The policy is a
  * data value so callers and result receipts can retain its exact identity.
  */
final class LinearPresetPolicy private (
    val id: String,
    val version: String,
    val preset: FlashalignPreset,
    val patch: PresetPatchPolicy,
    val sampling: PresetSamplingPolicy,
    val preparation: PresetPreparationPolicy,
    val capture: PresetCapturePolicy,
    val rigidTrust: PresetTrustPolicy,
    val affineTrust: PresetTrustPolicy,
    val qc: PresetQcPolicy,
    val calibration: PresetCalibrationIdentity
)

object LinearPresetPolicy:
  def create(
      id: String,
      version: String,
      preset: FlashalignPreset,
      patch: PresetPatchPolicy,
      sampling: PresetSamplingPolicy,
      preparation: PresetPreparationPolicy,
      capture: PresetCapturePolicy,
      rigidTrust: PresetTrustPolicy,
      affineTrust: PresetTrustPolicy,
      qc: PresetQcPolicy,
      calibration: PresetCalibrationIdentity
  ): Either[LinearPresetError, LinearPresetPolicy] =
    validateStrings(id, version, calibration).flatMap { _ =>
      validatePatch(patch).flatMap(_ =>
        validateSampling(sampling).flatMap(_ =>
          validatePreparation(preparation).flatMap(_ =>
            validateCapture(capture).flatMap(_ =>
              validateTrust("rigidTrust", rigidTrust).flatMap(_ =>
                validateTrust("affineTrust", affineTrust).flatMap(_ =>
                  validateQc(qc).map(_ =>
                    new LinearPresetPolicy(
                      id,
                      version,
                      preset,
                      patch,
                      sampling,
                      preparation,
                      capture,
                      rigidTrust,
                      affineTrust,
                      qc,
                      calibration
                    )
                  )
                )
              )
            )
          )
        )
      )
    }

  private def validateStrings(
      id: String,
      version: String,
      calibration: PresetCalibrationIdentity
  ): Either[LinearPresetError, Unit] =
    val fields = Vector(
      "id" -> id,
      "version" -> version,
      "calibration.receiptId" -> calibration.receiptId,
      "calibration.receiptPath" -> calibration.receiptPath,
      "calibration.evidenceScope" -> calibration.evidenceScope
    )
    fields.collectFirst { case (field, value) if value.trim.isEmpty =>
      LinearPresetError.Empty(field)
    } match
      case Some(error) => Left(error)
      case None =>
        if calibration.receiptSha256.matches("[0-9a-f]{64}") then Right(())
        else Left(LinearPresetError.InvalidSha256("calibration.receiptSha256", calibration.receiptSha256))

  private def validatePatch(value: PresetPatchPolicy): Either[LinearPresetError, Unit] =
    for
      _ <- closedUnit("patch.positivePolarityPrior", value.positivePolarityPrior)
      _ <- positive("patch.tau", value.tau)
      _ <- openUnit("patch.outlierFloor", value.outlierFloor)
      _ <- positive("patch.minimumContrastEnergy", value.minimumContrastEnergy)
      _ <- nonnegative("patch.correlationRoundingTolerance", value.correlationRoundingTolerance)
      _ <-
        if value.correlationRoundingTolerance <= 1e-8 then Right(())
        else Left(LinearPresetError.InvalidScalar("patch.correlationRoundingTolerance", value.correlationRoundingTolerance, "at most 1e-8"))
    yield ()

  private def validateSampling(value: PresetSamplingPolicy): Either[LinearPresetError, Unit] =
    for
      _ <- openUnit("sampling.selectionFraction", value.selectionFraction)
      _ <- openUnit("sampling.auditFraction", value.auditFraction)
      _ <- openUnit("sampling.uniformProbabilityMixture", value.uniformProbabilityMixture)
      _ <-
        if value.selectionFraction + value.auditFraction < 1.0 then Right(())
        else Left(LinearPresetError.InvalidRelation("selection and audit fractions must leave positive optimization mass"))
      _ <- positiveCount("sampling.optimizationDraws", value.optimizationDraws)
      _ <- positiveCount("sampling.selectionDraws", value.selectionDraws)
      _ <- positiveCount("sampling.auditDraws", value.auditDraws)
    yield ()

  private def validatePreparation(value: PresetPreparationPolicy): Either[LinearPresetError, Unit] =
    val levels = value.effectiveResolutionMillimetres
    val stencils = value.stencilSpacingMillimetres
    for
      _ <-
        if levels.nonEmpty && levels.forall(number => number.isFinite && number > 0.0) then Right(())
        else Left(LinearPresetError.InvalidRelation("preparation resolutions must be non-empty, finite and positive"))
      _ <-
        if stencils.length == levels.length && stencils.forall(number => number.isFinite && number > 0.0) then Right(())
        else Left(LinearPresetError.InvalidRelation("one finite positive stencil spacing is required per resolution"))
      _ <-
        if levels.zip(levels.drop(1)).forall(_ > _) then Right(())
        else Left(LinearPresetError.InvalidRelation("preparation resolutions must be strictly coarse-to-fine"))
      _ <- positive("preparation.worldCellSizeMillimetres", value.worldCellSizeMillimetres)
      _ <- boundedCount("preparation.targetCandidatePatches", value.targetCandidatePatches, 10000, 30000)
      _ <- positiveCount("preparation.maximumCentersToScreen", value.maximumCentersToScreen)
      _ <-
        if value.maximumCentersToScreen >= value.targetCandidatePatches then Right(())
        else Left(LinearPresetError.InvalidRelation("screening budget must cover the target candidate count"))
      _ <- positiveCount("preparation.maximumCandidatesPerCell", value.maximumCandidatesPerCell)
    yield ()

  private def validateCapture(value: PresetCapturePolicy): Either[LinearPresetError, Unit] =
    for
      _ <-
        if value.stages.nonEmpty then Right(())
        else Left(LinearPresetError.InvalidRelation("capture requires at least one rotation stage"))
      _ <- value.stages.zipWithIndex.foldLeft[Either[LinearPresetError, Unit]](Right(())) {
        case (result, (stage, index)) =>
          result.flatMap(_ =>
            positive(s"capture.stages[$index].maximumAngleDegrees", stage.maximumAngleDegrees).flatMap(_ =>
              positive(s"capture.stages[$index].stepDegrees", stage.stepDegrees).flatMap { _ =>
                val steps = stage.maximumAngleDegrees / stage.stepDegrees
                if math.abs(steps - math.rint(steps)) <= 1e-10 && steps <= 12.0 then Right(())
                else Left(LinearPresetError.InvalidRelation(s"capture stage $index needs an integral ratio with at most 12 steps per axis"))
              }
            )
          )
      }
      _ <- boundedCount("capture.maximumRotations", value.maximumRotations, 1, 4096)
      _ <- positiveCount("capture.maximumRetainedCandidates", value.maximumRetainedCandidates)
      _ <- positiveCount("capture.peaksPerRotation", value.peaksPerRotation)
      _ <- boundedCount("capture.minimumCandidatesToStop", value.minimumCandidatesToStop, 1, value.maximumRetainedCandidates)
      _ <- openClosedUnit("capture.minimumOverlapFraction", value.minimumOverlapFraction)
      _ <- positive("capture.minimumStructuralEnergy", value.minimumStructuralEnergy)
      _ <-
        if value.minimumStructuralScore.isFinite && value.minimumStructuralScore >= -1.0 && value.minimumStructuralScore <= 1.0 then Right(())
        else Left(LinearPresetError.InvalidScalar("capture.minimumStructuralScore", value.minimumStructuralScore, "within [-1, 1]"))
      _ <- positive("capture.minimumPeakSeparationMillimetres", value.minimumPeakSeparationMillimetres)
      _ <- positive("capture.minimumCandidateDisplacementMillimetres", value.minimumCandidateDisplacementMillimetres)
    yield ()

  private def validateTrust(name: String, value: PresetTrustPolicy): Either[LinearPresetError, Unit] =
    for
      _ <- positiveCount(s"$name.maximumLinearizations", value.maximumLinearizations)
      _ <- positiveCount(s"$name.maximumTrialAttempts", value.maximumTrialAttempts)
      _ <- positive(s"$name.minimumDamping", value.minimumDamping)
      _ <-
        if value.initialDamping.isFinite && value.initialDamping >= value.minimumDamping then Right(())
        else Left(LinearPresetError.InvalidRelation(s"$name initial damping must be finite and at least its minimum"))
      _ <-
        if value.maximumDamping.isFinite && value.maximumDamping >= value.initialDamping then Right(())
        else Left(LinearPresetError.InvalidRelation(s"$name maximum damping must be finite and at least its initial value"))
      _ <- positive(s"$name.trustRadiusRmsMillimetres", value.trustRadiusRmsMillimetres)
      _ <- positive(s"$name.maximumDisplacementMillimetres", value.maximumDisplacementMillimetres)
      _ <- openUnit(s"$name.acceptanceRatio", value.acceptanceRatio)
      _ <- nonnegative(s"$name.objectiveTolerance", value.objectiveTolerance)
      _ <- nonnegative(s"$name.gradientTolerance", value.gradientTolerance)
      _ <- nonnegative(s"$name.stepToleranceRmsMillimetres", value.stepToleranceRmsMillimetres)
      _ <-
        if value.conditionLimit.isFinite && value.conditionLimit > 1.0 then Right(())
        else Left(LinearPresetError.InvalidScalar(s"$name.conditionLimit", value.conditionLimit, "finite and greater than one"))
    yield ()

  private def validateQc(value: PresetQcPolicy): Either[LinearPresetError, Unit] =
    for
      _ <- closedUnit("qc.minimumPatchInlierWeight", value.minimumPatchInlierWeight)
      _ <- closedUnit("qc.minimumOverlapFraction", value.minimumOverlapFraction)
      _ <- closedUnit("qc.minimumSpatialInlierCoverage", value.minimumSpatialInlierCoverage)
      _ <-
        if value.maximumDataConditionNumber.isFinite && value.maximumDataConditionNumber > 1.0 then Right(())
        else Left(LinearPresetError.InvalidScalar("qc.maximumDataConditionNumber", value.maximumDataConditionNumber, "finite and greater than one"))
      _ <- positive("qc.maximumRegionalRefitDisplacementMillimetres", value.maximumRegionalRefitDisplacementMillimetres)
      _ <- nonnegative("qc.nearEqualObjectiveTolerance", value.nearEqualObjectiveTolerance)
    yield ()

  private def positive(field: String, value: Double): Either[LinearPresetError, Unit] =
    if value.isFinite && value > 0.0 then Right(())
    else Left(LinearPresetError.InvalidScalar(field, value, "finite and positive"))

  private def nonnegative(field: String, value: Double): Either[LinearPresetError, Unit] =
    if value.isFinite && value >= 0.0 then Right(())
    else Left(LinearPresetError.InvalidScalar(field, value, "finite and non-negative"))

  private def openUnit(field: String, value: Double): Either[LinearPresetError, Unit] =
    if value.isFinite && value > 0.0 && value < 1.0 then Right(())
    else Left(LinearPresetError.InvalidScalar(field, value, "within (0, 1)"))

  private def openClosedUnit(field: String, value: Double): Either[LinearPresetError, Unit] =
    if value.isFinite && value > 0.0 && value <= 1.0 then Right(())
    else Left(LinearPresetError.InvalidScalar(field, value, "within (0, 1]"))

  private def closedUnit(field: String, value: Double): Either[LinearPresetError, Unit] =
    if value.isFinite && value >= 0.0 && value <= 1.0 then Right(())
    else Left(LinearPresetError.InvalidScalar(field, value, "within [0, 1]"))

  private def positiveCount(field: String, value: Int): Either[LinearPresetError, Unit] =
    if value > 0 then Right(())
    else Left(LinearPresetError.InvalidCount(field, value, "positive"))

  private def boundedCount(
      field: String,
      value: Int,
      minimum: Int,
      maximum: Int
  ): Either[LinearPresetError, Unit] =
    if value >= minimum && value <= maximum then Right(())
    else Left(LinearPresetError.InvalidCount(field, value, s"within [$minimum, $maximum]"))

/** Frozen v1 engineering presets. Their receipt covers synthetic training
  * behavior and policy checks only; it is not anatomical qualification.
  */
object LinearPresetPolicies:
  val Version: String = "1.0.0"
  val CalibrationReceiptId: String = "flashalign-linear-preset-calibration-v1"
  val CalibrationReceiptPath: String =
    "benchmarks/flashalign/receipts/linear-preset-calibration-v1.json"
  val CalibrationReceiptSha256: String =
    "79c258b0da0a825650d57f74f95ecd69da17e3fa8bbd4145771f9708ea141dba"

  private val epiPatchT1 = PresetPatchPolicy(0.15, 0.55, 0.02, 1e-12, 1e-12)
  private val epiPatchT2 = PresetPatchPolicy(0.80, 0.55, 0.02, 1e-12, 1e-12)
  private val withinPatch = PresetPatchPolicy(0.995, 0.40, 0.01, 1e-12, 1e-12)

  private val epiSampling = PresetSamplingPolicy(0.15, 0.15, 0.30, 4000, 2000, 2000, 0x4f1bbcdc6762c9d5L)
  private val withinSampling = PresetSamplingPolicy(0.15, 0.15, 0.25, 2500, 1500, 1500, 0x2c9277b5a6e41d03L)
  private val slabSampling = PresetSamplingPolicy(0.15, 0.15, 0.40, 6000, 2500, 2500, 0x73ad48e910bf265cL)

  private val rigidTrust = PresetTrustPolicy(40, 8, 1e-2, 1e-8, 1e8, 2.0, 4.0, 0.1, 1e-8, 1e-7, 1e-6, 1e12)
  private val affineTrust = PresetTrustPolicy(50, 8, 1e-2, 1e-8, 1e8, 1.5, 3.0, 0.1, 1e-8, 1e-7, 1e-6, 1e12)

  private val standardCapture = PresetCapturePolicy(
    Vector(PresetCaptureStage(15.0, 5.0), PresetCaptureStage(30.0, 10.0)),
    0x1475b8a31c9de620L,
    256,
    4,
    2,
    2,
    0.20,
    1e-10,
    0.10,
    8.0,
    8.0
  )
  private val withinCapture = standardCapture.copy(
    stages = Vector(PresetCaptureStage(10.0, 5.0), PresetCaptureStage(30.0, 10.0)),
    seed = 0x527cd36e9810ab4fL,
    maximumRotations = 192,
    minimumOverlapFraction = 0.30,
    minimumStructuralScore = 0.15
  )
  private val slabCapture = standardCapture.copy(
    stages = Vector(PresetCaptureStage(20.0, 10.0), PresetCaptureStage(40.0, 20.0)),
    seed = 0x6e25c0a91f7b483dL,
    maximumRotations = 384,
    minimumOverlapFraction = 0.15,
    minimumStructuralScore = 0.08
  )

  private val epiPreparation = PresetPreparationPolicy(Vector(6.0, 3.0, 2.0), Vector(6.0, 3.0, 2.0), 12.0, 20000, 120000, 16)
  private val withinPreparation = PresetPreparationPolicy(Vector(6.0, 3.0, 1.5), Vector(6.0, 3.0, 1.5), 12.0, 15000, 90000, 16)
  private val slabPreparation = PresetPreparationPolicy(Vector(8.0, 4.0, 2.5), Vector(8.0, 4.0, 2.5), 12.0, 30000, 180000, 20)

  private val epiQc = PresetQcPolicy(0.50, 0.30, 0.25, 1e10, 3.0, 1e-3)
  private val withinQc = PresetQcPolicy(0.60, 0.40, 0.35, 1e9, 2.0, 5e-4)
  private val slabQc = PresetQcPolicy(0.45, 0.20, 0.15, 1e10, 4.0, 2e-3)

  private val calibration = PresetCalibrationIdentity(
    CalibrationReceiptId,
    CalibrationReceiptPath,
    CalibrationReceiptSha256,
    "deterministic analytic-synthetic training and policy calibration; no MRI accuracy or runtime qualification"
  )

  val EpiToT1: LinearPresetPolicy = build("flashalign-linear-epi-t1-v1", FlashalignPreset.EpiToT1, epiPatchT1, epiSampling, epiPreparation, standardCapture, epiQc)
  val EpiToT2: LinearPresetPolicy = build("flashalign-linear-epi-t2-v1", FlashalignPreset.EpiToT2, epiPatchT2, epiSampling.copy(seed = 0x59d6e147b20ca83fL), epiPreparation, standardCapture.copy(seed = 0x3a51cb90e74d826fL), epiQc)
  val WithinModality: LinearPresetPolicy = build("flashalign-linear-within-modality-v1", FlashalignPreset.WithinModality, withinPatch, withinSampling, withinPreparation, withinCapture, withinQc)
  val SlabToAnatomy: LinearPresetPolicy = build("flashalign-linear-slab-to-anatomy-v1", FlashalignPreset.SlabToAnatomy, epiPatchT1.copy(tau = 0.65, outlierFloor = 0.04), slabSampling, slabPreparation, slabCapture, slabQc)

  def forPreset(preset: FlashalignPreset): LinearPresetPolicy = preset match
    case FlashalignPreset.EpiToT1       => EpiToT1
    case FlashalignPreset.EpiToT2       => EpiToT2
    case FlashalignPreset.WithinModality => WithinModality
    case FlashalignPreset.SlabToAnatomy => SlabToAnatomy

  private def build(
      id: String,
      preset: FlashalignPreset,
      patch: PresetPatchPolicy,
      sampling: PresetSamplingPolicy,
      preparation: PresetPreparationPolicy,
      capture: PresetCapturePolicy,
      qc: PresetQcPolicy
  ): LinearPresetPolicy =
    LinearPresetPolicy
      .create(id, Version, preset, patch, sampling, preparation, capture, rigidTrust, affineTrust, qc, calibration)
      .fold(error => throw new IllegalStateException(error.message), identity)
