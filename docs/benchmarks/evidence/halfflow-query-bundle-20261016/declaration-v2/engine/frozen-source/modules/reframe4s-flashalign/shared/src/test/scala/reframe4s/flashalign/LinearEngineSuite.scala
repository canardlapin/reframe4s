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

final class LinearEngineSuite extends munit.FunSuite:
  test("public rigid engine recovers a subvoxel physical translation"):
    val fixture = new PairFixture("rigid-engine")
    val truth = affine(
      Affine.fromRowMajor[D3](
        Vector(
          1.0, 0.0, 0.0, 0.45,
          0.0, 1.0, 0.0, -0.35,
          0.0, 0.0, 1.0, 0.25,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    val moving = fixture.moving(truth)
    val fixed = fixture.fixed
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.SuppliedWorldTransform
    )
    val plan = flashalign(Flashalign.rigid(moving, fixed, config))
    val initial = rigid(
      Rigid3.fromAffine[fixture.movingFrame.type, fixture.fixedFrame.type](
        fixture.movingFrame,
        fixture.fixedFrame
      )(Affine.identity[D3])
    )
    val result = flashalign(plan.runFrom(initial, plan.newWorkspace()))
    val actual = result.movingToFixed.operator.rowMajor
    assertEqualsDouble(actual(3), 0.45, 0.08, actual)
    assertEqualsDouble(actual(7), -0.35, 0.08, actual)
    assertEqualsDouble(actual(11), 0.25, 0.08, actual)
    assert(result.report.acceptedSteps > 0, result.report.termination)
    assert(
      result.diagnostics.overlapFraction >= config.policy.qc.minimumOverlapFraction,
      result.diagnostics
    )

  test("public affine engine reduces physical error for mild scale shear and translation"):
    val fixture = new PairFixture("affine-engine")
    val truth = affine(
      Affine.fromRowMajor[D3](
        Vector(
          1.015, 0.008, -0.004, 0.30,
          0.006, 0.985, 0.007, -0.24,
          -0.003, 0.005, 1.010, 0.18,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    val moving = fixture.moving(truth)
    val fixed = fixture.fixed
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.SuppliedWorldTransform
    )
    val plan = flashalign(Flashalign.affine(moving, fixed, config))
    val initial = FramedAffine.betweenFrames[
      fixture.movingFrame.type,
      fixture.fixedFrame.type,
      D3
    ](
      fixture.movingFrame,
      fixture.fixedFrame
    )(Affine.identity[D3])
    val result = flashalign(plan.runFrom(initial, plan.newWorkspace()))
    val actual = result.movingToFixed.operator.rowMajor
    val landmarks = Vector(
      Vector(4.0, 4.0, 4.0),
      Vector(26.0, 4.0, 20.0),
      Vector(5.0, 25.0, 22.0),
      Vector(24.0, 24.0, 7.0),
      Vector(15.0, 15.0, 15.0)
    )
    val landmarkErrors = landmarks.map { point =>
      val left = applyAffine(actual, point)
      val right = applyAffine(truth.rowMajor, point)
      math.sqrt(left.zip(right).map { case (a, b) =>
        val difference = a - b
        difference * difference
      }.sum)
    }
    val initialErrors = landmarks.map { point =>
      val right = applyAffine(truth.rowMajor, point)
      math.sqrt(point.zip(right).map { case (a, b) =>
        val difference = a - b
        difference * difference
      }.sum)
    }
    assert(
      landmarkErrors.sum < initialErrors.sum && landmarkErrors.max < 0.8,
      (landmarkErrors, actual, truth.rowMajor, result.report.termination, result.report.acceptedSteps)
    )
    assert(result.report.acceptedSteps > 0, result.report.termination)
    assert(result.diagnostics.auditPatches > 0, result.diagnostics)

  test("workspace payload cannot cross otherwise compatible plans"):
    val fixture = new PairFixture("workspace-owner")
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.SuppliedWorldTransform
    )
    val left = flashalign(Flashalign.rigid(fixture.fixedAsMoving, fixture.fixed, config))
    val right = flashalign(Flashalign.rigid(fixture.fixedAsMoving, fixture.fixed, config))
    val initial = rigid(
      Rigid3.fromAffine[fixture.movingFrame.type, fixture.fixedFrame.type](
        fixture.movingFrame,
        fixture.fixedFrame
      )(Affine.identity[D3])
    )
    right.runFrom(initial, left.newWorkspace()) match
      case Left(FlashalignError.WorkspacePlanMismatch) => ()
      case other => fail(s"expected workspace-plan mismatch, got $other")

  private final class PairFixture(label: String):
    val movingFrame = geometry(Frame.named[D3](s"$label-moving"))
    val fixedFrame = geometry(Frame.named[D3](s"$label-fixed"))
    private val movingGrid = geometry(
      Grid.in(movingFrame)(Vector(31, 31, 31), Affine.identity[D3])
    )
    private val fixedGrid = geometry(
      Grid.in(fixedFrame)(Vector(31, 31, 31), Affine.identity[D3])
    )
    val fixed: FlashalignImage[fixedFrame.type] = sampled(
      Sampled.continuous(
        fixedGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](31, 31, 31)((i, j, k) => signal(i, j, k))
      )
    )
    val fixedAsMoving: FlashalignImage[movingFrame.type] = sampled(
      Sampled.continuous(
        movingGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](31, 31, 31)((i, j, k) => signal(i, j, k))
      )
    )

    def moving(transform: Affine[D3]): FlashalignImage[movingFrame.type] =
      val matrix = transform.rowMajor
      sampled(
        Sampled.continuous(
          movingGrid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](31, 31, 31) { (i, j, k) =>
            val x = matrix(0) * i + matrix(1) * j + matrix(2) * k + matrix(3)
            val y = matrix(4) * i + matrix(5) * j + matrix(6) * k + matrix(7)
            val z = matrix(8) * i + matrix(9) * j + matrix(10) * k + matrix(11)
            signal(x, y, z)
          }
        )
      )

    private def signal(x: Double, y: Double, z: Double): Double =
      def blob(
          centerX: Double,
          centerY: Double,
          centerZ: Double,
          scaleX: Double,
          scaleY: Double,
          scaleZ: Double
      ): Double =
        val dx = (x - centerX) / scaleX
        val dy = (y - centerY) / scaleY
        val dz = (z - centerZ) / scaleZ
        math.exp(-0.5 * (dx * dx + dy * dy + dz * dz))
      1.7 * blob(8.0, 11.0, 14.0, 3.0, 5.0, 4.0) -
        1.2 * blob(23.0, 8.0, 19.0, 5.0, 3.0, 4.0) +
        0.9 * blob(17.0, 24.0, 7.0, 4.0, 3.0, 5.0) +
        0.7 * blob(25.0, 23.0, 26.0, 3.0, 4.0, 2.5) +
        0.25 * math.sin(0.17 * x + 0.11 * y + 0.07 * z) +
        0.18 * math.cos(0.013 * x * y - 0.009 * y * z + 0.006 * x * z)

  private def geometry[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def sampled[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def affine[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def rigid[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def flashalign[A](result: Either[FlashalignError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def applyAffine(
      matrix: Vector[Double],
      point: Vector[Double]
  ): Vector[Double] =
    Vector.tabulate(3)(row =>
      matrix(row * 4) * point(0) +
        matrix(row * 4 + 1) * point(1) +
        matrix(row * 4 + 2) * point(2) +
        matrix(row * 4 + 3)
    )
