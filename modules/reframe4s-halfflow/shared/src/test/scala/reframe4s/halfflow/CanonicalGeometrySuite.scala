package reframe4s.halfflow

import image4s.geometry.{Affine, D3, Frame as PhysicalFrame, Grid, Point}
import reframe4s.core.SpatialMap
import reframe4s.halfflow.internal.*
import reframe4s.lie.FramedAffine
import ravel.NDArray

final class CanonicalGeometrySuite extends munit.FunSuite:
  private def right[E, A](value: Either[E, A]): A =
    value.fold(error => fail(error.toString), identity)

  private val source: PhysicalFrame[D3] = right(PhysicalFrame.named[D3]("source"))
  private val target: PhysicalFrame[D3] = right(PhysicalFrame.named[D3]("target"))
  private val operator = right(Affine.fromRowMajor[D3](Vector(
    -1.7, 0.2, 0.0, 13.0,
    0.1, 2.1, -0.3, -8.5,
    0.0, 0.2, 2.8, 21.0,
    0.0, 0.0, 0.0, 1.0
  )))
  private val canonical = right(Grid.forFrame[D3, PhysicalFrame[D3]](source)(Vector(4, 5, 6), operator))
  private val grid = GridSpec.fromGrid(canonical)

  private def mapping(p: Vector[Double]): Vector[Double] =
    Vector(1.2 * p(0) + 0.3 * p(1) + 2.0, 0.8 * p(1) - 1.0, 1.1 * p(2) + 0.2 * p(0))

  private def close(actual: Vector[Double], expected: Vector[Double], tolerance: Double = 1e-11): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach((a, b) => assertEqualsDouble(a, b, tolerance))

  private val samples = NDArray.tabulate[Double](4, 5, 6, 3): (i, j, k, component) =>
    mapping(grid.voxelToWorld(Vector(i.toDouble, j.toDouble, k.toDouble)))(component)
  private val field = DenseVectorField(grid, samples, DenseVectorFieldKind.SourceCoordinates)

  test("grid adapter retains the canonical grid, affine and physical owner across pyramid levels"):
    assert(grid.canonical.grid.sameRuntimeOwnerAs(canonical))
    assert(grid.indexToFrame eq operator)
    val point = Vector(1.25, 2.5, 3.75)
    close(right(operator.inverse(grid.voxelToWorld(point))), point)
    val coarse = HalfFlowKernels.pyramidGrid(grid, 2)
    assert(coarse.canonical.grid.frame.sameRuntimeOwnerAs(source))
    close(coarse.voxelToWorld(Vector(1.0, 1.0, 1.0)), grid.voxelToWorld(Vector(2.0, 2.0, 2.0)))

  test("canonical dense export preserves component storage and oblique interpolation"):
    val map = right(field.toMap(target))
    assert(map.coordinates.data eq samples)
    assert(map.source.sameRuntimeOwnerAs(source))
    assert(map.target.sameRuntimeOwnerAs(target))
    for index <- Vector(Vector(1.25, 2.5, 3.75), Vector(0.0, 0.0, 0.0), Vector(3.0, 4.0, 5.0)) do
      val point = grid.voxelToWorld(index)
      close(right(MapExecution.coordinates(map, point)), mapping(point))

  test("identity extension preserves partial-stencil weighting and fully outside queries"):
    val map = right(field.toMap(target))
    val partial = grid.voxelToWorld(Vector(-0.5, 2.0, 3.0))
    val edgeValue = mapping(grid.voxelToWorld(Vector(0.0, 2.0, 3.0)))
    close(right(MapExecution.coordinates(map, partial)), partial.zip(edgeValue).map((p, e) => 0.5 * (p + e)))
    val outside = grid.voxelToWorld(Vector(-2.0, 2.0, 3.0))
    close(right(MapExecution.coordinates(map, outside)), outside)

  test("equal geometry and role labels cannot substitute for a physical frame owner"):
    val alien: PhysicalFrame[D3] = right(PhysicalFrame.named[D3]("source"))
    val alienGrid = GridSpec.fromGrid(right(Grid.forFrame[D3, PhysicalFrame[D3]](alien)(canonical.shape, operator)))
    assertNotEquals(grid, alienGrid)
    val from = RegistrationFrame[Unit](SpatialDomainId("same-role"), grid)
    val wrong = RegistrationFrame[Unit](from.domain, alienGrid)
    val to = RegistrationFrame[String](SpatialDomainId("target-role"), GridSpec.fromGrid(
      right(Grid.forFrame[D3, PhysicalFrame[D3]](target)(canonical.shape, operator))
    ))
    assert(DensePull.make(wrong, to, field).isLeft)
    val pull = right(DensePull.make(from, to, field))
    assert(pull.regrid(wrong, to).isLeft)
    val dense = right(pull.toMap)
    val map = SpatialMap.eraseFrameRefinements(dense)
    val alienPoint = right(Point.in(alien)(1.0, 2.0, 3.0))
    val alignment = right(PhysicalFrame.alignOwners[D3, alien.type, PhysicalFrame[D3]](alien, alien))
    assert(map(right(alignment.pointToRight(alienPoint))).isLeft)

  test("supplied affine rejects unrelated endpoints even with identical grid geometry"):
    val registrationFrame = RegistrationFrame[Unit](SpatialDomainId("image"), grid)
    val image = right(RegistrationImage.make(registrationFrame, NeuroVol.fromLinear(
      Array.fill(grid.nVoxels)(0.0), grid
    )))
    val alien = right(PhysicalFrame.named[D3]("source"))
    val unrelated = FramedAffine.between(alien, alien)(Affine.identity[D3])
    assert(SuppliedAffineInitialization.fromFixedToMoving(image, image, registrationFrame, unrelated).isLeft)

  test("velocity admission uses the canonical field with the original component storage"):
    val values = NDArray.tabulate[Double](4, 5, 6, 3)((_, _, _, c) => (c + 1).toDouble)
    val displacement = DenseVectorField(grid, values, DenseVectorFieldKind.Displacement)
    val frame = RegistrationFrame[Unit](SpatialDomainId("velocity"), grid)
    val velocity = right(Velocity.make(frame, displacement))
    assert(velocity.canonical.samples.data eq values)
    assert(velocity.canonical.frame.sameRuntimeOwnerAs(source))
