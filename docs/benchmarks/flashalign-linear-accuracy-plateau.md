# Flashalign ordinary linear accuracy plateau

Date: 2026-09-13

Mote task: `bd-01M2DPWRPWEZD1K3XKFD50RED7` (FA-L29)

## Decision

The opened ordinary-rigid failures do not justify another optimizer tolerance
change. Exact-truth initialization still drifts, but an independent image
surface also shows that the stored fixture truth is not stationary for a
closely related NCC/trilinear estimator at the declared 2 mm sampling. The old
`<= 0.1 mm` release row therefore lacks the PRD precondition that truth be
identifiable at the declared resolution.

This is a negative qualification result. It neither releases the linear
candidate nor proves the Flashalign patch objective correct. The release path
now requires a fresh unopened court that establishes identifiability before the
candidate is run. That work is tracked by FA-L30
`bd-01M2DR963G3PH3X3TXPTF0EJ9P`; the fresh accuracy and whole-pipeline cost task
FA-L28 depends on it. C04 remains blocked by GL.

No accuracy threshold, trust-region tolerance, sampling count, or production
solver source changed in this task.

## Reproducible starting point

Before adding the truth-start diagnostic, the complete L26 candidate and its
required provider, runner, build-graph, receipt, raw-evidence, and analysis
files were written to the deterministic archive
`benchmarks/flashalign/snapshots/linear-automatic-completion-v1.tar.gz`.
Its manifest is
`benchmarks/flashalign/snapshots/linear-automatic-completion-v1.manifest.json`.

The archive SHA-256 is
`45a3ea86dd3e834c23ccff27b40c70b4001f1a085a34a4b1e7f6696bda9cfdea`.
It contains 108 regular files with normalized tar metadata. The manifest binds:

- repository HEAD `fa015c38a1b481096646d2c857191c327fc7a9e5`;
- Flashalign module SHA-256
  `7d5c0959cbe4edd4ee9f8a8a84f3d19660c4a1d360403b2f9d4df1c2b21b9490`
  over 72 files;
- resample provider SHA-256
  `252b9f431839d933d4442c14f8b7f58bfe784d88812199cd45574eb7d4a9db92`
  over 10 files;
- multiscale provider SHA-256
  `e61ec09607857757e428e863caca0371f3bad8e38917aee5ea4e074404509bb5`
  over 6 files;
- evidence-runner SHA-256
  `0e1c3c5ec143ee977fa519a688125a74a60472352a7f59bcaa45c509badc9ce5`
  over 13 files; and
- the L26 `LinearAccuracyCourt.scala` SHA-256
  `8194083c6e4215d6d62a602f14f7ce7f3ce40ce10bddf1d19df76150f3cf036e`.

Both the Flashalign module and runner hashes matched the closed L26 receipt at
snapshot time. The checkout remains an uncommitted multi-owner worktree; the
archive is the reviewable starting-point candidate rather than a claim that
repository HEAD contains the work.

## Truth-start experiment

The same four opened ordinary rigid cases were initialized with their
independently generated truth matrices. The normal arm retained the complete
frozen within-modality policy. The native-only arm changed only preparation
resolutions and stencil spacings from `[6.0, 3.0, 1.5]` to `[1.5]`. The final
level is the native image grid in both arms; all patch, sampling, trust, QC, and
intensity policies remained fixed.

| Case | Frozen status | Frozen selected RMS (mm) | Frozen last-valid RMS (mm) | Native-only status | Native-only selected RMS (mm) | Native-only last-valid RMS (mm) |
|---|---:|---:|---:|---:|---:|---:|
| s01 | stalled | 0.804060 | 1.359619 | success | 0.203856 | 0.203856 |
| s02 | stalled | 0.850144 | 1.038955 | stalled | 0.140534 | 0.742709 |
| s03 | stalled | 0.567927 | 0.650333 | success | 0.000000 | 0.000000 |
| s04 | success | 0.339375 | 0.339375 | stalled | 0.294400 | 0.594633 |

Removing the coarse levels improves every selected transform, so coarse-to-fine
propagation explains a substantial part of the plateau. It does not explain all
of it: three native-only selected checkpoints remain above 0.1 mm, and two of
those runs later exhaust their trial limit. The best checkpoint remains
distinct from the last valid state on failure.

Commands:

```text
sbt -J-Xmx4G -Dsbt.supershell=false 'flashalignBenchmarkJVM/runMain reframe4s.benchmark.flashalign.LinearAccuracyCourt benchmarks/flashalign/raw/linear-ordinary-truth-start-development-2026-09-13.json 7d5c0959cbe4edd4ee9f8a8a84f3d19660c4a1d360403b2f9d4df1c2b21b9490 --truth-start-ordinary-rigid-development'
sbt -J-Xmx4G -Dsbt.supershell=false 'flashalignBenchmarkJVM/runMain reframe4s.benchmark.flashalign.LinearAccuracyCourt benchmarks/flashalign/raw/linear-ordinary-truth-start-native-only-development-2026-09-13.json 7d5c0959cbe4edd4ee9f8a8a84f3d19660c4a1d360403b2f9d4df1c2b21b9490 --truth-start-ordinary-rigid-native-only-development'
```

The raw SHA-256 values are respectively
`8770aa4525e591ee767f96be868779185be43d76fc42fefe9c652be19250e31d`
and
`dd180368db90d83b692b3994476cd1bb7e596fba239204f1b30e7d40d8efbe22`.

## Numerical explanation

The generator samples a continuous phantom directly onto two 2 mm grids. At a
moving world point `x`, the moving image stores a signed square-root contrast
of `P(Tx)`. Flashalign samples the stored fixed image at `Tx` using the exact
derivative of its trilinear interpolant. These are different operations:

```text
moving(x)       = h(P(Tx))
fixed sampled   = L[g(P sampled on the fixed grid)](Tx)
```

For sub-voxel `Tx`, trilinear interpolation `L`, the nonlinear contrast maps
`g` and `h`, and sampling the continuous phantom do not commute. Geometric
truth can therefore be correct while an NCC objective prefers a nearby pose.
The hard zero-background boundary adds another interpolation approximation.

`benchmarks/flashalign/analyze_linear_truth_bias.py` tests this independently
of Flashalign. It verifies the sealed descriptor and case-table hashes, reads
the NIfTI-1 float payloads directly, uses SciPy trilinear interpolation, fixes a
common full-support voxel set, and evaluates global Pearson correlation around
truth. A deterministic translation-only search and central differences give:

| Case | Stored sqrt contrast + trilinear fixed optimum norm (mm) | Continuous fixed optimum norm (mm) | Affine-contrast counterfactual + trilinear fixed optimum norm (mm) |
|---|---:|---:|---:|
| s01 | 0.209127 | 0.002818 | 0.035721 |
| s02 | 0.215882 | 0.000294 | 0.028258 |
| s03 | 0.163366 | 0.003850 | 0.036216 |
| s04 | 0.115993 | 0.003810 | 0.027876 |

All four stored-image proxy surfaces improve outside the 0.1 mm gate. Their
truth-point correlation derivatives are nonzero in the same calculation. When
the fixed phantom is evaluated continuously, the local optimum is within
0.004 mm of truth. When the moving image uses an affine within-modality contrast
while retaining stored fixed data and trilinear interpolation, the optimum is
within 0.037 mm. This isolates the interaction between discretization and the
nonlinear contrast contract rather than floating-point cancellation.

The diagnostic command is:

```text
python3 benchmarks/flashalign/analyze_linear_truth_bias.py --output benchmarks/flashalign/raw/linear-truth-bias-analysis-2026-09-13.json --frozen-raw benchmarks/flashalign/raw/linear-ordinary-truth-start-development-2026-09-13.json --native-raw benchmarks/flashalign/raw/linear-ordinary-truth-start-native-only-development-2026-09-13.json
```

The script SHA-256 is
`5a5462b6cc1fed1d0bca72c0a4a65e69a38c0d587c442ecbc00891964a41d703`.
The JSON result SHA-256 is
`acac1b50ba463c254da9cb45904b14ceb63a3da36b7fa92825d1529c2b369e70`.
It records Python 3.14.7, NumPy 2.4.3, and SciPy 1.17.1.

This global NCC proxy is intentionally not Flashalign's sampled patch-mixture
objective. The s03 native run remains exactly at truth even though its global
proxy moves, which demonstrates that the two objectives are not interchangeable.
The proxy is sufficient to reject the old court's unqualified premise that
geometric truth must be the optimum of an NCC/trilinear estimator to 0.1 mm. It
is not sufficient to declare the Flashalign optimizer accurate.

## Required correction to qualification

The PRD already limits the 0.1 mm gate to cases where truth is identifiable at
the declared sampling resolution and explicitly separates interpolation
approximation. The next court must enforce that condition before execution:

1. Use an affine same-modality intensity relationship for exact-recovery rows.
2. Put nonlinear contrast changes in a separate approximation distribution
   with predeclared, non-exact estimands.
3. Run candidate-independent stationarity and recovery-oracle checks over the
   stored images and declared interpolant before sealing.
4. Freeze new transforms, subjects, byte hashes, thresholds, failure penalties,
   runner, and cost accounting before the candidate sees the images.
5. Execute the real automatic public path and preserve all failure rows.

The opened 32-case cohort and these truth-start rows remain development
material. They cannot be relabeled as fresh confirmation.

## Validation

```text
sbt -J-Xmx4G -Dsbt.supershell=false 'flashalignBenchmarkJVM/testOnly reframe4s.benchmark.flashalign.LinearAccuracyCourtSuite'
python3 benchmarks/flashalign/analyze_linear_truth_bias.py --self-test
python3 -m py_compile benchmarks/flashalign/analyze_linear_truth_bias.py
```

The Scala runner suite passed 3/3. The Python seal/NIfTI/NCC self-test passed,
and the script compiled. Existing JVM and Scala.js objective finite-difference,
explicit-Jacobian, and streamed-JtJ regression evidence remains bound by the
L24 receipt; this task did not change those sources.
