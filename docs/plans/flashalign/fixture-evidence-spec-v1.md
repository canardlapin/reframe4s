# Flashalign fixture and evidence specification v1

Status: **development fixture contract, frozen at version 1.0.0**

This document fixes the evidence boundaries used while implementing Flashalign.
It separates algebra correctness, geometric recovery, failure accounting, and
runtime cost. Passing one category cannot substitute for another.

The machine-readable manifest is
[`benchmarks/flashalign/manifests/development-fixtures-v1.json`](../../../benchmarks/flashalign/manifests/development-fixtures-v1.json).
It is validated by
[`fixture-manifest-v1.schema.json`](../../../benchmarks/flashalign/schemas/fixture-manifest-v1.schema.json).
Every benchmark attempt, including failure, uses
[`raw-result-v1.schema.json`](../../../benchmarks/flashalign/schemas/raw-result-v1.schema.json).

## Frozen development oracle

The small fixture generator is
[`generate_development_fixtures.py`](../../../benchmarks/flashalign/fixtures/generate_development_fixtures.py),
version `1.0.0`, SHA-256
`b7f268a62786ec32d6248247922db82a10c9460d9bb2a3d7ef47e9db95f9ba60`.
Its output is
[`development-fixtures-v1.json`](../../../benchmarks/flashalign/fixtures/development-fixtures-v1.json),
SHA-256
`317280b8f81aa8da4e544a3e06bdcafb73ede18979127bba51695bf651a038d5`.

The generator uses only the Python standard library. It evaluates an asymmetric
continuous phantom, its closed-form world gradient, physical transforms, and
landmarks directly. It does not import reframe4s, image4s, Ravel, Gale, a
candidate interpolator, a transform basis, or an optimizer. Regenerate it with:

```text
python3 benchmarks/flashalign/fixtures/generate_development_fixtures.py \
  --output benchmarks/flashalign/fixtures/development-fixtures-v1.json
```

Regeneration must be byte-identical. Any generator or output change requires a
new semantic generator version, new hashes, and a manifest review before the
changed fixtures inform tuning. Never update expected values merely because the
candidate implementation changed.

The current development set contains:

- an analytic scalar phantom with exact values and world gradients at off-grid
  probes;
- identity, +10 mm translation, nontrivial rigid, and mild affine truths;
- world-landmark pairs for every transform;
- isotropic, anisotropic, axis-permuted/reflected, oblique, sheared, and
  displaced voxel-to-world headers;
- three informative 27-sample physical stencils with precomputed normalized
  moving vectors;
- a constant degenerate patch and an invalid-support patch;
- fixed central-difference step sweeps for algebra, translation, and rotation.

These are intentionally small correctness fixtures. They do not establish MRI
accuracy, capture range, failure-tail performance, or runtime.

## Independent truth policy

At least one oracle for each scientific or numerical claim must be independent
of the candidate path being tested.

| Claim | Independent evidence |
|---|---|
| Coordinate direction and composition | Analytic world landmarks transformed by explicit matrices |
| Interpolation value and gradient | Analytic multilinear functions or the closed-form continuous phantom; finite differences avoid interpolation knots |
| Patch normalization and curvature | Explicit centering/projector/Jacobian matrices and the copied Python algebra oracles |
| Rigid and affine recovery | Fixed physical transform and world landmarks; the moving and fixed arrays are separately sampled from the continuous phantom |
| PE displacement | Analytic scalar fields expressed independently of the production spline evaluator, including unsafe-between-samples adversaries |
| Small-strain displacement | Analytic vector fields and independently evaluated gradients, singular values, and landmarks |
| Inverse | Independent forward/roundtrip residuals on a declared domain, including failed bracketing or out-of-domain points |
| Runtime | Work counters plus external wall-clock and allocation measurements; optimized loss is not a speed oracle |

Production interpolation may be used to exercise the candidate, but it may not
generate all fixed/moving truth or the expected answer. A basis-recovery fixture
must also be accompanied by out-of-basis truth so exact recovery cannot hide a
modeling error. Candidate similarity scores are diagnostics, never external
anatomical truth.

## Gate definitions and tolerances

All scalar checks combine absolute and relative tolerances. Record the value,
reference, scale, absolute error, relative error, condition diagnostic, and
floating-point backend. Do not globally loosen a tolerance to admit an
ill-conditioned case; require the typed fallback or failure intended for that
case.

| Gate | Initial development threshold | Interpretation |
|---|---:|---|
| Well-conditioned Double algebra and adjoints | `1e-10` absolute plus `1e-9` relative | Matrix/statistics or JVP/VJP correctness |
| Directional finite differences | `1e-6` relative on a stable step-size plateau | Derivative correctness away from knots/gates |
| Noise-free rigid landmark RMS | `<= 0.1 mm` | Core end-to-end geometry fixture |
| Noise-free rigid rotation | `<= 0.1 degree` geodesic SO(3) error | Core end-to-end pose fixture |
| Exactly representable nonlinear landmark RMS | `<= 0.1 mm` | Development basis recovery only |
| Certified-scope nonlinear inverse roundtrip | `<= 0.01 mm` maximum | Numerical inverse evidence |

The derivative sweep is part of the evidence. Report every declared step and
look for truncation, stable, and rounding regimes. A single selected step cannot
support the claim. Near-zero reference derivatives use the absolute tolerance
and are labeled. Interpolation tests stay away from trilinear knots unless the
test explicitly checks one-sided/boundary behavior.

The `0.1 mm`, `0.1 degree`, and `0.01 mm` values are engineering fixture gates.
They are neither clinical tolerances nor promises for acquired MRI. No runtime
target alters a correctness tolerance.

## Required fixture families

Each implementation gate selects from these families and records every selected
case ID.

1. **Geometry:** identity, translation, rigid, affine, anisotropy, obliquity,
   shear, axis permutation/reflection, origin changes, inversion, composition,
   and restored owner identity.
2. **Interpolation:** multilinear polynomials, analytic phantom probes,
   cropped/strided storage, boundary taps, invalid taps, large intensity offsets,
   and world-gradient finite differences.
3. **Patch algebra:** explicit versus compressed 6- and 12-parameter matrices;
   64-, 96-, and >128-parameter matrix-free actions; gain/offset invariance;
   positive/negative polarity; exact hard-norm projection; degenerate contrast;
   and constant invalid-patch cost.
4. **Objective and optimizer:** complete small-population enumeration,
   replacement-sampling multiplicities, exact early rejection, rejected-step
   reuse, refresh/relinearization, nonzero prior gradients, clipped-step
   prediction, stalled/converged separation, and solver breakdown.
5. **PE field:** oblique PE direction, coefficient derivatives, determinant
   lemma, constant/affine gauges, whole-domain monotonicity bounds, amplitude
   brackets, unsafe-between-samples cases, and bracketed inverse residuals.
6. **Small strain:** analytic vector modes, pose gauge, elastic prior derivatives,
   whole-domain gradient bounds, contraction inverse, padded-domain behavior,
   and independent singular-value checks.
7. **Failure tail:** partial slabs, low overlap, dropout, missing anatomy,
   homogeneous data, repetitive/mirror-like structure, large initialization
   errors, competing candidates, and prior-dominated nonlinear modes.
8. **Output and integration:** asymmetric grid pull direction, composition,
   original-image single resampling, serialized basis identity, concurrency,
   existing module gates, dependency graph, and symbol ownership.

## Initialization distributions

Development runs use explicit IDs and seeds. Every case records the complete
moving-to-fixed initialization matrix and its SHA-256.

- `near`: translations independently drawn from `[-2, 2]` mm and rotation-axis
  magnitude from `[0, 2]` degrees;
- `ordinary`: translations from `[-8, 8]` mm and rotation magnitude from
  `[0, 8]` degrees;
- `large`: translations from `[-25, 25]` mm and rotation magnitude from
  `[8, 25]` degrees;
- `adversarial`: deterministic mirror-like, slab-overlap, and near-boundary
  placements defined by fixture ID rather than random rejection sampling.

Sampling distributions, counts, seeds, and capture limits must be frozen in a
versioned manifest before selection or audit execution. Failed initializations
remain in their original strata and denominator.

## Development, selection, and audit separation

The current manifest is `development-open`. Its cases may be inspected while
implementing and debugging, so they cannot support a sealed accuracy or
superiority claim.

Future acquired-data manifests use subject as the minimum split unit. Related
sessions, repeat scans, derived volumes, and all initialization replicates from
one subject stay in the same split. Site is used as the split unit when site
effects are part of the claim. Selection data may choose presets and model
complexity. Audit data is read only after code revision, providers, fixtures,
initialization distribution, thresholds, and settings are frozen. Audit results
cannot flow back into tuning without retiring the audit and issuing a new
versioned split.

Within one image pair, optimization, selection, and audit patch sets use fixed
spatial blocks and recorded IDs. Overlapping stencils or deduplicated sample
points cannot cross a boundary used to claim generalization. If they do, the
sets must be labeled numerical holdouts with spatial dependence.

## Data provenance and licensing

The analytic fixture sources are repository-authored and Apache-2.0. Each future
real-data manifest must record dataset name/version, immutable subject/file
identifiers, checksums, acquisition subset, license/SPDX identifier or exact
terms, permitted redistribution, download provenance, preprocessing provenance,
mask/FOV policy, and exclusions. Private data records access authority and a
non-redistributable identifier without embedding protected paths or content in
public receipts.

No real-data result enters selection or audit until its license and provenance
record is complete. Replacing a file under the same display name changes its
hash and creates a new dataset version.

## Failure denominator and raw records

The denominator is every declared fixture-by-initialization run. Crashes,
nonconvergence, ambiguity, invalid geometry, inverse failure, insufficient
overlap/information, exhausted search, and unavailable comparator lanes remain
rows. A successful identity is permitted only when identity is the recovered
and validated result, never as a fallback for failure.

Raw records include:

- case, cohort, method/model, source revision, input/config/initialization hashes;
- OS, CPU, JDK, threads, comparator command and version;
- typed outcome and structured failure detail;
- external geometry metrics and diagnostic similarity metrics;
- unique interpolations, gradient calls, geometry/basis operations,
  linearizations, trial/rejected/early-rejected counts, and curvature products;
- read, preparation, capture, optimization, validation, output, and total time.

Raw rows are append-only for a run ID. Corrections create a new run and retain
the superseded row. Summary code must prove `reported denominator == declared
run count` for every cohort and must list missing rows. Keep per-case failures;
an aggregate failure rate alone is insufficient.

## Runtime and comparison evidence

Timing begins only after the scientific configuration and fixture set are fixed.
Report cold and warm behavior where relevant. NIfTI decompression may be shown
separately, but total input-to-result time remains available. Compare methods on
the same machine, thread count, input/checksum, initialization, mask/FOV, output
interpolation, and allowed transformation class. Include upstream initialization
and every intermediate resampling cost in complete-pipeline comparisons.

Flashalign linear, Flashalign constrained nonlinear, and HalfFlow/BasinBridge
retain separate method IDs and configs. Shared scoring and receipts do not imply
shared objectives. The C0-C7 comparison lanes in the implementation PRD retain
unchanged recipient controls and report stage costs. An unavailable or
unadmitted HalfFlow export remains an unavailable/failed row.

Report paired per-subject uncertainty for median, p95, and catastrophic-failure
differences. Superiority requires its predeclared confidence criterion; a
favorable point estimate or optimized metric is insufficient. Accuracy,
invertibility, regularization, and runtime remain separate claims.
