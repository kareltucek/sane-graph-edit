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
    fun `dragging empty space pans, and so does the middle button with shift`() {
        drag(Vector2(500, 400), Vector2(530, 380))
        assertEquals(Vector2(30, -20), view.viewTransform.offset)
        drag(Vector2(500, 400), Vector2(490, 400), Button.Middle, shift = true)
        assertEquals(Vector2(20, -20), view.viewTransform.offset)
        assertEquals(Vector2(100, 100), a.position)
    }

    @Test
    fun `the middle button alone does nothing in 2D mode`() {
        drag(Vector2(500, 400), Vector2(530, 380), Button.Middle)
        assertEquals(Vector2.Zero, view.viewTransform.offset)
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
