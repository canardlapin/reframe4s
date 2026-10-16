package reframe4s.field

import reframe4s.core.AffineMap
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid

import FieldFixtures.*

final class FieldCompositionSuite extends munit.FunSuite:
  private val a = frame[D3]("compose-a")
  private val b = frame[D3]("compose-b")
  private val c = frame[D3]("compose-c")

  private val gridA =
    grid(a, Vector(6, 5, 4), affine[D3](Vector(-3.0, 2.0, 1.0), Vector(2.0, 1.5, 1.0)))

  /** A smooth nonlinear map with displacements under half a voxel. */
  private def wobble(x: Vector[Double]): Vector[Double] =
    Vector(
      x(0) + 0.4 * math.sin(0.3 * x(1)) + 0.5,
      x(1) - 0.3 * math.cos(0.2 * x(0) + 0.1 * x(2)),
      x(2) + 0.25 * math.sin(0.4 * x(0)) - 0.2
    )

  /** A wide grid in `frame` that contains every image used in these tests. */
  private def wideGrid(frame: Frame[D3]): Grid[Frame[D3], D3] =
    grid(frame, Vector(26, 20, 16), affine[D3](Vector(-12.0, -6.0, -6.0), Vector(1.0, 1.0, 1.0)))

  test("identity stages on either side leave a dense map's samples unchanged"):
    val dense = denseMap(gridA, b)(wobble)
    val direct = compositionRight(FieldComposition.materialize(dense, gridA))
    val leftIdentity =
      compositionRight(
        FieldComposition.compose(AffineMap.identity[D3, Frame[D3]](a), dense, gridA)
      )
    val rightIdentity =
      compositionRight(
        FieldComposition.compose(dense, AffineMap.identity[D3, Frame[D3]](b), gridA)
      )
    for (index, point) <- latticePoints(gridA) do
      val expected = wobble(point.coordinates)
      for result <- Vector(direct, leftIdentity, rightIdentity) do
        assertVectors(sampleAt(result, index), expected, 1e-12)
        assertEquals(
          result.report.outcomeAt(index),
          Some(MaterializedOutcome.Covered)
        )
    assertEquals(
      direct.report.counts,
      CoverageCounts(covered = 120L, constantFilled = 0L, sourcePreserved = 0L, rejected = 0L)
    )
    assertEqualsDouble(direct.report.counts.coveredFraction, 1.0, 0.0)

  test("affine then dense matches the analytic composition"):
    val rotation =
      rowMajor[D3](
        0.9, -0.2, 0.1, 1.5,
        0.2, 0.95, 0.0, -0.5,
        -0.1, 0.05, 1.1, 0.25,
        0.0, 0.0, 0.0, 1.0
      )
    val transform = affineMap(a, b, rotation)
    def linear(y: Vector[Double]): Vector[Double] =
      Vector(
        1.2 * y(0) - 0.3 * y(1) + 0.1 * y(2) + 4.0,
        0.2 * y(0) + 0.8 * y(1) - 1.0,
        -0.1 * y(1) + 1.3 * y(2) + 0.5
      )
    // Linear interpolation of an affine field is exact, so the composition
    // must agree with the closed form to rounding.
    val dense = denseMap(wideGrid(b), c)(linear)
    val composed =
      compositionRight(FieldComposition.compose(transform, dense, gridA))
    for (index, point) <- latticePoints(gridA) do
      val expected = linear(right(rotation(point.coordinates)))
      assertVectors(sampleAt(composed, index), expected, 1e-10)
    assertEquals(composed.report.counts.covered, 120L)

  test("dense then affine matches the analytic composition"):
    val scaling =
      rowMajor[D3](
        2.0, 0.0, 0.0, -1.0,
        0.0, 0.5, 0.0, 3.0,
        0.0, 0.0, -1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      )
    val dense = denseMap(gridA, b)(wobble)
    val composed =
      compositionRight(
        FieldComposition.compose(dense, affineMap(b, c, scaling), gridA)
      )
    for (index, point) <- latticePoints(gridA) do
      val expected = right(scaling(wobble(point.coordinates)))
      assertVectors(sampleAt(composed, index), expected, 1e-12)

  test("dense then dense agrees with pointwise composition"):
    val first = denseMap(gridA, b)(wobble)
    val second =
      denseMap(wideGrid(b), c): y =>
        Vector(
          y(0) + 0.3 * math.cos(0.5 * y(2)),
          y(1) + 0.2 * math.sin(0.35 * y(0) * 0.5 + y(1) * 0.1),
          y(2) - 0.1 * y(0) * 0.05
        )
    val composed =
      compositionRight(FieldComposition.compose(first, second, gridA))
    val pointwise = SpatialMap.compose(first, second)
    for (index, point) <- latticePoints(gridA) do
      assertVectors(sampleAt(composed, index), mapped(pointwise, point), 1e-12)
    assertEquals(composed.report.counts.covered, 120L)

  test("points leaving a later stage's support are counted under its policy"):
    val first = denseMap(gridA, b)(wobble)
    val narrowGrid =
      grid(b, Vector(5, 4, 4), affine[D3](Vector(-2.0, 2.0, 0.0), Vector(1.5, 1.0, 1.0)))
    val identityLike = (y: Vector[Double]) => y.map(_ + 0.1)
    val expectedCovered =
      latticePoints(gridA).count((_, point) =>
        val y = wobble(point.coordinates)
        val index =
          Vector(
            (y(0) + 2.0) / 1.5,
            y(1) - 2.0,
            y(2)
          )
        index.indices.forall(axis =>
          index(axis) >= 0.0 && index(axis) <= narrowGrid.shape(axis) - 1.0
        )
      ).toLong
    assert(expectedCovered > 0L && expectedCovered < 120L)

    val preserving =
      denseMap(narrowGrid, c, boundary = CoordinateBoundaryPolicy.PreserveSource)(
        identityLike
      )
    val preserved =
      compositionRight(FieldComposition.compose(first, preserving, gridA))
    assertEquals(
      preserved.report.counts,
      CoverageCounts(expectedCovered, 0L, 120L - expectedCovered, 0L)
    )
    val pointwise = SpatialMap.compose(first, preserving)
    for (index, point) <- latticePoints(gridA) do
      assertVectors(sampleAt(preserved, index), mapped(pointwise, point), 1e-12)
      val covered = preserved.report.outcomeAt(index).contains(MaterializedOutcome.Covered)
      assertEquals(
        preserved.report.coverageMask.valueAt(index).toOption,
        Some(covered)
      )

    val constant =
      denseMap(
        narrowGrid,
        c,
        boundary = CoordinateBoundaryPolicy.Constant(Vector(0.0, 0.0, 0.0))
      )(identityLike)
    val filled =
      compositionRight(FieldComposition.compose(first, constant, gridA))
    assertEquals(
      filled.report.counts,
      CoverageCounts(expectedCovered, 120L - expectedCovered, 0L, 0L)
    )

    val rejecting = denseMap(narrowGrid, c)(identityLike)
    FieldComposition.compose(first, rejecting, gridA) match
      case Left(CompositionError.RejectedPoints(report)) =>
        assertEquals(
          report.counts,
          CoverageCounts(expectedCovered, 0L, 0L, 120L - expectedCovered)
        )
      case other => fail(s"expected rejected points, got $other")

    val explicitFill =
      compositionRight(
        FieldComposition.compose(
          first,
          rejecting,
          gridA,
          rejectedFill = CoordinateBoundaryPolicy.PreserveSource
        )
      )
    assertEquals(explicitFill.report.counts.rejected, 120L - expectedCovered)
    for (index, point) <- latticePoints(gridA) do
      if explicitFill.report.outcomeAt(index).contains(MaterializedOutcome.Rejected)
      then assertVectors(sampleAt(explicitFill, index), point.coordinates, 0.0)

  test("the first stage that leaves its support determines the outcome"):
    val smallA =
      grid(a, Vector(3, 3, 2), affine[D3](Vector(-3.0, 2.0, 1.0), Vector(2.0, 1.5, 1.0)))
    val first =
      denseMap(smallA, b, boundary = CoordinateBoundaryPolicy.PreserveSource)(wobble)
    val second =
      denseMap(
        wideGrid(b),
        c,
        boundary = CoordinateBoundaryPolicy.Constant(Vector(1.0, 2.0, 3.0))
      )(identity)
    val composed =
      compositionRight(FieldComposition.compose(first, second, gridA))
    for (index, _) <- latticePoints(gridA) do
      val insideFirst = index(0) <= 2 && index(1) <= 2 && index(2) <= 1
      assertEquals(
        composed.report.outcomeAt(index),
        Some(
          if insideFirst then MaterializedOutcome.Covered
          else MaterializedOutcome.SourcePreserved
        )
      )

  test("lattice frames and fill coordinates are validated"):
    val dense = denseMap(gridA, b)(wobble)
    FieldComposition.materialize(dense, wideGrid(b)) match
      case Left(CompositionError.Map(_: MapError.SourceFrameMismatch)) => ()
      case other => fail(s"expected a source frame mismatch, got $other")
    assertEquals(
      FieldComposition
        .materialize(dense, gridA, CoordinateBoundaryPolicy.Constant(Vector(1.0)))
        .map(_ => ()),
      Left(CompositionError.Field(FieldError.InvalidBoundaryCoordinates(3, 1)))
    )

  private def sampleAt(
      composed: ComposedField[Frame[D3], Frame[D3], D3],
      index: Vector[Int]
  ): Vector[Double] =
    Vector.tabulate(3)(component =>
      right(composed.map.coordinates.valueAt(index, Vector(component)))
    )

  private def assertVectors(
      actual: Vector[Double],
      expected: Vector[Double],
      tolerance: Double
  ): Unit =
    assertEquals(actual.length, expected.length)
    actual.indices.foreach(axis =>
      assertEqualsDouble(actual(axis), expected(axis), tolerance)
    )

  private def compositionRight[A](value: Either[CompositionError, A]): A =
    value.fold(error => fail(error.message), identity)
