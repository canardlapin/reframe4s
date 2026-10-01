package reframe4s.halfflow

import image4s.nifti.Nifti
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import reframe4s.halfflow.internal.*

/** Source-tree real-data court modelled on Hodgeflow's public T1 benchmark test.
  *
  * The Hodgeflow checkout is deliberately not copied into this repository or
  * packaged as a fixture. The test is ignored when that checkout is absent,
  * but an existing file with the wrong hash is a hard failure: silently
  * substituting a different image would change the accuracy court.
  */
object BasinBridgeRealDataSupport:
  final case class RealDataPaths(
      root: Path,
      moving: Path,
      fixed: Path
  )

  final case class RealDataReport(
      root: Path,
      moving: Path,
      fixed: Path,
      movingSha256: String,
      fixedSha256: String,
      movingShape: Vector[Int],
      fixedShape: Vector[Int],
      anchors: Int,
      initialMatchErrorMm: Double,
      finalMatchErrorMm: Double,
      initialCcLoss: Double,
      finalCcLoss: Double,
      accepted: Boolean,
      acceptedAlpha: Option[Double],
      rematched: Boolean,
      finalResidualP95Mm: Double,
      elapsedMillis: Long
  )

  private final case class LoadedVolume(
      path: Path,
      sha256: String,
      shape: Vector[Int],
      grid: GridSpec,
      volume: NeuroVol[Double]
  )

  private sealed trait Work
  private sealed trait Fixed
  private sealed trait Moving

  private val movingRelativePath =
    Paths.get("data-raw", "sub-1002_run-01_res-3_T1w.nii.gz")
  private val fixedRelativePath =
    Paths.get("data-raw", "tpl-MNI152Lin_res-02_T1w.nii.gz")

  // These hashes identify the exact source-tree images used by Hodgeflow's
  // one-case public T1 benchmark test on 2026-08-02.
  private val expectedMovingSha256 =
    "e0563a9d227383125ed4b1514bc66f480ed016de68ce1dc4ed6699b436d93692"
  private val expectedFixedSha256 =
    "5f6e8fc4f60b8e53ec5574d536238034bc340aca01438d7ab32ee9df8ea80b4d"

  def discover(): Option[RealDataPaths] =
    val explicit = sys.env.get("HODGEFLOW_ROOT").toVector
    val workingDirectory = Paths.get(System.getProperty("user.dir"))
    val relative =
      Vector(
        workingDirectory,
        workingDirectory.resolve(".."),
        workingDirectory.resolve("../.."),
        workingDirectory.resolve("../../.."),
        workingDirectory.resolve("../../../..")
      ).map(_.resolve("hodgeflow"))
    val conventional = Vector(
      Paths.get("/Users/bbuchsbaum/code/hodgeflow")
    )
    (explicit.map(Paths.get(_)) ++ relative ++ conventional)
      .map(_.toAbsolutePath.normalize())
      .distinct
      .iterator
      .map(root =>
        RealDataPaths(
          root,
          root.resolve(movingRelativePath),
          root.resolve(fixedRelativePath)
        )
      )
      .find(paths => Files.isRegularFile(paths.moving) && Files.isRegularFile(paths.fixed))

  def run(paths: RealDataPaths): Either[String, RealDataReport] =
    val started = System.nanoTime()
    for
      moving <- load(paths.moving, "Hodgeflow real moving T1")
      fixed <- load(paths.fixed, "Hodgeflow real fixed T1")
      _ <- requireHash("moving", moving.sha256, expectedMovingSha256)
      _ <- requireHash("fixed", fixed.sha256, expectedFixedSha256)
      report <- runBridge(paths, fixed, moving)
    yield report.copy(elapsedMillis = (System.nanoTime() - started) / 1000000L)

  private def load(path: Path, label: String): Either[String, LoadedVolume] =
    val sha = sha256(path)
    Nifti.readScaledDouble(path).left.map(error => s"$label NIfTI read failed: ${error.message}").flatMap { decoded =>
      decoded.image.fold(
        _ => Left(s"$label must be a 3D scalar NIfTI"),
        d3 =>
          val shape = d3.value.sampleSpace.grid.shape
          val rowMajor = decoded.affineSelection.affine.rowMajor
          val affine = DMat.fromRowMajorOwned(4, 4, rowMajor.toArray)
          val grid = GridSpec(shape, affine)
          if d3.value.nonSpatialAxes.size != 0 then
            Left(s"$label must not contain non-spatial axes")
          else
            val data3 = d3.value.data.reshapeView(ravel.Shape(shape(0), shape(1), shape(2)))
            val volume = NeuroVol.fromRavel(data3, grid.toNeuroSpace, label)
            val values = volume.copyLegacyLinear
            if values.length != grid.nVoxels then
              Left(s"$label decoded ${values.length} values for ${grid.nVoxels} voxels")
            else if values.exists(value => !value.isFinite) then
              Left(s"$label contains non-finite scaled values")
            else
              Right(
                LoadedVolume(
                  path,
                  sha,
                  shape,
                  grid,
                  volume
                )
              )
      )
    }

  private def runBridge(
      paths: RealDataPaths,
      fixed: LoadedVolume,
      moving: LoadedVolume
  ): Either[String, RealDataReport] =
    val fixedFrame = Frame[Fixed](SpatialDomainId("hodgeflow-real-fixed"), fixed.grid)
    val movingFrame = Frame[Moving](SpatialDomainId("hodgeflow-real-moving"), moving.grid)
    val workFrame = Frame[Work](SpatialDomainId("hodgeflow-real-work"), fixed.grid)
    for
      fixedImage <- RegistrationImage
        .make(fixedFrame, fixed.volume)
        .left
        .map(error => error.message)
      movingImage <- RegistrationImage
        .make(movingFrame, moving.volume)
        .left
        .map(error => error.message)
      initial <- ForwardMidpoint
        .identity(workFrame, fixedFrame, movingFrame)
        .left
        .map(error => error.message)
      result <- BasinBridgeRound
        .run(
          fixedImage,
          movingImage,
          initial,
          anchors(fixed.grid),
          realRoundConfig
        )
        .left
        .map(error => error.message)
      residuals <- BasinBridgeObjective
        .correspondenceResiduals(result.state, result.evidence.work.values)
        .left
        .map(error => error.message)
    yield
      val sortedResiduals = residuals.map(_.errorMm).sorted
      val p95Index = math.floor(0.95 * (sortedResiduals.length - 1).toDouble).toInt
      RealDataReport(
        paths.root,
        moving.path,
        fixed.path,
        moving.sha256,
        fixed.sha256,
        moving.shape,
        fixed.shape,
        result.evidence.work.values.length,
        result.assimilation.initialObjective.weightedMatchErrorMm,
        result.assimilation.finalObjective.weightedMatchErrorMm,
        result.assimilation.initialObjective.trueCcLoss,
        result.assimilation.finalObjective.trueCcLoss,
        result.assimilation.accepted,
        result.assimilation.acceptedAlpha,
        result.rematch.nonEmpty,
        sortedResiduals(p95Index),
        0L
      )

  private def anchors(grid: GridSpec): Vector[BasinBridgePoint] =
    def interior(extent: Int): Vector[Int] =
      Vector(
        math.round((extent - 1).toDouble * 0.30).toInt,
        math.round((extent - 1).toDouble * 0.50).toInt,
        math.round((extent - 1).toDouble * 0.70).toInt
      ).distinct

    for
      x <- interior(grid.shape.x)
      y <- interior(grid.shape.y)
      z <- Vector(interior(grid.shape.z).head, interior(grid.shape.z).last)
    yield
      val world = grid.voxelToWorld(Vector(x.toDouble, y.toDouble, z.toDouble))
      BasinBridgePoint.unsafe(world(0), world(1), world(2))

  private val realRoundConfig: BasinBridgeRoundConfig =
    val search =
      BasinBridgeBlockSearchConfig
        .make(
          VoxelWindowRadius(1, 1, 1),
          VoxelWindowRadius(8, 8, 8),
          minimumValidFraction = 0.75
        )
        .fold(error => throw new IllegalStateException(error.message), identity)
    BasinBridgeRoundConfig.make(
      search,
      projector = BasinBridgeProjectorConfig.default,
      cc = BasinBridgeCcObjectiveConfig.default
    )

  private def requireHash(label: String, actual: String, expected: String): Either[String, Unit] =
    if actual == expected then Right(())
    else Left(s"$label Hodgeflow asset hash changed: expected $expected, got $actual")

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val input = Files.newInputStream(path)
    try
      val buffer = Array.ofDim[Byte](1024 * 1024)
      var count = input.read(buffer)
      while count >= 0 do
        if count > 0 then digest.update(buffer, 0, count)
        count = input.read(buffer)
      digest.digest().iterator.map(value => f"${value & 0xff}%02x").mkString
    finally input.close()


final class BasinBridgeRealDataSuite extends munit.FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(10, "minutes")

  private val paths = BasinBridgeRealDataSupport.discover()
  override def munitIgnore: Boolean = paths.isEmpty

  test("Hodgeflow public T1 pair runs through the real BasinBridge court") {
    val report = BasinBridgeRealDataSupport
      .run(paths.get)
      .fold(error => fail(error), identity)
    assertEquals(report.movingShape, Vector(62, 93, 93))
    assertEquals(report.fixedShape, Vector(91, 109, 91))
    assert(report.anchors >= 8)
    assert(report.initialMatchErrorMm.isFinite)
    assert(report.finalMatchErrorMm.isFinite)
    assert(report.initialCcLoss.isFinite)
    assert(report.finalCcLoss.isFinite)
    assert(report.accepted, s"real-data BasinBridge rejected: $report")
    assert(
      report.finalMatchErrorMm <= report.initialMatchErrorMm,
      s"real-data bridge increased match error: $report"
    )
    println(
      s"BASIN_BRIDGE_REAL_DATA_REPORT root=${report.root} " +
        s"anchors=${report.anchors} initialMatchMm=${report.initialMatchErrorMm} " +
        s"finalMatchMm=${report.finalMatchErrorMm} initialCc=${report.initialCcLoss} " +
        s"finalCc=${report.finalCcLoss} alpha=${report.acceptedAlpha} " +
        s"p95ResidualMm=${report.finalResidualP95Mm} elapsedMs=${report.elapsedMillis}"
    )
  }
