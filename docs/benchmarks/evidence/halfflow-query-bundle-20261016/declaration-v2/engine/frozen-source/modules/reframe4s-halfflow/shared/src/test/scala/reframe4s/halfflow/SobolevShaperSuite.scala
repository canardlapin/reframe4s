package reframe4s.halfflow

import gale.linalg.DMat
import gale.linalg.MutableDVec
import reframe4s.halfflow.internal.*

class SobolevShaperSuite extends munit.FunSuite:
  sealed trait Work

  test("masked Helmholtz application matches an independent no-flux stencil") {
    val grid = GridSpec(
      Vector(5, 4, 3),
      DMat.dense(4, 4, (Vector(
          Vector(1.0, 0.0, 0.0, 0.0),
          Vector(0.0, 2.0, 0.0, 0.0),
          Vector(0.0, 0.0, 3.0, 0.0),
          Vector(0.0, 0.0, 0.0, 1.0)
        )).flatten)
    )
    val active = PrimitiveBuffers.fill[Boolean](grid.nVoxels)(true)
    active(0) = false
    val operator = new MaskedHelmholtzOperator(grid, active, lengthMm = 1.7)
    val input = gale.linalg.DVec.tabulate(grid.nVoxels)(i => math.sin(0.17 * i) + 0.03 * i)
    val output = MutableDVec.zeros(grid.nVoxels)
    operator.applyTo(input, output)

    val expected = naiveApply(input, grid, active, 1.7)
    var index = 0
    while index < grid.nVoxels do
      assertEqualsDouble(output(index), expected(index), 1e-13)
      index += 1
  }

  test("power-two solve matches the discrete Neumann cosine response") {
    val side = 12
    val grid = GridSpec.identity(Vector(side, side, side))
    val frame = RegistrationFrame[Work](SpatialDomainId("sobolev-frequency"), grid)
    val values = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    val frequency = 2
    var index = 0
    while index < grid.nVoxels do
      val x = index % side
      values(index) = math.cos(math.Pi * frequency.toDouble * (x.toDouble + 0.5) / side.toDouble)
      index += 1
    val raw = velocity(frame, values)
    val length = 1.3
    val config = SobolevConfig
      .make(
        SmoothLengthMm(length),
        power = 2,
        relativeTolerance = 1e-11,
        maximumIterations = 100,
        maximumDisplacementMm = 10.0,
        maximumStrain = 10.0
      )
      .fold(error => fail(error.message), identity)
    val result = SobolevShaper.shape(raw, FieldValidity.All, config).fold(error => fail(error.message), identity)
    val lambda = 1.0 + 2.0 * length * length * (1.0 - math.cos(math.Pi * frequency.toDouble / side.toDouble))
    val scale = 1.0 / (lambda * lambda)
    index = 0
    while index < grid.nVoxels do
      assertEqualsDouble(result.velocity.field.linearComponent(index, 0), values(index) * scale, 2e-9)
      index += 1
    assert(result.diagnostics.maximumRelativeResidual <= 1.01e-11)
  }

  test("constant fields are preserved, masks project exactly, and caps are enforced") {
    val grid = GridSpec.identity(Vector(9, 8, 7))
    val frame = RegistrationFrame[Work](SpatialDomainId("sobolev-mask"), grid)
    val values = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    val valid = PrimitiveBuffers.fill[Boolean](grid.nVoxels)(true)
    var index = 0
    while index < grid.nVoxels do
      values(index) = 4.0 + 0.5 * (index % grid.shape(0))
      values(index + grid.nVoxels) = -2.0
      values(index + 2 * grid.nVoxels) = 1.0
      if index % 11 == 0 then valid(index) = false
      index += 1
    val config = SobolevConfig
      .make(
        SmoothLengthMm(0.8),
        relativeTolerance = 1e-9,
        maximumIterations = 120,
        maximumDisplacementMm = 0.7,
        maximumStrain = 0.08
      )
      .fold(error => fail(error.message), identity)
    val result = SobolevShaper
      .shape(velocity(frame, values), FieldValidity.copyMask(valid), config)
      .fold(error => fail(error.message), identity)
    index = 0
    while index < grid.nVoxels do
      if !valid(index) then
        assertEqualsDouble(result.velocity.field.linearComponent(index, 0), 0.0, 0.0)
        assertEqualsDouble(result.velocity.field.linearComponent(index, 1), 0.0, 0.0)
        assertEqualsDouble(result.velocity.field.linearComponent(index, 2), 0.0, 0.0)
      index += 1
    assert(result.diagnostics.maximumNormAfterCapMm <= 0.7 * (1.0 + 1e-12))
    assert(result.diagnostics.maximumStrainAfterCap <= 0.08 * (1.0 + 1e-12))
  }

  test("in-place LM storage reuse matches disjoint Sobolev storage") {
    val grid = GridSpec.identity(Vector(8, 7, 6))
    val frame = RegistrationFrame[Work](SpatialDomainId("sobolev-alias"), grid)
    val values = PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3)
    var index = 0
    while index < values.length do
      values(index) = math.sin(0.03 * index) + 0.2 * math.cos(0.07 * index)
      index += 1
    val config = SobolevConfig
      .make(
        SmoothLengthMm(0.9),
        relativeTolerance = 1e-9,
        maximumIterations = 100,
        maximumDisplacementMm = 10.0,
        maximumStrain = 10.0
      )
      .fold(error => fail(error.message), identity)
    val reference = SobolevShaper
      .shape(velocity(frame, values), FieldValidity.All, config)
      .fold(error => fail(error.message), identity)

    val local = LocalLmBuffer(frame)
    index = 0
    while index < grid.nVoxels do
      local.step(index) = values(index)
      local.step(index + grid.nVoxels) = values(index + grid.nVoxels)
      local.step(index + 2 * grid.nVoxels) = values(index + 2 * grid.nVoxels)
      local.valid(index) = true
      index += 1
    val aliasedRaw = velocity(frame, local.step)
    val actual = SobolevShaper
      .shapeInto(
        aliasedRaw,
        FieldValidity.copyMask(local.valid),
        config,
        SobolevWorkspace(frame),
        SobolevBuffer.reusing(local)
      )
      .fold(error => fail(error.message), identity)
    index = 0
    while index < values.length do
      val component = index / grid.nVoxels
      val voxel = index % grid.nVoxels
      assertEqualsDouble(
        actual.velocity.field.linearComponent(voxel, component),
        reference.velocity.field.linearComponent(voxel, component),
        1e-12
      )
      index += 1
    assertEquals(actual.diagnostics.totalIterations, reference.diagnostics.totalIterations)
  }

  private def velocity[A](frame: RegistrationFrame[A], values: Array[Double]): Velocity[A] =
    val field = DenseVectorField.fromLegacyPlanar(
      frame.grid,
      values,
      DenseVectorFieldKind.Displacement
    )
    Velocity.make(frame, field).fold(error => fail(error.message), identity)

  private def naiveApply(
      input: gale.linalg.DVec,
      grid: GridSpec,
      active: Array[Boolean],
      length: Double
  ): Array[Double] =
    val spacing = grid.spacing
    val alpha = Array.tabulate(3)(axis => length * length / (spacing(axis) * spacing(axis)))
    val offsets = Array(1, grid.shape(0), grid.shape(0) * grid.shape(1))
    val output = Array.ofDim[Double](grid.nVoxels)
    var index = 0
    while index < grid.nVoxels do
      if !active(index) then output(index) = input(index)
      else
        val x = index % grid.shape(0)
        val yz = index / grid.shape(0)
        val y = yz % grid.shape(1)
        val z = yz / grid.shape(1)
        val coordinate = Array(x, y, z)
        var value = input(index)
        var axis = 0
        while axis < 3 do
          if coordinate(axis) > 0 && active(index - offsets(axis)) then
            value += alpha(axis) * (input(index) - input(index - offsets(axis)))
          val limit = if axis == 0 then grid.shape(0) else if axis == 1 then grid.shape(1) else grid.shape(2)
          if coordinate(axis) + 1 < limit && active(index + offsets(axis)) then
            value += alpha(axis) * (input(index) - input(index + offsets(axis)))
          axis += 1
        output(index) = value
      index += 1
    output
