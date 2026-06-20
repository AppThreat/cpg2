# Lesson 5: Wrapping Graph Algorithms for CPG Nodes

### Learning Objective

Apply call-graph and control-flow algorithms (PageRank, Topological Sort, Dominators, SCC, LCA) using the `CpgAlgorithms` implicit operators.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility.
- **Pre-compiled CPG File**: An existing `app.atom` or `cpg.bin` file.
- **JSON Processing Utilities**: Tools like `jq` to parse algorithm output.

### Conceptual Background

In Lesson 6 of the `overflowdb2` curriculum, we analyzed the raw algorithms implemented by the database engine. In CPG2, these algorithms are wrapped using Scala implicit classes inside **[CpgAlgorithms.scala](https://github.com/AppThreat/cpg2/blob/main/codepropertygraph/src/main/scala/io/shiftleft/codepropertygraph/CpgAlgorithms.scala)**.

These implicits extend:

- **`CpgNodeOps`**: Attaches algorithmic methods directly to `Node` instances (e.g., `node.dominatorTree(...)` or `node.pathsTo(...)`).
- **`CpgNodeCollectionOps`**: Attaches methods directly to collections of nodes (e.g., `nodes.stronglyConnectedComponents(...)` or `nodes.pageRank(...)`).

This wrapper logic handles type mapping between Java collections (used by the core algorithms) and Scala collections (used by traversals), simplifying query development for security analysts and compiler developers.

### Real Commands and Code Examples

#### 1. PageRank Centrality on Call Graph

Determine method importance using implicit PageRank calculations:

```scala
import io.shiftleft.codepropertygraph.CpgAlgorithms.*
import io.shiftleft.codepropertygraph.generated.nodes.Method

val methods = cpg.method.l
// Invoke pageRank directly on the Scala collection
val ranks: Map[Long, Double] = methods.pageRank(node => node.out("CALL"))

ranks.toSeq.sortBy(-_._2).take(5).foreach { case (nodeId, score) =>
  println(s"Method: ${cpg.graph.node(nodeId).property("FULL_NAME")} | Score: $score")
}
```

#### 2. Calculating the Dominator Tree

Find immediate dominators directly from a CFG node:

```scala
import io.shiftleft.codepropertygraph.CpgAlgorithms.*

val entryStatement = method.entryBlock
// Compute dominator tree directly from the node
val dominatorMap: Map[Long, Long] = entryStatement.dominatorTree(node => node.out("CFG"))

dominatorMap.foreach { case (nodeId, domId) =>
  println(s"Statement ID $nodeId is dominated by $domId")
}
```

#### 3. Topological Sorting of Declarations

Perform topological sorting on collections of declarations:

```scala
import io.shiftleft.codepropertygraph.CpgAlgorithms.*
import io.shiftleft.codepropertygraph.generated.nodes.Declaration

val declarations = method.astChildren.collect { case d: Declaration => d }.toList
val sortedNodes = declarations.topologicalSort(node => node.out("DEPENDS_ON"))

sortedNodes.foreach(decl => println(s"Sorted Decl: ${decl.name}"))
```

#### 4. Disjoint Set Union-Find for Alias Clusters

Find variable equivalence sets using `UnionFind`:

```scala
import overflowdb.algorithm.UnionFind
import io.shiftleft.codepropertygraph.generated.nodes.Local

val uf = new UnionFind[Local]()
cpg.local.foreach(uf.add)

// Perform union on assignments
cpg.assignment.foreach { assignment =>
  val target = assignment.argument(1).isIdentifier.flatMap(_.local).headOption
  val source = assignment.argument(2).isIdentifier.flatMap(_.local).headOption

  if (target.isDefined && source.isDefined) {
    uf.union(target.get, source.get)
  }
}
```
