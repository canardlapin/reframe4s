package reframe4s.flashalign

/** Explicit C/P/J oracle. It is deliberately test-only and matrix based. */
private object PatchObjectiveReference:
  final case class Terms(
      loss: Double,
      correlation: Double,
      outlierWeight: Double,
      positiveWeight: Double,
      negativeWeight: Double,
      jtu: Array[Double],
      jtj: Array[Double],
      gradient: Array[Double],
      curvature: Array[Double]
  )

  def evaluate(
      moving: Array[Double],
      fixed: Array[Double],
      intensityJacobian: Array[Array[Double]],
      positivePrior: Double,
      tau: Double,
      outlierFloor: Double
  ): Terms =
    val sampleCount = moving.length
    val parameterCount = intensityJacobian(0).length
    val u = centeredUnit(moving)
    val fixedCentered = centered(fixed)
    val fixedNorm = math.sqrt(dot(fixedCentered, fixedCentered))
    val v = fixedCentered.map(_ / fixedNorm)
    val jacobian = Array.ofDim[Double](sampleCount, parameterCount)
    var parameter = 0
    while parameter < parameterCount do
      val origin = intensityJacobian(0)(parameter)
      val shifted = Array.tabulate(sampleCount)(row =>
        intensityJacobian(row)(parameter) - origin
      )
      val shiftedMean = shifted.sum / sampleCount.toDouble
      val centeredGradient = shifted.map(_ - shiftedMean)
      val projection = dot(v, centeredGradient)
      var row = 0
      while row < sampleCount do
        jacobian(row)(parameter) =
          (centeredGradient(row) - v(row) * projection) / fixedNorm
        row += 1
      parameter += 1

    val correlation = dot(u, v)
    val tauSquared = tau * tau
    val outlierTerm = outlierFloor
    val positiveTerm =
      (1.0 - outlierFloor) * positivePrior *
        math.exp(-(1.0 - correlation) / tauSquared)
    val negativeTerm =
      (1.0 - outlierFloor) * (1.0 - positivePrior) *
        math.exp(-(1.0 + correlation) / tauSquared)
    val partition = outlierTerm + positiveTerm + negativeTerm
    val outlierWeight = outlierTerm / partition
    val positiveWeight = positiveTerm / partition
    val negativeWeight = negativeTerm / partition
    val signedWeight = positiveWeight - negativeWeight
    val inlierWeight = positiveWeight + negativeWeight

    val jtu = new Array[Double](parameterCount)
    val jtj = new Array[Double](parameterCount * parameterCount)
    var first = 0
    var row = 0
    while first < parameterCount do
      row = 0
      while row < sampleCount do
        jtu(first) += jacobian(row)(first) * u(row)
        row += 1
      var second = 0
      while second < parameterCount do
        row = 0
        while row < sampleCount do
          jtj(first * parameterCount + second) +=
            jacobian(row)(first) * jacobian(row)(second)
          row += 1
        second += 1
      first += 1

    Terms(
      -tauSquared * math.log(partition),
      correlation,
      outlierWeight,
      positiveWeight,
      negativeWeight,
      jtu,
      jtj,
      jtu.map(value => -signedWeight * value),
      jtj.map(value => inlierWeight * value)
    )

  private def centeredUnit(values: Array[Double]): Array[Double] =
    val result = centered(values)
    val norm = math.sqrt(dot(result, result))
    result.map(_ / norm)

  private def centered(values: Array[Double]): Array[Double] =
    val origin = values(0)
    val shifted = values.map(_ - origin)
    val mean = shifted.sum / shifted.length.toDouble
    shifted.map(_ - mean)

  private def dot(left: Array[Double], right: Array[Double]): Double =
    var result = 0.0
    var index = 0
    while index < left.length do
      result += left(index) * right(index)
      index += 1
    result
