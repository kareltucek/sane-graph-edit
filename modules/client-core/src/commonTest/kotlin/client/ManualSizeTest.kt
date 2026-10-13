@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import graph_tools.ManualSize
import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A node that is given a size of its own, and resized by a corner. */
class ManualSizeTest {
    private val node = Node("some text", Vector2(400, 300))
    private val view = EditorView(Graph().also { it.add(node) }, NoRenderer, NoShell).also {
        it.size = Vector2(800, 600)
        it.g.ensureCachesFresh()
        it.g.cleanSelect(listOf(node))
    }
    private val g = view.g

    private fun press(at: Vector2) = view.pointer.pressed(view.viewTransform.toScreen(at), PointerInput.Button.Left, control = false, shift = false)
    private fun move(to: Vector2) = view.pointer.moved(view.viewTransform.toScreen(to), dragging = true, shift = false)
    private fun release(at: Vector2) = view.viewTransform.toScreen(at).let { view.pointer.released(it, it, PointerInput.Button.Left, control = false) }

    @Test
    fun `the attribute is width and height, and nothing else is a size`() {
        assertEquals(Vector2(320, 200), ManualSize.parse("320x200"))
        assertEquals("320x200.5", ManualSize.write(Vector2(320.0, 200.52)))
        listOf(null, "", "320", "x", "0x10", "-5x10", "axb", "1x2x3", "NaNx5").forEach { assertNull(ManualSize.parse(it), "$it") }
    }

    @Test
    fun `manual keeps the size the node has, auto lets the text decide again, each one undo step`() {
        // the size is kept to a tenth of a unit
        val auto = ManualSize.parse(ManualSize.write(node.cache.shapeBounds))!!
        Actions.sizeModeSelection(view, manual = true)
        assertEquals(true, Actions.sizeModeOfSelection(view))
        node.attributes.text = "a much, much longer text than there was before"
        g.needsRecomputing(node)
        g.ensureCachesFresh()
        assertEquals(auto, node.cache.shapeBounds)
        Actions.sizeModeSelection(view, manual = false)
        g.ensureCachesFresh()
        assertEquals(false, Actions.sizeModeOfSelection(view))
        assertEquals(true, node.cache.shapeBounds.x > auto.x)
        g.history.undo()
        g.ensureCachesFresh()
        assertEquals(auto, node.cache.shapeBounds)
    }

    @Test
    fun `dragging a corner resizes the node, the opposite corner stays, one undo step`() {
        Actions.sizeModeSelection(view, manual = true)
        g.ensureCachesFresh()
        val size = node.cache.shapeBounds
        val topLeft = node.position - size / 2
        val corner = node.position + size / 2
        press(corner)
        move(corner + Vector2(100, 40))
        release(corner + Vector2(100, 40))
        g.ensureCachesFresh()
        assertEquals(size + Vector2(100, 40), node.cache.shapeBounds)
        assertEquals(topLeft, node.position - node.cache.shapeBounds / 2)
        g.history.undo()
        g.ensureCachesFresh()
        assertEquals(size, node.cache.shapeBounds)
        assertEquals(Vector2(400, 300), node.position)
    }

    @Test
    fun `a node as big as its text is not resized by a corner, it is moved`() {
        val size = node.cache.shapeBounds
        val corner = node.position + size / 2 - Vector2(2, 2)
        press(corner)
        move(corner + Vector2(100, 40))
        release(corner + Vector2(100, 40))
        g.ensureCachesFresh()
        assertEquals(size, node.cache.shapeBounds)
        assertNull(ManualSize.of(node))
    }
}
