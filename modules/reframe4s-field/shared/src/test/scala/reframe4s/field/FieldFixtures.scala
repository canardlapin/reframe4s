package reframe4s.field

import gale.linalg.DMat
import image4s.Axis
import image4s.AxisKind
import image4s.Continuous
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Shape
import reframe4s.core.AffineMap
import reframe4s.core.Jet1
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import image4s.geometry.Affine
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.FrameId
import image4s.geometry.Grid
import image4s.geometry.GridId
import image4s.geometry.LatticeIndex
import image4s.geometry.Point
import reframe4s.resample.Interpolation

/** Shared construction helpers for field suites. Frames are widened to
  * `Frame[D]`; runtime owner checks still apply.
  */
object FieldFixtures:
  def frame[D <: Dim](label: String)(using Dimension[D]): Frame[D] =
    right(Frame.named[D](label))

  def persistentFrame[D <: Dim](label: String)(using Dimension[D]): Frame[D] =
    right(
      FrameId
        .parse(s"$label-frame")
        .flatMap(id => Frame.persistentNamed[D](id, label))
    )

  def grid[D <: Dim](
      frame: Frame[D],
      shape: Vector[Int],
      affine: Affine[D]
  )(using Dimension[D]): Grid[Frame[D], D] =
    right(Grid.forFrame[D, Frame[D]](frame)(shape, affine))

  def persistentGrid[D <: Dim](
      frame: Frame[D],
      label: String,
      shape: Vector[Int],
      affine: Affine[D]
  )(using Dimension[D]): Grid[Frame[D], D] =
    right(
      GridId
        .parse(s"$label-grid")
        .flatMap(id =>
          Grid.createPersistent[D, Frame[D]](id, frame)(shape, affine)
        )
    )

  def affine[D <: Dim](
      origin: Vector[Double],
      spacing: Vector[Double]
  )(using dimension: Dimension[D]): Affine[D] =
    val directions =
      Vector.tabulate(dimension.rank * dimension.rank)(flat =>
        if flat / dimension.rank == flat % dimension.rank then 1.0 else 0.0
      )
    right(Affine.fromOriginSpacingDirection[D](origin, spacing, directions))

  def rowMajor[D <: Dim](values: Double*)(using Dimension[D]): Affine[D] =
    right(Affine.fromRowMajor[D](values))

  /** World coordinates of every lattice point in canonical order. */
  def latticePoints[D <: Dim](
      grid: Grid[Frame[D], D]
  )(using Dimension[D]): Vector[(Vector[Int], Point[Frame[D], D])] =
    indices(grid.shape).map(index =>
      index -> right(
        LatticeIndex.fromVector[D](index).flatMap(grid.pointAt)
      )
    )

  def indices(shape: Vector[Int]): Vector[Vector[Int]] =
    shape.foldLeft(Vector(Vector.empty[Int])) { (prefixes, extent) =>
      for
        prefix <- prefixes
        value <- 0 until extent
      yield prefix :+ value
    }

  def denseMap[D <: Dim](
      grid: Grid[Frame[D], D],
      target: Frame[D],
      interpolation: Interpolation[Continuous] = Interpolation.Linear,
      boundary: CoordinateBoundaryPolicy = CoordinateBoundaryPolicy.Reject
  )(
      f: Vector[Double] => Vector[Double]
  )(using dimension: Dimension[D]): DenseMap[Frame[D], Frame[D], D, AnyRank] =
    val values =
      latticePoints(grid).flatMap((_, point) => f(point.coordinates))
    val shape = right(Shape.from(grid.shape :+ dimension.rank))
    val data = NDArray.fromSeq[Double, AnyRank](shape, values)
    val image =
      right(
        for
          axis <- Axis.create("component", dimension.rank, AxisKind.Direction)
          axes <- NonSpatialAxes.from(Vector(axis))
          image <- Sampled.continuous(grid, axes, data)
        yield image
      )
    right(DenseMap.fromCoordinates(image, target, interpolation, boundary))

  def affineMap[D <: Dim](
      source: Frame[D],
      target: Frame[D],
      operator: Affine[D]
  )(using Dimension[D]): AffineMap[Frame[D], Frame[D], D] =
    new TestAffine(source, target, operator)

  def mapped[D <: Dim](
      map: SpatialMap[Frame[D], Frame[D], D],
      point: Point[Frame[D], D]
  ): Vector[Double] =
    right(map(point)).coordinates

  def right[E, A](value: Either[E, A]): A =
    value.fold(
      error =>
        throw new AssertionError(
          error match
            case described: FieldError             => described.message
            case described: MapError               => described.message
            case described: CompositionError       => described.message
            case described: image4s.ImageError     => described.message
            case described: image4s.geometry.GeometryError =>
              described.message
            case other => other.toString
        ),
      identity
    )

  private final class TestAffine[D <: Dim](
      val source: Frame[D],
      val target: Frame[D],
      val operator: Affine[D]
  )(using dimension: Dimension[D])
      extends AffineMap[Frame[D], Frame[D], D]:
    def apply(point: Point[Frame[D], D]): Either[MapError, Point[Frame[D], D]] =
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        coordinates <- operator(point.coordinates)
          .left
          .map(MapError.Geometry.apply)
        result <- Point
          .fromVector(target, coordinates)
          .flatMap(value =>
            Frame
              .alignOwners[D, target.type, Frame[D]](target, target)
              .flatMap(_.pointToRight(value))
          )
          .left
          .map(MapError.Geometry.apply)
      yield result

    def jet1At(
        point: Point[Frame[D], D]
    ): Either[MapError, Jet1[Frame[D], Frame[D], D]] =
      apply(point).flatMap(value =>
        Jet1.create[Frame[D], Frame[D], D](
          value,
          DMat.dense(
            dimension.rank,
            dimension.rank,
            Vector.tabulate(dimension.rank * dimension.rank)(flat =>
              operator.matrix(flat / dimension.rank, flat % dimension.rank)
            )
          )
        )
      )

    def inverse: AffineMap[Frame[D], Frame[D], D] =
      new TestAffine(target, source, operator.inverse)
