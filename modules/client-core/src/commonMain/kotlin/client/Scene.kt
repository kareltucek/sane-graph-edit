package client

import graph_tools.DynamicPlacement
import graph_tools.Edge
import utils.Constants
import graph_tools.Node
import graph_tools.NodeShape
import graph_tools.NodeText
import graph_tools.NodeType
import graph_tools.TextMeasurer
import utils.Rgb
import utils.Vector2

/**
 * A node as a renderer draws it; coordinates and sizes in workspace
 * units. A node that is not in the plane the view speaks of is
 * drawn [scale] times its sizes around [position], which is where
 * it appears to be.
 */
data class SceneNode(
    val id: String,
    val position: Vector2,
    val scale: Double,
    val shapeBounds: Vector2,
    val textBounds: Vector2,
    val lines: List<String>,
    /** A [NodeShape.id]. */
    val shape: String,
    val bg: Rgb?,
    val fg: Rgb?,
    val fontSize: Double,
    val monospace: Boolean,
    val selected: Boolean,
    /** What the text is: decides how it is shown while it is not edited. */
    val type: NodeType = NodeType.Plain,
)

/** An edge as a renderer draws it: a line with its head at [dst]. */
data class SceneEdge(val src: Vector2, val dst: Vector2)

/**
 * What a view shows right now, as plain data (conn1, client →
 * renderer): the visible nodes and edges in drawing order, and the
 * selection rectangle being dragged, if any. A renderer needs
 * nothing else from the graph to paint.
 */
data class Scene(
    val nodes: List<SceneNode>,
    val edges: List<SceneEdge>,
    val selectionBox: Pair<Vector2, Vector2>?,
)

/**
 * Bring the node sizes up to date, measuring text with [measurer],
 * and describe what the view shows.
 */
fun EditorView.scene(measurer: TextMeasurer): Scene {
    g.recompute(measurer)
    // after recompute: placement needs the node sizes
    DynamicPlacement.place(g)
    g.recompute(measurer)

    val visible = g.visibleNodes()
    val selected = g.selectedNodes
    // how each node appears from where the view looks; none for one behind the viewer or too near
    val seen = g.nodes.filter { it in visible }.mapNotNull { n -> apparent(n)?.let { n to it } }.toMap()
    return Scene(
        // what is further from the viewer first: what is nearer is drawn over it
        nodes = seen.entries.sortedBy { it.value.scale }.map { (n, how) -> sceneNode(n, n in selected, how) },
        edges = g.edges.mapNotNull { e ->
            val src = seen[e.src]
            val dst = seen[e.dst]
            if (src == null || dst == null) null else sceneEdge(e, src, dst)
        },
        selectionBox = gestures.selectionBoxFrom?.let { Vector2.computeCorners(gestures.lastPosition, it) },
    )
}

/** The node [id] alone, as [scene] describes it, its size brought up to date with [measurer]. Null when there is none, or it is not shown. */
fun EditorView.sceneNode(id: String, measurer: TextMeasurer): SceneNode? {
    g.recompute(measurer)
    return g.node(id)?.let { n -> apparent(n)?.let { sceneNode(n, n in g.selectedNodes, it) } }
}

/** The edge [e] between its nodes as they appear: from border to border of what is seen of them. */
private fun sceneEdge(e: Edge, src: Apparent, dst: Apparent): SceneEdge =
    // both in the plane: as the graph has it
    if (src.scale == 1.0 && dst.scale == 1.0) SceneEdge(e.cache.srcPt, e.cache.dstPt)
    else {
        val dir = (dst.position - src.position).toUnit()
        val out = e.src.cache.shape.connectionPoint(dir, e.src, 0.0) * src.scale
        val into = e.dst.cache.shape.connectionPoint(dir, e.dst, Constants.arrowheadRadius) * dst.scale
        listOf(
            src.position + out to dst.position - into,
            src.position - out to dst.position - into,
            src.position + out to dst.position + into,
        ).minBy { (it.first - it.second).lengthSquared() }.let { SceneEdge(it.first, it.second) }
    }

private fun sceneNode(n: Node, selected: Boolean, how: Apparent): SceneNode =
    SceneNode(
        id = n.id,
        position = how.position,
        scale = how.scale,
        shapeBounds = n.cache.shapeBounds,
        textBounds = n.cache.textBounds,
        lines = n.cache.lines,
        shape = (NodeShape.values().firstOrNull { it.impl === n.cache.shape } ?: NodeShape.defaultShape).id,
        bg = n.attributes.bg,
        fg = n.attributes.fg,
        fontSize = NodeText.fontSize(n),
        monospace = NodeText.isMonospace(n),
        selected = selected,
        type = NodeType.of(n),
    )
