package graph_tools

import Graph
import graph_tools.LayoutTimes.Part
import utils.Platform
import utils.Vector2
import utils.Vector3
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The settling of a whole graph by levels ([docs/developer/springs.md] §7): the nodes are contracted into solar
 * systems, those into systems of theirs, until few are left; the springs settle all levels at once, the moves of the
 * systems carried down a level at a time, each node going as the systems whose suns are near it, the nearest most.
 *
 * A node's mass is the room it wants: the disc (in 3D the ball) as far as its springs keep others from its middle,
 * by area (volume). A system weighs [Factors.PACKING] times its members together and stands as one round node that wants as much.
 * An edge from a system to another pulls as many times as there are edges that way between the nodes they hold
 * ([Level.count]). Nodes and systems alike are kept apart by the separation spring, which pushes two until they are as
 * many times as far apart as they reach (systems: their rooms) as [Level.separation] says, by how they are joined; nodes
 * at least as far as the collision spring keeps them.
 */
object Multilevel {
    /** The attribute that tells a system's stand-in from a node. */
    private const val CLUSTER = "sge-cluster"

    fun isCluster(n: Node): Boolean = n.attributes.other.containsKey(CLUSTER)

    /**
     * One level: its [nodes] in [g], what each weighs, and, above the nodes, what each holds of the level below, which of
     * those is its sun, and of each edge how many edges of the nodes it stands for ([count]).
     */
    internal class Level(
        val g: Graph,
        val nodes: List<Node>,
        val mass: Map<Node, Double>,
        val members: Map<Node, List<Node>>,
        val sun: Map<Node, Node>,
        private val counts: Map<Edge, Int> = emptyMap(),
    ) {
        fun count(e: Edge): Int = counts[e] ?: 1

        private fun withinTwo(next: (Node) -> List<Node>) = nodes.associateWith { n -> next(n).flatMap { listOf(it) + next(it) }.toSet() - n }

        private val within: Map<Node, Set<Node>> by lazy { withinTwo { n -> g.findOutEdges(n).map { it.dst } } }

        private val around: Map<Node, Set<Node>> by lazy { withinTwo { n -> g.edgeMap[n].orEmpty().map { if (it.src == n) it.dst else it.src } } }

        /** A path of at most two edges leads from one of [a] and [b] to the other. */
        fun related(a: Node, b: Node) = b in within[a].orEmpty() || a in within[b].orEmpty()

        /** A path of at most two edges joins [a] and [b], whichever way its edges go. */
        fun kin(a: Node, b: Node) = b in around[a].orEmpty()

        /** How far apart the separation spring keeps [a] and [b] ([Factors.SEPARATION_KIN]). */
        fun separation(a: Node, b: Node): Double = when {
            related(a, b) -> Factors.SEPARATION_RELATED
            kin(a, b) -> Factors.SEPARATION_KIN
            else -> Factors.SEPARATION_UNRELATED
        }
    }

    /**
     * A ball drawn around a system of [level] (1 the lowest), or, of level 0, around a node, as big as its room; [sun]: a
     * node that is the sun of one of the lowest; [reach]: how far the system reaches at most, laid straight ([measure]).
     */
    data class Shell(val at: Vector3, val radius: Double, val level: Int, val sun: Boolean = false, val reach: Double? = null)

    /** How far a ball (in 3D) or a disc reaches that has the room [mass]. */
    private fun radiusOf(mass: Double, depth: Boolean) = if (depth) (3 * mass / (4 * PI)).pow(1.0 / 3) else sqrt(mass / PI)

    /** The room of a ball (in 3D) or a disc that reaches [radius]. */
    private fun roomOf(radius: Double, depth: Boolean) = if (depth) 4 * PI / 3 * radius.pow(3) else PI * radius * radius

    private fun sparser(depth: Boolean) = if (depth) Factors.SPARSER else 1.0

    /** How far [n] reaches from its middle: as a square (in 3D a card) of its area. */
    private fun reach(n: Node, depth: Boolean): Double {
        val half = n.cache.shapeBounds / 2.0
        val deep = minOf(half.x, half.y) * Settling.Space.THICKNESS
        return if (depth) (half.x * half.y * deep).pow(1.0 / 3) else sqrt(half.x * half.y)
    }

    /** The room [n] wants: the disc (in 3D the ball) as far as its reach times the furthest of its springs. */
    private fun mass(g: Graph, n: Node, depth: Boolean): Double {
        val reach = reach(n, depth)
        val degree = g.edgeMap[n].orEmpty().size.toDouble()
        val spring = maxOf(LayoutOptimizer.impl.collisionReach(), LayoutOptimizer.springScale * LayoutOptimizer.impl.optimumLengthFactor(degree, degree, depth))
        // a node not measured yet has no size: it still weighs something
        return roomOf(reach * spring * sparser(depth), depth).coerceAtLeast(1.0)
    }

    /** How long the edge [e] of the nodes wants to be: (r_a + r_b) × s × optimumLengthFactor × 3D, the way it lies left out. */
    private fun ideal(g: Graph, e: Edge, depth: Boolean): Double =
        (reach(e.src, depth) + reach(e.dst, depth)) * LayoutOptimizer.springScale * LayoutOptimizer.impl.optimumLengthFactor(g, e.src, e.dst, depth) * sparser(depth)

    /** Nodes by how far they are, the nearest first ([ropes]' searches); one pushed again nearer leaves its old entry, skipped. */
    private class Nearest {
        private val heap = mutableListOf<Pair<Double, Node>>()

        fun isNotEmpty() = heap.isNotEmpty()

        fun push(d: Double, n: Node) {
            heap += d to n
            var i = heap.size - 1
            while (i > 0 && heap[(i - 1) / 2].first > heap[i].first) {
                heap[i] = heap[(i - 1) / 2].also { heap[(i - 1) / 2] = heap[i] }
                i = (i - 1) / 2
            }
        }

        fun pop(): Pair<Double, Node> {
            val top = heap[0]
            heap[0] = heap.last()
            heap.removeAt(heap.size - 1)
            var i = 0
            var child = nearer(i)
            while (child != null && heap[child].first < heap[i].first) {
                heap[i] = heap[child].also { heap[child] = heap[i] }
                i = child
                child = nearer(i)
            }
            return top
        }

        /** The nearer of the two below [i], if any. */
        private fun nearer(i: Int): Int? = listOf(2 * i + 1, 2 * i + 2).filter { it < heap.size }.minByOrNull { heap[it].first }
    }

    /**
     * Of each edge of the levels of systems, how long its rope is ([Factors.ROPE_LEVELS]): how far apart the two suns get
     * at most, the path between them laid straight. Of each system, the shortest ways from its sun to its members, among
     * them only, on the level below; of two systems, the shortest of these to the two ends of an edge between them, and the
     * edge. The edges of the nodes as long as their springs want ([ideal]), of the systems as their ropes. A path through
     * a third system is not looked for: a rope is never shorter than the shortest path, and it only pulls.
     *
     * Of each node and system also its reach: how far from its sun it reaches at most, laid straight, to the middle of an
     * edge out of it. A node: half its longest edge; a system: the furthest of its members, their way from its sun and
     * their reach. Of a rope stretched between two systems the two reaches meet at the middle of the edge it goes over.
     * How long it took goes to [LayoutTimes].
     */
    private fun measure(levels: List<Level>, depth: Boolean): Measured {
        val start = Platform.clockMillis()
        val ropes = mutableMapOf<Edge, Double>()
        val g = levels[0].g
        val among = levels[0].nodes.toSet()
        val reaches = levels[0].nodes.associateWith { n ->
            g.edgeMap[n].orEmpty().filter { e -> e.src != e.dst && e.src in among && e.dst in among }.maxOfOrNull { ideal(g, it, depth) / 2 } ?: reach(n, depth)
        }.toMutableMap()
        var reached = 0
        (1 until levels.size).forEach { k ->
            val below = levels[k - 1]
            val up = levels[k]
            val length = { e: Edge -> if (k == 1) ideal(below.g, e, depth) else ropes.getValue(e) }
            val from = up.nodes.flatMap { s ->
                val members = up.members.getValue(s).toSet()
                val found = mutableMapOf<Node, Double>()
                val todo = Nearest().apply { push(0.0, up.sun.getValue(s)) }
                while (todo.isNotEmpty()) todo.pop().let { (d, n) ->
                    if (n !in found) {
                        found[n] = d
                        below.g.edgeMap[n].orEmpty().forEach { e ->
                            val o = if (e.src == n) e.dst else e.src
                            if (o in members && o !in found) todo.push(d + length(e), o)
                        }
                    }
                }
                reached += found.size
                reaches[s] = found.maxOf { (m, d) -> d + reaches.getValue(m) }
                found.toList()
            }.toMap()
            val systemOf = up.nodes.flatMap { s -> up.members.getValue(s).map { it to s } }.toMap()
            // of two systems, the shortest way between their suns over an edge between them
            val between = mutableMapOf<Set<Node>, Double>()
            below.g.edges.forEach { e ->
                val (a, b) = systemOf[e.src] to systemOf[e.dst]
                val (x, y) = from[e.src] to from[e.dst]
                if (a != null && b != null && a != b && x != null && y != null) {
                    val way = x + length(e) + y
                    between[setOf(a, b)] = minOf(way, between[setOf(a, b)] ?: way)
                }
            }
            up.g.edges.forEach { e -> between[setOf(e.src, e.dst)]?.let { ropes[e] = it } }
        }
        LayoutTimes.roped = Platform.clockMillis() - start
        LayoutTimes.searches = levels.drop(1).sumOf { it.nodes.size }
        LayoutTimes.reached = reached
        return Measured(ropes, reaches)
    }

    /** Of each edge of the systems its rope, of each node and system its reach ([measure]). */
    internal class Measured(val ropes: Map<Edge, Double>, val reaches: Map<Node, Double>)

    private fun neighbours(level: Level, among: Set<Node>): Map<Node, List<Node>> =
        level.nodes.associateWith { n ->
            level.g.edgeMap[n].orEmpty().map { if (it.src == n) it.dst else it.src }.filter { it != n && it in among }.distinct()
        }

    /**
     * The level above [level]: its solar systems. Suns by degree (of equals the heavier, then the first), no
     * two within 2 edges of each other; each other node joins the lightest system next to it (of equals the nearest
     * sun), first those next to a sun, then those next to one of these.
     */
    private fun contract(level: Level, depth: Boolean): Level {
        val among = level.nodes.toSet()
        val near = neighbours(level, among)
        val order = level.nodes.sortedWith(compareByDescending<Node> { near.getValue(it).size }.thenByDescending { level.mass.getValue(it) })
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
            val mass = Factors.PACKING * held.getValue(sun)
            // its springs keep others as far as its room reaches: the reach that, times the collision spring's, is that
            val reach = radiusOf(mass, depth) / (LayoutOptimizer.impl.collisionReach() * sparser(depth))
            Node(Vector2.Zero).apply {
                attributes.other[CLUSTER] = ""
                cache.shape = NodeShape.Oval.impl
                cache.shapeBounds = Vector2(2 * reach, 2 * reach)
                // where its sun is: members off in another system do not drag it after them
                point = sun.point
            } to mass
        }
        stand.values.forEach { upper.add(it.first) }
        // one edge from a system to another, standing for all the nodes' edges that way
        val joined = mutableMapOf<Pair<Node, Node>, Edge>()
        val count = mutableMapOf<Edge, Int>()
        level.g.edges.filter { it.src in among && it.dst in among }.forEach { e ->
            val (a, b) = stand.getValue(sunOf.getValue(e.src)).first to stand.getValue(sunOf.getValue(e.dst)).first
            if (a != b) {
                val edge = joined[a to b] ?: Edge(a, b).also {
                    upper.add(it)
                    joined[a to b] = it
                }
                count[edge] = (count[edge] ?: 0) + level.count(e)
            }
        }
        return Level(
            upper,
            stand.values.map { it.first },
            stand.values.associate { it },
            stand.entries.associate { (sun, it) -> it.first to systems.getValue(sun) },
            stand.entries.associate { (sun, it) -> it.first to sun },
            count,
        )
    }

    /** The systems of [shown] of [g], level above level, as they are made when it is made; [depth]: for 3D mode. */
    class Systems(g: Graph, shown: Set<Node>, private val depth: Boolean) {
        internal val levels: List<Level> = run {
            val nodes = Level(g, shown.toList(), shown.associateWith { mass(g, it, depth) }, emptyMap(), emptyMap())
            generateSequence(nodes) { below -> if (below.nodes.size <= Factors.SMALL) null else contract(below, depth) }
                .zipWithNext()
                .takeWhile { (below, above) -> above.nodes.size <= below.nodes.size * Factors.SHRINKS }
                .fold(listOf(nodes)) { made, (_, above) -> made + above }
        }

        /** The ropes and reaches, of the nodes' sizes and the spring scale when first asked for. */
        internal val measured: Measured by lazy { measure(levels, depth) }

        /**
         * For seeing them: each of every level as a ball with its room and its reach, around its sun as that is now; each
         * sun of the lowest (level 0) as one with its own room.
         */
        fun shells(): List<Shell> {
            val middles = (1 until levels.size).runningFold(levels[0].nodes.associateWith { it.point }) { below, i ->
                levels[i].nodes.associateWith { n -> below.getValue(levels[i].sun.getValue(n)) }
            }
            val systems = (1 until levels.size).flatMap { i ->
                levels[i].nodes.map { n -> Shell(middles[i].getValue(n), radiusOf(levels[i].mass.getValue(n), depth), i, reach = measured.reaches[n]) }
            }
            val suns = levels.getOrNull(1)?.sun?.values.orEmpty().toSet()
            val nodes = levels[0].nodes.filter { it in suns }.map { n -> Shell(n.point, radiusOf(levels[0].mass.getValue(n), depth), 0, true) }
            return systems + nodes
        }
    }

    /**
     * `o` on the whole graph: [shown] of [g] by levels, all of them at each step, until all rest; the nodes' own level with
     * [weight] and [rank], the levels above by their masses; with [only] those levels alone. As [Settling.Run] for [budget],
     * [clock], [depth] and [past].
     * How long each level took goes to [LayoutTimes].
     */
    class Run(
        private val g: Graph,
        shown: Set<Node>,
        private val weight: (Node) -> Double,
        private val rank: (Node) -> Double,
        private val budget: Long?,
        private val clock: (() -> Long)?,
        private val depth: Boolean,
        private val past: Double,
        private val only: Set<Int>? = null,
    ) : Stepping {
        val systems = Platform.clockMillis().let { start ->
            Systems(g, shown, depth).also {
                LayoutTimes.built = Platform.clockMillis() - start
                LayoutTimes.made = it.levels.size
            }
        }
        private val levels = systems.levels

        private fun runAt(i: Int, past: Double): Settling.Run {
            val level = levels[i]
            val among = level.nodes.toSet()
            // the springs of this level ([Factors.EDGE_LEVELS] and the rest)
            val collision = if (i in Factors.COLLISION_LEVELS) 1.0 else 0.0
            val separating = i in Factors.SEPARATION_LEVELS
            val furthest = maxOf(collision, if (separating) maxOf(Factors.SEPARATION_RELATED, Factors.SEPARATION_KIN, Factors.SEPARATION_UNRELATED) else 0.0)
            val apart = { a: Node, b: Node -> maxOf(collision, if (separating) level.separation(a, b) else 0.0).takeIf { it > 0 } }
            // each held to the sun of the system it is in, a level up, as far as that system's room reaches
            val held = levels.getOrNull(i + 1)?.takeIf { i in Factors.COHESION_LEVELS }?.let { up ->
                up.nodes.flatMap { s -> up.members.getValue(s).map { it to (up.sun.getValue(s) to radiusOf(up.mass.getValue(s), depth)) } }.toMap()
            }.orEmpty()
            val edges = i in Factors.EDGE_LEVELS
            val siblings = i in Factors.SIBLINGS_LEVELS
            val antigravity = i in Factors.ANTIGRAVITY_LEVELS
            val rope = if (i in Factors.ROPE_LEVELS) ropes::get else { _: Edge -> null }
            val rooted = i in Factors.ROOT_LEVELS
            val pace = Factors.LEVEL_PACE(i)
            // on every level a node gives way by its mass, so that what the levels carry down leaves the middle of the masses
            val run = if (i == 0) level.mass.let { m ->
                val mass = { n: Node -> if (weight(n).isFinite()) m.getValue(n) else Settling.FIXED }
                Settling.Run(
                    g, among, mass, budget = budget, clock = clock, depth = depth, rank = rank, balanced = true, adaptive = true,
                    separation = maxOf(furthest, 1.0), separatedBy = apart, cohesion = held::get, edges = edges, siblings = siblings, antigravity = antigravity,
                    rope = rope, rooted = rooted, pace = pace,
                )
            }
            else level.mass.let { m ->
                val degree = { n: Node -> level.g.edgeMap[n].orEmpty().size.toDouble() }
                Settling.Run(
                    level.g, among, m::getValue, budget = budget, clock = clock, depth = depth, rank = { degree(it) * m.getValue(it) },
                    balanced = true, adaptive = true, pulls = { e -> level.count(e).toDouble() }, separation = maxOf(furthest, 1.0), separatedBy = apart,
                    cohesion = held::get, edges = edges, siblings = siblings, antigravity = antigravity, rope = rope,
                    rooted = rooted, pace = pace,
                )
            }
            return run.also { it.stir(among, past) }
        }

        /** Of each node of level i, the system a level up it is in. */
        private val systemOf: List<Map<Node, Node>> = (0 until levels.size - 1).map { i ->
            levels[i + 1].let { up -> up.nodes.flatMap { s -> up.members.getValue(s).map { it to s } }.toMap() }
        }

        /** The ropes of the systems' edges, when a level that moves has them. */
        private val ropes: Map<Edge, Double> by lazy { systems.measured.ropes }

        /** Of each node of level i, the systems a level up whose suns are no more than two edges from it, its own among them. */
        private val nearSuns: List<Map<Node, List<Node>>> = (0 until levels.size - 1).map { i ->
            val up = levels[i + 1]
            val next = { n: Node -> levels[i].g.edgeMap[n].orEmpty().map { if (it.src == n) it.dst else it.src } }
            val found = mutableMapOf<Node, MutableSet<Node>>()
            up.nodes.forEach { s ->
                val sun = up.sun.getValue(s)
                (next(sun).flatMap { listOf(it) + next(it) } + up.members.getValue(s)).forEach { found.getOrPut(it) { mutableSetOf() } += s }
            }
            found.mapValues { it.value.toList() }
        }

        /**
         * What the systems of level i + 1 moved, [from], carried to the nodes of level [i]: a sun goes as its system, any other
         * as the systems whose suns are near it (two edges at most), each by how near its sun is (1 / distance). Where two
         * systems meet, their planets go much the same way, and not the one way and its neighbour the other.
         */
        private fun blend(i: Int, from: Map<Node, Vector3>): Map<Node, Vector3> {
            val up = levels[i + 1]
            return levels[i].nodes.mapNotNull { m ->
                val own = systemOf[i][m]
                val near = if (own != null && up.sun[own] == m) listOf(own) else nearSuns[i][m].orEmpty()
                val weights = near.map { s -> 1.0 / maxOf((m.point - up.sun.getValue(s).point).length(), 1.0) }
                val by = near.zip(weights).fold(Vector3.Zero) { sum, (s, w) -> sum + (from[s] ?: Vector3.Zero) * w } / weights.sum()
                if (near.isEmpty() || by == Vector3.Zero) null else m to by
            }.toMap()
        }

        /** Each stand-in back on its sun, as the levels below moved that: the lowest first. */
        private fun recentre() {
            (1 until levels.size).forEach { i -> levels[i].nodes.forEach { n -> n.point = levels[i].sun.getValue(n).point } }
        }

        /**
         * The levels that move, the top first: [only] (`:set suns=<levels>`; for one higher than there are, the top one), or all;
         * of those, the ones in [Factors.OPTIMIZED_LEVELS].
         */
        private val moving = (only?.map { it.coerceIn(0, levels.size - 1) }?.toSet() ?: levels.indices.toSet())
            .filter { it in Factors.OPTIMIZED_LEVELS }.sortedDescending()

        private val runs = moving.associateWith { runAt(it, past) }

        /**
         * The highest level that moves, in the parts of it that hang together, each with the one of it that stays where it
         * is: of the most edges, then the heaviest (as a sun is picked). A part does not drift off; only its shape changes.
         */
        private val anchors: List<Pair<Node, List<Node>>> = levels[moving.first()].let { level ->
            val among = level.nodes.toSet()
            val next = { n: Node -> level.g.edgeMap[n].orEmpty().map { if (it.src == n) it.dst else it.src }.filter { it in among } }
            val seen = mutableSetOf<Node>()
            level.nodes.mapNotNull { start ->
                if (!seen.add(start)) null
                else {
                    val part = mutableListOf<Node>()
                    val todo = ArrayDeque(listOf(start))
                    while (todo.isNotEmpty()) todo.removeFirst().let { n -> part += n; next(n).filter { seen.add(it) }.forEach { todo.addLast(it) } }
                    part.maxWith(compareBy<Node> { next(it).size }.thenBy { level.mass.getValue(it) }) to part
                }
            }
        }

        /** The highest level moved from [before]: each part of it back by what its anchor moved. */
        private fun pin(before: Map<Node, Vector3>) {
            anchors.forEach { (anchor, part) ->
                val by = anchor.point - before.getValue(anchor)
                if (by != Vector3.Zero) part.forEach { it.point = it.point - by }
            }
        }

        /**
         * A step of every level that moves, the top first: a node goes its own way plus what the systems near it carry down
         * ([blend]), a level at a time.
         */
        override fun step(): Boolean {
            LayoutTimes.level = moving.last()
            LayoutTimes.time(Part.Recentre) { recentre() }
            // what the nodes of the level above moved in all, carried from above and their own
            var from = emptyMap<Node, Vector3>()
            val more = levels.indices.reversed().map { i ->
                LayoutTimes.level = i
                val level = levels[i]
                val carried = if (from.isEmpty()) emptyMap() else LayoutTimes.time(Part.Carry) { blend(i, from) }
                carried.forEach { (n, by) -> n.point = n.point + by }
                // what the systems carried, before the nodes' own step
                if (i == 0 && carried.isNotEmpty() && g.attachmentOwners.isNotEmpty()) LayoutTimes.time(Part.Reglue) { Attachments.reglue(g) }
                val before = if (i > 0 || i == moving.first()) level.nodes.associateWith { it.point } else emptyMap()
                (runs[i]?.step() ?: false).also {
                    if (i == moving.first()) pin(before)
                    if (i > 0) {
                        from = level.nodes.associateWith { n -> (carried[n] ?: Vector3.Zero) + n.point - before.getValue(n) }.filterValues { it != Vector3.Zero }
                    }
                }
            }
            return more.any { it }
        }
    }
}
