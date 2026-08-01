package reframe4s.benchmark.motion

import image4s.BoundaryPolicy
import image4s.Sampled
import image4s.ScalarImage
import image4s.nifti.Nifti
import image4s.nifti.NiftiHeader
import image4s.nifti.NiftiWriteOptions
import image4s.reference.ReferenceSampler
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Point

import java.nio.file.Path

/**
 * Allocation-tolerant benchmark-only application oracle.
 *
 * This deliberately uses image4s-reference rather than the production
 * resampling plan. Its elapsed time is outside the common-court timing
 * endpoint.
 */
object CommonCourtResampler:
  def run(
      input: Path,
      canonicalPoses: Path,
      output: Path
  ): Either[RunnerError, Unit] =
    for
      decoded <- Nifti
        .readScalar(input)
        .left
        .map(error => RunnerError.Nifti(error.message))
      poses <- EvidenceIo.readCanonicalPoses(canonicalPoses)
      _ <- decoded.image.fold(
        _ =>
          Left(
            RunnerError.UnsupportedInput(
              "common motion resampling requires a D3 image"
            )
          ),
        d3 => runD3(d3.value, decoded.header, poses, output)
      )
    yield ()

  private def runD3[
      F <: Frame[D3],
      R <: AnyRank
  ](
      image: ScalarImage[F, D3, R],
      header: NiftiHeader,
      poses: Vector[Matrix4],
      output: Path
  ): Either[RunnerError, Unit] =
    for
      ranked <- image
        .requireDataRank[4]
        .left
        .map(error => RunnerError.UnsupportedInput(error.message))
      frameCount <- header.nonSpatialShape match
        case Vector(count) if count > 0 => Right(count)
        case other =>
          Left(
            RunnerError.UnsupportedInput(
              s"common motion resampling requires one non-spatial extent, got $other"
            )
          )
      _ <-
        if poses.size == frameCount then Right(())
        else
          Left(
            RunnerError.InvalidPoseFile(
              output,
              s"expected $frameCount poses, got ${poses.size}"
            )
          )
      pulls <- sequence(poses.map(_.inverse))
      corrected <- resample(ranked, pulls)
      options <- NiftiWriteOptions.default
        .withNonSpatialSampling(
          header.pixelDimensions.drop(3),
          header.temporalUnit
        )
        .left
        .map(error => RunnerError.Nifti(error.message))
      files <- Nifti
        .writeScalar(
          output,
          corrected,
          options,
          extensions = header.extensions
        )
        .left
        .map(error => RunnerError.Nifti(error.message))
      _ <- files.paths.foldLeft[Either[RunnerError, Unit]](
        Right(())
      ) { (forced, path) =>
        forced.flatMap(_ => EvidenceIo.force(path))
      }
    yield ()

  private def resample[
      F <: Frame[D3]
  ](
      image: ScalarImage[F, D3, Rank[4]],
      fixedToMoving: Vector[Matrix4]
  ): Either[
    RunnerError,
    ScalarImage[F, D3, Rank[4]]
  ] =
    var failure = Option.empty[RunnerError]
    val shape = image.data.shape
    val corrected =
      NDArray.build[Double, Rank[4]](shape) { builder =>
        var i = 0
        var linear = 0
        while i < shape(0) do
          var j = 0
          while j < shape(1) do
            var k = 0
            while k < shape(2) do
              var frame = 0
              while frame < shape(3) do
                if failure.isEmpty then
                  val sampled =
                    for
                      fixed <- image.grid.indexToFrame
                        .apply(
                          Vector(
                            i.toDouble,
                            j.toDouble,
                            k.toDouble
                          )
                        )
                        .left
                        .map(error =>
                          RunnerError.UnsupportedInput(error.message)
                        )
                      moving <- fixedToMoving(frame).transform(fixed)
                      point <- Point
                        .fromVector[D3, F](image.frame, moving)
                        .left
                        .map(error =>
                          RunnerError.UnsupportedInput(error.message)
                        )
                      value <- ReferenceSampler
                        .linear(
                          image,
                          point,
                          Vector(frame),
                          BoundaryPolicy.Constant(0.0)
                        )
                        .left
                        .map(error =>
                          RunnerError.UnsupportedInput(error.message)
                        )
                    yield value.value
                  sampled match
                    case Right(value) =>
                      builder.writeLinear(linear, value)
                    case Left(error) =>
                      failure = Some(error)
                linear += 1
                frame += 1
              k += 1
            j += 1
          i += 1
      }
    failure.toLeft(corrected).flatMap { values =>
      Sampled
        .scalar(image.grid, image.nonSpatialAxes, values)
        .left
        .map(error => RunnerError.UnsupportedInput(error.message))
    }

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
