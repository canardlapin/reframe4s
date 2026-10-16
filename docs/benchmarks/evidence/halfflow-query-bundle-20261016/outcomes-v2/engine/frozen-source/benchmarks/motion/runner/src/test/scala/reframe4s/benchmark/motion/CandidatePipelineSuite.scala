package reframe4s.benchmark.motion

import image4s.nifti.Nifti
import image4s.nifti.NiftiTemporalUnit

import java.nio.file.Files

final class CandidatePipelineSuite extends munit.FunSuite:
  test("candidate native path reads NIfTI, fits, applies Lanczos-5, and writes evidence"):
    val root = Files.createTempDirectory("reframe4s-candidate-smoke")
    val input = RunnerFixture.input(root)
    val output = root.resolve("candidate")
    Files.createDirectories(output)

    right(
      CandidatePipeline.run(
        input,
        output,
        Court.NativeEndToEnd,
        referenceIndex = 0
      )
    )

    val poses =
      right(EvidenceIo.readCanonicalPoses(output.resolve("poses.csv")))
    assertEquals(poses.size, 2)
    val corrected =
      right(
        Nifti
          .readScalar(output.resolve("corrected.nii.gz"))
          .left
          .map(error => RunnerError.Nifti(error.message))
      )
    assertEquals(corrected.header.logicalShape, Vector(9, 9, 9, 2))
    assertEquals(corrected.header.pixelDimensions(3), 1.75)
    assertEquals(
      corrected.header.temporalUnit,
      NiftiTemporalUnit.Second
    )
    assert(
      corrected.image.fold(
        _ => false,
        d3 => d3.value.data.iterator.forall(_.isFinite)
      )
    )
    val stages =
      right(
        EvidenceIo.readStageTimes(
          output.resolve("stage-times.tsv"),
          1.0
        )
      )
    assertEquals(stages.source, StageTimingSource.Instrumented)
    assert(stages.decodeSeconds.nonEmpty)
    assert(stages.estimateSeconds.nonEmpty)
    assert(stages.applySeconds.nonEmpty)
    assert(stages.encodeSeconds.nonEmpty)

  private def right[A](value: Either[RunnerError, A]): A =
    value.fold(error => fail(error.message), identity)
