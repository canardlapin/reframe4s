package reframe4s.flashalign

import image4s.BoundaryPolicy
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.Validity
import image4s.geometry.Affine
import image4s.geometry.CoordinateConvention
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.FrameId
import image4s.geometry.Grid
import image4s.geometry.GridId
import image4s.geometry.LengthUnit
import image4s.geometry.Point
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import ravel.DType.given
import ravel.NDArray
import reframe4s.lie.Rigid3
import reframe4s.resample.Interpolation

/** Machine-local production output probe for the supported PE coefficient
  * range. It measures nonlinear model/result construction, numerical inverse
  * materialization and the single final interpolation pass separately.
  */
object NonlinearOutputCostProbe:
  private val MovingSide = 24
  private val Warmups = 2
  private val Measurements = 7
  private val CoefficientCounts = Vector(32, 64, 128, 256)
  private val TargetSides = Vector(8, 16)

  def main(arguments: Array[String]): Unit =
    val output = arguments.headOption
      .map(Paths.get(_))
      .getOrElse(
        Paths.get(
          "benchmarks/flashalign/raw/nonlinear-output-cost-probe-2026-09-12.json"
        )
      )
    // Discard one largest-case execution before recording the factorial.
    val _ = runCase(CoefficientCounts.last, TargetSides.last, "warmup")
    val observations = for
      coefficients <- CoefficientCounts
      side <- TargetSides
    yield runCase(coefficients, side, s"k$coefficients-s$side")
    Option(output.getParent).foreach(Files.createDirectories(_))
    Files.writeString(output, render(observations), StandardCharsets.UTF_8)
    println(s"wrote ${output.toAbsolutePath}")

  private final case class Observation(
      coefficients: Int,
      targetSide: Int,
      targetVoxels: Long,
      modelSetupMillis: Double,
      inverseMaterializationMedianMillis: Double,
      inverseMaterializationP95Millis: Double,
      interpolationMedianMillis: Double,
      interpolationP95Millis: Double,
      maximumOutputError: Double,
      validityFull: Long,
      coordinateBytes: Long,
      outputBytes: Long,
      validityBytes: Long,
      incrementalPeakBytes: Long,
      interpolationPasses: Int,
      interpolationEvaluations: Long,
      checksum: Double
  )

  private final case class Fixture(
      moving: Frame[D3],
      fixed: Frame[D3],
      movingGrid: Grid[Frame[D3], D3],
      fixedGrid: Grid[Frame[D3], D3],
      result: NonlinearFlashalignResult3_STA
  )

  private type NonlinearFlashalignResult3_STA = NonlinearFlashalignResult3[
    PeFieldState3[Frame[D3], Frame[D3]],
    Frame[D3],
    Frame[D3]
  ]

  private def runCase(
      coefficients: Int,
      targetSide: Int,
      label: String
  ): Observation =
    val setupStart = System.nanoTime()
    val fixture = makeFixture(coefficients, targetSide, label)
    val modelSetupMillis = elapsedMillis(setupStart)
    val movingAffine = fixture.movingGrid.indexToFrame.rowMajor
    val original = right(
      Sampled.continuous(
        fixture.movingGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](MovingSide, MovingSide, MovingSide) { (i, j, k) =>
          analytic(applyAffine(movingAffine, i.toDouble, j.toDouble, k.toDouble))
        }
      )
    )
    val budget = right(NonlinearOutputBudget.create(Long.MaxValue))
    def compileOutput() = right(
      NonlinearOutputPlan3.compile(
        original,
        fixture.fixedGrid,
        fixture.result,
        Interpolation.Linear,
        BoundaryPolicy.Constant(-1000.0),
        budget
      )
    )
    var inverseWarmup = 0
    while inverseWarmup < Warmups do
      compileOutput()
      inverseWarmup += 1
    val inverseSamples = new Array[Long](Measurements)
    var plan = compileOutput()
    var inverseMeasurement = 0
    while inverseMeasurement < Measurements do
      val started = System.nanoTime()
      plan = compileOutput()
      inverseSamples(inverseMeasurement) = System.nanoTime() - started
      inverseMeasurement += 1
    val sortedInverse = inverseSamples.sorted
    var warmup = 0
    while warmup < Warmups do
      right(plan.run())
      warmup += 1
    val samples = new Array[Long](Measurements)
    var last = right(plan.run())
    var measurement = 0
    while measurement < Measurements do
      val started = System.nanoTime()
      last = right(plan.run())
      samples(measurement) = System.nanoTime() - started
      measurement += 1
    val sorted = samples.sorted
    val affine = fixture.fixedGrid.indexToFrame.rowMajor
    var maximumError = 0.0
    var full = 0L
    var checksum = 0.0
    var i = 0
    while i < targetSide do
      var j = 0
      while j < targetSide do
        var k = 0
        while k < targetSide do
          val fixedWorld = applyAffine(affine, i.toDouble, j.toDouble, k.toDouble)
          val fixedPoint = point(fixture.fixed, fixedWorld)
          val movingPoint = right(fixture.result.fixedToMoving(fixedPoint))
          val expected = analytic(movingPoint.coordinates)
          val actual = right(last.output.image.valueAt(Vector(i, j, k)))
          maximumError = math.max(maximumError, math.abs(actual - expected))
          checksum += actual
          if right(last.output.validity.at(Vector(i, j, k))) == Validity.Full then full += 1L
          k += 1
        j += 1
      i += 1
    require(maximumError <= 5e-10, s"K=$coefficients side=$targetSide output error $maximumError")
    require(full == plan.cost.targetVoxelCount)
    Observation(
      coefficients,
      targetSide,
      plan.cost.targetVoxelCount,
      modelSetupMillis,
      percentile(sortedInverse, 0.5).toDouble / 1000000.0,
      percentile(sortedInverse, 0.95).toDouble / 1000000.0,
      percentile(sorted, 0.5).toDouble / 1000000.0,
      percentile(sorted, 0.95).toDouble / 1000000.0,
      maximumError,
      full,
      plan.cost.coordinateArrayBytes,
      plan.cost.outputBytes,
      plan.cost.validityBytes,
      plan.cost.estimatedIncrementalPeakBytes,
      plan.cost.finalInterpolationPasses,
      plan.cost.sourceInterpolationEvaluations,
      checksum
    )

  private def makeFixture(
      coefficients: Int,
      targetSide: Int,
      label: String
  ): Fixture =
    val moving = persistentFrame(s"nonlinear-output-$label-moving")
    val fixed = persistentFrame(s"nonlinear-output-$label-fixed")
    val movingGrid = right(
      Grid.createPersistent[D3, Frame[D3]](
        right(GridId.parse(s"nonlinear-output-$label-moving-grid")),
        moving
      )(
        Vector.fill(3)(MovingSide),
        axisAlignedAffine(-11.5, 1.0)
      )
    )
    val targetOrigin = -(targetSide - 1).toDouble / 2.0
    val fixedGrid = right(
      Grid.createPersistent[D3, Frame[D3]](
        right(GridId.parse(s"nonlinear-output-$label-fixed-grid")),
        fixed
      )(
        Vector.fill(3)(targetSide),
        axisAlignedAffine(targetOrigin, 1.0)
      )
    )
    val domain = right(
      PhysicalSpectralDomain3.create(
        Vector(-20.0, -20.0, -20.0),
        identityAxes,
        Vector(40.0, 40.0, 40.0),
        Vector(2.0, 2.0, 2.0),
        "periodic-padded-r3-v1"
      )
    )
    val basis = syntheticBasis(domain, coefficients, label)
    val rigidModel = right(
      RigidModel3.atFixedWorld[Frame[D3], Frame[D3]](moving, fixed)(0.0, 0.0, 0.0)
    )
    val model = right(
      PeFieldModel3.compile(
        rigidModel,
        basis,
        PhaseEncodingDirection3(0, 1, Vector(1.0, 0.0, 0.0)),
        SpectralPriorWeights(0.5, 0.8, 0.05),
        right(PeFieldModelConfig.create(coefficients, coefficients, coefficients))
      )
    )
    val pose = right(Rigid3.translationBetween(moving, fixed)(0.0, 0.0, 0.0))
    val values = Vector.tabulate(coefficients)(index => if index == 0 then 0.05 else 0.0)
    val state = PeFieldState3(pose, right(basis.coefficientState(values)))
    val sourceDomain = right(
      PeWorldDomain3.fromSpectralDomain(s"nonlinear-output-$label-source", domain, includePadding = false)
    )
    val fixedDomain = right(
      PeWorldDomain3.create(
        s"nonlinear-output-$label-fixed",
        Vector(-20.0, -20.0, -20.0),
        identityAxes,
        Vector(0.0, 0.0, 0.0),
        Vector(40.0, 40.0, 40.0)
      )
    )
    val config = PeGeometryConfig(
      right(PeCertificateConfig.create(0.2, 5.0, 1e-12, 1e-12)),
      right(PeInverseConfig.create(1e-10, 64)),
      sourceDomain,
      fixedDomain
    )
    val evidence = FlashalignInverseEvidenceRecord(
      "pe-bracketed-inverse-v1",
      converged = true,
      maximumResidualMm = 1e-10,
      meanResidualMm = 2e-11,
      sampleCount = targetSide.toLong * targetSide.toLong * targetSide.toLong,
      coveredFraction = 1.0,
      maximumIterations = 64,
      maximumResidualCriterionMm = 1e-6,
      minimumCoveredFraction = 1.0,
      "cost-probe held-grid roundtrip residual; numerical inverse and not SmoothIso"
    )
    val result = right(NonlinearFlashalignResult3.peField(model, state, config, evidence))
    Fixture(moving, fixed, movingGrid, fixedGrid, result)

  private def syntheticBasis(
      domain: PhysicalSpectralDomain3,
      coefficients: Int,
      label: String
  ): PhysicalSpectralBasis3 =
    require(coefficients % 2 == 0)
    val requested = (for
      i <- -8 to 8
      j <- -8 to 8
      k <- -8 to 8
      if i != 0 || j != 0 || k != 0
      if i > 0 || (i == 0 && j > 0) || (i == 0 && j == 0 && k > 0)
    yield IntegerWave3(i, j, k)).toVector
      .sortBy(wave => (wave.squaredFrequency, wave.i, wave.j, wave.k))
      .take(coefficients / 2)
    val modes = requested.flatMap { wave =>
      val angular = Vector(
        2.0 * math.Pi * wave.i.toDouble / 40.0,
        2.0 * math.Pi * wave.j.toDouble / 40.0,
        2.0 * math.Pi * wave.k.toDouble / 40.0
      )
      val gradientBound = math.sqrt(2.0) * math.sqrt(angular.map(value => value * value).sum)
      Vector(RealSpectralPhase.Cosine, RealSpectralPhase.Sine).map(phase =>
        SpectralMode3(
          s"${wave.i},${wave.j},${wave.k}:${phase.id}",
          wave,
          phase,
          angular,
          gaugeMean = 0.0,
          gradientBound
        )
      )
    }
    val squared = modes.map(mode => mode.angularWaveWorldPerMm.map(value => value * value).sum)
    PhysicalSpectralBasis3(
      s"nonlinear-output-probe-$label-k$coefficients",
      domain,
      s"nonlinear-output-probe-zero-mean-$label",
      modes,
      diagonal(Vector.fill(coefficients)(1.0)),
      diagonal(squared),
      diagonal(squared.map(value => value * value)),
      coefficients
    )

  private def diagonal(values: Vector[Double]): DenseOperator =
    DenseOperator.tabulate(values.length)((row, column) => if row == column then values(row) else 0.0)

  private def persistentFrame(id: String): Frame[D3] =
    right(
      Frame.persistentNamed[D3](
        right(FrameId.parse(id)),
        id,
        LengthUnit.Millimeter,
        CoordinateConvention.RAS
      )
    )

  private def point(
      frame: Frame[D3],
      coordinates: Vector[Double]
  ): Point[Frame[D3], D3] =
    val exact = right(Point.fromVector(frame, coordinates))
    val alignment = right(Frame.alignOwners[D3, frame.type, Frame[D3]](frame, frame))
    right(alignment.pointToRight(exact))

  private def axisAlignedAffine(origin: Double, spacing: Double): Affine[D3] =
    right(
      Affine.fromRowMajor[D3](Vector(
        spacing, 0.0, 0.0, origin,
        0.0, spacing, 0.0, origin,
        0.0, 0.0, spacing, origin,
        0.0, 0.0, 0.0, 1.0
      ))
    )

  private val identityAxes = Vector(
    Vector(1.0, 0.0, 0.0),
    Vector(0.0, 1.0, 0.0),
    Vector(0.0, 0.0, 1.0)
  )

  private def analytic(world: Vector[Double]): Double =
    4.0 + 0.3 * world(0) - 0.2 * world(1) + 0.1 * world(2)

  private def applyAffine(
      matrix: Vector[Double],
      x: Double,
      y: Double,
      z: Double
  ): Vector[Double] =
    Vector(
      matrix(0) * x + matrix(1) * y + matrix(2) * z + matrix(3),
      matrix(4) * x + matrix(5) * y + matrix(6) * z + matrix(7),
      matrix(8) * x + matrix(9) * y + matrix(10) * z + matrix(11)
    )

  private def elapsedMillis(started: Long): Double =
    (System.nanoTime() - started).toDouble / 1000000.0

  private def percentile(values: Array[Long], probability: Double): Long =
    val index = math.min(
      values.length - 1,
      math.max(0, math.ceil(probability * values.length.toDouble).toInt - 1)
    )
    values(index)

  private def render(values: Vector[Observation]): String =
    val runtime = Runtime.getRuntime
    val cases = values.map { value =>
      f"""    {
         |      "nonlinear_coefficients": ${value.coefficients},
         |      "target_side": ${value.targetSide},
         |      "target_voxels": ${value.targetVoxels},
         |      "model_and_result_setup_millis": ${value.modelSetupMillis}%.9f,
         |      "inverse_coordinate_materialization_median_millis": ${value.inverseMaterializationMedianMillis}%.9f,
         |      "inverse_coordinate_materialization_p95_millis": ${value.inverseMaterializationP95Millis}%.9f,
         |      "final_interpolation_median_millis": ${value.interpolationMedianMillis}%.9f,
         |      "final_interpolation_p95_millis": ${value.interpolationP95Millis}%.9f,
         |      "maximum_output_error": ${value.maximumOutputError}%.17g,
         |      "validity_full_count": ${value.validityFull},
         |      "coordinate_array_bytes": ${value.coordinateBytes},
         |      "output_bytes": ${value.outputBytes},
         |      "validity_bytes": ${value.validityBytes},
         |      "estimated_incremental_peak_bytes": ${value.incrementalPeakBytes},
         |      "final_interpolation_passes": ${value.interpolationPasses},
         |      "source_interpolation_evaluations": ${value.interpolationEvaluations},
         |      "checksum": ${value.checksum}%.17g
         |    }""".stripMargin
    }.mkString(",\n")
    s"""{
       |  "schema_version": "flashalign.nonlinear-output-cost-probe.v1",
       |  "environment": {
       |    "os": "${escape(System.getProperty("os.name"))}",
       |    "arch": "${escape(System.getProperty("os.arch"))}",
       |    "jdk": "${escape(System.getProperty("java.vm.name"))} ${escape(System.getProperty("java.version"))}",
       |    "available_processors": ${runtime.availableProcessors()},
       |    "maximum_heap_bytes": ${runtime.maxMemory()},
       |    "threads": 1
       |  },
       |  "measurement": {
       |    "moving_shape": [$MovingSide, $MovingSide, $MovingSide],
       |    "coefficient_counts": [${CoefficientCounts.mkString(", ")}],
       |    "target_sides": [${TargetSides.mkString(", ")}],
       |    "warmup_inverse_materializations": $Warmups,
       |    "measured_inverse_materializations": $Measurements,
       |    "warmup_interpolation_runs": $Warmups,
       |    "measured_interpolation_runs": $Measurements,
       |    "timer": "System.nanoTime",
       |    "field": "one nonzero 0.05 mm spectral coefficient; remaining coefficients zero",
       |    "basis": "deterministic structurally valid spectral probe basis; basis factory/rank qualification is outside timed setup",
       |    "output": "production numerical PE inverse, NonlinearOutputPlan3 and ResamplingPlan linear interpolation"
       |  },
       |  "cases": [
       |$cases
       |  ],
       |  "claim_boundary": "Machine-local deterministic inverse/output scaling measurement. It is not an MRI accuracy, clinical, full registration, compressed-I/O, or general-hardware claim. Model setup includes frames, grids, a structurally valid synthetic basis, the PE model and result record, but excludes source-image construction and the production basis factory/rank computation."
       |}
       |""".stripMargin

  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  private def right[A](value: Either[?, A]): A =
    value.fold(error => throw new IllegalArgumentException(error.toString), identity)
