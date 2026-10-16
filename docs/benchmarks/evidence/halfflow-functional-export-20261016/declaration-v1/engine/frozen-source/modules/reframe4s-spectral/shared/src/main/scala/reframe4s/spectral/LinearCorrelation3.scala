package reframe4s.spectral

final case class SpectralShape3(x: Int, y: Int, z: Int):
  def elementCount: Int = x * y * z

final class LinearCorrelation3Workspace private[spectral] (
    private val owner: AnyRef,
    private[spectral] val leftReal: Array[Double],
    private[spectral] val leftImaginary: Array[Double],
    private[spectral] val rightReal: Array[Double],
    private[spectral] val rightImaginary: Array[Double],
    private[spectral] val lineReal: Array[Double],
    private[spectral] val lineImaginary: Array[Double],
    val batchOutputCapacity: Int,
    private[spectral] val batchReal: Array[Array[Double]],
    private[spectral] val batchImaginary: Array[Array[Double]],
    private[spectral] val batchInitialized: Array[Boolean]
):
  private var active = false

  val complexScalarCapacity: Long =
    2L * (leftReal.length.toLong + rightReal.length.toLong) +
      2L * lineReal.length.toLong +
      batchReal.map(_.length.toLong).sum +
      batchImaginary.map(_.length.toLong).sum

  private[spectral] def acquire(candidate: AnyRef): Either[SpectralError, Unit] =
    this.synchronized {
      if !(owner eq candidate) then Left(SpectralError.WorkspacePlanMismatch)
      else if active then Left(SpectralError.WorkspaceInUse)
      else
        active = true
        Right(())
    }

  private[spectral] def release(candidate: AnyRef): Unit =
    this.synchronized {
      if owner eq candidate then active = false
    }

/** Immutable Fourier representation of one right-hand correlation field.
  *
  * A prepared field belongs to the plan that created it. It may be shared by
  * sequential workspaces because correlation never mutates its arrays.
  */
final class PreparedLinearCorrelation3Right private[spectral] (
    private[spectral] val owner: AnyRef,
    private[spectral] val real: Array[Double],
    private[spectral] val imaginary: Array[Double]
):
  val complexScalarCount: Long = 2L * real.length.toLong

/**
 * Zero-padded three-dimensional linear cross-correlation.
 *
 * For lag `(a,b,c)`, the output is
 * `sum left(i,j,k) * right(i+a,j+b,k+c)` over valid overlap. Output lags span
 * `[-leftExtent+1, rightExtent-1]` on each axis and are stored with the
 * corresponding left-extent offset. FFTs are unscaled forward and scaled once
 * per inverse axis, so no additional normalization is applied.
 */
final class LinearCorrelation3Plan private (
    val leftShape: SpectralShape3,
    val rightShape: SpectralShape3,
    val outputShape: SpectralShape3,
    val paddedShape: SpectralShape3,
    private val xPlan: Radix2FftPlan,
    private val yPlan: Radix2FftPlan,
    private val zPlan: Radix2FftPlan
):
  private val paddedCount = paddedShape.elementCount
  private val conjugateIndex =
    val indices = new Array[Int](paddedCount)
    var x = 0
    while x < paddedShape.x do
      val mirrorX = if x == 0 then 0 else paddedShape.x - x
      var y = 0
      while y < paddedShape.y do
        val mirrorY = if y == 0 then 0 else paddedShape.y - y
        var z = 0
        while z < paddedShape.z do
          val mirrorZ = if z == 0 then 0 else paddedShape.z - z
          indices(index(x, y, z, paddedShape)) =
            index(mirrorX, mirrorY, mirrorZ, paddedShape)
          z += 1
        y += 1
      x += 1
    indices

  def newWorkspace(): LinearCorrelation3Workspace =
    newWorkspace(batchOutputCapacity = 0)

  /** Allocate a workspace with reusable spectra for grouped correlations. */
  def newWorkspace(batchOutputCapacity: Int): LinearCorrelation3Workspace =
    require(batchOutputCapacity >= 0, "batch output capacity must be nonnegative")
    val lineCapacity = math.max(paddedShape.x, math.max(paddedShape.y, paddedShape.z))
    val packedBatchCapacity = (batchOutputCapacity + 1) / 2
    new LinearCorrelation3Workspace(
      this,
      new Array[Double](paddedCount),
      new Array[Double](paddedCount),
      new Array[Double](paddedCount),
      new Array[Double](paddedCount),
      new Array[Double](lineCapacity),
      new Array[Double](lineCapacity),
      batchOutputCapacity,
      Array.fill(packedBatchCapacity)(new Array[Double](paddedCount)),
      Array.fill(packedBatchCapacity)(new Array[Double](paddedCount)),
      new Array[Boolean](packedBatchCapacity)
    )

  def correlate(
      left: Array[Double],
      right: Array[Double],
      output: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Either[SpectralError, Unit] =
    validateLength("left", leftShape.elementCount, left.length)
      .flatMap(_ => validateLength("right", rightShape.elementCount, right.length))
      .flatMap(_ => validateLength("output", outputShape.elementCount, output.length))
      .flatMap(_ => validateFinite("left", left))
      .flatMap(_ => validateFinite("right", right))
      .flatMap(_ => workspace.acquire(this))
      .map { _ =>
        try correlateUnchecked(left, right, output, workspace)
        finally workspace.release(this)
      }

  /** Prepare a right-hand field once for repeated correlations by this plan. */
  def prepareRight(
      right: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Either[SpectralError, PreparedLinearCorrelation3Right] =
    validateLength("right", rightShape.elementCount, right.length)
      .flatMap(_ => validateFinite("right", right))
      .flatMap(_ => workspace.acquire(this))
      .map { _ =>
        try
          clearAndTransform(
            right,
            rightShape,
            workspace.rightReal,
            workspace.rightImaginary,
            workspace
          )
          new PreparedLinearCorrelation3Right(
            this,
            workspace.rightReal.clone(),
            workspace.rightImaginary.clone()
          )
        finally workspace.release(this)
      }

  /** Prepare two real right-hand fields with one packed complex transform. */
  def prepareRightPair(
      first: Array[Double],
      second: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Either[
    SpectralError,
    (PreparedLinearCorrelation3Right, PreparedLinearCorrelation3Right)
  ] =
    validateLength("first right", rightShape.elementCount, first.length)
      .flatMap(_ => validateLength("second right", rightShape.elementCount, second.length))
      .flatMap(_ => validateFinite("first right", first))
      .flatMap(_ => validateFinite("second right", second))
      .flatMap(_ => workspace.acquire(this))
      .map { _ =>
        try
          clearAndTransformPair(
            first,
            second,
            rightShape,
            workspace.rightReal,
            workspace.rightImaginary,
            workspace
          )
          val firstReal = new Array[Double](paddedCount)
          val firstImaginary = new Array[Double](paddedCount)
          val secondReal = new Array[Double](paddedCount)
          val secondImaginary = new Array[Double](paddedCount)
          unpackSpectra(
            workspace.rightReal,
            workspace.rightImaginary,
            firstReal,
            firstImaginary,
            secondReal,
            secondImaginary
          )
          new PreparedLinearCorrelation3Right(this, firstReal, firstImaginary) ->
            new PreparedLinearCorrelation3Right(this, secondReal, secondImaginary)
        finally workspace.release(this)
      }

  /** Correlate one left field with an immutable prepared right field. */
  def correlatePrepared(
      left: Array[Double],
      right: PreparedLinearCorrelation3Right,
      output: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Either[SpectralError, Unit] =
    validateLength("left", leftShape.elementCount, left.length)
      .flatMap(_ => validateLength("output", outputShape.elementCount, output.length))
      .flatMap(_ => validateFinite("left", left))
      .flatMap(_ => validatePrepared(right))
      .flatMap(_ => workspace.acquire(this))
      .map { _ =>
        try correlatePreparedUnchecked(left, right, output, workspace)
        finally workspace.release(this)
      }

  /** Sum weighted correlation spectra and perform one shared inverse FFT.
    *
    * The result equals the weighted sum of the corresponding spatial
    * correlations up to floating-point evaluation order.
    */
  def correlatePreparedWeightedSum(
      left: Array[Array[Double]],
      right: Array[PreparedLinearCorrelation3Right],
      weights: Array[Double],
      output: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Either[SpectralError, Unit] =
    validatePreparedSum(left, right, weights, output)
      .flatMap(_ => workspace.acquire(this))
      .map { _ =>
        try correlatePreparedWeightedSumUnchecked(left, right, weights, output, workspace)
        finally workspace.release(this)
      }

  /** Correlate one left field with several prepared right fields.
    *
    * The left forward transform is shared. Each right field still receives an
    * independent inverse transform and output array.
    */
  def correlatePreparedMany(
      left: Array[Double],
      right: Array[PreparedLinearCorrelation3Right],
      output: Array[Array[Double]],
      workspace: LinearCorrelation3Workspace
  ): Either[SpectralError, Unit] =
    validatePreparedMany(left, right, output)
      .flatMap(_ => workspace.acquire(this))
      .map { _ =>
        try correlatePreparedManyUnchecked(left, right, output, workspace)
        finally workspace.release(this)
      }

  /** Evaluate weighted groups of correlations with packed real transforms.
    *
    * `left` contains the distinct real input fields. Each term selects one
    * input and one prepared right field, contributes its weighted spectrum to
    * one output group, and retains the caller's term order within that group.
    * Two real inputs share each forward transform and two Hermitian output
    * spectra share each inverse transform.
    */
  def correlatePreparedWeightedGroups(
      left: Array[Array[Double]],
      termLeftIndices: Array[Int],
      right: Array[PreparedLinearCorrelation3Right],
      weights: Array[Double],
      termOutputIndices: Array[Int],
      output: Array[Array[Double]],
      workspace: LinearCorrelation3Workspace
  ): Either[SpectralError, Unit] =
    validatePreparedGroups(
      left,
      termLeftIndices,
      right,
      weights,
      termOutputIndices,
      output,
      workspace
    )
      .flatMap(_ => workspace.acquire(this))
      .map { _ =>
        try
          correlatePreparedWeightedGroupsUnchecked(
            left,
            termLeftIndices,
            right,
            weights,
            termOutputIndices,
            output,
            workspace
          )
        finally workspace.release(this)
      }

  def valueAtLag(
      output: Array[Double],
      lagX: Int,
      lagY: Int,
      lagZ: Int
  ): Either[SpectralError, Double] =
    validateLength("output", outputShape.elementCount, output.length)
      .flatMap(_ => validateLag(0, lagX, 1 - leftShape.x, rightShape.x - 1))
      .flatMap(_ => validateLag(1, lagY, 1 - leftShape.y, rightShape.y - 1))
      .flatMap(_ => validateLag(2, lagZ, 1 - leftShape.z, rightShape.z - 1))
      .map { _ =>
        val x = lagX + leftShape.x - 1
        val y = lagY + leftShape.y - 1
        val z = lagZ + leftShape.z - 1
        output(index(x, y, z, outputShape))
      }

  private def correlateUnchecked(
      left: Array[Double],
      right: Array[Double],
      output: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Unit =
    clearAndTransform(
      left,
      leftShape,
      workspace.leftReal,
      workspace.leftImaginary,
      workspace
    )
    clearAndTransform(
      right,
      rightShape,
      workspace.rightReal,
      workspace.rightImaginary,
      workspace
    )
    multiplyCorrelationSpectrum(
      workspace.leftReal,
      workspace.leftImaginary,
      workspace.rightReal,
      workspace.rightImaginary,
      weight = 1.0
    )
    inverseAndExtract(
      workspace.leftReal,
      workspace.leftImaginary,
      output,
      workspace
    )

  private def correlatePreparedUnchecked(
      left: Array[Double],
      right: PreparedLinearCorrelation3Right,
      output: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Unit =
    clearAndTransform(
      left,
      leftShape,
      workspace.leftReal,
      workspace.leftImaginary,
      workspace
    )
    multiplyCorrelationSpectrum(
      workspace.leftReal,
      workspace.leftImaginary,
      right.real,
      right.imaginary,
      weight = 1.0
    )
    inverseAndExtract(
      workspace.leftReal,
      workspace.leftImaginary,
      output,
      workspace
    )

  private def correlatePreparedWeightedSumUnchecked(
      left: Array[Array[Double]],
      right: Array[PreparedLinearCorrelation3Right],
      weights: Array[Double],
      output: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Unit =
    java.util.Arrays.fill(workspace.rightReal, 0.0)
    java.util.Arrays.fill(workspace.rightImaginary, 0.0)
    var field = 0
    while field < left.length do
      clearAndTransform(
        left(field),
        leftShape,
        workspace.leftReal,
        workspace.leftImaginary,
        workspace
      )
      multiplyCorrelationSpectrumInto(
        workspace.leftReal,
        workspace.leftImaginary,
        right(field).real,
        right(field).imaginary,
        weights(field),
        workspace.rightReal,
        workspace.rightImaginary
      )
      field += 1
    inverseAndExtract(
      workspace.rightReal,
      workspace.rightImaginary,
      output,
      workspace
    )

  private def correlatePreparedManyUnchecked(
      left: Array[Double],
      right: Array[PreparedLinearCorrelation3Right],
      output: Array[Array[Double]],
      workspace: LinearCorrelation3Workspace
  ): Unit =
    clearAndTransform(
      left,
      leftShape,
      workspace.leftReal,
      workspace.leftImaginary,
      workspace
    )
    var field = 0
    while field < right.length do
      java.util.Arrays.fill(workspace.rightReal, 0.0)
      java.util.Arrays.fill(workspace.rightImaginary, 0.0)
      multiplyCorrelationSpectrumInto(
        workspace.leftReal,
        workspace.leftImaginary,
        right(field).real,
        right(field).imaginary,
        1.0,
        workspace.rightReal,
        workspace.rightImaginary
      )
      inverseAndExtract(
        workspace.rightReal,
        workspace.rightImaginary,
        output(field),
        workspace
      )
      field += 1

  private def correlatePreparedWeightedGroupsUnchecked(
      left: Array[Array[Double]],
      termLeftIndices: Array[Int],
      right: Array[PreparedLinearCorrelation3Right],
      weights: Array[Double],
      termOutputIndices: Array[Int],
      output: Array[Array[Double]],
      workspace: LinearCorrelation3Workspace
  ): Unit =
    val packedOutputCount = (output.length + 1) / 2
    java.util.Arrays.fill(workspace.batchInitialized, false)

    var field = 0
    while field + 1 < left.length do
      clearAndTransformPair(
        left(field),
        left(field + 1),
        leftShape,
        workspace.leftReal,
        workspace.leftImaginary,
        workspace
      )
      var term = 0
      while term < right.length do
        val selected = termLeftIndices(term)
        if selected == field || selected == field + 1 then
          val outputIndex = termOutputIndices(term)
          val packedOutput = outputIndex / 2
          val initialize = !workspace.batchInitialized(packedOutput)
          multiplyPackedCorrelationSpectrumInto(
            workspace.leftReal,
            workspace.leftImaginary,
            first = selected == field,
            right(term).real,
            right(term).imaginary,
            weights(term),
            secondOutput = (outputIndex & 1) == 1,
            initialize,
            workspace.batchReal(packedOutput),
            workspace.batchImaginary(packedOutput)
          )
          workspace.batchInitialized(packedOutput) = true
        term += 1
      field += 2

    if field < left.length then
      clearAndTransform(
        left(field),
        leftShape,
        workspace.leftReal,
        workspace.leftImaginary,
        workspace
      )
      var term = 0
      while term < right.length do
        if termLeftIndices(term) == field then
          val outputIndex = termOutputIndices(term)
          val packedOutput = outputIndex / 2
          val initialize = !workspace.batchInitialized(packedOutput)
          multiplyCorrelationSpectrumLaneInto(
            workspace.leftReal,
            workspace.leftImaginary,
            right(term).real,
            right(term).imaginary,
            weights(term),
            secondOutput = (outputIndex & 1) == 1,
            initialize,
            workspace.batchReal(packedOutput),
            workspace.batchImaginary(packedOutput)
          )
          workspace.batchInitialized(packedOutput) = true
        term += 1

    var packedOutput = 0
    while packedOutput < packedOutputCount do
      if !workspace.batchInitialized(packedOutput) then
        java.util.Arrays.fill(workspace.batchReal(packedOutput), 0.0)
        java.util.Arrays.fill(workspace.batchImaginary(packedOutput), 0.0)
      val firstOutput = packedOutput * 2
      val secondOutput = firstOutput + 1
      if secondOutput < output.length then
        inverseAndExtractPair(
          workspace.batchReal(packedOutput),
          workspace.batchImaginary(packedOutput),
          output(firstOutput),
          output(secondOutput),
          workspace
        )
      else
        inverseAndExtract(
          workspace.batchReal(packedOutput),
          workspace.batchImaginary(packedOutput),
          output(firstOutput),
          workspace
        )
      packedOutput += 1

  private def clearAndTransform(
      input: Array[Double],
      shape: SpectralShape3,
      real: Array[Double],
      imaginary: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Unit =
    java.util.Arrays.fill(real, 0.0)
    java.util.Arrays.fill(imaginary, 0.0)
    embed(input, shape, real)
    transform3(real, imaginary, FftDirection.Forward, workspace)

  private def clearAndTransformPair(
      first: Array[Double],
      second: Array[Double],
      shape: SpectralShape3,
      real: Array[Double],
      imaginary: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Unit =
    java.util.Arrays.fill(real, 0.0)
    java.util.Arrays.fill(imaginary, 0.0)
    embed(first, shape, real)
    embed(second, shape, imaginary)
    transform3(real, imaginary, FftDirection.Forward, workspace)

  private def unpackSpectra(
      packedReal: Array[Double],
      packedImaginary: Array[Double],
      firstReal: Array[Double],
      firstImaginary: Array[Double],
      secondReal: Array[Double],
      secondImaginary: Array[Double]
  ): Unit =
    var element = 0
    while element < paddedCount do
      val mirror = conjugateIndex(element)
      val currentReal = packedReal(element)
      val currentImaginary = packedImaginary(element)
      val mirrorReal = packedReal(mirror)
      val mirrorImaginary = packedImaginary(mirror)
      firstReal(element) = 0.5 * (currentReal + mirrorReal)
      firstImaginary(element) = 0.5 * (currentImaginary - mirrorImaginary)
      secondReal(element) = 0.5 * (currentImaginary + mirrorImaginary)
      secondImaginary(element) = 0.5 * (mirrorReal - currentReal)
      element += 1

  private def multiplyCorrelationSpectrum(
      leftReal: Array[Double],
      leftImaginary: Array[Double],
      rightReal: Array[Double],
      rightImaginary: Array[Double],
      weight: Double
  ): Unit =
    var element = 0
    while element < paddedCount do
      val lr = leftReal(element)
      val li = leftImaginary(element)
      val rr = rightReal(element)
      val ri = rightImaginary(element)
      val productReal = weight * (lr * rr + li * ri)
      val productImaginary = weight * (lr * ri - li * rr)
      leftReal(element) = productReal
      leftImaginary(element) = productImaginary
      element += 1

  private def multiplyCorrelationSpectrumInto(
      leftReal: Array[Double],
      leftImaginary: Array[Double],
      rightReal: Array[Double],
      rightImaginary: Array[Double],
      weight: Double,
      outputReal: Array[Double],
      outputImaginary: Array[Double]
  ): Unit =
    var element = 0
    while element < paddedCount do
      val lr = leftReal(element)
      val li = leftImaginary(element)
      val rr = rightReal(element)
      val ri = rightImaginary(element)
      outputReal(element) += weight * (lr * rr + li * ri)
      outputImaginary(element) += weight * (lr * ri - li * rr)
      element += 1

  private def multiplyPackedCorrelationSpectrumInto(
      packedReal: Array[Double],
      packedImaginary: Array[Double],
      first: Boolean,
      rightReal: Array[Double],
      rightImaginary: Array[Double],
      weight: Double,
      secondOutput: Boolean,
      initialize: Boolean,
      outputReal: Array[Double],
      outputImaginary: Array[Double]
  ): Unit =
    var element = 0
    while element < paddedCount do
      val mirror = conjugateIndex(element)
      val currentReal = packedReal(element)
      val currentImaginary = packedImaginary(element)
      val mirrorReal = packedReal(mirror)
      val mirrorImaginary = packedImaginary(mirror)
      val leftReal =
        if first then 0.5 * (currentReal + mirrorReal)
        else 0.5 * (currentImaginary + mirrorImaginary)
      val leftImaginary =
        if first then 0.5 * (currentImaginary - mirrorImaginary)
        else 0.5 * (mirrorReal - currentReal)
      val rr = rightReal(element)
      val ri = rightImaginary(element)
      val productReal = weight * (leftReal * rr + leftImaginary * ri)
      val productImaginary = weight * (leftReal * ri - leftImaginary * rr)
      val laneReal = if secondOutput then -productImaginary else productReal
      val laneImaginary = if secondOutput then productReal else productImaginary
      if initialize then
        outputReal(element) = laneReal
        outputImaginary(element) = laneImaginary
      else
        outputReal(element) += laneReal
        outputImaginary(element) += laneImaginary
      element += 1

  private def multiplyCorrelationSpectrumLaneInto(
      leftReal: Array[Double],
      leftImaginary: Array[Double],
      rightReal: Array[Double],
      rightImaginary: Array[Double],
      weight: Double,
      secondOutput: Boolean,
      initialize: Boolean,
      outputReal: Array[Double],
      outputImaginary: Array[Double]
  ): Unit =
    var element = 0
    while element < paddedCount do
      val lr = leftReal(element)
      val li = leftImaginary(element)
      val rr = rightReal(element)
      val ri = rightImaginary(element)
      val productReal = weight * (lr * rr + li * ri)
      val productImaginary = weight * (lr * ri - li * rr)
      val laneReal = if secondOutput then -productImaginary else productReal
      val laneImaginary = if secondOutput then productReal else productImaginary
      if initialize then
        outputReal(element) = laneReal
        outputImaginary(element) = laneImaginary
      else
        outputReal(element) += laneReal
        outputImaginary(element) += laneImaginary
      element += 1

  private def inverseAndExtract(
      real: Array[Double],
      imaginary: Array[Double],
      output: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Unit =
    transform3(
      real,
      imaginary,
      FftDirection.Inverse,
      workspace
    )
    var outputX = 0
    while outputX < outputShape.x do
      val lagX = outputX - leftShape.x + 1
      val paddedX = if lagX >= 0 then lagX else paddedShape.x + lagX
      var outputY = 0
      while outputY < outputShape.y do
        val lagY = outputY - leftShape.y + 1
        val paddedY = if lagY >= 0 then lagY else paddedShape.y + lagY
        var outputZ = 0
        while outputZ < outputShape.z do
          val lagZ = outputZ - leftShape.z + 1
          val paddedZ = if lagZ >= 0 then lagZ else paddedShape.z + lagZ
          output(index(outputX, outputY, outputZ, outputShape)) =
            real(index(paddedX, paddedY, paddedZ, paddedShape))
          outputZ += 1
        outputY += 1
      outputX += 1

  private def inverseAndExtractPair(
      real: Array[Double],
      imaginary: Array[Double],
      firstOutput: Array[Double],
      secondOutput: Array[Double],
      workspace: LinearCorrelation3Workspace
  ): Unit =
    transform3(real, imaginary, FftDirection.Inverse, workspace)
    var outputX = 0
    while outputX < outputShape.x do
      val lagX = outputX - leftShape.x + 1
      val paddedX = if lagX >= 0 then lagX else paddedShape.x + lagX
      var outputY = 0
      while outputY < outputShape.y do
        val lagY = outputY - leftShape.y + 1
        val paddedY = if lagY >= 0 then lagY else paddedShape.y + lagY
        var outputZ = 0
        while outputZ < outputShape.z do
          val lagZ = outputZ - leftShape.z + 1
          val paddedZ = if lagZ >= 0 then lagZ else paddedShape.z + lagZ
          val padded = index(paddedX, paddedY, paddedZ, paddedShape)
          val destination = index(outputX, outputY, outputZ, outputShape)
          firstOutput(destination) = real(padded)
          secondOutput(destination) = imaginary(padded)
          outputZ += 1
        outputY += 1
      outputX += 1

  private def validatePrepared(
      value: PreparedLinearCorrelation3Right
  ): Either[SpectralError, Unit] =
    if value.owner eq this then Right(())
    else Left(SpectralError.WorkspacePlanMismatch)

  private def validatePreparedSum(
      left: Array[Array[Double]],
      right: Array[PreparedLinearCorrelation3Right],
      weights: Array[Double],
      output: Array[Double]
  ): Either[SpectralError, Unit] =
    if left.isEmpty then Left(SpectralError.InvalidArrayLength("left fields", 1, 0))
    else if right.length != left.length then
      Left(SpectralError.InvalidArrayLength("right fields", left.length, right.length))
    else if weights.length != left.length then
      Left(SpectralError.InvalidArrayLength("weights", left.length, weights.length))
    else
      validateLength("output", outputShape.elementCount, output.length)
        .flatMap(_ => validateFinite("weights", weights))
        .flatMap { _ =>
          var field = 0
          var failure = Option.empty[SpectralError]
          while field < left.length && failure.isEmpty do
            failure = validateLength(
              s"left[$field]",
              leftShape.elementCount,
              left(field).length
            ).left.toOption
            if failure.isEmpty then
              failure = validateFinite(s"left[$field]", left(field)).left.toOption
            if failure.isEmpty then
              failure = validatePrepared(right(field)).left.toOption
            field += 1
          failure.toLeft(())
        }

  private def validatePreparedMany(
      left: Array[Double],
      right: Array[PreparedLinearCorrelation3Right],
      output: Array[Array[Double]]
  ): Either[SpectralError, Unit] =
    validateLength("left", leftShape.elementCount, left.length)
      .flatMap(_ => validateFinite("left", left))
      .flatMap { _ =>
        if right.isEmpty then
          Left(SpectralError.InvalidArrayLength("right fields", 1, 0))
        else if output.length != right.length then
          Left(SpectralError.InvalidArrayLength("output fields", right.length, output.length))
        else
          var field = 0
          var failure = Option.empty[SpectralError]
          while field < right.length && failure.isEmpty do
            failure = validatePrepared(right(field)).left.toOption
            if failure.isEmpty then
              failure = validateLength(
                s"output[$field]",
                outputShape.elementCount,
                output(field).length
              ).left.toOption
            field += 1
          failure.toLeft(())
      }

  private def validatePreparedGroups(
      left: Array[Array[Double]],
      termLeftIndices: Array[Int],
      right: Array[PreparedLinearCorrelation3Right],
      weights: Array[Double],
      termOutputIndices: Array[Int],
      output: Array[Array[Double]],
      workspace: LinearCorrelation3Workspace
  ): Either[SpectralError, Unit] =
    if left.isEmpty then Left(SpectralError.InvalidArrayLength("left fields", 1, 0))
    else if output.isEmpty then Left(SpectralError.InvalidArrayLength("output groups", 1, 0))
    else if right.isEmpty then Left(SpectralError.InvalidArrayLength("terms", 1, 0))
    else if termLeftIndices.length != right.length then
      Left(SpectralError.InvalidArrayLength("term left indices", right.length, termLeftIndices.length))
    else if weights.length != right.length then
      Left(SpectralError.InvalidArrayLength("weights", right.length, weights.length))
    else if termOutputIndices.length != right.length then
      Left(SpectralError.InvalidArrayLength("term output indices", right.length, termOutputIndices.length))
    else if workspace.batchOutputCapacity < output.length then
      Left(
        SpectralError.InvalidArrayLength(
          "workspace batch output capacity",
          output.length,
          workspace.batchOutputCapacity
        )
      )
    else
      validateFinite("weights", weights).flatMap { _ =>
        var field = 0
        var failure = Option.empty[SpectralError]
        while field < left.length && failure.isEmpty do
          failure = validateLength(
            s"left[$field]",
            leftShape.elementCount,
            left(field).length
          ).left.toOption
          if failure.isEmpty then
            failure = validateFinite(s"left[$field]", left(field)).left.toOption
          field += 1
        var group = 0
        while group < output.length && failure.isEmpty do
          failure = validateLength(
            s"output[$group]",
            outputShape.elementCount,
            output(group).length
          ).left.toOption
          group += 1
        var term = 0
        while term < right.length && failure.isEmpty do
          if termLeftIndices(term) < 0 || termLeftIndices(term) >= left.length then
            failure = Some(
              SpectralError.InvalidArrayLength(
                s"term left index[$term]",
                left.length,
                termLeftIndices(term)
              )
            )
          else if termOutputIndices(term) < 0 || termOutputIndices(term) >= output.length then
            failure = Some(
              SpectralError.InvalidArrayLength(
                s"term output index[$term]",
                output.length,
                termOutputIndices(term)
              )
            )
          else failure = validatePrepared(right(term)).left.toOption
          term += 1
        failure.toLeft(())
      }

  private def embed(
      source: Array[Double],
      shape: SpectralShape3,
      destination: Array[Double]
  ): Unit =
    var x = 0
    while x < shape.x do
      var y = 0
      while y < shape.y do
        var z = 0
        while z < shape.z do
          destination(index(x, y, z, paddedShape)) =
            source(index(x, y, z, shape))
          z += 1
        y += 1
      x += 1

  private def transform3(
      real: Array[Double],
      imaginary: Array[Double],
      direction: FftDirection,
      workspace: LinearCorrelation3Workspace
  ): Unit =
    val finalScale =
      if direction == FftDirection.Inverse then 1.0 / paddedCount.toDouble
      else 1.0
    transformZ(real, imaginary, direction, inverseScale = 1.0)
    transformY(real, imaginary, direction, inverseScale = 1.0, workspace)
    transformX(real, imaginary, direction, finalScale, workspace)

  private def transformZ(
      real: Array[Double],
      imaginary: Array[Double],
      direction: FftDirection,
      inverseScale: Double
  ): Unit =
    var x = 0
    while x < paddedShape.x do
      var y = 0
      while y < paddedShape.y do
        val start = index(x, y, 0, paddedShape)
        zPlan.transformContiguousInPlaceUnchecked(
          real,
          imaginary,
          start,
          direction,
          inverseScale
        )
        y += 1
      x += 1

  private def transformY(
      real: Array[Double],
      imaginary: Array[Double],
      direction: FftDirection,
      inverseScale: Double,
      workspace: LinearCorrelation3Workspace
  ): Unit =
    var x = 0
    while x < paddedShape.x do
      var z = 0
      while z < paddedShape.z do
        var location = index(x, 0, z, paddedShape)
        var y = 0
        while y < paddedShape.y do
          workspace.lineReal(y) = real(location)
          workspace.lineImaginary(y) = imaginary(location)
          location += paddedShape.z
          y += 1
        yPlan.transformInPlaceUnchecked(
          workspace.lineReal,
          workspace.lineImaginary,
          direction,
          inverseScale
        )
        location = index(x, 0, z, paddedShape)
        y = 0
        while y < paddedShape.y do
          real(location) = workspace.lineReal(y)
          imaginary(location) = workspace.lineImaginary(y)
          location += paddedShape.z
          y += 1
        z += 1
      x += 1

  private def transformX(
      real: Array[Double],
      imaginary: Array[Double],
      direction: FftDirection,
      inverseScale: Double,
      workspace: LinearCorrelation3Workspace
  ): Unit =
    val xStride = paddedShape.y * paddedShape.z
    var y = 0
    while y < paddedShape.y do
      var z = 0
      while z < paddedShape.z do
        var location = index(0, y, z, paddedShape)
        var x = 0
        while x < paddedShape.x do
          workspace.lineReal(x) = real(location)
          workspace.lineImaginary(x) = imaginary(location)
          location += xStride
          x += 1
        xPlan.transformInPlaceUnchecked(
          workspace.lineReal,
          workspace.lineImaginary,
          direction,
          inverseScale
        )
        location = index(0, y, z, paddedShape)
        x = 0
        while x < paddedShape.x do
          real(location) = workspace.lineReal(x)
          imaginary(location) = workspace.lineImaginary(x)
          location += xStride
          x += 1
        z += 1
      y += 1

  private def validateLength(
      name: String,
      expected: Int,
      actual: Int
  ): Either[SpectralError, Unit] =
    if expected == actual then Right(())
    else Left(SpectralError.InvalidArrayLength(name, expected, actual))

  private def validateFinite(
      name: String,
      values: Array[Double]
  ): Either[SpectralError, Unit] =
    var index = 0
    while index < values.length do
      if !values(index).isFinite then
        return Left(SpectralError.NonFiniteInput(name, index, values(index)))
      index += 1
    Right(())

  private def validateLag(
      axis: Int,
      value: Int,
      minimum: Int,
      maximum: Int
  ): Either[SpectralError, Unit] =
    if value >= minimum && value <= maximum then Right(())
    else Left(SpectralError.InvalidLag(axis, value, minimum, maximum))

  private def index(x: Int, y: Int, z: Int, shape: SpectralShape3): Int =
    (x * shape.y + y) * shape.z + z

object LinearCorrelation3Plan:
  def create(
      leftShape: SpectralShape3,
      rightShape: SpectralShape3
  ): Either[SpectralError, LinearCorrelation3Plan] =
    validateShape(leftShape)
      .flatMap(_ => validateShape(rightShape))
      .flatMap { _ =>
        for
          outputX <- checkedSum(leftShape.x, rightShape.x)
          outputY <- checkedSum(leftShape.y, rightShape.y)
          outputZ <- checkedSum(leftShape.z, rightShape.z)
          paddedX <- nextPowerOfTwo(outputX)
          paddedY <- nextPowerOfTwo(outputY)
          paddedZ <- nextPowerOfTwo(outputZ)
          _ <- checkedProduct(paddedX, paddedY, paddedZ)
          xPlan <- Radix2FftPlan.create(paddedX)
          yPlan <- Radix2FftPlan.create(paddedY)
          zPlan <- Radix2FftPlan.create(paddedZ)
        yield
          new LinearCorrelation3Plan(
            leftShape,
            rightShape,
            SpectralShape3(outputX, outputY, outputZ),
            SpectralShape3(paddedX, paddedY, paddedZ),
            xPlan,
            yPlan,
            zPlan
          )
      }

  private def validateShape(shape: SpectralShape3): Either[SpectralError, Unit] =
    val extents = Array(shape.x, shape.y, shape.z)
    var axis = 0
    while axis < extents.length do
      if extents(axis) <= 0 then
        return Left(SpectralError.InvalidShape(axis, extents(axis)))
      axis += 1
    checkedProduct(shape.x, shape.y, shape.z).map(_ => ())

  private def checkedSum(left: Int, right: Int): Either[SpectralError, Int] =
    val result = left.toLong + right.toLong - 1L
    if result > Int.MaxValue.toLong then
      Left(SpectralError.SizeOverflow(s"linear extent $left + $right - 1 overflows Int"))
    else Right(result.toInt)

  private def nextPowerOfTwo(value: Int): Either[SpectralError, Int] =
    var result = 2L
    while result < value.toLong do result *= 2L
    if result > Int.MaxValue.toLong then
      Left(SpectralError.SizeOverflow(s"FFT padding for extent $value overflows Int"))
    else Right(result.toInt)

  private def checkedProduct(
      x: Int,
      y: Int,
      z: Int
  ): Either[SpectralError, Int] =
    val product = x.toLong * y.toLong * z.toLong
    if product > Int.MaxValue.toLong then
      Left(SpectralError.SizeOverflow(s"shape $x x $y x $z overflows Int storage"))
    else Right(product.toInt)
