import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbtcrossproject.{CrossClasspathDependency, CrossProject, CrossType}
import sbtcrossproject.CrossPlugin.autoImport.*
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.*

ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / homepage := Some(url("https://github.com/canardlapin/reframe4s"))
ThisBuild / licenses := List(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")
)
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Werror"
)
ThisBuild / Test / parallelExecution := false
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

// sbt evaluates source dependencies in the parent process. Resolve every
// repository path from an explicit build root so this build works both
// standalone and as a composite dependency.
lazy val reframe4sBuildRoot =
  ReframeBuildRoot.resolve("reframe4s.build.root")

lazy val ravelRevision = "195d71027423d5bda1c53ef1baa17e07670fe290"
// Immutable by default; the explicit property admits an audited local Ravel
// checkout for cross-repository development and performance verification.
lazy val ravelBuild =
  sys.props
    .get("reframe4s.ravel.build")
    .map(path => file(path).getCanonicalFile.toURI)
    .getOrElse(uri(s"https://github.com/canardlapin/ravel.git#$ravelRevision"))
lazy val ravelCoreJVM = ProjectRef(ravelBuild, "coreJVM")
lazy val ravelCoreJS = ProjectRef(ravelBuild, "coreJS")

lazy val galeRevision = "779ed3d8169cdd9f4eb9ab10b1ca9defadc27715"
lazy val galeBuild =
  sys.props.get("reframe4s.gale.build")
    .map(path => file(path).getCanonicalFile.toURI)
    .getOrElse(uri(s"https://github.com/canardlapin/gale.git#$galeRevision"))
lazy val galeCoreJVM = ProjectRef(galeBuild, "coreJVM")
lazy val galeCoreJS = ProjectRef(galeBuild, "coreJS")

// Spatial4s is the shared coordinate authority. It must be the same revision
// image4s pins; image4sBuild forwards a local override, and an image4s-only
// override is honoured here, so the graph resolves one Spatial4s build.
lazy val spatial4sRevision = "f3a4e40c38cdfec730d3e13093397ce38072f7fa"
lazy val spatial4sBuild =
  sys.props
    .get("reframe4s.spatial4s.build")
    .orElse(sys.props.get("image4s.spatial4s.build"))
    .map(path => file(path).getCanonicalFile.toURI)
    .getOrElse(
      uri(s"https://github.com/canardlapin/spatial4s.git#$spatial4sRevision")
    )
lazy val spatial4sCoreJVM = ProjectRef(spatial4sBuild, "spatial4s-coreJVM")
lazy val spatial4sCoreJS = ProjectRef(spatial4sBuild, "spatial4s-coreJS")

// image4s is an independently owned foundation. Ordinary builds clone the
// exact reviewed revision; the property admits an audited local checkout for
// coordinated cross-repository development.
lazy val image4sRevision = "3da276319e3813660ad1dd4a678a76e543ff305e"
lazy val image4sBuild = {
  sys.props.get("reframe4s.spatial4s.build").foreach { path =>
    sys.props.get("image4s.spatial4s.build") match {
      case Some(other) if other != path =>
        sys.error(
          s"reframe4s.spatial4s.build=$path conflicts with image4s.spatial4s.build=$other"
        )
      case _ => System.setProperty("image4s.spatial4s.build", path)
    }
  }
  sys.props
    .get("reframe4s.image4s.build")
    .map(path => file(path).getCanonicalFile.toURI)
    .getOrElse(
      uri(s"https://github.com/canardlapin/image4s.git#$image4sRevision")
    )
}
lazy val image4sGeometryJVM =
  ProjectRef(image4sBuild, "image4s-geometryJVM")
lazy val image4sGeometryJS =
  ProjectRef(image4sBuild, "image4s-geometryJS")
lazy val image4sCoreJVM = ProjectRef(image4sBuild, "image4s-coreJVM")
lazy val image4sCoreJS = ProjectRef(image4sBuild, "image4s-coreJS")
lazy val image4sReferenceJVM =
  ProjectRef(image4sBuild, "image4s-referenceJVM")
lazy val image4sReferenceJS =
  ProjectRef(image4sBuild, "image4s-referenceJS")
lazy val image4sNiftiJVM =
  ProjectRef(image4sBuild, "image4s-niftiJVM")

lazy val sharedSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit" % "1.3.0" % Test,
    "org.scalameta" %%% "munit-scalacheck" % "1.3.0" % Test
  )
)

lazy val artifactGraphRows =
  IO.readLines(reframe4sBuildRoot / "project" / "artifact-graph.tsv")
    .map(_.trim)
    .filter(line => line.nonEmpty && !line.startsWith("#"))
    .map(_.split("\\t", -1).toVector)

lazy val artifactKinds: Map[String, String] =
  artifactGraphRows.collect {
    case Vector("node", kind, artifact) => artifact -> kind
  }.toMap

lazy val artifactEdges: Vector[(String, String)] =
  artifactGraphRows.collect {
    case Vector("edge", consumer, dependency) => consumer -> dependency
  }.toVector

lazy val internalArtifactIds =
  artifactKinds.collect {
    case (artifact, kind) if kind.startsWith("internal") => artifact
  }.toVector.sorted

lazy val bareFoundationProjects: Map[String, CrossProject] =
  internalArtifactIds.map { artifact =>
    val module =
      CrossProject(
        artifact,
        reframe4sBuildRoot / "modules" / artifact
      )(JSPlatform, JVMPlatform)
        .crossType(CrossType.Full)
        .settings(sharedSettings)
        .settings(name := artifact)
        .jsSettings(
          scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
        )
    artifact -> module
  }.toMap

def configureExternalDependencies(
    artifact: String,
    module: CrossProject
): CrossProject = {
  val dependencies =
    artifactEdges.collect {
      case (`artifact`, dependency)
          if artifactKinds.get(dependency).contains("external") =>
        dependency
    }.toSet

  dependencies.toVector.sorted.foldLeft(module) {
    case (configured, "gale-core") =>
      configured
        .jvmConfigure(_.dependsOn(galeCoreJVM))
        .jsConfigure(_.dependsOn(galeCoreJS))
    case (configured, "ravel-core") =>
      configured
        .jvmConfigure(_.dependsOn(ravelCoreJVM))
        .jsConfigure(_.dependsOn(ravelCoreJS))
    case (configured, "spatial4s-core") =>
      configured
        .jvmConfigure(_.dependsOn(spatial4sCoreJVM))
        .jsConfigure(_.dependsOn(spatial4sCoreJS))
    case (configured, "image4s-geometry") =>
      configured
        .jvmConfigure(_.dependsOn(image4sGeometryJVM))
        .jsConfigure(_.dependsOn(image4sGeometryJS))
    case (configured, "image4s-core") =>
      configured
        .jvmConfigure(_.dependsOn(image4sCoreJVM))
        .jsConfigure(_.dependsOn(image4sCoreJS))
    case (configured, "image4s-reference") =>
      configured
        .jvmConfigure(_.dependsOn(image4sReferenceJVM))
        .jsConfigure(_.dependsOn(image4sReferenceJS))
    case (_, unsupported) =>
      sys.error(s"$artifact has unsupported external dependency: $unsupported")
  }
}

lazy val foundationProjects: Map[String, CrossProject] =
  bareFoundationProjects.map { case (artifact, bareModule) =>
    val internalDependencies =
      artifactEdges.collect {
        case (`artifact`, dependency)
            if artifactKinds.get(dependency).exists(_.startsWith("internal")) =>
          bareFoundationProjects(dependency)
      }
    val internallyConfigured =
      if (internalDependencies.isEmpty) bareModule
      else
        bareModule.dependsOn(
          internalDependencies
            .map(module => new CrossClasspathDependency(module, None)): _*
        )
    artifact -> configureExternalDependencies(artifact, internallyConfigured)
  }

lazy val reframe4sCore = foundationProjects("reframe4s-core")
lazy val reframe4sLie = foundationProjects("reframe4s-lie")
lazy val reframe4sResample = foundationProjects("reframe4s-resample")
lazy val reframe4sLaws = foundationProjects("reframe4s-laws")
lazy val reframe4sField = foundationProjects("reframe4s-field")
lazy val reframe4sFlow = foundationProjects("reframe4s-flow")
lazy val reframe4sGraph = foundationProjects("reframe4s-graph")
lazy val reframe4sRegister = foundationProjects("reframe4s-register")
lazy val reframe4sMultiscale = foundationProjects("reframe4s-multiscale")
lazy val reframe4sMotion = foundationProjects("reframe4s-motion")
lazy val reframe4sHalfflow =
  foundationProjects("reframe4s-halfflow")
    .jvmConfigure(_.dependsOn(image4sNiftiJVM % "test->compile"))
lazy val reframe4sSpectral = foundationProjects("reframe4s-spectral")
lazy val reframe4sFlashalign = foundationProjects("reframe4s-flashalign")
lazy val reframe4sBundle = foundationProjects("reframe4s")

// Performance evidence is deliberately outside the published aggregate. It
// exercises the same JVM artifact users consume without making JMH part of the
// library surface or ordinary compile/test cycle.
lazy val halfflowBenchJVM =
  project
    .in(reframe4sBuildRoot / "benchmarks" / "halfflow-jvm")
    .dependsOn(reframe4sHalfflow.jvm)
    .enablePlugins(JmhPlugin)
    .settings(
      name := "reframe4s-halfflow-benchmarks",
      publish / skip := true
    )

// Pair-registration evidence is JVM-only and unpublished. Keeping it outside
// the aggregate prevents filesystem, NIfTI, and benchmark concerns from
// entering the cross-published Flashalign artifact.
lazy val flashalignBenchmarkJVM =
  project
    .in(reframe4sBuildRoot / "benchmarks" / "flashalign" / "runner")
    .dependsOn(reframe4sFlashalign.jvm, reframe4sHalfflow.jvm, image4sNiftiJVM)
    .enablePlugins(JmhPlugin)
    .settings(
      name := "reframe4s-flashalign-benchmark-runner",
      publish / skip := true,
      libraryDependencies += "org.scalameta" %% "munit" % "1.3.0" % Test
    )

lazy val root =
  project
    .in(reframe4sBuildRoot)
    .aggregate(
      foundationProjects.values.toVector
        .flatMap(module =>
          Vector(LocalProject(module.jvm.id), LocalProject(module.js.id))
        ): _*
    )
    .settings(
      name := "reframe4s-root",
      publish / skip := true
    )

addCommandAlias("compileAll", ";root/compile")
addCommandAlias("testAll", ";root/test")

// Source dependencies also define generic aliases such as testAll. Repository-
// specific names avoid that collision. Separate commands finish each module's
// tests before starting the next, including asynchronous Scala.js runners.
addCommandAlias(
  "reframe4sTestJVM",
  internalArtifactIds.map(id => s"${id}JVM/test").mkString(";", ";", "")
)
addCommandAlias(
  "reframe4sTestJS",
  internalArtifactIds.map(id => s"${id}JS/test").mkString(";", ";", "")
)
addCommandAlias("reframe4sTestAll", ";reframe4sTestJVM;reframe4sTestJS")
addCommandAlias(
  "reframe4sTestRunners",
  ";flashalignBenchmarkJVM/test;halfflowBenchJVM/compile"
)
