package reframe4s.benchmark.flashalign

import image4s.ContinuousImage
import image4s.SampleSpace
import image4s.geometry.Affine
import image4s.geometry.D3
import image4s.geometry.Dimension
import image4s.geometry.Frame
import ravel.Rank
import reframe4s.flashalign.AffineFlashalignResult
import reframe4s.flashalign.Flashalign
import reframe4s.flashalign.FlashalignConfig
import reframe4s.flashalign.FlashalignError
import reframe4s.flashalign.FlashalignInitializationPolicy
import reframe4s.flashalign.FlashalignPreset
import reframe4s.flashalign.FlashalignWorkCounts
import reframe4s.flashalign.RigidFlashalignResult
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Executes the presealed analytic linear court. The fixture generator and
  * summary calculation live outside candidate code; this runner only admits
  * the frozen NIfTI inputs, invokes each method, and emits raw rows.
  */
object LinearAccuracyCourt:
  val FixtureDescriptor = Paths.get("benchmarks/flashalign/fixtures/linear-accuracy-audit-v1.json")
  val CaseTable = Paths.get("benchmarks/flashalign/fixtures/linear-accuracy-audit-v1.cases.tsv")
  val FixtureDescriptorSha256 = Sha256.unsafe("7b9e2387701b9138cff66697a37b5a1c8d45c691d336ab9917eebba8a55ade0a")
  val CaseTableSha256 = Sha256.unsafe("c39c7c20b28c768ad548cfa3d7d83cc7f48e3cbfc959689b61c8bf220a79bf0e")
  val AfniImage = "afni/afni_make_build@sha256:6bc2b04e92e0874d7cf252006be35f2671438904c233f844633c40a8fc7cf9bf"
  val AfniVersion = "AFNI_26.1.04 (Jun 2 2026) [64-bit]"

  def main(arguments: Array[String]): Unit =
    val result = arguments.toVector match
      case Vector(raw, candidateRevision) =>
        run(Paths.get(raw), candidateRevision)
      case Vector(raw, candidateRevision, "--candidate-only-development") =>
        runCandidateOnlyDevelopment(Paths.get(raw), candidateRevision)
      case Vector(raw, candidateRevision, "--automatic-candidate-only-development") =>
        runAutomaticCandidateOnlyDevelopment(Paths.get(raw), candidateRevision)
      case Vector(raw, candidateRevision, "--truth-start-ordinary-rigid-development") =>
        runTruthStartOrdinaryRigidDevelopment(
          Paths.get(raw),
          candidateRevision,
          nativeOnly = false
        )
      case Vector(raw, candidateRevision, "--truth-start-ordinary-rigid-native-only-development") =>
        runTruthStartOrdinaryRigidDevelopment(
          Paths.get(raw),
          candidateRevision,
          nativeOnly = true
        )
      case _ =>
        Left(
          EvidenceError.InvalidCandidate(
            "usage: LinearAccuracyCourt <raw-jsonl> <candidate-revision> [--candidate-only-development|--automatic-candidate-only-development|--truth-start-ordinary-rigid-development|--truth-start-ordinary-rigid-native-only-development]"
          )
        )
    result.fold(error => throw new IllegalStateException(error.message), _ => ())

  def run(
      rawOutput: Path,
      candidateRevision: String
  ): Either[EvidenceError, Unit] =
    for
      _ <- verifySeal(FixtureDescriptor, FixtureDescriptorSha256)
      _ <- verifySeal(CaseTable, CaseTableSha256)
      cases <- readCases(CaseTable)
      _ <-
        if cases.size == 32 then Right(())
        else Left(EvidenceError.InvalidCandidate(s"sealed court requires 32 cases, got ${cases.size}"))
      environment = currentEnvironment
      flashalignRigid = new FlashalignAdapter("rigid", candidateRevision)
      flashalignAffine = new FlashalignAdapter("affine", candidateRevision)
      afniRigid = new AfniAdapter("rigid")
      afniAffine = new AfniAdapter("affine")
      rows <- cases.foldLeft[Either[EvidenceError, Vector[RawPairResult]]](Right(Vector.empty)) {
        (accumulated, courtCase) =>
          accumulated.flatMap { rows =>
            val candidate = if courtCase.model == "rigid" then flashalignRigid else flashalignAffine
            val comparator = if courtCase.model == "rigid" then afniRigid else afniAffine
            for
              flashalign <- PairBenchmarkRunner.run(
                s"linear-accuracy-2026-09-12-${courtCase.pair.id}-flashalign",
                courtCase.pair,
                Image4sNiftiPairLoader,
                candidate,
                environment
              )
              afni <- PairBenchmarkRunner.run(
                s"linear-accuracy-2026-09-12-${courtCase.pair.id}-3dallineate",
                courtCase.pair,
                Image4sNiftiPairLoader,
                comparator,
                environment
              )
            yield rows :+ flashalign :+ afni
          }
      }
      _ <- writeRows(rawOutput, rows)
    yield ()

  /** Rerun only Flashalign on an already opened court for diagnosis.
    *
    * This mode cannot produce confirmation or comparator evidence. Keeping it
    * in the sealed runner ensures that fixture admission, input hashes,
    * initialization, scoring, and candidate execution are identical to the
    * two-method court while avoiding a redundant containerized comparator run.
    */
  def runCandidateOnlyDevelopment(
      rawOutput: Path,
      candidateRevision: String
  ): Either[EvidenceError, Unit] =
    runDevelopment(rawOutput, candidateRevision, automatic = false)

  def runAutomaticCandidateOnlyDevelopment(
      rawOutput: Path,
      candidateRevision: String
  ): Either[EvidenceError, Unit] =
    runDevelopment(rawOutput, candidateRevision, automatic = true)

  /** Run the four opened ordinary rigid cases from their independent truth.
    *
    * This diagnostic distinguishes initialization/capture error from drift in
    * the frozen coarse-to-fine schedule or the native-resolution objective.
    * It is development-only and cannot be used as sealed confirmation because
    * the truth initializes the candidate.
    */
  def runTruthStartOrdinaryRigidDevelopment(
      rawOutput: Path,
      candidateRevision: String,
      nativeOnly: Boolean
  ): Either[EvidenceError, Unit] =
    for
      _ <- verifySeal(FixtureDescriptor, FixtureDescriptorSha256)
      _ <- verifySeal(CaseTable, CaseTableSha256)
      allCases <- readCases(CaseTable)
      cases = allCases.filter(courtCase =>
        courtCase.model == "rigid" &&
          courtCase.pair.cohort == PairCohort.OrdinarySameSubject
      )
      _ <-
        if cases.size == 4 then Right(())
        else Left(
          EvidenceError.InvalidCandidate(
            s"truth-start development requires four ordinary rigid cases, got ${cases.size}"
          )
        )
      environment = currentEnvironment
      flashalign = new FlashalignAdapter(
        "rigid",
        candidateRevision,
        nativeOnly = nativeOnly
      )
      rows <- cases.zipWithIndex.foldLeft[
        Either[EvidenceError, Vector[RawPairResult]]
      ](Right(Vector.empty)) { case (accumulated, (courtCase, index)) =>
        accumulated.flatMap { rows =>
          val truthInitialization = InitializationIdentity(
            "independent-truth-development-only",
            courtCase.truth,
            EvidenceHash.matrix(courtCase.truth)
          )
          PairBenchmarkRunner
            .run(
              s"linear-truth-start-${if nativeOnly then "native-only-" else ""}development-2026-09-13-${courtCase.pair.id}-flashalign",
              courtCase.pair.copy(initialization = truthInitialization),
              Image4sNiftiPairLoader,
              flashalign,
              environment
            )
            .map { row =>
              println(
                s"Flashalign truth-start ${if nativeOnly then "native-only " else ""}case ${index + 1}/${cases.size}: ${courtCase.pair.id} -> ${row.candidate.status}"
              )
              rows :+ row
            }
        }
      }
      _ <- writeRows(rawOutput, rows)
    yield ()

  private def runDevelopment(
      rawOutput: Path,
      candidateRevision: String,
      automatic: Boolean
  ): Either[EvidenceError, Unit] =
    for
      _ <- verifySeal(FixtureDescriptor, FixtureDescriptorSha256)
      _ <- verifySeal(CaseTable, CaseTableSha256)
      cases <- readCases(CaseTable)
      _ <-
        if cases.size == 32 then Right(())
        else Left(EvidenceError.InvalidCandidate(s"sealed court requires 32 cases, got ${cases.size}"))
      environment = currentEnvironment
      flashalignRigid = new FlashalignAdapter("rigid", candidateRevision, automatic)
      flashalignAffine = new FlashalignAdapter("affine", candidateRevision, automatic)
      rows <- cases.zipWithIndex.foldLeft[Either[EvidenceError, Vector[RawPairResult]]](Right(Vector.empty)) {
        case (accumulated, (courtCase, index)) =>
          accumulated.flatMap { rows =>
            val candidate =
              if courtCase.model == "rigid" then flashalignRigid
              else flashalignAffine
            PairBenchmarkRunner
              .run(
                s"linear-${if automatic then "automatic-" else ""}development-2026-09-13-${courtCase.pair.id}-flashalign",
                courtCase.pair,
                Image4sNiftiPairLoader,
                candidate,
                environment
              )
              .map { row =>
                println(
                  s"Flashalign development case ${index + 1}/${cases.size}: ${courtCase.pair.id} -> ${row.candidate.status}"
                )
                rows :+ row
              }
          }
      }
      _ <- writeRows(rawOutput, rows)
    yield ()

  private[flashalign] def sealedCaseCount: Either[EvidenceError, Int] =
    for
      _ <- verifySeal(FixtureDescriptor, FixtureDescriptorSha256)
      _ <- verifySeal(CaseTable, CaseTableSha256)
      cases <- readCases(CaseTable)
    yield cases.size

  private def verifySeal(path: Path, expected: Sha256): Either[EvidenceError, Unit] =
    if !Files.isRegularFile(path) then
      Left(EvidenceError.InputFailure("seal", s"missing $path"))
    else
      val actual = EvidenceHash.file(path)
      if actual == expected then Right(())
      else Left(EvidenceError.InputHashMismatch(path.toString, expected, actual))

  private def readCases(path: Path): Either[EvidenceError, Vector[CourtCase]] =
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector
    lines.drop(1).filter(_.trim.nonEmpty).foldLeft[Either[EvidenceError, Vector[CourtCase]]](Right(Vector.empty)) {
      (accumulated, line) => accumulated.flatMap(values => parseCase(line).map(values :+ _))
    }

  private def parseCase(line: String): Either[EvidenceError, CourtCase] =
    val fields = line.split("\\t", -1).toVector
    if fields.size != 7 then
      Left(EvidenceError.InvalidCandidate(s"expected 7 court fields, got ${fields.size}"))
    else
      for
        cohort <- parseCohort(fields(2))
        model <-
          if fields(3) == "rigid" || fields(3) == "affine" then Right(fields(3))
          else Left(EvidenceError.InvalidCandidate(s"unknown model ${fields(3)}"))
        truth <- parseMatrix(fields(6))
        moving = Paths.get(fields(4))
        fixed = Paths.get(fields(5))
        _ <- requireFile(moving)
        _ <- requireFile(fixed)
        initial = identityMatrix
        sampleHash = EvidenceHash.utf8(line)
        pair = PairCase(
          id = fields(0),
          subjectId = fields(1),
          cohort = cohort,
          moving = LicensedArtifact(
            moving,
            EvidenceHash.file(moving),
            "flashalign-linear-accuracy-audit-v1 independent continuous phantom",
            "Apache-2.0"
          ),
          fixed = LicensedArtifact(
            fixed,
            EvidenceHash.file(fixed),
            "flashalign-linear-accuracy-audit-v1 independent continuous phantom",
            "Apache-2.0"
          ),
          initialization = InitializationIdentity(
            "world-identity-v1",
            initial,
            EvidenceHash.matrix(initial)
          ),
          immutableSampleIdsSha256 = sampleHash,
          landmarks = landmarks(cohort, truth)
        )
      yield CourtCase(model, pair, truth)

  private def requireFile(path: Path): Either[EvidenceError, Unit] =
    if Files.isRegularFile(path) then Right(())
    else Left(EvidenceError.InputFailure("fixture", s"missing $path"))

  private def parseCohort(value: String): Either[EvidenceError, PairCohort] =
    PairCohort.values.find(_.id == value).toRight(
      EvidenceError.InvalidCandidate(s"unknown cohort $value")
    )

  private def parseMatrix(value: String): Either[EvidenceError, Vector[Double]] =
    try
      val parsed = value.split(",", -1).toVector.map(_.toDouble)
      if parsed.size != 16 || parsed.exists(number => !number.isFinite) then
        Left(EvidenceError.InvalidMatrixLength("truth", parsed.size))
      else Right(parsed)
    catch
      case NonFatal(error) => Left(EvidenceError.InvalidCandidate(error.getMessage))

  private def landmarks(
      cohort: PairCohort,
      truth: Vector[Double]
  ): Vector[LandmarkTruth] =
    val zs =
      if cohort == PairCohort.PartialSlab then Vector(-8.0, -4.0, 0.0, 4.0, 8.0)
      else Vector(-12.0, -6.0, 0.0, 6.0, 12.0)
    val xy = Vector((-12.0, -8.0), (10.0, -7.0), (-8.0, 9.0), (9.0, 8.0), (0.0, 0.0))
    (for
      z <- zs
      (x, y) <- xy
    yield Vector(x, y, z)).zipWithIndex.map { case (moving, index) =>
      LandmarkTruth(
        f"landmark-$index%02d",
        moving,
        applyAffine(truth, moving)
      )
    }

  private def applyAffine(matrix: Vector[Double], point: Vector[Double]): Vector[Double] =
    Vector.tabulate(3)(row =>
      matrix(row * 4) * point(0) +
        matrix(row * 4 + 1) * point(1) +
        matrix(row * 4 + 2) * point(2) +
        matrix(row * 4 + 3)
    )

  private def writeRows(path: Path, rows: Vector[RawPairResult]): Either[EvidenceError, Unit] =
    try
      Option(path.getParent).foreach(Files.createDirectories(_))
      val content = rows.map(EvidenceJson.render).mkString("", "\n", "\n")
      Files.writeString(
        path,
        content,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      Right(())
    catch
      case NonFatal(error) => Left(EvidenceError.AdapterFailure(error.getMessage))

  private def currentEnvironment: EnvironmentRecord =
    EnvironmentRecord(
      s"${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
      sys.env.getOrElse("FLASHALIGN_BENCHMARK_CPU", "Apple Silicon host; AFNI linux/amd64 emulation"),
      s"${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
      1
    )

  private val identityMatrix = Vector(
    1.0, 0.0, 0.0, 0.0,
    0.0, 1.0, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
  )

  private final case class CourtCase(
      model: String,
      pair: PairCase,
      truth: Vector[Double]
  )

private final class FlashalignAdapter(
    model: String,
    candidateRevision: String,
    automatic: Boolean = false,
    nativeOnly: Boolean = false
) extends PairMethodAdapter[NiftiPair]:
  val identity: MethodIdentity =
    val configuration =
      s"flashalign-linear-audit-v1|$model|within-modality|${if automatic then "automatic-structural-capture" else "supplied-world-identity"}|${if nativeOnly then "native-only-development|" else ""}single-thread"
    MethodIdentity(
      s"flashalign-$model",
      model,
      candidateRevision,
      EvidenceHash.utf8(configuration),
      None,
      None
    )

  def run(pair: PairCase, inputs: NiftiPair): Either[EvidenceError, CandidateRun] =
    inputs.moving.fold(
      _ => Right(failure(RunStatus.InvalidGeometry, "moving-rank", "expected D3 moving image")),
      movingD3 =>
        inputs.fixed.fold(
          _ => Right(failure(RunStatus.InvalidGeometry, "fixed-rank", "expected D3 fixed image")),
          fixedD3 =>
            for
              moving <- movingD3.value.requireDataRank[3].left.map(error => EvidenceError.InputFailure("moving", error.message))
              fixed <- fixedD3.value.requireDataRank[3].left.map(error => EvidenceError.InputFailure("fixed", error.message))
              result <- runTyped(pair, moving, fixed)
            yield result
        )
    )

  private def runTyped[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      MovingSpace <: SampleSpace[Moving, D3],
      FixedSpace <: SampleSpace[Fixed, D3]
  ](
      pair: PairCase,
      moving: ContinuousImage[MovingSpace, Double, Rank[3]],
      fixed: ContinuousImage[FixedSpace, Double, Rank[3]]
  )(using Dimension[D3]): Either[EvidenceError, CandidateRun] =
    val initialization =
      if automatic then
        FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
      else FlashalignInitializationPolicy.SuppliedWorldTransform
    val config =
      if nativeOnly then
        val base = reframe4s.flashalign.LinearPresetPolicies.WithinModality
        val nativePreparation = base.preparation.copy(
          effectiveResolutionMillimetres = Vector(
            base.preparation.effectiveResolutionMillimetres.last
          ),
          stencilSpacingMillimetres = Vector(
            base.preparation.stencilSpacingMillimetres.last
          )
        )
        val policy = reframe4s.flashalign.LinearPresetPolicy
          .create(
            s"${base.id}-native-only-development",
            base.version,
            base.preset,
            base.patch,
            base.sampling,
            nativePreparation,
            base.capture,
            base.rigidTrust,
            base.affineTrust,
            base.qc,
            base.calibration
          )
          .fold(error => throw new IllegalStateException(error.message), value => value)
        FlashalignConfig.create(policy, initialization)
      else
        FlashalignConfig.forPreset(FlashalignPreset.WithinModality, initialization)
    val preparationStarted = System.nanoTime()
    val compiled =
      if model == "rigid" then Flashalign.rigid(moving, fixed, config)
      else Flashalign.affine(moving, fixed, config)
    val preparationMillis = elapsed(preparationStarted)
    compiled match
      case Left(error) => Right(flashalignFailure(error, preparationMillis, 0.0))
      case Right(plan) =>
        val optimizationStarted = System.nanoTime()
        val outcome: Either[FlashalignError, Either[RigidFlashalignResult[Moving, Fixed], AffineFlashalignResult[Moving, Fixed]]] =
          plan match
            case rigidPlan: reframe4s.flashalign.RigidFlashalignPlan[Moving, Fixed] =>
              if automatic then rigidPlan.run(rigidPlan.newWorkspace()).map(Left.apply)
              else
                Rigid3
                  .fromRowMajor(moving.frame, fixed.frame, pair.initialization.movingToFixed)
                  .left
                  .map(error => FlashalignError.Geometry(error match
                    case reframe4s.lie.RigidError.Geometry(geometry) => geometry
                    case other => throw new IllegalArgumentException(other.message)
                  ))
                  .flatMap(initial => rigidPlan.runFrom(initial, rigidPlan.newWorkspace()))
                  .map(Left.apply)
            case affinePlan: reframe4s.flashalign.AffineFlashalignPlan[Moving, Fixed] =>
              if automatic then affinePlan.run(affinePlan.newWorkspace()).map(Right.apply)
              else
                Affine
                  .fromRowMajor[D3](pair.initialization.movingToFixed)
                  .left
                  .map(FlashalignError.Geometry.apply)
                  .map(operator => FramedAffine.betweenFrames(moving.frame, fixed.frame)(operator))
                  .flatMap(initial => affinePlan.runFrom(initial, affinePlan.newWorkspace()))
                  .map(Right.apply)
        val optimizeMillis = elapsed(optimizationStarted)
        outcome match
          case Left(error) => Right(flashalignFailure(error, preparationMillis, optimizeMillis))
          case Right(Left(result)) => Right(success(result.movingToFixed.operator.rowMajor, result.report.finalObjective, result.diagnostics, preparationMillis, optimizeMillis))
          case Right(Right(result)) => Right(success(result.movingToFixed.operator.rowMajor, result.report.finalObjective, result.diagnostics, preparationMillis, optimizeMillis))

  private def success(
      matrix: Vector[Double],
      objective: Double,
      diagnostics: reframe4s.flashalign.FlashalignDiagnostics,
      preparationMillis: Double,
      totalExecutionMillis: Double
  ): CandidateRun =
    val work = diagnostics.work
    val stageTimes = diagnostics.capture match
      case None => AlgorithmStageTimes(preparationMillis, 0.0, totalExecutionMillis, 0.0, 0.0)
      case Some(capture) =>
        AlgorithmStageTimes(
          preparationMillis,
          nanosToMillis(capture.captureElapsedNanoseconds),
          nanosToMillis(capture.refinementAndSelectionElapsedNanoseconds),
          nanosToMillis(capture.finalAuditElapsedNanoseconds),
          0.0
        )
    CandidateRun(
      RunStatus.Success,
      accepted = true,
      failure = None,
      movingToFixed = Some(matrix),
      transformSha256 = Some(EvidenceHash.matrix(matrix)),
      candidateMetrics = Map(
        "projected_patch_objective" -> Some(objective),
        "audit_overlap_fraction" -> Some(diagnostics.overlapFraction),
        "pyramid_levels" -> Some(diagnostics.levels.size.toDouble),
        "capture_candidates" -> diagnostics.capture.map(_.candidates.size.toDouble),
        "identity_adequate" -> diagnostics.capture.map(value =>
          if value.identityAdequate then 1.0 else 0.0
        ),
        "expanded_capture_executed" -> diagnostics.capture.map(value =>
          if value.expandedCaptureExecuted then 1.0 else 0.0
        )
      ),
      work = workCounts(work),
      timingMs = stageTimes
    )

  private def flashalignFailure(
      error: FlashalignError,
      preparationMillis: Double,
      optimizeMillis: Double
  ): CandidateRun =
    val message = error.message
    val status =
      if message.contains("InsufficientOverlap") || message.contains("overlap") then RunStatus.InsufficientOverlap
      else if message.contains("RankDeficient") || message.contains("information") then RunStatus.InsufficientInformation
      else if message.contains("LinearizationLimit") then RunStatus.IterationLimit
      else if message.contains("TrialAttemptLimit") || message.contains("DampingLimited") then RunStatus.Stalled
      else if message.contains("geometry") || message.contains("Geometry") then RunStatus.InvalidGeometry
      else RunStatus.NumericalFailure
    val diagnostics = error.failureDiagnostics
    val capture = diagnostics.flatMap(_.capture)
    val stageTimes = capture match
      case None =>
        AlgorithmStageTimes(
          preparationMillis,
          0.0,
          optimizeMillis,
          0.0,
          0.0
        )
      case Some(value) =>
        AlgorithmStageTimes(
          preparationMillis,
          nanosToMillis(value.captureElapsedNanoseconds),
          nanosToMillis(value.refinementAndSelectionElapsedNanoseconds),
          nanosToMillis(value.finalAuditElapsedNanoseconds),
          0.0
        )
    val checkpoint = diagnostics.flatMap(_.lastCheckpoint).map { value =>
      FailureCheckpoint(
        value.movingToFixedRowMajor,
        EvidenceHash.matrix(value.movingToFixedRowMajor),
        value.lastValidMovingToFixedRowMajor,
        EvidenceHash.matrix(value.lastValidMovingToFixedRowMajor),
        value.selectionObjective,
        value.acceptedSteps,
        value.selectionObjectiveId,
        value.initialOptimizationObjective,
        value.lastOptimizationObjective
      )
    }
    failure(status, "flashalign", message).copy(
      candidateMetrics = Map(
        "pyramid_levels" -> diagnostics.map(_.levels.size.toDouble),
        "capture_candidates" -> capture.map(_.candidates.size.toDouble),
        "identity_adequate" -> capture.map(value =>
          if value.identityAdequate then 1.0 else 0.0
        ),
        "expanded_capture_executed" -> capture.map(value =>
          if value.expandedCaptureExecuted then 1.0 else 0.0
        )
      ),
      work = diagnostics.map(value => workCounts(value.work)).getOrElse(
        WorkCounts.Zero
      ),
      timingMs = stageTimes,
      lastCheckpoint = checkpoint
    )

  private def failure(status: RunStatus, kind: String, message: String): CandidateRun =
    CandidateRun(status, accepted = false, Some(FailureDetail(kind, message)), None, None, Map.empty, WorkCounts.Zero, AlgorithmStageTimes.Zero)

  private def elapsed(started: Long): Double =
    (System.nanoTime() - started).toDouble / 1000000.0

  private def nanosToMillis(value: Long): Double = value.toDouble / 1000000.0

  private def workCounts(work: FlashalignWorkCounts): WorkCounts =
    WorkCounts(
      work.uniqueInterpolations,
      work.gradientEvaluations,
      work.dataLinearizations.toLong,
      work.trialEvaluations.toLong,
      work.rejectedSteps.toLong,
      work.earlyRejectedTrials.toLong,
      work.curvatureProducts.toLong
    )

private final class AfniAdapter(model: String) extends PairMethodAdapter[NiftiPair]:
  private val configuration =
    s"3dAllineate|${LinearAccuracyCourt.AfniImage}|$model|lpa+ZZ|onepass|world-identity"

  val identity: MethodIdentity = MethodIdentity(
    s"3dAllineate-$model",
    model,
    LinearAccuracyCourt.AfniImage,
    EvidenceHash.utf8(configuration),
    Some(s"docker run --rm --platform linux/amd64 -v COURT:/court:ro -v OUTPUT:/out ${LinearAccuracyCourt.AfniImage} 3dAllineate -base FIXED -source MOVING -prefix NULL -1Dmatrix_save /out/result.aff12.1D -cost lpa+ZZ -warp ${warp(model)} -onepass"),
    Some(LinearAccuracyCourt.AfniVersion)
  )

  def run(pair: PairCase, inputs: NiftiPair): Either[EvidenceError, CandidateRun] =
    val _ = inputs
    val output = Files.createTempDirectory("flashalign-afni-")
    val matrixPath = output.resolve("result.aff12.1D")
    val logPath = output.resolve("3dAllineate.log")
    val court = pair.moving.path.toAbsolutePath.getParent
    val command = Vector(
      "docker", "run", "--rm", "--platform", "linux/amd64",
      "-v", s"$court:/court:ro",
      "-v", s"${output.toAbsolutePath}:/out",
      LinearAccuracyCourt.AfniImage,
      "3dAllineate",
      "-base", s"/court/${pair.fixed.path.getFileName}",
      "-source", s"/court/${pair.moving.path.getFileName}",
      "-prefix", "NULL",
      "-1Dmatrix_save", "/out/result.aff12.1D",
      "-cost", "lpa+ZZ",
      "-warp", warp(model),
      "-onepass"
    )
    val started = System.nanoTime()
    val candidate =
      try
        val builder = new ProcessBuilder(command*)
        builder.redirectErrorStream(true)
        builder.redirectOutput(logPath.toFile)
        val process = builder.start()
        val finished = process.waitFor(180L, TimeUnit.SECONDS)
        val milliseconds = (System.nanoTime() - started).toDouble / 1000000.0
        if !finished then
          process.destroyForcibly()
          failure(RunStatus.SearchExhausted, "timeout", "3dAllineate exceeded 180 seconds", milliseconds)
        else if process.exitValue() != 0 then
          failure(RunStatus.NumericalFailure, "3dAllineate-exit", tail(logPath), milliseconds)
        else
          AfniMatrix.readMovingToFixedRas(matrixPath) match
            case Left(error) => failure(RunStatus.NumericalFailure, "3dAllineate-matrix", error.message, milliseconds)
            case Right(matrix) =>
              CandidateRun(
                RunStatus.Success,
                accepted = true,
                failure = None,
                movingToFixed = Some(matrix),
                transformSha256 = Some(EvidenceHash.matrix(matrix)),
                candidateMetrics = Map.empty,
                work = WorkCounts.Zero,
                timingMs = AlgorithmStageTimes(0.0, 0.0, milliseconds, 0.0, 0.0)
              )
      catch
        case NonFatal(error) =>
          val milliseconds = (System.nanoTime() - started).toDouble / 1000000.0
          failure(RunStatus.Unavailable, "3dAllineate-process", Option(error.getMessage).getOrElse(error.getClass.getName), milliseconds)
    deleteTree(output)
    Right(candidate)

  private def failure(status: RunStatus, kind: String, message: String, milliseconds: Double): CandidateRun =
    CandidateRun(status, accepted = false, Some(FailureDetail(kind, message)), None, None, Map.empty, WorkCounts.Zero, AlgorithmStageTimes(0.0, 0.0, milliseconds, 0.0, 0.0))

  private def tail(path: Path): String =
    if !Files.isRegularFile(path) then "no comparator log"
    else Files.readAllLines(path, StandardCharsets.UTF_8).asScala.takeRight(8).mkString(" | ").take(2000)

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val stream = Files.walk(root)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally stream.close()

  private def warp(value: String): String =
    if value == "rigid" then "shift_rotate" else "affine_general"

private[flashalign] object AfniMatrix:
  def readMovingToFixedRas(path: Path): Either[EvidenceError, Vector[Double]] =
    if !Files.isRegularFile(path) then
      Left(EvidenceError.InputFailure("3dAllineate-matrix", s"missing $path"))
    else
      val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala
      lines.find(line => line.trim.nonEmpty && !line.trim.startsWith("#")) match
        case None => Left(EvidenceError.InvalidCandidate("3dAllineate matrix has no data row"))
        case Some(line) =>
          try
            val values = line.trim.split("\\s+").toVector.map(_.toDouble)
            fromBaseToSourceRai(values)
          catch
            case NonFatal(error) => Left(EvidenceError.InvalidCandidate(error.getMessage))

  def fromBaseToSourceRai(values: Vector[Double]): Either[EvidenceError, Vector[Double]] =
    if values.size != 12 then
      Left(EvidenceError.InvalidMatrixLength("3dAllineate base-to-source RAI", values.size))
    else
      val homogeneous = Vector(
        values(0), values(1), values(2), values(3),
        values(4), values(5), values(6), values(7),
        values(8), values(9), values(10), values(11),
        0.0, 0.0, 0.0, 1.0
      )
      Affine
        .fromRowMajor[D3](homogeneous)
        .left
        .map(error => EvidenceError.InvalidCandidate(error.message))
        .map(_.inverse.rowMajor)
        .map { inverse =>
          val signs = Vector(-1.0, -1.0, 1.0, 1.0)
          Vector.tabulate(16) { flat =>
            val row = flat / 4
            val column = flat % 4
            signs(row) * inverse(flat) * signs(column)
          }
        }
