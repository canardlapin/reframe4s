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

import scala.collection.mutable

final class LinearValidationSuite extends munit.FunSuite:
  test("selection evaluates every candidate on one fixed ID and audit opens only afterward"):
    val samples = samplePlan(seed = 441L)
    val config = qcConfig()
    val validation = LinearSelectionAuditPlan3.compile[Double, Frame[D3], String](samples, config)
    val workspace = validation.newWorkspace()
    val candidates = Vector(0.0, 1.0, 2.0).zipWithIndex.map { case (state, id) =>
      LinearSelectionCandidate3(id, state)
    }
    val visits = mutable.ArrayBuffer.empty[(Double, PatchSampleSetId)]
    val evaluator = (state: Double, set: PatchSampleSet3) =>
      visits += ((state, set.id))
      Right(evaluationInput(set, lossOffset = math.abs(state - 1.0)))

    validation.evaluateAudit(1.0, workspace)(evaluator) match
      case Left(
            LinearValidationError.Sampling(
              PatchSamplingError.AuditBeforeModelSelection
            )
          ) => ()
      case other => fail(s"expected gated audit, got $other")

    val comparison = validation
      .compareOnSelection(candidates, candidateDisagreementMillimetres = 9.0)(evaluator)
      .fold(error => fail(error.message), identity)
    assertEquals(visits.map(_._1).toVector, Vector(0.0, 1.0, 2.0))
    assert(visits.forall(_._2 == samples.selection.id))
    assertEquals(comparison.best.candidate.state, 1.0)
    assertEquals(comparison.sampleSetId, samples.selection.id)
    val selected = validation
      .finalizeSelection(comparison, workspace)
      .fold(error => fail(error.message), identity)
    assertEquals(selected.candidate.state, 1.0)

    val audit = validation
      .evaluateAudit(selected.candidate.state, workspace)(evaluator)
      .fold(error => fail(error.message), identity)
    assertEquals(audit.patch.sampleSetId.role, PatchSampleRole.Audit)
    assertNotEquals(audit.patch.sampleSetId, comparison.sampleSetId)
    validation.evaluateAudit(1.0, workspace)(evaluator) match
      case Left(
            LinearValidationError.Sampling(
              PatchSamplingError.AuditAlreadyOpened
            )
          ) => ()
      case other => fail(s"expected single-use audit, got $other")

  test("data-only scaled information excludes prior and damping by construction"):
    val set = samplePlan(seed = 42L).selection
    val zero = evaluationInput(
      set,
      lossOffset = 0.0,
      curvature = Array(0.0, 0.0, 0.0),
      metric = Array(4.0, 0.0, 9.0)
    )
    val zeroEvaluation = LinearQcEvaluation3
      .evaluate(set, zero, qcConfig())
      .fold(error => fail(error.message), identity)
    assertEquals(zeroEvaluation.information.dataRank, 0)
    assert(zeroEvaluation.information.scaledConditionNumber.isPosInfinity)
    assert(zeroEvaluation.information.dataOnly)
    assert(zeroEvaluation.information.priorExcluded)
    assert(zeroEvaluation.information.dampingExcluded)

    val identified = zero.copy(dataCurvatureUpper = Array(16.0, 0.0, 9.0))
    val information = LinearQcEvaluation3
      .evaluate(set, identified, qcConfig())
      .fold(error => fail(error.message), identity)
      .information
    assertEquals(information.dataRank, 2)
    assertEqualsDouble(information.scaledConditionNumber, 4.0, 1e-12)
    assertEquals(information.gaugeRank, 2)

  test("low score is insufficient when overlap coverage information ambiguity or stability fails"):
    val samples = samplePlan(seed = 88L)
    val selectionSet = samples.selection
    val auditSet = openAuditSet(samples)
    val config = qcConfig()
    val selected = evaluation(selectionSet, _ => true, _ => 1.0, identityCurvature)
    val stable = regional(selectionSet.id, Vector(0.2, 0.4, 0.1))

    val dropout = evaluation(
      auditSet,
      entry => entry.patch.id == auditSet.entries.head.patch.id,
      _ => 1.0,
      identityCurvature
    )
    decide(selected, dropout, stable, config) match
      case LinearFitDecision3.InsufficientOverlap(_, _) => ()
      case other => fail(s"expected dropout overlap failure, got $other")

    val firstCell = auditSet.entries.head.patch.cell
    val slab = evaluation(
      auditSet,
      _ => true,
      entry => if entry.patch.cell == firstCell then 1.0 else 0.0,
      identityCurvature
    )
    decide(selected, slab, stable, config) match
      case LinearFitDecision3.InsufficientSpatialCoverage(_, _) => ()
      case other => fail(s"expected slab coverage failure, got $other")

    val homogeneous = evaluation(
      auditSet,
      _ => true,
      _ => 1.0,
      Array(0.0, 0.0, 0.0)
    )
    decide(selected, homogeneous, stable, config) match
      case LinearFitDecision3.InsufficientDataInformation(0, 2, condition, _) =>
        assert(condition.isPosInfinity)
      case other => fail(s"expected homogeneous information failure, got $other")

    val ambiguous = selectionComparison(selected, competing = true)
    LinearFitAcceptance3.decide(
      optimizerConverged = true,
      requiredDataRank = 2,
      ambiguous,
      evaluation(auditSet, _ => true, _ => 1.0, identityCurvature),
      stable,
      config
    ) match
      case LinearFitDecision3.Ambiguous(12.0) => ()
      case other => fail(s"expected ambiguity failure, got $other")

    val unstable = regional(selectionSet.id, Vector(0.2, 4.5, 0.1))
    decide(selected, evaluation(auditSet, _ => true, _ => 1.0, identityCurvature), unstable, config) match
      case LinearFitDecision3.RegionallyUnstable(4.5, 3.0) => ()
      case other => fail(s"expected regional instability, got $other")

    LinearFitAcceptance3.decide(
      optimizerConverged = false,
      requiredDataRank = 2,
      selectionComparison(selected, competing = false),
      evaluation(auditSet, _ => true, _ => 1.0, identityCurvature),
      stable,
      config
    ) match
      case LinearFitDecision3.NonConverged => ()
      case other => fail(s"expected nonconverged result, got $other")

  test("regional leave-cell-out plans and results are reproducible"):
    val selection = samplePlan(seed = 909L).selection
    val first = LinearRegionalRefitPlan3
      .create(selection, maximumRegions = 3, seed = 19L)
      .fold(error => fail(error.message), identity)
    val second = LinearRegionalRefitPlan3
      .create(selection, maximumRegions = 3, seed = 19L)
      .fold(error => fail(error.message), identity)
    assertEquals(first.regions, second.regions)
    assertEquals(first.sampleSetId, selection.id)
    val refit = (excluded: Set[Int]) => Right(excluded.toVector.sorted.sum.toDouble / 100.0)
    val displacement = (base: Double, candidate: Double) => math.abs(candidate - base)
    val firstResult = first
      .evaluate(0.0)(refit, displacement)
      .fold(error => fail(error.message), identity)
    val secondResult = second
      .evaluate(0.0)(refit, displacement)
      .fold(error => fail(error.message), identity)
    assertEquals(firstResult, secondResult)
    assertEquals(firstResult.results.flatMap(_.region.excludedPatchIds).toSet, selection.entries.map(_.patch.id).toSet)
    assert(firstResult.maximumDisplacementMillimetres >= firstResult.medianDisplacementMillimetres)

  test("sample identity evidence and materially indefinite data curvature fail closed"):
    val samples = samplePlan(seed = 13L)
    val selection = samples.selection
    val wrong = samplePlan(seed = 14L).selection
    val input = evaluationInput(selection, lossOffset = 0.0)
    LinearQcEvaluation3.evaluate(wrong, input, qcConfig()) match
      case Left(LinearValidationError.SampleIdentityMismatch(expected, actual)) =>
        assertEquals(expected, wrong.id)
        assertEquals(actual, selection.id)
      case other => fail(s"expected sample identity failure, got $other")

    val indefinite = input.copy(dataCurvatureUpper = Array(1.0, 0.0, -0.2))
    LinearQcEvaluation3.evaluate(selection, indefinite, qcConfig()) match
      case Left(
            LinearValidationError.MateriallyNegativeDataEigenvalue(
              value,
              tolerance
            )
          ) =>
        assertEqualsDouble(value, -0.2, 1e-14)
        assert(tolerance > 0.0)
      case other => fail(s"expected indefinite curvature failure, got $other")

  private val identityCurvature = Array(4.0, 0.0, 1.0)

  private def decide(
      selection: LinearEvaluation3,
      audit: LinearEvaluation3,
      stability: LinearRegionalStability3,
      config: LinearQcConfig3
  ): LinearFitDecision3 =
    LinearFitAcceptance3.decide(
      optimizerConverged = true,
      requiredDataRank = 2,
      selectionComparison(selection, competing = false),
      audit,
      stability,
      config
    )

  private def selectionComparison(
      evaluation: LinearEvaluation3,
      competing: Boolean
  ): LinearSelectionComparison3[Double] =
    val candidate = LinearSelectionCandidate3(0, 0.0)
    LinearSelectionComparison3(
      evaluation.patch.sampleSetId,
      Vector(LinearCandidateAssessment3(candidate, evaluation)),
      competing,
      candidateDisagreementMillimetres = 12.0
    )

  private def evaluation(
      set: PatchSampleSet3,
      valid: WeightedPatchDraw3 => Boolean,
      inlier: WeightedPatchDraw3 => Double,
      curvature: Array[Double]
  ): LinearEvaluation3 =
    val evidence = set.entries.map { entry =>
      LinearPatchEvidence3(
        entry.patch.id,
        entry.patch.cell,
        loss = 0.0,
        validSupport = valid(entry),
        inlierWeight = inlier(entry)
      )
    }
    LinearQcEvaluation3
      .evaluate(
        set,
        LinearEvaluationInput3(
          set.id,
          evidence,
          curvature,
          Array(1.0, 0.0, 1.0),
          parameterCount = 2,
          gaugeRank = 2
        ),
        qcConfig()
      )
      .fold(error => fail(error.message), identity)

  private def evaluationInput(
      set: PatchSampleSet3,
      lossOffset: Double,
      curvature: Array[Double] = Array(4.0, 0.0, 1.0),
      metric: Array[Double] = Array(1.0, 0.0, 1.0)
  ): LinearEvaluationInput3 =
    LinearEvaluationInput3(
      set.id,
      set.entries.map { entry =>
        LinearPatchEvidence3(
          entry.patch.id,
          entry.patch.cell,
          lossOffset + entry.patch.id.toDouble * 1e-6,
          validSupport = true,
          inlierWeight = 0.9
        )
      },
      curvature.clone(),
      metric.clone(),
      parameterCount = 2,
      gaugeRank = 2
    )

  private def regional(
      id: PatchSampleSetId,
      values: Vector[Double]
  ): LinearRegionalStability3 =
    val results = values.zipWithIndex.map { case (value, index) =>
      LinearRegionalRefitResult3(
        LinearRegionalRefitRegion3(index, Set(index), Set(WorldCell3(index, 0L, 0L))),
        value
      )
    }
    val ordered = values.sorted
    LinearRegionalStability3(id, results, ordered(ordered.size / 2), values.max)

  private def openAuditSet(samples: PatchSamplePlan3[Frame[D3], String]): PatchSampleSet3 =
    val workspace = samples.newWorkspace()
    samples
      .markModelSelectionComplete(samples.selection.id, workspace)
      .fold(error => fail(error.message), identity)
    samples.openAudit(workspace).fold(error => fail(error.message), identity)

  private def qcConfig(): LinearQcConfig3 =
    LinearQcConfig3
      .create(
        minimumPatchInlierWeight = 0.5,
        minimumOverlapFraction = 0.3,
        minimumSpatialInlierCoverage = 0.4,
        rankRelativeTolerance = 1e-10,
        maximumDataConditionNumber = 10.0,
        maximumRegionalRefitDisplacementMillimetres = 3.0,
        nearEqualObjectiveTolerance = 0.01
      )
      .fold(error => fail(error.message), identity)

  private def samplePlan(seed: Long): PatchSamplePlan3[Frame[D3], String] =
    PatchSamplePlan3
      .compile(
        populationWithCells(cellCount = 15, patchesPerCell = 1),
        PatchSamplingConfig
          .create(
            selectionFraction = 0.3,
            auditFraction = 0.3,
            uniformProbabilityMixture = 0.3,
            optimizationDraws = 30,
            selectionDraws = 20,
            auditDraws = 20,
            seed = seed
          )
          .fold(error => fail(error.message), identity)
      )
      .fold(error => fail(error.message), identity)

  private def populationWithCells(
      cellCount: Int,
      patchesPerCell: Int
  ): PatchPopulation3[Frame[D3], String] =
    val frame = geometry(Frame.named[D3](s"validation-$cellCount"))
    val grid = geometry(Grid.forFrame(frame)(Vector(3, 3, 3), Affine.identity[D3]))
    val level = preparedLevel(grid)
    val stencil = PhysicalStencil3.create(1.0)
    val objectiveConfig = PatchObjectiveConfig
      .create(0.9, 0.55, 0.02, 1e-12)
      .fold(error => fail(error.message), identity)
    val patches = Vector.tabulate(cellCount * patchesPerCell) { id =>
      val cell = id / patchesPerCell
      val moving = PatchObjective
        .prepareMoving(
          Array.tabulate(27)(sample => math.sin(0.2 * sample + 0.1 * id) + 0.03 * sample),
          objectiveConfig
        )
        .fold(error => fail(error.message), identity)
      new PopulationPatch3(
        id,
        id,
        cell.toDouble * 12.0,
        0.0,
        0.0,
        WorldCell3(cell.toLong, 0L, 0L),
        moving,
        qualityWeight = id + 1.0,
        minimumStencilSupport = 1.0
      )
    }
    new PatchPopulation3(
      level,
      stencil,
      patches,
      PatchPopulationDiagnostics(
        0,
        10000,
        patches.size,
        1,
        patches.size,
        0,
        0,
        0,
        patches.size,
        0,
        patches.size,
        10000 - patches.size,
        cellCount,
        1.0,
        12.0,
        "Double",
        normalizedDuringPreparation = true
      )
    )

  private def preparedLevel(
      grid: Grid[Frame[D3], D3]
  ): SupportAwarePyramidLevel3[Frame[D3], String] =
    val data = NDArray.tabulate[Double](3, 3, 3)((i, j, k) => i + 2.0 * j + 3.0 * k)
    val support = NDArray.fill[Double, Rank[3]](Shape(3, 3, 3), 1.0)
    val source = Sampled
      .continuous(grid, NonSpatialAxes.empty, data)
      .fold(error => fail(error.message), identity)
    val native = ScaleSpec
      .create[D3](Vector(1, 1, 1), Vector(0.0, 0.0, 0.0))
      .fold(error => fail(error.message), identity)
    val tower = GridTower
      .build(
        grid,
        ScaleSchedule
          .create(Vector(ScaleLevel(native, "native")))
          .fold(error => fail(error.message), identity)
      )
      .fold(error => fail(error.message), identity)
    val zero = IsotropicGaussianPsf3
      .declared(0.0, GaussianWidthUnit.SigmaMillimetres)
      .fold(error => fail(error.message), identity)
    val config = SupportAwarePyramidConfig3
      .create(zero, Vector(zero))
      .fold(error => fail(error.message), identity)
    SupportAwarePyramid3
      .build(source, support, tower, config, SupportAwarePyramidWorkspace3.create)
      .fold(error => fail(error.message), identity)
      .levels(0)

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)
