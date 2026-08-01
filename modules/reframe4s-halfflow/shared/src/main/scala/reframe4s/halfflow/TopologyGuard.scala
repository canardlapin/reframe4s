package reframe4s.halfflow

import reframe4s.halfflow.internal.*

final case class GuardConfig(
    minimumJacobian: Double = 0.05,
    maximumInverseErrorMm: Double = 0.25,
    maximumInverseErrorVox: Double = 0.10,
    minimumValidFraction: Double = 0.95,
    minimumInverseValidFraction: Option[Double] = None
):
  require(minimumJacobian.isFinite && minimumJacobian > 0.0)
  require(maximumInverseErrorMm.isFinite && maximumInverseErrorMm >= 0.0)
  require(maximumInverseErrorVox.isFinite && maximumInverseErrorVox >= 0.0)
  require(minimumValidFraction.isFinite && minimumValidFraction > 0.0 && minimumValidFraction <= 1.0)
  require(
    minimumInverseValidFraction.forall(value => value.isFinite && value > 0.0 && value <= 1.0)
  )

  val effectiveMinimumInverseValidFraction: Double =
    minimumInverseValidFraction.getOrElse(minimumValidFraction)

final case class JacobianQuantiles(
    minimum: Option[Double],
    p01: Option[Double],
    p05: Option[Double],
    p50: Option[Double],
    p95: Option[Double],
    p99: Option[Double]
)

final case class MapGuardSummary(
    finite: Boolean,
    evaluated: Int,
    eligible: Int,
    validFraction: Double,
    nonPositive: Int,
    belowFloor: Int,
    quantiles: JacobianQuantiles
)

final case class PairGuardReport(
    safe: Boolean,
    forward: MapGuardSummary,
    backward: MapGuardSummary,
    inverse: InversePairSummary,
    reasons: Vector[String]
)

final case class MidpointGuardReport(
    safe: Boolean,
    fixed: PairGuardReport,
    moving: PairGuardReport
)

final class TopologyGuardWorkspace[A, B] private[halfflow] (
    val from: Frame[A],
    val to: Frame[B],
    private[halfflow] val forward: MapGuardBuffers,
    private[halfflow] val backward: MapGuardBuffers,
    private[halfflow] val forwardInverse: InverseErrorReduction,
    private[halfflow] val backwardInverse: InverseErrorReduction
):
  val ownedScalarBuffers: Int = 2
  val ownedValidityBuffers: Int = 1

private[halfflow] final class TopologyGuardScratch private (
    private[halfflow] val determinants: Array[Double],
    private[halfflow] val valid: Array[Boolean],
    private[halfflow] val sorted: Array[Double]
)

private[halfflow] object TopologyGuardScratch:
  def forGrids(grids: Vector[GridSpec]): TopologyGuardScratch =
    require(grids.nonEmpty, "topology scratch requires at least one grid")
    val capacity = grids.map(_.nVoxels).max
    val eligible = grids.map: grid =>
      math.max(0, grid.shape.x - 2) * math.max(0, grid.shape.y - 2) * math.max(0, grid.shape.z - 2)
    new TopologyGuardScratch(
      PrimitiveBuffers.ofSize[Double](capacity),
      PrimitiveBuffers.ofSize[Boolean](capacity),
      new Array[Double](eligible.max)
    )

object TopologyGuardWorkspace:
  def apply[A, B](pair: InversePair[A, B]): TopologyGuardWorkspace[A, B] =
    val scratch = TopologyGuardScratch.forGrids(Vector(pair.forward.from.grid, pair.backward.from.grid))
    using(pair, scratch)

  private[halfflow] def using[A, B](
      pair: InversePair[A, B],
      scratch: TopologyGuardScratch
  ): TopologyGuardWorkspace[A, B] =
    new TopologyGuardWorkspace(
      pair.forward.from,
      pair.forward.to,
      buffers(pair.forward.from.grid, scratch),
      buffers(pair.backward.from.grid, scratch),
      InverseErrorReduction(),
      InverseErrorReduction()
    )

  private def buffers(grid: GridSpec, scratch: TopologyGuardScratch): MapGuardBuffers =
    val eligible =
      math.max(0, grid.shape.x - 2) * math.max(0, grid.shape.y - 2) * math.max(0, grid.shape.z - 2)
    require(scratch.determinants.length >= grid.nVoxels, "topology determinant scratch is too small")
    require(scratch.valid.length >= grid.nVoxels, "topology validity scratch is too small")
    require(scratch.sorted.length >= eligible, "topology quantile scratch is too small")
    MapGuardBuffers(
      scratch.determinants,
      scratch.valid,
      scratch.sorted,
      DenseFieldSampler(grid),
      JacobianReduction()
    )

private[halfflow] final case class MapGuardBuffers(
    determinants: Array[Double],
    valid: Array[Boolean],
    sorted: Array[Double],
    sampler: DenseFieldSampler,
    reduction: JacobianReduction
)

object TopologyGuard:
  def evaluate[A, B](
      pair: InversePair[A, B],
      config: GuardConfig = GuardConfig()
  ): PairGuardReport =
    evaluateWith(pair, TopologyGuardWorkspace(pair), config).fold(
      error => throw new IllegalArgumentException(error.message),
      identity
    )

  /** Evaluates a pair without allocating full-volume guard temporaries. */
  def evaluateWith[A, B](
      pair: InversePair[A, B],
      workspace: TopologyGuardWorkspace[A, B],
      config: GuardConfig = GuardConfig()
  ): Either[RegistrationError, PairGuardReport] =
    if pair.forward.from.domain != workspace.from.domain then
      Left(RegistrationError.FrameMismatch("topology workspace source", pair.forward.from.domain, workspace.from.domain))
    else if pair.forward.to.domain != workspace.to.domain then
      Left(RegistrationError.FrameMismatch("topology workspace target", pair.forward.to.domain, workspace.to.domain))
    else if pair.forward.from.grid != workspace.from.grid || pair.forward.to.grid != workspace.to.grid then
      Left(RegistrationError.GridMismatch("topology workspace"))
    else Right(evaluateUnsafe(pair, workspace, config))

  private def evaluateUnsafe[A, B](
      pair: InversePair[A, B],
      workspace: TopologyGuardWorkspace[A, B],
      config: GuardConfig
  ): PairGuardReport =
    val forward = mapSummary(pair.forward, config.minimumJacobian, workspace.forward)
    val backward = mapSummary(pair.backward, config.minimumJacobian, workspace.backward)
    HalfFlowKernels.inversePairErrorInto(
      pair.forward.sourceCoordinates,
      pair.backward.sourceCoordinates,
      workspace.forward.sampler,
      workspace.backward.sampler,
      pair.forward.validity,
      pair.backward.validity,
      workspace.forwardInverse,
      workspace.backwardInverse
    )
    val inverse = InversePairSummary(
      workspace.forwardInverse.snapshot,
      workspace.backwardInverse.snapshot
    )
    val reasons = Vector.newBuilder[String]
    checkMap("forward", forward, config, reasons)
    checkMap("backward", backward, config, reasons)
    checkInverse("forward-backward", inverse.forwardThenBackward, config, reasons)
    checkInverse("backward-forward", inverse.backwardThenForward, config, reasons)
    val result = reasons.result()
    PairGuardReport(result.isEmpty, forward, backward, inverse, result)

  def evaluateMidpoint[W, F, M](
      midpoint: Midpoint[W, F, M],
      config: GuardConfig = GuardConfig()
  ): MidpointGuardReport =
    val fixedPair = midpoint.fixed.dense.fold(
      error => throw new IllegalArgumentException(error.message),
      identity
    )
    val movingPair = midpoint.moving.dense.fold(
      error => throw new IllegalArgumentException(error.message),
      identity
    )
    val fixed = evaluate(fixedPair, config)
    val moving = evaluate(movingPair, config)
    MidpointGuardReport(fixed.safe && moving.safe, fixed, moving)

  private def mapSummary[A, B](
      pull: DensePull[A, B],
      floor: Double,
      buffers: MapGuardBuffers
  ): MapGuardSummary =
    val grid = pull.from.grid
    HalfFlowKernels.jacobianDeterminantsReduceInto(
      pull.sourceCoordinates,
      buffers.determinants,
      buffers.valid,
      buffers.sampler,
      pull.validity,
      buffers.reduction
    )
    var finite = true
    var belowFloor = 0
    var source = 0
    var destination = 0
    while source < grid.nVoxels do
      if buffers.valid(source) then
        val value = buffers.determinants(source)
        finite = finite && value.isFinite
        if value < floor then belowFloor += 1
        if destination < buffers.sorted.length then
          buffers.sorted(destination) = value
          destination += 1
      source += 1
    var unused = destination
    while unused < buffers.sorted.length do
      buffers.sorted(unused) = Double.PositiveInfinity
      unused += 1
    scala.util.Sorting.quickSort(buffers.sorted)
    val eligible =
      math.max(0, grid.shape.x - 2) * math.max(0, grid.shape.y - 2) * math.max(0, grid.shape.z - 2)
    MapGuardSummary(
      finite && fieldFinite(pull.sourceCoordinates),
      buffers.reduction.evaluated,
      eligible,
      if eligible == 0 then 0.0 else buffers.reduction.evaluated.toDouble / eligible.toDouble,
      buffers.reduction.nonPositive,
      belowFloor,
      JacobianQuantiles(
        percentile(buffers.sorted, destination, 0.0),
        percentile(buffers.sorted, destination, 0.01),
        percentile(buffers.sorted, destination, 0.05),
        percentile(buffers.sorted, destination, 0.50),
        percentile(buffers.sorted, destination, 0.95),
        percentile(buffers.sorted, destination, 0.99)
      )
    )

  private def percentile(sorted: Array[Double], length: Int, probability: Double): Option[Double] =
    if length == 0 then None
    else
      val position = probability * (length - 1).toDouble
      val lower = math.floor(position).toInt
      val upper = math.ceil(position).toInt
      val fraction = position - lower.toDouble
      Some(sorted(lower) + fraction * (sorted(upper) - sorted(lower)))

  private def fieldFinite(field: DenseVectorField): Boolean =
    var finite = true
    var index = 0
    while index < field.grid.nVoxels && finite do
      var component = 0
      while component < 3 && finite do
        finite = field.linearComponent(index, component).isFinite
        component += 1
      index += 1
    finite

  private def checkMap(
      label: String,
      summary: MapGuardSummary,
      config: GuardConfig,
      reasons: scala.collection.mutable.Builder[String, Vector[String]]
  ): Unit =
    if !summary.finite then reasons += s"$label map contains non-finite values"
    if summary.evaluated == 0 then reasons += s"$label map has no Jacobian support"
    if summary.validFraction < config.minimumValidFraction then reasons += s"$label Jacobian coverage is too small"
    if summary.nonPositive > 0 then reasons += s"$label map has non-positive Jacobians"
    if summary.belowFloor > 0 then reasons += s"$label map crosses the Jacobian floor"

  private def checkInverse(
      label: String,
      summary: InverseErrorSummary,
      config: GuardConfig,
      reasons: scala.collection.mutable.Builder[String, Vector[String]]
  ): Unit =
    val total = summary.evaluated + summary.skipped
    val fraction = if total == 0 then 0.0 else summary.evaluated.toDouble / total.toDouble
    if fraction < config.effectiveMinimumInverseValidFraction then reasons += s"$label inverse coverage is too small"
    if summary.maximumMm.forall(value => !value.isFinite || value > config.maximumInverseErrorMm) then
      reasons += s"$label inverse error in millimetres exceeds the limit"
    if summary.maximumVox.forall(value => !value.isFinite || value > config.maximumInverseErrorVox) then
      reasons += s"$label inverse error in voxels exceeds the limit"
