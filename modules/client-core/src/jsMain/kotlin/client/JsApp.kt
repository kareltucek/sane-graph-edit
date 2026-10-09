@file:OptIn(ExperimentalJsExport::class)

package client

import Graph
import graph_tools.TextMeasurer
import utils.Rgb
import utils.Vector2

/** `set <name>=<value>` of these is the page's to act on. */
private val PAGE_SETTINGS = listOf("nvim-theme")

/** The key mapping of a JavaScript renderer: the editing and tab commands, and the user's init file like the Swing application's. */
private fun keyMapper(page: dynamic): KeyMapper {
    registerEditingCommands()
    registerTabCommands()
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

internal fun css(c: Rgb): String = "#" + ((c.red shl 16) or (c.green shl 8) or c.blue).toString(16).padStart(6, '0')

/**
 * A window of a renderer written in JavaScript or TypeScript: its
 * tabs, of which the page shows one at a time. What tabs there are
 * and what opening and closing does is [Tabs]'; the page draws the
 * strip and the current view.
 */
@JsExport
class JsApp internal constructor(private val client: EditorClient, private val measurer: TextMeasurer, private val page: dynamic) {
    private val keys: KeyMapper = keyMapper(page).also { it.modeListener = { current()?.status() } }

    /** The page's side of every view in the tabs. */
    private val shown = mutableMapOf<EditorView, JsView>()

    private val dialogs = object : Dialogs {
        override fun message(text: String) {
            page.showMessage(text)
        }
        override fun alert(title: String, text: String, error: Boolean) {
            page.showMessage("$title\n\n$text")
        }
        override fun confirm(title: String, question: String): Boolean = page.confirm(title, question) as Boolean
        override fun chooseOpen(): Path? = chosen(page.choose("open", ""))
        override fun chooseSave(defaultName: String): Path? = chosen(page.choose("save", defaultName))
        override fun chooseExport(defaultName: String): Path? = chosen(page.choose("export", defaultName))
        private fun chosen(path: dynamic): Path? = (path as? String)?.let { Fs.path(it) }
    }

    private val host = object : TabsHost {
        override fun view(graph: Graph, path: Path?, shell: Shell): EditorView {
            // the view that has the status line and the picker exists only after its renderer
            var made: JsView? = null
            val isCurrent = { made != null && made === current() }
            val renderer = object : Renderer {
                override fun repaint() {
                    if (isCurrent()) page.repaint()
                }
                override fun refreshStatus() {
                    if (isCurrent()) made?.status()
                }
                override fun startNodeEdit(nodeId: String, screenClick: Vector2?) {
                    if (isCurrent()) page.editNode(nodeId, screenClick != null, screenClick?.x ?: 0.0, screenClick?.y ?: 0.0)
                }
                override fun endNodeEdit() {
                    if (isCurrent()) page.endEdit()
                }
                override fun startStylePicker(screen: Vector2?) {
                    if (isCurrent()) made?.pickerStarted(screen)
                }
                override fun endStylePicker() {
                    if (isCurrent()) made?.pickerEnded()
                }
            }
            val view = EditorView(graph, renderer, shell, client, path)
            made = JsView(view, measurer, keys, page).also { shown[view] = it }
            return view
        }

        override fun screen(): Vector2? = tabs.current?.size?.takeIf { it.x > 0 && it.y > 0 }

        override fun leaving(view: EditorView) {
            shown[view]?.left()
        }

        override fun tabsChanged() {
            shown.keys.retainAll(tabs.views.toSet())
            page.tabs()
            page.repaint()
        }

        override fun modesChanged() {
            page.repaint()
        }

        override fun exit() {
            page.exit()
        }
    }

    private val tabs: Tabs = Tabs(host, dialogs) { Workspace.session() }

    /** The tabs of the workspace's last session, and [path] in one more. */
    internal fun start(path: String?) {
        tabs.restore()
        if (path != null) tabs.open(Fs.path(path))
    }

    /** The view the page shows now. */
    fun current(): JsView? = tabs.current?.let { shown[it] }

    /** The strip of tabs: `[{title, current}]`, in order. */
    fun tabs(): dynamic = tabs.views.mapIndexed { index, view ->
        val j: dynamic = js("({})")
        j.title = view.content.title
        j.current = index == tabs.currentIndex
        j
    }.toTypedArray()

    /** The user picked the tab at [index] of [tabs]. */
    fun select(index: Int) = tabs.select(index)

    /** The user asked to close the tab at [index] of [tabs]. */
    fun close(index: Int) {
        tabs.close(index)
    }

    /** The user asked to close the window; true if it may go (nothing unsaved, or the user agreed to lose it). */
    fun quit(): Boolean = tabs.quitAll(force = false)
}

/**
 * Connect to the editor at [url] (see `--ws`) and show the file or
 * directory at [path] in a tab, after the tabs the workspace's
 * session remembers; with neither, the workspace directory.
 * [done] gets the window's tabs, or the reason there are none.
 *
 * [page] is an object with what the client asks of the page:
 * `repaint()` — the picture is out of date;
 * `tabs()` — the strip of tabs or the view to show changed ([JsApp.tabs], [JsApp.current]);
 * `editNode(id, atClick, x, y)` — let the user edit the node's text, with the caret at the view position x, y if `atClick`;
 * `endEdit()` — close that editor;
 * `setting(name, value)` — the user's init file sets something that is the page's own, e.g. `nvim-theme`;
 * `showLine(text)` — show the `:` line holding `text`, the caret at its end, and give it the keys; `hideLine()`;
 * `showMessage(text)` — show `text` (several lines) until the user dismisses it; `hideMessage()`;
 * `status(text)` — the status line reads `text`;
 * `showPicker(x, y)` — show the style picker around the view position x, y; `hidePicker()`;
 * `confirm(title, question)` — ask the user a yes/no question and wait: true for yes;
 * `choose(kind, name)` — let the user choose a file to `open`, or where to `save` or `export` with `name` suggested, and wait: its path, or null;
 * `exit()` — close the window; the user has agreed to what is lost.
 */
@JsExport
fun openApp(url: String, path: String?, grid: GridMetrics, page: dynamic, done: (app: JsApp?, error: String?) -> Unit) {
    WebSocketConnection.connect(url) { connection ->
        connection.fold(
            onSuccess = { c -> done(startApp(c, path, grid, page), null) },
            onFailure = { done(null, it.message ?: it.toString()) },
        )
    }
}

/**
 * A window on the editor behind [connection], showing [path] as
 * [openApp] does: for [openApp], and for a host that has the
 * editor in the page's own process.
 */
fun startApp(connection: EditorConnection, path: String?, grid: GridMetrics, page: dynamic): JsApp {
    val measurer = GridMeasurer(grid)
    TextMeasurer.default = measurer
    val client = EditorClient(connection)
    client.onFailure = { reason -> page.showMessage(reason) }
    return JsApp(client, measurer, page).also { it.start(path) }
}
