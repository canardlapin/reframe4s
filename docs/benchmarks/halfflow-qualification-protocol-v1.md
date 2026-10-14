# HalfFlow qualification protocol

This prospective contract governs the T1 brain registration qualification epic
`bd-01M4X4DHZEPAVKJ7DE94ET3Z21`. The machine-readable rules are in
[protocol-v1.json](../../benchmarks/halfflow/qualification/protocol-v1.json).
All numerical limits below are qualification targets, not achieved results.
The candidate, independent cohort manifest and executable analysis must be
sealed under Q02 before any qualification outcomes are inspected.

## Required outcome

HalfFlow must complete accepted nonlinear registration on at least 95% of all
declared pairs, approach an independently audited same-affine SyN reference
within 0.02 mean Dice and 5% HD95, improve over affine in aggregate, and satisfy
all existing numerical geometry contracts. Every cohort and the worst cases
must meet the additional rules in the JSON. At least 100 pairs, 60 unique
subjects and three independently sourced cohorts are required.

A process exit, successful export or exhausted accepted-step budget does not
establish convergence. The current engine's `AcceptedStepBudget`,
`AttemptBudget` and `ControllerExhausted` statuses cannot satisfy the new
convergence requirement. O02 and U01 must supply a truthful image-only stopping
criterion. Historical results retain their original interpretation.

## Data separation and uncertainty

Build subject-disjoint blocks of two to four people within each cohort and
register every unordered pair in each block once. Use a deterministic direction
and evaluate both maps; reversing a pair is not another independent case.
Require at least 30 blocks overall and eight per cohort. Subjects cannot occur
in different blocks, cohorts or development and qualification partitions.
P03 selects the actual manifest from acquisition metadata before outcomes.

Bootstrap whole blocks within each cohort, preserving all their pairs,
directions and methods together. This retains the dependence caused by shared
subjects. Equal-weight the cohort means after averaging pairs within each
cohort. Use 10,000 replicates, NumPy PCG64 seed 20261014 and linear percentile
interpolation. The one-sided 95% bound must exceed -0.02 for the Dice difference
and remain at or below 1.05 for the HD95 ratio. Improvement over affine requires
strictly positive Dice gain and a ratio below one with the same confidence rule.
Report descriptive two-sided intervals, counts and cohort-specific estimates.

Resampling clusters preserves their internal observations, rather than treating
those observations as independent. The chosen subject-block design applies
that principle to registration pairs. Before qualification, simulation checks
must assess the actual analysis under shared-subject correlation and missing
outputs. Inference remains conditional on the named cohorts; three datasets
do not establish universal site generalization. See the methodological
[cluster bootstrap paper](https://doi.org/10.3758/s13428-019-01252-y).

## Anatomical scoring and failures

Freeze each cohort's label list and each pair's expected native labels before
fitting. Require at least 90% of the cohort list in each native image; record
all omitted labels. Evaluation labels never define optimization masks or
features, select parameters or choose runtime checkpoints. Nearest-neighbor
label sampling and the existing physical surface-distance convention remain
fixed. A label disappearing after warping cannot leave the denominator.

For each direction, average label Dice and take the median label HD95. Average
the two directions, then pairs within cohort, then cohorts equally. Publish
per-label values and the mean label HD95 as supplementary results so the
median cannot conceal a failing structure.

An upstream affine failure, crash, timeout, rejected map, fallback, unproven
convergence or incomplete anatomical score receives Dice zero and HD95 100 mm
for the entire pair in the primary comparison. Both methods use this rule;
valid HD95 measurements are never capped. Retain actual scores and failure
reasons separately. Complete-case and actual fallback-utility results are
supplementary and cannot rescue a failed primary result. At least 95% of the
reference results must also pass common admission, or the competitive claim
is inconclusive.

The 95% completion target is a floor. Failure penalties can still defeat the
anatomical criterion when that floor is met. Report the observed proportion
and its interval; a 95% observed rate does not establish a 95% population lower
bound. Exact ties remain failures in historical strict-improvement regressions.

## Numerical geometry and export boundary

Preserve native residual inverse limits of 0.2 mm and 0.2 voxel over the
recorded six-voxel interior, composed-map brain round-trip maximum of 0.2 mm,
coverage of at least 0.99, minimum sampled cell Jacobian of 0.05 and zero sampled
folded cells. Independently evaluate every complete cell in both saved maps
at its eight corners and center and retain the existing voxel-difference
checks. Invalid samples remain in coverage denominators. Reloaded image error
must meet the existing relative tolerance of 1e-6.

The full export boundary remains an additional unresolved contract in
`bd-01M2EG25H20DR7CXNHK22ZF4VE`. Its original C04 failures must be addressed
before G3/Q02; an interior check cannot close that issue. Preserve whole-field
and boundary diagnostics without eroding the domain or weakening thresholds.
Finite cell samples do not prove continuous diffeomorphism or analytic inverses.

## Controls and current source

The retained development control uses 20 accepted updates per level,
1.5 mm accumulated smoothing, a 6 mm boundary taper and the existing coarse/fine
plan. Step-dependent accumulated smoothing remains disabled. The full plan,
preprocessing and affine recovery policy are pinned in the JSON.

Historical source `7165f52a…` and its archived executable retain their original
provider versions. The worktree now pins newer providers. The
[control seal](../../benchmarks/halfflow/qualification/control-seal.json)
records actual source bytes, provider identities, five observed control pairs,
saved affines, annotation hashes and environment. A current-source rebuild or
replay is new development evidence; it does not replace an old receipt.

P02 must audit the existing SyNOnly reference on the same saved affine and
native prepared inputs, including all twelve affine coefficients, pull-map
conventions, independent geometry and image reload. It will compare a generous
convergence schedule and controlled compute budgets using observed data,
then freeze the strong primary reference before qualification.

## Development budget and practical limits

Allow at most 12 candidate policies and 120 full development fits under this
protocol. Checkpoint replays are counted separately. D01 declares the diagnostic
panel before new measurements; D03 specifies hypotheses and stopping rules.
Additional features require a measured failure mechanism.

Timed comparisons use one native thread, one fit at a time, a 6 GB Java heap,
a 12 GiB peak process-memory limit and a one-hour fit/export/admission deadline
per method and pair. Median end-to-end runtime must be at most twice the strong
reference on the same named hardware. Downloading, building and label-only
evaluation are excluded; initialization and required admission are included.
Report cold start separately. These are prospective practical targets;
P02/U02 must measure them before candidate freeze.

## Change control and completion

Never overwrite historical protocols, maps or decisions. Before Q02, any
necessary design correction gets a new immutable version and a Mote rationale.
After qualification outcomes are inspected, changing the method, selection
policy or numerical acceptance limits consumes that holdout and requires new
independent confirmation. Q04 independently recomputes the decision.

P01 freezes this contract and a reproducible development control. P02 owns
reference auditing, P03 the actual independent dataset manifest, and Q02 the
final executable/analysis seal. Completing P01 does not pass those gates or
admit HalfFlow to the stable foundation.

## Verification and reproduction

Validate the prospective contract and archived source with:

```sh
python3 benchmarks/halfflow/qualification/contract.py protocol
python3 benchmarks/halfflow/qualification/contract.py control
python3 -m unittest discover -s benchmarks/halfflow/qualification -p 'test_*.py' -v
```

On the original data host, add `--local` to the control command to verify all
40 prepared-input/annotation/affine/report identities and all 6,496 frozen
compiled files. The source archive contains 356 files, including existing
uncommitted numerical work as it actually stood at the rebuild. It is distinct
from the surrounding Git commit. Canonical dependency commits are recorded in
the seal; all four observed provider checkouts were clean.

The initial current-provider build passed 15 engine, 11 export and six controller
JVM tests and compiled the real-image runner. The build log is hash-bound in the
seal. Nine Python tests exercise the data-design and threshold checks. This
receipt establishes build/identity checks, not an anatomical replay or a
Scala.js qualification of this candidate.

The local frozen run is
`benchmarks/flashalign/local-results/hfq-control-20261014`. It contains
`protocol.json`, frozen source/classes, `classpath.txt` and input manifests.
The five prepared cases and saved affines are listed in `control-cases.json`.
Large MRI and class binaries remain outside Git. Reproduction on another host
requires materializing the hash-matched inputs, rebuilding the archived source
against its exact providers, and recording the new executable/environment
identities. Do not edit absolute paths inside an old receipt and claim its
identity was preserved.

For a new control freeze, export the runner runtime classpath in a successful
build log and invoke `freeze_control.py --build-log LOG --hardware HARDWARE_JSON
--run NEW_RUN_DIRECTORY` from a copy of the archived source. Existing seals
and run destinations are deliberately refused. Future control versions must
use a new artifact directory and retain this version.

The design validator checks identities and combinatorics, not source independence,
raw map geometry or statistical coverage. P03 must audit provenance, P02 the
independent map evaluator, and Q02 the executable statistical analysis and its
simulation checks before the qualification can run.
