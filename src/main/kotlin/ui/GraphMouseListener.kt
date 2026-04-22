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
import utils.Utils.toScreenVector
import utils.Utils.toScreenspaceVector
import utils.Utils.toWorkspaceVector
import java.awt.Robot
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

        if (e.button == BUTTON1 && controller.state == null) {
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
        val screen = Vector2(e.x.toDouble(), e.y.toDouble())

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

            controller.state == GraphMouseController.States.Rotating -> {
                controller.dragRotate(pos)
            }

            controller.state == GraphMouseController.States.Scaling -> {
                controller.dragScale(screen)
            }
        }

        val newPos = e.toWorkspaceVector()
        graphView.lastCursorPosition = newPos
        graphView.lastScreenCursorPosition = screen
        controller.lastPosition = newPos
    }



    override fun mouseMoved(e: MouseEvent) {
        val pos = e.toWorkspaceVector()
        val screen = Vector2(e.x.toDouble(), e.y.toDouble())

        when {
            controller.state == GraphMouseController.States.MovingNodes -> {
                controller.dragMoveNode(pos, e.isShiftDown)
            }
            controller.state == GraphMouseController.States.Rotating -> {
                controller.dragRotate(pos)
            }
            controller.state == GraphMouseController.States.Scaling -> {
                controller.dragScale(screen)
            }
        }

        graphView.lastCursorPosition = pos
        graphView.lastScreenCursorPosition = screen
        controller.lastPosition = pos
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
        /**
         * Captured positions of nodes at the start of a move gesture.
         * Built in [startMoveOrSelectSingleNode] /
         * [startMoveMultipleNodes], consumed in [endSingleClick] to
         * construct a single [MoveNodesCommand] for the whole drag —
         * see `tasks/undo.md` for the rationale (per-drag transaction
         * rather than one undo step per mouse-move event).
         */
        var moveStartPositions: Map<Node, Vector2>? = null,
        /**
         * Captured positions of the selection at the start of a
         * [States.Rotating] gesture. Consumed on commit (to build
         * a MoveNodesCommand) or on cancel (to revert the live
         * positions). Same pattern as [moveStartPositions].
         */
        var rotateStartPositions: Map<Node, Vector2>? = null,
        /** Bounding-box centre at the moment rotation began. */
        var rotateCenter: Vector2? = null,
        /**
         * Angle from [rotateCenter] to the cursor at the moment
         * rotation began. All subsequent cursor positions are
         * interpreted as deltas against this reference angle.
         */
        var rotateStartAngle: Double = 0.0,
        /**
         * Captured positions of the selection at the start of a
         * [States.Scaling] gesture. Same role as the move / rotate
         * equivalents: used by [dragScale] to recompute positions
         * from the origin each frame (avoiding drift), by commit
         * to build a [MoveNodesCommand], and by cancel to revert.
         */
        var scaleStartPositions: Map<Node, Vector2>? = null,
        /** Bounding-box centre at the moment scaling began — the anchor. */
        var scaleAnchor: Vector2? = null,
        /**
         * Cursor position *in canvas-screen coordinates* at the
         * moment scaling began. The per-axis scale factor is the
         * ratio of (current cursor − screen centre) to
         * (start cursor − screen centre), so we need a stable
         * reference point that's insulated from pan/zoom.
         */
        var scaleStartCursorScreen: Vector2? = null,
        /**
         * Live axis-lock flags shared across the three modal
         * transforms. Blender semantics: the `x` key toggles
         * [lockY] (constrain to X), the `y` key toggles [lockX].
         * Scale forces the locked axis's factor to 1.0; grab
         * zeros the locked axis's delta; rotate ignores them
         * (no meaning in 2D).
         */
        var lockX: Boolean = false,
        var lockY: Boolean = false,
    )  {
        enum class States { PanningWorkspace, MovingNodes, SelectionBox, Rotating, Scaling }


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
            captureMoveStart()
        }

        /**
         * Snapshot the positions of every currently-selected node. Called
         * when a move gesture begins so that the matching
         * [MoveNodesCommand] built at release time has a stable "before"
         * state regardless of how many intermediate drag events fire.
         */
        private fun captureMoveStart() {
            moveStartPositions = graphView.g.selectedNodes.associateWith { it.position }
        }

        fun startOrEndMove() {
            when (state) {
                States.MovingNodes -> {
                    commitMoveIfAny()
                    state = null
                    exitTransformMode()
                }
                null -> {
                    state = States.MovingNodes
                    captureMoveStart()
                    enterTransformMode()
                }
                else -> {
                    // Another modal gesture (rotate / scale) is
                    // active. Ignore so we don't clobber its
                    // captured state.
                }
            }
        }

        /**
         * Toggle rotate mode. First call: capture selection
         * positions + bounding-box centre + cursor angle, enter
         * [States.Rotating]. Subsequent cursor movement rotates
         * the selection around the centre in step with the
         * cursor's angular motion (see [dragRotate]). Second
         * call: commit the final positions as one
         * [MoveNodesCommand] and exit the mode.
         *
         * Cancel with [cancelActiveModal] (bound to Escape) to
         * revert to the captured positions.
         */
        fun startOrEndRotate() {
            when (state) {
                States.Rotating -> {
                    commitRotateIfAny()
                    state = null
                    exitTransformMode()
                }
                null -> {
                    if (!captureRotateStart()) return
                    state = States.Rotating
                    enterTransformMode()
                }
                else -> {
                    // Another modal gesture is active; ignore.
                }
            }
        }

        private fun captureRotateStart(): Boolean {
            val sel = graphView.g.selectedNodes
            if (sel.isEmpty()) return false
            val box = GraphTools.computeBoundingBox(sel) ?: return false
            val center = Vector2(
                (box.ul.x + box.br.x) / 2,
                (box.ul.y + box.br.y) / 2,
            )
            rotateCenter = center
            rotateStartPositions = sel.associateWith { it.position }
            val cursor = graphView.lastCursorPosition
            rotateStartAngle = atan2(cursor.y - center.y, cursor.x - center.x)
            return true
        }

        private fun commitRotateIfAny() {
            val before = rotateStartPositions ?: return
            rotateStartPositions = null
            rotateCenter = null
            val after = before.keys.associateWith { it.position }.toMutableMap()
            if (before.any { (n, p) -> after[n] != p }) {
                graphView.g.history.commitWithoutRun(
                    MoveNodesCommand(graphView.g, before, after),
                )
            }
        }

        /**
         * Called from `mouseMoved` while in [States.Rotating].
         * Computes the angular delta from the initial cursor
         * angle and applies a rigid rotation to the captured
         * starting positions — so the selection always reflects
         * an exact rotation from the start state, not an
         * accumulation of incremental updates (which would drift
         * as the mouse moves).
         */
        fun dragRotate(pos: Vector2) {
            val center = rotateCenter ?: return
            val start = rotateStartPositions ?: return
            val currentAngle = atan2(pos.y - center.y, pos.x - center.x)
            val delta = currentAngle - rotateStartAngle
            val cos = cos(delta)
            val sin = sin(delta)
            for ((n, origPos) in start) {
                val dx = origPos.x - center.x
                val dy = origPos.y - center.y
                n.position = Vector2(
                    center.x + dx * cos - dy * sin,
                    center.y + dx * sin + dy * cos,
                )
                graphView.g.needsRecomputing(n)
            }
            graphView.repaint()
        }

        /**
         * Toggle scale mode. First call: capture selection
         * positions + bounding-box centre (the scale anchor) +
         * current cursor position in screen coords (the factor
         * reference). Second call: commit final positions as one
         * [MoveNodesCommand]. Cancel via [cancelActiveModal] to
         * revert. Unlike move / rotate, scale reads the cursor
         * in *screen* coordinates so the factor is stable
         * against pan/zoom — the user can treat the canvas as a
         * scale dial whose centre is `1.0`.
         */
        fun startOrEndScale() {
            when (state) {
                States.Scaling -> {
                    commitScaleIfAny()
                    state = null
                    exitTransformMode()
                }
                null -> {
                    if (!captureScaleStart()) return
                    state = States.Scaling
                    enterTransformMode()
                }
                else -> {
                    // Another modal gesture is active; ignore.
                }
            }
        }

        private fun captureScaleStart(): Boolean {
            val sel = graphView.g.selectedNodes
            if (sel.isEmpty()) return false
            val box = GraphTools.computeBoundingBox(sel) ?: return false
            scaleAnchor = Vector2(
                (box.ul.x + box.br.x) / 2,
                (box.ul.y + box.br.y) / 2,
            )
            scaleStartPositions = sel.associateWith { it.position }
            scaleStartCursorScreen = graphView.lastScreenCursorPosition
            return true
        }

        private fun commitScaleIfAny() {
            val before = scaleStartPositions ?: return
            scaleStartPositions = null
            scaleAnchor = null
            scaleStartCursorScreen = null
            val after = before.keys.associateWith { it.position }.toMutableMap()
            if (before.any { (n, p) -> after[n] != p }) {
                graphView.g.history.commitWithoutRun(
                    MoveNodesCommand(graphView.g, before, after),
                )
            }
        }

        /**
         * Recompute every node's position from its captured
         * starting position each frame. [screenPos] is the cursor
         * in canvas-local screen coordinates.
         *
         * Factor per axis is the ratio of cursor-distance-from-
         * screen-centre now vs. at gesture start. That ratio is
         * `1.0` at entry by construction, grows as the cursor
         * moves further from centre, and shrinks as it nears.
         * Degeneracy: if the cursor started within
         * [SCALE_CENTER_EPSILON] pixels of a centre line, that
         * axis is frozen at `1.0` until the cursor crosses out,
         * at which point we re-bootstrap its reference distance.
         */
        fun dragScale(screenPos: Vector2) {
            val anchor = scaleAnchor ?: return
            val start = scaleStartPositions ?: return
            val startCursor = scaleStartCursorScreen ?: return
            val cx = graphView.graphCanvas.width / 2.0
            val cy = graphView.graphCanvas.height / 2.0

            var sx = axisFactor(
                startDelta = startCursor.x - cx,
                currentDelta = screenPos.x - cx,
                rebootstrap = { scaleStartCursorScreen = Vector2(screenPos.x, startCursor.y) },
            )
            var sy = axisFactor(
                startDelta = startCursor.y - cy,
                currentDelta = screenPos.y - cy,
                rebootstrap = { scaleStartCursorScreen = Vector2(scaleStartCursorScreen!!.x, screenPos.y) },
            )
            if (lockX) sx = 1.0
            if (lockY) sy = 1.0

            for ((n, origPos) in start) {
                val dx = origPos.x - anchor.x
                val dy = origPos.y - anchor.y
                n.position = Vector2(anchor.x + dx * sx, anchor.y + dy * sy)
                graphView.g.needsRecomputing(n)
            }
            graphView.repaint()
        }

        /**
         * Per-axis factor with the start-on-centre-line fallback.
         * If the start reference is too close to zero, freeze the
         * axis at `1.0` and let [rebootstrap] re-anchor the start
         * position as soon as the cursor moves outside the band —
         * that way the gesture continues smoothly from `1.0`
         * rather than jumping to some arbitrary ratio.
         */
        private fun axisFactor(
            startDelta: Double,
            currentDelta: Double,
            rebootstrap: () -> Unit,
        ): Double {
            if (Math.abs(startDelta) < SCALE_CENTER_EPSILON) {
                if (Math.abs(currentDelta) >= SCALE_CENTER_EPSILON) {
                    rebootstrap()
                }
                return 1.0
            }
            return currentDelta / startDelta
        }

        /**
         * Called by the toggle methods to flip the KeyMapper into
         * [KeyMapper.Mode.Transform] so the `x`/`y` axis-lock
         * bindings fire (and unbound keys are swallowed). Safe to
         * call when there's no mapper — tests drive the controller
         * directly and don't set one up.
         */
        private fun enterTransformMode() {
            graphView.keyMapper?.mode = KeyMapper.Mode.Transform
        }

        private fun exitTransformMode() {
            lockX = false
            lockY = false
            graphView.keyMapper?.mode = KeyMapper.Mode.Normal
        }

        /**
         * Revert any in-flight modal mutation (grab or rotate)
         * and exit the mode. Returns true if something was
         * cancelled, false if there was nothing to cancel.
         *
         * Bound to `Escape` via [GraphKeyListener.impl.unselectAll]
         * so the user's first Escape aborts the drag/rotation,
         * and a second Escape clears the selection as usual.
         */
        fun cancelActiveModal(): Boolean {
            if (state == States.MovingNodes) {
                moveStartPositions?.forEach { (n, p) ->
                    n.position = p
                    graphView.g.needsRecomputing(n)
                }
                moveStartPositions = null
                state = null
                exitTransformMode()
                graphView.repaint()
                return true
            }
            if (state == States.Rotating) {
                rotateStartPositions?.forEach { (n, p) ->
                    n.position = p
                    graphView.g.needsRecomputing(n)
                }
                rotateStartPositions = null
                rotateCenter = null
                state = null
                exitTransformMode()
                graphView.repaint()
                return true
            }
            if (state == States.Scaling) {
                scaleStartPositions?.forEach { (n, p) ->
                    n.position = p
                    graphView.g.needsRecomputing(n)
                }
                scaleStartPositions = null
                scaleAnchor = null
                scaleStartCursorScreen = null
                state = null
                exitTransformMode()
                graphView.repaint()
                return true
            }
            return false
        }
        fun startMove() {
            state = States.MovingNodes
            captureMoveStart()
        }

        fun startMoveOrSelectSingleNode(mouseoverNodes: MutableSet<Node>) {
            graphView.g.cleanSelect(mouseoverNodes)
            state = States.MovingNodes
            captureMoveStart()
        }

        fun dragPanView(pos: Vector2) {
            val diff = pos - lastPosition

            Plotter.t.translate(diff.x.toDouble(), diff.y.toDouble())
            graphView.repaint()
        }

        fun dragMoveNode(pos: Vector2, restrictOperator: Boolean) {
            val raw = pos - lastPosition
            // In Transform mode the user may have axis-locked the
            // drag with `x` / `y`. Zero out the frozen component so
            // movement stays on the unlocked axis.
            val diff = Vector2(
                if (lockX) 0.0 else raw.x,
                if (lockY) 0.0 else raw.y,
            )
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
//                        editNode(graphView.g.selectedNodes.first(), clickScreenCoordinates)
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

            commitMoveIfAny()

            state = null
            selectionBoxFrom = null
        }

        /**
         * If a move gesture was in progress, build a [MoveNodesCommand]
         * from the captured start positions and the current (final)
         * positions and push it onto the graph's undo stack. No-ops if no
         * capture was taken or nothing actually moved.
         */
        fun commitMoveIfAny() {
            val before = moveStartPositions ?: return
            moveStartPositions = null
            val after = before.keys.associateWith { it.position }.toMutableMap()
            if (before.any { (n, p) -> after[n] != p }) {
                graphView.g.history.commitWithoutRun(MoveNodesCommand(graphView.g, before, after))
            }
        }

        fun dragOrEndSelectionBox(saveHistory: Boolean) {
            selectionBoxFrom?.let { box ->
                val (ul, br) = Vector2.computeCorners(lastPosition, box)
                graphView.g.nodes
                    .filter { it.isVisible }
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
                pos = pos,
                style = graphView.defaultNodeStyle,
            )

            graphView.g.commit(AddNodeCommand(graphView.g, newNode))
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

        companion object {
            /**
             * Half-width (in canvas pixels) of the "cursor sits
             * on the screen-centre line" dead-band used by
             * [dragScale]. Gestures that begin inside this band
             * on a given axis have that axis frozen at 1.0 until
             * the cursor crosses out — at which point the
             * reference distance is re-bootstrapped so the scale
             * continues smoothly from 1.0 rather than jumping.
             */
            const val SCALE_CENTER_EPSILON: Double = 4.0
        }
    }
}