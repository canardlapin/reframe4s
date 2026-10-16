package reframe4s.flashalign.benchmark

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import reframe4s.flashalign.FlashalignWorkCounts

final case class CostMeasurement(
    latencyMedianMillis: Double,
    latencyP95Millis: Double,
    allocatedMedianBytes: Long,
    allocatedP95Bytes: Long,
    observedPeakHeapDeltaBytes: Long,
    checksum: Double
)

object LinearCostProbe:
  private val WarmupIterations = 3
  private val MeasurementIterations = 15

  def main(arguments: Array[String]): Unit =
    val output =
      arguments.headOption
        .map(Paths.get(_))
        .getOrElse(
          Paths.get("benchmarks/flashalign/raw/linear-cost-probe-2026-09-12.json")
        )
    val workload = LinearBenchmarkWorkload.create(31)

    val rigidPreparation = measure(() => workload.prepareRigid(), identity)
    val affinePreparation = measure(() => workload.prepareAffine(), identity)
    val rigidWarm = measure(
      () => workload.warmRigid(),
      (value: LinearRunObservation) => value.checksum
    )
    val affineWarm = measure(
      () => workload.warmAffine(),
      (value: LinearRunObservation) => value.checksum
    )
    val rigidCold = measure(
      () => workload.coldRigid(),
      (value: LinearRunObservation) => value.checksum
    )
    val affineCold = measure(
      () => workload.coldAffine(),
      (value: LinearRunObservation) => value.checksum
    )
    val rigidEvidence = workload.warmRigid()
    val affineEvidence = workload.warmAffine()

    val json = render(
      workload,
      rigidPreparation._1,
      affinePreparation._1,
      rigidWarm._1,
      affineWarm._1,
      rigidCold._1,
      affineCold._1,
      rigidEvidence,
      affineEvidence
    )
    Option(output.getParent).foreach(Files.createDirectories(_))
    Files.writeString(output, json, StandardCharsets.UTF_8)
    println(s"wrote ${output.toAbsolutePath}")

  private def measure[A](
      operation: () => A,
      checksumOf: A => Double
  ): (CostMeasurement, A) =
    var warmup = 0
    while warmup < WarmupIterations do
      checksumOf(operation())
      warmup += 1
    System.gc()
    Thread.sleep(50L)

    val bean = allocationBean()
    val threadId = Thread.currentThread().threadId()
    val latencies = new Array[Long](MeasurementIterations)
    val allocations = new Array[Long](MeasurementIterations)
    var maximumPeak = 0L
    var last = Option.empty[A]
    var checksum = 0.0
    var index = 0
    while index < MeasurementIterations do
      val peak = new PeakHeapProbe
      peak.start()
      val allocatedBefore = bean.getThreadAllocatedBytes(threadId)
      val started = System.nanoTime()
      val observedValue = operation()
      val finished = System.nanoTime()
      val allocatedAfter = bean.getThreadAllocatedBytes(threadId)
      val peakDelta = peak.stopAndPeakDelta()
      last = Some(observedValue)
      val observed = checksumOf(observedValue)
      require(observed.isFinite, s"benchmark checksum $index is nonfinite")
      checksum += observed
      latencies(index) = math.max(0L, finished - started)
      allocations(index) = math.max(0L, allocatedAfter - allocatedBefore)
      maximumPeak = math.max(maximumPeak, peakDelta)
      index += 1

    val sortedLatency = latencies.sorted
    val sortedAllocation = allocations.sorted
    (
      CostMeasurement(
        latencyMedianMillis = percentile(sortedLatency, 0.5).toDouble / 1000000.0,
        latencyP95Millis = percentile(sortedLatency, 0.95).toDouble / 1000000.0,
        allocatedMedianBytes = percentile(sortedAllocation, 0.5),
        allocatedP95Bytes = percentile(sortedAllocation, 0.95),
        observedPeakHeapDeltaBytes = maximumPeak,
        checksum = checksum
      ),
      last.getOrElse(throw new IllegalStateException("measurement produced no value"))
    )

  private def percentile(values: Array[Long], probability: Double): Long =
    val index = math.min(
      values.length - 1,
      math.max(0, math.ceil(probability * values.length.toDouble).toInt - 1)
    )
    values(index)

  private def allocationBean(): ThreadMXBean =
    ManagementFactory.getThreadMXBean match
      case bean: ThreadMXBean if bean.isThreadAllocatedMemorySupported =>
        if !bean.isThreadAllocatedMemoryEnabled then
          bean.setThreadAllocatedMemoryEnabled(true)
        bean
      case _ =>
        throw new IllegalStateException(
          "the JVM does not expose per-thread allocation accounting"
        )

  private def render(
      workload: LinearBenchmarkWorkload,
      rigidPreparation: CostMeasurement,
      affinePreparation: CostMeasurement,
      rigidWarm: CostMeasurement,
      affineWarm: CostMeasurement,
      rigidCold: CostMeasurement,
      affineCold: CostMeasurement,
      rigidEvidence: LinearRunObservation,
      affineEvidence: LinearRunObservation
  ): String =
    val runtime = Runtime.getRuntime
    s"""{
       |  "schema_version": "flashalign.linear-cost-probe.v1",
       |  "workload": {
       |    "side": ${workload.side},
       |    "voxels_per_image": ${workload.side * workload.side * workload.side},
       |    "rigid_preset": "epi-t1",
       |    "affine_preset": "within-modality",
       |    "input": "deterministic analytic in-memory scalar pair",
       |    "read_millis": 0.0,
       |    "decompress_millis": 0.0,
       |    "capture_millis": 0.0,
       |    "output_resampling_millis": 0.0,
       |    "warmup_iterations": $WarmupIterations,
       |    "measurement_iterations": $MeasurementIterations
       |  },
       |  "environment": {
       |    "os": "${escaped(System.getProperty("os.name"))}",
       |    "arch": "${escaped(System.getProperty("os.arch"))}",
       |    "jdk": "${escaped(System.getProperty("java.vm.name"))} ${escaped(System.getProperty("java.version"))}",
       |    "available_processors": ${runtime.availableProcessors()},
       |    "maximum_heap_bytes": ${runtime.maxMemory()}
       |  },
       |  "phase_contract": {
       |    "preparation": "compile immutable pair plan, including support, native prepared level, patch population, fixed role draws, fused fixed sampler and model-specific objective",
       |    "warm_optimize_and_audit": "reuse compiled plan and workspace; optimize and evaluate the unopened audit draw",
       |    "cold_complete": "preparation plus new workspace plus optimization and audit",
       |    "excluded_zero_cost_phases": ["file read", "NIfTI decompression", "structural capture", "final output resampling"]
       |  },
       |  "rigid": {
       |    "preparation": ${measurement(rigidPreparation)},
       |    "warm_optimize_and_audit": ${measurement(rigidWarm)},
       |    "cold_complete": ${measurement(rigidCold)},
       |    "accepted": true,
       |    "work": ${work(rigidEvidence.diagnostics.work)}
       |  },
       |  "affine": {
       |    "preparation": ${measurement(affinePreparation)},
       |    "warm_optimize_and_audit": ${measurement(affineWarm)},
       |    "cold_complete": ${measurement(affineCold)},
       |    "accepted": true,
       |    "work": ${work(affineEvidence.diagnostics.work)}
       |  },
       |  "claim_boundary": "Machine-local analytic engineering measurement. It is not an MRI accuracy, clinical, external-comparator, or general-hardware performance claim."
       |}
       |""".stripMargin

  private def measurement(value: CostMeasurement): String =
    f"""{
       |      "latency_median_millis": ${value.latencyMedianMillis}%.9f,
       |      "latency_p95_millis": ${value.latencyP95Millis}%.9f,
       |      "allocated_median_bytes": ${value.allocatedMedianBytes},
       |      "allocated_p95_bytes": ${value.allocatedP95Bytes},
       |      "observed_peak_heap_delta_bytes": ${value.observedPeakHeapDeltaBytes},
       |      "checksum": ${value.checksum}%.17g
       |    }""".stripMargin

  private def work(value: FlashalignWorkCounts): String =
    s"""{
       |      "unique_interpolations": ${value.uniqueInterpolations},
       |      "gradient_evaluations": ${value.gradientEvaluations},
       |      "source_voxel_reads": ${value.sourceVoxelReads},
       |      "patch_entry_evaluations": ${value.patchEntryEvaluations},
       |      "patch_occurrence_evaluations": ${value.patchOccurrenceEvaluations},
       |      "data_linearizations": ${value.dataLinearizations},
       |      "prior_linearizations": ${value.priorLinearizations},
       |      "linear_solver_calls": ${value.linearSolverCalls},
       |      "trial_evaluations": ${value.trialEvaluations},
       |      "early_rejected_trials": ${value.earlyRejectedTrials},
       |      "accepted_steps": ${value.acceptedSteps},
       |      "rejected_steps": ${value.rejectedSteps},
       |      "clipped_steps": ${value.clippedSteps},
       |      "selection_evaluations": ${value.selectionEvaluations},
       |      "audit_evaluations": ${value.auditEvaluations},
       |      "curvature_products": ${value.curvatureProducts},
       |      "nonlinear_coefficients": ${value.nonlinearCoefficients},
       |      "parallel_workers": ${value.parallelWorkers}
       |    }""".stripMargin

  private def escaped(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  private final class PeakHeapProbe:
    @volatile private var running = true
    @volatile private var maximum = usedHeap()
    private val baseline = maximum
    private val thread = new Thread(
      () =>
        while running do
          maximum = math.max(maximum, usedHeap())
          Thread.sleep(1L),
      "flashalign-peak-heap-probe"
    )
    thread.setDaemon(true)

    def start(): Unit = thread.start()

    def stopAndPeakDelta(): Long =
      running = false
      thread.join()
      math.max(0L, maximum - baseline)

    private def usedHeap(): Long =
      val runtime = Runtime.getRuntime
      runtime.totalMemory() - runtime.freeMemory()
