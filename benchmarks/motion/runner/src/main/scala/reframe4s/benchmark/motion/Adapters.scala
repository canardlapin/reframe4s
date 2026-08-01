package reframe4s.benchmark.motion

import image4s.nifti.Nifti

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

final case class RunLayout(root: Path):
  val stdout: Path = root.resolve("stdout.txt")
  val stderr: Path = root.resolve("stderr.txt")
  val versionStdout: Path = root.resolve("version-stdout.txt")
  val versionStderr: Path = root.resolve("version-stderr.txt")
  val rawMatrices: Path = root.resolve("raw-matrices.txt")
  val canonicalPoses: Path = root.resolve("poses.csv")
  val correctedImage: Path = root.resolve("corrected.nii.gz")
  val stageTimes: Path = root.resolve("stage-times.tsv")
  val runRecord: Path = root.resolve("run.json")
  val afniParameters: Path = root.resolve("afni-motion.1D")
  val fslOutputBase: Path = root.resolve("corrected")
  val fslMatrixDirectory: Path = root.resolve("corrected.mat")

final case class AdapterConfig(
    afniExecutable: String,
    mcflirtExecutable: String,
    pythonExecutable: String,
    nifreezeWrapper: Path,
    javaExecutable: String,
    candidateClasspath: String,
    workingDirectory: Path
)

trait ImplementationAdapter:
  def implementation: Implementation
  def registeredPin: String

  def command(
      spec: RunSpec,
      layout: RunLayout,
      config: AdapterConfig
  ): CommandSpec

  def versionCommand(
      config: AdapterConfig
  ): CommandSpec

  def normalize(
      spec: RunSpec,
      layout: RunLayout
  ): Either[RunnerError, Unit]

object ImplementationAdapter:
  private val mainClass =
    "reframe4s.benchmark.motion.MotionBenchmarkMain"

  val candidate: ImplementationAdapter =
    new ImplementationAdapter:
      val implementation = Implementation.Reframe4sMotion
      val registeredPin = "clean-revision-required-by-admission-lock"

      def command(
          spec: RunSpec,
          layout: RunLayout,
          config: AdapterConfig
      ): CommandSpec =
        CommandSpec(
          Vector(
            config.javaExecutable,
            "-cp",
            config.candidateClasspath,
            mainClass,
            "candidate",
            "--input",
            spec.workload.input.toString,
            "--output-dir",
            layout.root.toString,
            "--court",
            spec.court.id,
            "--reference-index",
            spec.referenceIndex.toString,
            "--threads",
            spec.requestedWorkerCount.toString
          ),
          benchmarkEnvironment(spec.requestedWorkerCount),
          config.workingDirectory
        )

      def versionCommand(config: AdapterConfig): CommandSpec =
        CommandSpec(
          Vector(
            config.javaExecutable,
            "-cp",
            config.candidateClasspath,
            mainClass,
            "--version"
          ),
          Vector.empty,
          config.workingDirectory
        )

      def normalize(
          spec: RunSpec,
          layout: RunLayout
      ): Either[RunnerError, Unit] =
        validateCanonical(spec, layout)

  val afni: ImplementationAdapter =
    new ImplementationAdapter:
      val implementation = Implementation.Afni3dvolreg
      val registeredPin = "AFNI_26.1.04+linux-amd64-digest-required"

      def command(
          spec: RunSpec,
          layout: RunLayout,
          config: AdapterConfig
      ): CommandSpec =
        val prefix =
          if spec.court == Court.CommonResamplerEstimation then "NULL"
          else layout.correctedImage.toString
        CommandSpec(
          Vector(
            config.afniExecutable,
            "-base",
            spec.referenceIndex.toString,
            "-twopass",
            "-zpad",
            "1",
            "-heptic",
            "-1Dfile",
            layout.afniParameters.toString,
            "-1Dmatrix_save",
            layout.rawMatrices.toString,
            "-prefix",
            prefix,
            spec.workload.input.toString
          ),
          benchmarkEnvironment(spec.requestedWorkerCount),
          config.workingDirectory
        )

      def versionCommand(config: AdapterConfig): CommandSpec =
        CommandSpec(
          Vector(config.afniExecutable, "-help"),
          Vector.empty,
          config.workingDirectory
        )

      def normalize(
          spec: RunSpec,
          layout: RunLayout
      ): Either[RunnerError, Unit] =
        for
          raw <- EvidenceIo.readMatrices(layout.rawMatrices, 12)
          canonical <- traverse(
            raw,
            TransformConvention
              .afniBaseToInputLpsToMovingToFixedRas
          )
          _ <- EvidenceIo.writeCanonicalPoses(
            layout.canonicalPoses,
            canonical
          )
          _ <- validateCanonical(spec, layout)
        yield ()

  val nifreeze: ImplementationAdapter =
    new ImplementationAdapter:
      val implementation = Implementation.Nifreeze
      val registeredPin =
        "a3985fe5a4763f0a2eafb24e78d88fcfad60992a+container-digest-required"

      def command(
          spec: RunSpec,
          layout: RunLayout,
          config: AdapterConfig
      ): CommandSpec =
        val base =
          Vector(
            config.pythonExecutable,
            config.nifreezeWrapper.toString,
            "--input",
            spec.workload.input.toString,
            "--output-dir",
            layout.root.toString,
            "--model",
            "mean",
            "--nthreads",
            spec.requestedWorkerCount.toString,
            "--seed",
            "4401",
            "--reference-index",
            spec.referenceIndex.toString,
            "--court",
            spec.court.id
          )
        val withMask =
          if spec.court == Court.NativeEndToEnd then
            spec.workload.mask.fold(base)(path =>
              base ++ Vector("--mask", path.toString)
            )
          else base
        CommandSpec(
          withMask,
          benchmarkEnvironment(spec.requestedWorkerCount),
          config.workingDirectory
        )

      def versionCommand(config: AdapterConfig): CommandSpec =
        CommandSpec(
          Vector(
            config.pythonExecutable,
            config.nifreezeWrapper.toString,
            "--probe"
          ),
          Vector.empty,
          config.workingDirectory
        )

      def normalize(
          spec: RunSpec,
          layout: RunLayout
      ): Either[RunnerError, Unit] =
        for
          raw <- EvidenceIo.readMatrices(layout.rawMatrices, 16)
          canonical <- traverse(
            raw,
            TransformConvention.nifreezePullToMovingToFixed
          )
          _ <- EvidenceIo.writeCanonicalPoses(
            layout.canonicalPoses,
            canonical
          )
          _ <- validateCanonical(spec, layout)
        yield ()

  val fsl: ImplementationAdapter =
    new ImplementationAdapter:
      val implementation = Implementation.FslMcflirt
      val registeredPin =
        "FSL-6.0.7.22-manifest-31062d2e+package-lock-required"

      def command(
          spec: RunSpec,
          layout: RunLayout,
          config: AdapterConfig
      ): CommandSpec =
        CommandSpec(
          Vector(
            config.mcflirtExecutable,
            "-in",
            spec.workload.input.toString,
            "-out",
            layout.fslOutputBase.toString,
            "-refvol",
            spec.referenceIndex.toString,
            "-plots",
            "-mats",
            "-spline_final"
          ),
          benchmarkEnvironment(spec.requestedWorkerCount),
          config.workingDirectory
        )

      def versionCommand(config: AdapterConfig): CommandSpec =
        CommandSpec(
          Vector(config.mcflirtExecutable, "-help"),
          Vector.empty,
          config.workingDirectory
        )

      def normalize(
          spec: RunSpec,
          layout: RunLayout
      ): Either[RunnerError, Unit] =
        for
          header <- Nifti
            .readHeader(spec.workload.input)
            .left
            .map(error => RunnerError.Nifti(error.message))
          indexToRas <- Matrix4.create(header.preferredAffine.rowMajor)
          raw <- readFslMatrices(layout.fslMatrixDirectory)
          canonical <- traverse(
            raw,
            matrix =>
              TransformConvention.flirtToMovingToFixedRas(
                matrix,
                indexToRas,
                header.spatialShape,
                indexToRas,
                header.spatialShape
              )
          )
          _ <- EvidenceIo.writeCanonicalPoses(
            layout.canonicalPoses,
            canonical
          )
          _ <- validateCanonical(spec, layout)
        yield ()

  val all: Vector[ImplementationAdapter] =
    Vector(candidate, afni, nifreeze, fsl)

  def forImplementation(
      implementation: Implementation
  ): ImplementationAdapter =
    implementation match
      case Implementation.Reframe4sMotion => candidate
      case Implementation.Afni3dvolreg    => afni
      case Implementation.Nifreeze        => nifreeze
      case Implementation.FslMcflirt      => fsl

  private def benchmarkEnvironment(
      threads: Int
  ): Vector[(String, String)] =
    Vector(
      "OMP_NUM_THREADS" -> threads.toString,
      "OPENBLAS_NUM_THREADS" -> "1",
      "MKL_NUM_THREADS" -> "1",
      "VECLIB_MAXIMUM_THREADS" -> "1",
      "NUMEXPR_NUM_THREADS" -> "1",
      "FSLOUTPUTTYPE" -> "NIFTI_GZ"
    )

  private def validateCanonical(
      spec: RunSpec,
      layout: RunLayout
  ): Either[RunnerError, Unit] =
    for
      header <- Nifti
        .readHeader(spec.workload.input)
        .left
        .map(error => RunnerError.Nifti(error.message))
      matrices <- EvidenceIo.readCanonicalPoses(layout.canonicalPoses)
      expected =
        header.nonSpatialShape.headOption.getOrElse(1)
      _ <-
        if matrices.size == expected then Right(())
        else
          Left(
            RunnerError.InvalidPoseFile(
              layout.canonicalPoses,
              s"expected $expected frames, got ${matrices.size}"
            )
          )
      _ <- matrices.zipWithIndex.foldLeft[Either[RunnerError, Unit]](
        Right(())
      ) { case (validated, (matrix, index)) =>
        validated.flatMap(_ => validateRigid(matrix, index))
      }
    yield ()

  private def validateRigid(
      matrix: Matrix4,
      frame: Int
  ): Either[RunnerError, Unit] =
    val bottom =
      Vector(matrix(3, 0), matrix(3, 1), matrix(3, 2), matrix(3, 3))
    if
      math.abs(bottom(0)) > 1e-6 ||
      math.abs(bottom(1)) > 1e-6 ||
      math.abs(bottom(2)) > 1e-6 ||
      math.abs(bottom(3) - 1.0) > 1e-6
    then
      Left(
        RunnerError.InvalidMatrix(
          s"frame $frame has non-affine bottom row $bottom"
        )
      )
    else
      var maximumError = 0.0
      var row = 0
      while row < 3 do
        var column = 0
        while column < 3 do
          var inner = 0
          var total = 0.0
          while inner < 3 do
            total += matrix(inner, row) * matrix(inner, column)
            inner += 1
          val expected = if row == column then 1.0 else 0.0
          maximumError =
            math.max(maximumError, math.abs(total - expected))
          column += 1
        row += 1
      val determinantError = math.abs(matrix.determinant3 - 1.0)
      if maximumError > 2e-3 || determinantError > 2e-3 then
        Left(
          RunnerError.InvalidMatrix(
            s"frame $frame is not rigid: orthogonality error=$maximumError determinant error=$determinantError"
          )
        )
      else Right(())

  private def readFslMatrices(
      directory: Path
  ): Either[RunnerError, Vector[Matrix4]] =
    if !Files.isDirectory(directory) then
      Left(RunnerError.MissingOutput("FSL matrix directory", directory))
    else
      try
        val entries = Files.list(directory)
        val paths =
          try
            entries.iterator.asScala
              .filter(path => path.getFileName.toString.startsWith("MAT_"))
              .toVector
              .sortBy(_.getFileName.toString)
          finally entries.close()
        paths.foldLeft[Either[RunnerError, Vector[Matrix4]]](
          Right(Vector.empty)
        ) { (accumulated, path) =>
          for
            found <- accumulated
            rows <- EvidenceIo.readLines(path)
            values <- parseFslValues(path, rows)
            matrix <- Matrix4.create(values)
          yield found :+ matrix
        }
      catch
        case error: Exception =>
          Left(
            RunnerError.Io(
              "list FSL matrices",
              directory,
              error.getMessage
            )
          )

  private def parseFslValues(
      path: Path,
      rows: Vector[String]
  ): Either[RunnerError, Vector[Double]] =
    val tokens =
      rows
        .flatMap(_.trim.split("\\s+").toVector)
        .filter(_.nonEmpty)
    tokens.foldLeft[Either[RunnerError, Vector[Double]]](
      Right(Vector.empty)
    ) { (accumulated, token) =>
      for
        values <- accumulated
        value <- token.toDoubleOption.toRight(
          RunnerError.InvalidPoseFile(
            path,
            s"non-numeric token $token"
          )
        )
      yield values :+ value
    }

  private def traverse[A, B](
      values: Vector[A],
      transform: A => Either[RunnerError, B]
  ): Either[RunnerError, Vector[B]] =
    values.foldLeft[Either[RunnerError, Vector[B]]](
      Right(Vector.empty)
    ) { (accumulated, value) =>
      for
        found <- accumulated
        converted <- transform(value)
      yield found :+ converted
    }
