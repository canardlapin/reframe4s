package reframe4s.halfflow

import image4s.{ContinuousImage, SampleSpace}
import image4s.geometry.{Affine, D3}
import image4s.geometry.Frame as CanonicalFrame
import image4s.nifti.Nifti
import java.io.{BufferedInputStream, DataInputStream}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import ravel.Rank
import reframe4s.benchmark.flashalign.EvidenceHash
import reframe4s.halfflow.internal.*

/** Read-only pyramid checkpoint audit. No labels, fitting or export admission.
  * The objective/gradient come from the production session; perturbations use
  * the actual paired integration, residual composition and image interpolation.
  */
object RealImageObjectiveAudit:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving
  private def right[E, A](value: Either[E, A]): A =
    value.fold(error => throw new IllegalArgumentException(error.toString), identity)

  def main(args: Array[String]): Unit =
    require(args.length >= 7 && args.length <= 9,
      "moving fixed fixed-mask checkpoint output expected-loss checkpoint-damping [decreasing-epsilons-mm] [level-index]")
    val epsilons = auditEpsilons(args.lift(7))
    val levelIndex = args.lift(8).fold(1)(_.toInt)
    val moving = right(Nifti.readScaledDouble(Paths.get(args(0))))
    val fixed = right(Nifti.readScaledDouble(Paths.get(args(1))))
    moving.image.fold(_ => throw new IllegalArgumentException("scalar D3 moving required"),
      m => fixed.image.fold(_ => throw new IllegalArgumentException("scalar D3 fixed required"),
        f => run(right(m.value.requireDataRank[3]), right(f.value.requireDataRank[3]),
          Paths.get(args(2)), Paths.get(args(3)), Paths.get(args(4)), args(5).toDouble, args(6).toDouble, epsilons, levelIndex)))

  private[halfflow] def auditEpsilons(value: Option[String]): Vector[Double] =
    val epsilons = value.fold(Vector(0.5, 0.2, 0.1, 0.05, 0.02, 0.01, 0.005, 0.002))(
      _.split(",", -1).map(_.trim.toDouble).toVector)
    require(epsilons.length >= 3 && epsilons.forall(e => e.isFinite && e > 0.0) &&
      epsilons.zip(epsilons.tail).forall((a, b) => a > b),
      "audit epsilons must contain at least three finite, positive, strictly decreasing values")
    epsilons

  private def run[M <: CanonicalFrame[D3], F <: CanonicalFrame[D3],
      MS <: SampleSpace[M, D3], FS <: SampleSpace[F, D3]](
      moving: ContinuousImage[MS, Double, Rank[3]], fixed: ContinuousImage[FS, Double, Rank[3]],
      maskPath: Path, checkpoint: Path, output: Path, expectedLoss: Double, damping: Double,
      epsilons: Vector[Double], levelIndex: Int
  ): Unit =
    require(!Files.exists(output), "refusing to overwrite objective audit")
    require(expectedLoss.isFinite && damping.isFinite && damping > 0.0)
    val fg = GridSpec.fromGrid(fixed.grid)
    val mg = GridSpec.fromGrid(moving.grid)
    val ff = RegistrationFrame[Fixed](SpatialDomainId("objective-audit-fixed"), fg)
    val mf = RegistrationFrame[Moving](SpatialDomainId("objective-audit-moving"), mg)
    val fv = NeuroVol.fromRavel(fixed.data, fg, "fixed")
    val mv = NeuroVol.fromRavel(moving.data, mg, "moving")
    val fi = right(RegistrationImage.make(ff, fv,
      FieldValidity.copyMask(RealImageRegistration.loadMask(maskPath, fg))))
    val mi = right(RegistrationImage.make(mf, mv))
    val plan = RealImageRegistration.finePlan(50, 1e-3, 6.0, 20, 6.0, 1.5)
    require(levelIndex >= 0 && levelIndex < plan.levels.length, "invalid pyramid level index")
    val level = plan.levels(levelIndex)
    val (fixedLevel, movingLevel) = right(HalfFlowCc.prepareProbeLevel(fi, mi, level))
    val wg = HalfFlowKernels.pyramidGrid(fg, level.shrink)
    val wf = RegistrationFrame[Work](SpatialDomainId("objective-audit-work"), wg)
    val state = right(ForwardMidpoint.make(loadArm(checkpoint, "fixed", wf, fixedLevel.frame),
      loadArm(checkpoint, "moving", wf, movingLevel.frame)))
    val probe = right(HalfFlowCc.probeObjectiveAtLevel(fixedLevel, movingLevel, state, level, plan))
    val sampledIntensity = probe.sampledIntensity.getOrElse(
      throw new IllegalArgumentException("CC intensity-space diagnostic required"))
    val spatialChain = probe.spatialChain.getOrElse(
      throw new IllegalArgumentException("Spatial-chain diagnostic required"))
    require(math.abs(probe.audit.loss - expectedLoss) <= 1e-12,
      s"saved objective not reproduced: ${probe.audit.loss} versus $expectedLoss")
    require(math.abs(right(probe.value(state)) - expectedLoss) <= 1e-12, "frozen current value mismatch")
    val n = wg.nVoxels
    val norms = Array.tabulate(n): i =>
      math.sqrt((0 until 3).map(c => math.pow(probe.gradient(i + c * n), 2)).sum)
    val maximum = norms.max
    require(maximum.isFinite && maximum > 0.0, "missing finite nonzero gradient")
    val crossover = math.sqrt(damping * (2.0 * probe.audit.loss + plan.rankOneEnergyEpsilon))
    Files.createDirectories(output)
    val files = Vector("fixed", "moving").flatMap: arm =>
      Vector(".json", ".f64", ".valid-u8").map(s => checkpoint.resolve(s"midpoint-$arm-residual$s")) :+
        checkpoint.resolve(s"midpoint-$arm-affine.json")
    val inputs = (files :+ maskPath :+ checkpoint.resolve("checkpoint.json"))
      .map(p => p.toAbsolutePath.toString -> EvidenceHash.file(p).hex).toMap
    write(output.resolve("initial.json"), Map("schema" -> "halfflow-objective-probe-v2",
      "audit" -> probe.audit, "expected_loss" -> expectedLoss, "inputs" -> inputs,
      "damping" -> damping, "epsilons_mm" -> epsilons, "gradient_maximum" -> maximum,
      "level_index" -> levelIndex, "shrink" -> level.shrink,
      "pyramid_sigma_mm" -> level.pyramidSigmaMm, "work_shape" -> wg.shape,
      "gradient_valid" -> probe.gradientValid.count(identity), "gradient_nonzero" -> norms.count(_ > 0.0),
      "spatial_chain_fixed" -> spatialChain.fixed, "spatial_chain_moving" -> spatialChain.moving,
      "rank_one_crossover" -> crossover, "gradient_above_crossover" -> norms.count(_ > crossover),
      "scope" -> "Frozen objective before accumulated regularization; no convergence or qualification claim"))
    val results = Vector.newBuilder[Map[String, Any]]
    for name <- Vector("translation", "smooth-wave", "negative-gradient") do
      val direction = new Array[Double](3 * n)
      var i = 0
      while i < n do
        val x = i % wg.extentX
        val y = (i / wg.extentX) % wg.extentY
        val z = i / (wg.extentX * wg.extentY)
        val wave = math.sin(2.0 * math.Pi * x / (wg.extentX - 1)) *
          math.sin(math.Pi * y / (wg.extentY - 1)) * math.sin(math.Pi * z / (wg.extentZ - 1))
        for c <- 0 until 3 do
          direction(i + c * n) = name match
            case "translation" => Vector(0.73, -0.21, 0.16)(c)
            case "smooth-wave" => wave * Vector(0.73, -0.21, 0.16)(c)
            case _ => -probe.gradient(i + c * n) / maximum
        i += 1
      VelocityBoundaryTaper.inPlace(direction, wg, plan.velocityBoundaryWidthMm)
      var analytic = 0.0
      var spatialAnalytic = 0.0
      i = 0
      while i < direction.length do
        analytic += probe.gradient(i) * direction(i)
        spatialAnalytic += spatialChain.gradient(i) * direction(i)
        i += 1
      val rows = epsilons.map: epsilon =>
        def value(scale: Double): HalfFlowSampledIntensityProbe =
          val velocity = right(Velocity.make(wf, DenseVectorField.fromLegacyPlanar(wg,
            direction.map(_ * scale), DenseVectorFieldKind.Displacement)))
          val half = HalfStep.fromPairedFlow(right(PairedScalingAndSquaring.expHalfPair(velocity)))
          val candidate = right(state.advance(half))
          right(sampledIntensity(candidate))
        val plus = value(epsilon)
        val minus = value(-epsilon)
        val numerical = (plus.loss - minus.loss) / (2.0 * epsilon)
        val intensityChain = (plus.linearizedChange - minus.linearizedChange) / (2.0 * epsilon)
        val chainError = math.abs(numerical - intensityChain) /
          math.max(1e-12, math.abs(numerical) + math.abs(intensityChain))
        val error = math.abs(numerical - analytic) / math.max(1e-12, math.abs(numerical) + math.abs(analytic))
        val spatialError = math.abs(numerical - spatialAnalytic) /
          math.max(1e-12, math.abs(numerical) + math.abs(spatialAnalytic))
        val row = Map[String, Any]("direction" -> name, "epsilon_mm" -> epsilon,
          "analytic" -> analytic, "finite_difference" -> numerical, "relative_error" -> error,
          "plus_loss" -> plus.loss, "minus_loss" -> minus.loss,
          "intensity_chain_derivative" -> intensityChain, "intensity_chain_relative_error" -> chainError,
          "spatial_chain_derivative" -> spatialAnalytic, "spatial_chain_relative_error" -> spatialError)
        Files.writeString(output.resolve("curve.jsonl"), RealImageCheckpointJson.encode(row) + "\n",
          StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        println(RealImageCheckpointJson.encode(row))
        row
      results += Map("direction" -> name, "analytic" -> analytic, "rows" -> rows)
    require(math.abs(right(probe.value(state)) - expectedLoss) <= 1e-12, "probe mutated frozen objective")
    write(output.resolve("result.json"), Map("directions" -> results.result(),
      "current_loss_unchanged" -> true, "qualification_claim" -> false))

  private[halfflow] def loadArm[W, E](directory: Path, arm: String, work: RegistrationFrame[W],
      endpoint: RegistrationFrame[E]): ForwardMidpointArm[W, E] =
    val name = s"midpoint-$arm-residual"
    val metadata = Files.readString(directory.resolve(name + ".json"))
    def array(key: String): Vector[Double] =
      val pattern = ("\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]").r
      pattern.findFirstMatchIn(metadata).getOrElse(throw new IllegalArgumentException(s"missing $key"))
        .group(1).split(",").map(_.trim.toDouble).toVector
    require(array("shape") == work.grid.shape.map(_.toDouble), "checkpoint is not on the declared pyramid work grid")
    val matrix = right(Affine.fromRowMajor[D3](array("index_to_ras_mm")))
    require((0 until 16).forall(i => matrix.matrix(i / 4, i % 4) == work.grid.affine(i / 4, i % 4)),
      "checkpoint work affine coefficients differ")
    require(metadata.contains("\"dtype\":\">f8\"") && metadata.contains("\"voxel_order\":\"x-fastest\"") &&
      metadata.contains("\"components\":\"interleaved-xyz\"") && metadata.contains("\"map\":\"absolute-source-RAS-mm\""))
    val n = work.grid.nVoxels
    val binary = directory.resolve(name + ".f64")
    require(Files.size(binary) == n.toLong * 24, "wrong checkpoint coordinate length")
    val stream = new DataInputStream(new BufferedInputStream(Files.newInputStream(binary)))
    val coordinates = new Array[Double](3 * n)
    try
      var i = 0
      while i < n do
        for c <- 0 until 3 do coordinates(i + c * n) = stream.readDouble()
        i += 1
    finally stream.close()
    val validity = Files.readAllBytes(directory.resolve(name + ".valid-u8"))
    require(validity.length == n && validity.forall(v => v == 0 || v == 1), "invalid validity buffer")
    val residual = right(DensePull.make(work, work,
      DenseVectorField.fromLegacyPlanar(work.grid, coordinates, DenseVectorFieldKind.SourceCoordinates),
      FieldValidity.copyMask(validity.map(_ == 1))))
    val values = Files.readString(directory.resolve(s"midpoint-$arm-affine.json"))
      .stripPrefix("[").stripSuffix("]").split(",").map(_.trim.toDouble).toVector
    right(ForwardMidpointArm.make(residual, right(AffineIso.make(work, endpoint,
      right(Affine.fromRowMajor[D3](values))))))

  private def write(path: Path, value: Any): Unit =
    Files.writeString(path, RealImageCheckpointJson.encode(value) + "\n", StandardOpenOption.CREATE_NEW)
    ()
