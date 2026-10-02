# Acquired MRI development evidence, 2026-10-02

See [the protocol and reproduction commands](../../real-image-development.md).

- `selection.json` and `acquisition.json` pin the public Mindboggle OASIS inputs.
- `development-3mm/` preserves failed initial fits, inverse-resolution probes,
  and the support-taper attempt that failed the independent endpoint gate.
- `development-1p5mm/run-05/` is the exhausted default affine budget.
- `development-1p5mm/run-06/` is the longer affine budget and rejected exterior
  folds. Brain-only geometry success did not admit this output.
- `development-1p5mm/run-07/acceptance.json` is the passing bounded example:
  native export, complete-grid sampled topology, brain inverse/reload/coverage,
  and improvement over affine on all 62 shared cortical labels, both directions.
- `transfer-1p5mm/run-01/` is the failed untuned transfer. No nonlinear output
  exists for it. Diagnostic logs implicate the hard contrast-energy transition.
- `frozen-recipe.json` pins the named mode and complete HalfFlow plan.
  Source receipts and patches bind implementation separately. The final
  low/high face product is exactly equal to run-07's nearest-face product on
  these nonoverlapping collars; `boundary-support.json` checks this directly.
- `verification/` contains actual check logs and exit metadata. Historical
  diagnostics may exit nonzero by design; only final verification is summarized
  as passing when its exit status is zero.

Pull-map keys describe target-to-source sampling. ANTs keys describe
source-to-target registration. New reports also carry explicit `target` and
`source`; match these fields when comparing scores. ANTs is a same-input
reference, not a matched-runtime experiment or an acceptance threshold.

Large MRI/map binaries remain in the ignored local directory
`benchmarks/flashalign/local-results/real-mri-20261002/`; they are not Git
artifacts. The local archive receipt and SHA-256 manifest identify them.
Original `/private/tmp/reframe4s-real-mri/` paths in historical logs map to
that directory with the same relative suffix. Reproduction downloads verify
pinned archive and selected-file hashes; no NITRC account is required.

The old diagnostic probes are bounded research artifacts. In particular,
`endpoint_boundary_probe.py` expects a rejected run directory, prepared data
and a new output JSON; `endpoint_worst_pointwise_probe.py` expects that rejected
run plus a new output JSON. They do not establish global injectivity.
`boundary_support_overlap.py RUN DATA OUTPUT` checks initial/final support,
not every intermediate nonlinear state.

This evidence does not close HalfFlow's separate 20-pair, two-dataset admission
requirement. The contrast-transition follow-up is `bd-01M3YCH2ZBB63SFMBYFHWCQGDV`.

Raw source patches and the run-03 compiler log are stored as deterministic
`.gz` files to preserve their exact bytes, including patch context whitespace.
Use `gzip -dc PATH.gz` to inspect them.
