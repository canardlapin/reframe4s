package reframe4s.field

import image4s.Axis
import image4s.AxisKind
import image4s.ImageError
import image4s.MaskImage
import image4s.NonSpatialAxes
import image4s.Sampled
import image4s.SampleSpace
import ravel.AnyRank
import ravel.DType.given
import ravel.NDArray
import ravel.Shape
import reframe4s.core.EvidenceError
import reframe4s.core.ImplementationRevision
import reframe4s.core.IndexRegion
import reframe4s.core.InverseCriteria
import reframe4s.core.InverseDirection
import reframe4s.core.InverseDomain
import reframe4s.core.InverseEstimate
import reframe4s.core.InverseResidual
import reframe4s.core.InversionStatus
import reframe4s.core.MapError
import reframe4s.core.SpatialMap
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.LatticeIndex
import image4s.geometry.Point
import reframe4s.resample.Interpolation

/** Fixed-point outcome at one lattice point of the inverse. */
enum InversePointStatus derives CanEqual:
  case Converged
  case MaxIterations
  case Diverged
  case OutsideCoverage

final case class InversionStatusCounts(
    converged: Long,
    maxIterations: Long,
    diverged: Long,
    outsideCoverage: Long
) derives CanEqual:
  def total: Long = converged + maxIterations + diverged + outsideCoverage

/** Mean, nearest-rank 99th percentile and maximum of Euclidean residuals. */
final case class ResidualSummary(
    mean: Double,
    p99: Double,
    maximum: Double,
    sampleCount: Long
) derives CanEqual

/** The pull map's boundary policy and the number of lattice points at
  * which an iterate left its sampled support.
  */
final case class BoundaryUse(
    policy: CoordinateBoundaryPolicy,
    points: Long
) derives CanEqual

final class InversionSettings private (
    val maximumIterations: Int,
    val tolerance: Double,
    val divergenceRatio: Double
)

object InversionSettings:
  /** `tolerance` bounds `|pull(y) - x|` for convergence. A point diverges
    * when its residual exceeds `divergenceRatio` times its initial residual.
    */
  def create(
      maximumIterations: Int = 100,
      tolerance: Double = 1e-8,
      divergenceRatio: Double = 4.0
  ): Either[InversionError, InversionSettings] =
    if maximumIterations <= 0 then
      Left(InversionError.InvalidSetting("maximumIterations", maximumIterations.toDouble))
    else if !tolerance.isFinite || tolerance <= 0.0 then
      Left(InversionError.InvalidSetting("tolerance", tolerance))
    else if !divergenceRatio.isFinite || divergenceRatio <= 1.0 then
      Left(InversionError.InvalidSetting("divergenceRatio", divergenceRatio))
    else
      Right(new InversionSettings(maximumIterations, tolerance, divergenceRatio))

/** Qualification gates for an `Estimated` inverse. Residual gates apply to
  * points at least `interiorMargin` samples from their lattice boundary.
  */
final class InversionGates private (
    val minimumCoverage: Double,
    val maximumResidual: Double,
    val p99Residual: Double,
    val interiorMargin: Int
)

object InversionGates:
  def create(
      minimumCoverage: Double,
      maximumResidual: Double,
      p99Residual: Double,
      interiorMargin: Int
  ): Either[InversionError, InversionGates] =
    if !minimumCoverage.isFinite || minimumCoverage < 0.0 || minimumCoverage > 1.0
    then Left(InversionError.InvalidSetting("minimumCoverage", minimumCoverage))
    else if !maximumResidual.isFinite || maximumResidual < 0.0 then
      Left(InversionError.InvalidSetting("maximumResidual", maximumResidual))
    else if !p99Residual.isFinite || p99Residual < 0.0 then
      Left(InversionError.InvalidSetting("p99Residual", p99Residual))
    else if interiorMargin < 0 then
      Left(InversionError.InvalidSetting("interiorMargin", interiorMargin.toDouble))
    else
      Right(
        new InversionGates(
          minimumCoverage,
          maximumResidual,
          p99Residual,
          interiorMargin
        )
      )

enum InversionGateFailure derives CanEqual:
  case CoverageBelowMinimum(observed: Double, minimum: Double)
  case MaximumResidualExceeded(
      direction: InverseDirection,
      observed: Double,
      maximum: Double
  )
  case P99ResidualExceeded(
      direction: InverseDirection,
      observed: Double,
      maximum: Double
  )
  case NoResidualSamples(direction: InverseDirection)
  case InteriorDivergence(points: Long)

/** Complete empirical record of one numerical inversion.
  *
  * The evaluation domain is the set of inverse-lattice points whose estimate
  * `push(x)` was found inside the pull map's sampled support: status
  * `Converged` or `MaxIterations`. `Forward` residuals are
  * `|pull(push(x)) - x|` on that domain; `Reverse` residuals are
  * `|push(pull(y)) - y|` on the pull lattice, where `push` interpolates the
  * estimate only from domain samples. Residual statistics are interior-only.
  */
final class DenseInverseEvidence[S <: Frame[D], D <: Dim] private[field] (
    val lattice: Grid[S, D],
    val domainMask: MaskImage[? <: SampleSpace[S, D], AnyRank],
    val coveredFraction: Double,
    val statusCounts: InversionStatusCounts,
    val interiorStatusCounts: InversionStatusCounts,
    val forwardResidual: Option[ResidualSummary],
    val reverseResidual: Option[ResidualSummary],
    val boundary: BoundaryUse,
    val maximumIterationsUsed: Int,
    private val statuses: Array[Byte]
):
  def statusAt(index: Vector[Int]): Option[InversePointStatus] =
    DeterminantField
      .linear(lattice.shape, index)
      .map(linear => InversePointStatus.fromOrdinal(statuses(linear).toInt))

sealed trait InversionError derives CanEqual:
  def message: String

object InversionError:
  final case class InvalidSetting(name: String, value: Double)
      extends InversionError:
    val message: String = s"invalid inversion setting $name = $value"

  final case class Map(error: MapError) extends InversionError:
    val message: String = error.message

  final case class Field(error: FieldError) extends InversionError:
    val message: String = error.message

  final case class Image(error: ImageError) extends InversionError:
    val message: String = error.message

  final case class Geometry(error: GeometryError) extends InversionError:
    val message: String = error.message

  final case class Evidence(error: EvidenceError) extends InversionError:
    val message: String = error.message

  final case class LatticeTooLarge(shape: Vector[Int]) extends InversionError:
    val message: String =
      s"lattice ${shape.mkString("x")} exceeds the addressable array size"

  /** The estimate failed a qualification gate. The evidence is complete. */
  final case class GatesFailed(
      failures: Vector[InversionGateFailure],
      evidence: DenseInverseEvidence[?, ?]
  ) extends InversionError:
    val message: String =
      s"numerical inverse failed ${failures.length} gate(s): " +
        failures.mkString(", ")

/** A qualified numerical inverse. `estimate.value` rejects points whose
  * interpolation stencil touches samples outside the evaluation domain;
  * `samples` holds every lattice sample, with the query coordinates as
  * placeholders outside the domain.
  */
final class NumericalInverse[S <: Frame[D], T <: Frame[D], D <: Dim] private[
  field
] (
    val estimate: InverseEstimate[S, T, D],
    val samples: DenseMap[S, T, D, AnyRank],
    val evidence: DenseInverseEvidence[S, D]
)

/** Fixed-point inversion of dense pullback fields.
  *
  * The result is `InverseEstimate` evidence, never a `SmoothIso`.
  */
object NumericalInversion:
  /** Estimate `push: S -> T` with `pull(push(x)) = x` on `lattice`.
    *
    * Each point iterates `y <- y - (pull(y) - x)` from `y = x`, which
    * converges where the pull displacement is a contraction. `lattice` must
    * be persistent because the estimate records its evaluation domain.
    */
  def invert[T <: Frame[D], S <: Frame[D], D <: Dim, R <: AnyRank](
      pull: DenseMap[T, S, D, R],
      lattice: Grid[S, D],
      settings: InversionSettings,
      gates: InversionGates,
      revision: ImplementationRevision
  )(using
      dimension: Dimension[D]
  ): Either[InversionError, NumericalInverse[S, T, D]] =
    val rank = dimension.rank
    val shape = lattice.shape
    val count = shape.product
    val statuses = new Array[Byte](count)
    val estimates = new Array[Double](count * rank)
    val residuals = new Array[Double](count)
    for
      _ <- SpatialMap
        .validateSourceFrame(pull.target, lattice.frame)
        .left
        .map(InversionError.Map.apply)
      region <- IndexRegion
        .within[D](shape, Vector.fill(rank)(0), shape)
        .left
        .map(InversionError.Evidence.apply)
      domain <- InverseDomain
        .on(lattice, region)
        .left
        .map(InversionError.Evidence.apply)
      iterations <- solve(pull, lattice, settings, statuses, estimates, residuals)
      samples <- sampleMap(lattice, pull.source, estimates)
      mask <- domainMask(lattice, statuses)
      push = new DomainRestrictedMap(samples, statuses)
      reverse <- reverseResiduals(pull, push, gates.interiorMargin)
      evidence = buildEvidence(
        lattice,
        mask,
        statuses,
        residuals,
        reverse,
        pull.boundary,
        iterations,
        gates.interiorMargin
      )
      _ <- qualify(evidence, gates)
      forward <- evidence.forwardResidual.toRight(
        InversionError.GatesFailed(
          Vector(InversionGateFailure.NoResidualSamples(InverseDirection.Forward)),
          evidence
        )
      )
      residual <- InverseResidual
        .create(forward.maximum, forward.mean, forward.sampleCount, evidence.coveredFraction)
        .left
        .map(InversionError.Evidence.apply)
      status <-
        (if evidence.interiorStatusCounts.maxIterations == 0L then
           InversionStatus.converged(math.max(1, iterations))
         else InversionStatus.iterationLimit(math.max(1, iterations)))
          .left
          .map(InversionError.Evidence.apply)
      criteria <- InverseCriteria
        .create(gates.maximumResidual, gates.minimumCoverage)
        .left
        .map(InversionError.Evidence.apply)
      estimate <- InverseEstimate
        .record(push, residual, status, domain, criteria, revision)
        .left
        .map(InversionError.Evidence.apply)
    yield new NumericalInverse(estimate, samples, evidence)

  private def solve[T <: Frame[D], S <: Frame[D], D <: Dim, R <: AnyRank](
      pull: DenseMap[T, S, D, R],
      lattice: Grid[S, D],
      settings: InversionSettings,
      statuses: Array[Byte],
      estimates: Array[Double],
      residuals: Array[Double]
  )(using dimension: Dimension[D]): Either[InversionError, Int] =
    val rank = dimension.rank
    val index = new Array[Int](rank)
    val iterate = new Array[Double](rank)
    val best = new Array[Double](rank)
    val image = new Array[Double](rank)
    val sampler = pull.linearSampler
    var maximumUsed = 0
    var linear = 0
    var failure = Option.empty[InversionError]
    while linear < statuses.length && failure.isEmpty do
      latticePoint(lattice, index) match
        case Left(error) => failure = Some(error)
        case Right(x) =>
          val target = x.coordinates
          var axis = 0
          while axis < rank do
            iterate(axis) = target(axis)
            best(axis) = target(axis)
            axis += 1
          var status = Option.empty[InversePointStatus]
          var initial = -1.0
          var bestResidual = Double.PositiveInfinity
          var step = 0
          while status.isEmpty && failure.isEmpty do
            evaluate(pull, sampler, iterate, image) match
              case Left(error) => failure = Some(error)
              case Right(false) =>
                status = Some(InversePointStatus.OutsideCoverage)
              case Right(true) =>
                var norm = 0.0
                axis = 0
                while axis < rank do
                  val difference = image(axis) - target(axis)
                  norm += difference * difference
                  axis += 1
                norm = math.sqrt(norm)
                if initial < 0.0 then initial = norm
                if norm < bestResidual then
                  bestResidual = norm
                  System.arraycopy(iterate, 0, best, 0, rank)
                if norm <= settings.tolerance then
                  status = Some(InversePointStatus.Converged)
                else if !norm.isFinite ||
                  norm > settings.divergenceRatio * initial
                then status = Some(InversePointStatus.Diverged)
                else if step >= settings.maximumIterations then
                  status = Some(InversePointStatus.MaxIterations)
                else
                  axis = 0
                  while axis < rank do
                    iterate(axis) -= image(axis) - target(axis)
                    axis += 1
                  step += 1
          maximumUsed = math.max(maximumUsed, step)
          val resolved = status.getOrElse(InversePointStatus.OutsideCoverage)
          statuses(linear) = resolved.ordinal.toByte
          val estimated =
            resolved == InversePointStatus.Converged ||
              resolved == InversePointStatus.MaxIterations
          residuals(linear) = if estimated then bestResidual else 0.0
          axis = 0
          while axis < rank do
            estimates(linear * rank + axis) =
              if estimated then best(axis) else target(axis)
            axis += 1
      linear += 1
      FieldComposition.advance(index, lattice.shape)
    failure.toLeft(maximumUsed)

  /** Write `pull(y)` into `image` and return `true` when `y` is inside the
    * pull's support, `false` when an iterate left it, and `Left` only for
    * structural failures. Linear pulls use the primitive sampler.
    */
  private def evaluate[T <: Frame[D], S <: Frame[D], D <: Dim, R <: AnyRank](
      pull: DenseMap[T, S, D, R],
      sampler: Option[LinearCoordinateSampler],
      coordinates: Array[Double],
      image: Array[Double]
  )(using Dimension[D]): Either[InversionError, Boolean] =
    if coordinates.exists(value => !value.isFinite) then Right(false)
    else
      sampler match
        case Some(linear) =>
          Right(linear.sample(coordinates, image).contains(SupportOutcome.Covered))
        case None =>
          evaluatePointwise(pull, coordinates).map {
            case Some(values) =>
              var axis = 0
              while axis < image.length do
                image(axis) = values(axis)
                axis += 1
              true
            case None => false
          }

  private def evaluatePointwise[T <: Frame[D], S <: Frame[D], D <: Dim, R <: AnyRank](
      pull: DenseMap[T, S, D, R],
      coordinates: Array[Double]
  )(using Dimension[D]): Either[InversionError, Option[Vector[Double]]] =
    Point
      .fromVector(pull.source, coordinates.toVector)
      .flatMap(point =>
        Frame
          .alignOwners[D, pull.source.type, T](pull.source, pull.source)
          .flatMap(_.pointToRight(point))
      )
      .left
      .map(InversionError.Geometry.apply)
      .flatMap(point =>
        pull.applyWithCoverage(point) match
          case Right(covered) if covered.outcome == SupportOutcome.Covered =>
            Right(Some(covered.point.coordinates))
          case Right(_)                         => Right(None)
          case Left(MapError.OutsideDomain(_))  => Right(None)
          case Left(error)                      => Left(InversionError.Map(error))
      )

  private def reverseResiduals[T <: Frame[D], S <: Frame[D], D <: Dim, R <: AnyRank](
      pull: DenseMap[T, S, D, R],
      push: DomainRestrictedMap[S, T, D],
      margin: Int
  )(using dimension: Dimension[D]): Either[InversionError, Array[Double]] =
    val grid = pull.grid
    val rank = dimension.rank
    val index = new Array[Int](rank)
    val values = Array.newBuilder[Double]
    var linear = 0
    var failure = Option.empty[InversionError]
    val count = grid.shape.product
    while linear < count && failure.isEmpty do
      if interior(index, grid.shape, margin) then
        val sample =
          for
            lattice <- LatticeIndex
              .fromVector[D](index.toVector)
              .left
              .map(InversionError.Geometry.apply)
            y <- grid.pointAt(lattice).left.map(InversionError.Geometry.apply)
          yield y
        sample match
          case Left(error) => failure = Some(error)
          case Right(y) =>
            pull.applyWithCoverage(y) match
              case Right(covered) if covered.outcome == SupportOutcome.Covered =>
                push(covered.point) match
                  case Right(back) =>
                    val original = y.coordinates
                    val returned = back.coordinates
                    var norm = 0.0
                    var axis = 0
                    while axis < rank do
                      val difference = returned(axis) - original(axis)
                      norm += difference * difference
                      axis += 1
                    values += math.sqrt(norm)
                  case Left(MapError.OutsideDomain(_)) => ()
                  case Left(error) => failure = Some(InversionError.Map(error))
              case Right(_) | Left(MapError.OutsideDomain(_)) => ()
              case Left(error) => failure = Some(InversionError.Map(error))
      linear += 1
      FieldComposition.advance(index, grid.shape)
    failure.toLeft(values.result())

  private def buildEvidence[S <: Frame[D], D <: Dim](
      lattice: Grid[S, D],
      mask: MaskImage[? <: SampleSpace[S, D], AnyRank],
      statuses: Array[Byte],
      residuals: Array[Double],
      reverse: Array[Double],
      policy: CoordinateBoundaryPolicy,
      iterations: Int,
      margin: Int
  ): DenseInverseEvidence[S, D] =
    val all = new Array[Long](InversePointStatus.values.length)
    val inner = new Array[Long](InversePointStatus.values.length)
    val forward = Array.newBuilder[Double]
    val index = new Array[Int](lattice.shape.length)
    var linear = 0
    while linear < statuses.length do
      val status = statuses(linear).toInt
      all(status) += 1L
      if interior(index, lattice.shape, margin) then
        inner(status) += 1L
        if status == InversePointStatus.Converged.ordinal ||
          status == InversePointStatus.MaxIterations.ordinal
        then forward += residuals(linear)
      linear += 1
      FieldComposition.advance(index, lattice.shape)
    val counts = tally(all)
    new DenseInverseEvidence(
      lattice,
      mask,
      (counts.converged + counts.maxIterations).toDouble / statuses.length.toDouble,
      counts,
      tally(inner),
      summarize(forward.result()),
      summarize(reverse),
      BoundaryUse(policy, counts.outsideCoverage),
      iterations,
      statuses
    )

  private def qualify[S <: Frame[D], D <: Dim](
      evidence: DenseInverseEvidence[S, D],
      gates: InversionGates
  ): Either[InversionError, Unit] =
    val failures = Vector.newBuilder[InversionGateFailure]
    if evidence.coveredFraction < gates.minimumCoverage then
      failures += InversionGateFailure.CoverageBelowMinimum(
        evidence.coveredFraction,
        gates.minimumCoverage
      )
    for
      (direction, summary) <- Vector(
        InverseDirection.Forward -> evidence.forwardResidual,
        InverseDirection.Reverse -> evidence.reverseResidual
      )
    do
      summary match
        case None =>
          failures += InversionGateFailure.NoResidualSamples(direction)
        case Some(value) =>
          if value.maximum > gates.maximumResidual then
            failures += InversionGateFailure.MaximumResidualExceeded(
              direction,
              value.maximum,
              gates.maximumResidual
            )
          if value.p99 > gates.p99Residual then
            failures += InversionGateFailure.P99ResidualExceeded(
              direction,
              value.p99,
              gates.p99Residual
            )
    if evidence.interiorStatusCounts.diverged > 0L then
      failures += InversionGateFailure.InteriorDivergence(
        evidence.interiorStatusCounts.diverged
      )
    val result = failures.result()
    if result.isEmpty then Right(())
    else Left(InversionError.GatesFailed(result, evidence))

  private def summarize(values: Array[Double]): Option[ResidualSummary] =
    if values.isEmpty then None
    else
      val sorted = values.sorted
      val rank = math.ceil(0.99 * sorted.length).toInt - 1
      Some(
        ResidualSummary(
          sorted.sum / sorted.length.toDouble,
          sorted(math.max(0, rank)),
          sorted.last,
          sorted.length.toLong
        )
      )

  private def tally(values: Array[Long]): InversionStatusCounts =
    InversionStatusCounts(
      converged = values(InversePointStatus.Converged.ordinal),
      maxIterations = values(InversePointStatus.MaxIterations.ordinal),
      diverged = values(InversePointStatus.Diverged.ordinal),
      outsideCoverage = values(InversePointStatus.OutsideCoverage.ordinal)
    )

  private def interior(
      index: Array[Int],
      shape: Vector[Int],
      margin: Int
  ): Boolean =
    var axis = 0
    var inside = true
    while axis < index.length && inside do
      inside = index(axis) >= margin && index(axis) < shape(axis) - margin
      axis += 1
    inside

  private def latticePoint[S <: Frame[D], D <: Dim](
      lattice: Grid[S, D],
      index: Array[Int]
  )(using Dimension[D]): Either[InversionError, Point[S, D]] =
    LatticeIndex
      .fromVector[D](index.toVector)
      .flatMap(lattice.pointAt)
      .left
      .map(InversionError.Geometry.apply)

  private def sampleMap[S <: Frame[D], T <: Frame[D], D <: Dim](
      lattice: Grid[S, D],
      target: T,
      estimates: Array[Double]
  )(using dimension: Dimension[D])
      : Either[InversionError, DenseMap[S, T, D, AnyRank]] =
    for
      axis <- Axis
        .create("component", dimension.rank, AxisKind.Direction)
        .left
        .map(InversionError.Image.apply)
      axes <- NonSpatialAxes.from(Vector(axis)).left.map(InversionError.Image.apply)
      shape <- Shape
        .from(lattice.shape :+ dimension.rank)
        .left
        .map(_ => InversionError.LatticeTooLarge(lattice.shape))
      data = NDArray.build[Double, AnyRank](shape): builder =>
        var index = 0
        while index < estimates.length do
          builder.writeLinear(index, estimates(index))
          index += 1
      image <- Sampled
        .continuous(lattice, axes, data)
        .left
        .map(InversionError.Image.apply)
      map <- DenseMap
        .fromCoordinates(image, target, Interpolation.Linear)
        .left
        .map(InversionError.Field.apply)
    yield map

  private def domainMask[S <: Frame[D], D <: Dim](
      lattice: Grid[S, D],
      statuses: Array[Byte]
  ): Either[InversionError, MaskImage[? <: SampleSpace[S, D], AnyRank]] =
    for
      shape <- Shape
        .from(lattice.shape)
        .left
        .map(_ => InversionError.LatticeTooLarge(lattice.shape))
      data = NDArray.build[Boolean, AnyRank](shape): builder =>
        var index = 0
        while index < statuses.length do
          builder.writeLinear(index, inDomain(statuses(index)))
          index += 1
      mask <- Sampled
        .mask(lattice, NonSpatialAxes.empty, data)
        .left
        .map(InversionError.Image.apply)
    yield mask

  private def inDomain(status: Byte): Boolean =
    status == InversePointStatus.Converged.ordinal.toByte ||
      status == InversePointStatus.MaxIterations.ordinal.toByte

  /** Linear interpolation of the estimate that rejects any point whose
    * stencil reaches a sample outside the evaluation domain.
    */
  private final class DomainRestrictedMap[S <: Frame[D], T <: Frame[D], D <: Dim](
      samples: DenseMap[S, T, D, AnyRank],
      statuses: Array[Byte]
  )(using dimension: Dimension[D])
      extends SpatialMap[S, T, D]:
    val source: S = samples.source
    val target: T = samples.target
    private val shape = samples.grid.shape

    def apply(point: Point[S, D]): Either[MapError, Point[T, D]] =
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        continuous <- samples.grid
          .continuousIndexOf(point)
          .left
          .map(MapError.Geometry.apply)
        _ <-
          if stencilInDomain(continuous.values) then Right(())
          else Left(MapError.OutsideDomain(point.coordinates))
        result <- samples(point)
      yield result

    /** Every linear-interpolation corner with positive weight must be a
      * lattice sample inside the evaluation domain.
      */
    private def stencilInDomain(values: Vector[Double]): Boolean =
      val rank = dimension.rank
      var corner = 0
      var inside = true
      while corner < (1 << rank) && inside do
        var linear = 0
        var weighted = true
        var inRange = true
        var axis = 0
        while axis < rank do
          val lower = math.floor(values(axis)).toInt
          val fraction = values(axis) - lower
          val upper = ((corner >> axis) & 1) == 1
          val coordinate = if upper then lower + 1 else lower
          weighted = weighted && (if upper then fraction > 0.0 else fraction < 1.0)
          inRange = inRange && coordinate >= 0 && coordinate < shape(axis)
          linear = linear * shape(axis) + coordinate
          axis += 1
        if weighted then
          inside = inRange && inDomain(statuses(linear))
        corner += 1
      inside
