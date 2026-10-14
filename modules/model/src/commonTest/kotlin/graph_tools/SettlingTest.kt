@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package graph_tools

import Graph
import utils.Vector2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettlingTest {
    /** A parent with a child put right on top of another child of it, and a node far away. */
    private class Fixture {
        val g = Graph()
        val parent = Node("parent", Vector2(0, 0))
        val fresh = Node("fresh", Vector2(200, 0))
        val inTheWay = Node("in the way", Vector2(205, 3))
        val far = Node("far", Vector2(5000, 5000))
        val all = setOf(parent, fresh, inTheWay, far)

        init {
            all.forEach { g.add(it) }
            g.add(Edge(parent, fresh))
            g.add(Edge(parent, inTheWay))
            g.ensureCachesFresh(force = true)
        }
    }

    private fun overlap(a: Node, b: Node): Boolean =
        abs(a.position.x - b.position.x) < (a.cache.shapeBounds.x + b.cache.shapeBounds.x) / 2 &&
            abs(a.position.y - b.position.y) < (a.cache.shapeBounds.y + b.cache.shapeBounds.y) / 2

    @Test
    fun `of two nodes on each other the lighter gives way more`() {
        val g = Graph()
        val heavy = Node("heavy", Vector2(0, 0))
        val light = Node("light", Vector2(10, 0))
        g.add(heavy)
        g.add(light)
        g.ensureCachesFresh(force = true)
        Settling.settle(g, setOf(heavy, light), setOf(heavy, light), { if (it == heavy) Settling.HEAVY else Settling.LIGHT })
        assertFalse(overlap(heavy, light))
        assertTrue(light.position.x - 10 > 3 * -heavy.position.x, "the light one went further: ${light.position}, ${heavy.position}")
    }

    @Test
    fun `steps cut short take turns - each node gets to move, and all come off each other`() {
        val g = Graph()
        val pile = List(20) { Node("node $it", Vector2(kotlin.math.cos(it.toDouble()) * 3, kotlin.math.sin(it.toDouble()) * 3)) }
        pile.forEach { g.add(it) }
        g.ensureCachesFresh(force = true)
        // no time at all: each step takes one node, the others wait; a window of the springs' time passes between steps
        var now = 0L
        Settling.Run(g, pile.toSet(), { Settling.USUAL }, budget = 0L, clock = { now.also { now += 50 } }).also {
            it.stir(pile.toSet())
            it.toRest()
        }
        assertTrue(pile.all { a -> pile.all { b -> a == b || !overlap(a, b) } }, "${pile.map { it.position }}")
    }

    /** The node placed and [yielding] may move, as easily as each other; the others not. */
    private fun Fixture.weight(vararg yielding: Node): (Node) -> Double = { if (it == fresh || it in yielding) Settling.USUAL else Settling.FIXED }

    @Test
    fun `a node in the way that may move does, what may not stays`() {
        val f = Fixture()
        val moved = Settling.settle(f.g, setOf(f.fresh), f.all, f.weight(f.inTheWay, f.far))
        assertEquals(setOf(f.fresh, f.inTheWay), moved)
        assertFalse(overlap(f.fresh, f.inTheWay))
        assertFalse(overlap(f.fresh, f.parent))
        assertFalse(overlap(f.inTheWay, f.parent))
        assertEquals(Vector2(0, 0), f.parent.position)
        assertEquals(Vector2(5000, 5000), f.far.position)
    }

    @Test
    fun `a node in the way that may not move pushes the new one off`() {
        val f = Fixture()
        val moved = Settling.settle(f.g, setOf(f.fresh), f.all, f.weight())
        assertEquals(setOf(f.fresh), moved)
        assertEquals(Vector2(205, 3), f.inTheWay.position)
        assertFalse(overlap(f.fresh, f.inTheWay))
    }

    @Test
    fun `where a node is by the document holds on to it, but no harder than the edge that brings it in`() {
        val f = Fixture()
        // by the document far to the right of its parent, and up
        val home = Vector2(4000, -1000)
        f.fresh.position = home
        Settling.settle(f.g, setOf(f.fresh), setOf(f.parent, f.fresh), f.weight()) { if (it == f.fresh) home else null }
        assertTrue(f.fresh.position.length() < 600, "brought in: ${f.fresh.position}")
        assertTrue(f.fresh.position.toUnit().dot(home.toUnit()) > 0.95, "in the direction it was: ${f.fresh.position}")
        assertFalse(overlap(f.fresh, f.parent))
    }

    @Test
    fun `settled nodes rest`() {
        val f = Fixture()
        Settling.settle(f.g, setOf(f.fresh), f.all, f.weight(f.inTheWay))
        val before = f.all.associateWith { it.position }
        Settling.settle(f.g, setOf(f.fresh), f.all, f.weight(f.inTheWay))
        assertTrue(f.all.all { (it.position - before.getValue(it)).length() < 5.0 })
    }
}
