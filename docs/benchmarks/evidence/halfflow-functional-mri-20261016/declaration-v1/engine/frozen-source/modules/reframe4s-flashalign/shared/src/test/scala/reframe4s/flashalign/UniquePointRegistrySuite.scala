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
import reframe4s.resample.LinearValueGradientSampler3
import reframe4s.resample.ResamplingError
import reframe4s.resample.SparseSamplingCounters
import reframe4s.resample.SparseValueGradientBuffer3

final class UniquePointRegistrySuite extends munit.FunSuite:
  test("deduplicates exact stencil points while retaining draw multiplicity"):
    val stencil = PhysicalStencil3.create(1.0)
    val first = patch(0, 5.0, 5.0, 5.0)
    val second = patch(1, 6.0, 5.0, 5.0)
    val samples = sampleSet(Vector(first -> 3, second -> 2))
    val registry = UniquePointRegistry3.compile(samples, stencil)

    assertEquals(registry.patchEntryCount, 2)
    assertEquals(registry.diagnostics.distinctPatchSampleReferences, 54)
    assertEquals(registry.diagnostics.drawPatchOccurrences, 5)
    assertEquals(registry.diagnostics.drawSampleReferences, 135L)
    assertEquals(registry.uniquePointCount, 36)
    assertEquals(registry.diagnostics.deduplicatedReferences, 18)
    assertEquals(
      registry.diagnostics.retainedPrimitiveBytes,
      36L * 3L * 8L + 54L * 4L + 36L * 4L + 36L * 4L
    )
    assert(registry.diagnostics.setupNanoseconds >= 0L)

    val patchValues = Array.tabulate(27)(index => index.toDouble + 1.0)
    val scattered = new Array[Double](registry.uniquePointCount)
    unique(
      registry.scatterAdd(
        patchValues,
        patchEntry = 0,
        scattered,
        includeDrawMultiplicity = true
      )
    )
    unique(
      registry.scatterAdd(
        patchValues,
        patchEntry = 1,
        scattered,
        includeDrawMultiplicity = true
      )
    )
    assertEqualsDouble(
      scattered.sum,
      patchValues.sum * 5.0,
      1e-12
    )

  test("does not coalesce merely nearby points"):
    val stencil = PhysicalStencil3.create(1.0)
    val exact = sampleSet(
      Vector(
        patch(0, 5.0, 5.0, 5.0) -> 1,
        patch(1, 5.0, 5.0, 5.0) -> 1
      )
    )
    val nearby = sampleSet(
      Vector(
        patch(0, 5.0, 5.0, 5.0) -> 1,
        patch(1, 5.0 + 1e-10, 5.0, 5.0) -> 1
      )
    )
    assertEquals(UniquePointRegistry3.compile(exact, stencil).uniquePointCount, 27)
    assertEquals(UniquePointRegistry3.compile(nearby, stencil).uniquePointCount, 54)

  test("shared-offset traversal matches independent affine patch sampling"):
    val stencil = PhysicalStencil3.create(1.0)
    val first = patch(0, 5.0, 5.0, 5.0)
    val second = patch(1, 6.0, 5.0, 5.0)
    val samples = sampleSet(Vector(first -> 4, second -> 3))
    val registry = UniquePointRegistry3.compile(samples, stencil)
    val traversal = CompiledAffinePatchTraversal3.compile(registry)
    assertEquals(
      traversal.mode,
      AffinePatchTraversalMode.TransformPatchCentersAndSharedOffsets
    )
    val workspace = traversal.newWorkspace()
    val affine = geometry(
      Affine.fromRowMajor[D3](
        Vector(
          1.0, 0.08, 0.02, 0.3,
          -0.03, 1.0, 0.04, 0.2,
          0.01, -0.02, 1.0, 0.4,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    traversal.transform(affine, workspace) match
      case Right(diagnostics) =>
        assertEquals(diagnostics.uniqueInterpolations, 36)
        assertEquals(diagnostics.affinePointTransforms, 2)
        assertEquals(diagnostics.affineLinearOffsetTransforms, 27)
      case Left(error) => fail(error.message)

    val fixture = new FixedSamplerFixture
    val buffer = sparseBuffer(registry.uniquePointCount)
    val counters = sampled(traversal.sampleFixed(fixture.sampler, workspace, buffer))
    assertEquals(counters, SparseSamplingCounters(36L, 36L, 0L, 288L))

    val gathered = new Array[Double](27)
    val gatheredX = new Array[Double](27)
    val gatheredY = new Array[Double](27)
    val gatheredZ = new Array[Double](27)
    val directBuffer = sparseBuffer(1)
    var entryIndex = 0
    while entryIndex < samples.entries.size do
      unique(registry.gather(buffer.values, entryIndex, gathered))
      unique(registry.gather(buffer.gradientX, entryIndex, gatheredX))
      unique(registry.gather(buffer.gradientY, entryIndex, gatheredY))
      unique(registry.gather(buffer.gradientZ, entryIndex, gatheredZ))
      val direct = new Array[Double](27)
      val patchEntry = samples.entries(entryIndex).patch
      var sampleIndex = 0
      while sampleIndex < 27 do
        val movingX = patchEntry.centerX + stencil.offsetX(sampleIndex)
        val movingY = patchEntry.centerY + stencil.offsetY(sampleIndex)
        val movingZ = patchEntry.centerZ + stencil.offsetZ(sampleIndex)
        val matrix = affine.rowMajor
        val fixedX =
          matrix(0) * movingX + matrix(1) * movingY +
            matrix(2) * movingZ + matrix(3)
        val fixedY =
          matrix(4) * movingX + matrix(5) * movingY +
            matrix(6) * movingZ + matrix(7)
        val fixedZ =
          matrix(8) * movingX + matrix(9) * movingY +
            matrix(10) * movingZ + matrix(11)
        directBuffer.resetCounters()
        resampling(
          fixture.sampler.sampleFullSupportWorld(
            Array(fixedX),
            Array(fixedY),
            Array(fixedZ),
            1,
            directBuffer
          )
        )
        assert(directBuffer.fullSupport(0))
        direct(sampleIndex) = directBuffer.values(0)
        assertEqualsDouble(gathered(sampleIndex), directBuffer.values(0), 2e-14)
        assertEqualsDouble(gatheredX(sampleIndex), directBuffer.gradientX(0), 2e-14)
        assertEqualsDouble(gatheredY(sampleIndex), directBuffer.gradientY(0), 2e-14)
        assertEqualsDouble(gatheredZ(sampleIndex), directBuffer.gradientZ(0), 2e-14)
        sampleIndex += 1
      val gatheredLoss = objective(
        PatchObjective.evaluate(
          patchEntry.moving,
          gathered,
          completeInterpolationSupport = true,
          objectiveConfig
        )
      )
      val directLoss = objective(
        PatchObjective.evaluate(
          patchEntry.moving,
          direct,
          completeInterpolationSupport = true,
          objectiveConfig
        )
      )
      assertEqualsDouble(gatheredLoss.loss, directLoss.loss, 2e-14)
      entryIndex += 1

  test("chooses direct unique transforms when shared-offset arithmetic is dearer"):
    val stencil = PhysicalStencil3.create(1.0)
    val registry = UniquePointRegistry3.compile(
      sampleSet(Vector(patch(0, 5.0, 5.0, 5.0) -> 1)),
      stencil
    )
    val traversal = CompiledAffinePatchTraversal3.compile(registry)
    assertEquals(
      traversal.mode,
      AffinePatchTraversalMode.TransformUniquePoints
    )
    assertEquals(traversal.diagnostics.affinePointTransforms, 27)
    assertEquals(traversal.diagnostics.affineLinearOffsetTransforms, 0)

    val workspace = traversal.newWorkspace()
    traversal.transform(Affine.identity[D3], workspace) match
      case Left(error) => fail(error.message)
      case Right(_)    => ()
    val fixture = new FixedSamplerFixture
    val buffer = sparseBuffer(registry.uniquePointCount)
    val counters = sampled(traversal.sampleFixed(fixture.sampler, workspace, buffer))
    assertEquals(counters.requestedSamples, 27L)
    val gathered = new Array[Double](27)
    val gatheredX = new Array[Double](27)
    unique(registry.gather(buffer.values, 0, gathered))
    unique(registry.gather(buffer.gradientX, 0, gatheredX))
    val direct = new Array[Double](27)
    val directBuffer = sparseBuffer(1)
    var sampleIndex = 0
    while sampleIndex < 27 do
      val x = registry.samples.entries(0).patch.centerX + stencil.offsetX(sampleIndex)
      val y = registry.samples.entries(0).patch.centerY + stencil.offsetY(sampleIndex)
      val z = registry.samples.entries(0).patch.centerZ + stencil.offsetZ(sampleIndex)
      directBuffer.resetCounters()
      resampling(
        fixture.sampler.sampleFullSupportWorld(
          Array(x),
          Array(y),
          Array(z),
          1,
          directBuffer
        )
      )
      direct(sampleIndex) = directBuffer.values(0)
      assertEqualsDouble(gathered(sampleIndex), directBuffer.values(0), 0.0)
      assertEqualsDouble(gatheredX(sampleIndex), directBuffer.gradientX(0), 0.0)
      sampleIndex += 1
    val candidate = registry.samples.entries(0).patch
    val gatheredLoss = objective(
      PatchObjective.evaluate(candidate.moving, gathered, true, objectiveConfig)
    )
    val directLoss = objective(
      PatchObjective.evaluate(candidate.moving, direct, true, objectiveConfig)
    )
    assertEqualsDouble(gatheredLoss.loss, directLoss.loss, 0.0)

    val otherRegistry = UniquePointRegistry3.compile(
      sampleSet(Vector(patch(2, 8.0, 8.0, 8.0) -> 1)),
      stencil
    )
    val other = CompiledAffinePatchTraversal3.compile(otherRegistry)
    val wrongWorkspace = other.newWorkspace()
    assertEquals(
      traversal.transform(Affine.identity[D3], wrongWorkspace),
      Left(AffinePatchTraversalError.WorkspacePlanMismatch)
    )

  private def patch(
      id: Int,
      x: Double,
      y: Double,
      z: Double
  ): PopulationPatch3 =
    val moving =
      PatchObjective
        .prepareMoving(
          Array.tabulate(27)(index =>
            math.sin(0.2 * index + 0.1 * id) + 0.03 * index
          ),
          objectiveConfig
        )
        .fold(error => fail(error.message), identity)
    new PopulationPatch3(
      id,
      id,
      x,
      y,
      z,
      WorldCell3(math.floor(x / 12.0).toLong, 0L, 0L),
      moving,
      qualityWeight = id.toDouble + 1.0,
      minimumStencilSupport = 1.0
    )

  private def sampleSet(
      patches: Vector[(PopulationPatch3, Int)]
  ): PatchSampleSet3 =
    val total = patches.map(_._2).sum
    val entries = patches.map { case (candidate, multiplicity) =>
      new WeightedPatchDraw3(
        candidate,
        multiplicity,
        drawProbability = 1.0 / patches.size.toDouble,
        objectiveWeight = multiplicity.toDouble / total.toDouble
      )
    }
    new PatchSampleSet3(
      PatchSampleSetId(
        populationFingerprint = 1L,
        samplingFingerprint = 2L,
        role = PatchSampleRole.Optimization,
        refreshOrdinal = 0,
        drawSeed = 3L
      ),
      entries,
      PatchSampleSetDiagnostics(
        PatchSampleRole.Optimization,
        patches.size,
        total,
        patches.size,
        total - patches.size,
        1.0 / patches.size.toDouble,
        1.0 / patches.size.toDouble,
        uniformProbabilityMixture = 0.25
      )
    )

  private final class FixedSamplerFixture:
    val frame = geometry(Frame.named[D3]("unique-fixed"))
    private val grid = geometry(
      Grid.forFrame(frame)(Vector(20, 20, 20), Affine.identity[D3])
        )
    private val data = NDArray.tabulate[Double](20, 20, 20) { (i, j, k) =>
      math.sin(i * 0.19) + math.cos(j * 0.17) + 0.05 * k * k
    }
    val image =
      Sampled
        .continuous(grid, NonSpatialAxes.empty, data)
        .fold(error => fail(error.message), identity)
    val sampler = resampling(LinearValueGradientSampler3.compile(image))

  private def objectiveConfig: PatchObjectiveConfig =
    PatchObjectiveConfig
      .create(
        positivePolarityPrior = 0.9,
        tau = 0.55,
        outlierFloor = 0.02,
        minimumContrastEnergy = 1e-12
      )
      .fold(error => fail(error.message), identity)

  private def objective(
      value: Either[PatchObjectiveError, PatchObjectiveValue]
  ): PatchObjectiveValue =
    value.fold(error => fail(error.message), identity)

  private def sparseBuffer(capacity: Int): SparseValueGradientBuffer3 =
    resampling(SparseValueGradientBuffer3.create(capacity))

  private def unique(
      value: Either[UniquePointRegistryError, Unit]
  ): Unit =
    value.fold(error => fail(error.message), identity)

  private def sampled(
      value: Either[AffinePatchSamplingError, SparseSamplingCounters]
  ): SparseSamplingCounters =
    value.fold(error => fail(error.message), identity)

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def resampling[A](value: Either[ResamplingError, A]): A =
    value.fold(error => fail(error.message), identity)
