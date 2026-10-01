package reframe4s.field

import ravel.DType.given
import ravel.NDArray
import ravel.Shape
import image4s.geometry.Affine
import image4s.geometry.ContinuousIndex
import image4s.geometry.D2
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid

final class BSplineFieldSuite extends munit.FunSuite:
  private val orders = Vector(BSplineOrder.Quadratic, BSplineOrder.Cubic)

  test("kernels match the closed-form B-spline values"):
    assertEqualsDouble(BSplineOrder.Cubic.weight(0.0), 2.0 / 3.0, 1e-15)
    assertEqualsDouble(BSplineOrder.Cubic.weight(0.5), 23.0 / 48.0, 1e-15)
    assertEqualsDouble(BSplineOrder.Cubic.weight(-1.0), 1.0 / 6.0, 1e-15)
    assertEqualsDouble(BSplineOrder.Cubic.weight(2.0), 0.0, 0.0)
    assertEqualsDouble(BSplineOrder.Quadratic.weight(0.0), 0.75, 1e-15)
    assertEqualsDouble(BSplineOrder.Quadratic.weight(0.5), 0.5, 1e-15)
    assertEqualsDouble(BSplineOrder.Quadratic.weight(1.0), 0.125, 1e-15)
    assertEqualsDouble(BSplineOrder.Quadratic.weight(1.5), 0.0, 0.0)

  test("FSL knot offsets are one exactly when spacing exceeds one voxel"):
    val knots = bsplineRight(KnotLattice.fsl[D3](Vector(1, 2, 5)))
    assertEquals(knots.offset, Vector(0, 1, 1))
    assertEquals(
      KnotLattice.fsl[D3](Vector(1, 0, 5)),
      Left(BSplineError.InvalidKnotSpacing(1, 0))
    )
    assertEquals(
      KnotLattice.create[D2](Vector(2, 2), Vector(0, -1)),
      Left(BSplineError.InvalidKnotOffset(1, -1))
    )
    assertEquals(
      KnotLattice.fsl[D3](Vector(2, 2)),
      Left(BSplineError.RankMismatch(3, 2))
    )

  test("a single unit coefficient evaluates to the shifted kernel"):
    val knots = bsplineRight(KnotLattice.fsl[D3](Vector(2, 2, 2)))
    for order <- orders do
      val coefficients =
        NDArray.tabulate[Double](5, 5, 5, 3): (a, b, c, component) =>
          if a == 2 && b == 2 && c == 2 && component == 1 then 1.0 else 0.0
      val field =
        bsplineRight(BSplineField.create(order, knots, coefficients))
      for (u, t) <- Vector(2 -> 0.0, 3 -> 0.5, 4 -> 1.0, 5 -> 1.5, 6 -> 2.0)
      do
        val value = field.at(continuous3(u.toDouble, 2.0, 2.0))
        val expected = order.weight(t) * order.weight(0.0) * order.weight(0.0)
        assertEqualsDouble(value(1), expected, 1e-14)
        assertEqualsDouble(value(0), 0.0, 0.0)
        assertEqualsDouble(value(2), 0.0, 0.0)

  test("constant coefficients reproduce the constant on the whole FSL lattice"):
    val shape = Vector(10, 7, 5)
    val spacing = Vector(3, 2, 2)
    val knots = bsplineRight(KnotLattice.fsl[D3](spacing))
    val counts = fslCoefficientCounts(shape, spacing)
    val constant = Vector(1.5, -2.0, 0.25)
    for order <- orders do
      val coefficients =
        NDArray.tabulate[Double](counts(0), counts(1), counts(2), 3):
          (_, _, _, component) => constant(component)
      val field =
        bsplineRight(BSplineField.create(order, knots, coefficients))
      val lattice = bsplineRight(field.evaluateLattice(shape))
      lattice.foreachIndex: index =>
        assertEqualsDouble(
          lattice.at(index),
          constant(index(3)),
          1e-12,
          s"$order at ${index.mkString(",")}"
        )

  test("linear and quadratic polynomials are reproduced exactly"):
    val shape = Vector(9, 8, 6)
    val spacing = Vector(3, 2, 4)
    val knots = bsplineRight(KnotLattice.fsl[D3](spacing))
    val counts = fslCoefficientCounts(shape, spacing)
    for order <- orders do
      val variance = if order == BSplineOrder.Cubic then 1.0 / 3.0 else 0.25
      val linear =
        NDArray.tabulate[Double](counts(0), counts(1), counts(2), 3):
          (a, b, c, component) =>
            val knot = Vector(a, b, c)(component)
            spacing(component) *
              (knot - knots.offset(component)).toDouble
      val quadratic =
        NDArray.tabulate[Double](counts(0), counts(1), counts(2), 3):
          (a, b, c, component) =>
            val knot = Vector(a, b, c)(component)
            val k = spacing(component).toDouble
            val position = (knot - knots.offset(component)).toDouble
            k * k * (position * position - variance)
      val linearField =
        bsplineRight(BSplineField.create(order, knots, linear))
      val quadraticField =
        bsplineRight(BSplineField.create(order, knots, quadratic))
      val probes =
        Vector(
          Vector(0.0, 0.0, 0.0),
          Vector(4.25, 3.5, 2.75),
          Vector(8.0, 7.0, 4.0),
          Vector(1.9, 6.1, 0.3)
        )
      for probe <- probes do
        val point = continuous3(probe(0), probe(1), probe(2))
        val linearValue = linearField.at(point)
        val quadraticValue = quadraticField.at(point)
        for axis <- 0 until 3 do
          assertEqualsDouble(linearValue(axis), probe(axis), 1e-12)
          assertEqualsDouble(
            quadraticValue(axis),
            probe(axis) * probe(axis),
            1e-11
          )

  test("lattice and pointwise evaluation agree with a direct reference sum"):
    val coefficientShape = Vector(6, 5, 7)
    val spacing = Vector(2, 3, 1)
    val latticeShape = Vector(9, 10, 6)
    val knots = bsplineRight(KnotLattice.fsl[D3](spacing))
    val random = new scala.util.Random(20260924L)
    val raw =
      Vector.fill(coefficientShape.product * 3)(random.nextDouble() * 2.0 - 1.0)
    val coefficients =
      NDArray.fromSeq(
        Shape(coefficientShape(0), coefficientShape(1), coefficientShape(2), 3),
        raw
      )
    for order <- orders do
      val field =
        bsplineRight(BSplineField.create(order, knots, coefficients))
      val lattice = bsplineRight(field.evaluateLattice(latticeShape))
      lattice.foreachIndex: index =>
        val expected =
          reference(
            order,
            raw,
            coefficientShape,
            knots,
            Vector(index(0), index(1), index(2)).map(_.toDouble)
          )
        assertEqualsDouble(lattice.at(index), expected(index(3)), 1e-12)
      for probe <- Vector(
          Vector(0.3, 4.7, 2.2),
          Vector(7.5, 0.0, 5.9),
          Vector(-1.0, 12.0, 3.0)
        )
      do
        val actual = field.at(continuous3(probe(0), probe(1), probe(2)))
        val expected = reference(order, raw, coefficientShape, knots, probe)
        for component <- 0 until 3 do
          assertEqualsDouble(actual(component), expected(component), 1e-12)

  test("unit spacing has no knot before the first voxel"):
    // With k = 1 the offset is zero, so voxel 0 lacks coefficient -1 and the
    // edge value is the truncated kernel sum, exactly as FNIRT evaluates it.
    val knots = bsplineRight(KnotLattice.fsl[D2](Vector(1, 1)))
    val coefficients = NDArray.fill(Shape(4, 4, 2), 1.0)
    for order <- orders do
      val field =
        bsplineRight(BSplineField.create(order, knots, coefficients))
      val edge = order.weight(0.0) + order.weight(1.0)
      val interior = order.weight(0.0) + 2.0 * order.weight(1.0)
      val lattice = bsplineRight(field.evaluateLattice(Vector(4, 4)))
      assertEqualsDouble(lattice.at(IArray(0, 1, 0)), edge * interior, 1e-14)
      assertEqualsDouble(lattice.at(IArray(1, 2, 1)), interior * interior, 1e-14)
      assertEqualsDouble(interior, 1.0, 1e-14)

  test("D2 fields evaluate on grids as validated displacements"):
    val frame = geometryRight(Frame.named[D2]("bspline-d2"))
    val grid = geometryRight(Grid.in(frame)(Vector(6, 4), Affine.identity[D2]))
    val knots = bsplineRight(KnotLattice.fsl[D2](Vector(2, 2)))
    val coefficients =
      NDArray.tabulate[Double](6, 5, 2): (a, b, component) =>
        if component == 0 then 2.0 * (a - 1).toDouble
        else 2.0 * (b - 1).toDouble
    val field =
      bsplineRight(BSplineField.create(BSplineOrder.Cubic, knots, coefficients))
    val displacement = bsplineRight(field.displacementOn(grid))

    assertEquals(displacement.grid.shape, Vector(6, 4))
    for
      i <- 0 until 6
      j <- 0 until 4
    do
      val value =
        displacement.samples.valueAt(Vector(i, j), Vector(0))
      assertEqualsDouble(
        value.fold(error => fail(error.message), identity),
        i.toDouble,
        1e-12
      )

  test("coefficient validation rejects wrong shapes and non-finite values"):
    val knots = bsplineRight(KnotLattice.fsl[D3](Vector(2, 2, 2)))
    assertEquals(
      BSplineField.create(
        BSplineOrder.Cubic,
        knots,
        NDArray.zeros[Double](3, 3, 3, 2)
      ),
      Left(BSplineError.InvalidCoefficientShape(3, Vector(3, 3, 3, 2)))
    )
    val invalid =
      NDArray.tabulate[Double](2, 2, 2, 3): (a, _, _, _) =>
        if a == 1 then Double.NaN else 0.0
    assert(
      BSplineField.create(BSplineOrder.Cubic, knots, invalid) match
        case Left(BSplineError.NonFiniteCoefficient(12L, _)) => true
        case _                                               => false
    )
    val field =
      bsplineRight(
        BSplineField.create(
          BSplineOrder.Cubic,
          knots,
          NDArray.zeros[Double](2, 2, 2, 3)
        )
      )
    assertEquals(
      field.evaluateLattice(Vector(2, 0, 2)).map(_ => ()),
      Left(BSplineError.InvalidLatticeShape(Vector(2, 0, 2)))
    )

  private def fslCoefficientCounts(
      shape: Vector[Int],
      spacing: Vector[Int]
  ): Vector[Int] =
    shape.indices.toVector.map(axis =>
      if spacing(axis) > 1 then
        math.ceil((shape(axis) + 1).toDouble / spacing(axis)).toInt + 2
      else shape(axis)
    )

  /** Independent brute-force sum over every coefficient. */
  private def reference(
      order: BSplineOrder,
      raw: Vector[Double],
      coefficientShape: Vector[Int],
      knots: KnotLattice[D3],
      u: Vector[Double]
  ): Vector[Double] =
    def kernel(t: Double): Double =
      val a = math.abs(t)
      if order == BSplineOrder.Cubic then
        if a < 1 then 2.0 / 3.0 - a * a + a * a * a / 2.0
        else if a < 2 then math.pow(2.0 - a, 3) / 6.0
        else 0.0
      else if a < 0.5 then 0.75 - a * a
      else if a < 1.5 then math.pow(a - 1.5, 2) / 2.0
      else 0.0
    val sums = Array.fill(3)(0.0)
    for
      a <- 0 until coefficientShape(0)
      b <- 0 until coefficientShape(1)
      c <- 0 until coefficientShape(2)
    do
      val weight =
        Vector(a, b, c).zipWithIndex
          .map((knot, axis) =>
            kernel(
              u(axis) / knots.spacing(axis) - knot + knots.offset(axis)
            )
          )
          .product
      for component <- 0 until 3 do
        val flat =
          ((a * coefficientShape(1) + b) * coefficientShape(2) + c) * 3 +
            component
        sums(component) += weight * raw(flat)
    sums.toVector

  private def continuous3(u: Double, v: Double, w: Double): ContinuousIndex[D3] =
    geometryRight(ContinuousIndex.of[D3](u, v, w))

  private def geometryRight[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def bsplineRight[A](value: Either[BSplineError, A]): A =
    value.fold(error => fail(error.message), identity)
