# Flashalign fast linear release assessment

FA-GL passes for candidate
`df4a4ddf84a63e5b7ae714a29b226d15639ce32bf8d583e3f08b931196442089`.
The released scope is automatic rigid and affine pair alignment through the
public `Flashalign.rigid` and `Flashalign.affine` plans, including structural
capture, multiscale refinement, typed QC/failure records, and final resampling
from the original moving image.

Every current blocking prerequisite is closed. The final candidate passed the
JVM and Scala.js numerical suites, the benchmark consumer suite, the
projected-patch allocation check, a fresh sealed automatic accuracy court, and
the declared complete-path latency gates. The source trees rehash to the exact
identities in the seal.

## Release evidence

The v5 court contains six new subjects, four cohorts, and both rigid and affine
models. It was sealed before execution with no reused v4 image hashes. All 48
expected rows returned typed successes. All 36 exact rows passed the frozen
landmark-RMS limits:

| Cohort | Rigid maximum RMS | Affine maximum RMS | Limit |
| --- | ---: | ---: | ---: |
| Exact core | 0.0608 mm | 0.0607 mm | 0.10 mm |
| Capture range | 0.0195 mm | 0.0448 mm | 0.25 mm |
| Partial slab | 0.0522 mm | 0.0883 mm | 0.25 mm |

Complete ordinary same-subject time includes read, decompression, public-plan
preparation, capture, every refinement attempt, validation, and one
materialized output resampling:

| Model | Rows below limit | Median | P95 | Strict limit |
| --- | ---: | ---: | ---: | ---: |
| Rigid | 6/6 | 874 ms | 948 ms | 1000 ms |
| Affine | 6/6 | 889 ms | 1351 ms | 2000 ms |

The complete optimization history, exact spectral oracles, failed intermediate
courts, commands, and validation results are recorded in the
[L32 qualification](flashalign-structural-capture-optimization-l32.md).

## External comparison status

The recorded 3dAllineate court remains a completed negative result for a
superseded pre-correction candidate: Flashalign succeeded on 1/32 cases and
3dAllineate on 32/32. It supplies no passed gate and supports no superiority
claim. The corrected final candidate passed a fresh independent absolute
accuracy court; it was not rerun against 3dAllineate. This release therefore
makes no claim that Flashalign has lower median error, p95 error, or failure
rate than 3dAllineate.

## Product boundary

The linear source imports no HalfFlow or nonlinear execution path. The artifact
graph gives `reframe4s-flashalign` only its lower-level geometry, registration,
resampling, multiscale, spectral, image4s, Ravel, and Gale dependencies. The
public linear plans retain explicit workspaces and do not construct field
bases, Krylov state, velocity stages, or dense warps.

The [visual QA plate](flashalign-linear-visual-qa.md) makes one final sealed v5
rigid result inspectable as fixed, moving, registered, overlay, and residual
slices. Registration and resampling use image4s/Reframe4s; benchmark-local
Java2D only composes the PNG. Flashalign has no ScalaFIM dependency.

This is release readiness for the analytic-synthetic fast linear product. It
does not publish an artifact, qualify acquired MRI, establish anatomical or
clinical validity, qualify nonlinear registration, or support external-method
superiority. The affine result is now admissible as the frozen initializer in
the separately controlled Flashalign-to-HalfFlow comparison.
