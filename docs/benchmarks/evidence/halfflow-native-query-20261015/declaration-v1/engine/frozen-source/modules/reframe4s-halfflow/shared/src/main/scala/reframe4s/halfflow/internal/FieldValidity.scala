package reframe4s.halfflow.internal

import ravel.{Array1, NDArray, Shape}

/** Validity of a voxel-aligned scalar or vector field.
  *
  * `All` avoids allocating a full mask for fields whose support is complete.
  */
enum FieldValidity:
  case All
  case Mask(values: Array1[Boolean])

  private[halfflow] def requireSize(size: Int): Unit =
    this match
      case All => ()
      case Mask(values) =>
        require(
          values.size == size,
          s"validity length ${values.size} != field size $size"
        )

  private[halfflow] inline def contains(index: Int): Boolean =
    this match
      case All => true
      case Mask(values) => values(index)

object FieldValidity:
  /** Freeze a mutable kernel mask at the semantic image boundary. */
  def copyMask(values: Array[Boolean]): FieldValidity =
    Mask(NDArray.fromSeq(Shape(values.length), values))
