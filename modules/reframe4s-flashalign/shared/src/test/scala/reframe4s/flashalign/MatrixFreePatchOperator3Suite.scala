package reframe4s.flashalign

import image4s.geometry.D3
import image4s.geometry.Frame
import image4s.geometry.GeometryError

final class MatrixFreePatchOperator3Suite extends munit.FunSuite:
  test("matrix-free products and signed RHS match explicit dense GN through 129 parameters"):
    Vector(6, 12, 64, 96, 129).foreach { parameterCount =>
      val fixture = makeFixture(parameterCount)
      val workspace = fixture.cache.newWorkspace()
      val directionValues = Array.tabulate(parameterCount)(index => math.sin(0.31 * (index + 1)))
      val direction = geometry(
        GeometryDirection3.create(
          fixture.operator.modelId,
          fixture.operator.basisId,
          directionValues
        )
      )
      val product = new Array[Double](parameterCount)
      matrixFree(fixture.cache.curvatureProduct(direction, product, workspace))
      val explicit = denseProduct(fixture, directionValues)
      assertVectorClose(product.toVector, explicit, 2e-10)

      val rhs = new Array[Double](parameterCount)
      matrixFree(fixture.cache.rightHandSide(rhs, workspace))
      assertVectorClose(rhs.toVector, denseRhs(fixture), 2e-10)
      assertEquals(workspace.curvatureProducts, 1L)
      assertEquals(fixture.cache.diagnostics.interpolationCallsPerProduct, 0L)
      assertEquals(fixture.cache.diagnostics.gradientCallsPerProduct, 0L)
      assertEquals(fixture.cache.diagnostics.productionDenseNormalBytes, 0L)
      assertEquals(
        fixture.cache.diagnostics.basisTableBytes,
        fixture.uniqueCount.toLong * parameterCount.toLong * 8L
      )
    }

  test("duplicate sample indices scatter exactly and invalid patches remain constant"):
    val fixture = makeFixture(12)
    val validOnly = fixture.patches.filter(_.invalidReason.isEmpty)
    assert(validOnly.exists(patch => patch.sampleIndices.distinct.length < patch.sampleIndices.length))
    assertEquals(fixture.cache.diagnostics.patches, 3)
    assertEquals(fixture.cache.diagnostics.validPatches, 2)
    val alteredInvalid = fixture.patches.updated(
      2,
      fixture.patches(2).copy(
        objectiveWeight = 1e12,
        inlierWeight = 1e12,
        signedWeight = -1e12
      )
    )
    val rebuilt = buildCache(fixture.operator, fixture.points, fixture.basis, alteredInvalid)
    val directionValues = Array.tabulate(12)(index => 0.01 * (index + 1))
    val direction = geometry(
      GeometryDirection3.create(fixture.operator.modelId, fixture.operator.basisId, directionValues)
    )
    val first = new Array[Double](12)
    val second = new Array[Double](12)
    matrixFree(fixture.cache.curvatureProduct(direction, first, fixture.cache.newWorkspace()))
    matrixFree(rebuilt.curvatureProduct(direction, second, rebuilt.newWorkspace()))
    assertVectorClose(first.toVector, second.toVector, 0.0)

  test("state sample and workspace identities invalidate stale caches explicitly"):
    val fixture = makeFixture(6)
    fixture.cache.requireIdentity(998L, fixture.cache.sampleSetId) match
      case Left(MatrixFreePatchError.StateIdentityMismatch(999L, 998L)) => ()
      case other => fail(s"expected state identity mismatch, got $other")
    val otherSample = fixture.cache.sampleSetId.copy(refreshOrdinal = 9)
    fixture.cache.requireIdentity(999L, otherSample) match
      case Left(_: MatrixFreePatchError.SampleSetIdentityMismatch) => ()
      case other => fail(s"expected sample identity mismatch, got $other")

    val other = buildCache(fixture.operator, fixture.points, fixture.basis, fixture.patches)
    val direction = geometry(
      GeometryDirection3.create(fixture.operator.modelId, fixture.operator.basisId, Array.fill(6)(0.0))
    )
    fixture.cache.curvatureProduct(direction, new Array[Double](6), other.newWorkspace()) match
      case Left(MatrixFreePatchError.WorkspaceCacheMismatch) => ()
      case otherResult => fail(s"expected workspace mismatch, got $otherResult")

  private final case class Fixture[
      Moving <: Frame[D3],
      Fixed <: Frame[D3]
  ](
      operator: DenseMockGeometry[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      basis: Vector[Vector[Double]],
      patches: Vector[CachedProjectedPatch3],
      cache: MatrixFreePatchLinearization3[Unit, Moving, Fixed]
  ):
    val uniqueCount: Int = points.size

  private def makeFixture(parameterCount: Int): Fixture[? <: Frame[D3], ? <: Frame[D3]] =
    val moving = geometryResult(Frame.named[D3](s"matrix-free-moving-$parameterCount"))
    val fixed = geometryResult(Frame.named[D3](s"matrix-free-fixed-$parameterCount"))
    val uniqueCount = 11
    val basis = Vector.tabulate(uniqueCount, parameterCount)((point, parameter) =>
      math.sin(0.17 * (point + 1) * (parameter + 1)) +
        0.3 * math.cos(0.11 * (point + 2) * (parameter + 1))
    )
    val operator = new DenseMockGeometry[moving.type, fixed.type](moving, fixed, basis)
    val points = geometry(
      WorldPointBatch3.create[moving.type](
        moving,
        Array.tabulate(uniqueCount * 3)(index =>
          val point = index / 3
          index % 3 match
            case 0 => point.toDouble
            case 1 => 0.25 * point
            case _ => -0.1 * point
        )
      )
    )
    val patches = Vector(
      patch(Vector(0, 1, 2, 3, 4), Vector(1.0, -2.0, 0.5, 1.5, -1.0), Vector(-0.2, 0.8, -1.4, 0.5, 0.3), 1.2, 0.75, 0.4),
      patch(Vector(2, 3, 6, 2, 8), Vector(-1.0, 0.2, 1.4, -0.6, 0.0), Vector(0.9, -1.2, 0.1, 0.7, -0.5), 0.7, 0.55, -0.25),
      patch(
        Vector(1, 5, 7, 9, 10),
        Vector(0.1, -0.2, 0.3, -0.4, 0.2),
        Vector(-0.3, 0.1, 0.2, -0.1, 0.1),
        9.0,
        9.0,
        9.0,
        Some(PatchInvalidReason.IncompleteInterpolationSupport)
      )
    )
    val cache = buildCache(operator, points, basis, patches)
    Fixture(operator, points, basis, patches, cache)

  private def buildCache[Moving <: Frame[D3], Fixed <: Frame[D3]](
      operator: DenseMockGeometry[Moving, Fixed],
      points: WorldPointBatch3[Moving],
      basis: Vector[Vector[Double]],
      patches: Vector[CachedProjectedPatch3]
  ): MatrixFreePatchLinearization3[Unit, Moving, Fixed] =
    val sampleId = PatchSampleSetId(17L, 23L, PatchSampleRole.Optimization, 0, 31L)
    matrixFree(
      MatrixFreePatchLinearization3.create(
        stateIdentity = 999L,
        sampleSetId = sampleId,
        geometry = operator,
        state = (),
        uniquePoints = points,
        imageValues = Array.tabulate(points.size)(index => 0.5 * index),
        imageGradientsWorld = Array.tabulate(points.size * 3)(index => if index % 3 == 0 then 1.0 else 0.0),
        patches = patches,
        basisTableBytes = points.size.toLong * basis.head.length.toLong * 8L
      )
    )

  private def patch(
      indices: Vector[Int],
      moving: Vector[Double],
      fixed: Vector[Double],
      objectiveWeight: Double,
      inlierWeight: Double,
      signedWeight: Double,
      invalid: Option[PatchInvalidReason] = None
  ): CachedProjectedPatch3 =
    val u = normalize(moving)
    val v = normalize(fixed)
    CachedProjectedPatch3(
      indices,
      u,
      v,
      fixedContrastNorm = 1.7,
      correlation = dot(u, v),
      objectiveWeight,
      inlierWeight,
      signedWeight,
      invalid
    )

  private def denseProduct(
      fixture: Fixture[? <: Frame[D3], ? <: Frame[D3]],
      direction: Array[Double]
  ): Vector[Double] =
    val size = direction.length
    val normal = Array.fill(size * size)(0.0)
    fixture.patches.foreach { patch =>
      if patch.invalidReason.isEmpty then
        val scale = patch.objectiveWeight * patch.inlierWeight /
          (patch.fixedContrastNorm * patch.fixedContrastNorm)
        var row = 0
        while row < size do
          var column = 0
          while column < size do
            val left = patch.sampleIndices.map(index => fixture.basis(index)(row))
            val right = patch.sampleIndices.map(index => fixture.basis(index)(column))
            normal(row * size + column) += scale * dot(left, project(right, patch.fixedUnit))
            column += 1
          row += 1
    }
    Vector.tabulate(size)(row =>
      Vector.tabulate(size)(column => normal(row * size + column) * direction(column)).sum
    )

  private def denseRhs(
      fixture: Fixture[? <: Frame[D3], ? <: Frame[D3]]
  ): Vector[Double] =
    Vector.tabulate(fixture.operator.parameterCount)(parameter =>
      fixture.patches.map { patch =>
        if patch.invalidReason.nonEmpty then 0.0
        else
          val residual = patch.movingUnit.zip(patch.fixedUnit).map { case (u, v) =>
            u - patch.correlation * v
          }
          val column = patch.sampleIndices.map(index => fixture.basis(index)(parameter))
          patch.objectiveWeight * patch.signedWeight / patch.fixedContrastNorm * dot(column, residual)
      }.sum
    )

  private def project(values: Vector[Double], fixedUnit: Vector[Double]): Vector[Double] =
    val mean = values.sum / values.length.toDouble
    val centered = values.map(_ - mean)
    val along = dot(fixedUnit, centered)
    centered.zip(fixedUnit).map { case (value, basis) => value - basis * along }

  private def normalize(values: Vector[Double]): Vector[Double] =
    val mean = values.sum / values.length.toDouble
    val centered = values.map(_ - mean)
    val norm = math.sqrt(dot(centered, centered))
    centered.map(_ / norm)

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

  private def assertVectorClose(actual: Vector[Double], expected: Vector[Double], tolerance: Double): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) => assertEqualsDouble(left, right, tolerance) }

  private def geometryResult[A](value: Either[GeometryError, A]): A =
    value.fold(error => fail(error.message), identity)
  private def geometry[A](value: Either[GeometryOperatorError, A]): A =
    value.fold(error => fail(error.message), identity)
  private def matrixFree[A](value: Either[MatrixFreePatchError, A]): A =
    value.fold(error => fail(error.message), identity)

private final class DenseMockGeometry[
    Moving <: Frame[D3],
    Fixed <: Frame[D3]
](
    val moving: Moving,
    val fixed: Fixed,
    basis: Vector[Vector[Double]]
) extends GeometryPointOperator3[Unit, Moving, Fixed]:
  val modelId = s"dense-mock-${basis.head.length}-v1"
  val basisId = Some(s"dense-basis-${basis.head.length}-v1")
  val parameterCount: Int = basis.head.length

  protected def validateState(state: Unit): Either[GeometryOperatorError, Unit] = Right(state)

  protected def mapUnchecked(
      state: Unit,
      points: WorldPointBatch3[Moving],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = state
    val _ = workspace
    Array.copy(points.packed, 0, output.packed, 0, points.packed.length)

  protected def inverseMapChecked(
      state: Unit,
      points: WorldPointBatch3[Fixed],
      output: GeometryOutputBuffer3[Moving],
      workspace: GeometryOperatorWorkspace3
  ): Either[GeometryOperatorError, Unit] =
    val _ = state
    val _ = workspace
    Array.copy(points.packed, 0, output.packed, 0, points.packed.length)
    Right(())

  protected def jvpUnchecked(
      state: Unit,
      points: WorldPointBatch3[Moving],
      direction: Array[Double],
      output: GeometryOutputBuffer3[Fixed],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = state
    val _ = points
    val _ = workspace
    var point = 0
    while point < basis.length do
      output.packed(point * 3) = dotArray(basis(point), direction)
      output.packed(point * 3 + 1) = 0.0
      output.packed(point * 3 + 2) = 0.0
      point += 1

  protected def vjpUnchecked(
      state: Unit,
      points: WorldPointBatch3[Moving],
      forces: WorldVectorBatch3[Fixed],
      output: Array[Double],
      workspace: GeometryOperatorWorkspace3
  ): Unit =
    val _ = state
    val _ = points
    val _ = workspace
    java.util.Arrays.fill(output, 0.0)
    var point = 0
    while point < basis.length do
      var parameter = 0
      while parameter < parameterCount do
        output(parameter) += basis(point)(parameter) * forces.packed(point * 3)
        parameter += 1
      point += 1

  protected def proposeChecked(
      state: Unit,
      direction: Array[Double]
  ): Either[GeometryOperatorError, (Unit, Array[Double])] = Right(state -> direction.clone())

  def priorTerms(state: Unit): Either[GeometryOperatorError, GeometryPriorTerms3] =
    Right(GeometryPriorTerms3(0.0, Vector.fill(parameterCount)(0.0), Vector.empty))

  def certify(state: Unit): Either[GeometryOperatorError, GeometryCertificate3] =
    Right(GeometryCertificate3(modelId, basisId, valid = true, state.toString))

  private def dotArray(left: Vector[Double], right: Array[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum
