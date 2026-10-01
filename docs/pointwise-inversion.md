# Pointwise numerical inversion

`reframe4s-field` provides `PointwiseInversion` for inverse queries that should
be solved at their actual coordinates. It constructs no inverse lattice and
does not interpolate inverse estimates between queries.

For a declared displacement map `D(x) = x + d(x)`, create a solver with
`PointwiseInversion.displacement(displacement, settings)`. For the declared
chain `P(x) = A(D(x))`, use:

```scala
val settings = InversionSettings.create(
  maximumIterations = 100,
  tolerance = 1e-8,
  divergenceRatio = 4.0
)

val solver = settings.flatMap { config =>
  PointwiseInversion
    .displacementThenAffine(displacement, affine, config)
    .left.map(InversionError.Map.apply)
}

val evidence = solver.map(_.at(query))
```

Here `displacement` is a `CoverageReportingMap[T, U, D]` returning **absolute
coordinates**, `affine` is an `AffineMap[U, S, D]`, and `query` is a
`Point[S, D]`. The method checks the intermediate frame alignment before
constructing a solver. Queries and mapped results retain runtime owner checks.
The displacement's input and output coordinates must share a basis and units;
the method name explicitly declares that assumption. It does not discover or
decompose an opaque composed map.

The affine case computes `z = A.inverse(query)` analytically, initializes
`x = z`, and iterates `x <- x - (D(x) - z)`. The displacement-only case uses
`z = query`. There is no inverse-lattice dependency, warm start, retry or
alternate algorithm. `InversionSettings` and the fixed-point update follow the
existing lattice-based `NumericalInversion` convention.

Each `at` call returns `PointwiseInverseResult`:

| Member | Meaning |
| --- | --- |
| `query` | The original query with its frame owner. |
| `best` | Optional covered iterate and its measured Euclidean residual `|P(x) - query|`. |
| `iterations` | Number of updates attempted; initial evaluation counts as zero. |
| `status` | The terminal outcome listed below. |

Residuals, the convergence tolerance, divergence ratio and best-iterate
selection all use the **full map in its final target coordinates**, including
affine scale and shear. For millimetre coordinates, the residual is in
millimetres. An iteration budget of `n` permits the initial evaluation and up
to `n` updates, evaluating the final update before declaring the limit.

| Status | Meaning |
| --- | --- |
| `Converged` | A covered iterate has a finite residual at or below tolerance. |
| `MaxIterations` | The update budget was exhausted without convergence. |
| `Diverged` | Residual exceeded `divergenceRatio` times the initial residual, or numerical residual/update arithmetic became nonfinite. |
| `LeftSupport` | An evaluation was outside support, including constant filling or source-preserving extension. |
| `Failure(error)` | A structural map, frame or geometry error occurred. |

`best` is absent when no finite covered residual was obtained. For unsuccessful
queries it is diagnostic evidence and must not be used as an implicit successful
inverse. The residual always belongs to the stored point, which may precede
the terminating iterate. No residual is invented for an unevaluated point.

Coverage must be explicit. `DenseMap` already reports it.
`CoverageReportingMap.lift` is available when the caller can declare that every
successful evaluation of an ordinary map is covered. Lifting a wrapper that
hides a dense map's boundary policy can lose support information; retain the
reporting map or use `CoverageReportingMap.compose` instead.

This API supplies numerical estimates, not `SpatialMap`, `SmoothIso`, exact
inverse, uniqueness or topology certificates. Contractive displacements admit
the fixed-point argument while their iterates remain in support. A finite
field can reject the initialization even when a preimage exists elsewhere.
Noncontractive maps may oscillate, diverge, or converge for particular queries;
one successful query establishes only its measured residual. Affine inversion
also remains subject to floating-point conditioning. Structural errors use the
map's typed error channel; exceptions thrown by a caller's implementation are
not converted into results.

The shared `PointwiseInversionSuite` checks analytic constant and affine-composed
fields, independent bisection of smooth contractive fields and sampled linear
and cubic maps, full-map tolerance under affine scaling, iteration limits,
oscillation, divergence, support edges, frame mismatches and structural errors.
Run it and the existing field regression tests on both platforms:

```sh
sbt -J-Xmx4G -batch 'reframe4s-fieldJVM/test' 'reframe4s-fieldJS/test'
```

These provider tests do not qualify ScalaFIM's anatomical route. Its all-vertex
oracle, voxel/value/support comparisons, and real JVM/Scala.js cost remain
consumer-owned gates. No real-data accuracy or performance claim follows from
this implementation.
