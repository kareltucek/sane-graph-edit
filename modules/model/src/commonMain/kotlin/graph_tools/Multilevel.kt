package graph_tools

import Graph
import utils.Vector2
import utils.Vector3
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The settling of a whole graph by levels ([docs/developer/springs.md] §7): the nodes are contracted into solar
 * systems, those into systems of theirs, until few are left; the springs settle the top level, every move of a system
 * carried to all it holds, then the level below, down to the nodes.
 *
 * A node's mass is the room it wants: the furthest its springs keep others, from its middle, to the power of the
 * dimensions. A system weighs [PACKING] times its members together and stands as one round node that wants as much.
 */
object Multilevel {
    /** A system wants this many times the room of its members together: they do not fill all the room between them. */
    private const val PACKING = 1.5

    /** Levels are made until one has no more nodes than this. */
    private const val SMALL = 50

    /** A level that keeps more than this part of the nodes of the one below is not made: the graph does not contract. */
    private const val SHRINKS = 0.8

    /** The attribute that tells a system's stand-in from a node. */
    private const val CLUSTER = "sge-cluster"

    fun isCluster(n: Node): Boolean = n.attributes.other.containsKey(CLUSTER)

    /** One level: its [nodes] in [g], what each weighs, and, above the nodes, what each holds of the level below and which of those is its sun. */
    private class Level(val g: Graph, val nodes: List<Node>, val mass: Map<Node, Double>, val members: Map<Node, List<Node>>, val sun: Map<Node, Node>)

    /** A ball drawn around a system while it settles, or a small one around a node that is the sun of one of the lowest ([sun]). */
    data class Shell(val at: Vector3, val radius: Double, val sun: Boolean)

    /** A sun's ball is this many times as far as the corners of the node. */
    private const val AROUND_SUN = 1.15

    /** How far a ball (in 3D) or a disc reaches that has the room [mass]. */
    private fun radiusOf(mass: Double, depth: Boolean) = if (depth) (3 * mass / (4 * PI)).pow(1.0 / 3) else sqrt(mass / PI)

    private fun dimensions(depth: Boolean) = if (depth) 3 else 2

    private fun sparser(depth: Boolean) = if (depth) Settling.Space.SPARSER else 1.0

    /** The room [n] wants: its reach (as a square, in 3D a card, of its area) times the furthest of its springs. */
    private fun mass(g: Graph, n: Node, depth: Boolean): Double {
        val half = n.cache.shapeBounds / 2.0
        val deep = minOf(half.x, half.y) * Settling.Space.THICKNESS
        val reach = if (depth) (half.x * half.y * deep).pow(1.0 / 3) else sqrt(half.x * half.y)
        val degree = g.edgeMap[n].orEmpty().size.toDouble()
        val spring = maxOf(LayoutOptimizer.impl.collisionReach(), LayoutOptimizer.springScale * LayoutOptimizer.impl.degFactor(degree))
        // a node not measured yet has no size: it still weighs something
        return (reach * spring * sparser(depth)).pow(dimensions(depth)).coerceAtLeast(1.0)
    }

    private fun neighbours(level: Level, among: Set<Node>): Map<Node, List<Node>> =
        level.nodes.associateWith { n ->
            level.g.edgeMap[n].orEmpty().map { if (it.src == n) it.dst else it.src }.filter { it != n && it in among }.distinct()
        }

    /**
     * The level above [level]: its solar systems. Suns by importance (edges times mass, the first of equals first), no
     * two within 2 edges of each other; each other node joins the lightest system next to it (of equals the nearest
     * sun), first those next to a sun, then those next to one of these.
     */
    private fun contract(level: Level, depth: Boolean): Level {
        val among = level.nodes.toSet()
        val near = neighbours(level, among)
        val order = level.nodes.sortedByDescending { near.getValue(it).size * level.mass.getValue(it) }
        val blocked = mutableSetOf<Node>()
        val sunOf = linkedMapOf<Node, Node>()
        order.forEach { n ->
            if (n !in blocked) {
                sunOf[n] = n
                blocked += n
                near.getValue(n).forEach { m -> blocked += m; blocked += near.getValue(m) }
            }
        }
        val held = sunOf.keys.associateWith { level.mass.getValue(it) }.toMutableMap()
        // the lightest of the systems [n] is next to, of equals the one whose sun is nearest
        val join = { n: Node, by: List<Node> ->
            by.minWithOrNull(compareBy({ held.getValue(sunOf.getValue(it)) }, { (sunOf.getValue(it).point - n.point).length() }))?.let { m ->
                val sun = sunOf.getValue(m)
                sunOf[n] = sun
                held[sun] = held.getValue(sun) + level.mass.getValue(n)
            }
        }
        val suns = sunOf.keys.toSet()
        order.filter { it !in sunOf }.forEach { n -> join(n, near.getValue(n).filter { it in suns }) }
        order.filter { it !in sunOf }.forEach { n -> join(n, near.getValue(n).filter { it in sunOf }) }
        // none is left but by a mistake: such a one is a system of its own
        order.filter { it !in sunOf }.forEach { n ->
            sunOf[n] = n
            held[n] = level.mass.getValue(n)
        }
        val systems = sunOf.entries.groupBy({ it.value }, { it.key })
        val upper = Graph()
        val stand = systems.mapValues { (sun, members) ->
            val mass = PACKING * held.getValue(sun)
            // its springs keep others as far as its room: the reach that, times the collision spring's, is that
            val reach = mass.pow(1.0 / dimensions(depth)) / (LayoutOptimizer.impl.collisionReach() * sparser(depth))
            val weights = members.map { level.mass.getValue(it) }
            Node(Vector2.Zero).apply {
                attributes.other[CLUSTER] = ""
                cache.shape = NodeShape.Oval.impl
                cache.shapeBounds = Vector2(2 * reach, 2 * reach)
                point = members.zip(weights).fold(Vector3.Zero) { at, (m, w) -> at + m.point * w } / weights.sum()
            } to mass
        }
        stand.values.forEach { upper.add(it.first) }
        val joined = mutableSetOf<Pair<Node, Node>>()
        level.g.edges.filter { it.src in among && it.dst in among }.forEach { e ->
            val (a, b) = stand.getValue(sunOf.getValue(e.src)).first to stand.getValue(sunOf.getValue(e.dst)).first
            if (a != b && joined.add(a to b) && joined.add(b to a)) upper.add(Edge(a, b))
        }
        return Level(
            upper,
            stand.values.map { it.first },
            stand.values.associate { it },
            stand.entries.associate { (sun, it) -> it.first to systems.getValue(sun) },
            stand.entries.associate { (sun, it) -> it.first to sun },
        )
    }

    /**
     * `o` on the whole graph: [shown] of [g] by levels, from the top down, a step at a time; the nodes' own level with
     * [weight] and [rank], the levels above by their masses. As [Settling.Run] for [budget], [clock], [depth] and [past].
     */
    class Run(
        private val g: Graph,
        shown: Set<Node>,
        private val weight: (Node) -> Double,
        private val rank: (Node) -> Double,
        private val budget: Long?,
        private val clock: (() -> Long)?,
        private val depth: Boolean,
        past: Double,
    ) : Stepping {
        private val levels: List<Level> = run {
            val nodes = Level(g, shown.toList(), shown.associateWith { mass(g, it, depth) }, emptyMap(), emptyMap())
            generateSequence(nodes) { below -> if (below.nodes.size <= SMALL) null else contract(below, depth) }
                .zipWithNext()
                .takeWhile { (below, above) -> above.nodes.size <= below.nodes.size * SHRINKS }
                .fold(listOf(nodes)) { made, (_, above) -> made + above }
        }

        private var at = levels.size - 1
        private var run = runAt(at, past)

        private fun runAt(i: Int, past: Double): Settling.Run {
            val level = levels[i]
            val among = level.nodes.toSet()
            val run = if (i == 0) Settling.Run(g, among, weight, budget = budget, clock = clock, depth = depth, rank = rank)
            else level.mass.let { m ->
                val edges = { n: Node -> level.g.edgeMap[n].orEmpty().size.toDouble() }
                Settling.Run(level.g, among, m::getValue, budget = budget, clock = clock, depth = depth, rank = { edges(it) * m.getValue(it) })
            }
            return run.also { it.stir(among, past) }
        }

        /**
         * For seeing the systems while they settle: each of every level as a ball with its room, around the middle of what it
         * holds as that is now; each sun of the lowest in a small one just around that node.
         */
        fun shells(): List<Shell> {
            val middles = (1 until levels.size).runningFold(levels[0].nodes.associateWith { it.point }) { below, i ->
                levels[i].nodes.associateWith { n ->
                    val held = levels[i].members.getValue(n)
                    val weights = held.map { levels[i - 1].mass.getValue(it) }
                    held.zip(weights).fold(Vector3.Zero) { at, (m, w) -> at + below.getValue(m) * w } / weights.sum()
                }
            }
            val systems = (1 until levels.size).flatMap { i ->
                levels[i].nodes.map { n -> Shell(middles[i].getValue(n), radiusOf(levels[i].mass.getValue(n), depth), sun = false) }
            }
            val suns = levels.getOrNull(1)?.sun?.values.orEmpty().map { s -> Shell(s.point, s.cache.shapeBounds.length() / 2 * AROUND_SUN, sun = true) }
            return systems + suns
        }

        /** [n] of level [i] moved [by]: so does all it holds, down to the nodes. */
        private fun carry(i: Int, n: Node, by: Vector3) {
            levels[i].members[n].orEmpty().forEach { m ->
                m.point = m.point + by
                if (i > 1) carry(i - 1, m, by)
            }
        }

        override fun step(): Boolean {
            val level = levels[at]
            val before = if (at > 0) level.nodes.associateWith { it.point } else emptyMap()
            val more = run.step()
            if (at > 0) {
                level.nodes.forEach { n -> (n.point - before.getValue(n)).takeIf { it != Vector3.Zero }?.let { carry(at, n, it) } }
                if (g.attachmentOwners.isNotEmpty()) Attachments.reglue(g)
            }
            val down = !more && at > 0
            if (down) {
                at -= 1
                run = runAt(at, LayoutOptimizer.WINDOW_MS)
            }
            return more || down
        }
    }
}
