package graph_tools

import Graph
import utils.Vector2

/**
 * A node's size as the user gave it (the picker's "manual", then a
 * drag by a corner), instead of the one its text has: the node is
 * that big whatever is in it, and what does not fit is not shown.
 *
 * In a file it is the node's `node-size` attribute, width and height
 * in the graph's units: `node-size="320x200"`. A node without it is
 * as big as its text ("auto").
 */
object ManualSize {
    /** The DOT attribute. */
    const val ATTRIBUTE = "node-size"

    /** No node is made smaller than this, either way. */
    const val SMALLEST = 10.0

    fun parse(text: String?): Vector2? {
        val parts = text?.trim()?.split('x', 'X').orEmpty().map { it.trim().toDoubleOrNull() }
        val width = parts.getOrNull(0)
        val height = parts.getOrNull(1)
        return if (parts.size == 2 && width != null && height != null && width.isFinite() && height.isFinite() && width > 0 && height > 0) {
            Vector2(width.coerceAtLeast(SMALLEST), height.coerceAtLeast(SMALLEST))
        } else null
    }

    fun write(size: Vector2): String = "${round(size.x)}x${round(size.y)}"

    private fun round(n: Double): String = (kotlin.math.round(n * 10) / 10).let { if (it == kotlin.math.floor(it)) it.toLong().toString() else it.toString() }

    /** The size [node] was given; null when it is as big as its text. */
    fun of(node: Node): Vector2? = parse(node.attributes.other[ATTRIBUTE])
}

/**
 * Give nodes a size of their own, or none: for each node of [before]
 * what its `node-size` attribute held and where it was, and of
 * [after] what it holds and where it is from now on (a node that is
 * resized by a corner moves: the opposite corner stays).
 */
class SetManualSizeCommand(
    private val graph: Graph,
    private val before: Map<Node, Pair<String?, Vector2>>,
    private val after: Map<Node, Pair<String?, Vector2>>,
) : Command {
    override fun redo() = after.forEach { (node, it) -> set(node, it) }

    override fun undo() = before.forEach { (node, it) -> set(node, it) }

    private fun set(node: Node, to: Pair<String?, Vector2>) {
        val (value, position) = to
        if (value == null) node.attributes.other.remove(ManualSize.ATTRIBUTE) else node.attributes.other[ManualSize.ATTRIBUTE] = value
        node.position = position
        graph.needsRecomputing(node)
    }
}
