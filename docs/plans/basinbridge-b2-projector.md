# BasinBridge B2: background-plus-residual projector

Mote issue: `sf-01ky5p208fn02gryf4mg943v1y`.

B2 is the first accuracy-bearing sparse-to-dense bridge slice. It consumes
typed physical-coordinate correspondences and emits a dense displacement
field. It is synthetic-only in this checkout: it does not claim block-search
accuracy, ANTs parity, or anatomical HalfFlow improvement.

## Contract

For each correspondence, `q` is the fixed point, `p` is the moving point, and
the observation is:

```text
z = (p + q) / 2
t = p - q
```

The projector performs the following fixed sequence:

1. Estimate a confidence-weighted translation `b0` from the tangents.
2. Apply one Cauchy IRLS reweight around `b0` and estimate the robust
   background `b`.
3. Splat each residual `t - b` at its physical midpoint `z` using clipped,
   renormalized trilinear weights. Confidence and the Cauchy weight multiply
   the support.
4. Add a weak uniform zero-residual prior to the support. This makes regions
   without observations converge to zero residual rather than to an implicit
   identity displacement.
5. Apply the existing physical-scale, support-normalized `Gaussian3D` kernel
   to each residual component with reflection at the finite-lattice boundary.
6. Restore `b` at every voxel. When dense support observes a small residual,
   retain that observed value so finite-lattice reflection does not introduce
   artificial affine boundary strain; large residuals remain on the robust
   smoothed path.

The implementation exposes an allocation-controlled `projectInto` path and a
convenience `project` path. Scratch ownership is explicit through
`BasinBridgeProjectorWorkspace`; the projector adds no private registration
convolution family.

## Accuracy evidence

`BasinBridgeProjectorSuite` checks on both JVM and Scala.js:

- sparse identical translations produce the same background motion across the
  complete domain, including unsupported corners;
- low-confidence/missing regions approach the estimated background;
- one Cauchy reweight limits an isolated outlier;
- correspondence order and common coordinate translation are equivariant;
- a small analytic rotational residual is preserved at the finite-lattice
  boundary;
- the fast separable implementation matches an independent direct reflected
  Gaussian reference on a small grid;
- controls reject invalid values and all emitted field samples are finite.

These tests establish the projector contract only. The current repository has
no block-search adapter and no wired path from this projector into the
experimental HalfFlow engine. Matcher execution, end-to-end HalfFlow impact,
runtime, allocations, and external anatomical validation remain separate
gates.
