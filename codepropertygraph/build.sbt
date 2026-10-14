name := "cpg2"

BareBuildSyntax.dependsOn(Projects.protoBindings, Projects.domainClasses)

libraryDependencies ++= Seq(
  "io.appthreat"          %% "odb2-traversal" % Versions.overflowdb,
  "io.appthreat"          %% "odb2-formats"   % Versions.overflowdb,
  "com.github.scopt"      %% "scopt"                % "4.1.0",
  "com.github.pathikrit"  %% "better-files"         % "3.9.2",
  "org.scalatest"         %% "scalatest"            % Versions.scalatest % Test
)

githubOwner := "appthreat"
githubRepository := "cpg2"
credentials +=
  Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "appthreat",
    sys.env.getOrElse("GITHUB_TOKEN", "N/A")
  )
