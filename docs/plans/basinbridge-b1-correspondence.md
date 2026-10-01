# BasinBridge B1: typed correspondences and soft confidence

Mote issue: `sf-01ky5p1zvxa0f6zsb2wpvmczeg`.

B1 adds the smallest portable evidence contract and an explicit-geometry native
same-grid search path without changing HalfFlow controls or projection policy.
Block size and search radius remain caller-supplied because the historical
matcher geometry is not present in this checkout.
The external same-affine anatomical lane remains deferred; these contracts
are validated on deterministic synthetic evidence.

## Contract

`BasinBridgeCorrespondence` stores a fixed-target point `q`, a moving-source
point `p`, and a bounded soft confidence. It derives:

```text
z = (p + q) / 2
t = p - q
```

Swapping the match preserves `z`, negates `t`, and preserves confidence.
Low-confidence matches remain values in the collection; this layer does not
hard-delete them.

`BasinBridgeConfidenceEvidence` accepts independent forward and reverse
displacement distributions, a non-negative forward/reverse cycle error, and an
optional prior confidence. The score is:

```text
ambiguity = sqrt(forwardQuality * reverseQuality)
cycle = exp(-cycleErrorMm / cycleScaleMm)
effectiveWeight = priorConfidence * ambiguity * cycle
```

Normalized entropy controls each soft quality. The default 0.02 ambiguity
floor keeps flat blocks inspectable while assigning them near-zero weight.
Candidate ordering has no effect on the score.

`BasinBridgeSearchObservation` is the typed boundary for one completed
forward/reverse search result. It stores `q`, `p`, `qBack`, the two candidate
distributions, and a prior; its cycle error is derived from `distance(qBack, q)`.
`BasinBridgeSearchAdapter.fromForwardReverse` maps those observations to
correspondences without changing their order or deleting weak matches, and
returns finite aggregate diagnostics for confidence and cycle error.

This remains an evidence adapter, not a native block matcher. The current
search geometry and image ownership remain explicit. `BasinBridgeBlockMatcher`
now consumes fixed and moving `RegistrationImage`s on one shared grid,
trilinearly samples the declared block, scores candidates by z-normalized
squared error, retains the candidate score distribution, and executes a real
reverse search from the selected moving point. The matcher reports through the
same observation adapter, so matcher accuracy/runtime remain separate from
bridge projection and HalfFlow refinement.

This native path is intentionally narrower than the eventual anatomical lane:
it does not recover the absent historical geometry, resample different grids,
or claim anatomical registration accuracy.

## Verification

The correspondence, adapter, and native matcher suites cover translation sign,
reverse-cycle derivation, swap symmetry, unique versus multimodal and
cycle-inconsistent evidence, flat-block soft weights, observation reordering,
deterministic image search, boundary support, finite/bounded output, and
invalid-input rejection on JVM and Scala.js. The combined focused B1 gate passes
19/19 on each platform; the full HalfFlow suites pass 130/130 on each platform.
These are synthetic and same-grid contract gates, not anatomical registration
accuracy gates.
