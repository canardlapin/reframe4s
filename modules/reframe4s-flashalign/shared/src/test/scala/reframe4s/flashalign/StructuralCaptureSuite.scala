package reframe4s.flashalign

import reframe4s.spectral.SpectralShape3

import scala.collection.mutable

final class StructuralCaptureSuite extends munit.FunSuite:
  test("FFT score volume matches the direct spatial oracle and world lag sign"):
    val movingLattice = lattice(SpectralShape3(7, 5, 5), 10.0, -3.0, 4.0)
    val fixedLattice = lattice(SpectralShape3(8, 6, 5), 12.0, -3.0, 4.0)
    val lag = (3, -1, 0)
    val points = Vector(
      (1, 2, 1, 1.0, 0.0, 0.0),
      (2, 3, 2, 0.0, 1.0, 0.0),
      (3, 2, 3, 0.0, 0.0, 1.0),
      (4, 3, 1, 0.6, 0.8, 0.0),
      (2, 2, 2, 0.0, 0.6, 0.8)
    )
    val moving = sparseTensor(movingLattice, points)
    val fixed = sparseTensor(
      fixedLattice,
      points.map(point =>
        (point._1 + lag._1, point._2 + lag._2, point._3 + lag._3, point._4, point._5, point._6)
      )
    )
    val config = captureConfig(minimumOverlap = 0.19, minimumScore = 0.05)
    val plan = capture(
      StructuralCapturePlan3.create(moving, fixed, config, 0.0, 0.0, 0.0)
    )
    assertEquals(plan.preparedFixedComplexScalarCount, 65536L)
    val workspace = plan.newWorkspace()
    val scores = capture(plan.identityScoreSnapshot(workspace))
    val outputShape = SpectralShape3(
      movingLattice.shape.x + fixedLattice.shape.x - 1,
      movingLattice.shape.y + fixedLattice.shape.y - 1,
      movingLattice.shape.z + fixedLattice.shape.z - 1
    )
    var outputX = 0
    while outputX < outputShape.x do
      val lagX = outputX - movingLattice.shape.x + 1
      var outputY = 0
      while outputY < outputShape.y do
        val lagY = outputY - movingLattice.shape.y + 1
        var outputZ = 0
        while outputZ < outputShape.z do
          val lagZ = outputZ - movingLattice.shape.z + 1
          val flat = (outputX * outputShape.y + outputY) * outputShape.z + outputZ
          val expected = directScore(moving, fixed, lagX, lagY, lagZ, config)
          if expected.isNaN then assert(scores(flat).isNaN, s"lag=($lagX,$lagY,$lagZ)")
          else assertEqualsDouble(scores(flat), expected, 3e-11, s"lag=($lagX,$lagY,$lagZ)")
          outputZ += 1
        outputY += 1
      outputX += 1

    capture(plan.capture(workspace)) match
      case StructuralCaptureOutcome3.Captured(candidates, diagnostics) =>
        val best = candidates.head
        assertEquals((best.lagX, best.lagY, best.lagZ), lag)
        assertEqualsDouble(best.translationXMillimetres, 8.0, 1e-12)
        assertEqualsDouble(best.translationYMillimetres, -1.5, 1e-12)
        assertEqualsDouble(best.translationZMillimetres, 0.0, 1e-12)
        assertEqualsDouble(best.structuralScore, 1.0, 3e-12)
        assertEquals(diagnostics.evaluatedRotations, 1)
      case other => fail(s"expected captured translation, got $other")

  test("linear padding does not create a circular edge peak"):
    val movingLattice = lattice(SpectralShape3(7, 5, 5), 0.0, 0.0, 0.0)
    val fixedLattice = lattice(SpectralShape3(8, 5, 5), 0.0, 0.0, 0.0)
    val moving = sparseTensor(movingLattice, Vector((1, 2, 2, 1.0, 0.0, 0.0)))
    val fixed = sparseTensor(fixedLattice, Vector((7, 2, 2, 1.0, 0.0, 0.0)))
    val config = captureConfig(minimumOverlap = 0.99, minimumScore = 0.9)
    val plan = capture(StructuralCapturePlan3.create(moving, fixed, config, 0.0, 0.0, 0.0))
    capture(plan.capture(plan.newWorkspace())) match
      case StructuralCaptureOutcome3.Captured(candidates, _) =>
        assertEquals(candidates.head.lagX, 6)
        assertEqualsDouble(candidates.head.translationXMillimetres, 12.0, 1e-12)
      case other => fail(s"expected edge translation, got $other")

  test("progressive rotation schedule is seeded bounded and stage ordered"):
    val config = capture(
      StructuralCaptureConfig3.create(
        Vector(CaptureRotationStage3(0.0, 1.0), CaptureRotationStage3(10.0, 10.0)),
        seed = 9341L,
        maximumRotations = 10
      )
    )
    val first = CaptureRotationSchedule3.build(config)
    val second = CaptureRotationSchedule3.build(config)
    assertEquals(first.map(item => (item.stage, item.xDegrees, item.yDegrees, item.zDegrees)), second.map(item => (item.stage, item.xDegrees, item.yDegrees, item.zDegrees)))
    assertEquals(first.size, 10)
    assertEquals((first.head.stage, first.head.xDegrees, first.head.yDegrees, first.head.zDegrees), (0, 0.0, 0.0, 0.0))
    assert(first.tail.forall(_.stage == 1))
    assertEquals(first.map(item => (item.xDegrees, item.yDegrees, item.zDegrees)).distinct.size, first.size)

  test("typed outcomes retain evidence for overlap information and exhausted search"):
    val captureLattice = lattice(SpectralShape3(5, 5, 5), 0.0, 0.0, 0.0)
    val empty = sparseTensor(captureLattice, Vector.empty)
    val xTensor = sparseTensor(captureLattice, Vector((2, 2, 2, 1.0, 0.0, 0.0)))
    val yTensor = sparseTensor(captureLattice, Vector((2, 2, 2, 0.0, 1.0, 0.0)))
    val zeroComponents = Array.fill(6)(new Array[Double](captureLattice.elementCount))
    val fullSupport = Array.fill(captureLattice.elementCount)(1.0)
    val noInformation = capture(
      NormalizedGradientTensor3.create(captureLattice, zeroComponents, fullSupport)
    )
    val config = captureConfig(minimumOverlap = 0.2, minimumScore = 0.9)

    outcome(empty, xTensor, config) match
      case StructuralCaptureOutcome3.InsufficientOverlap(diagnostics) =>
        assertEquals(diagnostics.evaluatedRotations, 1)
        assertEqualsDouble(diagnostics.evaluations.head.bestOverlapFraction, 0.0, 0.0)
      case other => fail(s"expected insufficient overlap, got $other")

    outcome(noInformation, noInformation, config) match
      case StructuralCaptureOutcome3.InsufficientInformation(diagnostics) =>
        assert(diagnostics.evaluations.head.overlapEligibleLags > 0)
        assertEquals(diagnostics.evaluations.head.informativeLags, 0)
      case other => fail(s"expected insufficient information, got $other")

    outcome(xTensor, yTensor, config) match
      case StructuralCaptureOutcome3.ExhaustedSchedule(best, diagnostics) =>
        assertEqualsDouble(best.getOrElse(fail("missing rejected score")), 0.0, 2e-15)
        assert(diagnostics.evaluations.head.informativeLags > 0)
        assertEquals(diagnostics.evaluations.head.acceptedPeaks, 0)
      case other => fail(s"expected exhausted schedule, got $other")

  test("common objective evaluates every retained candidate and reports competition"):
    val lattice = this.lattice(SpectralShape3(5, 5, 5), 0.0, 0.0, 0.0)
    val moving = sparseTensor(
      lattice,
      Vector(
        (1, 2, 2, 1.0, 0.0, 0.0),
        (3, 2, 2, 0.0, 1.0, 0.0)
      )
    )
    val config = captureConfig(minimumOverlap = 0.49, minimumScore = 0.1)
    val captured = outcome(moving, moving, config) match
      case StructuralCaptureOutcome3.Captured(candidates, diagnostics) =>
        assert(diagnostics.identityAdequate, diagnostics)
        assert(!diagnostics.expandedCaptureExecuted, diagnostics)
        assertEquals(diagnostics.evaluatedRotations, 1)
        assertEqualsDouble(diagnostics.identityOverlapFraction, 1.0, 1e-12)
        assertEqualsDouble(
          diagnostics.identityStructuralScore.getOrElse(
            fail("missing identity score")
          ),
          1.0,
          2e-15
        )
        candidates
      case other => fail(s"expected candidates, got $other")
    val expanded = captured ++ captured.map(candidate =>
      candidate.copy(
        rotationId = candidate.rotationId + 100,
        translationXMillimetres = candidate.translationXMillimetres + 10.0,
        lagX = candidate.lagX + 5
      )
    )
    val visited = mutable.ArrayBuffer.empty[Int]
    val comparison = capture(
      CommonCaptureComparison3.evaluateAll(expanded, nearEqualObjectiveTolerance = 0.01) {
        candidate =>
          visited += candidate.rotationId
          if candidate.rotationId >= 100 then 1.005 else 1.0
      }
    )
    assertEquals(visited.toVector, expanded.map(_.rotationId))
    assertEquals(comparison.assessments.size, expanded.size)
    assert(comparison.competingAlignments)

  private def lattice(
      shape: SpectralShape3,
      originX: Double,
      originY: Double,
      originZ: Double
  ): CaptureLattice3 =
    capture(
      CaptureLattice3.create(
        shape,
        originX,
        originY,
        originZ,
        spacingXMillimetres = 2.0,
        spacingYMillimetres = 1.5,
        spacingZMillimetres = 3.0
      )
    )

  private def captureConfig(
      minimumOverlap: Double,
      minimumScore: Double
  ): StructuralCaptureConfig3 =
    capture(
      StructuralCaptureConfig3.create(
        Vector(CaptureRotationStage3(0.0, 1.0)),
        seed = 71L,
        maximumRotations = 1,
        maximumRetainedCandidates = 4,
        peaksPerRotation = 4,
        minimumCandidatesToStop = 1,
        minimumOverlapFraction = minimumOverlap,
        minimumStructuralEnergy = 1e-12,
        minimumStructuralScore = minimumScore,
        minimumInterpolatedSupport = 1e-8,
        minimumPeakSeparationMillimetres = 2.0,
        minimumCandidateDisplacementMillimetres = 1.0
      )
    )

  private def sparseTensor(
      lattice: CaptureLattice3,
      points: Vector[(Int, Int, Int, Double, Double, Double)]
  ): NormalizedGradientTensor3 =
    val components = Array.fill(6)(new Array[Double](lattice.elementCount))
    val support = new Array[Double](lattice.elementCount)
    points.foreach { point =>
      val index = lattice.index(point._1, point._2, point._3)
      val norm = math.sqrt(point._4 * point._4 + point._5 * point._5 + point._6 * point._6)
      val x = point._4 / norm
      val y = point._5 / norm
      val z = point._6 / norm
      components(0)(index) = x * x
      components(1)(index) = y * y
      components(2)(index) = z * z
      components(3)(index) = x * y
      components(4)(index) = x * z
      components(5)(index) = y * z
      support(index) = 1.0
    }
    capture(NormalizedGradientTensor3.create(lattice, components, support))

  private def directScore(
      moving: NormalizedGradientTensor3,
      fixed: NormalizedGradientTensor3,
      lagX: Int,
      lagY: Int,
      lagZ: Int,
      config: StructuralCaptureConfig3
  ): Double =
    var numerator = 0.0
    var leftEnergy = 0.0
    var rightEnergy = 0.0
    var overlap = 0.0
    val leftLattice = moving.lattice
    val rightLattice = fixed.lattice
    var x = 0
    while x < leftLattice.shape.x do
      val rx = x + lagX
      var y = 0
      while y < leftLattice.shape.y do
        val ry = y + lagY
        var z = 0
        while z < leftLattice.shape.z do
          val rz = z + lagZ
          if rx >= 0 && rx < rightLattice.shape.x && ry >= 0 &&
            ry < rightLattice.shape.y && rz >= 0 && rz < rightLattice.shape.z
          then
            val leftIndex = leftLattice.index(x, y, z)
            val rightIndex = rightLattice.index(rx, ry, rz)
            val weight = moving.supportAt(leftIndex) * fixed.supportAt(rightIndex)
            overlap += weight
            var component = 0
            while component < 6 do
              val factor = if component < 3 then 1.0 else 2.0
              numerator += factor * weight * moving.component(component, leftIndex) * fixed.component(component, rightIndex)
              leftEnergy += factor * weight * moving.component(component, leftIndex) * moving.component(component, leftIndex)
              rightEnergy += factor * weight * fixed.component(component, rightIndex) * fixed.component(component, rightIndex)
              component += 1
          z += 1
        y += 1
      x += 1
    val overlapFraction = overlap / math.min(moving.totalSupport, fixed.totalSupport)
    val energyProduct = leftEnergy * rightEnergy
    if overlapFraction < config.minimumOverlapFraction || energyProduct < config.minimumStructuralEnergy then Double.NaN
    else numerator / math.sqrt(energyProduct)

  private def outcome(
      moving: NormalizedGradientTensor3,
      fixed: NormalizedGradientTensor3,
      config: StructuralCaptureConfig3
  ): StructuralCaptureOutcome3 =
    val plan = capture(StructuralCapturePlan3.create(moving, fixed, config, 0.0, 0.0, 0.0))
    capture(plan.capture(plan.newWorkspace()))

  private def capture[A](value: Either[StructuralCaptureError, A]): A =
    value.fold(error => fail(error.message), identity)
