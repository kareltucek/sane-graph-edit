package ui

import client.*
import DotGraphLoader
import Graph
import utils.Vector2
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.JTabbedPane
import javax.swing.event.ChangeListener

/**
 * The window's tabs as Swing shows them: the [JTabbedPane] and the
 * [GraphView] of every tab. Which tabs there are, what opening,
 * closing and quitting do, and what is remembered for the next
 * start is [client.Tabs]', shared with the other renderers; this
 * class makes the views, keeps the pane equal to the tabs and puts
 * the questions to the user.
 */
class TabManager(
    /**
     * Public so callers that need a reach through to the
     * containing window — the window's `JOptionPane` parenting
     * for prompts, the `refreshTitle` hook — have somewhere to
     * reach to.
     */
    val window: Window,
    /** The session of the current [Workspace]; injectable so tests can swap in a temp-file backed one. */
    sessionOf: () -> Session = { Workspace.session() },
) {

    val tabbedPane: JTabbedPane = JTabbedPane()

    /** The Swing side of every view in the tabs. */
    private val shown = mutableMapOf<EditorView, GraphView>()

    /** The pane is being made equal to the tabs: its selection changes are not the user's. */
    private var syncing = false

    /** The view the pane showed last, to tell a change of tab from a change of title. */
    private var lastShown: GraphView? = null

    private val host = object : TabsHost {
        // The view is its own shell: it asks the user itself and passes what is about tabs on to this class.
        override fun view(graph: Graph, path: Path?, shell: Shell): EditorView {
            // read here as well: a file that cannot be read fails before there is a tab, and without an editor it is the content
            val content = path?.let { DotGraphLoader.loadFromFile(it.toString()) } ?: graph
            return makeView(content, path).also { shown[it.model] = it }.model
        }

        // none before the window is shown
        override fun screen(): Vector2? = Vector2(tabbedPane.width, tabbedPane.height).takeIf { it.x > 0 && it.y > 0 }

        override fun leaving(view: EditorView) {}

        override fun tabsChanged() = sync()

        override fun mayClose(view: EditorView): Boolean? = shown[view]?.let { promptSaveDiscardCancel(it) }

        override fun modesChanged() {
            views.forEach {
                it.refreshStatus()
                it.repaint()
            }
        }

        override fun exit() = window.exit()
    }

    /** The questions of the tabs themselves go to the user through the view that is shown. */
    private val dialogs = object : Dialogs {
        private val asker: Dialogs get() = currentOrNull ?: NoShell
        override fun message(text: String) = asker.message(text)
        override fun alert(title: String, text: String, error: Boolean) = asker.alert(title, text, error)
        override fun confirm(title: String, question: String): Boolean = asker.confirm(title, question)
        override fun chooseOpen(): Path? = asker.chooseOpen()
        override fun chooseSave(defaultName: String): Path? = asker.chooseSave(defaultName)
        override fun chooseExport(defaultName: String): Path? = asker.chooseExport(defaultName)
    }

    val tabs: Tabs = Tabs(host, dialogs, sessionOf)

    private val views: List<GraphView> get() = tabs.views.mapNotNull { shown[it] }

    /**
     * Iterate every open view. Exposed for [AutosaveManager]'s
     * periodic flush — other callers should prefer [current] or
     * the tabbed pane's APIs.
     */
    fun forEachView(action: (GraphView) -> Unit) {
        views.forEach(action)
    }

    /**
     * The currently active view, or `null` if there are no tabs
     * (only possible transiently during construction). Most callers
     * want [current], which throws — the `OrNull` variant exists
     * only for code paths that run during startup.
     */
    val currentOrNull: GraphView?
        get() = tabs.current?.let { shown[it] }

    /** Same as [currentOrNull] but throws if there are no tabs. */
    val current: GraphView
        get() = currentOrNull ?: throw NoSuchElementException("no tabs")

    init {
        // the user picked a tab in the pane
        tabbedPane.addChangeListener(ChangeListener { if (!syncing) tabs.select(tabbedPane.selectedIndex) })
    }

    /** A view with its command bar, message area and status bar, not yet in a tab. */
    private fun makeView(graph: Graph, path: Path?): GraphView {
        val gv = GraphView(initialGraph = graph, initialFile = path)
        gv.tabManager = this
        gv.keyMapper = window.keyMapper
        gv.commandBar = CommandBar(gv).also { bar ->
            gv.add(bar)
            gv.setLayer(bar, 2)  // above canvas (0) and editor/picker (1)
        }
        gv.messageArea = MessageArea(gv).also { msg ->
            gv.add(msg)
            gv.setLayer(msg, 2)
        }
        gv.statusBar = StatusBar(gv).also { bar ->
            gv.add(bar)
            gv.setLayer(bar, 2)
            bar.installConstraints()
        }
        gv.refreshStatus()
        // its title, file or unsaved state changed
        gv.onStateChange = { sync() }
        return gv
    }

    /** Make the pane show the tabs as they are now: the same views in the same order, their titles, the one selected. */
    private fun sync() {
        val wanted = views
        syncing = true
        try {
            for (i in tabbedPane.tabCount - 1 downTo 0) {
                if (tabbedPane.getComponentAt(i) !in wanted) tabbedPane.removeTabAt(i)
            }
            wanted.forEachIndexed { i, gv ->
                if (i >= tabbedPane.tabCount || tabbedPane.getComponentAt(i) !== gv) tabbedPane.insertTab(gv.title, null, gv, null, i)
                if (tabbedPane.getTitleAt(i) != gv.title) tabbedPane.setTitleAt(i, gv.title)
            }
            if (tabs.currentIndex in 0 until tabbedPane.tabCount) tabbedPane.selectedIndex = tabs.currentIndex
        } finally {
            syncing = false
        }
        shown.keys.retainAll(tabs.views.toSet())
        window.refreshTitle()
        val now = currentOrNull
        if (now != null && now !== lastShown) {
            lastShown = now
            // global modes may have changed while another tab was active
            now.refreshStatus()
            // keystrokes go to the tab that is shown
            now.graphCanvas.requestFocusInWindow()
            now.repaint()
        }
    }

    /**
     * Restore the workspace's previous session (if any) or browse
     * the workspace directory, so the window always has something
     * to show. Called by [Window] after `this` is fully constructed.
     */
    fun bootstrap() = tabs.restore()

    /**
     * `:cd`: make [dir] the [Workspace]. The open tabs are kept in
     * the session of the workspace being left and replaced by those
     * of [dir]'s session. Asks about unsaved changes first; false if
     * the user cancelled.
     */
    fun switchWorkspace(dir: Path): Boolean = tabs.switchWorkspace(dir)

    /** A new tab without a file, holding [graph] (the help graph by default), and switch to it. */
    fun newTab(graph: Graph = Graph.defaultGraph()): GraphView = shown.getValue(tabs.newTab(graph))

    /** The file or directory [path] in a new tab. Fails when the file can't be read. */
    fun edit(path: Path): GraphView = shown.getValue(tabs.open(path))

    /**
     * Prompt for a file and open it in a new tab. Cancelling the
     * dialog leaves the tab bar untouched. Parse errors are
     * reported via a dialog.
     */
    fun openInNewTab() {
        FileOps.openDialog(window)?.let { openPath(it) }
    }

    /**
     * Load [path] as a DOT graph into a new tab (Ctrl+T/N, gF, the open
     * dialog); a directory is browsed. Parse errors are reported via a
     * dialog.
     */
    fun openPath(path: Path): GraphView? =
        try {
            edit(path)
        } catch (t: Throwable) {
            JOptionPane.showMessageDialog(
                window,
                "Could not open $path:\n${t.message}",
                "Open error",
                JOptionPane.ERROR_MESSAGE,
            )
            null
        }

    /** Somebody outside (`ctl open`) asks to see [path]: in a new tab, or with [here] in the current one, like a followed link. */
    fun present(path: Path, here: Boolean) {
        val view = currentOrNull
        if (here && view != null) view.content.openTarget(FileLink.Target(path, null, null)) else openPath(path)
        window.toFront()
    }

    /**
     * `:browse`: a new untitled tab with the [FileTree] of [dir],
     * a file picker driven by `gf`.
     */
    fun browse(dir: Path): GraphView = shown.getValue(tabs.browse(dir))

    /**
     * Close the tab at [idx] after prompting if it has unsaved
     * changes (unless [force]). Returns true if the tab was actually
     * closed (or cleared, on the last-tab path) and false if the
     * user cancelled.
     */
    fun closeTab(idx: Int, force: Boolean = false): Boolean = tabs.close(idx, force)

    /**
     * Close the currently active tab. Convenience for the Ctrl+W
     * binding — pure delegation to [closeTab].
     */
    fun closeCurrent(): Boolean = tabs.close()

    /**
     * vim's `:q` / `:q!`: close the current tab, or quit the
     * application when it is the last one (unlike Ctrl+W, which
     * clears the last tab). [force] skips the unsaved-changes prompt.
     */
    fun quitCurrent(force: Boolean) = tabs.quitCurrent(force)

    /** Switch to the next tab, wrapping around at the end. */
    fun selectNext() = tabs.selectNext()

    /** Switch to the previous tab, wrapping around at the start. */
    fun selectPrevious() = tabs.selectPrevious()

    /** Flush the current tab set to the workspace's `session.properties`. */
    fun saveSession() = tabs.saveSession()

    /**
     * Three-choice modal prompt for one dirty tab. Returns true iff
     * the caller is clear to proceed with closing (either because
     * the user saved, or because they explicitly discarded). False
     * on cancel, or on a failed save (which JOptionPane handled via
     * its own error dialog).
     */
    private fun promptSaveDiscardCancel(gv: GraphView): Boolean {
        // `:set discard`: don't ask, discard
        val result = if (graph_tools.ViewModes.discard) JOptionPane.NO_OPTION else JOptionPane.showConfirmDialog(
            window,
            "Save changes to ${gv.currentFile?.fileName ?: "(untitled)"}?",
            "Unsaved changes",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        return when (result) {
            JOptionPane.YES_OPTION -> gv.content.saveNow()  // false if Save-As was cancelled
            JOptionPane.NO_OPTION -> true
            else -> false
        }
    }
}
