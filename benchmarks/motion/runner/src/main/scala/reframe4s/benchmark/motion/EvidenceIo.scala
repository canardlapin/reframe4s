package reframe4s.benchmark.motion

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

object EvidenceIo:
  def sha256(path: Path): Either[RunnerError, String] =
    try
      val digest = MessageDigest.getInstance("SHA-256")
      val stream = Files.newInputStream(path)
      try
        val buffer = new Array[Byte](64 * 1024)
        var read = stream.read(buffer)
        while read >= 0 do
          if read > 0 then digest.update(buffer, 0, read)
          read = stream.read(buffer)
      finally stream.close()
      Right(digest.digest().map(byte => f"${byte & 0xff}%02x").mkString)
    catch
      case error: Exception =>
        Left(RunnerError.Io("sha256", path, error.getMessage))

  def writeUtf8(path: Path, value: String): Either[RunnerError, Unit] =
    try
      val bytes = value.getBytes(StandardCharsets.UTF_8)
      Files.write(
        path,
        bytes,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
      force(path)
    catch
      case error: Exception =>
        Left(RunnerError.Io("write", path, error.getMessage))

  def force(path: Path): Either[RunnerError, Unit] =
    try
      val channel =
        FileChannel.open(path, StandardOpenOption.WRITE)
      try channel.force(true)
      finally channel.close()
      Right(())
    catch
      case error: Exception =>
        Left(RunnerError.Io("fsync", path, error.getMessage))

  def prepareEmptyDirectory(path: Path): Either[RunnerError, Unit] =
    try
      if Files.exists(path) then
        val entries = Files.list(path)
        try
          if entries.findFirst().isPresent then
            Left(RunnerError.NonEmptyOutputDirectory(path))
          else Right(())
        finally entries.close()
      else
        Files.createDirectories(path)
        Right(())
    catch
      case error: Exception =>
        Left(RunnerError.Io("prepare directory", path, error.getMessage))

  def readLines(path: Path): Either[RunnerError, Vector[String]] =
    try Right(Files.readAllLines(path, StandardCharsets.UTF_8).toArray.toVector.map(_.toString))
    catch
      case error: Exception =>
        Left(RunnerError.Io("read", path, error.getMessage))

  def directoryBytes(path: Path): Either[RunnerError, Long] =
    try
      val entries = Files.walk(path)
      try
        Right(
          entries.iterator.asScala
            .filter(Files.isRegularFile(_))
            .map(Files.size(_))
            .foldLeft(0L)(Math.addExact)
        )
      finally entries.close()
    catch
      case error: Exception =>
        Left(
          RunnerError.Io(
            "measure materialized output bytes",
            path,
            error.getMessage
          )
        )

  def readMatrices(
      path: Path,
      valuesPerRow: Int
  ): Either[RunnerError, Vector[Matrix4]] =
    readLines(path).flatMap { lines =>
      val content =
        lines
          .map(_.trim)
          .filter(line => line.nonEmpty && !line.startsWith("#"))
      val parsed = Vector.newBuilder[Matrix4]
      var failure = Option.empty[RunnerError]
      content.zipWithIndex.foreach { case (line, index) =>
        if failure.isEmpty then
          val tokens = line.split("[,\\s]+").toVector.filter(_.nonEmpty)
          val numeric =
            tokens.foldLeft[Either[RunnerError, Vector[Double]]](
              Right(Vector.empty)
            ) { (accumulated, token) =>
              for
                values <- accumulated
                value <- token.toDoubleOption.toRight(
                  RunnerError.InvalidPoseFile(
                    path,
                    s"row ${index + 1} has non-numeric token $token"
                  )
                )
              yield values :+ value
            }
          numeric match
            case Left(error) =>
              failure = Some(error)
            case Right(values) if values.size != valuesPerRow =>
              failure =
                Some(
                  RunnerError.InvalidPoseFile(
                    path,
                    s"row ${index + 1} expected $valuesPerRow values, got ${values.size}"
                  )
                )
            case Right(values) =>
              val rowMajor =
                if valuesPerRow == 12 then
                  values ++ Vector(0.0, 0.0, 0.0, 1.0)
                else values
              Matrix4.create(rowMajor) match
                case Left(error) =>
                  failure = Some(error)
                case Right(matrix) =>
                  parsed += matrix
      }
      failure.toLeft(parsed.result())
    }

  def writeCanonicalPoses(
      path: Path,
      matrices: Vector[Matrix4]
  ): Either[RunnerError, Unit] =
    val header =
      "frame," +
        (for
          row <- 0 until 4
          column <- 0 until 4
        yield s"m$row$column").mkString(",")
    val rows =
      matrices.zipWithIndex.map { case (matrix, index) =>
        (Vector((index + 1).toString) ++
          matrix.rowMajor.map(java.lang.Double.toString)).mkString(",")
      }
    writeUtf8(path, (header +: rows).mkString("", "\n", "\n"))

  def readCanonicalPoses(
      path: Path
  ): Either[RunnerError, Vector[Matrix4]] =
    readLines(path).flatMap { lines =>
      lines.headOption match
        case None =>
          Left(RunnerError.InvalidPoseFile(path, "file is empty"))
        case Some(header) =>
          val expected =
            "frame," +
              (for
                row <- 0 until 4
                column <- 0 until 4
              yield s"m$row$column").mkString(",")
          if header != expected then
            Left(
              RunnerError.InvalidPoseFile(
                path,
                s"unexpected header: $header"
              )
            )
          else
            val matrices = Vector.newBuilder[Matrix4]
            var failure = Option.empty[RunnerError]
            lines.drop(1).zipWithIndex.foreach { case (line, index) =>
              if failure.isEmpty then
                val tokens = line.split(",", -1).toVector
                if tokens.size != 17 then
                  failure =
                    Some(
                      RunnerError.InvalidPoseFile(
                        path,
                        s"row ${index + 2} expected 17 columns, got ${tokens.size}"
                      )
                    )
                else if
                  tokens.headOption.flatMap(_.toIntOption) != Some(index + 1)
                then
                  failure =
                    Some(
                      RunnerError.InvalidPoseFile(
                        path,
                        s"row ${index + 2} has a nonconsecutive frame"
                      )
                    )
                else
                  val values =
                    tokens.drop(1).foldLeft[
                      Either[RunnerError, Vector[Double]]
                    ](Right(Vector.empty)) { (accumulated, token) =>
                      for
                        found <- accumulated
                        value <- token.toDoubleOption.toRight(
                          RunnerError.InvalidPoseFile(
                            path,
                            s"row ${index + 2} has non-numeric value $token"
                          )
                        )
                      yield found :+ value
                    }
                  values.flatMap(Matrix4.create) match
                    case Left(error) =>
                      failure = Some(error)
                    case Right(matrix) =>
                      matrices += matrix
            }
            failure.toLeft(matrices.result())
    }

  def readStageTimes(
      path: Path,
      fallbackEndToEndSeconds: Double
  ): Either[RunnerError, StageTimes] =
    readLines(path).flatMap {
      case header +: values +: _ =>
        val names = header.split("\\t", -1).toVector
        val fields = values.split("\\t", -1).toVector
        if names.size != fields.size then
          Left(
            RunnerError.InvalidStageTimes(
              path,
              "header and value counts differ"
            )
          )
        else
          val mapping = names.zip(fields).toMap
          def optional(name: String): Either[RunnerError, Option[Double]] =
            mapping.get(name) match
              case None | Some("") | Some("NA") => Right(None)
              case Some(value) =>
                value.toDoubleOption
                  .filter(number => number.isFinite && number >= 0.0)
                  .map(Some(_))
                  .toRight(
                    RunnerError.InvalidStageTimes(
                      path,
                      s"$name is not a non-negative finite number: $value"
                    )
                  )
          for
            decode <- optional("decode_seconds")
            prepare <- optional("prepare_seconds")
            estimate <- optional("estimate_seconds")
            apply <- optional("apply_seconds")
            encode <- optional("encode_seconds")
            report <- optional("report_seconds")
          yield
            StageTimes(
              StageTimingSource.Instrumented,
              decode,
              prepare,
              estimate,
              apply,
              encode,
              report,
              fallbackEndToEndSeconds
            )
      case _ =>
        Left(
          RunnerError.InvalidStageTimes(
            path,
            "expected a tab-separated header and value row"
          )
        )
    }
