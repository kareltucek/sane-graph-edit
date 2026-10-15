package graph_tools

import Graph
import graph_tools.LayoutTimes.Part
import utils.Vector2
import utils.Vector3
import kotlin.math.exp
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
/** Nodes on their way, a step at a time ([Settling.Run], [Multilevel.Run]); [step] is false once they rest. */
interface Stepping {
    fun step(): Boolean
}

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
     * pulled back to, asked at each step; null for none. Those of
     * [firm] are tugged there gently but without letting up, on top
     * of the springs on them: in [FOLLOW_MS] they are there, a
     * generation as well as the next, if nothing keeps them off.
     *
     * With [budget] a step takes no longer than that many
     * milliseconds: the nodes it does not get to wait, and the next
     * step takes all in an order of chance (by [rank], the highest
     * first, when there is one), so that over a few steps each gets its
     * turn. The nodes then come to rest later, and the
     * pictures keep coming. With a [clock] the run goes by it: what
     * the springs ask for is the move of a
     * [LayoutOptimizer.WINDOW_MS], and a step moves the part of it for
     * the time since the last one, so the nodes go as fast however
     * often a picture is drawn; without, a step is a whole window.
     */
    class Run(
        private val g: Graph,
        private val among: Set<Node>,
        private val weight: (Node) -> Double,
        private val close: Boolean = false,
        private val firm: Set<Node> = emptySet(),
        private val budget: Long? = null,
        private val clock: (() -> Long)? = null,
        private val depth: Boolean = false,
        private val rank: ((Node) -> Double)? = null,
        private val origin: (Node) -> Vector3? = { null },
    ) : Stepping {
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

        /**
         * [nodes] join those that move, and all start anew: where they
         * are going changed. A run stirred first goes by the [clock] as
         * if it had begun [past] milliseconds ago: its first step moves
         * at once.
         */
        fun stir(nodes: Set<Node>, past: Double = 0.0) {
            moving = moving + nodes.filter { weight(it).isFinite() }
            damping = DAMPING
            left = MAX_STEPS
            pulling = true
            resting = moving.isEmpty()
            covered = 0
            furthest = 0.0
            grew = false
            // the first step moves for the time since the run began
            if (lastStep == null) lastStep = clock?.invoke()?.minus(past.toLong())
        }

        /**
         * One step; false when the nodes rest. With all springs until
         * nothing moves any more, then, with nothing pulling, off each
         * other those still on each other (many nodes pulled to one
         * place); at most [MAX_STEPS] of each.
         */
        override fun step(): Boolean {
            if (!resting) {
                val start = utils.Platform.clockMillis()
                val now = clock?.invoke() ?: 0L
                val previous = lastStep ?: now
                lastStep = now
                // against the clock, the part of a window that has passed since the last step; else a whole window a step
                val share = if (clock == null) 1.0 else ((now - previous).toDouble() / LayoutOptimizer.WINDOW_MS).coerceIn(0.0, 1.0)
                // after a step cut short, those still waiting first; when all have had their turn, all again by chance
                // with a [rank], the most important first every time, so that a step cut short has moved them
                val fresh = { rank?.let { r -> moving.sortedByDescending(r) } ?: if (cut) moving.shuffled(chance) else moving.toList() }
                val order = LayoutTimes.time(Part.Order) { if (cut) waiting.filter { it in moving }.ifEmpty { fresh() } else fresh() }
                // how long the work may take is real time, whatever [clock] says
                val deadline = budget?.let { utils.Platform.nowMillis() + it }
                val done = step(g, moving, order, among, near, weight, origin, firm, damping, share, pulling, close, depth, down(g, depth), deadline)
                waiting = order.drop(done.taken)
                cut = cut || waiting.isNotEmpty()
                covered += done.taken
                furthest = maxOf(furthest, done.furthest)
                grew = grew || done.moving.size != moving.size
                moving = done.moving
                if (clock != null && pulling) damping *= COOLING.pow(share)
                if (covered >= moving.size) endRound()
                LayoutTimes.step(among.size, utils.Platform.clockMillis() - start)
            }
            return !resting
        }

        /** The order of turns when steps are cut short: by chance, but the same each time. */
        private val chance = kotlin.random.Random(0)

        /** The nodes a step cut short did not get to, in turn; once steps have been cut, [cut] stays so. */
        private var waiting: List<Node> = emptyList()

        /** When the last step was taken, by the [clock]; null before the first. */
        private var lastStep: Long? = null

        private fun endRound() {
            // the tug on a firm node is small when it is near: it is there only when it is there
            val arrived = !pulling || firm.all { n -> origin(n)?.let { ((if (depth) it else Vector3(it.xy, n.point.z)) - n.point).length() < ARRIVED } ?: true }
            val rests = furthest < SETTLED && !grew && arrived
            covered = 0
            furthest = 0.0
            grew = false
            left -= 1
            when {
                // against the clock it cools with time ([step]), else a round at a time
                !rests && left > 0 -> damping *= if (pulling && clock == null) COOLING else 1.0
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
     * once ([Run]); in space with [depth], else in the plane. [origin]
     * is in the plane: depth is not pulled anywhere. The nodes are
     * moved in place; those that took part are returned.
     */
    fun settle(
        g: Graph,
        fresh: Set<Node>,
        among: Set<Node>,
        weight: (Node) -> Double,
        depth: Boolean = false,
        origin: (Node) -> Vector2? = { null },
    ): Set<Node> =
        Run(g, among, weight, depth = depth, origin = { n -> origin(n)?.let { Vector3(it, n.point.z) } }).also {
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

    /** A firm node this near its origin is there. */
    private const val ARRIVED = 2.0

    /** In how many milliseconds a firm node ([Run]) has come 95 % of the way to its origin, if nothing else moves it. */
    private const val FOLLOW_MS = 2000.0

    /** The share of the way to its origin a firm node goes in a [LayoutOptimizer.WINDOW_MS]: 95 % (e^-3 left) in [FOLLOW_MS]. */
    private val FOLLOW_PER_WINDOW = 1 - exp(-3 * LayoutOptimizer.WINDOW_MS / FOLLOW_MS)

    /** In the last steps nodes are only kept this far apart, in times the distance at which they touch: no further than they need to be read. */
    private const val APART = 1.15

    /**
     * One step of the nodes of [order] ([moving], in the order to take
     * them): what the springs ask of each, then all moved by it. With
     * [deadline] (a [utils.Platform.nowMillis]) the nodes not reached
     * by then wait for the next step; at least one is taken. The nodes
     * move for [passed] of a window (times how many fewer were taken);
     * how far they went is told per window.
     */
    private fun step(
        g: Graph,
        moving: Set<Node>,
        order: List<Node>,
        among: Set<Node>,
        near: Near,
        weight: (Node) -> Double,
        origin: (Node) -> Vector3?,
        firm: Set<Node>,
        damping: Double,
        passed: Double,
        pulling: Boolean,
        close: Boolean,
        depth: Boolean,
        down: Vector3,
        deadline: Long?,
    ): Step {
        LayoutTimes.time(Part.EdgePoints) { EdgeGeometry.recompute(moving.flatMap { g.edgeMap[it].orEmpty() }.toSet()) }
        // the collision spring keeps nodes twice (times the scale) as far apart as they touch; asked for less, it does less
        val strength = if (pulling && !close) 1.0 else APART / LayoutOptimizer.impl.collisionReach()
        // which are near is looked up anew at each step: the nodes are elsewhere
        val nearOf = LayoutTimes.time(Part.Index) { near.forStep(LayoutOptimizer.impl.collisionReach(strength) * (if (depth) Space.SPARSER else 1.0)) }
        // of two on each other each takes the other's part of their weights: all of it from a fixed one, half from one as heavy
        val share = { n: Node, o: Node -> weight(o).let { w -> if (w.isInfinite()) 1.0 else w / (weight(n) + w) } }
        val mean = { vs: List<Vector3> -> if (vs.isEmpty()) null else vs.reduce(Vector3::plus) / vs.size }
        // where a node is for the springs: in space, or, when depth is not to change, in the plane
        val place = { n: Node -> if (depth) n.point else Vector3(n.position, 0.0) }
        val flat = { v: Vector2 -> Vector3(v, 0.0) }
        val moves = mutableMapOf<Node, Vector3>()
        val woken = mutableSetOf<Node>()
        var taken = 0
        val springs = utils.Platform.clockMillis()
        while (taken < order.size && (deadline == null || taken == 0 || utils.Platform.nowMillis() < deadline)) {
            val n = order[taken]
            taken += 1
            val pushes = nearOf(n).mapNotNull { o ->
                val push = if (depth) Space.collision(n, o, strength) else LayoutOptimizer.impl.computeCollisionSpring(g, n, o, strength)?.v?.let(flat)
                push?.let { it * share(n, o) to o }
            }
            woken += pushes.map { it.second }.filter { it !in moving && weight(it).isFinite() }
            // in space, nodes on each other also go apart across the plane they are in, each its own way ([Space.nudge])
            val nudges = if (depth) pushes.mapNotNull { (push, o) -> Space.nudge(g, n, o, push, among) } else emptyList()
            val home = if (pulling) origin(n)?.let { (if (depth) it else Vector3(it.xy, 0.0)) - place(n) } else null
            val pulls = if (!pulling) emptyList() else {
                val edges = g.edgeMap[n].orEmpty().mapNotNull { e ->
                    val other = if (e.src == n) e.dst else e.src
                    when {
                        other == n || other !in among -> null
                        depth -> Space.edge(g, n, other)
                        else -> flat(LayoutOptimizer.impl.computeBBSpring(g, n, other, e).v)
                    }
                }
                val capped = home?.takeIf { n !in firm }?.let { by -> if (by.length() > ORIGIN_CAP) by.toScale(ORIGIN_CAP) else by }
                // away from where the parent came from, carrying depth along the flow once there is some
                val away = Space.antigravity(g, n, among, place)
                edges + listOfNotNull(capped) + alike(g, n, among, place) + away + gravity(g, n, among, down, place)
            }
            // what pushes a node off others counts for more than what pulls it somewhere: or many nodes pulled to one place stay on each other
            val off = mean(pushes.map { it.first } + nudges)
            val to = mean(pulls)
            val loose = off?.let { it + (to ?: Vector3.Zero) * PULL_WHEN_PUSHED } ?: to
            // a firm one is tugged to its origin on top of the rest, however far, and not less as the others cool: a share of
            // the way each window, so that it is there in [FOLLOW_MS]; less while something pushes it, or it stays on what
            // is in its place
            val tug = home?.takeIf { n in firm }?.let { it * FOLLOW_PER_WINDOW * (if (off == null) 1.0 else PULL_WHEN_PUSHED) }
            val by = loose?.let { it * damping + (tug ?: Vector3.Zero) } ?: tug
            if (by != null) moves[n] = by
        }
        LayoutTimes.add(Part.Springs, utils.Platform.clockMillis() - springs)
        // cut short, those taken move for those that were not, as many times further as they are fewer (no matter that
        // it may swing): the nodes go as fast however many there are
        val windows = passed * (if (taken < order.size) order.size.toDouble() / taken else 1.0)
        LayoutTimes.time(Part.Apply) {
            moves.forEach { (n, by) ->
                near.moving(n)
                // moving for others that were not taken, no node goes further than its own size in a step (or than it would
                // for itself): none is flung across the graph
                val far = maxOf(n.cache.shapeBounds.length(), by.length() * passed)
                val go = (if (depth) by * windows else Vector3(by.xy * windows, 0.0)).let { if (it.length() > far) it.toScale(far) else it }
                n.point = n.point + go
            }
        }
        if (g.attachmentOwners.isNotEmpty()) LayoutTimes.time(Part.Reglue) { Attachments.reglue(g) }
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
            return { n -> now.nearest(n, COLLIDING, slack) }
        }
    }

    /** A node is pushed by at most this many of those near it, the nearest ([Near]). */
    private const val COLLIDING = 16

    /** How far the nodes may have moved before [Near] makes its index anew. */
    private const val REBUILD = 60.0

    /** [LayoutOptimizer.impl.computeUniformOutSprings] among the nodes seen: the edges out of each parent of [child] as long as each other. */
    private fun alike(g: Graph, child: Node, among: Set<Node>, place: (Node) -> Vector3): List<Vector3> =
        g.findInEdges(child).map { it.src }.filter { it in among && it != child }.distinct().mapNotNull { parent ->
            val children = g.findOutEdges(parent).map { it.dst }.filter { it in among && it != parent }.distinct()
            val offset = place(child) - place(parent)
            if (children.size < 2 || offset.length() < 0.001) null
            else (place(parent) + offset.toUnit() * children.map { (place(it) - place(parent)).length() }.average() - place(child)) * 0.5
        }

    /**
     * Gravity: the way the graph's edges go, on average (from parent to
     * child; in space when [depth]), as a unit; zero for none. A child
     * is pulled to lie that way from its parent ([gravity]), so a graph
     * laid out downwards, or to the right, goes on so.
     */
    fun down(g: Graph, depth: Boolean): Vector3 =
        g.edges.filter { it.src != it.dst }
            .map { e -> (if (depth) e.dst.point - e.src.point else Vector3(e.dst.position - e.src.position, 0.0)).toUnit() }
            .fold(Vector3.Zero, Vector3::plus)
            .toUnit()

    /** How much of the way to lying [down] from its parents a child is pulled in a window. */
    private const val GRAVITY = 0.1

    /** The pull on [child] to lie [down] from each of its parents among [among], as far from it as it is now. */
    private fun gravity(g: Graph, child: Node, among: Set<Node>, down: Vector3, place: (Node) -> Vector3): List<Vector3> =
        if (down == Vector3.Zero) emptyList()
        else g.findInEdges(child).map { it.src }.filter { it in among && it != child }.distinct().map { parent ->
            val from = place(parent)
            (from + down * (place(child) - from).length() - place(child)) * GRAVITY
        }

    /**
     * The springs in space ([Vector3]), for a view in 3D mode: a node is
     * a flat card facing the viewer, as wide and high as its shape and
     * [THICKNESS] of its smaller side deep, so that two one behind the
     * other keep apart too.
     */
    internal object Space {
        /** How deep a node is reckoned, in parts of its smaller side. */
        const val THICKNESS = 0.5

        /** In space nodes in front hide those behind: they are kept this many times further apart, by collisions and by edges. */
        const val SPARSER = 3.0

        /** How far from its middle [n] reaches the way [way] goes. */
        fun reach(n: Node, way: Vector3): Double {
            val length = way.length()
            val across = way.xy.length()
            val inPlane = if (across < 1e-9) 0.0 else n.cache.shape.connectionPoint(way.xy / across, n).length()
            // a cluster ([Multilevel]) is a ball, not a card
            val deep = minOf(n.cache.shapeBounds.x, n.cache.shapeBounds.y) * (if (Multilevel.isCluster(n)) 1.0 else THICKNESS) / 2
            // between the outline across and the depth, as the way leans
            return if (length < 1e-9) inPlane else kotlin.math.sqrt((inPlane * across / length).let { it * it } + (deep * way.z / length).let { it * it })
        }

        /** [LayoutOptimizer.impl.computeCollisionSpring] in space: [n] off [o] to as far as they touch, times the scale and [strength]. */
        fun collision(n: Node, o: Node, strength: Double): Vector3? {
            val way = o.point - n.point
            val wanted = (reach(n, way) + reach(o, -way)) * LayoutOptimizer.impl.collisionReach(strength) * SPARSER
            return if (way.length() < wanted) o.point - way.toUnit() * wanted - n.point else null
        }

        /** [LayoutOptimizer.impl.computeBBSpring] in space: [n] to as far from [other] as the edge between them wants. */
        fun edge(g: Graph, n: Node, other: Node): Vector3 {
            val way = other.point - n.point
            val wanted = (reach(n, way) + reach(other, -way)) * LayoutOptimizer.springScale * LayoutOptimizer.impl.degFactor(g, n, other) * SPARSER
            return (way - way.toUnit() * wanted) / 2.0
        }

        /** How much of a push across the plane two nodes on each other also get, each its own way. */
        const val NUDGE = 0.3

        /**
         * [n], pushed off [o] by [push], also goes across: along the
         * vector product of the way to [o] and the way it came from its
         * parent, so that two children of one parent go to opposite
         * sides of the plane they are in. A node without a parent goes
         * straight away from the viewer or towards, by which of the two
         * comes first.
         */
        fun nudge(g: Graph, n: Node, o: Node, push: Vector3, among: Set<Node>): Vector3? {
            val way = o.point - n.point
            val parent = g.findInEdges(n).map { it.src }.firstOrNull { it in among && it != n }
            val across = parent?.let { cross(way, n.point - it.point) }?.takeIf { it.length() > 1e-9 }
                ?: Vector3(0.0, 0.0, if (n.id < o.id) -1.0 else 1.0)
            return across.toScale(push.length() * NUDGE).takeIf { push.length() > 0 }
        }

        fun cross(a: Vector3, b: Vector3) = Vector3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

        /**
         * The optimizer's gravity spring ([LayoutOptimizer.impl.computeGravitySpring]),
         * where [place] has the nodes (in space or in the plane): [child]
         * pushed on the way its parent came, kept as far from the parent
         * as it is.
         */
        fun antigravity(g: Graph, child: Node, among: Set<Node>, place: (Node) -> Vector3): List<Vector3> =
            g.findInEdges(child).filter { it.src in among && it.src != child }.mapNotNull { e ->
                val parent = e.src
                val came = g.findInEdges(parent).map { (place(parent) - place(it.src)).toUnit() }.fold(Vector3.Zero, Vector3::plus).toUnit()
                val dir = (place(child) - place(parent)).toUnit()
                val factor = if (g.findOutEdges(parent).size < 3) (1 - came.dot(dir)) / 2 else 0.0
                val push = (came * factor).toUnit() * ANTIGRAVITY
                (push + (-dir).toScale(push.length())).takeIf { factor > 0 }
            }

        /** How hard [antigravity] pushes, as the optimizer's gravity spring. */
        const val ANTIGRAVITY = 10.0
    }
}
