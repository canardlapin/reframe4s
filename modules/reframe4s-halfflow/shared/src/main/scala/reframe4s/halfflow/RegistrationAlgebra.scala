package reframe4s.halfflow

import reframe4s.halfflow.internal.*

enum RegistrationError:
  case FrameMismatch(context: String, expected: SpatialDomainId, actual: SpatialDomainId)
  case GridMismatch(context: String)
  case InvalidField(context: String)
  case InvalidFlowParameter(context: String, value: Double)
  case SquaringDepthExceeded(required: Int, maximum: Int)
  case MorphismExportFailed(context: String, reason: String)
  case InvalidConfiguration(context: String)
  case InsufficientSupport(context: String, available: Int, required: Int)
  case ExcessiveSolveFailures(failed: Int, active: Int)
  case SmoothingDidNotConverge(component: Int, pass: Int, iterations: Int, relativeResidual: Double)
  case UnsafeTransform(context: String)
  case InvalidAffine(context: String, reason: String)
  case AffineSquareRootDidNotConverge(iterations: Int, residual: Double)

  def message: String =
    this match
      case FrameMismatch(context, expected, actual) =>
        s"$context domain '${actual.value}' does not match '${expected.value}'"
      case GridMismatch(context) => s"$context grid mismatch"
      case InvalidField(context) => s"invalid $context field"
      case InvalidFlowParameter(context, value) => s"invalid $context: $value"
      case SquaringDepthExceeded(required, maximum) =>
        s"paired flow requires squaring depth $required above configured maximum $maximum"
      case MorphismExportFailed(context, reason) => s"cannot export $context morphism: $reason"
      case InvalidConfiguration(context) => s"invalid registration configuration: $context"
      case InsufficientSupport(context, available, required) =>
        s"insufficient $context support: $available available, $required required"
      case ExcessiveSolveFailures(failed, active) =>
        s"local LM solve failed at $failed of $active active voxels"
      case SmoothingDidNotConverge(component, pass, iterations, relativeResidual) =>
        s"Sobolev solve component $component pass $pass did not converge after $iterations iterations (relative residual $relativeResidual)"
      case UnsafeTransform(context) => s"unsafe registration transform: $context"
      case InvalidAffine(context, reason) => s"invalid $context affine: $reason"
      case AffineSquareRootDidNotConverge(iterations, residual) =>
        s"affine square root did not converge after $iterations iterations (relative residual $residual)"

final case class Frame[A](domain: SpatialDomainId, grid: GridSpec)

final case class DensePull[A, B] private (
    from: Frame[A],
    to: Frame[B],
    sourceCoordinates: DenseVectorField,
    validity: FieldValidity
):
  def >>>[C](that: DensePull[B, C]): Either[RegistrationError, DensePull[A, C]] =
    DensePull.compose(this, that)

  private[halfflow] def thenSelfExtended(
      that: DensePull[B, B],
      outside: CoordinateMapOutside = CoordinateMapOutside.Identity
  ): Either[RegistrationError, DensePull[A, B]] =
    DensePull.compose(this, that, outside)

  def regrid(
      newFrom: Frame[A],
      newTo: Frame[B]
  ): Either[RegistrationError, DensePull[A, B]] =
    if newFrom.domain != from.domain then
      Left(RegistrationError.FrameMismatch("pull regrid source", from.domain, newFrom.domain))
    else if newTo.domain != to.domain then
      Left(RegistrationError.FrameMismatch("pull regrid target", to.domain, newTo.domain))
    else
      val result = HalfFlowKernels.regridPull(sourceCoordinates, newFrom.grid, validity)
      Right(DensePull.unsafe(newFrom, newTo, result.field, FieldValidity.copyMask(result.valid)))

object DensePull:
  def make[A, B](
      from: Frame[A],
      to: Frame[B],
      sourceCoordinates: DenseVectorField,
      validity: FieldValidity = FieldValidity.All
  ): Either[RegistrationError, DensePull[A, B]] =
    if sourceCoordinates.grid != from.grid then Left(RegistrationError.GridMismatch("dense pull source"))
    else if sourceCoordinates.kind != DenseVectorFieldKind.SourceCoordinates then
      Left(RegistrationError.InvalidField("dense pull coordinate kind"))
    else if !validitySizeMatches(validity, from.grid.nVoxels) then
      Left(RegistrationError.InvalidField("dense pull validity"))
    else if !allFinite(sourceCoordinates) then
      Left(RegistrationError.InvalidField("dense pull coordinates"))
    else Right(unsafe(from, to, sourceCoordinates, validity))

  def identity[A](frame: Frame[A]): DensePull[A, A] =
    val result = HalfFlowKernels.identity(frame.grid)
    unsafe(frame, frame, result.field, FieldValidity.All)

  private[halfflow] def unsafe[A, B](
      from: Frame[A],
      to: Frame[B],
      sourceCoordinates: DenseVectorField,
      validity: FieldValidity
  ): DensePull[A, B] =
    new DensePull(from, to, sourceCoordinates, validity)

  private def compose[A, B, C](
      left: DensePull[A, B],
      right: DensePull[B, C],
      outside: CoordinateMapOutside = CoordinateMapOutside.Invalid
  ): Either[RegistrationError, DensePull[A, C]] =
    if left.to.domain != right.from.domain then
      Left(RegistrationError.FrameMismatch("pull composition", left.to.domain, right.from.domain))
    else if left.to.grid != right.from.grid then Left(RegistrationError.GridMismatch("pull composition"))
    else
      val result = HalfFlowKernels.composePull(
        left.sourceCoordinates,
        right.sourceCoordinates,
        left.validity,
        right.validity,
        outside
      )
      Right(unsafe(left.from, right.to, result.field, FieldValidity.copyMask(result.valid)))

  private def validitySizeMatches(validity: FieldValidity, size: Int): Boolean =
    validity match
      case FieldValidity.All => true
      case FieldValidity.Mask(values) => values.size == size

  private def allFinite(field: DenseVectorField): Boolean =
    var finite = true
    var index = 0
    while index < field.grid.nVoxels && finite do
      var component = 0
      while component < 3 && finite do
        finite = field.linearComponent(index, component).isFinite
        component += 1
      index += 1
    finite

final case class InversePair[A, B] private (
    forward: DensePull[A, B],
    backward: DensePull[B, A]
):
  def inverse: InversePair[B, A] =
    InversePair.unsafe(backward, forward)

  def >>>[C](that: InversePair[B, C]): Either[RegistrationError, InversePair[A, C]] =
    for
      nextForward <- forward >>> that.forward
      nextBackward <- that.backward >>> backward
    yield InversePair.unsafe(nextForward, nextBackward)

  def regrid(
      newFrom: Frame[A],
      newTo: Frame[B]
  ): Either[RegistrationError, InversePair[A, B]] =
    for
      nextForward <- forward.regrid(newFrom, newTo)
      nextBackward <- backward.regrid(newTo, newFrom)
    yield InversePair.unsafe(nextForward, nextBackward)

  def toDenseFieldMorphisms(
      cost: Double = 10.0,
      methodTag: String = "half-flow-lm"
  ): Either[RegistrationError, DenseMorphismPair] =
    for
      nextForward <- DenseFieldMorphism
        .coordinates(
          forward.from.domain,
          forward.to.domain,
          forward.from.grid,
          forward.sourceCoordinates.values,
          Resample.Method.Linear,
          cost,
          methodTag
        )
        .left
        .map(error => RegistrationError.MorphismExportFailed("forward", error.message))
      nextBackward <- DenseFieldMorphism
        .coordinates(
          backward.from.domain,
          backward.to.domain,
          backward.from.grid,
          backward.sourceCoordinates.values,
          Resample.Method.Linear,
          cost,
          methodTag
        )
        .left
        .map(error => RegistrationError.MorphismExportFailed("backward", error.message))
    yield DenseMorphismPair(nextForward, nextBackward, forward.validity, backward.validity)

final case class DenseMorphismPair(
    forward: DenseFieldMorphism,
    backward: DenseFieldMorphism,
    forwardValidity: FieldValidity,
    backwardValidity: FieldValidity
)

object InversePair:
  /** Compose two sampled self-maps using identity outside their finite grids. */
  private[halfflow] def composeSelfExtended[A](
      left: InversePair[A, A],
      right: InversePair[A, A]
  ): Either[RegistrationError, InversePair[A, A]] =
    for
      nextForward <- left.forward.thenSelfExtended(right.forward)
      nextBackward <- right.backward.thenSelfExtended(left.backward)
    yield unsafe(nextForward, nextBackward)

  def make[A, B](
      forward: DensePull[A, B],
      backward: DensePull[B, A]
  ): Either[RegistrationError, InversePair[A, B]] =
    if forward.from.domain != backward.to.domain then
      Left(RegistrationError.FrameMismatch("inverse-pair A endpoint", forward.from.domain, backward.to.domain))
    else if forward.to.domain != backward.from.domain then
      Left(RegistrationError.FrameMismatch("inverse-pair B endpoint", forward.to.domain, backward.from.domain))
    else if forward.from.grid != backward.to.grid then Left(RegistrationError.GridMismatch("inverse-pair A endpoint"))
    else if forward.to.grid != backward.from.grid then Left(RegistrationError.GridMismatch("inverse-pair B endpoint"))
    else Right(unsafe(forward, backward))

  def identity[A](frame: Frame[A]): InversePair[A, A] =
    val pull = DensePull.identity(frame)
    unsafe(pull, pull)

  private[halfflow] def unsafe[A, B](
      forward: DensePull[A, B],
      backward: DensePull[B, A]
  ): InversePair[A, B] =
    new InversePair(forward, backward)

/** One midpoint arm: a sampled identity-extended residual followed by an exact affine. */
final case class MidpointArm[W, E] private (
    residual: InversePair[W, W],
    affine: AffineIso[W, E]
):
  def work: Frame[W] = residual.forward.from
  def endpoint: Frame[E] = affine.to

  def dense: Either[RegistrationError, InversePair[W, E]] =
    for
      forward <- affine.after(residual.forward)
      affineBackward = affine.inverse.dense.forward
      backward <- affineBackward.thenSelfExtended(residual.backward)
      pair <- InversePair.make(forward, backward)
    yield pair

  private[halfflow] def advance(
      left: InversePair[W, W]
  ): Either[RegistrationError, MidpointArm[W, E]] =
    InversePair.composeSelfExtended(left, residual).flatMap(MidpointArm.make(_, affine))

  private[halfflow] def regrid(
      newWork: Frame[W],
      newEndpoint: Frame[E]
  ): Either[RegistrationError, MidpointArm[W, E]] =
    val nextForwardResult = HalfFlowKernels.regridPull(
      residual.forward.sourceCoordinates,
      newWork.grid,
      residual.forward.validity,
      CoordinateMapOutside.Identity
    )
    val nextBackwardResult = HalfFlowKernels.regridPull(
      residual.backward.sourceCoordinates,
      newWork.grid,
      residual.backward.validity,
      CoordinateMapOutside.Identity
    )
    for
      nextForward <- DensePull.make(
        newWork,
        newWork,
        nextForwardResult.field,
        FieldValidity.copyMask(nextForwardResult.valid)
      )
      nextBackward <- DensePull.make(
        newWork,
        newWork,
        nextBackwardResult.field,
        FieldValidity.copyMask(nextBackwardResult.valid)
      )
      nextResidual <- InversePair.make(nextForward, nextBackward)
      nextAffine <- affine.reframe(newWork, newEndpoint)
      arm <- MidpointArm.make(nextResidual, nextAffine)
    yield arm

  private[halfflow] def residualGuardConfig(config: GuardConfig): GuardConfig =
    val weakestAffineScale = math.min(affine.determinant, 1.0 / affine.determinant)
    val worldScale = math.max(1.0, AffineGuardScale.world(affine))
    val voxelScale = math.max(1.0, AffineGuardScale.voxel(affine))
    config.copy(
      minimumJacobian = config.minimumJacobian / weakestAffineScale,
      maximumInverseErrorMm = config.maximumInverseErrorMm / worldScale,
      maximumInverseErrorVox = config.maximumInverseErrorVox / voxelScale
    )

object MidpointArm:
  def make[W, E](
      residual: InversePair[W, W],
      affine: AffineIso[W, E]
  ): Either[RegistrationError, MidpointArm[W, E]] =
    if residual.forward.from.domain != affine.from.domain then
      Left(RegistrationError.FrameMismatch("midpoint arm work", affine.from.domain, residual.forward.from.domain))
    else if residual.forward.from.grid != affine.from.grid then Left(RegistrationError.GridMismatch("midpoint arm work"))
    else Right(new MidpointArm(residual, affine))

  def identity[W, E](affine: AffineIso[W, E]): Either[RegistrationError, MidpointArm[W, E]] =
    make(InversePair.identity(affine.from), affine)

final case class Midpoint[W, F, M] private (
    fixed: MidpointArm[W, F],
    moving: MidpointArm[W, M]
):
  def work: Frame[W] = fixed.work

  def advance(halfFlow: InversePair[W, W]): Either[RegistrationError, Midpoint[W, F, M]] =
    for
      nextFixed <- fixed.advance(halfFlow)
      nextMoving <- moving.advance(halfFlow.inverse)
      next <- Midpoint.make(nextFixed, nextMoving)
    yield next

  private[halfflow] def advanceInto(
      halfFlow: InversePair[W, W],
      destination: MidpointAdvanceBuffer[W, F, M]
  ): Either[RegistrationError, Midpoint[W, F, M]] =
    destination.advance(this, halfFlow)

  private[halfflow] def relativeResidual: Either[RegistrationError, InversePair[W, W]] =
    InversePair.composeSelfExtended(fixed.residual.inverse, moving.residual)

  private[halfflow] def relativeResidualInto(
      destination: SelfPairComposeBuffer[W]
  ): Either[RegistrationError, InversePair[W, W]] =
    destination.compose(fixed.residual.inverse, moving.residual)

  private[halfflow] def relativeResidualGuardConfig(config: GuardConfig): GuardConfig =
    val determinant = moving.affine.determinant / fixed.affine.determinant
    val weakestAffineScale = math.min(determinant, 1.0 / determinant)
    val worldScale = math.max(AffineGuardScale.world(fixed.affine), AffineGuardScale.world(moving.affine))
    val voxelScale = math.max(AffineGuardScale.voxel(fixed.affine), AffineGuardScale.voxel(moving.affine))
    config.copy(
      minimumJacobian = config.minimumJacobian / weakestAffineScale,
      maximumInverseErrorMm = config.maximumInverseErrorMm / worldScale,
      maximumInverseErrorVox = config.maximumInverseErrorVox / voxelScale
    )

  def result: Either[RegistrationError, InversePair[F, M]] =
    val fixedToWork = fixed.affine.inverse.dense.forward
    val movingToWork = moving.affine.inverse.dense.forward
    for
      relative <- relativeResidual
      throughWork <- fixedToWork.thenSelfExtended(relative.forward)
      forward <- moving.affine.after(throughWork)
      backThroughWork <- movingToWork.thenSelfExtended(relative.backward)
      backward <- fixed.affine.after(backThroughWork)
      pair <- InversePair.make(forward, backward)
    yield pair

  def regrid(
      newWork: Frame[W],
      newFixed: Frame[F],
      newMoving: Frame[M]
  ): Either[RegistrationError, Midpoint[W, F, M]] =
    for
      nextFixed <- fixed.regrid(newWork, newFixed)
      nextMoving <- moving.regrid(newWork, newMoving)
      next <- Midpoint.make(nextFixed, nextMoving)
    yield next

object Midpoint:
  def make[W, F, M](
      fixed: MidpointArm[W, F],
      moving: MidpointArm[W, M]
  ): Either[RegistrationError, Midpoint[W, F, M]] =
    val left = fixed.work
    val right = moving.work
    if left.domain != right.domain then
      Left(RegistrationError.FrameMismatch("midpoint work", left.domain, right.domain))
    else if left.grid != right.grid then Left(RegistrationError.GridMismatch("midpoint work"))
    else Right(new Midpoint(fixed, moving))

  def identity[W, F, M](
      work: Frame[W],
      fixed: Frame[F],
      moving: Frame[M]
  ): Either[RegistrationError, Midpoint[W, F, M]] =
    for
      fixedAffine <- AffineIso.make(work, fixed, Affine3D.identity)
      movingAffine <- AffineIso.make(work, moving, Affine3D.identity)
      fixedArm <- MidpointArm.identity(fixedAffine)
      movingArm <- MidpointArm.identity(movingAffine)
      midpoint <- make(fixedArm, movingArm)
    yield midpoint

private object AffineGuardScale:
  def world[A, B](affine: AffineIso[A, B]): Double =
    inducedNormBound(affine.transform.matrix)

  def voxel[A, B](affine: AffineIso[A, B]): Double =
    val endpointInverse = affine.to.grid.affine3D.fold(
      error => throw new IllegalArgumentException(error.message),
      identity
    ).inverse
    val workToEndpointVoxel = Affine.multiply(
      endpointInverse,
      Affine.multiply(affine.transform.matrix, affine.from.grid.affine)
    )
    inducedNormBound(workToEndpointVoxel)

  private def inducedNormBound(matrix: DMat): Double =
    var maximumRowSum = 0.0
    var maximumColumnSum = 0.0
    var row = 0
    while row < 3 do
      var rowSum = 0.0
      var columnSum = 0.0
      var column = 0
      while column < 3 do
        rowSum += math.abs(matrix(row, column))
        columnSum += math.abs(matrix(column, row))
        column += 1
      maximumRowSum = math.max(maximumRowSum, rowSum)
      maximumColumnSum = math.max(maximumColumnSum, columnSum)
      row += 1
    math.sqrt(maximumRowSum * maximumColumnSum)

private[halfflow] final class MidpointAdvanceBuffer[W, F, M] private (
    val work: Frame[W],
    private val fixedForward: MidpointPullBuffer,
    private val fixedBackward: MidpointPullBuffer,
    private val movingForward: MidpointPullBuffer,
    private val movingBackward: MidpointPullBuffer,
    private val workSampler: DenseFieldSampler
):
  val ownedCoordinateBuffers: Int = 4
  val ownedValidityBuffers: Int = 4

  def advance(
      current: Midpoint[W, F, M],
      halfFlow: InversePair[W, W]
  ): Either[RegistrationError, Midpoint[W, F, M]] =
    if current.work != work then Left(RegistrationError.GridMismatch("midpoint advance workspace"))
    else if halfFlow.forward.from != work || halfFlow.forward.to != work then
      Left(RegistrationError.GridMismatch("midpoint advance flow"))
    else
      compose(halfFlow.forward, current.fixed.residual.forward, fixedForward)
      compose(current.fixed.residual.backward, halfFlow.backward, fixedBackward)
      compose(halfFlow.backward, current.moving.residual.forward, movingForward)
      compose(current.moving.residual.backward, halfFlow.forward, movingBackward)
      val nextFixedResidual = InversePair.unsafe(pull(fixedForward), pull(fixedBackward))
      val nextMovingResidual = InversePair.unsafe(pull(movingForward), pull(movingBackward))
      for
        nextFixed <- MidpointArm.make(nextFixedResidual, current.fixed.affine)
        nextMoving <- MidpointArm.make(nextMovingResidual, current.moving.affine)
        next <- Midpoint.make(nextFixed, nextMoving)
      yield next

  private def compose(
      left: DensePull[W, W],
      right: DensePull[W, W],
      out: MidpointPullBuffer
  ): Unit =
    HalfFlowKernels.composePullInto(
      left.sourceCoordinates,
      right.sourceCoordinates,
      out.values,
      out.valid,
      workSampler,
      left.validity,
      right.validity,
      CoordinateMapOutside.Identity
    )

  private def pull(buffer: MidpointPullBuffer): DensePull[W, W] =
    DensePull.unsafe(
      work,
      work,
      DenseVectorField.fromLegacyPlanar(
        work.grid,
        buffer.values,
        DenseVectorFieldKind.SourceCoordinates
      ),
      FieldValidity.copyMask(buffer.valid)
    )

private[halfflow] object MidpointAdvanceBuffer:
  def apply[W, F, M](state: Midpoint[W, F, M]): MidpointAdvanceBuffer[W, F, M] =
    new MidpointAdvanceBuffer(
      state.work,
      MidpointPullBuffer(state.work.grid),
      MidpointPullBuffer(state.work.grid),
      MidpointPullBuffer(state.work.grid),
      MidpointPullBuffer(state.work.grid),
      DenseFieldSampler(state.work.grid)
    )

private[halfflow] final class SelfPairComposeBuffer[A] private (
    val frame: Frame[A],
    private val forward: MidpointPullBuffer,
    private val backward: MidpointPullBuffer,
    private val sampler: DenseFieldSampler
):
  val ownedCoordinateBuffers: Int = 2
  val ownedValidityBuffers: Int = 2

  def compose(
      left: InversePair[A, A],
      right: InversePair[A, A]
  ): Either[RegistrationError, InversePair[A, A]] =
    if left.forward.from != frame || right.forward.from != frame then
      Left(RegistrationError.GridMismatch("self-pair composition buffer"))
    else
      composePull(left.forward, right.forward, forward)
      composePull(right.backward, left.backward, backward)
      Right(InversePair.unsafe(pull(forward), pull(backward)))

  private def composePull(
      left: DensePull[A, A],
      right: DensePull[A, A],
      out: MidpointPullBuffer
  ): Unit =
    HalfFlowKernels.composePullInto(
      left.sourceCoordinates,
      right.sourceCoordinates,
      out.values,
      out.valid,
      sampler,
      left.validity,
      right.validity,
      CoordinateMapOutside.Identity
    )

  private def pull(buffer: MidpointPullBuffer): DensePull[A, A] =
    DensePull.unsafe(
      frame,
      frame,
      DenseVectorField.fromLegacyPlanar(
        frame.grid,
        buffer.values,
        DenseVectorFieldKind.SourceCoordinates
      ),
      FieldValidity.copyMask(buffer.valid)
    )

private[halfflow] object SelfPairComposeBuffer:
  def apply[A](frame: Frame[A]): SelfPairComposeBuffer[A] =
    new SelfPairComposeBuffer(
      frame,
      MidpointPullBuffer(frame.grid),
      MidpointPullBuffer(frame.grid),
      DenseFieldSampler(frame.grid)
    )

private final class MidpointPullBuffer private (
    val values: Array[Double],
    val valid: Array[Boolean]
)

private object MidpointPullBuffer:
  def apply(grid: GridSpec): MidpointPullBuffer =
    new MidpointPullBuffer(
      PrimitiveBuffers.ofSize[Double](grid.nVoxels * 3),
      PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    )
