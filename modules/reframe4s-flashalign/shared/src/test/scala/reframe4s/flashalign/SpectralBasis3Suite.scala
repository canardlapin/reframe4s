package reframe4s.flashalign

final class SpectralBasis3Suite extends munit.FunSuite:
  test("wavevector representatives remove plus-minus duplicates in stable order"):
    val actual = IntegerWave3
      .canonicalDistinct(
        Vector(
          IntegerWave3(0, -1, 0),
          IntegerWave3(1, 0, 0),
          IntegerWave3(0, 1, 0),
          IntegerWave3(-1, 0, 0),
          IntegerWave3(1, 1, 0)
        )
      )
      .fold(error => fail(error.message), identity)
    assertEquals(
      actual,
      Vector(IntegerWave3(0, 1, 0), IntegerWave3(1, 0, 0), IntegerWave3(1, 1, 0))
    )
    IntegerWave3.canonical(IntegerWave3(0, 0, 0)) match
      case Left(SpectralBasisError.ZeroWavevector) => ()
      case other => fail(s"expected zero-mode rejection, got $other")

  test("physical periodic quadrature agrees with analytic mass gradient and bending priors"):
    val basis = fixtureBasis(1)
    assertEquals(basis.nominalSize, 6)
    assertEquals(basis.gaugeEffectiveRank, 6)
    basis.modes.foreach(mode => assertEqualsDouble(mode.gaugeMean, 0.0, 3e-16))
    val weights = SpectralPriorWeights(0.7, 1.3, 0.09)
    val precision = basis.precision(weights).fold(error => fail(error.message), identity)
    val coefficients = Vector(0.3, -0.7, 0.2, 0.4, -0.1, 0.6)
    val analytic = precision.quadratic(coefficients).fold(error => fail(error.message), identity)
    val quadrature = independentPriorQuadrature(basis, coefficients, weights)
    assertEqualsDouble(analytic, quadrature, 2e-12)
    basis.modes.indices.foreach { row =>
      basis.modes.indices.foreach { column =>
        val expectedMass = if row == column then 1.0 else 0.0
        val expectedGradient =
          if row == column then squaredNorm(basis.modes(row).angularWaveWorldPerMm) else 0.0
        val expectedBending = if row == column then expectedGradient * expectedGradient else 0.0
        assertEqualsDouble(basis.massGram(row, column), expectedMass, 1e-14)
        assertEqualsDouble(basis.gradientGram(row, column), expectedGradient, 1e-14)
        assertEqualsDouble(basis.bendingGram(row, column), expectedBending, 1e-12)
      }
    }

  test("geometry gauge is fixed and removes weighted means without inlier state"):
    val domain = fixtureDomain(Vector(12.0, 14.0, 16.0))
    val points = Vector(
      Vector(0.0, 0.0, 0.0),
      Vector(1.0, 2.0, 3.0),
      Vector(4.0, -1.0, 6.0),
      Vector(-2.0, 3.0, 5.0),
      Vector(5.0, 7.0, -3.0),
      Vector(8.0, 1.0, 9.0),
      Vector(-4.0, 6.0, 2.0),
      Vector(9.0, -5.0, 4.0)
    )
    val gauge = GeometryGaugeMeasure3
      .create("fixed-source-geometry-v1", points, Vector(1.0, 2.0, 1.0, 3.0, 2.0, 4.0, 1.0, 2.0))
      .fold(error => fail(error.message), identity)
    val basis = PhysicalSpectralBasis3
      .fromWavevectors(
        domain,
        Vector(IntegerWave3(1, 0, 0), IntegerWave3(0, 1, 0)),
        gauge
      )
      .fold(error => fail(error.message), identity)
    basis.modes.indices.foreach { mode =>
      val weightedMean = points.zip(gauge.normalizedWeights).map { case (point, weight) =>
        weight * basis.values(point).fold(error => fail(error.message), identity)(mode)
      }.sum
      assertEqualsDouble(weightedMean, 0.0, 2e-16)
    }

  test("rank deficiency and basis identity changes fail explicitly"):
    val domain = fixtureDomain(Vector(12.0, 14.0, 16.0))
    val onePoint = GeometryGaugeMeasure3
      .create("one-point", Vector(Vector(0.0, 0.0, 0.0)), Vector(1.0))
      .fold(error => fail(error.message), identity)
    PhysicalSpectralBasis3.fromWavevectors(
      domain,
      Vector(IntegerWave3(1, 0, 0)),
      onePoint
    ) match
      case Left(SpectralBasisError.GaugeRankDeficient(2, 0)) => ()
      case other => fail(s"expected rank-deficient gauge, got $other")

    val original = fixtureBasis(1)
    val changed = fixtureBasis(1, Vector(12.0, 14.0, 18.0))
    val state = original
      .coefficientState(Vector.fill(original.nominalSize)(0.0))
      .fold(error => fail(error.message), identity)
    changed.requireState(state) match
      case Left(SpectralBasisError.BasisIdentityMismatch(expected, actual)) =>
        assertEquals(expected, changed.id)
        assertEquals(actual, original.id)
      case other => fail(s"expected basis identity mismatch, got $other")

  test("precision follows coefficient transport by congruence"):
    val old = DenseOperator(
      3,
      Vector(
        4.0, 1.0, -0.5,
        1.0, 3.0, 0.25,
        -0.5, 0.25, 2.0
      )
    )
    val oldFromNew = Vector(
      Vector(1.0, 0.2),
      Vector(-0.3, 0.7),
      Vector(0.5, -0.4)
    )
    val transported = DenseOperator
      .congruence(old, oldFromNew)
      .fold(error => fail(error.message), identity)
    val next = Vector(0.8, -1.1)
    val previous = Vector.tabulate(3)(row =>
      oldFromNew(row).indices.map(column => oldFromNew(row)(column) * next(column)).sum
    )
    val left = transported.quadratic(next).fold(error => fail(error.message), identity)
    val right = old.quadratic(previous).fold(error => fail(error.message), identity)
    assertEqualsDouble(left, right, 1e-14)

  private def fixtureBasis(
      maximumFrequency: Int,
      periods: Vector[Double] = Vector(12.0, 14.0, 16.0)
  ): PhysicalSpectralBasis3 =
    val domain = fixtureDomain(periods)
    val points = periodicPoints(domain, 8)
    val gauge = GeometryGaugeMeasure3
      .create("periodic-8-cubed", points, Vector.fill(points.length)(1.0))
      .fold(error => fail(error.message), identity)
    PhysicalSpectralBasis3
      .lowFrequency(domain, maximumFrequency, gauge)
      .fold(error => fail(error.message), identity)

  private def fixtureDomain(periods: Vector[Double]): PhysicalSpectralDomain3 =
    PhysicalSpectralDomain3
      .create(
        originMm = Vector(-3.0, 2.0, 5.0),
        axes = Vector(Vector(1.0, 0.0, 0.0), Vector(0.0, 1.0, 0.0), Vector(0.0, 0.0, 1.0)),
        periodsMm = periods,
        paddingMm = Vector(2.0, 2.0, 2.0),
        extensionId = "periodic-smooth-r3-v1"
      )
      .fold(error => fail(error.message), identity)

  private def periodicPoints(domain: PhysicalSpectralDomain3, samplesPerAxis: Int): Vector[Vector[Double]] =
    (for
      i <- 0 until samplesPerAxis
      j <- 0 until samplesPerAxis
      k <- 0 until samplesPerAxis
    yield Vector.tabulate(3)(axis =>
      domain.originMm(axis) + domain.periodsMm(axis) * Vector(i, j, k)(axis).toDouble / samplesPerAxis
    )).toVector

  private def independentPriorQuadrature(
      basis: PhysicalSpectralBasis3,
      coefficients: Vector[Double],
      weights: SpectralPriorWeights
  ): Double =
    val points = periodicPoints(basis.domain, 12)
    points.map { point =>
      val values = basis.values(point).fold(error => fail(error.message), identity)
      val gradients = basis.gradients(point).fold(error => fail(error.message), identity)
      val field = dot(coefficients, values)
      val fieldGradient = Vector.tabulate(3)(axis =>
        basis.modes.indices.map(mode => coefficients(mode) * gradients(mode)(axis)).sum
      )
      val hessian = Vector.tabulate(3, 3)((row, column) =>
        basis.modes.indices.map { mode =>
          val raw = values(mode) + basis.modes(mode).gaugeMean
          -coefficients(mode) * raw *
            basis.modes(mode).angularWaveWorldPerMm(row) *
            basis.modes(mode).angularWaveWorldPerMm(column)
        }.sum
      )
      weights.magnitude * field * field +
        weights.gradient * squaredNorm(fieldGradient) +
        weights.bending * hessian.flatten.map(value => value * value).sum
    }.sum / points.length.toDouble

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

  private def squaredNorm(value: Vector[Double]): Double = value.map(component => component * component).sum
