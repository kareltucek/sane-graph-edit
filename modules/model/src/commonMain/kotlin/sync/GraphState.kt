package sync

import Graph
import graph_tools.Edge
import graph_tools.Node
import graph_tools.NodeShape
import kotlinx.serialization.Serializable
import utils.Utils
import utils.Utils.toHexString
import utils.Vector2

/** Field name → value. A field that is absent has its default value. */
typealias Fields = Map<String, String>

/** A field change; a null value removes the field. */
typealias FieldChanges = Map<String, String?>

@Serializable
data class EdgeState(val src: String, val dst: String, val fields: Fields = emptyMap())

/**
 * The shared content of a document as plain data, keyed by node /
 * edge id: what clients and the editor exchange and compare. Holds
 * no per-user state (selection, view) and nothing derived (sizes).
 */
@Serializable
data class GraphState(
    val nodes: Map<String, Fields> = emptyMap(),
    val edges: Map<String, EdgeState> = emptyMap(),
    val attributes: Fields = emptyMap(),
) {
    /** The changes that turn this state into [to]. */
    fun diff(to: GraphState): Patch = Patch(
        addNodes = to.nodes.filterKeys { it !in nodes },
        setNodes = changes(nodes, to.nodes) { it },
        removeNodes = nodes.keys - to.nodes.keys,
        addEdges = to.edges.filterKeys { it !in edges },
        setEdges = changes(edges, to.edges) { it.fields },
        removeEdges = edges.keys - to.edges.keys,
        setAttributes = fieldChanges(attributes, to.attributes),
    )

    /**
     * Why [patch] cannot be applied, or null when it can: everything
     * it names must still exist, and what it adds must not.
     */
    fun conflict(patch: Patch): String? {
        val nodesAfter = nodes.keys - patch.removeNodes + patch.addNodes.keys
        val missingNode = (patch.setNodes.keys + patch.removeNodes).firstOrNull { it !in nodes }
        val missingEdge = (patch.setEdges.keys + patch.removeEdges).firstOrNull { it !in edges }
        val duplicate = patch.addNodes.keys.firstOrNull { it in nodes } ?: patch.addEdges.keys.firstOrNull { it in edges }
        val dangling = patch.addEdges.entries.firstOrNull { it.value.src !in nodesAfter || it.value.dst !in nodesAfter }
        return when {
            missingNode != null -> "node $missingNode no longer exists"
            missingEdge != null -> "edge $missingEdge no longer exists"
            duplicate != null -> "$duplicate already exists"
            dangling != null -> "edge ${dangling.key} connects a node that no longer exists"
            else -> null
        }
    }

    /** This state with [patch] applied. Edges of removed nodes go with them. */
    fun apply(patch: Patch): GraphState {
        val newNodes = LinkedHashMap(nodes)
        patch.removeNodes.forEach { newNodes.remove(it) }
        newNodes.putAll(patch.addNodes)
        patch.setNodes.forEach { (id, c) -> newNodes[id]?.let { newNodes[id] = applyChanges(it, c) } }

        val newEdges = LinkedHashMap(edges)
        patch.removeEdges.forEach { newEdges.remove(it) }
        newEdges.putAll(patch.addEdges)
        patch.setEdges.forEach { (id, c) -> newEdges[id]?.let { newEdges[id] = it.copy(fields = applyChanges(it.fields, c)) } }
        newEdges.values.removeAll { it.src !in newNodes || it.dst !in newNodes }

        return GraphState(newNodes, newEdges, applyChanges(attributes, patch.setAttributes))
    }

    /** A fresh [Graph] with this content. */
    fun toGraph(): Graph = Graph().also {
        it.applyPatch(Patch(addNodes = nodes, addEdges = edges, setAttributes = attributes))
        it.lastActiveNode = null
    }

    companion object {
        fun of(g: Graph): GraphState = GraphState(
            nodes = g.nodes.associate { it.id to NodeFields.of(it) },
            edges = g.edges.associate { it.id to EdgeFields.of(it) },
            attributes = g.attributes.toMap(),
        )

        fun fieldChanges(from: Fields, to: Fields): FieldChanges =
            to.filter { from[it.key] != it.value } + (from.keys - to.keys).associateWith { null }

        fun applyChanges(fields: Fields, changes: FieldChanges): Fields {
            val res = LinkedHashMap(fields)
            changes.forEach { (k, v) -> if (v == null) res.remove(k) else res[k] = v }
            return res
        }

        private fun <T> changes(from: Map<String, T>, to: Map<String, T>, fields: (T) -> Fields): Map<String, FieldChanges> =
            to.mapNotNull { (id, t) ->
                from[id]?.let { f -> fieldChanges(fields(f), fields(t)).takeIf { it.isNotEmpty() }?.let { id to it } }
            }.toMap()
    }
}

/**
 * A change to a [GraphState]: the one kind of operation a client
 * sends to the editor and the editor sends to clients.
 */
@Serializable
data class Patch(
    val addNodes: Map<String, Fields> = emptyMap(),
    val setNodes: Map<String, FieldChanges> = emptyMap(),
    val removeNodes: Set<String> = emptySet(),
    val addEdges: Map<String, EdgeState> = emptyMap(),
    val setEdges: Map<String, FieldChanges> = emptyMap(),
    val removeEdges: Set<String> = emptySet(),
    val setAttributes: FieldChanges = emptyMap(),
) {
    val isEmpty: Boolean
        get() = addNodes.isEmpty() && setNodes.isEmpty() && removeNodes.isEmpty() &&
            addEdges.isEmpty() && setEdges.isEmpty() && removeEdges.isEmpty() && setAttributes.isEmpty()

    /** Nodes whose existing content this patch changes or removes. */
    val touchedNodes: Set<String> get() = setNodes.keys + removeNodes

    /** True when this only sets fields, and exactly the fields [other] sets. */
    fun setsSameFieldsAs(other: Patch): Boolean {
        val onlySets = { p: Patch -> p.addNodes.isEmpty() && p.removeNodes.isEmpty() && p.addEdges.isEmpty() && p.removeEdges.isEmpty() }
        val keys = { p: Patch -> Triple(p.setNodes.mapValues { it.value.keys }, p.setEdges.mapValues { it.value.keys }, p.setAttributes.keys) }
        return onlySets(this) && onlySets(other) && keys(this) == keys(other)
    }
}

/** The fields of a node. [Node.id] is not one of them. */
object NodeFields {
    const val POS = "pos"
    const val TEXT = "text"
    const val BG = "bg"
    const val FG = "fg"
    const val SCALE = "scale"
    const val NAME = "name"
    const val MARKS = "marks"
    const val SESSION_MARKS = "session-marks"
    const val HIDE = "hide"

    /** Prefix of the fields holding DOT attributes the editor does not interpret. */
    const val ATTR = "attr:"

    fun of(n: Node): Fields = listOf(
        POS to "${n.position.x},${n.position.y}",
        TEXT to n.attributes.text,
        BG to n.attributes.bg?.toHexString(),
        FG to n.attributes.fg?.toHexString(),
        SCALE to n.attributes.nodeScale?.toString(),
        NAME to n.attributes.name.takeIf { it.isNotEmpty() },
        MARKS to n.attributes.marks.takeIf { it.isNotEmpty() },
        SESSION_MARKS to n.cache.sessionMarks.takeIf { it.isNotEmpty() },
        HIDE to n.cache.hideLevel.takeIf { it != 0 }?.toString(),
    ).mapNotNull { (k, v) -> v?.let { k to it } }.toMap() +
        n.attributes.other.mapNotNull { (k, v) -> v?.let { ATTR + k to it } }

    fun apply(n: Node, changes: FieldChanges) {
        changes.forEach { (k, v) ->
            when (k) {
                POS -> v?.split(",")?.let { n.position = Vector2(it[0].toDouble(), it[1].toDouble()) }
                TEXT -> n.attributes.text = v.orEmpty()
                BG -> n.attributes.bg = v?.let { Utils.fromHexString(it) }
                FG -> n.attributes.fg = v?.let { Utils.fromHexString(it) }
                SCALE -> n.attributes.nodeScale = v?.toDouble()
                NAME -> n.attributes.name = v.orEmpty()
                MARKS -> n.attributes.marks = v.orEmpty()
                SESSION_MARKS -> n.cache.sessionMarks = v.orEmpty()
                HIDE -> n.cache.hideLevel = v?.toInt() ?: 0
                ATTR + "shape" -> n.setShape(NodeShape.fromString(v))
                else -> if (v == null) n.attributes.other.remove(k.removePrefix(ATTR)) else n.attributes.other[k.removePrefix(ATTR)] = v
            }
        }
    }

    fun create(id: String, fields: Fields): Node = Node(Vector2.Zero, id = id).also { apply(it, fields) }
}

object EdgeFields {
    const val NAME = "name"
    const val ATTR = "attr:"

    fun of(e: Edge): EdgeState = EdgeState(
        src = e.src.id,
        dst = e.dst.id,
        fields = listOfNotNull(e.attributes.name.takeIf { it.isNotEmpty() }?.let { NAME to it }).toMap() +
            e.attributes.other.mapKeys { ATTR + it.key },
    )

    fun apply(e: Edge, changes: FieldChanges) {
        changes.forEach { (k, v) ->
            when {
                k == NAME -> e.attributes.name = v.orEmpty()
                v == null -> e.attributes.other.remove(k.removePrefix(ATTR))
                else -> e.attributes.other[k.removePrefix(ATTR)] = v
            }
        }
    }
}

/**
 * Apply [patch] to this graph, keeping the node and edge objects it
 * already has. Lenient where the graph is already in the patched
 * state (re-adding, re-removing), so a client may apply a patch on
 * top of its own version of the same change.
 */
fun Graph.applyPatch(patch: Patch) {
    val active = lastActiveNode
    removeAllEdges(patch.removeEdges.mapNotNull { edge(it) })
    val removed = patch.removeNodes.mapNotNull { node(it) }
    removeAllEdges(removed.flatMap { edgeMap[it].orEmpty() }.distinct())
    removeAllNodes(removed)

    addAllNodes(patch.addNodes.filterKeys { node(it) == null }.map { (id, f) -> NodeFields.create(id, f) })
    patch.addNodes.forEach { (id, f) -> node(id)?.let { NodeFields.apply(it, GraphState.fieldChanges(NodeFields.of(it), f)) } }
    patch.setNodes.forEach { (id, c) -> node(id)?.let { NodeFields.apply(it, c) } }
    needsRecomputing((patch.addNodes.keys + patch.setNodes.keys).mapNotNull { node(it) })

    val newEdges = patch.addEdges.filterKeys { edge(it) == null }.mapNotNull { (id, s) ->
        val src = node(s.src)
        val dst = node(s.dst)
        if (src != null && dst != null) Edge(src, dst, id = id).also { EdgeFields.apply(it, s.fields) } else null
    }
    addAllEdges(newEdges)
    patch.setEdges.forEach { (id, c) -> edge(id)?.let { EdgeFields.apply(it, c) } }

    patch.setAttributes.forEach { (k, v) -> if (v == null) attributes.remove(k) else attributes[k] = v }
    // adding nodes moves the cursor; a patch is not a user action
    lastActiveNode = active?.takeIf { it in nodes }
}
