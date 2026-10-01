package reframe4s.resample

import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.ContinuousIndex
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.Grid
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Slice

final class SparseValueGradientBuffer3Suite extends munit.FunSuite:
  test("batch full-support sampling matches the typed pointwise fused path"):
    val fixture = new SamplerFixture(strided = false, "canonical")
    val x = Array(0.1, 1.2, 2.4, 4.5, -0.1, Double.NaN)
    val y = Array(0.2, 2.1, 3.3, 5.5, 2.0, 2.0)
    val z = Array(0.3, 3.2, 4.4, 6.5, 2.0, 2.0)
    val output = buffer(x.length)

    resampling(
      fixture.sampler.sampleFullSupport(x, y, z, x.length, output)
    )

    assertEquals(
      output.fullSupport.toVector,
      Vector(true, true, true, true, false, false)
    )
    var index = 0
    while index < 4 do
      val point = geometry(
        fixture.grid.pointAt(
          geometry(
            ContinuousIndex.fromVector[D3](
              Vector(x(index), y(index), z(index))
            )
          )
        )
      )
      val expected = resampling(
        fixture.sampler.at(point, ScalarValueGradient3.create)
      )
      assertEqualsDouble(output.values(index), expected.value, 1e-13)
      assertEqualsDouble(output.gradientX(index), expected.gradientX, 1e-13)
      assertEqualsDouble(output.gradientY(index), expected.gradientY, 1e-13)
      assertEqualsDouble(output.gradientZ(index), expected.gradientZ, 1e-13)
      index += 1
    assert(output.values.drop(4).forall(_ == 0.0))
    assert(output.gradientX.drop(4).forall(_ == 0.0))
    assertEquals(
      output.counters,
      SparseSamplingCounters(6L, 4L, 2L, 32L)
    )

  test("the same batch kernel respects sliced and reversed Ravel layouts"):
    val fixture = new SamplerFixture(strided = true, "view")
    val count = 19
    val x = Array.tabulate(count)(index => 0.1 + index % 5)
    val y = Array.tabulate(count)(index => 0.2 + index % 6)
    val z = Array.tabulate(count)(index => 0.3 + index % 7)
    val output = buffer(count)
    resampling(fixture.sampler.sampleFullSupport(x, y, z, count, output))

    assert(output.fullSupport.forall(identity))
    var index = 0
    while index < count do
      val expected =
        ScalarValueGradient3.create
      assert(
        fixture.sampler.sampleFullSupportContinuousIndex(
          x(index),
          y(index),
          z(index),
          expected
        )
      )
      assertEqualsDouble(output.values(index), expected.value, 0.0)
      assertEqualsDouble(output.gradientX(index), expected.gradientX, 0.0)
      assertEqualsDouble(output.gradientY(index), expected.gradientY, 0.0)
      assertEqualsDouble(output.gradientZ(index), expected.gradientZ, 0.0)
      index += 1

  test("world-coordinate batches include the complete inverse affine"):
    val fixture = new SamplerFixture(strided = false, "world-batch")
    val continuousX = Array(0.2, 1.3, 3.4, 4.6)
    val continuousY = Array(0.4, 2.2, 4.1, 5.7)
    val continuousZ = Array(0.6, 1.8, 3.5, 6.2)
    val world = Vector.tabulate(continuousX.length) { index =>
      val continuous = geometry(
        ContinuousIndex.fromVector[D3](
          Vector(
            continuousX(index),
            continuousY(index),
            continuousZ(index)
          )
        )
      )
      geometry(fixture.grid.pointAt(continuous))
    }
    val output = buffer(world.size)
    resampling(
      fixture.sampler.sampleFullSupportWorld(
        world.map(_.coordinates(0)).toArray,
        world.map(_.coordinates(1)).toArray,
        world.map(_.coordinates(2)).toArray,
        world.size,
        output
      )
    )
    var index = 0
    while index < world.size do
      val expected = resampling(
        fixture.sampler.at(world(index), ScalarValueGradient3.create)
      )
      assert(output.fullSupport(index))
      assertEqualsDouble(output.values(index), expected.value, 2e-13)
      assertEqualsDouble(output.gradientX(index), expected.gradientX, 2e-13)
      assertEqualsDouble(output.gradientY(index), expected.gradientY, 2e-13)
      assertEqualsDouble(output.gradientZ(index), expected.gradientZ, 2e-13)
      index += 1

  test("value-only world batches match fused values without derivative output"):
    val fixture = new SamplerFixture(strided = true, "value-only")
    val continuousX = Array(0.2, 1.3, 3.4, 4.6, -0.2)
    val continuousY = Array(0.4, 2.2, 4.1, 5.7, 2.0)
    val continuousZ = Array(0.6, 1.8, 3.5, 6.2, 2.0)
    val world = Vector.tabulate(continuousX.length) { index =>
      val continuous = geometry(
        ContinuousIndex.fromVector[D3](
          Vector(
            continuousX(index),
            continuousY(index),
            continuousZ(index)
          )
        )
      )
      geometry(fixture.grid.pointAt(continuous))
    }
    val values = new Array[Double](world.size)
    val support = new Array[Boolean](world.size)
    val counters = resampling(
      fixture.sampler.sampleValuesFullSupportWorld(
        world.map(_.coordinates(0)).toArray,
        world.map(_.coordinates(1)).toArray,
        world.map(_.coordinates(2)).toArray,
        world.size,
        values,
        support
      )
    )

    assertEquals(support.toVector, Vector(true, true, true, true, false))
    var index = 0
    while index < 4 do
      val expected = resampling(
        fixture.sampler.at(world(index), ScalarValueGradient3.create)
      )
      assertEqualsDouble(values(index), expected.value, 2e-13)
      index += 1
    assertEqualsDouble(values(4), 0.0, 0.0)
    assertEquals(counters, SparseSamplingCounters(5L, 4L, 1L, 32L))

  test("batch validation is typed and occurs before counters change"):
    val fixture = new SamplerFixture(strided = false, "validation")
    val output = buffer(2)
    val coordinates = Array(1.0, 2.0, 3.0)
    assertEquals(
      fixture.sampler.sampleFullSupport(
        coordinates,
        coordinates,
        coordinates,
        count = 3,
        output
      ),
      Left(
        ResamplingError.InvalidSparseSamplingShape(
          3,
          3,
          3,
          3,
          2
        )
      )
    )
    assertEquals(output.counters, SparseSamplingCounters(0L, 0L, 0L, 0L))
    assertEquals(
      SparseValueGradientBuffer3.create(-1),
      Left(ResamplingError.InvalidSparseSamplingCapacity(-1))
    )

  private final class SamplerFixture(
      strided: Boolean,
      suffix: String
  ):
    val frame = geometry(Frame.named[D3](s"sparse-$suffix"))
    private val affine = geometry(
      Affine.fromRowMajor[D3](
        Vector(
          1.8, 0.2, -0.1, 4.0,
          0.0, 2.2, 0.3, -3.0,
          0.1, 0.0, 2.7, 8.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    private val canonical = NDArray.tabulate[Double](12, 14, 16) { (i, j, k) =>
      math.sin(i * 0.13) + math.cos(j * 0.17) + k * 0.07
    }
    private val data: NDArray[Double, Rank[3]] =
      if strided then
        canonical
          .slice(0, Slice(0, 12, 2))
          .slice(1, Slice(0, 14, 2))
          .slice(2, Slice(0, 16, 2))
          .reverse(1)
      else
        NDArray.tabulate[Double](6, 7, 8) { (i, j, k) =>
          math.sin(i * 0.13) + math.cos(j * 0.17) + k * 0.07
        }
    val grid = geometry(Grid.in(frame)(Vector(6, 7, 8), affine))
    val image = sampled(
      Sampled.continuous(grid, NonSpatialAxes.empty, data)
    )
    val sampler = resampling(LinearValueGradientSampler3.compile(image))

  private def buffer(capacity: Int): SparseValueGradientBuffer3 =
    resampling(SparseValueGradientBuffer3.create(capacity))

  private def geometry[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def sampled[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def resampling[A](result: Either[ResamplingError, A]): A =
    result.fold(error => fail(error.message), identity)
