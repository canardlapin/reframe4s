package reframe4s.halfflow

class BasinBridgeSearchAdapterSuite extends munit.FunSuite:
  test("pure translation preserves forward sign and derives zero cycle error"):
    val fixed = point(4.0, 5.0, 6.0)
    val moving = point(9.0, 2.0, 10.0)
    val observation = searchObservation(
      fixed,
      moving,
      fixed,
      Vector(1.0, 0.0, 0.0),
      Vector(1.0, 0.0, 0.0)
    )
    val result = right(BasinBridgeSearchAdapter.fromForwardReverse(Vector(observation)))
    val correspondence = result.correspondences.head

    assertPoint(correspondence.tangent, 5.0, -3.0, 4.0)
    assertPoint(correspondence.midpoint, 6.5, 3.5, 8.0)
    assertEqualsDouble(observation.cycleErrorMm, 0.0, 0.0)
    assertEqualsDouble(correspondence.confidence, 1.0, 0.0)
    assertEquals(result.diagnostics.inputCount, 1)
    assertEquals(result.diagnostics.emittedCount, 1)
    assertEqualsDouble(result.diagnostics.minimumEffectiveWeight, 1.0, 0.0)
    assertEqualsDouble(result.diagnostics.meanEffectiveWeight, 1.0, 0.0)
    assertEqualsDouble(result.diagnostics.maximumEffectiveWeight, 1.0, 0.0)

  test("reverse search displacement controls cycle consistency"):
    val fixed = point(10.0, 10.0, 10.0)
    val moving = point(13.0, 10.0, 10.0)
    val reverseFixed = point(10.5, 10.0, 10.0)
    val observation = searchObservation(
      fixed,
      moving,
      reverseFixed,
      Vector(1.0, 0.0),
      Vector(1.0, 0.0)
    )
    val result = right(BasinBridgeSearchAdapter.fromForwardReverse(Vector(observation)))
    val expectedCycle = 0.5
    val expectedConsistency = math.exp(-expectedCycle / BasinBridgeConfidenceConfig.default.cycleScaleMm)

    assertEqualsDouble(observation.cycleErrorMm, expectedCycle, 1e-14)
    assertEqualsDouble(
      result.correspondences.head.confidenceEvidence.cycleConsistency,
      expectedConsistency,
      1e-14
    )
    assertEqualsDouble(result.diagnostics.minimumCycleErrorMm, expectedCycle, 1e-14)
    assertEqualsDouble(result.diagnostics.maximumCycleErrorMm, expectedCycle, 1e-14)

  test("weak observations remain emitted and inspectable"):
    val flat = searchObservation(
      point(2.0, 2.0, 2.0),
      point(3.0, 2.0, 2.0),
      point(2.0, 2.0, 2.0),
      Vector.fill(8)(1.0),
      Vector.fill(8)(1.0)
    )
    val unique = searchObservation(
      point(12.0, 12.0, 12.0),
      point(14.0, 12.0, 12.0),
      point(12.0, 12.0, 12.0),
      Vector(1.0, 0.0, 0.0),
      Vector(1.0, 0.0, 0.0)
    )
    val result = right(BasinBridgeSearchAdapter.fromForwardReverse(Vector(flat, unique)))

    assertEquals(result.correspondences.length, 2)
    assertEquals(result.diagnostics.inputCount, 2)
    assertEquals(result.diagnostics.emittedCount, 2)
    assert(result.correspondences.head.confidence > 0.0)
    assert(result.correspondences.head.confidence < 0.05)
    assertEqualsDouble(result.correspondences(1).confidence, 1.0, 0.0)
    assertEqualsDouble(
      result.diagnostics.minimumEffectiveWeight,
      result.correspondences.head.confidence,
      0.0
    )

  test("reordering observations preserves each result and aggregate diagnostics"):
    val observations = Vector(
      searchObservation(
        point(1.0, 1.0, 1.0),
        point(4.0, 1.0, 1.0),
        point(1.0, 1.0, 1.0),
        Vector(1.0, 0.0, 0.0),
        Vector(1.0, 0.0, 0.0)
      ),
      searchObservation(
        point(5.0, 5.0, 5.0),
        point(5.0, 7.0, 5.0),
        point(5.25, 5.0, 5.0),
        Vector(0.5, 0.5, 0.0),
        Vector(0.8, 0.1, 0.1),
        priorConfidence = 0.75
      ),
      searchObservation(
        point(9.0, 9.0, 9.0),
        point(8.0, 9.0, 9.0),
        point(9.0, 9.5, 9.0),
        Vector(0.7, 0.2, 0.1),
        Vector(0.1, 0.7, 0.2),
        priorConfidence = 0.4
      )
    )
    val forward = right(BasinBridgeSearchAdapter.fromForwardReverse(observations))
    val reordered = right(BasinBridgeSearchAdapter.fromForwardReverse(observations.reverse))

    assertEquals(forward.correspondences.length, reordered.correspondences.length)
    forward.correspondences.zip(reordered.correspondences.reverse).foreach { case (first, second) =>
      assertPoint(second.fixed, first.fixed.x, first.fixed.y, first.fixed.z)
      assertPoint(second.moving, first.moving.x, first.moving.y, first.moving.z)
      assertEqualsDouble(second.confidence, first.confidence, 1e-14)
    }
    assertEquals(forward.diagnostics.inputCount, reordered.diagnostics.inputCount)
    assertEquals(forward.diagnostics.emittedCount, reordered.diagnostics.emittedCount)
    assertEqualsDouble(
      forward.diagnostics.minimumEffectiveWeight,
      reordered.diagnostics.minimumEffectiveWeight,
      1e-14
    )
    assertEqualsDouble(
      forward.diagnostics.meanEffectiveWeight,
      reordered.diagnostics.meanEffectiveWeight,
      1e-14
    )
    assertEqualsDouble(
      forward.diagnostics.maximumEffectiveWeight,
      reordered.diagnostics.maximumEffectiveWeight,
      1e-14
    )
    assertEqualsDouble(
      forward.diagnostics.meanCycleErrorMm,
      reordered.diagnostics.meanCycleErrorMm,
      1e-14
    )

  test("adapter rejects empty input and observation construction fails closed"):
    assert(
      BasinBridgeSearchAdapter.fromForwardReverse(Vector.empty) ==
        Left(BasinBridgeSearchAdapterError.EmptyObservations)
    )
    assert(
      BasinBridgeSearchObservation.make(
        point(0.0, 0.0, 0.0),
        point(1.0, 0.0, 0.0),
        point(0.0, 0.0, 0.0),
        Vector.empty,
        Vector(1.0)
      ).isLeft
    )
    assert(
      BasinBridgeSearchObservation.make(
        point(0.0, 0.0, 0.0),
        point(1.0, 0.0, 0.0),
        point(0.0, 0.0, 0.0),
        Vector(1.0),
        Vector(1.0),
        priorConfidence = Double.NaN
      ).isLeft
    )

  test("finite observations produce only finite bounded confidence diagnostics"):
    val observation = searchObservation(
      point(0.0, 0.0, 0.0),
      point(1.0, 0.0, 0.0),
      point(1e150, 0.0, 0.0),
      Vector(1.0, 0.0),
      Vector(1.0, 0.0)
    )
    val result = right(BasinBridgeSearchAdapter.fromForwardReverse(Vector(observation)))
    val diagnostics = result.diagnostics

    assert(result.correspondences.forall(c => c.confidence.isFinite && c.confidence >= 0.0 && c.confidence <= 1.0))
    assert(diagnostics.minimumEffectiveWeight.isFinite)
    assert(diagnostics.meanEffectiveWeight.isFinite)
    assert(diagnostics.maximumEffectiveWeight.isFinite)
    assert(diagnostics.minimumCycleErrorMm.isFinite)
    assert(diagnostics.meanCycleErrorMm.isFinite)
    assert(diagnostics.maximumCycleErrorMm.isFinite)
    assertEqualsDouble(diagnostics.minimumEffectiveWeight, 0.0, 0.0)

  private def searchObservation(
      fixed: BasinBridgePoint,
      moving: BasinBridgePoint,
      reverseFixed: BasinBridgePoint,
      forward: Vector[Double],
      reverse: Vector[Double],
      priorConfidence: Double = 1.0
  ): BasinBridgeSearchObservation =
    right(
      BasinBridgeSearchObservation.make(
        fixed,
        moving,
        reverseFixed,
        forward,
        reverse,
        priorConfidence
      )
    )

  private def point(x: Double, y: Double, z: Double): BasinBridgePoint =
    right(BasinBridgePoint.make(x, y, z))

  private def assertPoint(point: BasinBridgePoint, x: Double, y: Double, z: Double): Unit =
    assertEqualsDouble(point.x, x, 1e-14)
    assertEqualsDouble(point.y, y, 1e-14)
    assertEqualsDouble(point.z, z, 1e-14)

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(error.toString)
