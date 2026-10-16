package reframe4s.halfflow

import image4s.geometry.{Affine, CoordinateConvention, D3, Frame, FrameId, Grid, GridId}
import java.nio.file.{Files, Paths, StandardOpenOption}
import reframe4s.benchmark.flashalign.EvidenceHash
import reframe4s.core.{CellSamplingRule, ImplementationRevision, IndexRegion, TopologyCertificate, TopologyCriteria, TopologyDiagnostics, TopologyScope}

/** Threshold-check independently measured cell diagnostics in the native
  * certificate API. The Python evaluator hashes the actual map and assesses
  * all declared cells; this adapter does not authenticate caller measurements.
  */
object RealImageTopologyCertificate:
  private def right[E, A](value: Either[E, A]): A =
    value.fold(error => throw new IllegalArgumentException(error.toString), identity)

  private def quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  def main(args: Array[String]): Unit =
    require(args.length == 3, "scope-input.txt output.json implementation-source-sha256")
    val input = Paths.get(args(0))
    val lines = Files.readAllLines(input)
    require(lines.size() == 4, "scope input requires shape, affine, diagnostics, and map SHA-256")
    val shape = lines.get(0).split(" ").map(_.toInt).toVector
    val affine = lines.get(1).split(" ").map(_.toDouble).toVector
    val values = lines.get(2).split(" ")
    val mapSha = lines.get(3)
    require(shape.length == 3 && affine.length == 16 && values.length == 5, "invalid scope input")
    require(mapSha.matches("[0-9a-f]{64}") && args(2).matches("[0-9a-f]{64}"), "invalid content identity")
    val frame = right(Frame.persistentNamed[D3](right(FrameId.parse("mri-map-" + mapSha)),
      "MRI pull-map evaluation", convention = CoordinateConvention.RAS))
    val grid = right(Grid.createPersistent(right(GridId.parse("mri-grid-" + mapSha)), frame)(shape,
      right(Affine.fromRowMajor[D3](affine))))
    val region = right(IndexRegion.within[D3](shape, Vector(0, 0, 0), shape))
    val scope = right(TopologyScope.on(grid, region, CellSamplingRule.CellCornersAndCenter,
      0.05, right(TopologyCriteria.create(0.99, 0L)), right(ImplementationRevision.parse(args(2)))))
    val totalCells = shape.map(_ - 1).map(_.toLong).product
    val coveredCells = values(4).toLong
    require(coveredCells > 0 && coveredCells <= totalCells, "covered cells differ from the declared grid")
    require(math.abs(values(3).toDouble - coveredCells.toDouble / totalCells.toDouble) <= 1e-12,
      "coverage fraction differs from cell counts")
    val diagnostics = right(TopologyDiagnostics.create(values(0).toDouble, values(1).toDouble,
      values(2).toLong, values(3).toDouble, values(4).toLong))
    val certificate = TopologyCertificate.fromReportedDiagnostics(scope, diagnostics)
    val record = certificate.fold(_ => "null", value => quoted(value.record.toString))
    val failure = certificate.fold(error => quoted(error.message), _ => "null")
    val json = s"""{
      |  "accepted": ${certificate.isRight},
      |  "map_sha256": "$mapSha",
      |  "scope_input_sha256": "${EvidenceHash.file(input).hex}",
      |  "implementation_source_sha256": "${args(2)}",
      |  "measurement_source": "independent Python trilinear-cell evaluator; caller-reported diagnostics",
      |  "scope_record": ${quoted(scope.record.toString)},
      |  "diagnostics_record": ${quoted(diagnostics.record.toString)},
      |  "certificate_record": $record,
      |  "failure": $failure
      |}
      |""".stripMargin
    Files.writeString(Paths.get(args(1)), json, StandardOpenOption.CREATE_NEW)
    println(s"TOPOLOGY accepted=${certificate.isRight} map=$mapSha cells=${diagnostics.sampledCellCount}")
    if certificate.isLeft then throw new IllegalArgumentException("cell topology certificate rejected")
