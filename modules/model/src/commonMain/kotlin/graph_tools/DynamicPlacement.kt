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
     * In explore mode what is put beside a node is stacked no higher
     * than this: about what is seen at once, on a screen or on a wall
     * (a unit is a millimetre there). And in no more columns than these.
     */
    const val EXPLORE_HEIGHT = 1500.0
    private const val MAX_COLUMNS = 50

    /**
     * Place the nodes that became visible since the last call. Tracks
     * nothing while the mode is off, so turning it on doesn't move
     * what's already on screen.
     */
    fun place(g: Graph) {
        // explore mode ([Graph.pinned]) places what it shows, whatever the setting
        val visible = if (ViewModes.dynamic || g.pinned != null) g.visibleNodes() else null
        val before = g.dynamicBaseline
        g.dynamicBaseline = visible
        // explore mode moves nodes for as long as it is on only ([Graph.beforeExplore]): no command, nothing to undo
        val exploring = g.pinned != null
        // While undoing / redoing (redo stack non-empty), a new command would wipe the redo history.
        val appeared = if (visible != null && before != null && (exploring || !g.history.canRedo)) visible - before else emptySet()
        // attached nodes follow their owner (Attachments.reglue), they aren't placed themselves
        val fresh = appeared.filter { it !in g.attachmentOwners }.toSet()
        if (fresh.isNotEmpty()) {
            val fixed = visible!! - appeared
            // explore mode: a faint node is not in the way of a new one, it gets out of it ([settled])
            val yielding = if (g.pinned != null) fixed.filter { g.isFaint(it) }.toSet() else emptySet()
            val placed = positionsFor(g, fresh, fixed, yielding, tall = EXPLORE_HEIGHT.takeIf { exploring })
            val targets = if (g.pinned != null) settled(g, placed, visible) else placed
            if (exploring) {
                targets.forEach { (n, p) ->
                    n.position = p
                    g.needsRecomputing(n)
                }
            } else if (targets.isNotEmpty()) {
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

    /**
     * Explore mode: where the nodes [placed] and the faint ones in
     * their way come to rest ([Settling]), among the [visible] ones.
     * The pinned nodes that were seen before stay. Nothing is moved
     * here: the nodes are back where they were when this returns.
     */
    private fun settled(g: Graph, placed: Map<Node, Vector2>, visible: Set<Node>): Map<Node, Vector2> {
        val among = visible.filter { it !in g.attachmentOwners }.toSet()
        val before = among.associateWith { it.position }
        placed.forEach { (n, p) -> n.position = p }
        val moved = Settling.settle(g, placed.keys, among) { g.isFaint(it) }
        val after = (placed.keys + moved).associateWith { it.position }
        before.forEach { (n, p) -> n.position = p }
        Attachments.reglue(g)
        return after
    }

    /**
     * New positions for [fresh] nodes, given the [fixed] visible ones; those of them that are [yielding] may be overlapped.
     * With [tall] the nodes put beside one node are stacked no higher than that, in as many columns as it takes.
     */
    fun positionsFor(g: Graph, fresh: Set<Node>, fixed: Set<Node>, yielding: Set<Node> = emptySet(), tall: Double? = null): Map<Node, Vector2> {
        // attached nodes first: a node's box is its whole block (it + what's glued to it), measured
        // relative to it, so the fanning out below starts from the block's edges
        Attachments.reglue(g)
        val boxes = (fresh + fixed).associateWith { Attachments.block(g, it) }
        val placed = fixed.associateWith { it.position }.toMutableMap()
        val result = mutableMapOf<Node, Vector2>()
        val parents = { n: Node -> g.findInEdges(n).map { it.src } }
        val children = { n: Node -> g.findOutEdges(n).map { it.dst } }
        val topToBottom = compareBy<Node>({ it.position.y }, { it.position.x })

        var layer = fresh.filter { n -> (parents(n) + children(n)).any { it in placed } }.sortedWith(topToBottom)
        while (layer.isNotEmpty()) {
            layer.forEach { n ->
                val target = spotFor(n, parents(n).filter { it in placed }, children(n).filter { it in placed }, placed, boxes, yielding, tall)
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

    private fun spotFor(
        n: Node,
        visibleParents: List<Node>,
        visibleChildren: List<Node>,
        placed: Map<Node, Vector2>,
        boxes: Map<Node, Attachments.Box>,
        yielding: Set<Node>,
        tall: Double?,
    ): Vector2 {
        val box = { m: Node -> boxes.getValue(m) }
        val pos = { m: Node -> placed.getValue(m) }
        val left = { m: Node, at: Vector2 -> at.x + box(m).center.x - box(m).size.x / 2 }
        val right = { m: Node, at: Vector2 -> at.x + box(m).center.x + box(m).size.x / 2 }
        // n's block right of the parents' blocks / left of the children's blocks
        val rightOfParents = visibleParents.maxOfOrNull { right(it, pos(it)) }
            ?.let { it + COLUMN_GAP - left(n, Vector2.Zero) }
        val leftOfChildren = visibleChildren.minOfOrNull { left(it, pos(it)) }
            ?.let { it - COLUMN_GAP - right(n, Vector2.Zero) }
        val x = when {
            rightOfParents != null && leftOfChildren != null -> (rightOfParents + leftOfChildren) / 2
            else -> rightOfParents ?: leftOfChildren!!
        }
        val anchors = visibleParents + visibleChildren
        val y = anchors.sumOf { pos(it).y } / anchors.size
        val step = box(n).size.y + GAP
        // a stack no higher than [tall]: what does not fit in it goes on in a column further from the neighbours
        val most = if (tall == null) MAX_ROWS else (tall / 2 / step).toInt().coerceIn(1, MAX_ROWS)
        val rows = (0..most).asSequence().flatMap { k -> if (k == 0) sequenceOf(0) else sequenceOf(k, -k) }
        val away = if (rightOfParents != null) 1 else -1
        val columns = if (tall == null) sequenceOf(0) else (0..MAX_COLUMNS).asSequence()
        return columns
            .flatMap { column -> rows.map { k -> Vector2(x + away * column * (box(n).size.x + COLUMN_GAP), y + k * step) } }
            .firstOrNull { spot -> placed.none { (m, p) -> m !in yielding && overlaps(box(n), spot, box(m), p) } }
            ?: Vector2(x, y)
    }

    private fun overlaps(a: Attachments.Box, pa: Vector2, b: Attachments.Box, pb: Vector2): Boolean {
        val ca = pa + a.center
        val cb = pb + b.center
        return abs(ca.x - cb.x) < (a.size.x + b.size.x) / 2 + GAP && abs(ca.y - cb.y) < (a.size.y + b.size.y) / 2 + GAP
    }
}
