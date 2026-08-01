package reframe4s.benchmark.motion

import java.nio.file.Path

enum Court(val id: String) derives CanEqual:
  case CommonResamplerEstimation
      extends Court("common_resampler_estimation")
  case NativeEndToEnd extends Court("native_end_to_end")

object Court:
  def parse(value: String): Either[RunnerError, Court] =
    Court.values
      .find(_.id == value)
      .toRight(RunnerError.InvalidArgument("court", value))

enum Implementation(val id: String) derives CanEqual:
  case Reframe4sMotion extends Implementation("reframe4s_motion")
  case Afni3dvolreg extends Implementation("afni_3dvolreg")
  case Nifreeze extends Implementation("nifreeze")
  case FslMcflirt extends Implementation("fsl_mcflirt")

object Implementation:
  val required: Vector[Implementation] =
    Vector(
      Reframe4sMotion,
      Afni3dvolreg,
      Nifreeze,
      FslMcflirt
    )

  def parse(value: String): Either[RunnerError, Implementation] =
    Implementation.values
      .find(_.id == value)
      .toRight(RunnerError.InvalidArgument("implementation", value))

enum RunPhase(val id: String) derives CanEqual:
  case Warmup extends RunPhase("warmup")
  case Measured extends RunPhase("measured")

enum ReferencePolicy(val id: String) derives CanEqual:
  case FixedFrame0 extends ReferencePolicy("fixed_frame_0")
  case MedianLeaveOneVolumeOut
      extends ReferencePolicy("median_leave_one_volume_out")

enum EffectiveWorkerEvidence(val id: String) derives CanEqual:
  case ImplementationContract
      extends EffectiveWorkerEvidence("implementation_contract")
  case LockedToolProbe
      extends EffectiveWorkerEvidence("locked_tool_probe")
  case LockedAdapterConfiguration
      extends EffectiveWorkerEvidence("locked_adapter_configuration")

final case class ExecutionRegistration(
    allocatedCoreCount: Int,
    requestedWorkerCount: Int,
    effectiveWorkerCount: Int,
    effectiveWorkerEvidence: EffectiveWorkerEvidence,
    referencePolicy: ReferencePolicy
)

object ExecutionRegistration:
  def create(
      implementation: Implementation,
      court: Court,
      allocatedCoreCount: Int
  ): Either[RunnerError, ExecutionRegistration] =
    if allocatedCoreCount != 1 && allocatedCoreCount != 4 then
      Left(
        RunnerError.InvalidArgument(
          "allocated-cores",
          allocatedCoreCount.toString
        )
      )
    else
      val effective =
        implementation match
          case Implementation.Nifreeze => allocatedCoreCount
          case _                       => 1
      val evidence =
        implementation match
          case Implementation.Reframe4sMotion =>
            EffectiveWorkerEvidence.ImplementationContract
          case Implementation.Nifreeze =>
            EffectiveWorkerEvidence.LockedAdapterConfiguration
          case _ =>
            EffectiveWorkerEvidence.LockedToolProbe
      val reference =
        (court, implementation) match
          case (
                Court.NativeEndToEnd,
                Implementation.Nifreeze
              ) =>
            ReferencePolicy.MedianLeaveOneVolumeOut
          case _ =>
            ReferencePolicy.FixedFrame0
      Right(
        ExecutionRegistration(
          allocatedCoreCount = allocatedCoreCount,
          requestedWorkerCount = allocatedCoreCount,
          effectiveWorkerCount = effective,
          effectiveWorkerEvidence = evidence,
          referencePolicy = reference
        )
      )

final case class Workload(
    subject: String,
    scenario: String,
    input: Path,
    mask: Option[Path]
)

final case class RunSpec(
    workload: Workload,
    court: Court,
    implementation: Implementation,
    phase: RunPhase,
    repetition: Int,
    order: Int,
    allocatedCoreCount: Int,
    requestedWorkerCount: Int,
    effectiveWorkerCount: Int,
    effectiveWorkerEvidence: EffectiveWorkerEvidence,
    referencePolicy: ReferencePolicy,
    referenceIndex: Int,
    timeoutSeconds: Long,
    outputDirectory: Path
)

enum StageTimingSource(val id: String) derives CanEqual:
  case Instrumented extends StageTimingSource("instrumented")
  case RunnerEndToEndOnly
      extends StageTimingSource("runner_end_to_end_only")

final case class StageTimes(
    source: StageTimingSource,
    decodeSeconds: Option[Double],
    prepareSeconds: Option[Double],
    estimateSeconds: Option[Double],
    applySeconds: Option[Double],
    encodeSeconds: Option[Double],
    reportSeconds: Option[Double],
    endToEndSeconds: Double
):
  def finite: Boolean =
    Vector(
      decodeSeconds,
      prepareSeconds,
      estimateSeconds,
      applySeconds,
      encodeSeconds,
      reportSeconds
    ).flatten.forall(value => value.isFinite && value >= 0.0) &&
      endToEndSeconds.isFinite &&
      endToEndSeconds >= 0.0

enum Termination(val id: String) derives CanEqual:
  case Success extends Termination("success")
  case ProcessFailure extends Termination("process_failure")
  case Timeout extends Termination("timeout")
  case InvalidOutput extends Termination("invalid_output")
  case RunnerFailure extends Termination("runner_failure")

final case class ToolMetadata(
    executable: String,
    registeredPin: String,
    versionExitStatus: Int,
    versionStdoutSha256: String,
    versionStderrSha256: String
)

final case class PipelineMetadata(
    estimatorMaskPolicy: String,
    estimationInterpolation: String,
    finalInterpolation: String,
    boundaryPolicy: String,
    finalResamplingPasses: Int,
    timingIncludesFinalResampling: Boolean
)

object PipelineMetadata:
  def forRun(spec: RunSpec): PipelineMetadata =
    val estimation =
      spec.implementation match
        case Implementation.Reframe4sMotion =>
          "production_trilinear_objective"
        case Implementation.Afni3dvolreg =>
          "afni_3dvolreg_heptic_registered"
        case Implementation.Nifreeze =>
          "ants_rigid_backend_registered"
        case Implementation.FslMcflirt =>
          "mcflirt_internal_registered"
    if spec.court == Court.CommonResamplerEstimation then
      PipelineMetadata(
        estimatorMaskPolicy = "none",
        estimationInterpolation = estimation,
        finalInterpolation = "image4s_reference_trilinear",
        boundaryPolicy = "constant_0",
        finalResamplingPasses = 1,
        timingIncludesFinalResampling = false
      )
    else
      spec.implementation match
        case Implementation.Reframe4sMotion =>
          PipelineMetadata(
            estimatorMaskPolicy = "none",
            estimationInterpolation = estimation,
            finalInterpolation = "production_lanczos_5",
            boundaryPolicy = "constant_0",
            finalResamplingPasses = 1,
            timingIncludesFinalResampling = true
          )
        case Implementation.Afni3dvolreg =>
          PipelineMetadata(
            estimatorMaskPolicy = "none",
            estimationInterpolation = estimation,
            finalInterpolation = "afni_heptic",
            boundaryPolicy = "zpad_1_and_clip",
            finalResamplingPasses = 1,
            timingIncludesFinalResampling = true
          )
        case Implementation.Nifreeze =>
          PipelineMetadata(
            estimatorMaskPolicy =
              if spec.workload.mask.nonEmpty then "registered_mask"
              else "none",
            estimationInterpolation = estimation,
            finalInterpolation = "nitransforms_cubic_order_3",
            boundaryPolicy = "constant_0",
            finalResamplingPasses = 1,
            timingIncludesFinalResampling = true
          )
        case Implementation.FslMcflirt =>
          PipelineMetadata(
            estimatorMaskPolicy = "none",
            estimationInterpolation = estimation,
            finalInterpolation = "mcflirt_spline_final",
            boundaryPolicy = "duplicated_terminal_z_slices",
            finalResamplingPasses = 1,
            timingIncludesFinalResampling = true
          )

final case class RunRecord(
    schemaVersion: String,
    protocolSha256: String,
    subject: String,
    scenario: String,
    court: String,
    implementation: String,
    phase: String,
    repetition: Int,
    order: Int,
    allocatedCoreCount: Int,
    requestedWorkerCount: Int,
    effectiveWorkerCount: Int,
    effectiveWorkerEvidence: String,
    cpuAffinity: String,
    referencePolicy: String,
    command: Vector[String],
    workingDirectory: String,
    environment: Vector[(String, String)],
    exitStatus: Int,
    termination: String,
    failure: Option[String],
    stageTimes: StageTimes,
    peakRssBytes: Option[Long],
    materializedOutputBytes: Long,
    stdoutSha256: String,
    stderrSha256: String,
    poseSha256: Option[String],
    correctedImageSha256: Option[String],
    tool: ToolMetadata,
    pipeline: PipelineMetadata
)

sealed trait RunnerError derives CanEqual:
  def message: String

object RunnerError:
  final case class InvalidArgument(name: String, value: String)
      extends RunnerError:
    val message: String =
      s"invalid $name argument: $value"

  final case class MissingArgument(name: String) extends RunnerError:
    val message: String =
      s"missing required argument --$name"

  final case class InvalidInteger(name: String, value: String)
      extends RunnerError:
    val message: String =
      s"$name must be an integer, got $value"

  final case class InvalidLong(name: String, value: String)
      extends RunnerError:
    val message: String =
      s"$name must be an integer, got $value"

  final case class Io(operation: String, path: Path, detail: String)
      extends RunnerError:
    val message: String =
      s"$operation failed for $path: $detail"

  final case class NonEmptyOutputDirectory(path: Path) extends RunnerError:
    val message: String =
      s"run output directory must be absent or empty: $path"

  final case class ProcessStart(command: Vector[String], detail: String)
      extends RunnerError:
    val message: String =
      s"failed to start ${command.mkString(" ")}: $detail"

  final case class MissingOutput(label: String, path: Path)
      extends RunnerError:
    val message: String =
      s"$label output is missing: $path"

  final case class InvalidMatrix(detail: String) extends RunnerError:
    val message: String =
      s"invalid transform matrix: $detail"

  final case class InvalidPoseFile(path: Path, detail: String)
      extends RunnerError:
    val message: String =
      s"invalid pose file $path: $detail"

  final case class InvalidStageTimes(path: Path, detail: String)
      extends RunnerError:
    val message: String =
      s"invalid stage-time file $path: $detail"

  final case class Nifti(detail: String) extends RunnerError:
    val message: String =
      s"NIfTI operation failed: $detail"

  final case class Motion(detail: String) extends RunnerError:
    val message: String =
      s"motion operation failed: $detail"

  final case class UnsupportedInput(detail: String) extends RunnerError:
    val message: String =
      s"unsupported benchmark input: $detail"
