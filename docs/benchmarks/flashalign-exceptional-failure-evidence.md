# Flashalign exceptional linear failure evidence

FA-L27 closes the evidence gap left by bounded nonconvergence. A typed evaluator
error or a nonfatal provider exception no longer discards a valid transform,
selection checkpoint, work counters, or completed pyramid levels merely because
the optimizer returned through an exceptional path.

## Optimizer boundary

The projected-patch optimizer establishes its initial optimization objective
and independent selection checkpoint before entering the trust-region loop.
After that checkpoint exists, failures from data or prior evaluation, physical
metric construction, proposal construction, maximum-displacement evaluation,
trial evaluation, checkpoint evaluation, and quadratic validation terminate as
`ProjectedPatchTermination.ExceptionalFailure`. The result retains:

- the best state under the frozen selection objective;
- the last valid optimizer state, which may differ from the best state;
- initial and last optimization objectives;
- every completed attempt record; and
- exact optimizer counters, including the evaluation attempt that failed.

Provider calls are wrapped with Scala `NonFatal` capture and converted to the
same typed evaluation failure. Fatal VM conditions remain outside this recovery
contract.

An error during the first data or selection evaluation occurs before a valid
selection checkpoint exists. That path now records attempted optimizer work and
partial data work in public failure diagnostics with `lastCheckpoint = None`.
It does not manufacture a selection score or reuse the optimization objective
as a substitute. A later pyramid level that fails before its own checkpoint
retains the last completed level's checkpoint, transform, levels, and work.

## Data-work boundary

Rigid and affine linearization workspaces snapshot fused interpolation and
patch progress independently of successful result construction. Because fused
linearization sampling is eager, its reusable sparse buffer also exposes the
current requested, supported, rejected, and source-read counts if a provider
throws during sampling. Rigid and affine loss-only trial workspaces record
completed sampling and patch progress before an objective error can return.

The refinement adapters add those snapshots to their work tracker on both
typed errors and nonfatal exceptions. Successful evaluations still add the
ordinary result counters exactly once. Cross-platform tests assert that the
workspace snapshot equals the successful result counters for rigid and affine
linearization and trial evaluation.

## Pyramid, audit, and evidence rows

`LinearEngine3` converts a nonconverged exceptional result into the existing
evidence-bearing fit rejection. The pyramid wrapper appends the failed level
when it has a checkpoint and adds its work to every previously completed level.
If the next level fails before a checkpoint, the wrapper retains the previous
level without pretending that the failed level completed.

Final audit errors have their own typed `FinalAuditFailed` result. They retain
the selected refinement checkpoint, every executed pyramid level, accumulated
candidate work, and partial audit work. The automatic path continues to perform
only one audit after candidate selection.

At the benchmark boundary, a failed schema-1.1 row keeps the `result` object
with `moving_to_fixed` and `sha256` both null. Its separate `last_checkpoint`
object contains independently hashed best and last-valid matrices. The
regression uses deliberately different matrices so an accidental collapse of
those two meanings is visible.

## Validation

The focused failure and work-accounting suites passed 19 tests on the JVM and
19 on Scala.js. The complete final module run passed 161 JVM tests and 160
Scala.js tests; the benchmark runner passed all 12 tests. The existing allocation
probe remained 24 bytes at both 2,000 and 20,000 projected-patch iterations.
Build-graph, PRD, and symbol-ownership checks also passed.

This change does not modify the frozen preset thresholds, patch objective,
trust-region acceptance rule, checkpoint comparison, capture ranking, or audit
selection. It changes failure capture and accounting. The sealed confirmation
court remains unopened and must be rebound to this final source-tree hash before
FA-L28 executes it.
