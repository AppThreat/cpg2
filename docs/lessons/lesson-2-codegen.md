# Lesson 2: Domain Class Code-Generation (Codegen)

### Learning Objective

Master how the CPG2 schema DSL compiles to concrete Type-safe domain nodes, edges, factories, and properties, understanding the compilation pipeline of the generated classes.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility.
- **Local clone of CPG2**: A clone of [cpg2](https://github.com/AppThreat/cpg2).

### Conceptual Background

Generic graph databases allow any node to connect to any other node with arbitrary attributes. In static analysis compilers, this dynamic behavior introduces memory overhead and allows bugs like linking a `CALL` node directly to a `LOCAL` node via a control flow edge.

CPG2 enforces strict, compiler-level typing by running a code-generation pass. The schema compiler parses the Scala DSL files in [io.shiftleft.codepropertygraph.schema](https://github.com/AppThreat/cpg2/tree/main/schema/src/main/scala/io/shiftleft/codepropertygraph/schema) and outputs concrete Java and Scala source files.

These generated source files are grouped under the `domainClasses` module and compiled directly into the binary distribution. The generated classes include:

- **Node Types**: For example, `Method`, `Call`, `Local`, `Identifier`, and `Literal` are generated as concrete classes extending `NodeRef` and `NodeDb`.
- **Properties**: Strongly-typed accessors (e.g. `node.name` returning a String instead of an Object needing a cast).
- **Edge Factories**: Type-safe factories for edge relations (e.g., `AST`, `CFG`, `CALL`, `REACHING_DEF`).
- **Static Traversals**: Helper step methods generated directly into traits, defining valid outgoing and incoming paths.

If you attempt to write code that sets an invalid property or adds an unsupported edge type, the code will fail to compile. This ensures type safety throughout the analysis pipeline.

### Real Commands

Trigger code generation and compile the resulting domain classes:

```bash
sbt domainClasses/compile
```

### Code Example

The generated domain class compiles properties into dedicated fields. Below is a conceptual illustration of a generated `Call` node class interface:

```scala
package io.shiftleft.codepropertygraph.generated.nodes

import overflowdb.*

// NodeRef definition
class Call(graph: Graph, id: Long) extends NodeRef[CallDb](graph, id) {
  def name: String = get().name
  def code: String = get().code
  def lineNumber: Option[Integer] = get().lineNumber
}

// NodeDb definition
class CallDb(graph: Graph, id: Long) extends NodeDb(graph, id) {
  var name: String = ""
  var code: String = ""
  var lineNumber: java.lang.Integer = null

  override def property(key: String): Object = key match {
    case "NAME" => name
    case "CODE" => code
    case "LINE_NUMBER" => lineNumber
    case _ => null
  }
}
```
