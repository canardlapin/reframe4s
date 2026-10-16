package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame
import reframe4s.lie.FramedAffine
import reframe4s.lie.Rigid3

private[flashalign] enum SmallStrainPoseKind(val id: String, val parameterCount: Int) derives CanEqual:
  case Rigid extends SmallStrainPoseKind("rigid", 6)
  case Affine extends SmallStrainPoseKind("affine", 12)

private[flashalign] final case class SmallStrainPriorWeights(
    shearStrain: Double,
    divergence: Double,
    bending: Double,
    magnitude: Double
)

private[flashalign] final case class SmallStrainModelConfig(
    minimumCoefficients: Int,
    maximumCoefficients: Int,
    recommendedInitialCoefficients: Int,
    rankTolerance: Double
)

private[flashalign] object SmallStrainModelConfig:
  def create(
      minimumCoefficients: Int = 48,
      maximumCoefficients: Int = 192,
      recommendedInitialCoefficients: Int = 96,
      rankTolerance: Double = 1e-10
  ): Either[SmallStrainError, SmallStrainModelConfig] =
    if minimumCoefficients <= 0 || maximumCoefficients < minimumCoefficients then
      Left(SmallStrainError.InvalidConfig("coefficient range must be positive and ordered"))
    else if recommendedInitialCoefficients < minimumCoefficients || recommendedInitialCoefficients > maximumCoefficients then
      Left(SmallStrainError.InvalidConfig("recommended coefficient count must lie in the configured range"))
    else if !rankTolerance.isFinite || rankTolerance <= 0.0 then
      Left(SmallStrainError.InvalidConfig("rank tolerance must be finite and positive"))
    else Right(SmallStrainModelConfig(minimumCoefficients, maximumCoefficients, recommendedInitialCoefficients, rankTolerance))

private[flashalign] final case class SmallStrainBasisMode3(
    id: String,
    scalarMode: SpectralMode3,
    polarization: Int,
    poseProjection: Vector[Double],
    derivativeBound: Double,
    amplitudeBoundMmPerCoefficient: Double
)

/** Vector modes obtained by projecting fixed low-frequency scalar/polarization
  * fields away from a geometry-only rigid or affine pose space. Coefficients
  * and values are millimetres; derivatives are dimensionless.
  */
private[flashalign] final class SmallStrainVectorBasis3 private (
    val id: String,
    val scalarBasisId: String,
    val domain: PhysicalSpectralDomain3,
    val gaugeId: String,
    val poseKind: SmallStrainPoseKind,
    val pivotWorldMm: Vector[Double],
    val modes: Vector[SmallStrainBasisMode3],
    val effectiveRank: Int,
    val priorPrecision: DenseOperator,
    val units: String,
    val gaugeDescription: String,
    val extensionDescription: String
):
  val nominalSize: Int = modes.length

  def coefficientState(coefficientsMm: Vector[Double]): Either[SmallStrainError, SmallStrainCoefficientState] =
    if coefficientsMm.length != nominalSize then
      Left(SmallStrainError.ParameterCountMismatch(nominalSize, coefficientsMm.length))
    else if coefficientsMm.exists(value => !value.isFinite) then Left(SmallStrainError.NonFiniteCoefficients)
    else Right(SmallStrainCoefficientState(id, coefficientsMm))

  def requireState(state: SmallStrainCoefficientState): Either[SmallStrainError, Unit] =
    if state.basisId != id then Left(SmallStrainError.BasisIdentityMismatch(id, state.basisId))
    else if state.coefficientsMm.length != nominalSize then
      Left(SmallStrainError.ParameterCountMismatch(nominalSize, state.coefficientsMm.length))
    else if state.coefficientsMm.exists(value => !value.isFinite) then Left(SmallStrainError.NonFiniteCoefficients)
    else Right(())

  def modeValue(modeIndex: Int, x: Double, y: Double, z: Double, output: Array[Double]): Unit =
    val mode = modes(modeIndex)
    val phi = SmallStrainVectorBasis3.scalarValue(domain, mode.scalarMode, x, y, z)
    output(0) = (if mode.polarization == 0 then phi else 0.0)
    output(1) = (if mode.polarization == 1 then phi else 0.0)
    output(2) = (if mode.polarization == 2 then phi else 0.0)
    SmallStrainVectorBasis3.subtractPoseField(
      poseKind,
      pivotWorldMm,
      mode.poseProjection,
      x,
      y,
      z,
      output
    )

  def modeGradient(modeIndex: Int, x: Double, y: Double, z: Double, output: Array[Double]): Unit =
    val mode = modes(modeIndex)
    val gradient = SmallStrainVectorBasis3.scalarGradient(domain, mode.scalarMode, x, y, z)
    java.util.Arrays.fill(output, 0.0)
    var axis = 0
    while axis < 3 do
      output(mode.polarization * 3 + axis) = gradient(axis)
      axis += 1
    SmallStrainVectorBasis3.subtractPoseGradient(poseKind, mode.poseProjection, output)

private[flashalign] object SmallStrainVectorBasis3:
  val Revision = "small-strain-vector-gauge-v1"

  def compile(
      scalar: PhysicalSpectralBasis3,
      gauge: GeometryGaugeMeasure3,
      poseKind: SmallStrainPoseKind,
      pivotWorldMm: Vector[Double],
      weights: SmallStrainPriorWeights,
      config: SmallStrainModelConfig
  ): Either[SmallStrainError, SmallStrainVectorBasis3] =
    val nominal = scalar.nominalSize * 3
    if scalar.gaugeId != gauge.id then Left(SmallStrainError.GaugeIdentityMismatch(scalar.gaugeId, gauge.id))
    else if pivotWorldMm.length != 3 || pivotWorldMm.exists(value => !value.isFinite) then
      Left(SmallStrainError.InvalidConfig("pose-gauge pivot must be a finite world-mm triple"))
    else if nominal < config.minimumCoefficients || nominal > config.maximumCoefficients then
      Left(SmallStrainError.CoefficientCountOutsideRange(nominal, config.minimumCoefficients, config.maximumCoefficients))
    else validateWeights(weights).flatMap { _ =>
      val poseGram = DenseOperator.tabulate(poseKind.parameterCount) { (row, column) =>
        gauge.pointsWorldMm.zip(gauge.normalizedWeights).map { case (point, weight) =>
          weight * dot(
            poseField(poseKind, pivotWorldMm, point, row),
            poseField(poseKind, pivotWorldMm, point, column)
          )
        }.sum
      }
      val rank = matrixRank(poseGram, config.rankTolerance)
      if rank != poseKind.parameterCount then Left(SmallStrainError.PoseGaugeRankDeficient(poseKind.parameterCount, rank))
      else
        val built = Vector.newBuilder[SmallStrainBasisMode3]
        var failure = Option.empty[SmallStrainError]
        var scalarIndex = 0
        while scalarIndex < scalar.nominalSize && failure.isEmpty do
          var polarization = 0
          while polarization < 3 && failure.isEmpty do
            val scalarMode = scalar.modes(scalarIndex)
            val rhs = Vector.tabulate(poseKind.parameterCount) { pose =>
              gauge.pointsWorldMm.zip(gauge.normalizedWeights).map { case (point, weight) =>
                val raw = Vector.tabulate(3)(axis =>
                  if axis == polarization then scalarValue(scalar.domain, scalarMode, point(0), point(1), point(2)) else 0.0
                )
                weight * dot(poseField(poseKind, pivotWorldMm, point, pose), raw)
              }.sum
            }
            solve(poseGram, rhs, config.rankTolerance) match
              case Left(error) => failure = Some(error)
              case Right(projection) =>
                val projectionGradient = poseGradientCombination(poseKind, projection)
                val derivativeBound = scalarMode.gradientBoundPerMm + spectralNorm3(projectionGradient)
                val projectionAmplitude = paddedCorners(scalar.domain).map(point =>
                  norm(poseFieldCombination(poseKind, pivotWorldMm, projection, point))
                ).max
                built += SmallStrainBasisMode3(
                  s"${scalarMode.id}:p$polarization:${poseKind.id}",
                  scalarMode,
                  polarization,
                  projection,
                  derivativeBound,
                  math.sqrt(2.0) + math.abs(scalarMode.gaugeMean) + projectionAmplitude
                )
            polarization += 1
          scalarIndex += 1
        failure.toLeft(built.result()).flatMap { modes =>
          val mass = gram(gauge, modes, poseKind, pivotWorldMm, scalar.domain, GramComponent.Magnitude)
          val effective = matrixRank(mass, config.rankTolerance)
          if effective != modes.length then Left(SmallStrainError.VectorGaugeRankDeficient(modes.length, effective))
          else
            val strain = gram(gauge, modes, poseKind, pivotWorldMm, scalar.domain, GramComponent.SymmetricStrain)
            val divergence = gram(gauge, modes, poseKind, pivotWorldMm, scalar.domain, GramComponent.Divergence)
            val bending = gram(gauge, modes, poseKind, pivotWorldMm, scalar.domain, GramComponent.Bending)
            val precision = DenseOperator.tabulate(modes.length) { (row, column) =>
              2.0 * weights.shearStrain * strain(row, column) +
                weights.divergence * divergence(row, column) +
                weights.bending * bending(row, column) +
                weights.magnitude * mass(row, column)
            }
            val encodedPivot = pivotWorldMm.map(java.lang.Double.toHexString).mkString(",")
            val id = s"$Revision|${scalar.id}|${gauge.id}|${poseKind.id}|$encodedPivot"
            Right(
              new SmallStrainVectorBasis3(
                id,
                scalar.id,
                scalar.domain,
                gauge.id,
                poseKind,
                pivotWorldMm,
                modes,
                effective,
                precision,
                "coefficients and displacement in millimetres; deformation gradients dimensionless",
                s"fixed geometry-only weighted projection removes ${poseKind.id} pose fields at pivot $pivotWorldMm",
                s"${scalar.domain.extensionId}; trigonometric modes minus global ${poseKind.id} fields"
              )
            )
        }
    }

  private enum GramComponent:
    case Magnitude, SymmetricStrain, Divergence, Bending

  private def validateWeights(weights: SmallStrainPriorWeights): Either[SmallStrainError, Unit] =
    val nonnegative = Vector(weights.shearStrain, weights.divergence, weights.bending)
    if nonnegative.exists(value => !value.isFinite || value < 0.0) then
      Left(SmallStrainError.InvalidConfig("strain, divergence and bending weights must be finite and nonnegative"))
    else if !weights.magnitude.isFinite || weights.magnitude <= 0.0 then
      Left(SmallStrainError.InvalidConfig("magnitude weight must be finite and positive"))
    else Right(())

  private def gram(
      gauge: GeometryGaugeMeasure3,
      modes: Vector[SmallStrainBasisMode3],
      poseKind: SmallStrainPoseKind,
      pivot: Vector[Double],
      domain: PhysicalSpectralDomain3,
      component: GramComponent
  ): DenseOperator =
    DenseOperator.tabulate(modes.length) { (row, column) =>
      gauge.pointsWorldMm.zip(gauge.normalizedWeights).map { case (point, weight) =>
        component match
          case GramComponent.Magnitude =>
            weight * dot(modeValue(modes(row), poseKind, pivot, domain, point), modeValue(modes(column), poseKind, pivot, domain, point))
          case GramComponent.SymmetricStrain =>
            weight * frobenius(sym(modeGradient(modes(row), poseKind, domain, point)), sym(modeGradient(modes(column), poseKind, domain, point)))
          case GramComponent.Divergence =>
            weight * trace(modeGradient(modes(row), poseKind, domain, point)) * trace(modeGradient(modes(column), poseKind, domain, point))
          case GramComponent.Bending =>
            weight * dot(modeLaplacian(modes(row), domain, point), modeLaplacian(modes(column), domain, point))
      }.sum
    }

  private def modeValue(
      mode: SmallStrainBasisMode3,
      poseKind: SmallStrainPoseKind,
      pivot: Vector[Double],
      domain: PhysicalSpectralDomain3,
      point: Vector[Double]
  ): Vector[Double] =
    val output = Array.tabulate(3)(axis => if axis == mode.polarization then scalarValue(domain, mode.scalarMode, point(0), point(1), point(2)) else 0.0)
    subtractPoseField(poseKind, pivot, mode.poseProjection, point(0), point(1), point(2), output)
    output.toVector

  private def modeGradient(
      mode: SmallStrainBasisMode3,
      poseKind: SmallStrainPoseKind,
      domain: PhysicalSpectralDomain3,
      point: Vector[Double]
  ): Vector[Double] =
    val gradient = scalarGradient(domain, mode.scalarMode, point(0), point(1), point(2))
    val output = Array.fill(9)(0.0)
    var axis = 0
    while axis < 3 do
      output(mode.polarization * 3 + axis) = gradient(axis)
      axis += 1
    subtractPoseGradient(poseKind, mode.poseProjection, output)
    output.toVector

  private def modeLaplacian(
      mode: SmallStrainBasisMode3,
      domain: PhysicalSpectralDomain3,
      point: Vector[Double]
  ): Vector[Double] =
    val value = scalarValue(domain, mode.scalarMode, point(0), point(1), point(2))
    val wave2 = dot(mode.scalarMode.angularWaveWorldPerMm, mode.scalarMode.angularWaveWorldPerMm)
    Vector.tabulate(3)(axis => if axis == mode.polarization then -wave2 * (value + mode.scalarMode.gaugeMean) else 0.0)

  private def sym(gradient: Vector[Double]): Vector[Double] =
    Vector.tabulate(9)(index =>
      val row = index / 3
      val column = index % 3
      0.5 * (gradient(row * 3 + column) + gradient(column * 3 + row))
    )

  private def trace(gradient: Vector[Double]): Double = gradient(0) + gradient(4) + gradient(8)
  private def frobenius(left: Vector[Double], right: Vector[Double]): Double = dot(left, right)

  private[flashalign] def scalarValue(
      domain: PhysicalSpectralDomain3,
      mode: SpectralMode3,
      x: Double,
      y: Double,
      z: Double
  ): Double =
    val angle = mode.angularWaveWorldPerMm(0) * (x - domain.originMm(0)) +
      mode.angularWaveWorldPerMm(1) * (y - domain.originMm(1)) +
      mode.angularWaveWorldPerMm(2) * (z - domain.originMm(2))
    val raw = mode.phase match
      case RealSpectralPhase.Cosine => math.sqrt(2.0) * math.cos(angle)
      case RealSpectralPhase.Sine => math.sqrt(2.0) * math.sin(angle)
    raw - mode.gaugeMean

  private[flashalign] def scalarGradient(
      domain: PhysicalSpectralDomain3,
      mode: SpectralMode3,
      x: Double,
      y: Double,
      z: Double
  ): Vector[Double] =
    val angle = mode.angularWaveWorldPerMm(0) * (x - domain.originMm(0)) +
      mode.angularWaveWorldPerMm(1) * (y - domain.originMm(1)) +
      mode.angularWaveWorldPerMm(2) * (z - domain.originMm(2))
    val scale = mode.phase match
      case RealSpectralPhase.Cosine => -math.sqrt(2.0) * math.sin(angle)
      case RealSpectralPhase.Sine => math.sqrt(2.0) * math.cos(angle)
    mode.angularWaveWorldPerMm.map(_ * scale)

  private def poseField(
      kind: SmallStrainPoseKind,
      pivot: Vector[Double],
      point: Vector[Double],
      parameter: Int
  ): Vector[Double] =
    val output = Array.fill(3)(0.0)
    addPoseField(kind, pivot, parameter, point(0), point(1), point(2), output)
    output.toVector

  private def poseFieldCombination(
      kind: SmallStrainPoseKind,
      pivot: Vector[Double],
      coefficients: Vector[Double],
      point: Vector[Double]
  ): Vector[Double] =
    Vector.tabulate(3)(axis =>
      coefficients.indices.map(parameter => coefficients(parameter) * poseField(kind, pivot, point, parameter)(axis)).sum
    )

  private def paddedCorners(domain: PhysicalSpectralDomain3): Vector[Vector[Double]] =
    val limits = domain.periodsMm.zip(domain.paddingMm).map { case (period, padding) => Vector(-padding, period + padding) }
    (for
      x <- limits(0)
      y <- limits(1)
      z <- limits(2)
    yield
      val local = Vector(x, y, z)
      Vector.tabulate(3)(world =>
        domain.originMm(world) + domain.axes.indices.map(axis => domain.axes(axis)(world) * local(axis)).sum
      )
    ).toVector

  private def addPoseField(
      kind: SmallStrainPoseKind,
      pivot: Vector[Double],
      parameter: Int,
      x: Double,
      y: Double,
      z: Double,
      output: Array[Double]
  ): Unit =
    val rx = x - pivot(0)
    val ry = y - pivot(1)
    val rz = z - pivot(2)
    parameter match
      case 0 => output(0) += 1.0
      case 1 => output(1) += 1.0
      case 2 => output(2) += 1.0
      case 3 => output(1) -= rz; output(2) += ry
      case 4 => output(0) += rz; output(2) -= rx
      case 5 => output(0) -= ry; output(1) += rx
      case 6 if kind == SmallStrainPoseKind.Affine => output(0) += rx
      case 7 if kind == SmallStrainPoseKind.Affine => output(1) += ry
      case 8 if kind == SmallStrainPoseKind.Affine => output(2) += rz
      case 9 if kind == SmallStrainPoseKind.Affine => output(0) += ry; output(1) += rx
      case 10 if kind == SmallStrainPoseKind.Affine => output(0) += rz; output(2) += rx
      case 11 if kind == SmallStrainPoseKind.Affine => output(1) += rz; output(2) += ry
      case _ => ()

  private[flashalign] def subtractPoseField(
      kind: SmallStrainPoseKind,
      pivot: Vector[Double],
      projection: Vector[Double],
      x: Double,
      y: Double,
      z: Double,
      output: Array[Double]
  ): Unit =
    var parameter = 0
    while parameter < projection.length do
      val field = Array.fill(3)(0.0)
      addPoseField(kind, pivot, parameter, x, y, z, field)
      output(0) -= projection(parameter) * field(0)
      output(1) -= projection(parameter) * field(1)
      output(2) -= projection(parameter) * field(2)
      parameter += 1

  private[flashalign] def subtractPoseGradient(
      kind: SmallStrainPoseKind,
      projection: Vector[Double],
      output: Array[Double]
  ): Unit =
    val projected = poseGradientCombination(kind, projection)
    var index = 0
    while index < 9 do
      output(index) -= projected(index)
      index += 1

  private def poseGradientCombination(kind: SmallStrainPoseKind, coefficients: Vector[Double]): Vector[Double] =
    val output = Array.fill(9)(0.0)
    var parameter = 3
    while parameter < coefficients.length do
      val coefficient = coefficients(parameter)
      parameter match
        case 3 => output(1 * 3 + 2) -= coefficient; output(2 * 3 + 1) += coefficient
        case 4 => output(0 * 3 + 2) += coefficient; output(2 * 3) -= coefficient
        case 5 => output(0 * 3 + 1) -= coefficient; output(1 * 3) += coefficient
        case 6 if kind == SmallStrainPoseKind.Affine => output(0) += coefficient
        case 7 if kind == SmallStrainPoseKind.Affine => output(4) += coefficient
        case 8 if kind == SmallStrainPoseKind.Affine => output(8) += coefficient
        case 9 if kind == SmallStrainPoseKind.Affine => output(1) += coefficient; output(3) += coefficient
        case 10 if kind == SmallStrainPoseKind.Affine => output(2) += coefficient; output(6) += coefficient
        case 11 if kind == SmallStrainPoseKind.Affine => output(5) += coefficient; output(7) += coefficient
        case _ => ()
      parameter += 1
    output.toVector

  private def solve(matrix: DenseOperator, rhs: Vector[Double], relativeTolerance: Double): Either[SmallStrainError, Vector[Double]] =
    val size = matrix.size
    val augmented = Array.tabulate(size)(row => Array.tabulate(size + 1)(column => if column == size then rhs(row) else matrix(row, column)))
    val scale = matrix.rowMajor.map(math.abs).maxOption.getOrElse(0.0)
    val threshold = relativeTolerance * math.max(1.0, scale)
    var column = 0
    while column < size do
      var pivot = column
      var row = column + 1
      while row < size do
        if math.abs(augmented(row)(column)) > math.abs(augmented(pivot)(column)) then pivot = row
        row += 1
      if math.abs(augmented(pivot)(column)) <= threshold then return Left(SmallStrainError.PoseGaugeRankDeficient(size, column))
      val swap = augmented(column)
      augmented(column) = augmented(pivot)
      augmented(pivot) = swap
      row = column + 1
      while row < size do
        val factor = augmented(row)(column) / augmented(column)(column)
        var inner = column
        while inner <= size do
          augmented(row)(inner) -= factor * augmented(column)(inner)
          inner += 1
        row += 1
      column += 1
    val solution = new Array[Double](size)
    var row = size - 1
    while row >= 0 do
      var value = augmented(row)(size)
      var inner = row + 1
      while inner < size do
        value -= augmented(row)(inner) * solution(inner)
        inner += 1
      solution(row) = value / augmented(row)(row)
      row -= 1
    Right(solution.toVector)

  private def matrixRank(matrix: DenseOperator, relativeTolerance: Double): Int =
    val work = Array.tabulate(matrix.size, matrix.size)(matrix.apply)
    val threshold = relativeTolerance * math.max(1.0, matrix.rowMajor.map(math.abs).maxOption.getOrElse(0.0))
    var rank = 0
    var column = 0
    while column < matrix.size && rank < matrix.size do
      var pivot = rank
      var row = rank + 1
      while row < matrix.size do
        if math.abs(work(row)(column)) > math.abs(work(pivot)(column)) then pivot = row
        row += 1
      if math.abs(work(pivot)(column)) > threshold then
        val swap = work(rank); work(rank) = work(pivot); work(pivot) = swap
        row = rank + 1
        while row < matrix.size do
          val factor = work(row)(column) / work(rank)(column)
          var inner = column
          while inner < matrix.size do
            work(row)(inner) -= factor * work(rank)(inner)
            inner += 1
          row += 1
        rank += 1
      column += 1
    rank

  private def spectralNorm3(matrix: Vector[Double]): Double =
    val gram = Array.tabulate(3, 3)((row, column) =>
      (0 until 3).map(axis => matrix(axis * 3 + row) * matrix(axis * 3 + column)).sum
    )
    var vector = Array(1.0, 0.7, -0.3)
    var iteration = 0
    while iteration < 24 do
      val next = Array.tabulate(3)(row => (0 until 3).map(column => gram(row)(column) * vector(column)).sum)
      val norm = math.sqrt(next.map(value => value * value).sum)
      if norm > 0.0 then vector = next.map(_ / norm)
      iteration += 1
    val product = Array.tabulate(3)(row => (0 until 3).map(column => gram(row)(column) * vector(column)).sum)
    math.sqrt(math.max(0.0, vector.indices.map(index => vector(index) * product(index)).sum))

  private def dot(left: Vector[Double], right: Vector[Double]): Double = left.indices.map(index => left(index) * right(index)).sum
  private def norm(value: Vector[Double]): Double = math.sqrt(dot(value, value))

private[flashalign] final case class SmallStrainCoefficientState(
    basisId: String,
    coefficientsMm: Vector[Double]
)

private[flashalign] final case class SmallStrainState3[Pose](
    pose: Pose,
    field: SmallStrainCoefficientState
)

private[flashalign] final case class SmallStrainPriorEvaluation(
    value: Double,
    gradient: Vector[Double],
    curvatureUpper: Vector[Double]
)

private trait SmallStrainPoseAdapter3[Pose, Moving <: Frame[D3], Fixed <: Frame[D3]]:
  def kind: SmallStrainPoseKind
  def moving: Moving
  def fixed: Fixed
  def pivot: Vector[Double]
  def validate(pose: Pose): Either[SmallStrainError, Unit]
  def matrix(pose: Pose): Vector[Double]
  def inverseMatrix(pose: Pose): Vector[Double]
  def propose(pose: Pose, direction: Array[Double]): Either[SmallStrainError, Pose]

private[flashalign] final class SmallStrainWorkspace3 private[flashalign] (
    val modeValue: Array[Double],
    val fieldValue: Array[Double],
    val mapped: Array[Double]
)

private[flashalign] final class SmallStrainModel3[
    Pose,
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
] private (
    private val poseAdapter: SmallStrainPoseAdapter3[Pose, Moving, Fixed],
    val basis: SmallStrainVectorBasis3
):
  val modelId: String = s"flashalign-small-strain-${poseAdapter.kind.id}-v1"
  val basisId: String = basis.id
  val moving: Moving = poseAdapter.moving
  val fixed: Fixed = poseAdapter.fixed
  val poseKind: SmallStrainPoseKind = poseAdapter.kind
  val poseParameterCount: Int = poseKind.parameterCount
  val fieldParameterCount: Int = basis.nominalSize
  val parameterCount: Int = poseParameterCount + fieldParameterCount
  val nominalFieldParameterCount: Int = basis.nominalSize
  val effectiveFieldParameterCount: Int = basis.effectiveRank
  val gaugeConventionId: String = basis.gaugeDescription
  val coefficientUnits: String = basis.units
  val interpretation: String = "geometric strain regularization; not measured tissue mechanics; local expansion and contraction allowed"

  private[flashalign] def poseMatrix(pose: Pose): Vector[Double] = poseAdapter.matrix(pose)
  private[flashalign] def inversePoseMatrix(pose: Pose): Vector[Double] = poseAdapter.inverseMatrix(pose)

  def newWorkspace(): SmallStrainWorkspace3 = new SmallStrainWorkspace3(new Array[Double](3), new Array[Double](3), new Array[Double](3))

  def zeroState(pose: Pose): Either[SmallStrainError, SmallStrainState3[Pose]] =
    basis.coefficientState(Vector.fill(fieldParameterCount)(0.0)).flatMap(field =>
      validateState(SmallStrainState3(pose, field)).map(_ => SmallStrainState3(pose, field))
    )

  def map(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      workspace: SmallStrainWorkspace3
  ): Either[SmallStrainError, Unit] =
    validateState(state).flatMap(_ => validateBatches(points, output)).map { _ =>
      mapUnchecked(state, points, output, workspace)
    }

  def jvp(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      workspace: SmallStrainWorkspace3
  ): Either[SmallStrainError, Unit] =
    if direction.length != parameterCount then Left(SmallStrainError.ParameterCountMismatch(parameterCount, direction.length))
    else if direction.exists(value => !value.isFinite) then Left(SmallStrainError.NonFiniteDirection)
    else validateState(state).flatMap(_ => validateBatches(points, output)).map { _ =>
      jvpUnchecked(state, points, direction, output, workspace)
    }

  def vjp(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: SmallStrainWorkspace3
  ): Either[SmallStrainError, Unit] =
    if output.length != parameterCount then Left(SmallStrainError.ParameterCountMismatch(parameterCount, output.length))
    else if !forces.frame.sameRuntimeOwnerAs(fixed) || forces.size != points.size then Left(SmallStrainError.FrameOrPointMismatch)
    else validateState(state).flatMap(_ => validatePointOwner(points)).map { _ =>
      vjpUnchecked(state, points, forces, output, workspace)
    }

  def propose(state: SmallStrainState3[Pose], direction: Array[Double]): Either[SmallStrainError, (SmallStrainState3[Pose], Array[Double])] =
    if direction.length != parameterCount then Left(SmallStrainError.ParameterCountMismatch(parameterCount, direction.length))
    else if direction.exists(value => !value.isFinite) then Left(SmallStrainError.NonFiniteDirection)
    else for
      _ <- validateState(state)
      pose <- poseAdapter.propose(state.pose, direction.take(poseParameterCount))
      field <- basis.coefficientState(Vector.tabulate(fieldParameterCount)(index =>
        state.field.coefficientsMm(index) + direction(poseParameterCount + index)
      ))
      candidate = SmallStrainState3(pose, field)
      _ <- validateState(candidate)
    yield candidate -> direction.clone()

  def prior(state: SmallStrainState3[Pose]): Either[SmallStrainError, SmallStrainPriorEvaluation] =
    validateState(state).map { _ =>
      val coefficients = state.field.coefficientsMm
      val fieldGradient = Vector.tabulate(fieldParameterCount)(row =>
        Vector.tabulate(fieldParameterCount)(column => basis.priorPrecision(row, column) * coefficients(column)).sum
      )
      val fullGradient = Vector.fill(poseParameterCount)(0.0) ++ fieldGradient
      val upper = Array.fill(PackedSymmetric.size(parameterCount))(0.0)
      var row = 0
      while row < fieldParameterCount do
        var column = row
        while column < fieldParameterCount do
          upper(PackedSymmetric.index(poseParameterCount + row, poseParameterCount + column)) = basis.priorPrecision(row, column)
          column += 1
        row += 1
      SmallStrainPriorEvaluation(0.5 * coefficients.indices.map(index => coefficients(index) * fieldGradient(index)).sum, fullGradient, upper.toVector)
    }

  private[flashalign] def validateState(state: SmallStrainState3[Pose]): Either[SmallStrainError, Unit] =
    poseAdapter.validate(state.pose).flatMap(_ => basis.requireState(state.field))

  private def validatePointOwner(points: WorldPointBatch3[Moving]): Either[SmallStrainError, Unit] =
    if points.frame.sameRuntimeOwnerAs(moving) then Right(()) else Left(SmallStrainError.FrameOrPointMismatch)

  private def validateBatches(points: WorldPointBatch3[Moving], output: GeometryOutputBuffer3[Fixed]): Either[SmallStrainError, Unit] =
    if !points.frame.sameRuntimeOwnerAs(moving) || !output.frame.sameRuntimeOwnerAs(fixed) || points.size != output.size then
      Left(SmallStrainError.FrameOrPointMismatch)
    else Right(())

  private[flashalign] def mapUnchecked(state: SmallStrainState3[Pose], points: WorldPointBatch3[Moving], output: GeometryOutputBuffer3[Fixed], workspace: SmallStrainWorkspace3): Unit =
    val matrix = poseAdapter.matrix(state.pose)
    var point = 0
    while point < points.size do
      val offset = point * 3
      fieldAt(state.field.coefficientsMm, points.packed, offset, workspace.fieldValue, workspace.modeValue)
      applyAffine(matrix, points.packed(offset) + workspace.fieldValue(0), points.packed(offset + 1) + workspace.fieldValue(1), points.packed(offset + 2) + workspace.fieldValue(2), workspace.mapped)
      output.packed(offset) = workspace.mapped(0)
      output.packed(offset + 1) = workspace.mapped(1)
      output.packed(offset + 2) = workspace.mapped(2)
      point += 1

  private[flashalign] def jvpUnchecked(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      workspace: SmallStrainWorkspace3
  ): Unit =
    val matrix = poseAdapter.matrix(state.pose)
    var point = 0
    while point < points.size do
      fieldAt(state.field.coefficientsMm, points.packed, point * 3, workspace.fieldValue, workspace.modeValue)
      val offset = point * 3
      val qx = points.packed(offset) + workspace.fieldValue(0)
      val qy = points.packed(offset + 1) + workspace.fieldValue(1)
      val qz = points.packed(offset + 2) + workspace.fieldValue(2)
      applyAffine(matrix, qx, qy, qz, workspace.mapped)
      poseJvp(workspace.mapped, direction, output.packed, offset)
      fieldDirectionAt(direction, points.packed, offset, workspace.fieldValue, workspace.modeValue)
      output.packed(offset) += matrix(0) * workspace.fieldValue(0) + matrix(1) * workspace.fieldValue(1) + matrix(2) * workspace.fieldValue(2)
      output.packed(offset + 1) += matrix(4) * workspace.fieldValue(0) + matrix(5) * workspace.fieldValue(1) + matrix(6) * workspace.fieldValue(2)
      output.packed(offset + 2) += matrix(8) * workspace.fieldValue(0) + matrix(9) * workspace.fieldValue(1) + matrix(10) * workspace.fieldValue(2)
      point += 1

  private[flashalign] def vjpUnchecked(
      state: SmallStrainState3[Pose],
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: SmallStrainWorkspace3
  ): Unit =
    java.util.Arrays.fill(output, 0.0)
    val matrix = poseAdapter.matrix(state.pose)
    var point = 0
    while point < points.size do
      val offset = point * 3
      fieldAt(state.field.coefficientsMm, points.packed, offset, workspace.fieldValue, workspace.modeValue)
      applyAffine(
        matrix,
        points.packed(offset) + workspace.fieldValue(0),
        points.packed(offset + 1) + workspace.fieldValue(1),
        points.packed(offset + 2) + workspace.fieldValue(2),
        workspace.mapped
      )
      poseVjp(workspace.mapped, forces.packed, offset, output)
      val sourceForceX = matrix(0) * forces.packed(offset) + matrix(4) * forces.packed(offset + 1) + matrix(8) * forces.packed(offset + 2)
      val sourceForceY = matrix(1) * forces.packed(offset) + matrix(5) * forces.packed(offset + 1) + matrix(9) * forces.packed(offset + 2)
      val sourceForceZ = matrix(2) * forces.packed(offset) + matrix(6) * forces.packed(offset + 1) + matrix(10) * forces.packed(offset + 2)
      var mode = 0
      while mode < fieldParameterCount do
        basis.modeValue(mode, points.packed(offset), points.packed(offset + 1), points.packed(offset + 2), workspace.modeValue)
        output(poseParameterCount + mode) += workspace.modeValue(0) * sourceForceX + workspace.modeValue(1) * sourceForceY + workspace.modeValue(2) * sourceForceZ
        mode += 1
      point += 1

  private[flashalign] def fieldAt(coefficients: Vector[Double], packed: Array[Double], offset: Int, output: Array[Double], modeValue: Array[Double]): Unit =
    java.util.Arrays.fill(output, 0.0)
    var mode = 0
    while mode < fieldParameterCount do
      basis.modeValue(mode, packed(offset), packed(offset + 1), packed(offset + 2), modeValue)
      output(0) += coefficients(mode) * modeValue(0)
      output(1) += coefficients(mode) * modeValue(1)
      output(2) += coefficients(mode) * modeValue(2)
      mode += 1

  private def fieldDirectionAt(direction: Array[Double], packed: Array[Double], offset: Int, output: Array[Double], modeValue: Array[Double]): Unit =
    java.util.Arrays.fill(output, 0.0)
    var mode = 0
    while mode < fieldParameterCount do
      basis.modeValue(mode, packed(offset), packed(offset + 1), packed(offset + 2), modeValue)
      val coefficient = direction(poseParameterCount + mode)
      output(0) += coefficient * modeValue(0)
      output(1) += coefficient * modeValue(1)
      output(2) += coefficient * modeValue(2)
      mode += 1

  private def poseJvp(mapped: Array[Double], direction: Array[Double], output: Array[Double], offset: Int): Unit =
    val rx = mapped(0) - poseAdapter.pivot(0)
    val ry = mapped(1) - poseAdapter.pivot(1)
    val rz = mapped(2) - poseAdapter.pivot(2)
    output(offset) = direction(0) + direction(4) * rz - direction(5) * ry
    output(offset + 1) = direction(1) + direction(5) * rx - direction(3) * rz
    output(offset + 2) = direction(2) + direction(3) * ry - direction(4) * rx
    if poseKind == SmallStrainPoseKind.Affine then
      output(offset) += direction(6) * rx + direction(9) * ry + direction(10) * rz
      output(offset + 1) += direction(9) * rx + direction(7) * ry + direction(11) * rz
      output(offset + 2) += direction(10) * rx + direction(11) * ry + direction(8) * rz

  private def poseVjp(mapped: Array[Double], forces: Array[Double], offset: Int, output: Array[Double]): Unit =
    val rx = mapped(0) - poseAdapter.pivot(0)
    val ry = mapped(1) - poseAdapter.pivot(1)
    val rz = mapped(2) - poseAdapter.pivot(2)
    val fx = forces(offset); val fy = forces(offset + 1); val fz = forces(offset + 2)
    output(0) += fx; output(1) += fy; output(2) += fz
    output(3) += ry * fz - rz * fy
    output(4) += rz * fx - rx * fz
    output(5) += rx * fy - ry * fx
    if poseKind == SmallStrainPoseKind.Affine then
      output(6) += rx * fx; output(7) += ry * fy; output(8) += rz * fz
      output(9) += ry * fx + rx * fy
      output(10) += rz * fx + rx * fz
      output(11) += rz * fy + ry * fz

  private def applyAffine(matrix: Vector[Double], x: Double, y: Double, z: Double, output: Array[Double]): Unit =
    output(0) = matrix(0) * x + matrix(1) * y + matrix(2) * z + matrix(3)
    output(1) = matrix(4) * x + matrix(5) * y + matrix(6) * z + matrix(7)
    output(2) = matrix(8) * x + matrix(9) * y + matrix(10) * z + matrix(11)

private[flashalign] object SmallStrainModel3:
  def rigid[Moving <: Frame[D3], Fixed <: Frame[D3]](
      pose: RigidModel3[Moving, Fixed],
      basis: SmallStrainVectorBasis3
  ): Either[SmallStrainError, SmallStrainModel3[Rigid3[Moving, Fixed], Moving, Fixed]] =
    if basis.poseKind != SmallStrainPoseKind.Rigid then Left(SmallStrainError.PoseKindMismatch(SmallStrainPoseKind.Rigid, basis.poseKind))
    else Right(new SmallStrainModel3(new RigidSmallStrainPoseAdapter3(pose), basis))

  def affine[Moving <: Frame[D3], Fixed <: Frame[D3]](
      pose: AffineModel3[Moving, Fixed],
      basis: SmallStrainVectorBasis3
  ): Either[SmallStrainError, SmallStrainModel3[FramedAffine[Moving, Fixed, D3], Moving, Fixed]] =
    if basis.poseKind != SmallStrainPoseKind.Affine then Left(SmallStrainError.PoseKindMismatch(SmallStrainPoseKind.Affine, basis.poseKind))
    else Right(new SmallStrainModel3(new AffineSmallStrainPoseAdapter3(pose), basis))

private final class RigidSmallStrainPoseAdapter3[Moving <: Frame[D3], Fixed <: Frame[D3]](
    model: RigidModel3[Moving, Fixed]
) extends SmallStrainPoseAdapter3[Rigid3[Moving, Fixed], Moving, Fixed]:
  val kind = SmallStrainPoseKind.Rigid
  val moving: Moving = model.moving
  val fixed: Fixed = model.fixed
  val pivot: Vector[Double] = Vector(model.pivotX, model.pivotY, model.pivotZ)
  def validate(pose: Rigid3[Moving, Fixed]): Either[SmallStrainError, Unit] = model.validateMovingToFixed(pose).left.map(error => SmallStrainError.Pose(error.message))
  def matrix(pose: Rigid3[Moving, Fixed]): Vector[Double] = pose.operator.rowMajor
  def inverseMatrix(pose: Rigid3[Moving, Fixed]): Vector[Double] = pose.inverse.operator.rowMajor
  def propose(pose: Rigid3[Moving, Fixed], direction: Array[Double]): Either[SmallStrainError, Rigid3[Moving, Fixed]] =
    model.propose(pose, direction).left.map(error => SmallStrainError.Pose(error.message))

private final class AffineSmallStrainPoseAdapter3[Moving <: Frame[D3], Fixed <: Frame[D3]](
    model: AffineModel3[Moving, Fixed]
) extends SmallStrainPoseAdapter3[FramedAffine[Moving, Fixed, D3], Moving, Fixed]:
  val kind = SmallStrainPoseKind.Affine
  val moving: Moving = model.moving
  val fixed: Fixed = model.fixed
  val pivot: Vector[Double] = Vector(model.pivotX, model.pivotY, model.pivotZ)
  def validate(pose: FramedAffine[Moving, Fixed, D3]): Either[SmallStrainError, Unit] = model.validateMovingToFixed(pose).left.map(error => SmallStrainError.Pose(error.message))
  def matrix(pose: FramedAffine[Moving, Fixed, D3]): Vector[Double] = pose.operator.rowMajor
  def inverseMatrix(pose: FramedAffine[Moving, Fixed, D3]): Vector[Double] = pose.inverse.operator.rowMajor
  def propose(pose: FramedAffine[Moving, Fixed, D3], direction: Array[Double]): Either[SmallStrainError, FramedAffine[Moving, Fixed, D3]] =
    model.propose(pose, direction).left.map(error => SmallStrainError.Pose(error.message))

private[flashalign] sealed trait SmallStrainError derives CanEqual:
  def message: String

private[flashalign] object SmallStrainError:
  final case class InvalidConfig(detail: String) extends SmallStrainError:
    val message = s"invalid small-strain configuration: $detail"
  final case class GaugeIdentityMismatch(expected: String, actual: String) extends SmallStrainError:
    val message = s"small-strain gauge identity mismatch: expected $expected, got $actual"
  final case class CoefficientCountOutsideRange(actual: Int, minimum: Int, maximum: Int) extends SmallStrainError:
    val message = s"small-strain basis has $actual coefficients outside [$minimum, $maximum]"
  final case class PoseGaugeRankDeficient(expected: Int, actual: Int) extends SmallStrainError:
    val message = s"pose gauge has rank $actual of $expected"
  final case class VectorGaugeRankDeficient(expected: Int, actual: Int) extends SmallStrainError:
    val message = s"projected vector basis has rank $actual of $expected"
  final case class ParameterCountMismatch(expected: Int, actual: Int) extends SmallStrainError:
    val message = s"small-strain model requires $expected parameters, got $actual"
  case object NonFiniteCoefficients extends SmallStrainError:
    val message = "small-strain coefficients must be finite millimetres"
  case object NonFiniteDirection extends SmallStrainError:
    val message = "small-strain direction must be finite"
  final case class BasisIdentityMismatch(expected: String, actual: String) extends SmallStrainError:
    val message = s"small-strain basis identity mismatch: expected $expected, got $actual"
  final case class PoseKindMismatch(expected: SmallStrainPoseKind, actual: SmallStrainPoseKind) extends SmallStrainError:
    val message = s"small-strain pose kind mismatch: expected $expected, got $actual"
  final case class Pose(detail: String) extends SmallStrainError:
    val message = detail
  case object FrameOrPointMismatch extends SmallStrainError:
    val message = "small-strain point/vector/output frame or count mismatch"
