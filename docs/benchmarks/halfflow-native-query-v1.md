# Native HalfFlow inverse and endpoint queries

HalfFlow now has an opt-in native bounded inverse evaluator and functional
endpoint composition. These implement the
[compact query experiment](halfflow-query-inverse-v1.md) without storing a
denser inverse or rasterizing inverse corrections between transform stages.
The existing optimizer, inverse cache and dense export admission remain
unchanged.

## Native interfaces and failure behavior

`ResidualInverseQuery.compile` takes the authoritative forward residual,
an explicit stored-inverse seed, and validated settings. It rechecks input
field construction, source/target roles, canonical lattice ownership,
complete 3D cells and the existing 0.05/99% complete-cell geometry criteria.
The seed is acceleration state; its accuracy never establishes success.

The default policy retains eight updates, a 4 mm step cap, twelve backtracks
and a 1e-8 mm full-equation tolerance. The factory rejects an update budget
above eight, a step cap above 4 mm, a backtracking budget above twelve and
nonfinite/invalid settings. The settings object has a private constructor.

Each query returns a converged value or a typed failure with residual,
last-estimate diagnostics and work counts. Failure reasons cover missing seed
or forward support, singular/nonpositive root Jacobians, nonfinite steps,
stalls, iteration limits, frame mismatches and invalid/busy workspaces.
Failed estimates are never returned as map values or silently substituted
for the seed.

The existing HalfFlow boundary-linear sampler owns the coordinate behavior.
Its new optional derivative path differentiates the actual trilinear field in
world coordinates, retains the existing near-knot snapping convention and
requires support from corners with nonzero derivative weights, including
corners whose value weight is zero. Existing value sampling uses the same
arithmetic and boundary policy. No separate coordinate/frame owner is added.

`ForwardMidpointQuery.compile` accepts both forward arms and their explicit
inverse seeds. Fixed-to-moving evaluation applies the exact inverse fixed
affine, evaluates the fixed residual inverse, evaluates the moving forward
residual, then applies the exact moving affine. Reverse evaluation uses the
opposite arm order. These stages remain functions; no intermediate inverse
grid is constructed.

Both APIs expose canonical `SpatialMap` adapters, retaining live frame
ownership and ordinary checked composition. Numerical failure is an undefined
map evaluation (`OutsideDomain`); callers needing the detailed HalfFlow
failure reason use `evaluate`. No `SmoothMap` or `SmoothIso` capability or
global invertibility certificate is manufactured. Reusable worker workspaces
are constant-sized, bound to their compiled plan and protected against
simultaneous/reentrant use. The convenience map adapters allocate separate
scratch per call.

## Verification before the frozen field run

All 95 distinct selected tests pass on each of JVM and Scala.js. These include
eleven new query/endpoint tests plus the existing sampler, complete-cell,
export, engine, BasinBridge, midpoint and registration-algebra checks. New
cases cover oblique anisotropic/left-handed world derivatives against central
finite differences, nonlinear between-node inversion, exterior continuation,
zero-weight derivative support, physical step limits, unsupported seeds,
singular exterior roots, frame ownership, workspace reuse and unchecked
internal field construction. Nonidentity affine endpoint composition agrees
with an independent piecewise-linear nonlinear fixture and closes its
canonical composed round trip.

The source guard's initial test fixture tried a private `DensePull.copy` and
failed compilation; the retained corrected fixture uses the existing
package-private unchecked construction path. Production numerical policy was
not changed by that correction. All eleven final focused checks pass on both
platforms, and the read-only native auditor compiles.

Ownership validation passes against the pinned clean image4s, spatial4s and
locus4s providers: 1,510 public qualified names are unique and all 66 canonical
owners verify. Generic field fixed-point inversion is unchanged; the new
Newton policy stays beside HalfFlow's residual inverse implementation.

## Prospective native field and endpoint audit

The [declaration](evidence/halfflow-native-query-20261015/declaration-v1/artifact-manifest.json)
was archived before launch. Source `bbd95a5305d4cdb25504993f9a62f2364e2dce4f38fd95b58c58de87d54f44b9`
identifies the full executable prerequisite snapshot. The freeze copies the
compiled class directories, hashes the compiled classpath/jars, and binds all
field, query, Python-reference, source and passing-check identities. Later
builds cannot replace the measured classes.

The declared tasks are:

- **24 residual comparisons:** all six previously observed synthetic cases,
  C4/C5, both arms. Each evaluates all 50,653 original target nodes and all
  50,653 forward-node queries, comparing native coordinates and exact success
  status against the earlier eight-correction Python reference. The absolute
  coordinate-difference tolerance is prospectively 1e-7 mm.
- **24 functional endpoint round trips:** both directions of all twelve
  captured two-arm states, at all 50,653 original nodes. Each uses the new
  native endpoint composition and its reverse, compared against the same
  explicitly composed Python query evaluator. All endpoint affines are
  explicitly identity, with fresh related canonical endpoint owners. These
  are residual-composition diagnostics, not historical endpoint replays.

Failures remain in the original denominators; diagnostic coordinates from a
failed query cannot be accepted output. Complete endpoint-cycle admission
requires every query to succeed and the unchanged 0.2 mm bound. Coordinate
and status parity is reported separately from that scientific diagnostic.

Jobs run sequentially with one active Java processor, a 2 GiB heap, a
180-second per-task deadline and a 1,800-second worker deadline. Work counts
are retained. These diagnostics do not establish production timing or memory
comparisons, dense exported-map topology, or raster-export accuracy.

## Completed native results

All 48 declared tasks finish successfully with unchanged source/input/class
identities. Every native query succeeds; no invalid or capped query is dropped
or replaced by its diagnostic estimate.

| Native task group | Tasks passing parity | Original queries | Successful queries | Maximum coordinate difference from Python |
| --- | ---: | ---: | ---: | ---: |
| Residual inverse | 24/24 | 2,431,344 | 2,431,344 | 3.035440e-14 mm |
| Functional endpoint round trip | 24/24 | 1,215,672 | 1,215,672 | 9.113247e-14 mm |

The largest full-equation residual is 9.999917e-9 mm, below the unchanged
1e-8 mm convergence tolerance. The minimum sampled forward Jacobian at a
residual query preimage is 0.147391; the endpoint-query minimum is 0.133622.
All 24 functional endpoint cycles pass the complete 0.2 mm diagnostic, with
worst round-trip error **3.460102e-8 mm**. Every cycle retains all 50,653
original points, including its full boundary.

This establishes native parity and functional composition on the declared
captured fields. It does not replay their historical endpoint affines or
admit their saved dense maps. The earlier rasterized C4 failures remain
unchanged; a function-level round trip cannot replace a saved-map audit.

The [complete outcomes](evidence/halfflow-native-query-20261015/outcomes-v1/artifact-manifest.json)
retain every native task receipt, failure ledger, work-count report,
comparison verdict and input/output coordinate hash, plus the source and
compiled-executable seals. Dense payloads remain local. The source guard's
initial fixture compilation failure and all passing validation logs are
retained alongside the numerical outcomes.

The subsequent [saved query-bundle study](halfflow-query-bundle-v1.md) now
preserves this native evaluator across serialization and validates functional
cycles on two acquired-MRI checkpoints using their actual endpoint affines and
native lattices. Dense raster approximation, independent topology and the
original full-boundary export contract remain separate. R02 remains open.

## Scope and reproduction

This work does not establish acquired-MRI accuracy, qualifying convergence,
global continuous invertibility or completion of R02. Dense export still
needs a separately validated representation and its existing geometry and
round-trip gates. No new MRI registration or candidate optimization fit is
part of this tranche.

Run `ResidualInverseQuerySuite` and `ForwardMidpointQuerySuite` on JVM/Scala.js,
and the existing affected suites listed in the retained logs. Compile
`flashalignBenchmarkJVM` and export its runtime classpath. `native_query.py`
freezes a fresh study against the complete Python query reference and passing
logs; archive that declaration before `run`. All dense inputs and output
coordinate payloads remain local, with exact hashes in the evidence. The
compact archive retains numerical sources, all scalar receipts and failures,
frozen executable identities and the verification logs.
