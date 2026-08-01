# Implementation status

The implementation follows the approved `PRD.json`. A Mote issue records each
migration phase and its evidence.

| Phase | State | Evidence or blocker |
|---|---|---|
| `MIG-000` authority | complete | approved PRD and valid ID references |
| `MIG-010` inventory | complete | pinned Gale/Ravel revisions and migration inventory |
| `MIG-050` artifact graph | complete | 27 concrete nodes, 83 consumer-to-dependency edges, independent image4s/locus4s repositories, DAG checks, and browser-safe `reframe4s` umbrella |
| `MIG-100` geometry | complete | JVM and Scala.js ownership, affine, restore, and dynamic-boundary suites |
| `MIG-110` image foundation | complete | canonical `Sampled`, path-dependent `SomeSampled`, reference oracle, and image laws are green on JVM and Scala.js |
| `MIG-120` basic NIfTI I/O | complete | shared typed NIfTI-1 parser/encoder with JVM and Node path/filesystem/gzip adapters |
| `MIG-121` NIfTI storage variants | complete | gzip output, single/pair files through either member, explicit physical-file results, and exact extension preservation |
| `MIG-122` NIfTI numeric output | complete | UInt8, Int16, Int32, Float32, and Float64 output with validated scaling and explicit integer quantization |
| `MIG-200` maps and Lie transforms | complete | exact/estimated inverse separation, scoped topology records, independent type audit, JVM and Scala.js laws |
| `MIG-210` production resampling | complete | Ravel `AC-006`, nearest, linear, and normalized Lanczos-5 policies, asymmetric pull-map oracle checks, explicit workspaces, and allocation/memory/concurrency gates are green |
| `MIG-300A` locus core | complete | unforgeable live domains and package-joining negative tests |
| `MIG-300B` locus data | complete | indexed fields and domain-neutral aggregation only; package-joining boundary tests |
| `MIG-310` image/locus bridge | complete | checked grid-to-finite-domain bridge; JVM and Scala.js tests |
| `MIG-400` registration and motion | complete | canonical dense components, scoped cell topology, explicit flow integration, unambiguous transform routing, endpoint-preserving multiscale continuation, precise registration capabilities, physical-affine motion, and an experimental HalfFlow ledger |
| `MIG-410` canonical motion engine | in progress | `MIG-412` supplies typed `Rigid3`, poses, SE(3) trajectories, timing, zero-copy time-volume views, and metrics. `MIG-413` supplies the initial complete estimator and application engine. `MIG-421` freezes optimizer semantics and `MIG-431` freezes the independent superiority protocol. `MIG-422` supplies the deterministic physical stencil, analytic SE(3) Jacobians, shared robust normal-equation kernel, and typed damped solver. `MIG-423` makes LM/GN the production pair, multiscale, series, and template strategy; coordinate search remains an explicit reference policy. `MIG-414` closes the analytic, adversarial, aggregate, allocation, throughput, JVM, development Scala.js, and optimized-Node evidence gates. `MIG-424` supplies production Lanczos-5 application. `MIG-425` freezes corrected reference, worker, and observable timing semantics in protocol v2. `MIG-432` supplies the unpublished pinned comparator runner, independent common resampler, versioned raw schema, plan enforcement, and execution aggregates. `MIG-435` supplies a candidate-only, space-bounded laptop accuracy and performance gate with no superiority authority. `MIG-433` now supplies the resumable 900-shard executor, strict checkpoints and retry semantics, SHA-256 corrected-image retention, designated scoring links, two-phase finalization, and a separate attestation job. The external court remains fail-closed on qualified-host access and the unresolved comparator, public-data, license, hardware, and candidate admission lock. No external result has run. |
| `MIG-450` unified image representation | complete | image4s is independently published at immutable revision `c1c9866eb61390de40d3ba110e040ee2a09f5f32`; reframe4s consumes it without a reverse edge; ScalaFIM `compileAll` and its 243 JVM plus 232 Scala.js image tests passed at the original migration pin; the full-source structural audit finds no raw access, parallel owner, duplicate kernel, or mutable source composite |
| `MIG-500` downstream removal/release | blocked by `MIG-410` and the active graph/locus cutover | image4s and reframe4s immutable gates are green; ScalaFIM aggregate verification is waiting on separately tracked `bd-01KYR1QHESSHADQBBTTDPATF6J` |

Production plans use the pinned Ravel kernel facilities directly.
`image4s-reference` remains an independent oracle and is not accepted as a
compiled plan. See
[the Ravel capability gate](https://github.com/canardlapin/image4s/blob/main/docs/ravel-capability-gate.md).

ScalaFIM's compiler migration removes the former TASTy mismatch. The remaining
consumer evidence is described in
[the ScalaFIM compatibility gate](scalafim-compatibility-gate.md). The
canonical-motion correctness and removal gates are in
[the motion evidence plan](motion-evidence-plan.md).
The dense image hierarchy, layout translation, and performance gates are in
[the image representation contract](https://github.com/canardlapin/image4s/blob/main/docs/image-representation-contract.md)
and its
[evidence ledger](https://github.com/canardlapin/image4s/blob/main/docs/image-representation-evidence.md).
The ScalaFIM removal
sequence and structural audit are in
[the image migration guide](scalafim-image-migration.md).

## Verification snapshot

The independent image4s repository passes 157 JVM/Scala.js/Node tests and its
optimized Scala.js links. Reframe4s then passes
`sbt -J-Xmx4G compileAll testAll` against the immutable remote image4s
revision, including 37 JVM and 35 Scala.js reframe4s laws plus the current
resampling, field, flow, registration, graph, multiscale, motion, and
experimental HalfFlow suites. ScalaFIM `compileAll` and the focused image
consumer suites pass against the same revision: 243 JVM and 232 Scala.js
tests. Its aggregate `testAll` currently stops in the separately tracked
standalone-locus migration's test sources, not in an image consumer. The
architecture checks confirm:

- 27 concrete artifacts and 83 consumer-to-dependency edges in an acyclic DAG;
- exact agreement between the PRD and sbt build graph;
- 55 canonical symbol owners, with no duplicate ownership among 726 public
  qualified names.

The unpublished nested benchmark runner has a separate 16-test strict JVM
court. It covers protocol-v2 plan enforcement, the canonical candidate path,
independent common-court application, external transform conventions,
both-court fake AFNI/nifreeze/MCFLIRT processes, explicit mask routing, and
complete process-failure rows. Four scorer tests cover exact-zero truth,
moving-to-fixed polarity, the registered inner boundary shell, full-affine
physical gradients, and ringing. Protocol, admission, asset, preflight,
raw-record, metric, statistical, receipt, evidence-manifest, coverage,
aggregate, and mutation validators pass. This infrastructure evidence is not
a comparator result or superiority claim.

Six focused Node test programs cover the release execution contract, shard
construction, interrupted-row quarantine and retry exhaustion, terminal-row
idempotency and corruption rejection, content-addressed output integrity,
bounded pending-shard selection, shard-local scoring, exact checkpoint
coverage, and two-phase finalization. The inactive workflow limits each
manual or scheduled invocation to four sequential shards and performs
attestation in a separate job. These tests close the scale-design blocker;
they do not satisfy external admission or create a court result.

The focused laptop gate passes all 13 registered candidate tests in about
20 seconds on the current machine. Its final incremental run persisted a
5,238-byte complete log and a 6,778-byte receipt, with zero net repository
build growth. The receipt reports 0.001716758 mm translation and 0.043979019
degrees rotation error, 5 of 5 capture successes, 20.070 MVox/s pair
throughput, 56.218 MSamples/s application throughput, and 79.663 MSamples/s
normal-kernel throughput. The source is an uncommitted dirty worktree, so this
is a development regression receipt only. The generated result directory
remains ignored.

The current JVM motion receipt uses five warm-up runs and twenty measured
runs. A nontrivial LM pair fit over 9,261 voxels performs eleven complete
objective scans, allocates 211,696 bytes, and records median and p95 times of
4,839,792 ns and 5,083,166 ns, or 21.049 million evaluated voxels per second.
The runtime spin sampler observed no positive peak-heap delta during that
short fit. Motion
application over 147,456 samples allocates 2,371,552 bytes: 2,359,296 bytes are
the required value and validity outputs, leaving 12,256 bytes of fixed
overhead. Its observed peak-heap delta is 4,194,304 bytes, and its median and
p95 times are 2,700,916 ns and 2,821,916 ns. The harness checks concurrent
reuse with separate workspaces and exact result checksums. It records the
revision as `uncommitted-worktree`, so these numbers are development evidence,
not a release receipt.

The `MIG-422` primitive normal-equation receipt evaluates the same kernel over
512 and 32,768 fixed-domain samples, with all compiled state, residuals,
validity values, and workspace storage outside the measured path. Four repeated
evaluations allocate 88 bytes at either size. The current uncommitted JVM run
records 74.863 million samples per second and the deterministic checksum
`32259.615053612182`. The shared analytic court fixes the objective, gradient,
normal matrix, damped increment, and checksum and passes on both JVM and
Scala.js.

The `MIG-423` known-transform receipt reports 0.001717 mm translation error
and 0.043979 degrees rotation error against the unchanged 0.05 mm and 0.05
degree gates. Under the same compiled objective, identity, truth, and recovered
losses are `0.0224215080449`, `0.0000183389187827`, and
`0.0000181944425078`. The deterministic axial capture court passes all five
registered scenarios from -12 through +12 degrees on both JVM and Scala.js,
preserves the capture-disabled 5/5 success rate at the declared 0.10 mm and
0.20 degree gates, and improves the +12-degree boundary error from 0.17501 to
0.15921 degrees. The complete motion suites pass 41 JVM and 38 Scala.js tests;
the registration suites pass four tests on each platform. A separate
`FullOptStage` Node run passes all 38 Scala.js motion tests with the same
analytic losses, recovered transform errors, capture selections, and capture
court results as the JVM run.

`reframe4sJVM/makePom` records 14 dependencies and `reframe4sJS/makePom`
records 16, the latter adding the Scala.js library and test bridge. The
reframe4s and image4s runtime dependencies carry the correct JVM or Scala.js
suffixes. Neither POM includes `image4s-nifti`, `image4s-reference`, a laws
artifact, or `reframe4s-halfflow`. The five-test Node NIfTI suite also passes
when its test linker is forced to `FullOptStage`, including non-spatial pixel
dimensions and temporal units.

The accepted `PRD.json` SHA-256 is
`8124439078f754c5eefe453c113b71ba14ea8e8b45bd871424b32e0953e8cbb7`.
