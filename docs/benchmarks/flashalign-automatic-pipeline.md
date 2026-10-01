# Flashalign automatic linear pipeline

This note records the FA-L25 public workflow implementation and its development
evidence. The cancellation correction that made this work meaningful is recorded
separately in `flashalign-linear-cancellation-analysis.md`.

## Executed public workflow

`RigidFlashalignPlan.run` and `AffineFlashalignPlan.run` now execute automatic
initialization when the plan was compiled with
`CoherentHeaderWorldIdentityThenCapture`. `runFrom` remains the explicit
supplied-transform entry point.

The automatic path executes these stages:

1. It derives physical bounding boxes from the complete moving and fixed
   index-to-world affines. It builds support-aware image towers and resamples
   the coarsest prepared values and supports onto axis-aligned physical capture
   lattices with common spacing.
2. It builds normalized gradient tensors and runs the bounded structural
   capture schedule. The deterministic schedule begins with the identity
   rotation and searches translations before adding bounded rotations.
3. It converts every retained capture candidate into a complete
   moving-to-fixed world transform. For rotation `R`, pivot `p`, and captured
   translation `t`, the affine offset is `p - R p + t`. Affine plans use this
   rigid transform as the initial affine.
4. It refines every retained candidate through the declared support-aware
   pyramid. For the `WithinModality` regression pair, the executed effective
   resolutions are 6.0, 3.0, and 1.5 mm. A nonterminal coarse level that cannot
   form the required optimization, selection, and audit roles is skipped; the
   native level remains mandatory.
5. It ranks successful candidates by the final native-level selection
   objective with deterministic rotation and lag tie breaks. It performs the
   audit exactly once, after selection, on the winning candidate.
6. It returns the canonical bidirectional registration result. A
   `FlashalignOutput.rigidPlan` or affine output plan then samples the original
   moving image once on the requested fixed grid; no coordinate volume is
   materialized.

A minimal rigid call is:

```scala
val config = FlashalignConfig.forPreset(
  FlashalignPreset.WithinModality,
  FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
)
val plan = Flashalign.rigid(moving, fixed, config).toOption.get
val result = plan.run(plan.newWorkspace()).toOption.get
val outputPlan = FlashalignOutput.rigidPlan(
  moving,
  fixed.grid,
  result,
  Interpolation.Linear,
  BoundaryPolicy.Constant(0.0)
).toOption.get
val registered = outputPlan.run(outputPlan.newWorkspace()).toOption.get
```

Production callers should retain the `Either` values rather than discarding
typed errors as this compact example does.

## Diagnostics and failure behavior

Successful results expose the capture schedule identity, evaluated rotations,
all retained candidate scores, each refinement failure or final objective, the
selected candidate, ambiguity, per-stage elapsed time, per-level objective and
work, and one aggregate work record. Candidate refinement work is accumulated
for every successful candidate; the final audit count is one.

Capture rejection and exhaustion are typed. If capture succeeds but none of its
retained candidates converges, `NoCaptureCandidateConverged` retains every
candidate failure message. No failed candidate can erase a successful candidate
because selection occurs only after every retained candidate has been tried.

Two accounting limitations remain relevant to release evidence. The policy
currently always runs bounded capture; it does not yet use a separately
validated coarse-identity adequacy check to skip the expanded search. Also, a
whole-run failure cannot return the internal work and stage diagnostics through
the current `Either[FlashalignError, Result]` API. The development runner retains
the total elapsed time and the explicit failure, but places execution time in
the optimize field and leaves capture/work counters at zero for such rows.
Fresh cost qualification must address those limitations rather than treating
zero failure-row counters as zero work.

## Cross-platform regression evidence

The public rigid regression starts from a 6 mm, -6 mm displaced analytic image
pair and recovers each translation within 0.35 mm. It then executes the
canonical one-pass output plan. The affine regression starts from a rigid
capture and recovers a mild scale, shear, and translation transform with less
than 0.9 mm maximum error across five independent landmarks. Both tests assert
the three-level pyramid, capture diagnostics, candidate selection work, and one
audit. A separate test verifies that `run` under the supplied-transform policy
returns `InitializationPolicyMismatch`.

The exact final source state passed 150 JVM Flashalign tests, 149 Scala.js
Flashalign tests, and 9 JVM benchmark-runner tests. Repository graph, symbol
ownership, and PRD consistency checks also passed. The projected-patch hot-loop
probe retained its prior result of 24 allocated bytes at both 2,000 and 20,000
iterations.

## Opened-cohort development result

The previously opened 32-case FA-L23 cohort was rerun through the automatic
entry point without changing the frozen preset. It produced 14 successes, 8
iteration-limit failures, 10 stalled failures, and no numerical failures. The
preceding corrected supplied-identity development run produced 8 successes, 13
iteration-limit failures, and 11 stalled failures.

Among the 14 automatic successes, landmark RMS ranged from 0.6100 to 4.2350 mm,
with median 0.8912 mm. Thirteen were at or below 2 mm and nine were at or below
1 mm. Only two of four ordinary rigid rows succeeded, at 0.6100 and 1.0345 mm;
therefore the frozen release gate requiring all ordinary rigid cases at or
below 0.1 mm is not met. Median total development runtime was 4,698 ms, compared
with 178 ms for the supplied-identity run, and includes structural capture plus
refinement of up to four candidates.

This cohort is development-open and cannot supply fresh confirmation. The
automatic workflow is now executable and cross-platform qualified as an
engineering slice. Linear accuracy, identity short-circuit behavior, complete
failure-path cost accounting, fresh sealed evaluation, and release admission
remain open.
