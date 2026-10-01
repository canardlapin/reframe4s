# Flashalign structural-capture optimization and release result

Candidate `50ec8257d6f531aec793e11f568627b6b95813b80be8cc2bd8ad748c47ab60d8`
reduces the automatic rigid-capture tail without changing the search schedule,
thresholds, candidate ordering, objective, or returned transforms. Accuracy is
qualified on a fresh sealed court. The fast linear product remains withheld
because complete ordinary-path latency still misses its declared targets.

## Change

Each rotation previously performed nine independent linear correlations. Every
correlation recomputed a forward FFT of its fixed field and performed its own
inverse FFT, for 27 three-dimensional transforms per rotation.

The spectral provider now supports immutable prepared right-hand fields, a
weighted sum of correlations with one shared inverse transform, and several
right-hand correlations sharing one left transform. Structural capture uses
those operations to:

- prepare the six fixed tensor channels, fixed support, and fixed
  support-weighted tensor norm once per compiled plan;
- combine the six tensor-channel numerator correlations before one inverse
  FFT; and
- share the rotated-support forward FFT between right-energy and overlap.

The repeated work is reduced to 12 three-dimensional transforms per rotation,
plus eight fixed-field forward transforms once during plan preparation. The
prepared bank is immutable and plan-owned. Workspace ownership, single-thread
execution, typed failure behavior, and deterministic output order are
unchanged.

## Development evidence

The development-only probe uses the public automatic rigid path on the existing
analytic `6, -6, 0 mm` translation fixture. Both candidates evaluated 125
rotations, retained four candidates, and returned the identical matrix. Across
five warmed repetitions:

| Measurement | Baseline | Candidate | Change |
| --- | ---: | ---: | ---: |
| Median capture | 268.960 ms | 119.934 ms | -55.4% |
| P95 capture | 275.282 ms | 122.548 ms | -55.5% |
| Median run after compilation | 301.918 ms | 150.551 ms | -50.1% |

The direct spatial correlation oracle continues to match the FFT score volume.
The prepared-field tests independently compare ordinary, prepared, weighted-sum,
and shared-left correlations on JVM and Scala.js.

An after-the-fact run on the already exposed prior court supplies a paired
engineering comparison only. All 48 old and new transform hashes, matrices,
statuses, and landmark errors are exactly equal. Among its 42 expanded-capture
rows, median capture changed from `3019.445 ms` to `1330.458 ms` (`-55.9%`).
This exposed comparison was not used for candidate tuning or release admission.

## Fresh sealed accuracy and cost

Release court `linear-automatic-release-confirmation-v2` was generated and
sealed before candidate execution. It contains 48 new `r01` through `r06`
analytic-synthetic rows: six subjects, four cohorts, and rigid/affine models.
All 36 exact rows passed the independent pre-execution identifiability oracle.
The remaining 12 nonlinear-contrast rows are descriptive.

The single recorded run returned success for all 48 rows. Every exact gate
passed:

| Cohort | Rigid maximum RMS | Affine maximum RMS | Limit |
| --- | ---: | ---: | ---: |
| Exact core | 0.0491 mm | 0.0730 mm | 0.10 mm |
| Capture range | 0.0382 mm | 0.0402 mm | 0.25 mm |
| Partial slab | 0.0483 mm | 0.0487 mm | 0.25 mm |

Every successful row also materialized one final linearly interpolated output
from the original moving image and recorded positive output time. Complete
ordinary same-subject latency remained above the release targets:

| Model | Rows below limit | Median | P95 | Limit |
| --- | ---: | ---: | ---: | ---: |
| Rigid | 1/6 | 1767 ms | 1799 ms | 1000 ms |
| Affine | 2/6 | 2164 ms | 2290 ms | 2000 ms |

Expanded structural capture remains the dominant stage at roughly 1.26 to
1.35 seconds on these rows. This optimization therefore qualifies accuracy and
materially reduces cost, but it does not qualify the fast linear release or
unblock the Flashalign-to-HalfFlow comparison.

## Reproduction and gates

Development timing:

```bash
sbt -J-Xmx4G -Dsbt.supershell=false \
  'flashalignBenchmarkJVM/runMain reframe4s.flashalign.StructuralCaptureDevelopmentProbe benchmarks/flashalign/raw/structural-capture-development-candidate-2026-09-13.json 50ec8257d6f531aec793e11f568627b6b95813b80be8cc2bd8ad748c47ab60d8 5'
```

Cross-platform validation:

```bash
sbt -J-Xmx4G -Dsbt.supershell=false \
  'reframe4s-spectralJVM/test' \
  'reframe4s-spectralJS/test' \
  'reframe4s-flashalignJVM/test' \
  'reframe4s-flashalignJS/test' \
  'flashalignBenchmarkJVM/test'
```

The results were spectral JVM/Scala.js `7/7` each, Flashalign JVM `161/161`,
Flashalign Scala.js `160/160`, and benchmark JVM `13/13`. The projected-patch
allocation observation remains 24 bytes at both 2,000 and 20,000 iterations.

The human-facing rigid result is separately inspectable in the
[Flashalign visual QA](flashalign-linear-visual-qa.md). That renderer uses
image4s/Reframe4s plus benchmark-local Java2D and adds no ScalaFIM dependency.

The next performance candidate should target the remaining left-channel FFTs,
for example by independently validating real-pair packing, and must face a new
unopened court. The current release result must remain visible as negative cost
evidence.
