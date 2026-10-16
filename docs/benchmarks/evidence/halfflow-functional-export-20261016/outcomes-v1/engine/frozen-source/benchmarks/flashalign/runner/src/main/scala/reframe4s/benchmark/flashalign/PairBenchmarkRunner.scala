package reframe4s.benchmark.flashalign

import image4s.Continuous
import image4s.SomeSampled
import image4s.nifti.Nifti
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.util.control.NonFatal

final case class LoadedInput[A](value: A, decompressMillis: Double)

trait PairInputLoader[A]:
  def load(pair: PairCase): Either[EvidenceError, LoadedInput[A]]

trait PairMethodAdapter[A]:
  def identity: MethodIdentity
  def run(pair: PairCase, inputs: A): Either[EvidenceError, CandidateRun]

trait NanoClock:
  def now(): Long

object NanoClock:
  val SystemClock: NanoClock = () => System.nanoTime()

final case class NiftiPair(
    moving: SomeSampled[Double, Continuous],
    fixed: SomeSampled[Double, Continuous]
)

/** Qualified external-input boundary. The runner hashes each exact byte stream,
  * then decodes through image4s-nifti and admits scalar D3 images only.
  */
object Image4sNiftiPairLoader extends PairInputLoader[NiftiPair]:
  def load(pair: PairCase): Either[EvidenceError, LoadedInput[NiftiPair]] =
    for
      _ <- verifyFile("moving", pair.moving)
      _ <- verifyFile("fixed", pair.fixed)
      decodeStart = System.nanoTime()
      decoded <- readBoth(pair)
      decodeMillis = (System.nanoTime() - decodeStart).toDouble / 1000000.0
    yield LoadedInput(decoded, decodeMillis)

  private def readBoth(pair: PairCase): Either[EvidenceError, NiftiPair] =
    for
      moving <- readD3("moving", pair.moving.path)
      fixed <- readD3("fixed", pair.fixed.path)
    yield NiftiPair(moving, fixed)

  private def verifyFile(label: String, artifact: LicensedArtifact): Either[EvidenceError, Unit] =
    if !Files.isRegularFile(artifact.path) then
      Left(EvidenceError.InputFailure(label, s"missing regular file ${artifact.path}"))
    else
      val actual = EvidenceHash.file(artifact.path)
      if actual == artifact.sha256 then Right(())
      else Left(EvidenceError.InputHashMismatch(label, artifact.sha256, actual))

  private def readD3(
      label: String,
      path: Path
  ): Either[EvidenceError, SomeSampled[Double, Continuous]] =
    Nifti.readScaledDouble(path)
      .left
      .map(error => EvidenceError.InputFailure(label, error.message))
      .flatMap(decoded =>
        decoded.image.fold(
          _ => Left(EvidenceError.InputFailure(label, "expected a scalar D3 NIfTI")),
          d3 =>
            if d3.value.nonSpatialAxes.size == 0 then Right(d3)
            else Left(EvidenceError.InputFailure(label, "non-spatial axes are not supported"))
        )
      )

object EvidenceHash:
  def bytes(bytes: Array[Byte]): Sha256 =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    Sha256.unsafe(digest.map(byte => f"${byte & 0xff}%02x").mkString)

  def utf8(value: String): Sha256 = bytes(value.getBytes(StandardCharsets.UTF_8))

  def file(path: Path): Sha256 = bytes(Files.readAllBytes(path))

  def matrix(rowMajor: Vector[Double]): Sha256 =
    utf8(rowMajor.map(java.lang.Double.toHexString).mkString("\n"))

object PairBenchmarkRunner:
  val SchemaVersion = "1.1.0"

  def run[A](
      runId: String,
      pair: PairCase,
      loader: PairInputLoader[A],
      adapter: PairMethodAdapter[A],
      environment: EnvironmentRecord,
      clock: NanoClock = NanoClock.SystemClock
  ): Either[EvidenceError, RawPairResult] =
    for
      checked <- validateRunInputs(runId, pair, adapter.identity, environment)
      result <- execute(runId, checked, loader, adapter, environment, clock)
    yield result

  private def validateRunInputs(
      runId: String,
      pair: PairCase,
      method: MethodIdentity,
      environment: EnvironmentRecord
  ): Either[EvidenceError, PairCase] =
    if runId.trim.isEmpty then Left(EvidenceError.EmptyField("run_id"))
    else if method.id.trim.isEmpty then Left(EvidenceError.EmptyField("method.id"))
    else if method.model.trim.isEmpty then Left(EvidenceError.EmptyField("method.model"))
    else if method.revision.trim.isEmpty then Left(EvidenceError.EmptyField("method.revision"))
    else if environment.os.trim.isEmpty || environment.cpu.trim.isEmpty || environment.jdk.trim.isEmpty then
      Left(EvidenceError.EmptyField("environment"))
    else if environment.threads < 1 then Left(EvidenceError.InvalidCandidate("threads must be positive"))
    else PairCase.validate(pair).flatMap { checked =>
      val actual = EvidenceHash.matrix(checked.initialization.movingToFixed)
      if actual == checked.initialization.sha256 then Right(checked)
      else Left(
        EvidenceError.InputHashMismatch("initialization", checked.initialization.sha256, actual)
      )
    }

  private def execute[A](
      runId: String,
      pair: PairCase,
      loader: PairInputLoader[A],
      adapter: PairMethodAdapter[A],
      environment: EnvironmentRecord,
      clock: NanoClock
  ): Either[EvidenceError, RawPairResult] =
    val started = clock.now()
    val readStarted = clock.now()
    val loaded = catchFailure(loader.load(pair))
    val readFinished = clock.now()
    val candidate = loaded match
      case Left(error) => failureCandidate(RunStatus.Unavailable, "input", error.message)
      case Right(inputs) =>
        catchFailure(adapter.run(pair, inputs.value)) match
          case Left(error) => failureCandidate(RunStatus.NumericalFailure, "adapter", error.message)
          case Right(value) => value
    val finished = clock.now()
    for
      checkedCandidate <- validateCandidate(candidate)
      metrics <- IndependentPairScorer.score(pair.landmarks, checkedCandidate)
      totalReadMs = nanosToMillis(readFinished - readStarted)
      decompressMs = loaded.toOption.map(_.decompressMillis).getOrElse(0.0)
      readMs = math.max(0.0, totalReadMs - decompressMs)
      elapsedMs = nanosToMillis(finished - started)
      stages = checkedCandidate.timingMs
      timing = CompleteTiming(
        read = readMs,
        decompress = decompressMs,
        prepare = stages.prepare,
        capture = stages.capture,
        optimize = stages.optimize,
        validate = stages.validate,
        output = stages.output,
        total = math.max(elapsedMs, readMs + decompressMs + stages.total)
      )
    yield RawPairResult(
      schemaVersion = SchemaVersion,
      runId = runId,
      pair = pair,
      method = adapter.identity,
      environment = environment,
      candidate = checkedCandidate,
      metrics = metrics,
      timingMs = timing
    )

  private def validateCandidate(candidate: CandidateRun): Either[EvidenceError, CandidateRun] =
    val successShape = candidate.status == RunStatus.Success && candidate.accepted
    val transformPresent = candidate.movingToFixed.exists(_.length == 16)
    val finiteTransform = candidate.movingToFixed.forall(_.forall(_.isFinite))
    val hashMatches = (candidate.movingToFixed, candidate.transformSha256) match
      case (Some(matrix), Some(hash)) => EvidenceHash.matrix(matrix) == hash
      case (None, None)               => true
      case _                          => false
    val validMetrics = candidate.candidateMetrics.values.flatten.forall(_.isFinite)
    val validCheckpoint = candidate.lastCheckpoint.forall { checkpoint =>
      checkpoint.movingToFixed.length == 16 &&
      checkpoint.movingToFixed.forall(_.isFinite) &&
      EvidenceHash.matrix(checkpoint.movingToFixed) == checkpoint.transformSha256 &&
      checkpoint.lastValidMovingToFixed.length == 16 &&
      checkpoint.lastValidMovingToFixed.forall(_.isFinite) &&
      EvidenceHash.matrix(checkpoint.lastValidMovingToFixed) ==
        checkpoint.lastValidTransformSha256 &&
      checkpoint.selectionObjective.isFinite &&
      checkpoint.acceptedSteps >= 0 &&
      checkpoint.initialOptimizationObjective.isFinite &&
      checkpoint.lastOptimizationObjective.isFinite
    }
    if successShape && !transformPresent then
      Left(EvidenceError.InvalidCandidate("accepted success requires a 4x4 transform"))
    else if !finiteTransform then Left(EvidenceError.InvalidCandidate("transform is non-finite"))
    else if !hashMatches then Left(EvidenceError.InvalidCandidate("transform hash is absent or incorrect"))
    else if !candidate.work.nonnegative then Left(EvidenceError.InvalidCandidate("work counts are negative"))
    else if !candidate.timingMs.valid then Left(EvidenceError.InvalidCandidate("stage timing is invalid"))
    else if !validMetrics then Left(EvidenceError.InvalidCandidate("candidate metrics are non-finite"))
    else if !validCheckpoint then
      Left(EvidenceError.InvalidCandidate("last checkpoint is invalid"))
    else if candidate.status != RunStatus.Success && candidate.failure.isEmpty then
      Left(EvidenceError.InvalidCandidate("non-success outcome requires failure detail"))
    else Right(candidate)

  private def catchFailure[A](operation: => Either[EvidenceError, A]): Either[EvidenceError, A] =
    try operation
    catch
      case NonFatal(error) =>
        Left(EvidenceError.AdapterFailure(Option(error.getMessage).getOrElse(error.getClass.getName)))

  private def failureCandidate(status: RunStatus, kind: String, detail: String): CandidateRun =
    CandidateRun(
      status = status,
      accepted = false,
      failure = Some(FailureDetail(kind, detail)),
      movingToFixed = None,
      transformSha256 = None,
      candidateMetrics = Map.empty,
      work = WorkCounts.Zero,
      timingMs = AlgorithmStageTimes.Zero
    )

  private def nanosToMillis(value: Long): Double = math.max(0L, value).toDouble / 1000000.0

object IndependentPairScorer:
  def score(
      landmarks: Vector[LandmarkTruth],
      candidate: CandidateRun
  ): Either[EvidenceError, PairMetrics] =
    candidate.movingToFixed match
      case None => Right(PairMetrics(None, None, None, candidate.candidateMetrics))
      case Some(matrix) if matrix.length != 16 =>
        Left(EvidenceError.InvalidMatrixLength("candidate.moving_to_fixed", matrix.length))
      case Some(matrix) =>
        val errors = landmarks.map { landmark =>
          val predicted = applyAffine(matrix, landmark.movingWorldMm)
          math.sqrt(predicted.zip(landmark.fixedWorldMm).map { case (a, b) =>
            val delta = a - b
            delta * delta
          }.sum)
        }.sorted
        if errors.isEmpty then Right(PairMetrics(None, None, None, candidate.candidateMetrics))
        else
          val rms = math.sqrt(errors.map(error => error * error).sum / errors.length.toDouble)
          val index95 = math.min(errors.length - 1, math.ceil(0.95 * errors.length).toInt - 1)
          Right(PairMetrics(Some(rms), Some(errors(index95)), Some(errors.last), candidate.candidateMetrics))

  private def applyAffine(matrix: Vector[Double], point: Vector[Double]): Vector[Double] =
    Vector.tabulate(3)(row =>
      matrix(row * 4) * point(0) +
        matrix(row * 4 + 1) * point(1) +
        matrix(row * 4 + 2) * point(2) +
        matrix(row * 4 + 3)
    )
