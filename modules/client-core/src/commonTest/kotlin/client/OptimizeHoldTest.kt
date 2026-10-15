@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import graph_tools.Edge
import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `o` held: a pass of the springs for each picture until the key is let go, all of it one undo step. */
class OptimizeHoldTest {
    private val a = Node("a", Vector2(0, 0))
    private val b = Node("b", Vector2(5, 0))
    private val c = Node("c", Vector2(900, 0))
    private val view = EditorView(Graph().also { g ->
        listOf(a, b, c).forEach { g.add(it) }
        g.add(Edge(a, b))
        g.add(Edge(b, c))
    }, NoRenderer).also {
        it.size = Vector2(800, 600)
        it.g.ensureCachesFresh(force = true)
    }
    private val before = view.g.nodes.associateWith { it.position }
    private val moved = { view.g.nodes.associateWith { it.position } }

    @Test
    fun `held, the nodes move by the time that passed, let go they stop, and undo takes all of it back`() {
        var now = 1000L
        view.gestures.clock = { now }
        view.byHeldKey = true
        Actions.optimizeWhileHeld(view, restrict = false)
        val afterPress = moved()
        assertTrue(afterPress != before, "a short while at once")
        // the keyboard's repeats change nothing
        Actions.optimizeWhileHeld(view, restrict = false)
        assertEquals(afterPress, moved())
        assertEquals(true, view.gestures.frame(), "more pictures are wanted while it is held")
        assertEquals(afterPress, moved(), "no time passed, nothing moved")
        now += 16
        view.gestures.frame()
        assertTrue(moved() != afterPress, "moved for the time since")
        view.gestures.stopOptimizing()
        val afterRelease = moved()
        assertEquals(false, view.gestures.frame())
        assertEquals(afterRelease, moved(), "let go, nothing moves")
        view.g.undo()
        assertEquals(before, moved(), "one undo step")
    }

    @Test
    fun `only the selection moves - a node it runs into stays where it is`() {
        view.g.cleanSelect(setOf(b, c))
        view.byHeldKey = false
        Actions.optimizeWhileHeld(view, restrict = false)
        assertEquals(before.getValue(a), a.position, "not selected, on b: stays")
        assertTrue(b.position != before.getValue(b), "selected: moves")
    }

    @Test
    fun `one node selected - all that is seen moves`() {
        view.g.cleanSelect(setOf(b))
        view.byHeldKey = false
        Actions.optimizeWhileHeld(view, restrict = false)
        assertTrue(a.position != before.getValue(a), "not selected, on b: moves too")
        assertTrue(b.position != before.getValue(b))
    }

    @Test
    fun `O on one node keeps it and lays out its children`() {
        val parent = Node("parent", Vector2(0, 0))
        val kid1 = Node("kid one", Vector2(0, 100))
        val kid2 = Node("kid two", Vector2(3, 100))
        val g = Graph().also { g ->
            listOf(parent, kid1, kid2).forEach { g.add(it) }
            g.add(Edge(parent, kid1))
            g.add(Edge(parent, kid2))
        }
        val v = EditorView(g, NoRenderer).also {
            it.size = Vector2(800, 600)
            it.g.ensureCachesFresh(force = true)
        }
        g.cleanSelect(setOf(parent))
        Actions.optimizeWhileHeld(v, restrict = true)
        assertEquals(Vector2(0, 0), parent.position)
        assertTrue(kid1.position != Vector2(0, 100) || kid2.position != Vector2(3, 100), "the children are laid out")
    }

    @Test
    fun `in 3D mode the nodes laid out spread across the plane`() {
        val parent = Node("parent", Vector2(0, 0))
        val kid1 = Node("kid one", Vector2(0, 100))
        val kid2 = Node("kid two", Vector2(3, 100))
        val g = Graph().also { g ->
            listOf(parent, kid1, kid2).forEach { g.add(it) }
            g.add(Edge(parent, kid1))
            g.add(Edge(parent, kid2))
        }
        val v = EditorView(g, NoRenderer).also {
            it.size = Vector2(800, 600)
            it.g.ensureCachesFresh(force = true)
        }
        v.viewTransform.threeD = true
        g.cleanSelect(setOf(kid1, kid2))
        Actions.optimizeWhileHeld(v, restrict = false)
        assertTrue(kid1.point.z * kid2.point.z < 0, "to opposite sides: ${kid1.point} ${kid2.point}")
    }

    @Test
    fun `asked for by no key that is held, it lays out a while at once, as one undo step, and asks for no pictures`() {
        view.byHeldKey = false
        Actions.optimizeWhileHeld(view, restrict = false)
        assertTrue(moved() != before)
        assertEquals(false, view.gestures.frame())
        view.g.undo()
        assertEquals(before, moved())
    }
}
