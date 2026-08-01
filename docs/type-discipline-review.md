# Scala type-discipline review

## `image4s-geometry`

Reviewed against `DEC-014`, `DEC-016`, `API-001`, `API-010`,
`AC-010`, `AC-011`, `AC-015`, and `AC-017`.

The first audit found four blockers and three major findings:

- `Dimension[D]` was open to lying rank instances.
- grid restoration could cast a grid onto a distinct live frame owner;
- alignment fabricated widened `=:=` evidence;
- affine tolerance admitted projective bottom rows while evaluation ignored
  homogeneous division;
- no existential restore operation existed;
- package-qualified constructors made runtime tokens forgeable;
- owner and affine failures were collapsed into misleading string-bearing
  errors.

The implementation now:

- seals and privately constructs the D2 and D3 dimension witnesses;
- records and checks the exact live frame owner before the one localized
  registry recovery cast;
- returns typed `FrameAlignment` and `GridAlignment` values that explicitly
  rebind points and vectors instead of fabricating Scala equality;
- bounds affine validation tolerance and canonicalizes an accepted
  near-homogeneous bottom row before inversion;
- restores unknown records through sealed `SomeFrame` evidence;
- keeps frame, grid, point, vector, and runtime-token construction truly
  private while exposing validated factories;
- distinguishes persistent-ID mismatch, live-owner mismatch, restore
  conflicts, structural affine failures, and non-invertibility.

The re-audit found one remaining major issue: widened point/vector arithmetic
still emitted `FrameMismatch` for two live owners with the same `FrameId`.
Those paths now use `FrameOwnerMismatch`, with an explicit widened-type
regression.

Current result: no open blocker or major finding. The only accepted minor
boundary is that `NonInvertibleAffine` retains Gale's human-readable
diagnostics as a string inside its already-distinct error case.

Verification: 17 JVM tests and 17 Scala.js tests pass.

## `image4s-core` runtime ingress and `image4s-nifti`

Verdict: Clean.

Plan reconciliation:

- `DEC-015` remains upheld: `Sampled` is still the sole image owner.
- `DEC-020` and `API-014` are upheld: `SomeSampled` hides D, F, and R with
  abstract type members, exposes only sealed D2/D3 cases, and retains the same
  `Sampled` object.
- `DEC-021` and `API-015` are upheld: filesystem and format effects occur only
  in `image4s-nifti`; the adapter reuses the geometry `Affine`, `Grid`, and
  `Frame` types. Format parsing and encoding are shared, while JVM and Node
  adapters contain only path, physical-file, and gzip effects. The Scala.js
  API is explicitly Node-only.
- `DEC-022` and `API-016` are upheld: storage is a closed single/pair
  distinction, pair writers return both paths, and extension payload is
  immutable. Suffix validation, absent companions, and malformed extension
  regions remain distinguishable typed failures.
- `DEC-023` and `API-017` are upheld: `NiftiWriteOptions` is a validated
  immutable policy. Narrow integer output rejects loss by default, quantization
  is a named ADT choice, and an indexed `ValueNotRepresentable` error retains
  the source value, encoded value, datatype, and structured reason.
- `API-008` is upheld: public NIfTI operations return a sealed `NiftiError`
  channel. Image and geometry failures remain nested typed errors.

Ergonomic reconciliation:

- `SomeSampled.fold` hides proof transport while requiring callers to handle
  the consequential D2/D3 distinction.
- `readScalar` and `readLabels` make interpolation role explicit.
- `readScalarIn` and `readLabelsIn` preserve the supplied frame's singleton
  owner and reject incompatible convention or unit metadata.
- The common reader creates a fresh RAS frame. Unknown spatial units use the
  caller-visible `NiftiReadOptions` fallback.
- The common writer defaults to Float64. Selecting a datatype is a meaningful
  output decision; callers do not handle proof objects or construct raw header
  fields.
- Non-spatial sampling is explicit typed metadata. `NiftiTemporalUnit` retains
  the header unit, while `withNonSpatialSampling` validates pixel dimensions
  before output. The motion adapter can preserve TR and extensions without
  constructing or mutating raw header fields.

Findings: none.

Should-have-changed: the artifact graph, JVM/Scala.js project aggregation, symbol-owner
scan, migration inventory, guide, and ScalaFIM boundary wording all changed
with the new public artifact. No stale claim that ScalaFIM owns every
neuroimaging file format remains in the reviewed files.

Verification: 12 `image4s-core` tests pass on each of JVM and Scala.js. Twenty-three
`image4s-nifti` JVM tests cover the existential boundary's concrete adapter,
format ordering, roles, frame ownership, metadata, numeric decoding, gzip
output, pair entry paths, extension preservation, all supported numeric output
encodings, independent payload bytes, conversion bounds, scaling, quantization,
failure atomicity, and typed failure paths. The production-source scan found no
cast, null, suppression, partial collection access, or public throw. Four Node
Scala.js tests exercise all five datatypes across compressed and uncompressed
single and pair storage, extension preservation, companion failures, and
pre-write conversion failure. A fifth Node test verifies temporal pixel
dimensions and units across a write-read boundary.

## `reframe4s-resample`

Verdict: Clean.

Plan reconciliation:

- `API-003` and `API-005` are upheld. `ResamplingPlan.affine` accepts only an
  `AffineMap[TargetFrame,SourceFrame,D]`; it does not accept an untyped matrix
  or silently invert a map.
- `API-010` is upheld. `AffineMap` exposes the geometry-owned `Affine[D]`;
  resampling stores only compiled primitive coefficients and defines no second
  affine algebra.
- `API-013` is upheld. A plan stores no mutable execution state. `run` requires
  a typed workspace, and concurrent runs use distinct workspaces.
- `AC-023` is upheld. Production source contains no cast, null, warning
  suppression, partial collection operation, string error, sentinel failure,
  or domain exception.

Ergonomic reconciliation:

- The source image, target grid, pull map, interpolation, and boundary are
  consequential choices and remain visible at plan construction.
- `Interpolation` is contravariant in field role. `Nearest` accepts scalar and
  label images; `Linear` accepts scalar images only. The caller transports no
  proof object.
- `ResamplingResult` packages its concrete Ravel rank as the path-dependent
  member `R` instead of widening the image to `AnyRank` or casting it back to
  the source rank.
- The validity mask reuses image4s `Validity`; no parallel full/partial/outside
  algebra was introduced.

Findings: none. An initial internal draft duplicated `Validity` and widened the
output rank. Both were removed before acceptance.

Should-have-changed: `FramedAffine` now implements the core `AffineMap`
capability, `Affine.andThen` owns affine composition, `reframe4s-laws` depends
on the production artifact for oracle checks, and symbol ownership includes the
new public capability and plan types. No stale map, validity, or interpolation
match site remains in the reviewed tree.

Verification: the changed production and shared law sources contain no escape
hatch from the review scan. JVM and Scala.js tests cover compile-time role
rejection, rank-two and rank-three data, non-spatial axes, strided Ravel views,
typed boundary/rank failures, label-set preservation, affine-field
reproduction, randomized pulls, and pointwise comparison with
`ReferenceSampler`. The JVM gate measures output-buffer allocation and runs one
immutable plan concurrently with four workspaces.

## `MIG-400` fields, flows, routing, registration, and motion

Verdict: Clean at the foundational boundary.

Plan reconciliation:

- `DEC-026`, `API-002`, and `API-019` are upheld. Continuous component data
  uses the canonical `Sampled` owner and a validated Direction axis.
  `DenseMap`, `Displacement`, `Velocity`, and `Momentum` are semantic wrappers,
  not parallel containers. Only displacement defines `x + u(x)`.
- `API-004`, `API-011`, and `API-012` are upheld. A multilinear dense map is a
  `SpatialMap`, not a `SmoothIso`. Explicit Euler returns a numerical dense
  endpoint. Topology assessment samples the declared cell rule in physical
  coordinates and can create only a certificate for the exact finite scope.
- `DEC-025` and `API-020` are upheld. `TransformGraph` is an immutable
  frame-identity multigraph. It rejects ambiguity, reverses only exact
  isomorphisms automatically, and does not import locus, surface, plugin, or
  workflow concepts.
- `API-006` and `AC-043` are upheld. One-way and bidirectional registration
  results are distinct capabilities. Their only directional result names are
  `fixedToMoving` and `movingToFixed`; exact inversion is used internally to
  construct the latter and is not exposed as an ambiguous result alias.
- `API-007` and `AC-040` are upheld. `RigidPose` and `PoseSeries` retain typed
  live endpoints, and checked restoration cannot promote an arbitrary affine
  to rigid motion. Estimation reads physical points from the complete Grid
  affine, and application delegates to the existing pull `ResamplingPlan`.
  The timed image boundary is a zero-copy view. `PoseTrajectory` transports
  relative SE(3) increments without exposing proof plumbing.
- `API-022` and `AC-042` are upheld. HalfFlow exposes an experimental ledger
  and ordinary certified maps only. It is still excluded from the bundle and
  cannot mint `SmoothIso`.

The production-source audit found no cast, null, warning suppression,
`require`, public throw, sentinel failure, untyped metadata bag, ScalaFIM
import, locus import, or duplicate sampling/image/affine algebra in the seven
MIG-400 artifacts. Partial collection access was removed from production
motion and multiscale paths even where private constructor invariants would
have made it safe.

The intentional use of `fixedToMoving.inverse` occurs only inside the exact
registration constructor, where the input capability is statically
`SmoothIso`. `RigidPose` similarly derives `fixedToMoving` from a checked
`Rigid3` value whose rotation is in SO(3). Numerical dense, certified, and
HalfFlow values have no corresponding promotion path.

## `MIG-412` rigid and temporal motion

Verdict: Clean for the implemented tranche.

Plan reconciliation:

- `DEC-028`, `API-007`, and `AC-045` are upheld. `Rigid3` is a
  frame-directed proper transform with private construction, structured
  `RigidError`, exact inverse/composition, SE(3) exponential/logarithm,
  target-frame retraction, interpolation, and adjoint transport.
- `API-023` and `AC-047` are upheld for the temporal foundation.
  `PoseTrajectory` uses the one Lie-group algebra; `AcquisitionSchedule`
  represents volume, slice, and packet offsets on an explicit `GridAxis`,
  validates finite values and exact packet coverage, and keeps extrapolation
  as a named caller policy.
- `TimedScalarSamples.volumeAt` rejects extra non-spatial axes and returns a
  zero-copy rank-three Ravel view. `MotionMetrics` derives framewise
  displacement from relative SE(3) logs and requires an explicit reference
  pose and non-empty physical support for point-displacement summaries.
- `MotionEstimate.create` checks pose/diagnostic cardinality. Invalid rigid
  records, non-finite or unordered times, wrong live endpoints, invalid
  packet partitions, and out-of-range time queries retain distinct error
  cases.

Ergonomic reconciliation:

- Frame endpoints and acquisition axis are meaningful domain choices.
  Singleton endpoint evidence is inferred by ordinary constructors and
  retained inside `RigidPose`; callers do not transport equality proofs.
- Extrapolation is a consequential policy and remains explicit. The default
  is rejection, so a query cannot silently invent motion outside the observed
  interval.
- `RigidPose.fromMovingToFixed`, trajectory interpolation, and acquisition
  lookup expand into the same `Rigid3` algebra. No adapter implements a second
  matrix, Euler, or interpolation semantics.

The production-source review found no cast, null, warning suppression,
`require`, public throw, sentinel failure, stringly error, or wildcard match.
The only formerly partial `head`/`last` accesses were replaced by values
established by `TimeAxis.create`. All `MotionError`, `RigidError`, `GridAxis`,
and `ExtrapolationPolicy` consumers were checked after the new cases were
added; no stale match site or stuffed fallback was found.

Verified: focused JVM and Scala.js rigid-law and motion suites. Inferred but
not yet claimed: estimator recovery, allocation, and downstream adapter gates,
which belong to `MIG-413` through `MIG-415`.

## `MIG-413` canonical estimator and application engine

Verdict: Clean.

Plan reconciliation:

- `DEC-027`, `DEC-028`, and `API-023` are upheld by the implemented engine.
  `CompiledRigidPairEstimator` accepts only canonical scalar
  `Sampled` values, uses complete `Grid` matrices, applies target-frame
  `Twist6` increments, and delegates every candidate to
  `ResamplingPlan.affine`.
- The objective is evaluated over the fixed target domain. Boundary samples
  contribute the declared constant value rather than being removed; overlap
  remains a separate checked diagnostic.
- Compiled state contains only immutable images, ranked Ravel views, and
  validated controls plus immutable primitive stencil storage. Mutable
  resampling and normal-equation ownership is explicit in
  `RigidEstimatorWorkspace`.
- `ScalarPyramid3` uses the authoritative `GridTower`, canonical `Sampled`
  storage, complete-affine physical axis scales, and the production resampler.
  Package-private level construction prevents mismatched grid/image pairs.
- Series and multiscale outputs preserve exact reference identity and retain
  typed per-frame and per-level reports. Template refresh is validity-weighted
  and caller-bounded.
- Motion application returns canonical samples and validity weights. Slice
  failures retain their typed `GridAxis`, exact runtime frame-alignment errors
  remain structured, and sampling delegates only to `ResamplingPlan`.

Ergonomic reconciliation:

- `RigidOptimizerControl` composes explicit `RigidConvergencePolicy`,
  `RigidDampingPolicy`, `RigidCapturePolicy`, `RigidSamplingPolicy`,
  `RigidRobustLoss`, and `RigidExecutionPolicy` values.
  `RigidOptimizerControl.create` remains a compatibility constructor for the
  default dense Huber profile and returns parameter-specific ADT errors.
  Information-aware sampling accepts only a validated `RigidStencilControl`;
  the selected sampling and robust-loss policies drive compilation and both
  normal and ordinary objective evaluation. The default strategy is
  `LevenbergMarquardt`; coordinate search is reachable only through the
  explicit `ReferenceCoordinateSearch` policy.
- `run` selects the full-affine centroid initializer and ranks the declared
  deterministic capture candidates. `runFrom` exposes the consequential
  warm-start choice and intentionally bypasses capture without introducing a
  second optimizer semantics.
- `measureAt` reports a declared pose without optimizing it, which makes
  reference preservation exact rather than conventional.
- `initialPose` exposes the same initializer independently for diagnostics and
  composable series planning.
- Reference selection, scale schedules, optimizer controls, convergence,
  sampling, robust loss, capture, damping, deterministic execution, the
  template statistic, refresh bounds, interpolation, boundary, acquisition
  timing, and extrapolation remain named policy choices. Callers do not
  transport frame or rank proof objects.

The production-source sweep found no cast, null, warning suppression,
`require`, sentinel, string error channel, or constraint-defeating wildcard
match. Four package-private fixed-size numeric accessors throw
`IndexOutOfBoundsException` on impossible internal axis or six-vector indices;
all public counts and scalar policies use validated constructors and typed
errors. Candidate loops allocate only fixed-size SE(3) values; voxel loops use
Ravel's primitive rank-three access and materialize no point or coordinate
collection.

Should-have-changed: `AffineMap` now supplies the exact identity capability
needed by production resampling and has live-owner/Jacobian law coverage.
`MultiscaleError` and `MotionError` gained distinct image, rank, workspace,
scale, template, and application cases. No stale exhaustive match or stuffed
fallback remains.

Verified: the affected full gate passes 28 law tests on JVM and 27 on
Scala.js, 4 multiscale tests on each platform, and 24 motion tests on each
platform. It covers deterministic recovery, full-affine initialization,
physical-sigma equivalence, constant preservation, outward fitting, exact
reference anchoring, validity-weighted template refresh, volume and
slice-timed application, typed controls, and workspace exclusivity.
Allocation and same-environment motion throughput receipts remain `MIG-414`;
the resampling-only receipt is not promoted into estimator evidence.

## `MIG-414` motion evidence and streaming execution

Verdict: Clean and verified for the production changes reviewed here.

`ResamplingPlan.scan` traverses the same compiled affine kernel as `run`, but
passes the canonical output-linear index, value, and validity weight to a
`ResamplingSink`. This avoids fixed-arity coordinate slots and placeholder
values for absent axes. The plan still owns coordinate fusion, interpolation,
boundary handling, and workspace exclusivity. The sink cannot mint geometry,
change map polarity, or retain the workspace through the API.

`RigidObjectiveSink` reads the fixed rank-three Ravel value and accumulates
Huber loss, overlap, and support without allocating per voxel.
`ApplicationOutputSink` maps a volume or typed `ApplicationSlice` into the
final rank-four builders. It does not construct an intermediate value image or
validity image. The public result remains the one canonical `Sampled`
representation.

The shared tests are defined to prove that `scan` and `run` produce identical
values and validity weights. Motion tests cover a fixed-domain overlap adversary,
translation recovery under oblique, permuted, and reflected grid
parameterizations, and analytic linear-field application on distinct
asymmetric affines for both translation and a four-degree rotation. These
tests pass after the callback refinement on JVM, development Scala.js, and
optimized Scala.js on Node.

The JVM harness uses five warm-up runs and twenty measured runs. Pair
allocation stays independent of voxel count. Motion application allocates only
12,256 bytes beyond the two required output payloads. The harness also records
median, p95, per-thread allocation, a runtime-sampled peak-heap delta,
environment, revision label, checksums, and concurrent plan reuse with
separate workspaces. The recorded revision is `uncommitted-worktree`; this is
development evidence rather than release evidence.

The production-source sweep found no cast, null, warning suppression,
`require`, throw, partial collection access, sentinel, string error channel,
or wildcard match in the changed estimator, application, or resampling
sources. The ownership gate now names `RigidPose`, not the removed
`AffinePose`, and verifies one owner for all 55 canonical symbols.

Verified: the aggregate `testAll` court passes 225 tests across the current JVM
and Scala.js module suites with no failures or errors. The focused motion court
passes 42 JVM and 39 Scala.js tests. The known six-degree-of-freedom case
recovers 0.001716758 mm translation and 0.043979019 degrees rotation against
unchanged 0.05 mm and 0.05 degree gates. Its identity, truth, and recovered
losses are 0.0224215080449, 0.0000183389187827, and 0.0000181944425078.
The primitive normal-equation kernel allocates 88 bytes at both 512 and 32,768
samples; a nontrivial eleven-scan LM fit allocates 211,696 bytes at 9,261
voxels. Application allocates the two required output payloads plus 12,256
bytes of fixed overhead.

## `MIG-424` production Lanczos-5

Verdict: Clean and verified for the reviewed production slice.

`Interpolation.Lanczos5` remains contravariant in `Continuous`, so a label
image cannot select it. `LanczosKernel` is package-private and is the single
numerical authority shared by the production whole-grid plan and the
pointwise dense-field interpolator. Motion application selects the public
policy but defines no second kernel.

The production plan retains its immutable compiled coefficients and explicit
run-local workspace. Three reusable primitive lanes hold the separable
weights. The output traversal constructs no point, index, vector, array, or
stencil object per voxel and materializes no coordinate field. Signed
interpolation weights are not misrepresented as probability: the validity
contract uses the fraction of absolute normalized kernel mass supported by
the source. An unrepresentable finite stencil follows the ordinary typed
outside-boundary path.

The changed production sources contain no cast, null, warning suppression,
`require`, throw, partial collection access, string error channel, or
constraint-defeating wildcard. Bounds arithmetic is guarded before conversion
to `Int`; there is no sentinel index.

Verified: an independent asymmetric oblique oracle, constant normalization,
partial-boundary validity, low-frequency analytic accuracy, D3-plus-Time
run/scan identity, extreme finite coordinates, and compile-negative label-role
checks pass on JVM and Scala.js. The JVM performance court records 464 bytes of
thread allocation at both its small and large scan shapes, zero observed peak
heap growth, zero materialized coordinates, deterministic checksums, and
concurrent plan reuse with separate workspaces.

## `MIG-425`, `MIG-432`, and `MIG-433` benchmark boundary

Verdict: Clean for the protocol amendment and unpublished runner
infrastructure. No superiority evidence has been admitted.

Protocol v2 separates three quantities that v1 collapsed: allocated physical
cores, requested algorithm workers, and effective algorithm workers. The
candidate's public `DeterministicSerial` contract is recorded as one effective
worker. Run-plan parsing rejects a row whose worker or reference registration
differs from the frozen protocol.

Reference policy is also a closed ADT at the runner boundary. The common court
requires fixed frame zero for every method and withholds the scoring mask from
estimators. The native court records nifreeze's leave-one-out predictor while
the other three methods retain fixed frame zero. The runner never infers a
fallback adapter from collection lookup; exhaustive implementation matching
selects all four adapters.

External process and format failures remain `RunnerError` values. Arguments are
passed as vectors, not through a shell. Raw matrices remain archived before a
separately tested convention conversion. Successful normalized poses must be
proper rigid matrices with the exact frame count; successful native outputs
must preserve shape and affine and contain only finite values.

The common court applies every accepted pose through the allocation-tolerant
`image4s-reference` trilinear oracle after its timed boundary. It does not call
the production `ResamplingPlan` and therefore cannot make candidate
interpolation part of the estimator-isolation score. Common and native
successful rows both require a checked corrected-image checksum.

The run schema distinguishes instrumented internal stage times from
runner-only end-to-end timing. The co-primary common-court boundary is
observable for all tools and does not promote a candidate-private optimizer
timer into cross-tool evidence. Native external outputs are explicitly fsynced
before that clock stops. CPU affinity, exact executable and classpath paths,
version stdout and stderr hashes, and the locked worker registration are raw
row evidence rather than orchestration assumptions.

The execution summarizer validates every raw row against the exact plan before
writing an aggregate. Its grouping keys retain worker and reference policies;
success counts, failure counts, stage-observation counts, and absent RSS remain
visible. Linux resource sampling sums the live process tree rather than
reporting only the wrapper process, and every row records materialized output
bytes. It performs no superiority inference.

The independent scorer has one dense numeric owner and no production
resampling dependency. It treats canonical poses as physical RAS
`movingToFixed`, computes physical gradients through the inverse-transpose of
the complete affine, and exposes every registered per-frame and aggregate
metric. The statistical evaluator clusters only by subject, preserves equal
scenario weights, and implements the frozen bootstrap, sign-flip, Holm, and
scenario-veto rules. A deterministic verifier reconstructs the receipt from
the locked sources; a changed decision or hidden loss cannot validate.

Verified: protocol-v2 structural and mutation tests pass, including negative
tests for the two v1 contradictions. Run-record mutation and coverage tests
and aggregate tests pass. Sixteen strict Scala runner tests cover scheduling and
plan enforcement, the real candidate NIfTI/estimator/Lanczos-5 path, the
independent identity and translated common resampler, three transform
convention fixtures, both-court fake-process smoke runs for AFNI, nifreeze, and
MCFLIRT, a complete nonzero-exit failure row, and four independent scorer
properties.

The active release court remains blocked, not waived. Protocol v2 expands to
57,600 fresh-process rows and roughly 3.9 TB of literal uncompressed repeated
output. The `release-execution-v1` contract now supplies 900 immutable
balanced shards, terminal completed records, bounded quarantine-only retries,
create-new checkpoints, SHA-256 corrected-image ownership, designated scoring
hard links, and two-phase finalization. Completed failures remain evidence;
corrupt completed rows cannot be silently retried into successes.

The prepared GitHub workflow remains an inactive draft until a qualified
single host and complete admission lock exist. It executes at most four shards
sequentially per invocation and performs attestation in a separate job after
all checkpoints, metrics, retained objects, and the reconstructed receipt
validate. No comparator result has been run.

`MIG-435` adds a separate candidate-only laptop gate without changing those
claim semantics. Its machine-readable definition fixes four JVM suites,
analytic in-memory fixtures, accuracy and performance thresholds, a 2 GiB JVM
ceiling, disk preconditions, net build-growth and evidence-size budgets, and
the exact non-claim boundary. The runner fails if a named diagnostic is
missing, so a zero exit code without the accuracy or performance receipts
cannot pass. It records both the Java launcher visible on `PATH` and the actual
test JVM reported by the measured suite.

Verified locally: all 13 focused tests passed; the final incremental run's
complete log plus receipt used 12,016 bytes and had zero net repository build
growth; every accuracy, capture, allocation, and throughput check passed. This
result cannot be imported into the external release court and does not unblock
`MIG-433` or adjudicate `MIG-434`.
