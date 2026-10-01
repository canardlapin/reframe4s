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
import reframe4s.lie.Rigid3
import reframe4s.resample.Interpolation

final class NonlinearResultSuite extends munit.FunSuite:
  test("PE result maps and persistent metadata restore across asymmetric grids"):
    val fixture = makeFixture("restore", PeInverseConfig.create(1e-10, 64))
    val result = nonlinearResult(fixture)
    val source = point(fixture.moving, Vector(-1.7, 0.8, -0.4))
    val mapped = mappedPoint(result.movingToFixed(source))
    val restoredPoint = mappedPoint(result.fixedToMoving(mapped))
    assertVectorClose(restoredPoint.coordinates, source.coordinates, 2e-9)

    val record = nonlinearRecord(
      result.record(fixture.movingGrid, fixture.fixedGrid)
    )
    assertEquals(record.model, FlashalignNonlinearModel.PhaseEncodingField)
    assertEquals(
      record.field.kind,
      FlashalignNonlinearFieldKind.ScalarPhaseEncodingDisplacement
    )
    assertEquals(record.field.phaseEncoding.map(_.voxelAxis), Some(0))
    assertEquals(record.movingGrid.key.shape, Vector(15, 14, 13))
    assertEquals(record.fixedGrid.key.shape, Vector(4, 5, 3))

    val restored = nonlinearRecord(
      FlashalignNonlinearResultRecord.restore(
        record,
        FrameRegistry.empty,
        GridRegistry.empty
      )
    )
    assertEquals(restored.frameRegistry.size, 2)
    assertEquals(restored.gridRegistry.size, 2)
    assertEquals(restored.movingGrid.shape, fixture.movingGrid.shape)
    assertEquals(restored.fixedGrid.shape, fixture.fixedGrid.shape)
    assertEquals(
      restored.affineComponent.operator.rowMajor,
      result.namedAffineComponentExport.rowMajor
    )
    assert(restored.record.geometryCertificate.evidenceKind.contains("not SmoothIso"))

  test("nonlinear complete-matrix export is rejected and pose export is named"):
    val fixture = makeFixture("export", PeInverseConfig.create(1e-10, 64))
    val result = nonlinearResult(fixture)
    result.completeMatrixExport match
      case Left(
            FlashalignNonlinearExportError.UnsupportedCompleteMatrix(
              FlashalignNonlinearModel.PhaseEncodingField
            )
          ) => ()
      case other => fail(s"expected nonlinear matrix rejection, got $other")
    val component = result.namedAffineComponentExport
    assertEquals(
      component.role,
      FlashalignAffineComponentRole.GlobalPoseComponentOnly
    )
    assertEquals(component.rowMajor, fixture.state.pose.operator.rowMajor)

    val record = nonlinearRecord(
      result.record(fixture.movingGrid, fixture.fixedGrid)
    )
    assertEquals(record.namedAffineComponentExport, component)
    assert(record.completeMatrixExport.isLeft)

  test("mapped output preflights memory and interpolates the original once with validity"):
    val fixture = makeFixture("output", PeInverseConfig.create(1e-10, 64))
    val result = nonlinearResult(fixture)
    val movingAffine = fixture.movingGrid.indexToFrame.rowMajor
    val original = Sampled
      .continuous(
        fixture.movingGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](15, 14, 13) { (i, j, k) =>
          analytic(applyAffine(movingAffine, i.toDouble, j.toDouble, k.toDouble))
        }
      )
      .fold(error => fail(error.message), identity)
    val budget = output(NonlinearOutputBudget.create(1000000L))
    val plan = output(
      NonlinearOutputPlan3.compile(
        original,
        fixture.fixedGrid,
        result,
        Interpolation.Linear,
        BoundaryPolicy.Constant(-1000.0),
        budget
      )
    )
    val voxels = fixture.fixedGrid.shape.map(_.toLong).product
    assertEquals(plan.cost.targetVoxelCount, voxels)
    assertEquals(plan.cost.materializedCoordinateCount, voxels)
    assertEquals(plan.cost.coordinateArrayBytes, 24L * voxels)
    assertEquals(plan.cost.outputBytes, 8L * voxels)
    assertEquals(plan.cost.validityBytes, 8L * voxels)
    assertEquals(plan.cost.estimatedIncrementalPeakBytes, 40L * voxels)
    assertEquals(plan.cost.finalInterpolationPasses, 1)
    assertEquals(plan.cost.sourceInterpolationEvaluations, voxels)

    val produced = output(plan.run())
    assert(produced.validityPreserved)
    val pull = result.fixedToMoving
    var i = 0
    while i < fixture.fixedGrid.shape(0) do
      var j = 0
      while j < fixture.fixedGrid.shape(1) do
        var k = 0
        while k < fixture.fixedGrid.shape(2) do
          val fixedPoint = geometry(
            fixture.fixedGrid.pointAt(
              geometry(image4s.geometry.LatticeIndex.fromVector[D3](Vector(i, j, k)))
            )
          )
          val movingPoint = mappedPoint(pull(fixedPoint))
          val expected = analytic(movingPoint.coordinates)
          val actual = produced.output.image
            .valueAt(Vector(i, j, k))
            .fold(error => fail(error.message), identity)
          assertEqualsDouble(actual, expected, 3e-10)
          assertEquals(
            produced.output.validity
              .at(Vector(i, j, k))
              .fold(error => fail(error.message), identity),
            Validity.Full
          )
          k += 1
        j += 1
      i += 1

    NonlinearOutputPlan3.compile(
      original,
      fixture.fixedGrid,
      result,
      Interpolation.Linear,
      BoundaryPolicy.Constant(-1000.0),
      output(NonlinearOutputBudget.create(plan.cost.estimatedIncrementalPeakBytes - 1L))
    ) match
      case Left(NonlinearOutputError.BudgetExceeded(required, available)) =>
        assertEquals(required, plan.cost.estimatedIncrementalPeakBytes)
        assertEquals(available, required - 1L)
      case other => fail(s"expected preflight budget failure, got $other")

  test("inverse failure aborts mapped output before boundary padding"):
    val fixture = makeFixture("inverse-failure", PeInverseConfig.create(1e-16, 1))
    val result = nonlinearResult(fixture, evidence(maximumIterations = 1))
    val original = Sampled
      .continuous(
        fixture.movingGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](15, 14, 13)((_, _, _) => 1.0)
      )
      .fold(error => fail(error.message), identity)
    NonlinearOutputPlan3.compile(
      original,
      fixture.fixedGrid,
      result,
      Interpolation.Linear,
      BoundaryPolicy.Constant(-999.0),
      output(NonlinearOutputBudget.create(1000000L))
    ) match
      case Left(NonlinearOutputError.InverseMapping(error)) =>
        assert(error.message.contains("inverse exhausted"), error.message)
      case other => fail(s"expected typed inverse failure before padding, got $other")

  test("restore rejects inconsistent model and acquisition metadata"):
    val fixture = makeFixture("corrupt-record", PeInverseConfig.create(1e-10, 64))
    val record = nonlinearRecord(
      nonlinearResult(fixture).record(fixture.movingGrid, fixture.fixedGrid)
    )
    val wrongKind = record.copy(
      field = record.field.copy(
        kind = FlashalignNonlinearFieldKind.VectorSmallStrainDisplacement
      )
    )
    FlashalignNonlinearResultRecord.restore(
      wrongKind,
      FrameRegistry.empty,
      GridRegistry.empty
    ) match
      case Left(_: FlashalignNonlinearRecordError.InvalidMetadata) => ()
      case other => fail(s"expected field/model mismatch, got $other")

    val nonUnitPe = record.copy(
      field = record.field.copy(
        phaseEncoding = record.field.phaseEncoding.map(
          _.copy(unitMovingWorld = Vector(2.0, 0.0, 0.0))
        )
      )
    )
    FlashalignNonlinearResultRecord.restore(
      nonUnitPe,
      FrameRegistry.empty,
      GridRegistry.empty
    ) match
      case Left(_: FlashalignNonlinearRecordError.InvalidMetadata) => ()
      case other => fail(s"expected non-unit PE metadata failure, got $other")

  private final case class Fixture(
      moving: Frame[D3],
      fixed: Frame[D3],
      movingGrid: Grid[Frame[D3], D3],
      fixedGrid: Grid[Frame[D3], D3],
      model: PeFieldModel3[Frame[D3], Frame[D3]],
      state: PeFieldState3[Frame[D3], Frame[D3]],
      config: PeGeometryConfig
  )

  private def makeFixture(
      label: String,
      inverseConfig: Either[PeGeometryError, PeInverseConfig]
  ): Fixture =
    val moving = persistentFrame(s"nonlinear-$label-moving")
    val fixed = persistentFrame(s"nonlinear-$label-fixed")
    val movingGrid = geometry(
      Grid.createPersistent[D3, Frame[D3]](
        geometry(GridId.parse(s"nonlinear-$label-moving-grid")),
        moving
      )(
        Vector(15, 14, 13),
        geometry(
          Affine.fromRowMajor[D3](Vector(
            1.2, 0.05, 0.0, -8.0,
            0.0, 1.1, 0.04, -7.0,
            0.0, 0.0, 1.25, -6.0,
            0.0, 0.0, 0.0, 1.0
          ))
        )
      )
    )
    val fixedGrid = geometry(
      Grid.createPersistent[D3, Frame[D3]](
        geometry(GridId.parse(s"nonlinear-$label-fixed-grid")),
        fixed
      )(
        Vector(4, 5, 3),
        geometry(
          Affine.fromRowMajor[D3](Vector(
            0.8, 0.03, 0.0, -2.0,
            0.0, 0.9, 0.02, -2.0,
            0.0, 0.0, 1.0, -1.0,
            0.0, 0.0, 0.0, 1.0
          ))
        )
      )
    )
    val domain = PhysicalSpectralDomain3.create(
      Vector(-10.0, -10.0, -10.0),
      Vector(
        Vector(1.0, 0.0, 0.0),
        Vector(0.0, 1.0, 0.0),
        Vector(0.0, 0.0, 1.0)
      ),
      Vector(20.0, 20.0, 20.0),
      Vector(2.0, 2.0, 2.0),
      "periodic-padded-r3-v1"
    ).fold(error => fail(error.message), identity)
    val coordinates = Vector(-7.5, -2.5, 2.5, 7.5)
    val gaugePoints = (for
      x <- coordinates
      y <- coordinates
      z <- coordinates
    yield Vector(x, y, z)).toVector
    val gauge = GeometryGaugeMeasure3
      .create(
        s"nonlinear-$label-gauge-v1",
        gaugePoints,
        Vector.fill(gaugePoints.length)(1.0)
      )
      .fold(error => fail(error.message), identity)
    val basis = PhysicalSpectralBasis3
      .fromWavevectors(domain, Vector(IntegerWave3(1, 0, 0)), gauge)
      .fold(error => fail(error.message), identity)
    val rigidModel = RigidModel3
      .atFixedWorld[Frame[D3], Frame[D3]](moving, fixed)(0.0, 0.0, 0.0)
      .fold(error => fail(error.message), identity)
    val model = PeFieldModel3
      .compile(
        rigidModel,
        basis,
        PhaseEncodingDirection3(0, 1, Vector(1.0, 0.0, 0.0)),
        SpectralPriorWeights(0.5, 0.8, 0.05),
        PeFieldModelConfig.create(2, 8, 2).fold(error => fail(error.message), identity)
      )
      .fold(error => fail(error.message), identity)
    val pose = Rigid3
      .translationBetween(moving, fixed)(0.15, -0.1, 0.05)
      .fold(error => fail(error.message), identity)
    val state = PeFieldState3(
      pose,
      basis
        .coefficientState(Vector(0.15, -0.05))
        .fold(error => fail(error.message), identity)
    )
    val sourceDomain = PeWorldDomain3
      .fromSpectralDomain(s"nonlinear-$label-source", domain, includePadding = false)
      .fold(error => fail(error.message), identity)
    val fixedDomain = PeWorldDomain3
      .create(
        s"nonlinear-$label-fixed",
        Vector(0.0, 0.0, 0.0),
        Vector(
          Vector(1.0, 0.0, 0.0),
          Vector(0.0, 1.0, 0.0),
          Vector(0.0, 0.0, 1.0)
        ),
        Vector(-9.0, -9.0, -9.0),
        Vector(9.0, 9.0, 9.0)
      )
      .fold(error => fail(error.message), identity)
    val config = PeGeometryConfig(
      PeCertificateConfig
        .create(0.2, 5.0, 1e-12, 1e-12)
        .fold(error => fail(error.message), identity),
      inverseConfig.fold(error => fail(error.message), identity),
      sourceDomain,
      fixedDomain
    )
    Fixture(moving, fixed, movingGrid, fixedGrid, model, state, config)

  private def nonlinearResult(
      fixture: Fixture,
      inverse: FlashalignInverseEvidenceRecord = evidence()
  ): NonlinearFlashalignResult3[
    PeFieldState3[Frame[D3], Frame[D3]],
    Frame[D3],
    Frame[D3]
  ] =
    NonlinearFlashalignResult3
      .peField(fixture.model, fixture.state, fixture.config, inverse)
      .fold(error => fail(error.message), identity)

  private def evidence(
      maximumIterations: Int = 64
  ): FlashalignInverseEvidenceRecord =
    FlashalignInverseEvidenceRecord(
      "pe-bracketed-inverse-v1",
      converged = true,
      maximumResidualMm = 1e-10,
      meanResidualMm = 2e-11,
      sampleCount = 60L,
      coveredFraction = 1.0,
      maximumIterations,
      maximumResidualCriterionMm = 1e-6,
      minimumCoveredFraction = 1.0,
      "held-grid roundtrip residual; numerical inverse and not SmoothIso"
    )

  private def persistentFrame(id: String): Frame[D3] =
    geometry(
      Frame.persistentNamed[D3](
        geometry(FrameId.parse(id)),
        id,
        LengthUnit.Millimeter,
        CoordinateConvention.RAS
      )
    )

  private def point(
      frame: Frame[D3],
      coordinates: Vector[Double]
  ): Point[Frame[D3], D3] =
    val exact = geometry(Point.fromVector(frame, coordinates))
    val alignment = geometry(Frame.alignOwners[D3, frame.type, Frame[D3]](frame, frame))
    geometry(alignment.pointToRight(exact))

  private def analytic(world: Vector[Double]): Double =
    4.0 + 0.3 * world(0) - 0.2 * world(1) + 0.1 * world(2)

  private def applyAffine(
      matrix: Vector[Double],
      x: Double,
      y: Double,
      z: Double
  ): Vector[Double] =
    Vector(
      matrix(0) * x + matrix(1) * y + matrix(2) * z + matrix(3),
      matrix(4) * x + matrix(5) * y + matrix(6) * z + matrix(7),
      matrix(8) * x + matrix(9) * y + matrix(10) * z + matrix(11)
    )

  private def assertVectorClose(
      actual: Vector[Double],
      expected: Vector[Double],
      tolerance: Double
  ): Unit = actual.zip(expected).foreach { case (left, right) =>
    assertEqualsDouble(left, right, tolerance)
  }

  private def mappedPoint[F <: Frame[D3]](
      value: Either[MapError, Point[F, D3]]
  ): Point[F, D3] = value.fold(error => fail(error.message), identity)

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def nonlinearRecord[A](
      value: Either[FlashalignNonlinearRecordError, A]
  ): A = value.fold(error => fail(error.message), identity)

  private def output[A](value: Either[NonlinearOutputError, A]): A =
    value.fold(error => fail(error.message), identity)
