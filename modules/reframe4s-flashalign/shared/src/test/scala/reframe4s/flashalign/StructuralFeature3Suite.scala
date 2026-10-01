package reframe4s.flashalign

import reframe4s.spectral.SpectralShape3

final class StructuralFeature3Suite extends munit.FunSuite:
  test("normalized-gradient tensors use physical spacing and ignore polarity"):
    val lattice = capture(
      CaptureLattice3.create(
        SpectralShape3(7, 7, 7),
        originXMillimetres = -4.0,
        originYMillimetres = 2.0,
        originZMillimetres = 10.0,
        spacingXMillimetres = 2.0,
        spacingYMillimetres = 3.0,
        spacingZMillimetres = 4.0
      )
    )
    val values = new Array[Double](lattice.elementCount)
    var x = 0
    while x < lattice.shape.x do
      var y = 0
      while y < lattice.shape.y do
        var z = 0
        while z < lattice.shape.z do
          values(lattice.index(x, y, z)) =
            2.0 * lattice.worldX(x) - 3.0 * lattice.worldY(y) +
              4.0 * lattice.worldZ(z)
          z += 1
        y += 1
      x += 1
    val support = Array.fill(lattice.elementCount)(1.0)
    val positive = tensor(lattice, values, support)
    val negative = tensor(lattice, values.map(-_), support)
    val center = lattice.index(3, 3, 3)
    val expected = Array(4.0 / 29.0, 9.0 / 29.0, 16.0 / 29.0, -6.0 / 29.0, 8.0 / 29.0, -12.0 / 29.0)
    var component = 0
    while component < 6 do
      assertEqualsDouble(positive.component(component, center), expected(component), 2e-15)
      assertEqualsDouble(negative.component(component, center), expected(component), 2e-15)
      component += 1
    assertEqualsDouble(positive.supportAt(center), 1.0, 0.0)
    assertEqualsDouble(positive.supportAt(lattice.index(0, 3, 3)), 0.0, 0.0)

  test("rotation moves tensor locations and rotates tensor components"):
    val lattice = capture(
      CaptureLattice3.create(
        SpectralShape3(7, 7, 7),
        originXMillimetres = -3.0,
        originYMillimetres = -3.0,
        originZMillimetres = -3.0
      )
    )
    val components = Array.fill(6)(new Array[Double](lattice.elementCount))
    val support = new Array[Double](lattice.elementCount)
    val sourceIndex = lattice.index(4, 3, 3)
    components(0)(sourceIndex) = 1.0
    support(sourceIndex) = 1.0
    val source = capture(NormalizedGradientTensor3.create(lattice, components, support))
    val rotation = capture(
      CaptureRotation3.fromEulerXyzRadians(0.0, 0.0, math.Pi / 2.0)
    )
    val rotated = capture(
      StructuralTensorRotation3.rotated(
        source,
        rotation,
        0.0,
        0.0,
        0.0,
        minimumInterpolatedSupport = 1e-9
      )
    )
    val destination = lattice.index(3, 4, 3)
    assertEqualsDouble(rotated.supportAt(destination), 1.0, 2e-15)
    assertEqualsDouble(rotated.component(0, destination), 0.0, 2e-15)
    assertEqualsDouble(rotated.component(1, destination), 1.0, 2e-15)
    assertEqualsDouble(rotated.component(3, destination), 0.0, 2e-15)
    assert(rotated.supportAt(sourceIndex) < 1e-12)
    assertEqualsDouble(rotation.determinant, 1.0, 2e-15)

  test("capture lattice and scalar volume validation is explicit"):
    assertEquals(
      CaptureLattice3.create(SpectralShape3(2, 4, 4)),
      Left(StructuralCaptureError.InvalidExtent(0, 2))
    )
    val lattice = capture(CaptureLattice3.create(SpectralShape3(3, 3, 3)))
    CaptureScalarVolume3.create(
      lattice,
      Array.fill(lattice.elementCount)(0.0),
      Array.fill(lattice.elementCount)(1.2)
    ) match
      case Left(StructuralCaptureError.InvalidSupport(0, 1.2)) => ()
      case other => fail(s"expected support failure, got $other")

  private def tensor(
      lattice: CaptureLattice3,
      values: Array[Double],
      support: Array[Double]
  ): NormalizedGradientTensor3 =
    val volume = capture(CaptureScalarVolume3.create(lattice, values, support))
    capture(NormalizedGradientTensor3.fromScalar(volume, 1e-12))

  private def capture[A](value: Either[StructuralCaptureError, A]): A =
    value.fold(error => fail(error.message), identity)
