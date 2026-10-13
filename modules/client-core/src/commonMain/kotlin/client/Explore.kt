package client

import Graph
import graph_tools.GraphTools
import graph_tools.Node
import utils.Vector2

/**
 * Explore mode (`x`, `:set explore`; `x` or Escape leaves it): walking through a graph from
 * the nodes one has selected, seeing little else.
 *
 * While it is on, the view has a set of pinned nodes
 * ([Graph.pinned]): those are shown, and their parents and children
 * are seen faint ([Graph.isFaint]); nothing else is. It starts as
 * the selection (with nothing selected, as [roots]). The
 * buttons of the mouse then mean what [KeyMapper.EXPLORE_MOUSE]
 * says, unless the user mapped them: the left one shows ([show]),
 * the right one folds ([hide]).
 *
 * The mode is the view's own: the hide levels are not touched, so
 * leaving it brings back what was seen before. What does reach the
 * document is where nodes are: those that come to be seen are placed
 * next to their neighbours ([graph_tools.DynamicPlacement]), on
 * entering the mode too: the pinned nodes stay where they are and
 * the faint ones are put next to them.
 */
object Explore {
    fun isOn(view: EditorView): Boolean = view.g.pinned != null

    /**
     * `:explore-show`, what a click of the left button does in the
     * mode: of the selected nodes those that are not pinned (a faint
     * node that was clicked) are; with all of them pinned, their
     * children are.
     */
    fun show(view: EditorView) {
        val g = view.g
        g.pinned?.let { pins ->
            val from = g.selectedNodes.toSet()
            val faint = from - pins
            g.pinned = pins + faint.ifEmpty { GraphTools.computeGeneration(g, from, true, intoHidden = true) }
        }
        view.repaint()
    }

    /**
     * `:explore-hide`, what the right button does in the mode: all
     * that is pinned below the selected pinned nodes is let go of,
     * but for what is also below a pinned node elsewhere (a part of
     * the subtree that another one shares); with nothing to let go
     * of, the selected nodes themselves are. The last pinned node
     * stays. A faint node is left as it is.
     */
    fun hide(view: EditorView) {
        val g = view.g
        g.pinned?.let { pins ->
            val from = g.selectedNodes.filter { it in pins }.toSet()
            val below = pinnedBelow(g, pins, from, through = pins - from) - from
            val held = pinnedBelow(g, pins, pins - below - from, through = below)
            val folded = (below - held).ifEmpty { from }
            if ((pins - folded).isNotEmpty()) g.pinned = pins - folded
        }
        view.repaint()
    }

    /** [from] and the pinned nodes reached from them along the edges, stepping only onto those in [through]. */
    private fun pinnedBelow(g: Graph, pins: Set<Node>, from: Set<Node>, through: Set<Node>): Set<Node> {
        val next = from.flatMap { n -> g.findOutEdges(n).map { it.dst } }.filter { it in pins && it in through && it !in from }.toSet()
        return if (next.isEmpty()) from else pinnedBelow(g, pins, from + next, through - next)
    }

    /**
     * The pinned nodes as the mark `!`: `m!` pins the selection and
     * nothing else, `m+!` pins it too, `m-!` lets go of it. The last
     * pinned node stays. Outside the mode there is nothing to mark.
     */
    fun mark(view: EditorView, change: MarkChange) {
        val g = view.g
        val selected = g.selectedNodes.toSet()
        val pins = g.pinned?.let { old ->
            when (change) {
                MarkChange.Set -> selected
                MarkChange.Add -> old + selected
                MarkChange.Remove -> old - selected
            }
        }
        when {
            pins == null -> view.shell.message("explore: the mark `!` is of explore mode (x)")
            pins.isNotEmpty() -> g.pinned = pins
        }
        view.repaint()
    }

    /** `'!`: the pinned nodes become the selection. */
    fun recall(view: EditorView) {
        view.g.pinned?.let { view.g.cleanSelect(it) }
        view.repaint()
    }

    /**
     * Where to start from when nothing is selected: of each part of
     * the graph that hangs together, the nodes that fewest edges
     * come into. Of a tree that is its root; of a cycle, all of it.
     */
    fun roots(g: Graph): Set<Node> {
        val nodes = g.nodes.filter { !it.detail().isBlank }.toSet()
        val into = nodes.associateWith { n -> g.findInEdges(n).map { it.src }.filter { it in nodes && it != n }.toSet() }
        val next = nodes.associateWith { n -> into.getValue(n) + g.findOutEdges(n).map { it.dst }.filter { it in nodes } }
        tailrec fun part(reached: Set<Node>, edge: Set<Node>): Set<Node> {
            val further = edge.flatMap { next.getValue(it) }.toSet() - reached
            return if (further.isEmpty()) reached else part(reached + further, further)
        }
        val seen = mutableSetOf<Node>()
        val parts = nodes.mapNotNull { n -> if (n in seen) null else part(setOf(n), setOf(n)).also { seen += it } }
        return parts.flatMap { part ->
            val fewest = part.minOf { into.getValue(it).size }
            part.filter { into.getValue(it).size == fewest }
        }.toSet()
    }

    /** How high a column of nodes the mode starts from is at most: about what is seen at once, on a screen or on a wall. */
    private const val COLUMN_HEIGHT = 1500.0

    /** Room between two such nodes, beyond what their neighbours need; and between two columns, for what is unfolded from them. */
    private const val BETWEEN = 40.0
    private const val UNFOLD_ROOM = 600.0

    /** What [graph_tools.DynamicPlacement] leaves between a node and the column of its neighbours, and between two of those. */
    private const val NEIGHBOUR_AWAY = 60.0
    private const val NEIGHBOUR_GAP = 12.0

    /**
     * Where the nodes the mode starts from go, when there are several:
     * not where they are in the graph, which may be all over it, but
     * together, in columns side by side, around the middle of what
     * the view shows. In a column they are in the order they were in
     * from top to bottom, each with the room its parents (left of it)
     * and children (right of it) take; a column that is full is
     * followed by the next, further right by what a few unfolded
     * levels take. One node alone stays where it is.
     */
    fun arrangement(g: Graph, pins: Set<Node>, middle: Vector2): Map<Node, Vector2> {
        // what is glued to a node goes with it
        val nodes = pins.filter { it !in g.attachmentOwners }.sortedWith(compareBy({ it.position.y }, { it.position.x }))
        return if (nodes.size < 2) emptyMap() else inColumns(g, pins, nodes, middle)
    }

    private fun inColumns(g: Graph, pins: Set<Node>, nodes: List<Node>, middle: Vector2): Map<Node, Vector2> {
        val parents = { n: Node -> g.findInEdges(n).map { it.src }.filter { it !in pins && !it.detail().isBlank }.distinct() }
        val children = { n: Node -> g.findOutEdges(n).map { it.dst }.filter { it !in pins && !it.detail().isBlank }.distinct() }
        val stacked = { of: List<Node> -> of.sumOf { it.cache.shapeBounds.y + NEIGHBOUR_GAP } }
        val widest = { of: List<Node> -> of.maxOfOrNull { it.cache.shapeBounds.x + NEIGHBOUR_AWAY } ?: 0.0 }
        val height = { n: Node -> maxOf(n.cache.shapeBounds.y, stacked(parents(n)), stacked(children(n))) + BETWEEN }
        // columns: each takes nodes until it is full
        val columns = nodes.fold(listOf(emptyList<Node>())) { done, n ->
            val last = done.last()
            if (last.isNotEmpty() && last.sumOf(height) + height(n) > COLUMN_HEIGHT) done + listOf(listOf(n)) else done.dropLast(1) + listOf(last + n)
        }
        val widths = columns.map { column ->
            column.maxOf { widest(parents(it)) } + column.maxOf { it.cache.shapeBounds.x } + column.maxOf { widest(children(it)) } + UNFOLD_ROOM
        }
        val lefts = widths.runningFold(0.0) { sum, w -> sum + w }
        val placed = columns.flatMapIndexed { i, column ->
            val x = lefts[i] + column.maxOf { widest(parents(it)) } + column.maxOf { it.cache.shapeBounds.x } / 2
            val tops = column.runningFold(0.0) { sum, n -> sum + height(n) }
            column.mapIndexed { k, n -> n to Vector2(x, tops[k] + height(n) / 2) }
        }
        val size = Vector2(lefts.last() - UNFOLD_ROOM, columns.maxOf { it.sumOf(height) })
        return placed.associate { (n, at) -> n to at - size / 2 + middle }
    }

    private fun arrange(view: EditorView, pins: Set<Node>) {
        val g = view.g
        val to = arrangement(g, pins, view.viewTransform.toWorkspace(view.size / 2))
        if (to.isNotEmpty()) {
            g.commit(graph_tools.MoveNodesCommand(g, to.keys.associateWith { it.position }, to.toMutableMap(), coalesceWindowMs = -1))
        }
    }

    fun set(view: EditorView, on: Boolean) {
        val g = view.g
        val kept = g.selectedNodes.toSet().ifEmpty { if (on && !isOn(view)) roots(g) else emptySet() }
        when {
            on == isOn(view) -> Unit
            on && kept.isEmpty() -> view.shell.message("explore: the graph has no nodes")
            on -> {
                arrange(view, kept)
                g.pinned = kept
                // all but the pinned nodes count as newly shown: the faint ones are put next to them ([graph_tools.DynamicPlacement])
                g.dynamicBaseline = kept
            }
            else -> {
                g.pinned = null
                // or all that comes back would count as newly shown, and be moved ([graph_tools.DynamicPlacement])
                g.dynamicBaseline = null
            }
        }
        view.shell.modesChanged()
        view.repaint()
    }
}
