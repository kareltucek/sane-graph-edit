package client

import Graph
import graph_tools.GraphTools
import graph_tools.Node
import graph_tools.NodeStyle
import utils.Utils.orElse
import utils.Vector2

/**
 * What a client asks of its renderer (conn1, client → renderer).
 * One-way: nothing is returned, and nodes are named by id, so that
 * a renderer could as well live in another process.
 */
interface Renderer {
    /** The picture is out of date. */
    fun repaint()

    /** The status line is out of date. */
    fun refreshStatus()

    /** Let the user edit the node's text in place; [screenClick] is where to put the caret. */
    fun startNodeEdit(nodeId: String, screenClick: Vector2?)

    fun endNodeEdit()

    /** Show the style picker at [screen], or at the cursor. */
    fun startStylePicker(screen: Vector2? = null)

    fun endStylePicker()
}

/**
 * One view of a document as the client sees it: everything about
 * it that is per user and needs no GUI toolkit — where it looks,
 * the cursor, the gesture in progress — and the editing that is
 * done with them ([Actions], [GestureController]).
 */
class EditorView(initialGraph: Graph, private val renderer: Renderer) : Renderer by renderer {
    /** The replica being edited; the selection lives on it. */
    var g: Graph = initialGraph

    val viewTransform: ViewTransform = ViewTransform()

    /** The view's size on screen, as last told by the renderer. */
    var size: Vector2 = Vector2.Zero

    /** Cursor position in workspace coordinates. */
    var lastCursorPosition: Vector2 = Vector2.Zero

    /** Cursor position on screen: stable against pan and zoom, which a scale gesture needs. */
    var lastScreenCursorPosition: Vector2 = Vector2.Zero

    var optimizeOnDrag: Boolean = false
    var defaultNodeStyle: NodeStyle = NodeStyle()

    val gestures: GestureController = GestureController(this)

    /** Told when a transform gesture (grab, rotate, scale) starts (true) and ends (false): keys then mean something else. */
    var onTransformMode: (Boolean) -> Unit = {}

    fun startNodeEdit(n: Node, screenClick: Vector2?) = startNodeEdit(n.id, screenClick)

    /** Look at [pos] with zoom [scale]: [pos] goes to the middle of the view. */
    fun setViewTo(pos: Vector2, scale: Double = 1.0) {
        viewTransform.setToScale(scale)
        viewTransform.translate(viewTransform.toWorkspace(size / 2) - pos)
        repaint()
    }

    private fun focus(): Set<Node> = g.selectedNodes.takeIf { it.isNotEmpty() }.orElse(g.visibleNodes())

    /** Centre on the selection, or on everything visible, at zoom 1. */
    fun centerScreen() {
        setViewTo(GraphTools.computeCenterOfMass(focus()), 1.0)
    }

    /** Fit the selection, or everything visible, into the view. */
    fun boundScreen() {
        GraphTools.computeBoundingBox(focus())?.let { box ->
            val scale = size / (box.br - box.ul)
            val targetScale = (Math.min(scale.x, scale.y) / 1.1).coerceIn(0.0001, 1.0)
            setViewTo((box.ul + box.br) / 2, targetScale)
        }
    }
}
