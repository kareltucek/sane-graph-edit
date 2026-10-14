package graph_tools

import Graph
import utils.Vector2
import kotlin.math.pow

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

    /** What a step did: who moves now, the furthest one went, and how many it took ([step]'s deadline). */
    private data class Step(val moving: Set<Node>, val furthest: Double, val taken: Int)

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
     *
     * With [budget] a step takes no longer than that many
     * milliseconds: the nodes it does not get to wait, and the next
     * step takes all in an order of chance, so that over a few steps
     * each gets its turn. The nodes then come to rest later, and the
     * pictures keep coming. With [budget] the run also goes by the
     * clock: what the springs ask for is the move of a
     * [LayoutOptimizer.WINDOW_MS], and a step moves the part of it for
     * the time since the last one, so the nodes go as fast however
     * often a picture is drawn; without, a step is a whole window.
     */
    class Run(
        private val g: Graph,
        private val among: Set<Node>,
        private val weight: (Node) -> Double,
        private val close: Boolean = false,
        private val budget: Long? = null,
        private val clock: () -> Long = { utils.Platform.nowMillis() },
        private val origin: (Node) -> Vector2? = { null },
    ) {
        var moving: Set<Node> = emptySet()
            private set
        private var damping = DAMPING
        private var left = MAX_STEPS
        private var pulling = true
        private var resting = true
        private var cut = false
        private val near = Near(among, rebuiltEachStep = g.attachmentOwners.isNotEmpty())

        /**
         * The round in progress: steps cut short ([budget]) make up one
         * between them, once they have taken as many nodes as move. The
         * limit of steps and the cooling count rounds; whether the nodes
         * rest is told at the end of one.
         */
        private var covered = 0
        private var furthest = 0.0
        private var grew = false

        /** [nodes] join those that move, and all start anew: where they are going changed. */
        fun stir(nodes: Set<Node>) {
            moving = moving + nodes.filter { weight(it).isFinite() }
            damping = DAMPING
            left = MAX_STEPS
            pulling = true
            resting = moving.isEmpty()
            covered = 0
            furthest = 0.0
            grew = false
        }

        /**
         * One step; false when the nodes rest. With all springs until
         * nothing moves any more, then, with nothing pulling, off each
         * other those still on each other (many nodes pulled to one
         * place); at most [MAX_STEPS] of each.
         */
        fun step(): Boolean {
            if (!resting) {
                val now = clock()
                // against the clock, the part of a window that has passed since the last step; else a whole window a step
                val share = if (budget == null) 1.0 else ((now - (lastStep ?: now)).toDouble() / LayoutOptimizer.WINDOW_MS).coerceIn(0.0, 1.0)
                lastStep = now
                // after a step cut short, those still waiting first; when all have had their turn, all again by chance
                val order = if (cut) waiting.filter { it in moving }.ifEmpty { moving.shuffled(chance) } else moving.toList()
                // how long the work may take is real time, whatever [clock] says
                val deadline = budget?.let { utils.Platform.nowMillis() + it }
                val done = step(g, moving, order, among, near, weight, origin, damping * share, pulling, close, deadline)
                waiting = order.drop(done.taken)
                cut = cut || waiting.isNotEmpty()
                covered += done.taken
                // how far it would have gone in a window: a short step is no sign of rest
                if (share > 0) furthest = maxOf(furthest, done.furthest / share)
                grew = grew || done.moving.size != moving.size
                moving = done.moving
                if (budget != null && pulling) damping *= COOLING.pow(share)
                if (covered >= moving.size) endRound()
            }
            return !resting
        }

        /** The order of turns when steps are cut short: by chance, but the same each time. */
        private val chance = kotlin.random.Random(0)

        /** The nodes a step cut short did not get to, in turn; once steps have been cut, [cut] stays so. */
        private var waiting: List<Node> = emptyList()

        /** When the last step was taken, against the clock ([budget]); null before the first. */
        private var lastStep: Long? = null

        private fun endRound() {
            val rests = furthest < SETTLED && !grew
            covered = 0
            furthest = 0.0
            grew = false
            left -= 1
            when {
                // against the clock it cools with time ([step]), else a round at a time
                !rests && left > 0 -> damping *= if (pulling && budget == null) COOLING else 1.0
                pulling -> {
                    pulling = false
                    damping = DAMPING
                    left = MAX_STEPS
                }
                else -> resting = true
            }
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
     */
    fun subtree(g: Graph, roots: Set<Node>, among: Set<Node>): Set<Node> {
        tailrec fun grow(inside: Set<Node>): Set<Node> {
            val more = inside.flatMap { n -> g.findOutEdges(n).map { it.dst } }
                .filter { it in among && it !in inside && g.findInEdges(it).all { e -> e.src in inside || e.src !in among } }
                .toSet()
            return if (more.isEmpty()) inside else grow(inside + more)
        }
        return grow(roots) - roots
    }

    /** In the last steps nodes are only kept this far apart, in times the distance at which they touch: no further than they need to be read. */
    private const val APART = 1.15

    /**
     * One step of the nodes of [order] ([moving], in the order to take
     * them): what the springs ask of each, then all moved by it. With
     * [deadline] (a [utils.Platform.nowMillis]) the nodes not reached
     * by then wait for the next step; at least one is taken.
     */
    private fun step(
        g: Graph,
        moving: Set<Node>,
        order: List<Node>,
        among: Set<Node>,
        near: Near,
        weight: (Node) -> Double,
        origin: (Node) -> Vector2?,
        damping: Double,
        pulling: Boolean,
        close: Boolean,
        deadline: Long?,
    ): Step {
        EdgeGeometry.recompute(moving.flatMap { g.edgeMap[it].orEmpty() }.toSet())
        // the collision spring keeps nodes twice (times the scale) as far apart as they touch; asked for less, it does less
        val strength = if (pulling && !close) 1.0 else APART / LayoutOptimizer.impl.collisionReach()
        // which are near is looked up anew at each step: the nodes are elsewhere
        val nearOf = near.forStep(LayoutOptimizer.impl.collisionReach(strength))
        // of two on each other each takes the other's part of their weights: all of it from a fixed one, half from one as heavy
        val share = { n: Node, o: Node -> weight(o).let { w -> if (w.isInfinite()) 1.0 else w / (weight(n) + w) } }
        val mean = { vs: List<Vector2> -> if (vs.isEmpty()) null else vs.reduce(Vector2::plus) / vs.size }
        val moves = mutableMapOf<Node, Vector2>()
        val woken = mutableSetOf<Node>()
        var taken = 0
        while (taken < order.size && (deadline == null || taken == 0 || utils.Platform.nowMillis() < deadline)) {
            val n = order[taken]
            taken += 1
            val pushes = nearOf(n).mapNotNull { o -> LayoutOptimizer.impl.computeCollisionSpring(g, n, o, strength)?.let { it.v * share(n, o) to o } }
            woken += pushes.map { it.second }.filter { it !in moving && weight(it).isFinite() }
            val pulls = if (!pulling) emptyList() else {
                val edges = g.edgeMap[n].orEmpty().mapNotNull { e ->
                    val other = if (e.src == n) e.dst else e.src
                    if (other != n && other in among) LayoutOptimizer.impl.computeBBSpring(g, n, other, e).v else null
                }
                val home = origin(n)?.let { to -> (to - n.position).let { by -> if (by.length() > ORIGIN_CAP) by.toScale(ORIGIN_CAP) else by } }
                edges + listOfNotNull(home) + alike(g, n, among)
            }
            // what pushes a node off others counts for more than what pulls it somewhere: or many nodes pulled to one place stay on each other
            val off = mean(pushes.map { it.first })
            val to = mean(pulls)
            val by = off?.let { it + (to ?: Vector2.Zero) * PULL_WHEN_PUSHED } ?: to
            if (by != null) moves[n] = by * damping
        }
        moves.forEach { (n, by) ->
            near.moving(n)
            n.position = n.position + by
        }
        if (g.attachmentOwners.isNotEmpty()) Attachments.reglue(g)
        return Step(moving + woken, moves.values.maxOfOrNull { it.length() } ?: 0.0, taken)
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

    /** [LayoutOptimizer.impl.computeUniformOutSprings] among the nodes seen: the edges out of each parent of [child] as long as each other. */
    private fun alike(g: Graph, child: Node, among: Set<Node>): List<Vector2> =
        g.findInEdges(child).map { it.src }.filter { it in among && it != child }.distinct().mapNotNull { parent ->
            val children = g.findOutEdges(parent).map { it.dst }.filter { it in among && it != parent }.distinct()
            val offset = child.position - parent.position
            if (children.size < 2 || offset.length() < 0.001) null
            else (parent.position + offset.toUnit() * children.map { (it.position - parent.position).length() }.average() - child.position) * 0.5
        }
}
