name := "schema2json"

dependsOn(Projects.schema)

libraryDependencies ++= Seq(
  "org.json4s"            %% "json4s-native" % Versions.json4s,
  "org.scalatest"         %% "scalatest"     % Versions.scalatest % Test
)

scalacOptions -= "-Xfatal-warnings" // some antl-generated sources prompt compiler warnings :(

scalacOptions ++= (
    Seq(
      "-deprecation", // Emit warning and location for usages of deprecated APIs.
      "--release",
      "21"
    )
)

compile / javacOptions ++= Seq("-Xlint", "--release=21", "-g")
Test / fork := true
testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v")

enablePlugins(JavaAppPackaging)

ThisBuild / resolvers ++= Seq(
  Resolver.mavenLocal,
  Resolver.githubPackages("appthreat/overflowdb2"),
  "Sonatype OSS".at("https://oss.sonatype.org/content/repositories/public")
)
