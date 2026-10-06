# Shared Spatial4s authority qualification — 2026-10-06

Baseline Reframe4s: `108028fb7127e71083bb46b5e4c033f831775c0e`.
Image4s: `ee4148c97dedb96918d979ff54db3b5110279771`, integrated by
[Image4s PR17](https://github.com/canardlapin/image4s/pull/17).
Spatial4s: `2eca2b379c1985881e4ca152b2a6c63a763277b0`, integrated by
[Spatial4s PR1](https://github.com/canardlapin/spatial4s/pull/1).
Gale: `85a8d12023b49598e5f39705fe8127cebbd9c014`.

Reframe4s uses the actual Spatial4s frames, points and dimensions through
Image4s compatibility aliases. Explicit module error companions accept either
Image4s geometry errors or Spatial4s errors and translate through
GeometryError.fromCoordinate. No implicit coordinate conversion is introduced.
Required Dimension witnesses are retained where shared alignment no longer
needs to read them. The authoritative graph and source-ownership verifier
include Spatial4s and reject competing Image4s coordinate owners.

Engineering results on macOS12.7.6 x86_64, Temurin21.0.12.1 and Node24.21.0:
all JVM module tests pass (537 tests). Full Scala.js modules compile and field,
flashalign, flow and graph tests pass; the full experimental registration run
is still in progress at initial PR publication. Direct ephys4s consumer probes
pass 24 tests per JVM/Scala.js and both examples using explicit isolated
snapshots. SharedSpatialAuthoritySuite adds owning typed map/owner/overflow
regressions. Validation of PRD, artifact graph, unique source symbols, Node
script tests and dataset unit tests passes. The registration inventory validator
passes under explicitly selected Python3.12.15; the machine's Python3.8 cannot
run that tool. The optional acquired-data registration suite is skipped because
its external Hodgeflow data is absent.

The provider has no scalafmt plugin, so no formatting task result is claimed.
Initial simultaneous composite builds raced Image4s's project/target; sequential
or isolated source builds corrected that build-cache collision. All local
cross-provider tests use explicit Image4s/Spatial4s overrides at the exact clean
integrated revisions above. CI uses immutable remote pins on JDK22 and Node22,
including full JVM/JS module tests, architecture checks and benchmark runners.

These are engineering regression checks. No ephys4s scientific workflow or
complete-workload performance qualification is claimed. Root Mote records:
ephys4s-provider-reframe-spatial-closure and ephys4s-0-spatial. Provider record:
reframe4s-ephys4s-spatial-closure.
