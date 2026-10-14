addSbtPlugin("com.github.sbt" % "sbt-protobuf"        % "0.8.4")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"        % "2.6.2")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.13.0")

// sbt-github-packages and sbt-overflowdb have no sbt 2.x builds; project/GithubPackages.scala and
// project/OdbCodegenSbtPlugin.scala cover them instead. The codegen itself still comes from the
// schema project's overflowdb-codegen dependency.
