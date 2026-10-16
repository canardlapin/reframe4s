package reframe4s.halfflow

import reframe4s.halfflow.internal.*

enum HalfFlowCcCheckpointKind:
  case LevelStart, Accepted, Rejected, LevelEnd

enum HalfFlowCcCaptureStatus:
  case NotRequested, Unavailable, BudgetExhausted, Captured

/** Scalar summaries retain zero and nonfinite counts rather than dropping them. */
final case class HalfFlowCcWeightSummary(
    count: Int,
    nonFinite: Int,
    positive: Int,
    sum: Double,
    minimum: Double,
    maximum: Double
)

object HalfFlowCcWeightSummary:
  private[halfflow] def from(values: Array[Double]): HalfFlowCcWeightSummary =
    var nonFinite = 0
    var positive = 0
    var sum = 0.0
    var minimum = Double.PositiveInfinity
    var maximum = Double.NegativeInfinity
    var index = 0
    while index < values.length do
      val value = values(index)
      if value.isFinite then
        sum += value
        minimum = math.min(minimum, value)
        maximum = math.max(maximum, value)
        if value > 0.0 then positive += 1
      else nonFinite += 1
      index += 1
    HalfFlowCcWeightSummary(values.length, nonFinite, positive, sum,
      if minimum.isFinite then minimum else 0.0,
      if maximum.isFinite then maximum else 0.0)

/** The trial uses `before`; an accepted update rebuilds weights for `after`. */
final case class HalfFlowCcObjectiveAudit(
    loss: Double,
    activeFraction: Double,
    edgeActiveFraction: Double,
    support: HalfFlowCcWeightSummary,
    reliability: Option[HalfFlowCcWeightSummary],
    stabilization: Option[HalfFlowCcWeightSummary],
    neighborhood: Option[NeighborhoodCcDiagnostics]
)

final case class HalfFlowCcCheckpointInfo(
    kind: HalfFlowCcCheckpointKind,
    levelIndex: Int,
    shrink: Int,
    acceptedSteps: Int,
    attempts: Int,
    before: HalfFlowCcObjectiveAudit,
    after: HalfFlowCcObjectiveAudit,
    control: HalfFlowCcControlState,
    trial: Option[HalfFlowCcAttemptDiagnostics],
    termination: Option[HalfFlowCcTermination],
    stateAvailable: Boolean
)

/** A rejected snapshot is the attempted candidate, never a replacement result.
  * Snapshot coordinate and validity buffers are detached from optimization.
  * Frame and affine values retain their ordinary immutable semantic ownership.
  */
final case class HalfFlowCcCheckpoint[W, F, M](
    info: HalfFlowCcCheckpointInfo,
    captureStatus: HalfFlowCcCaptureStatus,
    state: Option[ForwardMidpoint[W, F, M]]
)

trait HalfFlowCcObserver[W, F, M]:
  def wantsState(info: HalfFlowCcCheckpointInfo): Boolean
  def observe(checkpoint: HalfFlowCcCheckpoint[W, F, M]): Unit

/** Explicit diagnostic mode. Observer exceptions propagate; they are not
  * converted into successful optimizer results. The ordinary optimize method
  * performs no audit reductions or state copying.
  */
final case class HalfFlowCcObservation[W, F, M](
    observer: HalfFlowCcObserver[W, F, M],
    maximumSnapshots: Int
):
  require(maximumSnapshots >= 0, "checkpoint snapshot budget must be nonnegative")

private[halfflow] final class HalfFlowCcObservationRun[W, F, M](
    config: HalfFlowCcObservation[W, F, M]
):
  private var captured = 0

  def emit(info: HalfFlowCcCheckpointInfo, state: Option[ForwardMidpoint[W, F, M]]): Unit =
    val requested = config.observer.wantsState(info)
    val status =
      if !requested then HalfFlowCcCaptureStatus.NotRequested
      else if state.isEmpty then HalfFlowCcCaptureStatus.Unavailable
      else if captured >= config.maximumSnapshots then HalfFlowCcCaptureStatus.BudgetExhausted
      else HalfFlowCcCaptureStatus.Captured
    val snapshot =
      if status == HalfFlowCcCaptureStatus.Captured then
        captured += 1
        state.map(detach)
      else None
    config.observer.observe(HalfFlowCcCheckpoint(info, status, snapshot))

  private def detach(state: ForwardMidpoint[W, F, M]): ForwardMidpoint[W, F, M] =
    def arm[E](value: ForwardMidpointArm[W, E]): ForwardMidpointArm[W, E] =
      val original = value.residual
      val coordinates = DenseVectorField.fromLegacyPlanar(original.from.grid,
        original.sourceCoordinates.copyLegacyPlanar, DenseVectorFieldKind.SourceCoordinates)
      val validity = original.validity match
        case FieldValidity.All => FieldValidity.All
        case FieldValidity.Mask(values) =>
          FieldValidity.copyMask(Array.tabulate(original.from.grid.nVoxels)(values(_)))
      ForwardMidpointArm.unsafe(DensePull.unsafe(original.from, original.to, coordinates, validity), value.affine)
    ForwardMidpoint.make(arm(state.fixed), arm(state.moving))
      .fold(error => throw new IllegalStateException(error.message), identity)
