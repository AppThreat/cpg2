# Lesson 4: Protobuf Representation and Graph Serialization

### Learning Objective

Serialize and deserialize Code Property Graph states using Google Protocol Buffers to enable language-neutral storage and inter-process exchange.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility.
- **Protobuf Compiler (protoc)**: Required if compiling proto definitions manually.
- **Local clone of CPG2**: Clone of [cpg2](https://github.com/AppThreat/cpg2) to build the `proto-bindings` module.

### Conceptual Background

Large graph analysis tools are often built in different languages. For instance, code parsers might be implemented in Python, Go, or Rust, while the data-flow analysis engine is written in Scala or Java. To enable exchange between these runtimes, the CPG must be represented in a standard, language-neutral format.

CPG2 achieves this by compiling the graph structure into Google Protocol Buffers. The core schema definitions are defined in `cpg.proto`.

The serialization pipeline involves:

- **Nodes representation**: Each node is serialized with its ID, label, and a list of key-value properties.
- **Edges representation**: Edges are serialized as connection pairs containing the source node ID, target node ID, and edge label.
- **Compilation**: The `proto-bindings` subproject compiles `cpg.proto` using `protoc` to generate Java/Scala bindings (`Cpg.java`).
- **Interprocess Exchange**: Parsers write protobuf bytes to files (e.g. `cpg.bin`), which are then loaded by the Scala engine using `CpgLoader`.

### Real Commands

Build and compile the protocol buffer bindings:

```bash
sbt proto-bindings/compile
```

### Code Example

The serialization logic iterates over nodes and edges. Below is a conceptual Scala example showing how the graph is serialized to protobuf bytes:

```scala
package io.shiftleft.codepropertygraph

import io.shiftleft.proto.cpg.Cpg as ProtoCpg
import overflowdb.*
import java.io.FileOutputStream
import scala.jdk.CollectionConverters.*

class CpgSerializer(graph: Graph) {
  def serializeTo(outputPath: String): Unit = {
    val builder = ProtoCpg.CpgStruct.newBuilder()

    // Serialize nodes
    graph.nodes().asScala.foreach { node =>
      val nodeBuilder = ProtoCpg.CpgStruct.Node.newBuilder()
        .setKey(node.id())
        .setType(ProtoCpg.CpgStruct.Node.NodeType.valueOf(node.label()))

      node.propertiesMap().forEach { (k, v) =>
        val prop = ProtoCpg.CpgStruct.Node.Property.newBuilder()
          .setName(k)
          .setValue(v.toString)
        nodeBuilder.addProperty(prop)
      }
      builder.addNode(nodeBuilder)
    }

    // Write to file output stream
    val fos = new FileOutputStream(outputPath)
    try {
      builder.build().writeTo(fos)
    } finally {
      fos.close()
    }
  }
}
```
