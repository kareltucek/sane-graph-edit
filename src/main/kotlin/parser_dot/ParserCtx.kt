package parser_dot

import Edge
import Graph
import DotGraphLoader
import Node

data class ParserCtx(
    val graph: Graph,
    val idGen: IdGen,
    val log: ParseLog,
    val nodeStack: MutableList<Node> = mutableListOf(),
    val edgeStack: MutableList<Edge> = mutableListOf(),
    val nodes: MutableMap<String, Node> = mutableMapOf(),
    val edges: MutableMap<String, Edge> = mutableMapOf(),
) {

    fun clear() {
        this.edgeStack.clear()
        this.nodeStack.clear()
    }

    fun pushNode(it: DotGraphLoader.Token): Node {
        val id = it.value
        if (!this.nodes.containsKey(id)) {
            val n = Node(name = id)
            this.nodes[id] = n
            this.graph.add(nodes[id]!!)
            log.add(LogNodeDefined(id))
        }

        this.nodeStack.add(this.nodes[id]!!)

        return this.nodes[id]!!
    }

    fun pushEdge(edge: DotGraphLoader.Token): Edge? {
        return this.nodeStack
            .takeLast(2)
            .takeIf { it.size == 2 }
            ?.let { it[0] to it[1] }
            ?.let { (src, dst) ->
                val id = idGen.new("e")
                val e = Edge(src = src, dst = dst, name = id)
                this.graph.add(e)
                this.edgeStack.add(e)
                log.add(LogEdgeDefined(id))
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

    fun pushGraphAttribute(tok: DotGraphLoader.Token, attr: DotGraphLoader.Token) {
    }
}