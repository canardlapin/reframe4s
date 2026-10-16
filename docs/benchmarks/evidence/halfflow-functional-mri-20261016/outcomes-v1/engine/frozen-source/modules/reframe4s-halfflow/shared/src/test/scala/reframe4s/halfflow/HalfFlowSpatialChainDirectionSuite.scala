package reframe4s.halfflow

import gale.linalg.DMat
import image4s.geometry.{Affine, D3}
import reframe4s.halfflow.internal.*

class HalfFlowSpatialChainDirectionSuite extends munit.FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(120, "s")
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private val grid = GridSpec.identity(Vector(17,17,17))
  private val sourceGrid = GridSpec(Vector(29,29,29),DMat.dense(4,4,Vector(
    1.0,0,0,-5, 0,1.0,0,-5, 0,0,1.0,-5, 0,0,0,1.0)))
  private val work = RegistrationFrame[Work](SpatialDomainId("direction-work"),grid)
  private val ff = RegistrationFrame[Fixed](SpatialDomainId("direction-fixed"),sourceGrid)
  private val mf = RegistrationFrame[Moving](SpatialDomainId("direction-moving"),sourceGrid)
  private def image[A](frame: RegistrationFrame[A], shift: Double): RegistrationImage[A] =
    val values = Array.tabulate(sourceGrid.nVoxels): i =>
      val x = (i % 29).toDouble-shift; val y = ((i/29)%29).toDouble; val z = (i/(29*29)).toDouble
      math.sin(.31*x)+.7*math.cos(.27*y)+.4*math.sin(.23*(x+z))+.002*y*z
    right(RegistrationImage.make(frame,NeuroVol.fromLinear[Double](values,sourceGrid.toNeuroSpace,"direction-fixture")))
  private val fixed = image(ff,0.0)
  private val moving = image(mf,.7)
  private val transform = right(Affine.fromRowMajor[D3](Vector(
    1.0,0,0,.13, 0,1.0,0,.21, 0,0,1.0,.17, 0,0,0,1.0)))
  private val state = right(ForwardMidpoint.make(
    right(ForwardMidpointArm.make(DensePull.identity(work),right(AffineIso.make(work,ff,transform)))),
    right(ForwardMidpointArm.make(DensePull.identity(work),right(AffineIso.make(work,mf,transform))))))
  private val n = grid.nVoxels
  private val center = 8+17*(8+17*8)
  private def level(total: Double = 0.0, scaled: Boolean = false): HalfFlowCcLevel =
    right(HalfFlowCcLevel.make(1,0.0,smoothSigmaMm=1.0,maximumStepMm=.1,
      targetAcceptedSteps=1,maximumAttempts=6,accumulatedSmoothingMm=total,scaleAccumulatedSmoothingByStep=scaled))

  test("source-chain direction uses physical source derivatives for both actions"):
    val fd = new Array[Double](n);val md = new Array[Double](n)
    fd(center)=.7;md(center) = -.3
    val point = Vector(13.13,13.21,13.17)
    def expectedGradient(image: RegistrationImage[?]): Vector[Double] =
      // Independent central samples within one source cell differentiate the actual sampler.
      Vector.tabulate(3): c =>
        val plus = point.updated(c,point(c)+1e-5);val minus = point.updated(c,point(c)-1e-5)
        val a = HalfFlowSpatialChainProbe.sampleVoxelGradient(image.volume,image.validity,point).get._1(c)
        // The trilinear cell slope equals differences along its two enclosing faces.
        val lo = point.updated(c,math.floor(point(c)));val hi = lo.updated(c,lo(c)+1.0)
        def value(p: Vector[Double]): Double =
          val lower = p.map(math.floor(_).toInt)
          var sum = 0.0
          for dx <- 0 to 1;dy <- 0 to 1;dz <- 0 to 1 do
            val bits=Vector(dx,dy,dz)
            val weight=(0 until 3).map(k => if bits(k)==0 then 1.0-(p(k)-lower(k)) else p(k)-lower(k)).product
            if weight!=0.0 then sum += weight*image.volume.values(lower(0)+dx,lower(1)+dy,lower(2)+dz)
          sum
        val numerical=(value(plus)-value(minus))/(2e-5)
        assertEqualsDouble(a,value(hi)-value(lo),1e-12)
        numerical
    val fg=expectedGradient(fixed);val mg=expectedGradient(moving)
    for action <- Vector(HalfFlowCcAction.SymmetricMidpoint,HalfFlowCcAction.FixedAnchor) do
      val out = Array.fill(3*n)(Double.NaN);val valid = Array.fill(n)(false)
      right(HalfFlowSpatialChainDirection.into(fixed,moving,state,fd,md,action,Array.fill(n)(1.0),out,valid))
      for c <- 0 until 3 do
        val expected = if action==HalfFlowCcAction.SymmetricMidpoint then .5*(.7*fg(c)+.3*mg(c)) else .3*mg(c)
        assertEqualsDouble(out(center+c*n),expected,1e-10)
      assert(out.indices.filter(i => i%n!=center).forall(i => out(i)==0.0))

  test("unavailable boundary support fails without mutating output or inventing zero gradients"):
    val fd = new Array[Double](n);fd(0)=1.0
    val out=Array.fill(3*n)(7.0);val valid=Array.fill(n)(true)
    assert(HalfFlowSpatialChainDirection.into(fixed,moving,state,fd,new Array[Double](n),
      HalfFlowCcAction.SymmetricMidpoint,Array.fill(n)(1.0),out,valid).isLeft)
    assert(out.forall(_==7.0));assert(valid.forall(identity))

  test("a nonzero direction cannot silently disappear outside smoothing support"):
    val fd = new Array[Double](n);fd(center)=1.0
    val out=Array.fill(3*n)(7.0);val valid=Array.fill(n)(true)
    assert(HalfFlowSpatialChainDirection.into(fixed,moving,state,fd,new Array[Double](n),
      HalfFlowCcAction.SymmetricMidpoint,new Array[Double](n),out,valid).isLeft)
    assert(out.forall(_==7.0));assert(valid.forall(identity))

  test("experimental optimization accepts a real decreasing candidate and preserves observer neutrality"):
    val plan = right(HalfFlowCcPlan.make(Vector(level()),velocityBoundaryWidthMm=3.0))
    val plain = right(HalfFlowCc.optimizeSpatialChain(fixed,moving,state,plan))
    val observer = new HalfFlowCcObserver[Work,Fixed,Moving]:
      def wantsState(info: HalfFlowCcCheckpointInfo): Boolean = false
      def observe(checkpoint: HalfFlowCcCheckpoint[Work,Fixed,Moving]): Unit = ()
    val observed = right(HalfFlowCc.optimizeSpatialChain(fixed,moving,state,plan,
      Some(HalfFlowCcObservation(observer,0))))
    assertEquals(plain.diagnostics,observed.diagnostics)
    assertEquals(plain.diagnostics.acceptedSteps,1)
    assert(plain.diagnostics.levels.head.trace.exists(t => t.accepted && t.candidateLoss<t.currentLoss))
    for (a,b) <- Vector((plain.state.fixed.residual,observed.state.fixed.residual),
        (plain.state.moving.residual,observed.state.moving.residual));i <- 0 until n;c <- 0 until 3 do
      assertEqualsDouble(a.sourceCoordinates.linearComponent(i,c),b.sourceCoordinates.linearComponent(i,c),0.0)

  test("experimental optimization rejects discontinuous accumulated smoothing"):
    val plan = right(HalfFlowCcPlan.make(Vector(level(total=1.5))))
    assert(HalfFlowCc.optimizeSpatialChain(fixed,moving,state,plan).isLeft)
