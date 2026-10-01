package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame

private[flashalign] final case class SmallStrainObjectiveLinearization3[
    Pose,
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    dataObjective: Double,
    cache: MatrixFreePatchLinearization3[SmallStrainState3[Pose], Moving, Fixed],
    dataDiagonal: Vector[Double],
    validPatches: Int,
    invalidPatches: Int
)

/** The same frozen projected-patch contract used by PE fitting. Implementors
  * keep sample IDs, weights, loss parameters, support gates and polarity fixed
  * throughout every trust-region trial.
  */
private[flashalign] trait SmallStrainProjectedPatchObjective3[
    Pose,
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
]:
  def linearize(
      state: SmallStrainState3[Pose],
      geometry: GeometryPointOperator3[SmallStrainState3[Pose], Moving, Fixed]
  ): Either[String, SmallStrainObjectiveLinearization3[Pose, Moving, Fixed]]

  def trialDataObjective(
      state: SmallStrainState3[Pose],
      dataAcceptanceLimit: Double
  ): Either[String, PeTrialEvaluation]

  def selectionDataObjective(state: SmallStrainState3[Pose]): Either[String, Double]

private[flashalign] final case class SmallStrainOptimizerConfig(
    trust: PeFieldOptimizerConfig,
    minimumFieldDataDiagonal: Double
)

private[flashalign] object SmallStrainOptimizerConfig:
  def create(
      trust: PeFieldOptimizerConfig,
      minimumFieldDataDiagonal: Double = 1e-12
  ): Either[SmallStrainOptimizerError, SmallStrainOptimizerConfig] =
    if !minimumFieldDataDiagonal.isFinite || minimumFieldDataDiagonal < 0.0 then
      Left(SmallStrainOptimizerError.InvalidConfig("minimum field data diagonal must be finite and nonnegative"))
    else Right(SmallStrainOptimizerConfig(trust, minimumFieldDataDiagonal))

private[flashalign] enum SmallStrainFitStatus derives CanEqual:
  case Converged
  case IterationLimit
  case Stalled
  case InsufficientInformation
  case PriorDominated
  case UnsafeGeometry
  case ObjectiveFailure
  case SolverFailure

private[flashalign] final case class SmallStrainPhaseWork(
    linearizations: Int,
    poseBlockFactorizations: Int,
    solverProducts: Long,
    trialEvaluations: Int,
    trialPatchesEvaluated: Long,
    rejectedTrials: Int,
    acceptedTrials: Int,
    certificateChecks: Int,
    selectionEvaluations: Int,
    imageInterpolationsAtLinearization: Long,
    imageGradientEvaluationsAtLinearization: Long,
    imageInterpolationsInKrylovProducts: Long,
    imageGradientEvaluationsInKrylovProducts: Long
)

private[flashalign] final case class SmallStrainFitResult3[Pose](
    status: SmallStrainFitStatus,
    nonlinearState: Option[SmallStrainState3[Pose]],
    validatedBaseFallback: Pose,
    dataObjective: Double,
    priorObjective: Double,
    selectionObjective: Double,
    fieldDataDiagonalSum: Double,
    nominalCoefficientCount: Int,
    effectiveCoefficientRank: Int,
    certificate: Option[SmallStrainGeometryCertificate],
    work: SmallStrainPhaseWork,
    dataInformationExcludesPriorAndDamping: Boolean,
    detail: String,
    fieldUnits: String,
    fieldSemantics: String
):
  def successfulNonlinearFit: Boolean = status == SmallStrainFitStatus.Converged

private[flashalign] final class SmallStrainOptimizer3[
    Pose,
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    model: SmallStrainModel3[Pose, Moving, Fixed],
    geometryConfig: SmallStrainGeometryConfig,
    geometry: GeometryPointOperator3[SmallStrainState3[Pose], Moving, Fixed],
    objective: SmallStrainProjectedPatchObjective3[Pose, Moving, Fixed],
    probes: WorldPointBatch3[Moving],
    config: SmallStrainOptimizerConfig
):
  def fit(initial: SmallStrainState3[Pose], validatedBaseFallback: Pose): SmallStrainFitResult3[Pose] =
    val counters = new SmallStrainWorkCounters
    objective.selectionDataObjective(initial) match
      case Left(detail) => failure(SmallStrainFitStatus.ObjectiveFailure, validatedBaseFallback, counters, detail)
      case Right(initialSelection) =>
        model.prior(initial) match
          case Left(error) => failure(SmallStrainFitStatus.ObjectiveFailure, validatedBaseFallback, counters, error.message)
          case Right(initialPrior) =>
            counters.selectionEvaluations += 1
            var state = initial
            var bestState = initial
            var bestSelection = initialSelection + initialPrior.value
            var bestData = initialSelection
            var bestPrior = initialPrior.value
            var bestDataDiagonal = 0.0
            var damping = config.trust.initialDamping
            var previousObjective = Double.PositiveInfinity
            var iteration = 0
            var terminal = Option.empty[SmallStrainFitResult3[Pose]]
            while iteration < config.trust.maximumLinearizations && terminal.isEmpty do
              objective.linearize(state, geometry) match
                case Left(detail) => terminal = Some(failure(SmallStrainFitStatus.ObjectiveFailure, validatedBaseFallback, counters, detail))
                case Right(current) =>
                  counters.linearizations += 1
                  counters.imageInterpolations += current.cache.diagnostics.interpolationCallsAtLinearization
                  counters.imageGradients += current.cache.diagnostics.gradientCallsAtLinearization
                  val dataDiagonal = current.dataDiagonal.drop(model.poseParameterCount).sum
                  if current.validPatches <= 0 then
                    terminal = Some(failure(
                      SmallStrainFitStatus.InsufficientInformation,
                      validatedBaseFallback,
                      counters,
                      s"no valid projected patches; ${current.invalidPatches} invalid"
                    ))
                  else if current.dataDiagonal.length != model.parameterCount ||
                      current.dataDiagonal.exists(value => !value.isFinite || value < 0.0)
                  then terminal = Some(failure(SmallStrainFitStatus.ObjectiveFailure, validatedBaseFallback, counters, "invalid data-only diagonal"))
                  else if dataDiagonal <= config.minimumFieldDataDiagonal then
                    terminal = Some(failure(
                      SmallStrainFitStatus.PriorDominated,
                      validatedBaseFallback,
                      counters,
                      s"field data-only diagonal $dataDiagonal does not exceed ${config.minimumFieldDataDiagonal}; prior and damping excluded"
                    ))
                  else
                    linearizedStep(state, current, damping, validatedBaseFallback, counters, dataDiagonal) match
                      case Left(result) => terminal = Some(result)
                      case Right(accepted) =>
                        state = accepted.state
                        damping = accepted.nextDamping
                        objective.selectionDataObjective(state) match
                          case Left(detail) => terminal = Some(failure(SmallStrainFitStatus.ObjectiveFailure, validatedBaseFallback, counters, detail))
                          case Right(selectionData) =>
                            counters.selectionEvaluations += 1
                            val totalSelection = selectionData + accepted.priorObjective
                            if totalSelection < bestSelection then
                              bestState = state
                              bestSelection = totalSelection
                              bestData = selectionData
                              bestPrior = accepted.priorObjective
                              bestDataDiagonal = dataDiagonal
                            val improvement = previousObjective - accepted.totalObjective
                            previousObjective = accepted.totalObjective
                            if accepted.rmsDisplacementMm <= config.trust.stepToleranceRmsMm &&
                                math.abs(improvement) <= config.trust.objectiveTolerance
                            then terminal = Some(success(
                              SmallStrainFitStatus.Converged,
                              bestState,
                              validatedBaseFallback,
                              bestData,
                              bestPrior,
                              bestSelection,
                              bestDataDiagonal,
                              counters,
                              "physical step and objective improvement converged"
                            ))
              iteration += 1
            terminal.getOrElse(success(
              SmallStrainFitStatus.IterationLimit,
              bestState,
              validatedBaseFallback,
              bestData,
              bestPrior,
              bestSelection,
              bestDataDiagonal,
              counters,
              "maximum linearizations reached; nonlinear result is not marked converged"
            ))

  private def linearizedStep(
      state: SmallStrainState3[Pose],
      current: SmallStrainObjectiveLinearization3[Pose, Moving, Fixed],
      startingDamping: Double,
      fallback: Pose,
      counters: SmallStrainWorkCounters,
      fieldDataDiagonal: Double
  ): Either[SmallStrainFitResult3[Pose], AcceptedSmallStrainStep[Pose]] =
    val cacheWorkspace = current.cache.newWorkspace()
    val data = new CachedGeometryDataOperator(current.cache, cacheWorkspace, geometry)
    model.prior(state) match
      case Left(error) => Left(failure(SmallStrainFitStatus.ObjectiveFailure, fallback, counters, error.message))
      case Right(prior) =>
        val undamped = new DataFieldPriorOperator(data, model.basis.priorPrecision, model.poseParameterCount)
        val metric = new GeometryPhysicalMetricOperator3(geometry, state, probes)
        val rhs = new Array[Double](model.parameterCount)
        current.cache.rightHandSide(rhs, cacheWorkspace) match
          case Left(error) => Left(failure(SmallStrainFitStatus.ObjectiveFailure, fallback, counters, error.message))
          case Right(()) =>
            var index = 0
            while index < rhs.length do
              rhs(index) -= prior.gradient(index)
              index += 1
            val rhsNorm = math.sqrt(rhs.foldLeft(0.0)((sum, value) => sum + value * value))
            metric.diagonal() match
              case Left(detail) => Left(failure(SmallStrainFitStatus.SolverFailure, fallback, counters, detail))
              case Right(metricDiagonal) =>
                PoseEliminatedLinearization.create(undamped, metric, rhs.toVector, model.poseParameterCount, model.gaugeConventionId) match
                  case Left(error) => Left(failure(SmallStrainFitStatus.SolverFailure, fallback, counters, error.message))
                  case Right(system) =>
                    counters.certificateChecks += 1
                    var damping = startingDamping
                    var attempt = 0
                    var accepted = Option.empty[AcceptedSmallStrainStep[Pose]]
                    var detail = "no acceptable trial"
                    var unsafe = false
                    var smallestRms = Double.PositiveInfinity
                    while attempt < config.trust.maximumTrialAttempts && accepted.isEmpty && damping <= config.trust.maximumDamping do
                      val preconditioner = PriorDataDiagonalPreconditioner.create(
                        Vector.tabulate(model.fieldParameterCount)(field => model.basis.priorPrecision(field, field)),
                        current.dataDiagonal.drop(model.poseParameterCount),
                        metricDiagonal.drop(model.poseParameterCount).map(_ * damping)
                      )
                      val solved = for
                        pre <- preconditioner.left.map(error => PoseEliminationError.Pcg(error))
                        schur <- system.attempt(damping, pre, config.trust.solver)
                        result <- schur.solve(schur.newWorkspace())
                      yield result
                      counters.poseBlockFactorizations += 1
                      solved match
                        case Left(error) => detail = error.message
                        case Right(direction) =>
                          counters.solverProducts += direction.diagnostics.pcg.operatorProducts
                          if direction.fieldTermination != GalePcgTermination.Breakdown &&
                              direction.fieldTermination != GalePcgTermination.ResidualRejected
                          then
                            val raw = direction.values.toArray
                            metric.linearDisplacement(raw) match
                              case Left(error) => detail = error
                              case Right(linear) =>
                                val scale = math.min(1.0, math.min(
                                  config.trust.trustRadiusRmsMm / math.max(linear._1, 1e-30),
                                  config.trust.maximumProbeDisplacementMm / math.max(linear._2, 1e-30)
                                ))
                                val requested = raw.map(_ * scale)
                                propose(state, requested) match
                                  case Left(error) =>
                                    detail = error
                                    unsafe ||= error.contains("uncertified")
                                  case Right(candidate) =>
                                    counters.certificateChecks += 1
                                    metric.actualDisplacement(state, candidate._1) match
                                      case Left(error) => detail = error
                                      case Right(actual) =>
                                        smallestRms = math.min(smallestRms, actual._1)
                                        if actual._1 > config.trust.trustRadiusRmsMm * (1.0 + 1e-10) ||
                                            actual._2 > config.trust.maximumProbeDisplacementMm * (1.0 + 1e-10)
                                        then detail = "actual proposal displacement exceeds physical trust region"
                                        else prediction(undamped, rhs, candidate._2) match
                                          case Left(error) => detail = error
                                          case Right(predicted) if predicted <= 0.0 => detail = s"nonpositive undamped prediction $predicted"
                                          case Right(predicted) =>
                                            model.prior(candidate._1) match
                                              case Left(error) => detail = error.message
                                              case Right(candidatePrior) =>
                                                val currentTotal = current.dataObjective + prior.value
                                                val acceptanceLimit = currentTotal - config.trust.acceptanceRatio * predicted
                                                counters.trialEvaluations += 1
                                                objective.trialDataObjective(candidate._1, acceptanceLimit - candidatePrior.value) match
                                                  case Left(error) => detail = error
                                                  case Right(trial) =>
                                                    counters.trialPatches += trial.patchesEvaluated.toLong
                                                    trial.disposition match
                                                      case PeTrialDisposition.RejectedEarly => detail = "loss-only trial rejected by a nonnegative lower bound"
                                                      case PeTrialDisposition.Complete =>
                                                        val candidateTotal = trial.dataObjectiveOrLowerBound + candidatePrior.value
                                                        if candidateTotal <= acceptanceLimit then
                                                          val ratio = (currentTotal - candidateTotal) / predicted
                                                          val nextDamping =
                                                            if ratio >= config.trust.highGainRatio then math.max(config.trust.minimumDamping, damping * config.trust.acceptedDampingFactor)
                                                            else damping
                                                          accepted = Some(AcceptedSmallStrainStep(candidate._1, candidatePrior.value, candidateTotal, actual._1, nextDamping))
                                                          counters.acceptedTrials += 1
                                                        else detail = "complete loss-only trial failed gain acceptance"
                      if accepted.isEmpty then
                        counters.rejectedTrials += 1
                        damping *= config.trust.rejectedDampingFactor
                      attempt += 1
                    accepted match
                      case Some(value) => Right(value)
                      case None if rhsNorm <= config.trust.gradientTolerance && smallestRms <= config.trust.stepToleranceRmsMm =>
                        objective.selectionDataObjective(state) match
                          case Left(error) => Left(failure(SmallStrainFitStatus.ObjectiveFailure, fallback, counters, error))
                          case Right(selectionData) =>
                            counters.selectionEvaluations += 1
                            Left(success(
                              SmallStrainFitStatus.Converged,
                              state,
                              fallback,
                              current.dataObjective,
                              prior.value,
                              selectionData + prior.value,
                              fieldDataDiagonal,
                              counters,
                              s"stationary projected gradient $rhsNorm and physical step $smallestRms mm"
                            ))
                      case None =>
                        val status = if unsafe then SmallStrainFitStatus.UnsafeGeometry else SmallStrainFitStatus.Stalled
                        Left(failure(status, fallback, counters, s"all trust-region trials rejected: $detail; projected gradient norm=$rhsNorm"))

  private def propose(
      state: SmallStrainState3[Pose],
      requested: Array[Double]
  ): Either[String, (SmallStrainState3[Pose], Array[Double])] =
    GeometryDirection3.create(geometry.modelId, geometry.basisId, requested).left.map(_.message)
      .flatMap(direction => geometry.propose(state, direction).left.map(_.message))
      .flatMap { proposal =>
        val actual = proposal.actualDirection.snapshot.toArray
        val changed = actual.indices.exists(index => math.abs(actual(index) - requested(index)) > 1e-12)
        if changed then Left("geometry proposal changed the requested step") else Right(proposal.state -> actual)
      }

  private def prediction(operator: ArraySymmetricOperator, rhs: Array[Double], step: Array[Double]): Either[String, Double] =
    val product = new Array[Double](step.length)
    operator(step, product).map { _ =>
      var linear = 0.0
      var quadratic = 0.0
      var index = 0
      while index < step.length do
        linear += rhs(index) * step(index)
        quadratic += step(index) * product(index)
        index += 1
      linear - 0.5 * quadratic
    }

  private def success(
      status: SmallStrainFitStatus,
      state: SmallStrainState3[Pose],
      fallback: Pose,
      data: Double,
      prior: Double,
      selection: Double,
      dataDiagonal: Double,
      counters: SmallStrainWorkCounters,
      detail: String
  ): SmallStrainFitResult3[Pose] =
    val certificate = SmallStrainGeometryCertificate.evaluate(model, state, geometryConfig.certificate).toOption
    result(status, Some(state), fallback, data, prior, selection, dataDiagonal, certificate, counters, detail)

  private def failure(status: SmallStrainFitStatus, fallback: Pose, counters: SmallStrainWorkCounters, detail: String): SmallStrainFitResult3[Pose] =
    result(status, None, fallback, Double.NaN, Double.NaN, Double.NaN, Double.NaN, None, counters, detail)

  private def result(
      status: SmallStrainFitStatus,
      state: Option[SmallStrainState3[Pose]],
      fallback: Pose,
      data: Double,
      prior: Double,
      selection: Double,
      dataDiagonal: Double,
      certificate: Option[SmallStrainGeometryCertificate],
      counters: SmallStrainWorkCounters,
      detail: String
  ): SmallStrainFitResult3[Pose] =
    SmallStrainFitResult3(
      status,
      state,
      fallback,
      data,
      prior,
      selection,
      dataDiagonal,
      model.nominalFieldParameterCount,
      model.effectiveFieldParameterCount,
      certificate,
      counters.snapshot,
      dataInformationExcludesPriorAndDamping = true,
      detail,
      model.coefficientUnits,
      model.interpretation
    )

private[flashalign] object SmallStrainOptimizer3:
  def compile[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: SmallStrainModel3[Pose, Moving, Fixed],
      geometryConfig: SmallStrainGeometryConfig,
      objective: SmallStrainProjectedPatchObjective3[Pose, Moving, Fixed],
      probes: WorldPointBatch3[Moving],
      config: SmallStrainOptimizerConfig
  ): Either[SmallStrainOptimizerError, SmallStrainOptimizer3[Pose, Moving, Fixed]] =
    if !probes.frame.sameRuntimeOwnerAs(model.moving) then Left(SmallStrainOptimizerError.InvalidConfig("physical probes belong to another moving frame"))
    else if probes.size <= 0 then Left(SmallStrainOptimizerError.InvalidConfig("physical probes must be nonempty"))
    else Right(new SmallStrainOptimizer3(
      model,
      geometryConfig,
      GeometryPointOperator3.smallStrain(model, geometryConfig),
      objective,
      probes,
      config
    ))

private final case class AcceptedSmallStrainStep[Pose](
    state: SmallStrainState3[Pose],
    priorObjective: Double,
    totalObjective: Double,
    rmsDisplacementMm: Double,
    nextDamping: Double
)

private final class SmallStrainWorkCounters:
  var linearizations = 0
  var poseBlockFactorizations = 0
  var solverProducts = 0L
  var trialEvaluations = 0
  var trialPatches = 0L
  var rejectedTrials = 0
  var acceptedTrials = 0
  var certificateChecks = 0
  var selectionEvaluations = 0
  var imageInterpolations = 0L
  var imageGradients = 0L

  def snapshot: SmallStrainPhaseWork = SmallStrainPhaseWork(
    linearizations,
    poseBlockFactorizations,
    solverProducts,
    trialEvaluations,
    trialPatches,
    rejectedTrials,
    acceptedTrials,
    certificateChecks,
    selectionEvaluations,
    imageInterpolations,
    imageGradients,
    0L,
    0L
  )

private[flashalign] sealed trait SmallStrainOptimizerError derives CanEqual:
  def message: String

private[flashalign] object SmallStrainOptimizerError:
  final case class InvalidConfig(detail: String) extends SmallStrainOptimizerError:
    val message = s"invalid small-strain optimizer configuration: $detail"
