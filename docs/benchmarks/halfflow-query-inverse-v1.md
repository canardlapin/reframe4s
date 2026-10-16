# HalfFlow compact inverse query evaluation

This R02 experiment tests a compact alternative to the
[uniform inverse-lattice refinement study](halfflow-inverse-refinement-v1.md).
The evaluator retains the original forward and stored inverse fields. At each
requested inverse coordinate it uses the stored inverse as a seed and applies
a fixed bounded number of Newton corrections against the authoritative
boundary-linear trilinear forward map. It stores no denser inverse lattice.

## Prospective comparison

The [initial declaration](evidence/halfflow-query-inverse-20261015/declaration-v1/artifact-manifest.json)
selects all 24 admitted forward fields from the complete corrected six-case
synthetic replay: C4 and C5, both arms. Every forward field must match its
independent passing complete-cell audit and immutable captured payloads.
The comparison declares 0, 1, 2, 4 and 8 corrections for every field: 120
field/budget variants, with no selection or expansion after their outcomes.

Each evaluator starts from the original stored inverse, keeps the 4 mm step
cap, 12 backtracks and 1e-8 mm equation tolerance, and reports cap-limited or
blocked queries explicitly. Both compositions retain all original grid nodes,
including the full boundary. The maximum cycle limit remains 0.2 mm.

Direct query accuracy and raster export are separate measurements. The query
diagnostic requires both full-domain direct compositions to pass, no
unsupported queries, positive forward Jacobians at returned preimages, and
passing complete-cell geometry on the materialized inverse. Full 1e-8 mm
root convergence is reported separately; a capped query is never labelled
converged merely because it meets the looser cycle-error limit.

The raster diagnostic saves the corrected values on the original lattice,
then evaluates both compositions through that stored trilinear map. It retains
the original all-cell geometry check: eight corners and center, 0.05 Jacobian
floor and at least 99% complete-cell coverage. Good direct evaluations cannot
substitute for failed raster export. Neither root-local Jacobians nor sampled
raster geometry certify the continuous query evaluator's global invertibility.

Work counters include seed samples, forward samples, derivative samples,
linear solves, backtracking trials and final residual/Jacobian validation.
Diagnostic coordinate arrays remain local for verification and are identified
by hash; they are not additional evaluator coefficients. One numerical thread
and a 1,800-second worker deadline bound execution. Other numerical jobs were
active at preflight, so elapsed times cannot support production comparisons.

## Preserved validation failure

The [first attempt](evidence/halfflow-query-inverse-20261015/failed-validation-v1/artifact-manifest.json)
ends with exit 1 and zero recorded candidate variants. Its first C5 zero-budget
baseline exposed a convention mismatch in the independent SciPy check: the
authoritative sampler snaps coordinates within 1e-10 voxel of an integer
knot, while the independent interpolator initially did not. Thirty forward
queries differ by more than 1e-10 mm, with a maximum 3.090277e-10 mm vector
discrepancy. Applying the existing knot convention reduces that maximum to
1.740467e-14 mm. No candidate numerical policy is changed by this diagnosis.

The [corrected declaration](evidence/halfflow-query-inverse-20261015/declaration-v2/artifact-manifest.json)
was archived before the new attempt. Its freeze verifies the primary inverse
evaluator is byte-identical and the fields, correction budgets, solver
settings, cycle limit and expected denominator match the original declaration.
Only independent-validation coordinate handling and its provenance change.
The failed source bytes are preserved from their original declaration.

All 22 analytic checks pass, including seven new query checks and a new
independent near-knot interpolation check. The query values agree exactly
with the pre-existing bounded root oracle at every declared budget on the
analytic nonlinear fixture. Tests also cover oblique/left-handed affine
inversion, exterior coordinates, unsupported seeds, singular Jacobians,
large updates that must remain step-capped, exact zero-budget preservation,
work bounds, and the distinction between direct and rasterized inverses.

The initial analytic test run is retained too: its affine fixture incorrectly
expected a correction larger than the existing 4 mm cap to finish in one
step. The corrected fixture tests an affine update within that cap; a
separate large-translation test verifies cap enforcement and explicit
nonconvergence. The evaluator and physical cap were unchanged.

## Completed results

All 120 declared field/budget variants completed with unchanged source/input
identities. Each budget evaluates 2,431,344 queries across the original-node
and forward-node query sets. Every direct and raster composition keeps all
50,653 original nodes per field with zero invalid samples. Independent SciPy
forward evaluation agrees at all 12,156,720 corrected preimages, with maximum
vector disagreement 3.553824e-14 mm.

| Maximum corrections | Query diagnostic passes / 24 | Fields with every query converged / 24 | Unresolved at 1e-8 mm | Worst direct cycle (mm) | Raster diagnostic passes / 24 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0 | 12 | 0 | 2,428,612 | 1.156729 | 12 |
| 1 | 23 | 0 | 549,035 | 0.338073 | 12 |
| 2 | **24** | 12 | 1,634 | 0.050252 | 12 |
| 4 | **24** | 22 | 2 | 0.000002134 | 12 |
| 8 | **24** | **24** | **0** | **0.00000002073** | 12 |

Two corrections suffice for the unchanged 0.2 mm query diagnostic across all
six cases and both lanes/arms. This does not establish equation convergence:
1,634 queries still miss 1e-8 mm. The eight-correction variant brings every
query to that tolerance and keeps all direct cycle errors below 2.1e-8 mm.
All corrected materialized inverse lattices pass the cell check from budget
one onward; the uncorrected r19 C4 fixed inverse retains its known fold.
At budgets four/eight the minimum materialized inverse cell Jacobian is
0.081034, above the unchanged 0.05 floor.

The raster verdict remains **0/12 C4 fields and 12/12 C5 controls at every
budget**. At budget eight, the worst original-grid raster cycle is still
1.147023 mm. For r24 specifically, direct query cycles fall below 1.6e-8 mm
in both arms while their rasterized inverse cycles remain 0.756696 and
0.605013 mm. Query correction removes the observed evaluation error without
making the original stored-grid representation accurate. The positive C5
controls retain passing raster verdicts; corrected coordinates are not
claimed to be byte-identical to their previous inverses.

The work measurements expose the tradeoff:

| Correction budget | Forward point samples, including validation | Linear solves |
| --- | ---: | ---: |
| 0 | 2,431,344 | 0 |
| 1 | 7,291,300 | 2,428,612 |
| 2 | 10,271,679 | 2,977,647 |
| 4 | 13,920,335 | 2,979,287 |
| 8 | 14,173,602 | 2,979,289 |

Every variant retains the original 50,653 inverse nodes per field, rather
than the preceding uniform-refinement study's 3,048,625 nodes. These are
actual batch-prototype work counts, including repeated evaluations and final
validation; they do not predict native per-query timing. The prototype's
diagnostic peak worker RSS is about 345 MiB, including Python/SciPy and
verification/reporting work. It is not a production memory budget.

The [complete outcome archive](evidence/halfflow-query-inverse-20261015/outcomes-v2/artifact-manifest.json)
retains the 120 receipts, both composition reports and all-cell audits per
variant, independent checks, work counters, exact-set scientific summary,
terminal attempt and input/source identities. All local coordinate payloads
are hashed and verified; no dense fields or MRI data enter the archive.

The next implementation target is a native bounded inverse evaluator with
explicit query failures, preserving correction during transform evaluation
and endpoint composition. Raster export needs a separately validated
representation or approximation strategy. Simply correcting grid nodes and
using the current exporter has failed this experiment. R02 remains open.

The [native follow-up](halfflow-native-query-v1.md) implements the bounded
evaluator and functional endpoint composition. All 24 native residual tasks
match the Python reference, and all 24 identity-affine functional endpoint
cycles pass on the original full point sets. The worst endpoint cycle is
3.460102e-8 mm. These results retain correction during evaluation; the
separate saved-grid export failures remain unresolved.

## Scope and reproduction

This experiment concerns previously observed synthetic fields. It introduces
no optimizer, production map implementation, endpoint exporter, MRI fit or
qualification result. R02's production full-boundary contract remains a
separate requirement.

Run analytic verification with:

```sh
python -m unittest discover -s benchmarks/halfflow/boundary -p 'test_*.py' -v
```

`study_query_correction.py` retains the freeze, bounded worker and compact
archive commands. Reproduce a new corrected declaration with a fresh study,
the original 48-field audit, previous inverse-resolution study, passing check
log and preserved initial validation failure. Archive the declaration before
launch. Original MRI/dense fields remain local and must match their recorded
hashes; all scalar reports, source bytes and attempt failures are archived.
