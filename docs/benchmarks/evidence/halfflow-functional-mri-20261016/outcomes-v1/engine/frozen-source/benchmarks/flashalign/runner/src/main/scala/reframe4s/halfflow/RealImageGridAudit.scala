package reframe4s.halfflow

import gale.linalg.DMat
import image4s.{ContinuousImage, SampleSpace}
import image4s.geometry.{Affine, D3}
import image4s.geometry.Frame as CanonicalFrame
import image4s.nifti.Nifti
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import ravel.Rank
import reframe4s.halfflow.internal.*

/** Exact voxel reindexing of prepared source images. The physical samples,
  * masks, work lattice and residual/affine maps remain the same. No fitting.
  */
object RealImageGridAudit:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving
  private val lossTolerance = 1e-12
  private val gradientAbsoluteTolerance = 1e-11
  private val gradientRelativeTolerance = 1e-10
  private val epsilons = Vector(.01,.0001)
  private def right[E,A](value: Either[E,A]): A =
    value.fold(error => throw new IllegalArgumentException(error.toString),identity)

  private[halfflow] def reindex[A](image: RegistrationImage[A], axes: Vector[Int],
      signs: Vector[Int]): RegistrationImage[A] =
    require(axes.sorted==Vector(0,1,2) && signs.length==3 && signs.forall(s => s==1 || s == -1))
    val old = image.frame.grid
    val shape = axes.map(old.shape)
    // P maps new voxel coordinates to old voxel coordinates, including flips.
    val p = DMat.dense(4,4,Vector.tabulate(16): i =>
      val r=i/4; val c=i%4
      if r==3 then (if c==3 then 1.0 else 0.0)
      else if c==3 then (if signs(axes.indexOf(r)) == -1 then old.shape(r)-1.0 else 0.0)
      else if axes(c)==r then signs(c).toDouble else 0.0)
    val matrix = old.affine*p
    val geometry = right(Affine.fromRowMajor[D3](Vector.tabulate(16)(i => matrix(i/4,i%4))))
    val grid = old.withGeometry(shape,geometry)
    val values = new Array[Double](grid.nVoxels)
    val mask = new Array[Boolean](grid.nVoxels)
    for i <- 0 until grid.nVoxels do
      val next = Vector(i%shape(0),(i/shape(0))%shape(1),i/(shape(0)*shape(1)))
      val prior = Array.ofDim[Int](3)
      for c <- 0 until 3 do
        prior(axes(c)) = if signs(c)==1 then next(c) else old.shape(axes(c))-1-next(c)
      val j = prior(0)+old.extentX*(prior(1)+old.extentY*prior(2))
      values(i)=image.volume.linear(j);mask(i)=image.validity.contains(j)
    val validity = image.validity match
      case FieldValidity.All => FieldValidity.All
      case FieldValidity.Mask(_) => FieldValidity.copyMask(mask)
    right(RegistrationImage.make(RegistrationFrame[A](image.frame.domain,grid),
      NeuroVol.fromLinear[Double](values,grid,"reindexed-source"),validity))

  def main(args: Array[String]): Unit =
    require(args.length==7 || (args.length==8 && args(7)=="intensity-only"),
      "moving fixed fixed-mask checkpoint output expected-loss level-index [intensity-only]")
    val moving = right(Nifti.readScaledDouble(Paths.get(args(0))))
    val fixed = right(Nifti.readScaledDouble(Paths.get(args(1))))
    moving.image.fold(_ => throw new IllegalArgumentException("scalar D3 moving required"),
      m => fixed.image.fold(_ => throw new IllegalArgumentException("scalar D3 fixed required"),
        f => run(right(m.value.requireDataRank[3]),right(f.value.requireDataRank[3]),
          Paths.get(args(2)),Paths.get(args(3)),Paths.get(args(4)),args(5).toDouble,args(6).toInt,args.length==8)))

  private def run[M <: CanonicalFrame[D3],F <: CanonicalFrame[D3],
      MS <: SampleSpace[M,D3],FS <: SampleSpace[F,D3]](
      moving: ContinuousImage[MS,Double,Rank[3]],fixed: ContinuousImage[FS,Double,Rank[3]],
      maskPath: Path,checkpoint: Path,output: Path,expectedLoss: Double,levelIndex: Int,intensityOnly: Boolean
  ): Unit =
    require(!Files.exists(output),"refusing to overwrite grid audit")
    require(expectedLoss.isFinite)
    val fg = GridSpec.fromGrid(fixed.grid); val mg = GridSpec.fromGrid(moving.grid)
    val fi = right(RegistrationImage.make(RegistrationFrame[Fixed](SpatialDomainId("grid-fixed"),fg),
      NeuroVol.fromRavel(fixed.data,fg,"fixed"),FieldValidity.copyMask(RealImageRegistration.loadMask(maskPath,fg))))
    val mi = right(RegistrationImage.make(RegistrationFrame[Moving](SpatialDomainId("grid-moving"),mg),
      NeuroVol.fromRavel(moving.data,mg,"moving")))
    val plan = RealImageRegistration.finePlan(50,1e-3,6.0,20,6.0,1.5)
    require(levelIndex>=0 && levelIndex<plan.levels.length)
    val level = plan.levels(levelIndex)
    val (fixedLevel,movingLevel) = right(HalfFlowCc.prepareProbeLevel(fi,mi,level))
    val grid = HalfFlowKernels.pyramidGrid(fg,level.shrink)
    val work = RegistrationFrame[Work](SpatialDomainId("grid-work"),grid)
    def stateFor(f: RegistrationImage[Fixed],m: RegistrationImage[Moving]): ForwardMidpoint[Work,Fixed,Moving] =
      right(ForwardMidpoint.make(RealImageObjectiveAudit.loadArm(checkpoint,"fixed",work,f.frame),
        RealImageObjectiveAudit.loadArm(checkpoint,"moving",work,m.frame)))
    val state = stateFor(fixedLevel,movingLevel)
    val original = right(HalfFlowCc.probeObjectiveAtLevel(fixedLevel,movingLevel,state,level,plan))
    require(math.abs(original.audit.loss-expectedLoss)<=lossTolerance,"captured objective differs")
    val spatial = original.spatialChain.get
    Files.createDirectories(output)
    write(output.resolve("initial.json"),Map("original_audit"->original.audit,
      "expected_loss"->expectedLoss,"level_index"->levelIndex,"shrink"->level.shrink,
      "work_shape"->grid.shape,"fixed_shape"->fixedLevel.frame.grid.shape,"moving_shape"->movingLevel.frame.grid.shape,
      "fixed_support"->spatial.fixed,"moving_support"->spatial.moving))
    val n=grid.nVoxels
    val maximum=(0 until n).map(i => math.sqrt((0 until 3).map(c => math.pow(original.gradient(i+c*n),2)).sum)).max
    require(maximum.isFinite && maximum>0.0)
    val directions=Vector("translation","smooth-wave","negative-gradient").map: name =>
      val v=Array.tabulate(3*n): i =>
        val at=i%n; val x=at%grid.extentX;val y=(at/grid.extentX)%grid.extentY;val z=at/(grid.extentX*grid.extentY)
        val wave=math.sin(2*math.Pi*x/(grid.extentX-1))*math.sin(math.Pi*y/(grid.extentY-1))*math.sin(math.Pi*z/(grid.extentZ-1))
        name match
          case "translation" => Vector(.73,-.21,.16)(i/n)
          case "smooth-wave" => wave*Vector(.73,-.21,.16)(i/n)
          case _ => -original.gradient(i)/maximum
      VelocityBoundaryTaper.inPlace(v,grid,plan.velocityBoundaryWidthMm)
      name->v
    def candidate(s: ForwardMidpoint[Work,Fixed,Moving],v: Array[Double],scale: Double): ForwardMidpoint[Work,Fixed,Moving] =
      val velocity=right(Velocity.make(work,DenseVectorField.fromLegacyPlanar(grid,v.map(_*scale),DenseVectorFieldKind.Displacement)))
      right(s.advance(HalfStep.fromPairedFlow(right(PairedScalingAndSquaring.expHalfPair(velocity)))))
    val candidateDirections=if intensityOnly then Vector.empty[(String,Array[Double])] else directions
    val baseline=(for (name,v) <- candidateDirections; e <- epsilons; sign <- Vector(-1.0,1.0)
      yield (name,e,sign)->right(original.value(candidate(state,v,e*sign)))).toMap
    val variants=Vector("fixed-reindexed","moving-reindexed","both-reindexed")
    val results=variants.map: name =>
      val f=if name=="moving-reindexed" then fixedLevel else reindex(fixedLevel,Vector(2,0,1),Vector(-1,1,1))
      val m=if name=="fixed-reindexed" then movingLevel else reindex(movingLevel,Vector(1,0,2),Vector(1,-1,1))
      val nextState=stateFor(f,m)
      val probe=right(HalfFlowCc.probeObjectiveAtLevel(f,m,nextState,level,plan))
      val chain=probe.spatialChain.get
      val production=agreement(original.gradient,probe.gradient)
      val spatialAgreement=agreement(spatial.gradient,chain.gradient)
      val supportExact=spatial.fixed==chain.fixed && spatial.moving==chain.moving
      val complete=Vector(spatial.fixed,spatial.moving,chain.fixed,chain.moving).forall(s =>
        s.unavailable==0 && s.evaluated==s.nonzeroIntensityDerivatives && s.evaluated>0)
      val validityExact=original.gradientValid.sameElements(probe.gradientValid)
      val rows=for (direction,v) <- candidateDirections; e <- epsilons; sign <- Vector(-1.0,1.0) yield
        val a=baseline((direction,e,sign));val b=right(probe.value(candidate(nextState,v,e*sign)))
        require(a.isFinite && b.isFinite)
        val row=Map[String,Any]("variant"->name,"direction"->direction,"epsilon_mm"->e,"sign"->sign,
          "original_loss"->a,"reindexed_loss"->b,"absolute_difference"->math.abs(a-b),"passed"->(math.abs(a-b)<=lossTolerance))
        Files.writeString(output.resolve("curve.jsonl"),RealImageCheckpointJson.encode(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND)
        row
      val initialDifference=math.abs(original.audit.loss-probe.audit.loss)
      val unchanged=right(probe.value(nextState))==probe.audit.loss
      val passed=production("passed")==true && spatialAgreement("passed")==true && supportExact && complete &&
        validityExact && initialDifference<=lossTolerance && unchanged && rows.forall(_("passed")==true)
      val result=Map[String,Any]("variant"->name,"fixed_shape"->f.frame.grid.shape,"moving_shape"->m.frame.grid.shape,
        "fixed_index_to_ras_mm"->f.frame.grid.indexToFrame.rowMajor,"moving_index_to_ras_mm"->m.frame.grid.indexToFrame.rowMajor,
        "audit"->probe.audit,"initial_loss_absolute_difference"->initialDifference,
        "production_agreement"->production,"spatial_agreement"->spatialAgreement,
        "fixed_intensity_agreement"->intensityAgreement(original.intensityGradient.get._1,probe.intensityGradient.get._1),
        "moving_intensity_agreement"->intensityAgreement(original.intensityGradient.get._2,probe.intensityGradient.get._2),
        "fixed_support"->chain.fixed,"moving_support"->chain.moving,"support_exact"->supportExact,
        "complete_spatial_support"->complete,"production_validity_exact"->validityExact,
        "current_loss_unchanged"->unchanged,"rows"->rows,"passed"->passed)
      write(output.resolve(name+".json"),result)
      println(RealImageCheckpointJson.encode(Map("variant"->name,"passed"->passed)))
      result
    val originalUnchanged=right(original.value(state))==original.audit.loss
    write(output.resolve("result.json"),Map("schema"->"hfq-grid-audit-v1","variants"->results,
      "loss_tolerance"->lossTolerance,"gradient_absolute_tolerance"->gradientAbsoluteTolerance,
      "gradient_relative_tolerance"->gradientRelativeTolerance,"epsilons_mm"->epsilons,
      "original_current_loss_unchanged"->originalUnchanged,"passed"->(originalUnchanged && results.forall(_("passed")==true)),
      "intensity_only"->intensityOnly,"qualification_claim"->false,"runtime_comparison_allowed"->false))

  private def intensityAgreement(a: Array[Double],b: Array[Double]): Map[String,Any] =
    val mismatches=a.indices.filter(i => (a(i)==0.0)!=(b(i)==0.0)).map: i =>
      Map[String,Any]("index"->i,"original"->a(i),"reindexed"->b(i),"absolute_difference"->math.abs(a(i)-b(i)))
    agreement(a,b) ++ Map("original_nonzero"->a.count(_ != 0.0),"reindexed_nonzero"->b.count(_ != 0.0),
      "zero_status_changes"->mismatches,"zero_status_change_count"->mismatches.size)

  private def agreement(a: Array[Double],b: Array[Double]): Map[String,Any] =
    require(a.length==b.length && a.forall(_.isFinite) && b.forall(_.isFinite))
    var squared=0.0;var as=0.0;var bs=0.0;var maximum=0.0
    for i <- a.indices do
      val d=a(i)-b(i);squared+=d*d;as+=a(i)*a(i);bs+=b(i)*b(i);maximum=math.max(maximum,math.abs(d))
    require(as>0 && bs>0)
    val relative=math.sqrt(squared)/(math.sqrt(as)+math.sqrt(bs))
    Map("maximum_absolute_difference"->maximum,"relative_l2_difference"->relative,"original_l2"->math.sqrt(as),
      "reindexed_l2"->math.sqrt(bs),"passed"->(maximum<=gradientAbsoluteTolerance && relative<=gradientRelativeTolerance))

  private def write(path: Path,value: Any): Unit =
    Files.writeString(path,RealImageCheckpointJson.encode(value)+"\n",StandardOpenOption.CREATE_NEW)
    ()
