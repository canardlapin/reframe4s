# HalfFlow C04 export topology diagnosis

The r23 and r24 failures are reproducible boundary failures in numerical inverse
construction. A continuous boundary extension repairs both under the original
C04 optimizer settings and export thresholds. The original C04 raw rows,
manifest, receipt, 100 mm penalties, and report remain historical evidence;
this is a separate development follow-up.

## Mechanism and independent check

The optimized forward residuals have positive sampled Jacobians. Their
refined inverses do not: r23 has one non-positive moving-arm inverse Jacobian;
r24 has 22 in the fixed-arm inverse and eight in the moving-arm inverse. These
failures occur one or two voxels from an outer grid face. Endpoint composition
then produces the reported two r23 moving-to-fixed folds and 29 r24
fixed-to-moving folds. Inspecting both directions also exposes eight r24
moving-to-fixed folds, which the first-error export result did not report.

The previous inverse solver extended every residual by identity outside its
sampled grid. For nonzero boundary motion, this makes the extended map
discontinuous. A simple independent oracle exposes the defect: the inverse of
`x -> x + 3 mm` must be `x -> x - 3 mm`, including at the grid boundary. With
the previous implementation, the inverse at x=0 was approximately
-0.000000004 mm instead of -3 mm. No image matching, capture, nonlinear
optimization, or endpoint composition is involved in this counterexample.

The repair uses `CoordinateMapOutside.BoundaryLinear`: continue the outermost
trilinear interpolation cell when sampling outside the work grid. This is
continuous at the boundary and exact for affine fields on affine grids. The
inverse pyramid, both inverse correction compositions, inverse error reports,
and endpoint construction all use the same extension. Invalid contributing
samples remain invalid. Optimizer integration and forward-state regridding
retain their existing boundary policy. Export admission thresholds are
unchanged.

This localizes the failure more precisely than a capture-range explanation.
Larger residuals expose a defective boundary condition; these two failures do
not demonstrate that optimization itself folded the authoritative residuals.
Flashalign initialization still improves landmark accuracy on these cases.

## Results and limitations

All six identity-initialized C4 runs now export, as do all six C5 runs. Both
directions retain all 42,875 eligible Jacobian evaluations per map, with zero
non-positive determinants. Residual inverse interior coverage remains 100%.
All 24 optimized forward residual buffers and validity masks (six pairs,
two lanes, two arms) are byte-identical between the original and repaired
implementations. This includes the exporter's internal uses by BasinBridge's
correspondence objective: the change does not alter any optimized state in
this comparison. The checks and per-field hashes are in `comparison.json`.

| Pair | C4 before | C4 after RMS | C4 after minimum Jacobian, F→M / M→F | C5 after RMS |
| --- | --- | ---: | ---: | ---: |
| r19 | 0.5118 mm | 0.5118 mm | 0.39439 / 0.21114 | 0.0880 mm |
| r20 | 0.5689 mm | 0.5689 mm | 0.22177 / 0.35831 | 0.0827 mm |
| r21 | 0.5586 mm | 0.5586 mm | 0.40064 / 0.24960 | 0.0811 mm |
| r22 | 0.6131 mm | 0.6131 mm | 0.19057 / 0.36513 | 0.0668 mm |
| r23 | rejected | 0.5627 mm | 0.38156 / 0.15140 | 0.0783 mm |
| r24 | rejected | 0.7682 mm | 0.05472 / 0.05820 | 0.0792 mm |

The four previously successful C4 landmark scores change by less than
1e-14 mm. All six C5 landmark scores are unchanged. Mean landmark RMS is now
0.5972 mm for C4 and 0.0793 mm for C5; standalone Flashalign remains at
0.0444 mm. Affine preconditioning retains its accuracy benefit even after the
export defect is removed.

The six-case follow-up and numerical validation are recorded in
`benchmarks/flashalign/diagnostics/halfflow-export-topology-v1/`. The baseline
uses the original exporter from Git revision
`fa015c38a1b481096646d2c857191c327fc7a9e5`; the current lane uses production
`ForwardMidpointExporter.inspect` and `admit` directly. The diagnostic runner
verifies the sealed C04 runner and input manifest before execution and assigns
separate run identifiers. Its additional inverse inspections make diagnostic
timings unsuitable for a latency comparison.

The original C04 primary estimand is not recomputed or relabeled as a pass.
This follow-up is an export repair on the same six analytic-synthetic cases,
not a new sealed accuracy court, an acquired-MRI qualification, or a proof of
global continuous invertibility.

The topology check evaluates central-difference Jacobians with the existing
one-voxel margin. Residual inverse tolerances still apply to the configured
six-voxel interior, not every boundary voxel. The diagnostics retain whole-field
inverse maxima and endpoint round trips; passing the existing gate does not
establish small inverse error throughout the outer boundary.
For example, r24's worst residual inverse error over all evaluated voxels is
8.0862 mm after the change (5.0426 mm before), while its largest configured
interior error is 0.0608 mm, below the unchanged 0.2 mm limit. Its endpoint
round-trip maxima are approximately 4.65 and 4.63 mm over valid compositions.
This repair removes the observed sampled folds; whole-boundary inverse
accuracy remains a separate unresolved limitation.
It is tracked as `bd-01M2EG25H20DR7CXNHK22ZF4VE`.

## Reproduction

Use new output directories; the script refuses to overwrite a prior run:

```sh
python3 benchmarks/flashalign/diagnose_halfflow_export.py baseline /tmp/halfflow-export-baseline
python3 benchmarks/flashalign/diagnose_halfflow_export.py current /tmp/halfflow-export-current
```

The script generates isolated temporary benchmark sources and, for the baseline,
a temporary sbt source override for the original exporter. It does not edit the
sealed recipient, images, or checked-out production source. Each output contains
`rows.jsonl`, `execution.log`, and `provenance.json`, including source hashes,
generated-source hashes, both endpoint directions, inverse errors, and hashes
of the optimized residual coordinate buffers and validity masks.

The new shared `ForwardMidpointExportSuite` checks translation inversion,
analytic affine endpoint composition, oblique anisotropic grids, invalid
support, mutable self-composition, and rejection of folds in either endpoint
direction. The existing midpoint, kernels, registration algebra, CC engine,
and supplied-affine suites cover affected consumers. The existing 8 mm capture
case now exports successfully; its expectation is updated while retaining the
independent endpoint-Jacobian assertions. The 4 mm and 12 mm topology failures
in their respective existing fixtures remain explicit failures.

Final validation: **64/64 JVM and 64/64 Scala.js tests passed**, including all
six new regression tests on each platform. The execution record is
`benchmarks/flashalign/diagnostics/halfflow-export-topology-v1/validation.log`.

```sh
sbt -Dsbt.supershell=false 'reframe4s-halfflowJVM/testOnly *ForwardMidpoint* *HalfFlowKernelsSuite *RegistrationAlgebraSuite *HalfFlowCcEngineSuite *SuppliedAffineAdapterSuite' 'reframe4s-halfflowJS/testOnly *ForwardMidpoint* *HalfFlowKernelsSuite *RegistrationAlgebraSuite *HalfFlowCcEngineSuite *SuppliedAffineAdapterSuite'
```
