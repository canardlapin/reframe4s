package reframe4s.motion

import ravel.AnyRank
import ravel.Rank
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import reframe4s.lie.Rigid3
import reframe4s.multiscale.GridTower
import reframe4s.multiscale.ScaleSchedule
import reframe4s.multiscale.ScalarPyramid3
import reframe4s.multiscale.ScalarPyramidWorkspace

final class MultiscaleRigidSeriesWorkspace private (
    private[motion] val estimator: MultiscaleRigidEstimatorWorkspace
):
  private var active = false

  private[motion] def acquire(): Boolean =
    if active then false
    else
      active = true
      true

  private[motion] def release(): Unit =
    active = false

object MultiscaleRigidSeriesWorkspace:
  def create(using Dimension[D3]): MultiscaleRigidSeriesWorkspace =
    new MultiscaleRigidSeriesWorkspace(
      MultiscaleRigidEstimatorWorkspace.create
    )

final class MultiscaleRigidSeriesEstimate[F <: Frame[D3]] private (
    val poses: PoseSeries[F, F],
    val frameEstimates: Vector[
      MultiscaleRigidPairEstimate[F, F]
    ],
    val referenceIndex: Int
):
  def size: Int = poses.size

  def frameAt(
      index: Int
  ): Either[MotionError, MultiscaleRigidPairEstimate[F, F]] =
    frameEstimates
      .lift(index)
      .toRight(MotionError.SampleIndexOutOfBounds(index, size))

private object MultiscaleRigidSeriesEstimate:
  def create[F <: Frame[D3]](
      poses: PoseSeries[F, F],
      frameEstimates: Vector[
        MultiscaleRigidPairEstimate[F, F]
      ],
      referenceIndex: Int
  ): Either[MotionError, MultiscaleRigidSeriesEstimate[F]] =
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
        new MultiscaleRigidSeriesEstimate(
          poses,
          frameEstimates,
          referenceIndex
        )
      )

/**
 * Reference-anchored outward series fitting over filtered grid towers.
 */
final class CompiledMultiscaleRigidSeriesEstimator[
    F <: Frame[D3],
    R <: AnyRank
] private (
    val samples: TimedScalarSamples[F, R],
    val referenceIndex: Int,
    val schedule: ScaleSchedule[D3, RigidOptimizerControl],
    private val estimators: Vector[
      CompiledMultiscaleRigidPairEstimator[
        F,
        F,
        Rank[3],
        Rank[3]
      ]
    ]
)(using Dimension[D3]):
  def size: Int = estimators.size

  def newWorkspace(): MultiscaleRigidSeriesWorkspace =
    MultiscaleRigidSeriesWorkspace.create

  def run(
      workspace: MultiscaleRigidSeriesWorkspace
  ): Either[MotionError, MultiscaleRigidSeriesEstimate[F]] =
    if !workspace.acquire() then
      Left(MotionError.MultiscaleSeriesWorkspaceInUse)
    else
      try execute(workspace)
      finally workspace.release()

  private def execute(
      workspace: MultiscaleRigidSeriesWorkspace
  ): Either[MotionError, MultiscaleRigidSeriesEstimate[F]] =
    val identity =
      RigidPose.fromMovingToFixed(
        Rigid3.identity(samples.image.frame)
      )
    estimateAt(referenceIndex, identity, workspace).flatMap {
      referenceEstimate =>
        val before =
          Vector.newBuilder[MultiscaleRigidPairEstimate[F, F]]
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
            val after =
              Vector.newBuilder[MultiscaleRigidPairEstimate[F, F]]
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
                val estimates =
                  before.result().reverse ++
                    Vector(referenceEstimate) ++
                    after.result()
                for
                  poses <- PoseSeries.create(
                    samples.times,
                    estimates.map(_.pose)
                  )
                  result <- MultiscaleRigidSeriesEstimate.create(
                    poses,
                    estimates,
                    referenceIndex
                  )
                yield result
    }

  private def estimateAt(
      index: Int,
      initial: RigidPose[F, F],
      workspace: MultiscaleRigidSeriesWorkspace
  ): Either[MotionError, MultiscaleRigidPairEstimate[F, F]] =
    val estimated =
      if index == referenceIndex then
        estimators(index).measureAt(
          initial,
          workspace.estimator
        )
      else
        estimators(index).runFrom(
          initial,
          workspace.estimator
        )
    estimated.left.map(error =>
      MotionError.FrameEstimationFailed(index, error)
    )

object CompiledMultiscaleRigidSeriesEstimator:
  def compile[
      F <: Frame[D3],
      R <: AnyRank
  ](
      samples: TimedScalarSamples[F, R],
      referenceIndex: Int,
      schedule: ScaleSchedule[D3, RigidOptimizerControl],
      pyramidWorkspace: ScalarPyramidWorkspace
  )(using Dimension[D3]): Either[
    MotionError,
    CompiledMultiscaleRigidSeriesEstimator[F, R]
  ] =
    if referenceIndex < 0 || referenceIndex >= samples.times.size then
      Left(
        MotionError.InvalidReferenceIndex(
          referenceIndex,
          samples.times.size
        )
      )
    else
      samples.volumeAt(referenceIndex).flatMap(reference =>
        compileAgainst(
          samples,
          referenceIndex,
          reference,
          schedule,
          pyramidWorkspace
        )
      )

  def compileAgainst[
      F <: Frame[D3],
      R <: AnyRank
  ](
      samples: TimedScalarSamples[F, R],
      referenceIndex: Int,
      template: MotionScalarImage[F, D3, Rank[3]],
      schedule: ScaleSchedule[D3, RigidOptimizerControl],
      pyramidWorkspace: ScalarPyramidWorkspace
  )(using Dimension[D3]): Either[
    MotionError,
    CompiledMultiscaleRigidSeriesEstimator[F, R]
  ] =
    if referenceIndex < 0 || referenceIndex >= samples.times.size then
      Left(
        MotionError.InvalidReferenceIndex(
          referenceIndex,
          samples.times.size
        )
      )
    else
      for
        fixedTower <- GridTower
          .build(template.grid, schedule)
          .left
          .map(MotionError.Multiscale.apply)
        fixedPyramid <- ScalarPyramid3
          .build(template, fixedTower, pyramidWorkspace)
          .left
          .map(MotionError.Multiscale.apply)
        estimators <- compileFrames(
          samples,
          template,
          fixedPyramid,
          schedule,
          pyramidWorkspace
        )
      yield
        new CompiledMultiscaleRigidSeriesEstimator(
          samples,
          referenceIndex,
          schedule,
          estimators
        )

  private def compileFrames[
      F <: Frame[D3],
      R <: AnyRank
  ](
      samples: TimedScalarSamples[F, R],
      reference: MotionScalarImage[F, D3, Rank[3]],
      fixedPyramid: ScalarPyramid3[F, RigidOptimizerControl],
      schedule: ScaleSchedule[D3, RigidOptimizerControl],
      pyramidWorkspace: ScalarPyramidWorkspace
  )(using Dimension[D3]): Either[
    MotionError,
    Vector[
      CompiledMultiscaleRigidPairEstimator[
        F,
        F,
        Rank[3],
        Rank[3]
      ]
    ]
  ] =
    val result =
      Vector.newBuilder[
        CompiledMultiscaleRigidPairEstimator[
          F,
          F,
          Rank[3],
          Rank[3]
        ]
      ]
    var index = 0
    var failure = Option.empty[MotionError]
    while index < samples.times.size && failure.isEmpty do
      val compiled =
        for
          moving <- samples.volumeAt(index)
          movingTower <- GridTower
            .build(moving.grid, schedule)
            .left
            .map(MotionError.Multiscale.apply)
          movingPyramid <- ScalarPyramid3
            .build(moving, movingTower, pyramidWorkspace)
            .left
            .map(MotionError.Multiscale.apply)
          estimator <-
            CompiledMultiscaleRigidPairEstimator.fromPyramids(
              moving,
              reference,
              schedule,
              movingPyramid,
              fixedPyramid
            )
        yield estimator
      compiled match
        case Left(error) =>
          failure =
            Some(MotionError.FrameEstimationFailed(index, error))
        case Right(estimator) =>
          result += estimator
      index += 1
    failure.toLeft(result.result())
