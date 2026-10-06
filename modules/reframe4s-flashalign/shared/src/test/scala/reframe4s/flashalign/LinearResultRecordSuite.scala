package reframe4s.flashalign

import image4s.BoundaryPolicy
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.Validity
import image4s.geometry.Affine
import image4s.geometry.CoordinateConvention
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.FrameId
import image4s.geometry.FrameRegistry
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.GridId
import image4s.geometry.GridRegistry
import image4s.geometry.LengthUnit
import image4s.geometry.Point
import ravel.DType.given
import ravel.NDArray
import reframe4s.core.MapError
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.register.OptimizationReport
import reframe4s.register.Termination
import reframe4s.resample.Interpolation

final class LinearResultRecordSuite extends munit.FunSuite:
  test("rigid records restore frames grids directions and landmark mapping through registries"):
    val fixture = persistentFixture("rigid-record")
    val transform = rigid(
      Rigid3.translationBetween(fixture.movingFrame, fixture.fixedFrame)(
        2.0,
        -1.0,
        0.5
      )
    )
    val result = rigidResult(transform)
    val record = result
      .record(fixture.movingGrid, fixture.fixedGrid)
      .fold(error => fail(error.message), identity)
    assertEquals(record.version, FlashalignLinearResultRecord.CurrentVersion)
    assertEquals(record.direction, FlashalignTransformDirection.MovingWorldToFixedWorld)
    assertEquals(
      record.convention,
      FlashalignTransformConvention.RowMajorYEqualsTransformXMillimetresV1
    )
    assertEquals(record.coordinateUnit, LengthUnit.Millimeter)
    assertEquals(record.movingGrid.key.shape, fixture.movingGrid.shape)
    assertEquals(record.fixedGrid.key.shape, fixture.fixedGrid.shape)

    val restored = FlashalignLinearResultRecord
      .restoreRigid(record, FrameRegistry.empty, GridRegistry.empty)
      .fold(error => fail(error.message), identity)
    assertEquals(restored.frameRegistry.size, 2)
    assertEquals(restored.gridRegistry.size, 2)
    assert(restored.movingGrid.frame.sameRuntimeOwnerAs(restored.result.movingToFixed.source))
    assert(restored.fixedGrid.frame.sameRuntimeOwnerAs(restored.result.movingToFixed.target))
    val point = pointFor(
      restored.result.movingToFixed.source,
      Vector(1.0, 2.0, 3.0)
    )
    val mapped = mappedPoint(restored.result.movingToFixed(point))
    assertVectorClose(mapped.coordinates, Vector(3.0, 1.0, 3.5), 1e-12)
    val roundTrip = mappedPoint(restored.result.fixedToMoving(mapped))
    assertVectorClose(roundTrip.coordinates, point.coordinates, 1e-12)

    val independent = FlashalignLinearResultRecord
      .restoreRigid(record, FrameRegistry.empty, GridRegistry.empty)
      .fold(error => fail(error.message), identity)
    assert(restored.result.movingToFixed.source.samePersistentKeyAs(independent.result.movingToFixed.source))
    assert(!restored.result.movingToFixed.source.sameRuntimeOwnerAs(independent.result.movingToFixed.source))

  test("canonical output pulls the original image once on an asymmetric fixed grid"):
    val fixture = persistentFixture("output-direction")
    val transform = rigid(
      Rigid3.translationBetween(fixture.movingFrame, fixture.fixedFrame)(
        2.0,
        -1.0,
        0.5
      )
    )
    val result = rigidResult(transform)
    val movingAffine = fixture.movingGrid.indexToFrame.rowMajor
    val data = NDArray.tabulate[Double](
      fixture.movingGrid.shape(0),
      fixture.movingGrid.shape(1),
      fixture.movingGrid.shape(2)
    ) { (i, j, k) =>
      val world = applyAffine(movingAffine, i.toDouble, j.toDouble, k.toDouble)
      analytic(world)
    }
    val original = Sampled
      .continuous(fixture.movingGrid, NonSpatialAxes.empty, data)
      .fold(error => fail(error.message), identity)
    val plan = FlashalignOutput
      .rigidPlan(
        original,
        fixture.fixedGrid,
        result,
        Interpolation.Linear,
        BoundaryPolicy.Constant(-1000.0)
      )
      .fold(error => fail(error.message), identity)
    assertEquals(plan.structure.materializedCoordinateCount, 0)
    val output = plan.run(plan.newWorkspace()).fold(error => fail(error.message), identity)
    val fixedAffine = fixture.fixedGrid.indexToFrame.rowMajor
    var i = 0
    while i < fixture.fixedGrid.shape(0) do
      var j = 0
      while j < fixture.fixedGrid.shape(1) do
        var k = 0
        while k < fixture.fixedGrid.shape(2) do
          val fixedWorld = applyAffine(fixedAffine, i.toDouble, j.toDouble, k.toDouble)
          val movingWorld = Vector(
            fixedWorld(0) - 2.0,
            fixedWorld(1) + 1.0,
            fixedWorld(2) - 0.5
          )
          val actual = output.image
            .valueAt(Vector(i, j, k))
            .fold(error => fail(error.message), identity)
          assertEqualsDouble(actual, analytic(movingWorld), 2e-12)
          assertEquals(
            output.validity
              .at(Vector(i, j, k))
              .fold(error => fail(error.message), identity),
            Validity.Full
          )
          k += 1
        j += 1
      i += 1

  test("affine records retain affine type and inverse without pretending rigidity"):
    val fixture = persistentFixture("affine-record")
    val operator = geometry(
      Affine.fromRowMajor[D3](
        Vector(
          1.02, 0.03, 0.0, 1.0,
          -0.01, 0.98, 0.02, -2.0,
          0.0, 0.01, 1.01, 0.5,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    val transform = FramedAffine.betweenFrames(fixture.movingFrame, fixture.fixedFrame)(operator)
    val result = affineResult(transform)
    val record = result
      .record(fixture.movingGrid, fixture.fixedGrid)
      .fold(error => fail(error.message), identity)
    assertEquals(record.model, FlashalignModel.Affine)
    assertEquals(record.rigidValidationTolerance, None)
    val restored = FlashalignLinearResultRecord
      .restoreAffine(record, FrameRegistry.empty, GridRegistry.empty)
      .fold(error => fail(error.message), identity)
    assertEquals(restored.result.movingToFixed.operator.rowMajor, operator.rowMajor)
    val original = Sampled
      .continuous(
        fixture.movingGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](
          fixture.movingGrid.shape(0),
          fixture.movingGrid.shape(1),
          fixture.movingGrid.shape(2)
        )((_, _, _) => 1.0)
      )
      .fold(error => fail(error.message), identity)
    val outputPlan = FlashalignOutput
      .affinePlan(
        original,
        fixture.fixedGrid,
        result,
        Interpolation.Cubic,
        BoundaryPolicy.Constant(0.0)
      )
      .fold(error => fail(error.message), identity)
    assertEquals(outputPlan.structure.materializedCoordinateCount, 0)
    val point = pointFor(
      restored.result.movingToFixed.source,
      Vector(0.5, -1.0, 2.0)
    )
    val roundTrip = mappedPoint(
      restored.result.fixedToMoving(
        mappedPoint(restored.result.movingToFixed(point))
      )
    )
    assertVectorClose(roundTrip.coordinates, point.coordinates, 2e-12)

  test("ephemeral identity units versions and model mismatches fail explicitly"):
    val moving = geometry(Frame.named[D3]("ephemeral-moving"))
    val fixed = geometry(Frame.named[D3]("ephemeral-fixed"))
    val movingGrid = geometry(Grid.forFrame(moving)(Vector(3, 3, 3), Affine.identity[D3]))
    val fixedGrid = geometry(Grid.forFrame(fixed)(Vector(3, 3, 3), Affine.identity[D3]))
    val ephemeral = rigidResult(
      rigid(Rigid3.translationBetween(moving, fixed)(0.0, 0.0, 0.0))
    )
    ephemeral.record(movingGrid, fixedGrid) match
      case Left(
            FlashalignRecordError.PersistentFrameRequired(
              FlashalignEndpoint.Moving,
              _
            )
          ) => ()
      case other => fail(s"expected persistent-frame failure, got $other")

    val fixture = persistentFixture("invalid-record")
    val record = rigidResult(
      rigid(
        Rigid3.translationBetween(fixture.movingFrame, fixture.fixedFrame)(
          0.0,
          0.0,
          0.0
        )
      )
    ).record(fixture.movingGrid, fixture.fixedGrid).fold(error => fail(error.message), identity)

    restoreRigid(record.copy(coordinateUnit = LengthUnit.Meter)) match
      case Left(FlashalignRecordError.UnitMismatch(FlashalignEndpoint.Moving, LengthUnit.Meter)) => ()
      case other => fail(s"expected unit failure, got $other")
    restoreRigid(record.copy(version = 99)) match
      case Left(FlashalignRecordError.UnsupportedVersion(99, 1)) => ()
      case other => fail(s"expected version failure, got $other")
    restoreRigid(record.copy(model = FlashalignModel.Affine)) match
      case Left(FlashalignRecordError.ModelMismatch(FlashalignModel.Rigid, FlashalignModel.Affine)) => ()
      case other => fail(s"expected model failure, got $other")

  test("registry conflicts reject matching labels or IDs with different structure"):
    val fixture = persistentFixture("registry-conflict")
    val record = rigidResult(
      rigid(
        Rigid3.translationBetween(fixture.movingFrame, fixture.fixedFrame)(
          0.0,
          0.0,
          0.0
        )
      )
    ).record(fixture.movingGrid, fixture.fixedGrid).fold(error => fail(error.message), identity)
    val conflictingMoving = persistentFrame(
      "registry-conflict-moving",
      "same presentation label",
      LengthUnit.Meter
    )
    val registry = FrameRegistry.empty
      .register(conflictingMoving)
      .fold(error => fail(error.message), identity)
    FlashalignLinearResultRecord.restoreRigid(record, registry, GridRegistry.empty) match
      case Left(FlashalignRecordError.Geometry(_: GeometryError.FrameKeyConflict)) => ()
      case other => fail(s"expected registry identity conflict, got $other")

  private final case class PersistentFixture(
      movingFrame: Frame[D3],
      fixedFrame: Frame[D3],
      movingGrid: Grid[Frame[D3], D3],
      fixedGrid: Grid[Frame[D3], D3]
  )

  private def persistentFixture(prefix: String): PersistentFixture =
    val moving = persistentFrame(s"$prefix-moving", "moving")
    val fixed = persistentFrame(s"$prefix-fixed", "fixed")
    val movingAffine = geometry(
      Affine.fromRowMajor[D3](
        Vector(
          1.2, 0.1, 0.0, -10.0,
          0.0, 1.1, 0.2, -10.0,
          0.0, 0.0, 1.3, -10.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    val fixedAffine = geometry(
      Affine.fromRowMajor[D3](
        Vector(
          0.8, 0.05, 0.0, -4.0,
          0.0, 0.9, 0.1, -4.0,
          0.0, 0.0, 1.0, -4.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    val movingGrid = geometry(
      Grid.createPersistent[D3, Frame[D3]](
        geometry(GridId.parse(s"$prefix-moving-grid")),
        moving
      )(Vector(12, 11, 10), movingAffine)
    )
    val fixedGrid = geometry(
      Grid.createPersistent[D3, Frame[D3]](
        geometry(GridId.parse(s"$prefix-fixed-grid")),
        fixed
      )(Vector(4, 5, 3), fixedAffine)
    )
    PersistentFixture(moving, fixed, movingGrid, fixedGrid)

  private def persistentFrame(
      id: String,
      label: String,
      unit: LengthUnit = LengthUnit.Millimeter
  ): Frame[D3] =
    geometry(
      Frame.persistentNamed[D3](
        geometry(FrameId.parse(id)),
        label,
        unit,
        CoordinateConvention.RAS
      )
    )

  private def rigidResult(
      transform: Rigid3[Frame[D3], Frame[D3]]
  ): RigidFlashalignResult[Frame[D3], Frame[D3]] =
    flashalign(
      RigidFlashalignResult.create(
        transform,
        report,
        FlashalignDiagnostics(
          FlashalignModel.Rigid,
          FlashalignPreset.EpiToT1,
          100,
          50,
          0.9
        )
      )
    )

  private def affineResult(
      transform: FramedAffine[Frame[D3], Frame[D3], D3]
  ): AffineFlashalignResult[Frame[D3], Frame[D3]] =
    flashalign(
      AffineFlashalignResult.create(
        transform,
        report,
        FlashalignDiagnostics(
          FlashalignModel.Affine,
          FlashalignPreset.WithinModality,
          100,
          50,
          0.95
        )
      )
    )

  private def report: OptimizationReport =
    OptimizationReport
      .create(2.0, 1.0, 4, 6, 3, Termination.ObjectiveConverged)
      .fold(error => fail(error.message), identity)

  private def restoreRigid(
      record: FlashalignLinearResultRecord
  ): Either[FlashalignRecordError, RestoredRigidFlashalignResult] =
    FlashalignLinearResultRecord.restoreRigid(
      record,
      FrameRegistry.empty,
      GridRegistry.empty
    )

  private def analytic(world: Vector[Double]): Double =
    5.0 + 0.7 * world(0) - 1.2 * world(1) + 0.4 * world(2)

  private def applyAffine(
      values: Vector[Double],
      x: Double,
      y: Double,
      z: Double
  ): Vector[Double] =
    Vector(
      values(0) * x + values(1) * y + values(2) * z + values(3),
      values(4) * x + values(5) * y + values(6) * z + values(7),
      values(8) * x + values(9) * y + values(10) * z + values(11)
    )

  private def assertVectorClose(
      actual: Vector[Double],
      expected: Vector[Double],
      tolerance: Double
  ): Unit =
    actual.zip(expected).foreach { case (left, right) =>
      assertEqualsDouble(left, right, tolerance)
    }

  private def mappedPoint[F <: Frame[D3]](
      value: Either[MapError, Point[F, D3]]
  ): Point[F, D3] =
    value.fold(error => fail(error.message), identity)

  private def pointFor(
      frame: Frame[D3],
      coordinates: Vector[Double]
  ): Point[Frame[D3], D3] =
    val exact = geometry(Point.fromVector(frame, coordinates))
    val alignment = geometry(
      Frame.alignOwners[D3, frame.type, Frame[D3]](frame, frame)
    )
    geometry(alignment.pointToRight(exact).left.map(GeometryError.fromSpatial))

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def flashalign[A](value: Either[FlashalignError, A]): A =
    value.fold(error => fail(error.message), identity)
