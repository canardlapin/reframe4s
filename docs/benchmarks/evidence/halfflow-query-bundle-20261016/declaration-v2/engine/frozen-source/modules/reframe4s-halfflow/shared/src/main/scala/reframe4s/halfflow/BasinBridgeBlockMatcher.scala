package reframe4s.halfflow

import reframe4s.halfflow.internal.*

/** Direction of one physical block-search pass. */
enum BasinBridgeSearchDirection:
  case Forward, Reverse

  def label: String =
    this match
      case Forward => "forward"
      case Reverse => "reverse"

/** Errors raised by the explicit-geometry native block matcher. */
enum BasinBridgeBlockMatcherError:
  case EmptyAnchors
  case GridMismatch
  case SingularGrid(reason: String)
  case InvalidConfiguration(reason: String)
  case InvalidAnchor(index: Int, direction: BasinBridgeSearchDirection, reason: String)
  case InsufficientReferenceSupport(
      index: Int,
      direction: BasinBridgeSearchDirection,
      available: Int,
      required: Int
  )
  case NoUsableCandidate(index: Int, direction: BasinBridgeSearchDirection)
  case InsufficientRetainedAnchors(available: Int, required: Int, requested: Int)
  case InvalidObservation(index: Int, error: BasinBridgeError)
  case AdapterFailure(error: BasinBridgeSearchAdapterError)

  def message: String =
    this match
      case EmptyAnchors =>
        "BasinBridge block matcher requires at least one anchor"
      case GridMismatch =>
        "BasinBridge block matcher currently requires fixed and moving images to share a grid and affine"
      case SingularGrid(reason) =>
        s"BasinBridge block matcher grid is singular: $reason"
      case InvalidConfiguration(reason) =>
        s"invalid BasinBridge block-search configuration: $reason"
      case InvalidAnchor(index, direction, reason) =>
        s"BasinBridge $direction anchor $index is invalid: $reason"
      case InsufficientReferenceSupport(index, direction, available, required) =>
        s"BasinBridge $direction anchor $index has insufficient reference support: $available available, $required required"
      case NoUsableCandidate(index, direction) =>
        s"BasinBridge $direction anchor $index has no usable search candidate"
      case InsufficientRetainedAnchors(available, required, requested) =>
        s"BasinBridge rematch retained $available of $requested anchors; $required required"
      case InvalidObservation(index, error) =>
        s"BasinBridge block-search observation $index is invalid: ${error.message}"
      case AdapterFailure(error) =>
        s"BasinBridge search adapter failed after block search: ${error.message}"

/**
  * Frozen geometry and numerical support rules for one block-search lane.
  *
  * Block and search radii are required inputs. They are intentionally not
  * given defaults: the historical matcher geometry is absent from this
  * checkout, and silently selecting new radii would change the experiment.
  */
final case class BasinBridgeBlockSearchConfig private (
    blockRadius: VoxelWindowRadius,
    searchRadius: VoxelWindowRadius,
    minimumValidFraction: Double,
    varianceFloor: Double,
    scoreTemperature: Double,
    scoreFloor: Double,
    blockSampleCount: Int,
    candidateCount: Int,
    minimumRequiredSamples: Int
)

object BasinBridgeBlockSearchConfig:
  def make(
      blockRadius: VoxelWindowRadius,
      searchRadius: VoxelWindowRadius,
      minimumValidFraction: Double = 0.75,
      varianceFloor: Double = 1e-12,
      scoreTemperature: Double = 0.025,
      scoreFloor: Double = 1e-12
  ): Either[BasinBridgeBlockMatcherError, BasinBridgeBlockSearchConfig] =
    if !minimumValidFraction.isFinite || minimumValidFraction <= 0.0 || minimumValidFraction > 1.0 then
      Left(BasinBridgeBlockMatcherError.InvalidConfiguration("minimum valid fraction must be in (0, 1]"))
    else if !varianceFloor.isFinite || varianceFloor < 0.0 then
      Left(BasinBridgeBlockMatcherError.InvalidConfiguration("variance floor must be finite and non-negative"))
    else if !scoreTemperature.isFinite || scoreTemperature <= 0.0 then
      Left(BasinBridgeBlockMatcherError.InvalidConfiguration("score temperature must be finite and positive"))
    else if !scoreFloor.isFinite || scoreFloor <= 0.0 then
      Left(BasinBridgeBlockMatcherError.InvalidConfiguration("score floor must be finite and positive"))
    else
      for
        blockSampleCount <- checkedCount(blockRadius, "block")
        candidateCount <- checkedCount(searchRadius, "search")
        _ <-
          if blockSampleCount >= 2 then Right(())
          else Left(BasinBridgeBlockMatcherError.InvalidConfiguration("block must contain at least two samples"))
      yield
        val minimumRequiredSamples = math.max(
          2,
          math.ceil(minimumValidFraction * blockSampleCount.toDouble).toInt
        )
        new BasinBridgeBlockSearchConfig(
          blockRadius,
          searchRadius,
          minimumValidFraction,
          varianceFloor,
          scoreTemperature,
          scoreFloor,
          blockSampleCount,
          candidateCount,
          minimumRequiredSamples
        )

  private def checkedCount(
      radius: VoxelWindowRadius,
      label: String
  ): Either[BasinBridgeBlockMatcherError, Int] =
    val x = 2L * radius.x.toLong + 1L
    val y = 2L * radius.y.toLong + 1L
    val z = 2L * radius.z.toLong + 1L
    val maximum = Int.MaxValue.toLong
    if x > maximum / y || x * y > maximum / z then
      Left(BasinBridgeBlockMatcherError.InvalidConfiguration(s"$label window is too large"))
    else Right((x * y * z).toInt)

/** Minimum support retained by the rematch-only tolerant search. */
final case class BasinBridgeRematchRetentionConfig private (
    minimumRetainedAnchors: Int,
    minimumRetainedFraction: Double
)

object BasinBridgeRematchRetentionConfig:
  def make(
      minimumRetainedAnchors: Int = 4,
      minimumRetainedFraction: Double = 0.5
  ): Either[BasinBridgeBlockMatcherError, BasinBridgeRematchRetentionConfig] =
    if minimumRetainedAnchors <= 0 then
      Left(BasinBridgeBlockMatcherError.InvalidConfiguration("minimum retained anchors must be positive"))
    else if !minimumRetainedFraction.isFinite || minimumRetainedFraction <= 0.0 || minimumRetainedFraction > 1.0 then
      Left(BasinBridgeBlockMatcherError.InvalidConfiguration("minimum retained fraction must be in (0, 1]"))
    else Right(new BasinBridgeRematchRetentionConfig(minimumRetainedAnchors, minimumRetainedFraction))

  val default: BasinBridgeRematchRetentionConfig =
    make().fold(error => throw new IllegalStateException(error.message), identity)

final case class BasinBridgeDroppedAnchor(
    index: Int,
    error: BasinBridgeBlockMatcherError
)

final case class BasinBridgeRetainedSearch(
    result: BasinBridgeSearchResult,
    requestedAnchors: Int,
    retainedIndices: Vector[Int],
    dropped: Vector[BasinBridgeDroppedAnchor]
):
  val retainedAnchors: Int = retainedIndices.length

/**
  * A small native image matcher for the same-grid BasinBridge lane.
  *
  * The fixed block is compared with every source block in the explicit search
  * window using z-normalized squared error. The best candidate becomes the
  * moving point, while all positive candidate scores are retained as the
  * forward or reverse ambiguity distribution. A second pass searches back
  * from the selected moving point, so cycle confidence is measured from real
  * image samples rather than caller-supplied metadata.
  */
object BasinBridgeBlockMatcher:
  private final class Sample(var value: Double, var valid: Boolean)

  private final case class DirectionResult(
      point: BasinBridgePoint,
      weights: Vector[Double]
  )

  /** Search all anchors and adapt the forward/reverse results to correspondences. */
  def search[F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      anchors: Vector[BasinBridgePoint],
      config: BasinBridgeBlockSearchConfig,
      confidenceConfig: BasinBridgeConfidenceConfig = BasinBridgeConfidenceConfig.default
  ): Either[BasinBridgeBlockMatcherError, BasinBridgeSearchResult] =
    if anchors.isEmpty then Left(BasinBridgeBlockMatcherError.EmptyAnchors)
    else if fixed.frame.grid != moving.frame.grid then Left(BasinBridgeBlockMatcherError.GridMismatch)
    else
      val grid = fixed.frame.grid

      val observations = Vector.newBuilder[BasinBridgeSearchObservation]
      var index = 0
      var failure = Option.empty[BasinBridgeBlockMatcherError]
      while index < anchors.length && failure.isEmpty do
        observation(fixed, moving, anchors(index), grid, config, index) match
          case Left(error) => failure = Some(error)
          case Right(value) => observations += value
        index += 1

      failure match
        case Some(error) => Left(error)
        case None =>
          BasinBridgeSearchAdapter
            .fromForwardReverse(observations.result(), confidenceConfig)
            .left
            .map(BasinBridgeBlockMatcherError.AdapterFailure.apply)

  /** Rematch-only search that retains usable anchors and reports every drop.
    *
    * Strict initial matching continues to use [[search]]. Only anchor-local
    * support and candidate failures are droppable; geometry, observation, and
    * adapter failures remain fatal.
    */
  def searchRetainingUsable[F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      anchors: Vector[BasinBridgePoint],
      config: BasinBridgeBlockSearchConfig,
      retention: BasinBridgeRematchRetentionConfig = BasinBridgeRematchRetentionConfig.default,
      confidenceConfig: BasinBridgeConfidenceConfig = BasinBridgeConfidenceConfig.default
  ): Either[BasinBridgeBlockMatcherError, BasinBridgeRetainedSearch] =
    if anchors.isEmpty then Left(BasinBridgeBlockMatcherError.EmptyAnchors)
    else if fixed.frame.grid != moving.frame.grid then Left(BasinBridgeBlockMatcherError.GridMismatch)
    else
      val grid = fixed.frame.grid

      val observations = Vector.newBuilder[BasinBridgeSearchObservation]
      val retained = Vector.newBuilder[Int]
      val dropped = Vector.newBuilder[BasinBridgeDroppedAnchor]
      var index = 0
      var fatal = Option.empty[BasinBridgeBlockMatcherError]
      while index < anchors.length && fatal.isEmpty do
        observation(fixed, moving, anchors(index), grid, config, index) match
          case Right(value) =>
            observations += value
            retained += index
          case Left(error) if droppable(error) =>
            dropped += BasinBridgeDroppedAnchor(index, error)
          case Left(error) => fatal = Some(error)
        index += 1
      fatal match
        case Some(error) => Left(error)
        case None =>
          val retainedIndices = retained.result()
          val required = math.min(
            anchors.length,
            math.max(
              retention.minimumRetainedAnchors,
              math.ceil(retention.minimumRetainedFraction * anchors.length.toDouble).toInt
            )
          )
          if retainedIndices.length < required then
            Left(
              BasinBridgeBlockMatcherError.InsufficientRetainedAnchors(
                retainedIndices.length,
                required,
                anchors.length
              )
            )
          else
            BasinBridgeSearchAdapter
              .fromForwardReverse(observations.result(), confidenceConfig)
              .left
              .map(BasinBridgeBlockMatcherError.AdapterFailure.apply)
              .map(result =>
                BasinBridgeRetainedSearch(
                  result,
                  anchors.length,
                  retainedIndices,
                  dropped.result()
                )
              )

  private def observation(
      fixed: RegistrationImage[?],
      moving: RegistrationImage[?],
      fixedPoint: BasinBridgePoint,
      grid: GridSpec,
      config: BasinBridgeBlockSearchConfig,
      index: Int
  ): Either[BasinBridgeBlockMatcherError, BasinBridgeSearchObservation] =
    searchDirection(
      fixed,
      moving,
      fixedPoint,
      grid,
      config,
      index,
      BasinBridgeSearchDirection.Forward
    ).flatMap: forward =>
      searchDirection(
        moving,
        fixed,
        forward.point,
        grid,
        config,
        index,
        BasinBridgeSearchDirection.Reverse
      ).flatMap: reverse =>
        BasinBridgeSearchObservation
          .make(
            fixedPoint,
            forward.point,
            reverse.point,
            forward.weights,
            reverse.weights
          )
          .left
          .map(error => BasinBridgeBlockMatcherError.InvalidObservation(index, error))

  private def droppable(error: BasinBridgeBlockMatcherError): Boolean =
    error match
      case _: BasinBridgeBlockMatcherError.InvalidAnchor => true
      case _: BasinBridgeBlockMatcherError.InsufficientReferenceSupport => true
      case _: BasinBridgeBlockMatcherError.NoUsableCandidate => true
      case _ => false

  private def searchDirection(
      reference: RegistrationImage[?],
      source: RegistrationImage[?],
      center: BasinBridgePoint,
      grid: GridSpec,
      config: BasinBridgeBlockSearchConfig,
      anchorIndex: Int,
      direction: BasinBridgeSearchDirection
  ): Either[BasinBridgeBlockMatcherError, DirectionResult] =
    val centerVoxel = grid.indexToFrame.inverse(Vector(center.x, center.y, center.z)).toOption.get
    if !centerVoxel.forall(_.isFinite) then
      Left(BasinBridgeBlockMatcherError.InvalidAnchor(anchorIndex, direction, "voxel coordinate is non-finite"))
    else if !inside(centerVoxel(0), centerVoxel(1), centerVoxel(2), grid) then
      Left(BasinBridgeBlockMatcherError.InvalidAnchor(anchorIndex, direction, "voxel coordinate is outside the grid"))
    else
      val referenceValues = Array.ofDim[Double](config.blockSampleCount)
      val referenceValid = Array.ofDim[Boolean](config.blockSampleCount)
      val sourceValues = Array.ofDim[Double](config.blockSampleCount)
      val sourceValid = Array.ofDim[Boolean](config.blockSampleCount)
      val sample = Sample(0.0, false)
      val referenceSupport = collectBlock(
        reference,
        centerVoxel(0),
        centerVoxel(1),
        centerVoxel(2),
        config.blockRadius,
        referenceValues,
        referenceValid,
        grid,
        sample
      )
      if referenceSupport < config.minimumRequiredSamples then
        Left(
          BasinBridgeBlockMatcherError.InsufficientReferenceSupport(
            anchorIndex,
            direction,
            referenceSupport,
            config.minimumRequiredSamples
          )
        )
      else
        val weights = Array.ofDim[Double](config.candidateCount)
        var bestScore = Double.NegativeInfinity
        var bestX = 0.0
        var bestY = 0.0
        var bestZ = 0.0
        var candidateIndex = 0
        var dz = -config.searchRadius.z
        while dz <= config.searchRadius.z do
          var dy = -config.searchRadius.y
          while dy <= config.searchRadius.y do
            var dx = -config.searchRadius.x
            while dx <= config.searchRadius.x do
              val candidateX = centerVoxel(0) + dx.toDouble
              val candidateY = centerVoxel(1) + dy.toDouble
              val candidateZ = centerVoxel(2) + dz.toDouble
              if inside(candidateX, candidateY, candidateZ, grid) then
                val support = collectBlock(
                  source,
                  candidateX,
                  candidateY,
                  candidateZ,
                  config.blockRadius,
                  sourceValues,
                  sourceValid,
                  grid,
                  sample
                )
                if support >= config.minimumRequiredSamples then
                  val score = normalizedScore(
                    referenceValues,
                    referenceValid,
                    sourceValues,
                    sourceValid,
                    config.blockSampleCount,
                    config.minimumRequiredSamples,
                    config.varianceFloor,
                    config.scoreTemperature,
                    config.scoreFloor
                  )
                  if score.isFinite && score > 0.0 then
                    weights(candidateIndex) = score
                    if score > bestScore then
                      bestScore = score
                      bestX = candidateX
                      bestY = candidateY
                      bestZ = candidateZ
              candidateIndex += 1
              dx += 1
            dy += 1
          dz += 1

        if !bestScore.isFinite || bestScore <= 0.0 then
          Left(BasinBridgeBlockMatcherError.NoUsableCandidate(anchorIndex, direction))
        else
          val world = grid.voxelToWorld(Vector(bestX, bestY, bestZ))
          BasinBridgePoint
            .make(world(0), world(1), world(2), s"${direction.label} block-search point")
            .left
            .map(error => BasinBridgeBlockMatcherError.InvalidObservation(anchorIndex, error))
            .map(point => DirectionResult(point, weights.toVector))

  private def collectBlock(
      image: RegistrationImage[?],
      centerX: Double,
      centerY: Double,
      centerZ: Double,
      radius: VoxelWindowRadius,
      values: Array[Double],
      valid: Array[Boolean],
      grid: GridSpec,
      sample: Sample
  ): Int =
    var support = 0
    var index = 0
    var dz = -radius.z
    while dz <= radius.z do
      var dy = -radius.y
      while dy <= radius.y do
        var dx = -radius.x
        while dx <= radius.x do
          sampleLinear(
            image,
            centerX + dx.toDouble,
            centerY + dy.toDouble,
            centerZ + dz.toDouble,
            grid,
            sample
          )
          values(index) = sample.value
          valid(index) = sample.valid
          if sample.valid then support += 1
          index += 1
          dx += 1
        dy += 1
      dz += 1
    support

  private def normalizedScore(
      referenceValues: Array[Double],
      referenceValid: Array[Boolean],
      sourceValues: Array[Double],
      sourceValid: Array[Boolean],
      length: Int,
      minimumRequiredSamples: Int,
      varianceFloor: Double,
      scoreTemperature: Double,
      scoreFloor: Double
  ): Double =
    var count = 0
    var referenceSum = 0.0
    var sourceSum = 0.0
    var referenceSquares = 0.0
    var sourceSquares = 0.0
    var index = 0
    while index < length do
      if referenceValid(index) && sourceValid(index) then
        val reference = referenceValues(index)
        val source = sourceValues(index)
        referenceSum += reference
        sourceSum += source
        referenceSquares += reference * reference
        sourceSquares += source * source
        count += 1
      index += 1
    if count < minimumRequiredSamples then 0.0
    else
      val countDouble = count.toDouble
      val referenceMean = referenceSum / countDouble
      val sourceMean = sourceSum / countDouble
      val referenceVariance = math.max(0.0, referenceSquares / countDouble - referenceMean * referenceMean)
      val sourceVariance = math.max(0.0, sourceSquares / countDouble - sourceMean * sourceMean)
      if !referenceVariance.isFinite || !sourceVariance.isFinite then 0.0
      else if referenceVariance <= varianceFloor || sourceVariance <= varianceFloor then 1.0
      else
        val referenceScale = math.sqrt(referenceVariance)
        val sourceScale = math.sqrt(sourceVariance)
        var squaredError = 0.0
        index = 0
        while index < length do
          if referenceValid(index) && sourceValid(index) then
            val referenceZ = (referenceValues(index) - referenceMean) / referenceScale
            val sourceZ = (sourceValues(index) - sourceMean) / sourceScale
            val difference = referenceZ - sourceZ
            squaredError += difference * difference
          index += 1
        val error = squaredError / countDouble
        if error.isFinite then
          math.max(scoreFloor, math.exp(-error / scoreTemperature))
        else 0.0

  private def sampleLinear(
      image: RegistrationImage[?],
      voxelX: Double,
      voxelY: Double,
      voxelZ: Double,
      grid: GridSpec,
      destination: Sample
  ): Unit =
    if !inside(voxelX, voxelY, voxelZ, grid) then
      destination.value = 0.0
      destination.valid = false
    else
      val x0 = math.floor(voxelX).toInt
      val y0 = math.floor(voxelY).toInt
      val z0 = math.floor(voxelZ).toInt
      val x1 = math.min(grid.shape(0) - 1, x0 + 1)
      val y1 = math.min(grid.shape(1) - 1, y0 + 1)
      val z1 = math.min(grid.shape(2) - 1, z0 + 1)
      val fx = voxelX - x0.toDouble
      val fy = voxelY - y0.toDouble
      val fz = voxelZ - z0.toDouble
      var sum = 0.0
      var ok = true
      var dz = 0
      while dz <= 1 && ok do
        val z = if dz == 0 then z0 else z1
        val wz = if dz == 0 then 1.0 - fz else fz
        var dy = 0
        while dy <= 1 && ok do
          val y = if dy == 0 then y0 else y1
          val wy = if dy == 0 then 1.0 - fy else fy
          var dx = 0
          while dx <= 1 && ok do
            val x = if dx == 0 then x0 else x1
            val wx = if dx == 0 then 1.0 - fx else fx
            val weight = wx * wy * wz
            if weight != 0.0 then
              val linear = x + grid.shape(0) * y + grid.shape(0) * grid.shape(1) * z
              val value = image.volume(x, y, z)
              if !image.validity.contains(linear) || !value.isFinite then ok = false
              else sum += weight * value
            dx += 1
          dy += 1
        dz += 1
      destination.value = sum
      destination.valid = ok && sum.isFinite

  private def inside(x: Double, y: Double, z: Double, grid: GridSpec): Boolean =
    x.isFinite && y.isFinite && z.isFinite &&
      x >= 0.0 && x <= grid.shape(0).toDouble - 1.0 &&
      y >= 0.0 && y <= grid.shape(1).toDouble - 1.0 &&
      z >= 0.0 && z <= grid.shape(2).toDouble - 1.0
