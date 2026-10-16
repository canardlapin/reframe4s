# Reproducible HalfFlow query bundles

The native [bounded query evaluator](halfflow-native-query-v1.md) now has an
opt-in saved representation. `ForwardQueryBundle.encode` saves the evaluator's
inputs and policy; `decode` restores a checked `ForwardMidpointQuery` with fresh
canonical physical frame owners. Numerical failure remains an undefined map
value with typed diagnostics. This format does not claim dense-map export
admission, anatomical accuracy or global continuous invertibility.

## Saved evaluation contract

Version 1 stores:

- The three registration roles, their domain labels and lattice geometries,
  plus references recording which roles share one canonical physical frame.
- The two authoritative forward residuals and the two explicit inverse seeds,
  including every coordinate and every validity byte on the captured work grid.
- Both directions of each exact endpoint affine. This preserves the original
  cached inverse coefficients instead of silently recomputing them from the
  serialized forward coefficients.
- The explicit `halfflow-boundary-linear-newton-v1` evaluator policy and all
  bounded query settings: at most eight updates, a 4 mm step cap, at most twelve
  backtracks, and a positive equation tolerance (default 1e-8 mm).

Both map directions still evaluate inverse affine → bounded residual inverse
→ forward residual → forward affine. They never rasterize the corrected
inverse between stages. The original optimizer, inverse cache, dense export
admission and default registration policies are unchanged.

Reloading uses the existing canonical affine, grid, field and query factories.
It validates the affine direction pairs, finite fields, support lengths,
complete-cell geometry and numerical settings. Lattice sampling also depends
on its cached world-to-index matrix. Saving rejects a lattice if canonical
reconstruction from its forward matrix would change that cached inverse;
version 1 does not silently round or replace it. The affine direction-pair
consistency bound is 1e-9 in both composed matrices, using canonical affine
composition. This is construction validation, not an inverse-cycle accuracy
claim.

The old live frame owners are not valid for the reloaded maps. Callers bind
points to the restored endpoint frames. Within the restored bundle, shared
physical ownership is preserved; serialized role labels never identify an
unrelated live frame as the same owner.

## Binary layout and limits

The byte order is big endian. Fields appear in this order:

1. Int32 magic `0x48465142` (`HFQB`), Int32 version 1, and Java modified-UTF policy
   string (unsigned Int16 byte length).
2. Int32 maximum updates, Float64 equation tolerance in mm, Float64 maximum
   step in mm, Int32 maximum backtracks.
3. Three role records, in work/fixed/moving order: Int32 physical owner reference
   to this or an earlier role, modified-UTF domain label, three Int32 shape
   dimensions, and sixteen row-major Float64 index-to-world coefficients.
4. Four row-major affine matrices: work-to-fixed, fixed-to-work,
   work-to-moving, moving-to-work (sixteen Float64 values each).
5. Four fields: fixed forward, fixed inverse seed, moving forward, moving
   inverse seed. Each contains `3*N` planar Float64 coordinates, x-fastest
   within each component, followed by `N` validity bytes restricted to 0/1.
6. Int32 Adler-32 checksum of every preceding byte.

The decoder rejects unknown versions or evaluator policies, invalid shape
products, inconsistent payload sizes, invalid masks, trailing bytes and
checksum failures before returning an evaluator. The default byte limit is
512 MiB; callers can supply a stricter limit to both operations. The checksum
detects accidental corruption; provenance requires a separate SHA-256
manifest. The format retains existing dense seeds and does not yet optimize
identity-seed storage or establish production I/O/memory performance.

## Verification

All **19 selected tests pass on each of JVM and Scala.js**: eight bundle tests
plus the eleven existing query and endpoint tests. They verify byte-stable
round trips, exact affine coefficients, shared physical ownership and new live
owners, a separately calculated nonlinear between-node endpoint value, retained
failure behavior, corruption and truncation, unknown versions/policies,
invalid settings, inconsistent affine pairs, nonfinite coordinates, invalid
masks, folded cells, byte limits, and rejection of non-reproducible lattice
inverse caches. The read-only native auditor compiles. Ownership checks pass
against the actual pinned providers: **1,511 public names and 66 canonical
owners**.

## Prospective acquired-data diagnostic

The [replacement declaration](evidence/halfflow-query-bundle-20261016/declaration-v2/artifact-manifest.json)
is archived before launch. Source
`083091ae1c844a1426a7ce342b0457d529ebf3554bf3719dea6f0b6857a60bad`
binds the source snapshot and copied compiled executable. The
[first declaration](evidence/halfflow-query-bundle-20261016/unlaunched-declaration-v1/artifact-manifest.json)
remains archived and unlaunched: prelaunch code review added the lattice
inverse-cache guard and its test. No candidate outcome informed that change.

The two fixed inputs are the terminal selected-control fine checkpoints from
the previously analyzed OASIS13 and MMRR11 development trajectories: events
44 and 30, respectively. Selection precedes this audit's outcomes. Native
endpoint grids are independently checked against both the original NIfTI
headers and the prior native exports. The original residual fields and exact
affines are retained; explicit identity fields on the captured work lattice
serve only as query seeds. No new fit or inverse optimization is performed.

Both directions query **every original native endpoint voxel**, including every
boundary voxel. The audit records forward mappings and complete round trips,
with no clipping, masking or omitted failures. It separately requires:

- Exact in-memory/reloaded coordinates, status and numerical diagnostics.
- Byte-stable re-encoding and independent binary checksum/field/seed/affine/
  lattice verification against the original captured inputs.
- Exact success-status agreement with a separate Python evaluator using the
  original fields and NumPy-inverted original affines, and coordinate agreement
  within 1e-7 mm on common successful evaluations.
- Every query succeeding and a maximum cycle error at most 0.2 mm for a full
  query-cycle pass. Successful-subset maxima never replace this condition.

Every failed query has its original index and reason retained. Independent
validation uses chunks of 32,768 points while keeping the full denominators.
Native tasks run sequentially with one active Java processor, a 6 GiB heap and
a 1,200-second deadline per checkpoint; the worker deadline is 3,600 seconds.
These are untimed diagnostics, not production throughput comparisons.

## Completed acquired-data results

All four native and independent query-cycle diagnostics pass. Both bundles
re-encode byte-for-byte identically after reload. Every mapped query and round
trip succeeds; no failure, boundary voxel or original node is omitted.

| Captured checkpoint | Direction | Original queries / successful cycles | Boundary queries / successful cycles | Maximum native cycle error (mm) |
| --- | --- | ---: | ---: | ---: |
| OASIS13 event 44 | Fixed → moving | 2,228,760 / 2,228,760 | 101,338 / 101,338 | 2.202540e-8 |
| OASIS13 event 44 | Moving → fixed | 2,007,720 / 2,007,720 | 94,350 / 94,350 | 2.140781e-8 |
| MMRR11 event 30 | Fixed → moving | 1,747,278 / 1,747,278 | 85,758 / 85,758 | 2.124957e-8 |
| MMRR11 event 30 | Moving → fixed | 2,320,290 / 2,320,290 | 103,790 / 103,790 | 2.294836e-8 |

The total is **8,304,048 successful cycles**, including **385,236 boundary
voxels**, with worst native error **2.294836e-8 mm**. There are **zero** reload
status/diagnostic mismatches and **zero** coordinate changes on reload. The
independent Python evaluator agrees on every success status and every original
count, with maximum coordinate difference **1.038651e-8 mm**, below the declared
1e-7 mm parity limit. Every independent full-cycle verdict also passes.

Independent parsing verifies both Adler checksums, all original residual
coordinates and validity masks, the declared identity seeds, all endpoint
lattices, exact original affine coefficients, inverse-affine consistency and
the physical owner alias graph. The OASIS bundle is 222,877,058 bytes; MMRR is
174,728,858 bytes. These sizes include explicit dense identity seeds and are
not evidence of a production storage or performance advantage.

The [complete outcomes](evidence/halfflow-query-bundle-20261016/outcomes-v2/artifact-manifest.json)
retain the native/independent reports, complete empty failure ledgers,
byte-stability and payload hashes, original input identities, passing test and
ownership logs, source snapshot and compiled executable identities. The
worker exits successfully and all frozen identities remain unchanged. MRI,
bundle, coordinate and compiled binary payloads remain local and hashed.

This extends the prior synthetic, identity-affine evidence to two existing
acquired-MRI states with their actual endpoint affines and native lattices.
It establishes persistence and functional numerical inversion for these fixed
development checkpoints. It does not change their anatomical scores, show
qualifying convergence, prove global invertibility, or admit a separately
rasterized dense map. The historical C4 dense-export failures remain unresolved.
R02 remains open until the export contract and its original boundary cases are
resolved. A raster approximation still needs its own declared error bound,
independent topology and full-boundary cycle checks. Anatomical accuracy and
convergence remain separate qualification gates.

## Reproduction

Run `ForwardQueryBundleSuite`, `ForwardMidpointQuerySuite` and
`ResidualInverseQuerySuite` on JVM and Scala.js; compile
`flashalignBenchmarkJVM` and export its runtime classpath. With the retained
historical checkpoint and native-image inputs available, run
`benchmarks/halfflow/boundary/query_bundle.py freeze` with a fresh output tree,
passing check logs and the classpath. Archive its declaration before `run`.
`archive --phase outcomes` retains compact receipts after terminal execution;
the full command arguments are available with `--help`. The archived
`summarize_query_bundle.py` derives the scalar totals from completed receipts.
Do not overwrite either historical declaration or the completed study.
