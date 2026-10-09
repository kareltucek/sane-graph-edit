@file:OptIn(ExperimentalJsExport::class)

package client

import graph_tools.TextMeasurer
import graph_tools.TextMetrics
import utils.Rgb
import utils.Vector2

/*
 * conn1 for a renderer written in JavaScript or TypeScript: what
 * is exported here is all such a renderer sees of the client. Only
 * numbers, strings, plain objects and callbacks cross.
 */

/**
 * The text grid of a renderer that shows every node in one
 * monospaced font: the width of a character and the height and
 * ascent of a line, as fractions of the font size.
 */
@JsExport
class GridMetrics(val advance: Double, val lineHeight: Double, val ascent: Double)

private class GridMeasurer(private val grid: GridMetrics) : TextMeasurer {
    override fun measure(lines: List<String>, fontSize: Double, monospace: Boolean): TextMetrics {
        val columns = lines.maxOfOrNull { it.length } ?: 0
        val lineHeight = fontSize * grid.lineHeight
        return TextMetrics(Vector2(columns * fontSize * grid.advance, lines.size * lineHeight), ascent = fontSize * grid.ascent, lineHeight = lineHeight)
    }
}

/** One view of a document, for a renderer to draw and to feed the user's input to. */
@JsExport
class JsView internal constructor(private val view: EditorView, private val measurer: TextMeasurer, val file: String) {
    /**
     * What to draw, sizes brought up to date:
     * `{scale, offsetX, offsetY, nodes: [{id, x, y, width, height, textWidth, textHeight, lines, shape, bg, fg, fontSize, selected}],
     * edges: [{x1, y1, x2, y2}]}`. Node and edge coordinates are in workspace units, `screen = workspace * scale + offset`;
     * a node's x, y is its centre; colours are `#rrggbb` or null.
     */
    fun scene(): dynamic {
        val scene = view.scene(measurer)
        val o: dynamic = js("({})")
        o.scale = view.viewTransform.scale
        o.offsetX = view.viewTransform.offset.x
        o.offsetY = view.viewTransform.offset.y
        o.nodes = scene.nodes.map { n ->
            val j: dynamic = js("({})")
            j.id = n.id
            j.x = n.position.x
            j.y = n.position.y
            j.width = n.shapeBounds.x
            j.height = n.shapeBounds.y
            j.textWidth = n.textBounds.x
            j.textHeight = n.textBounds.y
            j.lines = n.lines.toTypedArray()
            j.shape = n.shape
            j.bg = n.bg?.let(::css)
            j.fg = n.fg?.let(::css)
            j.fontSize = n.fontSize
            j.selected = n.selected
            j
        }.toTypedArray()
        o.edges = scene.edges.map { e ->
            val j: dynamic = js("({})")
            j.x1 = e.src.x
            j.y1 = e.src.y
            j.x2 = e.dst.x
            j.y2 = e.dst.y
            j
        }.toTypedArray()
        return o
    }

    /** The view's size on the screen changed. */
    fun resize(width: Double, height: Double) {
        view.size = Vector2(width, height)
    }

    /** Move what is shown by a distance on the screen. */
    fun pan(dx: Double, dy: Double) {
        view.viewTransform.translate(Vector2(dx, dy) / view.viewTransform.scale)
        view.repaint()
    }

    /** Zoom by [factor], keeping what is under the screen point [x], [y] where it is. */
    fun zoomAt(x: Double, y: Double, factor: Double) {
        val t = view.viewTransform
        val screen = Vector2(x, y)
        val fixed = t.toWorkspace(screen)
        t.zoom(factor)
        t.translate((screen - t.toScreen(fixed)) / t.scale)
        view.repaint()
    }

    /** Show the whole graph. */
    fun fit() {
        view.boundScreen()
        view.repaint()
    }
}

private fun css(c: Rgb): String = "#" + ((c.red shl 16) or (c.green shl 8) or c.blue).toString(16).padStart(6, '0')

/**
 * Connect to the editor at [url] (see `--ws`) and open the file at
 * [path] in a view. [onRepaint] is called whenever the picture is
 * out of date; [done] gets the view, or the reason there is none.
 */
@JsExport
fun openView(url: String, path: String, grid: GridMetrics, onRepaint: () -> Unit, done: (view: JsView?, error: String?) -> Unit) {
    val measurer = GridMeasurer(grid)
    TextMeasurer.default = measurer
    WebSocketConnection.connect(url) { connection ->
        connection.fold(
            onSuccess = { c ->
                EditorClient(c).open(path) { opened ->
                    opened.fold(
                        onSuccess = { session ->
                            val renderer = object : Renderer {
                                override fun repaint() = onRepaint()
                                override fun refreshStatus() {}
                                override fun startNodeEdit(nodeId: String, screenClick: Vector2?) {}
                                override fun endNodeEdit() {}
                                override fun startStylePicker(screen: Vector2?) {}
                                override fun endStylePicker() {}
                            }
                            // the view edits the session's replica; opening through ViewContent would block
                            val view = EditorView(session.graph, renderer)
                            session.onChange = { onRepaint() }
                            done(JsView(view, measurer, path), null)
                        },
                        onFailure = { done(null, it.message ?: it.toString()) },
                    )
                }
            },
            onFailure = { done(null, it.message ?: it.toString()) },
        )
    }
}
