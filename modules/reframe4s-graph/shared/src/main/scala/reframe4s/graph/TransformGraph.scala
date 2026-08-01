package reframe4s.graph

import reframe4s.core.CertifiedBidirectionalPair
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.core.MapError
import reframe4s.core.SmoothIso
import reframe4s.core.SpatialMap
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Point

opaque type TransformKey = String

object TransformKey:
  def parse(value: String): Either[GraphError, TransformKey] =
    val normalized = value.trim
    if normalized.nonEmpty && normalized == value then Right(value)
    else Left(GraphError.InvalidKey(value))

  extension (key: TransformKey)
    def value: String = key

sealed trait GraphError derives CanEqual:
  def message: String

object GraphError:
  final case class InvalidKey(value: String) extends GraphError:
    val message: String =
      "transform key must be non-empty and contain no surrounding whitespace"

  final case class DuplicateKey(key: TransformKey) extends GraphError:
    val message: String = s"transform key ${key.value} is already present"

  final case class FrameConflict(
      frame: FrameOwnerDescriptor,
      error: GeometryError
  )
      extends GraphError:
    val message: String =
      s"frame $frame conflicts with its registered graph owner: ${error.message}"

  final case class MissingPath(
      source: FrameOwnerDescriptor,
      target: FrameOwnerDescriptor
  )
      extends GraphError:
    val message: String =
      s"no transform path exists from $source to $target"

  final case class AmbiguousPath(
      source: FrameOwnerDescriptor,
      target: FrameOwnerDescriptor,
      alternatives: Int
  ) extends GraphError:
    val message: String =
      s"$alternatives transform paths exist from $source to $target"

  final case class UnknownKey(key: TransformKey) extends GraphError:
    val message: String = s"transform key ${key.value} is not present"

  final case class DisconnectedKey(
      key: TransformKey,
      current: FrameOwnerDescriptor
  ) extends GraphError:
    val message: String =
      s"transform ${key.value} does not start at frame $current"

  final case class NonInvertibleReversal(
      key: TransformKey,
      current: FrameOwnerDescriptor
  ) extends GraphError:
    val message: String =
      s"transform ${key.value} cannot be reversed from frame $current"

  final case class ExplicitPathEndsAt(
      expected: FrameOwnerDescriptor,
      actual: FrameOwnerDescriptor
  ) extends GraphError:
    val message: String =
      s"explicit transform path ends at $actual, expected $expected"

sealed trait TransformEdge[D <: Dim]:
  type From <: Frame[D]
  type To <: Frame[D]
  val key: TransformKey
  val sourceToTarget: SpatialMap[From, To, D]
  private[graph] def traversals(using Dimension[D]): Vector[Traversal[D]]

object TransformEdge:
  def oneWay[
      FromFrame <: Frame[D],
      ToFrame <: Frame[D],
      D <: Dim
  ](
      key: TransformKey,
      map: SpatialMap[FromFrame, ToFrame, D]
  ): TransformEdge[D] {
    type From = FromFrame
    type To = ToFrame
  } =
    new OneWayEdge(key, map)

  def exact[
      FromFrame <: Frame[D],
      ToFrame <: Frame[D],
      D <: Dim
  ](
      key: TransformKey,
      map: SmoothIso[FromFrame, ToFrame, D]
  ): TransformEdge[D] {
    type From = FromFrame
    type To = ToFrame
  } =
    new ExactEdge(key, map)

  def bidirectional[
      FromFrame <: Frame[D],
      ToFrame <: Frame[D],
      D <: Dim
  ](
      key: TransformKey,
      pair: CertifiedBidirectionalPair[FromFrame, ToFrame, D]
  ): TransformEdge[D] {
    type From = FromFrame
    type To = ToFrame
  } =
    new BidirectionalEdge(key, pair)

  private final class OneWayEdge[
      FromFrame <: Frame[D],
      ToFrame <: Frame[D],
      D <: Dim
  ](
      val key: TransformKey,
      val sourceToTarget: SpatialMap[FromFrame, ToFrame, D]
  ) extends TransformEdge[D]:
    type From = FromFrame
    type To = ToFrame

    private[graph] def traversals(using
        Dimension[D]
    ): Vector[Traversal[D]] =
      Vector(Traversal.forward(key, sourceToTarget, exact = false))

  private final class ExactEdge[
      FromFrame <: Frame[D],
      ToFrame <: Frame[D],
      D <: Dim
  ](
      val key: TransformKey,
      val sourceToTarget: SmoothIso[FromFrame, ToFrame, D]
  ) extends TransformEdge[D]:
    type From = FromFrame
    type To = ToFrame

    private[graph] def traversals(using
        Dimension[D]
    ): Vector[Traversal[D]] =
      Vector(
        Traversal.forward(key, sourceToTarget, exact = true),
        Traversal.reverse(key, sourceToTarget.inverse, exact = true)
      )

  private final class BidirectionalEdge[
      FromFrame <: Frame[D],
      ToFrame <: Frame[D],
      D <: Dim
  ](
      val key: TransformKey,
      pair: CertifiedBidirectionalPair[FromFrame, ToFrame, D]
  ) extends TransformEdge[D]:
    type From = FromFrame
    type To = ToFrame
    val sourceToTarget: SpatialMap[FromFrame, ToFrame, D] =
      pair.toTarget

    private[graph] def traversals(using
        Dimension[D]
    ): Vector[Traversal[D]] =
      Vector(
        Traversal.forward(key, pair.toTarget, exact = false),
        Traversal.reverse(key, pair.toSource, exact = false)
      )

final class ResolvedPath[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim
] private[graph] (
    val source: From,
    val target: To,
    val keys: Vector[TransformKey],
    route: Vector[Traversal[D]]
)(using Dimension[D]) extends SpatialMap[From, To, D]:
  def apply(point: Point[From, D]): Either[MapError, Point[To, D]] =
    for
      _ <- SpatialMap.validateSourcePoint(source, point)
      coordinates <- route.foldLeft[Either[MapError, Vector[Double]]](
        Right(point.coordinates)
      )((current, edge) => current.flatMap(edge.applyCoordinates))
      result <- Point
        .fromVector(target, coordinates)
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

private opaque type GraphNode = Int

private object GraphNode:
  def apply(index: Int): GraphNode = index

  extension (node: GraphNode)
    def index: Int = node

final class TransformGraph[D <: Dim] private (
    private val edges: Vector[TransformEdge[D]],
    private val registeredFrames: Vector[Frame[D]]
)(using private val dimension: Dimension[D]):
  def size: Int = edges.size

  def add(edge: TransformEdge[D]): Either[GraphError, TransformGraph[D]] =
    if edges.exists(_.key == edge.key) then
      Left(GraphError.DuplicateKey(edge.key))
    else
      for
        withSource <- register(edge.sourceToTarget.source)
        withTarget <- withSource.register(edge.sourceToTarget.target)
      yield new TransformGraph(
        withTarget.edges :+ edge,
        withTarget.registeredFrames
      )

  def path[From <: Frame[D], To <: Frame[D]](
      source: From,
      target: To
  ): Either[GraphError, ResolvedPath[From, To, D]] =
    Frame.alignOwners(source, target) match
      case Right(_) =>
        Right(new ResolvedPath(source, target, Vector.empty, Vector.empty))
      case Left(_) =>
        for
          sourceNode <- requireRegistered(source)
          targetNode <- requireRegistered(target)
          routes = enumerate(sourceNode, targetNode)
          route <- routes match
            case Vector(single) => Right(single)
            case Vector() =>
              Left(
                GraphError.MissingPath(
                  describe(source),
                  describe(target)
                )
              )
            case alternatives =>
              Left(
                GraphError.AmbiguousPath(
                  describe(source),
                  describe(target),
                  alternatives.size
                )
              )
        yield new ResolvedPath(
          source,
          target,
          route.map(_.key),
          route
        )

  def pathByKeys[From <: Frame[D], To <: Frame[D]](
      source: From,
      target: To,
      keys: Vector[TransformKey]
  ): Either[GraphError, ResolvedPath[From, To, D]] =
    if keys.isEmpty && Frame.alignOwners(source, target).isRight then
      path(source, target)
    else
      for
        sourceNode <- requireRegistered(source)
        targetNode <- requireRegistered(target)
        route <- resolveKeys(sourceNode, keys)
        actualNode = route.lastOption
          .flatMap(edge => nodeFor(edge.target))
          .getOrElse(sourceNode)
        _ <-
          if actualNode == targetNode then Right(())
          else
            Left(
              GraphError.ExplicitPathEndsAt(
                describe(target),
                describe(frameAt(actualNode))
              )
            )
      yield new ResolvedPath(source, target, keys, route)

  private def register(
      frame: Frame[D]
  ): Either[GraphError, TransformGraph[D]] =
    nodeFor(frame) match
      case Some(_) => Right(this)
      case None =>
        persistentIdConflict(frame) match
          case Some(existing) =>
            Frame
              .align(existing, frame)
              .left
              .map(error => GraphError.FrameConflict(describe(frame), error))
              .map(_ => this)
          case None =>
            Right(
              new TransformGraph(edges, registeredFrames :+ frame)
            )

  private def requireRegistered(
      frame: Frame[D]
  ): Either[GraphError, GraphNode] =
    nodeFor(frame) match
      case Some(node) => Right(node)
      case None =>
        persistentIdConflict(frame) match
          case Some(existing) =>
            Frame
              .align(existing, frame)
              .left
              .map(error => GraphError.FrameConflict(describe(frame), error))
              .map(_ => GraphNode(registeredFrames.indexOf(existing)))
          case None =>
            Left(GraphError.MissingPath(describe(frame), describe(frame)))

  private def nodeFor(frame: Frame[D]): Option[GraphNode] =
    registeredFrames.zipWithIndex.collectFirst {
      case (existing, index)
          if existing.sameRuntimeOwnerAs(frame) ||
            existing.samePersistentKeyAs(frame) =>
        GraphNode(index)
    }

  private def persistentIdConflict(frame: Frame[D]): Option[Frame[D]] =
    frame.persistentId.flatMap { id =>
      registeredFrames.find(_.persistentId.contains(id))
    }

  private def frameAt(node: GraphNode): Frame[D] =
    registeredFrames(node.index)

  private def describe(frame: Frame[D]): FrameOwnerDescriptor =
    FrameOwnerDescriptor.of(frame)

  private def allTraversals: Vector[Traversal[D]] =
    edges.flatMap(_.traversals)

  private def enumerate(
      source: GraphNode,
      target: GraphNode
  ): Vector[Vector[Traversal[D]]] =
    if source == target then Vector(Vector.empty)
    else
      val found = Vector.newBuilder[Vector[Traversal[D]]]
      def visit(
          current: GraphNode,
          route: Vector[Traversal[D]],
          visited: Set[GraphNode]
      ): Unit =
        allTraversals.foreach { edge =>
          nodeFor(edge.source).foreach { edgeSource =>
            if edgeSource == current then
              nodeFor(edge.target).foreach { next =>
                if !visited.contains(next) then
                  val nextRoute = route :+ edge
                  if next == target then found += nextRoute
                  else visit(next, nextRoute, visited + next)
              }
          }
        }
      visit(source, Vector.empty, Set(source))
      found.result()

  private def resolveKeys(
      source: GraphNode,
      keys: Vector[TransformKey]
  ): Either[GraphError, Vector[Traversal[D]]] =
    val route = Vector.newBuilder[Traversal[D]]
    var current = source
    var index = 0
    var failure = Option.empty[GraphError]
    while index < keys.length && failure.isEmpty do
      val key = keys(index)
      edges.find(_.key == key) match
        case None =>
          failure = Some(GraphError.UnknownKey(key))
        case Some(edge) =>
          val candidates =
            edge.traversals.filter(traversal =>
              nodeFor(traversal.source).contains(current)
            )
          candidates.headOption match
            case Some(next) =>
              route += next
              current = nodeFor(next.target).getOrElse(current)
            case None =>
              val touchesCurrent =
                nodeFor(edge.sourceToTarget.target).contains(current)
              val currentFrame = describe(frameAt(current))
              failure = Some(
                if touchesCurrent then
                  GraphError.NonInvertibleReversal(key, currentFrame)
                else GraphError.DisconnectedKey(key, currentFrame)
              )
      index += 1
    failure.toLeft(route.result())

object TransformGraph:
  def empty[D <: Dim](using Dimension[D]): TransformGraph[D] =
    new TransformGraph(Vector.empty, Vector.empty)

private sealed trait Traversal[D <: Dim]:
  val key: TransformKey
  val source: Frame[D]
  val target: Frame[D]
  val exact: Boolean
  def applyCoordinates(
      coordinates: Vector[Double]
  ): Either[MapError, Vector[Double]]

private object Traversal:
  def forward[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      key: TransformKey,
      map: SpatialMap[From, To, D],
      exact: Boolean
  )(using Dimension[D]): Traversal[D] =
    typed(key, map, exact)

  def reverse[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      key: TransformKey,
      map: SpatialMap[From, To, D],
      exact: Boolean
  )(using Dimension[D]): Traversal[D] =
    typed(key, map, exact)

  private def typed[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      transformKey: TransformKey,
      map: SpatialMap[From, To, D],
      isExact: Boolean
  )(using Dimension[D]): Traversal[D] =
    new Traversal[D]:
      val key: TransformKey = transformKey
      val source: From = map.source
      val target: To = map.target
      val exact: Boolean = isExact

      def applyCoordinates(
          coordinates: Vector[Double]
      ): Either[MapError, Vector[Double]] =
        for
          point <- Point
            .fromVector(source, coordinates)
            .left
            .map(MapError.Geometry.apply)
          alignment <- Frame
            .alignOwners[D, source.type, From](source, source)
            .left
            .map(MapError.Geometry.apply)
          rebound <- alignment
            .pointToRight(point)
            .left
            .map(MapError.Geometry.apply)
          result <- map(rebound)
        yield result.coordinates
