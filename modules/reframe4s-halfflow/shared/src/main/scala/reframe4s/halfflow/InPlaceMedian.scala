package reframe4s.halfflow

/** Allocation-free median selection over the active prefix of a scratch array. */
private[halfflow] object InPlaceMedian:
  def apply(values: Array[Double], length: Int): Double =
    require(length > 0 && length <= values.length, "median length must fit the scratch array")
    val upper = select(values, length, length / 2)
    if (length & 1) == 1 then upper
    else
      var lower = Double.NegativeInfinity
      var index = 0
      while index < length / 2 do
        lower = math.max(lower, values(index))
        index += 1
      0.5 * (lower + upper)

  private def select(values: Array[Double], length: Int, target: Int): Double =
    var left = 0
    var right = length - 1
    while left < right do
      val middle = left + (right - left) / 2
      val pivot = median3(values(left), values(middle), values(right))
      var below = left
      var scan = left
      var above = right
      while scan <= above do
        val value = values(scan)
        if value < pivot then
          swap(values, below, scan)
          below += 1
          scan += 1
        else if value > pivot then
          swap(values, scan, above)
          above -= 1
        else scan += 1
      if target < below then right = below - 1
      else if target > above then left = above + 1
      else return values(target)
    values(left)

  private inline def median3(a: Double, b: Double, c: Double): Double =
    if a < b then
      if b < c then b else if a < c then c else a
    else if a < c then a
    else if b < c then c
    else b

  private inline def swap(values: Array[Double], first: Int, second: Int): Unit =
    val temporary = values(first)
    values(first) = values(second)
    values(second) = temporary
