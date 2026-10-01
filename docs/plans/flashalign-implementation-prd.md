# Flashalign implementation PRD

Version 2 · 2026-09-12 · implementation specification; implementation pending.

Make Flashalign the library's exceptionally fast, robust rigid/affine alignment
method, with an elegant and performant constrained nonlinear extension. It is
CPU-first and deterministic. Its nonlinear models are rigid pose plus a
phase-encoding (PE) displacement field and global pose plus a small-strain
anatomical field. HalfFlow/BasinBridge remains the complementary nonlinear
registration line targeting ANTs-class anatomical registration. Keep each
method's identity while sharing qualified primitives, comparing results, and
composing methods when experiments demonstrate a benefit.

This document makes implementation decisions from the
[original proposal record](flashalign-prd.md). Part 2's correctness repairs take
precedence over Part 1. The original remains a source record. This specification
is an additive feature plan under the repository's [architecture PRD](../../PRD.json),
not a replacement for that document or a declaration that the feature exists.
New API names below are proposed unless explicitly identified as existing.

## 1. Product contract and completion boundary

The primary workflow accepts two scalar 3-D images and returns a moving-to-fixed
physical map, its usable fixed-to-moving pullback, termination, QC, and evidence.
The user chooses a small named modality/model preset; advanced controls refine
that same computation rather than invoking a second implementation.

| Preset/model | Required delivery | Constraints |
|---|---|---|
| EPI→T1w / EPI→T2w rigid | Core release | Robust polarity mixture; 6 pose parameters |
| Within-modality rigid/affine | Core release | Repeated EPI, T1w→T1w, T2w→T2w; 6/12 parameters |
| EPI→anatomical PE field | Nonlinear release | Rigid pose plus initially about 64 scalar field coefficients; known PE axis |
| Anatomical small-strain field | Nonlinear release | Rigid or affine global map plus initially about 96 vector-mode coefficients |
| Adaptive local refinement | Subsequent explicit extension | Evidence-supported local basis growth, initially a few hundred to about 1,500 coefficients |
| Stationary velocity flow | Deferred extension | About 96–384 coefficients; discrete tangent/adjoint and topology qualification required |

The full requested scope includes **both a complete linear product and the
constrained nonlinear extensions**. The linear product has its own release and
performance gates and can ship independently; it must not wait for nonlinear
infrastructure or HalfFlow integration. PE and anatomical models are separate
choices; registration does not automatically traverse the model ladder.
Small-strain is not a promise of arbitrary atlas-scale normalization.

### 1.1 Complementary method identities

| Method | Main purpose | Main engineering pressure |
|---|---|---|
| **Flashalign** | Extremely fast, reliable rigid/affine alignment; economical constrained nonlinear refinement | Small streaming evaluations, low setup cost, few allocations, bounded latency and a small failure tail |
| **HalfFlow/BasinBridge** | Richer anatomical nonlinear registration, with ANTs as an external accuracy target | Correspondence capture, deformation expressiveness, topology, inverse consistency and anatomical accuracy |

ANTs is a target for the HalfFlow line, not a statement of achieved parity.
The methods overlap usefully on modest nonlinear problems. That overlap is an
opportunity for comparisons, initialization and shared implementation, not a
reason to make them one optimizer.

The core Flashalign path must instantiate only rigid/affine state and its small
kernel: no field basis construction, Krylov buffers, velocity stages, dense warp
allocation, HalfFlow initialization or joint-method dispatch during a linear fit.
Choose the specialized kernel at plan construction; keep a common objective
contract without routing the linear hot loop through per-sample generic geometry
callbacks. The nonlinear extension must justify its added setup, work and memory
through a measured accuracy-versus-cost benefit.

Adaptive fields and velocity flow remain conditional. Prefer the existing
HalfFlow line when broadening Flashalign would duplicate its purpose or erode
the linear product's simplicity and speed.

Partial-coverage/slab data are first-class inputs. Non-goals remain whole-series
motion correction, slice-to-volume fitting, recovery of EPI dropout, universal
medical-image registration, GPU execution, and unrestricted dense warping.
A PE fit estimates anatomically guided displacement; calibrated off-resonance
estimation, joint reverse-PE fitting, and fieldmap workflows remain separate.

Desirable desktop CPU targets for reasonably initialized ordinary same-subject
pairs are rigid EPI→anatomical <1 s, within-modality rigid substantially below
1 s, and affine refinement <2 s. These are unmeasured targets. Preparation,
capture, validation, decompression, and output costs must be visible separately.
No nonlinear runtime promise is made before profiling. Geometric failure tails
outrank latency: the intended rigid/affine external comparison is lower
catastrophic failure and p95 error than 3dAllineate, with median error no worse.
Those claims require the separate benchmark gate in §13.

## 2. Inspected repository baseline

Baseline: `HEAD fa015c38a1b481096646d2c857191c327fc7a9e5`, plus the dirty working
copy inspected on 2026-09-12. Exact hashes and executed checks are in the
[planning evidence receipt](flashalign/implementation-evidence-2026-09-12.json).
README/status snapshots describe older representations and pins in places;
current source and declared build pins govern implementation.

| Existing owner and source | Reuse or required additive work |
|---|---|
| `image4s-geometry`: `Frame`, `Grid`, `Affine`, points and identities | Sole geometry/affine authority; retain live owners and persistent records |
| `image4s-core`: `Sampled[S,A,Sem,R]`, `SampleSpace`, continuous roles | Sole image owner. Current API uses sample-space ownership, unlike older five-parameter descriptions |
| [`reframe4s-core/SpatialMap.scala`](../../modules/reframe4s-core/shared/src/main/scala/reframe4s/core/SpatialMap.scala) and [`Evidence.scala`](../../modules/reframe4s-core/shared/src/main/scala/reframe4s/core/Evidence.scala) | Typed endpoints, map composition, exact versus numerical inverse evidence |
| [`Rigid3.scala`](../../modules/reframe4s-lie/shared/src/main/scala/reframe4s/lie/Rigid3.scala), [`FramedAffine.scala`](../../modules/reframe4s-lie/shared/src/main/scala/reframe4s/lie/FramedAffine.scala) | Reuse SE(3), `Twist6`, and framed affine operators; add a checked affine retraction only here if needed |
| [`Registration.scala`](../../modules/reframe4s-register/shared/src/main/scala/reframe4s/register/Registration.scala) | Existing `ValueProblem`, `GradientProblem`, `ResidualProblem`, `Retraction`, reports and capability-precise results; do not replace these protocols |
| [`ScalarPyramid3.scala`](../../modules/reframe4s-multiscale/shared/src/main/scala/reframe4s/multiscale/ScalarPyramid3.scala) | Reuse `GridTower`, `ScaleSpec`, physical filtering, continuation; add support-aware preparation through this owner |
| [`SampledInterpolator.scala`](../../modules/reframe4s-resample/shared/src/main/scala/reframe4s/resample/SampledInterpolator.scala) | Existing pointwise value/validity semantics; needs a compiled D3 fused value/exact-gradient capability |
| [`ResamplingPlan.scala`](../../modules/reframe4s-resample/shared/src/main/scala/reframe4s/resample/ResamplingPlan.scala) | Existing `affine`, `mapped`, `scan`, workspaces, cubic/Lanczos5 output and validity; reuse them |
| [`RigidOptimizationKernel.scala`](../../modules/reframe4s-motion/shared/src/main/scala/reframe4s/motion/RigidOptimizationKernel.scala), [`RigidEstimator.scala`](../../modules/reframe4s-motion/shared/src/main/scala/reframe4s/motion/RigidEstimator.scala) | Existing canonical motion objective and private kernels; their stencil/SSD semantics are not the projected-patch objective |
| [`DenseField.scala`](../../modules/reframe4s-field/shared/src/main/scala/reframe4s/field/DenseField.scala), [`Flow.scala`](../../modules/reframe4s-flow/shared/src/main/scala/reframe4s/flow/Flow.scala) | Canonical component storage and numerical flow/evidence when needed; existing Euler integration is not a qualified sparse velocity derivative backend |
| `reframe4s-halfflow` and its BasinBridge additions | Independent experimental engine, ownership and evidence; no imports of its private helpers or changes to its default objective |

Current build declarations: Scala 3.7.4, sbt 1.11.7, Scala.js 1.22.0, MUnit and
MUnit-ScalaCheck 1.3.0. Strict options include `-Wunused:all`, `-Wvalue-discard`,
and `-Werror`. Exact provider pins inspected:

| Provider | `build.sbt` revision |
|---|---|
| image4s | `ec56b34806c22e26c28ecbd366ef2e323195fc88` |
| Ravel | `9c5669399ab8e2a11402e71973dd5f1e2f2c13f4` |
| Gale | `83cac90a678d1b8a31c590e0c1b8fc8bf3427161` |

### Baseline issues that must be resolved or explicitly qualified

1. `verify-prd.mjs` passes; `verify-build-graph.mjs` currently fails because
   `scalafim-image -> reframe4s-lie` is present in the TSV but undeclared in
   `PRD.json`. Reconcile the existing intended edge before adding feature edges;
   do not remove it just to make a validator green.
2. CI's ownership checkout uses image4s `c1c9866...`, while the build pins
   `ec56b348...`. Ownership qualification must inspect the same admitted provider
   revision as the consumer build. Reconcile this in its own baseline change.
3. Parent workspace policy targets sbt 1.12.14; this checkout declares 1.11.7.
   Before implementation admission, verify an exact catalog exception or perform
   a separately scoped toolchain migration. Do not silently alter the toolchain
   within an algorithm commit.
4. Mote records existing identity/provider work, including
   `bd-01KYSQBDJ5WYPADYRPN9FMP0WA` and `bd-01KZ9E29X8ZFRVX94CD04NA2H1`.
   Use current owner decisions and qualified pins, not old issue-title candidate
   SHAs, to establish the implementation baseline.
5. HalfFlow/BasinBridge source and `build.sbt` already have unrelated edits.
   Mote B7 (`bd-01KZ28VTQNRGVY6XSB35DY7CN3`) has a frozen one-factor affine
   preconditioning experiment. Flashalign is not an implicit replacement for it.

These are observed/planned integration gates, not new failures introduced by this
PRD. This planning task changes no implementation or build files and does not
claim a clean aggregate build, remote CI, or publication.

## 3. Module and ownership decisions

Add **one opt-in `reframe4s-flashalign` cross-project**, package
`reframe4s.flashalign`, initially `internal-experimental`, shared JVM/Scala.js
source with platform-specific execution adapters. Keep it out of the default
`reframe4s` umbrella until its release gate is deliberately changed.

Required new consumer→dependency edges:

```text
reframe4s-flashalign -> reframe4s-core
reframe4s-flashalign -> reframe4s-lie
reframe4s-flashalign -> reframe4s-register
reframe4s-flashalign -> reframe4s-resample
reframe4s-flashalign -> reframe4s-multiscale
reframe4s-flashalign -> image4s-geometry
reframe4s-flashalign -> image4s-core
reframe4s-flashalign -> ravel-core
reframe4s-flashalign -> gale-core
```

Declare these together in `PRD.json`, `project/artifact-graph.tsv`, and the build
binding `reframe4sFlashalign = foundationProjects("reframe4s-flashalign")`.
The existing graph-derived construction creates both platform projects. No
`motion -> flashalign`, `halfflow -> flashalign`, `register -> flashalign`, or
foundation→ScalaFIM reverse edge is needed. Add a `field` edge only with an actual
dense-field export consumer; add a `flow` edge only with the deferred velocity
backend. Test-only image4s-reference/NIfTI and JVM JMH dependencies stay scoped to
tests/benchmarks and outside published runtime POMs.

The execution spine stays small:

```text
canonical Sampled pair
    -> prepared levels + frozen patch populations
    -> bounded capture candidates
    -> projected-patch refinement with a selected geometry model
    -> typed maps + inverse evidence + QC
```

Internal source groups, all under the new module:

| Group | Responsibilities |
|---|---|
| `Flashalign`, `FlashalignPlan`, `FlashalignWorkspace` | Validated entry point, immutable compiled pair, explicit reusable scratch |
| `Preparation`, `PatchPopulation`, `PatchSampleSet` | Source support, scale/PSF policy, pre-normalization, sampling, unique-point registry |
| `PatchObjective`, `PatchLinearization` | One robust loss, scalar oracle, streamed affine and matrix-free kernels |
| `GeometryModel`, `RigidModel`, `AffineModel` | Typed parameter states and point JVP/VJP/retraction; canonical exported maps |
| `PeFieldModel`, `SmallStrainModel`, `Basis`, `GeometryCertificate` | Constrained coefficients, gauges, priors, derivatives and bounded numerical inverse |
| `ProjectedPatchOptimizer`, `Capture` | Fixed-objective trust trials, checkpoints, bounded search |
| `FlashalignDiagnostics`, `FlashalignResult` | Failure/termination, selection/audit evidence, quality and work counters |

These are ownership groups, not a demand for a public class per filename.
`GeometryModel` and compiled buffers stay package-private initially. Do not build
a public plug-in framework or a second generic optimizer algebra.

Reusable primitives must be contributed to their existing owner. The fused
sampler belongs in `reframe4s-resample`; physical support-aware filtering belongs
in `reframe4s-multiscale`; any generally useful affine retraction belongs in
`reframe4s-lie`. Land and test those as small additive changes before consuming
them. Share an internal primitive only when its common semantic contract has
been established; move it to the existing foundation owner and migrate consumers
in separate parity-checked changes. Do not expose method-private controls as a
general API merely because two loops look alike. Preserve current behavior during
shared-code extraction; method improvements have separate before/after evidence.

Gale owns general dense algebra and iterative solvers. The inspected pin contains
`DoubleLinearOperator`, `Preconditioner`, `CgWorkspace`, and `Solvers.cgWith`.
Qualify their failure, allocation, and residual behavior through an adapter;
catch documented provider `LinAlgError` at that boundary and preserve typed
causes. No generic solver/FFT/matrix library is to be reimplemented in Flashalign.
No FFT capability was identified in the inspected Gale source inventory: full
structural capture requires a separately admitted exact provider/backend (§8).

### 3.1 Shared primitives, distinct methods, explicit composition

Sharing useful code is an objective, not merely permitted cleanup. Evaluate each
opportunity on semantic agreement, maintenance savings, measured cost and the
behavior of both consumers. Keep separate objectives, parameterizations,
regularization policies, capture schedules, stopping decisions, validation and
maturity records. A common kernel must not force either method into the other's
scientific assumptions.

| Sharing opportunity | Owner and adoption contract |
|---|---|
| Physical coordinates, affine/rigid transforms, composition and inverse records | Existing image4s/reframe4s foundations; always shared canonical values |
| Fused interpolation, rank/layout-safe gathers, output resampling | `reframe4s-resample`; share when interpolant, derivative and support semantics match |
| Gaussian filtering, physical pyramids and mask propagation | `reframe4s-multiscale`; reuse execution with explicit sigma, covariance, boundary and support policies |
| Smooth field basis evaluation, derivative bounds and inverse utilities | `field`/`flow` only when two actual consumers establish the same contract; Flashalign-specific PE/gauge policy stays local |
| Tiny solves, structured operators, preconditioning and FFT workspaces | Gale or its qualified provider; share numerical machinery, not a universal registration optimizer |
| Fixed work blocks, timing/allocation probes and independent geometric metrics | Existing appropriate execution owner or unpublished benchmark support; method identity and work counters remain visible |
| Robust loss, normalized patches, Neighborhood-CC, correspondence confidence | Keep method-specific until mathematical and support equivalence is proved; names such as “CC” do not establish interchangeability |

An extraction has a small sequence: identify the two concrete call sites and
semantic contract; add the primitive to its existing owner; qualify against an
independent oracle; migrate one consumer without changing its settings/results;
then migrate the other and compare allocation, throughput and failure behavior.
Shared caches need an identity covering input/grid, scale/PSF, interpolation,
support/mask, derivative convention and preparation version. If that identity
does not match, reuse the primitive implementation and prepare separate values.
Do not silently share a mask, patch population or prepared image that changes
another method's objective. Count shared preparation once in an actual composed
run and also show standalone cold costs for fair independent comparison.

Do not create a broad “registration-common” module speculatively. Keep
method-neutral code in existing lower-level owners, preserve dependency
acyclicity, and retain specialized hot loops where the objectives differ.
Extraction is worthwhile when it eliminates real duplication or gives a measured
benefit; architectural symmetry alone is insufficient.

### 3.2 First transfer: Flashalign linear initialization for HalfFlow

The first concrete experiment is a Flashalign rigid/affine fit feeding the
existing HalfFlow/BasinBridge nonlinear path. The inspected
[`AffineInitialization.scala`](../../modules/reframe4s-halfflow/shared/src/main/scala/reframe4s/halfflow/AffineInitialization.scala)
already provides `AffineInitializer.supplied(fixed, moving, work, fixedToMoving)`
and midpoint splitting. Its current parameter is an internal `Affine3D`, so it
is an integration point to adapt, not an already canonical public seam.

Add a HalfFlow-owned checked adapter from a canonical
`AffineMap[Fixed,Moving,D3]` to the existing supplied-initializer path. It must
validate frames/units, direction, layout conversion and all midpoint prerequisites;
Flashalign consumers must not import `halfflow.internal.*`. Use Flashalign's exact
**inverse** for this pull-direction input. A valid global affine can still fail
this particular midpoint split; retain the typed failure rather than fabricate
matrix roots or weaken the receiving method's checks.

The experiment changes only the initializer. Hold HalfFlow's matcher,
Neighborhood-CC, nonlinear controls, masks, topology/export thresholds and compute
policy fixed. Retain initialization, bridge, fine-refinement and export results
separately. Assess whether a better initial pose reduces required deformation,
retries, folds or total cost while improving external geometry. Treat that as a
hypothesis until measured. This is a new experiment, not a rewrite of B7's frozen
correspondence-derived affine-preconditioning receipt.

### 3.3 Other transfers must preserve the receiving model

| Direction | Candidate opportunity | Admission requirement |
|---|---|---|
| HalfFlow/BasinBridge → Flashalign linear | Structural correspondences or a coarse affine candidate help a difficult initialization | Transfer geometry only; refine/compare with Flashalign's frozen objective and record the full upstream cost |
| Flashalign constrained warp → HalfFlow | Pose plus a smooth coarse field leaves an easier residual anatomical problem | Receiving endpoint/midpoint adapter supports the map and rechecks inverse, domain and topology; never treat arbitrary displacements as midpoint arms |
| HalfFlow → Flashalign constrained warp | A field proposal identifies useful low-dimensional deformation directions | Project/refit into the declared Flashalign basis and gauge on separate development/selection evidence; certify the resulting model and evaluate its own loss; no unrestricted warp hidden in a PE/small-strain result |
| Either method → shared development | Failure cases, independent geometry diagnostics or a proven sampling/filtering optimization improve the other | Keep fixture provenance, preserve sealed test splits and obtain the recipient's own regression/performance evidence |

For composed maps, make endpoints explicit. If a prealignment is
\(A:M\to W\) and a nonlinear **residual** map is \(R:W\to F\), the final forward
map is \(T=R\circ A\) and the pullback is \(A^{-1}\circ R^{-1}\). If the receiving
method instead returns a **complete original-domain map** \(M\to F\), use it
directly; do not compose the initializer a second time. The adapter's type and
result record must distinguish those cases, with landmark/composition tests.
Intermediate preparation may evaluate warped images, but final output samples
the original moving image once. Include all intermediate interpolation and
preparation costs and propagate validity across the composed domains.

Keep composed workflows in an unpublished runner or the higher-level ScalaFIM
adapter that depends on both method artifacts. Neither method needs a direct
runtime dependency on the other. The standalone library APIs accept explicit
canonical initial geometry. A composition gets its own method identifier,
ordered stage revisions/settings, intermediate evidence and final QC. Certificates
must be checked on the actual composed domain; a valid stage certificate does
not automatically certify every output point or a regridded interpolation.

No alternating two-engine outer loop is the default. Begin with one-way,
bounded initialization/refinement transfers. Add a round trip only if a
predeclared experiment shows a reproducible gain at an acceptable complete cost.
The standalone methods remain available and independently benchmarked.

## 4. Public API, types, and result semantics

Use the current image4s shape, conceptually:

```scala
// Proposed algorithm-specific constraint; no new image container.
type AlignmentImage[F <: Frame[D3], R <: AnyRank] =
  ContinuousImage[? <: SampleSpace[F, D3], Double, R]
```

The first implementation accepts D3 continuous scalar, spatial-only images.
Validate runtime ranks once using the canonical rank refinement. A time/echo
volume must be selected explicitly by the caller; no implicit first-volume or
channel averaging. Preserve input sample-space/frame identities, non-spatial
axis checks, and strided/cropped image semantics. Float input conversion is an
explicit canonical image operation, with memory cost recorded. The initial
Double path matches existing resampling; do not create a parallel Float volume
owner to match the original pseudocode.

Proposed ergonomic flow (design sketch, not a currently compilable example):

```scala
for
  plan <- Flashalign.compile(moving, fixed, FlashalignPreset.EpiToT1)
  fit  <- plan.runFrom(initialMovingToFixed, plan.newWorkspace())
yield fit
```

The compile step infers frame/rank witnesses. Provide separately named rigid,
affine, PE, and anatomical model constructors so rigid callers keep a `Rigid3`
result rather than recovering it from an untyped matrix. A preset is validated
configuration, not an ambient mutable default. The same plan can be reused with
fresh workspaces. Concurrent reuse of one workspace returns a typed error.

`runFrom` requires an endpoint-correct map. A convenience `run` may construct
coordinate identity between the two frame owners only when the caller has
explicitly selected a coherent-header initialization policy. Equal labels or
identical numeric matrices never imply frame identity.

Reuse `OptimizationReport` and appropriate existing `RegistrationResult`
constructors by composition: the result traits are sealed, so do not subclass
them from the new module. Flashalign-specific results additionally retain the
concrete model, `movingToFixed`, `fixedToMoving` where available, diagnostics,
selection/audit reports, and model/inverse certificates.

| Result capability | Required representation |
|---|---|
| Rigid/affine complete fit | Canonical exact `Rigid3`/`FramedAffine`; exact registration result |
| Nonlinear usable pair | Forward `SmoothMap` where smoothness is established; numerical inverse as `SpatialMap` plus `InverseEstimate`/`CertifiedBidirectionalPair` and retained evidence |
| Failed or stalled fit | Typed outcome with reason, counters and last valid diagnostic checkpoint; never a successful identity |
| Requested nonlinear model unsupported by data | Explicit status; optionally return a validated rigid/affine fallback only under a declared fallback policy |

Do not promote a coefficient derivative bound or a numerically converged inverse
to `SmoothIso`. A new global analytic bound certificate has a different meaning
from the existing sampled `TopologyCertificate`; retain both scopes explicitly.
`RegistrationResult.certified` does not by itself retain every certificate in the
returned interface, so the Flashalign wrapper must preserve them.

Failures distinguish invalid image/configuration/PE metadata, foreign owners,
insufficient overlap, insufficient data information, ambiguous candidates,
invalid geometry, numerical inconsistency, inverse failure, solver breakdown,
search/budget exhaustion, and workspace conflict. Expected outlier patches are
objective contributions, not fatal errors. Public errors are closed ADTs with
structured upstream causes; no strings, NaN sentinels, casts, `null`, `.get`,
warning suppression, or fabricated equalities/inverses. Do not add new cases to
existing sealed enums just to encode Flashalign-private states.

## 5. Coordinate, parameter, and preparation contract

### 5.1 Direction and updates

Optimize `movingToFixed`: \(y=T(x)\), where both coordinates are world millimetres.
Sampling fixed intensities at moving-domain stencil points uses
\(i_F=V_F^{-1}TV_Mi_M\). Pull resampling uses the inverse. The Python core calls
its variable sampled vector `moving`; in this PRD that vector is **the fixed
image sampled at \(T(x)\)**. Port the algebra, not that naming convention.

Write patch correlation as \(c_p\) and the fixed-world rotation pivot as \(o\)
to avoid the original overloaded `c`. For a left rigid increment around \(o\),

\[
\delta y=\delta t+\delta\omega\times(y-o),\qquad
G_j=[g_x,g_y,g_z,(y-o)\times g].
\]

Use `Rigid3`/`Twist6` exp and composition. Convert a pivot-centred translation
increment to the origin-centred twist explicitly:
\(v_{\rm origin}=\delta t-\delta\omega\times o\).
Freeze the pivot within the linearization/trial block.

Affine parameters are translation, rotation and six symmetric-strain coordinates.
Fix the strain basis to three diagonal units and three symmetric off-diagonal
units (entries 1 in both positions; no hidden factor of 2 or \(\sqrt2\)). To avoid
assuming an unqualified matrix-exponential provider, the first affine retraction
is explicitly the local multiplicative map

\[
y'=o+\delta t+(I+[\delta\omega]_\times+S(\delta s))(y-o).
\]

It has the prescribed Jacobian at zero. Reject increments with singular or
non-positive determinant, and enforce configured singular-value/strain bounds.
This is a deliberate implementation choice replacing the proposal's unspecified
affine exponential; rigid updates still use SE(3). Implement it through the
canonical affine constructor/composition, with no second matrix authority.

All transform and basis derivatives, trust metrics, certificates, and stopping
thresholds use physical units. Convert voxel interpolant gradients by
\(g_{\rm world}=V_{F,\mathrm{linear}}^{-T}g_{\rm voxel}\), including reflection,
obliquity and shear. Divide-by-spacing alone is insufficient.

### 5.2 Preparation

Validate finite invertible affines, role/rank, declared units, masks and source
ownership. Quantile-based intensity plausibility is a configurable transform-free
screen, not a universal brain mask: foreground is not synonymous with positive
intensity. Preserve explicit support for finite data, masks, dropout and missing
samples. Reject unsupportable tiny volumes rather than fabricate a valid patch.

Reuse canonical scale schedules with provisional physical effective resolutions
around 6 mm, 3 mm, and native EPI or 1.5–2 mm anatomy. Record whether a scale is
FWHM, sigma or grid spacing; convert explicitly. Start from native data for each
prepared level. Match fixed/moving available bandwidth with a declared PSF
approximation from voxel dimensions; negative required blur means zero added
blur, never deconvolution. Keep that approximation in provenance.

`ScalarPyramid3` currently filters along grid axes and clamps boundary taps.
For orthogonal oblique grids this supports physical axis scales; for sheared
axes it is not an exact isotropic world Gaussian. Add a multiscale policy that
handles covariance correctly or explicitly stages to an orthogonal physical
working grid with anti-aliasing. Test sheared headers, missing data, support
propagation and PSF estimates. Existing callers retain their existing filtering
policy. No unsupported pixel is silently made observed by blur or padding.

Create moving-domain 27-point stencils with shared physical offsets
\(\{-d,0,d\}^3\), deterministic world-space candidate cells (initially 12 mm),
and source-only quality weights. Store mean-centred, unit-norm moving samples
once. The reference and first production path store these 27 coefficients as
Double: rounded Float32 coefficients are not exactly centred/unit, so exploiting
zero sums without a correction would break exact identities. A Float32 storage
optimization requires a separately validated centering/renormalization policy
and an explicit approximation tolerance.

Reject low-contrast or unsupported source patches before fixing the population.
Use about 10–30k screened candidates and initially 4k optimization draws per
level, subject to available support. Every reduced budget and coverage deficit
is reported. Unique point keys derive from exact stencil construction/index
identity, never rounded transformed coordinates or approximate spatial merging.

## 6. One objective and two qualified evaluation paths

### 6.1 Frozen objective

Partition the source population into optimization, selection and audit sets with
fixed spatial blocks and recorded IDs. Prevent overlapping stencils/unique points
from crossing a holdout boundary when the split is used to assess generalization;
otherwise label the sets as numerical holdouts with spatial dependence. Selection
chooses capture candidates, checkpoints and complexity; audit is read only after
model selection. Subject-level test cohorts remain independent of all three.

For population weights \(q_p\geq0\), \(Q=\sum q_p>0\), define the level estimand

\[
E(T)=Q^{-1}\sum_{p\in\mathcal P}q_p\ell(c_p(T))+R(T).
\]

Start with deterministic-seeded **sampling with replacement**, with positive
probabilities over every eligible patch. A uniform/spatial component must remain
nonzero; optional information weights are computed only at refresh boundaries.
For \(M\) draws, set each draw's weight to \(q_p/(MQp_p)\). Aggregate repeated IDs
with their multiplicities. This implements the proposal's unbiased estimator
without confusing draw probability with without-replacement inclusion probability.
A later without-replacement policy must supply the correct inclusion estimator.

Freeze source IDs, objective weights, contrast thresholds, polarity priors,
robust-loss hyperparameters and prior definition during each trial block. Sample
validity and actual loss change with the trial transform. Refresh only between
accepted-step blocks or levels, then recompute current objective and curvature.
Loss histories carry sample-set IDs; do not compare raw values across objectives.
Use one fixed selection objective for candidates at a given checkpoint, and one
common fine-level selection objective for final candidate/model comparisons.

### 6.2 Stable normalization and exact loss

For each valid dynamic patch:

\[
h_j=f_j-f_0,\quad h_c=h-\bar h,\quad
L=\sqrt{h_c^Th_c},\quad v=h_c/L,\quad c=u^Tv.
\]

Use Double accumulation and a stable two-pass/Welford-equivalent contrast
calculation. A contrast energy at or below the frozen threshold or any sample
outside complete supported interpolation is an outlier patch. Malformed metadata,
nonfinite arithmetic, or materially impossible correlations are typed numerical
errors. Clamp only a declared floating-point roundoff excess over \([-1,1]\).

For \(0<\epsilon<1\), \(\tau>0\), \(0\leq\pi\leq1\):

\[
Z=\epsilon+(1-\epsilon)
[\pi e^{-(1-c)/\tau^2}+(1-\pi)e^{-(1+c)/\tau^2}],
\qquad\ell=-\tau^2\log Z.
\]

Evaluate in log space. Define the three posterior weights from these three terms:
\(\alpha_0,\alpha_+,\alpha_-\); \(w=\alpha_++\alpha_-\),
\(z=\alpha_+-\alpha_-\). Then

\[
\nabla_\theta\ell=-zJ^Tu,\qquad H_{\rm EM/GN}=wJ^TJ.
\]

This is an exact gradient away from support/contrast boundaries and a PSD
EM/Gauss–Newton curvature approximation, **not an exact Hessian**. Recompute the
original mixture loss at trial points; posterior weights are cached only for
the local model, not substituted for the trial loss.

Every invalid dynamic patch stays in the objective at
\(\ell_{out}=-\tau^2\log\epsilon\); derivative/curvature there are zero. The loss
is bounded and nonnegative. The core script permits \(\epsilon=0\); production
rejects it because finite invalid-patch cost and bounded rejection need a strict
positive floor. Illustrative script values (`pi=0.1`, `tau=0.55`, `epsilon=0.02`)
are development settings, not MRI-qualified defaults. Same-modality uses a strong
positive prior; EPI presets retain the mixture. Freeze final presets on training
subjects before any external release comparison.

The optional scalar-nuisance Schur helper in the supplied core script is a
**linearized profiling operation**, not a fully defined extra production loss.
Retain it as an oracle/experimental capability; do not enable an additional
nuisance direction by default or conflate it with the explicit PE deformation.
Any future use needs finite nuisance variance, a defined trial objective,
identifiability tests, and no invented marginalization log determinant.

### 6.3 Scalar oracle and streamed rigid/affine kernel

Maintain an explicitly centred scalar reference implementation in test/reference
sources. It forms \(C\), \(P=C-vv^T\), \(J=PG/L\) and dense products for small
fixtures. Production must agree with it; reference code is never a hidden
production fallback for performance admission.

With exact norm, \(P^2=P\). Accumulate only

\[
s_G=\sum G_j,\quad S_{GG}=\sum G_jG_j^T,\quad
s_{hG}=\sum h_jG_j,\quad s_{uG}=\sum u_jG_j.
\]

Then

\[
a=(s_{hG}-\bar h s_G)/L,\quad
J^TJ=(S_{GG}-s_Gs_G^T/m-aa^T)/L^2,\quad
J^Tu=(s_{uG}-ca)/L.
\]

Store just the upper triangle (21 entries for rigid, 78 for affine), vectors,
and scalar statistics in reusable buffers. No per-patch Jacobian, QR, SVD, or
projection matrix is allocated in the production loop. Use shifted/stable
moments, and a scale-aware symmetry/PSD check. A material cancellation discrepancy
triggers explicit centred recomputation of that patch and a diagnostic counter;
persistent disagreement fails the linearization. Do not clip substantial negative
eigenvalues to claim success. The slow diagnostic fallback cannot silently meet
an allocation/performance gate.

A separate loss-only kernel samples values without gradients. Both kernels call
the same normalization/gating/loss definitions and must produce matching losses,
including invalid patches. For affine geometry, compute warped shared offsets
once per trial/linearization and transform only each centre. With deduplicated
points, choose the cheaper exact traversal, and count both transform operations
and unique interpolations. Geometry and interpolation savings must not trade off
against unreported allocation.

### 6.4 Fused sampler and validity

Add a compiled D3 scalar value/gradient sampler under `reframe4s-resample` with
immutable image/geometry state, validated ranked access, and caller-owned output
buffers. Value and exact derivative of the trilinear interpolant share the same
eight voxel reads. Return existing image4s validity meaning and a gradient in
world coordinates; Flashalign requires complete support for every stencil sample.

Do not separately sample precomputed gradients in the default optimizer.
Do not introduce a new interpolation enum or competing boundary semantics.
Compile-time role tests reject labels/components where scalar intensity is
required. At interpolation knots document the cell derivative convention and
finite-difference-test away from knots. At borders, establish a one-sided cell
policy where mathematically valid; otherwise mark derivative support absent.
An intensity-only boundary value is not automatically a valid gradient sample.

The scalar pointwise sampler and analytic trilinear polynomials are independent
value/gradient oracles. Production sparse gathers use canonical Ravel/image4s
access. General-purpose provider deficiencies are addressed by their owner;
private compiled coefficients/scratch are allowed, a second image hierarchy is not.

## 7. Optimizer, damping, and deterministic work

Let \(b=-\nabla E\). Accumulate

\[
H=\sum_p a_pw_pJ_p^TJ_p+H_R,\qquad
b=\sum_p a_pz_pJ_p^Tu_p-\nabla R.
\]

Define a physical displacement metric on **fixed, transform-independent geometry
probe points**, not a changing subset of accepted patches:

\[
D=\frac1N\sum_i B_i^TB_i,\qquad
\|\Delta\|_D=\sqrt{\Delta^TD\Delta}.
\]

Gauges are removed before solving; numerical metric floors only ensure a usable
algorithmic norm and never count as data information. Use the same scaled
coordinates for damping, trust regions and conditioning reports. Check both RMS
and maximum actual probe displacement for nonlinear proposals.

```pseudo
samples = frozen_sample_set(level)
current = objective_and_linearization(state, samples)

for linearization in bounded_schedule:
    H, b, D = combine_data_prior_and_physical_metric(current)

    for attempt in bounded_trial_schedule:
        step = solve(H + damping*D, b)
        enforce trust and coefficient/geometry constraints on step
        candidate = geometry.propose(state, step)
        predicted = b' step - 0.5 * step' H step

        if invalid geometry or predicted <= 0:
            increase damping
            continue

        acceptance_limit = current.value - acceptance_ratio * predicted
        trial = loss_only_with_exact_early_rejection(candidate, acceptance_limit)

        if complete valid trial <= acceptance_limit:
            accept candidate
            update damping using actual/predicted reduction
            break
        increase damping

    if no accepted trial:
        return Stalled(last_valid_checkpoint, diagnostics)

    assess scheduled checkpoints on fixed selection samples
    test physical step + objective improvement + data information
    optionally refresh samples at a declared block boundary
    recompute current linearization
```

Predicted reduction excludes algorithmic damping and uses the **actual** clipped
or projected step. `propose` must retain the realized parameter increment (or
reject); a certificate/gauge projection cannot silently change the candidate
after its predicted reduction is computed. Rejected trials reuse values, gradients, robust posterior
weights and curvature. Only accepted states or sample-set changes invalidate
that cache. Small steps from extreme damping do not constitute convergence.

Use fixed-order early rejection: the prior plus evaluated nonnegative weighted
losses is a lower bound. Reject only when a conservatively rounded lower bound
exceeds the acceptance limit; account for accumulated summation roundoff near
the threshold. Never accept an incomplete candidate. The prior must be evaluated before applying the
bound; geometry constraints are checked even when a candidate might be rejected
quickly. Comparing candidates requires a complete common selection evaluation.

Use logical work blocks fixed independently of thread count, stable candidate
IDs, seeded RNG algorithm/version, deterministic tie breaks and a fixed reduction
tree. JVM workers own their scratch; Scala.js initially runs the same blocks
serially. Do not promise cross-architecture bitwise identity. Plans/results are
immutable; workspace size/capacity and active-use errors are explicit.

For implementation bring-up use bounded configuration, provisionally 50
linearizations/level, 8 trial attempts/linearization, at most 4 retained capture
candidates, and 4k optimization draws. Termination thresholds, maximum memory,
worker count and trust radii are validated and recorded; linear modality defaults
are frozen in FA-10L and nonlinear defaults in FA-10N, not inferred from this
engineering budget.

## 8. Capture and initialization

Start with a provided physical map or explicitly selected coherent-header
identity. Evaluate coarse overlap and information before refinement. If adequate,
skip expanded capture. Otherwise run a bounded structural search with a seeded,
versioned rotation schedule, beginning near the supplied pose and expanding in
stages. Keep distinct candidates under physical displacement separation, not
matrix-entry distance.

Use normalized-gradient orientation tensors as structural features, rotate both
locations and tensor components, and use **linear zero-padded** FFT correlation
for translations. Declare padding, lag-to-world conversion, overlap normalization,
support denominator and degeneracy rules. Retain only sufficient-overlap peaks,
then refine with the same projected-patch objective. Test translation sign,
rotation covariance, even/odd sizes and no circular-wrap false peaks against a
direct spatial correlation oracle. The established
[Cross-Sim-NGF method](https://arxiv.org/abs/2110.10156) motivates this capture
strategy; Flashalign does not claim to invent it.

A capability gate must admit the FFT implementation and its exact revision,
license, JVM/JS support and allocation/workspace behavior. Do not assume an FFT
exists in `gale-core`. If a separate spectral artifact is needed, add its edge
through the same architecture process. `InitializedOnly` may be released as an
explicit bounded developer capability while capture is pending; it is not the
complete automatic product. No silent coordinate-search or HalfFlow fallback.

All surviving candidates are compared on the same finest-level selection IDs,
weights, objective and model. Report competing near-equal alignments and their
physical disagreement. An exhausted schedule produces a typed search outcome.

## 9. Required nonlinear models

Both extensions use the same prepared evidence, mixture loss, trial acceptance,
QC, result direction and output path. Field coordinates are attached to the
moving world frame; the global pose acts after the field.

### 9.1 First basis choice and coefficient contract

Start with a small real trigonometric basis, permitted by the proposal's smooth
spectral option. This makes whole-domain derivative bounds directly computable
and avoids making the first nonlinear release depend on a spline topology
implementation. This is a recorded engineering choice, not a claim that Fourier
modes are always statistically preferable.

Define an origin, physical orthonormal axes and periods from a padded moving FOV.
Use sorted low-frequency integer wavevectors in those axes, omit the zero mode,
choose one representative of each ± pair, and add sine/cosine pairs in stable
frequency/lexicographic order. Record periods, padding, phase origin, basis
normalization and IDs. All functions have an explicit smooth extension to
\(\mathbb R^3\); only the declared image domain contributes data or the integral
prior. Coefficients are in mm after normalization. Do not reinterpret coefficient
arrays at a new grid or basis without a checked prolongation/transport.

Precompute mass, gradient, bending/elastic Gram operators over a fixed physical
domain, with normalized integration measure and stated units. Verify quadrature
against analytic integrals or converged independent quadrature. Magnitude
precision is strictly positive on the remaining free coefficients. A change of
basis transports the prior and metric by congruence, not by copying diagonal
weights. The simplest first implementation keeps one field basis across image
pyramid levels; finer modes are explicitly added, not implicitly introduced by
resampling images.

### 9.2 PE-restricted field

\[
T(x)=A[x+e\,d(x)]+t,\qquad d(x)=\sum_k c_k\phi_k(x),\quad A\in SO(3).
\]

The unit vector \(e\) is provided in the observed moving EPI world frame. A
higher-level adapter converts acquisition-axis/polarity metadata through the full
voxel-to-world affine. Missing or ambiguous PE direction prevents PE fitting;
it does not fall back to an arbitrary world axis. Scalar fields may vary in all
three spatial directions, but all displacement is along \(e\).

Start around 64 scalar coefficients, with a configurable 32–256 range.
Regularize

\[
R_d=\tfrac12\int[\lambda_0d^2+\lambda_1\|\nabla d\|^2
+\lambda_2\|\nabla^2d\|_F^2]dx,\qquad\lambda_0>0.
\]

Remove the constant/translation ambiguity by subtracting each basis function's
weighted mean on the fixed **source geometry** measure. This gauge is never
estimated from current inlier weights. Report both nominal coefficient count and
rank after constraints. With rigid pose, retain supported linear-like PE
variations; rigid cannot absorb general PE scaling/shear. If affine+PE is added
later, assign overlapping affine components once through an explicit gauge.

Derivatives and constraint:

\[
\partial_{c_k}T=Ae\phi_k(x),\qquad
\partial_{c_k}f=(g^TAe)\phi_k(x),\qquad
\det DU=1+e^T\nabla d(x).
\]

For the spectral basis use the sufficient global certificate

\[
\sum_k|c_k|\sup_x|\partial_e\phi_k(x)|\leq1-\delta,
\quad\delta>0.
\]

The directional derivative supremum for a sine/cosine is bounded by
\(|e\cdot k|\), adjusted for basis normalization; mean subtraction changes no
derivative. Store coefficient hash, bound, margin, domain/extension, arithmetic
allowance and implementation revision. Check every proposed state, not only
selected voxels. Configure a separate displacement-amplitude bound for plausible
search and inverse bracketing. Tighten the certificate with directed conservative
rounding/error allowances; sampled determinant plots are diagnostics only.

To invert, first form \(z=A^{-1}(y-t)\), then find \(s\) such that
\(s+d(z+es)=0\) and return \(x=z+es\). A bounded field supplies a bracket;
monotonicity supplies uniqueness. Use bracketed Newton/bisection with explicit
residual-mm and iteration limits and typed nonconvergence. Validate forward and
reverse domains separately, including points near support margins.

This is observed→undistorted-like displacement guided by anatomy. Do not export
Hz or infer recovered dropout. If reverse-PE fitting is later introduced, define
opposing forward distortions on a common undistorted domain, not negated inverse
fields at different observed coordinates. The acquisition-specific distinction
is established in [TOPUP's description](https://fsl.fmrib.ox.ac.uk/fsl/docs/diffusion/topup/index.html).

### 9.3 Small-strain anatomical field

\[
T(x)=A[x+u(x)]+t,\qquad u(x)=\sum_kc_k\psi_k(x).
\]

Begin with about 96 scalar coefficients multiplying vector modes (initial range
48–192), not 96 unconstrained 3-vectors. Use low-frequency scalar modes with
three vector polarizations, then remove overlap with the chosen global pose
through a fixed, rank-revealing geometry-only projection. Remove rigid components
for rigid pose or all affine components for affine pose. Record this gauge and
the transformed basis/derivative bounds; no current-image-dependent gauge.

Use

\[
R_u=\tfrac12\int[2\mu\|\operatorname{sym}\nabla u\|_F^2
+\lambda(\nabla\cdot u)^2+\eta\|\Delta u\|^2+\gamma\|u\|^2]dx.
\]

Use nonnegative strain/bending coefficients and \(\gamma>0\). These are chosen
geometric penalties, not measured mechanics. Allow supported local expansion and
contraction; strict incompressibility is not the default.

Require a configured \(\kappa<1\) and conservative certificate

\[
\sup_x\|\nabla u(x)\|_2\leq
\sum_k|c_k|\sup_x\|\nabla\psi_k(x)\|_2\leq\kappa.
\]

Derivative bounds must include the affine part removed during gauge projection.
The declared smooth extension must obey the same bound. This gives an injective
\(U=I+u\) with singular values in \([1-\kappa,1+\kappa]\); the final affine
composition has additional scale bounds from \(A\). Validate \(\det A>0\).

With a global contraction extension, invert by \(x_{n+1}=z-u(x_n)\),
\(z=A^{-1}(y-t)\). The a posteriori bound
\(\|x_n-x_*\|\leq\|x_n+u(x_n)-z\|/(1-\kappa)\)
sets a physical stopping rule. Near-one \(\kappa\) can make inversion slow;
record iteration counts and reject an exhausted inversion rather than claiming
an exact inverse. Separate amplitude and supported-domain checks still apply.

### 9.4 Model support and fallback

Condition data information on global pose, excluding priors/damping. Gauge out
exact redundancies before a Schur solve. With prior precision \(\Lambda\) on the
remaining modes, diagnose eigenvalues of
\(M=\Lambda^{-1/2}S_{data}\Lambda^{-1/2}\) and
\(d_{eff}=\operatorname{tr}[M(I+M)^{-1}]\).
Use a rank-aware solve/pseudoinverse for data-only pose conditioning, reporting
lost pose directions; the damped solve's conditioning is a different diagnostic.

This is local support information, not calibrated uncertainty. Admit flexibility
only when mode information, projected residual reduction, separate selection
improvement, regional refit stability and geometric constraints agree. A field
largely determined by its prior is reported as such. Failed nonlinear validation
returns the failure plus an explicitly authorized validated simpler checkpoint;
it cannot be labelled a successful nonlinear correction.

## 10. Matrix-free nonlinear solver

Keep a small private geometry protocol with state-specific `map`, `jvp`, `vjp`,
`propose`, `priorTerms`, `certify` and `inverseMap`. Parameter-space size, gauge,
basis identity and frame endpoints belong to the compiled model. Its point
operations use primitive buffers/workspaces internally and checked typed maps at
public boundaries. Adapt existing registration protocols where their semantics
match; do not force an EM curvature model into a fictitious exact least-squares
residual API.

At each accepted linearization cache values, world gradients and transformed
positions once per **unique** moving sample, and each valid patch's \(v,L,c,w,z\).
Each field JVP computes displacements; intensity JVP is the gradient dot product.
The curvature product is exactly the product of the chosen GN matrix:

\[
H_{data}h=\sum_p a_pw_pG_p^TP_pG_ph/L_p^2.
\]

```pseudo
dy = geometry.jvp(state, unique_points, direction)
df = row_dot(cached_world_gradients, dy)
zero scalar_forces
for valid patch:
    q = gather(df, patch.indices)
    q -= mean(q)
    q -= patch.v * dot(patch.v, q)
    scatter_add(scalar_forces, patch.indices,
                patch.objective_weight * patch.w * q / patch.L²)
return geometry.vjp(state, unique_points,
                    cached_world_gradients * scalar_forces)
```

The RHS scatters \(a_pz_p(u_p-c_pv_p)/L_p\) through the same gradient and
geometry transpose. Invalid patches contribute constant objective cost but zero
local force. Repeated indices accumulate, never overwrite. Gradients are cached
image evidence: a Krylov product performs **zero new image interpolations**.

Use Gale's qualified workspace PCG for the full SPD damped system. Check actual
unpreconditioned relative residual, iteration limit, nonfinite values and
nonpositive curvature. An inexact solution is only a candidate direction, with
its residual/status reported and full trust acceptance still required; never
mark an uncompleted solver as converged. Internal basis/value caching is chosen
by a measured memory budget: O(unique-points × coefficients) caches are not free.
Default to compact/tiled basis operations, O(unique points + patch samples)
image evidence, and O(K) Krylov buffers. Dense K×K normals are reference-only for
small qualification fixtures, not the production large-model path.

Partition the **full damped system**, including prior and all displacement-metric
cross blocks, into global pose and field coefficients. Factor its 6×6 or 12×12
pose block and solve the Schur system

\[
(H_{cc}-H_{ca}H_{aa}^{-1}H_{ac})\delta c
=b_c-H_{ca}H_{aa}^{-1}b_a,
\quad\delta a=H_{aa}^{-1}(b_a-H_{ac}\delta c).
\]

Never form an inverse. Factor once per damping attempt; no image relinearization
is needed when damping changes. Retain the undamped H operator separately for
predicted objective reduction. Operator symmetry/adjoint tests must include
Schur cross blocks, gauges and priors. Schur elimination improves conditioning;
it does not fix an unidentifiable model.

Precondition with the structured deformation prior plus data-diagonal/block
information. On an unmodified periodic constant-coefficient Fourier basis,

\[
\widehat L(k)=a(k)I+(\lambda+\mu)kk^T,
\quad a(k)=\gamma+\mu\|k\|^2+\eta\|k\|^4,
\]

so divide perpendicular components by \(a(k)\) and parallel components by
\(a(k)+(\lambda+\mu)\|k\|^2\). Gauge projection, nonperiodic integration domains,
variable stiffness or boundary changes can destroy exact diagonalization: then
this is a preconditioner, not the true prior inverse. Prior value/gradient/Hessian
must still agree on the actual defined regularizer.

Record products/accepted step, solver residuals, preconditioner setup, basis
operations, pose-block factorizations and every fallback. Approximate local
patch-motion compression (12 local affine coefficients; 4 for scalar PE) is
allowed first for screening/preconditioning. Exact trial transforms and exact
operator derivatives remain the required fit path. Any later approximation must
bound the Taylor remainder by curvature × patch-radius²/2 relative to physical
accuracy and explicitly fall back to exact basis evaluation.

### 10.1 Later extension contracts retained from the proposal

Adaptive refinement is conditional: summarize coherent residual and conditional
information by region, propose compatible local spline functions only in supported
regions, transport coefficients/priors/constraints, refit, and retain only separate
selection improvements with regional stability. Each sample's compact spline
support is insufficient by itself: normalized residuals couple the **union of
control points touched by every sample in the patch**. Scatter through that union.
Start with structured smoothness/block preconditioning; add multigrid only after
measuring its need. Conservative cellwise derivative bounds must survive basis
refinement, including transitions to unrefined regions.

For the deferred velocity model \(T=A\circ\operatorname{Exp}(v_c)\), with
\(\dot z=v_c(z)\), the directional sensitivity satisfies

\[
\dot\eta=Dv_c(z)\eta+v_h(z),\qquad\eta(0)=0.
\]

The transport term cannot be omitted or replaced by displacement derivatives.
Integrate only at unique sample points, cache integration stages, and differentiate
the actual discrete integrator with matching tangent and adjoint operations.
Qualify step size, inverse consistency, sampled/digital topology and error on
output grids independently: a continuum diffeomorphism claim does not certify
an interpolated numerical image map. No dense velocity-to-image Jacobian is
allowed. Before adding this backend, show with retained cases that the bounded
small-strain model limits useful registrations under the same external metrics.

## 11. Output, composition, and host workflow

Export `movingToFixed` and a usable `fixedToMoving` with source/destination frame
records, grid provenance, millimetre convention, model/version, affine component,
field basis/coefficients/gauge, domain/extension and inverse criteria/results.
Retain acquisition PE direction separately from a matrix. Serialization stores
persistent identities and restores through image4s registries/alignment evidence;
it never manufactures live owner equality from names or matching shapes.

For corrected output, compose all maps and call existing `ResamplingPlan.affine`
or `ResamplingPlan.mapped` with the original moving image and final fixed grid.
Use selected cubic/Lanczos5 output interpolation and existing boundary/validity
semantics, interpolating the original image once. Default anatomical/rigid output
is intensity pullback; no unrequested Jacobian intensity modulation. Any PE
modulation workflow needs separate physical modeling and validation.

`mapped` already compiles one source continuous index per target voxel. In D3 its
three Double coordinate arrays alone cost approximately 24 × target-voxel-count
bytes, in addition to image, output and validity buffers. This is output-stage
memory, not sparse optimization memory. Preflight the peak; return an explicit
budget error if necessary. An additive tiled mapped execution path may be
required to meet the declared target sizes, but must preserve existing map,
interpolation and boundary semantics. Do not copy a whole-grid sampler into
Flashalign or weaken the no-coordinate-materialization rule for affine plans.
A nonlinear inverse failure cannot be hidden as ordinary outside-image padding.

QC includes valid-overlap fraction on a fixed denominator, robust inlier weights
(not calibrated probabilities), spatial inlier coverage, data-only scaled
condition/rank, parameter gauge rank, candidate disagreement, leave-region-out
refit displacement, effective model dimension, certificate margin, inverse
residual/coverage and iteration counts. Return nonconverged, ambiguous and
insufficient-information outcomes independently of a finite image-similarity
score. Report timing for read/decompress, prepare, capture, linearize, solve,
trial evaluation, selection/audit and output, with cold/warm distinctions.

The repository README assigns command programs, BIDS metadata and neuroimaging
workflow/report policy to ScalaFIM. Keep those there. The intended `flashalign`
command is a thin separately owned adapter using this API and image4s NIfTI I/O:

```text
flashalign --moving epi.nii.gz --fixed T1w.nii.gz --mode epi-t1 \
  --model rigid --out-matrix epi_to_t1.txt

flashalign --moving epi.nii.gz --fixed T1w.nii.gz --mode epi-t1 \
  --model pe-field --pe-axis j- --out-transform epi_to_t1.json
```

These are target interfaces, not installed commands. A nonlinear transform must
not be exported as if its affine component were the complete matrix. A matrix
request for a nonlinear result returns an explicit unsupported-export error;
a separate named affine-component export is permitted. Basic transform records
belong to the library; file writes, existing-file policy and QC presentation
belong to the adapter. An unpublished JVM runner may exercise NIfTI end-to-end
in this repo's benchmark area without adding filesystem APIs to shared code.

## 12. Implementation work packages and integration gates

IDs below are PRD work-package IDs, not claims that implementation issues are
already created. The planning record is Mote
`bd-01M2AX78EXVX73WQQPASZWXJKR`; the complementary-method revision is
`bd-01M2AYFMXC09887M5YBBQKAM95`. Turn these packages into tracked work with exact
path reservations before implementation; do not reopen or take over existing
HalfFlow/provider issues. Coordinate shared-provider changes with their current
owner and preserve exact consumer→dependency order.

| ID | Depends on | Deliverable and acceptance | Primary paths |
|---|---|---|---|
| **FA-00** | Existing owner decisions | Reconcile baseline graph/CI/toolchain disposition; record exact clean candidate and provider closure; preserve existing changes | `PRD.json`, graph/build/CI only in a separate baseline slice |
| **FA-01** | FA-00 | New experimental cross-project, typed compile/run/result/error skeleton, fixture/benchmark manifest, symbol ownership; JVM+JS compile and API negative probes | New module, graph/build declarations, ownership checks |
| **FA-02** | FA-01 | Independent explicit patch oracle; stable normalization, mixture gradients/weights, invalid cost and streamed statistics; algebra and cancellation gates | New module tests and objective sources |
| **FA-03** | FA-00 | Additive compiled fused sampler with same interpolant derivative; support-aware multiscale/PSF policy; independent sampling and header gates | `resample`, `multiscale`, their focused suites |
| **FA-04** | FA-02, FA-03 | Rigid engine, sample freezing, unique points, loss-only/early rejection, trust/checkpoint/report semantics; initialized same-subject recovery | New module rigid/preparation/optimizer sources |
| **FA-05** | FA-04 | Affine retraction/priors, exact strain basis and gauge checks; 12-parameter recovery with prior-gradient and prediction tests | New module affine model; additive Lie helper if needed |
| **FA-06** | FA-04, qualified FFT provider | Bounded structural capture, direct-correlation oracle, common candidate comparison, large-init and ambiguity outcomes on both platforms | New module capture; separately admitted provider edge |
| **FA-07** | FA-05 | General point JVP/VJP, cached matrix-free GN, Gale workspace PCG adapter, full-system Schur/preconditioner and diagnostics; explicit dense parity | New module operator/solver/basis sources |
| **FA-08** | FA-07 | About 64-coefficient rigid+PE fit, gauge, exact directional derivatives, global monotonicity/amplitude bound, bracketed inverse, identity-limit and recovery fixtures | New module PE model and tests |
| **FA-09** | FA-07 | About 96-mode small-strain anatomy fit, fixed pose gauge, elastic prior, global gradient bound, contraction inverse, independently generated warps | New module anatomy model and tests |
| **FA-10L** | FA-05, FA-06 | Linear selection/audit, regional QC, training-only preset calibration and failure examples; freeze independent linear settings | New module validation; benchmark manifests/config |
| **FA-11L** | FA-10L | Complete rigid/affine result/export/resampling, JVM+JS/fullOpt, accuracy and phase-cost gates; prove absence of nonlinear runtime work; independently releasable linear product | New module results; benchmark runner; focused docs |
| **FA-10N** | FA-08, FA-09, FA-10L | Nonlinear model selection, field support/region stability, training-only nonlinear calibration; same audit policy | New module validation; nonlinear manifests/config |
| **FA-11N** | FA-10N, FA-11L | Complete nonlinear inverse/result/export/resampling, geometry and cost gates; preserve released linear behavior/performance | New module results; nonlinear benchmark runner |
| **FA-12** | FA-11N, demonstrated coarse-model limitation | Optional local spline refinement with patch-union support, transport and whole-cell derivative certificates; selection improves at matched geometry constraints | Separate extension slice |
| **FA-13** | FA-11N, demonstrated bounded-model limitation | Deferred velocity field only if composition with the existing nonlinear line is inadequate; discrete tangent/adjoint, inverse/topology/memory evidence | Separately scoped flow/provider and module changes |
| **FA-14A** | FA-11L, pinned runnable HalfFlow benchmark baseline | Standalone method baselines; typed supplied-affine handoff; compare HalfFlow's existing initializer versus Flashalign initialization while freezing its nonlinear controls | Unpublished cross-method runner; additive HalfFlow-owned input adapter if needed |
| **FA-14B** | FA-11N, FA-14A | Same-initialization nonlinear comparison and measured selective composition/transfer experiments; retain failures and stage costs; adopt only demonstrated benefits | Cross-method runner; individually qualified shared primitives/adapters |

Linear release gate: **FA-00–06, FA-10L and FA-11L**. It has no dependency on
FA-07–09, nonlinear validation, or the comparison/composition experiments.
Nonlinear release gate: **the linear release plus FA-07–09, FA-10N and FA-11N**.
FA-14A/B are required comparative-development deliverables with independent
readiness; the standalone linear release remains independent of the other
method's experimental state. FA-12/13 remain conditional. Shared-code improvements
can land as their own qualified slices throughout this sequence.

A work package is done when its declared evidence is complete, not when code
compiles or its iteration budget expires. Every implementation slice carries its
own existing-test regression results and updates symbol ownership/API evidence.
Do not bundle provider migration, HalfFlow retuning, motion optimization changes,
and Flashalign algorithm work in one commit. Shared-path patches are reviewed
and qualified before dependent work lands. New default behavior in motion,
HalfFlow, graph routing, interpolation or the umbrella requires its own explicit
scope and comparison. This PRD authorizes planning the explicit comparisons and
shared-code adoption in §3.1; it does not silently change another method's default.

## 13. Acceptance matrix: correctness, accuracy, and cost

Tests are selected to falsify the method's contracts, not mirror its source.
Keep fast tests in MUnit/ScalaCheck shared suites, allocation/performance probes
in JVM-specific tests/JMH, and larger data experiments in a separate manifest-
driven runner. All failure fixtures retain expected typed failure semantics.

| Gate | Independent evidence and pass condition |
|---|---|
| **G-geometry** | Analytic world-coordinate landmarks/phantoms under identity, +10 mm translation, rigid/affine truth, anisotropy, oblique/sheared affines, axis permutation/reflection/LPS-RAS rebasing, origins, composition and restored owners. Equivalent physical representations yield equivalent maps. Bad owner/direction/rank calls fail in compile-negative or boundary tests |
| **G-interpolation** | Analytic multilinear polynomials and scalar pointwise oracle; fused values match existing value semantics, world gradients match finite differences away from knots. Include cropped/strided arrays, boundary cells, invalid taps and large offsets. No separately interpolated gradient masquerades as exact |
| **G-patch** | Explicit C/P/J versus streamed products and matrix-free action for 6, 12, 64, 96 and >128 coefficients; signed-mixture exact gradient finite differences; positive gain/offset invariance; polarity behavior; norm threshold, partial support and invalid cost. Hard-norm idempotence passes; deliberately wrong soft-norm formula demonstrably fails |
| **G-objective** | Enumerate a small population to verify weighting and repeated-draw multiplicities. Full trial loss equals loss-only; early rejection never rejects a truly acceptable complete trial. Rejection reuses the same gradients. Refresh relinearizes; frozen hyperparameters remain frozen while trial posteriors/loss change |
| **G-optimizer** | Quadratic problems with known priors and nonzero prior gradients; actual-step prediction after clipping/gauge; damping excluded from prediction; stalled versus converged separation; inexact/broken PCG and tiny-information cases remain explicit |
| **G-operators** | Dot-product adjoint identity for geometry, gathers/scatters and complete H; dense GN action/solve versus operator PCG/Schur using independently assembled matrices. Include duplicate samples, non-diagonal damping, pose-field cross blocks, transformed priors and exact gauge nullspaces |
| **G-PE** | Independent scalar fields/landmarks, oblique PE axis, sign/direction and coefficient finite differences, determinant lemma, constant gauge, near-fold adversaries, global bound versus dense spot checks, amplitude brackets and inverse residuals. Certifier rejects an unsafe-between-samples example; voxel-only checks cannot substitute |
| **G-anatomy** | Independent analytic vector warps, gauge projection, affine composition, elastic-gradient/curvature consistency, derivative certificate including gauge correction, contraction residual bound and out-of-domain extension. Verify singular-value bounds independently and fail closed near the configured limit |
| **G-failure-tail** | Slabs, partial overlap, missing anatomy, dropout, homogeneous/low-contrast volumes, repetitive structures, competing mirror-like matches, large initialization error and prior-dominated fields. Failures stay in denominator and output status; no silent identity success |
| **G-output** | Compose forward/pull maps with independent asymmetric-grid truth; original image sampled once; nonlinear roundtrip residual/domain failures remain explicit; frame/grid/basis restore compatibility; serialized nonlinear output cannot be treated as an affine matrix |
| **G-determinism** | Same seed/policy produces identical IDs, trial decisions and fixed-order reductions on one backend; JVM versus JS numerical tolerance agreement; serial versus parallel block semantics; simultaneous immutable-plan runs with separate workspaces; typed shared-workspace rejection |
| **G-integration** | Existing map/Lie/resampling/registration/multiscale/motion/HalfFlow suites pass on affected platforms; symbol and graph checks pass; default bundle/POM exclusion and exact provider closure; no changed default capture/loss/termination path in existing estimators |
| **G-cross-method** | C0–C5 baseline/initialization comparisons and qualified C6/C7 experiments; typed handoff direction, midpoint/complete-versus-residual composition, preserved recipient controls and certificates; full stage costs and independent geometry. Failed or unavailable lanes remain explicit |
| **G-cost** | Phase-local allocations, peak retained memory, unique interpolations, gradient calls, geometry/basis operations, linearizations, rejected trials, early rejects, PCG products and output coordinate cost. Linear runs allocate no nonlinear state; adding extensions preserves the qualified standalone linear path. No per-sample/patch object allocation in qualified hot loops; same-environment unchanged resampling/transform median throughput regression ≤10% under existing AC-050 |

Initial tolerance policy: combine absolute and relative tolerances scaled to dtype,
conditioning and physical units. For well-conditioned Double oracle fixtures,
start at `1e-10` absolute + `1e-9` relative for algebra/adjoints and `1e-6` relative
for directional finite differences over a step-size sweep. State exceptions for
near-zero derivatives explicitly. Use ill-conditioned cases to demand a typed
failure or diagnostic fallback rather than loosen every test. Distinguish
algorithmic truncation, floating-point rounding and interpolation approximation.

Initial noise-free end-to-end core fixtures require ≤0.1 mm landmark RMS and
≤0.1 degree rigid error where truth is identifiable at the declared sampling
resolution. Nonlinear representable fixtures require ≤0.1 mm landmark RMS and
≤0.01 mm inverse roundtrip residual on the certified scope. These are engineering
fixture gates, not clinical/anatomical claims; freeze final thresholds and
fixture hashes before implementation tuning. Include out-of-basis synthetic
truth and noise as separate approximation/stability distributions rather than
forcing an impossible exact recovery gate. Unit algebra tolerances are not
end-to-end image registration tolerances.

### External comparison protocol

Create `benchmarks/flashalign/` with an unpublished JVM runner, raw result schema,
input/config/transform hashes, exact comparator command/version, initialized
transform, immutable sample IDs, CPU/OS/JDK/threads, failure status and phase work
counts. Reuse the *discipline* of the motion evidence runner, not its time-series
estimand or release claims. Do not modify its existing receipts or comparator
court to admit a different task.

Use subject-disjoint train/selection/test cohorts, explicit mask/FOV policies,
licensed data provenance and matched output interpolation. Freeze datasets,
initialization distributions, metric thresholds and hyperparameters before the
sealed evaluation. At minimum distinguish ordinary same-subject, large-init,
partial slab and dropout/low-signal cohorts, and report per-cohort as well as
pooled results.

Measure landmark/known-transform target-registration error and independent
anatomical boundary/segmentation agreement where valid; the optimized patch score
is secondary. Synthetic images must be generated from independent continuous
phantoms and different sampling grids, not solely by the candidate interpolator
with the same exact basis. Analyze repeated initializations clustered by subject;
report uncertainty for median, p95 and catastrophic-failure differences. Keep
crashes, nonconvergence and ambiguity in the original denominator. A superiority
claim requires the predeclared confidence criterion, not a favorable point estimate.

Compare rigid with rigid and affine with affine against 3dAllineate under
matched inputs, compute limits and initialization. Compare PE fields with
methods allowed equivalent acquisition information and distortion freedom;
reverse-PE/fieldmap-assisted runs form a separate stratum. Compare anatomical
fields with an appropriate matched nonlinear class, including a zero-field
ablation and the rigid/affine base. HalfFlow/BasinBridge is a required complementary benchmark entry under the
controlled comparisons below. Its baseline controls remain frozen within each
comparison. Record negative
results; geometric accuracy, invertibility and regularization are distinct claims.

### Complementary-method comparison and transfer experiments

Build one comparison harness with explicit method adapters and separate method
configurations. Share immutable inputs, physical transform interpretation,
independent scoring and receipt schemas; do not force identical internal
similarity scores, sample sets or regularizers. HalfFlow is an experimental
comparator: the [capability ledger](../halfflow-capability-ledger.md) still governs
its admission. Development comparisons can retain diagnostic outputs, but an
unadmitted/failed export never becomes an admitted result through this harness.

| Lane | Initialization and method | Question answered |
|---|---|---|
| **C0** | Common input affine / no nonlinear refinement | What does the initial geometry explain? |
| **C1** | Flashalign rigid/affine standalone | Does the primary fast-linear product meet accuracy, failure-tail and latency targets? |
| **C2** | Common admitted affine → Flashalign small-strain | What accuracy/cost does the economical nonlinear extension provide? |
| **C3** | The same affine → fixed HalfFlow/BasinBridge configuration | What additional anatomical accuracy and cost does the richer nonlinear method provide? |
| **C4** | HalfFlow's existing full pipeline with its existing initializer | What is the receiving method's unchanged end-to-end baseline? |
| **C5** | Flashalign affine → the same HalfFlow nonlinear configuration | Does changing only initialization improve the recipient and total cost relative to C4? |
| **C6** | Common affine → Flashalign small-strain → qualified HalfFlow residual refinement | Does an additional bounded stage help beyond C2/C3 and a direct affine-initialized HalfFlow run? |
| **C7** | Supported reverse transfer or shared-primitive adoption | Does the recipient improve relative to its own frozen baseline when exactly that factor changes? |

C2/C3 use the exact same initial transform and data/support policy, which can be
a frozen Flashalign affine result or an independently supplied affine. Report the
initialization cost separately and in total. C4/C5 change only the initializer;
C6 changes only the extra pre-refinement stage relative to a direct run from the
same affine. Retain matched direct-run controls if a cohort or initialization
differs. Use the same held-out subjects and multiple initialization errors, with
predeclared development settings and no tuning after sealed evaluation.

Run C2/C3/C6 on anatomical tasks both models claim to address. PE-specific tests
remain a separate acquisition-information stratum; a free anatomical warp and a
PE-restricted inverse field are not equivalent models. Compare both quality under
matched end-to-end budgets and cost to reach a predeclared quality threshold,
reporting limits when a method cannot reach it. Include ordinary same-subject
alignment and modest deformations as well as larger anatomical differences so
the harness represents both products' intended operating ranges.

Plot accuracy-versus-wall-time and accuracy-versus-memory tradeoffs rather than
collapsing them to one winner score. Record stage landmark error, regional
stability, overlap, rejection/export status, inverse/topology evidence, minimum
Jacobian/strain, iterations and all preparation/interpolation/solve costs. Report
failure tails and paired subject-level uncertainty. Cross-score method losses
only as secondary diagnostics; neither objective is independent ground truth
for the other, and disagreement alone does not identify the correct method.

Adopt a transfer or shared optimization when it provides a predeclared useful
accuracy, failure-tail, compute or maintenance benefit without violating the
recipient's constraints. Retain the standalone ablation, and reject experiments
whose extra stage mainly adds cost or whose apparent gain comes from a changed
mask, score, model freedom or acceptance threshold. Comparisons may reveal
complementary operating ranges rather than a universal winner. Benchmark-driven
fixture reuse must not leak final audit/test cases into model tuning.

### Performance and release evidence

Use cold and warm measurements with complete work counters and median/p95
latency. Qualification must expose preprocessing/materialization, capture,
linearization, each solve/trial, validation, and output memory. Increasing the
parameter count must not conceal O(U×K) basis tables or K² normals. Measure
matrix–vector products per accepted step and certify zero image-gradient calls
inside those products. Verify interpolation deduplication by counts and numerical
identity, not wall time alone.

Run the same scalar contracts on JVM and Scala.js and optimized Node execution.
Parallel/SIMD kernels are enabled only after they agree with the scalar engine at
declared tolerances and improve measured workloads. A performance regression
must be recorded and resolved or explicitly scoped; speed gains do not waive
failure-tail gates. No dependency version, benchmark number or passing count in
this PRD constitutes future qualification evidence.

## 14. Verification commands and release hygiene

Existing structural gates:

```sh
node scripts/verify-prd.mjs
node scripts/verify-build-graph.mjs
IMAGE4S_ROOT=/exact/qualified/image4s LOCUS4S_ROOT=/exact/qualified/locus4s \
  node scripts/verify-symbol-ownership.mjs
```

Planned feature and affected-consumer gates after the module is introduced:

```sh
sbt -J-Xmx4G -batch 'reframe4s-flashalignJVM/test' 'reframe4s-flashalignJS/test'
sbt -J-Xmx4G -batch compileAll testAll
sbt -J-Xmx4G -batch \
  'set reframe4s-flashalignJS / scalaJSStage := FullOptStage' \
  'reframe4s-flashalignJS/test'
sbt -J-Xmx4G -batch 'reframe4s-flashalignJVM/makePom' \
  'reframe4s-flashalignJS/makePom' 'reframe4sJVM/makePom' 'reframe4sJS/makePom'
```

Verify actual generated sbt project IDs in FA-01 before freezing runnable CI
commands. Add JMH outside the published aggregate following `halfflowBenchJVM`;
fix a small set of deterministic microbenchmarks and full pair workloads. Pin
consumer/provider revisions, inspect generated POMs, and run an external consumer
against exact artifacts before publication. Explicit local overrides can support
development, but must be labelled and requalified under immutable defaults.

Preserve Scala 3 strict warnings, JVM/Scala.js source split, MUnit/ScalaCheck law
style, typed errors, immutable public data and explicit workspace ownership.
No broad warning exceptions, new general utility hierarchy or dependency changes
solely for convenience. Keep shared code free of Java filesystem/thread/native
storage types; put bounded worker execution in `jvm/`. Add public symbols to the
existing ownership inventory. Update concise README/API examples and
implementation/evidence docs only to describe shipped capabilities.

When implementing, reserve exact paths through Mote and record dependent work
and evidence per package. Recheck dirty-tree ownership before every shared-path
edit; do not stage unrelated modifications or generated artifacts. Publication,
consumer API migration and any change to default package composition are explicit
later deliverables, not side effects of adding the new module.

## 15. Decisions that resolve the source proposal

| Source idea or gap | Implementation disposition |
|---|---|
| Raw `Volume`/`Mat4` pseudocode | Canonical image4s `Sampled`/`Affine` and reframe4s maps; private prepared caches only |
| Part 1 softened norm and one-pass variance | Exact hard-gated norm and stable moments; Part 2 repair governs |
| Float32 stored normalized moving patches | Double first; optional quantized storage needs explicit recentering/error qualification |
| “Low-rank” patch selection/Woodbury | Start stratified, correctly weighted draws; affine patch information may be full rank |
| Optimization/candidate loss differences | One mixture loss and invalid cost, three frozen evaluation roles |
| Prior curvature without gradient | Full prior value/gradient/curvature, separate damping |
| General affine `exp(delta)` | Checked local multiplicative affine retraction with exact local Jacobian; SE(3) for rigid |
| Nonlinear freedom | Required PE and small-strain bounded models; optional adaptive/velocity stages |
| Cubic spline or spectral basis | Smooth spectral basis first, with explicit gauge/global bounds; splines later |
| Hundreds of parameters | Cached unique-point image evidence and geometry operators; Gale PCG and full-system Schur; zero nonlinear setup on linear fits |
| Nonlinear inverse/topology | Numerical inverse with residual/domain evidence; no `SmoothIso` promotion |
| Nonlinear output | Existing `ResamplingPlan.mapped`, with measured coordinate-memory cost |
| CLI and metadata | Thin ScalaFIM-owned adapter, canonical image4s I/O; no reverse dependency |
| Existing motion/HalfFlow | Distinct method objectives/defaults; shared primitives and explicit cross-method comparison/composition with measured benefits |
| Supplied numerical scripts | Algebra oracles and regression seeds; not MRI/program/runtime evidence |
| Primary product and release order | Fast linear product has independent gates; nonlinear extensions and cross-method experiments do not delay it |

## 16. Evidence and prior work

The two supplied scripts were inspected and executed for this PRD under the
Python/NumPy versions recorded in the
[evidence receipt](flashalign/implementation-evidence-2026-09-12.json).
Both passed their own assertions. The core sign-mixture gradient finite-difference
relative error was about `7.57e-10`, and the normalized patch Jacobian error about
`8.22e-11`. The extension script passed streamed/explicit curvature, matrix-free,
PE Jacobian and Schur identities. Original scripts and supplied JSON remain
unchanged; these checks establish only their finite numerical examples.

Source artifacts:

- [Two-part proposal](flashalign-prd.md), including its original unresolved source markers.
- [Nuisance-projected numerical core](flashalign/nuisance_projected_registration_core.py).
- [Extension algebra checks](flashalign/flashalign_extension_checks.py).
- [Original supplied extension results](flashalign/flashalign_extension_check_results.json).

Relevant primary sources provide precedent, not Flashalign accuracy evidence:

- [Cross-Sim-NGF](https://arxiv.org/abs/2110.10156) motivates FFT structural rigid capture.
- [TOPUP](https://fsl.fmrib.ox.ac.uk/fsl/docs/diffusion/topup/index.html) describes acquisition-direction distortion and paired-PE estimation.
- [FNIRT's technical report](https://www.fmrib.ox.ac.uk/datasets/techrep/tr07ja2/tr07ja2.pdf) and [user guide](https://fsl.fmrib.ox.ac.uk/fsl/docs/registration/fnirt/user_guide.html) document spline fields and efficient GN/LM registration.
- [Zhang and Fletcher, finite-dimensional Lie algebras](https://publications.sci.utah.edu/publications/Zha2015a/Zhang_IPMI2015.pdf) provide prior work on band-limited velocity representations; the deferred Flashalign stationary-flow implementation still needs its own discrete derivative and topology evidence.

The current planning evidence includes a passing architecture-PRD validator and
an explicitly failing baseline build-graph validator. No Scala implementation,
full Scala build, external MRI comparison, production runtime claim, or remote
release qualification was performed by writing this specification.
