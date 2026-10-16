package reframe4s.spectral

final class LinearCorrelation3Suite extends munit.FunSuite:
  test("odd and even volume extents match direct linear correlation at every lag"):
    val cases = Vector(
      SpectralShape3(3, 2, 3) -> SpectralShape3(4, 3, 2),
      SpectralShape3(4, 3, 2) -> SpectralShape3(2, 4, 3)
    )
    cases.foreach { case (leftShape, rightShape) =>
      val plan = spectral(LinearCorrelation3Plan.create(leftShape, rightShape))
      val left = Array.tabulate(leftShape.elementCount)(index =>
        math.sin(0.21 * index.toDouble) - 0.03 * index.toDouble
      )
      val right = Array.tabulate(rightShape.elementCount)(index =>
        math.cos(0.17 * index.toDouble) + 0.02 * index.toDouble
      )
      val output = new Array[Double](plan.outputShape.elementCount)
      spectral(plan.correlate(left, right, output, plan.newWorkspace()))
      var lagX = 1 - leftShape.x
      while lagX < rightShape.x do
        var lagY = 1 - leftShape.y
        while lagY < rightShape.y do
          var lagZ = 1 - leftShape.z
          while lagZ < rightShape.z do
            val actual = spectral(plan.valueAtLag(output, lagX, lagY, lagZ))
            val expected = direct(left, leftShape, right, rightShape)(
              lagX,
              lagY,
              lagZ
            )
            assertEqualsDouble(actual, expected, 2e-11)
            lagZ += 1
          lagY += 1
        lagX += 1
    }

  test("zero padding prevents circular wraparound peaks"):
    val leftShape = SpectralShape3(5, 1, 1)
    val rightShape = SpectralShape3(6, 1, 1)
    val plan = spectral(LinearCorrelation3Plan.create(leftShape, rightShape))
    val left = Array(1.0, 0.0, 0.0, 0.0, 0.0)
    val right = Array(0.0, 0.0, 0.0, 0.0, 0.0, 2.0)
    val output = new Array[Double](plan.outputShape.elementCount)
    val workspace = plan.newWorkspace()
    spectral(plan.correlate(left, right, output, workspace))

    assertEqualsDouble(spectral(plan.valueAtLag(output, 5, 0, 0)), 2.0, 1e-14)
    assertEqualsDouble(spectral(plan.valueAtLag(output, -1, 0, 0)), 0.0, 1e-14)
    assertEquals(plan.paddedShape, SpectralShape3(16, 2, 2))
    assert(workspace.complexScalarCapacity > 0L, workspace.complexScalarCapacity)

  test("prepared right fields and one-inverse weighted sums match ordinary correlations"):
    val leftShape = SpectralShape3(4, 3, 3)
    val rightShape = SpectralShape3(3, 4, 2)
    val plan = spectral(LinearCorrelation3Plan.create(leftShape, rightShape))
    val workspace = plan.newWorkspace()
    val left = Array.tabulate(3)(field =>
      Array.tabulate(leftShape.elementCount)(index =>
        math.sin(0.13 * (index + 2 * field).toDouble) + 0.07 * field
      )
    )
    val right = Array.tabulate(3)(field =>
      Array.tabulate(rightShape.elementCount)(index =>
        math.cos(0.19 * (index - field).toDouble) - 0.04 * field
      )
    )
    val weights = Array(1.0, -0.75, 2.0)
    val ordinary = Array.fill(3)(new Array[Double](plan.outputShape.elementCount))
    var field = 0
    while field < left.length do
      spectral(plan.correlate(left(field), right(field), ordinary(field), workspace))
      field += 1
    val prepared = right.map(field => spectral(plan.prepareRight(field, workspace)))
    assert(prepared.forall(_.complexScalarCount > 0L))
    val paired = spectral(plan.prepareRightPair(right(0), right(1), workspace))
    val pairedPrepared = Array(paired._1, paired._2)
    field = 0
    while field < pairedPrepared.length do
      val pairedOutput = new Array[Double](plan.outputShape.elementCount)
      spectral(
        plan.correlatePrepared(
          left(field),
          pairedPrepared(field),
          pairedOutput,
          workspace
        )
      )
      ordinary(field).zip(pairedOutput).foreach { case (expected, actual) =>
        assertEqualsDouble(actual, expected, 2e-11)
      }
      field += 1
    val preparedSingle = new Array[Double](plan.outputShape.elementCount)
    spectral(plan.correlatePrepared(left(0), prepared(0), preparedSingle, workspace))
    ordinary(0).zip(preparedSingle).foreach { case (expected, actual) =>
      assertEqualsDouble(actual, expected, 2e-11)
    }
    val many = Array.fill(3)(new Array[Double](plan.outputShape.elementCount))
    spectral(plan.correlatePreparedMany(left(0), prepared, many, workspace))
    field = 0
    while field < many.length do
      val expected = new Array[Double](plan.outputShape.elementCount)
      spectral(plan.correlate(left(0), right(field), expected, workspace))
      expected.zip(many(field)).foreach { case (ordinaryValue, preparedValue) =>
        assertEqualsDouble(preparedValue, ordinaryValue, 2e-11)
      }
      field += 1
    val summed = new Array[Double](plan.outputShape.elementCount)
    spectral(
      plan.correlatePreparedWeightedSum(
        left,
        prepared,
        weights,
        summed,
        workspace
      )
    )
    var index = 0
    while index < summed.length do
      val expected =
        weights(0) * ordinary(0)(index) +
          weights(1) * ordinary(1)(index) +
          weights(2) * ordinary(2)(index)
      assertEqualsDouble(summed(index), expected, 5e-11)
      index += 1

    val other = spectral(LinearCorrelation3Plan.create(leftShape, rightShape))
    other.correlatePrepared(
      left(0),
      prepared(0),
      new Array[Double](other.outputShape.elementCount),
      other.newWorkspace()
    ) match
      case Left(SpectralError.WorkspacePlanMismatch) => ()
      case value => fail(s"expected prepared-field owner failure, got $value")

  test("packed weighted groups match independent spatial correlations"):
    val leftShape = SpectralShape3(4, 3, 3)
    val rightShape = SpectralShape3(3, 4, 2)
    val plan = spectral(LinearCorrelation3Plan.create(leftShape, rightShape))
    val preparationWorkspace = plan.newWorkspace()
    val left = Array.tabulate(5)(field =>
      Array.tabulate(leftShape.elementCount)(index =>
        math.sin(0.11 * (index + 3 * field).toDouble) + 0.03 * field
      )
    )
    val rightFields = Array.tabulate(6)(field =>
      Array.tabulate(rightShape.elementCount)(index =>
        math.cos(0.17 * (index - 2 * field).toDouble) - 0.02 * field
      )
    )
    val termLeft = Array(0, 1, 2, 3, 4, 4)
    val termOutput = Array(0, 0, 1, 1, 2, 3)
    val weights = Array(1.0, -0.5, 0.75, 2.0, -1.25, 0.4)
    val prepared = rightFields.map(field =>
      spectral(plan.prepareRight(field, preparationWorkspace))
    )
    val outputs = Array.fill(4)(new Array[Double](plan.outputShape.elementCount))
    val workspace = plan.newWorkspace(batchOutputCapacity = 4)
    spectral(
      plan.correlatePreparedWeightedGroups(
        left,
        termLeft,
        prepared,
        weights,
        termOutput,
        outputs,
        workspace
      )
    )
    assertEquals(workspace.batchOutputCapacity, 4)

    var lagX = 1 - leftShape.x
    while lagX < rightShape.x do
      var lagY = 1 - leftShape.y
      while lagY < rightShape.y do
        var lagZ = 1 - leftShape.z
        while lagZ < rightShape.z do
          val destination =
            ((lagX + leftShape.x - 1) * plan.outputShape.y +
              lagY + leftShape.y - 1) * plan.outputShape.z +
              lagZ + leftShape.z - 1
          var group = 0
          while group < outputs.length do
            var expected = 0.0
            var term = 0
            while term < prepared.length do
              if termOutput(term) == group then
                expected += weights(term) * direct(
                  left(termLeft(term)),
                  leftShape,
                  rightFields(term),
                  rightShape
                )(lagX, lagY, lagZ)
              term += 1
            assertEqualsDouble(outputs(group)(destination), expected, 8e-11)
            group += 1
          lagZ += 1
        lagY += 1
      lagX += 1

    plan.correlatePreparedWeightedGroups(
      left,
      termLeft,
      prepared,
      weights,
      termOutput,
      outputs,
      plan.newWorkspace(batchOutputCapacity = 3)
    ) match
      case Left(
            SpectralError.InvalidArrayLength(
              "workspace batch output capacity",
              4,
              3
            )
          ) => ()
      case value => fail(s"expected packed-output capacity failure, got $value")

  test("shape lag input and workspace failures are typed"):
    assertEquals(
      LinearCorrelation3Plan.create(
        SpectralShape3(0, 2, 2),
        SpectralShape3(2, 2, 2)
      ),
      Left(SpectralError.InvalidShape(0, 0))
    )
    val plan = spectral(
      LinearCorrelation3Plan.create(
        SpectralShape3(2, 2, 2),
        SpectralShape3(3, 2, 2)
      )
    )
    val workspace = plan.newWorkspace()
    val output = new Array[Double](plan.outputShape.elementCount)
    plan.correlate(Array.fill(7)(0.0), Array.fill(12)(0.0), output, workspace) match
      case Left(SpectralError.InvalidArrayLength("left", 8, 7)) => ()
      case other => fail(s"expected left-length failure, got $other")
    plan.valueAtLag(output, 3, 0, 0) match
      case Left(SpectralError.InvalidLag(0, 3, -1, 2)) => ()
      case other => fail(s"expected lag failure, got $other")
    val other = spectral(
      LinearCorrelation3Plan.create(
        SpectralShape3(2, 2, 2),
        SpectralShape3(3, 2, 2)
      )
    )
    other.correlate(Array.fill(8)(0.0), Array.fill(12)(0.0), output, workspace) match
      case Left(SpectralError.WorkspacePlanMismatch) => ()
      case value => fail(s"expected workspace-owner failure, got $value")

  private def direct(
      left: Array[Double],
      leftShape: SpectralShape3,
      right: Array[Double],
      rightShape: SpectralShape3
  )(lagX: Int, lagY: Int, lagZ: Int): Double =
    var total = 0.0
    var x = 0
    while x < leftShape.x do
      val rightX = x + lagX
      var y = 0
      while y < leftShape.y do
        val rightY = y + lagY
        var z = 0
        while z < leftShape.z do
          val rightZ = z + lagZ
          if rightX >= 0 && rightX < rightShape.x &&
            rightY >= 0 && rightY < rightShape.y &&
            rightZ >= 0 && rightZ < rightShape.z
          then
            total +=
              left(index(x, y, z, leftShape)) *
                right(index(rightX, rightY, rightZ, rightShape))
          z += 1
        y += 1
      x += 1
    total

  private def index(x: Int, y: Int, z: Int, shape: SpectralShape3): Int =
    (x * shape.y + y) * shape.z + z

  private def spectral[A](value: Either[SpectralError, A]): A =
    value.fold(error => fail(error.message), identity)
