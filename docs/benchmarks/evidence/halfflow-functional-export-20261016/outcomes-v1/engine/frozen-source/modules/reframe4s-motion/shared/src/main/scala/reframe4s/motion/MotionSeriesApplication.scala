package reframe4s.motion

import image4s.BoundaryPolicy
import image4s.Sampled
import image4s.Continuous
import ravel.AnyRank
import ravel.ArrayBuilder
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingPlan
import reframe4s.resample.ResamplingSink
import reframe4s.resample.ResamplingWorkspace

final case class MotionApplicationStructure(
    volumes: Int,
    compiledPlans: Int,
    materializedCoordinateCount: Int
) derives CanEqual

final case class ApplicationSlice(
    axis: GridAxis,
    index: Int
) derives CanEqual

/**
 * Explicit mutable state for one compiled motion-application run.
 */
final class MotionApplicationWorkspace private (
    private[motion] val resampling: ResamplingWorkspace[D3]
):
  private var active = false

  private[motion] def acquire(): Boolean =
    if active then false
    else
      active = true
      true

  private[motion] def release(): Unit =
    active = false

object MotionApplicationWorkspace:
  def create(using Dimension[D3]): MotionApplicationWorkspace =
    new MotionApplicationWorkspace(ResamplingWorkspace.create[D3])

/**
 * Canonical corrected samples and their interpolation-support weights.
 *
 * Both fields are ordinary image4s scalar images. This result introduces no
 * competing image storage representation.
 */
final class MotionApplicationResult[Fixed <: Frame[D3]] private (
    val image: MotionScalarImage[Fixed, D3, Rank[4]],
    val validityWeights: MotionScalarImage[Fixed, D3, Rank[4]]
)

private object MotionApplicationResult:
  def create[Fixed <: Frame[D3]](
      image: MotionScalarImage[Fixed, D3, Rank[4]],
      validityWeights: MotionScalarImage[Fixed, D3, Rank[4]]
  ): MotionApplicationResult[Fixed] =
    new MotionApplicationResult(image, validityWeights)

private sealed trait CompiledVolumeApplication[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
]:
  def planCount: Int

private object CompiledVolumeApplication:
  final class Full[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      val plan: ResamplingPlan[
        Moving,
        Fixed,
        D3,
        Continuous,
        Rank[3]
      ]
  ) extends CompiledVolumeApplication[Moving, Fixed]:
    val planCount: Int = 1

  final class Sliced[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      val axis: GridAxis,
      val plans: Vector[
        ResamplingPlan[
          Moving,
          Fixed,
          D3,
          Continuous,
          Rank[3]
        ]
      ]
  ) extends CompiledVolumeApplication[Moving, Fixed]:
    val planCount: Int = plans.size

/**
 * Immutable, affine-correct application plan for a D3 plus Time image.
 *
 * Volume-aligned schedules compile one production pull-resampling plan per
 * volume. Slice and packet schedules compile one plan per target slice against
 * an affine-correct one-slice subgrid. No interpolation kernel or coordinate
 * collection is duplicated here.
 */
final class CompiledMotionApplication[
    Moving <: Frame[D3],
    Fixed <: Frame[D3],
    R <: AnyRank
] private (
    val samples: TimedScalarSamples[Moving, R],
    val target: Grid[Fixed, D3],
    val trajectory: PoseTrajectory[Moving, Fixed],
    val schedule: AcquisitionSchedule,
    val interpolation: Interpolation[Continuous],
    val boundary: BoundaryPolicy[Double],
    private val volumes: Vector[
      CompiledVolumeApplication[Moving, Fixed]
    ]
)(using Dimension[D3]):
  val structure: MotionApplicationStructure =
    MotionApplicationStructure(
      volumes = volumes.size,
      compiledPlans = volumes.iterator.map(_.planCount).sum,
      materializedCoordinateCount = 0
    )

  def newWorkspace(): MotionApplicationWorkspace =
    MotionApplicationWorkspace.create

  def run(
      workspace: MotionApplicationWorkspace
  ): Either[MotionError, MotionApplicationResult[Fixed]] =
    if !workspace.acquire() then
      Left(MotionError.MotionApplicationWorkspaceInUse)
    else
      try execute(workspace)
      finally workspace.release()

  private def execute(
      workspace: MotionApplicationWorkspace
  ): Either[MotionError, MotionApplicationResult[Fixed]] =
    val nx = target.shape(0)
    val ny = target.shape(1)
    val nz = target.shape(2)
    val nt = volumes.size
    val shape = Shape(nx, ny, nz, nt)
    var failure = Option.empty[MotionError]
    var completedValues = Option.empty[NDArray[Double, Rank[4]]]
    val validity =
      NDArray.build[Double, Rank[4]](shape) { validityBuilder =>
        val values =
          NDArray.build[Double, Rank[4]](shape) { valueBuilder =>
            var volume = 0
            while volume < nt && failure.isEmpty do
              writeVolume(
                volume,
                volumes(volume),
                valueBuilder,
                validityBuilder,
                workspace
              ) match
                case Left(error) =>
                  failure = Some(error)
                case Right(()) =>
                  ()
              volume += 1
          }
        completedValues = Some(values)
      }

    failure match
      case Some(error) => Left(error)
      case None =>
        completedValues
          .toRight(MotionError.ApplicationBuilderProtocolViolation)
          .flatMap { values =>
            for
              image <- Sampled
                .continuous(
                  target,
                  samples.image.nonSpatialAxes,
                  values
                )
                .left
                .map(MotionError.Image.apply)
              validityImage <- Sampled
                .continuous(
                  target,
                  samples.image.nonSpatialAxes,
                  validity
                )
                .left
                .map(MotionError.Image.apply)
            yield
              MotionApplicationResult.create(
                image,
                validityImage
              )
          }

  private def writeVolume(
      volume: Int,
      compiled: CompiledVolumeApplication[Moving, Fixed],
      values: ArrayBuilder[Double],
      validity: ArrayBuilder[Double],
      workspace: MotionApplicationWorkspace
  ): Either[MotionError, Unit] =
    compiled match
      case full: CompiledVolumeApplication.Full[Moving, Fixed] =>
        scanPlan(
          full.plan,
          volume,
          None,
          values,
          validity,
          workspace
        )
      case sliced: CompiledVolumeApplication.Sliced[Moving, Fixed] =>
        var slice = 0
        var failure = Option.empty[MotionError]
        while slice < sliced.plans.size && failure.isEmpty do
          scanPlan(
            sliced.plans(slice),
            volume,
            Some(ApplicationSlice(sliced.axis, slice)),
            values,
            validity,
            workspace
          ) match
            case Left(error) =>
              failure = Some(error)
            case Right(()) =>
              ()
          slice += 1
        failure.toLeft(())

  private def scanPlan(
      plan: ResamplingPlan[
        Moving,
        Fixed,
        D3,
        Continuous,
        Rank[3]
      ],
      volume: Int,
      slice: Option[ApplicationSlice],
      values: ArrayBuilder[Double],
      validity: ArrayBuilder[Double],
      workspace: MotionApplicationWorkspace
  ): Either[MotionError, Unit] =
    plan
      .scan(
        workspace.resampling,
        new ApplicationOutputSink(
          volume,
          slice,
          values,
          validity
        )
      )
      .left
      .map(MotionError.Resampling.apply)
      .left
      .map(error =>
        MotionError.MotionApplicationFailed(volume, slice, error)
      )

  private final class ApplicationOutputSink(
      volume: Int,
      slice: Option[ApplicationSlice],
      values: ArrayBuilder[Double],
      validity: ArrayBuilder[Double]
  ) extends ResamplingSink:
    def accept(
        targetLinearIndex: Int,
        value: Double,
        validityWeight: Double
    ): Unit =
      val targetVoxel =
        slice match
          case None =>
            targetLinearIndex
          case Some(ApplicationSlice(GridAxis.I, index)) =>
            index * target.shape(1) * target.shape(2) +
              targetLinearIndex
          case Some(ApplicationSlice(GridAxis.J, index)) =>
            val i = targetLinearIndex / target.shape(2)
            val k = targetLinearIndex - i * target.shape(2)
            (i * target.shape(1) + index) * target.shape(2) + k
          case Some(ApplicationSlice(GridAxis.K, index)) =>
            val i = targetLinearIndex / target.shape(1)
            val j = targetLinearIndex - i * target.shape(1)
            (i * target.shape(1) + j) * target.shape(2) + index
      val output =
        targetVoxel * volumes.size + volume
      values.writeLinear(output, value)
      validity.writeLinear(output, validityWeight)

object CompiledMotionApplication:
  def compile[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      R <: AnyRank
  ](
      samples: TimedScalarSamples[Moving, R],
      target: Grid[Fixed, D3],
      trajectory: PoseTrajectory[Moving, Fixed],
      schedule: AcquisitionSchedule = AcquisitionSchedule.Volume,
      interpolation: Interpolation[Continuous] = Interpolation.Linear,
      boundary: BoundaryPolicy[Double] = BoundaryPolicy.Constant(0.0),
      extrapolation: ExtrapolationPolicy = ExtrapolationPolicy.Reject
  )(using
      Dimension[D3]
  ): Either[MotionError, CompiledMotionApplication[Moving, Fixed, R]] =
    for
      _ <- validateTimes(samples.times, trajectory.series.times)
      _ <- image4s.geometry.Frame
        .align(samples.image.frame, trajectory.series.movingFrame)
        .left
        .map(MotionError.Geometry.apply)
      _ <- image4s.geometry.Frame
        .align(target.frame, trajectory.series.fixedFrame)
        .left
        .map(MotionError.Geometry.apply)
      _ <- schedule.validateGrid(target)
      volumes <- compileVolumes(
        samples,
        target,
        trajectory,
        schedule,
        interpolation,
        boundary,
        extrapolation
      )
    yield
      new CompiledMotionApplication(
        samples,
        target,
        trajectory,
        schedule,
        interpolation,
        boundary,
        volumes
      )

  private def validateTimes(
      samples: TimeAxis,
      poses: TimeAxis
  ): Either[MotionError, Unit] =
    if samples.size != poses.size then
      Left(MotionError.TimeCountMismatch(samples.size, poses.size))
    else
      samples.seconds
        .zip(poses.seconds)
        .zipWithIndex
        .collectFirst {
          case ((sample, pose), index) if sample != pose =>
            MotionError.PoseTimeMismatch(index, sample, pose)
        }
        .toLeft(())

  private def compileVolumes[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      R <: AnyRank
  ](
      samples: TimedScalarSamples[Moving, R],
      target: Grid[Fixed, D3],
      trajectory: PoseTrajectory[Moving, Fixed],
      schedule: AcquisitionSchedule,
      interpolation: Interpolation[Continuous],
      boundary: BoundaryPolicy[Double],
      extrapolation: ExtrapolationPolicy
  )(using Dimension[D3]): Either[
    MotionError,
    Vector[CompiledVolumeApplication[Moving, Fixed]]
  ] =
    val result =
      Vector.newBuilder[CompiledVolumeApplication[Moving, Fixed]]
    var volume = 0
    var failure = Option.empty[MotionError]
    while volume < samples.times.size && failure.isEmpty do
      compileVolume(
        samples,
        target,
        trajectory,
        schedule,
        interpolation,
        boundary,
        extrapolation,
        volume
      ) match
        case Left(error) =>
          failure = Some(error)
        case Right(compiled) =>
          result += compiled
      volume += 1
    failure.toLeft(result.result())

  private def compileVolume[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      R <: AnyRank
  ](
      samples: TimedScalarSamples[Moving, R],
      target: Grid[Fixed, D3],
      trajectory: PoseTrajectory[Moving, Fixed],
      schedule: AcquisitionSchedule,
      interpolation: Interpolation[Continuous],
      boundary: BoundaryPolicy[Double],
      extrapolation: ExtrapolationPolicy,
      volume: Int
  )(using Dimension[D3]): Either[
    MotionError,
    CompiledVolumeApplication[Moving, Fixed]
  ] =
    samples.volumeAt(volume).flatMap { source =>
      schedule.sliceAxis match
        case None =>
          for
            representative <- LatticeIndex
              .of[D3](0, 0, 0)
              .left
              .map(MotionError.Geometry.apply)
            pose <- trajectory.poseForAcquisition(
              volume,
              representative,
              schedule,
              extrapolation
            )
            plan <- MotionApplication.planAt(
              source,
              target,
              pose,
              interpolation,
              boundary
            )
          yield new CompiledVolumeApplication.Full(plan)
        case Some(axis) =>
          val plans = Vector.newBuilder[
            ResamplingPlan[
              Moving,
              Fixed,
              D3,
              Continuous,
              Rank[3]
            ]
          ]
          var slice = 0
          var failure = Option.empty[MotionError]
          while slice < target.shape(axis.index) && failure.isEmpty do
            compileSlice(
              source,
              target,
              trajectory,
              schedule,
              interpolation,
              boundary,
              extrapolation,
              volume,
              axis,
              slice
            ) match
              case Left(error) =>
                failure = Some(error)
              case Right(plan) =>
                plans += plan
            slice += 1
          failure match
            case Some(error) => Left(error)
            case None =>
              Right(
                new CompiledVolumeApplication.Sliced(
                  axis,
                  plans.result()
                )
              )
    }

  private def compileSlice[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      source: MotionScalarImage[Moving, D3, Rank[3]],
      target: Grid[Fixed, D3],
      trajectory: PoseTrajectory[Moving, Fixed],
      schedule: AcquisitionSchedule,
      interpolation: Interpolation[Continuous],
      boundary: BoundaryPolicy[Double],
      extrapolation: ExtrapolationPolicy,
      volume: Int,
      axis: GridAxis,
      slice: Int
  )(using Dimension[D3]): Either[
    MotionError,
    ResamplingPlan[Moving, Fixed, D3, Continuous, Rank[3]]
  ] =
    val coordinates =
      Vector.tabulate(3)(index => if index == axis.index then slice else 0)
    for
      representative <- LatticeIndex
        .fromVector[D3](coordinates)
        .left
        .map(MotionError.Geometry.apply)
      pose <- trajectory.poseForAcquisition(
        volume,
        representative,
        schedule,
        extrapolation
      )
      sliceGrid <- targetSlice(target, axis, slice)
      plan <- MotionApplication.planAt(
        source,
        sliceGrid,
        pose,
        interpolation,
        boundary
      )
    yield plan

  private def targetSlice[Fixed <: Frame[D3]](
      target: Grid[Fixed, D3],
      axis: GridAxis,
      slice: Int
  )(using Dimension[D3]): Either[MotionError, Grid[Fixed, D3]] =
    val localToGlobalValues =
      Vector.tabulate(16) { flat =>
        val row = flat / 4
        val column = flat % 4
        if row == column then 1.0
        else if row == axis.index && column == 3 then slice.toDouble
        else 0.0
      }
    val shape =
      target.shape.updated(axis.index, 1)
    for
      localToGlobal <- Affine
        .fromRowMajor[D3](localToGlobalValues)
        .left
        .map(MotionError.Geometry.apply)
      indexToFrame <- localToGlobal
        .andThen(target.indexToFrame)
        .left
        .map(MotionError.Geometry.apply)
      grid <- Grid
        .forFrame[D3, Fixed](target.frame)(shape, indexToFrame)
        .left
        .map(MotionError.Geometry.apply)
    yield grid
