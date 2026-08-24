package reframe4s.field

import image4s.Axis
import image4s.AxisKind
import image4s.BoundaryPolicy
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import reframe4s.core.CellSamplingRule
import reframe4s.core.EvidenceError
import reframe4s.core.ImplementationRevision
import reframe4s.core.IndexRegion
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import reframe4s.core.TopologyCriteria
import reframe4s.core.TopologyScope
import image4s.geometry.Affine
import image4s.geometry.ContinuousIndex
import image4s.geometry.D2
import image4s.geometry.D3
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.FrameId
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.GridId
import image4s.geometry.LatticeIndex
import image4s.geometry.Point
import reframe4s.resample.Interpolation

final class DenseFieldSuite extends munit.FunSuite:
  test("D2 and D3 fields validate their one physical component axis"):
    val frame2 = geometryRight(Frame.named[D2]("components-d2"))
    val grid2 =
      geometryRight(Grid.in(frame2)(Vector(2, 2), Affine.identity[D2]))
    val direction2 = imageRight(Axis.create("component", 2, AxisKind.Direction))
    val axes2 = imageRight(NonSpatialAxes.from(Vector(direction2)))
    val samples2 =
      imageRight(
        Sampled.continuous(
          grid2,
          axes2,
          NDArray.zeros[Double, Rank[3]](Shape(2, 2, 2))
        )
      )

    assert(Velocity.from(samples2).isRight)

    val channels2 = imageRight(Axis.create("component", 2, AxisKind.Channel))
    val channelAxes2 = imageRight(NonSpatialAxes.from(Vector(channels2)))
    val channelSamples2 =
      imageRight(
        Sampled.continuous(
          grid2,
          channelAxes2,
          NDArray.zeros[Double, Rank[3]](Shape(2, 2, 2))
        )
      )
    assertEquals(
      Velocity.from(channelSamples2),
      Left(
        FieldError.InvalidComponentAxes(
          2,
          Vector(AxisKind.Channel -> 2)
        )
      )
    )

    val frame3 = geometryRight(Frame.named[D3]("components-d3"))
    val grid3 =
      geometryRight(
        Grid.in(frame3)(Vector(2, 2, 2), Affine.identity[D3])
      )
    val direction3 = imageRight(Axis.create("component", 3, AxisKind.Direction))
    val axes3 = imageRight(NonSpatialAxes.from(Vector(direction3)))
    val samples3 =
      imageRight(
        Sampled.continuous(
          grid3,
          axes3,
          NDArray.zeros[Double, Rank[4]](Shape(2, 2, 2, 3))
        )
      )

    assert(Displacement.from(samples3).isRight)

    val shortDirection =
      imageRight(Axis.create("component", 2, AxisKind.Direction))
    val shortAxes =
      imageRight(NonSpatialAxes.from(Vector(shortDirection)))
    val shortSamples =
      imageRight(
        Sampled.continuous(
          grid3,
          shortAxes,
          NDArray.zeros[Double, Rank[4]](Shape(2, 2, 2, 2))
        )
      )
    assertEquals(
      Displacement.from(shortSamples),
      Left(
        FieldError.InvalidComponentAxes(
          3,
          Vector(AxisKind.Direction -> 2)
        )
      )
    )

  test("dense maps interpolate absolute coordinates in physical space"):
    val source = geometryRight(Frame.named[D2]("physical-source"))
    val target = geometryRight(Frame.named[D2]("physical-target"))
    val embedding =
      geometryRight(
        Affine.fromOriginSpacingDirection[D2](
          origin = Vector(10.0, -3.0),
          spacing = Vector(2.0, 3.0),
          directionRowMajor = Vector(0.0, -1.0, 1.0, 0.0)
        )
      )
    val grid =
      geometryRight(Grid.in(source)(Vector(4, 4), embedding))
    val axis = imageRight(Axis.create("component", 2, AxisKind.Direction))
    val axes = imageRight(NonSpatialAxes.from(Vector(axis)))
    val values =
      for
        i <- 0 until 4
        j <- 0 until 4
        component <- 0 until 2
      yield
        val physical =
          geometryRight(
            grid.pointAt(geometryRight(LatticeIndex.of[D2](i, j)))
          ).coordinates
        if component == 0 then
          2.0 * physical(0) + 0.5 * physical(1) + 1.0
        else
          -physical(0) + 3.0 * physical(1) - 2.0
    val coordinates =
      imageRight(
        Sampled.continuous(
          grid,
          axes,
          NDArray.fromSeq(Shape(4, 4, 2), values)
        )
      )
    val map = fieldRight(DenseMap.fromCoordinates(coordinates, target))
    val input =
      geometryRight(
        grid.pointAt(geometryRight(ContinuousIndex.of[D2](1.25, 1.5)))
      )
    val result = mapRight(map(input))
    val physical = input.coordinates

    assertEqualsDouble(
      result.coordinates(0),
      2.0 * physical(0) + 0.5 * physical(1) + 1.0,
      1e-10
    )
    assertEqualsDouble(
      result.coordinates(1),
      -physical(0) + 3.0 * physical(1) - 2.0,
      1e-10
    )

  test("dense maps expose interpolation and identity-outside policy"):
    val frame = geometryRight(Frame.named[D2]("dense-boundary"))
    val grid =
      geometryRight(Grid.in(frame)(Vector(2, 2), Affine.identity[D2]))
    val axis = imageRight(Axis.create("component", 2, AxisKind.Direction))
    val axes = imageRight(NonSpatialAxes.from(Vector(axis)))
    val coordinates =
      imageRight(
        Sampled.continuous(
          grid,
          axes,
          NDArray.tabulate[Double](2, 2, 2): (i, j, component) =>
            if component == 0 then i.toDouble + 10.0
            else j.toDouble - 5.0
        )
      )
    val preserved =
      fieldRight(
        DenseMap.fromCoordinates(
          coordinates,
          frame,
          Interpolation.Nearest,
          CoordinateBoundaryPolicy.PreserveSource
        )
      )
    val rejected =
      fieldRight(DenseMap.fromCoordinates(coordinates, frame))
    val outside = geometryRight(Point.in[D2](frame)(5.0, -3.0))

    assertEquals(mapRight(preserved(outside)).coordinates, outside.coordinates)
    assertEquals(
      rejected(outside),
      Left(MapError.OutsideDomain(outside.coordinates))
    )
    assertEquals(
      DenseMap.fromCoordinates(
        coordinates,
        frame,
        boundary = CoordinateBoundaryPolicy.Constant(Vector(1.0))
      ),
      Left(FieldError.InvalidBoundaryCoordinates(2, 1))
    )

  test("dense-map fingerprints survive checked frame erasure and detect content"):
    val frame = geometryRight(Frame.named[D2]("dense-fingerprint"))
    val grid =
      geometryRight(Grid.in(frame)(Vector(2, 2), Affine.identity[D2]))
    val axis = imageRight(Axis.create("component", 2, AxisKind.Direction))
    val axes = imageRight(NonSpatialAxes.from(Vector(axis)))

    def dense(offset: Double) =
      val coordinates =
        imageRight(
          Sampled.continuous(
            grid,
            axes,
            NDArray.tabulate[Double](2, 2, 2): (i, j, component) =>
              if component == 0 then i.toDouble + offset else j.toDouble
          )
        )
      fieldRight(DenseMap.fromCoordinates(coordinates, frame))

    val first = dense(0.0)
    val changed = dense(0.25)
    val erased = SpatialMap.eraseFrameRefinements(first)

    assert(DenseMap.isDense(erased))
    assertEquals(
      DenseMap.fingerprint(erased),
      DenseMap.fingerprint(SpatialMap.eraseFrameRefinements(first))
    )
    assertNotEquals(
      DenseMap.fingerprint(erased),
      DenseMap.fingerprint(SpatialMap.eraseFrameRefinements(changed))
    )

  test("displacement maps delegate cubic interpolation to the provider kernel"):
    val frame = geometryRight(Frame.named[D2]("cubic-displacement"))
    val grid =
      geometryRight(Grid.in(frame)(Vector(5, 4), Affine.identity[D2]))
    val axis = imageRight(Axis.create("component", 2, AxisKind.Direction))
    val axes = imageRight(NonSpatialAxes.from(Vector(axis)))
    val samples =
      imageRight(
        Sampled.continuous(
          grid,
          axes,
          NDArray.tabulate[Double](5, 4, 2): (i, _, component) =>
            if component == 0 then i.toDouble * i.toDouble else 0.0
        )
      )
    val displacement = fieldRight(Displacement.from(samples))
    val map =
      Displacement.asInterpolatedMap(
        displacement,
        Interpolation.Cubic,
        BoundaryPolicy.Constant(0.0)
      )
    val input =
      geometryRight(
        grid.pointAt(geometryRight(ContinuousIndex.of[D2](1.5, 1.0)))
      )

    val result = mapRight(map(input))
    assertEqualsDouble(result.coordinates(0), 3.75, 1e-10)
    assertEqualsDouble(result.coordinates(1), 1.0, 1e-10)

  test("cell topology accepts D2 identity and rejects a reflection"):
    val frame =
      geometryRight(
        Frame.persistentNamed[D2](
          geometryRight(FrameId.parse("topology-d2-frame")),
          "topology-d2"
        )
      )
    val grid =
      geometryRight(
        Grid.createPersistent(
          geometryRight(GridId.parse("topology-d2-grid")),
          frame
        )(Vector(3, 3), Affine.identity[D2])
      )
    val axis = imageRight(Axis.create("component", 2, AxisKind.Direction))
    val axes = imageRight(NonSpatialAxes.from(Vector(axis)))

    def mapWithX(transformX: Double => Double) =
      val values =
        for
          i <- 0 until 3
          j <- 0 until 3
          component <- 0 until 2
        yield
          if component == 0 then transformX(i.toDouble)
          else j.toDouble
      val samples =
        imageRight(
          Sampled.continuous(
            grid,
            axes,
            NDArray.fromSeq(Shape(3, 3, 2), values)
          )
        )
      fieldRight(DenseMap.fromCoordinates(samples, frame))

    val scope = topologyScope(grid)
    val identityMap = mapWithX(value => value)
    val reflection = mapWithX(value => -value)
    val identityDiagnostics =
      topologyRight(TopologyAssessor.diagnose(identityMap, scope))
    val reflectionDiagnostics =
      topologyRight(TopologyAssessor.diagnose(reflection, scope))

    assertEquals(identityDiagnostics.foldedCellCount, 0L)
    assertEqualsDouble(identityDiagnostics.minimumCellJacobian, 1.0, 1e-12)
    assert(TopologyAssessor.certify(identityMap, scope).isRight)
    assertEquals(reflectionDiagnostics.foldedCellCount, 4L)
    assertEqualsDouble(
      reflectionDiagnostics.maximumCellJacobian,
      -1.0,
      1e-12
    )
    assert(TopologyAssessor.certify(reflection, scope).isLeft)

  test("cell topology accepts a D3 physical translation"):
    val frame =
      geometryRight(
        Frame.persistentNamed[D3](
          geometryRight(FrameId.parse("topology-d3-frame")),
          "topology-d3"
        )
      )
    val grid =
      geometryRight(
        Grid.createPersistent(
          geometryRight(GridId.parse("topology-d3-grid")),
          frame
        )(Vector(3, 3, 3), Affine.identity[D3])
      )
    val axis = imageRight(Axis.create("component", 3, AxisKind.Direction))
    val axes = imageRight(NonSpatialAxes.from(Vector(axis)))
    val shift = Vector(2.0, -1.0, 0.5)
    val values =
      for
        i <- 0 until 3
        j <- 0 until 3
        k <- 0 until 3
        component <- 0 until 3
      yield Vector(i.toDouble, j.toDouble, k.toDouble)(component) + shift(component)
    val samples =
      imageRight(
        Sampled.continuous(
          grid,
          axes,
          NDArray.fromSeq(Shape(3, 3, 3, 3), values)
        )
      )
    val map = fieldRight(DenseMap.fromCoordinates(samples, frame))
    val certificate =
      topologyRight(TopologyAssessor.certify(map, topologyScope(grid)))

    assertEquals(certificate.diagnostics.foldedCellCount, 0L)
    assertEquals(certificate.diagnostics.sampledCellCount, 8L)
    assertEqualsDouble(
      certificate.diagnostics.minimumCellJacobian,
      1.0,
      1e-12
    )

  private def topologyScope[F <: Frame[D], D <: Dim](
      grid: Grid[F, D]
  )(using dimension: Dimension[D]): TopologyScope[F, D] =
    val region =
      evidenceRight(
        IndexRegion.within[D](
          grid.shape,
          Vector.fill(dimension.rank)(0),
          grid.shape
        )
      )
    val criteria = evidenceRight(TopologyCriteria.create(1.0, 0L))
    val revision =
      evidenceRight(ImplementationRevision.parse("dense-field-suite-v1"))
    evidenceRight(
      TopologyScope.on(
        grid,
        region,
        CellSamplingRule.CellCornersAndCenter,
        determinantThreshold = 0.01,
        criteria,
        revision
      )
    )

  private def geometryRight[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def imageRight[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def fieldRight[A](value: Either[FieldError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def evidenceRight[A](value: Either[EvidenceError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def topologyRight[A](
      value: Either[TopologyAssessmentError, A]
  ): A =
    value.fold(error => fail(error.message), identity)

  private def mapRight[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)
