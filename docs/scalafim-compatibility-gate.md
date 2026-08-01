# ScalaFIM compiler compatibility gate

ScalaFIM and the extracted foundations now use Scala 3.7.4. ScalaFIM commit
`6b5d4993d9acc59873e00eed5211b56728968a78` recorded the earlier clean compiler,
documentation, consumer-build, and browser-path baseline. At that baseline,
`compileAll`, `testAll`, and `examplesTest` passed across JVM and Scala.js, and
the standalone Zarr consumer compiled on both platforms.

This resolves the previous TASTy incompatibility. The historical experiment is
still relevant: Scala 3.4.2 could read stable TASTy only through 28.4 and could
not consume the pinned 3.7.4 Gale and Ravel line, which contains TASTy 28.7. An
adapter could not have repaired that boundary. Historical 3.4.2 benchmark
receipts and frozen baselines therefore remain unchanged.

The current image migration pins image4s directly at
`c1c9866eb61390de40d3ba110e040ee2a09f5f32`; ordinary builds do not inspect
sibling image4s or reframe4s checkouts. The image unification audit is clean
across the current Scala source tree, and ScalaFIM's image main sources compile
against that immutable revision. The aggregate `compileAll`, `testAll`, and
`examplesTest` gates pass, including 243 JVM and 232 Scala.js image tests and
the separately tracked graph/locus source-build cutover. The stale
`locus-laws` aggregate calls are gone, and the gate runs against immutable
Ravel, image4s, locus4s, and graph4s source revisions rather than sibling
checkout overrides or `publishLocal` results.

The next downstream gate is `MIG-410`: make `reframe4s-motion` the canonical
motion engine and replace ScalaFIM's duplicate algorithms with adapters.
`MIG-500` can complete only after that consumer matrix and the remaining
release gates pass.
