# HalfFlow inverse-lattice resolution study

This bounded R02 experiment tests whether a denser stored inverse reduces the
remaining round-trip error of the corrected r24 forward fields. The
[preceding cell correction](halfflow-cell-admission-fix-v1.md) eliminated the
sampled forward folds, but the stored inverse still exceeds the unchanged
0.2 mm full-boundary limit. Solving inverse roots on the original lattice
does not meet that limit either.

## Prospective design

The [declaration](evidence/halfflow-inverse-refinement-20261015/declaration-v1/artifact-manifest.json)
was archived before launch. It binds both corrected C4 r24 arms, the previous
root study, the complete 48-field cell audit, exact input payloads, numerical
sources and passing analytic checks. Each authoritative forward field must
match its independent passing complete-cell audit.

The three inverse grids are 37³, 73³ and 145³, with physical spacings 2, 1
and 0.5 mm. Their physical box and orientation are identical. Every target
uses the existing stored inverse as its seed; the existing bounded root
solver retains 40 iterations, a 4 mm step cap, 12 backtracks and a 1e-8 mm
equation tolerance. There is no padding, restart, alternative seed, optimizer
change or production-source edit. A single numerical thread processes
32,768 targets per chunk, with a declared 1,800-second worker deadline.

Both compositions retain all 50,653 original evaluation nodes per arm,
including their full boundary. The original-grid variant must reproduce the
previous exact-root baseline within 1e-10 mm before the finer variants are
interpreted. Unsupported seeds fail explicitly. Unresolved roots, invalid
queries and nonpositive root Jacobians remain failures.

The diagnostic pass requires all inverse roots to converge with positive
root Jacobians, both original-domain compositions to meet 0.2 mm with zero
invalid queries, and every inverse lattice to pass the unchanged all-cell
topology criteria: eight corners and center, a 0.05 Jacobian floor and at
least 99% complete-cell coverage. A separate inside/exterior query breakdown
helps diagnose interpolation versus extrapolation; it never removes queries
from acceptance.

## Completed results

All six declared variants completed under the resource bound. Both
original-grid variants reproduce their prior cycle maxima within 1e-10 mm.
All 6,976,590 inverse-grid root solves converge with positive sampled root
Jacobians. Every inverse lattice passes the unchanged complete-cell check,
with 100% complete-cell coverage and no folded cells. Every composition
retains all 50,653 original points, with zero invalid queries.

| Inverse spacing | Nodes per arm | Fixed: inverse(forward(x)) max (mm) | Moving: inverse(forward(x)) max (mm) | Both arms meet 0.2 mm? |
| --- | ---: | ---: | ---: | --- |
| 2 mm / original | 50,653 | 0.756696 | 0.605013 | No |
| 1 mm / twice resolution | 389,017 | 0.330257 | 0.357573 | No |
| 0.5 mm / four times resolution | 3,048,625 | 0.194255 | 0.206091 | **No** |

The opposite composition, forward(inverse(y)), stays below 1e-8 mm in every
variant. At four-times resolution, the fixed arm meets the complete declared
diagnostic, while the moving arm remains 0.006091 mm above its unchanged
limit. This is a failed two-arm result; no additional resolution was tried
after inspecting it.

For context, the existing balanced production refiner's stored inverse had
worst cycle errors 0.465460 mm and 0.388483 mm on these fields. Replacing it
with exact roots on the original grid initially makes interpolation error
worse. The two finer representations reduce that error, without changing
the forward map, root solver budget, inverse physical extent or evaluation
population.

The maxima at every resolution occur **inside** the inverse physical box.
At four-times resolution, the separate exterior-query maxima are 0.047173
mm (4,202 fixed-arm points) and 0.069771 mm (3,744 moving-arm points). Every
exterior query remains in the main denominator. Thus the remaining observed
maximum is not explained by exterior support alone. The resolution trend
provides constructive evidence that stored-inverse interpolation contributes
to the residual error; it does not establish a general convergence rate.

The [terminal archive](evidence/halfflow-inverse-refinement-20261015/outcomes-v1/artifact-manifest.json)
retains all six scalar/chunk receipts, full cell audits, both query partitions,
the terminal attempt, source and input seals, and hashes for all local dense
inverse payloads. A post-outcome confirmation using SciPy's independent
multilinear interpolator reproduces all twelve cycle maxima and their
pass/fail verdicts within 1e-10 mm. This confirmation changes no primary
result or prospective criterion.

The next representation experiment should target local interpolation error
with bounded local refinement or an inverse evaluation correction, retaining
whole-field composition and topology checks. Uniform refinement already
raises inverse storage about sixtyfold and still misses the two-arm target.
R02 remains open; this study does not change production export behavior.

## Verification and scope

All 14 independent Python checks pass, including five new checks of refined
oblique/left-handed lattice bounds, affine inversion through the full
boundary, comparison with an independent nonlinear knot-exchange inverse,
unresolved roots/invalid seeds, and unchanged query accounting. Existing
root-sampler and exact representation checks remain included.

The study concerns two arms of one previously observed synthetic case. Even
a diagnostic pass would not establish endpoint export, convergence, acquired
MRI accuracy, uniqueness or global continuous invertibility. A four-times
linear resolution also stores about 60 times as many inverse nodes as the
original lattice; resource and production-design decisions remain separate.

Reproduce the analytic checks with:

```sh
python -m unittest discover -s benchmarks/halfflow/boundary -p 'test_*.py' -v
```

The archive retains the exact experiment source, protocol and identified
local dense-field inputs. To reproduce a new attempt, use
`inverse_refinement.py freeze` with a fresh `--output`, the prior corrected
root study as `--previous` and a passing test log as `--checks`, then archive
that declaration before invoking `run`. Existing attempts are never resumed
by overwriting their receipts.
