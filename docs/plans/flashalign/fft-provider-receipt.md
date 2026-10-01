# Flashalign FFT provider admission receipt

Date: 2026-09-12

Mote work item: `bd-01M2B1168QAVESSMFB8A2H0PEX` (FA-L15)

## Decision

Structural capture uses the repository-owned `reframe4s-spectral` artifact. It
is a separate internal-experimental provider consumed by Flashalign through the
declared artifact graph. FFT and correlation code do not live in the
Flashalign package, and no HalfFlow dependency is introduced.

The provider is cross-built from the same Scala 3 source for JVM and Scala.js.
It is covered by this repository's `Apache-2.0` build license declaration. No
third-party FFT binary, native library, platform-specific implementation, or
mutable upstream branch is part of this admission.

## Immutable source identity

The admitted provider and its independent numerical oracles have these SHA-256
identities:

| File | SHA-256 |
|---|---|
| `Radix2Fft.scala` | `8a61fbfda15490541b784f9ddb5e3026a80df3b0385e484b7cb0f59a0f2b0cef` |
| `LinearCorrelation3.scala` | `b3fa934f05c64c43147629ed7723b574ac35da5438fbedc853bf91f5e5d75e7f` |
| `Radix2FftSuite.scala` | `0f344b0bf58dd8580849d900996b116d536689ba8530efac6426532b71b553f6` |
| `LinearCorrelation3Suite.scala` | `0a91e6fced3fb8adffed66434c13dfdf013f49f9d97e35ff194d5dd7640536d7` |

Any source change invalidates these hashes and requires the provider and
Flashalign consumer gates below to be rerun.

## Exact conventions

`Radix2FftPlan` accepts complex arrays whose length is a power of two greater
than one. The forward transform uses the negative exponential and is unscaled;
the inverse uses the positive exponential and applies `1/N`. Immutable plans
own bit-reversal and twiddle tables. Mutable scratch storage belongs to an
explicit plan-specific workspace, rejects concurrent use, and performs no
scratch-array allocation during a transform.

`LinearCorrelation3Plan` computes the zero-padded linear correlation

```text
sum left(i,j,k) * right(i + lagX, j + lagY, k + lagZ)
```

over valid overlap. Lags span `[-leftExtent + 1, rightExtent - 1]` on each
axis. The provider pads each axis independently to the next radix-two extent at
least as large as `leftExtent + rightExtent - 1`; it does not expose circular
wraparound as a linear translation peak. One inverse scaling is applied per
axis by the one-dimensional inverse transforms, with no extra three-dimensional
normalization.

Plans reject invalid extents, size overflow, invalid array lengths, non-finite
inputs, out-of-domain lags, foreign workspaces, and active workspace reuse with
typed `SpectralError` values. The output and workspace are caller-owned.

## Numerical qualification

The radix-two FFT was compared with a direct complex DFT at sizes 2, 4, 8, and
16. The direct tolerance was `2e-12`; complex forward/inverse round trips used
`8e-16`.

The three-dimensional provider was compared at every valid lag with a direct
spatial correlation oracle for mixed odd/even left and right shapes. The
tolerance was `2e-11`. A separated impulse case required a `16 x 2 x 2` padded
shape and verified the true lag peak without circular leakage.

Commands and outcomes:

```text
sbt -J-Xmx4G -batch 'reframe4s-spectralJVM/test' 'reframe4s-spectralJS/test'
JVM: 6 passed, 0 failed
Scala.js: 6 passed, 0 failed

sbt -J-Xmx4G -batch 'reframe4s-flashalignJVM/test' 'reframe4s-flashalignJS/test'
JVM: 57 passed, 0 failed
Scala.js: 57 passed, 0 failed

node scripts/verify-prd.mjs
148 IDs, 28 nodes, 95 edges, acyclic

node scripts/verify-build-graph.mjs
build graph matches PRD: 28 nodes, 95 edges

node scripts/verify-symbol-ownership.mjs
1206 public names checked; 66 canonical owners verified
```

These checks qualify conventions, deterministic implementation behavior, graph
ownership, and cross-platform consumer compatibility. They do not establish
capture accuracy or runtime performance. FA-L16 must validate the structural
capture method against explicit spatial-domain translation oracles and bounded
rotation fixtures before automatic capture can be released.
