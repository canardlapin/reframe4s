package reframe4s.benchmark.motion

import image4s.Axis
import image4s.AxisKind
import image4s.BoundaryPolicy
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.ScalarImage
import image4s.nifti.Nifti
import image4s.nifti.NiftiHeader
import image4s.nifti.NiftiWriteOptions
import ravel.AnyRank
import image4s.geometry.D3
import image4s.geometry.Frame
import reframe4s.motion.CompiledMotionApplication
import reframe4s.motion.CompiledRigidSeriesEstimator
import reframe4s.motion.MotionError
import reframe4s.motion.PoseTrajectory
import reframe4s.motion.RigidSeriesEstimate
import reframe4s.motion.TimeAxis
import reframe4s.motion.TimedScalarSamples
import reframe4s.resample.Interpolation

import java.nio.file.Path

object CandidatePipeline:
  def run(
      input: Path,
      outputDirectory: Path,
      court: Court,
      referenceIndex: Int
  ): Either[RunnerError, Unit] =
    val decodeStart = System.nanoTime()
    Nifti
      .readScalar(input)
      .left
      .map(error => RunnerError.Nifti(error.message))
      .flatMap { decoded =>
        val decodeSeconds = elapsed(decodeStart)
        decoded.image.fold(
          _ =>
            Left(
              RunnerError.UnsupportedInput(
                "motion benchmarking requires a D3 image"
              )
            ),
          d3 =>
            runD3(
              d3.value,
              decoded.header,
              outputDirectory,
              court,
              referenceIndex,
              decodeSeconds
            )
        )
      }

  private def runD3[
      F <: Frame[D3],
      R <: AnyRank
  ](
      image: ScalarImage[F, D3, R],
      header: NiftiHeader,
      outputDirectory: Path,
      court: Court,
      referenceIndex: Int,
      decodeSeconds: Double
  ): Either[RunnerError, Unit] =
    val prepareStart = System.nanoTime()
    for
      prepared <- prepare(image, header)
      (samples, _) = prepared
      prepareSeconds = elapsed(prepareStart)
      estimateStart = System.nanoTime()
      estimator <- CompiledRigidSeriesEstimator
        .compile(samples, referenceIndex)
        .left
        .map(motionError)
      estimate <- estimator
        .run(estimator.newWorkspace())
        .left
        .map(motionError)
      estimateSeconds = elapsed(estimateStart)
      reportStart = System.nanoTime()
      matrices <- canonicalMatrices(estimate)
      _ <- EvidenceIo.writeCanonicalPoses(
        outputDirectory.resolve("poses.csv"),
        matrices
      )
      reportSeconds = elapsed(reportStart)
      application <-
        if court == Court.NativeEndToEnd then
          applyNative(samples, estimate, image, header, outputDirectory)
        else Right(ApplicationStages(None, None))
      _ <- writeStageTimes(
        outputDirectory.resolve("stage-times.tsv"),
        decodeSeconds,
        prepareSeconds,
        estimateSeconds,
        application,
        reportSeconds
      )
    yield ()

  private def prepare[
      F <: Frame[D3],
      R <: AnyRank
  ](
      image: ScalarImage[F, D3, R],
      header: NiftiHeader
  ): Either[
    RunnerError,
    (TimedScalarSamples[F, R], Double)
  ] =
    header.nonSpatialShape match
      case Vector(frameCount) =>
        val repetitionTime =
          header.pixelDimensions
            .lift(3)
            .filter(value => value.isFinite && value > 0.0)
            .getOrElse(1.0)
        for
          timeAxis <- Axis
            .create("time", frameCount, AxisKind.Time)
            .left
            .map(error => RunnerError.UnsupportedInput(error.message))
          axes <- NonSpatialAxes
            .from(Vector(timeAxis))
            .left
            .map(error => RunnerError.UnsupportedInput(error.message))
          series <- Sampled
            .scalar(image.grid, axes, image.data)
            .left
            .map(error => RunnerError.UnsupportedInput(error.message))
          times <- TimeAxis
            .create(
              Vector.tabulate(frameCount)(
                _.toDouble * repetitionTime
              )
            )
            .left
            .map(motionError)
          samples <- TimedScalarSamples
            .view(series, times)
            .left
            .map(motionError)
        yield samples -> repetitionTime
      case other =>
        Left(
          RunnerError.UnsupportedInput(
            s"expected exactly one non-spatial Time extent, got $other"
          )
        )

  private def canonicalMatrices[
      F <: Frame[D3]
  ](
      estimate: RigidSeriesEstimate[F]
  ): Either[RunnerError, Vector[Matrix4]] =
    estimate.poses.poses.foldLeft[
      Either[RunnerError, Vector[Matrix4]]
    ](Right(Vector.empty)) { (accumulated, pose) =>
      for
        matrices <- accumulated
        matrix <- Matrix4.create(
          pose.movingToFixed.operator.rowMajor
        )
      yield matrices :+ matrix
    }

  private def applyNative[
      F <: Frame[D3],
      R <: AnyRank
  ](
      samples: TimedScalarSamples[F, R],
      estimate: RigidSeriesEstimate[F],
      image: ScalarImage[F, D3, R],
      header: NiftiHeader,
      outputDirectory: Path
  ): Either[RunnerError, ApplicationStages] =
    val applyStart = System.nanoTime()
    for
      compiled <- CompiledMotionApplication
        .compile(
          samples,
          image.grid,
          PoseTrajectory.fromSeries(estimate.poses),
          interpolation = Interpolation.Lanczos5,
          boundary = BoundaryPolicy.Constant(0.0)
        )
        .left
        .map(motionError)
      corrected <- compiled
        .run(compiled.newWorkspace())
        .left
        .map(motionError)
      applySeconds = elapsed(applyStart)
      encodeStart = System.nanoTime()
      writeOptions <- NiftiWriteOptions.default
        .withNonSpatialSampling(
          header.pixelDimensions.drop(3),
          header.temporalUnit
        )
        .left
        .map(error => RunnerError.Nifti(error.message))
      files <- Nifti
        .writeScalar(
          outputDirectory.resolve("corrected.nii.gz"),
          corrected.image,
          writeOptions,
          extensions = header.extensions
        )
        .left
        .map(error => RunnerError.Nifti(error.message))
      _ <- files.paths.foldLeft[Either[RunnerError, Unit]](
        Right(())
      ) { (forced, path) =>
        forced.flatMap(_ => EvidenceIo.force(path))
      }
      encodeSeconds = elapsed(encodeStart)
    yield ApplicationStages(Some(applySeconds), Some(encodeSeconds))

  private def writeStageTimes(
      path: Path,
      decodeSeconds: Double,
      prepareSeconds: Double,
      estimateSeconds: Double,
      application: ApplicationStages,
      reportSeconds: Double
  ): Either[RunnerError, Unit] =
    val header =
      Vector(
        "decode_seconds",
        "prepare_seconds",
        "estimate_seconds",
        "apply_seconds",
        "encode_seconds",
        "report_seconds"
      ).mkString("\t")
    val values =
      Vector(
        java.lang.Double.toString(decodeSeconds),
        java.lang.Double.toString(prepareSeconds),
        java.lang.Double.toString(estimateSeconds),
        application.applySeconds
          .map(java.lang.Double.toString)
          .getOrElse("NA"),
        application.encodeSeconds
          .map(java.lang.Double.toString)
          .getOrElse("NA"),
        java.lang.Double.toString(reportSeconds)
      ).mkString("\t")
    EvidenceIo.writeUtf8(path, s"$header\n$values\n")

  private def elapsed(started: Long): Double =
    (System.nanoTime() - started).toDouble / 1e9

  private def motionError(error: MotionError): RunnerError =
    RunnerError.Motion(error.message)

  private final case class ApplicationStages(
      applySeconds: Option[Double],
      encodeSeconds: Option[Double]
  )
