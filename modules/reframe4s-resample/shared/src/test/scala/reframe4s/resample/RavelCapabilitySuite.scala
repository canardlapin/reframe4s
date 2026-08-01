package reframe4s.resample

import ravel.DType.given
import ravel.NDArray
import ravel.Shape

/**
 * Reframe-owned compile and behavior probe for the two Ravel capabilities
 * required by AC-006.
 */
final class RavelCapabilitySuite extends munit.FunSuite:
  test("rank-specific view indexing and consuming construction compose"):
    val source =
      NDArray
        .tabulate[Double](3, 4, 2)((i, j, k) =>
          100.0 * i.toDouble + 10.0 * j.toDouble + k.toDouble
        )
        .reverse(1)
    val output =
      NDArray.build[Double, ravel.Rank[3]](Shape(3, 4, 2)) { builder =>
        var i = 0
        var linear = 0
        while i < 3 do
          var j = 0
          while j < 4 do
            var k = 0
            while k < 2 do
              builder.writeLinear(linear, source(i, j, k) + 1.0)
              linear += 1
              k += 1
            j += 1
          i += 1
      }

    assertEquals(output(2, 0, 1), source(2, 0, 1) + 1.0)
    assert(output.isCanonicalLayout)
