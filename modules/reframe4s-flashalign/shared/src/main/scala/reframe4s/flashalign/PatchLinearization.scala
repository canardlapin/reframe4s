package reframe4s.flashalign

/** Allocation-free scalar access for one already-sampled patch linearization. */
private[flashalign] trait PatchSampleSource:
  def sampleCount: Int
  def parameterCount: Int
  def value(sample: Int): Double
  def derivative(sample: Int, parameter: Int): Double

private[flashalign] enum PatchStatisticsMode derives CanEqual:
  case Streamed
  case CenteredDiagnostic
  case InvalidContrast

private[flashalign] sealed trait PatchLinearizationError derives CanEqual:
  def message: String

private[flashalign] object PatchLinearizationError:
  final case class SampleCountMismatch(expected: Int, actual: Int)
      extends PatchLinearizationError:
    val message: String =
      s"moving patch has $expected samples but linearization has $actual"

  final case class UnsupportedParameterCount(value: Int)
      extends PatchLinearizationError:
    val message: String =
      s"streamed linear patch statistics require 6 or 12 parameters, got $value"

  final case class WorkspaceParameterMismatch(expected: Int, actual: Int)
      extends PatchLinearizationError:
    val message: String =
      s"linearization workspace has $actual parameters but source requires $expected"

  final case class NonFiniteValue(sample: Int, value: Double)
      extends PatchLinearizationError:
    val message: String = s"fixed sample $sample must be finite, got $value"

  final case class NonFiniteDerivative(
      sample: Int,
      parameter: Int,
      value: Double
  ) extends PatchLinearizationError:
    val message: String =
      s"intensity derivative ($sample, $parameter) must be finite, got $value"

  final case class MaterialIndefiniteness(
      minimumDiagonal: Double,
      maximumScale: Double
  ) extends PatchLinearizationError:
    val message: String =
      s"explicitly centred patch curvature remains materially indefinite: minimum diagonal $minimumDiagonal at scale $maximumScale"

  final case class NonFiniteCenteredStatistic(
      statistic: String,
      index: Int,
      value: Double
  ) extends PatchLinearizationError:
    val message: String =
      s"explicitly centred $statistic statistic $index must be finite, got $value"

/**
 * Workspace-backed result. Its arrays are overwritten by the next computation
 * with the same workspace and must be reduced before that reuse.
 */
private[flashalign] final class PatchLinearizationResult private[flashalign] (
    val jtu: Array[Double],
    val jtjUpper: Array[Double]
):
  private[flashalign] var currentCorrelation = 0.0
  private[flashalign] var currentFixedContrastNorm = 0.0
  private[flashalign] var currentMode = PatchStatisticsMode.Streamed

  def correlation: Double = currentCorrelation
  def fixedContrastNorm: Double = currentFixedContrastNorm
  def mode: PatchStatisticsMode = currentMode
  def active: Boolean = currentMode != PatchStatisticsMode.InvalidContrast

  def curvature(row: Int, column: Int): Double =
    jtjUpper(PackedSymmetric.index(row, column))

private[flashalign] final class PatchLinearizationWorkspace private (
    val parameterCount: Int,
    private[flashalign] val sg: Array[Double],
    private[flashalign] val shg: Array[Double],
    private[flashalign] val sug: Array[Double],
    private[flashalign] val sgg: Array[Double],
    private[flashalign] val gradientOrigin: Array[Double],
    private[flashalign] val meanGradient: Array[Double],
    private[flashalign] val a: Array[Double],
    private[flashalign] val q: Array[Double],
    private[flashalign] val centeredSgg: Array[Double],
    private[flashalign] val referenceJtu: Array[Double],
    private[flashalign] val referenceJtj: Array[Double],
    private[flashalign] val result: PatchLinearizationResult
):
  private[flashalign] var scalarOrigin = 0.0
  private[flashalign] var scalarShiftedSum = 0.0
  private[flashalign] var scalarShiftedSquares = 0.0
  private[flashalign] var scalarShiftedMovingDot = 0.0
  private[flashalign] var scalarSampleCount = 0
  private[flashalign] var diagnosticRecomputeCount = 0L
  private[flashalign] var diagnosticAcceptedCount = 0L
  private[flashalign] var diagnosticFailureCount = 0L

  def diagnostics: PatchLinearizationDiagnostics =
    PatchLinearizationDiagnostics(
      diagnosticRecomputeCount,
      diagnosticAcceptedCount,
      diagnosticFailureCount
    )

private[flashalign] object PatchLinearizationWorkspace:
  def create(
      parameterCount: Int
  ): Either[PatchLinearizationError, PatchLinearizationWorkspace] =
    if parameterCount != 6 && parameterCount != 12 then
      Left(
        PatchLinearizationError.UnsupportedParameterCount(parameterCount)
      )
    else
      val packedCount = PackedSymmetric.size(parameterCount)
      val jtu = new Array[Double](parameterCount)
      val jtj = new Array[Double](packedCount)
      Right(
        new PatchLinearizationWorkspace(
          parameterCount,
          new Array[Double](parameterCount),
          new Array[Double](parameterCount),
          new Array[Double](parameterCount),
          new Array[Double](packedCount),
          new Array[Double](parameterCount),
          new Array[Double](parameterCount),
          new Array[Double](parameterCount),
          new Array[Double](parameterCount),
          new Array[Double](packedCount),
          new Array[Double](parameterCount),
          new Array[Double](packedCount),
          new PatchLinearizationResult(jtu, jtj)
        )
      )

private[flashalign] final case class PatchLinearizationDiagnostics(
    centeredRecomputations: Long,
    acceptedCenteredRecomputations: Long,
    failedCenteredRecomputations: Long
)

private[flashalign] object PackedSymmetric:
  def size(dimension: Int): Int = dimension * (dimension + 1) / 2

  def index(row: Int, column: Int): Int =
    val first = math.min(row, column)
    val second = math.max(row, column)
    second * (second + 1) / 2 + first

private[flashalign] object PatchLinearization:
  private val CancellationRatio = 1e-10
  private val CheckAbsoluteTolerance = 1e-12
  private val CheckRelativeTolerance = 1e-9

  def compute(
      moving: PreparedMovingPatch,
      samples: PatchSampleSource,
      minimumContrastEnergy: Double,
      workspace: PatchLinearizationWorkspace
  ): Either[PatchLinearizationError, PatchLinearizationResult] =
    val error = computeInto(
      moving,
      samples,
      minimumContrastEnergy,
      workspace
    )
    if error == null then Right(workspace.result)
    else Left(error.asInstanceOf[PatchLinearizationError])

  /**
   * Allocation-free successful path for an already-validated reusable
   * workspace. Errors are allocated only when a contract or numerical check
   * fails; `null` denotes success and the result is available in `workspace`.
   */
  private[flashalign] def computeInto(
      moving: PreparedMovingPatch,
      samples: PatchSampleSource,
      minimumContrastEnergy: Double,
      workspace: PatchLinearizationWorkspace
  ): PatchLinearizationError | Null =
    if samples.sampleCount != moving.size then
      PatchLinearizationError.SampleCountMismatch(
        moving.size,
        samples.sampleCount
      )
    else if samples.parameterCount != 6 && samples.parameterCount != 12 then
      PatchLinearizationError.UnsupportedParameterCount(
        samples.parameterCount
      )
    else if samples.parameterCount != workspace.parameterCount then
      PatchLinearizationError.WorkspaceParameterMismatch(
        samples.parameterCount,
        workspace.parameterCount
      )
    else
      reset(workspace)
      val accumulationError = accumulateInto(moving, samples, workspace)
      if accumulationError != null then accumulationError
      else
        finishStreamed(moving, minimumContrastEnergy, workspace)
        if requiresCenteredDiagnostic(workspace) then
          centeredDiagnosticInto(
            moving,
            samples,
            minimumContrastEnergy,
            workspace
          )
        else null

  private def accumulateInto(
      moving: PreparedMovingPatch,
      samples: PatchSampleSource,
      workspace: PatchLinearizationWorkspace
  ): PatchLinearizationError | Null =
    val origin = samples.value(0)
    if !origin.isFinite then
      PatchLinearizationError.NonFiniteValue(0, origin)
    else
      var shiftedSum = 0.0
      var shiftedSquares = 0.0
      var shiftedMovingDot = 0.0
      var sample = 0
      while sample < samples.sampleCount do
        val value = samples.value(sample)
        if !value.isFinite then
          return PatchLinearizationError.NonFiniteValue(sample, value)
        val shifted = value - origin
        shiftedSum += shifted
        shiftedSquares += shifted * shifted
        shiftedMovingDot += shifted * moving.normalized(sample)
        var parameter = 0
        while parameter < workspace.parameterCount do
          val derivative = samples.derivative(sample, parameter)
          if !derivative.isFinite then
            return PatchLinearizationError.NonFiniteDerivative(
              sample,
              parameter,
              derivative
            )
          workspace.sg(parameter) += derivative
          workspace.shg(parameter) += shifted * derivative
          workspace.sug(parameter) +=
            moving.normalized(sample) * derivative
          var other = 0
          while other <= parameter do
            workspace.sgg(PackedSymmetric.index(other, parameter)) +=
              samples.derivative(sample, other) * derivative
            other += 1
          parameter += 1
        sample += 1
      workspace.scalarOrigin = origin
      workspace.scalarShiftedSum = shiftedSum
      workspace.scalarShiftedSquares = shiftedSquares
      workspace.scalarShiftedMovingDot = shiftedMovingDot
      workspace.scalarSampleCount = samples.sampleCount
      null

  private def finishStreamed(
      moving: PreparedMovingPatch,
      minimumContrastEnergy: Double,
      workspace: PatchLinearizationWorkspace
  ): Unit =
    val sampleCount = moving.size.toDouble
    val energy =
      workspace.scalarShiftedSquares -
        workspace.scalarShiftedSum * workspace.scalarShiftedSum / sampleCount
    if !energy.isFinite || energy <= minimumContrastEnergy then
      setInvalid(workspace.result)
    else
      val norm = math.sqrt(energy)
      val correlation = workspace.scalarShiftedMovingDot / norm
      var parameter = 0
      while parameter < workspace.parameterCount do
        workspace.a(parameter) =
          (workspace.shg(parameter) -
            workspace.scalarShiftedSum * workspace.sg(parameter) / sampleCount) /
            norm
        workspace.result.jtu(parameter) =
          (workspace.sug(parameter) -
            correlation * workspace.a(parameter)) /
            norm
        var other = 0
        while other <= parameter do
          val packed = PackedSymmetric.index(other, parameter)
          workspace.result.jtjUpper(packed) =
            (workspace.sgg(packed) -
              workspace.sg(other) * workspace.sg(parameter) / sampleCount -
              workspace.a(other) * workspace.a(parameter)) /
              energy
          other += 1
        parameter += 1
      workspace.result.currentCorrelation = correlation
      workspace.result.currentFixedContrastNorm = norm
      workspace.result.currentMode = PatchStatisticsMode.Streamed

  private def requiresCenteredDiagnostic(
      workspace: PatchLinearizationWorkspace
  ): Boolean =
    var maximumScale = 0.0
    var minimumDiagonal = Double.PositiveInfinity
    var cancellationRisk = false
    var parameter = 0
    while parameter < workspace.parameterCount do
      val packed = PackedSymmetric.index(parameter, parameter)
      val raw = workspace.sgg(packed)
      val meanPart =
        workspace.sg(parameter) * workspace.sg(parameter) /
          workspace.scalarSampleCount.toDouble
      val centered = raw - meanPart
      val cancellationScale = math.abs(raw) + math.abs(meanPart)
      if cancellationScale > 0.0 &&
        math.abs(centered) <= CancellationRatio * cancellationScale
      then cancellationRisk = true
      val diagonal = workspace.result.jtjUpper(packed)
      maximumScale = math.max(maximumScale, math.abs(diagonal))
      minimumDiagonal = math.min(minimumDiagonal, diagonal)
      parameter += 1
    val tolerance =
      CheckAbsoluteTolerance + CheckRelativeTolerance * maximumScale
    val diagonalFailure = minimumDiagonal < -tolerance
    val pairFailure = hasPairViolation(workspace.result, tolerance)
    val scalarMeanPart =
      workspace.scalarShiftedSum * workspace.scalarShiftedSum /
        workspace.scalarSampleCount.toDouble
    val scalarScale =
      math.abs(workspace.scalarShiftedSquares) + math.abs(scalarMeanPart)
    val scalarCentered = workspace.scalarShiftedSquares - scalarMeanPart
    val scalarCancellation =
      scalarScale > 0.0 &&
        math.abs(scalarCentered) <= CancellationRatio * scalarScale
    cancellationRisk || scalarCancellation || diagonalFailure || pairFailure ||
      !workspace.result.correlation.isFinite ||
      !workspace.result.fixedContrastNorm.isFinite ||
      workspace.result.jtu.exists(value => !value.isFinite) ||
      workspace.result.jtjUpper.exists(value => !value.isFinite) ||
      math.abs(workspace.result.correlation) > 1.0 + 1e-10

  private def hasPairViolation(
      result: PatchLinearizationResult,
      tolerance: Double
  ): Boolean =
    var first = 0
    while first < result.jtu.length do
      val firstDiagonal = result.curvature(first, first)
      var second = first + 1
      while second < result.jtu.length do
        val secondDiagonal = result.curvature(second, second)
        val cross = result.curvature(first, second)
        if firstDiagonal * secondDiagonal - cross * cross < -tolerance then
          return true
        second += 1
      first += 1
    false

  private def centeredDiagnosticInto(
      moving: PreparedMovingPatch,
      samples: PatchSampleSource,
      minimumContrastEnergy: Double,
      workspace: PatchLinearizationWorkspace
  ): PatchLinearizationError | Null =
    workspace.diagnosticRecomputeCount += 1L
    val sampleCount = samples.sampleCount.toDouble
    val meanShifted = workspace.scalarShiftedSum / sampleCount
    var parameter = 0
    while parameter < workspace.parameterCount do
      workspace.gradientOrigin(parameter) = samples.derivative(0, parameter)
      workspace.meanGradient(parameter) = 0.0
      workspace.a(parameter) = 0.0
      workspace.q(parameter) = 0.0
      parameter += 1
    var sample = 0
    while sample < samples.sampleCount do
      parameter = 0
      while parameter < workspace.parameterCount do
        workspace.meanGradient(parameter) +=
          samples.derivative(sample, parameter) -
            workspace.gradientOrigin(parameter)
        parameter += 1
      sample += 1
    parameter = 0
    while parameter < workspace.parameterCount do
      workspace.meanGradient(parameter) /= sampleCount
      parameter += 1
    clear(workspace.centeredSgg)
    var energy = 0.0
    var movingDot = 0.0
    sample = 0
    while sample < samples.sampleCount do
      val centeredValue =
        samples.value(sample) - workspace.scalarOrigin - meanShifted
      energy += centeredValue * centeredValue
      movingDot += centeredValue * moving.normalized(sample)
      sample += 1
    if !energy.isFinite || energy <= minimumContrastEnergy then
      workspace.diagnosticAcceptedCount += 1L
      setInvalid(workspace.result)
      null
    else
      val norm = math.sqrt(energy)
      val correlation = movingDot / norm
      sample = 0
      while sample < samples.sampleCount do
        val centeredValue =
          samples.value(sample) - workspace.scalarOrigin - meanShifted
        val normalizedFixed = centeredValue / norm
        parameter = 0
        while parameter < workspace.parameterCount do
          val centeredDerivative =
            samples.derivative(sample, parameter) -
              workspace.gradientOrigin(parameter) -
              workspace.meanGradient(parameter)
          workspace.a(parameter) += normalizedFixed * centeredDerivative
          workspace.q(parameter) +=
            moving.normalized(sample) * centeredDerivative
          var other = 0
          while other <= parameter do
            val otherDerivative =
              samples.derivative(sample, other) -
                workspace.meanGradient(other)
            workspace.centeredSgg(
              PackedSymmetric.index(other, parameter)
            ) += otherDerivative * centeredDerivative
            other += 1
          parameter += 1
        sample += 1
      parameter = 0
      while parameter < workspace.parameterCount do
        workspace.referenceJtu(parameter) =
          (workspace.q(parameter) - correlation * workspace.a(parameter)) /
            norm
        parameter += 1
      // This is the true explicit-J fallback. Reusing the compressed
      // Scc-aa' subtraction here would reproduce the cancellation that sent
      // us to the diagnostic path in the first place.
      clear(workspace.referenceJtj)
      sample = 0
      while sample < samples.sampleCount do
        val centeredValue =
          samples.value(sample) - workspace.scalarOrigin - meanShifted
        val normalizedFixed = centeredValue / norm
        parameter = 0
        while parameter < workspace.parameterCount do
          val centeredDerivative =
            samples.derivative(sample, parameter) -
              workspace.gradientOrigin(parameter) -
              workspace.meanGradient(parameter)
          workspace.q(parameter) =
            (centeredDerivative -
              normalizedFixed * workspace.a(parameter)) /
              norm
          var other = 0
          while other <= parameter do
            workspace.referenceJtj(
              PackedSymmetric.index(other, parameter)
            ) += workspace.q(other) * workspace.q(parameter)
            other += 1
          parameter += 1
        sample += 1
      val finiteFailure =
        firstNonFiniteCenteredStatistic(
          correlation,
          workspace.referenceJtu,
          workspace.referenceJtj
        )
      if finiteFailure != null then
        workspace.diagnosticFailureCount += 1L
        finiteFailure
      else
        val scale = maximumAbsolute(workspace.referenceJtj)
        val minimumDiagonal = minimumDiagonalValue(
          workspace.referenceJtj,
          workspace.parameterCount
        )
        val tolerance =
          CheckAbsoluteTolerance + CheckRelativeTolerance * scale
        if minimumDiagonal < -tolerance then
          workspace.diagnosticFailureCount += 1L
          PatchLinearizationError.MaterialIndefiniteness(
            minimumDiagonal,
            scale
          )
        else
          // The streamed expression is what selected this path, so agreement
          // with that cancellation-prone subtraction is not an admission
          // criterion. The directly constructed normalized Jacobian and its
          // Gram matrix are the authoritative result here.
          copy(workspace.referenceJtu, workspace.result.jtu)
          copy(workspace.referenceJtj, workspace.result.jtjUpper)
          workspace.result.currentCorrelation = correlation
          workspace.result.currentFixedContrastNorm = norm
          workspace.result.currentMode = PatchStatisticsMode.CenteredDiagnostic
          workspace.diagnosticAcceptedCount += 1L
          null

  private def firstNonFiniteCenteredStatistic(
      correlation: Double,
      direction: Array[Double],
      curvature: Array[Double]
  ): PatchLinearizationError | Null =
    if !correlation.isFinite then
      PatchLinearizationError.NonFiniteCenteredStatistic(
        "correlation",
        0,
        correlation
      )
    else
      var index = 0
      while index < direction.length do
        val value = direction(index)
        if !value.isFinite then
          return PatchLinearizationError.NonFiniteCenteredStatistic(
            "direction",
            index,
            value
          )
        index += 1
      index = 0
      while index < curvature.length do
        val value = curvature(index)
        if !value.isFinite then
          return PatchLinearizationError.NonFiniteCenteredStatistic(
            "curvature",
            index,
            value
          )
        index += 1
      null

  private def setInvalid(
      result: PatchLinearizationResult
  ): Unit =
    clear(result.jtu)
    clear(result.jtjUpper)
    result.currentCorrelation = 0.0
    result.currentFixedContrastNorm = 0.0
    result.currentMode = PatchStatisticsMode.InvalidContrast

  private def reset(workspace: PatchLinearizationWorkspace): Unit =
    clear(workspace.sg)
    clear(workspace.shg)
    clear(workspace.sug)
    clear(workspace.sgg)
    clear(workspace.a)
    clear(workspace.result.jtu)
    clear(workspace.result.jtjUpper)

  private def clear(values: Array[Double]): Unit =
    java.util.Arrays.fill(values, 0.0)

  private def copy(source: Array[Double], destination: Array[Double]): Unit =
    java.lang.System.arraycopy(source, 0, destination, 0, source.length)

  private def maximumAbsolute(values: Array[Double]): Double =
    var result = 0.0
    var index = 0
    while index < values.length do
      result = math.max(result, math.abs(values(index)))
      index += 1
    result

  private def minimumDiagonalValue(
      packed: Array[Double],
      dimension: Int
  ): Double =
    var result = Double.PositiveInfinity
    var index = 0
    while index < dimension do
      result = math.min(
        result,
        packed(PackedSymmetric.index(index, index))
      )
      index += 1
    result
