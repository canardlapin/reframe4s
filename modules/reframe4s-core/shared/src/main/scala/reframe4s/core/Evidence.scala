package reframe4s.core

import image4s.geometry.Dim
import image4s.geometry.Frame
import image4s.geometry.Grid
import image4s.geometry.GridKey
import image4s.geometry.Dimension

enum EvidenceMetric derives CanEqual:
  case MaximumResidual
  case MeanResidual
  case MaximumRoundTripResidual
  case DeterminantThreshold
  case MinimumCellJacobian
  case MaximumCellJacobian

enum EvidenceFraction derives CanEqual:
  case MinimumCovered
  case Covered
  case CoveredDomain

enum EvidenceCount derives CanEqual:
  case Iterations
  case Samples
  case MaximumFoldedCells
  case FoldedCells
  case SubdivisionLevels

enum InverseDirection derives CanEqual:
  case Forward, Reverse

sealed trait EvidenceError derives CanEqual:
  def message: String

object EvidenceError:
  case object EmptyImplementationRevision extends EvidenceError:
    val message: String = "implementation revision must be non-empty"

  final case class NonPositiveCount(field: EvidenceCount, value: Long)
      extends EvidenceError:
    val message: String = s"$field must be positive, got $value"

  final case class NegativeCount(field: EvidenceCount, value: Long)
      extends EvidenceError:
    val message: String = s"$field must be non-negative, got $value"

  final case class InvalidMetric(field: EvidenceMetric, value: Double)
      extends EvidenceError:
    val message: String = s"$field must be finite, got $value"

  final case class NegativeMetric(field: EvidenceMetric, value: Double)
      extends EvidenceError:
    val message: String = s"$field must be non-negative, got $value"

  final case class InvalidFraction(field: EvidenceFraction, value: Double)
      extends EvidenceError:
    val message: String = s"$field must be in [0, 1], got $value"

  final case class RankMismatch(expected: Int, actual: Int)
      extends EvidenceError:
    val message: String = s"expected rank $expected, got $actual"

  final case class InvalidResolution(axis: Int, value: Int)
      extends EvidenceError:
    val message: String =
      s"resolution axis $axis must be positive, got $value"

  final case class InvalidRegion(
      axis: Int,
      lowerInclusive: Int,
      upperExclusive: Int,
      extent: Int
  ) extends EvidenceError:
    val message: String =
      s"region axis $axis [$lowerInclusive, $upperExclusive) is outside [0, $extent)"

  final case class ResolutionMismatch(
      expected: Vector[Int],
      actual: Vector[Int]
  ) extends EvidenceError:
    val message: String =
      s"evidence resolution ${actual.mkString("x")} does not match grid " +
        expected.mkString("x")

  case object PersistentGridRequired extends EvidenceError:
    val message: String =
      "persistable evidence requires a grid with an explicit persistent key"

  final case class GridMismatch(expected: GridKey, actual: GridKey)
      extends EvidenceError:
    val message: String =
      s"evidence grid ${actual.id.value} does not match ${expected.id.value}"

  final case class ScopeMismatch(
      expected: TopologyScopeRecord,
      actual: TopologyScopeRecord
  ) extends EvidenceError:
    val message: String =
      s"topology evidence scope does not match requested scope for grid " +
        actual.gridKey.id.value

  final case class EndpointMismatch(error: MapError) extends EvidenceError:
    val message: String = error.message

  final case class MeanExceedsMaximum(mean: Double, maximum: Double)
      extends EvidenceError:
    val message: String =
      s"mean residual $mean exceeds maximum residual $maximum"

  final case class InvalidJacobianRange(minimum: Double, maximum: Double)
      extends EvidenceError:
    val message: String =
      s"minimum cell Jacobian $minimum exceeds maximum $maximum"

  final case class FoldedCellsExceedSamples(
      folded: Long,
      sampled: Long
  ) extends EvidenceError:
    val message: String =
      s"folded cell count $folded exceeds sampled cell count $sampled"

  final case class ResidualExceedsCriterion(
      observed: Double,
      maximum: Double
  ) extends EvidenceError:
    val message: String =
      s"inverse residual $observed exceeds criterion $maximum"

  final case class CoverageBelowCriterion(
      observed: Double,
      minimum: Double
  ) extends EvidenceError:
    val message: String =
      s"covered fraction $observed is below criterion $minimum"

  final case class DeterminantBelowThreshold(
      observed: Double,
      threshold: Double
  ) extends EvidenceError:
    val message: String =
      s"minimum sampled determinant $observed is below threshold $threshold"

  final case class TooManyFoldedCells(observed: Long, maximum: Long)
      extends EvidenceError:
    val message: String =
      s"folded cell count $observed exceeds criterion $maximum"

  final case class InverseDidNotConverge(
      direction: InverseDirection,
      outcome: InversionOutcome
  ) extends EvidenceError:
    val message: String =
      s"$direction inverse did not converge: $outcome"

opaque type ImplementationRevision = String

object ImplementationRevision:
  def parse(value: String): Either[EvidenceError, ImplementationRevision] =
    val normalized = value.trim
    if normalized.nonEmpty && normalized == value then Right(value)
    else Left(EvidenceError.EmptyImplementationRevision)

  extension (revision: ImplementationRevision)
    def value: String = revision

sealed trait InversionStatus derives CanEqual:
  def iterations: Int
  def outcome: InversionOutcome

  final def converged: Boolean =
    outcome == InversionOutcome.Converged

enum InversionOutcome derives CanEqual:
  case Converged, IterationLimit, Diverged

object InversionStatus:
  private final class Validated(
      val iterations: Int,
      val outcome: InversionOutcome
  ) extends InversionStatus

  def converged(iterations: Int): Either[EvidenceError, InversionStatus] =
    create(iterations, InversionOutcome.Converged)

  def iterationLimit(iterations: Int): Either[EvidenceError, InversionStatus] =
    create(iterations, InversionOutcome.IterationLimit)

  def diverged(iterations: Int): Either[EvidenceError, InversionStatus] =
    create(iterations, InversionOutcome.Diverged)

  private def create(
      iterations: Int,
      outcome: InversionOutcome
  ): Either[EvidenceError, InversionStatus] =
    if iterations > 0 then Right(new Validated(iterations, outcome))
    else
      Left(
        EvidenceError.NonPositiveCount(
          EvidenceCount.Iterations,
          iterations.toLong
        )
      )

final class InverseCriteria private (
    val maximumResidual: Double,
    val minimumCoveredFraction: Double
)

object InverseCriteria:
  def create(
      maximumResidual: Double,
      minimumCoveredFraction: Double
  ): Either[EvidenceError, InverseCriteria] =
    for
      _ <- EvidenceValidation.nonNegativeFinite(
        EvidenceMetric.MaximumResidual,
        maximumResidual
      )
      _ <- EvidenceValidation.fraction(
        EvidenceFraction.MinimumCovered,
        minimumCoveredFraction
      )
    yield new InverseCriteria(maximumResidual, minimumCoveredFraction)

final class InverseResidual private (
    val maximum: Double,
    val mean: Double,
    val sampleCount: Long,
    val coveredFraction: Double
)

object InverseResidual:
  def create(
      maximum: Double,
      mean: Double,
      sampleCount: Long,
      coveredFraction: Double
  ): Either[EvidenceError, InverseResidual] =
    for
      _ <- EvidenceValidation.nonNegativeFinite(
        EvidenceMetric.MaximumResidual,
        maximum
      )
      _ <- EvidenceValidation.nonNegativeFinite(
        EvidenceMetric.MeanResidual,
        mean
      )
      _ <-
        if mean <= maximum then Right(())
        else Left(EvidenceError.MeanExceedsMaximum(mean, maximum))
      _ <-
        if sampleCount > 0 then Right(())
        else
          Left(
            EvidenceError.NonPositiveCount(
              EvidenceCount.Samples,
              sampleCount
            )
          )
      _ <- EvidenceValidation.fraction(
        EvidenceFraction.Covered,
        coveredFraction
      )
    yield new InverseResidual(maximum, mean, sampleCount, coveredFraction)

final class IndexRegion[D <: Dim] private (
    val resolution: Vector[Int],
    val lowerInclusive: Vector[Int],
    val upperExclusive: Vector[Int]
):
  def record: IndexRegionRecord =
    IndexRegionRecord(resolution, lowerInclusive, upperExclusive)

final case class IndexRegionRecord(
    resolution: Vector[Int],
    lowerInclusive: Vector[Int],
    upperExclusive: Vector[Int]
) derives CanEqual

object IndexRegion:
  def within[D <: Dim](
      resolution: Vector[Int],
      lowerInclusive: Vector[Int],
      upperExclusive: Vector[Int]
  )(using dimension: Dimension[D]): Either[EvidenceError, IndexRegion[D]] =
    EvidenceValidation
      .resolution(resolution)
      .flatMap(_ =>
        if resolution.length != dimension.rank then
          Left(EvidenceError.RankMismatch(dimension.rank, resolution.length))
        else if lowerInclusive.length != resolution.length then
          Left(EvidenceError.RankMismatch(resolution.length, lowerInclusive.length))
        else if upperExclusive.length != resolution.length then
          Left(EvidenceError.RankMismatch(resolution.length, upperExclusive.length))
        else
          resolution.indices.collectFirst {
            case axis
                if lowerInclusive(axis) < 0 ||
                  upperExclusive(axis) <= lowerInclusive(axis) ||
                  upperExclusive(axis) > resolution(axis) =>
              EvidenceError.InvalidRegion(
                axis,
                lowerInclusive(axis),
                upperExclusive(axis),
                resolution(axis)
              )
          } match
            case Some(error) => Left(error)
            case None =>
              Right(
                new IndexRegion(
                  resolution,
                  lowerInclusive,
                  upperExclusive
                )
              )
      )

  def restore[D <: Dim](
      record: IndexRegionRecord
  )(using Dimension[D]): Either[EvidenceError, IndexRegion[D]] =
    within(
      record.resolution,
      record.lowerInclusive,
      record.upperExclusive
    )

final class InverseDomain[+F <: Frame[D], D <: Dim] private (
    val gridKey: GridKey,
    val frame: F,
    val resolution: Vector[Int],
    val region: IndexRegion[D]
):
  def record: InverseDomainRecord =
    InverseDomainRecord(gridKey, resolution, region.record)

final case class InverseDomainRecord(
    gridKey: GridKey,
    resolution: Vector[Int],
    region: IndexRegionRecord
) derives CanEqual

object InverseDomain:
  def on[F <: Frame[D], D <: Dim](
      grid: Grid[F, D],
      region: IndexRegion[D]
  ): Either[EvidenceError, InverseDomain[F, D]] =
    grid.persistentKey match
      case None =>
        Left(EvidenceError.PersistentGridRequired)
      case Some(key) if region.resolution == grid.shape =>
        Right(
          new InverseDomain(
            key,
            grid.frame,
            grid.shape,
            region
          )
        )
      case Some(_) =>
        Left(
          EvidenceError.ResolutionMismatch(
            grid.shape,
            region.resolution
          )
        )

  def restore[F <: Frame[D], D <: Dim](
      record: InverseDomainRecord,
      grid: Grid[F, D]
  )(using Dimension[D]): Either[EvidenceError, InverseDomain[F, D]] =
    grid.persistentKey match
      case None =>
        Left(EvidenceError.PersistentGridRequired)
      case Some(key) if record.gridKey != key =>
        Left(EvidenceError.GridMismatch(key, record.gridKey))
      case Some(_) if record.resolution != grid.shape =>
        Left(
          EvidenceError.ResolutionMismatch(
            grid.shape,
            record.resolution
          )
        )
      case Some(_) =>
        IndexRegion
          .restore[D](record.region)
          .flatMap(region => on(grid, region))

/** A numerical inverse candidate and its complete empirical provenance.
  *
  * This value deliberately does not implement `SmoothIso`.
  */
final class InverseEstimate[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim
] private (
    val value: SpatialMap[From, To, D],
    val residual: InverseResidual,
    val status: InversionStatus,
    val domain: InverseDomain[From, D],
    val criteria: InverseCriteria,
    val implementationRevision: ImplementationRevision
)

object InverseEstimate:
  def record[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      value: SpatialMap[From, To, D],
      residual: InverseResidual,
      status: InversionStatus,
      domain: InverseDomain[From, D],
      criteria: InverseCriteria,
      implementationRevision: ImplementationRevision
  ): Either[EvidenceError, InverseEstimate[From, To, D]] =
    EvidenceValidation
      .sourceEndpoint(value.source, domain.frame)
      .map(_ =>
        new InverseEstimate(
          value,
          residual,
          status,
          domain,
          criteria,
          implementationRevision
        )
      )

final class InversePairEvidence private (
    val maximumRoundTripResidual: Double,
    val sampleCount: Long,
    val coveredFraction: Double,
    val forwardStatus: InversionStatus,
    val reverseStatus: InversionStatus
)

object InversePairEvidence:
  def create(
      maximumRoundTripResidual: Double,
      sampleCount: Long,
      coveredFraction: Double,
      forwardStatus: InversionStatus,
      reverseStatus: InversionStatus
  ): Either[EvidenceError, InversePairEvidence] =
    for
      _ <- EvidenceValidation.nonNegativeFinite(
        EvidenceMetric.MaximumRoundTripResidual,
        maximumRoundTripResidual
      )
      _ <-
        if sampleCount > 0 then Right(())
        else
          Left(
            EvidenceError.NonPositiveCount(
              EvidenceCount.Samples,
              sampleCount
            )
          )
      _ <- EvidenceValidation.fraction(
        EvidenceFraction.Covered,
        coveredFraction
      )
    yield new InversePairEvidence(
      maximumRoundTripResidual,
      sampleCount,
      coveredFraction,
      forwardStatus,
      reverseStatus
    )

/** Two independently represented maps that passed stated numerical checks.
  *
  * Certification supplies finite evidence only; it does not manufacture an
  * analytic inverse and therefore this class is not a `SmoothIso`.
  */
final class CertifiedBidirectionalPair[
    From <: Frame[D],
    To <: Frame[D],
    D <: Dim
] private (
    val toTarget: SpatialMap[From, To, D],
    val toSource: SpatialMap[To, From, D],
    val evidence: InversePairEvidence,
    val forwardDomain: InverseDomain[From, D],
    val reverseDomain: InverseDomain[To, D],
    val criteria: InverseCriteria,
    val implementationRevision: ImplementationRevision
)

object CertifiedBidirectionalPair:
  def fromReportedEvidence[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      toTarget: SpatialMap[From, To, D],
      toSource: SpatialMap[To, From, D],
      evidence: InversePairEvidence,
      forwardDomain: InverseDomain[From, D],
      reverseDomain: InverseDomain[To, D],
      criteria: InverseCriteria,
      implementationRevision: ImplementationRevision
  ): Either[EvidenceError, CertifiedBidirectionalPair[From, To, D]] =
    if !evidence.forwardStatus.converged then
      Left(
        EvidenceError.InverseDidNotConverge(
          InverseDirection.Forward,
          evidence.forwardStatus.outcome
        )
      )
    else if !evidence.reverseStatus.converged then
      Left(
        EvidenceError.InverseDidNotConverge(
          InverseDirection.Reverse,
          evidence.reverseStatus.outcome
        )
      )
    else if evidence.maximumRoundTripResidual > criteria.maximumResidual then
      Left(
        EvidenceError.ResidualExceedsCriterion(
          evidence.maximumRoundTripResidual,
          criteria.maximumResidual
        )
      )
    else if evidence.coveredFraction < criteria.minimumCoveredFraction then
      Left(
        EvidenceError.CoverageBelowCriterion(
          evidence.coveredFraction,
          criteria.minimumCoveredFraction
        )
      )
    else
      for
        _ <- EvidenceValidation.sourceEndpoint(
          toTarget.source,
          forwardDomain.frame
        )
        _ <- EvidenceValidation.resultEndpoint(
          toTarget.target,
          reverseDomain.frame
        )
        _ <- EvidenceValidation.sourceEndpoint(
          toSource.source,
          reverseDomain.frame
        )
        _ <- EvidenceValidation.resultEndpoint(
          toSource.target,
          forwardDomain.frame
        )
      yield
        new CertifiedBidirectionalPair(
          toTarget,
          toSource,
          evidence,
          forwardDomain,
          reverseDomain,
          criteria,
          implementationRevision
        )

sealed trait CellSamplingRule derives CanEqual

object CellSamplingRule:
  case object CellCorners extends CellSamplingRule
  case object CellCornersAndCenter extends CellSamplingRule

  final class Subdivision private[CellSamplingRule] (
      val levels: Int
  ) extends CellSamplingRule

  def subdivision(levels: Int): Either[EvidenceError, CellSamplingRule] =
    if levels > 0 then Right(new Subdivision(levels))
    else
      Left(
        EvidenceError.NonPositiveCount(
          EvidenceCount.SubdivisionLevels,
          levels.toLong
        )
      )

  def record(rule: CellSamplingRule): CellSamplingRuleRecord =
    rule match
      case CellCorners          => CellSamplingRuleRecord.CellCorners
      case CellCornersAndCenter =>
        CellSamplingRuleRecord.CellCornersAndCenter
      case subdivision: Subdivision =>
        CellSamplingRuleRecord.Subdivision(subdivision.levels)

  def restore(
      record: CellSamplingRuleRecord
  ): Either[EvidenceError, CellSamplingRule] =
    record match
      case CellSamplingRuleRecord.CellCorners =>
        Right(CellCorners)
      case CellSamplingRuleRecord.CellCornersAndCenter =>
        Right(CellCornersAndCenter)
      case CellSamplingRuleRecord.Subdivision(levels) =>
        subdivision(levels)

enum CellSamplingRuleRecord derives CanEqual:
  case CellCorners
  case CellCornersAndCenter
  case Subdivision(levels: Int)

final class TopologyCriteria private (
    val minimumCoveredFraction: Double,
    val maximumFoldedCells: Long
):
  def record: TopologyCriteriaRecord =
    TopologyCriteriaRecord(minimumCoveredFraction, maximumFoldedCells)

final case class TopologyCriteriaRecord(
    minimumCoveredFraction: Double,
    maximumFoldedCells: Long
) derives CanEqual

object TopologyCriteria:
  def create(
      minimumCoveredFraction: Double,
      maximumFoldedCells: Long
  ): Either[EvidenceError, TopologyCriteria] =
    for
      _ <- EvidenceValidation.fraction(
        EvidenceFraction.MinimumCovered,
        minimumCoveredFraction
      )
      _ <-
        if maximumFoldedCells >= 0 then Right(())
        else
          Left(
            EvidenceError.NegativeCount(
              EvidenceCount.MaximumFoldedCells,
              maximumFoldedCells
            )
          )
    yield new TopologyCriteria(minimumCoveredFraction, maximumFoldedCells)

final class TopologyScope[F <: Frame[D], D <: Dim] private (
    val gridKey: GridKey,
    val resolution: Vector[Int],
    val region: IndexRegion[D],
    val samplingRule: CellSamplingRule,
    val determinantThreshold: Double,
    val criteria: TopologyCriteria,
    val implementationRevision: ImplementationRevision
):
  def record: TopologyScopeRecord =
    TopologyScopeRecord(
      gridKey,
      resolution,
      region.record,
      CellSamplingRule.record(samplingRule),
      determinantThreshold,
      criteria.record,
      implementationRevision.value
    )

  def appliesTo(
      requestedGrid: Grid[F, D],
      requested: TopologyScopeRecord
  ): Either[EvidenceError, Unit] =
    if requested != record then
      Left(EvidenceError.ScopeMismatch(record, requested))
    else
      requestedGrid.persistentKey match
        case None =>
          Left(EvidenceError.PersistentGridRequired)
        case Some(key) if key != gridKey =>
          Left(EvidenceError.GridMismatch(gridKey, key))
        case Some(_) =>
          Right(())

final case class TopologyScopeRecord(
    gridKey: GridKey,
    resolution: Vector[Int],
    region: IndexRegionRecord,
    samplingRule: CellSamplingRuleRecord,
    determinantThreshold: Double,
    criteria: TopologyCriteriaRecord,
    implementationRevision: String
) derives CanEqual

object TopologyScope:
  def on[F <: Frame[D], D <: Dim](
      grid: Grid[F, D],
      region: IndexRegion[D],
      samplingRule: CellSamplingRule,
      determinantThreshold: Double,
      criteria: TopologyCriteria,
      implementationRevision: ImplementationRevision
  ): Either[EvidenceError, TopologyScope[F, D]] =
    grid.persistentKey match
      case None =>
        Left(EvidenceError.PersistentGridRequired)
      case Some(_) if region.resolution != grid.shape =>
        Left(EvidenceError.ResolutionMismatch(grid.shape, region.resolution))
      case Some(key) =>
        EvidenceValidation
          .nonNegativeFinite(
            EvidenceMetric.DeterminantThreshold,
            determinantThreshold
          )
          .map(_ =>
            new TopologyScope(
              key,
              grid.shape,
              region,
              samplingRule,
              determinantThreshold,
              criteria,
              implementationRevision
            )
          )

  def restore[F <: Frame[D], D <: Dim](
      record: TopologyScopeRecord,
      grid: Grid[F, D]
  )(using Dimension[D]): Either[EvidenceError, TopologyScope[F, D]] =
    grid.persistentKey match
      case None =>
        Left(EvidenceError.PersistentGridRequired)
      case Some(key) if record.gridKey != key =>
        Left(EvidenceError.GridMismatch(key, record.gridKey))
      case Some(_) =>
        for
          region <- IndexRegion.restore[D](record.region)
          samplingRule <- CellSamplingRule.restore(record.samplingRule)
          criteria <- TopologyCriteria.create(
            record.criteria.minimumCoveredFraction,
            record.criteria.maximumFoldedCells
          )
          revision <- ImplementationRevision.parse(
            record.implementationRevision
          )
          scope <- on(
            grid,
            region,
            samplingRule,
            record.determinantThreshold,
            criteria,
            revision
          )
          _ <-
            if scope.record == record then Right(())
            else Left(EvidenceError.ScopeMismatch(record, scope.record))
        yield scope

final class TopologyDiagnostics private (
    val minimumCellJacobian: Double,
    val maximumCellJacobian: Double,
    val foldedCellCount: Long,
    val coveredDomainFraction: Double,
    val sampledCellCount: Long
):
  def record: TopologyDiagnosticsRecord =
    TopologyDiagnosticsRecord(
      minimumCellJacobian,
      maximumCellJacobian,
      foldedCellCount,
      coveredDomainFraction,
      sampledCellCount
    )

final case class TopologyDiagnosticsRecord(
    minimumCellJacobian: Double,
    maximumCellJacobian: Double,
    foldedCellCount: Long,
    coveredDomainFraction: Double,
    sampledCellCount: Long
) derives CanEqual

object TopologyDiagnostics:
  def create(
      minimumCellJacobian: Double,
      maximumCellJacobian: Double,
      foldedCellCount: Long,
      coveredDomainFraction: Double,
      sampledCellCount: Long
  ): Either[EvidenceError, TopologyDiagnostics] =
    for
      _ <- EvidenceValidation.finite(
        EvidenceMetric.MinimumCellJacobian,
        minimumCellJacobian
      )
      _ <- EvidenceValidation.finite(
        EvidenceMetric.MaximumCellJacobian,
        maximumCellJacobian
      )
      _ <-
        if minimumCellJacobian <= maximumCellJacobian then Right(())
        else Left(
          EvidenceError.InvalidJacobianRange(
            minimumCellJacobian,
            maximumCellJacobian
          )
        )
      _ <-
        if foldedCellCount >= 0 then Right(())
        else
          Left(
            EvidenceError.NegativeCount(
              EvidenceCount.FoldedCells,
              foldedCellCount
            )
          )
      _ <- EvidenceValidation.fraction(
        EvidenceFraction.CoveredDomain,
        coveredDomainFraction
      )
      _ <-
        if sampledCellCount > 0 then Right(())
        else
          Left(
            EvidenceError.NonPositiveCount(
              EvidenceCount.Samples,
              sampledCellCount
            )
          )
      _ <-
        if foldedCellCount <= sampledCellCount then Right(())
        else
          Left(
            EvidenceError.FoldedCellsExceedSamples(
              foldedCellCount,
              sampledCellCount
            )
          )
    yield new TopologyDiagnostics(
      minimumCellJacobian,
      maximumCellJacobian,
      foldedCellCount,
      coveredDomainFraction,
      sampledCellCount
    )

/** Evidence for one finite topology assessment, never a global proof. */
final class TopologyCertificate[F <: Frame[D], D <: Dim] private (
    val scope: TopologyScope[F, D],
    val diagnostics: TopologyDiagnostics
):
  def record: TopologyCertificateRecord =
    TopologyCertificateRecord(scope.record, diagnostics.record)

  def appliesTo(
      grid: Grid[F, D],
      requested: TopologyScopeRecord
  ): Either[EvidenceError, Unit] =
    scope.appliesTo(grid, requested)

final case class TopologyCertificateRecord(
    scope: TopologyScopeRecord,
    diagnostics: TopologyDiagnosticsRecord
) derives CanEqual

object TopologyCertificate:
  /** Threshold-check caller-reported diagnostics for one exact finite scope.
    *
    * This validates internal consistency and criteria; it does not authenticate
    * how the diagnostics were measured.
    */
  def fromReportedDiagnostics[F <: Frame[D], D <: Dim](
      scope: TopologyScope[F, D],
      diagnostics: TopologyDiagnostics
  ): Either[EvidenceError, TopologyCertificate[F, D]] =
    if diagnostics.minimumCellJacobian < scope.determinantThreshold then
      Left(
        EvidenceError.DeterminantBelowThreshold(
          diagnostics.minimumCellJacobian,
          scope.determinantThreshold
        )
      )
    else if
      diagnostics.foldedCellCount > scope.criteria.maximumFoldedCells
    then
      Left(
        EvidenceError.TooManyFoldedCells(
          diagnostics.foldedCellCount,
          scope.criteria.maximumFoldedCells
        )
      )
    else if
      diagnostics.coveredDomainFraction <
        scope.criteria.minimumCoveredFraction
    then
      Left(
        EvidenceError.CoverageBelowCriterion(
          diagnostics.coveredDomainFraction,
          scope.criteria.minimumCoveredFraction
        )
      )
    else Right(new TopologyCertificate(scope, diagnostics))

  def restore[F <: Frame[D], D <: Dim](
      record: TopologyCertificateRecord,
      grid: Grid[F, D]
  )(using Dimension[D]): Either[EvidenceError, TopologyCertificate[F, D]] =
    for
      scope <- TopologyScope.restore(record.scope, grid)
      diagnostics <- TopologyDiagnostics.create(
        record.diagnostics.minimumCellJacobian,
        record.diagnostics.maximumCellJacobian,
        record.diagnostics.foldedCellCount,
        record.diagnostics.coveredDomainFraction,
        record.diagnostics.sampledCellCount
      )
      certificate <- fromReportedDiagnostics(scope, diagnostics)
    yield certificate

private object EvidenceValidation:
  def finite(
      field: EvidenceMetric,
      value: Double
  ): Either[EvidenceError, Unit] =
    if value.isFinite then Right(())
    else Left(EvidenceError.InvalidMetric(field, value))

  def nonNegativeFinite(
      field: EvidenceMetric,
      value: Double
  ): Either[EvidenceError, Unit] =
    finite(field, value).flatMap(_ =>
      if value >= 0.0 then Right(())
      else Left(EvidenceError.NegativeMetric(field, value))
    )

  def fraction(
      field: EvidenceFraction,
      value: Double
  ): Either[EvidenceError, Unit] =
    if value.isFinite && value >= 0.0 && value <= 1.0 then Right(())
    else Left(EvidenceError.InvalidFraction(field, value))

  def resolution(values: Vector[Int]): Either[EvidenceError, Unit] =
    values.zipWithIndex.collectFirst {
      case (value, axis) if value <= 0 =>
        EvidenceError.InvalidResolution(axis, value)
    }.toLeft(())

  def sourceEndpoint[F <: Frame[D], D <: Dim](
      expected: F,
      actual: F
  ): Either[EvidenceError, Unit] =
    SpatialMap
      .validateSourceFrame(expected, actual)
      .left
      .map(EvidenceError.EndpointMismatch.apply)

  def resultEndpoint[F <: Frame[D], D <: Dim](
      expected: F,
      actual: F
  ): Either[EvidenceError, Unit] =
    SpatialMap
      .validateResultFrame(expected, actual)
      .left
      .map(EvidenceError.EndpointMismatch.apply)
