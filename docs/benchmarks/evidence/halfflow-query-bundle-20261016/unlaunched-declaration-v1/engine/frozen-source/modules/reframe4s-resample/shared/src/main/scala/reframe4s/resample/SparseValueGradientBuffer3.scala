package reframe4s.resample

/** Snapshot of batch work counters. Reading it is outside the hot loop. */
final case class SparseSamplingCounters(
    requestedSamples: Long,
    fullSupportSamples: Long,
    rejectedSamples: Long,
    sourceVoxelReads: Long
)

/**
 * Caller-owned primitive buffers for repeated sparse fused sampling.
 * Preparation allocates once; sampling overwrites only the requested prefix.
 */
final class SparseValueGradientBuffer3 private (
    val capacity: Int,
    val values: Array[Double],
    val gradientX: Array[Double],
    val gradientY: Array[Double],
    val gradientZ: Array[Double],
    val fullSupport: Array[Boolean],
    private[resample] val sample: ScalarValueGradient3
):
  private var requested = 0L
  private var accepted = 0L
  private var rejected = 0L

  def counters: SparseSamplingCounters =
    SparseSamplingCounters(
      requested,
      accepted,
      rejected,
      accepted * 8L
    )

  def resetCounters(): Unit =
    requested = 0L
    accepted = 0L
    rejected = 0L

  private[resample] def begin(count: Int): Unit =
    requested += count.toLong

  private[resample] def record(index: Int, valid: Boolean): Unit =
    fullSupport(index) = valid
    if valid then
      values(index) = sample.value
      gradientX(index) = sample.gradientX
      gradientY(index) = sample.gradientY
      gradientZ(index) = sample.gradientZ
      accepted += 1L
    else
      values(index) = 0.0
      gradientX(index) = 0.0
      gradientY(index) = 0.0
      gradientZ(index) = 0.0
      rejected += 1L

object SparseValueGradientBuffer3:
  def create(
      capacity: Int
  ): Either[ResamplingError, SparseValueGradientBuffer3] =
    if capacity < 0 then
      Left(ResamplingError.InvalidSparseSamplingCapacity(capacity))
    else
      Right(
        new SparseValueGradientBuffer3(
          capacity,
          new Array[Double](capacity),
          new Array[Double](capacity),
          new Array[Double](capacity),
          new Array[Double](capacity),
          new Array[Boolean](capacity),
          ScalarValueGradient3.create
        )
      )
