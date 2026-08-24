package reframe4s.laws

import scala.compiletime.testing.typeCheckErrors

import reframe4s.core.CellSamplingRule
import reframe4s.core.CertifiedBidirectionalPair
import reframe4s.core.AffineMap
import reframe4s.core.EvidenceError
import reframe4s.core.ImplementationRevision
import reframe4s.core.IndexRegion
import reframe4s.core.InverseCriteria
import reframe4s.core.InverseDomain
import reframe4s.core.InverseEstimate
import reframe4s.core.InversePairEvidence
import reframe4s.core.InverseResidual
import reframe4s.core.InversionStatus
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import reframe4s.core.SpatialDifferential
import reframe4s.core.TopologyCertificate
import reframe4s.core.TopologyCriteria
import reframe4s.core.TopologyDiagnostics
import reframe4s.core.TopologyScope
import image4s.geometry.Affine
import image4s.geometry.D2
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.FrameId
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.GridId
import image4s.geometry.Point
import reframe4s.lie.FramedAffine

final class MapLawsSuite extends munit.FunSuite:
  test("authoritative affine identity preserves capability and live owner"):
    val frame = rightGeometry(Frame.named[D2]("affine-identity"))
    val identity =
      AffineMap.identity[D2, frame.type](frame)
    val point =
      rightGeometry(Point.in[D2](frame)(2.5, -3.0))
    val mapped = rightMap(identity(point))
    val jet = rightMap(identity.jet1At(point))

    assert(identity.source eq frame)
    assert(identity.target eq frame)
    assert(identity.inverse eq identity)
    assert(mapped.frame eq frame)
    assertEquals(mapped.coordinates, point.coordinates)
    assertEquals(
      identity.operator.rowMajor,
      Affine.identity[D2].rowMajor
    )
    assertEqualsDouble(jet.differential(0, 0), 1.0, 0.0)
    assertEqualsDouble(jet.differential(0, 1), 0.0, 0.0)
    assertEqualsDouble(jet.differential(1, 0), 0.0, 0.0)
    assertEqualsDouble(jet.differential(1, 1), 1.0, 0.0)

  test("exact affine isomorphisms satisfy both reusable inverse laws"):
    val source = rightGeometry(Frame.named[D2]("source"))
    val target = rightGeometry(Frame.named[D2]("target"))
    val map = rightGeometry(
      FramedAffine.translation(source, target)(3.0, -4.0)
    )
    val sourcePoint = rightGeometry(Point.in[D2](source)(1.5, 2.5))
    val targetPoint = rightMap(map(sourcePoint))

    assert(rightMap(MapLaws.leftInverse(map, sourcePoint, 1e-12)))
    assert(rightMap(MapLaws.rightInverse(map, targetPoint, 1e-12)))
    assert(rightMap(MapLaws.leftIdentity(map, sourcePoint, 1e-12)))
    assert(rightMap(MapLaws.rightIdentity(map, sourcePoint, 1e-12)))

  test("frame-refinement erasure preserves action and rejects a foreign live owner"):
    val source = rightGeometry(Frame.named[D2]("erased-source"))
    val target = rightGeometry(Frame.named[D2]("erased-target"))
    val map = rightGeometry(
      FramedAffine.translation(source, target)(3.0, -4.0)
    )
    val erased = SpatialMap.eraseFrameRefinements(map)
    val erasedSource: Frame[D2] = erased.source
    val raw = rightGeometry(Point.fromVector(erasedSource, Vector(1.5, 2.5)))
    val alignment = rightGeometry(
      Frame.alignOwners[D2, erasedSource.type, Frame[D2]](
        erasedSource,
        erasedSource
      )
    )
    val point = rightGeometry(alignment.pointToRight(raw))
    val result = rightMap(erased(point))

    assert(erased.source eq source)
    assert(erased.target eq target)
    assert(result.frame eq target)
    assertEquals(result.coordinates, Vector(4.5, -1.5))

    val foreign = rightGeometry(Frame.named[D2]("erased-foreign"))
    val foreignFrame: Frame[D2] = foreign
    val foreignRaw = rightGeometry(
      Point.fromVector(foreignFrame, Vector(1.5, 2.5))
    )
    val foreignAlignment = rightGeometry(
      Frame.alignOwners[D2, foreignFrame.type, Frame[D2]](
        foreignFrame,
        foreignFrame
      )
    )
    val foreignPoint = rightGeometry(foreignAlignment.pointToRight(foreignRaw))
    erased(foreignPoint) match
      case Left(_: MapError.SourceFrameMismatch) => ()
      case other => fail(s"expected a typed source-owner failure, got $other")

  test("composition is associative across distinct framed endpoints"):
    val a = rightGeometry(Frame.named[D2]("a"))
    val b = rightGeometry(Frame.named[D2]("b"))
    val c = rightGeometry(Frame.named[D2]("c"))
    val d = rightGeometry(Frame.named[D2]("d"))
    val ab = rightGeometry(FramedAffine.translation(a, b)(1.0, 0.0))
    val bc = rightGeometry(FramedAffine.translation(b, c)(0.0, 2.0))
    val cd = rightGeometry(FramedAffine.translation(c, d)(-3.0, 4.0))
    val point = rightGeometry(Point.in[D2](a)(2.0, 3.0))

    assert(rightMap(MapLaws.associative(ab, bc, cd, point, 1e-12)))

  test("ordinary andThen preserves smooth and exact capabilities"):
    val errors = typeCheckErrors(
      """
        import reframe4s.core.*
        import image4s.geometry.*
        def smooth[F <: Frame[D2], T <: Frame[D2]](
          first: SmoothMap[F, T, D2],
          second: SmoothMap[T, T, D2]
        ): SmoothMap[F, T, D2] = first.andThen(second)
        def exact[F <: Frame[D2], T <: Frame[D2]](
          first: SmoothIso[F, T, D2],
          second: SmoothIso[T, T, D2]
        ): SmoothIso[F, T, D2] = first.andThen(second)
      """
    )
    assertEquals(errors, Nil)

  test("public point construction cannot manufacture a widened wrong owner"):
    val errors = typeCheckErrors(
      """
        import image4s.geometry.*
        def wrongOwner(
          declared: Frame[D2],
          wrong: Frame[D2]
        ): Either[GeometryError, Point[Frame[D2], D2]] =
          Point.fromVector[D2, Frame[D2]](
            wrong,
            Vector(1.0, 2.0)
          )
      """
    )

    assert(errors.nonEmpty)

  test("live frames do not expose a total persistent id"):
    val errors = typeCheckErrors(
      """
        import image4s.geometry.*
        def unsoundId(frame: Frame[D2]): FrameId =
          frame.id
      """
    )

    assert(errors.nonEmpty)

  test("analytic affine Jacobian agrees with a finite difference"):
    val source = rightGeometry(Frame.named[D2]("source"))
    val target = rightGeometry(Frame.named[D2]("target"))
    val map = FramedAffine.between(source, target)(
      rightGeometry(
        Affine.fromRowMajor[D2](
          Vector(
            2.0,
            1.0,
            3.0,
            -1.0,
            4.0,
            5.0,
            0.0,
            0.0,
            1.0
          )
        )
      )
    )
    val point = rightGeometry(Point.in[D2](source)(0.7, -1.2))
    val jet = rightMap(map.jet1At(point))
    val epsilon = 1e-6

    for axis <- 0 until 2 do
      val plusCoordinates =
        point.coordinates.updated(axis, point.coordinates(axis) + epsilon)
      val minusCoordinates =
        point.coordinates.updated(axis, point.coordinates(axis) - epsilon)
      val plus = rightMap(
        map(rightGeometry(Point.fromVector(source, plusCoordinates)))
      )
      val minus = rightMap(
        map(rightGeometry(Point.fromVector(source, minusCoordinates)))
      )
      for output <- 0 until 2 do
        val finiteDifference =
          (plus.coordinates(output) - minus.coordinates(output)) /
            (2.0 * epsilon)
        assertEqualsDouble(
          finiteDifference,
          jet.differential(output, axis),
          1e-8
        )

  test("provider numerical differential handles ordinary spatial maps and typed failures"):
    val sourceFrame = rightGeometry(Frame.named[D2]("numeric-source"))
    val targetFrame = rightGeometry(Frame.named[D2]("numeric-target"))
    val map = new SpatialMap[sourceFrame.type, targetFrame.type, D2]:
      val source: sourceFrame.type = sourceFrame
      val target: targetFrame.type = targetFrame

      def apply(
          point: Point[sourceFrame.type, D2]
      ): Either[MapError, Point[targetFrame.type, D2]] =
        if point.coordinates(0) < 0.0 then
          Left(MapError.OutsideDomain(point.coordinates))
        else
          Point
            .in[D2](targetFrame)(
              point.coordinates(0) * point.coordinates(0),
              3.0 * point.coordinates(1)
            )
            .left
            .map(MapError.Geometry.apply)

    val point = rightGeometry(Point.in[D2](sourceFrame)(2.0, 1.5))
    val jet = rightMap(
      SpatialDifferential.centralDifference(map, point, step = 1e-5)
    )
    assertEqualsDouble(jet.value.coordinates(0), 4.0, 1e-12)
    assertEqualsDouble(jet.differential(0, 0), 4.0, 1e-8)
    assertEqualsDouble(jet.differential(0, 1), 0.0, 1e-8)
    assertEqualsDouble(jet.differential(1, 0), 0.0, 1e-8)
    assertEqualsDouble(jet.differential(1, 1), 3.0, 1e-8)

    assertEquals(
      SpatialDifferential.centralDifference(map, point, step = 0.0).left.toOption,
      Some(MapError.InvalidFiniteDifferenceStep(0.0))
    )
    val boundary = rightGeometry(Point.in[D2](sourceFrame)(1e-6, 0.0))
    SpatialDifferential.centralDifference(map, boundary, step = 1e-5) match
      case Left(MapError.OutsideDomain(_)) => ()
      case other => fail(s"expected map domain failure, got $other")

  test("oblique D3 affine Jacobian agrees with a finite difference"):
    val source = rightGeometry(Frame.named[D3]("source-3d"))
    val target = rightGeometry(Frame.named[D3]("target-3d"))
    val map = FramedAffine.between(source, target)(
      rightGeometry(
        Affine.fromRowMajor[D3](
          Vector(
            1.2, 0.2, -0.1, 3.0,
            -0.3, 0.9, 0.4, -2.0,
            0.1, -0.2, 1.1, 5.0,
            0.0, 0.0, 0.0, 1.0
          )
        )
      )
    )
    val point = rightGeometry(Point.in[D3](source)(0.7, -1.2, 2.5))
    val jet = rightMap(map.jet1At(point))
    val epsilon = 1e-6

    for axis <- 0 until 3 do
      val plus = rightMap(
        map(
          rightGeometry(
            Point.fromVector(
              source,
              point.coordinates.updated(
                axis,
                point.coordinates(axis) + epsilon
              )
            )
          )
        )
      )
      val minus = rightMap(
        map(
          rightGeometry(
            Point.fromVector(
              source,
              point.coordinates.updated(
                axis,
                point.coordinates(axis) - epsilon
              )
            )
          )
        )
      )
      for output <- 0 until 3 do
        val finiteDifference =
          (plus.coordinates(output) - minus.coordinates(output)) /
            (2.0 * epsilon)
        assertEqualsDouble(
          finiteDifference,
          jet.differential(output, axis),
          1e-8
        )

  test("numerical inverse evidence cannot acquire SmoothIso"):
    val errors = typeCheckErrors(
      """
        import reframe4s.core.*
        import image4s.geometry.*
        summon[
          InverseEstimate[Frame[D2], Frame[D2], D2] <:<
            SmoothIso[Frame[D2], Frame[D2], D2]
        ]
      """
    )
    assert(errors.nonEmpty)

  test("scoped topology evidence cannot acquire SmoothIso"):
    val errors = typeCheckErrors(
      """
        import reframe4s.core.*
        import image4s.geometry.*
        summon[
          TopologyCertificate[Frame[D2], D2] <:<
            SmoothIso[Frame[D2], Frame[D2], D2]
        ]
      """
    )
    assert(errors.nonEmpty)

  test("certified numerical pairs cannot acquire SmoothIso"):
    val errors = typeCheckErrors(
      """
        import reframe4s.core.*
        import image4s.geometry.*
        summon[
          CertifiedBidirectionalPair[Frame[D2], Frame[D2], D2] <:<
            SmoothIso[Frame[D2], Frame[D2], D2]
        ]
      """
    )
    assert(errors.nonEmpty)

  test("index regions reject ranks inconsistent with their dimension"):
    assert(
      IndexRegion
        .within[D2](Vector(2, 2, 2), Vector(0, 0, 0), Vector(1, 1, 1))
        .isLeft
    )

  test("inverse pair certification enforces residual and coverage criteria"):
    val fixture = evidenceFixture()
    val identity =
      SpatialMap.identity[D2, fixture.frame.type](fixture.frame)
    val converged = rightEvidence(InversionStatus.converged(12))
    val highResidual = rightEvidence(
      InversePairEvidence.create(
        0.2,
        100,
        1.0,
        converged,
        converged
      )
    )
    val rejected = CertifiedBidirectionalPair.fromReportedEvidence(
      identity,
      identity,
      highResidual,
      fixture.domain,
      fixture.domain,
      fixture.inverseCriteria,
      fixture.revision
    )

    assert(rejected.isLeft)

    val acceptedEvidence = rightEvidence(
      InversePairEvidence.create(
        0.01,
        100,
        0.99,
        converged,
        converged
      )
    )
    val accepted = CertifiedBidirectionalPair.fromReportedEvidence(
      identity,
      identity,
      acceptedEvidence,
      fixture.domain,
      fixture.domain,
      fixture.inverseCriteria,
      fixture.revision
    )
    assert(accepted.isRight)

  test("inverse estimates retain nonconvergence without capability inflation"):
    val fixture = evidenceFixture()
    val status = rightEvidence(InversionStatus.iterationLimit(40))
    val residual = rightEvidence(
      InverseResidual.create(0.2, 0.05, 100, 0.9)
    )
    val estimate = rightEvidence(
      InverseEstimate.record(
        SpatialMap.identity[D2, fixture.frame.type](fixture.frame),
        residual,
        status,
        fixture.domain,
        fixture.inverseCriteria,
        fixture.revision
      )
    )

    assert(!estimate.status.converged)
    assertEquals(estimate.status.iterations, 40)

  test("inverse domains restore exactly and reject a tampered resolution"):
    val fixture = evidenceFixture()
    val restored = rightEvidence(
      InverseDomain.restore(fixture.domain.record, fixture.grid)
    )
    val tampered =
      fixture.domain.record.copy(resolution = Vector(9, 10))

    assertEquals(restored.record, fixture.domain.record)
    assert(InverseDomain.restore(tampered, fixture.grid).isLeft)

  test("inverse evidence rejects distinct live endpoint owners"):
    val frameId = rightGeometry(FrameId.parse("shared"))
    val original =
      rightGeometry(Frame.persistentNamed[D2](frameId, "shared"))
    val record = rightGeometry(original.record)
    val first: Frame[D2] = rightGeometry(
      Frame.restore[D2](
        record,
        image4s.geometry.FrameRegistry.empty
      )
    ).frame
    val second: Frame[D2] = rightGeometry(
      Frame.restore[D2](
        record,
        image4s.geometry.FrameRegistry.empty
      )
    ).frame
    val firstDomain = widenedDomain(first)
    val secondDomain = widenedDomain(second)
    val map = SpatialMap.identity[D2, Frame[D2]](first)
    val residual = rightEvidence(
      InverseResidual.create(0.01, 0.005, 20, 1.0)
    )
    val criteria = rightEvidence(InverseCriteria.create(0.05, 0.95))
    val status = rightEvidence(InversionStatus.converged(4))
    val revision = rightEvidence(ImplementationRevision.parse("endpoint-test"))
    val estimate = InverseEstimate.record(
      map,
      residual,
      status,
      secondDomain,
      criteria,
      revision
    )

    estimate match
      case Left(
            EvidenceError.EndpointMismatch(
              _: MapError.SourceFrameOwnerMismatch
            )
          ) => ()
      case Left(error) => fail(s"unexpected estimate error: ${error.message}")
      case Right(_)    => fail("estimate accepted a distinct live owner")

    val pairEvidence = rightEvidence(
      InversePairEvidence.create(
        0.01,
        20,
        1.0,
        status,
        status
      )
    )
    val pair = CertifiedBidirectionalPair.fromReportedEvidence(
      map,
      map,
      pairEvidence,
      firstDomain,
      secondDomain,
      criteria,
      revision
    )
    pair match
      case Left(EvidenceError.EndpointMismatch(_)) => ()
      case Left(error) => fail(s"unexpected pair error: ${error.message}")
      case Right(_)    => fail("pair accepted distinct live endpoint owners")

  test("topology certification is bound to an exact finite scope"):
    val fixture = evidenceFixture()
    val criteria = rightEvidence(TopologyCriteria.create(0.95, 0))
    val scope = rightEvidence(
      TopologyScope.on(
        fixture.grid,
        fixture.region,
        CellSamplingRule.CellCornersAndCenter,
        0.01,
        criteria,
        fixture.revision
      )
    )
    val diagnostics = rightEvidence(
      TopologyDiagnostics.create(0.2, 1.8, 0, 0.99, 81)
    )
    val certificate = rightEvidence(
      TopologyCertificate.fromReportedDiagnostics(scope, diagnostics)
    )

    assertEquals(
      certificate.scope.gridKey,
      fixture.grid.persistentKey.getOrElse(fail("missing grid key"))
    )
    assertEquals(certificate.scope.resolution, Vector(10, 10))
    assertEquals(
      certificate.scope.region.upperExclusive,
      Vector(9, 9)
    )

  test("persisted evidence requires and compares complete grid keys"):
    val ephemeralFrame = rightGeometry(Frame.named[D2]("ephemeral"))
    val ephemeralGrid =
      rightGeometry(
        Grid.in(ephemeralFrame)(
          Vector(10, 10),
          Affine.identity[D2]
        )
      )
    val ephemeralRegion =
      rightEvidence(
        IndexRegion.within[D2](
          ephemeralGrid.shape,
          Vector(0, 0),
          Vector(10, 10)
        )
      )

    assertEquals(
      InverseDomain.on(ephemeralGrid, ephemeralRegion),
      Left(EvidenceError.PersistentGridRequired)
    )

    val fixture = evidenceFixture()
    val originalKey =
      fixture.grid.persistentKey.getOrElse(fail("missing grid key"))
    val sameIdDifferentGrid =
      rightGeometry(
        Grid.createPersistent[D2, fixture.frame.type](
          originalKey.id,
          fixture.frame
        )(
          Vector(11, 10),
          Affine.identity[D2]
        )
      )

    InverseDomain.restore(fixture.domain.record, sameIdDifferentGrid) match
      case Left(EvidenceError.GridMismatch(expected, actual)) =>
        assertEquals(expected.id, actual.id)
        assertNotEquals(expected, actual)
      case other =>
        fail(s"expected a complete-key grid mismatch, got $other")

  test("topology certification rejects a sampled fold"):
    val fixture = evidenceFixture()
    val criteria = rightEvidence(TopologyCriteria.create(1.0, 0))
    val scope = rightEvidence(
      TopologyScope.on(
        fixture.grid,
        fixture.region,
        CellSamplingRule.CellCorners,
        0.01,
        criteria,
        fixture.revision
      )
    )
    val diagnostics = rightEvidence(
      TopologyDiagnostics.create(-0.1, 1.8, 1, 1.0, 81)
    )

    assert(
      TopologyCertificate.fromReportedDiagnostics(scope, diagnostics).isLeft
    )

  test("topology diagnostics reject impossible folded-cell counts"):
    assert(
      TopologyDiagnostics.create(0.1, 1.0, 2, 1.0, 1).isLeft
    )

  test("topology evidence restores and rejects a different scope"):
    val fixture = evidenceFixture()
    val criteria = rightEvidence(TopologyCriteria.create(0.95, 0))
    val scope = rightEvidence(
      TopologyScope.on(
        fixture.grid,
        fixture.region,
        CellSamplingRule.CellCorners,
        0.01,
        criteria,
        fixture.revision
      )
    )
    val diagnostics = rightEvidence(
      TopologyDiagnostics.create(0.2, 1.8, 0, 0.99, 81)
    )
    val certificate = rightEvidence(
      TopologyCertificate.fromReportedDiagnostics(scope, diagnostics)
    )
    val restored = rightEvidence(
      TopologyCertificate.restore(certificate.record, fixture.grid)
    )
    val differentScope = rightEvidence(
      TopologyScope.on(
        fixture.grid,
        fixture.region,
        CellSamplingRule.CellCornersAndCenter,
        0.01,
        criteria,
        fixture.revision
      )
    )

    assertEquals(restored.record, certificate.record)
    assert(restored.appliesTo(fixture.grid, certificate.scope.record).isRight)
    assert(restored.appliesTo(fixture.grid, differentScope.record).isLeft)

  private final class EvidenceFixture(
      val frame: Frame[D2]
  )(
      val grid: Grid[frame.type, D2],
      val region: IndexRegion[D2],
      val domain: InverseDomain[frame.type, D2],
      val inverseCriteria: InverseCriteria,
      val revision: ImplementationRevision
  )

  private def evidenceFixture(): EvidenceFixture =
    val frameId = rightGeometry(FrameId.parse("evidence-frame"))
    val gridId = rightGeometry(GridId.parse("evidence-grid"))
    val frame =
      rightGeometry(Frame.persistentNamed[D2](frameId, "evidence"))
    val grid = rightGeometry(
      Grid.createPersistent[D2, frame.type](gridId, frame)(
        Vector(10, 10),
        Affine.identity[D2]
      )
    )
    val region = rightEvidence(
      IndexRegion.within[D2](
        grid.shape,
        Vector(0, 0),
        Vector(9, 9)
      )
    )
    new EvidenceFixture(frame)(
      grid,
      region,
      rightEvidence(InverseDomain.on(grid, region)),
      rightEvidence(InverseCriteria.create(0.05, 0.95)),
      rightEvidence(ImplementationRevision.parse("test-revision"))
    )

  private def widenedDomain(
      frame: Frame[D2]
  ): InverseDomain[Frame[D2], D2] =
    val gridId = rightGeometry(GridId.parse("shared-domain"))
    val grid = rightGeometry(
      Grid.createPersistent(gridId, frame)(
        Vector(3, 3),
        Affine.identity[D2]
      )
    )
    val region = rightEvidence(
      IndexRegion.within[D2](
        grid.shape,
        Vector(0, 0),
        Vector(3, 3)
      )
    )
    rightEvidence(InverseDomain.on(grid, region))

  private def rightGeometry[A](value: Either[GeometryError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def rightMap[A](value: Either[MapError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def rightEvidence[A](value: Either[EvidenceError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)
