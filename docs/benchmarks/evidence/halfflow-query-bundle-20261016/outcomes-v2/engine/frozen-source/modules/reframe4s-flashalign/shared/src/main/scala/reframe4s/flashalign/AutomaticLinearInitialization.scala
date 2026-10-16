package reframe4s.flashalign

import image4s.ContinuousImage
import image4s.SampleSpace
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3
import reframe4s.multiscale.GridTower
import reframe4s.multiscale.IsotropicGaussianPsf3
import reframe4s.multiscale.ScaleLevel
import reframe4s.multiscale.ScaleSchedule
import reframe4s.multiscale.ScaleSpec
import reframe4s.multiscale.SupportAwarePyramid3
import reframe4s.multiscale.SupportAwarePyramidConfig3
import reframe4s.multiscale.SupportAwarePyramidLevel3
import reframe4s.multiscale.SupportAwarePyramidWorkspace3
import reframe4s.resample.LinearValueGradientSampler3
import reframe4s.resample.ScalarValueGradient3
import reframe4s.spectral.SpectralShape3

private[flashalign] final class LinearPlanWorkspace(
    val engine: AnyRef,
    val capture: Option[StructuralCaptureWorkspace3]
)

private[flashalign] final case class AutomaticCaptureCandidates3(
    candidates: Vector[StructuralCaptureCandidate3],
    diagnostics: StructuralCaptureDiagnostics3,
    elapsedNanoseconds: Long
)

private[flashalign] final class AutomaticLinearInitialization3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val moving: FlashalignImage[Moving],
    val fixed: FlashalignImage[Fixed],
    val policy: LinearPresetPolicy,
    val preparationElapsedNanoseconds: Long,
    private val plan: StructuralCapturePlan3
):
  def newWorkspace(): StructuralCaptureWorkspace3 = plan.newWorkspace()

  def capture(
      workspace: StructuralCaptureWorkspace3
  ): Either[FlashalignError, AutomaticCaptureCandidates3] =
    val started = System.nanoTime()
    plan
      .capture(workspace)
      .left
      .map(error => FlashalignError.AutomaticInitialization(error.message))
      .flatMap {
        case StructuralCaptureOutcome3.Captured(candidates, diagnostics) =>
          Right(
            AutomaticCaptureCandidates3(
              candidates,
              diagnostics,
              System.nanoTime() - started
            )
          )
        case StructuralCaptureOutcome3.InsufficientOverlap(diagnostics) =>
          val elapsed = System.nanoTime() - started
          Left(
            FlashalignError.CaptureRejected(
              FlashalignCaptureFailure.InsufficientOverlap,
              diagnostics.evaluatedRotations,
              diagnostics.scheduledRotations,
              None,
              rejectedDiagnostics(diagnostics, elapsed)
            )
          )
        case StructuralCaptureOutcome3.InsufficientInformation(diagnostics) =>
          val elapsed = System.nanoTime() - started
          Left(
            FlashalignError.CaptureRejected(
              FlashalignCaptureFailure.InsufficientInformation,
              diagnostics.evaluatedRotations,
              diagnostics.scheduledRotations,
              None,
              rejectedDiagnostics(diagnostics, elapsed)
            )
          )
        case StructuralCaptureOutcome3.ExhaustedSchedule(best, diagnostics) =>
          val elapsed = System.nanoTime() - started
          Left(
            FlashalignError.CaptureRejected(
              FlashalignCaptureFailure.ExhaustedSchedule,
              diagnostics.evaluatedRotations,
              diagnostics.scheduledRotations,
              best,
              rejectedDiagnostics(diagnostics, elapsed)
            )
          )
      }

  private def rejectedDiagnostics(
      diagnostics: StructuralCaptureDiagnostics3,
      elapsedNanoseconds: Long
  ): FlashalignFailureDiagnostics =
    FlashalignFailureDiagnostics(
      FlashalignWorkCounts.Zero,
      Vector.empty,
      Some(
        FlashalignCaptureDiagnostics(
          diagnostics.scheduleVersion,
          diagnostics.seed,
          diagnostics.scheduledRotations,
          diagnostics.evaluatedRotations,
          Vector.empty,
          selectedCandidateIndex = -1,
          competingAlignments = false,
          preparationElapsedNanoseconds = preparationElapsedNanoseconds,
          captureElapsedNanoseconds = elapsedNanoseconds,
          refinementAndSelectionElapsedNanoseconds = 0L,
          finalAuditElapsedNanoseconds = 0L,
          identityOverlapFraction = diagnostics.identityOverlapFraction,
          identityStructuralScore = diagnostics.identityStructuralScore,
          identityAdequate = diagnostics.identityAdequate,
          expandedCaptureExecuted = diagnostics.expandedCaptureExecuted
        )
      ),
      None
    )

  def rigid(
      candidate: StructuralCaptureCandidate3
  ): Either[FlashalignError, Rigid3[Moving, Fixed]] =
    Rigid3
      .fromRowMajor(moving.frame, fixed.frame, candidateMatrix(candidate))
      .left
      .map(error => FlashalignError.AutomaticInitialization(error.message))

  def affine(
      candidate: StructuralCaptureCandidate3
  ): Either[FlashalignError, FramedAffine[Moving, Fixed, D3]] =
    Affine
      .fromRowMajor[D3](candidateMatrix(candidate))
      .left
      .map(error => FlashalignError.Geometry(error))
      .map(operator => FramedAffine.betweenFrames(moving.frame, fixed.frame)(operator))

  private def candidateMatrix(
      candidate: StructuralCaptureCandidate3
  ): Vector[Double] =
    val rotation = candidate.rotation.rowMajor
    val pivotX = plan.pivotXMillimetres
    val pivotY = plan.pivotYMillimetres
    val pivotZ = plan.pivotZMillimetres
    val offsetX =
      pivotX - rotation(0) * pivotX - rotation(1) * pivotY -
        rotation(2) * pivotZ + candidate.translationXMillimetres
    val offsetY =
      pivotY - rotation(3) * pivotX - rotation(4) * pivotY -
        rotation(5) * pivotZ + candidate.translationYMillimetres
    val offsetZ =
      pivotZ - rotation(6) * pivotX - rotation(7) * pivotY -
        rotation(8) * pivotZ + candidate.translationZMillimetres
    Vector(
      rotation(0), rotation(1), rotation(2), offsetX,
      rotation(3), rotation(4), rotation(5), offsetY,
      rotation(6), rotation(7), rotation(8), offsetZ,
      0.0, 0.0, 0.0, 1.0
    )

private[flashalign] object AutomaticLinearInitialization3:
  private final case class WorldBounds3(
      minimumX: Double,
      maximumX: Double,
      minimumY: Double,
      maximumY: Double,
      minimumZ: Double,
      maximumZ: Double
  )

  private final case class SampledCaptureArray3(
      values: Array[Double],
      fullSupport: Array[Boolean]
  )

  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      policy: LinearPresetPolicy
  )(using Dimension[D3]): Either[
    FlashalignError,
    AutomaticLinearInitialization3[Moving, Fixed]
  ] =
    val started = System.nanoTime()
    val spacing = policy.preparation.effectiveResolutionMillimetres.head
    for
      movingBounds <- worldBounds(moving)
      fixedBounds <- worldBounds(fixed)
      captureLattice <- lattice(union(movingBounds, fixedBounds), spacing)
      movingPrepared <- prepareCaptureImage(moving, spacing)
      fixedPrepared <- prepareCaptureImage(fixed, spacing)
      compiled <- compilePrepared(
        moving,
        fixed,
        policy,
        movingBounds,
        captureLattice,
        movingPrepared,
        fixedPrepared,
        started
      )
    yield compiled

  def compileFromPreparedPyramids[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      policy: LinearPresetPolicy,
      prepared: PreparedLinearPyramids3[Moving, Fixed]
  ): Either[
    FlashalignError,
    AutomaticLinearInitialization3[Moving, Fixed]
  ] =
    val started = System.nanoTime()
    val spacing = policy.preparation.effectiveResolutionMillimetres.head
    for
      movingBounds <- worldBounds(moving)
      fixedBounds <- worldBounds(fixed)
      captureLattice <- lattice(union(movingBounds, fixedBounds), spacing)
      movingPrepared <- prepared.moving.levels.headOption.toRight(
        FlashalignError.AutomaticInitialization(
          "prepared moving pyramid contains no capture level"
        )
      )
      fixedPrepared <- prepared.fixed.levels.headOption.toRight(
        FlashalignError.AutomaticInitialization(
          "prepared fixed pyramid contains no capture level"
        )
      )
      compiled <- compilePrepared(
        moving,
        fixed,
        policy,
        movingBounds,
        captureLattice,
        movingPrepared,
        fixedPrepared,
        started
      )
    yield compiled

  private def compilePrepared[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: FlashalignImage[Moving],
      fixed: FlashalignImage[Fixed],
      policy: LinearPresetPolicy,
      movingBounds: WorldBounds3,
      captureLattice: CaptureLattice3,
      movingPrepared: SupportAwarePyramidLevel3[Moving, String],
      fixedPrepared: SupportAwarePyramidLevel3[Fixed, String],
      started: Long
  ): Either[
    FlashalignError,
    AutomaticLinearInitialization3[Moving, Fixed]
  ] =
    for
      movingVolume <- scalarVolume(movingPrepared, captureLattice)
      fixedVolume <- scalarVolume(fixedPrepared, captureLattice)
      movingTensor <- NormalizedGradientTensor3
        .fromScalar(movingVolume, policy.capture.minimumStructuralEnergy)
        .left
        .map(error => FlashalignError.AutomaticInitialization(error.message))
      fixedTensor <- NormalizedGradientTensor3
        .fromScalar(fixedVolume, policy.capture.minimumStructuralEnergy)
        .left
        .map(error => FlashalignError.AutomaticInitialization(error.message))
      captureConfig <- captureConfig(policy.capture)
      pivot = Vector(
        0.5 * (movingBounds.minimumX + movingBounds.maximumX),
        0.5 * (movingBounds.minimumY + movingBounds.maximumY),
        0.5 * (movingBounds.minimumZ + movingBounds.maximumZ)
      )
      capturePlan <- StructuralCapturePlan3
        .create(
          movingTensor,
          fixedTensor,
          captureConfig,
          pivot(0),
          pivot(1),
          pivot(2)
        )
        .left
        .map(error => FlashalignError.AutomaticInitialization(error.message))
    yield new AutomaticLinearInitialization3(
      moving,
      fixed,
      policy,
      System.nanoTime() - started,
      capturePlan
    )

  private def union(left: WorldBounds3, right: WorldBounds3): WorldBounds3 =
    WorldBounds3(
      math.min(left.minimumX, right.minimumX),
      math.max(left.maximumX, right.maximumX),
      math.min(left.minimumY, right.minimumY),
      math.max(left.maximumY, right.maximumY),
      math.min(left.minimumZ, right.minimumZ),
      math.max(left.maximumZ, right.maximumZ)
    )

  private def captureConfig(
      policy: PresetCapturePolicy
  ): Either[FlashalignError, StructuralCaptureConfig3] =
    StructuralCaptureConfig3
      .create(
        policy.stages.map(stage =>
          CaptureRotationStage3(stage.maximumAngleDegrees, stage.stepDegrees)
        ),
        seed = policy.seed,
        maximumRotations = policy.maximumRotations,
        maximumRetainedCandidates = policy.maximumRetainedCandidates,
        peaksPerRotation = policy.peaksPerRotation,
        minimumCandidatesToStop = policy.minimumCandidatesToStop,
        minimumOverlapFraction = policy.minimumOverlapFraction,
        minimumStructuralEnergy = policy.minimumStructuralEnergy,
        minimumStructuralScore = policy.minimumStructuralScore,
        minimumPeakSeparationMillimetres = policy.minimumPeakSeparationMillimetres,
        minimumCandidateDisplacementMillimetres =
          policy.minimumCandidateDisplacementMillimetres
      )
      .left
      .map(error => FlashalignError.AutomaticInitialization(error.message))

  private def worldBounds[F <: Frame[D3]](
      image: FlashalignImage[F]
  ): Either[FlashalignError, WorldBounds3] =
    val shape = image.grid.shape
    val affine = image.grid.indexToFrame.rowMajor
    var minimumX = Double.PositiveInfinity
    var maximumX = Double.NegativeInfinity
    var minimumY = Double.PositiveInfinity
    var maximumY = Double.NegativeInfinity
    var minimumZ = Double.PositiveInfinity
    var maximumZ = Double.NegativeInfinity
    var ix = 0
    while ix <= 1 do
      val x = if ix == 0 then 0.0 else shape(0).toDouble - 1.0
      var iy = 0
      while iy <= 1 do
        val y = if iy == 0 then 0.0 else shape(1).toDouble - 1.0
        var iz = 0
        while iz <= 1 do
          val z = if iz == 0 then 0.0 else shape(2).toDouble - 1.0
          val worldX = affine(0) * x + affine(1) * y + affine(2) * z + affine(3)
          val worldY = affine(4) * x + affine(5) * y + affine(6) * z + affine(7)
          val worldZ = affine(8) * x + affine(9) * y + affine(10) * z + affine(11)
          if !worldX.isFinite || !worldY.isFinite || !worldZ.isFinite then
            return Left(
              FlashalignError.AutomaticInitialization(
                "image grid produced a nonfinite capture bound"
              )
            )
          minimumX = math.min(minimumX, worldX)
          maximumX = math.max(maximumX, worldX)
          minimumY = math.min(minimumY, worldY)
          maximumY = math.max(maximumY, worldY)
          minimumZ = math.min(minimumZ, worldZ)
          maximumZ = math.max(maximumZ, worldZ)
          iz += 1
        iy += 1
      ix += 1
    Right(
      WorldBounds3(
        minimumX,
        maximumX,
        minimumY,
        maximumY,
        minimumZ,
        maximumZ
      )
    )

  private def lattice(
      bounds: WorldBounds3,
      spacing: Double
  ): Either[FlashalignError, CaptureLattice3] =
    def extent(minimum: Double, maximum: Double): Int =
      math.max(3, math.ceil((maximum - minimum) / spacing).toInt + 1)
    CaptureLattice3
      .create(
        SpectralShape3(
          extent(bounds.minimumX, bounds.maximumX),
          extent(bounds.minimumY, bounds.maximumY),
          extent(bounds.minimumZ, bounds.maximumZ)
        ),
        bounds.minimumX,
        bounds.minimumY,
        bounds.minimumZ,
        spacing,
        spacing,
        spacing
      )
      .left
      .map(error => FlashalignError.AutomaticInitialization(error.message))

  private def prepareCaptureImage[
      F <: Frame[D3]
  ](
      image: FlashalignImage[F],
      requestedSpacingMillimetres: Double
  )(using Dimension[D3]): Either[
    FlashalignError,
    SupportAwarePyramidLevel3[F, String]
  ] =
    val nativeShape = image.grid.shape
    val nativeAffine = image.grid.indexToFrame.rowMajor
    val shrink = Vector.tabulate(3) { axis =>
      val columnNorm = math.sqrt(
        nativeAffine(axis) * nativeAffine(axis) +
          nativeAffine(4 + axis) * nativeAffine(4 + axis) +
          nativeAffine(8 + axis) * nativeAffine(8 + axis)
      )
      math.max(1, math.round(requestedSpacingMillimetres / columnNorm).toInt)
    }
    val nativeSupport = NDArray.tabulate[Double](
      nativeShape(0),
      nativeShape(1),
      nativeShape(2)
    )((i, j, k) => if image.data(i, j, k).isFinite then 1.0 else 0.0)
    for
      coarse <- ScaleSpec
        .create[D3](shrink, Vector.fill(3)(0.0))
        .left
        .map(error => FlashalignError.AutomaticInitialization(error.message))
      native <- ScaleSpec
        .create[D3](Vector.fill(3)(1), Vector.fill(3)(0.0))
        .left
        .map(error => FlashalignError.AutomaticInitialization(error.message))
      schedule <- ScaleSchedule
        .create(
          Vector(
            ScaleLevel(coarse, "capture"),
            ScaleLevel(native, "native")
          )
        )
        .left
        .map(error => FlashalignError.AutomaticInitialization(error.message))
      tower <- GridTower
        .build(image.grid, schedule)
        .left
        .map(error => FlashalignError.AutomaticInitialization(error.message))
      sourcePsf = IsotropicGaussianPsf3.voxelCellApproximation(image.grid)
      targetPsfs = tower.levels.map(level =>
        IsotropicGaussianPsf3.voxelCellApproximation(level.grid)
      )
      pyramidConfig <- SupportAwarePyramidConfig3
        .create(sourcePsf, targetPsfs)
        .left
        .map(error => FlashalignError.AutomaticInitialization(error.message))
      pyramid <- SupportAwarePyramid3
        .build(
          image,
          nativeSupport,
          tower,
          pyramidConfig,
          SupportAwarePyramidWorkspace3.create
        )
        .left
        .map(error => FlashalignError.AutomaticInitialization(error.message))
    yield pyramid.levels.head

  private def scalarVolume[
      F <: Frame[D3]
  ](
      level: SupportAwarePyramidLevel3[F, String],
      lattice: CaptureLattice3
  )(using Dimension[D3]): Either[FlashalignError, CaptureScalarVolume3] =
    for
      imageSamples <- sampleArray(level.image, lattice)
      supportSamples <- sampleArray(level.support, lattice)
      support = Array.tabulate(lattice.elementCount) { index =>
        if imageSamples.fullSupport(index) && supportSamples.fullSupport(index) then
          math.max(0.0, math.min(1.0, supportSamples.values(index)))
        else 0.0
      }
      volume <- CaptureScalarVolume3
        .create(lattice, imageSamples.values, support)
        .left
        .map(error => FlashalignError.AutomaticInitialization(error.message))
    yield volume

  private def sampleArray[
      F <: Frame[D3],
      S <: SampleSpace[F, D3]
  ](
      image: ContinuousImage[S, Double, Rank[3]],
      lattice: CaptureLattice3
  )(using Dimension[D3]): Either[FlashalignError, SampledCaptureArray3] =
    val values = new Array[Double](lattice.elementCount)
    val fullSupport = new Array[Boolean](lattice.elementCount)
    val sampled = ScalarValueGradient3.create
    val frameToIndex = image.grid.indexToFrame.inverse.rowMajor
    LinearValueGradientSampler3
      .compile(image)
      .left
      .map(error => FlashalignError.AutomaticInitialization(error.message))
      .map { sampler =>
        var x = 0
        while x < lattice.shape.x do
          var y = 0
          while y < lattice.shape.y do
            var z = 0
            while z < lattice.shape.z do
              val worldX = lattice.worldX(x)
              val worldY = lattice.worldY(y)
              val worldZ = lattice.worldZ(z)
              val indexX =
                frameToIndex(0) * worldX + frameToIndex(1) * worldY +
                  frameToIndex(2) * worldZ + frameToIndex(3)
              val indexY =
                frameToIndex(4) * worldX + frameToIndex(5) * worldY +
                  frameToIndex(6) * worldZ + frameToIndex(7)
              val indexZ =
                frameToIndex(8) * worldX + frameToIndex(9) * worldY +
                  frameToIndex(10) * worldZ + frameToIndex(11)
              val index = lattice.index(x, y, z)
              if
                sampler.sampleFullSupportContinuousIndex(
                  indexX,
                  indexY,
                  indexZ,
                  sampled
                ) && sampled.value.isFinite
              then
                values(index) = sampled.value
                fullSupport(index) = true
              z += 1
            y += 1
          x += 1
        SampledCaptureArray3(values, fullSupport)
      }
