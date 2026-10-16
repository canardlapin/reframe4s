package reframe4s.halfflow

import gale.linalg.DMat
import image4s.geometry.{Affine, D3}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal
import reframe4s.halfflow.internal.*

/** Portable evaluation state, not a dense map or invertibility certificate.
  * Version 1 binds the boundary-linear sampler and bounded Newton policy.
  * Decoding creates fresh canonical owners while retaining their alias graph.
  * The Adler-32 trailer detects accidental corruption; use an external SHA-256
  * manifest when authenticity/provenance must be established.
  */
object ForwardQueryBundle:
  val version: Int = 1
  val policy: String = "halfflow-boundary-linear-newton-v1"
  val defaultMaximumBytes: Int = 512 * 1024 * 1024
  private val magic = 0x48465142 // HFQB

  def encode[W, F, M](plan: ForwardMidpointQuery[W, F, M], maximumBytes: Int = defaultMaximumBytes)
      : Either[RegistrationError, Array[Byte]] = protect:
    val frames = Vector(plan.state.work, plan.fixedFrame, plan.movingFrame)
    val estimate = 100L * plan.state.work.grid.nVoxels + 2048L + frames.map(_.domain.value.length.toLong * 3).sum
    require(maximumBytes > 0 && estimate <= maximumBytes, "bundle exceeds byte limit")
    val bytes = new ByteArrayOutputStream()
    val out = new DataOutputStream(bytes)
    out.writeInt(magic)
    out.writeInt(version)
    out.writeUTF(policy)
    val config = plan.fixedInverse.config
    require(config eq plan.movingInverse.config, "query arms must share settings")
    out.writeInt(config.maximumIterations)
    out.writeDouble(config.toleranceMm)
    out.writeDouble(config.maximumStepMm)
    out.writeInt(config.maximumBacktracks)
    frames.zipWithIndex.foreach: (frame, index) =>
      val owner = frames.take(index).indexWhere(_.canonical.sameRuntimeOwnerAs(frame.canonical)) match
        case -1 => index
        case value => value
      out.writeInt(owner)
      out.writeUTF(frame.domain.value)
      frame.grid.shape.foreach(out.writeInt)
      frame.grid.indexToFrame.rowMajor.foreach(out.writeDouble)
    Vector(plan.state.fixed.affine.transform, plan.fixedToWork,
      plan.state.moving.affine.transform, plan.movingToWork).foreach(_.rowMajor.foreach(out.writeDouble))
    Vector(plan.state.fixed.residual, plan.fixedInverse.seed, plan.state.moving.residual, plan.movingInverse.seed).foreach: pull =>
      val n = pull.from.grid.nVoxels
      for component <- 0 until 3; index <- 0 until n do
        out.writeDouble(pull.sourceCoordinates.linearComponent(index, component))
      for index <- 0 until n do out.writeByte(if pull.validity.contains(index) then 1 else 0)
    out.flush()
    val payload = bytes.toByteArray
    out.writeInt(checksum(payload, payload.length))
    out.flush()
    val result = bytes.toByteArray
    require(result.length <= maximumBytes, "bundle exceeds byte limit")
    result

  def decode(bytes: Array[Byte], maximumBytes: Int = defaultMaximumBytes)
      : Either[RegistrationError, ForwardMidpointQuery[?, ?, ?]] = protect:
    require(maximumBytes > 0 && bytes.length <= maximumBytes && bytes.length >= 12, "invalid bundle length")
    val trailer = new DataInputStream(new ByteArrayInputStream(bytes, bytes.length - 4, 4)).readInt()
    require(trailer == checksum(bytes, bytes.length - 4), "bundle checksum mismatch")
    val in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 4))
    require(in.readInt() == magic, "invalid bundle magic")
    require(in.readInt() == version, "unsupported bundle version")
    require(in.readUTF() == policy, "unsupported evaluator policy")
    val config = checked(ResidualQueryConfig.make(in.readInt(), in.readDouble(), in.readDouble(), in.readInt()))
    val frames = ArrayBuffer.empty[RegistrationFrame[Unit]]
    for index <- 0 until 3 do
      val owner = in.readInt()
      require(owner >= 0 && owner <= index, "invalid physical owner reference")
      val domain = SpatialDomainId(in.readUTF())
      val shape = Vector.fill(3)(in.readInt())
      require(shape.forall(_ > 0) && shape.foldLeft(BigInt(1))(_ * _) <= Int.MaxValue / 3, "invalid lattice size")
      val affine = checkedAffine(Vector.fill(16)(in.readDouble()))
      val grid = if owner == index then GridSpec(shape, DMat.dense(4, 4, affine.rowMajor))
        else frames(owner).grid.withGeometry(shape, affine)
      frames += RegistrationFrame[Unit](domain, grid)
    val work = frames(0)
    require(work.grid.shape.forall(_ >= 2), "work lattice needs complete cells")
    require(in.available().toLong == 512L + 100L * work.grid.nVoxels, "inconsistent bundle payload length")
    val operators = Vector.fill(4)(checkedAffine(Vector.fill(16)(in.readDouble())))
    def field(): DensePull[Unit, Unit] =
      val n = work.grid.nVoxels
      val coordinates = Array.fill(3 * n)(in.readDouble())
      val mask = Array.fill(n):
        val value = in.readUnsignedByte()
        require(value <= 1, "invalid validity byte")
        value == 1
      checked(DensePull.make(work, work,
        DenseVectorField.fromLegacyPlanar(work.grid, coordinates, DenseVectorFieldKind.SourceCoordinates), FieldValidity.copyMask(mask)))
    val fixed = field()
    val fixedSeed = field()
    val moving = field()
    val movingSeed = field()
    require(in.available() == 0, "trailing bundle bytes")
    val state = checked(ForwardMidpoint.make(
      checked(ForwardMidpointArm.make(fixed, checked(AffineIso.make(work, frames(1), operators(0))))),
      checked(ForwardMidpointArm.make(moving, checked(AffineIso.make(work, frames(2), operators(2)))))))
    checked(ForwardMidpointQuery.restore(state, fixedSeed, movingSeed, config, operators(1), operators(3)))

  private def checked[A](value: Either[RegistrationError, A]): A =
    value.fold(e => throw new IllegalArgumentException(e.message), identity)

  private def checkedAffine(values: Vector[Double]): Affine[D3] =
    Affine.fromRowMajor[D3](values).fold(e => throw new IllegalArgumentException(e.message), identity)

  private def protect[A](body: => A): Either[RegistrationError, A] =
    try Right(body)
    catch case NonFatal(error) => Left(RegistrationError.InvalidConfiguration("query bundle: " + error.getMessage))

  private def checksum(bytes: Array[Byte], length: Int): Int =
    var a = 1
    var b = 0
    var i = 0
    while i < length do
      a = (a + (bytes(i) & 255)) % 65521
      b = (b + a) % 65521
      i += 1
    (b << 16) | a
