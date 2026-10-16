package reframe4s.halfflow

class SpatialChainOptionsSuite extends munit.FunSuite:
  test("spatial-chain development direction requires an explicit flag"):
    assert(!RealImageRegistration.parseOptions(Vector.empty).spatialChainGradient)
    assert(RealImageRegistration.parseOptions(Vector("--spatial-chain-gradient","true")).spatialChainGradient)
    assert(!RealImageRegistration.parseOptions(Vector("--spatial-chain-gradient","false")).spatialChainGradient)

  test("spatial-chain development flag cannot be duplicated or silently malformed"):
    intercept[IllegalArgumentException]:
      RealImageRegistration.parseOptions(Vector("--spatial-chain-gradient","true","--spatial-chain-gradient","false"))
    intercept[IllegalArgumentException]:
      RealImageRegistration.parseOptions(Vector("--spatial-chain-gradient","maybe"))
