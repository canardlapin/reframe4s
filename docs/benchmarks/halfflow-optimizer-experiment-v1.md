# HalfFlow spatial-direction experiment

Status: opt-in implementation tested and frozen; no acquired-image candidate fit has run.
This is a bounded development experiment under HFQ-D03/O01. It does not close
those issues or qualify HalfFlow. Execution waits for the outstanding acquired
grid/boundary checks, completed trajectory analysis and the P02 comparison.

## Evidence and hypothesis

The [pyramid audit](halfflow-pyramid-chain-v1.md) finds a mismatch between the
production gradient and finite differences of the actual sampled objective.
The diagnostic chain through source interpolation and residual composition
agrees at sufficiently small steps on the five observed states. Its original
larger-step failures remain recorded. The
[coordinate audit](halfflow-coordinate-audit-v1.md) rules out a simple image-role
sign error on its three saved grids.

The complete OASIS 11 step-scaled trajectory remains a negative anatomical
result: fine refinement lowers the fixed-region intensity loss from 0.650371
to 0.626561, while Dice falls from 0.422652 to 0.413321 and median-label HD95
rises from 5.246936 to 5.404319 mm. Geometry passes at all scored checkpoints.
This falsifies the idea that intensity improvement alone demonstrates better
anatomy. It does not establish whether the spatial derivative, regularization,
optimizer or anatomical ambiguity of the data term is the principal cause.

The next hypothesis is narrow: replacing the spatial direction alone improves
anatomical endpoints under the existing continuous, step-scaled candidate
operation. A negative result stops expansion of this candidate; it does not
erase its numerical derivative evidence.

## One changed factor

The experimental `--spatial-chain-gradient true` flag invokes a package-private
optimizer entry point. Ordinary calls and the runner's default use the existing
gradient. The experiment requires true neighborhood CC and either no accumulated
smoothing or step-scaled accumulated smoothing. It rejects fixed-strength
accumulated smoothing because that candidate operation does not approach the
current state as its attempted step tends to zero.

For frozen intensity derivatives `alpha_f` and `alpha_m`, let `h_f(x)` and
`h_m(x)` map physical work coordinates through each residual and arm affine into
its source voxel coordinates. The symmetric direction is

`g(x) = 0.5 alpha_f(x) Dh_f(x)^T grad I_f(h_f(x)) − 0.5 alpha_m(x) Dh_m(x)^T grad I_m(h_m(x))`.

The source derivative is that of its trilinear interpolant. Residual derivatives
at work-grid vertices use symmetric slopes. The direction has units of loss per
millimetre. At interpolation knots this is a symmetric directional construction,
not a claim of a globally differentiable objective. Fixed-anchor diagnostics
use only the negative moving term; the acquired-image experiment remains
symmetric midpoint.

The existing pointwise update remains

`v_i = −s g_i / (lambda + ||g_i||² / (2 E + epsilon))`.

Velocity is in millimetres. The existing support-normalized Gaussian smoothing,
physical boundary taper, maximum-step scaling, paired integration, candidate
composition, step-scaled accumulated filtering, geometry checks, frozen CC
acceptance and weight rebuild remain unchanged. This isolates the direction;
it does not make the rank-one approximation an established curvature model or
turn accumulated filtering into an explicit energy penalty.

Every nonzero intensity derivative needs valid source-chain support. Missing
support, nonfinite values or a nonzero direction outside the smoothing support
fail explicitly before the output buffers change. No fallback to the production
gradient and no invented zero derivative is allowed.

## Bounded acquired-image screen

The two lanes are the existing `step-scaled-fine1.5` policy and exactly that
policy with the spatial-chain flag enabled. The selected P01 control remains a
reported contextual comparator; the isolated causal comparison is between the
two step-scaled lanes. Parameters stay at 20 accepted updates per level,
34 attempts per level, 1.5 mm accumulated smoothing, 6 mm boundary taper and
the existing coarse/fine CC, velocity smoothing and integration settings.

Declare six previously observed pairs before any candidate outcome:

- Refinement sentinels: OASIS 11 → 12, OASIS 13 → 14, MMRR 11 → 12.
- Prior selected-policy successes: OASIS 1 → 10, OASIS 15 → 16, OASIS 17 → 18.

OASIS 15 is the third success required by P01's development screen. Its existing
selected-policy recovery receipt records positive Dice and HD95 changes in
both directions versus the saved affine; selection uses that historical
receipt, before any new candidate fit. This adds a development case, not a
qualification subject. It was not part of the ten-fit trajectory panel.

The screen adds **one candidate policy and twelve full fits**: six baseline
replays and six candidate fits. Charge all twelve against the 120-fit
development ceiling. Record any failed or interrupted attempt in that budget;
do not replace a case or increase its iterations after seeing the outcome.
Freeze executable, inputs, evaluator, protocol and endpoint rules before launch.
Existing baseline maps must replay exactly where a historical baseline exists;
the newly introduced OASIS 15 step-scaled baseline is marked as having none.

Use only the final result from each unchanged stopping policy. Keep rejected
candidates, failed exports and nonconvergence explicit. Anatomy is evaluated
after fitting and never selects a runtime checkpoint. No labels or evaluation
masks enter fitting. Report each direction, pair, structure and complete attempt
denominator, with the fixed-region intensity audit and deformation diagnostics.

Expansion requires all six candidate fits to complete with admitted maps and
unchanged independent geometry gates, positive mean Dice change and negative
mean HD95 change against the replayed step-scaled baseline, and no pair losing
more than 0.005 Dice or worsening HD95 by more than 0.1 mm. These are prospective
development stop rules, not confidence bounds or replacements for qualification
criteria. If any condition fails, preserve the result and stop this candidate's
expansion. Capped/controller-exhausted fits remain nonconverged even if anatomy
improves; they cannot pass the qualification convergence requirement.

## Separate regularization and stopping work

O01's explicit energy is a separate experiment. It must specify a deformation
penalty in physical coordinates, derive its contribution through the same
candidate action, and include it in acceptance. Gaussian operations need a
stated role: preconditioning, a justified proximal operation, or a constraint.
Adding a penalty only to reported loss would not meet this requirement.

O02 then compares a simple consistent descent baseline with the rank-one update
under that same energy. It needs image-only progress and physical-step stopping
criteria, explicit stall/budget outcomes and an independent convergence check.
No new regularization coefficient, optimizer family or stopping tolerance is
selected by this direction experiment.

## Tested source and reproduction

The [declaration archive](evidence/halfflow-spatial-direction-20261014/declaration-v1/artifact-manifest.json)
freezes source `4c3bed8ae13b67b453f570ff8a9923ab924658637d04161e7e9d9f044bb0a9b1`.
Its [five-file patch](evidence/halfflow-spatial-direction-20261014/declaration-v1/source.patch)
reconstructs exactly from coordinate-audit source
`890f7b4d221d2091b1c30091f36e46c8ee86f559a908a148593a3dc8ebcc64c3`;
the archive helper verified that reconstruction in a scratch directory.
The working checkout contains earlier uncommitted prerequisites, so the patch
chain is the reviewable source artifact. Unrelated working changes are excluded.

The [build receipt](evidence/halfflow-spatial-direction-20261014/declaration-v1/build-verification.json)
records 30 passing tests on JVM, the same 30 on Scala.js, four runner tests and
four process-monitor tests. Each platform includes five new direction checks:
independent source-cell finite differences for both actions, missing support,
support-weight disagreement, actual optimizer descent with exact observation
neutrality, and rejection of discontinuous accumulated smoothing. Existing
engine, coordinate-equivariance and observation checks also pass. The initial
build's unused-parameter warning failure and the successful corrected build
are both retained. These checks do not establish acquired-image anatomical gain.

Reconstruct the [coordinate engine](halfflow-coordinate-audit-v1.md), apply this
patch, and run `HalfFlowCcEngineSuite`, `HalfFlowSpatialChainDirectionSuite`,
`HalfFlowCcEquivarianceSuite` and `HalfFlowCcObservationSuite` on JVM and Scala.js.
Run `SpatialChainOptionsSuite` and `RealImageObjectiveAuditSuite` in the benchmark
runner. `freeze_spatial_direction.py` verifies the passing logs and exact source
delta, freezes the compiled executable and packages the declaration; it does
not launch any fit. Supply a fresh `--output`, the design as `--protocol`, the
reconstructed coordinate engine as `--base`, a newly built `--classpath`, the
passing log directory as `--checks`, and a fresh `--archive`. The helper source
and frozen classpath/input manifests are retained with the archive.

The [declared design](evidence/halfflow-spatial-direction-20261014/declaration-v1/declared-design.json)
still requires the outstanding D01/D02/P02 evidence and a separate final freeze
of all input/evaluator identities and twelve attempt rows before execution.
No candidate MRI fit or runtime comparison is claimed by this package.

## Executable comparison declaration

The [execution package](evidence/halfflow-spatial-direction-20261014/execution-v1/artifact-manifest.json)
now seals all twelve ordered fit commands, the existing frozen executable and
input identities, evaluator sources, Java executable identity and the original
six-pair design. `spatial_direction_study.py` provides preparation, verification
and sequential execution. It uses the already-tested source `4c3bed8a…`; no
new Scala change or build is involved. Labels remain evaluation-only inputs.

Execution requires D01, D02 and P02 to be explicitly closed in Mote after their
evidence is reviewed. A launch check against their current state correctly
refused before creating any fit launch record. All twelve fits remain
unstarted. This package neither waives those gates nor closes their issues.

Seven focused checks cover the decision rules. A missing or failed attempt,
native rejection, independent geometry failure, nonfinite metric or baseline
replay mismatch prevents expansion. Average gains cannot hide a pair exceeding
either declared regression limit. The summary keeps all twelve planned rows
and does not compute a complete-case mean when any pair is incomparable.
Cap/controller stops never become qualifying convergence. Each actual launch
is charged to the full-fit budget and retained, including interruptions;
the driver refuses an automatic rerun of a previously launched study.

The fit, export and anatomy execution path has not yet run on MRI through this
new driver. Its scientific components are the existing frozen registration
and evaluators, but passing the decision-rule tests is not an end-to-end result.
