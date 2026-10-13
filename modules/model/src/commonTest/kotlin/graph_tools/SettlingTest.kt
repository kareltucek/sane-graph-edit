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
    fun `a node in the way that may move does, what may not stays`() {
        val f = Fixture()
        val moved = Settling.settle(f.g, setOf(f.fresh), f.all) { it == f.inTheWay || it == f.far }
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
        val moved = Settling.settle(f.g, setOf(f.fresh), f.all) { false }
        assertEquals(setOf(f.fresh), moved)
        assertEquals(Vector2(205, 3), f.inTheWay.position)
        assertFalse(overlap(f.fresh, f.inTheWay))
    }

    @Test
    fun `settled nodes rest`() {
        val f = Fixture()
        Settling.settle(f.g, setOf(f.fresh), f.all) { it == f.inTheWay }
        val before = f.all.associateWith { it.position }
        Settling.settle(f.g, setOf(f.fresh), f.all) { it == f.inTheWay }
        assertTrue(f.all.all { (it.position - before.getValue(it)).length() < 5.0 })
    }
}
