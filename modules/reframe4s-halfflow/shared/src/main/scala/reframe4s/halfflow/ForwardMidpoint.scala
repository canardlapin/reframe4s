package reframe4s.halfflow

import image4s.geometry.{Affine, D3}
import reframe4s.halfflow.internal.*

/** Paired sampled increments. Neither map is claimed to be the exact inverse of the other. */
final case class HalfStep[A] private (
    plus: DensePull[A, A],
    minus: DensePull[A, A]
)

object HalfStep:
  def make[A](plus: DensePull[A, A], minus: DensePull[A, A]): Either[RegistrationError, HalfStep[A]] =
    if plus.from != plus.to || minus.from != minus.to || plus.from != minus.from then
      Left(RegistrationError.GridMismatch("HalfFlow-CC paired half step"))
    else Right(new HalfStep(plus, minus))

  def fromPairedFlow[A](flow: PairedFlow[A]): HalfStep[A] =
    new HalfStep(flow.pair.forward, flow.pair.backward)

/** Optional numerical acceleration state, never an anatomical acceptance authority. */
final case class ApproximateInverseCache[A](
    inverse: DensePull[A, A],
    measuredErrorMm: Double,
    refreshes: Int
)

object ApproximateInverseCache:
  def refresh[A](
      forward: DensePull[A, A],
      config: ResidualInverseConfig = ResidualInverseConfig.default,
      previousRefreshes: Int = 0
  ): ApproximateInverseCache[A] =
    val refined = ResidualInverseRefiner.refine(forward, config)
    ApproximateInverseCache(
      refined.inverse,
      refined.report.maximumInteriorMm,
      previousRefreshes + 1
    )

object HalfStepNumerics:
  def evaluate[A](
      step: HalfStep[A],
      maximumInverseErrorMm: Double,
      interiorMargin: Int = 2,
      minimumInteriorValidFraction: Double = 0.95
  ): NumericalVerdict =
    require(maximumInverseErrorMm.isFinite && maximumInverseErrorMm >= 0.0)
    require(interiorMargin >= 0)
    require(
      minimumInteriorValidFraction.isFinite && minimumInteriorValidFraction > 0.0 &&
        minimumInteriorValidFraction <= 1.0
    )
    val error = math.max(
      interiorP99(step.plus, step.minus, interiorMargin, minimumInteriorValidFraction),
      interiorP99(step.minus, step.plus, interiorMargin, minimumInteriorValidFraction)
    )
    if error.isFinite && error <= maximumInverseErrorMm then NumericalVerdict.Accurate
    else NumericalVerdict.IncreaseIntegrationDepth(error)

  private def interiorP99[A](
      left: DensePull[A, A],
      right: DensePull[A, A],
      margin: Int,
      minimumValidFraction: Double
  ): Double =
    val grid = left.from.grid
    val n = grid.nVoxels
    val composed = PrimitiveBuffers.ofSize[Double](3 * n)
    val valid = PrimitiveBuffers.ofSize[Boolean](n)
    HalfFlowKernels.composePullInto(
      left.sourceCoordinates,
      right.sourceCoordinates,
      composed,
      valid,
      left.validity,
      right.validity,
      CoordinateMapOutside.Identity
    )
    val identity = PrimitiveBuffers.ofSize[Double](3 * n)
    val identityValid = PrimitiveBuffers.ofSize[Boolean](n)
    HalfFlowKernels.identityInto(grid, identity, identityValid)
    val eligible =
      math.max(0, grid.shape(0) - 2 * margin) * math.max(0, grid.shape(1) - 2 * margin) *
        math.max(0, grid.shape(2) - 2 * margin)
    val capacity = math.max(1, eligible)
    val errors = new Array[Double](capacity)
    var count = 0
    var index = 0
    while index < n do
      val x = index % grid.shape(0)
      val yz = index / grid.shape(0)
      val y = yz % grid.shape(1)
      val z = yz / grid.shape(1)
      val interior = x >= margin && x < grid.shape(0) - margin && y >= margin &&
        y < grid.shape(1) - margin && z >= margin && z < grid.shape(2) - margin
      if interior && valid(index) then
        val dx = composed(index) - identity(index)
        val dy = composed(index + n) - identity(index + n)
        val dz = composed(index + 2 * n) - identity(index + 2 * n)
        errors(count) = math.sqrt(dx * dx + dy * dy + dz * dz)
        count += 1
      index += 1
    index = count
    while index < errors.length do
      errors(index) = Double.PositiveInfinity
      index += 1
    scala.util.Sorting.quickSort(errors)
    if eligible == 0 || count.toDouble / eligible.toDouble < minimumValidFraction then Double.PositiveInfinity
    else errors(math.floor(0.99 * (count - 1).toDouble).toInt)

final case class ForwardGeometryConfig(
    minimumIncrementJacobian: Double = 0.05,
    minimumAccumulatedJacobian: Double = 0.05,
    minimumValidFraction: Double = 0.95,
    interiorMargin: Int = 1
):
  require(minimumIncrementJacobian.isFinite && minimumIncrementJacobian > 0.0)
  require(minimumAccumulatedJacobian.isFinite && minimumAccumulatedJacobian > 0.0)
  require(minimumValidFraction.isFinite && minimumValidFraction > 0.0 && minimumValidFraction <= 1.0)
  require(interiorMargin >= 1)

final case class ForwardJacobianReport(
    minimum: Double,
    evaluated: Int,
    eligible: Int,
    validFraction: Double,
    nonPositive: Int
)

final class ForwardGeometryWorkspace private (
    val grid: GridSpec,
    private[halfflow] val determinants: Array[Double],
    private[halfflow] val valid: Array[Boolean],
    private[halfflow] val sampler: DenseFieldSampler,
    private[halfflow] val reduction: JacobianReduction
)

object ForwardGeometryWorkspace:
  def apply(grid: GridSpec): ForwardGeometryWorkspace =
    new ForwardGeometryWorkspace(
      grid,
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Boolean](grid.nVoxels),
      DenseFieldSampler(grid),
      JacobianReduction()
    )

object ForwardGeometry:
  def incremental[A](
      step: HalfStep[A],
      config: ForwardGeometryConfig = ForwardGeometryConfig()
  ): (GeometryVerdict, Vector[ForwardJacobianReport]) =
    val workspace = ForwardGeometryWorkspace(step.plus.from.grid)
    val plus = report(step.plus, workspace, config.interiorMargin)
    val minus = report(step.minus, workspace, config.interiorMargin)
    val minimum = math.min(plus.minimum, minus.minimum)
    val valid = acceptable(plus, config.minimumIncrementJacobian, config.minimumValidFraction) &&
      acceptable(minus, config.minimumIncrementJacobian, config.minimumValidFraction)
    (
      if valid then GeometryVerdict.Valid else GeometryVerdict.IncrementJacobianTooSmall(minimum),
      Vector(plus, minus)
    )

  def accumulated[W, F, M](
      state: ForwardMidpoint[W, F, M],
      config: ForwardGeometryConfig = ForwardGeometryConfig()
  ): (GeometryVerdict, Vector[ForwardJacobianReport]) =
    val workspace = ForwardGeometryWorkspace(state.work.grid)
    val fixed = report(state.fixed.residual, workspace, config.interiorMargin)
    val moving = report(state.moving.residual, workspace, config.interiorMargin)
    val verdict =
      if !acceptable(fixed, config.minimumAccumulatedJacobian, config.minimumValidFraction) then
        GeometryVerdict.AccumulatedJacobianTooSmall(MidpointArmName.Fixed, fixed.minimum)
      else if !acceptable(moving, config.minimumAccumulatedJacobian, config.minimumValidFraction) then
        GeometryVerdict.AccumulatedJacobianTooSmall(MidpointArmName.Moving, moving.minimum)
      else GeometryVerdict.Valid
    (verdict, Vector(fixed, moving))

  private def report[A](
      pull: DensePull[A, A],
      workspace: ForwardGeometryWorkspace,
      interiorMargin: Int
  ): ForwardJacobianReport =
    require(workspace.grid == pull.from.grid, "forward geometry workspace/grid mismatch")
    HalfFlowKernels.jacobianDeterminantsReduceInto(
      pull.sourceCoordinates,
      workspace.determinants,
      workspace.valid,
      workspace.sampler,
      pull.validity,
      workspace.reduction,
      interiorMargin
    )
    val eligible =
      math.max(0, workspace.grid.shape(0) - 2 * interiorMargin) *
        math.max(0, workspace.grid.shape(1) - 2 * interiorMargin) *
        math.max(0, workspace.grid.shape(2) - 2 * interiorMargin)
    ForwardJacobianReport(
      workspace.reduction.minimumOrNaN,
      workspace.reduction.evaluated,
      eligible,
      if eligible == 0 then 0.0 else workspace.reduction.evaluated.toDouble / eligible.toDouble,
      workspace.reduction.nonPositive
    )

  private def acceptable(report: ForwardJacobianReport, floor: Double, minimumValidFraction: Double): Boolean =
    report.minimum.isFinite && report.minimum >= floor && report.nonPositive == 0 &&
      report.validFraction >= minimumValidFraction

/** One authoritative forward residual followed by an exact affine factor. */
final case class ForwardMidpointArm[W, E] private (
    residual: DensePull[W, W],
    affine: AffineIso[W, E]
):
  def work: RegistrationFrame[W] = residual.from
  def endpoint: RegistrationFrame[E] = affine.to

  def denseForward: Either[RegistrationError, DensePull[W, E]] =
    affine.after(residual)

  private[halfflow] def advance(
      increment: DensePull[W, W]
  ): Either[RegistrationError, ForwardMidpointArm[W, E]] =
    increment.thenSelfExtended(residual).flatMap(ForwardMidpointArm.make(_, affine))

  def regrid(
      newWork: RegistrationFrame[W],
      newEndpoint: RegistrationFrame[E]
  ): Either[RegistrationError, ForwardMidpointArm[W, E]] =
    val regridded = HalfFlowKernels.regridPull(
      residual.sourceCoordinates,
      newWork.grid,
      residual.validity,
      CoordinateMapOutside.Identity
    )
    for
      pull <- DensePull.make(newWork, newWork, regridded.field, FieldValidity.copyMask(regridded.valid))
      exactAffine <- affine.reframe(newWork, newEndpoint)
      arm <- ForwardMidpointArm.make(pull, exactAffine)
    yield arm

object ForwardMidpointArm:
  def make[W, E](
      residual: DensePull[W, W],
      affine: AffineIso[W, E]
  ): Either[RegistrationError, ForwardMidpointArm[W, E]] =
    if residual.from.domain != affine.from.domain then
      Left(RegistrationError.FrameMismatch("forward midpoint arm", affine.from.domain, residual.from.domain))
    else if residual.from.grid != affine.from.grid then
      Left(RegistrationError.GridMismatch("forward midpoint arm"))
    else Right(new ForwardMidpointArm(residual, affine))

  def identity[W, E](affine: AffineIso[W, E]): ForwardMidpointArm[W, E] =
    new ForwardMidpointArm(DensePull.identity(affine.from), affine)

  private[halfflow] def unsafe[W, E](
      residual: DensePull[W, W],
      affine: AffineIso[W, E]
  ): ForwardMidpointArm[W, E] =
    new ForwardMidpointArm(residual, affine)

/** Experimental HalfFlow-CC state: two forward W-to-endpoint pull maps meeting at W. */
final case class ForwardMidpoint[W, F, M] private (
    fixed: ForwardMidpointArm[W, F],
    moving: ForwardMidpointArm[W, M]
):
  def work: RegistrationFrame[W] = fixed.work

  def advance(step: HalfStep[W]): Either[RegistrationError, ForwardMidpoint[W, F, M]] =
    for
      nextFixed <- fixed.advance(step.plus)
      nextMoving <- moving.advance(step.minus)
      next <- ForwardMidpoint.make(nextFixed, nextMoving)
    yield next

  def swap: ForwardMidpoint[W, M, F] =
    ForwardMidpoint.unsafe(moving, fixed)

  def regrid(
      newWork: RegistrationFrame[W],
      newFixed: RegistrationFrame[F],
      newMoving: RegistrationFrame[M]
  ): Either[RegistrationError, ForwardMidpoint[W, F, M]] =
    for
      nextFixed <- fixed.regrid(newWork, newFixed)
      nextMoving <- moving.regrid(newWork, newMoving)
      next <- ForwardMidpoint.make(nextFixed, nextMoving)
    yield next

object ForwardMidpoint:
  def make[W, F, M](
      fixed: ForwardMidpointArm[W, F],
      moving: ForwardMidpointArm[W, M]
  ): Either[RegistrationError, ForwardMidpoint[W, F, M]] =
    if fixed.work.domain != moving.work.domain then
      Left(RegistrationError.FrameMismatch("forward midpoint work", fixed.work.domain, moving.work.domain))
    else if fixed.work.grid != moving.work.grid then Left(RegistrationError.GridMismatch("forward midpoint work"))
    else Right(unsafe(fixed, moving))

  def fromLegacy[W, F, M](state: Midpoint[W, F, M]): ForwardMidpoint[W, F, M] =
    unsafe(
      ForwardMidpointArm.unsafe(state.fixed.residual.forward, state.fixed.affine),
      ForwardMidpointArm.unsafe(state.moving.residual.forward, state.moving.affine)
    )

  def identity[W, F, M](
      work: RegistrationFrame[W],
      fixed: RegistrationFrame[F],
      moving: RegistrationFrame[M]
  ): Either[RegistrationError, ForwardMidpoint[W, F, M]] =
    for
      fixedAffine <- AffineIso.make(work, fixed, Affine.identity[D3])
      movingAffine <- AffineIso.make(work, moving, Affine.identity[D3])
      state <- make(ForwardMidpointArm.identity(fixedAffine), ForwardMidpointArm.identity(movingAffine))
    yield state

  private def unsafe[W, F, M](
      fixed: ForwardMidpointArm[W, F],
      moving: ForwardMidpointArm[W, M]
  ): ForwardMidpoint[W, F, M] =
    new ForwardMidpoint(fixed, moving)
