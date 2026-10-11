package client

import graph_tools.GraphTools
import graph_tools.HideCommand
import graph_tools.RevealCommand
import graph_tools.ViewModes

/**
 * `:set explore`: walking through a graph from the nodes one has
 * selected, seeing little else.
 *
 * Turning it on hides every node that is not selected (one undo
 * step; with nothing selected, all but the last active node) and
 * turns [ViewModes.showParents] and [ViewModes.showChildren] on, so
 * that the parents and children of what is shown are seen, faint
 * ([graph_tools.Graph.isFaint]). The buttons of the mouse then mean
 * what [KeyMapper.EXPLORE_MOUSE] says, unless the user mapped them:
 * the left one selects, as always, which shows the neighbours of
 * the node clicked for as long as it is selected; the right one
 * unfolds or folds it for good ([toggle]).
 *
 * Turning it off puts the two show modes back as they were. What is
 * hidden stays hidden: undo, or `zR`.
 */
object Explore {
    /** [ViewModes.showParents] and [ViewModes.showChildren] as they were when the mode was turned on. */
    private var before: Pair<Boolean, Boolean>? = null

    /**
     * `:explore-toggle`, what the right button does in the mode:
     * unfold the selected nodes, or fold them. Unfolding shows their
     * hidden children, and themselves if they are hidden (a faint
     * node that was clicked); with nothing of that hidden, the
     * children are hidden instead. One undo step.
     */
    fun toggle(view: EditorView) {
        val g = view.g
        val from = g.selectedNodes.toSet()
        val children = GraphTools.computeGeneration(g, from, true, intoHidden = true) - from
        val hidden = (from + children).filter { it.isHidden }
        val shown = children.filter { !it.isHidden }
        when {
            hidden.isNotEmpty() -> g.commit(RevealCommand(hidden.associateWith { it.cache.hideLevel }))
            shown.isNotEmpty() -> g.commit(HideCommand(shown))
        }
        view.repaint()
    }

    fun set(view: EditorView, on: Boolean) {
        val g = view.g
        val kept = g.selectedNodes.toSet().ifEmpty { setOfNotNull(g.lastActiveNode?.takeIf { it in g.nodes }) }
        when {
            on == ViewModes.explore -> Unit
            on && kept.isEmpty() -> view.shell.message("explore: no node is selected")
            on -> {
                val rest = g.nodes.filter { !it.isHidden && it !in kept }
                if (rest.isNotEmpty()) g.commit(HideCommand(rest))
                before = ViewModes.showParents to ViewModes.showChildren
                ViewModes.showParents = true
                ViewModes.showChildren = true
                ViewModes.explore = true
            }
            else -> {
                before?.let { (parents, children) ->
                    ViewModes.showParents = parents
                    ViewModes.showChildren = children
                }
                before = null
                ViewModes.explore = false
            }
        }
        view.repaint()
    }
}
