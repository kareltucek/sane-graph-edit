package ui

import graph_tools.Edge
import graph_tools.Node
import utils.Vector2

/**
 * Process-global clipboard for cross-tab node copy/paste.
 *
 * Holds a single [NodeFragment] at a time — a copy replaces whatever
 * was there before. The fragment stores *clones*, not references, so
 * pasting into any tab (including the source tab, after several
 * intermediate edits) never leaks identity with the original nodes.
 *
 * Not synced with the system clipboard (`Toolkit.getSystemClipboard`).
 * That would be a nice bonus — paste-from-terminal DOT snippets — but
 * is explicitly out of scope for the first cut; see `tasks/tabs.md`
 * under "DOT clipboard interop".
 */
object Clipboard {
    var fragment: NodeFragment? = null
}

/**
 * A self-contained piece of graph that can be pasted into any graph.
 *
 * [nodes] and [edges] hold freshly cloned objects: edges point at
 * nodes in *this* fragment's [nodes] list, never at nodes from the
 * source graph. This way the fragment survives arbitrary mutations
 * to the graph it was copied from.
 *
 * [referencePoint] is the centre-of-mass of the selection at copy
 * time. Pasting shifts everything so this point lands at the target
 * cursor position, which matches how most vector editors behave.
 */
data class NodeFragment(
    val nodes: List<Node>,
    val edges: List<Edge>,
    val referencePoint: Vector2,
)

/**
 * Produce a standalone copy of this node: same attributes and
 * position, but a fresh identity, a fresh cache, and an independent
 * `other` attribute map.
 *
 * Does not preserve the node's selection state or its place in any
 * particular graph — a clone is an orphan until someone adds it to
 * one. The render cache (`bounds`, `lines`, `font`) is left empty;
 * the next paint will repopulate it via `Graph.needsRecomputing`.
 */
fun Node.deepClone(): Node {
    val clone = Node(position)
    clone.attributes.text = this.attributes.text
    clone.attributes.bg = this.attributes.bg
    clone.attributes.fg = this.attributes.fg
    clone.attributes.nodeScale = this.attributes.nodeScale
    // Node name is intentionally left blank: the name is a
    // parse-derived identifier that the DOT serializer regenerates.
    // Carrying it across a clone would force an ID collision in the
    // destination graph.
    clone.attributes.other.putAll(this.attributes.other)
    clone.cache.shape = this.cache.shape
    return clone
}
