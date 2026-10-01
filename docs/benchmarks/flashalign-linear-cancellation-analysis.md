# Flashalign linear cancellation analysis

This note records the numerical defect exposed by the sealed FA-L23 court and
the bounded FA-L24 correction. The exact starting state is captured in
`benchmarks/flashalign/receipts/linear-post-audit-baseline-v1.json`.

## Failure mechanism

For fixed intensities `f`, normalized centered values `v`, and an intensity
Jacobian `G`, the normalized-intensity Jacobian is

```
J = (C G - v (v' G)) / ||C f||,
```

where `C` subtracts the sample mean. The Gauss-Newton curvature is the positive
semidefinite Gram matrix `J'J`.

The allocation-bounded fast path obtains the same matrix from streamed moments.
Its expression subtracts terms of the form

```
sum(g_i g_i') - sum(g_i) sum(g_i') / n.
```

When a derivative column has a large common component and small observable
variation, both terms are large and their difference is small. Floating-point
cancellation can then produce a materially negative diagonal or an inaccurate
off-diagonal even though the mathematical matrix is a Gram matrix.

The post-L23 code detected that condition and constructed explicit centered
Jacobian rows, but still required them to agree with the unstable streamed
subtraction. Reduced tests with derivative offsets of `1e6` reproduced the
problem: the direct result was finite and matched the independent projector,
while the streamed comparison differed by as much as `0.0040` and caused the
entire patch to fail. This made the recovery path diagnostic rather than a
working fallback.

## Correction

The diagnostic path now treats the directly constructed `J` and `J'J` as the
authoritative result after cancellation has selected that path. It does not
relax the fast-path cancellation threshold. It separately rejects non-finite
correlation, direction, or curvature values and retains the explicit
positive-semidefinite diagonal check.

Gradient columns are centered after subtracting their first representable
sample. This avoids losing their small observable variation while summing a
large common offset. The test oracle applies the projector through an
independently allocated dense Jacobian and Gram matrix using the same stable
origin-shift principle.

## Regression evidence

The focused suite covers six- and twelve-parameter systems, duplicate
poorly-constrained columns, offsets up to `1e12`, central finite differences,
near-perfect correlation, near-constant fixed contrast, invalid contrast, and
non-finite inputs. JVM and Scala.js must return the same explicit fallback mode
and pass the same numerical bounds.

The historical 32-case court is already opened. It can diagnose whether this
change removes the observed numerical failures, but it cannot become fresh
confirmation evidence. Any linear admission requires a new frozen court and a
cost receipt tied to the exact corrected source-tree hash.

The candidate-only development rerun produced 8 successes, 13 iteration-limit
rows, 11 trial-attempt-limit rows, and no numerical-failure rows. L23 had
produced 1 success, 2 iteration-limit rows, 2 trial-attempt-limit rows, and 27
numerical failures. This isolates the cancellation defect from the remaining
optimizer and capture work. One accepted development result had 12.779642 mm
landmark RMS, so accepted termination is not being treated as accuracy.
