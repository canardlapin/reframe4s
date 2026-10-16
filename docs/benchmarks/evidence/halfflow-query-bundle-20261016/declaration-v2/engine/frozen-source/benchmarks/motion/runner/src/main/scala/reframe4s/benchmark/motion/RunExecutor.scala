package reframe4s.benchmark.motion

import image4s.nifti.Nifti

import java.nio.file.Files
import java.nio.file.Path

object RunExecutor:
  val SchemaVersion =
    "reframe4s.motion-superiority.run/v2"

  def execute(
      spec: RunSpec,
      adapter: ImplementationAdapter,
      config: AdapterConfig,
      protocolSha256: String
  ): Either[RunnerError, RunRecord] =
    val layout = RunLayout(spec.outputDirectory)
    for
      _ <- EvidenceIo.prepareEmptyDirectory(layout.root)
      version <- runVersion(adapter, config, layout)
      command = adapter.command(spec, layout, config)
      record <- executeCommand(
        spec,
        adapter,
        command,
        layout,
        version,
        protocolSha256
      )
      _ <- EvidenceIo.writeUtf8(
        layout.runRecord,
        RunRecordJson.encode(record)
      )
    yield record

  private def runVersion(
      adapter: ImplementationAdapter,
      config: AdapterConfig,
      layout: RunLayout
  ): Either[RunnerError, ProcessOutcome] =
    ProcessExecutor
      .run(
        adapter.versionCommand(config),
        layout.versionStdout,
        layout.versionStderr,
        timeoutSeconds = 60L
      )
      .orElse {
        for
          _ <- ensureFile(layout.versionStdout)
          _ <- ensureFile(layout.versionStderr)
        yield
          ProcessOutcome(
            exitStatus = 127,
            timedOut = false,
            elapsedSeconds = 0.0,
            peakRssBytes = None
          )
      }

  private def executeCommand(
      spec: RunSpec,
      adapter: ImplementationAdapter,
      command: CommandSpec,
      layout: RunLayout,
      version: ProcessOutcome,
      protocolSha256: String
  ): Either[RunnerError, RunRecord] =
    val endToEndStarted = System.nanoTime()
    val process =
      if version.exitStatus == 0 then
        ProcessExecutor.run(
          command,
          layout.stdout,
          layout.stderr,
          spec.timeoutSeconds
        )
      else
        Left(
          RunnerError.ProcessStart(
            command.arguments,
            s"version probe failed with status ${version.exitStatus}"
          )
        )
    val outcome =
      process match
        case Right(value) => value
        case Left(_) =>
          ProcessOutcome(
            exitStatus = 127,
            timedOut = false,
            elapsedSeconds = 0.0,
            peakRssBytes = None
          )
    val normalization =
      process.flatMap { completed =>
        if completed.exitStatus != 0 then
          Left(
            RunnerError.ProcessStart(
              command.arguments,
              s"process exited ${completed.exitStatus}"
            )
          )
        else
          adapter.normalize(spec, layout).flatMap { _ =>
            if spec.court == Court.NativeEndToEnd then
              if Files.isRegularFile(layout.correctedImage) then
                EvidenceIo.force(layout.correctedImage)
              else
                Left(
                  RunnerError.MissingOutput(
                    "corrected NIfTI",
                    layout.correctedImage
                  )
                )
            else Right(())
          }
      }
    val endToEndSeconds =
      (System.nanoTime() - endToEndStarted).toDouble / 1e9
    val completion =
      normalization.flatMap { _ =>
        if spec.court == Court.CommonResamplerEstimation then
          CommonCourtResampler
            .run(
              spec.workload.input,
              layout.canonicalPoses,
              layout.correctedImage
            )
            .flatMap(_ => validateCorrected(spec, layout))
        else validateCorrected(spec, layout)
      }

    for
      _ <- ensureFile(layout.stdout)
      _ <- ensureFile(layout.stderr)
      stdoutHash <- EvidenceIo.sha256(layout.stdout)
      stderrHash <- EvidenceIo.sha256(layout.stderr)
      versionStdoutHash <- EvidenceIo.sha256(layout.versionStdout)
      versionStderrHash <- EvidenceIo.sha256(layout.versionStderr)
      poseHash <- optionalHash(layout.canonicalPoses)
      correctedHash <- optionalHash(layout.correctedImage)
      stageTimes <- stageTimes(layout, endToEndSeconds)
      outputBytes <- EvidenceIo.directoryBytes(layout.root)
    yield
      val termination =
        if outcome.timedOut then Termination.Timeout
        else if process.isLeft || outcome.exitStatus != 0 then
          Termination.ProcessFailure
        else if completion.isLeft then Termination.InvalidOutput
        else Termination.Success
      RunRecord(
        schemaVersion = SchemaVersion,
        protocolSha256 = protocolSha256,
        subject = spec.workload.subject,
        scenario = spec.workload.scenario,
        court = spec.court.id,
        implementation = spec.implementation.id,
        phase = spec.phase.id,
        repetition = spec.repetition,
        order = spec.order,
        allocatedCoreCount = spec.allocatedCoreCount,
        requestedWorkerCount = spec.requestedWorkerCount,
        effectiveWorkerCount = spec.effectiveWorkerCount,
        effectiveWorkerEvidence = spec.effectiveWorkerEvidence.id,
        cpuAffinity =
          sys.env.getOrElse(
            "MOTION_BENCHMARK_CPU_SET",
            "unconstrained"
          ),
        referencePolicy = spec.referencePolicy.id,
        command = command.arguments,
        workingDirectory =
          command.workingDirectory.toAbsolutePath.normalize().toString,
        environment = command.environment.sortBy(_._1),
        exitStatus = outcome.exitStatus,
        termination = termination.id,
        failure =
          process.swap.toOption
            .orElse(completion.swap.toOption)
            .map(_.message),
        stageTimes = stageTimes,
        peakRssBytes = outcome.peakRssBytes,
        materializedOutputBytes = outputBytes,
        stdoutSha256 = stdoutHash,
        stderrSha256 = stderrHash,
        poseSha256 = poseHash,
        correctedImageSha256 = correctedHash,
        tool =
          ToolMetadata(
            executable =
              command.arguments.headOption.getOrElse("unavailable"),
            registeredPin = adapter.registeredPin,
            versionExitStatus = version.exitStatus,
            versionStdoutSha256 = versionStdoutHash,
            versionStderrSha256 = versionStderrHash
          ),
        pipeline = PipelineMetadata.forRun(spec)
      )

  private def validateCorrected(
      spec: RunSpec,
      layout: RunLayout
  ): Either[RunnerError, Unit] =
    if !Files.isRegularFile(layout.correctedImage) then
      Left(
        RunnerError.MissingOutput(
          "corrected NIfTI",
          layout.correctedImage
        )
      )
    else
      for
        input <- Nifti
          .readHeader(spec.workload.input)
          .left
          .map(error => RunnerError.Nifti(error.message))
        output <- Nifti
          .readScalar(layout.correctedImage)
          .left
          .map(error => RunnerError.Nifti(error.message))
        _ <-
          if output.header.logicalShape == input.logicalShape then Right(())
          else
            Left(
              RunnerError.UnsupportedInput(
                s"corrected shape ${output.header.logicalShape} differs from input ${input.logicalShape}"
              )
            )
        _ <-
          val actual = output.header.preferredAffine.rowMajor
          val expected = input.preferredAffine.rowMajor
          val maximum =
            actual.zip(expected).map { case (left, right) =>
              math.abs(left - right)
            }.maxOption.getOrElse(0.0)
          if maximum <= 1e-5 then Right(())
          else
            Left(
              RunnerError.UnsupportedInput(
                s"corrected affine differs from input by $maximum"
              )
            )
        _ <-
          output.image.fold(
            _ =>
              Left(
                RunnerError.UnsupportedInput(
                  "corrected image unexpectedly decoded as D2"
                )
              ),
            d3 =>
              if d3.value.data.iterator.forall(_.isFinite) then Right(())
              else
                Left(
                  RunnerError.UnsupportedInput(
                    "corrected image contains non-finite samples"
                  )
                )
          )
      yield ()

  private def stageTimes(
      layout: RunLayout,
      endToEndSeconds: Double
  ): Either[RunnerError, StageTimes] =
    if Files.isRegularFile(layout.stageTimes) then
      EvidenceIo.readStageTimes(layout.stageTimes, endToEndSeconds)
    else
      Right(
        StageTimes(
          StageTimingSource.RunnerEndToEndOnly,
          None,
          None,
          None,
          None,
          None,
          None,
          endToEndSeconds
        )
      )

  private def optionalHash(
      path: Path
  ): Either[RunnerError, Option[String]] =
    if Files.isRegularFile(path) then EvidenceIo.sha256(path).map(Some(_))
    else Right(None)

  private def ensureFile(path: Path): Either[RunnerError, Unit] =
    if Files.exists(path) then Right(())
    else
      EvidenceIo.writeUtf8(path, "")
