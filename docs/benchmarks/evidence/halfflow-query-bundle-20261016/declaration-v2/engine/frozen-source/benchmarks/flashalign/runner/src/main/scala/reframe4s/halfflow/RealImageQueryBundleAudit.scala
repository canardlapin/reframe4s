package reframe4s.halfflow

import gale.linalg.DMat
import image4s.geometry.{Affine, D3}
import java.io.{BufferedOutputStream, DataOutputStream}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import reframe4s.benchmark.flashalign.EvidenceHash
import reframe4s.halfflow.internal.*

/** Offline replay only. No fitting, checkpoint selection or dense admission. */
object RealImageQueryBundleAudit:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def numbers(metadata: String, key: String): Vector[Double] =
    ("\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]").r.findFirstMatchIn(metadata)
      .getOrElse(throw new IllegalArgumentException("missing " + key)).group(1).split(",").map(_.trim.toDouble).toVector
  private def endpoint(path: Path, owner: Option[GridSpec]): GridSpec =
    val metadata = Files.readString(path)
    val dims = numbers(metadata, "shape")
    require(dims.length == 3 && dims.forall(n => n >= 1 && n.isFinite && n == n.toInt.toDouble))
    val affine = right(Affine.fromRowMajor[D3](numbers(metadata, "affine")))
    owner.fold(GridSpec(dims.map(_.toInt), DMat.dense(4, 4, affine.rowMajor)))(_.withGeometry(dims.map(_.toInt), affine))

  def main(args: Array[String]): Unit =
    require(args.length == 4, "checkpoint fixed-grid.json moving-grid.json fresh-output")
    val checkpoint = Paths.get(args(0))
    val output = Paths.get(args(3))
    require(!Files.exists(output), "preserve previous audit attempts")
    Files.createDirectories(output)
    val fixedGrid = endpoint(Paths.get(args(1)), None)
    val workGrid = RealImageCheckpointExport.workGrid(checkpoint, fixedGrid)
    val work = RegistrationFrame[Unit](SpatialDomainId("bundle-work"), workGrid)
    val fixed = RegistrationFrame[Unit](SpatialDomainId("bundle-fixed"), fixedGrid)
    val moving = RegistrationFrame[Unit](SpatialDomainId("bundle-moving"), endpoint(Paths.get(args(2)), None))
    val state = RealImageCheckpointExport.loadState(checkpoint, work, fixed, moving)
    val plan = right(ForwardMidpointQuery.compile(state, DensePull.identity(work), DensePull.identity(work)))
    val bytes = right(ForwardQueryBundle.encode(plan))
    val bundle = output.resolve("transform.hfqb")
    Files.write(bundle, bytes, StandardOpenOption.CREATE_NEW)
    // Read the actual saved bytes; retain original plan for independent reload parity.
    val restored = right(ForwardQueryBundle.decode(Files.readAllBytes(bundle)))
    val byteStable = java.util.Arrays.equals(bytes, right(ForwardQueryBundle.encode(restored)))
    require(byteStable, "bundle re-encoding changed bytes")
    val rows = Vector(ExportDirection.FixedToMoving, ExportDirection.MovingToFixed).map: direction =>
      val grid = if direction == ExportDirection.FixedToMoving then fixedGrid else moving.grid
      audit(plan, restored, direction, grid, output)
    val result = Map("schema" -> "hfq-query-bundle-native-v1", "bundle_sha256" -> EvidenceHash.file(bundle).hex,
      "bundle_bytes" -> bytes.length, "byte_stable" -> byteStable, "rows" -> rows,
      "seed_policy" -> "identity-on-captured-work-grid", "dense_export_admitted" -> false, "qualification_claim" -> false)
    Files.writeString(output.resolve("result.json"), RealImageCheckpointJson.encode(result) + "\n", StandardOpenOption.CREATE_NEW)
    ()

  private def audit(original: ForwardMidpointQuery[?, ?, ?], restored: ForwardMidpointQuery[?, ?, ?],
      direction: ExportDirection, grid: GridSpec, output: Path): Map[String, Any] =
    val reverse = if direction == ExportDirection.FixedToMoving then ExportDirection.MovingToFixed else ExportDirection.FixedToMoving
    val directory = output.resolve(direction.toString)
    Files.createDirectory(directory)
    def stream(name: String) = new DataOutputStream(new BufferedOutputStream(
      Files.newOutputStream(directory.resolve(name), StandardOpenOption.CREATE_NEW)))
    val mapped = stream("mapped.f64")
    val cycles = stream("cycles.f64")
    val status = stream("status.u8")
    val failures = Files.newBufferedWriter(directory.resolve("failures.jsonl"), StandardOpenOption.CREATE_NEW)
    val beforeWorkspace = original.newWorkspace()
    val afterWorkspace = restored.newWorkspace()
    var mappedSuccess = 0
    var cycleSuccess = 0
    var boundaryEligible = 0
    var boundarySuccess = 0
    var parityFailures = 0
    var maximumCycle = 0.0
    var maximumParity = 0.0
    var index = 0
    try
      while index < grid.nVoxels do
        val x = index % grid.extentX
        val y = index / grid.extentX % grid.extentY
        val z = index / (grid.extentX * grid.extentY)
        val boundary = x == 0 || y == 0 || z == 0 || x == grid.extentX - 1 || y == grid.extentY - 1 || z == grid.extentZ - 1
        if boundary then boundaryEligible += 1
        val query = grid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))
        val before = original.evaluateCoordinates(direction, query, beforeWorkspace)
        val after = restored.evaluateCoordinates(direction, query, afterWorkspace)
        val beforeBack = before.flatMap(v => original.evaluateCoordinates(reverse, v.coordinates, beforeWorkspace))
        val afterBack = after.flatMap(v => restored.evaluateCoordinates(reverse, v.coordinates, afterWorkspace))
        def sameReport(a: ResidualQueryReport, b: ResidualQueryReport): Boolean =
          a.estimate == b.estimate && java.lang.Double.doubleToLongBits(a.residualMm) == java.lang.Double.doubleToLongBits(b.residualMm) &&
            java.lang.Double.doubleToLongBits(a.determinant) == java.lang.Double.doubleToLongBits(b.determinant) &&
            (a.iterations, a.forwardSamples, a.derivativeSamples, a.linearSolves, a.backtrackingTrials) ==
            (b.iterations, b.forwardSamples, b.derivativeSamples, b.linearSolves, b.backtrackingTrials)
        def same(a: Either[ForwardQueryFailure, ResidualQueryValue], b: Either[ForwardQueryFailure, ResidualQueryValue]): Boolean =
          (a, b) match
            case (Right(u), Right(v)) =>
              val difference = math.sqrt(u.coordinates.zip(v.coordinates).map((x, y) => (x-y)*(x-y)).sum)
              maximumParity = math.max(maximumParity, difference)
              difference == 0.0 && sameReport(u.report, v.report)
            case (Left(u), Left(v)) => u.stage == v.stage && u.failure.reason == v.failure.reason && sameReport(u.failure.report, v.failure.report)
            case _ => false
        if !same(before, after) || !same(beforeBack, afterBack) then parityFailures += 1
        if after.isRight then mappedSuccess += 1
        if afterBack.isRight then
          cycleSuccess += 1
          if boundary then boundarySuccess += 1
          val value = afterBack.toOption.get.coordinates
          maximumCycle = math.max(maximumCycle, math.sqrt(value.zip(query).map((x, y) => (x-y)*(x-y)).sum))
        else
          val error = afterBack.swap.toOption.get
          failures.write(RealImageCheckpointJson.encode(Map("index" -> index, "boundary" -> boundary,
            "stage" -> error.stage.toString, "reason" -> error.failure.reason.toString)))
          failures.newLine()
        after.fold(_ => Vector.fill(3)(Double.NaN), _.coordinates).foreach(mapped.writeDouble)
        afterBack.fold(_ => Vector.fill(3)(Double.NaN), _.coordinates).foreach(cycles.writeDouble)
        status.writeByte((if after.isRight then 1 else 0) | (if afterBack.isRight then 2 else 0))
        index += 1
    finally
      mapped.close()
      cycles.close()
      status.close()
      failures.close()
    val result = Map("direction" -> direction.toString, "eligible" -> grid.nVoxels, "mapped_success" -> mappedSuccess,
      "cycle_success" -> cycleSuccess, "boundary_eligible" -> boundaryEligible, "boundary_success" -> boundarySuccess,
      "reload_parity_failures" -> parityFailures, "maximum_reload_difference_mm" -> maximumParity,
      "maximum_successful_cycle_mm" -> maximumCycle,
      "cycle_pass" -> (cycleSuccess == grid.nVoxels && maximumCycle <= 0.2))
    Files.writeString(directory.resolve("result.json"), RealImageCheckpointJson.encode(result) + "\n", StandardOpenOption.CREATE_NEW)
    result
