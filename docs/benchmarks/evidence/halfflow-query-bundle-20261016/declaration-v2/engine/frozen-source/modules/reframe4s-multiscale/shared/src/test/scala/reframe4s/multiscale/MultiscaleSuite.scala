package reframe4s.multiscale

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex

import scala.collection.mutable.ArrayBuffer

final class MultiscaleSuite extends munit.FunSuite:
  test("an awkward anisotropic oblique tower preserves both physical endpoints"):
    val frame = geometry(Frame.named[D3]("oblique"))
    val affine = geometry(
      Affine.fromRowMajor[D3](
        Vector(
          2.0, 0.5, 0.0, 10.0,
          0.0, 3.0, 0.25, -4.0,
          0.1, 0.0, 4.0, 7.0,
          0.0, 0.0, 0.0, 1.0
        )
      )
    )
    val native: Grid[frame.type, D3] =
      geometry(Grid.forFrame(frame)(Vector(9, 10, 8), affine))
    val schedule = scaleSchedule(
      Vector(
        ScaleLevel(
          scale(Vector(3, 4, 3), Vector(2.0, 1.5, 1.0)),
          "coarse"
        ),
        ScaleLevel(
          scale(Vector(1, 1, 1), Vector(0.0, 0.0, 0.0)),
          "native"
        )
      )
    )
    val tower: GridTower[frame.type, D3, String] =
      multiscale(
        GridTower.build[frame.type, D3, String](native, schedule)
      )
    val coarse = tower.levels.head.grid
    val nativeFirst = geometry(native.pointAt(geometry(LatticeIndex.of[D3](0, 0, 0))))
    val coarseFirst = geometry(coarse.pointAt(geometry(LatticeIndex.of[D3](0, 0, 0))))
    val nativeLast =
      geometry(native.pointAt(geometry(LatticeIndex.of[D3](8, 9, 7))))
    val coarseLast =
      geometry(coarse.pointAt(geometry(LatticeIndex.of[D3](3, 3, 3))))

    assertEquals(coarse.shape, Vector(4, 4, 4))
    assert(!coarse.sameRuntimeOwnerAs(native))
    assert(coarse.frame eq native.frame)
    assert(tower.levels.last.grid eq native)
    assertCoordinates(coarseFirst.coordinates, nativeFirst.coordinates)
    assertCoordinates(coarseLast.coordinates, nativeLast.coordinates)

  test("continuation propagates a level failure and stops before later levels"):
    val frame = geometry(Frame.named[D3]("continuation"))
    val native: Grid[frame.type, D3] =
      geometry(
        Grid.forFrame(frame)(
          Vector(9, 9, 9),
          Affine.identity[D3]
        )
      )
    val schedule = scaleSchedule(
      Vector(
        ScaleLevel(scale(Vector(4, 4, 4), zeros), "coarse"),
        ScaleLevel(scale(Vector(2, 2, 2), zeros), "middle"),
        ScaleLevel(scale(Vector(1, 1, 1), zeros), "native")
      )
    )
    val tower: GridTower[frame.type, D3, String] =
      multiscale(
        GridTower.build[frame.type, D3, String](native, schedule)
      )
    val events = ArrayBuffer.empty[String]
    val transfer = new Transfer[Int, frame.type, D3]:
      def restrict(
          value: Int,
          from: Grid[frame.type, D3],
          to: Grid[frame.type, D3]
      ): Either[TransferError, Int] =
        Left(TransferError.Unsupported(TransferOperation.Restrict))

      def prolong(
          value: Int,
          from: Grid[frame.type, D3],
          to: Grid[frame.type, D3]
      ): Either[TransferError, Int] =
        events +=
          s"prolong:${from.shape.mkString("x")}:${to.shape.mkString("x")}"
        Right(value + 10)

    val solver = new LevelSolver[Int, frame.type, D3, String]:
      def solve(
          level: GridLevel[frame.type, D3, String],
          initial: Int
      ): Either[LevelFailure, Int] =
        events += s"solve:${level.ordinal}:$initial"
        if level.ordinal == 1 then
          Left(LevelFailure.Reported("middle failed"))
        else Right(initial + 1)

    val result = Continuation.run(tower, 0, transfer, solver)

    result match
      case Left(MultiscaleError.Solve(1, LevelFailure.Reported(message))) =>
        assertEquals(message, "middle failed")
      case other =>
        fail(s"expected the middle-level failure, got $other")
    assertEquals(events.length, 3)
    assertEquals(events.head, "solve:0:0")
    assert(events(1).startsWith("prolong:"))
    assertEquals(events(2), "solve:1:11")

  private val zeros = Vector(0.0, 0.0, 0.0)

  private def scale(
      shrink: Vector[Int],
      smoothing: Vector[Double]
  ): ScaleSpec[D3] =
    multiscale(ScaleSpec.create[D3](shrink, smoothing))

  private def scaleSchedule(
      levels: Vector[ScaleLevel[D3, String]]
  ): ScaleSchedule[D3, String] =
    multiscale(ScaleSchedule.create(levels))

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def multiscale[A](value: Either[MultiscaleError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def assertCoordinates(
      actual: Vector[Double],
      expected: Vector[Double]
  ): Unit =
    actual.indices.foreach(index =>
      assertEqualsDouble(actual(index), expected(index), 1e-11)
    )
