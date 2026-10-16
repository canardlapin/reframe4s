package reframe4s.benchmark.motion

import java.nio.file.Files
import java.nio.file.Path

object MotionBenchmarkMain:
  private val RunnerVersion =
    "reframe4s-motion-superiority-runner/v2"

  def main(arguments: Array[String]): Unit =
    val result =
      arguments.toVector match
        case Vector("--version") =>
          println(
            s"$RunnerVersion candidate_revision=${candidateRevision}"
          )
          Right(true)
        case "candidate" +: rest =>
          candidate(rest).map(_ => true)
        case "plan" +: rest =>
          plan(rest).map(_ => true)
        case "run" +: rest =>
          run(rest)
        case "score" +: rest =>
          score(rest).map(_ => true)
        case "score-plan" +: rest =>
          scorePlan(rest).map(_ => true)
        case "execute-plan" +: rest =>
          executePlan(rest)
        case _ =>
          Left(
            RunnerError.InvalidArgument(
              "command",
              arguments.mkString(" ")
            )
          )
    result match
      case Right(success) =>
        if !success then sys.exit(2)
      case Left(error) =>
        System.err.println(error.message)
        sys.exit(2)

  private def candidate(
      arguments: Vector[String]
  ): Either[RunnerError, Unit] =
    for
      options <- Options.parse(arguments)
      input <- options.path("input")
      output <- options.path("output-dir")
      court <- options.required("court").flatMap(Court.parse)
      reference <- options.integer("reference-index")
      threads <- options.integer("threads")
      _ <-
        if threads > 0 then Right(())
        else
          Left(
            RunnerError.InvalidArgument(
              "threads",
              threads.toString
            )
          )
      _ <- CandidatePipeline.run(input, output, court, reference)
    yield ()

  private def plan(
      arguments: Vector[String]
  ): Either[RunnerError, Unit] =
    for
      options <- Options.parse(arguments)
      manifest <- options.path("workloads")
      outputRoot <- options.path("output-root")
      planPath <- options.path("plan")
      warmups <- options.integerOr("warmup-runs", 5)
      measured <- options.integerOr("measured-runs", 20)
      timeout <- options.longOr("timeout-seconds", 3600L)
      allocatedCores <- options
        .requiredOr("allocated-core-counts", "4,1")
        .flatMap(parseAllocatedCoreCounts)
      workloads <- PlanIo.readWorkloads(manifest)
      scheduled <- BenchmarkScheduler.plan(
        workloads,
        outputRoot,
        warmups,
        measured,
        allocatedCores,
        timeout
      )
      _ <- PlanIo.writePlan(planPath, scheduled)
    yield
      println(
        s"wrote ${scheduled.size} required run rows to $planPath"
      )

  private def run(
      arguments: Vector[String]
  ): Either[RunnerError, Boolean] =
    for
      options <- Options.parse(arguments)
      spec <- runSpec(options)
      config <- adapterConfig(options)
      protocol <- protocolPath(options)
      protocolHash <- EvidenceIo.sha256(protocol)
      adapter =
        ImplementationAdapter.forImplementation(spec.implementation)
      record <- RunExecutor.execute(
        spec,
        adapter,
        config,
        protocolHash
      )
    yield
      println(
        s"${record.implementation} ${record.subject}/${record.scenario} " +
          s"${record.court} ${record.phase}#${record.repetition}: " +
          record.termination
      )
      record.termination == Termination.Success.id

  private def executePlan(
      arguments: Vector[String]
  ): Either[RunnerError, Boolean] =
    for
      options <- Options.parse(arguments)
      planPath <- options.path("plan")
      config <- adapterConfig(options)
      protocol <- protocolPath(options)
      protocolHash <- EvidenceIo.sha256(protocol)
      specs <- PlanIo.readPlan(planPath)
      completed <- specs.foldLeft[
        Either[RunnerError, Vector[RunRecord]]
      ](Right(Vector.empty)) { (accumulated, spec) =>
        for
          records <- accumulated
          record <- RunExecutor.execute(
            spec,
            ImplementationAdapter.forImplementation(
              spec.implementation
            ),
            config,
            protocolHash
          )
        yield records :+ record
      }
    yield
      val failures =
        completed.count(_.termination != Termination.Success.id)
      println(
        s"completed ${completed.size} planned rows with $failures failures"
      )
      failures == 0

  private def score(
      arguments: Vector[String]
  ): Either[RunnerError, Unit] =
    for
      options <- Options.parse(arguments)
      corrected <- options.path("corrected")
      poses <- options.path("poses")
      truth <- options.path("truth")
      truthPoses <- options.path("truth-poses")
      mask <- options.path("mask")
      landmarks <- options.path("landmarks")
      runRecord <- options.path("run-record")
      output <- options.path("output")
      protocol <- protocolPath(options)
      protocolHash <- EvidenceIo.sha256(protocol)
      runRecordHash <- EvidenceIo.sha256(runRecord)
      subject <- options.required("subject")
      scenario <- options.required("scenario")
      stratum <- options.required("stratum")
      court <- options.required("court").flatMap(Court.parse)
      implementation <- options
        .required("implementation")
        .flatMap(Implementation.parse)
      phase <- options.required("phase")
      repetition <- options.integer("repetition")
      allocatedCores <- options.integer("allocated-cores")
      reference <- options.integerOr("reference-index", 0)
      scored <- MotionMetricScorer.scoreFiles(
        corrected,
        poses,
        truth,
        truthPoses,
        mask,
        landmarks,
        reference
      )
      record =
        MotionMetricRecord(
          schemaVersion = MotionMetricScorer.SchemaVersion,
          definitionVersion = MotionMetricScorer.DefinitionVersion,
          protocolSha256 = protocolHash,
          scorerRevision = candidateRevision,
          runRecordSha256 = runRecordHash,
          subject = subject,
          scenario = scenario,
          stratum = stratum,
          court = court.id,
          implementation = implementation.id,
          phase = phase,
          repetition = repetition,
          allocatedCoreCount = allocatedCores,
          frames = scored.frames,
          metrics = scored.summary
        )
      _ <- EvidenceIo.writeUtf8(
        output,
        MotionMetricRecordJson.encode(record)
      )
    yield ()

  private def scorePlan(
      arguments: Vector[String]
  ): Either[RunnerError, Unit] =
    for
      options <- Options.parse(arguments)
      plan <- options.path("plan")
      protocol <- protocolPath(options)
      protocolHash <- EvidenceIo.sha256(protocol)
      specs <- MotionMetricPlanIo.read(plan)
      _ <- specs.foldLeft[Either[RunnerError, Unit]](Right(())) {
        (completed, spec) =>
          completed.flatMap(_ => scoreOne(spec, protocolHash))
      }
    yield println(s"scored ${specs.size} primary motion outputs")

  private def scoreOne(
      spec: MotionMetricScoreSpec,
      protocolHash: String
  ): Either[RunnerError, Unit] =
    for
      runRecordHash <- EvidenceIo.sha256(spec.runRecord)
      scored <- MotionMetricScorer.scoreFiles(
        spec.corrected,
        spec.poses,
        spec.truth,
        spec.truthPoses,
        spec.mask,
        spec.landmarks,
        spec.referenceIndex
      )
      record =
        MotionMetricRecord(
          schemaVersion = MotionMetricScorer.SchemaVersion,
          definitionVersion = MotionMetricScorer.DefinitionVersion,
          protocolSha256 = protocolHash,
          scorerRevision = candidateRevision,
          runRecordSha256 = runRecordHash,
          subject = spec.subject,
          scenario = spec.scenario,
          stratum = spec.stratum,
          court = spec.court.id,
          implementation = spec.implementation.id,
          phase = spec.phase,
          repetition = spec.repetition,
          allocatedCoreCount = spec.allocatedCoreCount,
          frames = scored.frames,
          metrics = scored.summary
        )
      _ <- EvidenceIo.writeUtf8(
        spec.output,
        MotionMetricRecordJson.encode(record)
      )
    yield ()

  private def runSpec(options: Options): Either[RunnerError, RunSpec] =
    for
      subject <- options.required("subject")
      scenario <- options.required("scenario")
      input <- options.path("input")
      mask = options.optional("mask").map(Path.of(_))
      court <- options.required("court").flatMap(Court.parse)
      implementation <- options
        .required("implementation")
        .flatMap(Implementation.parse)
      phase <- options
        .requiredOr("phase", "measured")
        .flatMap(value =>
          RunPhase.values
            .find(_.id == value)
            .toRight(RunnerError.InvalidArgument("phase", value))
        )
      repetition <- options.integer("repetition")
      order <- options.integer("order")
      allocatedCores <- options.integerOr("allocated-cores", 4)
      reference <- options.integerOr("reference-index", 0)
      timeout <- options.longOr("timeout-seconds", 3600L)
      output <- options.path("output-dir")
      registration <- ExecutionRegistration.create(
        implementation,
        court,
        allocatedCores
      )
    yield
      RunSpec(
        Workload(subject, scenario, input, mask),
        court,
        implementation,
        phase,
        repetition,
        order,
        registration.allocatedCoreCount,
        registration.requestedWorkerCount,
        registration.effectiveWorkerCount,
        registration.effectiveWorkerEvidence,
        registration.referencePolicy,
        reference,
        timeout,
        output
      )

  private def adapterConfig(
      options: Options
  ): Either[RunnerError, AdapterConfig] =
    for
      working <- options.pathOr(
        "working-directory",
        Path.of("").toAbsolutePath.normalize()
      )
      wrapper <- options.pathOr(
        "nifreeze-wrapper",
        defaultNifreezeWrapper(working)
      )
    yield
      val javaHome = Path.of(System.getProperty("java.home"))
      val javaExecutable =
        javaHome.resolve("bin").resolve("java").toString
      AdapterConfig(
        afniExecutable =
          options.optional("afni").getOrElse("3dvolreg"),
        mcflirtExecutable =
          options.optional("mcflirt").getOrElse("mcflirt"),
        pythonExecutable =
          options.optional("python").getOrElse("python3"),
        nifreezeWrapper = wrapper,
        javaExecutable =
          options.optional("java").getOrElse(javaExecutable),
        candidateClasspath =
          options
            .optional("candidate-classpath")
            .getOrElse(System.getProperty("java.class.path")),
        workingDirectory = working
      )

  private def protocolPath(
      options: Options
  ): Either[RunnerError, Path] =
    options.pathOr("protocol", defaultProtocol)

  private def defaultProtocol: Path =
    val local = Path.of("..", "protocol-v2.json").normalize()
    if Files.isRegularFile(local) then local
    else Path.of("benchmarks", "motion", "protocol-v2.json")

  private def defaultNifreezeWrapper(working: Path): Path =
    val local =
      working.resolve("adapters").resolve("run_nifreeze.py")
    if Files.isRegularFile(local) then local
    else
      working
        .resolve("benchmarks")
        .resolve("motion")
        .resolve("runner")
        .resolve("adapters")
        .resolve("run_nifreeze.py")

  private def parseAllocatedCoreCounts(
      value: String
  ): Either[RunnerError, Vector[Int]] =
    val parsed =
      value.split(",", -1).toVector.foldLeft[
        Either[RunnerError, Vector[Int]]
      ](Right(Vector.empty)) { (accumulated, token) =>
        for
          values <- accumulated
          number <- token.toIntOption
            .filter(number => number == 1 || number == 4)
            .toRight(
              RunnerError.InvalidArgument(
                "allocated-core-counts",
                value
              )
            )
        yield values :+ number
      }
    parsed.flatMap { values =>
      if values.distinct == values then Right(values)
      else
        Left(
          RunnerError.InvalidArgument("allocated-core-counts", value)
        )
    }

  private def candidateRevision: String =
    sys.env
      .get("REFRAME4S_CANDIDATE_REVISION")
      .filter(_.matches("[0-9a-f]{40}"))
      .getOrElse("uncommitted-worktree")

final class Options private (
    private val values: Map[String, String]
):
  def required(name: String): Either[RunnerError, String] =
    values.get(name).toRight(RunnerError.MissingArgument(name))

  def requiredOr(name: String, default: String): Either[RunnerError, String] =
    Right(values.getOrElse(name, default))

  def optional(name: String): Option[String] =
    values.get(name)

  def path(name: String): Either[RunnerError, Path] =
    required(name).map(value =>
      Path.of(value).toAbsolutePath.normalize()
    )

  def pathOr(
      name: String,
      default: Path
  ): Either[RunnerError, Path] =
    Right(
      values
        .get(name)
        .fold(default)(Path.of(_))
        .toAbsolutePath
        .normalize()
    )

  def integer(name: String): Either[RunnerError, Int] =
    required(name).flatMap(value =>
      value.toIntOption.toRight(
        RunnerError.InvalidInteger(name, value)
      )
    )

  def integerOr(
      name: String,
      default: Int
  ): Either[RunnerError, Int] =
    values.get(name) match
      case None => Right(default)
      case Some(value) =>
        value.toIntOption.toRight(
          RunnerError.InvalidInteger(name, value)
        )

  def longOr(
      name: String,
      default: Long
  ): Either[RunnerError, Long] =
    values.get(name) match
      case None => Right(default)
      case Some(value) =>
        value.toLongOption.toRight(
          RunnerError.InvalidLong(name, value)
        )

object Options:
  def parse(arguments: Vector[String]): Either[RunnerError, Options] =
    val parsed = Map.newBuilder[String, String]
    var index = 0
    var failure = Option.empty[RunnerError]
    while index < arguments.size && failure.isEmpty do
      val option = arguments(index)
      if !option.startsWith("--") || option.size == 2 then
        failure =
          Some(RunnerError.InvalidArgument("option", option))
      else if index + 1 >= arguments.size then
        failure =
          Some(RunnerError.MissingArgument(option.drop(2)))
      else
        parsed += option.drop(2) -> arguments(index + 1)
        index += 2
    failure.toLeft(new Options(parsed.result()))
