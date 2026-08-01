package reframe4s.motion

import ravel.AnyRank
import ravel.Rank
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import reframe4s.lie.Rigid3

/**
 * Explicit mutable state for one rigid-series estimation run.
 *
 * The compiled estimator is immutable and may be shared. A workspace belongs
 * to one run at a time and reuses the pair estimator's resampling workspace
 * across all frames.
 */
final class RigidSeriesWorkspace private (
    private[motion] val pair: RigidEstimatorWorkspace
):
  private var active = false

  private[motion] def acquire(): Boolean =
    if active then false
    else
      active = true
      true

  private[motion] def release(): Unit =
    active = false

object RigidSeriesWorkspace:
  def create(using Dimension[D3]): RigidSeriesWorkspace =
    new RigidSeriesWorkspace(RigidEstimatorWorkspace.create)

/**
 * Result of fitting every volume to one declared reference volume.
 *
 * `poses` is the canonical temporal representation. `frameEstimates` retains
 * the optimizer report and overlap evidence at the corresponding pose index.
 */
final class RigidSeriesEstimate[F <: Frame[D3]] private (
    val poses: PoseSeries[F, F],
    val frameEstimates: Vector[RigidPairEstimate[F, F]],
    val referenceIndex: Int
):
  def size: Int = poses.size

  def frameAt(
      index: Int
  ): Either[MotionError, RigidPairEstimate[F, F]] =
    frameEstimates
      .lift(index)
      .toRight(MotionError.SampleIndexOutOfBounds(index, size))

private object RigidSeriesEstimate:
  def create[F <: Frame[D3]](
      poses: PoseSeries[F, F],
      frameEstimates: Vector[RigidPairEstimate[F, F]],
      referenceIndex: Int
  ): Either[MotionError, RigidSeriesEstimate[F]] =
    if poses.size != frameEstimates.size then
      Left(
        MotionError.DiagnosticCountMismatch(
          poses.size,
          frameEstimates.size
        )
      )
    else if referenceIndex < 0 || referenceIndex >= poses.size then
      Left(MotionError.InvalidReferenceIndex(referenceIndex, poses.size))
    else
      Right(
        new RigidSeriesEstimate(
          poses,
          frameEstimates,
          referenceIndex
        )
      )

/**
 * Immutable reference-anchored rigid estimator for a D3 plus Time image.
 *
 * The reference volume is fitted first at the identity. Frames after the
 * reference are fitted in increasing order and frames before it in decreasing
 * order. Each fit is initialized from its already-fitted temporal neighbor,
 * while every objective is still evaluated against the declared reference.
 */
final class CompiledRigidSeriesEstimator[
    F <: Frame[D3],
    R <: AnyRank
] private (
    val samples: TimedScalarSamples[F, R],
    val referenceIndex: Int,
    val control: RigidOptimizerControl,
    private val estimators: Vector[
      CompiledRigidPairEstimator[F, F, Rank[3], Rank[3]]
    ]
)(using Dimension[D3]):
  def size: Int = estimators.size

  def newWorkspace(): RigidSeriesWorkspace =
    RigidSeriesWorkspace.create

  def run(
      workspace: RigidSeriesWorkspace
  ): Either[MotionError, RigidSeriesEstimate[F]] =
    if !workspace.acquire() then
      Left(MotionError.SeriesEstimatorWorkspaceInUse)
    else
      try execute(workspace)
      finally workspace.release()

  private def execute(
      workspace: RigidSeriesWorkspace
  ): Either[MotionError, RigidSeriesEstimate[F]] =
    val identity =
      RigidPose.fromMovingToFixed(
        Rigid3.identity(samples.image.frame)
      )
    estimateAt(referenceIndex, identity, workspace).flatMap {
      referenceEstimate =>
        val before = Vector.newBuilder[RigidPairEstimate[F, F]]
        var beforeIndex = referenceIndex - 1
        var beforeInitial = referenceEstimate.pose
        var beforeFailure = Option.empty[MotionError]
        while beforeIndex >= 0 && beforeFailure.isEmpty do
          estimateAt(beforeIndex, beforeInitial, workspace) match
            case Left(error) =>
              beforeFailure = Some(error)
            case Right(estimate) =>
              before += estimate
              beforeInitial = estimate.pose
          beforeIndex -= 1

        beforeFailure match
          case Some(error) => Left(error)
          case None =>
            val after = Vector.newBuilder[RigidPairEstimate[F, F]]
            var afterIndex = referenceIndex + 1
            var afterInitial = referenceEstimate.pose
            var afterFailure = Option.empty[MotionError]
            while afterIndex < size && afterFailure.isEmpty do
              estimateAt(afterIndex, afterInitial, workspace) match
                case Left(error) =>
                  afterFailure = Some(error)
                case Right(estimate) =>
                  after += estimate
                  afterInitial = estimate.pose
              afterIndex += 1

            afterFailure match
              case Some(error) => Left(error)
              case None =>
                val frameEstimates =
                  before.result().reverse ++
                    Vector(referenceEstimate) ++
                    after.result()
                for
                  poses <- PoseSeries.create(
                    samples.times,
                    frameEstimates.map(_.pose)
                  )
                  result <- RigidSeriesEstimate.create(
                    poses,
                    frameEstimates,
                    referenceIndex
                  )
                yield result
    }

  private def estimateAt(
      index: Int,
      initial: RigidPose[F, F],
      workspace: RigidSeriesWorkspace
  ): Either[MotionError, RigidPairEstimate[F, F]] =
    val estimated =
      if index == referenceIndex then
        estimators(index).measureAt(initial, workspace.pair)
      else
        estimators(index).runFrom(initial, workspace.pair)
    estimated
      .left
      .map(error => MotionError.FrameEstimationFailed(index, error))

object CompiledRigidSeriesEstimator:
  def compile[
      F <: Frame[D3],
      R <: AnyRank
  ](
      samples: TimedScalarSamples[F, R],
      referenceIndex: Int,
      control: RigidOptimizerControl = RigidOptimizerControl.default
  )(using
      Dimension[D3]
  ): Either[MotionError, CompiledRigidSeriesEstimator[F, R]] =
    if referenceIndex < 0 || referenceIndex >= samples.times.size then
      Left(
        MotionError.InvalidReferenceIndex(
          referenceIndex,
          samples.times.size
        )
      )
    else
      samples.volumeAt(referenceIndex).flatMap { reference =>
        val estimators = Vector.newBuilder[
          CompiledRigidPairEstimator[F, F, Rank[3], Rank[3]]
        ]
        var index = 0
        var failure = Option.empty[MotionError]
        while index < samples.times.size && failure.isEmpty do
          val compiled =
            for
              moving <- samples.volumeAt(index)
              estimator <- CompiledRigidPairEstimator.compile(
                moving,
                reference,
                control
              )
            yield estimator
          compiled match
            case Left(error) =>
              failure =
                Some(MotionError.FrameEstimationFailed(index, error))
            case Right(estimator) =>
              estimators += estimator
          index += 1

        failure match
          case Some(error) => Left(error)
          case None =>
            Right(
              new CompiledRigidSeriesEstimator(
                samples,
                referenceIndex,
                control,
                estimators.result()
              )
            )
      }
