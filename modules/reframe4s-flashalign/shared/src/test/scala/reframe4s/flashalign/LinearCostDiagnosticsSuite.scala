package reframe4s.flashalign

import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid
import ravel.DType.given
import ravel.NDArray
import reframe4s.lie.Rigid3

final class LinearCostDiagnosticsSuite extends munit.FunSuite:
  test("linear diagnostics retain exact deterministic work and zero nonlinear state"):
    val movingFrame = geometry(Frame.named[D3]("cost-moving"))
    val fixedFrame = geometry(Frame.named[D3]("cost-fixed"))
    val movingGrid = geometry(
      Grid.in(movingFrame)(Vector(25, 25, 25), Affine.identity[D3])
    )
    val fixedGrid = geometry(
      Grid.in(fixedFrame)(Vector(25, 25, 25), Affine.identity[D3])
    )
    val fixed = sampled(
      Sampled.continuous(
        fixedGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](25, 25, 25) { (i, j, k) =>
          signal(i.toDouble, j.toDouble, k.toDouble)
        }
      )
    )
    val moving = sampled(
      Sampled.continuous(
        movingGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](25, 25, 25) { (i, j, k) =>
          signal(i + 0.4, j - 0.3, k + 0.2)
        }
      )
    )
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.SuppliedWorldTransform
    )
    val plan = flashalign(Flashalign.rigid(moving, fixed, config))
    val initial = rigid(
      Rigid3.fromAffine[movingFrame.type, fixedFrame.type](movingFrame, fixedFrame)(
        Affine.identity[D3]
      )
    )
    val workspace = plan.newWorkspace()
    val first = flashalign(plan.runFrom(initial, workspace))
    val second = flashalign(plan.runFrom(initial, workspace))
    val work = first.diagnostics.work
    val levels = first.diagnostics.levels

    assertEquals(second.diagnostics.work, work)
    assert(levels.nonEmpty, first.diagnostics)
    assertEquals(
      work.dataLinearizations,
      levels.map(_.work.dataLinearizations).sum
    )
    assertEquals(work.acceptedSteps, levels.map(_.work.acceptedSteps).sum)
    assertEquals(levels.last.work.dataLinearizations, first.report.iterations)
    assertEquals(levels.last.work.acceptedSteps, first.report.acceptedSteps)
    assert(work.uniqueInterpolations > 0L, work)
    assert(work.gradientEvaluations > 0L, work)
    assert(work.gradientEvaluations <= work.uniqueInterpolations, work)
    assert(work.sourceVoxelReads >= work.gradientEvaluations * 8L, work)
    assert(work.patchEntryEvaluations > 0L, work)
    assert(work.patchOccurrenceEvaluations >= work.patchEntryEvaluations, work)
    assert(work.linearSolverCalls >= work.trialEvaluations, work)
    assert(work.selectionEvaluations >= 1, work)
    assertEquals(work.auditEvaluations, 1)
    assertEquals(work.curvatureProducts, 0)
    assertEquals(work.nonlinearCoefficients, 0)
    assertEquals(work.parallelWorkers, 1)
    assert(first.diagnostics.selectionPatches > 0, first.diagnostics)

  private def signal(x: Double, y: Double, z: Double): Double =
    def blob(cx: Double, cy: Double, cz: Double, scale: Double): Double =
      val dx = (x - cx) / scale
      val dy = (y - cy) / scale
      val dz = (z - cz) / scale
      math.exp(-0.5 * (dx * dx + dy * dy + dz * dz))
    1.4 * blob(7.0, 10.0, 13.0, 3.5) -
      1.1 * blob(19.0, 7.0, 16.0, 4.0) +
      0.8 * blob(14.0, 20.0, 6.0, 3.0) +
      0.2 * math.sin(0.17 * x + 0.11 * y + 0.07 * z)

  private def geometry[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def sampled[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def rigid[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def flashalign[A](result: Either[FlashalignError, A]): A =
    result.fold(error => fail(error.message), identity)
