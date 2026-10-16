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
import reframe4s.resample.ResamplingError

final class PreparationSuite extends munit.FunSuite:
  test("constructs the exact shared 27-point physical stencil"):
    val stencil = PhysicalStencil3.create(2.5)
    assertEquals(stencil.sampleCount, 27)
    val offsets =
      Vector.tabulate(stencil.sampleCount) { sample =>
        (
          stencil.offsetX(sample),
          stencil.offsetY(sample),
          stencil.offsetZ(sample)
        )
      }
    assertEquals(offsets.distinct.size, 27)
    assertEquals(
      offsets.map(_._1).distinct.sorted,
      Vector(-2.5, 0.0, 2.5)
    )
    assertEquals(
      offsets.map(_._2).distinct.sorted,
      Vector(-2.5, 0.0, 2.5)
    )
    assertEquals(
      offsets.map(_._3).distinct.sorted,
      Vector(-2.5, 0.0, 2.5)
    )
    assert(offsets.contains((0.0, 0.0, 0.0)))

  test("screens, stratifies and normalizes signed source patches deterministically"):
    val shape = Vector(17, 17, 17)
    val frame = geometry(Frame.named[D3]("signed-population"))
    val affine =
      geometry(
        Affine.fromRowMajor[D3](
          Vector(
            1.5, 0.2, 0.0, -13.0,
            0.1, 1.2, 0.3, 4.0,
            0.0, 0.0, -2.0, 9.0,
            0.0, 0.0, 0.0, 1.0
          )
        )
      )
    val grid = geometry(Grid.forFrame(frame)(shape, affine))
    val data =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) =>
          -20.0 + math.sin(0.31 * i) + math.cos(0.27 * j) - 0.07 * k
      }
    assert(data.iterator.forall(_ < 0.0))
    val support =
      NDArray.fill[Double, Rank[3]](Shape(17, 17, 17), 1.0)
    val level = preparedLevel(grid, data, support)
    val config = populationConfig(
      spacing = 1.0,
      cellSize = 5.0,
      maximumPerCell = 8
    )
    val first = population(level, config)
    val second = population(level, config)

    assert(first.size > 0)
    assertEquals(
      first.patches.map(_.id),
      second.patches.map(_.id)
    )
    first.patches.zip(second.patches).foreach { case (left, right) =>
      assertEquals(
        left.moving.normalizedCopy.toVector,
        right.moving.normalizedCopy.toVector
      )
    }
    first.patches.foreach { patch =>
      val normalized = patch.moving.normalizedCopy
      assertEqualsDouble(normalized.sum, 0.0, 2e-14)
      assertEqualsDouble(normalized.map(value => value * value).sum, 1.0, 2e-14)
      assert(patch.qualityWeight > 0.0)
      assertEqualsDouble(patch.minimumStencilSupport, 1.0, 1e-12)
      assertEquals(
        patch.samplePointKey(first.stencil, 0),
        patch.samplePointKey(first.stencil, 0)
      )
    }
    first.patches.groupBy(_.cell).values.foreach(bucket =>
      assert(bucket.size <= 8)
    )
    val diagnostics = first.diagnostics
    assertEquals(diagnostics.targetCandidateCount, 10000)
    assertEquals(diagnostics.coefficientStorage, "Double")
    assert(diagnostics.normalizedDuringPreparation)
    assert(diagnostics.rejectedByCellQuota > 0)
    assertEquals(
      diagnostics.candidateBudgetShortfall,
      diagnostics.targetCandidateCount - diagnostics.retainedCandidates
    )

  test("reports low contrast, missing support and slab-limited populations"):
    val frame = geometry(Frame.named[D3]("support-screening"))
    val shape = Vector(17, 17, 3)
    val grid =
      geometry(
        Grid.forFrame(frame)(shape, Affine.identity[D3])
      )
    val varying =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) => math.sin(i * 0.4) - math.cos(j * 0.3) + k * 0.2
      }
    val full = NDArray.fill[Double, Rank[3]](Shape(17, 17, 3), 1.0)
    val slabPopulation =
      population(
        preparedLevel(grid, varying, full),
        populationConfig(spacing = 0.4)
      )
    assert(slabPopulation.size > 0)
    assert(slabPopulation.diagnostics.rejectedIncompleteGeometry > 0)
    assert(slabPopulation.patches.forall(_.centerZ == 1.0))

    val partial =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, _, _) => if i <= 8 then 1.0 else 0.0
      }
    val partialPopulation =
      population(
        preparedLevel(grid, varying, partial),
        populationConfig(spacing = 0.4)
      )
    assert(partialPopulation.size < slabPopulation.size)
    assert(partialPopulation.diagnostics.rejectedInsufficientSupport > 0)
    assert(partialPopulation.diagnostics.candidateBudgetShortfall > 0)

    val constant =
      NDArray.fill[Double, Rank[3]](Shape(17, 17, 3), -8.0)
    val flatPopulation =
      population(
        preparedLevel(grid, constant, full),
        populationConfig(spacing = 0.4)
      )
    assertEquals(flatPopulation.size, 0)
    assert(flatPopulation.diagnostics.rejectedInsufficientContrast > 0)

  test("rejects a volume too thin for the exact trilinear support contract"):
    val frame = geometry(Frame.named[D3]("tiny-population"))
    val grid =
      geometry(
        Grid.forFrame(frame)(
          Vector(7, 7, 1),
          Affine.identity[D3]
        )
      )
    val data = NDArray.fill[Double, Rank[3]](Shape(7, 7, 1), 1.0)
    val support = NDArray.fill[Double, Rank[3]](Shape(7, 7, 1), 1.0)
    Preparation.buildPopulation(
      preparedLevel(grid, data, support),
      populationConfig(spacing = 0.4),
      objectiveConfig
    ) match
      case Left(
            PreparationError.Sampling(
              ResamplingError.DerivativeExtentTooSmall(2, 1)
            )
          ) => ()
      case other => fail(s"expected thin-volume failure, got $other")

  test("enforces the production candidate range and screening budget"):
    assertEquals(
      PatchPopulationConfig.create(
        stencilSpacingMillimetres = 1.0,
        targetCandidateCount = 9999
      ),
      Left(
        PreparationError.CandidateTargetOutsideProductionRange(
          9999,
          10000,
          30000
        )
      )
    )
    assertEquals(
      PatchPopulationConfig.create(
        stencilSpacingMillimetres = 1.0,
        targetCandidateCount = 10000,
        maximumCentersToScreen = 9999
      ),
      Left(PreparationError.ScreeningBudgetBelowTarget(10000, 9999))
    )

  private def populationConfig(
      spacing: Double,
      cellSize: Double = 12.0,
      maximumPerCell: Int = 16
  ): PatchPopulationConfig =
    PatchPopulationConfig
      .create(
        stencilSpacingMillimetres = spacing,
        worldCellSizeMillimetres = cellSize,
        targetCandidateCount = 10000,
        maximumCentersToScreen = 10000,
        maximumCandidatesPerCell = maximumPerCell
      )
      .fold(error => fail(error.message), identity)

  private def objectiveConfig: PatchObjectiveConfig =
    PatchObjectiveConfig
      .create(
        positivePolarityPrior = 0.9,
        tau = 0.55,
        outlierFloor = 0.02,
        minimumContrastEnergy = 1e-12
      )
      .fold(error => fail(error.message), identity)

  private def population[F <: Frame[D3], C](
      level: SupportAwarePyramidLevel3[F, C],
      config: PatchPopulationConfig
  ): PatchPopulation3[F, C] =
    Preparation
      .buildPopulation(level, config, objectiveConfig)
      .fold(error => fail(error.message), identity)

  private def preparedLevel[F <: Frame[D3]](
      grid: Grid[F, D3],
      data: NDArray[Double, Rank[3]],
      support: NDArray[Double, Rank[3]]
  ): SupportAwarePyramidLevel3[F, String] =
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
