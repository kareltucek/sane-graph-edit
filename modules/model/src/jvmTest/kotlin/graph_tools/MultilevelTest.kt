@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package graph_tools

import Graph
import utils.Vector2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class MultilevelTest {
    private val HELD = 600

    private fun overlap(a: Node, b: Node): Boolean =
        abs(a.position.x - b.position.x) < (a.cache.shapeBounds.x + b.cache.shapeBounds.x) / 2 &&
            abs(a.position.y - b.position.y) < (a.cache.shapeBounds.y + b.cache.shapeBounds.y) / 2

    /** A tree of [count] nodes, four children each, all piled on a few units around the middle; a few cross edges. */
    private fun pile(count: Int): Graph {
        val g = Graph()
        val chance = kotlin.random.Random(1)
        val nodes = List(count) { Node("node $it", Vector2(chance.nextDouble() * 5, chance.nextDouble() * 5)) }
        nodes.forEach { g.add(it) }
        (1 until count).forEach { g.add(Edge(nodes[(it - 1) / 4], nodes[it])) }
        (1 until count step 37).forEach { g.add(Edge(nodes[it], nodes[(it * 7) % count])) }
        g.ensureCachesFresh(force = true)
        return g
    }

    private fun overlaps(g: Graph): Set<Pair<Node, Node>> = g.nodes.toList().let { ns ->
        ns.indices.flatMap { i -> (i + 1 until ns.size).filter { overlap(ns[i], ns[it]) }.map { ns[i] to ns[it] } }.toSet()
    }

    @Test
    fun `a pile of the whole graph comes apart by levels`() {
        // enough for levels; on the JVM only, as the JS tests' two seconds are about what it takes there
        val g = pile(90)
        var now = 0L
        val importance = { n: Node -> (g.findOutEdges(n).size + 1) * n.cache.shapeBounds.length().coerceAtLeast(1.0) }
        val run = Multilevel.Run(g, g.nodes, importance, importance, null, { now }, false, LayoutOptimizer.WINDOW_MS)
        // held for about ten seconds: it goes on for as long as it is held
        val going = (1..HELD).all { run.also { now += 16 }.step() }
        // still moving, nodes brush past each other: only those still on each other a hundred steps later stay so
        val passing = overlaps(g)
        (1..100).forEach { run.also { now += 16 }.step() }
        val left = overlaps(g).intersect(passing).size
        println("multilevel: $HELD steps, ${passing.size} overlapping pairs, $left of them still 100 steps later")
        assertTrue(going, "goes on while held")
        println(LayoutTimes.report())
        // on one level (Settling.Run alone) this pile keeps 8 pairs on each other
        assertTrue(left < 3, "$left overlapping pairs")
    }

    @Test
    fun `one level only - a planet near its own sun alone goes as it, one near two suns between them`() {
        val g = pile(90)
        var now = 0L
        val importance = { n: Node -> (g.findOutEdges(n).size + 1) * n.cache.shapeBounds.length().coerceAtLeast(1.0) }
        val run = Multilevel.Run(g, g.nodes, importance, importance, null, { now }, false, LayoutOptimizer.WINDOW_MS, only = setOf(1))
        val level = run.systems.levels[1]
        val suns = level.nodes.associateBy { level.sun.getValue(it) }
        val next = { n: Node -> g.edgeMap[n].orEmpty().map { if (it.src == n) it.dst else it.src } }
        val sunsNear = { n: Node -> (next(n).flatMap { listOf(it) + next(it) } + n).filter { it in suns }.toSet() }
        val planets = level.nodes.flatMap { s -> level.members.getValue(s).map { it to level.sun.getValue(s) } }.filter { (m, sun) -> m != sun }
        val (alone, between) = planets.partition { (m, sun) -> sunsNear(m) == setOf(sun) }
        assertTrue(alone.isNotEmpty() && between.isNotEmpty(), "${alone.size} near their sun alone, ${between.size} near more")
        val offsets = { ps: List<Pair<Node, Node>> -> ps.map { (m, sun) -> m.point - sun.point } }
        val (aloneBefore, betweenBefore) = offsets(alone) to offsets(between)
        repeat(50) { run.also { now += 16 }.step() }
        offsets(alone).zip(aloneBefore).forEach { (a, b) -> assertTrue((a - b).length() < 1e-6, "near its sun alone, it goes as its system") }
        assertTrue(offsets(between).zip(betweenBefore).any { (a, b) -> (a - b).length() > 1e-6 }, "near two suns, it goes between them")
    }

    @Test
    fun `the reaches of two systems are at least as long together as the rope between them`() {
        val g = pile(90)
        val systems = Multilevel.Systems(g, g.nodes, depth = false)
        val (ropes, reaches) = systems.measured.let { it.ropes to it.reaches }
        assertTrue(ropes.isNotEmpty(), "there are ropes")
        ropes.forEach { (e, rope) -> assertTrue(reaches.getValue(e.src) + reaches.getValue(e.dst) >= rope - 1e-9, "$rope") }
    }

    @Test
    fun `the shells -a ball for each system and one for each sun of the lowest, around what they hold`() {
        val g = pile(90)
        val importance = { n: Node -> (g.findOutEdges(n).size + 1) * n.cache.shapeBounds.length().coerceAtLeast(1.0) }
        val run = Multilevel.Run(g, g.nodes, importance, importance, null, { 0L }, true, LayoutOptimizer.WINDOW_MS)
        val shells = run.systems.shells()
        val (nodes, systems) = shells.partition { it.level == 0 }
        assertTrue(nodes.size == run.systems.levels[1].nodes.size && systems.isNotEmpty(), "${nodes.size} suns, ${systems.size} systems")
        // a node's room reaches further than its corners: the springs keep others off it
        assertTrue(nodes.all { s -> g.nodes.any { it.point == s.at && s.radius > it.cache.shapeBounds.length() / 2 } }, "a node's ball is around it")
        // a system holds at least its sun: as much room as a node's ball at least
        assertTrue(systems.all { it.radius > 1.0 }, "systems have room")
        // the whole graph moved, the shells move with it
        g.nodes.forEach { it.point = it.point + utils.Vector3(100.0, 0.0, 0.0) }
        run.systems.shells().zip(shells).forEach { (now, before) -> assertTrue(abs(now.at.x - before.at.x - 100.0) < 1e-6, "follows the nodes") }
    }
}
