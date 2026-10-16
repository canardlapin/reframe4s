package reframe4s.benchmark.flashalign

import image4s.BoundaryPolicy
import image4s.ContinuousImage
import image4s.SampleSpace
import image4s.geometry.D3
import image4s.geometry.Frame
import ravel.Rank
import reframe4s.flashalign.AffineFlashalignResult
import reframe4s.flashalign.Flashalign
import reframe4s.flashalign.FlashalignConfig
import reframe4s.flashalign.FlashalignError
import reframe4s.flashalign.FlashalignInitializationPolicy
import reframe4s.flashalign.FlashalignOutput
import reframe4s.flashalign.FlashalignPreset
import reframe4s.flashalign.FlashalignWorkCounts
import reframe4s.flashalign.RigidFlashalignResult
import reframe4s.resample.Interpolation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Executes the unopened automatic linear confirmation court.
  *
  * The fixture generator, truth-identifiability oracle, thresholds and this
  * runner are sealed before this entry point is executed against Flashalign.
  */
object LinearAutomaticConfirmationCourt:
  val FixtureDescriptor = Paths.get(
    "benchmarks/flashalign/fixtures/linear-automatic-confirmation-v1.json"
  )
  val CaseTable = Paths.get(
    "benchmarks/flashalign/fixtures/linear-automatic-confirmation-v1.cases.tsv"
  )
  val FixtureDescriptorSha256 = Sha256.unsafe(
    "0256c23cc60d6b9d93dc87cc6b57c271ab6eb30fdbb4bcd105de4b6ec20961d9"
  )
  val CaseTableSha256 = Sha256.unsafe(
    "c75de6659264c1580008d6312895ce4f21c52bcd461ced6b84c9a0afb06669fd"
  )
  private val DefaultCourtId = "linear-automatic-confirmation-v1"
  private val DefaultSeal = CourtSeal(
    FixtureDescriptor,
    FixtureDescriptorSha256,
    CaseTable,
    CaseTableSha256,
    DefaultCourtId
  )

  def main(arguments: Array[String]): Unit =
    val result = arguments.toVector match
      case Vector(raw, candidateRevision) =>
        run(Paths.get(raw), candidateRevision)
      case Vector(
            raw,
            candidateRevision,
            descriptor,
            descriptorSha256,
            caseTable,
            caseTableSha256,
            courtId
          ) =>
        for
          descriptorHash <- Sha256.parse(descriptorSha256)
          tableHash <- Sha256.parse(caseTableSha256)
          _ <- runSealed(
            Paths.get(raw),
            candidateRevision,
            CourtSeal(
              Paths.get(descriptor),
              descriptorHash,
              Paths.get(caseTable),
              tableHash,
              courtId
            )
          )
        yield ()
      case _ =>
        Left(
          EvidenceError.InvalidCandidate(
            "usage: LinearAutomaticConfirmationCourt <raw-jsonl> <candidate-revision> [<descriptor> <descriptor-sha256> <case-table> <case-table-sha256> <court-id>]"
          )
        )
    result.fold(error => throw new IllegalStateException(error.message), _ => ())

  def run(
      rawOutput: Path,
      candidateRevision: String
  ): Either[EvidenceError, Unit] =
    runSealed(rawOutput, candidateRevision, DefaultSeal)

  private def runSealed(
      rawOutput: Path,
      candidateRevision: String,
      seal: CourtSeal
  ): Either[EvidenceError, Unit] =
    for
      cases <- sealedCases(seal)
      environment = currentEnvironment
      rigid = new AutomaticConfirmationAdapter("rigid", candidateRevision, seal.courtId)
      affine = new AutomaticConfirmationAdapter("affine", candidateRevision, seal.courtId)
      rows <- cases.zipWithIndex.foldLeft[
        Either[EvidenceError, Vector[RawPairResult]]
      ](Right(Vector.empty)) { case (accumulated, (courtCase, index)) =>
        accumulated.flatMap { rows =>
          val adapter = if courtCase.model == "rigid" then rigid else affine
          PairBenchmarkRunner
            .run(
              s"${seal.courtId}-${courtCase.pair.id}-flashalign",
              courtCase.pair,
              Image4sNiftiPairLoader,
              adapter,
              environment
            )
            .map { row =>
              println(
                s"Flashalign confirmation case ${index + 1}/${cases.size}: ${courtCase.pair.id} -> ${row.candidate.status}"
              )
              rows :+ row
            }
        }
      }
      _ <- writeRows(rawOutput, rows)
    yield ()

  private[flashalign] def sealedCaseCount: Either[EvidenceError, Int] =
    sealedCases(DefaultSeal).map(_.size)

  private[flashalign] def sealedCohortCounts
      : Either[EvidenceError, Map[(PairCohort, String), Int]] =
    sealedCases(DefaultSeal).map(
      _.groupMapReduce(value => value.pair.cohort -> value.model)(_ => 1)(_ + _)
    )

  private def sealedCases(
      seal: CourtSeal
  ): Either[EvidenceError, Vector[CourtCase]] =
    for
      _ <- verifySeal(seal.fixtureDescriptor, seal.fixtureDescriptorSha256)
      _ <- verifySeal(seal.caseTable, seal.caseTableSha256)
      cases <- readCases(seal.caseTable, seal.courtId)
      _ <-
        if cases.size == 48 then Right(())
        else Left(
          EvidenceError.InvalidCandidate(
            s"sealed automatic confirmation court requires 48 cases, got ${cases.size}"
          )
        )
    yield cases

  private def verifySeal(
      path: Path,
      expected: Sha256
  ): Either[EvidenceError, Unit] =
    if !Files.isRegularFile(path) then
      Left(EvidenceError.InputFailure("seal", s"missing $path"))
    else
      val actual = EvidenceHash.file(path)
      if actual == expected then Right(())
      else Left(EvidenceError.InputHashMismatch(path.toString, expected, actual))

  private def readCases(
      path: Path,
      courtId: String
  ): Either[EvidenceError, Vector[CourtCase]] =
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector
    lines.drop(1).filter(_.trim.nonEmpty).foldLeft[
      Either[EvidenceError, Vector[CourtCase]]
    ](Right(Vector.empty)) { (accumulated, line) =>
      accumulated.flatMap(values => parseCase(line, courtId).map(values :+ _))
    }

  private def parseCase(
      line: String,
      courtId: String
  ): Either[EvidenceError, CourtCase] =
    val fields = line.split("\\t", -1).toVector
    if fields.size != 10 then
      Left(
        EvidenceError.InvalidCandidate(
          s"expected 10 automatic confirmation fields, got ${fields.size}"
        )
      )
    else
      for
        cohort <- parseCohort(fields(2))
        model <- parseModel(fields(3))
        movingHash <- Sha256.parse(fields(6))
        fixedHash <- Sha256.parse(fields(7))
        truth <- parseMatrix(fields(8))
        _ <- parseTruthKind(cohort, fields(9))
        moving = Paths.get(fields(4))
        fixed = Paths.get(fields(5))
        _ <- requireFile(moving)
        _ <- requireFile(fixed)
        identity = identityMatrix
        pair = PairCase(
          id = fields(0),
          subjectId = fields(1),
          cohort = cohort,
          moving = LicensedArtifact(
            moving,
            movingHash,
            s"flashalign-$courtId sealed image-backed fixture",
            "Apache-2.0"
          ),
          fixed = LicensedArtifact(
            fixed,
            fixedHash,
            s"flashalign-$courtId sealed image-backed fixture",
            "Apache-2.0"
          ),
          initialization = InitializationIdentity(
            "automatic-coherent-header-identity-v1",
            identity,
            EvidenceHash.matrix(identity)
          ),
          immutableSampleIdsSha256 = EvidenceHash.utf8(line),
          landmarks = landmarks(cohort, truth)
        )
      yield CourtCase(model, pair)

  private def parseCohort(value: String): Either[EvidenceError, PairCohort] =
    value match
      case "exact-core"         => Right(PairCohort.OrdinarySameSubject)
      case "capture-range"      => Right(PairCohort.LargeInitializationError)
      case "partial-slab"       => Right(PairCohort.PartialSlab)
      case "nonlinear-contrast" => Right(PairCohort.DropoutOrLowSignal)
      case other =>
        Left(EvidenceError.InvalidCandidate(s"unknown confirmation cohort $other"))

  private def parseModel(value: String): Either[EvidenceError, String] =
    if value == "rigid" || value == "affine" then Right(value)
    else Left(EvidenceError.InvalidCandidate(s"unknown model $value"))

  private def parseTruthKind(
      cohort: PairCohort,
      value: String
  ): Either[EvidenceError, Unit] =
    val expected =
      if cohort == PairCohort.DropoutOrLowSignal then
        "approximation-distribution"
      else "interpolant-exact"
    if value == expected then Right(())
    else
      Left(
        EvidenceError.InvalidCandidate(
          s"cohort ${cohort.id} requires truth kind $expected, got $value"
        )
      )

  private def parseMatrix(value: String): Either[EvidenceError, Vector[Double]] =
    try
      val parsed = value.split(",", -1).toVector.map(_.toDouble)
      if parsed.size != 16 || parsed.exists(number => !number.isFinite) then
        Left(EvidenceError.InvalidMatrixLength("truth", parsed.size))
      else Right(parsed)
    catch
      case NonFatal(error) =>
        Left(EvidenceError.InvalidCandidate(error.getMessage))

  private def requireFile(path: Path): Either[EvidenceError, Unit] =
    if Files.isRegularFile(path) then Right(())
    else Left(EvidenceError.InputFailure("fixture", s"missing $path"))

  private def landmarks(
      cohort: PairCohort,
      truth: Vector[Double]
  ): Vector[LandmarkTruth] =
    val zs =
      if cohort == PairCohort.PartialSlab then
        Vector(-10.0, -5.0, 0.0, 5.0, 10.0)
      else Vector(-14.0, -7.0, 0.0, 7.0, 14.0)
    val xy = Vector(
      (-14.0, -9.0),
      (12.0, -8.0),
      (-9.0, 11.0),
      (11.0, 10.0),
      (0.0, 0.0)
    )
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

  private def applyAffine(
      matrix: Vector[Double],
      point: Vector[Double]
  ): Vector[Double] =
    Vector.tabulate(3)(row =>
      matrix(row * 4) * point(0) +
        matrix(row * 4 + 1) * point(1) +
        matrix(row * 4 + 2) * point(2) +
        matrix(row * 4 + 3)
    )

  private def writeRows(
      path: Path,
      rows: Vector[RawPairResult]
  ): Either[EvidenceError, Unit] =
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
      case NonFatal(error) =>
        Left(EvidenceError.AdapterFailure(error.getMessage))

  private def currentEnvironment: EnvironmentRecord =
    EnvironmentRecord(
      s"${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
      sys.env.getOrElse(
        "FLASHALIGN_BENCHMARK_CPU",
        "Apple Silicon host; single-thread Flashalign"
      ),
      s"${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
      1
    )

  private val identityMatrix = Vector(
    1.0, 0.0, 0.0, 0.0,
    0.0, 1.0, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
  )

  private final case class CourtCase(model: String, pair: PairCase)

  private final case class CourtSeal(
      fixtureDescriptor: Path,
      fixtureDescriptorSha256: Sha256,
      caseTable: Path,
      caseTableSha256: Sha256,
      courtId: String
  )

private[flashalign] final case class OutputSummary(
    voxels: Long,
    finiteValueSum: Double,
    nonfiniteVoxels: Long,
    meanValidity: Double,
    nonfiniteValidityWeights: Long
)

private[flashalign] object OutputEvidence:
  def summarize(
      values: Iterator[Double],
      validityWeights: Iterator[Double]
  ): OutputSummary =
    var valueSum = 0.0
    var valueCompensation = 0.0
    var voxelCount = 0L
    var nonfiniteVoxelCount = 0L
    while values.hasNext do
      val value = values.next()
      if value.isFinite then
        val corrected = value - valueCompensation
        val updated = valueSum + corrected
        valueCompensation = (updated - valueSum) - corrected
        valueSum = updated
      else nonfiniteVoxelCount += 1L
      voxelCount += 1L
    var validitySum = 0.0
    var finiteValidityCount = 0L
    var nonfiniteValidityCount = 0L
    while validityWeights.hasNext do
      val weight = validityWeights.next()
      if weight.isFinite then
        validitySum += weight
        finiteValidityCount += 1L
      else nonfiniteValidityCount += 1L
    val meanValidity =
      if finiteValidityCount == 0L then 0.0
      else validitySum / finiteValidityCount.toDouble
    OutputSummary(
      voxelCount,
      valueSum,
      nonfiniteVoxelCount,
      meanValidity,
      nonfiniteValidityCount
    )

private final class AutomaticConfirmationAdapter(
    model: String,
    candidateRevision: String,
    courtId: String
) extends PairMethodAdapter[NiftiPair]:
  val identity: MethodIdentity =
    val configuration =
      s"flashalign-$courtId|$model|within-modality|automatic-structural-capture|single-thread"
    MethodIdentity(
      s"flashalign-$model",
      model,
      candidateRevision,
      EvidenceHash.utf8(configuration),
      None,
      None
    )

  def run(
      pair: PairCase,
      inputs: NiftiPair
  ): Either[EvidenceError, CandidateRun] =
    inputs.moving.fold(
      _ => Right(
        failure(
          RunStatus.InvalidGeometry,
          "moving-rank",
          "expected D3 moving image"
        )
      ),
      movingD3 =>
        inputs.fixed.fold(
          _ => Right(
            failure(
              RunStatus.InvalidGeometry,
              "fixed-rank",
              "expected D3 fixed image"
            )
          ),
          fixedD3 =>
            for
              moving <- movingD3.value
                .requireDataRank[3]
                .left
                .map(error => EvidenceError.InputFailure("moving", error.message))
              fixed <- fixedD3.value
                .requireDataRank[3]
                .left
                .map(error => EvidenceError.InputFailure("fixed", error.message))
              result <- runTyped(moving, fixed)
            yield result
        )
    )

  private def runTyped[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      MovingSpace <: SampleSpace[Moving, D3],
      FixedSpace <: SampleSpace[Fixed, D3]
  ](
      moving: ContinuousImage[MovingSpace, Double, Rank[3]],
      fixed: ContinuousImage[FixedSpace, Double, Rank[3]]
  ): Either[EvidenceError, CandidateRun] =
    val config = FlashalignConfig.forPreset(
      FlashalignPreset.WithinModality,
      FlashalignInitializationPolicy.CoherentHeaderWorldIdentityThenCapture
    )
    val preparationStarted = System.nanoTime()
    val compiled =
      if model == "rigid" then Flashalign.rigid(moving, fixed, config)
      else Flashalign.affine(moving, fixed, config)
    val preparationMillis = elapsed(preparationStarted)
    compiled match
      case Left(error) =>
        Right(flashalignFailure(error, preparationMillis, 0.0))
      case Right(plan) =>
        val executionStarted = System.nanoTime()
        val outcome: Either[
          FlashalignError,
          Either[
            RigidFlashalignResult[Moving, Fixed],
            AffineFlashalignResult[Moving, Fixed]
          ]
        ] =
          plan match
            case rigidPlan: reframe4s.flashalign.RigidFlashalignPlan[Moving, Fixed] =>
              rigidPlan.run(rigidPlan.newWorkspace()).map(Left.apply)
            case affinePlan: reframe4s.flashalign.AffineFlashalignPlan[Moving, Fixed] =>
              affinePlan.run(affinePlan.newWorkspace()).map(Right.apply)
        val executionMillis = elapsed(executionStarted)
        outcome match
          case Left(error) =>
            Right(
              flashalignFailure(error, preparationMillis, executionMillis)
            )
          case Right(Left(result)) =>
            completeRigid(
              moving,
              fixed,
              result,
              preparationMillis,
              executionMillis
            )
          case Right(Right(result)) =>
            completeAffine(
              moving,
              fixed,
              result,
              preparationMillis,
              executionMillis
            )

  private def completeRigid[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      MovingSpace <: SampleSpace[Moving, D3],
      FixedSpace <: SampleSpace[Fixed, D3]
  ](
      originalMoving: ContinuousImage[MovingSpace, Double, Rank[3]],
      fixed: ContinuousImage[FixedSpace, Double, Rank[3]],
      result: RigidFlashalignResult[Moving, Fixed],
      preparationMillis: Double,
      executionMillis: Double
  ): Either[EvidenceError, CandidateRun] =
    val outputStarted = System.nanoTime()
    val output =
      FlashalignOutput
        .rigidPlan(
          originalMoving,
          fixed.grid,
          result,
          Interpolation.Linear,
          BoundaryPolicy.Constant(0.0)
        )
        .flatMap(plan => plan.run(plan.newWorkspace()))
    output
      .left
      .map(error => EvidenceError.AdapterFailure(s"output: ${error.message}"))
      .map { resampled =>
        val summary = OutputEvidence.summarize(
          resampled.image.data.iterator,
          resampled.validity.weights.data.iterator
        )
        val outputMillis = elapsed(outputStarted)
        success(
          result.movingToFixed.operator.rowMajor,
          result.report.finalObjective,
          result.diagnostics,
          preparationMillis,
          executionMillis,
          outputMillis,
          summary
        )
      }

  private def completeAffine[
      Moving <: Frame[D3],
      Fixed <: Frame[D3],
      MovingSpace <: SampleSpace[Moving, D3],
      FixedSpace <: SampleSpace[Fixed, D3]
  ](
      originalMoving: ContinuousImage[MovingSpace, Double, Rank[3]],
      fixed: ContinuousImage[FixedSpace, Double, Rank[3]],
      result: AffineFlashalignResult[Moving, Fixed],
      preparationMillis: Double,
      executionMillis: Double
  ): Either[EvidenceError, CandidateRun] =
    val outputStarted = System.nanoTime()
    val output =
      FlashalignOutput
        .affinePlan(
          originalMoving,
          fixed.grid,
          result,
          Interpolation.Linear,
          BoundaryPolicy.Constant(0.0)
        )
        .flatMap(plan => plan.run(plan.newWorkspace()))
    output
      .left
      .map(error => EvidenceError.AdapterFailure(s"output: ${error.message}"))
      .map { resampled =>
        val summary = OutputEvidence.summarize(
          resampled.image.data.iterator,
          resampled.validity.weights.data.iterator
        )
        val outputMillis = elapsed(outputStarted)
        success(
          result.movingToFixed.operator.rowMajor,
          result.report.finalObjective,
          result.diagnostics,
          preparationMillis,
          executionMillis,
          outputMillis,
          summary
        )
      }

  private def success(
      matrix: Vector[Double],
      objective: Double,
      diagnostics: reframe4s.flashalign.FlashalignDiagnostics,
      preparationMillis: Double,
      totalExecutionMillis: Double,
      outputMillis: Double,
      output: OutputSummary
  ): CandidateRun =
    val capture = diagnostics.capture
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
        "capture_candidates" -> capture.map(_.candidates.size.toDouble),
        "identity_adequate" -> capture.map(value =>
          if value.identityAdequate then 1.0 else 0.0
        ),
        "expanded_capture_executed" -> capture.map(value =>
          if value.expandedCaptureExecuted then 1.0 else 0.0
        ),
        "output_voxels" -> Some(output.voxels.toDouble),
        "output_finite_value_sum" -> Some(output.finiteValueSum),
        "output_nonfinite_voxels" -> Some(output.nonfiniteVoxels.toDouble),
        "output_mean_validity" -> Some(output.meanValidity),
        "output_nonfinite_validity_weights" -> Some(
          output.nonfiniteValidityWeights.toDouble
        )
      ),
      work = workCounts(diagnostics.work),
      timingMs = stageTimes(
        capture,
        preparationMillis,
        totalExecutionMillis,
        outputMillis
      )
    )

  private def flashalignFailure(
      error: FlashalignError,
      preparationMillis: Double,
      executionMillis: Double
  ): CandidateRun =
    val message = error.message
    val status =
      if message.contains("InsufficientOverlap") || message.contains("overlap")
      then RunStatus.InsufficientOverlap
      else if message.contains("RankDeficient") || message.contains("information")
      then RunStatus.InsufficientInformation
      else if message.contains("LinearizationLimit")
      then RunStatus.IterationLimit
      else if message.contains("TrialAttemptLimit") || message.contains("DampingLimited")
      then RunStatus.Stalled
      else if message.contains("geometry") || message.contains("Geometry")
      then RunStatus.InvalidGeometry
      else RunStatus.NumericalFailure
    val diagnostics = error.failureDiagnostics
    val capture = diagnostics.flatMap(_.capture)
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
      work = diagnostics
        .map(value => workCounts(value.work))
        .getOrElse(WorkCounts.Zero),
      timingMs = stageTimes(capture, preparationMillis, executionMillis),
      lastCheckpoint = checkpoint
    )

  private def stageTimes(
      capture: Option[reframe4s.flashalign.FlashalignCaptureDiagnostics],
      preparationMillis: Double,
      executionMillis: Double,
      outputMillis: Double = 0.0
  ): AlgorithmStageTimes =
    capture match
      case None =>
        AlgorithmStageTimes(
          preparationMillis,
          0.0,
          executionMillis,
          0.0,
          outputMillis
        )
      case Some(value) =>
        AlgorithmStageTimes(
          preparationMillis,
          nanosToMillis(value.captureElapsedNanoseconds),
          nanosToMillis(value.refinementAndSelectionElapsedNanoseconds),
          nanosToMillis(value.finalAuditElapsedNanoseconds),
          outputMillis
        )

  private def failure(
      status: RunStatus,
      kind: String,
      message: String
  ): CandidateRun =
    CandidateRun(
      status,
      accepted = false,
      Some(FailureDetail(kind, message)),
      None,
      None,
      Map.empty,
      WorkCounts.Zero,
      AlgorithmStageTimes.Zero
    )

  private def elapsed(started: Long): Double =
    (System.nanoTime() - started).toDouble / 1000000.0

  private def nanosToMillis(value: Long): Double =
    value.toDouble / 1000000.0

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
