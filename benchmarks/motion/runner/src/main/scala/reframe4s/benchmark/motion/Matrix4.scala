package reframe4s.benchmark.motion

/**
 * Benchmark-boundary matrix algebra.
 *
 * This is deliberately not a geometry authority. It decodes external tool
 * files before checked values cross into reframe4s, and it is tested only as
 * convention-conversion code.
 */
final class Matrix4 private (
    val rowMajor: Vector[Double]
) derives CanEqual:
  def apply(row: Int, column: Int): Double =
    rowMajor(row * 4 + column)

  def andThen(next: Matrix4): Matrix4 =
    next.multiply(this)

  def multiply(right: Matrix4): Matrix4 =
    val values =
      Vector.tabulate(16) { flat =>
        val row = flat / 4
        val column = flat % 4
        var inner = 0
        var total = 0.0
        while inner < 4 do
          total += apply(row, inner) * right(inner, column)
          inner += 1
        total
      }
    Matrix4.unsafe(values)

  def inverse: Either[RunnerError, Matrix4] =
    val augmented = Array.ofDim[Double](4, 8)
    var row = 0
    while row < 4 do
      var column = 0
      while column < 4 do
        augmented(row)(column) = apply(row, column)
        augmented(row)(column + 4) =
          if row == column then 1.0 else 0.0
        column += 1
      row += 1

    var pivot = 0
    var failure = Option.empty[RunnerError]
    while pivot < 4 && failure.isEmpty do
      var best = pivot
      var candidate = pivot + 1
      while candidate < 4 do
        if
          math.abs(augmented(candidate)(pivot)) >
            math.abs(augmented(best)(pivot))
        then best = candidate
        candidate += 1
      if math.abs(augmented(best)(pivot)) <= 1e-15 then
        failure = Some(RunnerError.InvalidMatrix("singular matrix"))
      else
        if best != pivot then
          val swap = augmented(best)
          augmented(best) = augmented(pivot)
          augmented(pivot) = swap
        val scale = augmented(pivot)(pivot)
        var column = 0
        while column < 8 do
          augmented(pivot)(column) /= scale
          column += 1
        row = 0
        while row < 4 do
          if row != pivot then
            val factor = augmented(row)(pivot)
            column = 0
            while column < 8 do
              augmented(row)(column) -=
                factor * augmented(pivot)(column)
              column += 1
          row += 1
      pivot += 1

    failure.toLeft(
      Matrix4.unsafe(
        Vector.tabulate(16) { flat =>
          augmented(flat / 4)(flat % 4 + 4)
        }
      )
    )

  def transform(point: Vector[Double]): Either[RunnerError, Vector[Double]] =
    if point.size != 3 then
      Left(
        RunnerError.InvalidMatrix(
          s"expected a three-coordinate landmark, got ${point.size}"
        )
      )
    else
      val homogeneous = Vector(point(0), point(1), point(2), 1.0)
      val output =
        Vector.tabulate(4) { row =>
          var column = 0
          var total = 0.0
          while column < 4 do
            total += apply(row, column) * homogeneous(column)
            column += 1
          total
        }
      if math.abs(output(3)) <= 1e-15 then
        Left(
          RunnerError.InvalidMatrix(
            "landmark mapped to a zero homogeneous coordinate"
          )
        )
      else Right(output.take(3).map(_ / output(3)))

  def determinant3: Double =
    val a = apply(0, 0)
    val b = apply(0, 1)
    val c = apply(0, 2)
    val d = apply(1, 0)
    val e = apply(1, 1)
    val f = apply(1, 2)
    val g = apply(2, 0)
    val h = apply(2, 1)
    val i = apply(2, 2)
    a * (e * i - f * h) -
      b * (d * i - f * g) +
      c * (d * h - e * g)

object Matrix4:
  val identity: Matrix4 =
    unsafe(
      Vector(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      )
    )

  def create(values: Vector[Double]): Either[RunnerError, Matrix4] =
    if values.size != 16 then
      Left(
        RunnerError.InvalidMatrix(
          s"expected 16 row-major values, got ${values.size}"
        )
      )
    else
      values.zipWithIndex.collectFirst {
        case (value, index) if !value.isFinite =>
          RunnerError.InvalidMatrix(
            s"value $index is non-finite: $value"
          )
      }.toLeft(unsafe(values))

  private[motion] def unsafe(values: Vector[Double]): Matrix4 =
    new Matrix4(values)

object TransformConvention:
  private val lpsToRas =
    Matrix4.unsafe(
      Vector(
        -1.0, 0.0, 0.0, 0.0,
        0.0, -1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
      )
    )

  /**
   * AFNI saves a base-to-input pull matrix in DICOM LPS coordinates.
   * Canonical records require input-to-base physical RAS.
   */
  def afniBaseToInputLpsToMovingToFixedRas(
      baseToInputLps: Matrix4
  ): Either[RunnerError, Matrix4] =
    baseToInputLps.inverse.map(inverted =>
      lpsToRas.multiply(inverted).multiply(lpsToRas)
    )

  /**
   * nifreeze/nitransforms stores the reference-to-moving pull used by
   * resampling. Canonical records require moving-to-reference.
   */
  def nifreezePullToMovingToFixed(
      fixedToMovingRas: Matrix4
  ): Either[RunnerError, Matrix4] =
    fixedToMovingRas.inverse

  /**
   * Convert a FLIRT-compatible input-scaled-mm to reference-scaled-mm matrix
   * into physical RAS input-to-reference coordinates.
   */
  def flirtToMovingToFixedRas(
      flirt: Matrix4,
      inputIndexToRas: Matrix4,
      inputShape: Vector[Int],
      referenceIndexToRas: Matrix4,
      referenceShape: Vector[Int]
  ): Either[RunnerError, Matrix4] =
    for
      inputScaled <- flirtScaledMillimeters(
        inputIndexToRas,
        inputShape
      )
      referenceScaled <- flirtScaledMillimeters(
        referenceIndexToRas,
        referenceShape
      )
      inputRasToIndex <- inputIndexToRas.inverse
      referenceScaledToIndex <- referenceScaled.inverse
    yield
      referenceIndexToRas
        .multiply(referenceScaledToIndex)
        .multiply(flirt)
        .multiply(inputScaled)
        .multiply(inputRasToIndex)

  private def flirtScaledMillimeters(
      indexToRas: Matrix4,
      shape: Vector[Int]
  ): Either[RunnerError, Matrix4] =
    if shape.size != 3 || shape.exists(_ <= 0) then
      Left(
        RunnerError.InvalidMatrix(
          s"FLIRT space requires a positive D3 shape, got $shape"
        )
      )
    else
      val spacing =
        Vector.tabulate(3) { column =>
          math.sqrt(
            indexToRas(0, column) * indexToRas(0, column) +
              indexToRas(1, column) * indexToRas(1, column) +
              indexToRas(2, column) * indexToRas(2, column)
          )
        }
      if spacing.exists(value => !value.isFinite || value <= 0.0) then
        Left(
          RunnerError.InvalidMatrix(
            s"FLIRT space has invalid voxel sizes: $spacing"
          )
        )
      else
        val flip = indexToRas.determinant3 > 0.0
        Right(
          Matrix4.unsafe(
            Vector(
              if flip then -spacing(0) else spacing(0),
              0.0,
              0.0,
              if flip then (shape(0) - 1).toDouble * spacing(0)
              else 0.0,
              0.0,
              spacing(1),
              0.0,
              0.0,
              0.0,
              0.0,
              spacing(2),
              0.0,
              0.0,
              0.0,
              0.0,
              1.0
            )
          )
        )
