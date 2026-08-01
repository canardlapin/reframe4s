# HalfFlow-LM v1 independent fixtures

`half-flow-synthetic.json` is the implementation-independent oracle for the
first HalfFlow-LM contracts. The future `registration` crossProject should load
this file from shared tests so the same expectations run on JVM and Scala.js.

Generate it from the repository root:

```sh
python3 tools/registration/generate_halfflow_oracles.py
```

Verify that the checked-in file is current:

```sh
python3 tools/registration/generate_halfflow_oracles.py --check
```

The generator uses only the Python standard library. It does not call
ScalaFIM, Gale, ITK, ANTs, NumPy, or SciPy, so a shared implementation error
cannot silently define both the production result and the expected value.

## Contents

- identity, translation, and oblique-affine pull maps with closed-form
  inverses, determinants, samples, and round trips;
- two work-to-source affine maps and the expected fixed-to-moving,
  moving-to-fixed, and swapped midpoint results;
- an exact constant-velocity half-flow that detects missing or doubled `0.5`
  time factors;
- `exp(+0.5 v)` and `exp(-0.5 v)` samples for a smooth stationary velocity;
- flow Jacobian determinants from the independently integrated variational
  equation;
- both inverse-composition orders for every smooth-flow sample;
- gain-ratio trust decisions and damping updates; and
- separate attempted-step and accepted-step sequences.

The smooth-flow reference uses 4,096 classical RK4 steps for both the point
trajectory and the `dJ/dt = Dv(phi_t) J` variational equation. The checked-in
tolerances describe agreement with this reference integrator. Production
scaling-and-squaring also incurs grid interpolation error and therefore needs a
separate, resolution-calibrated tolerance and a refinement-convergence test.

Before writing or checking the fixture, the generator also verifies its
analytic velocity Jacobian by central differences, checks RK4 convergence at
2,048 versus 4,096 steps, checks the integrated variational Jacobian against
finite differences of the flow, and verifies both inverse-composition orders.

## Direction convention

All matrices and sampled fields are pull maps. A map `A -> B` accepts a point
in target frame `A` and returns sampling coordinates in source frame `B`.
Matrices are row-major 4x4 values applied to homogeneous column points.

`A >>> B` means `B(A(x))`, so its matrix is `B * A`.

Do not regenerate the file merely to make a failing Scala test pass. A fixture
change requires a contract change, a generator review, and a tracker decision
note.
