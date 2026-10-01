# BasinBridge B5: one-factor fine-model follow-up

Mote issue: `sf-01ky5p21c338zz91n2k2hjj3yw`.

B4 supplied the trigger for B5: exact oracle correspondences reach zero
bridge residual at 2/4/8/12 mm, while the frozen `budget2x` HalfFlow stage is
safe only at 2/4 mm and rejects 8/12 mm at accumulated topology admission.
The first conditional branch therefore audits the rank-one step before any
boundary halo, midpoint action, or control retuning.

## Predeclared candidate

The frozen production step is pointwise energy-normalized:

```text
v_i = -s g_i / (lambda + ||g_i||^2 / (2 L + epsilon))
```

The B5 candidate treats the global mean-CC loss `L` as one scalar residual,
and makes the damping coefficient dimensionless using the median active
gradient norm `m`:

```text
v_i = -s sqrt(2 L + epsilon) g_i /
      (||g_i||^2 + lambda m^2)
```

No topology floor, support policy, matcher control, accepted-step budget,
integration depth, or export policy changes are allowed in this branch. The
candidate is an opt-in ablation and is removed if it does not widen the
oracle fine-stage basin.

## Frozen comparison

The comparison is the exact oracle-to-BasinBridge-to-HalfFlow lane at the
existing `budget2x` profile over the deterministic 2/4/8/12 mm translation
matrix. The bridge must remain accepted in every case; the decision variable
is whether the typed fine-stage topology failure at 8/12 mm disappears without
losing the 2/4 mm passes.

## Observed result

The JVM candidate run retained the bridge and produced:

| shift | fine stage | retained bridge-state minimum sampled determinant |
| ---: | --- | ---: |
| 2 mm | passed | 1.0 |
| 4 mm | passed | 0.9375 |
| 8 mm | typed accumulated-topology rejection | 0.1845703125 |
| 12 mm | typed accumulated-topology rejection | 0.028076171875 |

The candidate therefore does not widen the safe basin. It is rejected and the
frozen production rank-one law remains unchanged. The result is a mechanism
falsification, not a promotion or anatomical-accuracy claim. A Scala.js
candidate rerun is not required for retention because the candidate is
removed; the cross-platform gate remains attached to any future retained
accuracy change.

## Boundary and midpoint follow-up

The next two permitted one-factor checks were also run on the exact oracle
8/12 mm lane. A translated 16-voxel computational halo with the fixed
optimization support restricted to the 33^3 requested core did not widen the
fine basin. The existing `FixedAnchor` midpoint action likewise retained typed
accumulated-topology rejection at 8/12 mm. Neither changed topology floors or
the production default. Their JVM-only falsification and validator are
recorded in `docs/benchmarks/receipts/basinbridge-b5-followup-2026-08-02.json`
and `tools/registration/validate_basinbridge_b5_followup.py`; the experiment
is kept out of shared Scala.js tests because no candidate was retained.

No further accuracy factor is selected from these negative results. Any future
retained branch must use the same oracle matrix, preserve topology floors, and
carry JVM/Scala.js and performance evidence before promotion.
