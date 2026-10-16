package reframe4s.halfflow

import image4s.geometry.{Affine, D3}
import reframe4s.halfflow.internal.*

class ForwardCellAdmissionSuite extends munit.FunSuite:
  test("shared incremental and accumulated gates reject a hidden fold in either arm"):
    val frame = fixture()
    val identity = DensePull.identity(frame)
    val folded = boundary(frame, 5.5)
    val affine = right(AffineIso.make(frame, frame, Affine.identity[D3]))
    for fixedIsFolded <- Vector(true, false) do
      val fixed = if fixedIsFolded then folded else identity
      val moving = if fixedIsFolded then identity else folded
      val incremental = ForwardGeometry.incremental(right(HalfStep.make(fixed, moving)))
      assertEquals(incremental._1, GeometryVerdict.IncrementJacobianTooSmall(-0.5))
      val reports = incremental._2
      assert(reports.forall(_.minimum > 0.0))
      assertEquals(reports.count(_.cells.exists(_.nonPositive == 36)), 1)
      val state = right(ForwardMidpoint.make(right(ForwardMidpointArm.make(fixed, affine)),
        right(ForwardMidpointArm.make(moving, affine))))
      assertEquals(ForwardGeometry.accumulated(state)._1,
        GeometryVerdict.AccumulatedJacobianTooSmall(
          if fixedIsFolded then MidpointArmName.Fixed else MidpointArmName.Moving, -0.5))

  test("the existing Jacobian floor also applies to positive outer cells"):
    val frame = fixture()
    val thin = boundary(frame, 6.02)
    val verdict = ForwardGeometry.incremental(right(HalfStep.make(thin, DensePull.identity(frame))))
    assert(verdict._1.isInstanceOf[GeometryVerdict.IncrementJacobianTooSmall])
    assertEquals(verdict._2.head.cells.get.nonPositive, 0)
    assertEqualsDouble(verdict._2.head.cells.get.minimum, 0.02, 1e-12)

  test("export construction rejects manually supplied hidden-fold forward states"):
    val frame = fixture()
    val affine = right(AffineIso.make(frame, frame, Affine.identity[D3]))
    val bad = right(ForwardMidpointArm.make(boundary(frame, 5.5), affine))
    val good = ForwardMidpointArm.identity(affine)
    for fixedIsFolded <- Vector(true, false) do
      val state = right(ForwardMidpoint.make(if fixedIsFolded then bad else good, if fixedIsFolded then good else bad))
      ForwardMidpointExporter.inspect(state) match
        case Left(ForwardExportError.ForwardResidualTopologyInvalid(arm, report)) =>
          assertEquals(arm, if fixedIsFolded then MidpointArmName.Fixed else MidpointArmName.Moving)
          assertEquals(report.nonPositive, 36)
        case other => fail(s"hidden forward fold was not rejected: $other")

  test("endpoint admission recomputes cell geometry on copied candidate maps"):
    val frame = fixture()
    val candidate = right(ForwardMidpointExporter.inspect(right(ForwardMidpoint.identity(frame, frame, frame))))
    val bad = boundary(frame, 5.5)
    val good = DensePull.identity(frame)
    for forwardIsFolded <- Vector(true, false) do
      val maps = right(InversePair.make(if forwardIsFolded then bad else good, if forwardIsFolded then good else bad))
      ForwardMidpointExporter.admit(candidate.copy(transform = maps)) match
        case Left(ForwardExportError.ExportedCellTopologyInvalid(direction, report)) =>
          assertEquals(direction, if forwardIsFolded then ExportDirection.FixedToMoving else ExportDirection.MovingToFixed)
          assertEquals(report.nonPositive, 36)
        case other => fail(s"copied endpoint fold was not rejected: $other")

  test("legacy pair guard cannot admit a hidden fold when voxel and inverse checks pass"):
    val frame = fixture()
    val pair = right(InversePair.make(boundary(frame, 5.5), DensePull.identity(frame)))
    val report = TopologyGuard.evaluate(pair, GuardConfig(maximumInverseErrorMm = 100.0,
      maximumInverseErrorVox = 100.0))
    assertEquals(report.forward.nonPositive, 0)
    assertEquals(report.forward.belowFloor, 0)
    assertEquals(report.forward.cells.get.nonPositive, 36)
    assert(!report.safe)
    assert(report.reasons.exists(_.contains("complete-cell")))

  private def fixture(): RegistrationFrame[Unit] =
    RegistrationFrame[Unit](SpatialDomainId("cell-admission"), GridSpec.identity(Vector(7, 7, 7)))

  private def boundary(frame: RegistrationFrame[Unit], last: Double): DensePull[Unit, Unit] =
    val coordinates = HalfFlowKernels.identity(frame.grid).field.copyLegacyPlanar
    val xs = Vector(0.0, 1.0, 2.0, 3.0, 4.0, 6.0, last)
    for i <- 0 until frame.grid.nVoxels do coordinates(i) = xs(i % 7)
    right(DensePull.make(frame, frame,
      DenseVectorField.fromLegacyPlanar(frame.grid, coordinates, DenseVectorFieldKind.SourceCoordinates)))

  private def right[E, A](value: Either[E, A]): A = value.fold(error => fail(error.toString), identity)
