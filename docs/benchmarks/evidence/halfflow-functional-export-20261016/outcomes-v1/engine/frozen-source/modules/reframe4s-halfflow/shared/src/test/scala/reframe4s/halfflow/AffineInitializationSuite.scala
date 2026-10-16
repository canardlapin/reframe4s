package reframe4s.halfflow

import image4s.geometry.{Affine, D3}
import gale.linalg.DMat
import reframe4s.halfflow.internal.*

class AffineInitializationSuite extends munit.FunSuite:
  sealed trait Work
  sealed trait Fixed
  sealed trait Moving

  test("a supplied affine is split into swapped inverse half maps") {
    val grid = GridSpec.identity(Vector(19, 18, 17))
    val frames = makeFrames(grid)
    val images = analyticImages(grid, frames, knownTransform)
    val initial = AffineInitializer
      .supplied(images._1, images._2, frames._1, Affine.fromRowMajor[D3](Vector.tabulate(16)(i => knownTransform(i / 4, i % 4))).toOption.get)
      .fold(error => fail(error.message), identity)
    val swapped = AffineInitializer
      .supplied(images._2, images._1, frames._1, Affine.fromRowMajor[D3](Vector.tabulate(16)(i => knownTransform(i / 4, i % 4))).toOption.get.inverse)
      .fold(error => fail(error.message), identity)

    assertEquals(initial.diagnostics.origin, AffineInitializationOrigin.Supplied)
    assertFieldsClose(
      initial.midpoint.fixed.dense.fold(error => fail(error.message), identity).forward,
      swapped.midpoint.moving.dense.fold(error => fail(error.message), identity).forward,
      2e-9
    )
    assertFieldsClose(
      initial.midpoint.moving.dense.fold(error => fail(error.message), identity).forward,
      swapped.midpoint.fixed.dense.fold(error => fail(error.message), identity).forward,
      2e-9
    )

    val result = initial.midpoint.result.fold(error => fail(error.message), identity)
    assertMappedPoint(result.forward, 9, 8, 8, knownTransform, 2e-8)
    assertMappedPoint(result.backward, 9, 8, 8, Affine.fromRowMajor[D3](Vector.tabulate(16)(i => knownTransform(i / 4, i % 4))).toOption.get.inverse.matrix, 2e-8)
  }

  test("bidirectional robust affine initialization recovers an analytic physical transform") {
    val grid = GridSpec.identity(Vector(31, 30, 29))
    val frames = makeFrames(grid)
    val images = analyticImages(grid, frames, knownTransform)
    val config = AffineInitializationConfig
      .make(
        coarseShrink = 3,
        fineShrink = 1,
        smoothingSigmaMm = 1.0,
        maximumSamplesPerImage = 10000,
        rigidSweepsPerLevel = 6,
        affineSweepsPerLevel = 6,
        translationStepMm = 2.0,
        rotationStepRadians = 2.0 * math.Pi / 180.0,
        logScaleStep = 0.02,
        shearStep = 0.015
      )
      .fold(error => fail(error.message), identity)
    val result = AffineInitializer
      .estimate(images._1, images._2, frames._1, config)
      .fold(error => fail(error.message), identity)

    assertEquals(result.diagnostics.origin, AffineInitializationOrigin.Estimated)
    assert(result.diagnostics.finalValue < result.diagnostics.initialValue)
    assert(result.diagnostics.overlap > 0.75)
    assert(result.diagnostics.determinant > 0.8)
    val landmarks = Vector(
      Vector(8.0, 9.0, 7.0),
      Vector(22.0, 9.0, 18.0),
      Vector(12.0, 22.0, 20.0),
      Vector(24.0, 21.0, 8.0)
    )
    val error = landmarkRms(result.affine.transform.matrix, knownTransform, landmarks)
    assert(
      error < 0.8,
      s"affine landmark RMS error=$error, actual=${Vector.tabulate(4)(r => Vector.tabulate(4)(c => result.affine.transform.matrix(r, c)))}, expected=${Vector.tabulate(4)(r => Vector.tabulate(4)(c => knownTransform(r, c)))}, diagnostics=${result.diagnostics}"
    )
  }

  test("estimated initialization is equivariant when fixed and moving are swapped") {
    val grid = GridSpec.identity(Vector(27, 26, 25))
    val frames = makeFrames(grid)
    val images = analyticImages(grid, frames, knownTransform)
    val config = AffineInitializationConfig
      .make(
        coarseShrink = 3,
        fineShrink = 2,
        maximumSamplesPerImage = 4000,
        rigidSweepsPerLevel = 4,
        affineSweepsPerLevel = 3,
        translationStepMm = 2.0,
        rotationStepRadians = 2.0 * math.Pi / 180.0,
        logScaleStep = 0.02,
        shearStep = 0.015
      )
      .fold(error => fail(error.message), identity)
    val forward = AffineInitializer
      .estimate(images._1, images._2, frames._1, config)
      .fold(error => fail(error.message), identity)
    val reverse = AffineInitializer
      .estimate(images._2, images._1, frames._1, config)
      .fold(error => fail(error.message), identity)
    val landmarks = Vector(
      Vector(7.0, 8.0, 6.0),
      Vector(19.0, 8.0, 17.0),
      Vector(11.0, 19.0, 18.0),
      Vector(21.0, 18.0, 7.0)
    )
    val error = landmarkRms(forward.affine.transform.matrix, reverse.affine.transform.inverse.matrix, landmarks)
    assert(error < 2e-6, s"swap inverse RMS error=$error")
    assertFieldsClose(
      forward.midpoint.fixed.dense.fold(error => fail(error.message), identity).forward,
      reverse.midpoint.moving.dense.fold(error => fail(error.message), identity).forward,
      2e-6
    )
    assertFieldsClose(
      forward.midpoint.moving.dense.fold(error => fail(error.message), identity).forward,
      reverse.midpoint.fixed.dense.fold(error => fail(error.message), identity).forward,
      2e-6
    )
  }

  test("constant images fail at the typed support boundary") {
    val grid = GridSpec.identity(Vector(12, 12, 12))
    val frames = makeFrames(grid)
    val values = PrimitiveBuffers.fillConst[Double](grid.nVoxels, 1.0)
    val volume = NeuroVol.fromLinear[Double](values, grid.toNeuroSpace, "constant")
    val fixed = RegistrationImage.make(frames._2, volume).fold(error => fail(error.message), identity)
    val moving = RegistrationImage.make(frames._3, volume).fold(error => fail(error.message), identity)
    assert(AffineInitializer.estimate(fixed, moving, frames._1).isLeft)
  }

  private def knownTransform: DMat =
    val angle = 3.0 * math.Pi / 180.0
    val c = math.cos(angle)
    val s = math.sin(angle)
    DMat.dense(4, 4, (Vector(
        Vector(1.015 * c, -0.990 * s + 0.010, 0.008, 1.15),
        Vector(1.015 * s, 0.990 * c, -0.006, -0.75),
        Vector(0.004, 0.007, 1.010, 0.55),
        Vector(0.0, 0.0, 0.0, 1.0)
      )).flatten)

  private def makeFrames(
      grid: GridSpec
  ): (RegistrationFrame[Work], RegistrationFrame[Fixed], RegistrationFrame[Moving]) =
    (
      RegistrationFrame[Work](SpatialDomainId("affine-work"), grid),
      RegistrationFrame[Fixed](SpatialDomainId("affine-fixed"), grid),
      RegistrationFrame[Moving](SpatialDomainId("affine-moving"), grid)
    )

  private def analyticImages(
      grid: GridSpec,
      frames: (RegistrationFrame[Work], RegistrationFrame[Fixed], RegistrationFrame[Moving]),
      fixedToMoving: DMat
  ): (RegistrationImage[Fixed], RegistrationImage[Moving]) =
    val inverse = fixedToMoving.solve(DMat.eye(fixedToMoving.cols)).left.map(_.getMessage).fold(reason => fail(reason), identity)
    val fixedValues = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val movingValues = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val affine = grid.affine
    val nx = grid.shape(0)
    val ny = grid.shape(1)
    var index = 0
    while index < grid.nVoxels do
      val x = (index % nx).toDouble
      val yz = index / nx
      val y = (yz % ny).toDouble
      val z = (yz / ny).toDouble
      val world = apply(affine, Vector(x, y, z))
      val fixedWorld = apply(inverse, world)
      fixedValues(index) = signal(world(0), world(1), world(2), grid)
      movingValues(index) = signal(fixedWorld(0), fixedWorld(1), fixedWorld(2), grid)
      index += 1
    val fixedVolume = NeuroVol.fromLinear[Double](fixedValues, grid.toNeuroSpace, "fixed")
    val movingVolume = NeuroVol.fromLinear[Double](movingValues, grid.toNeuroSpace, "moving")
    (
      RegistrationImage.make(frames._2, fixedVolume).fold(error => fail(error.message), identity),
      RegistrationImage.make(frames._3, movingVolume).fold(error => fail(error.message), identity)
    )

  private def signal(x: Double, y: Double, z: Double, grid: GridSpec): Double =
    val cx = 0.48 * (grid.shape(0) - 1).toDouble
    val cy = 0.51 * (grid.shape(1) - 1).toDouble
    val cz = 0.46 * (grid.shape(2) - 1).toDouble
    val main = 105.0 * gaussian(x, y, z, cx, cy, cz, 7.2, 6.2, 6.8)
    val first = 42.0 * gaussian(x, y, z, cx - 4.8, cy + 2.6, cz + 3.2, 2.4, 3.0, 2.1)
    val second = 31.0 * gaussian(x, y, z, cx + 5.2, cy - 3.8, cz - 2.5, 3.1, 2.0, 2.8)
    main + first + second + 3.0 * math.sin(0.19 * x + 0.11 * y) * math.exp(-0.02 * ((z - cz) * (z - cz)))

  private def gaussian(
      x: Double,
      y: Double,
      z: Double,
      cx: Double,
      cy: Double,
      cz: Double,
      sx: Double,
      sy: Double,
      sz: Double
  ): Double =
    math.exp(
      -0.5 * (
        (x - cx) * (x - cx) / (sx * sx) +
          (y - cy) * (y - cy) / (sy * sy) +
          (z - cz) * (z - cz) / (sz * sz)
      )
    )

  private def assertMappedPoint[A, B](
      pull: DensePull[A, B],
      x: Int,
      y: Int,
      z: Int,
      expectedMatrix: DMat,
      tolerance: Double
  ): Unit =
    val index = x + pull.from.grid.shape(0) * (y + pull.from.grid.shape(1) * z)
    val expected = apply(expectedMatrix, Vector(x.toDouble, y.toDouble, z.toDouble))
    assertEqualsDouble(pull.sourceCoordinates.linearComponent(index, 0), expected(0), tolerance)
    assertEqualsDouble(pull.sourceCoordinates.linearComponent(index, 1), expected(1), tolerance)
    assertEqualsDouble(pull.sourceCoordinates.linearComponent(index, 2), expected(2), tolerance)

  private def assertFieldsClose[A, B, C, D](
      left: DensePull[A, B],
      right: DensePull[C, D],
      tolerance: Double
  ): Unit =
    assertEquals(left.from.grid.nVoxels, right.from.grid.nVoxels)
    var index = 0
    while index < left.from.grid.nVoxels do
      var component = 0
      while component < 3 do
        assertEqualsDouble(
          left.sourceCoordinates.linearComponent(index, component),
          right.sourceCoordinates.linearComponent(index, component),
          tolerance
        )
        component += 1
      index += 1

  private def landmarkRms(actual: DMat, expected: DMat, landmarks: Vector[Vector[Double]]): Double =
    var sum = 0.0
    var index = 0
    while index < landmarks.length do
      val a = apply(actual, landmarks(index))
      val b = apply(expected, landmarks(index))
      val dx = a(0) - b(0)
      val dy = a(1) - b(1)
      val dz = a(2) - b(2)
      sum += dx * dx + dy * dy + dz * dz
      index += 1
    math.sqrt(sum / landmarks.length.toDouble)

  private def apply(matrix: DMat, point: Vector[Double]): Vector[Double] =
    Vector(
      matrix(0, 0) * point(0) + matrix(0, 1) * point(1) + matrix(0, 2) * point(2) + matrix(0, 3),
      matrix(1, 0) * point(0) + matrix(1, 1) * point(1) + matrix(1, 2) * point(2) + matrix(1, 3),
      matrix(2, 0) * point(0) + matrix(2, 1) * point(1) + matrix(2, 2) * point(2) + matrix(2, 3)
    )
