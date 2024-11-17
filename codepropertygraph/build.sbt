name := "cpg2"

dependsOn(Projects.protoBindings, Projects.domainClasses)

libraryDependencies ++= Seq(
  "io.appthreat"          %% "odb2-traversal" % Versions.overflowdb,
  "io.appthreat"          %% "odb2-formats"   % Versions.overflowdb,
  "com.github.scopt"      %% "scopt"                % "4.1.0",
  "com.github.pathikrit"  %% "better-files"         % "3.9.2",
  "org.scalatest"         %% "scalatest"            % Versions.scalatest % Test
)

ThisBuild / resolvers ++= Seq(
  Resolver.mavenLocal,
  Resolver.githubPackages("appthreat/overflowdb2"),
  "Sonatype OSS".at("https://oss.sonatype.org/content/repositories/public")
)
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
