name := "codepropertygraph"

dependsOn(Projects.protoBindings, Projects.domainClasses)

libraryDependencies ++= Seq(
  "io.appthreat"          %% "overflowdb-traversal" % Versions.overflowdb,
  "io.appthreat"          %% "overflowdb-formats"   % Versions.overflowdb,
  "com.github.scopt"      %% "scopt"                % "4.0.1",
  "com.github.pathikrit"  %% "better-files"         % "3.9.2",
  "org.scalatest"         %% "scalatest"            % Versions.scalatest % Test
)
