@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import graph_tools.ApproximateTextMeasurer
import graph_tools.Edge
import graph_tools.HideCommand
import graph_tools.Node
import graph_tools.NodeShape
import utils.Rgb
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneTest {
    private val a = Node("A", Vector2(0, 0))
    private val b = Node("two\nlines", Vector2(200, 0))
    private val c = Node("C", Vector2(0, 200))
    private val view = EditorView(
        Graph().also { g ->
            listOf(a, b, c).forEach { g.add(it) }
            g.add(Edge(a, b))
            g.add(Edge(b, c))
        },
        NoRenderer,
    )

    private fun scene() = view.scene(ApproximateTextMeasurer)

    @Test
    fun `a scene has what a renderer needs to draw each node`() {
        a.attributes.bg = Rgb(1, 2, 3)
        b.setShape(NodeShape.Oval)
        b.attributes.other["fontname"] = "monospace"
        view.g.cleanSelect(listOf(b))
        val nodes = scene().nodes.associateBy { it.id }

        assertEquals(setOf(a.id, b.id, c.id), nodes.keys)
        assertEquals(Rgb(1, 2, 3), nodes.getValue(a.id).bg)
        assertEquals("box", nodes.getValue(a.id).shape)
        val two = nodes.getValue(b.id)
        assertEquals("oval", two.shape)
        assertEquals(listOf("two", "lines"), two.lines)
        assertTrue(two.monospace && two.selected && !nodes.getValue(a.id).selected)
        // measured while building the scene
        assertTrue(two.shapeBounds.x > two.textBounds.x && two.textBounds.x > 0)
    }

    @Test
    fun `edges end on their nodes' outlines`() {
        val edge = scene().edges.first()
        assertTrue(edge.src.x > a.position.x && edge.dst.x < b.position.x)
    }

    @Test
    fun `hidden nodes and their edges are left out`() {
        view.g.commit(HideCommand(listOf(b)))
        val s = scene()
        assertEquals(setOf(a.id, c.id), s.nodes.map { it.id }.toSet())
        assertTrue(s.edges.isEmpty())
    }

    @Test
    fun `the selection rectangle is there only while one is dragged`() {
        assertNull(scene().selectionBox)
        view.gestures.lastPosition = Vector2(50, 60)
        view.gestures.startBoxSelect(Vector2(10, 20))
        assertNotNull(scene().selectionBox)
    }
}
