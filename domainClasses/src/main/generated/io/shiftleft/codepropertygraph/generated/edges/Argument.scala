package io.shiftleft.codepropertygraph.generated.edges

import overflowdb.*
import scala.jdk.CollectionConverters.*

object Argument:
    val Label = "ARGUMENT"

    object PropertyNames:

        val all: Set[String]                 = Set()
        val allAsJava: java.util.Set[String] = all.asJava

    object Properties {}

    object PropertyDefaults {}

    val layoutInformation = new EdgeLayoutInformation(Label, PropertyNames.allAsJava)

    val factory = new EdgeFactory[Argument]:
        override val forLabel = Argument.Label

        override def createEdge(graph: Graph, outNode: NodeRef[NodeDb], inNode: NodeRef[NodeDb]) =
            new Argument(graph, outNode, inNode)
end Argument

class Argument(_graph: Graph, _outNode: NodeRef[NodeDb], _inNode: NodeRef[NodeDb])
    extends Edge(_graph, Argument.Label, _outNode, _inNode, Argument.PropertyNames.allAsJava):

    override def propertyDefaultValue(propertyKey: String) =
        propertyKey match

            case _ => super.propertyDefaultValue(propertyKey)
