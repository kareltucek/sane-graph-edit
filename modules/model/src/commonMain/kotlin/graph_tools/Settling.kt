package graph_tools

import Graph
import utils.Vector2

/**
 * The springs of [LayoutOptimizer] on a few nodes only, until they
 * rest: for nodes that just came to be seen in explore mode
 * ([DynamicPlacement]), those around nodes dragged with Shift held
 * ([Run], a step for each picture drawn), and those in their way.
 *
 * Only the nodes given move, and with them every node they run
 * into that is not [FIXED] (it joins them, and so on from it); all
 * else stays where it is and is pushed off. Of two nodes on each
 * other the lighter gives way more ([HEAVY], [USUAL], [LIGHT]). On a node that moves
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
     * How readily a node gives way ([Run]'s weight): of two nodes on
     * each other the lighter is pushed off the heavier, two as heavy
     * share it, and a [FIXED] one does not move at all.
     */
    const val FIXED = Double.POSITIVE_INFINITY
    const val HEAVY = 4.0
    const val USUAL = 2.0
    const val LIGHT = 1.0

    /**
     * Nodes on their way to rest, one step at a time: [stir] says
     * which move, [step] moves them once, as many times as is wanted
     * (one for each picture drawn, so that the user sees them go) or
     * all at once ([toRest]).
     *
     * [among] are the nodes seen; of them a node that one that moves
     * pushes joins it, unless [weight] says it is [FIXED]. With
     * [close] nodes are only kept off each other ([APART]), not as far
     * apart as the collision spring keeps them: nodes the user put
     * near each other stay so, and only those that are on one that
     * moves are pushed, and join it. [origin] is where a node is
     * pulled back to, asked at each step; null for none.
     */
    class Run(
        private val g: Graph,
        private val among: Set<Node>,
        private val weight: (Node) -> Double,
        private val close: Boolean = false,
        private val origin: (Node) -> Vector2? = { null },
    ) {
        var moving: Set<Node> = emptySet()
            private set
        private var damping = DAMPING
        private var left = MAX_STEPS
        private var pulling = true
        private var resting = true
        private val near = Near(among, rebuiltEachStep = g.attachmentOwners.isNotEmpty())

        /** [nodes] join those that move, and all start anew: where they are going changed. */
        fun stir(nodes: Set<Node>) {
            moving = moving + nodes.filter { weight(it).isFinite() }
            damping = DAMPING
            left = MAX_STEPS
            pulling = true
            resting = moving.isEmpty()
        }

        /**
         * One step; false when the nodes rest. With all springs until
         * nothing moves any more, then, with nothing pulling, off each
         * other those still on each other (many nodes pulled to one
         * place); at most [MAX_STEPS] of each.
         */
        fun step(): Boolean {
            if (!resting) {
                val done = step(g, moving, among, near, weight, origin, damping, pulling, close)
                val rests = done.furthest < SETTLED && done.moving.size == moving.size
                moving = done.moving
                left -= 1
                when {
                    !rests && left > 0 -> damping *= if (pulling) COOLING else 1.0
                    pulling -> {
                        pulling = false
                        damping = DAMPING
                        left = MAX_STEPS
                    }
                    else -> resting = true
                }
            }
            return !resting
        }

        fun toRest() {
            while (step()) Unit
        }
    }

    /**
     * Move [fresh] and the nodes in their way until they rest, all at
     * once ([Run]). The nodes are moved in place; those that took part
     * are returned.
     */
    fun settle(
        g: Graph,
        fresh: Set<Node>,
        among: Set<Node>,
        weight: (Node) -> Double,
        origin: (Node) -> Vector2? = { null },
    ): Set<Node> =
        Run(g, among, weight, origin = origin).also {
            it.stir(fresh)
            it.toRest()
        }.moving

    /**
     * The descendants of [roots] that are theirs alone: among the nodes
     * seen ([among]), those all of whose parents that are seen are
     * [roots] or such descendants themselves. [roots] are not in it.
     * Level by level from [roots], and no more than [most]: a level
     * that would make it more is left out, and those below it.
     */
    fun subtree(g: Graph, roots: Set<Node>, among: Set<Node>, most: Int = Int.MAX_VALUE): Set<Node> {
        tailrec fun grow(inside: Set<Node>): Set<Node> {
            val more = inside.flatMap { n -> g.findOutEdges(n).map { it.dst } }
                .filter { it in among && it !in inside && g.findInEdges(it).all { e -> e.src in inside || e.src !in among } }
                .toSet()
            return if (more.isEmpty() || inside.size - roots.size + more.size > most) inside else grow(inside + more)
        }
        return grow(roots) - roots
    }

    /** In the last steps nodes are only kept this far apart, in times the distance at which they touch: no further than they need to be read. */
    private const val APART = 1.15

    private fun step(
        g: Graph,
        moving: Set<Node>,
        among: Set<Node>,
        near: Near,
        weight: (Node) -> Double,
        origin: (Node) -> Vector2?,
        damping: Double,
        pulling: Boolean,
        close: Boolean,
    ): Step {
        EdgeGeometry.recompute(moving.flatMap { g.edgeMap[it].orEmpty() }.toSet())
        // the collision spring keeps nodes twice (times the scale) as far apart as they touch; asked for less, it does less
        val strength = if (pulling && !close) 1.0 else APART / LayoutOptimizer.impl.collisionReach()
        // which are near is looked up anew at each step: the nodes are elsewhere
        val nearOf = near.forStep(LayoutOptimizer.impl.collisionReach(strength))
        val pushes = moving.flatMap { n -> nearOf(n).mapNotNull { o -> LayoutOptimizer.impl.computeCollisionSpring(g, n, o, strength)?.let { it to o } } }
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
        // of two on each other each takes the other's part of their weights: all of it from a fixed one, half from one as heavy
        val share = { n: Node, o: Node -> weight(o).let { w -> if (w.isInfinite()) 1.0 else w / (weight(n) + w) } }
        val pushed = mean(pushes.map { (s, o) -> LayoutOptimizer.Spring(s.n, s.v * share(s.n, o)) })
        val pulled = if (pulling) mean(pulls + home + alike(g, moving, among)) else emptyMap()
        val moves = (pushed.keys + pulled.keys).associateWith { n ->
            val off = pushed[n]
            val to = pulled[n] ?: Vector2.Zero
            (if (off == null) to else off + to * PULL_WHEN_PUSHED) * damping
        }
        moves.forEach { (n, by) ->
            near.moving(n)
            n.position = n.position + by
        }
        if (g.attachmentOwners.isNotEmpty()) Attachments.reglue(g)
        val woken = pushes.map { it.second }.filter { it !in moving && weight(it).isFinite() }
        return Step(moving + woken, moves.values.maxOfOrNull { it.length() } ?: 0.0)
    }

    /**
     * Which of [among] are near a node, at each step: a
     * [LayoutOptimizer.impl.Colliders] made once and looked into wider
     * by how far the nodes moved since, made anew only when that is
     * more than [REBUILD] (or the reach asked for changes). Only the
     * nodes that settle move, and they say so ([moving]) before; with
     * [rebuiltEachStep] (attached nodes, which move with their owners)
     * it is made anew at each step.
     */
    private class Near(private val among: Set<Node>, private val rebuiltEachStep: Boolean) {
        private var colliders: LayoutOptimizer.impl.Colliders? = null
        private var reach = 0.0

        /** Where the nodes that moved since it was made were then. */
        private val madeAt = mutableMapOf<Node, Vector2>()

        fun moving(n: Node) {
            madeAt.getOrPut(n) { n.position }
        }

        fun forStep(reach: Double): (Node) -> List<Node> {
            val drift = madeAt.maxOfOrNull { (n, at) -> (n.position - at).length() } ?: 0.0
            val made = colliders?.takeIf { !rebuiltEachStep && reach == this.reach && drift <= REBUILD }
            val now = made ?: LayoutOptimizer.impl.Colliders(among, reach).also {
                colliders = it
                this.reach = reach
                madeAt.clear()
            }
            // two nodes that both moved may have come nearer by both their ways
            val slack = if (made == null) 0.0 else 2 * drift
            return { n -> now.of(n, slack) }
        }
    }

    /** How far the nodes may have moved before [Near] makes its index anew. */
    private const val REBUILD = 60.0

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
