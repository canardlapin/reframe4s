package reframe4s.motion

import ravel.AnyRank
import ravel.Rank
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import reframe4s.multiscale.GridTower
import reframe4s.multiscale.ScaleSchedule
import reframe4s.multiscale.ScalarPyramid3
import reframe4s.multiscale.ScalarPyramidWorkspace

/**
 * Explicit mutable state for one multiscale rigid fit.
 */
final class MultiscaleRigidEstimatorWorkspace private (
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

object MultiscaleRigidEstimatorWorkspace:
  def create(using Dimension[D3]): MultiscaleRigidEstimatorWorkspace =
    new MultiscaleRigidEstimatorWorkspace(
      RigidEstimatorWorkspace.create
    )

/**
 * Coarse-to-fine fit with evidence retained for every filtered level.
 */
final class MultiscaleRigidPairEstimate[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    val pose: RigidPose[Moving, Fixed],
    val levelEstimates: Vector[RigidPairEstimate[Moving, Fixed]]
)

private object MultiscaleRigidPairEstimate:
  def create[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      levelEstimates: Vector[RigidPairEstimate[Moving, Fixed]]
  ): Either[MotionError, MultiscaleRigidPairEstimate[Moving, Fixed]] =
    levelEstimates.lastOption match
      case None =>
        Left(
          MotionError.Multiscale(
            reframe4s.multiscale.MultiscaleError.EmptySchedule
          )
        )
      case Some(last) =>
        Right(
          new MultiscaleRigidPairEstimate(
            last.pose,
            levelEstimates
          )
        )

/**
 * Immutable estimator over physically filtered grid towers.
 *
 * Pose state already lives in physical frame coordinates, so continuation
 * requires no parameter rescaling between grids.
 */
final class CompiledMultiscaleRigidPairEstimator[
    Moving <: Frame[D3],
    Fixed <: Frame[D3],
    RMoving <: AnyRank,
    RFixed <: AnyRank
] private (
    val moving: MotionScalarImage[Moving, D3, RMoving],
    val fixed: MotionScalarImage[Fixed, D3, RFixed],
    val schedule: ScaleSchedule[D3, RigidOptimizerControl],
    val movingPyramid: ScalarPyramid3[Moving, RigidOptimizerControl],
    val fixedPyramid: ScalarPyramid3[Fixed, RigidOptimizerControl],
    private val levels: Vector[
      CompiledRigidPairEstimator[
        Moving,
        Fixed,
        Rank[3],
        Rank[3]
      ]
    ]
)(using Dimension[D3]):
  def size: Int = levels.size

  def newWorkspace(): MultiscaleRigidEstimatorWorkspace =
    MultiscaleRigidEstimatorWorkspace.create

  def run(
      workspace: MultiscaleRigidEstimatorWorkspace
  ): Either[
    MotionError,
    MultiscaleRigidPairEstimate[Moving, Fixed]
  ] =
    if !workspace.acquire() then
      Left(MotionError.MultiscaleEstimatorWorkspaceInUse)
    else
      try
        levels.headOption match
          case None =>
            Left(
              MotionError.Multiscale(
                reframe4s.multiscale.MultiscaleError.EmptySchedule
              )
            )
          case Some(coarsest) =>
            coarsest
              .run(workspace.pair)
              .left
              .map(error =>
                MotionError.ScaleEstimationFailed(0, error)
              )
              .flatMap(first =>
                continue(first, workspace)
              )
      finally workspace.release()

  def runFrom(
      initial: RigidPose[Moving, Fixed],
      workspace: MultiscaleRigidEstimatorWorkspace
  ): Either[
    MotionError,
    MultiscaleRigidPairEstimate[Moving, Fixed]
  ] =
    if !workspace.acquire() then
      Left(MotionError.MultiscaleEstimatorWorkspaceInUse)
    else
      try
        levels.headOption match
          case None =>
            Left(
              MotionError.Multiscale(
                reframe4s.multiscale.MultiscaleError.EmptySchedule
              )
            )
          case Some(coarsest) =>
            coarsest
              .runFrom(initial, workspace.pair)
              .left
              .map(error =>
                MotionError.ScaleEstimationFailed(0, error)
              )
              .flatMap(first =>
                continue(first, workspace)
              )
      finally workspace.release()

  def measureAt(
      pose: RigidPose[Moving, Fixed],
      workspace: MultiscaleRigidEstimatorWorkspace
  ): Either[
    MotionError,
    MultiscaleRigidPairEstimate[Moving, Fixed]
  ] =
    if !workspace.acquire() then
      Left(MotionError.MultiscaleEstimatorWorkspaceInUse)
    else
      try
        val result =
          Vector.newBuilder[RigidPairEstimate[Moving, Fixed]]
        var level = 0
        var failure = Option.empty[MotionError]
        while level < levels.size && failure.isEmpty do
          levels(level).measureAt(pose, workspace.pair) match
            case Left(error) =>
              failure =
                Some(MotionError.ScaleEstimationFailed(level, error))
            case Right(estimate) =>
              result += estimate
          level += 1
        failure match
          case Some(error) => Left(error)
          case None =>
            MultiscaleRigidPairEstimate.create(result.result())
      finally workspace.release()

  private def continue(
      first: RigidPairEstimate[Moving, Fixed],
      workspace: MultiscaleRigidEstimatorWorkspace
  ): Either[
    MotionError,
    MultiscaleRigidPairEstimate[Moving, Fixed]
  ] =
    val result =
      Vector.newBuilder[RigidPairEstimate[Moving, Fixed]]
    result += first
    var current = first
    var level = 1
    var failure = Option.empty[MotionError]
    while level < levels.size && failure.isEmpty do
      levels(level).runFrom(current.pose, workspace.pair) match
        case Left(error) =>
          failure =
            Some(MotionError.ScaleEstimationFailed(level, error))
        case Right(estimate) =>
          result += estimate
          current = estimate
      level += 1
    failure match
      case Some(error) => Left(error)
      case None =>
        MultiscaleRigidPairEstimate.create(result.result())

object CompiledMultiscaleRigidPairEstimator:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      RMoving <: AnyRank,
      RFixed <: AnyRank
  ](
      moving: MotionScalarImage[Moving, D3, RMoving],
      fixed: MotionScalarImage[Fixed, D3, RFixed],
      schedule: ScaleSchedule[D3, RigidOptimizerControl],
      pyramidWorkspace: ScalarPyramidWorkspace
  )(using Dimension[D3]): Either[
    MotionError,
    CompiledMultiscaleRigidPairEstimator[
      Moving,
      Fixed,
      RMoving,
      RFixed
    ]
  ] =
    for
      movingTower <- GridTower
        .build(moving.grid, schedule)
        .left
        .map(MotionError.Multiscale.apply)
      fixedTower <- GridTower
        .build(fixed.grid, schedule)
        .left
        .map(MotionError.Multiscale.apply)
      movingPyramid <- ScalarPyramid3
        .build(moving, movingTower, pyramidWorkspace)
        .left
        .map(MotionError.Multiscale.apply)
      fixedPyramid <- ScalarPyramid3
        .build(fixed, fixedTower, pyramidWorkspace)
        .left
        .map(MotionError.Multiscale.apply)
      compiled <- fromPyramids(
        moving,
        fixed,
        schedule,
        movingPyramid,
        fixedPyramid
      )
    yield compiled

  private[motion] def fromPyramids[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      RMoving <: AnyRank,
      RFixed <: AnyRank
  ](
      moving: MotionScalarImage[Moving, D3, RMoving],
      fixed: MotionScalarImage[Fixed, D3, RFixed],
      schedule: ScaleSchedule[D3, RigidOptimizerControl],
      movingPyramid: ScalarPyramid3[Moving, RigidOptimizerControl],
      fixedPyramid: ScalarPyramid3[Fixed, RigidOptimizerControl]
  )(using Dimension[D3]): Either[
    MotionError,
    CompiledMultiscaleRigidPairEstimator[
      Moving,
      Fixed,
      RMoving,
      RFixed
    ]
  ] =
    compileLevels(movingPyramid, fixedPyramid).map { levels =>
      new CompiledMultiscaleRigidPairEstimator(
        moving,
        fixed,
        schedule,
        movingPyramid,
        fixedPyramid,
        levels
      )
    }

  private[motion] def compileLevels[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      moving: ScalarPyramid3[Moving, RigidOptimizerControl],
      fixed: ScalarPyramid3[Fixed, RigidOptimizerControl]
  )(using Dimension[D3]): Either[
    MotionError,
    Vector[
      CompiledRigidPairEstimator[
        Moving,
        Fixed,
        Rank[3],
        Rank[3]
      ]
    ]
  ] =
    val result =
      Vector.newBuilder[
        CompiledRigidPairEstimator[
          Moving,
          Fixed,
          Rank[3],
          Rank[3]
        ]
      ]
    var level = 0
    var failure = Option.empty[MotionError]
    while level < moving.levels.size && failure.isEmpty do
      CompiledRigidPairEstimator.compile(
        moving.levels(level).image,
        fixed.levels(level).image,
        moving.levels(level).level.configuration
      ) match
        case Left(error) =>
          failure =
            Some(MotionError.ScaleEstimationFailed(level, error))
        case Right(compiled) =>
          result += compiled
      level += 1
    failure.toLeft(result.result())
