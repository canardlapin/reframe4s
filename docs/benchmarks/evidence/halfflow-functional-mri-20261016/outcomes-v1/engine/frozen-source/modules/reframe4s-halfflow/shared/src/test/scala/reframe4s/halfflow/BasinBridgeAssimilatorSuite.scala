package reframe4s.halfflow

import ravel.NDArray as RavelArray
import reframe4s.halfflow.internal.*

class BasinBridgeAssimilatorSuite extends munit.FunSuite:
  sealed trait Work
  sealed trait Fixed
  sealed trait Moving

  test("constant 12 mm tangent backtracks past boundary folds before accepting"):
    val grid = GridSpec.identity(Vector(33, 33, 33))
    val work = RegistrationFrame[Work](SpatialDomainId("b3-work"), grid)
    val fixed = RegistrationFrame[Fixed](SpatialDomainId("b3-fixed"), grid)
    val moving = RegistrationFrame[Moving](SpatialDomainId("b3-moving"), grid)
    val initial = right(ForwardMidpoint.identity(work, fixed, moving))
    val correspondences = Vector(
      correspondence(point(10.0, 8.0, 8.0), point(12.0, 0.0, 0.0)),
      correspondence(point(16.0, 16.0, 16.0), point(12.0, 0.0, 0.0)),
      correspondence(point(22.0, 24.0, 24.0), point(12.0, 0.0, 0.0))
    )
    val projected = right(
      BasinBridgeProjector.project(
        correspondences,
        grid,
        right(BasinBridgeProjectorConfig.make(sigmaMm = 1.5))
      )
    )
    val velocity = right(Velocity.make(work, projected.field))
    val objective = correspondenceObjective[Fixed, Moving](correspondences)
    val result = right(
      BasinBridgeAssimilator.assimilate(
        initial,
        velocity,
        correspondences,
        objective
      )
    )
    assert(result.accepted)
    assertEqualsDouble(result.initialAlpha, 1.0, 1e-12)
    assertEqualsDouble(
      result.acceptedAlpha.getOrElse(Double.NaN),
      0.25,
      0.0,
      "boundary-safe alpha"
    )
    assertEquals(result.trials.length, 3)
    assert(result.trials.take(2).forall(_.rejection.exists(
      _.isInstanceOf[BasinBridgeTrialRejection.IncrementalGeometry])))
    assert(result.trials.last.accepted)
    val finalError = right(BasinBridgeObjective.correspondenceMatchError(result.state, correspondences))
    assertEqualsDouble(finalError, 9.0, 1e-4)
    val acceptedGeometry = ForwardGeometryConfig(interiorMargin = 8)
    val (verdict, reports) = ForwardGeometry.accumulated(result.state, acceptedGeometry)
    assertEquals(verdict, GeometryVerdict.Valid)
    assert(reports.forall(_.cells.exists(_.acceptable(0.05, 0.95))))
    val minimum = reports.map(_.minimum).min
    assertEqualsDouble(minimum, 1.0, 1e-10)

    val fineGrid = result.state.work.grid.withGeometry(Vector(35, 35, 35), image4s.geometry.Affine.identity[image4s.geometry.D3])
    val regridded = right(
      result.state.regrid(
        RegistrationFrame[Work](SpatialDomainId("b3-work"), fineGrid),
        RegistrationFrame[Fixed](SpatialDomainId("b3-fixed"), fineGrid),
        RegistrationFrame[Moving](SpatialDomainId("b3-moving"), fineGrid)
      )
    )
    val (regridVerdict, regridReports) = ForwardGeometry.accumulated(
      regridded,
      acceptedGeometry.copy(interiorMargin = 10)
    )
    assert(regridReports.forall(report => report.minimum.isFinite && report.minimum > 0.99))
    assertEquals(regridVerdict, GeometryVerdict.Valid)
    assert(regridReports.forall(_.cells.exists(_.acceptable(0.05, 0.95))))

  test("compressive field exhausts the declared trials without accepting boundary folds"):
    val grid = GridSpec.identity(Vector(33, 33, 33))
    val work = RegistrationFrame[Work](SpatialDomainId("b3-compress-work"), grid)
    val fixed = RegistrationFrame[Fixed](SpatialDomainId("b3-compress-fixed"), grid)
    val moving = RegistrationFrame[Moving](SpatialDomainId("b3-compress-moving"), grid)
    val initial = right(ForwardMidpoint.identity(work, fixed, moving))
    val center = 16.0
    val rate = -1.4
    val velocity = right(Velocity.make(work, field(grid) { (x, _, _) =>
      point(rate * (x - center), 0.0, 0.0)
    }))
    val correspondences = Vector(8.0, 12.0, 16.0, 20.0, 24.0).map: x =>
      val tangent = point(rate * (x - center), 0.0, 0.0)
      correspondence(point(x, 16.0, 16.0), tangent)
    val config = right(
      BasinBridgeAssimilationConfig.make(
        targetSymmetricStrain = 10.0,
        minimumMatchDropMm = 1e-4,
        maximumTrials = 4,
        geometry = ForwardGeometryConfig(
          minimumIncrementJacobian = 0.5,
          minimumAccumulatedJacobian = 0.5
        ),
        flow = FlowConfig()
      )
    )
    val result = right(
      BasinBridgeAssimilator.assimilate(
        initial,
        velocity,
        correspondences,
        correspondenceObjective[Fixed, Moving](correspondences),
        config
      )
    )
    assert(!result.accepted)
    assert(result.state eq initial)
    assertEqualsDouble(result.initialAlpha, 1.0, 1e-12)
    assertEquals(result.acceptedAlpha, None)
    assertEquals(result.trials.length, 4)
    assert(result.trials.forall(trial => !trial.accepted && trial.rejection.exists(
      _.isInstanceOf[BasinBridgeTrialRejection.IncrementalGeometry])))
    val (_, reports) = ForwardGeometry.accumulated(result.state, ForwardGeometryConfig(interiorMargin = 8))
    assert(reports.forall(report => report.minimum.isFinite && report.minimum >= 0.05))

  test("CC increase rejects every candidate and leaves the state untouched"):
    val grid = GridSpec.identity(Vector(25, 25, 25))
    val work = RegistrationFrame[Work](SpatialDomainId("b3-reject-work"), grid)
    val fixed = RegistrationFrame[Fixed](SpatialDomainId("b3-reject-fixed"), grid)
    val moving = RegistrationFrame[Moving](SpatialDomainId("b3-reject-moving"), grid)
    val initial = right(ForwardMidpoint.identity(work, fixed, moving))
    val correspondences = Vector(
      correspondence(point(10.0, 8.0, 8.0), point(12.0, 0.0, 0.0)),
      correspondence(point(16.0, 16.0, 16.0), point(12.0, 0.0, 0.0))
    )
    val velocity = right(Velocity.make(work, constantField(grid, 12.0, 0.0, 0.0)))
    val objective = (candidate: ForwardMidpoint[Work, Fixed, Moving]) =>
      BasinBridgeObjective.correspondenceMatchError(candidate, correspondences).flatMap: matchError =>
        BasinBridgeObjective.make(
          matchError,
          if matchError < 11.9 then 1.0 else 0.0
        )
    val config = right(
      BasinBridgeAssimilationConfig.make(
        minimumAlpha = 1.0 / 16.0,
        maximumTrials = 5
      )
    )
    val result = right(
      BasinBridgeAssimilator.assimilate(
        initial,
        velocity,
        correspondences,
        objective,
        config
      )
    )

    assert(!result.accepted)
    assert(result.state eq initial)
    assert(result.trials.nonEmpty)
    assert(result.trials.forall(!_.accepted))
    assert(result.trials.exists(_.rejection.exists(_.isInstanceOf[BasinBridgeTrialRejection.CcLossIncreased])))

  test("swapping endpoints and negating the tangent preserves the accepted alpha"):
    val grid = GridSpec.identity(Vector(25, 25, 25))
    val work = RegistrationFrame[Work](SpatialDomainId("b3-swap-work"), grid)
    val fixed = RegistrationFrame[Fixed](SpatialDomainId("b3-swap-fixed"), grid)
    val moving = RegistrationFrame[Moving](SpatialDomainId("b3-swap-moving"), grid)
    val initial = right(ForwardMidpoint.identity(work, fixed, moving))
    val correspondences = Vector(
      correspondence(point(10.0, 8.0, 8.0), point(12.0, 0.0, 0.0)),
      correspondence(point(16.0, 16.0, 16.0), point(12.0, 0.0, 0.0))
    )
    val reverseCorrespondences = correspondences.map(_.swapped)
    val forwardVelocity = right(Velocity.make(work, constantField(grid, 12.0, 0.0, 0.0)))
    val reverseVelocity = right(Velocity.make(work, constantField(grid, -12.0, 0.0, 0.0)))
    val forward = right(
      BasinBridgeAssimilator.assimilate(
        initial,
        forwardVelocity,
        correspondences,
        correspondenceObjective[Fixed, Moving](correspondences)
      )
    )
    val reverse = right(
      BasinBridgeAssimilator.assimilate(
        initial.swap,
        reverseVelocity,
        reverseCorrespondences,
        correspondenceObjective[Moving, Fixed](reverseCorrespondences)
      )
    )

    assertEqualsDouble(
      forward.acceptedAlpha.getOrElse(Double.NaN),
      reverse.acceptedAlpha.getOrElse(Double.NaN),
      0.0,
      "swap alpha"
    )
    val forwardPair: InversePair[Fixed, Moving] = right(ForwardMidpointExporter.inspect(forward.state)).transform
    val reversePair: InversePair[Moving, Fixed] = right(ForwardMidpointExporter.inspect(reverse.state)).transform
    assertPullClose(forwardPair.forward, reversePair.backward, 1e-10)

  private def correspondenceObjective[F, M](
      correspondences: Vector[BasinBridgeCorrespondence]
  ): ForwardMidpoint[Work, F, M] => Either[RegistrationError, BasinBridgeObjective] =
    (state: ForwardMidpoint[Work, F, M]) =>
      BasinBridgeObjective.correspondenceMatchError(state, correspondences).flatMap: matchError =>
        BasinBridgeObjective.make(matchError, matchError)

  private def correspondence(
      midpoint: BasinBridgePoint,
      tangent: BasinBridgePoint
  ): BasinBridgeCorrespondence =
    right(
      BasinBridgeCorrespondence.make(
        midpoint - tangent * 0.5,
        midpoint + tangent * 0.5,
        1.0
      )
    )

  private def point(x: Double, y: Double, z: Double): BasinBridgePoint =
    right(BasinBridgePoint.make(x, y, z))

  private def constantField(grid: GridSpec, x: Double, y: Double, z: Double): DenseVectorField =
    field(grid)((_, _, _) => point(x, y, z))

  private def field(
      grid: GridSpec
  )(
      value: (Double, Double, Double) => BasinBridgePoint
  ): DenseVectorField =
    DenseVectorField(
      grid,
      RavelArray.tabulate[Double](grid.shape(0), grid.shape(1), grid.shape(2), 3) { (x, y, z, component) =>
        val point = value(x.toDouble, y.toDouble, z.toDouble)
        component match
          case 0 => point.x
          case 1 => point.y
          case _ => point.z
      },
      DenseVectorFieldKind.Displacement
    )

  private def assertPullClose[A, B](
      actual: DensePull[A, B],
      expected: DensePull[A, B],
      tolerance: Double
  ): Unit =
    assertEquals(actual.from.grid, expected.from.grid)
    var index = 0
    while index < actual.from.grid.nVoxels do
      var component = 0
      while component < 3 do
        assertEqualsDouble(
          actual.sourceCoordinates.linearComponent(index, component),
          expected.sourceCoordinates.linearComponent(index, component),
          tolerance
        )
        component += 1
      index += 1

  private def right[A](value: Either[?, A]): A =
    value match
      case Right(result) => result
      case Left(error) => fail(error.toString)
