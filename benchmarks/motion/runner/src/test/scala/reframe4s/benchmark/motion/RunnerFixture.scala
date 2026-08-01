package reframe4s.benchmark.motion

import image4s.Axis
import image4s.AxisKind
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.nifti.Nifti
import image4s.nifti.NiftiTemporalUnit
import image4s.nifti.NiftiWriteOptions
import ravel.DType.given
import ravel.NDArray
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import scala.jdk.CollectionConverters.*

private[motion] object RunnerFixture:
  def input(root: Path): Path =
    val path = root.resolve("input.nii.gz")
    val frame =
      Frame.named[D3]("runner-fixture").fold(
        error => throw new IllegalStateException(error.message),
        identity
      )
    val grid =
      Grid
        .in(frame)(
          Vector(9, 9, 9),
          Affine.identity[D3]
        )
        .fold(
          error => throw new IllegalStateException(error.message),
          identity
        )
    val time =
      Axis
        .create("time", 2, AxisKind.Time)
        .fold(
          error => throw new IllegalStateException(error.message),
          identity
        )
    val axes =
      NonSpatialAxes
        .from(Vector(time))
        .fold(
          error => throw new IllegalStateException(error.message),
          identity
        )
    val image =
      Sampled
        .scalar(
          grid,
          axes,
          NDArray.tabulate[Double](9, 9, 9, 2)((i, j, k, t) =>
            val dx = i.toDouble - 4.0
            val dy = j.toDouble - 3.0
            val dz = k.toDouble - 5.0
            math.exp(-(dx * dx + dy * dy + dz * dz) / 8.0) +
              0.25 * math.exp(
                -(
                  (i.toDouble - 6.0) * (i.toDouble - 6.0) +
                    (j.toDouble - 6.0) * (j.toDouble - 6.0) +
                    (k.toDouble - 2.0) * (k.toDouble - 2.0)
                ) / 3.0
              ) +
              t.toDouble * 0.0
          )
        )
        .fold(
          error => throw new IllegalStateException(error.message),
          identity
        )
    val options =
      NiftiWriteOptions.default
        .withNonSpatialSampling(
          Vector(1.75),
          NiftiTemporalUnit.Second
        )
        .fold(
          error => throw new IllegalStateException(error.message),
          identity
        )
    val _ =
      Nifti
        .writeScalar(path, image, options)
        .fold(
          error => throw new IllegalStateException(error.message),
          identity
        )
    path

  def executable(
      root: Path,
      name: String,
      content: String
  ): Path =
    val path = root.resolve(name)
    val _ = Files.writeString(path, content)
    val permissions =
      Set(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
      )
    val _ = Files.setPosixFilePermissions(path, permissions.asJava)
    path
