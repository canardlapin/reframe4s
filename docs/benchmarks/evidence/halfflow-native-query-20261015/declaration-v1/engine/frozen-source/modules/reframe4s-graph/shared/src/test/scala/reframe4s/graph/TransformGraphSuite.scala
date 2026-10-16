package reframe4s.graph

import gale.linalg.DMat
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.core.Jet1
import reframe4s.core.MapError
import reframe4s.core.SmoothIso
import reframe4s.core.SpatialMap
import image4s.geometry.D2
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.FrameId
import image4s.geometry.GeometryError
import image4s.geometry.Point

final class TransformGraphSuite extends munit.FunSuite:
  test("an empty route is the checked identity, even before registration"):
    val frame = geometry(Frame.named[D2]("identity"))
    val path =
      resolved(TransformGraph.empty[D2].path[frame.type, frame.type](frame, frame))
    val point = geometry(Point.in[D2](frame)(2.0, -3.0))
    val result = mapped(path(point))

    assertEquals(path.keys, Vector.empty)
    assertEquals(result.coordinates, point.coordinates)
    assert(result.belongsTo(frame))

  test("a unique chain composes in declared order"):
    val first = geometry(Frame.named[D2]("first"))
    val middle = geometry(Frame.named[D2]("middle"))
    val last = geometry(Frame.named[D2]("last"))
    val firstKey = key("first-middle")
    val secondKey = key("middle-last")
    val firstMap =
      new TranslationMap(first, middle, Vector(2.0, -1.0))
    val secondMap =
      new TranslationMap(middle, last, Vector(-3.0, 4.0))
    val graph =
      added(
        added(
          TransformGraph.empty[D2],
          TransformEdge.oneWay(firstKey, firstMap)
        ),
        TransformEdge.oneWay(secondKey, secondMap)
      )

    val path =
      resolved(graph.path[first.type, last.type](first, last))
    val result = mapped(path(geometry(Point.in[D2](first)(1.0, 2.0))))

    assertEquals(path.keys, Vector(firstKey, secondKey))
    assertEquals(result.coordinates, Vector(0.0, 5.0))

  test("ephemeral frames with identical metadata remain distinct graph nodes"):
    val source = geometry(Frame.named[D2]("same-label"))
    val target = geometry(Frame.named[D2]("same-label"))
    val edgeKey = key("ephemeral-owners")
    val graph =
      added(
        TransformGraph.empty[D2],
        TransformEdge.oneWay(
          edgeKey,
          new TranslationMap(source, target, Vector(4.0, -2.0))
        )
      )

    val path =
      resolved(graph.path[source.type, target.type](source, target))
    val result =
      mapped(path(geometry(Point.in[D2](source)(1.0, 3.0))))

    assert(source ne target)
    assertEquals(path.keys, Vector(edgeKey))
    assertEquals(result.coordinates, Vector(5.0, 1.0))
    assert(result.belongsTo(target))
    assert(!result.belongsTo(source))

  test("persistent reconstructions align without exposing a total id"):
    val original =
      geometry(
        Frame.persistentNamed[D2](
          geometry(FrameId.parse("graph-restored-owner")),
          "original-label"
        )
      )
    val record = geometry(original.record.left.map(image4s.geometry.GeometryError.fromSpatial))
    val first =
      geometry(Frame.restore[D2](record, Frame.Registry.empty)).frame
    val second =
      geometry(
        Frame.restore[D2](
          record.copy(metadata =
            geometry(image4s.geometry.FrameMetadata.named("restored-label"))
          ),
          Frame.Registry.empty
        )
      ).frame
    val path =
      resolved(
        TransformGraph.empty[D2]
          .path[first.type, second.type](first, second)
      )
    val point = geometry(Point.in[D2](first)(7.0, -5.0))
    val result = mapped(path(point))

    assert(first ne second)
    assert(first.samePersistentKeyAs(second))
    assertEquals(path.keys, Vector.empty)
    assertEquals(result.coordinates, point.coordinates)
    assert(result.belongsTo(second))
    assert(!result.belongsTo(first))

  test("missing and ambiguous paths are reported rather than selected"):
    val first = geometry(Frame.named[D2]("first"))
    val middle = geometry(Frame.named[D2]("middle"))
    val last = geometry(Frame.named[D2]("last"))
    val isolated = geometry(Frame.named[D2]("isolated"))
    val chainFirst = key("chain-first")
    val chainSecond = key("chain-second")
    val direct = key("direct")
    val isolatedEdge = key("isolated-edge")
    val graph =
      added(
        added(
          added(
            added(
              TransformGraph.empty[D2],
              TransformEdge.oneWay(
                chainFirst,
                new TranslationMap(first, middle, Vector(1.0, 0.0))
              )
            ),
            TransformEdge.oneWay(
              chainSecond,
              new TranslationMap(middle, last, Vector(1.0, 0.0))
            )
          ),
          TransformEdge.oneWay(
            direct,
            new TranslationMap(first, last, Vector(2.0, 0.0))
          )
        ),
        TransformEdge.oneWay(
          isolatedEdge,
          new TranslationMap(isolated, isolated, Vector(0.0, 0.0))
        )
      )

    graph.path(first, last) match
      case Left(GraphError.AmbiguousPath(source, target, alternatives)) =>
        assertEquals(source, FrameOwnerDescriptor.of(first))
        assertEquals(target, FrameOwnerDescriptor.of(last))
        assertEquals(alternatives, 2)
      case other =>
        fail(s"expected an ambiguous path, got $other")

    assertEquals(
      graph.path(first, isolated).left.toOption,
      Some(
        GraphError.MissingPath(
          FrameOwnerDescriptor.of(first),
          FrameOwnerDescriptor.of(isolated)
        )
      )
    )

  test("only exact edges reverse automatically and explicit reversal diagnoses one-way edges"):
    val first = geometry(Frame.named[D2]("first"))
    val middle = geometry(Frame.named[D2]("middle"))
    val last = geometry(Frame.named[D2]("last"))
    val exactKey = key("exact")
    val oneWayKey = key("one-way")
    val exact =
      new TranslationIso(first, middle, Vector(3.0, -2.0))
    val oneWay =
      new TranslationMap(middle, last, Vector(1.0, 1.0))
    val graph =
      added(
        added(
          TransformGraph.empty[D2],
          TransformEdge.exact(exactKey, exact)
        ),
        TransformEdge.oneWay(oneWayKey, oneWay)
      )

    val reverse =
      resolved(graph.path[middle.type, first.type](middle, first))
    val result =
      mapped(reverse(geometry(Point.in[D2](middle)(7.0, 5.0))))
    assertEquals(result.coordinates, Vector(4.0, 7.0))

    assertEquals(
      graph.path(last, middle).left.toOption,
      Some(
        GraphError.MissingPath(
          FrameOwnerDescriptor.of(last),
          FrameOwnerDescriptor.of(middle)
        )
      )
    )
    assertEquals(
      graph.pathByKeys(last, middle, Vector(oneWayKey)).left.toOption,
      Some(
        GraphError.NonInvertibleReversal(
          oneWayKey,
          FrameOwnerDescriptor.of(last)
        )
      )
    )

  private def key(value: String): TransformKey =
    TransformKey.parse(value).fold(error => fail(error.message), identity)

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def mapped[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def added[D <: Dim](
      graph: TransformGraph[D],
      edge: TransformEdge[D]
  ): TransformGraph[D] =
    graph.add(edge).fold(error => fail(error.message), identity)

  private def resolved[A](value: Either[GraphError, A]): A =
    value.fold(error => fail(error.message), identity)

  private final class TranslationMap[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      val source: From,
      val target: To,
      offset: Vector[Double]
  )(using Dimension[D]) extends SpatialMap[From, To, D]:
    def apply(point: Point[From, D]): Either[MapError, Point[To, D]] =
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        result <- Point
          .fromVector(
            target,
            point.coordinates.zip(offset).map(_ + _)
          )
          .left
          .map(MapError.Geometry.apply)
        alignment <- Frame
          .alignOwners[D, target.type, To](target, target)
          .left
          .map(MapError.Geometry.apply)
        rebound <- alignment
          .pointToRight(result)
          .left
          .map(MapError.Geometry.apply)
      yield rebound

  private final class TranslationIso[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      val source: From,
      val target: To,
      offset: Vector[Double]
  )(using dimension: Dimension[D]) extends SmoothIso[From, To, D]:
    def apply(point: Point[From, D]): Either[MapError, Point[To, D]] =
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        result <- Point
          .fromVector(
            target,
            point.coordinates.zip(offset).map(_ + _)
          )
          .left
          .map(MapError.Geometry.apply)
        alignment <- Frame
          .alignOwners[D, target.type, To](target, target)
          .left
          .map(MapError.Geometry.apply)
        rebound <- alignment
          .pointToRight(result)
          .left
          .map(MapError.Geometry.apply)
      yield rebound

    def jet1At(
        point: Point[From, D]
    ): Either[MapError, Jet1[From, To, D]] =
      apply(point).flatMap(result =>
        Jet1.create(result, DMat.eye(dimension.rank))
      )

    def inverse: SmoothIso[To, From, D] =
      new TranslationIso(target, source, offset.map(-_))
