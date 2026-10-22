package client

import graph_tools.GraphTools.computeGeneration
import graph_tools.AddEdgesCommand
import graph_tools.AddNodeCommand
import graph_tools.AddNodesCommand
import graph_tools.CompositeCommand
import graph_tools.Depth
import graph_tools.SetDepthCommand
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
import graph_tools.NodeShape
import graph_tools.NodeType
import graph_tools.SetNodeTypeCommand
import graph_tools.SetShapeCommand
import graph_tools.SetSizeCommand
import graph_tools.StyleNodesCommand
import graph_tools.StyleSnapshot
import graph_tools.UnhideCommand
import graph_tools.ViewModes
import kotlin.math.pow
import utils.Constants
import utils.Vector2
import utils.Utils.orElse
import utils.Utils.toUnit

/** What `m<letter>`, `m+<letter>` and `m-<letter>` do to a mark. */
enum class MarkChange { Set, Add, Remove }

/**
 * What the editing commands do, on an [EditorView]: they read its
 * graph, selection and cursor and commit [Command]s. No GUI toolkit
 * involved — a renderer registers them under their command names
 * and runs them on key presses.
 */
object Actions {


    fun copyFormat(graphView: EditorView) {
        val target = graphView.g.selectedNodes.firstOrNull()

        graphView.defaultNodeStyle = target?.let { n ->
            NodeStyle.fromNode(n)
        }.orElse(NodeStyle())
    }

    fun pasteFormat(graphView: EditorView) {
        val targets = graphView.g.selectedNodes.toList()
        if (targets.isEmpty()) return
        val cmd = StyleNodesCommand.toUniform(graphView.g, targets, graphView.defaultNodeStyle)
        graphView.g.commit(cmd)
        graphView.repaint()
    }

    // --- what the style picker offers, on the selection ---

    /** Give the selected nodes the colours of [style]; their sizes stay. */
    fun colorSelection(graphView: EditorView, style: ColorStyle) {
        val g = graphView.g
        val targets = g.selectedNodes.toList()
        if (targets.isNotEmpty()) {
            val before = targets.associateWith { StyleSnapshot.of(it) }
            val after = targets.associateWith { StyleSnapshot(bg = style.background, fg = style.foreground, nodeScale = it.attributes.nodeScale) }
            g.commit(StyleNodesCommand(g, before, after))
        }
    }

    /** Make the selected nodes [steps] size steps bigger (smaller when negative). */
    fun resizeSelection(graphView: EditorView, steps: Double) {
        val g = graphView.g
        val targets = g.selectedNodes.toList()
        if (targets.isNotEmpty()) {
            g.commit(SetSizeCommand(g, targets.associateWith { it.attributes.nodeScale }, delta = steps, absolute = null))
            graphView.refreshStatus()
            graphView.repaint()
        }
    }

    /**
     * Make the selected nodes (or, with none selected, the last
     * active one) nodes of [type]: plain text, code in a language,
     * or markdown. One undo step; a message if there is no node.
     */
    fun typeSelection(graphView: EditorView, type: NodeType, keepSource: Boolean = false) {
        val g = graphView.g
        val targets = typeTargets(graphView)
        if (targets.isEmpty()) {
            graphView.shell.message("content-type: no node is selected")
        } else {
            val typed = SetNodeTypeCommand(g, targets.associateWith { it.attributes.other[NodeType.ATTRIBUTE] }, type, keepSource)
            // a terminal is as big as it is given to be: one that has no size yet gets the usual one
            val unsized = if (type.kind == NodeType.Kind.Terminal) targets.filter { graph_tools.ManualSize.of(it) == null } else emptyList()
            val sized = graph_tools.SetManualSizeCommand(
                g,
                unsized.associateWith { null to it.position },
                unsized.associateWith { graph_tools.ManualSize.write(terminalSize(it)) to it.position },
            )
            g.commit(if (unsized.isEmpty()) typed else CompositeCommand(listOf(typed, sized)))
            graphView.repaint()
        }
    }

    /**
     * Give the selected nodes a size of their own, the one they have
     * now ([manual]: they are then resized by their corners and what
     * does not fit in them is not shown), or let their text decide
     * how big they are again. One undo step.
     */
    fun sizeModeSelection(graphView: EditorView, manual: Boolean) {
        val g = graphView.g
        val targets = g.selectedNodes.toList().filter { (graph_tools.ManualSize.of(it) != null) != manual }
        if (targets.isNotEmpty()) {
            val before = targets.associateWith { it.attributes.other[graph_tools.ManualSize.ATTRIBUTE] to it.position }
            val after = targets.associateWith { (if (manual) graph_tools.ManualSize.write(it.cache.shapeBounds) else null) to it.position }
            g.commit(graph_tools.SetManualSizeCommand(g, before, after))
            graphView.repaint()
        }
    }

    /** Whether the selected nodes have a size of their own, when they all do or all do not; else null. */
    fun sizeModeOfSelection(graphView: EditorView): Boolean? =
        graphView.g.selectedNodes.map { graph_tools.ManualSize.of(it) != null }.distinct().singleOrNull()

    /** The size of a terminal of 80 columns and 24 rows in [n]'s font, about: a character is taken to be 0.6 of the font's size wide and a line 1.25 of it high. */
    private fun terminalSize(n: Node): utils.Vector2 =
        graph_tools.NodeText.fontSize(n).let { font -> utils.Vector2(82 * 0.6 * font, 25 * 1.25 * font) }

    private fun typeTargets(graphView: EditorView): List<Node> =
        graphView.g.let { g -> g.selectedNodes.toList().ifEmpty { listOfNotNull(g.lastActiveNode?.takeIf { it in g.nodes }) } }

    /** What the nodes that [typeSelection] would change are, when they all are the same: as the attribute has it, without where the text is kept; else null. */
    fun typeOfSelection(graphView: EditorView): String? =
        typeTargets(graphView).map { NodeType.of(it).copy(source = null).let { type -> type.attribute ?: NodeType.Kind.Plain.id } }.distinct().singleOrNull()

    /**
     * Move the selected nodes (or, with none selected, the last
     * active one) to the depth [z]; with [relative], by [z] from
     * where each is. One undo step; a message if there is no node.
     */
    fun depthSelection(graphView: EditorView, z: Double, relative: Boolean) {
        val g = graphView.g
        val targets = g.selectedNodes.toList().ifEmpty { listOfNotNull(g.lastActiveNode?.takeIf { it in g.nodes }) }
        if (targets.isEmpty()) {
            graphView.shell.message("z: no node is selected")
        } else {
            val before = targets.associateWith { Depth.of(it) }
            g.commit(SetDepthCommand(g, before, before.mapValues { (_, was) -> if (relative) was + z else z }))
            graphView.repaint()
        }
    }

    fun shapeSelection(graphView: EditorView, shape: NodeShape) {
        val g = graphView.g
        val targets = g.selectedNodes.toList()
        if (targets.isNotEmpty()) {
            // the command wants the shapes before as NodeShape entries; nodes hold their implementations
            val before = targets.associateWith { n -> NodeShape.values().firstOrNull { it.impl === n.cache.shape } }
            g.commit(SetShapeCommand(g, before, shape))
            graphView.repaint()
        }
    }

    fun grab(graphView: EditorView) {
        graphView.gestures.startOrEndMove()
    }

    fun undo(graphView: EditorView) {
        graphView.g.undo()
        // Commands mark specific nodes dirty during their `undo`,
        // but recompute-on-paint is a full sweep of the dirty set
        // anyway — marking everything here costs nothing and covers
        // layout commands that move many nodes at once.
        graphView.g.needsRecomputing(graphView.g.nodes)
        graphView.repaint()
    }

    fun redo(graphView: EditorView) {
        graphView.g.redo()
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
    fun invertSelection(graphView: EditorView) {
        val visible = graphView.g.visibleNodes()
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
    fun hideSelected(graphView: EditorView) {
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
    fun unhideOneLevel(graphView: EditorView) {
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
     * With [MarkChange.Add] the selection gains the mark and
     * with [MarkChange.Remove] it loses it; the other nodes
     * keep what they have.
     *
     * Undoable via [graph_tools.SetMarksCommand]. No-op if
     * nothing changes.
     */
    fun setMark(graphView: EditorView, mark: Char, change: MarkChange = MarkChange.Set) {
        if (!mark.isLetter()) return
        val g = graphView.g
        val upper = mark.isUpperCase()
        val sel = g.selectedNodes
        val before = g.nodes.associateWith { it.getMarks(upper) }
        val after = g.nodes.associateWith { n ->
            val old = n.getMarks(upper)
            val wanted = when (change) {
                MarkChange.Set -> n in sel
                MarkChange.Add -> n in sel || old.contains(mark)
                MarkChange.Remove -> n !in sel && old.contains(mark)
            }
            if (wanted) {
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
    fun recallMark(graphView: EditorView, mark: Char) {
        if (!mark.isLetter()) return
        val g = graphView.g
        val marked = g.nodes
            .filter { g.isVisible(it) && it.hasMark(mark) }
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
    fun copySelection(graphView: EditorView) {
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
            from = graphView.content.currentFile,
        )
    }

    /**
     * Copy the selection to the clipboard, then delete it from
     * the graph as an undoable compound. The delete is a single
     * history entry; the copy itself is not undoable (clipboards
     * don't participate in undo stacks).
     */
    fun cutSelection(graphView: EditorView) {
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
    fun pasteClipboard(graphView: EditorView) {
        val frag = Clipboard.fragment ?: return
        val g = graphView.g
        // under the cursor, in the plane the view speaks of; the fragment's middle depth goes there
        val t = graphView.viewTransform
        val target = t.toWorld(Point3(graphView.lastCursorPosition, t.depth))
        val delta = target.xy - frag.referencePoint
        val sink = target.z - frag.nodes.sumOf { Depth.of(it) } / frag.nodes.size

        // Clone again on every paste so repeated Ctrl+V calls
        // produce independent copies rather than multiple
        // references to the same node.
        val pasteMap: Map<Node, Node> = frag.nodes.associateWith { src ->
            src.deepClone().also {
                it.position = src.position + delta
                Depth.set(it, Depth.of(src) + sink)
                FileLink.moveTo(it, frag.from, graphView.content.currentFile)
            }
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

    fun unselectAll(graphView: EditorView) {
        // Escape cancels any active modal drag / rotation
        // first — so the first Escape aborts a grab or
        // rotate, and a subsequent one clears the selection
        // as usual. Explore mode is left the same way, before
        // the selection is touched.
        if (graphView.gestures.cancelActiveModal()) return
        if (Explore.isOn(graphView)) {
            Explore.set(graphView, false)
        } else {
            graphView.g.cleanSelect(emptySet())
            graphView.repaint()
        }
    }

    fun centerScreen(graphView: EditorView) {
        graphView.centerScreen()
    }

    fun boundScreen(graphView: EditorView) {
        graphView.boundScreen()
    }

    /** Turn the view back to the front, around what is in its middle. */
    fun viewFront(graphView: EditorView) {
        graphView.viewTransform.turnTo(0.0, 0.0, graphView.size / 2)
        graphView.repaint()
    }

    fun selectClosure(graphView: EditorView, forward: Boolean) {
        selectTraversed(graphView, computeClosure(graphView.g, graphView.g.selectedNodes, forward))
    }

    fun selectLinked(graphView: EditorView, forward: Boolean) {
        selectTraversed(graphView, computeGeneration(graphView.g, graphView.g.selectedNodes, forward))
    }

    /**
     * Add [reached] to the selection. In unhide mode traversal
     * also reaches hidden nodes; those are unhidden (one undo
     * step) so the selection never holds nodes you can't see.
     */
    private fun selectTraversed(graphView: EditorView, reached: Set<Node>) {
        val hidden = reached.filter { ViewModes.unhide && it.isHidden }
        if (hidden.isNotEmpty()) {
            graphView.g.commit(RevealCommand(hidden.associateWith { it.cache.hideLevel }))
        }
        graphView.g.selectAll(reached)
        graphView.repaint()
    }

    fun unselectOldestGen(graphView: EditorView, forward: Boolean) {
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
    fun mirror(graphView: EditorView, horizontal: Boolean) {
        val sel = graphView.g.movable(graphView.g.selectedNodes)
        if (sel.isEmpty()) return
        // across the view: left and right, up and down are the viewer's
        val middle = graphView.middleOf(sel) ?: return
        val before = sel.associateWith { it.position }
        val depthBefore = sel.associateWith { Depth.of(it) }
        for (n in sel) {
            val p = graphView.viewPoint(n)
            graphView.place(n, if (horizontal) Point3(2 * middle.x - p.x, p.y, p.z) else Point3(p.x, 2 * middle.y - p.y, p.z))
        }
        graphView.commitMoved(before, depthBefore)
        graphView.repaint()
    }

    /**
     * Toggle rotate mode. Delegates to the mouse controller,
     * which handles the live tracking in [mouseMoved]. See
     * [GestureController.startOrEndRotate] for the state
     * machine.
     *
     * Matches the shape of [grab]: first call arms the mode,
     * cursor movement rotates the selection around the
     * bounding-box centre in step with the mouse angle,
     * second call commits the final positions as one
     * [MoveNodesCommand] (undoable as a single step).
     * Escape cancels and reverts (see [unselectAll]).
     */
    fun rotate(graphView: EditorView) {
        graphView.gestures.startOrEndRotate()
    }

    fun scale(graphView: EditorView) {
        graphView.gestures.startOrEndScale()
    }

    /**
     * Toggle [GestureController.lockX].
     * Bound to the `y` key in Transform mode (Blender semantic:
     * `y` constrains to the Y axis by freezing X).
     */
    /**
     * "Constrain to X": press `x` to scale / move along X
     * only. Internally that means locking Y (the other axis).
     * Tapping `x` while already X-constrained releases the
     * constraint; tapping `x` while Y-constrained switches
     * (radio-style) so only one axis is locked at a time.
     *
     * Blender convention: the letter you press names the
     * axis that *remains free*.
     */
    fun constrainAxisX(graphView: EditorView) {
        val c = graphView.gestures
        val alreadyConstrainedToX = c.lockY && !c.lockX
        if (alreadyConstrainedToX) {
            c.lockY = false   // release
        } else {
            c.lockY = true    // lock the *other* axis
            c.lockX = false   // radio clear
        }
        // Re-render positions with the new constraint, then
        // refresh the status bar so the "(X)/(Y)" suffix
        // reflects the new state.
        reapplyActiveGesture(graphView)
        graphView.refreshStatus()
    }

    fun constrainAxisY(graphView: EditorView) {
        val c = graphView.gestures
        val alreadyConstrainedToY = c.lockX && !c.lockY
        if (alreadyConstrainedToY) {
            c.lockX = false
        } else {
            c.lockX = true
            c.lockY = false
        }
        reapplyActiveGesture(graphView)
        graphView.refreshStatus()
    }

    /**
     * After an axis-lock flip, re-run the active gesture's
     * drag function so nodes snap to the axis-locked position
     * without waiting for the next mouse-move event. Scale is
     * the common case; grab's next delta is zero (cursor
     * hasn't moved) so re-running dragMoveNode is a no-op;
     * rotate turns around the locked axis in 3D mode.
     */
    private fun reapplyActiveGesture(graphView: EditorView) {
        val c = graphView.gestures
        when (c.state) {
            GestureController.States.Scaling ->
                c.dragScale(graphView.lastScreenCursorPosition)
            // Grab's dragMoveNode is now idempotent (from-start
            // + total delta), so re-invoking it with the last
            // cursor reverts any drift on the newly-frozen
            // axis instead of leaving it stranded.
            GestureController.States.MovingNodes ->
                c.dragMoveNode(graphView.lastCursorPosition, settling = false)
            // in 3D mode an axis lock turns around that axis of the screen; in 2D it does nothing
            GestureController.States.Rotating ->
                c.dragRotate(graphView.lastCursorPosition)
            else -> Unit
        }
    }

    /**
     * Time of the last spring-scale tweak, for rate-limiting
     * the multiplier so held keys and single taps both feel
     * right regardless of the OS key-repeat rate.
     * Package-visible so tests can reset it between runs —
     * this is singleton state on the `impl` object and
     * persists across test cases otherwise.
     */
    internal var lastSpringTweakTime: Long = 0L

    /**
     * Adjust [LayoutOptimizer.springScale] toward [perSecFactor]
     * and run one optimize pass.
     *
     * [perSecFactor] is the "rate": the scale the multiplier
     * would reach if the key were held for a full second
     * (e.g. 0.9 for shorter, 1/0.9 for longer). The actual
     * multiplier per call is `perSecFactor^elapsedSec`, where
     * `elapsedSec` is the time since the last call, clamped
     * to a minimum of 200 ms so a single tap produces a
     * visible 0.2-seconds-worth step.
     *
     * Consequence: sustained key-repeat yields a smooth
     * per-second scaling regardless of repeat rate — fast
     * repeats produce many tiny steps, slow repeats produce
     * fewer larger ones, both totalling to perSecFactor per
     * second. A single press gives 0.2 s worth.
     */
    fun tweakSpringScale(graphView: EditorView, perSecFactor: Double) {
        val now = utils.Platform.nowMillis()
        // Elapsed since last tap, clamped to at most 200 ms.
        // A single tap after a long pause = 200 ms of effect.
        // A held key firing every 30 ms = 30 ms of effect per
        // tick, 33 ticks/sec → perSecFactor per second total.
        // First call (lastSpringTweakTime == 0) also lands at
        // 200 ms due to the clamp.
        val elapsedMs = (now - lastSpringTweakTime).coerceAtMost(200L)
        val elapsedSec = elapsedMs / 1000.0
        LayoutOptimizer.springScale *= perSecFactor.pow(elapsedSec)
        lastSpringTweakTime = now
        optimize(graphView, restrict = false)
    }

    fun optimize(graphView: EditorView, restrict: Boolean) {
        // Capture positions before optimising, then build a
        // MoveNodesCommand so the user can undo a layout pass with
        // Ctrl+Z. We snapshot all nodes because the optimiser may
        // touch any of them; unchanged nodes cost nothing to record.
        val before = graphView.g.nodes.associateWith { it.position }
        LayoutOptimizer.optimize(graphView.g, movingNodes = false, restrictOperator = restrict)
        val after = graphView.g.nodes.associateWith { it.position }.toMutableMap()
        if (before != after) {
            graphView.g.commitApplied(MoveNodesCommand(graphView.g, before, after))
        }
        graphView.repaint()
    }

    /**
     * `o` / `O`: the springs move the nodes while the key is held ([GestureController.startOptimizing]); asked for
     * otherwise (a macro, a mapping, `:optimize`), as far as they move in [TAPPED_MS], as one undo step: the same
     * wherever and however fast it runs.
     */
    fun optimizeWhileHeld(graphView: EditorView, restrict: Boolean) {
        if (graphView.byHeldKey) graphView.gestures.startOptimizing(restrict)
        else {
            val before = graphView.g.nodes.associateWith { it.point }
            graphView.gestures.layout(restrict, TAPPED_MS).step()
            graphView.gestures.commitPoints(before)
            graphView.repaint()
        }
    }

    /** How long, in the springs' time ([LayoutOptimizer.WINDOW_MS]), an `o` that is not held lays out; a held one at least that. */
    const val TAPPED_MS = 20.0

    fun selectAll(graphView: EditorView) {
        val visible = graphView.g.visibleNodes()
        if (graphView.g.selectedNodes == visible) {
            graphView.g.cleanSelect(setOf())
        } else {
            graphView.g.cleanSelect(visible)
        }
        graphView.repaint()
    }

    fun editNode(graphView: EditorView, select: Boolean) {
        graphView.g.lastActiveNode?.let { n ->
            if (select) {
                graphView.g.cleanSelect(setOf(n))
            }
            graphView.startNodeEdit(n, null)
        }
    }

    /** Go into the terminal of the last active node as big as the window lets it; any other node is edited as by [editNode]. */
    fun enterMaximized(graphView: EditorView) {
        graphView.g.lastActiveNode?.let { n ->
            if (NodeType.of(n).kind == NodeType.Kind.Terminal) graphView.startMaximized(n.id) else graphView.startNodeEdit(n, null)
        }
    }

    fun deleteNode(graphView: EditorView, reconnectNodes: Boolean) {
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


    fun clearEdges(graphView: EditorView) {
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

    fun drawNodeWithEdge(graphView: EditorView, forwardEdge: Boolean, append: Boolean) {
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

        val at = graphView.viewTransform.toWorld(Point3(pos, graphView.viewTransform.depth))
        val newNode = Node(
            label = Constants.defaultNodeText,
            pos = at.xy,
            style = style,
        )
        Depth.set(newNode, at.z)

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

    fun drawEdge(graphView: EditorView, forward: Boolean) {
        val g = graphView.g
        val pos = graphView.lastCursorPosition
        val pointed = Clicker.selectClickedNode(graphView, pos)
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
