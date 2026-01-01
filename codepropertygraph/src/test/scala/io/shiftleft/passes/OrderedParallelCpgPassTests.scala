package io.shiftleft.passes

import better.files.File
import io.shiftleft.SerializedCpg
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.Properties
import io.shiftleft.codepropertygraph.generated.nodes.{NewCall, NewFile}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import overflowdb.traversal.*

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

class OrderedParallelCpgPassTests extends AnyWordSpec with Matchers {

    private object Fixture {
        def apply(keyPools: Option[Iterator[KeyPool]] = None)(f: (Cpg, CpgPassBase) => Unit): Unit = {
            val cpg  = Cpg.emptyCpg
            val pool = keyPools.flatMap(_.nextOption())
            class MyPass(cpg: Cpg) extends OrderedParallelCpgPass[String](cpg, "MyPass", pool) {
                override def generateParts(): Array[String] = Array("foo", "bar")

                override def runOnPart(diffGraph: DiffGraphBuilder, part: String): Unit = {
                    diffGraph.addNode(NewFile().name(part))
                }
            }
            val pass = new MyPass(cpg)
            f(cpg, pass)
        }
    }

    "OrderedParallelCpgPass" should {
        "allow creating and applying result of pass" in Fixture() { (cpg, pass) =>
            pass.createAndApply()
            cpg.graph.nodes.map(_.property(Properties.NAME)).toSetMutable shouldBe Set("foo", "bar")
        }

        "produce a serialized inverse CPG" in Fixture() { (_, pass) =>
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

        val keyPools = Iterator(new IntervalKeyPool(10, 20), new IntervalKeyPool(30, 40))

        "use only the first KeyPool for createAndApply" in Fixture(Some(keyPools)) { (cpg, pass) =>
            pass.createAndApply()
            val ids = cpg.graph.V.asScala.map(_.id()).toSet
            ids.foreach { id =>
                id should (be >= 10L and be <= 20L)
            }
        }

        "preserve order of IDs independent of execution speed" in {
            // This is the defining test for Ordered vs Streaming.
            // We force "part A" to be slow. In a streaming pass, "part B" would write first
            // and get lower IDs. In Ordered pass, "part A" must still get the lower IDs.
            val cpg = Cpg.emptyCpg
            val pass = new OrderedParallelCpgPass[String](cpg, "ordered_pass") {
                override def generateParts() = Array("A", "B")

                override def runOnPart(diffGraph: DiffGraphBuilder, part: String): Unit = {
                    part match {
                        case "A" =>
                            Thread.sleep(200) // Force A to finish slower
                            diffGraph.addNode(NewFile().name("A"))
                        case "B" =>
                            diffGraph.addNode(NewFile().name("B"))
                    }
                }
            }

            pass.createAndApply()

            val nodes = cpg.graph.nodes.asScala.toList.sortBy(_.id())
            nodes.size shouldBe 2

            // Node A must have the lower ID because it was first in generateParts(),
            // even though it finished processing last.
            nodes(0).property(Properties.NAME) shouldBe "A"
            nodes(1).property(Properties.NAME) shouldBe "B"
        }

        "fail for schema violations" in {
            val cpg = Cpg.emptyCpg
            val pass = new OrderedParallelCpgPass[String](cpg, "pass2") {
                override def generateParts() = Array("a", "b")
                override def runOnPart(diffGraph: DiffGraphBuilder, part: String): Unit =
                    part match {
                        case "a" =>
                            diffGraph.addNode(NewFile().name(part))
                        case "b" =>
                            // Deliberate schema violation
                            val file1 = NewFile().name("foo")
                            val file2 = NewFile().name("bar")
                            diffGraph
                                .addNode(file1)
                                .addNode(file2)
                                .addEdge(file1, file2, "illegal_edge_label")
                    }
            }

            intercept[Exception] {
                pass.createAndApply()
            }
        }
    }
}