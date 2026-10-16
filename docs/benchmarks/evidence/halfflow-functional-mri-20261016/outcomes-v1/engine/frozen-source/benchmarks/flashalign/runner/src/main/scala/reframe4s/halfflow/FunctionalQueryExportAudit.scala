package reframe4s.halfflow

import gale.linalg.DMat
import image4s.geometry.{Affine, D3}
import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import reframe4s.benchmark.flashalign.EvidenceHash
import reframe4s.halfflow.internal.*

/** Frozen C4 replay from captured residuals/seeds; never fits a registration. */
object FunctionalQueryExportAudit:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def array(metadata: String, key: String): Vector[Double] =
    ("\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]").r.findFirstMatchIn(metadata)
      .getOrElse(throw new IllegalArgumentException("missing " + key)).group(1).split(",").map(_.trim.toDouble).toVector
  private def geometry(metadata: String, owner: Option[GridSpec]): GridSpec =
    val shape = array(metadata, "shape")
    require(shape.length == 3 && shape.forall(n => n.isFinite && n >= 2 && n == n.toInt.toDouble))
    val affine = right(Affine.fromRowMajor[D3](array(metadata, "affine")))
    owner.fold(GridSpec(shape.map(_.toInt), DMat.dense(4, 4, affine.rowMajor)))(_.withGeometry(shape.map(_.toInt), affine))
  private def load(stem: Path, work: RegistrationFrame[Unit]): DensePull[Unit, Unit] =
    val metadata = Files.readString(Paths.get(stem.toString + ".json"))
    require(array(metadata, "shape").map(_.toInt) == work.grid.shape)
    require(array(metadata, "affine") == work.grid.indexToFrame.rowMajor)
    val n = work.grid.nVoxels
    val coordinates = new Array[Double](3*n)
    val input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(stem.toString + ".f64"))))
    try
      for i <- coordinates.indices do coordinates(i) = input.readDouble()
      require(input.read() == -1)
    finally input.close()
    val mask = Files.readAllBytes(Paths.get(stem.toString + ".valid-u8"))
    require(mask.length == n && mask.forall(b => b == 0 || b == 1))
    right(DensePull.make(work, work, DenseVectorField.fromLegacyPlanar(work.grid, coordinates, DenseVectorFieldKind.SourceCoordinates),
      FieldValidity.copyMask(mask.map(_ != 0))))

  def main(args: Array[String]): Unit =
    require(args.length == 4, "captured-fields fixed-grid.json moving-grid.json fresh-output")
    val fields = Paths.get(args(0))
    val output = Paths.get(args(3))
    require(!Files.exists(output), "preserve prior outcomes")
    Files.createDirectories(output)
    val fixedGrid = geometry(Files.readString(Paths.get(args(1))), None)
    val movingGrid = geometry(Files.readString(Paths.get(args(2))), None)
    val workGrid = geometry(Files.readString(fields.resolve("fixed-forward.json")), Some(fixedGrid))
    val work = RegistrationFrame[Unit](SpatialDomainId("c4-work"), workGrid)
    val fixed = RegistrationFrame[Unit](SpatialDomainId("c4-fixed"), fixedGrid)
    val moving = RegistrationFrame[Unit](SpatialDomainId("c4-moving"), movingGrid)
    // This is the original C4 lane's actual factorization: runIdentity, with
    // affine factors preserved by advance/regularize/regrid. Declaration binds
    // the original runner and endpoint image identities before execution.
    val state = right(ForwardMidpoint.make(
      right(ForwardMidpointArm.make(load(fields.resolve("fixed-forward"), work), right(AffineIso.make(work, fixed, Affine.identity[D3])))),
      right(ForwardMidpointArm.make(load(fields.resolve("moving-forward"), work), right(AffineIso.make(work, moving, Affine.identity[D3]))))))
    val cellReports = Vector(state.fixed.residual, state.moving.residual).map(p => TrilinearCellGeometry.evaluate(p.sourceCoordinates, p.validity))
    val declared = Vector(fixedGrid, movingGrid).map(_.shape.foldLeft(1L)((n, s) => n*(2L*s-1L)))
    val common: Map[String, Any] = Map("schema" -> "hfq-functional-export-native-v1", "declared_samples" -> declared,
      "forward_cell_reports" -> cellReports, "actual_affines" -> "original-C4-identity-factors", "dense_export_admitted" -> false,
      "qualification_claim" -> false)
    val plan = ForwardMidpointQuery.compile(state, load(fields.resolve("fixed-inverse"), work), load(fields.resolve("moving-inverse"), work))
    val result = plan match
      case Left(error) => common ++ Map("construction_succeeded" -> false, "construction_error" -> error.toString, "functional_admitted" -> false)
      case Right(query) =>
        val directions = Vector(ExportDirection.FixedToMoving, ExportDirection.MovingToFixed)
        val streams = directions.map: direction =>
          val directory = output.resolve(direction.toString)
          Files.createDirectory(directory)
          def stream(name: String) = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(directory.resolve(name), StandardOpenOption.CREATE_NEW)))
          direction -> (stream("samples.f64"), stream("status.u8"), Files.newBufferedWriter(directory.resolve("failures.jsonl"), StandardOpenOption.CREATE_NEW))
        val byDirection = streams.toMap
        val candidate = try
          ForwardQueryExporter.inspect(query, observe = sample =>
            val (values, status, failures) = byDirection(sample.direction)
            sample.result match
              case Right(v) =>
                (v.forward.coordinates ++ v.backward.coordinates ++ Vector(v.forwardJacobian, v.backwardJacobian, v.cycleErrorMm)).foreach(values.writeDouble)
                status.writeByte(1)
              case Left(error) =>
                for _ <- 0 until 9 do values.writeDouble(Double.NaN)
                status.writeByte(0)
                failures.write(RealImageCheckpointJson.encode(Map("index" -> sample.index, "coordinates" -> sample.coordinates,
                  "boundary" -> sample.boundary, "reason" -> error.toString)))
                failures.newLine())
        finally streams.foreach: (_, handles) =>
          handles._1.close()
          handles._2.close()
          handles._3.close()
        candidate match
          case Left(error) => common ++ Map("construction_succeeded" -> true, "inspection_error" -> error.toString, "functional_admitted" -> false)
          case Right(value) =>
            val admitted = ForwardQueryExporter.admit(value)
            val bytes = value.bundleBytes
            val bundle = output.resolve("functional.hfqb")
            Files.write(bundle, bytes, StandardOpenOption.CREATE_NEW)
            val restored = right(ForwardQueryBundle.decode(Files.readAllBytes(bundle)))
            val roundtripBytes = right(ForwardQueryBundle.encode(restored))
            val probes = admitted.toOption.toVector.flatMap: exported =>
              directions.map: direction =>
                val frame = if direction == ExportDirection.FixedToMoving then exported.fixedFrame else exported.movingFrame
                val point = right(image4s.geometry.Point.fromVector(frame.canonical, frame.grid.voxelToWorld(Vector(7.123, 13.234, 22.345))))
                val checked = exported.evaluate(direction, point, exported.newWorkspace())
                Map("direction" -> direction.toString, "point" -> point.coordinates, "succeeded" -> checked.isRight,
                  "coordinates" -> checked.toOption.map(_.forward.coordinates), "cycle_mm" -> checked.toOption.map(_.cycleErrorMm),
                  "failure" -> checked.swap.toOption.map(_.toString))
            common ++ Map("construction_succeeded" -> true, "report" -> value.report, "functional_admitted" -> admitted.isRight,
              "bundle_sha256" -> EvidenceHash.file(bundle).hex, "bundle_bytes" -> bytes.length,
              "bundle_byte_stable" -> java.util.Arrays.equals(bytes, roundtripBytes), "runtime_probes" -> probes)
    Files.writeString(output.resolve("result.json"), RealImageCheckpointJson.encode(result) + "\n", StandardOpenOption.CREATE_NEW)
    ()
