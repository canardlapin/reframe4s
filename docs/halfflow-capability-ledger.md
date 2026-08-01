# HalfFlow experimental capability ledger

Status: **not admitted to the stable foundation**.

The `reframe4s-halfflow` artifact is experimental and is excluded from the
`reframe4s` umbrella. Its half-time endpoints are independent numerical
estimates. They are not analytic inverses and cannot implement `SmoothIso`.

## Recorded admission threshold

A future immutable HalfFlow implementation revision may change this ledger to
`Passing` only after one frozen evaluation satisfies all of the following:

- at least 20 predeclared anatomical registration pairs from at least two
  independently sourced datasets;
- at least 90 percent convergence under one predeclared parameter policy;
- every exported pair meets its recorded bidirectional residual and coverage
  criteria;
- every exported direction receives a `TopologyCertificate` over its complete
  declared evaluation region with zero folded cells, at least 0.99 covered
  fraction, and minimum sampled cell Jacobian at least 0.05;
- landmark or segmentation agreement improves over the declared affine
  initialization in aggregate, with every loss and excluded pair reported;
- the dataset manifest, implementation revision, settings, per-pair
  diagnostics, and aggregate calculation are reproducible from checked-in
  records.

Passing this finite gate would permit experimental admission to an ordinary
`BidirectionalRegistrationResult`. It would not prove a global
diffeomorphism, create `SmoothIso`, or by itself justify an accuracy comparison
with another registration package.

## Current evidence

| Field | Current value |
|---|---|
| implementation | reframe4s concrete forward-midpoint/HalfFlow CC engine |
| anatomical dataset manifest | unavailable |
| observed anatomical pairs | 0 |
| required anatomical pairs | 20 |
| status | `NotRun` |
| stable export admitted | no |

The checked-in fixtures migrated from ScalaFIM are synthetic local-refinement
evidence and remain versioned independently of the implementation. The
2 mm case is admitted by the extant export checks; the 4, 8, and 12 mm cases
exercise typed topology rejection. That is useful algorithm-development
evidence but does not satisfy the anatomical threshold above.

The complete engine, controls, kernels, utilities, fixtures, shared law suites,
platform probes, and JMH benchmarks now live in this repository. The shared
HalfFlow court passes 100 tests on each of JVM and Scala.js. The JVM probe also
checks exact agreement between reference, primitive, and Ravel-workspace
composition paths while recording time and allocation. `ExperimentalHalfFlowSuite`
proves that an otherwise valid candidate is rejected when the ledger is not
admissible and that numerical HalfFlow evidence cannot type-check as
`SmoothIso`.
