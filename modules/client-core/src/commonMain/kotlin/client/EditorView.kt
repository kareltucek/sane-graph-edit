package client

import Graph
import graph_tools.CompositeCommand
import graph_tools.Depth
import graph_tools.EditTextCommand
import graph_tools.MoveNodesCommand
import graph_tools.SetDepthCommand
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

    fun startNodeEdit(n: Node, screenClick: Vector2?) {
        // what is edited is what the file holds now
        FileContent.refresh(this, listOf(n))
        g.ensureCachesFresh()
        startNodeEdit(n.id, screenClick)
    }

    init {
        content.onceLoaded { FileContent.refresh(this) }
    }

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
            val source = FileContent.of(n, content.currentFile)
            when {
                // the node's text follows from the file's
                source != null -> if (FileContent.read(source) != text) FileContent.write(this, n, source, text, shell.views())
                before != text -> g.commit(EditTextCommand(g, n, before, text))
            }
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
        val at = viewPoint(n)
        return if (at.z == viewTransform.depth) Apparent(at.xy, 1.0)
        else viewTransform.perspective(at.z)?.let { p -> viewTransform.apparent(at.xy, at.z, size / 2)?.let { Apparent(it, p) } }
    }

    /** Where [n] is as this view sees the graph: across, down and away, whichever way the view is turned. */
    fun viewPoint(n: Node): Point3 = viewTransform.toView(Point3(n.position, Depth.of(n)))

    /** Put [n] where this view sees [at]. */
    fun place(n: Node, at: Point3) {
        val world = viewTransform.toWorld(at)
        n.position = world.xy
        Depth.set(n, world.z)
        g.needsRecomputing(n)
    }

    /**
     * The depth to look at [nodes] at: the one most of them have,
     * and of several such the nearest to the viewer, so that none of
     * those is behind him. The view's own for no nodes.
     */
    private fun depthOf(nodes: Collection<Node>): Double =
        when {
            nodes.isEmpty() -> viewTransform.depth
            // seen from the side hardly any two are at one depth: their middle
            viewTransform.isTurned -> nodes.sumOf { viewPoint(it).z } / nodes.size
            else -> nodes.groupingBy { Depth.of(it) }.eachCount().entries
                .maxWithOrNull(compareBy<Map.Entry<Double, Int>> { it.value }.thenByDescending { it.key })!!.key
        }

    /**
     * Nodes were moved by the user: from the positions [before] and
     * the depths [depthBefore] to where they are now. That becomes
     * one undo step, if anything changed.
     */
    fun commitMoved(before: Map<Node, Vector2>, depthBefore: Map<Node, Double>) {
        val after = before.keys.associateWith { it.position }.toMutableMap()
        val depthAfter = depthBefore.keys.associateWith { Depth.of(it) }
        val moved = before.any { (n, p) -> after[n] != p }
        val sunk = depthBefore != depthAfter
        when {
            moved && sunk -> g.commitApplied(CompositeCommand(listOf(MoveNodesCommand(g, before, after), SetDepthCommand(g, depthBefore, depthAfter))))
            moved -> g.commitApplied(MoveNodesCommand(g, before, after))
            sunk -> g.commitApplied(SetDepthCommand(g, depthBefore, depthAfter))
        }
    }

    /** The box around [nodes] as they appear in the plane the view speaks of, each as big as it seems; null if none of them is shown. */
    fun apparentBox(nodes: Collection<Node>): Pair<Vector2, Vector2>? =
        nodes.mapNotNull { n -> apparent(n)?.let { it.position to n.cache.shapeBounds * it.scale / 2 } }.takeIf { it.isNotEmpty() }?.let { seen ->
            Vector2(seen.minOf { it.first.x - it.second.x }, seen.minOf { it.first.y - it.second.y }) to
                Vector2(seen.maxOf { it.first.x + it.second.x }, seen.maxOf { it.first.y + it.second.y })
        }

    /** How far away the middle of what a turn of the view goes around is: of the selection, or of everything visible. Null when there is nothing. */
    fun orbitDepth(): Double? = focus().takeIf { it.isNotEmpty() }?.let { nodes -> nodes.sumOf { viewPoint(it).z } / nodes.size }

    /** The middle of [nodes] across the view: of the box around them in the graph, or, in a turned view, around where they are seen. Null for none. */
    fun middleOf(nodes: Collection<Node>): Vector2? = boxOf(nodes)?.let { (ul, br) -> (ul + br) / 2 }

    /** The box around [nodes] as the view sees them, or null for none; in a view from the front, the box around them in the graph. */
    private fun boxOf(nodes: Collection<Node>): Pair<Vector2, Vector2>? =
        if (!viewTransform.isTurned) GraphTools.computeBoundingBox(nodes)?.let { it.ul to it.br }
        else nodes.takeIf { it.isNotEmpty() }?.map { viewPoint(it).xy to it.cache.shapeBounds / 2 }?.let { seen ->
            Vector2(seen.minOf { it.first.x - it.second.x }, seen.minOf { it.first.y - it.second.y }) to
                Vector2(seen.maxOf { it.first.x + it.second.x }, seen.maxOf { it.first.y + it.second.y })
        }

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
        val nodes = focus()
        val middle = if (viewTransform.isTurned) nodes.fold(Vector2.Zero) { sum, n -> sum + viewPoint(n).xy } / nodes.size else GraphTools.computeCenterOfMass(nodes)
        setViewTo(middle, 1.0, depthOf(nodes))
    }

    /** Fit the selection, or everything visible, into the view. */
    fun boundScreen() {
        boxOf(focus())?.let { (ul, br) ->
            val scale = size / (br - ul)
            val targetScale = (minOf(scale.x, scale.y) / 1.1).coerceIn(0.0001, 1.0)
            setViewTo((ul + br) / 2, targetScale, depthOf(focus()))
        }
    }
}
