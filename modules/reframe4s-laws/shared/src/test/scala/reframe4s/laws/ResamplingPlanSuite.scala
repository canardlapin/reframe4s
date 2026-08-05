package reframe4s.laws

import scala.compiletime.testing.typeCheckErrors

import image4s.Axis
import image4s.AxisKind
import image4s.BoundaryPolicy
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.PartialWeight.*
import image4s.Sample
import image4s.Sampled
import image4s.Validity
import image4s.reference.ReferenceSampler
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Shape
import reframe4s.core.MapError
import image4s.geometry.Affine
import image4s.geometry.D2
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex
import reframe4s.lie.FramedAffine
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingError
import reframe4s.resample.ResamplingPlan
import reframe4s.resample.ResamplingSink

final class ResamplingPlanSuite extends munit.FunSuite:
  test("linear interpolation reproduces an affine scalar field"):
    val sourceFrame = geometryRight(Frame.named[D2]("affine-field-source"))
    val targetFrame = geometryRight(Frame.named[D2]("affine-field-target"))
    val sourceGrid =
      geometryRight(
        Grid.in(sourceFrame)(Vector(6, 6), Affine.identity[D2])
      )
    val targetGrid =
      geometryRight(
        Grid.in(targetFrame)(
          Vector(4, 4),
          geometryRight(
            Affine.fromOriginSpacingDirection[D2](
              origin = Vector(0.25, 0.75),
              spacing = Vector(1.0, 1.0),
              directionRowMajor = Vector(1.0, 0.0, 0.0, 1.0)
            )
          )
        )
      )
    val source =
      imageRight(
        Sampled.continuous(
          sourceGrid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](6, 6)((i, j) =>
            3.0 * i.toDouble - 2.0 * j.toDouble + 5.0
          )
        )
      )
    val pull =
      FramedAffine.between(targetFrame, sourceFrame)(Affine.identity[D2])
    val plan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          pull,
          Interpolation.Linear
        )
      )
    val result = resamplingRight(plan.run(plan.newWorkspace()))

    for
      i <- 0 until 4
      j <- 0 until 4
    do
      val expected =
        3.0 * (i.toDouble + 0.25) -
          2.0 * (j.toDouble + 0.75) +
          5.0
      assertEqualsDouble(
        imageRight(result.image.valueAt(Vector(i, j))),
        expected,
        1e-12
      )
      assertEquals(
        imageRight(result.validity.at(Vector(i, j))),
        Validity.Full
      )

  test(
    "asymmetric physical grids agree with the reference pull sampler"
  ):
    val sourceFrame = geometryRight(Frame.named[D2]("source-physical"))
    val targetFrame = geometryRight(Frame.named[D2]("target-physical"))
    val sourceEmbedding =
      geometryRight(
        Affine.fromOriginSpacingDirection[D2](
          origin = Vector(10.0, -4.0),
          spacing = Vector(2.0, 3.0),
          directionRowMajor = Vector(0.0, -1.0, 1.0, 0.0)
        )
      )
    val targetEmbedding =
      geometryRight(
        Affine.fromOriginSpacingDirection[D2](
          origin = Vector(11.0, -1.0),
          spacing = Vector(1.25, 2.5),
          directionRowMajor = Vector(1.0, 0.0, 0.0, -1.0)
        )
      )
    val sourceGrid =
      geometryRight(
        Grid.in(sourceFrame)(Vector(5, 6), sourceEmbedding)
      )
    val targetGrid =
      geometryRight(
        Grid.in(targetFrame)(Vector(3, 4), targetEmbedding)
      )
    val sourceData =
      NDArray.tabulate[Double](5, 6)((i, j) =>
        100.0 * i.toDouble + 7.0 * j.toDouble
      )
    val source =
      imageRight(
        Sampled.continuous(sourceGrid, NonSpatialAxes.empty, sourceData)
      )
    val pull =
      geometryRight(
        FramedAffine.translation(targetFrame, sourceFrame)(0.75, -1.5)
      )
    val boundary = BoundaryPolicy.Constant(-17.0)
    val plan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          pull,
          Interpolation.Linear,
          boundary
        )
      )
    val result = resamplingRight(plan.run(plan.newWorkspace()))

    assertEquals(
      plan.structure.materializedCoordinateCount,
      0
    )
    assertEquals(result.image.logicalShape, Vector(3, 4))

    for
      i <- 0 until targetGrid.shape(0)
      j <- 0 until targetGrid.shape(1)
    do
      val targetPoint =
        geometryRight(targetGrid.pointAt(geometryRight(LatticeIndex.of[D2](i, j))))
      val sourcePoint = mapRight(pull(targetPoint))
      val expected =
        imageRight(
          ReferenceSampler.linearToDouble(
            source,
            sourcePoint,
            boundary = boundary
          )
        )
      val actual = imageRight(result.image.valueAt(Vector(i, j)))
      assertEqualsDouble(actual, expected.value, 1e-10)
      assertValidity(
        imageRight(result.validity.at(Vector(i, j))),
        expected
      )

  test(
    "Lanczos-5 agrees with an independent oracle on asymmetric oblique grids"
  ):
    val sourceFrame = geometryRight(Frame.named[D2]("lanczos-source"))
    val targetFrame = geometryRight(Frame.named[D2]("lanczos-target"))
    val sourceEmbedding =
      geometryRight(
        Affine.fromOriginSpacingDirection[D2](
          origin = Vector(17.0, -9.0),
          spacing = Vector(1.7, 2.3),
          directionRowMajor = Vector(
            math.cos(0.31), -math.sin(0.31),
            math.sin(0.31), math.cos(0.31)
          )
        )
      )
    val targetToSourceIndex =
      geometryRight(
        Affine.fromRowMajor[D2](
          Vector(
            0.0, 1.1, 8.25,
            -0.9, 0.0, 10.5,
            0.0, 0.0, 1.0
          )
        )
      )
    val targetEmbedding =
      geometryRight(targetToSourceIndex.andThen(sourceEmbedding))
    val sourceGrid =
      geometryRight(
        Grid.in(sourceFrame)(Vector(24, 22), sourceEmbedding)
      )
    val targetGrid =
      geometryRight(
        Grid.in(targetFrame)(Vector(3, 3), targetEmbedding)
      )
    val sourceData =
      NDArray.tabulate[Double](24, 22)((i, j) =>
        2.0 +
          math.sin(2.0 * math.Pi * i.toDouble / 19.0) +
          0.35 * math.cos(2.0 * math.Pi * j.toDouble / 17.0) +
          (if i == 10 && j == 9 then 1.25 else 0.0)
      )
    val source =
      imageRight(
        Sampled.continuous(sourceGrid, NonSpatialAxes.empty, sourceData)
      )
    val pull =
      FramedAffine.between(targetFrame, sourceFrame)(Affine.identity[D2])
    val plan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          pull,
          Interpolation.Lanczos5,
          BoundaryPolicy.Reject
        )
      )
    val result = resamplingRight(plan.run(plan.newWorkspace()))

    for
      i <- 0 until targetGrid.shape(0)
      j <- 0 until targetGrid.shape(1)
    do
      val continuous =
        geometryRight(
          targetToSourceIndex(Vector(i.toDouble, j.toDouble))
        )
      val expected =
        independentLanczos2(
          sourceData,
          continuous(0),
          continuous(1),
          outside = 0.0
        )
      val actual =
        imageRight(result.image.valueAt(Vector(i, j)))
      assertEqualsDouble(actual, expected._1, 1e-12)
      assertEqualsDouble(expected._2, 1.0, 1e-12)
      assertEquals(
        imageRight(result.validity.at(Vector(i, j))),
        Validity.Full
      )
    assertEquals(plan.structure.materializedCoordinateCount, 0)

  test("Lanczos-5 normalizes constants and reports absolute support validity"):
    val sourceFrame = geometryRight(Frame.named[D2]("lanczos-boundary-source"))
    val targetFrame = geometryRight(Frame.named[D2]("lanczos-boundary-target"))
    val sourceGrid =
      geometryRight(
        Grid.in(sourceFrame)(Vector(12, 12), Affine.identity[D2])
      )
    val targetGrid =
      geometryRight(
        Grid.in(targetFrame)(
          Vector(1, 1),
          geometryRight(
            Affine.fromOriginSpacingDirection[D2](
              origin = Vector(-0.25, 5.5),
              spacing = Vector(1.0, 1.0),
              directionRowMajor = Vector(1.0, 0.0, 0.0, 1.0)
            )
          )
        )
      )
    val sourceData =
      NDArray.tabulate[Double](12, 12)((_, _) => 3.25)
    val source =
      imageRight(
        Sampled.continuous(sourceGrid, NonSpatialAxes.empty, sourceData)
      )
    val pull =
      FramedAffine.between(targetFrame, sourceFrame)(Affine.identity[D2])
    val plan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          pull,
          Interpolation.Lanczos5,
          BoundaryPolicy.Constant(3.25)
        )
      )
    val result = resamplingRight(plan.run(plan.newWorkspace()))
    val expected =
      independentLanczos2(sourceData, -0.25, 5.5, outside = 3.25)
    val actual = imageRight(result.image.valueAt(Vector(0, 0)))
    val validity =
      imageRight(result.validity.weights.valueAt(Vector(0, 0)))

    assertEqualsDouble(actual, 3.25, 1e-12)
    assertEqualsDouble(actual, expected._1, 1e-12)
    assertEqualsDouble(validity, expected._2, 1e-12)
    assert(validity > 0.0 && validity < 1.0)

  test("Lanczos-5 treats an unrepresentable finite stencil as outside"):
    val sourceFrame =
      geometryRight(Frame.named[D2]("lanczos-extreme-source"))
    val targetFrame =
      geometryRight(Frame.named[D2]("lanczos-extreme-target"))
    val sourceGrid =
      geometryRight(
        Grid.in(sourceFrame)(Vector(2, 2), Affine.identity[D2])
      )
    val targetGrid =
      geometryRight(
        Grid.in(targetFrame)(Vector(1, 1), Affine.identity[D2])
      )
    val source =
      imageRight(
        Sampled.continuous(
          sourceGrid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](2, 2)((i, j) => i.toDouble + j.toDouble)
        )
      )
    val pull =
      geometryRight(
        FramedAffine.translation(targetFrame, sourceFrame)(
          Double.MaxValue,
          0.0
        )
      )
    val constantPlan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          pull,
          Interpolation.Lanczos5,
          BoundaryPolicy.Constant(-7.0)
        )
      )
    val constant =
      resamplingRight(constantPlan.run(constantPlan.newWorkspace()))

    assertEqualsDouble(
      imageRight(constant.image.valueAt(Vector(0, 0))),
      -7.0,
      0.0
    )
    assertEquals(
      imageRight(constant.validity.at(Vector(0, 0))),
      Validity.Outside
    )

    val rejectPlan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          pull,
          Interpolation.Lanczos5,
          BoundaryPolicy.Reject
        )
      )
    rejectPlan.run(rejectPlan.newWorkspace()) match
      case Left(ResamplingError.OutsideSource(target, continuous)) =>
        assertEquals(target, Vector(0, 0))
        assert(continuous(0).isFinite)
        assert(continuous(0) > Int.MaxValue.toDouble)
      case Left(error) =>
        fail(s"unexpected rejection: ${error.message}")
      case Right(_) =>
        fail("an unrepresentable Lanczos stencil was sampled")

  test("Lanczos-5 reproduces a low-frequency analytic field"):
    val sourceFrame = geometryRight(Frame.named[D2]("lanczos-band-source"))
    val targetFrame = geometryRight(Frame.named[D2]("lanczos-band-target"))
    val sourceGrid =
      geometryRight(
        Grid.in(sourceFrame)(Vector(30, 28), Affine.identity[D2])
      )
    val targetGrid =
      geometryRight(
        Grid.in(targetFrame)(
          Vector(5, 5),
          geometryRight(
            Affine.fromOriginSpacingDirection[D2](
              origin = Vector(10.2, 9.35),
              spacing = Vector(1.0, 1.0),
              directionRowMajor = Vector(1.0, 0.0, 0.0, 1.0)
            )
          )
        )
      )
    def field(x: Double, y: Double): Double =
      2.0 +
        math.sin(2.0 * math.Pi * x / 48.0) +
        0.5 * math.cos(2.0 * math.Pi * y / 40.0)
    val source =
      imageRight(
        Sampled.continuous(
          sourceGrid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](30, 28)((i, j) =>
            field(i.toDouble, j.toDouble)
          )
        )
      )
    val pull =
      FramedAffine.between(targetFrame, sourceFrame)(Affine.identity[D2])
    val lanczosPlan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          pull,
          Interpolation.Lanczos5,
          BoundaryPolicy.Reject
        )
      )
    val linearPlan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          pull,
          Interpolation.Linear,
          BoundaryPolicy.Reject
        )
      )
    val lanczos =
      resamplingRight(lanczosPlan.run(lanczosPlan.newWorkspace()))
    val linear =
      resamplingRight(linearPlan.run(linearPlan.newWorkspace()))
    var lanczosMaximum = 0.0
    var linearMaximum = 0.0

    for
      i <- 0 until 5
      j <- 0 until 5
    do
      val expected = field(10.2 + i.toDouble, 9.35 + j.toDouble)
      lanczosMaximum =
        math.max(
          lanczosMaximum,
          math.abs(
            imageRight(lanczos.image.valueAt(Vector(i, j))) - expected
          )
        )
      linearMaximum =
        math.max(
          linearMaximum,
          math.abs(
            imageRight(linear.image.valueAt(Vector(i, j))) - expected
          )
        )

    assert(
      lanczosMaximum <= 0.0011,
      s"Lanczos analytic maximum error was $lanczosMaximum"
    )
    assert(
      lanczosMaximum < linearMaximum,
      s"Lanczos error $lanczosMaximum did not improve on linear $linearMaximum"
    )

  test("Lanczos-5 scan and run are identical for D3 plus time"):
    val sourceFrame = geometryRight(Frame.named[D3]("lanczos-scan-source"))
    val targetFrame = geometryRight(Frame.named[D3]("lanczos-scan-target"))
    val sourceGrid =
      geometryRight(
        Grid.in(sourceFrame)(Vector(14, 14, 14), Affine.identity[D3])
      )
    val targetGrid =
      geometryRight(
        Grid.in(targetFrame)(
          Vector(2, 2, 2),
          geometryRight(
            Affine.fromOriginSpacingDirection[D3](
              origin = Vector(5.25, 5.5, 5.75),
              spacing = Vector(1.0, 1.0, 1.0),
              directionRowMajor = Vector(
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0
              )
            )
          )
        )
      )
    val time = imageRight(Axis.create("time", 2, AxisKind.Time))
    val axes = imageRight(NonSpatialAxes.from(Vector(time)))
    val source =
      imageRight(
        Sampled.continuous(
          sourceGrid,
          axes,
          NDArray.tabulate[Double](14, 14, 14, 2)((i, j, k, t) =>
            math.sin(i.toDouble * 0.2) +
              math.cos(j.toDouble * 0.17) +
              k.toDouble * 0.03 +
              t.toDouble
          )
        )
      )
    val plan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          FramedAffine.between(targetFrame, sourceFrame)(
            Affine.identity[D3]
          ),
          Interpolation.Lanczos5,
          BoundaryPolicy.Reject
        )
      )
    val materialized =
      resamplingRight(plan.run(plan.newWorkspace()))
    val values = Array.fill(16)(Double.NaN)
    val weights = Array.fill(16)(Double.NaN)

    resamplingRight(
      plan.scan(
        plan.newWorkspace(),
        new ResamplingSink:
          def accept(
              outputLinearIndex: Int,
              value: Double,
              validityWeight: Double
          ): Unit =
            values(outputLinearIndex) = value
            weights(outputLinearIndex) = validityWeight
      )
    )

    val materializedValues = materialized.image.data.iterator.toVector
    val materializedWeights =
      materialized.validity.weights.data.iterator.toVector
    var index = 0
    while index < values.length do
      assertEqualsDouble(values(index), materializedValues(index), 0.0)
      assertEqualsDouble(
        weights(index),
        materializedWeights(index),
        0.0
      )
      index += 1

  test("D3 plus time uses rank-four physical indexing on a source view"):
    val frame = geometryRight(Frame.named[D3]("source-series"))
    val grid =
      geometryRight(
        Grid.in(frame)(Vector(2, 3, 2), Affine.identity[D3])
      )
    val time = imageRight(Axis.create("time", 2, AxisKind.Time))
    val axes = imageRight(NonSpatialAxes.from(Vector(time)))
    val canonical =
      NDArray.fromSeq(
        Shape(2, 3, 2, 2),
        (0 until 24).map(_.toDouble)
      )
    val sourceView = canonical.reverse(1)
    val source = imageRight(Sampled.continuous(grid, axes, sourceView))
    val pull = FramedAffine.identity(frame)
    val plan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          grid,
          pull,
          Interpolation.Nearest
        )
      )
    val first = resamplingRight(plan.run(plan.newWorkspace()))
    val second = resamplingRight(plan.run(plan.newWorkspace()))

    assert(!sourceView.isCanonicalLayout)
    assert(first.image.data.isCanonicalLayout)
    assertEquals(plan.structure.dataRank, 4)
    for
      i <- 0 until 2
      j <- 0 until 3
      k <- 0 until 2
      t <- 0 until 2
    do
      val index = Vector(i, j, k)
      val extra = Vector(t)
      val expected = imageRight(source.valueAt(index, extra))
      val actual = imageRight(first.image.valueAt(index, extra))
      val repeated = imageRight(second.image.valueAt(index, extra))
      assertEquals(actual, expected)
      assertEquals(repeated, expected)
      assertEquals(
        imageRight(first.validity.at(index, extra)),
        Validity.Full
      )

  test("primitive scan is value-and-validity equivalent to materialized run"):
    val sourceFrame = geometryRight(Frame.named[D3]("scan-source"))
    val targetFrame = geometryRight(Frame.named[D3]("scan-target"))
    val sourceGrid =
      geometryRight(
        Grid.in(sourceFrame)(
          Vector(3, 2, 2),
          geometryRight(
            Affine.fromOriginSpacingDirection[D3](
              origin = Vector(2.0, -1.0, 4.0),
              spacing = Vector(1.5, 2.0, 0.75),
              directionRowMajor = Vector(
                0.0, -1.0, 0.0,
                1.0, 0.0, 0.0,
                0.0, 0.0, 1.0
              )
            )
          )
        )
      )
    val targetGrid =
      geometryRight(
        Grid.in(targetFrame)(Vector(2, 2, 2), sourceGrid.indexToFrame)
      )
    val time = imageRight(Axis.create("time", 2, AxisKind.Time))
    val axes = imageRight(NonSpatialAxes.from(Vector(time)))
    val source =
      imageRight(
        Sampled.continuous(
          sourceGrid,
          axes,
          NDArray.tabulate[Double](3, 2, 2, 2)((i, j, k, t) =>
            1000.0 * i + 100.0 * j + 10.0 * k + t
          )
        )
      )
    val pull =
      geometryRight(
        FramedAffine.translation(
          targetFrame,
          sourceFrame
        )(0.25, -0.5, 0.125)
      )
    val plan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          pull,
          Interpolation.Linear,
          BoundaryPolicy.Constant(-7.0)
        )
      )
    val materialized =
      resamplingRight(plan.run(plan.newWorkspace()))
    val values = Array.fill(16)(Double.NaN)
    val weights = Array.fill(16)(Double.NaN)
    var visits = 0

    resamplingRight(
      plan.scan(
        plan.newWorkspace(),
        new ResamplingSink:
          def accept(
              outputLinearIndex: Int,
              value: Double,
              validityWeight: Double
          ): Unit =
            values(outputLinearIndex) = value
            weights(outputLinearIndex) = validityWeight
            visits += 1
      )
    )

    assertEquals(visits, 16)
    for
      i <- 0 until 2
      j <- 0 until 2
      k <- 0 until 2
      t <- 0 until 2
    do
      val output = (((i * 2) + j) * 2 + k) * 2 + t
      assertEqualsDouble(
        values(output),
        imageRight(materialized.image.valueAt(Vector(i, j, k), Vector(t))),
        1e-12
      )
      assertEqualsDouble(
        weights(output),
        imageRight(
          materialized.validity.weights.valueAt(
            Vector(i, j, k),
            Vector(t)
          )
        ),
        1e-12
      )

  test("nearest label resampling introduces no new labels"):
    val frame = geometryRight(Frame.named[D2]("categorical-labels"))
    val grid =
      geometryRight(
        Grid.in(frame)(Vector(3, 3), Affine.identity[D2])
      )
    val labels =
      NDArray.fromSeq(
        Shape(3, 3),
        Vector(
          1, 1, 2,
          1, 3, 2,
          4, 4, 2
        )
      )
    val source =
      imageRight(
        Sampled.categorical(grid, NonSpatialAxes.empty, labels)
      )
    val plan =
      resamplingRight(
        ResamplingPlan.nearest(
          source,
          grid,
          FramedAffine.between(frame, frame)(
            geometryRight(
              Affine.fromRowMajor[D2](
                Vector(
                  1.0, 0.0, 0.4,
                  0.0, 1.0, 0.4,
                  0.0, 0.0, 1.0
                )
              )
            )
          )
        )
      )
    val result = resamplingRight(plan.run(plan.newWorkspace()))
    val sourceLabels = Set(1, 2, 3, 4)

    for
      i <- 0 until 3
      j <- 0 until 3
    do
      assert(
        sourceLabels.contains(
          imageRight(result.image.valueAt(Vector(i, j)))
        )
      )

  test("reject boundary reports the target and mapped source indices"):
    val sourceFrame = geometryRight(Frame.named[D2]("reject-source"))
    val targetFrame = geometryRight(Frame.named[D2]("reject-target"))
    val sourceGrid =
      geometryRight(
        Grid.in(sourceFrame)(Vector(2, 2), Affine.identity[D2])
      )
    val targetGrid =
      geometryRight(
        Grid.in(targetFrame)(Vector(1, 1), Affine.identity[D2])
      )
    val source =
      imageRight(
        Sampled.continuous(
          sourceGrid,
          NonSpatialAxes.empty,
          NDArray.zeros[Double](2, 2)
        )
      )
    val pull =
      geometryRight(
        FramedAffine.translation(targetFrame, sourceFrame)(-0.25, 0.0)
      )
    val plan =
      resamplingRight(
        ResamplingPlan.affine(
          source,
          targetGrid,
          pull,
          Interpolation.Linear
        )
      )

    plan.run(plan.newWorkspace()) match
      case Left(
            ResamplingError.OutsideSource(
              Vector(0, 0),
              Vector(x, y)
            )
          ) =>
        assertEqualsDouble(x, -0.25, 1e-12)
        assertEqualsDouble(y, 0.0, 1e-12)
      case Left(error) => fail(s"unexpected error: ${error.message}")
      case Right(_)    => fail("reject boundary accepted outside support")

  test("total ranks above four fail before plan compilation"):
    val frame = geometryRight(Frame.named[D3]("rank-five"))
    val grid =
      geometryRight(
        Grid.in(frame)(Vector(2, 2, 2), Affine.identity[D3])
      )
    val time = imageRight(Axis.create("time", 2, AxisKind.Time))
    val channel = imageRight(Axis.create("channel", 2, AxisKind.Channel))
    val axes = imageRight(NonSpatialAxes.from(Vector(time, channel)))
    val shape =
      Shape
        .from(Vector(2, 2, 2, 2, 2))
        .fold(error => fail(error.getMessage), identity)
    val source =
      imageRight(
        Sampled.continuous(
          grid,
          axes,
          NDArray.zeros[Double, AnyRank](shape)
        )
      )

    ResamplingPlan.affine(
      source,
      grid,
      FramedAffine.identity(frame),
      Interpolation.Nearest
    ) match
      case Left(ResamplingError.UnsupportedDataRank(3, 5, 4)) => ()
      case Left(error) => fail(s"unexpected error: ${error.message}")
      case Right(_)    => fail("rank-five data acquired a production plan")

  test("continuous interpolation is unavailable for categorical semantics"):
    val nearestErrors = typeCheckErrors(
      """
        import image4s.*
        import ravel.*
        import image4s.geometry.*
        import reframe4s.lie.FramedAffine
        import reframe4s.resample.*
        def nearest[
          F <: Frame[D2],
          T <: Frame[D2],
          S <: SampleSpace[F, D2],
          R <: AnyRank
        ](
          image: Sampled[S, Int, Categorical, R],
          target: Grid[T, D2],
          pull: FramedAffine[T, F, D2]
        ) =
          ResamplingPlan.nearest(
            image,
            target,
            pull
          )
      """
    )
    val linearErrors = typeCheckErrors(
      """
        import image4s.*
        import ravel.*
        import image4s.geometry.*
        import reframe4s.lie.FramedAffine
        import reframe4s.resample.*
        def linear[
          F <: Frame[D2],
          T <: Frame[D2],
          S <: SampleSpace[F, D2],
          R <: AnyRank
        ](
          image: Sampled[S, Int, Categorical, R],
          target: Grid[T, D2],
          pull: FramedAffine[T, F, D2]
        ) =
          ResamplingPlan.affine(
            image,
            target,
            pull,
            Interpolation.Linear
          )
      """
    )
    val lanczosErrors = typeCheckErrors(
      """
        import image4s.*
        import ravel.*
        import image4s.geometry.*
        import reframe4s.lie.FramedAffine
        import reframe4s.resample.*
        def lanczos[
          F <: Frame[D2],
          T <: Frame[D2],
          S <: SampleSpace[F, D2],
          R <: AnyRank
        ](
          image: Sampled[S, Int, Categorical, R],
          target: Grid[T, D2],
          pull: FramedAffine[T, F, D2]
        ) =
          ResamplingPlan.affine(
            image,
            target,
            pull,
            Interpolation.Lanczos5
          )
      """
    )

    assertEquals(nearestErrors, Nil)
    assert(linearErrors.nonEmpty)
    assert(lanczosErrors.nonEmpty)

  private def independentLanczos2(
      data: NDArray[Double, ravel.Rank[2]],
      x: Double,
      y: Double,
      outside: Double
  ): (Double, Double) =
    val xWeights = independentLanczosWeights(x)
    val yWeights = independentLanczosWeights(y)
    val absoluteTotal =
      xWeights.iterator.map(pair => math.abs(pair._2)).sum *
        yWeights.iterator.map(pair => math.abs(pair._2)).sum
    var value = 0.0
    var absoluteInside = 0.0
    xWeights.foreach { case (i, wx) =>
      yWeights.foreach { case (j, wy) =>
        val weight = wx * wy
        if math.abs(weight) > 1e-15 then
          if
            i >= 0 && i < data.shape(0) &&
            j >= 0 && j < data.shape(1)
          then
            value += weight * data(i, j)
            absoluteInside += math.abs(weight)
          else value += weight * outside
      }
    }
    value -> math.max(
      0.0,
      math.min(1.0, absoluteInside / absoluteTotal)
    )

  private def independentLanczosWeights(
      coordinate: Double
  ): Vector[(Int, Double)] =
    val first = math.floor(coordinate).toInt - 4
    val raw =
      Vector.tabulate(10) { tap =>
        val index = first + tap
        val distance = coordinate - index.toDouble
        val absolute = math.abs(distance)
        val weight =
          if absolute >= 5.0 then 0.0
          else if distance == 0.0 then 1.0
          else if distance == math.rint(distance) then 0.0
          else
            val radians = math.Pi * distance
            val windowRadians = radians / 5.0
            (math.sin(radians) / radians) *
              (math.sin(windowRadians) / windowRadians)
        index -> weight
      }
    val sum = raw.iterator.map(_._2).sum
    raw.map(pair => pair._1 -> (pair._2 / sum))

  private def assertValidity(
      actual: Validity,
      expected: Sample[Double]
  ): Unit =
    (actual, expected.validity) match
      case (Validity.Full, Validity.Full) => ()
      case (Validity.Outside, Validity.Outside) => ()
      case (
            Validity.Partial(actualWeight),
            Validity.Partial(expectedWeight)
          ) =>
        assertEqualsDouble(
          actualWeight.value,
          expectedWeight.value,
          1e-12
        )
      case (found, wanted) =>
        fail(s"validity mismatch: found $found, expected $wanted")

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
