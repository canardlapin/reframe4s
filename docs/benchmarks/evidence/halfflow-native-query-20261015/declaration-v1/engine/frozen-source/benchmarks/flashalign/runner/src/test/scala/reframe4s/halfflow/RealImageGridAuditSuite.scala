package reframe4s.halfflow

import gale.linalg.DMat
import reframe4s.halfflow.internal.*

class RealImageGridAuditSuite extends munit.FunSuite:
  private sealed trait Source
  private def right[E,A](value: Either[E,A]): A = value.fold(e => fail(e.toString),identity)
  private val grid=GridSpec(Vector(3,4,5),DMat.dense(4,4,Vector(
    1.2,.1,0,3, .2,1.4,.1,-2, 0,.1,1.8,7, 0,0,0,1)))
  private val frame=RegistrationFrame[Source](SpatialDomainId("reindex-test"),grid)
  private val values=Array.tabulate(grid.nVoxels)(_.toDouble)
  private val mask=Array.tabulate(grid.nVoxels)(_ % 7 != 0)
  private val image=right(RegistrationImage.make(frame,NeuroVol.fromLinear(values,grid),FieldValidity.copyMask(mask)))

  test("axis cycling and reflection preserve physical sample identity and mask ownership"):
    val result=RealImageGridAudit.reindex(image,Vector(2,0,1),Vector(-1,1,1))
    assertEquals(result.frame.grid.shape,Vector(5,3,4))
    for z <- 0 until 4; y <- 0 until 3; x <- 0 until 5 do
      val index=x+5*(y+3*z);val oldIndex=y+3*(z+4*(4-x))
      assertEqualsDouble(result.volume.linear(index),values(oldIndex),0.0)
      assertEquals(result.validity.contains(index),mask(oldIndex))
      val physical=result.frame.grid.voxelToWorld(Vector(x.toDouble,y.toDouble,z.toDouble))
      val expected=grid.voxelToWorld(Vector(y.toDouble,z.toDouble,4.0-x))
      physical.zip(expected).foreach((a,b) => assertEqualsDouble(a,b,1e-12))
    val restored=RealImageGridAudit.reindex(result,Vector(1,2,0),Vector(1,1,-1))
    assertEquals(restored.frame.grid.shape,grid.shape)
    for i <- values.indices do
      assertEqualsDouble(restored.volume.linear(i),values(i),0.0)
      assertEquals(restored.validity.contains(i),mask(i))
    restored.frame.grid.indexToFrame.rowMajor.zip(grid.indexToFrame.rowMajor)
      .foreach((a,b) => assertEqualsDouble(a,b,1e-12))

  test("reindexing retains full validity and rejects non-bijective or invalid axis maps"):
    val full=right(RegistrationImage.make(frame,NeuroVol.fromLinear(values,grid)))
    assertEquals(RealImageGridAudit.reindex(full,Vector(1,0,2),Vector(1,-1,1)).validity,FieldValidity.All)
    val _=intercept[IllegalArgumentException](RealImageGridAudit.reindex(image,Vector(0,0,2),Vector(1,1,1)))
    val _=intercept[IllegalArgumentException](RealImageGridAudit.reindex(image,Vector(0,1,2),Vector(1,0,1)))
