package reframe4s.flashalign

import reframe4s.spectral.SpectralError
import reframe4s.spectral.SpectralShape3

private[flashalign] enum StructuralCaptureQuantity derives CanEqual:
  case Origin, Spacing, ScalarValue, Support, GradientThreshold, Rotation

private[flashalign] sealed trait StructuralCaptureError derives CanEqual:
  def message: String

private[flashalign] object StructuralCaptureError:
  final case class InvalidExtent(axis: Int, value: Int)
      extends StructuralCaptureError:
    val message: String = s"capture extent $axis must be at least 3, got $value"

  final case class InvalidLength(name: String, expected: Int, actual: Int)
      extends StructuralCaptureError:
    val message: String = s"$name requires $expected values, got $actual"

  final case class NonFiniteValue(
      quantity: StructuralCaptureQuantity,
      index: Int,
      value: Double
  ) extends StructuralCaptureError:
    val message: String = s"$quantity value $index must be finite, got $value"

  final case class InvalidPositiveValue(
      quantity: StructuralCaptureQuantity,
      value: Double
  ) extends StructuralCaptureError:
    val message: String = s"$quantity must be finite and positive, got $value"

  final case class InvalidSupport(index: Int, value: Double)
      extends StructuralCaptureError:
    val message: String = s"support value $index must be in [0, 1], got $value"

  final case class InvalidRotation(detail: String) extends StructuralCaptureError:
    val message: String = detail

  final case class LatticeMismatch(detail: String) extends StructuralCaptureError:
    val message: String = detail

  final case class InvalidConfiguration(detail: String)
      extends StructuralCaptureError:
    val message: String = detail

  final case class Spectral(error: SpectralError) extends StructuralCaptureError:
    val message: String = error.message

  case object WorkspacePlanMismatch extends StructuralCaptureError:
    val message: String = "capture workspace belongs to another plan"

  case object WorkspaceInUse extends StructuralCaptureError:
    val message: String = "capture workspace is already active"

/** Axis-aligned physical lattice used only by the bounded capture stage. */
private[flashalign] final class CaptureLattice3 private (
    val shape: SpectralShape3,
    val originXMillimetres: Double,
    val originYMillimetres: Double,
    val originZMillimetres: Double,
    val spacingXMillimetres: Double,
    val spacingYMillimetres: Double,
    val spacingZMillimetres: Double
):
  val elementCount: Int = shape.elementCount

  def index(x: Int, y: Int, z: Int): Int =
    (x * shape.y + y) * shape.z + z

  def worldX(x: Int): Double = originXMillimetres + x * spacingXMillimetres
  def worldY(y: Int): Double = originYMillimetres + y * spacingYMillimetres
  def worldZ(z: Int): Double = originZMillimetres + z * spacingZMillimetres

private[flashalign] object CaptureLattice3:
  def create(
      shape: SpectralShape3,
      originXMillimetres: Double = 0.0,
      originYMillimetres: Double = 0.0,
      originZMillimetres: Double = 0.0,
      spacingXMillimetres: Double = 1.0,
      spacingYMillimetres: Double = 1.0,
      spacingZMillimetres: Double = 1.0
  ): Either[StructuralCaptureError, CaptureLattice3] =
    val extents = Array(shape.x, shape.y, shape.z)
    var axis = 0
    while axis < extents.length do
      if extents(axis) < 3 then
        return Left(StructuralCaptureError.InvalidExtent(axis, extents(axis)))
      axis += 1
    val origins = Array(originXMillimetres, originYMillimetres, originZMillimetres)
    axis = 0
    while axis < origins.length do
      if !origins(axis).isFinite then
        return Left(
          StructuralCaptureError.NonFiniteValue(
            StructuralCaptureQuantity.Origin,
            axis,
            origins(axis)
          )
        )
      axis += 1
    val spacings = Array(spacingXMillimetres, spacingYMillimetres, spacingZMillimetres)
    axis = 0
    while axis < spacings.length do
      if !spacings(axis).isFinite || spacings(axis) <= 0.0 then
        return Left(
          StructuralCaptureError.InvalidPositiveValue(
            StructuralCaptureQuantity.Spacing,
            spacings(axis)
          )
        )
      axis += 1
    Right(
      new CaptureLattice3(
        shape,
        originXMillimetres,
        originYMillimetres,
        originZMillimetres,
        spacingXMillimetres,
        spacingYMillimetres,
        spacingZMillimetres
      )
    )

private[flashalign] final class CaptureScalarVolume3 private (
    val lattice: CaptureLattice3,
    private[flashalign] val values: Array[Double],
    private[flashalign] val support: Array[Double]
)

private[flashalign] object CaptureScalarVolume3:
  def create(
      lattice: CaptureLattice3,
      values: Array[Double],
      support: Array[Double]
  ): Either[StructuralCaptureError, CaptureScalarVolume3] =
    validateArray("scalar image", lattice.elementCount, values, StructuralCaptureQuantity.ScalarValue)
      .flatMap(_ => validateSupport(lattice.elementCount, support))
      .map(_ => new CaptureScalarVolume3(lattice, values.clone(), support.clone()))

  private def validateArray(
      name: String,
      expected: Int,
      values: Array[Double],
      quantity: StructuralCaptureQuantity
  ): Either[StructuralCaptureError, Unit] =
    if values.length != expected then
      Left(StructuralCaptureError.InvalidLength(name, expected, values.length))
    else
      var index = 0
      while index < values.length do
        if !values(index).isFinite then
          return Left(
            StructuralCaptureError.NonFiniteValue(quantity, index, values(index))
          )
        index += 1
      Right(())

  private def validateSupport(
      expected: Int,
      support: Array[Double]
  ): Either[StructuralCaptureError, Unit] =
    if support.length != expected then
      Left(StructuralCaptureError.InvalidLength("support", expected, support.length))
    else
      var index = 0
      while index < support.length do
        val value = support(index)
        if !value.isFinite || value < 0.0 || value > 1.0 then
          return Left(StructuralCaptureError.InvalidSupport(index, value))
        index += 1
      Right(())

/** Six unique components of a fixed-world normalized-gradient tensor. */
private[flashalign] final class NormalizedGradientTensor3 private[flashalign] (
    val lattice: CaptureLattice3,
    private[flashalign] val components: Array[Array[Double]],
    private[flashalign] val support: Array[Double]
):
  def component(component: Int, index: Int): Double = components(component)(index)
  def supportAt(index: Int): Double = support(index)
  val totalSupport: Double = support.sum

private[flashalign] object NormalizedGradientTensor3:
  val ComponentCount: Int = 6

  def fromScalar(
      volume: CaptureScalarVolume3,
      minimumGradientEnergy: Double
  ): Either[StructuralCaptureError, NormalizedGradientTensor3] =
    if !minimumGradientEnergy.isFinite || minimumGradientEnergy <= 0.0 then
      Left(
        StructuralCaptureError.InvalidPositiveValue(
          StructuralCaptureQuantity.GradientThreshold,
          minimumGradientEnergy
        )
      )
    else
      val lattice = volume.lattice
      val components = Array.fill(ComponentCount)(new Array[Double](lattice.elementCount))
      val outputSupport = new Array[Double](lattice.elementCount)
      var x = 1
      while x < lattice.shape.x - 1 do
        var y = 1
        while y < lattice.shape.y - 1 do
          var z = 1
          while z < lattice.shape.z - 1 do
            val center = lattice.index(x, y, z)
            val xm = lattice.index(x - 1, y, z)
            val xp = lattice.index(x + 1, y, z)
            val ym = lattice.index(x, y - 1, z)
            val yp = lattice.index(x, y + 1, z)
            val zm = lattice.index(x, y, z - 1)
            val zp = lattice.index(x, y, z + 1)
            val localSupport = math.min(
              volume.support(center),
              math.min(
                math.min(volume.support(xm), volume.support(xp)),
                math.min(
                  math.min(volume.support(ym), volume.support(yp)),
                  math.min(volume.support(zm), volume.support(zp))
                )
              )
            )
            if localSupport > 0.0 then
              val gx =
                (volume.values(xp) - volume.values(xm)) /
                  (2.0 * lattice.spacingXMillimetres)
              val gy =
                (volume.values(yp) - volume.values(ym)) /
                  (2.0 * lattice.spacingYMillimetres)
              val gz =
                (volume.values(zp) - volume.values(zm)) /
                  (2.0 * lattice.spacingZMillimetres)
              val energy = gx * gx + gy * gy + gz * gz
              if energy >= minimumGradientEnergy then
                val inverseNorm = 1.0 / math.sqrt(energy)
                val nx = gx * inverseNorm
                val ny = gy * inverseNorm
                val nz = gz * inverseNorm
                components(0)(center) = nx * nx
                components(1)(center) = ny * ny
                components(2)(center) = nz * nz
                components(3)(center) = nx * ny
                components(4)(center) = nx * nz
                components(5)(center) = ny * nz
                outputSupport(center) = localSupport
            z += 1
          y += 1
        x += 1
      Right(new NormalizedGradientTensor3(lattice, components, outputSupport))

  def create(
      lattice: CaptureLattice3,
      components: Array[Array[Double]],
      support: Array[Double]
  ): Either[StructuralCaptureError, NormalizedGradientTensor3] =
    if components.length != ComponentCount then
      Left(
        StructuralCaptureError.InvalidLength(
          "tensor component array",
          ComponentCount,
          components.length
        )
      )
    else
      var component = 0
      while component < ComponentCount do
        if components(component).length != lattice.elementCount then
          return Left(
            StructuralCaptureError.InvalidLength(
              s"tensor component $component",
              lattice.elementCount,
              components(component).length
            )
          )
        var index = 0
        while index < lattice.elementCount do
          val value = components(component)(index)
          if !value.isFinite then
            return Left(
              StructuralCaptureError.NonFiniteValue(
                StructuralCaptureQuantity.ScalarValue,
                index,
                value
              )
            )
          index += 1
        component += 1
      CaptureScalarVolume3
        .create(lattice, new Array[Double](lattice.elementCount), support)
        .map(_ =>
          new NormalizedGradientTensor3(
            lattice,
            components.map(_.clone()),
            support.clone()
          )
        )

/** Proper fixed-world rotation used for both sample locations and tensors. */
private[flashalign] final class CaptureRotation3 private (
    private[flashalign] val rowMajor: Array[Double]
):
  def determinant: Double =
    val r = rowMajor
    r(0) * (r(4) * r(8) - r(5) * r(7)) -
      r(1) * (r(3) * r(8) - r(5) * r(6)) +
      r(2) * (r(3) * r(7) - r(4) * r(6))

private[flashalign] object CaptureRotation3:
  val Identity: CaptureRotation3 =
    new CaptureRotation3(Array(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

  def fromEulerXyzRadians(
      x: Double,
      y: Double,
      z: Double
  ): Either[StructuralCaptureError, CaptureRotation3] =
    val angles = Array(x, y, z)
    var index = 0
    while index < angles.length do
      if !angles(index).isFinite then
        return Left(
          StructuralCaptureError.NonFiniteValue(
            StructuralCaptureQuantity.Rotation,
            index,
            angles(index)
          )
        )
      index += 1
    val cx = math.cos(x)
    val sx = math.sin(x)
    val cy = math.cos(y)
    val sy = math.sin(y)
    val cz = math.cos(z)
    val sz = math.sin(z)
    Right(
      new CaptureRotation3(
        Array(
          cz * cy,
          cz * sy * sx - sz * cx,
          cz * sy * cx + sz * sx,
          sz * cy,
          sz * sy * sx + cz * cx,
          sz * sy * cx - cz * sx,
          -sy,
          cy * sx,
          cy * cx
        )
      )
    )

private[flashalign] object StructuralTensorRotation3:
  def rotated(
      source: NormalizedGradientTensor3,
      rotation: CaptureRotation3,
      pivotX: Double,
      pivotY: Double,
      pivotZ: Double,
      minimumInterpolatedSupport: Double
  ): Either[StructuralCaptureError, NormalizedGradientTensor3] =
    if !minimumInterpolatedSupport.isFinite || minimumInterpolatedSupport <= 0.0 then
      Left(
        StructuralCaptureError.InvalidConfiguration(
          s"minimum interpolated support must be positive, got $minimumInterpolatedSupport"
        )
      )
    else
      val components = Array.fill(6)(new Array[Double](source.lattice.elementCount))
      val support = new Array[Double](source.lattice.elementCount)
      rotateInto(
        source,
        rotation,
        pivotX,
        pivotY,
        pivotZ,
        minimumInterpolatedSupport,
        components,
        support,
        new Array[Double](7)
      )
      Right(new NormalizedGradientTensor3(source.lattice, components, support))

  def rotateInto(
      source: NormalizedGradientTensor3,
      rotation: CaptureRotation3,
      pivotX: Double,
      pivotY: Double,
      pivotZ: Double,
      minimumInterpolatedSupport: Double,
      outputComponents: Array[Array[Double]],
      outputSupport: Array[Double],
      sampleScratch: Array[Double]
  ): Unit =
    outputComponents.foreach(java.util.Arrays.fill(_, 0.0))
    java.util.Arrays.fill(outputSupport, 0.0)
    val lattice = source.lattice
    val r = rotation.rowMajor
    var x = 0
    while x < lattice.shape.x do
      val worldX = lattice.worldX(x)
      var y = 0
      while y < lattice.shape.y do
        val worldY = lattice.worldY(y)
        var z = 0
        while z < lattice.shape.z do
          val worldZ = lattice.worldZ(z)
          val dx = worldX - pivotX
          val dy = worldY - pivotY
          val dz = worldZ - pivotZ
          val sourceWorldX = pivotX + r(0) * dx + r(3) * dy + r(6) * dz
          val sourceWorldY = pivotY + r(1) * dx + r(4) * dy + r(7) * dz
          val sourceWorldZ = pivotZ + r(2) * dx + r(5) * dy + r(8) * dz
          sampleTensorInto(
            source,
            sourceWorldX,
            sourceWorldY,
            sourceWorldZ,
            minimumInterpolatedSupport,
            sampleScratch
          )
          val destination = lattice.index(x, y, z)
          if sampleScratch(0) >= minimumInterpolatedSupport then
            rotateTensor(sampleScratch, r, outputComponents, destination)
            outputSupport(destination) = sampleScratch(0)
          z += 1
        y += 1
      x += 1

  private def sampleTensorInto(
      source: NormalizedGradientTensor3,
      worldX: Double,
      worldY: Double,
      worldZ: Double,
      minimumSupport: Double,
      output: Array[Double]
  ): Unit =
    val lattice = source.lattice
    val ix = (worldX - lattice.originXMillimetres) / lattice.spacingXMillimetres
    val iy = (worldY - lattice.originYMillimetres) / lattice.spacingYMillimetres
    val iz = (worldZ - lattice.originZMillimetres) / lattice.spacingZMillimetres
    val x0 = math.floor(ix).toInt
    val y0 = math.floor(iy).toInt
    val z0 = math.floor(iz).toInt
    if x0 < 0 || y0 < 0 || z0 < 0 ||
      x0 >= lattice.shape.x - 1 || y0 >= lattice.shape.y - 1 ||
      z0 >= lattice.shape.z - 1
    then java.util.Arrays.fill(output, 0.0)
    else
      val tx = ix - x0
      val ty = iy - y0
      val tz = iz - z0
      var weightedSupport = 0.0
      var xx = 0.0
      var yy = 0.0
      var zz = 0.0
      var xy = 0.0
      var xz = 0.0
      var yz = 0.0
      var ox = 0
      while ox <= 1 do
        val wx = if ox == 0 then 1.0 - tx else tx
        var oy = 0
        while oy <= 1 do
          val wy = if oy == 0 then 1.0 - ty else ty
          var oz = 0
          while oz <= 1 do
            val wz = if oz == 0 then 1.0 - tz else tz
            val index = lattice.index(x0 + ox, y0 + oy, z0 + oz)
            val weighted = wx * wy * wz * source.support(index)
            weightedSupport += weighted
            xx += weighted * source.components(0)(index)
            yy += weighted * source.components(1)(index)
            zz += weighted * source.components(2)(index)
            xy += weighted * source.components(3)(index)
            xz += weighted * source.components(4)(index)
            yz += weighted * source.components(5)(index)
            oz += 1
          oy += 1
        ox += 1
      if weightedSupport < minimumSupport then
        java.util.Arrays.fill(output, 0.0)
        output(0) = weightedSupport
      else
        val inverse = 1.0 / weightedSupport
        output(0) = weightedSupport
        output(1) = xx * inverse
        output(2) = yy * inverse
        output(3) = zz * inverse
        output(4) = xy * inverse
        output(5) = xz * inverse
        output(6) = yz * inverse

  private def rotateTensor(
      sampled: Array[Double],
      r: Array[Double],
      output: Array[Array[Double]],
      index: Int
  ): Unit =
    output(0)(index) = tensorBilinear(sampled, r, 0, 0)
    output(1)(index) = tensorBilinear(sampled, r, 1, 1)
    output(2)(index) = tensorBilinear(sampled, r, 2, 2)
    output(3)(index) = tensorBilinear(sampled, r, 0, 1)
    output(4)(index) = tensorBilinear(sampled, r, 0, 2)
    output(5)(index) = tensorBilinear(sampled, r, 1, 2)

  private def tensorBilinear(
      tensor: Array[Double],
      rotation: Array[Double],
      leftRow: Int,
      rightRow: Int
  ): Double =
    val ax = rotation(3 * leftRow)
    val ay = rotation(3 * leftRow + 1)
    val az = rotation(3 * leftRow + 2)
    val bx = rotation(3 * rightRow)
    val by = rotation(3 * rightRow + 1)
    val bz = rotation(3 * rightRow + 2)
    ax * bx * tensor(1) + ay * by * tensor(2) + az * bz * tensor(3) +
      (ax * by + ay * bx) * tensor(4) +
      (ax * bz + az * bx) * tensor(5) +
      (ay * bz + az * by) * tensor(6)
