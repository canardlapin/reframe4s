package reframe4s.flashalign.benchmark

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import scala.compiletime.uninitialized

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class FlashalignLinearBenchmark:
  @Param(Array("31"))
  var side: Int = 0

  private var workload: LinearBenchmarkWorkload = uninitialized

  @Setup
  def setup(): Unit =
    workload = LinearBenchmarkWorkload.create(side)

  @Benchmark
  def rigidPreparation(): Double = workload.prepareRigid()

  @Benchmark
  def affinePreparation(): Double = workload.prepareAffine()

  @Benchmark
  def rigidWarmOptimizeAndAudit(): Double = workload.warmRigid().checksum

  @Benchmark
  def affineWarmOptimizeAndAudit(): Double = workload.warmAffine().checksum

  @Benchmark
  def rigidColdComplete(): Double = workload.coldRigid().checksum

  @Benchmark
  def affineColdComplete(): Double = workload.coldAffine().checksum
