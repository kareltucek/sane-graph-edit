package ui

import utils.Vector2
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CursorWrapTest {

    private val canvas = JPanel().apply { setSize(400, 300) }

    private fun move(x: Int, y: Int) =
        MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0, x, y, 0, false)

    private val warps = mutableListOf<Point>()
    private val wrap = CursorWrap { _, p -> warps.add(p); true }

    @Test
    fun `away from the edges nothing warps and the position is physical`() {
        assertEquals(Vector2(200.0, 150.0), wrap.virtualPosition(move(200, 150)))
        wrap.wrapIfAtEdge(move(200, 150), bottomInset = 0)
        assertEquals(emptyList(), warps)
    }

    @Test
    fun `crossing the right edge warps left and the virtual cursor continues`() {
        wrap.wrapIfAtEdge(move(398, 150), bottomInset = 0)
        assertEquals(listOf(Point(398 - (400 - 3 * CursorWrap.MARGIN), 150)), warps)

        // the next real event arrives near the warp target, 5px further right
        val after = move(warps[0].x + 5, 150)
        assertEquals(Vector2(403.0, 150.0), wrap.virtualPosition(after))
    }

    @Test
    fun `stale events queued before the warp are dropped`() {
        wrap.wrapIfAtEdge(move(398, 150), bottomInset = 0)
        assertNull(wrap.virtualPosition(move(399, 150)))
        assertEquals(Vector2(403.0, 150.0), wrap.virtualPosition(move(warps[0].x + 5, 150)))
        // once confirmed, the region near the old edge is fine again (offset still applies)
        assertEquals(Vector2(399.0 + 391, 150.0), wrap.virtualPosition(move(399, 150)))
    }

    @Test
    fun `status bar inset moves the bottom edge up`() {
        wrap.wrapIfAtEdge(move(200, 300 - 22 - 1), bottomInset = 22)
        assertEquals(1, warps.size)
        assertEquals(200, warps[0].x)
    }

    @Test
    fun `a pointer far outside is clamped into the canvas`() {
        wrap.wrapIfAtEdge(move(-500, 150), bottomInset = 0)
        assertEquals(Point(400 - 1 - CursorWrap.MARGIN, 150), warps.single())
        assertEquals(Vector2(-500.0, 150.0), wrap.virtualPosition(move(warps[0].x, 150)))
    }

    @Test
    fun `reset forgets the offset`() {
        wrap.wrapIfAtEdge(move(398, 150), bottomInset = 0)
        wrap.reset()
        assertEquals(Vector2(10.0, 150.0), wrap.virtualPosition(move(10, 150)))
    }
}
