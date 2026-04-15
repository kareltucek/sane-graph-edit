package ui

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
import graph_tools.LayoutOptimizer
import graph_tools.MoveNodesCommand
import graph_tools.Node
import graph_tools.RemoveEdgesCommand
import graph_tools.RemoveNodesCommand
import graph_tools.StyleNodesCommand
import graph_tools.UnhideCommand
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
        mapper.activeView = graphView
        mapper.feedKey(notation)
        e.consume()
    }

    override fun keyPressed(e: KeyEvent) {
        val notation = KeyNotation.fromKeyPressed(e) ?: return

        val mapper = graphView.keyMapper ?: return
        mapper.activeView = graphView
        mapper.feedKey(notation)
        e.consume()
    }

    override fun keyReleased(e: KeyEvent) {}

    /**
     * Command implementations. Each method is a `(GraphView) -> Unit`
     * registered into [CommandRegistry] at startup by
     * [registerAllCommands]. The old hardcoded dispatch table is gone;
     * the mapper + registry replace it.
     */
    object impl {


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

        fun undo(graphView: GraphView) {
            graphView.g.history.undo()
            // Commands mark specific nodes dirty during their `undo`,
            // but recompute-on-paint is a full sweep of the dirty set
            // anyway — marking everything here costs nothing and covers
            // layout commands that move many nodes at once.
            graphView.g.needsRecomputing(graphView.g.nodes)
            graphView.repaint()
        }

        fun redo(graphView: GraphView) {
            graphView.g.history.redo()
            graphView.g.needsRecomputing(graphView.g.nodes)
            graphView.repaint()
        }

        /**
         * Invert the selection among visible nodes: visible nodes
         * that were selected become unselected, visible nodes that
         * were unselected become selected. Hidden nodes are
         * untouched.
         *
         * Useful as a prefix to `h`: select what you want to KEEP,
         * press `i` to flip, then `h` to hide the (now-selected)
         * clutter.
         */
        fun invertSelection(graphView: GraphView) {
            val visible = graphView.g.nodes.filter { it.isVisible }.toSet()
            val inverted = visible - graphView.g.selectedNodes
            graphView.g.cleanSelect(inverted)
            graphView.repaint()
        }

        /**
         * Hide the selected nodes. Increments hideLevel on every
         * selected node — including nodes already hidden (which
         * get buried deeper), so successive hides stack and need
         * one `H` each to unwind.
         *
         * After hiding, the selection is cleared (hidden nodes
         * shouldn't stay selected — you can't interact with what
         * you can't see).
         *
         * Compose with `i` (invert selection) for the inverse
         * workflow: select what you want to KEEP, press `ih` to
         * invert-then-hide.
         *
         * No-op if the selection is empty.
         */
        fun hideSelected(graphView: GraphView) {
            val sel = graphView.g.selectedNodes.toList()
            if (sel.isEmpty()) return
            graphView.g.commit(HideCommand(sel))
            graphView.g.unselectAll(sel)
            graphView.repaint()
        }

        /**
         * Unhide one level: decrement hideLevel on every node where
         * it's > 0. One `H` undoes the effect of one `h`.
         */
        fun unhideOneLevel(graphView: GraphView) {
            val affected = graphView.g.nodes.filter { it.cache.hideLevel > 0 }
            if (affected.isEmpty()) return
            graphView.g.commit(UnhideCommand(affected))
            graphView.repaint()
        }

        /**
         * Set mark [mark] to the current selection. Every node
         * in the selection gains the mark; every node not in the
         * selection loses it (so the mark exactly names the
         * current selection afterwards). Case of [mark] picks
         * between persistent (uppercase → attributes.marks) and
         * transient (lowercase → cache.sessionMarks) storage.
         *
         * Undoable via [graph_tools.SetMarksCommand]. No-op if
         * nothing changes.
         */
        fun setMark(graphView: GraphView, mark: Char) {
            if (!mark.isLetter()) return
            val g = graphView.g
            val upper = mark.isUpperCase()
            val sel = g.selectedNodes
            val before = g.nodes.associateWith { it.getMarks(upper) }
            val after = g.nodes.associateWith { n ->
                val old = n.getMarks(upper)
                if (n in sel) {
                    if (old.contains(mark)) old else (old + mark).toCharArray().sorted().joinToString("")
                } else {
                    if (old.contains(mark)) old.replace(mark.toString(), "") else old
                }
            }
            if (before != after) {
                g.commit(graph_tools.SetMarksCommand(g, upper, before, after))
            }
            graphView.repaint()
        }

        /**
         * Replace the selection with all visible nodes carrying
         * [mark]. Hidden marked nodes are skipped (you can't
         * select what you can't see). Not undoable — selection
         * changes aren't, per existing policy.
         */
        fun recallMark(graphView: GraphView, mark: Char) {
            if (!mark.isLetter()) return
            val g = graphView.g
            val marked = g.nodes
                .filter { it.isVisible && it.hasMark(mark) }
                .toSet()
            g.cleanSelect(marked)
            graphView.repaint()
        }

        /**
         * Snapshot the current selection to the process-global
         * [Clipboard] as a [NodeFragment]. Clones the nodes so
         * subsequent mutations to the source graph don't touch the
         * clipboard copy; filters edges to "both endpoints selected"
         * so pasting a disconnected piece doesn't try to re-link to
         * nodes that weren't copied.
         *
         * No-op if the selection is empty; the previous clipboard
         * contents are left intact in that case (matches how text
         * editors behave — Ctrl+C on nothing does not wipe state).
         */
        fun copySelection(graphView: GraphView) {
            val sel = graphView.g.selectedNodes.toList()
            if (sel.isEmpty()) return

            // Map each original node to its clone. Edges use this
            // map to rewrite their endpoints so the fragment is
            // fully self-referential.
            val nodeMap: Map<Node, Node> = sel.associateWith { it.deepClone() }
            val edges: List<Edge> = graphView.g.edges
                .filter { it.src in nodeMap && it.dst in nodeMap }
                .map { Edge(nodeMap[it.src]!!, nodeMap[it.dst]!!) }
            val reference = GraphTools.computeCenterOfMass(sel)

            Clipboard.fragment = NodeFragment(
                nodes = nodeMap.values.toList(),
                edges = edges,
                referencePoint = reference,
            )
        }

        /**
         * Copy the selection to the clipboard, then delete it from
         * the graph as an undoable compound. The delete is a single
         * history entry; the copy itself is not undoable (clipboards
         * don't participate in undo stacks).
         */
        fun cutSelection(graphView: GraphView) {
            val sel = graphView.g.selectedNodes.toList()
            if (sel.isEmpty()) return
            copySelection(graphView)
            // Delegate to the existing delete-without-reconnect
            // pathway so the history entry matches what `d` would
            // produce.
            deleteNode(graphView, false)
        }

        /**
         * Instantiate a fresh copy of the clipboard fragment into
         * the current graph, positioned so the fragment's reference
         * point lands under the cursor. The paste is one undoable
         * history step and the pasted nodes become the new
         * selection (a convention that lets the user drag the
         * insertion into place right after pasting).
         */
        fun pasteClipboard(graphView: GraphView) {
            val frag = Clipboard.fragment ?: return
            val g = graphView.g
            val target = graphView.lastCursorPosition
            val delta = target - frag.referencePoint

            // Clone again on every paste so repeated Ctrl+V calls
            // produce independent copies rather than multiple
            // references to the same node.
            val pasteMap: Map<Node, Node> = frag.nodes.associateWith { src ->
                src.deepClone().also { it.position = src.position + delta }
            }
            val newNodes = pasteMap.values.toList()
            val newEdges = frag.edges.map { e ->
                // Edges in the fragment point to nodes in the
                // fragment; rewire them to the fresh clones.
                Edge(pasteMap[e.src]!!, pasteMap[e.dst]!!)
            }

            val children = buildList<Command> {
                add(AddNodesCommand(g, newNodes))
                if (newEdges.isNotEmpty()) {
                    add(AddEdgesCommand(g, newEdges))
                }
            }
            g.commit(CompositeCommand(children))

            // Select the pasted nodes so the user can move them
            // with a drag or adjust styling immediately.
            g.cleanSelect(newNodes.toSet())
            graphView.repaint()
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

        /**
         * Mirror the selected nodes around their bounding-box
         * centre. [horizontal] = true flips x (left↔right),
         * false flips y (top↔bottom).
         *
         * No-op if the selection is empty. Wraps the position
         * changes in a single [MoveNodesCommand] so undo
         * reverts the whole flip in one step.
         */
        fun mirror(graphView: GraphView, horizontal: Boolean) {
            val sel = graphView.g.selectedNodes
            if (sel.isEmpty()) return
            val box = GraphTools.computeBoundingBox(sel) ?: return
            val cx = (box.ul.x + box.br.x) / 2
            val cy = (box.ul.y + box.br.y) / 2

            val before = sel.associateWith { it.position }
            for (n in sel) {
                val p = n.position
                n.position = if (horizontal) {
                    Vector2(2 * cx - p.x, p.y)
                } else {
                    Vector2(p.x, 2 * cy - p.y)
                }
                graphView.g.needsRecomputing(n)
            }
            val after = sel.associateWith { it.position }.toMutableMap()
            if (before != after) {
                graphView.g.history.commitWithoutRun(
                    MoveNodesCommand(graphView.g, before, after),
                )
            }
            graphView.repaint()
        }

        /**
         * Rotate the selected nodes 90° clockwise around their
         * bounding-box centre. Tap four times to return to the
         * starting orientation. (Hence the "toggle" naming —
         * each tap cycles one quarter turn.)
         */
        fun toggleRotate(graphView: GraphView) {
            val sel = graphView.g.selectedNodes
            if (sel.isEmpty()) return
            val box = GraphTools.computeBoundingBox(sel) ?: return
            val cx = (box.ul.x + box.br.x) / 2
            val cy = (box.ul.y + box.br.y) / 2

            val before = sel.associateWith { it.position }
            for (n in sel) {
                // Clockwise 90°: (dx, dy) → (-dy, dx) gives ccw,
                // so we want (dy, -dx) for clockwise. Pick what
                // looks "natural" — matching screen coords where
                // y grows downward, clockwise means
                // (dx, dy) → (-dy, dx).
                val dx = n.position.x - cx
                val dy = n.position.y - cy
                n.position = Vector2(cx - dy, cy + dx)
                graphView.g.needsRecomputing(n)
            }
            val after = sel.associateWith { it.position }.toMutableMap()
            if (before != after) {
                graphView.g.history.commitWithoutRun(
                    MoveNodesCommand(graphView.g, before, after),
                )
            }
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
            val visible = graphView.g.nodes.filter { it.isVisible }.toSet()
            if (graphView.g.selectedNodes == visible) {
                graphView.g.cleanSelect(setOf())
            } else {
                graphView.g.cleanSelect(visible)
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