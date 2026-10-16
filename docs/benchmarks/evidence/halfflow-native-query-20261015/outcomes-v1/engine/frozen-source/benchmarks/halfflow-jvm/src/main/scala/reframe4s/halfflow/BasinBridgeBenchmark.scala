package reframe4s.halfflow

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized
import reframe4s.halfflow.internal.*

/** JVM performance guardrail for one deterministic BasinBridge round.
  *
  * The benchmark measures the actual allocation-owning native round and the
  * supplied-match oracle round separately. Setup validates that both paths
  * accept the same two-millimetre synthetic translation and that the native
  * path performs its mandatory rematch. It is a runtime baseline, not an
  * accuracy or external-data admission.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class BasinBridgeBenchmark:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving

  @Param(Array("25"))
  var side: Int = 0

  private var grid: GridSpec = uninitialized
  private var fixed: RegistrationImage[Fixed] = uninitialized
  private var moving: RegistrationImage[Moving] = uninitialized
  private var initial: ForwardMidpoint[Work, Fixed, Moving] = uninitialized
  private var anchors: Vector[BasinBridgePoint] = uninitialized
  private var oracleMatches: BasinBridgeWorkMatches = uninitialized
  private var config: BasinBridgeRoundConfig = uninitialized
  private var oracleConfig: BasinBridgeRoundConfig = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    grid = GridSpec.identity(Vector(side, side, side))
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("basin-bridge-benchmark-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("basin-bridge-benchmark-moving"), grid)
    val workFrame = RegistrationFrame[Work](SpatialDomainId("basin-bridge-benchmark-work"), grid)
    fixed = image(fixedFrame, shiftX = 0.0, "basin-bridge-benchmark-fixed")
    moving = image(movingFrame, shiftX = 2.0, "basin-bridge-benchmark-moving")
    initial = right(ForwardMidpoint.identity(workFrame, fixedFrame, movingFrame))
    anchors = Vector(point(8.0, 8.0, 8.0), point(12.0, 12.0, 12.0), point(16.0, 16.0, 16.0))
    oracleMatches = right(
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
    config = BasinBridgeRoundConfig.make(
      search,
      projector = right(BasinBridgeProjectorConfig.make(sigmaMm = 1.5)),
      cc = right(BasinBridgeCcObjectiveConfig.make(maximumStepMm = 4.0))
    )
    oracleConfig = config
    val native = right(BasinBridgeRound.run(fixed, moving, initial, anchors, config))
    require(native.assimilation.accepted, s"native benchmark setup rejected: ${native.assimilation.trials}")
    require(native.rematch.nonEmpty, "native benchmark setup did not rematch")
    val oracle = right(BasinBridgeRound.runWithWorkMatches(fixed, moving, initial, oracleMatches, oracleConfig))
    require(oracle.assimilation.accepted, s"oracle benchmark setup rejected: ${oracle.assimilation.trials}")
    require(oracle.diagnostics.finalObjective.weightedMatchErrorMm < 1e-6)

  @Benchmark
  def nativeRound(): Double =
    val result = right(BasinBridgeRound.run(fixed, moving, initial, anchors, config))
    require(result.assimilation.accepted && result.rematch.nonEmpty)
    checksum(result)

  @Benchmark
  def oracleRound(): Double =
    val result = right(BasinBridgeRound.runWithWorkMatches(fixed, moving, initial, oracleMatches, oracleConfig))
    require(result.assimilation.accepted)
    checksum(result)

  private def checksum(result: BasinBridgeRoundResult[Work, Fixed, Moving]): Double =
    val n = grid.nVoxels
    val fixedMap = result.state.fixed.residual.sourceCoordinates
    val movingMap = result.state.moving.residual.sourceCoordinates
    fixedMap.linearComponent(n / 2, 0) +
      movingMap.linearComponent(n / 3, 0) +
      result.diagnostics.finalObjective.weightedMatchErrorMm +
      result.assimilation.acceptedAlpha.getOrElse(0.0)

  private def image[A](frame: RegistrationFrame[A], shiftX: Double, label: String): RegistrationImage[A] =
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
