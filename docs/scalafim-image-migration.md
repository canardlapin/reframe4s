# Migrating ScalaFIM images to image4s and Ravel

This guide defines the ScalaFIM side of `MIG-450`. It applies to
`NeuroVol`, `NeuroVec`, `SparseNeuroVec`, component fields, image I/O, and
algorithms that traverse their values.

The migration is complete only when ScalaFIM has no dense multidimensional
array or sampled-image implementation of its own. Source compatibility may
keep familiar names for one release, but those names must refer to the
image4s value directly or expose a narrowly scoped view over that value.

## Dense images

A dense image retains one value:

```text
Sampled[F,D,A,Role,R]
  data: Ravel NDArray[A,R]
  grid: Grid[F,D]
  nonSpatialAxes: NonSpatialAxes
```

`NeuroVol[A]` denotes a D3 `Sampled` value. `NeuroVec[A]` denotes a D3 value
with a declared Time axis. A compatibility class may retain the `Sampled`
value while callers migrate, but it may not retain another array, affine, or
shape as an independent authority.

Use ranked coordinates in algorithms:

```scala
val voxel = volume(i, j, k)
val sample = series(i, j, k, t)
```

Do not read `values.data(linearOffset)`. Ravel’s flat storage order is not an
image contract, and a view need not be contiguous. When an algorithm already
has a legacy x-fastest spatial ordinal, convert it to `(i,j,k)` once outside
the inner time or component loop.

## Legacy ingress and export

ScalaFIM’s historical buffers place the first axis fastest. Canonical Ravel
arrays place the last logical axis fastest. The same flat buffer therefore
cannot represent both layouts with the same shape.

A public legacy constructor must:

1. validate rank, extents, buffer length, affine, and axis metadata;
2. copy each logical `(i,j,k[,t])` value into canonical Ravel order exactly
   once;
3. return a receipt that says the input was materialized; and
4. retain only the resulting `Sampled` value.

An export for NIfTI or a legacy consumer must be named as a copy, such as
`copyLegacyLinear`. It always returns fresh x-fastest storage. Algorithms must
not call this export to obtain a convenient hot-loop buffer.

## Views

Selecting time `t` from a series uses `Sampled.selectTime`. The returned
volume shares the immutable Ravel storage with the series. It may be strided
and non-contiguous.

Cropping uses `Sampled.spatialView`. The view shares storage and has a new
grid whose full affine maps the cropped origin to the corresponding source
index. A caller must not construct a cropped `NeuroSpace` independently.

## Sparse series

`SparseNeuroVec[A]` is not a second image or ndarray hierarchy. It contains:

- one checked spatial support tied to the dense image grid;
- one duplicate-free ordered sequence of supported voxel ordinals;
- one full-grid ordinal-to-support-position lookup; and
- one compact `Ravel NDArray[A,Rank[2]]`.

The compact axes are `(time, supportPosition)`. Support order is part of the
value’s contract. Constructors reject duplicate and out-of-bounds ordinals.
Selection APIs choose one explicit missing-voxel policy: reject, drop, or
fill. Densification allocates a new dense `Sampled` value and scatters by
logical voxel coordinate.

The sparse type may derive a dense mask for compatibility. It must not retain
both the mask and another independently authoritative support description.

## Component fields

Displacements, velocities, momenta, and absolute coordinate maps use an
image4s `ComponentImage` with one Direction axis. For a D3 field, logical
access is `(i,j,k,component)` and the direction extent is three.

ScalaFIM must not keep a component-planar `NDArray` beside the
`ComponentImage`. A legacy component-planar import is a materializing boundary;
an export is an explicitly named copy. Field interpolation and differential
kernels read ranked Ravel coordinates.

## Removal order

Migrate in this order:

1. Admit the exact image4s and Ravel artifacts and validate the legacy layout
   conversion.
2. Move `NeuroVol` and `NeuroVec` storage and geometry authority to
   `Sampled`.
3. Replace every dense consumer’s raw offsets with ranked coordinates or
   Ravel kernels.
4. Move `SparseNeuroVec` to typed support plus compact rank-2 Ravel.
5. Move component fields to `ComponentImage`.
6. Remove ScalaFIM’s `NDArray`, `NeuroImage`, and `NeuroImageView`
   declarations.
7. Run the matched workload, I/O, serialization, JVM, Scala.js, and downstream
   consumer gates.

Do not remove the legacy types before their callers have moved. Do not retain
the old implementations behind deprecated names after callers have moved.

## Executable removal audit

From the reframe4s checkout, run:

```text
node scripts/verify-image-unification.mjs \
  --scalafim /absolute/path/to/scalafim
```

The audit fails when ScalaFIM still declares `NDArray`, `NeuroImage`, or
`NeuroImageView`, parallel geometry or component-field owners, or duplicate
dense-field kernels; when any Scala source uses `.values.data`; when the dense,
sparse, and component-image target representations are absent; or when the
consumer build selects a mutable image4s checkout instead of the immutable
revision. During migration, `--report-only`
prints the remaining findings without converting the expected failure into a
successful acceptance receipt. Add `--summary-only` when only the counts are
needed.

This structural audit is necessary but not sufficient. Final acceptance also
requires:

```text
# reframe4s
sbt -J-Xmx4G -batch testAll
node scripts/verify-prd.mjs
node scripts/verify-build-graph.mjs
node scripts/verify-symbol-ownership.mjs

# ScalaFIM, consuming the immutable image4s revision directly
sbt -J-Xmx4G -batch compileAll
sbt -J-Xmx4G -batch testAll
sbt -J-Xmx4G -batch examplesTest
```

The ScalaFIM run must include image I/O and serialization round trips, dense
and sparse conversion laws, representative atlas, motion, MVPA, registration,
and workflow consumers, and the matched allocation and checksum court from
`AC-059`. The explicit `scalafim.image4s.build` development override can
diagnose coordinated changes but cannot serve as the final immutable-consumer
receipt.
