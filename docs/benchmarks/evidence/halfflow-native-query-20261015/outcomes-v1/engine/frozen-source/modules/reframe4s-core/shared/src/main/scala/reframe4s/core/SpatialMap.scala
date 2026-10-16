package reframe4s.core

import gale.linalg.DMat
import image4s.geometry.Affine
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Point
import scala.annotation.unused

trait SpatialMap[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim
]:
  def source: From
  def target: To
  def apply(point: Point[From, D]): Either[MapError, Point[To, D]]

  final def andThen[Next <: Frame[D]](
      next: SpatialMap[To, Next, D]
  )(using Dimension[D]): SpatialMap[From, Next, D] =
    SpatialMap.compose(this, next)

/** A checked widening of path-dependent frame endpoint refinements.
  *
  * `underlying` exists so provider modules can preserve capabilities and
  * structural provenance without asking consumers to use casts.
  */
trait FrameErasedMap[D <: Dim]
    extends SpatialMap[Frame[D], Frame[D], D]:
  type UnderlyingFrom <: Frame[D]
  type UnderlyingTo <: Frame[D]
  def underlying: SpatialMap[UnderlyingFrom, UnderlyingTo, D]

trait SmoothMap[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim
] extends SpatialMap[From, To, D]:
  def jet1At(point: Point[From, D]): Either[MapError, Jet1[From, To, D]]

  final def andThen[Next <: Frame[D]](
      next: SmoothMap[To, Next, D]
  )(using Dimension[D]): SmoothMap[From, Next, D] =
    SmoothMap.compose(this, next)

  final def andThenSmooth[Next <: Frame[D]](
      next: SmoothMap[To, Next, D]
  )(using Dimension[D]): SmoothMap[From, Next, D] =
    SmoothMap.compose(this, next)

trait SmoothIso[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim
] extends SmoothMap[From, To, D]:
  def inverse: SmoothIso[To, From, D]

  final def andThen[Next <: Frame[D]](
      next: SmoothIso[To, Next, D]
  )(using Dimension[D]): SmoothIso[From, Next, D] =
    SmoothIso.compose(this, next)

  final def andThenIso[Next <: Frame[D]](
      next: SmoothIso[To, Next, D]
  )(using Dimension[D]): SmoothIso[From, Next, D] =
    SmoothIso.compose(this, next)

/**
 * A spatial isomorphism whose coordinate action is exactly one authoritative
 * geometry [[Affine]].
 *
 * This capability lets downstream kernels compile affine scanline evaluation
 * without introducing another matrix or transform algebra. Concrete affine
 * values and parameterizations remain outside `reframe4s-core`.
 */
trait AffineMap[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim
] extends SmoothIso[From, To, D]:
  def operator: Affine[D]

object AffineMap:
  /**
   * The authoritative affine identity between one live frame owner and itself.
   */
  def identity[D <: Dim, F <: Frame[D]](
      frame: F
  )(using Dimension[D]): AffineMap[F, F, D] =
    new IdentityAffineMap(frame)

  private final class IdentityAffineMap[
      D <: Dim,
      F <: Frame[D]
  ](
      val source: F
  )(using dimension: Dimension[D]) extends AffineMap[F, F, D]:
    val target: F = source
    val operator: Affine[D] = Affine.identity[D]

    def apply(point: Point[F, D]): Either[MapError, Point[F, D]] =
      SpatialMap.validateSourcePoint(source, point).map(_ => point)

    def jet1At(
        point: Point[F, D]
    ): Either[MapError, Jet1[F, F, D]] =
      apply(point).flatMap(value =>
        Jet1.create[F, F, D](
          value,
          DMat.eye(dimension.rank)
        )
      )

    def inverse: AffineMap[F, F, D] =
      this

object SpatialMap:
  /** Safely widen path-dependent endpoint refinements at an API boundary.
    *
    * The returned map preserves the exact live endpoint owners and checks both
    * input and output ownership on every application. This is the provider
    * boundary for consumers that must store heterogeneous maps without casts.
    */
  def eraseFrameRefinements[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      map: SpatialMap[From, To, D]
  )(using Dimension[D]): SpatialMap[Frame[D], Frame[D], D] =
    new ErasedFrameMap(map)

  def identity[D <: Dim, F <: Frame[D]](
      frame: F
  )(using Dimension[D]): SmoothIso[F, F, D] =
    new IdentityMap(frame)

  def compose[
      A <: Frame[D],
      B <: Frame[D],
      C <: Frame[D],
      D <: Dim
  ](
      first: SpatialMap[A, B, D],
      second: SpatialMap[B, C, D]
  )(using Dimension[D]): SpatialMap[A, C, D] =
    new CompositeMap(first, second)

  private final class IdentityMap[D <: Dim, F <: Frame[D]](
      val source: F
  )(using dimension: Dimension[D]) extends SmoothIso[F, F, D]:
    val target: F = source

    def apply(point: Point[F, D]): Either[MapError, Point[F, D]] =
      validateSourcePoint(source, point).map(_ => point)

    def jet1At(point: Point[F, D]): Either[MapError, Jet1[F, F, D]] =
      apply(point).flatMap(value =>
        Jet1.create[F, F, D](value, DMat.eye(dimension.rank))
      )

    def inverse: SmoothIso[F, F, D] =
      this

  private final class CompositeMap[
      A <: Frame[D],
      B <: Frame[D],
      C <: Frame[D],
      D <: Dim
  ](
      first: SpatialMap[A, B, D],
      second: SpatialMap[B, C, D]
  )(using Dimension[D]) extends SpatialMap[A, C, D]:
    val source: A = first.source
    val target: C = second.target

    def apply(point: Point[A, D]): Either[MapError, Point[C, D]] =
      for
        _ <- validateSourcePoint(source, point)
        intermediate <- first(point)
        rebound <- alignIntermediate(first.target, second.source, intermediate)
        result <- second(rebound)
        _ <- validateResultPoint(target, result)
      yield result

  private final class ErasedFrameMap[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      val underlying: SpatialMap[From, To, D]
  )(using @unused dimension: Dimension[D]) extends FrameErasedMap[D]:
    type UnderlyingFrom = From
    type UnderlyingTo = To
    private val map = underlying
    val source: Frame[D] = map.source
    val target: Frame[D] = map.target

    def apply(
        point: Point[Frame[D], D]
    ): Either[MapError, Point[Frame[D], D]] =
      for
        _ <- validateSourcePoint(source, point)
        sourceAlignment <- Frame
          .alignOwners[D, Frame[D], From](source, map.source)
          .left
          .map(MapError.Geometry.apply)
        refined <- sourceAlignment
          .pointToRight(point)
          .left
          .map(MapError.Geometry.apply)
        mapped <- map(refined)
        _ <- validateResultPoint(target, mapped)
        targetAlignment <- Frame
          .alignOwners[D, To, Frame[D]](map.target, target)
          .left
          .map(MapError.Geometry.apply)
        widened <- targetAlignment
          .pointToRight(mapped)
          .left
          .map(MapError.Geometry.apply)
      yield widened

  def validateSourcePoint[
      D <: Dim,
      Expected <: Frame[D],
      Actual <: Frame[D]
  ](
      expected: Expected,
      point: Point[Actual, D]
  ): Either[MapError, Unit] =
    validateSourceFrame(expected, point.frame)

  def validateSourceFrame[
      D <: Dim,
      Expected <: Frame[D],
      Actual <: Frame[D]
  ](
      expected: Expected,
      actual: Actual
  ): Either[MapError, Unit] =
    if expected.sameRuntimeOwnerAs(actual) then Right(())
    else if expected.samePersistentKeyAs(actual) then
      Left(
        MapError.SourceFrameOwnerMismatch(
          FrameOwnerDescriptor.of(expected)
        )
      )
    else
      Left(
        MapError.SourceFrameMismatch(
          FrameOwnerDescriptor.of(expected),
          FrameOwnerDescriptor.of(actual)
        )
      )

  def validateResultPoint[
      D <: Dim,
      Expected <: Frame[D],
      Actual <: Frame[D]
  ](
      expected: Expected,
      point: Point[Actual, D]
  ): Either[MapError, Unit] =
    validateResultFrame(expected, point.frame)

  def validateResultFrame[
      D <: Dim,
      Expected <: Frame[D],
      Actual <: Frame[D]
  ](
      expected: Expected,
      actual: Actual
  ): Either[MapError, Unit] =
    if expected.sameRuntimeOwnerAs(actual) then Right(())
    else if expected.samePersistentKeyAs(actual) then
      Left(
        MapError.ResultFrameOwnerMismatch(
          FrameOwnerDescriptor.of(expected)
        )
      )
    else
      Left(
        MapError.ResultFrameMismatch(
          FrameOwnerDescriptor.of(expected),
          FrameOwnerDescriptor.of(actual)
        )
      )

  private[core] def alignIntermediate[
      D <: Dim,
      F <: Frame[D]
  ](
      left: F,
      right: F,
      point: Point[F, D]
  )(using @unused dimension: Dimension[D]): Either[MapError, Point[F, D]] =
    for
      _ <- validateResultPoint(left, point)
      alignment <- Frame
        .alignOwners(left, right)
        .left
        .map(MapError.Geometry.apply)
      rebound <- alignment
        .pointToRight(point)
        .left
        .map(MapError.Geometry.apply)
    yield rebound

object SmoothMap:
  def compose[
      A <: Frame[D],
      B <: Frame[D],
      C <: Frame[D],
      D <: Dim
  ](
      first: SmoothMap[A, B, D],
      second: SmoothMap[B, C, D]
  )(using Dimension[D]): SmoothMap[A, C, D] =
    new CompositeSmoothMap(first, second)

  private final class CompositeSmoothMap[
      A <: Frame[D],
      B <: Frame[D],
      C <: Frame[D],
      D <: Dim
  ](
      first: SmoothMap[A, B, D],
      second: SmoothMap[B, C, D]
  )(using dimension: Dimension[D]) extends SmoothMap[A, C, D]:
    val source: A = first.source
    val target: C = second.target

    def apply(point: Point[A, D]): Either[MapError, Point[C, D]] =
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        intermediate <- first(point)
        rebound <- SpatialMap.alignIntermediate(
          first.target,
          second.source,
          intermediate
        )
        result <- second(rebound)
        _ <- SpatialMap.validateResultPoint(target, result)
      yield result

    def jet1At(point: Point[A, D]): Either[MapError, Jet1[A, C, D]] =
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        firstJet <- first.jet1At(point)
        rebound <- SpatialMap.alignIntermediate(
          first.target,
          second.source,
          firstJet.value
        )
        secondJet <- second.jet1At(rebound)
        _ <- SpatialMap.validateResultPoint(target, secondJet.value)
        differential = multiply(
          secondJet.differential,
          firstJet.differential,
          dimension.rank
        )
        result <- Jet1.create[A, C, D](secondJet.value, differential)
      yield result

  private[core] def multiply(
      left: DMat,
      right: DMat,
      rank: Int
  ): DMat =
    DMat.dense(
      rank,
      rank,
      Vector.tabulate(rank * rank) { flat =>
        val row = flat / rank
        val column = flat % rank
        var middle = 0
        var sum = 0.0
        while middle < rank do
          sum += left(row, middle) * right(middle, column)
          middle += 1
        sum
      }
    )

object SmoothIso:
  def compose[
      A <: Frame[D],
      B <: Frame[D],
      C <: Frame[D],
      D <: Dim
  ](
      first: SmoothIso[A, B, D],
      second: SmoothIso[B, C, D]
  )(using Dimension[D]): SmoothIso[A, C, D] =
    new CompositeIso(first, second)

  private final class CompositeIso[
      A <: Frame[D],
      B <: Frame[D],
      C <: Frame[D],
      D <: Dim
  ](
      first: SmoothIso[A, B, D],
      second: SmoothIso[B, C, D]
  )(using dimension: Dimension[D]) extends SmoothIso[A, C, D]:
    private val smooth = SmoothMap.compose(first, second)

    val source: A = first.source
    val target: C = second.target

    def apply(point: Point[A, D]): Either[MapError, Point[C, D]] =
      smooth(point)

    def jet1At(point: Point[A, D]): Either[MapError, Jet1[A, C, D]] =
      smooth.jet1At(point)

    def inverse: SmoothIso[C, A, D] =
      SmoothIso.compose(second.inverse, first.inverse)
