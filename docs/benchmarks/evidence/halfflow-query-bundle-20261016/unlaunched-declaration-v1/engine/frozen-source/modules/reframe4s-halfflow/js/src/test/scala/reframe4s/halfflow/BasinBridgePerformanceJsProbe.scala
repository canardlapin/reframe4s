package reframe4s.halfflow

import scala.scalajs.js

/** Scala.js/Node front end for the shared BasinBridge performance probe. */
object BasinBridgePerformanceJsProbe:
  private final case class Measurement(
      medianMillis: Double,
      p95Millis: Double,
      checksum: Double
  )

  def main(args: Array[String]): Unit =
    val side = if args.nonEmpty then args(0).toInt else 25
    val warmups = if args.length >= 2 then args(1).toInt else 1
    val samples = if args.length >= 3 then args(2).toInt else 3
    require(warmups >= 1, "warmups must be positive")
    require(samples >= 3, "samples must be at least three")
    val setup = BasinBridgePerformanceScenario.make(side)
    val native = measure(warmups, samples)(BasinBridgePerformanceScenario.nativeRound(setup))
    val oracle = measure(warmups, samples)(BasinBridgePerformanceScenario.oracleRound(setup))
    println("BASIN_BRIDGE_PERFORMANCE_JS_JSON_BEGIN")
    println("{")
    println("  \"schema\": \"reframe4s-basinbridge-performance-probe-v1\",")
    println("  \"platform\": \"scalajs-node\",")
    println(s"  \"side\": ${setup.side},")
    println(s"  \"voxels\": ${setup.grid.nVoxels},")
    println(s"  \"warmups\": $warmups,")
    println(s"  \"samples\": $samples,")
    printMeasurement("nativeRound", native, trailingComma = true)
    printMeasurement("oracleRound", oracle, trailingComma = false)
    println("}")
    println("BASIN_BRIDGE_PERFORMANCE_JS_JSON_END")

  private def measure(warmups: Int, samples: Int)(operation: => Double): Measurement =
    var checksum = 0.0
    var index = 0
    while index < warmups do
      checksum = operation
      index += 1
    val millis = Array.ofDim[Double](samples)
    index = 0
    while index < samples do
      val started = js.Date.now()
      checksum = operation
      millis(index) = js.Date.now() - started
      index += 1
    val sorted = millis.sorted
    Measurement(percentile(sorted, 0.5), percentile(sorted, 0.95), checksum)

  private def percentile(values: Array[Double], probability: Double): Double =
    val index = math.min(values.length - 1, math.ceil(probability * values.length.toDouble).toInt - 1)
    values(math.max(0, index))

  private def printMeasurement(name: String, measurement: Measurement, trailingComma: Boolean): Unit =
    println(s"  \"$name\": {")
    println(s"    \"medianMillis\": ${measurement.medianMillis},")
    println(s"    \"p95Millis\": ${measurement.p95Millis},")
    println(s"    \"checksum\": ${measurement.checksum}")
    println(if trailingComma then "  }," else "  }")
