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
    fun `rotate dragRotate produces a 90 degree turn`() {
        // rotate is now modal: first call captures state and
        // enters Rotating mode, mouse moves rotate continuously
        // around the bounding-box centre. To unit-test the
        // geometry, we drive the controller directly: capture
        // starting angle (cursor at 3 o'clock relative to centre
        // (50, 50), i.e. position (150, 50) → angle 0), then
        // drag to 6 o'clock (position (50, 150) → angle +π/2)
        // and verify a clean quarter turn clockwise.
        val g = trianglesGraph()
        val v = viewWith(g)
        v.lastCursorPosition = Vector2(150.0, 50.0)  // angle 0
        v.mouseListener.controller.startOrEndRotate()
        v.mouseListener.controller.dragRotate(Vector2(50.0, 150.0))  // angle π/2

        val byName = g.nodes.associateBy { it.attributes.text }
        // Clockwise 90°: (dx, dy) → (-dy, dx); new = (cx - dy, cy + dx).
        // A (0, 0): dx=-50, dy=-50 → (50-(-50), 50+(-50)) = (100, 0)
        // B (100, 0): dx=50, dy=-50 → (50-(-50), 50+50) = (100, 100)
        // C (0, 100): dx=-50, dy=50 → (50-50, 50+(-50)) = (0, 0)
        assertEquals(100.0, byName["A"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(0.0, byName["A"]!!.position.y, absoluteTolerance = 0.001)
        assertEquals(100.0, byName["B"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(100.0, byName["B"]!!.position.y, absoluteTolerance = 0.001)
        assertEquals(0.0, byName["C"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(0.0, byName["C"]!!.position.y, absoluteTolerance = 0.001)
    }

    @Test
    fun `rotate cancelled via Escape restores original positions`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        val originals = g.nodes.associateWith { it.position }

        v.lastCursorPosition = Vector2(150.0, 50.0)
        v.mouseListener.controller.startOrEndRotate()
        v.mouseListener.controller.dragRotate(Vector2(50.0, 150.0))
        // Positions have moved away from originals at this point.
        val aMid = g.nodes.first { it.attributes.text == "A" }
        assertEquals(100.0, aMid.position.x, absoluteTolerance = 0.001)

        // Cancel — should revert to the originals.
        assertEquals(true, v.mouseListener.controller.cancelActiveModal())
        for ((node, orig) in originals) {
            assertEquals(orig.x, node.position.x, absoluteTolerance = 0.001)
            assertEquals(orig.y, node.position.y, absoluteTolerance = 0.001)
        }
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
