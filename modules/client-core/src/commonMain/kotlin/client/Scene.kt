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
    /** Hidden, and seen only as a parent or a child of a shown node ([graph_tools.Graph.isFaint]): drawn half as strong. */
    val faint: Boolean = false,
    /** The node has a size of its own ([graph_tools.ManualSize]): what of its content does not fit is not shown, and it is resized by its corners. */
    val manual: Boolean = false,
)

/**
 * An edge as a renderer draws it: a line with its head at [dst].
 * Between nodes out of the plane the view speaks of the line is
 * [scale] times as thick and the head [headScale] times as big; an
 * edge that leaves the view towards the viewer has no head (0).
 */
data class SceneEdge(val src: Vector2, val dst: Vector2, val scale: Double = 1.0, val headScale: Double = 1.0)

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
    val shells: List<SceneShell> = emptyList(),
)

/**
 * A system of nodes of [level] ([graph_tools.Multilevel.Shell]; 0: a node, [sun] if it is the sun of one of the lowest):
 * a ball, flat a circle, [radius] around [position] and [depth] away (for [SpaceScene]; in [Scene] as it appears); in
 * [SpaceScene] also a ring of its [reach], if any.
 */
data class SceneShell(val position: Vector2, val radius: Double, val level: Int, val depth: Double = 0.0, val sun: Boolean = false, val reach: Double? = null)

/**
 * What a view shows, not projected: for a renderer that has
 * perspective of its own (conn1, client → renderer). The nodes are
 * where the view sees them ([EditorView.viewPoint]: across, down
 * and [depths] away), each as big as it is, facing the viewer; the
 * edges are between nodes, by index into [nodes]. The viewer is
 * [distance] in front of the plane at [depth], before the point
 * [middle] of it, which is in the middle of the screen; a unit of
 * that plane is [scale] pixels.
 */
data class SpaceScene(
    val nodes: List<SceneNode>,
    val depths: List<Double>,
    val edges: List<Pair<Int, Int>>,
    val middle: Vector2,
    val depth: Double,
    val distance: Double,
    val scale: Double,
    val shells: List<SceneShell> = emptyList(),
)

/** Bring the node sizes up to date, as [scene] does, and describe what the view shows without projecting it. */
fun EditorView.spaceScene(measurer: TextMeasurer, asSeen: Boolean = true): SpaceScene {
    g.recompute(measurer)
    DynamicPlacement.place(g, depth = viewTransform.threeD)
    g.recompute(measurer)
    val shown = g.visibleNodes()
    val visible = g.nodes.filter { it in shown }
    val index = visible.withIndex().associate { (i, n) -> n to i }
    val selected = g.selectedNodes
    // not [asSeen]: where the nodes are in the graph, however this view is turned; for a renderer that looks from where it likes
    val points = visible.map { if (asSeen) viewPoint(it) else Point3(it.position, graph_tools.Depth.of(it)) }
    return SpaceScene(
        nodes = visible.zip(points).map { (n, at) -> sceneNode(n, n in selected, g.isFaint(n), Apparent(at.xy, 1.0)) },
        depths = points.map { it.z },
        edges = g.edges.mapNotNull { e -> index[e.src]?.let { from -> index[e.dst]?.let { from to it } } },
        middle = viewTransform.toWorkspace(size / 2),
        depth = viewTransform.depth,
        distance = viewTransform.distance,
        scale = viewTransform.scale,
        shells = gestures.shells.map { s -> (if (asSeen) viewTransform.toView(s.at) else s.at).let { SceneShell(it.xy, s.radius, s.level, it.z, s.sun, s.reach) } },
    )
}

/**
 * Bring the node sizes up to date, measuring text with [measurer],
 * and describe what the view shows.
 */
fun EditorView.scene(measurer: TextMeasurer): Scene {
    g.recompute(measurer)
    // after recompute: placement needs the node sizes
    DynamicPlacement.place(g, depth = viewTransform.threeD)
    g.recompute(measurer)

    val visible = g.visibleNodes()
    val selected = g.selectedNodes
    // how each node appears from where the view looks; none for one behind the viewer or too near
    val seen = g.nodes.filter { it in visible }.mapNotNull { n -> apparent(n)?.let { n to it } }.toMap()
    return Scene(
        // what is further from the viewer first: what is nearer is drawn over it
        nodes = seen.entries.sortedBy { it.value.scale }.map { (n, how) -> sceneNode(n, n in selected, g.isFaint(n), how) },
        edges = g.edges.filter { it.src in visible && it.dst in visible }.mapNotNull { e ->
            val src = seen[e.src]
            val dst = seen[e.dst]
            when {
                src != null && dst != null -> sceneEdge(e, src, dst)
                // one end is behind the viewer or too near: the edge is drawn as far as it can be seen
                src != null -> cut(e.src, e.dst)?.let { SceneEdge(src.position, it, src.scale, 0.0) }
                dst != null -> cut(e.dst, e.src)?.let { from ->
                    val into = e.dst.cache.shape.connectionPoint((dst.position - from).toUnit(), e.dst, Constants.arrowheadRadius) * dst.scale
                    SceneEdge(from, listOf(dst.position - into, dst.position + into).minBy { (it - from).lengthSquared() }, dst.scale, dst.scale)
                }
                else -> null
            }
        },
        selectionBox = gestures.selectionBoxFrom?.let { Vector2.computeCorners(gestures.lastPosition, it) },
        shells = gestures.shells.mapNotNull { s -> apparentAt(viewTransform.toView(s.at))?.let { SceneShell(it.position, s.radius * it.scale, s.level, sun = s.sun) } },
    )
}

/** The node [id] alone, as [scene] describes it, its size brought up to date with [measurer]. Null when there is none, or it is not shown. */
fun EditorView.sceneNode(id: String, measurer: TextMeasurer): SceneNode? {
    g.recompute(measurer)
    return g.node(id)?.let { n -> apparent(n)?.let { sceneNode(n, n in g.selectedNodes, g.isFaint(n), it) } }
}

/**
 * Where the line from [shown] to [hidden], a node too near the
 * viewer to be shown or behind him, leaves what can be seen, as it
 * appears in the plane the view speaks of. Null if it cannot be told.
 */
private fun EditorView.cut(shown: Node, hidden: Node): Vector2? {
    val a = viewPoint(shown)
    val b = viewPoint(hidden)
    // a little further away than the nearest that is shown
    val nearest = viewTransform.depth - viewTransform.distance + viewTransform.distance / Constants.maxPerspective * 1.01
    val part = if (a.z > nearest && b.z < a.z) (a.z - nearest) / (a.z - b.z) else null
    return part?.takeIf { it in 0.0..1.0 }?.let { viewTransform.apparent(a.xy + (b.xy - a.xy) * it, nearest, size / 2) }
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
        ).minBy { (it.first - it.second).lengthSquared() }.let { SceneEdge(it.first, it.second, (src.scale + dst.scale) / 2, dst.scale) }
    }

private fun sceneNode(n: Node, selected: Boolean, faint: Boolean, how: Apparent): SceneNode =
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
        faint = faint,
        manual = graph_tools.ManualSize.of(n) != null,
    )
