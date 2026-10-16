package reframe4s.halfflow

import gale.linalg.DMat
import reframe4s.halfflow.internal.*

class TrilinearCellGeometrySuite extends munit.FunSuite:
  test("physical affine determinant is exact on oblique anisotropic and left-handed grids"):
    for sign <- Vector(1.0, -1.0) do
      val grid = GridSpec(Vector(6, 5, 4), DMat.dense(4, 4, Vector(
        sign * 1.2, -0.4, 0.1, -3.0,
        sign * 0.3, 1.5, 0.2, 2.0,
        0.0, 0.1, 2.0, -1.0,
        0.0, 0.0, 0.0, 1.0
      )))
      val coordinates = HalfFlowKernels.identity(grid).field.copyLegacyPlanar
      val n = grid.nVoxels
      for i <- 0 until n do
        val x = coordinates(i)
        val y = coordinates(i + n)
        val z = coordinates(i + 2 * n)
        coordinates(i) = 1.1 * x + 0.15 * y - 0.02 * z + 3.0
        coordinates(i + n) = 0.9 * y + 0.04 * z - 2.0
        coordinates(i + 2 * n) = 1.03 * z + 1.0
      val field = DenseVectorField.fromLegacyPlanar(grid, coordinates, DenseVectorFieldKind.SourceCoordinates)
      val report = TrilinearCellGeometry.evaluate(field)
      assertEquals(report.eligible, 60)
      assertEquals(report.evaluated, 60)
      assertEquals(report.nonPositive, 0)
      assertEquals(report.nonFinite, 0)
      assertEqualsDouble(report.minimum, 1.1 * 0.9 * 1.03, 1e-12)
      assertEqualsDouble(report.maximum, 1.1 * 0.9 * 1.03, 1e-12)

  test("an outer-cell fold missed by positive voxel differences is detected"):
    val grid = GridSpec.identity(Vector(7, 7, 7))
    val field = boundaryField(grid, 5.5)
    val old = JacobianReduction()
    HalfFlowKernels.jacobianDeterminantsReduceInto(field, new Array[Double](grid.nVoxels),
      new Array[Boolean](grid.nVoxels), DenseFieldSampler(grid), FieldValidity.All, old)
    assertEquals(old.nonPositive, 0)
    assert(old.minimumOrNaN > 0.0)
    val report = TrilinearCellGeometry.evaluate(field)
    assertEquals(report.evaluated, 216)
    assertEquals(report.eligible, 216)
    assertEquals(report.nonPositive, 36)
    assertEqualsDouble(report.minimum, -0.5, 1e-12)

  test("one invalid corner removes only its cell and remains in the denominator"):
    val grid = GridSpec.identity(Vector(3, 3, 3))
    val valid = Array.fill(grid.nVoxels)(true)
    valid(0) = false
    val report = TrilinearCellGeometry.evaluate(HalfFlowKernels.identity(grid).field, FieldValidity.copyMask(valid))
    assertEquals(report.eligible, 8)
    assertEquals(report.evaluated, 7)
    assertEqualsDouble(report.validFraction, 0.875, 0.0)
    assert(!report.acceptable(0.05, 0.95))

  test("nonfinite boundary data cannot be hidden by invalid support"):
    val grid = GridSpec.identity(Vector(3, 3, 3))
    val coordinates = HalfFlowKernels.identity(grid).field.copyLegacyPlanar
    coordinates(0) = Double.NaN
    val valid = Array.fill(grid.nVoxels)(true)
    valid(0) = false
    val report = TrilinearCellGeometry.evaluate(
      DenseVectorField.fromLegacyPlanar(grid, coordinates, DenseVectorFieldKind.SourceCoordinates),
      FieldValidity.copyMask(valid))
    assertEquals(report.nonFinite, 1)
    assert(!report.acceptable(0.05, 0.5))

  test("a grid without complete cells cannot pass"):
    val grid = GridSpec.identity(Vector(1, 3, 3))
    val report = TrilinearCellGeometry.evaluate(HalfFlowKernels.identity(grid).field)
    assertEquals(report.eligible, 0)
    assertEquals(report.evaluated, 0)
    assert(!report.acceptable(0.05, 0.95))

  private def boundaryField(grid: GridSpec, last: Double): DenseVectorField =
    val coordinates = HalfFlowKernels.identity(grid).field.copyLegacyPlanar
    val xs = Vector(0.0, 1.0, 2.0, 3.0, 4.0, 6.0, last)
    for i <- 0 until grid.nVoxels do coordinates(i) = xs(i % 7)
    DenseVectorField.fromLegacyPlanar(grid, coordinates, DenseVectorFieldKind.SourceCoordinates)
