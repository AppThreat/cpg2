import org.scalafmt.sbt.ScalafmtPlugin
import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmtConfig
import sbt.*
import sbt.Keys.*

import scala.util.Try

/** Generates the overflowdb domain classes for a schema, replacing `io.shiftleft:sbt-overflowdb`
  * (2.103), which has no sbt 2.x build and is no longer released.
  *
  * Same keys and behaviour as that plugin's `generateDomainClasses`: run `overflowdb.codegen.Main`
  * on the project's own classpath (so the codegen version comes from its `overflowdb-codegen`
  * dependency), formatted with the build's scalafmt config, and skip it while the schema sources,
  * the project's build.sbt and its dependency classpath are unchanged. `ODB_CODEGEN_DISABLE=true`
  * turns it off.
  */
object OdbCodegenSbtPlugin extends AutoPlugin {

    object autoImport {
        val generateDomainClasses = taskKey[File](
          "regenerates domain classes for the given schema; return value is the output root directory"
        )
        val outputDir = settingKey[File](
          "target directory for the generated domain classes, e.g. `Projects.domainClasses/scalaSource`"
        )
        val classWithSchema = settingKey[String]("class with schema field, e.g. `org.example.MyDomain$`")
        val fieldName = settingKey[String](
          "(static) field name for schema within the specified `classWithSchema`, e.g. `instance`"
        )
        val disableFormatting = settingKey[Boolean]("disable automatic scalafmt invocation")
    }
    import autoImport.*

    override def requires: Plugins      = plugins.JvmPlugin && ScalafmtPlugin
    override def trigger: PluginTrigger = noTrigger

    override def projectSettings: Seq[Setting[?]] = inConfig(Compile)(
      Seq(
        generateDomainClasses                     := Def.uncached(generateDomainClassesTask.value),
        generateDomainClasses / disableFormatting := false
      )
    )

    private lazy val generateDomainClassesTask: Def.Initialize[Task[File]] = Def.taskDyn {
        val log           = streams.value.log
        val outputDirFile = (generateDomainClasses / outputDir).value
        val formatting =
            if ((generateDomainClasses / disableFormatting).value) Seq("--noformat")
            else
                Seq((generateDomainClasses / scalafmtConfig).value)
                    .filter(_.exists)
                    .map(config => s"--scalafmtConfig=$config")
        val args = Seq(
          s"--classWithSchema=${(generateDomainClasses / classWithSchema).value}",
          s"--field=${(generateDomainClasses / fieldName).value}",
          s"--out=$outputDirFile"
        ) ++ formatting

        val hashFile         = target.value / "overflowdb-schema-and-dependencies.md5"
        val dependenciesFile = target.value / "dependenciesCP.txt" // includes the codegen version
        IO.write(dependenciesFile, dependencyClasspath.value.mkString(System.lineSeparator))
        val currentHash = FileUtils.md5(sourceDirectory.value, baseDirectory.value / "build.sbt", dependenciesFile)
        val lastHash    = Try(IO.read(hashFile)).toOption

        if (sys.env.getOrElse("ODB_CODEGEN_DISABLE", "false").equalsIgnoreCase("true")) {
            log.info("overflowdb codegen is disabled")
            Def.task(outputDirFile)
        } else if (outputDirFile.exists && lastHash.contains(currentHash)) {
            log.info("inputs did not change -> no need to run codegen")
            Def.task(outputDirFile)
        } else {
            Def.task {
                (Compile / runMain).toTask(args.mkString(" overflowdb.codegen.Main ", " ", "")).value
                IO.write(hashFile, currentHash)
                outputDirFile
            }
        }
    }
}
