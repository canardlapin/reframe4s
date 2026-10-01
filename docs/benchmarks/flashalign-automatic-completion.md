# Flashalign automatic identity and failure evidence

This note records FA-L26, which completes the ordinary automatic-initialization
control flow and preserves evidence for bounded nonconvergence. It follows the
public workflow implemented in FA-L25 and does not change the frozen linear
preset thresholds.

## Coarse identity eligibility

Automatic capture now resamples both prepared images onto one axis-aligned
physical lattice covering the union of their complete affine-derived world
bounds. This makes zero correlation lag the exact coherent world-identity
transform even when the source image grids differ.

The capture schedule evaluates its identity rotation first. Identity is
eligible to skip expanded capture only when the exact zero-lag location:

- meets the preset minimum overlap;
- meets the preset minimum structural energy;
- meets the preset minimum structural score; and
- is retained as a deterministic local maximum under the existing peak and
  separation rules.

These conditions reuse the frozen capture policy. The first attempted rule used
only the three numeric thresholds. A displaced 6 mm analytic pair still passed
that permissive rule, and affine refinement degraded from less than 0.9 mm to
4.55 mm maximum landmark error. Requiring zero lag to be a retained local
maximum rejected that rule without introducing or tuning a new tolerance.

An eligible identity is returned as one explicit capture candidate after one
rotation evaluation. If it is not eligible, the existing bounded translation
and rotation schedule runs. The public diagnostics report the identity overlap,
score, eligibility decision, and whether expanded capture executed.

## Bounded failure evidence

`FlashalignError.failureDiagnostics` now exposes evidence when it is available.
For a bounded nonconverged refinement, each capture candidate retains its
failure message, exact work, executed pyramid levels, best selection checkpoint,
and final valid transform. The automatic result aggregates work from failed and
successful candidates before the one permitted audit. If every candidate
exhausts, `NoCaptureCandidateConverged` retains all candidate rows, their summed
work, the best diagnostic checkpoint, capture/refinement times, and zero audit
work. A capture rejection retains its schedule and preparation/capture times.

Final audit rejection also carries refinement, audit, work, level, and
checkpoint evidence. Direct `runFrom` bounded rejection exposes the same linear
failure record without capture diagnostics.

The pair-evidence schema is version 1.1. Failed rows keep `result` null and
serialize a separate `last_checkpoint` object. That object contains the best
selection transform and the optimizer's final valid transform, with independent
hashes, objectives, accepted-step count, and selection-objective identity. The
runner validates matrix dimensions, finiteness, hashes, objectives, counts,
work, and stage times before writing a row.

An exceptional internal optimizer error returned before a bounded optimization
result may still lack counters or a checkpoint. The typed error remains visible,
but this slice does not claim complete evidence after arbitrary provider or
evaluation exceptions. Structural-capture work is represented by its explicit
stage time rather than projected-patch work counters.

## Cross-platform evidence

Shared tests cover an aligned image pair that skips expanded capture, displaced
rigid and affine pairs that require it, capture rejection, and a deliberately
restricted optimizer under which every retained candidate exhausts. The latter
asserts nonzero per-candidate and aggregate work, no audit, executed level
diagnostics, a checkpoint, and nonzero capture/refinement times. The final source
state passed 153 JVM Flashalign tests, 152 Scala.js Flashalign tests, and 10 JVM
benchmark-runner tests. The projected-patch allocation probe remained 24 bytes
at both 2,000 and 20,000 iterations.

## Opened-cohort development result

The opened 32-case development cohort was rerun under schema 1.1 against module
tree `7d5c0959cbe4edd4ee9f8a8a84f3d19660c4a1d360403b2f9d4df1c2b21b9490`.
It produced 12 successes, 8 iteration-limit rows, 12 stalled rows, and no
numerical failures. Every one of the 20 failures recorded nonzero capture and
refinement time, 19 to 271 linearizations, a best checkpoint, and a final valid
transform.

Five successful rows used the identity short circuit and seven used expanded
capture. Median total time was 2,710 ms, compared with 4,698 ms before the
short circuit and complete failure accounting. This is a development timing
observation, not a cost qualification.

Successful landmark RMS ranged from 0.3935 to 4.2213 mm, with median 0.8745 mm.
Only one of four ordinary rigid cases succeeded, at 0.3935 mm. The frozen gate
requires all four ordinary rigid cases to succeed at or below 0.1 mm, so linear
accuracy, failure-tail, and release admission remain failed. Because this cohort
was opened during the earlier audit, it cannot be used as fresh confirmation or
as a basis for further preset tuning.
