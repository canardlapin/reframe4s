package reframe4s.laws

import java.lang.management.ManagementFactory
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

import com.sun.management.ThreadMXBean
import image4s.BoundaryPolicy
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import image4s.geometry.Affine
import image4s.geometry.D2
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import reframe4s.lie.FramedAffine
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingError
import reframe4s.resample.ResamplingPlan
import reframe4s.resample.ResamplingResult

final class ResamplingPerformanceSuite extends munit.FunSuite:
  test(
    "affine execution allocates only output-sized buffers and is plan-concurrent"
  ):
    val extent = 192
    val frame = geometryRight(Frame.named[D2]("allocation-baseline"))
    val grid =
      geometryRight(
        Grid.in(frame)(
          Vector(extent, extent),
          Affine.identity[D2]
        )
      )
    val data =
      NDArray.tabulate[Double](extent, extent)((i, j) =>
        i.toDouble * 0.25 + j.toDouble * 0.5
      )
    val source =
      imageRight(Sampled.continuous(grid, NonSpatialAxes.empty, data))
    val plan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          grid,
          FramedAffine.identity(frame),
          Interpolation.Linear,
          BoundaryPolicy.Reject
        )
      )

    var warmup = 0
    while warmup < 5 do
      resamplingRight(plan.run(plan.newWorkspace()))
      warmup += 1

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
    val measured = resamplingRight(plan.run(plan.newWorkspace()))
    val allocatedBytes =
      allocationBean.getThreadAllocatedBytes(threadId) - before
    val voxels = extent.toLong * extent.toLong
    val outputPayloadBytes = voxels * java.lang.Double.BYTES.toLong * 2L
    val fixedOverheadBytes = allocatedBytes - outputPayloadBytes

    assertEquals(plan.structure.materializedCoordinateCount, 0)
    assert(
      fixedOverheadBytes <= 128L * 1024L,
      s"resampling allocated $fixedOverheadBytes bytes beyond its two " +
        s"output-sized buffers ($allocatedBytes total)"
    )

    val timings =
      Vector.tabulate(7) { _ =>
        val started = System.nanoTime()
        resamplingRight(plan.run(plan.newWorkspace()))
        System.nanoTime() - started
      }.sorted
    val medianNanos = timings(timings.size / 2)
    val megaVoxelsPerSecond =
      voxels.toDouble / medianNanos.toDouble * 1000.0

    val concurrent =
      Await.result(
        Future.traverse(0 until 4)(_ =>
          Future(resamplingRight(plan.run(plan.newWorkspace())))
        ),
        30.seconds
      )
    val expectedChecksum = checksum(measured, extent)
    concurrent.foreach(result =>
      assertEqualsDouble(checksum(result, extent), expectedChecksum, 0.0)
    )

    assert(
      megaVoxelsPerSecond >= 0.1,
      f"unexpectedly low affine throughput: $megaVoxelsPerSecond%.3f MVox/s"
    )
    println(
      f"AC-026 JVM baseline: voxels=$voxels%d, " +
        f"allocated=$allocatedBytes%d B, payload=$outputPayloadBytes%d B, " +
        f"fixedOverhead=$fixedOverheadBytes%d B, " +
        f"median=$medianNanos%d ns, throughput=$megaVoxelsPerSecond%.3f MVox/s"
    )

  test(
    "Lanczos-5 scan has shape-independent allocation and bounded peak memory"
  ):
    val small = new LanczosScanFixture(4, "small")
    val large = new LanczosScanFixture(10, "large")

    var warmup = 0
    while warmup < 3 do
      small.run()
      large.run()
      warmup += 1

    val (_, smallAllocated) = measuredAllocation(small.run())
    val (checksum, largeAllocated, observedPeakDelta) =
      measuredAllocationAndPeak(large.run())

    assert(
      largeAllocated <= smallAllocated + 2048L,
      s"Lanczos scan allocation scaled with output size: " +
        s"small=$smallAllocated B, large=$largeAllocated B"
    )
    assert(
      largeAllocated <= 16L * 1024L,
      s"Lanczos scan fixed allocation exceeded 16 KiB: $largeAllocated B"
    )
    assertEquals(large.materializedCoordinateCount, 0)

    val timings =
      Vector.tabulate(7) { _ =>
        val started = System.nanoTime()
        large.run()
        System.nanoTime() - started
      }.sorted
    val medianNanos = timings(timings.size / 2)
    val p95Nanos = timings(math.ceil(timings.size * 0.95).toInt - 1)
    val megaVoxelsPerSecond =
      large.voxelCount.toDouble / medianNanos.toDouble * 1000.0

    val concurrent =
      Await.result(
        Future.traverse(0 until 4)(_ => Future(large.run())),
        30.seconds
      )
    concurrent.foreach(value =>
      assertEqualsDouble(value, checksum, 0.0)
    )
    assert(
      megaVoxelsPerSecond >= 0.001,
      f"unexpectedly low Lanczos throughput: $megaVoxelsPerSecond%.6f MVox/s"
    )
    println(
      f"MIG-424 Lanczos-5 JVM baseline: voxels=${large.voxelCount}%d, " +
        f"smallAllocated=$smallAllocated%d B, " +
        f"largeAllocated=$largeAllocated%d B, " +
        f"observedPeakHeapDelta=$observedPeakDelta%d B, " +
        f"median=$medianNanos%d ns, p95=$p95Nanos%d ns, " +
        f"throughput=$megaVoxelsPerSecond%.6f MVox/s, " +
        f"checksum=$checksum%.12f, " +
        s"peakMethod=runtime-spin-sampler, " +
        s"revision=$evidenceRevision, environment=$environment"
    )

  private final class LanczosScanFixture(
      targetExtent: Int,
      suffix: String
  ):
    private val sourceFrame =
      geometryRight(Frame.named[D3](s"lanczos-perf-source-$suffix"))
    private val targetFrame =
      geometryRight(Frame.named[D3](s"lanczos-perf-target-$suffix"))
    private val sourceGrid =
      geometryRight(
        Grid.in(sourceFrame)(
          Vector(24, 24, 24),
          Affine.identity[D3]
        )
      )
    private val targetGrid =
      geometryRight(
        Grid.in(targetFrame)(
          Vector(targetExtent, targetExtent, targetExtent),
          geometryRight(
            Affine.fromOriginSpacingDirection[D3](
              origin = Vector(6.25, 6.5, 6.75),
              spacing = Vector(1.0, 1.0, 1.0),
              directionRowMajor = Vector(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0
              )
            )
          )
        )
      )
    private val source =
      imageRight(
        Sampled.continuous(
          sourceGrid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](24, 24, 24)((i, j, k) =>
            math.sin(i.toDouble * 0.11) +
              math.cos(j.toDouble * 0.09) +
              k.toDouble * 0.015
          )
        )
      )
    private val plan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          FramedAffine.between(targetFrame, sourceFrame)(
            Affine.identity[D3]
          ),
          Interpolation.Lanczos5,
          BoundaryPolicy.Reject
        )
      )
    val materializedCoordinateCount: Int =
      plan.structure.materializedCoordinateCount
    val voxelCount: Long =
      targetExtent.toLong * targetExtent.toLong * targetExtent.toLong

    def run(): Double =
      val sink = new ChecksumSink
      resamplingRight(plan.scan(plan.newWorkspace(), sink))
      sink.total

  private final class ChecksumSink
      extends reframe4s.resample.ResamplingSink:
    var total = 0.0

    def accept(
        outputLinearIndex: Int,
        value: Double,
        validityWeight: Double
    ): Unit =
      total +=
        value * (outputLinearIndex + 1).toDouble +
          validityWeight * 0.125

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
    val allocated =
      allocationBean.getThreadAllocatedBytes(threadId) - before
    result -> allocated

  private def measuredAllocationAndPeak[A](
      value: => A
  ): (A, Long, Long) =
    System.gc()
    Thread.sleep(100L)
    val probe = new PeakHeapProbe
    probe.start()
    Thread.sleep(10L)
    val baseline = runtimeHeapUsed()
    probe.reset(baseline)
    val measured =
      try measuredAllocation(value)
      finally probe.stop()
    val peakDelta =
      math.max(0L, probe.peakUsed - baseline)
    (measured._1, measured._2, peakDelta)

  private final class PeakHeapProbe:
    @volatile private var active = true
    @volatile private var peak = 0L
    private val sampler =
      new Thread(
        () =>
          while active do
            val current = runtimeHeapUsed()
            if current > peak then peak = current
            Thread.onSpinWait(),
        "reframe4s-lanczos-heap-probe"
      )

    def start(): Unit =
      sampler.setDaemon(true)
      sampler.start()

    def reset(baseline: Long): Unit =
      peak = baseline

    def stop(): Unit =
      active = false
      sampler.join()
      val finalUsage = runtimeHeapUsed()
      if finalUsage > peak then peak = finalUsage

    def peakUsed: Long = peak

  private def runtimeHeapUsed(): Long =
    val runtime = Runtime.getRuntime
    runtime.totalMemory() - runtime.freeMemory()

  private val evidenceRevision: String =
    sys.env.getOrElse(
      "REFRAME4S_EVIDENCE_REVISION",
      "uncommitted-worktree"
    )

  private val environment: String =
    s"${System.getProperty("java.vm.name")} " +
      s"${System.getProperty("java.version")}; " +
      s"${System.getProperty("os.name")} " +
      s"${System.getProperty("os.arch")}"

  private def checksum[
      F <: Frame[D2],
      Sem
  ](
      result: ResamplingResult[F, D2, Sem],
      extent: Int
  ): Double =
    var total = 0.0
    var i = 0
    while i < extent do
      var j = 0
      while j < extent do
        total += result.image.data(i, j)
        j += 1
      i += 1
    total

  private def geometryRight[A](value: Either[GeometryError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def imageRight[A](value: Either[ImageError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def resamplingRight[A](
      value: Either[ResamplingError, A]
  ): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)
