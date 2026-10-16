package reframe4s.flashalign

import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import reframe4s.multiscale.GaussianWidthUnit
import reframe4s.multiscale.GridTower
import reframe4s.multiscale.IsotropicGaussianPsf3
import reframe4s.multiscale.ScaleLevel
import reframe4s.multiscale.ScaleSchedule
import reframe4s.multiscale.ScaleSpec
import reframe4s.multiscale.SupportAwarePyramid3
import reframe4s.multiscale.SupportAwarePyramidConfig3
import reframe4s.multiscale.SupportAwarePyramidLevel3
import reframe4s.multiscale.SupportAwarePyramidWorkspace3

final class PatchSampleSetSuite extends munit.FunSuite:
  test("partitions disjoint centre cells and records dependent holdout semantics"):
    val population = populationWithCells(12, patchesPerCell = 3)
    val config = samplingConfig(seed = 77L)
    val first = plan(population, config)
    val second = plan(population, config)
    val optimization = first.distribution(PatchSampleRole.Optimization)
    val selection = first.distribution(PatchSampleRole.Selection)
    val audit = first.distribution(PatchSampleRole.Audit)

    val optimizationCells = optimization.patches.map(_.cell).toSet
    val selectionCells = selection.patches.map(_.cell).toSet
    val auditCells = audit.patches.map(_.cell).toSet
    assertEquals(optimizationCells.intersect(selectionCells), Set.empty)
    assertEquals(optimizationCells.intersect(auditCells), Set.empty)
    assertEquals(selectionCells.intersect(auditCells), Set.empty)
    assertEquals(
      optimization.patches.map(_.id).toSet.size +
        selection.patches.map(_.id).toSet.size +
        audit.patches.map(_.id).toSet.size,
      population.size
    )
    assertEquals(
      first.partitionDiagnostics.leakagePolicy,
      SpatialLeakagePolicy.CenterCellsDisjointStencilDependenceDeclared
    )
    assert(!first.partitionDiagnostics.supportsGeneralizationClaim)
    assert(first.selection eq first.selection)
    assertEquals(first.selection.id, second.selection.id)
    assertEquals(
      first.selection.entries.map(entry => entry.patch.id -> entry.multiplicity),
      second.selection.entries.map(entry => entry.patch.id -> entry.multiplicity)
    )

  test("enumerates the unbiased weighted estimand and aggregates repeat draws"):
    val population = populationWithCells(15, patchesPerCell = 2)
    val config = samplingConfig(seed = 991L, optimizationDraws = 250)
    val compiled = plan(population, config)
    val distribution = compiled.distribution(PatchSampleRole.Optimization)
    val loss = (patch: PopulationPatch3) =>
      0.25 + patch.id.toDouble * patch.id.toDouble

    assertEqualsDouble(
      distribution.expectedOneDrawEstimate(loss),
      distribution.targetWeightedMean(loss),
      2e-13
    )
    assertEqualsDouble(distribution.drawProbabilities.sum, 1.0, 2e-15)
    assert(distribution.drawProbabilities.forall(_ > 0.0))

    val sampled = sample(compiled.refreshOptimization(0))
    assertEquals(sampled.entries.map(_.multiplicity).sum, 250)
    assert(sampled.diagnostics.repeatedDraws > 0)
    sampled.entries.foreach { entry =>
      val index = distribution.patches.indexWhere(_.id == entry.patch.id)
      assert(index >= 0)
      val expected =
        entry.multiplicity.toDouble * entry.patch.qualityWeight /
          (250.0 * distribution.totalQuality *
            distribution.drawProbabilities(index))
      assertEqualsDouble(entry.objectiveWeight, expected, 2e-15)
    }

  test("refreshes invalidate linearizations and histories retain objective identity"):
    val population = populationWithCells(12, patchesPerCell = 3)
    val compiled = plan(population, samplingConfig(seed = 123L))
    val zero = sample(compiled.refreshOptimization(0))
    val one = sample(compiled.refreshOptimization(1))
    assertNotEquals(zero.id, one.id)

    val positiveConfig = objectiveConfig(positivePrior = 0.9)
    val mixedConfig = objectiveConfig(positivePrior = 0.4)
    val first = FrozenPatchObjective3.create(zero, positiveConfig)
    val refreshed = FrozenPatchObjective3.create(one, positiveConfig)
    val changedLoss = FrozenPatchObjective3.create(zero, mixedConfig)
    assert(first.accepts(first.stamp))
    assert(!first.accepts(refreshed.stamp))
    assert(!first.accepts(changedLoss.stamp))
    assertEquals(first.history(3, 1.25).objectiveId, first.id)
    assertEquals(refreshed.history(0, 2.0).objectiveId.sampleSetId, one.id)

    val entry = zero.entries(0)
    val aligned = entry.patch.moving.normalizedCopy
    val reversed = aligned.reverse
    val alignedValue = objective(first.evaluate(entry, aligned, true))
    val reversedValue = objective(first.evaluate(entry, reversed, true))
    assertNotEquals(alignedValue.correlation, reversedValue.correlation)
    assertNotEquals(alignedValue.posterior, reversedValue.posterior)
    assertEquals(
      first.id.config,
      PatchObjectiveConfigSnapshot.from(positiveConfig)
    )

  test("reuses selection and gates audit behind the named selection objective"):
    val population = populationWithCells(12, patchesPerCell = 2)
    val compiled = plan(population, samplingConfig(seed = 456L))
    val workspace = compiled.newWorkspace()

    assertEquals(
      compiled.openAudit(workspace),
      Left(PatchSamplingError.AuditBeforeModelSelection)
    )
    val optimization = sample(compiled.refreshOptimization(0))
    assertEquals(
      compiled.markModelSelectionComplete(optimization.id, workspace),
      Left(PatchSamplingError.SelectionIdentityMismatch)
    )
    assertEquals(
      compiled.markModelSelectionComplete(compiled.selection.id, workspace),
      Right(())
    )
    val audit = sample(compiled.openAudit(workspace))
    assertEquals(audit.diagnostics.role, PatchSampleRole.Audit)
    assertEquals(
      compiled.openAudit(workspace),
      Left(PatchSamplingError.AuditAlreadyOpened)
    )

    val other = plan(population, samplingConfig(seed = 456L))
    assertEquals(
      compiled.openAudit(other.newWorkspace()),
      Left(PatchSamplingError.WorkspacePlanMismatch)
    )

  test("rejects invalid partition and probability configurations"):
    assertEquals(
      PatchSamplingConfig.create(
        selectionFraction = 0.6,
        auditFraction = 0.4
      ),
      Left(PatchSamplingError.InvalidRoleFractions(0.6, 0.4))
    )
    assertEquals(
      PatchSamplingConfig.create(uniformProbabilityMixture = 0.0),
      Left(
        PatchSamplingError.InvalidOpenUnitInterval(
          SamplingParameter.UniformProbabilityMixture,
          0.0
        )
      )
    )
    val twoCells = populationWithCells(2, patchesPerCell = 2)
    PatchSamplePlan3.compile(twoCells, samplingConfig(seed = 1L)) match
      case Left(PatchSamplingError.InsufficientWorldCells(2, 3)) => ()
      case other => fail(s"expected insufficient-cell failure, got $other")

  private def samplingConfig(
      seed: Long,
      optimizationDraws: Int = 120
  ): PatchSamplingConfig =
    PatchSamplingConfig
      .create(
        selectionFraction = 0.2,
        auditFraction = 0.2,
        uniformProbabilityMixture = 0.3,
        optimizationDraws = optimizationDraws,
        selectionDraws = 80,
        auditDraws = 70,
        seed = seed
      )
      .fold(error => fail(error.message), identity)

  private def plan[F <: Frame[D3], C](
      population: PatchPopulation3[F, C],
      config: PatchSamplingConfig
  ): PatchSamplePlan3[F, C] =
    PatchSamplePlan3
      .compile(population, config)
      .fold(error => fail(error.message), identity)

  private def sample(
      value: Either[PatchSamplingError, PatchSampleSet3]
  ): PatchSampleSet3 =
    value.fold(error => fail(error.message), identity)

  private def objective(
      value: Either[PatchObjectiveError, PatchObjectiveValue]
  ): PatchObjectiveValue =
    value.fold(error => fail(error.message), identity)

  private def objectiveConfig(positivePrior: Double): PatchObjectiveConfig =
    PatchObjectiveConfig
      .create(
        positivePolarityPrior = positivePrior,
        tau = 0.55,
        outlierFloor = 0.02,
        minimumContrastEnergy = 1e-12
      )
      .fold(error => fail(error.message), identity)

  private def populationWithCells(
      cellCount: Int,
      patchesPerCell: Int
  ) =
    val frame = geometry(Frame.named[D3](s"sample-population-$cellCount"))
    val grid =
      geometry(
        Grid.forFrame(frame)(Vector(3, 3, 3), Affine.identity[D3])
      )
    val level = preparedLevel(grid)
    val stencil = PhysicalStencil3.create(1.0)
    val patches =
      Vector.tabulate(cellCount * patchesPerCell) { id =>
        val cellIndex = id / patchesPerCell
        val values =
          Array.tabulate(27) { sampleIndex =>
            math.sin(0.2 * sampleIndex + 0.1 * id) +
              0.03 * sampleIndex
          }
        val prepared =
          PatchObjective
            .prepareMoving(values, objectiveConfig(0.9))
            .fold(error => fail(error.message), identity)
        new PopulationPatch3(
          id,
          id,
          cellIndex.toDouble * 12.0,
          0.0,
          0.0,
          WorldCell3(cellIndex.toLong, 0L, 0L),
          prepared,
          qualityWeight = id.toDouble + 1.0,
          minimumStencilSupport = 1.0
        )
      }
    val diagnostics =
      PatchPopulationDiagnostics(
        levelOrdinal = 0,
        targetCandidateCount = 10000,
        totalLatticeCenters = patches.size.toLong,
        centerScanStride = 1,
        screenedCenters = patches.size,
        rejectedIncompleteGeometry = 0,
        rejectedInsufficientSupport = 0,
        rejectedInsufficientContrast = 0,
        eligibleBeforeCellQuota = patches.size,
        rejectedByCellQuota = 0,
        retainedCandidates = patches.size,
        candidateBudgetShortfall = 10000 - patches.size,
        occupiedWorldCells = cellCount,
        stencilSpacingMillimetres = 1.0,
        worldCellSizeMillimetres = 12.0,
        coefficientStorage = "Double",
        normalizedDuringPreparation = true
      )
    new PatchPopulation3(level, stencil, patches, diagnostics)

  private def preparedLevel[F <: Frame[D3]](
      grid: Grid[F, D3]
  ): SupportAwarePyramidLevel3[F, String] =
    val data =
      NDArray.tabulate[Double](3, 3, 3) { (i, j, k) =>
        i.toDouble + 2.0 * j + 3.0 * k
      }
    val support = NDArray.fill[Double, Rank[3]](Shape(3, 3, 3), 1.0)
    val source =
      Sampled
        .continuous(grid, NonSpatialAxes.empty, data)
        .fold(error => fail(error.message), identity)
    val native =
      ScaleSpec
        .create[D3](Vector(1, 1, 1), Vector(0.0, 0.0, 0.0))
        .fold(error => fail(error.message), identity)
    val schedule =
      ScaleSchedule
        .create(Vector(ScaleLevel(native, "native")))
        .fold(error => fail(error.message), identity)
    val tower =
      GridTower
        .build(grid, schedule)
        .fold(error => fail(error.message), identity)
    val zero =
      IsotropicGaussianPsf3
        .declared(0.0, GaussianWidthUnit.SigmaMillimetres)
        .fold(error => fail(error.message), identity)
    val config =
      SupportAwarePyramidConfig3
        .create(zero, Vector(zero))
        .fold(error => fail(error.message), identity)
    SupportAwarePyramid3
      .build(
        source,
        support,
        tower,
        config,
        SupportAwarePyramidWorkspace3.create
      )
      .fold(error => fail(error.message), identity)
      .levels(0)

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)
