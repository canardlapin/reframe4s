package reframe4s.motion

import java.lang.management.ManagementFactory

import com.sun.management.ThreadMXBean
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

final class RigidOptimizationKernelPerformanceSuite extends munit.FunSuite:
  test("normal-equation accumulation allocation is independent of sample count"):
    val repetitions = 4
    val small = new KernelFixture(extent = 8, suffix = "small")
    val large = new KernelFixture(extent = 32, suffix = "large")

    var warmup = 0
    while warmup < 5 do
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
      largeAllocated <= smallAllocated + 8L * 1024L,
      s"kernel allocation scaled with sample count: " +
        s"small=${small.stencilSize}/$smallAllocated B, " +
        s"large=${large.stencilSize}/$largeAllocated B"
    )
    assert(
      largeAllocated <= 32L * 1024L,
      s"kernel fixed allocation exceeded 32 KiB: $largeAllocated B"
    )

    val timings =
      Vector.tabulate(11) { _ =>
        val started = System.nanoTime()
        large.run(repetitions)
        System.nanoTime() - started
      }.sorted
    val medianNanos = timings(timings.size / 2)
    val evaluatedSamples = large.stencilSize.toLong * repetitions.toLong
    val megaSamplesPerSecond =
      evaluatedSamples.toDouble / medianNanos.toDouble * 1000.0

    assert(
      megaSamplesPerSecond >= 0.05,
      f"unexpectedly low normal-kernel throughput: " +
        f"$megaSamplesPerSecond%.3f MSamples/s"
    )
    println(
      f"MIG-422 normal-kernel JVM baseline: " +
        f"smallSamples=${small.stencilSize}%d, " +
        f"largeSamples=${large.stencilSize}%d, " +
        f"repetitions=$repetitions%d, " +
        f"smallAllocated=$smallAllocated%d B, " +
        f"largeAllocated=$largeAllocated%d B, " +
        f"median=$medianNanos%d ns, " +
        f"throughput=$megaSamplesPerSecond%.3f MSamples/s, " +
        f"checksum=$largeChecksum%.12f, " +
        s"revision=$evidenceRevision, environment=$environment"
    )

  private final class KernelFixture(
      extent: Int,
      suffix: String
  ):
    private val frame =
      geometry(Frame.named[D3](s"kernel-performance-$suffix"))
    private val grid =
      geometry(
        Grid.in[D3](frame)(
          Vector(extent, extent, extent),
          Affine.identity[D3]
        )
      )
    private val data =
      NDArray.tabulate[Double](extent, extent, extent)((i, j, k) =>
        math.sin(i.toDouble * 0.11) +
          math.cos(j.toDouble * 0.07) +
          math.sin(k.toDouble * 0.05) +
          i.toDouble * j.toDouble * 0.0005 +
          j.toDouble * k.toDouble * 0.0003
      )
    private val fixed =
      image(Sampled.continuous(grid, NonSpatialAxes.empty, data))
    private val stencil =
      kernel(CompiledRigidStencil.dense(fixed))
    private val residuals =
      Array.tabulate(stencil.size)(index =>
        math.sin(index.toDouble * 0.013) * 0.75
      )
    private val validity =
      Array.tabulate(stencil.size)(index =>
        if index % 17 == 0 then 0.0 else 1.0
      )
    private val loss = kernel(RigidRobustLoss.huber(0.5))
    private val workspace = RigidNormalWorkspace.create()

    val stencilSize: Int = stencil.size

    def run(repetitions: Int): Double =
      var iteration = 0
      var checksum = 0.0
      while iteration < repetitions do
        RigidNormalKernel.evaluateInto(
          stencil,
          residuals,
          validity,
          loss,
          workspace
        ) match
          case Left(error) => fail(error.message)
          case Right(())   => checksum += workspace.checksum
        iteration += 1
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
    val allocated =
      allocationBean.getThreadAllocatedBytes(threadId) - before
    result -> allocated

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

  private def kernel[A](value: Either[RigidKernelError, A]): A =
    value.fold(error => fail(error.message), identity)
