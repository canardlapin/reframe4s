package reframe4s.motion

import java.lang.management.ManagementFactory
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

import com.sun.management.ThreadMXBean
import image4s.Axis
import image4s.AxisKind
import image4s.BoundaryPolicy
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.resample.Interpolation

final class MotionPerformanceSuite extends munit.FunSuite:
  test(
    "pair estimation has shape-independent allocation and immutable concurrency"
  ):
    val small = new EstimatorFixture(9, "small")
    val large = new EstimatorFixture(21, "large")

    var warmup = 0
    while warmup < 5 do
      small.run()
      large.run()
      warmup += 1

    val (_, smallAllocated) = measuredAllocation(small.run())
    val (measured, largeAllocated, observedPeakDelta) =
      measuredAllocationAndPeak(large.run())

    assert(
      largeAllocated <= smallAllocated + 128L * 1024L,
      s"pair-estimator allocation scaled with voxel count: " +
        s"small=$smallAllocated B, large=$largeAllocated B"
    )
    assert(
      largeAllocated <= 2L * 1024L * 1024L,
      s"pair-estimator fixed allocation exceeded 2 MiB: $largeAllocated B"
    )

    val timings =
      Vector.tabulate(20) { _ =>
        val started = System.nanoTime()
        large.run()
        System.nanoTime() - started
      }.sorted
    val medianNanos = timings(timings.size / 2)
    val p95Nanos = timings(math.ceil(timings.size * 0.95).toInt - 1)
    val objectiveEvaluations =
      measured.report.attempts.toLong +
        measured.report.acceptedSteps.toLong +
        1L
    val evaluatedVoxels =
      large.voxelCount * objectiveEvaluations
    val megaVoxelsPerSecond =
      evaluatedVoxels.toDouble / medianNanos.toDouble * 1000.0

    val concurrent =
      Await.result(
        Future.traverse(0 until 4)(_ => Future(large.run())),
        30.seconds
      )
    val expectedChecksum = estimateChecksum(measured)
    concurrent.foreach(result =>
      assertEqualsDouble(
        estimateChecksum(result),
        expectedChecksum,
        0.0
      )
    )

    assert(
      megaVoxelsPerSecond >= 0.05,
      f"unexpectedly low pair-objective throughput: " +
        f"$megaVoxelsPerSecond%.3f MVox/s"
    )
    println(
      f"MIG-414 pair JVM baseline: voxels=${large.voxelCount}%d, " +
        f"evaluations=$objectiveEvaluations%d, " +
        f"smallAllocated=$smallAllocated%d B, " +
        f"largeAllocated=$largeAllocated%d B, " +
        f"observedPeakHeapDelta=$observedPeakDelta%d B, " +
        f"median=$medianNanos%d ns, p95=$p95Nanos%d ns, " +
        f"throughput=$megaVoxelsPerSecond%.3f MVox/s, " +
        f"checksum=$expectedChecksum%.12f, " +
        s"peakMethod=runtime-spin-sampler, " +
        s"revision=$evidenceRevision, environment=$environment"
    )

  test(
    "motion application allocates only final value and validity outputs"
  ):
    val fixture = new ApplicationFixture(48, 32, 24, 4)
    val compiled = fixture.compiled

    var warmup = 0
    while warmup < 5 do
      motion(compiled.run(compiled.newWorkspace()))
      warmup += 1

    val (measured, allocatedBytes, observedPeakDelta) =
      measuredAllocationAndPeak(
        motion(compiled.run(compiled.newWorkspace()))
      )
    val outputPayloadBytes =
      fixture.sampleCount * java.lang.Double.BYTES.toLong * 2L
    val fixedOverheadBytes = allocatedBytes - outputPayloadBytes

    assertEquals(compiled.structure.materializedCoordinateCount, 0)
    assert(
      fixedOverheadBytes <= 256L * 1024L,
      s"motion application allocated $fixedOverheadBytes bytes beyond " +
        s"its two final output buffers ($allocatedBytes total)"
    )

    val timings =
      Vector.tabulate(20) { _ =>
        val started = System.nanoTime()
        motion(compiled.run(compiled.newWorkspace()))
        System.nanoTime() - started
      }.sorted
    val medianNanos = timings(timings.size / 2)
    val p95Nanos = timings(math.ceil(timings.size * 0.95).toInt - 1)
    val megaSamplesPerSecond =
      fixture.sampleCount.toDouble / medianNanos.toDouble * 1000.0

    val concurrent =
      Await.result(
        Future.traverse(0 until 4)(_ =>
          Future(motion(compiled.run(compiled.newWorkspace())))
        ),
        30.seconds
      )
    val expectedChecksum = applicationChecksum(measured)
    val valueChecksum = measured.image.data.iterator.sum
    val validityChecksum = measured.validityWeights.data.iterator.sum
    concurrent.foreach(result =>
      assertEqualsDouble(
        applicationChecksum(result),
        expectedChecksum,
        0.0
      )
    )

    assert(
      megaSamplesPerSecond >= 0.05,
      f"unexpectedly low application throughput: " +
        f"$megaSamplesPerSecond%.3f MSamples/s"
    )
    println(
      f"MIG-414 application JVM baseline: samples=${fixture.sampleCount}%d, " +
        f"allocated=$allocatedBytes%d B, payload=$outputPayloadBytes%d B, " +
        f"fixedOverhead=$fixedOverheadBytes%d B, " +
        f"observedPeakHeapDelta=$observedPeakDelta%d B, " +
        f"median=$medianNanos%d ns, p95=$p95Nanos%d ns, " +
        f"throughput=$megaSamplesPerSecond%.3f MSamples/s, " +
        f"valueChecksum=$valueChecksum%.12f, " +
        f"validityChecksum=$validityChecksum%.12f, " +
        s"peakMethod=runtime-spin-sampler, " +
        s"revision=$evidenceRevision, environment=$environment"
    )

  private final class EstimatorFixture(
      extent: Int,
      suffix: String
  ):
    val movingFrame =
      geometry(Frame.named[D3](s"performance-moving-$suffix"))
    val fixedFrame =
      geometry(Frame.named[D3](s"performance-fixed-$suffix"))
    private val shape = Vector(extent, extent, extent)
    val voxelCount: Long =
      extent.toLong * extent.toLong * extent.toLong
    private val movingGrid =
      geometry(Grid.in[D3](movingFrame)(shape, Affine.identity[D3]))
    private val fixedGrid =
      geometry(Grid.in[D3](fixedFrame)(shape, Affine.identity[D3]))
    private def field(x: Double, y: Double, z: Double): Double =
      math.exp(
        -(x * x / 17.0 + y * y / 11.0 + z * z / 7.0)
      ) + 0.25 * math.exp(
        -(
          (x - 2.0) * (x - 2.0) +
            (y + 1.0) * (y + 1.0) +
            (z - 3.0) * (z - 3.0)
        ) / 5.0
      )
    private val fixedData =
      NDArray.tabulate[Double](extent, extent, extent)((i, j, k) =>
        val x = i.toDouble - extent.toDouble * 0.37
        val y = j.toDouble - extent.toDouble * 0.51
        val z = k.toDouble - extent.toDouble * 0.43
        field(x, y, z)
      )
    private val movingData =
      NDArray.tabulate[Double](extent, extent, extent)((i, j, k) =>
        val x = i.toDouble - extent.toDouble * 0.37
        val y = j.toDouble - extent.toDouble * 0.51
        val z = k.toDouble - extent.toDouble * 0.43
        field(x + 0.8, y - 0.4, z + 0.2)
      )
    private val moving =
      image(Sampled.continuous(movingGrid, NonSpatialAxes.empty, movingData))
    private val fixed =
      image(Sampled.continuous(fixedGrid, NonSpatialAxes.empty, fixedData))
    private val control =
      motion(
        RigidOptimizerControl.create(
          maximumIterations = 5,
          huberThreshold = 1.5,
          initialTranslationStepMm = 1.0,
          initialRotationStepRadians = math.toRadians(1.0),
          minimumTranslationStepMm = 0.125,
          minimumRotationStepRadians = math.toRadians(0.125),
          objectiveTolerance = 0.0,
          minimumOverlap = 0.25
        )
      )
    private val estimator =
      motion(
        CompiledRigidPairEstimator.compile(moving, fixed, control)
      )
    private val identity =
      RigidPose.fromMovingToFixed(
        rigid(
          Rigid3.translation(movingFrame, fixedFrame)(0.0, 0.0, 0.0)
        )
      )

    def run(): RigidPairEstimate[movingFrame.type, fixedFrame.type] =
      motion(estimator.runFrom(identity, estimator.newWorkspace()))

  private final class ApplicationFixture(
      nx: Int,
      ny: Int,
      nz: Int,
      nt: Int
  ):
    val frame = geometry(Frame.named[D3]("performance-application"))
    private val grid =
      geometry(
        Grid.in[D3](frame)(
          Vector(nx, ny, nz),
          Affine.identity[D3]
        )
      )
    private val times =
      motion(TimeAxis.create(Vector.tabulate(nt)(_.toDouble)))
    private val timeAxis =
      image(Axis.create("time", nt, AxisKind.Time))
    private val axes =
      image(NonSpatialAxes.from(Vector(timeAxis)))
    private val data =
      NDArray.tabulate[Double](nx, ny, nz, nt)((i, j, k, time) =>
        i.toDouble * 0.125 +
          j.toDouble * 0.25 +
          k.toDouble * 0.5 +
          time.toDouble
      )
    private val series =
      image(Sampled.continuous(grid, axes, data))
    private val samples =
      motion(TimedScalarSamples.view(series, times))
    private val identity =
      RigidPose.fromMovingToFixed(Rigid3.identity[frame.type](frame))
    private val poses =
      motion(PoseSeries.create(times, Vector.fill(nt)(identity)))
    private val trajectory = PoseTrajectory.fromSeries(poses)
    val sampleCount: Long =
      nx.toLong * ny.toLong * nz.toLong * nt.toLong
    val compiled =
      motion(
        CompiledMotionApplication.compile(
          samples,
          grid,
          trajectory,
          schedule = AcquisitionSchedule.Volume,
          interpolation = Interpolation.Linear,
          boundary = BoundaryPolicy.Reject
        )
      )

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
        "reframe4s-motion-heap-probe"
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

  private def estimateChecksum(
      result: RigidPairEstimate[?, ?]
  ): Double =
    result.pose.movingToFixed.operator.rowMajor.sum +
      result.report.finalObjective +
      result.overlap +
      result.supportedVoxels.toDouble

  private def applicationChecksum(
      result: MotionApplicationResult[?]
  ): Double =
    result.image.data.iterator.sum +
      result.validityWeights.data.iterator.sum

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

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def image[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def motion[A](value: Either[MotionError, A]): A =
    value.fold(error => fail(error.message), identity)
