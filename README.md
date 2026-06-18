# Introduction

**CPG2** is a high-performance library for constructing, manipulating, and serializing Code Property Graphs (CPGs). It serves as the foundation for language frontends (parsers) and static analysis tools.

This library is designed for **extreme scalability**, handling codebases ranging from small microservices to massive monoliths (10M+ LoC) while optimizing for memory pressure and CPU utilization.

## Key Architectures

CPG2 provides specialized base classes for implementing graph passes (transformations). Choosing the right base class is critical for performance.

### 1. `StreamingCpgPass` (Recommended for Frontends)

**Best for:** AST Creation, Initial Graph Construction, Processing millions of independent files.

A memory-optimized replacement for legacy concurrent passes. It trades **determinism of Node IDs** for **maximum throughput and minimal memory footprint**.

- **The Problem:** Traditional concurrent passes use "Head-of-Line Blocking." If Thread A processes a 50MB file and Thread B processes a 1KB file, Thread B's result sits in RAM waiting for Thread A to finish to maintain ID order. In large repos, this causes OOMs.
- **The Solution:** `StreamingCpgPass` writes results to the graph database (OverflowDB) _immediately_ upon task completion.
- **Mechanism:**
  - Uses Virtual Threads for high-concurrency parsing.
  - Implements **Backpressure** using `ExecutorCompletionService` and strict semaphore limits to prevent queuing millions of pending AST objects.
  - **Non-Deterministic IDs:** Node IDs will vary between runs based on execution speed, but graph topology remains identical.

```scala
class MyAstCreationPass(cpg: Cpg) extends StreamingCpgPass[String](cpg) {
  override def generateParts(): Array[String] = sourceFileNames

  override def runOnPart(builder: DiffGraphBuilder, filename: String): Unit = {
    // 1. Parse file
    // 2. Build local AST in 'builder'
    // 3. Return (StreamingCpgPass automatically flushes builder to DB)
  }
}
```

### 2. `OrderedParallelCpgPass` (Recommended for deterministic artifacts)

**Best for:** CI/CD pipelines requiring binary-identical CPGs, Regression Testing, Caching layers.

Functions similarly to `StreamingCpgPass` but includes a **Reordering Buffer**.

- **Mechanism:** Tasks run in parallel. Results are buffered in a holding area. The Writer thread commits them strictly in the order defined by `generateParts()`.
- **Trade-off:** Higher peak memory usage than `StreamingCpgPass` (because fast results must wait in RAM for slow predecessors), but guarantees stable Node IDs.

### 3. `ForkJoinParallelCpgPass`

**Best for:** Semantic Analysis, Linking, Data Flow (Map-Reduce operations).

Unlike the streaming passes, this follows a `fork/join` model. It reads the _entire_ initial state of the graph, computes changes in parallel, and merges them.

- **Mechanism:**
  - **Map:** `runOnPart` generates changes for a specific chunk.
  - **Reduce:** Changes are aggregated into a single `DiffGraph`.
  - **Apply:** All changes are applied atomically at the end.
- **Chunking:** Automatically processes parts in chunks (default: 1000) to keep GC pressure manageable.

### 4. ConcurrentWriterCpgPass

**Best for:** Processing when input order must be strictly preserved in the output ID generation.

`ConcurrentWriterCpgPass` executes tasks in parallel but commits results to the graph database in strict sequential order based on the `generateParts()` array.

- **Head-of-Line Blocking:** If a large file is first in the list, all subsequent results are buffered in memory until the first one completes. This ensures deterministic Node IDs but can cause high memory pressure or OOMs on large repositories.
- **Race Conditions:** `runOnPart` sees the CPG in an intermediate state. Reading data from the CPG while writing to it is unsafe and will lead to race conditions.
- **Use case:** When you require deterministic IDs (like `OrderedParallelCpgPass`) but prefer the legacy implementation style used in older Joern versions.

### Comparison Table

| Feature                   | StreamingCpgPass                            | OrderedParallelCpgPass     | ConcurrentWriterCpgPass    | ForkJoinParallelCpgPass     |
| :------------------------ | :------------------------------------------ | :------------------------- | :------------------------- | :-------------------------- |
| **Execution Model**       | Parallel (Virtual Threads)                  | Parallel (Virtual Threads) | Parallel (Virtual Threads) | Parallel (Fork/Join)        |
| **Commit Order**          | Completion Order (First-Finish First-Write) | Input Order (Strict)       | Input Order (Strict)       | Atomic Batch (All at once)  |
| **Node ID Determinism**   | No (Varies by speed)                        | Yes (Stable)               | Yes (Stable)               | Yes (Stable)                |
| **Memory Footprint**      | Minimal (Best)                              | Moderate (Buffers results) | High (Buffers results)     | Moderate (Chunked)          |
| **Head-of-Line Blocking** | No                                          | Yes                        | Yes                        | N/A (Batch)                 |
| **Best Use Case**         | Large Parsing / AST Creation                | CI/CD / Regression Tests   | Legacy deterministic needs | Semantic Analysis / Linkers |

---

## Performance Tuning

### Virtual Threads

CPG2 leverages JDK 21+ Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor`) for IO-bound tasks (like parsing C/C++ files where disk IO or head-lock contention is high). This allows thousands of concurrent parsers without the overhead of OS threads.

### Backpressure & Memory Safety

Both `StreamingCpgPass` and `OrderedParallelCpgPass` implement strict backpressure.

- **Producer Limit:** `Math.max(4, 0.7 * cores)`
- **Why?** Creating AST nodes is memory-expensive. Allowing unbounded parallelism (e.g., submitting 10,000 files to a thread pool) causes the Heap to fill with `IASTTranslationUnit` objects faster than the Writer can serialize them to disk. The Semaphore/Active-Count mechanism ensures the system only bites off what it can chew.

### KeyPools and IDs

For passes that need to allocate IDs (e.g., creating new nodes), pass a `KeyPool`.

- **IntervalKeyPool:** Allocates a range of IDs (e.g., 1000-2000). Efficient and thread-safe.
- **SequenceKeyPool:** Allocates specific IDs from a list.

### Garbage Collection during Passes

By default, CPG2 runs passes without triggering stop-the-world garbage collection pauses between chunk processing loops. This maximizes throughput. If you run into severe memory constraints during large batch runs and want to force explicit garbage collection pauses after each chunk, set the JVM system property `odb.forkjoinpass.explicitGc=1`.

---

## Usage Example

### build.sbt

```scala
libraryDependencies += "io.appthreat" %% "cpg2" % "2.1.6"
```

### Implementing a Pass

```scala
import io.shiftleft.passes.{StreamingCpgPass, DiffGraphBuilder}

class MyPass(cpg: Cpg) extends StreamingCpgPass[String](cpg) {

  // 1. Define the work items
  override def generateParts(): Array[String] = Array("file1.c", "file2.c")

  // 2. Process each item concurrently
  override def runOnPart(builder: DiffGraphBuilder, filename: String): Unit = {
    val newNode = NewFile().name(filename)
    builder.addNode(newNode)
  }

  // 3. (Optional) Cleanup resources after all parts are processed
  override def finish(): Unit = {
    println("Pass complete!")
  }
}
```

---

## Serialized CPG (Overlay)

CPG2 supports the Overlay protocol (`SerializedCpg`).

- Passes can modify the in-memory graph.
- Passes can _also_ produce ProtoBuf overlays (stored as zip entries) for downstream tools (like Joern) to consume without re-running the analysis.

## Advanced Traversal Algorithms

CPG2 exposes the traversal algorithms from the underlying graph store as clean, idiomatic extension methods on graph nodes and collections inside the `io.shiftleft.codepropertygraph.CpgAlgorithms` package.

### Exposing Extensions

To use these extension methods, import `io.shiftleft.codepropertygraph.CpgAlgorithms.*` in your code.

### Dominators and Post-Dominators

```scala
// Computes immediate dominators in the CFG
val idoms = entryNode.dominatorTree(node => node.out("CFG"))

// Computes post-dominators starting from exit
val postIdoms = exitNode.postDominatorTree(node => node.in("CFG"))
```

### Strongly Connected Components

```scala
// Finds loops and call cycles inside the induced node subgraph
val sccs = allNodes.stronglyConnectedComponents(node => node.out("AST"))
```

### Context-Sensitive Paths

```scala
// Computes data-flow paths matching call/return contexts (OPEN/CLOSE tags)
val path = sourceNode.contextSensitivePathTo(targetNode, getContextEdges, maxStackDepth = 10)
```

### Subgraph Tensor Export

```scala
// Extracts subgraph structures into flat primitive arrays for GNN consumption
val gnnTensors = subgraphNodes.exportToGnn
```

### DSL Traversal Methods

CPG2 also inherits and supports the optimized traversal DSL methods:

#### 1. Navigation Steps

- `.out` or `.out(labels)`: Follow outgoing edges to adjacent nodes.
- `.in` or `.in(labels)`: Follow incoming edges to adjacent nodes.
- `.both` or `.both(labels)`: Follow both incoming and outgoing edges to adjacent nodes.
- `.outE` or `.outE(labels)`: Follow outgoing edges.
- `.inE` or `.inE(labels)`: Follow incoming edges.
- `.bothE` or `.bothE(labels)`: Follow both incoming and outgoing edges.

#### 2. Filtering Steps

- `.hasOut(label)`: A zero-allocation filter step that keeps nodes containing at least one outgoing edge with the specified label, resolved directly in the storage engine without iterator allocations.
- `.hasIn(label)`: A zero-allocation filter step that keeps nodes containing at least one incoming edge with the specified label.
- `.hasId(values)` or `.id(values)`: Keep nodes with the specified IDs.
- `.hasLabel(labels)` or `.label(labels)`: Keep nodes with the specified labels.
- `.labelNot(labels)`: Discard nodes matching the specified labels.
- `.has(key)` or `.hasNot(key)`: Filter elements by existence or non-existence of a property.
- `.has(key, value)` or `.hasNot(key, value)`: Filter elements by property values.
- `.has(propertyPredicate)`: Filter elements using standard predicates like `P.eq`, `P.neq`, `P.within`.
- `.is(value)`: Keep elements that are equal to the specified value.
- `.within(set)` or `.without(set)`: Keep or discard elements present in the specified set.

#### 3. Conditional and Routing Steps

- `.choose(on)(options)`: A routing step that enables conditional paths inside a single fluent traversal expression.
- `.where(subWalk)` or `.whereNot(subWalk)`: Look-ahead filter steps that preserve or discard the active elements depending on whether the sub-walk returns results.
- `.coalesce(options)`: Evaluates traversals in order and returns the first one that emits elements.

#### 4. Transformation and Aggregation Steps

- `.map(fun)`: Transform each element.
- `.flatMap(fun)`: Transform and flatten elements.
- `.collectAll[B]`: Filter and collect elements matching the specified class.
- `.cast[B]`: Cast all elements to the specified type.
- `.dedup` or `.dedupBy(fun)`: Remove duplicate elements.
- `.sorted` or `.sortBy(fun)`: Sort elements.
- `.groupCount` or `.groupCount(fun)`: Group elements and count occurrences.
- `.groupBy(fun)` or `.groupMap(key)(fun)`: Group elements by a key or transform values.
- `.union(travs)`: Aggregate multiple traversal branches into one.

#### 5. Path and Graph Walk Steps

- `.repeat(walk)(config)`: Recursively repeat the walk. Supported modulators include `.maxDepth`, `.until`, `.emit`, `.whilst`, `.breadthFirstSearch`.
- `.path`: Resolves the visited path tracking for each element in the traversal.
- `.neighborhood(maxDepth, direction)`: Returns all nodes reachable within the given maximum depth in the specified direction using cycle-safe breadth-first search.

#### 6. Side Effect and Diagnostic Steps

- `.sideEffect(fun)` or `.sideEffectPF(pf)`: Execute a side effect on each element without altering the traversal.
- `.profile(name)`: Monitors execution time and count metrics for elements passing through the step, logging console statistics upon completion.

#### 7. Materialization Steps

- `.l` or `.toList`: Execute the traversal and return a List.
- `.iterate()`: Execute the traversal strictly for side effects without returning anything.
- `.countTrav` or `.size`: Resolve the total number of elements.
- `.head` or `.headOption`: Return the first element.
- `.last` or `.lastOption`: Return the last element.

## License

Apache-2.0
