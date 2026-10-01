package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/** Machine-local operator probe. It uses the production projected-patch cache
  * and Gale PCG adapter with a deterministic synthetic nonlinear basis.
  */
object NonlinearCostProbe:
  private val UniquePoints = 1024
  private val Patches = 512
  private val PatchSize = 27
  private val Warmups = 5
  private val Measurements = 25

  def main(arguments: Array[String]): Unit =
    val output = arguments.headOption
      .map(Paths.get(_))
      .getOrElse(
        Paths.get(
          "benchmarks/flashalign/raw/nonlinear-cost-probe-2026-09-12.json"
        )
      )
    // Compile and warm the largest loop shape before recording any case so
    // case order is not also JVM warmup order.
    val _ = runCase(128)
    val observations = Vector(32, 64, 128).map(runCase)
    val preconditioning = preconditionerProbe(6 + observations.last.coefficients)
    val rendered = render(observations, preconditioning)
    Option(output.getParent).foreach(Files.createDirectories(_))
    Files.writeString(output, rendered, StandardCharsets.UTF_8)
    println(s"wrote ${output.toAbsolutePath}")

  private final case class Observation(
      coefficients: Int,
      parameters: Int,
      setupMillis: Double,
      productMedianMillis: Double,
      productP95Millis: Double,
      maximumRelativeError: Double,
      checksum: Double,
      retainedEvidenceBytes: Long,
      basisTableBytes: Long,
      productionDenseNormalBytes: Long,
      interpolationCallsAtLinearization: Long,
      gradientCallsAtLinearization: Long,
      interpolationCallsPerProduct: Long,
      gradientCallsPerProduct: Long,
      products: Long
  )

  private final case class PreconditioningObservation(
      dimension: Int,
      unpreconditionedTermination: String,
      unpreconditionedIterations: Int,
      unpreconditionedProducts: Long,
      unpreconditionedResidual: Double,
      diagonalTermination: String,
      diagonalIterations: Int,
      diagonalProducts: Long,
      diagonalResidual: Double,
      retainedPreconditionerBytes: Long
  )

  private final case class Fixture(
      cache: MatrixFreePatchLinearization3[
        Unit,
        Frame[D3],
        Frame[D3]
      ],
      operator: ProbeGeometry,
      basis: Vector[Vector[Double]],
      patches: Vector[CachedProjectedPatch3]
  )

  private def runCase(coefficients: Int): Observation =
    val started = System.nanoTime()
    val fixture = createFixture(coefficients)
    val setupMillis = (System.nanoTime() - started).toDouble / 1000000.0
    val directionValues = Array.tabulate(fixture.operator.parameterCount)(index =>
      0.03 * math.sin(0.17 * (index + 1))
    )
    val direction = right(
      GeometryDirection3.create(
        fixture.operator.modelId,
        fixture.operator.basisId,
        directionValues
      )
    )
    val workspace = fixture.cache.newWorkspace()
    val result = new Array[Double](fixture.operator.parameterCount)
    var warmup = 0
    while warmup < Warmups do
      right(fixture.cache.curvatureProduct(direction, result, workspace))
      warmup += 1
    val samples = new Array[Long](Measurements)
    var checksum = 0.0
    var measurement = 0
    while measurement < Measurements do
      val begin = System.nanoTime()
      right(fixture.cache.curvatureProduct(direction, result, workspace))
      samples(measurement) = System.nanoTime() - begin
      checksum += result.sum
      measurement += 1
    val explicit = explicitProduct(
      fixture.basis,
      fixture.patches,
      directionValues
    )
    val relative = maximumRelativeError(result, explicit)
    require(relative <= 2e-10, s"K=$coefficients product error $relative")
    val diagnostics = fixture.cache.diagnostics
    require(diagnostics.productionDenseNormalBytes == 0L)
    require(diagnostics.interpolationCallsPerProduct == 0L)
    require(diagnostics.gradientCallsPerProduct == 0L)
    val sorted = samples.sorted
    Observation(
      coefficients,
      fixture.operator.parameterCount,
      setupMillis,
      percentile(sorted, 0.5).toDouble / 1000000.0,
      percentile(sorted, 0.95).toDouble / 1000000.0,
      relative,
      checksum,
      diagnostics.retainedEvidenceBytes,
      diagnostics.basisTableBytes,
      diagnostics.productionDenseNormalBytes,
      diagnostics.interpolationCallsAtLinearization,
      diagnostics.gradientCallsAtLinearization,
      diagnostics.interpolationCallsPerProduct,
      diagnostics.gradientCallsPerProduct,
      workspace.curvatureProducts
    )

  private def createFixture(coefficients: Int): Fixture =
    val parameters = 6 + coefficients
    val moving: Frame[D3] = right(Frame.named[D3](s"nonlinear-cost-moving-$coefficients"))
    val fixed: Frame[D3] = right(Frame.named[D3](s"nonlinear-cost-fixed-$coefficients"))
    val basis = Vector.tabulate(UniquePoints, parameters) { (point, parameter) =>
      val p = point + 1.0
      val q = parameter + 1.0
      math.sin(0.0017 * p * q) +
        0.35 * math.cos(0.00091 * (p + 3.0) * q)
    }
    val operator = new ProbeGeometry(moving, fixed, basis, coefficients)
    val coordinates = Array.tabulate(UniquePoints * 3) { index =>
      val point = index / 3
      index % 3 match
        case 0 => -8.0 + 16.0 * (point % 32).toDouble / 31.0
        case 1 => -7.0 + 14.0 * ((point / 32) % 32).toDouble / 31.0
        case _ => -6.0 + 12.0 * (point / (32 * 32)).toDouble
    }
    val points = right(WorldPointBatch3.create(moving, coordinates))
    val patches = Vector.tabulate(Patches) { patch =>
      val indices = Vector.tabulate(PatchSize)(sample =>
        (patch * 13 + sample * 7) % UniquePoints
      )
      val u = normalized(Vector.tabulate(PatchSize)(sample =>
        math.sin(0.31 * (sample + 1) + 0.007 * patch)
      ))
      val v = normalized(Vector.tabulate(PatchSize)(sample =>
        math.cos(0.23 * (sample + 2) - 0.005 * patch)
      ))
      CachedProjectedPatch3(
        indices,
        u,
        v,
        fixedContrastNorm = 1.0 + 0.01 * (patch % 11),
        correlation = dot(u, v),
        objectiveWeight = 0.5 + 0.01 * (patch % 17),
        inlierWeight = 0.4 + 0.02 * (patch % 13),
        signedWeight = 0.2,
        invalidReason = None
      )
    }
    val cache = right(
      MatrixFreePatchLinearization3.create(
        20260912L + coefficients,
        PatchSampleSetId(
          101L,
          coefficients.toLong,
          PatchSampleRole.Optimization,
          0,
          20260912L
        ),
        operator,
        (),
        points,
        Array.tabulate(UniquePoints)(point => math.sin(0.03 * point)),
        Array.tabulate(UniquePoints * 3) { index =>
          index % 3 match
            case 0 => 1.0
            case 1 => 0.25
            case _ => -0.1
        },
        patches,
        basisTableBytes = UniquePoints.toLong * parameters.toLong * 8L
      )
    )
    Fixture(cache, operator, basis, patches)

  private def explicitProduct(
      basis: Vector[Vector[Double]],
      patches: Vector[CachedProjectedPatch3],
      direction: Array[Double]
  ): Vector[Double] =
    val intensity = basis.map(row => dot(row, direction.toVector))
    val scalarForces = Array.fill(basis.length)(0.0)
    patches.foreach { patch =>
      val gathered = patch.sampleIndices.map(intensity)
      val mean = gathered.sum / gathered.length.toDouble
      val centered = gathered.map(_ - mean)
      val along = dot(patch.fixedUnit, centered)
      val scale = patch.objectiveWeight * patch.inlierWeight /
        (patch.fixedContrastNorm * patch.fixedContrastNorm)
      patch.sampleIndices.indices.foreach { sample =>
        scalarForces(patch.sampleIndices(sample)) +=
          scale * (centered(sample) - patch.fixedUnit(sample) * along)
      }
    }
    Vector.tabulate(direction.length)(parameter =>
      basis.indices.map(point => basis(point)(parameter) * scalarForces(point)).sum
    )

  private def preconditionerProbe(
      dimension: Int
  ): PreconditioningObservation =
    val diagonal = Vector.tabulate(dimension)(index =>
      math.pow(10.0, -6.0 + 12.0 * index.toDouble / (dimension - 1).toDouble)
    )
    val operator = new ArraySymmetricOperator:
      val dimension: Int = diagonal.length
      def apply(
          input: Array[Double],
          output: Array[Double]
      ): Either[String, Unit] =
        var index = 0
        while index < dimension do
          output(index) = diagonal(index) * input(index)
          index += 1
        Right(())
    val identity = new ArrayPreconditioner:
      val dimension: Int = diagonal.length
      val retainedBytes: Long = 0L
      def solve(
          residual: Array[Double],
          output: Array[Double]
      ): Either[String, Unit] =
        java.lang.System.arraycopy(residual, 0, output, 0, dimension)
        Right(())
    val exact = right(
      PriorDataDiagonalPreconditioner.create(
        Vector.fill(dimension)(0.0),
        diagonal,
        Vector.fill(dimension)(0.0)
      )
    )
    val config = right(GalePcgConfig.create(1e-10, 512))
    val rhs = Vector.tabulate(dimension)(index =>
      diagonal(index) * math.sin(0.17 * (index + 1))
    )
    val plainPlan = right(GalePcgPlan.create(operator, identity, config))
    val exactPlan = right(GalePcgPlan.create(operator, exact, config))
    val plain = right(plainPlan.solve(rhs, plainPlan.newWorkspace()))
    val conditioned = right(exactPlan.solve(rhs, exactPlan.newWorkspace()))
    require(conditioned.converged)
    require(conditioned.diagnostics.operatorProducts < plain.diagnostics.operatorProducts)
    PreconditioningObservation(
      dimension,
      plain.termination.toString,
      plain.diagnostics.iterations,
      plain.diagnostics.operatorProducts,
      plain.diagnostics.unpreconditionedResidualNorm,
      conditioned.termination.toString,
      conditioned.diagnostics.iterations,
      conditioned.diagnostics.operatorProducts,
      conditioned.diagnostics.unpreconditionedResidualNorm,
      exact.retainedBytes
    )

  private def render(
      values: Vector[Observation],
      preconditioner: PreconditioningObservation
  ): String =
    val runtime = Runtime.getRuntime
    val cases = values.map(value =>
      f"""    {
         |      "nonlinear_coefficients": ${value.coefficients},
         |      "parameters_including_pose": ${value.parameters},
         |      "unique_points": $UniquePoints,
         |      "patches": $Patches,
         |      "patch_size": $PatchSize,
         |      "setup_millis": ${value.setupMillis}%.9f,
         |      "curvature_product_median_millis": ${value.productMedianMillis}%.9f,
         |      "curvature_product_p95_millis": ${value.productP95Millis}%.9f,
         |      "maximum_relative_error_vs_explicit": ${value.maximumRelativeError}%.17g,
         |      "checksum": ${value.checksum}%.17g,
         |      "retained_evidence_bytes": ${value.retainedEvidenceBytes},
         |      "budgeted_basis_table_bytes": ${value.basisTableBytes},
         |      "production_dense_normal_bytes": ${value.productionDenseNormalBytes},
         |      "interpolation_calls_at_linearization": ${value.interpolationCallsAtLinearization},
         |      "gradient_calls_at_linearization": ${value.gradientCallsAtLinearization},
         |      "interpolation_calls_per_product": ${value.interpolationCallsPerProduct},
         |      "gradient_calls_per_product": ${value.gradientCallsPerProduct},
         |      "measured_products_including_warmup": ${value.products}
         |    }""".stripMargin
    ).mkString(",\n")
    s"""{
       |  "schema_version": "flashalign.nonlinear-cost-probe.v1",
       |  "environment": {
       |    "os": "${escape(System.getProperty("os.name"))}",
       |    "arch": "${escape(System.getProperty("os.arch"))}",
       |    "jdk": "${escape(System.getProperty("java.vm.name"))} ${escape(System.getProperty("java.version"))}",
       |    "available_processors": ${runtime.availableProcessors()},
       |    "maximum_heap_bytes": ${runtime.maxMemory()},
       |    "threads": 1
       |  },
       |  "measurement": {
       |    "warmup_products": $Warmups,
       |    "measured_products": $Measurements,
       |    "timer": "System.nanoTime",
       |    "accuracy_reference": "independent explicit gather-project-scatter and basis-transpose loop"
       |  },
       |  "cases": [
       |$cases
       |  ],
       |  "preconditioner": {
       |    "dimension": ${preconditioner.dimension},
       |    "condition_span": "1e-6 through 1e6 diagonal",
       |    "unpreconditioned_termination": "${preconditioner.unpreconditionedTermination}",
       |    "unpreconditioned_iterations": ${preconditioner.unpreconditionedIterations},
       |    "unpreconditioned_products": ${preconditioner.unpreconditionedProducts},
       |    "unpreconditioned_residual": ${preconditioner.unpreconditionedResidual},
       |    "diagonal_termination": "${preconditioner.diagonalTermination}",
       |    "diagonal_iterations": ${preconditioner.diagonalIterations},
       |    "diagonal_products": ${preconditioner.diagonalProducts},
       |    "diagonal_residual": ${preconditioner.diagonalResidual},
       |    "retained_preconditioner_bytes": ${preconditioner.retainedPreconditionerBytes}
       |  },
       |  "claim_boundary": "Machine-local deterministic operator measurement. Setup excludes MRI I/O and registration capture; the result is not an MRI accuracy, clinical, end-to-end latency, or general-hardware claim."
       |}
       |""".stripMargin

  private def normalized(values: Vector[Double]): Vector[Double] =
    val mean = values.sum / values.length.toDouble
    val centered = values.map(_ - mean)
    val length = math.sqrt(dot(centered, centered))
    centered.map(_ / length)

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

  private def maximumRelativeError(
      actual: Array[Double],
      expected: Vector[Double]
  ): Double = actual.indices.map { index =>
    math.abs(actual(index) - expected(index)) /
      math.max(1.0, math.abs(expected(index)))
  }.max

  private def percentile(values: Array[Long], probability: Double): Long =
    val index = math.min(
      values.length - 1,
      math.max(0, math.ceil(probability * values.length.toDouble).toInt - 1)
    )
    values(index)

  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  private def right[A](value: Either[?, A]): A =
    value.fold(error => throw new IllegalArgumentException(error.toString), identity)

private final class ProbeGeometry(
    val moving: Frame[D3],
    val fixed: Frame[D3],
    basis: Vector[Vector[Double]],
    coefficients: Int
) extends GeometryPointOperator3[Unit, Frame[D3], Frame[D3]]:
  val modelId = s"flashalign-probe-field-$coefficients-v1"
  val basisId = Some(s"flashalign-probe-basis-$coefficients-v1")
  val parameterCount: Int = basis.head.length

  protected def validateState(state: Unit): Either[GeometryOperatorError, Unit] =
    Right(state)

  protected def mapUnchecked(
      state: Unit,
      points: WorldPointBatch3[Frame[D3]],
      output: GeometryOutputBuffer3[Frame[D3]],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = state
    val _ = workspace
    java.lang.System.arraycopy(points.packed, 0, output.packed, 0, points.packed.length)

  protected def inverseMapChecked(
      state: Unit,
      points: WorldPointBatch3[Frame[D3]],
      output: GeometryOutputBuffer3[Frame[D3]],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    mapUnchecked(state, points, output, workspace)
    Right(())

  protected def jvpUnchecked(
      state: Unit,
      points: WorldPointBatch3[Frame[D3]],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Frame[D3]],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = state
    val _ = workspace
    var point = 0
    while point < points.size do
      var value = 0.0
      var parameter = 0
      while parameter < parameterCount do
        value += basis(point)(parameter) * direction(parameter)
        parameter += 1
      output.packed(point * 3) = value
      output.packed(point * 3 + 1) = 0.0
      output.packed(point * 3 + 2) = 0.0
      point += 1

  protected def vjpUnchecked(
      state: Unit,
      points: WorldPointBatch3[Frame[D3]],
      forces: WorldVectorBatch3[Frame[D3]],
      output: Array[Double],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = state
    val _ = workspace
    java.util.Arrays.fill(output, 0.0)
    var point = 0
    while point < points.size do
      var parameter = 0
      while parameter < parameterCount do
        output(parameter) += basis(point)(parameter) * forces.packed(point * 3)
        parameter += 1
      point += 1

  protected def proposeChecked(
      state: Unit,
      direction: Array[Double]
  ): Either[GeometryOperatorError, (Unit, Array[Double])] =
    Right(state -> direction.clone())

  def priorTerms(state: Unit): Either[GeometryOperatorError, GeometryPriorTerms3] =
    Right(
      GeometryPriorTerms3(
        0.0,
        Vector.fill(parameterCount)(0.0),
        Vector.fill(PackedSymmetric.size(parameterCount))(0.0)
      )
    )

  def certify(state: Unit): Either[GeometryOperatorError, GeometryCertificate3] =
    Right(
      GeometryCertificate3(
        modelId,
        basisId,
        valid = true,
        "deterministic nonlinear operator cost probe"
      )
    )
