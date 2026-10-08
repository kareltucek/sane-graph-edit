package ui

import client.*
import graph_tools.GraphTools.computeGeneration
import graph_tools.AddEdgesCommand
import graph_tools.AddNodeCommand
import graph_tools.AddNodesCommand
import graph_tools.CompositeCommand
import graph_tools.Command
import graph_tools.Edge
import graph_tools.GraphTools
import graph_tools.GraphTools.computeClosure
import graph_tools.HideCommand
import graph_tools.NodeStyle
import graph_tools.LayoutOptimizer
import graph_tools.MoveNodesCommand
import graph_tools.Node
import graph_tools.RemoveEdgesCommand
import graph_tools.RemoveNodesCommand
import graph_tools.RevealCommand
import graph_tools.StyleNodesCommand
import graph_tools.UnhideCommand
import graph_tools.ViewModes
import utils.Vector2
// Stale impl imports removed — dispatch now goes through KeyMapper + CommandRegistry
import utils.Utils.orElse
import utils.Utils.toUnit
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.awt.event.KeyListener

/**
 * Top-level keyboard handler for the canvas.
 *
 * Delegates to the shared [KeyMapper] for all dispatch. Two AWT
 * entry points feed key notations into the mapper:
 *
 *   - `keyPressed` handles modifier combos (Ctrl+S → `<C-s>`) and
 *     special keys (Escape → `<Esc>`, Space → `<Space>`).
 *   - `keyTyped` handles plain characters (`e`, `E`, `v`, etc.).
 *
 * The mapper resolves each notation against user mappings first,
 * then built-in defaults, and calls [CommandRegistry.execute] to
 * run the action.
 *
 * The `impl` object below holds the command implementations — pure
 * functions that take a [GraphView] and mutate the graph. They're
 * registered into [CommandRegistry] at startup and are not called
 * directly from the key dispatch anymore.
 */
class GraphKeyListener(
    val graphView: GraphView,
) : KeyListener {

    override fun keyTyped(e: KeyEvent) {
        // Ctrl combos produce control chars (0x01 for Ctrl+A, etc.)
        // that were already handled in keyPressed. Skip them.
        val notation = KeyNotation.fromKeyTyped(e.keyChar) ?: return

        // ':' opens the command bar instead of feeding into the mapper
        if (notation == ":") {
            graphView.commandBar?.open(":")
            e.consume()
            return
        }
        // '/' and '?' open the command bar in search mode
        if (notation == "/" || notation == "?") {
            graphView.commandBar?.open(notation)
            e.consume()
            return
        }

        val mapper = graphView.keyMapper ?: return
        mapper.activeView = graphView.model
        mapper.feedKey(notation)
        e.consume()
    }

    override fun keyPressed(e: KeyEvent) {
        if (e.keyCode in modifierKeys) graphView.keyMapper?.modifierPressed()
        val notation = AwtKeys.fromKeyPressed(e) ?: return

        val mapper = graphView.keyMapper ?: return
        mapper.activeView = graphView.model
        mapper.feedKey(notation)
        e.consume()
    }

    override fun keyReleased(e: KeyEvent) {}

    private val modifierKeys = setOf(VK_SHIFT, VK_CONTROL, VK_ALT, VK_META, VK_ALT_GRAPH)

}