package reframe4s.benchmark.motion

import java.nio.file.Path

final case class MotionMetricScoreSpec(
    subject: String,
    scenario: String,
    stratum: String,
    court: Court,
    implementation: Implementation,
    phase: String,
    repetition: Int,
    allocatedCoreCount: Int,
    referenceIndex: Int,
    runRecord: Path,
    corrected: Path,
    poses: Path,
    truth: Path,
    truthPoses: Path,
    mask: Path,
    landmarks: Path,
    output: Path
)

object MotionMetricPlanIo:
  private val Header =
    Vector(
      "subject",
      "scenario",
      "stratum",
      "court",
      "implementation",
      "phase",
      "repetition",
      "allocated_core_count",
      "reference_index",
      "run_record",
      "corrected",
      "poses",
      "truth",
      "truth_poses",
      "mask",
      "landmarks",
      "output"
    )

  def read(path: Path): Either[RunnerError, Vector[MotionMetricScoreSpec]] =
    EvidenceIo.readLines(path).flatMap {
      case header +: rows
          if header.split("\\t", -1).toVector == Header =>
        rows.zipWithIndex.foldLeft[
          Either[RunnerError, Vector[MotionMetricScoreSpec]]
        ](Right(Vector.empty)) { case (accumulated, (row, index)) =>
          val fields = row.split("\\t", -1).toVector
          if fields.size != Header.size then
            Left(
              RunnerError.Io(
                "parse metric plan",
                path,
                s"row ${index + 2} expected ${Header.size} fields"
              )
            )
          else
            for
              found <- accumulated
              court <- Court.parse(fields(3))
              implementation <- Implementation.parse(fields(4))
              _ <-
                if
                  fields(2) == "synthetic" ||
                  fields(2) == "real_anatomy"
                then Right(())
                else
                  Left(
                    RunnerError.InvalidArgument(
                      "stratum",
                      fields(2)
                    )
                  )
              _ <-
                if fields(5) == "measured" then Right(())
                else
                  Left(
                    RunnerError.InvalidArgument("phase", fields(5))
                  )
              repetition <- integer(path, index, "repetition", fields(6))
              allocated <- integer(
                path,
                index,
                "allocated_core_count",
                fields(7)
              )
              reference <- integer(
                path,
                index,
                "reference_index",
                fields(8)
              )
              _ <-
                if repetition == 0 && allocated == 4 && reference == 0 then
                  Right(())
                else
                  Left(
                    RunnerError.Io(
                      "parse metric plan",
                      path,
                      s"row ${index + 2} must select measured repetition 0 at four cores and reference 0"
                    )
                  )
            yield
              found :+
                MotionMetricScoreSpec(
                  subject = fields(0),
                  scenario = fields(1),
                  stratum = fields(2),
                  court = court,
                  implementation = implementation,
                  phase = fields(5),
                  repetition = repetition,
                  allocatedCoreCount = allocated,
                  referenceIndex = reference,
                  runRecord = absolute(fields(9)),
                  corrected = absolute(fields(10)),
                  poses = absolute(fields(11)),
                  truth = absolute(fields(12)),
                  truthPoses = absolute(fields(13)),
                  mask = absolute(fields(14)),
                  landmarks = absolute(fields(15)),
                  output = absolute(fields(16))
                )
        }
      case _ =>
        Left(
          RunnerError.Io(
            "parse metric plan",
            path,
            s"expected ${Header.mkString(", ")} TSV header"
          )
        )
    }

  private def absolute(value: String): Path =
    Path.of(value).toAbsolutePath.normalize()

  private def integer(
      path: Path,
      row: Int,
      name: String,
      value: String
  ): Either[RunnerError, Int] =
    value.toIntOption.toRight(
      RunnerError.Io(
        "parse metric plan",
        path,
        s"row ${row + 2} has invalid $name: $value"
      )
    )
