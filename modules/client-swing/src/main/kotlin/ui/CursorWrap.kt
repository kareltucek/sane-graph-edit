package ui

import utils.Vector2
import java.awt.Component
import java.awt.Point
import java.awt.Robot
import java.awt.event.MouseEvent
import kotlin.math.abs

/**
 * Blender-style cursor wrapping for drag gestures (pan, grab, rotate,
 * scale): when the pointer reaches the canvas edge it is warped to the
 * opposite side, and the gesture keeps seeing a *virtual* cursor that
 * continued past the edge.
 *
 * The virtual position is the event position plus [offset]; every warp
 * moves the physical pointer by some delta and subtracts that delta
 * from [offset], so the virtual position doesn't jump. Events that were
 * already queued before the warp still carry pre-warp coordinates;
 * they are dropped until an event lands near the warp target.
 */
class CursorWrap(
    /**
     * Move the physical pointer to a canvas-local point; false when
     * that's impossible. Tests replace the [Robot]-based default.
     */
    private val movePointer: (Component, Point) -> Boolean = ::robotMove,
) {
    /** Virtual minus physical cursor position, in canvas pixels. */
    private var offset: Vector2 = Vector2.Zero

    /** Where the last warp put the pointer, until an event confirms it. */
    private var pendingTarget: Point? = null
    private var droppedEvents = 0

    /** Start from the physical position again (new gesture). */
    fun reset() {
        offset = Vector2.Zero
        pendingTarget = null
    }

    /**
     * The event's virtual position in canvas pixels, or null for a
     * stale pre-warp event that should be ignored.
     */
    fun virtualPosition(e: MouseEvent): Vector2? {
        val target = pendingTarget
        val stale = target != null && droppedEvents < MAX_DROPPED_EVENTS && (
            abs(e.x - target.x) > e.component.width / 4 || abs(e.y - target.y) > e.component.height / 4
        )
        if (stale) {
            droppedEvents++
        } else {
            pendingTarget = null
        }
        return if (stale) null else Vector2(e.x + offset.x, e.y + offset.y)
    }

    /**
     * If [e] is within [MARGIN] pixels of an edge (or already outside
     * the canvas), warp the pointer to the opposite side. The bottom
     * [bottomInset] pixels of the canvas count as outside (the status
     * bar overlays them and would swallow the events).
     */
    fun wrapIfAtEdge(e: MouseEvent, bottomInset: Int) {
        val target = Point(wrapped(e.x, e.component.width), wrapped(e.y, e.component.height - bottomInset))
        val moved = target.x != e.x || target.y != e.y
        if (moved && movePointer(e.component, target)) {
            offset += Vector2((e.x - target.x).toDouble(), (e.y - target.y).toDouble())
            pendingTarget = target
            droppedEvents = 0
        }
    }

    /**
     * New coordinate along an axis of [size] pixels; unchanged when
     * away from the edges. Past one edge → in from the opposite one
     * (at that edge if the pointer went further out than a canvas
     * width).
     */
    private fun wrapped(v: Int, size: Int): Int {
        val low = MARGIN
        val high = size - 1 - MARGIN
        val jump = size - 3 * MARGIN
        return when {
            size < 8 * MARGIN -> v
            v < low -> (v + jump).takeIf { it >= low }?.coerceAtMost(high) ?: high
            v > high -> (v - jump).takeIf { it <= high }?.coerceAtLeast(low) ?: low
            else -> v
        }
    }

    companion object {
        /** Distance from the edge (px) at which the pointer wraps. */
        const val MARGIN = 3

        /** Give up waiting for the warp to land after this many events. */
        const val MAX_DROPPED_EVENTS = 20

        /** Null when warping is impossible (headless, no permission). */
        private val robot: Robot? by lazy {
            try {
                Robot()
            } catch (_: Exception) {
                null
            }
        }

        private fun robotMove(c: Component, p: Point): Boolean {
            val r = robot
            val possible = r != null && c.isShowing
            if (possible) {
                val origin = c.locationOnScreen
                r!!.mouseMove(origin.x + p.x, origin.y + p.y)
            }
            return possible
        }
    }
}
