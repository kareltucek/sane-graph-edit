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

    /**
     * Asked when the left or the right button is pressed on a node,
     * with the button as a key ([KeyMapper.LEFT_MOUSE],
     * [KeyMapper.RIGHT_MOUSE]) and what makes that node the
     * selection: true when the button is mapped to something, which
     * was then done ([KeyMapper.mouseKey]). Nobody asked: the buttons
     * do what they do out of the box.
     */
    var mouseKey: (key: String, select: () -> Unit) -> Boolean = { _, _ -> false }

    /** Whether [button], pressed at [pos], stands for keys, which were then run. */
    private fun mapped(pos: Vector2, button: Button, control: Boolean): Boolean {
        val key = when (button) {
            Button.Left -> KeyMapper.LEFT_MOUSE
            Button.Right -> KeyMapper.RIGHT_MOUSE
            Button.Middle -> null
        }
        val under = if (key == null || control || gestures.state != null) emptySet() else Clicker.selectClickedNode(view, pos)
        return key != null && under.isNotEmpty() && mouseKey(key) {
            view.endNodeEdit()
            view.endStylePicker()
            view.g.cleanSelect(under)
        }
    }

    private fun press(screen: Vector2, button: Button, control: Boolean, shift: Boolean) {
        if (mapped(workspace(screen), button, control)) view.repaint()
        else pressUnmapped(screen, button, control, shift)
    }

    private fun pressUnmapped(screen: Vector2, button: Button, control: Boolean, shift: Boolean) {
        val pos = workspace(screen)
        val starts = gestures.state == null
        if (button == Button.Left && starts) {
            val under = Clicker.selectClickedNode(view, pos)
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
        } else if (button == Button.Middle && starts) {
            // turns the view; in 2D mode, where nothing does, it pans
            if (!gestures.startOrbit(screen)) gestures.startPanView()
            pressAt(pos)
        }

        view.endNodeEdit()
        view.endStylePicker()

        if (button == Button.Right) {
            gestures.startStylePicker(Clicker.selectClickedNode(view, pos), screen)
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
            GestureController.States.Orbiting -> if (dragging) gestures.dragOrbit(screen)
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
        val middleGesture = gestures.state == GestureController.States.PanningWorkspace || gestures.state == GestureController.States.Orbiting
        if (button == Button.Left || (button == Button.Middle && middleGesture)) {
            if (gestures.state == GestureController.States.SelectionBox) gestures.dragOrEndSelectionBox(true)
            gestures.endSingleClick(workspace(screen), actual, control, view)
        }
        view.repaint()
    }

    /**
     * The wheel turned by [steps] (positive: towards the user) at
     * [screen]: zoom, keeping what is under the pointer where it is.
     * In 3D mode: towards the 3D cursor ([EditorView.cursor]), the
     * node the pointer is on or nearest to, which is what the view
     * turns around too; that stays where it is. How far one can zoom
     * in is then how near one can come to that node.
     */
    fun wheel(screen: Vector2, steps: Double) {
        view.endNodeEdit()
        view.endStylePicker()
        val t = view.viewTransform
        val cursor = if (t.threeD) view.cursor(screen) else null
        // the plane of what one goes towards is what the view speaks of from here on; what is seen stays as it is
        cursor?.let { t.refocus(it.depth, view.size / 2) }
        val around = cursor?.screen ?: screen
        val factor = Constants.zoomOutStep.pow(steps)
        val max = Constants.maxZoom
        if ((t.scale > 1 / max || factor > 1.0) && (t.scale < max || factor < 1.0)) {
            val before = workspace(around)
            t.zoom(factor)
            t.translate(workspace(around) - before)
            view.repaint()
        }
    }
}
