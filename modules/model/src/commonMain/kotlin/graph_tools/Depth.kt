package graph_tools

import Graph
import utils.Vector3

/**
 * A node's third coordinate: how far it is behind the plane the
 * graph is drawn in, away from the viewer, in the units of its
 * position; negative for a node in front of that plane.
 * In a DOT file it is the third number of `pos` (`pos="x,y,z!"`), and
 * an attribute `z` is read as well; a node with neither is in the
 * plane, and its `pos` has two numbers. In memory it is the third
 * coordinate of [Node.point].
 */
object Depth {
    /** The attribute older files have the depth in. */
    const val ATTRIBUTE = "z"

    fun of(node: Node): Double = node.point.z

    fun set(node: Node, z: Double) {
        node.point = Vector3(node.point.xy, z)
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
