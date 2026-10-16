package reframe4s.halfflow

/** Detached diagnostics plus a single-threaded frozen-objective evaluator.
  * This opt-in probe does not advance state or participate in optimization.
  * `gradient` is the production physical velocity gradient, before the
  * pointwise solver, smoothing, clipping, integration and regularization.
  */
private[halfflow] final case class HalfFlowCcObjectiveProbe[W, F, M](
    audit: HalfFlowCcObjectiveAudit,
    gradient: Array[Double],
    gradientValid: Array[Boolean],
    value: ForwardMidpoint[W, F, M] => Either[RegistrationError, Double],
    sampledIntensity: Option[ForwardMidpoint[W, F, M] => Either[RegistrationError, HalfFlowSampledIntensityProbe]] = None,
    spatialChain: Option[HalfFlowSpatialChainResult] = None,
    intensityGradient: Option[(Array[Double], Array[Double])] = None
)

/** Frozen loss and its intensity-space linearization at the original state.
  * Actual candidate warps are used; no spatial-gradient approximation enters
  * the intensity-space dot product.
  */
private[halfflow] final case class HalfFlowSampledIntensityProbe(loss: Double, linearizedChange: Double)
