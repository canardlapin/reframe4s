package reframe4s.motion

import image4s.BoundaryPolicy
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import reframe4s.multiscale.ScaleSchedule
import reframe4s.multiscale.ScalarPyramidWorkspace
import reframe4s.resample.Interpolation

enum MotionReference derives CanEqual:
  case Middle
  case Frame(index: Int)

  def resolve(size: Int): Either[MotionError, Int] =
    this match
      case Middle =>
        if size <= 0 then Left(MotionError.EmptySeries)
        else Right(size / 2)
      case Frame(index) =>
        if index < 0 || index >= size then
          Left(MotionError.InvalidReferenceIndex(index, size))
        else Right(index)

enum TemplateStatistic derives CanEqual:
  case ValidityWeightedMean

final class TemplateRefreshControl private (
    val passes: Int,
    val statistic: TemplateStatistic
)

object TemplateRefreshControl:
  val MaximumPasses: Int = 3
  val default: TemplateRefreshControl =
    new TemplateRefreshControl(
      1,
      TemplateStatistic.ValidityWeightedMean
    )

  def create(
      passes: Int
  ): Either[MotionError, TemplateRefreshControl] =
    create(passes, TemplateStatistic.ValidityWeightedMean)

  def create(
      passes: Int,
      statistic: TemplateStatistic
  ): Either[MotionError, TemplateRefreshControl] =
    if passes < 0 || passes > MaximumPasses then
      Left(
        MotionError.InvalidTemplateRefreshPasses(
          passes,
          MaximumPasses
        )
      )
    else Right(new TemplateRefreshControl(passes, statistic))

/**
 * Explicit scratch state for iterative template estimation.
 */
final class TemplateMotionEstimatorWorkspace private (
    private[motion] val pyramid: ScalarPyramidWorkspace,
    private[motion] val series: MultiscaleRigidSeriesWorkspace,
    private[motion] val application: MotionApplicationWorkspace
):
  private var active = false

  private[motion] def acquire(): Boolean =
    if active then false
    else
      active = true
      true

  private[motion] def release(): Unit =
    active = false

object TemplateMotionEstimatorWorkspace:
  def create(using Dimension[D3]): TemplateMotionEstimatorWorkspace =
    new TemplateMotionEstimatorWorkspace(
      ScalarPyramidWorkspace.create,
      MultiscaleRigidSeriesWorkspace.create,
      MotionApplicationWorkspace.create
    )

/**
 * Final reference-anchored motion estimate plus the exact template against
 * which it was fitted.
 */
final class TemplateMotionEstimate[F <: Frame[D3]] private (
    val estimate: MultiscaleRigidSeriesEstimate[F],
    val template: MotionScalarImage[F, D3, Rank[3]],
    val referenceIndex: Int,
    val refreshPasses: Int,
    val templateStatistic: TemplateStatistic,
    val meanFinalObjectives: Vector[Double]
)

private object TemplateMotionEstimate:
  def create[F <: Frame[D3]](
      estimate: MultiscaleRigidSeriesEstimate[F],
      template: MotionScalarImage[F, D3, Rank[3]],
      referenceIndex: Int,
      refreshPasses: Int,
      templateStatistic: TemplateStatistic,
      meanFinalObjectives: Vector[Double]
  ): TemplateMotionEstimate[F] =
    new TemplateMotionEstimate(
      estimate,
      template,
      referenceIndex,
      refreshPasses,
      templateStatistic,
      meanFinalObjectives
    )

/**
 * Bounded reference-anchored template fitting.
 *
 * The initial template is the selected observed volume. Each refresh applies
 * the current poses through [[CompiledMotionApplication]], forms a
 * validity-weighted mean on the reference grid, and recompiles the filtered
 * multiscale estimator against that immutable template. The selected reference
 * pose is measured at identity on every pass and is never optimized away.
 */
object TemplateMotionEstimator:
  def estimate[
      F <: Frame[D3],
      R <: AnyRank
  ](
      samples: TimedScalarSamples[F, R],
      reference: MotionReference,
      schedule: ScaleSchedule[D3, RigidOptimizerControl],
      refresh: TemplateRefreshControl,
      workspace: TemplateMotionEstimatorWorkspace
  )(using Dimension[D3]): Either[
    MotionError,
    TemplateMotionEstimate[F]
  ] =
    if !workspace.acquire() then
      Left(MotionError.TemplateEstimatorWorkspaceInUse)
    else
      try
        for
          referenceIndex <- reference.resolve(samples.times.size)
          initialTemplate <- samples.volumeAt(referenceIndex)
          initialCompiled <-
            CompiledMultiscaleRigidSeriesEstimator.compileAgainst(
              samples,
              referenceIndex,
              initialTemplate,
              schedule,
              workspace.pyramid
            )
          initialEstimate <- initialCompiled.run(workspace.series)
          result <- refreshTemplate(
            samples,
            schedule,
            referenceIndex,
            refresh,
            initialTemplate,
            initialEstimate,
            workspace
          )
        yield result
      finally workspace.release()

  private def refreshTemplate[
      F <: Frame[D3],
      R <: AnyRank
  ](
      samples: TimedScalarSamples[F, R],
      schedule: ScaleSchedule[D3, RigidOptimizerControl],
      referenceIndex: Int,
      control: TemplateRefreshControl,
      initialTemplate: MotionScalarImage[F, D3, Rank[3]],
      initialEstimate: MultiscaleRigidSeriesEstimate[F],
      workspace: TemplateMotionEstimatorWorkspace
  )(using Dimension[D3]): Either[
    MotionError,
    TemplateMotionEstimate[F]
  ] =
    var template = initialTemplate
    var estimate = initialEstimate
    val objectives = Vector.newBuilder[Double]
    objectives += meanFinalObjective(initialEstimate)
    var pass = 0
    var failure = Option.empty[MotionError]
    while pass < control.passes && failure.isEmpty do
      val refreshed =
        for
          application <- CompiledMotionApplication.compile(
            samples,
            template.grid,
            PoseTrajectory.fromSeries(estimate.poses),
            schedule = AcquisitionSchedule.Volume,
            interpolation = Interpolation.Linear,
            boundary = BoundaryPolicy.Constant(0.0)
          )
          corrected <- application.run(workspace.application)
          nextTemplate <-
            control.statistic match
              case TemplateStatistic.ValidityWeightedMean =>
                validityWeightedMean(corrected, template)
          compiled <-
            CompiledMultiscaleRigidSeriesEstimator.compileAgainst(
              samples,
              referenceIndex,
              nextTemplate,
              schedule,
              workspace.pyramid
            )
          nextEstimate <- compiled.run(workspace.series)
        yield nextTemplate -> nextEstimate
      refreshed match
        case Left(error) =>
          failure =
            Some(
              MotionError.TemplateRefreshFailed(
                pass + 1,
                error
              )
            )
        case Right((nextTemplate, nextEstimate)) =>
          template = nextTemplate
          estimate = nextEstimate
          objectives += meanFinalObjective(nextEstimate)
      pass += 1

    failure match
      case Some(error) => Left(error)
      case None =>
        Right(
          TemplateMotionEstimate.create(
            estimate,
            template,
            referenceIndex,
            control.passes,
            control.statistic,
            objectives.result()
          )
        )

  private def validityWeightedMean[F <: Frame[D3]](
      corrected: MotionApplicationResult[F],
      fallback: MotionScalarImage[F, D3, Rank[3]]
  ): Either[MotionError, MotionScalarImage[F, D3, Rank[3]]] =
    val nx = fallback.grid.shape(0)
    val ny = fallback.grid.shape(1)
    val nz = fallback.grid.shape(2)
    val nt = corrected.image.nonSpatialAxes.shape(0)
    val mean =
      NDArray.build[Double, Rank[3]](Shape(nx, ny, nz)) { builder =>
        var i = 0
        while i < nx do
          var j = 0
          while j < ny do
            var k = 0
            while k < nz do
              var weighted = 0.0
              var totalWeight = 0.0
              var time = 0
              while time < nt do
                val weight =
                  corrected.validityWeights.data(i, j, k, time)
                weighted +=
                  weight * corrected.image.data(i, j, k, time)
                totalWeight += weight
                time += 1
              val value =
                if totalWeight > 0.0 then weighted / totalWeight
                else fallback.data(i, j, k)
              builder.writeLinear((i * ny + j) * nz + k, value)
              k += 1
            j += 1
          i += 1
      }
    Sampled
      .continuous(
        fallback.grid,
        NonSpatialAxes.empty,
        mean
      )
      .left
      .map(MotionError.Image.apply)

  private def meanFinalObjective[F <: Frame[D3]](
      estimate: MultiscaleRigidSeriesEstimate[F]
  ): Double =
    var sum = 0.0
    var count = 0
    estimate.frameEstimates.foreach { frame =>
      frame.levelEstimates.lastOption.foreach { finest =>
        sum += finest.report.finalObjective
        count += 1
      }
    }
    if count == 0 then 0.0 else sum / count.toDouble
