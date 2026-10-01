# Flashalign linear release assessment

FA-GL remains withheld for candidate
`d3e96fdfcc4525a473ab42d3b371c15b7fa907ac2859809996237b245dea3c9f`.
The candidate now has a trustworthy cancellation fallback, evidence-bearing
exceptional failures, a public automatic capture and refinement path, one-shot
final output resampling, a fresh unopened accuracy court, and complete JVM and
Scala.js gates.

The fresh court resolves the previous accuracy blocker. All 48 rows succeeded.
All 36 interpolation-exact rows passed their frozen thresholds, with worst
exact-core RMS below 0.066 mm and worst capture-range or partial-slab RMS below
0.056 mm. The remaining 12 nonlinear-contrast rows were descriptive by the
sealed protocol.

The complete-cost evidence does not support release as the intended fast
automatic product. On six ordinary same-subject rows per model, rigid median
and p95 total latency were 2240 and 3887 ms against the desirable 1000 ms
limit. Affine median and p95 were 2191 and 4318 ms against 2000 ms. Only 2/6
rigid and 3/6 affine rows met their respective limit. Structural capture,
approximately 3 seconds at the median on slow rows, accounts for the tail;
final output resampling is approximately 2.3 ms at the median.

The negative latency result does not invalidate the accuracy qualification.
It does prevent describing the current automatic workflow as the independently
qualified fast linear product. FA-L31 owns a development-only investigation and
narrow correction of structural-capture tail cost. Any changed candidate must
face another unopened accuracy and complete-cost court. C04 remains blocked
until FA-GL passes; the affine result is not yet admitted as a qualified
HalfFlow initializer.

The authoritative evidence is
`benchmarks/flashalign/receipts/linear-automatic-qualification-v1.json`
(SHA-256 `d1864b980dbb5281eb2cb0dc11b9002d95ff24bb7336e0962c6783a01d6b41c8`).
This assessment makes no acquired-MRI, anatomical, clinical, publication, or
cross-method superiority claim.
