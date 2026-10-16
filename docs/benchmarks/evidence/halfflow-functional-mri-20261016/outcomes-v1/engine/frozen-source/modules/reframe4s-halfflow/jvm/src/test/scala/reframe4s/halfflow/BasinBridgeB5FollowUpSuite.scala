package reframe4s.halfflow

import gale.linalg.DMat
import reframe4s.halfflow.internal.*

/** JVM-only B5 experiment: compute on a true halo and evaluate the core only
  * after the padded transform has passed its topology/export checks.
  *
  * The fixed optimization mask is the requested field of view. The analytic
  * images still exist on the padded grid, but the halo contributes no image
  * force. This keeps the experiment distinct from boundary tapering or a
  * relaxed topology floor.
  */
class BasinBridgeB5FollowUpSuite extends munit.FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(10, "minutes")

  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving

  test("true computational halo does not widen the frozen oracle 8/12 mm fine matrix"):
    Vector(8.0, 12.0).foreach: shiftMm =>
      val input = haloCase(coreSide = 33, halo = 16, shiftMm, s"halo-$shiftMm")
      val result = right(
        BasinBridgeDecomposition.runCase(
          input,
          BasinBridgeDecompositionLane.OracleToBridgeToHalfFlow,
          BasinBridgeDecompositionConfig(
            wideRoundConfig(),
            budget2xPlan(),
            ForwardGeometryConfig(interiorMargin = 3)
          )
        )
      )
      assert(result.bridge.assimilation.accepted, s"halo $shiftMm mm bridge rejected")
      assert(result.bridge.diagnostics.finalObjective.weightedMatchErrorMm < 1e-6)
      assert(
        result.fineFailure.exists(_.isInstanceOf[HalfFlowCcError.RegriddedTopologyInvalid]),
        s"halo $shiftMm mm unexpectedly changed the fine-stage decision: ${result.fineFailure}"
      )

  test("fixed-anchor midpoint action does not widen the exact-oracle 8/12 mm basin"):
    Vector(8.0, 12.0).foreach: shiftMm =>
      val input = haloCase(coreSide = 49, halo = 0, shiftMm, s"fixed-anchor-$shiftMm")
      val result = right(
        BasinBridgeDecomposition.runCase(
          input,
          BasinBridgeDecompositionLane.OracleToBridgeToHalfFlow,
          BasinBridgeDecompositionConfig(
            wideRoundConfig(),
            budget2xPlan(HalfFlowCcAction.FixedAnchor),
            ForwardGeometryConfig(interiorMargin = 3)
          )
        )
      )
      assert(result.bridge.assimilation.accepted, s"fixed-anchor $shiftMm mm bridge rejected")
      assert(
        result.fineFailure.exists(_.isInstanceOf[HalfFlowCcError.RegriddedTopologyInvalid]),
        s"fixed-anchor $shiftMm mm unexpectedly changed the fine-stage decision: ${result.fineFailure}"
      )

  private def haloCase(
      coreSide: Int,
      halo: Int,
      shiftMm: Double,
      tag: String
  ): BasinBridgeDecompositionCase[Work, Fixed, Moving] =
    val grid = paddedGrid(coreSide, halo)
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId(s"$tag-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId(s"$tag-moving"), grid)
    val workFrame = RegistrationFrame[Work](SpatialDomainId(s"$tag-work"), grid)
    val fixed = image(
      fixedFrame,
      grid,
      shiftMm = 0.0,
      FieldValidity.copyMask(fixedMask(coreSide, halo, grid)),
      s"$tag-fixed"
    )
    val moving = image(movingFrame, grid, shiftMm, FieldValidity.All, s"$tag-moving")
    val initial = right(ForwardMidpoint.identity(workFrame, fixedFrame, movingFrame))
    val coordinates = Vector(8.0, 16.0, 24.0)
    val points = coordinates.map(coordinate => point(coordinate, coordinate, coordinate))
    val matches = right(
      BasinBridgeWorkMatches.fromVector(
        coordinates.map(coordinate => correspondence(coordinate, coordinate, coordinate, shiftMm))
      )
    )
    BasinBridgeDecompositionCase(tag, fixed, moving, initial, points, matches)

  private def paddedGrid(coreSide: Int, halo: Int): GridSpec =
    val shift = -halo.toDouble
    GridSpec(
      Vector(coreSide + 2 * halo, coreSide + 2 * halo, coreSide + 2 * halo),
      DMat.dense(4, 4, (Vector(
          Vector(1.0, 0.0, 0.0, shift),
          Vector(0.0, 1.0, 0.0, shift),
          Vector(0.0, 0.0, 1.0, shift),
          Vector(0.0, 0.0, 0.0, 1.0)
        )).flatten)
    )

  private def fixedMask(coreSide: Int, halo: Int, grid: GridSpec): Array[Boolean] =
    Array.tabulate(grid.nVoxels): index =>
      val x = index % grid.shape(0)
      val yz = index / grid.shape(0)
      val y = yz % grid.shape(1)
      val z = yz / grid.shape(1)
      x >= halo && x < halo + coreSide && y >= halo && y < halo + coreSide &&
        z >= halo && z < halo + coreSide

  private def image[A](
      frame: RegistrationFrame[A],
      grid: GridSpec,
      shiftMm: Double,
      validity: FieldValidity,
      label: String
  ): RegistrationImage[A] =
    val values = Array.tabulate[Double](grid.nVoxels): index =>
      val x = index % grid.shape(0)
      val yz = index / grid.shape(0)
      val y = yz % grid.shape(1)
      val z = yz / grid.shape(1)
      val world = grid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))
      pattern(world(0) - shiftMm, world(1), world(2))
    right(
      RegistrationImage.make(
        frame,
        NeuroVol.fromLinear[Double](values, grid.toNeuroSpace, label),
        validity
      )
    )

  private def wideRoundConfig(): BasinBridgeRoundConfig =
    BasinBridgeRoundConfig.make(
      right(
        BasinBridgeBlockSearchConfig.make(
          VoxelWindowRadius(1, 1, 1),
          VoxelWindowRadius(16, 16, 16),
          minimumValidFraction = 0.8
        )
      ),
      projector = right(BasinBridgeProjectorConfig.make(sigmaMm = 1.5)),
      cc = right(BasinBridgeCcObjectiveConfig.make(maximumStepMm = 16.0))
    )

  private def budget2xPlan(action: HalfFlowCcAction = HalfFlowCcAction.SymmetricMidpoint): HalfFlowCcPlan =
    val cc = right(
      NeighborhoodCcConfig.make(
        radius = VoxelWindowRadius(2, 2, 2),
        minimumSupportFraction = 0.15,
        fullSupportFraction = 0.7,
        minimumVarianceFraction = 1e-7,
        fullVarianceFraction = 1e-5,
        denominatorEpsilonFraction = 1e-7
      )
    )
    val levels = Vector(
      right(
        HalfFlowCcLevel.make(
          shrink = 2,
          pyramidSigmaMm = 1.0,
          cc = cc,
          smoothSigmaMm = 10.0,
          maximumStepMm = 1.0,
          targetAcceptedSteps = 10,
          maximumAttempts = 24
        )
      ),
      right(
        HalfFlowCcLevel.make(
          shrink = 1,
          pyramidSigmaMm = 0.0,
          cc = cc,
          smoothSigmaMm = 6.0,
          maximumStepMm = 0.6,
          targetAcceptedSteps = 10,
          maximumAttempts = 24
        )
      )
    )
    val control = right(
      HalfFlowCcControlConfig.make(
        initialDamping = 1e-4,
        minimumDamping = 1e-8,
        maximumDamping = 1.0,
        maximumObjectiveRetries = 6,
        maximumGeometryRetries = 8,
        maximumIntegrationRetries = 5
      )
    )
    val exportConfig = right(
      ResidualInverseConfig.make(
        shrinks = Vector(2, 1),
        iterationsPerLevel = 50,
        maximumInteriorErrorMm = 0.2,
        maximumInteriorErrorVox = 0.2,
        interiorMargin = 6
      )
    )
    right(
      HalfFlowCcPlan.make(
        levels,
        supportSigmaMm = 1.5,
        minimumUsefulStepMm = 1e-6,
        maximumIntegrationInverseErrorMm = 0.03,
        control = control,
        exportConfig = exportConfig,
        action = action
      )
    )

  private def correspondence(
      x: Double,
      y: Double,
      z: Double,
      shiftX: Double
  ): BasinBridgeCorrespondence =
    right(
      BasinBridgeCorrespondence.make(
        point(x, y, z),
        point(x + shiftX, y, z),
        1.0
      )
    )

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
      case Left(error) => fail(error.toString)

