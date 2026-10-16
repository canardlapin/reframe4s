package reframe4s.multiscale

import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape

final class SupportAwarePyramid3Suite extends munit.FunSuite:
  test("records width units and the complete-affine voxel PSF approximation"):
    val sigma = psf(
      IsotropicGaussianPsf3.declared(
        2.0,
        GaussianWidthUnit.SigmaMillimetres
      )
    )
    val fwhm = psf(
      IsotropicGaussianPsf3.declared(
        2.0 * math.sqrt(2.0 * math.log(2.0)),
        GaussianWidthUnit.FwhmMillimetres
      )
    )
    assertEqualsDouble(sigma.sigmaMillimetres, 2.0, 0.0)
    assertEqualsDouble(fwhm.sigmaMillimetres, 1.0, 1e-15)

    val frame = geometry(Frame.named[D3]("voxel-psf"))
    val affine =
      geometry(
        Affine.fromRowMajor[D3](
          Vector(
            2.0, 0.5, 0.0, 11.0,
            0.0, 3.0, 0.25, -7.0,
            0.1, 0.0, -4.0, 2.0,
            0.0, 0.0, 0.0, 1.0
          )
        )
      )
    val grid = geometry(Grid.forFrame(frame)(Vector(3, 3, 3), affine))
    val estimate = IsotropicGaussianPsf3.voxelCellApproximation(grid)
    val linearSquared =
      2.0 * 2.0 + 0.5 * 0.5 +
        3.0 * 3.0 + 0.25 * 0.25 +
        0.1 * 0.1 + 4.0 * 4.0
    assertEqualsDouble(
      estimate.sigmaMillimetres,
      math.sqrt(linearSquared / 36.0),
      1e-15
    )
    estimate.provenance match
      case PsfProvenance3.VoxelCellTraceEquivalent(linear) =>
        assertEquals(linear.size, 9)
        assertEqualsDouble(linear(1), 0.5, 0.0)
        assertEqualsDouble(linear(8), -4.0, 0.0)
      case other => fail(s"unexpected PSF provenance $other")

  test("clamps negative required blur to an exact no-filter path"):
    val shape = Vector(5, 4, 3)
    val frame = geometry(Frame.named[D3]("no-deconvolution"))
    val grid =
      geometry(
        Grid.forFrame(frame)(
          shape,
          Affine.identity[D3]
        )
      )
    val data =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) => i * 100.0 + j * 10.0 + k
      }
    val source = continuous(grid, data)
    val support = NDArray.fill[Double, Rank[3]](Shape(5, 4, 3), 1.0)
    val tower = nativeTower(grid)
    val config =
      pyramidConfig(
        declaredSigma(2.0),
        Vector(declaredSigma(1.0))
      )
    val pyramid = build(source, support, tower, config)
    val level = pyramid.levels.head

    assertEquals(
      level.image.data.iterator.toVector,
      data.iterator.toVector
    )
    assert(level.support.data.iterator.forall(_ == 1.0))
    assertEqualsDouble(level.provenance.addedSigmaMillimetres, 0.0, 0.0)
    assert(level.provenance.negativeRequiredVarianceClamped)
    assertEquals(level.provenance.kernelTapCount, 1)
    assert(
      !level.provenance.materializations.contains(
        PreparationMaterialization3.PhysicalGaussianConvolution
      )
    )

  test("evaluates an isotropic world Gaussian through a sheared affine"):
    val shape = Vector(15, 15, 15)
    val frame = geometry(Frame.named[D3]("sheared-world-gaussian"))
    val affine =
      geometry(
        Affine.fromRowMajor[D3](
          Vector(
            1.2, 0.4, 0.1, 5.0,
            0.2, -1.1, 0.3, -3.0,
            0.0, 0.15, 1.4, 8.0,
            0.0, 0.0, 0.0, 1.0
          )
        )
      )
    val grid = geometry(Grid.forFrame(frame)(shape, affine))
    val center = 7
    val data =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) =>
          if i == center && j == center && k == center then 1.0
          else 0.0
      }
    val support =
      NDArray.fill[Double, Rank[3]](Shape(15, 15, 15), 1.0)
    val pyramid =
      build(
        continuous(grid, data),
        support,
        nativeTower(grid),
        pyramidConfig(
          declaredSigma(0.0),
          Vector(declaredSigma(1.4))
        )
      )
    val output = pyramid.levels.head.image.data
    val peak = output(center, center, center)
    val offsets = Vector((1, 0, 0), (0, 1, 0), (1, -1, 0), (0, 1, -1))
    offsets.foreach { case (i, j, k) =>
      val x = 1.2 * i + 0.4 * j + 0.1 * k
      val y = 0.2 * i - 1.1 * j + 0.3 * k
      val z = 0.15 * j + 1.4 * k
      val expected = math.exp(-0.5 * (x * x + y * y + z * z) / (1.4 * 1.4))
      assertEqualsDouble(
        output(center + i, center + j, center + k) / peak,
        expected,
        2e-15
      )
    }
    assertEquals(
      pyramid.levels.head.provenance.physicalGaussianPolicy,
      PhysicalGaussianPolicy3.NativeGridFullAffine
    )

  test("zero padding and normalized convolution retain explicit support"):
    val shape = Vector(11, 11, 11)
    val frame = geometry(Frame.named[D3]("missing-support"))
    val grid =
      geometry(
        Grid.forFrame(frame)(shape, Affine.identity[D3])
      )
    val center = 5
    val data =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) =>
          if i == center && j == center && k == center then 7.0
          else Double.NaN
      }
    val support =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) =>
          if i == center && j == center && k == center then 1.0
          else 0.0
      }
    val pyramid =
      build(
        continuous(grid, data),
        support,
        nativeTower(grid),
        pyramidConfig(
          declaredSigma(0.0),
          Vector(declaredSigma(1.0))
        )
      )
    val level = pyramid.levels.head
    assert(level.image.data.iterator.forall(_.isFinite))
    assertEqualsDouble(level.image.data(center, center, center), 7.0, 1e-14)
    assertEqualsDouble(level.image.data(center + 1, center, center), 7.0, 1e-14)
    assert(level.support.data(center, center, center) > 0.0)
    assert(level.support.data(center, center, center) < 1.0)
    assertEqualsDouble(level.support.data(0, 0, 0), 0.0, 0.0)
    assertEqualsDouble(level.image.data(0, 0, 0), 0.0, 0.0)
    assertEquals(level.provenance.nativePositiveSupportCount, 1L)
    assertEquals(level.provenance.levelFullSupportCount, 0L)

    val fullSource =
      continuous(
        grid,
        NDArray.fill[Double, Rank[3]](Shape(11, 11, 11), 4.0)
      )
    val fullSupport =
      NDArray.fill[Double, Rank[3]](Shape(11, 11, 11), 1.0)
    val bounded =
      build(
        fullSource,
        fullSupport,
        nativeTower(grid),
        pyramidConfig(
          declaredSigma(0.0),
          Vector(declaredSigma(1.0))
        )
      ).levels.head
    assertEqualsDouble(bounded.image.data(0, 0, 0), 4.0, 1e-14)
    assert(bounded.support.data(0, 0, 0) < 1.0)
    assertEquals(
      bounded.provenance.supportPolicy,
      SupportPropagationPolicy3.ZeroPaddedNormalizedConvolution
    )

  test("builds every level directly from native data"):
    val shape = Vector(13, 13, 13)
    val frame = geometry(Frame.named[D3]("native-level-source"))
    val grid =
      geometry(
        Grid.forFrame(frame)(shape, Affine.identity[D3])
      )
    val center = 6
    val data =
      NDArray.tabulate[Double](shape(0), shape(1), shape(2)) {
        (i, j, k) =>
          if i == center && j == center && k == center then 1.0
          else 0.0
      }
    val source = continuous(grid, data)
    val support =
      NDArray.fill[Double, Rank[3]](Shape(13, 13, 13), 1.0)
    val tower = twoLevelTower(grid)
    val pyramid =
      build(
        source,
        support,
        tower,
        pyramidConfig(
          declaredSigma(0.0),
          Vector(declaredSigma(2.0), declaredSigma(1.0))
        )
      )
    val fine = pyramid.levels(1).image.data
    val observedRatio = fine(center + 1, center, center) / fine(center, center, center)
    assertEqualsDouble(observedRatio, math.exp(-0.5), 2e-15)
    assert(pyramid.levels.forall(_.provenance.builtDirectlyFromNative))
    assert(
      pyramid.levels.head.provenance.materializations.contains(
        PreparationMaterialization3.AffineLevelSampling
      )
    )
    assert(
      !pyramid.levels(1).provenance.materializations.contains(
        PreparationMaterialization3.AffineLevelSampling
      )
    )

  test("rejects malformed or empty support and concurrent workspace use"):
    val frame = geometry(Frame.named[D3]("invalid-support"))
    val grid =
      geometry(
        Grid.forFrame(frame)(Vector(3, 3, 3), Affine.identity[D3])
      )
    val source =
      continuous(
        grid,
        NDArray.fill[Double, Rank[3]](Shape(3, 3, 3), 1.0)
      )
    val tower = nativeTower(grid)
    val config =
      pyramidConfig(
        declaredSigma(0.0),
        Vector(declaredSigma(0.0))
      )
    val workspace = SupportAwarePyramidWorkspace3.create
    val empty = NDArray.fill[Double, Rank[3]](Shape(3, 3, 3), 0.0)
    assertEquals(
      SupportAwarePyramid3.build(source, empty, tower, config, workspace),
      Left(MultiscaleError.EmptySourceSupport)
    )
    val wrongShape = NDArray.fill[Double, Rank[3]](Shape(2, 3, 3), 1.0)
    assertEquals(
      SupportAwarePyramid3.build(
        source,
        wrongShape,
        tower,
        config,
        workspace
      ),
      Left(
        MultiscaleError.SupportShapeMismatch(
          Vector(3, 3, 3),
          Vector(2, 3, 3)
        )
      )
    )
    assert(workspace.acquire())
    val full = NDArray.fill[Double, Rank[3]](Shape(3, 3, 3), 1.0)
    assertEquals(
      SupportAwarePyramid3.build(source, full, tower, config, workspace),
      Left(MultiscaleError.SupportAwarePyramidWorkspaceInUse)
    )
    workspace.release()

  private def declaredSigma(value: Double): IsotropicGaussianPsf3 =
    psf(
      IsotropicGaussianPsf3.declared(
        value,
        GaussianWidthUnit.SigmaMillimetres
      )
    )

  private def pyramidConfig(
      source: IsotropicGaussianPsf3,
      targets: Vector[IsotropicGaussianPsf3]
  ): SupportAwarePyramidConfig3 =
    SupportAwarePyramidConfig3
      .create(source, targets)
      .fold(error => fail(error.message), identity)

  private def nativeTower[F <: Frame[D3]](
      grid: Grid[F, D3]
  ): GridTower[F, D3, String] =
    val native =
      ScaleSpec
        .create[D3](Vector(1, 1, 1), Vector(0.0, 0.0, 0.0))
        .fold(error => fail(error.message), identity)
    val schedule =
      ScaleSchedule
        .create(Vector(ScaleLevel(native, "native")))
        .fold(error => fail(error.message), identity)
    GridTower
      .build(grid, schedule)
      .fold(error => fail(error.message), identity)

  private def twoLevelTower[F <: Frame[D3]](
      grid: Grid[F, D3]
  ): GridTower[F, D3, String] =
    val coarse =
      ScaleSpec
        .create[D3](Vector(2, 2, 2), Vector(0.0, 0.0, 0.0))
        .fold(error => fail(error.message), identity)
    val native =
      ScaleSpec
        .create[D3](Vector(1, 1, 1), Vector(0.0, 0.0, 0.0))
        .fold(error => fail(error.message), identity)
    val schedule =
      ScaleSchedule
        .create(
          Vector(
            ScaleLevel(coarse, "coarse"),
            ScaleLevel(native, "native")
          )
        )
        .fold(error => fail(error.message), identity)
    GridTower
      .build(grid, schedule)
      .fold(error => fail(error.message), identity)

  private def continuous[F <: Frame[D3]](
      grid: Grid[F, D3],
      data: NDArray[Double, Rank[3]]
  ) =
    Sampled
      .continuous(grid, NonSpatialAxes.empty, data)
      .fold(error => fail(error.message), identity)

  private def build[F <: Frame[D3], C](
      source: image4s.ContinuousImage[
        ? <: image4s.SampleSpace[F, D3],
        Double,
        Rank[3]
      ],
      support: NDArray[Double, Rank[3]],
      tower: GridTower[F, D3, C],
      config: SupportAwarePyramidConfig3
  ): SupportAwarePyramid3[F, C] =
    SupportAwarePyramid3
      .build(
        source,
        support,
        tower,
        config,
        SupportAwarePyramidWorkspace3.create
      )
      .fold(error => fail(error.message), identity)

  private def psf(
      value: Either[MultiscaleError, IsotropicGaussianPsf3]
  ): IsotropicGaussianPsf3 =
    value.fold(error => fail(error.message), identity)

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)
