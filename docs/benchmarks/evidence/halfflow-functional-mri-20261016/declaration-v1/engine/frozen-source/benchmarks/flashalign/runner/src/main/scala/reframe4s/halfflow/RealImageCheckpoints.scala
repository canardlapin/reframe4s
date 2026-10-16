package reframe4s.halfflow

import java.nio.file.{Files, Path, StandardOpenOption}
import reframe4s.benchmark.flashalign.EvidenceHash
import reframe4s.halfflow.internal.*

private[halfflow] final case class RealImageCheckpointPolicy(
    acceptedSteps: Vector[Int],
    rejectedAttempts: Vector[Int],
    maximumStates: Int,
    manifest: Path,
    deferNativeExport: Boolean = false
)

/** Diagnostics are saved on native prepared-image grids for common scoring.
  * Raw midpoint fields retain their original pyramid grid for exact replay.
  * Labels never enter this writer or its checkpoint selection.
  */
private[halfflow] final class RealImageCheckpoints[W, F, M](
    output: Path,
    policy: RealImageCheckpointPolicy,
    initial: ForwardMidpoint[W, F, M],
    plan: HalfFlowCcPlan,
    fixed: NeuroVol[Double],
    moving: NeuroVol[Double],
    fixedMask: Array[Boolean]
) extends HalfFlowCcObserver[W, F, M]:
  private val root = output.resolve("checkpoints")
  private val manifestHash = EvidenceHash.file(policy.manifest).hex
  private val movingMask = FieldValidity.copyMask(moving.copyLegacyLinear.map(_ > 0.0))
  private var serial = 0
  Files.createDirectory(root)
  Files.copy(policy.manifest, root.resolve("manifest.json"))

  def wantsState(info: HalfFlowCcCheckpointInfo): Boolean =
    info.kind match
      case HalfFlowCcCheckpointKind.LevelStart | HalfFlowCcCheckpointKind.LevelEnd => true
      case HalfFlowCcCheckpointKind.Accepted => policy.acceptedSteps.contains(info.acceptedSteps)
      case HalfFlowCcCheckpointKind.Rejected => policy.rejectedAttempts.contains(info.attempts)

  def observe(checkpoint: HalfFlowCcCheckpoint[W, F, M]): Unit =
    serial += 1
    val info = checkpoint.info
    val name = f"$serial%04d-level${info.levelIndex}%02d-${info.kind.toString.toLowerCase}-attempt${info.attempts}%04d"
    val saved = checkpoint.state.map: state =>
      val directory = root.resolve(name)
      Files.createDirectory(directory)
      RealImageRegistration.savePull("midpoint-fixed-residual", state.fixed.residual, fixed, directory)
      RealImageRegistration.savePull("midpoint-moving-residual", state.moving.residual, fixed, directory)
      for (arm, matrix) <- Vector("fixed" -> state.fixed.affine.transform.matrix,
                                  "moving" -> state.moving.affine.transform.matrix) do
        Files.writeString(directory.resolve(s"midpoint-$arm-affine.json"),
          (0 until 16).map(i => matrix(i / 4, i % 4)).mkString("[", ",", "]"), StandardOpenOption.CREATE_NEW)
      val status = if policy.deferNativeExport then "deferred: raw midpoint captured; native export pending"
      else exportNative(state, directory)
      Files.writeString(directory.resolve("export-status.txt"), status + "\n", StandardOpenOption.CREATE_NEW)
      name -> status
    val record = Map[String, Any](
      "schema" -> "halfflow-checkpoint-v1", "diagnostic_only" -> true,
      "serial" -> serial, "manifest_sha256" -> manifestHash,
      "info" -> info, "capture_status" -> checkpoint.captureStatus,
      "directory" -> saved.map(_._1), "export_status" -> saved.map(_._2))
    val json = RealImageCheckpointJson.encode(record)
    saved.foreach: (directory, _) =>
      Files.writeString(root.resolve(directory).resolve("checkpoint.json"), json + "\n", StandardOpenOption.CREATE_NEW)
    Files.writeString(root.resolve("events.jsonl"), json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    ()

  private def exportNative(state: ForwardMidpoint[W, F, M], directory: Path): String =
    state.regrid(initial.work, initial.fixed.endpoint, initial.moving.endpoint) match
        case Left(error) => s"regrid failed: ${error.message}"
        case Right(native) =>
          ForwardMidpointExporter.inspect(native, plan.exportConfig,
            Some(FieldValidity.copyMask(fixedMask)), Some(movingMask)) match
            case Left(error) => s"export construction failed: ${error.message}"
            case Right(candidate) =>
              val admitted = ForwardMidpointExporter.admit(candidate, plan.exportConfig)
              val lane = if admitted.isRight then "halfflow" else "halfflow-rejected"
              RealImageRegistration.savePair(lane, candidate.transform, moving, fixed, directory)
              Files.writeString(directory.resolve("export-diagnostics.txt"),
                s"${candidate.fixedResidualInverse}\n${candidate.movingResidualInverse}\n${candidate.endpointRoundTrip}\n",
                StandardOpenOption.CREATE_NEW)
              admitted.fold(error => s"rejected: ${error.message}", _ => "native export gate accepted")

private[halfflow] object RealImageCheckpointJson:
  def encode(value: Any): String = value match
    case null => "null"
    case value: String => quote(value)
    case value: Boolean => value.toString
    case value: Int => value.toString
    case value: Long => value.toString
    case value: Double => if value.isFinite then value.toString else "null"
    case value: Float => if value.isFinite then value.toString else "null"
    case value: Option[?] => value.fold("null")(encode)
    case value: Map[?, ?] =>
      value.toVector.map((key, field) => key.toString -> field).sortBy(_._1)
        .map((key, field) => quote(key) + ":" + encode(field)).mkString("{", ",", "}")
    case value: Seq[?] => value.map(encode).mkString("[", ",", "]")
    case value: Product if value.productArity == 0 => quote(value.productPrefix)
    case value: Product =>
      value.productElementNames.zip(value.productIterator)
        .map((key, field) => quote(key) + ":" + encode(field)).mkString("{", ",", "}")
    case other => throw new IllegalArgumentException(s"unsupported checkpoint JSON value: ${other.getClass.getName}")

  private def quote(value: String): String =
    val escaped = value.flatMap:
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => "\\" + "u" + f"${c.toInt}%04x"
      case c => c.toString
    "\"" + escaped + "\""
