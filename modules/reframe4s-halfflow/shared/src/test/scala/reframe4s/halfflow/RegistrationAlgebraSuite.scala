package reframe4s.halfflow

import image4s.geometry.{Affine, D3}
import gale.linalg.DMat
import reframe4s.halfflow.internal.*

class RegistrationAlgebraSuite extends munit.FunSuite:
  sealed trait A
  sealed trait B
  sealed trait C
  sealed trait W
  sealed trait F
  sealed trait M

  private val grid = GridSpec.identity(Vector(9, 9, 9))

  test("typed pull composition follows bc(ab(x)) and checks runtime endpoints"):
    val a = RegistrationFrame[A](SpatialDomainId("a"), grid)
    val b = RegistrationFrame[B](SpatialDomainId("b"), grid)
    val c = RegistrationFrame[C](SpatialDomainId("c"), grid)
    val ab = pull(a, b)(point => Vector(point(0) + 0.5, point(1), point(2)))
    val bc = pull(b, c)(point => Vector(1.5 * point(0), point(1) - 0.25, point(2)))
    val ac = right(ab >>> bc)
    val point = valueAt(ac, 3, 4, 5)
    assertClose(point(0), 1.5 * 3.5)
    assertClose(point(1), 3.75)
    assertClose(point(2), 5.0)

    val wrongB = RegistrationFrame[B](SpatialDomainId("other-b"), grid)
    val wrong = pull(wrongB, c)(identity)
    assert((ab >>> wrong).isLeft)

  test("inverse-pair construction rejects runtime endpoint disagreement"):
    val a = RegistrationFrame[A](SpatialDomainId("a"), grid)
    val b = RegistrationFrame[B](SpatialDomainId("b"), grid)
    val wrongB = RegistrationFrame[B](SpatialDomainId("wrong-b"), grid)
    val forward = pull(a, b)(identity)
    val backward = pull(wrongB, a)(identity)
    assert(InversePair.make(forward, backward).isLeft)

  test("midpoint advance applies opposite halves and result carries its inverse"):
    val work = RegistrationFrame[W](SpatialDomainId("work"), grid)
    val fixed = RegistrationFrame[F](SpatialDomainId("fixed"), grid)
    val moving = RegistrationFrame[M](SpatialDomainId("moving"), grid)
    val midpoint = right(Midpoint.identity(work, fixed, moving))
    val half = selfPair(work, 0.25, 0.0, 0.0)
    val advanced = right(midpoint.advance(half))
    val result = right(advanced.result)

    val forward = valueAt(result.forward, 4, 4, 4)
    val backward = valueAt(result.backward, 4, 4, 4)
    assertClose(forward(0), 3.5)
    assertClose(backward(0), 4.5)
    assertClose(forward(1), 4.0)
    assertClose(backward(1), 4.0)

    val exported = right(result.toDenseMaps)
    assert(exported.forward.source.sameRuntimeOwnerAs(fixed.canonical))
    assert(exported.forward.target.sameRuntimeOwnerAs(moving.canonical))
    assert(exported.backward.source.sameRuntimeOwnerAs(moving.canonical))
    assert(exported.backward.target.sameRuntimeOwnerAs(fixed.canonical))
    assertPoint(right(MapExecution.coordinates(exported.forward, Vector(4.0, 4.0, 4.0))), forward, 1e-12)

  test("identity midpoint advance preserves affine arms beyond the work grid"):
    val work = RegistrationFrame[W](SpatialDomainId("work"), grid)
    val fixed = RegistrationFrame[F](SpatialDomainId("fixed"), grid)
    val moving = RegistrationFrame[M](SpatialDomainId("moving"), grid)
    val fixedArm = affineArm(work, fixed, 2.0)
    val movingArm = affineArm(work, moving, -2.0)
    val midpoint = right(Midpoint.make(fixedArm, movingArm))
    val advanced = right(midpoint.advance(InversePair.identity(work)))
    val reused = right(midpoint.advanceInto(InversePair.identity(work), MidpointAdvanceBuffer(midpoint)))

    assertPairEquals(right(advanced.fixed.dense), right(fixedArm.dense))
    assertPairEquals(right(advanced.moving.dense), right(movingArm.dense))
    assertPairEquals(right(reused.fixed.dense), right(fixedArm.dense))
    assertPairEquals(right(reused.moving.dense), right(movingArm.dense))
    val result = right(advanced.result)
    val forwardEdge = index(grid, 0, 4, 4)
    val backwardEdge = index(grid, 8, 4, 4)
    assert(validAt(result.forward.validity, forwardEdge))
    assert(validAt(result.backward.validity, backwardEdge))
    assertPoint(valueAt(result.forward, 0, 4, 4), Vector(-4.0, 4.0, 4.0), 1e-12)
    assertPoint(valueAt(result.backward, 8, 4, 4), Vector(12.0, 4.0, 4.0), 1e-12)
    val guarded = TopologyGuard.evaluateMidpoint(
      advanced,
      GuardConfig(
        maximumInverseErrorMm = 1e-10,
        maximumInverseErrorVox = 1e-10,
        minimumValidFraction = 0.95,
        minimumInverseValidFraction = Some(0.70)
      )
    )
    assert(
      guarded.safe,
      s"fixed=${guarded.fixed.reasons.mkString(", ")} moving=${guarded.moving.reasons.mkString(", ")}"
    )

  test("tiny midpoint updates remain guard-safe after affine initialization"):
    val work = RegistrationFrame[W](SpatialDomainId("work"), grid)
    val fixed = RegistrationFrame[F](SpatialDomainId("fixed"), grid)
    val moving = RegistrationFrame[M](SpatialDomainId("moving"), grid)
    val midpoint = right(Midpoint.make(affineArm(work, fixed, 2.0), affineArm(work, moving, -2.0)))
    val tiny = right(
      Velocity.make(work, displacement(grid)((_, _, _) => Vector(1e-6, 0.0, 0.0)))
    )
    val candidate = right(midpoint.advance(right(PairedScalingAndSquaring.expHalfPair(tiny)).pair))
    val guarded = TopologyGuard.evaluateMidpoint(
      candidate,
      GuardConfig(
        maximumInverseErrorMm = 1e-4,
        maximumInverseErrorVox = 1e-4,
        minimumValidFraction = 0.80,
        minimumInverseValidFraction = Some(0.60)
      )
    )

    assert(
      guarded.safe,
      s"fixed=${guarded.fixed.reasons.mkString(", ")} moving=${guarded.moving.reasons.mkString(", ")}"
    )

  test("paired exponential is exact for zero and constant translation away from boundaries"):
    val frame = RegistrationFrame[W](SpatialDomainId("work"), grid)
    val zero = right(Velocity.make(frame, displacement(grid)((_, _, _) => Vector(0.0, 0.0, 0.0))))
    val zeroFlow = right(PairedScalingAndSquaring.expHalfPair(zero))
    assertEquals(zeroFlow.diagnostics.squaringDepth, 0)
    assert(TopologyGuard.evaluate(zeroFlow.pair).safe)
    assertPoint(valueAt(zeroFlow.pair.forward, 4, 4, 4), Vector(4.0, 4.0, 4.0), 0.0)

    val translation = right(Velocity.make(frame, displacement(grid)((_, _, _) => Vector(0.8, -0.4, 0.2))))
    val flow = right(
      PairedScalingAndSquaring.expHalfPair(
        translation,
        FlowConfig(maximumInitialDisplacementMm = 0.1, maximumInitialGradient = 0.2)
      )
    )
    assertEquals(flow.diagnostics.squaringDepth, 3)
    assertPoint(valueAt(flow.pair.forward, 4, 4, 4), Vector(4.4, 3.8, 4.1), 2e-12)
    assertPoint(valueAt(flow.pair.backward, 4, 4, 4), Vector(3.6, 4.2, 3.9), 2e-12)
    assertEquals(flow.diagnostics.compositionCalls, 6)

    val workspace = PairedFlowWorkspace(frame)
    val reused = right(PairedScalingAndSquaring.expHalfPairWith(translation, workspace, FlowConfig(0.1, 0.2)))
    assertPoint(valueAt(reused.pair.forward, 4, 4, 4), Vector(4.4, 3.8, 4.1), 2e-12)
    assertEquals(workspace.ownedCoordinateBuffers, 4)
    assertEquals(workspace.ownedValidityBuffers, 4)
    right(PairedScalingAndSquaring.expHalfPairWith(zero, workspace))
    assertPoint(
      valueAt(reused.pair.forward, 4, 4, 4),
      Vector(4.4, 3.8, 4.1),
      2e-12
    )

  test("adaptive depth uses the physical velocity-gradient bound"):
    val frame = RegistrationFrame[W](SpatialDomainId("work"), grid)
    val velocity = right(
      Velocity.make(
        frame,
        displacement(grid)((x, _, _) => Vector(0.8 * (x - 4.0), 0.0, 0.0))
      )
    )
    val flow = right(
      PairedScalingAndSquaring.expHalfPair(
        velocity,
        FlowConfig(maximumInitialDisplacementMm = 10.0, maximumInitialGradient = 0.1)
      )
    )
    assertEquals(flow.diagnostics.squaringDepth, 2)
    assert(flow.diagnostics.initialGradientBound <= 0.1 + 1e-12)

  test("scaling-and-squaring converges toward an independent linear-flow exponential"):
    val flowGrid = GridSpec.identity(Vector(33, 33, 33))
    val frame = RegistrationFrame[W](SpatialDomainId("work"), flowGrid)
    val rate = 0.1
    val velocity = right(
      Velocity.make(
        frame,
        displacement(flowGrid)((x, _, _) => Vector(rate * (x - 16.0), 0.0, 0.0))
      )
    )
    val coarse = right(
      PairedScalingAndSquaring.expHalfPair(
        velocity,
        FlowConfig(maximumInitialDisplacementMm = 10.0, maximumInitialGradient = 1.0)
      )
    )
    val refined = right(
      PairedScalingAndSquaring.expHalfPair(
        velocity,
        FlowConfig(maximumInitialDisplacementMm = 10.0, maximumInitialGradient = 0.003125)
      )
    )
    val exactPlus = 16.0 + math.exp(0.5 * rate) * 4.0
    val exactMinus = 16.0 + math.exp(-0.5 * rate) * 4.0
    val coarseError = math.abs(valueAt(coarse.pair.forward, 20, 16, 16)(0) - exactPlus)
    val refinedError = math.abs(valueAt(refined.pair.forward, 20, 16, 16)(0) - exactPlus)
    assertEquals(coarse.diagnostics.squaringDepth, 0)
    assertEquals(refined.diagnostics.squaringDepth, 4)
    assert(refinedError < coarseError * 0.2, s"coarse=$coarseError refined=$refinedError")
    assertClose(valueAt(refined.pair.backward, 20, 16, 16)(0), exactMinus, 4e-4)

  test("guard accepts identity and rejects an inverse-consistent fold"):
    val frame = RegistrationFrame[W](SpatialDomainId("work"), grid)
    val identityPair = InversePair.identity(frame)
    val accepted = TopologyGuard.evaluate(identityPair)
    assert(accepted.safe, accepted.reasons.mkString(", "))
    assertEquals(accepted.forward.nonPositive, 0)
    assertClose(accepted.forward.quantiles.p50.getOrElse(Double.NaN), 1.0)
    val workspace = TopologyGuardWorkspace(identityPair)
    val reused = right(TopologyGuard.evaluateWith(identityPair, workspace))
    assertEquals(reused, accepted)
    assertEquals(workspace.ownedScalarBuffers, 2)
    assertEquals(workspace.ownedValidityBuffers, 1)

    val reflection = pull(frame, frame)(point => Vector(8.0 - point(0), point(1), point(2)))
    val folded = right(InversePair.make(reflection, reflection))
    val rejected = TopologyGuard.evaluate(folded)
    assert(!rejected.safe)
    assert(rejected.forward.nonPositive > 0)
    assert(rejected.reasons.exists(_.contains("non-positive Jacobians")))

  test("guard separates Jacobian support from inverse-composition overlap"):
    val frame = RegistrationFrame[W](SpatialDomainId("work"), grid)
    val translated = selfPair(frame, 2.0, 0.0, 0.0)
    val strict = TopologyGuard.evaluate(
      translated,
      GuardConfig(
        maximumInverseErrorMm = 1e-10,
        maximumInverseErrorVox = 1e-10,
        minimumValidFraction = 0.95,
        minimumInverseValidFraction = Some(0.95)
      )
    )
    assert(!strict.safe)
    assertClose(strict.forward.validFraction, 1.0)
    assert(strict.reasons.exists(_.contains("inverse coverage is too small")))

    val admitted = TopologyGuard.evaluate(
      translated,
      GuardConfig(
        maximumInverseErrorMm = 1e-10,
        maximumInverseErrorVox = 1e-10,
        minimumValidFraction = 0.95,
        minimumInverseValidFraction = Some(0.70)
      )
    )
    assert(admitted.safe, admitted.reasons.mkString(", "))
    assertClose(admitted.forward.validFraction, 1.0)
    assert(admitted.inverse.forwardThenBackward.skipped > 0)
    assertClose(admitted.inverse.forwardThenBackward.maximumMm.getOrElse(Double.NaN), 0.0)

  test("accumulated midpoint guard catches an unsafe arm after a safe local flow"):
    val work = RegistrationFrame[W](SpatialDomainId("work"), grid)
    val fixed = RegistrationFrame[F](SpatialDomainId("fixed"), grid)
    val moving = RegistrationFrame[M](SpatialDomainId("moving"), grid)
    val identityMidpoint = right(Midpoint.identity(work, fixed, moving))
    val safeLocal = TopologyGuard.evaluate(InversePair.identity(work))
    assert(safeLocal.safe)

    val foldForward = pull(work, work)(point => Vector(8.0 - point(0), point(1), point(2)))
    val foldBackward = pull(work, work)(point => Vector(8.0 - point(0), point(1), point(2)))
    val foldPair = right(InversePair.make(foldForward, foldBackward))
    val foldArm = right(MidpointArm.make(foldPair, identityMidpoint.fixed.affine))
    val unsafeMidpoint = right(Midpoint.make(foldArm, identityMidpoint.moving))
    val accumulated = TopologyGuard.evaluateMidpoint(unsafeMidpoint)
    assert(!accumulated.safe)
    assert(!accumulated.fixed.safe)
    assert(accumulated.moving.safe)

  test("linear-only regridding preserves a physical translation and inverse"):
    val coarse = GridSpec(
      Vector(5, 5, 5),
      DMat.dense(4, 4, (Vector(
          Vector(2.0, 0.0, 0.0, 0.0),
          Vector(0.0, 2.0, 0.0, 0.0),
          Vector(0.0, 0.0, 2.0, 0.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )).flatten)
    )
    val fine = coarse.withGeometry(grid.shape, grid.indexToFrame)
    val coarseFrame = RegistrationFrame[W](SpatialDomainId("work"), coarse)
    val fineFrame = RegistrationFrame[W](SpatialDomainId("work"), fine)
    val coarsePair = selfPair(coarseFrame, 0.75, -0.5, 0.25)
    val finePair = right(coarsePair.regrid(fineFrame, fineFrame))
    assertPoint(valueAt(finePair.forward, 4, 4, 4), Vector(4.75, 3.5, 4.25), 2e-12)
    assertPoint(valueAt(finePair.backward, 4, 4, 4), Vector(3.25, 4.5, 3.75), 2e-12)

  private def selfPair[T](frame: RegistrationFrame[T], dx: Double, dy: Double, dz: Double): InversePair[T, T] =
    val forward = pull(frame, frame)(point => Vector(point(0) + dx, point(1) + dy, point(2) + dz))
    val backward = pull(frame, frame)(point => Vector(point(0) - dx, point(1) - dy, point(2) - dz))
    right(InversePair.make(forward, backward))

  private def affineArm[X, Y](from: RegistrationFrame[X], to: RegistrationFrame[Y], dx: Double): MidpointArm[X, Y] =
    val matrix = DMat.dense(4, 4, (Vector(
        Vector(1.0, 0.0, 0.0, dx),
        Vector(0.0, 1.0, 0.0, 0.0),
        Vector(0.0, 0.0, 1.0, 0.0),
        Vector(0.0, 0.0, 0.0, 1.0)
      )).flatten)
    val affine = right(AffineIso.make(from, to, Affine.fromRowMajor[D3](Vector.tabulate(16)(i => matrix(i / 4, i % 4))).toOption.get))
    right(MidpointArm.identity(affine))

  private def assertPairEquals[X, Y](actual: InversePair[X, Y], expected: InversePair[X, Y]): Unit =
    assertPullEquals(actual.forward, expected.forward)
    assertPullEquals(actual.backward, expected.backward)

  private def assertPullEquals[X, Y](actual: DensePull[X, Y], expected: DensePull[X, Y]): Unit =
    var index = 0
    while index < actual.from.grid.nVoxels do
      assertEquals(validAt(actual.validity, index), validAt(expected.validity, index))
      var component = 0
      while component < 3 do
        assertClose(
          actual.sourceCoordinates.linearComponent(index, component),
          expected.sourceCoordinates.linearComponent(index, component)
        )
        component += 1
      index += 1

  private def validAt(validity: FieldValidity, index: Int): Boolean =
    validity match
      case FieldValidity.All => true
      case FieldValidity.Mask(values) => values(index)

  private def index(fieldGrid: GridSpec, x: Int, y: Int, z: Int): Int =
    x + y * fieldGrid.shape(0) + z * fieldGrid.shape(0) * fieldGrid.shape(1)

  private def pull[X, Y](
      from: RegistrationFrame[X],
      to: RegistrationFrame[Y]
  )(mapping: Vector[Double] => Vector[Double]): DensePull[X, Y] =
    right(DensePull.make(from, to, coordinateField(from.grid)(mapping)))

  private def coordinateField(
      fieldGrid: GridSpec
  )(mapping: Vector[Double] => Vector[Double]): DenseVectorField =
    val values = PrimitiveBuffers.ofSize[Double](fieldGrid.nVoxels * 3)
    var z = 0
    while z < fieldGrid.shape(2) do
      var y = 0
      while y < fieldGrid.shape(1) do
        var x = 0
        while x < fieldGrid.shape(0) do
          val index = x + fieldGrid.shape(0) * y + fieldGrid.shape(0) * fieldGrid.shape(1) * z
          val point = (fieldGrid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble)))
          val mapped = mapping(point)
          values(index) = mapped(0)
          values(index + fieldGrid.nVoxels) = mapped(1)
          values(index + 2 * fieldGrid.nVoxels) = mapped(2)
          x += 1
        y += 1
      z += 1
    DenseVectorField.fromLegacyPlanar(
      fieldGrid,
      values,
      DenseVectorFieldKind.SourceCoordinates
    )

  private def displacement(
      fieldGrid: GridSpec
  )(value: (Double, Double, Double) => Vector[Double]): DenseVectorField =
    val values = PrimitiveBuffers.ofSize[Double](fieldGrid.nVoxels * 3)
    var z = 0
    while z < fieldGrid.shape(2) do
      var y = 0
      while y < fieldGrid.shape(1) do
        var x = 0
        while x < fieldGrid.shape(0) do
          val index = x + fieldGrid.shape(0) * y + fieldGrid.shape(0) * fieldGrid.shape(1) * z
          val world = fieldGrid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))
          val vector = value(world(0), world(1), world(2))
          values(index) = vector(0)
          values(index + fieldGrid.nVoxels) = vector(1)
          values(index + 2 * fieldGrid.nVoxels) = vector(2)
          x += 1
        y += 1
      z += 1
    DenseVectorField.fromLegacyPlanar(fieldGrid, values, DenseVectorFieldKind.Displacement)

  private def valueAt[X, Y](pull: DensePull[X, Y], x: Int, y: Int, z: Int): Vector[Double] =
    val fieldGrid = pull.from.grid
    val index = x + fieldGrid.shape(0) * y + fieldGrid.shape(0) * fieldGrid.shape(1) * z
    Vector(
      pull.sourceCoordinates.linearComponent(index, 0),
      pull.sourceCoordinates.linearComponent(index, 1),
      pull.sourceCoordinates.linearComponent(index, 2)
    )

  private def assertPoint(actual: Vector[Double], expected: Vector[Double], tolerance: Double): Unit =
    assertClose(actual(0), expected(0), tolerance)
    assertClose(actual(1), expected(1), tolerance)
    assertClose(actual(2), expected(2), tolerance)

  private def assertClose(actual: Double, expected: Double, tolerance: Double = 1e-10): Unit =
    assertEqualsDouble(actual, expected, tolerance)

  private def right[L, R](value: Either[L, R]): R =
    value match
      case Right(result) => result
      case Left(error) => fail(error.toString)
