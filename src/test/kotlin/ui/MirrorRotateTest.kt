package ui

import Graph
import graph_tools.Edge
import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals

class MirrorRotateTest {

    /**
     * Build a graph with three nodes at (0,0), (100,0), (0,100)
     * — bounding box (0,0)–(100,100), center (50,50). All
     * selected.
     */
    private fun trianglesGraph(): Graph {
        val g = Graph()
        val a = Node("A", Vector2(0, 0))
        val b = Node("B", Vector2(100, 0))
        val c = Node("C", Vector2(0, 100))
        g.add(a); g.add(b); g.add(c)
        g.cleanSelect(setOf(a, b, c))
        return g
    }

    private fun viewWith(g: Graph): GraphView {
        // Headless construction: GraphView allocates Swing
        // components, but with java.awt.headless they don't try
        // to open a display. Same trick as the SVG writer tests.
        System.setProperty("java.awt.headless", "true")
        return GraphView(initialGraph = g)
    }

    @Test
    fun `hmirror flips x around bounding-box center`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        GraphKeyListener.impl.mirror(v, horizontal = true)

        val byName = g.nodes.associateBy { it.attributes.text }
        // Center x = 50. So x' = 2*50 - x = 100 - x.
        assertEquals(Vector2(100.0, 0.0), byName["A"]!!.position)  // 0 → 100
        assertEquals(Vector2(0.0, 0.0), byName["B"]!!.position)    // 100 → 0
        assertEquals(Vector2(100.0, 100.0), byName["C"]!!.position) // 0 → 100, y unchanged
    }

    @Test
    fun `vmirror flips y around bounding-box center`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        GraphKeyListener.impl.mirror(v, horizontal = false)

        val byName = g.nodes.associateBy { it.attributes.text }
        // Center y = 50. So y' = 2*50 - y = 100 - y.
        assertEquals(Vector2(0.0, 100.0), byName["A"]!!.position)  // 0 → 100
        assertEquals(Vector2(100.0, 100.0), byName["B"]!!.position) // 0 → 100
        assertEquals(Vector2(0.0, 0.0), byName["C"]!!.position)    // 100 → 0
    }

    @Test
    fun `toggle-rotate four times returns to start`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        val originals = g.nodes.associateWith { it.position }

        repeat(4) { GraphKeyListener.impl.toggleRotate(v) }

        for ((node, orig) in originals) {
            assertEquals(orig.x, node.position.x, absoluteTolerance = 0.001)
            assertEquals(orig.y, node.position.y, absoluteTolerance = 0.001)
        }
    }

    @Test
    fun `toggle-rotate produces a 90 degree turn`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        GraphKeyListener.impl.toggleRotate(v)

        val byName = g.nodes.associateBy { it.attributes.text }
        // Center (50, 50). Clockwise 90° in screen-space:
        // (dx, dy) → (-dy, dx). So new = (cx - dy, cy + dx).
        // A was at (0, 0): dx=-50, dy=-50 → new = (50-(-50), 50+(-50)) = (100, 0).
        // B was at (100, 0): dx=50, dy=-50 → new = (50-(-50), 50+50) = (100, 100).
        // C was at (0, 100): dx=-50, dy=50 → new = (50-50, 50+(-50)) = (0, 0).
        assertEquals(Vector2(100.0, 0.0), byName["A"]!!.position)
        assertEquals(Vector2(100.0, 100.0), byName["B"]!!.position)
        assertEquals(Vector2(0.0, 0.0), byName["C"]!!.position)
    }

    @Test
    fun `mirror is undoable`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        val originals = g.nodes.associateWith { it.position }

        GraphKeyListener.impl.mirror(v, horizontal = true)
        // Verify something changed
        val a = g.nodes.first { it.attributes.text == "A" }
        assertEquals(100.0, a.position.x, absoluteTolerance = 0.001)

        g.history.undo()
        for ((node, orig) in originals) {
            assertEquals(orig, node.position)
        }
    }

    @Test
    fun `mirror with empty selection is a no-op`() {
        val g = Graph()
        val a = Node("A", Vector2(42, 42))
        g.add(a)
        // No selection
        val v = viewWith(g)
        GraphKeyListener.impl.mirror(v, horizontal = true)
        assertEquals(Vector2(42.0, 42.0), a.position)
    }
}
