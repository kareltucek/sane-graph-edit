package ui

import graph_tools.GraphTools.computeGeneration
import graph_tools.AddEdgesCommand
import graph_tools.AddNodeCommand
import graph_tools.CompositeCommand
import graph_tools.Command
import graph_tools.Edge
import graph_tools.GraphTools
import graph_tools.GraphTools.computeClosure
import graph_tools.LayoutOptimizer
import graph_tools.MoveNodesCommand
import graph_tools.Node
import graph_tools.RemoveEdgesCommand
import graph_tools.RemoveNodesCommand
import graph_tools.StyleNodesCommand
import utils.Vector2
import ui.GraphKeyListener.impl.selectAll
import ui.GraphKeyListener.impl.editNode
import ui.GraphKeyListener.impl.executeCommand
import ui.GraphKeyListener.impl.unselectAll
import utils.Utils.orElse
import utils.Utils.toUnit
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.awt.event.KeyListener

/**
 * Top-level keyboard handler for the canvas.
 *
 * Two dispatch paths:
 *
 *   - `keyPressed` handles modifier-driven shortcuts (Ctrl+S save,
 *     Ctrl+O open, Ctrl+A select all, Escape deselect, Space edit).
 *     These need the raw key code because the char form drops the
 *     modifier.
 *
 *   - `keyTyped` forwards plain character keys to
 *     [impl.executeCommand], a big single-character dispatch table for
 *     the editor's text-editor-style commands (`e` for edge, `v` for
 *     new+edge, `d` for delete, `o` for layout, etc.).
 *
 * Full command reference is at `Constants.helpCommands` and in
 * `docs/developer/architecture.md`.
 */
class GraphKeyListener(
    val graphView: GraphView,
) : KeyListener {
    override fun keyTyped(e: KeyEvent) {
        val used = executeCommand(e.keyChar.toString(), graphView)
        if (used) {
            e.consume()
        }
    }

    override fun keyPressed(e: KeyEvent) {
        when {
            // Undo / redo. Ctrl+Z undoes, Ctrl+Shift+Z (and Ctrl+Y) redoes.
            // Both are standard; supporting both keeps muscle memory happy
            // across editors.
            e.keyCode == VK_Z && e.isControlDown && !e.isShiftDown -> {
                graphView.g.history.undo()
                graphView.g.needsRecomputing(graphView.g.nodes)
                graphView.repaint()
            }

            e.keyCode == VK_Z && e.isControlDown && e.isShiftDown -> {
                graphView.g.history.redo()
                graphView.g.needsRecomputing(graphView.g.nodes)
                graphView.repaint()
            }

            e.keyCode == VK_Y && e.isControlDown -> {
                graphView.g.history.redo()
                graphView.g.needsRecomputing(graphView.g.nodes)
                graphView.repaint()
            }

            e.keyCode == VK_S && e.isControlDown -> {
                graphView.saveFile()
            }

            e.keyCode == VK_O && e.isControlDown -> {
                graphView.loadFile()
                graphView.repaint()
            }

            e.keyCode == VK_A && e.isControlDown -> {
                selectAll(graphView)
            }

            e.keyCode == VK_ESCAPE -> {
                unselectAll(graphView)
            }

            e.keyCode == VK_SPACE -> {
                editNode(graphView, e.isShiftDown)
            }
        }
        e.consume()
    }


    override fun keyReleased(e: KeyEvent) {}

    object impl {
        fun executeCommand(oneCommand: String, graphView: GraphView): Boolean {
            val used: Unit? = when (oneCommand) {
                "e" -> drawEdge(graphView, true)
                "E" -> drawEdge(graphView, false)
                "v" -> drawNodeWithEdge(graphView, forwardEdge = true, append = false)
                "V" -> drawNodeWithEdge(graphView, forwardEdge = false, append = false)
                "a" -> drawNodeWithEdge(graphView, forwardEdge = true, append = true)
                "A" -> drawNodeWithEdge(graphView, forwardEdge = false, append = true)
                "c" -> clearEdges(graphView)
                "d" -> deleteNode(graphView, false)
                "D" -> deleteNode(graphView, true)
                "o" -> optimize(graphView, false)
                "O" -> optimize(graphView, true)
                "t" -> selectClosure(graphView, true)
                "T" -> selectClosure(graphView, false)
                "l" -> selectLinked(graphView, true)
                "L" -> selectLinked(graphView, false)
                "w" -> unselectOldestGen(graphView, true)
                "W" -> unselectOldestGen(graphView, false)
                "0" -> boundScreen(graphView)
                "1" -> centerScreen(graphView)
                "f" -> pasteFormat(graphView)
                "F" -> copyFormat(graphView)
                "g" -> grab(graphView)
                "G" -> executeMacro(graphView, "tw0").toUnit()
//                "G" -> executeMacro(graphView, "TW0").toUnit()
                else -> null
            }
            return used != null
        }

        fun executeMacro(graphView: GraphView, commandSequence: String): Boolean {
            return commandSequence
                .chunked(1)
                .map { executeCommand(it, graphView) }
                .fold(true) { a, b -> a && b }
        }


        fun copyFormat(graphView: GraphView) {
            val target = graphView.g.selectedNodes.firstOrNull()

            graphView.defaultNodeStyle = target?.let { n ->
                NodeStyle.fromNode(n)
            }.orElse(NodeStyle())
        }

        fun pasteFormat(graphView: GraphView) {
            val targets = graphView.g.selectedNodes.toList()
            if (targets.isEmpty()) return
            val cmd = StyleNodesCommand.toUniform(graphView.g, targets, graphView.defaultNodeStyle)
            graphView.g.commit(cmd)
            graphView.repaint()
        }

        fun grab(graphView: GraphView) {
            graphView.mouseListener.controller.startOrEndMove()
        }

        fun unselectAll(graphView: GraphView) {
            graphView.g.cleanSelect(emptySet())
            graphView.repaint()
        }

        fun centerScreen(graphView: GraphView) {
            graphView.centerScreen()
        }

        fun boundScreen(graphView: GraphView) {
            graphView.boundScreen()
        }

        fun selectClosure(graphView: GraphView, forward: Boolean) {
            val nextGen = computeClosure(graphView.g, graphView.g.selectedNodes, forward)
            graphView.g.selectAll(nextGen)
            graphView.repaint()
        }

        fun selectLinked(graphView: GraphView, forward: Boolean) {
            val nextGen = computeGeneration(graphView.g, graphView.g.selectedNodes, forward)
            graphView.g.selectAll(nextGen)
            graphView.repaint()
        }

        fun unselectOldestGen(graphView: GraphView, forward: Boolean) {
            val oldestGen = graphView.g.selectedNodes
                .filter {
                    if (forward) {
                        graphView.g.findEddges(graphView.g.selectedNodes, setOf(it)).isEmpty()
                    } else {
                        graphView.g.findEddges(setOf(it), graphView.g.selectedNodes).isEmpty()
                    }
                }
            graphView.g.unselectAll(oldestGen)
            graphView.repaint()
        }

        fun optimize(graphView: GraphView, restrict: Boolean) {
            // Capture positions before optimising, then build a
            // MoveNodesCommand so the user can undo a layout pass with
            // Ctrl+Z. We snapshot all nodes because the optimiser may
            // touch any of them; unchanged nodes cost nothing to record.
            val before = graphView.g.nodes.associateWith { it.position }
            LayoutOptimizer.optimize(graphView.g, movingNodes = false, restrictOperator = restrict)
            val after = graphView.g.nodes.associateWith { it.position }.toMutableMap()
            if (before != after) {
                graphView.g.history.commitWithoutRun(MoveNodesCommand(graphView.g, before, after))
            }
            graphView.repaint()
        }

        fun selectAll(graphView: GraphView) {
            if (graphView.g.selectedNodes.size == graphView.g.nodes.size) {
                graphView.g.cleanSelect(setOf())
            } else {
                graphView.g.cleanSelect(graphView.g.nodes)
            }
            graphView.repaint()
        }

        fun editNode(graphView: GraphView, select: Boolean) {
            graphView.g.lastActiveNode?.let { n ->
                if (select) {
                    graphView.g.cleanSelect(setOf(n))
                }
                graphView.startNodeEdit(n, null)
            }
        }

        fun deleteNode(graphView: GraphView, reconnectNodes: Boolean) {
            val g = graphView.g
            val sel = g.selectedNodes.toList()
            if (sel.isEmpty()) return

            // Collect the reconnection edges first, before we compute the
            // edges to remove — we want the new bridges in the graph
            // *after* the originals are gone, but we need to derive them
            // from the structure *before* deletion. Building them now and
            // letting the composite command re-play the add after the
            // removes is the cleanest order.
            val reconnects: List<Edge> =
                if (reconnectNodes) {
                    sel.flatMap { n ->
                        val ins = g.findInEdges(n).map { it.src }.toSet()
                        val outs = g.findOutEdges(n).map { it.dst }.toSet()
                        ins.flatMap { src -> outs.map { dst -> src to dst } }
                    }
                        .distinct()
                        // Don't bridge through deleted nodes.
                        .filter { it.first !in sel && it.second !in sel }
                        .map { Edge(it.first, it.second) }
                } else {
                    emptyList()
                }

            val incidentEdges = g.edges
                .filter { sel.contains(it.src) || sel.contains(it.dst) }
                .toList()

            g.unselectAll(sel)

            val children = buildList<Command> {
                add(RemoveEdgesCommand(g, incidentEdges))
                add(RemoveNodesCommand(g, sel))
                if (reconnects.isNotEmpty()) {
                    add(AddEdgesCommand(g, reconnects))
                }
            }
            g.commit(CompositeCommand(children))

            graphView.repaint()
        }


        fun clearEdges(graphView: GraphView) {
            val g = graphView.g
            val sel = g.selectedNodes

            val edgesInComponent = g.findEddges(sel, sel)

            val victims: List<Edge> =
                if (edgesInComponent.isNotEmpty()) {
                    edgesInComponent.toList()
                } else {
                    sel.flatMap { n ->
                        g.edges.filter { it.src == n || it.dst == n }
                    }.distinct()
                }

            if (victims.isNotEmpty()) {
                g.commit(RemoveEdgesCommand(g, victims))
            }

            graphView.repaint()
        }

        fun drawNodeWithEdge(graphView: GraphView, forwardEdge: Boolean, append: Boolean) {
            val g = graphView.g
            val pos = graphView.lastCursorPosition

            val from = if (append) {
                g.lastActiveNode
                    ?.let { listOf(it) }
                    .orElse(g.selectedNodes)
            } else {
                g.selectedNodes
            }

            val style = from
                .takeIf { it.size == 1 }
                ?.let { GraphTools.pickNodeStyle(g, it.first()) }
                .orElse(graphView.defaultNodeStyle)

            val newNode = Node(
                label = "New node",
                pos = pos,
                style = style,
            )

            val newEdges = from.map { selectedNode ->
                if (forwardEdge) Edge(selectedNode, newNode) else Edge(newNode, selectedNode)
            }

            val children = buildList<Command> {
                add(AddNodeCommand(g, newNode))
                if (newEdges.isNotEmpty()) {
                    add(AddEdgesCommand(g, newEdges))
                }
            }
            g.commit(CompositeCommand(children))

            graphView.repaint()
        }

        fun drawEdge(graphView: GraphView, forward: Boolean) {
            val g = graphView.g
            val pos = graphView.lastCursorPosition
            val pointed = Clicker.selectClickedNode(g, pos)
            val selected = g.selectedNodes - pointed

            val (src, dst) = if (forward) selected to pointed else pointed to selected

            val existingEdges = g.findEddges(src, dst)
            val representedSrcs = existingEdges.map { it.src }.toSet()
            val representedDsts = existingEdges.map { it.dst }.toSet()

            // Branch 1: all endpoints already covered → user is toggling
            // the edges off. Branch 2: at least one pair is missing →
            // add those, leave existing ones alone.
            if (representedDsts.size == dst.size && representedSrcs.size == src.size) {
                if (existingEdges.isNotEmpty()) {
                    g.commit(RemoveEdgesCommand(g, existingEdges))
                }
            } else {
                val existingConnections = existingEdges.associateBy { it.src to it.dst }
                val missingEdges = src.flatMap { s ->
                    dst.map { d ->
                        Edge(src = s, dst = d)
                    }
                }
                    .filter { !existingConnections.containsKey(it.src to it.dst) }
                    .filter { it.src != it.dst }

                if (missingEdges.isNotEmpty()) {
                    g.commit(AddEdgesCommand(g, missingEdges))
                }
            }

            graphView.repaint()
        }
    }
}