package reframe4s.halfflow.internal

import ravel.AnyRank
import ravel.NDArray as RavelArray
import ravel.Shape

/** Pure helpers for homogeneous affine transforms.
  *
  * These mirror the pure affine utilities in neuroim2's R core, while keeping
  * the Scala API typed around immutable `DMat` and `Vector` values.
  */
enum Affine3DError:
  case InvalidShape(rows: Int, cols: Int)
  case NonFiniteValue(index: Int)
  case InvalidHomogeneousRow(actual: Vector[Double])
  case Singular(reason: String)

  def message: String =
    this match
      case InvalidShape(rows, cols) =>
        s"Affine3D must be a 4x4 matrix; got ${rows}x${cols}"
      case NonFiniteValue(index) =>
        s"Affine3D value at linear index $index is not finite"
      case InvalidHomogeneousRow(actual) =>
        s"Affine3D bottom row must be [0, 0, 0, 1]; got $actual"
      case Singular(reason) =>
        s"Affine3D must be invertible: $reason"

final case class Affine3D private (matrix: DMat, inverse: DMat):
  def voxelToWorld(voxel: VoxelPoint): WorldPoint =
    WorldPoint.unsafeFromVector(Affine.applyAffine(matrix, voxel.toVector), "world point")

  def voxelsToWorld(voxels: Vector[VoxelPoint]): Vector[WorldPoint] =
    voxels.map(voxelToWorld)

  def worldToVoxel(world: WorldPoint): VoxelPoint =
    VoxelPoint.unsafeFromVector(Affine.applyAffine(inverse, world.toVector), "voxel point")

  def worldsToVoxel(worlds: Vector[WorldPoint]): Vector[VoxelPoint] =
    worlds.map(worldToVoxel)

  def inverseAffine: Affine3D =
    Affine3D.unsafe(inverse, matrix)

  def linearPart: DMat =
    DMat.fromRows(
      Vector.tabulate(3)(r => Vector.tabulate(3)(c => matrix(r, c)))
    )

  def voxelSizes: Vector[Double] =
    Affine.voxelSizes(matrix)

  def origin: WorldPoint =
    WorldPoint(matrix(0, 3), matrix(1, 3), matrix(2, 3))

object Affine3D:
  private val HomogeneousTol = 1e-12

  def make(matrix: DMat): Either[Affine3DError, Affine3D] =
    validateShape(matrix).flatMap { _ =>
      validateFinite(matrix).flatMap { _ =>
        validateHomogeneousRow(matrix).flatMap { _ =>
          DMat.invert(matrix) match
            case Left(reason) => Left(Affine3DError.Singular(reason))
            case Right(inverse) => Right(new Affine3D(matrix, inverse))
        }
      }
    }

  def apply(matrix: DMat): Affine3D =
    make(matrix).fold(err => throw new IllegalArgumentException(err.message), affine => affine)

  def unsafe(matrix: DMat, inverse: DMat): Affine3D =
    new Affine3D(matrix, inverse)

  def identity: Affine3D =
    Affine3D(DMat.eye(4))

  def fromRows(rows: Vector[Vector[Double]]): Either[Affine3DError, Affine3D] =
    make(DMat.fromRows(rows))

  private def validateShape(matrix: DMat): Either[Affine3DError, Unit] =
    if matrix.rows == 4 && matrix.cols == 4 then Right(())
    else Left(Affine3DError.InvalidShape(matrix.rows, matrix.cols))

  private def validateFinite(matrix: DMat): Either[Affine3DError, Unit] =
    var i = 0
    var error = Option.empty[Affine3DError]
    while i < matrix.data.length && error.isEmpty do
      if !matrix.data(i).isFinite then error = Some(Affine3DError.NonFiniteValue(i))
      i += 1

    error match
      case Some(err) => Left(err)
      case None => Right(())

  private def validateHomogeneousRow(matrix: DMat): Either[Affine3DError, Unit] =
    val actual = Vector(matrix(3, 0), matrix(3, 1), matrix(3, 2), matrix(3, 3))
    val ok =
      math.abs(actual(0)) <= HomogeneousTol &&
        math.abs(actual(1)) <= HomogeneousTol &&
        math.abs(actual(2)) <= HomogeneousTol &&
        math.abs(actual(3) - 1.0) <= HomogeneousTol

    if ok then Right(()) else Left(Affine3DError.InvalidHomogeneousRow(actual))

object Affine:

  final case class MatVec(matrix: DMat, vector: Vector[Double])

  def applyAffine(affine: DMat, point: Vector[Double]): Vector[Double] =
    val ndIn = affine.cols - 1
    val ndOut = affine.rows - 1
    require(ndIn > 0 && ndOut > 0, "affine must be at least 2x2")
    require(point.length == ndIn, "point length must match affine input dimension")

    Vector.tabulate(ndOut) { r =>
      var c = 0
      var sum = affine(r, affine.cols - 1)
      while c < ndIn do
        sum += affine(r, c) * point(c)
        c += 1
      sum
    }

  def applyAffines(affine: DMat, points: Vector[Vector[Double]]): Vector[Vector[Double]] =
    points.map(point => applyAffine(affine, point))

  def applyAffine(
      affine: DMat,
      points: RavelArray[Double, ? <: AnyRank]
  ): RavelArray[Double, AnyRank] =
    require(points.rank >= 1, "points must have at least one dimension")
    val ndIn = affine.cols - 1
    val ndOut = affine.rows - 1
    require(
      points.shape(points.rank - 1) == ndIn,
      "last points dimension must match affine input dimension"
    )

    val leadingShape =
      Vector.tabulate(points.rank - 1)(points.shape.apply)
    val nPoints = if leadingShape.isEmpty then 1 else leadingShape.product
    val outShape = leadingShape :+ ndOut
    val shape =
      Shape
        .from(outShape)
        .fold(error => throw new IllegalArgumentException(error.getMessage), identity)
    RavelArray.build[Double, AnyRank](shape): out =>
      var point = 0
      while point < nPoints do
        val leading = rowMajorCoordinates(leadingShape, point)
        var row = 0
        while row < ndOut do
          var column = 0
          var sum = affine(row, affine.cols - 1)
          while column < ndIn do
            val indices =
              IArray.unsafeFromArray((leading :+ column).toArray)
            sum += affine(row, column) * points.at(indices)
            column += 1
          out.writeLinear(point * ndOut + row, sum)
          row += 1
        point += 1

  private def rowMajorCoordinates(shape: Vector[Int], linear: Int): Vector[Int] =
    val coordinates = Array.ofDim[Int](shape.length)
    var remaining = linear
    var axis = shape.length - 1
    while axis >= 0 do
      coordinates(axis) = remaining % shape(axis)
      remaining /= shape(axis)
      axis -= 1
    coordinates.toVector

  def toMatVec(transform: DMat): MatVec =
    require(transform.rows >= 2 && transform.cols >= 2, "transform must be at least 2x2")
    val ndOut = transform.rows - 1
    val ndIn = transform.cols - 1
    val matrix =
      DMat.fromRows(
        Vector.tabulate(ndOut)(r => Vector.tabulate(ndIn)(c => transform(r, c)))
      )
    val vector = Vector.tabulate(ndOut)(r => transform(r, transform.cols - 1))
    MatVec(matrix, vector)

  def fromMatVec(matrix: DMat, vector: Vector[Double] = Vector.empty): DMat =
    val nOut = matrix.rows
    val nIn = matrix.cols
    val translation =
      if vector.isEmpty then Vector.fill(nOut)(0.0)
      else
        require(vector.length == nOut, "translation vector length must equal matrix rows")
        require(vector.forall(_.isFinite), "translation vector must be finite")
        vector

    DMat.fromRows(
      Vector.tabulate(nOut + 1) { r =>
        Vector.tabulate(nIn + 1) { c =>
          if r < nOut && c < nIn then matrix(r, c)
          else if r < nOut && c == nIn then translation(r)
          else if r == nOut && c == nIn then 1.0
          else 0.0
        }
      }
    )

  def appendDiag(affine: DMat, steps: Vector[Double], starts: Vector[Double] = Vector.empty): DMat =
    require(affine.rows >= 2 && affine.cols >= 2, "affine must be at least 2x2")
    require(steps.nonEmpty && steps.forall(_.isFinite), "steps must contain finite values")
    val offsets =
      if starts.isEmpty then Vector.fill(steps.length)(0.0)
      else
        require(starts.length == steps.length, "starts must be empty or match steps length")
        require(starts.forall(_.isFinite), "starts must be finite")
        starts

    val oldOut = affine.rows - 1
    val oldIn = affine.cols - 1
    val nSteps = steps.length
    val newRows = oldOut + nSteps + 1
    val newCols = oldIn + nSteps + 1

    DMat.fromRows(
      Vector.tabulate(newRows) { r =>
        Vector.tabulate(newCols) { c =>
          if r < oldOut && c < oldIn then affine(r, c)
          else if r < oldOut && c == newCols - 1 then affine(r, affine.cols - 1)
          else if r >= oldOut && r < oldOut + nSteps && c == oldIn + (r - oldOut) then steps(r - oldOut)
          else if r >= oldOut && r < oldOut + nSteps && c == newCols - 1 then offsets(r - oldOut)
          else if r == newRows - 1 && c == newCols - 1 then 1.0
          else 0.0
        }
      }
    )

  def dotReduce(first: DMat, rest: DMat*): DMat =
    if rest.isEmpty then first
    else multiply(first, rest.reduceRight((m, acc) => multiply(m, acc)))

  def voxelSizes(affine: DMat): Vector[Double] =
    require(affine.rows >= 2 && affine.cols >= 2, "affine must be at least 2x2")
    val ndIn = affine.cols - 1
    val ndOut = affine.rows - 1
    Vector.tabulate(ndIn) { c =>
      var r = 0
      var sum = 0.0
      while r < ndOut do
        val v = affine(r, c)
        sum += v * v
        r += 1
      math.sqrt(sum)
    }

  def obliquity(affine: DMat): Vector[Double] =
    val sizes = voxelSizes(affine)
    require(sizes.forall(_ != 0.0), "cannot compute obliquity for zero-length voxel axes")
    val ndOut = affine.rows - 1
    val ndIn = affine.cols - 1

    Vector.tabulate(ndOut) { r =>
      var c = 0
      var best = 0.0
      while c < ndIn do
        val cosine = math.abs(affine(r, c) / sizes(c))
        if cosine > best then best = cosine
        c += 1
      math.acos(clamp(best, -1.0, 1.0))
    }

  def rescaleAffine(
    affine: DMat,
    shape: Vector[Int],
    zooms: Vector[Double],
    newShape: Option[Vector[Int]] = None
  ): DMat =
    require(affine.rows == affine.cols, "rescaleAffine requires a square homogeneous affine")
    val ndim = affine.rows - 1
    val outShape = newShape.getOrElse(shape)
    require(shape.length == ndim && zooms.length == ndim && outShape.length == ndim,
      "shape, zooms, and newShape must match affine spatial dimensionality")
    require(shape.forall(_ > 0) && outShape.forall(_ > 0), "shape and newShape must be positive")
    require(zooms.forall(z => z.isFinite && z > 0.0), "zooms must be positive and finite")

    val sizes = voxelSizes(affine)
    require(sizes.forall(_ != 0.0), "cannot rescale affine with zero voxel size in linear block")

    val rzsOut =
      DMat.fromRows(
        Vector.tabulate(ndim) { r =>
          Vector.tabulate(ndim) { c =>
            affine(r, c) * (zooms(c) / sizes(c))
          }
        }
      )

    val centerIn = shape.map(n => math.floor((n - 1).toDouble / 2.0))
    val centerOut = outShape.map(n => math.floor((n - 1).toDouble / 2.0))
    val centroid = applyAffine(affine, centerIn)
    val shiftedCenter = multiply(rzsOut, centerOut)
    val translation = Vector.tabulate(ndim)(i => centroid(i) - shiftedCenter(i))

    fromMatVec(rzsOut, translation)

  def multiply(left: DMat, right: DMat): DMat =
    require(left.cols == right.rows, "matrix dimensions are not conformable")
    DMat.fromRows(
      Vector.tabulate(left.rows) { r =>
        Vector.tabulate(right.cols) { c =>
          var k = 0
          var sum = 0.0
          while k < left.cols do
            sum += left(r, k) * right(k, c)
            k += 1
          sum
        }
      }
    )

  def multiply(matrix: DMat, vector: Vector[Double]): Vector[Double] =
    require(matrix.cols == vector.length, "matrix/vector dimensions are not conformable")
    Vector.tabulate(matrix.rows) { r =>
      var c = 0
      var sum = 0.0
      while c < matrix.cols do
        sum += matrix(r, c) * vector(c)
        c += 1
      sum
    }

  private def clamp(x: Double, low: Double, high: Double): Double =
    math.max(low, math.min(high, x))
