# Motion benchmark runner

The unpublished runner under `benchmarks/motion/runner` executes the frozen
[`motion-superiority-v2`](../benchmarks/motion/protocol-v2.json) court. It is a
nested JVM build, not a published foundation artifact. Its source dependencies
are `image4s-niftiJVM`, `image4s-referenceJVM`, and
`reframe4s-motionJVM`; AFNI, nifreeze, and FSL remain external processes.

No superiority result has been run or admitted. The runner is infrastructure
for a future immutable candidate revision, locked comparator environments, and
locked public data.

## Space-bounded laptop gate

The external runner is not the routine development gate. Use:

```sh
node scripts/run-motion-laptop-gate.mjs
```

The command reads
[`execution-tiers-v1.json`](../benchmarks/motion/execution-tiers-v1.json),
runs four candidate-only JVM suites, and writes one bounded log and one JSON
receipt under `benchmarks/motion/results/laptop/`. Its fixtures are analytic
arrays constructed in memory. It does not provision comparators, download
datasets, generate a workload manifest, or write corrected NIfTI images.

The receipt records the definition hash, source revision and dirty state,
actual test JVM, host, exact sbt command, free disk, net repository build
growth, parsed accuracy and performance diagnostics, every threshold decision,
and the log hash. A nonzero suite, missing diagnostic, threshold loss, short
disk precondition, excessive build growth, or truncated log fails the gate.

This command is a candidate regression check. It does not execute this
external runner and cannot contribute rows to the release superiority court.

## What one run records

Every planned row gets a fresh output directory containing:

- the exact argument vector and controlled environment;
- stdout, stderr, version-probe output, exit status, and typed termination;
- raw comparator matrices and canonical physical RAS `movingToFixed` poses;
- a corrected NIfTI for the native court;
- instrumented stage times where the implementation exposes them;
- the runner-observed process boundary, aggregate process-tree peak RSS where
  the Linux host supports it, materialized output bytes, and SHA-256 checksums;
- allocated cores, requested workers, effective workers, worker evidence, and
  the exact reference policy.

The JSON contract is
[`run-record-v2.schema.json`](../benchmarks/motion/runner/schema/run-record-v2.schema.json).
Null internal stage splits are deliberate for monolithic tools. The
co-primary common-court time is the observable fresh-process or
already-provisioned-container boundary through fsynced canonical poses, not a
method-private optimizer timer.

`allocatedCoreCount` is a resource ceiling. `requestedWorkerCount` is the
method configuration. `effectiveWorkerCount` is the maximum algorithm
parallelism that configuration can use; it is not an operating-system thread
count. Protocol v2 registers the production candidate, AFNI `3dvolreg`, and
MCFLIRT as effectively serial, while the pinned nifreeze/ANTs adapter may use
the allocated one or four workers. The later admission lock must attach the
declared evidence for all four tools.

## Reference and mask policies

The common court gives every estimator frame zero as its fixed target. No
estimator receives the scoring mask. The immutable mask is used only by the
independent scorer. After the timed pose boundary, the runner applies every
method's canonical poses with the same allocation-tolerant
`image4s-reference` trilinear oracle and `Constant(0)` boundary. This corrected
image is required for every successful common-court row, but its application
time is excluded from the common-court speed endpoint.

The native court keeps each prospectively registered pipeline visible:

| Implementation | Native reference policy |
|---|---|
| reframe4s-motion | fixed frame zero |
| AFNI `3dvolreg` | fixed frame zero |
| nifreeze | median leave-one-volume-out predictor |
| FSL MCFLIRT | fixed frame zero |

The asymmetry is part of the native-pipeline comparison. It is neither hidden
nor retrospectively normalized.

## Transform conventions

Adapters preserve raw matrices before normalization and are tested with
nontrivial full affines and physical landmarks.

- AFNI `-1Dmatrix_save` stores base-to-input pull matrices in DICOM/LPS
  coordinates. The adapter inverts the matrix and changes the LPS/RAS basis.
  See the
  [AFNI matrix-save specification](https://afni.nimh.nih.gov/pub/dist/doc/misc/Registration_Matrix_Save.html).
- The pinned nifreeze adapter stores the reference-to-moving physical RAS pull
  consumed by nitransforms. The runner inverts it once.
- MCFLIRT emits FLIRT-compatible matrices. The adapter converts FLIRT scaled
  millimeters, including the determinant-dependent handedness convention,
  into physical RAS input-to-reference matrices. See the
  [FLIRT coordinate-system FAQ](https://fsl.fmrib.ox.ac.uk/fsl/docs/registration/flirt/faq.html)
  and
  [MCFLIRT output description](https://fsl.fmrib.ox.ac.uk/fsl/docs/registration/mcflirt.html).

Every normalized pose is checked for frame count, homogeneous form,
orthogonality, and proper rotation before it can become a successful row.
Native corrected images must retain shape and affine and contain only finite
samples.

## Run the infrastructure checks

From the repository root:

```sh
node scripts/verify-motion-superiority-protocol.mjs
node scripts/verify-motion-superiority-protocol.test.mjs
node scripts/verify-motion-run-records.test.mjs
node scripts/verify-motion-metrics.test.mjs
node scripts/motion-statistics.test.mjs
node scripts/analyze-motion-court.test.mjs
node scripts/build-motion-receipt.test.mjs
node scripts/verify-motion-receipt.test.mjs
node scripts/summarize-motion-runs.test.mjs
node --test \
  scripts/motion-shard-contract.test.mjs \
  scripts/prepare-motion-shards.test.mjs \
  scripts/execute-motion-shard.test.mjs \
  scripts/select-motion-shards.test.mjs \
  scripts/score-motion-shard.test.mjs \
  scripts/finalize-motion-shards.test.mjs
cd benchmarks/motion/runner
sbt -J-Xmx4G -batch test
```

The 16-test Scala suite exercises the real candidate
NIfTI/estimation/Lanczos-5 path,
balanced scheduling, plan round trips, transform conventions, and hermetic fake
AFNI, nifreeze, and MCFLIRT processes. It also checks exact-zero truth,
moving-to-fixed pose polarity, the registered three-voxel inner shell,
full-affine physical-gradient energy, and ringing. It does not stand in for
running the locked external tools.

`plan` reads a tab-separated workload manifest with
`subject`, `scenario`, `input`, and optional `mask` columns. The default core
ceilings are `4,1`; any other value is rejected. The generated plan contains
every warmup and measured row in a deterministic seeded balanced Latin square.
The run-record validator checks the plan one-for-one, so failures remain
complete rows and a missing or duplicate row is never silently skipped.

After a plan completes, produce deterministic execution aggregates with:

```sh
node scripts/summarize-motion-runs.mjs \
  --plan results/plan.tsv \
  --output-json results/execution-aggregate.json \
  --output-csv results/execution-aggregate.csv
```

The summary groups by subject, scenario, court, implementation, phase, worker
registration, and reference policy. It reports success and failure counts,
median and p95 observable elapsed time, peak RSS coverage and p95, and the
materialized-output p95, plus the observed count, median, and p95 for every
optional internal stage. It cannot hide a missing stage or failed row.
The independent scorer selects measured repetition zero at four cores for raw
accuracy evidence. It uses common-court outputs for pose and co-primary image
accuracy, native outputs for interpolation and boundary fidelity, and every raw
row for failure accounting. Its `motion-metrics-v1` definitions are:

- nearest-rank p95 over physical RAS landmark displacement after
  `estimatedMovingToFixed × inverse(truthMovingToFixed)`;
- population-SD-normalized masked image and temporal-difference RMSE;
- relative rotation from the trace of the physical pose error;
- transition displacement as translation L1 plus a 50 mm head radius times
  relative rotation angle;
- a three-iteration 26-neighbor inner mask erosion for the boundary shell;
- central physical gradients transformed by the inverse-transpose of the full
  index-to-world affine;
- a separable `3 × 3 × 3` local truth envelope expanded by one percent of the
  frame's global truth range.

The receipt evaluator uses subject clusters only, equal scenario weights, the
registered studentized bootstrap and sign-flip seeds, Holm adjustment, and
scenario/real-anatomy vetoes. Failed and inadmissible courts still produce a
receipt and human audit when their source rows exist; no missing metric is
silently omitted.

## Resumable release execution

[`release-execution-v1.json`](../benchmarks/motion/release-execution-v1.json)
binds the unchanged protocol-v2 court to a bounded execution design. The
57,600 master rows become 900 immutable shards. Each shard contains 16
complete randomized four-method blocks, or 64 rows. One qualified benchmark
host executes shards sequentially, with at most four shards in one workflow
invocation.

Prepare the immutable shard set once:

```sh
node scripts/prepare-motion-shards.mjs \
  --definition benchmarks/motion/release-execution-v1.json \
  --plan results/plan.tsv \
  --output-root results/execution
```

Execute and score one shard:

```sh
node scripts/execute-motion-shard.mjs \
  --manifest results/execution/manifest.json \
  --shard shard-0000 \
  --lock benchmarks/motion/environment-and-data-lock-v2.json
node scripts/score-motion-shard.mjs \
  --manifest results/execution/manifest.json \
  --shard shard-0000 \
  --scoring-plan results/scoring-plan.tsv \
  --lock benchmarks/motion/environment-and-data-lock-v2.json
```

A valid `run.json` is terminal, including an explicit tool failure; the
executor never retries it. An interrupted directory without `run.json` is
atomically moved under `quarantine/` and may be attempted once more. A second
interruption exhausts the registered attempt limit. Every completed row is
revalidated against its plan row and artifact hashes before a create-new shard
checkpoint is written.

Corrected images enter a SHA-256 object store under
`execution/objects/sha256/`. Each row retains a checked
`corrected.cas.json` pointer. Direct files are removed after the object and
pointer are durable, except measured repetition zero at four cores: those
rows keep a hard link until their independent metrics exist. Finalization
first validates all 900 checkpoints, exact master-plan coverage, every
content-addressed object, and all required metric links. Only after that
global validation succeeds does it release the designated hard links:

```sh
node scripts/finalize-motion-shards.mjs \
  --manifest results/execution/manifest.json \
  --release-designated \
  --output results/shard-finalization.json
```

Finalization is idempotent. A corrupt completed record, pointer, object,
checkpoint, or linked metric fails closed; it is not repaired by rerunning the
benchmark.

Exact-digest comparator provisioning, host preflight, affinity-wrapped runner
invocation, raw/metric validation, deterministic receipts, the resumable
content-addressed executor, evidence manifests, and GitHub artifact
attestation are implemented. The workflow remains deliberately inactive at
`.github/workflow-drafts/motion-superiority.yml`. Its `setup`, `shard`, and
`finalize` actions use a persistent filesystem keyed by candidate revision.
When activated, a six-hour schedule selects the first four shards without
checkpoints; the concurrency group keeps all invocations sequential on the
registered host. Scheduled runs require `MOTION_CANDIDATE_SHA`; every job
checks out that immutable revision and uses its SHA as the persistent evidence
directory. Attestation and archival run in a separate job after final
validation.

The remaining activation blockers are external: a qualified single benchmark
host, a complete immutable admission lock, provisioned comparators, and
licensed public data. No external court has run. The candidate-only laptop
gate does not resolve or bypass those blockers.

Claim execution remains fail-closed until protocol-v2 admission succeeds:

```sh
node scripts/verify-motion-superiority-protocol.mjs --admission \
  --lock benchmarks/motion/environment-and-data-lock-v2.json
```

The lock must match the exact protocol SHA-256 and resolve candidate,
comparator, data, hardware, licensing, and worker-evidence blockers.
