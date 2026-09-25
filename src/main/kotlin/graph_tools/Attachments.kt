package graph_tools

import Graph
import utils.Vector2

/**
 * Nodes glued to another node (docs/file-format.md, `attachTo` /
 * `attachSide`): an attached node's position is derived from its
 * owner's — flush against the given side with a [GAP], centred on the
 * owner, several on one side stacked outwards in node order.
 *
 * [reglue] re-derives the positions (called from [Graph.recompute], so
 * on every paint and before headless export); movers act on
 * [Graph.movable] (owners instead of attached nodes) and the layout
 * optimiser pushes an attached node's forces onto its owner.
 */
object Attachments {
    const val ATTACH_TO = "attachTo"
    const val ATTACH_SIDE = "attachSide"

    /** Distance between an owner and what is glued to it. */
    const val GAP = 2.0

    enum class Side { Above, Below, Left, Right }

    fun sideOf(n: Node): Side = when (n.attributes.other[ATTACH_SIDE]) {
        "above" -> Side.Above
        "left" -> Side.Left
        "right" -> Side.Right
        else -> Side.Below
    }

    /**
     * attached node → owner, for every node whose `attachTo` names
     * another existing node (by DOT id) without leading into a cycle.
     */
    fun owners(g: Graph): Map<Node, Node> {
        val byName = g.nodes.filter { it.attributes.name.isNotBlank() }.associateBy { it.attributes.name }
        val raw = g.nodes.mapNotNull { n ->
            n.attributes.other[ATTACH_TO]?.let { byName[it] }?.takeIf { it !== n }?.let { n to it }
        }.toMap()
        val acyclic = { n: Node ->
            val chain = generateSequence(n) { raw[it] }.take(raw.size + 2).toList()
            chain.size <= raw.size + 1
        }
        return raw.filterKeys(acyclic)
    }

    /** The owner at the top of [n]'s attachment chain ([n] itself if unattached). */
    fun root(n: Node, owners: Map<Node, Node>): Node = generateSequence(n) { owners[it] }.last()

    /**
     * Put every attached node where its owner says; owners first, so
     * chains follow. Returns the nodes that moved (their edges need
     * recomputing). Uses the nodes' cached sizes.
     */
    fun reglue(g: Graph): Set<Node> {
        val owners = g.attachmentOwners
        val attached = g.nodes.filter { it in owners }.groupBy { owners.getValue(it) }
        val moved = mutableSetOf<Node>()
        var layer = attached.keys.filter { it !in owners }
        while (layer.isNotEmpty()) {
            layer.forEach { owner -> moved.addAll(place(owner, attached[owner].orEmpty())) }
            layer = layer.flatMap { attached[it].orEmpty() }.filter { it in attached }
        }
        return moved
    }

    /** Glue [nodes] to [owner]; returns those whose position changed. */
    private fun place(owner: Node, nodes: List<Node>): List<Node> {
        val half = owner.cache.shapeBounds / 2
        return nodes.groupBy { sideOf(it) }.flatMap { (side, stack) ->
            // distance from the owner's centre to the far edge of what is stacked so far
            var reach = if (side == Side.Above || side == Side.Below) half.y else half.x
            stack.mapNotNull { n ->
                val size = n.cache.shapeBounds
                val along = if (side == Side.Above || side == Side.Below) size.y else size.x
                val offset = reach + GAP + along / 2
                reach += GAP + along
                val target = when (side) {
                    Side.Above -> owner.position + Vector2(0.0, -offset)
                    Side.Below -> owner.position + Vector2(0.0, offset)
                    Side.Left -> owner.position + Vector2(-offset, 0.0)
                    Side.Right -> owner.position + Vector2(offset, 0.0)
                }
                val changed = (target - n.position).lengthSquared() > 1e-12
                if (changed) n.position = target
                n.takeIf { changed }
            }
        }
    }
}
