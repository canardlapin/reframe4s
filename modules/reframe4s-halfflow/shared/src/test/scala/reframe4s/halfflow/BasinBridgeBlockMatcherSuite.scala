package reframe4s.halfflow

import reframe4s.halfflow.internal.*

class BasinBridgeBlockMatcherSuite extends munit.FunSuite:
  sealed trait Fixed
  sealed trait Moving

  test("same-grid translation recovers the forward tangent and reverse cycle"):
    val grid = GridSpec.identity(Vector(17, 17, 17))
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("matcher-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("matcher-moving"), grid)
    val fixed = image(fixedFrame, grid, shiftX = 0.0, "fixed")
    val moving = image(movingFrame, grid, shiftX = 2.0, "moving")
    val config = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(3, 2, 2),
        minimumValidFraction = 0.8
      )
    )
    val anchor = point(8.0, 8.0, 8.0)
    val result = right(BasinBridgeBlockMatcher.search(fixed, moving, Vector(anchor), config))
    val correspondence = result.correspondences.head

    assertEquals(result.correspondences.length, 1)
    assertPoint(correspondence.tangent, 2.0, 0.0, 0.0, 1e-10)
    assertPoint(correspondence.midpoint, 9.0, 8.0, 8.0, 1e-10)
    assert(correspondence.confidence > 0.5, s"translation confidence=${correspondence.confidence}")
    assertEqualsDouble(correspondence.confidenceEvidence.cycleConsistency, 1.0, 1e-10)
    assertEquals(result.diagnostics.inputCount, 1)
    assertEquals(result.diagnostics.emittedCount, 1)

  test("swapping image roles recovers the negated tangent"):
    val grid = GridSpec.identity(Vector(17, 17, 17))
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("swap-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("swap-moving"), grid)
    val fixed = image(fixedFrame, grid, shiftX = 0.0, "fixed")
    val moving = image(movingFrame, grid, shiftX = 2.0, "moving")
    val config = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(3, 2, 2)
      )
    )
    val forward = right(
      BasinBridgeBlockMatcher.search(
        fixed,
        moving,
        Vector(point(8.0, 8.0, 8.0)),
        config
      )
    ).correspondences.head
    val reverse = right(
      BasinBridgeBlockMatcher.search(
        moving,
        fixed,
        Vector(point(10.0, 8.0, 8.0)),
        config
      )
    ).correspondences.head

    assertPoint(reverse.tangent, -forward.tangent.x, -forward.tangent.y, -forward.tangent.z, 1e-10)
    assertPoint(reverse.midpoint, forward.midpoint.x, forward.midpoint.y, forward.midpoint.z, 1e-10)
    assertEqualsDouble(reverse.confidence, forward.confidence, 1e-10)

  test("repeated search is deterministic"):
    val grid = GridSpec.identity(Vector(15, 15, 15))
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("det-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("det-moving"), grid)
    val fixed = image(fixedFrame, grid, shiftX = 0.0, "fixed")
    val moving = image(movingFrame, grid, shiftX = 1.0, "moving")
    val config = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(2, 2, 2)
      )
    )
    val anchors = Vector(point(6.0, 6.0, 6.0), point(8.0, 8.0, 8.0))
    val first = right(BasinBridgeBlockMatcher.search(fixed, moving, anchors, config))
    val second = right(BasinBridgeBlockMatcher.search(fixed, moving, anchors, config))

    first.correspondences.zip(second.correspondences).foreach { case (left, right) =>
      assertPoint(right.fixed, left.fixed.x, left.fixed.y, left.fixed.z, 0.0)
      assertPoint(right.moving, left.moving.x, left.moving.y, left.moving.z, 0.0)
      assertEqualsDouble(right.confidence, left.confidence, 0.0)
    }
    assertEquals(first.diagnostics, second.diagnostics)

  test("flat blocks remain emitted with a low finite confidence"):
    val grid = GridSpec.identity(Vector(11, 11, 11))
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("flat-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("flat-moving"), grid)
    val fixed = constantImage(fixedFrame, grid, 3.0, "fixed")
    val moving = constantImage(movingFrame, grid, 3.0, "moving")
    val config = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(1, 1, 1)
      )
    )
    val result = right(
      BasinBridgeBlockMatcher.search(
        fixed,
        moving,
        Vector(point(5.0, 5.0, 5.0)),
        config
      )
    )

    assertEquals(result.correspondences.length, 1)
    val confidence = result.correspondences.head.confidence
    assert(confidence > 0.0)
    assert(confidence < 0.05)
    assert(confidence.isFinite)

  test("boundary blocks fail closed when the declared support is unavailable"):
    val grid = GridSpec.identity(Vector(9, 9, 9))
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("edge-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("edge-moving"), grid)
    val fixed = image(fixedFrame, grid, shiftX = 0.0, "fixed")
    val moving = image(movingFrame, grid, shiftX = 1.0, "moving")
    val config = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(1, 1, 1),
        minimumValidFraction = 0.8
      )
    )
    val result = BasinBridgeBlockMatcher.search(
      fixed,
      moving,
      Vector(point(0.0, 0.0, 0.0)),
      config
    )

    result match
      case Left(error: BasinBridgeBlockMatcherError.InsufficientReferenceSupport) =>
        assertEquals(error.direction, BasinBridgeSearchDirection.Forward)
        assert(error.available < error.required)
      case Left(error) => fail(s"unexpected matcher error: ${error.message}")
      case Right(_) => fail("boundary block should have failed closed")

  test("rematch search retains usable anchors without changing strict search semantics"):
    val grid = GridSpec.identity(Vector(15, 15, 15))
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("retained-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("retained-moving"), grid)
    val fixed = image(fixedFrame, grid, shiftX = 0.0, "fixed")
    val moving = image(movingFrame, grid, shiftX = 1.0, "moving")
    val config = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(2, 2, 2),
        minimumValidFraction = 0.8
      )
    )
    val retention = right(
      BasinBridgeRematchRetentionConfig.make(
        minimumRetainedAnchors = 1,
        minimumRetainedFraction = 0.5
      )
    )
    val anchors = Vector(point(0.0, 0.0, 0.0), point(7.0, 7.0, 7.0))

    BasinBridgeBlockMatcher.search(fixed, moving, anchors, config) match
      case Left(_: BasinBridgeBlockMatcherError.InsufficientReferenceSupport) => ()
      case Left(error) => fail(s"strict search returned an unexpected error: ${error.message}")
      case Right(_) => fail("strict initial search must still fail on the boundary anchor")

    val first = right(
      BasinBridgeBlockMatcher.searchRetainingUsable(
        fixed,
        moving,
        anchors,
        config,
        retention
      )
    )
    val second = right(
      BasinBridgeBlockMatcher.searchRetainingUsable(
        fixed,
        moving,
        anchors,
        config,
        retention
      )
    )

    assertEquals(first.requestedAnchors, 2)
    assertEquals(first.retainedIndices, Vector(1))
    assertEquals(first.retainedAnchors, 1)
    assertEquals(first.result.correspondences.length, 1)
    assertEquals(first.dropped.map(_.index), Vector(0))
    first.dropped.head.error match
      case error: BasinBridgeBlockMatcherError.InsufficientReferenceSupport =>
        assertEquals(error.index, 0)
        assertEquals(error.direction, BasinBridgeSearchDirection.Forward)
      case error => fail(s"unexpected retained-search drop: ${error.message}")
    assertEquals(second, first)

  test("rematch search fails with a typed error below the retention floor"):
    val grid = GridSpec.identity(Vector(15, 15, 15))
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("retention-floor-fixed"), grid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("retention-floor-moving"), grid)
    val fixed = image(fixedFrame, grid, shiftX = 0.0, "fixed")
    val moving = image(movingFrame, grid, shiftX = 1.0, "moving")
    val config = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(2, 2, 2),
        minimumValidFraction = 0.8
      )
    )
    val retention = right(
      BasinBridgeRematchRetentionConfig.make(
        minimumRetainedAnchors = 2,
        minimumRetainedFraction = 1.0
      )
    )
    val result = BasinBridgeBlockMatcher.searchRetainingUsable(
      fixed,
      moving,
      Vector(point(0.0, 0.0, 0.0), point(7.0, 7.0, 7.0)),
      config,
      retention
    )

    result match
      case Left(error: BasinBridgeBlockMatcherError.InsufficientRetainedAnchors) =>
        assertEquals(error.available, 1)
        assertEquals(error.required, 2)
        assertEquals(error.requested, 2)
      case Left(error) => fail(s"unexpected retained-search error: ${error.message}")
      case Right(_) => fail("retained search should fail below its explicit support floor")

  test("invalid geometry and mismatched grids fail before search"):
    assert(
      BasinBridgeBlockSearchConfig
        .make(VoxelWindowRadius(0, 0, 0), VoxelWindowRadius(1, 1, 1))
        .isLeft
    )
    assert(
      BasinBridgeBlockSearchConfig
        .make(
          VoxelWindowRadius(1, 1, 1),
          VoxelWindowRadius(1, 1, 1),
          minimumValidFraction = 0.0
        )
        .isLeft
    )

    val fixedGrid = GridSpec.identity(Vector(9, 9, 9))
    val movingGrid = GridSpec.identity(Vector(11, 11, 11))
    val fixedFrame = RegistrationFrame[Fixed](SpatialDomainId("mismatch-fixed"), fixedGrid)
    val movingFrame = RegistrationFrame[Moving](SpatialDomainId("mismatch-moving"), movingGrid)
    val fixed = image(fixedFrame, fixedGrid, shiftX = 0.0, "fixed")
    val moving = image(movingFrame, movingGrid, shiftX = 0.0, "moving")
    val config = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(1, 1, 1)
      )
    )
    BasinBridgeBlockMatcher.search(fixed, moving, Vector(point(4.0, 4.0, 4.0)), config) match
      case Left(error) => assertEquals(error, BasinBridgeBlockMatcherError.GridMismatch)
      case Right(_) => fail("mismatched grids should fail before search")

  private def image[A](
      frame: RegistrationFrame[A],
      grid: GridSpec,
      shiftX: Double,
      label: String
  ): RegistrationImage[A] =
    val values = Array.tabulate[Double](grid.nVoxels): index =>
      val x = index % grid.shape(0)
      val yz = index / grid.shape(0)
      val y = yz % grid.shape(1)
      val z = yz / grid.shape(1)
      pattern(x.toDouble - shiftX, y.toDouble, z.toDouble)
    val volume = NeuroVol.fromLinear[Double](values, grid.toNeuroSpace, label)
    right(RegistrationImage.make(frame, volume))

  private def constantImage[A](frame: RegistrationFrame[A], grid: GridSpec, value: Double, label: String): RegistrationImage[A] =
    val volume = NeuroVol.fromLinear[Double](Array.fill(grid.nVoxels)(value), grid.toNeuroSpace, label)
    right(RegistrationImage.make(frame, volume))

  private def pattern(x: Double, y: Double, z: Double): Double =
    math.sin(0.21 * x + 0.07 * y) +
      math.cos(0.17 * z - 0.13 * x) +
      0.01 * x * y +
      0.003 * y * z

  private def point(x: Double, y: Double, z: Double): BasinBridgePoint =
    right(BasinBridgePoint.make(x, y, z))

  private def assertPoint(
      actual: BasinBridgePoint,
      x: Double,
      y: Double,
      z: Double,
      tolerance: Double
  ): Unit =
    assertEqualsDouble(actual.x, x, tolerance)
    assertEqualsDouble(actual.y, y, tolerance)
    assertEqualsDouble(actual.z, z, tolerance)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(error.toString)
