name := "schema2json"

BareBuildSyntax.dependsOn(Projects.schema)

libraryDependencies ++= Seq(
  "org.json4s"            %% "json4s-native" % Versions.json4s,
  "org.scalatest"         %% "scalatest"     % Versions.scalatest % Test
)

scalacOptions -= "-Xfatal-warnings" // some antl-generated sources prompt compiler warnings :(

compile / javacOptions ++= Seq("-Xlint", "--release=23", "-g")
Test / fork := true
testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v")

enablePlugins(JavaAppPackaging)
// sbt 2 moves `target` to target/out/jvm/scala-<version>/schema2json; the checked-in schema2json.sh
// links to the staged launcher where sbt 1 put it. sbt 2's clean only removes target/out, so clean
// the stage too, as sbt 1's removal of all of target did.
Universal / com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.stagingDirectory :=
  baseDirectory.value / "target" / "universal" / "stage"
cleanFiles += (Universal / com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.stagingDirectory).value

githubOwner := "appthreat"
githubRepository := "cpg2"
credentials +=
  Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "appthreat",
    sys.env.getOrElse("GITHUB_TOKEN", "N/A")
  )
