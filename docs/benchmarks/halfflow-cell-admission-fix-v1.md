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

Exact native/independent parity and the prospectively declared six-case replay
remain pending in this declaration. Terminal outcomes will be recorded separately.

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
The existing MRI checkpoint export/scoring batch continues under its previously
frozen executable; those measurements are separate from this new source.
The remaining 58 captured checkpoints from OASIS1-to-10 and OASIS17-to-18
now have frozen export and independent scoring plans. They use the existing
`a2b5ddf1…` export engine and remain unlaunched until the declared scheduling
and reference-isolation order permits them.

The next optimization screen remains gated by D01, D02 and P02. A future MRI
comparison using this correction needs a newly identified executable and a
prospective protocol; the earlier frozen spatial-direction baseline is not
silently replaced.
