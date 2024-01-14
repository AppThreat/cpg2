name := "cpg2"
ThisBuild / organization := "io.appthreat"
ThisBuild / version      := "0.0.1"
ThisBuild / scalaVersion := "3.3.1"

// parsed by project/Versions.scala
val overflowdbVersion = "0.0.3"
val overflowdbCodegenVersion = "2.103"

ThisBuild / Test / fork           := true
ThisBuild / Test / javaOptions += s"-Dlog4j2.configurationFile=file:${(ThisBuild / baseDirectory).value}/resources/log4j2-test.xml"
// If we fork we immediately stumble upon https://github.com/sbt/sbt/issues/3892 and https://github.com/sbt/sbt/issues/3892
ThisBuild / Test / javaOptions += s"-Duser.dir=${(ThisBuild / baseDirectory).value}"

ThisBuild / libraryDependencies ++= Seq(
  // `Optional` means "not transitive", but still included in "stage/lib"
)

publish / skip := true

ThisBuild / resolvers ++= Seq(
  Resolver.mavenLocal,
  Resolver.githubPackages("appthreat/overflowdb2"),
  "Sonatype OSS".at("https://oss.sonatype.org/content/repositories/public")
)

ThisBuild / licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

ThisBuild / versionScheme := Some("semver-spec")

lazy val schema            = Projects.schema
lazy val domainClasses     = Projects.domainClasses
lazy val protoBindings     = Projects.protoBindings
lazy val codepropertygraph = Projects.codepropertygraph
lazy val schema2json       = Projects.schema2json

ThisBuild / scalacOptions ++= Seq(
  "-release",
  "21",
  "-deprecation"
)

ThisBuild / javacOptions ++= Seq(
  "-g", // debug symbols
  "-Xlint",
  "--release", "21"
) ++ {
  // fail early if users with JDK11 try to run this
  val javaVersion = sys.props("java.specification.version").toFloat
  assert(javaVersion.toInt >= 21, s"this build requires JDK21+ - you're using $javaVersion")
  Nil
}

Global / onChangedBuildSource := ReloadOnSourceChanges

githubOwner := "appthreat"
githubRepository := "cpg2"
githubSuppressPublicationWarning := true
credentials +=
  Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "appthreat",
    sys.env.getOrElse("GITHUB_TOKEN", "N/A")
  )
