package reframe4s.spectral

sealed trait SpectralError derives CanEqual:
  def message: String

object SpectralError:
  final case class InvalidFftSize(size: Int) extends SpectralError:
    val message: String =
      s"FFT size must be a power of two greater than one, got $size"

  final case class InvalidArrayLength(
      name: String,
      expected: Int,
      actual: Int
  ) extends SpectralError:
    val message: String = s"$name requires $expected values, got $actual"

  final case class NonFiniteInput(name: String, index: Int, value: Double)
      extends SpectralError:
    val message: String = s"$name value $index must be finite, got $value"

  case object WorkspacePlanMismatch extends SpectralError:
    val message: String = "spectral workspace belongs to another plan"

  case object WorkspaceInUse extends SpectralError:
    val message: String = "spectral workspace is already active"

  final case class InvalidShape(axis: Int, extent: Int) extends SpectralError:
    val message: String = s"shape extent $axis must be positive, got $extent"

  final case class SizeOverflow(detail: String) extends SpectralError:
    val message: String = detail

  final case class InvalidLag(axis: Int, value: Int, minimum: Int, maximum: Int)
      extends SpectralError:
    val message: String =
      s"lag $axis value $value is outside [$minimum, $maximum]"

enum FftDirection derives CanEqual:
  case Forward, Inverse

final class Radix2FftWorkspace private[spectral] (
    private val owner: AnyRef,
    private[spectral] val real: Array[Double],
    private[spectral] val imaginary: Array[Double]
):
  private var active = false

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

/**
 * Deterministic, unscaled-forward and 1/N-scaled-inverse complex FFT.
 * The plan owns immutable bit-reversal and twiddle tables; calls use an
 * explicitly owned workspace and do not allocate scratch arrays. Adjacent
 * radix-2 stages are evaluated as a single radix-4 butterfly.
 */
final class Radix2FftPlan private (
    val size: Int,
    private val swapLeft: Array[Int],
    private val swapRight: Array[Int],
    private val initialRadixTwo: Boolean,
    private val cosine: Array[Double],
    private val positiveSine: Array[Double],
    private val negativeSine: Array[Double]
):
  private val rootHalf = math.sqrt(0.5)

  def newWorkspace(): Radix2FftWorkspace =
    new Radix2FftWorkspace(
      this,
      new Array[Double](size),
      new Array[Double](size)
    )

  def transform(
      inputReal: Array[Double],
      inputImaginary: Array[Double],
      outputReal: Array[Double],
      outputImaginary: Array[Double],
      direction: FftDirection,
      workspace: Radix2FftWorkspace
  ): Either[SpectralError, Unit] =
    validateLength("input real", inputReal)
      .flatMap(_ => validateLength("input imaginary", inputImaginary))
      .flatMap(_ => validateLength("output real", outputReal))
      .flatMap(_ => validateLength("output imaginary", outputImaginary))
      .flatMap(_ => validateFinite("input real", inputReal))
      .flatMap(_ => validateFinite("input imaginary", inputImaginary))
      .flatMap(_ => workspace.acquire(this))
      .map { _ =>
        try
          java.lang.System.arraycopy(inputReal, 0, workspace.real, 0, size)
          java.lang.System.arraycopy(
            inputImaginary,
            0,
            workspace.imaginary,
            0,
            size
          )
          transformInPlaceUnchecked(
            workspace.real,
            workspace.imaginary,
            direction
          )
          java.lang.System.arraycopy(workspace.real, 0, outputReal, 0, size)
          java.lang.System.arraycopy(
            workspace.imaginary,
            0,
            outputImaginary,
            0,
            size
          )
        finally workspace.release(this)
      }

  private[spectral] def transformInPlaceUnchecked(
      real: Array[Double],
      imaginary: Array[Double],
      direction: FftDirection
  ): Unit =
    val inverseScale =
      if direction == FftDirection.Inverse then 1.0 / size.toDouble else 1.0
    transformInPlaceUnchecked(real, imaginary, direction, inverseScale)

  private[spectral] def transformInPlaceUnchecked(
      real: Array[Double],
      imaginary: Array[Double],
      direction: FftDirection,
      inverseScale: Double
  ): Unit =
    transformAt(real, imaginary, start = 0, direction, inverseScale)

  private[spectral] def transformContiguousInPlaceUnchecked(
      real: Array[Double],
      imaginary: Array[Double],
      start: Int,
      direction: FftDirection,
      inverseScale: Double
  ): Unit =
    transformAt(real, imaginary, start, direction, inverseScale)

  private def transformAt(
      real: Array[Double],
      imaginary: Array[Double],
      start: Int,
      direction: FftDirection,
      inverseScale: Double
  ): Unit =
    var swap = 0
    while swap < swapLeft.length do
      val left = start + swapLeft(swap)
      val right = start + swapRight(swap)
      val realSwap = real(left)
      real(left) = real(right)
      real(right) = realSwap
      val imaginarySwap = imaginary(left)
      imaginary(left) = imaginary(right)
      imaginary(right) = imaginarySwap
      swap += 1
    val forward = direction == FftDirection.Forward
    val sine = if forward then negativeSine else positiveSine
    var block = 0
    while block < size do
      val first = start + block
      val second = first + 1
      val firstReal = real(first)
      val firstImaginary = imaginary(first)
      val secondReal = real(second)
      val secondImaginary = imaginary(second)
      real(first) = firstReal + secondReal
      imaginary(first) = firstImaginary + secondImaginary
      real(second) = firstReal - secondReal
      imaginary(second) = firstImaginary - secondImaginary
      block += 2
    var span = 2
    if size >= 4 then
      block = 0
      while block < size do
        val first = start + block
        val third = first + 2
        val firstReal = real(first)
        val firstImaginary = imaginary(first)
        val thirdReal = real(third)
        val thirdImaginary = imaginary(third)
        real(first) = firstReal + thirdReal
        imaginary(first) = firstImaginary + thirdImaginary
        real(third) = firstReal - thirdReal
        imaginary(third) = firstImaginary - thirdImaginary

        val second = first + 1
        val fourth = third + 1
        val secondReal = real(second)
        val secondImaginary = imaginary(second)
        val fourthReal = real(fourth)
        val fourthImaginary = imaginary(fourth)
        val productReal = if forward then fourthImaginary else -fourthImaginary
        val productImaginary = if forward then -fourthReal else fourthReal
        real(second) = secondReal + productReal
        imaginary(second) = secondImaginary + productImaginary
        real(fourth) = secondReal - productReal
        imaginary(fourth) = secondImaginary - productImaginary
        block += 4
      span = 4
    if initialRadixTwo && size >= 8 then
      transformWidthEight(real, imaginary, start, forward)
      span = 8
    while span < size do
      val width = span * 4
      val stride = size / width
      var block = 0
      while block < size do
        var offset = 0
        while offset < span do
          val first = start + block + offset
          val second = first + span
          val third = second + span
          val fourth = third + span
          val firstReal = real(first)
          val firstImaginary = imaginary(first)
          val secondReal = real(second)
          val secondImaginary = imaginary(second)
          val thirdReal = real(third)
          val thirdImaginary = imaginary(third)
          val fourthReal = real(fourth)
          val fourthImaginary = imaginary(fourth)

          var twiddledSecondReal = secondReal
          var twiddledSecondImaginary = secondImaginary
          var twiddledThirdReal = thirdReal
          var twiddledThirdImaginary = thirdImaginary
          var twiddledFourthReal = fourthReal
          var twiddledFourthImaginary = fourthImaginary
          if offset != 0 then
            val firstTwiddle = offset * stride
            val secondTwiddle = firstTwiddle * 2
            val thirdTwiddle = firstTwiddle * 3
            val secondCosine = cosine(secondTwiddle)
            val secondSine = sine(secondTwiddle)
            twiddledSecondReal =
              secondCosine * secondReal - secondSine * secondImaginary
            twiddledSecondImaginary =
              secondCosine * secondImaginary + secondSine * secondReal
            val thirdCosine = cosine(firstTwiddle)
            val thirdSine = sine(firstTwiddle)
            twiddledThirdReal =
              thirdCosine * thirdReal - thirdSine * thirdImaginary
            twiddledThirdImaginary =
              thirdCosine * thirdImaginary + thirdSine * thirdReal
            val fourthCosine = cosine(thirdTwiddle)
            val fourthSine = sine(thirdTwiddle)
            twiddledFourthReal =
              fourthCosine * fourthReal - fourthSine * fourthImaginary
            twiddledFourthImaginary =
              fourthCosine * fourthImaginary + fourthSine * fourthReal

          val evenReal = firstReal + twiddledSecondReal
          val evenImaginary = firstImaginary + twiddledSecondImaginary
          val oddReal = firstReal - twiddledSecondReal
          val oddImaginary = firstImaginary - twiddledSecondImaginary
          val outerReal = twiddledThirdReal + twiddledFourthReal
          val outerImaginary = twiddledThirdImaginary + twiddledFourthImaginary
          val innerReal = twiddledThirdReal - twiddledFourthReal
          val innerImaginary = twiddledThirdImaginary - twiddledFourthImaginary
          val rotatedReal = if forward then innerImaginary else -innerImaginary
          val rotatedImaginary = if forward then -innerReal else innerReal

          real(first) = evenReal + outerReal
          imaginary(first) = evenImaginary + outerImaginary
          real(third) = evenReal - outerReal
          imaginary(third) = evenImaginary - outerImaginary
          real(second) = oddReal + rotatedReal
          imaginary(second) = oddImaginary + rotatedImaginary
          real(fourth) = oddReal - rotatedReal
          imaginary(fourth) = oddImaginary - rotatedImaginary
          offset += 1
        block += width
      span = width
    if direction == FftDirection.Inverse && inverseScale != 1.0 then
      var index = 0
      while index < size do
        real(start + index) *= inverseScale
        imaginary(start + index) *= inverseScale
        index += 1

  private def transformWidthEight(
      real: Array[Double],
      imaginary: Array[Double],
      start: Int,
      forward: Boolean
  ): Unit =
    var block = 0
    while block < size do
      val first = start + block
      val fifth = first + 4
      val firstReal = real(first)
      val firstImaginary = imaginary(first)
      val fifthReal = real(fifth)
      val fifthImaginary = imaginary(fifth)
      real(first) = firstReal + fifthReal
      imaginary(first) = firstImaginary + fifthImaginary
      real(fifth) = firstReal - fifthReal
      imaginary(fifth) = firstImaginary - fifthImaginary

      val second = first + 1
      val sixth = fifth + 1
      val secondReal = real(second)
      val secondImaginary = imaginary(second)
      val sixthReal = real(sixth)
      val sixthImaginary = imaginary(sixth)
      val productOneReal =
        if forward then rootHalf * (sixthReal + sixthImaginary)
        else rootHalf * (sixthReal - sixthImaginary)
      val productOneImaginary =
        if forward then rootHalf * (sixthImaginary - sixthReal)
        else rootHalf * (sixthImaginary + sixthReal)
      real(second) = secondReal + productOneReal
      imaginary(second) = secondImaginary + productOneImaginary
      real(sixth) = secondReal - productOneReal
      imaginary(sixth) = secondImaginary - productOneImaginary

      val third = first + 2
      val seventh = fifth + 2
      val thirdReal = real(third)
      val thirdImaginary = imaginary(third)
      val seventhReal = real(seventh)
      val seventhImaginary = imaginary(seventh)
      val productTwoReal = if forward then seventhImaginary else -seventhImaginary
      val productTwoImaginary = if forward then -seventhReal else seventhReal
      real(third) = thirdReal + productTwoReal
      imaginary(third) = thirdImaginary + productTwoImaginary
      real(seventh) = thirdReal - productTwoReal
      imaginary(seventh) = thirdImaginary - productTwoImaginary

      val fourth = first + 3
      val eighth = fifth + 3
      val fourthReal = real(fourth)
      val fourthImaginary = imaginary(fourth)
      val eighthReal = real(eighth)
      val eighthImaginary = imaginary(eighth)
      val productThreeReal =
        if forward then rootHalf * (eighthImaginary - eighthReal)
        else -rootHalf * (eighthReal + eighthImaginary)
      val productThreeImaginary =
        if forward then -rootHalf * (eighthReal + eighthImaginary)
        else rootHalf * (eighthReal - eighthImaginary)
      real(fourth) = fourthReal + productThreeReal
      imaginary(fourth) = fourthImaginary + productThreeImaginary
      real(eighth) = fourthReal - productThreeReal
      imaginary(eighth) = fourthImaginary - productThreeImaginary
      block += 8

  private def validateLength(
      name: String,
      values: Array[Double]
  ): Either[SpectralError, Unit] =
    if values.length == size then Right(())
    else Left(SpectralError.InvalidArrayLength(name, size, values.length))

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

object Radix2FftPlan:
  def create(size: Int): Either[SpectralError, Radix2FftPlan] =
    if size <= 1 || (size & (size - 1)) != 0 then
      Left(SpectralError.InvalidFftSize(size))
    else
      val bits = Integer.numberOfTrailingZeros(size)
      val reversed = Array.tabulate(size) { value =>
        Integer.reverse(value) >>> (32 - bits)
      }
      val swapCount = reversed.indices.count(index => reversed(index) > index)
      val swapLeft = new Array[Int](swapCount)
      val swapRight = new Array[Int](swapCount)
      var swap = 0
      var index = 0
      while index < size do
        if reversed(index) > index then
          swapLeft(swap) = index
          swapRight(swap) = reversed(index)
          swap += 1
        index += 1
      val cosine = Array.tabulate(size) { index =>
        math.cos(2.0 * math.Pi * index.toDouble / size.toDouble)
      }
      val positiveSine = Array.tabulate(size) { index =>
        math.sin(2.0 * math.Pi * index.toDouble / size.toDouble)
      }
      val negativeSine = positiveSine.map(-_)
      Right(
        new Radix2FftPlan(
          size,
          swapLeft,
          swapRight,
          (bits & 1) == 1,
          cosine,
          positiveSine,
          negativeSine
        )
      )
