package reframe4s.laws

import image4s.BoundaryPolicy
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.reference.ReferenceSampler
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import ravel.DType.given
import ravel.NDArray
import reframe4s.core.MapError
import image4s.geometry.Affine
import image4s.geometry.D2
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex
import reframe4s.lie.FramedAffine
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingError
import reframe4s.resample.ResamplingPlan

final class ResamplingPropertiesSuite extends ScalaCheckSuite:
  private val sourceFrame =
    geometryRight(Frame.named[D2]("property-source"))
  private val targetFrame =
    geometryRight(Frame.named[D2]("property-target"))
  private val sourceGrid =
    geometryRight(
      Grid.in(sourceFrame)(Vector(6, 7), Affine.identity[D2])
    )
  private val targetGrid =
    geometryRight(
      Grid.in(targetFrame)(Vector(4, 5), Affine.identity[D2])
    )
  private val source =
    imageRight(
      Sampled.continuous(
        sourceGrid,
        NonSpatialAxes.empty,
        NDArray.tabulate[Double](6, 7)((i, j) =>
          math.sin(i.toDouble * 0.4) +
            math.cos(j.toDouble * 0.3) +
            i.toDouble * j.toDouble * 0.05
        )
      )
    )

  property("random interior affine pulls agree with the reference oracle"):
    forAll(
      Gen.choose(0.0, 0.9),
      Gen.choose(0.0, 0.9)
    ): (shift0, shift1) =>
      val pull =
        geometryRight(
          FramedAffine.translation(
            targetFrame,
            sourceFrame
          )(shift0, shift1)
        )
      val plan =
        resamplingRight(
          ResamplingPlan.affine(
            source,
            targetGrid,
            pull,
            Interpolation.Linear,
            BoundaryPolicy.Reject
          )
        )
      val result = resamplingRight(plan.run(plan.newWorkspace()))

      for
        i <- 0 until targetGrid.shape(0)
        j <- 0 until targetGrid.shape(1)
      do
        val point =
          geometryRight(
            targetGrid.pointAt(geometryRight(LatticeIndex.of[D2](i, j)))
          )
        val sourcePoint = mapRight(pull(point))
        val expected =
          imageRight(ReferenceSampler.linear(source, sourcePoint))
        val actual = imageRight(result.image.valueAt(Vector(i, j)))
        assertEqualsDouble(actual, expected.value, 1e-11)

  private def geometryRight[A](value: Either[GeometryError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def imageRight[A](value: Either[ImageError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def mapRight[A](value: Either[MapError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def resamplingRight[A](
      value: Either[ResamplingError, A]
  ): A =
    value match
      case Right(result) => result
      case Left(error)   => fail(error.message)
