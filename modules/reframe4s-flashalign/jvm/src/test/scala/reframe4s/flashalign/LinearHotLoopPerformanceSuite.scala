package reframe4s.flashalign

import java.lang.management.ManagementFactory

import com.sun.management.ThreadMXBean

final class LinearHotLoopPerformanceSuite extends munit.FunSuite:
  test("projected-patch linearization and loss kernels do not allocate per patch"):
    val fixture = new PatchKernelFixture
    val smallIterations = 2000
    val largeIterations = 20000

    var warmup = 0
    while warmup < 6 do
      fixture.run(smallIterations)
      fixture.run(largeIterations)
      warmup += 1

    val (smallChecksum, smallAllocated) =
      measuredAllocation(fixture.run(smallIterations))
    val (largeChecksum, largeAllocated) =
      measuredAllocation(fixture.run(largeIterations))

    assert(smallChecksum.isFinite)
    assert(largeChecksum.isFinite)
    assert(
      largeAllocated <= smallAllocated + 8L * 1024L,
      s"hot-loop allocation scaled with patch count: " +
        s"$smallIterations/$smallAllocated B versus " +
        s"$largeIterations/$largeAllocated B"
    )
    assert(
      largeAllocated <= 16L * 1024L,
      s"projected-patch hot loops allocated more than 16 KiB: " +
        s"$largeAllocated B"
    )

    println(
      s"FA-L22 projected-patch allocation gate: " +
        s"smallIterations=$smallIterations, " +
        s"smallAllocated=$smallAllocated B, " +
        s"largeIterations=$largeIterations, " +
        s"largeAllocated=$largeAllocated B, " +
        f"checksum=$largeChecksum%.12f"
    )

  private final class PatchKernelFixture:
    private val config = patch(
      PatchObjectiveConfig.create(
        positivePolarityPrior = 0.65,
        tau = 0.7,
        outlierFloor = 0.03,
        minimumContrastEnergy = 1e-20
      )
    )
    private val movingValues = Array.tabulate(27) { index =>
      math.sin(index.toDouble * 0.31 + 0.19) +
        0.23 * math.cos(index.toDouble * 0.17 - 0.19) +
        index.toDouble * 0.012
    }
    private val fixedValues = Array.tabulate(27) { index =>
      math.sin(index.toDouble * 0.31 - 0.27) +
        0.23 * math.cos(index.toDouble * 0.17 + 0.27) +
        index.toDouble * 0.012
    }
    private val derivatives = Array.tabulate(27 * 12) { flatIndex =>
      val sample = flatIndex / 12
      val parameter = flatIndex % 12
      math.sin((sample + 1).toDouble * (parameter + 2).toDouble * 0.059) +
        0.17 * math.cos(sample.toDouble * 0.11 - parameter.toDouble * 0.37)
    }
    private val moving = patch(PatchObjective.prepareMoving(movingValues, config))
    private val samples = new PatchSampleSource:
      val sampleCount: Int = fixedValues.length
      val parameterCount: Int = 12
      def value(sample: Int): Double = fixedValues(sample)
      def derivative(sample: Int, parameter: Int): Double =
        derivatives(sample * parameterCount + parameter)
    private val linearization = linear(PatchLinearizationWorkspace.create(12))
    private val posterior = PatchObjectiveScratch.create

    def run(iterations: Int): Double =
      var checksum = 0.0
      var iteration = 0
      while iteration < iterations do
        val statisticsError = PatchLinearization.computeInto(
          moving,
          samples,
          config.minimumContrastEnergy,
          linearization
        )
        if statisticsError != null then
          throw new IllegalStateException(
            statisticsError.asInstanceOf[PatchLinearizationError].message
          )
        val posteriorError = PatchObjective.evaluateCorrelationInto(
          linearization.result.correlation,
          config,
          posterior
        )
        if posteriorError != null then
          throw new IllegalStateException(
            posteriorError.asInstanceOf[PatchObjectiveError].message
          )
        val lossError = PatchObjective.evaluateInto(
          moving,
          fixedValues,
          completeInterpolationSupport = true,
          config,
          posterior
        )
        if lossError != null then
          throw new IllegalStateException(
            lossError.asInstanceOf[PatchObjectiveError].message
          )
        checksum +=
          linearization.result.jtu(0) +
            linearization.result.jtjUpper(0) +
            posterior.loss
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
    val allocated = allocationBean.getThreadAllocatedBytes(threadId) - before
    result -> allocated

  private def patch[A](result: Either[PatchObjectiveError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def linear[A](result: Either[PatchLinearizationError, A]): A =
    result.fold(error => fail(error.message), identity)
