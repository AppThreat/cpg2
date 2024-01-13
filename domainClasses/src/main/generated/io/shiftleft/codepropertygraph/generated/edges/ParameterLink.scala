package io.shiftleft.codepropertygraph.generated.edges

import overflowdb.*
import scala.jdk.CollectionConverters.*

object ParameterLink:
    val Label = "PARAMETER_LINK"

    object PropertyNames:

        val all: Set[String]                 = Set()
        val allAsJava: java.util.Set[String] = all.asJava

    object Properties {}

    object PropertyDefaults {}

    val layoutInformation = new EdgeLayoutInformation(Label, PropertyNames.allAsJava)

    val factory = new EdgeFactory[ParameterLink]:
        override val forLabel = ParameterLink.Label

        override def createEdge(graph: Graph, outNode: NodeRef[NodeDb], inNode: NodeRef[NodeDb]) =
            new ParameterLink(graph, outNode, inNode)
end ParameterLink

class ParameterLink(_graph: Graph, _outNode: NodeRef[NodeDb], _inNode: NodeRef[NodeDb])
    extends Edge(
      _graph,
      ParameterLink.Label,
      _outNode,
      _inNode,
      ParameterLink.PropertyNames.allAsJava
    ):

    override def propertyDefaultValue(propertyKey: String) =
        propertyKey match

            case _ => super.propertyDefaultValue(propertyKey)
