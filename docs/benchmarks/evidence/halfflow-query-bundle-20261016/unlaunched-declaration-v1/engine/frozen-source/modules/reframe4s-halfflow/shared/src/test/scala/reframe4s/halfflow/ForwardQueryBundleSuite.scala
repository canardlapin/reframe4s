package reframe4s.halfflow

import image4s.geometry.{Affine, D3, Point}
import java.io.{ByteArrayInputStream, DataInputStream}
import reframe4s.halfflow.internal.*

class ForwardQueryBundleSuite extends munit.FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def affine(scale: Double, shift: Double): Affine[D3] =
    right(Affine.fromRowMajor[D3](Vector(scale, 0.0, 0.0, shift, 0.0, 1.0, 0.0, 0.0,
      0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)))
  private def fixture(iterations: Int = 8): ForwardMidpointQuery[Unit, Unit, Unit] =
    val work = RegistrationFrame[Unit](SpatialDomainId("work-μ"), GridSpec.identity(Vector(7, 7, 7)))
    val fixed = RegistrationFrame[Unit](SpatialDomainId("fixed"), work.grid.withGeometry(Vector(9, 6, 8), affine(0.7, -1.3)))
    val moving = RegistrationFrame[Unit](SpatialDomainId("moving"), GridSpec.identity(Vector(8, 9, 6)))
    def nonlinear(coefficient: Double): DensePull[Unit, Unit] =
      val n = work.grid.nVoxels
      val values = new Array[Double](3 * n)
      work.grid.worldCoords.zipWithIndex.foreach: (p, i) =>
        values(i) = p(0) + coefficient * p(0) * p(0)
        values(i + n) = p(1)
        values(i + 2 * n) = p(2)
      right(DensePull.make(work, work, DenseVectorField.fromLegacyPlanar(work.grid, values, DenseVectorFieldKind.SourceCoordinates)))
    val state = right(ForwardMidpoint.make(
      right(ForwardMidpointArm.make(nonlinear(0.04), right(AffineIso.make(work, fixed, affine(1.1, 2.0).inverse)))),
      right(ForwardMidpointArm.make(nonlinear(0.02), right(AffineIso.make(work, moving, affine(0.9, -3.0)))))))
    val seed = DensePull.identity(work)
    val masked = right(DensePull.make(work, work, seed.sourceCoordinates,
      FieldValidity.copyMask(Array.tabulate(work.grid.nVoxels)(_ != 0))))
    right(ForwardMidpointQuery.compile(state, masked, seed, right(ResidualQueryConfig.make(maximumIterations = iterations))))

  private def reseal(bytes: Array[Byte]): Array[Byte] =
    var a = 1
    var b = 0
    for i <- 0 until bytes.length - 4 do
      a = (a + (bytes(i) & 255)) % 65521
      b = (b + a) % 65521
    putInt(bytes, bytes.length - 4, (b << 16) | a)
    bytes
  private def putInt(bytes: Array[Byte], offset: Int, value: Int): Unit =
    for i <- 0 until 4 do bytes(offset + i) = (value >>> (24 - 8 * i)).toByte
  private def putDouble(bytes: Array[Byte], offset: Int, value: Double): Unit =
    val bits = java.lang.Double.doubleToLongBits(value)
    for i <- 0 until 8 do bytes(offset + i) = (bits >>> (56 - 8 * i)).toByte
  private def offsets(bytes: Array[Byte]): (Int, Int) =
    val buffer = new ByteArrayInputStream(bytes)
    val in = new DataInputStream(buffer)
    assertEquals(in.readInt(), 0x48465142)
    assertEquals(in.readInt(), 1)
    val _ = in.readUTF()
    val config = bytes.length - buffer.available()
    val _ = in.skipBytes(24)
    for _ <- 0 until 3 do
      val _ = in.readInt()
      val _ = in.readUTF()
      val _ = in.skipBytes(140)
    (config, bytes.length - buffer.available())

  test("bundle round trip preserves all values, cached affine directions, settings and owner aliases"):
    val original = fixture()
    val bytes = right(ForwardQueryBundle.encode(original))
    val restored = right(ForwardQueryBundle.decode(bytes))
    assertEquals(right(ForwardQueryBundle.encode(restored)).toVector, bytes.toVector)
    assertEquals(restored.fixedToWork.rowMajor, original.fixedToWork.rowMajor)
    assertEquals(restored.movingToWork.rowMajor, original.movingToWork.rowMajor)
    assertEquals(restored.fixedFrame.grid.shape, Vector(9, 6, 8))
    assertEquals(restored.state.work.domain.value, "work-μ")
    assert(restored.state.work.canonical.sameRuntimeOwnerAs(restored.fixedFrame.canonical))
    assert(!restored.state.work.canonical.sameRuntimeOwnerAs(restored.movingFrame.canonical))
    assert(!restored.fixedFrame.canonical.sameRuntimeOwnerAs(original.fixedFrame.canonical))
    assert(!restored.fixedInverse.seed.validity.contains(0))

  test("reloaded nonlinear affine endpoint composition agrees with an independent between-node value"):
    val restored = right(ForwardQueryBundle.decode(right(ForwardQueryBundle.encode(fixture()))))
    val x = 2.37
    val fx = (x + 0.04 * (4.0 + 0.37 * 5.0) - 2.0) / 1.1
    val mx = 0.9 * (x + 0.02 * (4.0 + 0.37 * 5.0)) - 3.0
    val point = right(Point.in[D3](restored.fixedFrame.canonical)(fx, 1.2, 3.4))
    val forward = restored.fixedToMoving
    val backward = restored.movingToFixed
    val result = right(forward(point))
    assertEqualsDouble(result.coordinates(0), mx, 1e-8)
    val back = right(forward.andThen(backward)(point))
    for axis <- 0 until 3 do assertEqualsDouble(back.coordinates(axis), point.coordinates(axis), 1e-8)

  test("reloaded failures retain missing seed support, bounded nonconvergence and live ownership checks"):
    val original = fixture(0)
    val restored = right(ForwardQueryBundle.decode(right(ForwardQueryBundle.encode(original))))
    val point = right(Point.in[D3](restored.fixedFrame.canonical)(2.5, 1.2, 3.4))
    val failure = restored.evaluate(ExportDirection.FixedToMoving, point, restored.newWorkspace()).swap.toOption.get
    assertEquals(failure.failure.reason, ResidualQueryError.IterationLimit)
    assert(restored.fixedToMoving(point).isLeft)
    val zero = right(Point.in[D3](restored.fixedFrame.canonical)(-2.0 / 1.1, 0.0, 0.0))
    assertEquals(restored.evaluate(ExportDirection.FixedToMoving, zero, restored.newWorkspace())
      .swap.toOption.get.failure.reason, ResidualQueryError.UnsupportedSeed)
    val old = right(Point.in[D3](original.fixedFrame.canonical)(2.5, 1.2, 3.4))
    assertEquals(restored.evaluate(ExportDirection.FixedToMoving, old, restored.newWorkspace())
      .swap.toOption.get.stage, ForwardQueryStage.Frame)

  test("truncation, corruption, trailing data and byte limits are rejected"):
    val bytes = right(ForwardQueryBundle.encode(fixture()))
    for length <- Vector(0, 4, 8, 100, bytes.length - 1) do assert(ForwardQueryBundle.decode(bytes.take(length)).isLeft)
    val changed = bytes.clone()
    changed(changed.length / 2) = (changed(changed.length / 2) ^ 1).toByte
    assert(ForwardQueryBundle.decode(changed).isLeft)
    assert(ForwardQueryBundle.decode(reseal(bytes ++ Array[Byte](0))).isLeft)
    assert(ForwardQueryBundle.decode(bytes, bytes.length - 1).isLeft)
    assert(ForwardQueryBundle.encode(fixture(), 100).isLeft)

  test("well-checksummed unknown policy/version and invalid budgets are rejected"):
    val bytes = right(ForwardQueryBundle.encode(fixture()))
    val version = bytes.clone()
    putInt(version, 4, 2)
    assert(ForwardQueryBundle.decode(reseal(version)).isLeft)
    val policy = bytes.clone()
    policy(10) = 'X'.toByte
    assert(ForwardQueryBundle.decode(reseal(policy)).isLeft)
    val invalid = bytes.clone()
    putInt(invalid, offsets(bytes)._1, 9)
    assert(ForwardQueryBundle.decode(reseal(invalid)).isLeft)

  test("well-checksummed inconsistent inverse directions and nonfinite coordinates are rejected"):
    val bytes = right(ForwardQueryBundle.encode(fixture()))
    val operators = offsets(bytes)._2
    val changed = bytes.clone()
    putDouble(changed, operators + 128 + 24, 17.0)
    assert(ForwardQueryBundle.decode(reseal(changed)).isLeft)
    val nonfinite = bytes.clone()
    putDouble(nonfinite, operators + 512, Double.NaN)
    assert(ForwardQueryBundle.decode(reseal(nonfinite)).isLeft)

  test("well-checksummed unsupported masks and folded forward cells cannot bypass checked restoration"):
    val bytes = right(ForwardQueryBundle.encode(fixture()))
    val fields = offsets(bytes)._2 + 512
    val n = 343
    val invalid = bytes.clone()
    invalid(fields + 24 * n) = 2
    assert(ForwardQueryBundle.decode(reseal(invalid)).isLeft)
    val folded = bytes.clone()
    for i <- 0 until n do putDouble(folded, fields + 8 * i, -(i % 7).toDouble)
    assert(ForwardQueryBundle.decode(reseal(folded)).isLeft)
