ThisBuild / scalaVersion := "3.7.4"
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Werror"
)

lazy val foundationRoot = {
  val root = file("../../..").getCanonicalFile
  sys.props.update("reframe4s.build.root", root.getPath)
  root
}
lazy val foundation = foundationRoot.toURI
lazy val image4sRevision = "c1c9866eb61390de40d3ba110e040ee2a09f5f32"
lazy val image4sFoundation =
  sys.props
    .get("reframe4s.image4s.build")
    .map(path => file(path).getCanonicalFile.toURI)
    .getOrElse(
      uri(s"https://github.com/canardlapin/image4s.git#$image4sRevision")
    )
lazy val image4sNifti =
  ProjectRef(image4sFoundation, "image4s-niftiJVM")
lazy val image4sReference =
  ProjectRef(image4sFoundation, "image4s-referenceJVM")
lazy val reframe4sMotion =
  ProjectRef(foundation, "reframe4s-motionJVM")

lazy val runner =
  project
    .in(file("."))
    .dependsOn(image4sNifti, image4sReference, reframe4sMotion)
    .settings(
      name := "reframe4s-motion-benchmark-runner",
      publish / skip := true,
      Compile / run / fork := true,
      Test / parallelExecution := false,
      libraryDependencies +=
        "org.scalameta" %% "munit" % "1.3.0" % Test
    )
