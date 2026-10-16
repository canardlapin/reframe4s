# Functional HalfFlow export on acquired MRI, v1

This study applies the checked functional export API to the two existing
saved acquired-MRI bundles: OASIS13→14 terminal selected-control event44 and
MMRR11→12 event30. It introduces no registration fits, checkpoint selection,
solver changes or anatomical tuning. Numerical export admission and anatomical
quality are measured separately.

The [declaration](evidence/halfflow-functional-mri-20261016/declaration-v1/artifact-manifest.json)
was archived before launch. It freezes source `5d109f04`, compiled classes,
input/bundle identities, the independent Python implementation, complete
sampling denominators, image interpolation policy and original common labelsets.
Full source SHA256 is
`5d109f046c695343e721651c97a840f6a820e5b98dbf67b378fc95bbd0837a08`.

## Contract and independent checks

Native admission reloads each bundle through `ForwardQueryBundle`, then
`ForwardQueryExporter.inspect` snapshots it into private state. Both complete
half-voxel endpoint lattices are scanned, without masks or skipped boundaries:
**65,652,768** declared queries across the two pairs. Each direction remains
inside the unchanged default 20-million-sample budget. Every query must have
a converged root and reverse root under the existing eight-update Newton
policy, 4 mm step cap, twelve backtracks and 1e-8 mm equation tolerance. Both
analytic full-chain endpoint branch determinants must be finite and positive,
and the full cycle must be at most 0.2 mm. One failure rejects the entire pair.

Python independently reconstructs the maps from the original captured forward
fields, identity seeds and saved endpoint affine factors. It uses independent
NumPy affine inverses and the existing trilinear/Newton oracle. Both MRI
checkpoints have nonidentity saved affine factors in both arms. It compares
every success status, coordinate and endpoint determinant on the full declared
half-voxel lattices, including all boundaries. Frozen coordinate and determinant
agreement limits are both 1e-7. The independent bundle parser verifies original
fields, seeds, canonical owner aliases, exact saved forward affine factors and
checksum before the audit.

Only admitted maps proceed to application. The native runner directly calls
the checked API at every original native endpoint voxel, rechecking geometry
and cycle per call. It validates exact input image shape/affine before binding
the loaded image data to the fresh canonical endpoint owner restored from the
bundle. It uses those direct coordinates to pull the original prepared MRI
intensities with the existing scalar kernel. The diagnostic coordinate arrays
do not authorize an interpolated dense transform.

Python checks every native application against the corresponding independently
audited half-voxel location. It independently resamples all intensity values
with SciPy linear interpolation, including outside values. Coordinates within
1e-10 voxel of a grid node are snapped before the inclusive image support test;
outside intensities are zero. Frozen intensity agreement is 1e-9 absolute.
Transform success and image sampling coverage retain separate denominators.

Labels never enter the native runner or admission. Evaluation pulls original
manual labels with nearest-neighbor interpolation, retains every target voxel,
zeros invalid/outside samples and uses the common nonzero original labelset
frozen for each pair. The existing court computes per-label Dice and symmetric
physical HD95, including missing-label failures. The same labelsets and endpoint
lattices are used to independently score the same checkpoint's historical dense
export and its saved affine baseline in both directions. No score chooses the
checkpoint, map representation, inverse policy or admission verdict.

The native IO and known-shift phantom tests pass (2/2), including exact owner
rebinding after lattice validation, wrong shape/affine/nonfinite image rejection,
and full transform success with partial image coverage. Independent scalar
boundary and affine-chain self-checks pass. Ownership verification reports
1,522 public names and 66 canonical owners. A source comparison against the preceding functional C4 study confirms that
all production Scala sources are unchanged from its 27 passing focused tests
on each of JVM and Scala.js; only this application runner and its tests were
added. An initial unused-import compilation
failure is retained; it was fixed before freezing the study.

## Native admission and application

Both saved MRI bundles pass native functional admission. Every declared
**65,652,768 / 65,652,768** half-voxel query succeeds, including all
**1,540,920 / 1,540,920** boundary points. Maximum native cycle error is
**2.463951e-8 mm**, below the unchanged 0.2 mm limit; minimum sampled full-chain
endpoint branch determinant is **0.276432**.

| Saved checkpoint | Half-voxel queries passing, both directions | Boundary points passing | Worst cycle (mm) | Minimum endpoint branch determinant |
| --- | ---: | ---: | ---: | ---: |
| OASIS13→14, event44 | 33,495,850 / 33,495,850 | 782,740 / 782,740 | 2.463951e-8 | 0.276432 |
| MMRR11→12, event30 | 32,156,918 / 32,156,918 | 758,180 / 758,180 | 2.345793e-8 | 0.337523 |

All **8,304,048 / 8,304,048** direct checked native endpoint-node applications
succeed. Image sampling support is **7,346,913 / 8,304,048**; the remaining
957,135 mapped points lie outside the source image lattice and receive the
declared zero intensity. They remain successful transform evaluations and are
explicitly excluded from image support, with all original target voxels retained
for anatomical scoring.

## Independent outcomes and anatomical limits

The complete worker exits successfully; both pair receipts and all four
independent geometry/application validations pass. Python agrees on every
status and admission decision: **zero status mismatches**. Maximum coordinate
difference is **1.286715e-8 mm** and endpoint determinant difference is
**3.989920e-10**, within their frozen 1e-7 limits. Native-node application
records exactly match the corresponding audited half-voxel records. Independent
SciPy resampling matches every image-support status, with maximum scalar
intensity difference **5.551115e-16** across all 8,304,048 voxels, including
outside values.

The original trajectory input identities also match for all twelve MRI image,
label, affine and baseline-report inputs. Source, frozen executable and input
verification passes before and after execution. All 62 frozen anatomical labels
remain present for every map/direction; no missing label or target voxel is
removed. The following scores use the original labels and freshly evaluated
matched baselines:

| Pair / pull direction | Functional macro Dice | Same-checkpoint dense Dice | Saved affine Dice | Functional median HD95 (mm) | Dense median HD95 (mm) | Affine median HD95 (mm) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| OASIS13→14 / fixed-to-moving | 0.417979 | 0.418074 | 0.357014 | 4.7434 | 4.7434 | 5.6125 |
| OASIS13→14 / moving-to-fixed | 0.423526 | 0.423500 | 0.355137 | 4.5000 | 4.5000 | 5.4083 |
| MMRR11→12 / fixed-to-moving | 0.374238 | 0.374197 | 0.334475 | 5.6125 | 5.6125 | 6.0000 |
| MMRR11→12 / moving-to-fixed | 0.382302 | 0.382391 | 0.337084 | 6.0923 | 5.9207 | 6.4468 |

Functional Dice differs from the same-checkpoint dense result by less than
0.00010 in every direction. Three median HD95 values match; **MMRR reverse
median HD95 worsens by 0.1717 mm**. Thus the functional representation does not
improve all anatomical measures. Every direction still has higher Dice and
lower median HD95 than its saved affine baseline, but those gains belong to
the existing checkpoints, not a new optimization in this study. This tranche
establishes the checked numerical export and image-application path on these
two acquired-MRI pairs; it does not establish better anatomical registration.

The [complete outcome archive](evidence/halfflow-functional-mri-20261016/outcomes-v1/artifact-manifest.json)
retains all per-pair receipts, full empty failure ledgers, directional geometry
and image reports, per-label scores for every comparison, exact commands/runtime
versions, source snapshots and executable/input hashes. Multi-gigabyte coordinate,
status, intensity and original bundle payloads remain local with exact hashes;
raw MRI and label volumes are not added to the repository. The retained
`summarize_functional_mri.py` derives the report totals from the terminal
receipts.

R02's bounded inverse-contract work is complete for the checked functional
representation: the earlier six corrected C4 states and their unsafe controls
plus these two actual MRI checkpoints have their declared numerical outcomes.
Closing that research task does not admit a dense approximation, pass G3 or
change the stable capability ledger. The next work returns to the D02/D03 MRI
failure diagnosis and a predeclared, bounded regularized optimizer experiment;
convergence and anatomical competitiveness remain unresolved.

## Limits

These are two previously studied development pairs. Sampled functional
admission is not a continuous or global invertibility certificate. Neither
image parity nor numerical cycles establish anatomical accuracy or optimizer
convergence. The dense exporter, optimizer and defaults are unchanged. The
six corrected C4 dense exports remain rejected under their existing contract.
A dense approximation needs separately declared interpolation error and topology
checks. Held-out anatomical and reference-method qualification remain separate
epic gates. This is an untimed audit with sequential native tasks, one active
Java processor, a 6 GiB heap and a 2,400-second native task deadline; the complete
worker deadline is 14,400 seconds.

## Reproduction

Compile `flashalignBenchmarkJVM`, run `RealImageFunctionalExportSuite` and export
the runtime classpath. Run the independent checks with
`benchmarks/halfflow/boundary/functional_mri.py check`. Run the pinned ownership
check, then `functional_mri.py freeze` with a fresh output directory, classpath
and check-log directory. Archive the declaration before `run`; preserve every
attempt and archive the terminal outcomes. Original captures, labels, images
and saved bundles must retain their declared hashes. Do not refit to reproduce
this export/application audit.
