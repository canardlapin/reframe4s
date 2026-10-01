"""Numerical core for a proposed nuisance-projected MRI registration method.

This is NOT a complete MRI registration program: no NIfTI I/O, image pyramid,
spatial interpolation, transform optimizer, masks, coreset selection, or FFT
initializer is implemented here. It implements and checks the proposed local
patch loss, its exact gradient, a positive-semidefinite EM/Gauss-Newton
curvature approximation, and a scalar-nuisance Schur complement.

The caller supplies moving-image samples and their derivatives with respect
to transform parameters. Those derivatives must be derivatives of the SAME
interpolant that produces the samples. Patch positions and reference weights
must remain fixed during a local step. Degenerate/invalid moving patches
must receive an explicit outlier cost in a complete implementation; silently
dropping them would change the objective and invite overlap collapse.

Requirements: Python 3.10+, NumPy.
Run: python nuisance_projected_registration_core.py
"""
from __future__ import annotations

from dataclasses import dataclass
import json
import math
import numpy as np
from numpy.typing import ArrayLike, NDArray

FloatArray = NDArray[np.float64]


@dataclass(frozen=True)
class PatchTerms:
    loss: float
    gradient: FloatArray
    curvature: FloatArray
    correlation: float
    inlier_weight: float
    conditional_polarity: float


def _vector(value: ArrayLike, name: str) -> FloatArray:
    x = np.asarray(value, dtype=np.float64)
    if x.ndim != 1 or not np.all(np.isfinite(x)):
        raise ValueError(f"{name} must be a finite one-dimensional array")
    return x


def _unit_centered(x: FloatArray, minimum_norm: float) -> tuple[FloatArray, float]:
    c = x - np.mean(x)
    norm = float(np.linalg.norm(c))
    if norm <= minimum_norm:
        raise ValueError("Degenerate patch: centered intensity norm is too small")
    return c / norm, norm


def normalized_patch_jacobian(
    moving: ArrayLike, d_moving: ArrayLike, *, minimum_norm: float = 1e-10
) -> tuple[FloatArray, FloatArray]:
    """Return v = centered(f)/norm and its exact parameter Jacobian.

    d_moving[j, k] is the derivative of moving sample j with respect to
    parameter k. No dense m-by-m projection matrix is constructed.
    """
    f = _vector(moving, "moving")
    g = np.asarray(d_moving, dtype=np.float64)
    if minimum_norm <= 0 or not math.isfinite(minimum_norm):
        raise ValueError("minimum_norm must be finite and positive")
    if f.size < 4:
        raise ValueError("Use at least four samples in a patch")
    if g.ndim != 2 or g.shape[0] != f.size or g.shape[1] < 1:
        raise ValueError("d_moving must have shape (number of samples, parameters)")
    if not np.all(np.isfinite(g)):
        raise ValueError("d_moving contains nonfinite values")
    v, norm = _unit_centered(f, minimum_norm)
    gc = g - np.mean(g, axis=0, keepdims=True)
    j = (gc - np.outer(v, v @ gc)) / norm
    return v, j


def patch_terms(
    reference: ArrayLike,
    moving: ArrayLike,
    d_moving: ArrayLike,
    *,
    p_positive: float = 0.1,
    tau: float = 0.55,
    outlier_floor: float = 0.02,
    minimum_norm: float = 1e-10,
) -> PatchTerms:
    """Compute robust sign-mixture loss and local optimization terms.

    L = -tau^2 log[eps + (1-eps) *
           (pi exp(-(1-c)/tau^2) + (1-pi) exp(-(1+c)/tau^2))].

    c is the centered patch correlation. eps is an outlier affinity floor,
    NOT a calibrated probability of registration failure. Parameter defaults
    are illustrative, not empirically validated MRI defaults.

    The returned gradient is exact away from degeneracy/validity boundaries.
    The returned curvature is an EM/Gauss-Newton approximation, NOT the exact
    Hessian. It is positive semidefinite. Trust-region or line-search
    acceptance must evaluate the original loss.
    """
    y = _vector(reference, "reference")
    f = _vector(moving, "moving")
    if y.shape != f.shape:
        raise ValueError("reference and moving must have the same shape")
    if not 0.0 <= p_positive <= 1.0:
        raise ValueError("p_positive must be in [0, 1]")
    if not 0.0 <= outlier_floor < 1.0:
        raise ValueError("outlier_floor must be in [0, 1)")
    if not math.isfinite(tau) or tau <= 0:
        raise ValueError("tau must be finite and positive")
    v, j = normalized_patch_jacobian(f, d_moving, minimum_norm=minimum_norm)
    u, _ = _unit_centered(y, minimum_norm)
    c = float(u @ v)
    t2 = tau * tau
    log_inlier = math.log1p(-outlier_floor)
    logs = np.array([
        math.log(outlier_floor) if outlier_floor > 0 else -np.inf,
        log_inlier + math.log(p_positive) - (1.0 - c) / t2
            if p_positive > 0 else -np.inf,
        log_inlier + math.log1p(-p_positive) - (1.0 + c) / t2
            if p_positive < 1 else -np.inf,
    ])
    logz = float(np.logaddexp.reduce(logs))
    weights = np.exp(logs - logz)
    wp, wm = float(weights[1]), float(weights[2])
    win = wp + wm
    signed_weight = wp - wm
    return PatchTerms(
        loss=-t2 * logz,
        gradient=-signed_weight * (j.T @ u),
        curvature=win * (j.T @ j),
        correlation=c,
        inlier_weight=win,
        conditional_polarity=signed_weight / win if win > 0 else 0.0,
    )


def scalar_nuisance_schur(
    residual: ArrayLike, jacobian: ArrayLike, nuisance_derivative: ArrayLike,
    *, nuisance_sd: float
) -> tuple[FloatArray, FloatArray, float]:
    """Profile a scalar nuisance from a LINEARIZED, unit-noise least square.

    min_d ||r + J delta + k d||^2 + d^2 / nuisance_sd^2
        = delta.T H delta - 2 b.T delta + constant.

    Returns H, b, constant; solve H delta = b after adding damping/prior.
    This is profiling, not integration over d (no log-determinant term).
    nuisance_sd must be finite: removing all information along a nuisance
    direction can destroy identifiability of the affine transform.
    """
    r = _vector(residual, "residual")
    k = _vector(nuisance_derivative, "nuisance_derivative")
    j = np.asarray(jacobian, dtype=np.float64)
    if k.shape != r.shape or j.ndim != 2 or j.shape[0] != r.size:
        raise ValueError("incompatible residual, Jacobian, and nuisance shapes")
    if not np.all(np.isfinite(j)):
        raise ValueError("jacobian contains nonfinite values")
    if not math.isfinite(nuisance_sd) or nuisance_sd <= 0:
        raise ValueError("nuisance_sd must be finite and positive")
    denom = 1.0 / nuisance_sd**2 + float(k @ k)
    v = j.T @ k
    kr = float(k @ r)
    h = j.T @ j - np.outer(v, v) / denom
    b = -(j.T @ r) + v * kr / denom
    constant = float(r @ r) - kr**2 / denom
    return h, b, constant


def self_test(seed: int = 417) -> dict[str, float]:
    """Algebra/finite-difference tests only; no MRI benchmark is performed."""
    rng = np.random.default_rng(seed)
    m, d = 27, 12
    e = rng.normal(size=m)
    f = rng.normal(size=m)
    g = rng.normal(size=(m, d))
    step = 1e-6
    v, j = normalized_patch_jacobian(f, g)
    jfd = np.column_stack([
        (_unit_centered(f + step * g[:, k], 1e-10)[0]
         - _unit_centered(f - step * g[:, k], 1e-10)[0]) / (2 * step)
        for k in range(d)
    ])
    jac_error = float(np.linalg.norm(j - jfd) / np.linalg.norm(jfd))
    terms = patch_terms(e, f, g)
    gfd = np.array([
        (patch_terms(e, f + step * g[:, k], g).loss
         - patch_terms(e, f - step * g[:, k], g).loss) / (2 * step)
        for k in range(d)
    ])
    grad_error = float(np.linalg.norm(terms.gradient - gfd) / np.linalg.norm(gfd))
    cmat = np.eye(m) - np.ones((m, m)) / m
    u, _ = _unit_centered(e, 1e-10)
    design = np.column_stack([np.ones(m), f])
    coeff = np.linalg.lstsq(design, e, rcond=None)[0]
    sse = float(np.linalg.norm(e - design @ coeff)**2)
    sse_identity = float(np.linalg.norm(cmat @ e)**2 * (1.0 - (u @ v)**2))
    q, _ = np.linalg.qr(design, mode="reduced")
    projection_error = float(np.linalg.norm(
        (cmat - np.outer(v, v)) - (np.eye(m) - q @ q.T)))
    r = rng.normal(size=m)
    j0 = rng.normal(size=(m, d))
    k = rng.normal(size=m)
    sd, damping = 2.0, 0.3
    h, b, _ = scalar_nuisance_schur(r, j0, k, nuisance_sd=sd)
    reduced = np.linalg.solve(h + damping * np.eye(d), b)
    full_h = np.block([
        [j0.T @ j0 + damping * np.eye(d), (j0.T @ k)[:, None]],
        [(k @ j0)[None, :], np.array([[k @ k + 1.0 / sd**2]])],
    ])
    full = np.linalg.solve(full_h, -np.r_[j0.T @ r, k @ r])
    schur_error = float(np.linalg.norm(full[:d] - reduced))
    invariance_error = abs(terms.loss - patch_terms(3.0 * e + 7, 2.0 * f - 4, 2.0 * g).loss)
    result = {
        "normalized_patch_jacobian_relative_error": jac_error,
        "robust_sign_mixture_gradient_relative_error": grad_error,
        "profiled_regression_identity_absolute_error": abs(sse - sse_identity),
        "nuisance_projection_absolute_error": projection_error,
        "scalar_nuisance_schur_step_absolute_error": schur_error,
        "positive_gain_offset_invariance_absolute_error": invariance_error,
    }
    assert jac_error < 1e-7, result
    assert grad_error < 1e-6, result
    assert abs(sse - sse_identity) < 1e-10, result
    assert projection_error < 1e-10, result
    assert schur_error < 1e-10, result
    assert invariance_error < 1e-10, result
    assert np.linalg.eigvalsh(terms.curvature).min() > -1e-10
    return result


if __name__ == "__main__":
    print(json.dumps(self_test(), indent=2))
