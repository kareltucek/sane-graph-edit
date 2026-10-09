package client

import graph_tools.GraphTools
import graph_tools.HideCommand
import graph_tools.Node
import graph_tools.RevealCommand
import utils.Vector2

/**
 * The `z` commands: moving the view to the nodes one is working
 * on, and folding — hiding along the edges.
 *
 * They act on the **target**: the selection, or the last active
 * node when nothing is selected. Without one a command does
 * nothing and says so.
 */
object ZCommands {
    /** Left between the target and the edge of the window it is moved to, in pixels. */
    const val EDGE_MARGIN = 40.0

    private fun target(view: EditorView, command: String): Set<Node> {
        val g = view.g
        val nodes = g.selectedNodes.takeIf { it.isNotEmpty() }?.toSet() ?: setOfNotNull(g.lastActiveNode?.takeIf { it in g.nodes })
        if (nodes.isEmpty()) view.shell.message("$command: no node is selected")
        return nodes
    }

    // --- view: only where the view looks changes; the zoom, the selection, the undo history and the jump list stay ---

    enum class Edge { Top, Bottom, Left, Right }

    /**
     * Move the view along one axis so that the target is at [edge]
     * of the window, [EDGE_MARGIN] from it. A target larger than
     * the window ends up with that edge of its own there.
     */
    fun viewToEdge(view: EditorView, edge: Edge, command: String) {
        GraphTools.computeBoundingBox(target(view, command))?.let { box ->
            val t = view.viewTransform
            val ul = t.toScreen(box.ul)
            val br = t.toScreen(box.br)
            val move = when (edge) {
                Edge.Top -> Vector2(0.0, EDGE_MARGIN - ul.y)
                Edge.Bottom -> Vector2(0.0, view.size.y - EDGE_MARGIN - br.y)
                Edge.Left -> Vector2(EDGE_MARGIN - ul.x, 0.0)
                Edge.Right -> Vector2(view.size.x - EDGE_MARGIN - br.x, 0.0)
            }
            // translate takes workspace units
            t.translate(move / t.scale)
            view.repaint()
        }
    }

    // --- folding ---

    enum class Fold { Fold, Unfold, Toggle }

    /** What a motion is: one of the commands that extend the selection along the edges. */
    private data class Motion(val forward: Boolean, val all: Boolean)

    private val motions = mapOf(
        "select-linked-forward" to Motion(forward = true, all = false),
        "select-linked-backward" to Motion(forward = false, all = false),
        "select-closure-forward" to Motion(forward = true, all = true),
        "select-closure-backward" to Motion(forward = false, all = true),
    )

    fun isMotion(command: String): Boolean = command in motions

    /**
     * Hide, unhide or toggle the nodes the motion [motionCommand]
     * reaches from the target: those it would add to the selection,
     * hidden ones included. The target and whatever is selected stay
     * visible. One undo step.
     *
     * [Fold.Fold] hides the reached nodes that are visible, as one
     * hide level. [Fold.Unfold] shows the hidden ones.
     * [Fold.Toggle] unfolds if the motion reaches a hidden node and
     * folds otherwise.
     */
    fun fold(view: EditorView, how: Fold, motionCommand: String, command: String) {
        val motion = motions[motionCommand]
        val from = if (motion != null) target(view, command) else emptySet()
        when {
            motion == null -> view.shell.message("$command: `$motionCommand` is not a motion (t, T, l, L)")
            from.isNotEmpty() -> {
                val g = view.g
                val along = if (motion.all) GraphTools.computeClosure(g, from, motion.forward, intoHidden = true) else GraphTools.computeGeneration(g, from, motion.forward, intoHidden = true)
                val reached = along - from - g.selectedNodes
                val hidden = reached.filter { it.isHidden }
                val unfold = how == Fold.Unfold || (how == Fold.Toggle && hidden.isNotEmpty())
                if (unfold && hidden.isNotEmpty()) g.commit(RevealCommand(hidden.associateWith { it.cache.hideLevel }))
                val visible = reached.filter { !it.isHidden }
                if (!unfold && visible.isNotEmpty()) g.commit(HideCommand(visible))
                view.repaint()
            }
        }
    }

    /** Show every hidden node, whatever its level. One undo step. */
    fun unhideAll(view: EditorView) {
        val hidden = view.g.nodes.filter { it.isHidden }
        if (hidden.isNotEmpty()) {
            view.g.commit(RevealCommand(hidden.associateWith { it.cache.hideLevel }))
            view.repaint()
        }
    }
}
