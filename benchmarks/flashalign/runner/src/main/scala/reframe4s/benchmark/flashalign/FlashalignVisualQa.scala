package reframe4s.benchmark.flashalign

import image4s.BoundaryPolicy
import image4s.ContinuousImage
import image4s.Sampled
import image4s.SampleSpace
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.nifti.Nifti
import ravel.Rank
import reframe4s.flashalign.Flashalign
import reframe4s.flashalign.FlashalignConfig
import reframe4s.flashalign.FlashalignInitializationPolicy
import reframe4s.flashalign.FlashalignOutput
import reframe4s.flashalign.FlashalignPreset
import reframe4s.resample.Interpolation

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

/** Reproducible human-facing QA plate for one preserved Flashalign court row.
  *
  * Registration and final resampling use the same image4s/Reframe4s path as the
  * court. Java2D only lays already sampled slices into a PNG; production code
  * acquires no dependency on ScalaFIM or a renderer.
  */
object FlashalignVisualQa:
  private val CaseId = "r19-exact-core-rigid"
  private val CandidateIdentity =
    "df4a4ddf84a63e5b7ae714a29b226d15639ce32bf8d583e3f08b931196442089"
  private val FlashalignSourceTree =
    "51f65b218eb7051f89d232491f55f5b4523e3c230a9df43994d539c8415ce456"
  private val RawEvidence = Paths.get(
    "benchmarks/flashalign/raw/linear-automatic-release-confirmation-v5-2026-09-13.jsonl"
  )
  private val RawEvidenceSha256 = Sha256.unsafe(
    "38dc4857d30844711e4d676e75558a98975dce7f3788a0c9144b4090aca22d2e"
  )
  private val Moving = Paths.get(
    "benchmarks/flashalign/fixtures/linear-automatic-release-confirmation-v5/r19-exact-core-rigid-moving.nii.gz"
  )
  private val MovingSha256 = Sha256.unsafe(
    "1b3962267eb5e98e8b88470e39edb70f417833020e026332081d9b2e248ec2ff"
  )
  private val Fixed = Paths.get(
    "benchmarks/flashalign/fixtures/linear-automatic-release-confirmation-v5/r19-fixed.nii.gz"
  )
  private val FixedSha256 = Sha256.unsafe(
    "accc5a97373b9c225ab5db734e55756526dc40d82094439b35fc958f83854412"
  )
  private val ExpectedTransformSha256 = Sha256.unsafe(
    "b50c7521235b1ed68dfe3885c11301069b5252e6a5e836708cd2c8e9e0bc8a54"
  )
  private val LandmarkRmsMm = 0.03241684835962561

  def main(arguments: Array[String]): Unit =
    val (png, manifest) = arguments.toVector match
      case Vector(pngPath, manifestPath) =>
        Paths.get(pngPath) -> Paths.get(manifestPath)
      case _ =>
        throw new IllegalArgumentException(
          "usage: FlashalignVisualQa <output-png> <output-manifest-json>"
        )
    verifyFile(RawEvidence, RawEvidenceSha256)
    verifyFile(Moving, MovingSha256)
    verifyFile(Fixed, FixedSha256)
    val moving = readD3("moving", Moving)
    val fixed = readD3("fixed", Fixed)
    moving.fold(
      _ => throw new IllegalStateException("expected D3 moving image"),
      movingD3 =>
        fixed.fold(
          _ => throw new IllegalStateException("expected D3 fixed image"),
          fixedD3 =>
            val typedMoving = movingD3.value
              .requireDataRank[3]
              .fold(error => throw new IllegalStateException(error.message), identity)
            val typedFixed = fixedD3.value
              .requireDataRank[3]
              .fold(error => throw new IllegalStateException(error.message), identity)
            renderTyped(typedMoving, typedFixed, png, manifest)
        )
    )

  private def renderTyped[
      MovingFrame <: Frame[D3],
      FixedFrame <: Frame[D3],
      MovingSpace <: SampleSpace[MovingFrame, D3],
      FixedSpace <: SampleSpace[FixedFrame, D3]
  ](
      moving: ContinuousImage[MovingSpace, Double, Rank[3]],
      fixed: ContinuousImage[FixedSpace, Double, Rank[3]],
      png: Path,
      manifest: Path
  ): Unit =
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )
    val plan = Flashalign
      .rigid(moving, fixed, config)
      .fold(error => throw new IllegalStateException(error.message), identity)
    val result = plan
      .run(plan.newWorkspace())
      .fold(error => throw new IllegalStateException(error.message), identity)
    val matrix = result.movingToFixed.operator.rowMajor
    val transformHash = EvidenceHash.matrix(matrix)
    if transformHash != ExpectedTransformSha256 then
      throw new IllegalStateException(
        s"$CaseId transform changed: expected ${ExpectedTransformSha256.hex}, got ${transformHash.hex}"
      )
    val outputPlan = FlashalignOutput
      .rigidPlan(
        moving,
        fixed.grid,
        result,
        Interpolation.Linear,
        BoundaryPolicy.Constant(Double.NaN)
      )
      .fold(error => throw new IllegalStateException(error.message), identity)
    val output = outputPlan
      .run(outputPlan.newWorkspace())
      .fold(error => throw new IllegalStateException(error.message), identity)

    val fixedVolume = copyVolume(fixed)
    val movingVolume = copyVolume(moving)
    val registeredVolume = copyVolume(output.image)
    if fixedVolume.shape != movingVolume.shape ||
      fixedVolume.shape != registeredVolume.shape
    then throw new IllegalStateException("visual QA volumes must share one grid shape")

    val fixedNormalized = normalize(fixedVolume)
    val movingNormalized = normalize(movingVolume)
    val registeredNormalized = normalize(registeredVolume)
    val plate = renderPlate(
      fixedNormalized,
      movingNormalized,
      registeredNormalized
    )
    Option(png.getParent).foreach(Files.createDirectories(_))
    if !ImageIO.write(plate.image, "png", png.toFile) then
      throw new IllegalStateException("no PNG writer is available")
    val pngHash = EvidenceHash.file(png)
    val pngBytes = Files.size(png)
    val manifestJson = renderManifest(
      png,
      pngHash,
      pngBytes,
      plate,
      matrix,
      result.report.finalObjective,
      result.diagnostics.overlapFraction
    )
    Option(manifest.getParent).foreach(Files.createDirectories(_))
    Files.writeString(manifest, manifestJson, StandardCharsets.UTF_8)
    println(
      s"Flashalign visual QA: $png (${pngHash.hex}, $pngBytes bytes); manifest $manifest"
    )

  private def readD3(
      label: String,
      path: Path
  ): image4s.SomeSampled[Double, image4s.Continuous] =
    Nifti
      .readScaledDouble(path)
      .fold(
        error => throw new IllegalStateException(s"$label: ${error.message}"),
        _.image
      )

  private def verifyFile(path: Path, expected: Sha256): Unit =
    if !Files.isRegularFile(path) then
      throw new IllegalStateException(s"missing input $path")
    val actual = EvidenceHash.file(path)
    if actual != expected then
      throw new IllegalStateException(
        s"input hash mismatch for $path: expected ${expected.hex}, got ${actual.hex}"
      )

  private def copyVolume[
      F <: Frame[D3],
      S <: SampleSpace[F, D3],
      Sem,
      R <: ravel.AnyRank
  ](
      image: Sampled[S, Double, Sem, R]
  ): Volume3 =
    val shape = image.grid.shape
    val values = new Array[Double](shape(0) * shape(1) * shape(2))
    var x = 0
    while x < shape(0) do
      var y = 0
      while y < shape(1) do
        var z = 0
        while z < shape(2) do
          values((x * shape(1) + y) * shape(2) + z) = image
            .valueAt(Vector(x, y, z))
            .fold(error => throw new IllegalStateException(error.message), identity)
          z += 1
        y += 1
      x += 1
    Volume3(shape, values)

  private def normalize(volume: Volume3): Volume3 =
    val finite = volume.values.filter(_.isFinite).sorted
    if finite.isEmpty then throw new IllegalStateException("volume has no finite values")
    val lower = finite(math.floor(0.01 * (finite.length - 1)).toInt)
    val upper = finite(math.ceil(0.99 * (finite.length - 1)).toInt)
    val scale = math.max(1e-12, upper - lower)
    Volume3(
      volume.shape,
      volume.values.map { value =>
        if !value.isFinite then Double.NaN
        else math.max(0.0, math.min(1.0, (value - lower) / scale))
      }
    )

  private def renderPlate(
      fixed: Volume3,
      moving: Volume3,
      registered: Volume3
  ): RenderedPlate =
    val panelSize = 176
    val gap = 12
    val left = 24
    val top = 86
    val rowLabelWidth = 76
    val columns = Vector(
      "Fixed",
      "Moving before",
      "Registered",
      "Overlay before",
      "Overlay after",
      "Abs diff after"
    )
    val planes = Vector(Plane.Axial, Plane.Coronal, Plane.Sagittal)
    val width = left + rowLabelWidth + columns.size * panelSize +
      (columns.size - 1) * gap + 24
    val height = top + planes.size * panelSize + (planes.size - 1) * gap + 34
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try
      graphics.setRenderingHint(
        RenderingHints.KEY_ANTIALIASING,
        RenderingHints.VALUE_ANTIALIAS_ON
      )
      graphics.setColor(new Color(15, 18, 24))
      graphics.fillRect(0, 0, width, height)
      graphics.setColor(new Color(245, 247, 250))
      graphics.setFont(new Font("SansSerif", Font.BOLD, 24))
      graphics.drawString("Flashalign rigid registration — visual QA", left, 34)
      graphics.setFont(new Font("SansSerif", Font.PLAIN, 14))
      graphics.setColor(new Color(176, 185, 199))
      graphics.drawString(
        f"$CaseId · automatic public path · landmark RMS $LandmarkRmsMm%.4f mm",
        left,
        60
      )
      graphics.setFont(new Font("SansSerif", Font.BOLD, 12))
      columns.zipWithIndex.foreach { case (label, column) =>
        val x = left + rowLabelWidth + column * (panelSize + gap)
        graphics.setColor(new Color(220, 226, 235))
        graphics.drawString(label, x + 4, top - 12)
      }
      val beforeErrors = Vector.newBuilder[Double]
      val afterErrors = Vector.newBuilder[Double]
      planes.zipWithIndex.foreach { case (plane, row) =>
        val fixedSlice = slice(fixed, plane)
        val movingSlice = slice(moving, plane)
        val registeredSlice = slice(registered, plane)
        val beforeOverlay = overlay(fixedSlice, movingSlice)
        val afterOverlay = overlay(fixedSlice, registeredSlice)
        val afterDifference = difference(fixedSlice, registeredSlice)
        beforeErrors += meanAbsoluteDifference(fixedSlice, movingSlice)
        afterErrors += meanAbsoluteDifference(fixedSlice, registeredSlice)
        val panels = Vector(
          grayscale(fixedSlice),
          grayscale(movingSlice),
          grayscale(registeredSlice),
          beforeOverlay,
          afterOverlay,
          afterDifference
        )
        val y = top + row * (panelSize + gap)
        graphics.setFont(new Font("SansSerif", Font.BOLD, 13))
        graphics.setColor(new Color(220, 226, 235))
        graphics.drawString(plane.label, left, y + panelSize / 2)
        panels.zipWithIndex.foreach { case (panel, column) =>
          val x = left + rowLabelWidth + column * (panelSize + gap)
          graphics.drawImage(panel, x, y, panelSize, panelSize, null)
          graphics.setColor(new Color(69, 78, 92))
          graphics.setStroke(new BasicStroke(1.0f))
          graphics.drawRect(x, y, panelSize, panelSize)
        }
      }
      val before = beforeErrors.result()
      val after = afterErrors.result()
      graphics.setFont(new Font("SansSerif", Font.PLAIN, 12))
      graphics.setColor(new Color(150, 160, 175))
      graphics.drawString(
        "Red = fixed, cyan = moving; neutral gray indicates structural agreement. Display windows are robust per-volume.",
        left + rowLabelWidth,
        height - 13
      )
      RenderedPlate(image, before, after)
    finally graphics.dispose()

  private def slice(volume: Volume3, plane: Plane): Slice2 =
    val nx = volume.shape(0)
    val ny = volume.shape(1)
    val nz = volume.shape(2)
    plane match
      case Plane.Axial =>
        val z = nz / 2
        Slice2(nx, ny, Array.tabulate(nx * ny) { flat =>
          val x = flat % nx
          val displayY = flat / nx
          volume(x, ny - 1 - displayY, z)
        })
      case Plane.Coronal =>
        val y = ny / 2
        Slice2(nx, nz, Array.tabulate(nx * nz) { flat =>
          val x = flat % nx
          val displayZ = flat / nx
          volume(x, y, nz - 1 - displayZ)
        })
      case Plane.Sagittal =>
        val x = nx / 2
        Slice2(ny, nz, Array.tabulate(ny * nz) { flat =>
          val y = flat % ny
          val displayZ = flat / ny
          volume(x, y, nz - 1 - displayZ)
        })

  private def grayscale(slice: Slice2): BufferedImage =
    raster(slice) { value =>
      if !value.isFinite then 0xff0f1218.toInt
      else
        val channel = math.round(255.0 * value).toInt
        argb(channel, channel, channel)
    }

  private def overlay(fixed: Slice2, moving: Slice2): BufferedImage =
    raster2(fixed, moving) { (reference, candidate) =>
      if !reference.isFinite || !candidate.isFinite then 0xff0f1218.toInt
      else
        val red = math.round(255.0 * reference).toInt
        val cyan = math.round(255.0 * candidate).toInt
        argb(red, cyan, cyan)
    }

  private def difference(fixed: Slice2, moving: Slice2): BufferedImage =
    raster2(fixed, moving) { (reference, candidate) =>
      if !reference.isFinite || !candidate.isFinite then 0xff0f1218.toInt
      else
        val difference = math.min(1.0, math.abs(reference - candidate) * 2.5)
        val red = math.round(255.0 * difference).toInt
        val green = math.round(210.0 * difference * difference).toInt
        argb(red, green, 0)
    }

  private def meanAbsoluteDifference(left: Slice2, right: Slice2): Double =
    var total = 0.0
    var count = 0
    var index = 0
    while index < left.values.length do
      val a = left.values(index)
      val b = right.values(index)
      if a.isFinite && b.isFinite then
        total += math.abs(a - b)
        count += 1
      index += 1
    if count == 0 then Double.NaN else total / count.toDouble

  private def raster(slice: Slice2)(color: Double => Int): BufferedImage =
    val image = new BufferedImage(slice.width, slice.height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(
      0,
      0,
      slice.width,
      slice.height,
      slice.values.map(color),
      0,
      slice.width
    )
    image

  private def raster2(left: Slice2, right: Slice2)(
      color: (Double, Double) => Int
  ): BufferedImage =
    val pixels = Array.tabulate(left.values.length)(index =>
      color(left.values(index), right.values(index))
    )
    val image = new BufferedImage(left.width, left.height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, left.width, left.height, pixels, 0, left.width)
    image

  private def argb(red: Int, green: Int, blue: Int): Int =
    0xff000000.toInt | (red << 16) | (green << 8) | blue

  private def renderManifest(
      png: Path,
      pngHash: Sha256,
      pngBytes: Long,
      plate: RenderedPlate,
      matrix: Vector[Double],
      objective: Double,
      overlap: Double
  ): String =
    val matrixJson = matrix.map(java.lang.Double.toString).mkString(",")
    val beforeJson = plate.beforeMeanAbsoluteDifference
      .map(java.lang.Double.toString)
      .mkString(",")
    val afterJson = plate.afterMeanAbsoluteDifference
      .map(java.lang.Double.toString)
      .mkString(",")
    s"""{
       |  "schema_version": "flashalign.linear-visual-qa.v2",
       |  "case_id": "$CaseId",
       |  "candidate_identity_sha256": "$CandidateIdentity",
       |  "flashalign_source_tree_sha256": "$FlashalignSourceTree",
       |  "raw_evidence": {"path": "${RawEvidence.toString}", "sha256": "${RawEvidenceSha256.hex}"},
       |  "moving": {"path": "${Moving.toString}", "sha256": "${MovingSha256.hex}"},
       |  "fixed": {"path": "${Fixed.toString}", "sha256": "${FixedSha256.hex}"},
       |  "result": {"moving_to_fixed": [$matrixJson], "sha256": "${ExpectedTransformSha256.hex}", "landmark_rms_mm": $LandmarkRmsMm, "projected_patch_objective": $objective, "audit_overlap_fraction": $overlap},
       |  "visual": {"path": "${png.toString}", "sha256": "${pngHash.hex}", "bytes": $pngBytes, "width": ${plate.image.getWidth}, "height": ${plate.image.getHeight}, "planes": ["axial-k", "coronal-j", "sagittal-i"], "columns": ["fixed", "moving-before", "registered", "red-cyan-overlay-before", "red-cyan-overlay-after", "absolute-difference-after"], "normalized_slice_mean_absolute_difference_before": [$beforeJson], "normalized_slice_mean_absolute_difference_after": [$afterJson]},
       |  "rendering_boundary": "image4s/Reframe4s load, automatic registration and final resampling; benchmark-local Java2D PNG composition; no ScalaFIM dependency",
       |  "claim_boundary": "Human-facing analytic-synthetic visual QA for one preserved rigid row; not acquired-MRI or clinical evidence."
       |}
       |""".stripMargin

  private final case class Volume3(shape: Vector[Int], values: Array[Double]):
    def apply(x: Int, y: Int, z: Int): Double =
      values((x * shape(1) + y) * shape(2) + z)

  private final case class Slice2(width: Int, height: Int, values: Array[Double])

  private final case class RenderedPlate(
      image: BufferedImage,
      beforeMeanAbsoluteDifference: Vector[Double],
      afterMeanAbsoluteDifference: Vector[Double]
  )

  private enum Plane(val label: String):
    case Axial extends Plane("Axial (K)")
    case Coronal extends Plane("Coronal (J)")
    case Sagittal extends Plane("Sagittal (I)")
