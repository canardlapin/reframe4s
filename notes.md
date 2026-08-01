The library should sit beneath registration

The right foundation is not “images, metrics, and optimizers.” It is a calculus of spatial correspondences and their lawful actions on spatial objects.

From that substrate:

* Motion correction is estimation of a time-indexed curve of rigid correspondences.
* Affine registration is estimation within a finite-dimensional family of affine isomorphisms.
* Nonlinear registration is estimation within smooth-map, flow, or diffeomorphism families.
* Normalization is composition of correspondences into a chosen reference frame.
* Warping is transport of a spatial object along a correspondence.
* Resampling is the numerical realization of that transport on a discrete grid.

That reduction should govern the entire design.

ITK gets one foundational point right: an image is not merely an array; it is a grid embedded in physical space through its origin, spacing, direction, and extent. It also correctly formulates resampling in terms of an output grid, a map from output coordinates into source coordinates, and an interpolator. Its documentation notes that accidentally using the inverse transform is a common error. A Scala library can make that error unrepresentable.

⸻

1. The mathematical nucleus: a groupoid of spatial frames

1.1 Frames are objects; maps are arrows

Let A, B, and C denote spatial frames.

A spatial map is an arrow

\phi : A \longrightarrow B.

Maps compose only when their intermediate frames agree:

A \xrightarrow{\phi} B \xrightarrow{\psi} C
\quad\Longrightarrow\quad
\psi\circ\phi : A\longrightarrow C.

Every frame has an identity map. Invertible maps have inverses. Thus:

* all spatial maps form a category;
* invertible spatial maps form a groupoid;
* endomorphisms of a single frame form a group.

This last distinction is important. A rigid map from subject space to atlas space is not itself an element of a single group in the same sense as a rigid update within atlas coordinates. It is an arrow between frames. Left and right updates live in different tangent spaces.

This gives the core its first set of types:

sealed trait Dim
sealed trait D2 extends Dim
sealed trait D3 extends Dim
trait Frame[D <: Dim]
trait SpatialMap[
  From <: Frame[D],
  To   <: Frame[D],
  D    <: Dim
]:
  def source: From
  def target: To
  def apply(p: Point[From, D]): Point[To, D]
trait SmoothMap[
  From <: Frame[D],
  To   <: Frame[D],
  D    <: Dim
] extends SpatialMap[From, To, D]:
  def jet1At(p: Point[From, D]): Jet1[From, To, D]
trait SmoothIso[
  From <: Frame[D],
  To   <: Frame[D],
  D    <: Dim
] extends SmoothMap[From, To, D]:
  def inverse: SmoothIso[To, From, D]

SmoothIso is an exact semantic capability. Its inverse must be analytic or
lawfully derived from an exact representation, and composition must preserve
the two-sided inverse laws. A numerically inverted dense map does not become a
SmoothIso merely because an iterative routine converged; approximate inverses
and grid-scoped numerical evidence have separate types below.

Composition preserves the strongest capability justified by both operands:

extension [A <: Frame[D], B <: Frame[D], D <: Dim]
  (f: SpatialMap[A, B, D])
  def andThen[C <: Frame](
    g: SpatialMap[B, C, D]
  ): SpatialMap[A, C, D]

There should also be overloads or internal composition instances so that:

SmoothMap[A, B, D] andThen SmoothMap[B, C, D]

returns a SmoothMap[A, C, D], while two SmoothIsos return a SmoothIso.

The machinery needed to infer the result of heterogeneous composition can remain private. Users should never see a match type such as ComposeResult[K1, K2].

1.2 Frames should be easy to create and impossible to conflate

Developers should not have to declare phantom marker traits for every image. Scala singleton types can generate frame types from ordinary values:

val native = Frame.fresh[D3](FrameMetadata.named("sub-01:T1w"))
val atlas  = Frame.fresh[D3](FrameMetadata.named("MNI152NLin2009cAsym"))
val moving =
  Image3.in(native)(
    data = nativeData,
    geometry = nativeGeometry
  )
val fixed =
  Image3.in(atlas)(
    data = atlasData,
    geometry = atlasGeometry
  )

moving can have the inferred type:

ScalarImage3[Float, native.type]

and fixed:

ScalarImage3[Float, atlas.type]

No user-written phantom classes are required.

A frame value carries a persistent identifier, validated metadata, and an
unforgeable live token:

final class Frame[D <: Dim] private (
  val id: FrameId,
  val metadata: FrameMetadata,
  val unit: LengthUnit,
  val convention: CoordinateConvention,
  private val runtimeToken: RuntimeFrameToken
)

`Frame.named[D](name)`, if offered as a convenience, is exactly a shorthand
for `Frame.fresh[D](FrameMetadata.named(name))`. Two calls with the same name
produce different `FrameId` values and different runtime owners. Display names
are never identity.

Serialization writes a `FrameRecord` containing `FrameId`, dimension, and all
identity-relevant metadata. `Frame.restore[D](record, registry)` checks the
record's dimension and metadata, then resolves the one registered live owner
for that `FrameId`, or installs it if absent. A conflicting record fails.
`Frame.align(left, right)` returns a typed alignment value only after the
persistent identifiers, dimensions, and metadata agree. That value explicitly
rebinds points and vectors from one checked live owner to the other; it does
not fabricate Scala `=:=` evidence between distinct runtime objects.
Existential values from files, transform graphs, and plugin boundaries must
use `restore` and `align`; unchecked casts cannot recover static evidence.

`GridId` is a separate persistent identifier. Multiple grids may embed
different lattices in the same frame. A locus `DomainId` is separate from both
because a finite domain is not a coordinate frame or a grid.

Within a typed pipeline, singleton types enforce direction. Across
serialization, BIDS metadata, transform files, or a transform graph, the
checked registry and alignment operations recover that evidence.

1.3 Not everything belongs at the type level

A disciplined division would be:

Encode in types	Keep as values
Source and target frames	Grid extents
Dimension: 2D or 3D	Spacing and origin
Point versus vector versus index	Interpolation order
Generic map versus smooth map versus isomorphism	Boundary policy
Scalar, label, vector, density, tensor semantics	Tolerances and certificates
Velocity versus displacement versus momentum	Regularization strengths
Direction of composition	Pyramid schedules

Do not put voxel counts, control-grid sizes, smoothing widths, optimization schedules, or exact Jacobian bounds into types.

Scala 3 opaque types are particularly appropriate for distinctions such as point, vector, velocity, displacement, momentum, length, and angle because they provide abstraction without adding wrapper overhead.

⸻

2. Points, vectors, indices, and grids must be distinct

The numerical representation may be similar, but the meanings are not.

opaque type Point[F <: Frame[D], D <: Dim] = Coord[D]
opaque type Vec[F <: Frame[D], D <: Dim]   = Coord[D]
opaque type Index[D <: Dim]             = IntCoord[D]
opaque type ContinuousIndex[D <: Dim]   = Coord[D]

The lawful operations are:

Point[F, D] + Vec[F, D]   => Point[F, D]
Point[F, D] - Point[F, D] => Vec[F, D]
Vec[F, D] + Vec[F, D]     => Vec[F, D]

There is no:

Point + Point
Index + Point
Point[native] + Vec[atlas]

A grid embeds a discrete lattice in a physical frame:

final class Grid[F <: Frame[D], D <: Dim] private (
  val frame: F,
  val shape: Shape[D],
  val id: GridId,
  val indexToFrame: Affine[D]
):
  def pointAt(index: Index[D]): Point[F, D]
  def continuousIndexOf(point: Point[F, D]): ContinuousIndex[D]

Affine[D] is the one validated numerical coordinate operator owned by
image4s-geometry. The grid supplies the index-frame and physical-frame
meaning; a framed affine map wraps the same operator rather than defining a
second matrix or affine algebra. The familiar origin, spacing, and direction
matrix are a convenient construction view of indexToFrame; they are not the
deeper abstraction.

Transforms should operate in physical coordinates by default. Index-space transforms should be explicit and tied to a particular grid. This prevents voxel-space matrices from being silently applied as world-space matrices.

⸻

3. The most important separation: field, pullback, and resampling

3.1 A continuous field is not a sampled image

A scalar field on frame A is conceptually

f : A \longrightarrow V.

A sampled image is a finite collection of observations of such a field on a
grid. There is exactly one owned representation:

trait Field[F <: Frame[D], D <: Dim, V]:
  def at(p: Point[F, D]): Sample[V]
final class Sampled[
  F <: Frame[D],
  D <: Dim,
  A,
  Role <: FieldRole,
  R <: Rank
] private (
  val data: Ravel[A, R],
  val grid: Grid[F, D],
  val nonSpatialAxes: AxisSet
)

Construction validates:

data.shape == grid.shape ++ nonSpatialAxes.shape.

`Image`, `ImageSeries`, `ScalarImage`, `LabelImage`, and their D2/D3
convenience names are type aliases or zero-copy views over `Sampled`; they do
not own storage or define parallel shape, grid, or identity invariants. A view
may narrow the role or expose a convenient axis interpretation, but the
underlying `Sampled` remains the single owner.

Interpolation turns a sampled field into an evaluable field:

def interpolate[F, D, A, Role, R](
  image: Sampled[F, D, A, Role, R],
  kernel: Interpolator[A, Role, D],
  boundary: BoundaryPolicy[A]
): Field[F, D, A]

The distinction is not philosophical decoration. It determines which laws are exact and which are only numerical approximations.

3.2 Scalar fields transform by pullback

Given

\phi : A \rightarrow B

and a field f on B, the pullback is a field on A:

\phi^\*f = f\circ\phi.

def pullback[
  A <: Frame,
  B <: Frame,
  D <: Dim,
  V
](
  field: Field[B, D, V],
  along: SpatialMap[A, B, D]
): Field[A, D, V]

This has exact semantic laws:

\mathrm{id}^{*}f = f

and

(\psi\circ\phi)^{*}f
=
\phi^{*}(\psi^{*}f).

To transport a source field forward along an invertible map

\phi : A\rightarrow B,

one uses the inverse pullback:

\operatorname{transport}_{\phi}(f)
=
(\phi^{-1})^{*}f
=
f\circ\phi^{-1}.

That distinction explains the apparently reversed transform direction used by resamplers.

3.3 Resampling is a terminal numerical operation

Suppose a source image lives in A, and we want samples on a grid in B. The primitive operation requires a map:

B \rightarrow A.

val output =
  Resample(moving)
    .onto(fixed.grid)
    .through(fit.fixedToMoving) // fixed frame -> moving frame
    .using(Linear)
    .run()

Passing fit.movingToFixed, whose type is native.type -> atlas.type, cannot
compile because the resampler requires atlas.type -> native.type.

The high-level operation can remain intuitive:

val output =
  fit.warpMoving(moving)
    .onto(fixed.grid)
    .using(Linear)
    .run()

Internally, it uses fit.fixedToMoving.

3.4 Never resample between transform stages

At the continuous-field level, pullbacks compose lawfully. At the discrete-image level, sequential resampling introduces interpolation loss:

\operatorname{sample}
\bigl(
\phi^{*}(
\operatorname{sample}(\psi^{*}f)
)\bigr)
\neq
\operatorname{sample}
\bigl(
(\psi\circ\phi)^{*}f
\bigr).

Therefore a transform chain should remain symbolic until the final output grid is known:

val boldToMni =
  motionToReference
    .andThen(referenceToT1)
    .andThen(t1ToMni)
val mniToBold = boldToMni.inverse
val normalized =
  Resample(boldVolume)
    .onto(mniGrid)
    .through(mniToBold)
    .run()

The chain is composed first. The image is sampled once.

This is one of the core laws of the architecture, even though it is not an equality law of discrete resampling.

⸻

4. Spatial values have transformation laws

A Vec3 stored at every voxel could represent:

* RGB channels;
* a physical tangent vector;
* a covector or image gradient;
* a displacement;
* a fiber orientation;
* three unrelated scalar measurements.

The library must not infer transformation semantics merely from storage shape.

4.1 A field role determines the fiber action

A spatially varying value belongs to a representation of the local Jacobian action. The library should make this explicit:

sealed trait FieldRole
sealed trait ScalarRole          extends FieldRole
sealed trait LabelRole           extends FieldRole
sealed trait TangentVectorRole   extends FieldRole
sealed trait CovectorRole        extends FieldRole
sealed trait DensityRole         extends FieldRole
sealed trait SymmetricTensorRole extends FieldRole
sealed trait OrientationRole     extends FieldRole
sealed trait ChannelsRole        extends FieldRole

A FiberTransport describes how values respond to the map’s differential:

trait FiberTransport[
  V,
  Role <: FieldRole,
  D <: Dim
]:
  def forward(
    value: V,
    differential: Jacobian[D],
    inverseDifferential: Jacobian[D]
  ): V

Then one generic transport mechanism handles:

* scalar pullback;
* label transport;
* tangent-vector pushforward;
* covector transformation;
* density scaling by a Jacobian determinant;
* tensor reorientation;
* orientations and directed fibers.

Tensor methods can provide alternative policies—finite strain, preservation of principal directions, or a custom representation—without contaminating the transform core.

4.2 Interpolation is constrained by field role

The type system should reject meaningless combinations:

LabelImage + LinearInterpolator     // rejected
LabelImage + NearestNeighbor        // valid
ScalarImage + LinearInterpolator    // valid
ScalarImage + CubicBSpline          // valid
TangentVectorField + Linear         // valid with vector transport
RGBImage + TensorReorientation      // rejected

A label interpolator should preserve the input label set by construction. An ordinary scalar interpolator should not be usable merely because labels happen to be stored as integers.

⸻

5. Smooth maps and their first jets

A smooth map is often evaluated together with its spatial derivative. These should be a single reusable concept:

final case class Jet1[
  From <: Frame,
  To   <: Frame,
  D    <: Dim
](
  value: Point[To, D],
  jacobian: Jacobian[From, To, D]
)

The first-jet composition law is:

J(\psi\circ\phi)_x
=
J\psi_{\phi(x)}
\,J\phi_x.

A Jet1 is useful for:

* vector and tensor transport;
* Jacobian determinant maps;
* morphometry;
* topology diagnostics;
* normal and surface transport;
* image-gradient propagation;
* registration derivatives;
* fused map-and-gradient resampling.

It also cleanly distinguishes two different derivatives:

1. Spatial derivative
    D_x\phi
    describing how nearby points move.
2. Parameter derivative
    D_p\phi
    describing how the map changes when an optimization parameter changes.

They should never share one generic jacobian method.

A transform model’s parameter derivative should instead use matrix-free linearization:

trait MapLinearization[P, Delta, From, To, D]:
  def jvp(delta: Delta, at: Point[From, D]): Vec[To, D]
  def vjp(force: Vec[To, D], at: Point[From, D]): Delta

In practice the bulk versions matter more than scalar calls.

⸻

6. Rigid and affine geometry

6.1 There is one affine algebra

image4s-geometry owns the sole affine coordinate value:

final class Affine[D <: Dim] private (
  linear: InvertibleMatrix[D],
  translation: Coord[D]
):
  def inverse: Affine[D]

It is immutable and validated, and it is the operator used by Grid for
index-to-frame embedding. Gale supplies matrices and decompositions; it does
not define a competing spatial affine value. A typed map between frames adds
semantic evidence by wrapping that same operator:

final class FramedAffine[
  From <: Frame,
  To   <: Frame,
  D    <: Dim
] private (
  val source: From,
  val target: To,
  val operator: Affine[D]
) extends SmoothIso[From, To, D]

No image, resampling, registration, or ScalaFIM artifact may define a parallel
affine matrix or affine composition algebra.

6.2 Transform values are not optimizer parameterizations

An affine map is a mathematical value:

x \mapsto Ax+b.

Its center-of-rotation representation, Euler-angle ordering, quaternion convention, or optimizer scaling is a chart, not part of the map itself.

Separate model classes describe parameterization:

trait TransformModel[
  State,
  Delta,
  From <: Frame,
  To   <: Frame,
  D    <: Dim
]:
  def map(state: State): SpatialMap[From, To, D]
  def retract(state: State, delta: Delta): State
  def linearize(state: State): MapLinearization[
    State, Delta, From, To, D
  ]

Examples include:

RigidModel3(center)
SimilarityModel3(center)
AffineExponentialChart3(center)
AffinePolarChart3(center)

Two parameterizations can produce the same FramedAffine value.

6.3 Between-frame rigid maps are arrows; updates are group elements

For a current estimate

T : A \rightarrow B,

a left update is:

T' = \exp(\delta_B)\circ T

where \delta_B is expressed in the target frame.

A right update is:

T' = T\circ\exp(\delta_A)

where \delta_A is expressed in the source frame.

The API should expose this distinction:

estimate.leftUpdated(deltaInTarget)
estimate.rightUpdated(deltaInSource)

The adjoint converts twists between trivializations:

targetDelta =
  estimate.adjoint(sourceDelta)

This is substantially better than treating six rigid parameters as an ordinary vector and writing parameters + step.

6.4 Concrete finite-dimensional families

The reusable Lie module should provide:

* translations;
* rotations;
* orientation-preserving rigid maps;
* optional reflection-containing Euclidean isometries;
* similarities;
* invertible affines;
* orientation-preserving affines;
* exponential and local retraction operations;
* adjoint actions;
* polar and QR decompositions;
* stable composition and inversion;
* distance and norm conventions.

A rigid map should lawfully preserve distance. A positive affine should have positive determinant. Constructors for invertible maps should be private and validated.

⸻

7. Nonlinear geometry: do not conflate distinct capabilities

The nonlinear core should distinguish:

Type	Meaning
DenseMap[A, B]	Absolute coordinate map A\to B
Displacement[F]	Field u defining x\mapsto x+u(x) within one affine frame
Velocity[F]	Tangent vector field generating a flow
Momentum[F]	Dual variable related to velocity through a metric operator
Flow[F]	A path of maps \phi_t
Diffeomorphism[A, B]	Theoretical exact smooth isomorphism, represented only by a lawful SmoothIso

The underlying buffers may all contain three floating-point channels. Their mathematics is entirely different.

7.1 A displacement requires an identity map

The formula

\phi(x)=x+u(x)

only makes natural sense when the domain and codomain share an affine structure. Consequently:

Displacement[F, D]

should generate an endomorphism:

Deformation[F, F, D]

A dense map between unrelated frames should be stored as absolute coordinates:

DenseMap[A, B, D]

or as an explicit base map plus a local displacement:

BasePlusDisplacement[A, B, D](
  base: SmoothIso[A, B, D],
  local: Displacement[B, D]
)

This prevents a very common hidden assumption: treating the identity matrix as an implicit identification between subject and atlas coordinates.

7.2 Velocity and momentum need a geometry

A velocity space is not complete until its norm or metric is specified.

trait FieldGeometry[
  V,
  M
]:
  def lower(velocity: V): M      // m = L v
  def raise(momentum: M): V      // v = K m
  def energy(velocity: V): Double

Implementations can include:

* membrane or diffusion geometry;
* elastic geometry;
* Helmholtz–Sobolev geometry;
* RKHS kernels;
* spatially varying geometry;
* incompressible or divergence-penalized geometry;
* Hodge-split div–curl–shear geometry.

This is where the proposed Hodge Flow machinery should live. Its regularization is a FieldGeometry; its spectral or Hodge preconditioner is an implementation of the corresponding Riesz map or approximate inverse. It should not be welded to one end-to-end registration class.

7.3 Flow is more fundamental than SVF

The core abstraction should be a flow:

trait VelocityPath[F <: Frame, D <: Dim]:
  def velocityAt(t: Time): Velocity[F, D]
trait FlowIntegrator:
  def integrate[F, D](
    path: VelocityPath[F, D],
    domain: Domain[F, D],
    settings: IntegrationSettings
  ): FlowResult[F, D]

Stationary velocity is one specialization:

final case class StationaryVelocity[F, D](
  value: Velocity[F, D]
) extends VelocityPath[F, D]

The diffeomorphic literature includes time-varying geodesic-flow formulations such as LDDMM, symmetric diffeomorphic constructions such as SyN, and stationary-velocity/log-domain approaches used by log-Euclidean methods and diffeomorphic demons. The core must therefore place Flow and SmoothIso above any one parameterization.

Potential integrators include:

* scaling and squaring for stationary velocity;
* piecewise-stationary composition;
* Runge–Kutta integration;
* semi-Lagrangian integration;
* geodesic shooting;
* paired endpoint/inverse-estimate integration.

Each returns not just a map, but numerical evidence:

final class InverseEstimate[A <: Frame[D], B <: Frame[D], D <: Dim] private (
  val value: SpatialMap[A, B, D],
  val residual: InverseResidual,
  val status: InversionStatus,
  val domain: InverseDomain[A, D],
  val criteria: InverseCriteria,
  val implementationRevision: ImplementationRevision
)
final class CertifiedBidirectionalPair[
  A <: Frame[D],
  B <: Frame[D],
  D <: Dim
] private (
  val toTarget: SpatialMap[A, B, D],
  val toSource: SpatialMap[B, A, D],
  val evidence: InversePairEvidence,
  val forwardDomain: InverseDomain[A, D],
  val reverseDomain: InverseDomain[B, D],
  val criteria: InverseCriteria,
  val implementationRevision: ImplementationRevision
)
final case class FlowResult[F, D](
  endpoint: DenseMap[F, F, D],
  inverseEstimate: Option[InverseEstimate[F, F, D]],
  integrationError: IntegrationError,
  topologyAssessment: TopologyAssessment
)

InverseEstimate records the result, residual, convergence status, exact framed
domain, criteria, and implementation revision of a numerical inversion.
CertifiedBidirectionalPair records that two sampled maps met stated numerical
criteria in both framed directions. Construction checks all live map endpoints
against the directional domains. Neither is a SmoothIso: neither provides an
analytic or lawfully exact inverse.

7.4 “Diffeomorphism” must not be a hopeful class name

A raw displacement field is not automatically invertible. A smooth-looking field is not automatically topology-preserving. Even a positive central-difference Jacobian determinant at every voxel is not sufficient to establish that a digital transformation is fold-free; recent work shows that multiple cell-level finite-difference configurations must be considered.

Therefore:

DenseMap

should be easy to construct, while:

TopologyCertificate

should require validation and state exactly where and how it was evaluated.

final case class TopologyScopeRecord(
  gridId: GridId,
  resolution: Vector[Int],
  region: IndexRegionRecord,
  samplingRule: CellSamplingRuleRecord,
  determinantThreshold: Double,
  criteria: TopologyCriteriaRecord,
  implementationRevision: String
)
final case class TopologyCertificateRecord(
  scope: TopologyScopeRecord,
  diagnostics: TopologyDiagnosticsRecord
)

The constructor should be private:

def fromReportedDiagnostics(
  scope: TopologyScope[F, D],
  diagnostics: TopologyDiagnostics
): Either[EvidenceError, TopologyCertificate[F, D]]

The criteria are values, not type parameters. The certificate records that
they were satisfied only for its exact GridId, resolution, sampled region,
cell-sampling rule, determinant threshold, criteria, and implementation
revision. A certificate cannot be silently reused after changing any of that
scope. It is not a global proof of diffeomorphism and does not upgrade the
sampled map to SmoothIso. Records restore only through a checked live grid, and
consumption rejects a different scope. The public construction name also makes
clear that threshold checks validate caller-reported diagnostics but do not
authenticate how those diagnostics were measured. An analytically constructed
SmoothIso may carry a TopologyCertificate as numerical evidence without
deriving its exact inverse capability from that certificate.

⸻

8. Global and local transformation models

Affine-plus-local deformation should not be represented merely as an unstructured list.

A global group acts on local maps by conjugation:

g\cdot\phi
=
g\circ\phi\circ g^{-1}.

Likewise, it acts on velocity fields by pushforward. Under suitable domain assumptions, global and local components can be understood through a semidirect-product structure.

The practical abstraction is:

final case class GlobalLocal[
  Global,
  Local,
  From <: Frame,
  Mid  <: Frame,
  To   <: Frame,
  D    <: Dim
](
  global: Global,
  local: Local,
  side: LocalActionSide
)

This supports:

* joint affine and nonlinear optimization;
* rebasing a velocity field after an affine update;
* motion plus static scanner distortion;
* local deformation in subject or atlas coordinates;
* coordinate-covariant regularization;
* principled conversion between “affine then warp” and “warp then affine.”

At minimum, the core must expose conjugate, pushForwardVelocity, and pullBackMomentum. Whether a particular model is literally implemented as a semidirect product can remain within the specialized module.

⸻

9. Registration is an estimation problem over an action

Let:

* M be a moving observation on frame A;
* F be a fixed observation on frame B;
* p be the state of a transformation model;
* \phi_p:A\to B be the corresponding map;
* \mu be an evaluation measure on B.

Then the generic registration problem is:

\min_p
\int_B
\ell
\left(
F(y),
\operatorname{transport}_{\phi_p}(M)(y)
\right)
\,d\mu(y)
+
R(p).

This decomposes registration into seven independent concepts:

1. Observation — image, label field, landmarks, surface, point set, feature field.
2. Action — how the transform moves that observation.
3. Model — rigid, affine, FFD, SVF, time-varying flow, learned basis.
4. Evaluation measure — where and with what weights comparison occurs.
5. Data term — SSD, robust SSD, NCC, LNCC, MI, feature distance.
6. Geometry or regularizer — what constitutes a small or plausible change.
7. Solver — first-order, Gauss–Newton, trust region, L-BFGS, ADMM, shooting.

Do not call all seven of these things a “metric.”

9.1 The evaluation domain is independent of either image grid

ITKv4 introduced a virtual sampling domain independent of the fixed and moving image grids. The deeper abstraction is a quadrature or evaluation measure, not another synthetic image.

trait Quadrature[F <: Frame, D <: Dim]:
  def size: Long
  def foreachSample(
    f: (Point[F, D], Weight) => Unit
  ): Unit

Implementations include:

GridQuadrature(grid)
MaskedQuadrature(grid, mask)
WeightedQuadrature(grid, weights)
PointQuadrature(points)
SurfaceQuadrature(mesh)
StochasticQuadrature(domain, sampler)

A mask is therefore not a special mutable option on a metric. It is support or weighting of the evaluation measure.

This immediately broadens the substrate beyond image-to-image registration without enlarging the mathematical core.

9.2 Registration problems should expose numerical structure

A monolithic interface returning only value and gradient throws away useful structure. Prefer a hierarchy:

trait ValueProblem[P]:
  def value(p: P): Double
trait GradientProblem[P, Delta] extends ValueProblem[P]:
  def valueAndGradient(p: P): ValueGradient[Delta]
trait ResidualProblem[P, Delta, Residual]:
  def residual(p: P): Residual
  def jvp(p: P, delta: Delta): Residual
  def vjp(p: P, residual: Residual): Delta
trait ProxProblem[P]:
  def prox(p: P, step: Double): P

From ResidualProblem, a solver can construct a matrix-free Gauss–Newton normal operator:

J^\top WJ.

No dense design matrix or transform Jacobian need ever be materialized.

This allows:

* gradient descent;
* nonlinear conjugate gradient;
* L-BFGS;
* Gauss–Newton with PCG;
* trust-region methods;
* primal–dual methods;
* ADMM for split regularizers.

The registration model supplies a retraction rather than assuming parameters form a vector space.

⸻

10. Multiresolution is a reusable algebra, not loop boilerplate

Multiresolution registration commonly improves robustness and computational cost by estimating at coarse resolution and transferring the result to progressively finer scales.

The core should express this as a tower of spaces and transfer maps:

trait ScaleSpace[A]:
  def levels: NonEmptyVector[ScaleLevel]
  def at(level: ScaleLevel): A
trait Transfer[P]:
  def restrict(
    value: P,
    from: ScaleLevel,
    to: ScaleLevel
  ): P
  def prolong(
    value: P,
    from: ScaleLevel,
    to: ScaleLevel
  ): P

Different objects transfer differently:

* affine maps are unchanged across levels;
* images are smoothed in physical units and resampled;
* velocity fields are restricted and prolonged as vector fields;
* displacements must retain physical magnitude;
* B-spline coefficient lattices are refined;
* masks may be conservatively downsampled;
* momentum may require a dual-space transfer.

A RegistrationSchedule then becomes ordinary data:

val schedule =
  PyramidSchedule(
    shrink = 8, 4, 2, 1,
    smooth = 3.mm, 2.mm, 1.mm, 0.mm,
    iterations = 80, 60, 40, 20
  )

The schedule does not own the algorithm. It drives a generic continuation procedure.

⸻

11. Motion correction is a curve in a finite-dimensional group

A motion-correction result is not merely Vector[Mat4]. It is a sampled curve of poses:

final case class PoseSeries[
  Local <: Frame,
  Reference <: Frame,
  D <: Dim,
  P
](
  times: TimeAxis,
  poses: IndexedSeq[P]
)

For rigid fMRI motion:

T_t : \text{volume-local} \rightarrow \text{reference}.

The model may be:

* independent rigid poses;
* a temporally smooth pose curve;
* a spline in the Lie algebra;
* a state-space model;
* a robust model with outlier volumes;
* a joint motion-and-distortion model.

Temporal regularization acts on group increments or Lie-algebra velocities, not on Euler-angle columns:

TemporalPrior.firstDifference
TemporalPrior.secondDifference
TemporalPrior.acceleration
TemporalPrior.stateSpace(...)

The same rigid algebra, image action, resampling plan, data terms, pyramids, and solvers are shared with ordinary rigid registration.

Most importantly, motion, susceptibility correction, anatomical coregistration, and atlas normalization can be composed and applied in one final resampling operation.

⸻

12. Normalization is a transform graph

Normalization should not be its own primitive transformation type. It is the act of obtaining a path into a chosen reference frame.

val graph =
  TransformGraph.empty
    .add(volumeToReference)
    .add(referenceToT1)
    .add(t1ToAtlas)

Then:

val volumeToAtlas =
  graph.path(volumeFrame, atlasFrame)

For invertible edges, the graph automatically has inverse traversal. A path query returns a typed composition when called from typed code and a runtime-validated composition in dynamic code.

The graph should also detect:

* missing paths;
* ambiguous paths;
* inconsistent cycles;
* unit or convention mismatches;
* multiple estimates between the same frames;
* paths requiring noninvertible reversal.

A cycle

A\rightarrow B\rightarrow C\rightarrow A

need not be silently accepted. Its deviation from identity is useful diagnostic information.

Transform values should remain pure. Estimation metadata belongs in a wrapper:

final case class Estimated[T](
  value: T,
  objective: ObjectiveTrace,
  convergence: ConvergenceReport,
  diagnostics: RegistrationDiagnostics,
  provenance: Provenance,
  uncertainty: Option[TangentUncertainty]
)

The same affine matrix estimated twice under different data, masks, or metrics is the same transform value but a different estimate.

⸻

13. Proposed module structure

The foundation is one repository with enforceable artifact boundaries.
Repository splits add release coupling without improving conceptual
separation while these APIs are stabilizing. A future physical split must
preserve the artifact DAG and public ownership below.

Artifact	Responsibility
image4s-geometry	Dim, frames and FrameId, points, vectors, indices, GridId, grids, and the sole Affine[D] coordinate operator
image4s-core	The one sampled-image representation, axes, roles, validity, and immutable ownership
image4s-reference	Correct, deliberately unoptimized sampling oracles used by tests
reframe4s-core	Spatial maps, smooth maps, exact isomorphisms, composition, jets, and transform graphs
reframe4s-lie	Rotation, rigid, similarity, twists, retractions, and adjoints over the geometry affine authority
reframe4s-resample	Compiled pull-resampling plans and production sampling kernels
reframe4s-laws	Discipline and property-based laws across geometry, maps, and sampling
locus4s-core	Identities, finite spaces, ordinals, regions, selections, relations, and total maps
locus4s-data	Indexed fields and genuinely domain-neutral aggregation
locus4s-laws	Laws for the locus core and data artifacts
image4s-locus	The grid-to-finite-domain bridge only

Parcellation and searchlight workflows remain in ScalaFIM. Specialized flow,
registration, multiscale, and algorithm artifacts may be added above this
foundation after their APIs stabilize; they must not absorb geometry, image,
or locus ownership.

Relationship to Ravel and Gale

Given the surrounding library ecosystem:

* Ravel should supply rank-typed, runtime-shaped arrays and flat buffers.
* Gale should supply small dense matrices, decompositions, linear operators, and iterative solvers.
* The monorepo should add geometry and transformation semantics, not duplicate either foundation.

Every arrow below means consumer -> dependency. Every node is a concrete
artifact, not a repository aggregate:

image4s-geometry     -> gale-core
image4s-core           -> image4s-geometry + ravel-core
image4s-reference      -> image4s-core + ravel-core
image4s-laws           -> image4s-core + image4s-reference
reframe4s-core         -> image4s-geometry + gale-core
reframe4s-lie          -> reframe4s-core + image4s-geometry + gale-core
reframe4s-resample     -> reframe4s-core + image4s-core + ravel-core
reframe4s-laws         -> reframe4s-core + reframe4s-lie + image4s-reference
locus4s-data           -> locus4s-core
locus4s-laws           -> locus4s-core + locus4s-data
image4s-locus          -> image4s-core + image4s-geometry + locus4s-core
scalafim-image         -> image4s-core + reframe4s-resample + image4s-locus
scalafim-registration  -> reframe4s-register + reframe4s-resample
scalafim-motion        -> reframe4s-motion + scalafim-image

The graph must be checked mechanically for undeclared nodes, duplicate edges,
and cycles. The pure semantic artifacts cross-compile to JVM and Scala.js.
JVM-specific vectorized, parallel, or native kernels can inhabit explicit
backend artifacts without changing the public mathematics.

The accepted Ravel revision is sufficient for immutable representation and
reference fixtures, but it does not yet provide allocation-free rank-specific
indexing and consuming output construction. Therefore production
reframe4s-resample implementation is blocked until an immutable remotely green
Ravel revision supplies both capabilities. image4s-reference is an oracle, not
a production substitute, and this plan does not authorize a private array
kernel that recreates Ravel.

⸻

14. Computations the foundation should own

The foundation should implement computations whose semantics are universal and whose optimization benefits nearly every method.

Geometry and transform kernels

* point and batch evaluation;
* rigid, similarity, and affine composition;
* stable inverses;
* small-matrix exponentials and retractions;
* adjoint actions;
* polar decomposition;
* first-jet composition;
* determinant and condition diagnostics.

Grid and sampling kernels

* index-to-physical and physical-to-index conversion;
* nearest, linear, and cubic interpolation;
* fused value-and-gradient interpolation;
* explicit outside-domain handling;
* scalar, label, vector, and tensor transport;
* affine scanline stepping;
* one-pass transform-chain resampling.

Dense-field kernels

* deformation composition;
* forward and inverse dense-map evaluation;
* fixed-point and multilevel inversion;
* velocity exponentiation;
* paired endpoint/inverse-estimate scaling and squaring;
* displacement conversion around an explicit base map;
* spatial Jacobians in physical coordinates;
* divergence, gradient, curl, Laplacian, and symmetric gradient;
* Jacobian determinant and cell-fold diagnostics.

Multiscale and solver support

* Gaussian pyramids in physical units;
* field restriction and prolongation;
* reusable workspaces;
* matrix-free normal operators;
* common preconditioner interfaces;
* reduction kernels for data terms and diagnostics.

Common SSD, robust SSD, NCC, and LNCC implementations belong in a standard data-term module. Mutual information may also belong there, but not in reframe4s-core.

⸻

15. The laws

The laws are not documentation after the fact. They define what an implementation is.

15.1 Map and groupoid laws

For valid compositions:

f\circ\mathrm{id} = f

\mathrm{id}\circ f = f

h\circ(g\circ f) = (h\circ g)\circ f.

For isomorphisms:

f^{-1}\circ f = \mathrm{id}

f\circ f^{-1} = \mathrm{id}

(g\circ f)^{-1}=f^{-1}\circ g^{-1}.

15.2 Pullback laws

\mathrm{id}^{*}f=f

(g\circ f)^{*}=f^{*}\circ g^{*}.

For forward transport:

\operatorname{transport}_{g\circ f}
=
\operatorname{transport}_{g}
\circ
\operatorname{transport}_{f}.

These are exact at the semantic field level.

15.3 Jet laws

J(g\circ f)_x
=
Jg_{f(x)}Jf_x.

Analytic jets should agree with finite differences to the declared convergence order.

For an affine map, the Jacobian is spatially constant.

15.4 Geometric laws

Rigid maps preserve distances:

\|T(x)-T(y)\|=\|x-y\|.

Rigid Jacobians are orthogonal with determinant +1.

Affine composition agrees with homogeneous-matrix composition.

Orientation-preserving affine maps have positive determinant.

15.5 Grid and interpolation laws

* index-to-frame and frame-to-continuous-index round-trip;
* image data shape agrees with grid shape;
* nearest-neighbour interpolation at lattice points reproduces samples exactly;
* linear interpolation reproduces constant and affine fields;
* label interpolation introduces no new labels;
* pyramid operations preserve physical extent;
* field prolongation preserves physical vector magnitudes.

15.6 Flow laws

For stationary velocity v:

\exp(0)=\mathrm{id}

\exp(-v)\approx \exp(v)^{-1}

\exp((s+t)v)
\approx
\exp(sv)\circ\exp(tv).

Errors should converge with increasing integration depth.

A flow integrator’s claimed order should be tested as a convergence law rather than pretending floating-point integration satisfies exact equations.

15.7 Coordinate-covariance laws

Re-expressing a registration problem in different coordinates should re-express the answer by the corresponding conjugation.

This is one of the most valuable algorithm-level law families. It detects:

* RAS/LPS confusion;
* voxel-versus-physical-space errors;
* incorrect translation scaling;
* wrong left/right update conventions;
* center-of-rotation bugs;
* nonphysical regularization tied to voxel dimensions.

15.8 Resampling laws must acknowledge approximation

Sequential resampling is not required to equal one-shot resampling. Instead test that:

* a composed-map, one-shot result agrees with direct continuous evaluation;
* error decreases under grid refinement;
* identity resampling reproduces input within the interpolator’s expected error;
* interpolation and transport preserve the field role’s invariants.

The reframe4s-laws artifact can use Discipline and ScalaCheck, following the Typelevel pattern of specifying laws separately from implementations.

⸻

16. The approachable surface

The public API should read like spatial reasoning, not like theorem proving.

16.1 Affine registration

val native = Frame.named("sub-01:T1w")
val mni    = Frame.named("MNI152NLin2009cAsym")
val moving = Image3.in(native)(nativeData, nativeGeometry)
val fixed  = Image3.in(mni)(atlasData, atlasGeometry)
val fit =
  register(moving, fixed)
    .rigid(
      loss = MutualInformation.default,
      levels = Pyramid.default
    )
    .thenAffine()
    .run()
val nativeToMni = fit.movingToFixed
val mniToNative = fit.fixedToMoving
val normalized =
  fit.warpMoving(moving)
    .onto(fixed.grid)
    .using(Cubic)
    .run()

Users see ordinary nouns and verbs. The frame types and composition evidence are inferred.

16.2 Diffeomorphic registration

val nonlinear =
  register(moving, fixed)
    .initializedBy(fit.movingToFixed)
    .stationaryVelocity(
      controlSpacing = 4.mm,
      loss = LocalNcc(radius = 4.mm),
      geometry =
        FieldGeometry.helmholtzSobolev(
          divergence = 1.0,
          curl = 1.0,
          shear = 0.5,
          boundary = Neumann
        ),
      integrator = ScalingAndSquaring.default
    )
    .run()
val certified =
  nonlinear.movingToFixed.certify(
    scope = TopologyScope(
      grid = fixed.grid,
      resolution = fixed.grid.shape,
      region = fixed.foregroundRegion,
      criteria = DiffeomorphismCriteria.clinical
    )
  )

The high-level SVF module is concise because the foundation already owns fields, flow integration, Jacobians, topology checks, quadrature, interpolation, pyramids, and linearization.

16.3 Motion correction

val corrected =
  motionCorrect(bold)
    .reference(Reference.robustMean)
    .model(Rigid3)
    .loss(RobustLeastSquares.huber)
    .temporalPrior(TemporalPrior.secondDifference)
    .levels(Pyramid(4, 2, 1))
    .run()

The result exposes both the pose series and a lazy corrected view. Actual interpolation can be deferred until distortion correction and normalization have also been composed.

16.4 Extending the map family

A developer adding a map should implement ordinary methods:

final case class RadialWarp[
  A <: Frame,
  B <: Frame
](
  source: A,
  target: B,
  center: Point[A, D3],
  coefficients: Vector[Double]
) extends SmoothMap[A, B, D3]:
  def apply(
    p: Point[A, D3]
  ): Point[B, D3] =
    // scalar implementation
  def jet1At(
    p: Point[A, D3]
  ): Jet1[A, B, D3] =
    // value and spatial Jacobian

That is enough for correctness.

For speed, the developer may additionally provide:

given BulkMapKernel[RadialWarp[?, ?]]
given ParameterLinearization[RadialWarp[?, ?], RadialDelta]

These are optional capabilities. The basic extension path is not a typeclass puzzle.

⸻

17. Performance architecture

Mathematical elegance here should improve performance rather than compete with it.

17.1 Scalar semantics, bulk execution

map(point) and field.at(point) define meaning. Inner loops should use bulk kernels:

trait BulkMapKernel[T]:
  def applyInto(
    transform: T,
    input: PointBuffer,
    output: MutablePointBuffer
  ): Unit

The fallback can call the scalar implementation. Built-in transforms provide specialized kernels.

No Point objects should be allocated per voxel.

17.2 Compile a warp plan

A resampling request should compile into a plan:

val plan =
  Resample(moving)
    .onto(fixed.grid)
    .through(transform)
    .using(Linear)
    .compile()

Compilation can:

* flatten transform chains;
* remove identities;
* fuse adjacent affine maps;
* precompose grid-to-world and world-to-grid affines;
* choose an affine scanline kernel;
* select dense-field or generic-map evaluation;
* fuse interpolation with image-gradient evaluation;
* determine valid interior and boundary tiles;
* allocate reusable workspace;
* choose parallel tiling.

Execution then becomes:

plan.run()
plan.runInto(output)

The ordinary API returns owned immutable results. Advanced users get explicit runInto and workspace reuse.

17.3 Keep backends explicit

The semantic core should not have a global mutable backend registry.

plan.run(using Execution.jvmParallel)
plan.run(using Execution.pureScala)

The shared implementation should work on JVM and Scala.js. Platform modules may provide optimized buffers, vectorized kernels, or native interop without changing the public mathematics.

17.4 Coordinates should normally be Double

Do not make every geometric operation polymorphic over an arbitrary numeric type.

A sensible policy is:

* geometry, transforms, Jacobians, and optimization states use Double;
* sampled field values may use Float, Double, integers, or domain-specific values;
* accumulation precision is chosen explicitly by the kernel;
* mixed-precision implementations are backend concerns.

This is faster, easier to reason about, and more numerically honest than universal Field[A] abstraction.

⸻

18. What should not be in the core

The foundation should resist several temptations.

It should not:

* make Image a subtype of a generic collection;
* use a giant inheritance hierarchy patterned after ITK;
* treat every three-channel image as a vector field;
* call an arbitrary displacement field a diffeomorphism;
* combine transform values with optimizer state and diagnostics;
* make interpolation a property of a transform;
* hide transform direction in documentation;
* encode exact image extents in types;
* require Cats Effect to represent a spatial map;
* depend on a specific autodiff library;
* make SVF synonymous with nonlinear registration;
* make a naked Mat4 a valid inter-frame transform;
* resample after each stage of a transform chain;
* assume zero outside every bounded domain;
* use one Jacobian-determinant stencil as a topology certificate.

Nor should the first version attempt arbitrary manifold registration. The core abstractions should leave that direction open, but the initial implementation should be excellent for Euclidean 2D and 3D spaces.

⸻

19. A disciplined first release

A strong first release would contain:

Semantic kernel

* frame tokens;
* typed points, vectors, indices, and grids;
* spatial maps, smooth maps, and isomorphisms;
* composition, inverse, and transform chains;
* first jets and Jacobians;
* transform graph;
* laws.

Rigid and affine kernel

* 2D and 3D translation, rigid, similarity, and affine maps;
* stable composition and inversion;
* Lie updates and adjoints;
* affine and rigid models;
* reference rigid and affine registration.

Sampling kernel

* scalar and label fields;
* nearest, linear, and cubic interpolation;
* explicit boundaries and validity;
* one-shot resampling;
* fused value-and-gradient sampling;
* Gaussian pyramids and physical-space smoothing.

Nonlinear substrate

* dense absolute maps;
* displacement, velocity, and momentum distinctions;
* dense composition;
* scaling and squaring;
* approximate inverse estimation and certified bidirectional pairs;
* Jacobian and topology diagnostics;
* field geometries and differential operators.

Registration algebra

* quadrature and masks;
* SSD, robust SSD, NCC, and LNCC;
* transform models and retractions;
* residual/JVP/VJP interface;
* matrix-free Gauss–Newton protocol;
* multiresolution continuation;
* diagnostics and provenance.

Then:

* motion correction becomes the first major client;
* Hodge Flow becomes the first nonlinear client;
* SyN, LDDMM, FFD, and atlas construction test the extensibility claims.

⸻

The central design rule

The library’s deepest type is not Image, Metric, or Optimizer.

It is:

SpatialMap[From, To, Dimension]

Everything else follows from four questions:

1. What frames does this map connect?
2. What structural guarantees does it have?
3. How does it act on this kind of spatial object?
4. How is that continuous action realized numerically?

With those questions kept separate:

* types prevent directional and coordinate mistakes;
* category and groupoid laws govern composition;
* Lie geometry governs updates;
* pullback governs scalar warping;
* fiber representations govern vectors and tensors;
* quadrature governs comparison;
* operator linearization governs optimization;
* resampling becomes a final compiled operation;
* motion, affine registration, diffeomorphic normalization, and future methods become variations over one small and coherent foundation.

That is the substrate an expert could recognize as mathematically inevitable, while an ordinary Scala developer could use without ever needing to know the word “groupoid.”

⸻

20. Runtime image ingress and basic NIfTI I/O

The in-memory image algebra should not depend on files, streams, compression,
or format metadata. It still needs a sound value at the boundary where a
decoder discovers types at runtime.

`SomeSampled[A, Role]` packages one existing `Sampled` value behind abstract
spatial-dimension, frame-owner, and Ravel-rank members. Its D2 and D3 fold cases
recover a dimension witness and the same underlying object. The package does
not copy storage, widen a frame to a string identifier, or create a second image
container.

Basic NIfTI-1 I/O belongs in the separate JVM-only `image4s-nifti` artifact.
That artifact depends on `image4s-core`, `image4s-geometry`, and Ravel. It owns
header parsing, numeric decoding, qform and sform handling, uncompressed and
gzip-compressed input and output, single-file and header/image-pair storage,
extension preservation, and conversion between NIfTI first-axis-fastest
offsets and image4s logical indices.

The reader returns NIfTI metadata beside the image, not inside `Sampled`.
Scalar and label entry points are distinct because a datatype code does not
determine interpolation semantics. A default read creates a fresh RAS frame; a
caller may instead supply a compatible frame and retain its exact live owner.

Physical storage is explicit. `NiftiStorage` distinguishes one-file `n+1` from
pair-file `ni1`; `NiftiFiles` returns every path produced by a write. Either
pair member may be used to enter a read, but its companion must exist.
Extensions retain a non-negative code and the exact padded payload bytes after
the `esize` and `ecode` fields. New content is padded to a valid 16-byte block;
malformed size, alignment, and region bounds are typed errors.

Numeric output is also explicit. `NiftiWriteOptions` selects UInt8, Int16,
Int32, Float32, or Float64 plus the slope and intercept recorded in the header.
Integer conversion rejects fractional, non-finite, and out-of-range raw values
by default. `RoundToNearestEven` is an explicit request to quantize, not an
ambient writer behavior. The options constructor canonicalizes scaling to the
Float32 values that NIfTI-1 can actually record, so payload encoding and header
decoding use the same numbers.

ScalaFIM continues to own BIDS discovery, staging and random-access policy,
acquisition semantics, application workflows, and compatibility wrappers for
`NeuroVol` and `NeuroVec`.
