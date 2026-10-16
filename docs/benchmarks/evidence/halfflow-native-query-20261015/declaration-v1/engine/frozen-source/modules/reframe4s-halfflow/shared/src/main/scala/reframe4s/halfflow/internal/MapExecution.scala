package reframe4s.halfflow.internal

import image4s.geometry.{D3, Frame, Point}
import reframe4s.core.{MapError, SpatialMap}
import reframe4s.halfflow.RegistrationError
import ravel.{NDArray, Rank}

/** Lift finite physical coordinates from HalfFlow's execution buffers through
  * a canonical map. Runtime owner checks remain with the provider.
  */
object MapExecution:
  def coordinates[From <: Frame[D3], To <: Frame[D3]](
      map: SpatialMap[From, To, D3],
      values: Vector[Double]
  ): Either[MapError, Vector[Double]] =
    val source = map.source
    for
      point <- Point.fromVector(source, values).left.map(MapError.Geometry.apply)
      alignment <- Frame.alignOwners[D3, source.type, From](source, source)
        .left.map(MapError.Geometry.apply)
      rebound <- alignment.pointToRight(point).left.map(MapError.Geometry.apply)
      result <- map(rebound)
    yield result.coordinates

  def prepare(grid: GridSpec, points: Vector[Vector[Double]])
      : Either[RegistrationError, QueryBatch] =
    if points.exists(point => point.length != 3 || !point.forall(_.isFinite)) then
      Left(RegistrationError.InvalidField("query batch query coordinates"))
    else Right(new QueryBatch(grid, points))

  final class QueryBatch(grid: GridSpec, points: Vector[Vector[Double]]):
    def sample(values: NDArray[Double, Rank[4]])
        : Either[RegistrationError, Vector[Vector[Double]]] =
      if values.shape != ravel.Shape(grid.shape(0), grid.shape(1), grid.shape(2), 3) then
        Left(RegistrationError.InvalidField("query batch component shape"))
      else
        val field = DenseVectorField(grid, values, DenseVectorFieldKind.SourceCoordinates)
        field.toMap(grid.canonical.grid.frame)
          .left.map(error => RegistrationError.MorphismExportFailed("query batch", error.message))
          .flatMap: map =>
            points.foldLeft[Either[RegistrationError, Vector[Vector[Double]]]](Right(Vector.empty)):
              (acc, point) =>
                for
                  prior <- acc
                  value <- MapExecution.coordinates(map, point)
                    .left.map(error => RegistrationError.MorphismExportFailed("query batch", error.message))
                yield prior :+ value
