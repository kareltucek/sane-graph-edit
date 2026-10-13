package graph_tools

import Graph
import utils.Vector2

/**
 * The springs of [LayoutOptimizer] on a few nodes only, until they
 * rest: for nodes that just came to be seen in explore mode
 * ([DynamicPlacement]) and those that are in their way.
 *
 * Only the nodes given move, and with them every node they run
 * into that may give way (it joins them, and so on from it); all
 * else stays where it is and is pushed off. On a node that moves
 * act, of the optimizer's springs:
 *
 *  - the edge spring, to the nodes it has an edge with that are seen;
 *  - the collision spring, from the nodes near it
 *    ([LayoutOptimizer.impl.Colliders]: a step costs about as much
 *    as there are nodes seen, not that squared);
 *  - the one that makes the edges out of one node as long as each
 *    other, among its children that are seen;
 *
 * not the gravity spring, and instead of it one that is only here:
 *
 *  - the origin spring, to where the node is in the document relative
 *    to its neighbours that are seen (given by who asks): it keeps
 *    both the direction and the distance the user gave the node, but
 *    pulls no harder than [ORIGIN_CAP], so that the others bring a
 *    node that is far away in.
 *
 * As in the optimizer what the springs ask of a node is averaged,
 * but what pushes it and what pulls it apart, and the pull counts
 * for less while something pushes. Each step moves a node a part of that, a smaller part each time,
 * so that two nodes pushing each other do not swing. The origin
 * spring acts until the nodes rest, as all of this does.
 */
object Settling {
    private const val MAX_STEPS = 200

    /** The part of what the springs ask for that the first step moves. */
    private const val DAMPING = 0.6

    /** By how much that part shrinks with each step. */
    private const val COOLING = 0.985

    /** Nothing moved further than this in a step: the nodes rest. */
    private const val SETTLED = 0.5

    /** How much of what pulls a node counts while something pushes it. */
    private const val PULL_WHEN_PUSHED = 0.3

    /** The origin spring asks for no more than this in a step, however far the node is from where it was. */
    const val ORIGIN_CAP = 40.0

    private data class Step(val moving: Set<Node>, val furthest: Double)

    /**
     * Move [fresh], and of [among] (the nodes seen, [fresh] too)
     * those in their way that [yields] says may move, until they
     * rest. [origin] is where a node would be by the document, as
     * things are now; null for a node the document says nothing of.
     * The nodes are moved in place; those that took part are returned.
     */
    fun settle(g: Graph, fresh: Set<Node>, among: Set<Node>, yields: (Node) -> Boolean, origin: (Node) -> Vector2? = { null }): Set<Node> {
        tailrec fun run(moving: Set<Node>, damping: Double, left: Int): Set<Node> {
            val done = step(g, moving, among, yields, origin, damping)
            val rests = done.furthest < SETTLED && done.moving.size == moving.size
            return if (rests || left <= 1) done.moving else run(done.moving, damping * COOLING, left - 1)
        }
        // and then, with nothing pulling any more, off each other those that are still on each other: many nodes pulled to one place
        tailrec fun apart(moving: Set<Node>, left: Int): Set<Node> {
            val done = step(g, moving, among, yields, origin, DAMPING, pulling = false)
            return if (done.furthest < SETTLED || left <= 1) done.moving else apart(done.moving, left - 1)
        }
        return if (fresh.isEmpty()) fresh else apart(run(fresh, DAMPING, MAX_STEPS), MAX_STEPS)
    }

    /** In the last steps nodes are only kept this far apart, in times the distance at which they touch: no further than they need to be read. */
    private const val APART = 1.15

    private fun step(
        g: Graph,
        moving: Set<Node>,
        among: Set<Node>,
        yields: (Node) -> Boolean,
        origin: (Node) -> Vector2?,
        damping: Double,
        pulling: Boolean = true,
    ): Step {
        EdgeGeometry.recompute(moving.flatMap { g.edgeMap[it].orEmpty() }.toSet())
        // the collision spring keeps nodes twice (times the scale) as far apart as they touch; asked for less, it does less
        val strength = if (pulling) 1.0 else APART / LayoutOptimizer.impl.collisionReach()
        // the nodes are elsewhere at each step: which are near is found anew
        val near = LayoutOptimizer.impl.Colliders(among, LayoutOptimizer.impl.collisionReach(strength))
        val pushes = moving.flatMap { n -> near.of(n).mapNotNull { o -> LayoutOptimizer.impl.computeCollisionSpring(g, n, o, strength)?.let { it to o } } }
        val pulls = if (!pulling) emptyList() else moving.flatMap { n ->
            g.edgeMap[n].orEmpty().mapNotNull { e ->
                val other = if (e.src == n) e.dst else e.src
                if (other != n && other in among) LayoutOptimizer.impl.computeBBSpring(g, n, other, e) else null
            }
        }
        val home = moving.mapNotNull { n ->
            origin(n)?.let { to -> (to - n.position).let { by -> LayoutOptimizer.Spring(n, if (by.length() > ORIGIN_CAP) by.toScale(ORIGIN_CAP) else by) } }
        }
        // what pushes a node off others counts for more than what pulls it somewhere: or many nodes pulled to one place stay on each other
        val mean = { springs: List<LayoutOptimizer.Spring> -> springs.groupBy({ it.n }, { it.v }).mapValues { (_, vs) -> vs.reduce(Vector2::plus) / vs.size } }
        val pushed = mean(pushes.map { it.first })
        val pulled = if (pulling) mean(pulls + home + alike(g, moving, among)) else emptyMap()
        val moves = (pushed.keys + pulled.keys).associateWith { n ->
            val off = pushed[n]
            val to = pulled[n] ?: Vector2.Zero
            (if (off == null) to else off + to * PULL_WHEN_PUSHED) * damping
        }
        moves.forEach { (n, by) -> n.position = n.position + by }
        if (g.attachmentOwners.isNotEmpty()) Attachments.reglue(g)
        val woken = pushes.map { it.second }.filter { it !in moving && yields(it) }
        return Step(moving + woken, moves.values.maxOfOrNull { it.length() } ?: 0.0)
    }

    /** [LayoutOptimizer.impl.computeUniformOutSprings] among the nodes seen: the edges out of one node to as long as each other, for the children that move. */
    private fun alike(g: Graph, moving: Set<Node>, among: Set<Node>): List<LayoutOptimizer.Spring> {
        val parents = moving.flatMap { n -> g.findInEdges(n).map { it.src } }.filter { it in among }.toSet()
        return parents.flatMap { parent ->
            val children = g.findOutEdges(parent).map { it.dst }.filter { it in among && it != parent }.distinct()
            val lengths = children.map { (it.position - parent.position).length() }
            val average = if (children.size < 2) 0.0 else lengths.average()
            children.filter { it in moving }.mapNotNull { child ->
                val offset = child.position - parent.position
                if (children.size < 2 || offset.length() < 0.001) null
                else LayoutOptimizer.Spring(child, (parent.position + offset.toUnit() * average - child.position) * 0.5)
            }
        }
    }
}
