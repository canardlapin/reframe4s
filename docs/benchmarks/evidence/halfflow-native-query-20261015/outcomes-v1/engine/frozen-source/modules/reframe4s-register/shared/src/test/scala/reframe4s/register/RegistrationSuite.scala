package reframe4s.register

import gale.linalg.DMat
import reframe4s.core.Jet1
import reframe4s.core.MapError
import reframe4s.core.SmoothIso
import reframe4s.core.SpatialMap
import image4s.geometry.D2
import image4s.geometry.Dim
import image4s.geometry.Dimension
import image4s.geometry.Frame
import image4s.geometry.GeometryError
import image4s.geometry.Point

import scala.compiletime.testing.typeCheckErrors

final class RegistrationSuite extends munit.FunSuite:
  test("one-way results expose only the declared fixed-to-moving capability"):
    val fixed = geometry(Frame.named[D2]("fixed"))
    val moving = geometry(Frame.named[D2]("moving"))
    val map =
      new TranslationMap[fixed.type, moving.type, D2](
        fixed,
        moving,
        Vector(2.0, -1.0)
      )
    val result =
      RegistrationResult
        .oneWay[fixed.type, moving.type, D2, map.type](map, report)
        .fold(error => fail(error.message), identity)
    val transformed =
      mapped(
        result.fixedToMoving(
          geometry(Point.in[D2](fixed)(1.0, 3.0))
        )
      )

    assertEquals(transformed.coordinates, Vector(3.0, 2.0))

    val errors = typeCheckErrors(
      """
import image4s.geometry.*
import reframe4s.register.*
def reverse[
  F <: Frame[D],
  M <: Frame[D],
  D <: Dim
](result: RegistrationResult[F, M, D]) =
  result.movingToFixed
"""
    )
    assert(errors.nonEmpty)

  test("exact results expose the analytic moving-to-fixed inverse"):
    val fixed = geometry(Frame.named[D2]("fixed"))
    val moving = geometry(Frame.named[D2]("moving"))
    val map =
      new TranslationIso[fixed.type, moving.type, D2](
        fixed,
        moving,
        Vector(4.0, -3.0)
      )
    val result =
      RegistrationResult
        .exact[fixed.type, moving.type, D2, map.type](map, report)
        .fold(error => fail(error.message), identity)
    val movingPoint = geometry(Point.in[D2](moving)(9.0, 2.0))
    val fixedPoint = mapped(result.movingToFixed(movingPoint))
    val roundTrip = mapped(result.fixedToMoving(fixedPoint))

    assertEquals(fixedPoint.coordinates, Vector(5.0, 5.0))
    assertEquals(roundTrip.coordinates, movingPoint.coordinates)

  test("registration results define no ambiguous forward or inverse aliases"):
    val forwardErrors = typeCheckErrors(
      """
import image4s.geometry.*
import reframe4s.register.*
def ambiguous[
  F <: Frame[D],
  M <: Frame[D],
  D <: Dim
](result: RegistrationResult[F, M, D]) =
  result.forward
"""
    )
    val inverseErrors = typeCheckErrors(
      """
import image4s.geometry.*
import reframe4s.register.*
def ambiguous[
  F <: Frame[D],
  M <: Frame[D],
  D <: Dim
](result: BidirectionalRegistrationResult[F, M, D]) =
  result.inverse
"""
    )

    assert(forwardErrors.nonEmpty)
    assert(inverseErrors.nonEmpty)

  test("optimization reports retain exact termination and rejection counts"):
    val measured =
      registration(
        OptimizationReport.create(
          initialObjective = 2.0,
          finalObjective = 0.75,
          iterations = 5,
          attempts = 8,
          acceptedSteps = 3,
          termination = Termination.GradientConverged
        )
      )

    assertEquals(measured.rejectedSteps, 5)
    assertEquals(measured.termination, Termination.GradientConverged)

  private lazy val report: OptimizationReport =
    registration(
      OptimizationReport.create(
        initialObjective = 2.0,
        finalObjective = 1.0,
        iterations = 3,
        attempts = 4,
        acceptedSteps = 3,
        termination = Termination.Converged
      )
    )

  private def geometry[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def mapped[A](value: Either[MapError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def registration[A](value: Either[RegistrationFailure, A]): A =
    value.fold(error => fail(error.message), identity)

  private final class TranslationMap[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      val source: From,
      val target: To,
      offset: Vector[Double]
  )(using Dimension[D]) extends SpatialMap[From, To, D]:
    def apply(point: Point[From, D]): Either[MapError, Point[To, D]] =
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        result <- Point
          .fromVector(
            target,
            point.coordinates.zip(offset).map(_ + _)
          )
          .left
          .map(MapError.Geometry.apply)
        alignment <- Frame
          .alignOwners[D, target.type, To](target, target)
          .left
          .map(MapError.Geometry.apply)
        rebound <- alignment
          .pointToRight(result)
          .left
          .map(MapError.Geometry.apply)
      yield rebound

  private final class TranslationIso[
      From <: Frame[D],
      To <: Frame[D],
      D <: Dim
  ](
      val source: From,
      val target: To,
      offset: Vector[Double]
  )(using dimension: Dimension[D]) extends SmoothIso[From, To, D]:
    def apply(point: Point[From, D]): Either[MapError, Point[To, D]] =
      for
        _ <- SpatialMap.validateSourcePoint(source, point)
        result <- Point
          .fromVector(
            target,
            point.coordinates.zip(offset).map(_ + _)
          )
          .left
          .map(MapError.Geometry.apply)
        alignment <- Frame
          .alignOwners[D, target.type, To](target, target)
          .left
          .map(MapError.Geometry.apply)
        rebound <- alignment
          .pointToRight(result)
          .left
          .map(MapError.Geometry.apply)
      yield rebound

    def jet1At(
        point: Point[From, D]
    ): Either[MapError, Jet1[From, To, D]] =
      apply(point).flatMap(result =>
        Jet1.create(result, DMat.eye(dimension.rank))
      )

    def inverse: SmoothIso[To, From, D] =
      new TranslationIso(target, source, offset.map(-_))
