package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame

import scala.collection.mutable

private[flashalign] sealed trait LinearValidationError derives CanEqual:
  def message: String

private[flashalign] object LinearValidationError:
  final case class InvalidConfiguration(detail: String)
      extends LinearValidationError:
    val message: String = detail

  final case class Sampling(error: PatchSamplingError)
      extends LinearValidationError:
    val message: String = error.message

  final case class SampleIdentityMismatch(
      expected: PatchSampleSetId,
      actual: PatchSampleSetId
  ) extends LinearValidationError:
    val message: String = s"expected sample set $expected, got $actual"

  final case class PatchEvidenceMismatch(detail: String)
      extends LinearValidationError:
    val message: String = detail

  final case class NonFiniteValue(component: String, index: Int, value: Double)
      extends LinearValidationError:
    val message: String = s"$component value $index must be finite, got $value"

  final case class MateriallyNegativeDataEigenvalue(value: Double, tolerance: Double)
      extends LinearValidationError:
    val message: String =
      s"data-only scaled curvature eigenvalue $value is below tolerance -$tolerance"

  final case class EvaluationFailure(candidateId: Int, detail: String)
      extends LinearValidationError:
    val message: String = s"candidate $candidateId evaluation failed: $detail"

  final case class RegionalRefitFailure(regionId: Int, detail: String)
      extends LinearValidationError:
    val message: String = s"regional refit $regionId failed: $detail"

private[flashalign] enum PosteriorWeightMeaning derives CanEqual:
  case RobustMixtureWeightNotCalibratedProbability

private[flashalign] final class LinearQcConfig3 private (
    val minimumPatchInlierWeight: Double,
    val minimumOverlapFraction: Double,
    val minimumSpatialInlierCoverage: Double,
    val rankRelativeTolerance: Double,
    val maximumDataConditionNumber: Double,
    val maximumRegionalRefitDisplacementMillimetres: Double,
    val nearEqualObjectiveTolerance: Double
)

private[flashalign] object LinearQcConfig3:
  def create(
      minimumPatchInlierWeight: Double = 0.5,
      minimumOverlapFraction: Double = 0.3,
      minimumSpatialInlierCoverage: Double = 0.25,
      rankRelativeTolerance: Double = 1e-10,
      maximumDataConditionNumber: Double = 1e10,
      maximumRegionalRefitDisplacementMillimetres: Double = 3.0,
      nearEqualObjectiveTolerance: Double = 1e-3
  ): Either[LinearValidationError, LinearQcConfig3] =
    val unitValues = Vector(
      "minimum patch inlier weight" -> minimumPatchInlierWeight,
      "minimum overlap fraction" -> minimumOverlapFraction,
      "minimum spatial inlier coverage" -> minimumSpatialInlierCoverage
    )
    unitValues.collectFirst {
      case (name, value) if !value.isFinite || value < 0.0 || value > 1.0 =>
        s"$name must be finite and within [0, 1], got $value"
    } match
      case Some(detail) => invalid(detail)
      case None if !positive(rankRelativeTolerance) =>
        invalid(s"rank relative tolerance must be positive, got $rankRelativeTolerance")
      case None if !maximumDataConditionNumber.isFinite || maximumDataConditionNumber <= 1.0 =>
        invalid(s"maximum data condition number must exceed one, got $maximumDataConditionNumber")
      case None if !positive(maximumRegionalRefitDisplacementMillimetres) =>
        invalid(
          s"maximum regional refit displacement must be positive, got $maximumRegionalRefitDisplacementMillimetres"
        )
      case None if !nearEqualObjectiveTolerance.isFinite || nearEqualObjectiveTolerance < 0.0 =>
        invalid(s"near-equal objective tolerance must be nonnegative, got $nearEqualObjectiveTolerance")
      case None =>
        Right(
          new LinearQcConfig3(
            minimumPatchInlierWeight,
            minimumOverlapFraction,
            minimumSpatialInlierCoverage,
            rankRelativeTolerance,
            maximumDataConditionNumber,
            maximumRegionalRefitDisplacementMillimetres,
            nearEqualObjectiveTolerance
          )
        )

  private def invalid(detail: String): Either[LinearValidationError, LinearQcConfig3] =
    Left(LinearValidationError.InvalidConfiguration(detail))

  private def positive(value: Double): Boolean = value.isFinite && value > 0.0

private[flashalign] final case class LinearPatchEvidence3(
    patchId: Int,
    cell: WorldCell3,
    loss: Double,
    validSupport: Boolean,
    inlierWeight: Double
)

private[flashalign] final case class LinearDataInformation3(
    parameterCount: Int,
    dataRank: Int,
    gaugeRank: Int,
    scaledConditionNumber: Double,
    scaledEigenvalues: Vector[Double],
    dataOnly: Boolean,
    priorExcluded: Boolean,
    dampingExcluded: Boolean
)

private[flashalign] final case class LinearPatchQcSummary3(
    sampleSetId: PatchSampleSetId,
    weightedObjective: Double,
    fixedOverlapDenominator: Double,
    validOverlapWeight: Double,
    overlapFraction: Double,
    meanRobustInlierWeight: Double,
    posteriorWeightMeaning: PosteriorWeightMeaning,
    spatialInlierCoverage: Double,
    representedCells: Int,
    inlierCells: Int,
    validPatches: Int,
    invalidPatches: Int
)

private[flashalign] final case class LinearEvaluationInput3(
    sampleSetId: PatchSampleSetId,
    patchEvidence: Vector[LinearPatchEvidence3],
    dataCurvatureUpper: Array[Double],
    physicalMetricUpper: Array[Double],
    parameterCount: Int,
    gaugeRank: Int
)

private[flashalign] final case class LinearEvaluation3(
    patch: LinearPatchQcSummary3,
    information: LinearDataInformation3
)

private[flashalign] object LinearQcEvaluation3:
  def evaluate(
      samples: PatchSampleSet3,
      input: LinearEvaluationInput3,
      config: LinearQcConfig3
  ): Either[LinearValidationError, LinearEvaluation3] =
    if input.sampleSetId != samples.id then
      Left(LinearValidationError.SampleIdentityMismatch(samples.id, input.sampleSetId))
    else
      for
        patch <- summarizePatches(samples, input.patchEvidence, config)
        information <- analyzeDataInformation(
          input.dataCurvatureUpper,
          input.physicalMetricUpper,
          input.parameterCount,
          input.gaugeRank,
          config.rankRelativeTolerance
        )
      yield LinearEvaluation3(patch, information)

  private def summarizePatches(
      samples: PatchSampleSet3,
      evidence: Vector[LinearPatchEvidence3],
      config: LinearQcConfig3
  ): Either[LinearValidationError, LinearPatchQcSummary3] =
    val expected = samples.entries.map(_.patch.id).sorted
    val actual = evidence.map(_.patchId).sorted
    if expected != actual then
      Left(
        LinearValidationError.PatchEvidenceMismatch(
          s"evidence patch IDs $actual do not match fixed sample IDs $expected"
        )
      )
    else
      val byId = evidence.map(item => item.patchId -> item).toMap
      if byId.size != evidence.size then
        Left(LinearValidationError.PatchEvidenceMismatch("patch evidence IDs must be unique"))
      else
        var denominator = 0.0
        var validWeight = 0.0
        var weightedLoss = 0.0
        var weightedInlier = 0.0
        var validPatches = 0
        val representedCells = mutable.HashSet.empty[WorldCell3]
        val inlierCells = mutable.HashSet.empty[WorldCell3]
        var index = 0
        while index < samples.entries.length do
          val entry = samples.entries(index)
          val item = byId(entry.patch.id)
          if item.cell != entry.patch.cell then
            return Left(
              LinearValidationError.PatchEvidenceMismatch(
                s"patch ${item.patchId} cell ${item.cell} does not match ${entry.patch.cell}"
              )
            )
          if !item.loss.isFinite || item.loss < 0.0 then
            return Left(
              LinearValidationError.NonFiniteValue("patch loss", index, item.loss)
            )
          if !item.inlierWeight.isFinite || item.inlierWeight < 0.0 || item.inlierWeight > 1.0 then
            return Left(
              LinearValidationError.PatchEvidenceMismatch(
                s"patch ${item.patchId} inlier weight must be within [0, 1], got ${item.inlierWeight}"
              )
            )
          val weight = entry.objectiveWeight
          denominator += weight
          weightedLoss += weight * item.loss
          representedCells += item.cell
          if item.validSupport then
            validWeight += weight
            weightedInlier += weight * item.inlierWeight
            validPatches += 1
            if item.inlierWeight >= config.minimumPatchInlierWeight then
              inlierCells += item.cell
          index += 1
        if !denominator.isFinite || denominator <= 0.0 then
          Left(
            LinearValidationError.PatchEvidenceMismatch(
              s"fixed overlap denominator must be positive, got $denominator"
            )
          )
        else
          Right(
            LinearPatchQcSummary3(
              samples.id,
              weightedLoss,
              denominator,
              validWeight,
              validWeight / denominator,
              weightedInlier / denominator,
              PosteriorWeightMeaning.RobustMixtureWeightNotCalibratedProbability,
              inlierCells.size.toDouble / representedCells.size.toDouble,
              representedCells.size,
              inlierCells.size,
              validPatches,
              evidence.size - validPatches
            )
          )

  private def analyzeDataInformation(
      curvature: Array[Double],
      physicalMetric: Array[Double],
      dimension: Int,
      gaugeRank: Int,
      relativeTolerance: Double
  ): Either[LinearValidationError, LinearDataInformation3] =
    if dimension <= 0 || dimension > 12 then
      Left(
        LinearValidationError.InvalidConfiguration(
          s"linear QC parameter count must be within [1, 12], got $dimension"
        )
      )
    else if gaugeRank < 0 || gaugeRank > dimension then
      Left(
        LinearValidationError.InvalidConfiguration(
          s"gauge rank must be within [0, $dimension], got $gaugeRank"
        )
      )
    else
      val packedSize = dimension * (dimension + 1) / 2
      if curvature.length != packedSize || physicalMetric.length != packedSize then
        Left(
          LinearValidationError.PatchEvidenceMismatch(
            s"curvature and metric require $packedSize packed values"
          )
        )
      else
        val scaled = new Array[Double](dimension * dimension)
        var row = 0
        while row < dimension do
          val rowScale = physicalMetric(PackedSymmetric.index(row, row))
          if !rowScale.isFinite || rowScale <= 0.0 then
            return Left(
              LinearValidationError.PatchEvidenceMismatch(
                s"physical metric diagonal $row must be positive, got $rowScale"
              )
            )
          var column = 0
          while column < dimension do
            val columnScale = physicalMetric(PackedSymmetric.index(column, column))
            val value = curvature(PackedSymmetric.index(row, column))
            if !value.isFinite then
              return Left(
                LinearValidationError.NonFiniteValue(
                  "data curvature",
                  PackedSymmetric.index(row, column),
                  value
                )
              )
            scaled(row * dimension + column) =
              value / math.sqrt(rowScale * columnScale)
            column += 1
          row += 1
        val eigenvalues = symmetricEigenvalues(scaled, dimension).sorted
        val scale = eigenvalues.map(math.abs).maxOption.getOrElse(0.0)
        val tolerance = relativeTolerance * math.max(1.0, scale)
        eigenvalues.find(_ < -tolerance) match
          case Some(value) =>
            Left(
              LinearValidationError.MateriallyNegativeDataEigenvalue(
                value,
                tolerance
              )
            )
          case None =>
            val positive = eigenvalues.filter(_ > tolerance)
            val condition =
              if positive.isEmpty then Double.PositiveInfinity
              else positive.last / positive.head
            Right(
              LinearDataInformation3(
                dimension,
                positive.size,
                gaugeRank,
                condition,
                eigenvalues.map(value => if math.abs(value) <= tolerance then 0.0 else value),
                dataOnly = true,
                priorExcluded = true,
                dampingExcluded = true
              )
            )

  private def symmetricEigenvalues(
      input: Array[Double],
      dimension: Int
  ): Vector[Double] =
    val matrix = input.clone()
    val maximumSweeps = 64 * dimension * dimension
    var sweep = 0
    var converged = false
    while sweep < maximumSweeps && !converged do
      var p = 0
      var q = 0
      var maximum = 0.0
      var row = 0
      while row < dimension do
        var column = row + 1
        while column < dimension do
          val value = math.abs(matrix(row * dimension + column))
          if value > maximum then
            maximum = value
            p = row
            q = column
          column += 1
        row += 1
      val diagonalScale =
        Vector.tabulate(dimension)(index => math.abs(matrix(index * dimension + index))).maxOption.getOrElse(0.0)
      if maximum <= 1e-14 * math.max(1.0, diagonalScale) then converged = true
      else
        val app = matrix(p * dimension + p)
        val aqq = matrix(q * dimension + q)
        val apq = matrix(p * dimension + q)
        val angle = 0.5 * math.atan2(2.0 * apq, aqq - app)
        val cosine = math.cos(angle)
        val sine = math.sin(angle)
        var index = 0
        while index < dimension do
          if index != p && index != q then
            val aip = matrix(index * dimension + p)
            val aiq = matrix(index * dimension + q)
            val newP = cosine * aip - sine * aiq
            val newQ = sine * aip + cosine * aiq
            matrix(index * dimension + p) = newP
            matrix(p * dimension + index) = newP
            matrix(index * dimension + q) = newQ
            matrix(q * dimension + index) = newQ
          index += 1
        matrix(p * dimension + p) =
          cosine * cosine * app - 2.0 * sine * cosine * apq + sine * sine * aqq
        matrix(q * dimension + q) =
          sine * sine * app + 2.0 * sine * cosine * apq + cosine * cosine * aqq
        matrix(p * dimension + q) = 0.0
        matrix(q * dimension + p) = 0.0
      sweep += 1
    Vector.tabulate(dimension)(index => matrix(index * dimension + index))

private[flashalign] final case class LinearSelectionCandidate3[State](
    id: Int,
    state: State
)

private[flashalign] final case class LinearCandidateAssessment3[State](
    candidate: LinearSelectionCandidate3[State],
    evaluation: LinearEvaluation3
)

private[flashalign] final case class LinearSelectionComparison3[State](
    sampleSetId: PatchSampleSetId,
    assessments: Vector[LinearCandidateAssessment3[State]],
    competingAlignments: Boolean,
    candidateDisagreementMillimetres: Double
):
  def best: LinearCandidateAssessment3[State] = assessments.head

private[flashalign] final class LinearSelectionAuditWorkspace3 private[flashalign] (
    private[flashalign] val sampling: PatchSamplingWorkspace3
)

private[flashalign] final class LinearSelectionAuditPlan3[
    State,
    F <: Frame[D3],
    C
] private (
    val samples: PatchSamplePlan3[F, C],
    val config: LinearQcConfig3
):
  def newWorkspace(): LinearSelectionAuditWorkspace3 =
    new LinearSelectionAuditWorkspace3(samples.newWorkspace())

  def compareOnSelection(
      candidates: Vector[LinearSelectionCandidate3[State]],
      candidateDisagreementMillimetres: Double
  )(
      evaluate: (State, PatchSampleSet3) => Either[String, LinearEvaluationInput3]
  ): Either[LinearValidationError, LinearSelectionComparison3[State]] =
    if candidates.isEmpty then
      Left(LinearValidationError.InvalidConfiguration("selection requires candidates"))
    else if !candidateDisagreementMillimetres.isFinite || candidateDisagreementMillimetres < 0.0 then
      Left(
        LinearValidationError.InvalidConfiguration(
          s"candidate disagreement must be finite and nonnegative, got $candidateDisagreementMillimetres"
        )
      )
    else
      val assessments = Vector.newBuilder[LinearCandidateAssessment3[State]]
      var index = 0
      while index < candidates.length do
        val candidate = candidates(index)
        evaluate(candidate.state, samples.selection) match
          case Left(detail) =>
            return Left(LinearValidationError.EvaluationFailure(candidate.id, detail))
          case Right(input) =>
            LinearQcEvaluation3.evaluate(samples.selection, input, config) match
              case Left(error) => return Left(error)
              case Right(value) =>
                assessments += LinearCandidateAssessment3(candidate, value)
        index += 1
      val ranked = assessments.result().sortBy(item => (item.evaluation.patch.weightedObjective, item.candidate.id))
      val competing =
        ranked.size > 1 &&
          ranked(1).evaluation.patch.weightedObjective -
            ranked.head.evaluation.patch.weightedObjective <=
            config.nearEqualObjectiveTolerance
      Right(
        LinearSelectionComparison3(
          samples.selection.id,
          ranked,
          competing,
          candidateDisagreementMillimetres
        )
      )

  def finalizeSelection(
      comparison: LinearSelectionComparison3[State],
      workspace: LinearSelectionAuditWorkspace3
  ): Either[LinearValidationError, LinearCandidateAssessment3[State]] =
    samples
      .markModelSelectionComplete(comparison.sampleSetId, workspace.sampling)
      .left
      .map(LinearValidationError.Sampling.apply)
      .map(_ => comparison.best)

  def evaluateAudit(
      state: State,
      workspace: LinearSelectionAuditWorkspace3
  )(
      evaluate: (State, PatchSampleSet3) => Either[String, LinearEvaluationInput3]
  ): Either[LinearValidationError, LinearEvaluation3] =
    samples
      .openAudit(workspace.sampling)
      .left
      .map(LinearValidationError.Sampling.apply)
      .flatMap { audit =>
        evaluate(state, audit)
          .left
          .map(detail => LinearValidationError.EvaluationFailure(-1, detail))
          .flatMap(input => LinearQcEvaluation3.evaluate(audit, input, config))
      }

private[flashalign] object LinearSelectionAuditPlan3:
  def compile[State, F <: Frame[D3], C](
      samples: PatchSamplePlan3[F, C],
      config: LinearQcConfig3
  ): LinearSelectionAuditPlan3[State, F, C] =
    new LinearSelectionAuditPlan3(samples, config)

private[flashalign] final case class LinearRegionalRefitRegion3(
    id: Int,
    excludedPatchIds: Set[Int],
    excludedCells: Set[WorldCell3]
)

private[flashalign] final case class LinearRegionalRefitResult3(
    region: LinearRegionalRefitRegion3,
    maximumDisplacementMillimetres: Double
)

private[flashalign] final case class LinearRegionalStability3(
    sampleSetId: PatchSampleSetId,
    results: Vector[LinearRegionalRefitResult3],
    medianDisplacementMillimetres: Double,
    maximumDisplacementMillimetres: Double
)

private[flashalign] final class LinearRegionalRefitPlan3 private (
    val sampleSetId: PatchSampleSetId,
    val regions: Vector[LinearRegionalRefitRegion3]
):
  def evaluate[State](
      base: State
  )(
      refitExcluding: Set[Int] => Either[String, State],
      maximumDisplacement: (State, State) => Double
  ): Either[LinearValidationError, LinearRegionalStability3] =
    val results = Vector.newBuilder[LinearRegionalRefitResult3]
    var index = 0
    while index < regions.length do
      val region = regions(index)
      refitExcluding(region.excludedPatchIds) match
        case Left(detail) =>
          return Left(LinearValidationError.RegionalRefitFailure(region.id, detail))
        case Right(refit) =>
          val displacement = maximumDisplacement(base, refit)
          if !displacement.isFinite || displacement < 0.0 then
            return Left(
              LinearValidationError.NonFiniteValue(
                "regional refit displacement",
                region.id,
                displacement
              )
            )
          results += LinearRegionalRefitResult3(region, displacement)
      index += 1
    val completed = results.result()
    val ordered = completed.map(_.maximumDisplacementMillimetres).sorted
    val median =
      if ordered.size % 2 == 1 then ordered(ordered.size / 2)
      else 0.5 * (ordered(ordered.size / 2 - 1) + ordered(ordered.size / 2))
    Right(
      LinearRegionalStability3(
        sampleSetId,
        completed,
        median,
        ordered.last
      )
    )

private[flashalign] object LinearRegionalRefitPlan3:
  def create(
      selection: PatchSampleSet3,
      maximumRegions: Int,
      seed: Long
  ): Either[LinearValidationError, LinearRegionalRefitPlan3] =
    if maximumRegions <= 0 then
      Left(
        LinearValidationError.InvalidConfiguration(
          s"maximum regional refits must be positive, got $maximumRegions"
        )
      )
    else
      val byCell = selection.entries.groupBy(_.patch.cell)
      if byCell.isEmpty then
        Left(LinearValidationError.InvalidConfiguration("regional refits require cells"))
      else
        val count = math.min(maximumRegions, byCell.size)
        val buckets = Array.fill(count)(mutable.ArrayBuffer.empty[(WorldCell3, Int)])
        byCell.toVector
          .sortBy { case (cell, _) => (cellHash(cell, seed), cell.i, cell.j, cell.k) }
          .zipWithIndex
          .foreach { case ((cell, entries), index) =>
            entries.foreach(entry => buckets(index % count) += ((cell, entry.patch.id)))
          }
        val regions = buckets.zipWithIndex.map { case (items, id) =>
          LinearRegionalRefitRegion3(
            id,
            items.map(_._2).toSet,
            items.map(_._1).toSet
          )
        }.toVector
        Right(new LinearRegionalRefitPlan3(selection.id, regions))

  private def cellHash(cell: WorldCell3, seed: Long): Long =
    mix(seed ^ cell.i * 0x9e3779b97f4a7c15L ^ cell.j * 0xbf58476d1ce4e5b9L ^ cell.k * 0x94d049bb133111ebL)

  private def mix(input: Long): Long =
    var value = input
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
    value ^ (value >>> 31)

private[flashalign] sealed trait LinearFitDecision3 derives CanEqual:
  def accepted: Boolean

private[flashalign] object LinearFitDecision3:
  case object Accepted extends LinearFitDecision3:
    val accepted: Boolean = true

  case object NonConverged extends LinearFitDecision3:
    val accepted: Boolean = false

  final case class InsufficientOverlap(observed: Double, required: Double)
      extends LinearFitDecision3:
    val accepted: Boolean = false

  final case class InsufficientSpatialCoverage(observed: Double, required: Double)
      extends LinearFitDecision3:
    val accepted: Boolean = false

  final case class InsufficientDataInformation(
      rank: Int,
      requiredRank: Int,
      conditionNumber: Double,
      conditionLimit: Double
  ) extends LinearFitDecision3:
    val accepted: Boolean = false

  final case class Ambiguous(candidateDisagreementMillimetres: Double)
      extends LinearFitDecision3:
    val accepted: Boolean = false

  final case class RegionallyUnstable(observed: Double, limit: Double)
      extends LinearFitDecision3:
    val accepted: Boolean = false

private[flashalign] object LinearFitAcceptance3:
  def decide[State](
      optimizerConverged: Boolean,
      requiredDataRank: Int,
      selection: LinearSelectionComparison3[State],
      audit: LinearEvaluation3,
      regional: LinearRegionalStability3,
      config: LinearQcConfig3
  ): LinearFitDecision3 =
    if !optimizerConverged then LinearFitDecision3.NonConverged
    else if audit.patch.overlapFraction < config.minimumOverlapFraction then
      LinearFitDecision3.InsufficientOverlap(
        audit.patch.overlapFraction,
        config.minimumOverlapFraction
      )
    else if audit.patch.spatialInlierCoverage < config.minimumSpatialInlierCoverage then
      LinearFitDecision3.InsufficientSpatialCoverage(
        audit.patch.spatialInlierCoverage,
        config.minimumSpatialInlierCoverage
      )
    else if
      audit.information.dataRank < requiredDataRank ||
        audit.information.scaledConditionNumber > config.maximumDataConditionNumber
    then
      LinearFitDecision3.InsufficientDataInformation(
        audit.information.dataRank,
        requiredDataRank,
        audit.information.scaledConditionNumber,
        config.maximumDataConditionNumber
      )
    else if selection.competingAlignments then
      LinearFitDecision3.Ambiguous(selection.candidateDisagreementMillimetres)
    else if regional.maximumDisplacementMillimetres > config.maximumRegionalRefitDisplacementMillimetres then
      LinearFitDecision3.RegionallyUnstable(
        regional.maximumDisplacementMillimetres,
        config.maximumRegionalRefitDisplacementMillimetres
      )
    else LinearFitDecision3.Accepted
