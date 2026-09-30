package reframe4s.field

import reframe4s.core.EvidenceError
import reframe4s.core.ImplementationRevision
import reframe4s.core.InverseDirection
import reframe4s.core.InversionOutcome
import reframe4s.core.MapError
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Point

import FieldFixtures.*

final class NumericalInversionSuite extends munit.FunSuite:
  private val t = frame[D3]("inverse-reference")
  private val s = persistentFrame[D3]("inverse-source")
  private val revision = right(ImplementationRevision.parse("numerical-inversion-suite-v1"))

  // pull(y) = y + a sin(omega y) per axis; a * omega = 0.4 < 1, so the
  // fixed-point iteration contracts and the inverse is unique.
  private val amplitude = 1.0
  private val omega = 0.4
  private val spacing = 0.5

  private val pullGrid =
    grid(t, Vector(24, 20, 16), affine[D3](Vector(0.0, 0.0, 0.0), Vector(spacing, spacing, spacing)))

  private def sinusoid(a: Double, w: Double)(y: Vector[Double]): Vector[Double] =
    y.map(value => value + a * math.sin(w * value))

  /** Newton solve of y + a sin(w y) = x to machine precision. */
  private def exactInverse(a: Double, w: Double)(x: Double): Double =
    var y = x
    var step = 0
    while step < 60 do
      y -= (y + a * math.sin(w * y) - x) / (1.0 + a * w * math.cos(w * y))
      step += 1
    y

  private val settings = right(InversionSettings.create(maximumIterations = 200, tolerance = 1e-10))

  test("a smooth sinusoidal displacement inverts within its interpolation bound"):
    val pull = denseMap(pullGrid, s)(sinusoid(amplitude, omega))
    val lattice =
      persistentGrid(s, "inverse-analytic", Vector(20, 16, 12), affine[D3](Vector(1.0, 1.0, 1.0), Vector(spacing, spacing, spacing)))
    val gates = right(InversionGates.create(1.0, 0.05, 0.05, interiorMargin = 1))
    val inverse = right(NumericalInversion.invert(pull, lattice, settings, gates, revision))
    val evidence = inverse.evidence

    // Linear interpolation of the pull errs by at most a w^2 h^2 / 8; the
    // inverse amplifies that by at most 1 / (1 - a w).
    val bound =
      amplitude * omega * omega * spacing * spacing / 8.0 / (1.0 - amplitude * omega)
    var worst = 0.0
    for (index, point) <- latticePoints(lattice) do
      assertEquals(evidence.statusAt(index), Some(InversePointStatus.Converged))
      val estimated =
        Vector.tabulate(3)(component =>
          right(inverse.samples.coordinates.valueAt(index, Vector(component)))
        )
      val exact = point.coordinates.map(exactInverse(amplitude, omega))
      estimated.indices.foreach(axis =>
        worst = math.max(worst, math.abs(estimated(axis) - exact(axis)))
      )
    assert(worst <= bound + 1e-9, s"worst inverse error $worst exceeds $bound")
    assert(worst > 0.0)

    assertEquals(evidence.statusCounts, InversionStatusCounts(3840L, 0L, 0L, 0L))
    assertEqualsDouble(evidence.coveredFraction, 1.0, 0.0)
    assertEquals(evidence.boundary, BoundaryUse(CoordinateBoundaryPolicy.Reject, 0L))
    val forward = evidence.forwardResidual.getOrElse(fail("no forward residual"))
    val reverse = evidence.reverseResidual.getOrElse(fail("no reverse residual"))
    assert(forward.maximum <= 1e-10)
    assertEquals(forward.sampleCount, 18L * 14L * 10L)
    assert(reverse.sampleCount > 1000L)
    assert(reverse.p99 <= reverse.maximum && reverse.mean <= reverse.p99)
    assert(reverse.maximum <= 0.05)
    assert(evidence.maximumIterationsUsed > 1)

    val estimate = inverse.estimate
    assertEqualsDouble(estimate.residual.coveredFraction, 1.0, 0.0)
    assertEquals(estimate.status.outcome, InversionOutcome.Converged)
    assertEquals(estimate.domain.resolution, lattice.shape)
    val probe = right(Point.fromVector(s, Vector(5.3, 4.1, 3.7)))
    val pushed = right(estimate.value(widen(probe))).coordinates
    val expected = Vector(5.3, 4.1, 3.7).map(exactInverse(amplitude, omega))
    pushed.indices.foreach(axis =>
      assertEqualsDouble(pushed(axis), expected(axis), bound + 0.02)
    )

  test("coverage below the gate is a typed failure carrying the evidence"):
    val pull = denseMap(pullGrid, s)(sinusoid(amplitude, omega))
    // This lattice reaches past the pull's support on every axis.
    val lattice =
      persistentGrid(s, "inverse-wide", Vector(18, 16, 14), affine[D3](Vector(-2.0, -2.0, -2.0), Vector(0.75, 0.75, 0.75)))
    val strict = right(InversionGates.create(0.99, 0.05, 0.05, interiorMargin = 1))
    NumericalInversion.invert(pull, lattice, settings, strict, revision) match
      case Left(InversionError.GatesFailed(failures, evidence)) =>
        assert(
          failures.exists {
            case InversionGateFailure.CoverageBelowMinimum(observed, 0.99) =>
              observed < 0.99
            case _ => false
          },
          failures.toString
        )
        val counts = evidence.statusCounts
        assert(counts.outsideCoverage > 0L)
        assertEquals(counts.total, 18L * 16L * 14L)
        assertEquals(evidence.boundary.points, counts.outsideCoverage)
        assertEqualsDouble(
          evidence.coveredFraction,
          (counts.converged + counts.maxIterations).toDouble / counts.total.toDouble,
          1e-15
        )
        val corner = Vector(0, 0, 0)
        assertEquals(evidence.statusAt(corner), Some(InversePointStatus.OutsideCoverage))
        assertEquals(evidence.domainMask.valueAt(corner).toOption, Some(false))
      case other => fail(s"expected a coverage gate failure, got $other")

    val lenient = right(InversionGates.create(0.3, 0.05, 0.05, interiorMargin = 1))
    val inverse = right(NumericalInversion.invert(pull, lattice, settings, lenient, revision))
    assert(inverse.evidence.coveredFraction < 0.99)
    val outside = right(Point.fromVector(s, Vector(-2.0, -2.0, -2.0)))
    inverse.estimate.value(widen(outside)) match
      case Left(MapError.OutsideDomain(_)) => ()
      case other => fail(s"expected the estimate to reject a point outside its domain, got $other")

  test("a folding displacement diverges in the interior and fails its gate"):
    val wide =
      grid(t, Vector(40, 16, 16), affine[D3](Vector(-5.0, -2.0, -2.0), Vector(0.5, 0.5, 0.5)))
    // a * w = 2 folds the map, and the fixed-point iteration is expansive
    // wherever |cos(w y)| > 1/2.
    val pull = denseMap(wide, s)(y => Vector(y(0) + 2.0 * math.sin(y(0)), y(1), y(2)))
    val lattice =
      persistentGrid(s, "inverse-fold", Vector(20, 4, 4), affine[D3](Vector(0.0, 1.0, 1.0), Vector(0.5, 0.5, 0.5)))
    val gates = right(InversionGates.create(0.0, 1.0, 1.0, interiorMargin = 1))
    NumericalInversion.invert(pull, lattice, settings, gates, revision) match
      case Left(InversionError.GatesFailed(failures, evidence)) =>
        assert(evidence.interiorStatusCounts.diverged > 0L)
        assert(
          failures.contains(
            InversionGateFailure.InteriorDivergence(evidence.interiorStatusCounts.diverged)
          ),
          failures.toString
        )
        val diverged =
          latticePoints(lattice).count((index, _) =>
            evidence.statusAt(index).contains(InversePointStatus.Diverged)
          )
        assertEquals(diverged.toLong, evidence.statusCounts.diverged)
      case other => fail(s"expected an interior divergence failure, got $other")

  test("residual gates name the direction that failed"):
    val pull = denseMap(pullGrid, s)(sinusoid(amplitude, omega))
    val lattice =
      persistentGrid(s, "inverse-tight", Vector(10, 8, 6), affine[D3](Vector(2.0, 2.0, 2.0), Vector(0.5, 0.5, 0.5)))
    // The solver reaches 1e-10 at lattice points, but the interpolated
    // inverse cannot match 1e-6 on the pull lattice.
    val gates = right(InversionGates.create(1.0, 1e-6, 1e-6, interiorMargin = 1))
    NumericalInversion.invert(pull, lattice, settings, gates, revision) match
      case Left(InversionError.GatesFailed(failures, _)) =>
        assert(
          failures.exists {
            case InversionGateFailure.MaximumResidualExceeded(InverseDirection.Reverse, _, _) => true
            case _ => false
          },
          failures.toString
        )
        assert(
          !failures.exists {
            case InversionGateFailure.MaximumResidualExceeded(InverseDirection.Forward, _, _) => true
            case _ => false
          },
          failures.toString
        )
      case other => fail(s"expected a reverse residual failure, got $other")

  test("the primitive linear sampler reproduces applyWithCoverage exactly"):
    val oblique =
      grid(
        t,
        Vector(6, 5, 4),
        right(
          image4s.geometry.Affine.fromOriginSpacingDirection[D3](
            Vector(-1.0, 2.0, 0.5),
            Vector(0.7, 1.1, 0.9),
            Vector(0.6, 0.8, 0.0, -0.8, 0.6, 0.0, 0.0, 0.0, 1.0)
          )
        )
      )
    val random = new scala.util.Random(24092026L)
    val points =
      Vector.fill(400)(Vector(-4.0 + 12.0 * random.nextDouble(), -2.0 + 10.0 * random.nextDouble(), -1.0 + 5.0 * random.nextDouble())) ++
        latticePoints(oblique).map(_._2.coordinates)
    for
      boundary <- Vector(
        CoordinateBoundaryPolicy.Reject,
        CoordinateBoundaryPolicy.Constant(Vector(9.0, -9.0, 0.5)),
        CoordinateBoundaryPolicy.PreserveSource
      )
    do
      val dense = denseMap(oblique, s, boundary = boundary)(sinusoid(0.7, 0.9))
      val sampler = dense.linearSampler.getOrElse(fail("linear map has no sampler"))
      val out = new Array[Double](3)
      var outcomes = Set.empty[Option[SupportOutcome]]
      for coordinates <- points do
        val point = right(Point.fromVector(t, coordinates))
        val widened =
          right(Frame.alignOwners[D3, t.type, Frame[D3]](t, t).flatMap(_.pointToRight(point)))
        val fast = sampler.sample(coordinates.toArray, out)
        outcomes += fast
        dense.applyWithCoverage(widened) match
          case Right(covered) =>
            assertEquals(fast, Some(covered.outcome))
            assertEquals(out.toVector, covered.point.coordinates)
          case Left(MapError.OutsideDomain(_)) => assertEquals(fast, None)
          case Left(error) => fail(error.message)
      assert(outcomes.size >= 2, s"$boundary exercised only $outcomes")
    val cubic =
      denseMap(oblique, s, interpolation = reframe4s.resample.Interpolation.Cubic)(identity)
    assertEquals(cubic.linearSampler, None)

  test("cubic pulls invert through the pointwise path"):
    val pull =
      denseMap(pullGrid, s, interpolation = reframe4s.resample.Interpolation.Cubic)(
        sinusoid(amplitude, omega)
      )
    val lattice =
      persistentGrid(s, "inverse-cubic", Vector(5, 4, 4), affine[D3](Vector(3.0, 3.0, 3.0), Vector(0.5, 0.5, 0.5)))
    val gates = right(InversionGates.create(1.0, 0.05, 0.05, interiorMargin = 1))
    val inverse = right(NumericalInversion.invert(pull, lattice, settings, gates, revision))
    assertEquals(inverse.evidence.statusCounts.converged, 80L)
    for (index, point) <- latticePoints(lattice) do
      val exact = point.coordinates.map(exactInverse(amplitude, omega))
      for component <- 0 until 3 do
        assertEqualsDouble(
          right(inverse.samples.coordinates.valueAt(index, Vector(component))),
          exact(component),
          5e-3
        )

  test("settings, gates and lattice persistence are validated"):
    assertEquals(
      InversionSettings.create(maximumIterations = 0).map(_ => ()),
      Left(InversionError.InvalidSetting("maximumIterations", 0.0))
    )
    assertEquals(
      InversionSettings.create(divergenceRatio = 1.0).map(_ => ()),
      Left(InversionError.InvalidSetting("divergenceRatio", 1.0))
    )
    assertEquals(
      InversionGates.create(1.5, 0.1, 0.1, 0).map(_ => ()),
      Left(InversionError.InvalidSetting("minimumCoverage", 1.5))
    )
    assertEquals(
      InversionGates.create(1.0, 0.1, 0.1, -1).map(_ => ()),
      Left(InversionError.InvalidSetting("interiorMargin", -1.0))
    )
    val pull = denseMap(pullGrid, s)(sinusoid(amplitude, omega))
    val ephemeral =
      grid(s, Vector(4, 4, 4), affine[D3](Vector(2.0, 2.0, 2.0), Vector(0.5, 0.5, 0.5)))
    val gates = right(InversionGates.create(1.0, 1.0, 1.0, 0))
    assertEquals(
      NumericalInversion.invert(pull, ephemeral, settings, gates, revision).map(_ => ()),
      Left(InversionError.Evidence(EvidenceError.PersistentGridRequired))
    )

  private def widen(point: Point[s.type, D3]): Point[Frame[D3], D3] =
    right(Frame.alignOwners[D3, s.type, Frame[D3]](s, s).flatMap(_.pointToRight(point)))
