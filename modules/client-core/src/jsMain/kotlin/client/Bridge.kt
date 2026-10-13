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
            // not a view that already looks somewhere: the session put it where it was
            if (view.viewTransform.isIdentity) {
                if (view.content.currentFile != null) view.boundScreen() else view.viewTransform.translate(view.size / 2)
            }
        }
        if (placed) view.content.painted()
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
            // the list and a message share a place: closing the line must not take away what its command said
            override fun showList(text: String) {
                page.showMessage(text, true)
            }
            override fun hideList() {
                page.hideMessage(true)
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

    /**
     * Make the selected nodes what [type] names (`plain`, `markdown`, `code`, `code-kotlin`…): what their text is, not where
     * it is kept. A name that names nothing changes nothing.
     */
    fun pickType(type: String) {
        graph_tools.NodeType.parse(type)?.takeIf { it.source == null }?.let { Actions.typeSelection(view, it, keepSource = true) }
    }

    /** What the selected nodes are, as [pickType] takes it; empty when they are not all the same or there are none. */
    val pickedType: String get() = Actions.typeOfSelection(view).orEmpty()

    /** Give the selected nodes a size of their own, the one they have now ([manual]), or let their text decide again. */
    fun pickSizeMode(manual: Boolean) = Actions.sizeModeSelection(view, manual)

    /** `manual` or `auto`: which of them the selected nodes are; empty when not all the same or there are none. */
    val pickedSizeMode: String get() = Actions.sizeModeOfSelection(view)?.let { if (it) "manual" else "auto" }.orEmpty()

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
     * a node's x, y is its centre; colours are `#rrggbb` or null, as to be shown ([dark]); `palette` says they are from the picker's palette;
     * every node also has `type` (`plain`, `code` or `markdown`) and, for code that names one, `language`.
     */
    fun scene(): dynamic {
        place()
        val scene = view.scene(measurer)
        showEdited()
        // the links shown there follow the selection
        status()
        val o: dynamic = js("({})")
        o.scale = view.viewTransform.scale
        o.offsetX = view.viewTransform.offset.x
        o.offsetY = view.viewTransform.offset.y
        o.nodes = scene.nodes.map(::node).toTypedArray()
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
            j.scale = e.scale
            j.headScale = e.headScale
            j
        }.toTypedArray()
        return o
    }

    /** A node as [scene] and [space] tell of it. */
    private fun node(n: SceneNode): dynamic {
        val j: dynamic = js("({})")
        j.id = n.id
        j.x = n.position.x
        j.y = n.position.y
        val shape = if (n.id == view.editing?.id) edited(n.shapeBounds, n.scale) else n.shapeBounds
        val text = (n.textBounds - (n.shapeBounds - shape)).max(Vector2.Zero)
        j.width = shape.x
        j.height = shape.y
        j.textWidth = text.x
        j.textHeight = text.y
        j.lines = n.lines.toTypedArray()
        j.shape = n.shape
        val (bg, fg) = ColorStyle.PickerSet.shown(n.bg, n.fg, dark)
        j.bg = bg?.let(::css)
        j.fg = fg?.let(::css)
        j.palette = ColorStyle.PickerSet.isOurs(n.bg, n.fg)
        j.type = n.type.kind.id
        j.language = n.type.language
        j.fontSize = n.fontSize
        j.depthScale = n.scale
        j.selected = n.selected
        j.faint = n.faint
        j.manual = n.manual
        return j
    }

    /** Whether the view is to be drawn with depth ([space]) rather than flat ([scene]). */
    val threeD: Boolean get() = view.viewTransform.threeD

    /**
     * What to draw, not projected, for a renderer with perspective of its own:
     * `{nodes: [as in scene, x, y where the view sees the node, and z, how far away], edges: [{from, to}] (indices into nodes),
     * middleX, middleY, depth, distance, scale}`: the viewer is `distance` in front of the plane at `depth`, before its point
     * `middleX, middleY`, which is in the middle of the screen, and a unit of that plane is `scale` pixels. Nodes face the viewer.
     */
    fun space(): dynamic = spaceOf(view.spaceScene(measurer))

    /**
     * The same, but the nodes where they are in the graph, however this view is turned, zoomed or moved:
     * for a renderer that looks from where it likes, as a headset does.
     */
    fun world(): dynamic = spaceOf(view.spaceScene(measurer, asSeen = false))

    /** How the graph is on the walls now, and of what that was made: the walls, and whether the view was in explore mode. */
    private var onWalls: Pair<Any, OnWalls>? = null

    /**
     * [world] with the graph on the walls of a room (`:set walls`, [OnWalls]); null when that is not asked for or there are no
     * [walls] (of each the two ends as seen from above, x and z of each, metres). The nodes are where they are in the room,
     * in the graph's units and directions (x right, y down from the floor, z away from where the room's -z is), each with what
     * is towards the room from it (`tx`, `tz`); `nodeScale` is how many times its size a node is. The middle of the graph is
     * on the wall that who is at [standing] looks at (where they stand and which way they look, x and z of each).
     *
     * Where the graph is on the walls and how big is decided when it is first asked for, and again when explore mode is
     * entered or left: so what is unfolded, moved or added later does not shift all the rest along the wall.
     */
    fun worldOnWalls(walls: DoubleArray, standing: DoubleArray): dynamic {
        val scene = view.spaceScene(measurer, asSeen = false)
        val made = walls.toList() to Explore.isOn(view)
        val strip = if (!graph_tools.ViewModes.walls || scene.nodes.isEmpty()) null else
            WallStrip.of(walls.asList().chunked(4).map { (x1, z1, x2, z2) -> Wall(Vector2(x1, z1), Vector2(x2, z2)) }, Vector2(standing[0], standing[1]), Vector2(standing[2], standing[3]))
        if (strip == null) onWalls = null
        else if (onWalls?.first != made) {
            val from = Vector2(scene.nodes.minOf { it.position.x - it.shapeBounds.x / 2 }, scene.nodes.minOf { it.position.y - it.shapeBounds.y / 2 })
            val to = Vector2(scene.nodes.maxOf { it.position.x + it.shapeBounds.x / 2 }, scene.nodes.maxOf { it.position.y + it.shapeBounds.y / 2 })
            onWalls = made to OnWalls.of(strip, from, to, fitted = !Explore.isOn(view))
        }
        return onWalls?.second?.let { on ->
            val o = spaceOf(scene)
            scene.nodes.forEachIndexed { i, n ->
                val place = on.place(Point3(n.position, scene.depths[i]))
                val j = o.nodes[i]
                j.x = place.x / OnWalls.METRES_PER_UNIT
                j.y = -place.y / OnWalls.METRES_PER_UNIT
                j.z = -place.z / OnWalls.METRES_PER_UNIT
                j.tx = place.out.x
                j.tz = -place.out.y
            }
            o.nodeScale = on.scale
            o
        }
    }

    /** Points of the graph (x, y and z of each after each other) as this view sees them: where [space] would have nodes that are there. */
    fun seen(points: DoubleArray): DoubleArray =
        points.asList().chunked(3).flatMap { (x, y, z) -> view.viewTransform.toView(Point3(x, y, z)).let { listOf(it.x, it.y, it.z) } }.toDoubleArray()

    private fun spaceOf(scene: SpaceScene): dynamic {
        val o: dynamic = js("({})")
        o.nodes = scene.nodes.mapIndexed { i, n ->
            val j = node(n)
            j.z = scene.depths[i]
            j
        }.toTypedArray()
        o.edges = scene.edges.map { (from, to) ->
            val j: dynamic = js("({})")
            j.from = from
            j.to = to
            j
        }.toTypedArray()
        o.middleX = scene.middle.x
        o.middleY = scene.middle.y
        o.depth = scene.depth
        o.distance = scene.distance
        o.scale = scene.scale
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
    fun textEditStarted(id: String) {
        view.textEditStarted(id)
        showEdited()
    }

    /**
     * Move the view so that all of the node being edited, as it is
     * shown meanwhile, is in it, a margin around it. Also while it
     * is edited: it grows with its text, up and down from its middle.
     */
    private fun showEdited() {
        view.editing?.let { n -> view.apparent(n)?.let { n to it } }?.let { (n, how) ->
            val half = edited(n.cache.shapeBounds, how.scale) * (view.viewTransform.scale * how.scale / 2)
            val at = view.viewTransform.toScreen(how.position)
            val low = Vector2(EDIT_MARGIN, EDIT_MARGIN) + half
            val high = view.size - low
            val to = Vector2(at.x.coerceIn(low.x, maxOf(low.x, high.x)), at.y.coerceIn(low.y, maxOf(low.y, high.y)))
            view.viewTransform.translate((to - at) / view.viewTransform.scale)
        }
    }

    /** How big a node of [size] that is being edited is shown: no bigger than the view less a margin. Beyond that the editor scrolls. */
    private fun edited(size: Vector2, depthScale: Double): Vector2 =
        size.min(((view.size - Vector2(2 * EDIT_MARGIN, 2 * EDIT_MARGIN)) / (view.viewTransform.scale * depthScale)).max(Vector2(1.0, 1.0)))

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

    init {
        // the buttons of the mouse can be mapped as keys are
        view.pointer.mouseKey = { key, select ->
            keys.activeView = view
            keys.mouseKey(key, select)
        }
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

    /** A button clicked on the node [id], by what points at nodes (a headset's controller). */
    fun clickNode(id: String, domButton: Int) {
        button(domButton)?.let { view.pointer.clicked(id, it) }
    }

    /** The node [id] taken and put at x, y, z of the graph, by what holds nodes in space (a headset's controller). */
    fun dragNode(id: String, x: Double, y: Double, z: Double) {
        view.pointer.dragged(id, Point3(x, y, z))
    }

    /** The links of the nodes shown that have any: by the node's id, each link as its letter and, after a space, where it leads. */
    fun links(): dynamic {
        val o: dynamic = js("({})")
        view.g.visibleNodes().forEach { n ->
            val letters = FileLink.letters(n)
            if (letters.isNotEmpty()) o[n.id] = letters.map { "$it ${FileLink.of(n, it).orEmpty()}" }.toTypedArray()
        }
        return o
    }

    /** Follow the link [letter] of the node [id], into a new tab: nothing of this one is lost, and nothing is asked. */
    fun followLink(id: String, letter: String) {
        val node = view.g.visibleNodes().firstOrNull { it.id == id }
        val l = letter.singleOrNull()
        if (node != null && l != null && l in FileLink.letters(node) && !view.content.isBusy) {
            keys.activeView = view
            view.endNodeEdit()
            view.g.cleanSelect(listOf(node))
            view.content.followFileLinkInTab(l)
        }
    }

    /** Into explore mode, or out of it. */
    fun toggleExplore() {
        keys.activeView = view
        Explore.set(view, !Explore.isOn(view))
    }

    val exploring: Boolean get() = Explore.isOn(view)

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

        /** Pixels of the view left free around the node that is being edited. */
        const val EDIT_MARGIN = 40.0

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

