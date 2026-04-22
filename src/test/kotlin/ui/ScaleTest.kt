package ui

import Graph
import graph_tools.Node
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Geometry and state-machine tests for the scale gesture, plus
 * smoke tests for Transform-mode key dispatch. Mirrors the shape
 * of [MirrorRotateTest].
 *
 * Canvas width/height aren't configured in headless tests, so
 * `graphCanvas.width / 2` is `0`. That makes the "screen centre"
 * the origin of the cursor-coordinate space we pass in — which
 * is fine, because the scale factor is a *ratio* and is
 * self-consistent whatever reference we pick. The tests treat
 * the start-cursor values as offsets from the centre directly.
 */
class ScaleTest {

    /**
     * Three-node triangle: (0,0), (100,0), (0,100). Bounding-box
     * centre (50,50). All selected.
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
        System.setProperty("java.awt.headless", "true")
        return GraphView(initialGraph = g)
    }

    @Test
    fun `dragScale doubles positions around bbox centre when cursor distance doubles`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        // Start cursor at (50, 50) in screen coords → distance
        // from screen centre (0, 0) is (50, 50).
        v.lastScreenCursorPosition = Vector2(50.0, 50.0)
        v.mouseListener.controller.startOrEndScale()
        // Move cursor to (100, 100) → distance doubles → sx=sy=2.
        v.mouseListener.controller.dragScale(Vector2(100.0, 100.0))

        val byName = g.nodes.associateBy { it.attributes.text }
        // Anchor is bbox centre (50, 50). Each node:
        //   new = anchor + 2 * (orig - anchor)
        // A (0,0):    anchor + 2*(-50,-50)  = (-50, -50)
        // B (100,0):  anchor + 2*(50,-50)   = (150, -50)
        // C (0,100):  anchor + 2*(-50, 50)  = (-50, 150)
        assertEquals(-50.0, byName["A"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(-50.0, byName["A"]!!.position.y, absoluteTolerance = 0.001)
        assertEquals(150.0, byName["B"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(-50.0, byName["B"]!!.position.y, absoluteTolerance = 0.001)
        assertEquals(-50.0, byName["C"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(150.0, byName["C"]!!.position.y, absoluteTolerance = 0.001)
    }

    @Test
    fun `dragScale with lockY holds y coords at originals`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        v.lastScreenCursorPosition = Vector2(50.0, 50.0)
        v.mouseListener.controller.startOrEndScale()
        v.mouseListener.controller.lockY = true   // `x` key: constrain to X
        // Double X, double Y in the factor — lockY should nuke Y.
        v.mouseListener.controller.dragScale(Vector2(100.0, 100.0))

        val byName = g.nodes.associateBy { it.attributes.text }
        // X scales as before; Y pinned to original values.
        assertEquals(-50.0, byName["A"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(0.0, byName["A"]!!.position.y, absoluteTolerance = 0.001)
        assertEquals(150.0, byName["B"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(0.0, byName["B"]!!.position.y, absoluteTolerance = 0.001)
        assertEquals(-50.0, byName["C"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(100.0, byName["C"]!!.position.y, absoluteTolerance = 0.001)
    }

    @Test
    fun `dragScale with lockX holds x coords at originals`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        v.lastScreenCursorPosition = Vector2(50.0, 50.0)
        v.mouseListener.controller.startOrEndScale()
        v.mouseListener.controller.lockX = true   // `y` key: constrain to Y
        v.mouseListener.controller.dragScale(Vector2(100.0, 100.0))

        val byName = g.nodes.associateBy { it.attributes.text }
        // Y scales; X pinned.
        assertEquals(0.0, byName["A"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(-50.0, byName["A"]!!.position.y, absoluteTolerance = 0.001)
        assertEquals(100.0, byName["B"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(-50.0, byName["B"]!!.position.y, absoluteTolerance = 0.001)
        assertEquals(0.0, byName["C"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(150.0, byName["C"]!!.position.y, absoluteTolerance = 0.001)
    }

    @Test
    fun `scale cancelled via Escape restores original positions`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        val originals = g.nodes.associateWith { it.position }

        v.lastScreenCursorPosition = Vector2(50.0, 50.0)
        v.mouseListener.controller.startOrEndScale()
        v.mouseListener.controller.dragScale(Vector2(100.0, 100.0))
        // Something moved.
        assertEquals(-50.0, g.nodes.first { it.attributes.text == "A" }.position.x, absoluteTolerance = 0.001)

        // Cancel — positions revert; state clears; history untouched.
        assertTrue(v.mouseListener.controller.cancelActiveModal())
        for ((node, orig) in originals) {
            assertEquals(orig.x, node.position.x, absoluteTolerance = 0.001)
            assertEquals(orig.y, node.position.y, absoluteTolerance = 0.001)
        }
    }

    @Test
    fun `scale commits one undoable step`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        val originals = g.nodes.associateWith { it.position }

        v.lastScreenCursorPosition = Vector2(50.0, 50.0)
        v.mouseListener.controller.startOrEndScale()
        v.mouseListener.controller.dragScale(Vector2(100.0, 100.0))
        // Second toggle commits.
        v.mouseListener.controller.startOrEndScale()

        val a = g.nodes.first { it.attributes.text == "A" }
        assertEquals(-50.0, a.position.x, absoluteTolerance = 0.001)

        g.history.undo()
        for ((node, orig) in originals) {
            assertEquals(orig, node.position)
        }
    }

    @Test
    fun `scale re-bootstraps reference when cursor starts near screen centre`() {
        // Start cursor at (1, 1) — well inside the 4px dead-band
        // around screen centre (0, 0). Scale should freeze both
        // axes at 1.0; the selection must not move.
        val g = trianglesGraph()
        val v = viewWith(g)
        v.lastScreenCursorPosition = Vector2(1.0, 1.0)
        v.mouseListener.controller.startOrEndScale()
        v.mouseListener.controller.dragScale(Vector2(2.0, 2.0))

        val a = g.nodes.first { it.attributes.text == "A" }
        assertEquals(0.0, a.position.x, absoluteTolerance = 0.001)
        assertEquals(0.0, a.position.y, absoluteTolerance = 0.001)

        // Now cursor jumps outside the band → reference
        // re-bootstraps at the crossing cursor position. After
        // re-bootstrap, moving back to the same point gives 1.0
        // (no movement); moving further out scales from there.
        v.mouseListener.controller.dragScale(Vector2(100.0, 100.0))
        // This new state defines (100, 100) as sx=sy=1 reference.
        // Dragging to (200, 200) doubles it.
        v.mouseListener.controller.dragScale(Vector2(200.0, 200.0))
        // A (0,0): anchor + 2*(-50,-50) = (-50, -50)
        assertEquals(-50.0, a.position.x, absoluteTolerance = 0.001)
        assertEquals(-50.0, a.position.y, absoluteTolerance = 0.001)
    }

    @Test
    fun `startOrEndScale while another gesture is active is a no-op`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        // Enter rotate mode first.
        v.lastCursorPosition = Vector2(150.0, 50.0)
        v.mouseListener.controller.startOrEndRotate()
        assertEquals(
            GraphMouseListener.GraphMouseController.States.Rotating,
            v.mouseListener.controller.state,
        )
        // Try to enter scale — should be ignored.
        v.lastScreenCursorPosition = Vector2(50.0, 50.0)
        v.mouseListener.controller.startOrEndScale()
        assertEquals(
            GraphMouseListener.GraphMouseController.States.Rotating,
            v.mouseListener.controller.state,
        )
    }

    @Test
    fun `Transform mode swallows unbound keys`() {
        // Route a dummy command executor so we can observe what
        // fires (or doesn't). Use a standalone mapper — no view.
        val executed = mutableListOf<String>()
        val mapper = KeyMapper(KeyMapper.defaultBindings())
        mapper.commandExecutor = { cmd, _ -> executed += cmd }
        mapper.installDefaultTransformBindings()
        mapper.activeView = GraphView(initialGraph = Graph())
        mapper.mode = KeyMapper.Mode.Transform

        mapper.feedKey("u")          // undo — would fire in Normal
        mapper.feedKey("d")          // delete — would fire in Normal
        mapper.feedKey("<Space>")    // edit-node — would fire in Normal
        assertTrue(executed.isEmpty(), "Unbound Transform keys should swallow, got $executed")
    }

    @Test
    fun `Transform mode fires axis-lock bindings`() {
        val executed = mutableListOf<String>()
        val mapper = KeyMapper(KeyMapper.defaultBindings())
        mapper.commandExecutor = { cmd, _ -> executed += cmd }
        mapper.installDefaultTransformBindings()
        mapper.activeView = GraphView(initialGraph = Graph())
        mapper.mode = KeyMapper.Mode.Transform

        mapper.feedKey("x")
        mapper.feedKey("y")
        mapper.feedKey("s")

        assertEquals(
            listOf("toggle-axis-lock-y", "toggle-axis-lock-x", "scale"),
            executed,
        )
    }

    @Test
    fun `grab with lockY zeroes y component of dragMoveNode delta`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        // Drive the controller directly — simulate "grab started,
        // then cursor moved from (0,0) to (50,50)".
        val c = v.mouseListener.controller
        c.lastPosition = Vector2(0.0, 0.0)
        c.startOrEndMove()
        c.lockY = true
        c.dragMoveNode(Vector2(50.0, 50.0), restrictOperator = false)

        // Every selected node should have moved by (+50, 0).
        val byName = g.nodes.associateBy { it.attributes.text }
        assertEquals(50.0, byName["A"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(0.0, byName["A"]!!.position.y, absoluteTolerance = 0.001)
        assertEquals(150.0, byName["B"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(0.0, byName["B"]!!.position.y, absoluteTolerance = 0.001)
        assertEquals(50.0, byName["C"]!!.position.x, absoluteTolerance = 0.001)
        assertEquals(100.0, byName["C"]!!.position.y, absoluteTolerance = 0.001)
    }

    @Test
    fun `entering scale flips KeyMapper to Transform mode, exiting restores Normal`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        val mapper = KeyMapper(KeyMapper.defaultBindings()).also {
            it.installDefaultTransformBindings()
        }
        v.keyMapper = mapper

        v.lastScreenCursorPosition = Vector2(50.0, 50.0)
        v.mouseListener.controller.startOrEndScale()
        assertEquals(KeyMapper.Mode.Transform, mapper.mode)
        v.mouseListener.controller.startOrEndScale()   // commit
        assertEquals(KeyMapper.Mode.Normal, mapper.mode)

        // Cancel path: enter again, then cancel.
        v.mouseListener.controller.startOrEndScale()
        assertEquals(KeyMapper.Mode.Transform, mapper.mode)
        v.mouseListener.controller.cancelActiveModal()
        assertEquals(KeyMapper.Mode.Normal, mapper.mode)
        // Axis-locks should also reset on exit.
        assertFalse(v.mouseListener.controller.lockX)
        assertFalse(v.mouseListener.controller.lockY)
    }
}
