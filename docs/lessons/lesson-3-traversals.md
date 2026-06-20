# Lesson 3: Traversals, Shortcuts, and AST/CFG Navigation

### Learning Objective

Navigate code structures using CPG shortcuts (such as `cpg.method.parameter` or `cpg.call.argument`) and trace control/data dependencies across call-graph boundaries.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard Java SE Development Kit.
- **SBT 1.10+**: Standard build utility.
- **Pre-compiled CPG File**: An existing `app.atom` or `cpg.bin` file.
- **Node.js or Python**: Runtimes to generate test graphs.

### Conceptual Background

The strength of CPG2 lies in its querying capability. By defining type-safe domain classes, CPG2 can offer convenient shortcuts and semantic steps. These steps are declared as implicit classes in [Shortcuts.scala](https://github.com/AppThreat/cpg2/blob/main/schema/src/main/scala/io/shiftleft/codepropertygraph/schema/Shortcuts.scala) and compiled into traits.

Key navigation concepts include:

- **AST Traversal**: Traverse down from a method to retrieve its block statements, or walk up from an expression to find its enclosing method or file boundary.
- **CFG Traversal**: Follow execution paths (predecessors and successors) to evaluate statement ordering.
- **Call Graph Navigation**: Follow `CALL` edges from call sites to resolve target methods, or follow incoming edges (`callIn`) to locate callers.
- **Parameter/Argument Alignment**: Map the arguments of a call site (such as `call.argument(1)`) to the parameters of the resolved method (such as `method.parameter(1)`).

### Real Commands and Code Examples

#### 1. Listing All Methods and Their Parameters

Query the graph to list method signatures and parameter types:

```scala
cpg.method.foreach { method =>
  val params = method.parameter.map(p => s"${p.name}: ${p.typeFullName}").mkString(", ")
  println(s"Method: ${method.fullName}($params)")
}
```

#### 2. Resolving Call Targets and Mapping Arguments

Trace a call site to its target method definition:

```scala
cpg.call.name("execute.*").foreach { call =>
  // Resolve target method definitions using NoResolve resolver
  val targets = call.callee.fullName.l
  val arg1 = call.argument(1).code
  println(s"Callsite '${call.code}' resolves to: $targets | Arg 1 code: $arg1")
}
```

#### 3. Backtracking from a Local Variable to its Method enclosing block

Walk up the AST hierarchy:

```scala
cpg.local.name("secretToken").foreach { local =>
  val enclosingMethod = local.method.fullName.headOption.getOrElse("unknown")
  val fileName = local.file.name.headOption.getOrElse("unknown")
  println(s"Variable '${local.name}' declared in method '$enclosingMethod' ($fileName)")
}
```

#### 4. Checking Control Flow Successors

Identify branch structures in the CFG:

```scala
cpg.call.name("checkPassword").cfgNext.foreach { nextStatement =>
  println(s"Following checkPassword, the next statement executed is: ${nextStatement.code}")
}
```
