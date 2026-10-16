package reframe4s.halfflow

import image4s.geometry.{Affine, D3, Point}
import reframe4s.halfflow.internal.*

class ForwardMidpointQuerySuite extends munit.FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def affine(scale: Double, shift: Double): Affine[D3] =
    right(Affine.fromRowMajor[D3](Vector(scale, 0.0, 0.0, shift, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)))
  private def nonlinear(frame: RegistrationFrame[Unit], coefficient: Double): DensePull[Unit, Unit] =
    val n = frame.grid.nVoxels
    val values = new Array[Double](3 * n)
    frame.grid.worldCoords.zipWithIndex.foreach: (p, i) =>
      values(i) = p(0) + coefficient * p(0) * p(0)
      values(i + n) = p(1)
      values(i + 2 * n) = p(2)
    right(DensePull.make(frame, frame, DenseVectorField.fromLegacyPlanar(frame.grid, values, DenseVectorFieldKind.SourceCoordinates)))
  private def fixture(config: ResidualQueryConfig = ResidualQueryConfig.default): ForwardMidpointQuery[Unit, Unit, Unit] =
    val work = RegistrationFrame[Unit](SpatialDomainId("work"), GridSpec.identity(Vector(7, 7, 7)))
    val fixed = RegistrationFrame[Unit](SpatialDomainId("fixed"), GridSpec.identity(Vector(7, 7, 7)))
    val moving = RegistrationFrame[Unit](SpatialDomainId("moving"), GridSpec.identity(Vector(7, 7, 7)))
    val state = right(ForwardMidpoint.make(
      right(ForwardMidpointArm.make(nonlinear(work, 0.04), right(AffineIso.make(work, fixed, affine(1.1, 2.0))))),
      right(ForwardMidpointArm.make(nonlinear(work, 0.02), right(AffineIso.make(work, moving, affine(0.9, -3.0)))))))
    right(ForwardMidpointQuery.compile(state, DensePull.identity(work), DensePull.identity(work), config))

  test("exact endpoint affines and between-node inverse correction survive canonical composition"):
    val plan = fixture()
    val x = 2.37
    val fixedX = 1.1 * (x + 0.04 * (4.0 + 0.37 * 5.0)) + 2.0
    val movingX = 0.9 * (x + 0.02 * (4.0 + 0.37 * 5.0)) - 3.0
    val query = right(Point.in[D3](plan.fixedFrame.canonical)(fixedX, 1.2, 3.4))
    val forward = plan.fixedToMoving
    val backward = plan.movingToFixed
    val result = right(forward(query))
    assertEqualsDouble(result.coordinates(0), movingX, 1e-8)
    val roundtrip = right(forward.andThen(backward)(query))
    for axis <- 0 until 3 do assertEqualsDouble(roundtrip.coordinates(axis), query.coordinates(axis), 1e-8)

  test("endpoint failures remain typed and do not return a sampled or seed fallback"):
    val plan = fixture(right(ResidualQueryConfig.make(maximumIterations = 0)))
    val query = right(Point.in[D3](plan.fixedFrame.canonical)(5.4, 1.2, 3.4))
    val error = plan.evaluate(ExportDirection.FixedToMoving, query, plan.newWorkspace()).swap.toOption.get
    assertEquals(error.stage, ForwardQueryStage.Inverse)
    assertEquals(error.failure.reason, ResidualQueryError.IterationLimit)
    assert(plan.fixedToMoving(query).isLeft)

  test("endpoint workspaces and live source frame ownership are checked"):
    val plan = fixture()
    val other = fixture()
    val query = right(Point.in[D3](plan.fixedFrame.canonical)(4.0, 1.2, 3.4))
    val result = plan.evaluate(ExportDirection.FixedToMoving, query, other.newWorkspace()).swap.toOption.get
    assertEquals(result.failure.reason, ResidualQueryError.WorkspaceMismatch)
    val wrong = right(Point.in[D3](other.fixedFrame.canonical)(4.0, 1.2, 3.4))
    assertEquals(plan.evaluate(ExportDirection.FixedToMoving, wrong, plan.newWorkspace()).swap.toOption.get.stage,
      ForwardQueryStage.Frame)
