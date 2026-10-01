# Flashalign affine handoff to HalfFlow

The C04 transfer experiment supports the Flashalign affine as an initializer
for the frozen HalfFlow recipient on the six-pair analytic-synthetic court. All
six standalone Flashalign affines (C1) and all six Flashalign-initialized
HalfFlow runs (C5) succeeded. The unchanged HalfFlow pipeline (C4) returned
four valid maps and two explicit inverse failures.

The receiver settings were byte-identical between C4 and C5. C5 changes only
initialization, using `SuppliedAffineInitialization.fromMovingToFixed`. The
adapter performs the required inversion once. The exported HalfFlow endpoint
is recorded as a complete moving-to-fixed map; the runner rejects a residual
declaration because that would apply the Flashalign affine twice.

## Paired result

| Pair | C1 Flashalign RMS | C4 existing HalfFlow | C5 Flashalign + HalfFlow | C5 - C4 adjudicated RMS |
| --- | ---: | ---: | ---: | ---: |
| r19 | 0.0412 mm | 0.5118 mm | 0.0880 mm | -0.4238 mm |
| r20 | 0.0572 mm | 0.5689 mm | 0.0827 mm | -0.4863 mm |
| r21 | 0.0607 mm | 0.5586 mm | 0.0811 mm | -0.4776 mm |
| r22 | 0.0316 mm | 0.6131 mm | 0.0668 mm | -0.5463 mm |
| r23 | 0.0411 mm | inverse failure | 0.0783 mm | -99.9217 mm |
| r24 | 0.0349 mm | inverse failure | 0.0792 mm | -99.9208 mm |

The frozen primary estimand assigns 100 mm to a failed recipient and computes
the paired mean of C5 minus C4 landmark RMS. Its observed value is -33.6294 mm.
The exact paired nonparametric bootstrap over all 46,656 six-subject resamples
gives a 95% interval of [-66.7864, -0.4730] mm, which meets the predeclared
`benefit` rule. The large mean reflects the two retained C4 failures. Among the
four pairs where both recipient variants returned valid maps, the descriptive
mean difference is -0.4835 mm and every paired difference favors C5.

The handoff is useful, but the residual stage does not improve the already
accurate affine on this exact-transform court. Mean successful landmark RMS is
0.0444 mm for C1 and 0.0793 mm for C5, a 0.0349 mm increase after HalfFlow.
This distinction matters: the experiment supports Flashalign as a robust
HalfFlow initializer, while the standalone affine remains the more accurate
answer for these affine-only pairs.

The two C4 failures remain in the raw denominator:

- r23: moving-to-fixed export contained two non-positive Jacobians, minimum
  -0.00970.
- r24: fixed-to-moving export contained 29 non-positive Jacobians, minimum
  -0.21645.

## Cost and output accounting

Each court pair loads and decompresses the canonical image pair once. C5 stage
cost contains the full C1 preparation, capture, optimization, and audit cost,
plus the supplied-affine conversion, BasinBridge, HalfFlow fine optimization,
export, and one final sampling of the original moving image. C4 and successful
C5 rows each report one final output sampling; C1 reports zero because it is
the preserved affine checkpoint.

| Lane | Successes | Complete median | Complete p95 | Output samplings |
| --- | ---: | ---: | ---: | ---: |
| C1 Flashalign affine | 6/6 | 1.096 s | 1.834 s | 0 |
| C4 existing HalfFlow | 4/6 | 6.935 s | 7.188 s | 4 |
| C5 Flashalign + HalfFlow | 6/6 | 3.675 s | 5.157 s | 6 |

Complete time is the shared input read and decompression plus the lane's full
stage total. These single-host timings are descriptive and are not a release
latency gate.

## Visual QA

The separately qualified final Flashalign candidate has a reproducible slice
plate for one sealed v5 case:

![Flashalign fixed, moving, registered, overlay, and absolute-difference slices](../../benchmarks/flashalign/visual-qa/r19-exact-core-rigid.png)

Its three normalized slice MAD values fall from 0.0867, 0.0826, and 0.0692
before registration to 0.0282, 0.0284, and 0.0281 afterward. The result uses
the public `Flashalign.rigid` plan and has 0.0324 mm landmark RMS. Registration
and final sampling use image4s/Reframe4s. Benchmark-local Java2D only composes
the PNG, so neither Reframe4s nor Flashalign depends on ScalaFIM.

## Reproduction and evidence

The manifest was SHA-256
`b150d86bd0e707e91faa72d7d9d4caa539c7d07165fd70fd9b647c2fbcdd6989`
and the raw output did not exist when it was sealed. The court was opened once:

```text
sbt -Dsbt.supershell=false 'flashalignBenchmarkJVM/runMain reframe4s.benchmark.flashalign.FlashalignHalfFlowComparisonCourt benchmarks/flashalign/raw/flashalign-halfflow-affine-transfer-v1-2026-09-13.jsonl benchmarks/flashalign/manifests/flashalign-halfflow-affine-transfer-v1.json b150d86bd0e707e91faa72d7d9d4caa539c7d07165fd70fd9b647c2fbcdd6989'
```

It completed in 66 seconds. The raw SHA-256 is
`648c47c0a6fba2b0444d98d43a6d1ea3f8e775739844b4ddb4fe6699625ad670`.
The frozen adjudication can be reproduced with:

```text
python3 benchmarks/flashalign/summarize_halfflow_affine_transfer_v1.py benchmarks/flashalign/raw/flashalign-halfflow-affine-transfer-v1-2026-09-13.jsonl benchmarks/flashalign/manifests/flashalign-halfflow-affine-transfer-v1.json benchmarks/flashalign/receipts/flashalign-halfflow-affine-transfer-v1.json
```

The machine-readable receipt is
`benchmarks/flashalign/receipts/flashalign-halfflow-affine-transfer-v1.json`.
The prior external sub-18 C4 diagnostic remains a separate recorded negative
result: it failed the fine-stage topology floor and does not become a pass
because this synthetic court succeeded.

This experiment establishes an affine handoff on six analytic-synthetic image
pairs. It does not qualify acquired MRI, anatomical alignment, HalfFlow's
stable export, nonlinear Flashalign, clinical use, external-method parity, or
publication. Constrained-warp to HalfFlow composition remains a separate
milestone after an image-backed nonlinear Flashalign objective and admissible
nonlinear court exist.
