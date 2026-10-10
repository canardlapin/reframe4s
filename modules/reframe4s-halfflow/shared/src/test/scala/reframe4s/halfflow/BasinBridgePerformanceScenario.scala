package reframe4s.halfflow

import reframe4s.halfflow.internal.*

/** One deterministic performance scenario shared by the JVM and Scala.js probes.
  *
  * The scenario owns setup validation and checksums; platform front ends only
  * supply clocks and allocation instrumentation. This prevents the two probes
  * from silently benchmarking different registration paths.
  */
object BasinBridgePerformanceScenario:
  sealed trait Work
  sealed trait Fixed
  sealed trait Moving

  final case class Setup(
      side: Int,
      grid: GridSpec,
      fixed: RegistrationImage[Fixed],
      moving: RegistrationImage[Moving],
      initial: ForwardMidpoint[Work, Fixed, Moving],
      anchors: Vector[BasinBridgePoint],
      oracleMatches: BasinBridgeWorkMatches,
      config: BasinBridgeRoundConfig
  )

  def make(side: Int): Setup =
    require(side >= 9, "performance scenario side must be at least nine")
    val grid = GridSpec.identity(Vector(side, side, side))
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("basin-bridge-performance-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("basin-bridge-performance-moving"), grid)
    val workFrame = RegistrationFrame[Work](SpatialDomainId("basin-bridge-performance-work"), grid)
    val fixed = image(fixedFrame, shiftX = 0.0, "basin-bridge-performance-fixed")
    val moving = image(movingFrame, shiftX = 2.0, "basin-bridge-performance-moving")
    val initial = right(ForwardMidpoint.identity(workFrame, fixedFrame, movingFrame))
    val anchors = Vector(
      point(8.0, 8.0, 8.0),
      point(side.toDouble / 2.0, side.toDouble / 2.0, side.toDouble / 2.0),
      point(side.toDouble - 8.0, side.toDouble - 8.0, side.toDouble - 8.0)
    )
    val oracleMatches = right(
      BasinBridgeWorkMatches.fromVector(
        anchors.map(anchor => correspondence(anchor.x, anchor.y, anchor.z))
      )
    )
    val search = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(3, 2, 2),
        minimumValidFraction = 0.8
      )
    )
    val config = BasinBridgeRoundConfig.make(
      search,
      projector = right(BasinBridgeProjectorConfig.make(sigmaMm = 1.5)),
      cc = right(BasinBridgeCcObjectiveConfig.make(maximumStepMm = 4.0))
    )
    val setup = Setup(side, grid, fixed, moving, initial, anchors, oracleMatches, config)
    val native = right(BasinBridgeRound.run(setup.fixed, setup.moving, setup.initial, setup.anchors, setup.config))
    require(native.assimilation.accepted, s"native performance setup rejected: ${native.assimilation.trials}")
    require(native.rematch.nonEmpty, "native performance setup did not perform its mandatory rematch")
    val oracle = right(
      BasinBridgeRound.runWithWorkMatches(
        setup.fixed,
        setup.moving,
        setup.initial,
        setup.oracleMatches,
        setup.config
      )
    )
    require(oracle.assimilation.accepted, s"oracle performance setup rejected: ${oracle.assimilation.trials}")
    require(oracle.diagnostics.finalObjective.weightedMatchErrorMm < 1e-6)
    setup

  def nativeRound(setup: Setup): Double =
    val result = right(BasinBridgeRound.run(setup.fixed, setup.moving, setup.initial, setup.anchors, setup.config))
    require(result.assimilation.accepted && result.rematch.nonEmpty)
    checksum(result, setup.grid)

  def oracleRound(setup: Setup): Double =
    val result = right(
      BasinBridgeRound.runWithWorkMatches(
        setup.fixed,
        setup.moving,
        setup.initial,
        setup.oracleMatches,
        setup.config
      )
    )
    require(result.assimilation.accepted)
    checksum(result, setup.grid)

  private def checksum(
      result: BasinBridgeRoundResult[Work, Fixed, Moving],
      grid: GridSpec
  ): Double =
    val n = grid.nVoxels
    val fixedMap = result.state.fixed.residual.sourceCoordinates
    val movingMap = result.state.moving.residual.sourceCoordinates
    fixedMap.linearComponent(n / 2, 0) +
      movingMap.linearComponent(n / 3, 0) +
      result.diagnostics.finalObjective.weightedMatchErrorMm +
      result.assimilation.acceptedAlpha.getOrElse(0.0)

  private def image[A](frame: RegistrationFrame[A], shiftX: Double, label: String): RegistrationImage[A] =
    val grid = frame.grid
    val values = Array.tabulate[Double](grid.nVoxels): index =>
      val x = index % grid.shape(0)
      val yz = index / grid.shape(0)
      val y = yz % grid.shape(1)
      val z = yz / grid.shape(1)
      pattern(x.toDouble - shiftX, y.toDouble, z.toDouble)
    right(
      RegistrationImage.make(
        frame,
        NeuroVol.fromLinear[Double](values, grid.toNeuroSpace, label)
      )
    )

  private def correspondence(x: Double, y: Double, z: Double): BasinBridgeCorrespondence =
    right(BasinBridgeCorrespondence.make(point(x, y, z), point(x + 2.0, y, z), 1.0))

  private def point(x: Double, y: Double, z: Double): BasinBridgePoint =
    right(BasinBridgePoint.make(x, y, z))

  private def pattern(x: Double, y: Double, z: Double): Double =
    math.sin(0.21 * x + 0.07 * y) +
      math.cos(0.17 * z - 0.13 * x) +
      0.01 * x * y +
      0.003 * y * z

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => throw new IllegalArgumentException(error.toString)
