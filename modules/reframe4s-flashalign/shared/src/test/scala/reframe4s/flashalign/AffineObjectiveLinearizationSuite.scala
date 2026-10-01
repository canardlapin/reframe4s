package reframe4s.flashalign

import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.Point
import ravel.DType.given
import ravel.NDArray
import reframe4s.core.MapError
import reframe4s.lie.FramedAffine
import reframe4s.resample.LinearValueGradientSampler3
import reframe4s.resample.ResamplingError
import reframe4s.resample.ScalarValueGradient3

final class AffineObjectiveLinearizationSuite extends munit.FunSuite:
  test("12-column streamed objective and loss-only trial match explicit patches"):
    val fixture = new PhysicalFixture
    val workspace = objective(fixture.compiled.newWorkspace())
    val result = objective(fixture.compiled.linearize(fixture.identity, workspace))
    val expectedGradient = new Array[Double](12)
    val expectedCurvature = new Array[Double](12 * 12)
    var expectedObjective = 0.0
    var entryIndex = 0
    while entryIndex < fixture.samples.entries.size do
      val entry = fixture.samples.entries(entryIndex)
      val weight = entry.objectiveWeight
      if entry.patch.id == fixture.incompletePatchId ||
        entry.patch.id == fixture.constantPatchId
      then expectedObjective += weight * fixture.config.outlierCost
      else
        val fixedValues = new Array[Double](fixture.stencil.sampleCount)
        val intensityJacobian = Array.ofDim[Double](fixture.stencil.sampleCount, 12)
        var sample = 0
        while sample < fixture.stencil.sampleCount do
          val movingPoint = geometry(
            Point.in[D3](fixture.moving)(
              entry.patch.centerX + fixture.stencil.offsetX(sample),
              entry.patch.centerY + fixture.stencil.offsetY(sample),
              entry.patch.centerZ + fixture.stencil.offsetZ(sample)
            )
          )
          val fixedPoint = mapped(fixture.identity(movingPoint))
          val sampled = resampling(
            fixture.sampler.at(fixedPoint, ScalarValueGradient3.create)
          )
          fixedValues(sample) = sampled.value
          affineModel(
            fixture.model.writeIntensityJacobianAtFixedWorld(
              fixedPoint.coordinates(0),
              fixedPoint.coordinates(1),
              fixedPoint.coordinates(2),
              sampled.gradientX,
              sampled.gradientY,
              sampled.gradientZ,
              intensityJacobian(sample),
              0
            )
          )
          sample += 1
        val reference = PatchObjectiveReference.evaluate(
          fixture.rawMoving(entry.patch.id),
          fixedValues,
          intensityJacobian,
          fixture.config.positivePolarityPrior,
          fixture.config.tau,
          fixture.config.outlierFloor
        )
        expectedObjective += weight * reference.loss
        var row = 0
        while row < 12 do
          expectedGradient(row) += weight * reference.gradient(row)
          var column = 0
          while column < 12 do
            expectedCurvature(row * 12 + column) +=
              weight * reference.curvature(row * 12 + column)
            column += 1
          row += 1
      entryIndex += 1

    assertEqualsDouble(result.objective, expectedObjective, 2e-12)
    assertArrayClose(result.gradient, expectedGradient, 3e-11)
    var row = 0
    while row < 12 do
      var column = 0
      while column < 12 do
        assertEqualsDouble(
          result.curvature(row, column),
          expectedCurvature(row * 12 + column),
          5e-11
        )
        column += 1
      row += 1

    assertEquals(
      workspace.lastWorkSnapshot,
      LinearDataWorkCounts.from(result.counters)
    )
    val trials = CompiledAffineTrialObjective3.compile(fixture.compiled)
    val trialWorkspace = trials.newWorkspace()
    val trial = complete(
      trialValue(
        trials.evaluate(
          fixture.identity,
          Double.PositiveInfinity,
          trialWorkspace
        )
      )
    )
    assertEqualsDouble(trial.value, result.objective, 2e-13)
    assertEquals(trial.counters.gradientEvaluations, 0L)
    assertEquals(
      trialWorkspace.lastWorkSnapshot,
      LinearDataWorkCounts.from(trial.counters)
    )
    assertEquals(
      fixture.compiled.executionShape,
      AffineObjectiveExecutionShape.SpecializedTwelveParameterStreaming
    )
    assertEquals(result.gradient.length, 12)
    assertEquals(result.curvatureUpper.length, 78)
    assertEquals(result.counters.activePatches, 2)
    assertEquals(result.counters.invalidContrastPatches, 1)
    assertEquals(result.counters.invalidSupportPatches, 1)
    assertEquals(
      result.counters.uniqueInterpolations,
      fixture.compiled.registry.uniquePointCount.toLong
    )
    assertEqualsDouble(
      result.counters.activeObjectiveWeight +
        result.counters.invalidSupportWeight +
        result.counters.invalidContrastWeight,
      1.0,
      1e-15
    )

  private final class PhysicalFixture:
    val moving = geometry(Frame.named[D3]("affine-objective-moving"))
    val fixed = geometry(Frame.named[D3]("affine-objective-fixed"))
    val pivot: Vector[Double] = Vector(8.0, 8.0, 8.0)
    val stencil: PhysicalStencil3 = PhysicalStencil3.create(0.5)
    val config: PatchObjectiveConfig = patch(
      PatchObjectiveConfig.create(
        positivePolarityPrior = 0.65,
        tau = 0.7,
        outlierFloor = 0.03,
        minimumContrastEnergy = 1e-14
      )
    )
    val grid = geometry(Grid.in(fixed)(Vector(20, 20, 20), Affine.identity[D3]))
    val image = sampled(
      Sampled.continuous(
        grid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](20, 20, 20) { (i, j, k) =>
          if i >= 14 then 7.0
          else
            math.sin(0.19 * i.toDouble) +
              0.7 * math.cos(0.13 * j.toDouble) +
              0.03 * i.toDouble * k.toDouble -
              0.02 * j.toDouble * k.toDouble
        }
      )
    )
    val sampler = resampling(LinearValueGradientSampler3.compile(image))
    private val affineConfig = affineModel(AffineModelConfig.create())
    val model = affineModel(
      AffineModel3.atFixedWorld[moving.type, fixed.type](
        moving,
        fixed,
        affineConfig
      )(pivot(0), pivot(1), pivot(2))
    )
    val identity = FramedAffine.betweenFrames[moving.type, fixed.type, D3](
      moving,
      fixed
    )(Affine.identity[D3])
    val constantPatchId = 2
    val incompletePatchId = 3
    val rawMoving: Map[Int, Array[Double]] = Map(
      0 -> fixedSignalAt(5.0, 5.0, 5.0),
      1 -> fixedSignalAt(9.0, 9.0, 9.0).map(value => -value),
      constantPatchId -> sourceSignal(0.31),
      incompletePatchId -> sourceSignal(-0.27)
    )
    private val entries = Vector(
      weightedPatch(0, 5.0, 5.0, 5.0, 0.35),
      weightedPatch(1, 9.0, 9.0, 9.0, 0.30),
      weightedPatch(constantPatchId, 16.0, 8.0, 8.0, 0.20),
      weightedPatch(incompletePatchId, 30.0, 30.0, 30.0, 0.15)
    )
    val samples = new PatchSampleSet3(
      PatchSampleSetId(0x6123L, 0x7124L, PatchSampleRole.Optimization, 1, 0x8125L),
      entries,
      PatchSampleSetDiagnostics(
        PatchSampleRole.Optimization,
        entries.size,
        entries.size,
        entries.size,
        0,
        0.25,
        0.25,
        1.0
      )
    )
    private val frozen = FrozenPatchObjective3.create(samples, config)
    val compiled = objective(
      CompiledAffineObjective3.compile(model, frozen, stencil, sampler)
    )

    private def weightedPatch(
        id: Int,
        x: Double,
        y: Double,
        z: Double,
        weight: Double
    ): WeightedPatchDraw3 =
      val movingPatch = patch(PatchObjective.prepareMoving(rawMoving(id), config))
      val value = new PopulationPatch3(
        id,
        id,
        x,
        y,
        z,
        WorldCell3(math.floor(x / 12.0).toLong, 0L, 0L),
        movingPatch,
        1.0,
        1.0
      )
      new WeightedPatchDraw3(value, 1, 0.25, weight)

    private def fixedSignalAt(x: Double, y: Double, z: Double): Array[Double] =
      Array.tabulate(stencil.sampleCount) { sample =>
        val point = geometry(
          Point.in[D3](fixed)(
            x + stencil.offsetX(sample),
            y + stencil.offsetY(sample),
            z + stencil.offsetZ(sample)
          )
        )
        resampling(sampler.at(point, ScalarValueGradient3.create)).value
      }

    private def sourceSignal(phase: Double): Array[Double] =
      Array.tabulate(stencil.sampleCount) { sample =>
        math.sin(0.23 * sample.toDouble + phase) +
          0.17 * math.cos(0.11 * sample.toDouble - phase)
      }

  private def complete(
      value: AffineTrialObjectiveValue
  ): AffineTrialObjectiveValue.Complete =
    value match
      case result: AffineTrialObjectiveValue.Complete => result
      case other => fail(s"expected complete affine trial, got $other")

  private def assertArrayClose(
      actual: Array[Double],
      expected: Array[Double],
      tolerance: Double
  ): Unit =
    assertEquals(actual.length, expected.length)
    var index = 0
    while index < actual.length do
      assertEqualsDouble(actual(index), expected(index), tolerance)
      index += 1

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def mapped[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def affineModel[A](value: Either[AffineModelError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def sampled[A](value: Either[image4s.ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def resampling[A](value: Either[ResamplingError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def patch[A](value: Either[PatchObjectiveError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def objective[A](
      value: Either[AffineObjectiveLinearizationError, A]
  ): A = value.fold(error => fail(error.message), identity)

  private def trialValue[A](value: Either[AffineTrialObjectiveError, A]): A =
    value.fold(error => fail(error.message), identity)
