# Real-image registration development court

Started 2026-10-02 for `bd-01M3Y5ZZRMYC1V8V0A8Z2GRXGD`, under the existing
real-image goal `bd-01M2EPJ70MMKEP49N65ND1ATGF`.

The development pair now passes the declared geometry and anatomical-improvement
criteria. The untuned transfer pair fails Flashalign convergence; broader
reliability remains unqualified. Final 1.5 mm forward cortical scores are:

| Method | Macro Dice | Median per-label HD95 |
| --- | ---: | ---: |
| Flashalign affine | 0.357924 | 5.612 mm |
| Flashalign + HalfFlow | 0.412700 | 5.302 mm |
| ANTs reference | 0.496983 | 4.743 mm |

All 62 shared manual cortical labels are retained. See the
[machine-readable results](evidence/real-mri-20261002/results.json) and
[three-plane QA](evidence/real-mri-20261002/development-1p5mm/run-07/qa.png).

## Input preflight and selected experiment

The initially available sub-1002 T1 to MNI152Lin pair was rejected at input
inspection, before method execution: the BET-named moving mask retained neck
and other nonbrain tissue, while an alternate mask had the wrong physical
affine. Its input plate and preparation receipt are retained. The historical
sub18 comparator and prepared images are absent locally. IBSR download requires
an authenticated NITRC session; the user did not have a local copy. IBSR is the
Internet Brain Segmentation Repository, a collection of acquired brain MRIs and
expert anatomical segmentations. It is not required for the alternative below.

The executable alternative is the openly downloadable OASIS-TRT-20 subset of
[Mindboggle-101 release 3](https://zenodo.org/records/22070005), CC BY 4.0.
Use original-space brain images, not the supplied affine-to-MNI images. Before
method execution, select the first four distinct subject identifiers in lexical
order: first to second for development, third to fourth for recipe transfer.
The cortical labels are used only after fitting. Positive support of the supplied
brain intensity volumes supplies the optimization mask, independently of the
cortical labels. Record the input provenance and the labeler publication:
Klein and Tourville (2012), DOI 10.3389/fnins.2012.00171, and the originating
OASIS acquisition described in the dataset documentation.

Before execution, hash all inputs and preparation outputs. Prepare brain-only
intensities using the image-derived masks, crop each independently to
its brain bounding box plus 24 mm, resample to 3 mm isotropic RAS grids, and
divide intensities by the positive 99th percentile. No registration, labels,
or comparator output participates in preparation. This is a development
experiment, not a sealed generalization claim.

Run the public Flashalign affine API with its WithinModality preset and coherent
header-world identity then capture. Save its complete affine checkpoint. Run
HalfFlow directly from that affine with the existing budget2x fine plan: shrinks
2/1, smoothing 10/6 mm, maximum steps 1/0.6 mm, 10 accepted steps and 24 attempts
per level, symmetric midpoint action. Keep the production geometry thresholds
and 0.2 mm residual-inverse export limit. Omit BasinBridge in this first lane to
isolate the affine handoff and fine stage. Identity-start HalfFlow is a separate
diagnostic lane, never a substitute for the affine-initialized result.

The runner receives images and a fixed optimization mask only. Anatomical
evaluation labels must be loaded by a separate evaluator after fitting.

## Acceptance and evidence

Persist both absolute RAS-mm pull maps, validity masks, sampled volumes, affine
matrix, stage diagnostics, input hashes, source identity, and the actual command.
Rejected candidates are explicitly named and retained; an upstream affine is
never relabeled as a successful HalfFlow result.

Independently reload both maps and reconstruct their images with SciPy. Check
agreement on valid interior source support to 1e-6 times the image intensity
scale. Evaluate physical-coordinate Jacobians by independent finite differences.
For each direction, require no non-positive sampled Jacobians over the complete
one-voxel-interior grid, and minimum >= 0.05 in the declared brain evaluation
region. Require >=99% brain sampling and round-trip coverage and maximum
round-trip error <=0.2 mm over the covered brain region. Report all excluded
samples and whole-grid errors separately. These sampled checks do not prove
continuous invertibility between samples.

For a labeled pair, require improvement over affine in macro-average anatomical
Dice and median per-label HD95, using every shared nonzero anatomical label and
retaining every failure. Report ANTs from the same prepared inputs as a reference.
Inspect fixed, registered, overlay, and difference views in all three planes.
For the initial unlabeled T1-to-template pair, NCC and brain-mask Dice are
diagnostics only: they cannot establish internal anatomical accuracy. A successful
process or export alone does not satisfy the real-image goal.

Any recipe change after results are observed is a new, named development attempt.
Preserve the original settings and result, then freeze a successful recipe before
trying a second pair. Neither a development success nor its transfer check closes
the separate 20-pair/two-dataset HalfFlow admission gate.

The ANTs reference uses antspyx 0.6.3 on the same prepared images: SyN with CC
radius 2 and iterations 40/20/0, preceding Mattes affine iterations
1000/500/250/0, full affine sampling, fixed mask at all stages, one thread,
and seed 20261002. This is a reference registration, not a matched runtime
comparison. Parameters are saved before execution.

## Development findings (2026-10-02)

The frozen subjects are OASIS-TRT-20-1 to OASIS-TRT-20-10 for development,
and OASIS-TRT-20-11 to OASIS-TRT-20-12 for transfer. All 62 shared nonzero
manual cortical labels enter evaluation. The original images, labels, selection
rule, archive provenance and hashes are bound in
[evidence/real-mri-20261002](evidence/real-mri-20261002/selection.json).

The initial 3 mm run rejected all four Flashalign candidates. Three analytic
regressions exposed and reproduced optimizer defects: objective-rejected retries
barely shortened their physical step at the damping floor; damping alone could
cause a false small-step convergence; and the final accepted update's fresh
gradient was not checked before declaring budget exhaustion. The repair contracts
rejected physical steps, requires an undamped/unclipped small model step for that
termination, and checks the final fresh gradient and rank. Acceptance ratios,
objective thresholds, retry counts and iteration budgets are unchanged.
Directional finite differences on the failed MRI endpoint agree with the analytic
derivative. This was a retry-control defect, not evidence of a derivative sign bug.

Flashalign then succeeded. Independent affine map reload and geometry checks
passed in both directions. HalfFlow's original 20-step recipe improved cortical
Dice, but failed inverse export and contained one non-positive sampled endpoint
Jacobian. Increasing inverse iterations from 50 to 200 left the failure unchanged.
An independent SciPy root probe found accurate local inverses but large errors
when those inverses were sampled and interpolated on the same 3 mm grid. Its
bounded positive-Jacobian and multiple-start checks do not prove global injectivity.

The velocity trace located a sharp support cutoff: normalized Gaussian smoothing
retained a nonzero weighted-average velocity at support about 1e-6, immediately
adjacent to a voxel set to zero. The new **opt-in** `velocitySupportFloor` controls
only HalfFlow velocity construction: Gaussian numerator N and support W give
`N / (W + floor)`. A zero floor preserves the historical filtering path exactly;
image filtering is unchanged. The named `fine-taper` recipe uses 1e-3. This value
is a development setting, not a universally calibrated default. Removing a tail
maximum also changes global step scaling, so anatomical results must be reevaluated.

| 3 mm development attempt | Native export | Forward cortical Dice | Forward median HD95 | Brain round-trip maximum, forward / reverse |
| --- | --- | ---: | ---: | ---: |
| Repaired Flashalign affine | Accepted | 0.364782 | 6.708 mm | <1e-12 / <1e-12 mm |
| HalfFlow original recipe | Rejected | 0.375307 | 6.708 mm | 0.823 / 1.061 mm |
| HalfFlow support taper 1e-3 | Accepted | 0.390264 | 6.354 mm | 0.253 / 0.441 mm |
| ANTs reference | Reference only | 0.424962 | 6.000 mm | Not evaluated here |

**Neither 3 mm HalfFlow attempt passes the declared independent geometry court.**
Native export admission checks residual inverse error and topology; it records
endpoint round-trip error without gating it. The independent evaluator retains
the stricter 0.2 mm endpoint gate. The next named attempt uses 1.5 mm prepared
images, freshly fitted Flashalign, the same taper/step budgets/export tolerances,
and a newly fitted same-input ANTs reference.

In map filenames, `fixed-to-moving` denotes pull coordinates used to render the
moving image on the fixed grid. Thus its anatomical score is the forward
moving-to-fixed registration score. Saved rejected candidates remain diagnostics.

At 1.5 mm, the default 50-update affine budget exhausted on all four candidates
at the 3 mm pyramid level. Run-05 is retained as a failure. Run-06 names a separate
`fine-taper-long` configuration, with only the affine per-level budget extended
to 200. Its [policy receipt](evidence/real-mri-20261002/budget-200-policy.json)
explicitly disclaims calibration. The default Flashalign preset remains unchanged.

The runner now returns failure after preserving diagnostics for any failed fit
or rejected native export. The evaluator writes its full report and returns
status 2 when the independent geometry gate fails. Neither command treats a
saved candidate as a successful registration by itself.

## Reproduce the current development recipe

Use Python 3.12 with `numpy==2.3.5 scipy==1.15.3 nibabel==5.4.2
matplotlib==3.11.2 antspyx==0.6.3`. The Scala runner is opt-in and unpublished.
From the repository root, choose a new output directory for each attempt:

```sh
court="$PWD/benchmarks/flashalign/local-results/reproduction"
python benchmarks/flashalign/real_image_court.py fetch --output "$court/inputs"
# fetch accepts --archive /path/OASIS-TRT-20_volumes.tar.gz to reuse a
# download after checking its pinned SHA-256. It retrieves ~408 MB, not 5.2 GB.
subjects="$court/inputs/selected"
moving="$subjects/OASIS-TRT-20-1"
fixed="$subjects/OASIS-TRT-20-10"
python benchmarks/flashalign/real_image_court.py prepare \
  --moving "$moving/t1weighted_brain.nii.gz" \
  --fixed "$fixed/t1weighted_brain.nii.gz" \
  --moving-mask "$moving/positive-intensity-mask.nii.gz" \
  --fixed-mask "$fixed/positive-intensity-mask.nii.gz" \
  --spacing-mm 1.5 --output "$court/data"
sbt -J-Xmx6G "flashalignBenchmarkJVM/runMain reframe4s.halfflow.RealImageRegistration $court/data/moving.nii.gz $court/data/fixed.nii.gz $court/data/fixed-mask.nii.gz $court/fit fine-taper-boundary"
python benchmarks/flashalign/real_image_court.py evaluate \
  --data "$court/data" --output "$court/fit" --lane affine \
  --moving-labels "$moving/labels.DKT31.manual.nii.gz" \
  --fixed-labels "$fixed/labels.DKT31.manual.nii.gz"
python benchmarks/flashalign/real_image_court.py evaluate \
  --data "$court/data" --output "$court/fit" --lane halfflow \
  --moving-labels "$moving/labels.DKT31.manual.nii.gz" \
  --fixed-labels "$fixed/labels.DKT31.manual.nii.gz"
python benchmarks/flashalign/real_image_court.py assess \
  --affine-report "$court/fit/affine-evaluation.json" \
  --halfflow-report "$court/fit/halfflow-evaluation.json" \
  --frozen-recipe docs/benchmarks/evidence/real-mri-20261002/frozen-recipe.json \
  --output "$court/fit/acceptance.json"
ITK_GLOBAL_DEFAULT_NUMBER_OF_THREADS=1 python benchmarks/flashalign/real_image_court.py ants \
  --data "$court/data" --output "$court/ants"
ITK_GLOBAL_DEFAULT_NUMBER_OF_THREADS=1 python benchmarks/flashalign/real_image_court.py evaluate-ants \
  --data "$court/data" --output "$court/ants" \
  --moving-labels "$moving/labels.DKT31.manual.nii.gz" \
  --fixed-labels "$fixed/labels.DKT31.manual.nii.gz"
```

Use `plot --fixed ... --warped ... --mask ... --output ... --title ...` to make
three-plane QA plates from saved NIfTI outputs. The forward registered NIfTI is
`halfflow-fixed-to-moving-warped.nii.gz`. `evaluate` also writes per-label results,
coverage/exclusions, Jacobians and inverse residuals in `halfflow-evaluation.json`.
The optional final runner argument is an accepted saved moving-to-fixed affine
JSON; its file hash is recorded whenever fitting is deliberately reused.

The transferred recipe must use the second pair (11 to 12) unchanged. A result
below ANTs remains below ANTs; successful geometry and improvement over affine
are the bounded acceptance target here, not superiority over the reference.

Run-06's longer-budget affine fit succeeded. At 1.5 mm, HalfFlow improved forward /
reverse cortical Dice from 0.357924 / 0.367895 to 0.412700 / 0.428218, and median
HD95 from 5.612 / 6.000 mm to 5.302 / 5.408 mm. Brain round-trip maxima were
0.0522 / 0.0712 mm with 100% coverage and positive brain Jacobians. Nevertheless,
the complete maps had 555 / 187 folded samples and remained rejected.

Independent localization put every fold outside the work grid after the initial
affine query, at least 15 mm from brain support. The extrapolated forward residual
itself can reverse orientation; pointwise inverse solving alone cannot repair
that exterior contract. Run-07 adds an optional `velocityBoundaryWidthMm = 6`:
a product of cubic smoothstep windows leaves both outermost node layers at zero
on every face. Width is measured perpendicular to physical grid faces, including
shear. With identity initial residuals, this preserves identity boundary cells
through integration and composition, while affine factors keep exact global
behavior. The zero default preserves historical experiments. This control cannot
erase deformation already present at an initial residual boundary.

The named recipe is now `fine-taper-boundary` (same 1e-3 support floor, budget-200
affine, 20 accepted fine updates, and unchanged admission thresholds). On the
development pair, independent checks of affine-warped brain masks found zero
brain overlap with its taper at both levels and both arms. Run-07 deliberately
reuses run-06's accepted affine, whose exact file hash is recorded.

Run-07 passes native admission and the independent geometry checks in both
directions. All 4,468,055 sampled interior Jacobians are positive; the minima
are 0.665271 / 0.459988. Brain sampling and round-trip coverage are both 100%,
with maximum round-trip errors 0.052199 / 0.071169 mm. Reloaded scalar images
agree within 6.7e-16. The cortex scores equal run-06's scores above: the boundary
repair removes the exterior folds without changing these anatomical results.
The final residual boundary cells are exactly identity. Initial and final brain
support does not overlap the taper; intermediate optimizer states were not
independently checked for overlap. Whole-grid round-trip maxima remain
0.284 / 0.261 mm, reported separately from the predeclared brain-region gate.

The final window multiplies separate low and high face factors to remain smooth
when unusually wide collars overlap. An independent grid check confirms this
is exactly equal to the run-07 window at both development resolutions.

The evaluator checks the complete geometric interior and fails on invalid or
nonfinite Jacobian samples, rather than excluding invalid exterior regions.
It checks image, mask and map grid identity. `evaluate` gates geometry;
`assess` separately requires accepted native export, matched image and label
identities, the frozen mode and plan hash, complete labels, and strict Dice and
median-HD95 improvement over affine in both directions. Source receipts bind
the fitted implementation separately. Neither command consumes ANTs results
as an acceptance threshold. ANTs reports retain their historical source-to-target
keys, whereas pull-map reports use target-to-source keys; explicit `target` and
`source` fields disambiguate new reports.

The first untuned transfer attempt on subjects 11 to 12 failed in Flashalign:
all four capture candidates ended at `TrialAttemptLimit`. No HalfFlow output
was produced or substituted. That attempt and its source identity remain in
`evidence/real-mri-20261002/transfer-1p5mm/run-01`. The successful development
pair is therefore evidence of a working acquired-image example, not evidence
that this recipe transfers reliably or meets the broader admission gate.

The transfer diagnostic found zero rejected interpolations or invalid-support
patches. At the discontinuous positive perturbation, invalid-contrast patches
change from 16 to 17, and their objective weight rises from 4.961297e-5 to
8.416092e-5. The local derivative agrees at 1e-5 mm. The hard contrast-energy
cutoff replaces correlation loss with a constant outlier cost, which can jump.
The switching patch's energy has not yet been independently localized. This
remaining method issue is tracked as `bd-01M3YCH2ZBB63SFMBYFHWCQGDV`; any
continuous-objective repair needs new derivative evidence and qualification.
Temporary diagnostic instrumentation was removed from production sources.

## Final local verification

All complete changed-module suites passed: Flashalign 169 JVM / 168 JavaScript,
HalfFlow 166 JVM / 163 JavaScript, benchmark runner 16, and Python evaluator 5.
The complete HalfFlow run includes the existing acquired-image BasinBridge test.
Its criteria differ from the new anatomical court. PRD, build-graph, symbol-owner,
registry and Node validation-tool checks also passed during this work; final logs
and exit metadata are in [verification](evidence/real-mri-20261002/verification/).
These are local checks, not a claim about a hosted CI run or release admission.
