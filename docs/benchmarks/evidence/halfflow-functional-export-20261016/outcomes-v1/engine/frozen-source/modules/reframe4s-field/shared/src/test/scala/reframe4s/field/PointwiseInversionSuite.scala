package reframe4s.field

import image4s.geometry.D2
import image4s.geometry.D3
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import reframe4s.resample.Interpolation

import FieldFixtures.*

final class PointwiseInversionSuite extends munit.FunSuite:
  private val t = frame[D3]("pointwise-input")
  private val u = frame[D3]("pointwise-intermediate")
  private val s = frame[D3]("pointwise-output")
  private val settings = right(InversionSettings.create(tolerance = 1e-10))

  private def point[D <: Dim](f: Frame[D], values: Vector[Double])(using Dimension[D]): Point[Frame[D], D] =
    right(Point.fromVector(f, values).flatMap(p =>
      Frame.alignOwners[D, f.type, Frame[D]](f, f).flatMap(_.pointToRight(p))
    ))

  private def analytic(
      f: Vector[Double] => Vector[Double]
  ): CoverageReportingMap[Frame[D3], Frame[D3], D3] =
    new CoverageReportingMap[Frame[D3], Frame[D3], D3]:
      val source = t
      val target = u
      def applyWithCoverage(p: Point[Frame[D3], D3]): Either[MapError, CoveredPoint[Frame[D3], D3]] =
        Right(CoveredPoint(point(u, f(p.coordinates)), SupportOutcome.Covered))

  private def assertEvidence(
      result: PointwiseInverseResult[Frame[D3], Frame[D3], D3],
      pull: SpatialMap[Frame[D3], Frame[D3], D3]
  ): Unit =
    val best = result.best.getOrElse(fail("missing measured iterate"))
    val mapped = right(pull(best.point)).coordinates
    val residual = mapped.zip(result.query.coordinates)
      .foldLeft(0.0)((norm, pair) => math.hypot(norm, pair._1 - pair._2))
    assertEqualsDouble(best.residual, residual, 1e-14)
    assert(best.residual.isFinite)

  test("constant displacement uses no lattice and records the final update"):
    val offset = Vector(2.0, -3.0, 0.5)
    val pull = analytic(_.zip(offset).map(_ + _))
    val query = point(u, Vector(4.0, 2.0, 8.0))
    val result = PointwiseInversion.displacement(pull, settings).at(query)
    assertEquals(result.status, PointwiseInverseStatus.Converged)
    assertEquals(result.iterations, 1)
    assertEquals(result.best.get.point.coordinates, Vector(2.0, 5.0, 7.5))
    assertEvidence(result, pull)

  test("identity converges on the initial evaluation in two dimensions"):
    val f = frame[D2]("pointwise-2d")
    val inverse = PointwiseInversion.displacement(CoverageReportingMap.lift(SpatialMap.identity(f)), settings)
    val query = point(f, Vector(1.25, -3.5))
    val result = inverse.at(query)
    assertEquals(result.status, PointwiseInverseStatus.Converged)
    assertEquals(result.iterations, 0)
    assertEquals(result.best.get.point.coordinates, query.coordinates)
    assertEquals(result.best.get.residual, 0.0)

  test("analytic affine removal handles reflection, rotation, shear, scale and translation"):
    val pull = analytic(p => Vector(p(0) + 1.5, p(1) - 0.75, p(2) + 0.125))
    val affine = affineMap(u, s, rowMajor[D3](
      0.0, -3.0, 0.5, 120.0,
      2.0, 0.0, 0.0, -80.0,
      0.0, 0.0, -4.0, 30.0,
      0.0, 0.0, 0.0, 1.0
    ))
    val full = CoverageReportingMap.compose(pull, affine)
    val expected = point(t, Vector(2.25, 3.75, -1.5))
    val query = right(full(expected))
    val result = right(PointwiseInversion.displacementThenAffine(pull, affine, settings)).at(query)
    assertEquals(result.status, PointwiseInverseStatus.Converged)
    assertEquals(result.iterations, 1)
    result.best.get.point.coordinates.zip(expected.coordinates).foreach((a, b) => assertEqualsDouble(a, b, 1e-12))
    assertEvidence(result, full)
    // Iterating the entire affine-composed map instead is expansive here.
    assertEquals(PointwiseInversion.displacement(full, settings).at(query).status, PointwiseInverseStatus.Diverged)

  test("smooth contractive fields agree with independent bracketed bisection"):
    val pull = analytic(_.map(x => x + 0.7 * math.sin(0.6 * x)))
    val inverse = PointwiseInversion.displacement(pull, settings)
    def bisect(y: Double): Double =
      var lo = y - 0.7
      var hi = y + 0.7
      for _ <- 0 until 60 do
        val mid = (lo + hi) / 2.0
        if mid + 0.7 * math.sin(0.6 * mid) > y then hi = mid else lo = mid
      (lo + hi) / 2.0
    val random = new scala.util.Random(20261001L)
    for _ <- 0 until 100 do
      val query = point(u, Vector.fill(3)(20.0 * random.nextDouble() - 10.0))
      val result = inverse.at(query)
      assertEquals(result.status, PointwiseInverseStatus.Converged)
      // The derivative is >= 0.58, so coordinate error <= residual / 0.58.
      result.best.get.point.coordinates.zip(query.coordinates.map(bisect)).foreach((a, b) =>
        assertEqualsDouble(a, b, settings.tolerance / 0.58 + 1e-14)
      )
      assertEvidence(result, pull)

  test("affine scale is included in the convergence tolerance"):
    val pull = analytic(p => Vector(p(0) * 1.4, p(1), p(2)))
    val affine = affineMap(u, s, FieldFixtures.affine[D3](Vector(7.0, -5.0, 2.0), Vector(1000000.0, 0.01, 2.0)))
    val full = CoverageReportingMap.compose(pull, affine)
    val query = right(full(point(t, Vector(0.71, -2.1, 3.3))))
    val tolerance = right(InversionSettings.create(tolerance = 1e-7))
    val result = right(PointwiseInversion.displacementThenAffine(pull, affine, tolerance)).at(query)
    assertEquals(result.status, PointwiseInverseStatus.Converged)
    assert(result.best.get.residual <= tolerance.tolerance)
    assertEvidence(result, full)
    val onlyDisplacement = PointwiseInversion.displacement(pull, tolerance).at(point(u, Vector(0.994, -2.1, 3.3)))
    assert(result.iterations > onlyDisplacement.iterations)

  test("sampled linear and cubic maps solve the actual interpolated map at off-lattice queries"):
    val g = grid(t, Vector(17, 17, 17), FieldFixtures.affine[D3](Vector(-4.0, -4.0, -4.0), Vector(0.5, 0.5, 0.5)))
    for interpolation <- Vector(Interpolation.Linear, Interpolation.Cubic) do
      val pull = denseMap(g, u, interpolation)(_.map(x => x + 0.5 * math.sin(x)))
      val inverse = PointwiseInversion.displacement(pull, settings)
      for value <- Vector(-2.13, -0.21, 0.137, 1.73, 2.19) do
        val query = point(u, Vector(value, value, value))
        var lo = value - 0.6
        var hi = value + 0.6
        for _ <- 0 until 50 do
          val mid = (lo + hi) / 2.0
          val mapped = right(pull(point(t, Vector(mid, mid, mid)))).coordinates.head
          if mapped > value then hi = mid else lo = mid
        val result = inverse.at(query)
        assertEquals(result.status, PointwiseInverseStatus.Converged)
        result.best.get.point.coordinates.foreach(x => assertEqualsDouble(x, (lo + hi) / 2.0, 3e-10))
        assertEvidence(result, pull)

  test("an iteration limit retains the best measured point with its own residual"):
    val pull = analytic(_.map(_ * 1.5))
    val limited = right(InversionSettings.create(maximumIterations = 1, tolerance = 1e-12))
    val result = PointwiseInversion.displacement(pull, limited).at(point(u, Vector(1.0, 0.0, 0.0)))
    assertEquals(result.status, PointwiseInverseStatus.MaxIterations)
    assertEquals(result.iterations, 1)
    assertEquals(result.best.get.point.coordinates, Vector(0.5, 0.0, 0.0))
    assertEquals(result.best.get.residual, 0.25)
    assertEvidence(result, pull)

  test("noncontractive oscillation and divergence return distinct statuses"):
    for (scale, expected) <- Vector(2.0 -> PointwiseInverseStatus.MaxIterations, 3.0 -> PointwiseInverseStatus.Diverged) do
      val pull = analytic(_.map(_ * scale))
      val result = PointwiseInversion.displacement(pull, settings).at(point(u, Vector(0.25, 0.0, 0.0)))
      assertEquals(result.status, expected)
      assertEquals(result.best.get.point.coordinates, Vector(0.25, 0.0, 0.0))
      assertEquals(result.iterations, if scale == 2.0 then settings.maximumIterations else 3)
      assertEvidence(result, pull)

  test("support edges reject boundary extension even when it has zero residual"):
    val g = grid(t, Vector(5, 5, 5), FieldFixtures.affine[D3](Vector(0.0, 0.0, 0.0), Vector(1.0, 1.0, 1.0)))
    for boundary <- Vector(CoordinateBoundaryPolicy.Reject, CoordinateBoundaryPolicy.PreserveSource, CoordinateBoundaryPolicy.Constant(Vector(4.5, 2.0, 2.0))) do
      val pull = denseMap(g, u, boundary = boundary)(identity)
      val inverse = PointwiseInversion.displacement(pull, settings)
      for edge <- Vector(0.0, 4.0) do
        assertEquals(inverse.at(point(u, Vector(edge, 2.0, 2.0))).status, PointwiseInverseStatus.Converged)
      val result = inverse.at(point(u, Vector(4.5, 2.0, 2.0)))
      assertEquals(result.status, PointwiseInverseStatus.LeftSupport)
      assertEquals(result.iterations, 0)
      assertEquals(result.best, None)

  test("an update leaving support retains earlier evidence but never succeeds"):
    val g = grid(t, Vector(5, 5, 5), FieldFixtures.affine[D3](Vector(0.0, 0.0, 0.0), Vector(1.0, 1.0, 1.0)))
    val pull = denseMap(g, u, boundary = CoordinateBoundaryPolicy.PreserveSource)(p => p.updated(0, p(0) + 1.0))
    val result = PointwiseInversion.displacement(pull, settings).at(point(u, Vector(0.5, 2.0, 2.0)))
    assertEquals(result.status, PointwiseInverseStatus.LeftSupport)
    assertEquals(result.iterations, 1)
    assertEquals(result.best.get.residual, 1.0)
    assertEvidence(result, pull)

  test("affine removal happens before the displacement support check"):
    val g = grid(t, Vector(5, 5, 5), FieldFixtures.affine[D3](Vector(0.0, 0.0, 0.0), Vector(1.0, 1.0, 1.0)))
    val pull = denseMap(g, u)(p => p.updated(0, p(0) + 0.25))
    val affine = affineMap(u, s, FieldFixtures.affine[D3](Vector(100.0, -80.0, 20.0), Vector(3.0, 2.0, 4.0)))
    val full = CoverageReportingMap.compose(pull, affine)
    val query = right(full(point(t, Vector(1.5, 2.0, 2.0))))
    val result = right(PointwiseInversion.displacementThenAffine(pull, affine, settings)).at(query)
    assertEquals(result.status, PointwiseInverseStatus.Converged)
    assertEvidence(result, full)

  test("structural failures are typed and do not discard preceding measurements"):
    val error = MapError.InvalidFiniteDifferenceStep(-1.0)
    val pull = new CoverageReportingMap[Frame[D3], Frame[D3], D3]:
      val source = t
      val target = u
      def applyWithCoverage(p: Point[Frame[D3], D3]): Either[MapError, CoveredPoint[Frame[D3], D3]] =
        if p.coordinates.head < 1.0 then Left(error)
        else Right(CoveredPoint(point(u, p.coordinates.updated(0, p.coordinates.head + 1.0)), SupportOutcome.Covered))
    val result = PointwiseInversion.displacement(pull, settings).at(point(u, Vector(1.5, 0.0, 0.0)))
    assertEquals(result.status, PointwiseInverseStatus.Failure(error))
    assertEquals(result.iterations, 1)
    assertEquals(result.best.get.residual, 1.0)

  test("query, intermediate and returned frame owners are checked"):
    val pull = analytic(identity)
    val wrongQuery = PointwiseInversion.displacement(pull, settings).at(point(s, Vector(1.0, 2.0, 3.0)))
    assert(wrongQuery.status.isInstanceOf[PointwiseInverseStatus.Failure])
    assertEquals(wrongQuery.best, None)
    val wrongAffine = affineMap(s, s, image4s.geometry.Affine.identity[D3])
    assert(PointwiseInversion.displacementThenAffine(pull, wrongAffine, settings).isLeft)
    val bad = new CoverageReportingMap[Frame[D3], Frame[D3], D3]:
      val source = t
      val target = u
      def applyWithCoverage(p: Point[Frame[D3], D3]): Either[MapError, CoveredPoint[Frame[D3], D3]] =
        Right(CoveredPoint(point(s, p.coordinates), SupportOutcome.Covered))
    val result = PointwiseInversion.displacement(bad, settings).at(point(u, Vector(0.0, 0.0, 0.0)))
    assert(result.status.isInstanceOf[PointwiseInverseStatus.Failure])
    assertEquals(result.best, None)

  test("large finite residuals do not overflow solely from squaring"):
    val pull = analytic(p => p.updated(0, p.head + 1e200))
    val result = PointwiseInversion.displacement(pull, settings).at(point(u, Vector(0.0, 0.0, 0.0)))
    assertEquals(result.status, PointwiseInverseStatus.Converged)
    assertEquals(result.iterations, 1)
    assertEquals(result.best.get.point.coordinates.head, -1e200)

  test("nonfinite residuals terminate as divergence without fabricated evidence"):
    val pull = analytic(_ => Vector(1e308, 0.0, 0.0))
    val result = PointwiseInversion.displacement(pull, settings).at(point(u, Vector(-1e308, 0.0, 0.0)))
    assertEquals(result.status, PointwiseInverseStatus.Diverged)
    assertEquals(result.iterations, 0)
    assertEquals(result.best, None)
