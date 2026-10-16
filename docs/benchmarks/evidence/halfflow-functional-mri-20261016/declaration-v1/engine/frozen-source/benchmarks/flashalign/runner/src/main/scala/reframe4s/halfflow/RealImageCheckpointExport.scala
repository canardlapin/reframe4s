package reframe4s.halfflow

import image4s.{ContinuousImage, SampleSpace}
import image4s.geometry.{Affine, D3}
import image4s.geometry.Frame as CanonicalFrame
import image4s.nifti.Nifti
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import ravel.Rank
import reframe4s.benchmark.flashalign.EvidenceHash
import reframe4s.halfflow.internal.*

/** Offline export of a committed raw checkpoint. Source captures are immutable;
  * export construction, admission and failures are written to a separate tree.
  */
object RealImageCheckpointExport:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving
  private def right[E, A](value: Either[E, A]): A =
    value.fold(error => throw new IllegalArgumentException(error.toString), identity)

  def main(args: Array[String]): Unit =
    require(args.length == 5, "moving fixed fixed-mask committed-checkpoint output")
    val moving = right(Nifti.readScaledDouble(Paths.get(args(0))))
    val fixed = right(Nifti.readScaledDouble(Paths.get(args(1))))
    moving.image.fold(_ => throw new IllegalArgumentException("scalar D3 moving required"),
      m => fixed.image.fold(_ => throw new IllegalArgumentException("scalar D3 fixed required"),
        f => run(right(m.value.requireDataRank[3]), right(f.value.requireDataRank[3]),
          Paths.get(args(2)), Paths.get(args(3)), Paths.get(args(4)), args.take(3).map(Paths.get(_)).toVector)))

  private[halfflow] def workGrid(checkpoint: Path, native: GridSpec): GridSpec =
    val metadata = Files.readString(checkpoint.resolve("midpoint-fixed-residual.json"))
    def array(key: String): Vector[Double] =
      val pattern = ("\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]").r
      pattern.findFirstMatchIn(metadata).getOrElse(
        throw new IllegalArgumentException("missing checkpoint grid " + key)).group(1)
        .split(",").map(_.trim.toDouble).toVector
    val shape = array("shape")
    require(shape.length == 3 && shape.forall(n => n.isFinite && n > 0 && n == n.toInt.toDouble),
      "invalid checkpoint grid shape")
    native.withGeometry(shape.map(_.toInt), right(Affine.fromRowMajor[D3](array("index_to_ras_mm"))))

  private[halfflow] def loadState[W, F, M](checkpoint: Path, work: RegistrationFrame[W],
      fixed: RegistrationFrame[F], moving: RegistrationFrame[M]): ForwardMidpoint[W, F, M] =
    val fixedArm = RealImageObjectiveAudit.loadArm(checkpoint, "fixed", work, fixed)
    val movingArm = RealImageObjectiveAudit.loadArm(checkpoint, "moving", work, moving)
    // The runner's splitAt creates (root.inverse, root). Affine stores both
    // matrices, so independently inverting the serialized fixed matrix would
    // lose the exact cached root. Rebuild the same pair, checking every saved
    // forward coefficient before using that construction.
    val fixedOperator = movingArm.affine.transform.inverse
    require(fixedOperator.rowMajor == fixedArm.affine.transform.rowMajor,
      "checkpoint affine arms do not match the runner's declared midpoint split")
    val exactFixed = right(ForwardMidpointArm.make(fixedArm.residual,
      right(AffineIso.make(work, fixed, fixedOperator))))
    right(ForwardMidpoint.make(exactFixed, movingArm))

  private def run[M <: CanonicalFrame[D3], F <: CanonicalFrame[D3],
      MS <: SampleSpace[M, D3], FS <: SampleSpace[F, D3]](
      moving: ContinuousImage[MS, Double, Rank[3]], fixed: ContinuousImage[FS, Double, Rank[3]],
      maskPath: Path, checkpoint: Path, output: Path, imagePaths: Vector[Path]
  ): Unit =
    require(!Files.exists(output), "refusing to overwrite checkpoint export")
    require(Files.isRegularFile(checkpoint.resolve("checkpoint.json")), "uncommitted checkpoint")
    val fg = GridSpec.fromGrid(fixed.grid)
    val mg = GridSpec.fromGrid(moving.grid)
    val ff = RegistrationFrame[Fixed](SpatialDomainId("checkpoint-fixed"), fg)
    val mf = RegistrationFrame[Moving](SpatialDomainId("checkpoint-moving"), mg)
    val nativeWork = RegistrationFrame[Work](SpatialDomainId("checkpoint-work"), fg)
    val capturedWork = RegistrationFrame[Work](nativeWork.domain, workGrid(checkpoint, fg))
    val fv = NeuroVol.fromRavel(fixed.data, fg, "fixed")
    val mv = NeuroVol.fromRavel(moving.data, mg, "moving")
    val mask = FieldValidity.copyMask(RealImageRegistration.loadMask(maskPath, fg))
    val movingMask = FieldValidity.copyMask(mv.copyLegacyLinear.map(_ > 0.0))
    val state = loadState(checkpoint, capturedWork, ff, mf)
    val config = RealImageRegistration.finePlan(50, 1e-3, 6.0).exportConfig
    val files = Vector("fixed", "moving").flatMap: arm =>
      Vector(".json", ".f64", ".valid-u8").map(s => checkpoint.resolve(s"midpoint-$arm-residual$s")) :+
        checkpoint.resolve(s"midpoint-$arm-affine.json")
    Files.createDirectories(output)
    val inputs = (files ++ imagePaths :+ checkpoint.resolve("checkpoint.json"))
      .map(p => p.toAbsolutePath.toString -> EvidenceHash.file(p).hex).toMap
    write(output.resolve("inputs.json"), inputs)
    Files.writeString(output.resolve("export-config.txt"), config.toString + "\n", StandardOpenOption.CREATE_NEW)
    var lane: Option[String] = None
    val status = state.regrid(nativeWork, ff, mf) match
      case Left(error) => s"regrid failed: ${error.message}"
      case Right(native) =>
        ForwardMidpointExporter.inspect(native, config, Some(mask), Some(movingMask)) match
          case Left(error) => s"export construction failed: ${error.message}"
          case Right(candidate) =>
            val admitted = ForwardMidpointExporter.admit(candidate, config)
            lane = Some(if admitted.isRight then "halfflow" else "halfflow-rejected")
            RealImageRegistration.savePair(lane.get, candidate.transform, mv, fv, output)
            Files.writeString(output.resolve("export-diagnostics.txt"),
              s"${candidate.fixedResidualInverse}\n${candidate.movingResidualInverse}\n${candidate.endpointRoundTrip}\n",
              StandardOpenOption.CREATE_NEW)
            admitted.fold(error => s"rejected: ${error.message}", _ => "native export gate accepted")
    Files.writeString(output.resolve("export-status.txt"), status + "\n", StandardOpenOption.CREATE_NEW)
    write(output.resolve("result.json"), Map("schema" -> "halfflow-offline-checkpoint-export-v1",
      "checkpoint_sha256" -> EvidenceHash.file(checkpoint.resolve("checkpoint.json")).hex,
      "status" -> status, "lane" -> lane, "native_export_admitted" -> (status == "native export gate accepted"),
      "diagnostic_only" -> true, "qualification_claim" -> false))

  private def write(path: Path, value: Any): Unit =
    Files.writeString(path, RealImageCheckpointJson.encode(value) + "\n", StandardOpenOption.CREATE_NEW)
    ()
