"""Bounded inverse evaluation seeded by the original stored inverse.

This research evaluator retains the existing boundary-linear trilinear map.
It creates no denser persistent inverse and never substitutes a failed query.
"""
import numpy as np
from field_oracle import sample


def evaluate(forward, forward_valid, inverse, inverse_valid, shape, affine, targets,
             iterations, tolerance=1e-8, step_cap=4., backtracks=12):
    if type(iterations) is not int or not 0 <= iterations <= 8:
        raise ValueError('Declared correction budget must be an integer from zero through eight')
    if tolerance <= 0 or step_cap <= 0 or backtracks < 1:
        raise ValueError('Positive bounded solver settings required')
    targets = np.asarray(targets, dtype=float)
    q, seed_valid, _ = sample(inverse, inverse_valid, shape, affine, targets)
    if not np.isfinite(q).all():
        raise ValueError('Nonfinite inverse seed; no substitution permitted')
    blocked = ~seed_valid.copy()
    indices_all = np.arange(len(q))
    counters = {name: np.zeros(len(q), dtype=np.int64) for name in
                ['forward_point_samples', 'forward_derivative_samples', 'linear_solves', 'backtracking_trials']}

    def forward_at(points, indices, derivatives=False):
        counters['forward_point_samples'][indices] += 1
        if derivatives:
            counters['forward_derivative_samples'][indices] += 1
        return sample(forward, forward_valid, shape, affine, points, derivatives)

    used = 0
    for iteration in range(iterations):
        f, valid, jac = forward_at(q, indices_all, True)
        residual = f - targets
        norm = np.linalg.norm(residual, axis=1)
        determinant = np.linalg.det(jac)
        active = valid & seed_valid & (norm > tolerance) & ~blocked
        if not active.any():
            break
        good = active & np.isfinite(determinant) & (np.abs(determinant) > 1e-12)
        blocked |= active & ~good
        if not good.any():
            break
        indices = np.flatnonzero(good)
        counters['linear_solves'][indices] += 1
        step = np.linalg.solve(jac[indices], residual[indices, :, None])[:, :, 0]
        length = np.linalg.norm(step, axis=1)
        step *= np.minimum(1., step_cap / np.maximum(length, 1e-300))[:, None]
        accepted = np.zeros(len(indices), dtype=bool)
        for halving in range(backtracks):
            remaining = np.flatnonzero(~accepted)
            if not len(remaining):
                break
            at = indices[remaining]
            trial = q[at] - step[remaining] * (.5 ** halving)
            sampled, available, _ = forward_at(trial, at)
            counters['backtracking_trials'][at] += 1
            improves = available & (np.linalg.norm(sampled - targets[at], axis=1) < norm[at])
            q[at[improves]] = trial[improves]
            accepted[remaining[improves]] = True
        blocked[indices[~accepted]] = True
        used = iteration + 1
    # Include final residual/Jacobian validation in the reported evaluation work.
    f, valid, jac = forward_at(q, indices_all, True)
    error = np.linalg.norm(f - targets, axis=1)
    determinant = np.linalg.det(jac)
    supported = seed_valid & valid & np.isfinite(error)
    converged = supported & (error <= tolerance)
    finite = np.isfinite(error)
    finite_jac = np.isfinite(determinant)
    report = dict(targets=len(q), supported=int(supported.sum()), unsupported=int((~supported).sum()),
                  converged=int(converged.sum()), unresolved=int((~converged).sum()),
                  all_converged=bool(converged.all()), blocked=int(blocked.sum()), iterations_used=used,
                  maximum_equation_residual_mm=float(error[finite].max()) if finite.any() else None,
                  minimum_root_jacobian=float(determinant[finite_jac].min()) if finite_jac.any() else None,
                  nonpositive_root_jacobians=int((~finite_jac | (determinant <= 0)).sum()),
                  seed_point_samples=len(q),
                  work={name: dict(total=int(values.sum()), maximum_per_query=int(values.max()))
                        for name, values in counters.items()},
                  work_includes_final_residual_and_jacobian_validation=True)
    return q, supported, report
