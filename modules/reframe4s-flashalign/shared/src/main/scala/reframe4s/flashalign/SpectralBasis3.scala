package reframe4s.flashalign

private[flashalign] final case class IntegerWave3(i: Int, j: Int, k: Int):
  def squaredFrequency: Int = i * i + j * j + k * k
  def negate: IntegerWave3 = IntegerWave3(-i, -j, -k)

private[flashalign] object IntegerWave3:
  def canonical(value: IntegerWave3): Either[SpectralBasisError, IntegerWave3] =
    if value.i == 0 && value.j == 0 && value.k == 0 then Left(SpectralBasisError.ZeroWavevector)
    else
      val first = Vector(value.i, value.j, value.k).find(_ != 0).get
      Right(if first > 0 then value else value.negate)

  def canonicalDistinct(values: Vector[IntegerWave3]): Either[SpectralBasisError, Vector[IntegerWave3]] =
    values.foldLeft[Either[SpectralBasisError, Set[IntegerWave3]]](Right(Set.empty)) {
      case (acc, value) => acc.flatMap(set => canonical(value).map(set + _))
    }.map(_.toVector.sortBy(value => (value.squaredFrequency, value.i, value.j, value.k)))

private[flashalign] enum RealSpectralPhase(val id: String) derives CanEqual:
  case Cosine extends RealSpectralPhase("cos")
  case Sine extends RealSpectralPhase("sin")

private[flashalign] final case class PhysicalSpectralDomain3(
    originMm: Vector[Double],
    axes: Vector[Vector[Double]],
    periodsMm: Vector[Double],
    paddingMm: Vector[Double],
    extensionId: String
)

private[flashalign] object PhysicalSpectralDomain3:
  def create(
      originMm: Vector[Double],
      axes: Vector[Vector[Double]],
      periodsMm: Vector[Double],
      paddingMm: Vector[Double],
      extensionId: String,
      tolerance: Double = 1e-10
  ): Either[SpectralBasisError, PhysicalSpectralDomain3] =
    val vectors = (originMm +: axes) ++ Vector(periodsMm, paddingMm)
    if vectors.exists(_.length != 3) || axes.length != 3 then
      Left(SpectralBasisError.InvalidDomain("all physical vectors must have length three"))
    else if vectors.flatten.exists(value => !value.isFinite) then
      Left(SpectralBasisError.InvalidDomain("domain values must be finite"))
    else if periodsMm.exists(_ <= 0.0) || paddingMm.exists(_ < 0.0) then
      Left(SpectralBasisError.InvalidDomain("periods must be positive and padding nonnegative"))
    else if extensionId.trim.isEmpty then Left(SpectralBasisError.InvalidDomain("extension ID is empty"))
    else if !orthonormal(axes, tolerance) then
      Left(SpectralBasisError.InvalidDomain("physical axes must be orthonormal"))
    else Right(PhysicalSpectralDomain3(originMm, axes, periodsMm, paddingMm, extensionId))

  private def orthonormal(axes: Vector[Vector[Double]], tolerance: Double): Boolean =
    axes.indices.forall { row =>
      axes.indices.forall { column =>
        val expected = if row == column then 1.0 else 0.0
        math.abs(dot(axes(row), axes(column)) - expected) <= tolerance
      }
    }

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

private[flashalign] final case class GeometryGaugeMeasure3(
    id: String,
    pointsWorldMm: Vector[Vector[Double]],
    normalizedWeights: Vector[Double]
)

private[flashalign] object GeometryGaugeMeasure3:
  def create(
      id: String,
      pointsWorldMm: Vector[Vector[Double]],
      weights: Vector[Double]
  ): Either[SpectralBasisError, GeometryGaugeMeasure3] =
    if id.trim.isEmpty then Left(SpectralBasisError.InvalidGauge("gauge ID is empty"))
    else if pointsWorldMm.isEmpty || pointsWorldMm.length != weights.length then
      Left(SpectralBasisError.InvalidGauge("gauge points and weights must be nonempty and aligned"))
    else if pointsWorldMm.exists(_.length != 3) || pointsWorldMm.flatten.exists(value => !value.isFinite) then
      Left(SpectralBasisError.InvalidGauge("gauge points must be finite world-mm triples"))
    else if weights.exists(weight => !weight.isFinite || weight <= 0.0) then
      Left(SpectralBasisError.InvalidGauge("gauge weights must be finite and positive"))
    else
      val total = weights.sum
      if !total.isFinite || total <= 0.0 then Left(SpectralBasisError.InvalidGauge("invalid weight sum"))
      else Right(GeometryGaugeMeasure3(id, pointsWorldMm, weights.map(_ / total)))

private[flashalign] final case class SpectralMode3(
    id: String,
    wave: IntegerWave3,
    phase: RealSpectralPhase,
    angularWaveWorldPerMm: Vector[Double],
    gaugeMean: Double,
    gradientBoundPerMm: Double
)

private[flashalign] final case class DenseOperator(
    size: Int,
    rowMajor: Vector[Double]
):
  def apply(row: Int, column: Int): Double = rowMajor(row * size + column)

  def quadratic(coefficients: Vector[Double]): Either[SpectralBasisError, Double] =
    if coefficients.length != size then
      Left(SpectralBasisError.CoefficientCountMismatch(size, coefficients.length))
    else
      Right(Vector.tabulate(size)(row =>
        coefficients(row) * Vector.tabulate(size)(column => apply(row, column) * coefficients(column)).sum
      ).sum)

private[flashalign] object DenseOperator:
  def tabulate(size: Int)(value: (Int, Int) => Double): DenseOperator =
    DenseOperator(size, Vector.tabulate(size * size)(index => value(index / size, index % size)))

  def congruence(
      oldPrecision: DenseOperator,
      oldFromNew: Vector[Vector[Double]]
  ): Either[SpectralBasisError, DenseOperator] =
    val oldSize = oldPrecision.size
    if oldFromNew.length != oldSize || oldFromNew.exists(_.length != oldFromNew.headOption.fold(0)(_.length)) then
      Left(SpectralBasisError.InvalidTransport("transport rows must match the old basis"))
    else
      val newSize = oldFromNew.headOption.fold(0)(_.length)
      Right(DenseOperator.tabulate(newSize) { (row, column) =>
        Vector.tabulate(oldSize)(left =>
          Vector.tabulate(oldSize)(right =>
            oldFromNew(left)(row) * oldPrecision(left, right) * oldFromNew(right)(column)
          ).sum
        ).sum
      })

private[flashalign] final case class SpectralPriorWeights(
    magnitude: Double,
    gradient: Double,
    bending: Double
)

private[flashalign] final case class PhysicalSpectralBasis3(
    id: String,
    domain: PhysicalSpectralDomain3,
    gaugeId: String,
    modes: Vector[SpectralMode3],
    massGram: DenseOperator,
    gradientGram: DenseOperator,
    bendingGram: DenseOperator,
    gaugeEffectiveRank: Int
):
  def nominalSize: Int = modes.length

  def values(pointWorldMm: Vector[Double]): Either[SpectralBasisError, Vector[Double]] =
    validatePoint(pointWorldMm).map(point => modes.map(mode => rawValue(mode, point) - mode.gaugeMean))

  def gradients(pointWorldMm: Vector[Double]): Either[SpectralBasisError, Vector[Vector[Double]]] =
    validatePoint(pointWorldMm).map(point => modes.map(mode => rawGradient(mode, point)))

  def precision(weights: SpectralPriorWeights): Either[SpectralBasisError, DenseOperator] =
    if !weights.magnitude.isFinite || weights.magnitude <= 0.0 ||
        !weights.gradient.isFinite || weights.gradient < 0.0 ||
        !weights.bending.isFinite || weights.bending < 0.0
    then Left(SpectralBasisError.InvalidPrior("magnitude must be positive; derivative weights nonnegative"))
    else
      Right(DenseOperator.tabulate(nominalSize) { (row, column) =>
        weights.magnitude * massGram(row, column) +
          weights.gradient * gradientGram(row, column) +
          weights.bending * bendingGram(row, column)
      })

  def coefficientState(coefficientsMm: Vector[Double]): Either[SpectralBasisError, SpectralCoefficientState] =
    if coefficientsMm.length != nominalSize then
      Left(SpectralBasisError.CoefficientCountMismatch(nominalSize, coefficientsMm.length))
    else if coefficientsMm.exists(value => !value.isFinite) then
      Left(SpectralBasisError.InvalidCoefficients("coefficients must be finite millimetres"))
    else Right(SpectralCoefficientState(id, coefficientsMm))

  def requireState(state: SpectralCoefficientState): Either[SpectralBasisError, Vector[Double]] =
    if state.basisId != id then Left(SpectralBasisError.BasisIdentityMismatch(id, state.basisId))
    else if state.coefficientsMm.length != nominalSize then
      Left(SpectralBasisError.CoefficientCountMismatch(nominalSize, state.coefficientsMm.length))
    else Right(state.coefficientsMm)

  private def validatePoint(point: Vector[Double]): Either[SpectralBasisError, Vector[Double]] =
    if point.length != 3 || point.exists(value => !value.isFinite) then
      Left(SpectralBasisError.InvalidPoint)
    else Right(point)

  private def phase(mode: SpectralMode3, point: Vector[Double]): Double =
    dot(mode.angularWaveWorldPerMm, subtract(point, domain.originMm))

  private def rawValue(mode: SpectralMode3, point: Vector[Double]): Double =
    val angle = phase(mode, point)
    val base = mode.phase match
      case RealSpectralPhase.Cosine => math.cos(angle)
      case RealSpectralPhase.Sine   => math.sin(angle)
    math.sqrt(2.0) * base

  private def rawGradient(mode: SpectralMode3, point: Vector[Double]): Vector[Double] =
    val angle = phase(mode, point)
    val scale = mode.phase match
      case RealSpectralPhase.Cosine => -math.sqrt(2.0) * math.sin(angle)
      case RealSpectralPhase.Sine   => math.sqrt(2.0) * math.cos(angle)
    mode.angularWaveWorldPerMm.map(_ * scale)

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

  private def subtract(left: Vector[Double], right: Vector[Double]): Vector[Double] =
    left.indices.map(index => left(index) - right(index)).toVector

private[flashalign] final case class SpectralCoefficientState(
    basisId: String,
    coefficientsMm: Vector[Double]
)

private[flashalign] object PhysicalSpectralBasis3:
  val Revision = "physical-real-trigonometric-v1"

  def lowFrequency(
      domain: PhysicalSpectralDomain3,
      maximumIntegerFrequency: Int,
      gauge: GeometryGaugeMeasure3,
      rankTolerance: Double = 1e-10
  ): Either[SpectralBasisError, PhysicalSpectralBasis3] =
    if maximumIntegerFrequency < 1 then
      Left(SpectralBasisError.InvalidFrequencyLimit(maximumIntegerFrequency))
    else
      val waves = (for
        i <- -maximumIntegerFrequency to maximumIntegerFrequency
        j <- -maximumIntegerFrequency to maximumIntegerFrequency
        k <- -maximumIntegerFrequency to maximumIntegerFrequency
        if i != 0 || j != 0 || k != 0
        if i * i + j * j + k * k <= maximumIntegerFrequency * maximumIntegerFrequency
      yield IntegerWave3(i, j, k)).toVector
      fromWavevectors(domain, waves, gauge, rankTolerance)

  def fromWavevectors(
      domain: PhysicalSpectralDomain3,
      requested: Vector[IntegerWave3],
      gauge: GeometryGaugeMeasure3,
      rankTolerance: Double = 1e-10
  ): Either[SpectralBasisError, PhysicalSpectralBasis3] =
    IntegerWave3.canonicalDistinct(requested).flatMap { waves =>
      if waves.isEmpty then Left(SpectralBasisError.InvalidFrequencyLimit(0))
      else
        val ungauged = waves.flatMap(wave =>
          Vector(RealSpectralPhase.Cosine, RealSpectralPhase.Sine).map(phase =>
            val angular = angularWave(domain, wave)
            SpectralMode3(
              id = s"${wave.i},${wave.j},${wave.k}:${phase.id}",
              wave = wave,
              phase = phase,
              angularWaveWorldPerMm = angular,
              gaugeMean = 0.0,
              gradientBoundPerMm = math.sqrt(2.0) * norm(angular)
            )
          )
        )
        val means = ungauged.map(mode =>
          gauge.pointsWorldMm.zip(gauge.normalizedWeights).map { case (point, weight) =>
            weight * rawValue(domain, mode, point)
          }.sum
        )
        val modes = ungauged.zip(means).map { case (mode, mean) => mode.copy(gaugeMean = mean) }
        val gaugeGram = DenseOperator.tabulate(modes.length) { (row, column) =>
          gauge.pointsWorldMm.zip(gauge.normalizedWeights).map { case (point, weight) =>
            val left = rawValue(domain, modes(row), point) - modes(row).gaugeMean
            val right = rawValue(domain, modes(column), point) - modes(column).gaugeMean
            weight * left * right
          }.sum
        }
        val effectiveRank = matrixRank(gaugeGram, rankTolerance)
        if effectiveRank < modes.length then
          Left(SpectralBasisError.GaugeRankDeficient(modes.length, effectiveRank))
        else
          val mass = DenseOperator.tabulate(modes.length) { (row, column) =>
            val base = if row == column then 1.0 else 0.0
            base + modes(row).gaugeMean * modes(column).gaugeMean
          }
          val gradient = diagonal(modes.map(mode => squaredNorm(mode.angularWaveWorldPerMm)))
          val bending = diagonal(modes.map(mode =>
            val squared = squaredNorm(mode.angularWaveWorldPerMm)
            squared * squared
          ))
          val id = basisId(domain, gauge.id, modes)
          Right(
            PhysicalSpectralBasis3(
              id,
              domain,
              gauge.id,
              modes,
              mass,
              gradient,
              bending,
              effectiveRank
            )
          )
    }

  private def angularWave(domain: PhysicalSpectralDomain3, wave: IntegerWave3): Vector[Double] =
    val integers = Vector(wave.i, wave.j, wave.k)
    Vector.tabulate(3)(worldAxis =>
      Vector.tabulate(3)(basisAxis =>
        2.0 * math.Pi * integers(basisAxis).toDouble /
          domain.periodsMm(basisAxis) * domain.axes(basisAxis)(worldAxis)
      ).sum
    )

  private def rawValue(
      domain: PhysicalSpectralDomain3,
      mode: SpectralMode3,
      point: Vector[Double]
  ): Double =
    val offset = point.indices.map(index => point(index) - domain.originMm(index)).toVector
    val angle = dot(mode.angularWaveWorldPerMm, offset)
    val base = mode.phase match
      case RealSpectralPhase.Cosine => math.cos(angle)
      case RealSpectralPhase.Sine   => math.sin(angle)
    math.sqrt(2.0) * base

  private def diagonal(values: Vector[Double]): DenseOperator =
    DenseOperator.tabulate(values.length)((row, column) => if row == column then values(row) else 0.0)

  private def matrixRank(matrix: DenseOperator, relativeTolerance: Double): Int =
    val work = Array.tabulate(matrix.size, matrix.size)(matrix.apply)
    val scale = matrix.rowMajor.map(math.abs).maxOption.getOrElse(0.0)
    val threshold = relativeTolerance * math.max(1.0, scale)
    var rank = 0
    var column = 0
    while column < matrix.size && rank < matrix.size do
      var pivot = rank
      var candidate = rank + 1
      while candidate < matrix.size do
        if math.abs(work(candidate)(column)) > math.abs(work(pivot)(column)) then pivot = candidate
        candidate += 1
      if math.abs(work(pivot)(column)) > threshold then
        val swap = work(rank)
        work(rank) = work(pivot)
        work(pivot) = swap
        candidate = rank + 1
        while candidate < matrix.size do
          val factor = work(candidate)(column) / work(rank)(column)
          var inner = column
          while inner < matrix.size do
            work(candidate)(inner) -= factor * work(rank)(inner)
            inner += 1
          candidate += 1
        rank += 1
      column += 1
    rank

  private def basisId(
      domain: PhysicalSpectralDomain3,
      gaugeId: String,
      modes: Vector[SpectralMode3]
  ): String =
    val geometry =
      (domain.originMm ++ domain.axes.flatten ++ domain.periodsMm ++ domain.paddingMm)
        .map(java.lang.Double.toHexString)
        .mkString(",")
    s"$Revision|$geometry|${domain.extensionId}|$gaugeId|${modes.map(_.id).mkString(";")}"

  private def squaredNorm(value: Vector[Double]): Double = value.map(component => component * component).sum
  private def norm(value: Vector[Double]): Double = math.sqrt(squaredNorm(value))
  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

private[flashalign] sealed trait SpectralBasisError derives CanEqual:
  def message: String

private[flashalign] object SpectralBasisError:
  case object ZeroWavevector extends SpectralBasisError:
    val message = "the zero wavevector is not a deformation mode"
  final case class InvalidDomain(detail: String) extends SpectralBasisError:
    val message = s"invalid physical spectral domain: $detail"
  final case class InvalidGauge(detail: String) extends SpectralBasisError:
    val message = s"invalid fixed-geometry gauge: $detail"
  final case class InvalidFrequencyLimit(value: Int) extends SpectralBasisError:
    val message = s"invalid maximum integer frequency $value"
  final case class GaugeRankDeficient(nominal: Int, effective: Int) extends SpectralBasisError:
    val message = s"fixed geometry supports rank $effective of $nominal spectral modes"
  final case class CoefficientCountMismatch(expected: Int, actual: Int) extends SpectralBasisError:
    val message = s"basis requires $expected coefficients, got $actual"
  final case class BasisIdentityMismatch(expected: String, actual: String) extends SpectralBasisError:
    val message = s"basis identity mismatch: expected $expected, got $actual"
  final case class InvalidCoefficients(detail: String) extends SpectralBasisError:
    val message = s"invalid spectral coefficients: $detail"
  final case class InvalidPrior(detail: String) extends SpectralBasisError:
    val message = s"invalid spectral prior: $detail"
  final case class InvalidTransport(detail: String) extends SpectralBasisError:
    val message = s"invalid basis transport: $detail"
  case object InvalidPoint extends SpectralBasisError:
    val message = "basis evaluation requires a finite world-mm point"
