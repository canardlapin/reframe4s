package reframe4s.halfflow

import java.io.DataOutputStream
import java.nio.file.Files
import reframe4s.halfflow.internal.*

class RealImageObjectiveAuditSuite extends munit.FunSuite:
  private sealed trait Work
  private sealed trait Endpoint

  test("audit steps retain the original default and reject invalid experimental ledgers"):
    assertEquals(RealImageObjectiveAudit.auditEpsilons(None),
      Vector(0.5, 0.2, 0.1, 0.05, 0.02, 0.01, 0.005, 0.002))
    assertEquals(RealImageObjectiveAudit.auditEpsilons(Some("0.001,0.0005,0.0002")),
      Vector(0.001, 0.0005, 0.0002))
    for invalid <- Vector("0.1,0.1,0.01", "0.1,0.2,0.01", "0.1,0,-0.1",
        "NaN,0.1,0.01", "Infinity,0.1,0.01", "0.1,0.01", "0.1,0.01,") do
      val _ = intercept[IllegalArgumentException](RealImageObjectiveAudit.auditEpsilons(Some(invalid)))

  test("checkpoint loader preserves interleaved physical coordinates and compares affine coefficients"):
    val directory = Files.createTempDirectory("halfflow-checkpoint-load-")
    val grid = GridSpec.identity(Vector(3, 4, 5))
    val work = RegistrationFrame[Work](SpatialDomainId("loader-work"), grid)
    val endpoint = RegistrationFrame[Endpoint](SpatialDomainId("loader-endpoint"), grid)
    val name = "midpoint-fixed-residual"
    val affine = "[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]"
    def metadata(matrix: String): String =
      s"""{"shape":[3,4,5],"index_to_ras_mm":$matrix,"dtype":">f8","voxel_order":"x-fastest","components":"interleaved-xyz","map":"absolute-source-RAS-mm"}"""
    val meta = directory.resolve(name + ".json")
    val coordinates = directory.resolve(name + ".f64")
    val validity = directory.resolve(name + ".valid-u8")
    val armAffine = directory.resolve("midpoint-fixed-affine.json")
    try
      Files.writeString(meta, metadata(affine))
      Files.writeString(armAffine, affine)
      Files.write(validity, Array.fill[Byte](grid.nVoxels)(1))
      val stream = new DataOutputStream(Files.newOutputStream(coordinates))
      try
        for i <- 0 until grid.nVoxels do
          stream.writeDouble(i % 3 + 0.125)
          stream.writeDouble((i / 3) % 4 - 0.25)
          stream.writeDouble(i / 12 + 0.5)
      finally stream.close()
      val loaded = RealImageObjectiveAudit.loadArm(directory, "fixed", work, endpoint)
      for i <- 0 until grid.nVoxels do
        assertEqualsDouble(loaded.residual.sourceCoordinates.linearComponent(i, 0), i % 3 + 0.125, 0.0)
        assertEqualsDouble(loaded.residual.sourceCoordinates.linearComponent(i, 1), (i / 3) % 4 - 0.25, 0.0)
        assertEqualsDouble(loaded.residual.sourceCoordinates.linearComponent(i, 2), i / 12 + 0.5, 0.0)
      Files.writeString(meta, metadata("[1,0,0,0.25,0,1,0,0,0,0,1,0,0,0,0,1]"))
      val _ = intercept[IllegalArgumentException](RealImageObjectiveAudit.loadArm(directory, "fixed", work, endpoint))
    finally
      Vector(meta, coordinates, validity, armAffine).foreach(Files.delete)
      Files.delete(directory)
