# BasinBridge B4: frozen oracle, bridge, and refinement decomposition

Mote issue: `sf-01ky5p210dbhc1st4vr73nyd5c`.

B4 is the first end-to-end accuracy decomposition. It keeps the matcher,
projector, group line search, and HalfFlow controls fixed while separating
three causal lanes:

1. `oracle-to-bridge`: known work-frame correspondences through BasinBridge;
2. `oracle-to-bridge-to-halfflow`: the same correspondences followed by the
   frozen local HalfFlow plan;
3. `block-to-bridge-to-halfflow`: native block-search correspondences followed
   by the same bridge and local plan.

The runner is `BasinBridgeDecomposition`. It retains bridge-stage and final
stage measurements in one typed result. Oracle matches are work-frame values;
the round lifts them through the current fixed and moving midpoint arms before
computing endpoint residuals. The native lane performs one rewarp/rematch after
an accepted bridge update. No lane retunes a parameter after observing a
result.

## Frozen contract profile

The cross-platform runner contract uses a deterministic same-grid analytic
image pair and the following small profile so that the orchestration itself is
fast enough for JVM and Scala.js gates:

| Control | Frozen value |
| --- | --- |
| grid | `25^3`, identity 1 mm spacing |
| native block radius | `(1,1,1)` voxels |
| native search radius | `(3,2,2)` voxels |
| matcher minimum valid fraction | `0.8` |
| projector sigma | `1.5 mm` |
| bridge CC maximum step | `4.0 mm` |
| fine profile in the contract test | one level, shrink `1`, smooth sigma `1.0 mm`, maximum step `0.5 mm`, one accepted step, two attempts |
| geometry interior margin | `3` voxels |

The production decomposition serializes the existing `HalfFlowCcEngine`
two-level `budget2x` controls separately from the compact contract test. The
receipt freezes two levels (shrink `2` then `1`), true Neighborhood-CC, the
existing damping/retry policy, support sigma, export inverse policy, and the
`2/4/8/12 mm` capture matrix. The checked-in B0 fixture remains unchanged.

## Measurements and decision table

For every lane and stage, retain weighted endpoint residual `p50`, `p95`, and
maximum, weighted mean, true frozen-support Neighborhood-CC loss, accepted
alpha, rematch rounds, rejection reasons, sampled minimum determinant,
non-positive determinant count, valid fraction, and fine accepted/attempted
steps. Labels and surface metrics are evaluation-only and are absent from the
synthetic contract. Runtime, allocations, peak memory, export inverse error,
and any labels/surfaces must be added to the external receipt rather than
silently approximated by this runner.

Before observing results, classify them as follows:

- oracle bridge failure or folding: fix the projector/assimilator and do not
  tune HalfFlow;
- oracle bridge success followed by fine-refinement stall: investigate the
  fine objective, regularizer, rank-one scaling, or midpoint interpolation;
- oracle-plus-fine success with block-plus-fine lag: open a narrow matcher or
  confidence follow-up;
- block-plus-fine closing the predeclared oracle gap: retain the architecture
  as the candidate, subject to the external five-pair court.

The proposed retention threshold is `1 - blockGap / oracleGap >= 0.8`, defined
on the same residual metric and only when both gaps are positive. This is a
classification rule, not a post-hoc tuning target.

## Evidence boundary

The executable B4 contract proves the three-lane wiring and metric invariants
on a deterministic 2 mm synthetic translation. The native bridge-to-fine lane
also runs the frozen 2/4/8/12 mm capture matrix on both platforms: 2/4/8 mm
complete fine refinement, while 12 mm is retained as a typed accumulated-
Jacobian rejection after a successful exact bridge and rematch. This is a
useful failure localization, not a promotion result. The exact two-level
production `budget2x` oracle matrix now passes bridge-only at 2/4/8/12 mm and
passes bridge-plus-fine at 2/4 mm; 8/12 mm retain typed fine-stage topology
failures. This establishes the local engine's synthetic 2-4 mm basin without
confounding it with matcher confidence. Licensed same-affine sub18 and at
least five labeled development pairs remain required before architectural
accuracy promotion. No synthetic receipt is an ANTs parity or
anatomical-accuracy claim.

## Real source-tree court

The first real-data diagnostic now follows Hodgeflow's source-tree test
pattern. `BasinBridgeRealDataSuite` resolves `HODGEFLOW_ROOT` (then nearby
source checkouts), verifies the exact `sub-1002_run-01_res-3_T1w.nii.gz` and
`tpl-MNI152Lin_res-02_T1w.nii.gz` hashes, decodes their physical NIfTI
affines through the JVM-only `image4s-nifti` test dependency, and runs one
deterministic native BasinBridge round in a ten-minute MUnit suite. The suite
is ignored when the Hodgeflow checkout is absent; present-but-changed assets
fail closed.

The corrected 2026-08-02 source-tree run used 18 fixed-grid physical anchors,
a `(1,1,1)` block, an `(8,8,8)` search window, and the default projector and
frozen true-CC controls. NIfTI data now move from image4s logical D3 order into
the HalfFlow x-fastest facade through `NeuroVol.fromRavel`; the prior
`elementsIterator` observation is withdrawn. The corrected run accepted alpha
1.0, reduced weighted match error from `15.380748177022042` mm to
`9.480970212168321` mm, reduced true-CC loss from `0.7897691963417637` to
`0.7828775417413045`, and completed in 100.625 s of method time. This remains
ingress and bridge evidence only: the pair is not same-affine, has no labels
or surfaces, has no verified data-license evidence, and does not establish
ANTs parity or anatomical accuracy. The detailed receipt is
`docs/benchmarks/receipts/basinbridge-realdata-hodgeflow-2026-08-02.json`.

## Sub-18 differential head-to-head

`BasinBridgeSub18AccuracyProbe` consumes Hodgeflow's saved sub-18 softbrain,
MNI2009cAsym fixed image, fixed support mask, evaluation-only moving BET mask,
and saved deterministic ANTs outputs. Six artifacts are SHA-256 pinned. Before
running HalfFlow, the Scala evaluator must reproduce both current Hodgeflow's
header-identity baseline and the five saved-ANTs post-registration metrics
within absolute `1e-6` plus relative `1e-6`. The saved ANTs row reproduces at
floating-point noise: NCC `0.6722980501504529`, global NCC
`0.9457095651791072`, gradient NCC `0.43994560051600695`, Dice
`0.9793589557154222`, and mask COM distance `0.30836844437303096` voxels.

The unchanged `(1,1,1)` block and `(8,8,8)` search produced 18 initial
correspondences. Assimilation accepted alpha 1.0 and reduced sparse match error
from `15.755849482703288` mm to `8.765332170357594` mm. It modestly improved
global NCC (`0.5678517376638428` to `0.628563548990878`), Dice
(`0.6609480584043748` to `0.7208578251914299`), and COM distance
(`17.720557855282404` to `13.687294707249926` voxels), but gradient NCC worsened
and all anatomical metrics remained far below ANTs. The complete round failed
mandatory rematch when one moved anchor retained only 6 of 21 required
reference samples. Frozen `budget2x` then rejected the partial state at shrink
1 because the fixed accumulated Jacobian was `0.03943797115921224`, below the
unchanged `0.05` floor. Endpoint export also rejected the state: residual
inverse error was `0.6796445796390174` mm and the diagnostic endpoint candidate
contained 11,434 non-positive Jacobians.

This localizes the next one-factor work to bridge boundary/support and global
affine handling, including rematch anchor support. Do not loosen topology or
tune the fine objective from this failed state. The validator-backed negative
receipt is
`docs/benchmarks/receipts/basinbridge-sub18-headtohead-2026-08-02.json`.

B6 tested that rematch-support factor in isolation. Strict initial matching is
unchanged. Only the post-acceptance rematch may retain usable anchors after an
anchor-local invalid-anchor, insufficient-support, or no-candidate failure; it
requires up to four anchors (bounded by the declared set) and 50 percent of the
requested set, preserves input order, and records every retained index and
dropped reason. Focused JVM
and Scala.js suites both passed 16 tests, including the frozen B4 decomposition
matrix. On the unchanged sub-18 court the
complete round retained 15 of 18 rematch anchors, dropping indices 1, 3, and 5
for forward reference support of 6, 14, and 15 samples against 21 required.

This clears the operational rematch failure but does not improve or promote
accuracy: rematching is diagnostic and the accepted state and anatomical
metrics are exactly unchanged. Frozen `budget2x` still rejects the fixed-arm
accumulated Jacobian at `0.03943797115921224`, endpoint export still rejects a
`0.6796445796390174` mm residual inverse, and the endpoint candidate still has
11,434 non-positive Jacobians. Retain the auditable rematch behavior; next
separate global-affine initialization from bridge boundary/projector behavior.
The B6 receipt is
`docs/benchmarks/receipts/basinbridge-b6-rematch-support-2026-08-02.json`.

The 12 mm bridge report is qualified: at the B4 reporting margin of three
voxels its minimum sampled determinant is `0.028076171875`, with zero
non-positive samples and full valid fraction. The B3 trial guard uses a
displacement-aware interior margin for the half-flow numerical reach, whereas
the fine regrid/export policy evaluates its own declared support. This is a
boundary-support diagnostic, not evidence that the 12 mm result is globally
topology-safe.

## Phase-4 JVM guardrail

`benchmarks/halfflow-jvm/src/main/scala/reframe4s/halfflow/BasinBridgeBenchmark.scala`
now measures the actual 25^3 native round and the supplied-match oracle round
under the same JMH fork and iteration policy. Setup checks the analytic 2 mm
translation, accepted assimilation, and mandatory native rematch before any
sample is measured. The first local JVM receipt records runtime and JMH GC
allocation-rate evidence separately. The JVM receipt also records a separate
three-sample JVM probe. The shared probe runs under Scala.js/Node with the
same 25^3 scenario; checksums agree across platforms and both native/oracle
setup checks pass before sampling. The JVM probe reports per-thread
allocation; Scala.js allocation remains explicitly not instrumented.

Process-level `/usr/bin/time -l` captures are recorded for both invocations as
maximum-RSS envelopes. They include sbt project loading/linking and the child
runtime, so they are qualified process envelopes rather than method-level peak
memory. The cross-platform receipt remains `baseline-only`; no scaling law,
allocation parity, ANTs parity, anatomical accuracy, or production-performance
promotion is inferred.

The cross-platform probe sources are:

- `modules/reframe4s-halfflow/shared/src/test/scala/reframe4s/halfflow/BasinBridgePerformanceScenario.scala`
- `modules/reframe4s-halfflow/jvm/src/test/scala/reframe4s/halfflow/BasinBridgePerformanceProbe.scala`
- `modules/reframe4s-halfflow/js/src/test/scala/reframe4s/halfflow/BasinBridgePerformanceJsProbe.scala`

The updated receipt and validator are:

- `docs/benchmarks/receipts/basinbridge-b4-performance-2026-08-02.json`
- `tools/registration/validate_basinbridge_b4_performance_receipt.py`

The external activation contract is frozen separately in
`docs/benchmarks/manifests/basinbridge-external-v1.json`. Its validator
`tools/registration/validate_basinbridge_external_manifest.py` requires the
licensed same-affine oracle, sub18 case, five labeled pairs, per-pair
provenance/hashes, and evaluation-only roles for moving masks and labels.
Until those inputs are supplied, the manifest remains
`pending-required-inputs` with `doNotRun: true` and no result rows.

Verification from the repository root:

```sh
sbt "reframe4s-halfflowJVM/testOnly reframe4s.halfflow.BasinBridgeDecompositionSuite"
sbt "reframe4s-halfflowJS/testOnly reframe4s.halfflow.BasinBridgeDecompositionSuite"
sbt "reframe4s-halfflowJVM/Test/compile" "reframe4s-halfflowJS/Test/compile"
sbt "reframe4s-halfflowJVM/Test/runMain reframe4s.halfflow.BasinBridgePerformanceProbe 25 1 3"
sbt "project reframe4s-halfflowJS" "set Test / mainClass := Some(\"reframe4s.halfflow.BasinBridgePerformanceJsProbe\")" "set Test / scalaJSUseTestModuleInitializer := false" "set Test / scalaJSUseMainModuleInitializer := true" "Test/run"
python3 tools/registration/validate_basinbridge_b4_performance_receipt.py
python3 tools/registration/validate_basinbridge_external_manifest.py
python3 tools/registration/validate_basinbridge_realdata_receipt.py
python3 tools/registration/validate_basinbridge_sub18_receipt.py
python3 tools/registration/validate_basinbridge_b6_receipt.py
python3 tools/registration/generate_basinbridge_fixtures.py --check
python3 tools/registration/validate_basinbridge_b4_receipt.py
```
