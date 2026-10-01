package reframe4s.halfflow

import image4s.geometry.Affine as CanonicalAffine
import image4s.geometry.D3
import image4s.geometry.Frame as CanonicalFrame
import image4s.geometry.Point
import reframe4s.core.AffineMap
import reframe4s.core.Jet1
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import reframe4s.halfflow.internal.*

final class SuppliedAffineAdapterSuite extends munit.FunSuite:
  sealed trait Work
  sealed trait Fixed
  sealed trait Moving

  test("moving-to-fixed input is inverted exactly once on asymmetric physical grids") {
    val fixedGrid = GridSpec(
      Vector(7, 6, 5),
      DMat.fromRows(
        Vector(
          Vector(1.7, 0.2, 0.0, -13.0),
          Vector(0.0, 2.1, -0.1, 8.5),
          Vector(0.1, 0.0, 2.8, 21.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )
      )
    )
    val movingGrid = GridSpec(
      Vector(9, 8, 6),
      DMat.fromRows(
        Vector(
          Vector(2.3, 0.0, 0.1, 4.0),
          Vector(-0.2, 1.4, 0.0, -17.0),
          Vector(0.0, 0.1, 3.2, 6.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )
      )
    )
    val workGrid = GridSpec(
      Vector(6, 5, 4),
      DMat.fromRows(
        Vector(
          Vector(2.0, 0.1, 0.0, -6.0),
          Vector(0.0, 1.8, 0.2, -2.0),
          Vector(0.1, 0.0, 2.5, 11.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )
      )
    )
    val movingToFixedMatrix = DMat.fromRows(
      Vector(
        Vector(1.08, 0.07, -0.02, 5.5),
        Vector(-0.04, 0.96, 0.05, -3.25),
        Vector(0.03, -0.01, 1.04, 2.75),
        Vector(0.0, 0.0, 0.0, 1.0)
      )
    )
    val fixture = images(fixedGrid, movingGrid, workGrid)
    val canonical = canonicalMap(movingToFixedMatrix)
    val adapted = SuppliedAffineInitialization
      .fromMovingToFixed(fixture.fixed, fixture.moving, fixture.work, canonical)
      .fold(error => fail(error.message), identity)
    val expectedFixedToMoving = DMat.invert(movingToFixedMatrix).fold(reason => fail(reason), identity)

    assertEquals(adapted.suppliedDirection, SuppliedAffineDirection.MovingToFixed)
    assertEquals(adapted.diagnostics.origin, AffineInitializationOrigin.Supplied)
    assertEquals(adapted.diagnostics.evaluations, 0)
    assertDenseLandmark(
      adapted.fixedToMoving.dense.forward,
      3,
      2,
      1,
      expectedFixedToMoving,
      2e-11
    )

    val legacyResult = adapted.fixedToMoving.dense
    assertDenseLandmark(legacyResult.backward, 4, 3, 2, movingToFixedMatrix, 2e-11)

    val fixedArm = adapted.initial.fixed.denseForward.fold(error => fail(error.message), identity)
    val movingArm = adapted.initial.moving.denseForward.fold(error => fail(error.message), identity)
    val fixedPoint = densePoint(fixedArm, 2, 2, 1)
    val movingPoint = densePoint(movingArm, 2, 2, 1)
    val composedMoving = apply(expectedFixedToMoving, fixedPoint)
    assertWorldClose(movingPoint, composedMoving, 2e-9)
  }

  test("fixed-to-moving adapter is exactly the existing supplied initializer") {
    val fixedGrid = GridSpec.identity(Vector(8, 7, 6))
    val movingGrid = GridSpec.identity(Vector(9, 8, 7))
    val workGrid = GridSpec.identity(Vector(6, 5, 4))
    val fixedToMovingMatrix = DMat.fromRows(
      Vector(
        Vector(1.03, 0.02, 0.01, -1.5),
        Vector(-0.01, 0.98, 0.04, 2.25),
        Vector(0.0, -0.03, 1.01, 0.75),
        Vector(0.0, 0.0, 0.0, 1.0)
      )
    )
    val fixture = images(fixedGrid, movingGrid, workGrid)
    val canonical = canonicalMap(fixedToMovingMatrix)
    val adapted = SuppliedAffineInitialization
      .fromFixedToMoving(fixture.fixed, fixture.moving, fixture.work, canonical)
      .fold(error => fail(error.message), identity)
    val existing = AffineInitializer
      .supplied(fixture.fixed, fixture.moving, fixture.work, Affine3D(fixedToMovingMatrix))
      .fold(error => fail(error.message), identity)

    assertEquals(adapted.suppliedDirection, SuppliedAffineDirection.FixedToMoving)
    assertEquals(adapted.diagnostics.origin, existing.diagnostics.origin)
    assertEquals(adapted.diagnostics.evaluations, existing.diagnostics.evaluations)
    assertEqualsDouble(adapted.diagnostics.determinant, existing.diagnostics.determinant, 0.0)
    assert(adapted.diagnostics.initialValue.isNaN && existing.diagnostics.initialValue.isNaN)
    assert(adapted.diagnostics.finalValue.isNaN && existing.diagnostics.finalValue.isNaN)
    assertFieldsClose(
      adapted.initial.fixed.denseForward.fold(error => fail(error.message), identity),
      ForwardMidpoint
        .fromLegacy(existing.midpoint)
        .fixed
        .denseForward
        .fold(error => fail(error.message), identity),
      0.0
    )
    assertFieldsClose(
      adapted.initial.moving.denseForward.fold(error => fail(error.message), identity),
      ForwardMidpoint
        .fromLegacy(existing.midpoint)
        .moving
        .denseForward
        .fold(error => fail(error.message), identity),
      0.0
    )
  }

  test("a valid affine with an unsupported midpoint root fails explicitly") {
    val grid = GridSpec.identity(Vector(5, 5, 5))
    val fixture = images(grid, grid, grid)
    val halfTurn = DMat.fromRows(
      Vector(
        Vector(-1.0, 0.0, 0.0, 0.0),
        Vector(0.0, -1.0, 0.0, 0.0),
        Vector(0.0, 0.0, 1.0, 0.0),
        Vector(0.0, 0.0, 0.0, 1.0)
      )
    )
    val result = SuppliedAffineInitialization.fromFixedToMoving(
      fixture.fixed,
      fixture.moving,
      fixture.work,
      canonicalMap(halfTurn)
    )

    result match
      case Left(RegistrationError.InvalidAffine(context, _)) =>
        assertEquals(context, "affine square root")
      case Left(RegistrationError.AffineSquareRootDidNotConverge(_, _)) => ()
      case Left(other) => fail(s"unexpected typed failure: ${other.message}")
      case Right(_) => fail("unsupported midpoint square root was silently invented")
  }

  private final case class Fixture(
      fixed: RegistrationImage[Fixed],
      moving: RegistrationImage[Moving],
      work: Frame[Work]
  )

  private def images(
      fixedGrid: GridSpec,
      movingGrid: GridSpec,
      workGrid: GridSpec
  ): Fixture =
    val fixedFrame = Frame[Fixed](SpatialDomainId("adapter-fixed"), fixedGrid)
    val movingFrame = Frame[Moving](SpatialDomainId("adapter-moving"), movingGrid)
    val work = Frame[Work](SpatialDomainId("adapter-work"), workGrid)
    val fixedVolume = NeuroVol.fromLinear[Double](
      PrimitiveBuffers.fillConst[Double](fixedGrid.nVoxels, 0.0),
      fixedGrid.toNeuroSpace,
      "adapter-fixed"
    )
    val movingVolume = NeuroVol.fromLinear[Double](
      PrimitiveBuffers.fillConst[Double](movingGrid.nVoxels, 0.0),
      movingGrid.toNeuroSpace,
      "adapter-moving"
    )
    Fixture(
      RegistrationImage.make(fixedFrame, fixedVolume).fold(error => fail(error.message), identity),
      RegistrationImage.make(movingFrame, movingVolume).fold(error => fail(error.message), identity),
      work
    )

  private def canonicalMap(
      matrix: DMat
  ): AffineMap[CanonicalFrame[D3], CanonicalFrame[D3], D3] =
    val source = CanonicalFrame.named[D3]("canonical-source").fold(error => fail(error.toString), identity)
    val target = CanonicalFrame.named[D3]("canonical-target").fold(error => fail(error.toString), identity)
    val operator = CanonicalAffine.fromRowMajor[D3](matrix.data).fold(error => fail(error.toString), identity)
    new TestAffineMap(source, target, operator)

  private final class TestAffineMap[
      From <: CanonicalFrame[D3],
      To <: CanonicalFrame[D3]
  ](
      val source: From,
      val target: To,
      val operator: CanonicalAffine[D3]
  ) extends AffineMap[From, To, D3]:
    def apply(point: Point[From, D3]): Either[MapError, Point[To, D3]] =
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        coordinates <- operator(point.coordinates).left.map(MapError.Geometry.apply)
        mapped <- Point.fromVector(target, coordinates).left.map(MapError.Geometry.apply)
        alignment <- CanonicalFrame
          .alignOwners[D3, target.type, To](target, target)
          .left
          .map(MapError.Geometry.apply)
        result <- alignment.pointToRight(mapped).left.map(MapError.Geometry.apply)
      yield result

    def jet1At(point: Point[From, D3]): Either[MapError, Jet1[From, To, D3]] =
      apply(point).flatMap(value =>
        Jet1.create[From, To, D3](
          value,
          gale.linalg.DMat.dense(
            3,
            3,
            Vector.tabulate(9)(index => operator.matrix(index / 3, index % 3))
          )
        )
      )

    def inverse: AffineMap[To, From, D3] =
      new TestAffineMap(target, source, operator.inverse)

  private def assertDenseLandmark[A, B](
      pull: DensePull[A, B],
      x: Int,
      y: Int,
      z: Int,
      expected: DMat,
      tolerance: Double
  ): Unit =
    val actual = densePoint(pull, x, y, z)
    val input = apply(pull.from.grid.affine, WorldPoint(x.toDouble, y.toDouble, z.toDouble))
    assertWorldClose(actual, apply(expected, input), tolerance)

  private def densePoint[A, B](pull: DensePull[A, B], x: Int, y: Int, z: Int): WorldPoint =
    val index = x + pull.from.grid.shape.x * (y + pull.from.grid.shape.y * z)
    WorldPoint(
      pull.sourceCoordinates.linearComponent(index, 0),
      pull.sourceCoordinates.linearComponent(index, 1),
      pull.sourceCoordinates.linearComponent(index, 2)
    )

  private def assertFieldsClose[A, B, C, D](
      left: DensePull[A, B],
      right: DensePull[C, D],
      tolerance: Double
  ): Unit =
    assertEquals(left.from.grid, right.from.grid)
    var index = 0
    while index < left.from.grid.nVoxels do
      var component = 0
      while component < 3 do
        assertEqualsDouble(
          left.sourceCoordinates.linearComponent(index, component),
          right.sourceCoordinates.linearComponent(index, component),
          tolerance
        )
        component += 1
      index += 1

  private def apply(matrix: DMat, point: WorldPoint): WorldPoint =
    WorldPoint(
      matrix(0, 0) * point.x + matrix(0, 1) * point.y + matrix(0, 2) * point.z + matrix(0, 3),
      matrix(1, 0) * point.x + matrix(1, 1) * point.y + matrix(1, 2) * point.z + matrix(1, 3),
      matrix(2, 0) * point.x + matrix(2, 1) * point.y + matrix(2, 2) * point.z + matrix(2, 3)
    )

  private def assertWorldClose(actual: WorldPoint, expected: WorldPoint, tolerance: Double): Unit =
    assertEqualsDouble(actual.x, expected.x, tolerance)
    assertEqualsDouble(actual.y, expected.y, tolerance)
    assertEqualsDouble(actual.z, expected.z, tolerance)
