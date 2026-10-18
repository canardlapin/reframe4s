package reframe4s.lie

import image4s.geometry.{Affine, D3, Frame, FrameId, Point}
import reframe4s.core.{LinkDirection, WorldLink, WorldLinkError}

class WorldLinkSuite extends munit.FunSuite:
  private def ok[E, A](result: Either[E, A]): A =
    result.fold(error => fail(s"unexpected failure: $error"), identity)

  private def translation(x: Double, y: Double, z: Double): Affine[D3] =
    ok(Affine.fromRowMajor[D3](Vector(1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1)))

  private def point(frame: Frame[D3], x: Double, y: Double, z: Double): Point[frame.type, D3] =
    ok(Point.in(frame)(x, y, z))

  private def assertAt(actual: Point[?, D3], expected: Vector[Double]): Unit =
    (0 until 3).foreach(axis => assertEqualsDouble(actual.coordinates(axis), expected(axis), 1e-12))

  /** A fresh runtime owner of one persistent key per call. */
  private def persistent(id: String): Frame[D3] =
    ok(Frame.persistentNamed[D3](ok(FrameId.parse(id)), id))

  private val subject = ok(Frame.named[D3]("sub-01 T1w"))
  private val mni = persistent("MNI152NLin2009cAsym")

  test("one world links directly: coordinates carry over under a checked alignment"):
    val again = persistent("MNI152NLin2009cAsym")
    assert(!again.sameRuntimeOwnerAs(mni), clue = "distinct runtime owners of one persistent key")
    val link = ok(WorldLink.shared[mni.type, again.type, D3](mni, again))
    val there = ok(link.toRight(point(mni, 1.0, -2.0, 3.5)))
    assert(there.belongsTo(again))
    assertAt(there, Vector(1.0, -2.0, 3.5))
    assertAt(ok(link.swap.toRight(there)), Vector(1.0, -2.0, 3.5))
    assert(LinkDirection.values.forall(link.supports))

  test("different worlds, including two ephemeral frames with one label, do not link without a map"):
    val relabelled = ok(Frame.named[D3]("sub-01 T1w"))
    assert(WorldLink.shared[subject.type, relabelled.type, D3](subject, relabelled).isLeft)
    WorldLink.shared[subject.type, mni.type, D3](subject, mni) match
      case Left(WorldLinkError.FrameMismatch(_)) => ()
      case other                                 => fail(s"expected a frame mismatch, got $other")

  test("a pullback-only link maps target points back and refuses the forward direction"):
    // pullback MNI -> subject: subject = mni + (10, 0, -5)
    val pull = FramedAffine.betweenFrames[mni.type, subject.type, D3](mni, subject)(translation(10.0, 0.0, -5.0))
    val link = ok(WorldLink.pullback[subject.type, mni.type, D3](pull, None, "dense warp without inverse"))
    assert(link.supports(LinkDirection.RightToLeft))
    assert(!link.supports(LinkDirection.LeftToRight))
    val back = ok(link.toLeft(point(mni, 1.0, 2.0, 3.0)))
    assert(back.belongsTo(subject))
    assertAt(back, Vector(11.0, 2.0, -2.0))
    assertEquals(
      link.toRight(point(subject, 0.0, 0.0, 0.0)),
      Left(WorldLinkError.DirectionUnavailable(LinkDirection.LeftToRight, "dense warp without inverse"))
    )
    assertEquals(
      link.swap.toLeft(point(subject, 0.0, 0.0, 0.0)),
      Left(WorldLinkError.DirectionUnavailable(LinkDirection.RightToLeft, "dense warp without inverse"))
    )

  test("a link with both directions round-trips"):
    val pull = FramedAffine.betweenFrames[mni.type, subject.type, D3](mni, subject)(translation(10.0, 0.0, -5.0))
    val link = ok(WorldLink.pullback[subject.type, mni.type, D3](pull, Some(pull.inverse), "affine"))
    val forward = ok(link.toRight(point(subject, 11.0, 2.0, -2.0)))
    assertAt(forward, Vector(1.0, 2.0, 3.0))
    assertAt(ok(link.toLeft(forward)), Vector(11.0, 2.0, -2.0))

  test("points of another owner of the link frame's key are rebound, other worlds are refused"):
    val pull = FramedAffine.betweenFrames[mni.type, subject.type, D3](mni, subject)(translation(10.0, 0.0, -5.0))
    val link = ok(WorldLink.pullback[subject.type, mni.type, D3](pull, None, "warp"))
    val otherOwner: mni.type = persistent("MNI152NLin2009cAsym").asInstanceOf[mni.type]
    assertAt(ok(link.toLeft(ok(Point.in(otherOwner)(0.0, 0.0, 0.0)))), Vector(10.0, 0.0, -5.0))
    val fsaverage = persistent("fsaverage")
    val stray = ok(Point.in(fsaverage)(0.0, 0.0, 0.0)).asInstanceOf[Point[mni.type, D3]]
    link.toLeft(stray) match
      case Left(WorldLinkError.FrameMismatch(_)) => ()
      case other                                 => fail(s"expected a frame mismatch, got $other")

  test("a mapped link needs at least one direction, and each map must join the link's frames"):
    assertEquals(
      WorldLink.mapped[subject.type, mni.type, D3](subject, mni, None, None, "nothing"),
      Left(WorldLinkError.NoDirection("nothing"))
    )
    val fsaverage = persistent("fsaverage")
    val wrong = FramedAffine.betweenFrames[fsaverage.type, subject.type, D3](fsaverage, subject)(translation(0, 0, 0))
    WorldLink.mapped[subject.type, mni.type, D3](
      subject,
      mni,
      None,
      Some(wrong.asInstanceOf[FramedAffine[mni.type, subject.type, D3]]),
      "wrong"
    ) match
      case Left(WorldLinkError.FrameMismatch(_)) => ()
      case other                                 => fail(s"expected a frame mismatch, got $other")
