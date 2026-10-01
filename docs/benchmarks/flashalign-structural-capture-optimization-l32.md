# Flashalign exact structural-capture latency qualification

Candidate `df4a4ddf84a63e5b7ae714a29b226d15639ce32bf8d583e3f08b931196442089`
meets the declared automatic linear accuracy and complete-path latency gates on
a fresh sealed 48-row court. It preserves the capture schedule, objectives,
thresholds, candidate order, typed failures, single-thread execution, and
public API. The fast linear product can proceed to its release assessment.

## Exact computation changes

Structural capture evaluates eight real left fields and returns four real
correlation volumes for every rotation. The spectral provider now packs each
pair of real inputs into one complex forward transform. It recovers the two
Hermitian spectra from `FFT(x + i y)` using conjugate symmetry. Four real
output spectra are accumulated directly into two complex spectra and recovered
by two inverse transforms. Fixed support and tensor fields use the same
real-pair preparation once when the plan is compiled.

The power-of-two transform retains bit reversal and uses specialized width 2,
4, and 8 stages followed by mixed radix-4 stages. Three-dimensional inverse
scaling happens once, and contiguous z lines transform in place. The grouped
workspace stores only the two packed output spectra that four public output
slots require.

The public `Flashalign.rigid` and `Flashalign.affine` paths now prepare their
moving and fixed pyramids once. Automatic structural capture and subsequent
model compilation consume those exact prepared levels instead of rebuilding
the coarse pair. The existing supplied-initializer compilation methods remain
available and delegate through the same implementation.

## Independent numerical checks

The packed grouped-correlation test compares all four returned volumes at every
lag with a direct spatial oracle. The paired fixed-field preparation is also
compared with independently transformed ordinary correlations. The maximum
existing tolerances remain `8e-11` for the grouped direct oracle, `3e-11` for
the structural score oracle, and `2e-11` for paired prepared correlations.

The FFT suite compares forward and inverse results with a direct discrete
Fourier transform at sizes 2, 4, 8, 16, 32, and 64 on both JVM and Scala.js.
No scientific tolerance, optimizer condition, or rotation schedule was changed.
An exposed v4 development replay returned the same status and exact result hash
for all 48 rows after the final pyramid and workspace changes.

## Preserved negative courts

Each failed cost court remains part of the evidence. Once exposed, a court was
used only for engineering comparison; a new fixture generation supplied the
next admission attempt.

| Court | Outcome | Rigid median / p95 | Affine median / p95 |
| --- | --- | ---: | ---: |
| v2 | 48/48 success; cost failed | 1767 / 1799 ms | 2164 / 2290 ms |
| v3 | 47 success, 1 typed `Stalled`; cost failed | 1173 / 1224 ms | 1581 / 1697 ms |
| v4 | 48/48 success; cost failed | 1051 / 1077 ms | 998 / 1562 ms |
| v5 | 48/48 success; accuracy and cost passed | 874 / 948 ms | 889 / 1351 ms |

The v5 limits were the predeclared strict checks `median < 1000 ms` and
`p95 < 1000 ms` for rigid, and `median < 2000 ms` and `p95 < 2000 ms` for
affine. All six ordinary rows for each model were below their limit. Complete
cost includes input read and decompression, public-plan preparation, capture,
all refinement attempts, validation, and one final materialized resampling from
the original moving image.

## Fresh sealed accuracy

The v5 generator created subjects `r19` through `r24` before candidate
execution. Its descriptor, case table, and 54-image tree were hashed, all 36
exact rows passed an independent identifiability oracle, no v4/v5 image hash
overlapped, and the raw v5 path did not exist when manifest
`c3f56906a2b447e0d3ac272ffc203616b8e1bd6d7a887db58362adef3a59b80f`
was sealed.

The one recorded execution produced all 48 expected rows with no failure or
duplicate. The frozen landmark-RMS gates passed:

| Cohort | Rigid maximum RMS | Affine maximum RMS | Limit |
| --- | ---: | ---: | ---: |
| Exact core | 0.0608 mm | 0.0607 mm | 0.10 mm |
| Capture range | 0.0195 mm | 0.0448 mm | 0.25 mm |
| Partial slab | 0.0522 mm | 0.0883 mm | 0.25 mm |

The twelve nonlinear-contrast rows remain descriptive and are excluded from
exact recovery adjudication. They also returned typed successes, but they do
not qualify nonlinear registration.

## Reproduction and gates

The sealed execution used:

```bash
sbt -J-Xmx4G -Dsbt.supershell=false \
  'flashalignBenchmarkJVM/runMain reframe4s.benchmark.flashalign.LinearAutomaticConfirmationCourt benchmarks/flashalign/raw/linear-automatic-release-confirmation-v5-2026-09-13.jsonl df4a4ddf84a63e5b7ae714a29b226d15639ce32bf8d583e3f08b931196442089 benchmarks/flashalign/manifests/linear-automatic-release-confirmation-v5.json 19bd0beab38f7f4cc75663cf496777d6383004f580b19d7d87739add6b7ad915 benchmarks/flashalign/fixtures/linear-automatic-release-confirmation-v5.cases.tsv 01b19237dc07a90bcce37b13e47c88e6eeb856d15f523ce2cfcd0d2d575f4fd6 linear-automatic-release-confirmation-v5'
```

Cross-platform validation used:

```bash
sbt -Dsbt.supershell=false \
  'reframe4s-spectralJVM/test' \
  'reframe4s-spectralJS/test' \
  'reframe4s-flashalignJVM/test' \
  'reframe4s-flashalignJS/test' \
  'flashalignBenchmarkJVM/test'
```

Results were spectral JVM/Scala.js `8/8` each, Flashalign JVM `161/161`,
Flashalign Scala.js `160/160`, and benchmark JVM `13/13`. The projected-patch
allocation gate remains 24 bytes at both 2,000 and 20,000 iterations.

The [Flashalign visual QA](flashalign-linear-visual-qa.md) reruns the final v5
candidate on `r19-exact-core-rigid`, resamples the original moving image, and
shows fixed, moving, registered, overlay, and residual slices. It uses
image4s/Reframe4s plus benchmark-local Java2D and adds no ScalaFIM dependency.

This result qualifies the analytic-synthetic automatic linear product and one
single-host descriptive latency court. It does not establish acquired-MRI,
anatomical, clinical, nonlinear, publication, or cross-method superiority
claims.
