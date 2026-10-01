package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame

private[flashalign] final case class CachedProjectedPatch3(
    sampleIndices: Vector[Int],
    movingUnit: Vector[Double],
    fixedUnit: Vector[Double],
    fixedContrastNorm: Double,
    correlation: Double,
    objectiveWeight: Double,
    inlierWeight: Double,
    signedWeight: Double,
    invalidReason: Option[PatchInvalidReason]
)

private[flashalign] final case class MatrixFreeCacheDiagnostics(
    uniquePoints: Int,
    patches: Int,
    validPatches: Int,
    interpolationCallsAtLinearization: Long,
    gradientCallsAtLinearization: Long,
    interpolationCallsPerProduct: Long,
    gradientCallsPerProduct: Long,
    retainedEvidenceBytes: Long,
    basisTableBytes: Long,
    productionDenseNormalBytes: Long
)

private[flashalign] final class MatrixFreePatchWorkspace3[
    Fixed <: Frame[D3]
] private[flashalign] (
    private val owner: AnyRef,
    val geometryWorkspace: GeometryOperatorWorkspace3,
    val displacement: GeometryOutputBuffer3[Fixed],
    private[flashalign] val intensityDirection: Array[Double],
    private[flashalign] val scalarForces: Array[Double],
    private[flashalign] val spatialForceValues: Array[Double],
    private[flashalign] val spatialForces: WorldVectorBatch3[Fixed],
    private[flashalign] val patchScratch: Array[Double]
):
  private var active = false
  private var products = 0L

  private[flashalign] def acquire(candidate: AnyRef): Either[MatrixFreePatchError, Unit] =
    this.synchronized {
      if !(owner eq candidate) then Left(MatrixFreePatchError.WorkspaceCacheMismatch)
      else if active then Left(MatrixFreePatchError.WorkspaceInUse)
      else
        active = true
        Right(())
    }

  private[flashalign] def release(candidate: AnyRef): Unit =
    this.synchronized {
      if owner eq candidate then active = false
    }

  private[flashalign] def recordProduct(): Unit = products += 1L
  def curvatureProducts: Long = products

private[flashalign] final class MatrixFreePatchLinearization3[
    State,
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val stateIdentity: Long,
    val sampleSetId: PatchSampleSetId,
    val geometry: GeometryPointOperator3[State, Moving, Fixed],
    val state: State,
    val uniquePoints: WorldPointBatch3[Moving],
    private[flashalign] val imageValues: Array[Double],
    private val imageGradientsWorld: Array[Double],
    val patches: Vector[CachedProjectedPatch3],
    val diagnostics: MatrixFreeCacheDiagnostics,
    private val maximumPatchSize: Int
):
  def newWorkspace(): MatrixFreePatchWorkspace3[Fixed] =
    val unique = uniquePoints.size
    val forceValues = new Array[Double](unique * 3)
    new MatrixFreePatchWorkspace3(
      this,
      geometry.newWorkspace(),
      GeometryOutputBuffer3.allocate(geometry.fixed, unique).fold(
        error => throw new IllegalStateException(error.message),
        identity
      ),
      new Array[Double](unique),
      new Array[Double](unique),
      forceValues,
      WorldVectorBatch3.wrapOwned(geometry.fixed, forceValues),
      new Array[Double](maximumPatchSize)
    )

  def requireIdentity(
      expectedStateIdentity: Long,
      expectedSampleSetId: PatchSampleSetId
  ): Either[MatrixFreePatchError, Unit] =
    if stateIdentity != expectedStateIdentity then
      Left(MatrixFreePatchError.StateIdentityMismatch(stateIdentity, expectedStateIdentity))
    else if sampleSetId != expectedSampleSetId then
      Left(MatrixFreePatchError.SampleSetIdentityMismatch(sampleSetId, expectedSampleSetId))
    else Right(())

  def curvatureProduct(
      direction: GeometryDirection3,
      output: Array[Double],
      workspace: MatrixFreePatchWorkspace3[Fixed]
  ): Either[MatrixFreePatchError, Unit] =
    withWorkspace(workspace) {
      if output.length != geometry.parameterCount then
        Left(MatrixFreePatchError.ParameterCountMismatch(geometry.parameterCount, output.length))
      else
        geometry
          .jvp(state, uniquePoints, direction, workspace.displacement, workspace.geometryWorkspace)
          .left
          .map(MatrixFreePatchError.Geometry.apply)
          .flatMap { _ =>
            projectImageDirection(workspace)
            scatterCurvature(workspace)
            geometry
              .vjp(
                state,
                uniquePoints,
                workspace.spatialForces,
                output,
                workspace.geometryWorkspace
              )
              .left
              .map(MatrixFreePatchError.Geometry.apply)
              .map { _ => workspace.recordProduct() }
          }
    }

  def rightHandSide(
      output: Array[Double],
      workspace: MatrixFreePatchWorkspace3[Fixed]
  ): Either[MatrixFreePatchError, Unit] =
    withWorkspace(workspace) {
      if output.length != geometry.parameterCount then
        Left(MatrixFreePatchError.ParameterCountMismatch(geometry.parameterCount, output.length))
      else
        java.util.Arrays.fill(workspace.scalarForces, 0.0)
        patches.foreach { patch =>
          if patch.invalidReason.isEmpty then
            val scale = patch.objectiveWeight * patch.signedWeight / patch.fixedContrastNorm
            var sample = 0
            while sample < patch.sampleIndices.length do
              val force = scale * (
                patch.movingUnit(sample) - patch.correlation * patch.fixedUnit(sample)
              )
              workspace.scalarForces(patch.sampleIndices(sample)) += force
              sample += 1
        }
        expandSpatialForces(workspace)
        geometry
          .vjp(
            state,
            uniquePoints,
            workspace.spatialForces,
            output,
            workspace.geometryWorkspace
          )
          .left
          .map(MatrixFreePatchError.Geometry.apply)
    }

  private def projectImageDirection(workspace: MatrixFreePatchWorkspace3[Fixed]): Unit =
    var unique = 0
    while unique < uniquePoints.size do
      val offset = unique * 3
      workspace.intensityDirection(unique) =
        imageGradientsWorld(offset) * workspace.displacement.packed(offset) +
          imageGradientsWorld(offset + 1) * workspace.displacement.packed(offset + 1) +
          imageGradientsWorld(offset + 2) * workspace.displacement.packed(offset + 2)
      unique += 1

  private def scatterCurvature(workspace: MatrixFreePatchWorkspace3[Fixed]): Unit =
    java.util.Arrays.fill(workspace.scalarForces, 0.0)
    patches.foreach { patch =>
      if patch.invalidReason.isEmpty then
        val size = patch.sampleIndices.length
        var mean = 0.0
        var sample = 0
        while sample < size do
          val value = workspace.intensityDirection(patch.sampleIndices(sample))
          workspace.patchScratch(sample) = value
          mean += value
          sample += 1
        mean /= size.toDouble
        var projection = 0.0
        sample = 0
        while sample < size do
          workspace.patchScratch(sample) -= mean
          projection += patch.fixedUnit(sample) * workspace.patchScratch(sample)
          sample += 1
        val scale = patch.objectiveWeight * patch.inlierWeight /
          (patch.fixedContrastNorm * patch.fixedContrastNorm)
        sample = 0
        while sample < size do
          val projected = workspace.patchScratch(sample) - patch.fixedUnit(sample) * projection
          workspace.scalarForces(patch.sampleIndices(sample)) += scale * projected
          sample += 1
    }
    expandSpatialForces(workspace)

  private def expandSpatialForces(workspace: MatrixFreePatchWorkspace3[Fixed]): Unit =
    var unique = 0
    while unique < uniquePoints.size do
      val scalar = workspace.scalarForces(unique)
      val offset = unique * 3
      workspace.spatialForceValues(offset) = imageGradientsWorld(offset) * scalar
      workspace.spatialForceValues(offset + 1) = imageGradientsWorld(offset + 1) * scalar
      workspace.spatialForceValues(offset + 2) = imageGradientsWorld(offset + 2) * scalar
      unique += 1

  private def withWorkspace[A](workspace: MatrixFreePatchWorkspace3[Fixed])(
      operation: => Either[MatrixFreePatchError, A]
  ): Either[MatrixFreePatchError, A] =
    workspace.acquire(this).flatMap { _ =>
      try operation
      finally workspace.release(this)
    }

private[flashalign] object MatrixFreePatchLinearization3:
  def create[
      State,
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      stateIdentity: Long,
      sampleSetId: PatchSampleSetId,
      geometry: GeometryPointOperator3[State, Moving, Fixed],
      state: State,
      uniquePoints: WorldPointBatch3[Moving],
      imageValues: Array[Double],
      imageGradientsWorld: Array[Double],
      patches: Vector[CachedProjectedPatch3],
      basisTableBytes: Long = 0L
  ): Either[MatrixFreePatchError, MatrixFreePatchLinearization3[State, Moving, Fixed]] =
    if imageValues.length != uniquePoints.size then
      Left(MatrixFreePatchError.ValueCountMismatch(uniquePoints.size, imageValues.length))
    else if imageValues.exists(value => !value.isFinite) then Left(MatrixFreePatchError.NonFiniteValue)
    else if imageGradientsWorld.length != uniquePoints.size * 3 then
      Left(MatrixFreePatchError.GradientCountMismatch(uniquePoints.size * 3, imageGradientsWorld.length))
    else if imageGradientsWorld.exists(value => !value.isFinite) then
      Left(MatrixFreePatchError.NonFiniteGradient)
    else if basisTableBytes < 0L then Left(MatrixFreePatchError.InvalidBasisTableBytes(basisTableBytes))
    else
      patches.zipWithIndex.foldLeft[Either[MatrixFreePatchError, Unit]](Right(())) {
        case (acc, (patch, index)) => acc.flatMap(_ => validatePatch(index, patch, uniquePoints.size))
      }.map { _ =>
        val maximum = patches.map(_.sampleIndices.length).maxOption.getOrElse(0)
        val evidenceBytes =
          uniquePoints.size.toLong * 24L +
            imageValues.length.toLong * 8L +
            imageGradientsWorld.length.toLong * 8L +
            patches.map(patch =>
              patch.sampleIndices.length.toLong * 4L +
                patch.movingUnit.length.toLong * 8L +
                patch.fixedUnit.length.toLong * 8L +
                5L * 8L
            ).sum
        val diagnostics = MatrixFreeCacheDiagnostics(
          uniquePoints.size,
          patches.size,
          patches.count(_.invalidReason.isEmpty),
          uniquePoints.size.toLong,
          uniquePoints.size.toLong,
          0L,
          0L,
          evidenceBytes,
          basisTableBytes,
          0L
        )
        new MatrixFreePatchLinearization3(
          stateIdentity,
          sampleSetId,
          geometry,
          state,
          uniquePoints,
          imageValues.clone(),
          imageGradientsWorld.clone(),
          patches,
          diagnostics,
          maximum
        )
      }

  private def validatePatch(
      patchIndex: Int,
      patch: CachedProjectedPatch3,
      uniqueCount: Int
  ): Either[MatrixFreePatchError, Unit] =
    val size = patch.sampleIndices.length
    if patch.movingUnit.length != size || patch.fixedUnit.length != size then
      Left(MatrixFreePatchError.PatchSizeMismatch(patchIndex))
    else if patch.sampleIndices.exists(index => index < 0 || index >= uniqueCount) then
      Left(MatrixFreePatchError.InvalidUniqueIndex(patchIndex))
    else if patch.invalidReason.nonEmpty then Right(())
    else if size < PatchObjective.MinimumSamples then Left(MatrixFreePatchError.PatchTooSmall(patchIndex, size))
    else if !patch.fixedContrastNorm.isFinite || patch.fixedContrastNorm <= 0.0 then
      Left(MatrixFreePatchError.InvalidPatchScalar(patchIndex, "fixed contrast norm"))
    else if Vector(
        patch.correlation,
        patch.objectiveWeight,
        patch.inlierWeight,
        patch.signedWeight
      ).exists(value => !value.isFinite)
    then Left(MatrixFreePatchError.InvalidPatchScalar(patchIndex, "weight or correlation"))
    else if patch.objectiveWeight < 0.0 || patch.inlierWeight < 0.0 then
      Left(MatrixFreePatchError.InvalidPatchScalar(patchIndex, "negative objective/inlier weight"))
    else if (patch.movingUnit ++ patch.fixedUnit).exists(value => !value.isFinite) then
      Left(MatrixFreePatchError.InvalidPatchScalar(patchIndex, "non-finite normalized vector"))
    else if !centeredUnit(patch.movingUnit) || !centeredUnit(patch.fixedUnit) then
      Left(MatrixFreePatchError.InvalidNormalization(patchIndex))
    else Right(())

  private def centeredUnit(values: Vector[Double]): Boolean =
    math.abs(values.sum) <= 1e-9 &&
      math.abs(values.map(value => value * value).sum - 1.0) <= 1e-9

private[flashalign] sealed trait MatrixFreePatchError derives CanEqual:
  def message: String

private[flashalign] object MatrixFreePatchError:
  final case class Geometry(error: GeometryOperatorError) extends MatrixFreePatchError:
    val message = error.message
  final case class StateIdentityMismatch(actual: Long, expected: Long) extends MatrixFreePatchError:
    val message = s"linearization state identity $actual does not match $expected"
  final case class SampleSetIdentityMismatch(actual: PatchSampleSetId, expected: PatchSampleSetId)
      extends MatrixFreePatchError:
    val message = s"linearization sample set $actual does not match $expected"
  final case class ParameterCountMismatch(expected: Int, actual: Int) extends MatrixFreePatchError:
    val message = s"operator requires $expected parameters, got $actual"
  final case class GradientCountMismatch(expected: Int, actual: Int) extends MatrixFreePatchError:
    val message = s"linearization requires $expected world-gradient values, got $actual"
  final case class ValueCountMismatch(expected: Int, actual: Int) extends MatrixFreePatchError:
    val message = s"linearization requires $expected sampled values, got $actual"
  case object NonFiniteValue extends MatrixFreePatchError:
    val message = "linearization image values must be finite"
  case object NonFiniteGradient extends MatrixFreePatchError:
    val message = "linearization image gradients must be finite"
  final case class InvalidBasisTableBytes(actual: Long) extends MatrixFreePatchError:
    val message = s"basis table bytes must be nonnegative, got $actual"
  final case class PatchSizeMismatch(patch: Int) extends MatrixFreePatchError:
    val message = s"patch $patch normalized vectors do not match its sample count"
  final case class InvalidUniqueIndex(patch: Int) extends MatrixFreePatchError:
    val message = s"patch $patch references a unique point outside the cache"
  final case class PatchTooSmall(patch: Int, size: Int) extends MatrixFreePatchError:
    val message = s"patch $patch has $size samples"
  final case class InvalidPatchScalar(patch: Int, detail: String) extends MatrixFreePatchError:
    val message = s"patch $patch has invalid $detail"
  final case class InvalidNormalization(patch: Int) extends MatrixFreePatchError:
    val message = s"patch $patch moving/fixed vectors must be centred and unit length"
  case object WorkspaceCacheMismatch extends MatrixFreePatchError:
    val message = "matrix-free workspace belongs to a different linearization"
  case object WorkspaceInUse extends MatrixFreePatchError:
    val message = "matrix-free workspace is already in use"
