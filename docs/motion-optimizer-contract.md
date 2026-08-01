# Motion optimizer and oracle contract

This document is normative for the production rigid estimator introduced by
`MIG-420`. It refines `DEC-027`, `DEC-028`, `API-007`, `API-023`, and
`AC-044` through `AC-049` in `PRD.json`. Where the current coordinate-search
implementation differs, this contract describes the replacement rather than
the current behavior.

## Roles, direction, and update convention

For one pairwise fit:

- `moving: Sampled[Moving, D3, Double, Scalar, R]` is the volume being
  corrected.
- `fixed: Sampled[Fixed, D3, Double, Scalar, R]` defines the target domain and
  reference intensities.
- The authoritative estimated value is
  `movingToFixed: Rigid3[Moving, Fixed]`.
- Pull sampling uses its lawful inverse,
  `fixedToMoving: Rigid3[Fixed, Moving]`.

The public API must not expose ambiguous `forward`, `backward`, or `inverse`
aliases. A pose update is a target-frame, left group increment:

```text
T_next = Exp_Fixed(delta) compose T
```

where `T` is `movingToFixed` and `delta: Twist6[Fixed]`. In the existing Lie
API this operation is `T.retractTarget(delta)`. Translation components are
millimetres in the live fixed frame and angular components are radians. The
optimizer never updates or averages Euler columns. Euler ZYX values are
permitted only at an explicitly named compatibility or serialization boundary.

## Fixed-domain objective

The compiled pair defines a deterministic fixed-domain sample set `S`. Its
points, fixed values, mask membership, base weights, and normalization do not
change with a candidate pose. For `p` in `S`, let:

```text
x_p       = fixed.grid.indexToFrame(p)
y_p(T)    = fixedToMoving(T)(x_p)
r_p(T)    = moving.sample(y_p(T), boundary) - fixed.value(p)
W         = sum(baseWeight_p)
E(T)      = sum(baseWeight_p * rho(r_p(T) / scale)) / W
overlap(T)= sum(baseWeight_p * inBounds(y_p(T))) / W
```

The following rules are mandatory:

1. `W` is fixed for the solve. A candidate cannot improve `E` by reducing the
   number of valid or in-bounds samples.
2. Boundary handling is part of the compiled objective. Every selected fixed
   sample contributes exactly once. An out-of-bounds moving sample is evaluated
   through the declared boundary policy; it is never silently dropped.
3. `overlap` is a separate diagnostic and hard feasibility gate. A candidate
   below `minimumOverlap` is rejected even when its boundary-valued objective
   is numerically smaller.
4. The objective reports both the normalized robust value and the unscaled
   fixed-denominator residual sum. This makes changes to sample count, scale,
   and normalization visible.
5. The default estimation boundary is a validated constant value. Other
   boundary policies are typed strategies and appear in the fit receipt.
6. All fixed and moving intensities used by the built-in objective must be
   finite. Invalid masks, empty support, and zero fixed weight are typed
   preparation failures.

`RobustLoss` is a closed policy rather than a scalar option bag. The first
production policies are squared loss and Huber loss. A fixed Huber threshold is
expressed in residual-intensity units. An adaptive threshold uses a named
robust scale estimator, a finite positive floor, and a declared update
schedule. The scale is fixed while comparing candidate steps in one
Levenberg-Marquardt iteration. Re-estimation, if enabled, occurs only at an
outer iteration boundary and both the previous and next scale are recorded.

Template construction is outside the pairwise inner solve. Mean, trimmed mean,
and valid-frame refresh are separately named template policies. A refresh
creates a new compiled pair; it cannot mutate fixed state while a pair is
running.

## Production optimizer boundary

The production path is a six-parameter Gauss-Newton optimizer with
Levenberg-Marquardt damping over the target-frame SE(3) retraction. Coordinate
search remains only a named diagnostic baseline.

The public design is split into validated policies:

- `StencilPolicy` controls deterministic fixed-domain selection and carries
  the seed or declares a seed-free construction.
- `RobustLoss` owns residual weighting and scale semantics.
- `DampingPolicy` owns the initial damping, accepted-step decrease, rejected
  step increase, and finite bounds.
- `ConvergencePolicy` owns step, objective, gradient, iteration, and rejection
  limits.
- `CapturePolicy` owns coarse seeds and restart selection in physical units.
- `ExecutionPolicy` owns deterministic reduction order and worker count.
- `TemplatePolicy` owns reference and refresh behavior outside the inner solve.
- `TemporalPolicy` owns optional group-valued warm starts, priors, smoothing,
  and acquisition-time refinement.

Each smart constructor validates cross-field invariants. There is no public
map of strings, flag-dependent sentinel, or silently ignored field.

### Compiled state and workspace

`CompiledRigidPair[Moving, Fixed]` is immutable and thread-safe. It owns or
safely shares immutable:

- complete moving and fixed `Grid` affines;
- fixed-domain linear indices and physical coordinates in primitive storage;
- fixed values, fixed weights, and any fixed image gradients;
- interpolation coefficients or other reusable sampling state;
- stencil, robust-loss, boundary, and execution metadata.

Compilation must not materialize `Point` objects per voxel. A run receives a
distinct `RigidOptimizerWorkspace` containing mutable 6-vectors, packed 6-by-6
normal matrices, reduction lanes, candidate state, and any explicitly bounded
scratch buffers. Concurrent use of one workspace fails visibly; concurrent
runs with distinct workspaces over one compiled pair are supported.

The inner kernel accumulates the objective, gradient, approximate Hessian,
support, overlap, and finite-status data in one deterministic pass. Built-in
paths allocate no object per selected sample or candidate. The kernel is
tested separately from the optimizer state machine.

### Accepted steps and termination

A candidate is accepted only when it is finite, satisfies the overlap and
support gates, and meets the damping policy's actual-reduction rule. Every run
returns a trace or summary containing initial and final objective, fixed
normalizer, robust scales, overlap, gradient norm, step norm, damping,
iterations, attempts, accepted and rejected steps, and elapsed stage times.

Successful termination variants are:

- `ObjectiveConverged`
- `StepConverged`
- `GradientConverged`

Non-success variants are distinct and never reported as convergence:

- `IterationLimit`
- `RejectedStepLimit`
- `InsufficientSupport`
- `InsufficientOverlap`
- `IllConditionedNormalMatrix`
- `NonFiniteObjective`
- `NonFiniteDerivative`
- `InvalidControl`
- `WorkspaceInUse`

An iteration limit may still return the best inspected pose, but the result
retains its non-success termination. The caller chooses through a typed policy
whether a motion series may retain, replace, or reject such a frame.

## Volregger feature disposition

Volregger is the algorithmic and experimental progenitor, not a geometry or
API authority. The following table covers the controls and behaviors in
`R/control.R`, `R/profiles.R`, and the C++ estimator.

| Volregger behavior | reframe4s disposition | Reason |
|---|---|---|
| Coarse-to-fine pyramid and per-level budgets | canonical core | Use real `GridTower` levels with full affines. |
| Deterministic information-content stencil and spatial bins | canonical core | Compile a reusable physical-domain stencil; validate its efficacy independently. |
| Six-by-six Gauss-Newton/LM solve and damping | canonical core | This is the production local optimizer. |
| Target-frame SE(3) composition | canonical core | It is the only update convention. |
| Huber loss with a fixed scale | canonical core | Robust pairwise estimation needs a stable default. |
| Capture grid, ranked seeds, restarts, and warm-start traversal | canonical core | They provide a declared physical capture range. |
| Mean or trimmed-mean template and valid-only refresh | canonical core | Expose named immutable template policies. |
| Fixed mask, edge exclusion, overlap diagnostic, and overlap taper | canonical core | Selection is fixed-domain; tapering must not change the denominator. |
| Per-frame parallelism and kernel worker count | canonical core | Express through `ExecutionPolicy` with deterministic reductions. |
| Pose-derived displacement, relative rotation, and framewise displacement | canonical core | These are reusable transform diagnostics with explicit units. |
| Adaptive Huber scale and collapse guard | optional typed strategy | Admit only after scale and update semantics pass derivative and adversarial tests. |
| Template-mode whitening and ridge | optional typed strategy | Useful but not required for the first lawful optimizer. |
| Nuisance-basis marginalization, orthogonalization, and ridge | optional typed strategy | It must be a typed residual model with rank diagnostics. |
| IC or dense polish passes | optional typed strategy | They are explicit subsequent optimizer stages, never hidden flags. |
| Temporal normal prior | optional typed strategy | Define on relative `Rigid3` increments with physical rotation scaling. |
| Group-valued temporal regularization | optional typed strategy | Replace neighbour-averaged Euler columns with SE(3) smoothing and a fit guard. |
| Slice or packet spline refinement | optional typed strategy | Keep acquisition-aware estimation/application in core, using group interpolation. |
| Final linear, B-spline, Lanczos, quintic, or heptic resampling | delegated strategy | Motion selects a production resampling policy; it owns no interpolation kernel. |
| Named tuned profiles and ablation families | benchmark-only evidence | Promote a profile only after a frozen benchmark; profiles are not semantic types. |
| Native tSNR, DVARS, robust DVARS, censor suggestions, reports, BIDS, and CLI | downstream QC or policy | ScalaFIM owns image-series interpretation and workflows. |
| Spin-history repair and intensity gain smoothing | downstream QC or policy | These are acquisition and analysis policies, not rigid estimation. |
| Additive or optionally right-sided Euler updates | rejected legacy behavior | The canonical update is target-frame SE(3). |
| Spacing-only coordinates and centre-synthesized geometry | rejected legacy behavior | Complete `Grid` affines are mandatory. |
| Private trilinear, cubic, or high-order samplers | rejected legacy behavior | Production resampling is the sole interpolation authority. |
| Dropping invalid boundary samples or candidate-dependent normalization | rejected legacy behavior | It creates an overlap-seeking objective. |
| Euler-column smoothing or temporal averaging | rejected legacy behavior | It is discontinuous and not group-equivariant. |
| Low-motion pose shrinkage toward identity | rejected legacy behavior | It biases the physical estimate using a post-hoc regime rule. |
| Low-motion output temporal or spatial smoothing | rejected legacy behavior | It can improve tSNR while reducing temporal or spatial fidelity. |
| Integer status flags, silent fallback, and exhausted rejection as convergence | rejected legacy behavior | Failures and termination are typed and visible. |

## Independent evidence taxonomy

Evidence is labelled in the test name and machine receipt. One result may carry
more than one label, but each label has a distinct authority:

| Class | Accepted evidence | What it may establish |
|---|---|---|
| algebraic | independently generated `Rigid3` values and group laws | endpoint, inverse, composition, adjoint, exponential, logarithm, and retraction laws |
| analytic-oracle | closed-form fields or the independent continuous renderer below | physical application, derivative, and known-transform accuracy |
| differential | a pinned external implementation on a non-defective shared case | compatibility or a comparative measurement, never truth by itself |
| metamorphic | physical reparameterization, global frame change, intensity affine change, or deterministic replay | invariance and equivariance properties |
| adversarial | absent or changing overlap, singular texture, non-finite input, extreme coordinates, and workspace races | visible failure and safety properties |
| regression | a minimal fixture for a previously localized defect with a declared independent expectation | non-recurrence of that defect only |
| performance | a public workload with checksum, allocation, memory, timing, environment, and failures | resource behavior for that exact workload |

Self-warping a discrete volume with the production sampler and estimating it
back is a pipeline round-trip, not an independent truth oracle. The same rule
applies to a fixture rendered by the candidate interpolation code. Negated
Euler columns and parameter-vector differences are compatibility diagnostics;
they are not physical pose error.

## Continuous full-affine phantom

The independent renderer is test and benchmark tooling and does not depend on
`reframe4s-resample` or the estimator kernel.

For each subject seed it constructs a continuous scalar field from:

- nested, off-centre ellipsoids with distinct axes and smooth boundaries;
- unequal bilateral cavities and at least one unilateral feature;
- a seeded sum of non-axis-aligned sinusoidal texture components;
- optional multiplicative bias, temporal gain, drift, and additive noise
  fields generated from separate recorded seeds.

The renderer accepts an arbitrary invertible 4-by-4 index-to-frame affine,
including translation, anisotropic scale, axis permutation, reflection, and
oblique direction. It evaluates a declared exact
`Rigid3[Moving, Fixed]` in homogeneous coordinates. A voxel value is the mean
of continuous evaluations on a fixed tensor sub-grid in the voxel cell. Claim
fixtures use at least `3 x 3 x 3` samples per voxel and verify convergence
against `5 x 5 x 5` on a registered subset. Neither the field evaluator nor
the voxel integrator calls production image interpolation.

The required pairwise suite crosses:

- identical and distinct moving/fixed shapes;
- isotropic and anisotropic resolution;
- translated, permuted, reflected, and oblique grids;
- low, moderate, and capture-boundary rigid motions;
- clean, intensity-biased, noisy, dropout, and partial-overlap conditions;
- at least twenty independently seeded synthetic subjects for comparative
  claims.

Truth is scored through relative `Rigid3` composition, physical landmark
displacement in millimetres, and corrected-image error against a separately
rendered motion-free target. Voxels and frames are never treated as
independent subjects.

## Objective-at-truth diagnostic

Every failed known-transform recovery records, under the same compiled
objective:

```text
E_identity
E_truth
E_recovered
overlap_identity
overlap_truth
overlap_recovered
gradient_norm_truth
pose_error_truth_to_recovered
```

The diagnosis is mechanical:

1. If truth is infeasible, non-finite, or worse than identity beyond numerical
   tolerance, first investigate polarity, geometry, renderer, boundary, or
   objective defects. The optimizer is not exonerated or blamed.
2. If `E_truth + objectiveTolerance < E_recovered`, the optimizer failed to
   reach a demonstrably better known candidate.
3. If `E_recovered <= E_truth + objectiveTolerance` while physical pose error
   exceeds its gate, the discrete objective is biased, flat, or
   non-identifying for that fixture. Tightening iterations cannot be presented
   as the remedy without new evidence.
4. If pose and objective gates pass but the gradient at truth fails a
   finite-difference check, the analytic derivative is defective.
5. A tolerance may change only through a versioned contract revision that
   explains the renderer, interpolation, and physical consequences. It may
   not be loosened solely because the current estimator misses it.

This diagnostic is mandatory for the current six-degree-of-freedom regression:
the reported `0.060269686980678604` degree error cannot be adjudicated against
the `0.05` degree gate until the truth and recovered objectives are recorded.

## Acceptance matrix

| Mote criterion | Contract evidence |
|---|---|
| `AC-421-01` | Roles, polarity, retraction, fixed objective, scale, overlap, and termination sections |
| `AC-421-02` | Complete Volregger feature disposition table |
| `AC-421-03` | Continuous full-affine phantom definition |
| `AC-421-04` | Independent evidence taxonomy and prohibited pseudo-oracles |
| `AC-421-05` | Objective-at-truth diagnostic and decision rules |
| `AC-421-06` | Explicit mapping to PRD decisions and the canonical motion evidence plan |
