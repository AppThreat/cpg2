ThisBuild / organization := "io.appthreat"
ThisBuild / version      := "3.1.1"
ThisBuild / scalaVersion := "3.10.0"

// parsed by project/Versions.scala
val overflowdbVersion = "3.1.1"
val overflowdbCodegenVersion = "2.103"

ThisBuild / Test / fork           := true
ThisBuild / Test / javaOptions += s"-Dlog4j2.configurationFile=file:${(ThisBuild / baseDirectory).value}/resources/log4j2-test.xml"
// If we fork we immediately stumble upon https://github.com/sbt/sbt/issues/3892 and https://github.com/sbt/sbt/issues/3892
ThisBuild / Test / javaOptions += s"-Duser.dir=${(ThisBuild / baseDirectory).value}"
// sbt 2 runs the suites of a forked test JVM in parallel by default; keep sbt 1's one-at-a-time
// runs, since the pass tests set and clear JVM-wide system properties
ThisBuild / Test / testForkedParallel := false
// executeTests reads it through a dynamic task, which lintUnused cannot see
Global / excludeLintKeys += testForkedParallel
// sbt 2 packs test classes+resources into jars (exportJars := true); keep exploded dirs so
// tests can read their fixtures as plain files
ThisBuild / Test / exportJars := false

ThisBuild / resolvers ++= Seq(
  Resolver.mavenLocal,
  "Sonatype OSS".at("https://oss.sonatype.org/content/repositories/public")
)

ThisBuild / licenses := List("Apache-2.0" -> uri("http://www.apache.org/licenses/LICENSE-2.0"))

ThisBuild / versionScheme := Some("semver-spec")

lazy val schema            = Projects.schema
lazy val domainClasses     = Projects.domainClasses
lazy val protoBindings     = Projects.protoBindings
lazy val codepropertygraph = Projects.codepropertygraph
lazy val schema2json       = Projects.schema2json

// sbt 2 applies bare settings to every subproject, so root-only settings live on the root project.
// Its name must differ from codepropertygraph's ("cpg2"): sbt 2 names output directories after it.
lazy val root = project
  .in(file("."))
  .aggregate(schema, domainClasses, protoBindings, codepropertygraph, schema2json)
  .settings(
    name := "cpg2-root",
    publish / skip := true
  )

ThisBuild / scalacOptions ++= Seq(
  "-release",
  "23",
  "-deprecation"
)

ThisBuild / javacOptions ++= Seq(
  "-Xlint",
  "--release", "23"
) ++ {
  // fail early if users with JDK11 try to run this
  val javaVersion = sys.props("java.specification.version").toFloat
  assert(javaVersion.toInt >= 23, s"this build requires JDK23+ - you're using $javaVersion")
  Nil
}

Global / onChangedBuildSource := ReloadOnSourceChanges

githubOwner := "appthreat"
githubRepository := "cpg2"
credentials +=
  Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "appthreat",
    sys.env.getOrElse("GITHUB_TOKEN", "N/A")
  )
