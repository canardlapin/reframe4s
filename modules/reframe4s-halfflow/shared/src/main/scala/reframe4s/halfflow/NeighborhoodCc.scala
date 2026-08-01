package reframe4s.halfflow

import reframe4s.halfflow.internal.*

final case class NeighborhoodCcConfig private (
    radius: VoxelWindowRadius,
    minimumSupportFraction: Double,
    fullSupportFraction: Double,
    minimumVarianceFraction: Double,
    fullVarianceFraction: Double,
    denominatorEpsilonFraction: Double
)

object NeighborhoodCcConfig:
  def make(
      radius: VoxelWindowRadius = VoxelWindowRadius(2, 2, 2),
      minimumSupportFraction: Double = 0.25,
      fullSupportFraction: Double = 0.75,
      minimumVarianceFraction: Double = 1e-4,
      fullVarianceFraction: Double = 1e-3,
      denominatorEpsilonFraction: Double = 1e-6
  ): Either[RegistrationError, NeighborhoodCcConfig] =
    if !unitInterval(minimumSupportFraction) || !unitInterval(fullSupportFraction) then
      Left(RegistrationError.InvalidConfiguration("neighborhood CC support fractions"))
    else if fullSupportFraction <= minimumSupportFraction then
      Left(RegistrationError.InvalidConfiguration("neighborhood CC full support fraction"))
    else if !minimumVarianceFraction.isFinite || minimumVarianceFraction < 0.0 then
      Left(RegistrationError.InvalidConfiguration("neighborhood CC minimum variance fraction"))
    else if !fullVarianceFraction.isFinite || fullVarianceFraction <= minimumVarianceFraction then
      Left(RegistrationError.InvalidConfiguration("neighborhood CC full variance fraction"))
    else if !denominatorEpsilonFraction.isFinite || denominatorEpsilonFraction <= 0.0 then
      Left(RegistrationError.InvalidConfiguration("neighborhood CC denominator epsilon fraction"))
    else
      Right(
        new NeighborhoodCcConfig(
          radius,
          minimumSupportFraction,
          fullSupportFraction,
          minimumVarianceFraction,
          fullVarianceFraction,
          denominatorEpsilonFraction
        )
      )

  val default: NeighborhoodCcConfig =
    make().fold(error => throw new IllegalStateException(error.message), identity)

  private def unitInterval(value: Double): Boolean =
    value.isFinite && value >= 0.0 && value <= 1.0

final case class CcSupportDiagnostics(
    minimumFraction: Double,
    meanFraction: Double,
    maximumFraction: Double,
    activeFraction: Double,
    edgeBandActiveFraction: Double
)

final case class CcVarianceDiagnostics(
    fixedGlobalVariance: Double,
    movingGlobalVariance: Double,
    fixedReliabilityMean: Double,
    movingReliabilityMean: Double,
    jointReliabilityMean: Double
)

final case class NeighborhoodCcDiagnostics(
    loss: Double,
    activeWindows: Int,
    totalWindows: Int,
    support: CcSupportDiagnostics,
    variance: CcVarianceDiagnostics
)

/** Immutable-for-one-trial IRLS snapshot.
  *
  * Support, reliability weights, and denominator stabilization are frozen while
  * a derivative or candidate loss is evaluated. A caller may prepare a fresh
  * snapshot only after accepting an update.
  */
final class FrozenCcWeights private[halfflow] (
    val grid: GridSpec,
    val config: NeighborhoodCcConfig,
    private[halfflow] val support: Array[Double],
    private[halfflow] val weight: Array[Double],
    private[halfflow] val epsilon: Array[Double],
    val activeWindows: Int,
    val supportDiagnostics: CcSupportDiagnostics,
    val varianceDiagnostics: CcVarianceDiagnostics
)

final case class NeighborhoodCcEvaluation(
    loss: Double,
    fixedIntensityGradient: Array[Double],
    movingIntensityGradient: Array[Double],
    diagnostics: NeighborhoodCcDiagnostics
)

final class NeighborhoodCcBuffer private (
    val grid: GridSpec,
    private[halfflow] val fixedGradient: Array[Double],
    private[halfflow] val movingGradient: Array[Double]
)

object NeighborhoodCcBuffer:
  def apply(grid: GridSpec): NeighborhoodCcBuffer =
    new NeighborhoodCcBuffer(
      grid,
      PrimitiveBuffers.ofSize[Double](grid.nVoxels),
      PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    )

final class NeighborhoodCcWorkspace private (
    val grid: GridSpec,
    private[halfflow] val count: Array[Double],
    private[halfflow] val meanFixed: Array[Double],
    private[halfflow] val meanMoving: Array[Double],
    private[halfflow] val covariance: Array[Double],
    private[halfflow] val fixedScatter: Array[Double],
    private[halfflow] val movingScatter: Array[Double],
    private[halfflow] val scratch: Array[Double],
    private[halfflow] val box: BoxSumWorkspace
):
  val ownedScalarBuffers: Int = 9

object NeighborhoodCcWorkspace:
  def apply(grid: GridSpec): NeighborhoodCcWorkspace =
    val n = grid.nVoxels
    new NeighborhoodCcWorkspace(
      grid,
      PrimitiveBuffers.ofSize[Double](n),
      PrimitiveBuffers.ofSize[Double](n),
      PrimitiveBuffers.ofSize[Double](n),
      PrimitiveBuffers.ofSize[Double](n),
      PrimitiveBuffers.ofSize[Double](n),
      PrimitiveBuffers.ofSize[Double](n),
      PrimitiveBuffers.ofSize[Double](n),
      BoxSumWorkspace(grid)
    )

/** Squared, overlapping-window neighborhood correlation with an adjoint-box derivative. */
object NeighborhoodCc:
  def prepare(
      fixed: Array[Double],
      moving: Array[Double],
      support: Array[Double],
      grid: GridSpec,
      config: NeighborhoodCcConfig = NeighborhoodCcConfig.default
  ): Either[RegistrationError, FrozenCcWeights] =
    prepareWith(fixed, moving, support, grid, config, NeighborhoodCcWorkspace(grid))

  def prepareWith(
      fixed: Array[Double],
      moving: Array[Double],
      support: Array[Double],
      grid: GridSpec,
      config: NeighborhoodCcConfig,
      workspace: NeighborhoodCcWorkspace
  ): Either[RegistrationError, FrozenCcWeights] =
    validateInputs(fixed, moving, support, grid, workspace).map: _ =>
      statisticsInto(fixed, moving, support, grid, config.radius, workspace)
      val (fixedVariance, movingVariance) = globalVariances(fixed, moving, support, grid.nVoxels)
      val frozenSupport = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
      val weights = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
      val epsilons = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
      var supportMinimum = Double.PositiveInfinity
      var supportMaximum = 0.0
      var supportTotal = 0.0
      var fixedReliabilityTotal = 0.0
      var movingReliabilityTotal = 0.0
      var jointReliabilityTotal = 0.0
      var rawWeightTotal = 0.0
      var active = 0
      var edge = 0
      var edgeActive = 0
      var index = 0
      while index < grid.nVoxels do
        val available = availableWindowSamples(grid, config.radius, index).toDouble
        val count = workspace.count(index)
        val supportFraction = if available > 0.0 then count / available else 0.0
        val supportReliability = smoothStep(
          supportFraction,
          config.minimumSupportFraction,
          config.fullSupportFraction
        )
        val fixedLocalVariance = if count > 0.0 then workspace.fixedScatter(index) / count else 0.0
        val movingLocalVariance = if count > 0.0 then workspace.movingScatter(index) / count else 0.0
        val fixedReliability = varianceReliability(fixedLocalVariance, fixedVariance, config)
        val movingReliability = varianceReliability(movingLocalVariance, movingVariance, config)
        val jointReliability = supportReliability * fixedReliability * movingReliability
        frozenSupport(index) = support(index)
        weights(index) = jointReliability
        epsilons(index) = math.max(
          Double.MinPositiveValue,
          config.denominatorEpsilonFraction * count * count * fixedVariance * movingVariance
        )
        supportMinimum = math.min(supportMinimum, supportFraction)
        supportMaximum = math.max(supportMaximum, supportFraction)
        supportTotal += supportFraction
        fixedReliabilityTotal += fixedReliability
        movingReliabilityTotal += movingReliability
        jointReliabilityTotal += jointReliability
        rawWeightTotal += jointReliability
        if jointReliability > 0.0 then active += 1
        if inEdgeBand(grid, config.radius, index) then
          edge += 1
          if jointReliability > 0.0 then edgeActive += 1
        index += 1
      if rawWeightTotal > 0.0 then
        index = 0
        while index < weights.length do
          weights(index) /= rawWeightTotal
          index += 1
      val total = grid.nVoxels.toDouble
      new FrozenCcWeights(
        grid,
        config,
        frozenSupport,
        weights,
        epsilons,
        active,
        CcSupportDiagnostics(
          if grid.nVoxels > 0 then supportMinimum else Double.NaN,
          supportTotal / total,
          if grid.nVoxels > 0 then supportMaximum else Double.NaN,
          active.toDouble / total,
          if edge > 0 then edgeActive.toDouble / edge.toDouble else Double.NaN
        ),
        CcVarianceDiagnostics(
          fixedVariance,
          movingVariance,
          fixedReliabilityTotal / total,
          movingReliabilityTotal / total,
          jointReliabilityTotal / total
        )
      )

  def value(
      fixed: Array[Double],
      moving: Array[Double],
      frozen: FrozenCcWeights
  ): Either[RegistrationError, Double] =
    valueWith(fixed, moving, frozen, NeighborhoodCcWorkspace(frozen.grid))

  def valueWith(
      fixed: Array[Double],
      moving: Array[Double],
      frozen: FrozenCcWeights,
      workspace: NeighborhoodCcWorkspace
  ): Either[RegistrationError, Double] =
    validateFrozenInputs(fixed, moving, frozen, workspace).map: _ =>
      statisticsInto(fixed, moving, frozen.support, frozen.grid, frozen.config.radius, workspace)
      lossAndCoefficients(frozen, workspace, writeCoefficients = false)

  def valueAndGradient(
      fixed: Array[Double],
      moving: Array[Double],
      frozen: FrozenCcWeights
  ): Either[RegistrationError, NeighborhoodCcEvaluation] =
    valueAndGradientWith(
      fixed,
      moving,
      frozen,
      NeighborhoodCcWorkspace(frozen.grid),
      NeighborhoodCcBuffer(frozen.grid)
    )

  /** Evaluate the frozen objective and write exact conditional intensity gradients.
    *
    * The returned arrays borrow `destination` until its next reuse.
    */
  def valueAndGradientWith(
      fixed: Array[Double],
      moving: Array[Double],
      frozen: FrozenCcWeights,
      workspace: NeighborhoodCcWorkspace,
      destination: NeighborhoodCcBuffer
  ): Either[RegistrationError, NeighborhoodCcEvaluation] =
    validateFrozenInputs(fixed, moving, frozen, workspace).flatMap: _ =>
      if destination.grid != frozen.grid then Left(RegistrationError.GridMismatch("neighborhood CC destination"))
      else
        statisticsInto(fixed, moving, frozen.support, frozen.grid, frozen.config.radius, workspace)
        val loss = lossAndCoefficients(frozen, workspace, writeCoefficients = true)
        adjointGradientInto(fixed, moving, frozen, workspace, destination)
        val diagnostics = NeighborhoodCcDiagnostics(
          loss,
          frozen.activeWindows,
          frozen.grid.nVoxels,
          frozen.supportDiagnostics,
          frozen.varianceDiagnostics
        )
        Right(
          NeighborhoodCcEvaluation(
            loss,
            destination.fixedGradient,
            destination.movingGradient,
            diagnostics
          )
        )

  private def statisticsInto(
      fixed: Array[Double],
      moving: Array[Double],
      support: Array[Double],
      grid: GridSpec,
      radius: VoxelWindowRadius,
      workspace: NeighborhoodCcWorkspace
  ): Unit =
    fill(workspace.scratch, grid.nVoxels)(index => support(index))
    BoxSum3D.sumInto(workspace.scratch, grid, radius, workspace.count, workspace.box)
    fill(workspace.scratch, grid.nVoxels)(index => support(index) * fixed(index))
    BoxSum3D.sumInto(workspace.scratch, grid, radius, workspace.meanFixed, workspace.box)
    fill(workspace.scratch, grid.nVoxels)(index => support(index) * moving(index))
    BoxSum3D.sumInto(workspace.scratch, grid, radius, workspace.meanMoving, workspace.box)
    fill(workspace.scratch, grid.nVoxels)(index => support(index) * fixed(index) * fixed(index))
    BoxSum3D.sumInto(workspace.scratch, grid, radius, workspace.fixedScatter, workspace.box)
    fill(workspace.scratch, grid.nVoxels)(index => support(index) * moving(index) * moving(index))
    BoxSum3D.sumInto(workspace.scratch, grid, radius, workspace.movingScatter, workspace.box)
    fill(workspace.scratch, grid.nVoxels)(index => support(index) * fixed(index) * moving(index))
    BoxSum3D.sumInto(workspace.scratch, grid, radius, workspace.covariance, workspace.box)
    var index = 0
    while index < grid.nVoxels do
      val count = workspace.count(index)
      if count > 0.0 then
        val sumFixed = workspace.meanFixed(index)
        val sumMoving = workspace.meanMoving(index)
        workspace.meanFixed(index) = sumFixed / count
        workspace.meanMoving(index) = sumMoving / count
        workspace.fixedScatter(index) = math.max(0.0, workspace.fixedScatter(index) - sumFixed * sumFixed / count)
        workspace.movingScatter(index) = math.max(0.0, workspace.movingScatter(index) - sumMoving * sumMoving / count)
        workspace.covariance(index) -= sumFixed * sumMoving / count
      else
        workspace.meanFixed(index) = 0.0
        workspace.meanMoving(index) = 0.0
        workspace.fixedScatter(index) = 0.0
        workspace.movingScatter(index) = 0.0
        workspace.covariance(index) = 0.0
      index += 1

  /** With coefficients enabled: fixedScatter=P, movingScatter=Qf, covariance=Qm. */
  private def lossAndCoefficients(
      frozen: FrozenCcWeights,
      workspace: NeighborhoodCcWorkspace,
      writeCoefficients: Boolean
  ): Double =
    var loss = 0.0
    var index = 0
    while index < frozen.grid.nVoxels do
      val omega = frozen.weight(index)
      val covariance = workspace.covariance(index)
      val fixedScatter = workspace.fixedScatter(index)
      val movingScatter = workspace.movingScatter(index)
      val denominator = fixedScatter * movingScatter + frozen.epsilon(index)
      if omega > 0.0 && denominator.isFinite && denominator > 0.0 then
        val squared = covariance * covariance
        loss += omega * (1.0 - squared / denominator)
        if writeCoefficients then
          val inverse = 1.0 / denominator
          workspace.fixedScatter(index) = -2.0 * omega * covariance * inverse
          workspace.movingScatter(index) = 2.0 * omega * squared * movingScatter * inverse * inverse
          workspace.covariance(index) = 2.0 * omega * squared * fixedScatter * inverse * inverse
      else if writeCoefficients then
        workspace.fixedScatter(index) = 0.0
        workspace.movingScatter(index) = 0.0
        workspace.covariance(index) = 0.0
      index += 1
    loss

  private def adjointGradientInto(
      fixed: Array[Double],
      moving: Array[Double],
      frozen: FrozenCcWeights,
      workspace: NeighborhoodCcWorkspace,
      destination: NeighborhoodCcBuffer
  ): Unit =
    val grid = frozen.grid
    val n = grid.nVoxels
    val p = workspace.fixedScatter
    val qFixed = workspace.movingScatter
    val qMoving = workspace.covariance
    clear(destination.fixedGradient, n)
    clear(destination.movingGradient, n)

    boxAndAccumulate(p, grid, frozen, workspace): (index, boxed) =>
      destination.fixedGradient(index) += frozen.support(index) * moving(index) * boxed
      destination.movingGradient(index) += frozen.support(index) * fixed(index) * boxed

    fill(workspace.scratch, n)(index => p(index) * workspace.meanMoving(index))
    boxAndAccumulate(workspace.scratch, grid, frozen, workspace): (index, boxed) =>
      destination.fixedGradient(index) -= frozen.support(index) * boxed

    fill(workspace.scratch, n)(index => p(index) * workspace.meanFixed(index))
    boxAndAccumulate(workspace.scratch, grid, frozen, workspace): (index, boxed) =>
      destination.movingGradient(index) -= frozen.support(index) * boxed

    boxAndAccumulate(qFixed, grid, frozen, workspace): (index, boxed) =>
      destination.fixedGradient(index) += frozen.support(index) * fixed(index) * boxed

    fill(workspace.scratch, n)(index => qFixed(index) * workspace.meanFixed(index))
    boxAndAccumulate(workspace.scratch, grid, frozen, workspace): (index, boxed) =>
      destination.fixedGradient(index) -= frozen.support(index) * boxed

    boxAndAccumulate(qMoving, grid, frozen, workspace): (index, boxed) =>
      destination.movingGradient(index) += frozen.support(index) * moving(index) * boxed

    fill(workspace.scratch, n)(index => qMoving(index) * workspace.meanMoving(index))
    boxAndAccumulate(workspace.scratch, grid, frozen, workspace): (index, boxed) =>
      destination.movingGradient(index) -= frozen.support(index) * boxed

  private def boxAndAccumulate(
      source: Array[Double],
      grid: GridSpec,
      frozen: FrozenCcWeights,
      workspace: NeighborhoodCcWorkspace
  )(accumulate: (Int, Double) => Unit): Unit =
    BoxSum3D.sumInto(source, grid, frozen.config.radius, workspace.count, workspace.box)
    var index = 0
    while index < grid.nVoxels do
      accumulate(index, workspace.count(index))
      index += 1

  private def validateInputs(
      fixed: Array[Double],
      moving: Array[Double],
      support: Array[Double],
      grid: GridSpec,
      workspace: NeighborhoodCcWorkspace
  ): Either[RegistrationError, Unit] =
    if workspace.grid != grid then Left(RegistrationError.GridMismatch("neighborhood CC workspace"))
    else if fixed.length != grid.nVoxels || moving.length != grid.nVoxels || support.length != grid.nVoxels then
      Left(RegistrationError.InvalidField("neighborhood CC input lengths"))
    else
      var valid = true
      var index = 0
      while index < grid.nVoxels && valid do
        valid = fixed(index).isFinite && moving(index).isFinite && support(index).isFinite &&
          support(index) >= 0.0 && support(index) <= 1.0
        index += 1
      if valid then Right(()) else Left(RegistrationError.InvalidField("neighborhood CC input values"))

  private def validateFrozenInputs(
      fixed: Array[Double],
      moving: Array[Double],
      frozen: FrozenCcWeights,
      workspace: NeighborhoodCcWorkspace
  ): Either[RegistrationError, Unit] =
    if workspace.grid != frozen.grid then Left(RegistrationError.GridMismatch("neighborhood CC workspace"))
    else if fixed.length != frozen.grid.nVoxels || moving.length != frozen.grid.nVoxels then
      Left(RegistrationError.InvalidField("neighborhood CC input lengths"))
    else
      var finite = true
      var index = 0
      while index < frozen.grid.nVoxels && finite do
        finite = fixed(index).isFinite && moving(index).isFinite
        index += 1
      if finite then Right(()) else Left(RegistrationError.InvalidField("neighborhood CC input values"))

  private def globalVariances(
      fixed: Array[Double],
      moving: Array[Double],
      support: Array[Double],
      size: Int
  ): (Double, Double) =
    var totalWeight = 0.0
    var fixedTotal = 0.0
    var movingTotal = 0.0
    var index = 0
    while index < size do
      val weight = support(index)
      totalWeight += weight
      fixedTotal += weight * fixed(index)
      movingTotal += weight * moving(index)
      index += 1
    if totalWeight <= 0.0 then (0.0, 0.0)
    else
      val fixedMean = fixedTotal / totalWeight
      val movingMean = movingTotal / totalWeight
      var fixedScatter = 0.0
      var movingScatter = 0.0
      index = 0
      while index < size do
        val weight = support(index)
        val df = fixed(index) - fixedMean
        val dm = moving(index) - movingMean
        fixedScatter += weight * df * df
        movingScatter += weight * dm * dm
        index += 1
      (fixedScatter / totalWeight, movingScatter / totalWeight)

  private def varianceReliability(
      localVariance: Double,
      globalVariance: Double,
      config: NeighborhoodCcConfig
  ): Double =
    if globalVariance <= 0.0 then 0.0
    else
      smoothStep(
        localVariance / globalVariance,
        config.minimumVarianceFraction,
        config.fullVarianceFraction
      )

  private def smoothStep(value: Double, minimum: Double, maximum: Double): Double =
    if value <= minimum then 0.0
    else if value >= maximum then 1.0
    else
      val x = (value - minimum) / (maximum - minimum)
      x * x * (3.0 - 2.0 * x)

  private def availableWindowSamples(grid: GridSpec, radius: VoxelWindowRadius, index: Int): Int =
    val x = index % grid.shape.x
    val yz = index / grid.shape.x
    val y = yz % grid.shape.y
    val z = yz / grid.shape.y
    val nx = math.min(grid.shape.x - 1, x + radius.x) - math.max(0, x - radius.x) + 1
    val ny = math.min(grid.shape.y - 1, y + radius.y) - math.max(0, y - radius.y) + 1
    val nz = math.min(grid.shape.z - 1, z + radius.z) - math.max(0, z - radius.z) + 1
    nx * ny * nz

  private def inEdgeBand(grid: GridSpec, radius: VoxelWindowRadius, index: Int): Boolean =
    val x = index % grid.shape.x
    val yz = index / grid.shape.x
    val y = yz % grid.shape.y
    val z = yz / grid.shape.y
    x <= radius.x || x >= grid.shape.x - radius.x - 1 ||
      y <= radius.y || y >= grid.shape.y - radius.y - 1 ||
      z <= radius.z || z >= grid.shape.z - radius.z - 1

  private inline def fill(values: Array[Double], size: Int)(inline value: Int => Double): Unit =
    var index = 0
    while index < size do
      values(index) = value(index)
      index += 1

  private def clear(values: Array[Double], size: Int): Unit =
    var index = 0
    while index < size do
      values(index) = 0.0
      index += 1
