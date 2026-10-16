package reframe4s.flashalign

final class GalePcgAdapterSuite extends munit.FunSuite:
  test("Gale workspace PCG solves an independent SPD system with the declared sign and residual"):
    val matrix = Vector(
      Vector(5.0, 1.0, 0.2),
      Vector(1.0, 4.0, -0.3),
      Vector(0.2, -0.3, 2.5)
    )
    val truth = Vector(0.7, -1.1, 0.4)
    val rhs = multiply(matrix, truth)
    val preconditioner = diagonal(
      prior = Vector(1.0, 1.0, 0.5),
      data = Vector(4.0, 3.0, 2.0),
      damping = Vector(0.0, 0.0, 0.0)
    )
    val plan = solver(
      dense(matrix),
      preconditioner,
      config(1e-12, 20)
    )
    val result = pcg(plan.solve(rhs, plan.newWorkspace()))
    assertEquals(result.termination, GalePcgTermination.Converged)
    assertVectorClose(result.values, truth, 2e-12)
    assert(result.diagnostics.unpreconditionedResidualNorm <= result.diagnostics.requiredResidualNorm)
    assert(result.diagnostics.operatorProducts >= result.diagnostics.iterations.toLong + 2L)
    assert(result.diagnostics.preconditionerApplications >= 1L)
    assertEquals(result.diagnostics.retainedWorkspaceBytes, 3L * 12L * 8L)
    assertEquals(result.diagnostics.estimatedOwnedBytesPerSolve, 3L * 2L * 8L)

  test("ill-conditioned diagonal uses combined prior-data-damping preconditioning"):
    val diagonalValues = Vector(1e-10, 2.0, 3e8, 7.0)
    val matrix = Vector.tabulate(4, 4)((row, column) => if row == column then diagonalValues(row) else 0.0)
    val truth = Vector(2.0, -0.5, 0.25, 1.2)
    val plan = solver(
      dense(matrix),
      diagonal(Vector.fill(4)(0.0), diagonalValues, Vector.fill(4)(0.0)),
      config(1e-13, 10)
    )
    val result = pcg(plan.solve(multiply(matrix, truth), plan.newWorkspace()))
    assertVectorClose(result.values, truth, 5e-13)
    assertEquals(result.termination, GalePcgTermination.Converged)

  test("iteration limit and algebraic breakdown retain distinct trial directions"):
    val matrix = Vector(
      Vector(4.0, 1.0, 0.0),
      Vector(1.0, 3.0, 0.5),
      Vector(0.0, 0.5, 2.0)
    )
    val limitPlan = solver(
      dense(matrix),
      diagonal(Vector.fill(3)(1.0), Vector.fill(3)(0.0), Vector.fill(3)(0.0)),
      config(1e-15, 1)
    )
    val limited = pcg(limitPlan.solve(Vector(1.0, 2.0, 3.0), limitPlan.newWorkspace()))
    assertEquals(limited.termination, GalePcgTermination.IterationLimit)
    assertEquals(limited.diagnostics.iterations, 1)
    assertEquals(limited.values.length, 3)

    val indefinite = Vector(Vector(-1.0, 0.0), Vector(0.0, 1.0))
    val breakdownPlan = solver(
      dense(indefinite),
      diagonal(Vector(1.0, 1.0), Vector(0.0, 0.0), Vector(0.0, 0.0)),
      config(1e-12, 10)
    )
    val broken = pcg(breakdownPlan.solve(Vector(1.0, 1.0), breakdownPlan.newWorkspace()))
    assertEquals(broken.termination, GalePcgTermination.Breakdown)
    assertEquals(broken.diagnostics.iterations, 0)
    assertEquals(broken.values, Vector(0.0, 0.0))

  test("operator preconditioner and workspace failures cross the provider boundary as typed values"):
    val failingOperator = new ArraySymmetricOperator:
      val dimension = 2
      def apply(input: Array[Double], output: Array[Double]): Either[String, Unit] =
        val _ = input
        val _ = output
        Left("deliberate operator failure")
    val goodPreconditioner = diagonal(Vector(1.0, 1.0), Vector(0.0, 0.0), Vector(0.0, 0.0))
    val failingPlan = solver(failingOperator, goodPreconditioner, config(1e-8, 4))
    failingPlan.solve(Vector(1.0, 2.0), failingPlan.newWorkspace()) match
      case Left(GalePcgError.OperatorFailure("deliberate operator failure")) => ()
      case other => fail(s"expected operator failure, got $other")

    val failingPreconditioner = new ArrayPreconditioner:
      val dimension = 2
      val retainedBytes = 0L
      def solve(residual: Array[Double], output: Array[Double]): Either[String, Unit] =
        val _ = residual
        val _ = output
        Left("deliberate preconditioner failure")
    val identityMatrix = Vector(Vector(1.0, 0.0), Vector(0.0, 1.0))
    val preconditionerPlan = solver(dense(identityMatrix), failingPreconditioner, config(1e-8, 4))
    preconditionerPlan.solve(Vector(1.0, 2.0), preconditionerPlan.newWorkspace()) match
      case Left(GalePcgError.PreconditionerFailure("deliberate preconditioner failure")) => ()
      case other => fail(s"expected preconditioner failure, got $other")

    val otherPlan = solver(dense(identityMatrix), goodPreconditioner, config(1e-8, 4))
    preconditionerPlan.solve(Vector(1.0, 2.0), otherPlan.newWorkspace()) match
      case Left(GalePcgError.WorkspacePlanMismatch) => ()
      case other => fail(s"expected workspace mismatch, got $other")

  test("returned solution is owned across repeated workspace reuse"):
    val matrix = Vector(Vector(2.0, 0.0), Vector(0.0, 4.0))
    val plan = solver(
      dense(matrix),
      diagonal(Vector(0.0, 0.0), Vector(2.0, 4.0), Vector(0.0, 0.0)),
      config(1e-14, 5)
    )
    val workspace = plan.newWorkspace()
    val first = pcg(plan.solve(Vector(2.0, 8.0), workspace))
    val second = pcg(plan.solve(Vector(-4.0, 4.0), workspace))
    assertEquals(first.values, Vector(1.0, 2.0))
    assertEquals(second.values, Vector(-2.0, 1.0))
    assertEquals(first.values, Vector(1.0, 2.0))

  private def dense(matrix: Vector[Vector[Double]]): ArraySymmetricOperator =
    new ArraySymmetricOperator:
      val dimension: Int = matrix.length
      def apply(input: Array[Double], output: Array[Double]): Either[String, Unit] =
        if input.length != dimension || output.length != dimension then Left("dimension mismatch")
        else
          var row = 0
          while row < dimension do
            output(row) = matrix(row).indices.map(column => matrix(row)(column) * input(column)).sum
            row += 1
          Right(())

  private def diagonal(
      prior: Vector[Double],
      data: Vector[Double],
      damping: Vector[Double]
  ): PriorDataDiagonalPreconditioner =
    PriorDataDiagonalPreconditioner.create(prior, data, damping).fold(error => fail(error.message), identity)

  private def config(tolerance: Double, iterations: Int): GalePcgConfig =
    GalePcgConfig.create(tolerance, iterations).fold(error => fail(error.message), identity)

  private def solver(
      operator: ArraySymmetricOperator,
      preconditioner: ArrayPreconditioner,
      config: GalePcgConfig
  ): GalePcgPlan = GalePcgPlan.create(operator, preconditioner, config).fold(error => fail(error.message), identity)

  private def pcg[A](value: Either[GalePcgError, A]): A = value.fold(error => fail(error.message), identity)

  private def multiply(matrix: Vector[Vector[Double]], vector: Vector[Double]): Vector[Double] =
    matrix.map(row => row.indices.map(column => row(column) * vector(column)).sum)

  private def assertVectorClose(actual: Vector[Double], expected: Vector[Double], tolerance: Double): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach { case (left, right) => assertEqualsDouble(left, right, tolerance) }
