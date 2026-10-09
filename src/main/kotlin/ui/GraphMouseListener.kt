package ui

import utils.Constants
import graph_tools.AddNodeCommand
import graph_tools.GraphTools
import graph_tools.LayoutOptimizer
import graph_tools.MoveNodesCommand
import graph_tools.Node
import graph_tools.Plotter
import utils.Vector2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import utils.Utils.orElse
import client.Clicker
import client.GestureController
import client.ViewTransform
import utils.AwtUtils.toScreenspaceVector
import java.awt.Robot
import java.awt.event.*
import java.awt.event.MouseEvent.*


//Todo: refactor this!
class GraphMouseListener(
    val graphView: GraphView,
) : MouseListener, MouseMotionListener, MouseWheelListener {
    /** The view's gesture state machine; this listener only feeds it. */
    val controller: GestureController get() = graphView.model.gestures

    private val view: ViewTransform get() = graphView.viewTransform

    private fun MouseEvent.toWorkspaceVector(): Vector2 = view.toWorkspace(toScreenspaceVector())

    private fun Vector2.toWorkspaceVector(): Vector2 = view.toWorkspace(this)

    override fun mouseClicked(e: MouseEvent) {
    }

    override fun mousePressed(e: MouseEvent) {
        val pos = e.toWorkspaceVector()

        if (e.button == BUTTON1 && controller.state == null) {
            val mouseoverNodes = Clicker.selectClickedNode(graphView.g, pos, graphView.viewTransform.scale)
            when {
                //move single node
                !e.isControlDown && mouseoverNodes.isNotEmpty() && graphView.g.selectedNodes.size <= 1 -> {
                    controller.startMoveOrSelectSingleNode(mouseoverNodes)
                }

                // move multiple nodes
                !e.isControlDown && mouseoverNodes.isNotEmpty() && graphView.g.selectedNodes.size > 1 -> {
                    controller.startMoveMultipleNodes(mouseoverNodes)
                }

                // pan view
                !e.isControlDown && mouseoverNodes.isEmpty() -> {
                    controller.startPanView()
                }

                // click in empty space - start selection box, adding to current selection
                e.isControlDown && mouseoverNodes.size == 0 -> {
                    controller.startBoxSelect(pos)
                }

                // click on node - invert its selection state
                e.isControlDown && mouseoverNodes.size == 1 -> {
                    controller.startToggleMultiselectState(mouseoverNodes)
                }
            }

            // update data
            controller.pressedAt = pos
            controller.pressedTime = System.currentTimeMillis()
            controller.lastPosition = pos
        }

        graphView.endNodeEdit()
        graphView.endStylePicker()

        if (e.button == BUTTON3) {
            val mouseoverNodes = Clicker.selectClickedNode(graphView.g, pos, graphView.viewTransform.scale)
            controller.startStylePicker(mouseoverNodes, e.toScreenspaceVector())
        }

        graphView.repaint()
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
        val screen = gesturePosition(e) ?: return
        val pos = screen.toWorkspaceVector()

        when {
            controller.state == GestureController.States.MovingNodes -> {
                controller.dragMoveNode(pos, e.isShiftDown)
            }

            controller.state == GestureController.States.PanningWorkspace -> {
                controller.dragPanView(pos)
            }

            controller.state == GestureController.States.SelectionBox -> {
                controller.dragOrEndSelectionBox(false)
            }

            controller.state == GestureController.States.Rotating -> {
                controller.dragRotate(pos)
            }

            controller.state == GestureController.States.Scaling -> {
                controller.dragScale(screen)
            }
        }

        // Re-read: panning changed the transform, so the same screen point is a new workspace point.
        val newPos = screen.toWorkspaceVector()
        graphView.lastCursorPosition = newPos
        graphView.lastScreenCursorPosition = screen
        controller.lastPosition = newPos
        if (wrapsCursor) cursorWrap.wrapIfAtEdge(e, statusBarInset)
    }

    override fun mouseMoved(e: MouseEvent) {
        // a waiting `g` grabs right away
        graphView.keyMapper?.mouseMoved()
        val screen = gesturePosition(e) ?: return
        val pos = screen.toWorkspaceVector()

        when {
            controller.state == GestureController.States.MovingNodes -> {
                controller.dragMoveNode(pos, e.isShiftDown)
            }
            controller.state == GestureController.States.Rotating -> {
                controller.dragRotate(pos)
            }
            controller.state == GestureController.States.Scaling -> {
                controller.dragScale(screen)
            }
        }

        graphView.lastCursorPosition = pos
        graphView.lastScreenCursorPosition = screen
        controller.lastPosition = pos
        if (wrapsCursor) cursorWrap.wrapIfAtEdge(e, statusBarInset)
    }

    override fun mouseReleased(e: MouseEvent) {
        // virtual, so a drag that wrapped around isn't mistaken for a click
        val pos = (gesturePosition(e) ?: e.toScreenspaceVector()).toWorkspaceVector()

        //handle release
        if (e.button == BUTTON1) {
            //update selection
            if (controller.state == GestureController.States.SelectionBox) {
                controller.dragOrEndSelectionBox(true)
            }

            controller.endSingleClick(pos, Vector2(e.x, e.y), e.isControlDown, graphView.model)
        }

        graphView.repaint()
    }


    override fun mouseEntered(e: MouseEvent?) {}
    /** A fast modal move can leave the canvas between two move events; wrap from outside too. */
    override fun mouseExited(e: MouseEvent) {
        if (wrapsCursor && gesturePosition(e) != null) cursorWrap.wrapIfAtEdge(e, statusBarInset)
    }


    override fun mouseWheelMoved(e: MouseWheelEvent) {
        graphView.endNodeEdit()
        graphView.endStylePicker()

        val coef = Math.pow(Constants.zoomOutStep, e.unitsToScroll.toDouble()).toDouble()
        val maxZoom = Constants.maxZoom

        if ((view.scale > 1/maxZoom || coef > 1.0) && (view.scale < maxZoom || coef < 1.0)) {
            val oldPos = e.toWorkspaceVector()
            view.zoom(coef)
            val newPos = e.toWorkspaceVector()
            // keep the point under the cursor where it is
            view.translate(newPos - oldPos)

            graphView.repaint()
        }
    }

}