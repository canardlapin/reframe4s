package reframe4s.halfflow

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized
import reframe4s.halfflow.internal.*

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class HalfFlowRegistrationBenchmark:
  sealed trait Work
  sealed trait Fixed
  sealed trait Moving

  @Param(Array("32"))
  var side: Int = 0

  private val amplitude = 0.8
  private val flowConfig = FlowConfig(maximumInitialGradient = 0.012)
  private var frame: Frame[Work] = uninitialized
  private var velocity: Velocity[Work] = uninitialized
  private var workspace: PairedFlowWorkspace[Work] = uninitialized
  private var guardWorkspace: TopologyGuardWorkspace[Work, Work] = uninitialized
  private var admittedFlow: PairedFlow[Work] = uninitialized
  private var fixedSource: Array[Double] = uninitialized
  private var movingSource: Array[Double] = uninitialized
  private var ccFrozen: FrozenCcWeights = uninitialized
  private var ccWorkspace: NeighborhoodCcWorkspace = uninitialized
  private var ccBuffer: NeighborhoodCcBuffer = uninitialized
  private var featureConfig: T1FeatureConfig = uninitialized
  private var fixedFeatureWorkspace: T1FeatureWorkspace[Work] = uninitialized
  private var movingFeatureWorkspace: T1FeatureWorkspace[Work] = uninitialized
  private var fixedFeatureBuffer: T1FeatureBuffer[Work] = uninitialized
  private var movingFeatureBuffer: T1FeatureBuffer[Work] = uninitialized
  private var fixedFeatures: T1FeatureVolume[Work] = uninitialized
  private var movingFeatures: T1FeatureVolume[Work] = uninitialized
  private var rankOneConfig: LocalLmConfig = uninitialized
  private var multiChannelConfig: LocalLmConfig = uninitialized
  private var localWorkspace: LocalLmWorkspace[Work] = uninitialized
  private var rankOneBuffer: LocalLmBuffer[Work] = uninitialized
  private var multiChannelBuffer: LocalLmBuffer[Work] = uninitialized
  private var multiChannelResult: LocalLmResult[Work] = uninitialized
  private var sobolevConfig: SobolevConfig = uninitialized
  private var sobolevPowerOneConfig: SobolevConfig = uninitialized
  private var sobolevWorkspace: SobolevWorkspace[Work] = uninitialized
  private var sobolevBuffer: SobolevBuffer[Work] = uninitialized
  private var sobolevResult: SobolevResult[Work] = uninitialized
  private var registrationFixed: RegistrationImage[Fixed] = uninitialized
  private var registrationMoving: RegistrationImage[Moving] = uninitialized
  private var registrationInitial: Midpoint[Work, Fixed, Moving] = uninitialized
  private var registrationPlan: HalfFlowPlan = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val grid = GridSpec.identity(Vector(side, side, side))
    frame = Frame[Work](SpatialDomainId("half-flow-benchmark"), grid)
    val values = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    val denominator = (side - 1).toDouble
    var z = 0
    while z < side do
      val wz = math.sin(math.Pi * z.toDouble / denominator)
      var y = 0
      while y < side do
        val wy = math.sin(math.Pi * y.toDouble / denominator)
        var x = 0
        while x < side do
          val index = x + side * y + side * side * z
          values(index) = analyticVelocityX(x.toDouble, wy, wz, denominator)
          values(index + grid.nVoxels) = 0.0
          values(index + 2 * grid.nVoxels) = 0.0
          x += 1
        y += 1
      z += 1
    val field = DenseVectorField.fromLegacyPlanar(
      grid,
      values,
      DenseVectorFieldKind.Displacement
    )
    velocity = Velocity.make(frame, field).fold(error => throw new IllegalArgumentException(error.message), identity)
    workspace = PairedFlowWorkspace(frame)
    admittedFlow = expWithWorkspace()
    require(admittedFlow.diagnostics.squaringDepth == 3, "benchmark must exercise three squarings")
    require(admittedFlow.diagnostics.plusValid == grid.nVoxels, "plus map lost fixture support")
    require(admittedFlow.diagnostics.minusValid == grid.nVoxels, "minus map lost fixture support")
    require(maximumOracleError(admittedFlow) <= 1.2e-3, "paired flow failed independent RK4 oracle")
    guardWorkspace = TopologyGuardWorkspace(admittedFlow.pair)
    val guard = TopologyGuard.evaluate(admittedFlow.pair)
    require(guard.safe, guard.reasons.mkString(", "))
    setupLocalModel(grid)
    setupSobolev()
    setupRegistration(grid)

  @Benchmark
  def pairedExponential(): Double =
    checksum(exp())

  @Benchmark
  def pairedExponentialWithWorkspace(): Double =
    checksum(expWithWorkspace())

  @Benchmark
  def topologyAndInverseGuard(): Double =
    val report = TopologyGuard.evaluate(admittedFlow.pair)
    require(report.safe, report.reasons.mkString(", "))
    report.forward.quantiles.p50.getOrElse(Double.NaN) +
      report.backward.quantiles.p50.getOrElse(Double.NaN) +
      report.inverse.forwardThenBackward.maximumMm.getOrElse(Double.NaN)

  @Benchmark
  def topologyAndInverseGuardWithWorkspace(): Double =
    val report = TopologyGuard
      .evaluateWith(admittedFlow.pair, guardWorkspace)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    require(report.safe, report.reasons.mkString(", "))
    report.forward.quantiles.p50.getOrElse(Double.NaN) +
      report.backward.quantiles.p50.getOrElse(Double.NaN) +
      report.inverse.forwardThenBackward.maximumMm.getOrElse(Double.NaN)

  @Benchmark
  def t1FeaturesWithWorkspace(): Double =
    val result = T1Features
      .computeInto(frame, fixedSource, FieldValidity.All, featureConfig, fixedFeatureWorkspace, fixedFeatureBuffer)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    featureChecksum(result)

  @Benchmark
  def neighborhoodCcValueAndGradient(): Double =
    val result = NeighborhoodCc
      .valueAndGradientWith(fixedSource, movingSource, ccFrozen, ccWorkspace, ccBuffer)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val center = fixedSource.length / 2
    result.loss + result.fixedIntensityGradient(center) + result.movingIntensityGradient(center)

  @Benchmark
  def rankOneLocalLmWithWorkspace(): Double =
    val result = LocalLm
      .solveInto(fixedFeatures, movingFeatures, rankOneConfig, localWorkspace, rankOneBuffer)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    localChecksum(result)

  @Benchmark
  def multiChannelLocalLmWithWorkspace(): Double =
    val result = LocalLm
      .solveInto(fixedFeatures, movingFeatures, multiChannelConfig, localWorkspace, multiChannelBuffer)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    localChecksum(result)

  @Benchmark
  def predictedDropMultiChannel(): Double =
    LocalLm
      .predictedDrop(
        fixedFeatures,
        movingFeatures,
        multiChannelResult.rawVelocity,
        multiChannelResult.rawValidity,
        multiChannelResult.model
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)

  @Benchmark
  def sobolevShapeWithWorkspace(): Double =
    val result = SobolevShaper
      .shapeInto(
        multiChannelResult.rawVelocity,
        multiChannelResult.rawValidity,
        sobolevConfig,
        sobolevWorkspace,
        sobolevBuffer
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    sobolevChecksum(result)

  @Benchmark
  def sobolevPowerOneWithWorkspace(): Double =
    val result = SobolevShaper
      .shapeInto(
        multiChannelResult.rawVelocity,
        multiChannelResult.rawValidity,
        sobolevPowerOneConfig,
        sobolevWorkspace,
        sobolevBuffer
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    sobolevChecksum(result)

  @Benchmark
  def acceptedNonlinearLevel(): Double =
    val result = HalfFlowLm
      .register(registrationFixed, registrationMoving, registrationInitial, registrationPlan)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    require(result.diagnostics.acceptedSteps >= 1, "benchmark must accept a nonlinear step")
    registrationChecksum(result)

  @Benchmark
  def robustAffineInitialization(): Double =
    val result = AffineInitializer
      .estimate(registrationFixed, registrationMoving, registrationInitial.work)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    require(result.diagnostics.finalValue < result.diagnostics.initialValue, "affine benchmark must improve its objective")
    val matrix = result.affine.transform.matrix
    matrix(0, 3) + matrix(1, 3) + matrix(2, 3) + result.diagnostics.finalValue

  private def exp(): PairedFlow[Work] =
    PairedScalingAndSquaring
      .expHalfPair(velocity, flowConfig)
      .fold(error => throw new IllegalArgumentException(error.message), identity)

  private def expWithWorkspace(): PairedFlow[Work] =
    PairedScalingAndSquaring
      .expHalfPairWith(velocity, workspace, flowConfig)
      .fold(error => throw new IllegalArgumentException(error.message), identity)

  private def setupLocalModel(grid: GridSpec): Unit =
    fixedSource = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    movingSource = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val denominator = (side - 1).toDouble
    var z = 0
    while z < side do
      var y = 0
      while y < side do
        var x = 0
        while x < side do
          val index = x + side * y + side * side * z
          val base =
            100.0 + 18.0 * math.sin(2.0 * math.Pi * x / denominator) +
              12.0 * math.cos(2.0 * math.Pi * y / denominator) +
              8.0 * math.sin(4.0 * math.Pi * z / denominator) + 0.2 * x
          fixedSource(index) = base
          movingSource(index) =
            base + 1.5 * math.sin(2.0 * math.Pi * (x + y) / denominator) -
              0.8 * math.cos(2.0 * math.Pi * (y + z) / denominator)
          x += 1
        y += 1
      z += 1
    val ccSupport = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var index = 0
    while index < ccSupport.length do
      ccSupport(index) = 1.0
      index += 1
    ccWorkspace = NeighborhoodCcWorkspace(grid)
    ccBuffer = NeighborhoodCcBuffer(grid)
    ccFrozen = NeighborhoodCc
      .prepareWith(
        fixedSource,
        movingSource,
        ccSupport,
        grid,
        NeighborhoodCcConfig.default,
        ccWorkspace
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    require(ccFrozen.activeWindows > 0, "CC benchmark has no active windows")
    featureConfig = T1FeatureConfig
      .make(
        Vector(FeatureRadiusMm(2.0), FeatureRadiusMm(4.0), FeatureRadiusMm(8.0)),
        minimumActiveVoxels = 64
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    fixedFeatureWorkspace = T1FeatureWorkspace(frame, 3)
    movingFeatureWorkspace = T1FeatureWorkspace(frame, 3)
    fixedFeatureBuffer = T1FeatureBuffer(frame, featureConfig)
    movingFeatureBuffer = T1FeatureBuffer(frame, featureConfig)
    fixedFeatures = T1Features
      .computeInto(frame, fixedSource, FieldValidity.All, featureConfig, fixedFeatureWorkspace, fixedFeatureBuffer)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    movingFeatures = T1Features
      .computeInto(frame, movingSource, FieldValidity.All, featureConfig, movingFeatureWorkspace, movingFeatureBuffer)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    rankOneConfig = LocalLmConfig
      .make(minimumActiveVoxels = 64, variant = LocalLmVariant.RankOne(0))
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    multiChannelConfig = LocalLmConfig
      .make(minimumActiveVoxels = 64, variant = LocalLmVariant.MultiChannel)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    localWorkspace = LocalLmWorkspace(frame)
    rankOneBuffer = LocalLmBuffer(frame)
    multiChannelBuffer = LocalLmBuffer(frame)
    multiChannelResult = LocalLm
      .solveInto(fixedFeatures, movingFeatures, multiChannelConfig, localWorkspace, multiChannelBuffer)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    require(featureChecksum(fixedFeatures).isFinite, "feature checksum must be finite")
    require(localChecksum(multiChannelResult).isFinite, "local LM checksum must be finite")
    require(predictedDropMultiChannel() > 0.0, "multi-channel predicted drop must be positive")

  private def setupSobolev(): Unit =
    sobolevConfig = SobolevConfig
      .make(
        SmoothLengthMm(0.9),
        power = 2,
        relativeTolerance = 1e-6,
        maximumIterations = 80,
        maximumDisplacementMm = 0.2,
        maximumStrain = 0.20
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    sobolevPowerOneConfig = SobolevConfig
      .make(
        SmoothLengthMm(0.9),
        power = 1,
        relativeTolerance = 1e-6,
        maximumIterations = 80,
        maximumDisplacementMm = 0.2,
        maximumStrain = 0.20
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    sobolevWorkspace = SobolevWorkspace(frame)
    sobolevBuffer = SobolevBuffer(frame)
    sobolevResult = SobolevShaper
      .shapeInto(
        multiChannelResult.rawVelocity,
        multiChannelResult.rawValidity,
        sobolevConfig,
        sobolevWorkspace,
        sobolevBuffer
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    require(sobolevResult.diagnostics.maximumRelativeResidual <= 1.01e-6, "Sobolev solve missed tolerance")
    require(sobolevChecksum(sobolevResult).isFinite, "Sobolev checksum must be finite")

  private def setupRegistration(grid: GridSpec): Unit =
    val fixedFrame = Frame[Fixed](SpatialDomainId("half-flow-fixed"), grid)
    val movingFrame = Frame[Moving](SpatialDomainId("half-flow-moving"), grid)
    val fixedValues = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val movingValues = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val shift = 0.35
    var z = 0
    while z < side do
      var y = 0
      while y < side do
        var x = 0
        while x < side do
          val index = x + side * y + side * side * z
          fixedValues(index) = registrationSignal(x.toDouble, y.toDouble, z.toDouble)
          movingValues(index) = registrationSignal(x.toDouble - shift, y.toDouble, z.toDouble)
          x += 1
        y += 1
      z += 1
    registrationFixed = RegistrationImage
      .make(fixedFrame, NeuroVol.fromLinear[Double](fixedValues, grid.toNeuroSpace, "fixed"))
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    registrationMoving = RegistrationImage
      .make(movingFrame, NeuroVol.fromLinear[Double](movingValues, grid.toNeuroSpace, "moving"))
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    registrationInitial = Midpoint
      .identity(frame, fixedFrame, movingFrame)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val feature = T1FeatureConfig
      .make(
        Vector(FeatureRadiusMm(2.0), FeatureRadiusMm(4.0)),
        minimumValidWindowFraction = 0.45,
        minimumActiveVoxels = 64
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val local = LocalLmConfig
      .make(damping = 0.08, minimumActiveVoxels = 64, variant = LocalLmVariant.MultiChannel)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val trust = TrustConfig
      .make(targetAcceptedSteps = 1, maximumAttempts = 4)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val registrationLevel = HalfFlowLevel
      .make(
        shrink = 1,
        pyramidSigmaMm = 0.0,
        feature = feature,
        localLm = local,
        sobolev = sobolevConfig,
        flow = FlowConfig(maximumInitialDisplacementMm = 0.25, maximumInitialGradient = 0.15),
        guard = GuardConfig(maximumInverseErrorMm = 0.5, maximumInverseErrorVox = 0.5, minimumValidFraction = 0.85),
        trust = trust,
        minimumUsefulVelocityMm = 1e-7
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    registrationPlan = HalfFlowPlan
      .make(Vector(registrationLevel))
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val admitted = HalfFlowLm
      .register(registrationFixed, registrationMoving, registrationInitial, registrationPlan)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    require(admitted.diagnostics.acceptedSteps >= 1, "benchmark registration fixture did not accept")
    require(registrationChecksum(admitted).isFinite, "registration checksum must be finite")

  private def maximumOracleError(flow: PairedFlow[Work]): Double =
    val points = Array((side / 4, side / 3, side / 2), (side / 2, side / 4, 2 * side / 3))
    var maximum = 0.0
    var index = 0
    while index < points.length do
      val (x, y, z) = points(index)
      val wy = math.sin(math.Pi * y.toDouble / (side - 1).toDouble)
      val wz = math.sin(math.Pi * z.toDouble / (side - 1).toDouble)
      maximum = math.max(maximum, math.abs(mapX(flow.pair.forward, x, y, z) - rk4(x.toDouble, wy, wz, 0.5)))
      maximum = math.max(maximum, math.abs(mapX(flow.pair.backward, x, y, z) - rk4(x.toDouble, wy, wz, -0.5)))
      index += 1
    maximum

  private def rk4(start: Double, wy: Double, wz: Double, time: Double): Double =
    val steps = 4096
    val dt = time / steps.toDouble
    val denominator = (side - 1).toDouble
    var value = start
    var step = 0
    while step < steps do
      val k1 = analyticVelocityX(value, wy, wz, denominator)
      val k2 = analyticVelocityX(value + 0.5 * dt * k1, wy, wz, denominator)
      val k3 = analyticVelocityX(value + 0.5 * dt * k2, wy, wz, denominator)
      val k4 = analyticVelocityX(value + dt * k3, wy, wz, denominator)
      value += dt * (k1 + 2.0 * k2 + 2.0 * k3 + k4) / 6.0
      step += 1
    value

  private def analyticVelocityX(x: Double, wy: Double, wz: Double, denominator: Double): Double =
    amplitude * math.sin(2.0 * math.Pi * x / denominator) * wy * wz

  private def registrationSignal(x: Double, y: Double, z: Double): Double =
    val scale = 2.0 * math.Pi / (side - 1).toDouble
    100.0 + 17.0 * math.sin(scale * x) + 9.0 * math.cos(2.0 * scale * y) +
      6.0 * math.sin(scale * (x + 0.4 * z)) + 4.0 * math.cos(scale * (y + z))

  private def mapX[A, B](pull: DensePull[A, B], x: Int, y: Int, z: Int): Double =
    pull.sourceCoordinates.linearComponent(
      x + side * y + side * side * z,
      0
    )

  private def checksum(flow: PairedFlow[Work]): Double =
    val forward = flow.pair.forward.sourceCoordinates
    val backward = flow.pair.backward.sourceCoordinates
    val n = frame.grid.nVoxels
    forward.linearComponent(n / 3, 0) +
      forward.linearComponent(n / 2, 1) +
      backward.linearComponent(2 * n / 3, 2) +
      flow.diagnostics.plusValid.toDouble + flow.diagnostics.minusValid.toDouble

  private def featureChecksum(features: T1FeatureVolume[Work]): Double =
    val n = frame.grid.nVoxels
    features.values(n / 2) + features.values(n + n / 3) + features.gradients(6 * n + 2 * n / 3)

  private def localChecksum(result: LocalLmResult[Work]): Double =
    val values = result.rawVelocity.field
    val n = frame.grid.nVoxels
    values.linearComponent(n / 2, 0) +
      values.linearComponent(n / 3, 1) +
      values.linearComponent(2 * n / 3, 2) +
      result.summary.value + result.summary.maximumRawVelocityMm

  private def sobolevChecksum(result: SobolevResult[Work]): Double =
    val values = result.velocity.field
    val n = frame.grid.nVoxels
    values.linearComponent(n / 2, 0) +
      values.linearComponent(n / 3, 1) +
      values.linearComponent(2 * n / 3, 2) +
      result.diagnostics.totalIterations.toDouble + result.diagnostics.maximumRelativeResidual

  private def registrationChecksum(result: RegistrationResult[Fixed, Moving]): Double =
    val forward = result.transform.forward.sourceCoordinates
    val backward = result.transform.backward.sourceCoordinates
    val n = result.transform.forward.from.grid.nVoxels
    forward.linearComponent(n / 2, 0) +
      forward.linearComponent(n / 3, 1) +
      backward.linearComponent(2 * n / 3, 2) +
      result.diagnostics.acceptedSteps.toDouble + result.diagnostics.levels.last.finalValue
