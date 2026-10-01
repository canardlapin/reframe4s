# BasinBridge B3: topology-safe group line-search assimilator

Mote issue: `sf-01ky5p20mngx2xcj8xzrxhyxrz`.

B3 consumes one projected BasinBridge velocity and selects a globally safe
fraction before advancing the existing paired HalfFlow midpoint state. The
synthetic contract is now wired through a native same-grid matcher, a shared
frozen-support true-Neighborhood-CC evaluator, and an explicit post-acceptance
rewarp/rematch round. It remains a synthetic engineering result: no anatomical
accuracy or ANTs parity claim is made without the external licensed lane.

## Contract

The authoritative observation remains `p - q`, where `q` is fixed and `p` is
moving. The existing `ForwardMidpoint` engine has the opposite sign for a
directly applied tangent, so B3 applies the explicit negative bridge while
constructing the paired exponential. There is no direct `x + u` proposal path.

For each projected velocity, B3:

1. computes the initial alpha from the configured percentile of the physical
   symmetric-strain Frobenius norm, clamped to one and the minimum alpha;
2. evaluates alpha, alpha/2, alpha/4, and so on globally;
3. retries paired scaling-and-squaring at a fixed alpha with higher squaring
   depth before rejecting that alpha for integration error;
4. composes both existing paired half-flow arms, checks the paired inverse
   diagnostic, and checks incremental and accumulated Jacobian geometry on an
   interior margin large enough for the candidate's finite-lattice reach;
5. evaluates a confidence-weighted sparse match-error objective plus a caller
   supplied frozen-support true-CC loss;
6. accepts only the largest tested fraction with meaningful match-error drop,
   no allowed true-CC increase, and valid topology. Rejection returns the
   original immutable state and diagnostics.

The interior margin is a numerical/topology support boundary, not a relaxation
of the Jacobian floor. The default geometry floor remains unchanged; tests use
a stricter synthetic floor where they need to force a compressive backtrack.

## Integration boundary

`BasinBridgeRound` keeps two named observations for every round. Work matches
are used to project a tangent in the midpoint work frame. The same matches are
lifted through the current fixed and moving midpoint arms before they enter the
fixed-to-moving sparse objective. This prevents identity-aligned synthetic
tests from hiding an endpoint/work-frame mix-up.

`BasinBridgeFrozenCcObjective` prepares support and Neighborhood-CC weights
once per round. Alpha trials rewarp the native images against that immutable
snapshot. A new evaluator is created only after an accepted state, when native
sources are rewarped and the matcher is run again.

## Accuracy evidence

`BasinBridgeAssimilatorSuite` covers on both JVM and Scala.js:

- a projected constant 12 mm tangent accepts alpha one, drives the weighted
  match error to zero, and retains unit determinant through a regrid-core
  check;
- a compressive field rejects alpha one at the configured Jacobian floor and
  accepts alpha one-half after independent integration-depth escalation;
- an objective-reported CC increase rejects every candidate and preserves the
  original state object;
- a native image round rejects a deliberately reversed sparse proposal because
  the actual frozen Neighborhood-CC loss increases, while the accepted native
  round decreases it;
- swapping endpoints and negating the tangent preserves accepted alpha and
  swaps the exported forward/backward maps.

These tests prove the typed selector, actual frozen-support true-CC accept and
reject behavior, work-to-endpoint lifting, native matcher integration, and
JVM/JS behavior. The remaining limitations are the multi-lane B4 decomposition,
runtime/allocation court, and the external same-affine ANTs/anatomical
validation lane.
