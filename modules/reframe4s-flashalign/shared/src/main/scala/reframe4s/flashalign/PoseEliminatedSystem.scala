package reframe4s.flashalign

/** A frozen quadratic model split into global pose and gauge-reduced field
  * coordinates. The data/prior Hessian and physical displacement metric remain
  * separate so damping never enters objective prediction or data information.
  */
private[flashalign] final class PoseEliminatedLinearization private (
    val undamped: ArraySymmetricOperator,
    val physicalMetric: ArraySymmetricOperator,
    val rightHandSide: Vector[Double],
    val poseDimension: Int,
    val gaugeConventionId: String,
    private val undampedPose: Array[Double],
    private val undampedCross: Array[Double],
    private val metricPose: Array[Double],
    private val metricCross: Array[Double]
):
  val dimension: Int = undamped.dimension
  val fieldDimension: Int = dimension - poseDimension
  val sourceProductsAtCompilation: Long = poseDimension.toLong * 2L

  def attempt(
      damping: Double,
      fieldPreconditioner: ArrayPreconditioner,
      solverConfig: GalePcgConfig
  ): Either[PoseEliminationError, PoseEliminatedAttempt] =
    if !damping.isFinite || damping < 0.0 then Left(PoseEliminationError.InvalidDamping(damping))
    else if fieldPreconditioner.dimension != fieldDimension then
      Left(PoseEliminationError.DimensionMismatch(fieldDimension, fieldPreconditioner.dimension))
    else
      val pose = new Array[Double](poseDimension * poseDimension)
      val cross = new Array[Double](poseDimension * fieldDimension)
      var index = 0
      while index < pose.length do
        pose(index) = undampedPose(index) + damping * metricPose(index)
        index += 1
      index = 0
      while index < cross.length do
        cross(index) = undampedCross(index) + damping * metricCross(index)
        index += 1
      TinyPositiveCholesky.factor(pose, poseDimension).left.map(PoseEliminationError.PoseFactor.apply).flatMap {
        poseFactor =>
          val poseRhs = rightHandSide.take(poseDimension).toArray
          poseFactor.solveInPlace(poseRhs).left.map(PoseEliminationError.PoseFactor.apply).flatMap { solvedPoseRhs =>
            val conditionalRhs = rightHandSide.drop(poseDimension).toArray
            var field = 0
            while field < fieldDimension do
              var reduction = 0.0
              var global = 0
              while global < poseDimension do
                reduction += cross(global * fieldDimension + field) * solvedPoseRhs(global)
                global += 1
              conditionalRhs(field) -= reduction
              field += 1
            val operator = new ConditionalFieldOperator(
              undamped,
              physicalMetric,
              damping,
              poseDimension,
              fieldDimension,
              cross,
              poseFactor
            )
            GalePcgPlan
              .create(operator, fieldPreconditioner, solverConfig)
              .left
              .map(PoseEliminationError.Pcg.apply)
              .map(plan =>
                new PoseEliminatedAttempt(
                  this,
                  damping,
                  cross,
                  poseFactor,
                  conditionalRhs.toVector,
                  operator,
                  plan,
                  solverConfig.relativeTolerance
                )
              )
          }
      }

private[flashalign] object PoseEliminatedLinearization:
  def create(
      undamped: ArraySymmetricOperator,
      physicalMetric: ArraySymmetricOperator,
      rightHandSide: Vector[Double],
      poseDimension: Int,
      gaugeConventionId: String,
      symmetryTolerance: Double = 1e-10
  ): Either[PoseEliminationError, PoseEliminatedLinearization] =
    val dimension = undamped.dimension
    if dimension != physicalMetric.dimension then
      Left(PoseEliminationError.DimensionMismatch(dimension, physicalMetric.dimension))
    else if rightHandSide.length != dimension then
      Left(PoseEliminationError.DimensionMismatch(dimension, rightHandSide.length))
    else if poseDimension != 6 && poseDimension != 12 then
      Left(PoseEliminationError.InvalidPoseDimension(poseDimension))
    else if dimension <= poseDimension then
      Left(PoseEliminationError.InvalidFieldDimension(dimension - poseDimension))
    else if gaugeConventionId.trim.isEmpty then Left(PoseEliminationError.MissingGaugeConvention)
    else if !symmetryTolerance.isFinite || symmetryTolerance <= 0.0 then
      Left(PoseEliminationError.InvalidSymmetryTolerance(symmetryTolerance))
    else if rightHandSide.exists(value => !value.isFinite) then Left(PoseEliminationError.NonFiniteRightHandSide)
    else
      for
        hBlocks <- extractPoseAndCross(undamped, poseDimension, symmetryTolerance, "undamped")
        dBlocks <- extractPoseAndCross(physicalMetric, poseDimension, symmetryTolerance, "physical metric")
      yield new PoseEliminatedLinearization(
        undamped,
        physicalMetric,
        rightHandSide,
        poseDimension,
        gaugeConventionId,
        hBlocks._1,
        hBlocks._2,
        dBlocks._1,
        dBlocks._2
      )

  private[flashalign] def extractPoseAndCross(
      operator: ArraySymmetricOperator,
      poseDimension: Int,
      symmetryTolerance: Double,
      component: String
  ): Either[PoseEliminationError, (Array[Double], Array[Double])] =
    val dimension = operator.dimension
    val fieldDimension = dimension - poseDimension
    val pose = new Array[Double](poseDimension * poseDimension)
    val cross = new Array[Double](poseDimension * fieldDimension)
    val input = new Array[Double](dimension)
    val output = new Array[Double](dimension)
    var column = 0
    while column < poseDimension do
      java.util.Arrays.fill(input, 0.0)
      input(column) = 1.0
      operator(input, output) match
        case Left(detail) => return Left(PoseEliminationError.OperatorFailure(component, detail))
        case Right(()) =>
          var row = 0
          while row < dimension do
            if !output(row).isFinite then
              return Left(PoseEliminationError.NonFiniteOperatorValue(component, row, column, output(row)))
            if row < poseDimension then pose(row * poseDimension + column) = output(row)
            else cross(column * fieldDimension + row - poseDimension) = output(row)
            row += 1
      column += 1
    var row = 0
    while row < poseDimension do
      column = row + 1
      while column < poseDimension do
        val left = pose(row * poseDimension + column)
        val right = pose(column * poseDimension + row)
        val scale = math.max(1.0, math.max(math.abs(left), math.abs(right)))
        if math.abs(left - right) > symmetryTolerance * scale then
          return Left(PoseEliminationError.AsymmetricPoseBlock(component, row, column, left, right))
        val average = 0.5 * (left + right)
        pose(row * poseDimension + column) = average
        pose(column * poseDimension + row) = average
        column += 1
      row += 1
    Right((pose, cross))

private[flashalign] final case class PoseEliminatedDiagnostics(
    damping: Double,
    poseDimension: Int,
    fieldDimension: Int,
    gaugeConventionId: String,
    sourceProductsAtCompilation: Long,
    conditionalProducts: Long,
    fullResidualNorm: Double,
    requiredFullResidualNorm: Double,
    rightHandSideNorm: Double,
    predictedUndampedReduction: Double,
    pcg: GalePcgDiagnostics
)

private[flashalign] final case class PoseEliminatedDirection(
    values: Vector[Double],
    fieldTermination: GalePcgTermination,
    diagnostics: PoseEliminatedDiagnostics
):
  def converged: Boolean = fieldTermination == GalePcgTermination.Converged

private[flashalign] final class PoseEliminatedAttempt private[flashalign] (
    linearization: PoseEliminatedLinearization,
    val damping: Double,
    private val cross: Array[Double],
    private val poseFactor: TinyPositiveCholesky,
    val conditionalRightHandSide: Vector[Double],
    private val conditionalOperator: ConditionalFieldOperator,
    private val solver: GalePcgPlan,
    private val relativeTolerance: Double
):
  def newWorkspace(): GalePcgWorkspace = solver.newWorkspace()

  def conditionalProduct(input: Array[Double], output: Array[Double]): Either[PoseEliminationError, Unit] =
    conditionalOperator(input, output).left.map(detail => PoseEliminationError.OperatorFailure("Schur", detail))

  def solve(workspace: GalePcgWorkspace): Either[PoseEliminationError, PoseEliminatedDirection] =
    solver.solve(conditionalRightHandSide, workspace).left.map(PoseEliminationError.Pcg.apply).flatMap { field =>
      recover(field.values.toArray).flatMap { full =>
        evaluateFullResidual(full).flatMap { residual =>
          undampedPrediction(full).map { predicted =>
            val rhsNorm = math.sqrt(linearization.rightHandSide.map(value => value * value).sum)
            val requiredFullResidual = relativeTolerance * rhsNorm
            val termination =
              if field.termination == GalePcgTermination.Converged && residual > requiredFullResidual then
                GalePcgTermination.ResidualRejected
              else field.termination
            PoseEliminatedDirection(
              full.toVector,
              termination,
              PoseEliminatedDiagnostics(
                damping,
                linearization.poseDimension,
                linearization.fieldDimension,
                linearization.gaugeConventionId,
                linearization.sourceProductsAtCompilation,
                conditionalOperator.productCount,
                residual,
                requiredFullResidual,
                rhsNorm,
                predicted,
                field.diagnostics
              )
            )
          }
        }
      }
    }

  private def recover(field: Array[Double]): Either[PoseEliminationError, Array[Double]] =
    val poseRhs = linearization.rightHandSide.take(linearization.poseDimension).toArray
    var global = 0
    while global < linearization.poseDimension do
      var contribution = 0.0
      var local = 0
      while local < linearization.fieldDimension do
        contribution += cross(global * linearization.fieldDimension + local) * field(local)
        local += 1
      poseRhs(global) -= contribution
      global += 1
    poseFactor.solveInPlace(poseRhs).left.map(PoseEliminationError.PoseFactor.apply).map { pose =>
      val full = new Array[Double](linearization.dimension)
      java.lang.System.arraycopy(pose, 0, full, 0, pose.length)
      java.lang.System.arraycopy(field, 0, full, pose.length, field.length)
      full
    }

  private def evaluateFullResidual(direction: Array[Double]): Either[PoseEliminationError, Double] =
    val h = new Array[Double](linearization.dimension)
    val d = new Array[Double](linearization.dimension)
    linearization.undamped(direction, h).left.map(PoseEliminationError.OperatorFailure("undamped", _)).flatMap { _ =>
      linearization.physicalMetric(direction, d).left.map(PoseEliminationError.OperatorFailure("physical metric", _)).flatMap {
        _ =>
          var squared = 0.0
          var index = 0
          while index < direction.length do
            val residual = linearization.rightHandSide(index) - h(index) - damping * d(index)
            squared += residual * residual
            index += 1
          val norm = math.sqrt(squared)
          if norm.isFinite then Right(norm) else Left(PoseEliminationError.NonFiniteFullResidual)
      }
    }

  private def undampedPrediction(direction: Array[Double]): Either[PoseEliminationError, Double] =
    val product = new Array[Double](direction.length)
    linearization.undamped(direction, product).left.map(PoseEliminationError.OperatorFailure("undamped", _)).flatMap {
      _ =>
        var linear = 0.0
        var quadratic = 0.0
        var index = 0
        while index < direction.length do
          linear += linearization.rightHandSide(index) * direction(index)
          quadratic += direction(index) * product(index)
          index += 1
        val predicted = linear - 0.5 * quadratic
        if predicted.isFinite then Right(predicted) else Left(PoseEliminationError.NonFinitePrediction)
    }

private final class ConditionalFieldOperator(
    undamped: ArraySymmetricOperator,
    physicalMetric: ArraySymmetricOperator,
    damping: Double,
    poseDimension: Int,
    val dimension: Int,
    cross: Array[Double],
    poseFactor: TinyPositiveCholesky
) extends ArraySymmetricOperator:
  private val fullDimension = poseDimension + dimension
  private val fullInput = new Array[Double](fullDimension)
  private val hOutput = new Array[Double](fullDimension)
  private val dOutput = new Array[Double](fullDimension)
  private val poseScratch = new Array[Double](poseDimension)
  private var products = 0L

  def productCount: Long = products

  def apply(input: Array[Double], output: Array[Double]): Either[String, Unit] =
    if input.length != dimension || output.length != dimension then Left(s"Schur operator requires $dimension values")
    else
      java.util.Arrays.fill(fullInput, 0.0)
      java.lang.System.arraycopy(input, 0, fullInput, poseDimension, dimension)
      undamped(fullInput, hOutput).flatMap { _ =>
        physicalMetric(fullInput, dOutput).flatMap { _ =>
          var global = 0
          while global < poseDimension do
            var value = 0.0
            var field = 0
            while field < dimension do
              value += cross(global * dimension + field) * input(field)
              field += 1
            poseScratch(global) = value
            global += 1
          poseFactor.solveInPlace(poseScratch) match
            case Left(error) => Left(error.message)
            case Right(solved) =>
              var field = 0
              var invalid = Option.empty[String]
              while field < dimension && invalid.isEmpty do
                var correction = 0.0
                global = 0
                while global < poseDimension do
                  correction += cross(global * dimension + field) * solved(global)
                  global += 1
                output(field) = hOutput(poseDimension + field) +
                  damping * dOutput(poseDimension + field) - correction
                if !output(field).isFinite then invalid = Some(s"non-finite Schur product at $field")
                field += 1
              invalid match
                case Some(detail) => Left(detail)
                case None =>
                  products += 1L
                  Right(())
        }
      }

private final class TinyPositiveCholesky private (
    val dimension: Int,
    private val lower: Array[Double]
):
  def solveInPlace(rightHandSide: Array[Double]): Either[TinyFactorError, Array[Double]] =
    if rightHandSide.length != dimension then Left(TinyFactorError.DimensionMismatch(dimension, rightHandSide.length))
    else
      solveLowerInPlace(rightHandSide)
        .flatMap(solveUpperInPlace)

  def solveLowerInPlace(rightHandSide: Array[Double]): Either[TinyFactorError, Array[Double]] =
    if rightHandSide.length != dimension then Left(TinyFactorError.DimensionMismatch(dimension, rightHandSide.length))
    else
      var row = 0
      while row < dimension do
        var value = rightHandSide(row)
        var column = 0
        while column < row do
          value -= lower(row * dimension + column) * rightHandSide(column)
          column += 1
        rightHandSide(row) = value / lower(row * dimension + row)
        row += 1
      Right(rightHandSide)

  def solveUpperInPlace(rightHandSide: Array[Double]): Either[TinyFactorError, Array[Double]] =
    if rightHandSide.length != dimension then Left(TinyFactorError.DimensionMismatch(dimension, rightHandSide.length))
    else
      var row = dimension - 1
      while row >= 0 do
        var value = rightHandSide(row)
        var column = row + 1
        while column < dimension do
          value -= lower(column * dimension + row) * rightHandSide(column)
          column += 1
        rightHandSide(row) = value / lower(row * dimension + row)
        if !rightHandSide(row).isFinite then return Left(TinyFactorError.NonFiniteSolution(row))
        row -= 1
      Right(rightHandSide)

private object TinyPositiveCholesky:
  def factor(matrix: Array[Double], dimension: Int): Either[TinyFactorError, TinyPositiveCholesky] =
    if matrix.length != dimension * dimension then
      Left(TinyFactorError.DimensionMismatch(dimension * dimension, matrix.length))
    else
      val lower = new Array[Double](matrix.length)
      val scale = matrix.indices.collect {
        case index if index / dimension == index % dimension => math.abs(matrix(index))
      }.maxOption.getOrElse(0.0)
      val threshold = math.max(1e-15, scale * 1e-14)
      var row = 0
      while row < dimension do
        var column = 0
        while column <= row do
          var value = matrix(row * dimension + column)
          if !value.isFinite then return Left(TinyFactorError.NonFiniteMatrix(row, column, value))
          var inner = 0
          while inner < column do
            value -= lower(row * dimension + inner) * lower(column * dimension + inner)
            inner += 1
          if row == column then
            if value <= threshold then return Left(TinyFactorError.NotPositiveDefinite(row, value, threshold))
            lower(row * dimension + column) = math.sqrt(value)
          else lower(row * dimension + column) = value / lower(column * dimension + column)
          column += 1
        row += 1
      Right(new TinyPositiveCholesky(dimension, lower))

private[flashalign] sealed trait TinyFactorError derives CanEqual:
  def message: String

private[flashalign] object TinyFactorError:
  final case class DimensionMismatch(expected: Int, actual: Int) extends TinyFactorError:
    val message = s"tiny factor dimension mismatch: expected $expected, got $actual"
  final case class NonFiniteMatrix(row: Int, column: Int, value: Double) extends TinyFactorError:
    val message = s"non-finite pose block at ($row, $column): $value"
  final case class NotPositiveDefinite(pivot: Int, value: Double, threshold: Double) extends TinyFactorError:
    val message = s"pose block is not positive definite at $pivot: $value <= $threshold"
  final case class NonFiniteSolution(index: Int) extends TinyFactorError:
    val message = s"pose solve produced a non-finite value at $index"

private[flashalign] sealed trait PoseEliminationError derives CanEqual:
  def message: String

private[flashalign] object PoseEliminationError:
  final case class DimensionMismatch(expected: Int, actual: Int) extends PoseEliminationError:
    val message = s"full-system dimension mismatch: expected $expected, got $actual"
  final case class InvalidPoseDimension(actual: Int) extends PoseEliminationError:
    val message = s"global pose dimension must be 6 or 12, got $actual"
  final case class InvalidFieldDimension(actual: Int) extends PoseEliminationError:
    val message = s"field dimension must be positive, got $actual"
  case object MissingGaugeConvention extends PoseEliminationError:
    val message = "field coordinates require an explicit gauge convention"
  final case class InvalidSymmetryTolerance(actual: Double) extends PoseEliminationError:
    val message = s"symmetry tolerance must be finite and positive, got $actual"
  case object NonFiniteRightHandSide extends PoseEliminationError:
    val message = "full-system right-hand side must be finite"
  final case class InvalidDamping(actual: Double) extends PoseEliminationError:
    val message = s"damping must be finite and nonnegative, got $actual"
  final case class OperatorFailure(component: String, detail: String) extends PoseEliminationError:
    val message = s"$component operator failed: $detail"
  final case class NonFiniteOperatorValue(component: String, row: Int, column: Int, value: Double)
      extends PoseEliminationError:
    val message = s"$component operator column $column is non-finite at row $row: $value"
  final case class AsymmetricPoseBlock(
      component: String,
      row: Int,
      column: Int,
      left: Double,
      right: Double
  ) extends PoseEliminationError:
    val message = s"$component pose block is asymmetric at ($row, $column): $left versus $right"
  final case class PoseFactor(error: TinyFactorError) extends PoseEliminationError:
    val message = error.message
  final case class Pcg(error: GalePcgError) extends PoseEliminationError:
    val message = error.message
  case object NonFiniteFullResidual extends PoseEliminationError:
    val message = "full damped residual is non-finite"
  case object NonFinitePrediction extends PoseEliminationError:
    val message = "undamped objective prediction is non-finite"
  case object NoInformationDirections extends PoseEliminationError:
    val message = "at least one explicit field information direction is required"
  final case class InvalidInformationDirection(expected: Int) extends PoseEliminationError:
    val message = s"every information direction must have $expected field coefficients"
  case object NonFiniteInformationDirection extends PoseEliminationError:
    val message = "information directions must be finite"
  final case class InvalidInformationTolerance(actual: Double) extends PoseEliminationError:
    val message = s"information tolerance must be finite and positive, got $actual"
  case object NonFiniteInformationMatrix extends PoseEliminationError:
    val message = "information matrix must be finite"
  final case class InformationMetricFactor(error: TinyFactorError) extends PoseEliminationError:
    val message = s"conditional physical information metric is invalid: ${error.message}"
  final case class MateriallyNegativeInformation(component: String, value: Double, tolerance: Double)
      extends PoseEliminationError:
    val message = s"$component has materially negative eigenvalue $value below -$tolerance"
  final case class UnidentifiablePoseFieldCoupling(direction: Int, residual: Double, tolerance: Double)
      extends PoseEliminationError:
    val message =
      s"field direction $direction couples to an unidentifiable pose mode: residual $residual exceeds $tolerance"
  final case class InformationEigendecompositionLimit(sweeps: Int) extends PoseEliminationError:
    val message = s"information eigendecomposition did not converge in $sweeps sweeps"
private[flashalign] final case class PoseConditionedDataInformation(
    poseRank: Int,
    poseDimension: Int,
    supportedFieldRank: Int,
    testedFieldDirections: Int,
    generalizedEigenvalues: Vector[Double],
    conditionNumber: Double,
    dataOnly: Boolean,
    priorExcluded: Boolean,
    dampingExcluded: Boolean,
    poseConditioned: Boolean
)

/** Rank-aware data information on an explicitly supplied, small set of field
  * directions. This diagnostic never densifies the complete production field
  * normal. It conditions on pose with a pseudoinverse of the data-only pose
  * block and whitens the tested directions by the conditional physical metric.
  */
private[flashalign] object PoseConditionedDataInformation:
  def analyze(
      dataOnly: ArraySymmetricOperator,
      physicalMetric: ArraySymmetricOperator,
      poseDimension: Int,
      fieldDirections: Vector[Vector[Double]],
      relativeTolerance: Double = 1e-10
  ): Either[PoseEliminationError, PoseConditionedDataInformation] =
    val fullDimension = dataOnly.dimension
    val fieldDimension = fullDimension - poseDimension
    if physicalMetric.dimension != fullDimension then
      Left(PoseEliminationError.DimensionMismatch(fullDimension, physicalMetric.dimension))
    else if poseDimension != 6 && poseDimension != 12 then
      Left(PoseEliminationError.InvalidPoseDimension(poseDimension))
    else if fieldDimension <= 0 then Left(PoseEliminationError.InvalidFieldDimension(fieldDimension))
    else if fieldDirections.isEmpty then Left(PoseEliminationError.NoInformationDirections)
    else if fieldDirections.exists(_.length != fieldDimension) then
      Left(PoseEliminationError.InvalidInformationDirection(fieldDimension))
    else if fieldDirections.flatten.exists(value => !value.isFinite) then
      Left(PoseEliminationError.NonFiniteInformationDirection)
    else if !relativeTolerance.isFinite || relativeTolerance <= 0.0 then
      Left(PoseEliminationError.InvalidInformationTolerance(relativeTolerance))
    else
      for
        dataBlocks <- PoseEliminatedLinearization.extractPoseAndCross(
          dataOnly,
          poseDimension,
          relativeTolerance,
          "data-only"
        )
        metricBlocks <- PoseEliminatedLinearization.extractPoseAndCross(
          physicalMetric,
          poseDimension,
          relativeTolerance,
          "information physical metric"
        )
        poseSpectrum <- TinySymmetricSpectrum.decompose(dataBlocks._1, poseDimension)
        poseTolerance = relativeTolerance * math.max(1.0, poseSpectrum.maximumMagnitude)
        _ <- validateSemidefinite(poseSpectrum.eigenvalues, poseTolerance, "data-only pose")
        metricPose <- TinyPositiveCholesky.factor(metricBlocks._1, poseDimension)
          .left.map(PoseEliminationError.PoseFactor.apply)
        actions <- conditionalActions(
          dataOnly,
          physicalMetric,
          poseDimension,
          fieldDirections,
          dataBlocks._2,
          metricBlocks._2,
          poseSpectrum,
          poseTolerance,
          metricPose,
          relativeTolerance
        )
        information = gram(fieldDirections, actions._1)
        metric = gram(fieldDirections, actions._2)
        metricFactor <- TinyPositiveCholesky.factor(metric, fieldDirections.length)
          .left.map(PoseEliminationError.InformationMetricFactor.apply)
        whitened <- whiten(information, metricFactor)
        fieldSpectrum <- TinySymmetricSpectrum.decompose(whitened, fieldDirections.length)
        fieldTolerance = relativeTolerance * math.max(1.0, fieldSpectrum.maximumMagnitude)
        _ <- validateSemidefinite(fieldSpectrum.eigenvalues, fieldTolerance, "pose-conditioned data")
      yield
        val eigenvalues = fieldSpectrum.eigenvalues.sorted.map(value => if math.abs(value) <= fieldTolerance then 0.0 else value)
        val supported = eigenvalues.filter(_ > fieldTolerance)
        PoseConditionedDataInformation(
          poseRank = poseSpectrum.eigenvalues.count(_ > poseTolerance),
          poseDimension = poseDimension,
          supportedFieldRank = supported.size,
          testedFieldDirections = fieldDirections.size,
          generalizedEigenvalues = eigenvalues,
          conditionNumber = if supported.isEmpty then Double.PositiveInfinity else supported.last / supported.head,
          dataOnly = true,
          priorExcluded = true,
          dampingExcluded = true,
          poseConditioned = true
        )

  private def conditionalActions(
      data: ArraySymmetricOperator,
      metric: ArraySymmetricOperator,
      poseDimension: Int,
      directions: Vector[Vector[Double]],
      dataCross: Array[Double],
      metricCross: Array[Double],
      dataPose: TinySymmetricSpectrum,
      dataTolerance: Double,
      metricPose: TinyPositiveCholesky,
      relativeTolerance: Double
  ): Either[PoseEliminationError, (Vector[Vector[Double]], Vector[Vector[Double]])] =
    val fullDimension = data.dimension
    val fieldDimension = fullDimension - poseDimension
    val input = new Array[Double](fullDimension)
    val dataOutput = new Array[Double](fullDimension)
    val metricOutput = new Array[Double](fullDimension)
    val dataActions = Vector.newBuilder[Vector[Double]]
    val metricActions = Vector.newBuilder[Vector[Double]]
    var failure = Option.empty[PoseEliminationError]
    var directionIndex = 0
    while directionIndex < directions.length && failure.isEmpty do
      val direction = directions(directionIndex)
      java.util.Arrays.fill(input, 0.0)
      var field = 0
      while field < fieldDimension do
        input(poseDimension + field) = direction(field)
        field += 1
      data(input, dataOutput) match
        case Left(detail) => failure = Some(PoseEliminationError.OperatorFailure("data-only information", detail))
        case Right(()) => ()
      if failure.isEmpty then
        metric(input, metricOutput) match
          case Left(detail) =>
            failure = Some(PoseEliminationError.OperatorFailure("information physical metric", detail))
          case Right(()) => ()
      if failure.isEmpty then
        val dataPoseRhs = dataOutput.take(poseDimension)
        val dataSolved = dataPose.pseudoSolve(dataPoseRhs, dataTolerance)
        val projected = multiplyPose(dataPose.matrix, poseDimension, dataSolved)
        val residual = normDifference(dataPoseRhs, projected)
        val scale = math.max(1.0, norm(dataPoseRhs))
        if residual > relativeTolerance * scale then
          failure = Some(
            PoseEliminationError.UnidentifiablePoseFieldCoupling(
              directionIndex,
              residual,
              relativeTolerance * scale
            )
          )
        else
          val metricSolved = metricOutput.take(poseDimension)
          metricPose.solveInPlace(metricSolved) match
            case Left(error) => failure = Some(PoseEliminationError.PoseFactor(error))
            case Right(_) =>
              dataActions += correctedField(dataOutput, poseDimension, dataCross, dataSolved, fieldDimension)
              metricActions += correctedField(metricOutput, poseDimension, metricCross, metricSolved, fieldDimension)
      directionIndex += 1
    failure.toLeft((dataActions.result(), metricActions.result()))

  private def correctedField(
      fullAction: Array[Double],
      poseDimension: Int,
      cross: Array[Double],
      solvedPose: Array[Double],
      fieldDimension: Int
  ): Vector[Double] = Vector.tabulate(fieldDimension) { field =>
    var correction = 0.0
    var global = 0
    while global < poseDimension do
      correction += cross(global * fieldDimension + field) * solvedPose(global)
      global += 1
    fullAction(poseDimension + field) - correction
  }

  private def gram(directions: Vector[Vector[Double]], actions: Vector[Vector[Double]]): Array[Double] =
    val size = directions.size
    val matrix = new Array[Double](size * size)
    var row = 0
    while row < size do
      var column = 0
      while column < size do
        matrix(row * size + column) = dot(directions(row), actions(column))
        column += 1
      row += 1
    symmetrize(matrix, size)
    matrix

  private def whiten(
      information: Array[Double],
      metricFactor: TinyPositiveCholesky
  ): Either[PoseEliminationError, Array[Double]] =
    val size = metricFactor.dimension
    val left = new Array[Double](size * size)
    var column = 0
    while column < size do
      val values = Array.tabulate(size)(row => information(row * size + column))
      metricFactor.solveLowerInPlace(values) match
        case Left(error) => return Left(PoseEliminationError.InformationMetricFactor(error))
        case Right(solved) =>
          var row = 0
          while row < size do
            left(row * size + column) = solved(row)
            row += 1
      column += 1
    val whitened = new Array[Double](size * size)
    var row = 0
    while row < size do
      val values = Array.tabulate(size)(columnIndex => left(row * size + columnIndex))
      metricFactor.solveLowerInPlace(values) match
        case Left(error) => return Left(PoseEliminationError.InformationMetricFactor(error))
        case Right(solved) =>
          column = 0
          while column < size do
            whitened(row * size + column) = solved(column)
            column += 1
      row += 1
    symmetrize(whitened, size)
    Right(whitened)

  private def validateSemidefinite(
      eigenvalues: Vector[Double],
      tolerance: Double,
      component: String
  ): Either[PoseEliminationError, Unit] =
    eigenvalues.find(_ < -tolerance).toLeft(()).left.map(value =>
      PoseEliminationError.MateriallyNegativeInformation(component, value, tolerance)
    )

  private def symmetrize(matrix: Array[Double], size: Int): Unit =
    var row = 0
    while row < size do
      var column = row + 1
      while column < size do
        val average = 0.5 * (matrix(row * size + column) + matrix(column * size + row))
        matrix(row * size + column) = average
        matrix(column * size + row) = average
        column += 1
      row += 1

  private def multiplyPose(matrix: Array[Double], size: Int, vector: Array[Double]): Array[Double] =
    Array.tabulate(size)(row =>
      var sum = 0.0
      var column = 0
      while column < size do
        sum += matrix(row * size + column) * vector(column)
        column += 1
      sum
    )

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

  private def norm(values: Array[Double]): Double = math.sqrt(values.map(value => value * value).sum)

  private def normDifference(left: Array[Double], right: Array[Double]): Double =
    math.sqrt(left.indices.map(index =>
      val difference = left(index) - right(index)
      difference * difference
    ).sum)

private final case class TinySymmetricSpectrum(
    matrix: Array[Double],
    eigenvalues: Vector[Double],
    eigenvectors: Array[Double]
):
  val dimension: Int = eigenvalues.length
  val maximumMagnitude: Double = eigenvalues.map(math.abs).maxOption.getOrElse(0.0)

  def pseudoSolve(rightHandSide: Array[Double], tolerance: Double): Array[Double] =
    val output = new Array[Double](dimension)
    var mode = 0
    while mode < dimension do
      val eigenvalue = eigenvalues(mode)
      if eigenvalue > tolerance then
        var projection = 0.0
        var row = 0
        while row < dimension do
          projection += eigenvectors(row * dimension + mode) * rightHandSide(row)
          row += 1
        val coefficient = projection / eigenvalue
        row = 0
        while row < dimension do
          output(row) += eigenvectors(row * dimension + mode) * coefficient
          row += 1
      mode += 1
    output

private object TinySymmetricSpectrum:
  def decompose(matrix: Array[Double], dimension: Int): Either[PoseEliminationError, TinySymmetricSpectrum] =
    if matrix.length != dimension * dimension then
      Left(PoseEliminationError.DimensionMismatch(dimension * dimension, matrix.length))
    else if matrix.exists(value => !value.isFinite) then Left(PoseEliminationError.NonFiniteInformationMatrix)
    else
      val work = matrix.clone()
      val vectors = new Array[Double](matrix.length)
      var diagonal = 0
      while diagonal < dimension do
        vectors(diagonal * dimension + diagonal) = 1.0
        diagonal += 1
      val scale = matrix.map(math.abs).maxOption.getOrElse(0.0)
      val convergence = math.max(1e-15, scale * 1e-14)
      val maximumSweeps = 64 * dimension * dimension
      var sweep = 0
      var done = false
      while sweep < maximumSweeps && !done do
        var p = 0
        var q = 0
        var maximum = 0.0
        var row = 0
        while row < dimension do
          var column = row + 1
          while column < dimension do
            val value = math.abs(work(row * dimension + column))
            if value > maximum then
              maximum = value
              p = row
              q = column
            column += 1
          row += 1
        if maximum <= convergence then done = true
        else
          rotate(work, vectors, dimension, p, q)
          sweep += 1
      if !done then Left(PoseEliminationError.InformationEigendecompositionLimit(maximumSweeps))
      else
        Right(
          TinySymmetricSpectrum(
            matrix.clone(),
            Vector.tabulate(dimension)(index => work(index * dimension + index)),
            vectors
          )
        )

  private def rotate(matrix: Array[Double], vectors: Array[Double], size: Int, p: Int, q: Int): Unit =
    val app = matrix(p * size + p)
    val aqq = matrix(q * size + q)
    val apq = matrix(p * size + q)
    val tau = (aqq - app) / (2.0 * apq)
    val t = (if tau >= 0.0 then 1.0 else -1.0) / (math.abs(tau) + math.sqrt(1.0 + tau * tau))
    val cosine = 1.0 / math.sqrt(1.0 + t * t)
    val sine = t * cosine
    var index = 0
    while index < size do
      if index != p && index != q then
        val aip = matrix(index * size + p)
        val aiq = matrix(index * size + q)
        val newP = cosine * aip - sine * aiq
        val newQ = sine * aip + cosine * aiq
        matrix(index * size + p) = newP
        matrix(p * size + index) = newP
        matrix(index * size + q) = newQ
        matrix(q * size + index) = newQ
      val vip = vectors(index * size + p)
      val viq = vectors(index * size + q)
      vectors(index * size + p) = cosine * vip - sine * viq
      vectors(index * size + q) = sine * vip + cosine * viq
      index += 1
    matrix(p * size + p) = app - t * apq
    matrix(q * size + q) = aqq + t * apq
    matrix(p * size + q) = 0.0
    matrix(q * size + p) = 0.0
