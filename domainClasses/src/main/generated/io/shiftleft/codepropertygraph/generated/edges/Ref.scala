package io.shiftleft.codepropertygraph.generated.edges

import overflowdb.*
import scala.jdk.CollectionConverters.*

object Ref:
    val Label = "REF"

    object PropertyNames:

        val all: Set[String]                 = Set()
        val allAsJava: java.util.Set[String] = all.asJava

    object Properties {}

    object PropertyDefaults {}

    val layoutInformation = new EdgeLayoutInformation(Label, PropertyNames.allAsJava)

    val factory = new EdgeFactory[Ref]:
        override val forLabel = Ref.Label

        override def createEdge(graph: Graph, outNode: NodeRef[NodeDb], inNode: NodeRef[NodeDb]) =
            new Ref(graph, outNode, inNode)
end Ref

class Ref(_graph: Graph, _outNode: NodeRef[NodeDb], _inNode: NodeRef[NodeDb])
    extends Edge(_graph, Ref.Label, _outNode, _inNode, Ref.PropertyNames.allAsJava):

    override def propertyDefaultValue(propertyKey: String) =
        propertyKey match

            case _ => super.propertyDefaultValue(propertyKey)
