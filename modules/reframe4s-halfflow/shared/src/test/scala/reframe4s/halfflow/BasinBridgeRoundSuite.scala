package reframe4s.halfflow

import reframe4s.halfflow.internal.*

class BasinBridgeRoundSuite extends munit.FunSuite:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving

  test("endpoint lifting preserves distinct fixed and moving affine frames"):
    val grid = GridSpec.identity(Vector(17, 17, 17))
    val work = Frame[Work](SpatialDomainId("round-work"), grid)
    val fixed = Frame[Fixed](SpatialDomainId("round-fixed"), grid)
    val moving = Frame[Moving](SpatialDomainId("round-moving"), grid)
    val fixedAffine = right(
      AffineIso.make(
        work,
        fixed,
        Affine3D.fromRows(
          Vector(
            Vector(1.0, 0.0, 0.0, 2.0),
            Vector(0.0, 1.0, 0.0, 0.0),
            Vector(0.0, 0.0, 1.0, 0.0),
            Vector(0.0, 0.0, 0.0, 1.0)
          )
        ).fold(error => fail(error.message), identity)
      )
    )
    val movingAffine = right(
      AffineIso.make(
        work,
        moving,
        Affine3D.fromRows(
          Vector(
            Vector(1.0, 0.0, 0.0, 5.0),
            Vector(0.0, 1.0, 0.0, 0.0),
            Vector(0.0, 0.0, 1.0, 0.0),
            Vector(0.0, 0.0, 0.0, 1.0)
          )
        ).fold(error => fail(error.message), identity)
      )
    )
    val state = right(
      ForwardMidpoint.make(
        ForwardMidpointArm.identity(fixedAffine),
        ForwardMidpointArm.identity(movingAffine)
      )
    )
    val workMatch = right(
      BasinBridgeCorrespondence.make(
        point(4.0, 8.0, 8.0),
        point(6.0, 8.0, 8.0),
        1.0
      )
    )
    val workMatches = right(BasinBridgeWorkMatches.fromVector(Vector(workMatch)))
    val endpoint = right(BasinBridgeRound.liftEndpointMatches(state, workMatches))

    assertEquals(endpoint.values.length, 1)
    assertPoint(endpoint.values.head.fixed, 6.0, 8.0, 8.0)
    assertPoint(endpoint.values.head.moving, 11.0, 8.0, 8.0)
    assertEqualsDouble(endpoint.values.head.confidence, 1.0, 0.0)

  test("native round uses true frozen CC and rematches after acceptance"):
    val grid = GridSpec.identity(Vector(25, 25, 25))
    val fixedFrame = Frame[Fixed](SpatialDomainId("round-fixed-image"), grid)
    val movingFrame = Frame[Moving](SpatialDomainId("round-moving-image"), grid)
    val fixed = image(fixedFrame, grid, shiftX = 0.0, "round-fixed")
    val moving = image(movingFrame, grid, shiftX = 2.0, "round-moving")
    val state = right(
      ForwardMidpoint.identity(
        Frame[Work](SpatialDomainId("round-image-work"), grid),
        fixedFrame,
        movingFrame
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
    val result = right(
      BasinBridgeRound.run(
        fixed,
        moving,
        state,
        Vector(
          point(8.0, 8.0, 8.0),
          point(12.0, 12.0, 12.0),
          point(8.0, 12.0, 8.0),
          point(12.0, 8.0, 12.0)
        ),
        config
      )
    )

    assert(result.assimilation.accepted, s"round did not accept: ${result.assimilation.trials}")
    assert(result.diagnostics.cc.supportPreparations == 1)
    assert(result.diagnostics.cc.candidateEvaluations > 0)
    assert(result.rematch.nonEmpty, "accepted round must rematch native sources")
    assert(result.diagnostics.rematched)
    val rematchSupport = result.diagnostics.rematchSupport.getOrElse(fail("rematch support diagnostics are required"))
    assertEquals(rematchSupport.requestedAnchors, 4)
    assertEquals(rematchSupport.retainedIndices, Vector(0, 1, 2, 3))
    assertEquals(rematchSupport.retainedAnchors, 4)
    assertEquals(rematchSupport.dropped, Vector.empty)
    assert(result.diagnostics.finalObjective.weightedMatchErrorMm < 1e-6)
    assert(result.diagnostics.finalObjective.trueCcLoss <= result.diagnostics.initialObjective.trueCcLoss)

  test("actual frozen CC rejects a sparse proposal that points away from the native alignment"):
    val grid = GridSpec.identity(Vector(25, 25, 25))
    val fixedFrame = Frame[Fixed](SpatialDomainId("round-reject-fixed"), grid)
    val movingFrame = Frame[Moving](SpatialDomainId("round-reject-moving"), grid)
    val fixed = image(fixedFrame, grid, shiftX = 0.0, "round-reject-fixed")
    val moving = image(movingFrame, grid, shiftX = 2.0, "round-reject-moving")
    val state = right(
      ForwardMidpoint.identity(
        Frame[Work](SpatialDomainId("round-reject-work"), grid),
        fixedFrame,
        movingFrame
      )
    )
    val wrongMatches = right(
      BasinBridgeWorkMatches.fromVector(
        Vector(
          right(
            BasinBridgeCorrespondence.make(
              point(8.0, 8.0, 8.0),
              point(6.0, 8.0, 8.0),
              1.0
            )
          ),
          right(
            BasinBridgeCorrespondence.make(
              point(12.0, 12.0, 12.0),
              point(10.0, 12.0, 12.0),
              1.0
            )
          )
        )
      )
    )
    val config = BasinBridgeRoundConfig.make(
      right(
        BasinBridgeBlockSearchConfig.make(
          VoxelWindowRadius(1, 1, 1),
          VoxelWindowRadius(3, 2, 2),
          minimumValidFraction = 0.8
        )
      ),
      projector = right(BasinBridgeProjectorConfig.make(sigmaMm = 1.5)),
      cc = right(BasinBridgeCcObjectiveConfig.make(maximumStepMm = 4.0))
    )

    val result = right(
      BasinBridgeRound.runWithWorkMatches(fixed, moving, state, wrongMatches, config)
    )

    assert(!result.assimilation.accepted)
    assert(
      result.assimilation.trials.exists(
        _.rejection.exists(_.isInstanceOf[BasinBridgeTrialRejection.CcLossIncreased])
      )
    )
    assertEquals(result.state, state)

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

  private def pattern(x: Double, y: Double, z: Double): Double =
    math.sin(0.21 * x + 0.07 * y) +
      math.cos(0.17 * z - 0.13 * x) +
      0.01 * x * y +
      0.003 * y * z

  private def point(x: Double, y: Double, z: Double): BasinBridgePoint =
    right(BasinBridgePoint.make(x, y, z))

  private def assertPoint(actual: BasinBridgePoint, x: Double, y: Double, z: Double): Unit =
    assertEqualsDouble(actual.x, x, 1e-10)
    assertEqualsDouble(actual.y, y, 1e-10)
    assertEqualsDouble(actual.z, z, 1e-10)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(error.toString)
