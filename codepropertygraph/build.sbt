name := "cpg2"

dependsOn(Projects.protoBindings, Projects.domainClasses)

libraryDependencies ++= Seq(
  "io.appthreat"          %% "odb2-traversal" % Versions.overflowdb,
  "io.appthreat"          %% "odb2-formats"   % Versions.overflowdb,
  "com.github.scopt"      %% "scopt"                % "4.0.1",
  "com.github.pathikrit"  %% "better-files"         % "3.9.2",
  "org.scalatest"         %% "scalatest"            % Versions.scalatest % Test
)
