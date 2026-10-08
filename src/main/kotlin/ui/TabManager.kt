package ui

import client.*
import DotGraphLoader
import Graph
import graph_tools.Plotter
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.JTabbedPane
import javax.swing.event.ChangeListener

/**
 * Owns the [JTabbedPane] and the list of [GraphView]s behind it.
 *
 * Every mutable concern that was previously "window-global" is now
 * "current-tab-global": the current graph, the current file path,
 * the undo stack, the view transform. Switching tabs rewires
 * [Plotter.t] to the active view's [GraphView.viewTransform] so
 * existing rendering and input code that reads `Plotter.t` keeps
 * working without having to thread a transform parameter through.
 *
 * The manager also handles the "last tab" edge case: closing the
 * only remaining tab clears it to an empty untitled document rather
 * than removing it, so the window always has something to show and
 * the user never ends up staring at a blank frame.
 */
class TabManager(
    /**
     * Public so callers that need a reach through to the
     * containing window — the window's `JOptionPane` parenting
     * for prompts, the `refreshTitle` hook — have somewhere to
     * reach to. Nothing else in this class relies on the window.
     */
    val window: Window,
    /**
     * The session of the current [Workspace]; replaced by
     * [switchWorkspace]. Injectable so tests can swap in a
     * temp-file backed instance.
     */
    private var session: Session = Workspace.session(),
) {

    val tabbedPane: JTabbedPane = JTabbedPane()

    private val views: MutableList<GraphView> = mutableListOf()

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
        get() = tabbedPane.selectedIndex.takeIf { it in views.indices }?.let { views[it] }

    /** Same as [currentOrNull] but throws if there are no tabs. */
    val current: GraphView
        get() = views[tabbedPane.selectedIndex]

    init {
        // Tabs are populated by bootstrap() after construction so
        // the Window can control ordering (session restore before
        // Plotter.t assignment, autosave timer start after).
        tabbedPane.addChangeListener(ChangeListener { onChanged() })
    }

    /**
     * Restore the workspace's previous session (if any) or browse
     * the workspace directory, so the window always has something
     * to show. Called by [Window] after `this` is fully constructed,
     * and by [switchWorkspace].
     *
     * Missing or un-parseable session files are treated as "no
     * session" — we never want a corrupted state file to block
     * startup. Individual session entries that fail to load
     * (deleted since last run, parse error) are skipped with a
     * stderr warning.
     *
     * Note: only the list of *files* is persisted, not the
     * contents of those files — session restore just reopens
     * them from disk. That means any unsaved edits present at
     * the previous shutdown are not recovered this way; that's
     * the job of the autosave directory, which is never touched
     * automatically.
     */
    fun bootstrap() {
        val snap = session.load()
        for (path in snap.files) {
            if (!Files.exists(path)) {
                System.err.println("sane-graph-edit: session file $path no longer exists, skipping")
                continue
            }
            try {
                val g = DotGraphLoader.loadFromFile(path.toString())
                val gv = newTab(graph = g, path = path, persistSession = false)
                // Restore the saved pan/zoom if we had one for this
                // file. Keying by absolute path matches how Session
                // writes/reads them. GraphCanvas's first-paint
                // centering translate is gated on `isIdentity`, so
                // the restore survives that pass.
                snap.viewTransforms[path.toAbsolutePath()]?.let { m ->
                    if (m.size == 6) gv.viewTransform.matrix = m
                }
            } catch (t: Throwable) {
                System.err.println("sane-graph-edit: failed to restore $path: ${t.message}")
            }
        }
        if (views.isEmpty()) {
            browse(Workspace.dir, persistSession = false)
        }
        // Point the open/save dialogs at the last restored file's
        // directory — that's where the user was working, which beats
        // whatever directory a dialog happened to visit last.
        views.lastOrNull { it.currentFile != null }
            ?.currentFile?.toAbsolutePath()?.parent
            ?.let { dir ->
                AppState.lastOpenDir = dir
                AppState.lastSaveDir = dir
            }
        // Restore the previously active tab by matching its file
        // path; if it's no longer among the open tabs, leave the
        // JTabbedPane's default (index 0).
        snap.activeFile
            ?.let { wanted -> views.indexOfFirst { it.currentFile == wanted } }
            ?.takeIf { it >= 0 }
            ?.let { tabbedPane.selectedIndex = it }
    }

    /**
     * `:cd`: make [dir] the [Workspace]. The open tabs are kept in
     * the session of the workspace being left and replaced by those
     * of [dir]'s session. Asks about unsaved changes first; false if
     * the user cancelled.
     */
    fun switchWorkspace(dir: Path): Boolean {
        if (!confirmCloseAll()) return false
        saveSession()
        Workspace.dir = dir.toAbsolutePath().normalize()
        session = Workspace.session()
        views.forEach { it.closeDocument() }
        views.clear()
        tabbedPane.removeAll()
        AppState.lastOpenDir = Workspace.dir
        AppState.lastSaveDir = Workspace.dir
        bootstrap()
        return true
    }

    /**
     * Add a new tab holding [graph] (defaults to empty) associated
     * with [path] (nullable for an untitled document) and switch to
     * it. Returns the created view.
     *
     * [persistSession] can be set to false during session restore
     * to avoid re-writing session.properties once per restored tab.
     * All user-initiated paths leave it at the default `true`.
     */
    fun newTab(
        // Default to the help/keystroke reference graph rather than a
        // truly empty document. A fresh tab is also "first contact"
        // for any new user, and the help text being one `:tabnew` away
        // is more useful than a blank canvas.
        graph: Graph = Graph.defaultGraph(),
        path: Path? = null,
        persistSession: Boolean = true,
    ): GraphView {
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
        gv.onStateChange = { refreshTab(gv) }
        views.add(gv)
        tabbedPane.addTab(gv.title, gv)
        tabbedPane.selectedIndex = views.size - 1
        if (persistSession) saveSession()
        return gv
    }

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
    fun openPath(path: Path): GraphView? {
        if (Files.isDirectory(path)) return browse(path)
        return try {
            val loaded = DotGraphLoader.loadFromFile(path.toString())
            AppState.lastOpenDir = path.toAbsolutePath().parent
            newTab(graph = loaded, path = path)
        } catch (t: Throwable) {
            JOptionPane.showMessageDialog(
                window,
                "Could not open $path:\n${t.message}",
                "Open error",
                JOptionPane.ERROR_MESSAGE,
            )
            null
        }
    }

    /**
     * `:browse`: a new untitled tab with the [FileTree] of [dir],
     * a file picker driven by `gf`.
     */
    fun browse(dir: Path, persistSession: Boolean = true): GraphView {
        val gv = newTab(graph = FileTree.build(dir), persistSession = persistSession)
        gv.browseDir = dir.toAbsolutePath().normalize()
        gv.untitledName = "browse: ${gv.browseDir}"
        gv.onStateChange()
        return gv
    }

    /**
     * Close the tab at [idx] after prompting if it has unsaved
     * changes (unless [force]). Returns true if the tab was actually
     * closed (or cleared, on the last-tab path) and false if the
     * user cancelled.
     */
    fun closeTab(idx: Int, force: Boolean = false): Boolean {
        val gv = views.getOrNull(idx) ?: return false
        if (!force && gv.isDirty && !promptSaveDiscardCancel(gv)) return false

        if (views.size == 1) {
            // Never leave the window with zero tabs. Reset the
            // remaining slot to a fresh empty document instead.
            // Deliberately *not* deleting backup files here —
            // autosave snapshots accumulate as a manual safety
            // net; pruning is the user's job.
            gv.clearToEmpty()
            saveSession()
            return true
        }

        views.removeAt(idx)
        tabbedPane.remove(idx)
        gv.closeDocument()
        saveSession()
        return true
    }

    /**
     * Close the currently active tab. Convenience for the Ctrl+W
     * binding — pure delegation to [closeTab].
     */
    fun closeCurrent(): Boolean = closeTab(tabbedPane.selectedIndex)

    /**
     * vim's `:q` / `:q!`: close the current tab, or quit the
     * application when it is the last one (unlike Ctrl+W, which
     * clears the last tab). [force] skips the unsaved-changes prompt.
     */
    fun quitCurrent(force: Boolean) {
        if (views.size == 1) {
            window.quit(force)
        } else {
            closeTab(tabbedPane.selectedIndex, force)
        }
    }

    /** Switch to the next tab, wrapping around at the end. */
    fun selectNext() {
        if (views.size < 2) return
        tabbedPane.selectedIndex = (tabbedPane.selectedIndex + 1) % views.size
    }

    /** Switch to the previous tab, wrapping around at the start. */
    fun selectPrevious() {
        if (views.size < 2) return
        tabbedPane.selectedIndex =
            (tabbedPane.selectedIndex - 1 + views.size) % views.size
    }

    /**
     * Walk every tab, prompting Save / Discard / Cancel for each
     * dirty one. Returns true if every prompt was answered
     * non-cancel (or the user had nothing dirty to prompt about).
     * Used by the window's close handler.
     */
    fun confirmCloseAll(): Boolean {
        // Iterate over a snapshot because saveFile could in principle
        // mutate the list (it doesn't today, but future me might add
        // "save to a new tab" and this keeps that option open).
        for (gv in views.toList()) {
            if (gv.isDirty && !promptSaveDiscardCancel(gv)) return false
        }
        return true
    }

    /**
     * Invoked by each [GraphView] whenever its title-affecting state
     * changes (dirty flag, file path, graph swap). Updates both the
     * tab-bar label and, if this was the current tab, the window
     * title.
     */
    private fun refreshTab(gv: GraphView) {
        val idx = views.indexOf(gv)
        if (idx >= 0) {
            tabbedPane.setTitleAt(idx, gv.title)
        }
        if (gv === currentOrNull) {
            window.refreshTitle()
        }
    }

    /**
     * Handler for `JTabbedPane` selection changes. Points the global
     * [Plotter.t] at the freshly-active view's transform so
     * subsequent pans/zooms hit that tab's stored state, refreshes
     * the window title, and drops focus onto the new canvas so
     * keystrokes go to the right listener.
     */
    private fun onChanged() {
        val gv = currentOrNull ?: return
        window.refreshTitle()
        gv.refreshStatus()  // global modes may have changed while another tab was active
        gv.graphCanvas.requestFocusInWindow()
        gv.repaint()
    }

    /**
     * Flush the current tab set to the workspace's `session.properties`. Called
     * on tab open/close/save and on window close — anywhere the
     * persisted "what was open last time" answer could change.
     *
     * Untitled tabs are intentionally omitted: there's no stable
     * identifier to restore them from. If you care about not
     * losing unsaved work across restarts, the autosave
     * directory in `$XDG_CACHE_HOME/sane-graph-edit/backups` is
     * the place to look.
     */
    fun saveSession() {
        val files = views.mapNotNull { it.currentFile }
        val active = currentOrNull?.currentFile
        // Snapshot each file-backed view's transform as six doubles.
        // Untitled tabs are omitted for the same reason they're
        // omitted from `files`: no stable key to restore them by.
        val transforms = mutableMapOf<java.nio.file.Path, List<Double>>()
        for (gv in views) {
            val path = gv.currentFile ?: continue
            transforms[path.toAbsolutePath()] = gv.viewTransform.matrix
        }
        session.save(Session.Snapshot(files = files, activeFile = active, viewTransforms = transforms))
    }

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
            JOptionPane.YES_OPTION -> gv.saveFile()  // false if Save-As was cancelled
            JOptionPane.NO_OPTION -> true
            else -> false
        }
    }
}
