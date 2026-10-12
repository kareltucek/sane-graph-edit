package client

import Graph
import graph_tools.GraphTools
import graph_tools.Node

/**
 * Explore mode (`x`, `:set explore`): walking through a graph from
 * the nodes one has selected, seeing little else.
 *
 * While it is on, the view has a set of pinned nodes
 * ([Graph.pinned]): those are shown, and their parents and children
 * are seen faint ([Graph.isFaint]); nothing else is. It starts as
 * the selection (with nothing selected, the last active node). The
 * buttons of the mouse then mean what [KeyMapper.EXPLORE_MOUSE]
 * says, unless the user mapped them: the left one shows ([show]),
 * the right one folds ([hide]).
 *
 * The mode is the view's own: the hide levels are not touched, so
 * leaving it brings back what was seen before. What does reach the
 * document is where nodes are: those that come to be seen are placed
 * next to their neighbours ([graph_tools.DynamicPlacement]).
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

    fun set(view: EditorView, on: Boolean) {
        val g = view.g
        val kept = g.selectedNodes.toSet().ifEmpty { setOfNotNull(g.lastActiveNode?.takeIf { it in g.nodes }) }
        when {
            on == isOn(view) -> Unit
            on && kept.isEmpty() -> view.shell.message("explore: no node is selected")
            on -> g.pinned = kept
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
