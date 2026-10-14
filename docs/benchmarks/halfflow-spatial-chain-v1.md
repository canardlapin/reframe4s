# HalfFlow source-interpolation and residual-composition derivative audit

Separately differentiating source-image interpolation and residual-map composition
passes the declared directional-derivative criterion on the observed OASIS
11 → 12 fine-start checkpoint. The unchanged production gradient fails all three
directions. This identifies a concrete candidate correction for the spatial
gradient calculation; it does not establish an anatomical improvement or qualify
HalfFlow.

This extends the [intensity-chain isolation](halfflow-intensity-chain-v1.md) under
HFQ-D01 (`bd-01M4X4DY7DQCVK33MCK3T17NA1`) and prepares HFQ-D02. The
[evidence manifest](evidence/halfflow-spatial-chain-20261014/artifact-manifest.json)
retains the complete results, source reconstruction, tests, provider review and
failed compiler attempt. MRI and dense-map payloads remain local.

## What was differentiated

Production computes finite spatial differences of images already warped onto
the midpoint lattice. An actual candidate instead composes the midpoint residual
map with a half-step, applies an affine, and samples the source-image trilinear
interpolant. These discretizations need not give the same derivative.

The diagnostic computes the source trilinear interpolant's derivative at each
actual sample point, then applies the affine, residual-map and work-grid
coordinate Jacobians in composition order. It combines those physical covectors
with the already-validated CC intensity derivatives and the action's signs and
half-step factors. Residual derivatives use symmetric slopes at work-grid
vertices. The production optimizer, gradient, support, weights, candidate
integration and accumulated regularization are unchanged.

The inspected image4s APIs provide scalar linear sampling and Sobel/Scharr image
stencils, but do not expose this arbitrary-coordinate interpolant derivative.
The [provider review](evidence/halfflow-spatial-chain-20261014/spatial-chain-build-v1/provider-review.json)
records their pinned source identities. The diagnostic differentiates the
existing HalfFlow sampler polynomial and retains the current physical owners.

Missing derivative support is counted as unavailable. Source-grid knots use a
counted symmetric slope; a simultaneous source/residual kink is not claimed to
have a unique linear derivative. On this real checkpoint, both arms evaluate
all **149,166** nonzero intensity derivatives, with **zero unavailable samples
and zero source-grid knots**.

## Measured agreement on the real checkpoint

The same three directions and eight perturbations, from 0.5 to 0.002 mm, were
declared before execution. Each direction must have a minimum symmetric relative
error at most 0.005 and a region of three consecutive decreasing-error samples.
The spatial-chain check additionally requires complete derivative support.
No criterion or tolerance was relaxed.

At the smallest perturbation, **0.002 mm**, the measured percentage errors are:

| Direction | Production gradient | Source/interpolation composition chain | CC intensity chain |
| --- | ---: | ---: | ---: |
| Translation | 9.6975% | 0.083729% | 0.0002991% |
| Smooth wave | 16.0727% | 0.0025524% | 0.0008943% |
| Negative production gradient | 4.4850% | 0.0003653% | 0.000001973% |

All three spatial-chain directions pass the full criterion, as do the three
intensity-chain directions. The production gradient fails all three. All 24
earlier production-gradient, finite-difference, loss and intensity-chain
measurements reproduce **exactly**, and the frozen current objective is unchanged.
The [receipt](evidence/halfflow-spatial-chain-20261014/spatial-chain-pilot-v1/receipt.json)
contains every verdict and support denominator.

![All derivative measurements](evidence/halfflow-spatial-chain-20261014/spatial-chain-figures-v1/spatial-chain.png)

The spatial-chain curves are not monotonically decreasing over the whole tested
range. In particular, translation retains about 0.084% error at the smallest
step. Passing the declared criterion does not establish an exact derivative
under every interpolation or boundary condition. The original negative-gradient
direction is already a descent direction in this case; these results do not
demonstrate an ascent-direction failure.

## Verification and reproduction

Seven focused tests pass on both JVM and Scala.js: four new spatial-chain checks
and three existing frozen-objective checks. The new tests cover exact trilinear
polynomials, counted knot slopes, invalid and zero-weight interpolation support,
and actual paired composition on mixed oblique grids for both symmetric and
fixed-anchor actions. The acquired-image runner compiles. The first compiler
attempt used four indices on a rank-three scalar image; the corrected access
passes, and the failed log remains in the archive.

The frozen numerical source is
`a3827bfdff07a34f5c54f5229ab92b515b1b20bf45fbf80751baa228c3f7afaf`.
The verified source patch reconstructs it from the frozen D01 engine: P01's
`control-source.tar.gz`, the D01 `instrumentation.patch`, then this archive's
`source.patch`. Run `objective_probe.py freeze` against the original verified
trajectory pilot and pass the preceding `intensity-chain-pilot-v1` as
`--derivative-baseline`; use the pinned classpath and a fresh output directory.
The run took 334.74 diagnostic wall seconds alongside other work, which does
not support a production-performance comparison.

## Remaining work

Before making this the production gradient, repeat acquired-image checks at
other states and pyramid scales, including image-order, boundary, mixed-grid and
applicable regularization cases. The synthetic mixed-grid checks are useful but
do not replace those acquired-image checks. Then test a controlled optimizer
candidate against the frozen baseline, retaining all convergence, geometry,
anatomy and runtime gates.

The [trajectory evidence](halfflow-deferred-capture-v1.md) already shows that
improving both the optimizer intensity objective and an independent fixed-region
intensity audit can worsen anatomical overlap. A more faithful gradient alone
therefore remains an unproven anatomical remedy. D01's full trajectory panel,
D02's broader audit and the qualification epic all remain open.
