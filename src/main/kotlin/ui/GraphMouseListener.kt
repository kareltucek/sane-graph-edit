package ui

import utils.Constants
import graph_tools.LayoutOptimizer
import Node
import graph_tools.Plotter
import utils.Vector2
import ui.Utils.orElse
import ui.Utils.toScreenspaceVector
import ui.Utils.toWorkspaceVector
import java.awt.event.*
import java.awt.event.MouseEvent.*
import java.time.Instant


//Todo: refactor this!
class GraphMouseListener(
    val graphView: GraphView,
    var controller: GraphMouseController = GraphMouseController(graphView),
) : MouseListener, MouseMotionListener, MouseWheelListener {

    override fun mouseClicked(e: MouseEvent) {
    }

    override fun mousePressed(e: MouseEvent) {
        val pos = e.toWorkspaceVector()

        if (e.button == BUTTON1) {
            val mouseoverNodes = Clicker.selectClickedNode(graphView.g, pos)
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
            controller.pressedTime = Instant.now()
            controller.lastPosition = pos
        }

        graphView.endNodeEdit()
        graphView.endStylePicker()

        if (e.button == BUTTON3) {
            val mouseoverNodes = Clicker.selectClickedNode(graphView.g, pos)
            controller.startStylePicker(mouseoverNodes, e.toScreenspaceVector())
        }

        graphView.repaint()
    }

    override fun mouseDragged(e: MouseEvent) {
        //graph_tools.Plotter.screenDimensions = utils.Vector2(e.x, e.y)
        val pos = e.toWorkspaceVector()

        when {
            controller.state == GraphMouseController.States.MovingNodes -> {
                controller.dragMoveNode(pos, e.isShiftDown)
            }

            controller.state == GraphMouseController.States.PanningWorkspace -> {
                controller.dragPanView(pos)
            }

            controller.state == GraphMouseController.States.SelectionBox -> {
                controller.dragOrEndSelectionBox(false)
            }
        }

        val newPos = e.toWorkspaceVector()
        graphView.lastCursorPosition = newPos
        controller.lastPosition = newPos
    }



    override fun mouseMoved(e: MouseEvent) {
        graphView.lastCursorPosition = e.toWorkspaceVector()
    }

    override fun mouseReleased(e: MouseEvent) {
        val pos = e.toWorkspaceVector()

        //handle release
        if (e.button == BUTTON1) {
            //update selection
            if (controller.state == GraphMouseController.States.SelectionBox) {
                controller.dragOrEndSelectionBox(true)
            }

            controller.endSingleClick(pos, e, graphView)
        }

        graphView.repaint()
    }


    override fun mouseEntered(e: MouseEvent?) {}
    override fun mouseExited(e: MouseEvent?) {}


    override fun mouseWheelMoved(e: MouseWheelEvent) {
        graphView.endNodeEdit()
        graphView.endStylePicker()

        val coef = Math.pow(Constants.zoomOutStep, e.unitsToScroll.toDouble()).toDouble()
        val maxZoom = Constants.maxZoom

        if ((Plotter.t.scaleX > 1/maxZoom || coef > 1.0) && (Plotter.t.scaleX < maxZoom || coef < 1.0)) {
            val oldPos = e.toWorkspaceVector()
            Plotter.t.scale(coef.toDouble(), coef.toDouble())
            val newPos = e.toWorkspaceVector()
            val diff = newPos - oldPos

            Plotter.t.translate(diff.x.toDouble(), diff.y.toDouble())

            graphView.repaint()
        }
    }

    class GraphMouseController(
        val graphView: GraphView,
        var state: States? = null,
        var pressedAt: Vector2 = Vector2.Zero,
        var pressedTime: Instant? = Instant.now(),
        var lastPosition: Vector2 = Vector2.Zero,
        var singleClickedAt: Vector2? = null,
        var singleClickedTime: Instant? = Instant.now(),
        var selectionBoxFrom: Vector2? = null,
        var selectionBoxInitial: Set<Node>? = null,
    )  {
        enum class States { PanningWorkspace, MovingNodes, SelectionBox }


        fun startToggleMultiselectState(mouseoverNodes: MutableSet<Node>) {
            if (graphView.g.selectedNodes.contains(mouseoverNodes.first())) {
                graphView.g.unselect(mouseoverNodes.first())
            } else {
                graphView.g.select(mouseoverNodes.first())
            }
            state = States.MovingNodes
        }

        fun startBoxSelect(pos: Vector2) {
            selectionBoxFrom = pos
            selectionBoxInitial = graphView.g.selectedNodes.toMutableSet()
            assert(graphView.g.selectedNodes !== selectionBoxInitial)
            state = States.SelectionBox
        }

        fun startPanView() {
            state = States.PanningWorkspace
        }

        fun startMoveMultipleNodes(mouseoverNodes: MutableSet<Node>) {
            if (!graphView.g.selectedNodes.contains(mouseoverNodes.first())) {
                graphView.g.cleanSelect(mouseoverNodes)
            }
            state = States.MovingNodes
        }

        fun startMoveOrSelectSingleNode(mouseoverNodes: MutableSet<Node>) {
            graphView.g.cleanSelect(mouseoverNodes)
            state = States.MovingNodes
        }

        fun dragPanView(pos: Vector2) {
            val diff = pos - lastPosition
            Plotter.t.translate(diff.x.toDouble(), diff.y.toDouble())
            graphView.repaint()
        }

        fun dragMoveNode(pos: Vector2, restrictOperator: Boolean) {
            val diff = pos - lastPosition
            graphView.g.selectedNodes.forEach {
                it.position = it.position + diff
                graphView.g.needsRecomputing(it)
            }
            if (graphView.optimizeOnDrag || restrictOperator) {
                LayoutOptimizer.optimize(graphView.g, movingNodes = true, restrictOperator = restrictOperator == graphView.optimizeOnDrag)
            }
            graphView.repaint()
        }


        fun endSingleClick(pos: Vector2, e: MouseEvent, graphView: GraphView) {
            //handle doubleclick
            val thisClickMatch =
                (this.pressedAt - pos).lengthSquared() < Constants.cursorDeadZone * Constants.cursorDeadZone
            val firstTimeSatisfied =
                this.pressedTime?.let {
                    (Instant.now().toEpochMilli() - it.toEpochMilli()) < Constants.doubletapTimeout
                }.orElse(false)
            val secondClickMatch =
                this.singleClickedAt?.let { (it - pos).lengthSquared() < Constants.cursorDeadZone * Constants.cursorDeadZone }
                    .orElse(false)
            val secondTimeSatisfied =
                this.singleClickedTime?.let {
                    (Instant.now().toEpochMilli() - it.toEpochMilli()) < Constants.doubletapTimeout
                }.orElse(false)

            if (thisClickMatch && secondClickMatch && secondTimeSatisfied && !e.isControlDown) {
                val mouseoverNodes = Clicker.selectClickedNode(graphView.g, pos)
                val clickScreenCoordinates = Vector2(e.x, e.y)

                when (mouseoverNodes.size) {
                    0 -> {
                        spawnNewNode(pos)
                        editNode(graphView.g.selectedNodes.first(), clickScreenCoordinates)
                    }

                    1 -> {
                        editNode(mouseoverNodes.first(), clickScreenCoordinates)
                    }
                }

                singleClickedAt = null
                singleClickedTime = null
            } else if (thisClickMatch && firstTimeSatisfied && !e.isControlDown) {
                val mouseoverNodes = Clicker.selectClickedNode(graphView.g, pos)

                // reset select in case one node was clicked while multiple were selected - the user could have wanted to move the selected group
                graphView.g.cleanSelect(mouseoverNodes)

                singleClickedAt = pressedAt
                singleClickedTime = pressedTime
            }

            state = null
            selectionBoxFrom = null
        }

        fun dragOrEndSelectionBox(saveHistory: Boolean) {
            selectionBoxFrom?.let { box ->
                val (ul, br) = Vector2.computeCorners(lastPosition, box)
                graphView.g.nodes
                    .filter { ul.lt(it.position) && it.position.lt(br) }
                    .let {
                        selectionBoxInitial?.let { graphView.g.cleanSelect(it, false) }
                        graphView.g.selectAll(it, saveHistory)
                    }
                graphView.repaint()
            }
        }

        fun spawnNewNode(pos: Vector2) {
            val newNode = Node(
                label = Constants.defaultNodeText,
                pos = pos
            )

            graphView.g.add(newNode)
            graphView.g.cleanSelect(mutableSetOf(newNode))
            graphView.repaint()
        }

        fun editNode(n: Node, screenClickCoordinates: Vector2) {
            graphView.startNodeEdit(n, screenClickCoordinates)
        }

        fun startStylePicker(mouseoverNodes: MutableSet<Node>, screenspaceCoordinates: Vector2) {
            if (graphView.g.selectedNodes.size < 2 && mouseoverNodes.isNotEmpty()) {
                graphView.g.cleanSelect(mouseoverNodes)
            }
            graphView.startStylePicker(screenspaceCoordinates)
        }
    }
}