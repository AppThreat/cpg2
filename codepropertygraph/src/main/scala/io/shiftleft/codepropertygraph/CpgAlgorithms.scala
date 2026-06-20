package io.shiftleft.codepropertygraph

import overflowdb.Node
import overflowdb.algorithm.{
    AsynchronousPrefetcher,
    ContextSensitivePathFinder,
    DependencySequencer,
    DominatorTree,
    GetParents,
    GnnExporter,
    HeapWalker,
    LowestCommonAncestors,
    PageRank,
    PathFinder,
    StronglyConnectedComponents,
    TopologicalSort
}
import scala.jdk.CollectionConverters.*

object CpgAlgorithms:

    implicit class CpgNodeOps(val node: Node) extends AnyVal:
        /** Computes the immediate dominator tree starting from this node.
          * @param getSuccessors
          *   A function mapping a node to its outgoing CFG neighbors.
          */
        def dominatorTree(getSuccessors: Node => Iterator[Node]): Map[Long, Long] =
            val jFunc: java.util.function.Function[Node, java.util.Iterator[Node]] =
                (n: Node) => getSuccessors(n).asJava
            DominatorTree.computeDominators(node, jFunc).asScala.map { case (k, v) =>
                k.longValue() -> v.longValue()
            }.toMap

        /** Computes the immediate post-dominator tree starting from this terminal node.
          * @param getPredecessors
          *   A function mapping a node to its incoming CFG neighbors.
          */
        def postDominatorTree(getPredecessors: Node => Iterator[Node]): Map[Long, Long] =
            val jFunc: java.util.function.Function[Node, java.util.Iterator[Node]] =
                (n: Node) => getPredecessors(n).asJava
            DominatorTree.computePostDominators(node, jFunc).asScala.map { case (k, v) =>
                k.longValue() -> v.longValue()
            }.toMap

        /** Finds a context-sensitive path from this node to the target node.
          * @param target
          *   The target destination node.
          * @param getEdges
          *   A function returning context-sensitive transitions (OPEN, CLOSE, NEUTRAL).
          * @param maxStackDepth
          *   Maximum call-stack depth.
          */
        def contextSensitivePathTo(
          target: Node,
          getEdges: Node => Iterator[ContextSensitivePathFinder.ContextEdge],
          maxStackDepth: Int = 10
        ): Option[Seq[Node]] =
            val jFunc: java.util.function.Function[
              Node,
              java.util.Iterator[ContextSensitivePathFinder.ContextEdge]
            ] =
                (n: Node) => getEdges(n).asJava
            val pathOpt = ContextSensitivePathFinder.findPath(node, target, jFunc, maxStackDepth)
            if pathOpt.isPresent then Some(pathOpt.get().nodes.asScala.toSeq)
            else None
        end contextSensitivePathTo

        /** Walks the AST/graph iteratively using the heap stack instead of recursive JVM frames to
          * avoid stack overflow.
          */
        def walkHeap(edgeLabels: String*): Iterator[Node] =
            HeapWalker.forNode(node, edgeLabels*).asScala

        /** Find shortest paths from this node to target node up to maxDepth. */
        def pathsTo(target: Node, maxDepth: Int = -1): Seq[PathFinder.Path] =
            PathFinder(node, target, maxDepth)
    end CpgNodeOps

    implicit class CpgNodeCollectionOps(val nodes: IterableOnce[Node]) extends AnyVal:
        /** Computes Strongly Connected Components (SCC) for the sub-graph induced by these nodes.
          * @param getSuccessors
          *   Successor function.
          */
        def stronglyConnectedComponents(getSuccessors: Node => Iterator[Node]): Seq[Set[Node]] =
            val jFunc: java.util.function.Function[Node, java.util.Iterator[Node]] =
                (n: Node) => getSuccessors(n).asJava
            val jNodes = nodes.iterator.toSeq.asJava
            StronglyConnectedComponents.compute(jNodes, jFunc).asScala.map(_.asScala.toSet).toSeq

        /** Asynchronously pre-fetches evicted nodes from disk storage in the background.
          */
        def prefetch(prefetcher: AsynchronousPrefetcher): Unit =
            val jNodes = nodes.iterator.toSeq.asJava
            prefetcher.prefetch(jNodes)

        def exportToGnn: GnnExporter.GnnExport =
            val jNodes = nodes.iterator.toSeq.asJava
            GnnExporter.exportGraph(jNodes)

        /** Sorts these nodes topologically based on the given successor function.
          * @throws TopologicalSort.CycleDetectedException
          *   if a cycle is detected.
          */
        def topologicalSort(getSuccessors: Node => Iterator[Node]): Seq[Node] =
            val jFunc: java.util.function.Function[Node, java.util.Iterator[Node]] =
                (n: Node) => getSuccessors(n).asJava
            val jNodes = nodes.iterator.toSeq.asJava
            TopologicalSort.sort(jNodes, jFunc).asScala.toSeq

        /** Find Lowest Common Ancestors (LCA) in a DAG for this set of nodes based on a parent
          * resolver.
          */
        def lowestCommonAncestors(getParentsFunc: Node => Set[Node]): Set[Node] =
            implicit val gp: GetParents[Node] = (node: Node) => getParentsFunc(node)
            LowestCommonAncestors(nodes.iterator.toSet)

        /** Sequences dependent tasks into parallelisable stages.
          * @throws java.lang.AssertionError
          *   if given nodes have cyclic dependencies.
          */
        def dependencySequence(getParentsFunc: Node => Set[Node]): Seq[Set[Node]] =
            implicit val gp: GetParents[Node] = (node: Node) => getParentsFunc(node)
            DependencySequencer(nodes.iterator.toSet)

        /** Counts, for every node, how many edges from this set point at it. Useful as a quick "how
          * heavily referenced is this" ranking, for example over a call graph.
          * @param getSuccessors
          *   Successor function.
          */
        def inDegreeCentrality(getSuccessors: Node => Iterator[Node]): Map[Long, Int] =
            val jFunc: java.util.function.Function[Node, java.util.Iterator[Node]] =
                (n: Node) => getSuccessors(n).asJava
            val jNodes = nodes.iterator.toSeq.asJava
            PageRank.inDegree(jNodes, jFunc).asScala.map { case (k, v) =>
                k.longValue() -> v.intValue()
            }.toMap

        /** Ranks these nodes with PageRank over the subgraph they induce. A node referenced by
          * highly ranked nodes is itself ranked highly. Scores sum to roughly 1.0.
          * @param getSuccessors
          *   Successor function.
          */
        def pageRank(getSuccessors: Node => Iterator[Node]): Map[Long, Double] =
            val jFunc: java.util.function.Function[Node, java.util.Iterator[Node]] =
                (n: Node) => getSuccessors(n).asJava
            val jNodes = nodes.iterator.toSeq.asJava
            PageRank.compute(jNodes, jFunc).asScala.map { case (k, v) =>
                k.longValue() -> v.doubleValue()
            }.toMap
    end CpgNodeCollectionOps
end CpgAlgorithms
