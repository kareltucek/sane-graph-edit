package client

import Graph
import graph_tools.Depth
import graph_tools.EditTextCommand
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
class EditorView(
    initialGraph: Graph,
    private val renderer: Renderer,
    val shell: Shell = NoShell,
    client: EditorClient? = null,
    initialFile: Path? = null,
) : Renderer by renderer {
    /** Which file or directory the view shows, and the opening and saving of it. */
    val content: ViewContent = ViewContent(this, shell, client)

    /** The replica being edited; the selection lives on it. */
    var g: Graph = content.start(initialGraph, initialFile)

    /** The last search: query, results, position in them. */
    val searchState: SearchState = SearchState()

    val viewTransform: ViewTransform = ViewTransform()

    /** The view's size on screen, as last told by the renderer. */
    var size: Vector2 = Vector2.Zero

    /** Cursor position in workspace coordinates. */
    var lastCursorPosition: Vector2 = Vector2.Zero

    /** Cursor position on screen: stable against pan and zoom, which a scale gesture needs. */
    var lastScreenCursorPosition: Vector2 = Vector2.Zero

    /**
     * What an autosave backup of this view is named by while the
     * view has no file ([BackupPaths]); a view with a file is named
     * by the file. The same for as long as the view lives.
     */
    val autosaveId: String = List(32) { "0123456789abcdef".random() }.joinToString("")

    var optimizeOnDrag: Boolean = false
    var defaultNodeStyle: NodeStyle = NodeStyle()

    val gestures: GestureController = GestureController(this)

    /** Where a renderer reports what the mouse does. */
    val pointer: PointerInput = PointerInput(this)

    /** Told when a transform gesture (grab, rotate, scale) starts (true) and ends (false): keys then mean something else. */
    var onTransformMode: (Boolean) -> Unit = {}

    fun startNodeEdit(n: Node, screenClick: Vector2?) = startNodeEdit(n.id, screenClick)

    // --- text of a node being edited in place (renderer → client) ---

    /** The node whose text the user is editing, and the text it had before. */
    private var textEdit: Pair<Node, String>? = null

    /** The renderer opened its editor on the node. */
    fun textEditStarted(nodeId: String) {
        textEdit = g.node(nodeId)?.let { it to it.attributes.text }
    }

    /** The text in the renderer's editor is now [text]: show it in the node as the user types. */
    fun textTyped(text: String) {
        textEdit?.let { (n, _) ->
            n.attributes.text = text
            g.needsRecomputing(n)
        }
    }

    /** The renderer closed its editor with [text] in it: the edit becomes one undo step. */
    fun textEditEnded(text: String) {
        textEdit?.let { (n, before) ->
            // back to the text before the edit, so that the command's redo is what applies the change
            n.attributes.text = before
            if (before != text) g.commit(EditTextCommand(g, n, before, text))
            g.needsRecomputing(n)
        }
        textEdit = null
    }

    /**
     * How [n] appears in this view: where its middle seems to be in
     * the plane the view speaks of, and how many times its size it
     * seems to have. Null for a node that is not shown, being
     * behind the viewer or too near.
     */
    fun apparent(n: Node): Apparent? {
        val z = Depth.of(n)
        return if (z == viewTransform.depth) Apparent(n.position, 1.0)
        else viewTransform.perspective(z)?.let { p -> viewTransform.apparent(n.position, z, size / 2)?.let { Apparent(it, p) } }
    }

    /**
     * The depth to look at [nodes] at: the one most of them have,
     * and of several such the nearest to the viewer, so that none of
     * those is behind him. The view's own for no nodes.
     */
    private fun depthOf(nodes: Collection<Node>): Double =
        nodes.groupingBy { Depth.of(it) }.eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<Double, Int>> { it.value }.thenByDescending { it.key })?.key
            ?: viewTransform.depth

    /** Look at [pos] with zoom [scale]: [pos] goes to the middle of the view. With [depth], [pos] is in the plane at that depth. */
    fun setViewTo(pos: Vector2, scale: Double = 1.0, depth: Double? = null) {
        depth?.let { viewTransform.setDepth(it) }
        viewTransform.setToScale(scale)
        viewTransform.translate(viewTransform.toWorkspace(size / 2) - pos)
        repaint()
    }

    private fun focus(): Set<Node> = g.selectedNodes.takeIf { it.isNotEmpty() }.orElse(g.visibleNodes())

    /** Centre on the selection, or on everything visible, at zoom 1. */
    fun centerScreen() {
        setViewTo(GraphTools.computeCenterOfMass(focus()), 1.0, depthOf(focus()))
    }

    /** Fit the selection, or everything visible, into the view. */
    fun boundScreen() {
        GraphTools.computeBoundingBox(focus())?.let { box ->
            val scale = size / (box.br - box.ul)
            val targetScale = (minOf(scale.x, scale.y) / 1.1).coerceIn(0.0001, 1.0)
            setViewTo((box.ul + box.br) / 2, targetScale, depthOf(focus()))
        }
    }
}
