package reframe4s.flashalign

import gale.linalg.DMat
import gale.spectral.Eigen
import gale.spectral.EigenSelection
import gale.spectral.EigenVectors

private[flashalign] sealed trait NonlinearInformationError derives CanEqual:
  def message: String

private[flashalign] object NonlinearInformationError:
  final case class InvalidConfiguration(detail: String)
      extends NonlinearInformationError:
    val message: String = detail

  final case class DimensionMismatch(expected: Int, actual: Int)
      extends NonlinearInformationError:
    val message: String = s"expected dimension $expected, got $actual"

  final case class OperatorFailure(detail: String)
      extends NonlinearInformationError:
    val message: String = detail

  final case class AsymmetricData(
      row: Int,
      column: Int,
      left: Double,
      right: Double
  ) extends NonlinearInformationError:
    val message: String =
      s"data-only curvature is asymmetric at ($row,$column): $left versus $right"

  final case class MaterialNegativeCurvature(
      component: String,
      minimumEigenvalue: Double,
      tolerance: Double
  ) extends NonlinearInformationError:
    val message: String =
      s"$component has material negative curvature $minimumEigenvalue below -$tolerance"

  final case class SpectralFailure(component: String, detail: String)
      extends NonlinearInformationError:
    val message: String = s"$component eigensystem failed: $detail"

/** Data support after removing the best identifiable pose adjustment. Prior
  * and damping are excluded from `conditionalDataRank` and the pose rank.
  * Prior precision enters only through the dimensionless diagnostic spectrum.
  */
private[flashalign] final case class NonlinearInformationDiagnostics(
    poseDimension: Int,
    fieldDimension: Int,
    poseDataRank: Int,
    lostPoseDirections: Int,
    conditionalDataRank: Int,
    priorRelativeEigenvalues: Vector[Double],
    effectiveDimension: Double,
    maximumPriorRelativeInformation: Double,
    dataOnlyPoseConditioning: Boolean,
    priorAndDampingExcludedFromDataRank: Boolean,
    operatorProducts: Long,
    checkpointDenseBytes: Long,
    rankTolerance: Double
)

/** Occasional checkpoint analyzer for the initial 32--192 coefficient models.
  * It materializes only the data-only diagnostic matrix; fitting remains
  * matrix-free. The explicit upper bound prevents accidental use as a large
  * production normal-matrix path.
  */
private[flashalign] object NonlinearInformationAnalyzer:
  val MaximumFieldDimension = 192

  def analyze(
      dataOnly: ArraySymmetricOperator,
      poseDimension: Int,
      priorPrecisionDiagonal: Vector[Double],
      rankRelativeTolerance: Double = 1e-9,
      symmetryRelativeTolerance: Double = 1e-10
  ): Either[NonlinearInformationError, NonlinearInformationDiagnostics] =
    val fieldDimension = dataOnly.dimension - poseDimension
    if poseDimension != 6 && poseDimension != 12 then
      Left(
        NonlinearInformationError.InvalidConfiguration(
          s"pose dimension must be 6 or 12, got $poseDimension"
        )
      )
    else if fieldDimension <= 0 || fieldDimension > MaximumFieldDimension then
      Left(
        NonlinearInformationError.InvalidConfiguration(
          s"field dimension must be in [1,$MaximumFieldDimension], got $fieldDimension"
        )
      )
    else if priorPrecisionDiagonal.size != fieldDimension then
      Left(
        NonlinearInformationError.DimensionMismatch(
          fieldDimension,
          priorPrecisionDiagonal.size
        )
      )
    else if priorPrecisionDiagonal.exists(value => !value.isFinite || value <= 0.0) then
      Left(
        NonlinearInformationError.InvalidConfiguration(
          "prior precision diagonal must be finite and strictly positive"
        )
      )
    else if !rankRelativeTolerance.isFinite || rankRelativeTolerance <= 0.0 ||
        !symmetryRelativeTolerance.isFinite || symmetryRelativeTolerance <= 0.0
    then
      Left(
        NonlinearInformationError.InvalidConfiguration(
          "rank and symmetry tolerances must be finite and positive"
        )
      )
    else
      for
        dense <- materialize(dataOnly, symmetryRelativeTolerance)
        poseSpectrum <- spectrum(
          block(dense, dataOnly.dimension, 0, poseDimension),
          poseDimension,
          vectors = true,
          "data-only pose block"
        )
        poseValues = Vector.tabulate(poseDimension)(poseSpectrum.eigenvalues.apply)
        poseScale = poseValues.map(math.abs).maxOption.getOrElse(0.0)
        poseTolerance = rankRelativeTolerance * math.max(1.0, poseScale)
        _ <- requireSemidefinite("data-only pose block", poseValues, poseTolerance)
        poseRank = poseValues.count(_ > poseTolerance)
        conditional = conditionalField(
          dense,
          dataOnly.dimension,
          poseDimension,
          poseValues,
          poseSpectrum.eigenvectors,
          poseTolerance
        )
        conditionalSpectrum <- spectrum(
          conditional,
          fieldDimension,
          vectors = false,
          "data-only conditional field information"
        )
        conditionalValues = Vector.tabulate(fieldDimension)(
          conditionalSpectrum.eigenvalues.apply
        )
        conditionalScale =
          conditionalValues.map(math.abs).maxOption.getOrElse(0.0)
        conditionalTolerance =
          rankRelativeTolerance * math.max(1.0, conditionalScale)
        _ <- requireSemidefinite(
          "data-only conditional field information",
          conditionalValues,
          conditionalTolerance
        )
        whitened = priorWhiten(conditional, fieldDimension, priorPrecisionDiagonal)
        fieldSpectrum <- spectrum(
          whitened,
          fieldDimension,
          vectors = false,
          "prior-relative conditional field information"
        )
        rawFieldValues = Vector.tabulate(fieldDimension)(fieldSpectrum.eigenvalues.apply)
        fieldScale = rawFieldValues.map(math.abs).maxOption.getOrElse(0.0)
        fieldTolerance = rankRelativeTolerance * math.max(1.0, fieldScale)
        _ <- requireSemidefinite(
          "prior-relative conditional field information",
          rawFieldValues,
          fieldTolerance
        )
        fieldValues = rawFieldValues.map(value => if value < 0.0 then 0.0 else value)
      yield NonlinearInformationDiagnostics(
        poseDimension,
        fieldDimension,
        poseRank,
        poseDimension - poseRank,
        conditionalValues.count(_ > conditionalTolerance),
        fieldValues,
        fieldValues.foldLeft(0.0)((sum, value) => sum + value / (1.0 + value)),
        fieldValues.maxOption.getOrElse(0.0),
        dataOnlyPoseConditioning = true,
        priorAndDampingExcludedFromDataRank = true,
        operatorProducts = dataOnly.dimension.toLong,
        checkpointDenseBytes =
          (dataOnly.dimension.toLong * dataOnly.dimension.toLong +
            2L * fieldDimension.toLong * fieldDimension.toLong) * 8L,
        rankTolerance = conditionalTolerance
      )

  private def materialize(
      operator: ArraySymmetricOperator,
      symmetryRelativeTolerance: Double
  ): Either[NonlinearInformationError, Array[Double]] =
    val dimension = operator.dimension
    val result = new Array[Double](dimension * dimension)
    val input = new Array[Double](dimension)
    val output = new Array[Double](dimension)
    var column = 0
    while column < dimension do
      java.util.Arrays.fill(input, 0.0)
      input(column) = 1.0
      operator(input, output) match
        case Left(detail) =>
          return Left(NonlinearInformationError.OperatorFailure(detail))
        case Right(()) =>
          var row = 0
          while row < dimension do
            if !output(row).isFinite then
              return Left(
                NonlinearInformationError.OperatorFailure(
                  s"non-finite data-only curvature at ($row,$column)"
                )
              )
            result(row * dimension + column) = output(row)
            row += 1
      column += 1
    var row = 0
    while row < dimension do
      column = row + 1
      while column < dimension do
        val left = result(row * dimension + column)
        val right = result(column * dimension + row)
        val scale = math.max(1.0, math.max(math.abs(left), math.abs(right)))
        if math.abs(left - right) > symmetryRelativeTolerance * scale then
          return Left(
            NonlinearInformationError.AsymmetricData(
              row,
              column,
              left,
              right
            )
          )
        val average = 0.5 * (left + right)
        result(row * dimension + column) = average
        result(column * dimension + row) = average
        column += 1
      row += 1
    Right(result)

  private def block(
      matrix: Array[Double],
      dimension: Int,
      start: Int,
      size: Int
  ): Array[Double] =
    Array.tabulate(size * size) { flat =>
      val row = flat / size
      val column = flat % size
      matrix((row + start) * dimension + column + start)
    }

  private def conditionalField(
      matrix: Array[Double],
      dimension: Int,
      poseDimension: Int,
      poseEigenvalues: Vector[Double],
      poseEigenvectors: DMat,
      poseTolerance: Double
  ): Array[Double] =
    val fieldDimension = dimension - poseDimension
    val projectedCross = new Array[Double](poseDimension * fieldDimension)
    var mode = 0
    while mode < poseDimension do
      if poseEigenvalues(mode) > poseTolerance then
        val inverseRoot = 1.0 / math.sqrt(poseEigenvalues(mode))
        var field = 0
        while field < fieldDimension do
          var projection = 0.0
          var pose = 0
          while pose < poseDimension do
            projection +=
              poseEigenvectors(pose, mode) *
                matrix(pose * dimension + poseDimension + field)
            pose += 1
          projectedCross(mode * fieldDimension + field) =
            projection * inverseRoot
          field += 1
      mode += 1
    Array.tabulate(fieldDimension * fieldDimension) { flat =>
      val row = flat / fieldDimension
      val column = flat % fieldDimension
      var correction = 0.0
      mode = 0
      while mode < poseDimension do
        correction +=
          projectedCross(mode * fieldDimension + row) *
            projectedCross(mode * fieldDimension + column)
        mode += 1
      matrix((poseDimension + row) * dimension + poseDimension + column) -
        correction
    }

  private def priorWhiten(
      conditional: Array[Double],
      dimension: Int,
      prior: Vector[Double]
  ): Array[Double] =
    Array.tabulate(conditional.length) { flat =>
      val row = flat / dimension
      val column = flat % dimension
      conditional(flat) / math.sqrt(prior(row) * prior(column))
    }

  private def spectrum(
      rowMajor: Array[Double],
      dimension: Int,
      vectors: Boolean,
      component: String
  ) =
    val matrix = DMat.dense(dimension, dimension, rowMajor.toVector)
    Eigen
      .eigSymmetric(
        matrix,
        EigenSelection.All,
        if vectors then EigenVectors.Right else EigenVectors.ValuesOnly
      )
      .left
      .map(error =>
        NonlinearInformationError.SpectralFailure(component, error.toString)
      )

  private def requireSemidefinite(
      component: String,
      eigenvalues: Vector[Double],
      tolerance: Double
  ): Either[NonlinearInformationError, Unit] =
    val minimum = eigenvalues.minOption.getOrElse(0.0)
    if minimum < -tolerance then
      Left(
        NonlinearInformationError.MaterialNegativeCurvature(
          component,
          minimum,
          tolerance
        )
      )
    else Right(())

private[flashalign] final case class NonlinearRoleEvidence(
    optimizationId: String,
    selectionId: String,
    auditId: String,
    subjectSplitId: String,
    presetManifestSha256: String,
    seed: Long
):
  def validate: Either[String, Unit] =
    val ids = Vector(optimizationId, selectionId, auditId)
    if ids.exists(_.trim.isEmpty) || ids.distinct.size != ids.size then
      Left("optimization, selection, and audit identities must be nonempty and distinct")
    else if subjectSplitId.trim.isEmpty then Left("subject split identity must be nonempty")
    else if !presetManifestSha256.matches("[0-9a-f]{64}") then
      Left("preset manifest identity must be a lowercase SHA-256")
    else Right(())

private[flashalign] final case class NonlinearSelectionConfig(
    minimumConditionalRank: Int,
    minimumEffectiveDimension: Double,
    minimumMaximumInformation: Double,
    minimumPredictedResidualReduction: Double,
    minimumSelectionImprovement: Double,
    maximumRegionalInstabilityMillimetres: Double,
    minimumRegionalSupportFraction: Double,
    allowPartialSupport: Boolean,
    simplerFallbackModelId: String
)

private[flashalign] final case class NonlinearCandidateEvidence(
    modelId: String,
    complexityOrder: Int,
    information: NonlinearInformationDiagnostics,
    predictedResidualReduction: Double,
    selectionImprovement: Double,
    regionalInstabilityMillimetres: Double,
    regionalSupportFraction: Double,
    optimizerConverged: Boolean,
    geometryCertified: Boolean
)

private[flashalign] enum NonlinearCandidateSupport derives CanEqual:
  case Supported
  case PartiallySupported
  case OptimizerFailure
  case UnsafeGeometry
  case InsufficientConditionalRank
  case PriorControlled
  case InsufficientResidualProjection
  case NoSelectionImprovement
  case RegionallyUnstable
  case InsufficientRegionalSupport

private[flashalign] final case class NonlinearCandidateAssessment(
    evidence: NonlinearCandidateEvidence,
    support: NonlinearCandidateSupport
):
  def admitted: Boolean =
    support == NonlinearCandidateSupport.Supported ||
      support == NonlinearCandidateSupport.PartiallySupported

private[flashalign] enum NonlinearSelectionStatus derives CanEqual:
  case AdmittedNonlinear
  case RetainedSimplerFallback
  case InvalidConfiguration
  case InvalidRoleEvidence

private[flashalign] final case class NonlinearSelectionDecision(
    status: NonlinearSelectionStatus,
    selectedNonlinearModelId: Option[String],
    simplerFallbackModelId: String,
    assessments: Vector[NonlinearCandidateAssessment],
    roles: NonlinearRoleEvidence,
    detail: String
):
  def successfulNonlinearSelection: Boolean =
    status == NonlinearSelectionStatus.AdmittedNonlinear &&
      selectedNonlinearModelId.nonEmpty

private[flashalign] object NonlinearModelSelector:
  def select(
      candidates: Vector[NonlinearCandidateEvidence],
      config: NonlinearSelectionConfig,
      roles: NonlinearRoleEvidence
  ): NonlinearSelectionDecision =
    roles.validate match
      case Left(detail) =>
        decision(
          NonlinearSelectionStatus.InvalidRoleEvidence,
          None,
          Vector.empty,
          config.simplerFallbackModelId,
          roles,
          detail
        )
      case Right(()) =>
        validateConfig(config) match
          case Left(detail) =>
            decision(
              NonlinearSelectionStatus.InvalidConfiguration,
              None,
              Vector.empty,
              config.simplerFallbackModelId,
              roles,
              detail
            )
          case Right(()) =>
            val assessments = candidates
              .sortBy(candidate =>
                (candidate.complexityOrder, candidate.modelId)
              )
              .map(candidate => assess(candidate, config))
            assessments.find(_.admitted) match
              case Some(selected) =>
                decision(
                  NonlinearSelectionStatus.AdmittedNonlinear,
                  Some(selected.evidence.modelId),
                  assessments,
                  config.simplerFallbackModelId,
                  roles,
                  s"selected the smallest supported model ${selected.evidence.modelId}"
                )
              case None =>
                decision(
                  NonlinearSelectionStatus.RetainedSimplerFallback,
                  None,
                  assessments,
                  config.simplerFallbackModelId,
                  roles,
                  s"no nonlinear candidate passed; retained validated simpler checkpoint ${config.simplerFallbackModelId}"
                )

  private def assess(
      candidate: NonlinearCandidateEvidence,
      config: NonlinearSelectionConfig
  ): NonlinearCandidateAssessment =
    val support =
      if !candidate.optimizerConverged then
        NonlinearCandidateSupport.OptimizerFailure
      else if !candidate.geometryCertified then
        NonlinearCandidateSupport.UnsafeGeometry
      else if
        candidate.information.conditionalDataRank <
          config.minimumConditionalRank
      then NonlinearCandidateSupport.InsufficientConditionalRank
      else if
        candidate.information.effectiveDimension <
          config.minimumEffectiveDimension ||
          candidate.information.maximumPriorRelativeInformation <
            config.minimumMaximumInformation
      then NonlinearCandidateSupport.PriorControlled
      else if
        candidate.predictedResidualReduction <
          config.minimumPredictedResidualReduction
      then NonlinearCandidateSupport.InsufficientResidualProjection
      else if
        candidate.selectionImprovement < config.minimumSelectionImprovement
      then NonlinearCandidateSupport.NoSelectionImprovement
      else if
        candidate.regionalInstabilityMillimetres >
          config.maximumRegionalInstabilityMillimetres
      then NonlinearCandidateSupport.RegionallyUnstable
      else if
        candidate.regionalSupportFraction <
          config.minimumRegionalSupportFraction ||
          (!config.allowPartialSupport &&
            candidate.regionalSupportFraction < 1.0)
      then NonlinearCandidateSupport.InsufficientRegionalSupport
      else if candidate.regionalSupportFraction < 1.0 then
        NonlinearCandidateSupport.PartiallySupported
      else NonlinearCandidateSupport.Supported
    NonlinearCandidateAssessment(candidate, support)

  private def validateConfig(
      config: NonlinearSelectionConfig
  ): Either[String, Unit] =
    val finiteNonnegative = Vector(
      config.minimumEffectiveDimension,
      config.minimumMaximumInformation,
      config.minimumPredictedResidualReduction,
      config.minimumSelectionImprovement,
      config.maximumRegionalInstabilityMillimetres
    ).forall(value => value.isFinite && value >= 0.0)
    if config.minimumConditionalRank <= 0 then
      Left("minimum conditional rank must be positive")
    else if !finiteNonnegative then
      Left("nonlinear support thresholds must be finite and nonnegative")
    else if !config.minimumRegionalSupportFraction.isFinite ||
        config.minimumRegionalSupportFraction <= 0.0 ||
        config.minimumRegionalSupportFraction > 1.0
    then Left("minimum regional support fraction must be in (0,1]")
    else if config.simplerFallbackModelId.trim.isEmpty then
      Left("simpler fallback model identity must be nonempty")
    else Right(())

  private def decision(
      status: NonlinearSelectionStatus,
      selected: Option[String],
      assessments: Vector[NonlinearCandidateAssessment],
      fallback: String,
      roles: NonlinearRoleEvidence,
      detail: String
  ): NonlinearSelectionDecision =
    NonlinearSelectionDecision(
      status,
      selected,
      fallback,
      assessments,
      roles,
      detail
    )
