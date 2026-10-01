# PRD: `flashalign`

> Proposal record, supplied in parts. Parts 1 and 2 recorded 2026-09-12. Equations,
> pseudocode, targets, and recommendations are preserved as proposed. Part 2
> supplies explicit correctness repairs that supersede the affected Part 1
> recommendations; both records are retained. Part 1 ends mid-equation in §17;
> Part 2 restates and completes that section. No independent technical validation
> has been performed during recording.

The PRD should be opinionated: **rigid EPI↔anatomical and rigid/affine
within-modality first**, CPU-first, deterministic, with the projected-patch
formulation as the fundamental primitive. The design goal is not merely “fewer
evaluations than 3dAllineate”; it is that each evaluation becomes an extremely
small streaming computation whose sufficient statistics are tiny matrices.

## 1. Product objective

`flashalign` estimates a high-quality rigid or affine transformation between two
3-D MRI volumes.

Primary targets:

- EPI → T1w
- EPI → T2w
- T1w → T1w / T2w → T2w
- repeated EPI/reference → EPI/reference
- partial-volume/slab acquisition → whole-brain anatomy

Non-goals for v1:

- nonlinear anatomical normalization
- susceptibility correction
- slice-to-volume registration
- motion correction of complete time series
- arbitrary multimodal medical-image registration
- hundreds of similarity metrics/options

The desired invocation should essentially be:

```text
flashalign \
    --moving epi.nii.gz \
    --fixed T1w.nii.gz \
    --mode epi-t1 \
    --out-matrix epi_to_t1.txt
```

with sensible automatic behavior.

### Performance targets

For ordinary reasonably initialized same-subject data:

```text
rigid EPI → T1:       < 1 s desirable
rigid within-modal:   << 1 s desirable
affine refinement:    < 2 s desirable
```

on a contemporary desktop CPU, excluding NIfTI decompression if appropriate.

More important:

```text
catastrophic failure rate < 3dAllineate
95th-percentile error     < 3dAllineate
median error              <= 3dAllineate
```

Speed is secondary to the failure tail.

## 2. Fundamental architecture

There should be only five conceptual components:

```text
Volume
  ↓
PreparedVolume
  ↓
Capture
  ↓
ProjectedPatchOptimizer
  ↓
Transform + QC
```

Do not build a general registration framework and then implement our method
inside it. Build precisely the machinery this algorithm needs.

Core data structures:

```text
Volume {
    data: float32[nx, ny, nz]
    voxel_to_world: Mat4
    world_to_voxel: Mat4
    spacing_mm: Vec3
}

PreparedLevel {
    image: float32[]
    gradient: optional / interpolator-dependent
    support: uint8[] or float32[]
    candidate_patches: Patch[]
    fixed_geometry
}

Transform {
    matrix: Mat4
}

Patch {
    center_world: Vec3
    offsets_world[m]: Vec3
    moving_normalized[m]: float32
    quality: float32
}

FitState {
    T: Transform
    loss: float64
    H: Matrix<12,12>
    g: Vector<12>
    diagnostics
}
```

Internally, geometry is **always world-space millimetres**.
That rule should be almost religious.

## 3. Coordinate convention

This is the first correctness requirement because MRI registration bugs
disproportionately come from coordinates rather than optimization.

Define:

\[
y=T x,
\]

where:

- \(x\): moving-image world coordinate
- \(y\): fixed-image world coordinate.

Thus for EPI→T1:

\[
x_{\rm T1}=T_{\rm EPI\to T1}x_{\rm EPI}.
\]

Sampling requires:

\[
i_F = V_F^{-1} T V_M i_M.
\]

Never optimize voxel-space transforms.

### Coordinate unit tests

Required before any optimizer work:

```text
identity volumes → identity
known +10 mm translation → recovered +10 mm
anisotropic voxels → same answer
axis permutations → same physical answer
LPS/RAS permutations → same physical answer
different image origins → correct answer
transform inversion → maps landmarks correctly
```

Generate synthetic volumes with deliberately horrible header arrangements.

## 4. Preprocessing

Keep preprocessing minimal. For each image:

```pseudo
function prepare_volume(V):

    validate_affine(V.voxel_to_world)

    robust_range = quantiles(V, .01, .99)

    support =
        finite(V) AND
        intensity_is_plausible(V, robust_range)

    pyramid = []

    for resolution in choose_levels(V):
        L = physical_gaussian_downsample(V, resolution)
        pyramid.append(L)

    return PreparedVolume(pyramid)
```

Suggested physical scales:

```text
coarse:  ~6 mm effective resolution
medium:  ~3 mm
fine:    native EPI resolution or ~1.5–2 mm anatomy
```

For EPI→T1, don't drag the T1 through a 1-mm optimization simply because it
exists at 1 mm. The EPI contains no corresponding information.

### Resolution matching

Before comparison:

\[
\sigma^2_{\rm blur,F}
\approx
\sigma^2_{\rm target}-\sigma^2_F.
\]

Approximate EPI PSF from voxel dimensions initially.

This matters both statistically and computationally: **removing spatial
frequencies that cannot constrain the alignment makes optimization cheaper
and smoother.**

## 5. Patch construction

Do not use conventional overlapping cubic patches with every voxel.
Use a fixed stencil. For example:

```text
3 × 3 × 3 = 27 samples
```

but physical offsets:

```text
{-d, 0, +d} × {-d, 0, +d} × {-d, 0, +d}
```

with \(d\) appropriate to pyramid level. This gives 27 samples while
interrogating a substantially larger physical neighborhood.

For each moving patch:

\[
e_p=(E(x_1),\ldots,E(x_m)).
\]

Precompute:

\[
\bar e_p,\qquad
\tilde e_p=e_p-\bar e_p,
\qquad
u_p=\frac{\tilde e_p}{\|\tilde e_p\|}.
\]

Reject patches where:

\[
\|\tilde e_p\| < \epsilon.
\]

Thus **moving-patch normalization never happens during optimization.**
Store `u_p` as float32.

## 6. Patch screening

We don't initially know which patches are geometrically informative because
that depends on the current transform. So use two-stage selection.

### Cheap static screening

Calculate moving-side features:

```pseudo
for candidate center x:

    patch = sample_moving_stencil(x)

    variance = var(patch)
    structure = gradient_structure_tensor(patch)

    if variance < threshold:
        reject

    if mostly_background:
        reject

    retain
```

Spatially stratify candidates:

```text
brain divided into ~10–15 mm cells
retain K candidates/cell
```

This prevents 90% of the sample from coming from occipital cortex.
Aim for perhaps:

```text
10,000–30,000 cheap candidate patches
```

but optimize over only a few thousand.

## 7. The inner evaluation kernel

This is where implementation quality matters most.
For patch \(p\), fixed samples are:

\[
f_j=F(Tx_j).
\]

Calculate:

\[
f_c=f-\bar f,
\qquad
L=\sqrt{f_c^Tf_c+\epsilon},
\qquad
v=f_c/L.
\]

Correlation:

\[
c=u^Tv.
\]

For the expected polarity \(s\):

\[
\rho=sc.
\]

Initially the simple robust loss can be:

\[
\ell=\rho_{\rm robust}(1-\rho).
\]

But retain the polarity-mixture formulation as the EPI default.

## 8. Analytical derivatives

This is mandatory. No numerical transform derivatives.
At transformed sample \(y_j\):

\[
g_j=\nabla F(y_j).
\]

For rigid registration:

\[
B_j=
\begin{bmatrix}
I&-[y_j-c]_\times
\end{bmatrix},
\]

therefore:

\[
G_j=g_j^TB_j.
\]

For affine add six strain columns. Then:

\[
G_c=G-\mathbf1\bar G
\]

and:

\[
a=v^TG_c.
\]

The normalized-patch Jacobian is:

\[
\boxed{J=\frac{G_c-va}{L}.}
\]

### Important implementation trick

Never construct:

\[
C=I-\frac{11^T}{m}
\]

or:

\[
P=C-vv^T.
\]

Compute:

```pseudo
mean_f = sum(f) / m
fc[j] = f[j] - mean_f

mean_G[k] = sum_j G[j,k] / m
Gc[j,k] = G[j,k] - mean_G[k]

a[k] = sum_j v[j] * Gc[j,k]

J[j,k] = (Gc[j,k] - v[j]*a[k]) / L
```

For 27 samples × 12 parameters this is tiny.

## 9. Do not even materialize J

We can go further. We need \(J^TJ\) and \(J^Tu\). But:

\[
J^TJ
=
\frac{1}{L^2}
\left[G_c^TG_c-aa^T\right].
\]

And because \(u\) is centred:

\[
J^Tu
=
\frac{1}{L}
\left[G_c^Tu-a(v^Tu)\right].
\]

Since \(v^Tu=c\):

\[
\boxed{J^Tu=\frac{G_c^Tu-ca}{L}.}
\]

Therefore the hot loop does **not need to create a 27×12 Jacobian at all**.
For every patch accumulate only:

```pseudo
Sgg = Gc' * Gc       # 12 × 12
a   = Gc' * v        # 12
q   = Gc' * u        # 12

JTJ = (Sgg - a*a') / L²
JTu = (q - c*a) / L
```

This is one of the central speed tricks.

## 10. Even centering G can disappear

There is another simplification. Let:

\[
s_G=\sum_jG_j.
\]

Then:

\[
G_c^TG_c=G^TG-\frac1m s_Gs_G^T.
\]

Furthermore, because \(u\) and \(v\) are already centred:

\[
G_c^Tu=G^Tu,
\qquad
G_c^Tv=G^Tv.
\]

Thus the complete patch sufficient statistics are:

\[
\boxed{G^TG,\quad\sum G,\quad G^Tu,\quad G^Tv,\quad c,\quad L.}
\]

So the hot loop can be:

```pseudo
Sgg = 0
sg  = 0
Gu  = 0
Gv  = 0

for j in 1..27:
    f, grad = interpolate_value_and_gradient(F, y[j])

    compute G_j

    Sgg += outer(G_j, G_j)
    sg  += G_j
    Gu  += u[j] * G_j

# after fixed patch normalization is known:
for j:
    Gv += v[j] * G_j

Scc = Sgg - outer(sg,sg)/m

JTJ = (Scc - outer(Gv,Gv)) / L²
JTu = (Gu - c*Gv) / L
```

No projection matrices. No patch Jacobian. No QR. No SVD.
Just reductions and rank-one updates.
This is exactly the sort of analytical compression wanted throughout the implementation.

## 11. Fused interpolation

Interpolation is likely to dominate runtime. We require simultaneously:

\[
F(y),\qquad \nabla F(y).
\]

Implement:

```pseudo
(value, gx, gy, gz) = interp_value_gradient(volume, x, y, z)
```

in one traversal of neighboring voxels. Do **not** separately interpolate:

```text
F
∂F/∂x
∂F/∂y
∂F/∂z
```

unless benchmarking shows that precomputed gradients are actually faster.

For trilinear interpolation, value and exact derivative of the trilinear
interpolant come essentially for free from the same eight voxel values.
This makes trilinear interpolation surprisingly attractive during optimization.

Use higher-quality interpolation **once**, when producing the final resampled
output. That distinction is important:

> Registration interpolation should optimize speed and derivative consistency.
> Output interpolation should optimize image fidelity.

## 12. Another major trick: reuse geometry inside patches

A patch's points satisfy:

\[
x_j=x_c+\delta_j.
\]

Under affine transformation:

\[
Tx_j=Tx_c+A\delta_j.
\]

Therefore don't apply a full affine matrix to all 27 points. Calculate:

```pseudo
yc = T * xc

for j:
    y[j] = yc + A * offset[j]
```

And because every patch uses the same stencil, calculate:

```pseudo
warped_offsets[j] = A * offset[j]
```

**once per optimizer iteration**, not once per patch. Then:

```pseudo
for patch:
    yc = T * patch.center

    for j:
        y = yc + warped_offsets[j]
```

For 4,000 patches × 27 points this removes a substantial amount of repeated
transform arithmetic.

## 13. Exploit rigid Jacobian structure

For rigid transformation:

\[
G_j=
\begin{bmatrix}
g_x & g_y & g_z & g^T(-[y-c]_\times)
\end{bmatrix}.
\]

But \(g^T(-[r]_\times)\) is simply a cross product. Thus:

```pseudo
translation_part = grad
rotation_part    = r × grad
```

up to the chosen sign convention. So:

```pseudo
G = [gx, gy, gz,
     ry*gz-rz*gy,
     rz*gx-rx*gz,
     rx*gy-ry*gx]
```

No matrices. Likewise affine strain columns can be written directly as
products such as:

```text
gx*rx
gy*ry
gz*rz
gx*ry + gy*rx
...
```

Again: no matrix multiplication in the hot loop.

## 14. Information selection

At the beginning of each level, evaluate perhaps 10–30k candidate patches
once. Each produces:

\[
H_p=J_p^TJ_p.
\]

Form approximate global information:

\[
H_0=\sum_p H_p.
\]

Whiten parameter space:

\[
\bar H_p=D^{-1/2}H_pD^{-1/2}.
\]

Then greedily choose patches that increase:

\[
\log\det(H+\lambda I).
\]

Conceptually:

```pseudo
H = λI
selected = []

repeat until budget:
    for candidate p:
        gain[p] =
          logdet(H + Hp) - logdet(H)

    choose spatially-valid p with largest gain
    H += Hp
```

But doing this literally is unnecessary. By the matrix determinant lemma:

\[
\Delta_p
=\log\det(I+H^{-1/2}H_pH^{-1/2}).
\]

Since \(H_p\) is tiny and low-rank, gains are cheap.
Still, a stochastic greedy implementation is probably preferable:

```pseudo
while selected < K:
    examine random subset of remaining candidates
    choose best information gain
```

with spatial quotas. This should provide most of D-optimal selection at tiny cost.

## 15. Better still: select information directions rather than patches

An interesting extension is to maintain \(H^{-1}\) during selection.
For each candidate, approximate gain with:

\[
\ell_p=\operatorname{tr}(H^{-1}H_p).
\]

Then select the largest \(\ell_p\), subject to spatial diversity.
After adding \(H_p\), update the inverse via Woodbury rather than invert from
scratch.

For a 6×6 or 12×12 matrix this is not about computational necessity—the inverse
is trivial. It makes the algorithm conceptually simple.

The selected patches automatically seek weak transform directions.
If pitch is poorly constrained, patches that constrain pitch become
disproportionately valuable.

## 16. Optimization loop

Use damped Gauss–Newton / Levenberg–Marquardt with displacement-scaled trust regions.

```pseudo
function refine(T, level):

    patches = select_information_patches(T, level)

    μ = initial_damping

    repeat:

        H = 0
        b = 0
        loss = 0

        warped_offsets = transform_offsets(T.linear)

        parallel for patch in patches:

            stats = evaluate_patch(T, patch, warped_offsets)

            weight, signed_weight =
                robust_weights(stats.correlation)

            local_H =
                weight * stats.JTJ

            local_b =
                signed_weight * stats.JTu

            thread_H += local_H
            thread_b += local_b
            thread_loss += loss(stats)

        reduce thread accumulators

        H += affine_prior_curvature(T)

        Δ = solve(H + μD, b)

        if displacement_norm(Δ,D) > trust_radius:
            rescale Δ

        T_candidate = exp(Δ) ∘ T

        candidate_loss =
            evaluate_loss_only(T_candidate, patches)

        predicted_reduction =
            quadratic_prediction(H,b,Δ)

        actual_reduction =
            loss - candidate_loss

        ratio =
            actual_reduction / predicted_reduction

        if ratio good:
            T = T_candidate
            decrease μ
        else:
            increase μ

        if converged:
            break

    return T
```

Notice that rejected steps require only **loss evaluation**, not gradients.
That is considerably cheaper.

## 17. Loss-only evaluation gets its own optimized kernel

Do not reuse the full Jacobian routine.

```pseudo
function patch_loss_only(T, patch):

    sample 27 fixed intensities

    mean = sum(f)/27

    norm² = sum(f²) - 27*mean²

    dot = Σ u[j] * f[j]
          # u sums to zero, so centering f is unnecessary!

    c = dot / sqrt(norm² + eps)

    return robust_loss(c)
```

That identity is useful: because

\[
\sum_j u_j=0,
\]

> The supplied Part 1 ends with the following unfinished equation (verbatim).
> Part 2 below restates and completes this section; this fragment is retained
> as originally supplied.

```text
\[
u^T(f-\bar f1)=u^
```

---

# Part 2 — Completion, correctness repairs, and higher-parameter extensions

> Recorded 2026-09-12. Part 2 explicitly revises the earlier normalization,
> sampling, and optimizer proposals. Part 1 is retained above as the original
> record; the corrections below supersede the affected recommendations.
> Associated supplied artifacts (copied byte-for-byte, not rerun during recording):
> [check script](flashalign/flashalign_extension_checks.py) and
> [check results](flashalign/flashalign_extension_check_results.json).
> Original paths: `/Users/bbuchsbaum/Downloads/flashalign_extension_checks.py`
> and `/Users/bbuchsbaum/Downloads/flashalign_extension_check_results.json`.

**I would extend Flashalign by restricting what the extra parameters are allowed
to explain—not simply by increasing the number of deformation coefficients.**

The most useful first extension is **rigid pose plus a smooth,
phase-encoding–restricted EPI displacement field**. For anatomical registration,
I would instead use **a small set of smooth, strain-penalized deformation modes**.
Both can retain the same projected-patch objective and optimizer.

Part I completes the PRD from Section 17, including correctness repairs. Part II
specifies higher-parameter extensions. Part III gives the analytical machinery
that should keep them fast.

The parameter counts and computational budgets below are proposed engineering
settings, not measured performance results.

# Part I — Complete the Flashalign PRD

## 17. Loss-only evaluation gets its own optimized kernel

Do not reuse the full Jacobian routine. At a trial transform, the loss-only
kernel needs sampled intensities, patch normalization, and the robust correlation
loss. It does not need image gradients, transform derivatives, or curvature matrices.

For a precomputed, centred, unit-length moving patch \(u\),

\[
\mathbf 1^Tu=0,\qquad u^Tu=1,
\]

and sampled anatomical patch \(f\),

\[
c=\frac{u^Tf}{\|Cf\|},
\qquad C=I-\frac{\mathbf 1\mathbf 1^T}{m}.
\]

The numerator needs no explicit centering because \(u^T\mathbf 1=0\).

### A correctness repair to the earlier PRD

**Use an exact norm above a contrast threshold:**

\[
L=\|Cf\|.
\]

Do not replace it with \(\sqrt{\|Cf\|^2+\epsilon}\) while retaining the earlier
projector identities. With an exact norm,

\[
P=C-vv^T,\qquad v=Cf/L
\]

satisfies \(P^2=P\). With a softened norm, it does not. The simplified curvature
formula would therefore be wrong. For v1, hard-gate degenerate patches and use
the exact formula elsewhere.

```pseudo
function patch_loss_only(transform, patch, fixed_image, loss_config):

    f = sample_values(
        fixed_image,
        transform.map(patch.sample_points)
    )

    if any sample is outside valid interpolation support:
        return loss_config.outlier_cost

    # Work in float64 on this small stack-allocated buffer.
    # Subtracting one value first reduces large-offset cancellation.
    h = f - f[0]
    hc = h - mean(h)
    L2 = dot(hc, hc)

    if L2 <= loss_config.minimum_contrast_energy:
        return loss_config.outlier_cost

    c = dot(patch.u, hc) / sqrt(L2)

    if abs(c) > 1 + rounding_tolerance:
        return NUMERICAL_ERROR

    c = clamp_roundoff_only(c, -1, +1)

    return robust_correlation_loss(c, loss_config)
```

Twenty-seven-element buffers are inexpensive. Prefer a stable two-pass
calculation over a fragile “one-pass” variance formula unless the latter is
demonstrably safe.

### The robust loss must be identical in every kernel

Use the same implementation for optimization, trial evaluation, candidate
comparison, and validation:

\[
\ell(c)=
-\tau^2\log\left[
\epsilon+(1-\epsilon)
\left(\pi e^{-(1-c)/\tau^2}+(1-\pi)e^{-(1+c)/\tau^2}\right)
\right].
\]

Evaluate the bracket with `logsumexp`. For \(0<\epsilon<1\),

\[
0\leq \ell(c)\leq -\tau^2\log\epsilon.
\]

Define

\[
\ell_{\mathrm{out}}=-\tau^2\log\epsilon.
\]

An invalid transformed patch receives this cost. **It remains in the objective;
it is not silently discarded.**

## 18. Reject bad trial steps before evaluating every patch

The bounded, nonnegative loss gives an exact acceleration. Write the objective as

\[
E(T)=\sum_p a_p\ell_p(T)+R(T),
\]

where all \(a_p\geq0\) are fixed during a trial and include any sampling corrections.
While accumulating a candidate’s loss,

\[
E_{\mathrm{partial}}
=R(T_{\mathrm{candidate}})
+\sum_{p\in\mathrm{evaluated}}a_p\ell_p
\]

is a lower bound on its complete objective. If that lower bound already exceeds
the acceptance threshold, reject immediately.

```pseudo
function trial_objective(candidate, patches, acceptance_limit):

    if not geometry_constraints_satisfied(candidate):
        return REJECT

    total = prior_value(candidate)

    for patch in predetermined_evaluation_order:

        total += patch.weight * patch_loss_only(candidate, patch)

        if total > acceptance_limit:
            return REJECT_EARLY

    return total
```

The patch order can prioritize previously difficult or high-weight patches. The
order does not change the objective. **This is safe early rejection, not
approximate early acceptance.**

## 19. Finish the sufficient-statistics compression

The previous PRD still required a second pass to compute \(G^Tv\). That pass can
also disappear. Let \(G_j=\partial f_j/\partial\theta\). Accumulate

\[
s_G=\sum_jG_j,\qquad S_{GG}=\sum_jG_jG_j^T,
\]

\[
s_{hG}=\sum_jh_jG_j,\qquad s_{uG}=\sum_ju_jG_j,
\]

where \(h_j=f_j-f_0\). Also accumulate the scalar moments needed for \(L\) and \(c\).
Then

\[
a=G^Tv=\frac{s_{hG}-\bar h\,s_G}{L}.
\]

Consequently,

\[
\boxed{J^TJ=\frac{S_{GG}-s_Gs_G^T/m-aa^T}{L^2}}
\]

and

\[
\boxed{J^Tu=\frac{s_{uG}-ca}{L}.}
\]

Thus the affine kernel does not need to store \(G\), \(G_c\), \(v\), or \(J\) as matrices.

```pseudo
function linear_patch_statistics(transform, patch):

    initialize scalar moments
    initialize sg, shg, sug
    initialize symmetric Sgg

    for sample j:

        value, gradient = sample_value_and_exact_gradient(...)

        if invalid:
            return INVALID_PATCH

        if j == 0:
            origin = value

        h = value - origin
        Gj = transform_intensity_jacobian(gradient, sample_position)

        update_stable_intensity_moments(h)
        sg  += Gj
        shg += h * Gj
        sug += patch.u[j] * Gj
        Sgg += symmetric_outer_product(Gj)

    L, c = finish_patch_normalization()

    a = (shg - mean_h * sg) / L

    JTJ = (Sgg - outer(sg, sg)/m - outer(a, a)) / L²
    JTu = (sug - c*a) / L

    return correlation=c, curvature=JTJ, direction=JTu
```

Use double-precision accumulators. If cancellation makes the computed curvature
materially indefinite, fall back to the explicitly centred reference kernel and
flag the discrepancy. Do not conceal a substantial numerical error by clipping
eigenvalues.

### Another exact acceleration: deduplicate sample locations

Different patches may contain identical moving-space sample points. Those points
remain identical after **any deterministic transform**, including a nonlinear
transform. Construct once per selected patch set:

```text
unique_sample_points
patch_to_unique_sample_indices
```

Interpolate each unique point only once per evaluation, then gather its value
into the relevant patches. This saves work without changing the metric. For
nonlinear registration, transform evaluation and trajectory integration can also
be reused.

## 20. Define a fixed objective before optimizing it

The sampling scheme must not accidentally define a different objective every
iteration. The earlier suggestion of repeatedly choosing the “best” patches and
optimizing them without correction would do exactly that.

### Required policy

Maintain three distinct sets:

| Set | Purpose |
|---|---|
| Optimization samples | Compute steps and trial acceptance |
| Selection samples | Compare candidates, pyramid checkpoints, and model complexity |
| Audit samples | Final diagnostics; not repeatedly used to tune the fit |

For a candidate population with fixed quality weights \(q_p\), sampling patch
\(p\) with probability \(p_p\) gives the estimator

\[
\widehat E_{\mathrm{data}}
=\frac{1}{MQ}\sum_{p\in\mathrm{sample}}\frac{q_p}{p_p}\ell_p,
\qquad Q=\sum_{\mathrm{population}}q_p.
\]

With probabilities fixed for that draw, this is unbiased for the specified
population objective.

A practical initial sampler is a mixture of spatially balanced sampling and
information-weighted sampling. Retaining a uniform component protects against a
bad initial transform suppressing useful anatomy.

**Freeze sample IDs, weights, polarity priors, contrast gates, and robust-loss
parameters throughout a trust-region trial.** Refresh sampling between
accepted-step blocks or pyramid levels, then recompute the objective and local model.

### Do not overengineer selection

A 27-sample normalized patch has

\[
\operatorname{rank}(J)\leq m-2=25.
\]

For a 12-parameter affine model, its curvature can therefore be full rank. The
earlier description of every patch contribution as conveniently “low rank” does
not justify elaborate Woodbury updates for affine selection.

Start with cheap stratified sampling. Add information-based selection only when
its screening cost is repaid by fewer subsequent evaluations.

## 21. Complete the optimizer, including priors and rejection handling

The sign convention should be unambiguous:

\[
b=-\nabla E.
\]

The approximate curvature \(H\) includes the data term and prior curvature.
Algorithmic damping is separate. The earlier optimizer omitted the prior
gradient; adding only prior curvature would not optimize the intended objective.

```pseudo
function refine(state, level, model, config):

    samples = choose_samples(state, level)
    current = evaluate_objective_and_linearize(state, samples)

    damping = config.initial_damping
    best_checkpoint = state

    for iteration in 1 .. config.max_linearizations:

        H = current.data_curvature + current.prior_curvature
        b = current.data_rhs - current.prior_gradient
        D = displacement_metric(state, fixed_geometry_points)

        accepted = false

        for attempt in 1 .. config.max_trial_attempts:

            delta = solve(H + damping*D, b)

            delta = enforce_displacement_trust_region(delta, D)
            candidate = model.propose(state, delta)

            predicted = dot(b, delta) - 0.5*dot(delta, H*delta)

            if predicted <= 0:
                increase damping
                continue

            limit = current.objective - config.acceptance_ratio*predicted

            trial = trial_objective(candidate, samples, limit)

            if trial is valid and trial <= limit:

                state = candidate
                accepted = true
                update damping from actual/predicted reduction
                break

            increase damping

        if not accepted:
            return state with STALLED diagnostics

        if checkpoint_due:
            assess state on fixed selection samples
            update best_checkpoint when justified

        if convergence_tests_pass:
            return validated best state

        if resampling_due:
            samples = choose_samples(state, level)

        current = evaluate_objective_and_linearize(state, samples)

    return validated best state with ITERATION_LIMIT diagnostics
```

Important details:

- **Rejected trials reuse the same linearization.** Changing damping does not
  require another image-gradient evaluation.
- **Predicted reduction excludes damping.** Damping controls the step; it is not
  an additional penalty in the objective unless explicitly defined as such.
- **Step clipping changes the prediction.** Recalculate predicted reduction using
  the actual proposed step.
- **Convergence is measured in physical displacement.** Require small spatial
  steps, small improvement, and adequate information. A tiny step caused by
  severe damping is not evidence of successful registration.

## 22. Complete capture, checkpoints, and fallback behavior

Begin with the supplied world-space transform, or identity in world coordinates
when the headers provide a coherent common frame. Do not confuse that with the
voxel sampling matrix

\[
V_F^{-1}TV_M.
\]

If coarse validation is weak, invoke the structural capture stage: test a limited
rotation set and use FFT correlation for translations. This is an established
strategy rather than a claimed invention; Cross-Sim-NGF provides a relevant
multimodal precedent.

```pseudo
function capture(moving, fixed, initial_transform):

    candidates = [initial_transform]

    if coarse_support_and_similarity_are_adequate(initial_transform):
        return candidates

    for rotation in progressively_expanded_rotation_schedule:

        translation_peaks = structural_fft_translation_search(rotation)

        add distinct, sufficient-overlap peaks to candidates

        if enough promising candidates:
            break

    return retain_best_distinct_candidates(candidates)
```

Require linear, zero-padded correlations rather than accidental circular
wraparound. Rotate orientation-tensor components as well as their spatial
locations. All surviving candidates must eventually be compared under the same
fine-level objective and sampling configuration.

Failure states should distinguish insufficient overlap, insufficient information,
competing alignments, numerical failure, and exhausted search. **Never return
identity with a success status merely because optimization failed.**

## 23. Output, quality control, and implementation acceptance

### Output contract

Return the transform with explicit source space, destination space, direction,
units, model parameters, and versioned conventions.

For resampling the moving image onto the fixed grid,

\[
I_{\mathrm{out}}(y)=I_M(T^{-1}y).
\]

Use the original moving image and interpolate once after composing the necessary
transforms. A nonlinear inverse is not generally obtained by negating a
displacement field.

Include diagnostics for overlap, spatial inlier coverage, candidate disagreement,
regional refit stability, and data-only conditioning. Damping and priors must not
be allowed to manufacture apparent data information.

Report separate timing for reading/decompression, preparation, capture,
optimization, validation, and output resampling.

### Required test gates

| Gate | Required tests |
|---|---|
| Geometry | Oblique and anisotropic headers, axis permutations, different origins, transform composition and direction |
| Interpolation | Values and derivatives from the same interpolant; finite-difference checks away from interpolation knots |
| Patch algebra | Explicit Jacobian versus compressed statistics; gain/offset invariance; degenerate and invalid patches |
| Optimization | Prior-gradient signs, acceptance prediction, rejected-step reuse, fixed-sample reproducibility |
| Robustness | Partial coverage, dropout, low contrast, large initialization error, competing anatomical matches |
| Performance | Unique interpolation count, gradient evaluations, rejected trials, preprocessing and cold-cache cost |

The scalar reference implementation remains the correctness oracle. SIMD and
parallel kernels must agree within specified tolerances before being enabled by
default. For reproducible reductions, use fixed work blocks and a fixed reduction
tree. Do not promise bitwise reproducibility across arbitrary compilers and
processor architectures.

# Part II — Higher-parameter models that remain conservative

## 24. Extension ladder

I would expose a small number of explicit geometric models:

| Model | Additional parameters, initially | Intended use |
|---|---:|---|
| **PE field** | Approximately 32–256 scalar coefficients | Residual EPI distortion |
| **Small-strain anatomical field** | Approximately 48–192 vector-mode coefficients | Modest anatomical shape differences |
| **Adaptive local refinement** | A few hundred to roughly 1,500 coefficients | Local mismatch supported by sufficient evidence |
| **Low-dimensional velocity flow** | Approximately 96–384 coefficients | Larger smooth anatomical deformations |

These are separate choices, not stages that every registration must traverse.

For EPI–anatomical registration, susceptibility distortion has a specific
acquisition-direction structure. For anatomical shape registration, that
constraint is inappropriate. TOPUP’s formulation explicitly exploits
phase-encoding direction and polarity rather than allowing arbitrary
three-dimensional displacement.

## 25. First priority: rigid pose plus a smooth scalar PE field

Let \(x\) denote observed EPI-world coordinates and \(e\) the unit phase-encoding
direction in that coordinate system. Use

\[
\boxed{T(x)=A\left[x+e\,d(x)\right]+t,}
\]

where \(A\) is initially rigid and

\[
d(x)=\sum_{k=1}^{K}c_k\phi_k(x)
\]

is a smooth scalar displacement field in millimetres. The scalar field varies in
three dimensions, but displacement occurs along only one direction.

This can be more conservative than a full affine model despite having more
parameters: it does not permit arbitrary scaling or shearing in the other directions.

### Basis choice

Start with a coarse cubic B-spline field or a small smooth spectral basis. Use
perhaps 64 coefficients initially, increasing resolution only when justified.

B-spline deformation fields and efficient Gauss–Newton optimization have
longstanding implementations in FNIRT; the proposed distinction here is the PE
restriction and reuse of Flashalign’s projected-patch machinery.

Penalize both displacement magnitude and roughness:

\[
R_d=
\frac{\lambda_0}{2}\int d^2\,dx
+\frac{\lambda_1}{2}\int\|\nabla d\|^2\,dx
+\frac{\lambda_2}{2}\int\|\nabla^2d\|_F^2\,dx.
\]

The magnitude term prevents poorly observed regions from wandering merely
because a very smooth displacement is cheap.

### The derivative is exceptionally simple

For coefficient \(c_k\),

\[
\frac{\partial T(x)}{\partial c_k}=Ae\,\phi_k(x).
\]

Therefore,

\[
\boxed{
\frac{\partial F(Tx)}{\partial c_k}
=\underbrace{\nabla F(Tx)^TAe}_{\text{one scalar per sample}}\phi_k(x).
}
\]

Compute the directional image derivative once per sample. Every field
coefficient then contributes only a basis weight.

```pseudo
function pe_field_intensity_jvp(sample, coefficient_step):

    directional_gradient = dot(sample.image_gradient, A*pe_direction)
    displacement_step = basis_value_at_sample(coefficient_step)

    return directional_gradient * displacement_step
```

### Invertibility reduces to a scalar condition

Let

\[
U(x)=x+e\,d(x).
\]

Then

\[
DU=I+e\nabla d^T,
\]

and the matrix determinant lemma gives

\[
\boxed{\det DU=1+e^T\nabla d.}
\]

Require

\[
1+\partial_e d(x)\geq\delta>0
\]

throughout the modeled domain.

Each phase-encoding column is then strictly monotone. For a scanner-aligned
spline representation, conservative bounds can be obtained from the derivative
spline’s control coefficients; checking only selected voxels is insufficient.

The inverse is a one-dimensional root solve along each PE column, using
bracketed Newton iterations or bisection.

### Two identifiability requirements

A constant \(d\) is indistinguishable from global translation along \(Ae\). Fix
its weighted mean, anchor it externally, or otherwise define an explicit convention.

With a full affine \(A\), linear components of \(d\) also overlap the affine model.
Assign those components to one model only.

**Do not remove all linear PE-field terms when \(A\) is rigid:** rigid motion
cannot absorb general PE scaling and shear.

### Scope boundary

This estimates an anatomically guided inverse displacement, not automatically a
calibrated off-resonance field. Dropout remains missing information.

When reverse-PE data or a fieldmap are available, use that additional physical
information. For joint reverse-PE fitting, define the opposing forward
distortions on a common undistorted domain; simply negating two inverse fields
evaluated at different observed coordinates is incorrect. TOPUP’s common-field
and motion modeling is an important reference for this distinction.

## 26. Second priority: small-strain anatomical deformation modes

For anatomical registration, use

\[
T(x)=A[x+u(x)]+t,\qquad u(x)=\sum_{k=1}^{K}c_k\psi_k(x),
\]

where \(\psi_k(x)\in\mathbb R^3\) are smooth vector fields.

Start with low spatial frequencies or low-energy elastic modes—not a fine
control lattice. A suitable regularizer is

\[
R(u)=\frac12\int\left[
2\mu\|\operatorname{sym}\nabla u\|_F^2
+\lambda(\nabla\cdot u)^2
+\eta\|\Delta u\|^2
+\gamma\|u\|^2
\right]dx.
\]

These terms discourage shear strain, excessive volume change, fine-scale
bending, and unsupported displacement.

They are **geometric regularizers, not measured tissue mechanics**. In particular,
do not impose strict incompressibility for intersubject or longitudinal
anatomical registration: the shape differences being modeled may include local
expansion and contraction.

### A particularly useful fast version: globally bounded deformation gradient

Before implementing a full diffeomorphic flow, require

\[
\sup_x\|\nabla u(x)\|_2\leq\kappa<1
\]

on a convex padded domain with a defined smooth extension. Then

\[
\|(x+u(x))-(y+u(y))\|\geq(1-\kappa)\|x-y\|.
\]

Thus the map is globally injective on that domain. Its local singular values lie
between \(1-\kappa\) and \(1+\kappa\).

This gives a direct small-deformation model with a genuine sufficient
invertibility condition, without velocity integration.

For a basis expansion, a conservative certificate is

\[
\boxed{\sum_k|c_k|\sup_x\|\nabla\psi_k(x)\|_2<1.}
\]

Precompute the basis derivative bounds.

```pseudo
function certify_small_strain_model(coefficients):

    bound = sum_k(
        abs(coefficients[k]) * basis_derivative_bound[k]
    )

    return bound <= configured_maximum_gradient
```

The bound may be conservative. Later, replace it with tighter cellwise bounds
rather than abandoning certification. The inverse can be obtained from

\[
x_{n+1}=z-u(x_n),
\]

which is a contraction under the same condition.

**This is my preferred first general nonlinear anatomical model.** It is simple,
bounded, and compatible with the existing optimizer.

## 27. Adaptive local refinement: add detail only where it is identifiable

A coarse field may leave genuine local mismatch. Do not automatically double
resolution everywhere.

Refine only where the residual is spatially coherent, the relevant directional
information survives nuisance projection, and a more flexible model improves
separate selection patches.

```pseudo
function propose_local_refinement(current_fit):

    regions = summarize_residual_and_information_by_region()

    for region in regions:

        if region.has_poor_signal:
            continue

        if not region.has_supported_geometric_mismatch:
            continue

        propose finer basis functions in region

    enforce basis compatibility and regularization
    fit proposed extension
    retain only validated improvements
```

For cubic tensor-product splines, each sample touches a bounded local set of
control coefficients. Increasing the total number of control points need not
make every sample depend on every parameter.

However, normalized patches couple their samples. The union of control points
touched by a patch can be larger than the support at one sample. The
implementation must scatter through the complete patch support, not pretend
every voxel contributes an independent scalar residual.

A high-resolution field inferred mainly from its prior should be reported as
such. Smoothness is not evidence.

## 28. Later option: low-dimensional stationary velocity flow

For larger deformations, use

\[
T=A\circ\phi,\qquad \phi=\operatorname{Exp}(v),
\qquad v(x)=\sum_kc_k\psi_k(x).
\]

Stationary-velocity registration and scaling-and-squaring have established
precedents, including DARTEL. Band-limited velocity representations also have
direct prior work, notably Zhang and Fletcher’s Fourier-approximated registration
method.

Flashalign’s opportunity is to combine a low-dimensional field with sparse patch
evaluation—not to claim velocity fields themselves are new.

### Do not differentiate the exponential as though it were displacement

For

\[
\dot z(t)=v_c(z(t)),
\]

the directional sensitivity to coefficient perturbation \(h\) obeys

\[
\dot \eta(t)=Dv_c(z(t))\eta(t)+v_h(z(t)),\qquad \eta(0)=0.
\]

The term \(Dv_c(z)\eta\) is essential.

For sparse optimization, integrate trajectories only at unique patch sample
locations. Cache their stages and use tangent/adjoint operations consistent with
the actual discrete integrator. Do not allocate a dense velocity-to-image Jacobian.

This model is a later extension because correct derivatives and numerical
topology checks add complexity. A theoretically diffeomorphic continuum flow
does not automatically make every sampled or interpolated implementation
fold-free; even common finite-difference Jacobian checks can miss digital folding.

# Part III — Analytical machinery that keeps higher-parameter models fast

## 29. Generalize geometry, not the patch metric

Define one small geometry interface:

```pseudo
interface GeometryModel:

    map(state, points)
        -> transformed_points

    jvp(state, points, parameter_direction)
        -> point_displacement_direction

    vjp(state, points, point_forces)
        -> parameter_forces

    propose(state, parameter_step)
        -> candidate_state

    prior_terms(state)
        -> value, gradient, curvature_operator

    certify(state)
        -> geometric_validity

    inverse_map(state, points)
        -> inverse_points
```

`jvp` means Jacobian–vector product. `vjp` means its transpose operation.

The patch evaluator should not know whether the geometry has 6, 12, 96, or 1,000
parameters. The affine model retains its specialized small-matrix kernel. Larger
models use the operator implementation below.

## 30. The central speed result: exact matrix-free patch curvature

For any parameterized transform, let

\[
G=\frac{\partial f}{\partial\theta}.
\]

The normalized-patch Jacobian remains

\[
J=\frac{PG}{L},\qquad P=C-vv^T.
\]

Because \(P^2=P\),

\[
\boxed{J^TJh=\frac{G^TPGh}{L^2}.}
\]

No \(K\times K\) matrix is required. For each patch, computing the curvature
product requires:

1. The intensity perturbation \(Gh\).
2. Centering and one rank-one projection.
3. Applying \(G^T\).

The expensive image interpolation is absent: values, gradients, normalization,
and robust weights are cached at the current linearization.

### Complete operator pseudocode

```pseudo
function data_curvature_product(direction, cache):

    # One displacement direction per unique sample.
    dy = geometry.jvp(
        cache.state,
        cache.unique_points,
        direction
    )

    df = row_dot(cache.image_gradients, dy)

    scalar_forces = zeros(number_of_unique_points)

    for patch in cache.patches:

        if patch.invalid:
            continue  # Its constant outlier loss remains in the objective.

        z = gather(df, patch.sample_indices)

        # P*z, where P is the exact normalization projector.
        z = z - mean(z)
        z = z - patch.v * dot(patch.v, z)

        z *= patch.objective_weight
             * patch.inlier_weight
             / patch.L²

        scatter_add(scalar_forces, patch.sample_indices, z)

    spatial_forces =
        cache.image_gradients * scalar_forces[:, None]

    return geometry.vjp(
        cache.state,
        cache.unique_points,
        spatial_forces
    )
```

Add prior and damping products:

\[
\mathcal Hh=\mathcal H_{\mathrm{data}}h
+\mathcal H_{\mathrm{prior}}h+\mu Dh.
\]

Solve with preconditioned conjugate gradients.

The corresponding data right-hand side uses the patch force

\[
\frac{a_pz_p}{L_p}(u_p-c_pv_p),
\]

where \(z_p=\alpha_{p,+}-\alpha_{p,-}\). Scatter these scalar forces, multiply by
image gradients, then call the same geometry transpose operation.

**This is the main architectural reason hundreds of parameters need not destroy
Flashalign’s speed.**

## 31. Use the deformation prior as a preconditioner

The data curvature may be irregular. The deformation prior is deliberately
smooth and structured.

For constant-coefficient elasticity on a padded periodic Fourier domain, the
regularizer’s symbol is

\[
\widehat L(k)=a(k)I+(\lambda+\mu)kk^T,
\]

where

\[
a(k)=\gamma+\mu\|k\|^2+\eta\|k\|^4.
\]

Split a vector into components parallel and perpendicular to \(k\):

\[
r=r_\parallel+r_\perp.
\]

Then

\[
\boxed{
\widehat L(k)^{-1}r
=\frac{r_\perp}{a(k)}
+\frac{r_\parallel}{a(k)+(\lambda+\mu)\|k\|^2}.
}
\]

That is a cheap modewise operation.

For spatially varying stiffness or different boundary conditions, this expression
is a preconditioner—not the exact inverse of the actual regularizer.

For spline fields, start with block-diagonal data information plus a structured
smoothness preconditioner. Add multigrid only when profiling demonstrates the need.

The crucial performance counter is **matrix–vector products per accepted step**,
not just outer iteration count.

## 32. Eliminate global pose before solving for deformation

Pose and deformation compete to explain some of the same residuals. Partition
the local system:

\[
\begin{bmatrix}H_{aa}&H_{ac}\\H_{ca}&H_{cc}\end{bmatrix}
\begin{bmatrix}\delta a\\\delta c\end{bmatrix}
=\begin{bmatrix}b_a\\b_c\end{bmatrix},
\]

where \(a\) contains the 6 or 12 global parameters. Eliminate them:

\[
\boxed{
\left(H_{cc}-H_{ca}H_{aa}^{-1}H_{ac}\right)\delta c
=b_c-H_{ca}H_{aa}^{-1}b_a.
}
\]

Then recover

\[
\delta a=H_{aa}^{-1}(b_a-H_{ac}\delta c).
\]

Never form the inverse explicitly; factor the tiny global block and solve
against it.

```pseudo
factor_global_block(Haa)

function conditional_warp_product(h):

    return Hcc_product(h)
           - Hca * solve_global(Hac*h)
```

This is both a conditioning improvement and an interpretive improvement:

> The deformation is asked to explain what remains after the best local pose adjustment.

Exact parameter redundancies still require an explicit gauge convention. A Schur
complement cannot make an unidentifiable decomposition identifiable.

## 33. A useful approximation: compress smooth deformation into local patch motion

For a smooth displacement increment near patch centre \(x_p\),

\[
\delta u(x_p+\xi)\approx\delta u(x_p)+D\delta u(x_p)\xi.
\]

Thus a general smooth field acts approximately like a **12-parameter local affine
motion** within a sufficiently small patch. For the scalar PE field,

\[
\delta d(x_p+\xi)\approx\delta d(x_p)+\nabla\delta d(x_p)^T\xi,
\]

requiring only **four local coefficients**. This suggests a reusable compression:

\[
H_{\mathrm{global}}\approx\sum_p B_p^TH_{p,\mathrm{local}}B_p.
\]

The patch stores a small local information matrix; \(B_p\) converts global field
coefficients into local value and derivative coefficients.

For a scalar PE extension, the local system can combine six pose parameters and
four field coefficients rather than constructing derivatives against hundreds of
global coefficients at every sample.

### Where this approximation belongs

Use it first for screening and preconditioning, or for proposing a step
subsequently evaluated with the exact transform. The Taylor remainder satisfies
a bound of the form

\[
\|\mathrm{error}\|\leq\frac12
\sup_{\mathrm{patch}}\|D^2\delta u\|\,r_{\mathrm{patch}}^2.
\]

If the estimated error is not small relative to the requested spatial accuracy,
use the exact basis evaluation.

**Do not label this compression exact for arbitrary spline fields.**

## 34. Release deformation modes according to information, not residual size alone

A large residual may indicate dropout, contrast mismatch, or missing anatomy—not
a need for more parameters.

After conditioning on global pose, let \(S\) denote data curvature for the
deformation coefficients and \(\Lambda\) their prior precision. Consider

\[
M=\Lambda^{-1/2}S\Lambda^{-1/2}.
\]

For an eigenvalue \(\lambda_j\), the corresponding local regularized sensitivity
factor is

\[
\frac{\lambda_j}{1+\lambda_j}.
\]

Modes with very small \(\lambda_j\) are mostly prior-controlled. A useful local
diagnostic is

\[
d_{\mathrm{effective}}=\operatorname{tr}\left[M(I+M)^{-1}\right].
\]

This is not a calibrated global uncertainty measure. It is an operational summary
of how much of the proposed flexibility the current linearization can constrain.

Combine it with the residual’s projection onto each mode and with improvement on
selection patches.

```pseudo
function consider_model_expansion(fit):

    conditional_information =
        deformation_information_after_pose_elimination(fit)

    proposals = next_coarser_to_finer_mode_block()

    discard proposals with negligible information
    discard proposals with negligible predicted residual reduction

    fit remaining proposals under the same physical constraints

    retain expansion only if:
        selection patches improve
        regional stability is preserved
        geometric constraints remain satisfied
```

The default should therefore be **the smallest supported model**, not the largest
allowed model.

# Implementation sequence and release criteria

I would implement the extensions in this order:

**First:** finish and benchmark the rigid/affine engine, including stable
normalization, fixed-objective acceptance, unique-point interpolation, and
explicit failure reporting.

**Second:** add a roughly 64-coefficient PE field with rigid pose, exact
directional derivatives, a scalar monotonicity certificate, and a correct inverse.

**Third:** add a roughly 96-mode small-strain anatomical field with a derivative
bound and matrix-free optimization.

**Fourth:** add adaptive local refinement. Introduce velocity-flow integration
only when the bounded small-strain model demonstrably limits useful registrations.

For every extension, benchmark geometric accuracy independently of the optimized
image-similarity score. Compare equivalent data and transformation classes: an
anatomically guided PE field should not be declared superior to an affine method
merely because it has permission to correct nonlinear distortion.

Also distinguish three claims in the software’s documentation:

**Invertible** means the geometric map satisfies the relevant conditions.  
**Regularized** means it is penalized toward chosen deformation properties.  
**Anatomically accurate** requires external validation.

None implies the next.

## Numerical checks already performed

> This subsection records the supplied author's claims and results, not checks
> independently rerun during proposal transcription.

I checked the additional algebra against explicit matrices and finite
differences. The streamed curvature and matrix-free curvature product agreed with
their explicit counterparts to approximately \(4\times10^{-16}\) relative error
in the supplied tests. The directional patch derivative agreed to approximately
\(1.6\times10^{-10}\), and the PE spatial Jacobian to approximately
\(2.1\times10^{-9}\).

These are numerical correctness checks, not MRI accuracy or runtime benchmarks.

The supplied source-reference markers are preserved verbatim below; their
underlying references were not included in the supplied text:

```text
:chatgpt-content-reference{index="7"} · :chatgpt-content-reference{index="8"}
```

**The strongest design is a shared projected-patch engine with two carefully
constrained extensions: a scalar acquisition-distortion field for EPI, and a
low-dimensional strain-controlled field for anatomy.** Cache image evidence once
per linearization, solve through geometry operators, and let additional
flexibility enter only when it explains mismatch that pose and contrast nuisance
cannot.

## Additional supplied core script

The user also supplied [nuisance_projected_registration_core.py](flashalign/nuisance_projected_registration_core.py),
copied byte-for-byte from
`/Users/bbuchsbaum/Downloads/nuisance_projected_registration_core.py`.
This associated source artifact is preserved without modification or execution.
