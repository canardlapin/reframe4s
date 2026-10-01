package reframe4s.halfflow

import reframe4s.halfflow.internal.*

class ForwardMidpointExportSuite extends munit.FunSuite:
  test("a translated residual has the analytic inverse through the boundary"):
    val grid = GridSpec.identity(Vector(25, 25, 25))
    val frame = Frame[Unit](SpatialDomainId("export-translation"), grid)
    val forward = affinePull(frame, 1.0, 3.0)
    val config = right(ResidualInverseConfig.make(
      shrinks = Vector(2, 1),
      iterationsPerLevel = 50,
      iterationToleranceMm = 1e-8,
      interiorMargin = 6
    ))
    val refined = ResidualInverseRefiner.refine(forward, config)
    for index <- 0 until grid.nVoxels do
      assert(refined.inverse.validity.contains(index))
      assertEqualsDouble(
        refined.inverse.sourceCoordinates.linearComponent(index, 0),
        (index % grid.extentX).toDouble - 3.0,
        1e-6,
        s"inverse at voxel $index"
      )

  test("affine residual endpoint maps agree with analytic composition over the full grid"):
    val grid = GridSpec.identity(Vector(25, 25, 25))
    val frame = Frame[Unit](SpatialDomainId("export-affine"), grid)
    val affine = right(AffineIso.make(frame, frame, Affine3D.identity))
    val state = right(ForwardMidpoint.make(
      right(ForwardMidpointArm.make(affinePull(frame, 1.05, 3.0), affine)),
      right(ForwardMidpointArm.make(affinePull(frame, 0.96, -2.0), affine))
    ))
    val config = right(ResidualInverseConfig.make(
      shrinks = Vector(2, 1),
      iterationsPerLevel = 50,
      iterationToleranceMm = 1e-8,
      interiorMargin = 6
    ))
    val exported = right(ForwardMidpointExporter.build(state, config))
    for index <- 0 until grid.nVoxels do
      val x = (index % grid.extentX).toDouble
      assert(exported.transform.forward.validity.contains(index))
      assert(exported.transform.backward.validity.contains(index))
      assertEqualsDouble(exported.transform.forward.sourceCoordinates.linearComponent(index, 0),
        0.96 * (x - 3.0) / 1.05 - 2.0, 1e-6)
      assertEqualsDouble(exported.transform.backward.sourceCoordinates.linearComponent(index, 0),
        1.05 * (x + 2.0) / 0.96 + 3.0, 1e-6)

  test("boundary continuation preserves world affine fields on an oblique anisotropic grid"):
    val grid = GridSpec(Vector(7, 7, 7), DMat.fromRows(Vector(
      Vector(1.2, -0.4, 0.1, -3.0),
      Vector(0.3, 1.5, 0.2, 2.0),
      Vector(0.0, 0.1, 2.0, -1.0),
      Vector(0.0, 0.0, 0.0, 1.0)
    )))
    val frame = Frame[Unit](SpatialDomainId("export-oblique"), grid)
    val forward = affinePull(frame, 1.05, 3.0)
    val target = GridSpec.identity(Vector(15, 15, 15))
    val sampled = HalfFlowKernels.regridPull(forward.sourceCoordinates, target,
      forward.validity, CoordinateMapOutside.BoundaryLinear)
    for index <- 0 until target.nVoxels do
      assert(sampled.valid(index))
      assertEqualsDouble(sampled.field.linearComponent(index, 0),
        1.05 * (index % target.extentX).toDouble + 3.0, 1e-10)
      assertEqualsDouble(sampled.field.linearComponent(index, 1),
        ((index / target.extentX) % target.extentY).toDouble, 1e-10)
      assertEqualsDouble(sampled.field.linearComponent(index, 2),
        (index / (target.extentX * target.extentY)).toDouble, 1e-10)

  test("boundary continuation does not turn invalid contributing samples into valid ones"):
    val grid = GridSpec.identity(Vector(5, 5, 5))
    val target = GridSpec(Vector(5, 5, 5), DMat.fromRows(Vector(
      Vector(1.0, 0.0, 0.0, -0.5),
      Vector(0.0, 1.0, 0.0, 0.0),
      Vector(0.0, 0.0, 1.0, 0.0),
      Vector(0.0, 0.0, 0.0, 1.0)
    )))
    val mask = Array.fill(grid.nVoxels)(true)
    mask(5 * 2 + 25 * 2) = false
    val sampled = HalfFlowKernels.regridPull(HalfFlowKernels.identity(grid).field,
      target, FieldValidity.copyMask(mask), CoordinateMapOutside.BoundaryLinear)
    assert(!sampled.valid(5 * 2 + 25 * 2))
    assert(sampled.valid(2 + 5 * 2 + 25 * 2))
    assert(sampled.valid(5 + 25))

  test("mutable self-composition also continues the analytic affine at the boundary"):
    val grid = GridSpec.identity(Vector(7, 7, 7))
    val source = ravel.MutableNDArray.zeros[Double, ravel.Rank[1]](ravel.Shape(3 * grid.nVoxels))
    val destination = ravel.MutableNDArray.zeros[Double, ravel.Rank[1]](ravel.Shape(3 * grid.nVoxels))
    val values = ravel.MutableCanonicalArray.require(source)
    for x <- 0 until 7; y <- 0 until 7; z <- 0 until 7 do
      val base = 3 * (z + 7 * (y + 7 * x))
      values(base) = 1.05 * x + 3.0
      values(base + 1) = y.toDouble
      values(base + 2) = z.toDouble
    val valid = new Array[Boolean](grid.nVoxels)
    HalfFlowKernels.composeSelfPullInto(grid, source, Array.fill(grid.nVoxels)(true),
      destination, valid, DenseFieldSampler(grid), CoordinateMapOutside.BoundaryLinear)
    val result = ravel.MutableCanonicalArray.require(destination)
    assert(valid.forall(identity))
    for x <- 0 until 7; y <- 0 until 7; z <- 0 until 7 do
      val base = 3 * (z + 7 * (y + 7 * x))
      assertEqualsDouble(result(base), 1.05 * (1.05 * x + 3.0) + 3.0, 1e-12)
      assertEqualsDouble(result(base + 1), y.toDouble, 1e-12)
      assertEqualsDouble(result(base + 2), z.toDouble, 1e-12)

  test("export admission still rejects a folded endpoint in either direction"):
    val grid = GridSpec.identity(Vector(9, 9, 9))
    val frame = Frame[Unit](SpatialDomainId("export-fold"), grid)
    val state = right(ForwardMidpoint.identity(frame, frame, frame))
    val config = right(ResidualInverseConfig.make(shrinks = Vector(1), interiorMargin = 2))
    val candidate = right(ForwardMidpointExporter.inspect(state, config))
    val folded = affinePull(frame, -1.0, 8.0)
    for direction <- ExportDirection.values do
      val pair = direction match
        case ExportDirection.FixedToMoving => right(InversePair.make(folded, DensePull.identity(frame)))
        case ExportDirection.MovingToFixed => right(InversePair.make(DensePull.identity(frame), folded))
      ForwardMidpointExporter.admit(candidate.copy(transform = pair), config) match
        case Left(ForwardExportError.ExportedTopologyInvalid(observed, minimum, count)) =>
          assertEquals(observed, direction)
          assertEqualsDouble(minimum, -1.0, 1e-12)
          assertEquals(count, 7 * 7 * 7)
        case other => fail(s"fold was not rejected: $other")

  private def affinePull[A](frame: Frame[A], scale: Double, shift: Double): DensePull[A, A] =
    val coordinates = HalfFlowKernels.identity(frame.grid).field.copyLegacyPlanar
    for index <- 0 until frame.grid.nVoxels do
      coordinates(index) = scale * coordinates(index) + shift
    right(DensePull.make(
      frame,
      frame,
      DenseVectorField.fromLegacyPlanar(frame.grid, coordinates, DenseVectorFieldKind.SourceCoordinates)
    ))

  private def right[E, A](value: Either[E, A]): A =
    value.fold(error => fail(error.toString), identity)
