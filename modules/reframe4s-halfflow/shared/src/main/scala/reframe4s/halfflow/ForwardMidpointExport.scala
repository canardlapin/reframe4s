package reframe4s.halfflow

import reframe4s.halfflow.internal.*

final case class ResidualInverseConfig private (
    shrinks: Vector[Int],
    iterationsPerLevel: Int,
    relaxation: Double,
    iterationToleranceMm: Double,
    maximumInteriorErrorMm: Double,
    maximumInteriorErrorVox: Double,
    minimumInteriorValidFraction: Double,
    interiorMargin: Int
)

object ResidualInverseConfig:
  def make(
      shrinks: Vector[Int] = Vector(4, 2, 1),
      iterationsPerLevel: Int = 40,
      relaxation: Double = 0.8,
      iterationToleranceMm: Double = 1e-3,
      maximumInteriorErrorMm: Double = 0.10,
      maximumInteriorErrorVox: Double = 0.10,
      minimumInteriorValidFraction: Double = 0.95,
      interiorMargin: Int = 2
  ): Either[ForwardExportError, ResidualInverseConfig] =
    val ordered = shrinks.nonEmpty && shrinks.last == 1 &&
      shrinks.indices.drop(1).forall(index => shrinks(index - 1) > shrinks(index))
    val numeric =
      relaxation.isFinite && relaxation > 0.0 && relaxation <= 1.0 &&
        iterationToleranceMm.isFinite && iterationToleranceMm > 0.0 &&
        maximumInteriorErrorMm.isFinite && maximumInteriorErrorMm >= 0.0 &&
        maximumInteriorErrorVox.isFinite && maximumInteriorErrorVox >= 0.0 &&
        minimumInteriorValidFraction.isFinite && minimumInteriorValidFraction > 0.0 &&
        minimumInteriorValidFraction <= 1.0
    if !ordered then Left(ForwardExportError.InvalidConfiguration("inverse shrinks must descend and end at one"))
    else if iterationsPerLevel <= 0 || interiorMargin < 0 then
      Left(ForwardExportError.InvalidConfiguration("inverse iteration or margin"))
    else if !numeric then Left(ForwardExportError.InvalidConfiguration("inverse numerical bounds"))
    else
      Right(
        new ResidualInverseConfig(
          shrinks,
          iterationsPerLevel,
          relaxation,
          iterationToleranceMm,
          maximumInteriorErrorMm,
          maximumInteriorErrorVox,
          minimumInteriorValidFraction,
          interiorMargin
        )
      )

  val default: ResidualInverseConfig =
    make().fold(error => throw new IllegalStateException(error.message), identity)

final case class InverseErrorPercentiles(
    evaluated: Int,
    eligible: Int,
    validFraction: Double,
    p50Mm: Double,
    p95Mm: Double,
    p99Mm: Double,
    maximumMm: Double,
    maximumIndex: Int,
    maximumVox: Double,
    allMaximumMm: Double
)

final case class ResidualInverseReport(
    forwardThenInverse: InverseErrorPercentiles,
    inverseThenForward: InverseErrorPercentiles,
    iterationsByLevel: Vector[Int]
):
  val maximumInteriorMm: Double =
    math.max(forwardThenInverse.maximumMm, inverseThenForward.maximumMm)

  val maximumInteriorVox: Double =
    math.max(forwardThenInverse.maximumVox, inverseThenForward.maximumVox)

final case class RefinedResidualInverse[A](
    inverse: DensePull[A, A],
    report: ResidualInverseReport
)

enum ForwardExportError:
  case InvalidConfiguration(context: String)
  case Registration(error: RegistrationError)
  case InverseDidNotConverge(arm: MidpointArmName, report: ResidualInverseReport)
  case ExportedTopologyInvalid(direction: ExportDirection, minimumJacobian: Double, nonPositive: Int)

  def message: String =
    this match
      case InvalidConfiguration(context) => s"invalid forward-midpoint export configuration: $context"
      case Registration(error) => error.message
      case InverseDidNotConverge(arm, report) =>
        s"$arm residual inverse missed tolerance: ${report.maximumInteriorMm} mm, ${report.maximumInteriorVox} vox"
      case ExportedTopologyInvalid(direction, minimum, nonPositive) =>
        s"$direction export contains $nonPositive non-positive Jacobians (minimum $minimum)"

enum ExportDirection:
  case FixedToMoving
  case MovingToFixed

final case class ForwardMidpointExport[F, M](
    transform: InversePair[F, M],
    fixedResidualInverse: ResidualInverseReport,
    movingResidualInverse: ResidualInverseReport,
    endpointRoundTrip: InversePairSummary
)

/** Fully constructed export candidate whose numerical/topological admission is still pending. */
final case class ForwardMidpointExportCandidate[F, M](
    transform: InversePair[F, M],
    fixedResidualInverse: ResidualInverseReport,
    movingResidualInverse: ResidualInverseReport,
    endpointRoundTrip: InversePairSummary
)

object ResidualInverseRefiner:
  def refine[A](
      forward: DensePull[A, A],
      config: ResidualInverseConfig = ResidualInverseConfig.default
  ): RefinedResidualInverse[A] =
    val fullFrame = forward.from
    var previous = Option.empty[DensePull[A, A]]
    val iterations = Vector.newBuilder[Int]
    var levelIndex = 0
    while levelIndex < config.shrinks.length do
      val shrink = config.shrinks(levelIndex)
      val levelGrid = HalfFlowKernels.pyramidGrid(fullFrame.grid, shrink)
      val levelFrame = Frame[A](fullFrame.domain, levelGrid)
      val forwardLevelResult = HalfFlowKernels.regridPull(
        forward.sourceCoordinates,
        levelGrid,
        forward.validity,
        CoordinateMapOutside.Identity
      )
      val forwardLevel = DensePull.unsafe(
        levelFrame,
        levelFrame,
        forwardLevelResult.field,
        FieldValidity.copyMask(forwardLevelResult.valid)
      )
      val seed = previous match
        case None => DensePull.identity(levelFrame)
        case Some(coarse) =>
          val result = HalfFlowKernels.regridPull(
            coarse.sourceCoordinates,
            levelGrid,
            coarse.validity,
            CoordinateMapOutside.Identity
          )
          DensePull.unsafe(levelFrame, levelFrame, result.field, FieldValidity.copyMask(result.valid))
      val (inverse, used) = refineLevel(
        forwardLevel,
        seed,
        config.iterationsPerLevel,
        config.relaxation,
        config.iterationToleranceMm * shrink.toDouble,
        config.interiorMargin
      )
      previous = Some(inverse)
      iterations += used
      levelIndex += 1
    val inverse = previous.get
    val report = ResidualInverseReport(
      errorPercentiles(forward, inverse, config.interiorMargin),
      errorPercentiles(inverse, forward, config.interiorMargin),
      iterations.result()
    )
    RefinedResidualInverse(inverse, report)

  private def refineLevel[A](
      forward: DensePull[A, A],
      seed: DensePull[A, A],
      maximumIterations: Int,
      relaxation: Double,
      toleranceMm: Double,
      interiorMargin: Int
  ): (DensePull[A, A], Int) =
    val grid = forward.from.grid
    val n = grid.nVoxels
    val current = seed.sourceCoordinates.copyLegacyPlanar
    val currentValid = validityCopy(seed.validity, n)
    val composed = PrimitiveBuffers.ofSize[Double](3 * n)
    val composedValid = PrimitiveBuffers.ofSize[Boolean](n)
    val correction = PrimitiveBuffers.ofSize[Double](3 * n)
    val corrected = PrimitiveBuffers.ofSize[Double](3 * n)
    val correctedValid = PrimitiveBuffers.ofSize[Boolean](n)
    val identity = PrimitiveBuffers.ofSize[Double](3 * n)
    val identityValid = PrimitiveBuffers.ofSize[Boolean](n)
    HalfFlowKernels.identityInto(grid, identity, identityValid)
    val sampler = DenseFieldSampler(grid)
    var used = 0
    var converged = false
    while used < maximumIterations && !converged do
      val inverseField =
        DenseVectorField.fromLegacyPlanar(
          grid,
          current,
          DenseVectorFieldKind.SourceCoordinates
        )
      HalfFlowKernels.composePullInto(
        inverseField,
        forward.sourceCoordinates,
        composed,
        composedValid,
        sampler,
        FieldValidity.copyMask(currentValid),
        forward.validity,
        CoordinateMapOutside.Identity
      )
      var maximum = 0.0
      var index = 0
      while index < n do
        if composedValid(index) then
          val dx = identity(index) - composed(index)
          val dy = identity(index + n) - composed(index + n)
          val dz = identity(index + 2 * n) - composed(index + 2 * n)
          current(index) += relaxation * dx
          current(index + n) += relaxation * dy
          current(index + 2 * n) += relaxation * dz
          if interior(grid, index, interiorMargin) then
            maximum = math.max(maximum, math.sqrt(dx * dx + dy * dy + dz * dz))
        else currentValid(index) = false
        index += 1
      val updatedInverseField = DenseVectorField.fromLegacyPlanar(
        grid,
        current,
        DenseVectorFieldKind.SourceCoordinates
      )
      HalfFlowKernels.composePullInto(
        forward.sourceCoordinates,
        updatedInverseField,
        composed,
        composedValid,
        sampler,
        forward.validity,
        FieldValidity.copyMask(currentValid),
        CoordinateMapOutside.Identity
      )
      index = 0
      while index < n do
        if composedValid(index) then
          val dx = identity(index) - composed(index)
          val dy = identity(index + n) - composed(index + n)
          val dz = identity(index + 2 * n) - composed(index + 2 * n)
          correction(index) = identity(index) + relaxation * dx
          correction(index + n) = identity(index + n) + relaxation * dy
          correction(index + 2 * n) = identity(index + 2 * n) + relaxation * dz
          if interior(grid, index, interiorMargin) then
            maximum = math.max(maximum, math.sqrt(dx * dx + dy * dy + dz * dz))
        else
          correction(index) = identity(index)
          correction(index + n) = identity(index + n)
          correction(index + 2 * n) = identity(index + 2 * n)
        index += 1
      val correctionField = DenseVectorField.fromLegacyPlanar(
        grid,
        correction,
        DenseVectorFieldKind.SourceCoordinates
      )
      HalfFlowKernels.composePullInto(
        updatedInverseField,
        correctionField,
        corrected,
        correctedValid,
        sampler,
        FieldValidity.copyMask(currentValid),
        FieldValidity.copyMask(composedValid),
        CoordinateMapOutside.Identity
      )
      index = 0
      while index < n do
        if correctedValid(index) then
          current(index) = corrected(index)
          current(index + n) = corrected(index + n)
          current(index + 2 * n) = corrected(index + 2 * n)
        else currentValid(index) = false
        index += 1
      used += 1
      converged = maximum <= toleranceMm
    val field = DenseVectorField.fromLegacyPlanar(grid, current, DenseVectorFieldKind.SourceCoordinates)
    (DensePull.unsafe(forward.from, forward.to, field, FieldValidity.copyMask(currentValid)), used)

  private def errorPercentiles[A](
      left: DensePull[A, A],
      right: DensePull[A, A],
      margin: Int
  ): InverseErrorPercentiles =
    val grid = left.from.grid
    val n = grid.nVoxels
    val composed = PrimitiveBuffers.ofSize[Double](3 * n)
    val valid = PrimitiveBuffers.ofSize[Boolean](n)
    HalfFlowKernels.composePullInto(
      left.sourceCoordinates,
      right.sourceCoordinates,
      composed,
      valid,
      left.validity,
      right.validity,
      CoordinateMapOutside.Identity
    )
    val identity = PrimitiveBuffers.ofSize[Double](3 * n)
    val identityValid = PrimitiveBuffers.ofSize[Boolean](n)
    HalfFlowKernels.identityInto(grid, identity, identityValid)
    val inverseAffine = DMat.invert(grid.affine).fold(
      reason => throw new IllegalArgumentException(s"inverse-error grid is singular: $reason"),
      matrix => matrix
    )
    val eligible = interiorCount(grid, margin)
    val errors = new Array[Double](math.max(1, eligible))
    var evaluated = 0
    var maximumMm = Double.NegativeInfinity
    var maximumIndex = -1
    var maximumVox = 0.0
    var allMaximumMm = 0.0
    var index = 0
    while index < n do
      if valid(index) then
        val dx = composed(index) - identity(index)
        val dy = composed(index + n) - identity(index + n)
        val dz = composed(index + 2 * n) - identity(index + 2 * n)
        val mm = math.sqrt(dx * dx + dy * dy + dz * dz)
        allMaximumMm = math.max(allMaximumMm, mm)
        if interior(grid, index, margin) then
          val vx = inverseAffine(0, 0) * dx + inverseAffine(0, 1) * dy + inverseAffine(0, 2) * dz
          val vy = inverseAffine(1, 0) * dx + inverseAffine(1, 1) * dy + inverseAffine(1, 2) * dz
          val vz = inverseAffine(2, 0) * dx + inverseAffine(2, 1) * dy + inverseAffine(2, 2) * dz
          maximumVox = math.max(maximumVox, math.sqrt(vx * vx + vy * vy + vz * vz))
          if mm > maximumMm then
            maximumMm = mm
            maximumIndex = index
          errors(evaluated) = mm
          evaluated += 1
      index += 1
    index = evaluated
    while index < errors.length do
      errors(index) = Double.PositiveInfinity
      index += 1
    scala.util.Sorting.quickSort(errors)
    InverseErrorPercentiles(
      evaluated,
      eligible,
      if eligible == 0 then 0.0 else evaluated.toDouble / eligible.toDouble,
      percentile(errors, evaluated, 0.50),
      percentile(errors, evaluated, 0.95),
      percentile(errors, evaluated, 0.99),
      if evaluated == 0 then Double.NaN else maximumMm,
      maximumIndex,
      if evaluated == 0 then Double.NaN else maximumVox,
      if evaluated == 0 then Double.NaN else allMaximumMm
    )

  private def percentile(sorted: Array[Double], length: Int, probability: Double): Double =
    if length == 0 then Double.NaN
    else
      val position = probability * (length - 1).toDouble
      val lower = math.floor(position).toInt
      val upper = math.ceil(position).toInt
      val fraction = position - lower.toDouble
      sorted(lower) + fraction * (sorted(upper) - sorted(lower))

  private def validityCopy(source: FieldValidity, size: Int): Array[Boolean] =
    val result = PrimitiveBuffers.ofSize[Boolean](size)
    var index = 0
    while index < size do
      result(index) = source match
        case FieldValidity.All => true
        case FieldValidity.Mask(values) => values(index)
      index += 1
    result

  private def interior(grid: GridSpec, index: Int, margin: Int): Boolean =
    val x = index % grid.shape.x
    val yz = index / grid.shape.x
    val y = yz % grid.shape.y
    val z = yz / grid.shape.y
    x >= margin && x < grid.shape.x - margin && y >= margin && y < grid.shape.y - margin &&
      z >= margin && z < grid.shape.z - margin

  private def interiorCount(grid: GridSpec, margin: Int): Int =
    math.max(0, grid.shape.x - 2 * margin) * math.max(0, grid.shape.y - 2 * margin) *
      math.max(0, grid.shape.z - 2 * margin)

object ForwardMidpointExporter:
  def build[W, F, M](
      state: ForwardMidpoint[W, F, M],
      config: ResidualInverseConfig = ResidualInverseConfig.default
  ): Either[ForwardExportError, ForwardMidpointExport[F, M]] =
    inspect(state, config).flatMap(admit(_, config))

  /** Construct the best available endpoint pair without claiming that it passes export policy. */
  def inspect[W, F, M](
      state: ForwardMidpoint[W, F, M],
      config: ResidualInverseConfig = ResidualInverseConfig.default
  ): Either[ForwardExportError, ForwardMidpointExportCandidate[F, M]] =
    val fixedInverse = ResidualInverseRefiner.refine(state.fixed.residual, config)
    val movingInverse = ResidualInverseRefiner.refine(state.moving.residual, config)
    val fixedToWork = state.fixed.affine.inverse.dense.forward
    val movingToWork = state.moving.affine.inverse.dense.forward
    val pair = for
      throughFixed <- fixedToWork.thenSelfExtended(fixedInverse.inverse)
      throughMoving <- throughFixed.thenSelfExtended(state.moving.residual)
      forward <- state.moving.affine.after(throughMoving)
      backThroughMoving <- movingToWork.thenSelfExtended(movingInverse.inverse)
      backThroughFixed <- backThroughMoving.thenSelfExtended(state.fixed.residual)
      backward <- state.fixed.affine.after(backThroughFixed)
      result <- InversePair.make(forward, backward)
    yield result
    pair.left.map(ForwardExportError.Registration.apply).map: transform =>
      val roundTrip = HalfFlowKernels.inversePairError(
        transform.forward.sourceCoordinates,
        transform.backward.sourceCoordinates,
        transform.forward.validity,
        transform.backward.validity
      )
      ForwardMidpointExportCandidate(transform, fixedInverse.report, movingInverse.report, roundTrip)

  /** Apply the declared inverse and topology contract to a diagnostic candidate. */
  def admit[F, M](
      candidate: ForwardMidpointExportCandidate[F, M],
      config: ResidualInverseConfig = ResidualInverseConfig.default
  ): Either[ForwardExportError, ForwardMidpointExport[F, M]] =
    if !acceptable(candidate.fixedResidualInverse, config) then
      Left(ForwardExportError.InverseDidNotConverge(MidpointArmName.Fixed, candidate.fixedResidualInverse))
    else if !acceptable(candidate.movingResidualInverse, config) then
      Left(ForwardExportError.InverseDidNotConverge(MidpointArmName.Moving, candidate.movingResidualInverse))
    else
      for
        _ <- requireTopology(candidate.transform.forward, ExportDirection.FixedToMoving)
        _ <- requireTopology(candidate.transform.backward, ExportDirection.MovingToFixed)
      yield
        ForwardMidpointExport(
          candidate.transform,
          candidate.fixedResidualInverse,
          candidate.movingResidualInverse,
          candidate.endpointRoundTrip
        )

  private def acceptable(report: ResidualInverseReport, config: ResidualInverseConfig): Boolean =
    val coverage = report.forwardThenInverse.validFraction >= config.minimumInteriorValidFraction &&
      report.inverseThenForward.validFraction >= config.minimumInteriorValidFraction
    coverage && report.maximumInteriorMm.isFinite && report.maximumInteriorVox.isFinite &&
      report.maximumInteriorMm <= config.maximumInteriorErrorMm &&
      report.maximumInteriorVox <= config.maximumInteriorErrorVox

  private def requireTopology[A, B](
      pull: DensePull[A, B],
      direction: ExportDirection
  ): Either[ForwardExportError, Unit] =
    val grid = pull.from.grid
    val determinants = PrimitiveBuffers.ofSize[Double](grid.nVoxels)
    val valid = PrimitiveBuffers.ofSize[Boolean](grid.nVoxels)
    val reduction = JacobianReduction()
    HalfFlowKernels.jacobianDeterminantsReduceInto(
      pull.sourceCoordinates,
      determinants,
      valid,
      DenseFieldSampler(grid),
      pull.validity,
      reduction
    )
    val minimum = reduction.minimumOrNaN
    if reduction.nonPositive == 0 && minimum.isFinite && minimum > 0.0 then Right(())
    else Left(ForwardExportError.ExportedTopologyInvalid(direction, minimum, reduction.nonPositive))
