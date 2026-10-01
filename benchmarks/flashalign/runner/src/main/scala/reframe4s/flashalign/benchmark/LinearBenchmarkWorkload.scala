package reframe4s.flashalign.benchmark

import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid
import ravel.DType.given
import ravel.NDArray
import reframe4s.flashalign.AffineFlashalignResult
import reframe4s.flashalign.Flashalign
import reframe4s.flashalign.FlashalignConfig
import reframe4s.flashalign.FlashalignDiagnostics
import reframe4s.flashalign.FlashalignError
import reframe4s.flashalign.FlashalignImage
import reframe4s.flashalign.FlashalignInitializationPolicy
import reframe4s.flashalign.FlashalignPreset
import reframe4s.flashalign.RigidFlashalignResult
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3
import reframe4s.register.OptimizationReport

final case class LinearRunObservation(
    checksum: Double,
    report: OptimizationReport,
    diagnostics: FlashalignDiagnostics
)

/** Deterministic in-memory pair used by both JMH and the receipt probe. */
final class LinearBenchmarkWorkload private (
    val side: Int,
    private val rigidPreparation: () => Double,
    private val affinePreparation: () => Double,
    private val rigidColdRun: () => LinearRunObservation,
    private val affineColdRun: () => LinearRunObservation,
    private val rigidWarmRun: () => LinearRunObservation,
    private val affineWarmRun: () => LinearRunObservation
):
  def prepareRigid(): Double = rigidPreparation()
  def prepareAffine(): Double = affinePreparation()
  def coldRigid(): LinearRunObservation = rigidColdRun()
  def coldAffine(): LinearRunObservation = affineColdRun()
  def warmRigid(): LinearRunObservation = rigidWarmRun()
  def warmAffine(): LinearRunObservation = affineWarmRun()

object LinearBenchmarkWorkload:
  def create(side: Int): LinearBenchmarkWorkload =
    require(side >= 17, s"benchmark side must be at least 17, got $side")
    val fixture = new PairFixture(side)
    val rigidConfig = FlashalignConfig.forPreset(
      FlashalignPreset.EpiToT1,
      FlashalignInitializationPolicy.SuppliedWorldTransform
    )
    val affineConfig = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.SuppliedWorldTransform
    )
    val rigidInitial = right(
      Rigid3.fromAffine[fixture.movingFrame.type, fixture.fixedFrame.type](
        fixture.movingFrame,
        fixture.fixedFrame
      )(
        right(
          Affine.fromRowMajor[D3](
            Vector(
              1.0, 0.0, 0.0, 0.45,
              0.0, 1.0, 0.0, -0.35,
              0.0, 0.0, 1.0, 0.25,
              0.0, 0.0, 0.0, 1.0
            )
          )
        )
      )
    )
    val affineInitial = FramedAffine.betweenFrames[
      fixture.movingFrame.type,
      fixture.fixedFrame.type,
      D3
    ](fixture.movingFrame, fixture.fixedFrame)(Affine.identity[D3])

    def rigidPlan() =
      flashalign(Flashalign.rigid(fixture.rigidMoving, fixture.fixed, rigidConfig))

    def affinePlan() =
      flashalign(Flashalign.affine(fixture.affineMoving, fixture.fixed, affineConfig))

    val compiledRigid = rigidPlan()
    val rigidWorkspace = compiledRigid.newWorkspace()
    val compiledAffine = affinePlan()
    val affineWorkspace = compiledAffine.newWorkspace()

    val workload = new LinearBenchmarkWorkload(
      side,
      () => preparationChecksum(rigidPlan()),
      () => preparationChecksum(affinePlan()),
      () =>
        val plan = rigidPlan()
        observe(flashalign(plan.runFrom(rigidInitial, plan.newWorkspace()))),
      () =>
        val plan = affinePlan()
        observe(flashalign(plan.runFrom(affineInitial, plan.newWorkspace()))),
      () => observe(flashalign(compiledRigid.runFrom(rigidInitial, rigidWorkspace))),
      () => observe(flashalign(compiledAffine.runFrom(affineInitial, affineWorkspace)))
    )
    validate(workload.warmRigid(), "rigid")
    validate(workload.warmAffine(), "affine")
    workload

  private final class PairFixture(val side: Int):
    val movingFrame = right(Frame.named[D3]("flashalign-cost-moving"))
    val fixedFrame = right(Frame.named[D3]("flashalign-cost-fixed"))
    private val movingGrid = right(
      Grid.in(movingFrame)(Vector(side, side, side), Affine.identity[D3])
    )
    private val fixedGrid = right(
      Grid.in(fixedFrame)(Vector(side, side, side), Affine.identity[D3])
    )
    val fixed: FlashalignImage[fixedFrame.type] = sampled(
      Sampled.continuous(
        fixedGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](side, side, side) { (i, j, k) =>
          signal(i.toDouble, j.toDouble, k.toDouble)
        }
      )
    )
    val rigidMoving: FlashalignImage[movingFrame.type] = transformed(
      Vector(
        1.0, 0.0, 0.0, 0.45,
        0.0, 1.0, 0.0, -0.35,
        0.0, 0.0, 1.0, 0.25,
        0.0, 0.0, 0.0, 1.0
      ),
      polarity = 1.0
    )
    val affineMoving: FlashalignImage[movingFrame.type] = transformed(
      Vector(
        1.012, 0.006, -0.003, 0.28,
        0.004, 0.989, 0.005, -0.21,
        -0.002, 0.004, 1.008, 0.16,
        0.0, 0.0, 0.0, 1.0
      ),
      polarity = 1.0
    )

    private def transformed(
        matrix: Vector[Double],
        polarity: Double
    ): FlashalignImage[movingFrame.type] =
      sampled(
        Sampled.continuous(
          movingGrid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](side, side, side) { (i, j, k) =>
            val x = matrix(0) * i + matrix(1) * j + matrix(2) * k + matrix(3)
            val y = matrix(4) * i + matrix(5) * j + matrix(6) * k + matrix(7)
            val z = matrix(8) * i + matrix(9) * j + matrix(10) * k + matrix(11)
            polarity * signal(x, y, z)
          }
        )
      )

    private def signal(x: Double, y: Double, z: Double): Double =
      val scale = side.toDouble / 31.0
      def blob(
          centerX: Double,
          centerY: Double,
          centerZ: Double,
          scaleX: Double,
          scaleY: Double,
          scaleZ: Double
      ): Double =
        val dx = (x - centerX * scale) / (scaleX * scale)
        val dy = (y - centerY * scale) / (scaleY * scale)
        val dz = (z - centerZ * scale) / (scaleZ * scale)
        math.exp(-0.5 * (dx * dx + dy * dy + dz * dz))
      1.7 * blob(8.0, 11.0, 14.0, 3.0, 5.0, 4.0) -
        1.2 * blob(23.0, 8.0, 19.0, 5.0, 3.0, 4.0) +
        0.9 * blob(17.0, 24.0, 7.0, 4.0, 3.0, 5.0) +
        0.7 * blob(25.0, 23.0, 26.0, 3.0, 4.0, 2.5) +
        0.25 * math.sin(0.17 * x + 0.11 * y + 0.07 * z) +
        0.18 * math.cos(0.013 * x * y - 0.009 * y * z + 0.006 * x * z)

  private def preparationChecksum(plan: reframe4s.flashalign.FlashalignPlan[?, ?]): Double =
    plan.moving.grid.shape.product.toDouble +
      plan.fixed.grid.shape.product.toDouble +
      plan.config.minimumContrastEnergy

  private def observe(result: RigidFlashalignResult[?, ?]): LinearRunObservation =
    LinearRunObservation(
      result.movingToFixed.operator.rowMajor.sum + result.report.finalObjective,
      result.report,
      result.diagnostics
    )

  private def observe(result: AffineFlashalignResult[?, ?]): LinearRunObservation =
    LinearRunObservation(
      result.movingToFixed.operator.rowMajor.sum + result.report.finalObjective,
      result.report,
      result.diagnostics
    )

  private def validate(observation: LinearRunObservation, model: String): Unit =
    require(observation.checksum.isFinite, s"$model benchmark checksum is nonfinite")
    require(
      observation.report.finalObjective <= observation.report.initialObjective,
      s"$model benchmark increased its objective"
    )
    require(
      observation.diagnostics.work.uniqueInterpolations > 0L,
      s"$model benchmark performed no interpolation"
    )
    require(
      observation.diagnostics.work.nonlinearCoefficients == 0,
      s"$model linear benchmark created nonlinear state"
    )

  private def flashalign[A](result: Either[FlashalignError, A]): A =
    result.fold(error => throw new IllegalArgumentException(error.message), identity)

  private def sampled[A](result: Either[?, A]): A = right(result)

  private def right[A](result: Either[?, A]): A =
    result.fold(error => throw new IllegalArgumentException(error.toString), identity)
