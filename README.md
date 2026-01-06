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

---

## Usage Example

### build.sbt

```scala
libraryDependencies += "io.appthreat" %% "cpg2" % "2.1.2"
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

## License

Apache-2.0
