package reframe4s.halfflow

import image4s.geometry.{Affine, D3}
import java.io.{DataInputStream, BufferedInputStream}
import java.nio.file.Files
import reframe4s.halfflow.internal.*

class RealImageFunctionalExportSuite extends munit.FunSuite:
  private def right[E,A](v: Either[E,A]): A = v.fold(e => fail(e.toString),identity)
  private def image(grid: GridSpec): NeuroVol[Double] = NeuroVol.fromLinear(
    Array.tabulate(grid.nVoxels)(i => (i%5 + 10*(i/5%5) + 100*(i/25)).toDouble),grid)

  test("IO binds equal numerical lattices to the restored owner and rejects different geometry"):
    val original = GridSpec.identity(Vector(5,5,5))
    val restored = GridSpec.identity(Vector(5,5,5))
    val bound = RealImageFunctionalExport.bind(image(original),restored)
    assert(bound.space eq restored)
    assertEquals(bound.copyLegacyLinear.toVector,image(original).copyLegacyLinear.toVector)
    intercept[IllegalArgumentException](RealImageFunctionalExport.bind(image(original),GridSpec.identity(Vector(6,5,5))))
    val shifted = original.withGeometry(Vector(5,5,5),right(Affine.fromRowMajor[D3](Vector(
      1.0,0.0,0.0,1.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0))))
    intercept[IllegalArgumentException](RealImageFunctionalExport.bind(image(original),shifted))
    intercept[IllegalArgumentException](RealImageFunctionalExport.bind(
      NeuroVol.fromLinear(Array.fill(125)(Double.NaN),original),restored))

  test("checked native applications resample a known shift and keep image support separate"):
    val grid = GridSpec.identity(Vector(5,5,5))
    val w = RegistrationFrame[Unit](SpatialDomainId("work"),grid)
    val f = RegistrationFrame[Unit](SpatialDomainId("fixed"),grid)
    val m = RegistrationFrame[Unit](SpatialDomainId("moving"),GridSpec.identity(Vector(5,5,5)))
    val shift = right(Affine.fromRowMajor[D3](Vector(1.0,0.0,0.0,0.5,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0)))
    val state = right(ForwardMidpoint.make(
      right(ForwardMidpointArm.make(DensePull.identity(w),right(AffineIso.make(w,f,Affine.identity[D3])))),
      right(ForwardMidpointArm.make(DensePull.identity(w),right(AffineIso.make(w,m,shift))))))
    val query = right(ForwardMidpointQuery.compile(state,DensePull.identity(w),DensePull.identity(w)))
    val exported = right(ForwardQueryExporter.build(query))
    val out = Files.createTempDirectory("hfq-application-")
    val report = RealImageFunctionalExport.applyNodes(exported,ExportDirection.FixedToMoving,image(m.grid),out)
    assertEquals(report("eligible"),125)
    assertEquals(report("successful"),125)
    assertEquals(report("image_supported"),100)
    assert(Files.readAllBytes(out.resolve("nodes-status.u8")).forall(_ == 1))
    val valid = Files.readAllBytes(out.resolve("image-status.u8"))
    val values = new DataInputStream(new BufferedInputStream(Files.newInputStream(out.resolve("warped.f64"))))
    try
      for i <- 0 until 125 do
        assertEquals(valid(i),if i%5==4 then 0.toByte else 1.toByte)
        assertEqualsDouble(values.readDouble(),if i%5==4 then 0.0 else image(grid).linear(i)+0.5,1e-12)
      assertEquals(values.read(),-1)
    finally values.close()
