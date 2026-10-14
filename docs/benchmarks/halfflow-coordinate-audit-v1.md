# HalfFlow coordinate and image-role audit

The [pyramid derivative study](halfflow-pyramid-chain-v1.md) supports the
diagnostic source/composition derivative at several observed states. Coordinate
and image-order behavior still need independent checks before a production
change. This study tests physical coordinate identities and then tests image
role swapping on three saved acquired-image states. It introduces no optimizer
policy and uses no qualification subjects.

## Synthetic coordinate checks

`HalfFlowCcEquivarianceSuite` uses unequal fixed, moving and work grids with
oblique anisotropic geometry, nonidentity arm affines and nonconstant residual
maps. Its four checks pass on **JVM and Scala.js**:

1. Swapping images and their saved arms preserves the frozen CC objective,
   reverses the symmetric spatial derivative, and gives equal candidate losses
   when the velocity sign is reversed.
2. Rotating or reflecting the physical coordinate system preserves the objective
   and candidate values and transforms the spatial covector correctly. This
   includes a coordinate change with negative determinant and both symmetric
   midpoint and fixed-anchor actions.
3. The complete frozen CC directional derivative agrees with independently
   evaluated positive/negative candidates through actual paired integration,
   composition and image interpolation on the unequal oblique grids. Both
   actions satisfy the existing 0.5% minimum symmetric relative error and
   three-point decreasing-error criterion over the seven declared test steps.
4. Nonzero intensity derivatives at work-grid boundaries are counted as
   unavailable. The zero contents of their output buffer are never mistaken
   for a valid measured derivative.

Together with the existing probe suites, **13 tests pass on each platform**.
The acquired-image runner compiles and its two existing checkpoint/argument
tests pass. Four Python verdict checks pass: complete and legitimate negative
outcomes, duplicate candidate substitution, falsely reported loss agreement,
and missing support or altered tolerances. The initial sbt sandbox-lock failure
and a system-Python invocation lacking the project's `nibabel` dependency are
retained beside the successful runs. No numerical tolerance was changed.

These checks establish synthetic coordinate identities. They do not certify
full-volume boundary inversion, anatomical benefit, arbitrary affine changes
of physical units, or all acquired-image grid combinations.

## Declared acquired-image role swaps

The [pre-execution protocol](evidence/halfflow-coordinate-audit-20261014/declaration-v1/declared-protocol.json)
selects exactly these previously observed states:

| Pair | State | Event | Original captured loss |
| --- | --- | ---: | ---: |
| OASIS 11 → 12 | Coarse end | 22 | 0.431496606182 |
| OASIS 13 → 14 | Fine end | 44 | 0.416309794869 |
| MMRR 11 → 12 | Fine start | 23 | 0.482824721177 |

`RealImageOrderAudit` prepares each pyramid level through the production helper,
reloads the saved maps on their original work grid, and swaps the images,
validity masks and arms together. The original fixed fitting mask remains
attached to its original image, which becomes the moving image in this private
objective probe. No optimizer is called; this is not a proposal to allow moving
optimization masks.

Every original complete objective audit must reproduce the prior pyramid audit
exactly. Each state then compares both signs of translation, smooth wave and
negative production gradient at `0.01` and `0.0001 mm`: **12 candidate pairs per
state, 36 in total**. The original candidate at a signed velocity is compared
with the swapped candidate at its opposite. Candidate and initial loss
differences must be at most `1e-12`. Both production and diagnostic spatial
gradients must reverse, with maximum absolute summed component at most `1e-11`
and relative L2 sum at most `1e-10`. Spatial support must be complete and swap
exactly; production validity must be identical. Both frozen current values must
remain unchanged after all candidates.

Every declared attempt is retained. A process failure, missing state, failed
identity or incomplete support fails the three-state replication. No case is
replaced because of its outcome. The Python validator reconstructs the complete
signed-candidate ledger and loss arithmetic and checks every declared verdict.

The frozen numerical source is
`890f7b4d221d2091b1c30091f36e46c8ee86f559a908a148593a3dc8ebcc64c3`.
Its verified delta from the pyramid engine contains only the new diagnostic
runner and synthetic coordinate suite. The production optimizer, gradient and
interpolation kernels are unchanged. The
[declaration archive](evidence/halfflow-coordinate-audit-20261014/declaration-v1/artifact-manifest.json)
contains the source patch, checks, source/input hashes and original audit/state
metadata, with no new acquired-image outcomes or image/map payloads.

One worker runs attempts sequentially, with one active Java processor, a 6 GiB
heap and a one-hour deadline per attempt. Concurrent diagnostic walltimes cannot
support production speed claims.

## Completed acquired-image outcomes

All three attempts completed and passed the declared role-swap criteria. Each
original complete objective audit reproduces its prior pyramid audit exactly.
All **36 signed candidate comparisons** are retained in the
[complete ledger](evidence/halfflow-coordinate-audit-20261014/outcomes-v1/results.json).

| Saved state | Maximum candidate loss difference | Production gradient relative L2 sum | Spatial-chain gradient relative L2 sum |
| --- | ---: | ---: | ---: |
| OASIS 11 coarse end | 2.50e-15 | 1.33e-14 | 1.26e-14 |
| OASIS 13 fine end | 1.68e-14 | 2.41e-13 | 2.46e-13 |
| MMRR 11 fine start | 1.02e-14 | 1.14e-13 | 1.05e-13 |

Initial loss differences are at most 4.89e-15. The maximum absolute summed
gradient component is below 1e-16 for both gradients on all three states.
Spatial support is complete and swaps exactly, production validity is identical,
and both current frozen losses remain exactly unchanged after all candidates.
The [outcome archive](evidence/halfflow-coordinate-audit-20261014/outcomes-v1/artifact-manifest.json)
rechecks all identities, reconstructs every signed candidate key and loss
comparison, recomputes the verdicts, and verifies that the declaration archive
is unchanged. It preserves all logs and scalar measurements, with no MRI or
dense-map payloads.

Both the production and diagnostic gradients pass the role-swap identity,
despite their different derivative-accuracy results in the pyramid study. This
rules out a simple failure to reverse under consistent image/arm relabeling on
these saved grids. It does not explain the production-gradient mismatch,
demonstrate anatomical improvement, or establish order independence of complete
registration with different reference grids and fitting masks.

## Reproduction and remaining scope

Reconstruct the pyramid engine using its documented source-patch chain, then
apply this declaration's `source.patch`. Run the three named probe suites on
JVM and Scala.js and the existing acquired-image loader suite. Use the project's
Python environment for `test_order_study.py`, then `order_study.py freeze`
against the completed pyramid study and the new compiled classpath. Execute
`order_study.py run` only in the new, sealed study directory.

A same-work-grid role swap does not test reverse fitting, exchanging the chosen
reference lattice, asymmetric fitting-mask selection, or the complete optimizer.
Those acquired-image checks, applicable regularization, anatomical trajectories
and a bounded optimizer experiment remain required. D01/D02 and the
qualification epic remain open.
