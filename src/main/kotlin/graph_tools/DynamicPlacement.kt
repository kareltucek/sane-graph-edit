package graph_tools

import Graph
import utils.Vector2
import kotlin.math.abs

/**
 * `:set dynamic` — nodes that become visible are moved next to the
 * nodes that were already visible, so showing a far-away neighbour
 * (`H`, unhide-mode traversal, show-parents / show-children) doesn't
 * send it to the other end of the canvas.
 *
 * Called on every paint ([place]); compares the visible set with the
 * one from the previous call. Placement follows the flow, left to
 * right: a newly visible child goes in a column right of its visible
 * parents, a newly visible parent left of its visible children (a node
 * that is both goes between them). Vertically it starts level with its
 * visible neighbours and stacks below / above, alternating, until it
 * overlaps nothing visible. Chains of new nodes are placed
 * breadth-first from the already-visible ones; siblings keep their
 * original top-to-bottom order. New nodes with no visible neighbour
 * stay where they are. The moves are one undoable [MoveNodesCommand];
 * nodes shown by undo / redo are left alone.
 */
object DynamicPlacement {
    /** Horizontal gap between a node and the column of its parents / children. */
    private const val COLUMN_GAP = 60.0

    /** Minimum gap between placed boxes. */
    private const val GAP = 12.0

    /** How many rows up and down to try before giving up and overlapping. */
    private const val MAX_ROWS = 500

    /**
     * Place the nodes that became visible since the last call. Tracks
     * nothing while the mode is off, so turning it on doesn't move
     * what's already on screen.
     */
    fun place(g: Graph) {
        val visible = if (ViewModes.dynamic) g.visibleNodes() else null
        val before = g.dynamicBaseline
        g.dynamicBaseline = visible
        // While undoing / redoing (redo stack non-empty), a new command would wipe the redo history.
        val fresh = if (visible != null && before != null && !g.history.canRedo) visible - before else emptySet()
        if (fresh.isNotEmpty()) {
            val targets = positionsFor(g, fresh, fixed = visible!! - fresh)
            if (targets.isNotEmpty()) {
                g.commit(
                    MoveNodesCommand(
                        g,
                        targets.keys.associateWith { it.position },
                        targets.toMutableMap(),
                        coalesceWindowMs = -1,  // never merge into the user's own moves
                    )
                )
            }
        }
    }

    /** New positions for [fresh] nodes, given the [fixed] visible ones. */
    fun positionsFor(g: Graph, fresh: Set<Node>, fixed: Set<Node>): Map<Node, Vector2> {
        val placed = fixed.associateWith { it.position }.toMutableMap()
        val result = mutableMapOf<Node, Vector2>()
        val parents = { n: Node -> g.findInEdges(n).map { it.src } }
        val children = { n: Node -> g.findOutEdges(n).map { it.dst } }
        val topToBottom = compareBy<Node>({ it.position.y }, { it.position.x })

        var layer = fresh.filter { n -> (parents(n) + children(n)).any { it in placed } }.sortedWith(topToBottom)
        while (layer.isNotEmpty()) {
            layer.forEach { n ->
                val target = spotFor(n, parents(n).filter { it in placed }, children(n).filter { it in placed }, placed)
                placed[n] = target
                result[n] = target
            }
            layer = layer
                .flatMap { parents(it) + children(it) }
                .filter { it in fresh && it !in placed }
                .distinct()
                .sortedWith(topToBottom)
        }
        return result
    }

    private fun spotFor(n: Node, visibleParents: List<Node>, visibleChildren: List<Node>, placed: Map<Node, Vector2>): Vector2 {
        val half = { m: Node -> m.cache.shapeBounds.x / 2 }
        val pos = { m: Node -> placed.getValue(m) }
        // right of the parents' column / left of the children's column
        val rightOfParents = visibleParents.maxOfOrNull { pos(it).x + half(it) }?.let { it + COLUMN_GAP + half(n) }
        val leftOfChildren = visibleChildren.minOfOrNull { pos(it).x - half(it) }?.let { it - COLUMN_GAP - half(n) }
        val x = when {
            rightOfParents != null && leftOfChildren != null -> (rightOfParents + leftOfChildren) / 2
            else -> rightOfParents ?: leftOfChildren!!
        }
        val anchors = visibleParents + visibleChildren
        val y = anchors.sumOf { pos(it).y } / anchors.size
        val step = n.cache.shapeBounds.y + GAP
        val rows = (0..MAX_ROWS).asSequence().flatMap { k -> if (k == 0) sequenceOf(0) else sequenceOf(k, -k) }
        return rows
            .map { k -> Vector2(x, y + k * step) }
            .firstOrNull { spot -> placed.none { (m, p) -> overlaps(n, spot, m, p) } }
            ?: Vector2(x, y)
    }

    private fun overlaps(a: Node, pa: Vector2, b: Node, pb: Vector2): Boolean {
        val sa = a.cache.shapeBounds
        val sb = b.cache.shapeBounds
        return abs(pa.x - pb.x) < (sa.x + sb.x) / 2 + GAP && abs(pa.y - pb.y) < (sa.y + sb.y) / 2 + GAP
    }
}
