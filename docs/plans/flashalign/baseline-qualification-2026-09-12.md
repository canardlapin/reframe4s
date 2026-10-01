# Flashalign isolated baseline qualification

Date: 2026-09-12  
Mote task: `bd-01M2B114262AWWXQKSRE5TVAFQ`  
Machine receipt: [`baseline-qualification-2026-09-12.json`](baseline-qualification-2026-09-12.json)

## Candidate identity

The baseline was built in a clean local clone at reframe4s revision
`fa015c38a1b481096646d2c857191c327fc7a9e5`. Only the two prerequisite patches
were applied:

- `PRD.json` SHA-256
  `cb16679e325dae927f19117588d2efc039d0a0661c81689d0311e19e97fdbd4d`;
- `.github/workflows/ci.yml` SHA-256
  `a6139e4947094b02390777095263e069a2de87219f5c2f103fc86a07bddd78a8`.

The clean baseline `build.sbt` SHA-256 is
`e6ba38d0e36d4ad647ff39a95e7f7ee20670a756ad48ea2c76366406230e3402`.
This matters because the main workspace contains separately owned HalfFlow
changes. They were exercised in an additional court, but they are absent from
the admitted isolated candidate and from these baseline speed numbers.

Provider closure:

| Provider | Revision |
|---|---|
| image4s | `ec56b34806c22e26c28ecbd366ef2e323195fc88` |
| locus4s | `58c9739be51345ad9adc4bc9c9e7335023254ec9` |
| Gale | `83cac90a678d1b8a31c590e0c1b8fc8bf3427161` |
| Ravel | `9c5669399ab8e2a11402e71973dd5f1e2f2c13f4` |

The toolchain is Scala 3.7.4 and the bounded repository exception sbt 1.11.7.
The local launcher reported OpenJDK 22 while sbt test processes reported
OpenJDK 25.0.1 on macOS arm64. Future performance comparisons must match the
test runtime, hardware, and thread policy, not merely the shell launcher.

## Passing gates


```text
node scripts/verify-prd.mjs
PRD valid: 148 unique IDs, 26 artifact nodes, 85 edges, acyclic DAG.

node scripts/verify-build-graph.mjs
Build graph matches PRD: 26 nodes and 85 edges.

IMAGE4S_ROOT=<detached-ec56b348> \
LOCUS4S_ROOT=<detached-58c9739> \
node scripts/verify-symbol-ownership.mjs
Symbol ownership is unique across 992 public qualified names;
55 canonical owners verified.

sbt -J-Xmx4G -batch compileAll testAll
[success] Total time: 137 s
```

All JVM and Scala.js projects compiled and tested. The exact baseline HalfFlow
suite reported 100 passing tests on each platform. This is regression evidence
for the existing experimental module, not an admission or accuracy claim.

The clean ScalaFIM consumer snapshot
`96db5b7a317331f180e3106f0462f1019965251a` pins this exact reframe4s HEAD and
image4s revision. It passed all declared JVM/Scala.js compilation targets:

```text
sbt -J-Xmx6G -batch scalafimCompileAll
exit 0
```

The registered deterministic motion laptop court also passed all 13 tests with
no ignored cases. Its source receipt SHA-256 is
`5aa0a6f5e55e334c9ce3ce420dd0fe2c9b1baa2fc274b13e2a1669d41a871cef`;
the full values and checks are copied into the machine receipt linked above.

## Same-machine AC-050 anchors

These values are regression anchors for unchanged transform/resampling code.
They are not Flashalign measurements, external comparisons, or release claims.

| Existing path | Median | Throughput | Allocation evidence |
|---|---:|---:|---:|
| AC-026 affine resampling, 36,864 voxels | 3.304 ms | 11.159 MVox/s | 597,568 B total; 7,744 B over payload |
| MIG-424 Lanczos-5 output, 1,000 voxels | 15.567 ms | 0.064239 MVox/s | 464 B size-invariant allocation |
| MIG-414 motion pair, 9,261 voxels x 11 evaluations | 6.303 ms | 16.163 MVox/s | 236,416 B large case |
| MIG-414 motion application, 147,456 samples | 3.086 ms | 47.782 MSamples/s | 14,256 B over payload |
| MIG-422 normal kernel, 32,768 samples | 1.909 ms | 68.660 MSamples/s | 88 B; zero size growth |

For the PRD's unchanged-path `<=10%` median-throughput regression rule, initial
same-environment floors are 10.043 MVox/s for AC-026 affine resampling, 14.547
MVox/s for the motion pair, 43.004 MSamples/s for motion application, and
61.794 MSamples/s for the normal kernel. The small Lanczos fixture is visibly
noisy; compare repeated medians and work counts before using its 0.057815 MVox/s
floor. A changed environment requires a fresh paired baseline rather than
normalizing these numbers after the fact.

## Preserved preexisting failure

The clean ScalaFIM snapshot does not pass the broader image-unification removal
audit:

```text
node scripts/verify-image-unification.mjs \
  --scalafim /tmp/reframe4s-flashalign-scalafim-96db5b7a \
  --summary-only
exit 1
```

It reports nine preexisting migration findings: three test-only raw-image-buffer
references and one each for dense-volume ownership, dense-series ownership,
sparse rank-two storage, sparse typed support, component-image ownership, and a
temporary source composite. The same snapshot nevertheless passes the complete
consumer compile above.

This audit belongs to the existing ScalaFIM `MIG-500` removal/cutover work
described in [`docs/implementation-status.md`](../../implementation-status.md)
and [`docs/scalafim-compatibility-gate.md`](../../scalafim-compatibility-gate.md).
No corresponding live bead exists in this checkout's Mote store, so no invalid
dependency was fabricated. The finding is not caused by the Flashalign graph or
CI patches and does not block adding an excluded experimental cross-project.
The later Flashalign downstream-admission task must rerun the then-current clean
consumer gates and may not claim this broader audit as passing until its owner
actually resolves it.

No remote CI, publication, or external MRI benchmark was run. The admitted
baseline establishes an exact local implementation starting point only.
