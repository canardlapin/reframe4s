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
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.resample.LinearValueGradientSampler3
import reframe4s.resample.ResamplingError
import reframe4s.resample.ScalarValueGradient3

final class RigidObjectiveLinearizationSuite extends munit.FunSuite:
  test("streamed physical objective gradient and GN matrix match explicit patches"):
    val fixture = new PhysicalFixture
    val state = fixture.identity
    val workspace = objective(fixture.compiled.newWorkspace())
    val result = objective(fixture.compiled.linearize(state, workspace))
    val expectedGradient = new Array[Double](6)
    val expectedCurvature = new Array[Double](36)
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
        val intensityJacobian =
          Array.ofDim[Double](fixture.stencil.sampleCount, 6)
        var sample = 0
        while sample < fixture.stencil.sampleCount do
          val movingPoint = geometry(
            Point.in[D3](fixture.moving)(
              entry.patch.centerX + fixture.stencil.offsetX(sample),
              entry.patch.centerY + fixture.stencil.offsetY(sample),
              entry.patch.centerZ + fixture.stencil.offsetZ(sample)
            )
          )
          val fixedPoint = mapped(state(movingPoint))
          val sampled = resampling(
            fixture.sampler.at(
              fixedPoint,
              ScalarValueGradient3.create
            )
          )
          fixedValues(sample) = sampled.value
          val rx = fixedPoint.coordinates(0) - fixture.pivot(0)
          val ry = fixedPoint.coordinates(1) - fixture.pivot(1)
          val rz = fixedPoint.coordinates(2) - fixture.pivot(2)
          intensityJacobian(sample)(0) = sampled.gradientX
          intensityJacobian(sample)(1) = sampled.gradientY
          intensityJacobian(sample)(2) = sampled.gradientZ
          intensityJacobian(sample)(3) =
            ry * sampled.gradientZ - rz * sampled.gradientY
          intensityJacobian(sample)(4) =
            rz * sampled.gradientX - rx * sampled.gradientZ
          intensityJacobian(sample)(5) =
            rx * sampled.gradientY - ry * sampled.gradientX
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
        while row < 6 do
          expectedGradient(row) += weight * reference.gradient(row)
          var column = 0
          while column < 6 do
            expectedCurvature(row * 6 + column) +=
              weight * reference.curvature(row * 6 + column)
            column += 1
          row += 1
      entryIndex += 1

    assertEqualsDouble(result.objective, expectedObjective, 2e-12)
    assertArrayClose(result.gradient, expectedGradient, 2e-11)
    var row = 0
    while row < 6 do
      var column = 0
      while column < 6 do
        assertEqualsDouble(
          result.curvature(row, column),
          expectedCurvature(row * 6 + column),
          3e-11
        )
        column += 1
      row += 1

    assertEquals(
      fixture.compiled.executionShape,
      RigidObjectiveExecutionShape.SpecializedSixParameterStreaming
    )
    assertEquals(fixture.compiled.parameterCount, 6)
    assertEquals(result.gradient.length, 6)
    assertEquals(result.curvatureUpper.length, 21)
    assertEquals(result.counters.activePatches, 2)
    assertEquals(result.counters.invalidContrastPatches, 1)
    assertEquals(result.counters.invalidSupportPatches, 1)
    assertEquals(
      result.counters.uniqueInterpolations,
      fixture.compiled.registry.uniquePointCount.toLong
    )
    assertEquals(
      result.counters.fullSupportInterpolations +
        result.counters.rejectedInterpolations,
      result.counters.uniqueInterpolations
    )
    assertEquals(
      result.counters.sourceVoxelReads,
      result.counters.fullSupportInterpolations * 8L
    )
    assertEquals(
      workspace.lastWorkSnapshot,
      LinearDataWorkCounts.from(result.counters)
    )
    assertEqualsDouble(result.counters.objectiveWeight, 1.0, 1e-15)
    assertEqualsDouble(
      result.counters.activeObjectiveWeight +
        result.counters.invalidSupportWeight +
        result.counters.invalidContrastWeight,
      1.0,
      1e-15
    )
    assertEqualsDouble(
      result.counters.posteriorOutlierWeight +
        result.counters.posteriorPositiveWeight +
        result.counters.posteriorNegativeWeight,
      1.0,
      2e-15
    )
    assertEqualsDouble(
      result.dataInformation.curvatureTrace,
      result.dataInformation.diagonal.sum,
      1e-15
    )
    assert(result.dataInformation.gradientEuclideanNorm > 0.0)
    assert(result.dataInformation.curvatureFrobeniusNorm > 0.0)

  test("cache identity freezes objective and geometry while tracking pose"):
    val fixture = new PhysicalFixture
    val workspace = objective(fixture.compiled.newWorkspace())
    val first = objective(
      fixture.compiled.linearize(fixture.identity, workspace)
    )
    val firstId = first.cacheId
    val firstGradient = first.gradient
    val firstCurvature = first.curvatureUpper
    val repeated = objective(
      fixture.compiled.linearize(fixture.identity, workspace)
    )
    val repeatedId = repeated.cacheId

    assert(first eq repeated)
    assert(firstGradient eq repeated.gradient)
    assert(firstCurvature eq repeated.curvatureUpper)
    assertEquals(repeatedId, firstId)
    assertEquals(firstId.objectiveId, fixture.frozen.id)
    assertEquals(firstId.geometryFingerprint, fixture.compiled.geometryFingerprint)

    val shifted = rigidModel(
      fixture.model.propose(
        fixture.identity,
        Array(0.05, -0.02, 0.01, 0.0, 0.0, 0.0)
      )
    )
    val shiftedResult = objective(
      fixture.compiled.linearize(shifted, workspace)
    )
    assertEquals(shiftedResult.cacheId.objectiveId, firstId.objectiveId)
    assertEquals(
      shiftedResult.cacheId.geometryFingerprint,
      firstId.geometryFingerprint
    )
    assertNotEquals(
      shiftedResult.cacheId.movingToFixedFingerprint,
      firstId.movingToFixedFingerprint
    )

    val otherCompiled = fixture.compileAgain()
    otherCompiled.linearize(
      fixture.identity,
      workspace
    ) match
      case Left(RigidObjectiveLinearizationError.WorkspacePlanMismatch) => ()
      case other => fail(s"expected workspace-plan mismatch, got $other")

  private final class PhysicalFixture:
    val moving = geometry(Frame.named[D3]("objective-moving"))
    val fixed = geometry(Frame.named[D3]("objective-fixed"))
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
    val grid = geometry(
      Grid.in(fixed)(Vector(20, 20, 20), Affine.identity[D3])
    )
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
    val model = rigidModel(
      RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(
        pivot(0),
        pivot(1),
        pivot(2)
      )
    )
    val identity: Rigid3[moving.type, fixed.type] = rigid(
      Rigid3.fromAffine[moving.type, fixed.type](moving, fixed)(
        Affine.identity[D3]
      )
    )
    val constantPatchId = 2
    val incompletePatchId = 3
    val rawMoving: Map[Int, Array[Double]] = Map(
      0 -> fixedSignalAt(5.0, 5.0, 5.0),
      1 -> fixedSignalAt(9.0, 9.0, 9.0).map(value => -value),
      constantPatchId -> sourceSignal(0.31),
      incompletePatchId -> sourceSignal(-0.27)
    )
    val entries = Vector(
      weightedPatch(0, 5.0, 5.0, 5.0, 0.35),
      weightedPatch(1, 9.0, 9.0, 9.0, 0.30),
      weightedPatch(constantPatchId, 16.0, 8.0, 8.0, 0.20),
      weightedPatch(incompletePatchId, 30.0, 30.0, 30.0, 0.15)
    )
    val samples = new PatchSampleSet3(
      PatchSampleSetId(
        populationFingerprint = 0x52a9L,
        samplingFingerprint = 0x91c3L,
        PatchSampleRole.Optimization,
        refreshOrdinal = 2,
        drawSeed = 0x7712L
      ),
      entries,
      PatchSampleSetDiagnostics(
        PatchSampleRole.Optimization,
        poolPatches = entries.size,
        requestedDraws = entries.size,
        distinctDraws = entries.size,
        repeatedDraws = 0,
        minimumDrawProbability = 0.25,
        maximumDrawProbability = 0.25,
        uniformProbabilityMixture = 1.0
      )
    )
    val frozen: FrozenPatchObjective3 =
      FrozenPatchObjective3.create(samples, config)
    val compiled = compileAgain()

    def compileAgain() =
      objective(
        CompiledRigidObjective3.compile(
          model,
          frozen,
          stencil,
          sampler
        )
      )

    private def weightedPatch(
        id: Int,
        x: Double,
        y: Double,
        z: Double,
        weight: Double
    ): WeightedPatchDraw3 =
      val movingPatch = patch(
        PatchObjective.prepareMoving(rawMoving(id), config)
      )
      val patchValue = new PopulationPatch3(
        id,
        id,
        x,
        y,
        z,
        WorldCell3(math.floor(x / 12.0).toLong, 0L, 0L),
        movingPatch,
        qualityWeight = 1.0,
        minimumStencilSupport = 1.0
      )
      new WeightedPatchDraw3(
        patchValue,
        multiplicity = 1,
        drawProbability = 0.25,
        objectiveWeight = weight
      )

    private def fixedSignalAt(
        x: Double,
        y: Double,
        z: Double
    ): Array[Double] =
      Array.tabulate(stencil.sampleCount) { sample =>
        val point = geometry(
          Point.in[D3](fixed)(
            x + stencil.offsetX(sample),
            y + stencil.offsetY(sample),
            z + stencil.offsetZ(sample)
          )
        )
        resampling(
          sampler.at(point, ScalarValueGradient3.create)
        ).value
      }

    private def sourceSignal(phase: Double): Array[Double] =
      Array.tabulate(stencil.sampleCount) { sample =>
        math.sin(0.23 * sample.toDouble + phase) +
          0.17 * math.cos(0.11 * sample.toDouble - phase)
      }

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

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigidModel[A](value: Either[RigidModelError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def sampled[A](value: Either[image4s.ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def resampling[A](value: Either[ResamplingError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def patch[A](value: Either[PatchObjectiveError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def objective[A](
      value: Either[RigidObjectiveLinearizationError, A]
  ): A =
    value.fold(error => fail(error.message), identity)
