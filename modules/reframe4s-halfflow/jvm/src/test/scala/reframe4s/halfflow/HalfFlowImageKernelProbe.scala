package reframe4s.halfflow

import java.lang.management.ManagementFactory
import ravel.MutableNDArray as MutableRavelArray
import ravel.NDArray as RavelArray
import ravel.Rank
import ravel.Shape
import reframe4s.halfflow.internal.*

/** Standalone diagnostic until the isolated JMH configuration can be added.
  *
  * Run with:
  *
  * `sbt 'reframe4sHalfflowJVM/Test/runMain reframe4s.halfflow.HalfFlowImageKernelProbe'`
  */
object HalfFlowImageKernelProbe:

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
    val side = if args.nonEmpty then args(0).toInt else 48
    val warmups = if args.length >= 2 then args(1).toInt else 4
    val samples = if args.length >= 3 then args(2).toInt else 9
    require(side >= 4, "side must be at least four")
    require(warmups >= 1, "warmups must be positive")
    require(samples >= 3, "samples must be at least three")

    val grid = GridSpec.identity(Vector(side, side, side))
    val left = coordinateField(grid) { (x, y, z) =>
      (x + 0.25, y + 0.125, z + 0.375)
    }
    val right = coordinateField(grid) { (x, y, z) =>
      (
        1.01 * x + 0.015 * y + 0.1,
        0.99 * y + 0.01 * z - 0.2,
        1.005 * z + 0.02 * x + 0.05
      )
    }
    val points =
      Vector.tabulate(grid.nVoxels) { i =>
        Vector(
          left.linearComponent(i, 0),
          left.linearComponent(i, 1),
          left.linearComponent(i, 2)
        )
      }
    val referencePlan =
      DenseFieldInterpolationPlan.make(grid, points, Resample.Method.Linear)
        .fold(err => throw new IllegalArgumentException(err.message), plan => plan)
    val destination = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    val destinationValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    val rightSampler = DenseFieldSampler(right.grid)
    HalfFlowKernels.composePullInto(
      left,
      right,
      destination,
      destinationValid,
      rightSampler,
      FieldValidity.All,
      FieldValidity.All
    )
    var referenceResult = Vector.empty[Vector[Double]]

    val preparedReference = measure(warmups, samples) {
      referenceResult =
        referencePlan.sample(right.values, DenseFieldOutside.QueryPoint)
          .fold(err => throw new IllegalArgumentException(err.message), values => values)
      checksumReference(referenceResult, destinationValid)
    }
    val dynamicReference = measure(warmups, samples) {
      val plan =
        DenseFieldInterpolationPlan.make(grid, points, Resample.Method.Linear)
          .fold(err => throw new IllegalArgumentException(err.message), value => value)
      referenceResult =
        plan.sample(right.values, DenseFieldOutside.QueryPoint)
          .fold(err => throw new IllegalArgumentException(err.message), values => values)
      checksumReference(referenceResult, destinationValid)
    }
    val primitive = measure(warmups, samples) {
      HalfFlowKernels.composePullInto(
        left,
        right,
        destination,
        destinationValid,
        rightSampler,
        FieldValidity.All,
        FieldValidity.All
      )
      checksumPrimitive(destination, destinationValid, grid.nVoxels)
    }
    val selfDestination = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    val selfValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    HalfFlowKernels.composePullInto(
      left,
      left,
      selfDestination,
      selfValid,
      DenseFieldSampler(grid),
      FieldValidity.All,
      FieldValidity.All,
      CoordinateMapOutside.Identity
    )
    val workspaceSource =
      MutableRavelArray.zeros[Double, Rank[1]](
        Shape(grid.nVoxels * 3)
      )
    workspaceSource.assign(left.flatValues)
    val workspaceDestination =
      MutableRavelArray.zeros[Double, Rank[1]](
        Shape(grid.nVoxels * 3)
      )
    val workspaceSourceValid =
      PrimitiveBuffers.fillConst[Boolean](grid.nVoxels, true)
    val workspaceDestinationValid =
      PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    val workspaceSampler = DenseFieldSampler(grid)
    val ravelWorkspace = measure(warmups, samples) {
      HalfFlowKernels.composeSelfPullInto(
        grid,
        workspaceSource,
        workspaceSourceValid,
        workspaceDestination,
        workspaceDestinationValid,
        workspaceSampler,
        CoordinateMapOutside.Identity
      )
      checksumMutable(
        workspaceDestination,
        workspaceDestinationValid,
        grid
      )
    }

    val maxError = maximumValidError(referenceResult, destination, destinationValid, grid.nVoxels)
    val workspaceMaxError =
      maximumMutableError(
        selfDestination,
        selfValid,
        workspaceDestination,
        workspaceDestinationValid,
        grid
      )
    val validCount = countValid(destinationValid)
    val dynamicSpeedup = dynamicReference.medianNanos.toDouble / primitive.medianNanos.toDouble
    val preparedSpeedup = preparedReference.medianNanos.toDouble / primitive.medianNanos.toDouble
    val dynamicAllocationRatio =
      if primitive.medianAllocatedBytes > 0 then
        dynamicReference.medianAllocatedBytes.toDouble / primitive.medianAllocatedBytes.toDouble
      else Double.PositiveInfinity
    val preparedAllocationRatio =
      if primitive.medianAllocatedBytes > 0 then
        preparedReference.medianAllocatedBytes.toDouble / primitive.medianAllocatedBytes.toDouble
      else Double.PositiveInfinity

    println("HALF_FLOW_IMAGE_KERNEL_PROBE_JSON_BEGIN")
    println("{")
    println("  \"schema\": \"reframe4s-half-flow-image-kernel-probe-v1\",")
    println(s"  \"jvm\": \"${escape(System.getProperty("java.vm.name"))} ${escape(System.getProperty("java.version"))}\",")
    println(s"  \"os\": \"${escape(System.getProperty("os.name"))} ${escape(System.getProperty("os.arch"))}\",")
    println(s"  \"side\": $side,")
    println(s"  \"voxels\": ${grid.nVoxels},")
    println(s"  \"warmups\": $warmups,")
    println(s"  \"samples\": $samples,")
    println(s"  \"validCount\": $validCount,")
    println(s"  \"maximumValidError\": $maxError,")
    println(s"  \"ravelWorkspaceMaximumValidError\": $workspaceMaxError,")
    println(s"  \"dynamicSpeedupMedian\": $dynamicSpeedup,")
    println(s"  \"preparedSpeedupMedian\": $preparedSpeedup,")
    println(s"  \"dynamicAllocationReductionMedian\": $dynamicAllocationRatio,")
    println(s"  \"preparedAllocationReductionMedian\": $preparedAllocationRatio,")
    printMeasurement("referencePlanAndSample", dynamicReference, trailingComma = true)
    printMeasurement("referencePreparedSample", preparedReference, trailingComma = true)
    printMeasurement("primitiveComposeInto", primitive, trailingComma = true)
    printMeasurement("ravelWorkspaceComposeInto", ravelWorkspace, trailingComma = false)
    println("}")
    println("HALF_FLOW_IMAGE_KERNEL_PROBE_JSON_END")

  private def coordinateField(
      grid: GridSpec
  )(
      f: (Double, Double, Double) => (Double, Double, Double)
  ): DenseVectorField =
    val values =
      RavelArray.tabulate[Double](
        grid.shape.x,
        grid.shape.y,
        grid.shape.z,
        3
      ) { (x, y, z, component) =>
          val mapped = f(x.toDouble, y.toDouble, z.toDouble)
          component match
            case 0 => mapped._1
            case 1 => mapped._2
            case _ => mapped._3
      }
    DenseVectorField(
      grid,
      values,
      DenseVectorFieldKind.SourceCoordinates
    )

  private def measure(
      warmups: Int,
      samples: Int
  )(
      operation: => Double
  ): Measurement =
    var checksum = 0.0
    var i = 0
    while i < warmups do
      checksum = operation
      i += 1

    val nanos = Array.ofDim[Long](samples)
    val allocated = Array.ofDim[Long](samples)
    i = 0
    while i < samples do
      val allocatedBefore = currentAllocatedBytes()
      val start = System.nanoTime()
      checksum = operation
      nanos(i) = System.nanoTime() - start
      val allocatedAfter = currentAllocatedBytes()
      allocated(i) =
        if allocatedBefore >= 0L && allocatedAfter >= allocatedBefore then allocatedAfter - allocatedBefore
        else -1L
      i += 1

    val sortedNanos = nanos.sorted
    val measuredAllocations = allocated.filter(_ >= 0L).sorted
    Measurement(
      medianNanos = percentile(sortedNanos, 0.5),
      p95Nanos = percentile(sortedNanos, 0.95),
      medianAllocatedBytes =
        if measuredAllocations.isEmpty then -1L else percentile(measuredAllocations, 0.5),
      checksum = checksum
    )

  private def checksumReference(
      values: Vector[Vector[Double]],
      valid: Array[Boolean]
  ): Double =
    var sum = 0.0
    var i = 0
    while i < values.length do
      if valid(i) then
        val point = values(i)
        val weight = (i % 17 + 1).toDouble
        sum += weight * (point(0) + 0.5 * point(1) + 0.25 * point(2))
      i += 1
    sum

  private def checksumPrimitive(
      values: Array[Double],
      valid: Array[Boolean],
      n: Int
  ): Double =
    var sum = 0.0
    var i = 0
    while i < n do
      if valid(i) then
        val weight = (i % 17 + 1).toDouble
        sum += weight * (values(i) + 0.5 * values(i + n) + 0.25 * values(i + 2 * n))
      i += 1
    sum

  private def checksumMutable(
      values: MutableRavelArray[Double, Rank[1]],
      valid: Array[Boolean],
      grid: GridSpec
  ): Double =
    var sum = 0.0
    var i = 0
    while i < grid.nVoxels do
      if valid(i) then
        val x = i % grid.extentX
        val yz = i / grid.extentX
        val y = yz % grid.extentY
        val z = yz / grid.extentY
        val storageBase =
          3 * (z + grid.extentZ * (y + grid.extentY * x))
        val weight = (i % 17 + 1).toDouble
        sum += weight * (
          values(storageBase) +
            0.5 * values(storageBase + 1) +
            0.25 * values(storageBase + 2)
        )
      i += 1
    sum

  private def maximumMutableError(
      expected: Array[Double],
      expectedValid: Array[Boolean],
      actual: MutableRavelArray[Double, Rank[1]],
      actualValid: Array[Boolean],
      grid: GridSpec
  ): Double =
    val nx = grid.extentX
    val ny = grid.extentY
    val nz = grid.extentZ
    val n = grid.nVoxels
    var maximum = 0.0
    var i = 0
    while i < n do
      require(
        expectedValid(i) == actualValid(i),
        s"workspace validity mismatch at $i"
      )
      if expectedValid(i) then
        val x = i % nx
        val yz = i / nx
        val y = yz % ny
        val z = yz / ny
        val storageBase = 3 * (z + nz * (y + ny * x))
        maximum =
          math.max(maximum, math.abs(expected(i) - actual(storageBase)))
        maximum =
          math.max(maximum, math.abs(expected(i + n) - actual(storageBase + 1)))
        maximum =
          math.max(maximum, math.abs(expected(i + 2 * n) - actual(storageBase + 2)))
      i += 1
    maximum

  private def maximumValidError(
      reference: Vector[Vector[Double]],
      actual: Array[Double],
      valid: Array[Boolean],
      n: Int
  ): Double =
    var maximum = 0.0
    var i = 0
    while i < n do
      if valid(i) then
        maximum = math.max(maximum, math.abs(actual(i) - reference(i)(0)))
        maximum = math.max(maximum, math.abs(actual(i + n) - reference(i)(1)))
        maximum = math.max(maximum, math.abs(actual(i + 2 * n) - reference(i)(2)))
      i += 1
    maximum

  private def countValid(valid: Array[Boolean]): Int =
    var count = 0
    var i = 0
    while i < valid.length do
      if valid(i) then count += 1
      i += 1
    count

  private def currentAllocatedBytes(): Long =
    allocationBean match
      case Some(bean) => bean.getCurrentThreadAllocatedBytes
      case None => -1L

  private def percentile(sorted: Array[Long], probability: Double): Long =
    val index = math.min(sorted.length - 1, math.ceil(probability * sorted.length.toDouble).toInt - 1)
    sorted(math.max(0, index))

  private def printMeasurement(
      name: String,
      measurement: Measurement,
      trailingComma: Boolean
  ): Unit =
    println(s"  \"$name\": {")
    println(s"    \"medianNanos\": ${measurement.medianNanos},")
    println(s"    \"p95Nanos\": ${measurement.p95Nanos},")
    println(s"    \"medianAllocatedBytes\": ${measurement.medianAllocatedBytes},")
    println(s"    \"checksum\": ${measurement.checksum}")
    println(if trailingComma then "  }," else "  }")

  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
