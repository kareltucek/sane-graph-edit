import graph_tools.*
import utils.Vector2.Companion.Zero
import parser_dot.IdGen
import parser_dot.ParseLog
import utils.Constants
import utils.Vector2
import java.awt.Graphics2D


class Graph(
    var id: String = IdGen.global.new("g"),
) {
    private val nodesRestricted: MutableSet<Node> = mutableSetOf()
    private val edgesRestricted: MutableSet<Edge> = mutableSetOf()
    private val selectedNodesRestricted: MutableSet<Node> = mutableSetOf()
    private val edgeMapRestricted: MutableMap<Node, MutableSet<Edge>> = mutableMapOf()
    private val needsRecomputing: MutableSet<Node> = mutableSetOf()

    var lastActiveNode: Node? = null
    var parseLog: ParseLog? = null

    val nodes: Set<Node>
        get() {
            return nodesRestricted
        }
    val edges: Set<Edge>
        get() {
            return edgesRestricted
        }
    val selectedNodes: Set<Node>
        get() {
            return selectedNodesRestricted
        }
    val edgeMap: Map<Node, Set<Edge>>
        get() {
            return edgeMapRestricted
        }

    val subGraphs: MutableSet<Graph> = mutableSetOf()

    fun add(e: Node) {
        addAllNodes(setOf(e))
    }

    fun add(e: Edge) {
        addAllEdges(setOf(e))
    }

    fun addAllNodes(i: Iterable<Node>) {
        nodesRestricted.addAll(i)
        edgeMapRestricted.putAll(i.map { it to mutableSetOf() })
        needsRecomputing(i)
        lastActiveNode = i.lastOrNull()
    }

    fun addAllEdges(i: Iterable<Edge>) {
        i.forEach {
            edgesRestricted.add(it)
            edgeMapRestricted[it.src]!!.add(it)
            edgeMapRestricted[it.dst]!!.add(it)
            Plotter.recomputeEdges(i)
        }
    }

    fun remove(e: Node) {
        removeAllNodes(setOf(e))
    }

    fun remove(e: Edge) {
        removeAllEdges(setOf(e))
    }

    fun removeAllNodes(i: Iterable<Node>) {
        i.forEach {
            edgesRestricted.removeAll(edgeMapRestricted[it]!!)
            edgeMapRestricted.remove(it)
            selectedNodesRestricted.remove(it)
        }
        nodesRestricted.removeAll(i)
    }

    fun removeAllEdges(i: Iterable<Edge>) {
        i.forEach {
            edgeMapRestricted[it.src]!!.remove(it)
            edgeMapRestricted[it.dst]!!.remove(it)
            edgesRestricted.remove(it)
        }
    }

    fun select(e: Node, commitHistory: Boolean = true) {
        selectAll(setOf(e))
    }

    fun selectAll(i: Iterable<Node>, commitHistory: Boolean = true) {
        selectedNodesRestricted.addAll(i)
        lastActiveNode = i.lastOrNull()
    }

    fun unselect(e: Node, commitHistory: Boolean = true) {
        selectedNodesRestricted.remove(e)
    }

    fun unselectAll(i: Iterable<Node>, commitHistory: Boolean = true) {
        selectedNodesRestricted.removeAll(i)
    }

    fun cleanSelect(i: Iterable<Node>, commitHistory: Boolean = true) {
        selectedNodesRestricted.clear(); selectAll(i)
    }

    fun needsRecomputing(e: Node) {
        needsRecomputing.add(e)
    }

    fun needsRecomputing(e: Iterable<Node>) {
        needsRecomputing.addAll(e)
    }

    fun recompute(g2d: Graphics2D) {
        val nodes = needsRecomputing
        val edges = needsRecomputing.flatMap { edgeMapRestricted[it].orEmpty() }
        Plotter.recomputeNodes(g2d, nodes)
        Plotter.recomputeEdges(edges)
        needsRecomputing.clear()
    }

    fun findEddges(from: Set<Node>, to: Set<Node>): List<Edge> {
        //the first filter ensures that every edge is returned just once in O(n) - otherwise edges inside from will be returned twice
        return from
            .flatMap { n -> edgeMap[n].orEmpty().filter { it.src == n } }
            .filter { from.contains(it.src) && to.contains(it.dst) }
    }

    fun findInEdges(n: Node): List<Edge> = edgeMap[n].orEmpty().filter { it.dst == n }

    fun findOutEdges(n: Node): List<Edge> = edgeMap[n].orEmpty().filter { it.src == n }

    fun findEdge(src: Node, dst: Node, forward: Boolean = true, backward: Boolean = true): Edge? {
        var e: Edge? = null
        //todo: optimize this!
        if (forward) {
            e = edgeMap[src]?.find { it.dst == dst }
        }
        if (backward && e == null) {
            e = edgeMap[src]?.find { it.src == dst }
        }
        return e
    }

    fun centerOfMass(): Vector2 {
        return nodes.fold(Zero) { a, b -> a + b.position } / nodes.size
    }

    fun printStats() {
        println("Graph has ${nodes.size} nodes, and ${edges.size} vertices!")
    }

    companion object {
        fun testGraph(): Graph {
            val g = Graph()
            val n1 = Node(Constants.helpCommands, Vector2(-300, 100))
            val n2 = Node(Constants.helpFile, Vector2(300, 200))
            val n3 = Node(Constants.helpAttribution, Vector2(-100, -200))
            val n4 = Node(Constants.helpTodo, Vector2(-300, -200))
            val e1 = Edge(n1, n2)
            val e2 = Edge(n1, n3)
            val e3 = Edge(n1, n3)
            g.add(n1)
            g.add(n2)
            g.add(n3)
            g.add(e1)
            g.add(e2)
            g.add(e3)
            return g
        }
    }
}