# Validating the current source

Run all library modules on JVM and Scala.js, followed by the unpublished
benchmark runner checks:

```sh
sbt -J-Xmx4G -batch reframe4sTestAll reframe4sTestRunners
```

The build uses the immutable dependency revisions in `build.sbt`. The checked-in
CI environment uses Java 22 and Node.js 22. Each test command compiles its module
and dependencies before running tests; `root/compile` is also available for a
compile-only check.

## Test scheduling

`reframe4sTestJVM` and `reframe4sTestJS` enumerate every internal artifact from
`project/artifact-graph.tsv`, including experimental artifacts and the bundle.
They finish each module's tests before starting the next. `reframe4sTestAll`
runs those two platform commands in order:

```sh
sbt -J-Xmx4G -batch reframe4sTestJVM
sbt -J-Xmx4G -batch reframe4sTestJS
```

CI runs the two platforms on separate workers. This avoids contention between
the long numerical JVM and Scala.js tests. The repository-specific alias names
also avoid collisions with `testAll` and `compileAll` aliases in source
dependencies. Use these commands for full validation; an aggregate `root/test`
does not guarantee that different projects finish in sequence.

The BasinBridge B4 decomposition suite gives each native translation and each
oracle translation/lane its own test result. The 2/4/8/12 mm fixtures, grid sizes,
iteration budgets, expected topology failures, and five-minute per-test timeout
are preserved. A failure identifies the individual case instead of timing a
whole matrix as one test.

The small-strain optimizer suite also reports each fixed noise seed separately
from the omitted-mode distribution. All three noise seeds and all three omitted
mode amplitudes remain covered, including the comparison between the smallest
and largest omitted-mode errors. Its default per-test timeout is unchanged.

`reframe4sTestRunners` runs `flashalignBenchmarkJVM/test` and compiles
`halfflowBenchJVM`. It does not run performance benchmarks or establish timing
claims.

## Contracts and validation tools

Use checkouts matching the image4s revision in `build.sbt` and its pinned locus4s
revision for the ownership audit. CI checks out these exact revisions under
`.ci/`.

```sh
node scripts/verify-prd.mjs
node scripts/verify-build-graph.mjs
IMAGE4S_ROOT=/path/to/image4s LOCUS4S_ROOT=/path/to/locus4s \
  node scripts/verify-symbol-ownership.mjs
node --test scripts/*.test.mjs
python3 tools/registration/validate_real_world_registration_registry.py
```

The registry check validates retained receipts, hashes, ownership, and generated
documentation. It does not rerun the historical experiments. Optional acquired
data tests report a skip when their external fixture is absent; record those
skips alongside the test totals. Passing the regression suite does not establish
anatomical registration accuracy, ANTs parity, or benchmark superiority.
