package reframe4s.halfflow

class BasinBridgeCorrespondenceSuite extends munit.FunSuite:
  test("translation uses the moving-minus-fixed pull tangent"):
    val fixed = point(1.0, 2.0, 3.0)
    val moving = point(13.0, 1.0, 5.0)
    val correspondence = right(BasinBridgeCorrespondence.make(fixed, moving, 1.0))

    assertPoint(correspondence.midpoint, 7.0, 1.5, 4.0)
    assertPoint(correspondence.tangent, 12.0, -1.0, 2.0)
    assertPoint(correspondence.p, 13.0, 1.0, 5.0)
    assertPoint(correspondence.q, 1.0, 2.0, 3.0)

  test("forward and reverse correspondences preserve midpoint and negate tangent"):
    val correspondence =
      right(BasinBridgeCorrespondence.make(point(-2.0, 4.0, 1.0), point(5.0, -3.0, 9.0), 0.37))
    val reverse = correspondence.swapped

    assertPoint(reverse.midpoint, correspondence.midpoint.x, correspondence.midpoint.y, correspondence.midpoint.z)
    assertPoint(
      reverse.tangent,
      -correspondence.tangent.x,
      -correspondence.tangent.y,
      -correspondence.tangent.z
    )
    assertEqualsDouble(reverse.confidence, correspondence.confidence, 0.0)

  test("unique forward and reverse peaks receive the largest soft weight"):
    val unique = confidence(Vector(1.0, 0.0, 0.0), Vector(1.0, 0.0, 0.0), cycleErrorMm = 0.0)
    val multimodal = confidence(Vector(0.5, 0.5, 0.0), Vector(0.5, 0.5, 0.0), cycleErrorMm = 0.0)
    val cycleInconsistent = confidence(Vector(1.0, 0.0, 0.0), Vector(1.0, 0.0, 0.0), cycleErrorMm = 12.0)

    assert(unique.effectiveWeight > multimodal.effectiveWeight)
    assert(multimodal.effectiveWeight > cycleInconsistent.effectiveWeight)
    assert(unique.effectiveWeight <= 1.0)
    assert(cycleInconsistent.effectiveWeight > 0.0)

  test("flat blocks retain a near-zero inspectable soft weight"):
    val flat = confidence(Vector.fill(8)(1.0), Vector.fill(8)(1.0), cycleErrorMm = 0.0)
    assert(flat.effectiveWeight > 0.0)
    assert(flat.effectiveWeight < 0.05)
    assert(flat.effectiveWeight.isFinite)

  test("evidence produces a typed correspondence without deleting weak matches"):
    val evidence = right(
      BasinBridgeConfidenceEvidence.make(
        Vector(0.5, 0.5),
        Vector(1.0, 0.0),
        cycleErrorMm = 0.0
      )
    )
    val correspondence = right(
      BasinBridgeCorrespondence.fromEvidence(
        point(0.0, 0.0, 0.0),
        point(12.0, 0.0, 0.0),
        evidence
      )
    )
    assert(correspondence.confidence > 0.0)
    assert(correspondence.confidence < 1.0)
    assert(correspondence.confidenceEvidence.cycleConsistency == 1.0)
    assertPoint(correspondence.tangent, 12.0, 0.0, 0.0)

  test("confidence is invariant to candidate ordering"):
    val first = confidence(Vector(0.7, 0.2, 0.1), Vector(0.8, 0.1, 0.1), cycleErrorMm = 1.5)
    val permuted = confidence(Vector(0.1, 0.7, 0.2), Vector(0.1, 0.8, 0.1), cycleErrorMm = 1.5)
    assertEqualsDouble(first.effectiveWeight, permuted.effectiveWeight, 1e-14)
    assertEqualsDouble(first.ambiguityQuality, permuted.ambiguityQuality, 1e-14)

  test("invalid evidence fails closed"):
    assert(
      BasinBridgeConfidenceEvidence.make(Vector.empty, Vector(1.0), cycleErrorMm = 0.0).isLeft
    )
    assert(
      BasinBridgeConfidenceEvidence
        .make(Vector(1.0, -0.1), Vector(1.0), cycleErrorMm = 0.0)
        .isLeft
    )
    assert(
      BasinBridgeConfidenceEvidence
        .make(Vector(1.0), Vector(1.0), cycleErrorMm = Double.NaN)
        .isLeft
    )
    assert(BasinBridgeCorrespondence.make(point(0.0, 0.0, 0.0), point(1.0, 1.0, 1.0), 1.1).isLeft)

  private def confidence(
      forward: Vector[Double],
      reverse: Vector[Double],
      cycleErrorMm: Double
  ): BasinBridgeConfidence =
    val evidence = right(
      BasinBridgeConfidenceEvidence.make(
        forward,
        reverse,
        cycleErrorMm
      )
    )
    right(BasinBridgeConfidence.from(evidence))

  private def point(x: Double, y: Double, z: Double): BasinBridgePoint =
    right(BasinBridgePoint.make(x, y, z))

  private def assertPoint(point: BasinBridgePoint, x: Double, y: Double, z: Double): Unit =
    assertEqualsDouble(point.x, x, 1e-14)
    assertEqualsDouble(point.y, y, 1e-14)
    assertEqualsDouble(point.z, z, 1e-14)

  private def right[A](value: Either[BasinBridgeError, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(error.message)
