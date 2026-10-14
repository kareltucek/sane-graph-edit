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

    @Test
    fun `a firm node is tugged to its origin gently but there in two seconds`() {
        val g = Graph()
        val n = Node("follower", Vector2(0, 0))
        g.add(n)
        g.ensureCachesFresh(force = true)
        val target = utils.Vector3(600.0, 0.0, 0.0)
        var now = 0L
        val run = Settling.Run(g, setOf(n), { Settling.HEAVY }, close = true, firm = setOf(n), clock = { now }) { target }
        run.stir(setOf(n))
        // pictures at 60 a second
        val seconds = { s: Double -> repeat((s * 1000 / 16).toInt()) { now += 16; run.step() } }
        seconds(0.25)
        assertTrue(n.position.x < 300, "gently: after a quarter of a second ${n.position}")
        seconds(1.75)
        assertTrue(n.position.x > 540, "there after two seconds: ${n.position}")
    }

    /** A parent with its own parent above it, and two children on each other under it. */
    private class Siblings {
        val g = Graph()
        val grand = Node("grand", Vector2(0, -200))
        val parent = Node("parent", Vector2(0, 0))
        val a = Node("child a", Vector2(0, 120))
        val b = Node("child b", Vector2(4, 121))
        val all = setOf(grand, parent, a, b)

        init {
            all.forEach { g.add(it) }
            g.add(Edge(grand, parent))
            g.add(Edge(parent, a))
            g.add(Edge(parent, b))
            g.ensureCachesFresh(force = true)
        }

        fun settle(depth: Boolean) = Settling.Run(g, all, { if (it == a || it == b) Settling.USUAL else Settling.FIXED }, depth = depth).also {
            it.stir(setOf(a, b))
            it.toRest()
        }
    }

    @Test
    fun `in space, children on each other go to opposite sides of the plane`() {
        val s = Siblings()
        s.settle(depth = true)
        assertTrue(s.a.point.z * s.b.point.z < 0, "opposite sides: ${s.a.point} ${s.b.point}")
        assertTrue((s.a.point - s.b.point).length() > 20, "apart: ${s.a.point} ${s.b.point}")
    }

    @Test
    fun `in the plane, nothing leaves it`() {
        val s = Siblings()
        s.settle(depth = false)
        assertTrue(s.all.all { it.point.z == 0.0 })
        assertFalse(overlap(s.a, s.b))
    }

    @Test
    fun `gravity is the way the edges go - a child put beside its parent in a graph that goes down is pulled down`() {
        val g = Graph()
        val column = List(5) { Node("node $it", Vector2(0, it * 150)) }
        column.forEach { g.add(it) }
        column.zipWithNext().forEach { (p, c) -> g.add(Edge(p, c)) }
        val beside = Node("beside", Vector2(300, 300))
        g.add(beside)
        g.add(Edge(column[2], beside))
        g.ensureCachesFresh(force = true)
        assertTrue(Settling.down(g, depth = false).y > 0.5, "down: ${Settling.down(g, false)}")
        Settling.settle(g, setOf(beside), g.nodes.toSet(), { if (it == beside) Settling.USUAL else Settling.FIXED })
        assertTrue(beside.position.y > 300 + 20, "pulled down: ${beside.position}")
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
