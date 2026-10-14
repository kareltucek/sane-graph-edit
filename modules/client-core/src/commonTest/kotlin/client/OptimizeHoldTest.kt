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
    fun `asked for by no key that is held, it lays out a while at once, as one undo step, and asks for no pictures`() {
        view.byHeldKey = false
        Actions.optimizeWhileHeld(view, restrict = false)
        assertTrue(moved() != before)
        assertEquals(false, view.gestures.frame())
        view.g.undo()
        assertEquals(before, moved())
    }
}
