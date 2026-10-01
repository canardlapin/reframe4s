package reframe4s.flashalign

import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid
import ravel.DType.given
import ravel.NDArray

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/** Large-field development-only timing probe for structural capture.
  *
  * The analytic pair is the public-pipeline regression fixture, rather than a
  * confirmation-court row. It deliberately requires expanded capture.
  */
object StructuralCaptureLargeDevelopmentProbe:
  private val Side = 73
  private val Truth = Vector(
    1.0, 0.0, 0.0, 6.0,
    0.0, 1.0, 0.0, -6.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
  )

  def main(arguments: Array[String]): Unit =
    val (output, candidateId, repetitions) = arguments.toVector match
      case Vector(path, candidate, count) =>
        (Paths.get(path), candidate, count.toInt)
      case _ =>
        throw new IllegalArgumentException(
          "usage: StructuralCaptureLargeDevelopmentProbe <output-json> <candidate-id> <repetitions>"
        )
    if repetitions <= 0 then
      throw new IllegalArgumentException("repetitions must be positive")

    val movingFrame = right(Frame.named[D3]("capture-probe-moving"))
    val fixedFrame = right(Frame.named[D3]("capture-probe-fixed"))
    val movingGrid = right(
      Grid.in(movingFrame)(Vector(Side, Side, Side), Affine.identity[D3])
    )
    val fixedGrid = right(
      Grid.in(fixedFrame)(Vector(Side, Side, Side), Affine.identity[D3])
    )
    val fixed = right(
      Sampled.continuous(
        fixedGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](Side, Side, Side)(signal)
      )
    )
    val moving = right(
      Sampled.continuous(
        movingGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](Side, Side, Side) { (i, j, k) =>
          val mapped = applyAffine(
            Truth,
            Vector(i.toDouble, j.toDouble, k.toDouble)
          )
          signal(mapped(0), mapped(1), mapped(2))
        }
      )
    )
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )
    val plan = right(Flashalign.rigid(moving, fixed, config))

    var warmup = 0
    while warmup < 2 do
      right(plan.run(plan.newWorkspace()))
      warmup += 1

    val rows = Vector.newBuilder[ProbeRow]
    var repetition = 0
    while repetition < repetitions do
      val started = System.nanoTime()
      val result = right(plan.run(plan.newWorkspace()))
      val totalMs = (System.nanoTime() - started).toDouble / 1000000.0
      val capture = result.diagnostics.capture.getOrElse(
        throw new IllegalStateException("automatic result omitted capture diagnostics")
      )
      if !capture.expandedCaptureExecuted || capture.identityAdequate then
        throw new IllegalStateException(
          s"development fixture no longer exercises expanded capture: $capture"
        )
      val matrix = result.movingToFixed.operator.rowMajor
      val maximumTruthError = matrix.zip(Truth).map { case (actual, expected) =>
        math.abs(actual - expected)
      }.max
      if maximumTruthError > 0.35 then
        throw new IllegalStateException(
          s"development fixture transform drifted by $maximumTruthError: $matrix"
        )
      rows += ProbeRow(
        repetition,
        totalMs,
        capture.captureElapsedNanoseconds.toDouble / 1000000.0,
        capture.preparationElapsedNanoseconds.toDouble / 1000000.0,
        capture.refinementAndSelectionElapsedNanoseconds.toDouble / 1000000.0,
        capture.finalAuditElapsedNanoseconds.toDouble / 1000000.0,
        capture.evaluatedRotations,
        capture.candidates.size,
        maximumTruthError,
        matrix
      )
      repetition += 1

    val measured = rows.result()
    val captureTimes = measured.map(_.captureMs)
    val totalTimes = measured.map(_.totalMs)
    val json = render(
      candidateId,
      measured,
      median(captureTimes),
      percentile(captureTimes, 0.95),
      median(totalTimes),
      percentile(totalTimes, 0.95)
    )
    Option(output.getParent).foreach(Files.createDirectories(_))
    Files.writeString(output, json, StandardCharsets.UTF_8)
    println(json)

  private def render(
      candidateId: String,
      rows: Vector[ProbeRow],
      captureMedian: Double,
      captureP95: Double,
      totalMedian: Double,
      totalP95: Double
  ): String =
    val rowJson = rows.map { row =>
      val matrix = row.matrix.map(java.lang.Double.toString).mkString(",")
      s"""{"repetition":${row.repetition},"total_ms":${row.totalMs},"capture_ms":${row.captureMs},"preparation_ms":${row.preparationMs},"refinement_and_selection_ms":${row.refinementMs},"final_audit_ms":${row.auditMs},"evaluated_rotations":${row.evaluatedRotations},"retained_candidates":${row.retainedCandidates},"maximum_matrix_truth_error":${row.maximumMatrixTruthError},"moving_to_fixed":[$matrix]}"""
    }.mkString(",")
    s"""{
       |  "schema_version": "flashalign.structural-capture-development-probe.v1",
       |  "candidate_id": "$candidateId",
       |  "fixture": "automatic-linear-pipeline-analytic-translation-6-minus6-0",
       |  "role": "open-development-only; excluded from confirmation",
       |  "repetitions": ${rows.size},
       |  "capture_median_ms": $captureMedian,
       |  "capture_p95_ms": $captureP95,
       |  "total_median_ms": $totalMedian,
       |  "total_p95_ms": $totalP95,
       |  "runs": [$rowJson]
       |}
       |""".stripMargin

  private def median(values: Vector[Double]): Double =
    percentile(values, 0.5)

  private def percentile(values: Vector[Double], probability: Double): Double =
    val sorted = values.sorted
    val index = math.ceil(probability * sorted.size).toInt - 1
    sorted(math.max(0, math.min(sorted.size - 1, index)))

  private def signal(x: Double, y: Double, z: Double): Double =
    def blob(
        centerX: Double,
        centerY: Double,
        centerZ: Double,
        scaleX: Double,
        scaleY: Double,
        scaleZ: Double
    ): Double =
      val dx = (x - centerX) / scaleX
      val dy = (y - centerY) / scaleY
      val dz = (z - centerZ) / scaleZ
      math.exp(-0.5 * (dx * dx + dy * dy + dz * dz))
    2.0 * blob(9.0, 12.0, 17.0, 3.0, 5.0, 4.0) -
      1.4 * blob(27.0, 9.0, 24.0, 5.0, 3.0, 4.0) +
      1.1 * blob(19.0, 29.0, 8.0, 4.0, 3.0, 5.0) +
      0.8 * blob(30.0, 27.0, 31.0, 3.0, 4.0, 2.5) +
      0.23 * math.sin(0.17 * x + 0.11 * y + 0.07 * z) +
      0.16 * math.cos(0.013 * x * y - 0.009 * y * z + 0.006 * x * z)

  private def applyAffine(
      matrix: Vector[Double],
      point: Vector[Double]
  ): Vector[Double] =
    Vector.tabulate(3)(row =>
      matrix(row * 4) * point(0) +
        matrix(row * 4 + 1) * point(1) +
        matrix(row * 4 + 2) * point(2) +
        matrix(row * 4 + 3)
    )

  private def right[A](value: Either[?, A]): A =
    value.fold(error => throw new IllegalStateException(error.toString), identity)

  private final case class ProbeRow(
      repetition: Int,
      totalMs: Double,
      captureMs: Double,
      preparationMs: Double,
      refinementMs: Double,
      auditMs: Double,
      evaluatedRotations: Int,
      retainedCandidates: Int,
      maximumMatrixTruthError: Double,
      matrix: Vector[Double]
  )
