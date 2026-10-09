package graph_tools

import Graph
import utils.Utils.toNiceString

/**
 * A node's third coordinate: how far it is behind the plane the
 * graph is drawn in, away from the viewer, in the units of its
 * position; negative for a node in front of that plane.
 * In a DOT file it is the third number of `pos` (`pos="x,y,z!"`), and
 * an attribute `z` is read as well; a node with neither is in the
 * plane. Among a node's attributes here it is kept as `z`, so that it
 * is undone and shared like the others.
 */
object Depth {
    const val ATTRIBUTE = "z"

    fun of(node: Node): Double = node.attributes.other[ATTRIBUTE]?.toDoubleOrNull() ?: 0.0

    /** Put [node] at [z]. A node in the plane has no attribute: a flat graph's file says nothing of depth. */
    fun set(node: Node, z: Double) {
        if (z == 0.0) node.attributes.other.remove(ATTRIBUTE) else node.attributes.other[ATTRIBUTE] = z.toNiceString()
    }
}

/** Move the nodes of [after] to the depths given there; [before] is where they were, for undo. */
class SetDepthCommand(
    private val graph: Graph,
    private val before: Map<Node, Double>,
    private val after: Map<Node, Double>,
) : Command {
    override fun redo() = after.forEach { (node, z) -> Depth.set(node, z) }

    override fun undo() = before.forEach { (node, z) -> Depth.set(node, z) }
}
