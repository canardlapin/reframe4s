package reframe4s.halfflow

import gale.linalg.DMat
import image4s.geometry.{Affine, D3}
import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}
import java.nio.file.{Files, Paths, StandardOpenOption}
import reframe4s.halfflow.internal.*

/** Read-only scalar query audit of immutable captured fields. No fitting. */
object RealImageInverseQueryAudit:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def array(metadata: String, key: String): Vector[Double] =
    ("\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]").r.findFirstMatchIn(metadata)
      .getOrElse(throw new IllegalArgumentException("missing " + key)).group(1).split(",").map(_.trim.toDouble).toVector
  private def grid(stem: String): GridSpec =
    val metadata = Files.readString(Paths.get(stem + ".json"))
    val shape = array(metadata, "shape")
    require(shape.length == 3 && shape.forall(v => v.isFinite && v >= 2 && v == v.toInt.toDouble))
    GridSpec(shape.map(_.toInt), DMat.dense(4, 4, array(metadata, "affine")))
  private def load(stem: String, frame: RegistrationFrame[Unit]): DensePull[Unit, Unit] =
    val metadata = Files.readString(Paths.get(stem + ".json"))
    require(array(metadata, "shape").map(_.toInt) == frame.grid.shape)
    require(array(metadata, "affine") == frame.grid.indexToFrame.rowMajor)
    val values = new Array[Double](3 * frame.grid.nVoxels)
    val input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(stem + ".f64"))))
    try
      var index = 0
      while index < values.length do
        values(index) = input.readDouble()
        index += 1
      require(input.read() == -1, "trailing captured coordinates")
    finally input.close()
    val mask = Files.readAllBytes(Paths.get(stem + ".valid-u8"))
    require(mask.length == frame.grid.nVoxels && mask.forall(b => b == 0 || b == 1))
    right(DensePull.make(frame, frame, DenseVectorField.fromLegacyPlanar(frame.grid, values,
      DenseVectorFieldKind.SourceCoordinates), FieldValidity.copyMask(mask.map(_ != 0))))

  def main(args: Array[String]): Unit =
    require(args.nonEmpty)
    args(0) match
      case "residual" =>
        require(args.length == 6, "residual forward seed queries.f64 result.json coordinates.f64")
        val frame = RegistrationFrame[Unit](SpatialDomainId("native-inverse-query"), grid(args(1)))
        val plan = right(ResidualInverseQuery.compile(load(args(1), frame), load(args(2), frame)))
        val workspace = plan.newWorkspace()
        audit(args(3), args(4), args(5), q => plan.evaluateCoordinates(q(0), q(1), q(2), workspace))
      case "endpoint" =>
        require(args.length == 9, "endpoint fixed seed moving seed queries.f64 result.json coordinates.f64 direction")
        val work = RegistrationFrame[Unit](SpatialDomainId("native-work"), grid(args(1)))
        // Fresh, explicitly related endpoint owners with identity physical affines.
        // This is a residual-composition diagnostic, not a historical endpoint replay.
        val fixed = RegistrationFrame[Unit](SpatialDomainId("native-fixed"), grid(args(1)))
        val moving = RegistrationFrame[Unit](SpatialDomainId("native-moving"), grid(args(1)))
        val state = right(ForwardMidpoint.make(
          right(ForwardMidpointArm.make(load(args(1), work), right(AffineIso.make(work, fixed, Affine.identity[D3])))),
          right(ForwardMidpointArm.make(load(args(3), work), right(AffineIso.make(work, moving, Affine.identity[D3]))))))
        val plan = right(ForwardMidpointQuery.compile(state, load(args(2), work), load(args(4), work)))
        val workspace = plan.newWorkspace()
        val direction = ExportDirection.valueOf(args(8))
        val reverse = if direction == ExportDirection.FixedToMoving then ExportDirection.MovingToFixed else ExportDirection.FixedToMoving
        audit(args(5), args(6), args(7), q =>
          plan.evaluateCoordinates(direction, q, workspace).left.map(_.failure)
            .flatMap(v => plan.evaluateCoordinates(reverse, v.coordinates, workspace).left.map(_.failure).map: back =>
              val a = v.report
              val b = back.report
              back.copy(report = b.copy(residualMm = math.max(a.residualMm, b.residualMm),
                determinant = math.min(a.determinant, b.determinant), iterations = a.iterations + b.iterations,
                forwardSamples = a.forwardSamples + b.forwardSamples, derivativeSamples = a.derivativeSamples + b.derivativeSamples,
                linearSolves = a.linearSolves + b.linearSolves, backtrackingTrials = a.backtrackingTrials + b.backtrackingTrials))))
      case other => throw new IllegalArgumentException("unknown mode " + other)

  private def audit(queryFile: String, resultFile: String, coordinateFile: String,
      evaluate: Vector[Double] => Either[ResidualQueryFailure, ResidualQueryValue]): Unit =
    require(!Files.exists(Paths.get(resultFile)) && !Files.exists(Paths.get(coordinateFile)), "fresh audit outputs required")
    val size = Files.size(Paths.get(queryFile))
    require(size > 0 && size % 24L == 0 && size / 24L <= Int.MaxValue)
    val count = (size / 24L).toInt
    val input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(queryFile))))
    val output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(coordinateFile), StandardOpenOption.CREATE_NEW)))
    val failures = Vector.newBuilder[Map[String, Any]]
    var succeeded = 0
    var maximumResidual = 0.0
    var maximumRoundtrip = 0.0
    var minimumDeterminant = Double.PositiveInfinity
    var samples = 0L
    var derivatives = 0L
    var solves = 0L
    var trials = 0L
    var index = 0
    try
      while index < count do
        val query = Vector(input.readDouble(), input.readDouble(), input.readDouble())
        val result = evaluate(query)
        val report = result.fold(_.report, _.report)
        val coordinates = result match
          case Right(value) =>
            succeeded += 1
            value.coordinates
          case Left(error) =>
            failures += Map("index" -> index, "reason" -> error.reason.toString, "residual_mm" -> report.residualMm)
            // Diagnostic coordinates only; failure remains explicit in the ledger.
            report.estimate.getOrElse(Vector.fill(3)(Double.NaN))
        coordinates.foreach(output.writeDouble)
        if result.isRight then
          maximumResidual = math.max(maximumResidual, report.residualMm)
          minimumDeterminant = math.min(minimumDeterminant, report.determinant)
          maximumRoundtrip = math.max(maximumRoundtrip, math.sqrt((0 until 3).map(a =>
            (coordinates(a)-query(a)) * (coordinates(a)-query(a))).sum))
        samples += report.forwardSamples
        derivatives += report.derivativeSamples
        solves += report.linearSolves
        trials += report.backtrackingTrials
        index += 1
      require(input.read() == -1)
    finally
      input.close()
      output.close()
    val result = Map("eligible" -> count, "succeeded" -> succeeded, "failures" -> failures.result(),
      "maximum_equation_residual_mm" -> maximumResidual, "minimum_root_jacobian" -> minimumDeterminant,
      "maximum_output_query_distance_mm" -> maximumRoundtrip, "forward_samples" -> samples,
      "derivative_samples" -> derivatives, "linear_solves" -> solves, "backtracking_trials" -> trials)
    val _ = Files.writeString(Paths.get(resultFile), RealImageCheckpointJson.encode(result) + "\n", StandardOpenOption.CREATE_NEW)
