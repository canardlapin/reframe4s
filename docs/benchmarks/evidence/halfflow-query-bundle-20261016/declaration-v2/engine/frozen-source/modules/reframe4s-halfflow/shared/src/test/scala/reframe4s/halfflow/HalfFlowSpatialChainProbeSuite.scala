package reframe4s.halfflow

import gale.linalg.DMat
import image4s.geometry.{Affine, D3}
import reframe4s.halfflow.internal.*

class HalfFlowSpatialChainProbeSuite extends munit.FunSuite:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def volume(grid: GridSpec)(f: (Double, Double, Double) => Double): NeuroVol[Double] =
    val values = Array.tabulate(grid.nVoxels): i =>
      f(i % grid.extentX, (i / grid.extentX) % grid.extentY, i / (grid.extentX * grid.extentY))
    NeuroVol.fromLinear[Double](values, grid.toNeuroSpace, "spatial-chain-fixture")

  test("trilinear polynomial has its exact within-cell index derivative"):
    val grid = GridSpec.identity(Vector(9, 10, 11))
    val image = volume(grid)((x, y, z) => 2*x - 3*y + 0.7*z + 0.2*x*y - 0.1*x*z + 0.04*y*z + 0.03*x*y*z)
    val p = Vector(3.23, 4.42, 5.17)
    val (g, knot) = HalfFlowSpatialChainProbe.sampleVoxelGradient(image, FieldValidity.All, p).get
    val x = p(0); val y = p(1); val z = p(2)
    val expected = Vector(2 + 0.2*y - 0.1*z + 0.03*y*z,
      -3 + 0.2*x + 0.04*z + 0.03*x*z, 0.7 - 0.1*x + 0.04*y + 0.03*x*y)
    assert(!knot)
    for c <- 0 until 3 do assertEqualsDouble(g(c), expected(c), 1e-12)

  test("source-grid knots use the counted symmetric slope, not a smooth-image derivative"):
    val grid = GridSpec.identity(Vector(9, 9, 9))
    val image = volume(grid)((x, y, z) => x*x + 2*y*y - z*z)
    val (between, noKnot) = HalfFlowSpatialChainProbe.sampleVoxelGradient(image, FieldValidity.All,
      Vector(3.2, 4.3, 5.4)).get
    for c <- 0 until 3 do assertEqualsDouble(between(c), Vector(7.0, 18.0, -11.0)(c), 1e-12)
    assert(!noKnot)
    val (atKnot, knot) = HalfFlowSpatialChainProbe.sampleVoxelGradient(image, FieldValidity.All,
      Vector(3.0, 4.0, 5.0)).get
    assertEquals(atKnot, Vector(6.0, 16.0, -10.0))
    assert(knot)

  test("missing derivative support remains unavailable; zero-weight corners do not invalidate it"):
    val grid = GridSpec.identity(Vector(9, 9, 9))
    val image = volume(grid)((x, y, z) => x + y + z)
    val valid = Array.fill(grid.nVoxels)(true)
    valid(4 + 9 * (4 + 9 * 4)) = false
    assert(HalfFlowSpatialChainProbe.sampleVoxelGradient(image, FieldValidity.copyMask(valid),
      Vector(3.2, 3.0, 3.0)).nonEmpty)
    assert(HalfFlowSpatialChainProbe.sampleVoxelGradient(image, FieldValidity.copyMask(valid),
      Vector(3.2, 3.2, 3.2)).isEmpty)
    assert(HalfFlowSpatialChainProbe.sampleVoxelGradient(image, FieldValidity.All, Vector(0.0, 3.2, 3.2)).isEmpty)
    assert(HalfFlowSpatialChainProbe.sampleVoxelGradient(image, FieldValidity.All, Vector(Double.NaN, 3.2, 3.2)).isEmpty)

  test("full physical chain matches actual paired composition on mixed oblique grids and both actions"):
    def matrix(values: Double*): DMat = DMat.dense(4, 4, values.toVector)
    val grid = GridSpec(Vector(13,13,13), matrix(1.2,.2,0,4, 0,1.1,.1,3, .1,0,.9,2, 0,0,0,1))
    val source = GridSpec(Vector(33,33,33), matrix(.8,.1,0,-1, .1,.9,.1,-2, 0,.1,1.1,-3, 0,0,0,1))
    val wf = RegistrationFrame[Work](SpatialDomainId("chain-work"),grid)
    val ff = RegistrationFrame[Fixed](SpatialDomainId("chain-fixed"),source)
    val mf = RegistrationFrame[Moving](SpatialDomainId("chain-moving"),source)
    val fixed = right(RegistrationImage.make(ff,volume(source)((x,y,z) => math.sin(.41*x)+math.cos(.37*y)+.02*x*z)))
    val moving = right(RegistrationImage.make(mf,volume(source)((x,y,z) => math.cos(.31*x)+math.sin(.43*y)-.01*y*z)))
    def arm[E](endpoint: RegistrationFrame[E], sign: Double): ForwardMidpointArm[Work,E] =
      val coordinates = HalfFlowKernels.identity(grid).field.copyLegacyPlanar
      for i <- 0 until grid.nVoxels do
        val x = coordinates(i)
        val y = coordinates(i+grid.nVoxels)
        val z = coordinates(i+2*grid.nVoxels)
        coordinates(i) += sign * .2 * math.sin(.3*y)
        coordinates(i+grid.nVoxels) += sign * .15 * math.cos(.2*z)
        coordinates(i+2*grid.nVoxels) += sign * .1 * math.sin(.4*x)
      val residual = right(DensePull.make(wf,wf,
        DenseVectorField.fromLegacyPlanar(grid,coordinates,DenseVectorFieldKind.SourceCoordinates)))
      val affine = right(Affine.fromRowMajor[D3](Vector(1.01,.03,0,.23*sign, 0,.98,.02,-.17*sign,
        .01,0,1.02,.31*sign, 0,0,0,1)))
      right(ForwardMidpointArm.make(residual,right(AffineIso.make(wf,endpoint,affine))))
    val state = right(ForwardMidpoint.make(arm(ff,1.0),arm(mf,-1.0)))
    val n = grid.nVoxels
    val at = 6 + 13*(6+13*6)
    val fd = new Array[Double](n); fd(at) = .7
    val md = new Array[Double](n); md(at) = -.3
    def sample[E](image: RegistrationImage[E], arm: ForwardMidpointArm[Work,E]): Double =
      val values = new Array[Double](n)
      val valid = new Array[Boolean](n)
      HalfFlowKernels.pullScalarAffineInto(image.volume,arm.residual.sourceCoordinates,arm.affine.transform.matrix,
        values,valid,DenseFieldSampler(source),arm.residual.validity,image.validity,0.0)
      assert(valid(at))
      values(at)
    for action <- Vector(HalfFlowCcAction.SymmetricMidpoint,HalfFlowCcAction.FixedAnchor) do
      val result = HalfFlowSpatialChainProbe(fixed,moving,state,fd,md,action)
      assertEquals(result.fixed.unavailable+result.moving.unavailable,0)
      assertEquals(result.fixed.interpolationKnotVoxels+result.moving.interpolationKnotVoxels,0)
      for direction <- Vector(Vector(.7,-.2,.1),Vector(-.3,.4,.6)) do
        val analytic = (0 until 3).map(c => result.gradient(at+c*n)*direction(c)).sum
        def value(epsilon: Double): Double =
          val scale = if action == HalfFlowCcAction.FixedAnchor then 2.0 else 1.0
          val field = Array.tabulate(3*n)(i => direction(i/n)*epsilon*scale)
          val velocity = right(Velocity.make(wf,DenseVectorField.fromLegacyPlanar(grid,field,DenseVectorFieldKind.Displacement)))
          val pair = right(PairedScalingAndSquaring.expHalfPair(velocity))
          val half = if action == HalfFlowCcAction.SymmetricMidpoint then HalfStep.fromPairedFlow(pair)
            else right(HalfStep.make(DensePull.identity(wf),pair.pair.backward))
          val next = right(state.advance(half))
          fd(at)*sample(fixed,next.fixed)+md(at)*sample(moving,next.moving)
        val epsilon = 1e-6
        val numerical = (value(epsilon)-value(-epsilon))/(2*epsilon)
        assertEqualsDouble(analytic,numerical,1e-7)
