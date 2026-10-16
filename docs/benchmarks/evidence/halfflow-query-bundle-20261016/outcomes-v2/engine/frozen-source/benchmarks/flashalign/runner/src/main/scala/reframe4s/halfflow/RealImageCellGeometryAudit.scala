package reframe4s.halfflow

import gale.linalg.DMat
import java.io.DataInputStream
import java.nio.file.{Files, Paths, StandardOpenOption}
import reframe4s.halfflow.internal.*

/** Read-only audit of captured coordinate fields; no fitting or map mutation. */
object RealImageCellGeometryAudit:
  def main(args: Array[String]): Unit =
    require(args.length == 2, "field-stem output-json")
    val output = Paths.get(args(1))
    require(!Files.exists(output), "refusing to overwrite cell audit")
    val metadata = Files.readString(Paths.get(args(0) + ".json"))
    def array(key: String): Vector[Double] =
      val pattern = ("\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]").r
      pattern.findFirstMatchIn(metadata).getOrElse(
        throw new IllegalArgumentException("missing field " + key)).group(1)
        .split(",").map(_.trim.toDouble).toVector
    val shape = array("shape")
    require(shape.length == 3 && shape.forall(v => v.isFinite && v >= 1 && v == v.toInt.toDouble))
    val grid = GridSpec(shape.map(_.toInt), DMat.dense(4, 4, array("affine")))
    val coordinates = new Array[Double](3 * grid.nVoxels)
    val input = new DataInputStream(Files.newInputStream(Paths.get(args(0) + ".f64")))
    try
      var i = 0
      while i < coordinates.length do
        coordinates(i) = input.readDouble()
        i += 1
      require(input.read() == -1, "trailing coordinate data")
    finally input.close()
    val mask = Files.readAllBytes(Paths.get(args(0) + ".valid-u8"))
    require(mask.length == grid.nVoxels && mask.forall(v => v == 0 || v == 1), "invalid validity data")
    val validity = FieldValidity.copyMask(mask.map(_ != 0))
    val field = DenseVectorField.fromLegacyPlanar(grid, coordinates, DenseVectorFieldKind.SourceCoordinates)
    val report = TrilinearCellGeometry.evaluate(field, validity)
    val frame = RegistrationFrame[Unit](SpatialDomainId("captured-cell-audit"), grid)
    val pull = DensePull.make(frame, frame, field, validity).fold(e => throw new IllegalArgumentException(e.message), identity)
    val step = HalfStep.make(pull, DensePull.identity(frame)).fold(e => throw new IllegalArgumentException(e.message), identity)
    val admitted = ForwardGeometry.incremental(step)._1 == GeometryVerdict.Valid
    val result = Map("minimum_cell_jacobian" -> report.minimum, "maximum_cell_jacobian" -> report.maximum,
      "covered_cells" -> report.evaluated, "total_declared_cells" -> report.eligible,
      "covered_fraction" -> report.validFraction, "folded_cells" -> report.nonPositive,
      "nonfinite_cells" -> report.nonFinite, "samples_per_cell" -> 9,
      "cell_pass" -> report.acceptable(0.05, 0.99), "shared_forward_gate_accepted" -> admitted)
    val _ = Files.writeString(output, RealImageCheckpointJson.encode(result) + "\n", StandardOpenOption.CREATE_NEW)
