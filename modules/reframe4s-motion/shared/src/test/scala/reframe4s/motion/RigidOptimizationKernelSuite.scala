package reframe4s.motion

import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import reframe4s.core.MapError
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.Point
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError
import reframe4s.lie.Twist6

final class RigidOptimizationKernelSuite extends munit.FunSuite:
  test("stencil controls reject invalid counts, bins, and gamma"):
    assertEquals(
      RigidStencilControl.create(0, 1, 1, 1, 0.5),
      Left(RigidKernelError.InvalidSampleCount(0))
    )
    assertEquals(
      RigidStencilControl.create(8, 2, 0, 2, 0.5),
      Left(RigidKernelError.InvalidBinCount(1, 0))
    )
    assertEquals(
      RigidStencilControl.create(7, 2, 2, 2, 0.5),
      Left(RigidKernelError.SampleCountBelowBinCount(7, 8))
    )
    RigidStencilControl.create(8, 2, 2, 2, Double.NaN) match
      case Left(RigidKernelError.InvalidStencilGamma(value)) =>
        assert(value.isNaN)
      case other =>
        fail(s"expected InvalidStencilGamma(NaN), obtained $other")

  test(
    "information stencil covers physical bins and survives grid reparameterization"
  ):
    val firstFrame = geometry(Frame.named[D3]("stencil-first"))
    val secondFrame = geometry(Frame.named[D3]("stencil-second"))
    val firstAffine = Affine.identity[D3]
    val secondAffine =
      geometry(
        Affine.fromRowMajor[D3](
          Vector(
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, -1.0, 3.0,
            1.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 1.0
          )
        )
      )
    val firstGrid =
      geometry(
        Grid.in[D3](firstFrame)(Vector(5, 4, 3), firstAffine)
      )
    val secondGrid =
      geometry(
        Grid.in[D3](secondFrame)(Vector(3, 5, 4), secondAffine)
      )
    val firstData =
      valuesOn(firstGrid)((x, y, z) =>
        5.0 + 2.0 * x - 3.0 * y + 0.5 * z + 0.25 * x * y
      )
    val secondData =
      valuesOn(secondGrid)((x, y, z) =>
        5.0 + 2.0 * x - 3.0 * y + 0.5 * z + 0.25 * x * y
      )
    val firstImage =
      image(
        Sampled.continuous(firstGrid, NonSpatialAxes.empty, firstData)
      )
    val secondImage =
      image(
        Sampled.continuous(secondGrid, NonSpatialAxes.empty, secondData)
      )
    val control =
      kernel(RigidStencilControl.create(12, 2, 2, 1, 0.5))
    val first =
      kernel(CompiledRigidStencil.informationAware(firstImage, control))
    val second =
      kernel(CompiledRigidStencil.informationAware(secondImage, control))

    assertEquals(first.size, 12)
    assertEquals(second.size, 12)
    assertEquals(first.coveredBinCount, 4)
    assertEquals(second.coveredBinCount, 4)
    assertEquals(first.declaredBinCount, 4)
    assertEquals(second.declaredBinCount, 4)
    assertEqualsDouble(
      first.physicalChecksum,
      second.physicalChecksum,
      1e-12
    )
    var index = 0
    while index < first.size do
      assertEqualsDouble(first.x(index), second.x(index), 0.0)
      assertEqualsDouble(first.y(index), second.y(index), 0.0)
      assertEqualsDouble(first.z(index), second.z(index), 0.0)
      assertEqualsDouble(
        first.fixedValue(index),
        second.fixedValue(index),
        0.0
      )
      var axis = 0
      while axis < 3 do
        assertEqualsDouble(
          first.gradient(index, axis),
          second.gradient(index, axis),
          1e-12
        )
        axis += 1
      var column = 0
      while column < 6 do
        assertEqualsDouble(
          first.jacobian(index, column),
          second.jacobian(index, column),
          1e-12
        )
        column += 1
      index += 1

  test(
    "compiled full-affine gradients and SE3 columns match analytic and finite differences"
  ):
    val frame = geometry(Frame.named[D3]("gradient-frame"))
    val angle = math.toRadians(23.0)
    val direction =
      Vector(
        math.cos(angle), -math.sin(angle), 0.0,
        math.sin(angle), math.cos(angle), 0.0,
        0.0, 0.0, 1.0
      )
    val affine =
      geometry(
        Affine.fromOriginSpacingDirection[D3](
          Vector(31.0, -17.0, 8.0),
          Vector(1.3, 2.1, 3.7),
          direction
        )
      )
    val grid =
      geometry(Grid.in[D3](frame)(Vector(4, 5, 6), affine))
    val expectedGradient = Vector(0.2, -0.35, 0.125)
    def field(x: Double, y: Double, z: Double): Double =
      7.0 +
        expectedGradient(0) * x +
        expectedGradient(1) * y +
        expectedGradient(2) * z
    val fixed =
      image(
        Sampled.continuous(
          grid,
          NonSpatialAxes.empty,
          valuesOn(grid)(field)
        )
      )
    val stencil = kernel(CompiledRigidStencil.dense(fixed))

    var sample = 0
    while sample < stencil.size do
      var axis = 0
      while axis < 3 do
        assertEqualsDouble(
          stencil.gradient(sample, axis),
          expectedGradient(axis),
          1e-12
        )
        axis += 1
      sample += 1

    val selected = stencil.size / 2
    val point =
      geometry(
        Point.in[D3](frame)(
          stencil.x(selected),
          stencil.y(selected),
          stencil.z(selected)
        )
      )
    val epsilon = 1e-6
    var column = 0
    while column < 6 do
      val plus =
        pulledField(frame, point, column, epsilon, field)
      val minus =
        pulledField(frame, point, column, -epsilon, field)
      val numerical = (plus - minus) / (2.0 * epsilon)
      assertEqualsDouble(
        stencil.jacobian(selected, column),
        numerical,
        2e-8,
        s"SE3 column $column"
      )
      column += 1

  test(
    "Huber objective, normal equations, damping, and step have analytic values"
  ):
    val frame = geometry(Frame.named[D3]("normal-frame"))
    val grid =
      geometry(
        Grid.in[D3](frame)(Vector(2, 1, 1), Affine.identity[D3])
      )
    val fixed =
      image(
        Sampled.continuous(
          grid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](2, 1, 1)((i, _, _) => i.toDouble)
        )
      )
    val stencil = kernel(CompiledRigidStencil.dense(fixed))
    val workspace = RigidNormalWorkspace.create()
    val loss = kernel(RigidRobustLoss.huber(1.0))
    kernel(
      RigidNormalKernel.evaluateInto(
        stencil,
        residuals = Array(0.25, 2.0),
        validity = Array(1.0, 0.0),
        loss,
        workspace
      )
    )

    assertEqualsDouble(workspace.rawLoss, 1.53125, 1e-15)
    assertEqualsDouble(workspace.objective, 0.765625, 1e-15)
    assertEqualsDouble(workspace.overlap, 0.5, 0.0)
    assertEquals(workspace.support, 1L)
    assertEqualsDouble(workspace.normalizer, 2.0, 0.0)
    assertEqualsDouble(workspace.gradient(0), -0.625, 1e-15)
    assertEqualsDouble(workspace.normal(0, 0), 0.75, 1e-15)
    var row = 0
    while row < 6 do
      var column = 0
      while column < 6 do
        assertEqualsDouble(
          workspace.normal(row, column),
          workspace.normal(column, row),
          0.0
        )
        if row != 0 || column != 0 then
          assertEqualsDouble(workspace.normal(row, column), 0.0, 0.0)
        column += 1
      row += 1

    val step =
      kernel(
        RigidSmallSystem.solve(
          workspace,
          damping = 0.25,
          conditionLimit = 10.0
        )
      )
    assertEqualsDouble(step(0), 0.625, 1e-15)
    (1 until 6).foreach(index =>
      assertEqualsDouble(step(index), 0.0, 0.0)
    )
    assertEqualsDouble(step.pivotRatio, 4.0, 1e-15)
    assertEqualsDouble(workspace.proposedStep(0), 0.625, 1e-15)
    assertEqualsDouble(workspace.checksum, 1.301513671875, 0.0)

  test("kernel and solver failures remain typed and visible"):
    val frame = geometry(Frame.named[D3]("failure-frame"))
    val grid =
      geometry(
        Grid.in[D3](frame)(Vector(2, 1, 1), Affine.identity[D3])
      )
    val fixed =
      image(
        Sampled.continuous(
          grid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](2, 1, 1)((i, _, _) => i.toDouble)
        )
      )
    val stencil = kernel(CompiledRigidStencil.dense(fixed))
    val workspace = RigidNormalWorkspace.create()

    assertEquals(
      RigidNormalKernel.evaluateInto(
        stencil,
        Array(0.0),
        Array(1.0, 1.0),
        RigidRobustLoss.squared,
        workspace
      ),
      Left(
        RigidKernelError.InputLengthMismatch(
          RigidKernelInput.Residual,
          2,
          1
        )
      )
    )
    RigidNormalKernel.evaluateInto(
      stencil,
      Array(Double.NaN, 0.0),
      Array(1.0, 1.0),
      RigidRobustLoss.squared,
      workspace
    ) match
      case Left(RigidKernelError.NonFiniteResidual(0, value)) =>
        assert(value.isNaN)
      case other =>
        fail(s"expected non-finite residual, got $other")

    kernel(
      RigidNormalKernel.evaluateInto(
        stencil,
        Array(0.25, 2.0),
        Array(1.0, 1.0),
        RigidRobustLoss.squared,
        workspace
      )
    )
    assertEquals(
      RigidSmallSystem.solve(workspace, 0.0, 1e12),
      Left(RigidKernelError.SingularNormalMatrix(1, 0.0))
    )
    RigidSmallSystem.solve(workspace, 1e-12, 1e6) match
      case Left(
            RigidKernelError.IllConditionedNormalMatrix(ratio, limit)
          ) =>
        assert(ratio > limit)
      case other =>
        fail(s"expected ill-conditioned system, got $other")
    assertEquals(
      RigidSmallSystem.solve(workspace, -1.0, 1e6),
      Left(RigidKernelError.InvalidDamping(-1.0))
    )

    workspace.normalValues(0) = Double.PositiveInfinity
    RigidSmallSystem.solve(workspace, 1.0, 1e6) match
      case Left(
            RigidKernelError.NonFiniteSystem(
              RigidSystemComponent.NormalMatrix,
              0,
              value
            )
          ) =>
        assert(value.isPosInfinity)
      case other =>
        fail(s"expected non-finite normal matrix, got $other")

  test("dense and information stencil use one fixed objective definition"):
    val frame = geometry(Frame.named[D3]("dense-stencil-frame"))
    val grid =
      geometry(
        Grid.in[D3](frame)(Vector(4, 4, 4), Affine.identity[D3])
      )
    val fixed =
      image(
        Sampled.continuous(
          grid,
          NonSpatialAxes.empty,
          NDArray.tabulate[Double](4, 4, 4)((i, j, k) =>
            i.toDouble + 2.0 * j + 3.0 * k
          )
        )
      )
    val dense = kernel(CompiledRigidStencil.dense(fixed))
    val control =
      kernel(RigidStencilControl.create(8, 2, 2, 2, 0.5))
    val selected =
      kernel(CompiledRigidStencil.informationAware(fixed, control))
    val denseWorkspace = RigidNormalWorkspace.create()
    val selectedWorkspace = RigidNormalWorkspace.create()
    kernel(
      RigidNormalKernel.evaluateInto(
        dense,
        Array.fill(dense.size)(0.75),
        Array.fill(dense.size)(0.4),
        RigidRobustLoss.squared,
        denseWorkspace
      )
    )
    kernel(
      RigidNormalKernel.evaluateInto(
        selected,
        Array.fill(selected.size)(0.75),
        Array.fill(selected.size)(0.4),
        RigidRobustLoss.squared,
        selectedWorkspace
      )
    )

    assertEqualsDouble(denseWorkspace.objective, 0.28125, 0.0)
    assertEqualsDouble(selectedWorkspace.objective, 0.28125, 0.0)
    assertEqualsDouble(denseWorkspace.overlap, 0.4, 1e-15)
    assertEqualsDouble(selectedWorkspace.overlap, 0.4, 1e-15)
    assertEquals(denseWorkspace.support, dense.size.toLong)
    assertEquals(selectedWorkspace.support, selected.size.toLong)

  private def valuesOn[F <: Frame[D3]](
      grid: Grid[F, D3]
  )(
      field: (Double, Double, Double) => Double
  ): NDArray[Double, ravel.Rank[3]] =
    val matrix = grid.indexToFrame.rowMajor
    NDArray.tabulate[Double](
      grid.shape(0),
      grid.shape(1),
      grid.shape(2)
    )((i, j, k) =>
      val x =
        matrix(0) * i + matrix(1) * j + matrix(2) * k + matrix(3)
      val y =
        matrix(4) * i + matrix(5) * j + matrix(6) * k + matrix(7)
      val z =
        matrix(8) * i + matrix(9) * j + matrix(10) * k + matrix(11)
      field(x, y, z)
    )

  private def pulledField[F <: Frame[D3]](
      frame: F,
      point: Point[F, D3],
      column: Int,
      amount: Double,
      field: (Double, Double, Double) => Double
  ): Double =
    val values = Array.fill(6)(0.0)
    values(column) = amount
    val twist =
      rigid(
        Twist6.createFor(frame)(
          values(0),
          values(1),
          values(2),
          values(3),
          values(4),
          values(5)
        )
      )
    val increment = rigid(Rigid3.exp(twist))
    val pulled = mapped(increment.inverse(point))
    field(
      pulled.coordinates(0),
      pulled.coordinates(1),
      pulled.coordinates(2)
    )

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def image[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def rigid[A](value: Either[RigidError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def mapped[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def kernel[A](value: Either[RigidKernelError, A]): A =
    value.fold(error => fail(error.message), identity)
