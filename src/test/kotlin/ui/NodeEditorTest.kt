package ui

import Graph
import graph_tools.Node
import utils.Rgb
import utils.Vector2
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The in-place text editor: it is told a node id and reports text; the node changes through the model. */
class NodeEditorTest {
    private val node = Node("old", Vector2(0, 0)).also { it.attributes.bg = Rgb(10, 20, 30) }
    private val view = GraphView(initialGraph = Graph().also { it.add(node) }).also {
        System.setProperty("java.awt.headless", "true")
        JPanel().add(it)
        it.setSize(400, 400)
    }

    @Test
    fun `the editor opens with the node's text and colour, over the node`() {
        view.model.startNodeEdit(node.id, null)
        val editor = view.nodeEditor
        assertTrue(editor.isVisible)
        assertEquals("old", editor.text)
        assertEquals(java.awt.Color(10, 20, 30), editor.background)
        // the canvas has not been painted, so the workspace origin is the view's corner
        val west = view.springLayout.getConstraint(javax.swing.SpringLayout.WEST, editor).value
        val east = view.springLayout.getConstraint(javax.swing.SpringLayout.EAST, editor).value
        assertTrue(west < east, "editor has no width: $west..$east")
        assertEquals((west + east) / 2.0, utils.Constants.frameMargin + 1.0, 1.0)
    }

    @Test
    fun `typing shows in the node at once and closing makes it one undo step`() {
        view.model.startNodeEdit(node.id, null)
        view.nodeEditor.text = "new text"
        assertEquals("new text", node.attributes.text)
        val widthWhileTyping = view.springLayout.getConstraint(javax.swing.SpringLayout.EAST, view.nodeEditor).value

        view.model.endNodeEdit()
        assertFalse(view.nodeEditor.isVisible)
        assertEquals("new text", view.g.nodes.single().attributes.text)
        view.g.undo()
        assertEquals("old", view.g.nodes.single().attributes.text)

        // the editor grew with the text
        view.model.startNodeEdit(view.g.nodes.single().id, null)
        assertTrue(widthWhileTyping > view.springLayout.getConstraint(javax.swing.SpringLayout.EAST, view.nodeEditor).value)
    }

    @Test
    fun `a node that is gone opens no editor`() {
        view.model.startNodeEdit("no-such-id", null)
        assertFalse(view.nodeEditor.isVisible)
    }
}
