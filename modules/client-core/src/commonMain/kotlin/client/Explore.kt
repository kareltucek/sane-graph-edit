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
 * the left one shows ([show]: a faint node, or the children of one
 * that is shown already), the right one hides ([hide]).
 *
 * Turning it off puts the two show modes back as they were. What is
 * hidden stays hidden: undo, or `zR`.
 */
object Explore {
    /** [ViewModes.showParents] and [ViewModes.showChildren] as they were when the mode was turned on. */
    private var before: Pair<Boolean, Boolean>? = null

    /**
     * `:explore-show`, what a click of the left button does in the
     * mode: of the selected nodes those that are hidden (a faint
     * node that was clicked) are shown; with none hidden, their
     * hidden children are. One undo step.
     */
    fun show(view: EditorView) {
        val g = view.g
        val from = g.selectedNodes.toSet()
        val own = from.filter { it.isHidden }
        val hidden = own.ifEmpty { (GraphTools.computeGeneration(g, from, true, intoHidden = true) - from).filter { it.isHidden } }
        if (hidden.isNotEmpty()) g.commit(RevealCommand(hidden.associateWith { it.cache.hideLevel }))
        view.repaint()
    }

    /**
     * `:explore-hide`, what the right button does in the mode: the
     * children of the selected nodes that are shown are hidden (one
     * generation: what is below them stays as it is); with none
     * shown, the selected nodes themselves are, and are no longer
     * selected. One undo step.
     */
    fun hide(view: EditorView) {
        val g = view.g
        val from = g.selectedNodes.filter { !it.isHidden }.toSet()
        val below = (GraphTools.computeGeneration(g, from, true, intoHidden = true) - from).filter { !it.isHidden }
        when {
            below.isNotEmpty() -> g.commit(HideCommand(below))
            from.isNotEmpty() -> {
                g.commit(HideCommand(from.toList()))
                g.unselectAll(from)
            }
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
