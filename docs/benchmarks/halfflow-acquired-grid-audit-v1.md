# HalfFlow acquired-image grid representation audit

Status: acquired-image audit and derivative-value follow-up complete. The
original exact-count criterion fails on floating-point-sensitive zero status. This is
read-only D02 diagnostic work on observed data, with no new optimization policy
or full registration fit.

The completed [role-swap audit](halfflow-coordinate-audit-v1.md) checks image/arm
relabeling on a fixed saved work grid. This follow-up changes the voxel-axis
representation of each prepared source image independently. It tests whether
coordinate handling depends on source-axis order or handedness while preserving
the physical image and mask samples exactly.

## Declared experiment

Use the same saved states: OASIS 11 → 12 coarse end, OASIS 13 → 14 fine end, and
MMRR 11 → 12 fine start. Reproduce each original complete objective audit and all
twelve original signed candidate losses exactly from the preceding role audit.
No case may be replaced after observing an outcome.

Each state receives three representation variants:

| Variant | Fixed prepared image | Moving prepared image |
| --- | --- | --- |
| Fixed only | Axis cycle `(z,x,y)` with new first axis reversed | Original |
| Moving only | Axis order `(y,x,z)` with new second axis reversed | Original |
| Both | Fixed transformation above | Moving transformation above |

The fixed transformation reverses handedness; the moving transformation has
positive determinant. Every voxel value and validity bit moves with its index.
If `P` maps new voxel coordinates to old coordinates, the new voxel-to-physical
affine is `A P`. This is exact sample reindexing, with no image resampling.
The original work grid, physical residual maps and arm affines stay fixed.
Reindexing happens after pyramid preparation, so it isolates representation
handling from effects of smoothing or changed pyramid sampling.

For each variant, compare frozen current loss, production gradient, spatial-chain
gradient, validity and derivative-support summaries. Apply the original tapered
translation, smooth-wave and negative-production-gradient directions at both
signs of 0.01 and 0.0001 mm through paired integration and actual composition.
That gives **108 signed candidate comparisons**: three states × three variants
× twelve candidates.

Retain the previous audit's tolerances: loss absolute difference at most
`1e-12`; gradient maximum absolute difference at most `1e-11` and relative L2
difference at most `1e-10`. Both gradients must be finite and nonzero. Complete
spatial support, identical support summaries and identical production validity
are required. Frozen current objectives must remain exactly unchanged after
candidate evaluation. Any failed identity, missing measurement or process
failure fails this three-state replication; all outcomes remain in the ledger.

One worker uses one active Java processor, a 6 GiB heap and a one-hour deadline
per state. Diagnostic walltime under the current machine load cannot support
performance comparisons. The isolated P02 study and the subsequent 61-state
export batch remain unstarted.

## Validation and reproduction

Two new benchmark tests check physical sample identity, mask ownership,
reversibility, full validity and rejection of invalid voxel maps on a rectangular
oblique grid. Together with checkpoint-loader and experimental-option tests,
all six benchmark checks pass. Two Python ledger tests reject omitted or
duplicated candidates and fabricated positive verdicts, while retaining a
correctly reported negative outcome. The initial sbt sandbox-lock failure and
the successful rerun are both retained.

The [pre-execution declaration](evidence/halfflow-acquired-grid-20261014/declaration-v1/artifact-manifest.json)
contains protocol, inputs, compiled/source identities, checks and a reconstructible
two-file source patch. Frozen source is
`ed31a4e84869d72df88453e26972ea51bce83dcf9b7b2c9ea8763de8c5160944`,
based on spatial-direction source
`4c3bed8ae13b67b453f570ff8a9923ab924658637d04161e7e9d9f044bb0a9b1`.
Reconstruct the
[spatial-direction source](halfflow-optimizer-experiment-v1.md), then apply this
declaration's patch and run the three named benchmark suites. Use
`grid_study.py freeze` with the completed role study, reconstructed source base
and newly compiled classpath. Archive that fresh declaration before calling
`grid_study.py run`; archive every outcome with `archive_grid_study.py`.

Passing would reject a source-axis representation defect on these saved states.
It would not establish reverse-fit symmetry, invariance to changing the chosen
reference lattice or image resampling, symmetric fitting-mask selection,
full-boundary map admission, convergence, or anatomical improvement. Those
distinct questions and the remaining D01/D02/P02 gates stay open.

## Completed grid outcomes

All three attempts completed and all original full audits and 36 original signed
candidate losses replayed exactly. Every one of the **108 new candidate-loss
comparisons** passes the unchanged `1e-12` tolerance. Both gradients pass their
absolute and relative criteria in all nine variants. Production validity stays
identical, every nonzero derivative has available source-chain support, and
all frozen current objectives remain exactly unchanged.

| Saved state | Maximum candidate loss difference | Maximum production relative L2 difference | Maximum spatial-chain relative L2 difference | Variants passing all criteria |
| --- | ---: | ---: | ---: | ---: |
| OASIS 11 coarse end | 4.441e-15 | 3.103e-14 | 2.964e-14 | 3 / 3 |
| OASIS 13 fine end | 2.487e-14 | 4.072e-13 | 4.005e-13 | 1 / 3 |
| MMRR 11 fine start | 1.954e-14 | 1.814e-13 | 1.641e-13 | 1 / 3 |

The complete replication **fails its declared exact support-summary condition**.
In OASIS 13, fixed-only and both-image reindexing change the fixed derivative
count from 115,444 to 115,443. In MMRR, the same two variants change that count
from 107,967 to 107,969. All four variants retain complete derivative coverage
and identical production validity. Counts in the other five variants agree.

The [complete outcome archive](evidence/halfflow-acquired-grid-20261014/outcomes-v1/artifact-manifest.json)
retains every variant, candidate, failure, log and receipt, with independently
recomputed verdicts and verified original baselines. The pre-execution declaration
remains unchanged. These observations constrain a numerical representation defect
in loss or physical gradient on these states, but do not justify relabeling the
strict replication as a pass.

## Declared exact-zero follow-up

The first completed OASIS 13 fixed-reindex variant fails the exact support-summary
identity: the fixed arm's nonzero intensity-derivative count changes from
115,444 to 115,443. Losses and both physical gradients pass their original
tolerances, all derivatives that require evaluation are available, and production
validity remains identical. The corresponding moving count remains 115,444.
This is a failed condition under the original protocol, retained without changing
its criterion.

A separate read-only follow-up exposes detached CC intensity-derivative
copies on all three original states and all three representation variants.
It records every zero/nonzero mismatch, index and both derivative values, and
checks that those changes exactly balance the reported counts. All previous
original/variant scalar audits, gradient comparisons and support summaries must
replay exactly. The original grid verdict remains unchanged.

The declared hypothesis was that exact-zero classification is sensitive to
floating-point evaluation order. Magnitude comparisons
use the existing `1e-11` absolute and `1e-10` relative limits as diagnostic
measurements; they do not replace the original exact-count criterion or change
any fitting derivative. This follow-up performs no candidate perturbations or
full fits. A mutation test verifies that editing the detached arrays cannot
change the frozen loss or sampled-intensity linearization.

The separately [archived follow-up declaration](evidence/halfflow-acquired-grid-20261014/zero-declaration-v1/artifact-manifest.json)
freezes source `5b792b869c62b47597d7de880eba4fe7c9387239a927c0160c45a5d34b7bb924`
and a four-file patch against the original grid-audit engine. Nine JVM, nine
Scala.js, six runner and two follow-up ledger checks pass. The source change
exposes cloned diagnostic arrays and adds an intensity-only runner mode; normal
optimization is unchanged. Reproduce with `grid_zero_followup.py freeze`, archive
the declaration, then `run` and archive all outcomes. The same three states run
sequentially under the original one-hour per-state deadline.

## Completed derivative-value follow-up

All three attempts completed. Every original and reindexed scalar measurement
from the first study reproduces exactly, including the failed support-count
identities. All eighteen arm/variant derivative comparisons pass the declared
diagnostic magnitude criteria.

| Saved state | Zero-status changes across variants/arms | Maximum difference at those sites | Maximum full derivative difference | Maximum relative L2 difference |
| --- | ---: | ---: | ---: | ---: |
| OASIS 11 coarse end | 0 | 0 | 6.904e-16 | 5.240e-14 |
| OASIS 13 fine end | 3 | 3.956e-25 | 6.439e-15 | 6.453e-13 |
| MMRR 11 fine start | 4 | 5.159e-24 | 2.619e-15 | 2.921e-13 |

The seven changes occur at three work-grid sites across the repeated variants.
OASIS 13's fixed derivative at index 840748 changes from `-3.956e-25` to zero
in both fixed-reindex variants; its moving derivative also changes from
`5.447e-26` to zero when both images are reindexed. MMRR's two affected fixed
derivatives change from zero to values between `1.315e-24` and `5.159e-24`.
The [complete follow-up archive](evidence/halfflow-acquired-grid-20261014/zero-outcomes-v1/artifact-manifest.json)
retains every index and both values, count-balance checks, exact baseline replay,
all logs and independently recomputed summaries.

This explains the failed count condition as exact-zero sensitivity at
roundoff-sized derivatives, with no observed loss of validity or derivative
availability. It does not identify the particular floating-point operation
responsible. The original replication stays failed under its original rule;
no derivative is thresholded or changed, and no geometry criterion is relaxed.
Future diagnostic protocols should distinguish validity/availability from
exact nonzero counts and report these count differences with their magnitudes.

Source-axis representation therefore has no material observed effect on the
frozen loss or physical gradients in this panel within the declared tolerances.
This does not explain the earlier anatomical decline. Actual reference-grid
selection, reverse fitting/mask asymmetry, complete boundary behavior and the
remaining trajectory/reference work still gate the candidate MRI experiment.
The post-study isolation check at 21:59 UTC found seven active external R/Java
processes, so no reference timing attempt was launched.
