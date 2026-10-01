package reframe4s.flashalign

import reframe4s.spectral.LinearCorrelation3Plan
import reframe4s.spectral.LinearCorrelation3Workspace
import reframe4s.spectral.PreparedLinearCorrelation3Right
import reframe4s.spectral.SpectralError

import scala.collection.mutable

private[flashalign] final case class CaptureRotationStage3(
    maximumAngleDegrees: Double,
    stepDegrees: Double
)

private[flashalign] final class StructuralCaptureConfig3 private (
    val stages: Vector[CaptureRotationStage3],
    val seed: Long,
    val maximumRotations: Int,
    val maximumRetainedCandidates: Int,
    val peaksPerRotation: Int,
    val minimumCandidatesToStop: Int,
    val minimumOverlapFraction: Double,
    val minimumStructuralEnergy: Double,
    val minimumStructuralScore: Double,
    val minimumInterpolatedSupport: Double,
    val minimumPeakSeparationMillimetres: Double,
    val minimumCandidateDisplacementMillimetres: Double
)

private[flashalign] object StructuralCaptureConfig3:
  val ScheduleVersion: String = "flashalign-euler-xyz-splitmix64-v1"

  def create(
      stages: Vector[CaptureRotationStage3],
      seed: Long,
      maximumRotations: Int = 256,
      maximumRetainedCandidates: Int = 4,
      peaksPerRotation: Int = 2,
      minimumCandidatesToStop: Int = 2,
      minimumOverlapFraction: Double = 0.2,
      minimumStructuralEnergy: Double = 1e-10,
      minimumStructuralScore: Double = 0.1,
      minimumInterpolatedSupport: Double = 1e-6,
      minimumPeakSeparationMillimetres: Double = 8.0,
      minimumCandidateDisplacementMillimetres: Double = 8.0
  ): Either[StructuralCaptureError, StructuralCaptureConfig3] =
    if stages.isEmpty then invalid("rotation schedule must contain a stage")
    else if maximumRotations <= 0 || maximumRotations > 4096 then
      invalid(s"maximum rotations must be within [1, 4096], got $maximumRotations")
    else if maximumRetainedCandidates <= 0 then
      invalid(s"maximum retained candidates must be positive, got $maximumRetainedCandidates")
    else if peaksPerRotation <= 0 then
      invalid(s"peaks per rotation must be positive, got $peaksPerRotation")
    else if
      minimumCandidatesToStop <= 0 ||
        minimumCandidatesToStop > maximumRetainedCandidates
    then
      invalid(
        s"minimum candidates to stop must be within [1, $maximumRetainedCandidates], got $minimumCandidatesToStop"
      )
    else if !unitInterval(minimumOverlapFraction) then
      invalid(s"minimum overlap fraction must be in (0, 1], got $minimumOverlapFraction")
    else if !positive(minimumStructuralEnergy) then
      invalid(s"minimum structural energy must be positive, got $minimumStructuralEnergy")
    else if
      !minimumStructuralScore.isFinite || minimumStructuralScore < -1.0 ||
        minimumStructuralScore > 1.0
    then invalid(s"minimum structural score must be in [-1, 1], got $minimumStructuralScore")
    else if !positive(minimumInterpolatedSupport) then
      invalid(s"minimum interpolated support must be positive, got $minimumInterpolatedSupport")
    else if !positive(minimumPeakSeparationMillimetres) then
      invalid(s"minimum peak separation must be positive, got $minimumPeakSeparationMillimetres")
    else if !positive(minimumCandidateDisplacementMillimetres) then
      invalid(
        s"minimum candidate displacement must be positive, got $minimumCandidateDisplacementMillimetres"
      )
    else
      var stageIndex = 0
      while stageIndex < stages.length do
        val stage = stages(stageIndex)
        if !stage.maximumAngleDegrees.isFinite || stage.maximumAngleDegrees < 0.0 then
          return invalid(
            s"stage $stageIndex maximum angle must be finite and nonnegative, got ${stage.maximumAngleDegrees}"
          )
        if !positive(stage.stepDegrees) then
          return invalid(
            s"stage $stageIndex step must be positive, got ${stage.stepDegrees}"
          )
        val steps = stage.maximumAngleDegrees / stage.stepDegrees
        if math.abs(steps - math.rint(steps)) > 1e-10 || steps > 12.0 then
          return invalid(
            s"stage $stageIndex maximum angle must be an integer multiple of its step with at most 12 steps per axis"
          )
        stageIndex += 1
      Right(
        new StructuralCaptureConfig3(
          stages,
          seed,
          maximumRotations,
          maximumRetainedCandidates,
          peaksPerRotation,
          minimumCandidatesToStop,
          minimumOverlapFraction,
          minimumStructuralEnergy,
          minimumStructuralScore,
          minimumInterpolatedSupport,
          minimumPeakSeparationMillimetres,
          minimumCandidateDisplacementMillimetres
        )
      )

  private def invalid(
      detail: String
  ): Either[StructuralCaptureError, StructuralCaptureConfig3] =
    Left(StructuralCaptureError.InvalidConfiguration(detail))

  private def positive(value: Double): Boolean = value.isFinite && value > 0.0
  private def unitInterval(value: Double): Boolean = positive(value) && value <= 1.0

private[flashalign] final case class ScheduledCaptureRotation3(
    id: Int,
    stage: Int,
    xDegrees: Double,
    yDegrees: Double,
    zDegrees: Double,
    rotation: CaptureRotation3
)

private[flashalign] object CaptureRotationSchedule3:
  private final case class RotationKey(x: Long, y: Long, z: Long)

  def build(
      config: StructuralCaptureConfig3
  ): Vector[ScheduledCaptureRotation3] =
    val seen = mutable.HashSet.empty[RotationKey]
    val output = Vector.newBuilder[ScheduledCaptureRotation3]
    var accepted = 0
    var stageIndex = 0
    while stageIndex < config.stages.length && accepted < config.maximumRotations do
      val stage = config.stages(stageIndex)
      val steps = math.rint(stage.maximumAngleDegrees / stage.stepDegrees).toInt
      val proposals = Vector.newBuilder[(Double, Long, Int, Int, Int)]
      var ix = -steps
      while ix <= steps do
        var iy = -steps
        while iy <= steps do
          var iz = -steps
          while iz <= steps do
            val magnitude = ix.toDouble * ix + iy.toDouble * iy + iz.toDouble * iz
            val tie = splitMix64(
              config.seed ^
                ix.toLong * 0x9e3779b97f4a7c15L ^
                iy.toLong * 0xbf58476d1ce4e5b9L ^
                iz.toLong * 0x94d049bb133111ebL
            )
            proposals += ((magnitude, tie, ix, iy, iz))
            iz += 1
          iy += 1
        ix += 1
      proposals.result().sortBy(value => (value._1, value._2, value._3, value._4, value._5)).foreach {
        proposal =>
          if accepted < config.maximumRotations then
            val x = proposal._3 * stage.stepDegrees
            val y = proposal._4 * stage.stepDegrees
            val z = proposal._5 * stage.stepDegrees
            val key = RotationKey(quantize(x), quantize(y), quantize(z))
            if seen.add(key) then
              val radians = math.Pi / 180.0
              val rotation = CaptureRotation3
                .fromEulerXyzRadians(x * radians, y * radians, z * radians)
                .fold(error => throw new IllegalStateException(error.message), identity)
              output += ScheduledCaptureRotation3(
                accepted,
                stageIndex,
                x,
                y,
                z,
                rotation
              )
              accepted += 1
      }
      stageIndex += 1
    output.result()

  private def quantize(value: Double): Long = math.round(value * 1000000000.0)

  private def splitMix64(input: Long): Long =
    var value = input + 0x9e3779b97f4a7c15L
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL
    value ^ (value >>> 31)

private[flashalign] final case class StructuralCaptureCandidate3(
    rotationId: Int,
    stage: Int,
    rotation: CaptureRotation3,
    translationXMillimetres: Double,
    translationYMillimetres: Double,
    translationZMillimetres: Double,
    lagX: Int,
    lagY: Int,
    lagZ: Int,
    structuralScore: Double,
    overlapFraction: Double
)

private[flashalign] final case class CaptureRotationEvaluation3(
    rotationId: Int,
    stage: Int,
    bestOverlapFraction: Double,
    bestInformativeScore: Option[Double],
    overlapEligibleLags: Int,
    informativeLags: Int,
    acceptedPeaks: Int
)

private[flashalign] final case class StructuralCaptureDiagnostics3(
    scheduleVersion: String,
    seed: Long,
    scheduledRotations: Int,
    evaluations: Vector[CaptureRotationEvaluation3],
    identityOverlapFraction: Double,
    identityStructuralScore: Option[Double],
    identityAdequate: Boolean,
    expandedCaptureExecuted: Boolean
):
  val evaluatedRotations: Int = evaluations.size

private[flashalign] sealed trait StructuralCaptureOutcome3:
  def diagnostics: StructuralCaptureDiagnostics3

private[flashalign] object StructuralCaptureOutcome3:
  final case class Captured(
      candidates: Vector[StructuralCaptureCandidate3],
      diagnostics: StructuralCaptureDiagnostics3
  ) extends StructuralCaptureOutcome3

  final case class InsufficientOverlap(
      diagnostics: StructuralCaptureDiagnostics3
  ) extends StructuralCaptureOutcome3

  final case class InsufficientInformation(
      diagnostics: StructuralCaptureDiagnostics3
  ) extends StructuralCaptureOutcome3

  final case class ExhaustedSchedule(
      bestRejectedScore: Option[Double],
      diagnostics: StructuralCaptureDiagnostics3
  ) extends StructuralCaptureOutcome3

private[flashalign] final class StructuralCaptureWorkspace3 private[flashalign] (
    private val owner: AnyRef,
    private[flashalign] val spectral: LinearCorrelation3Workspace,
    private[flashalign] val rotatedComponents: Array[Array[Double]],
    private[flashalign] val rotatedSupport: Array[Double],
    private[flashalign] val tensorSampleScratch: Array[Double],
    private[flashalign] val tensorInputs: Array[Array[Double]],
    private[flashalign] val leftInput: Array[Double],
    private[flashalign] val numerator: Array[Double],
    private[flashalign] val leftEnergy: Array[Double],
    private[flashalign] val rightEnergy: Array[Double],
    private[flashalign] val overlap: Array[Double],
    private[flashalign] val correlationInputs: Array[Array[Double]],
    private[flashalign] val correlationOutputs: Array[Array[Double]],
    private[flashalign] val scores: Array[Double]
):
  private var active = false

  private[flashalign] def acquire(candidate: AnyRef): Either[StructuralCaptureError, Unit] =
    this.synchronized {
      if !(owner eq candidate) then Left(StructuralCaptureError.WorkspacePlanMismatch)
      else if active then Left(StructuralCaptureError.WorkspaceInUse)
      else
        active = true
        Right(())
    }

  private[flashalign] def release(candidate: AnyRef): Unit =
    this.synchronized {
      if owner eq candidate then active = false
    }

private final class FixedStructuralSpectra3(
    val tensorComponents: Array[PreparedLinearCorrelation3Right],
    val support: PreparedLinearCorrelation3Right,
    val tensorNorm: PreparedLinearCorrelation3Right
):
  val complexScalarCount: Long =
    tensorComponents.map(_.complexScalarCount).sum +
      support.complexScalarCount + tensorNorm.complexScalarCount

private object FixedStructuralSpectra3:
  def prepare(
      plan: LinearCorrelation3Plan,
      fixed: NormalizedGradientTensor3
  ): Either[SpectralError, FixedStructuralSpectra3] =
    val firstInput = new Array[Double](fixed.lattice.elementCount)
    val secondInput = new Array[Double](fixed.lattice.elementCount)
    val workspace = plan.newWorkspace()
    val components = new Array[PreparedLinearCorrelation3Right](6)
    var component = 0
    var failure = Option.empty[SpectralError]
    while component < components.length && failure.isEmpty do
      var index = 0
      while index < firstInput.length do
        firstInput(index) =
          fixed.support(index) * fixed.components(component)(index)
        secondInput(index) =
          fixed.support(index) * fixed.components(component + 1)(index)
        index += 1
      plan.prepareRightPair(firstInput, secondInput, workspace) match
        case Left(error) => failure = Some(error)
        case Right((first, second)) =>
          components(component) = first
          components(component + 1) = second
      component += 2
    failure match
      case Some(error) => Left(error)
      case None =>
        var index = 0
        while index < firstInput.length do
          firstInput(index) = fixed.support(index)
          secondInput(index) =
            fixed.support(index) * tensorNormSquared(fixed.components, index)
          index += 1
        plan.prepareRightPair(firstInput, secondInput, workspace).map {
          case (support, tensorNorm) =>
            new FixedStructuralSpectra3(components, support, tensorNorm)
        }

  private def tensorNormSquared(
      components: Array[Array[Double]],
      index: Int
  ): Double =
    components(0)(index) * components(0)(index) +
      components(1)(index) * components(1)(index) +
      components(2)(index) * components(2)(index) +
      2.0 * (
        components(3)(index) * components(3)(index) +
          components(4)(index) * components(4)(index) +
          components(5)(index) * components(5)(index)
      )

private[flashalign] final class StructuralCapturePlan3 private (
    val moving: NormalizedGradientTensor3,
    val fixed: NormalizedGradientTensor3,
    val config: StructuralCaptureConfig3,
    val pivotXMillimetres: Double,
    val pivotYMillimetres: Double,
    val pivotZMillimetres: Double,
    private val correlationPlan: LinearCorrelation3Plan,
    private val fixedSpectra: FixedStructuralSpectra3,
    val schedule: Vector[ScheduledCaptureRotation3]
):
  private val outputCount = correlationPlan.outputShape.elementCount
  private val supportDenominator = math.min(moving.totalSupport, fixed.totalSupport)
  private val correlationTermLeftIndices = Array(0, 1, 2, 3, 4, 5, 6, 7, 7)
  private val correlationTermRight =
    fixedSpectra.tensorComponents ++
      Array(fixedSpectra.support, fixedSpectra.tensorNorm, fixedSpectra.support)
  private val correlationTermWeights =
    Array(1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0)
  private val correlationTermOutputIndices = Array(0, 0, 0, 0, 0, 0, 1, 2, 3)

  private[flashalign] val preparedFixedComplexScalarCount: Long =
    fixedSpectra.complexScalarCount

  def newWorkspace(): StructuralCaptureWorkspace3 =
    val rotatedComponents =
      Array.fill(6)(new Array[Double](moving.lattice.elementCount))
    val rotatedSupport = new Array[Double](moving.lattice.elementCount)
    val tensorInputs = Array.fill(6)(new Array[Double](moving.lattice.elementCount))
    val leftInput = new Array[Double](moving.lattice.elementCount)
    val numerator = new Array[Double](outputCount)
    val leftEnergy = new Array[Double](outputCount)
    val rightEnergy = new Array[Double](outputCount)
    val overlap = new Array[Double](outputCount)
    new StructuralCaptureWorkspace3(
      this,
      correlationPlan.newWorkspace(batchOutputCapacity = 4),
      rotatedComponents,
      rotatedSupport,
      new Array[Double](7),
      tensorInputs,
      leftInput,
      numerator,
      leftEnergy,
      rightEnergy,
      overlap,
      tensorInputs ++ Array(leftInput, rotatedSupport),
      Array(numerator, leftEnergy, rightEnergy, overlap),
      new Array[Double](outputCount)
    )

  def capture(
      workspace: StructuralCaptureWorkspace3
  ): Either[StructuralCaptureError, StructuralCaptureOutcome3] =
    workspace.acquire(this).flatMap { _ =>
      try execute(workspace)
      finally workspace.release(this)
    }

  private[flashalign] def identityScoreSnapshot(
      workspace: StructuralCaptureWorkspace3
  ): Either[StructuralCaptureError, Array[Double]] =
    workspace.acquire(this).flatMap { _ =>
      try
        evaluateRotation(schedule.head, workspace).map(_ => workspace.scores.clone())
      finally workspace.release(this)
    }

  private def execute(
      workspace: StructuralCaptureWorkspace3
  ): Either[StructuralCaptureError, StructuralCaptureOutcome3] =
    val identityRotation = schedule.head
    val identityResult = evaluateRotation(identityRotation, workspace) match
      case Left(error)   => return Left(error)
      case Right(result) => result
    val identity = identityCandidate(identityRotation, workspace)
    val identityAdequate = identity.exists(candidate =>
      identityResult.peaks.exists(peak =>
        peak.lagX == candidate.lagX && peak.lagY == candidate.lagY &&
          peak.lagZ == candidate.lagZ
      )
    )
    if identityAdequate then
      val candidate = identity.get
      val diagnostics = StructuralCaptureDiagnostics3(
        StructuralCaptureConfig3.ScheduleVersion,
        config.seed,
        schedule.size,
        Vector(identityResult.evaluation),
        candidate.overlapFraction,
        Some(candidate.structuralScore),
        identityAdequate = true,
        expandedCaptureExecuted = false
      )
      return Right(
        StructuralCaptureOutcome3.Captured(Vector(candidate), diagnostics)
      )

    var retained = Vector.empty[StructuralCaptureCandidate3]
    val evaluations = Vector.newBuilder[CaptureRotationEvaluation3]
    evaluations += identityResult.evaluation
    var anyOverlap = identityResult.evaluation.overlapEligibleLags > 0
    var anyInformation = identityResult.evaluation.informativeLags > 0
    var bestRejected = identityResult.evaluation.bestInformativeScore
    retained = retainDistinct(identityResult.peaks)
    var index = 1
    var finishedStage = -1
    while index < schedule.length && finishedStage < 0 do
      val scheduled = schedule(index)
      evaluateRotation(scheduled, workspace) match
        case Left(error) => return Left(error)
        case Right(result) =>
          evaluations += result.evaluation
          anyOverlap ||= result.evaluation.overlapEligibleLags > 0
          anyInformation ||= result.evaluation.informativeLags > 0
          result.evaluation.bestInformativeScore.foreach { score =>
            if bestRejected.forall(score > _) then bestRejected = Some(score)
          }
          retained = retainDistinct(retained ++ result.peaks)
          val nextStage =
            if index + 1 < schedule.length then schedule(index + 1).stage else -1
          if
            nextStage != scheduled.stage &&
              retained.size >= config.minimumCandidatesToStop
          then finishedStage = scheduled.stage
      index += 1
    val diagnostics = StructuralCaptureDiagnostics3(
      StructuralCaptureConfig3.ScheduleVersion,
      config.seed,
      schedule.size,
      evaluations.result(),
      identity.map(_.overlapFraction).getOrElse(identityOverlapFraction(workspace)),
      identity.map(_.structuralScore),
      identityAdequate = false,
      expandedCaptureExecuted = true
    )
    if retained.nonEmpty then
      Right(StructuralCaptureOutcome3.Captured(retained, diagnostics))
    else if !anyOverlap then
      Right(StructuralCaptureOutcome3.InsufficientOverlap(diagnostics))
    else if !anyInformation then
      Right(StructuralCaptureOutcome3.InsufficientInformation(diagnostics))
    else
      Right(StructuralCaptureOutcome3.ExhaustedSchedule(bestRejected, diagnostics))

  private def identityCandidate(
      scheduled: ScheduledCaptureRotation3,
      workspace: StructuralCaptureWorkspace3
  ): Option[StructuralCaptureCandidate3] =
    val movingLattice = moving.lattice
    val fixedLattice = fixed.lattice
    val spacings = Array(
      movingLattice.spacingXMillimetres,
      movingLattice.spacingYMillimetres,
      movingLattice.spacingZMillimetres
    )
    val origins = Array(
      movingLattice.originXMillimetres -> fixedLattice.originXMillimetres,
      movingLattice.originYMillimetres -> fixedLattice.originYMillimetres,
      movingLattice.originZMillimetres -> fixedLattice.originZMillimetres
    )
    val lags = Array.ofDim[Int](3)
    var axis = 0
    while axis < 3 do
      val exact = (origins(axis)._1 - origins(axis)._2) / spacings(axis)
      val rounded = math.rint(exact)
      if math.abs(exact - rounded) > 1e-10 then return None
      lags(axis) = rounded.toInt
      axis += 1
    val outputX = lags(0) + movingLattice.shape.x - 1
    val outputY = lags(1) + movingLattice.shape.y - 1
    val outputZ = lags(2) + movingLattice.shape.z - 1
    val outputShape = correlationPlan.outputShape
    if outputX < 0 || outputX >= outputShape.x || outputY < 0 ||
      outputY >= outputShape.y || outputZ < 0 || outputZ >= outputShape.z
    then None
    else
      val flat = (outputX * outputShape.y + outputY) * outputShape.z + outputZ
      val score = workspace.scores(flat)
      if score.isFinite then
        Some(candidateAt(scheduled, flat, score, workspace))
      else None

  private def identityOverlapFraction(
      workspace: StructuralCaptureWorkspace3
  ): Double =
    val movingLattice = moving.lattice
    val fixedLattice = fixed.lattice
    if
      movingLattice.shape != fixedLattice.shape ||
      movingLattice.originXMillimetres != fixedLattice.originXMillimetres ||
      movingLattice.originYMillimetres != fixedLattice.originYMillimetres ||
      movingLattice.originZMillimetres != fixedLattice.originZMillimetres
    then 0.0
    else
      val outputShape = correlationPlan.outputShape
      val flat =
        ((movingLattice.shape.x - 1) * outputShape.y +
          movingLattice.shape.y - 1) * outputShape.z +
          movingLattice.shape.z - 1
      if supportDenominator > 0.0 then
        math.max(0.0, workspace.overlap(flat)) / supportDenominator
      else 0.0

  private final case class RotationResult(
      peaks: Vector[StructuralCaptureCandidate3],
      evaluation: CaptureRotationEvaluation3
  )

  private def evaluateRotation(
      scheduled: ScheduledCaptureRotation3,
      workspace: StructuralCaptureWorkspace3
  ): Either[StructuralCaptureError, RotationResult] =
    StructuralTensorRotation3.rotateInto(
      moving,
      scheduled.rotation,
      pivotXMillimetres,
      pivotYMillimetres,
      pivotZMillimetres,
      config.minimumInterpolatedSupport,
      workspace.rotatedComponents,
      workspace.rotatedSupport,
      workspace.tensorSampleScratch
    )
    clearScoreAccumulators(workspace)
    fillTensorChannels(workspace)
    fillLeftEnergy(workspace)
    correlationPlan
      .correlatePreparedWeightedGroups(
        workspace.correlationInputs,
        correlationTermLeftIndices,
        correlationTermRight,
        correlationTermWeights,
        correlationTermOutputIndices,
        workspace.correlationOutputs,
        workspace.spectral
      )
      .left
      .map(StructuralCaptureError.Spectral.apply) match
      case Left(error) => return Left(error)
      case Right(_)    => ()
    Right(extractPeaks(scheduled, workspace))

  private def clearScoreAccumulators(workspace: StructuralCaptureWorkspace3): Unit =
    java.util.Arrays.fill(workspace.numerator, 0.0)
    java.util.Arrays.fill(workspace.leftEnergy, 0.0)
    java.util.Arrays.fill(workspace.rightEnergy, 0.0)
    java.util.Arrays.fill(workspace.overlap, 0.0)
    java.util.Arrays.fill(workspace.scores, Double.NaN)

  private def fillTensorChannels(
      workspace: StructuralCaptureWorkspace3
  ): Unit =
    var component = 0
    while component < workspace.tensorInputs.length do
      var index = 0
      while index < workspace.leftInput.length do
        workspace.tensorInputs(component)(index) =
          workspace.rotatedSupport(index) * workspace.rotatedComponents(component)(index)
        index += 1
      component += 1

  private def fillLeftEnergy(workspace: StructuralCaptureWorkspace3): Unit =
    var index = 0
    while index < workspace.leftInput.length do
      workspace.leftInput(index) =
        workspace.rotatedSupport(index) * tensorNormSquared(workspace.rotatedComponents, index)
      index += 1

  private def extractPeaks(
      scheduled: ScheduledCaptureRotation3,
      workspace: StructuralCaptureWorkspace3
  ): RotationResult =
    var bestOverlap = 0.0
    var bestScore = Option.empty[Double]
    var overlapEligible = 0
    var informative = 0
    var index = 0
    while index < outputCount do
      val overlapFraction =
        if supportDenominator > 0.0 then
          math.max(0.0, workspace.overlap(index)) / supportDenominator
        else 0.0
      if overlapFraction > bestOverlap then bestOverlap = overlapFraction
      if overlapFraction >= config.minimumOverlapFraction then
        overlapEligible += 1
        val energyProduct = workspace.leftEnergy(index) * workspace.rightEnergy(index)
        if energyProduct >= config.minimumStructuralEnergy then
          informative += 1
          val score = workspace.numerator(index) / math.sqrt(energyProduct)
          if score.isFinite then
            workspace.scores(index) = score
            if bestScore.forall(score > _) then bestScore = Some(score)
      index += 1
    val peaks = Vector.newBuilder[StructuralCaptureCandidate3]
    val ordered = Vector.newBuilder[(Double, Int)]
    index = 0
    while index < outputCount do
      val score = workspace.scores(index)
      if score.isFinite && score >= config.minimumStructuralScore &&
        isDeterministicLocalMaximum(index, workspace.scores)
      then ordered += ((score, index))
      index += 1
    var retained = Vector.empty[StructuralCaptureCandidate3]
    ordered.result().sortBy(value => (-value._1, value._2)).foreach { value =>
      if retained.size < config.peaksPerRotation then
        val candidate = candidateAt(scheduled, value._2, value._1, workspace)
        if retained.forall(peakSeparated(candidate, _)) then retained :+= candidate
    }
    peaks ++= retained
    RotationResult(
      peaks.result(),
      CaptureRotationEvaluation3(
        scheduled.id,
        scheduled.stage,
        bestOverlap,
        bestScore,
        overlapEligible,
        informative,
        retained.size
      )
    )

  private def candidateAt(
      scheduled: ScheduledCaptureRotation3,
      outputIndex: Int,
      score: Double,
      workspace: StructuralCaptureWorkspace3
  ): StructuralCaptureCandidate3 =
    val outputShape = correlationPlan.outputShape
    val yz = outputShape.y * outputShape.z
    val x = outputIndex / yz
    val remainder = outputIndex - x * yz
    val y = remainder / outputShape.z
    val z = remainder - y * outputShape.z
    val lagX = x - moving.lattice.shape.x + 1
    val lagY = y - moving.lattice.shape.y + 1
    val lagZ = z - moving.lattice.shape.z + 1
    val overlapFraction = math.max(0.0, workspace.overlap(outputIndex)) / supportDenominator
    StructuralCaptureCandidate3(
      scheduled.id,
      scheduled.stage,
      scheduled.rotation,
      fixed.lattice.originXMillimetres - moving.lattice.originXMillimetres +
        lagX * moving.lattice.spacingXMillimetres,
      fixed.lattice.originYMillimetres - moving.lattice.originYMillimetres +
        lagY * moving.lattice.spacingYMillimetres,
      fixed.lattice.originZMillimetres - moving.lattice.originZMillimetres +
        lagZ * moving.lattice.spacingZMillimetres,
      lagX,
      lagY,
      lagZ,
      score,
      overlapFraction
    )

  private def isDeterministicLocalMaximum(
      flat: Int,
      scores: Array[Double]
  ): Boolean =
    val shape = correlationPlan.outputShape
    val yz = shape.y * shape.z
    val x = flat / yz
    val remainder = flat - x * yz
    val y = remainder / shape.z
    val z = remainder - y * shape.z
    val score = scores(flat)
    var dx = -1
    while dx <= 1 do
      var dy = -1
      while dy <= 1 do
        var dz = -1
        while dz <= 1 do
          val nx = x + dx
          val ny = y + dy
          val nz = z + dz
          if
            nx >= 0 && nx < shape.x && ny >= 0 && ny < shape.y &&
              nz >= 0 && nz < shape.z && (dx != 0 || dy != 0 || dz != 0)
          then
            val neighbor = (nx * shape.y + ny) * shape.z + nz
            val neighborScore = scores(neighbor)
            if neighborScore.isFinite &&
              (neighborScore > score || (neighborScore == score && neighbor < flat))
            then return false
          dz += 1
        dy += 1
      dx += 1
    true

  private def peakSeparated(
      left: StructuralCaptureCandidate3,
      right: StructuralCaptureCandidate3
  ): Boolean =
    val dx = left.translationXMillimetres - right.translationXMillimetres
    val dy = left.translationYMillimetres - right.translationYMillimetres
    val dz = left.translationZMillimetres - right.translationZMillimetres
    math.sqrt(dx * dx + dy * dy + dz * dz) >=
      config.minimumPeakSeparationMillimetres

  private def retainDistinct(
      candidates: Vector[StructuralCaptureCandidate3]
  ): Vector[StructuralCaptureCandidate3] =
    var output = Vector.empty[StructuralCaptureCandidate3]
    candidates.sortBy(candidate => (-candidate.structuralScore, candidate.rotationId, candidate.lagX, candidate.lagY, candidate.lagZ)).foreach {
      candidate =>
        if output.size < config.maximumRetainedCandidates &&
          output.forall(other => maximumCornerDisagreement(candidate, other) >= config.minimumCandidateDisplacementMillimetres)
        then output :+= candidate
    }
    output

  private def maximumCornerDisagreement(
      left: StructuralCaptureCandidate3,
      right: StructuralCaptureCandidate3
  ): Double =
    val lattice = moving.lattice
    var maximum = 0.0
    var ix = 0
    while ix <= 1 do
      val x = if ix == 0 then lattice.worldX(0) else lattice.worldX(lattice.shape.x - 1)
      var iy = 0
      while iy <= 1 do
        val y = if iy == 0 then lattice.worldY(0) else lattice.worldY(lattice.shape.y - 1)
        var iz = 0
        while iz <= 1 do
          val z = if iz == 0 then lattice.worldZ(0) else lattice.worldZ(lattice.shape.z - 1)
          val distance = mappedDistance(left, right, x, y, z)
          if distance > maximum then maximum = distance
          iz += 1
        iy += 1
      ix += 1
    maximum

  private def mappedDistance(
      left: StructuralCaptureCandidate3,
      right: StructuralCaptureCandidate3,
      x: Double,
      y: Double,
      z: Double
  ): Double =
    val leftMapped = mapPoint(left, x, y, z)
    val rightMapped = mapPoint(right, x, y, z)
    val dx = leftMapped._1 - rightMapped._1
    val dy = leftMapped._2 - rightMapped._2
    val dz = leftMapped._3 - rightMapped._3
    math.sqrt(dx * dx + dy * dy + dz * dz)

  private def mapPoint(
      candidate: StructuralCaptureCandidate3,
      x: Double,
      y: Double,
      z: Double
  ): (Double, Double, Double) =
    val r = candidate.rotation.rowMajor
    val dx = x - pivotXMillimetres
    val dy = y - pivotYMillimetres
    val dz = z - pivotZMillimetres
    (
      pivotXMillimetres + r(0) * dx + r(1) * dy + r(2) * dz +
        candidate.translationXMillimetres,
      pivotYMillimetres + r(3) * dx + r(4) * dy + r(5) * dz +
        candidate.translationYMillimetres,
      pivotZMillimetres + r(6) * dx + r(7) * dy + r(8) * dz +
        candidate.translationZMillimetres
    )

  private def tensorNormSquared(
      components: Array[Array[Double]],
      index: Int
  ): Double =
    components(0)(index) * components(0)(index) +
      components(1)(index) * components(1)(index) +
      components(2)(index) * components(2)(index) +
      2.0 * (
        components(3)(index) * components(3)(index) +
          components(4)(index) * components(4)(index) +
          components(5)(index) * components(5)(index)
      )

private[flashalign] object StructuralCapturePlan3:
  def create(
      moving: NormalizedGradientTensor3,
      fixed: NormalizedGradientTensor3,
      config: StructuralCaptureConfig3,
      pivotXMillimetres: Double,
      pivotYMillimetres: Double,
      pivotZMillimetres: Double
  ): Either[StructuralCaptureError, StructuralCapturePlan3] =
    val pivots = Array(pivotXMillimetres, pivotYMillimetres, pivotZMillimetres)
    var axis = 0
    while axis < pivots.length do
      if !pivots(axis).isFinite then
        return Left(
          StructuralCaptureError.NonFiniteValue(
            StructuralCaptureQuantity.Origin,
            axis,
            pivots(axis)
          )
        )
      axis += 1
    val movingLattice = moving.lattice
    val fixedLattice = fixed.lattice
    val spacings = Array(
      movingLattice.spacingXMillimetres -> fixedLattice.spacingXMillimetres,
      movingLattice.spacingYMillimetres -> fixedLattice.spacingYMillimetres,
      movingLattice.spacingZMillimetres -> fixedLattice.spacingZMillimetres
    )
    axis = 0
    while axis < spacings.length do
      val pair = spacings(axis)
      if math.abs(pair._1 - pair._2) > 1e-12 * math.max(pair._1, pair._2) then
        return Left(
          StructuralCaptureError.LatticeMismatch(
            s"capture spacing axis $axis differs: ${pair._1} versus ${pair._2}"
          )
        )
      axis += 1
    for
      plan <- LinearCorrelation3Plan
        .create(movingLattice.shape, fixedLattice.shape)
        .left
        .map(StructuralCaptureError.Spectral.apply)
      fixedSpectra <- FixedStructuralSpectra3
        .prepare(plan, fixed)
        .left
        .map(StructuralCaptureError.Spectral.apply)
    yield new StructuralCapturePlan3(
      moving,
      fixed,
      config,
      pivotXMillimetres,
      pivotYMillimetres,
      pivotZMillimetres,
      plan,
      fixedSpectra,
      CaptureRotationSchedule3.build(config)
    )

private[flashalign] final case class CaptureCandidateAssessment3(
    candidate: StructuralCaptureCandidate3,
    objective: Double
)

private[flashalign] final case class CommonCaptureComparison3(
    assessments: Vector[CaptureCandidateAssessment3],
    competingAlignments: Boolean
):
  def best: CaptureCandidateAssessment3 = assessments.head

private[flashalign] object CommonCaptureComparison3:
  def evaluateAll(
      candidates: Vector[StructuralCaptureCandidate3],
      nearEqualObjectiveTolerance: Double
  )(
      objective: StructuralCaptureCandidate3 => Double
  ): Either[StructuralCaptureError, CommonCaptureComparison3] =
    if candidates.isEmpty then
      Left(StructuralCaptureError.InvalidConfiguration("candidate comparison requires candidates"))
    else if
      !nearEqualObjectiveTolerance.isFinite || nearEqualObjectiveTolerance < 0.0
    then
      Left(
        StructuralCaptureError.InvalidConfiguration(
          s"near-equal objective tolerance must be finite and nonnegative, got $nearEqualObjectiveTolerance"
        )
      )
    else
      val evaluated = Vector.newBuilder[CaptureCandidateAssessment3]
      var index = 0
      while index < candidates.length do
        val value = objective(candidates(index))
        if !value.isFinite then
          return Left(
            StructuralCaptureError.InvalidConfiguration(
              s"common objective returned non-finite value $value for candidate $index"
            )
          )
        evaluated += CaptureCandidateAssessment3(candidates(index), value)
        index += 1
      val ranked = evaluated.result().sortBy(item => (item.objective, item.candidate.rotationId, item.candidate.lagX, item.candidate.lagY, item.candidate.lagZ))
      val competing =
        ranked.size > 1 &&
          ranked(1).objective - ranked.head.objective <= nearEqualObjectiveTolerance
      Right(CommonCaptureComparison3(ranked, competing))
