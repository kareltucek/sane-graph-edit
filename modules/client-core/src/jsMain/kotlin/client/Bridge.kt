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
 * ascent of a line, and the space between the text and the edge of
 * a box beside the text and above and below it, all as fractions
 * of the font size.
 */
@JsExport
class GridMetrics(val advance: Double, val lineHeight: Double, val ascent: Double, val marginX: Double, val marginY: Double)

private class GridMeasurer(private val grid: GridMetrics) : TextMeasurer {
    override fun measure(lines: List<String>, fontSize: Double, monospace: Boolean): TextMetrics {
        val columns = lines.maxOfOrNull { it.length } ?: 0
        val lineHeight = fontSize * grid.lineHeight
        val margin = Vector2(grid.marginX, grid.marginY) * fontSize
        return TextMetrics(Vector2(columns * fontSize * grid.advance, lines.size * lineHeight), fontSize * grid.ascent, lineHeight, margin)
    }
}

/** One view of a document, for a renderer to draw and to feed the user's input to. */
@JsExport
class JsView internal constructor(private val view: EditorView, private val measurer: TextMeasurer, val file: String, page: dynamic) {
    private val keys: KeyMapper = keyMapper(page)

    /**
     * What to draw, sizes brought up to date:
     * `{scale, offsetX, offsetY, nodes: [{id, x, y, width, height, textWidth, textHeight, lines, shape, bg, fg, fontSize, selected}],
     * edges: [{x1, y1, x2, y2}], selectionBox: {x1, y1, x2, y2} or null}`. Node, edge and box coordinates are in workspace units, `screen = workspace * scale + offset`;
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
        o.selectionBox = scene.selectionBox?.let { (a, b) ->
            val j: dynamic = js("({})")
            j.x1 = a.x
            j.y1 = a.y
            j.x2 = b.x
            j.y2 = b.y
            j
        }
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

    /** Show the whole graph. */
    fun fit() {
        view.boundScreen()
        view.repaint()
    }

    // --- the renderer's text editor, opened when the client asks for it (`editNode`) ---

    /** The text of the node [id], lines separated by `\n`; null when there is no such node. */
    fun nodeText(id: String): String? = view.g.node(id)?.attributes?.text

    /** The renderer opened its editor on the node [id]. */
    fun textEditStarted(id: String) = view.textEditStarted(id)

    /** The editor now holds [text]: the node shows it. */
    fun textTyped(text: String) {
        view.textTyped(text)
        view.repaint()
    }

    /** The editor was closed with [text] in it: the node's text changes, as one undo step. */
    fun textEditEnded(text: String) {
        view.textEditEnded(text)
        view.repaint()
    }

    // --- input: positions in view pixels, buttons as the DOM numbers them (0 left, 1 middle, 2 right) ---

    private fun button(domButton: Int): PointerInput.Button? = when (domButton) {
        0 -> PointerInput.Button.Left
        1 -> PointerInput.Button.Middle
        2 -> PointerInput.Button.Right
        else -> null
    }

    fun pointerDown(x: Double, y: Double, domButton: Int, control: Boolean, shift: Boolean) {
        button(domButton)?.let { view.pointer.pressed(Vector2(x, y), it, control, shift) }
    }

    /** The pointer moved, with a button held ([dragging]) or without. */
    fun pointerMove(x: Double, y: Double, dragging: Boolean, shift: Boolean) {
        // a waiting `g` grabs right away
        if (!dragging) keys.mouseMoved()
        view.pointer.moved(Vector2(x, y), dragging, shift)
        view.repaint()
    }

    fun pointerUp(x: Double, y: Double, domButton: Int, control: Boolean) {
        button(domButton)?.let { view.pointer.released(Vector2(x, y), Vector2(x, y), it, control) }
    }

    /** The wheel turned; [deltaY] as a DOM wheel event has it in pixels. */
    fun wheel(x: Double, y: Double, deltaY: Double) {
        view.pointer.wheel(Vector2(x, y), deltaY / WHEEL_PIXELS_PER_STEP)
    }

    /**
     * A key went down; [key] is a DOM `KeyboardEvent.key`. True if
     * it was passed to the key mapping, false for keys that mean
     * nothing to it (a modifier alone, keys without a name here).
     */
    fun keyDown(key: String, control: Boolean, shift: Boolean): Boolean {
        val special = specialKeys[key] ?: key.takeIf { functionKey.matches(it) }
        val character = key.singleOrNull()
        val notation = when {
            key in modifierKeys -> null
            special != null -> KeyNotation.fromKeyPressed(special, null, control, shift)
            control && character != null && character.isLetterOrDigit() -> KeyNotation.fromKeyPressed(null, character.lowercaseChar(), control, shift)
            character != null -> KeyNotation.fromKeyTyped(character)
            else -> null
        }
        if (key in modifierKeys) keys.modifierPressed()
        notation?.let {
            keys.activeView = view
            keys.feedKey(it)
            view.repaint()
        }
        return notation != null
    }

    private companion object {
        /** A wheel notch is about 100 pixels in a DOM event and 3 units for the gesture code. */
        const val WHEEL_PIXELS_PER_STEP = 100.0 / 3

        val modifierKeys = setOf("Shift", "Control", "Alt", "Meta", "AltGraph")
        val functionKey = Regex("F([1-9]|1[0-2])")

        /** DOM key names in the client's [KeyNotation]. */
        val specialKeys = mapOf(
            "Escape" to "Esc", "Enter" to "CR", " " to "Space", "Tab" to "Tab", "PageDown" to "PgDn", "PageUp" to "PgUp",
            "ArrowUp" to "Up", "ArrowDown" to "Down", "ArrowLeft" to "Left", "ArrowRight" to "Right", "Backspace" to "BS", "Delete" to "Del",
        )
    }
}

private val timers: dynamic = js("globalThis")

private object JsScheduler : Scheduler {
    override fun after(ms: Int, action: () -> Unit): () -> Unit {
        val id = timers.setTimeout(action, ms)
        return { timers.clearTimeout(id) }
    }
}

/** `set <name>=<value>` of these is the page's to act on. */
private val PAGE_SETTINGS = listOf("nvim-theme")

/** The key mapping of a JavaScript renderer: the editing commands, and the user's init file like the Swing application's. */
private fun keyMapper(page: dynamic): KeyMapper {
    registerEditingCommands()
    val mapper = KeyMapper(KeyMapper.defaultBindings(), JsScheduler)
    PAGE_SETTINGS.forEach { name -> mapper.applicationSettings[name] = { value -> page.setting(name, value) } }
    mapper.commandExecutor = { cmd, view -> CommandRegistry.execute(cmd, view) }
    mapper.commandNames = { CommandRegistry.list() }
    mapper.markSetter = { view, letter -> Actions.setMark(view, letter) }
    mapper.markRecaller = { view, letter -> Actions.recallMark(view, letter) }
    mapper.linkFollower = { view, letter, quiet -> view.content.followFileLink(letter, quiet) }
    mapper.installDefaultTransformBindings()
    runCatching { mapper.loadInitFile(Fs.configDir.resolve("init")) }
    return mapper
}

private fun css(c: Rgb): String = "#" + ((c.red shl 16) or (c.green shl 8) or c.blue).toString(16).padStart(6, '0')

/**
 * Connect to the editor at [url] (see `--ws`) and open the file at
 * [path] in a view. [done] gets the view, or the reason there is none.
 *
 * [page] is an object with what the client asks of the page:
 * `repaint()` — the picture is out of date;
 * `editNode(id, atClick, x, y)` — let the user edit the node's text, with the caret at the view position x, y if `atClick`;
 * `endEdit()` — close that editor;
 * `setting(name, value)` — the user's init file sets something that is the page's own, e.g. `nvim-theme`.
 */
@JsExport
fun openView(url: String, path: String, grid: GridMetrics, page: dynamic, done: (view: JsView?, error: String?) -> Unit) {
    val onRepaint: () -> Unit = { page.repaint() }
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
                                override fun startNodeEdit(nodeId: String, screenClick: Vector2?) {
                                    page.editNode(nodeId, screenClick != null, screenClick?.x ?: 0.0, screenClick?.y ?: 0.0)
                                }
                                override fun endNodeEdit() {
                                    page.endEdit()
                                }
                                override fun startStylePicker(screen: Vector2?) {}
                                override fun endStylePicker() {}
                            }
                            // the view edits the session's replica; opening through ViewContent would block
                            val view = EditorView(session.graph, renderer)
                            session.onChange = { onRepaint() }
                            done(JsView(view, measurer, path, page), null)
                        },
                        onFailure = { done(null, it.message ?: it.toString()) },
                    )
                }
            },
            onFailure = { done(null, it.message ?: it.toString()) },
        )
    }
}
