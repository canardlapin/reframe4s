package reframe4s.benchmark.motion

private[motion] sealed trait Json

private[motion] object Json:
  final case class Obj(fields: Vector[(String, Json)]) extends Json
  final case class Arr(values: Vector[Json]) extends Json
  final case class Str(value: String) extends Json
  final case class Num(value: String) extends Json
  final case class Bool(value: Boolean) extends Json
  case object Null extends Json

  def string(value: String): Json =
    Str(value)

  def int(value: Int): Json =
    Num(value.toString)

  def long(value: Long): Json =
    Num(value.toString)

  def double(value: Double): Json =
    Num(java.lang.Double.toString(value))

  def bool(value: Boolean): Json =
    Bool(value)

  def optional[A](value: Option[A])(encode: A => Json): Json =
    value.fold[Json](Null)(encode)

  def render(value: Json): String =
    val builder = new StringBuilder
    append(value, builder, 0)
    builder.append('\n')
    builder.result()

  private def append(
      value: Json,
      builder: StringBuilder,
      indent: Int
  ): Unit =
    value match
      case Obj(fields) =>
        builder.append('{')
        if fields.nonEmpty then
          builder.append('\n')
          fields.zipWithIndex.foreach { case ((key, field), index) =>
            spaces(builder, indent + 2)
            quote(key, builder)
            builder.append(": ")
            append(field, builder, indent + 2)
            if index + 1 < fields.size then builder.append(',')
            builder.append('\n')
          }
          spaces(builder, indent)
        builder.append('}')
      case Arr(values) =>
        builder.append('[')
        if values.nonEmpty then
          builder.append('\n')
          values.zipWithIndex.foreach { case (item, index) =>
            spaces(builder, indent + 2)
            append(item, builder, indent + 2)
            if index + 1 < values.size then builder.append(',')
            builder.append('\n')
          }
          spaces(builder, indent)
        builder.append(']')
      case Str(text) =>
        quote(text, builder)
      case Num(number) =>
        builder.append(number)
      case Bool(boolean) =>
        builder.append(boolean.toString)
      case Null =>
        builder.append("null")

  private def quote(value: String, builder: StringBuilder): Unit =
    builder.append('"')
    value.foreach {
      case '"'  => builder.append("\\\"")
      case '\\' => builder.append("\\\\")
      case '\b' => builder.append("\\b")
      case '\f' => builder.append("\\f")
      case '\n' => builder.append("\\n")
      case '\r' => builder.append("\\r")
      case '\t' => builder.append("\\t")
      case character if character < ' ' =>
        builder.append(f"\\u${character.toInt}%04x")
      case character =>
        builder.append(character)
    }
    builder.append('"')

  private def spaces(builder: StringBuilder, count: Int): Unit =
    var index = 0
    while index < count do
      builder.append(' ')
      index += 1

private[motion] object RunRecordJson:
  import Json.*

  def encode(record: RunRecord): String =
    render(
      Obj(
        Vector(
          "schemaVersion" -> string(record.schemaVersion),
          "protocolSha256" -> string(record.protocolSha256),
          "subject" -> string(record.subject),
          "scenario" -> string(record.scenario),
          "court" -> string(record.court),
          "implementation" -> string(record.implementation),
          "phase" -> string(record.phase),
          "repetition" -> int(record.repetition),
          "order" -> int(record.order),
          "allocatedCoreCount" -> int(record.allocatedCoreCount),
          "requestedWorkerCount" -> int(record.requestedWorkerCount),
          "effectiveWorkerCount" -> int(record.effectiveWorkerCount),
          "effectiveWorkerEvidence" ->
            string(record.effectiveWorkerEvidence),
          "cpuAffinity" -> string(record.cpuAffinity),
          "referencePolicy" -> string(record.referencePolicy),
          "command" -> Arr(record.command.map(string)),
          "workingDirectory" -> string(record.workingDirectory),
          "environment" -> Obj(
            record.environment.map { case (name, value) =>
              name -> string(value)
            }
          ),
          "exitStatus" -> int(record.exitStatus),
          "termination" -> string(record.termination),
          "failure" -> optional(record.failure)(string),
          "stageTimes" -> stageTimes(record.stageTimes),
          "peakRssBytes" -> optional(record.peakRssBytes)(long),
          "materializedOutputBytes" ->
            long(record.materializedOutputBytes),
          "stdoutSha256" -> string(record.stdoutSha256),
          "stderrSha256" -> string(record.stderrSha256),
          "poseSha256" -> optional(record.poseSha256)(string),
          "correctedImageSha256" ->
            optional(record.correctedImageSha256)(string),
          "tool" -> tool(record.tool),
          "pipeline" -> pipeline(record.pipeline)
        )
      )
    )

  private def stageTimes(times: StageTimes): Json =
    Obj(
      Vector(
        "source" -> string(times.source.id),
        "decodeSeconds" -> optional(times.decodeSeconds)(double),
        "prepareSeconds" -> optional(times.prepareSeconds)(double),
        "estimateSeconds" -> optional(times.estimateSeconds)(double),
        "applySeconds" -> optional(times.applySeconds)(double),
        "encodeSeconds" -> optional(times.encodeSeconds)(double),
        "reportSeconds" -> optional(times.reportSeconds)(double),
        "endToEndSeconds" -> double(times.endToEndSeconds)
      )
    )

  private def tool(metadata: ToolMetadata): Json =
    Obj(
      Vector(
        "executable" -> string(metadata.executable),
        "registeredPin" -> string(metadata.registeredPin),
        "versionExitStatus" -> int(metadata.versionExitStatus),
        "versionStdoutSha256" -> string(metadata.versionStdoutSha256),
        "versionStderrSha256" -> string(metadata.versionStderrSha256)
      )
    )

  private def pipeline(metadata: PipelineMetadata): Json =
    Obj(
      Vector(
        "estimatorMaskPolicy" -> string(metadata.estimatorMaskPolicy),
        "estimationInterpolation" ->
          string(metadata.estimationInterpolation),
        "finalInterpolation" -> string(metadata.finalInterpolation),
        "boundaryPolicy" -> string(metadata.boundaryPolicy),
        "finalResamplingPasses" ->
          int(metadata.finalResamplingPasses),
        "timingIncludesFinalResampling" ->
          bool(metadata.timingIncludesFinalResampling)
      )
    )

private[motion] object MotionMetricRecordJson:
  import Json.*

  def encode(record: MotionMetricRecord): String =
    render(
      Obj(
        Vector(
          "schemaVersion" -> string(record.schemaVersion),
          "definitionVersion" -> string(record.definitionVersion),
          "protocolSha256" -> string(record.protocolSha256),
          "scorerRevision" -> string(record.scorerRevision),
          "runRecordSha256" -> string(record.runRecordSha256),
          "subject" -> string(record.subject),
          "scenario" -> string(record.scenario),
          "stratum" -> string(record.stratum),
          "court" -> string(record.court),
          "implementation" -> string(record.implementation),
          "phase" -> string(record.phase),
          "repetition" -> int(record.repetition),
          "allocatedCoreCount" -> int(record.allocatedCoreCount),
          "frames" -> Arr(record.frames.map(frame)),
          "metrics" -> metrics(record.metrics)
        )
      )
    )

  private def frame(value: FrameMotionMetrics): Json =
    Obj(
      Vector(
        "frame" -> int(value.frame),
        "physical_landmark_displacement_p95_mm" ->
          double(value.landmarkDisplacementP95Mm),
        "relative_rotation_error_degrees" ->
          double(value.relativeRotationErrorDegrees),
        "framewise_displacement_error_mm" ->
          optional(value.framewiseDisplacementErrorMm)(double),
        "corrected_image_nrmse" ->
          optional(value.correctedImageNrmse)(double),
        "boundary_shell_nrmse" ->
          optional(value.boundaryShellNrmse)(double),
        "temporal_difference_nrmse" ->
          optional(value.temporalDifferenceNrmse)(double),
        "edge_energy_log_error" ->
          optional(value.edgeEnergyLogError)(double),
        "ringing_fraction" -> optional(value.ringingFraction)(double)
      )
    )

  private def metrics(value: MotionMetricSummary): Json =
    Obj(
      Vector(
        "physical_landmark_displacement_p95_mm" ->
          double(value.physicalLandmarkDisplacementP95Mm),
        "corrected_image_nrmse" ->
          double(value.correctedImageNrmse),
        "relative_rotation_error_p95_degrees" ->
          double(value.relativeRotationErrorP95Degrees),
        "framewise_displacement_error_p95_mm" ->
          double(value.framewiseDisplacementErrorP95Mm),
        "boundary_shell_nrmse" ->
          double(value.boundaryShellNrmse),
        "temporal_difference_nrmse" ->
          double(value.temporalDifferenceNrmse),
        "edge_energy_log_error" ->
          double(value.edgeEnergyLogError),
        "ringing_fraction" -> double(value.ringingFraction)
      )
    )
