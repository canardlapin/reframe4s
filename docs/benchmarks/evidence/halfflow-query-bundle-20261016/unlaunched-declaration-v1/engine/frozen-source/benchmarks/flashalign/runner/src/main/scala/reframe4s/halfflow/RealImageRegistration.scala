package reframe4s.halfflow

import image4s.geometry.{Affine, D3}
import image4s.ContinuousImage
import image4s.SampleSpace
import image4s.geometry.Frame as CanonicalFrame
import image4s.nifti.Nifti
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import ravel.Rank
import reframe4s.flashalign.*
import reframe4s.benchmark.flashalign.EvidenceHash
import reframe4s.halfflow.internal.*

/** Opt-in acquired-image development runner. Labels are deliberately absent
  * from its input interface. Persisted maps are absolute RAS-mm pull coordinates,
  * x-fastest voxels, interleaved xyz, big-endian float64; never displacements.
  * The companion Python evaluator reloads and independently checks them.
  */
object RealImageRegistration:
  private sealed trait Fixed
  private sealed trait Moving
  private sealed trait Work

  /** Explicit development controls; legacy invocations retain their exact plan. */
  private[halfflow] final case class RunOptions(
      stepsPerLevel: Int = 10,
      fineSmoothingMm: Double = 6.0,
      affineBudget: Option[Int] = None,
      policyReceipt: Option[Path] = None,
      savedAffine: Option[Path] = None,
      accumulatedSmoothingMm: Double = 0.0,
      boundaryWidthMm: Double = 6.0,
      fineAccumulatedSmoothingMm: Option[Double] = None,
      scaleAccumulatedSmoothingByStep: Boolean = false,
      affineTrials: Option[Int] = None,
      fineCcRadius: Int = 2,
      checkpoints: Option[RealImageCheckpointPolicy] = None,
      spatialChainGradient: Boolean = false
  )

  private[halfflow] def parseOptions(args: Vector[String]): RunOptions =
    val saved = args.headOption.filterNot(_.startsWith("--")).map(Paths.get(_))
    val flags = if saved.nonEmpty then args.tail else args
    require(flags.length % 2 == 0, "development flags require values")
    val pairs = flags.grouped(2).map(p => p(0) -> p(1)).toVector
    require(pairs.map(_._1).distinct.size == pairs.size, "duplicate development flag")
    val checkpointFlags = Set("--checkpoint-accepted", "--checkpoint-rejected", "--checkpoint-max-states", "--checkpoint-manifest", "--checkpoint-defer-export")
    require(pairs.forall(p => (Set("--steps-per-level", "--fine-smoothing-mm", "--affine-budget", "--policy-receipt", "--total-smoothing-mm", "--boundary-width-mm", "--fine-total-smoothing-mm", "--scale-total-smoothing-by-step", "--affine-trials", "--fine-cc-radius", "--spatial-chain-gradient") ++ checkpointFlags).contains(p._1)), "unknown development flag")
    val values = pairs.toMap
    val checkpoints =
      if !values.keys.exists(checkpointFlags.contains) then None
      else
        require(Set("--checkpoint-accepted", "--checkpoint-max-states", "--checkpoint-manifest").subsetOf(values.keySet),
          "checkpoints require declared accepted steps, a state budget and a frozen manifest")
        def indices(value: String): Vector[Int] =
          if value.isEmpty then Vector.empty else value.split(",").map(_.toInt).toVector
        val accepted = indices(values("--checkpoint-accepted"))
        val rejected = indices(values.getOrElse("--checkpoint-rejected", ""))
        val maximum = values("--checkpoint-max-states").toInt
        require(accepted.nonEmpty && accepted.distinct == accepted && accepted.forall(_ >= 0), "invalid accepted checkpoint indices")
        require(rejected.distinct == rejected && rejected.forall(n => n > 0 && n <= 10000), "invalid rejected checkpoint indices")
        require(maximum >= 0 && maximum <= 128, "checkpoint state budget must lie in [0,128]")
        Some(RealImageCheckpointPolicy(accepted, rejected, maximum, Paths.get(values("--checkpoint-manifest")),
          values.get("--checkpoint-defer-export").fold(false)(_.toBoolean)))
    val result = RunOptions(
      values.get("--steps-per-level").fold(10)(_.toInt),
      values.get("--fine-smoothing-mm").fold(6.0)(_.toDouble),
      values.get("--affine-budget").map(_.toInt),
      values.get("--policy-receipt").map(Paths.get(_)), saved,
      values.get("--total-smoothing-mm").fold(0.0)(_.toDouble),
      values.get("--boundary-width-mm").fold(6.0)(_.toDouble),
      values.get("--fine-total-smoothing-mm").map(_.toDouble),
      values.get("--scale-total-smoothing-by-step").fold(false)(_.toBoolean),
      values.get("--affine-trials").map(_.toInt),
      values.get("--fine-cc-radius").fold(2)(_.toInt), checkpoints,
      values.get("--spatial-chain-gradient").fold(false)(_.toBoolean))
    require(result.stepsPerLevel > 0 && result.stepsPerLevel <= 1000, "invalid accepted-step budget")
    require(result.fineCcRadius > 0 && result.fineCcRadius <= 10, "invalid fine CC radius")
    require(result.fineSmoothingMm.isFinite && result.fineSmoothingMm >= 0.0, "invalid fine smoothing")
    require(result.accumulatedSmoothingMm.isFinite && result.accumulatedSmoothingMm >= 0.0, "invalid total smoothing")
    require(result.boundaryWidthMm.isFinite && result.boundaryWidthMm >= 0.0, "invalid boundary width")
    require(result.fineAccumulatedSmoothingMm.forall(v => v.isFinite && v >= 0.0), "invalid fine total smoothing")
    require(result.affineBudget.forall(n => n > 0 && n <= 1000), "invalid affine budget")
    require(result.affineTrials.forall(n => n > 0 && n <= 64) &&
      (result.affineTrials.isEmpty || result.affineBudget.nonEmpty), "affine trials require an explicit budget and receipt")
    require(result.affineBudget.isDefined == result.policyReceipt.isDefined, "affine budget requires its policy receipt")
    require(result.savedAffine.isEmpty || result.affineBudget.isEmpty, "supplied affine cannot also request affine fitting")
    require(result.checkpoints.forall(_.acceptedSteps.forall(_ <= result.stepsPerLevel)),
      "checkpoint indices exceed the accepted-step budget")
    result

  def main(args: Array[String]): Unit =
    require(args.length >= 5,
      "moving.nii.gz fixed.nii.gz fixed-mask.nii.gz output-directory identity|affine|fine|fine-inverse-200|fine-taper|fine-taper-long|fine-taper-boundary|identity-fine|identity-fine-taper-boundary [saved-moving-to-fixed-affine.json]")
    val mode = args(4)
    require(Set("identity", "affine", "fine", "fine-inverse-200", "fine-taper", "fine-taper-long", "fine-taper-boundary", "identity-fine", "identity-fine-taper-boundary").contains(mode), s"unknown mode $mode")
    val options = parseOptions(args.drop(5).toVector)
    require(options.checkpoints.isEmpty || mode.contains("fine"), "checkpoints require nonlinear refinement")
    val out = Paths.get(args(3))
    require(!Files.exists(out), s"refusing to overwrite $out")
    Files.createDirectories(out)
    val inputs = args.take(3).map(Paths.get(_))
    val savedAffine = options.savedAffine
    require(savedAffine.isEmpty || mode.startsWith("fine"), "saved affine requires a fine mode")
    val provenance = (inputs.toVector ++ savedAffine ++ options.policyReceipt ++ options.checkpoints.map(_.manifest))
      .map(p => s"${p.toAbsolutePath}\t${EvidenceHash.file(p).hex}").mkString("\n")
    Files.writeString(out.resolve("inputs.sha256"), provenance + "\n", StandardOpenOption.CREATE_NEW)
    Files.writeString(out.resolve("mode.txt"), mode + "\n", StandardOpenOption.CREATE_NEW)
    val moving = Nifti.readScaledDouble(inputs(0)).fold(e => fail(e.message), identity)
    val fixed = Nifti.readScaledDouble(inputs(1)).fold(e => fail(e.message), identity)
    moving.image.fold(
      _ => fail("moving must be scalar D3"),
      m => fixed.image.fold(
        _ => fail("fixed must be scalar D3"),
        f => run(
          m.value.requireDataRank[3].fold(e => fail(e.message), identity),
          f.value.requireDataRank[3].fold(e => fail(e.message), identity),
          inputs(2), out, mode, options
        )
      )
    )

  private def run[M <: CanonicalFrame[D3], F <: CanonicalFrame[D3],
      MS <: SampleSpace[M, D3], FS <: SampleSpace[F, D3]](
      moving: ContinuousImage[MS, Double, Rank[3]],
      fixed: ContinuousImage[FS, Double, Rank[3]],
      maskPath: Path, out: Path, mode: String, options: RunOptions
  ): Unit =
    val started = System.nanoTime()
    val savedAffine = options.savedAffine
    def grid[A <: CanonicalFrame[D3], S <: SampleSpace[A, D3]](
        image: ContinuousImage[S, Double, Rank[3]]): GridSpec =
      GridSpec.fromGrid(image.grid)
    val mg = grid(moving)
    val fg = grid(fixed)
    val mf = RegistrationFrame[Moving](SpatialDomainId("real-mri-moving"), mg)
    val ff = RegistrationFrame[Fixed](SpatialDomainId("real-mri-fixed"), fg)
    val wf = RegistrationFrame[Work](SpatialDomainId("real-mri-work"), fg)
    val mv = NeuroVol.fromRavel(moving.data, mg, "moving")
    val fv = NeuroVol.fromRavel(fixed.data, fg, "fixed")
    require(mv.copyLegacyLinear.forall(_.isFinite) && fv.copyLegacyLinear.forall(_.isFinite), "nonfinite input")
    val mask = loadMask(maskPath, fg)
    val mi = RegistrationImage.make(mf, mv).fold(e => fail(e.message), identity)
    val fi = RegistrationImage.make(ff, fv, FieldValidity.copyMask(mask)).fold(e => fail(e.message), identity)
    val plan = finePlan(if mode == "fine-inverse-200" then 200 else 50, if mode.contains("fine-taper") then 1e-3 else 0.0, if mode.endsWith("taper-boundary") then options.boundaryWidthMm else 0.0,
      options.stepsPerLevel, options.fineSmoothingMm, options.accumulatedSmoothingMm, options.fineAccumulatedSmoothingMm,
      options.scaleAccumulatedSmoothingByStep, options.fineCcRadius)
    Files.writeString(out.resolve("development-controls.txt"), options.toString + "\n", StandardOpenOption.CREATE_NEW)
    Files.writeString(out.resolve("plan.txt"), plan.toString + "\n", StandardOpenOption.CREATE_NEW)
    val identityPair = AffineIso.make(ff, mf, Affine.identity[D3]).fold(e => fail(e.message), identity).dense
    savePair("identity", identityPair, mv, fv, out)
    val initial =
      if mode.startsWith("identity") then
        ForwardMidpoint.identity(wf, ff, mf).fold(e => fail(e.message), identity)
      else if savedAffine.nonEmpty then
        val values = Files.readString(savedAffine.get).trim.stripPrefix("[").stripSuffix("]").split(",").map(_.trim.toDouble)
        require(values.length == 16, "saved affine requires 16 row-major numbers")
        val affine = Affine.fromRowMajor[D3](values.toVector).fold(e => fail(e.message), identity)
        val supplied = AffineInitializer.supplied(fi, mi, wf, affine.inverse).fold(e => fail(e.message), identity)
        savePair("affine", supplied.affine.dense, mv, fv, out)
        println(s"REAL_MRI stage=affine-initialization status=supplied hash=${EvidenceHash.file(savedAffine.get).hex}")
        ForwardMidpoint.fromLegacy(supplied.midpoint)
      else
        println("REAL_MRI stage=flashalign started=true")
        val base = LinearPresetPolicies.WithinModality
        val policy = if options.affineBudget.nonEmpty then
          val budget = options.affineBudget.get
          val receipt = options.policyReceipt.get
          val trials = options.affineTrials.getOrElse(base.affineTrust.maximumTrialAttempts)
          val id = s"real-mri-within-modality-budget-$budget-trials-$trials-v1"
          LinearPresetPolicy.create(id, "1.0.0", base.preset,
            base.patch, base.sampling, base.preparation, base.capture, base.rigidTrust,
            base.affineTrust.copy(maximumLinearizations = budget, maximumTrialAttempts = trials), base.qc,
            PresetCalibrationIdentity(id, receipt.toString,
              EvidenceHash.file(receipt).hex, "Uncalibrated development budget extension; thresholds unchanged")
          ).fold(e => fail(e.message), identity)
        else base
        val config = if policy eq base then
          FlashalignConfig.forPreset(FlashalignPreset.WithinModality,
            FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture)
        else FlashalignConfig.create(policy, FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture)
        Files.writeString(out.resolve("flashalign-policy.txt"),
          s"${policy.id}\n${policy.affineTrust}\n${policy.calibration}\n" +
            config.affineRecoveryPolicy.fold("")(p => s"Recovery only after no capture candidate converges: ${p.id}\n${p.affineTrust}\n${p.calibration}\n"))
        val compiled = Flashalign.affine(moving, fixed, config).fold(e => fail(e.message), identity)
        compiled.run(compiled.newWorkspace()) match
          case Left(error) =>
            Files.writeString(out.resolve("failure.txt"), s"flashalign: ${error.message}\n${error.failureDiagnostics}\n")
            println(s"REAL_MRI stage=flashalign status=failed error=${error.message}")
            fail(s"Flashalign failed; diagnostics saved in $out")
          case Right(result) =>
            Files.writeString(out.resolve("flashalign-affine-moving-to-fixed.json"),
              result.movingToFixed.operator.rowMajor.mkString("[", ",", "]"))
            Files.writeString(out.resolve("flashalign-diagnostics.txt"), s"${result.report}\n${result.diagnostics}\n")
            val supplied = SuppliedAffineInitialization.fromMovingToFixed(fi, mi, wf, result.movingToFixed)
              .fold(e => fail(e.message), identity)
            savePair("affine", supplied.fixedToMoving.dense, mv, fv, out)
            println(s"REAL_MRI stage=flashalign status=ok elapsedMs=${elapsed(started)}")
            supplied.initial
    if mode != "affine" && mode != "identity" then
      println("REAL_MRI stage=halfflow started=true")
      val optimized =
        if options.spatialChainGradient then
          val observation = options.checkpoints.map: policy =>
            val observer = new RealImageCheckpoints(out, policy, initial, plan, fv, mv, mask)
            HalfFlowCcObservation(observer, policy.maximumStates)
          HalfFlowCc.optimizeSpatialChain(fi, mi, initial, plan, observation)
        else options.checkpoints match
          case None => HalfFlowCc.optimize(fi, mi, initial, plan)
          case Some(policy) =>
            val observer = new RealImageCheckpoints(out, policy, initial, plan, fv, mv, mask)
            HalfFlowCc.optimizeObserved(fi, mi, initial, plan, HalfFlowCcObservation(observer, policy.maximumStates))
      optimized match
        case Left(error) =>
          Files.writeString(out.resolve("failure.txt"), s"halfflow-fine: ${error.message}\n")
          println(s"REAL_MRI stage=halfflow status=failed error=${error.message}")
        case Right(result) =>
          Files.writeString(out.resolve("halfflow-diagnostics.txt"), s"${result.diagnostics}\n")
          savePull("midpoint-fixed-residual", result.state.fixed.residual, fv, out)
          savePull("midpoint-moving-residual", result.state.moving.residual, fv, out)
          for (name, matrix) <- Vector(
            "fixed" -> result.state.fixed.affine.transform.matrix,
            "moving" -> result.state.moving.affine.transform.matrix
          ) do
            Files.writeString(out.resolve(s"midpoint-$name-affine.json"),
              (0 until 16).map(i => matrix(i / 4, i % 4)).mkString("[", ",", "]"))
          ForwardMidpointExporter.inspect(result.state, plan.exportConfig,
            Some(FieldValidity.copyMask(mask)),
            Some(FieldValidity.copyMask(mv.copyLegacyLinear.map(_ > 0.0)))) match
            case Left(error) =>
              Files.writeString(out.resolve("failure.txt"), s"halfflow-export-construction: ${error.message}\n")
              println(s"REAL_MRI stage=export status=failed error=${error.message}")
            case Right(candidate) =>
              val admitted = ForwardMidpointExporter.admit(candidate, plan.exportConfig)
              val status = admitted.fold(e => s"rejected: ${e.message}", _ => "accepted")
              // Rejected candidates remain explicitly named diagnostics.
              val name = if admitted.isRight then "halfflow" else "halfflow-rejected"
              savePair(name, candidate.transform, mv, fv, out)
              Files.writeString(out.resolve("export-diagnostics.txt"),
                s"$status\n${candidate.fixedResidualInverse}\n${candidate.movingResidualInverse}\n${candidate.endpointRoundTrip}\n")
              if admitted.isLeft then
                val _ = Files.writeString(out.resolve("failure.txt"), status + "\n")
              println(s"REAL_MRI stage=export status=$status acceptedSteps=${result.diagnostics.acceptedSteps} attempts=${result.diagnostics.attempts}")
    Files.writeString(out.resolve("elapsed-ms.txt"), elapsed(started).toString + "\n")
    println(s"REAL_MRI complete=true elapsedMs=${elapsed(started)} output=$out")
    if Files.exists(out.resolve("failure.txt")) then fail(s"registration rejected; diagnostics saved in $out")

  private[halfflow] def savePair[A, B](name: String, pair: InversePair[A, B],
      moving: NeuroVol[Double], fixed: NeuroVol[Double], out: Path): Unit =
    savePull(name + "-fixed-to-moving", pair.forward, moving, out)
    savePull(name + "-moving-to-fixed", pair.backward, fixed, out)

  private[halfflow] def savePull[A, B](name: String, pull: DensePull[A, B],
      source: NeuroVol[Double], out: Path): Unit =
    val grid = pull.from.grid
    val coords = Array.tabulate(grid.nVoxels * 3)(i => pull.sourceCoordinates.linearComponent(i / 3, i % 3))
    writeDoubles(out.resolve(name + ".f64"), coords)
    val valid = Array.tabulate[Byte](grid.nVoxels)(i => pull.validity match
      case FieldValidity.All => 1.toByte
      case FieldValidity.Mask(m) => (if m(i) then 1 else 0).toByte)
    Files.write(out.resolve(name + ".valid-u8"), valid, StandardOpenOption.CREATE_NEW)
    val warped = HalfFlowKernels.pullScalar(source, pull.sourceCoordinates,
      pull.validity, FieldValidity.All, 0.0)
    writeDoubles(out.resolve(name + "-warped.f64"), warped.values.copyLegacyLinear)
    val matrix = (0 until 16).map(i => grid.affine(i / 4, i % 4)).mkString("[", ",", "]")
    val shape = Vector(grid.shape(0), grid.shape(1), grid.shape(2)).mkString("[", ",", "]")
    val _ = Files.writeString(out.resolve(name + ".json"),
      s"{\"shape\":$shape,\"index_to_ras_mm\":$matrix,\"dtype\":\">f8\",\"voxel_order\":\"x-fastest\",\"components\":\"interleaved-xyz\",\"map\":\"absolute-source-RAS-mm\"}\n")

  private def writeDoubles(path: Path, values: Array[Double]): Unit =
    val stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)))
    try values.foreach(stream.writeDouble)
    finally stream.close()

  private[halfflow] def loadMask(path: Path, expected: GridSpec): Array[Boolean] =
    val decoded = Nifti.readScaledDouble(path).fold(e => fail(e.message), identity)
    decoded.image.fold(_ => fail("mask must be D3"), d =>
      val shape = d.value.grid.shape
      // Independent NIfTI reads mint different live frame owners. At this IO
      // boundary, validate the physical lattice before attaching the mask to
      // the explicitly selected fixed image owner.
      val sameAffine = d.value.grid.indexToFrame.rowMajor.zip(expected.indexToFrame.rowMajor)
        .forall((actual, wanted) => math.abs(actual - wanted) <= 1e-6)
      require(shape == expected.shape && sameAffine, "fixed mask grid differs from fixed image")
      val data = d.value.data.reshapeView(ravel.Shape(shape(0), shape(1), shape(2)))
      val values = NeuroVol.fromRavel(data, expected, "fixed mask").copyLegacyLinear
      require(values.forall(_.isFinite) && values.exists(_ > 0.0), "invalid fixed mask")
      values.map(_ > 0.0)
    )

  private[halfflow] def finePlan(inverseIterations: Int, supportFloor: Double, boundaryWidthMm: Double,
      stepsPerLevel: Int = 10, fineSmoothingMm: Double = 6.0, accumulatedSmoothingMm: Double = 0.0,
      fineAccumulatedSmoothingMm: Option[Double] = None,
      scaleAccumulatedSmoothingByStep: Boolean = false, fineCcRadius: Int = 2): HalfFlowCcPlan =
    def cc(radius: Int) = NeighborhoodCcConfig.make(radius = VoxelWindowRadius(radius, radius, radius),
      minimumSupportFraction = 0.15, fullSupportFraction = 0.7,
      minimumVarianceFraction = 1e-7, fullVarianceFraction = 1e-5,
      denominatorEpsilonFraction = 1e-7).fold(e => fail(e.message), identity)
    val levels = Vector(
      HalfFlowCcLevel.make(2, 1.0, cc(2), 10.0, 1.0, stepsPerLevel, stepsPerLevel + 14,
        accumulatedSmoothingMm = accumulatedSmoothingMm, scaleAccumulatedSmoothingByStep = scaleAccumulatedSmoothingByStep),
      HalfFlowCcLevel.make(1, 0.0, cc(fineCcRadius), fineSmoothingMm, 0.6, stepsPerLevel, stepsPerLevel + 14,
        accumulatedSmoothingMm = fineAccumulatedSmoothingMm.getOrElse(accumulatedSmoothingMm),
        scaleAccumulatedSmoothingByStep = scaleAccumulatedSmoothingByStep)
    ).map(_.fold(e => fail(e.message), identity))
    val control = HalfFlowCcControlConfig.make(initialDamping = 1e-4, minimumDamping = 1e-8,
      maximumDamping = 1.0, maximumObjectiveRetries = 6, maximumGeometryRetries = 8,
      maximumIntegrationRetries = 5).fold(e => fail(e.message), identity)
    val exportConfig = ResidualInverseConfig.make(shrinks = Vector(2, 1), iterationsPerLevel = inverseIterations,
      maximumInteriorErrorMm = 0.2, maximumInteriorErrorVox = 0.2, interiorMargin = 6)
      .fold(e => fail(e.message), identity)
    HalfFlowCcPlan.make(levels, supportSigmaMm = 1.5, minimumUsefulStepMm = 1e-6,
      maximumIntegrationInverseErrorMm = 0.03, control = control, exportConfig = exportConfig,
      velocitySupportFloor = supportFloor, velocityBoundaryWidthMm = boundaryWidthMm)
      .fold(e => fail(e.message), identity)

  private def elapsed(started: Long): Double = (System.nanoTime() - started).toDouble / 1e6
  private def fail(message: String): Nothing = throw new IllegalArgumentException(message)
