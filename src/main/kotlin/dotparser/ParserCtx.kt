package dotparser

import Edge
import Graph
import Lexer
import Node

data class ParserCtx(
    val graph: Graph,
    val idGen: IdGen,
    val nodeStack: MutableList<Node> = mutableListOf(),
    val edgeStack: MutableList<Edge> = mutableListOf(),
    val nodes: MutableMap<String, Node> = mutableMapOf(),
    val edges: MutableMap<String, Edge> = mutableMapOf(),

    ) {

    fun clear() {
        this.edgeStack.clear()
        this.nodeStack.clear()
    }

    fun pushNode(it: Lexer.Token): Node {
        if (!this.nodes.containsKey(it.value)) {
            this.nodes[it.value] = Node.fromId(it.value)
            this.graph.add(nodes[it.value]!!)
        }

        return this.nodes[it.value]!!
    }

    fun pushEdge(edge: Lexer.Token): Edge? {
        return this.nodeStack
            .takeLast(2)
            .takeIf { it.size == 2 }
            ?.let { it[0] to it[1] }
            ?.let { (src, dst) ->
                val id = idGen.new("e")
                val e = Edge(src = src, dst = dst, id = id)
                this.graph.add(e)
                this.edgeStack.add(e)
                e
            }
    }

    fun pushAttribute(left: String?, right: String?) {
        left?.let { l ->
            right?.let { r ->
                if (edgeStack.isEmpty()) {
                    nodeStack.forEach {
                        it.applyAttribute(l, r)
                    }
                } else {
                    edgeStack.forEach {
                        it.applyAttribute(l, r)
                    }
                }
            }
        }
    }
}