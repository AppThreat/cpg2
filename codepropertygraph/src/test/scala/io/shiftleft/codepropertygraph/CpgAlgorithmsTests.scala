package io.shiftleft.codepropertygraph

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import io.shiftleft.OverflowDbTestInstance
import io.shiftleft.codepropertygraph.CpgAlgorithms._
import overflowdb.algorithm.{AsynchronousPrefetcher, ContextSensitivePathFinder}
import scala.jdk.CollectionConverters._

class CpgAlgorithmsTests extends AnyWordSpec with Matchers {

  "CpgAlgorithms" should {

    "compute dominators on CPG nodes correctly" in {
      val graph = OverflowDbTestInstance.create
      val a = graph.addNode("METHOD")
      val b = graph.addNode("METHOD")
      val c = graph.addNode("METHOD")
      val d = graph.addNode("METHOD")

      a.addEdge("AST", b)
      a.addEdge("AST", c)
      b.addEdge("AST", d)
      c.addEdge("AST", d)

      val idoms = a.dominatorTree(n => n.out("AST").asScala)
      idoms.get(b.id()) shouldBe Some(a.id())
      idoms.get(c.id()) shouldBe Some(a.id())
      idoms.get(d.id()) shouldBe Some(a.id())

      graph.close()
    }

    "compute strongly connected components correctly" in {
      val graph = OverflowDbTestInstance.create
      val a = graph.addNode("METHOD")
      val b = graph.addNode("METHOD")
      val c = graph.addNode("METHOD")

      a.addEdge("AST", b)
      b.addEdge("AST", c)
      c.addEdge("AST", a)

      val sccs = Seq(a, b, c).stronglyConnectedComponents(n => n.out("AST").asScala)
      sccs shouldBe Seq(Set(a, b, c))

      graph.close()
    }

    "find context-sensitive path on CPG nodes correctly" in {
      val graph = OverflowDbTestInstance.create
      val entry = graph.addNode("METHOD")
      val call1 = graph.addNode("METHOD")
      val body = graph.addNode("METHOD")
      val ret1 = graph.addNode("METHOD")
      val exit = graph.addNode("METHOD")

      import ContextSensitivePathFinder.ContextEdge
      import ContextSensitivePathFinder.ContextEdge.Type

      val getEdges = (n: overflowdb.Node) => {
        val list = new java.util.ArrayList[ContextEdge]()
        if (n == entry) {
          list.add(new ContextEdge(call1, Type.NEUTRAL, 0))
        } else if (n == call1) {
          list.add(new ContextEdge(body, Type.OPEN, 101))
        } else if (n == body) {
          list.add(new ContextEdge(ret1, Type.CLOSE, 101))
          list.add(new ContextEdge(exit, Type.CLOSE, 999))
        }
        list.iterator().asScala
      }

      val pathOpt = entry.contextSensitivePathTo(ret1, getEdges, 5)
      pathOpt shouldBe Some(Seq(entry, call1, body, ret1))

      val invalidPathOpt = entry.contextSensitivePathTo(exit, getEdges, 5)
      invalidPathOpt shouldBe None

      graph.close()
    }

    "export subgraph to GNN flat arrays" in {
      val graph = OverflowDbTestInstance.create
      val a = graph.addNode("METHOD")
      val b = graph.addNode("METHOD")
      a.addEdge("AST", b)

      val gnnExport = Seq(a, b).exportToGnn
      gnnExport.nodeIds should contain theSameElementsAs Array(a.id(), b.id())
      gnnExport.edgeSrcIds shouldBe Array(a.id())
      gnnExport.edgeDstIds shouldBe Array(b.id())

      graph.close()
    }

    "rank nodes by PageRank and in-degree centrality" in {
      val graph = OverflowDbTestInstance.create
      val hub = graph.addNode("METHOD")
      val a = graph.addNode("METHOD")
      val b = graph.addNode("METHOD")
      val c = graph.addNode("METHOD")

      a.addEdge("AST", hub)
      b.addEdge("AST", hub)
      c.addEdge("AST", hub)

      val nodes = Seq(hub, a, b, c)

      val degrees = nodes.inDegreeCentrality(n => n.out("AST").asScala)
      degrees(hub.id()) shouldBe 3
      degrees(a.id()) shouldBe 0

      val ranks = nodes.pageRank(n => n.out("AST").asScala)
      ranks.values.sum shouldBe (1.0 +- 1e-6)
      ranks(hub.id()) should be > ranks(a.id())

      graph.close()
    }

    "support new traversal methods (hasOut, hasIn, neighborhood, profile)" in {
      val graph = OverflowDbTestInstance.create
      val a = graph.addNode("METHOD")
      val b = graph.addNode("METHOD")
      a.addEdge("AST", b)

      import overflowdb.traversal._

      // Test hasOut / hasIn
      val hasOutNodes = graph.V().asScala.hasOut("AST").toList
      hasOutNodes shouldBe List(a)

      val hasInNodes = graph.V().asScala.hasIn("AST").toList
      hasInNodes shouldBe List(b)

      // Test neighborhood
      val neighborhoodNodes = Iterator.single(a).neighborhood(1, overflowdb.Direction.OUT).toSet
      neighborhoodNodes shouldBe Set(a, b)

      // Test profile
      val profiledNodes = graph.V().asScala.profile("cpg2-profile").toList
      profiledNodes.size shouldBe 2

      graph.close()
    }

    "support walkHeap, pathsTo, topologicalSort, lowestCommonAncestors, and dependencySequence" in {
      val graph = OverflowDbTestInstance.create
      val a = graph.addNode("METHOD")
      val b = graph.addNode("METHOD")
      val c = graph.addNode("METHOD")
      val d = graph.addNode("METHOD")

      a.addEdge("AST", b)
      a.addEdge("AST", c)
      b.addEdge("AST", d)
      c.addEdge("AST", d)

      // Test walkHeap
      val walked = a.walkHeap("AST").toSet
      walked shouldBe Set(a, b, c, d)

      // Test pathsTo
      val paths = a.pathsTo(d)
      paths.map(_.nodes) should contain (Seq(a, b, d))

      // Test topologicalSort
      val sorted = Seq(a, b, c, d).topologicalSort(n => n.out("AST").asScala)
      sorted.indexOf(a) should be < sorted.indexOf(b)
      sorted.indexOf(a) should be < sorted.indexOf(c)
      sorted.indexOf(b) should be < sorted.indexOf(d)
      sorted.indexOf(c) should be < sorted.indexOf(d)

      // Test lowestCommonAncestors
      val lca = Seq(b, c).lowestCommonAncestors(n => n.in("AST").asScala.toSet)
      lca shouldBe Set(a)

      // Test dependencySequence
      val stages = Seq(a, b, c, d).dependencySequence(n => n.in("AST").asScala.toSet)
      stages shouldBe Seq(Set(a), Set(b, c), Set(d))

      graph.close()
    }
  }
}

