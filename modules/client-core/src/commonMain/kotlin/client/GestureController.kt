package client

import graph_tools.AddNodeCommand
import graph_tools.Depth
import graph_tools.ViewModes
import graph_tools.GraphTools
import graph_tools.LayoutOptimizer
import graph_tools.MoveNodesCommand
import graph_tools.Settling
import graph_tools.Node
import utils.Constants
import utils.Utils.orElse
import utils.Vector2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class GestureController(
    val graphView: EditorView,
    initialState: States? = null,
    var pressedAt: Vector2 = Vector2.Zero,
    var pressedTime: Long? = utils.Platform.nowMillis(),
    var lastPosition: Vector2 = Vector2.Zero,
    var singleClickedAt: Vector2? = null,
    var singleClickedTime: Long? = utils.Platform.nowMillis(),
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
     * Cursor position at the start of a move gesture (first
     * drag frame). Used by [dragMoveNode] to compute the
     * *total* delta from origin each frame — making the
     * function idempotent so that flipping an axis-lock
     * mid-drag reverts drift on the now-frozen axis instead
     * of leaving it stranded. Lazily set on the first drag
     * event because the mouse-press path updates
     * [lastPosition] after starting the gesture; defaulting
     * to the first drag position is equivalent to "start of
     * the drag" for both modal and mouse-driven flows.
     */
    var moveAnchor: Vector2? = null,
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
     * ratio of (current cursor − anchor-in-screen) to
     * (start cursor − anchor-in-screen), so we need a stable
     * reference point that's insulated from pan/zoom.
     */
    var scaleStartCursorScreen: Vector2? = null,
    /**
     * Anchor (= bbox centre) projected into canvas-screen
     * coordinates at gesture start. Drives the factor source
     * so that when the cursor reaches the bbox centre on
     * screen, the per-axis factor is `0` and the selection
     * collapses at the anchor. Captured once at entry so
     * pan/zoom mid-gesture doesn't shift the reference.
     */
    var scaleAnchorScreen: Vector2? = null,
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
    enum class States {
        PanningWorkspace, MovingNodes, SelectionBox, Rotating, Scaling, Orbiting;

        /**
         * True for the three modal "transform" gestures —
         * grab / rotate / scale. These are the ones that
         * flip [KeyMapper] into Transform mode and accept
         * `x` / `y` axis-lock bindings. Pan and
         * selection-box don't.
         */
        val isTransformGesture: Boolean
            get() = this == MovingNodes || this == Rotating || this == Scaling
    }

    /**
     * Current mouse/gesture state. Setting this to `null`
     * from a transform gesture ([States.isTransformGesture])
     * runs [onGestureEnded] — a single cleanup hook that
     * flips [KeyMapper] back to Normal, clears axis-locks
     * and leftover gesture captures, and refreshes the
     * status bar. Any code path that ends a gesture just
     * needs to null `state` out; it doesn't have to know
     * about Transform mode or what else needs resetting.
     */
    var state: States? = initialState
        set(value) {
            val prev = field
            // a new gesture: what the last one left on its way rests first, and its undo step is made
            if (prev == null && value != null) finishSettling()
            field = value
            if (prev != null && prev.isTransformGesture && value == null) {
                onGestureEnded()
            }
        }

    /**
     * Commit whatever transform gesture is currently active
     * as a single [MoveNodesCommand] on the undo stack, if
     * positions actually moved. No-op if no gesture is
     * active. Call this *before* setting `state = null` —
     * the commit needs to read the capture snapshots that
     * [onGestureEnded] will clear.
     *
     * This is the shared exit path for "finish the current
     * gesture": the modal toggles call it, and so does
     * [endSingleClick] so that a click-release during any
     * transform lands in the undo history (not just grab).
     */
    fun commitActiveTransform() {
        when (state) {
            States.MovingNodes -> commitMoveIfAny()
            States.Rotating -> commitRotateIfAny()
            States.Scaling -> commitScaleIfAny()
            else -> Unit
        }
    }

    /**
     * Called automatically by the [state] setter whenever a
     * transform gesture ends (commit, cancel, click-release,
     * or any other exit). Idempotent — calling it from a
     * non-gesture state is a no-op.
     */
    private fun onGestureEnded() {
        // Drop any leftover capture snapshots / anchors in
        // case an exit path forgot to clear them.
        moveStartPositions = null
        moveAnchor = null
        // what the nodes still on their way were is wanted when they rest
        if (settling == null) settleStart = null
        rotateStartPositions = null
        rotateCenter = null
        scaleStartPositions = null
        scaleAnchor = null
        scaleAnchorScreen = null
        scaleStartCursorScreen = null
        // Reset axis-locks and Transform mode; refresh the
        // status bar so the indicator clears.
        lockX = false
        lockY = false
        graphView.onTransformMode(false)
        graphView.refreshStatus()
    }


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
        moveStartPositions = captureStart(graphView.g.movable(graphView.g.selectedNodes))
        // the others too, for when Shift is held: what follows the dragged nodes follows them from where it was relative to them
        settleStart = graphView.g.visibleNodes().associateWith { it.point }
    }

    /**
     * The depths the nodes of the gesture in progress started at.
     * A gesture moves nodes across the view; seen from the side that
     * changes their depth as well as their position.
     */
    private var startDepths: Map<Node, Double>? = null

    /** Note where [nodes] are as a gesture begins: their positions are returned, their depths kept here. */
    private fun captureStart(nodes: Collection<Node>): Map<Node, Vector2> {
        startDepths = nodes.associateWith { Depth.of(it) }
        return nodes.associateWith { it.position }
    }

    /** Where [n] was when the gesture began, as the view sees it; [position] is what was captured of it then. */
    private fun startSeen(n: Node, position: Vector2): Point3 =
        graphView.viewTransform.toView(Point3(position, startDepths?.get(n) ?: Depth.of(n)))

    /** The gesture is over: what it did to the nodes of [before], captured at its start, becomes one undo step. */
    private fun commitMoved(before: Map<Node, Vector2>) =
        graphView.commitMoved(before, (startDepths ?: emptyMap()).filterKeys { it in before })

    private fun middleOf(nodes: Collection<Node>): Vector2? = graphView.middleOf(nodes)

    fun startOrEndMove() {
        when (state) {
            States.MovingNodes -> {
                commitActiveTransform()
                state = null   // setter runs onGestureEnded()
            }
            null -> {
                captureMoveStart()
                // Anchor the drag at the cursor's current
                // position so [dragMoveNode]'s from-start
                // recomputation has a stable origin. Only
                // the modal path sets this — mouse-driven
                // grabs leave it null and fall back to the
                // per-frame delta path in [dragMoveNode].
                moveAnchor = graphView.lastCursorPosition
                enterTransformMode()
                state = States.MovingNodes
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
                commitActiveTransform()
                state = null   // setter runs onGestureEnded()
            }
            null -> {
                if (!captureRotateStart()) return
                enterTransformMode()
                state = States.Rotating
            }
            else -> {
                // Another modal gesture is active; ignore.
            }
        }
    }

    private fun captureRotateStart(): Boolean {
        val sel = graphView.g.movable(graphView.g.selectedNodes)
        if (sel.isEmpty()) return false
        val center = middleOf(sel) ?: return false
        rotateCenter = center
        rotateStartPositions = captureStart(sel)
        val cursor = graphView.lastCursorPosition
        rotateStartAngle = atan2(cursor.y - center.y, cursor.x - center.x)
        return true
    }

    private fun commitRotateIfAny() {
        val before = rotateStartPositions ?: return
        rotateStartPositions = null
        rotateCenter = null
        commitMoved(before)
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
        val cz = middleDepth(start)
        // in 3D mode kept to an axis (`x`, `y`): around the screen's across or its up and down; otherwise around the axis
        // the view looks along (in a view from the front, in the graph's plane)
        val around = when {
            !graphView.viewTransform.threeD -> null
            lockY && !lockX -> "across"
            lockX && !lockY -> "upright"
            else -> null
        }
        for ((n, origPos) in start) {
            val was = startSeen(n, origPos)
            val dx = was.x - center.x
            val dy = was.y - center.y
            val dz = was.z - cz
            graphView.place(
                n,
                when (around) {
                    "across" -> Point3(was.x, center.y + dy * cos - dz * sin, cz + dy * sin + dz * cos)
                    "upright" -> Point3(center.x + dx * cos - dz * sin, was.y, cz + dx * sin + dz * cos)
                    else -> Point3(center.x + dx * cos - dy * sin, center.y + dx * sin + dy * cos, was.z)
                },
            )
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
                commitActiveTransform()
                state = null   // setter runs onGestureEnded()
            }
            null -> {
                if (!captureScaleStart()) return
                enterTransformMode()
                state = States.Scaling
            }
            else -> {
                // Another modal gesture is active; ignore.
            }
        }
    }

    private fun captureScaleStart(): Boolean {
        val sel = graphView.g.movable(graphView.g.selectedNodes)
        if (sel.isEmpty()) return false
        // Anchor = the selection's bounding-box centre (world
        // coords). Selection grows/shrinks in place around
        // this pivot. Factor source = cursor distance from
        // the anchor *projected to screen coords* — so
        // dragging the cursor *to* the bbox centre drives
        // the factor to 0 and collapses the selection at the
        // anchor.
        val anchor = middleOf(sel) ?: return false
        scaleAnchor = anchor
        scaleAnchorScreen = graphView.viewTransform.toScreen(anchor)
        scaleStartPositions = captureStart(sel)
        scaleStartCursorScreen = graphView.lastScreenCursorPosition
        return true
    }

    private fun commitScaleIfAny() {
        val before = scaleStartPositions ?: return
        scaleStartPositions = null
        scaleAnchor = null
        scaleAnchorScreen = null
        scaleStartCursorScreen = null
        commitMoved(before)
    }

    /**
     * Recompute every node's position from its captured
     * starting position each frame. [screenPos] is the cursor
     * in canvas-local screen coordinates.
     *
     * Factor is the ratio of cursor distance from the anchor
     * (in screen pixels, true Euclidean) now vs. at gesture
     * start — a single scalar applied to both axes. Axis locks
     * pin the locked axis at `1.0` so only the free axis
     * scales; the factor itself is still isotropic.
     * Degeneracy: if the cursor started within
     * [SCALE_CENTER_EPSILON] pixels of the anchor, factor is
     * frozen at `1.0` until the cursor leaves the band, at
     * which point we re-bootstrap the reference distance so
     * the gesture continues smoothly from `1.0`.
     */
    fun dragScale(screenPos: Vector2) {
        val anchor = scaleAnchor ?: return
        val anchorScreen = scaleAnchorScreen ?: return
        val start = scaleStartPositions ?: return
        val startCursor = scaleStartCursorScreen ?: return

        val startDist = (startCursor - anchorScreen).length()
        val currentDist = (screenPos - anchorScreen).length()
        val factor = if (startDist < SCALE_CENTER_EPSILON) {
            if (currentDist >= SCALE_CENTER_EPSILON) {
                scaleStartCursorScreen = screenPos
            }
            1.0
        } else {
            currentDist / startDist
        }
        val sx = if (lockX) 1.0 else factor
        val sy = if (lockY) 1.0 else factor
        // in 3D mode the depth each node is seen at as well, toward the selection's middle, unless an axis is kept to
        val sz = if (!graphView.viewTransform.threeD || lockX || lockY) 1.0 else factor
        val anchorZ = middleDepth(start)

        for ((n, origPos) in start) {
            val was = startSeen(n, origPos)
            graphView.place(n, Point3(anchor.x + (was.x - anchor.x) * sx, anchor.y + (was.y - anchor.y) * sy, anchorZ + (was.z - anchorZ) * sz))
        }
        graphView.repaint()
    }

    /** The depth the nodes of [start] are seen at, on average, as the gesture began. */
    private fun middleDepth(start: Map<Node, Vector2>): Double =
        start.entries.map { (n, p) -> startSeen(n, p).z }.average().takeIf { it.isFinite() } ?: 0.0

    /**
     * Called by the toggle methods to flip the KeyMapper into
     * [KeyMapper.Mode.Transform] so the `x`/`y` axis-lock
     * bindings fire (and unbound keys are swallowed). Safe to
     * call when there's no mapper — tests drive the controller
     * directly and don't set one up.
     */
    private fun enterTransformMode() {
        graphView.onTransformMode(true)
    }

    // exitTransformMode inlined into onGestureEnded so there's
    // a single exit hook — any path that sets `state = null`
    // out of a transform gesture triggers it via the setter.

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
        val captured = when (state) {
            States.MovingNodes -> moveStartPositions
            States.Rotating -> rotateStartPositions
            States.Scaling -> scaleStartPositions
            else -> return false
        }
        // Revert positions to what the gesture captured. The
        // state setter runs onGestureEnded() which clears the
        // capture fields, axis-locks, and Transform mode —
        // no extra bookkeeping here.
        captured?.forEach { (n, p) ->
            n.position = p
            startDepths?.get(n)?.let { Depth.set(n, it) }
            graphView.g.needsRecomputing(n)
        }
        // and what the springs moved meanwhile, which stops
        captured?.let { settledBefore(it) }?.forEach { (n, p) ->
            n.point = p
            graphView.g.needsRecomputing(n)
        }
        settling = null
        following = emptySet()
        state = null
        graphView.repaint()
        return true
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

    /** Turning the view starts, the pointer being at [screen]. Only in 3D mode: in 2D nothing turns a view. */
    fun startOrbit(screen: Vector2): Boolean {
        if (graphView.viewTransform.threeD) {
            state = States.Orbiting
            orbitedFrom = screen
            // around the node the pointer is on or nearest to ([EditorView.cursor]), which stays where it is on the screen
            val cursor = graphView.cursor(screen)
            cursor?.let { graphView.viewTransform.refocus(it.depth, graphView.size / 2) }
            orbitAround = cursor?.screen ?: (graphView.size / 2)
        }
        return graphView.viewTransform.threeD
    }

    private var orbitedFrom: Vector2 = Vector2.Zero

    /** Where on the screen the point is that the view turns around: it stays there. */
    private var orbitAround: Vector2 = Vector2.Zero

    /** The pointer went to [screen] while turning the view: the view turns around the 3D cursor, as far as the pointer went. */
    fun dragOrbit(screen: Vector2) {
        val by = (screen - orbitedFrom) * ORBIT_RADIANS_PER_PIXEL
        // the graph follows the pointer: dragged right, its near side goes right
        graphView.viewTransform.orbit(-by.x, by.y, orbitAround)
        orbitedFrom = screen
        graphView.repaint()
    }

    fun dragPanView(pos: Vector2) {
        graphView.viewTransform.translate(pos - lastPosition)
        graphView.repaint()
    }

    /** A node nearer the viewer than the plane seems bigger, and has less far to go to stay under the pointer. */
    private fun apparentScale(n: Node): Double = graphView.apparent(n)?.scale ?: 1.0

    fun dragMoveNode(pos: Vector2, settling: Boolean) {
        val start = moveStartPositions
        val anchor = moveAnchor
        if (start != null && anchor != null) {
            // Modal grab (toggled with `g`): recompute each
            // node's position from its start snapshot using
            // the total delta (pos − moveAnchor). Idempotent,
            // so toggling an axis-lock mid-gesture reverts
            // accumulated drift on the now-frozen axis
            // instead of stranding it there.
            val totalRaw = pos - anchor
            val total = Vector2(
                if (lockX) 0.0 else totalRaw.x,
                if (lockY) 0.0 else totalRaw.y,
            )
            for ((n, origPos) in start) {
                val was = startSeen(n, origPos)
                graphView.place(n, Point3(was.xy + total / apparentScale(n), was.z))
            }
        } else {
            // Mouse-driven drag (no anchor captured). Stay on
            // the per-frame delta path — axis-lock still
            // applies but prior drift isn't reverted because
            // we don't have a gesture origin. Click-drags
            // don't involve axis-lock toggling, so this is
            // fine.
            val raw = pos - lastPosition
            val diff = Vector2(
                if (lockX) 0.0 else raw.x,
                if (lockY) 0.0 else raw.y,
            )
            graphView.g.movable(graphView.g.selectedNodes).forEach {
                val now = graphView.viewPoint(it)
                graphView.place(it, Point3(now.xy + diff / apparentScale(it), now.z))
            }
        }
        if (settling) settleAround()
        graphView.repaint()
    }

    /**
     * How long a step of the springs may take, in milliseconds ([Settling.Run]): with more nodes than fit, a part of them
     * takes each step, moving for the others.
     */
    private val STEP_BUDGET = 50L

    /** Where the nodes seen were when the move in progress started ([captureMoveStart]); null outside one. */
    private var settleStart: Map<Node, Point3>? = null

    /** The nodes on their way, one step for each picture drawn ([frame]); null when none are. */
    private var settling: Settling.Run? = null

    /** The descendants that follow the dragged nodes in the move in progress. */
    private var following: Set<Node> = emptySet()

    /** A move that ended while nodes were still on their way: its undo step, made when they rest ([finishSettling]). */
    private var settledMove: Pair<Map<Node, Vector2>, Map<Node, Double>>? = null

    /**
     * Shift held while nodes are dragged: those dragged move exactly
     * with the pointer; by [Settling]'s springs, a step for each
     * picture drawn, the nodes on them make way (pulled back to where
     * they were, no harder than [Settling.ORIGIN_CAP]) and the
     * descendants that hang from them alone ([Settling.subtree])
     * follow them. Of two on each other the one that matters less
     * gives way more: the dragged ones not at all, then those that
     * follow, then the others, the ghosts most. Only nodes that are
     * seen take part.
     */
    private fun settleAround() {
        val g = graphView.g
        val start = settleStart.orEmpty()
        val dragged = g.movable(g.selectedNodes)
        val run = settling ?: g.visibleNodes().let { shown ->
            following = Settling.subtree(g, dragged, shown)
            val weight = { n: Node ->
                when {
                    n in dragged -> Settling.FIXED
                    n in following -> Settling.HEAVY
                    g.isFaint(n) -> Settling.LIGHT
                    else -> Settling.USUAL
                }
            }
            // how far the dragged nodes are from where they were, as they are now, in space
            val by = { dragged.firstNotNullOfOrNull { n -> start[n]?.let { n.point - it } } ?: Point3(0.0, 0.0, 0.0) }
            // nodes only kept off each other: those the user put near each other stay near, and only those on others are pushed
            // those that follow keep to where they were relative to the dragged nodes, however many generations down
            // in 3D mode in space: the springs may take nodes off the plane, and spread them across it
            Settling.Run(g, shown, weight, close = true, firm = following, budget = STEP_BUDGET, clock = clock, depth = graphView.viewTransform.threeD) { n ->
                start[n]?.let { if (n in following) it + by() else it }
            }.also { settling = it }
        }
        // on them: nearer than touching, not only within the collision spring's reach
        val touching = 1 / LayoutOptimizer.impl.collisionReach()
        val near = LayoutOptimizer.impl.Colliders(g.visibleNodes(), LayoutOptimizer.impl.collisionReach())
        val inTheWay = dragged.flatMap { d -> near.of(d).filter { it !in dragged && LayoutOptimizer.impl.computeCollisionSpring(g, it, d, touching) != null } }
        run.stir(following + inTheWay)
    }

    /** Besides the dragged nodes, the others [settleAround] moved: where they were, in space. */
    private fun settledBefore(dragged: Map<Node, Vector2>): Map<Node, Point3> =
        settleStart.orEmpty().filter { (n, p) -> n !in dragged && n.point != p }

    /** The undo step of a move: [before] and [depths] of the dragged nodes, and where the springs took the others from. */
    private fun commitWithSettled(before: Map<Node, Vector2>, depths: Map<Node, Double>) {
        val settled = settledBefore(before)
        graphView.commitMoved(before + settled.mapValues { it.value.xy }, depths + settled.mapValues { it.value.z })
    }

    /** `o` / `O` held: the nodes it lays out, a step for each picture ([frame]); null while neither is held. */
    private var laying: Settling.Run? = null

    /** Where all nodes were when `o` / `O` was pressed: the undo step of the hold is made from it when it is let go. */
    private var layingFrom: Map<Node, Point3>? = null

    /** The time the springs move by ([LayoutOptimizer.WINDOW_MS]); a test may give its own. */
    var clock: () -> Long = { utils.Platform.nowMillis() }

    /**
     * `o` / `O`: the settling springs on the nodes it lays out, every other node fixed, in space in 3D mode
     * ([docs/developer/springs.md]). `o`: the selected nodes, all seen ones when none is selected; `O` on one node: its
     * children that hang from it alone, the node itself staying ([LayoutOptimizer.SpringTarget.MoveChildsOnly]). The run
     * begins as if [past] milliseconds ago, so that its first step moves at once.
     */
    fun layout(restrict: Boolean, past: Double): Settling.Run {
        val g = graphView.g
        val shown = g.visibleNodes()
        val selected = g.movable(g.selectedNodes).filter { it in shown }.toSet()
        val moving = when {
            selected.isEmpty() -> shown
            restrict && selected.size == 1 ->
                LayoutOptimizer.impl.computeNodeSet(g, LayoutOptimizer.SpringTarget.MoveChildsOnly).filter { it in shown && it !in selected }.toSet()
            else -> selected
        }
        val weight = { n: Node -> if (n in moving) Settling.USUAL else Settling.FIXED }
        return Settling.Run(g, shown, weight, budget = STEP_BUDGET, clock = clock, depth = graphView.viewTransform.threeD).also {
            it.stir(moving, past)
        }
    }

    /**
     * `o` / `O` pressed: the nodes it lays out ([layout]) move for
     * [Actions.TAPPED_MS] at once, and then at each picture drawn for
     * the time since the last one, until a key is let go
     * ([stopOptimizing]). The keyboard's repeats of the key change nothing.
     */
    fun startOptimizing(restrict: Boolean) {
        if (laying == null) {
            finishSettling()
            layingFrom = graphView.g.nodes.associateWith { it.point }
            laying = layout(restrict, Actions.TAPPED_MS).also { it.step() }
            graphView.repaint()
        }
    }

    /** A key was let go: the nodes stop, and all they moved since `o` / `O` was pressed is one undo step. */
    fun stopOptimizing() {
        val before = layingFrom
        laying = null
        layingFrom = null
        if (before != null) commitPoints(before)
    }

    /** The nodes of [before] that moved since, as one undo step: their positions and their depths. */
    fun commitPoints(before: Map<Node, Point3>) {
        val moved = before.filter { (n, p) -> n.point != p }
        graphView.commitMoved(moved.mapValues { it.value.xy }, moved.mapValues { it.value.z })
    }

    /** Before a picture is drawn: the nodes on their way take a step. True while they have not come to rest. */
    fun frame(): Boolean {
        val laid = laying?.step() ?: false
        val more = settling?.step() ?: false
        if (!more && settledMove != null) finishSettling()
        return more || laid
    }

    /**
     * Something else is to happen: the nodes on their way stop where they are (letting them rest at once could take long
     * with many), and the undo step of the move they were part of is made, if it has ended.
     */
    fun finishSettling() {
        settledMove?.let { (before, depths) -> commitWithSettled(before, depths) }
        settledMove = null
        if (state != States.MovingNodes) {
            settling = null
            settleStart = null
            following = emptySet()
        }
    }


    /** A button was released at [pos] (workspace) / [screen], with or without [control] held. */
    fun endSingleClick(pos: Vector2, screen: Vector2, control: Boolean, graphView: EditorView) {
        //handle doubleclick
        val thisClickMatch =
            (this.pressedAt - pos).lengthSquared() < Constants.cursorDeadZone * Constants.cursorDeadZone
        val firstTimeSatisfied =
            this.pressedTime?.let {
                (utils.Platform.nowMillis() - it) < Constants.doubletapTimeout
            }.orElse(false)
        val secondClickMatch =
            this.singleClickedAt?.let { (it - pos).lengthSquared() < Constants.cursorDeadZone * Constants.cursorDeadZone }
                .orElse(false)
        val secondTimeSatisfied =
            this.singleClickedTime?.let {
                (utils.Platform.nowMillis() - it) < Constants.doubletapTimeout
            }.orElse(false)

        if (thisClickMatch && secondClickMatch && secondTimeSatisfied && !control) {
            val mouseoverNodes = Clicker.selectClickedNode(graphView, pos)
            val clickScreenCoordinates = screen

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
        } else if (thisClickMatch && firstTimeSatisfied && !control) {
            val mouseoverNodes = Clicker.selectClickedNode(graphView, pos)

            // reset select in case one node was clicked while multiple were selected - the user could have wanted to move the selected group
            graphView.g.cleanSelect(mouseoverNodes)

            singleClickedAt = pressedAt
            singleClickedTime = pressedTime
        }

        // Commit whatever gesture was active — grab, rotate,
        // or scale. Click-release is a "confirm" action for
        // all three, so the undo record and dirty flag land
        // the same way as a second `s`/`g`/`r` tap.
        commitActiveTransform()

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
        moveAnchor = null
        // nodes still on their way: the undo step waits for them to rest, and has what they moved too
        if (settling != null) settledMove = before to (startDepths ?: emptyMap()).filterKeys { it in before }
        else commitMoved(before)
    }

    fun dragOrEndSelectionBox(saveHistory: Boolean) {
        selectionBoxFrom?.let { box ->
            val (ul, br) = Vector2.computeCorners(lastPosition, box)
            graphView.g.nodes
                .filter { graphView.g.isVisible(it) }
                .filter { n -> graphView.apparent(n)?.position?.let { ul.lt(it) && it.lt(br) } ?: false }
                .let {
                    selectionBoxInitial?.let { graphView.g.cleanSelect(it, false) }
                    graphView.g.selectAll(it, saveHistory)
                }
            graphView.repaint()
        }
    }

    fun spawnNewNode(pos: Vector2) {
        // in the plane the view speaks of: where the pointer is
        val at = graphView.viewTransform.toWorld(Point3(pos, graphView.viewTransform.depth))
        val newNode = Node(
            label = Constants.defaultNodeText,
            pos = at.xy,
            style = graphView.defaultNodeStyle,
        )
        Depth.set(newNode, at.z)

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

        /** How far the view turns for each pixel the pointer goes while orbiting: half a turn across some 600 pixels. */
        const val ORBIT_RADIANS_PER_PIXEL: Double = 0.005
    }
}
