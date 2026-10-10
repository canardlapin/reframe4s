package reframe4s.halfflow

import image4s.geometry.{Affine, D3}
import image4s.geometry.Frame as CanonicalFrame
import reframe4s.core.AffineMap

/** The direction in which a canonical affine was supplied to HalfFlow. */
enum SuppliedAffineDirection:
  case MovingToFixed
  case FixedToMoving

/** A supplied affine compiled into HalfFlow's exact affine factorization.
  *
  * `fixedToMoving` is the pull direction used by HalfFlow. `initial` contains
  * identity residuals and the two exact affine half factors, so passing it to
  * [[HalfFlowCc.register]] applies the supplied affine once rather than using
  * it as an additional residual initializer.
  */
final case class SuppliedAffineInitialization[W, F, M] private (
    fixedToMoving: AffineIso[F, M],
    initial: ForwardMidpoint[W, F, M],
    diagnostics: AffineInitializationDiagnostics,
    suppliedDirection: SuppliedAffineDirection
)

object SuppliedAffineInitialization:
  /** Compile a canonical moving-to-fixed result, such as Flashalign output.
    *
    * HalfFlow samples moving data from fixed coordinates, so this entry point
    * takes the exact inverse coordinate operator exactly once before delegating
    * to the existing supplied-affine initializer.
    */
  def fromMovingToFixed[
      W,
      F,
      M,
      MovingFrame <: CanonicalFrame[D3],
      FixedFrame <: CanonicalFrame[D3]
  ](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      work: RegistrationFrame[W],
      movingToFixed: AffineMap[MovingFrame, FixedFrame, D3]
  ): Either[RegistrationError, SuppliedAffineInitialization[W, F, M]] =
    if !movingToFixed.source.sameRuntimeOwnerAs(moving.frame.canonical) ||
        !movingToFixed.target.sameRuntimeOwnerAs(fixed.frame.canonical) then
      Left(RegistrationError.PhysicalFrameMismatch("supplied movingToFixed"))
    else
      compile(
        fixed,
        moving,
        work,
        movingToFixed.operator.inverse,
        SuppliedAffineDirection.MovingToFixed
      )

  /** Compile an already pull-directed canonical fixed-to-moving affine. */
  def fromFixedToMoving[
      W,
      F,
      M,
      FixedFrame <: CanonicalFrame[D3],
      MovingFrame <: CanonicalFrame[D3]
  ](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      work: RegistrationFrame[W],
      fixedToMoving: AffineMap[FixedFrame, MovingFrame, D3]
  ): Either[RegistrationError, SuppliedAffineInitialization[W, F, M]] =
    if !fixedToMoving.source.sameRuntimeOwnerAs(fixed.frame.canonical) ||
        !fixedToMoving.target.sameRuntimeOwnerAs(moving.frame.canonical) then
      Left(RegistrationError.PhysicalFrameMismatch("supplied fixedToMoving"))
    else
      compile(
        fixed,
        moving,
        work,
        fixedToMoving.operator,
        SuppliedAffineDirection.FixedToMoving
      )

  private def compile[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      work: RegistrationFrame[W],
      fixedToMoving: Affine[D3],
      suppliedDirection: SuppliedAffineDirection
  ): Either[RegistrationError, SuppliedAffineInitialization[W, F, M]] =
    for
      legacy <- AffineInitializer.supplied(fixed, moving, work, fixedToMoving)
    yield
      new SuppliedAffineInitialization(
        legacy.affine,
        ForwardMidpoint.fromLegacy(legacy.midpoint),
        legacy.diagnostics,
        suppliedDirection
      )
