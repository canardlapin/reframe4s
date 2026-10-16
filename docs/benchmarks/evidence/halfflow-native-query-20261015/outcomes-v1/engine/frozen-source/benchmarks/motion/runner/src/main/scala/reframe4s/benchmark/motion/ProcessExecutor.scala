package reframe4s.benchmark.motion

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

final case class CommandSpec(
    arguments: Vector[String],
    environment: Vector[(String, String)],
    workingDirectory: Path
)

final case class ProcessOutcome(
    exitStatus: Int,
    timedOut: Boolean,
    elapsedSeconds: Double,
    peakRssBytes: Option[Long]
)

object ProcessExecutor:
  def run(
      command: CommandSpec,
      stdout: Path,
      stderr: Path,
      timeoutSeconds: Long
  ): Either[RunnerError, ProcessOutcome] =
    if command.arguments.isEmpty then
      Left(RunnerError.ProcessStart(Vector.empty, "empty command"))
    else
      val builder = new ProcessBuilder(command.arguments*)
      val _ = builder.directory(command.workingDirectory.toFile)
      val processEnvironment = builder.environment()
      command.environment.foreach { case (name, value) =>
        val _ = processEnvironment.put(name, value)
      }
      val _ = builder.redirectOutput(stdout.toFile)
      val _ = builder.redirectError(stderr.toFile)
      try
        val started = System.nanoTime()
        val process = builder.start()
        var peakRss = Option.empty[Long]
        val deadline =
          started + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while process.isAlive && System.nanoTime() < deadline do
          peakRss = maximum(peakRss, linuxResidentTreeBytes(process))
          Thread.sleep(5L)
        val timedOut = process.isAlive
        if timedOut then
          val _ = process.destroy()
          if !process.waitFor(2L, TimeUnit.SECONDS) then
            val _ = process.destroyForcibly()
            val _ = process.waitFor(2L, TimeUnit.SECONDS)
        val finished = System.nanoTime()
        peakRss = maximum(peakRss, linuxResidentTreeBytes(process))
        for
          _ <- EvidenceIo.force(stdout)
          _ <- EvidenceIo.force(stderr)
        yield
          ProcessOutcome(
            exitStatus = if timedOut then 124 else process.exitValue(),
            timedOut = timedOut,
            elapsedSeconds = (finished - started).toDouble / 1e9,
            peakRssBytes = peakRss
          )
      catch
        case error: Exception =>
          Left(
            RunnerError.ProcessStart(
              command.arguments,
              error.getMessage
            )
          )

  private def linuxResidentTreeBytes(process: Process): Option[Long] =
    val descendants = process.toHandle.descendants()
    val processIds =
      try
        process.pid() +:
          descendants.iterator().asScala.map(_.pid()).toVector
      finally descendants.close()
    val values = processIds.flatMap(linuxResidentBytes)
    if values.isEmpty then None
    else Some(values.foldLeft(0L)(_ + _))

  private def linuxResidentBytes(processId: Long): Option[Long] =
    val status = Path.of("/proc", processId.toString, "status")
    if !Files.isRegularFile(status) then None
    else
      try
        Files
          .readAllLines(status, StandardCharsets.US_ASCII)
          .toArray
          .iterator
          .map(_.toString)
          .collectFirst {
            case line if line.startsWith("VmRSS:") =>
              val parts = line.trim.split("\\s+")
              parts.lift(1).flatMap(_.toLongOption).map(_ * 1024L)
          }
          .flatten
      catch
        case _: Exception => None

  private def maximum(
      left: Option[Long],
      right: Option[Long]
  ): Option[Long] =
    (left, right) match
      case (Some(a), Some(b)) => Some(math.max(a, b))
      case (Some(a), None)    => Some(a)
      case (None, Some(b))    => Some(b)
      case (None, None)       => None
