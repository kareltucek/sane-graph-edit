import Vector2.Companion.Zero
import java.awt.Graphics2D

data class Edge(
    val src: Node,
    val dst: Node,
    val id: String = "",
    val cache: EdgeData = EdgeData(),
) {
    class EdgeData(
        var srcPt: Vector2 = Zero,
        var dstPt: Vector2 = Zero,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Edge
        return src == other.src && dst == other.dst && id == other.id
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + src.hashCode()
        result = 31 * result + dst.hashCode()
        return result
    }

    fun applyAttribute(l: String, r: String) {
    }

    companion object {
    }
}

class Node(
    var text: String,
    var position: Vector2,
    var id: String = "",
    var cachedBounds: Plotter.TextPlotter.NodeBounds = Plotter.TextPlotter.NodeBounds(Zero, Zero, emptyList()),
) {
    fun applyAttribute(l: String, r: String) {
        if (l == "label") {
            text = r
        }
    }

    constructor(label: String, x: Float, y: Float) : this(label, Vector2(x, y))
    constructor(label: String, x: Int, y: Int) : this(label, Vector2(x.toFloat(), y.toFloat()))

    companion object {
        fun fromId(id: String) = Node ( text = id, position = Zero, id = id)
    }
}

class Graph(
   val id: String = ""
) {
    private val nodesRestricted: MutableSet<Node> = mutableSetOf()
    private val edgesRestricted: MutableSet<Edge> = mutableSetOf()
    private val selectedNodesRestricted: MutableSet<Node> = mutableSetOf()
    private val edgeMapRestricted: MutableMap<Node, MutableSet<Edge>> = mutableMapOf()
    private val needsRecomputing: MutableSet<Node> = mutableSetOf()

    var lastActiveNode: Node? = null
    val nodes: Set<Node> get() { return nodesRestricted }
    val edges: Set<Edge> get() { return edgesRestricted }
    val selectedNodes: Set<Node> get() { return  selectedNodesRestricted }

    val subGraphs: MutableSet<Graph> = mutableSetOf()

    fun add(e: Node) { addAllNodes(setOf(e)) }
    fun add(e: Edge) { addAllEdges(setOf(e)) }
    fun addAllNodes(i: Iterable<Node>) {
        nodesRestricted.addAll(i)
        edgeMapRestricted.putAll(i.map {it to mutableSetOf() } )
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
    fun remove(e: Node) { removeAllNodes(setOf(e)) }
    fun remove(e: Edge) { removeAllEdges(setOf(e)) }
    fun removeAllNodes(i: Iterable<Node>) {
        i.forEach {
            edgesRestricted.removeAll(edgeMapRestricted[it]!!)
            edgeMapRestricted.remove(it)
            selectedNodesRestricted.remove(it)
        }
        nodesRestricted.removeAll(i)
    }
    fun removeAllEdges(i: Set<Edge>) {
        i.forEach {
            edgeMapRestricted[it.src]!!.remove(it)
            edgeMapRestricted[it.dst]!!.remove(it)
            edgesRestricted.remove(it)
        }
    }
    fun select(e: Node, commitHistory: Boolean = true) { selectAll(setOf(e)) }
    fun selectAll(i: Iterable<Node>, commitHistory: Boolean = true) {
        selectedNodesRestricted.addAll(i)
        lastActiveNode = i.lastOrNull()
    }
    fun unselect(e: Node, commitHistory: Boolean = true) { selectedNodesRestricted.remove(e) }
    fun unselectAll(i: Iterable<Node>, commitHistory: Boolean = true) { selectedNodesRestricted.removeAll(i) }
    fun cleanSelect(i: Iterable<Node>, commitHistory: Boolean = true) { selectedNodesRestricted.clear(); selectAll(i) }

    fun needsRecomputing(e: Node) { needsRecomputing.add(e) }
    fun needsRecomputing(e: Iterable<Node>) { needsRecomputing.addAll(e) }

    fun recompute(g2d: Graphics2D) {
        val nodes = needsRecomputing
        val edges = needsRecomputing.flatMap { edgeMapRestricted[it].orEmpty() }
        Plotter.recomputeNodes(g2d, nodes)
        Plotter.recomputeEdges(edges)
        needsRecomputing.clear()
    }

    companion object {
        fun testGraph(): Graph {
            val g = Graph()
            val n1 = Node(Constants.helpCommands, -300, 100)
            val n2 = Node(Constants.helpFile, 300, 200)
            val n3 = Node(Constants.helpAttribution, -100, -200)
            val n4 = Node(Constants.helpTodo, -300, -200)
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

        val g: Graph = testGraph()
    }
}