package reframe4s.halfflow

import gale.linalg.DMat
import image4s.geometry.{Affine, D3}
import reframe4s.halfflow.internal.*

/** Coordinate identities for the actual sampled objective, not an optimizer trial. */
class HalfFlowCcEquivarianceSuite extends munit.FunSuite:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def matrix(values: Double*): DMat = DMat.dense(4, 4, values.toVector)
  private def point(a: DMat, x: Vector[Double]): Vector[Double] =
    Vector.tabulate(3)(r => a(r, 3) + (0 until 3).map(c => a(r, c) * x(c)).sum)
  private def affine(a: DMat): Affine[D3] =
    right(Affine.fromRowMajor[D3](Vector.tabulate(16)(i => a(i / 4, i % 4))))
  private val level = right(HalfFlowCcLevel.make(1, 0.0, smoothSigmaMm = 2.0,
    maximumStepMm = 0.2, targetAcceptedSteps = 1, maximumAttempts = 2))
  private def plan(action: HalfFlowCcAction): HalfFlowCcPlan =
    right(HalfFlowCcPlan.make(Vector(level), action = action))

  private final case class Fixture(fixed: RegistrationImage[Fixed], moving: RegistrationImage[Moving],
      state: ForwardMidpoint[Work, Fixed, Moving])

  private def fixture(change: DMat = DMat.eye(4)): Fixture =
    val inverseChange = affine(change).inverse.matrix
    val workBase = GridSpec(Vector(17,19,15), matrix(1.2,.2,0,4, 0,1.1,.1,3, .1,0,.9,2, 0,0,0,1))
    val fixedBase = GridSpec(Vector(37,39,35), matrix(.9,.1,0,-4, 0,.85,.05,-3, .04,0,1.0,-4, 0,0,0,1))
    val movingBase = GridSpec(Vector(41,35,37), matrix(.85,0,.1,-3, .08,1.05,0,-4, 0,.06,.9,-3, 0,0,0,1))
    val grid = GridSpec(workBase.shape, change * workBase.affine)
    val fixedGrid = GridSpec(fixedBase.shape, change * fixedBase.affine)
    val movingGrid = GridSpec(movingBase.shape, change * movingBase.affine)
    val work = RegistrationFrame[Work](SpatialDomainId("equivariance-work"), grid)
    val ff = RegistrationFrame[Fixed](SpatialDomainId("equivariance-fixed"), fixedGrid)
    val mf = RegistrationFrame[Moving](SpatialDomainId("equivariance-moving"), movingGrid)
    def image[A](frame: RegistrationFrame[A], base: GridSpec, shift: Double): RegistrationImage[A] =
      val values = Array.tabulate(base.nVoxels): i =>
        val p = base.voxelToWorld(Vector((i % base.extentX).toDouble,
          ((i / base.extentX) % base.extentY).toDouble, (i / (base.extentX * base.extentY)).toDouble))
        val x = p(0) - shift; val y = p(1); val z = p(2)
        math.sin(.31*x) + math.cos(.27*y) + .4*math.sin(.23*(x+z)) + .01*y*z
      right(RegistrationImage.make(frame, NeuroVol.fromLinear[Double](values, frame.grid.toNeuroSpace, "coordinate-fixture")))
    def arm[A](endpoint: RegistrationFrame[A], sign: Double): ForwardMidpointArm[Work,A] =
      val coordinates = new Array[Double](3 * grid.nVoxels)
      for i <- 0 until grid.nVoxels do
        val p = workBase.voxelToWorld(Vector((i % grid.extentX).toDouble,
          ((i / grid.extentX) % grid.extentY).toDouble, (i / (grid.extentX * grid.extentY)).toDouble))
        val next = point(change, Vector(p(0)+sign*.2*math.sin(.3*p(1)),
          p(1)+sign*.15*math.cos(.2*p(2)), p(2)+sign*.1*math.sin(.4*p(0))))
        for c <- 0 until 3 do coordinates(i+c*grid.nVoxels) = next(c)
      val residual = right(DensePull.make(work,work,
        DenseVectorField.fromLegacyPlanar(grid,coordinates,DenseVectorFieldKind.SourceCoordinates)))
      val a = matrix(1.01,.03,0,.23*sign, 0,.98,.02,-.17*sign, .01,0,1.02,.31*sign, 0,0,0,1)
      right(ForwardMidpointArm.make(residual, right(AffineIso.make(work,endpoint,affine(change*a*inverseChange)))))
    Fixture(image(ff,fixedBase,0.0), image(mf,movingBase,.7), right(ForwardMidpoint.make(arm(ff,1.0),arm(mf,-1.0))))

  private def direction(grid: GridSpec): Array[Double] =
    val n = grid.nVoxels
    val values = Array.tabulate(3*n): i =>
      val at = i % n
      val x = at % grid.extentX; val y = (at / grid.extentX) % grid.extentY
      val z = at / (grid.extentX * grid.extentY)
      Vector(.7+.1*math.sin(.3*y), -.2+.1*math.cos(.2*z), .1+.05*math.sin(.4*x))(i/n)
    VelocityBoundaryTaper.inPlace(values,grid,3.5)
    values

  private def transformed(values: Array[Double], change: DMat): Array[Double] =
    val n = values.length / 3
    Array.tabulate(values.length)(i => (0 until 3).map(c => change(i/n,c)*values(i%n+c*n)).sum)

  private def advance[F,M](state: ForwardMidpoint[Work,F,M], values: Array[Double], epsilon: Double,
      action: HalfFlowCcAction): ForwardMidpoint[Work,F,M] =
    val scale = if action == HalfFlowCcAction.FixedAnchor then 2.0 else 1.0
    val velocity = right(Velocity.make(state.work, DenseVectorField.fromLegacyPlanar(state.work.grid,
      values.map(_ * epsilon * scale), DenseVectorFieldKind.Displacement)))
    val pair = right(PairedScalingAndSquaring.expHalfPair(velocity))
    val half = if action == HalfFlowCcAction.SymmetricMidpoint then HalfStep.fromPairedFlow(pair)
      else right(HalfStep.make(DensePull.identity(state.work),pair.pair.backward))
    right(state.advance(half))

  private def complete(result: HalfFlowSpatialChainResult): Unit =
    for summary <- Vector(result.fixed,result.moving) do
      assertEquals(summary.unavailable,0)
      assertEquals(summary.evaluated,summary.nonzeroIntensityDerivatives)
    assert(result.fixed.evaluated + result.moving.evaluated > 0)

  test("swapping unequal image grids and arms reverses the symmetric derivative and candidate action"):
    val f = fixture()
    val p = plan(HalfFlowCcAction.SymmetricMidpoint)
    val original = right(HalfFlowCc.probeObjectiveAtLevel(f.fixed,f.moving,f.state,level,p))
    val reversedState = right(ForwardMidpoint.make(f.state.moving,f.state.fixed))
    val reversed = right(HalfFlowCc.probeObjectiveAtLevel(f.moving,f.fixed,reversedState,level,p))
    assertEqualsDouble(original.audit.loss,reversed.audit.loss,1e-12)
    val a = original.spatialChain.get; val b = reversed.spatialChain.get
    complete(a); complete(b)
    assertEquals(a.fixed,b.moving); assertEquals(a.moving,b.fixed)
    for i <- a.gradient.indices do assertEqualsDouble(a.gradient(i),-b.gradient(i),1e-11)
    val v = direction(f.state.work.grid)
    for epsilon <- Vector(-.01,0.0,.01) do
      val left = right(original.value(advance(f.state,v,epsilon,p.action)))
      val rightValue = right(reversed.value(advance(reversedState,v,-epsilon,p.action)))
      assertEqualsDouble(left,rightValue,1e-12)

  test("rotating or reflecting physical coordinates preserves objective values and transforms spatial covectors"):
    val f = fixture()
    val v = direction(f.state.work.grid)
    val changes = Vector(matrix(0,-1,0,7, 1,0,0,-5, 0,0,1,3, 0,0,0,1),
      matrix(0,1,0,-2, 1,0,0,4, 0,0,1,6, 0,0,0,1))
    for action <- Vector(HalfFlowCcAction.SymmetricMidpoint,HalfFlowCcAction.FixedAnchor) do
      val p = plan(action)
      val original = right(HalfFlowCc.probeObjectiveAtLevel(f.fixed,f.moving,f.state,level,p))
      complete(original.spatialChain.get)
      for change <- changes do
        val g = fixture(change)
        val probe = right(HalfFlowCc.probeObjectiveAtLevel(g.fixed,g.moving,g.state,level,p))
        complete(probe.spatialChain.get)
        assertEqualsDouble(probe.audit.loss,original.audit.loss,1e-11)
        val expected = transformed(original.spatialChain.get.gradient,change)
        for i <- expected.indices do assertEqualsDouble(probe.spatialChain.get.gradient(i),expected(i),1e-10)
        val nextDirection = transformed(v,change)
        for epsilon <- Vector(-.01,.01) do
          assertEqualsDouble(right(probe.value(advance(g.state,nextDirection,epsilon,action))),
            right(original.value(advance(f.state,v,epsilon,action))),1e-11)

  test("full frozen CC derivative agrees with actual composition on unequal oblique grids for both actions"):
    val f = fixture()
    val v = direction(f.state.work.grid)
    for action <- Vector(HalfFlowCcAction.SymmetricMidpoint,HalfFlowCcAction.FixedAnchor) do
      val probe = right(HalfFlowCc.probeObjectiveAtLevel(f.fixed,f.moving,f.state,level,plan(action)))
      val chain = probe.spatialChain.get
      complete(chain)
      assertEquals(chain.fixed.interpolationKnotVoxels+chain.moving.interpolationKnotVoxels,0)
      val analytic = chain.gradient.indices.map(i => chain.gradient(i)*v(i)).sum
      assert(math.abs(analytic)>1e-8)
      val errors = Vector(.01,.005,.002,.001,.0005,.0002,.0001).map: epsilon =>
        val plus = right(probe.value(advance(f.state,v,epsilon,action)))
        val minus = right(probe.value(advance(f.state,v,-epsilon,action)))
        val numerical = (plus-minus)/(2*epsilon)
        math.abs(numerical-analytic)/math.max(1e-12,math.abs(numerical)+math.abs(analytic))
      assert(errors.min<=.005,s"$action: $errors")
      assert(errors.sliding(3).exists(e => e(2)<e(1) && e(1)<e(0)),s"$action: $errors")
      assertEqualsDouble(right(probe.value(f.state)),probe.audit.loss,0.0)

  test("active boundary derivatives remain unavailable instead of silently becoming valid zeros"):
    val f = fixture()
    val n = f.state.work.grid.nVoxels
    val fixedDerivative = new Array[Double](n)
    val movingDerivative = new Array[Double](n)
    fixedDerivative(0)=.7; movingDerivative(n-1) = -.3
    val result = HalfFlowSpatialChainProbe(f.fixed,f.moving,f.state,fixedDerivative,movingDerivative,
      HalfFlowCcAction.SymmetricMidpoint)
    assertEquals(result.fixed,SpatialChainArmSummary(1,0,1,0))
    assertEquals(result.moving,SpatialChainArmSummary(1,0,1,0))
    assert(result.gradient.forall(_==0.0))
