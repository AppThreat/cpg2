package io.shiftleft.codepropertygraph.schema

import overflowdb.codegen.CodeGen

import java.io.File

import java.security.MessageDigest
import java.nio.ByteBuffer

object Codegen:
    def main(args: Array[String]): Unit =
        val outputDir =
            args.headOption.map(new File(_)).getOrElse(throw new AssertionError(
              "please pass outputDir as first parameter"
            ))
        val onlyHash = args.contains("--only-hash")

        val schema = CpgSchema.instance
        if !onlyHash then
            new CodeGen(schema).run(outputDir)

        val nodeLabels   = schema.nodeTypes.map(_.name).sorted
        val edgeLabels   = schema.edgeTypes.map(_.name).sorted
        val propertyKeys = schema.properties.map(_.name).sorted

        val md         = MessageDigest.getInstance("SHA-256")
        val allStrings = (nodeLabels ++ edgeLabels ++ propertyKeys).mkString("\n")
        md.update(allStrings.getBytes("UTF-8"))
        val digest     = md.digest()
        val buffer     = ByteBuffer.wrap(digest)
        val schemaHash = buffer.getLong()

        val packageDir = new File(outputDir, "io/shiftleft/codepropertygraph/generated")
        packageDir.mkdirs()
        val schemaInfoFile = new File(packageDir, "SchemaInfo.scala")

        val fileContent =
            s"""package io.shiftleft.codepropertygraph.generated
               |
               |object SchemaInfo:
               |    val schemaHash: Long = ${schemaHash}L
               |""".stripMargin

        val writer = new java.io.PrintWriter(schemaInfoFile)
        try
            writer.write(fileContent)
        finally
            writer.close()
    end main
end Codegen
