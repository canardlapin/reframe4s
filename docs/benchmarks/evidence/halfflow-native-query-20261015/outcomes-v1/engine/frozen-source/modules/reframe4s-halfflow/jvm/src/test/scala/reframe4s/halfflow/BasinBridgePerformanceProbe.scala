package reframe4s.halfflow

import java.lang.management.ManagementFactory

/** JVM front end for the shared cross-platform BasinBridge performance probe. */
object BasinBridgePerformanceProbe:
  private final case class Measurement(
      medianNanos: Long,
      p95Nanos: Long,
      medianAllocatedBytes: Long,
      checksum: Double
  )

  private val allocationBean: Option[com.sun.management.ThreadMXBean] =
    ManagementFactory.getThreadMXBean match
      case bean: com.sun.management.ThreadMXBean =>
        if bean.isThreadAllocatedMemorySupported then
          if !bean.isThreadAllocatedMemoryEnabled then bean.setThreadAllocatedMemoryEnabled(true)
          Some(bean)
        else None
      case _ => None

  def main(args: Array[String]): Unit =
    val side = if args.nonEmpty then args(0).toInt else 25
    val warmups = if args.length >= 2 then args(1).toInt else 2
    val samples = if args.length >= 3 then args(2).toInt else 5
    require(warmups >= 1, "warmups must be positive")
    require(samples >= 3, "samples must be at least three")
    val setup = BasinBridgePerformanceScenario.make(side)
    val native = measure(warmups, samples)(BasinBridgePerformanceScenario.nativeRound(setup))
    val oracle = measure(warmups, samples)(BasinBridgePerformanceScenario.oracleRound(setup))
    println("BASIN_BRIDGE_PERFORMANCE_JVM_JSON_BEGIN")
    println("{")
    println("  \"schema\": \"reframe4s-basinbridge-performance-probe-v1\",")
    println(s"""  "jvm": "${escape(System.getProperty("java.vm.name"))} ${escape(System.getProperty("java.version"))}",""")
    println(s"""  "os": "${escape(System.getProperty("os.name"))} ${escape(System.getProperty("os.arch"))}",""")
    println(s"  \"side\": ${setup.side},")
    println(s"  \"voxels\": ${setup.grid.nVoxels},")
    println(s"  \"warmups\": $warmups,")
    println(s"  \"samples\": $samples,")
    println(s"  \"allocationAccounting\": ${allocationBean.nonEmpty},")
    printMeasurement("nativeRound", native, true)
    printMeasurement("oracleRound", oracle, false)
    println("}")
    println("BASIN_BRIDGE_PERFORMANCE_JVM_JSON_END")

  private def measure(warmups: Int, samples: Int)(operation: => Double): Measurement =
    var checksum = 0.0
    var index = 0
    while index < warmups do
      checksum = operation
      index += 1
    val nanos = Array.ofDim[Long](samples)
    val allocated = Array.ofDim[Long](samples)
    index = 0
    while index < samples do
      val before = currentAllocatedBytes()
      val started = System.nanoTime()
      checksum = operation
      nanos(index) = System.nanoTime() - started
      val after = currentAllocatedBytes()
      allocated(index) =
        if before >= 0L && after >= before then after - before else -1L
      index += 1
    val sortedNanos = nanos.sorted
    val sortedAllocations = allocated.filter(_ >= 0L).sorted
    Measurement(
      percentile(sortedNanos, 0.5),
      percentile(sortedNanos, 0.95),
      if sortedAllocations.isEmpty then -1L else percentile(sortedAllocations, 0.5),
      checksum
    )

  private def currentAllocatedBytes(): Long =
    allocationBean.fold(-1L)(_.getCurrentThreadAllocatedBytes)

  private def percentile(values: Array[Long], probability: Double): Long =
    val index = math.min(values.length - 1, math.ceil(probability * values.length.toDouble).toInt - 1)
    values(math.max(0, index))

  private def printMeasurement(name: String, measurement: Measurement, trailingComma: Boolean): Unit =
    println(s"  \"$name\": {")
    println(s"    \"medianNanos\": ${measurement.medianNanos},")
    println(s"    \"p95Nanos\": ${measurement.p95Nanos},")
    println(s"    \"medianAllocatedBytes\": ${measurement.medianAllocatedBytes},")
    println(s"    \"checksum\": ${measurement.checksum}")
    println(if trailingComma then "  }," else "  }")

  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
