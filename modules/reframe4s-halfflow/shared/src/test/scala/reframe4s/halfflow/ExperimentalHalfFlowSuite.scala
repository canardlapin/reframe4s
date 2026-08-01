package reframe4s.halfflow

import scala.compiletime.testing.typeCheckErrors

import image4s.Axis
import image4s.AxisKind
import image4s.ImageError
import image4s.NonSpatialAxes
import image4s.Sampled
import ravel.DType.given
import ravel.NDArray
import ravel.Rank
import ravel.Shape
import reframe4s.core.CellSamplingRule
import reframe4s.core.CertifiedBidirectionalPair
import reframe4s.core.EvidenceError
import reframe4s.core.ImplementationRevision
import reframe4s.core.IndexRegion
import reframe4s.core.InverseCriteria
import reframe4s.core.InverseDomain
import reframe4s.core.InversePairEvidence
import reframe4s.core.InversionStatus
import reframe4s.core.TopologyCriteria
import reframe4s.core.TopologyScope
import reframe4s.field.DenseMap
import reframe4s.field.FieldError
import reframe4s.field.TopologyAssessmentError
import reframe4s.field.TopologyAssessor
import image4s.geometry.Affine
import image4s.geometry.D2
import image4s.geometry.Frame
import image4s.geometry.FrameId
import image4s.geometry.GeometryError
import image4s.geometry.Grid
import image4s.geometry.GridId
import reframe4s.register.OptimizationReport
import reframe4s.register.RegistrationFailure
import reframe4s.register.Termination

final class ExperimentalHalfFlowSuite extends munit.FunSuite:
  test("an insufficient anatomical ledger rejects an otherwise valid candidate"):
    val fixed =
      geometryRight(
        Frame.persistentNamed[D2](
          geometryRight(FrameId.parse("halfflow-fixed-frame")),
          "halfflow-fixed"
        )
      )
    val moving =
      geometryRight(
        Frame.persistentNamed[D2](
          geometryRight(FrameId.parse("halfflow-moving-frame")),
          "halfflow-moving"
        )
      )
    val fixedGrid =
      geometryRight(
        Grid.createPersistent[D2, fixed.type](
          geometryRight(GridId.parse("halfflow-fixed-grid")),
          fixed
        )(Vector(3, 3), Affine.identity[D2])
      )
    val movingGrid =
      geometryRight(
        Grid.createPersistent[D2, moving.type](
          geometryRight(GridId.parse("halfflow-moving-grid")),
          moving
        )(Vector(3, 3), Affine.identity[D2])
      )
    val fixedToMoving =
      identityCoordinates[fixed.type, moving.type](fixedGrid, moving)
    val movingToFixed =
      identityCoordinates[moving.type, fixed.type](movingGrid, fixed)
    val fixedScope = topologyScope(fixedGrid)
    val movingScope = topologyScope(movingGrid)
    val fixedCertificate =
      topologyRight(TopologyAssessor.certify(fixedToMoving, fixedScope))
    val movingCertificate =
      topologyRight(TopologyAssessor.certify(movingToFixed, movingScope))
    val fixedRegion = fullRegion(fixedGrid)
    val movingRegion = fullRegion(movingGrid)
    val criteria = evidenceRight(InverseCriteria.create(1e-10, 1.0))
    val status = evidenceRight(InversionStatus.converged(1))
    val pairEvidence =
      evidenceRight(
        InversePairEvidence.create(
          maximumRoundTripResidual = 0.0,
          sampleCount = 9L,
          coveredFraction = 1.0,
          forwardStatus = status,
          reverseStatus = status
        )
      )
    val revision =
      evidenceRight(ImplementationRevision.parse("halfflow-suite-v1"))
    val pair =
      evidenceRight(
        CertifiedBidirectionalPair.fromReportedEvidence(
          fixedToMoving,
          movingToFixed,
          pairEvidence,
          evidenceRight(InverseDomain.on(fixedGrid, fixedRegion)),
          evidenceRight(InverseDomain.on(movingGrid, movingRegion)),
          criteria,
          revision
        )
      )
    val report =
      registrationRight(
        OptimizationReport.create(
          initialObjective = 1.0,
          finalObjective = 0.5,
          iterations = 1,
          attempts = 1,
          acceptedSteps = 1,
          termination = Termination.Converged
        )
      )
    val candidate =
      HalfFlowCandidate(
        pair,
        fixedCertificate,
        movingCertificate,
        report
      )
    val ledger =
      experimentalRight(
        ExperimentalCapabilityLedger.create(
          "synthetic-only",
          observedPairs = 0,
          requiredPairs = 20,
          EvidenceStatus.NotRun
        )
      )

    HalfFlowAdmission.admit(candidate, ledger) match
      case Left(
            ExperimentalError.EvidenceNotAdmissible(
              EvidenceStatus.NotRun,
              0,
              20
            )
          ) =>
        assert(!ledger.admitsStableExport)
      case other =>
        fail(s"expected experimental evidence rejection, got $other")

  test("HalfFlow evidence cannot promote a numerical map to SmoothIso"):
    val errors =
      typeCheckErrors(
        """
import reframe4s.core.*
import image4s.geometry.*
import reframe4s.halfflow.*

def unsoundPromotion[
    Fixed <: Frame[D],
    Moving <: Frame[D],
    D <: Dim
](
    candidate: HalfFlowCandidate[Fixed, Moving, D]
): SmoothIso[Fixed, Moving, D] =
  candidate.pair.toTarget
"""
      )

    assert(errors.nonEmpty)

  private def identityCoordinates[
      From <: Frame[D2],
      To <: Frame[D2]
  ](
      grid: Grid[From, D2],
      target: To
  ): DenseMap[From, To, D2, Rank[3]] =
    val axis = imageRight(Axis.create("component", 2, AxisKind.Direction))
    val axes = imageRight(NonSpatialAxes.from(Vector(axis)))
    val values =
      for
        i <- 0 until grid.shape(0)
        j <- 0 until grid.shape(1)
        component <- 0 until 2
      yield if component == 0 then i.toDouble else j.toDouble
    val samples =
      imageRight(
        Sampled.continuous(
          grid,
          axes,
          NDArray.fromSeq(
            Shape(grid.shape(0), grid.shape(1), 2),
            values
          )
        )
      )
    fieldRight(DenseMap.fromCoordinates(samples, target))

  private def topologyScope[F <: Frame[D2]](
      grid: Grid[F, D2]
  ): TopologyScope[F, D2] =
    val criteria = evidenceRight(TopologyCriteria.create(1.0, 0L))
    val revision =
      evidenceRight(ImplementationRevision.parse("halfflow-topology-v1"))
    evidenceRight(
      TopologyScope.on(
        grid,
        fullRegion(grid),
        CellSamplingRule.CellCornersAndCenter,
        determinantThreshold = 0.01,
        criteria,
        revision
      )
    )

  private def fullRegion[F <: Frame[D2]](
      grid: Grid[F, D2]
  ): IndexRegion[D2] =
    evidenceRight(
      IndexRegion.within[D2](
        grid.shape,
        Vector(0, 0),
        grid.shape
      )
    )

  private def geometryRight[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def imageRight[A](value: Either[ImageError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def fieldRight[A](value: Either[FieldError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def evidenceRight[A](value: Either[EvidenceError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def topologyRight[A](
      value: Either[TopologyAssessmentError, A]
  ): A =
    value.fold(error => fail(error.message), identity)

  private def registrationRight[A](
      value: Either[RegistrationFailure, A]
  ): A =
    value.fold(error => fail(error.message), identity)

  private def experimentalRight[A](
      value: Either[ExperimentalError, A]
  ): A =
    value.fold(error => fail(error.message), identity)
