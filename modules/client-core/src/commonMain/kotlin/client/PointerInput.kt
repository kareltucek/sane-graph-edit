package client

import graph_tools.ManualSize
import graph_tools.Node
import graph_tools.SetManualSizeCommand
import utils.Constants
import utils.Vector2
import kotlin.math.pow

/** How near a corner of a node the pointer takes it, in pixels of the view. */
private const val GRIP_PIXELS = 8.0

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
     * Asked when a button is used on a node, with the button as a
     * key ([KeyMapper.LEFT_MOUSE], [KeyMapper.RIGHT_MOUSE]) and what
     * makes that node the selection: true when the button is mapped
     * to something, which was then done ([KeyMapper.mouseKey]).
     * Nobody asked: the buttons do what they do out of the box.
     *
     * The right button is asked about when it is pressed, and does
     * nothing else if it is mapped. The left one when it was clicked
     * (pressed and released in one place), after what it does out of
     * the box: it selects and drags as ever.
     */
    var mouseKey: (key: String, select: () -> Unit) -> Boolean = { _, _ -> false }

    /** Whether the button that [key] is, used at [pos], stands for keys, which were then run. */
    private fun mapped(pos: Vector2, key: String): Boolean {
        val under = Clicker.selectClickedNode(view, pos)
        return under.isNotEmpty() && mouseKey(key) {
            view.endNodeEdit()
            view.endStylePicker()
            view.g.cleanSelect(under)
        }
    }

    /**
     * [button] was clicked on the node [id] by something that points
     * at nodes and not at places of the view (a headset's
     * controller): what the button is mapped to, as [mapped]; if
     * nothing, the left one selects the node and the right one does
     * nothing. A node that is not shown is not clicked.
     */
    fun clicked(id: String, button: Button) {
        val under = view.g.visibleNodes().filter { it.id == id }
        val key = if (button == Button.Left) KeyMapper.LEFT_MOUSE else KeyMapper.RIGHT_MOUSE
        if (under.isNotEmpty() && button != Button.Middle && !view.content.isBusy && gestures.state == null) {
            view.endNodeEdit()
            view.endStylePicker()
            if (!mouseKey(key) { view.g.cleanSelect(under) } && button == Button.Left) view.g.cleanSelect(under)
            view.repaint()
        }
    }

    /**
     * The node [id] was taken and put at [to] (in the graph, not as
     * the view sees it) by something that holds nodes in space (a
     * headset's controller). If it is one of several selected nodes,
     * they all go along. One undo step.
     */
    fun dragged(id: String, to: Point3) {
        val held = view.g.visibleNodes().firstOrNull { it.id == id }
        if (held != null && !view.content.isBusy && gestures.state == null) {
            view.endNodeEdit()
            view.endStylePicker()
            val all = view.g.selectedNodes.toList().takeIf { held in it } ?: listOf(held)
            val before = all.associateWith { it.position }
            val depthBefore = all.associateWith { graph_tools.Depth.of(it) }
            val by = to.xy - held.position
            val deeper = to.z - graph_tools.Depth.of(held)
            all.forEach {
                it.position = it.position + by
                graph_tools.Depth.set(it, depthBefore.getValue(it) + deeper)
                view.g.needsRecomputing(it)
            }
            if (held !in view.g.selectedNodes) view.g.cleanSelect(all)
            view.commitMoved(before, depthBefore)
            view.repaint()
        }
    }

    /**
     * A node that has a size of its own ([ManualSize]) being resized
     * by a corner: the corner opposite to the one taken is [fixed],
     * the one taken is from it towards [towards] (1 or -1 each way).
     * [before] is what the node's size and place were, for undo.
     */
    private class Resizing(val node: Node, val fixed: Vector2, val towards: Vector2, val before: Pair<String?, Vector2>)

    private var resizing: Resizing? = null

    /** The corner at [pos], of a node that is resized by its corners; not in 3D mode, where a place of the screen is not one of the graph. */
    private fun cornerAt(pos: Vector2): Resizing? {
        val reach = GRIP_PIXELS / view.viewTransform.scale
        val corners = listOf(Vector2(-1, -1), Vector2(1, -1), Vector2(-1, 1), Vector2(1, 1))
        val sized = if (view.viewTransform.threeD) emptyList() else view.g.visibleNodes().filter { ManualSize.of(it) != null }
        return sized.firstNotNullOfOrNull { n ->
            val half = n.cache.shapeBounds / 2
            corners.firstOrNull { (n.position + half * it - pos).length() <= reach }
                ?.let { Resizing(n, n.position - half * it, it, n.attributes.other[ManualSize.ATTRIBUTE] to n.position) }
        }
    }

    private fun resize(by: Resizing, pos: Vector2) {
        val asked = (pos - by.fixed) * by.towards
        val size = Vector2(asked.x.coerceAtLeast(ManualSize.SMALLEST), asked.y.coerceAtLeast(ManualSize.SMALLEST))
        by.node.attributes.other[ManualSize.ATTRIBUTE] = ManualSize.write(size)
        by.node.position = by.fixed + by.towards * size / 2
        view.g.needsRecomputing(by.node)
        view.repaint()
    }

    private fun endResize(by: Resizing) {
        val after = by.node.attributes.other[ManualSize.ATTRIBUTE] to by.node.position
        if (after != by.before) view.g.commitApplied(SetManualSizeCommand(view.g, mapOf(by.node to by.before), mapOf(by.node to after)))
        resizing = null
    }

    private fun press(screen: Vector2, button: Button, control: Boolean, shift: Boolean) {
        val asKey = button == Button.Right && !control && gestures.state == null
        val corner = if (button == Button.Left && !control && gestures.state == null) cornerAt(workspace(screen)) else null
        if (corner != null) {
            view.endNodeEdit()
            view.endStylePicker()
            resizing = corner
            view.repaint()
        } else if (asKey && mapped(workspace(screen), KeyMapper.RIGHT_MOUSE)) view.repaint()
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
        val by = resizing
        if (by != null) resize(by, pos)
        else when (gestures.state) {
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
        val by = resizing
        if (by != null) endResize(by)
        else if (button == Button.Left || (button == Button.Middle && middleGesture)) {
            if (gestures.state == GestureController.States.SelectionBox) gestures.dragOrEndSelectionBox(true)
            val pos = workspace(screen)
            val clicked = button == Button.Left && !control && (gestures.pressedAt - pos).lengthSquared() < Constants.cursorDeadZone * Constants.cursorDeadZone
            gestures.endSingleClick(pos, actual, control, view)
            if (clicked) mapped(pos, KeyMapper.LEFT_MOUSE)
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
