package reframe4s.core

import spatial4s.{Dim, Frame, FrameAlignment, Point, SpatialError}

/** Which way a point crosses a [[WorldLink]]. */
enum LinkDirection derives CanEqual:
  /** From the link's left frame to its right frame. */
  case LeftToRight

  /** From the link's right frame to its left frame. */
  case RightToLeft

  def reverse: LinkDirection =
    this match
      case LeftToRight => RightToLeft
      case RightToLeft => LeftToRight

/** Failures of carrying a point across a [[WorldLink]]. */
enum WorldLinkError derives CanEqual:
  /** The two frames are different worlds and nothing supplied the map in this direction, for example a dense warp read
    * without its inverse. The point is not approximated.
    */
  case DirectionUnavailable(direction: LinkDirection, link: String)

  /** A link's maps were constructed with neither direction. */
  case NoDirection(link: String)

  /** The map exists but failed at this point (outside a field's support, a rejected boundary). */
  case Map(direction: LinkDirection, error: MapError)

  /** A point, map or frame is not in the world the link expects (at construction or when a point crosses). */
  case FrameMismatch(error: SpatialError)

  def message: String =
    this match
      case DirectionUnavailable(direction, link) =>
        s"$link has no ${WorldLinkError.describe(direction)} map; it is not approximated"
      case NoDirection(link)       => s"$link supplies no map in either direction"
      case Map(direction, error)   => s"${WorldLinkError.describe(direction)} map failed: ${error.message}"
      case FrameMismatch(error)    => s"frame mismatch: ${error.message}"

object WorldLinkError:
  private def describe(direction: LinkDirection): String =
    direction match
      case LinkDirection.LeftToRight => "left-to-right"
      case LinkDirection.RightToLeft => "right-to-left"

/** How points of the world frame `L` correspond to points of the world frame `R`: the typed map behind a linked cursor.
  *
  * A link is either [[WorldLink.Shared]], when both frames are one world and coordinates carry over unchanged under a
  * checked alignment, or [[WorldLink.Mapped]], when the frames are different worlds joined by typed maps (for example a
  * dense warp and its optional inverse). A mapped link always knows which directions exist: a direction nothing
  * supplied is [[WorldLinkError.DirectionUnavailable]], never an approximation.
  *
  * Points may belong to any runtime owner of a link frame's persistent key; they are rebound to the link's own frames
  * before a map runs, and results are returned as points of the link's frames.
  */
sealed trait WorldLink[L <: Frame[D], R <: Frame[D], D <: Dim]:
  def left: L
  def right: R

  /** Whether a point can cross in `direction` at all (it can still fail at a particular point). */
  def supports(direction: LinkDirection): Boolean

  def toRight(point: Point[L, D]): Either[WorldLinkError, Point[R, D]]

  def toLeft(point: Point[R, D]): Either[WorldLinkError, Point[L, D]]

  /** The same correspondence read the other way. */
  def swap: WorldLink[R, L, D]

object WorldLink:
  /** Both frames are one world (one runtime owner or one persistent key): coordinates carry over unchanged. */
  final class Shared[L <: Frame[D], R <: Frame[D], D <: Dim] private[WorldLink] (
      val alignment: FrameAlignment[D, L, R],
      private val reverse: FrameAlignment[D, R, L]
  ) extends WorldLink[L, R, D]:
    def left: L = alignment.left
    def right: R = alignment.right
    def supports(direction: LinkDirection): Boolean = true

    def toRight(point: Point[L, D]): Either[WorldLinkError, Point[R, D]] =
      for
        owned <- own(point, left)
        moved <- alignment.pointToRight(owned).left.map(WorldLinkError.FrameMismatch.apply)
      yield moved

    def toLeft(point: Point[R, D]): Either[WorldLinkError, Point[L, D]] =
      for
        owned <- own(point, right)
        moved <- alignment.pointToLeft(owned).left.map(WorldLinkError.FrameMismatch.apply)
      yield moved

    def swap: Shared[R, L, D] = new Shared(reverse, alignment)

    override def toString: String = s"WorldLink.Shared($left, $right)"

  /** Different worlds joined by typed maps; at least one direction exists. */
  final class Mapped[L <: Frame[D], R <: Frame[D], D <: Dim] private[WorldLink] (
      val left: L,
      val right: R,
      private val rightward: Option[SpatialMap[L, R, D]],
      private val leftward: Option[SpatialMap[R, L, D]],
      val description: String
  ) extends WorldLink[L, R, D]:
    def supports(direction: LinkDirection): Boolean =
      direction match
        case LinkDirection.LeftToRight => rightward.nonEmpty
        case LinkDirection.RightToLeft => leftward.nonEmpty

    def toRight(point: Point[L, D]): Either[WorldLinkError, Point[R, D]] =
      cross(point, rightward, LinkDirection.LeftToRight, right)

    def toLeft(point: Point[R, D]): Either[WorldLinkError, Point[L, D]] =
      cross(point, leftward, LinkDirection.RightToLeft, left)

    def swap: Mapped[R, L, D] = new Mapped(right, left, leftward, rightward, description)

    private def cross[A <: Frame[D], B <: Frame[D]](
        point: Point[A, D],
        map: Option[SpatialMap[A, B, D]],
        direction: LinkDirection,
        endpoint: B
    ): Either[WorldLinkError, Point[B, D]] =
      map match
        case None => Left(WorldLinkError.DirectionUnavailable(direction, description))
        case Some(f) =>
          for
            owned <- own(point, f.source)
            mapped <- f(owned).left.map(WorldLinkError.Map(direction, _))
            result <- own(mapped, endpoint)
          yield result

    override def toString: String = s"WorldLink.Mapped($left, $right, $description)"

  /** Link two frames of one world; fails when they name different worlds. */
  def shared[L <: Frame[D], R <: Frame[D], D <: Dim](left: L, right: R): Either[WorldLinkError, Shared[L, R, D]] =
    val checked =
      for
        forward <- Frame.alignOwners[D, L, R](left, right)
        backward <- Frame.alignOwners[D, R, L](right, left)
      yield new Shared(forward, backward)
    checked.left.map(WorldLinkError.FrameMismatch.apply)

  /** Link two frames through an alignment already checked. */
  def aligned[L <: Frame[D], R <: Frame[D], D <: Dim](
      alignment: FrameAlignment[D, L, R]
  ): Either[WorldLinkError, Shared[L, R, D]] =
    shared[L, R, D](alignment.left, alignment.right)

  /** Link through a pullback `R -> L`, which always exists, and a forward map `L -> R` when one is known.
    *
    * This is the shape of a transform from `L` (source) to `R` (target): the pullback carries target points to source
    * points, and the forward direction exists only when something supplied it.
    */
  def pullback[L <: Frame[D], R <: Frame[D], D <: Dim](
      pull: SpatialMap[R, L, D],
      push: Option[SpatialMap[L, R, D]],
      description: String
  ): Either[WorldLinkError, Mapped[L, R, D]] =
    mapped[L, R, D](pull.target, pull.source, push, Some(pull), description)

  /** Link `left` and `right` through whichever maps exist; at least one must, and each must join the two frames. */
  def mapped[L <: Frame[D], R <: Frame[D], D <: Dim](
      left: L,
      right: R,
      toRight: Option[SpatialMap[L, R, D]],
      toLeft: Option[SpatialMap[R, L, D]],
      description: String
  ): Either[WorldLinkError, Mapped[L, R, D]] =
    val label = if description.trim.isEmpty then s"link $left -> $right" else description.trim
    def joins[A <: Frame[D], B <: Frame[D]](map: SpatialMap[A, B, D], from: A, to: B): Either[WorldLinkError, Unit] =
      for
        _ <- Frame.alignOwners[D, A, A](map.source, from).left.map(WorldLinkError.FrameMismatch.apply)
        _ <- Frame.alignOwners[D, B, B](map.target, to).left.map(WorldLinkError.FrameMismatch.apply)
      yield ()
    if toRight.isEmpty && toLeft.isEmpty then Left(WorldLinkError.NoDirection(label))
    else
      for
        _ <- toRight.fold(Right(()))(joins(_, left, right))
        _ <- toLeft.fold(Right(()))(joins(_, right, left))
      yield new Mapped(left, right, toRight, toLeft, label)

  /** Rebind a point to `owner` when it belongs to any runtime owner of `owner`'s persistent key. */
  private def own[F <: Frame[D], D <: Dim](point: Point[F, D], owner: F): Either[WorldLinkError, Point[F, D]] =
    if point.belongsTo(owner) then Right(point)
    else
      Frame
        .alignOwners[D, F, F](point.frame, owner)
        .flatMap(_.pointToRight(point))
        .left
        .map(WorldLinkError.FrameMismatch.apply)
