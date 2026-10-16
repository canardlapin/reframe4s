package reframe4s.halfflow

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import ravel.MutableNDArray as MutableRavelArray
import ravel.NDArray as RavelArray
import ravel.Rank
import ravel.Shape
import reframe4s.halfflow.internal.*
import scala.compiletime.uninitialized

/** Dynamic pull-map composition against both current reference paths. */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
class HalfFlowImageKernelBenchmark:

  @Param(Array("64"))
  var side: Int = 0

  private var grid: GridSpec = uninitialized
  private var left: DenseVectorField = uninitialized
  private var right: DenseVectorField = uninitialized
  private var points: Vector[Vector[Double]] = uninitialized
  private var preparedPlan: MapExecution.QueryBatch = uninitialized
  private var destination: Array[Double] = uninitialized
  private var valid: Array[Boolean] = uninitialized
  private var sampler: DenseFieldSampler = uninitialized
  private var workspaceSource: MutableRavelArray[Double, Rank[1]] = uninitialized
  private var workspaceDestination: MutableRavelArray[Double, Rank[1]] = uninitialized
  private var workspaceSourceValid: Array[Boolean] = uninitialized
  private var workspaceDestinationValid: Array[Boolean] = uninitialized
  private var workspaceSampler: DenseFieldSampler = uninitialized
  private var scalarSource: NeuroVol[Double] = uninitialized
  private var scalarDestination: Array[Double] = uninitialized
  private var scalarValid: Array[Boolean] = uninitialized
  private var scalarSampler: DenseFieldSampler = uninitialized
  private var coarseGrid: GridSpec = uninitialized
  private var regridSource: DenseVectorField = uninitialized
  private var regridDestination: Array[Double] = uninitialized
  private var regridValid: Array[Boolean] = uninitialized
  private var regridSampler: DenseFieldSampler = uninitialized
  private var jacobianDestination: Array[Double] = uninitialized
  private var jacobianValid: Array[Boolean] = uninitialized
  private var jacobianSampler: DenseFieldSampler = uninitialized
  private var jacobianReduction: JacobianReduction = uninitialized
  private var forwardTranslation: DenseVectorField = uninitialized
  private var backwardTranslation: DenseVectorField = uninitialized
  private var forwardSampler: DenseFieldSampler = uninitialized
  private var backwardSampler: DenseFieldSampler = uninitialized
  private var forwardReduction: InverseErrorReduction = uninitialized
  private var backwardReduction: InverseErrorReduction = uninitialized
  private var pyramidSource: NeuroVol[Double] = uninitialized
  private var pyramidWorkspace: PyramidWorkspace = uninitialized
  private var pyramidDestination: Array[Double] = uninitialized
  private var pyramidValid: Array[Boolean] = uninitialized
  private var pyramidSampler: DenseFieldSampler = uninitialized
  private var boxSource: Array[Double] = uninitialized
  private var boxDestination: Array[Double] = uninitialized
  private var boxWorkspace: BoxSumWorkspace = uninitialized
  private var gaussianSupport: Array[Double] = uninitialized
  private var gaussianDestination: Array[Double] = uninitialized
  private var gaussianWeight: Array[Double] = uninitialized
  private var gaussianWorkspace: GaussianWorkspace = uninitialized
  private var gaussianReduction: GaussianReduction = uninitialized
  private var boxRadius: VoxelWindowRadius = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    grid = GridSpec.identity(Vector(side, side, side))
    left = coordinateField(grid) { (x, y, z) =>
      (x + 0.25, y + 0.125, z + 0.375)
    }
    right = coordinateField(grid) { (x, y, z) =>
      (1.01 * x + 0.015 * y + 0.1, 0.99 * y + 0.01 * z - 0.2, 1.005 * z + 0.02 * x + 0.05)
    }
    points =
      Vector.tabulate(grid.nVoxels) { i =>
        Vector(
          left.linearComponent(i, 0),
          left.linearComponent(i, 1),
          left.linearComponent(i, 2)
        )
      }
    preparedPlan =
      MapExecution.prepare(grid, points)
        .fold(err => throw new IllegalArgumentException(err.message), value => value)
    destination = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    valid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    sampler = DenseFieldSampler(grid)
    HalfFlowKernels.composePullInto(
      left,
      right,
      destination,
      valid,
      sampler,
      FieldValidity.All,
      FieldValidity.All
    )
    val reference =
      preparedPlan.sample(right.values)
        .fold(err => throw new IllegalArgumentException(err.message), value => value)
    val referenceChecksum = checksumReference(reference)
    val primitiveChecksum = checksumPrimitive()
    require(
      referenceChecksum == primitiveChecksum,
      s"benchmark setup checksum mismatch: reference=$referenceChecksum primitive=$primitiveChecksum"
    )
    workspaceSource =
      MutableRavelArray.zeros[Double, Rank[1]](
        Shape(grid.nVoxels * 3)
      )
    workspaceSource.assign(left.flatValues)
    workspaceDestination =
      MutableRavelArray.zeros[Double, Rank[1]](
        Shape(grid.nVoxels * 3)
      )
    workspaceSourceValid =
      PrimitiveBuffers.fillConst[Boolean](grid.nVoxels, true)
    workspaceDestinationValid =
      PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    workspaceSampler = DenseFieldSampler(grid)
    HalfFlowKernels.composeSelfPullInto(
      grid,
      workspaceSource,
      workspaceSourceValid,
      workspaceDestination,
      workspaceDestinationValid,
      workspaceSampler,
      CoordinateMapOutside.Identity
    )
    require(
      workspaceDestinationValid(centerIndex(grid)),
      "Ravel workspace benchmark center must be valid"
    )
    HalfFlowKernels.composePullInto(
      left,
      left,
      destination,
      valid,
      sampler,
      FieldValidity.All,
      FieldValidity.All,
      CoordinateMapOutside.Identity
    )
    val primitiveSelfChecksum = checksumPrimitive()
    val workspaceSelfChecksum = checksumWorkspace()
    require(
      primitiveSelfChecksum == workspaceSelfChecksum,
      s"self-composition oracle mismatch: primitive=$primitiveSelfChecksum workspace=$workspaceSelfChecksum"
    )

    scalarSource = analyticScalarVolume(grid)
    scalarDestination = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    scalarValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    scalarSampler = DenseFieldSampler(grid)
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
    val center = centerIndex(grid)
    require(scalarValid(center), "scalar benchmark center must be valid")
    require(
      math.abs(scalarDestination(center) - analyticScalarAt(left, center)) <= 1e-10,
      "scalar benchmark failed its analytic setup oracle"
    )

    coarseGrid = HalfFlowKernels.pyramidGrid(grid, 2)
    regridSource = coordinateField(coarseGrid) { (x, y, z) =>
      (1.02 * x + 0.01 * y + 0.2, 0.98 * y + 0.015 * z - 0.1, 1.01 * z + 0.005 * x)
    }
    regridDestination = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    regridValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    regridSampler = DenseFieldSampler(coarseGrid)
    HalfFlowKernels.regridPullInto(
      regridSource,
      grid,
      regridDestination,
      regridValid,
      regridSampler,
      FieldValidity.All
    )
    require(regridValid(center), "regrid benchmark center must be valid")
    require(maximumRegridCenterError(center) <= 1e-10, "regrid benchmark failed its analytic setup oracle")

    jacobianDestination = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    jacobianValid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    jacobianSampler = DenseFieldSampler(grid)
    jacobianReduction = JacobianReduction()
    HalfFlowKernels.jacobianDeterminantsReduceInto(
      right,
      jacobianDestination,
      jacobianValid,
      jacobianSampler,
      FieldValidity.All,
      jacobianReduction
    )
    require(jacobianReduction.evaluated == (side - 2) * (side - 2) * (side - 2), "Jacobian support mismatch")
    require(
      math.abs(jacobianReduction.meanOrNaN - rightDeterminant) <= 1e-10,
      "Jacobian benchmark failed its analytic determinant oracle"
    )

    forwardTranslation = coordinateField(grid) { (x, y, z) =>
      (x + 0.2, y - 0.15, z + 0.1)
    }
    backwardTranslation = coordinateField(grid) { (x, y, z) =>
      (x - 0.2, y + 0.15, z - 0.1)
    }
    forwardSampler = DenseFieldSampler(grid)
    backwardSampler = DenseFieldSampler(grid)
    forwardReduction = InverseErrorReduction()
    backwardReduction = InverseErrorReduction()
    HalfFlowKernels.inversePairErrorInto(
      forwardTranslation,
      backwardTranslation,
      forwardSampler,
      backwardSampler,
      FieldValidity.All,
      FieldValidity.All,
      forwardReduction,
      backwardReduction
    )
    require(forwardReduction.maximumMmOrNaN <= 1e-10, "forward inverse oracle failed")
    require(backwardReduction.maximumMmOrNaN <= 1e-10, "backward inverse oracle failed")

    val constant = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var i = 0
    while i < constant.length do
      constant(i) = 7.25
      i += 1
    pyramidSource = NeuroVol.fromLinear(constant, grid.toNeuroSpace, "constant")
    pyramidWorkspace = PyramidWorkspace(grid.nVoxels)
    pyramidDestination = PrimitiveBuffers.ofSize[Double](coarseGrid.nVoxels)
    pyramidValid = PrimitiveBuffers.ofSize[Boolean](coarseGrid.nVoxels)
    pyramidSampler = DenseFieldSampler(grid)
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
    require(maximumPyramidConstantError() <= 1e-10, "pyramid benchmark failed its constant-field oracle")

    boxSource = scalarSource.copyLegacyLinear
    boxDestination = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    boxWorkspace = BoxSumWorkspace(grid)
    boxRadius = VoxelWindowRadius(2, 2, 2)
    BoxSum3D.sumInto(
      boxSource,
      grid,
      boxRadius,
      boxDestination,
      boxWorkspace
    )
    val boxReference = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    BoxSum3D.referenceInto(boxSource, grid, boxRadius, boxReference)
    require(
      math.abs(boxDestination(center) - boxReference(center)) <= 1e-10,
      "box benchmark failed its reference oracle"
    )

    gaussianSupport = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    gaussianDestination = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    gaussianWeight = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    gaussianWorkspace = GaussianWorkspace(grid)
    gaussianReduction = GaussianReduction()
    i = 0
    while i < gaussianSupport.length do
      gaussianSupport(i) = if (i % side) > 1 then 1.0 else 0.0
      i += 1
    val gaussian = Gaussian3D.normalizedInto(
      boxSource,
      gaussianSupport,
      grid,
      sigmaMm = 1.5,
      minimumWeight = 1e-8,
      gaussianDestination,
      gaussianWeight,
      gaussianWorkspace
    )
    require(gaussian.validVoxels > 0, "Gaussian benchmark has no valid support")

  @Benchmark
  def referencePlanAndSample(): Double =
    val plan =
      MapExecution.prepare(grid, points)
        .fold(err => throw new IllegalArgumentException(err.message), value => value)
    val values =
      plan.sample(right.values)
        .fold(err => throw new IllegalArgumentException(err.message), value => value)
    values(grid.nVoxels / 2)(0)

  @Benchmark
  def referencePreparedSample(): Double =
    val values =
      preparedPlan.sample(right.values)
        .fold(err => throw new IllegalArgumentException(err.message), value => value)
    values(grid.nVoxels / 2)(0)

  @Benchmark
  @OperationsPerInvocation(128)
  def primitiveComposeInto(): Double =
    var repetition = 0
    while repetition < 128 do
      HalfFlowKernels.composePullInto(
        left,
        right,
        destination,
        valid,
        sampler,
        FieldValidity.All,
        FieldValidity.All
      )
      repetition += 1
    destination(grid.nVoxels / 2)

  @Benchmark
  @OperationsPerInvocation(128)
  def primitiveSelfComposeInto(): Double =
    var repetition = 0
    while repetition < 128 do
      HalfFlowKernels.composePullInto(
        left,
        left,
        destination,
        valid,
        sampler,
        FieldValidity.All,
        FieldValidity.All,
        CoordinateMapOutside.Identity
      )
      repetition += 1
    destination(grid.nVoxels / 2)

  @Benchmark
  @OperationsPerInvocation(128)
  def ravelWorkspaceSelfComposeInto(): Double =
    var repetition = 0
    while repetition < 128 do
      HalfFlowKernels.composeSelfPullInto(
        grid,
        workspaceSource,
        workspaceSourceValid,
        workspaceDestination,
        workspaceDestinationValid,
        workspaceSampler,
        CoordinateMapOutside.Identity
      )
      repetition += 1
    val center = side / 2
    val storageIndex = 3 * (center + side * (center + side * center))
    workspaceDestination(storageIndex)

  @Benchmark
  @OperationsPerInvocation(128)
  def primitivePullScalarInto(): Double =
    var repetition = 0
    while repetition < 128 do
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
      repetition += 1
    scalarDestination(grid.nVoxels / 2)

  @Benchmark
  @OperationsPerInvocation(128)
  def primitiveRegridPullInto(): Double =
    var repetition = 0
    while repetition < 128 do
      HalfFlowKernels.regridPullInto(
        regridSource,
        grid,
        regridDestination,
        regridValid,
        regridSampler,
        FieldValidity.All
      )
      repetition += 1
    regridDestination(grid.nVoxels / 2)

  @Benchmark
  @OperationsPerInvocation(128)
  def primitiveJacobianReduction(): Double =
    var repetition = 0
    while repetition < 128 do
      HalfFlowKernels.jacobianDeterminantsReduceInto(
        right,
        jacobianDestination,
        jacobianValid,
        jacobianSampler,
        FieldValidity.All,
        jacobianReduction
      )
      repetition += 1
    jacobianReduction.meanOrNaN

  @Benchmark
  @OperationsPerInvocation(128)
  def primitiveInversePairReduction(): Double =
    var repetition = 0
    while repetition < 128 do
      HalfFlowKernels.inversePairErrorInto(
        forwardTranslation,
        backwardTranslation,
        forwardSampler,
        backwardSampler,
        FieldValidity.All,
        FieldValidity.All,
        forwardReduction,
        backwardReduction
      )
      repetition += 1
    forwardReduction.maximumMmOrNaN

  @Benchmark
  @OperationsPerInvocation(256)
  def primitivePyramidLevelInto(): Double =
    var repetition = 0
    while repetition < 256 do
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
      repetition += 1
    pyramidDestination(coarseGrid.nVoxels / 2)

  @Benchmark
  @OperationsPerInvocation(64)
  def separableBoxSumInto(): Double =
    var repetition = 0
    while repetition < 64 do
      BoxSum3D.sumInto(
        boxSource,
        grid,
        boxRadius,
        boxDestination,
        boxWorkspace
      )
      repetition += 1
    boxDestination(grid.nVoxels / 2)

  @Benchmark
  @OperationsPerInvocation(32)
  def physicalGaussianInto(): Double =
    var repetition = 0
    while repetition < 32 do
      Gaussian3D.smoothInto(
        boxSource,
        grid,
        sigmaMm = 1.5,
        gaussianDestination,
        gaussianWorkspace
      )
      repetition += 1
    gaussianDestination(grid.nVoxels / 2)

  @Benchmark
  @OperationsPerInvocation(32)
  def normalizedGaussianInto(): Double =
    var repetition = 0
    while repetition < 32 do
      Gaussian3D.normalizedInto(
        boxSource,
        gaussianSupport,
        grid,
        sigmaMm = 1.5,
        minimumWeight = 1e-8,
        gaussianDestination,
        gaussianWeight,
        gaussianWorkspace,
        GaussianBoundary.Reflect,
        gaussianReduction
      )
      repetition += 1
    gaussianDestination(grid.nVoxels / 2)

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
        val world =
          grid.voxelToWorld(
            Vector(x.toDouble, y.toDouble, z.toDouble)
          )
        val mapped = f(world(0), world(1), world(2))
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

  private def maximumRegridCenterError(index: Int): Double =
    val x = index % grid.shape(0)
    val yz = index / grid.shape(0)
    val y = yz % grid.shape(1)
    val z = yz / grid.shape(1)
    val world = grid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))
    val expectedX = 1.02 * world(0) + 0.01 * world(1) + 0.2
    val expectedY = 0.98 * world(1) + 0.015 * world(2) - 0.1
    val expectedZ = 1.01 * world(2) + 0.005 * world(0)
    math.max(
      math.abs(regridDestination(index) - expectedX),
      math.max(
        math.abs(regridDestination(index + grid.nVoxels) - expectedY),
        math.abs(regridDestination(index + 2 * grid.nVoxels) - expectedZ)
      )
    )

  private val rightDeterminant: Double =
    1.01 * (0.99 * 1.005 - 0.01 * 0.0) -
      0.015 * (0.0 * 1.005 - 0.01 * 0.02)

  private def maximumPyramidConstantError(): Double =
    var maximum = 0.0
    var i = 0
    while i < pyramidDestination.length do
      require(pyramidValid(i), s"pyramid benchmark voxel $i must be valid")
      maximum = math.max(maximum, math.abs(pyramidDestination(i) - 7.25))
      i += 1
    maximum

  private def checksumReference(values: Vector[Vector[Double]]): Double =
    var sum = 0.0
    var i = 0
    while i < values.length do
      if valid(i) then
        val point = values(i)
        val weight = (i % 17 + 1).toDouble
        sum += weight * (point(0) + 0.5 * point(1) + 0.25 * point(2))
      i += 1
    sum

  private def checksumPrimitive(): Double =
    var sum = 0.0
    var i = 0
    while i < grid.nVoxels do
      if valid(i) then
        val weight = (i % 17 + 1).toDouble
        sum += weight * (
          destination(i) +
            0.5 * destination(i + grid.nVoxels) +
            0.25 * destination(i + 2 * grid.nVoxels)
        )
      i += 1
    sum

  private def checksumWorkspace(): Double =
    var sum = 0.0
    var i = 0
    while i < grid.nVoxels do
      require(
        valid(i) == workspaceDestinationValid(i),
        s"self-composition validity mismatch at voxel $i"
      )
      if workspaceDestinationValid(i) then
        val x = i % grid.shape(0)
        val yz = i / grid.shape(0)
        val y = yz % grid.shape(1)
        val z = yz / grid.shape(1)
        val storageBase = 3 * (z + grid.shape(2) * (y + grid.shape(1) * x))
        require(
          destination(i) == workspaceDestination(storageBase) &&
            destination(i + grid.nVoxels) == workspaceDestination(storageBase + 1) &&
            destination(i + 2 * grid.nVoxels) == workspaceDestination(storageBase + 2),
          s"self-composition value mismatch at voxel $i"
        )
        val weight = (i % 17 + 1).toDouble
        sum += weight * (
          workspaceDestination(storageBase) +
            0.5 * workspaceDestination(storageBase + 1) +
            0.25 * workspaceDestination(storageBase + 2)
        )
      i += 1
    sum
