package reframe4s.flashalign

import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3
import reframe4s.lie.RigidError

final class GeometryPointOperator3Suite extends munit.FunSuite:
  test("rigid point JVP and VJP are adjoints and match proposal finite differences"):
    val moving = geometry(Frame.named[D3]("operator-rigid-moving"))
    val fixed = geometry(Frame.named[D3]("operator-rigid-fixed"))
    val model = rigidModel(
      RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(1.0, -2.0, 0.5)
    )
    val operator = GeometryPointOperator3.rigid(model)
    val state: Rigid3[moving.type, fixed.type] = rigid(
      Rigid3.translationBetween[moving.type, fixed.type](moving, fixed)(0.7, -0.3, 0.2)
    )
    val points = pointBatch[moving.type](moving, samplePoints)
    val direction = geometryOperator(
      GeometryDirection3.create(
        operator.modelId,
        operator.basisId,
        Array(0.2, -0.1, 0.05, 0.03, -0.04, 0.02)
      )
    )
    checkAdjointAndFiniteDifference(operator, state, points, direction, 2e-8)
    val proposal = geometryOperator(operator.propose(state, direction))
    assertVectorClose(proposal.actualDirection.snapshot, direction.snapshot, 0.0)

  test("affine point JVP and VJP are adjoints and preserve canonical inverse direction"):
    val moving = geometry(Frame.named[D3]("operator-affine-moving"))
    val fixed = geometry(Frame.named[D3]("operator-affine-fixed"))
    val config = affineModel(AffineModelConfig.create())
    val model = affineModel(
      AffineModel3.atFixedWorld[moving.type, fixed.type](moving, fixed, config)(0.5, -0.7, 1.2)
    )
    val state: FramedAffine[moving.type, fixed.type, D3] = framed[moving.type, fixed.type](
      moving,
      fixed,
      Vector(
        1.02, 0.01, 0.0, 0.4,
        -0.02, 0.98, 0.01, -0.3,
        0.0, 0.02, 1.03, 0.2,
        0.0, 0.0, 0.0, 1.0
      )
    )
    val operator = GeometryPointOperator3.affine(model)
    val points = pointBatch[moving.type](moving, samplePoints)
    val direction = geometryOperator(
      GeometryDirection3.create(
        operator.modelId,
        operator.basisId,
        Array(
          0.1, -0.08, 0.03,
          0.02, -0.01, 0.015,
          0.03, -0.02, 0.01,
          0.025, -0.015, 0.02
        )
      )
    )
    checkAdjointAndFiniteDifference(operator, state, points, direction, 3e-8)
    val mapped = output[fixed.type](fixed, points.size)
    val roundTrip = output[moving.type](moving, points.size)
    val workspace = operator.newWorkspace()
    geometryOperator(operator.map(state, points, mapped, workspace))
    val mappedPoints = pointBatch[fixed.type](fixed, mapped.snapshot.toArray)
    geometryOperator(operator.inverseMap(state, mappedPoints, roundTrip, workspace))
    assertVectorClose(roundTrip.snapshot, samplePoints.toVector, 2e-12)

  test("test-only field model verifies adjoints and returns the actual clipped increment"):
    val moving = geometry(Frame.named[D3]("operator-field-moving"))
    val fixed = geometry(Frame.named[D3]("operator-field-fixed"))
    val operator = new MockFieldOperator[moving.type, fixed.type](moving, fixed)
    val state = Vector(0.04, -0.03)
    val points = pointBatch[moving.type](moving, samplePoints)
    val direction = geometryOperator(
      GeometryDirection3.create(operator.modelId, operator.basisId, Array(0.08, -0.06))
    )
    checkAdjointAndFiniteDifference(operator, state, points, direction, 3e-9)
    val requested = geometryOperator(
      GeometryDirection3.create(operator.modelId, operator.basisId, Array(0.8, -0.7))
    )
    val proposal = geometryOperator(operator.propose(state, requested))
    assertVectorClose(proposal.actualDirection.snapshot, Vector(0.1, -0.1), 0.0)
    assertVectorClose(proposal.state, Vector(0.14, -0.13), 1e-16)

  test("model basis owner shape and workspace mismatches fail before computation"):
    val moving = geometry(Frame.named[D3]("operator-errors-moving"))
    val fixed = geometry(Frame.named[D3]("operator-errors-fixed"))
    val otherMoving = geometry(Frame.named[D3]("operator-errors-other-moving"))
    val model = rigidModel(RigidModel3.atFixedWorld[moving.type, fixed.type](moving, fixed)(0.0, 0.0, 0.0))
    val operator = GeometryPointOperator3.rigid(model)
    val state: Rigid3[moving.type, fixed.type] = rigid(
      Rigid3.translationBetween[moving.type, fixed.type](moving, fixed)(0.0, 0.0, 0.0)
    )
    val points = pointBatch[moving.type](moving, samplePoints)
    val outputBuffer = output[fixed.type](fixed, points.size)
    val wrongModel = geometryOperator(
      GeometryDirection3.create("different-model", None, Array.fill(6)(0.0))
    )
    operator.jvp(state, points, wrongModel, outputBuffer, operator.newWorkspace()) match
      case Left(GeometryOperatorError.ModelIdentityMismatch(_, "different-model")) => ()
      case other => fail(s"expected model mismatch, got $other")

    val wrongBasis = geometryOperator(
      GeometryDirection3.create(operator.modelId, Some("unexpected-basis"), Array.fill(6)(0.0))
    )
    operator.jvp(state, points, wrongBasis, outputBuffer, operator.newWorkspace()) match
      case Left(GeometryOperatorError.BasisIdentityMismatch(None, Some("unexpected-basis"))) => ()
      case other => fail(s"expected basis mismatch, got $other")

    val otherPoints = pointBatch[otherMoving.type](otherMoving, samplePoints)
      .asInstanceOf[WorldPointBatch3[moving.type]]
    operator.map(state, otherPoints, outputBuffer, operator.newWorkspace()) match
      case Left(_: GeometryOperatorError.FrameOwnerMismatch) => ()
      case other => fail(s"expected owner mismatch, got $other")

    val otherOperator = new MockFieldOperator[moving.type, fixed.type](moving, fixed)
    operator.map(state, points, outputBuffer, otherOperator.newWorkspace()) match
      case Left(GeometryOperatorError.WorkspaceOperatorMismatch) => ()
      case other => fail(s"expected workspace mismatch, got $other")

    val shortOutput = output[fixed.type](fixed, points.size - 1)
    operator.map(state, points, shortOutput, operator.newWorkspace()) match
      case Left(GeometryOperatorError.PointCountMismatch(points.size, _)) => ()
      case other => fail(s"expected point-count mismatch, got $other")

  private val samplePoints = Array(
    -1.0, 2.0, 0.5,
    3.0, -0.4, 2.1,
    0.2, 1.7, -2.0,
    4.1, 0.3, 1.2
  )

  private val forces = Array(
    0.7, -0.3, 0.2,
    -0.5, 0.8, 0.1,
    0.25, -0.6, 0.9,
    0.4, 0.2, -0.7
  )

  private def checkAdjointAndFiniteDifference[
      State,
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      operator: GeometryPointOperator3[State, Moving, Fixed],
      state: State,
      points: WorldPointBatch3[Moving],
      direction: GeometryDirection3,
      tolerance: Double
  ): Unit =
    val workspace = operator.newWorkspace()
    val jvpOutput = output(operator.fixed, points.size)
    geometryOperator(operator.jvp(state, points, direction, jvpOutput, workspace))
    val forceBatch = vectorBatch(operator.fixed, forces)
    val transpose = new Array[Double](operator.parameterCount)
    geometryOperator(operator.vjp(state, points, forceBatch, transpose, workspace))
    val left = dot(jvpOutput.snapshot, forces.toVector)
    val right = dot(direction.snapshot, transpose.toVector)
    assertEqualsDouble(left, right, 2e-12)

    val epsilon = 1e-6
    val plus = directionWithScale(operator, direction, epsilon)
    val minus = directionWithScale(operator, direction, -epsilon)
    val plusState = geometryOperator(operator.propose(state, plus)).state
    val minusState = geometryOperator(operator.propose(state, minus)).state
    val plusMapped = output(operator.fixed, points.size)
    val minusMapped = output(operator.fixed, points.size)
    geometryOperator(operator.map(plusState, points, plusMapped, workspace))
    geometryOperator(operator.map(minusState, points, minusMapped, workspace))
    val numeric = plusMapped.snapshot.zip(minusMapped.snapshot).map { case (a, b) =>
      (a - b) / (2.0 * epsilon)
    }
    assertVectorClose(jvpOutput.snapshot, numeric, tolerance)

  private def directionWithScale[
      State,
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      operator: GeometryPointOperator3[State, Moving, Fixed],
      direction: GeometryDirection3,
      scale: Double
  ): GeometryDirection3 =
    geometryOperator(
      GeometryDirection3.create(
        operator.modelId,
        operator.basisId,
        direction.snapshot.map(_ * scale).toArray
      )
    )

  private def pointBatch[F <: Frame[D3]](frame: F, packed: Array[Double]): WorldPointBatch3[F] =
    geometryOperator(WorldPointBatch3.create(frame, packed))

  private def vectorBatch[F <: Frame[D3]](frame: F, packed: Array[Double]): WorldVectorBatch3[F] =
    geometryOperator(WorldVectorBatch3.create(frame, packed))

  private def output[F <: Frame[D3]](frame: F, size: Int): GeometryOutputBuffer3[F] =
    geometryOperator(GeometryOutputBuffer3.allocate(frame, size))

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

  private def assertVectorClose(actual: Vector[Double], expected: Vector[Double], tolerance: Double): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) => assertEqualsDouble(left, right, tolerance) }

  private def geometry[A](value: Either[GeometryError, A]): A = value.fold(error => fail(error.message), identity)
  private def rigid[A](value: Either[RigidError, A]): A = value.fold(error => fail(error.message), identity)
  private def rigidModel[A](value: Either[RigidModelError, A]): A =
    value.fold(error => fail(error.message), identity)
  private def affineModel[A](value: Either[AffineModelError, A]): A =
    value.fold(error => fail(error.message), identity)
  private def geometryOperator[A](value: Either[GeometryOperatorError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def framed[Moving <: Frame[D3], Fixed <: Frame[D3]](
      moving: Moving,
      fixed: Fixed,
      rowMajor: Vector[Double]
  ): FramedAffine[Moving, Fixed, D3] =
    val affine = geometry(Affine.fromRowMajor[D3](rowMajor))
    FramedAffine.betweenFrames(moving, fixed)(affine)

private final class MockFieldOperator[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](val moving: Moving, val fixed: Fixed)
    extends GeometryPointOperator3[Vector[Double], Moving, Fixed]:
  val modelId = "independent-mock-field-v1"
  val basisId = Some("mock-basis-v1")
  val parameterCount = 2

  protected def validateState(state: Vector[Double]): Either[GeometryOperatorError, Unit] =
    if state.length != 2 then Left(GeometryOperatorError.ParameterCountMismatch(2, state.length))
    else if state.exists(value => !value.isFinite || math.abs(value) >= 0.5) then
      Left(GeometryOperatorError.InvalidDirection("mock field state outside certificate"))
    else Right(())

  protected def mapUnchecked(
      state: Vector[Double],
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = workspace
    var index = 0
    while index < points.packed.length do
      output.packed(index) = (1.0 + state(0)) * points.packed(index)
      output.packed(index + 1) = (1.0 + state(1)) * points.packed(index + 1)
      output.packed(index + 2) = points.packed(index + 2)
      index += 3

  protected def inverseMapChecked(
      state: Vector[Double],
      points: WorldPointBatch3[Fixed],
      output: GeometryOutputBuffer3[Moving],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    val _ = workspace
    var index = 0
    while index < points.packed.length do
      output.packed(index) = points.packed(index) / (1.0 + state(0))
      output.packed(index + 1) = points.packed(index + 1) / (1.0 + state(1))
      output.packed(index + 2) = points.packed(index + 2)
      index += 3
    Right(())

  protected def jvpUnchecked(
      state: Vector[Double],
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = state
    val _ = workspace
    var index = 0
    while index < points.packed.length do
      output.packed(index) = direction(0) * points.packed(index)
      output.packed(index + 1) = direction(1) * points.packed(index + 1)
      output.packed(index + 2) = 0.0
      index += 3

  protected def vjpUnchecked(
      state: Vector[Double],
      points: WorldPointBatch3[Moving],
      force: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = state
    val _ = workspace
    output(0) = 0.0
    output(1) = 0.0
    var index = 0
    while index < points.packed.length do
      output(0) += force.packed(index) * points.packed(index)
      output(1) += force.packed(index + 1) * points.packed(index + 1)
      index += 3

  protected def proposeChecked(
      state: Vector[Double],
      direction: Array[Double]
  ): Either[GeometryOperatorError, (Vector[Double], Array[Double])] =
    val actual = direction.map(value => math.max(-0.1, math.min(0.1, value)))
    Right(state.zip(actual).map(_ + _).toVector -> actual)

  def priorTerms(state: Vector[Double]): Either[GeometryOperatorError, GeometryPriorTerms3] =
    validateState(state).map(_ =>
      GeometryPriorTerms3(
        0.5 * state.map(value => value * value).sum,
        state,
        Vector(1.0, 0.0, 1.0)
      )
    )

  def certify(state: Vector[Double]): Either[GeometryOperatorError, GeometryCertificate3] =
    validateState(state).map(_ => GeometryCertificate3(modelId, basisId, valid = true, "mock analytic"))
