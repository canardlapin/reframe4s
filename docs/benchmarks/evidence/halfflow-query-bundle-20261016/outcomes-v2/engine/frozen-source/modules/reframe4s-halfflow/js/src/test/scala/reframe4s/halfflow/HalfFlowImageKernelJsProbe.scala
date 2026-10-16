package reframe4s.halfflow

import ravel.MutableNDArray as MutableRavelArray
import ravel.NDArray as RavelArray
import ravel.Rank
import ravel.Shape
import reframe4s.halfflow.internal.*
import scala.scalajs.js

/** Scala.js/Node diagnostic companion to `HalfFlowImageKernelProbe`. */
object HalfFlowImageKernelJsProbe:

  private final case class Measurement(
      medianMillis: Double,
      p95Millis: Double,
      checksum: Double
  )

  /** Explicit accounting for benchmark-owned full-volume construction sites. */
  private final class ConstructionCounter:
    var scalar: Int = 0
    var vector: Int = 0
    var mask: Int = 0
    var scratch: Int = 0

    def scalarBuffer(size: Int): Array[Double] =
      scalar += 1
      PrimitiveBuffers.ofSize[Double](size)

    def vectorBuffer(voxels: Int): Array[Double] =
      vector += 1
      PrimitiveBuffers.ofSize[Double](voxels * 3)

    def maskBuffer(size: Int): Array[Boolean] =
      mask += 1
      PrimitiveBuffers.ofSize[Boolean](size)

    def pyramidWorkspace(size: Int): PyramidWorkspace =
      scratch += 4
      PyramidWorkspace(size)

    def reset(): Unit =
      scalar = 0
      vector = 0
      mask = 0
      scratch = 0

  def main(args: Array[String]): Unit =
    val side = if args.nonEmpty then args(0).toInt else 32
    val warmups = if args.length >= 2 then args(1).toInt else 5
    val samples = if args.length >= 3 then args(2).toInt else 11
    require(side >= 4, "side must be at least four")
    require(warmups >= 1, "warmups must be positive")
    require(samples >= 3, "samples must be at least three")

    val grid = GridSpec.identity(Vector(side, side, side))
    val left = coordinateField(grid) { (x, y, z) =>
      (x + 0.25, y + 0.125, z + 0.375)
    }
    val right = coordinateField(grid) { (x, y, z) =>
      (1.01 * x + 0.015 * y + 0.1, 0.99 * y + 0.01 * z - 0.2, 1.005 * z + 0.02 * x + 0.05)
    }
    val points =
      Vector.tabulate(grid.nVoxels) { i =>
        Vector(
          left.linearComponent(i, 0),
          left.linearComponent(i, 1),
          left.linearComponent(i, 2)
        )
      }
    val preparedPlan =
      MapExecution.prepare(grid, points)
        .fold(err => throw new IllegalArgumentException(err.message), value => value)
    val constructions = ConstructionCounter()
    val destination = constructions.vectorBuffer(grid.nVoxels)
    val valid = constructions.maskBuffer(grid.nVoxels)
    val sampler = DenseFieldSampler(grid)
    HalfFlowKernels.composePullInto(
      left,
      right,
      destination,
      valid,
      sampler,
      FieldValidity.All,
      FieldValidity.All
    )
    val selfDestination = constructions.vectorBuffer(grid.nVoxels)
    val selfValid = constructions.maskBuffer(grid.nVoxels)
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

    val center = centerIndex(grid)
    val scalarSource = analyticScalarVolume(grid)
    val scalarDestination = constructions.scalarBuffer(grid.nVoxels)
    val scalarValid = constructions.maskBuffer(grid.nVoxels)
    val scalarSampler = DenseFieldSampler(grid)
    HalfFlowKernels.pullScalarInto(
      scalarSource,
      left,
      scalarDestination,
      scalarValid,
      scalarSampler,
      FieldValidity.All,
      FieldValidity.All,
      0.0
    )
    require(scalarValid(center), "scalar JS benchmark center must be valid")
    require(
      math.abs(scalarDestination(center) - analyticScalarAt(left, center)) <= 1e-10,
      "scalar JS benchmark failed its analytic setup oracle"
    )

    val coarseGrid = HalfFlowKernels.pyramidGrid(grid, 2)
    val regridSource = coordinateField(coarseGrid) { (x, y, z) =>
      (1.02 * x + 0.01 * y + 0.2, 0.98 * y + 0.015 * z - 0.1, 1.01 * z + 0.005 * x)
    }
    val regridDestination = constructions.vectorBuffer(grid.nVoxels)
    val regridValid = constructions.maskBuffer(grid.nVoxels)
    val regridSampler = DenseFieldSampler(coarseGrid)
    HalfFlowKernels.regridPullInto(
      regridSource,
      grid,
      regridDestination,
      regridValid,
      regridSampler,
      FieldValidity.All
    )
    require(regridValid(center), "regrid JS benchmark center must be valid")
    require(
      maximumRegridCenterError(grid, regridDestination, center) <= 1e-10,
      "regrid JS benchmark failed its analytic setup oracle"
    )

    val jacobianDestination = constructions.scalarBuffer(grid.nVoxels)
    val jacobianValid = constructions.maskBuffer(grid.nVoxels)
    val jacobianSampler = DenseFieldSampler(grid)
    val jacobianReduction = JacobianReduction()
    HalfFlowKernels.jacobianDeterminantsReduceInto(
      right,
      jacobianDestination,
      jacobianValid,
      jacobianSampler,
      FieldValidity.All,
      jacobianReduction
    )
    require(
      math.abs(jacobianReduction.meanOrNaN - rightDeterminant) <= 1e-10,
      "Jacobian JS benchmark failed its determinant oracle"
    )

    val forward = coordinateField(grid) { (x, y, z) => (x + 0.2, y - 0.15, z + 0.1) }
    val backward = coordinateField(grid) { (x, y, z) => (x - 0.2, y + 0.15, z - 0.1) }
    val forwardSampler = DenseFieldSampler(grid)
    val backwardSampler = DenseFieldSampler(grid)
    val forwardReduction = InverseErrorReduction()
    val backwardReduction = InverseErrorReduction()
    HalfFlowKernels.inversePairErrorInto(
      forward,
      backward,
      forwardSampler,
      backwardSampler,
      FieldValidity.All,
      FieldValidity.All,
      forwardReduction,
      backwardReduction
    )
    require(forwardReduction.maximumMmOrNaN <= 1e-10, "forward JS inverse oracle failed")
    require(backwardReduction.maximumMmOrNaN <= 1e-10, "backward JS inverse oracle failed")

    val constant: Array[Double] = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var constantIndex = 0
    while constantIndex < constant.length do
      constant(constantIndex) = 7.25
      constantIndex += 1
    val pyramidSource = NeuroVol.fromLinear[Double](constant, grid.toNeuroSpace, "constant")
    val pyramidWorkspace = constructions.pyramidWorkspace(grid.nVoxels)
    val pyramidDestination = constructions.scalarBuffer(coarseGrid.nVoxels)
    val pyramidValid = constructions.maskBuffer(coarseGrid.nVoxels)
    val pyramidSampler = DenseFieldSampler(grid)
    HalfFlowKernels.buildPyramidLevelInto(
      pyramidSource,
      coarseGrid,
      1.2,
      pyramidWorkspace,
      pyramidDestination,
      pyramidValid,
      pyramidSampler,
      FieldValidity.All,
      1e-8
    )
    require(
      maximumPyramidConstantError(pyramidDestination, pyramidValid) <= 1e-10,
      "pyramid JS benchmark failed its constant-field oracle"
    )

    // All timed primitive paths below reuse these destinations and workspaces.
    constructions.reset()
    var reference = Vector.empty[Vector[Double]]

    val prepared = measure(warmups, samples) {
      reference =
        preparedPlan.sample(right.values)
          .fold(err => throw new IllegalArgumentException(err.message), value => value)
      checksumReference(reference, valid)
    }
    val dynamic = measure(warmups, samples) {
      val plan =
        MapExecution.prepare(grid, points)
          .fold(err => throw new IllegalArgumentException(err.message), value => value)
      reference =
        plan.sample(right.values)
          .fold(err => throw new IllegalArgumentException(err.message), value => value)
      checksumReference(reference, valid)
    }
    val primitive = measure(warmups, samples) {
      HalfFlowKernels.composePullInto(
        left,
        right,
        destination,
        valid,
        sampler,
        FieldValidity.All,
        FieldValidity.All
      )
      checksumPrimitive(destination, valid, grid.nVoxels)
    }
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
    val scalarPull = measure(warmups, samples) {
      HalfFlowKernels.pullScalarInto(
        scalarSource,
        left,
        scalarDestination,
        scalarValid,
        scalarSampler,
        FieldValidity.All,
        FieldValidity.All,
        0.0
      )
      scalarDestination(center)
    }
    val regrid = measure(warmups, samples) {
      HalfFlowKernels.regridPullInto(
        regridSource,
        grid,
        regridDestination,
        regridValid,
        regridSampler,
        FieldValidity.All
      )
      regridDestination(center)
    }
    val jacobian = measure(warmups, samples) {
      HalfFlowKernels.jacobianDeterminantsReduceInto(
        right,
        jacobianDestination,
        jacobianValid,
        jacobianSampler,
        FieldValidity.All,
        jacobianReduction
      )
      jacobianReduction.meanOrNaN
    }
    val inverse = measure(warmups, samples) {
      HalfFlowKernels.inversePairErrorInto(
        forward,
        backward,
        forwardSampler,
        backwardSampler,
        FieldValidity.All,
        FieldValidity.All,
        forwardReduction,
        backwardReduction
      )
      forwardReduction.maximumMmOrNaN
    }
    val pyramid = measure(warmups, samples) {
      HalfFlowKernels.buildPyramidLevelInto(
        pyramidSource,
        coarseGrid,
        1.2,
        pyramidWorkspace,
        pyramidDestination,
        pyramidValid,
        pyramidSampler,
        FieldValidity.All,
        1e-8
      )
      pyramidDestination(coarseGrid.nVoxels / 2)
    }
    val maxError = maximumValidError(reference, destination, valid, grid.nVoxels)
    val workspaceMaxError =
      maximumMutableError(
        selfDestination,
        selfValid,
        workspaceDestination,
        workspaceDestinationValid,
        grid
      )

    println("HALF_FLOW_IMAGE_KERNEL_JS_PROBE_JSON_BEGIN")
    println("{")
    println("  \"schema\": \"reframe4s-half-flow-image-kernel-js-probe-v1\",")
    println(s"  \"side\": $side,")
    println(s"  \"voxels\": ${grid.nVoxels},")
    println(s"  \"warmups\": $warmups,")
    println(s"  \"samples\": $samples,")
    println(s"  \"validCount\": ${countValid(valid)},")
    println(s"  \"maximumValidError\": $maxError,")
    println(s"  \"ravelWorkspaceMaximumValidError\": $workspaceMaxError,")
    println(s"  \"dynamicSpeedupMedian\": ${dynamic.medianMillis / primitive.medianMillis},")
    println(s"  \"preparedSpeedupMedian\": ${prepared.medianMillis / primitive.medianMillis},")
    println("  \"constructionCounterStatus\": \"explicit benchmark-owned allocation boundaries\",")
    println("  \"primitiveOwnedFullVolumeConstructionsPerOp\": {")
    println(s"    \"scalar\": ${constructions.scalar},")
    println(s"    \"vector\": ${constructions.vector},")
    println(s"    \"mask\": ${constructions.mask},")
    println(s"    \"scratch\": ${constructions.scratch}")
    println("  },")
    printMeasurement("referencePlanAndSample", dynamic, trailingComma = true)
    printMeasurement("referencePreparedSample", prepared, trailingComma = true)
    printMeasurement("primitiveComposeInto", primitive, trailingComma = true)
    printMeasurement("ravelWorkspaceComposeInto", ravelWorkspace, trailingComma = true)
    printMeasurement("primitivePullScalarInto", scalarPull, trailingComma = true)
    printMeasurement("primitiveRegridPullInto", regrid, trailingComma = true)
    printMeasurement("primitiveJacobianReduction", jacobian, trailingComma = true)
    printMeasurement("primitiveInversePairReduction", inverse, trailingComma = true)
    printMeasurement("primitivePyramidLevelInto", pyramid, trailingComma = false)
    println("}")
    println("HALF_FLOW_IMAGE_KERNEL_JS_PROBE_JSON_END")

  private def coordinateField(
      grid: GridSpec
  )(
      f: (Double, Double, Double) => (Double, Double, Double)
  ): DenseVectorField =
    val values =
      RavelArray.tabulate[Double](
        grid.shape(0),
        grid.shape(1),
        grid.shape(2),
        3
      ) { (x, y, z, component) =>
          val world = grid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))
          val mapped = f(world(0), world(1), world(2))
          component match
            case 0 => mapped._1
            case 1 => mapped._2
            case _ => mapped._3
      }
    DenseVectorField(grid, values, DenseVectorFieldKind.SourceCoordinates)

  private def analyticScalarVolume(grid: GridSpec): NeuroVol[Double] =
    val values = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var z = 0
    while z < grid.shape(2) do
      var y = 0
      while y < grid.shape(1) do
        var x = 0
        while x < grid.shape(0) do
          val i = x + y * grid.shape(0) + z * grid.shape(0) * grid.shape(1)
          val world = grid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))
          values(i) = analyticScalar(world(0), world(1), world(2))
          x += 1
        y += 1
      z += 1
    NeuroVol.fromLinear(values, grid.toNeuroSpace, "analytic")

  private inline def analyticScalar(x: Double, y: Double, z: Double): Double =
    1.5 + 0.75 * x - 0.5 * y + 0.25 * z

  private def analyticScalarAt(field: DenseVectorField, index: Int): Double =
    analyticScalar(
      field.linearComponent(index, 0),
      field.linearComponent(index, 1),
      field.linearComponent(index, 2)
    )

  private def centerIndex(grid: GridSpec): Int =
    val x = grid.shape(0) / 2
    val y = grid.shape(1) / 2
    val z = grid.shape(2) / 2
    x + y * grid.shape(0) + z * grid.shape(0) * grid.shape(1)

  private def maximumRegridCenterError(
      grid: GridSpec,
      values: Array[Double],
      index: Int
  ): Double =
    val x = index % grid.shape(0)
    val yz = index / grid.shape(0)
    val y = yz % grid.shape(1)
    val z = yz / grid.shape(1)
    val world = grid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))
    val expectedX = 1.02 * world(0) + 0.01 * world(1) + 0.2
    val expectedY = 0.98 * world(1) + 0.015 * world(2) - 0.1
    val expectedZ = 1.01 * world(2) + 0.005 * world(0)
    math.max(
      math.abs(values(index) - expectedX),
      math.max(
        math.abs(values(index + grid.nVoxels) - expectedY),
        math.abs(values(index + 2 * grid.nVoxels) - expectedZ)
      )
    )

  private def checksumMutable(
      values: MutableRavelArray[Double, Rank[1]],
      valid: Array[Boolean],
      grid: GridSpec
  ): Double =
    val nx = grid.extentX
    val ny = grid.extentY
    val nz = grid.extentZ
    var sum = 0.0
    var i = 0
    while i < grid.nVoxels do
      if valid(i) then
        val x = i % nx
        val yz = i / nx
        val y = yz % ny
        val z = yz / ny
        val storageBase = 3 * (z + nz * (y + ny * x))
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

  private val rightDeterminant: Double =
    1.01 * (0.99 * 1.005 - 0.01 * 0.0) -
      0.015 * (0.0 * 1.005 - 0.01 * 0.02)

  private def maximumPyramidConstantError(
      values: Array[Double],
      valid: Array[Boolean]
  ): Double =
    var maximum = 0.0
    var i = 0
    while i < values.length do
      require(valid(i), s"pyramid JS benchmark voxel $i must be valid")
      maximum = math.max(maximum, math.abs(values(i) - 7.25))
      i += 1
    maximum

  private def measure(warmups: Int, samples: Int)(operation: => Double): Measurement =
    var checksum = 0.0
    var i = 0
    while i < warmups do
      checksum = operation
      i += 1
    val elapsed = Array.ofDim[Double](samples)
    i = 0
    while i < samples do
      val start = js.Date.now()
      checksum = operation
      elapsed(i) = js.Date.now() - start
      i += 1
    val sorted = elapsed.sorted
    Measurement(percentile(sorted, 0.5), percentile(sorted, 0.95), checksum)

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

  private def percentile(sorted: Array[Double], probability: Double): Double =
    val index = math.min(sorted.length - 1, math.ceil(probability * sorted.length.toDouble).toInt - 1)
    sorted(math.max(0, index))

  private def printMeasurement(
      name: String,
      measurement: Measurement,
      trailingComma: Boolean
  ): Unit =
    println(s"  \"$name\": {")
    println(s"    \"medianMillis\": ${measurement.medianMillis},")
    println(s"    \"p95Millis\": ${measurement.p95Millis},")
    println(s"    \"checksum\": ${measurement.checksum}")
    println(if trailingComma then "  }," else "  }")
