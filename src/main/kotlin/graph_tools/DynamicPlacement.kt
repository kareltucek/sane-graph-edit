package graph_tools

import Graph
import utils.Vector2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * `:set dynamic` — nodes that become visible are moved next to the
 * nodes that were already visible, so showing a far-away neighbour
 * (`H`, unhide-mode traversal, show-parents / show-children) doesn't
 * send it to the other end of the canvas.
 *
 * Called on every paint ([place]); compares the visible set with the
 * one from the previous call. Each newly visible node goes one typical
 * edge length away from its visible neighbours, towards its original
 * position (so the old layout's orientation survives), at the nearest
 * spot where it doesn't overlap a visible node. Chains of new nodes
 * are placed breadth-first from the already-visible ones. New nodes
 * with no visible neighbour stay where they are. The moves are one
 * undoable [MoveNodesCommand]; nodes shown by undo / redo are left
 * alone.
 */
object DynamicPlacement {
    /** Minimum gap between placed boxes. */
    private const val GAP = 12.0

    /** Edge length used when the visible graph has no edges to learn from. */
    private const val DEFAULT_EDGE_LENGTH = 150.0

    /** Direction offsets tried around the preferred one, in degrees, nearest first. */
    private val ANGLES = listOf(0, 20, -20, 40, -40, 60, -60, 90, -90, 120, -120, 150, -150, 180)

    /** Distances tried, as multiples of the edge length. */
    private val DISTANCES = listOf(1.0, 1.4, 1.8, 2.4, 3.0, 4.0, 5.5, 7.5)

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
        val edgeLength = typicalEdgeLength(g, fixed)
        val neighbours = { n: Node -> g.edgeMap[n].orEmpty().map { if (it.src == n) it.dst else it.src } }

        val queue = ArrayDeque(fresh.filter { n -> neighbours(n).any { it in placed } })
        val queued = queue.toMutableSet()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val anchors = neighbours(n).filter { it in placed }
            val target = spotFor(n, anchors.map { placed.getValue(it) }, placed, edgeLength)
            placed[n] = target
            result[n] = target
            neighbours(n).filter { it in fresh && it !in queued }.forEach { queue.addLast(it); queued.add(it) }
        }
        return result
    }

    /**
     * Median length of edges between [fixed] nodes, so placed nodes
     * match the spacing of what's on screen.
     */
    private fun typicalEdgeLength(g: Graph, fixed: Set<Node>): Double {
        val lengths = g.edges
            .filter { it.src in fixed && it.dst in fixed }
            .map { (it.src.position - it.dst.position).length() }
            .filter { it > 1.0 }
            .sorted()
        return lengths.getOrNull(lengths.size / 2) ?: DEFAULT_EDGE_LENGTH
    }

    private fun spotFor(n: Node, anchors: List<Vector2>, placed: Map<Node, Vector2>, edgeLength: Double): Vector2 {
        val centre = Vector2(anchors.sumOf { it.x } / anchors.size, anchors.sumOf { it.y } / anchors.size)
        val towardsOriginal = n.position - centre
        val baseAngle = if (towardsOriginal.length() < 1.0) 0.0 else kotlin.math.atan2(towardsOriginal.y, towardsOriginal.x)
        val candidates = DISTANCES.asSequence().flatMap { d ->
            ANGLES.asSequence().map { a ->
                val angle = baseAngle + Math.toRadians(a.toDouble())
                centre + Vector2(cos(angle) * d * edgeLength, sin(angle) * d * edgeLength)
            }
        }
        return candidates.firstOrNull { spot -> placed.none { (m, p) -> overlaps(n, spot, m, p) } }
            ?: (centre + Vector2(cos(baseAngle), sin(baseAngle)) * (DISTANCES.last() * edgeLength))
    }

    private fun overlaps(a: Node, pa: Vector2, b: Node, pb: Vector2): Boolean {
        val sa = a.cache.shapeBounds
        val sb = b.cache.shapeBounds
        return abs(pa.x - pb.x) < (sa.x + sb.x) / 2 + GAP && abs(pa.y - pb.y) < (sa.y + sb.y) / 2 + GAP
    }
}
