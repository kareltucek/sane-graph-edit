@file:OptIn(ExperimentalJsExport::class)

package client

import Graph
import graph_tools.TextMeasurer
import utils.Rgb
import utils.Vector2

/** `set <name>=<value>` of these is the page's to act on. */
private val PAGE_SETTINGS = listOf("node-editor")

/** `set <name>` / `set no<name>` of these, which are on unless turned off, too: the page is told `on` or `off`. */
private val PAGE_FLAGS = listOf("nvim-theme")

/** The key mapping of a JavaScript renderer: the editing and tab commands, and the user's init file like the Swing application's. */
private fun keyMapper(page: dynamic): KeyMapper {
    registerEditingCommands()
    registerTabCommands()
    FilePicker.runner = NodeShellRunner
    val mapper = KeyMapper(KeyMapper.defaultBindings(), JsScheduler, defaultMappings = KeyMapper.defaultKeyMappings())
    PAGE_SETTINGS.forEach { name -> mapper.applicationSettings[name] = { value -> page.setting(name, value) } }
    KeyMapper.APPLICATION_SETTINGS += PAGE_SETTINGS
    PAGE_FLAGS.forEach { name ->
        val flag = KeyMapper.ApplicationFlag(true) { on -> page.setting(name, if (on) "on" else "off") }
        mapper.applicationFlags[name] = flag
        // as it used to be written, and may be in an init file: `set nvim-theme=off`
        mapper.applicationSettings[name] = { value ->
            flag.on = value != "off"
            flag.set(flag.on)
        }
    }
    KeyMapper.APPLICATION_FLAGS += PAGE_FLAGS
    // the page's own: how much memory the pictures it keeps take
    CommandRegistry.register("cache") { it.shell.message(page.cacheStats() as String) }
    // the room a headset told the page of
    CommandRegistry.register("room") { it.shell.message(page.room() as String) }
    // a made-up room, to try without a headset what a room is used for
    CommandRegistry.register("room-mock") { it.shell.message(page.roomMock() as String) }
    mapper.commandExecutor = { cmd, view -> CommandRegistry.execute(cmd, view) }
    mapper.commandNames = { CommandRegistry.list() }
    mapper.markSetter = { view, letter, change -> Actions.setMark(view, letter, change) }
    mapper.markRecaller = { view, letter -> Actions.recallMark(view, letter) }
    mapper.linkFollower = { view, letter, quiet -> view.content.followFileLink(letter, quiet) }
    mapper.installDefaultTransformBindings()
    runCatching { mapper.loadInitFile(Fs.configDir.resolve("init")) }
    return mapper
}

private external fun require(module: String): dynamic
private external val process: dynamic

/** How a Node.js application runs the shell command of [FilePicker]: Node calls back when it has ended. */
internal object NodeShellRunner : FilePicker.ShellRunner {
    override fun run(command: String, dir: Path, environment: Map<String, String>, then: (FilePicker.Output) -> Unit) {
        val options: dynamic = js("({})")
        options.cwd = dir.toString()
        options.env = js("Object").assign(js("({})"), process.env)
        environment.forEach { (name, value) -> options.env[name] = value }
        val child: dynamic = require("child_process").exec(command, options) { error: dynamic, printed: String, complaints: String ->
            val lines = FilePicker.lines(printed)
            // what the command complained of goes where the application's own complaints go
            if (complaints.isNotBlank()) utils.Platform.warn(complaints.trimEnd())
            then(FilePicker.Output(lines, if (error != null && lines.isEmpty()) "picker failed (${error.message})" else null))
        }
        // no input for it
        child.stdin.end()
    }
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
                override fun startMaximized(nodeId: String) {
                    if (isCurrent()) page.enterMaximized(nodeId)
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
            // during a transform gesture the keys are its controls (axis locks, confirm, cancel)
            view.onTransformMode = { on -> keys.mode = if (on) KeyMapper.Mode.Transform else KeyMapper.Mode.Normal }
            made =JsView(view, measurer, keys, page).also { shown[view] = it }
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
            autosave.stop()
            page.exit()
        }
    }

    private val tabs: Tabs = Tabs(host, dialogs) { Workspace.session() }

    /** Snapshots of every tab with unsaved changes, as long as the window lives. */
    private val autosave = Autosave({ tabs.views }, JsScheduler)

    /** The tabs of the workspace's last session, and [path] in one more. */
    internal fun start(path: String?) {
        tabs.restore()
        if (path != null) tabs.open(Fs.path(path))
        autosave.start()
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

    private val global = GlobalState()

    /** The zoom a maximized node is shown at, the user's whatever the workspace; null before one was kept. */
    fun maximizedZoom(): Double? = global.maximizedZoom

    fun keepMaximizedZoom(zoom: Double) {
        global.maximizedZoom = zoom
    }
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
 * `cacheStats()` — as text of several lines: how much memory the pictures the page keeps take (`:cache`);
 * `room()` — as text, a line for each surface of the room a headset is in (`:room`); `roomMock()` — make a room up, and the same;
 * `editNode(id, atClick, x, y)` — let the user edit the node's text, with the caret at the view position x, y if `atClick`;
 * `endEdit()` — close that editor;
 * `enterMaximized(id)` — as `editNode(id, false, 0, 0)`, the node shown as big as the window lets it while it has the keys;
 * `setting(name, value)` — the user's init file sets something that is the page's own, e.g. `nvim-theme`;
 * `showLine(text)` — show the `:` line holding `text`, the caret at its end, and give it the keys; `hideLine()`;
 * `showMessage(text)` — show `text` (several lines) until the user dismisses it; `hideMessage()`;
 *   `showMessage(text, true)` shows the list of completions in the same place, and `hideMessage(true)` hides only that;
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
 * Join the session of the window already running in the directory
 * this one is started in, through its socket ([SessionSocket]), and
 * show [path] as [openApp] does. [done] gets the window's tabs, or
 * the reason there are none: nobody has a session there.
 */
@JsExport
fun attachApp(path: String?, grid: GridMetrics, page: dynamic, done: (app: JsApp?, error: String?) -> Unit) {
    UnixSocketConnection.connect(SessionSocket.pathFor(Workspace.dir).toString()) { connection ->
        connection.fold(
            onSuccess = { c -> done(startApp(c, path, grid, page), null) },
            onFailure = { done(null, "no window is running in ${Workspace.dir}") },
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
    (connection as? RemoteConnection)?.onClosed = {
        // what is open stays, as graphs of this window's own that can still be saved
        client.connectionLost()
        page.showMessage("The window that had this session is gone.\nWhat is open here is no longer shared; it can still be saved.")
    }
    return JsApp(client, measurer, page).also { it.start(path) }
}
