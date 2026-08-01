# Foundation guide

This guide shows the boundaries a caller must make explicit. The linked test
suites compile and execute the same workflows on the JVM and Scala.js.

## Create a frame and grid

`Frame.named` creates a fresh identity each time. The label is metadata; two
frames named `scanner` are not interchangeable.

```scala
import image4s.geometry.*

val result =
  for
    frame <- Frame.named[D3]("scanner")
    grid <- Grid.in(frame)(
      Vector(64, 64, 40),
      Affine.identity[D3]
    )
    index <- Index.of[D3](10, 20, 5)
    point <- grid.pointAt(index)
  yield point
```

Every step that validates input returns `Either[GeometryError, A]`. A dimension
mismatch, invalid extent, non-finite coordinate, or foreign live owner remains
a distinct error case.

To recover a serialized frame, pass its `FrameRecord` through a registry:

```scala
val registry = FrameRegistry.empty

val restored =
  for
    original <- Frame.named[D3]("scanner")
    first <- Frame.restore[D3](original.record, registry)
    second <- Frame.restore[D3](original.record, registry)
  yield (first, second)
```

One registry returns one live owner for an identical record. Independent
registries may produce distinct live owners for the same persistent record.
`Frame.align` checks the records and returns an explicit rebinder for points and
vectors.

The full ownership and dynamic-boundary scenarios are executable in
[`GeometrySuite.scala`](https://github.com/canardlapin/image4s/blob/main/modules/image4s-geometry/shared/src/test/scala/image4s/geometry/GeometrySuite.scala).

## Construct one sampled image

`Sampled[F,D,A,Role,R]` is the only image container. Its Ravel array shape must
equal `grid.shape ++ nonSpatialAxes.shape`.

```scala
import image4s.*
import ravel.DType.given
import ravel.{NDArray, Shape}
import image4s.geometry.*

enum ImageConstructionError:
  case Geometry(error: GeometryError)
  case Image(error: ImageError)

val image =
  for
    frame <- Frame
      .named[D2]("plane")
      .left
      .map(ImageConstructionError.Geometry.apply)
    grid <- Grid
      .in(frame)(Vector(2, 3), Affine.identity[D2])
      .left
      .map(ImageConstructionError.Geometry.apply)
    sampled <- Sampled.scalar(
      grid,
      NonSpatialAxes.empty,
      NDArray.fromSeq(Shape(2, 3), (0 until 6).map(_.toDouble))
    ).left.map(ImageConstructionError.Image.apply)
  yield sampled
```

An immutable `NDArray` is shared by identity. Constructors whose names begin
with `copy` accept mutable or borrowed Ravel values and copy them at the public
boundary. Generic element values are structurally immutable only; no generic
container can deep-copy an arbitrary mutable element payload.

For a time series, append an `Axis` with `AxisKind.Time` and include its extent
after the spatial extents in the Ravel shape. The frame and grid remain D3.

The shape, ownership, and axis examples run in
[`SampledSuite.scala`](https://github.com/canardlapin/image4s/blob/main/modules/image4s-core/shared/src/test/scala/image4s/SampledSuite.scala).

## Receive an image from a runtime adapter

`SomeSampled[A,Role]` packages a `Sampled` value when an adapter discovers its
dimension, frame owner, and Ravel rank at runtime. It does not copy the image or
create another storage representation.

```scala
def spatialDescription(
    loaded: SomeSampled[Double, Scalar]
): String =
  loaded.fold(
    d2 => s"D2 image with shape ${d2.value.logicalShape}",
    d3 => s"D3 image with shape ${d3.value.logicalShape}"
  )
```

Inside each branch, `d2.value` or `d3.value` retains its hidden frame and Ravel
rank as path-dependent type members. A caller can pass that value through
ordinary typed image operations. To compare it with an expected frame or grid,
use `Frame.align` or `Grid.align`; `SomeSampled` does not invent equality
evidence.

The D2 and D3 identity and compile-negative cases run in
[`SampledSuite.scala`](https://github.com/canardlapin/image4s/blob/main/modules/image4s-core/shared/src/test/scala/image4s/SampledSuite.scala).

## Read a NIfTI image on the JVM or Node.js

The optional `image4s-nifti` artifact performs basic NIfTI-1 I/O:

```scala
import image4s.nifti.Nifti

// JVM
val loaded =
  Nifti.readScalar(java.nio.file.Path.of("subject.nii.gz"))
```

The Scala.js API targets Node.js and accepts Node filesystem path strings:

```scala
import image4s.nifti.Nifti

val loaded = Nifti.readScalar("subject.nii.gz")
```

Parsing, byte order, geometry, storage, extensions, and numeric conversion are
the same shared Scala implementation on both platforms. Only paths, physical
file access, and gzip are platform-specific. This artifact does not emulate or
claim browser filesystem access; browser applications should provide bytes
through a separate application-owned ingress adapter.

`readScalar` and `readLabels` make the semantic role explicit. Both return
`Either[NiftiError, DecodedNifti[SomeSampled[...]]]`; the `Sampled` value owns
the image data and `NiftiHeader` retains format metadata separately.

A default read creates a fresh RAS frame. If the header does not declare a
spatial unit, `NiftiReadOptions.default` interprets it as millimeters. Use
`readScalarIn` or `readLabelsIn` when the application already owns the frame.
Those methods preserve the supplied singleton owner and reject a non-RAS frame
or a known unit mismatch.

The reader accepts little- and big-endian NIfTI-1 input, supported integer and
floating datatypes, slope/intercept scaling, qform, and sform. Both readers and
writers accept `.nii`, `.nii.gz`, `.hdr`, `.img`, `.hdr.gz`, and `.img.gz`.
Either member of a pair may be the entry path. Writers return `NiftiFiles[P]`
with `SingleFile` and `PairFile` cases, where `P` is `java.nio.file.Path` on
the JVM and `String` on Node, so a two-file result is never collapsed to one
ambiguous path.

Writing defaults to unscaled Float64. Pass `NiftiWriteOptions` to select another
supported encoding:

```scala
import image4s.nifti.NiftiDatatype
import image4s.nifti.NiftiTemporalUnit
import image4s.nifti.NiftiWriteOptions

val int16 =
  NiftiWriteOptions
    .forDatatype(NiftiDatatype.Int16)
    .withNonSpatialSampling(
      Vector(1.75),
      NiftiTemporalUnit.Second
    )
val written = Nifti.writeScalar(path, image, int16)
```

Integer output rejects fractional encoded values, non-finite values, and values
outside the target range. It never truncates, wraps, or clamps silently. To
quantize deliberately, construct options with
`NiftiIntegerConversion.RoundToNearestEven`. `NiftiWriteOptions.create` also
accepts slope and intercept. It stores their exact Float32 header
representations and the writer encodes
`raw = (value - intercept) / slope`. Float32 payload output uses IEEE rounding
and rejects finite values that would overflow to infinity.

`NiftiHeader.pixelDimensions` and `NiftiHeader.temporalUnit` retain discovered
non-spatial sampling metadata. Writers use
`NiftiWriteOptions.withNonSpatialSampling` to preserve or deliberately choose
those values. The option validates positive finite Float32-representable
dimensions and rejects more values than the image has non-spatial axes.
Temporal units include seconds, milliseconds, microseconds, Hertz, parts per
million, and radians per second; unknown input remains explicitly `Unknown`.

`NiftiHeader.storage` records whether the header magic declares single or pair
storage. `NiftiHeader.extensions` contains immutable `NiftiExtension` values.
Each value retains its non-negative code and exact padded payload bytes. Use
`NiftiExtension.create(code, content)` to build an extension; it pads the block
to the NIfTI 16-byte alignment. Readers reject malformed sizes, alignment, and
region bounds through `NiftiError.Extension`.

The adapter names dimensions after the first three `nifti-axis-4`,
`nifti-axis-5`, and so on, with `AxisKind.Other`. A raw NIfTI dimension does not
by itself prove time, channel, echo, or another acquisition meaning. ScalaFIM
may refine those axes when BIDS or acquisition metadata supplies that evidence.

NIfTI stores its first axis fastest. Ravel contiguous arrays advance the last
logical axis fastest. The adapter translates between these orders instead of
exposing either layout as an image4s contract. The asymmetric JVM fixtures and
read-write-read checks run in
[`NiftiSuite.scala`](https://github.com/canardlapin/image4s/blob/main/modules/image4s-nifti/jvm/src/test/scala/image4s/nifti/NiftiSuite.scala).
Node storage, compression, extension, datatype, and failure-atomicity parity
runs in
[`NodeNiftiSuite.scala`](https://github.com/canardlapin/image4s/blob/main/modules/image4s-nifti/js/src/test/scala/image4s/nifti/NodeNiftiSuite.scala).

Gzip support is sequential I/O. It does not promise random access or hidden
staging; ScalaFIM retains those workflow policies.

## Use the reference sampler

`ReferenceSampler` defines nearest and linear interpolation semantics. It may
allocate and does not compile or cache a plan.

```scala
import image4s.reference.ReferenceSampler

val sampledValue =
  for
    sampled <- image
    point <- Point
      .in(sampled.frame)(0.25, 1.5)
      .left
      .map(ImageConstructionError.Geometry.apply)
    sample <- ReferenceSampler
      .linear(sampled, point)
      .left
      .map(ImageConstructionError.Image.apply)
  yield sample
```

Linear interpolation accepts scalar images. Nearest interpolation also accepts
labels. Boundary behavior is explicit: `BoundaryPolicy.Reject` reports
`OutsideGrid`, while a constant boundary reports whether support was full,
partial, or outside.

Production affine pull resampling requires:

```scala
AffineMap[TargetFrame, SourceFrame, D]
```

The first endpoint is the target because the plan maps each target point into
the source before interpolation:

```scala
import reframe4s.resample.Interpolation
import reframe4s.resample.ResamplingPlan

val resampled =
  for
    plan <- ResamplingPlan.affine(
      source = image,
      target = targetGrid,
      pull = targetToSource,
      interpolation = Interpolation.Linear,
      boundary = BoundaryPolicy.Constant(0.0)
    )
    result <- plan.run(plan.newWorkspace())
  yield result
```

`result.image` is the owned resampled `Sampled` value. The result packages its
exact Ravel output rank as the path-dependent member `result.R`.
`result.validity.weights` is a zero-to-one sampled field; `result.validity.at`
decodes a location as the existing `Validity.Full`, `Validity.Partial`, or
`Validity.Outside` case.

Linear and Lanczos-5 interpolation accept only `Double` scalar images. Nearest
an integral-only nearest plan accepts label images, so the compiler rejects both
continuous policies for label resampling. D2 inputs may have up to two
non-spatial axes; D3 inputs may have one. The plan reports
`UnsupportedDataRank` before execution for total ranks above four.

`Interpolation.Lanczos5` is the canonical high-quality continuous policy. It
uses a normalized, separable ten-tap window in each spatial dimension,
covering `floor(x) - 4` through `floor(x) + 5`. Normalization reproduces a
constant exactly. A constant boundary contributes the declared outside value;
a rejecting boundary reports the first target whose stencil leaves the source.
Because Lanczos weights may be negative, validity is the fraction of absolute
normalized kernel mass supported by the source, clamped to zero through one.
Coordinates whose complete stencil cannot be represented by an `Int` are
treated as outside rather than overflowing an index or producing a non-finite
sample.

A compiled plan contains fixed affine coefficients and no per-voxel coordinate
collection. It is immutable and may run concurrently. Each run must receive a
separate mutable workspace; sharing a workspace concurrently reports
`WorkspaceInUse`. The production Lanczos path reuses three primitive
ten-element lanes in that workspace. It does not allocate a stencil or a
coordinate object per output voxel.

The asymmetric orientation, origin, spacing, and extent fixtures compare every
production output and validity value with `ReferenceSampler`. Random interior
pulls, affine-field reproduction, a reversed D3-plus-time Ravel view, typed
boundary failures, an independent oblique Lanczos oracle, analytic
band-limited reconstruction, extreme finite coordinates, exact run/scan
parity, and JVM allocation/concurrency baselines run in `reframe4s-laws`.

## Represent dense maps and vector fields

Dense values reuse `Sampled`; they are not another image container. Create a
component image with exactly one `AxisKind.Direction` axis whose extent equals
the spatial dimension, then select its mathematical meaning:

```scala
val displacement = Displacement.from(componentImage)
val velocity = Velocity.from(componentImage)
val momentum = Momentum.from(componentImage)
```

These wrappers share the same immutable Ravel value by identity but are not
interchangeable types. Only `Displacement.asMap` supplies the endomorphism
`x + u(x)`. `DenseMap.fromCoordinates(componentImage, targetFrame)` instead
interprets every component tuple as an absolute point in the target frame.
Piecewise interpolation makes it a `SpatialMap`, not a `SmoothIso`.

`TopologyAssessor` evaluates the actual multilinear cell interpolant in
physical coordinates. Its sampling locations come from the requested
`TopologyScope`; a central-voxel determinant is not substituted for corner,
corner-and-center, or subdivision criteria. A successful call returns a
`TopologyCertificate` for that exact grid and finite scope. A fold, inadequate
region, non-finite Jacobian, grid mismatch, or failed threshold remains a
typed error.

`ExplicitEuler` is the deliberately named first stationary-velocity
integrator. It returns an ordinary `DenseMap`, integration diagnostics, and
optional scoped topology evidence. It never upgrades numerical integration to
an analytic inverse. Higher-order and scaling-and-squaring integrators can
implement the same capability boundary later.

The field, topology, and flow scenarios run on the JVM and Scala.js in the
corresponding module suites.

## Route transforms without choosing an estimate implicitly

`TransformGraph[D]` is an immutable transform multigraph. Each estimate has a
unique `TransformKey`; multiple estimates between the same frames are allowed.

```scala
val graph =
  TransformGraph
    .empty[D3]
    .add(TransformEdge.exact(t1ToAtlasKey, t1ToAtlas))

val path = graph.flatMap(_.path(t1Frame, atlasFrame))
```

A one-way edge supplies only its declared traversal. A `SmoothIso` edge alone
adds automatic exact reversal. A `CertifiedBidirectionalPair` adds its two
ordinary numerical maps without becoming a `SmoothIso`.

The default `path` operation requires one unambiguous simple path. It reports
missing or ambiguous routes instead of selecting a lowest-cost estimate.
`pathByKeys` is the explicit disambiguation operation and reports an attempted
non-invertible reversal.

## Build endpoint-preserving grid towers

`ScaleSpec[D]` contains per-axis shrink factors and physical smoothing widths.
`ScaleSchedule[D,C]` is non-empty, coarse-to-fine, and ends at unit shrink; the
algorithm-specific configuration `C` remains opaque to the multiscale module.

`GridTower.build` keeps the original grid as its terminal level. For an axis of
extent `n` and requested shrink `s`, a coarse extent is
`ceil((n - 1) / s) + 1`; the affine basis column is scaled by
`(n - 1) / (m - 1)`. This preserves both physical endpoints even when the
interval count is not divisible by the requested shrink, including anisotropic
and oblique grids.

`Transfer[P,F,D]` makes restriction and prolongation policies explicit.
`Continuation.run` visits levels in order, propagates typed transfer or
level-solver failure, and owns no registration optimizer policy.

`ScalarPyramid3.build` adds sampled values to that same grid authority. Each
level smooths the immutable native rank-three scalar image with a separable
Gaussian and delegates downsampling to the production affine resampler.
Physical sigma is converted through the norm of each complete affine basis
column, so anisotropic, reflected, and oblique grids retain their physical
axis scales. A build requires an explicit `ScalarPyramidWorkspace`; the
returned images are canonical immutable `Sampled` values, and a native
zero-smoothing level shares the original Ravel data.

## Consume registration and motion directions

Every `RegistrationResult` has `fixedToMoving`, the direction required by pull
resampling a moving image onto a fixed grid. Only
`BidirectionalRegistrationResult` also has `movingToFixed`. The exact
constructor derives that second direction from a lawful `SmoothIso`;
independently estimated and certified numerical maps retain their ordinary
`SpatialMap` capability. There are no result aliases named `forward`,
`backward`, or `inverse`.

Rigid motion follows the same polarity. `RigidPose` stores the authoritative
proper `movingToFixed` transform and derives `fixedToMoving`. Construction
cannot admit scale, shear, reflection, or a malformed homogeneous matrix.
`PoseSeries` validates non-empty, strictly increasing times and common live
endpoints.
`TimedScalarSamples.view` is a zero-copy check that the canonical sampled value
has exactly one `AxisKind.Time` axis with the matching extent. For a canonical
D3-plus-Time value, `volumeAt` returns a rank-three Ravel view over the same
immutable storage; motion estimation does not need a parallel series
container.

`PoseTrajectory` interpolates target-frame relative transforms with the
`Rigid3` exponential and logarithm. Queries at knots return the declared pose;
out-of-range queries require an explicit reject-or-clamp policy.
`AcquisitionSchedule` represents volume, grid-axis slice, and packet timing.
Slice and packet constructors require finite offsets and packet schedules must
cover each declared slice exactly once. `validateGrid` checks the schedule
extent against the selected grid-index axis; it never conflates that axis with
an oriented physical axis.

`MotionMetrics.framewiseDisplacement` computes consecutive motion from relative
SE(3) logarithms, so angle wrapping cannot turn a two-degree change into a
358-degree change. Physical point displacement and its summary require an
explicit non-empty support and reference pose. DVARS and censoring remain
ScalaFIM image-series policy.

`PhysicalCentroidTranslation` is a small generic estimator used to prove the
physical-geometry boundary. It evaluates voxel locations through the complete
grid affine, including origin, anisotropy, orientation, obliquity, and shear.
`MotionApplication.planAt` then delegates to `ResamplingPlan.affine` with
`pose.fixedToMoving`; it defines no second sampling kernel.

`CompiledRigidPairEstimator` is the first production-shaped estimator slice.
Compilation validates spatial-only rank-three immutable inputs and finite
voxels. Its default initializer computes physical centroids directly from the
full grid matrices without allocating point objects per voxel. `runFrom`
supports an explicit warm pose for outward series fitting.

The compiled estimator is immutable. Each run requires a distinct
`RigidEstimatorWorkspace`, while candidate evaluation delegates to the
production affine pull resampler. The default strategy is a six-parameter
inverse-compositional Gauss-Newton solve with explicit Levenberg-Marquardt
damping and target-frame SE(3) retraction. `RigidSamplingPolicy.Dense` is the
default fixed-domain selection and `RigidRobustLoss.default` is Huber loss
with threshold 1.5. An information-aware selection is available only by first
constructing a validated `RigidStencilControl` and passing it through
`RigidSamplingPolicy.informationAware`; squared loss is an explicit
`RigidRobustLoss.squared` choice. Either path compiles full-affine physical
coordinates, fixed gradients, and Jacobians. A reusable primitive sink
accumulates the selected loss, overlap, gradient, and 6-by-6 normal matrix in
the same fixed target-domain scan.

`RigidOptimizerControl` composes closed policies rather than an option map.
`RigidConvergencePolicy` owns iteration, physical step, objective, gradient,
and overlap limits; `RigidDampingPolicy` owns the bounded LM schedule and
rejection limit; `RigidCapturePolicy` owns restart seeds;
`RigidSamplingPolicy` and `RigidRobustLoss` own the compiled kernel choices;
and `RigidExecutionPolicy` owns reduction execution. Only
`DeterministicSerial` execution is currently supported. Callers may schedule
independent frames in parallel with separate workspaces, but the pair kernel
does not accept a worker count it would ignore. Accepted objectives are
strictly monotone, rejected proposals raise damping, and objective, gradient,
step, iteration, and rejection terminations remain distinct. The former
coordinate search is available only through the explicitly named
`RigidOptimizationStrategy.ReferenceCoordinateSearch`.

`run` ranks a deterministic, typed capture policy around the physical-centroid
initializer before the local solve. `runFrom` deliberately bypasses capture so
series and caller-supplied warm starts remain authoritative. The fit
diagnostics retain the strategy, capture selection, accepted-objective trace,
rejection count, final damping, and physical translation and rotation step
norms. Sampling, robust loss, convergence, damping, capture, and execution are
validated typed policies; no option is stringly typed or represented by a
sentinel.

`CompiledRigidSeriesEstimator` fixes an explicit reference pose at identity and
fits outward in both temporal directions. Every non-reference frame starts
from its already-fitted neighbor. The multiscale pair and series estimators
apply the same engine to filtered `GridTower` levels and continue the physical
SE(3) pose coarse-to-fine without rescaling its parameters.

`TemplateMotionEstimator` performs a caller-bounded number of refreshes. Each
refresh applies the current poses through the production application engine,
forms the explicitly named `TemplateStatistic.ValidityWeightedMean`, and
recompiles the filtered estimator. The result retains that statistic. The
declared reference is measured at exact identity on every pass and cannot
drift.

`CompiledMotionApplication` corrects a complete D3-plus-Time value and returns
canonical rank-four samples plus canonical validity-weight samples. Volume
timing compiles one production pull plan per volume. Slice and packet timing
uses affine-correct one-slice target grids and SE(3) trajectory poses at the
declared acquisition times. It defines no interpolation kernel and reports
zero materialized voxel-coordinate collections. The native protocol profile
selects `Interpolation.Lanczos5` explicitly; motion delegates that policy to
the canonical resampling implementation rather than owning another
windowed-sinc kernel.

`ResamplingPlan.scan` exposes the same compiled interpolation kernel to a
primitive `ResamplingSink`. The sink receives the canonical output-linear
index, value, and validity weight. The estimator uses it to reduce a candidate
pose directly into loss and overlap statistics. Motion application uses it to
write each volume or slice into the final rank-four value and validity
builders. Neither operation constructs a temporary sampled image. Use `run`
when you need a `Sampled` result; use `scan` only when a run-local reduction or
final builder can consume every sample immediately.

`PoseSeriesRecord` stores finite times, persistent endpoint IDs, and one
moving-to-fixed homogeneous matrix per sample. Restore validates counts,
frames, and every matrix as a proper rigid transform before reconstructing the
series.

`MIG-412` establishes the rigid and temporal foundation, and `MIG-413` adds the
canonical estimator and application engine. `MIG-420` supplies the production
compiled LM/GN kernel, typed policy surface, capture court, and internal
correctness and performance evidence. The evidence revision is currently
`uncommitted-worktree`, so it is not a release or external-superiority
receipt. `MIG-410` makes
`reframe4s-motion` the sole reusable motion engine. It adds a
validated frame-directed rigid transform in `reframe4s-lie`, SE(3) updates and
trajectory interpolation, explicit acquisition timing, filtered multiscale
estimation, typed termination, reusable workspaces, and pose-derived
diagnostics. The extant ScalaFIM spacing-only sampler and Euler-column
regularizer are correction fixtures rather than parity authorities.

ScalaFIM will retain BIDS discovery, `NeuroVec` compatibility, DVARS,
censoring, confounds, reports, and command policy. It will delegate reusable
estimation and application to reframe4s. The required analytic, differential,
metamorphic, adversarial, cross-platform, and performance checks are listed in
[the motion evidence plan](motion-evidence-plan.md).

`reframe4s-halfflow` remains outside the stable bundle. Its capability ledger
must record a passing multi-pair anatomical threshold before an experimental
candidate can be admitted, and admission still returns ordinary certified
maps rather than `SmoothIso`.

## Distinguish exact and numerical inverses

`SmoothIso[A,B,D]` has an analytic inverse and must satisfy both inverse laws.
`FramedAffine` is an exact isomorphism because it wraps one validated,
invertible geometry `Affine[D]`.

Numerical routines instead record an `InverseEstimate` or a
`CertifiedBidirectionalPair`. Their domains retain the exact input frame and
grid. Construction checks live endpoint ownership, convergence, residuals,
coverage, criteria, and implementation revision. Neither type is a
`SmoothIso`.

`TopologyCertificate` is similarly finite evidence. Its record includes the
grid, resolution, region, cell sampling rule, determinant threshold, criteria,
implementation revision, and diagnostics. Restoring or consuming a certificate
against a different scope returns a typed error.

These contracts, finite-difference checks, and compile-negative capability
tests run in
`modules/reframe4s-laws/shared/src/test/scala/reframe4s/laws/MapLawsSuite.scala`.

## Create and restore a finite domain

Locus identity generation is explicit and deterministic. A registry without an
ID source can restore a record but cannot create a fresh persistent identity.

```scala
import locus4s.*

val domain =
  for
    registry <- DomainRegistry.withSequentialIds("cortex")
    resolved <- registry.fresh("cortex", 100)
    point <- resolved.space.point(42)
  yield (resolved.registry, resolved.space, point)
```

`FiniteSpace[S]` is its live owner token. Equal ordinals in different live
domains are different points. A `DomainAlignment` can rebind a point, region,
selection, or indexed field only after persistent records agree.

The core identity examples run in the standalone locus4s repository at
`modules/locus4s-core/shared/src/test/scala/locus4s/DomainSuite.scala`.
Indexed fields and aggregation laws run there at
`modules/locus4s-laws/shared/src/test/scala/locus4s/laws/IndexedFieldLawsSuite.scala`.

## What remains in ScalaFIM

The bridge artifact may convert a grid index to a finite-domain ordinal and
back. It does not define parcels, searchlights, ROIs, neuroimaging file
formats, or image storage. `image4s-nifti` owns only basic format mechanics.
BIDS discovery, compressed-file staging policy, acquisition semantics, and
analysis workflows remain in ScalaFIM and consume the foundation through
compatibility adapters.
