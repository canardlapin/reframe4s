# HalfFlow complete-cell admission correction

HalfFlow now checks the trilinear coordinate map before accepting an update.
The previous voxel-difference check could report positive Jacobians while an
outer complete cell reversed orientation. The
[boundary diagnosis](halfflow-boundary-representation-v1.md) established that
failure on the historical r24 residuals with independent derivatives away
from interpolation knots.

The correction evaluates world-coordinate determinants at each complete cell's
eight one-sided corners and center. Every cell in the sampled grid is eligible,
including the outermost cells. All eight vertices must have valid support;
nonfinite coordinates or determinants explicitly fail admission. The existing
Jacobian floors and valid-fraction thresholds apply to the cell report as well
as the retained voxel report. Interior margins cannot hide cell failures.

The shared incremental and accumulated gates cover HalfFlow-CC and BasinBridge.
The compatibility pair guard also checks cells. Direct export construction
rejects folded authoritative forward states, and endpoint admission recomputes
cell topology from the candidate maps, including copied candidates. No trial,
iteration, inverse-error or convergence budget is increased.

Nine samples per cell are sampled evidence; they do not certify positivity
everywhere within a trilinear cell or global invertibility. Export still
requires its separate inverse and topology checks.

## Verification and changed regression outcomes

The affected geometry, export, CC, BasinBridge and registration-algebra
suites pass all 66 tests on each of JVM and JavaScript. Nine independent Python representation/root-oracle
tests also pass. New cases cover oblique anisotropic and left-handed grids,
hidden boundary folds in either arm, the existing positive Jacobian floor,
invalid-corner coverage, masked nonfinite data, grids without complete cells,
manual forward-state export, copied endpoint maps and the legacy pair guard.
Ownership validation verifies 66 canonical owners and unique ownership of
1,499 public qualified names against the clean exact dependency revisions
selected by the pinned build. The initial default-path check selected unrelated
sibling development checkouts; its failure and corrected provider selection
are retained with the checks.

The existing large-motion tests expose the practical effect of the correction:

- A constant 12 mm BasinBridge tangent rejects two integration trials with
  complete-cell minimum Jacobian −0.078125, then accepts alpha 0.25. Its match
  error remains approximately 9 mm. A safe partial step is not a solved match.
- The compression example rejects all four originally declared trials and
  retains the initial state. Its old interior-only acceptance was unsafe.
- The 12 mm CC translation's final forward maps pass the cell floor, but export
  still fails the endpoint inverse bound. That remaining failure is retained;
  it is not converted into an accepted result.

The native evaluator matches the independent Python evaluator on all 48
previously captured fields: cell counts, coverage, folded-cell counts and
pass/fail verdicts agree exactly. Determinant extrema agree within the
prospective `1e-10` absolute/relative tolerance. Every independently failing
field is rejected by the shared forward gate, and all positive C5 controls
pass. This checks all 2,239,488 complete cells across the field ledger.

## Complete six-case replay

The [terminal replay and all-field audit](evidence/halfflow-cell-admission-20261015/outcomes-v1/artifact-manifest.json)
contain all six original cases, both lanes and all 48 forward/inverse fields.
All source and input hashes remained unchanged during replay. Every field has
46,656 eligible complete cells and 100% coverage.

| Field group | Fields | Folded fields before | Folded fields after | Cell passes after |
| --- | ---: | ---: | ---: | ---: |
| C4 forward | 12 | 9 | **0** | 12/12 |
| C4 stored inverse | 12 | 3 | 1 | 11/12 |
| C5 forward | 12 | 0 | 0 | 12/12 |
| C5 stored inverse | 12 | 0 | 0 | 12/12 |

The minimum C4 forward cell Jacobian rises from −1.556543 to 0.128891, above
the unchanged 0.05 floor. Both r24 forward arms now pass: fixed minimum
0.484742 and moving minimum 0.390295. All 24 C5 field hashes, all six C5
endpoint-map hashes and all six C5 landmark RMS values match the prior replay
exactly.

The comparison uses the previous canonical-provider capture, `c04-capture-v4`.
Its C4 fields replayed the historical hashes exactly. Its C5 fields were current
canonical controls and differed from the original historical C5 capture;
the unchanged-control result here refers specifically to that prior replay.

Export admission remains 0/6 for C4 and 6/6 for C5. The remaining r19 fixed
stored inverse has one folded cell, minimum −0.014432. The new endpoint cell
check catches a corresponding exported-map fold, minimum −0.023200, despite
positive voxel-difference Jacobians. The other five C4 cases fail endpoint
inverse bounds. The runner's C5 `success` label records admission; this replay
does not establish qualifying convergence.

## Corrected r24 inverse representation

The [corrected r24 root declaration](evidence/halfflow-cell-admission-20261015/corrected-roots-declaration-v1/artifact-manifest.json)
repeats the original two-arm, two-inverse-grid diagnosis after the complete
replay and independent cell audit. Both original compositions
keep all 50,653 target points; the 40 iterations, 4 mm step cap, 12 backtracks
and `1e-8` mm equation tolerance remain fixed. The numerical arm-analysis loop
and imported field-reader utilities are byte-identical to the original helper.
The added mode changes corrected-capture eligibility and historical attribution.

| r24 arm | Previous stored inverse, worst whole-field cycle | Corrected stored inverse | Solved roots stored on original grid, opposite cycle | Padded-grid unresolved roots |
| --- | ---: | ---: | ---: | ---: |
| Fixed | 3.417999 mm | 0.465460 mm | 0.756696 mm | 21/68,921 |
| Moving | 8.086230 mm | 0.388483 mm | 0.605013 mm | 21/68,921 |

All 50,653 original-grid roots converge in both arms, with equation residuals
below `1e-8` mm and positive sampled root Jacobians. Direct solves at all
forward-node queries return the original coordinates within `2.1e-8` mm.
Storing those roots on the original lattice still fails the opposite cycle;
it also increases its worst error relative to the current balanced refiner.
The declared 41³ padded grid leaves the same worst original-domain cycle
errors and adds 21 unresolved exterior roots per arm. Every original-domain
composition still evaluates all 50,653 points, with zero invalid points.

Thus the forward-fold correction substantially reduces this r24 inverse error,
while the 0.2 mm full-boundary contract remains unmet. This experiment supports
investigating a richer inverse representation and its interpolation error.
Pointwise root convergence and additional exterior support alone do not pass
the contract.

## Source and evidence delivery

The [prospective declaration](evidence/halfflow-cell-admission-20261015/declaration-v1/artifact-manifest.json)
includes protocols, source identities, test logs, failed preparations and the
exact nine-file correction. Reconstruct the existing `5b792b86…` source using
the [acquired-grid reproduction chain](halfflow-acquired-grid-audit-v1.md), then
apply the [verified source patch](evidence/halfflow-cell-admission-20261015/declaration-v1/source.patch).
Scratch application must reproduce every declared changed-file hash; all
other prerequisite source identities are retained.

## Scope and next work

This fixes an admission defect. It does not yet establish acquired-MRI accuracy,
full-boundary inverse accuracy, convergence or qualification. R02 remains open.
The added gate's runtime cost still needs measurement under isolation.
The existing MRI checkpoint export/scoring batch continues under its previously
frozen executable; those measurements are separate from this new source.
The [verified partial MRI snapshot](evidence/halfflow-cell-admission-20261015/offline-progress-v1/artifact-manifest.json)
now contains 54 scored states and 101 explicitly uncaptured events: 155 of
162 declared events. Seven captured states remain unscored, so the batch is
incomplete. All captured scientific reports and per-structure plots for those
54 states are retained.

Both completed OASIS13 fine-level trajectories still lose mean Dice from their
fine-level start to the twentieth accepted step: selected control
0.423217 → 0.420787, step-scaled policy 0.423353 → 0.421371. Both fixed-region
intensity losses decrease. This continues the existing anatomical-regression
diagnosis and does not measure the cell correction's acquired-MRI effect.

The remaining 58 captured checkpoints from OASIS1-to-10 and OASIS17-to-18
now have frozen export and independent scoring plans. They use the existing
`a2b5ddf1…` export engine and remain unlaunched until the declared scheduling
and reference-isolation order permits them.

The next optimization screen remains gated by D01, D02 and P02. A future MRI
comparison using this correction needs a newly identified executable and a
prospective protocol; the earlier frozen spatial-direction baseline is not
silently replaced.
