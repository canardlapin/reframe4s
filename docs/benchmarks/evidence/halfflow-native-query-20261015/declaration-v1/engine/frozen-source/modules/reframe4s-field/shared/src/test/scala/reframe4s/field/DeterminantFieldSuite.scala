package reframe4s.field

import image4s.geometry.Affine
import image4s.geometry.D2
import image4s.geometry.D3

import FieldFixtures.*

final class DeterminantFieldSuite extends munit.FunSuite:
  private val a = frame[D3]("jacobian-a")
  private val b = frame[D3]("jacobian-b")
  private val c = frame[D3]("jacobian-c")

  /** An oblique, anisotropic lattice so the voxel-to-world chain rule
    * matters.
    */
  private val obliqueGrid =
    val direction = Vector(0.8, -0.6, 0.0, 0.6, 0.8, 0.0, 0.0, 0.0, 1.0)
    val embedding =
      right(
        Affine.fromOriginSpacingDirection[D3](
          Vector(-4.0, 1.0, 2.0),
          Vector(1.5, 0.75, 2.0),
          direction
        )
      )
    grid(a, Vector(7, 6, 5), embedding)

  test("an affine field has the constant determinant of its linear part"):
    val linear =
      Vector(
        Vector(1.3, 0.2, -0.1),
        Vector(-0.4, 0.9, 0.3),
        Vector(0.05, 0.1, 1.6)
      )
    val expected = det3(linear)
    val dense =
      denseMap(obliqueGrid, b): x =>
        Vector.tabulate(3)(row =>
          (0 until 3).map(column => linear(row)(column) * x(column)).sum +
            Vector(2.0, -1.0, 0.5)(row)
        )
    val pull = assessmentRight(TopologyAssessor.determinantField(dense))
    val push =
      assessmentRight(
        TopologyAssessor.determinantField(dense, DeterminantDirection.Push)
      )
    for (index, _) <- latticePoints(obliqueGrid) do
      assertRegular(pull.at(index), expected, 1e-10)
      assertRegular(push.at(index), 1.0 / expected, 1e-10)
      assertLog(pull.logDeterminant.at(index), math.log(expected), 1e-10)
      assertLog(push.logDeterminant.at(index), -math.log(expected), 1e-10)
    assertEquals(pull.foldCount, 0L)
    assertEquals(pull.at(Vector(7, 0, 0)), None)
    assertEquals(pull.logDeterminant.at(Vector(0, -1, 0)), None)
    assertEqualsDouble(pull.pullRange._1, expected, 1e-10)
    assertEqualsDouble(pull.pullRange._2, expected, 1e-10)

  test("radial scaling matches its closed-form determinant"):
    val center = Vector(1.0, 3.0, 5.0)
    val alpha = 0.002
    val fine =
      grid(a, Vector(17, 15, 13), affine[D3](Vector(-3.0, -0.5, 2.0), Vector(0.5, 0.5, 0.5)))
    def radialFactor(x: Vector[Double]): (Double, Double) =
      val r2 = x.indices.map(axis => math.pow(x(axis) - center(axis), 2)).sum
      (1.0 + alpha * r2, r2)
    val dense =
      denseMap(fine, b): x =>
        val (g, _) = radialFactor(x)
        x.indices.toVector.map(axis => center(axis) + (x(axis) - center(axis)) * g)
    val field = assessmentRight(TopologyAssessor.determinantField(dense))
    // The map is cubic, so a central difference with spacing h equals the
    // derivative plus alpha * h^2 on the diagonal, exactly:
    // D_h y = (g + alpha h^2) I + 2 alpha v v^T.
    val bias = alpha * 0.5 * 0.5
    for (index, point) <- latticePoints(fine)
        if index.indices.forall(axis => index(axis) > 0 && index(axis) < fine.shape(axis) - 1)
    do
      val (g, r2) = radialFactor(point.coordinates)
      val closedForm = g * g * (g + 2.0 * alpha * r2)
      val discrete = math.pow(g + bias, 2) * (g + bias + 2.0 * alpha * r2)
      assertRegular(field.at(index), discrete, 1e-10 * discrete)
      assertRegular(field.at(index), closedForm, 4.0 * bias * closedForm)

  test("D2 radial scaling uses the two-dimensional closed form"):
    val plane = frame[D2]("jacobian-plane")
    val target = frame[D2]("jacobian-plane-target")
    val alpha = 0.01
    val lattice =
      grid(plane, Vector(21, 19), affine[D2](Vector(-5.0, -4.5), Vector(0.5, 0.5)))
    val dense =
      denseMap(lattice, target): x =>
        val g = 1.0 + alpha * (x(0) * x(0) + x(1) * x(1))
        Vector(x(0) * g, x(1) * g)
    val field = assessmentRight(TopologyAssessor.determinantField(dense))
    val bias = alpha * 0.5 * 0.5
    for (index, point) <- latticePoints(lattice)
        if index(0) > 0 && index(0) < 20 && index(1) > 0 && index(1) < 18
    do
      val x = point.coordinates
      val r2 = x(0) * x(0) + x(1) * x(1)
      val g = 1.0 + alpha * r2
      val closedForm = g * (g + 2.0 * alpha * r2)
      val discrete = (g + bias) * (g + bias + 2.0 * alpha * r2)
      assertRegular(field.at(index), discrete, 1e-10 * discrete)
      assertRegular(field.at(index), closedForm, 3.0 * bias * closedForm)

  test("a materialized composition satisfies the chain rule"):
    val lattice =
      grid(a, Vector(13, 11, 9), affine[D3](Vector(-3.0, -2.0, -2.0), Vector(0.5, 0.5, 0.5)))
    val secondGrid =
      grid(b, Vector(41, 37, 33), affine[D3](Vector(-6.0, -5.0, -5.0), Vector(0.25, 0.25, 0.25)))
    def first(x: Vector[Double]): Vector[Double] =
      Vector(
        x(0) + 0.2 * math.sin(0.3 * x(1)),
        x(1) + 0.15 * math.sin(0.25 * x(2)),
        x(2) + 0.1 * math.sin(0.2 * x(0))
      )
    def firstJacobian(x: Vector[Double]): Vector[Vector[Double]] =
      Vector(
        Vector(1.0, 0.06 * math.cos(0.3 * x(1)), 0.0),
        Vector(0.0, 1.0, 0.0375 * math.cos(0.25 * x(2))),
        Vector(0.02 * math.cos(0.2 * x(0)), 0.0, 1.0)
      )
    def second(y: Vector[Double]): Vector[Double] =
      Vector(
        1.1 * y(0) + 0.1 * math.cos(0.2 * y(2)),
        0.9 * y(1) + 0.05 * y(0),
        y(2) + 0.12 * math.sin(0.3 * y(1))
      )
    def secondJacobian(y: Vector[Double]): Vector[Vector[Double]] =
      Vector(
        Vector(1.1, 0.0, -0.02 * math.sin(0.2 * y(2))),
        Vector(0.05, 0.9, 0.0),
        Vector(0.0, 0.036 * math.cos(0.3 * y(1)), 1.0)
      )
    val composed =
      right(
        FieldComposition.compose(
          denseMap(lattice, b)(first),
          denseMap(secondGrid, c)(second),
          lattice
        )
      )
    val firstField =
      assessmentRight(TopologyAssessor.determinantField(denseMap(lattice, b)(first)))
    val field = assessmentRight(TopologyAssessor.determinantField(composed.map))
    for (index, point) <- latticePoints(lattice)
        if index.indices.forall(axis => index(axis) > 0 && index(axis) < lattice.shape(axis) - 1)
    do
      val x = point.coordinates
      val expected = det3(secondJacobian(first(x))) * det3(firstJacobian(x))
      assertRegular(field.at(index), expected, 2e-3 * expected)
      assertRegular(firstField.at(index), det3(firstJacobian(x)), 2e-3)

  test("injected folds are masked and counted, never NaN"):
    val lattice =
      grid(a, Vector(24, 4, 3), affine[D3](Vector(0.0, 0.0, 0.0), Vector(0.5, 1.0, 1.0)))
    // d y0 / d x0 = 1 + 1.6 cos(x0) is negative on part of every period.
    def warp(x: Vector[Double]): Vector[Double] =
      Vector(x(0) + 1.6 * math.sin(x(0)), x(1), x(2))
    val dense = denseMap(lattice, b)(warp)
    val field = assessmentRight(TopologyAssessor.determinantField(dense))
    def expected(i: Int): Double =
      val lower = if i == 0 then 0 else i - 1
      val upper = if i == 23 then 23 else i + 1
      (warp(Vector(upper * 0.5, 0.0, 0.0))(0) - warp(Vector(lower * 0.5, 0.0, 0.0))(0)) /
        ((upper - lower) * 0.5)
    val foldedColumns = (0 until 24).filter(i => expected(i) <= 0.0)
    assert(foldedColumns.nonEmpty && foldedColumns.length < 24)
    assertEquals(field.foldCount, foldedColumns.length.toLong * 12L)
    assertEquals(field.logDeterminant.nonPositiveCount, field.foldCount)
    for (index, _) <- latticePoints(lattice) do
      val determinant = expected(index(0))
      val folded = determinant <= 0.0
      assertEquals(field.foldMask.valueAt(index).toOption, Some(folded))
      assertEquals(field.logDeterminant.finiteMask.valueAt(index).toOption, Some(!folded))
      val logValue = right(field.logDeterminant.values.valueAt(index))
      val value = right(field.values.valueAt(index))
      assert(logValue.isFinite && value.isFinite)
      if folded then
        field.logDeterminant.at(index) match
          case Some(LogDeterminant.NonPositive(pull)) =>
            assertEqualsDouble(pull, determinant, 1e-12)
          case other => fail(s"expected NonPositive at $index, got $other")
        field.at(index) match
          case Some(DeterminantValue.Folded(pull)) =>
            assertEqualsDouble(pull, determinant, 1e-12)
          case other => fail(s"expected a fold at $index, got $other")
      else
        assertRegular(field.at(index), determinant, 1e-12)
        assertLog(field.logDeterminant.at(index), math.log(determinant), 1e-12)
    assert(field.pullRange._1 < 0.0)

  test("a single-sample axis cannot be differentiated"):
    val flat =
      grid(a, Vector(4, 1, 3), affine[D3](Vector(0.0, 0.0, 0.0), Vector(1.0, 1.0, 1.0)))
    assertEquals(
      TopologyAssessor.determinantField(denseMap(flat, b)(identity)).map(_ => ()),
      Left(TopologyAssessmentError.DegenerateAxis(1, 1))
    )

  private def det3(m: Vector[Vector[Double]]): Double =
    m(0)(0) * (m(1)(1) * m(2)(2) - m(1)(2) * m(2)(1)) -
      m(0)(1) * (m(1)(0) * m(2)(2) - m(1)(2) * m(2)(0)) +
      m(0)(2) * (m(1)(0) * m(2)(1) - m(1)(1) * m(2)(0))

  private def assertRegular(
      actual: Option[DeterminantValue],
      expected: Double,
      tolerance: Double
  ): Unit =
    actual match
      case Some(DeterminantValue.Regular(value)) =>
        assertEqualsDouble(value, expected, tolerance)
      case other => fail(s"expected a regular determinant $expected, got $other")

  private def assertLog(
      actual: Option[LogDeterminant],
      expected: Double,
      tolerance: Double
  ): Unit =
    actual match
      case Some(LogDeterminant.Finite(value)) =>
        assertEqualsDouble(value, expected, tolerance)
      case other => fail(s"expected a finite log determinant, got $other")

  private def assessmentRight[A](value: Either[TopologyAssessmentError, A]): A =
    value.fold(error => fail(error.message), identity)
