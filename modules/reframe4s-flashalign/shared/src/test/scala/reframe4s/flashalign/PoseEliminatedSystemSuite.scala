package reframe4s.flashalign

final class PoseEliminatedSystemSuite extends munit.FunSuite:
  test("Schur PCG agrees with an independently solved full system with cross-coupled damping"):
    val h = spd(9, diagonal = 3.0, scale = 0.08)
    val d = spd(9, diagonal = 1.2, scale = 0.035)
    val rhs = Vector.tabulate(9)(index => math.sin(index.toDouble + 0.4))
    val hOperator = new CountingDenseOperator(h)
    val dOperator = new CountingDenseOperator(d)
    val linearization = value(
      PoseEliminatedLinearization.create(
        hOperator,
        dOperator,
        rhs,
        poseDimension = 6,
        gaugeConventionId = "weighted-mean-zero-v1"
      )
    )
    assertEquals(hOperator.products, 6L)
    assertEquals(dOperator.products, 6L)

    Vector(0.17, 1.1).foreach { damping =>
      val beforeH = hOperator.products
      val beforeD = dOperator.products
      val attempt = value(
        linearization.attempt(
          damping,
          identityPreconditioner(3),
          config(1e-13, 30)
        )
      )
      assertEquals(hOperator.products, beforeH)
      assertEquals(dOperator.products, beforeD)
      val result = value(attempt.solve(attempt.newWorkspace()))
      val combined = add(h, d, damping)
      val expected = directSolve(combined, rhs)
      assertEquals(result.fieldTermination, GalePcgTermination.Converged)
      assertVectorClose(result.values, expected, 3e-11)
      assert(result.diagnostics.fullResidualNorm <= 1e-10)
      assertEquals(result.diagnostics.sourceProductsAtCompilation, 12L)
      val expectedPrediction = dot(rhs, expected) - 0.5 * dot(expected, multiply(h, expected))
      assertEqualsDouble(result.diagnostics.predictedUndampedReduction, expectedPrediction, 2e-11)
    }

  test("conditional operator is symmetric and includes pose-field metric cross blocks"):
    val h = spd(10, diagonal = 2.0, scale = 0.06)
    val d = spd(10, diagonal = 0.9, scale = 0.04)
    val linearization = value(
      PoseEliminatedLinearization.create(
        dense(h),
        dense(d),
        Vector.fill(10)(0.0),
        poseDimension = 6,
        gaugeConventionId = "constant-mode-removed"
      )
    )
    val attempt = value(linearization.attempt(0.8, identityPreconditioner(4), config(1e-12, 20)))
    val left = Array(0.2, -0.7, 1.1, 0.4)
    val right = Array(-0.3, 0.5, 0.9, -1.2)
    val appliedLeft = new Array[Double](4)
    val appliedRight = new Array[Double](4)
    value(attempt.conditionalProduct(left, appliedLeft))
    value(attempt.conditionalProduct(right, appliedRight))
    assertEqualsDouble(dot(left.toVector, appliedRight.toVector), dot(right.toVector, appliedLeft.toVector), 2e-12)

    val full = add(h, d, 0.8)
    val expected = denseSchur(full, 6)
    assertVectorClose(appliedLeft.toVector, multiply(expected, left.toVector), 2e-12)

  test("data information is rank-aware, pose-conditioned, metric-whitened, and excludes prior and damping"):
    val diagonal = Vector(4.0, 3.0, 2.0, 1.0, 0.0, 0.0, 5.0, 0.0)
    val data = Vector.tabulate(8, 8)((row, column) => if row == column then diagonal(row) else 0.0)
    val metric = Vector.tabulate(8, 8)((row, column) => if row == column then 1.0 else 0.0)
    val information = value(
      PoseConditionedDataInformation.analyze(
        dense(data),
        dense(metric),
        poseDimension = 6,
        fieldDirections = Vector(Vector(1.0, 0.0), Vector(0.0, 1.0))
      )
    )
    assertEquals(information.poseRank, 4)
    assertEquals(information.supportedFieldRank, 1)
    assertEquals(information.generalizedEigenvalues, Vector(0.0, 5.0))
    assertEquals(information.conditionNumber, 1.0)
    assert(information.dataOnly)
    assert(information.priorExcluded)
    assert(information.dampingExcluded)
    assert(information.poseConditioned)

  test("unremoved gauges and data coupling into a null pose direction fail closed"):
    val identity = Vector.tabulate(7, 7)((row, column) => if row == column then 1.0 else 0.0)
    PoseEliminatedLinearization.create(dense(identity), dense(identity), Vector.fill(7)(0.0), 6, "") match
      case Left(PoseEliminationError.MissingGaugeConvention) => ()
      case other => fail(s"expected missing gauge convention, got $other")

    val data = identity.updated(4, identity(4).updated(4, 0.0).updated(6, 0.25))
      .updated(6, identity(6).updated(4, 0.25))
    PoseConditionedDataInformation.analyze(
      dense(data),
      dense(identity),
      6,
      Vector(Vector(1.0)),
      1e-10
    ) match
      case Left(_: PoseEliminationError.UnidentifiablePoseFieldCoupling) => ()
      case other => fail(s"expected null-pose coupling failure, got $other")

  test("both admitted pose sizes are explicit and a singular damped pose block is rejected"):
    val affineIdentity = Vector.tabulate(13, 13)((row, column) => if row == column then 1.0 else 0.0)
    val affine = value(
      PoseEliminatedLinearization.create(
        dense(affineIdentity),
        dense(affineIdentity),
        Vector.fill(13)(0.0),
        12,
        "affine-field-gauge-v1"
      )
    )
    assertEquals(affine.poseDimension, 12)

    val singular = affineIdentity.updated(2, affineIdentity(2).updated(2, 0.0))
    val linearization = value(
      PoseEliminatedLinearization.create(
        dense(singular),
        dense(singular),
        Vector.fill(13)(0.0),
        12,
        "affine-field-gauge-v1"
      )
    )
    linearization.attempt(0.0, identityPreconditioner(1), config(1e-10, 4)) match
      case Left(PoseEliminationError.PoseFactor(_: TinyFactorError.NotPositiveDefinite)) => ()
      case other => fail(s"expected singular pose factor rejection, got $other")

  private final class CountingDenseOperator(matrix: Vector[Vector[Double]]) extends ArraySymmetricOperator:
    val dimension: Int = matrix.length
    var products = 0L
    def apply(input: Array[Double], output: Array[Double]): Either[String, Unit] =
      if input.length != dimension || output.length != dimension then Left("dimension mismatch")
      else
        var row = 0
        while row < dimension do
          output(row) = matrix(row).indices.map(column => matrix(row)(column) * input(column)).sum
          row += 1
        products += 1L
        Right(())

  private def dense(matrix: Vector[Vector[Double]]): ArraySymmetricOperator =
    new CountingDenseOperator(matrix)

  private def identityPreconditioner(size: Int): PriorDataDiagonalPreconditioner =
    PriorDataDiagonalPreconditioner
      .create(Vector.fill(size)(1.0), Vector.fill(size)(0.0), Vector.fill(size)(0.0))
      .fold(error => fail(error.message), identity)

  private def config(tolerance: Double, iterations: Int): GalePcgConfig =
    GalePcgConfig.create(tolerance, iterations).fold(error => fail(error.message), identity)

  private def spd(size: Int, diagonal: Double, scale: Double): Vector[Vector[Double]] =
    val generator = Vector.tabulate(size, size)((row, column) =>
      scale * math.sin((row + 1).toDouble * (column + 2).toDouble)
    )
    Vector.tabulate(size, size) { (row, column) =>
      val gram = Vector.tabulate(size)(index => generator(index)(row) * generator(index)(column)).sum
      gram + (if row == column then diagonal + row.toDouble * 0.1 else 0.0)
    }

  private def add(
      left: Vector[Vector[Double]],
      right: Vector[Vector[Double]],
      rightScale: Double
  ): Vector[Vector[Double]] = Vector.tabulate(left.length, left.length) { (row, column) =>
    left(row)(column) + rightScale * right(row)(column)
  }

  private def denseSchur(matrix: Vector[Vector[Double]], pose: Int): Vector[Vector[Double]] =
    val field = matrix.length - pose
    val aa = Vector.tabulate(pose, pose)((row, column) => matrix(row)(column))
    val ac = Vector.tabulate(pose, field)((row, column) => matrix(row)(pose + column))
    val ca = Vector.tabulate(field, pose)((row, column) => matrix(pose + row)(column))
    val cc = Vector.tabulate(field, field)((row, column) => matrix(pose + row)(pose + column))
    Vector.tabulate(field, field) { (row, column) =>
      val solved = directSolve(aa, Vector.tabulate(pose)(index => ac(index)(column)))
      cc(row)(column) - dot(ca(row), solved)
    }

  private def directSolve(matrix: Vector[Vector[Double]], rhs: Vector[Double]): Vector[Double] =
    val size = matrix.length
    val augmented = Array.tabulate(size, size + 1) { (row, column) =>
      if column == size then rhs(row) else matrix(row)(column)
    }
    var pivot = 0
    while pivot < size do
      val selected = (pivot until size).maxBy(row => math.abs(augmented(row)(pivot)))
      val swap = augmented(pivot)
      augmented(pivot) = augmented(selected)
      augmented(selected) = swap
      val divisor = augmented(pivot)(pivot)
      var column = pivot
      while column <= size do
        augmented(pivot)(column) /= divisor
        column += 1
      var row = 0
      while row < size do
        if row != pivot then
          val multiple = augmented(row)(pivot)
          column = pivot
          while column <= size do
            augmented(row)(column) -= multiple * augmented(pivot)(column)
            column += 1
        row += 1
      pivot += 1
    Vector.tabulate(size)(row => augmented(row)(size))

  private def multiply(matrix: Vector[Vector[Double]], vector: Vector[Double]): Vector[Double] =
    matrix.map(row => dot(row, vector))

  private def dot(left: Vector[Double], right: Vector[Double]): Double =
    left.indices.map(index => left(index) * right(index)).sum

  private def value[A](result: Either[PoseEliminationError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def assertVectorClose(actual: Vector[Double], expected: Vector[Double], tolerance: Double): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) => assertEqualsDouble(left, right, tolerance) }

