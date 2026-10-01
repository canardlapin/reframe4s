package reframe4s.halfflow

import image4s.nifti.Nifti
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import reframe4s.halfflow.internal.*

/** Opt-in real-data differential court for the saved Hodgeflow sub-18 case.
  *
  * Run with:
  *
  *   reframe4s-halfflowJVM/Test/runMain
  *     reframe4s.halfflow.BasinBridgeSub18AccuracyProbe [oracles|bridge|all]
  *
  * The moving BET mask is loaded only for evaluation. The fixed mask is the
  * sole optimization mask, matching the saved ANTs protocol. This probe is
  * intentionally outside the ordinary MUnit gate because the real-data
  * bridge and frozen fine stage are long-running diagnostics.
  */
object BasinBridgeSub18AccuracyProbe:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving

  private enum Mode:
    case Oracles, Bridge, All

  private final case class ArtifactSpec(name: String, fileName: String, sha256: String)

  private val artifacts = Vector(
    ArtifactSpec("moving", "moving.nii.gz", "917dd8c4c6cf6fcc96c445fa0e17f88dfad469853e5bc3509bf17b81fb440d06"),
    ArtifactSpec("fixed", "fixed.nii.gz", "7b168153a9508f5f116ac37d33835fa44dd168ba7a9ae31a3b33e6f63bd23292"),
    ArtifactSpec("moving_mask", "moving_mask.nii.gz", "64e089065293eabd54244ea73862f97aa59466b7dd054948bfb5c330abc0f0e3"),
    ArtifactSpec("fixed_mask", "fixed_mask.nii.gz", "745c970d2b45eb5088446528399b6965c2ce912d37ca7cacb993513a302d5161"),
    ArtifactSpec("ants_warped", "synquick_Warped.nii.gz", "54e7ff7ea17adb15b8ef99627d64f5cf03f534f67ec9ab26fbdf6654ec39d835"),
    ArtifactSpec("ants_mask_warped", "synquick_MaskWarped.nii.gz", "08a10d9192239547671d519dab121ded90447f11928ebbe7264892a1cf1c2140")
  )

  private final case class LoadedVolume(
      path: Path,
      shape: Vector[Int],
      grid: GridSpec,
      values: Array[Double],
      volume: NeuroVol[Double]
  )

  private final case class AccuracyMetrics(
      ncc: Double,
      globalNcc: Double,
      gradNcc: Double,
      dice: Double,
      comDistanceVox: Double
  )

  private final case class JacobianStats(
      minimum: Double,
      q01: Double,
      q05: Double,
      q50: Double,
      q95: Double,
      q99: Double,
      nonPositive: Int,
      evaluated: Int,
      eligible: Int,
      validFraction: Double
  )

  private final case class StateEvaluation(
      metrics: AccuracyMetrics,
      jacobian: JacobianStats,
      exportStatus: String,
      exportMaximumInteriorMm: Double,
      evaluationMillis: Long
  )

  private final case class Timed[A](value: A, elapsedMillis: Long)

  private val expectedInitial = AccuracyMetrics(
    // Recomputed from the current Hodgeflow source-tree metric harness on
    // 2026-08-02. The older saved metrics.csv carries stale pre-registration
    // values from an earlier header-baseline implementation.
    ncc = 0.12629275726471551,
    globalNcc = 0.567851411262996,
    gradNcc = -0.10722447412063871,
    dice = 0.6609480584043748,
    comDistanceVox = 17.720557855282404
  )

  private val expectedAnts = AccuracyMetrics(
    ncc = 0.672298050150454,
    globalNcc = 0.9457095651791,
    gradNcc = 0.439945600516007,
    dice = 0.979358955715422,
    comDistanceVox = 0.308368444373031
  )

  def main(arguments: Array[String]): Unit =
    val mode = arguments.headOption.map(_.toLowerCase) match
      case None | Some("all") => Mode.All
      case Some("oracles") => Mode.Oracles
      case Some("bridge") => Mode.Bridge
      case Some(other) => throw new IllegalArgumentException(s"expected oracles, bridge, or all; got '$other'")

    val directory = discover().getOrElse(
      throw new IllegalStateException(
        "saved sub-18 court not found; set HF_SUB18_SYNQUICK_INPUT_DIR or provide the Hodgeflow checkout"
      )
    )
    val paths = artifacts.map(spec => spec.name -> directory.resolve(spec.fileName)).toMap
    artifacts.foreach: spec =>
      requireHash(paths(spec.name), spec.sha256, spec.name)

    println(s"SUB18_COURT directory=$directory mode=${mode.toString.toLowerCase} promotional=false")
    artifacts.foreach(spec => println(s"SUB18_ARTIFACT name=${spec.name} sha256=${spec.sha256}"))

    val moving = load(paths("moving"), "sub-18 moving T1")
    val fixed = load(paths("fixed"), "MNI2009cAsym fixed T1")
    println(s"SUB18_GRID name=moving shape=${moving.shape.mkString("x")} affine=${quoted(matrixString(moving.grid.affine))}")
    println(s"SUB18_GRID name=fixed shape=${fixed.shape.mkString("x")} affine=${quoted(matrixString(fixed.grid.affine))}")
    val fixedMaskVolume = load(paths("fixed_mask"), "MNI2009cAsym fixed mask")
    requireSameGrid(fixed, fixedMaskVolume, "fixed mask")
    val fixedMask = fixedMaskVolume.values.map(_ > 0.0)

    val fixedFrame = Frame[Fixed](SpatialDomainId("hodgeflow-sub18-fixed"), fixed.grid)
    val movingFrame = Frame[Moving](SpatialDomainId("hodgeflow-sub18-moving"), moving.grid)
    val workFrame = Frame[Work](SpatialDomainId("hodgeflow-sub18-work"), fixed.grid)
    val fixedImage = right(
      RegistrationImage.make(
        fixedFrame,
        fixed.volume,
        FieldValidity.copyMask(fixedMask)
      )
    )
    val movingImage = right(RegistrationImage.make(movingFrame, moving.volume))
    val initial = right(ForwardMidpoint.identity(workFrame, fixedFrame, movingFrame))
    val plan = budget2xPlan()

    val bridge = mode match
      case Mode.Oracles => None
      case Mode.Bridge | Mode.All =>
        val config = roundConfig()
        val preflightImages = right(
          right(BasinBridgeFrozenCcObjective.make(fixedImage, movingImage, initial, config.cc)).workImages
        )
        val anchorSet = anchors(preflightImages.fixed)
        val timedBridge = timed:
          right(BasinBridgeRound.run(fixedImage, movingImage, initial, anchorSet, config))
        val result = timedBridge.value
        println(
          s"SUB18_MATCH status=ok anchors=${anchorSet.length} " +
            s"correspondences=${result.evidence.work.values.length}"
        )
        val rematchStatus = result.rematch match
          case Some(search) => s"ok:${search.correspondences.length}"
          case None if !result.assimilation.accepted => "not-run:not-accepted"
          case None => "missing"
        val rematchSupport = result.diagnostics.rematchSupport
        val retainedIndices = rematchSupport.map(_.retainedIndices.mkString(",")).getOrElse("")
        val dropped = rematchSupport
          .map(_.dropped.map(drop => s"${drop.index}:${drop.error.message}").mkString(";"))
          .getOrElse("")
        println(
          s"SUB18_REMATCH status=${quoted(rematchStatus)} " +
            s"requested=${rematchSupport.map(_.requestedAnchors).getOrElse(0)} " +
            s"retained=${rematchSupport.map(_.retainedAnchors).getOrElse(0)} " +
            s"retainedIndices=${quoted(retainedIndices)} dropped=${quoted(dropped)}"
        )
        println(
          s"SUB18_BRIDGE anchors=${anchorSet.length} elapsedMs=${timedBridge.elapsedMillis} " +
            s"accepted=${result.assimilation.accepted} alpha=${result.assimilation.acceptedAlpha} " +
            s"initialMatchMm=${result.assimilation.initialObjective.weightedMatchErrorMm} " +
            s"finalMatchMm=${result.assimilation.finalObjective.weightedMatchErrorMm} " +
            s"initialCcLoss=${result.assimilation.initialObjective.trueCcLoss} " +
            s"finalCcLoss=${result.assimilation.finalObjective.trueCcLoss} rematchStatus=${quoted(rematchStatus)}"
        )
        Some(result -> timedBridge.elapsedMillis)

    val fine = mode match
      case Mode.All =>
        bridge.map { case (bridgeResult, _) =>
          val timedFine = timed(HalfFlowCc.optimize(fixedImage, movingImage, bridgeResult.state, plan))
          timedFine.value match
            case Left(error) =>
              println(s"SUB18_FINE status=failed elapsedMs=${timedFine.elapsedMillis} error=${quoted(error.message)}")
              Left(error.message) -> timedFine.elapsedMillis
            case Right(optimization) =>
              println(
                s"SUB18_FINE status=ok elapsedMs=${timedFine.elapsedMillis} " +
                  s"acceptedSteps=${optimization.diagnostics.acceptedSteps} attempts=${optimization.diagnostics.attempts}"
              )
              Right(optimization.state) -> timedFine.elapsedMillis
        }
      case Mode.Oracles | Mode.Bridge => None

    // Evaluation masks and saved ANTs outputs are deliberately loaded only
    // after optimization so they cannot influence matcher or fine-stage work.
    val movingMaskVolume = load(paths("moving_mask"), "sub-18 evaluation mask")
    requireSameGrid(moving, movingMaskVolume, "moving mask")
    val antsWarped = load(paths("ants_warped"), "saved ANTs warped T1")
    val antsMaskWarped = load(paths("ants_mask_warped"), "saved ANTs warped mask")
    requireSameGrid(fixed, antsWarped, "ANTs warped T1")
    requireSameGrid(fixed, antsMaskWarped, "ANTs warped mask")
    val metricCourt = MetricCourt(fixed.values, fixedMask, fixed.grid)

    val identityPull = right(AffineIso.make(fixedFrame, movingFrame, Affine3D.identity)).dense.forward
    val initialWarped = warp(identityPull, moving.volume, movingMaskVolume.volume)
    val initialMetrics = metricCourt.evaluate(initialWarped._1, initialWarped._2)
    printMetrics("initial", initialMetrics, methodElapsedMillis = 0L, evaluationMillis = 0L)

    val antsMetrics = metricCourt.evaluate(antsWarped.values, antsMaskWarped.values.map(_ > 0.0))
    printMetrics("ants-synquick-fixedmask", antsMetrics, methodElapsedMillis = 47800L, evaluationMillis = 0L)
    assertOracle("saved ANTs", antsMetrics, expectedAnts)
    assertOracle("initial/header-identity", initialMetrics, expectedInitial)

    bridge.foreach { case (bridgeResult, elapsedMillis) =>
      val evaluated = evaluateState(
        bridgeResult.state,
        moving.volume,
        movingMaskVolume.volume,
        metricCourt,
        plan
      )
      printMetrics("basinbridge", evaluated.metrics, elapsedMillis, evaluated.evaluationMillis)
      printStateDiagnostics("basinbridge", evaluated)
    }

    fine.foreach { case (result, elapsedMillis) =>
      result.foreach: state =>
        val evaluated = evaluateState(
          state,
          moving.volume,
          movingMaskVolume.volume,
          metricCourt,
          plan
        )
        printMetrics("basinbridge-budget2x", evaluated.metrics, elapsedMillis, evaluated.evaluationMillis)
        printStateDiagnostics("basinbridge-budget2x", evaluated)
    }

  private def evaluateState[W, F, M](
      state: ForwardMidpoint[W, F, M],
      moving: NeuroVol[Double],
      movingMask: NeuroVol[Double],
      court: MetricCourt,
      plan: HalfFlowCcPlan
  ): StateEvaluation =
    val evaluated = timed:
      val candidate = ForwardMidpointExporter
        .inspect(state, plan.exportConfig)
        .fold(error => throw new IllegalStateException(error.message), identity)
      val admission = ForwardMidpointExporter.admit(candidate, plan.exportConfig)
      val warped = warp(candidate.transform.forward, moving, movingMask)
      val metrics = court.evaluate(warped._1, warped._2)
      val jacobian = jacobianStats(candidate.transform.forward, interiorMargin = 1)
      val maximumInterior = math.max(
        candidate.fixedResidualInverse.maximumInteriorMm,
        candidate.movingResidualInverse.maximumInteriorMm
      )
      val status = admission match
        case Right(_) => "admitted"
        case Left(error) => s"rejected:${error.message}"
      (metrics, jacobian, status, maximumInterior)
    StateEvaluation(
      evaluated.value._1,
      evaluated.value._2,
      evaluated.value._3,
      evaluated.value._4,
      evaluated.elapsedMillis
    )

  private def warp[A, B](
      pull: DensePull[A, B],
      moving: NeuroVol[Double],
      movingMask: NeuroVol[Double]
  ): (Array[Double], Array[Boolean]) =
    val image = HalfFlowKernels.pullScalar(
      moving,
      pull.sourceCoordinates,
      pull.validity,
      FieldValidity.All,
      0.0
    )
    val mask = HalfFlowKernels.pullScalarNearest(
      movingMask,
      pull.sourceCoordinates,
      pull.validity,
      FieldValidity.All,
      0.0
    )
    (image.values.copyLegacyLinear, mask.values.copyLegacyLinear.map(_ > 0.0))

  private final class MetricCourt(
      fixed: Array[Double],
      fixedMask: Array[Boolean],
      grid: GridSpec
  ):
    private val spacing = Affine.voxelSizes(grid.affine)
    private val fixedGradientMagnitude = gradientMagnitude(fixed, grid, spacing)

    def evaluate(warped: Array[Double], warpedMask: Array[Boolean]): AccuracyMetrics =
      require(warped.length == fixed.length, "warped image/fixed size mismatch")
      require(warpedMask.length == fixed.length, "warped mask/fixed size mismatch")
      AccuracyMetrics(
        ncc = ncc(fixed, warped, Some(fixedMask)),
        globalNcc = ncc(fixed, warped, None),
        gradNcc = ncc(
          fixedGradientMagnitude,
          gradientMagnitude(warped, grid, spacing),
          Some(fixedMask)
        ),
        dice = dice(fixedMask, warpedMask),
        comDistanceVox = comDistance(fixedMask, warpedMask, grid)
      )

  private def ncc(a: Array[Double], b: Array[Double], mask: Option[Array[Boolean]]): Double =
    var count = 0
    var sumA = 0.0
    var sumB = 0.0
    var index = 0
    while index < a.length do
      val selected = mask.forall(_(index)) && a(index).isFinite && b(index).isFinite
      if selected then
        count += 1
        sumA += a(index)
        sumB += b(index)
      index += 1
    require(count > 0, "NCC has no finite selected voxels")
    val meanA = sumA / count.toDouble
    val meanB = sumB / count.toDouble
    var cross = 0.0
    var squareA = 0.0
    var squareB = 0.0
    index = 0
    while index < a.length do
      val selected = mask.forall(_(index)) && a(index).isFinite && b(index).isFinite
      if selected then
        val centeredA = a(index) - meanA
        val centeredB = b(index) - meanB
        cross += centeredA * centeredB
        squareA += centeredA * centeredA
        squareB += centeredB * centeredB
      index += 1
    cross / math.sqrt(squareA * squareB + 1e-8)

  private def gradientMagnitude(
      values: Array[Double],
      grid: GridSpec,
      spacing: Vector[Double]
  ): Array[Double] =
    val nx = grid.shape.x
    val ny = grid.shape.y
    val nz = grid.shape.z
    val xy = nx * ny
    val result = new Array[Double](values.length)
    var z = 0
    while z < nz do
      var y = 0
      while y < ny do
        var x = 0
        while x < nx do
          val index = x + nx * y + xy * z
          val gx =
            if nx <= 1 then 0.0
            else if x == 0 then (values(index + 1) - values(index)) / spacing(0)
            else if x == nx - 1 then (values(index) - values(index - 1)) / spacing(0)
            else (values(index + 1) - values(index - 1)) / (2.0 * spacing(0))
          val gy =
            if ny <= 1 then 0.0
            else if y == 0 then (values(index + nx) - values(index)) / spacing(1)
            else if y == ny - 1 then (values(index) - values(index - nx)) / spacing(1)
            else (values(index + nx) - values(index - nx)) / (2.0 * spacing(1))
          val gz =
            if nz <= 1 then 0.0
            else if z == 0 then (values(index + xy) - values(index)) / spacing(2)
            else if z == nz - 1 then (values(index) - values(index - xy)) / spacing(2)
            else (values(index + xy) - values(index - xy)) / (2.0 * spacing(2))
          result(index) = math.sqrt(gx * gx + gy * gy + gz * gz)
          x += 1
        y += 1
      z += 1
    result

  private def dice(a: Array[Boolean], b: Array[Boolean]): Double =
    var countA = 0
    var countB = 0
    var intersection = 0
    var index = 0
    while index < a.length do
      if a(index) then countA += 1
      if b(index) then countB += 1
      if a(index) && b(index) then intersection += 1
      index += 1
    val denominator = countA + countB
    if denominator == 0 then 1.0 else 2.0 * intersection.toDouble / denominator.toDouble

  private def comDistance(a: Array[Boolean], b: Array[Boolean], grid: GridSpec): Double =
    def center(mask: Array[Boolean]): (Double, Double, Double) =
      var count = 0L
      var sumX = 0.0
      var sumY = 0.0
      var sumZ = 0.0
      var index = 0
      while index < mask.length do
        if mask(index) then
          val x = index % grid.shape.x
          val yz = index / grid.shape.x
          val y = yz % grid.shape.y
          val z = yz / grid.shape.y
          count += 1L
          sumX += x.toDouble
          sumY += y.toDouble
          sumZ += z.toDouble
        index += 1
      require(count > 0L, "COM mask is empty")
      (sumX / count.toDouble, sumY / count.toDouble, sumZ / count.toDouble)
    val ca = center(a)
    val cb = center(b)
    val dx = ca._1 - cb._1
    val dy = ca._2 - cb._2
    val dz = ca._3 - cb._3
    math.sqrt(dx * dx + dy * dy + dz * dz)

  private def jacobianStats[A, B](pull: DensePull[A, B], interiorMargin: Int): JacobianStats =
    val grid = pull.from.grid
    val determinants = new Array[Double](grid.nVoxels)
    val valid = new Array[Boolean](grid.nVoxels)
    val reduction = JacobianReduction()
    HalfFlowKernels.jacobianDeterminantsReduceInto(
      pull.sourceCoordinates,
      determinants,
      valid,
      DenseFieldSampler(grid),
      pull.validity,
      reduction,
      interiorMargin
    )
    val eligible = math.max(0, grid.shape.x - 2 * interiorMargin) *
      math.max(0, grid.shape.y - 2 * interiorMargin) *
      math.max(0, grid.shape.z - 2 * interiorMargin)
    val selected = new Array[Double](math.max(1, reduction.evaluated))
    var count = 0
    var index = 0
    while index < determinants.length do
      if valid(index) && determinants(index).isFinite then
        selected(count) = determinants(index)
        count += 1
      index += 1
    require(count == reduction.evaluated && count > 0, "Jacobian reduction/sample mismatch")
    scala.util.Sorting.quickSort(selected)
    def quantile(probability: Double): Double =
      selected(math.floor(probability * (count - 1).toDouble).toInt)
    JacobianStats(
      minimum = selected(0),
      q01 = quantile(0.01),
      q05 = quantile(0.05),
      q50 = quantile(0.50),
      q95 = quantile(0.95),
      q99 = quantile(0.99),
      nonPositive = reduction.nonPositive,
      evaluated = count,
      eligible = eligible,
      validFraction = if eligible == 0 then 0.0 else count.toDouble / eligible.toDouble
    )

  private def anchors(reference: RegistrationImage[?]): Vector[BasinBridgePoint] =
    final case class Candidate(x: Int, y: Int, z: Int, world: Vector[Double])
    val grid = reference.frame.grid
    val fractions = Vector(0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80)
    def positions(extent: Int): Vector[Int] =
      fractions.map(fraction => math.round((extent - 1).toDouble * fraction).toInt).distinct
    val candidates = (for
      x <- positions(grid.shape.x)
      y <- positions(grid.shape.y)
      z <- positions(grid.shape.z)
      world = grid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))
      if referenceSupport(reference, world) == 27
    yield
      Candidate(
        x,
        y,
        z,
        world
      )).toVector
    require(candidates.length >= 8, s"only ${candidates.length} full-support fixed-mask anchors")

    def squaredDistance(left: Candidate, right: Candidate): Double =
      val dx = left.world(0) - right.world(0)
      val dy = left.world(1) - right.world(1)
      val dz = left.world(2) - right.world(2)
      dx * dx + dy * dy + dz * dz
    val center = Candidate(
      grid.shape.x / 2,
      grid.shape.y / 2,
      grid.shape.z / 2,
      grid.voxelToWorld(
        Vector(
          (grid.shape.x - 1).toDouble / 2.0,
          (grid.shape.y - 1).toDouble / 2.0,
          (grid.shape.z - 1).toDouble / 2.0
        )
      )
    )
    var selected = Vector(candidates.minBy(candidate => squaredDistance(candidate, center)))
    val target = math.min(18, candidates.length)
    while selected.length < target do
      val remaining = candidates.filterNot(selected.contains)
      val next = remaining.maxBy: candidate =>
        selected.map(existing => squaredDistance(candidate, existing)).min
      selected = selected :+ next
    selected.map: candidate =>
      BasinBridgePoint.unsafe(candidate.world(0), candidate.world(1), candidate.world(2))

  /** Mirror the matcher's trilinear validity rule without evaluating scores. */
  private def referenceSupport(image: RegistrationImage[?], world: Vector[Double]): Int =
    val grid = image.frame.grid
    val inverse = DMat.invert(grid.affine).fold(
      reason => throw new IllegalStateException(reason),
      identity
    )
    val center = Affine.applyAffine(inverse, world)
    def sampleValid(voxelX: Double, voxelY: Double, voxelZ: Double): Boolean =
      val inside = voxelX.isFinite && voxelY.isFinite && voxelZ.isFinite &&
        voxelX >= 0.0 && voxelX <= grid.shape.x.toDouble - 1.0 &&
        voxelY >= 0.0 && voxelY <= grid.shape.y.toDouble - 1.0 &&
        voxelZ >= 0.0 && voxelZ <= grid.shape.z.toDouble - 1.0
      if !inside then false
      else
        val x0 = math.floor(voxelX).toInt
        val y0 = math.floor(voxelY).toInt
        val z0 = math.floor(voxelZ).toInt
        val x1 = math.min(grid.shape.x - 1, x0 + 1)
        val y1 = math.min(grid.shape.y - 1, y0 + 1)
        val z1 = math.min(grid.shape.z - 1, z0 + 1)
        val fx = voxelX - x0.toDouble
        val fy = voxelY - y0.toDouble
        val fz = voxelZ - z0.toDouble
        var valid = true
        var dz = 0
        while dz <= 1 && valid do
          val z = if dz == 0 then z0 else z1
          val wz = if dz == 0 then 1.0 - fz else fz
          var dy = 0
          while dy <= 1 && valid do
            val y = if dy == 0 then y0 else y1
            val wy = if dy == 0 then 1.0 - fy else fy
            var dx = 0
            while dx <= 1 && valid do
              val x = if dx == 0 then x0 else x1
              val wx = if dx == 0 then 1.0 - fx else fx
              if wx * wy * wz != 0.0 then
                val linear = x + grid.shape.x * y + grid.shape.x * grid.shape.y * z
                valid = image.validity.contains(linear) && image.volume(x, y, z).isFinite
              dx += 1
            dy += 1
          dz += 1
        valid
    var support = 0
    var dz = -1
    while dz <= 1 do
      var dy = -1
      while dy <= 1 do
        var dx = -1
        while dx <= 1 do
          if sampleValid(center(0) + dx, center(1) + dy, center(2) + dz) then support += 1
          dx += 1
        dy += 1
      dz += 1
    support

  private def roundConfig(): BasinBridgeRoundConfig =
    val search = right(
      BasinBridgeBlockSearchConfig.make(
        VoxelWindowRadius(1, 1, 1),
        VoxelWindowRadius(8, 8, 8),
        minimumValidFraction = 0.75
      )
    )
    BasinBridgeRoundConfig.make(
      search,
      projector = BasinBridgeProjectorConfig.default,
      cc = BasinBridgeCcObjectiveConfig.default
    )

  private def budget2xPlan(): HalfFlowCcPlan =
    val cc = right(
      NeighborhoodCcConfig.make(
        radius = VoxelWindowRadius(2, 2, 2),
        minimumSupportFraction = 0.15,
        fullSupportFraction = 0.7,
        minimumVarianceFraction = 1e-7,
        fullVarianceFraction = 1e-5,
        denominatorEpsilonFraction = 1e-7
      )
    )
    val levels = Vector(
      right(
        HalfFlowCcLevel.make(
          shrink = 2,
          pyramidSigmaMm = 1.0,
          cc = cc,
          smoothSigmaMm = 10.0,
          maximumStepMm = 1.0,
          targetAcceptedSteps = 10,
          maximumAttempts = 24
        )
      ),
      right(
        HalfFlowCcLevel.make(
          shrink = 1,
          pyramidSigmaMm = 0.0,
          cc = cc,
          smoothSigmaMm = 6.0,
          maximumStepMm = 0.6,
          targetAcceptedSteps = 10,
          maximumAttempts = 24
        )
      )
    )
    val control = right(
      HalfFlowCcControlConfig.make(
        initialDamping = 1e-4,
        minimumDamping = 1e-8,
        maximumDamping = 1.0,
        maximumObjectiveRetries = 6,
        maximumGeometryRetries = 8,
        maximumIntegrationRetries = 5
      )
    )
    val exportConfig = right(
      ResidualInverseConfig.make(
        shrinks = Vector(2, 1),
        iterationsPerLevel = 50,
        maximumInteriorErrorMm = 0.2,
        maximumInteriorErrorVox = 0.2,
        interiorMargin = 6
      )
    )
    right(
      HalfFlowCcPlan.make(
        levels,
        supportSigmaMm = 1.5,
        minimumUsefulStepMm = 1e-6,
        maximumIntegrationInverseErrorMm = 0.03,
        control = control,
        exportConfig = exportConfig,
        action = HalfFlowCcAction.SymmetricMidpoint
      )
    )

  private def assertOracle(label: String, actual: AccuracyMetrics, expected: AccuracyMetrics): Unit =
    val pairs = Vector(
      "ncc" -> (actual.ncc, expected.ncc),
      "global_ncc" -> (actual.globalNcc, expected.globalNcc),
      "grad_ncc" -> (actual.gradNcc, expected.gradNcc),
      "dice" -> (actual.dice, expected.dice),
      "com_distance_vox" -> (actual.comDistanceVox, expected.comDistanceVox)
    )
    pairs.foreach { case (name, (observed, reference)) =>
      val tolerance = 1e-6 + 1e-6 * math.abs(reference)
      require(
        math.abs(observed - reference) <= tolerance,
        s"$label $name differential mismatch: observed=$observed reference=$reference tolerance=$tolerance"
      )
    }
    println(s"SUB18_ORACLE label=${quoted(label)} status=matched tolerance=abs1e-6+rel1e-6")

  private def printMetrics(
      method: String,
      metrics: AccuracyMetrics,
      methodElapsedMillis: Long,
      evaluationMillis: Long
  ): Unit =
    println(
      s"SUB18_METRICS method=$method ncc=${metrics.ncc} globalNcc=${metrics.globalNcc} " +
        s"gradNcc=${metrics.gradNcc} dice=${metrics.dice} comDistanceVox=${metrics.comDistanceVox} " +
        s"methodElapsedMs=$methodElapsedMillis evaluationElapsedMs=$evaluationMillis"
    )

  private def printStateDiagnostics(method: String, evaluated: StateEvaluation): Unit =
    val jacobian = evaluated.jacobian
    println(
      s"SUB18_STATE method=$method exportStatus=${quoted(evaluated.exportStatus)} " +
        s"exportMaximumInteriorMm=${evaluated.exportMaximumInteriorMm} " +
        s"jacMin=${jacobian.minimum} jacQ01=${jacobian.q01} jacQ05=${jacobian.q05} " +
        s"jacQ50=${jacobian.q50} jacQ95=${jacobian.q95} jacQ99=${jacobian.q99} " +
        s"jacNonPositive=${jacobian.nonPositive} jacEvaluated=${jacobian.evaluated} " +
        s"jacEligible=${jacobian.eligible} jacValidFraction=${jacobian.validFraction}"
    )

  private def load(path: Path, label: String): LoadedVolume =
    Nifti.readScaledDouble(path).fold(
      error => throw new IllegalStateException(s"$label NIfTI read failed: ${error.message}"),
      decoded =>
        decoded.image.fold(
          _ => throw new IllegalStateException(s"$label must be a 3D scalar NIfTI"),
          d3 =>
            val shape = d3.value.sampleSpace.grid.shape
            val affine = DMat.fromRowMajorOwned(4, 4, decoded.affineSelection.affine.rowMajor.toArray)
            val grid = GridSpec(shape, affine)
            require(d3.value.nonSpatialAxes.size == 0, s"$label must not contain non-spatial axes")
            val data3 = d3.value.data.reshapeView(ravel.Shape(shape(0), shape(1), shape(2)))
            val volume = NeuroVol.fromRavel(data3, grid.toNeuroSpace, label)
            val values = volume.copyLegacyLinear
            require(values.length == grid.nVoxels, s"$label decoded size mismatch")
            require(values.forall(_.isFinite), s"$label contains non-finite values")
            LoadedVolume(
              path,
              shape,
              grid,
              values,
              volume
            )
        )
    )

  private def requireSameGrid(reference: LoadedVolume, candidate: LoadedVolume, label: String): Unit =
    require(candidate.shape == reference.shape, s"$label shape ${candidate.shape} != ${reference.shape}")
    require(candidate.grid == reference.grid, s"$label physical grid differs from ${reference.path.getFileName}")

  private def discover(): Option[Path] =
    val explicit = sys.env.get("HF_SUB18_SYNQUICK_INPUT_DIR").toVector.map(Paths.get(_))
    val roots = sys.env.get("HODGEFLOW_ROOT").toVector.map(Paths.get(_)) ++
      Vector(Paths.get("/Users/bbuchsbaum/code/hodgeflow"))
    val conventional = roots.map(
      _.resolve(Paths.get("tmp", "sub18_hodgeflow_vs_ants", "ants_synquick_softbrain_fixedmask"))
    )
    (explicit ++ conventional)
      .map(_.toAbsolutePath.normalize())
      .distinct
      .find(directory => artifacts.forall(spec => Files.isRegularFile(directory.resolve(spec.fileName))))

  private def requireHash(path: Path, expected: String, label: String): Unit =
    val actual = sha256(path)
    require(actual == expected, s"$label hash changed: expected=$expected actual=$actual path=$path")

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    try
      val buffer = new Array[Byte](1024 * 1024)
      var count = input.read(buffer)
      while count >= 0 do
        if count > 0 then digest.update(buffer, 0, count)
        count = input.read(buffer)
      digest.digest().iterator.map(value => f"${value & 0xff}%02x").mkString
    finally input.close()

  private def timed[A](body: => A): Timed[A] =
    val started = System.nanoTime()
    val value = body
    Timed(value, (System.nanoTime() - started) / 1000000L)

  private def right[A](value: Either[?, A]): A =
    value.fold(error => throw new IllegalStateException(error.toString), identity)

  private def quoted(value: String): String =
    s"\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

  private def matrixString(matrix: DMat): String =
    (for
      row <- 0 until matrix.rows
      column <- 0 until matrix.cols
    yield matrix(row, column)).mkString(",")
