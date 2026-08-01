package reframe4s.halfflow

import reframe4s.halfflow.internal.*

class LocalLmSuite extends munit.FunSuite:
  test("multi-channel LM is the accuracy-first default") {
    val config = LocalLmConfig.make().fold(error => fail(error.message), identity)
    assertEquals(config.variant, LocalLmVariant.MultiChannel)
  }

  test("in-place median is exact on odd, even, repeated, and partial inputs") {
    assertEqualsDouble(InPlaceMedian(Array(9.0, 1.0, 5.0), 3), 5.0, 0.0)
    assertEqualsDouble(InPlaceMedian(Array(8.0, 2.0, 6.0, 4.0), 4), 5.0, 0.0)
    assertEqualsDouble(InPlaceMedian(Array(3.0, 3.0, 3.0, 3.0, 99.0), 4), 3.0, 0.0)
    assertEqualsDouble(InPlaceMedian(Array(7.0, 1.0, 5.0, -1000.0), 3), 5.0, 0.0)
  }

  sealed trait W

  test("T1 features are invariant to positive common gain and offset on stable support"):
    val grid = GridSpec.identity(Vector(17, 17, 17))
    val frame = Frame[W](SpatialDomainId("work"), grid)
    val source = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val transformed = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    var z = 0
    while z < 17 do
      var y = 0
      while y < 17 do
        var x = 0
        while x < 17 do
          val index = x + 17 * y + 17 * 17 * z
          val value = 0.03 * x * x - 0.02 * y + 0.01 * z * z + math.sin(0.21 * x + 0.13 * y)
          source(index) = value
          transformed(index) = 3.5 * value + 11.0
          x += 1
        y += 1
      z += 1
    val config = right(
      T1FeatureConfig.make(
        Vector(FeatureRadiusMm(1.0), FeatureRadiusMm(2.0)),
        minimumValidWindowFraction = 0.8,
        minimumActiveVoxels = 32
      )
    )
    val first = right(T1Features.compute(frame, source, FieldValidity.All, config))
    val second = right(T1Features.compute(frame, transformed, FieldValidity.All, config))
    val center = 8 + 17 * 8 + 17 * 17 * 8
    var channel = 0
    while channel < first.channels do
      val scalar = channel * grid.nVoxels + center
      val gradient = channel * 3 * grid.nVoxels
      assert(first.valid(scalar) && second.valid(scalar))
      assertEqualsDouble(first.values(scalar), second.values(scalar), 2e-12)
      assertEqualsDouble(first.gradients(gradient + center), second.gradients(gradient + center), 2e-12)
      assertEqualsDouble(
        first.gradients(gradient + grid.nVoxels + center),
        second.gradients(gradient + grid.nVoxels + center),
        2e-12
      )
      channel += 1

  test("rank-one LM matches its closed form and tighter damping shrinks the step"):
    val grid = GridSpec.identity(Vector(5, 5, 5))
    val frame = Frame[W](SpatialDomainId("work"), grid)
    val fixed = feature(frame, Vector(0.2), Vector((1.0, 2.0, -1.0)))
    val moving = feature(frame, Vector(0.0), Vector((1.0, 2.0, -1.0)))
    val looseConfig = right(
      LocalLmConfig.make(damping = 0.5, minimumActiveVoxels = 1, variant = LocalLmVariant.RankOne(0))
    )
    val tightConfig = right(
      LocalLmConfig.make(damping = 2.0, minimumActiveVoxels = 1, variant = LocalLmVariant.RankOne(0))
    )
    val loose = right(LocalLm.solve(fixed, moving, looseConfig))
    val tight = right(LocalLm.solve(fixed, moving, tightConfig))
    val epsilon = 0.02
    val weight = 1.0 / math.sqrt(0.2 * 0.2 + epsilon * epsilon)
    val scale = -weight * 0.2 / (0.5 + weight * 6.0)
    val center = 2 + 5 * 2 + 25 * 2
    assertEqualsDouble(loose.rawVelocity.field.linearComponent(center, 0), scale, 1e-12)
    assertEqualsDouble(loose.rawVelocity.field.linearComponent(center, 1), 2.0 * scale, 1e-12)
    assertEqualsDouble(loose.rawVelocity.field.linearComponent(center, 2), -scale, 1e-12)
    assert(loose.summary.maximumRawVelocityMm > tight.summary.maximumRawVelocityMm)
    assertEquals(loose.summary.failedSolves, 0)

  test("multi-channel LM satisfies the original damped system and shaped prediction oracle"):
    val grid = GridSpec.identity(Vector(5, 5, 5))
    val frame = Frame[W](SpatialDomainId("work"), grid)
    val residuals = Vector(0.1, -0.2, 0.3)
    val axes = Vector((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0))
    val fixed = feature(frame, residuals, axes)
    val moving = feature(frame, Vector.fill(3)(0.0), axes)
    val config = right(
      LocalLmConfig.make(damping = 0.5, minimumActiveVoxels = 1, variant = LocalLmVariant.MultiChannel)
    )
    val result = right(LocalLm.solve(fixed, moving, config))
    val center = 2 + 5 * 2 + 25 * 2
    var channel = 0
    while channel < 3 do
      val epsilon = 0.1 * math.abs(residuals(channel))
      val weight = 1.0 / math.sqrt(residuals(channel) * residuals(channel) + epsilon * epsilon)
      val actual = result.rawVelocity.field.linearComponent(center, channel)
      val residual = (weight + 0.5) * actual + weight * residuals(channel)
      assertEqualsDouble(residual, 0.0, 2e-12)
      channel += 1

    val predicted = right(
      LocalLm.predictedDrop(fixed, moving, result.rawVelocity, result.rawValidity, result.model)
    )
    val step = result.rawVelocity.field.copyLegacyPlanar
    val reference = independentPrediction(residuals, result.model.epsilonPerChannel, step, center, grid.nVoxels)
    assertEqualsDouble(predicted, reference, 2e-12)
    assert(predicted > 0.0)

    val scale = 1e-6
    val tiny = scaledVelocity(frame, result.rawVelocity, scale)
    val tinyPrediction = right(LocalLm.predictedDrop(fixed, moving, tiny, result.rawValidity, result.model))
    val finiteDifference =
      (result.model.value - shiftedEnergy(residuals, result.model.epsilonPerChannel, step, center, grid.nVoxels, scale)) /
        scale
    assertEqualsDouble(tinyPrediction / scale, finiteDifference, 2e-6)

  test("candidate objective keeps the reference support fixed"):
    val grid = GridSpec.identity(Vector(5, 5, 5))
    val frame = Frame[W](SpatialDomainId("work"), grid)
    val fixed = feature(frame, Vector(0.2, -0.1), Vector((1.0, 0.0, 0.0), (0.0, 1.0, 0.0)))
    val moving = feature(frame, Vector(0.0, 0.0), Vector((1.0, 0.0, 0.0), (0.0, 1.0, 0.0)))
    val config = right(
      LocalLmConfig.make(damping = 0.5, minimumActiveVoxels = 1, variant = LocalLmVariant.MultiChannel)
    )
    val model = right(LocalLm.solve(fixed, moving, config)).model
    val unchanged = right(LocalLm.candidateValue(fixed, moving, fixed, moving, model))
    assertEqualsDouble(unchanged, model.value, 1e-15)

    val unsupported = feature(frame, Vector(0.2, -0.1), Vector((1.0, 0.0, 0.0), (0.0, 1.0, 0.0)))
    unsupported.valid(2 + 5 * 2 + 25 * 2) = false
    LocalLm.candidateValue(fixed, moving, unsupported, moving, model) match
      case Left(_: RegistrationError.InsufficientSupport) => ()
      case Left(error) => fail(s"unexpected error: ${error.message}")
      case Right(value) => fail(s"candidate with missing reference support was accepted: $value")

  private def feature(
      frame: Frame[W],
      channelValues: Vector[Double],
      channelGradients: Vector[(Double, Double, Double)]
  ): T1FeatureVolume[W] =
    val n = frame.grid.nVoxels
    val channels = channelValues.length
    val values = PrimitiveBuffers.ofSize[Double](n * channels)
    val gradients = PrimitiveBuffers.ofSize[Double](n * channels * 3)
    val valid = PrimitiveBuffers.ofSize[Boolean](n * channels)
    var channel = 0
    while channel < channels do
      val gradient = channelGradients(channel)
      var index = 0
      while index < n do
        values(channel * n + index) = channelValues(channel)
        gradients(channel * 3 * n + index) = gradient._1
        gradients(channel * 3 * n + n + index) = gradient._2
        gradients(channel * 3 * n + 2 * n + index) = gradient._3
        valid(channel * n + index) = true
        index += 1
      channel += 1
    right(
      T1FeatureVolume.make(
        frame,
        Vector.tabulate(channels)(index => index.toDouble + 1.0),
        Vector.fill(channels)(VoxelWindowRadius(1, 1, 1)),
        Vector.fill(channels)(0.1),
        values,
        gradients,
        valid
      )
    )

  private def independentPrediction(
      residuals: Vector[Double],
      epsilons: Vector[Double],
      step: Array[Double],
      index: Int,
      n: Int
  ): Double =
    var change = 0.0
    var channel = 0
    while channel < residuals.length do
      val weight = 1.0 / math.sqrt(residuals(channel) * residuals(channel) + epsilons(channel) * epsilons(channel))
      val projection = step(index + channel * n)
      change += weight * residuals(channel) * projection + 0.5 * weight * projection * projection
      channel += 1
    -change

  private def shiftedEnergy(
      residuals: Vector[Double],
      epsilons: Vector[Double],
      step: Array[Double],
      index: Int,
      n: Int,
      scale: Double
  ): Double =
    var energy = 0.0
    var channel = 0
    while channel < residuals.length do
      val shifted = residuals(channel) + scale * step(index + channel * n)
      energy += math.sqrt(shifted * shifted + epsilons(channel) * epsilons(channel)) - epsilons(channel)
      channel += 1
    energy

  private def scaledVelocity(
      frame: Frame[W],
      source: Velocity[W],
      scale: Double
  ): Velocity[W] =
    val output =
      ravel.NDArray.tabulate[Double](
        frame.grid.shape.x,
        frame.grid.shape.y,
        frame.grid.shape.z,
        3
      ) { (x, y, z, component) =>
        scale * source.field(x, y, z, component)
      }
    right(
      Velocity.make(
        frame,
        DenseVectorField(
          frame.grid,
          output,
          DenseVectorFieldKind.Displacement
        )
      )
    )

  private def right[L, R](value: Either[L, R]): R =
    value match
      case Right(result) => result
      case Left(error) => fail(error.toString)
