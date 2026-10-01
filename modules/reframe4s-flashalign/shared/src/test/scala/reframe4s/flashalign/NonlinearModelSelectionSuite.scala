package reframe4s.flashalign

final class NonlinearModelSelectionSuite extends munit.FunSuite:
  test("rank-aware pose conditioning reports null directions and data-only field support"):
    val diagonal = Vector(4.0, 3.0, 2.0, 1.0, 0.0, 0.0, 2.0, 0.5, 0.0)
    val analysis = information(
      NonlinearInformationAnalyzer.analyze(
        diagonalOperator(diagonal),
        poseDimension = 6,
        priorPrecisionDiagonal = Vector(1.0, 1.0, 1.0)
      )
    )

    assertEquals(analysis.poseDataRank, 4)
    assertEquals(analysis.lostPoseDirections, 2)
    assertEquals(analysis.conditionalDataRank, 2)
    assertEquals(analysis.priorRelativeEigenvalues.map(round), Vector(0.0, 0.5, 2.0))
    assertEqualsDouble(analysis.effectiveDimension, 1.0, 1e-10)
    assert(analysis.dataOnlyPoseConditioning)
    assert(analysis.priorAndDampingExcludedFromDataRank)
    assertEquals(analysis.operatorProducts, 9L)

  test("prior-relative sensitivity changes without manufacturing data rank"):
    val diagonal = Vector(4.0, 3.0, 2.0, 1.0, 0.0, 0.0, 2.0, 0.5, 0.0)
    val weakPrior = information(
      NonlinearInformationAnalyzer.analyze(
        diagonalOperator(diagonal),
        6,
        Vector(1.0, 1.0, 1.0)
      )
    )
    val strongPrior = information(
      NonlinearInformationAnalyzer.analyze(
        diagonalOperator(diagonal),
        6,
        Vector(100.0, 100.0, 100.0)
      )
    )

    assertEquals(strongPrior.conditionalDataRank, weakPrior.conditionalDataRank)
    assert(strongPrior.effectiveDimension < weakPrior.effectiveDimension)
    assert(strongPrior.maximumPriorRelativeInformation < weakPrior.maximumPriorRelativeInformation)

  test("data-only Schur complement removes the best identifiable pose motion"):
    val dimension = 8
    val matrix = Array.fill(dimension * dimension)(0.0)
    Vector(2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 3.0)
      .zipWithIndex
      .foreach { case (value, index) =>
        matrix(index * dimension + index) = value
      }
    matrix(0 * dimension + 6) = 1.0
    matrix(6 * dimension + 0) = 1.0
    val analysis = information(
      NonlinearInformationAnalyzer.analyze(
        denseOperator(matrix, dimension),
        poseDimension = 6,
        priorPrecisionDiagonal = Vector(1.0, 1.0)
      )
    )

    assertEquals(analysis.poseDataRank, 6)
    assertEquals(analysis.conditionalDataRank, 2)
    assertEquals(
      analysis.priorRelativeEigenvalues.map(round),
      Vector(1.5, 3.0)
    )

  test("selector retains the smallest fully supported model"):
    val info = supportedInformation
    val larger = candidate("field-96", 2, info)
    val smaller = candidate("field-48", 1, info)
    val decision = NonlinearModelSelector.select(
      Vector(larger, smaller),
      config,
      roles
    )

    assertEquals(decision.status, NonlinearSelectionStatus.AdmittedNonlinear)
    assertEquals(decision.selectedNonlinearModelId, Some("field-48"))
    assert(decision.successfulNonlinearSelection)
    assertEquals(decision.simplerFallbackModelId, "validated-rigid")

  test("prior-controlled candidates retain an explicit simpler fallback"):
    val weak = supportedInformation.copy(
      effectiveDimension = 0.05,
      maximumPriorRelativeInformation = 0.02
    )
    val decision = NonlinearModelSelector.select(
      Vector(candidate("field-48", 1, weak)),
      config,
      roles
    )

    assertEquals(
      decision.status,
      NonlinearSelectionStatus.RetainedSimplerFallback
    )
    assertEquals(decision.selectedNonlinearModelId, None)
    assertEquals(
      decision.assessments.head.support,
      NonlinearCandidateSupport.PriorControlled
    )
    assert(!decision.successfulNonlinearSelection)
    assert(decision.detail.contains("validated-rigid"))

  test("selection and audit identities must remain distinct"):
    val invalidRoles = roles.copy(auditId = roles.selectionId)
    val decision = NonlinearModelSelector.select(
      Vector(candidate("field-48", 1, supportedInformation)),
      config,
      invalidRoles
    )
    assertEquals(
      decision.status,
      NonlinearSelectionStatus.InvalidRoleEvidence
    )
    assertEquals(decision.selectedNonlinearModelId, None)

  private val supportedInformation = NonlinearInformationDiagnostics(
    poseDimension = 6,
    fieldDimension = 3,
    poseDataRank = 6,
    lostPoseDirections = 0,
    conditionalDataRank = 3,
    priorRelativeEigenvalues = Vector(0.5, 1.0, 2.0),
    effectiveDimension = 1.5,
    maximumPriorRelativeInformation = 2.0,
    dataOnlyPoseConditioning = true,
    priorAndDampingExcludedFromDataRank = true,
    operatorProducts = 9L,
    checkpointDenseBytes = 1000L,
    rankTolerance = 1e-9
  )

  private val config = NonlinearSelectionConfig(
    minimumConditionalRank = 2,
    minimumEffectiveDimension = 0.5,
    minimumMaximumInformation = 0.25,
    minimumPredictedResidualReduction = 0.1,
    minimumSelectionImprovement = 0.05,
    maximumRegionalInstabilityMillimetres = 1.0,
    minimumRegionalSupportFraction = 0.75,
    allowPartialSupport = false,
    simplerFallbackModelId = "validated-rigid"
  )

  private val roles = NonlinearRoleEvidence(
    optimizationId = "train-opt-v1",
    selectionId = "train-selection-v1",
    auditId = "sealed-audit-v1",
    subjectSplitId = "subject-disjoint-training-v1",
    presetManifestSha256 = "a" * 64,
    seed = 20260912L
  )

  private def candidate(
      id: String,
      complexity: Int,
      info: NonlinearInformationDiagnostics
  ): NonlinearCandidateEvidence =
    NonlinearCandidateEvidence(
      id,
      complexity,
      info,
      predictedResidualReduction = 0.2,
      selectionImprovement = 0.1,
      regionalInstabilityMillimetres = 0.4,
      regionalSupportFraction = 1.0,
      optimizerConverged = true,
      geometryCertified = true
    )

  private def diagonalOperator(values: Vector[Double]): ArraySymmetricOperator =
    new ArraySymmetricOperator:
      val dimension: Int = values.size
      def apply(
          input: Array[Double],
          output: Array[Double]
      ): Either[String, Unit] =
        if input.length != dimension || output.length != dimension then
          Left("dimension mismatch")
        else
          var index = 0
          while index < dimension do
            output(index) = values(index) * input(index)
            index += 1
          Right(())

  private def denseOperator(
      rowMajor: Array[Double],
      size: Int
  ): ArraySymmetricOperator =
    new ArraySymmetricOperator:
      val dimension: Int = size
      def apply(
          input: Array[Double],
          output: Array[Double]
      ): Either[String, Unit] =
        var row = 0
        while row < dimension do
          var value = 0.0
          var column = 0
          while column < dimension do
            value += rowMajor(row * dimension + column) * input(column)
            column += 1
          output(row) = value
          row += 1
        Right(())

  private def information(
      result: Either[
        NonlinearInformationError,
        NonlinearInformationDiagnostics
      ]
  ): NonlinearInformationDiagnostics =
    result.fold(error => fail(error.message), identity)

  private def round(value: Double): Double =
    math.rint(value * 1e10) / 1e10
