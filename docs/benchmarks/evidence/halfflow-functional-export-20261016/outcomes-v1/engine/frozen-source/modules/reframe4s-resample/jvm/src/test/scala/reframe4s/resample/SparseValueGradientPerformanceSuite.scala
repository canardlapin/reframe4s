package reframe4s.resample

import java.lang.management.ManagementFactory

import com.sun.management.ThreadMXBean
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid
import ravel.DType.given
import ravel.NDArray

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

final class SparseValueGradientPerformanceSuite extends munit.FunSuite:
  test("hot batch allocation is independent of sampled point count"):
    val fixture = new PerformanceFixture
    val small = new Batch(count = 512, fixture)
    val large = new Batch(count = 32768, fixture)
    val repetitions = 4

    var warmup = 0
    while warmup < 8 do
      small.run(repetitions)
      large.run(repetitions)
      warmup += 1

    val (smallChecksum, smallAllocated) =
      measuredAllocation(small.run(repetitions))
    val (largeChecksum, largeAllocated) =
      measuredAllocation(large.run(repetitions))

    assert(smallChecksum.isFinite)
    assert(largeChecksum.isFinite)
    assert(
      largeAllocated <= smallAllocated + 4096L,
      s"sparse sampler allocation scaled with count: small=$smallAllocated B, large=$largeAllocated B"
    )
    assert(
      largeAllocated <= 8192L,
      s"sparse sampler hot batch allocated $largeAllocated B"
    )

    val timings = Vector.tabulate(9) { _ =>
      val started = System.nanoTime()
      large.run(repetitions)
      System.nanoTime() - started
    }.sorted
    val medianNanos = timings(timings.size / 2)
    val samples = large.count.toLong * repetitions.toLong
    val megaSamplesPerSecond = samples.toDouble / medianNanos.toDouble * 1000.0
    val workspacePayloadBytes =
      large.count.toLong * (4L * java.lang.Double.BYTES + 1L)
    val coordinatePayloadBytes =
      large.count.toLong * 3L * java.lang.Double.BYTES
    println(
      f"FA-L04 sparse fused sampler: count=${large.count}%d, repetitions=$repetitions%d, allocated=$largeAllocated%d B, workspacePayload=$workspacePayloadBytes%d B, coordinatePayload=$coordinatePayloadBytes%d B, median=$medianNanos%d ns, throughput=$megaSamplesPerSecond%.3f MSamples/s, checksum=$largeChecksum%.12f"
    )
    assert(
      megaSamplesPerSecond >= 0.05,
      f"unexpectedly low fused-sampling throughput: $megaSamplesPerSecond%.3f MSamples/s"
    )

  test("one immutable sampler is safe with separate concurrent buffers"):
    val fixture = new PerformanceFixture
    val first = new Batch(4096, fixture)
    val second = new Batch(4096, fixture)
    val expectedFirst = first.run(2)
    val expectedSecond = second.run(2)
    val futures = Vector(
      Future(first.run(2)),
      Future(second.run(2))
    )
    val actual = Await.result(Future.sequence(futures), 30.seconds)

    assertEqualsDouble(actual(0), expectedFirst, 0.0)
    assertEqualsDouble(actual(1), expectedSecond, 0.0)
    assertEquals(first.output.counters.rejectedSamples, 0L)
    assertEquals(second.output.counters.rejectedSamples, 0L)

  private final class PerformanceFixture:
    private val extent = 40
    val frame = geometry(Frame.named[D3]("sparse-performance"))
    private val grid = geometry(
      Grid.in(frame)(
        Vector(extent, extent, extent),
        Affine.identity[D3]
      )
    )
    val image = sampled(
      Sampled.continuous(
        grid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](extent, extent, extent) { (i, j, k) =>
          math.sin(i * 0.07) +
            math.cos(j * 0.09) +
            math.sin(k * 0.05) +
            i * j * 0.0003
        }
      )
    )
    val sampler = resampling(LinearValueGradientSampler3.compile(image))

  private final class Batch(
      val count: Int,
      fixture: PerformanceFixture
  ):
    private val x = Array.tabulate(count)(index => 0.1 + index % 39)
    private val y = Array.tabulate(count)(index => 0.2 + (index * 7) % 39)
    private val z = Array.tabulate(count)(index => 0.3 + (index * 13) % 39)
    val output = resampling(SparseValueGradientBuffer3.create(count))

    def run(repetitions: Int): Double =
      var repetition = 0
      var checksum = 0.0
      while repetition < repetitions do
        fixture.sampler.sampleFullSupport(x, y, z, count, output) match
          case Left(error) => fail(error.message)
          case Right(()) =>
            var index = 0
            while index < count do
              checksum +=
                output.values(index) +
                  output.gradientX(index) * 0.1 +
                  output.gradientY(index) * 0.01 +
                  output.gradientZ(index) * 0.001
              index += 1
        repetition += 1
      checksum

  private def measuredAllocation[A](value: => A): (A, Long) =
    val allocationBean =
      ManagementFactory.getThreadMXBean match
        case bean: ThreadMXBean if bean.isThreadAllocatedMemorySupported =>
          if !bean.isThreadAllocatedMemoryEnabled then
            bean.setThreadAllocatedMemoryEnabled(true)
          bean
        case _ =>
          fail("this JVM does not expose per-thread allocation accounting")
    val threadId = Thread.currentThread().threadId()
    val before = allocationBean.getThreadAllocatedBytes(threadId)
    val result = value
    val allocated = allocationBean.getThreadAllocatedBytes(threadId) - before
    result -> allocated

  private def geometry[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def sampled[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def resampling[A](result: Either[ResamplingError, A]): A =
    result.fold(error => fail(error.message), identity)
