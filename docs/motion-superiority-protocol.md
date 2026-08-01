# Motion speed-and-accuracy superiority protocol

The normative protocol is
[`benchmarks/motion/protocol-v2.json`](../benchmarks/motion/protocol-v2.json).
It freezes the evaluation before the production optimizer or comparator
adapters can influence the endpoints, margins, sample, or pass rule.

The research target is deliberately stronger than “competitive”:
`reframe4s-motion` must demonstrate practically and statistically meaningful
superiority in both speed and accuracy against AFNI `3dvolreg` and nifreeze.
FSL MCFLIRT is a required context comparator. It cannot replace either
headline comparator or rescue a failed headline result.

The accepted claim is scoped to protocol-v2 workloads and the recorded
hardware. No result from this court supports “universally superior,” “best
motion corrector,” or a clinical claim.

## Two execution tiers

[`benchmarks/motion/execution-tiers-v1.json`](../benchmarks/motion/execution-tiers-v1.json)
separates routine development evidence from the release claim.

The laptop regression gate runs thirteen tests from four focused JVM suites.
It uses deterministic analytic arrays in memory. It downloads no data, starts
no external comparator, and writes no NIfTI image. The gate checks the
registered six-degree-of-freedom and capture thresholds, allocation ceilings,
and broad pair, application, and kernel throughput floors. It also enforces a
2 GiB JVM heap ceiling, a 512 MiB free-disk precondition, at most 256 MiB of
net repository build growth, a 2 MiB log, and a 256 KiB receipt.

Run it from the repository root:

```sh
node scripts/run-motion-laptop-gate.mjs
```

This gate has no claim authority. A passing laptop receipt means that the
candidate has not crossed these absolute regression limits on that machine.
It supplies no AFNI, nifreeze, or FSL evidence and cannot admit, weaken, or
replace any protocol-v2 endpoint.

The release superiority court remains a separate operation for a qualified
larger host. It requires the complete immutable admission lock, external
comparators, public data, stable hardware controls, and final attestation.
The resumable, content-addressed execution machinery is now implemented under
the separate
[`release-execution-v1`](../benchmarks/motion/release-execution-v1.json)
contract. Only an admitted run of that court can support the registered
speed-and-accuracy claim.

## Current gate

The protocol is frozen, but claim admission is intentionally blocked. A
structurally valid file is not yet an admissible benchmark. Before the first
candidate result is run, the environment-and-data lock must record:

- a clean candidate git revision and artifact checksums;
- the manifest digest for AFNI `AFNI_26.1.04` on `linux/amd64`;
- a fully hashed environment and image digest for nifreeze commit
  `a3985fe5a4763f0a2eafb24e78d88fcfad60992a`;
- an FSL `6.0.7.22` installation lock, `mcflirt` version output, and accepted
  non-commercial license terms;
- at least twelve lexically selected public real-anatomy subject-runs with
  checksums, provenance, and license constraints;
- the complete benchmark host, runtime, CPU, memory, filesystem, frequency,
  and thermal controls.
- locked evidence for allocated, requested, and effective worker counts at the
  one-core and four-core settings.

Two local Volregger inputs have byte checksums but no recorded provenance or
license. The protocol lists them explicitly as exploratory and rejects them
from claim evidence. Unknown provenance is a blocker, not metadata to fill in
after a favorable result.

Protocol v1 remains archived unchanged. Before any result was run, runner
construction exposed three contradictions: v1 simultaneously required a
fixed-frame native reference and nifreeze leave-one-out prediction, labeled
the serial candidate as four-threaded, and compared method-private estimation
timers that external tools cannot expose equivalently. Protocol v2 corrects
those design defects without changing workloads, seeds, margins, sample sizes,
hypotheses, or the complete pass rule. Validators and the runner now bind to
the v2 digest.

The registered candidate's native `Lanczos5` interpolation is implemented by
the canonical production resampler. This closes an implementation
prerequisite; it does not admit a claim. The external runner, comparator
installations, public data lock, and clean candidate release receipt remain
mandatory.

Expanding the frozen counts gives 57,600 fresh-process rows:

```text
144 workloads × 2 courts × 4 implementations × 25 runs × 2 core settings
```

The registered shapes average about 68 MB per corrected Float64 NIfTI before
gzip, so literal retention of every repeated output is about 3.9 TB. A single
GitHub job is not a credible solution: even on a self-hosted runner,
[its `GITHUB_TOKEN` expires after 24 hours](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idtimeout-minutes),
which can prevent the final attestation and upload. No court has run.
The execution-tier split keeps this workload off development laptops.
`release-execution-v1` now divides it into 900 immutable 64-row shards,
deduplicates corrected images in a SHA-256 object store, retains direct hard
links only for the registered scoring rows, checkpoints only fully validated
shards, and places attestation in a later job. This execution contract does
not change protocol rows, ordering, hypotheses, endpoints, or the decision
rule.

The operational design blocker is therefore closed. Activation remains
fail-closed on the external admission facts listed above and on access to one
qualified host with a persistent evidence filesystem. This is a protocol
decision, not a CI tuning detail.

Run the structural and claim-admission checks with:

```sh
node scripts/verify-motion-superiority-protocol.mjs
node scripts/verify-motion-superiority-protocol.test.mjs
node scripts/verify-motion-superiority-protocol.mjs --admission
# Later, after provisioning:
node scripts/verify-motion-superiority-protocol.mjs --admission \
  --lock benchmarks/motion/environment-and-data-lock-v2.json
```

The first two commands must pass now. The admission command must fail until
every lock is resolved. The frozen protocol is never edited to flip an
“admitted” boolean; the later lock is bound to its exact SHA-256. A premature
success without that lock would be a validation defect.

## Registered workloads

The analytic stratum contains 24 independently seeded subjects and four
equal-weight scenarios:

1. clean, small, isotropic motion;
2. moderate motion on an oblique anisotropic grid;
3. capture-boundary motion on a permuted grid;
4. hard motion with obliquity, bias, gain drift, noise, and registered
   dropouts.

The JSON records every seed, rank-four shape, complete index-to-world affine,
twist harmonic, impulse, nuisance parameter, reference rule, mask rule, and
scenario weight. The continuous renderer uses fixed `3 x 3 x 3` voxel
integration and checks a registered subset against `5 x 5 x 5`. Its source and
generated assets receive checksums. It cannot call production resampling.

The headline estimates use only these 24 analytic subjects. At least twelve
public real-anatomy subjects form a separate external-validity stratum. Their
motion is injected by a benchmark-only windowed-sinc reconstruction on a
0.75 mm world grid. This stratum can veto the claim through absolute, failure,
or non-inferiority guardrails, but it cannot rescue a failed analytic
superiority result.

Frames, voxels, landmarks, scenarios, and timing repetitions are repeated
measurements. None is counted as an independent subject.

## Two required courts

The common-resampler estimation court gives each method frame zero as the same
fixed reference. No estimator receives the scoring mask; the independent
scorer uses one immutable registered mask. It scores physical matrices
directly, then applies all accepted matrices through one independent benchmark
resampler. This separates estimator quality from output interpolation.

Common-court timing uses one externally observable boundary for every method:
fresh process or already-provisioned container launch through materialized,
fsynced canonical pose matrices. Tool-private decode, preparation, and
optimizer timers remain diagnostics and cannot replace this co-primary
endpoint.

The native end-to-end court runs each recommended pipeline with its registered
native reference, mask, interpolation, and boundary behavior. The candidate,
AFNI, and MCFLIRT use frame zero; nifreeze uses its registered median
leave-one-volume-out predictor. This difference is declared prospectively and
retained in every raw row. Native timing includes process or pre-provisioned
container launch, input read, preparation, estimation, application, output
serialization, `fsync`, and close. Environment creation, image pulls, and
dataset downloads are outside timing.

Both courts are mandatory. The claim fails closed if a required implementation,
court, scenario, subject, or output is absent.

## Co-primary endpoints and margins

The following four endpoints are co-primary. All eight headline hypotheses
(four endpoints against each of two comparators) must pass.

| Endpoint | Effect | Practical superiority margin | Scenario non-inferiority margin |
|---|---|---:|---:|
| physical landmark displacement p95 | comparator minus candidate | `0.05 mm` | candidate may not lose by `0.10 mm` |
| corrected-image NRMSE | comparator minus candidate | `0.005` truth SD | candidate may not lose by `0.01` |
| native end-to-end elapsed time | log comparator/candidate | `log(1.10)` | candidate may not be over `5%` slower |
| common-court elapsed time | log comparator/candidate | `log(1.10)` | candidate may not be over `5%` slower |

Thus a tiny positive difference is not superiority. The candidate must be at
least 0.05 mm better on physical displacement, 0.005 truth-standard-deviation
better on corrected-image error, and 10% faster on both registered timing
endpoints after paired aggregation.

Guardrails cover relative rotation, framewise-displacement error, the
three-voxel boundary shell, temporal first differences, physical edge energy,
ringing, failure rate, and peak resident memory. Every metric has units,
direction, aggregation, missing-value behavior, a positive practical margin,
and a scenario non-inferiority margin in the machine registry. Native tSNR and
DVARS may be reported as downstream context, but they are not accuracy truth.

## Timing and analysis

The primary resource ceiling is four isolated physical cores, with a
registered one-core sensitivity run. Every row separately records allocated
cores, requested workers, effective workers, and the evidence source. A serial
implementation remains one effective worker even when it is allowed four
cores. The JVM and every comparator receive five warmups and twenty measured
repetitions. Method order is a seeded balanced Latin square within
subject-scenario-court-repetition blocks. One-shot timing, different cache
policies, missing repetitions, and silently substituted worker counts
invalidate evidence.

The executable boundary and raw schema are documented in
[the motion benchmark runner guide](motion-benchmark-runner.md).

Analysis is paired by subject. The four analytic scenarios retain equal
weights; voxel or frame counts never change those weights. The protocol uses a
10,000-resample studentized subject-cluster bootstrap. The eight headline
hypotheses use one-sided 99.375% lower bounds plus Holm-controlled paired
sign-flip tests. Scenario and real-anatomy non-inferiority guardrails receive
their own registered family-wise control.

There is no result-dependent early stop, subject replacement, or sample-size
extension. A method crash, invalid transform, non-finite result, timeout, or
missing output remains in the intention-to-benchmark failure rate.

## Complete decision

The superiority claim passes only if:

- claim admission and both courts are complete;
- every candidate absolute and fidelity gate passes in every scenario;
- all eight AFNI-and-nifreeze co-primary superiority hypotheses exceed their
  practical margins;
- every scenario-level and real-anatomy non-inferiority guardrail passes;
- failures remain at or below 2%;
- the FSL MCFLIRT context family is reported completely;
- raw commands, outputs, timings, losses, checksums, analysis, and the signed
  receipt are archived.

Parity, a speed-accuracy tradeoff, aggregate wins that hide a scenario loss, or
superiority against only one headline comparator is an unmet result. The
protocol fixes exact accepted and unmet language so the final report cannot
reframe a loss after inspection.
