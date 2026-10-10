package reframe4s.halfflow

import image4s.geometry.{Affine, D3}
import reframe4s.lie.FramedAffine
import gale.linalg.DMat
import reframe4s.halfflow.internal.*

enum AffineInitializationOrigin:
  case Supplied
  case Estimated

final case class AffineInitializationConfig private (
    coarseShrink: Int,
    fineShrink: Int,
    smoothingSigmaMm: Double,
    maximumSamplesPerImage: Int,
    rigidSweepsPerLevel: Int,
    affineSweepsPerLevel: Int,
    rotationSeedRadians: Double,
    translationStepMm: Double,
    rotationStepRadians: Double,
    logScaleStep: Double,
    shearStep: Double,
    minimumOverlap: Double,
    overlapPenalty: Double
)

object AffineInitializationConfig:
  val default: AffineInitializationConfig =
    make().fold(error => throw new IllegalArgumentException(error.message), identity)

  def make(
      coarseShrink: Int = 4,
      fineShrink: Int = 1,
      smoothingSigmaMm: Double = 2.0,
      maximumSamplesPerImage: Int = 12000,
      rigidSweepsPerLevel: Int = 5,
      affineSweepsPerLevel: Int = 4,
      rotationSeedRadians: Double = 10.0 * math.Pi / 180.0,
      translationStepMm: Double = 4.0,
      rotationStepRadians: Double = 4.0 * math.Pi / 180.0,
      logScaleStep: Double = 0.04,
      shearStep: Double = 0.03,
      minimumOverlap: Double = 0.35,
      overlapPenalty: Double = 0.15
  ): Either[RegistrationError, AffineInitializationConfig] =
    val positiveFinite =
      Vector(
        smoothingSigmaMm,
        rotationSeedRadians,
        translationStepMm,
        rotationStepRadians,
        logScaleStep,
        shearStep
      ).forall(value => value.isFinite && value > 0.0)
    if coarseShrink < fineShrink || fineShrink < 1 then
      Left(RegistrationError.InvalidConfiguration("affine shrink schedule"))
    else if maximumSamplesPerImage < 32 then
      Left(RegistrationError.InvalidConfiguration("affine maximum samples"))
    else if rigidSweepsPerLevel < 1 || affineSweepsPerLevel < 1 then
      Left(RegistrationError.InvalidConfiguration("affine sweep budget"))
    else if !positiveFinite then
      Left(RegistrationError.InvalidConfiguration("affine physical step sizes"))
    else if !minimumOverlap.isFinite || minimumOverlap <= 0.0 || minimumOverlap > 1.0 then
      Left(RegistrationError.InvalidConfiguration("affine minimum overlap"))
    else if !overlapPenalty.isFinite || overlapPenalty < 0.0 then
      Left(RegistrationError.InvalidConfiguration("affine overlap penalty"))
    else
      Right(
        new AffineInitializationConfig(
          coarseShrink,
          fineShrink,
          smoothingSigmaMm,
          maximumSamplesPerImage,
          rigidSweepsPerLevel,
          affineSweepsPerLevel,
          rotationSeedRadians,
          translationStepMm,
          rotationStepRadians,
          logScaleStep,
          shearStep,
          minimumOverlap,
          overlapPenalty
        )
      )

final case class AffineInitializationDiagnostics(
    origin: AffineInitializationOrigin,
    initialValue: Double,
    finalValue: Double,
    forwardDirectionalValue: Double,
    reverseDirectionalValue: Double,
    overlap: Double,
    evaluations: Int,
    determinant: Double
)

final case class AffineIso[A, B] private (
    from: RegistrationFrame[A],
    to: RegistrationFrame[B],
    private val canonicalMap: FramedAffine[?, ?, D3]
):
  def transform: Affine[D3] = canonicalMap.operator
  def map: FramedAffine[?, ?, D3] = canonicalMap
  val determinant: Double =
    AffineMatrices.linearDeterminant(transform.matrix)

  def inverse: AffineIso[B, A] =
    AffineIso.unsafe(to, from, transform.inverse)

  def dense: InversePair[A, B] =
    InversePair.unsafe(
      AffineIso.densePull(from, to, transform.matrix),
      AffineIso.densePull(to, from, transform.inverse.matrix)
    )

  /** Apply this affine exactly after a sampled pull map. */
  private[halfflow] def after[X](pull: DensePull[X, A]): Either[RegistrationError, DensePull[X, B]] =
    if pull.to.domain != from.domain then
      Left(RegistrationError.FrameMismatch("affine-after-pull", from.domain, pull.to.domain))
    else if !pull.to.canonical.sameRuntimeOwnerAs(from.canonical) then
      Left(RegistrationError.PhysicalFrameMismatch("affine-after-pull"))
    else
      val source = pull.sourceCoordinates
      val n = source.grid.nVoxels
      val output = PrimitiveBuffers.ofSize[Double](3 * n)
      val matrix = transform.matrix
      var index = 0
      while index < n do
        val x = source.linearComponent(index, 0)
        val y = source.linearComponent(index, 1)
        val z = source.linearComponent(index, 2)
        output(index) = AffineIso.affineCoordinate(matrix, 0, x, y, z)
        output(index + n) = AffineIso.affineCoordinate(matrix, 1, x, y, z)
        output(index + 2 * n) = AffineIso.affineCoordinate(matrix, 2, x, y, z)
        index += 1
      Right(
        DensePull.unsafe(
          pull.from,
          to,
          DenseVectorField.fromLegacyPlanar(
            source.grid,
            output,
            DenseVectorFieldKind.SourceCoordinates
          ),
          pull.validity
        )
      )

  private[halfflow] def reframe(
      newFrom: RegistrationFrame[A],
      newTo: RegistrationFrame[B]
  ): Either[RegistrationError, AffineIso[A, B]] =
    if newFrom.domain != from.domain then
      Left(RegistrationError.FrameMismatch("affine reframe source", from.domain, newFrom.domain))
    else if newTo.domain != to.domain then
      Left(RegistrationError.FrameMismatch("affine reframe target", to.domain, newTo.domain))
    else if !newFrom.canonical.sameRuntimeOwnerAs(from.canonical) ||
        !newTo.canonical.sameRuntimeOwnerAs(to.canonical) then
      Left(RegistrationError.PhysicalFrameMismatch("affine reframe"))
    else Right(AffineIso.unsafe(newFrom, newTo, transform))

  def splitAt[W](work: RegistrationFrame[W]): Either[RegistrationError, Midpoint[W, A, B]] =
    for
      rootMatrix <- AffineMatrices.squareRoot(transform.matrix)
      root <- Affine.fromRowMajor[D3](Vector.tabulate(16)(i => rootMatrix(i / 4, i % 4))).left.map(error => RegistrationError.InvalidAffine("affine half", error.message))
      fixedAffine <- AffineIso.make(work, from, root.inverse)
      movingAffine <- AffineIso.make(work, to, root)
      fixed <- MidpointArm.identity(fixedAffine)
      moving <- MidpointArm.identity(movingAffine)
      midpoint <- Midpoint.make(fixed, moving)
    yield midpoint

object AffineIso:
  def make[A, B](
      from: RegistrationFrame[A],
      to: RegistrationFrame[B],
      transform: Affine[D3]
  ): Either[RegistrationError, AffineIso[A, B]] =
    val determinant = AffineMatrices.linearDeterminant(transform.matrix)
    if !determinant.isFinite || determinant <= 1e-8 then
      Left(RegistrationError.InvalidAffine("fixed-to-moving", s"non-positive determinant $determinant"))
    else Right(unsafe(from, to, transform))

  private[halfflow] def unsafe[A, B](
      from: RegistrationFrame[A],
      to: RegistrationFrame[B],
      transform: Affine[D3]
  ): AffineIso[A, B] =
    new AffineIso(from, to, FramedAffine.between(from.canonical, to.canonical)(transform))

  private def densePull[A, B](from: RegistrationFrame[A], to: RegistrationFrame[B], matrix: DMat): DensePull[A, B] =
    val grid = from.grid
    val n = grid.nVoxels
    val coordinates = PrimitiveBuffers.ofSize[Double](3 * n)
    val sourceWorld = grid.affine
    val nx = grid.shape(0)
    val ny = grid.shape(1)
    var index = 0
    while index < n do
      val x = (index % nx).toDouble
      val yz = index / nx
      val y = (yz % ny).toDouble
      val z = (yz / ny).toDouble
      val wx = affineCoordinate(sourceWorld, 0, x, y, z)
      val wy = affineCoordinate(sourceWorld, 1, x, y, z)
      val wz = affineCoordinate(sourceWorld, 2, x, y, z)
      coordinates(index) = affineCoordinate(matrix, 0, wx, wy, wz)
      coordinates(index + n) = affineCoordinate(matrix, 1, wx, wy, wz)
      coordinates(index + 2 * n) = affineCoordinate(matrix, 2, wx, wy, wz)
      index += 1
    DensePull.unsafe(
      from,
      to,
      DenseVectorField.fromLegacyPlanar(grid, coordinates, DenseVectorFieldKind.SourceCoordinates),
      FieldValidity.All
    )

  private inline def affineCoordinate(matrix: DMat, row: Int, x: Double, y: Double, z: Double): Double =
    matrix(row, 0) * x + matrix(row, 1) * y + matrix(row, 2) * z + matrix(row, 3)

final case class AffineInitializationResult[W, F, M](
    affine: AffineIso[F, M],
    midpoint: Midpoint[W, F, M],
    diagnostics: AffineInitializationDiagnostics
)

object AffineInitializer:
  def supplied[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      work: RegistrationFrame[W],
      fixedToMoving: Affine[D3]
  ): Either[RegistrationError, AffineInitializationResult[W, F, M]] =
    for
      affine <- AffineIso.make(fixed.frame, moving.frame, fixedToMoving)
      midpoint <- affine.splitAt(work)
    yield
      AffineInitializationResult(
        affine,
        midpoint,
        AffineInitializationDiagnostics(
          AffineInitializationOrigin.Supplied,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          0,
          AffineMatrices.linearDeterminant(fixedToMoving.matrix)
        )
      )

  def estimate[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      work: RegistrationFrame[W],
      config: AffineInitializationConfig = AffineInitializationConfig.default
  ): Either[RegistrationError, AffineInitializationResult[W, F, M]] =
    for
      fixedLevels <- prepareLevels(fixed, config)
      movingLevels <- prepareLevels(moving, config)
      result <- estimatePrepared(fixed, moving, work, fixedLevels, movingLevels, config)
    yield result

  private def estimatePrepared[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      work: RegistrationFrame[W],
      fixedLevels: Vector[PreparedImage],
      movingLevels: Vector[PreparedImage],
      config: AffineInitializationConfig
  ): Either[RegistrationError, AffineInitializationResult[W, F, M]] =
    val counter = EvaluationCounter()
    val forward = optimizeDirection(fixedLevels, movingLevels, config, counter)
    val reverse = optimizeDirection(movingLevels, fixedLevels, config, counter)
    for
      reverseInverse <- AffineMatrices.inverse(reverse.matrix)
      forwardInverse <- AffineMatrices.inverse(forward.matrix)
      relative = AffineMatrices.multiply(forwardInverse, reverseInverse)
      correction <- AffineMatrices.squareRoot(relative)
      reconciled = AffineMatrices.multiply(forward.matrix, correction)
      transform <- Affine.fromRowMajor[D3](Vector.tabulate(16)(i => reconciled(i / 4, i % 4))).left.map(error => RegistrationError.InvalidAffine("estimated affine", error.message))
      affine <- AffineIso.make(fixed.frame, moving.frame, transform)
      midpoint <- affine.splitAt(work)
      finalScore = symmetricScore(reconciled, fixedLevels.last, movingLevels.last, config, counter)
    yield
      AffineInitializationResult(
        affine,
        midpoint,
        AffineInitializationDiagnostics(
          AffineInitializationOrigin.Estimated,
          forward.initial.cost,
          finalScore.cost,
          forward.score.cost,
          reverse.score.cost,
          finalScore.overlap,
          counter.value,
          AffineMatrices.linearDeterminant(reconciled)
        )
      )

  private final case class DirectionResult(matrix: DMat, initial: AffineScore, score: AffineScore)

  private def optimizeDirection(
      referenceLevels: Vector[PreparedImage],
      sourceLevels: Vector[PreparedImage],
      config: AffineInitializationConfig,
      counter: EvaluationCounter
  ): DirectionResult =
    val coarseReference = referenceLevels.head
    val coarseSource = sourceLevels.head
    val starts = rotationStarts(config.rotationSeedRadians)
    var bestMatrix = centeredTransform(starts.head, coarseReference.centroid, coarseSource.centroid)
    var bestScore = symmetricScore(bestMatrix, coarseReference, coarseSource, config, counter)
    var startIndex = 1
    while startIndex < starts.length do
      val candidate = centeredTransform(starts(startIndex), coarseReference.centroid, coarseSource.centroid)
      val score = symmetricScore(candidate, coarseReference, coarseSource, config, counter)
      if score.cost < bestScore.cost then
        bestMatrix = candidate
        bestScore = score
      startIndex += 1
    val initial = bestScore

    var level = 0
    while level < referenceLevels.length do
      val reference = referenceLevels(level)
      val source = sourceLevels(level)
      val scale = math.max(1.0, config.coarseShrink.toDouble / reference.shrink.toDouble)
      bestMatrix = coordinateSearch(
        bestMatrix,
        reference,
        source,
        config,
        config.rigidSweepsPerLevel,
        activeParameters = 6,
        translationStep = config.translationStepMm / scale,
        rotationStep = config.rotationStepRadians / scale,
        config.logScaleStep,
        config.shearStep,
        counter
      )._1
      bestMatrix = coordinateSearch(
        bestMatrix,
        reference,
        source,
        config,
        config.affineSweepsPerLevel,
        activeParameters = 12,
        translationStep = 0.5 * config.translationStepMm / scale,
        rotationStep = 0.5 * config.rotationStepRadians / scale,
        config.logScaleStep / scale,
        config.shearStep / scale,
        counter
      )._1
      level += 1
    val finalScore = symmetricScore(bestMatrix, referenceLevels.last, sourceLevels.last, config, counter)
    DirectionResult(bestMatrix, initial, finalScore)

  private def coordinateSearch(
      initial: DMat,
      reference: PreparedImage,
      source: PreparedImage,
      config: AffineInitializationConfig,
      maximumSweeps: Int,
      activeParameters: Int,
      translationStep: Double,
      rotationStep: Double,
      logScaleStep: Double,
      shearStep: Double,
      counter: EvaluationCounter
  ): (DMat, AffineScore) =
    val steps = Array(
      translationStep,
      translationStep,
      translationStep,
      rotationStep,
      rotationStep,
      rotationStep,
      logScaleStep,
      logScaleStep,
      logScaleStep,
      shearStep,
      shearStep,
      shearStep
    )
    var matrix = initial
    var score = symmetricScore(matrix, reference, source, config, counter)
    var sweep = 0
    while sweep < maximumSweeps do
      var parameter = 0
      while parameter < activeParameters do
        val plus = increment(matrix, parameter, steps(parameter), source.centroid)
        val plusScore = symmetricScore(plus, reference, source, config, counter)
        val minus = increment(matrix, parameter, -steps(parameter), source.centroid)
        val minusScore = symmetricScore(minus, reference, source, config, counter)
        if plusScore.cost < score.cost || minusScore.cost < score.cost then
          if plusScore.cost <= minusScore.cost then
            matrix = plus
            score = plusScore
          else
            matrix = minus
            score = minusScore
        else steps(parameter) *= 0.5
        parameter += 1
      sweep += 1
    (matrix, score)

  private def increment(matrix: DMat, parameter: Int, value: Double, center: Array[Double]): DMat =
    val delta = AffineMatrices.identityArray()
    parameter match
      case 0 | 1 | 2 => delta(parameter * 4 + 3) = value
      case 3 | 4 | 5 =>
        val rotation = axisRotation(parameter - 3, value)
        return centeredLeftMultiply(matrix, rotation, center)
      case 6 | 7 | 8 =>
        delta((parameter - 6) * 4 + (parameter - 6)) = math.exp(value)
      case 9 => delta(1) = value
      case 10 => delta(2) = value
      case _ => delta(6) = value
    if parameter < 3 then AffineMatrices.multiply(AffineMatrices.fromArray(delta), matrix)
    else centeredLeftMultiply(matrix, AffineMatrices.fromArray(delta), center)

  private def centeredLeftMultiply(matrix: DMat, delta: DMat, center: Array[Double]): DMat =
    val centered = AffineMatrices.copyArray(delta)
    centered(3) = center(0) - delta(0, 0) * center(0) - delta(0, 1) * center(1) - delta(0, 2) * center(2)
    centered(7) = center(1) - delta(1, 0) * center(0) - delta(1, 1) * center(1) - delta(1, 2) * center(2)
    centered(11) = center(2) - delta(2, 0) * center(0) - delta(2, 1) * center(1) - delta(2, 2) * center(2)
    AffineMatrices.multiply(AffineMatrices.fromArray(centered), matrix)

  private def rotationStarts(angle: Double): Array[DMat] =
    Array(
      AffineMatrices.identity,
      axisRotation(0, angle),
      axisRotation(0, -angle),
      axisRotation(1, angle),
      axisRotation(1, -angle),
      axisRotation(2, angle),
      axisRotation(2, -angle)
    )

  private def axisRotation(axis: Int, angle: Double): DMat =
    val c = math.cos(angle)
    val s = math.sin(angle)
    axis match
      case 0 => AffineMatrices.fromRows(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
      case 1 => AffineMatrices.fromRows(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
      case _ => AffineMatrices.fromRows(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)

  private def centeredTransform(linear: DMat, from: Array[Double], to: Array[Double]): DMat =
    val out = AffineMatrices.copyArray(linear)
    out(3) = to(0) - linear(0, 0) * from(0) - linear(0, 1) * from(1) - linear(0, 2) * from(2)
    out(7) = to(1) - linear(1, 0) * from(0) - linear(1, 1) * from(1) - linear(1, 2) * from(2)
    out(11) = to(2) - linear(2, 0) * from(0) - linear(2, 1) * from(1) - linear(2, 2) * from(2)
    AffineMatrices.fromArray(out)

  private final case class AffineScore(cost: Double, overlap: Double)

  private def symmetricScore(
      matrix: DMat,
      fixed: PreparedImage,
      moving: PreparedImage,
      config: AffineInitializationConfig,
      counter: EvaluationCounter
  ): AffineScore =
    counter.value += 1
    AffineMatrices.inverse(matrix) match
      case Left(_) => AffineScore(Double.PositiveInfinity, 0.0)
      case Right(inverse) =>
        val forward = directionalCorrelation(fixed, moving, matrix)
        val backward = directionalCorrelation(moving, fixed, inverse)
        val overlap = 0.5 * (forward.overlap + backward.overlap)
        if !forward.correlation.isFinite || !backward.correlation.isFinite || overlap < config.minimumOverlap then
          AffineScore(Double.PositiveInfinity, overlap)
        else
          AffineScore(
            1.0 - 0.5 * (forward.correlation + backward.correlation) + config.overlapPenalty * (1.0 - overlap),
            overlap
          )

  private final case class DirectionalScore(correlation: Double, overlap: Double)

  private def directionalCorrelation(reference: PreparedImage, source: PreparedImage, matrix: DMat): DirectionalScore =
    val samples = reference.samples
    var count = 0
    var sumReference = 0.0
    var sumSource = 0.0
    var squareReference = 0.0
    var squareSource = 0.0
    var cross = 0.0
    var index = 0
    while index < samples.length do
      val x = samples.worldX(index)
      val y = samples.worldY(index)
      val z = samples.worldZ(index)
      val sx = matrix(0, 0) * x + matrix(0, 1) * y + matrix(0, 2) * z + matrix(0, 3)
      val sy = matrix(1, 0) * x + matrix(1, 1) * y + matrix(1, 2) * z + matrix(1, 3)
      val sz = matrix(2, 0) * x + matrix(2, 1) * y + matrix(2, 2) * z + matrix(2, 3)
      if source.sampleLinear(sx, sy, sz) then
        val a = samples.value(index)
        val b = source.lastSample
        count += 1
        sumReference += a
        sumSource += b
        squareReference += a * a
        squareSource += b * b
        cross += a * b
      index += 1
    if count < 16 then DirectionalScore(Double.NaN, count.toDouble / samples.length.toDouble)
    else
      val n = count.toDouble
      val covariance = cross - sumReference * sumSource / n
      val varianceReference = squareReference - sumReference * sumReference / n
      val varianceSource = squareSource - sumSource * sumSource / n
      val denominator = math.sqrt(math.max(0.0, varianceReference * varianceSource))
      DirectionalScore(
        if denominator <= 1e-12 then Double.NaN else covariance / denominator,
        count.toDouble / samples.length.toDouble
      )

  private def prepareLevels[A](
      image: RegistrationImage[A],
      config: AffineInitializationConfig
  ): Either[RegistrationError, Vector[PreparedImage]] =
    val shrinks = if config.coarseShrink == config.fineShrink then Vector(config.coarseShrink) else Vector(config.coarseShrink, config.fineShrink)
    val levels = Vector.newBuilder[PreparedImage]
    var index = 0
    var failure = Option.empty[RegistrationError]
    while index < shrinks.length && failure.isEmpty do
      val shrink = shrinks(index)
      val result = HalfFlowKernels.buildPyramidLevel(
        image.volume,
        shrink,
        config.smoothingSigmaMm,
        image.validity
      )
      PreparedImage.make(result.values, result.valid, shrink, config.maximumSamplesPerImage) match
        case Left(error) => failure = Some(error)
        case Right(level) => levels += level
      index += 1
    failure match
      case Some(error) => Left(error)
      case None => Right(levels.result())

  private final class EvaluationCounter private (var value: Int)
  private object EvaluationCounter:
    def apply(): EvaluationCounter = new EvaluationCounter(0)

private final class AffineSamples(
    val worldX: Array[Double],
    val worldY: Array[Double],
    val worldZ: Array[Double],
    val value: Array[Double]
):
  val length: Int = value.length

private final class PreparedImage private (
    val shrink: Int,
    val grid: GridSpec,
    val volume: NeuroVol[Double],
    val validity: NeuroVol[Boolean],
    val low: Double,
    val scale: Double,
    val centroid: Array[Double],
    val samples: AffineSamples,
    private val inverse: DMat
):
  private var sampledValue: Double = Double.NaN
  def lastSample: Double = sampledValue

  def sampleLinear(worldX: Double, worldY: Double, worldZ: Double): Boolean =
    val x = affineCoordinate(inverse, 0, worldX, worldY, worldZ)
    val y = affineCoordinate(inverse, 1, worldX, worldY, worldZ)
    val z = affineCoordinate(inverse, 2, worldX, worldY, worldZ)
    val nx = grid.shape(0)
    val ny = grid.shape(1)
    val nz = grid.shape(2)
    if !x.isFinite || !y.isFinite || !z.isFinite || x < 0.0 || y < 0.0 || z < 0.0 ||
        x > (nx - 1).toDouble || y > (ny - 1).toDouble || z > (nz - 1).toDouble
    then false
    else
      val x0 = math.floor(x).toInt
      val y0 = math.floor(y).toInt
      val z0 = math.floor(z).toInt
      val x1 = math.min(nx - 1, x0 + 1)
      val y1 = math.min(ny - 1, y0 + 1)
      val z1 = math.min(nz - 1, z0 + 1)
      val fx = x - x0.toDouble
      val fy = y - y0.toDouble
      val fz = z - z0.toDouble
      var sum = 0.0
      var ok = true
      var dz = 0
      while dz <= 1 && ok do
        val zi = if dz == 0 then z0 else z1
        val wz = if dz == 0 then 1.0 - fz else fz
        var dy = 0
        while dy <= 1 && ok do
          val yi = if dy == 0 then y0 else y1
          val wy = if dy == 0 then 1.0 - fy else fy
          var dx = 0
          while dx <= 1 && ok do
            val xi = if dx == 0 then x0 else x1
            val wx = if dx == 0 then 1.0 - fx else fx
            val weight = wx * wy * wz
            if weight != 0.0 then
              val value = volume(xi, yi, zi)
              if !validity(xi, yi, zi) || !value.isFinite then ok = false
              else sum += weight * value
            dx += 1
          dy += 1
        dz += 1
      if ok then sampledValue = normalize(sum, low, scale)
      ok

  private inline def affineCoordinate(matrix: DMat, row: Int, x: Double, y: Double, z: Double): Double =
    matrix(row, 0) * x + matrix(row, 1) * y + matrix(row, 2) * z + matrix(row, 3)

  private inline def normalize(value: Double, low: Double, scale: Double): Double =
    math.max(0.0, math.min(1.0, (value - low) * scale))

private object PreparedImage:
  private val HistogramBins = 512

  def make(
      volume: NeuroVol[Double],
      validity: NeuroVol[Boolean],
      shrink: Int,
    maximumSamples: Int
  ): Either[RegistrationError, PreparedImage] =
    val grid = GridSpec.fromSpace(volume.space)
    var minimum = Double.PositiveInfinity
    var maximum = Double.NegativeInfinity
    var finiteCount = 0
    var index = 0
    while index < volume.values.size do
      val value = volume.linear(index)
      if validity.linear(index) && value.isFinite then
        minimum = math.min(minimum, value)
        maximum = math.max(maximum, value)
        finiteCount += 1
      index += 1
    if finiteCount < 32 || maximum - minimum <= 1e-12 then
      Left(RegistrationError.InsufficientSupport("affine intensity", finiteCount, 32))
    else
      val histogram = Array.fill(HistogramBins)(0)
      val histogramScale = (HistogramBins - 1).toDouble / (maximum - minimum)
      index = 0
      while index < volume.values.size do
        val value = volume.linear(index)
        if validity.linear(index) && value.isFinite then
          val bin = math.max(0, math.min(HistogramBins - 1, ((value - minimum) * histogramScale).toInt))
          histogram(bin) += 1
        index += 1
      val low = histogramQuantile(histogram, finiteCount, 0.02, minimum, maximum)
      val high = histogramQuantile(histogram, finiteCount, 0.98, minimum, maximum)
      if high - low <= 1e-12 then Left(RegistrationError.InvalidAffine("intensity normalization", "degenerate robust range"))
      else
        val normalizationScale = 1.0 / (high - low)
        val affine = grid.affine
        val nx = grid.shape(0)
        val ny = grid.shape(1)
        var weightSum = 0.0
        val centroid = Array(0.0, 0.0, 0.0)
        var eligible = 0
        index = 0
        while index < volume.values.size do
          val value = volume.linear(index)
          val normalized = normalize(value, low, normalizationScale)
          if validity.linear(index) && value.isFinite && normalized > 0.05 then
            val x = (index % nx).toDouble
            val yz = index / nx
            val y = (yz % ny).toDouble
            val z = (yz / ny).toDouble
            val worldX = affineCoordinate(affine, 0, x, y, z)
            val worldY = affineCoordinate(affine, 1, x, y, z)
            val worldZ = affineCoordinate(affine, 2, x, y, z)
            val weight = normalized * normalized
            centroid(0) += weight * worldX
            centroid(1) += weight * worldY
            centroid(2) += weight * worldZ
            weightSum += weight
            eligible += 1
          index += 1
        if eligible < 32 || weightSum <= 0.0 then
          Left(RegistrationError.InsufficientSupport("affine foreground", eligible, 32))
        else
          centroid(0) /= weightSum
          centroid(1) /= weightSum
          centroid(2) /= weightSum
          val stride = math.max(1, math.ceil(eligible.toDouble / maximumSamples.toDouble).toInt)
          val capacity = (eligible + stride - 1) / stride
          val worldX = Array.ofDim[Double](capacity)
          val worldY = Array.ofDim[Double](capacity)
          val worldZ = Array.ofDim[Double](capacity)
          val sampleValues = Array.ofDim[Double](capacity)
          var foregroundIndex = 0
          var sampleIndex = 0
          index = 0
          while index < volume.values.size do
            val value = volume.linear(index)
            val normalized = normalize(value, low, normalizationScale)
            if validity.linear(index) && value.isFinite && normalized > 0.05 then
              if foregroundIndex % stride == 0 then
                val x = (index % nx).toDouble
                val yz = index / nx
                val y = (yz % ny).toDouble
                val z = (yz / ny).toDouble
                worldX(sampleIndex) = affineCoordinate(affine, 0, x, y, z)
                worldY(sampleIndex) = affineCoordinate(affine, 1, x, y, z)
                worldZ(sampleIndex) = affineCoordinate(affine, 2, x, y, z)
                sampleValues(sampleIndex) = normalized
                sampleIndex += 1
              foregroundIndex += 1
            index += 1
          val samples = new AffineSamples(
            if sampleIndex == capacity then worldX else worldX.take(sampleIndex),
            if sampleIndex == capacity then worldY else worldY.take(sampleIndex),
            if sampleIndex == capacity then worldZ else worldZ.take(sampleIndex),
            if sampleIndex == capacity then sampleValues else sampleValues.take(sampleIndex)
          )
          Right(
            new PreparedImage(
              shrink,
              grid,
              volume,
              validity,
              low,
              normalizationScale,
              centroid,
              samples,
              grid.inverseAffine
            )
          )

  private def histogramQuantile(
      histogram: Array[Int],
      count: Int,
      probability: Double,
      minimum: Double,
      maximum: Double
  ): Double =
    val target = math.floor(probability * (count - 1).toDouble).toInt
    var cumulative = 0
    var bin = 0
    while bin < histogram.length - 1 && cumulative + histogram(bin) <= target do
      cumulative += histogram(bin)
      bin += 1
    minimum + bin.toDouble * (maximum - minimum) / (histogram.length - 1).toDouble

  private inline def normalize(value: Double, low: Double, scale: Double): Double =
    math.max(0.0, math.min(1.0, (value - low) * scale))

  private inline def affineCoordinate(matrix: DMat, row: Int, x: Double, y: Double, z: Double): Double =
    matrix(row, 0) * x + matrix(row, 1) * y + matrix(row, 2) * z + matrix(row, 3)

private object AffineMatrices:
  val identity: DMat = DMat.eye(4)

  def identityArray(): Array[Double] =
    Array(
      1.0, 0.0, 0.0, 0.0,
      0.0, 1.0, 0.0, 0.0,
      0.0, 0.0, 1.0, 0.0,
      0.0, 0.0, 0.0, 1.0
    )

  def fromRows(
      m00: Double, m01: Double, m02: Double,
      m10: Double, m11: Double, m12: Double,
      m20: Double, m21: Double, m22: Double
  ): DMat =
    fromArray(
      Array(
        m00, m01, m02, 0.0,
        m10, m11, m12, 0.0,
        m20, m21, m22, 0.0,
        0.0, 0.0, 0.0, 1.0
      )
    )

  def fromArray(values: Array[Double]): DMat =
    val data = PrimitiveBuffers.ofSize[Double](16)
    var index = 0
    while index < 16 do
      data(index) = values(index)
      index += 1
    DMat.dense(4, 4, (data).toVector)

  def copyArray(matrix: DMat): Array[Double] =
    val out = Array.ofDim[Double](16)
    var index = 0
    while index < 16 do
      out(index) = matrix(index / 4, index % 4)
      index += 1
    out

  def multiply(left: DMat, right: DMat): DMat =
    val out = PrimitiveBuffers.ofSize[Double](16)
    var row = 0
    while row < 4 do
      var col = 0
      while col < 4 do
        var k = 0
        var sum = 0.0
        while k < 4 do
          sum += left(row, k) * right(k, col)
          k += 1
        out(row * 4 + col) = sum
        col += 1
      row += 1
    DMat.dense(4, 4, (out).toVector)

  def inverse(matrix: DMat): Either[RegistrationError, DMat] =
    matrix.solve(DMat.eye(matrix.cols)).left.map(_.getMessage).left.map(reason => RegistrationError.InvalidAffine("matrix inverse", reason))

  def squareRoot(matrix: DMat): Either[RegistrationError, DMat] =
    var y = matrix
    var z = identity
    val denominator = math.max(1.0, frobenius(matrix))
    var iteration = 0
    var residual = Double.PositiveInfinity
    while iteration < 24 && residual > 1e-10 do
      (z.solve(DMat.eye(z.cols)).left.map(_.getMessage), y.solve(DMat.eye(y.cols)).left.map(_.getMessage)) match
        case (Right(inverseZ), Right(inverseY)) =>
          y = average(y, inverseZ)
          z = average(z, inverseY)
          residual = frobeniusDifference(multiply(y, y), matrix) / denominator
        case (Left(reason), _) => return Left(RegistrationError.InvalidAffine("affine square root", reason))
        case (_, Left(reason)) => return Left(RegistrationError.InvalidAffine("affine square root", reason))
      iteration += 1
    if !residual.isFinite || residual > 1e-8 then
      Left(RegistrationError.AffineSquareRootDidNotConverge(iteration, residual))
    else
      val out = copyArray(y)
      out(12) = 0.0
      out(13) = 0.0
      out(14) = 0.0
      out(15) = 1.0
      Right(fromArray(out))

  def linearDeterminant(matrix: DMat): Double =
    val a = matrix(0, 0)
    val b = matrix(0, 1)
    val c = matrix(0, 2)
    val d = matrix(1, 0)
    val e = matrix(1, 1)
    val f = matrix(1, 2)
    val g = matrix(2, 0)
    val h = matrix(2, 1)
    val i = matrix(2, 2)
    a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)

  private def average(left: DMat, right: DMat): DMat =
    val out = PrimitiveBuffers.ofSize[Double](16)
    var index = 0
    while index < 16 do
      out(index) = 0.5 * (left(index / 4, index % 4) + right(index / 4, index % 4))
      index += 1
    DMat.dense(4, 4, (out).toVector)

  private def frobenius(matrix: DMat): Double =
    var sum = 0.0
    var index = 0
    while index < 16 do
      sum += matrix(index / 4, index % 4) * matrix(index / 4, index % 4)
      index += 1
    math.sqrt(sum)

  private def frobeniusDifference(left: DMat, right: DMat): Double =
    var sum = 0.0
    var index = 0
    while index < 16 do
      val difference = left(index / 4, index % 4) - right(index / 4, index % 4)
      sum += difference * difference
      index += 1
    math.sqrt(sum)
