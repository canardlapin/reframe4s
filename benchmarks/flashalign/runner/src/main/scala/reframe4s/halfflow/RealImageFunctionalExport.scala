package reframe4s.halfflow

import image4s.geometry.Point
import image4s.nifti.Nifti
import java.io.{BufferedOutputStream, DataOutputStream}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import reframe4s.halfflow.internal.*

/** Offline application of saved functional maps. No fitting and no label inputs. */
object RealImageFunctionalExport:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def stream(path: Path): DataOutputStream =
    new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)))
  private def write(path: Path, value: Any): Unit =
    Files.writeString(path, RealImageCheckpointJson.encode(value) + "\n", StandardOpenOption.CREATE_NEW)
    ()

  /** Explicit IO boundary: numerical lattice equality precedes binding to the
    * fresh canonical owner reconstructed by the bundle. Names are insufficient.
    */
  private[halfflow] def bind(image: NeuroVol[Double], expected: GridSpec): NeuroVol[Double] =
    require(image.space.shape == expected.shape, "image shape differs from saved endpoint")
    require(image.space.indexToFrame.rowMajor == expected.indexToFrame.rowMajor, "image affine differs from saved endpoint")
    require(image.copyLegacyLinear.forall(_.isFinite), "nonfinite input image")
    NeuroVol.fromRavel(image.values, expected, "saved-query-image")

  private def load(path: Path): NeuroVol[Double] =
    val decoded = right(Nifti.readScaledDouble(path))
    decoded.image.fold(_ => throw new IllegalArgumentException("scalar D3 image required"), d =>
      val image = right(d.value.requireDataRank[3])
      NeuroVol.fromRavel(image.data, GridSpec.fromGrid(image.grid), "original-image"))

  private[halfflow] def applyNodes(exported: ForwardQueryExporter.Admitted, direction: ExportDirection,
      source: NeuroVol[Double], output: Path): Map[String, Any] =
    val frame = if direction == ExportDirection.FixedToMoving then exported.fixedFrame else exported.movingFrame
    val sourceFrame = if direction == ExportDirection.FixedToMoving then exported.movingFrame else exported.fixedFrame
    val bound = bind(source, sourceFrame.grid)
    val grid = frame.grid
    val n = grid.nVoxels
    val coordinates = Array.fill(3*n)(Double.NaN)
    val valid = Array.fill(n)(false)
    val workspace = exported.newWorkspace()
    val values = stream(output.resolve("nodes.f64"))
    val statuses = stream(output.resolve("nodes-status.u8"))
    val failures = Files.newBufferedWriter(output.resolve("node-failures.jsonl"), StandardOpenOption.CREATE_NEW)
    var successful = 0
    try
      var i = 0
      while i < n do
        val q = grid.voxelToWorld(Vector((i % grid.extentX).toDouble,
          (i / grid.extentX % grid.extentY).toDouble, (i / (grid.extentX*grid.extentY)).toDouble))
        val point = right(Point.fromVector(frame.canonical, q))
        exported.evaluate(direction, point, workspace) match
          case Right(v) =>
            for c <- 0 until 3 do coordinates(i+c*n) = v.forward.coordinates(c)
            valid(i) = true
            successful += 1
            (v.forward.coordinates ++ v.backward.coordinates ++ Vector(v.forwardJacobian,v.backwardJacobian,v.cycleErrorMm)).foreach(values.writeDouble)
            statuses.writeByte(1)
          case Left(error) =>
            for _ <- 0 until 9 do values.writeDouble(Double.NaN)
            statuses.writeByte(0)
            failures.write(RealImageCheckpointJson.encode(Map("index" -> i,"coordinates" -> q,"reason" -> error.toString)))
            failures.newLine()
        i += 1
    finally
      values.close()
      statuses.close()
      failures.close()
    // Diagnostic nodal coordinates, evaluated directly through the checked API.
    // This does not create or admit an interpolated dense transform.
    val field = DenseVectorField.fromLegacyPlanar(grid, coordinates, DenseVectorFieldKind.SourceCoordinates)
    val pulled = HalfFlowKernels.pullScalar(bound, field, FieldValidity.copyMask(valid))
    val imageValues = stream(output.resolve("warped.f64"))
    val imageValid = stream(output.resolve("image-status.u8"))
    try
      pulled.values.copyLegacyLinear.foreach(imageValues.writeDouble)
      pulled.valid.copyLegacyLinear.foreach(v => imageValid.writeByte(if v then 1 else 0))
    finally
      imageValues.close()
      imageValid.close()
    val report = Map("eligible" -> n,"successful" -> successful,
      "image_supported" -> pulled.valid.copyLegacyLinear.count(identity),
      "shape" -> grid.shape,"index_to_ras_mm" -> grid.indexToFrame.rowMajor,
      "coordinates" -> "nodes.f64: big endian9 doubles, XYZ/backXYZ/Jforward/Jback/cycle, x-fastest",
      "warped" -> "warped.f64: big endian scalar, x-fastest; outside=0", "dense_export_admitted" -> false)
    write(output.resolve("application.json"), report)
    report

  def main(args: Array[String]): Unit =
    require(args.length == 4, "saved.hfqb fixed.nii.gz moving.nii.gz fresh-output")
    val output = Paths.get(args(3))
    require(!Files.exists(output), "preserve prior outcomes")
    val query = right(ForwardQueryBundle.decode(Files.readAllBytes(Paths.get(args(0)))))
    val fixed = bind(load(Paths.get(args(1))), query.fixedFrame.grid)
    val moving = bind(load(Paths.get(args(2))), query.movingFrame.grid)
    Files.createDirectories(output)
    val directions = Vector(ExportDirection.FixedToMoving, ExportDirection.MovingToFixed)
    val streams = directions.map: direction =>
      val directory = output.resolve(direction.toString)
      Files.createDirectory(directory)
      direction -> (stream(directory.resolve("samples.f64")), stream(directory.resolve("status.u8")),
        Files.newBufferedWriter(directory.resolve("failures.jsonl"), StandardOpenOption.CREATE_NEW))
    val handles = streams.toMap
    val candidate = try
      ForwardQueryExporter.inspect(query, observe = sample =>
        val (values,status,failures) = handles(sample.direction)
        sample.result match
          case Right(v) =>
            (v.forward.coordinates ++ v.backward.coordinates ++ Vector(v.forwardJacobian,v.backwardJacobian,v.cycleErrorMm)).foreach(values.writeDouble)
            status.writeByte(1)
          case Left(error) =>
            for _ <- 0 until 9 do values.writeDouble(Double.NaN)
            status.writeByte(0)
            failures.write(RealImageCheckpointJson.encode(Map("index" -> sample.index,"coordinates" -> sample.coordinates,
              "boundary" -> sample.boundary,"reason" -> error.toString)))
            failures.newLine())
    finally streams.foreach: (_,h) =>
      h._1.close()
      h._2.close()
      h._3.close()
    val result = candidate match
      case Left(error) => Map("inspection_error" -> error.toString,"functional_admitted" -> false)
      case Right(value) =>
        val admitted = ForwardQueryExporter.admit(value)
        val applications = admitted.toOption.toVector.flatMap: exported =>
          directions.map(d => d.toString -> applyNodes(exported,d,
            if d == ExportDirection.FixedToMoving then moving else fixed,output.resolve(d.toString)))
        Map("report" -> value.report,"functional_admitted" -> admitted.isRight,
          "applications" -> applications.toMap,"admission_failure" -> admitted.swap.toOption.map(_.toString))
    write(output.resolve("result.json"), result ++ Map("schema" -> "hfq-functional-mri-native-v1",
      "dense_export_admitted" -> false,"qualification_claim" -> false))
