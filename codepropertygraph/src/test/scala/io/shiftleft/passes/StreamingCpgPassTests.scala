package io.shiftleft.passes

import better.files.File
import io.shiftleft.SerializedCpg
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.Properties
import io.shiftleft.codepropertygraph.generated.nodes.{NewCall, NewFile}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.concurrent.TimeLimits
import org.scalatest.time.{Seconds, Span}
import overflowdb.traversal.*

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class StreamingCpgPassTests extends AnyWordSpec with Matchers with TimeLimits {

    val DeadlockTimeout = Span(5, Seconds)

    private object Fixture {
        def apply(keyPools: Option[Iterator[KeyPool]] = None)(f: (Cpg, CpgPassBase) => Unit): Unit = {
            val cpg  = Cpg.emptyCpg
            val pool = keyPools.flatMap(_.nextOption())
            class MyPass(cpg: Cpg) extends StreamingCpgPass[String](cpg, "MyPass", pool) {
                override def generateParts(): Array[String] = Array("foo", "bar")

                override def runOnPart(diffGraph: DiffGraphBuilder, part: String): Unit = {
                    diffGraph.addNode(NewFile().name(part))
                }
            }
            val pass = new MyPass(cpg)
            f(cpg, pass)
        }
    }

    "StreamingCpgPass" should {
        "allow creating and applying result of pass" in Fixture() { (cpg, pass) =>
            failAfter(DeadlockTimeout) {
                pass.createAndApply()
                cpg.graph.nodes.map(_.property(Properties.NAME)).toSetMutable shouldBe Set("foo", "bar")
            }
        }

        "produce a serialized inverse CPG" in Fixture() { (_, pass) =>
            failAfter(DeadlockTimeout) {
                File.usingTemporaryFile("pass", ".zip") { file =>
                    file.delete()
                    val filename      = file.path.toString
                    val serializedCpg = new SerializedCpg(filename)
                    pass.createApplySerializeAndStore(serializedCpg, true)
                    serializedCpg.close()
                    file.exists shouldBe true
                    Files.size(file.path) should not be 0
                }
            }
        }

        "fail gracefully (no deadlock) when Writer crashes under load" in {
            failAfter(DeadlockTimeout) {
                val cpg = Cpg.emptyCpg
                val partCount = 100

                val pass = new StreamingCpgPass[String](cpg, "LoadTestPass") {
                    override def generateParts(): Array[String] =
                        (0 until partCount).map(_.toString).toArray

                    override def runOnPart(diffGraph: DiffGraphBuilder, part: String): Unit = {
                        val partId = part.toInt
                        if (partId == 10) {
                            val f1 = NewFile().name("A")
                            val f2 = NewFile().name("B")
                            diffGraph.addNode(f1).addNode(f2)
                            diffGraph.addEdge(f1, f2, "ILLEGAL_SCHEMA_VIOLATION")
                        } else {
                            Thread.sleep(10)
                            diffGraph.addNode(NewFile().name(s"file_$partId"))
                        }
                    }
                }

                intercept[RuntimeException] {
                    pass.createAndApply()
                }
            }
        }

        "apply every part regardless of the configured writer batch size" in {
            failAfter(DeadlockTimeout) {
                val prop      = "odb.streamingpass.writerBatchSize"
                val previous  = Option(System.getProperty(prop))
                val partCount = 200
                try
                    for batchSize <- Seq(1, 3, 32, partCount + 50) do
                        System.setProperty(prop, batchSize.toString)
                        StreamingCpgPass.writerBatchSize shouldBe batchSize
                        val cpg = Cpg.emptyCpg
                        val pass = new StreamingCpgPass[String](cpg, "BatchSizePass") {
                            override def generateParts(): Array[String] =
                                (0 until partCount).map(i => s"file_$i").toArray
                            override def runOnPart(
                              diffGraph: DiffGraphBuilder,
                              part: String
                            ): Unit =
                                diffGraph.addNode(NewFile().name(part))
                        }
                        pass.createAndApply()
                        cpg.graph.nodes.map(_.property(Properties.NAME)).toSetMutable shouldBe
                            (0 until partCount).map(i => s"file_$i").toSet
                finally
                    previous match
                        case Some(v) => System.setProperty(prop, v)
                        case None    => System.clearProperty(prop)
            }
        }

        "fall back to the default writer batch size for invalid system properties" in {
            val prop     = "odb.streamingpass.writerBatchSize"
            val previous = Option(System.getProperty(prop))
            try
                for invalid <- Seq("0", "-5", "not-a-number") do
                    System.setProperty(prop, invalid)
                    StreamingCpgPass.writerBatchSize shouldBe 32
            finally
                previous match
                    case Some(v) => System.setProperty(prop, v)
                    case None    => System.clearProperty(prop)
        }

        "fail fast if the writer thread dies unexpectedly" in {
            failAfter(DeadlockTimeout) {
                val cpg = Cpg.emptyCpg
                val pass = new StreamingCpgPass[String](cpg, "SimulatedCrashPass") {
                    override def generateParts() = Array("a", "b", "c", "d", "e")
                    override def runOnPart(builder: DiffGraphBuilder, part: String): Unit = {
                        builder.addNode(NewFile().name(part))

                        if (part == "a") {
                            val n1 = NewFile().name("n1")
                            val n2 = NewFile().name("n2")
                            builder.addNode(n1).addNode(n2).addEdge(n1, n2, "BAD_EDGE")
                        }
                    }
                }

                intercept[Exception] {
                    pass.createAndApply()
                }
            }
        }
    }
}