package graph_tools

import Graph
import ui.NodeStyle
import utils.Vector2
import java.awt.Color
import java.time.Instant

/**
 * Concrete [Command] implementations for every user-visible mutation.
 *
 * Each command holds just enough state to reverse itself. Nothing here
 * touches rendering directly — commands only call the low-level
 * primitives on [Graph] (add/remove of nodes and edges, direct field
 * writes on `Node`). The graph's `needsRecomputing` bookkeeping is
 * refreshed as the mutation happens so that the next paint picks it up.
 *
 * Callers construct a command and pass it to [Graph.commit], which runs
 * the redo path and pushes the entry onto the graph's [History].
 */

/**
 * Composite that groups several commands into one undo/redo step.
 * Typical uses: "delete with reconnect" (remove edges + remove nodes +
 * add replacement edges), or "spawn new node with edge" (add node + add
 * edge). Children run in forward order on redo, reverse order on undo.
 */
class CompositeCommand(val children: List<Command>) : Command {
    override fun redo() {
        children.forEach { it.redo() }
    }

    override fun undo() {
        // Reverse order so that an operation that depends on an earlier
        // operation's effect is undone before the operation it depends on.
        children.asReversed().forEach { it.undo() }
    }
}

/** Add a single [node] to [graph]. */
class AddNodeCommand(private val graph: Graph, private val node: Node) : Command {
    override fun redo() {
        graph.add(node)
        graph.needsRecomputing(node)
    }

    override fun undo() {
        // Direct remove; any incident edges must be handled by a sibling
        // command in a Composite if they exist.
        graph.remove(node)
    }
}

/** Add a batch of [nodes] to [graph]. Used by paste. */
class AddNodesCommand(private val graph: Graph, private val nodes: List<Node>) : Command {
    override fun redo() {
        graph.addAllNodes(nodes)
        graph.needsRecomputing(nodes)
    }

    override fun undo() {
        graph.removeAllNodes(nodes)
    }
}

/** Add a batch of [edges] to [graph]. */
class AddEdgesCommand(private val graph: Graph, private val edges: List<Edge>) : Command {
    override fun redo() {
        graph.addAllEdges(edges)
    }

    override fun undo() {
        graph.removeAllEdges(edges)
    }
}

/**
 * Remove a batch of [nodes] from [graph].
 *
 * Note: this command does NOT capture edges incident to the removed
 * nodes. Callers that care about edge preservation wrap a
 * [RemoveEdgesCommand] together with this one inside a
 * [CompositeCommand] so that undo brings back both the nodes and their
 * connections.
 */
class RemoveNodesCommand(private val graph: Graph, private val nodes: List<Node>) : Command {
    override fun redo() {
        graph.removeAllNodes(nodes)
    }

    override fun undo() {
        graph.addAllNodes(nodes)
        graph.needsRecomputing(nodes)
    }
}

/** Remove a batch of [edges] from [graph]. */
class RemoveEdgesCommand(private val graph: Graph, private val edges: List<Edge>) : Command {
    override fun redo() {
        graph.removeAllEdges(edges)
    }

    override fun undo() {
        graph.addAllEdges(edges)
    }
}

/**
 * Move a set of nodes from their captured "before" positions to their
 * captured "after" positions.
 *
 * Used for two quite different gestures:
 *
 *  - **Direct drag.** `GraphMouseController.dragMoveNode` streams pixel
 *    deltas onto the live positions; we capture the initial positions
 *    once when the drag begins and build the command at mouse release
 *    using the current positions as "after". The command is pushed with
 *    `commitWithoutRun` since the nodes are already in their final
 *    places by then.
 *
 *  - **Layout optimizer.** A one-shot `LayoutOptimizer.optimize` call
 *    moves everything; we snapshot before/after and push a command the
 *    normal way.
 *
 * Coalescing: multiple small move commands issued within
 * [coalesceWindowMs] merge if they affect the same node set. This is a
 * safety net; the drag path already produces one command per drag.
 */
class MoveNodesCommand(
    private val graph: Graph,
    /** Snapshot of positions at drag-start. */
    private val before: Map<Node, Vector2>,
    /** Snapshot of positions at drag-end. Mutable because coalescing extends it in place. */
    private var after: MutableMap<Node, Vector2>,
    private val coalesceWindowMs: Long = 500,
    private var lastTouch: Instant = Instant.now(),
) : Command {
    override fun redo() {
        after.forEach { (n, p) ->
            n.position = p
            graph.needsRecomputing(n)
        }
    }

    override fun undo() {
        before.forEach { (n, p) ->
            n.position = p
            graph.needsRecomputing(n)
        }
    }

    override fun coalesceInto(previous: Command): Boolean {
        if (previous !is MoveNodesCommand) return false
        if (previous.before.keys != before.keys) return false
        val now = Instant.now()
        if (now.toEpochMilli() - previous.lastTouch.toEpochMilli() > coalesceWindowMs) return false
        // Extend the previous command's "after" to reflect our new
        // final positions, keeping the previous "before" as the entry
        // point for the merged undo step.
        previous.after = after
        previous.lastTouch = now
        return true
    }
}

/**
 * Change the text content of a single node from [before] to [after].
 *
 * Consecutive edits on the same node within a short window coalesce, so
 * a user typing a label does not produce one undo step per keystroke.
 */
class EditTextCommand(
    private val graph: Graph,
    private val node: Node,
    private val before: String,
    private var after: String,
    private val coalesceWindowMs: Long = 1000,
    private var lastTouch: Instant = Instant.now(),
) : Command {
    override fun redo() {
        node.attributes.text = after
        graph.needsRecomputing(node)
    }

    override fun undo() {
        node.attributes.text = before
        graph.needsRecomputing(node)
    }

    override fun coalesceInto(previous: Command): Boolean {
        if (previous !is EditTextCommand) return false
        if (previous.node !== node) return false
        val now = Instant.now()
        if (now.toEpochMilli() - previous.lastTouch.toEpochMilli() > coalesceWindowMs) return false
        previous.after = after
        previous.lastTouch = now
        return true
    }
}

/**
 * Snapshot of a node's visual style. Used by [StyleNodesCommand] to
 * capture enough state for a full before/after restore without
 * entangling with the rest of `NodeAttributes`.
 */
data class StyleSnapshot(
    val bg: Color?,
    val fg: Color?,
    val nodeScale: Double?,
) {
    fun applyTo(n: Node) {
        n.attributes.bg = bg
        n.attributes.fg = fg
        n.attributes.nodeScale = nodeScale
    }

    companion object {
        fun of(n: Node): StyleSnapshot = StyleSnapshot(
            bg = n.attributes.bg,
            fg = n.attributes.fg,
            nodeScale = n.attributes.nodeScale,
        )
    }
}

/**
 * Apply a style change to a set of nodes. Captures each node's previous
 * style individually so undo restores per-node state (not "all nodes to
 * the same style").
 */
class StyleNodesCommand(
    private val graph: Graph,
    private val before: Map<Node, StyleSnapshot>,
    private val after: Map<Node, StyleSnapshot>,
) : Command {
    override fun redo() {
        after.forEach { (n, s) ->
            s.applyTo(n)
            graph.needsRecomputing(n)
        }
    }

    override fun undo() {
        before.forEach { (n, s) ->
            s.applyTo(n)
            graph.needsRecomputing(n)
        }
    }

    companion object {
        /** Build a command that sets every node in [nodes] to [style]. */
        fun toUniform(graph: Graph, nodes: Iterable<Node>, style: NodeStyle): StyleNodesCommand {
            val before = nodes.associateWith { StyleSnapshot.of(it) }
            val target = StyleSnapshot(
                bg = style.background,
                fg = style.foreground,
                nodeScale = style.nodeScale,
            )
            val after = nodes.associateWith { target }
            return StyleNodesCommand(graph, before, after)
        }
    }
}

/**
 * Change the shape of a set of nodes. Captures per-node "before" so
 * mixing shapes in one selection still undoes cleanly.
 */
class SetShapeCommand(
    private val graph: Graph,
    private val before: Map<Node, NodeShape?>,
    private val newShape: NodeShape,
) : Command {
    override fun redo() {
        before.keys.forEach {
            it.setShape(newShape)
            graph.needsRecomputing(it)
        }
    }

    override fun undo() {
        before.forEach { (n, s) ->
            n.setShape(s)
            graph.needsRecomputing(n)
        }
    }
}

/**
 * Adjust the font scale of a set of nodes by [delta]. Doubles as an
 * "absolute" setter when [absolute] is provided (in which case the
 * delta is ignored).
 */
class SetSizeCommand(
    private val graph: Graph,
    private val before: Map<Node, Double?>,
    private val delta: Double?,
    private val absolute: Double?,
) : Command {
    override fun redo() {
        before.keys.forEach {
            it.setSize(absolute = absolute, relative = delta)
            graph.needsRecomputing(it)
        }
    }

    override fun undo() {
        before.forEach { (n, s) ->
            n.attributes.nodeScale = s
            graph.needsRecomputing(n)
        }
    }
}
