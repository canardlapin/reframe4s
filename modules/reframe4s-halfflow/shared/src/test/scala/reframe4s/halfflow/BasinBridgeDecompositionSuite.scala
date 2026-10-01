package reframe4s.halfflow

import reframe4s.halfflow.internal.*

class BasinBridgeDecompositionSuite extends munit.FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(5, "minutes")

  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving

  test("B4 runner executes all three frozen lanes and retains stage metrics"):
    val grid = GridSpec.identity(Vector(25, 25, 25))
    val fixedFrame = Frame[Fixed](SpatialDomainId("decomp-fixed"), grid)
    val movingFrame = Frame[Moving](SpatialDomainId("decomp-moving"), grid)
    val workFrame = Frame[Work](SpatialDomainId("decomp-work"), grid)
    val fixed = image(fixedFrame, grid, 0.0, "decomp-fixed")
    val moving = image(movingFrame, grid, 2.0, "decomp-moving")
    val initial = right(ForwardMidpoint.identity(workFrame, fixedFrame, movingFrame))
    val oracle = right(
      BasinBridgeWorkMatches.fromVector(
        Vector(
          correspondence(8.0, 8.0, 8.0),
          correspondence(12.0, 12.0, 12.0),
          correspondence(16.0, 16.0, 16.0)
        )
      )
    )
    val search = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(3, 2, 2),
        minimumValidFraction = 0.8
      )
    )
    val level = right(
      HalfFlowCcLevel.make(
        shrink = 1,
        pyramidSigmaMm = 0.0,
        smoothSigmaMm = 1.0,
        maximumStepMm = 0.5,
        targetAcceptedSteps = 1,
        maximumAttempts = 2
      )
    )
    val plan = right(
      HalfFlowCcPlan.make(
        Vector(level),
        supportSigmaMm = 1.5,
        geometry = ForwardGeometryConfig(interiorMargin = 3)
      )
    )
    val config = BasinBridgeDecompositionConfig(
      BasinBridgeRoundConfig.make(
        search,
        projector = right(BasinBridgeProjectorConfig.make(sigmaMm = 1.5)),
        cc = right(BasinBridgeCcObjectiveConfig.make(maximumStepMm = 4.0))
      ),
      plan,
      ForwardGeometryConfig(interiorMargin = 3)
    )
    val input = BasinBridgeDecompositionCase(
      "translation-2mm",
      fixed,
      moving,
      initial,
      Vector(point(8.0, 8.0, 8.0), point(12.0, 12.0, 12.0), point(16.0, 16.0, 16.0)),
      oracle
    )
    val results = right(BasinBridgeDecomposition.runAll(input, config))

    assertEquals(results.map(_.lane), BasinBridgeDecompositionLane.values.toVector)
    assert(results.forall(_.bridgeMetrics.residuals.p95Mm.isFinite))
    assert(results.forall(_.finalMetrics.trueCcLoss.isFinite))
    assert(results.forall(_.finalMetrics.topology.nonPositiveJacobians == 0))
    assertEquals(
      results.find(_.lane == BasinBridgeDecompositionLane.OracleToBridge).get.bridgeMetrics.rematchRounds,
      0
    )
    assertEquals(
      results.find(_.lane == BasinBridgeDecompositionLane.BlockToBridgeToHalfFlow).get.bridgeMetrics.rematchRounds,
      1
    )

  test("weighted residual percentiles preserve confidence ordering"):
    val percentiles = right(
      BasinBridgeResidualPercentiles.from(
        Vector(
          BasinBridgeMatchResidual(1.0, 0.1),
          BasinBridgeMatchResidual(2.0, 0.8),
          BasinBridgeMatchResidual(10.0, 0.1)
        )
      )
    )
    assertEqualsDouble(percentiles.p50Mm, 2.0, 0.0)
    assertEqualsDouble(percentiles.p95Mm, 10.0, 0.0)
    assertEqualsDouble(percentiles.weightedMeanMm, 2.7, 1e-12)

  test("native B4 lane captures the frozen 2/4/8/12 mm translation matrix"):
    Vector(2.0, 4.0, 8.0, 12.0).foreach: shiftMm =>
      val grid = GridSpec.identity(Vector(49, 49, 49))
      val fixedFrame = Frame[Fixed](SpatialDomainId(s"matrix-fixed-$shiftMm"), grid)
      val movingFrame = Frame[Moving](SpatialDomainId(s"matrix-moving-$shiftMm"), grid)
      val workFrame = Frame[Work](SpatialDomainId(s"matrix-work-$shiftMm"), grid)
      val fixed = image(fixedFrame, grid, 0.0, s"matrix-fixed-$shiftMm")
      val moving = image(movingFrame, grid, shiftMm, s"matrix-moving-$shiftMm")
      val initial = right(ForwardMidpoint.identity(workFrame, fixedFrame, movingFrame))
      val roundConfig = BasinBridgeRoundConfig.make(
        right(
          BasinBridgeBlockSearchConfig.make(
            VoxelWindowRadius(1, 1, 1),
            VoxelWindowRadius(12, 12, 12),
            minimumValidFraction = 0.8
          )
        ),
        projector = right(BasinBridgeProjectorConfig.make(sigmaMm = 1.5)),
        cc = right(BasinBridgeCcObjectiveConfig.make(maximumStepMm = 16.0))
      )
      val level = right(
        HalfFlowCcLevel.make(
          shrink = 1,
          pyramidSigmaMm = 0.0,
          smoothSigmaMm = 1.0,
          maximumStepMm = 0.5,
          targetAcceptedSteps = 1,
          maximumAttempts = 2
        )
      )
      val finePlan = right(
        HalfFlowCcPlan.make(
          Vector(level),
          supportSigmaMm = 1.5,
          geometry = ForwardGeometryConfig(interiorMargin = 3)
        )
      )
      val config = BasinBridgeDecompositionConfig(
        roundConfig,
        finePlan,
        ForwardGeometryConfig(interiorMargin = 3)
      )
      val input = BasinBridgeDecompositionCase(
        s"translation-${shiftMm.toInt}mm",
        fixed,
        moving,
        initial,
        Vector(point(16.0, 16.0, 16.0), point(24.0, 24.0, 24.0), point(32.0, 32.0, 32.0)),
        right(
          BasinBridgeWorkMatches.fromVector(
            Vector(
              correspondence(16.0, 16.0, 16.0, shiftMm),
              correspondence(24.0, 24.0, 24.0, shiftMm),
              correspondence(32.0, 32.0, 32.0, shiftMm)
            )
          )
        )
      )
      val lane = right(
        BasinBridgeDecomposition.runCase(
          input,
          BasinBridgeDecompositionLane.BlockToBridgeToHalfFlow,
          config
        )
      )
      val result = lane.bridge
      assert(result.assimilation.accepted, s"native $shiftMm mm bridge rejected")
      assert(result.rematch.nonEmpty, s"native $shiftMm mm bridge did not rematch")
      assert(result.diagnostics.finalObjective.weightedMatchErrorMm < 1e-6)
      if shiftMm == 12.0 then
        assert(
          lane.fineFailure.exists(_.isInstanceOf[HalfFlowCcError.RegriddedTopologyInvalid]),
          s"12 mm fine-stage failure was not retained: ${lane.fineFailure}"
        )
      else
        assert(lane.fineFailure.isEmpty, s"${shiftMm} mm fine stage failed: ${lane.fineFailure}")
      assert(lane.finalMetrics.trueCcLoss.isFinite)
      assert(lane.finalMetrics.topology.nonPositiveJacobians == 0)
      val pre = right(
        BasinBridgeResidualPercentiles.from(
          right(BasinBridgeObjective.correspondenceResiduals(initial, result.evidence.endpoint.values))
        )
      )
      val post = right(
        BasinBridgeResidualPercentiles.from(
          right(BasinBridgeObjective.correspondenceResiduals(result.state, result.evidence.endpoint.values))
        )
      )
      assert(pre.p95Mm >= post.p95Mm)
      assert(post.p95Mm.isFinite)

  test("exact 12 mm oracle separates bridge success from fine-stage topology rejection"):
    val shiftMm = 12.0
    val grid = GridSpec.identity(Vector(33, 33, 33))
    val fixedFrame = Frame[Fixed](SpatialDomainId("oracle-12-fixed"), grid)
    val movingFrame = Frame[Moving](SpatialDomainId("oracle-12-moving"), grid)
    val workFrame = Frame[Work](SpatialDomainId("oracle-12-work"), grid)
    val fixed = image(fixedFrame, grid, 0.0, "oracle-12-fixed")
    val moving = image(movingFrame, grid, shiftMm, "oracle-12-moving")
    val initial = right(ForwardMidpoint.identity(workFrame, fixedFrame, movingFrame))
    val roundConfig = BasinBridgeRoundConfig.make(
      right(
        BasinBridgeBlockSearchConfig.make(
          VoxelWindowRadius(1, 1, 1),
          VoxelWindowRadius(12, 12, 12),
          minimumValidFraction = 0.8
        )
      ),
      projector = right(BasinBridgeProjectorConfig.make(sigmaMm = 1.5)),
      cc = right(BasinBridgeCcObjectiveConfig.make(maximumStepMm = 16.0))
    )
    val config = BasinBridgeDecompositionConfig(
      roundConfig,
      budget2xPlan(),
      ForwardGeometryConfig(interiorMargin = 3)
    )
    val oracle = right(
      BasinBridgeWorkMatches.fromVector(
        Vector(
          correspondence(6.0, 6.0, 6.0, shiftMm),
          correspondence(12.0, 12.0, 12.0, shiftMm),
          correspondence(18.0, 18.0, 18.0, shiftMm)
        )
      )
    )
    val input = BasinBridgeDecompositionCase(
      "oracle-translation-12mm",
      fixed,
      moving,
      initial,
      Vector(point(6.0, 6.0, 6.0), point(12.0, 12.0, 12.0), point(18.0, 18.0, 18.0)),
      oracle
    )
    val bridgeOnly = right(
      BasinBridgeDecomposition.runCase(
        input,
        BasinBridgeDecompositionLane.OracleToBridge,
        config
      )
    )
    assert(bridgeOnly.fineFailure.isEmpty)
    assert(bridgeOnly.bridge.assimilation.accepted)
    val bridgeAndFine = right(
      BasinBridgeDecomposition.runCase(
        input,
        BasinBridgeDecompositionLane.OracleToBridgeToHalfFlow,
        config
      )
    )
    assert(bridgeAndFine.bridge.assimilation.accepted)
    assert(
      bridgeAndFine.fineFailure.exists(_.isInstanceOf[HalfFlowCcError.RegriddedTopologyInvalid]),
      s"oracle 12 mm fine-stage failure was not retained: ${bridgeAndFine.fineFailure}"
    )
    assertEquals(bridgeAndFine.finalState, bridgeAndFine.bridge.state)

  test("oracle B4 budget2x lane covers the complete 2/4/8/12 mm matrix"):
    Vector(2.0, 4.0, 8.0, 12.0).foreach: shiftMm =>
      val input = oracleCase(side = 49, shiftMm, s"oracle-matrix-$shiftMm")
      val config = BasinBridgeDecompositionConfig(
        wideRoundConfig(),
        budget2xPlan(),
        ForwardGeometryConfig(interiorMargin = 3)
      )
      val bridgeOnly = right(
        BasinBridgeDecomposition.runCase(
          input,
          BasinBridgeDecompositionLane.OracleToBridge,
          config
        )
      )
      val bridgeAndFine = right(
        BasinBridgeDecomposition.runCase(
          input,
          BasinBridgeDecompositionLane.OracleToBridgeToHalfFlow,
          config
        )
      )
      assert(bridgeOnly.bridge.assimilation.accepted, s"oracle $shiftMm mm bridge rejected")
      assert(bridgeOnly.fineFailure.isEmpty)
      assert(bridgeAndFine.bridge.assimilation.accepted)
      if shiftMm >= 8.0 then
        assert(
          bridgeAndFine.fineFailure.exists(_.isInstanceOf[HalfFlowCcError.RegriddedTopologyInvalid]),
          s"oracle $shiftMm mm should retain a fine topology failure"
        )
      else
        assert(bridgeAndFine.fineFailure.isEmpty, s"oracle $shiftMm mm fine failure: ${bridgeAndFine.fineFailure}")

  private def correspondence(
      x: Double,
      y: Double,
      z: Double,
      shiftX: Double = 2.0
  ): BasinBridgeCorrespondence =
    right(
      BasinBridgeCorrespondence.make(
        point(x, y, z),
        point(x + shiftX, y, z),
        1.0
      )
    )

  private def oracleCase(
      side: Int,
      shiftMm: Double,
      tag: String
  ): BasinBridgeDecompositionCase[Work, Fixed, Moving] =
    val grid = GridSpec.identity(Vector(side, side, side))
    val fixedFrame = Frame[Fixed](SpatialDomainId(s"$tag-fixed"), grid)
    val movingFrame = Frame[Moving](SpatialDomainId(s"$tag-moving"), grid)
    val workFrame = Frame[Work](SpatialDomainId(s"$tag-work"), grid)
    val fixed = image(fixedFrame, grid, 0.0, s"$tag-fixed")
    val moving = image(movingFrame, grid, shiftMm, s"$tag-moving")
    val initial = right(ForwardMidpoint.identity(workFrame, fixedFrame, movingFrame))
    val q = Vector(16.0, 24.0, 32.0)
    val points = q.map(coordinate => point(coordinate, coordinate, coordinate))
    val matches = right(
      BasinBridgeWorkMatches.fromVector(
        q.map(coordinate => correspondence(coordinate, coordinate, coordinate, shiftMm))
      )
    )
    BasinBridgeDecompositionCase(
      tag,
      fixed,
      moving,
      initial,
      points,
      matches
    )

  private def wideRoundConfig(): BasinBridgeRoundConfig =
    BasinBridgeRoundConfig.make(
      right(
        BasinBridgeBlockSearchConfig.make(
          VoxelWindowRadius(1, 1, 1),
          VoxelWindowRadius(12, 12, 12),
          minimumValidFraction = 0.8
        )
      ),
      projector = right(BasinBridgeProjectorConfig.make(sigmaMm = 1.5)),
      cc = right(BasinBridgeCcObjectiveConfig.make(maximumStepMm = 16.0))
    )

  private def image[A](
      frame: Frame[A],
      grid: GridSpec,
      shiftX: Double,
      label: String
  ): RegistrationImage[A] =
    val values = Array.tabulate[Double](grid.nVoxels): index =>
      val x = index % grid.shape.x
      val yz = index / grid.shape.x
      val y = yz % grid.shape.y
      val z = yz / grid.shape.y
      pattern(x.toDouble - shiftX, y.toDouble, z.toDouble)
    right(RegistrationImage.make(
      frame,
      NeuroVol.fromLinear[Double](values, grid.toNeuroSpace, label)
    ))

  private def budget2xPlan(): HalfFlowCcPlan =
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
        action = HalfFlowCcAction.SymmetricMidpoint
      )
    )

  private def pattern(x: Double, y: Double, z: Double): Double =
    math.sin(0.21 * x + 0.07 * y) +
      math.cos(0.17 * z - 0.13 * x) +
      0.01 * x * y +
      0.003 * y * z

  private def point(x: Double, y: Double, z: Double): BasinBridgePoint =
    right(BasinBridgePoint.make(x, y, z))

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(error.toString)
