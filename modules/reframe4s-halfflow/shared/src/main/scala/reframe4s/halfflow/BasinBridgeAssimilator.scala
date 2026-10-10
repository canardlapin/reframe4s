package reframe4s.halfflow

import ravel.NDArray as RavelArray
import reframe4s.halfflow.internal.*

/** Errors raised while selecting a topology-safe BasinBridge update. */
enum BasinBridgeAssimilationError:
  case EmptyCorrespondences
  case Registration(error: RegistrationError)
  case ObjectiveEvaluation(error: RegistrationError)
  case InvalidConfiguration(context: String)

  def message: String =
    this match
      case EmptyCorrespondences =>
        "BasinBridge assimilation requires at least one correspondence"
      case Registration(error) => error.message
      case ObjectiveEvaluation(error) => s"BasinBridge objective evaluation failed: ${error.message}"
      case InvalidConfiguration(context) => s"invalid BasinBridge assimilation configuration: $context"

/** A paired objective: lower values are better for both components. */
final case class BasinBridgeObjective private (
    weightedMatchErrorMm: Double,
    trueCcLoss: Double
)

/** One confidence-weighted sparse residual retained for decomposition metrics. */
final case class BasinBridgeMatchResidual(errorMm: Double, confidence: Double)

object BasinBridgeObjective:
  def make(
      weightedMatchErrorMm: Double,
      trueCcLoss: Double
  ): Either[RegistrationError, BasinBridgeObjective] =
    if !weightedMatchErrorMm.isFinite || weightedMatchErrorMm < 0.0 then
      Left(RegistrationError.InvalidConfiguration("BasinBridge match-error objective"))
    else if !trueCcLoss.isFinite || trueCcLoss < 0.0 then
      Left(RegistrationError.InvalidConfiguration("BasinBridge true-CC objective"))
    else Right(new BasinBridgeObjective(weightedMatchErrorMm, trueCcLoss))

  /** Computes the confidence-weighted sparse pull-map error for a state. */
  def correspondenceMatchError[W, F, M](
      state: ForwardMidpoint[W, F, M],
      correspondences: Vector[BasinBridgeCorrespondence]
  ): Either[RegistrationError, Double] =
    correspondenceResiduals(state, correspondences).flatMap: residuals =>
      var weightedError = 0.0
      var totalWeight = 0.0
      var index = 0
      while index < residuals.length do
        val residual = residuals(index)
        if residual.confidence > 0.0 then
          weightedError += residual.confidence * residual.errorMm
          totalWeight += residual.confidence
        index += 1
      if totalWeight > 0.0 && totalWeight.isFinite then Right(weightedError / totalWeight)
      else Left(RegistrationError.InsufficientSupport("BasinBridge correspondence weights", 0, 1))

  /** Returns individual endpoint residuals for percentile and calibration courts. */
  def correspondenceResiduals[W, F, M](
      state: ForwardMidpoint[W, F, M],
      correspondences: Vector[BasinBridgeCorrespondence]
  ): Either[RegistrationError, Vector[BasinBridgeMatchResidual]] =
    if correspondences.isEmpty then
      Left(RegistrationError.InsufficientSupport("BasinBridge correspondences", 0, 1))
    else
      ForwardMidpointExporter
        .inspect(state)
        .left
        .map(error => RegistrationError.MorphismExportFailed("BasinBridge correspondence objective", error.message))
        .flatMap: candidate =>
          val pair = candidate.transform
          pair.forward.toMap
            .flatMap: morphism =>
              val residuals = Vector.newBuilder[BasinBridgeMatchResidual]
              var index = 0
              var failure = Option.empty[RegistrationError]
              while index < correspondences.length && failure.isEmpty do
                val correspondence = correspondences(index)
                MapExecution.coordinates(morphism,
                  Vector(correspondence.fixed.x, correspondence.fixed.y, correspondence.fixed.z)
                ) match
                  case Left(error) =>
                    failure = Some(RegistrationError.MorphismExportFailed("correspondence residual", error.message))
                  case Right(moving) =>
                    val dx = moving(0) - correspondence.moving.x
                    val dy = moving(1) - correspondence.moving.y
                    val dz = moving(2) - correspondence.moving.z
                    residuals += BasinBridgeMatchResidual(
                      math.sqrt(dx * dx + dy * dy + dz * dz),
                      correspondence.confidence
                    )
                index += 1
              failure.toLeft(residuals.result())

  /** Builds a synthetic or externally supplied objective from one state.
    *
    * `trueCcLoss` is deliberately supplied by the caller. In production it
    * must be the frozen coarse true-CC session; tests may use an analytic
    * proxy, but that proxy is not an anatomical claim.
    */
  def fromCorrespondences[W, F, M](
      state: ForwardMidpoint[W, F, M],
      correspondences: Vector[BasinBridgeCorrespondence],
      trueCcLoss: Double
  ): Either[RegistrationError, BasinBridgeObjective] =
    correspondenceMatchError(state, correspondences).flatMap: matchError =>
      make(matchError, trueCcLoss)

/** Controls for one global alpha backtracking decision. */
final case class BasinBridgeAssimilationConfig private (
    targetSymmetricStrain: Double,
    symmetricStrainPercentile: Double,
    minimumAlpha: Double,
    maximumTrials: Int,
    minimumMatchDropMm: Double,
    maximumCcIncrease: Double,
    flow: FlowConfig,
    geometry: ForwardGeometryConfig,
    maximumIntegrationInverseErrorMm: Double
)

object BasinBridgeAssimilationConfig:
  def make(
      targetSymmetricStrain: Double = 0.15,
      symmetricStrainPercentile: Double = 0.95,
      minimumAlpha: Double = 1.0 / 64.0,
      maximumTrials: Int = 8,
      minimumMatchDropMm: Double = 1e-6,
      maximumCcIncrease: Double = 0.0,
      flow: FlowConfig = FlowConfig(),
      geometry: ForwardGeometryConfig = ForwardGeometryConfig(),
      maximumIntegrationInverseErrorMm: Double = 0.02
  ): Either[BasinBridgeAssimilationError, BasinBridgeAssimilationConfig] =
    if !targetSymmetricStrain.isFinite || targetSymmetricStrain <= 0.0 then
      Left(BasinBridgeAssimilationError.InvalidConfiguration("target symmetric strain"))
    else if !symmetricStrainPercentile.isFinite || symmetricStrainPercentile < 0.0 || symmetricStrainPercentile > 1.0 then
      Left(BasinBridgeAssimilationError.InvalidConfiguration("symmetric strain percentile"))
    else if !minimumAlpha.isFinite || minimumAlpha <= 0.0 || minimumAlpha > 1.0 then
      Left(BasinBridgeAssimilationError.InvalidConfiguration("minimum alpha"))
    else if maximumTrials <= 0 then
      Left(BasinBridgeAssimilationError.InvalidConfiguration("maximum alpha trials"))
    else if !minimumMatchDropMm.isFinite || minimumMatchDropMm < 0.0 then
      Left(BasinBridgeAssimilationError.InvalidConfiguration("minimum match-error drop"))
    else if !maximumCcIncrease.isFinite || maximumCcIncrease < 0.0 then
      Left(BasinBridgeAssimilationError.InvalidConfiguration("maximum true-CC increase"))
    else if !maximumIntegrationInverseErrorMm.isFinite || maximumIntegrationInverseErrorMm < 0.0 then
      Left(BasinBridgeAssimilationError.InvalidConfiguration("maximum integration inverse error"))
    else
      Right(
        new BasinBridgeAssimilationConfig(
          targetSymmetricStrain,
          symmetricStrainPercentile,
          minimumAlpha,
          maximumTrials,
          minimumMatchDropMm,
          maximumCcIncrease,
          flow,
          geometry,
          maximumIntegrationInverseErrorMm
        )
      )

  val default: BasinBridgeAssimilationConfig =
    make().fold(error => throw new IllegalStateException(error.message), identity)

enum BasinBridgeTrialRejection:
  case FlowFailure(error: RegistrationError)
  case IntegrationInaccurate(errorMm: Double)
  case IncrementalGeometry(verdict: GeometryVerdict)
  case AccumulatedGeometry(verdict: GeometryVerdict)
  case MatchErrorNotImproved(current: Double, candidate: Double)
  case CcLossIncreased(current: Double, candidate: Double)

  def message: String =
    this match
      case FlowFailure(error) => s"paired flow failed: ${error.message}"
      case IntegrationInaccurate(errorMm) => s"paired integration error $errorMm mm exceeds the configured limit"
      case IncrementalGeometry(verdict) => s"incremental topology rejected: $verdict"
      case AccumulatedGeometry(verdict) => s"accumulated topology rejected: $verdict"
      case MatchErrorNotImproved(current, candidate) =>
        s"weighted match error did not improve: current=$current candidate=$candidate"
      case CcLossIncreased(current, candidate) =>
        s"true-CC loss increased: current=$current candidate=$candidate"

final case class BasinBridgeTrialDiagnostic(
    alpha: Double,
    accepted: Boolean,
    currentObjective: BasinBridgeObjective,
    candidateObjective: Option[BasinBridgeObjective],
    numerical: Option[NumericalVerdict],
    incremental: Option[GeometryVerdict],
    accumulated: Option[GeometryVerdict],
    rejection: Option[BasinBridgeTrialRejection]
)

final case class BasinBridgeAssimilationResult[W, F, M](
    state: ForwardMidpoint[W, F, M],
    initialAlpha: Double,
    acceptedAlpha: Option[Double],
    initialObjective: BasinBridgeObjective,
    finalObjective: BasinBridgeObjective,
    trials: Vector[BasinBridgeTrialDiagnostic]
):
  def accepted: Boolean = acceptedAlpha.nonEmpty

/** Global, topology-safe assimilation of one projected BasinBridge velocity. */
object BasinBridgeAssimilator:
  def assimilate[W, F, M](
      state: ForwardMidpoint[W, F, M],
      projectedTangent: Velocity[W],
      correspondences: Vector[BasinBridgeCorrespondence],
      objective: ForwardMidpoint[W, F, M] => Either[RegistrationError, BasinBridgeObjective],
      config: BasinBridgeAssimilationConfig = BasinBridgeAssimilationConfig.default
  ): Either[BasinBridgeAssimilationError, BasinBridgeAssimilationResult[W, F, M]] =
    if correspondences.isEmpty then Left(BasinBridgeAssimilationError.EmptyCorrespondences)
    else if state.work.domain != projectedTangent.frame.domain then
      Left(
        BasinBridgeAssimilationError.Registration(
          RegistrationError.FrameMismatch(
            "BasinBridge projected tangent",
            state.work.domain,
            projectedTangent.frame.domain
          )
        )
      )
    else if state.work.grid != projectedTangent.frame.grid then
      Left(BasinBridgeAssimilationError.Registration(RegistrationError.GridMismatch("BasinBridge projected tangent")))
    else
      objective(state)
        .left
        .map(BasinBridgeAssimilationError.ObjectiveEvaluation.apply)
        .flatMap: initialObjective =>
          initialAlpha(projectedTangent.field, config)
            .left
            .map(BasinBridgeAssimilationError.Registration.apply)
            .map: alpha0 =>
              var alpha = alpha0
              var trial = 0
              var current = state
              var acceptedAlpha = Option.empty[Double]
              var finalObjective = initialObjective
              val diagnostics = Vector.newBuilder[BasinBridgeTrialDiagnostic]
              while trial < config.maximumTrials && acceptedAlpha.isEmpty && alpha >= config.minimumAlpha * (1.0 - 1e-12) do
                val result = evaluateTrial(
                  current,
                  projectedTangent,
                  alpha,
                  initialObjective,
                  objective,
                  config
                )
                diagnostics += result.diagnostic
                result.acceptedState match
                  case Some(candidate) =>
                    current = candidate
                    finalObjective = result.diagnostic.candidateObjective.get
                    acceptedAlpha = Some(alpha)
                  case None =>
                    alpha *= 0.5
                trial += 1
              BasinBridgeAssimilationResult(
                current,
                alpha0,
                acceptedAlpha,
                initialObjective,
                finalObjective,
                diagnostics.result()
              )

  private final case class TrialResult[W, F, M](
      acceptedState: Option[ForwardMidpoint[W, F, M]],
      diagnostic: BasinBridgeTrialDiagnostic
  )

  private enum IntegrationOutcome[A]:
    case Accurate(step: HalfStep[A], numerical: NumericalVerdict, maximumVelocityMm: Double)
    case Inaccurate(errorMm: Double)

  private def evaluateTrial[W, F, M](
      current: ForwardMidpoint[W, F, M],
      projectedTangent: Velocity[W],
      alpha: Double,
      currentObjective: BasinBridgeObjective,
      objective: ForwardMidpoint[W, F, M] => Either[RegistrationError, BasinBridgeObjective],
      config: BasinBridgeAssimilationConfig
      ): TrialResult[W, F, M] =
    scaledVelocity(projectedTangent, -alpha) match
      case Left(error) =>
        TrialResult(
          None,
          BasinBridgeTrialDiagnostic(
            alpha,
            accepted = false,
            currentObjective,
            None,
            None,
            None,
            None,
            Some(BasinBridgeTrialRejection.FlowFailure(error))
          )
        )
      case Right(velocity) =>
        integrateAccurately(velocity, config) match
          case Left(error) =>
            TrialResult(
              None,
              BasinBridgeTrialDiagnostic(
                alpha,
                accepted = false,
                currentObjective,
                None,
                None,
                None,
                None,
                Some(BasinBridgeTrialRejection.FlowFailure(error))
              )
            )
          case Right(IntegrationOutcome.Inaccurate(errorMm)) =>
            TrialResult(
              None,
              BasinBridgeTrialDiagnostic(
                alpha,
                accepted = false,
                currentObjective,
                None,
                Some(NumericalVerdict.IncreaseIntegrationDepth(errorMm)),
                None,
                None,
                Some(BasinBridgeTrialRejection.IntegrationInaccurate(errorMm))
              )
            )
          case Right(IntegrationOutcome.Accurate(half, numerical, maximumVelocityMm)) =>
            val geometry = config.geometry.copy(
              interiorMargin = integrationInteriorMargin(current.work.grid, maximumVelocityMm)
            )
            val incremental = ForwardGeometry.incremental(half, geometry)._1
                incremental match
                  case verdict @ GeometryVerdict.IncrementJacobianTooSmall(_) =>
                    TrialResult(
                      None,
                      BasinBridgeTrialDiagnostic(
                        alpha,
                        accepted = false,
                        currentObjective,
                        None,
                        Some(numerical),
                        Some(verdict),
                        None,
                        Some(BasinBridgeTrialRejection.IncrementalGeometry(verdict))
                      )
                    )
                  case verdict @ GeometryVerdict.AccumulatedJacobianTooSmall(_, _) =>
                    TrialResult(
                      None,
                      BasinBridgeTrialDiagnostic(
                        alpha,
                        accepted = false,
                        currentObjective,
                        None,
                        Some(numerical),
                        Some(verdict),
                        None,
                        Some(BasinBridgeTrialRejection.IncrementalGeometry(verdict))
                      )
                    )
                  case GeometryVerdict.Valid =>
                    current.advance(half) match
                      case Left(error) =>
                        TrialResult(
                          None,
                          BasinBridgeTrialDiagnostic(
                            alpha,
                            accepted = false,
                            currentObjective,
                            None,
                            Some(numerical),
                            Some(incremental),
                            None,
                            Some(BasinBridgeTrialRejection.FlowFailure(error))
                          )
                        )
                      case Right(candidate) =>
                        val accumulated = ForwardGeometry.accumulated(candidate, geometry)._1
                        accumulated match
                          case verdict @ GeometryVerdict.IncrementJacobianTooSmall(_) =>
                            TrialResult(
                              None,
                              BasinBridgeTrialDiagnostic(
                                alpha,
                                accepted = false,
                                currentObjective,
                                None,
                                Some(numerical),
                                Some(incremental),
                                Some(verdict),
                                Some(BasinBridgeTrialRejection.AccumulatedGeometry(verdict))
                              )
                            )
                          case verdict @ GeometryVerdict.AccumulatedJacobianTooSmall(_, _) =>
                            TrialResult(
                              None,
                              BasinBridgeTrialDiagnostic(
                                alpha,
                                accepted = false,
                                currentObjective,
                                None,
                                Some(numerical),
                                Some(incremental),
                                Some(verdict),
                                Some(BasinBridgeTrialRejection.AccumulatedGeometry(verdict))
                              )
                            )
                          case GeometryVerdict.Valid =>
                            objective(candidate) match
                              case Left(error) =>
                                TrialResult(
                                  None,
                                  BasinBridgeTrialDiagnostic(
                                    alpha,
                                    accepted = false,
                                    currentObjective,
                                    None,
                                    Some(numerical),
                                    Some(incremental),
                                    Some(accumulated),
                                    Some(BasinBridgeTrialRejection.FlowFailure(error))
                                  )

                                )
                              case Right(candidateObjective) =>
                                if candidateObjective.weightedMatchErrorMm >
                                    currentObjective.weightedMatchErrorMm - config.minimumMatchDropMm then
                                  TrialResult(
                                    None,
                                    BasinBridgeTrialDiagnostic(
                                      alpha,
                                      accepted = false,
                                      currentObjective,
                                      Some(candidateObjective),
                                      Some(numerical),
                                      Some(incremental),
                                      Some(accumulated),
                                      Some(
                                        BasinBridgeTrialRejection.MatchErrorNotImproved(
                                          currentObjective.weightedMatchErrorMm,
                                          candidateObjective.weightedMatchErrorMm
                                        )
                                      )
                                    )
                                  )
                                else if candidateObjective.trueCcLoss >
                                    currentObjective.trueCcLoss + config.maximumCcIncrease then
                                  TrialResult(
                                    None,
                                    BasinBridgeTrialDiagnostic(
                                      alpha,
                                      accepted = false,
                                      currentObjective,
                                      Some(candidateObjective),
                                      Some(numerical),
                                      Some(incremental),
                                      Some(accumulated),
                                      Some(
                                        BasinBridgeTrialRejection.CcLossIncreased(
                                          currentObjective.trueCcLoss,
                                          candidateObjective.trueCcLoss
                                        )
                                      )
                                    )
                                  )
                                else
                                  TrialResult(
                                    Some(candidate),
                                    BasinBridgeTrialDiagnostic(
                                      alpha,
                                      accepted = true,
                                      currentObjective,
                                      Some(candidateObjective),
                                      Some(numerical),
                                      Some(incremental),
                                      Some(accumulated),
                                      None
                                    )
                                  )

  /** Increase squaring depth at a fixed proposal before alpha is backtracked.
    *
    * Integration accuracy is therefore independent of the proposal schedule:
    * a valid alpha is not discarded merely because its first integration depth
    * was insufficient, while an alpha that remains inaccurate at the configured
    * maximum still fails closed.
    */
  private def integrateAccurately[A](
      velocity: Velocity[A],
      config: BasinBridgeAssimilationConfig
  ): Either[RegistrationError, IntegrationOutcome[A]] =
    var minimumDepth = config.flow.minimumSquaringDepth
    var outcome = Option.empty[IntegrationOutcome[A]]
    var failure = Option.empty[RegistrationError]
    while outcome.isEmpty && failure.isEmpty do
      val flowConfig = config.flow.copy(minimumSquaringDepth = minimumDepth)
      PairedScalingAndSquaring.expHalfPair(velocity, flowConfig) match
        case Left(error) => failure = Some(error)
        case Right(flow) =>
          val half = HalfStep.fromPairedFlow(flow)
          val margin = integrationInteriorMargin(
            velocity.frame.grid,
            flow.diagnostics.maximumVelocityNormMm
          )
          val numerical = HalfStepNumerics.evaluate(
            half,
            config.maximumIntegrationInverseErrorMm,
            margin
          )
          numerical match
            case NumericalVerdict.IncreaseIntegrationDepth(errorMm) =>
              val nextDepth = math.max(minimumDepth + 1, flow.diagnostics.squaringDepth + 1)
              if nextDepth > config.flow.maximumSquaringDepth then
                outcome = Some(IntegrationOutcome.Inaccurate(errorMm))
              else minimumDepth = nextDepth
            case _ =>
              outcome = Some(
                IntegrationOutcome.Accurate(
                  half,
                  numerical,
                  flow.diagnostics.maximumVelocityNormMm
                )
              )
    failure match
      case Some(error) => Left(error)
      case None => Right(outcome.get)

  private def scaledVelocity[A](
      velocity: Velocity[A],
      scale: Double
  ): Either[RegistrationError, Velocity[A]] =
    if scale == 1.0 then Right(velocity)
    else
      val grid = velocity.frame.grid
      val values =
        RavelArray.tabulate[Double](grid.shape(0), grid.shape(1), grid.shape(2), 3):
          (x, y, z, component) => velocity.field(x, y, z, component) * scale
      Velocity.make(
        velocity.frame,
        DenseVectorField(grid, values, DenseVectorFieldKind.Displacement)
      )

  private def initialAlpha(
      field: DenseVectorField,
      config: BasinBridgeAssimilationConfig
  ): Either[RegistrationError, Double] =
    val inverse = field.grid.inverseAffine
    val grid = field.grid
    val eligible =
      math.max(0, grid.shape(0) - 2) * math.max(0, grid.shape(1) - 2) * math.max(0, grid.shape(2) - 2)
    if eligible == 0 then Right(1.0)
    else
      val strains = new Array[Double](eligible)
      var count = 0
      var z = 1
      while z < grid.shape(2) - 1 do
        var y = 1
        while y < grid.shape(1) - 1 do
          var x = 1
          while x < grid.shape(0) - 1 do
            val gradient = Array.ofDim[Double](3, 3)
            var component = 0
            while component < 3 do
              val dx = 0.5 * (field(x + 1, y, z, component) - field(x - 1, y, z, component))
              val dy = 0.5 * (field(x, y + 1, z, component) - field(x, y - 1, z, component))
              val dz = 0.5 * (field(x, y, z + 1, component) - field(x, y, z - 1, component))
              var axis = 0
              while axis < 3 do
                gradient(component)(axis) =
                  dx * inverse(0, axis) + dy * inverse(1, axis) + dz * inverse(2, axis)
                axis += 1
              component += 1
            var squared = 0.0
            var row = 0
            while row < 3 do
              var column = 0
              while column < 3 do
                val value = 0.5 * (gradient(row)(column) + gradient(column)(row))
                squared += value * value
                column += 1
              row += 1
            val strain = math.sqrt(squared)
            if !strain.isFinite then return Left(RegistrationError.InvalidField("BasinBridge strain"))
            strains(count) = strain
            count += 1
            x += 1
          y += 1
        z += 1
      scala.util.Sorting.quickSort(strains)
      val position = config.symmetricStrainPercentile * (count - 1).toDouble
      val lower = math.floor(position).toInt
      val upper = math.ceil(position).toInt
      val fraction = position - lower.toDouble
      val percentile = strains(lower) + fraction * (strains(upper) - strains(lower))
      if percentile <= 1e-12 then Right(1.0)
      else
        val alpha = math.max(
          config.minimumAlpha,
          math.min(1.0, config.targetSymmetricStrain / percentile)
        )
        Right(alpha)

  /** Keep boundary fallback outside the inverse-pair error sample.
    *
    * A half-flow may legitimately reach outside a finite lattice even when its
    * interior map is an exact translation. The validity and topology gates
    * still inspect the complete candidate; this margin only prevents the
    * numerical inverse diagnostic from treating that boundary fallback as
    * integration error.
    */
  private def integrationInteriorMargin(grid: GridSpec, maximumVelocityMm: Double): Int =
    val inverse = grid.inverseAffine
    var maximumVoxelRowNorm = 0.0
    var row = 0
    while row < 3 do
      var squared = 0.0
      var column = 0
      while column < 3 do
        val value = inverse(row, column)
        squared += value * value
        column += 1
      maximumVoxelRowNorm = math.max(maximumVoxelRowNorm, math.sqrt(squared))
      row += 1
    val halfDisplacementVoxels = 0.5 * maximumVelocityMm * maximumVoxelRowNorm
    math.max(2, math.ceil(halfDisplacementVoxels).toInt + 2)
