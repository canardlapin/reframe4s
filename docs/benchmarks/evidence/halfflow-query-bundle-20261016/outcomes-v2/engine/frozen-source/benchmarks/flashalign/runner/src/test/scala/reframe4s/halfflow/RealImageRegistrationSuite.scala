package reframe4s.halfflow

import image4s.{NonSpatialAxes, Sampled}
import image4s.geometry.{Affine, CoordinateConvention, D3, Frame, Grid}
import image4s.nifti.Nifti
import java.nio.file.{Files, Path}
import ravel.NDArray
import reframe4s.halfflow.internal.{GridSpec, VoxelWindowRadius}

final class RealImageRegistrationSuite extends munit.FunSuite:
  test("checkpoint selection is opt-in, bounded and tied to a declared manifest"):
    assertEquals(RealImageRegistration.parseOptions(Vector.empty).checkpoints, None)
    val flags = Vector("--checkpoint-accepted", "0,1,2,5,10", "--checkpoint-rejected", "1,2,6",
      "--checkpoint-max-states", "24", "--checkpoint-manifest", "protocol.json")
    val selected = RealImageRegistration.parseOptions(flags).checkpoints.get
    assertEquals(selected.acceptedSteps, Vector(0, 1, 2, 5, 10))
    assertEquals(selected.rejectedAttempts, Vector(1, 2, 6))
    assertEquals(selected.maximumStates, 24)
    assertEquals(selected.deferNativeExport, false)
    assertEquals(RealImageRegistration.parseOptions(flags ++ Vector("--checkpoint-defer-export", "true"))
      .checkpoints.get.deferNativeExport, true)
    assertEquals(selected.manifest, java.nio.file.Paths.get("protocol.json"))
    for invalid <- Vector(
      Vector("--checkpoint-max-states", "1"),
      flags.updated(1, "-1"), flags.updated(1, "1,1"), flags.updated(1, "11"),
      flags.updated(3, "0"), flags.updated(5, "129"), flags.updated(5, "-1"),
      flags ++ Vector("--checkpoint-defer-export", "maybe")
    ) do
      val _ = intercept[IllegalArgumentException](RealImageRegistration.parseOptions(invalid))

  test("checkpoint JSON escapes text and represents unavailable numerical values as null"):
    val encoded = RealImageCheckpointJson.encode(Map("value" -> Double.NaN,
      "text" -> "quote\" slash\\ newline\n", "missing" -> None))
    assertEquals(encoded, "{\"missing\":null,\"text\":\"quote\\\" slash\\\\ newline\\n\",\"value\":null}")
    assertEquals(RealImageCheckpointJson.encode(Vector(1.0, Double.PositiveInfinity, -2.0)), "[1.0,null,-2.0]")

  test("development controls preserve the legacy plan and isolate smoothing from budgets"):
    val defaults = RealImageRegistration.parseOptions(Vector.empty)
    val legacy = RealImageRegistration.finePlan(50, 1e-3, 6.0)
    val explicit = RealImageRegistration.finePlan(50, 1e-3, 6.0, defaults.stepsPerLevel, defaults.fineSmoothingMm)
    assertEquals(explicit, legacy)
    val options = RealImageRegistration.parseOptions(Vector("saved.json", "--steps-per-level", "40", "--fine-smoothing-mm", "3"))
    val longer = RealImageRegistration.finePlan(50, 1e-3, 6.0, options.stepsPerLevel, options.fineSmoothingMm)
    assertEquals(longer.levels.map(_.targetAcceptedSteps), Vector(40, 40))
    assertEquals(longer.levels.map(_.maximumAttempts), Vector(54, 54))
    assertEquals(longer.levels.map(_.smoothSigmaMm), Vector(10.0, 3.0))
    assertEquals(longer.geometry, legacy.geometry)
    assertEquals(longer.exportConfig, legacy.exportConfig)
    assertEquals(options.savedAffine, Some(java.nio.file.Paths.get("saved.json")))

  test("ambiguous and invalid development controls are rejected"):
    for args <- Vector(
      Vector("--steps-per-level", "0"), Vector("--fine-smoothing-mm", "NaN"),
      Vector("--total-smoothing-mm", "-1"), Vector("--boundary-width-mm", "Infinity"),
      Vector("--fine-total-smoothing-mm", "NaN"),
      Vector("--scale-total-smoothing-by-step", "maybe"),
      Vector("--fine-cc-radius", "0"), Vector("--affine-trials", "16"),
      Vector("--steps-per-level", "20", "--steps-per-level", "40"),
      Vector("--affine-budget", "100"), Vector("--unknown", "1"),
      Vector("saved.json", "--affine-budget", "100", "--policy-receipt", "protocol.json")
    ) do
      val _ = intercept[IllegalArgumentException](RealImageRegistration.parseOptions(args))

  test("accumulated smoothing changes candidate regularization without changing export tolerances"):
    val options = RealImageRegistration.parseOptions(Vector("--total-smoothing-mm", "0.75", "--boundary-width-mm", "18"))
    val plan = RealImageRegistration.finePlan(50, 1e-3, options.boundaryWidthMm, 20, 6.0, options.accumulatedSmoothingMm)
    assertEquals(plan.levels.map(_.accumulatedSmoothingMm), Vector(0.75, 0.75))
    assertEquals(plan.velocityBoundaryWidthMm, 18.0)
    assertEquals(plan.exportConfig, RealImageRegistration.finePlan(50, 1e-3, 6.0).exportConfig)
    val fine = RealImageRegistration.parseOptions(Vector("--total-smoothing-mm", "1.5", "--fine-total-smoothing-mm", "0.75"))
    val scheduled = RealImageRegistration.finePlan(50, 1e-3, 6.0, 20, 6.0,
      fine.accumulatedSmoothingMm, fine.fineAccumulatedSmoothingMm)
    assertEquals(scheduled.levels.map(_.accumulatedSmoothingMm), Vector(1.5, 0.75))
    assertEquals(scheduled.exportConfig, plan.exportConfig)
    val scaled = RealImageRegistration.parseOptions(Vector("--scale-total-smoothing-by-step", "true"))
    val scaledPlan = RealImageRegistration.finePlan(50, 1e-3, 6.0, 20, 6.0, 1.5,
      scaleAccumulatedSmoothingByStep = scaled.scaleAccumulatedSmoothingByStep)
    assert(scaledPlan.levels.forall(_.scaleAccumulatedSmoothingByStep))
    assert(plan.levels.forall(!_.scaleAccumulatedSmoothingByStep))
    assertEquals(scaledPlan.exportConfig, plan.exportConfig)
    val physicalWindow = RealImageRegistration.finePlan(50, 1e-3, 6.0, fineCcRadius = 4)
    assertEquals(physicalWindow.levels.map(_.cc.radius), Vector(VoxelWindowRadius(2, 2, 2), VoxelWindowRadius(4, 4, 4)))
    assertEquals(physicalWindow.exportConfig, plan.exportConfig)

  private def right[E, A](value: Either[E, A]): A =
    value.fold(error => fail(error.toString), identity)

  private val frame = right(Frame.named[D3]("fixed", convention = CoordinateConvention.RAS))
  private val affine = right(Affine.fromRowMajor[D3](Vector(
    1.5, 0.0, 0.0, -12.0,
    0.0, 1.5, 0.0, 3.0,
    0.0, 0.0, 1.5, -9.0,
    0.0, 0.0, 0.0, 1.0
  )))
  private val grid = right(Grid.in(frame)(Vector(3, 4, 5), affine))
  private val expected = GridSpec.fromGrid(grid)

  private def withMask(operator: Affine[D3], shape: Vector[Int] = grid.shape)(body: Path => Unit): Unit =
    val directory = Files.createTempDirectory("reframe4s-real-mask-")
    val path = directory.resolve("mask.nii")
    try
      val other = right(Frame.named[D3]("independent mask file", convention = CoordinateConvention.RAS))
      val maskGrid = right(Grid.in(other)(shape, operator))
      val values = NDArray.tabulate[Double](shape(0), shape(1), shape(2)):
        (x, y, z) => if (x + y + z) % 2 == 0 then 1.0 else 0.0
      val image = right(Sampled.continuous(maskGrid, NonSpatialAxes.empty, values))
      right(Nifti.writeScalar(path, image))
      body(path)
    finally
      Files.deleteIfExists(path)
      Files.delete(directory)

  test("a separately decoded matching mask binds to the fixed lattice and preserves x-fastest order"):
    withMask(affine): path =>
      val values = RealImageRegistration.loadMask(path, expected)
      assertEquals(values.length, 60)
      for z <- 0 until 5; y <- 0 until 4; x <- 0 until 3 do
        assertEquals(values(x + 3 * (y + 4 * z)), (x + y + z) % 2 == 0)

  test("mask admission still rejects a shifted physical lattice"):
    val shifted = right(Affine.fromRowMajor[D3](affine.rowMajor.updated(3, -11.5)))
    withMask(shifted): path =>
      val _ = interceptMessage[IllegalArgumentException]("requirement failed: fixed mask grid differs from fixed image"):
        RealImageRegistration.loadMask(path, expected)

  test("mask admission still rejects a different shape"):
    withMask(affine, Vector(4, 4, 5)): path =>
      val _ = interceptMessage[IllegalArgumentException]("requirement failed: fixed mask grid differs from fixed image"):
        RealImageRegistration.loadMask(path, expected)
