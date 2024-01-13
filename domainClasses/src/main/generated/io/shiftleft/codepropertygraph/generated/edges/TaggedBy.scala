package io.shiftleft.codepropertygraph.generated.edges

import overflowdb.*
import scala.jdk.CollectionConverters.*

object TaggedBy:
    val Label = "TAGGED_BY"

    object PropertyNames:

        val all: Set[String]                 = Set()
        val allAsJava: java.util.Set[String] = all.asJava

    object Properties {}

    object PropertyDefaults {}

    val layoutInformation = new EdgeLayoutInformation(Label, PropertyNames.allAsJava)

    val factory = new EdgeFactory[TaggedBy]:
        override val forLabel = TaggedBy.Label

        override def createEdge(graph: Graph, outNode: NodeRef[NodeDb], inNode: NodeRef[NodeDb]) =
            new TaggedBy(graph, outNode, inNode)
end TaggedBy

class TaggedBy(_graph: Graph, _outNode: NodeRef[NodeDb], _inNode: NodeRef[NodeDb])
    extends Edge(_graph, TaggedBy.Label, _outNode, _inNode, TaggedBy.PropertyNames.allAsJava):

    override def propertyDefaultValue(propertyKey: String) =
        propertyKey match

            case _ => super.propertyDefaultValue(propertyKey)
