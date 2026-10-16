package reframe4s.halfflow

import image4s.{ContinuousImage, SampleSpace}
import image4s.geometry.D3
import image4s.geometry.Frame as CanonicalFrame
import image4s.nifti.Nifti
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import ravel.Rank
import reframe4s.halfflow.internal.*

/** Diagnostic role swap on a fixed saved work grid, not reverse registration.
  * Images, validity and arms move together. The original fixed fitting mask
  * therefore belongs to the swapped moving image. No optimizer is called.
  */
object RealImageOrderAudit:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving
  private val lossTolerance = 1e-12
  private val gradientAbsoluteTolerance = 1e-11
  private val gradientRelativeTolerance = 1e-10
  private val epsilons = Vector(.01,.0001)
  private def right[E,A](value: Either[E,A]): A =
    value.fold(error => throw new IllegalArgumentException(error.toString),identity)

  def main(args: Array[String]): Unit =
    require(args.length==7,"moving fixed fixed-mask checkpoint output expected-loss level-index")
    val moving = right(Nifti.readScaledDouble(Paths.get(args(0))))
    val fixed = right(Nifti.readScaledDouble(Paths.get(args(1))))
    moving.image.fold(_ => throw new IllegalArgumentException("scalar D3 moving required"),
      m => fixed.image.fold(_ => throw new IllegalArgumentException("scalar D3 fixed required"),
        f => run(right(m.value.requireDataRank[3]),right(f.value.requireDataRank[3]),
          Paths.get(args(2)),Paths.get(args(3)),Paths.get(args(4)),args(5).toDouble,args(6).toInt)))

  private def run[M <: CanonicalFrame[D3],F <: CanonicalFrame[D3],
      MS <: SampleSpace[M,D3],FS <: SampleSpace[F,D3]](
      moving: ContinuousImage[MS,Double,Rank[3]],fixed: ContinuousImage[FS,Double,Rank[3]],
      maskPath: Path,checkpoint: Path,output: Path,expectedLoss: Double,levelIndex: Int
  ): Unit =
    require(!Files.exists(output),"refusing to overwrite order audit")
    require(expectedLoss.isFinite)
    val fg = GridSpec.fromGrid(fixed.grid); val mg = GridSpec.fromGrid(moving.grid)
    val ff = RegistrationFrame[Fixed](SpatialDomainId("order-fixed"),fg)
    val mf = RegistrationFrame[Moving](SpatialDomainId("order-moving"),mg)
    val fi = right(RegistrationImage.make(ff,NeuroVol.fromRavel(fixed.data,fg,"fixed"),
      FieldValidity.copyMask(RealImageRegistration.loadMask(maskPath,fg))))
    val mi = right(RegistrationImage.make(mf,NeuroVol.fromRavel(moving.data,mg,"moving")))
    val plan = RealImageRegistration.finePlan(50,1e-3,6.0,20,6.0,1.5)
    require(levelIndex>=0 && levelIndex<plan.levels.length,"invalid pyramid level")
    val level = plan.levels(levelIndex)
    val (fixedLevel,movingLevel) = right(HalfFlowCc.prepareProbeLevel(fi,mi,level))
    val grid = HalfFlowKernels.pyramidGrid(fg,level.shrink)
    val work = RegistrationFrame[Work](SpatialDomainId("order-work"),grid)
    val state = right(ForwardMidpoint.make(RealImageObjectiveAudit.loadArm(checkpoint,"fixed",work,fixedLevel.frame),
      RealImageObjectiveAudit.loadArm(checkpoint,"moving",work,movingLevel.frame)))
    val swappedState = right(ForwardMidpoint.make(state.moving,state.fixed))
    val original = right(HalfFlowCc.probeObjectiveAtLevel(fixedLevel,movingLevel,state,level,plan))
    val swapped = right(HalfFlowCc.probeObjectiveAtLevel(movingLevel,fixedLevel,swappedState,level,plan))
    require(math.abs(original.audit.loss-expectedLoss)<=lossTolerance,"captured objective differs")
    val spatial = original.spatialChain.get; val reversedSpatial = swapped.spatialChain.get
    val productionCheck = antisymmetry(original.gradient,swapped.gradient)
    val spatialCheck = antisymmetry(spatial.gradient,reversedSpatial.gradient)
    val supportSwapped = spatial.fixed==reversedSpatial.moving && spatial.moving==reversedSpatial.fixed
    val complete = Vector(spatial.fixed,spatial.moving,reversedSpatial.fixed,reversedSpatial.moving)
      .forall(s => s.unavailable==0 && s.evaluated==s.nonzeroIntensityDerivatives && s.evaluated>0)
    val validSwapped = original.gradientValid.sameElements(swapped.gradientValid)
    val initialDifference = math.abs(original.audit.loss-swapped.audit.loss)
    Files.createDirectories(output)
    write(output.resolve("initial.json"),Map("original_audit"->original.audit,"swapped_audit"->swapped.audit,
      "expected_loss"->expectedLoss,"level_index"->levelIndex,"shrink"->level.shrink,
      "production_antisymmetry"->productionCheck,"spatial_antisymmetry"->spatialCheck,
      "original_fixed_support"->spatial.fixed,"original_moving_support"->spatial.moving,
      "swapped_fixed_support"->reversedSpatial.fixed,"swapped_moving_support"->reversedSpatial.moving,
      "support_swapped_exact"->supportSwapped,"complete_spatial_support"->complete,
      "production_validity_swapped_exact"->validSwapped,"initial_loss_absolute_difference"->initialDifference))
    val n = grid.nVoxels
    val maximum = (0 until n).map(i => math.sqrt((0 until 3).map(c => math.pow(original.gradient(i+c*n),2)).sum)).max
    require(maximum.isFinite && maximum>0.0)
    val rows = Vector.newBuilder[Map[String,Any]]
    var maximumCandidateDifference = 0.0
    for name <- Vector("translation","smooth-wave","negative-gradient") do
      val direction = Array.tabulate(3*n): i =>
        val at = i % n; val c = i / n
        val x = at % grid.extentX; val y = (at / grid.extentX) % grid.extentY
        val z = at / (grid.extentX * grid.extentY)
        val wave = math.sin(2*math.Pi*x/(grid.extentX-1)) * math.sin(math.Pi*y/(grid.extentY-1)) *
          math.sin(math.Pi*z/(grid.extentZ-1))
        name match
          case "translation" => Vector(.73,-.21,.16)(c)
          case "smooth-wave" => wave*Vector(.73,-.21,.16)(c)
          case _ => -original.gradient(i)/maximum
      VelocityBoundaryTaper.inPlace(direction,grid,plan.velocityBoundaryWidthMm)
      for epsilon <- epsilons; sign <- Vector(-1.0,1.0) do
        def half(scale: Double): HalfStep[Work] =
          val velocity = right(Velocity.make(work,DenseVectorField.fromLegacyPlanar(grid,
            direction.map(_ * scale),DenseVectorFieldKind.Displacement)))
          HalfStep.fromPairedFlow(right(PairedScalingAndSquaring.expHalfPair(velocity)))
        val a = right(original.value(right(state.advance(half(sign*epsilon)))))
        val b = right(swapped.value(right(swappedState.advance(half(-sign*epsilon)))))
        require(a.isFinite && b.isFinite,"nonfinite candidate objective")
        val difference = math.abs(a-b)
        maximumCandidateDifference = math.max(maximumCandidateDifference,difference)
        val row = Map[String,Any]("direction"->name,"epsilon_mm"->epsilon,"sign"->sign,
          "original_loss"->a,"swapped_opposite_loss"->b,"absolute_difference"->difference,
          "passed"->(difference<=lossTolerance))
        rows += row
        Files.writeString(output.resolve("curve.jsonl"),RealImageCheckpointJson.encode(row)+"\n",
          StandardOpenOption.CREATE,StandardOpenOption.APPEND)
        println(RealImageCheckpointJson.encode(row))
    val originalUnchanged = right(original.value(state))==original.audit.loss
    val swappedUnchanged = right(swapped.value(swappedState))==swapped.audit.loss
    val passed = productionCheck("passed")==true && spatialCheck("passed")==true && supportSwapped && complete &&
      validSwapped && initialDifference<=lossTolerance && maximumCandidateDifference<=lossTolerance &&
      originalUnchanged && swappedUnchanged
    write(output.resolve("result.json"),Map("schema"->"hfq-order-audit-v1","rows"->rows.result(),
      "loss_tolerance"->lossTolerance,"gradient_absolute_tolerance"->gradientAbsoluteTolerance,
      "gradient_relative_tolerance"->gradientRelativeTolerance,"epsilons_mm"->epsilons,
      "maximum_candidate_loss_difference"->maximumCandidateDifference,
      "original_current_loss_unchanged"->originalUnchanged,"swapped_current_loss_unchanged"->swappedUnchanged,
      "passed"->passed,"qualification_claim"->false,"runtime_comparison_allowed"->false,
      "scope"->"Fixed work-grid image/validity/arm role swap; not reverse fitting or reference-grid exchange"))

  private def antisymmetry(a: Array[Double],b: Array[Double]): Map[String,Any] =
    require(a.length==b.length && a.forall(_.isFinite) && b.forall(_.isFinite))
    var squared = 0.0; var aSquared = 0.0; var bSquared = 0.0; var maximum = 0.0
    for i <- a.indices do
      val sum = a(i)+b(i)
      squared += sum*sum; aSquared += a(i)*a(i); bSquared += b(i)*b(i)
      maximum = math.max(maximum,math.abs(sum))
    require(aSquared>0.0 && bSquared>0.0,"missing nonzero derivative")
    val relative = math.sqrt(squared)/(math.sqrt(aSquared)+math.sqrt(bSquared))
    Map("maximum_absolute_sum"->maximum,"relative_l2_sum"->relative,
      "original_l2"->math.sqrt(aSquared),"swapped_l2"->math.sqrt(bSquared),
      "passed"->(maximum<=gradientAbsoluteTolerance && relative<=gradientRelativeTolerance))

  private def write(path: Path,value: Any): Unit =
    Files.writeString(path,RealImageCheckpointJson.encode(value)+"\n",StandardOpenOption.CREATE_NEW)
    ()
