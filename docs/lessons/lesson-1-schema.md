# Lesson 1: CPG Specification and Graph Schema Architecture

### Learning Objective

Understand how the Code Property Graph (CPG) represents code structures, control flows, and type hierarchies using the Scala DSL schema definitions defined in the CPG specification.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility.
- **Local clone of CPG2**: Clone the [cpg2 repository](https://github.com/AppThreat/cpg2) and run `sbt compile` in the root folder.

### Conceptual Background

A Code Property Graph (CPG) is a directed, edge-labeled, attributed multigraph that unifies multiple program representations into a single structure:

- **Abstract Syntax Tree (AST)**: Captures the nesting of code statements and declarations using `AST` parent-child edges.
- **Control Flow Graph (CFG)**: Captures the order in which statements are evaluated using `CFG` edges.
- **Program Dependence Graph (PDG)**: Captures control dependencies (via dominators) and data dependencies (via reaching definitions / data dependence edges).

In `cpg2`, the schema is defined in the [schema module](https://github.com/AppThreat/cpg2/tree/main/schema/src/main/scala/io/shiftleft/codepropertygraph/schema) using a specialized Scala DSL. This DSL statically defines:

- **Node Types**: For example, [Method](https://github.com/AppThreat/cpg2/blob/main/schema/src/main/scala/io/shiftleft/codepropertygraph/schema/Method.scala) represents function declarations, and [Ast.scala](https://github.com/AppThreat/cpg2/blob/main/schema/src/main/scala/io/shiftleft/codepropertygraph/schema/Ast.scala) defines expressions like `CALL` or `IDENTIFIER`.
- **Edge Types**: For example, `AST` connects parent blocks to children, and `CFG` connects executable statements.
- **Properties**: For example, `NAME`, `FULL_NAME`, `CODE`, `LINE_NUMBER`, and `TYPE_FULL_NAME` are typed properties attached to nodes.
- **Constraints**: Schema definitions enforce which node types are allowed to be connected by a given edge label, preventing malformed graphs at compile time.

### Real Commands

Compile the schema definitions to verify syntax and prepare for code generation:

```bash
sbt schema/compile
```

### Code Example

The schema defines nodes, properties, and edges. Below is a simplified conceptual example of how a schema is declared using the CPG2 DSL:

```scala
package io.shiftleft.codepropertygraph.schema

import overflowdb.schema.*
import overflowdb.schema.SchemaBuilder

class MethodSchema(builder: SchemaBuilder, base: BaseSchema) {
  // Properties definitions
  val name = builder.addProperty("NAME", ValueType.STRING)
    .comment("Name of the method")

  val fullName = builder.addProperty("FULL_NAME", ValueType.STRING)
    .comment("Full path of the method including class and package")

  // Node definition
  val method = builder.addNodeType("METHOD")
    .addProperties(name, fullName)
    .comment("Represents a method declaration")

  // Edge definition
  val ast = builder.addEdgeType("AST")
    .comment("AST parent-child relationship")

  // Edge constraints mapping
  method.addOutEdge(ast, InNode(method))
}
```
