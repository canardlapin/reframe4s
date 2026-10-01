package reframe4s.flashalign

import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid
import ravel.DType.given
import ravel.NDArray
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3
import reframe4s.register.OptimizationReport
import reframe4s.register.Termination

import scala.compiletime.testing.typeCheckErrors

final class FlashalignApiSuite extends munit.FunSuite:
  test("compiled plans own reusable non-concurrent workspaces"):
    val movingFrame = geometry(Frame.named[D3]("moving"))
    val fixedFrame = geometry(Frame.named[D3]("fixed"))
    val moving = scalarImage(movingFrame)
    val fixed = scalarImage(fixedFrame)
    val plan = flashalign(
      Flashalign.compile(
        moving,
        fixed,
        FlashalignPreset.EpiToT1,
        FlashalignInitializationPolicy.SuppliedWorldTransform
      )
    )
    val otherPlan = flashalign(
      Flashalign.compile(
        moving,
        fixed,
        FlashalignPreset.EpiToT1,
        FlashalignInitializationPolicy.SuppliedWorldTransform
      )
    )
    val initial = rigid(
      Rigid3.fromAffine(movingFrame, fixedFrame)(Affine.identity[D3])
    )
    val workspace = plan.newWorkspace()

    assert(workspace.acquire(plan).isRight)
    plan.runFrom(initial, workspace) match
      case Left(FlashalignError.WorkspaceInUse) => ()
      case other => fail(s"expected in-use failure, got $other")
    workspace.release(plan)

    plan.runFrom(initial, otherPlan.newWorkspace()) match
      case Left(FlashalignError.WorkspacePlanMismatch) => ()
      case other => fail(s"expected plan mismatch, got $other")

    Vector.fill(2)(plan.runFrom(initial, workspace)).foreach { result =>
      val fitted = flashalign(result)
      assertEquals(fitted.movingToFixed.source, movingFrame)
      assertEquals(fitted.movingToFixed.target, fixedFrame)
      assert(fitted.report.termination != Termination.RejectedStepLimit)
    }

  test("rigid and affine plans retain concrete endpoint-correct transforms"):
    val movingFrame = geometry(Frame.named[D3]("moving-model"))
    val fixedFrame = geometry(Frame.named[D3]("fixed-model"))
    val moving = scalarImage(movingFrame)
    val fixed = scalarImage(fixedFrame)
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.SuppliedWorldTransform
    )
    val rigidPlan = flashalign(Flashalign.rigid(moving, fixed, config))
    val affinePlan = flashalign(Flashalign.affine(moving, fixed, config))
    val rigidInitial = rigid(
      Rigid3.fromAffine(movingFrame, fixedFrame)(Affine.identity[D3])
    )
    val affineInitial =
      FramedAffine.betweenFrames(movingFrame, fixedFrame)(Affine.identity[D3])

    val rigidResult = flashalign(
      rigidPlan.runFrom(rigidInitial, rigidPlan.newWorkspace())
    )
    val affineResult = flashalign(
      affinePlan.runFrom(affineInitial, affinePlan.newWorkspace())
    )
    assertEquals(rigidResult.diagnostics.model, FlashalignModel.Rigid)
    assertEquals(affineResult.diagnostics.model, FlashalignModel.Affine)
    assert(rigidResult.diagnostics.auditPatches > 0)
    assert(affineResult.diagnostics.auditPatches > 0)

  test("configuration retains the complete preset and explicit initialization policy"):
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.EpiToT1,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )
    assertEquals(config.policy.id, "flashalign-linear-epi-t1-v1")
    assertEquals(config.preset, FlashalignPreset.EpiToT1)
    assertEquals(config.minimumContrastEnergy, config.policy.patch.minimumContrastEnergy)
    assertEquals(config.maximumLinearizations, config.policy.rigidTrust.maximumLinearizations)
    assertEquals(
      config.initialization,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )

  test("linear results retain concrete maps and the exact registration contract"):
    val movingFrame = geometry(Frame.named[D3]("moving-result"))
    val fixedFrame = geometry(Frame.named[D3]("fixed-result"))
    val movingToFixed = rigid(
      Rigid3.fromAffine(movingFrame, fixedFrame)(Affine.identity[D3])
    )
    val report = registration(
      OptimizationReport.create(
        initialObjective = 2.0,
        finalObjective = 1.0,
        iterations = 3,
        attempts = 4,
        acceptedSteps = 2,
        termination = Termination.ObjectiveConverged
      )
    )
    val diagnostics = FlashalignDiagnostics(
      FlashalignModel.Rigid,
      FlashalignPreset.EpiToT1,
      selectedPatches = 2048,
      auditPatches = 1024,
      overlapFraction = 0.9
    )
    val result = flashalign(
      RigidFlashalignResult.create(movingToFixed, report, diagnostics)
    )

    assert(result.movingToFixed eq movingToFixed)
    assert(result.fixedToMoving.source eq fixedFrame)
    assert(result.fixedToMoving.target eq movingFrame)
    assert(result.registration.fixedToMoving eq result.fixedToMoving)
    assert(result.registration.movingToFixed.source eq movingFrame)
    assert(result.registration.movingToFixed.target eq fixedFrame)
    assert(result.report eq report)

  test("wrong frames, ranks, labels, and fabricated result values do not compile"):
    val wrongFrame = typeCheckErrors(
      """
import image4s.geometry.*
import reframe4s.flashalign.*
import reframe4s.lie.Rigid3
def wrongFrame[
    M <: Frame[D3],
    F <: Frame[D3]
](
    plan: RigidFlashalignPlan[M, F],
    inverse: Rigid3[F, M]
) = plan.runFrom(inverse, plan.newWorkspace())
"""
    )
    val wrongRank = typeCheckErrors(
      """
import image4s.*
import image4s.geometry.*
import ravel.Rank
import reframe4s.flashalign.*
def wrongRank[
    M <: Frame[D3],
    F <: Frame[D3],
    MS <: SampleSpace[M, D3],
    FS <: SampleSpace[F, D3]
](
    moving: ContinuousImage[MS, Double, Rank[4]],
    fixed: ContinuousImage[FS, Double, Rank[3]]
) = Flashalign.compile(
  moving,
  fixed,
  FlashalignPreset.EpiToT1,
  FlashalignInitializationPolicy.SuppliedWorldTransform
)
"""
    )
    val wrongLabel = typeCheckErrors(
      """
import image4s.*
import image4s.geometry.*
import ravel.Rank
import reframe4s.flashalign.*
def wrongLabel[
    M <: Frame[D3],
    F <: Frame[D3],
    MS <: SampleSpace[M, D3],
    FS <: SampleSpace[F, D3]
](
    moving: ContinuousImage[MS, Double, Rank[3]],
    fixed: ContinuousImage[FS, Double, Rank[3]]
) = Flashalign.compile(
  moving,
  fixed,
  "epi-t1",
  FlashalignInitializationPolicy.SuppliedWorldTransform
)
"""
    )
    val fabricatedResult = typeCheckErrors(
      """
import image4s.geometry.*
import reframe4s.flashalign.*
final class Fabricated[M <: Frame[D3], F <: Frame[D3]]
    extends FlashalignResult[M, F]
"""
    )

    assert(wrongFrame.nonEmpty)
    assert(wrongRank.nonEmpty)
    assert(wrongLabel.nonEmpty)
    assert(fabricatedResult.nonEmpty)

  private def scalarImage[F <: Frame[D3]](
      frame: F
  ): FlashalignImage[F] =
    val grid = geometry(
      Grid.forFrame(frame)(Vector(25, 25, 25), Affine.identity[D3])
    )
    image(
      Sampled.continuous(
        grid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](25, 25, 25) { (i, j, k) =>
          math.sin(0.31 * i + 0.07 * j) +
            0.73 * math.cos(0.23 * j - 0.05 * k) +
            0.51 * math.sin(0.19 * k + 0.03 * i) +
            0.011 * i * j - 0.008 * j * k + 0.006 * i * k
        }
      )
    )

  private def geometry[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def image[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def rigid[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def registration[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def flashalign[A](result: Either[FlashalignError, A]): A =
    result.fold(error => fail(error.message), identity)
