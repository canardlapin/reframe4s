package reframe4s.halfflow

import reframe4s.halfflow.internal.*

/** Errors raised while executing one native matcher to BasinBridge round. */
enum BasinBridgeRoundError:
  case Registration(error: RegistrationError)
  case Evidence(error: BasinBridgeError)
  case Matcher(error: BasinBridgeBlockMatcherError)
  case Projector(error: BasinBridgeProjectorError)
  case Assimilation(error: BasinBridgeAssimilationError)

  def message: String =
    this match
      case Registration(error) => error.message
      case Evidence(error) => error.message
      case Matcher(error) => error.message
      case Projector(error) => error.message
      case Assimilation(error) => error.message

/** All policies that affect one BasinBridge round. */
final case class BasinBridgeRoundConfig private (
    search: BasinBridgeBlockSearchConfig,
    confidence: BasinBridgeConfidenceConfig,
    projector: BasinBridgeProjectorConfig,
    assimilation: BasinBridgeAssimilationConfig,
    cc: BasinBridgeCcObjectiveConfig,
    rematchRetention: BasinBridgeRematchRetentionConfig
)

object BasinBridgeRoundConfig:
  def make(
      search: BasinBridgeBlockSearchConfig,
      confidence: BasinBridgeConfidenceConfig = BasinBridgeConfidenceConfig.default,
      projector: BasinBridgeProjectorConfig = BasinBridgeProjectorConfig.default,
      assimilation: BasinBridgeAssimilationConfig = BasinBridgeAssimilationConfig.default,
      cc: BasinBridgeCcObjectiveConfig = BasinBridgeCcObjectiveConfig.default,
      rematchRetention: BasinBridgeRematchRetentionConfig = BasinBridgeRematchRetentionConfig.default
  ): BasinBridgeRoundConfig =
    new BasinBridgeRoundConfig(search, confidence, projector, assimilation, cc, rematchRetention)

final case class BasinBridgeRematchSupportDiagnostics(
    requestedAnchors: Int,
    retainedIndices: Vector[Int],
    dropped: Vector[BasinBridgeDroppedAnchor]
):
  val retainedAnchors: Int = retainedIndices.length

/** Diagnostics from one round, including the mandatory post-acceptance rematch. */
final case class BasinBridgeRoundDiagnostics(
    initialObjective: BasinBridgeObjective,
    finalObjective: BasinBridgeObjective,
    cc: BasinBridgeCcObjectiveDiagnostics,
    rematched: Boolean,
    rematchSupport: Option[BasinBridgeRematchSupportDiagnostics]
)

final case class BasinBridgeRoundResult[W, F, M](
    state: ForwardMidpoint[W, F, M],
    evidence: BasinBridgeRoundEvidence,
    projection: BasinBridgeProjection,
    assimilation: BasinBridgeAssimilationResult[W, F, M],
    diagnostics: BasinBridgeRoundDiagnostics,
    rematch: Option[BasinBridgeSearchResult]
)

/** One explicit native-image BasinBridge round. */
object BasinBridgeRound:
  def run[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      state: ForwardMidpoint[W, F, M],
      anchors: Vector[BasinBridgePoint],
      config: BasinBridgeRoundConfig
  ): Either[BasinBridgeRoundError, BasinBridgeRoundResult[W, F, M]] =
    BasinBridgeFrozenCcObjective
      .make(fixed, moving, state, config.cc)
      .left
      .map(BasinBridgeRoundError.Registration.apply)
      .flatMap: frozen =>
        frozen.workImages
          .left
          .map(BasinBridgeRoundError.Registration.apply)
          .flatMap: images =>
            BasinBridgeBlockMatcher
              .search(
                images.fixed,
                images.moving,
                anchors,
                config.search,
                config.confidence
              )
              .left
              .map(BasinBridgeRoundError.Matcher.apply)
              .flatMap: search =>
                assimilateWithWorkMatches(
                  fixed,
                  moving,
                  state,
                  anchors,
                  BasinBridgeWorkMatches
                    .fromVector(search.correspondences)
                    .left
                    .map(BasinBridgeRoundError.Evidence.apply),
                  config,
                  frozen,
                  rematch = true
                )

  /** Run the same bridge and true-CC path with supplied work-space oracle matches. */
  def runWithWorkMatches[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      state: ForwardMidpoint[W, F, M],
      work: BasinBridgeWorkMatches,
      config: BasinBridgeRoundConfig
  ): Either[BasinBridgeRoundError, BasinBridgeRoundResult[W, F, M]] =
    BasinBridgeFrozenCcObjective
      .make(fixed, moving, state, config.cc)
      .left
      .map(BasinBridgeRoundError.Registration.apply)
      .flatMap: frozen =>
        assimilateWithWorkMatches(
          fixed,
          moving,
          state,
          Vector.empty,
          Right(work),
          config,
          frozen,
          rematch = false
        )

  private def assimilateWithWorkMatches[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      state: ForwardMidpoint[W, F, M],
      anchors: Vector[BasinBridgePoint],
      workMatches: Either[BasinBridgeRoundError, BasinBridgeWorkMatches],
      config: BasinBridgeRoundConfig,
      frozen: BasinBridgeFrozenCcObjective[W, F, M],
      rematch: Boolean
  ): Either[BasinBridgeRoundError, BasinBridgeRoundResult[W, F, M]] =
    for
      work <- workMatches
      endpoint <- liftEndpointMatches(state, work)
      evidence <- BasinBridgeRoundEvidence
        .make(work, endpoint)
        .left
        .map(BasinBridgeRoundError.Evidence.apply)
      projection <- BasinBridgeProjector
        .project(work.values, state.work.grid, config.projector)
        .left
        .map(BasinBridgeRoundError.Projector.apply)
      velocity <- Velocity
        .make(state.work, projection.field)
        .left
        .map(BasinBridgeRoundError.Registration.apply)
      objective = (candidate: ForwardMidpoint[W, F, M]) =>
        for
          matchError <- BasinBridgeObjective.correspondenceMatchError(
            candidate,
            endpoint.values
          )
          ccLoss <- frozen.trueCcLoss(candidate)
          result <- BasinBridgeObjective.make(matchError, ccLoss)
        yield result
      assimilation <- BasinBridgeAssimilator
        .assimilate(
          state,
          velocity,
          endpoint.values,
          objective,
          config.assimilation
        )
        .left
        .map(BasinBridgeRoundError.Assimilation.apply)
      rematchOutcome <- rematchIfAccepted(
        fixed,
        moving,
        assimilation,
        anchors,
        config,
        rematch
      )
      finalObjective <- objective(assimilation.state)
        .left
        .map(BasinBridgeRoundError.Registration.apply)
    yield
      BasinBridgeRoundResult(
        assimilation.state,
        evidence,
        projection,
        assimilation,
        BasinBridgeRoundDiagnostics(
          assimilation.initialObjective,
          finalObjective,
          frozen.diagnostics,
          rematchOutcome.nonEmpty,
          rematchOutcome.map(_._2)
        ),
        rematchOutcome.map(_._1)
      )

  private def rematchIfAccepted[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      assimilation: BasinBridgeAssimilationResult[W, F, M],
      anchors: Vector[BasinBridgePoint],
      config: BasinBridgeRoundConfig,
      rematch: Boolean
  ): Either[
    BasinBridgeRoundError,
    Option[(BasinBridgeSearchResult, BasinBridgeRematchSupportDiagnostics)]
  ] =
    if !assimilation.accepted || !rematch then Right(None)
    else
      BasinBridgeFrozenCcObjective
        .make(fixed, moving, assimilation.state, config.cc)
        .left
        .map(BasinBridgeRoundError.Registration.apply)
        .flatMap: refreshed =>
          refreshed.workImages
            .left
            .map(BasinBridgeRoundError.Registration.apply)
            .flatMap: images =>
              BasinBridgeBlockMatcher
                .searchRetainingUsable(
                  images.fixed,
                  images.moving,
                  anchors,
                  config.search,
                  config.rematchRetention,
                  config.confidence
                )
                .left
                .map(BasinBridgeRoundError.Matcher.apply)
                .map: retained =>
                  Some(
                    retained.result -> BasinBridgeRematchSupportDiagnostics(
                      retained.requestedAnchors,
                      retained.retainedIndices,
                      retained.dropped
                    )
                  )

  /** Lift work-space matcher output through the current midpoint arms. */
  def liftEndpointMatches[W, F, M](
      state: ForwardMidpoint[W, F, M],
      work: BasinBridgeWorkMatches
  ): Either[BasinBridgeRoundError, BasinBridgeEndpointMatches] =
    for
      fixedPull <- state.fixed.denseForward.left.map(BasinBridgeRoundError.Registration.apply)
      movingPull <- state.moving.denseForward.left.map(BasinBridgeRoundError.Registration.apply)
      fixed <- morphism(fixedPull, "BasinBridge fixed endpoint")
      moving <- morphism(movingPull, "BasinBridge moving endpoint")
      values <- work.values.zipWithIndex.foldLeft(
        Right(Vector.empty[BasinBridgeCorrespondence]): Either[BasinBridgeRoundError, Vector[BasinBridgeCorrespondence]]
      ) { case (accumulator, (correspondence, index)) =>
        accumulator.flatMap: collected =>
          for
            fixedPoint <- MapExecution.coordinates(fixed, Vector(correspondence.fixed.x, correspondence.fixed.y, correspondence.fixed.z))
              .left.map(error => BasinBridgeRoundError.Registration(RegistrationError.MorphismExportFailed("fixed endpoint", error.message)))
            movingPoint <- MapExecution.coordinates(moving, Vector(correspondence.moving.x, correspondence.moving.y, correspondence.moving.z))
              .left.map(error => BasinBridgeRoundError.Registration(RegistrationError.MorphismExportFailed("moving endpoint", error.message)))
            result <-
              BasinBridgePoint
                .make(fixedPoint(0), fixedPoint(1), fixedPoint(2), s"fixed endpoint $index")
                .left
                .map(BasinBridgeRoundError.Evidence.apply)
                .flatMap: endpointFixed =>
                  BasinBridgePoint
                    .make(movingPoint(0), movingPoint(1), movingPoint(2), s"moving endpoint $index")
                    .left
                    .map(BasinBridgeRoundError.Evidence.apply)
                    .map: endpointMoving =>
                      collected :+ BasinBridgeCorrespondence.fromConfidence(
                        endpointFixed,
                        endpointMoving,
                        correspondence.confidenceEvidence
                      )
          yield result
      }
      result <- BasinBridgeEndpointMatches
        .fromVector(values)
        .left
        .map(BasinBridgeRoundError.Evidence.apply)
    yield result

  private def morphism[A, B](
      pull: DensePull[A, B],
      context: String
  ): Either[BasinBridgeRoundError, reframe4s.field.DenseMap[?, ?, image4s.geometry.D3, ravel.Rank[4]]] =
    pull.toMap.left.map(error => BasinBridgeRoundError.Registration(
      RegistrationError.MorphismExportFailed(context, error.message)
    ))
