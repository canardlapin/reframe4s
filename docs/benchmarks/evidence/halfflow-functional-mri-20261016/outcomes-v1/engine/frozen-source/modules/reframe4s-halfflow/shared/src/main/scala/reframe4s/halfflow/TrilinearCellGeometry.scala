package reframe4s.halfflow

import reframe4s.halfflow.internal.*

/** Every cell is eligible, including the outermost cells. A cell is evaluated
  * only when all eight vertices are valid and all nine determinants are finite.
  * Nonpositive counts are cells, not vertices. This is sampled cell evidence,
  * not a certificate of positivity everywhere inside a trilinear cell.
  */
final case class TrilinearCellJacobianReport(
    minimum: Double,
    maximum: Double,
    evaluated: Int,
    eligible: Int,
    nonPositive: Int,
    nonFinite: Int
):
  def validFraction: Double = if eligible == 0 then 0.0 else evaluated.toDouble / eligible.toDouble

  def acceptable(floor: Double, minimumValidFraction: Double): Boolean =
    evaluated > 0 && minimum.isFinite && minimum >= floor && nonPositive == 0 && nonFinite == 0 &&
      validFraction >= minimumValidFraction

/** World-coordinate Jacobians of the actual trilinear coordinate map, sampled
  * at each complete cell's eight one-sided corners and center. Geometry/frame
  * ownership stays with the canonical grid; no finite-difference halo is used.
  */
object TrilinearCellGeometry:
  def evaluate(field: DenseVectorField, validity: FieldValidity = FieldValidity.All): TrilinearCellJacobianReport =
    require(field.kind == DenseVectorFieldKind.SourceCoordinates, "cell geometry requires source coordinates")
    val grid = field.grid
    validity.requireSize(grid.nVoxels)
    val nx = grid.extentX
    val ny = grid.extentY
    val nz = grid.extentZ
    val eligible = math.max(0, nx - 1) * math.max(0, ny - 1) * math.max(0, nz - 1)
    val basis = grid.affine
    val basisDeterminant =
      basis(0, 0) * (basis(1, 1) * basis(2, 2) - basis(1, 2) * basis(2, 1)) -
        basis(0, 1) * (basis(1, 0) * basis(2, 2) - basis(1, 2) * basis(2, 0)) +
        basis(0, 2) * (basis(1, 0) * basis(2, 1) - basis(1, 1) * basis(2, 0))
    require(basisDeterminant.isFinite && basisDeterminant != 0.0, "cell grid basis must be nonsingular")
    // Scratch is constant-size; the traversal allocates nothing per cell.
    val vertices = new Array[Double](24)
    val edges = new Array[Double](45)
    var evaluated = 0
    var nonPositive = 0
    var nonFinite = 0
    var minimum = Double.PositiveInfinity
    var maximum = Double.NegativeInfinity
    var z = 0
    while z < nz - 1 do
      var y = 0
      while y < ny - 1 do
        var x = 0
        while x < nx - 1 do
          var complete = true
          var finite = true
          var corner = 0
          while corner < 8 do
            val index = x + (corner & 1) + nx * (y + ((corner >> 1) & 1) + ny * (z + (corner >> 2)))
            complete &&= validity.contains(index)
            var component = 0
            while component < 3 do
              val value = field.linearComponent(index, component)
              vertices(3 * corner + component) = value
              finite &&= value.isFinite
              component += 1
            corner += 1
          if finite && complete then
            var edge = 0
            while edge < 4 do
              val a = edge & 1
              val b = edge >> 1
              var c = 0
              while c < 3 do
                edges(3 * edge + c) = vertices(3 * (1 + 2 * a + 4 * b) + c) - vertices(3 * (2 * a + 4 * b) + c)
                edges(12 + 3 * edge + c) = vertices(3 * (a + 2 + 4 * b) + c) - vertices(3 * (a + 4 * b) + c)
                edges(24 + 3 * edge + c) = vertices(3 * (a + 2 * b + 4) + c) - vertices(3 * (a + 2 * b) + c)
                c += 1
              edge += 1
            var c = 0
            while c < 9 do
              val group = c / 3
              val component = c % 3
              val start = 12 * group + component
              edges(36 + c) = 0.25 * (edges(start) + edges(start + 3) + edges(start + 6) + edges(start + 9))
              c += 1
            var cellMinimum = Double.PositiveInfinity
            var cellMaximum = Double.NegativeInfinity
            corner = 0
            while corner < 9 do
              val dx = corner & 1
              val dy = (corner >> 1) & 1
              val dz = corner >> 2
              val a = if corner == 8 then 36 else 3 * (dy + 2 * dz)
              val b = if corner == 8 then 39 else 12 + 3 * (dx + 2 * dz)
              val cc = if corner == 8 then 42 else 24 + 3 * (dx + 2 * dy)
              val determinant = triple(edges, a, b, cc) / basisDeterminant
              finite &&= determinant.isFinite
              cellMinimum = math.min(cellMinimum, determinant)
              cellMaximum = math.max(cellMaximum, determinant)
              corner += 1
            if finite then
              evaluated += 1
              minimum = math.min(minimum, cellMinimum)
              maximum = math.max(maximum, cellMaximum)
              if cellMinimum <= 0.0 then nonPositive += 1
          if !finite then nonFinite += 1
          x += 1
        y += 1
      z += 1
    TrilinearCellJacobianReport(
      if evaluated == 0 then Double.NaN else minimum,
      if evaluated == 0 then Double.NaN else maximum,
      evaluated, eligible, nonPositive, nonFinite
    )

  private inline def triple(v: Array[Double], a: Int, b: Int, c: Int): Double =
    v(a) * (v(b + 1) * v(c + 2) - v(b + 2) * v(c + 1)) -
      v(a + 1) * (v(b) * v(c + 2) - v(b + 2) * v(c)) +
      v(a + 2) * (v(b) * v(c + 1) - v(b + 1) * v(c))
