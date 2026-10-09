package ui

import client.GestureController
import client.PointerInput
import utils.AwtUtils.toScreenspaceVector
import utils.Vector2
import java.awt.event.*
import java.awt.event.MouseEvent.*

/**
 * Passes AWT mouse events to the client's [PointerInput]. Its own
 * part is the cursor wrap: during a pan or a transform the cursor
 * re-enters at the opposite edge, and the client is told the
 * position as if it had not.
 */
class GraphMouseListener(
    val graphView: GraphView,
) : MouseListener, MouseMotionListener, MouseWheelListener {
    /** The view's gesture state machine. */
    val controller: GestureController get() = graphView.model.gestures

    private val pointer: PointerInput get() = graphView.model.pointer

    private fun button(e: MouseEvent): PointerInput.Button? = when (e.button) {
        BUTTON1 -> PointerInput.Button.Left
        BUTTON2 -> PointerInput.Button.Middle
        BUTTON3 -> PointerInput.Button.Right
        else -> null
    }

    override fun mouseClicked(e: MouseEvent) {
    }

    override fun mousePressed(e: MouseEvent) {
        button(e)?.let { pointer.pressed(e.toScreenspaceVector(), it, e.isControlDown, e.isShiftDown) }
    }

    private val cursorWrap = CursorWrap()

    /** Gesture the [cursorWrap] offset belongs to; a different one starts from zero. */
    private var wrapGesture: GestureController.States? = null

    /** The status bar overlays the canvas bottom; wrap above it. */
    private val statusBarInset: Int
        get() = if (graphView.statusBar != null) StatusBar.HEIGHT_PX else 0

    /** Pan and the three transforms wrap the cursor; box select doesn't. */
    private val wrapsCursor: Boolean
        get() = controller.state == GestureController.States.PanningWorkspace ||
            controller.state?.isTransformGesture == true

    /**
     * Cursor position for the gesture code, in canvas pixels:
     * virtual (unwrapped) while a wrapping gesture runs. Null for
     * a stale event from before a warp, which is to be ignored.
     */
    private fun gesturePosition(e: MouseEvent): Vector2? {
        if (controller.state != wrapGesture) {
            cursorWrap.reset()
            wrapGesture = controller.state
        }
        return if (wrapsCursor) cursorWrap.virtualPosition(e) else e.toScreenspaceVector()
    }

    override fun mouseDragged(e: MouseEvent) {
        gesturePosition(e)?.let { screen ->
            pointer.moved(screen, dragging = true, shift = e.isShiftDown)
            if (wrapsCursor) cursorWrap.wrapIfAtEdge(e, statusBarInset)
        }
    }

    override fun mouseMoved(e: MouseEvent) {
        // a waiting `g` grabs right away
        graphView.keyMapper?.mouseMoved()
        gesturePosition(e)?.let { screen ->
            pointer.moved(screen, dragging = false, shift = e.isShiftDown)
            if (wrapsCursor) cursorWrap.wrapIfAtEdge(e, statusBarInset)
        }
    }

    override fun mouseReleased(e: MouseEvent) {
        button(e)?.let { pointer.released(gesturePosition(e) ?: e.toScreenspaceVector(), Vector2(e.x, e.y), it, e.isControlDown) }
    }

    override fun mouseEntered(e: MouseEvent?) {}

    /** A fast modal move can leave the canvas between two move events; wrap from outside too. */
    override fun mouseExited(e: MouseEvent) {
        if (wrapsCursor && gesturePosition(e) != null) cursorWrap.wrapIfAtEdge(e, statusBarInset)
    }

    override fun mouseWheelMoved(e: MouseWheelEvent) {
        pointer.wheel(e.toScreenspaceVector(), e.unitsToScroll.toDouble())
    }
}
