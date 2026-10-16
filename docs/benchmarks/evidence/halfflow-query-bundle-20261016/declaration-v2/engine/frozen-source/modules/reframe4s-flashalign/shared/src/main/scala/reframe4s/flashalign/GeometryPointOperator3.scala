package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame
import reframe4s.core.FrameOwnerDescriptor
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3

private[flashalign] enum GeometryBatchKind derives CanEqual:
  case Point, Vector, Output

private[flashalign] final class WorldPointBatch3[F <: Frame[D3]] private (
    val frame: F,
    private[flashalign] val packed: Array[Double]
):
  val size: Int = packed.length / 3
  def point(index: Int): Vector[Double] =
    Vector(packed(index * 3), packed(index * 3 + 1), packed(index * 3 + 2))

private[flashalign] object WorldPointBatch3:
  def create[F <: Frame[D3]](
      frame: F,
      packedCoordinates: Array[Double]
  ): Either[GeometryOperatorError, WorldPointBatch3[F]] =
    validatePacked(GeometryBatchKind.Point, packedCoordinates).map(_ =>
      new WorldPointBatch3(frame, packedCoordinates.clone())
    )

  /** Package-local adapter for an already-owned scratch array. The caller must
    * keep the array private for the lifetime of the batch.
    */
  private[flashalign] def wrapOwned[F <: Frame[D3]](
      frame: F,
      packedCoordinates: Array[Double]
  ): WorldPointBatch3[F] = new WorldPointBatch3(frame, packedCoordinates)

  private[flashalign] def validatePacked(
      kind: GeometryBatchKind,
      packed: Array[Double]
  ): Either[GeometryOperatorError, Unit] =
    if packed.length % 3 != 0 then Left(GeometryOperatorError.InvalidPackedLength(kind, packed.length))
    else
      var index = 0
      while index < packed.length do
        if !packed(index).isFinite then return Left(GeometryOperatorError.NonFinite(kind, index, packed(index)))
        index += 1
      Right(())

private[flashalign] final class WorldVectorBatch3[F <: Frame[D3]] private (
    val frame: F,
    private[flashalign] val packed: Array[Double]
):
  val size: Int = packed.length / 3

private[flashalign] object WorldVectorBatch3:
  def create[F <: Frame[D3]](
      frame: F,
      packedVectors: Array[Double]
  ): Either[GeometryOperatorError, WorldVectorBatch3[F]] =
    WorldPointBatch3.validatePacked(GeometryBatchKind.Vector, packedVectors).map(_ =>
      new WorldVectorBatch3(frame, packedVectors.clone())
    )

  private[flashalign] def wrapOwned[F <: Frame[D3]](
      frame: F,
      packedVectors: Array[Double]
  ): WorldVectorBatch3[F] = new WorldVectorBatch3(frame, packedVectors)

private[flashalign] final class GeometryOutputBuffer3[F <: Frame[D3]] private (
    val frame: F,
    private[flashalign] val packed: Array[Double]
):
  val size: Int = packed.length / 3
  def snapshot: Vector[Double] = packed.toVector

private[flashalign] object GeometryOutputBuffer3:
  def allocate[F <: Frame[D3]](frame: F, size: Int): Either[GeometryOperatorError, GeometryOutputBuffer3[F]] =
    if size < 0 then Left(GeometryOperatorError.InvalidPointCount(size))
    else Right(new GeometryOutputBuffer3(frame, new Array[Double](size * 3)))

  /** Package-local adapter for an already-owned scratch array. */
  private[flashalign] def wrapOwned[F <: Frame[D3]](
      frame: F,
      packedCoordinates: Array[Double]
  ): GeometryOutputBuffer3[F] = new GeometryOutputBuffer3(frame, packedCoordinates)

private[flashalign] final class GeometryDirection3 private (
    val modelId: String,
    val basisId: Option[String],
    private[flashalign] val values: Array[Double]
):
  def snapshot: Vector[Double] = values.toVector

private[flashalign] object GeometryDirection3:
  def create(
      modelId: String,
      basisId: Option[String],
      values: Array[Double]
  ): Either[GeometryOperatorError, GeometryDirection3] =
    if modelId.trim.isEmpty then Left(GeometryOperatorError.InvalidDirection("model ID is empty"))
    else
      var index = 0
      while index < values.length do
        if !values(index).isFinite then
          return Left(GeometryOperatorError.NonFinite(GeometryBatchKind.Vector, index, values(index)))
        index += 1
      Right(new GeometryDirection3(modelId, basisId, values.clone()))

private[flashalign] final case class GeometryProposal3[State](
    state: State,
    actualDirection: GeometryDirection3
)

private[flashalign] final case class GeometryPriorTerms3(
    value: Double,
    gradient: Vector[Double],
    curvatureUpper: Vector[Double]
)

private[flashalign] final case class GeometryCertificate3(
    modelId: String,
    basisId: Option[String],
    valid: Boolean,
    detail: String
)

private[flashalign] final class GeometryOperatorWorkspace3 private (
    private val owner: AnyRef
):
  private var active = false
  private var mappedScratch = new Array[Double](0)
  private var parameterScratch = new Array[Double](0)
  private var smallStrainScratch: SmallStrainWorkspace3 = null

  private[flashalign] def mapped(capacity: Int): Array[Double] =
    if mappedScratch.length < capacity then mappedScratch = new Array[Double](capacity)
    mappedScratch

  private[flashalign] def parameters(capacity: Int): Array[Double] =
    if parameterScratch.length < capacity then parameterScratch = new Array[Double](capacity)
    parameterScratch

  private[flashalign] def smallStrain(): SmallStrainWorkspace3 =
    if smallStrainScratch == null then
      smallStrainScratch = new SmallStrainWorkspace3(new Array[Double](3), new Array[Double](3), new Array[Double](3))
    smallStrainScratch

  private[flashalign] def acquire(candidate: AnyRef): Either[GeometryOperatorError, Unit] =
    this.synchronized {
      if !(owner eq candidate) then Left(GeometryOperatorError.WorkspaceOperatorMismatch)
      else if active then Left(GeometryOperatorError.WorkspaceInUse)
      else
        active = true
        Right(())
    }

  private[flashalign] def release(candidate: AnyRef): Unit =
    this.synchronized {
      if owner eq candidate then active = false
    }

private[flashalign] object GeometryOperatorWorkspace3:
  def forOperator(owner: AnyRef): GeometryOperatorWorkspace3 = new GeometryOperatorWorkspace3(owner)

/** Private nonlinear geometry boundary. Implementations remain closed inside
  * Flashalign; this is not a public registration-plugin interface.
  */
private[flashalign] trait GeometryPointOperator3[
    State,
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
]:
  self: AnyRef =>
  def modelId: String
  def basisId: Option[String]
  def parameterCount: Int
  def moving: Moving
  def fixed: Fixed

  final def newWorkspace(): GeometryOperatorWorkspace3 = GeometryOperatorWorkspace3.forOperator(this)

  final def map(
      state: State,
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    withWorkspace(workspace) {
      for
        _ <- validateState(state)
        _ <- validateMapBatches(points, output)
        _ <- validateForwardPoints(state, points)
      yield mapUnchecked(state, points, output, workspace)
    }

  final def inverseMap(
      state: State,
      points: WorldPointBatch3[Fixed],
      output: GeometryOutputBuffer3[Moving],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    withWorkspace(workspace) {
      for
        _ <- validateState(state)
        _ <- validateInverseBatches(points, output)
        _ <- validateInversePoints(state, points)
        _ <- inverseMapChecked(state, points, output, workspace)
      yield ()
    }

  final def jvp(
      state: State,
      points: WorldPointBatch3[Moving],
      direction: GeometryDirection3,
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    withWorkspace(workspace) {
      for
        _ <- validateState(state)
        _ <- validateMapBatches(points, output)
        _ <- validateForwardPoints(state, points)
        _ <- validateDirection(direction)
      yield jvpUnchecked(state, points, direction.values, output, workspace)
    }

  final def vjp(
      state: State,
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    withWorkspace(workspace) {
      for
        _ <- validateState(state)
        _ <- validatePointOwner(points)
        _ <- validateVectorOwner(forces)
        _ <- validateSameSize(points.size, forces.size)
        _ <- validateForwardPoints(state, points)
        _ <- validateParameterOutput(output)
      yield vjpUnchecked(state, points, forces, output, workspace)
    }

  final def propose(
      state: State,
      direction: GeometryDirection3
  ): Either[GeometryOperatorError, GeometryProposal3[State]] =
    for
      _ <- validateState(state)
      _ <- validateDirection(direction)
      proposal <- proposeChecked(state, direction.values)
      actual <- GeometryDirection3.create(modelId, basisId, proposal._2)
    yield GeometryProposal3(proposal._1, actual)

  def priorTerms(state: State): Either[GeometryOperatorError, GeometryPriorTerms3]
  def certify(state: State): Either[GeometryOperatorError, GeometryCertificate3]

  protected def validateState(state: State): Either[GeometryOperatorError, Unit]
  protected def mapUnchecked(
      state: State,
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit
  protected def inverseMapChecked(
      state: State,
      points: WorldPointBatch3[Fixed],
      output: GeometryOutputBuffer3[Moving],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit]
  protected def jvpUnchecked(
      state: State,
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit
  protected def vjpUnchecked(
      state: State,
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: GeometryOperatorWorkspace3
  ): Unit
  protected def proposeChecked(
      state: State,
      direction: Array[Double]
  ): Either[GeometryOperatorError, (State, Array[Double])]

  protected def validateForwardPoints(
      state: State,
      points: WorldPointBatch3[Moving]
  ): Either[GeometryOperatorError, Unit] =
    val _ = state
    val _ = points
    Right(())

  protected def validateInversePoints(
      state: State,
      points: WorldPointBatch3[Fixed]
  ): Either[GeometryOperatorError, Unit] =
    val _ = state
    val _ = points
    Right(())

  private def validateDirection(direction: GeometryDirection3): Either[GeometryOperatorError, Unit] =
    if direction.modelId != modelId then
      Left(GeometryOperatorError.ModelIdentityMismatch(modelId, direction.modelId))
    else if direction.basisId != basisId then
      Left(GeometryOperatorError.BasisIdentityMismatch(basisId, direction.basisId))
    else if direction.values.length != parameterCount then
      Left(GeometryOperatorError.ParameterCountMismatch(parameterCount, direction.values.length))
    else Right(())

  private def validateMapBatches(
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed]
  ): Either[GeometryOperatorError, Unit] =
    validatePointOwner(points)
      .flatMap(_ => validateOutputOwner(output, fixed))
      .flatMap(_ => validateSameSize(points.size, output.size))

  private def validateInverseBatches(
      points: WorldPointBatch3[Fixed],
      output: GeometryOutputBuffer3[Moving]
  ): Either[GeometryOperatorError, Unit] =
    validateInputOwner(points.frame, fixed)
      .flatMap(_ => validateOutputOwner(output, moving))
      .flatMap(_ => validateSameSize(points.size, output.size))

  private def validatePointOwner(points: WorldPointBatch3[Moving]): Either[GeometryOperatorError, Unit] =
    validateInputOwner(points.frame, moving)

  private def validateVectorOwner(forces: WorldVectorBatch3[Fixed]): Either[GeometryOperatorError, Unit] =
    validateInputOwner(forces.frame, fixed)

  private def validateInputOwner(actual: Frame[D3], expected: Frame[D3]): Either[GeometryOperatorError, Unit] =
    if actual.sameRuntimeOwnerAs(expected) then Right(())
    else Left(GeometryOperatorError.FrameOwnerMismatch(FrameOwnerDescriptor.of(expected), FrameOwnerDescriptor.of(actual)))

  private def validateOutputOwner[F <: Frame[D3]](
      output: GeometryOutputBuffer3[F],
      expected: Frame[D3]
  ): Either[GeometryOperatorError, Unit] = validateInputOwner(output.frame, expected)

  private def validateSameSize(expected: Int, actual: Int): Either[GeometryOperatorError, Unit] =
    if expected == actual then Right(())
    else Left(GeometryOperatorError.PointCountMismatch(expected, actual))

  private def validateParameterOutput(output: Array[Double]): Either[GeometryOperatorError, Unit] =
    if output.length == parameterCount then Right(())
    else Left(GeometryOperatorError.ParameterCountMismatch(parameterCount, output.length))

  private def withWorkspace[A](workspace: GeometryOperatorWorkspace3)(
      operation: => Either[GeometryOperatorError, A]
  ): Either[GeometryOperatorError, A] =
    workspace.acquire(this).flatMap { _ =>
      try operation
      finally workspace.release(this)
    }

private[flashalign] object GeometryPointOperator3:
  def rigid[Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: RigidModel3[Moving, Fixed]
  ): GeometryPointOperator3[Rigid3[Moving, Fixed], Moving, Fixed] =
    new RigidGeometryPointOperator3(model)

  def affine[Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: AffineModel3[Moving, Fixed],
      prior: Option[AffineStrainPrior3[Moving, Fixed]] = None
  ): GeometryPointOperator3[FramedAffine[Moving, Fixed, D3], Moving, Fixed] =
    new AffineGeometryPointOperator3(model, prior)

  def peField[Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: PeFieldModel3[Moving, Fixed],
      config: PeGeometryConfig
  ): GeometryPointOperator3[PeFieldState3[Moving, Fixed], Moving, Fixed] =
    new PeFieldGeometryPointOperator3(model, config)

  def smallStrain[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]](
      model: SmallStrainModel3[Pose, Moving, Fixed],
      config: SmallStrainGeometryConfig
  ): GeometryPointOperator3[SmallStrainState3[Pose], Moving, Fixed] =
    new SmallStrainGeometryPointOperator3(model, config)

private final class RigidGeometryPointOperator3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](val model: RigidModel3[Moving, Fixed])
    extends GeometryPointOperator3[Rigid3[Moving, Fixed], Moving, Fixed]:
  val modelId = "flashalign-rigid-point-operator-v1"
  val basisId = Option.empty[String]
  val parameterCount = 6
  val moving: Moving = model.moving
  val fixed: Fixed = model.fixed

  protected def validateState(state: Rigid3[Moving, Fixed]): Either[GeometryOperatorError, Unit] =
    model.validateMovingToFixed(state).left.map(error => GeometryOperatorError.Rigid(error.message))

  protected def mapUnchecked(
      state: Rigid3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = workspace
    applyAffine(state.operator.rowMajor, points.packed, output.packed)

  protected def inverseMapChecked(
      state: Rigid3[Moving, Fixed],
      points: WorldPointBatch3[Fixed],
      output: GeometryOutputBuffer3[Moving],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    val _ = workspace
    applyAffine(state.inverse.operator.rowMajor, points.packed, output.packed)
    Right(())

  protected def jvpUnchecked(
      state: Rigid3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val mapped = workspace.mapped(points.packed.length)
    applyAffine(state.operator.rowMajor, points.packed, mapped)
    localJvp(mapped, direction, output.packed, model.pivotX, model.pivotY, model.pivotZ, affine = false)

  protected def vjpUnchecked(
      state: Rigid3[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    java.util.Arrays.fill(output, 0.0)
    val mapped = workspace.mapped(points.packed.length)
    applyAffine(state.operator.rowMajor, points.packed, mapped)
    localVjp(mapped, forces.packed, output, model.pivotX, model.pivotY, model.pivotZ, affine = false)

  protected def proposeChecked(
      state: Rigid3[Moving, Fixed],
      direction: Array[Double]
  ): Either[GeometryOperatorError, (Rigid3[Moving, Fixed], Array[Double])] =
    model.propose(state, direction).left
      .map(error => GeometryOperatorError.Rigid(error.message))
      .map(candidate => candidate -> direction.clone())

  def priorTerms(state: Rigid3[Moving, Fixed]): Either[GeometryOperatorError, GeometryPriorTerms3] =
    validateState(state).map(_ => GeometryPriorTerms3(0.0, Vector.fill(6)(0.0), Vector.fill(21)(0.0)))

  def certify(state: Rigid3[Moving, Fixed]): Either[GeometryOperatorError, GeometryCertificate3] =
    validateState(state).map(_ => GeometryCertificate3(modelId, basisId, valid = true, "canonical Rigid3"))

private final class AffineGeometryPointOperator3[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    val model: AffineModel3[Moving, Fixed],
    val prior: Option[AffineStrainPrior3[Moving, Fixed]]
) extends GeometryPointOperator3[FramedAffine[Moving, Fixed, D3], Moving, Fixed]:
  val modelId = "flashalign-affine-point-operator-v1"
  val basisId = Option.empty[String]
  val parameterCount = 12
  val moving: Moving = model.moving
  val fixed: Fixed = model.fixed

  protected def validateState(
      state: FramedAffine[Moving, Fixed, D3]
  ): Either[GeometryOperatorError, Unit] =
    model.validateMovingToFixed(state).left.map(error => GeometryOperatorError.Affine(error.message))

  protected def mapUnchecked(
      state: FramedAffine[Moving, Fixed, D3],
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = workspace
    applyAffine(state.operator.rowMajor, points.packed, output.packed)

  protected def inverseMapChecked(
      state: FramedAffine[Moving, Fixed, D3],
      points: WorldPointBatch3[Fixed],
      output: GeometryOutputBuffer3[Moving],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    val _ = workspace
    applyAffine(state.inverse.operator.rowMajor, points.packed, output.packed)
    Right(())

  protected def jvpUnchecked(
      state: FramedAffine[Moving, Fixed, D3],
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val mapped = workspace.mapped(points.packed.length)
    applyAffine(state.operator.rowMajor, points.packed, mapped)
    localJvp(mapped, direction, output.packed, model.pivotX, model.pivotY, model.pivotZ, affine = true)

  protected def vjpUnchecked(
      state: FramedAffine[Moving, Fixed, D3],
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    java.util.Arrays.fill(output, 0.0)
    val mapped = workspace.mapped(points.packed.length)
    applyAffine(state.operator.rowMajor, points.packed, mapped)
    localVjp(mapped, forces.packed, output, model.pivotX, model.pivotY, model.pivotZ, affine = true)

  protected def proposeChecked(
      state: FramedAffine[Moving, Fixed, D3],
      direction: Array[Double]
  ): Either[GeometryOperatorError, (FramedAffine[Moving, Fixed, D3], Array[Double])] =
    model.propose(state, direction).left
      .map(error => GeometryOperatorError.Affine(error.message))
      .map(candidate => candidate -> direction.clone())

  def priorTerms(
      state: FramedAffine[Moving, Fixed, D3]
  ): Either[GeometryOperatorError, GeometryPriorTerms3] =
    prior match
      case None =>
        validateState(state).map(_ => GeometryPriorTerms3(0.0, Vector.fill(12)(0.0), Vector.fill(78)(0.0)))
      case Some(value) =>
        value.evaluate(state).left.map(error => GeometryOperatorError.Affine(error.message)).map(terms =>
          GeometryPriorTerms3(terms.value, terms.gradient.toVector, terms.curvatureUpper.toVector)
        )

  def certify(
      state: FramedAffine[Moving, Fixed, D3]
  ): Either[GeometryOperatorError, GeometryCertificate3] =
    validateState(state).map(_ =>
      GeometryCertificate3(modelId, basisId, valid = true, "checked affine singular values and determinant")
    )

private def applyAffine(matrix: Vector[Double], input: Array[Double], output: Array[Double]): Unit =
  var point = 0
  while point < input.length / 3 do
    val offset = point * 3
    val x = input(offset)
    val y = input(offset + 1)
    val z = input(offset + 2)
    output(offset) = matrix(0) * x + matrix(1) * y + matrix(2) * z + matrix(3)
    output(offset + 1) = matrix(4) * x + matrix(5) * y + matrix(6) * z + matrix(7)
    output(offset + 2) = matrix(8) * x + matrix(9) * y + matrix(10) * z + matrix(11)
    point += 1

private def localJvp(
    mapped: Array[Double],
    direction: Array[Double],
    output: Array[Double],
    pivotX: Double,
    pivotY: Double,
    pivotZ: Double,
    affine: Boolean
): Unit =
  var point = 0
  while point < mapped.length / 3 do
    val offset = point * 3
    val rx = mapped(offset) - pivotX
    val ry = mapped(offset + 1) - pivotY
    val rz = mapped(offset + 2) - pivotZ
    output(offset) = direction(0) + direction(4) * rz - direction(5) * ry
    output(offset + 1) = direction(1) + direction(5) * rx - direction(3) * rz
    output(offset + 2) = direction(2) + direction(3) * ry - direction(4) * rx
    if affine then
      output(offset) += direction(6) * rx + direction(9) * ry + direction(10) * rz
      output(offset + 1) += direction(9) * rx + direction(7) * ry + direction(11) * rz
      output(offset + 2) += direction(10) * rx + direction(11) * ry + direction(8) * rz
    point += 1

private def localVjp(
    mapped: Array[Double],
    forces: Array[Double],
    output: Array[Double],
    pivotX: Double,
    pivotY: Double,
    pivotZ: Double,
    affine: Boolean
): Unit =
  var point = 0
  while point < mapped.length / 3 do
    val offset = point * 3
    val rx = mapped(offset) - pivotX
    val ry = mapped(offset + 1) - pivotY
    val rz = mapped(offset + 2) - pivotZ
    val fx = forces(offset)
    val fy = forces(offset + 1)
    val fz = forces(offset + 2)
    output(0) += fx
    output(1) += fy
    output(2) += fz
    output(3) += ry * fz - rz * fy
    output(4) += rz * fx - rx * fz
    output(5) += rx * fy - ry * fx
    if affine then
      output(6) += fx * rx
      output(7) += fy * ry
      output(8) += fz * rz
      output(9) += fx * ry + fy * rx
      output(10) += fx * rz + fz * rx
      output(11) += fy * rz + fz * ry
    point += 1

private[flashalign] sealed trait GeometryOperatorError derives CanEqual:
  def message: String

private[flashalign] object GeometryOperatorError:
  final case class InvalidPackedLength(kind: GeometryBatchKind, actual: Int) extends GeometryOperatorError:
    val message = s"$kind batch requires a multiple of three values, got $actual"
  final case class NonFinite(kind: GeometryBatchKind, index: Int, value: Double) extends GeometryOperatorError:
    val message = s"$kind value $index must be finite, got $value"
  final case class InvalidPointCount(actual: Int) extends GeometryOperatorError:
    val message = s"point count must be nonnegative, got $actual"
  final case class PointCountMismatch(expected: Int, actual: Int) extends GeometryOperatorError:
    val message = s"point count mismatch: expected $expected, got $actual"
  final case class ParameterCountMismatch(expected: Int, actual: Int) extends GeometryOperatorError:
    val message = s"parameter count mismatch: expected $expected, got $actual"
  final case class FrameOwnerMismatch(expected: FrameOwnerDescriptor, actual: FrameOwnerDescriptor)
      extends GeometryOperatorError:
    val message = s"frame owner $actual does not match $expected"
  final case class ModelIdentityMismatch(expected: String, actual: String) extends GeometryOperatorError:
    val message = s"model identity mismatch: expected $expected, got $actual"
  final case class BasisIdentityMismatch(expected: Option[String], actual: Option[String])
      extends GeometryOperatorError:
    val message = s"basis identity mismatch: expected $expected, got $actual"
  final case class InvalidDirection(detail: String) extends GeometryOperatorError:
    val message = s"invalid geometry direction: $detail"
  case object WorkspaceOperatorMismatch extends GeometryOperatorError:
    val message = "geometry workspace belongs to a different compiled operator"
  case object WorkspaceInUse extends GeometryOperatorError:
    val message = "geometry workspace is already in use"
  final case class Rigid(detail: String) extends GeometryOperatorError:
    val message = detail
  final case class Affine(detail: String) extends GeometryOperatorError:
    val message = detail
  final case class Nonlinear(detail: String) extends GeometryOperatorError:
    val message = detail
