# Flashalign and HalfFlow shared-primitive audit

This note closes the time-boxed FA-C08 audit. It compares concrete Flashalign
and HalfFlow call sites against the implementation PRD at SHA-256
`a66500fec106a0df0f6635bb81329f44cca0a031cab32958c6c370f2ca6fc929`.
The decision is to make no additional extraction. Five candidate families were
examined, none had identical cross-method semantics, and the three useful
provider boundaries are already shared at their current owners.

## Decision matrix

| Candidate | Flashalign contract | HalfFlow contract | Decision |
| --- | --- | --- | --- |
| Value and gradient sampling | `LinearValueGradientSampler3` consumes sparse world coordinates, rejects any derivative cell without complete eight-tap support, computes the analytic trilinear derivative in the same pass, maps it through the complete inverse grid affine, and writes caller-owned sparse buffers. `UniquePointRegistry3` deduplicates exact bit-identical physical stencil points before sampling. | HalfFlow pulls dense scalar volumes through absolute source-coordinate fields and explicit validity masks. HalfFlow-CC then computes masked physical finite-difference gradients on the already warped dense lattice. BasinBridge has a separate voxel-coordinate block sampler whose edge behavior clamps the upper tap and whose support is the intersection of valid scalar taps. | Keep separate. Reusing the Flashalign primitive would move the derivative before the warp and replace HalfFlow's mask-aware finite differences. Reusing the HalfFlow kernels would discard Flashalign's exact fused derivative and complete-support rule. |
| Physical pyramid | `SupportAwarePyramid3` is already owned by `reframe4s-multiscale`. Every level is built directly from native data. It derives added blur from target PSF variance minus the affine-derived voxel-cell PSF, evaluates an isotropic world Gaussian through the full native affine, zero-pads weighted values and fractional support, resamples both on the canonical level grid, and normalizes above a declared support threshold. | `HalfFlowKernels.buildPyramidLevelInto` implements HalfFlow's frozen shrink and sigma schedule on `GridSpec`. It applies separable axis kernels using sigma in millimetres divided by axis spacing, filters values against binary method validity, samples numerator and denominator with partial linear support, and uses its own minimum-weight rule. | Keep separate. Target-PSF subtraction, affine handling, fractional support, boundary normalization, default thresholds, data representation, and provenance differ. Migrating HalfFlow would change a frozen recipient control rather than remove neutral duplication. |
| Affine application and direction conversion | Flashalign publishes typed canonical `Rigid3` or `FramedAffine` moving-to-fixed maps and constructs the inverse once for pull resampling. | `SuppliedAffineInitialization.fromMovingToFixed` accepts the canonical map, takes its exact inverse once, copies it into HalfFlow's internal matrix, and delegates to the existing fixed-to-moving factorization. | Already shared at the correct boundary. A new registration-common affine wrapper would duplicate canonical `AffineMap`/`FramedAffine` ownership and obscure the one required inversion. |
| Gather, scatter, and neighborhood indexing | `UniquePointRegistry3` is an immutable index from selected patch entries and one physical stencil to exact shared world points. Gather/scatter preserves draw multiplicity and caches ownership for affine traversal choices. | Neighborhood CC uses dense overlapping box sums and adjoint scatter on one work grid. BasinBridge allocates per-anchor reference/source blocks and enumerates distinct integer search offsets in forward and reverse passes. | Keep method-private. The keys, multiplicity, support, traversal, and cache lifetimes are different; HalfFlow has no exact-point registry consumer to migrate. |
| Evidence and orchestration | Flashalign method rows use the generic pair-row model; automatic linear failures retain method-specific capture, pyramid, checkpoint, and work evidence. | HalfFlow rows retain its own objective, topology, inverse, and stage diagnostics. | The unpublished `CrossMethodEvidenceRunner` is already the higher-level consumer that depends on both methods. It shares input loading, lane accounting, map composition, independent scoring, and complete failure denominators while keeping method-specific evidence distinct. No library extraction is needed. |

## Support, scale, and cache identities

The superficially similar loops do not have interchangeable cache keys.
Flashalign's fused sampler is compiled for one typed continuous image and its
complete grid affine; its sparse output belongs to a caller-owned capacity.
The unique-point registry belongs to one immutable patch sample set and one
physical stencil. The support-aware pyramid workspace belongs to one active
build, while every output level records source PSF, target PSF, support policy,
kernel taps, and materializations.

HalfFlow's dense sampler is keyed by `GridSpec`; volume-sized warp, gradient,
Gaussian, neighborhood, and reduction buffers belong to the work grid and
method plan. BasinBridge block buffers belong to one anchor search and use its
voxel window, candidate window, validity fraction, variance floor, score
temperature, and forward/reverse direction. Treating these caches as one
primitive would either weaken ownership checks or require a union API whose
options merely reproduce both private implementations.

## Maintenance and compute assessment

The audit covered five candidate families and found zero pairs for which mask,
support, interpolation, derivative, scale, and cache identity all matched.
Consequently, an extraction would remove zero semantically duplicated
cross-method call sites. HalfFlow currently has no dependency on
`reframe4s-resample` or `reframe4s-multiscale`; adding either dependency solely
to wrap behavior it cannot use unchanged would increase the published graph
and conversion surface.

The generic facilities that do match Flashalign's needs have already migrated:
the 504-line fused sampler and 76-line sparse buffer live in
`reframe4s-resample`, and the 758-line support-aware pyramid lives in
`reframe4s-multiscale`. Flashalign consumes those providers rather than private
copies. Canonical affine ownership already lives below both methods, and the
473-line cross-method runner stays outside the published aggregate. These are
real maintenance boundaries; adding a method-neutral objective, pyramid union,
or patch/neighborhood abstraction would create policy-bearing APIs without a
second equivalent consumer.

No timing comparison can justify substituting a numerically different kernel.
The existing Flashalign projected-patch probe remains allocation bounded at 24
bytes for both 2,000 and 20,000 iterations, while HalfFlow cost evidence must
continue to time its frozen dense stages. Any future sharing proposal must first
name two equivalent call sites and demonstrate numerical parity before timing
can be an admission criterion.

## Result and future trigger

No source code is extracted by FA-C08. The supported result is **no additional
extraction justified**. This avoids direct Flashalign-to-HalfFlow dependencies,
preserves both objectives and their failure evidence, and leaves the existing
cross-method runner as the orchestration layer.

Reopen the decision only if a second consumer adopts an exact existing contract
without compatibility switches. That proposal must state the two call sites,
complete support and mask rules, interpolation and derivative semantics,
physical scale policy, cache identity, dependency-graph effect, parity oracle,
and measured allocation or compute benefit before migration.
