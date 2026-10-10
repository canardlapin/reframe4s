# HalfFlow canonical geometry migration

GitHub [issue #12](https://github.com/canardlapin/reframe4s/issues/12) removes
HalfFlow's private geometry authority. `Affine.scala`, `Geometry.scala`,
`DMat.scala`, `DenseFieldMorphism.scala`, and `CanonicalImageCompat.scala` have
been removed from `halfflow/internal`.

`ExecutionAdapters.scala` retains the x-fastest indexing and planar scratch-buffer
boundary. Its `GridSpec` holds one image4s `SampleSpace`; shape, affine and inverse
come from that canonical grid. `GridSpec.fromGrid` preserves an existing grid's
live owner. Pyramid levels use `withGeometry` to retain the physical frame.
Equal shapes and affine values alone do not establish frame identity.

The experimental role wrapper is now `RegistrationFrame[A]`. Its domain label
identifies a registration role; its `canonical` member is the provider-owned
physical frame. `AffineIso` keeps HalfFlow's positive-determinant admission and
midpoint factorization policy around a `FramedAffine`. The ordinary matrix
operations use Gale's existing `DMat`.

`DensePull.toMap` and `InversePair.toDenseMaps` export canonical `DenseMap` values
with actual source and target frame owners and `PreserveSource` boundary policy.
Component storage is shared with the execution adapter. The maps remain numerical
estimates; the pair does not acquire `SmoothIso` capability. Velocity admission
uses the provider's `Velocity` field validation and retains its sampled storage.
`MapExecution` only batches buffer coordinates through canonical `Point` and map
evaluation; it implements no interpolation algorithm.

The HalfFlow kernels continue to own their execution workspaces, validity masks,
boundary extensions, topology admission, correspondence policies, and optimization
logic. This migration does not qualify new anatomical accuracy or performance
claims.

## Experimental API migration

- Replace `halfflow.Frame[A]` with `RegistrationFrame[A]`.
- Build adapters for existing images with `GridSpec.fromGrid(image.grid)`.
- Use `grid.shape(axis)` for axis extents and `grid.indexToFrame` for the
  canonical affine. `grid.affine` is a derived Gale matrix view.
- Pass `image4s.geometry.Affine[D3]` to the supplied initializer, or use the
  canonical `SuppliedAffineInitialization` entry points. Those entry points now
  check physical endpoint owners.
- Replace `toDenseFieldMorphisms` with `toDenseMaps` and evaluate the resulting
  maps with frame-owned `Point` values. Routing cost and method labels belong to
  the caller's policy and are no longer attached to a private morphism.
- Regridding preserves physical frames. Construct the new lattice with
  `withGeometry` or a canonical grid in the existing frame.

## Candidate evidence

The [recorded receipt](halfflow-geometry-issue-12.json) covers candidate
`01d8d1071d91178812d80b46341ec8da231364335b993f400f85c2018c761f8d`, based on
reframe4s `66090360ca8530efde7a156f5cf2e91ac9ff3b82`. Validation completed on
2026-10-10 with 171 JVM tests, 169 Scala.js tests, 16 benchmark runner tests,
and 27 validation-tool tests passing. The PRD, build-graph, and ownership checks
also passed, and the HalfFlow benchmark sources compiled. The acquired-MRI
suite was ignored because its optional fixture was unavailable; these results
make no acquired-image accuracy or performance claim.

The normalized [numerical log](halfflow-geometry-issue-12-numericalLog.txt) and
[runner log](halfflow-geometry-issue-12-runnerLog.txt) are retained with hashes
in the receipt. The unchanged baseline passed 40 focused JVM regression tests
before the migration.

The companion JSON receipt binds JVM and Scala.js numerical results and the
ownership audit to SHA-256 hashes of the exact source, tests, build graph,
validation tools, CI configuration, and dependency pins. The receipt's source
fingerprint also covers new files in an uncommitted candidate. It is not a
consumer pin or a claim that any downstream repository has migrated.

Verify a recorded receipt against a checkout with:

```sh
node scripts/halfflow-candidate.mjs docs/verification/halfflow-geometry-issue-12.json
```

Any source or pin change invalidates that receipt. To reproduce numerical and
structural checks, use the immutable dependencies from `build.sbt`:

```sh
sbt -J-Xmx4G -batch 'reframe4s-halfflowJVM/test' 'reframe4s-halfflowJS/test' reframe4sTestRunners
node scripts/verify-prd.mjs
node scripts/verify-build-graph.mjs
IMAGE4S_ROOT=/path/to/pinned/image4s \
SPATIAL4S_ROOT=/path/to/pinned/spatial4s \
LOCUS4S_ROOT=/path/to/pinned/locus4s node scripts/verify-symbol-ownership.mjs
node --test scripts/*.test.mjs
```

The ownership audit rejects dirty or incorrectly pinned provider checkouts and
reintroduced private HalfFlow geometry declarations. `CanonicalGeometrySuite`
checks reflected oblique grids, anisotropic interpolation, partial and outside
support, shared component storage, pyramid frame preservation, and rejection of
unrelated frame owners. The existing HalfFlow suites retain their scientific
tolerances. Acquired MRI fixtures remain optional and are reported as skipped
when unavailable.
