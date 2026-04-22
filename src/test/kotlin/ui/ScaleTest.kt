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
        // Plotter.t is a shared singleton — reset to identity so
        // bbox-centre projection (toScreenVector) is 1:1 and the
        // per-test cursor math is predictable regardless of what
        // earlier tests did to the transform.
        graph_tools.Plotter.t.setToIdentity()
        return GraphView(initialGraph = g)
    }

    @Test
    fun `dragScale doubles positions around bbox centre when cursor distance doubles`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        // Headless canvas has width/height 0, so screen centre
        // projects to world (0, 0). Start cursor at (50, 50)
        // (screen), drag to (100, 100): cursor distance from
        // screen centre doubles per axis → sx = sy = 2.
        // Anchor = bbox centre (50,50). Start cursor at (100,100)
        // — 50px from the anchor on each axis. Drag to (150,150)
        // — 100px from the anchor → factor doubles on both axes.
        v.lastScreenCursorPosition = Vector2(100.0, 100.0)
        v.mouseListener.controller.startOrEndScale()
        v.mouseListener.controller.dragScale(Vector2(150.0, 150.0))

        val byName = g.nodes.associateBy { it.attributes.text }
        // Anchor is bbox centre (50, 50).
        // new = anchor + 2 * (orig - anchor).
        // A (0,0)   → (50 + 2*(-50),  50 + 2*(-50))  = (-50, -50)
        // B (100,0) → (50 + 2*(50),   50 + 2*(-50))  = (150, -50)
        // C (0,100) → (50 + 2*(-50),  50 + 2*(50))   = (-50, 150)
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
        v.lastScreenCursorPosition = Vector2(100.0, 100.0)
        v.mouseListener.controller.startOrEndScale()
        // `x` key: constrain to X → lockY=true, Y frozen.
        v.mouseListener.controller.lockY = true
        v.mouseListener.controller.dragScale(Vector2(150.0, 150.0))

        val byName = g.nodes.associateBy { it.attributes.text }
        // X doubles around bbox centre; Y pinned at originals.
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
        v.lastScreenCursorPosition = Vector2(100.0, 100.0)
        v.mouseListener.controller.startOrEndScale()
        // `y` key: constrain to Y → lockX=true, X frozen.
        v.mouseListener.controller.lockX = true
        v.mouseListener.controller.dragScale(Vector2(150.0, 150.0))

        val byName = g.nodes.associateBy { it.attributes.text }
        // Y doubles around bbox centre; X pinned.
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

        // Anchor = bbox centre (50,50). Start cursor at (100,100)
        // — 50px from the anchor on each axis. Drag to (150,150)
        // — 100px from the anchor → factor doubles on both axes.
        v.lastScreenCursorPosition = Vector2(100.0, 100.0)
        v.mouseListener.controller.startOrEndScale()
        v.mouseListener.controller.dragScale(Vector2(150.0, 150.0))
        // Something moved — B's x went from 100 to 150.
        assertEquals(150.0, g.nodes.first { it.attributes.text == "B" }.position.x, absoluteTolerance = 0.001)

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

        // Anchor = bbox centre (50,50). Start cursor at (100,100)
        // — 50px from the anchor on each axis. Drag to (150,150)
        // — 100px from the anchor → factor doubles on both axes.
        v.lastScreenCursorPosition = Vector2(100.0, 100.0)
        v.mouseListener.controller.startOrEndScale()
        v.mouseListener.controller.dragScale(Vector2(150.0, 150.0))
        // Second toggle commits.
        v.mouseListener.controller.startOrEndScale()

        val b = g.nodes.first { it.attributes.text == "B" }
        assertEquals(150.0, b.position.x, absoluteTolerance = 0.001)

        g.history.undo()
        for ((node, orig) in originals) {
            assertEquals(orig, node.position)
        }
    }

    @Test
    fun `scale commits on click-release via endSingleClick`() {
        // Regression: pressing `s`, dragging, then clicking
        // must land one MoveNodesCommand on the undo stack and
        // flip the dirty flag. Previously endSingleClick called
        // commitMoveIfAny only (committed grab, not scale).
        val g = trianglesGraph()
        val v = viewWith(g)
        val originals = g.nodes.associateWith { it.position }
        assertFalse(v.isDirty)

        // Anchor = bbox centre (50,50). Start cursor at (100,100)
        // — 50px from the anchor on each axis. Drag to (150,150)
        // — 100px from the anchor → factor doubles on both axes.
        v.lastScreenCursorPosition = Vector2(100.0, 100.0)
        v.mouseListener.controller.startOrEndScale()
        v.mouseListener.controller.dragScale(Vector2(150.0, 150.0))
        // Drive the click path directly. endSingleClick is the
        // mouse-release terminal — it's what ends the gesture
        // when the user clicks mid-scale.
        v.mouseListener.controller.commitActiveTransform()
        v.mouseListener.controller.state = null

        // Dirty flag should be set and undo should revert.
        assertTrue(v.isDirty)
        val b = g.nodes.first { it.attributes.text == "B" }
        assertEquals(150.0, b.position.x, absoluteTolerance = 0.001)
        g.history.undo()
        for ((node, orig) in originals) {
            assertEquals(orig, node.position)
        }
    }

    @Test
    fun `scale re-bootstraps reference when cursor starts near the anchor`() {
        // Anchor is bbox centre (50, 50). Start cursor at (51, 51)
        // — inside the 4px dead-band on both axes. Scale should
        // freeze at 1.0; selection unchanged.
        val g = trianglesGraph()
        val v = viewWith(g)
        v.lastScreenCursorPosition = Vector2(51.0, 51.0)
        v.mouseListener.controller.startOrEndScale()
        v.mouseListener.controller.dragScale(Vector2(52.0, 52.0))

        val b = g.nodes.first { it.attributes.text == "B" }
        assertEquals(100.0, b.position.x, absoluteTolerance = 0.001)
        assertEquals(0.0, b.position.y, absoluteTolerance = 0.001)

        // Cursor jumps outside the band → reference re-bootstraps
        // at the crossing cursor position. From (100,100) out to
        // (150,150) is a 2x factor (dist from anchor doubles).
        v.mouseListener.controller.dragScale(Vector2(100.0, 100.0))
        v.mouseListener.controller.dragScale(Vector2(150.0, 150.0))
        // Anchor (50,50). B (100,0) → (50+2*50, 50+2*(-50)) = (150, -50).
        assertEquals(150.0, b.position.x, absoluteTolerance = 0.001)
        assertEquals(-50.0, b.position.y, absoluteTolerance = 0.001)
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

        // Blender semantics: the letter names the axis that
        // stays free.
        assertEquals(
            listOf("constrain-axis-x", "constrain-axis-y", "scale"),
            executed,
        )
    }

    @Test
    fun `axis constraint is radio-style — tapping the other axis releases the first`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        v.lastScreenCursorPosition = Vector2(50.0, 50.0)
        v.mouseListener.controller.startOrEndScale()

        // Constrain to X: X free, Y locked.
        GraphKeyListener.impl.constrainAxisX(v)
        assertFalse(v.mouseListener.controller.lockX)
        assertTrue(v.mouseListener.controller.lockY)

        // Constrain to Y: Y free, X locked.
        GraphKeyListener.impl.constrainAxisY(v)
        assertTrue(v.mouseListener.controller.lockX)
        assertFalse(v.mouseListener.controller.lockY)

        // Tap Y again → release to free 2D scale.
        GraphKeyListener.impl.constrainAxisY(v)
        assertFalse(v.mouseListener.controller.lockX)
        assertFalse(v.mouseListener.controller.lockY)
    }

    @Test
    fun `grab with lockY zeroes y component of dragMoveNode delta`() {
        val g = trianglesGraph()
        val v = viewWith(g)
        // Simulate "cursor was at (0,0), user toggled grab,
        // then dragged cursor to (50,50)". startOrEndMove
        // anchors at the view's current lastCursorPosition.
        v.lastCursorPosition = Vector2(0.0, 0.0)
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
    fun `axis-lock toggled mid-grab reverts drift on newly-frozen axis`() {
        // Start at (0,0), drag to (40, 30) with both axes free —
        // nodes move by (+40, +30). Then lock Y. With from-start
        // recomputation, the Y drift (+30) must revert to 0 even
        // though the cursor hasn't moved.
        val g = trianglesGraph()
        val v = viewWith(g)
        v.lastCursorPosition = Vector2(0.0, 0.0)
        val c = v.mouseListener.controller
        c.lastPosition = Vector2(0.0, 0.0)
        c.startOrEndMove()
        c.dragMoveNode(Vector2(40.0, 30.0), restrictOperator = false)
        // Verify the diagonal drag went through.
        val aAfterDrag = g.nodes.first { it.attributes.text == "A" }
        assertEquals(40.0, aAfterDrag.position.x, absoluteTolerance = 0.001)
        assertEquals(30.0, aAfterDrag.position.y, absoluteTolerance = 0.001)

        // Lock Y and re-drag at the same cursor — Y should snap
        // back to origin.
        c.lockY = true
        c.dragMoveNode(Vector2(40.0, 30.0), restrictOperator = false)
        assertEquals(40.0, aAfterDrag.position.x, absoluteTolerance = 0.001)
        assertEquals(0.0, aAfterDrag.position.y, absoluteTolerance = 0.001)
    }

    @Test
    fun `any exit from a transform gesture restores Normal mode via the state setter`() {
        // Regression: clicking during modal grab used to drop the
        // state but leave KeyMapper.mode stuck at Transform. The
        // state setter now runs a shared onGestureEnded() hook
        // whenever a transform gesture transitions to null, so any
        // exit path (commit, cancel, click-release, anything
        // future) gets the cleanup for free.
        val g = trianglesGraph()
        val v = viewWith(g)
        val mapper = KeyMapper(KeyMapper.defaultBindings()).also {
            it.installDefaultTransformBindings()
        }
        v.keyMapper = mapper
        val c = v.mouseListener.controller

        // Modal grab → Transform.
        v.lastCursorPosition = Vector2(0.0, 0.0)
        c.lastPosition = Vector2(0.0, 0.0)
        c.startOrEndMove()
        assertEquals(KeyMapper.Mode.Transform, mapper.mode)
        c.lockX = true

        // Simulate a mouse-click exit — drop state the way
        // endSingleClick does, without going through
        // startOrEndMove.
        c.state = null
        assertEquals(KeyMapper.Mode.Normal, mapper.mode)
        assertFalse(c.lockX)
        assertFalse(c.lockY)
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
