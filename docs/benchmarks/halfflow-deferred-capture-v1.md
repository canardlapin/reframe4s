# HalfFlow checkpoint export and fine-level anatomy loss

The revised diagnostic capture reproduces the original control exactly while
moving checkpoint export out of fitting. Separately, the completed scoring of
one timed-out trajectory shows that fine refinement can improve both intensity
objectives while worsening anatomical overlap. Neither finding qualifies
HalfFlow or establishes an anatomical improvement.

This is HFQ-D01 evidence for Mote `bd-01M4X4DY7DQCVK33MCK3T17NA1`.
The [evidence manifest](evidence/halfflow-deferred-capture-20261014/artifact-manifest.json)
retains protocols, receipts, failed attempts, source patches, checks and figures.
Large MRI and map arrays remain local with recorded hashes.

## Capture neutrality

The original synchronous-export panel ended with one successful fit, two
one-hour timeouts and seven explicit cancellations. Its entire attempt ledger
remains in the [objective-probe archive](halfflow-objective-probe-v1.md).
The replay retains the same five pairs, two existing policies, checkpoint
selection, 24-state budget and one-hour per-fit deadline.

An explicit `--checkpoint-defer-export true` option writes raw midpoint maps,
validity, affine factors and scalar events during optimization. Each saved state
is marked as awaiting export. Ordinary final export and admission still occur
inside the fit deadline. Offline checkpoint exports have their own frozen
inputs, process measurements, deadlines and receipts in a separate directory.

Before expanding the replay, the OASIS 11 → 12 selected control passed the
[complete neutrality check](evidence/halfflow-deferred-capture-20261014/trajectories-deferred-v2/neutrality-verification.json):

- All 30 scalar events and capture selections agree exactly.
- All 13 raw states agree exactly across 130 saved artifacts.
- All 15 compared final files agree exactly, including optimizer diagnostics,
  both native pull maps, validity, warped images and residual maps.
- All 20 offline-export artifacts agree exactly across the original coarse-start
  and fine-start checkpoints, including native admission status and diagnostics.

The fit exited successfully after 1,196.27 diagnostic wall seconds. Concurrent
work makes this unsuitable for a runtime comparison. Its optimizer still ends
with the original accepted-step budget/controller exhaustion behavior; this
replay does not turn that behavior into convergence.

## Independent scoring after offline export

The separate scoring path now verifies the frozen capture, export engine,
raw-state inputs, per-export receipt, output hashes and native admission before
using an offline map. Admission comes from the export result; the raw capture's
"deferred" status does not admit a map. A pending export receives no score,
while process failure, construction failure and geometric rejection remain
distinct. The analysis protocol retains every selected source fit, including
failed fits with no events. An exported checkpoint cannot change its source
fit's outcome.

Eleven focused provenance and failure tests pass. On the original coarse-start
and fine-start real checkpoints, the
[scoring parity check](evidence/halfflow-deferred-capture-20261014/offline-scoring-supplement-v1/offline-analysis-parity-v1/parity-verification.json)
reproduces all regularity, fixed-intensity, geometry and anatomy numbers exactly.
All six complete evaluation, per-structure and sampled-cell reports are
byte-identical. Only elapsed audit times and relocated paths to identical
hashed inputs are excluded from numerical equality. All 30 original scalar
events remain represented: two scored states, 17 uncaptured events and 11
states outside the explicitly limited parity-export plan.

The OASIS 11 → 12 step-scaled replay has also completed, retaining 16 raw
checkpoints and matching all four historical final map/validity files exactly.
Its successful process exit does not establish optimizer convergence. A frozen
first batch now includes every captured state from both OASIS 11 policies:
29 separate export attempts followed by independent scoring. Explicit terminal
fit batches allow this work to proceed while other fits run; omitted panel fits
remain outside the batch and in the full panel's original denominator.

The [scoring supplement](evidence/halfflow-deferred-capture-20261014/offline-scoring-supplement-v1/artifact-manifest.json)
archives the completed parity evidence, tests, helper sources, source-fit
receipts and immutable first-batch declarations. It does not claim that this
29-state batch or the full ten-fit panel has completed. Two orchestration launch
errors are also retained; neither started a scoring or export process.

## Preserved failures and correction

The first offline-export attempt failed exact parity on both checkpoints.
Maximum forward-coordinate differences were `1.09e-12` and `1.35e-12` mm;
backward maps and validity were unchanged. The original midpoint initializer
constructs affine arms as `(root.inverse, root)`. The canonical affine stores
both directions, so independently re-inverting the serialized fixed arm loses
the exact cached root through floating-point rounding.

The corrected loader reconstructs the original pair from the moving root and
requires exact agreement with every saved fixed-arm coefficient. An inconsistent
pair is rejected. Both checkpoint exports then pass byte equality without a
tolerance change. A focused regression checks both cached affine directions;
the grid test also checks oblique coefficients and the native live frame owner.

The initial ten runner/loader/grid checks passed; both focused affine/grid
checks passed after the correction. The archive also retains an sbt launch
failure caused by cache permissions and an aborted freeze caused by the Git
file list exceeding Node's default output buffer. Increasing that buffer changes
neither source selection nor registration numerics.

## A fixed intensity audit can improve while anatomy worsens

All 42 committed events from the original OASIS 11 → 12 step-scaled run have
analysis receipts. Fourteen states were captured and scored; every scored state
passes the current native/common geometry and sampled-cell checks. The source
fit timed out and remains a failed fit. Its incomplete, uncommitted step-20
directory is not promoted to a captured state or a final result.

| Fine accepted steps | Optimizer rebuilt loss ↓ | Independent fixed-audit loss ↓ | Mean bidirectional Dice ↑ | HD95 (mm) ↓ |
| ---: | ---: | ---: | ---: | ---: |
| 0 | 0.529247 | 0.650371 | 0.422652 | 5.246936 |
| 1 | 0.528819 | 0.650137 | 0.422615 | 5.246936 |
| 2 | 0.527537 | 0.649676 | 0.422617 | 5.277353 |
| 5 | 0.512420 | 0.644460 | 0.422738 | 5.315000 |
| 10 | 0.469591 | 0.633549 | 0.419009 | 5.404319 |
| 15 | 0.449892 | 0.629331 | 0.415789 | 5.404319 |

The independent audit retains its fixed target masks and window population;
both directions have full window coverage at these states. Its loss improves
alongside the optimizer loss while Dice declines after step 5. Changing
optimizer weights alone therefore cannot explain this case's anatomical loss.
The lower displacement-gradient norm during fine refinement also cautions
against simply describing this as excessive deformation complexity.

![All committed scalar events and scored states](evidence/halfflow-deferred-capture-20261014/partial-step-scaled-figures-v2/trajectory.png)

The [per-structure figure](evidence/halfflow-deferred-capture-20261014/partial-step-scaled-figures-v2/per-structure.png)
retains all 62 labels in both directions. These observed labels diagnose the
trajectory; they do not select a stopping point or an output for deployment.
This single partial trajectory does not establish a population-level effect.

The [full partial-panel scoring supplement](evidence/halfflow-deferred-capture-20261014/partial-panel-analysis-supplement-v1/artifact-manifest.json)
now accounts for all 124 committed events across the original ten fit attempts:
47 captured states were scored and 77 events requested no retained state.
There are no analysis/export-unavailable failures among those committed states;
all 47 pass the current common geometry and sampled-cell checks. The two timed-out
and seven cancelled fits retain their original failed outcomes. Five cancelled
fits produced no committed events and remain in the fit-attempt denominator.

## Reproduction and remaining work

The archive contains independently verified source patches against the frozen
D01 engine. Reconstruct that engine from P01's `control-source.tar.gz` plus the
[D01 instrumentation patch](evidence/halfflow-diagnostics-pilot-20261014/instrumentation.patch),
then apply the appropriate new patch. The capture build is
`57e03c8b5d3f0af6f65c38e3603f811292c1715065b8127afc12b1e9f8884ad6`;
the corrected offline-export build is
`a2b5ddf1ce9da82ed4f4b59ee9aa488466baf69a264d46938cb962cf188d146a`.
They have separate frozen classpaths and manifests.

Use `trajectory_study.py freeze --prior-study ...` for deferred capture,
`export_engine.py` for a separate offline build, `offline_checkpoints.py` for
export receipts and `verify_deferred.py` for exact pilot neutrality. Use fresh
directories and the pinned Python/Scala environments. Captures, failed attempts
and published archives are not overwritten.

For a terminal fit batch, repeat `offline_checkpoints.py freeze --fit pair/policy`.
Freeze its scorer with `checkpoint_analysis.py freeze --offline-exports ...`,
then run the scorer as exports finish. It skips pending exports without writing
terminal analysis receipts; rerunning scores those states once their receipts
exist. `verify_offline_analysis.py` reproduces the two-state scientific-score
parity check. The full panel denominator remains defined by the trajectory
protocol, independently of batch boundaries.

The remaining declared replays are running sequentially. Full offline export,
scoring and review of that panel remain required for D01. The separate
[derivative-chain investigation](halfflow-intensity-chain-v1.md) localizes the
pilot's discrepancy to spatial/composition differentiation; its broader checks
remain open, and a corrected derivative alone has not yet been shown to improve
anatomy. No fresh qualification data, new optimizer policy or relaxed geometry
threshold was used here.

## Exact event accounting and the next offline batch

`verify_offline_ledger.py` checks the complete expected event identities, their
committed capture metadata, preserved source-fit outcomes, export receipts,
map hashes and admission status. It also compares the final export ledger with
every individual attempt receipt. A matching event count alone cannot establish
completion: four regression tests cover missing, duplicated, substituted and
mutated events. `--require-complete` exits 2 for incomplete accounting; export
failures and rejected maps remain explicit even when accounting is complete.

The [real-data completion-gate checks](evidence/halfflow-deferred-capture-20261014/offline-ledger-supplement-v1/runtime-verification-v1/artifact-manifest.json)
also pass: the completed two-state parity pilot accounts for all 30 events and
exits 0, while the still-incomplete 74-event batch exits 2 under
`--require-complete`. The pilot explicitly retains its 11 captured states
outside that two-state export plan, rather than counting them as scored.

The [recorded partial audit](evidence/halfflow-deferred-capture-20261014/offline-ledger-supplement-v1/verification/partial-verification-v2.json)
contains 55 of 74 expected OASIS 11 events: 10 scored checkpoints and 45 events
without captured states. It records 19 missing events and no terminal export
ledger. This timestamped snapshot is incomplete, and does not describe a final
scientific result. Its receipt hashes preserve exactly which events were checked.

The next [export declaration](evidence/halfflow-deferred-capture-20261014/offline-ledger-supplement-v1/offline-panel-oasis13-mmrr11-v1/protocol.json)
and [analysis declaration](evidence/halfflow-deferred-capture-20261014/offline-ledger-supplement-v1/offline-analysis-oasis13-mmrr11-v1/protocol.json)
cover both policies for OASIS 13 → 14 and MMRR 11 → 12. All four source fits
terminated with exit 0. Every captured state is included: 61 exports from
162 total events, with the other 101 explicitly uncaptured. Source exit 0
does not establish convergence. The batch is prepared for sequential export
after the existing worker; no outcomes from this next batch are included in
the [supplement](evidence/halfflow-deferred-capture-20261014/offline-ledger-supplement-v1/artifact-manifest.json).
