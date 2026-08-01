package reframe4s.halfflow.internal


final case class DMat private (rows: Int, cols: Int, data: Array[Double]):
  require(rows > 0 && cols > 0, "rows/cols must be positive")
  require(data.length == rows * cols, s"data length ${data.length} != $rows*$cols")

  inline def apply(r: Int, c: Int): Double =
    data(r * cols + c)

  def transpose: DMat =
    val arr = Array.ofDim[Double](rows * cols)
    var r = 0
    while r < rows do
      var c = 0
      while c < cols do
        arr(c * rows + r) = apply(r, c)
        c += 1
      r += 1
    DMat(cols, rows, arr)

  def toRows: Vector[Vector[Double]] =
    Vector.tabulate(rows)(r => Vector.tabulate(cols)(c => apply(r, c)))

  override def equals(other: Any): Boolean =
    other match
      case that: DMat =>
        if (this eq that) then true
        else if this.rows != that.rows || this.cols != that.cols || this.data.length != that.data.length then false
        else
          var i = 0
          while i < data.length do
            val a = this.data(i)
            val b = that.data(i)
            if !(a == b || (a.isNaN && b.isNaN)) then return false
            i += 1
          true
      case _ => false

  override def hashCode(): Int =
    var h = 31 * rows + cols
    var i = 0
    while i < data.length do
      val v = if data(i) == 0.0 then 0.0 else data(i)
      val bits = java.lang.Double.doubleToLongBits(v)
      h = 31 * h + (bits ^ (bits >>> 32)).toInt
      i += 1
    h

object DMat:
  /** Adopt an already row-major primitive buffer without copying it.
    *
    * The caller transfers ownership of `data` to the returned matrix and must
    * not mutate the buffer afterwards.
    */
  def fromRowMajorOwned(rows: Int, cols: Int, data: Array[Double]): DMat =
    require(rows > 0 && cols > 0, "rows/cols must be positive")
    val expected = rows.toLong * cols.toLong
    require(expected <= Int.MaxValue.toLong, s"matrix size $rows*$cols exceeds the supported array size")
    require(data.length == expected.toInt, s"data length ${data.length} != $rows*$cols")
    DMat(rows, cols, data)

  def fromRows(rowsV: Vector[Vector[Double]]): DMat =
    require(rowsV.nonEmpty, "matrix must be non-empty")
    val r = rowsV.length
    val c = rowsV.head.length
    require(rowsV.forall(_.length == c), "ragged rows")

    val arr = Array.ofDim[Double](r * c)
    var i = 0
    var rr = 0
    while rr < r do
      var cc = 0
      while cc < c do
        arr(i) = rowsV(rr)(cc)
        i += 1
        cc += 1
      rr += 1
    DMat(r, c, arr)

  def eye(n: Int): DMat =
    val arr = PrimitiveBuffers.fillConst[Double](n * n, 0.0)
    var i = 0
    while i < n do
      arr(i * n + i) = 1.0
      i += 1
    DMat(n, n, arr)

  def invert(m: DMat, eps: Double = 1e-12): Either[String, DMat] =
    if m.rows != m.cols then Left("matrix must be square")
    else
      val n = m.rows
      val a = Array.ofDim[Double](n, n)
      val inv = Array.ofDim[Double](n, n)

      var r = 0
      while r < n do
        var c = 0
        while c < n do
          a(r)(c) = m(r, c)
          inv(r)(c) = if r == c then 1.0 else 0.0
          c += 1
        r += 1

      var i = 0
      while i < n do
        var pivot = i
        var max = math.abs(a(i)(i))
        var r2 = i + 1
        while r2 < n do
          val v = math.abs(a(r2)(i))
          if v > max then
            max = v
            pivot = r2
          r2 += 1

        if max < eps then return Left("matrix is singular")

        if pivot != i then
          val tmpA = a(i); a(i) = a(pivot); a(pivot) = tmpA
          val tmpI = inv(i); inv(i) = inv(pivot); inv(pivot) = tmpI

        val pv = a(i)(i)
        var c2 = 0
        while c2 < n do
          a(i)(c2) /= pv
          inv(i)(c2) /= pv
          c2 += 1

        var r3 = 0
        while r3 < n do
          if r3 != i then
            val factor = a(r3)(i)
            if factor != 0.0 then
              var c3 = 0
              while c3 < n do
                a(r3)(c3) -= factor * a(i)(c3)
                inv(r3)(c3) -= factor * inv(i)(c3)
                c3 += 1
          r3 += 1

        i += 1

      val out = Array.ofDim[Double](n * n)
      var idx = 0
      r = 0
      while r < n do
        var c4 = 0
        while c4 < n do
          out(idx) = inv(r)(c4)
          idx += 1
          c4 += 1
        r += 1

      Right(DMat(n, n, out))
