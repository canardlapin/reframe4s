package reframe4s.core

import image4s.geometry.Frame
import image4s.geometry.FrameKey
import image4s.geometry.GeometryError

/** Safe diagnostic identity for either a persistent or ephemeral frame owner.
  *
  * Presentation metadata is deliberately excluded. This descriptor is never
  * used as identity evidence; it only explains a failed checked boundary.
  */
final case class FrameOwnerDescriptor(
    persistentKey: Option[FrameKey]
) derives CanEqual

object FrameOwnerDescriptor:
  def of(frame: Frame[?]): FrameOwnerDescriptor =
    FrameOwnerDescriptor(frame.persistentKey)

sealed trait MapError derives CanEqual:
  def message: String

object MapError:
  final case class SourceFrameMismatch(
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends MapError:
    val message: String =
      s"map source frame does not align: expected $expected, got $actual"

  final case class SourceFrameOwnerMismatch(
      identity: FrameOwnerDescriptor
  ) extends MapError:
    val message: String =
      s"map source frame $identity has a distinct live runtime owner"

  final case class ResultFrameMismatch(
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends MapError:
    val message: String =
      s"map result frame does not align: expected $expected, got $actual"

  final case class ResultFrameOwnerMismatch(
      identity: FrameOwnerDescriptor
  ) extends MapError:
    val message: String =
      s"map result frame $identity has a distinct live runtime owner"

  final case class InvalidDifferentialShape(
      expected: Int,
      rows: Int,
      columns: Int
  ) extends MapError:
    val message: String =
      s"expected a ${expected}x$expected spatial differential, got ${rows}x$columns"

  final case class NonFiniteDifferential(
      row: Int,
      column: Int,
      value: Double
  ) extends MapError:
    val message: String =
      s"differential ($row,$column) must be finite, got $value"

  final case class InvalidFiniteDifferenceStep(value: Double)
      extends MapError:
    val message: String =
      s"finite-difference step must be finite and positive, got $value"

  final case class OutsideDomain(coordinates: Vector[Double])
      extends MapError:
    val message: String =
      s"map is undefined at ${coordinates.mkString("(", ", ", ")")}"

  final case class Geometry(error: GeometryError) extends MapError:
    val message: String = error.message

  object Geometry:
    def apply(error: GeometryError | spatial4s.SpatialError): Geometry =
      new Geometry(GeometryError.fromCoordinate(error))
