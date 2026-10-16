package reframe4s.benchmark.motion

import java.nio.file.Path
import java.util.SplittableRandom

object BenchmarkScheduler:
  def plan(
      workloads: Vector[Workload],
      outputRoot: Path,
      warmupRuns: Int,
      measuredRuns: Int,
      allocatedCoreCounts: Vector[Int],
      timeoutSeconds: Long,
      seed: Long = 6201L
  ): Either[RunnerError, Vector[RunSpec]] =
    if warmupRuns < 0 then
      Left(
        RunnerError.InvalidArgument(
          "warmup-runs",
          warmupRuns.toString
        )
      )
    else if measuredRuns <= 0 then
      Left(
        RunnerError.InvalidArgument(
          "measured-runs",
          measuredRuns.toString
        )
      )
    else if
      allocatedCoreCounts.isEmpty ||
      allocatedCoreCounts.exists(count => count != 1 && count != 4)
    then
      Left(
        RunnerError.InvalidArgument(
          "allocated-core-counts",
          allocatedCoreCounts.mkString(",")
        )
      )
    else
      val rows =
        workloads.flatMap { workload =>
          Court.values.toVector.flatMap { court =>
            allocatedCoreCounts.flatMap { allocatedCores =>
              val labels =
                shuffledImplementations(
                  seed,
                  workload.subject,
                  workload.scenario,
                  court.id,
                  allocatedCores.toString
                )
              val phases =
                Vector(
                  RunPhase.Warmup -> warmupRuns,
                  RunPhase.Measured -> measuredRuns
                )
              phases.flatMap { case (phase, count) =>
                Vector.tabulate(count) { repetition =>
                  balancedRow(labels, repetition).zipWithIndex.map {
                    case (implementation, order) =>
                      val directory =
                        outputRoot
                          .resolve(workload.subject)
                          .resolve(workload.scenario)
                          .resolve(court.id)
                          .resolve(s"cores-$allocatedCores")
                          .resolve(s"${phase.id}-$repetition")
                          .resolve(f"$order%02d-${implementation.id}")
                      ExecutionRegistration
                        .create(
                          implementation,
                          court,
                          allocatedCores
                        )
                        .map { registration =>
                          RunSpec(
                            workload,
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
                            referenceIndex = 0,
                            timeoutSeconds,
                            directory
                          )
                        }
                  }
                }.flatten
              }
            }
          }
        }
      sequence(rows)

  private def balancedRow(
      implementations: Vector[Implementation],
      repetition: Int
  ): Vector[Implementation] =
    val size = implementations.size
    val base =
      Vector.tabulate(size) { position =>
        if position == 0 then 0
        else if position % 2 == 1 then (position + 1) / 2
        else size - position / 2
      }
    base.map(index =>
      implementations((index + repetition) % size)
    )

  private def shuffledImplementations(
      seed: Long,
      parts: String*
  ): Vector[Implementation] =
    val derived =
      parts.foldLeft(seed) { (current, part) =>
        part.foldLeft(current)((value, character) =>
          value * 6364136223846793005L + character.toLong + 1L
        )
      }
    val random = new SplittableRandom(derived)
    val values = Implementation.required.toArray
    var index = values.length - 1
    while index > 0 do
      val selected = random.nextInt(index + 1)
      val swap = values(index)
      values(index) = values(selected)
      values(selected) = swap
      index -= 1
    values.toVector

  private def sequence[A](
      values: Vector[Either[RunnerError, A]]
  ): Either[RunnerError, Vector[A]] =
    values.foldLeft[Either[RunnerError, Vector[A]]](Right(Vector.empty)) {
      (accumulated, value) =>
        for
          found <- accumulated
          next <- value
        yield found :+ next
    }

object PlanIo:
  private val Header =
    Vector(
      "subject",
      "scenario",
      "input",
      "mask",
      "court",
      "implementation",
      "phase",
      "repetition",
      "order",
      "allocated_core_count",
      "requested_worker_count",
      "effective_worker_count",
      "effective_worker_evidence",
      "reference_policy",
      "reference_index",
      "timeout_seconds",
      "output_directory"
    )

  def readWorkloads(path: Path): Either[RunnerError, Vector[Workload]] =
    EvidenceIo.readLines(path).flatMap {
      case header +: rows if header.split("\\t", -1).toVector ==
            Vector("subject", "scenario", "input", "mask") =>
        rows.zipWithIndex.foldLeft[
          Either[RunnerError, Vector[Workload]]
        ](Right(Vector.empty)) { case (accumulated, (row, index)) =>
          val fields = row.split("\\t", -1).toVector
          if fields.size != 4 then
            Left(
              RunnerError.Io(
                "parse workload manifest",
                path,
                s"row ${index + 2} expected four fields"
              )
            )
          else
            accumulated.map(_ :+
              Workload(
                fields(0),
                fields(1),
                Path.of(fields(2)).toAbsolutePath.normalize(),
                Option(fields(3))
                  .filter(_.nonEmpty)
                  .map(value =>
                    Path.of(value).toAbsolutePath.normalize()
                  )
              )
            )
        }
      case _ =>
        Left(
          RunnerError.Io(
            "parse workload manifest",
            path,
            "expected subject, scenario, input, and mask TSV header"
          )
        )
    }

  def writePlan(
      path: Path,
      plan: Vector[RunSpec]
  ): Either[RunnerError, Unit] =
    val rows =
      plan.map { spec =>
        Vector(
          spec.workload.subject,
          spec.workload.scenario,
          spec.workload.input.toString,
          spec.workload.mask.fold("")(_.toString),
          spec.court.id,
          spec.implementation.id,
          spec.phase.id,
          spec.repetition.toString,
          spec.order.toString,
          spec.allocatedCoreCount.toString,
          spec.requestedWorkerCount.toString,
          spec.effectiveWorkerCount.toString,
          spec.effectiveWorkerEvidence.id,
          spec.referencePolicy.id,
          spec.referenceIndex.toString,
          spec.timeoutSeconds.toString,
          spec.outputDirectory.toString
        ).mkString("\t")
      }
    EvidenceIo.writeUtf8(
      path,
      (Header.mkString("\t") +: rows).mkString("", "\n", "\n")
    )

  def readPlan(path: Path): Either[RunnerError, Vector[RunSpec]] =
    EvidenceIo.readLines(path).flatMap {
      case header +: rows
          if header.split("\\t", -1).toVector == Header =>
        rows.zipWithIndex.foldLeft[
          Either[RunnerError, Vector[RunSpec]]
        ](Right(Vector.empty)) { case (accumulated, (row, index)) =>
          val fields = row.split("\\t", -1).toVector
          if fields.size != Header.size then
            Left(
              RunnerError.Io(
                "parse execution plan",
                path,
                s"row ${index + 2} expected ${Header.size} fields"
              )
            )
          else
            for
              found <- accumulated
              court <- Court.parse(fields(4))
              implementation <- Implementation.parse(fields(5))
              phase <- parsePhase(fields(6))
              repetition <- integer(
                path,
                index,
                "repetition",
                fields(7)
              )
              order <- integer(path, index, "order", fields(8))
              allocatedCores <- integer(
                path,
                index,
                "allocated_core_count",
                fields(9)
              )
              requestedWorkers <- integer(
                path,
                index,
                "requested_worker_count",
                fields(10)
              )
              effectiveWorkers <- integer(
                path,
                index,
                "effective_worker_count",
                fields(11)
              )
              workerEvidence <- parseWorkerEvidence(fields(12))
              referencePolicy <- parseReferencePolicy(fields(13))
              reference <- integer(
                path,
                index,
                "reference_index",
                fields(14)
              )
              timeout <- long(
                path,
                index,
                "timeout_seconds",
                fields(15)
              )
              registered <- ExecutionRegistration.create(
                implementation,
                court,
                allocatedCores
              )
              _ <-
                if
                  requestedWorkers == registered.requestedWorkerCount &&
                  effectiveWorkers == registered.effectiveWorkerCount &&
                  workerEvidence == registered.effectiveWorkerEvidence &&
                  referencePolicy == registered.referencePolicy
                then Right(())
                else
                  Left(
                    RunnerError.Io(
                      "parse execution plan",
                      path,
                      s"row ${index + 2} contradicts protocol-v2 execution registration"
                    )
                  )
            yield found :+
              RunSpec(
                Workload(
                  fields(0),
                  fields(1),
                  Path.of(fields(2)),
                  Option(fields(3)).filter(_.nonEmpty).map(Path.of(_))
                ),
                court,
                implementation,
                phase,
                repetition,
                order,
                allocatedCores,
                requestedWorkers,
                effectiveWorkers,
                workerEvidence,
                referencePolicy,
                reference,
                timeout,
                Path.of(fields(16))
              )
        }
      case _ =>
        Left(
          RunnerError.Io(
            "parse execution plan",
            path,
            "unexpected TSV header"
          )
        )
    }

  private def parsePhase(
      value: String
  ): Either[RunnerError, RunPhase] =
    RunPhase.values
      .find(_.id == value)
      .toRight(RunnerError.InvalidArgument("phase", value))

  private def parseWorkerEvidence(
      value: String
  ): Either[RunnerError, EffectiveWorkerEvidence] =
    EffectiveWorkerEvidence.values
      .find(_.id == value)
      .toRight(
        RunnerError.InvalidArgument(
          "effective-worker-evidence",
          value
        )
      )

  private def parseReferencePolicy(
      value: String
  ): Either[RunnerError, ReferencePolicy] =
    ReferencePolicy.values
      .find(_.id == value)
      .toRight(RunnerError.InvalidArgument("reference-policy", value))

  private def integer(
      path: Path,
      row: Int,
      field: String,
      value: String
  ): Either[RunnerError, Int] =
    value.toIntOption.toRight(
      RunnerError.Io(
        "parse execution plan",
        path,
        s"row ${row + 2} has invalid $field: $value"
      )
    )

  private def long(
      path: Path,
      row: Int,
      field: String,
      value: String
  ): Either[RunnerError, Long] =
    value.toLongOption.toRight(
      RunnerError.Io(
        "parse execution plan",
        path,
        s"row ${row + 2} has invalid $field: $value"
      )
    )
