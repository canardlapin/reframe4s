package reframe4s.field

import ravel.AnyRank
import reframe4s.core.CellSamplingRule
import reframe4s.core.EvidenceError
import reframe4s.core.TopologyCertificate
import reframe4s.core.TopologyDiagnostics
import reframe4s.core.TopologyScope
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GridKey

sealed trait TopologyAssessmentError derives CanEqual:
  def message: String

object TopologyAssessmentError:
  final case class WrongGrid(expected: GridKey, actual: GridKey)
      extends TopologyAssessmentError:
    val message: String =
      s"topology scope grid ${actual.id.value} does not match map grid ${expected.id.value}"

  final case class NoCompleteCells(region: Vector[Int])
      extends TopologyAssessmentError:
    val message: String =
      s"topology region ${region.mkString("x")} contains no complete cells"

  final case class Field(error: FieldError) extends TopologyAssessmentError:
    val message: String = error.message

  final case class Evidence(error: EvidenceError)
      extends TopologyAssessmentError:
    val message: String = error.message

  final case class NonFiniteJacobian(
      cell: Vector[Int],
      localSample: Vector[Double],
      value: Double
  ) extends TopologyAssessmentError:
    val message: String =
      s"non-finite cell Jacobian $value at ${cell.mkString(",")} / " +
        localSample.mkString("(", ",", ")")

object TopologyAssessor:
  def diagnose[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      map: DenseMap[From, To, D, R],
      scope: TopologyScope[From, D]
  )(using dimension: Dimension[D])
      : Either[TopologyAssessmentError, TopologyDiagnostics] =
    map.grid.persistentKey match
      case None =>
        Left(
          TopologyAssessmentError.Evidence(
            EvidenceError.PersistentGridRequired
          )
        )
      case Some(mapKey) =>
        if mapKey != scope.gridKey then
          Left(TopologyAssessmentError.WrongGrid(mapKey, scope.gridKey))
        else
          val lower = scope.region.lowerInclusive
          val upperCellExclusive =
            scope.region.upperExclusive.map(_ - 1)
          if lower.indices.exists(axis =>
              upperCellExclusive(axis) <= lower(axis)
            )
          then
            Left(
              TopologyAssessmentError.NoCompleteCells(
                scope.region.upperExclusive
                  .zip(lower)
                  .map((upper, start) => upper - start)
              )
            )
          else
            evaluateCells(map, scope, lower, upperCellExclusive)

  def certify[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      map: DenseMap[From, To, D, R],
      scope: TopologyScope[From, D]
  )(using Dimension[D])
      : Either[TopologyAssessmentError, TopologyCertificate[From, D]] =
    for
      diagnostics <- diagnose(map, scope)
      certificate <- TopologyCertificate
        .fromReportedDiagnostics(scope, diagnostics)
        .left
        .map(TopologyAssessmentError.Evidence.apply)
    yield certificate

  private def evaluateCells[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      map: DenseMap[From, To, D, R],
      scope: TopologyScope[From, D],
      lower: Vector[Int],
      upperCellExclusive: Vector[Int]
  )(using dimension: Dimension[D])
      : Either[TopologyAssessmentError, TopologyDiagnostics] =
    val positions = samplePositions(scope.samplingRule, dimension.rank)
    val cell = lower.toArray
    var minimum = Double.PositiveInfinity
    var maximum = Double.NegativeInfinity
    var foldedCells = 0L
    var sampledCells = 0L
    var failure = Option.empty[TopologyAssessmentError]
    var done = false
    while !done && failure.isEmpty do
      var folded = false
      var sample = 0
      while sample < positions.length && failure.isEmpty do
        determinant(map, cell.toVector, positions(sample)) match
          case Left(error) =>
            failure = Some(error)
          case Right(value) =>
            if !value.isFinite then
              failure = Some(
                TopologyAssessmentError.NonFiniteJacobian(
                  cell.toVector,
                  positions(sample),
                  value
                )
              )
            else
              minimum = math.min(minimum, value)
              maximum = math.max(maximum, value)
              folded = folded || value <= 0.0
        sample += 1
      if failure.isEmpty then
        sampledCells += 1L
        if folded then foldedCells += 1L
        done = advance(cell, lower, upperCellExclusive)
    failure match
      case Some(error) => Left(error)
      case None =>
        TopologyDiagnostics
          .create(
            minimum,
            maximum,
            foldedCells,
            coveredDomainFraction = 1.0,
            sampledCellCount = sampledCells
          )
          .left
          .map(TopologyAssessmentError.Evidence.apply)

  private def determinant[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim,
      R <: AnyRank
  ](
      map: DenseMap[From, To, D, R],
      cell: Vector[Int],
      local: Vector[Double]
  )(using dimension: Dimension[D])
      : Either[TopologyAssessmentError, Double] =
    val rank = dimension.rank
    val derivative = Array.fill(rank * rank)(0.0)
    var output = 0
    var failure = Option.empty[FieldError]
    while output < rank && failure.isEmpty do
      var axis = 0
      while axis < rank && failure.isEmpty do
        val otherCorners = 1 << (rank - 1)
        var other = 0
        var sum = 0.0
        while other < otherCorners && failure.isEmpty do
          val lowVertex = cell.toArray
          val highVertex = cell.toArray
          highVertex(axis) += 1
          var weight = 1.0
          var bit = 0
          var coordinate = 0
          while coordinate < rank do
            if coordinate != axis then
              val upper = ((other >> bit) & 1) == 1
              if upper then
                lowVertex(coordinate) += 1
                highVertex(coordinate) += 1
                weight *= local(coordinate)
              else weight *= 1.0 - local(coordinate)
              bit += 1
            coordinate += 1
          val low =
            map.coordinates.valueAt(lowVertex.toVector, Vector(output))
          val high =
            map.coordinates.valueAt(highVertex.toVector, Vector(output))
          (low, high) match
            case (Right(lowValue), Right(highValue)) =>
              sum += weight * (highValue - lowValue)
            case (Left(error), _) =>
              failure = Some(FieldError.Image(error))
            case (_, Left(error)) =>
              failure = Some(FieldError.Image(error))
          other += 1
        derivative(output * rank + axis) = sum
        axis += 1
      output += 1
    failure match
      case Some(error) => Left(TopologyAssessmentError.Field(error))
      case None =>
        val indexDeterminant = determinantOf(derivative, rank)
        val basis = Array.tabulate(rank * rank) { flat =>
          val row = flat / rank
          val column = flat % rank
          map.grid.indexToFrame.matrix(row, column)
        }
        Right(indexDeterminant / determinantOf(basis, rank))

  private def determinantOf(values: Array[Double], rank: Int): Double =
    if rank == 2 then
      values(0) * values(3) - values(1) * values(2)
    else
      values(0) * (values(4) * values(8) - values(5) * values(7)) -
        values(1) * (values(3) * values(8) - values(5) * values(6)) +
        values(2) * (values(3) * values(7) - values(4) * values(6))

  private def samplePositions(
      rule: CellSamplingRule,
      rank: Int
  ): Vector[Vector[Double]] =
    rule match
      case CellSamplingRule.CellCorners =>
        corners(rank)
      case CellSamplingRule.CellCornersAndCenter =>
        corners(rank) :+ Vector.fill(rank)(0.5)
      case subdivision: CellSamplingRule.Subdivision =>
        val denominator = subdivision.levels.toDouble
        cartesian(
          Vector.fill(rank)(
            Vector.tabulate(subdivision.levels + 1)(_ / denominator)
          )
        )

  private def corners(rank: Int): Vector[Vector[Double]] =
    Vector.tabulate(1 << rank) { corner =>
      Vector.tabulate(rank) { axis =>
        if ((corner >> axis) & 1) == 1 then 1.0 else 0.0
      }
    }

  private def cartesian(
      axes: Vector[Vector[Double]]
  ): Vector[Vector[Double]] =
    axes.foldLeft(Vector(Vector.empty[Double])) { (prefixes, axis) =>
      for
        prefix <- prefixes
        value <- axis
      yield prefix :+ value
    }

  private def advance(
      cell: Array[Int],
      lower: Vector[Int],
      upperExclusive: Vector[Int]
  ): Boolean =
    var axis = cell.length - 1
    while axis >= 0 do
      cell(axis) += 1
      if cell(axis) < upperExclusive(axis) then return false
      cell(axis) = lower(axis)
      axis -= 1
    true
