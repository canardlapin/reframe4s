package reframe4s.lie

import spatial4s.*
import image4s.geometry.Affine
import reframe4s.core.SpatialMap
import scala.compiletime.testing.typeCheckErrors

/** Concrete source-geometry consumer: units, head/device/MRI direction and owners. */
class SharedSpatialAuthoritySuite extends munit.FunSuite:
  private def right[E,A](value: Either[E,A]): A = value.fold(e => fail(e.toString),identity)
  private def close(actual: Vector[Double],expected: Vector[Double]): Unit =
    assertEquals(actual.length,expected.length)
    actual.indices.foreach(i => assertEqualsDouble(actual(i),expected(i),1e-12))

  test("Reframe consumes the exact Spatial4s frame and point authority"):
    val _ = summon[image4s.geometry.Frame[D3] =:= Frame[D3]]
    val _ = summon[image4s.geometry.D3 =:= D3]
    val head = right(Frame.named[D3]("head"))
    val mri = right(Frame.named[D3]("MRI"))
    val map = right(FramedAffine.translation(head,mri)(10.0,20.0,30.0))
    val point = right(Point.in(head)(1.0,2.0,3.0))
    val result = right(map(point))
    assert(result.belongsTo(mri))
    close(result.coordinates,Vector(11.0,22.0,33.0))
    close(right(map.inverse(result)).coordinates,point.coordinates)

  test("device-to-head followed by head-to-MRI preserves declared composition order"):
    val device = right(Frame.named[D3]("device"))
    val head = right(Frame.named[D3]("head"))
    val mri = right(Frame.named[D3]("MRI"))
    val deviceToHead = right(FramedAffine.translation(device,head)(1.0,2.0,3.0))
    val scale = right(Affine.fromRowMajor[D3](Vector(2.0,0.0,0.0,0.0,0.0,3.0,0.0,0.0,0.0,0.0,4.0,0.0,0.0,0.0,0.0,1.0)))
    val headToMri = FramedAffine.between(head,mri)(scale)
    val composed = deviceToHead.andThen(headToMri)
    val point = right(Point.in(device)(1.0,2.0,3.0))
    close(right(composed(point)).coordinates,Vector(4.0,12.0,24.0))
    close(right(composed.inverse(right(composed(point)))).coordinates,point.coordinates)
    close(right(headToMri(right(deviceToHead(point)))).coordinates,right(composed(point)).coordinates)
    assert(scale(Vector(Double.MaxValue,0.0,0.0)).isLeft)
    assert(headToMri(right(Point.in(head)(Double.MaxValue,0.0,0.0))).isLeft)

  test("maps with erased static endpoints still reject another participant before numerical work"):
    val a = right(Frame.named[D3]("head"))
    val b = right(Frame.named[D3]("head"))
    val mri = right(Frame.named[D3]("MRI"))
    val map = right(FramedAffine.translation(a,mri)(1.0,2.0,3.0))
    val erased = SpatialMap.eraseFrameRefinements(map)
    val alien = right(Point.in(b)(1.0,2.0,3.0))
    val alignment = right(Frame.alignOwners[D3,b.type,Frame[D3]](b,b))
    assert(erased(right(alignment.pointToRight(alien))).isLeft)
    assertEquals(alien.coordinates,Vector(1.0,2.0,3.0))

  test("unit and convention changes require an explicit directed map, not frame alignment"):
    val lpsMeters = right(Frame.named[D3]("head",CoordinateUnit.Meter,CoordinateConvention.LPS))
    val rasMillimeters = right(Frame.named[D3]("MRI",CoordinateUnit.Millimeter,CoordinateConvention.RAS))
    assert(Frame.align(lpsMeters,rasMillimeters).isLeft)
    val coefficients = right(Affine.fromRowMajor[D3](Vector(-1000.0,0.0,0.0,0.0,0.0,-1000.0,0.0,0.0,0.0,0.0,1000.0,0.0,0.0,0.0,0.0,1.0)))
    val map = FramedAffine.between(lpsMeters,rasMillimeters)(coefficients)
    val point = right(Point.in(lpsMeters)(0.01,0.02,0.03))
    close(right(map(point)).coordinates,Vector(-10.0,-20.0,30.0))
    close(right(map.inverse(right(map(point)))).coordinates,point.coordinates)

