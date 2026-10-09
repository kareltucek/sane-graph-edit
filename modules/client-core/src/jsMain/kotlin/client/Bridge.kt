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

internal class GridMeasurer(private val grid: GridMetrics) : TextMeasurer {
    override fun measure(lines: List<String>, fontSize: Double, monospace: Boolean): TextMetrics {
        val columns = lines.maxOfOrNull { it.length } ?: 0
        val lineHeight = fontSize * grid.lineHeight
        val margin = Vector2(grid.marginX, grid.marginY) * fontSize
        return TextMetrics(Vector2(columns * fontSize * grid.advance, lines.size * lineHeight), fontSize * grid.ascent, lineHeight, margin)
    }
}

/** One view of a document, for a renderer to draw and to feed the user's input to. */
@JsExport
class JsView internal constructor(
    internal val view: EditorView,
    private val measurer: TextMeasurer,
    private val keys: KeyMapper,
    private val page: dynamic,
) {
    /** The tab's title: the file's name or what else is shown, with `*` in front while there are unsaved changes. */
    val title: String get() = view.content.title

    private var placed = false

    /**
     * Where the view looks when it is first drawn, once it has a
     * size and its content: all of a file's graph; anything else
     * (a file tree, the help) around the middle, as it was laid out.
     * A link that opened this view is followed to its node then.
     */
    private fun place() {
        if (!placed && view.size.x > 0 && !view.content.isBusy) {
            placed = true
            // the sizes of the nodes are known once the scene has been asked for
            view.scene(measurer)
            if (view.content.currentFile != null) view.boundScreen() else if (view.viewTransform.isIdentity) view.viewTransform.translate(view.size / 2)
            view.content.pendingFocus?.let {
                view.content.pendingFocus = null
                view.content.focusNode(it)
            }
        }
    }

    /** The view stops being the one the page shows: what it had open on the page goes. */
    internal fun left() {
        if (line.isOpen) line.cancel()
        page.endEdit()
        if (pickerOpen) view.endStylePicker()
    }

    private val line = CommandLine(
        view,
        object : CommandLineWidget {
            override fun show(text: String) {
                page.showLine(text)
            }
            override fun hide() {
                page.hideLine()
            }
            override fun showList(text: String) {
                page.showMessage(text)
            }
            override fun hideList() {
                page.hideMessage()
            }
        },
    ) { keys }

    /** Tell the page its status line. */
    internal fun status() {
        page.status(StatusLine.text(view, keys, pickerOpen))
    }

    // --- the style picker: the page has the buttons, shown and hidden through `showPicker` / `hidePicker` ---

    private var pickerOpen = false

    internal fun pickerStarted(screen: Vector2?) {
        val at = screen ?: view.lastScreenCursorPosition
        pickerOpen = true
        page.showPicker(at.x, at.y)
        status()
    }

    internal fun pickerEnded() {
        pickerOpen = false
        page.hidePicker()
        status()
    }

    /**
     * The page is in a dark theme: colours from the picker's palette
     * are shown as their dark counterparts. Files hold the light ones
     * whatever the theme.
     */
    var dark: Boolean = false

    /** The colour styles to offer as shown now ([dark]), row by row: `{columns, styles: [{bg, fg, label}]}`; a colour is `#rrggbb`, or null for the default. */
    fun pickerStyles(): dynamic {
        val o: dynamic = js("({})")
        o.columns = ColorStyle.PickerSet.saturationHue.size
        o.styles = (if (dark) ColorStyle.PickerSet.darkColorStyles else ColorStyle.PickerSet.colorStyles).map { s ->
            val j: dynamic = js("({})")
            j.bg = s.background?.let(::css)
            j.fg = s.foreground?.let(::css)
            j.label = s.label
            j
        }.toTypedArray()
        return o
    }

    /** The user picked the style at [index] of [pickerStyles]: the selected nodes get it and the picker closes. */
    fun pickColour(index: Int) {
        ColorStyle.PickerSet.colorStyles.getOrNull(index)?.let { Actions.colorSelection(view, it) }
        view.endStylePicker()
        view.repaint()
    }

    /** Make the selected nodes [steps] size steps bigger (smaller when negative). */
    fun pickSize(steps: Double) = Actions.resizeSelection(view, steps)

    /** Give the selected nodes the shape [shape]: `box` or `oval`. */
    fun pickShape(shape: String) {
        graph_tools.NodeShape.fromString(shape)?.let { Actions.shapeSelection(view, it) }
    }

    /** The user dismissed the picker (Escape). */
    fun pickerDismissed() = view.endStylePicker()

    // --- the `:` / `/` / `?` line: the page has the text field, opened and closed through `showLine` / `hideLine` ---

    /** The user edited the line; it now holds [text]. */
    fun lineTyped(text: String) = line.typed(text)

    /**
     * A key went down in the line; [key] is a DOM `KeyboardEvent.key`.
     * True if it means something there (Enter, Escape, the arrows up
     * and down, Tab, Ctrl+D) and is not the text field's to handle.
     */
    fun lineKey(key: String, control: Boolean, shift: Boolean): Boolean {
        val action: (() -> Unit)? = when {
            key == "Enter" -> line::execute
            key == "Escape" -> line::cancel
            key == "ArrowUp" -> line::historyUp
            key == "ArrowDown" -> line::historyDown
            key == "Tab" -> ({ line.complete(backward = shift) })
            key == "d" && control -> line::listCompletions
            else -> null
        }
        action?.invoke()
        return action != null
    }

    /**
     * What to draw, sizes brought up to date:
     * `{scale, offsetX, offsetY, nodes: [{id, x, y, width, height, textWidth, textHeight, lines, shape, bg, fg, palette, fontSize, selected}],
     * edges: [{x1, y1, x2, y2}], selectionBox: {x1, y1, x2, y2} or null}`. Node, edge and box coordinates are in workspace units, `screen = workspace * scale + offset`;
     * a node's x, y is its centre; colours are `#rrggbb` or null, as to be shown ([dark]); `palette` says they are from the picker's palette.
     */
    fun scene(): dynamic {
        place()
        val scene = view.scene(measurer)
        // the links shown there follow the selection
        status()
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
            val (bg, fg) = ColorStyle.PickerSet.shown(n.bg, n.fg, dark)
            j.bg = bg?.let(::css)
            j.fg = fg?.let(::css)
            j.palette = ColorStyle.PickerSet.isOurs(n.bg, n.fg)
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
        when {
            notation == null -> {}
            // these open the line instead of going through the key mapping
            special == null && !control && notation in lineOpeners -> line.open(notation)
            else -> {
                keys.activeView = view
                keys.feedKey(notation)
                view.repaint()
            }
        }
        return notation != null
    }

    private companion object {
        /** A wheel notch is about 100 pixels in a DOM event and 3 units for the gesture code. */
        const val WHEEL_PIXELS_PER_STEP = 100.0 / 3

        val modifierKeys = setOf("Shift", "Control", "Alt", "Meta", "AltGraph")
        val lineOpeners = setOf(":", "/", "?")
        val functionKey = Regex("F([1-9]|1[0-2])")

        /** DOM key names in the client's [KeyNotation]. */
        val specialKeys = mapOf(
            "Escape" to "Esc", "Enter" to "CR", " " to "Space", "Tab" to "Tab", "PageDown" to "PgDn", "PageUp" to "PgUp",
            "ArrowUp" to "Up", "ArrowDown" to "Down", "ArrowLeft" to "Left", "ArrowRight" to "Right", "Backspace" to "BS", "Delete" to "Del",
        )
    }
}

private val timers: dynamic = js("globalThis")

internal object JsScheduler : Scheduler {
    override fun after(ms: Int, action: () -> Unit): () -> Unit {
        val id = timers.setTimeout(action, ms)
        return { timers.clearTimeout(id) }
    }
}

