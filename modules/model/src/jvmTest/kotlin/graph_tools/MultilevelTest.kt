@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package graph_tools

import Graph
import utils.Vector2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class MultilevelTest {
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

    private fun overlaps(g: Graph): Int = g.nodes.toList().let { ns -> ns.indices.sumOf { i -> (i + 1 until ns.size).count { overlap(ns[i], ns[it]) } } }

    @Test
    fun `a pile of the whole graph comes apart by levels`() {
        // enough for levels; on the JVM only, as the JS tests' two seconds are about what it takes there
        val g = pile(90)
        var now = 0L
        val importance = { n: Node -> (g.findOutEdges(n).size + 1) * n.cache.shapeBounds.length().coerceAtLeast(1.0) }
        val run = Multilevel.Run(g, g.nodes, importance, importance, null, { now }, false, LayoutOptimizer.WINDOW_MS)
        var steps = 0
        while (steps < 20000 && run.also { now += 16 }.step()) steps += 1
        val left = overlaps(g)
        println("multilevel: $steps steps, $left overlapping pairs")
        assertTrue(steps < 20000, "rests")
        println(LayoutTimes.report())
        // on one level (Settling.Run alone) this pile keeps 8 pairs on each other
        assertTrue(left < 3, "$left overlapping pairs")
    }

    @Test
    fun `one level only - its systems move, each as a whole`() {
        val g = pile(90)
        var now = 0L
        val importance = { n: Node -> (g.findOutEdges(n).size + 1) * n.cache.shapeBounds.length().coerceAtLeast(1.0) }
        val run = Multilevel.Run(g, g.nodes, importance, importance, null, { now }, false, LayoutOptimizer.WINDOW_MS, only = 1)
        val systems = run.systems.levels[1].members.values
        val offsets = { systems.map { held -> held.map { it.point - held.first().point } } }
        val before = offsets()
        val start = g.nodes.associateWith { it.point }
        repeat(50) { run.also { now += 16 }.step() }
        assertTrue(g.nodes.any { it.point != start.getValue(it) }, "the systems moved")
        offsets().zip(before).forEach { (now, was) -> now.zip(was).forEach { (a, b) -> assertTrue((a - b).length() < 1e-6, "a system moves as a whole") } }
    }

    @Test
    fun `the shells -a ball for each system, one around each sun of the lowest, around what they hold`() {
        val g = pile(90)
        val importance = { n: Node -> (g.findOutEdges(n).size + 1) * n.cache.shapeBounds.length().coerceAtLeast(1.0) }
        val run = Multilevel.Run(g, g.nodes, importance, importance, null, { 0L }, true, LayoutOptimizer.WINDOW_MS)
        val shells = run.systems.shells()
        val (suns, systems) = shells.partition { it.level == 0 }
        // the lowest systems are as many as their suns, and there may be more levels above them
        assertTrue(suns.isNotEmpty() && systems.size >= suns.size, "${suns.size} suns, ${systems.size} systems")
        assertTrue(suns.all { s -> g.nodes.any { it.point == s.at && s.radius > it.cache.shapeBounds.length() / 2 } }, "a sun's ball is around its node")
        // a system holds at least its sun: as much room as a node's ball at least
        assertTrue(systems.all { it.radius > 1.0 }, "systems have room")
        // the whole graph moved, the shells move with it
        g.nodes.forEach { it.point = it.point + utils.Vector3(100.0, 0.0, 0.0) }
        run.systems.shells().zip(shells).forEach { (now, before) -> assertTrue(abs(now.at.x - before.at.x - 100.0) < 1e-6, "follows the nodes") }
    }
}
