# HalfFlow functional query export admission

`ForwardQueryExporter` adds opt-in admission for the saved
[functional query representation](halfflow-query-bundle-v1.md). It returns
canonical partial `SpatialMap`s that retain inverse correction at evaluation
time. The existing optimizer, dense exporter, dense-export thresholds and
registration defaults are unchanged.

## Admission and application contract

`inspect(plan, config, observe)` first encodes and reloads a privately owned
query-bundle snapshot. This validates all saved fields, seeds, affines, frame
relationships and numerical policy. A caller's input fields cannot subsequently
replace the measured snapshot. A query equation tolerance above 1e-8 mm is
rejected; existing limits of eight updates, 4 mm steps and twelve backtracks
remain in force. Cycle configuration can tighten the bound but cannot exceed
0.2 mm. The default sample budget is twenty million points per direction;
exceeding it rejects the scan rather than evaluating a selected subset.

For each native endpoint grid with shape `(nx,ny,nz)`, admission evaluates every
point of its full half-voxel lattice, shape `(2*nx-1,2*ny-1,2*nz-1)`. This
includes all original voxel nodes, edge midpoints, face centers, cell centers,
and every outer face/edge/corner at that sampling resolution. Both endpoint
directions are evaluated. No mask, interior-only scope or dropped failure is
allowed.

At each point, the original bounded inverse must converge; the full endpoint
map's analytic branch determinant must be finite and strictly positive; its
reverse must also converge with a finite positive branch determinant; and
the complete endpoint cycle must be finite and at most the configured bound.
The determinant uses the existing sampler's branch at knots and the exact
chain rule:

`det(A_destination) * det(F_destination'(root)) * det(A_source_inverse) / det(F_source'(root))`.

Both residual derivatives come from the shared boundary-linear sampler's
world-coordinate derivative path. Corners with zero value weight but nonzero
derivative weight must have support. Affines remain canonical operators; no
second geometry or coordinate owner is introduced.

The observer receives every indexed sample, including every failure. The
report retains complete attempted/successful and boundary denominators and
the first failure per direction. Its minimum-Jacobian and maximum-cycle fields
are explicitly named successful-subset statistics; a successful subset never
satisfies admission. Experiment observers stream the complete failure ledger
and all successful coordinates, determinants and cycle errors to disk.

`admit(candidate)` requires every declared sample to pass in both directions.
Candidate construction is private, and a copied public report cannot authorize
another transform. The admitted object exposes maps and cloned bundle bytes,
not mutable access to its private measured evaluator.

Every subsequent call through an admitted map independently repeats both local
branch determinant checks and the full numerical cycle check. A query that
fails between admission samples or in exterior continuation returns a typed
failure/undefined canonical map value; it never returns its seed or last
estimate as a substitute. Source frame and workspace ownership checks remain
active. Fresh frame owners are expected because admission measures a reloaded
snapshot.

These are partial-map guarantees for returned query values and a finite
sampled admission report. They do not manufacture `SmoothMap`, `SmoothIso`,
global continuous invertibility, dense-map topology, anatomical accuracy or
convergence. The saved bundle contains evaluation inputs, not a reusable
admission certificate: reloading for export requires fresh inspection/admission.

## Verification before replay

All **27 selected tests pass on both JVM and Scala.js**: eight new functional
export tests plus the nineteen query/bundle checks. They cover analytic
endpoint determinants against an independently calculated piecewise result
and finite differences, exact value-path parity, complete half-voxel/boundary
counts, canonical composition, failed-root denominators, non-authoritative
copied reports, immutable snapshots and byte copies, owner/workspace mismatch,
invalid policies and budgets, derivative-only support, and per-call rejection
of exterior folds and between-sample cycle violations.

The native read-only auditor compiles after correcting a Scala existential
inference error in an inline decode/encode expression; its initial failed build
log is retained. Production numerical settings were unchanged by that fix.
Ownership validation against the pinned providers passes for **1,522 public
names and 66 canonical owners**.

## Frozen original-C4 replay

The [declaration](evidence/halfflow-functional-export-20261016/declaration-v1/artifact-manifest.json)
was archived before launch. Source
`c294e1268f6d6684616ed6aa0d4aaad6cf7fd6c7a11c4d3bd6ceea8388d408c2`
binds the full source snapshot and copied compiled executable.

All six original exact-core affine C4 cases, r19 through r24, are included:

- The six immutable states captured after the complete-cell admission repair.
- The six archived pre-repair states as controls, including their unsafe fields.

The exact original lane source matches both capture protocols:
`e9388b9d6c4363508c46c24445e06ae9dcf09dee738dec8cc19dd7ee60d47e91`.
C4 calls `ForwardMidpoint.identity`; residual advances preserve its affine
factors. Its actual factors are therefore identity. This is explicitly bound
to the historical lane and is not an arbitrary identity-affine replacement.
The actual original moving/fixed NIfTI inputs are hash-checked against the
sealed six-case table, and their native lattices are used. No C5 affine
attribution or new fitting is part of this study.

Each 37³ native endpoint grid yields **389,017** half-voxel queries per
direction (73³), **778,034** per state. Construction rejection remains explicit
with both original declared denominators; a rejected state is never treated
as a zero-error query result or silently removed from the twelve-state panel.
The captured original inverse fields are retained as seeds; their sampled
accuracy never authorizes acceptance.

Independent Python recomputes complete-cell geometry and construction
verdicts, then evaluates the same full half-voxel queries from original fields
and seeds. It must agree on every status and count, coordinates within 1e-7 mm,
endpoint determinants within 1e-7, and each functional-admission verdict.
It separately checks declared runtime probes between admission samples.
The unchanged 0.2 mm cycle limit and strictly positive endpoint branch
Jacobian requirement apply to every successful query. Query cycles and
geometry cannot substitute for anatomical or dense-export evidence.

Tasks run sequentially with one active Java processor, a 3 GiB heap and a
180-second deadline per state; the worker deadline is 1,800 seconds. All
attempts, errors and input/source identities are retained. This is an untimed
numerical audit, not a production throughput or memory comparison.

## Results and remaining scope

All twelve states finish with unchanged source/input/executable identities,
and every native/independent validation passes. The six corrected states all
pass functional admission; all six pre-repair controls are rejected before
query scanning because at least one forward arm has folded complete cells.
Their full **4,668,204** declared query positions remain explicitly associated
with construction rejection, not counted as successful cycles.

The corrected states evaluate and pass **4,668,204 / 4,668,204** half-voxel
queries, including **373,272 / 373,272** boundary points. Worst cycle error is
**3.692972e-8 mm**, below the unchanged 0.2 mm limit. Minimum sampled full-chain
endpoint branch determinant is **0.139508**, strictly positive.

| Corrected C4 state | Both directions admitted | Original half-voxel queries passing | Maximum cycle error (mm) | Minimum endpoint branch determinant |
| --- | --- | ---: | ---: | ---: |
| r19 | yes | 778,034 / 778,034 | 2.772660e-8 | 0.139508 |
| r20 | yes | 778,034 / 778,034 | 3.692972e-8 | 0.261407 |
| r21 | yes | 778,034 / 778,034 | 2.558695e-8 | 0.303081 |
| r22 | yes | 778,034 / 778,034 | 3.566894e-8 | 0.239378 |
| r23 | yes | 778,034 / 778,034 | 2.488272e-8 | 0.335861 |
| r24 | yes | 778,034 / 778,034 | 2.359343e-8 | 0.259490 |

Python agrees on every construction and functional-admission decision and
every queried success status, with **zero status mismatches**. Maximum native/
Python coordinate difference is **9.113247e-14 mm**; maximum endpoint determinant
difference is **5.906386e-14**, within the respective frozen 1e-7 limits.
Independent complete-cell counts, fold counts and minima agree for all 24
forward fields. All twelve between-sample application probes match Python;
the six saved bundles re-encode byte-for-byte identically.

The [complete outcomes](evidence/halfflow-functional-export-20261016/outcomes-v1/artifact-manifest.json)
retain every state receipt, construction rejection, query and probe report,
full empty query-failure ledgers for admitted states, passing/initial build
logs, original input identities, source snapshot and executable hashes. Full
coordinate, status and bundle payloads remain local with exact hashes.

This completes sampled functional admission for the corrected original C4
cases and verifies rejection of their unsafe predecessors. It does not turn
the historical raster outputs into admitted dense maps; the existing dense
exporter still rejects all six corrected C4 cases. No optimizer or anatomical
score changes are part of this tranche.

R02 remains open for application-level adoption and qualification of the new
functional export contract, including acquired-MRI admission through this same
API and independent resampling/scoring. The earlier acquired-MRI query-bundle
study establishes persistence and whole-node cycles; it did not run this new
half-voxel admission/pointwise geometry contract. A dense approximation, if
required, still needs separate declared error and topology checks. Global
invertibility, convergence and anatomical qualification remain separate gates.

## Reproduction

Run `ForwardQueryExportSuite` plus the three existing query/bundle suites on
JVM and Scala.js. Compile `flashalignBenchmarkJVM` and export its runtime
classpath. `benchmarks/halfflow/boundary/functional_export.py freeze` takes a
fresh output tree, classpath and check-log directory; archive the declaration
before `run`. Completed outcomes are archived with `archive --phase outcomes`.
The retained `summarize_functional_export.py` derives all scalar totals from
the twelve completed receipts. Original captures and sealed endpoint images
must retain their declared hashes; no refitting is permitted for reproduction.
