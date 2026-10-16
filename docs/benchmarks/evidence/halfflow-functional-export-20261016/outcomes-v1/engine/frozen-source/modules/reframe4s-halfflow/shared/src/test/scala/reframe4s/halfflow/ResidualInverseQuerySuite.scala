package reframe4s.halfflow

import gale.linalg.DMat
import image4s.geometry.{D3, Point}
import reframe4s.halfflow.internal.*

class ResidualInverseQuerySuite extends munit.FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def frame(grid: GridSpec): RegistrationFrame[Unit] = RegistrationFrame(SpatialDomainId("query"), grid)
  private def field(f: RegistrationFrame[Unit], value: Vector[Double] => Vector[Double],
      validity: FieldValidity = FieldValidity.All): DensePull[Unit, Unit] =
    val n = f.grid.nVoxels
    val values = new Array[Double](3 * n)
    f.grid.worldCoords.zipWithIndex.foreach: (p, i) =>
      val q = value(p)
      for c <- 0 until 3 do values(i + c * n) = q(c)
    right(DensePull.make(f, f, DenseVectorField.fromLegacyPlanar(f.grid, values,
      DenseVectorFieldKind.SourceCoordinates), validity))

  test("nonlinear query inversion retains correction between original grid nodes"):
    val f = frame(GridSpec.identity(Vector(7, 7, 7)))
    val forward = field(f, p => Vector(p(0) + 0.04 * p(0) * p(0), p(1), p(2)))
    val plan = right(ResidualInverseQuery.compile(forward, DensePull.identity(f)))
    val x = 2.37
    val mapped = x + 0.04 * (4.0 + 0.37 * 5.0)
    val result = right(plan.evaluate(right(Point.in[D3](f.canonical)(mapped, 1.2, 3.4)), plan.newWorkspace()))
    assertEqualsDouble(result.coordinates(0), x, 1e-8)
    assert(result.report.residualMm <= 1e-8)
    assert(result.report.linearSolves <= 8)
    assert(result.report.forwardSamples <= 8 * 13 + 1)
    assert(result.report.derivativeSamples <= 9)

  test("world derivative matches independent finite differences on an oblique left-handed grid"):
    val grid = GridSpec(Vector(7, 7, 7), DMat.dense(4, 4, Vector(
      1.2, -0.4, 0.1, -3.0, 0.3, 1.5, 0.2, 2.0, 0.0, 0.1, -2.0, -1.0, 0.0, 0.0, 0.0, 1.0)))
    val f = frame(grid)
    val forward = field(f, p => Vector(p(0) + 0.002 * p(0) * p(1), p(1), p(2)))
    val p = grid.voxelToWorld(Vector(2.173, 3.229, 2.347))
    val sampler = DenseFieldSampler(grid)
    val differential = new Array[Double](9)
    HalfFlowKernels.sampleCoordinateAtWorldInto(forward.sourceCoordinates, forward.validity,
      sampler, p(0), p(1), p(2), differential)
    assert(sampler.valid)
    def sampled(q: Vector[Double]): Vector[Double] =
      HalfFlowKernels.sampleCoordinateAtWorldInto(forward.sourceCoordinates, forward.validity,
        sampler, q(0), q(1), q(2))
      assert(sampler.valid)
      Vector(sampler.x, sampler.y, sampler.z)
    for axis <- 0 until 3 do
      val plus = sampled(p.updated(axis, p(axis) + 1e-5))
      val minus = sampled(p.updated(axis, p(axis) - 1e-5))
      for row <- 0 until 3 do
        assertEqualsDouble(differential(3 * row + axis), (plus(row) - minus(row)) / 2e-5, 1e-8)
    val plan = right(ResidualInverseQuery.compile(forward, DensePull.identity(f)))
    val mapped = sampled(p)
    val inverse = right(plan.evaluate(right(Point.fromVector(f.canonical, mapped)), plan.newWorkspace()))
    for axis <- 0 until 3 do assertEqualsDouble(inverse.coordinates(axis), p(axis), 1e-8)

  test("derivative support includes corners with zero value weight"):
    val f = frame(GridSpec.identity(Vector(5, 5, 5)))
    val mask = Array.fill(f.grid.nVoxels)(true)
    mask(1) = false
    val identity = DensePull.identity(f)
    val sampler = DenseFieldSampler(f.grid)
    val valid = FieldValidity.copyMask(mask)
    HalfFlowKernels.sampleCoordinateAtWorldInto(identity.sourceCoordinates, valid, sampler, 0.0, 0.0, 0.0)
    assert(sampler.valid)
    HalfFlowKernels.sampleCoordinateAtWorldInto(identity.sourceCoordinates, valid, sampler, 0.0, 0.0, 0.0, new Array[Double](9))
    assert(!sampler.valid)

  test("step-limited failures retain evidence and never become canonical map values"):
    val f = frame(GridSpec.identity(Vector(7, 7, 7)))
    val forward = field(f, p => Vector(p(0) + 12.0, p(1), p(2)))
    val plan = right(ResidualInverseQuery.compile(forward, DensePull.identity(f), right(ResidualQueryConfig.make(maximumIterations = 1))))
    val query = right(Point.in[D3](plan.frame.canonical)(1.0, 2.0, 3.0))
    val failure = plan.evaluate(query, plan.newWorkspace()).swap.toOption.get
    assertEquals(failure.reason, ResidualQueryError.IterationLimit)
    assertEqualsDouble(failure.report.residualMm, 8.0, 1e-10)
    assertEqualsDouble(failure.report.estimate.get(0), -3.0, 1e-10)
    assert(plan.asSpatialMap(query).isLeft)

  test("invalid seeds and singular exterior Jacobians fail explicitly"):
    val f = frame(GridSpec.identity(Vector(7, 7, 7)))
    val identity = DensePull.identity(f)
    val seed = field(f, identity => identity, FieldValidity.copyMask(Array.fill(f.grid.nVoxels)(false)))
    val plan = right(ResidualInverseQuery.compile(identity, seed))
    val query = right(Point.in[D3](f.canonical)(1.0, 2.0, 3.0))
    assertEquals(plan.evaluate(query, plan.newWorkspace()).swap.toOption.get.reason, ResidualQueryError.UnsupportedSeed)
    val forward = field(f, p => Vector(p(0) * (1.0 + 0.1 * p(1)), p(1), p(2)))
    val singular = right(ResidualInverseQuery.compile(forward, identity))
    val outside = right(Point.in[D3](f.canonical)(1.0, -10.0, 2.0))
    assertEquals(singular.evaluate(outside, singular.newWorkspace()).swap.toOption.get.reason, ResidualQueryError.SingularJacobian)

  test("workspaces are owned, single-use and reusable after failure"):
    val f = frame(GridSpec.identity(Vector(7, 7, 7)))
    val plan = right(ResidualInverseQuery.compile(DensePull.identity(f), DensePull.identity(f)))
    val other = right(ResidualInverseQuery.compile(DensePull.identity(f), DensePull.identity(f)))
    val query = right(Point.in[D3](f.canonical)(1.0, 2.0, 3.0))
    assertEquals(plan.evaluate(query, other.newWorkspace()).swap.toOption.get.reason, ResidualQueryError.WorkspaceMismatch)
    val workspace = plan.newWorkspace()
    assert(workspace.acquire())
    assertEquals(plan.evaluate(query, workspace).swap.toOption.get.reason, ResidualQueryError.WorkspaceInUse)
    workspace.release()
    assert(plan.evaluate(query, workspace).isRight)
    assertEquals(plan.evaluateCoordinates(Double.NaN, 0.0, 0.0, workspace).swap.toOption.get.reason, ResidualQueryError.NonFiniteQuery)
    assert(plan.evaluate(query, workspace).isRight)

  test("runtime owners, forward cells and the fixed resource envelope are checked"):
    val f = frame(GridSpec.identity(Vector(7, 7, 7)))
    val other = frame(GridSpec.identity(Vector(7, 7, 7)))
    val plan = right(ResidualInverseQuery.compile(DensePull.identity(f), DensePull.identity(f)))
    val query = right(Point.in[D3](other.canonical)(1.0, 2.0, 3.0))
    assert(plan.evaluate(query, plan.newWorkspace()).swap.toOption.get.reason.isInstanceOf[ResidualQueryError.Frame])
    assert(ResidualInverseQuery.compile(DensePull.identity(f), DensePull.identity(other)).isLeft)
    assert(ResidualInverseQuery.compile(field(f, p => Vector(-p(0), p(1), p(2))), DensePull.identity(f)).isLeft)
    val thin = frame(GridSpec.identity(Vector(1, 7, 7)))
    assert(ResidualInverseQuery.compile(DensePull.identity(thin), DensePull.identity(thin)).isLeft)
    assert(ResidualQueryConfig.make(maximumIterations = 9).isLeft)
    assert(ResidualQueryConfig.make(toleranceMm = Double.NaN).isLeft)
    assert(ResidualQueryConfig.make(maximumBacktracks = 13).isLeft)
    assert(ResidualQueryConfig.make(maximumStepMm = 4.1).isLeft)

  test("compile revalidates unchecked internal pulls instead of trusting construction evidence"):
    val f = frame(GridSpec.identity(Vector(7, 7, 7)))
    val original = DensePull.identity(f)
    val wrongKind = DenseVectorField.fromLegacyPlanar(f.grid, original.sourceCoordinates.copyLegacyPlanar,
      DenseVectorFieldKind.Displacement)
    val unchecked = DensePull.unsafe(f, f, wrongKind, FieldValidity.All)
    assert(ResidualInverseQuery.compile(unchecked, original).isLeft)
    assert(ResidualInverseQuery.compile(original, unchecked).isLeft)
