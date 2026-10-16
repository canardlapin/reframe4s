package reframe4s.core

import gale.linalg.DMat
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Point

final class Jet1[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim
] private (
    val value: Point[To, D],
    val differential: DMat
)

object Jet1:
  def create[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      value: Point[To, D],
      differential: DMat
  )(using dimension: Dimension[D]): Either[MapError, Jet1[From, To, D]] =
    if differential.rows != dimension.rank ||
      differential.cols != dimension.rank
    then
      Left(
        MapError.InvalidDifferentialShape(
          dimension.rank,
          differential.rows,
          differential.cols
        )
      )
    else
      firstNonFinite(differential) match
        case Some((row, column, value)) =>
          Left(MapError.NonFiniteDifferential(row, column, value))
        case None =>
          Right(new Jet1(value, differential))

  private def firstNonFinite(
      matrix: DMat
  ): Option[(Int, Int, Double)] =
    var row = 0
    var result = Option.empty[(Int, Int, Double)]
    while row < matrix.rows && result.isEmpty do
      var column = 0
      while column < matrix.cols && result.isEmpty do
        val value = matrix(row, column)
        if !value.isFinite then result = Some((row, column, value))
        column += 1
      row += 1
    result
