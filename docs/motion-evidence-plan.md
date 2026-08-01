# Canonical motion evidence plan

`reframe4s-motion` is the reusable rigid-motion engine. ScalaFIM consumes it
through neuroimaging adapters and retains BIDS discovery, DVARS, censoring,
confounds, reports, and command policy. This plan defines the evidence required
before ScalaFIM's duplicate pose, estimator, sampler, and application code can
be removed.

The decision-complete direction, objective, optimizer, Volregger disposition,
and independent-oracle rules are in the
[motion optimizer contract](motion-optimizer-contract.md). Solver changes and
recovery-tolerance decisions must satisfy that contract.

The external speed-and-accuracy claim is governed separately by the frozen
[motion superiority protocol](motion-superiority-protocol.md). Its structural
validation can pass before execution, but its claim-admission gate must remain
closed until candidate, comparator, data, and hardware locks are complete.

Routine laptop checks use the separate
[`motion-laptop-regression-v1`](../benchmarks/motion/execution-tiers-v1.json)
definition. That gate runs deterministic in-memory candidate fixtures only. It
checks absolute recovery, capture, allocation, concurrency, and broad
throughput floors without downloading data or materializing images. A laptop
receipt is development regression evidence, not comparative or release
evidence. The full external court runs later on a qualified larger host.

## Corrected implementation boundary

The port must not copy three known defects from ScalaFIM:

- The existing estimator and applier reduce image geometry to dimensions and
  diagonal spacing. The replacement must use each `Grid`'s complete
  index-to-frame affine.
- The existing sampler implements a private spacing-only voxel map and
  trilinear kernel. The replacement must use the production interpolation or
  resampling machinery and explicit reusable workspaces.
- The existing spline and temporal regularizer average Euler components. The
  replacement must interpolate relative rigid transforms through SE(3)
  exponential and logarithm operations.
- The existing applier rotates about a synthesized image center while its
  exported matrix describes rotation about the physical origin. The replacement
  must expose the exact physical map it applies.
- The existing solver reports singular systems and exhausted rejected steps as
  convergence. The replacement must preserve typed termination causes and must
  not improve its objective merely by discarding out-of-bounds samples.

`reframe4s-lie` therefore establishes a validated frame-directed `Rigid3`
authority before the estimator is ported. A rigid transform has an orthonormal
rotation with determinant `+1`, a homogeneous bottom row, exact composition and
inverse, and typed exponential, logarithm, adjoint, and retraction operations.
Euler ZYX columns may exist as an explicitly named compatibility codec, but
they are not the canonical representation.

## Legacy fixture disposition

The existing Volregger-derived fixture remains useful only where it records
behavior independent of the defective geometry:

| Evidence | Disposition |
|---|---|
| Pose, row-major matrix, and inverse matrix | Compatibility oracle for the pinned Euler ZYX codec |
| Framewise displacement and pose-derived displacement summaries | Differential metric oracle after direction is made explicit |
| DVARS and censoring outputs | ScalaFIM QC evidence; not a reframe4s-motion contract |
| Identity estimator case | Smoke test only |
| Axis-aligned application edge cases | Restricted compatibility evidence only |
| Translation estimator fixture | Replace with a physical known-transform oracle |
| Euler spline fixture | Defective specification; retain only to prove the corrected result differs |
| Existing benchmark report | Harness schema evidence only; not performance evidence |

Every reused fixture must carry one of these dispositions in its test name or
metadata. Old-new agreement cannot close a gate when both implementations use
the same spacing-only geometry.

## Required test families

### Rigid-transform laws

Shared JVM and Scala.js suites must cover:

- identity, composition, exact inverse, and point round trips;
- checked affine import, including rejection of scale, shear, reflection,
  non-finite values, and a malformed homogeneous row;
- exponential-logarithm round trips near zero and near a rotation of pi;
- adjoint consistency and frame-owner rejection;
- compile-negative checks for endpoint reversal and ambiguous direction names.

### Physical estimation and application

An executable fixture builder must create distinct moving and fixed grids:

- moving shape `9 x 7 x 5`, origin `(31, -17, 8)` mm, spacing
  `(1.3, 2.1, 3.7)` mm, and direction `Rz(27 deg) * Ry(-13 deg)`;
- fixed shape `6 x 8 x 4`, spacing `(2.4, 1.1, 2.8)` mm, and direction
  `Ry(11 deg) * Rz(-19 deg)`;
- a fixed origin derived so the physical centers overlap under the known pose;
- a known moving-to-fixed translation `(2.25, -1.5, 0.75)` mm, plus a
  separate application case with a four-degree rotation about z.

The estimator suite must add translated, anisotropic, permuted, reflected, and
oblique grid parameterizations. Reparameterizing the voxels without changing
the physical image must not change the recovered physical transform.
Multiscale tests must use filtered images on actual `GridTower` grids rather
than coordinate strides. Capture controls must honor their validated physical
ranges, and every template policy must compute the statistic named by its API.

For application, sample the physical field

```text
f(x, y, z) = 2 + 0.1x - 0.2y + 0.05z
```

Trilinear interpolation reproduces this field exactly at every fully supported
target voxel. Tests must compare the production plan with the independent
`image4s-reference` sampler, including validity and boundary results.

### Temporal and acquisition behavior

Shared suites must verify:

- one and only one Time axis, strictly increasing finite time coordinates, and
  matching pose, sample, and diagnostic counts;
- explicit volume, slice, and packet coverage with a declared spatial axis;
- nonuniform acquisition times and typed extrapolation policy;
- knot and endpoint preservation;
- constant-twist reproduction;
- a `+179` to `-179` degree midpoint that remains near pi instead of crossing
  zero;
- reference-pose preservation during regularization;
- frame equivariance under a global coordinate change;
- packet-aware application against independently evaluated time-dependent pull
  maps.

### Failure and adversarial behavior

Tests must distinguish empty, zero-support, non-finite, insufficient-support,
ill-conditioned, non-converged, and invalid-control failures. They must also
cover partial or absent overlap, extreme finite coordinates, distinct live
frame owners with the same persistent identifier, wrong serialized endpoints,
missing or duplicate Time axes, malformed rigid matrices, and invalid packet
partitions. A changing-overlap fixture must prove that a candidate cannot lower
its objective by moving difficult samples outside the field of view.

### Performance and execution

Critical paths require recorded evidence rather than estimated byte counts:

- compiled state is immutable and shared safely;
- every run receives a distinct explicit workspace;
- affine application materializes no voxel-coordinate collection;
- estimator and application hot loops allocate no object per sample or voxel;
- deterministic execution policies produce identical checksums;
- JVM benchmarks report warm-up, at least twenty measured repetitions, median,
  p95, allocated bytes, peak memory, environment, commit, validity checksum,
  and visible failures;
- Scala.js optimized Node runs verify the same numerical checksums, without
  claiming JVM allocation behavior.

## Current development receipt

The current uncommitted worktree satisfies the internal `MIG-414` court:

- `sbt -J-Xmx4G -batch testAll` passes 428 tests across 82 current JVM and
  Scala.js reports with no failures or errors;
- the motion suites pass 40 JVM and 37 Scala.js tests, and an independent
  `FullOptStage` Node run passes the same 37 Scala.js tests;
- the analytic six-degree-of-freedom case recovers 0.001716758 mm translation
  and 0.043979019 degrees rotation against unchanged 0.05 mm and 0.05 degree
  gates;
- the deterministic capture court succeeds in all five scenarios on JVM,
  development Scala.js, and optimized Scala.js, with identical selections and
  results;
- the fixed-domain normal-equation kernel allocates 88 bytes at both 512 and
  32,768 samples, while a nontrivial eleven-scan LM fit over 9,261 voxels
  allocates 211,696 bytes;
- application over 147,456 samples allocates 2,371,552 bytes, of which
  2,359,296 bytes are the required output payloads.

This is development evidence, not a superiority or release claim. External
candidate/comparator execution remains subject to the frozen protocol and its
fail-closed admission lock.

The focused laptop command packages the relevant subset into one bounded
receipt:

```sh
node scripts/run-motion-laptop-gate.mjs
```

It runs `RigidEstimatorSuite`, `RigidCaptureSuite`,
`MotionPerformanceSuite`, and
`RigidOptimizationKernelPerformanceSuite`. The definition requires all
thirteen tests and every named diagnostic. It preserves the 0.05 mm and
0.05 degree six-degree-of-freedom limits, all five capture successes,
allocation ceilings, and conservative throughput floors of 5 MVox/s for pair
estimation, 10 MSamples/s for application, and 20 MSamples/s for the normal
kernel. The floors are regression tripwires, not release performance claims.

## Tolerances

Use a combined comparison:

```text
abs(actual - expected) <= absTol + relTol * max(abs(actual), abs(expected))
```

| Quantity | Required tolerance |
|---|---|
| Affine point and inverse laws | `absTol = 1e-10 mm`, `relTol = 1e-12` |
| One-impulse physical centroid | `1e-10 mm` absolute |
| Weighted physical centroid | `absTol = 1e-9 mm`, `relTol = 1e-12` |
| Linear-field production/reference comparison | `absTol = 1e-9`, `relTol = 1e-11` |
| Noise-free rigid recovery | translation `<= 0.05 mm`, rotation `<= 0.05 deg`, landmark RMS `<= 0.10 mm` |
| Noisy rigid recovery | translation `<= 0.25 mm`, rotation `<= 0.25 deg`, physical p95 displacement `<= 0.50 mm` |
| Constant-twist interpolation | point error `<= 1e-9 mm`, rotation error `<= 1e-10 rad` |
| `179` to `-179` degree midpoint | angular distance to pi `<= 1e-8 rad` |

The centroid initializer cannot claim the rigid-estimator recovery thresholds.

## Downstream removal gate

ScalaFIM may remove its duplicate algorithms only after:

1. its `NeuroVec` and mask adapters consume the canonical engine;
2. BIDS, NIfTI, report, and CLI round trips preserve time, frame identity, map
   direction, matrices, and the selected target affine;
3. trusted restricted legacy fixtures pass through the adapter;
4. corrected physical-affine and SE(3) fixtures pass instead of the defective
   legacy expectations;
5. `motionJVM/test`, `motionJS/test`, ScalaFIM aggregate tests, examples,
   browser checks, and an immutable consumer build are green.
