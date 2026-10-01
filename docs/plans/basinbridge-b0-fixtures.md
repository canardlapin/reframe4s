# BasinBridge B0: frozen correspondence fixtures

Mote issue: `sf-01ky5p1xx2k8abtnqp9vdxhdhq`.

B0 freezes the coordinate convention and the three diagnostic lanes before a
matcher or projector is changed. The checked-in synthetic fixture is an
independent contract, not an anatomical result and not a claim about ANTs.

## Coordinate contract

All points are physical-coordinate pull-map points. `q` is a fixed-target
point, `p` is the moving-source point that should be sampled at that target.
The bridge receives:

```text
z = (p + q) / 2
t = p - q
```

This makes a moving image defined as `moving(x) = fixed(x - shift)` produce
the positive pull tangent `t = shift`. A direction swap must preserve the
midpoint and negate the tangent.

Seeds are spaced every 8 mm on a 49^3, 1 mm isotropic physical grid. The
fixture contains constant translation, rotation, affine background, regional
residual, outlier, and boundary-support cases. Every case has 125 deterministic
known-transform correspondences.

## Frozen lanes

1. `oracle-to-bridge`: known-transform or same-affine-ANTs correspondences
   enter BasinBridge without fine refinement.
2. `oracle-to-bridge-to-halfflow`: the same correspondences enter the bridge
   and then the frozen HalfFlow `budget2x` local-refinement profile.
3. `block-to-bridge-to-halfflow`: current block-search correspondences enter
   the same bridge and the same frozen HalfFlow profile.

The lanes use independent native fixed and moving pyramids, one supplied
affine, and the fixed mask as optimization support. Moving masks and labels
are evaluation-only. The anatomical same-affine-ANTs input remains an explicit
user-provided licensed artifact; it is not present in this repository.

## Measurements

Each later lane receipt must report weighted match-error percentiles, true CC
loss, labels or surface distance when available, TRE when available, sampled
determinants and fold counts, export-inverse errors, support coverage, accepted
bridge fractions and rematch rounds, rejection reasons, runtime, allocations,
and peak memory. The first decisive decomposition is oracle -> bridge ->
HalfFlow versus block -> bridge -> HalfFlow; no parameter retuning is admitted
until those causes are separated.

Generate or verify the fixture from the repository root:

```sh
python3 tools/registration/generate_basinbridge_fixtures.py
python3 tools/registration/generate_basinbridge_fixtures.py --check
```

The fixture generator uses only the Python standard library and rejects
non-finite values, altered seed order, incorrect midpoint/tangent arithmetic,
confidence values outside `[0, 1]`, and any moving-mask optimization role.
