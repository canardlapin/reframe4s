package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame

import scala.collection.mutable

private[flashalign] enum PatchSampleRole derives CanEqual:
  case Optimization
  case Selection
  case Audit

private[flashalign] enum SpatialLeakagePolicy derives CanEqual:
  /**
   * Patch centres from one world cell stay in one role. Stencil dependence
   * across adjacent cell boundaries is deliberately not claimed absent.
   */
  case CenterCellsDisjointStencilDependenceDeclared

private[flashalign] enum SamplingParameter derives CanEqual:
  case SelectionFraction
  case AuditFraction
  case UniformProbabilityMixture
  case OptimizationDraws
  case SelectionDraws
  case AuditDraws
  case RefreshOrdinal

private[flashalign] sealed trait PatchSamplingError derives CanEqual:
  def message: String

private[flashalign] object PatchSamplingError:
  final case class InvalidOpenUnitInterval(
      parameter: SamplingParameter,
      value: Double
  ) extends PatchSamplingError:
    val message: String =
      s"$parameter must be finite and in (0, 1), got $value"

  final case class InvalidPositiveCount(
      parameter: SamplingParameter,
      value: Int
  ) extends PatchSamplingError:
    val message: String = s"$parameter must be positive, got $value"

  final case class InvalidNonNegativeCount(
      parameter: SamplingParameter,
      value: Int
  ) extends PatchSamplingError:
    val message: String = s"$parameter must be non-negative, got $value"

  final case class InvalidRoleFractions(
      selection: Double,
      audit: Double
  ) extends PatchSamplingError:
    val message: String =
      s"selection and audit fractions must leave positive optimization mass, got $selection and $audit"

  final case class InsufficientWorldCells(actual: Int, required: Int)
      extends PatchSamplingError:
    val message: String =
      s"role partition requires at least $required occupied world cells, got $actual"

  final case class EmptyRole(role: PatchSampleRole)
      extends PatchSamplingError:
    val message: String = s"$role role has no eligible patches"

  final case class InvalidQuality(
      role: PatchSampleRole,
      patchId: Int,
      value: Double
  ) extends PatchSamplingError:
    val message: String =
      s"$role patch $patchId quality must be finite and positive, got $value"

  final case class InvalidTotalQuality(
      role: PatchSampleRole,
      value: Double
  ) extends PatchSamplingError:
    val message: String =
      s"$role total quality must be finite and positive, got $value"

  case object WorkspacePlanMismatch extends PatchSamplingError:
    val message: String =
      "patch-sampling workspace belongs to a different plan"

  case object SelectionIdentityMismatch extends PatchSamplingError:
    val message: String =
      "model-selection completion must name this plan's fixed selection set"

  case object AuditBeforeModelSelection extends PatchSamplingError:
    val message: String =
      "audit samples cannot be opened before model selection is completed"

  case object AuditAlreadyOpened extends PatchSamplingError:
    val message: String = "audit samples have already been opened"

private[flashalign] final class PatchSamplingConfig private (
    val selectionFraction: Double,
    val auditFraction: Double,
    val uniformProbabilityMixture: Double,
    val optimizationDraws: Int,
    val selectionDraws: Int,
    val auditDraws: Int,
    val seed: Long
)

private[flashalign] object PatchSamplingConfig:
  def create(
      selectionFraction: Double = 0.15,
      auditFraction: Double = 0.15,
      uniformProbabilityMixture: Double = 0.25,
      optimizationDraws: Int = 4000,
      selectionDraws: Int = 2000,
      auditDraws: Int = 2000,
      seed: Long = 0x4f1bbcdc6762c9d5L
  ): Either[PatchSamplingError, PatchSamplingConfig] =
    if !openUnit(selectionFraction) then
      Left(
        PatchSamplingError.InvalidOpenUnitInterval(
          SamplingParameter.SelectionFraction,
          selectionFraction
        )
      )
    else if !openUnit(auditFraction) then
      Left(
        PatchSamplingError.InvalidOpenUnitInterval(
          SamplingParameter.AuditFraction,
          auditFraction
        )
      )
    else if selectionFraction + auditFraction >= 1.0 then
      Left(
        PatchSamplingError.InvalidRoleFractions(
          selectionFraction,
          auditFraction
        )
      )
    else if !openUnit(uniformProbabilityMixture) then
      Left(
        PatchSamplingError.InvalidOpenUnitInterval(
          SamplingParameter.UniformProbabilityMixture,
          uniformProbabilityMixture
        )
      )
    else if optimizationDraws <= 0 then
      Left(
        PatchSamplingError.InvalidPositiveCount(
          SamplingParameter.OptimizationDraws,
          optimizationDraws
        )
      )
    else if selectionDraws <= 0 then
      Left(
        PatchSamplingError.InvalidPositiveCount(
          SamplingParameter.SelectionDraws,
          selectionDraws
        )
      )
    else if auditDraws <= 0 then
      Left(
        PatchSamplingError.InvalidPositiveCount(
          SamplingParameter.AuditDraws,
          auditDraws
        )
      )
    else
      Right(
        new PatchSamplingConfig(
          selectionFraction,
          auditFraction,
          uniformProbabilityMixture,
          optimizationDraws,
          selectionDraws,
          auditDraws,
          seed
        )
      )

  private def openUnit(value: Double): Boolean =
    value.isFinite && value > 0.0 && value < 1.0

private[flashalign] final case class PatchSampleSetId(
    populationFingerprint: Long,
    samplingFingerprint: Long,
    role: PatchSampleRole,
    refreshOrdinal: Int,
    drawSeed: Long
)

private[flashalign] final class WeightedPatchDraw3 private[flashalign] (
    val patch: PopulationPatch3,
    val multiplicity: Int,
    val drawProbability: Double,
    val objectiveWeight: Double
)

private[flashalign] final case class PatchSampleSetDiagnostics(
    role: PatchSampleRole,
    poolPatches: Int,
    requestedDraws: Int,
    distinctDraws: Int,
    repeatedDraws: Int,
    minimumDrawProbability: Double,
    maximumDrawProbability: Double,
    uniformProbabilityMixture: Double
)

/** One immutable with-replacement draw with repeated IDs aggregated. */
private[flashalign] final class PatchSampleSet3 private[flashalign] (
    val id: PatchSampleSetId,
    val entries: Vector[WeightedPatchDraw3],
    val diagnostics: PatchSampleSetDiagnostics
):
  def weightedEstimate(loss: PopulationPatch3 => Double): Double =
    entries.foldLeft(0.0) { (total, entry) =>
      total + entry.objectiveWeight * loss(entry.patch)
    }

private[flashalign] final case class PatchRolePartitionDiagnostics(
    leakagePolicy: SpatialLeakagePolicy,
    supportsGeneralizationClaim: Boolean,
    optimizationCells: Int,
    selectionCells: Int,
    auditCells: Int,
    optimizationPatches: Int,
    selectionPatches: Int,
    auditPatches: Int
)

private[flashalign] final class PatchSamplingDistribution3 private[flashalign] (
    val role: PatchSampleRole,
    val patches: Vector[PopulationPatch3],
    val drawProbabilities: Vector[Double],
    val totalQuality: Double
):
  def targetWeightedMean(loss: PopulationPatch3 => Double): Double =
    patches.foldLeft(0.0) { (total, patch) =>
      total + patch.qualityWeight * loss(patch) / totalQuality
    }

  /** Exact enumeration of a one-draw Horvitz-style estimator. */
  def expectedOneDrawEstimate(loss: PopulationPatch3 => Double): Double =
    patches.indices.foldLeft(0.0) { (total, index) =>
      val patch = patches(index)
      val probability = drawProbabilities(index)
      val oneDrawWeight =
        patch.qualityWeight / (totalQuality * probability)
      total + probability * oneDrawWeight * loss(patch)
    }

private[flashalign] final class PatchSamplingWorkspace3 private[flashalign] (
    private val owner: AnyRef
):
  private var selectionCompleted = false
  private var auditOpened = false

  private[flashalign] def completeSelection(
      candidateOwner: AnyRef,
      expected: PatchSampleSetId,
      actual: PatchSampleSetId
  ): Either[PatchSamplingError, Unit] =
    if !(owner eq candidateOwner) then
      Left(PatchSamplingError.WorkspacePlanMismatch)
    else if expected != actual then
      Left(PatchSamplingError.SelectionIdentityMismatch)
    else
      selectionCompleted = true
      Right(())

  private[flashalign] def openAudit(
      candidateOwner: AnyRef
  ): Either[PatchSamplingError, Unit] =
    if !(owner eq candidateOwner) then
      Left(PatchSamplingError.WorkspacePlanMismatch)
    else if !selectionCompleted then
      Left(PatchSamplingError.AuditBeforeModelSelection)
    else if auditOpened then Left(PatchSamplingError.AuditAlreadyOpened)
    else
      auditOpened = true
      Right(())

private[flashalign] final class PatchSamplePlan3[
    F <: Frame[D3],
    +C
] private (
    val population: PatchPopulation3[F, C],
    val config: PatchSamplingConfig,
    val partitionDiagnostics: PatchRolePartitionDiagnostics,
    private val populationFingerprint: Long,
    private val optimizationDistribution: PatchSamplingDistribution3,
    private val selectionDistribution: PatchSamplingDistribution3,
    private val auditDistribution: PatchSamplingDistribution3,
    private val fixedSelection: PatchSampleSet3,
    private val fixedAudit: PatchSampleSet3
):
  def newWorkspace(): PatchSamplingWorkspace3 =
    new PatchSamplingWorkspace3(this)

  def refreshOptimization(
      refreshOrdinal: Int
  ): Either[PatchSamplingError, PatchSampleSet3] =
    if refreshOrdinal < 0 then
      Left(
        PatchSamplingError.InvalidNonNegativeCount(
          SamplingParameter.RefreshOrdinal,
          refreshOrdinal
        )
      )
    else
      Right(
        PatchSamplePlan3.draw(
          optimizationDistribution,
          config.optimizationDraws,
          config.seed ^ 0x243f6a8885a308d3L ^ refreshOrdinal.toLong,
          populationFingerprint,
          refreshOrdinal,
          config.uniformProbabilityMixture
        )
      )

  /** The exact same immutable selection draw is reused at every checkpoint. */
  def selection: PatchSampleSet3 = fixedSelection

  def markModelSelectionComplete(
      selectionId: PatchSampleSetId,
      workspace: PatchSamplingWorkspace3
  ): Either[PatchSamplingError, Unit] =
    workspace.completeSelection(this, fixedSelection.id, selectionId)

  def openAudit(
      workspace: PatchSamplingWorkspace3
  ): Either[PatchSamplingError, PatchSampleSet3] =
    workspace.openAudit(this).map(_ => fixedAudit)

  private[flashalign] def distribution(
      role: PatchSampleRole
  ): PatchSamplingDistribution3 =
    role match
      case PatchSampleRole.Optimization => optimizationDistribution
      case PatchSampleRole.Selection    => selectionDistribution
      case PatchSampleRole.Audit        => auditDistribution

private[flashalign] object PatchSamplePlan3:
  private val SelectionSalt = 0xa4093822299f31d0L
  private val AuditSalt = 0x082efa98ec4e6c89L

  def compile[F <: Frame[D3], C](
      population: PatchPopulation3[F, C],
      config: PatchSamplingConfig
  ): Either[PatchSamplingError, PatchSamplePlan3[F, C]] =
    partition(population, config).flatMap { partitioned =>
      for
        optimization <- distribution(
          PatchSampleRole.Optimization,
          partitioned.optimization,
          config.uniformProbabilityMixture
        )
        selection <- distribution(
          PatchSampleRole.Selection,
          partitioned.selection,
          config.uniformProbabilityMixture
        )
        audit <- distribution(
          PatchSampleRole.Audit,
          partitioned.audit,
          config.uniformProbabilityMixture
        )
      yield
        val fingerprint = fingerprintOf(population)
        val selectionSet =
          draw(
            selection,
            config.selectionDraws,
            config.seed ^ SelectionSalt,
            fingerprint,
            refreshOrdinal = 0,
            config.uniformProbabilityMixture
          )
        val auditSet =
          draw(
            audit,
            config.auditDraws,
            config.seed ^ AuditSalt,
            fingerprint,
            refreshOrdinal = 0,
            config.uniformProbabilityMixture
          )
        new PatchSamplePlan3(
          population,
          config,
          partitioned.diagnostics,
          fingerprint,
          optimization,
          selection,
          audit,
          selectionSet,
          auditSet
        )
    }

  private final class Partitioned(
      val optimization: Vector[PopulationPatch3],
      val selection: Vector[PopulationPatch3],
      val audit: Vector[PopulationPatch3],
      val diagnostics: PatchRolePartitionDiagnostics
  )

  private def partition[F <: Frame[D3], C](
      population: PatchPopulation3[F, C],
      config: PatchSamplingConfig
  ): Either[PatchSamplingError, Partitioned] =
    val byCell = population.patches.groupBy(_.cell)
    if byCell.size < 3 then
      Left(PatchSamplingError.InsufficientWorldCells(byCell.size, 3))
    else
      val orderedCells =
        byCell.keys.toVector.sortBy(cell =>
          (cellOrder(cell, config.seed), cell.i, cell.j, cell.k)
        )
      val counts = roleCellCounts(
        orderedCells.size,
        config.selectionFraction,
        config.auditFraction
      )
      val selectionCells = orderedCells.take(counts._2).toSet
      val auditCells =
        orderedCells.slice(counts._2, counts._2 + counts._3).toSet
      val optimizationCells =
        orderedCells.drop(counts._2 + counts._3).toSet
      val optimization =
        population.patches.filter(patch => optimizationCells.contains(patch.cell))
      val selection =
        population.patches.filter(patch => selectionCells.contains(patch.cell))
      val audit =
        population.patches.filter(patch => auditCells.contains(patch.cell))
      val diagnostics =
        PatchRolePartitionDiagnostics(
          SpatialLeakagePolicy.CenterCellsDisjointStencilDependenceDeclared,
          supportsGeneralizationClaim = false,
          optimizationCells.size,
          selectionCells.size,
          auditCells.size,
          optimization.size,
          selection.size,
          audit.size
        )
      Right(new Partitioned(optimization, selection, audit, diagnostics))

  private def roleCellCounts(
      total: Int,
      selectionFraction: Double,
      auditFraction: Double
  ): (Int, Int, Int) =
    var selection = math.max(1, math.round(total * selectionFraction).toInt)
    var audit = math.max(1, math.round(total * auditFraction).toInt)
    while selection + audit >= total do
      if selection >= audit && selection > 1 then selection -= 1
      else if audit > 1 then audit -= 1
    (total - selection - audit, selection, audit)

  private def distribution(
      role: PatchSampleRole,
      patches: Vector[PopulationPatch3],
      uniformMixture: Double
  ): Either[PatchSamplingError, PatchSamplingDistribution3] =
    if patches.isEmpty then Left(PatchSamplingError.EmptyRole(role))
    else
      patches.collectFirst {
        case patch
            if !patch.qualityWeight.isFinite || patch.qualityWeight <= 0.0 =>
          PatchSamplingError.InvalidQuality(
            role,
            patch.id,
            patch.qualityWeight
          )
      } match
        case Some(error) => Left(error)
        case None =>
          val totalQuality = patches.map(_.qualityWeight).sum
          if !totalQuality.isFinite || totalQuality <= 0.0 then
            Left(PatchSamplingError.InvalidTotalQuality(role, totalQuality))
          else
            val uniform = uniformMixture / patches.size.toDouble
            val weightedMixture = 1.0 - uniformMixture
            val rawProbabilities = patches.map { patch =>
              uniform + weightedMixture * patch.qualityWeight / totalQuality
            }
            val probabilityTotal = rawProbabilities.sum
            val probabilities = rawProbabilities.map(_ / probabilityTotal)
            Right(
              new PatchSamplingDistribution3(
                role,
                patches,
                probabilities,
                totalQuality
              )
            )

  private[flashalign] def draw(
      distribution: PatchSamplingDistribution3,
      draws: Int,
      seed: Long,
      populationFingerprint: Long,
      refreshOrdinal: Int,
      uniformMixture: Double
  ): PatchSampleSet3 =
    val cumulative = new Array[Double](distribution.patches.size)
    var total = 0.0
    var index = 0
    while index < cumulative.length do
      total += distribution.drawProbabilities(index)
      cumulative(index) = total
      index += 1
    cumulative(cumulative.length - 1) = 1.0
    val random = new SplitMix64(seed)
    val multiplicities = mutable.HashMap.empty[Int, Int]
    var drawIndex = 0
    while drawIndex < draws do
      val selected = lowerBound(cumulative, random.nextDouble())
      multiplicities.update(
        selected,
        multiplicities.getOrElse(selected, 0) + 1
      )
      drawIndex += 1
    val entries =
      multiplicities.toVector
        .sortBy { case (patchIndex, _) => distribution.patches(patchIndex).id }
        .map { case (patchIndex, multiplicity) =>
          val patch = distribution.patches(patchIndex)
          val probability = distribution.drawProbabilities(patchIndex)
          val weight =
            multiplicity.toDouble * patch.qualityWeight /
              (draws.toDouble * distribution.totalQuality * probability)
          new WeightedPatchDraw3(
            patch,
            multiplicity,
            probability,
            weight
          )
        }
    val probabilities = distribution.drawProbabilities
    val diagnostics =
      PatchSampleSetDiagnostics(
        distribution.role,
        distribution.patches.size,
        draws,
        entries.size,
        draws - entries.size,
        probabilities.min,
        probabilities.max,
        uniformMixture
      )
    new PatchSampleSet3(
      PatchSampleSetId(
        populationFingerprint,
        samplingFingerprint(distribution, draws, uniformMixture),
        distribution.role,
        refreshOrdinal,
        seed
      ),
      entries,
      diagnostics
    )

  private def lowerBound(cumulative: Array[Double], value: Double): Int =
    var low = 0
    var high = cumulative.length - 1
    while low < high do
      val middle = low + (high - low) / 2
      if value < cumulative(middle) then high = middle
      else low = middle + 1
    low

  private def fingerprintOf[F <: Frame[D3], C](
      population: PatchPopulation3[F, C]
  ): Long =
    population.patches.foldLeft(
      mix64(
        population.sourceLevel.level.ordinal.toLong ^
          population.size.toLong ^
          java.lang.Double.doubleToLongBits(
            population.stencil.spacingMillimetres
          )
      )
    ) { (state, patch) =>
      mix64(
        state ^ patch.id.toLong ^
          java.lang.Double.doubleToLongBits(patch.centerX) ^
          java.lang.Long.rotateLeft(
            java.lang.Double.doubleToLongBits(patch.centerY),
            17
          ) ^
          java.lang.Long.rotateLeft(
            java.lang.Double.doubleToLongBits(patch.centerZ),
            34
          ) ^
          java.lang.Double.doubleToLongBits(patch.qualityWeight) ^
          java.lang.Double.doubleToLongBits(patch.minimumStencilSupport)
      )
    }

  private def samplingFingerprint(
      distribution: PatchSamplingDistribution3,
      draws: Int,
      uniformMixture: Double
  ): Long =
    distribution.patches.indices.foldLeft(
      mix64(
        draws.toLong ^
          java.lang.Double.doubleToLongBits(uniformMixture) ^
          distribution.role.ordinal.toLong
      )
    ) { (state, index) =>
      mix64(
        state ^ distribution.patches(index).id.toLong ^
          java.lang.Double.doubleToLongBits(
            distribution.drawProbabilities(index)
          )
      )
    }

  private def cellOrder(cell: WorldCell3, seed: Long): Long =
    mix64(
      seed ^ mix64(cell.i) ^
        java.lang.Long.rotateLeft(mix64(cell.j), 21) ^
        java.lang.Long.rotateLeft(mix64(cell.k), 42)
    ) & Long.MaxValue

  private[flashalign] def mix64(value: Long): Long =
    var z = value
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)

  private final class SplitMix64(seed: Long):
    private var state = seed

    def nextDouble(): Double =
      state += 0x9e3779b97f4a7c15L
      val bits = mix64(state) >>> 11
      bits.toDouble * 1.1102230246251565e-16

private[flashalign] final case class PatchObjectiveConfigSnapshot(
    positivePolarityPrior: Double,
    tau: Double,
    outlierFloor: Double,
    minimumContrastEnergy: Double,
    correlationRoundingTolerance: Double
)

private[flashalign] object PatchObjectiveConfigSnapshot:
  def from(config: PatchObjectiveConfig): PatchObjectiveConfigSnapshot =
    PatchObjectiveConfigSnapshot(
      config.positivePolarityPrior,
      config.tau,
      config.outlierFloor,
      config.minimumContrastEnergy,
      config.correlationRoundingTolerance
    )

private[flashalign] final case class FrozenPatchObjectiveId(
    sampleSetId: PatchSampleSetId,
    config: PatchObjectiveConfigSnapshot
)

private[flashalign] final case class PatchLinearizationStamp(
    objectiveId: FrozenPatchObjectiveId
)

private[flashalign] final case class PatchObjectiveHistoryEntry(
    objectiveId: FrozenPatchObjectiveId,
    iteration: Int,
    objective: Double
)

/** Frozen patch IDs, weights and loss parameters for one trust-region block. */
private[flashalign] final class FrozenPatchObjective3 private (
    val samples: PatchSampleSet3,
    private val config: PatchObjectiveConfig,
    val id: FrozenPatchObjectiveId
):
  val minimumContrastEnergy: Double = config.minimumContrastEnergy

  def stamp: PatchLinearizationStamp = PatchLinearizationStamp(id)

  def accepts(stamp: PatchLinearizationStamp): Boolean =
    stamp.objectiveId == id

  /** Trial evaluations recompute correlation and posterior under frozen config. */
  def evaluate(
      entry: WeightedPatchDraw3,
      fixedSamples: Array[Double],
      completeInterpolationSupport: Boolean
  ): Either[PatchObjectiveError, PatchObjectiveValue] =
    PatchObjective.evaluate(
      entry.patch.moving,
      fixedSamples,
      completeInterpolationSupport,
      config
    )

  private[flashalign] def evaluateInto(
      entry: WeightedPatchDraw3,
      fixedSamples: Array[Double],
      completeInterpolationSupport: Boolean,
      scratch: PatchObjectiveScratch
  ): PatchObjectiveError | Null =
    PatchObjective.evaluateInto(
      entry.patch.moving,
      fixedSamples,
      completeInterpolationSupport,
      config,
      scratch
    )

  private[flashalign] def evaluateCorrelation(
      correlation: Double
  ): Either[PatchObjectiveError, PatchObjectiveValue] =
    PatchObjective.evaluateCorrelation(correlation, config)

  private[flashalign] def evaluateCorrelationInto(
      correlation: Double,
      scratch: PatchObjectiveScratch
  ): PatchObjectiveError | Null =
    PatchObjective.evaluateCorrelationInto(correlation, config, scratch)

  private[flashalign] def invalid(
      reason: PatchInvalidReason
  ): PatchObjectiveValue =
    PatchObjective.invalid(config, reason)

  private[flashalign] def invalidInto(
      reason: PatchInvalidReason,
      scratch: PatchObjectiveScratch
  ): Unit =
    PatchObjective.invalidInto(config, reason, scratch)

  def history(
      iteration: Int,
      objective: Double
  ): PatchObjectiveHistoryEntry =
    PatchObjectiveHistoryEntry(id, iteration, objective)

private[flashalign] object FrozenPatchObjective3:
  def create(
      samples: PatchSampleSet3,
      config: PatchObjectiveConfig
  ): FrozenPatchObjective3 =
    new FrozenPatchObjective3(
      samples,
      config,
      FrozenPatchObjectiveId(
        samples.id,
        PatchObjectiveConfigSnapshot.from(config)
      )
    )
