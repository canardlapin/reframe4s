package reframe4s.benchmark.motion

import image4s.nifti.Nifti

import java.nio.file.Path

final case class MotionMetricInput(
    shape: Vector[Int],
    corrected: Array[Double],
    truth: Array[Double],
    mask: Array[Boolean],
    indexToWorld: Matrix4,
    estimatedPoses: Vector[Matrix4],
    truthPoses: Vector[Matrix4],
    landmarksRas: Vector[Vector[Double]],
    referenceIndex: Int
)

final case class FrameMotionMetrics(
    frame: Int,
    landmarkDisplacementP95Mm: Double,
    relativeRotationErrorDegrees: Double,
    framewiseDisplacementErrorMm: Option[Double],
    correctedImageNrmse: Option[Double],
    boundaryShellNrmse: Option[Double],
    temporalDifferenceNrmse: Option[Double],
    edgeEnergyLogError: Option[Double],
    ringingFraction: Option[Double]
)

final case class MotionMetricSummary(
    physicalLandmarkDisplacementP95Mm: Double,
    correctedImageNrmse: Double,
    relativeRotationErrorP95Degrees: Double,
    framewiseDisplacementErrorP95Mm: Double,
    boundaryShellNrmse: Double,
    temporalDifferenceNrmse: Double,
    edgeEnergyLogError: Double,
    ringingFraction: Double
):
  def finite: Boolean =
    Vector(
      physicalLandmarkDisplacementP95Mm,
      correctedImageNrmse,
      relativeRotationErrorP95Degrees,
      framewiseDisplacementErrorP95Mm,
      boundaryShellNrmse,
      temporalDifferenceNrmse,
      edgeEnergyLogError,
      ringingFraction
    ).forall(_.isFinite)

final case class MotionMetricResult(
    frames: Vector[FrameMotionMetrics],
    summary: MotionMetricSummary
)

final case class MotionMetricRecord(
    schemaVersion: String,
    definitionVersion: String,
    protocolSha256: String,
    scorerRevision: String,
    runRecordSha256: String,
    subject: String,
    scenario: String,
    stratum: String,
    court: String,
    implementation: String,
    phase: String,
    repetition: Int,
    allocatedCoreCount: Int,
    frames: Vector[FrameMotionMetrics],
    metrics: MotionMetricSummary
)

object MotionMetricScorer:
  val SchemaVersion = "reframe4s.motion-superiority.metrics/v1"
  val DefinitionVersion = "motion-metrics-v1"
  private val HeadRadiusMm = 50.0

  def score(input: MotionMetricInput): Either[RunnerError, MotionMetricResult] =
    for
      dimensions <- dimensionsOf(input)
      (nx, ny, nz, nt) = dimensions
      spatialSize = nx * ny * nz
      _ <- validateInput(input, spatialSize, nt)
      truthStandardDeviation <- standardDeviation(
        input.truth,
        input.mask,
        dimensions,
        input.referenceIndex,
        temporalDifferences = false
      )
      truthDifferenceStandardDeviation <- standardDeviation(
        input.truth,
        input.mask,
        dimensions,
        input.referenceIndex,
        temporalDifferences = true
      )
      inverseIndexToWorld <- input.indexToWorld.inverse
      boundaryShell = innerBoundaryShell(input.mask, nx, ny, nz, 3)
      localEnvelope = localTruthEnvelope(input.truth, dimensions)
      poseMetrics <- scorePoses(input)
      imageMetrics <- scoreImages(
        input,
        dimensions,
        boundaryShell,
        localEnvelope,
        inverseIndexToWorld,
        truthStandardDeviation,
        truthDifferenceStandardDeviation
      )
      frames = mergeFrames(poseMetrics, imageMetrics)
      summary <- summarize(
        input,
        frames,
        truthStandardDeviation,
        truthDifferenceStandardDeviation,
        boundaryShell,
        inverseIndexToWorld,
        localEnvelope,
        dimensions
      )
    yield MotionMetricResult(frames, summary)

  def scoreFiles(
      corrected: Path,
      estimatedPoses: Path,
      motionFreeTruth: Path,
      truthPoses: Path,
      mask: Path,
      landmarks: Path,
      referenceIndex: Int
  ): Either[RunnerError, MotionMetricResult] =
    for
      correctedDecoded <- Nifti
        .readScalar(corrected)
        .left
        .map(error => RunnerError.Nifti(error.message))
      truthDecoded <- Nifti
        .readScalar(motionFreeTruth)
        .left
        .map(error => RunnerError.Nifti(error.message))
      maskDecoded <- Nifti
        .readScalar(mask)
        .left
        .map(error => RunnerError.Nifti(error.message))
      correctedValues <- d3Values(correctedDecoded.image, "corrected image")
      truthValues <- d3Values(truthDecoded.image, "motion-free truth")
      maskValues <- d3Values(maskDecoded.image, "scoring mask")
      estimated <- EvidenceIo.readCanonicalPoses(estimatedPoses)
      expected <- EvidenceIo.readCanonicalPoses(truthPoses)
      points <- readLandmarks(landmarks)
      affine <- Matrix4.create(
        truthDecoded.header.preferredAffine.rowMajor
      )
      _ <-
        if correctedDecoded.header.logicalShape ==
            truthDecoded.header.logicalShape
        then Right(())
        else
          Left(
            RunnerError.UnsupportedInput(
              "corrected and motion-free truth shapes differ"
            )
          )
      _ <-
        if maskDecoded.header.logicalShape ==
            truthDecoded.header.spatialShape
        then Right(())
        else
          Left(
            RunnerError.UnsupportedInput(
              "scoring mask shape differs from the truth spatial shape"
            )
          )
      _ <- sameAffine(
        correctedDecoded.header.preferredAffine.rowMajor,
        truthDecoded.header.preferredAffine.rowMajor,
        "corrected image"
      )
      _ <- sameAffine(
        maskDecoded.header.preferredAffine.rowMajor,
        truthDecoded.header.preferredAffine.rowMajor,
        "scoring mask"
      )
      result <- score(
        MotionMetricInput(
          shape = truthDecoded.header.logicalShape,
          corrected = correctedValues,
          truth = truthValues,
          mask = maskValues.map(_ > 0.5),
          indexToWorld = affine,
          estimatedPoses = estimated,
          truthPoses = expected,
          landmarksRas = points,
          referenceIndex = referenceIndex
        )
      )
    yield result

  private def dimensionsOf(
      input: MotionMetricInput
  ): Either[RunnerError, (Int, Int, Int, Int)] =
    input.shape match
      case Vector(nx, ny, nz, nt)
          if nx > 0 && ny > 0 && nz > 0 && nt > 1 =>
        Right((nx, ny, nz, nt))
      case other =>
        Left(
          RunnerError.UnsupportedInput(
            s"motion scoring requires a positive rank-four shape, got $other"
          )
        )

  private def validateInput(
      input: MotionMetricInput,
      spatialSize: Int,
      frameCount: Int
  ): Either[RunnerError, Unit] =
    val expectedValues = spatialSize * frameCount
    if
      input.corrected.length != expectedValues ||
      input.truth.length != expectedValues
    then
      Left(
        RunnerError.UnsupportedInput(
          s"image payload lengths must equal $expectedValues"
        )
      )
    else if input.mask.length != spatialSize then
      Left(
        RunnerError.UnsupportedInput(
          s"mask payload length must equal $spatialSize"
        )
      )
    else if !input.mask.contains(true) then
      Left(RunnerError.UnsupportedInput("scoring mask is empty"))
    else if
      input.corrected.exists(value => !value.isFinite) ||
      input.truth.exists(value => !value.isFinite)
    then
      Left(
        RunnerError.UnsupportedInput(
          "scoring images contain non-finite samples"
        )
      )
    else if
      input.estimatedPoses.size != frameCount ||
      input.truthPoses.size != frameCount
    then
      Left(
        RunnerError.UnsupportedInput(
          s"pose counts must equal the frame count $frameCount"
        )
      )
    else if input.landmarksRas.isEmpty then
      Left(RunnerError.UnsupportedInput("landmark set is empty"))
    else if
      input.landmarksRas.exists(point =>
        point.size != 3 || point.exists(value => !value.isFinite)
      )
    then
      Left(
        RunnerError.UnsupportedInput(
          "landmarks must be finite physical RAS triples"
        )
      )
    else if
      input.referenceIndex < 0 || input.referenceIndex >= frameCount
    then
      Left(
        RunnerError.UnsupportedInput(
          s"reference index ${input.referenceIndex} is outside $frameCount frames"
        )
      )
    else Right(())

  private final case class PoseFrame(
      landmarkP95: Double,
      rotationDegrees: Double,
      framewiseDisplacementError: Option[Double]
  )

  private def scorePoses(
      input: MotionMetricInput
  ): Either[RunnerError, Vector[PoseFrame]] =
    input.estimatedPoses.indices.foldLeft[
      Either[RunnerError, Vector[PoseFrame]]
    ](Right(Vector.empty)) { (accumulated, frame) =>
      for
        found <- accumulated
        truthInverse <- input.truthPoses(frame).inverse
        poseError =
          input.estimatedPoses(frame).multiply(truthInverse)
        displacements <- sequence(
          input.landmarksRas.map { fixedLandmark =>
            poseError.transform(fixedLandmark).map { estimated =>
              euclideanDistance(estimated, fixedLandmark)
            }
          }
        )
        rotation <- rotationAngle(poseError)
        fd <-
          if frame == 0 then Right(None)
          else
            for
              estimatedPreviousInverse <-
                input.estimatedPoses(frame - 1).inverse
              truthPreviousInverse <- input.truthPoses(frame - 1).inverse
              estimatedRelative =
                estimatedPreviousInverse.multiply(
                  input.estimatedPoses(frame)
                )
              truthRelative =
                truthPreviousInverse.multiply(input.truthPoses(frame))
              estimatedFd <- framewiseDisplacement(estimatedRelative)
              truthFd <- framewiseDisplacement(truthRelative)
            yield Some(math.abs(estimatedFd - truthFd))
      yield
        found :+ PoseFrame(
          percentile95(displacements),
          math.toDegrees(rotation),
          fd
        )
    }

  private final case class ImageFrame(
      correctedNrmse: Option[Double],
      boundaryNrmse: Option[Double],
      temporalDifferenceNrmse: Option[Double],
      edgeEnergyLogError: Option[Double],
      ringingFraction: Option[Double]
  )

  private def scoreImages(
      input: MotionMetricInput,
      dimensions: (Int, Int, Int, Int),
      shell: Array[Boolean],
      envelope: (Array[Double], Array[Double]),
      inverseIndexToWorld: Matrix4,
      truthSd: Double,
      truthDifferenceSd: Double
  ): Either[RunnerError, Vector[ImageFrame]] =
    val nt = dimensions._4
    val frameResults = Vector.newBuilder[ImageFrame]
    var failure = Option.empty[RunnerError]
    var frame = 0
    while frame < nt && failure.isEmpty do
      if frame == input.referenceIndex then
        frameResults += ImageFrame(None, None, None, None, None)
      else
        val correctedSquaredError =
          squaredErrorForFrame(
            input.corrected,
            input.truth,
            input.mask,
            dimensions,
            frame
          )
        val boundarySquaredError =
          squaredErrorForFrame(
            input.corrected,
            input.truth,
            shell,
            dimensions,
            frame
          )
        val temporalSquaredError =
          if frame == 0 then None
          else
            Some(
              temporalSquaredErrorForFrame(
                input.corrected,
                input.truth,
                input.mask,
                dimensions,
                frame
              )
            )
        val edge =
          edgeEnergyForFrame(
            input,
            inverseIndexToWorld,
            dimensions,
            frame
          )
        val ringing =
          ringingForFrame(input, envelope, dimensions, frame)
        val values =
          for
            correctedNrmse <- normalizedRootMeanSquare(
              correctedSquaredError,
              truthSd,
              "corrected-image frame"
            )
            boundaryNrmse <- normalizedRootMeanSquare(
              boundarySquaredError,
              truthSd,
              "boundary-shell frame"
            )
            temporalNrmse <- temporalSquaredError match
              case None => Right(None)
              case Some(value) =>
                normalizedRootMeanSquare(
                  value,
                  truthDifferenceSd,
                  "temporal-difference frame"
                ).map(Some(_))
            edgeError <- edge.map { case (correctedEnergy, truthEnergy) =>
              math.abs(math.log(correctedEnergy / truthEnergy))
            }
          yield
            ImageFrame(
              Some(correctedNrmse),
              Some(boundaryNrmse),
              temporalNrmse,
              Some(edgeError),
              Some(ringing)
            )
        values match
          case Left(error) => failure = Some(error)
          case Right(value) => frameResults += value
      frame += 1
    failure.toLeft(frameResults.result())

  private def mergeFrames(
      poses: Vector[PoseFrame],
      images: Vector[ImageFrame]
  ): Vector[FrameMotionMetrics] =
    poses.zip(images).zipWithIndex.map {
      case ((pose, image), frame) =>
        FrameMotionMetrics(
          frame,
          pose.landmarkP95,
          pose.rotationDegrees,
          pose.framewiseDisplacementError,
          image.correctedNrmse,
          image.boundaryNrmse,
          image.temporalDifferenceNrmse,
          image.edgeEnergyLogError,
          image.ringingFraction
        )
    }

  private def summarize(
      input: MotionMetricInput,
      frames: Vector[FrameMotionMetrics],
      truthSd: Double,
      truthDifferenceSd: Double,
      shell: Array[Boolean],
      inverseIndexToWorld: Matrix4,
      envelope: (Array[Double], Array[Double]),
      dimensions: (Int, Int, Int, Int)
  ): Either[RunnerError, MotionMetricSummary] =
    for
      landmarkDisplacements <- allLandmarkDisplacements(input)
      corrected <- normalizedRootMeanSquare(
        squaredErrorAllFrames(
          input.corrected,
          input.truth,
          input.mask,
          dimensions,
          input.referenceIndex
        ),
        truthSd,
        "corrected image"
      )
      boundary <- normalizedRootMeanSquare(
        squaredErrorAllFrames(
          input.corrected,
          input.truth,
          shell,
          dimensions,
          input.referenceIndex
        ),
        truthSd,
        "boundary shell"
      )
      temporal <- normalizedRootMeanSquare(
        temporalSquaredErrorAllFrames(
          input.corrected,
          input.truth,
          input.mask,
          dimensions
        ),
        truthDifferenceSd,
        "temporal difference"
      )
      energy <- edgeEnergyAllFrames(
        input,
        inverseIndexToWorld,
        dimensions
      )
      (correctedEnergy, truthEnergy) = energy
      ringing = ringingAllFrames(
        input,
        envelope,
        dimensions,
        input.referenceIndex
      )
      summary =
        MotionMetricSummary(
          percentile95(landmarkDisplacements),
          corrected,
          percentile95(frames.map(_.relativeRotationErrorDegrees)),
          percentile95(
            frames.flatMap(_.framewiseDisplacementErrorMm)
          ),
          boundary,
          temporal,
          math.abs(math.log(correctedEnergy / truthEnergy)),
          ringing
        )
      _ <-
        if summary.finite then Right(())
        else
          Left(
            RunnerError.UnsupportedInput(
              "motion metric summary contains a non-finite result"
            )
          )
    yield summary

  private def allLandmarkDisplacements(
      input: MotionMetricInput
  ): Either[RunnerError, Vector[Double]] =
    input.estimatedPoses.indices.foldLeft[
      Either[RunnerError, Vector[Double]]
    ](Right(Vector.empty)) { (accumulated, frame) =>
      for
        found <- accumulated
        truthInverse <- input.truthPoses(frame).inverse
        poseError =
          input.estimatedPoses(frame).multiply(truthInverse)
        next <- sequence(
          input.landmarksRas.map { fixedLandmark =>
            poseError.transform(fixedLandmark).map { estimated =>
              euclideanDistance(estimated, fixedLandmark)
            }
          }
        )
      yield found ++ next
    }

  private def standardDeviation(
      values: Array[Double],
      mask: Array[Boolean],
      dimensions: (Int, Int, Int, Int),
      referenceIndex: Int,
      temporalDifferences: Boolean
  ): Either[RunnerError, Double] =
    val (nx, ny, nz, nt) = dimensions
    val spatialSize = nx * ny * nz
    var count = 0L
    var sum = 0.0
    var sumSquares = 0.0
    var spatial = 0
    while spatial < spatialSize do
      if mask(spatial) then
        var frame = if temporalDifferences then 1 else 0
        while frame < nt do
          if temporalDifferences || frame != referenceIndex then
            val current = values(spatial * nt + frame)
            val value =
              if temporalDifferences then
                current - values(spatial * nt + frame - 1)
              else current
            count += 1
            sum += value
            sumSquares += value * value
          frame += 1
      spatial += 1
    val variance =
      if count == 0 then Double.NaN
      else math.max(0.0, sumSquares / count.toDouble -
        (sum / count.toDouble) * (sum / count.toDouble))
    val result = math.sqrt(variance)
    if result.isFinite && result > 0.0 then Right(result)
    else
      Left(
        RunnerError.UnsupportedInput(
          if temporalDifferences then
            "truth temporal-difference standard deviation is not positive"
          else "truth standard deviation is not positive"
        )
      )

  private def squaredErrorForFrame(
      corrected: Array[Double],
      truth: Array[Double],
      mask: Array[Boolean],
      dimensions: (Int, Int, Int, Int),
      frame: Int
  ): (Double, Long) =
    val (nx, ny, nz, nt) = dimensions
    val spatialSize = nx * ny * nz
    var sum = 0.0
    var count = 0L
    var spatial = 0
    while spatial < spatialSize do
      if mask(spatial) then
        val index = spatial * nt + frame
        val difference = corrected(index) - truth(index)
        sum += difference * difference
        count += 1
      spatial += 1
    sum -> count

  private def squaredErrorAllFrames(
      corrected: Array[Double],
      truth: Array[Double],
      mask: Array[Boolean],
      dimensions: (Int, Int, Int, Int),
      referenceIndex: Int
  ): (Double, Long) =
    val (_, _, _, nt) = dimensions
    var sum = 0.0
    var count = 0L
    var frame = 0
    while frame < nt do
      if frame != referenceIndex then
        val (frameSum, frameCount) =
          squaredErrorForFrame(
            corrected,
            truth,
            mask,
            dimensions,
            frame
          )
        sum += frameSum
        count += frameCount
      frame += 1
    sum -> count

  private def temporalSquaredErrorForFrame(
      corrected: Array[Double],
      truth: Array[Double],
      mask: Array[Boolean],
      dimensions: (Int, Int, Int, Int),
      frame: Int
  ): (Double, Long) =
    val (nx, ny, nz, nt) = dimensions
    val spatialSize = nx * ny * nz
    var sum = 0.0
    var count = 0L
    var spatial = 0
    while spatial < spatialSize do
      if mask(spatial) then
        val current = spatial * nt + frame
        val previous = current - 1
        val correctedDifference =
          corrected(current) - corrected(previous)
        val truthDifference = truth(current) - truth(previous)
        val error = correctedDifference - truthDifference
        sum += error * error
        count += 1
      spatial += 1
    sum -> count

  private def temporalSquaredErrorAllFrames(
      corrected: Array[Double],
      truth: Array[Double],
      mask: Array[Boolean],
      dimensions: (Int, Int, Int, Int)
  ): (Double, Long) =
    val (_, _, _, nt) = dimensions
    var sum = 0.0
    var count = 0L
    var frame = 1
    while frame < nt do
      val (frameSum, frameCount) =
        temporalSquaredErrorForFrame(
          corrected,
          truth,
          mask,
          dimensions,
          frame
        )
      sum += frameSum
      count += frameCount
      frame += 1
    sum -> count

  private def normalizedRootMeanSquare(
      squaredError: (Double, Long),
      denominator: Double,
      label: String
  ): Either[RunnerError, Double] =
    val (sum, count) = squaredError
    if count <= 0 then
      Left(
        RunnerError.UnsupportedInput(
          s"$label has no registered samples"
        )
      )
    else
      val result = math.sqrt(sum / count.toDouble) / denominator
      if result.isFinite then Right(result)
      else
        Left(
          RunnerError.UnsupportedInput(
            s"$label NRMSE is non-finite"
          )
        )

  private def edgeEnergyForFrame(
      input: MotionMetricInput,
      inverseIndexToWorld: Matrix4,
      dimensions: (Int, Int, Int, Int),
      frame: Int
  ): Either[RunnerError, (Double, Double)] =
    val (nx, ny, nz, _) = dimensions
    var correctedEnergy = 0.0
    var truthEnergy = 0.0
    var count = 0L
    var i = 1
    while i + 1 < nx do
      var j = 1
      while j + 1 < ny do
        var k = 1
        while k + 1 < nz do
          val spatial = spatialIndex(i, j, k, ny, nz)
          if
            input.mask(spatial) &&
            axisNeighborsMasked(input.mask, i, j, k, nx, ny, nz)
          then
            val correctedGradient =
              physicalGradient(
                input.corrected,
                inverseIndexToWorld,
                i,
                j,
                k,
                frame,
                dimensions
              )
            val truthGradient =
              physicalGradient(
                input.truth,
                inverseIndexToWorld,
                i,
                j,
                k,
                frame,
                dimensions
              )
            correctedEnergy += squaredNorm(correctedGradient)
            truthEnergy += squaredNorm(truthGradient)
            count += 1
          k += 1
        j += 1
      i += 1
    if
      count > 0 &&
      correctedEnergy.isFinite &&
      truthEnergy.isFinite &&
      correctedEnergy > 0.0 &&
      truthEnergy > 0.0
    then Right(correctedEnergy -> truthEnergy)
    else
      Left(
        RunnerError.UnsupportedInput(
          s"frame $frame has no positive finite physical-gradient energy"
        )
      )

  private def edgeEnergyAllFrames(
      input: MotionMetricInput,
      inverseIndexToWorld: Matrix4,
      dimensions: (Int, Int, Int, Int)
  ): Either[RunnerError, (Double, Double)] =
    val (_, _, _, nt) = dimensions
    var corrected = 0.0
    var truth = 0.0
    var frame = 0
    var failure = Option.empty[RunnerError]
    while frame < nt && failure.isEmpty do
      if frame != input.referenceIndex then
        edgeEnergyForFrame(
          input,
          inverseIndexToWorld,
          dimensions,
          frame
        ) match
          case Left(error) => failure = Some(error)
          case Right((correctedFrame, truthFrame)) =>
            corrected += correctedFrame
            truth += truthFrame
      frame += 1
    failure.toLeft(corrected -> truth)

  private def physicalGradient(
      values: Array[Double],
      inverseIndexToWorld: Matrix4,
      i: Int,
      j: Int,
      k: Int,
      frame: Int,
      dimensions: (Int, Int, Int, Int)
  ): Vector[Double] =
    val (_, ny, nz, nt) = dimensions
    def value(x: Int, y: Int, z: Int): Double =
      values(spatialIndex(x, y, z, ny, nz) * nt + frame)
    val indexGradient =
      Vector(
        (value(i + 1, j, k) - value(i - 1, j, k)) * 0.5,
        (value(i, j + 1, k) - value(i, j - 1, k)) * 0.5,
        (value(i, j, k + 1) - value(i, j, k - 1)) * 0.5
      )
    Vector.tabulate(3) { worldAxis =>
      var indexAxis = 0
      var total = 0.0
      while indexAxis < 3 do
        total +=
          inverseIndexToWorld(indexAxis, worldAxis) *
            indexGradient(indexAxis)
        indexAxis += 1
      total
    }

  private def axisNeighborsMasked(
      mask: Array[Boolean],
      i: Int,
      j: Int,
      k: Int,
      nx: Int,
      ny: Int,
      nz: Int
  ): Boolean =
    i > 0 && i + 1 < nx &&
    j > 0 && j + 1 < ny &&
    k > 0 && k + 1 < nz &&
    mask(spatialIndex(i - 1, j, k, ny, nz)) &&
    mask(spatialIndex(i + 1, j, k, ny, nz)) &&
    mask(spatialIndex(i, j - 1, k, ny, nz)) &&
    mask(spatialIndex(i, j + 1, k, ny, nz)) &&
    mask(spatialIndex(i, j, k - 1, ny, nz)) &&
    mask(spatialIndex(i, j, k + 1, ny, nz))

  private def ringingForFrame(
      input: MotionMetricInput,
      envelope: (Array[Double], Array[Double]),
      dimensions: (Int, Int, Int, Int),
      frame: Int
  ): Double =
    val (nx, ny, nz, nt) = dimensions
    val spatialSize = nx * ny * nz
    val (minimum, maximum) = envelope
    val truthRange = globalRange(input.truth, dimensions, frame)
    val expansion = truthRange * 0.01
    var outside = 0L
    var count = 0L
    var spatial = 0
    while spatial < spatialSize do
      if input.mask(spatial) then
        val index = spatial * nt + frame
        val value = input.corrected(index)
        if
          value < minimum(index) - expansion ||
          value > maximum(index) + expansion
        then outside += 1
        count += 1
      spatial += 1
    if count == 0 then Double.NaN
    else outside.toDouble / count.toDouble

  private def ringingAllFrames(
      input: MotionMetricInput,
      envelope: (Array[Double], Array[Double]),
      dimensions: (Int, Int, Int, Int),
      referenceIndex: Int
  ): Double =
    val (nx, ny, nz, nt) = dimensions
    val spatialSize = nx * ny * nz
    val (minimum, maximum) = envelope
    var outside = 0L
    var count = 0L
    var frame = 0
    while frame < nt do
      if frame != referenceIndex then
        val expansion = globalRange(input.truth, dimensions, frame) * 0.01
        var spatial = 0
        while spatial < spatialSize do
          if input.mask(spatial) then
            val index = spatial * nt + frame
            val value = input.corrected(index)
            if
              value < minimum(index) - expansion ||
              value > maximum(index) + expansion
            then outside += 1
            count += 1
          spatial += 1
      frame += 1
    if count == 0 then Double.NaN
    else outside.toDouble / count.toDouble

  private def localTruthEnvelope(
      truth: Array[Double],
      dimensions: (Int, Int, Int, Int)
  ): (Array[Double], Array[Double]) =
    val minimumX = spatialFilter(truth, dimensions, axis = 0, minimum = true)
    val minimumY =
      spatialFilter(minimumX, dimensions, axis = 1, minimum = true)
    val minimumZ =
      spatialFilter(minimumY, dimensions, axis = 2, minimum = true)
    val maximumX =
      spatialFilter(truth, dimensions, axis = 0, minimum = false)
    val maximumY =
      spatialFilter(maximumX, dimensions, axis = 1, minimum = false)
    val maximumZ =
      spatialFilter(maximumY, dimensions, axis = 2, minimum = false)
    minimumZ -> maximumZ

  private def spatialFilter(
      values: Array[Double],
      dimensions: (Int, Int, Int, Int),
      axis: Int,
      minimum: Boolean
  ): Array[Double] =
    val (nx, ny, nz, nt) = dimensions
    val output = new Array[Double](values.length)
    var i = 0
    while i < nx do
      var j = 0
      while j < ny do
        var k = 0
        while k < nz do
          var frame = 0
          while frame < nt do
            var selected =
              if minimum then Double.PositiveInfinity
              else Double.NegativeInfinity
            var offset = -1
            while offset <= 1 do
              val x = if axis == 0 then i + offset else i
              val y = if axis == 1 then j + offset else j
              val z = if axis == 2 then k + offset else k
              if
                x >= 0 && x < nx &&
                y >= 0 && y < ny &&
                z >= 0 && z < nz
              then
                val value =
                  values(spatialIndex(x, y, z, ny, nz) * nt + frame)
                selected =
                  if minimum then math.min(selected, value)
                  else math.max(selected, value)
              offset += 1
            output(spatialIndex(i, j, k, ny, nz) * nt + frame) =
              selected
            frame += 1
          k += 1
        j += 1
      i += 1
    output

  private def innerBoundaryShell(
      mask: Array[Boolean],
      nx: Int,
      ny: Int,
      nz: Int,
      width: Int
  ): Array[Boolean] =
    var eroded = mask.clone()
    var step = 0
    while step < width do
      val next = new Array[Boolean](mask.length)
      var i = 0
      while i < nx do
        var j = 0
        while j < ny do
          var k = 0
          while k < nz do
            val index = spatialIndex(i, j, k, ny, nz)
            var retained = eroded(index)
            var di = -1
            while di <= 1 && retained do
              var dj = -1
              while dj <= 1 && retained do
                var dk = -1
                while dk <= 1 && retained do
                  val x = i + di
                  val y = j + dj
                  val z = k + dk
                  retained =
                    x >= 0 && x < nx &&
                      y >= 0 && y < ny &&
                      z >= 0 && z < nz &&
                      eroded(spatialIndex(x, y, z, ny, nz))
                  dk += 1
                dj += 1
              di += 1
            next(index) = retained
            k += 1
          j += 1
        i += 1
      eroded = next
      step += 1
    Array.tabulate(mask.length)(index => mask(index) && !eroded(index))

  private def framewiseDisplacement(
      relative: Matrix4
  ): Either[RunnerError, Double] =
    rotationAngle(relative).map { angle =>
      math.abs(relative(0, 3)) +
        math.abs(relative(1, 3)) +
        math.abs(relative(2, 3)) +
        HeadRadiusMm * angle
    }

  private def rotationAngle(
      matrix: Matrix4
  ): Either[RunnerError, Double] =
    val cosine =
      ((matrix(0, 0) + matrix(1, 1) + matrix(2, 2)) - 1.0) * 0.5
    if !cosine.isFinite then
      Left(
        RunnerError.InvalidMatrix(
          "rotation trace produced a non-finite angle"
        )
      )
    else Right(math.acos(math.max(-1.0, math.min(1.0, cosine))))

  private def percentile95(values: Vector[Double]): Double =
    if values.isEmpty then Double.NaN
    else
      val sorted = values.sorted
      val index =
        math.max(0, math.ceil(0.95 * sorted.size.toDouble).toInt - 1)
      sorted(index)

  private def globalRange(
      values: Array[Double],
      dimensions: (Int, Int, Int, Int),
      frame: Int
  ): Double =
    val (nx, ny, nz, nt) = dimensions
    val spatialSize = nx * ny * nz
    var minimum = Double.PositiveInfinity
    var maximum = Double.NegativeInfinity
    var spatial = 0
    while spatial < spatialSize do
      val value = values(spatial * nt + frame)
      minimum = math.min(minimum, value)
      maximum = math.max(maximum, value)
      spatial += 1
    maximum - minimum

  private def spatialIndex(
      i: Int,
      j: Int,
      k: Int,
      ny: Int,
      nz: Int
  ): Int =
    (i * ny + j) * nz + k

  private def squaredNorm(values: Vector[Double]): Double =
    values.iterator.map(value => value * value).sum

  private def euclideanDistance(
      left: Vector[Double],
      right: Vector[Double]
  ): Double =
    math.sqrt(
      left.zip(right).iterator
        .map { case (a, b) =>
          val difference = a - b
          difference * difference
        }
        .sum
    )

  private def sameAffine(
      actual: Vector[Double],
      expected: Vector[Double],
      label: String
  ): Either[RunnerError, Unit] =
    val maximum =
      actual.zip(expected).map { case (left, right) =>
        math.abs(left - right)
      }.maxOption.getOrElse(Double.PositiveInfinity)
    if actual.size == 16 && expected.size == 16 && maximum <= 1e-6 then
      Right(())
    else
      Left(
        RunnerError.UnsupportedInput(
          s"$label affine differs from motion-free truth by $maximum"
        )
      )

  private def d3Values[A <: image4s.FieldRole](
      sampled: image4s.SomeSampled[Double, A],
      label: String
  ): Either[RunnerError, Array[Double]] =
    sampled.fold(
      _ =>
        Left(
          RunnerError.UnsupportedInput(
            s"$label unexpectedly decoded as D2"
          )
        ),
      d3 => Right(d3.value.data.iterator.toArray)
    )

  private def readLandmarks(
      path: Path
  ): Either[RunnerError, Vector[Vector[Double]]] =
    EvidenceIo.readLines(path).flatMap {
      case header +: rows
          if header == "landmark,x_ras_mm,y_ras_mm,z_ras_mm" =>
        rows.zipWithIndex.foldLeft[
          Either[RunnerError, Vector[Vector[Double]]]
        ](Right(Vector.empty)) { case (accumulated, (row, index)) =>
          val fields = row.split(",", -1).toVector
          if fields.size != 4 || fields.headOption.forall(_.isEmpty) then
            Left(
              RunnerError.Io(
                "parse landmarks",
                path,
                s"row ${index + 2} must have a name and three coordinates"
              )
            )
          else
            fields.drop(1).foldLeft[
              Either[RunnerError, Vector[Double]]
            ](Right(Vector.empty)) { (values, field) =>
              for
                found <- values
                value <- field.toDoubleOption
                  .filter(_.isFinite)
                  .toRight(
                    RunnerError.Io(
                      "parse landmarks",
                      path,
                      s"row ${index + 2} has a non-finite coordinate"
                    )
                  )
              yield found :+ value
            }.flatMap(point => accumulated.map(_ :+ point))
        }
      case _ =>
        Left(
          RunnerError.Io(
            "parse landmarks",
            path,
            "expected landmark,x_ras_mm,y_ras_mm,z_ras_mm header"
          )
        )
    }

  private def sequence[A](
      values: Vector[Either[RunnerError, A]]
  ): Either[RunnerError, Vector[A]] =
    values.foldLeft[Either[RunnerError, Vector[A]]](Right(Vector.empty)) {
      (accumulated, value) =>
        for
          found <- accumulated
          next <- value
        yield found :+ next
    }
