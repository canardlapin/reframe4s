package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame
import reframe4s.lie.Rigid3

private[flashalign] final case class PeFieldObjectiveLinearization3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    dataObjective: Double,
    cache: MatrixFreePatchLinearization3[PeFieldState3[Moving, Fixed], Moving, Fixed],
    dataDiagonal: Vector[Double],
    validPatches: Int,
    invalidPatches: Int
)

private[flashalign] enum PeTrialDisposition derives CanEqual:
  case Complete, RejectedEarly

private[flashalign] final case class PeTrialEvaluation(
    disposition: PeTrialDisposition,
    dataObjectiveOrLowerBound: Double,
    patchesEvaluated: Int,
    totalPatches: Int
)

/** Frozen projected-patch objective boundary. Implementations must use the same
  * sample IDs, fixed weights, polarity, gates and robust loss throughout one
  * optimizer trial. Losses are nonnegative, so `RejectedEarly` is only a lower
  * bound rejection and can never be an approximate acceptance.
  */
private[flashalign] trait PeProjectedPatchObjective3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
]:
  def linearize(
      state: PeFieldState3[Moving, Fixed],
      geometry: GeometryPointOperator3[PeFieldState3[Moving, Fixed], Moving, Fixed]
  ): Either[String, PeFieldObjectiveLinearization3[Moving, Fixed]]

  def trialDataObjective(
      state: PeFieldState3[Moving, Fixed],
      dataAcceptanceLimit: Double
  ): Either[String, PeTrialEvaluation]

  def selectionDataObjective(state: PeFieldState3[Moving, Fixed]): Either[String, Double]

private[flashalign] final case class PeFieldOptimizerConfig(
    maximumLinearizations: Int,
    maximumTrialAttempts: Int,
    initialDamping: Double,
    minimumDamping: Double,
    maximumDamping: Double,
    rejectedDampingFactor: Double,
    acceptedDampingFactor: Double,
    acceptanceRatio: Double,
    highGainRatio: Double,
    trustRadiusRmsMm: Double,
    maximumProbeDisplacementMm: Double,
    objectiveTolerance: Double,
    stepToleranceRmsMm: Double,
    gradientTolerance: Double,
    solver: GalePcgConfig
)

private[flashalign] object PeFieldOptimizerConfig:
  def create(
      maximumLinearizations: Int = 30,
      maximumTrialAttempts: Int = 8,
      initialDamping: Double = 1e-2,
      minimumDamping: Double = 1e-8,
      maximumDamping: Double = 1e8,
      rejectedDampingFactor: Double = 4.0,
      acceptedDampingFactor: Double = 0.5,
      acceptanceRatio: Double = 0.1,
      highGainRatio: Double = 0.75,
      trustRadiusRmsMm: Double = 1.5,
      maximumProbeDisplacementMm: Double = 3.0,
      objectiveTolerance: Double = 1e-8,
      stepToleranceRmsMm: Double = 1e-5,
      gradientTolerance: Double = 1e-8,
      solver: GalePcgConfig
  ): Either[PeFieldOptimizerError, PeFieldOptimizerConfig] =
    if maximumLinearizations <= 0 || maximumTrialAttempts <= 0 then
      Left(PeFieldOptimizerError.InvalidConfig("iteration and trial limits must be positive"))
    else if !minimumDamping.isFinite || minimumDamping <= 0.0 ||
        !initialDamping.isFinite || initialDamping < minimumDamping ||
        !maximumDamping.isFinite || maximumDamping < initialDamping
    then Left(PeFieldOptimizerError.InvalidConfig("damping must satisfy 0 < minimum <= initial <= maximum"))
    else if !rejectedDampingFactor.isFinite || rejectedDampingFactor <= 1.0 ||
        !acceptedDampingFactor.isFinite || acceptedDampingFactor <= 0.0 || acceptedDampingFactor >= 1.0
    then Left(PeFieldOptimizerError.InvalidConfig("damping factors must increase on rejection and decrease on acceptance"))
    else if !acceptanceRatio.isFinite || acceptanceRatio <= 0.0 || acceptanceRatio >= 1.0 ||
        !highGainRatio.isFinite || highGainRatio <= acceptanceRatio || highGainRatio > 1.0
    then Left(PeFieldOptimizerError.InvalidConfig("gain ratios must satisfy 0 < acceptance < high <= 1"))
    else if !trustRadiusRmsMm.isFinite || trustRadiusRmsMm <= 0.0 ||
        !maximumProbeDisplacementMm.isFinite || maximumProbeDisplacementMm <= 0.0
    then Left(PeFieldOptimizerError.InvalidConfig("physical trust limits must be finite and positive"))
    else if !objectiveTolerance.isFinite || objectiveTolerance < 0.0 ||
        !stepToleranceRmsMm.isFinite || stepToleranceRmsMm < 0.0 ||
        !gradientTolerance.isFinite || gradientTolerance < 0.0
    then Left(PeFieldOptimizerError.InvalidConfig("convergence tolerances must be finite and nonnegative"))
    else
      Right(
        PeFieldOptimizerConfig(
          maximumLinearizations,
          maximumTrialAttempts,
          initialDamping,
          minimumDamping,
          maximumDamping,
          rejectedDampingFactor,
          acceptedDampingFactor,
          acceptanceRatio,
          highGainRatio,
          trustRadiusRmsMm,
          maximumProbeDisplacementMm,
          objectiveTolerance,
          stepToleranceRmsMm,
          gradientTolerance,
          solver
        )
      )

private[flashalign] enum PeFieldFitStatus derives CanEqual:
  case Converged
  case IterationLimit
  case Stalled
  case InsufficientInformation
  case ObjectiveFailure
  case SolverFailure

private[flashalign] final case class PeFieldPhaseWork(
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

private[flashalign] final case class PeFieldFitResult3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    status: PeFieldFitStatus,
    nonlinearState: Option[PeFieldState3[Moving, Fixed]],
    validatedRigidFallback: Rigid3[Moving, Fixed],
    dataObjective: Double,
    priorObjective: Double,
    selectionObjective: Double,
    nominalCoefficientCount: Int,
    effectiveCoefficientRank: Int,
    certificate: Option[PeGeometryCertificate],
    work: PeFieldPhaseWork,
    detail: String,
    fieldUnits: String,
    fieldSemantics: String,
    dropoutRecoveryClaimed: Boolean
):
  def successfulNonlinearFit: Boolean = status == PeFieldFitStatus.Converged

private[flashalign] final class PeFieldOptimizer3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    model: PeFieldModel3[Moving, Fixed],
    geometryConfig: PeGeometryConfig,
    geometry: GeometryPointOperator3[PeFieldState3[Moving, Fixed], Moving, Fixed],
    objective: PeProjectedPatchObjective3[Moving, Fixed],
    probes: WorldPointBatch3[Moving],
    config: PeFieldOptimizerConfig
):
  def fit(
      initial: PeFieldState3[Moving, Fixed],
      validatedRigidFallback: Rigid3[Moving, Fixed]
  ): PeFieldFitResult3[Moving, Fixed] =
    val counters = new PeWorkCounters
    val initialSelection = objective.selectionDataObjective(initial)
    initialSelection match
      case Left(detail) => failure(PeFieldFitStatus.ObjectiveFailure, validatedRigidFallback, counters, detail)
      case Right(selection) =>
        model.prior(initial) match
          case Left(error) => failure(PeFieldFitStatus.ObjectiveFailure, validatedRigidFallback, counters, error.message)
          case Right(initialPrior) =>
            counters.selectionEvaluations += 1
            var state = initial
            var bestState = initial
            var bestSelection = selection + initialPrior.value
            var bestData = selection
            var bestPrior = initialPrior.value
            var damping = config.initialDamping
            var previousObjective = Double.PositiveInfinity
            var linearizationIndex = 0
            var terminal = Option.empty[PeFieldFitResult3[Moving, Fixed]]
            while linearizationIndex < config.maximumLinearizations && terminal.isEmpty do
              objective.linearize(state, geometry) match
                case Left(detail) =>
                  terminal = Some(
                    failure(PeFieldFitStatus.ObjectiveFailure, validatedRigidFallback, counters, detail)
                  )
                case Right(current) =>
                  counters.linearizations += 1
                  counters.imageInterpolations += current.cache.diagnostics.interpolationCallsAtLinearization
                  counters.imageGradients += current.cache.diagnostics.gradientCallsAtLinearization
                  if current.validPatches <= 0 then
                    terminal = Some(
                      failure(
                        PeFieldFitStatus.InsufficientInformation,
                        validatedRigidFallback,
                        counters,
                        s"no valid projected patches; ${current.invalidPatches} invalid"
                      )
                    )
                  else if current.dataDiagonal.length != model.parameterCount ||
                      current.dataDiagonal.exists(value => !value.isFinite || value < 0.0)
                  then
                    terminal = Some(
                      failure(
                        PeFieldFitStatus.ObjectiveFailure,
                        validatedRigidFallback,
                        counters,
                        "linearization supplied an invalid data diagonal"
                      )
                    )
                  else
                    terminal = linearizedStep(
                      state,
                      current,
                      damping,
                      validatedRigidFallback,
                      counters
                    ) match
                      case Left(result) => Some(result)
                      case Right(accepted) =>
                        state = accepted.state
                        damping = accepted.nextDamping
                        val selectionResult = objective.selectionDataObjective(state)
                        selectionResult match
                          case Left(detail) =>
                            Some(failure(PeFieldFitStatus.ObjectiveFailure, validatedRigidFallback, counters, detail))
                          case Right(selectionData) =>
                            counters.selectionEvaluations += 1
                            val totalSelection = selectionData + accepted.priorObjective
                            if totalSelection < bestSelection then
                              bestState = state
                              bestSelection = totalSelection
                              bestData = selectionData
                              bestPrior = accepted.priorObjective
                            val improvement = previousObjective - accepted.totalObjective
                            previousObjective = accepted.totalObjective
                            if accepted.rmsDisplacementMm <= config.stepToleranceRmsMm &&
                                math.abs(improvement) <= config.objectiveTolerance
                            then
                              Some(
                                success(
                                  PeFieldFitStatus.Converged,
                                  bestState,
                                  validatedRigidFallback,
                                  bestData,
                                  bestPrior,
                                  bestSelection,
                                  counters,
                                  "physical step and objective improvement converged"
                                )
                              )
                            else None
              linearizationIndex += 1
            terminal.getOrElse(
              success(
                PeFieldFitStatus.IterationLimit,
                bestState,
                validatedRigidFallback,
                bestData,
                bestPrior,
                bestSelection,
                counters,
                "maximum linearizations reached; nonlinear result is not marked converged"
              )
            )

  private def linearizedStep(
      state: PeFieldState3[Moving, Fixed],
      current: PeFieldObjectiveLinearization3[Moving, Fixed],
      startingDamping: Double,
      fallback: Rigid3[Moving, Fixed],
      counters: PeWorkCounters
  ): Either[PeFieldFitResult3[Moving, Fixed], AcceptedPeStep[Moving, Fixed]] =
    val cacheWorkspace = current.cache.newWorkspace()
    val data = new CachedGeometryDataOperator(current.cache, cacheWorkspace, geometry)
    model.prior(state) match
      case Left(error) => Left(failure(PeFieldFitStatus.ObjectiveFailure, fallback, counters, error.message))
      case Right(prior) =>
        val undamped = new DataFieldPriorOperator(data, model.priorPrecision, model.poseParameterCount)
        val metric = new GeometryPhysicalMetricOperator3(geometry, state, probes)
        val rhs = new Array[Double](model.parameterCount)
        current.cache.rightHandSide(rhs, cacheWorkspace) match
          case Left(error) => Left(failure(PeFieldFitStatus.ObjectiveFailure, fallback, counters, error.message))
          case Right(()) =>
            var index = 0
            while index < rhs.length do
              rhs(index) -= prior.gradient(index)
              index += 1
            val rhsNorm = math.sqrt(rhs.foldLeft(0.0)((sum, value) => sum + value * value))
            metric.diagonal() match
              case Left(detail) => Left(failure(PeFieldFitStatus.SolverFailure, fallback, counters, detail))
              case Right(metricDiagonal) =>
                PoseEliminatedLinearization.create(
                  undamped,
                  metric,
                  rhs.toVector,
                  model.poseParameterCount,
                  model.gaugeConventionId
                ) match
                  case Left(error) => Left(failure(PeFieldFitStatus.SolverFailure, fallback, counters, error.message))
                  case Right(system) =>
                    counters.certificateChecks += 1
                    var damping = startingDamping
                    var attemptIndex = 0
                    var accepted = Option.empty[AcceptedPeStep[Moving, Fixed]]
                    var solverDetail = "no acceptable trial"
                    var smallestRmsDisplacement = Double.PositiveInfinity
                    while attemptIndex < config.maximumTrialAttempts && accepted.isEmpty && damping <= config.maximumDamping do
                      val fieldPrior = Vector.tabulate(model.fieldParameterCount)(field =>
                        model.priorPrecision(field, field)
                      )
                      val fieldData = current.dataDiagonal.drop(model.poseParameterCount)
                      val fieldDamping = metricDiagonal.drop(model.poseParameterCount).map(_ * damping)
                      val preconditioner = PriorDataDiagonalPreconditioner.create(fieldPrior, fieldData, fieldDamping)
                      val attempted = for
                        pre <- preconditioner.left.map(error => PoseEliminationError.Pcg(error))
                        schur <- system.attempt(damping, pre, config.solver)
                        solved <- schur.solve(schur.newWorkspace())
                      yield solved
                      counters.poseBlockFactorizations += 1
                      attempted match
                        case Left(error) => solverDetail = error.message
                        case Right(solved) =>
                          counters.solverProducts += solved.diagnostics.pcg.operatorProducts
                          solverDetail = solved.fieldTermination.toString
                          if solved.fieldTermination != GalePcgTermination.Breakdown &&
                              solved.fieldTermination != GalePcgTermination.ResidualRejected
                          then
                            val raw = solved.values.toArray
                            metric.linearDisplacement(raw) match
                              case Left(detail) => solverDetail = detail
                              case Right(linearDisplacement) =>
                                val scale = math.min(
                                  1.0,
                                  math.min(
                                    config.trustRadiusRmsMm / math.max(linearDisplacement._1, 1e-30),
                                    config.maximumProbeDisplacementMm / math.max(linearDisplacement._2, 1e-30)
                                  )
                                )
                                val requested = raw.map(_ * scale)
                                propose(state, requested) match
                                  case Left(detail) => solverDetail = detail
                                  case Right(candidate) =>
                                    counters.certificateChecks += 1
                                    metric.actualDisplacement(state, candidate._1) match
                                      case Left(detail) => solverDetail = detail
                                      case Right(actualDisplacement) =>
                                        smallestRmsDisplacement = math.min(smallestRmsDisplacement, actualDisplacement._1)
                                        if actualDisplacement._1 > config.trustRadiusRmsMm * (1.0 + 1e-10) ||
                                            actualDisplacement._2 > config.maximumProbeDisplacementMm * (1.0 + 1e-10)
                                        then solverDetail = "actual proposal displacement exceeds physical trust region"
                                        else
                                          prediction(undamped, rhs, candidate._2) match
                                            case Left(detail) => solverDetail = detail
                                            case Right(predicted) if predicted <= 0.0 =>
                                              solverDetail = s"nonpositive undamped prediction $predicted"
                                            case Right(predicted) =>
                                              model.prior(candidate._1) match
                                                case Left(error) => solverDetail = error.message
                                                case Right(candidatePrior) =>
                                                  val currentTotal = current.dataObjective + prior.value
                                                  val acceptanceLimit = currentTotal - config.acceptanceRatio * predicted
                                                  val dataLimit = acceptanceLimit - candidatePrior.value
                                                  counters.trialEvaluations += 1
                                                  objective.trialDataObjective(candidate._1, dataLimit) match
                                                    case Left(detail) => solverDetail = detail
                                                    case Right(trial) =>
                                                      counters.trialPatches += trial.patchesEvaluated.toLong
                                                      trial.disposition match
                                                        case PeTrialDisposition.RejectedEarly =>
                                                          solverDetail = "loss-only trial rejected by a nonnegative lower bound"
                                                        case PeTrialDisposition.Complete =>
                                                          val candidateTotal = trial.dataObjectiveOrLowerBound + candidatePrior.value
                                                          if candidateTotal <= acceptanceLimit then
                                                            val actualReduction = currentTotal - candidateTotal
                                                            val ratio = actualReduction / predicted
                                                            val nextDamping =
                                                              if ratio >= config.highGainRatio then
                                                                math.max(config.minimumDamping, damping * config.acceptedDampingFactor)
                                                              else damping
                                                            accepted = Some(
                                                              AcceptedPeStep(
                                                                candidate._1,
                                                                candidatePrior.value,
                                                                candidateTotal,
                                                                actualDisplacement._1,
                                                                nextDamping
                                                              )
                                                            )
                                                            counters.acceptedTrials += 1
                                                          else solverDetail = "complete loss-only trial failed gain acceptance"
                      if accepted.isEmpty then
                        counters.rejectedTrials += 1
                        damping *= config.rejectedDampingFactor
                      attemptIndex += 1
                    accepted match
                      case Some(step) => Right(step)
                      case None if rhsNorm <= config.gradientTolerance &&
                          smallestRmsDisplacement <= config.stepToleranceRmsMm =>
                        objective.selectionDataObjective(state) match
                          case Left(detail) =>
                            Left(failure(PeFieldFitStatus.ObjectiveFailure, fallback, counters, detail))
                          case Right(selectionData) =>
                            counters.selectionEvaluations += 1
                            Left(
                              success(
                                PeFieldFitStatus.Converged,
                                state,
                                fallback,
                                current.dataObjective,
                                prior.value,
                                selectionData + prior.value,
                                counters,
                                s"stationary projected gradient $rhsNorm and physical step $smallestRmsDisplacement mm"
                              )
                            )
                      case None =>
                        Left(
                          failure(
                            PeFieldFitStatus.Stalled,
                            fallback,
                            counters,
                            s"all trust-region trials rejected: $solverDetail; projected gradient norm=$rhsNorm; smallest RMS step=$smallestRmsDisplacement mm"
                          )
                        )

  private def propose(
      state: PeFieldState3[Moving, Fixed],
      requested: Array[Double]
  ): Either[String, (PeFieldState3[Moving, Fixed], Array[Double])] =
    GeometryDirection3.create(geometry.modelId, geometry.basisId, requested)
      .left.map(_.message)
      .flatMap(direction => geometry.propose(state, direction).left.map(_.message))
      .flatMap { proposal =>
        val actual = proposal.actualDirection.snapshot.toArray
        val changed = actual.indices.exists(index => math.abs(actual(index) - requested(index)) > 1e-12)
        if changed then Left("geometry proposal changed the requested step")
        else Right(proposal.state -> actual)
      }

  private def prediction(
      undamped: ArraySymmetricOperator,
      rhs: Array[Double],
      step: Array[Double]
  ): Either[String, Double] =
    val product = new Array[Double](step.length)
    undamped(step, product).map { _ =>
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
      status: PeFieldFitStatus,
      state: PeFieldState3[Moving, Fixed],
      fallback: Rigid3[Moving, Fixed],
      data: Double,
      prior: Double,
      selection: Double,
      counters: PeWorkCounters,
      detail: String
  ): PeFieldFitResult3[Moving, Fixed] =
    val certificate = PeGeometryCertificate.evaluate(model, state, geometryConfig.certificate).toOption
    result(status, Some(state), fallback, data, prior, selection, certificate, counters, detail)

  private def failure(
      status: PeFieldFitStatus,
      fallback: Rigid3[Moving, Fixed],
      counters: PeWorkCounters,
      detail: String
  ): PeFieldFitResult3[Moving, Fixed] =
    result(status, None, fallback, Double.NaN, Double.NaN, Double.NaN, None, counters, detail)

  private def result(
      status: PeFieldFitStatus,
      state: Option[PeFieldState3[Moving, Fixed]],
      fallback: Rigid3[Moving, Fixed],
      data: Double,
      prior: Double,
      selection: Double,
      certificate: Option[PeGeometryCertificate],
      counters: PeWorkCounters,
      detail: String
  ): PeFieldFitResult3[Moving, Fixed] =
    PeFieldFitResult3(
      status,
      state,
      fallback,
      data,
      prior,
      selection,
      model.nominalFieldParameterCount,
      model.effectiveFieldParameterCount,
      certificate,
      counters.snapshot,
      detail,
      "millimetres",
      "anatomically guided inverse displacement along observed moving-world PE direction; not an off-resonance field",
      dropoutRecoveryClaimed = false
    )

private[flashalign] object PeFieldOptimizer3:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      model: PeFieldModel3[Moving, Fixed],
      geometryConfig: PeGeometryConfig,
      objective: PeProjectedPatchObjective3[Moving, Fixed],
      probes: WorldPointBatch3[Moving],
      config: PeFieldOptimizerConfig
  ): Either[PeFieldOptimizerError, PeFieldOptimizer3[Moving, Fixed]] =
    if !probes.frame.sameRuntimeOwnerAs(model.rigid.moving) then
      Left(PeFieldOptimizerError.InvalidConfig("physical probes belong to another moving frame"))
    else if probes.size <= 0 then Left(PeFieldOptimizerError.InvalidConfig("physical probes must be nonempty"))
    else
      Right(
        new PeFieldOptimizer3(
          model,
          geometryConfig,
          GeometryPointOperator3.peField(model, geometryConfig),
          objective,
          probes,
          config
        )
      )

private final case class AcceptedPeStep[Moving <: Frame[D3], Fixed <: Frame[D3]](
    state: PeFieldState3[Moving, Fixed],
    priorObjective: Double,
    totalObjective: Double,
    rmsDisplacementMm: Double,
    nextDamping: Double
)

private final class PeWorkCounters:
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

  def snapshot: PeFieldPhaseWork = PeFieldPhaseWork(
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

private[flashalign] final class CachedGeometryDataOperator[
    State,
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    cache: MatrixFreePatchLinearization3[State, Moving, Fixed],
    workspace: MatrixFreePatchWorkspace3[Fixed],
    geometry: GeometryPointOperator3[State, Moving, Fixed]
) extends ArraySymmetricOperator:
  val dimension: Int = geometry.parameterCount
  def apply(input: Array[Double], output: Array[Double]): Either[String, Unit] =
    GeometryDirection3.create(geometry.modelId, geometry.basisId, input)
      .left.map(_.message)
      .flatMap(direction => cache.curvatureProduct(direction, output, workspace).left.map(_.message))

private[flashalign] final class DataFieldPriorOperator(
    data: ArraySymmetricOperator,
    prior: DenseOperator,
    poseDimension: Int
) extends ArraySymmetricOperator:
  val dimension: Int = data.dimension
  private val scratch = new Array[Double](dimension)
  def apply(input: Array[Double], output: Array[Double]): Either[String, Unit] =
    data(input, scratch).map { _ =>
      java.lang.System.arraycopy(scratch, 0, output, 0, dimension)
      var row = 0
      while row < prior.size do
        var value = 0.0
        var column = 0
        while column < prior.size do
          value += prior(row, column) * input(poseDimension + column)
          column += 1
        output(poseDimension + row) += value
        row += 1
    }

private[flashalign] final class GeometryPhysicalMetricOperator3[
    State,
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    geometry: GeometryPointOperator3[State, Moving, Fixed],
    state: State,
    probes: WorldPointBatch3[Moving]
) extends ArraySymmetricOperator:
  val dimension: Int = geometry.parameterCount
  private val workspace = geometry.newWorkspace()
  private val displacement = GeometryOutputBuffer3.allocate(geometry.fixed, probes.size).fold(
    error => throw new IllegalArgumentException(error.message),
    identity
  )
  private val beforeMapped = GeometryOutputBuffer3.allocate(geometry.fixed, probes.size).fold(
    error => throw new IllegalArgumentException(error.message),
    identity
  )
  private val forceValues = new Array[Double](probes.size * 3)
  private val forces = WorldVectorBatch3.wrapOwned(geometry.fixed, forceValues)

  def apply(input: Array[Double], output: Array[Double]): Either[String, Unit] =
    GeometryDirection3.create(geometry.modelId, geometry.basisId, input)
      .left.map(_.message)
      .flatMap(direction => geometry.jvp(state, probes, direction, displacement, workspace).left.map(_.message))
      .flatMap { _ =>
        var index = 0
        while index < forceValues.length do
          forceValues(index) = displacement.packed(index) / probes.size.toDouble
          index += 1
        geometry.vjp(state, probes, forces, output, workspace).left.map(_.message)
      }

  def diagonal(): Either[String, Vector[Double]] =
    val input = new Array[Double](dimension)
    val output = new Array[Double](dimension)
    val result = new Array[Double](dimension)
    var column = 0
    var failure = Option.empty[String]
    while column < dimension && failure.isEmpty do
      java.util.Arrays.fill(input, 0.0)
      input(column) = 1.0
      apply(input, output) match
        case Left(detail) => failure = Some(detail)
        case Right(()) => result(column) = output(column)
      column += 1
    failure.toLeft(result.toVector)

  def linearDisplacement(direction: Array[Double]): Either[String, (Double, Double)] =
    GeometryDirection3.create(geometry.modelId, geometry.basisId, direction)
      .left.map(_.message)
      .flatMap(value => geometry.jvp(state, probes, value, displacement, workspace).left.map(_.message))
      .map(_ => displacementSummary(displacement.packed, None))

  def actualDisplacement(before: State, after: State): Either[String, (Double, Double)] =
    geometry.map(before, probes, beforeMapped, workspace).left.map(_.message).flatMap { _ =>
      geometry.map(after, probes, displacement, workspace).left.map(_.message).map { _ =>
        displacementSummary(displacement.packed, Some(beforeMapped.packed))
      }
    }

  private def displacementSummary(values: Array[Double], subtract: Option[Array[Double]]): (Double, Double) =
    var squared = 0.0
    var maximum = 0.0
    var point = 0
    while point < probes.size do
      val offset = point * 3
      val dx = values(offset) - subtract.fold(0.0)(_(offset))
      val dy = values(offset + 1) - subtract.fold(0.0)(_(offset + 1))
      val dz = values(offset + 2) - subtract.fold(0.0)(_(offset + 2))
      val norm2 = dx * dx + dy * dy + dz * dz
      squared += norm2
      maximum = math.max(maximum, math.sqrt(norm2))
      point += 1
    (math.sqrt(squared / probes.size.toDouble), maximum)

private[flashalign] sealed trait PeFieldOptimizerError derives CanEqual:
  def message: String

private[flashalign] object PeFieldOptimizerError:
  final case class InvalidConfig(detail: String) extends PeFieldOptimizerError:
    val message = s"invalid PE optimizer configuration: $detail"
