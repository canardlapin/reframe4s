# reframe4s

reframe4s provides lawful typed spatial transformations, resampling,
registration, fields, flows, and motion for Scala on the JVM and Scala.js.
Spatial geometry and sampled images live in the independent
[image4s](https://github.com/canardlapin/image4s) repository. Identity-safe
finite domains live in [locus4s](https://github.com/canardlapin/locus4s).
ScalaFIM remains the higher-level neuroimaging suite and owns BIDS discovery,
reports, command-line programs, parcellation workflows, searchlights, and
BOLD-specific orchestration.

> **Maturity:** `0.1-development` / pre-release. The typed transformation
> foundations are executable, while registration, motion, and experimental
> HalfFlow surfaces remain under active development; APIs and package
> boundaries may change.

This repository publishes only the reframe4s artifact family. Dependencies
point from a consumer to its dependency and are checked against the
cross-repository DAG in `PRD.json`.

| Authority | Artifact | Owns |
|---|---|---|
| Default bundle | `reframe4s` | stable browser-safe runtime artifacts through transitive dependencies |
| Maps | `reframe4s-core` | spatial maps, jets, exact inverses, numerical evidence |
| Lie transforms | `reframe4s-lie` | framed affine and validated rigid SE(3) transforms |
| Dense fields | `reframe4s-field` | canonical component storage, displacement, velocity, momentum, tensor-product B-spline fields, coverage-reporting composition to a lattice, determinant and log-determinant fields, numerical inversion evidence, and scoped topology assessment |
| Flow | `reframe4s-flow` | explicit stationary-velocity integration with typed diagnostics |
| Transform graph | `reframe4s-graph` | immutable keyed routing with exact-only automatic reversal |
| Multiscale | `reframe4s-multiscale` | endpoint-preserving grid towers and typed level continuation |
| Registration | `reframe4s-register` | optimizer protocols and capability-precise registration results |
| Motion | `reframe4s-motion` | canonical rigid estimation, pose trajectories, acquisition timing, application, and pose-derived diagnostics |

The external `image4s-geometry` artifact owns dimensions, frames, points,
vectors, grids, and affine coordinates. `image4s-core` owns the single
`Sampled[F,D,A,Role,R]` representation. Reframe modules consume those values
directly; no adapter image or geometry hierarchy exists here.

`reframe4s-halfflow` owns the concrete experimental HalfFlow engine and its
evidence ledger. It is not part of the default bundle and cannot mint an exact
inverse capability.

`MIG-410` is replacing the initial centroid-only motion foundation with the
canonical reusable engine. The port uses complete physical grid affines,
validated frame-directed rigid transforms, SE(3) updates and interpolation,
filtered multiscale images, typed termination, and ordinary production
resampling. ScalaFIM will retain BIDS, DVARS, censoring, reports, confounds, and
command policy as adapters rather than a competing motion implementation. See
[the motion evidence plan](docs/motion-evidence-plan.md).

The public contracts are intentionally strict:

- A frame, grid, and finite domain have distinct persistent identifiers and
  distinct live owners.
- Restoring serialized identity produces typed rebinding evidence; it never
  fabricates Scala equality.
- Spatial maps consume `From` points and return `To` points. Pull resampling
  therefore requires a target-to-source map.
- Only an analytic lawful inverse implements `SmoothIso`. Numerical inversion
  returns evidence values that cannot be used as exact isomorphisms.
  `NumericalInversion.invert` returns an `InverseEstimate` with per-point
  status, coverage and two-directional residual evidence, or a typed gate
  failure carrying that evidence.
- Scalar, label, and series image names are aliases of `Sampled`, not additional
  containers.
- Logical image access is `(i,j[,k][,t...])` regardless of Ravel layout. In D3,
  `z` means the third grid-axis slice; the affine determines its world or
  anatomical direction.
- `SomeSampled` packages a runtime-discovered dimension, frame, and rank without
  copying or erasing the underlying `Sampled`.
- Non-spatial axes such as time and channel do not enter spatial transforms.

The canonical layout, ownership, slicing, legacy-buffer, and performance rules
are specified in
[the image representation contract](https://github.com/canardlapin/image4s/blob/main/docs/image-representation-contract.md).
In particular, ScalaFIM first-axis-fastest buffers cannot be passed directly to
canonical C-order Ravel storage: the checked compatibility boundary must prove
a view or perform one explicit logical-order materialization.
The downstream removal sequence and its executable structural audit are in
[the ScalaFIM image migration guide](docs/scalafim-image-migration.md).

Statically ranked images use allocation-free logical indexing:

```scala
val value = series(i, j, k, t)
val volume = series.selectTime(t).toOption.get
val cropped = volume.spatialView(Vector(8, 8, 4), Vector(32, 32, 20))
```

`selectTime` and `spatialView` share immutable Ravel storage. Call
`canonicalLayout` when a consumer requires canonical C order, or
`materializedCopy` when it requires an independent buffer.

Most transformation consumers should select the `reframe4s` artifact. Its POM
supplies stable geometry, image, mapping, resampling, registration, and motion
runtime modules transitively. Consumers needing NIfTI, reference sampling, or
the locus bridge select those granular artifacts directly from image4s. The
bundle excludes laws, reference oracles, experimental HalfFlow, and Node-only
NIfTI I/O.

`reframe4s-resample` now compiles immutable affine pull-resampling plans for
continuous `Double` images and integral categorical label images. A plan requires an
`AffineMap[TargetFrame,SourceFrame,D]`; linear interpolation is available only
for scalar fields, while the integral-only nearest plan accepts labels. Every run
requires an explicit workspace and returns both the sampled image and a
full/partial/outside validity mask. D2 data may have total rank 2–4, and D3 data
may have total rank 3–4. `ModulatedResamplingPlan` scales a continuous pull by
`|det D phi|` (`Jacobian`, which preserves a density's integral) or its square
root (`SqrtJacobian`, which preserves the squared L2 norm). `image4s-reference` remains the independent
correctness oracle.

The build pins Ravel
`89cbc557dfa467dd3f88bcb8098bceb4c85834b3`, whose rank-specific indexing,
canonical linear access, and consuming builder satisfy `AC-006`. The image
representation capability evidence now lives in
[image4s](https://github.com/canardlapin/image4s/blob/main/docs/ravel-capability-gate.md).

Ordinary builds resolve image4s from the immutable revision declared in
`build.sbt`. During coordinated development, override it explicitly with
`-Dreframe4s.image4s.build=/absolute/path/to/image4s`.

Start with [the foundation guide](docs/foundation-guide.md). The implementation
and blocker state is recorded in
[implementation-status.md](docs/implementation-status.md).

Run the architecture checks with:

```text
node scripts/verify-prd.mjs
node scripts/verify-build-graph.mjs
IMAGE4S_ROOT=/path/to/image4s LOCUS4S_ROOT=/path/to/locus4s \
  node scripts/verify-symbol-ownership.mjs
```

Run the complete cross-platform suite with a heap large enough for concurrent
Scala.js linking:

```text
sbt -J-Xmx4G -batch testAll
```
