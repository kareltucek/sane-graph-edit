package client

import utils.Constants
import utils.Vector2
import kotlin.math.pow

/**
 * What the buttons and the wheel of a pointing device do in a
 * view (conn1, renderer → client): a renderer reports presses,
 * movement and releases with positions on its screen, and this
 * turns them into the [GestureController]'s gestures.
 *
 * Positions are in view pixels. A renderer that wraps the cursor
 * around the view's edges during a gesture passes the unwrapped
 * position.
 */
class PointerInput(private val view: EditorView) {
    enum class Button { Left, Middle, Right }

    private val gestures: GestureController get() = view.gestures

    private fun workspace(screen: Vector2): Vector2 = view.viewTransform.toWorkspace(screen)

    fun pressed(screen: Vector2, button: Button, control: Boolean, shift: Boolean) {
        // nothing starts on a view that waits for its content
        if (!view.content.isBusy) press(screen, button, control, shift)
    }

    private fun press(screen: Vector2, button: Button, control: Boolean, shift: Boolean) {
        val pos = workspace(screen)
        val starts = gestures.state == null
        if (button == Button.Left && starts) {
            val under = Clicker.selectClickedNode(view.g, pos, view.viewTransform.scale)
            when {
                // move one node, or several
                !control && under.isNotEmpty() && view.g.selectedNodes.size <= 1 -> gestures.startMoveOrSelectSingleNode(under)
                !control && under.isNotEmpty() -> gestures.startMoveMultipleNodes(under)
                !control -> gestures.startPanView()
                // in empty space: a selection box, adding to the selection; on a node: toggle it
                under.isEmpty() -> gestures.startBoxSelect(pos)
                under.size == 1 -> gestures.startToggleMultiselectState(under)
            }
            pressAt(pos)
        } else if (button == Button.Middle && shift && starts) {
            gestures.startPanView()
            pressAt(pos)
        }

        view.endNodeEdit()
        view.endStylePicker()

        if (button == Button.Right) {
            gestures.startStylePicker(Clicker.selectClickedNode(view.g, pos, view.viewTransform.scale), screen)
        }
        view.repaint()
    }

    private fun pressAt(pos: Vector2) {
        gestures.pressedAt = pos
        gestures.pressedTime = utils.Platform.nowMillis()
        gestures.lastPosition = pos
    }

    /** The pointer moved, with a button held ([dragging]) or without. */
    fun moved(screen: Vector2, dragging: Boolean, shift: Boolean) {
        val pos = workspace(screen)
        when (gestures.state) {
            GestureController.States.MovingNodes -> gestures.dragMoveNode(pos, shift)
            GestureController.States.Rotating -> gestures.dragRotate(pos)
            GestureController.States.Scaling -> gestures.dragScale(screen)
            GestureController.States.PanningWorkspace -> if (dragging) gestures.dragPanView(pos)
            GestureController.States.SelectionBox -> if (dragging) gestures.dragOrEndSelectionBox(false)
            null -> Unit
        }
        // again: panning changed the transform, so the same screen point is another workspace point
        val now = workspace(screen)
        view.lastCursorPosition = now
        view.lastScreenCursorPosition = screen
        gestures.lastPosition = now
    }

    /** [screen] unwrapped, so that a drag that wrapped around is not taken for a click; [actual] is where the pointer really is. */
    fun released(screen: Vector2, actual: Vector2, button: Button, control: Boolean) {
        if (button == Button.Left || (button == Button.Middle && gestures.state == GestureController.States.PanningWorkspace)) {
            if (gestures.state == GestureController.States.SelectionBox) gestures.dragOrEndSelectionBox(true)
            gestures.endSingleClick(workspace(screen), actual, control, view)
        }
        view.repaint()
    }

    /** The wheel turned by [steps] (positive: towards the user) at [screen]: zoom, keeping what is under the pointer where it is. */
    fun wheel(screen: Vector2, steps: Double) {
        view.endNodeEdit()
        view.endStylePicker()
        val t = view.viewTransform
        val factor = Constants.zoomOutStep.pow(steps)
        val max = Constants.maxZoom
        if ((t.scale > 1 / max || factor > 1.0) && (t.scale < max || factor < 1.0)) {
            val before = workspace(screen)
            t.zoom(factor)
            t.translate(workspace(screen) - before)
            view.repaint()
        }
    }
}
