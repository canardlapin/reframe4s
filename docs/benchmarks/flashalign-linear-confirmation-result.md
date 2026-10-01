# Flashalign automatic linear confirmation result

The sealed automatic linear court passed its accuracy gates for candidate
`d3e96fdfcc4525a473ab42d3b371c15b7fa907ac2859809996237b245dea3c9f`.
All 48 image-backed rows returned a typed success. The 36 interpolation-exact
rows met every frozen rigid and affine threshold, and the 12 nonlinear-contrast
rows remained descriptive as declared before execution.

## Accuracy

| Cohort | Model | Successes | Maximum landmark RMS | Limit | Result |
|---|---:|---:|---:|---:|---:|
| exact-core | rigid | 6/6 | 0.0488 mm | 0.1 mm | pass |
| exact-core | affine | 6/6 | 0.0653 mm | 0.1 mm | pass |
| capture-range | rigid | 6/6 | 0.0320 mm | 0.25 mm | pass |
| capture-range | affine | 6/6 | 0.0558 mm | 0.25 mm | pass |
| partial-slab | rigid | 6/6 | 0.0380 mm | 0.25 mm | pass |
| partial-slab | affine | 6/6 | 0.0491 mm | 0.25 mm | pass |

The nonlinear-contrast maximum RMS was 0.1126 mm for rigid and 0.3107 mm for
affine. Those values do not enter the exact-recovery admission gate.

The raw 48-row result is
`benchmarks/flashalign/raw/linear-automatic-confirmation-2026-09-13.jsonl`
(SHA-256 `0f5b25b34fee92670add2e5683404a9770105f20245a99ffe2c7fd2f1119c2a8`).
The frozen accuracy summarizer produced
`benchmarks/flashalign/receipts/linear-automatic-confirmation-execution-v1.json`
(SHA-256 `dc77dc8c082e5ba319590aa5724bd3a5d1763b8f5e8ce23ecd34a7abd341b20e`).

## Complete cost

Every successful row built `FlashalignOutput` from the original moving image,
materialized one linearly interpolated image on the fixed grid, consumed its
values and validity weights, and recorded a positive output-stage time. Total
wall time also includes input read and decompression, plan preparation,
structural capture, every candidate refinement, final audit, and output.

| Court scope | Model | Median total | p95 total | Desirable ordinary limit |
|---|---:|---:|---:|---:|
| all 24 rows | rigid | 3371 ms | 3787 ms | — |
| all 24 rows | affine | 3711 ms | 4318 ms | — |
| ordinary same-subject, 6 rows | rigid | 2240 ms | 3887 ms | 1000 ms |
| ordinary same-subject, 6 rows | affine | 2191 ms | 4318 ms | 2000 ms |

Only 2/6 ordinary rigid rows were below 1 second, and 3/6 ordinary affine rows
were below 2 seconds. Structural capture dominated the slow rows: across all
models its median was approximately 3 seconds, while median final-output time
was approximately 2.3 milliseconds. The complete cost evidence is present,
but the desirable automatic-path latency targets did not pass. The cost receipt
is `benchmarks/flashalign/receipts/linear-automatic-confirmation-cost-v1.json`
(SHA-256 `0a5cf88e662e34fa9db3520d3a0c597a87315a193251cf677a75abff2bb0cd51`).

## Harness correction and gates

Attempt 1 stopped before writing any row because a diagnostic output checksum
summed non-finite image voxels. The benchmark runner rejected its own non-finite
metric. The preserved attempt record is
`benchmarks/flashalign/raw/linear-automatic-confirmation-2026-09-13-attempt-1.json`.
The correction counts non-finite voxels and sums finite values only. It changed
no Flashalign source, fixture, transform, threshold, failure penalty,
configuration, or adjudication. The manifest discloses the attempt and rebound
runner.

The final exact candidate passed 161 Flashalign JVM tests, 160 Flashalign
Scala.js tests, and 13 benchmark JVM tests. The projected-patch allocation gate
remained 24 bytes at both 2,000 and 20,000 iterations. The architecture PRD
validated 148 unique IDs, 28 nodes, and 95 acyclic edges; the build graph matched
those 28 nodes and 95 edges; symbol ownership remained unique across 1,411
public names and 66 canonical owners.

## Decision

The automatic linear candidate is accuracy-qualified on this sealed
analytic-synthetic court, including capture-range and partial-slab rows. Its
complete automatic-path latency misses the intended fast-product targets, so
the FA-GL release decision remains withheld. Flashalign must reduce structural
capture tail cost on development material and then face another unopened
accuracy and cost confirmation before it can be admitted as the initializer in
C04. This result makes no acquired-MRI, anatomical, clinical, publication, or
cross-method superiority claim.
