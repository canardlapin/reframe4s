package reframe4s.benchmark.motion

import image4s.nifti.Nifti
import image4s.nifti.NiftiTemporalUnit

import java.nio.file.Files

final class CommonCourtResamplerSuite extends munit.FunSuite:
  test("identity poses preserve values, affine, and temporal sampling"):
    val root = Files.createTempDirectory("reframe4s-common-identity")
    val input = RunnerFixture.input(root)
    val poses = root.resolve("poses.csv")
    val output = root.resolve("corrected.nii.gz")
    right(
      EvidenceIo.writeCanonicalPoses(
        poses,
        Vector(Matrix4.identity, Matrix4.identity)
      )
    )
    right(CommonCourtResampler.run(input, poses, output))

    val original = decoded(input)
    val corrected = decoded(output)
    assertEquals(
      corrected.header.preferredAffine.rowMajor,
      original.header.preferredAffine.rowMajor
    )
    assertEquals(corrected.header.pixelDimensions(3), 1.75)
    assertEquals(
      corrected.header.temporalUnit,
      NiftiTemporalUnit.Second
    )
    val originalValues =
      original.image.fold(_ => Vector.empty, _.value.data.iterator.toVector)
    val correctedValues =
      corrected.image.fold(_ => Vector.empty, _.value.data.iterator.toVector)
    assertEquals(correctedValues, originalValues)

  test("moving-to-fixed translation is inverted exactly once for pull sampling"):
    val root = Files.createTempDirectory("reframe4s-common-translation")
    val input = RunnerFixture.input(root)
    val poses = root.resolve("poses.csv")
    val output = root.resolve("corrected.nii.gz")
    val movingToFixed =
      right(
        Matrix4.create(
          Vector(
            1.0, 0.0, 0.0, 1.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
          )
        )
      )
    right(
      EvidenceIo.writeCanonicalPoses(
        poses,
        Vector(Matrix4.identity, movingToFixed)
      )
    )
    right(CommonCourtResampler.run(input, poses, output))

    val original = decoded(input)
    val corrected = decoded(output)
    val source =
      original.image.fold(
        _ => fail("fixture unexpectedly decoded as D2"),
        d3 =>
          d3.value
            .valueAt(Vector(3, 3, 5), Vector(1))
            .fold(error => fail(error.message), identity)
      )
    val shifted =
      corrected.image.fold(
        _ => fail("corrected image unexpectedly decoded as D2"),
        d3 =>
          d3.value
            .valueAt(Vector(4, 3, 5), Vector(1))
            .fold(error => fail(error.message), identity)
      )
    val outside =
      corrected.image.fold(
        _ => fail("corrected image unexpectedly decoded as D2"),
        d3 =>
          d3.value
            .valueAt(Vector(0, 3, 5), Vector(1))
            .fold(error => fail(error.message), identity)
      )
    assertEqualsDouble(shifted, source, 1e-12)
    assertEqualsDouble(outside, 0.0, 1e-12)

  private def decoded(path: java.nio.file.Path) =
    Nifti
      .readScalar(path)
      .fold(error => fail(error.message), identity)

  private def right[A](value: Either[RunnerError, A]): A =
    value.fold(error => fail(error.message), identity)
