package reframe4s.halfflow

/** Opt-in bridge from the audited source-chain probe to the existing step.
  * Missing support fails before any destination is changed. Symmetric slopes
  * at interpolation knots retain the probe's documented directional limits.
  */
private[halfflow] object HalfFlowSpatialChainDirection:
  def into[W, F, M](
      fixed: RegistrationImage[F], moving: RegistrationImage[M],
      state: ForwardMidpoint[W, F, M], fixedDerivative: Array[Double], movingDerivative: Array[Double],
      action: HalfFlowCcAction, smoothingWeight: Array[Double],
      destination: Array[Double], validity: Array[Boolean]
  ): Either[RegistrationError, Unit] =
    val n = state.work.grid.nVoxels
    require(destination.length == 3 * n && validity.length == n && smoothingWeight.length == n)
    val result = HalfFlowSpatialChainProbe(fixed, moving, state, fixedDerivative, movingDerivative, action)
    if result.fixed.unavailable > 0 || result.moving.unavailable > 0 then
      Left(RegistrationError.InvalidField(
        s"spatial-chain derivative support (fixed=${result.fixed.unavailable}, moving=${result.moving.unavailable})"))
    else if result.gradient.exists(v => !v.isFinite) || smoothingWeight.exists(v => !v.isFinite || v < 0.0) then
      Left(RegistrationError.InvalidField("spatial-chain derivative or smoothing weight"))
    else if (0 until n).exists(i => smoothingWeight(i) == 0.0 &&
        (0 until 3).exists(c => result.gradient(i + c * n) != 0.0)) then
      Left(RegistrationError.InvalidField("nonzero spatial-chain derivative outside smoothing support"))
    else
      var i = 0
      while i < n do
        validity(i) = smoothingWeight(i) > 0.0
        var c = 0
        while c < 3 do
          destination(i + c * n) = if validity(i) then result.gradient(i + c * n) else 0.0
          c += 1
        i += 1
      Right(())
