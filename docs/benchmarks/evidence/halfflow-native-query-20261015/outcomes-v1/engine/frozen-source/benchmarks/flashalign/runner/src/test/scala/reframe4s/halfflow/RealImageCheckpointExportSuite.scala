package reframe4s.halfflow

import image4s.geometry.{Affine, D3}
import java.nio.file.Files
import reframe4s.halfflow.internal.*

final class RealImageCheckpointExportSuite extends munit.FunSuite:
  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving

  test("midpoint affine reload preserves both cached directions and rejects an inconsistent pair"):
    val grid = GridSpec.identity(Vector(3, 4, 5))
    val work = RegistrationFrame[Work](SpatialDomainId("export-work"), grid)
    val fixed = RegistrationFrame[Fixed](SpatialDomainId("export-fixed"), grid)
    val moving = RegistrationFrame[Moving](SpatialDomainId("export-moving"), grid)
    val root = Affine.fromRowMajor[D3](Vector(
      1.13, 0.027, -0.019, 3.17, -0.013, 0.97, 0.031, -1.23,
      0.017, -0.023, 1.07, 2.19, 0.0, 0.0, 0.0, 1.0)).toOption.get
    val directory = Files.createTempDirectory("halfflow-offline-pair-")
    try
      val volume = NeuroVol.fromLinear(Array.fill(grid.nVoxels)(1.0), grid)
      for (name, operator) <- Vector("fixed" -> root.inverse, "moving" -> root) do
        RealImageRegistration.savePull("midpoint-"+name+"-residual", DensePull.identity(work), volume, directory)
        Files.writeString(directory.resolve("midpoint-"+name+"-affine.json"), operator.rowMajor.mkString("[", ",", "]"))
      val loaded = RealImageCheckpointExport.loadState(directory, work, fixed, moving)
      assertEquals(loaded.fixed.affine.transform.rowMajor, root.inverse.rowMajor)
      assertEquals(loaded.fixed.affine.transform.inverse.rowMajor, root.rowMajor)
      assertEquals(loaded.moving.affine.transform.inverse.rowMajor, root.inverse.rowMajor)
      Files.writeString(directory.resolve("midpoint-fixed-affine.json"), Affine.identity[D3].rowMajor.mkString("[", ",", "]"))
      val _ = intercept[IllegalArgumentException](RealImageCheckpointExport.loadState(directory, work, fixed, moving))
    finally
      val files = Files.list(directory)
      try files.forEach(path => { Files.delete(path); () })
      finally files.close()
      Files.delete(directory)

  test("captured coarse geometry binds to the native live owner and preserves oblique coefficients"):
    val native = GridSpec.identity(Vector(13, 15, 17))
    val affine = Affine.fromRowMajor[D3](Vector(
      2.0, 0.25, 0.0, -7.125,
      0.0, 3.0, 0.125, 2.375,
      0.0, 0.0, 4.0, -9.625,
      0.0, 0.0, 0.0, 1.0)).toOption.get
    val expected = native.withGeometry(Vector(7, 8, 9), affine)
    val directory = Files.createTempDirectory("halfflow-offline-grid-")
    val path = directory.resolve("midpoint-fixed-residual.json")
    try
      Files.writeString(path, s"""{"shape":[7,8,9],"index_to_ras_mm":${affine.rowMajor.mkString("[", ",", "]")}}""")
      val loaded = RealImageCheckpointExport.workGrid(directory, native)
      assertEquals(loaded, expected)
      assert(loaded.canonical.grid.frame.sameRuntimeOwnerAs(native.canonical.grid.frame))
      Files.writeString(path, """{"shape":[7,8.5,9],"index_to_ras_mm":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}""")
      val _ = intercept[IllegalArgumentException](RealImageCheckpointExport.workGrid(directory, native))
    finally
      Files.deleteIfExists(path)
      Files.delete(directory)
