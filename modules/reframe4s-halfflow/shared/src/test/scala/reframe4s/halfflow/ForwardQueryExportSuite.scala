package reframe4s.halfflow

import image4s.geometry.{Affine, D3, Point}
import reframe4s.halfflow.internal.*

class ForwardQueryExportSuite extends munit.FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def affine(scale: Double, shift: Double): Affine[D3] = right(Affine.fromRowMajor[D3](Vector(
    scale, 0.0, 0.0, shift, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)))
  private def fixture(config: ResidualQueryConfig = ResidualQueryConfig.default,
      cross: Boolean = false, linear: Boolean = false): ForwardMidpointQuery[Unit, Unit, Unit] =
    val grid = GridSpec.identity(Vector(5, 5, 5))
    val work = RegistrationFrame[Unit](SpatialDomainId("work"), grid)
    val fixed = RegistrationFrame[Unit](SpatialDomainId("fixed"), grid)
    val moving = RegistrationFrame[Unit](SpatialDomainId("moving"), GridSpec.identity(Vector(5, 5, 5)))
    def field(coefficient: Double): DensePull[Unit, Unit] =
      val n = grid.nVoxels
      val values = new Array[Double](3*n)
      grid.worldCoords.zipWithIndex.foreach: (p, i) =>
        values(i) = p(0) + coefficient * p(0) * (if cross then p(1) else p(0))
        values(i+n) = p(1)
        values(i+2*n) = p(2)
      right(DensePull.make(work, work, DenseVectorField.fromLegacyPlanar(grid, values, DenseVectorFieldKind.SourceCoordinates)))
    val fixedAffine = if cross || linear then Affine.identity[D3] else affine(1.1, 2.0)
    val movingAffine = if cross then Affine.identity[D3] else if linear then affine(2.0, 4.0) else affine(0.9, -3.0)
    val state = right(ForwardMidpoint.make(
      right(ForwardMidpointArm.make(field(if cross || linear then 0.0 else 0.005), right(AffineIso.make(work, fixed, fixedAffine)))),
      right(ForwardMidpointArm.make(field(if cross then 0.1 else if linear then 0.0 else 0.002), right(AffineIso.make(work, moving, movingAffine))))))
    right(ForwardMidpointQuery.compile(state, DensePull.identity(work), DensePull.identity(work), config))

  test("full endpoint Jacobian agrees with piecewise analytic value and independent finite differences"):
    val plan = fixture()
    val x = 2.37
    val q = Vector(1.1 * (x + 0.005 * (4.0 + 0.37*5.0)) + 2.0, 1.2, 2.3)
    val point = right(Point.fromVector(plan.fixedFrame.canonical, q))
    val geometry = right(plan.evaluateGeometry(ExportDirection.FixedToMoving, point, plan.newWorkspace()))
    val value = right(plan.evaluate(ExportDirection.FixedToMoving, point, plan.newWorkspace()))
    assertEquals(geometry.value.coordinates, value.coordinates)
    assertEqualsDouble(geometry.determinant, 0.9*(1.0+0.002*5)/(1.1*(1.0+0.005*5)), 1e-12)
    val h = 1e-5
    def mapped(dx: Double): Vector[Double] = right(plan.evaluateCoordinates(ExportDirection.FixedToMoving,
      q.updated(0, q(0)+dx), plan.newWorkspace())).coordinates
    assertEqualsDouble(geometry.determinant, (mapped(h)(0)-mapped(-h)(0))/(2*h), 1e-8)
    assertEquals(geometry.value.report.derivativeSamples, value.report.derivativeSamples+1)

  test("admission retains the complete half-voxel lattice, all boundaries and canonical composition"):
    val plan = fixture()
    var observed = 0
    val candidate = right(ForwardQueryExporter.inspect(plan, observe = sample =>
      observed += 1
      assert(sample.result.isRight)))
    assertEquals(observed, 2*729)
    for report <- Vector(candidate.report.fixedToMoving, candidate.report.movingToFixed) do
      assertEquals(report.eligible, 729)
      assertEquals(report.evaluated, 729)
      assertEquals(report.successful, 729)
      assertEquals(report.boundaryEligible, 386)
      assertEquals(report.boundarySuccessful, 386)
      assert(report.minimumSuccessfulJacobian > 0.0)
    val exported = right(ForwardQueryExporter.admit(candidate))
    val q = right(Point.in[D3](exported.fixedFrame.canonical)(3.2, 1.2, 2.3))
    val forward = exported.fixedToMoving
    val backward = exported.movingToFixed
    val back = right(forward.andThen(backward)(q))
    for axis <- 0 until 3 do assertEqualsDouble(back.coordinates(axis), q.coordinates(axis), 1e-7)

  test("failed roots retain every original sample and cannot be admitted through a copied report"):
    val plan = fixture(right(ResidualQueryConfig.make(maximumIterations = 0)))
    var failures = 0
    var total = 0
    val candidate = right(ForwardQueryExporter.inspect(plan, observe = sample =>
      total += 1
      if sample.result.isLeft then failures += 1))
    assertEquals(total, 1458)
    assert(failures > 0)
    val reports = Vector(candidate.report.fixedToMoving, candidate.report.movingToFixed)
    assertEquals(reports.map(r => r.eligible-r.successful).sum, failures)
    assert(reports.forall(_.firstFailure.isDefined))
    val fabricated = candidate.report.copy(fixedToMoving = candidate.report.fixedToMoving.copy(successful = 729, firstFailure = None))
    assertEquals(fabricated.fixedToMoving.successful, 729)
    assert(ForwardQueryExporter.admit(candidate).isLeft)

  test("an admitted map rejects later exterior folding rather than returning a coordinate"):
    val plan = fixture(cross = true)
    val exported = right(ForwardQueryExporter.build(plan))
    val point = right(Point.in[D3](exported.fixedFrame.canonical)(1.0, -20.0, 1.0))
    val error = exported.evaluate(ExportDirection.FixedToMoving, point, exported.newWorkspace()).swap.toOption.get
    error match
      case ForwardQueryCheckFailure.Jacobian(false, determinant) => assert(determinant < 0.0)
      case other => fail(other.toString)
    assert(exported.fixedToMoving(point).isLeft)

  test("every admitted map call enforces its cycle bound between admission samples"):
    val exported = right(ForwardQueryExporter.build(fixture(linear = true), right(ForwardQueryExportConfig.make(maximumCycleMm = 1e-20))))
    val point = right(Point.in[D3](exported.fixedFrame.canonical)(0.1, 1.0, 2.0))
    exported.evaluate(ExportDirection.FixedToMoving, point, exported.newWorkspace()) match
      case Left(ForwardQueryCheckFailure.Cycle(error, limit)) => assert(error > limit)
      case other => fail(other.toString)
    assert(exported.fixedToMoving(point).isLeft)

  test("bundle copies and original frame/workspace owners cannot alter admitted private state"):
    val plan = fixture()
    val candidate = right(ForwardQueryExporter.inspect(plan))
    val bytes = candidate.bundleBytes
    bytes(0) = 0
    val exported = right(ForwardQueryExporter.admit(candidate))
    assertEquals(exported.bundleBytes(0), 'H'.toByte)
    val old = right(Point.in[D3](plan.fixedFrame.canonical)(3.2, 1.2, 2.3))
    assert(exported.evaluate(ExportDirection.FixedToMoving, old, exported.newWorkspace()).isLeft)
    val point = right(Point.in[D3](exported.fixedFrame.canonical)(3.2, 1.2, 2.3))
    assert(exported.evaluate(ExportDirection.FixedToMoving, point, plan.newWorkspace()).isLeft)

  test("budgets and loose solver tolerances reject before any partial admission scan"):
    assert(ForwardQueryExportConfig.make(maximumCycleMm = 0.21).isLeft)
    assert(ForwardQueryExportConfig.make(maximumCycleMm = Double.NaN).isLeft)
    assert(ForwardQueryExportConfig.make(maximumSamplesPerDirection = 0).isLeft)
    var observed = 0
    val rejected = ForwardQueryExporter.inspect(fixture(), right(ForwardQueryExportConfig.make(maximumSamplesPerDirection = 10)), _ => observed += 1)
    assert(rejected.isLeft)
    assertEquals(observed, 0)
    assert(ForwardQueryExporter.inspect(fixture(right(ResidualQueryConfig.make(toleranceMm = 1e-3)))).isLeft)

  test("geometry evaluation requires zero-value-weight derivative support"):
    val grid = GridSpec.identity(Vector(7, 7, 7))
    val work = RegistrationFrame[Unit](SpatialDomainId("masked-work"), grid)
    val fixed = RegistrationFrame[Unit](SpatialDomainId("masked-fixed"), grid)
    val moving = RegistrationFrame[Unit](SpatialDomainId("masked-moving"), grid)
    val identity = DensePull.identity(work)
    val masked = right(DensePull.make(work, work, identity.sourceCoordinates,
      FieldValidity.copyMask(Array.tabulate(grid.nVoxels)(_ != 0))))
    val state = right(ForwardMidpoint.make(
      right(ForwardMidpointArm.make(identity, right(AffineIso.make(work, fixed, Affine.identity[D3])))),
      right(ForwardMidpointArm.make(masked, right(AffineIso.make(work, moving, Affine.identity[D3]))))))
    val plan = right(ForwardMidpointQuery.compile(state, identity, identity))
    // An absent value-bearing corner must also fail geometry evaluation.
    val point = right(Point.in[D3](fixed.canonical)(0.0, 0.0, 0.0))
    assert(plan.evaluateGeometry(ExportDirection.FixedToMoving, point, plan.newWorkspace()).isLeft)
    val corner = right(Point.in[D3](fixed.canonical)(1.0, 0.0, 0.0))
    assert(plan.evaluate(ExportDirection.FixedToMoving, corner, plan.newWorkspace()).isRight)
    // At x=1 the active cell is [1,2]; test support using an invalid zero-weight vertex at x=1 instead.
    val mask2 = FieldValidity.copyMask(Array.tabulate(grid.nVoxels)(_ != 1))
    val masked2 = right(DensePull.make(work, work, identity.sourceCoordinates, mask2))
    val state2 = right(ForwardMidpoint.make(state.fixed,
      right(ForwardMidpointArm.make(masked2, state.moving.affine))))
    val plan2 = right(ForwardMidpointQuery.compile(state2, identity, identity))
    assert(plan2.evaluate(ExportDirection.FixedToMoving, point, plan2.newWorkspace()).isRight)
    assertEquals(plan2.evaluateGeometry(ExportDirection.FixedToMoving, point, plan2.newWorkspace())
      .swap.toOption.get.stage, ForwardQueryStage.Geometry)
