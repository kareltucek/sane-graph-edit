package client

import graph_tools.DynamicPlacement
import graph_tools.NodeShape
import graph_tools.NodeText
import graph_tools.TextMeasurer
import utils.Rgb
import utils.Vector2

/** A node as a renderer draws it; coordinates and sizes in workspace units. */
data class SceneNode(
    val id: String,
    val position: Vector2,
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
    return Scene(
        nodes = g.nodes.filter { it in visible }.map { n ->
            SceneNode(
                id = n.id,
                position = n.position,
                shapeBounds = n.cache.shapeBounds,
                textBounds = n.cache.textBounds,
                lines = n.cache.lines,
                shape = (NodeShape.values().firstOrNull { it.impl === n.cache.shape } ?: NodeShape.defaultShape).id,
                bg = n.attributes.bg,
                fg = n.attributes.fg,
                fontSize = NodeText.fontSize(n),
                monospace = NodeText.isMonospace(n),
                selected = n in selected,
            )
        },
        edges = g.edges.filter { it.src in visible && it.dst in visible }.map { SceneEdge(it.cache.srcPt, it.cache.dstPt) },
        selectionBox = gestures.selectionBoxFrom?.let { Vector2.computeCorners(gestures.lastPosition, it) },
    )
}
