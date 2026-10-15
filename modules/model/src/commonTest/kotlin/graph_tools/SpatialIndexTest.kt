@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package graph_tools

import Graph
import utils.Vector2
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The index against looking at everything: on points thrown about, some in clumps, with cells smaller and bigger than what is asked for. */
class SpatialIndexTest {
    private val random = Random(20261013)
    private val spread = List(400) { Vector2(random.nextDouble(-3000.0, 3000.0), random.nextDouble(-2000.0, 2000.0)) }
    private val clumps = List(200) { Vector2(9000 + random.nextDouble(-40.0, 40.0), 9000 + random.nextDouble(-40.0, 40.0)) }
    private val points = spread + clumps
    private val asked = List(60) { Vector2(random.nextDouble(-4000.0, 10000.0), random.nextDouble(-3000.0, 10000.0)) } + points.take(20)

    @Test
    fun `within a distance - the same as by looking at all`() {
        listOf(10.0, 150.0, 2500.0).forEach { cell ->
            val index = SpatialIndex(points, cell) { it }
            listOf(0.0, 35.0, 400.0, 20000.0).forEach { radius ->
                asked.forEach { p ->
                    assertEquals(points.filter { (it - p).length() <= radius }.toSet(), index.within(p, radius).toSet(), "cell $cell, radius $radius, from $p")
                }
            }
        }
    }

    @Test
    fun `the nearest few - the same as by looking at all, the nearest first`() {
        listOf(10.0, 150.0, 2500.0).forEach { cell ->
            val index = SpatialIndex(points, cell) { it }
            listOf(1, 5, 37, points.size + 3).forEach { count ->
                asked.forEach { p ->
                    val found = index.nearest(p, count)
                    val distances = found.map { (it - p).length() }
                    // by distance, not by which point: two may be as far
                    assertEquals(points.map { (it - p).length() }.sorted().take(count), distances, "cell $cell, $count from $p")
                }
            }
        }
    }

    @Test
    fun `the nearest few within a radius - all of those within it are among them`() {
        listOf(10.0, 150.0, 2500.0).forEach { cell ->
            val index = SpatialIndex(points, cell) { it }
            listOf(1, 5, 37).forEach { count ->
                listOf(0.0, 35.0, 400.0).forEach { radius ->
                    asked.forEach { p ->
                        val near = index.nearest(p, count, radius = radius).map { (it - p).length() }.filter { it <= radius }
                        assertEquals(points.map { (it - p).length() }.filter { it <= radius }.sorted().take(count), near, "cell $cell, $count within $radius from $p")
                    }
                }
            }
        }
    }

    @Test
    fun `the one asked from is left out, nothing is found among nothing`() {
        val index = SpatialIndex(points, 150.0) { it }
        val from = points[7]
        assertTrue(from !in index.nearest(from, 5, but = from))
        assertEquals(5, index.nearest(from, 5, but = from).size)
        assertEquals(emptyList(), SpatialIndex(emptyList<Vector2>(), 100.0) { it }.nearest(Vector2(0, 0), 5))
        assertEquals(emptyList(), SpatialIndex(emptyList<Vector2>(), 100.0) { it }.within(Vector2(0, 0), 500.0))
    }

    /** Nodes thrown about, of several sizes, one of them huge; some on each other. */
    private fun crowd(count: Int): Graph {
        val g = Graph()
        val texts = listOf("a", "some words", "a much longer line of text than the others have", "two\nlines", "four\nlines\nof\ntext")
        (0 until count).forEach { i ->
            g.add(Node(if (i == 3) "huge\n".repeat(60) + "x".repeat(300) else texts[i % texts.size], Vector2(random.nextDouble(-2500.0, 2500.0), random.nextDouble(-2500.0, 2500.0))))
        }
        g.ensureCachesFresh(force = true)
        return g
    }

    @Test
    fun `collisions - the springs are those of trying every pair, to the last digit`() {
        listOf(0.4, 1.0, 2.5).forEach { scale ->
            LayoutOptimizer.springScale = scale
            try {
                val g = crowd(300)
                listOf(false, true).forEach { moving ->
                    val tgt = LayoutOptimizer.SpringTarget.MoveEveryone
                    val strength = if (moving) 1.3 else 1.0
                    // as it was before there was an index
                    val all = g.nodes.flatMap { a -> g.nodes.filter { it != a }.mapNotNull { b -> LayoutOptimizer.impl.computeCollisionSpring(g, a, b, strength) } }
                    val found = LayoutOptimizer.impl.computeCollisionSprings(g, tgt, moving)
                    assertTrue(all.size > 50, "there is something to compare: ${all.size} springs")
                    assertEquals(all.size, found.size, "scale $scale, moving $moving: how many springs")
                    all.zip(found).forEachIndexed { i, (a, b) -> assertTrue(a.n === b.n && a.v == b.v, "scale $scale, moving $moving: spring $i is ${b.v} at ${b.n.position}, not ${a.v} at ${a.n.position}") }
                }
            } finally {
                LayoutOptimizer.springScale = 1.0
            }
        }
    }
}
