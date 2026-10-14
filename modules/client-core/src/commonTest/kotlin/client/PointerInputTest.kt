@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import client.PointerInput.Button
import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a renderer's mouse reports do to a view; the view starts at zoom 1 with the workspace origin in its corner. */
class PointerInputTest {
    private val a = Node("A", Vector2(100, 100))
    private val b = Node("B", Vector2(300, 100))
    private val view = EditorView(Graph().also { it.add(a); it.add(b) }, NoRenderer).also {
        it.size = Vector2(800, 600)
        it.g.ensureCachesFresh()
    }
    private val pointer = view.pointer

    private fun drag(from: Vector2, to: Vector2, button: Button = Button.Left, control: Boolean = false, shift: Boolean = false) {
        pointer.pressed(from, button, control, shift)
        pointer.moved((from + to) / 2, dragging = true, shift = false)
        pointer.moved(to, dragging = true, shift = false)
        pointer.released(to, to, button, control)
    }

    @Test
    fun `a click on a node selects it, a click beside it clears the selection`() {
        drag(a.position, a.position)
        assertEquals(setOf(a), view.g.selectedNodes.toSet())
        drag(Vector2(500, 400), Vector2(500, 400))
        assertTrue(view.g.selectedNodes.isEmpty())
    }

    @Test
    fun `dragging a node moves it, as one undo step`() {
        drag(a.position, Vector2(160, 40))
        assertEquals(Vector2(160, 40), a.position)
        assertEquals(Vector2(300, 100), b.position)
        view.g.undo()
        assertEquals(Vector2(100, 100), a.position)
    }

    @Test
    fun `with shift a dragged node takes along what hangs from it alone, pushes off what it lands on, and that is one undo step`() {
        val g = Graph()
        val parent = Node("parent", Vector2(100, 300))
        val child = Node("child", Vector2(100, 360))
        val other = Node("other", Vector2(400, 100))
        val shared = Node("shared", Vector2(250, 160))
        val inTheWay = Node("in the way", Vector2(600, 300))
        listOf(parent, child, other, shared, inTheWay).forEach { g.add(it) }
        g.add(graph_tools.Edge(parent, child))
        g.add(graph_tools.Edge(parent, shared))
        g.add(graph_tools.Edge(other, shared))
        val v = EditorView(g, NoRenderer).also {
            it.size = Vector2(800, 600)
            it.g.ensureCachesFresh()
        }
        val before = g.nodes.associateWith { it.position }
        // a renderer draws pictures while the nodes are on their way, a step each, here 60 a second
        var now = 0L
        v.gestures.clock = { now }
        val frame = {
            now += 16
            v.gestures.frame()
        }
        val frames = { generateSequence { frame() }.takeWhile { it }.take(3000).count() }
        v.pointer.pressed(parent.position, Button.Left, control = false, shift = false)
        listOf(250, 400, 600).forEach { x ->
            v.pointer.moved(Vector2(x.toDouble(), 300.0), dragging = true, shift = true)
            repeat(3) { frame() }
        }
        v.pointer.released(Vector2(600, 300), Vector2(600, 300), Button.Left, false)
        assertEquals(true, frame(), "still on their way when let go")
        assertTrue(frames() < 3000, "they come to rest")

        assertEquals(Vector2(600, 300), parent.position, "the dragged node is where the pointer is")
        // by springs: as near as the collision spring lets it, not exactly where it was relative to the parent
        assertTrue(child.position.x > 450 && (child.position - parent.position).length() < 150, "the child followed: ${child.position}")
        assertEquals(before[other], other.position, "a node not near is left alone")
        assertTrue((shared.position - before.getValue(shared)).length() < 1, "a child with another parent stays: ${shared.position}")
        assertTrue(graph_tools.LayoutOptimizer.impl.computeCollisionSpring(g, inTheWay, parent) == null, "pushed off: ${inTheWay.position}")
        g.undo()
        assertEquals(before, g.nodes.associateWith { it.position }, "one undo step puts all back")
    }

    @Test
    fun `in explore mode a drag is no undo step - the positions there are not the document's`() {
        view.g.beforeExplore = view.g.nodes.associateWith { it.point }
        drag(a.position, Vector2(160, 40))
        assertEquals(Vector2(160, 40), a.position)
        view.g.undo()
        assertEquals(Vector2(160, 40), a.position)
    }

    @Test
    fun `dragging empty space pans, and so does the middle button with shift`() {
        drag(Vector2(500, 400), Vector2(530, 380))
        assertEquals(Vector2(30, -20), view.viewTransform.offset)
        drag(Vector2(500, 400), Vector2(490, 400), Button.Middle, shift = true)
        assertEquals(Vector2(20, -20), view.viewTransform.offset)
        assertEquals(Vector2(100, 100), a.position)
    }

    @Test
    fun `the middle button pans in 2D mode`() {
        drag(Vector2(500, 400), Vector2(530, 380), Button.Middle)
        assertEquals(Vector2(30, -20), view.viewTransform.offset)
    }

    @Test
    fun `control and a drag over empty space selects what the box covers`() {
        drag(Vector2(50, 50), Vector2(200, 200), control = true)
        assertEquals(setOf(a), view.g.selectedNodes.toSet())
    }

    @Test
    fun `the wheel zooms and keeps what is under the pointer where it is`() {
        val at = Vector2(300, 100)
        pointer.wheel(at, -3.0)
        assertTrue(view.viewTransform.scale > 1.0)
        val under = view.viewTransform.toScreen(b.position)
        assertEquals(at.x, under.x, 1e-9)
        assertEquals(at.y, under.y, 1e-9)
    }
}
