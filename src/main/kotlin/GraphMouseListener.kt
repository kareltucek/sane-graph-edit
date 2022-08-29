import GraphKeyListener.impl.editNode
import GraphMouseListener.impl.editNode
import GraphMouseListener.impl.spawnNewNode
import GraphMouseListener.impl.updateSelection
import Utils.orElse
import Utils.toWorkspaceVector
import Vector2.Companion.Zero
import java.awt.event.*
import java.awt.event.MouseEvent.BUTTON1
import java.time.Instant

class GraphMouseListener(
    val parent: GraphComponent,
    var state: States? = null,
    var pressedAt: Vector2 = Zero,
    var pressedTime: Instant? = Instant.now(),
    var lastPosition: Vector2 = Zero,
    var singleClickedAt: Vector2? = null,
    var singleClickedTime: Instant? = Instant.now(),
    var selectionBoxFrom: Vector2? = null,
    var selectionBoxInitial: Set<Node>? = null,
) : MouseListener, MouseMotionListener, MouseWheelListener {
    enum class States { PanningWorkspace, MovingNodes, SelectionBox }

    override fun mouseClicked(e: MouseEvent) {
    }

    override fun mousePressed(e: MouseEvent) {
        val pos = e.toWorkspaceVector()

        if (e.button == BUTTON1) {
            val selection = Clicker.selectClickedNode(Graph.g, pos)
            when {
                //move single node
                !e.isControlDown && selection.isNotEmpty() && Graph.g.selectedNodes.size <= 1 -> {
                    Graph.g.cleanSelect(selection)
                    state = States.MovingNodes
                }

                // move multiple nodes
                !e.isControlDown && selection.isNotEmpty() && Graph.g.selectedNodes.size > 1 -> {
                    if (!Graph.g.selectedNodes.contains(selection.first())) {
                        Graph.g.cleanSelect(selection)
                    }
                    state = States.MovingNodes
                }

                !e.isControlDown && selection.isEmpty() -> {
                    state = States.PanningWorkspace
                }

                e.isControlDown && selection.size == 0 -> {
                    selectionBoxFrom = pos
                    selectionBoxInitial = Graph.g.selectedNodes.toMutableSet()
                    assert(Graph.g.selectedNodes !== selectionBoxInitial)
                    state = States.SelectionBox
                }

                e.isControlDown && selection.size == 1 -> {
                    if (Graph.g.selectedNodes.contains(selection.first())) {
                        Graph.g.unselect(selection.first())
                    } else {
                        Graph.g.select(selection.first())
                    }
                    state = States.MovingNodes
                }

                e.isControlDown && selection.size > 1 -> {
                    Graph.g.selectAll(selection)
                    state = States.MovingNodes
                }
            }

            // update data
            pressedAt = pos
            pressedTime = Instant.now()
            lastPosition = pos
        }

        parent.endNodeEdit()
        parent.requestGlobalRepaint()
    }

    override fun mouseDragged(e: MouseEvent) {
        //Plotter.screenDimensions = Vector2(e.x, e.y)
        val pos = e.toWorkspaceVector()

        when {
            this.state == States.MovingNodes -> {
                val diff = pos - lastPosition
                Graph.g.selectedNodes.forEach {
                    it.position = it.position + diff
                    Graph.g.needsRecomputing(it)
                }
                parent.requestGlobalRepaint()
            }

            this.state == States.PanningWorkspace -> {
                val diff = pos - lastPosition
                Plotter.t.translate(diff.x.toDouble(), diff.y.toDouble())
                parent.requestGlobalRepaint()
            }

            //TODO: this may be a performance bottleneck
            this.state == States.SelectionBox -> {
                updateSelection(this, parent, false)
            }
        }

        val newPos = e.toWorkspaceVector()
        parent.lastCursorPosition = newPos
        lastPosition = newPos
    }

    override fun mouseMoved(e: MouseEvent) {
        parent.lastCursorPosition = e.toWorkspaceVector()
    }

    override fun mouseReleased(e: MouseEvent) {
        val pos = e.toWorkspaceVector()

        //handle release
        if (e.button == BUTTON1) {
            //update selection
            if (this.state == States.SelectionBox) {
                updateSelection(this, parent, true)
            }

            //handle doubleclick
            val thisClickMatch =
                (this.pressedAt - pos).distanceSquare() < Constants.cursorDeadZone * Constants.cursorDeadZone
            val firstTimeSatisfied =
                this.pressedTime?.let {
                    (Instant.now().toEpochMilli() - it.toEpochMilli()) < Constants.doubletapTimeout
                }.orElse(false)
            val secondClickMatch =
                this.singleClickedAt?.let { (it - pos).distanceSquare() < Constants.cursorDeadZone * Constants.cursorDeadZone }
                    .orElse(false)
            val secondTimeSatisfied =
                this.singleClickedTime?.let {
                    (Instant.now().toEpochMilli() - it.toEpochMilli()) < Constants.doubletapTimeout
                }.orElse(false)

            if (thisClickMatch && secondClickMatch && secondTimeSatisfied && !e.isControlDown) {
                val selection = Clicker.selectClickedNode(Graph.g, pos)

                when (selection.size) {
                    0 -> {
                        spawnNewNode(pos, parent)
                        editNode(Graph.g.selectedNodes.first(), parent, e)
                    }

                    1 -> {
                        editNode(selection.first(), parent, e)
                    }
                }

                singleClickedAt = null
                singleClickedTime = null
            } else if (thisClickMatch && firstTimeSatisfied && !e.isControlDown) {
                val selection = Clicker.selectClickedNode(Graph.g, pos)

                // reset select in case one node was clicked while multiple were selected - the user could have wanted to move the selected group
                Graph.g.cleanSelect(selection)

                singleClickedAt = pressedAt
                singleClickedTime = pressedTime
            }
        }

        state = null
        selectionBoxFrom = null
        parent.requestGlobalRepaint()
    }

    override fun mouseEntered(e: MouseEvent?) {}
    override fun mouseExited(e: MouseEvent?) {}


    override fun mouseWheelMoved(e: MouseWheelEvent) {
        parent.endNodeEdit()

        val coef = Math.pow(0.99, e.unitsToScroll.toDouble()).toFloat()

        if ((Plotter.t.scaleX > 0.1 || coef > 1.0) && (Plotter.t.scaleX < 10 || coef < 1.0)) {
            val oldPos = e.toWorkspaceVector()
            Plotter.t.scale(coef.toDouble(), coef.toDouble())
            val newPos = e.toWorkspaceVector()
            val diff = newPos - oldPos

            Plotter.t.translate(diff.x.toDouble(), diff.y.toDouble())

            parent.requestGlobalRepaint()
        }
    }

    object impl {
        fun updateSelection(me: GraphMouseListener, parent: GraphComponent, saveHistory: Boolean) {
            me.selectionBoxFrom?.let { box ->
                val (ul, br) = Vector2.computeCorners(me.lastPosition, box)
                Graph.g.nodes
                    .filter { ul.lt(it.position) && it.position.lt(br) }
                    .let {
                        me.selectionBoxInitial?.let { Graph.g.cleanSelect(it, false) }
                        Graph.g.selectAll(it, false)
                    }
                parent.requestGlobalRepaint()
            }
        }

        fun spawnNewNode(pos: Vector2, parent: GraphComponent) {
            val newNode = Node(
                text = Constants.defaultNodeText,
                position = pos
            )

            Graph.g.add(newNode)
            Graph.g.cleanSelect(mutableSetOf(newNode))
            parent.requestGlobalRepaint()
        }

        fun editNode(n: Node, parent: GraphComponent, evt: MouseEvent) {
            parent.startNodeEdit(n, evt)
        }
    }
}