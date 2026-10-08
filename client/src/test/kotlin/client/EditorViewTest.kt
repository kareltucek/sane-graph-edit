package client

import Graph
import graph_tools.Node
import graph_tools.NodeShape
import utils.Rgb
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a renderer's widgets report to the client, with no renderer at all. */
class EditorViewTest {
    private val a = Node("A", Vector2(0, 0))
    private val b = Node("B", Vector2(100, 0))
    private val view = EditorView(Graph().also { it.add(a); it.add(b) }, NoRenderer)

    @Test
    fun `typing shows at once and becomes one undo step when the editor closes`() {
        view.textEditStarted(a.id)
        view.textTyped("Ax")
        view.textTyped("Axy")
        assertEquals("Axy", a.attributes.text)
        assertFalse(view.g.history.canUndo)

        view.textEditEnded("Axy")
        assertEquals("Axy", a.attributes.text)
        view.g.undo()
        assertEquals("A", a.attributes.text)
        assertFalse(view.g.history.canUndo)
    }

    @Test
    fun `an edit that changes nothing leaves no undo step`() {
        view.textEditStarted(a.id)
        view.textTyped("changed my mind")
        view.textEditEnded("A")
        assertEquals("A", a.attributes.text)
        assertFalse(view.g.history.canUndo)
    }

    @Test
    fun `text reported without an edit in progress is ignored`() {
        view.textTyped("stray")
        view.textEditEnded("stray")
        assertEquals("A", a.attributes.text)
    }

    @Test
    fun `the style picker's picks apply to the selection only`() {
        a.attributes.nodeScale = 2.0
        view.g.cleanSelect(listOf(a))

        Actions.colorSelection(view, ColorStyle(background = Rgb(1, 2, 3), foreground = Rgb(4, 5, 6)))
        assertEquals(Rgb(1, 2, 3), a.attributes.bg)
        // a colour pick keeps the size
        assertEquals(2.0, a.attributes.nodeScale)
        assertEquals(null, b.attributes.bg)

        Actions.resizeSelection(view, 1.0)
        assertEquals(3.0, a.attributes.nodeScale)

        Actions.shapeSelection(view, NodeShape.Oval)
        assertSame(NodeShape.Oval.impl, a.cache.shape)
        assertSame(NodeShape.Rectangle.impl, b.cache.shape)

        repeat(3) { view.g.undo() }
        assertEquals(null, a.attributes.bg)
        assertEquals(2.0, a.attributes.nodeScale)
        assertSame(NodeShape.Rectangle.impl, a.cache.shape)
    }

    @Test
    fun `picks with nothing selected do nothing`() {
        Actions.colorSelection(view, ColorStyle(background = Rgb(1, 2, 3)))
        Actions.resizeSelection(view, 1.0)
        Actions.shapeSelection(view, NodeShape.Oval)
        assertFalse(view.g.history.canUndo)
        assertTrue(a.attributes.bg == null && a.attributes.nodeScale == null)
    }
}
