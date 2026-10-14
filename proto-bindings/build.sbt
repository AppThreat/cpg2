name := "cpg2-protos"

lazy val generateProtobuf = taskKey[File]("generate protobuf definitions: cpg.proto")

enablePlugins(ProtobufPlugin)
val protocLocalDir   = "protoc"
val protocBinaryPath = s"$protocLocalDir/bin/protoc"
ProtobufConfig / protobufProtoc := protocBinaryPath
ProtobufConfig / version        := "3.25.5"
// compile the schema project's latest cpg.proto in place: since 0.8.4, sbt-protobuf prunes
// protobuf_external down to the schemas unpacked from dependencies, so it cannot be copied there
ProtobufConfig / protobufSources += Def.uncached((Projects.schema / generateProtobuf).value)
ProtobufConfig / protobufIncludePaths += Def.uncached((Projects.schema / generateProtobuf).value.getParentFile)
ProtobufConfig / protobufGenerate := Def.uncached(
  (ProtobufConfig / protobufGenerate)
    .dependsOn(installProtoc)
    .value
)

lazy val installProtoc =
  taskKey[File]("downloads configured protoc version for your platform, unless already available")
installProtoc := Def.uncached {
  import sys.process._
  import java.nio.file.Files
  // import better.files.FileExtensions
  val protocVersion      = "3.20.1"
  val protocBinary       = new File(protocBinaryPath)
  val isAlreadyInstalled = protocBinary.exists && s"$protocBinaryPath --version".!!.contains(protocVersion)

  if (!isAlreadyInstalled) {
    val platform = (System.getProperty("os.name"), System.getProperty("os.arch")) match {
      case ("Linux", "amd64")                                                      => "linux-x86_64"
      case (name, "amd64") if name.startsWith("Windows")                           => "win64"
      case (name, arch) if name.toLowerCase.contains("mac") && arch.contains("64") => "osx-x86_64"
      case (name, arch) =>
        throw new AssertionError(
          s"unknown platform name/arch ($name/$arch), please add in `proto-bindings/build.sbt` match"
        )
    }
    val url = new URI(
      s"https://github.com/protocolbuffers/protobuf/releases/download/v$protocVersion/protoc-$protocVersion-$platform.zip"
    ).toURL

    println(s"downloading $url and extracting into $protocLocalDir")
    val outdir = new File(protocLocalDir)
    FileUtils.deleteRecursively(outdir)
    IO.unzipURL(url, outdir)
    val permissionSet = new java.util.HashSet[java.nio.file.attribute.PosixFilePermission]()
    permissionSet.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
    Files.setPosixFilePermissions(protocBinary.toPath(), permissionSet)
  }
  protocBinary
}

/** workaround for an IntelliJ [bug](https://youtrack.jetbrains.com/issue/SCL-19517), where it only recognizes generated
  * sources if there is at least one Scala class in the same package
  *
  * TODO remove after a fix for https://youtrack.jetbrains.com/issue/SCL-19517 is available
  */
Compile / sourceGenerators += Def.task {
  val protoPackage = "io.shiftleft.proto.cpg"

  val scalaFile = (Compile / sourceManaged).value / "_ONLY_FOR_INTELLIJ.scala"

  IO.write(
    scalaFile,
    s"""package $protoPackage
      |
      |private class _ONLY_FOR_INTELLIJ
      |""".stripMargin
  )

  Seq(scalaFile)
}.taskValue

githubOwner := "appthreat"
githubRepository := "cpg2"
credentials +=
  Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "appthreat",
    sys.env.getOrElse("GITHUB_TOKEN", "N/A")
  )
