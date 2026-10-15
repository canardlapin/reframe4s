package reframe4s.halfflow

import reframe4s.halfflow.internal.*

private[halfflow] final case class SpatialChainArmSummary(
    nonzeroIntensityDerivatives: Int,
    evaluated: Int,
    unavailable: Int,
    interpolationKnotVoxels: Int
)

private[halfflow] final case class HalfFlowSpatialChainResult(
    gradient: Array[Double],
    fixed: SpatialChainArmSummary,
    moving: SpatialChainArmSummary
)

/** Diagnostic-only chain rule for source interpolation after residual composition.
  *
  * The source trilinear interpolant is differentiated at the actual sample point.
  * At source-grid knots we report the symmetric slope and count the knot: a
  * simultaneous source/residual kink need not have a unique linear derivative.
  * Residual derivatives use the symmetric slopes of the piecewise trilinear map
  * at work-grid vertices. No fitting, smoothing or production gradient is changed.
  */
private[halfflow] object HalfFlowSpatialChainProbe:
  def apply[W, F, M](
      fixed: RegistrationImage[F],
      moving: RegistrationImage[M],
      state: ForwardMidpoint[W, F, M],
      fixedDerivative: Array[Double],
      movingDerivative: Array[Double],
      action: HalfFlowCcAction
  ): HalfFlowSpatialChainResult =
    val gradient = new Array[Double](3 * state.work.grid.nVoxels)
    val fixedScale = if action == HalfFlowCcAction.SymmetricMidpoint then 0.5 else 0.0
    val movingScale = if action == HalfFlowCcAction.SymmetricMidpoint then -0.5 else -1.0
    val fs = arm(fixed, state.fixed, fixedDerivative, fixedScale, gradient)
    val ms = arm(moving, state.moving, movingDerivative, movingScale, gradient)
    HalfFlowSpatialChainResult(gradient, fs, ms)

  private def arm[W, E](image: RegistrationImage[E], state: ForwardMidpointArm[W, E],
      intensityDerivative: Array[Double], scale: Double, destination: Array[Double]): SpatialChainArmSummary =
    require(image.frame == state.endpoint, "spatial-chain endpoint frame mismatch")
    val grid = state.work.grid
    val n = grid.nVoxels
    require(intensityDerivative.length == n && destination.length == 3 * n)
    val worldToSourceIndex = image.frame.grid.inverseAffine * state.affine.transform.matrix
    val workInverse = grid.inverseAffine
    val residual = state.residual.sourceCoordinates
    val strides = Vector(1, grid.extentX, grid.extentX * grid.extentY)
    var nonzero = 0
    var evaluated = 0
    var unavailable = 0
    var knots = 0
    var index = 0
    while index < n do
      val weight = scale * intensityDerivative(index)
      require(weight.isFinite, "nonfinite intensity derivative")
      if weight != 0.0 then
        nonzero += 1
        val coordinate = Vector(index % grid.extentX, (index / grid.extentX) % grid.extentY,
          index / (grid.extentX * grid.extentY))
        val valid = state.residual.validity.contains(index) &&
          (0 until 3).forall(axis => coordinate(axis) > 0 && coordinate(axis) < grid.shape(axis) - 1 &&
            state.residual.validity.contains(index - strides(axis)) &&
            state.residual.validity.contains(index + strides(axis)))
        val sample = if valid then
          val voxel = Vector.tabulate(3): axis =>
            worldToSourceIndex(axis, 3) + (0 until 3).map(c =>
              worldToSourceIndex(axis, c) * residual.linearComponent(index, c)).sum
          sampleVoxelGradient(image.volume, image.validity, voxel)
        else None
        sample match
          case None => unavailable += 1
          case Some((sourceGradient, knot)) =>
            // Covectors multiply each Jacobian in forward composition order.
            val residualCovector = Vector.tabulate(3): c =>
              (0 until 3).map(axis => sourceGradient(axis) * worldToSourceIndex(axis, c)).sum
            val workIndexCovector = Vector.tabulate(3): axis =>
              (0 until 3).map(c => residualCovector(c) * 0.5 *
                (residual.linearComponent(index + strides(axis), c) -
                  residual.linearComponent(index - strides(axis), c))).sum
            val physical = Vector.tabulate(3): c =>
              (0 until 3).map(axis => workIndexCovector(axis) * workInverse(axis, c)).sum
            if physical.forall(_.isFinite) then
              evaluated += 1
              if knot then knots += 1
              for c <- 0 until 3 do destination(index + c * n) += weight * physical(c)
            else unavailable += 1
      index += 1
    SpatialChainArmSummary(nonzero, evaluated, unavailable, knots)

  /** Source-index covector of the trilinear scalar interpolant. Invalid support
    * is unavailable, not an invented zero derivative. Uses the production
    * sampler's 1e-10 voxel snap convention; no boundary extension is introduced.
    */
  private[halfflow] def sampleVoxelGradient(source: NeuroVol[Double], validity: FieldValidity,
      coordinate: Vector[Double]): Option[(Vector[Double], Boolean)] =
    require(coordinate.length == 3)
    if !coordinate.forall(_.isFinite) then None
    else
      val grid = GridSpec.fromSpace(source.space)
      val point = coordinate.map: value =>
        val nearest = math.rint(value)
        if math.abs(value - nearest) <= 1e-10 then nearest else value
      if (0 until 3).exists(axis => point(axis) < 0.0 || point(axis) > grid.shape(axis) - 1.0) then None
      else
        val lower = point.map(math.floor(_).toInt)
        val fraction = (0 until 3).map(axis => point(axis) - lower(axis)).toVector
        val gradient = new Array[Double](3)
        var ok = true
        var axis = 0
        while axis < 3 && ok do
          val other = (0 until 3).filter(_ != axis)
          val knot = fraction(axis) == 0.0
          var a = 0
          while a <= 1 && ok do
            var b = 0
            while b <= 1 && ok do
              val wa = if a == 0 then 1.0 - fraction(other(0)) else fraction(other(0))
              val wb = if b == 0 then 1.0 - fraction(other(1)) else fraction(other(1))
              val weight = wa * wb
              if weight != 0.0 then
                val lo = lower.toArray
                lo(other(0)) += a
                lo(other(1)) += b
                if knot then lo(axis) -= 1
                val hi = lo.clone()
                hi(axis) += (if knot then 2 else 1)
                def value(at: Array[Int]): Option[Double] =
                  if (0 until 3).exists(c => at(c) < 0 || at(c) >= grid.shape(c)) then None
                  else
                    val index = at(0) + grid.extentX * (at(1) + grid.extentY * at(2))
                    val v = source.values(at(0), at(1), at(2))
                    if validity.contains(index) && v.isFinite then Some(v) else None
                (value(lo), value(hi)) match
                  case (Some(l), Some(h)) => gradient(axis) += weight * (h - l) * (if knot then 0.5 else 1.0)
                  case _ => ok = false
              b += 1
            a += 1
          axis += 1
        if ok && gradient.forall(_.isFinite) then Some((gradient.toVector, fraction.contains(0.0))) else None
