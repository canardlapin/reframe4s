package reframe4s.laws

import image4s.BoundaryPolicy
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import image4s.geometry.Affine
import image4s.geometry.D2
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.Point
import reframe4s.lie.FramedAffine
import reframe4s.resample.Interpolation
import reframe4s.resample.ModulatedResamplingPlan
import reframe4s.resample.ResamplingError
import reframe4s.resample.VolumeModulation

/** Modulated resampling laws: `Jacobian` preserves the integral of a
  * density and `SqrtJacobian` the squared L2 norm. Each law is checked on a
  * compressive and an expansive pull, together with the failure of the
  * other law.
  */
final class ModulatedResamplingSuite extends munit.FunSuite:
  private val sourceFrame = geometryRight(Frame.named[D2]("modulation-source"))
  private val targetFrame = geometryRight(Frame.named[D2]("modulation-target"))
  private val sigma = 4.0
  private val sourceSpacing = 0.5
  private val targetSpacing = 0.8
  private val sourceGrid =
    geometryRight(
      Grid.in(sourceFrame)(
        Vector(96, 96),
        spacingAffine(Vector(-23.75, -23.75), sourceSpacing)
      )
    )
  private val targetGrid =
    geometryRight(
      Grid.in(targetFrame)(
        Vector(64, 64),
        spacingAffine(Vector(-25.2, -25.2), targetSpacing)
      )
    )
  private val source =
    imageRight(
      Sampled.continuous(
        sourceGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](96, 96): (i, j) =>
          val x = -23.75 + sourceSpacing * i
          val y = -23.75 + sourceSpacing * j
          math.exp(-(x * x + y * y) / (2.0 * sigma * sigma))
      )
    )
  private val sourceVoxel = sourceSpacing * sourceSpacing
  private val targetVoxel = targetSpacing * targetSpacing
  private val sourceMass = total(source.data, identity) * sourceVoxel
  private val sourceEnergy = total(source.data, value => value * value) * sourceVoxel

  private type Pull = SpatialMap[targetFrame.type, sourceFrame.type, D2]

  private def warp(scale: Double, beta: Double, omega: Double): Pull =
    new Warp[targetFrame.type, sourceFrame.type](targetFrame, sourceFrame, scale, beta, omega)

  private def pulls(scale: Double): Vector[(String, Pull)] =
    Vector(
      "affine" -> FramedAffine.between(targetFrame, sourceFrame)(
        geometryRight(
          Affine.fromRowMajor[D2](
            Vector(scale, 0.0, 0.0, 0.0, scale, 0.0, 0.0, 0.0, 1.0)
          )
        )
      ),
      "warped" -> warp(scale, beta = 0.5, omega = 0.15)
    )

  for scale <- Vector(0.7, 1.3) do
    val kind = if scale < 1.0 then "compressive" else "expansive"

    test(s"jacobian modulation preserves density mass under a $kind pull"):
      for (name, pull) <- pulls(scale) do
        val image = run(pull, VolumeModulation.Jacobian)
        val mass = total(image, identity) * targetVoxel
        val energy = total(image, value => value * value) * targetVoxel
        assertEqualsDouble(mass / sourceMass, 1.0, 5e-3, name)
        // The squared norm scales by about scale^2 and is not preserved.
        assert(math.abs(energy / sourceEnergy - 1.0) > 0.2, s"$name energy")

    test(s"sqrt-jacobian modulation preserves squared L2 norm under a $kind pull"):
      for (name, pull) <- pulls(scale) do
        val image = run(pull, VolumeModulation.SqrtJacobian)
        val mass = total(image, identity) * targetVoxel
        val energy = total(image, value => value * value) * targetVoxel
        assertEqualsDouble(energy / sourceEnergy, 1.0, 5e-3, name)
        assert(math.abs(mass / sourceMass - 1.0) > 0.1, s"$name mass")

    test(s"unmodulated resampling preserves neither law under a $kind pull"):
      for (name, pull) <- pulls(scale) do
        val image = run(pull, VolumeModulation.Unmodulated)
        val mass = total(image, identity) * targetVoxel
        val energy = total(image, value => value * value) * targetVoxel
        assert(math.abs(mass / sourceMass - 1.0) > 0.2, s"$name mass")
        assert(math.abs(energy / sourceEnergy - 1.0) > 0.2, s"$name energy")

  test("affine determinants are exact and warped ones follow the analytic Jacobian"):
    val (_, affine) = pulls(0.7)(0)
    val exact =
      resamplingRight(
        ModulatedResamplingPlan.compile(
          source,
          targetGrid,
          affine,
          Interpolation.Linear,
          VolumeModulation.Jacobian,
          BoundaryPolicy.Constant(0.0)
        )
      )
    assertEqualsDouble(exact.diagnostics.minimumDeterminant, 0.49, 1e-15)
    assertEqualsDouble(exact.diagnostics.maximumDeterminant, 0.49, 1e-15)
    assertEquals(exact.diagnostics.orientationReversingPoints, 0L)
    assertEquals(exact.diagnostics.singularPoints, 0L)
    assertEqualsDouble(
      exact.factorAt(Vector(10, 20)).getOrElse(fail("missing factor")),
      0.49,
      1e-15
    )
    val warpedPull = warp(0.7, beta = 0.5, omega = 0.15)
    val warped =
      resamplingRight(
        ModulatedResamplingPlan.compile(
          source,
          targetGrid,
          warpedPull,
          Interpolation.Linear,
          VolumeModulation.SqrtJacobian,
          BoundaryPolicy.Constant(0.0)
        )
      )
    for
      i <- 1 until 63 by 7
      j <- 1 until 63 by 5
    do
      val y0 = -25.2 + targetSpacing * i
      val y1 = -25.2 + targetSpacing * j
      val analytic =
        0.49 - 0.075 * 0.075 * math.cos(0.15 * y1) * math.cos(0.15 * y0)
      assertEqualsDouble(
        warped.factorAt(Vector(i, j)).getOrElse(fail("missing factor")),
        math.sqrt(analytic),
        1e-4
      )
    assertEquals(warped.factorAt(Vector(64, 0)), None)

  test("a single-sample target axis cannot be differentiated"):
    val thin =
      geometryRight(
        Grid.in(targetFrame)(Vector(1, 8), spacingAffine(Vector(0.0, 0.0), 1.0))
      )
    assertEquals(
      ModulatedResamplingPlan
        .compile(
          source,
          thin,
          warp(1.0, 0.1, 0.1),
          Interpolation.Linear,
          VolumeModulation.Jacobian,
          BoundaryPolicy.Constant(0.0)
        )
        .map(_ => ()),
      Left(ResamplingError.DegenerateModulationAxis(0, 1))
    )

  private def run(
      pull: Pull,
      modulation: VolumeModulation
  ): NDArray[Double, ?] =
    val plan =
      resamplingRight(
        ModulatedResamplingPlan.compile(
          source,
          targetGrid,
          pull,
          Interpolation.Linear,
          modulation,
          BoundaryPolicy.Constant(0.0)
        )
      )
    resamplingRight(plan.run(plan.newWorkspace())).image.data

  private def total(data: NDArray[Double, ?], term: Double => Double): Double =
    var sum = 0.0
    data.foreachElement(value => sum += term(value))
    sum

  /** `phi(y) = scale * y + beta * (sin(omega y1), sin(omega y0))`, with
    * `det D phi = scale^2 - (beta omega)^2 cos(omega y0) cos(omega y1)`.
    */
  private final class Warp[T <: Frame[D2], S <: Frame[D2]](
      val source: T,
      val target: S,
      scale: Double,
      beta: Double,
      omega: Double
  ) extends SpatialMap[T, S, D2]:
    def apply(point: Point[T, D2]): Either[MapError, Point[S, D2]] =
      val y = point.coordinates
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        result <- Point
          .fromVector(
            target,
            Vector(
              scale * y(0) + beta * math.sin(omega * y(1)),
              scale * y(1) + beta * math.sin(omega * y(0))
            )
          )
          .flatMap(value =>
            Frame
              .alignOwners[D2, target.type, S](target, target)
              .flatMap(_.pointToRight(value))
          )
          .left
          .map(MapError.Geometry.apply)
      yield result

  private def spacingAffine(origin: Vector[Double], spacing: Double): Affine[D2] =
    geometryRight(
      Affine.fromOriginSpacingDirection[D2](
        origin = origin,
        spacing = Vector(spacing, spacing),
        directionRowMajor = Vector(1.0, 0.0, 0.0, 1.0)
      )
    )

  private def geometryRight[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def imageRight[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def resamplingRight[A](value: Either[ResamplingError, A]): A =
    value.fold(error => fail(error.message), identity)
