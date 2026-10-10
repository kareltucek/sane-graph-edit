package client

import Graph
import utils.Vector2

/** What [Tabs] needs of the application that shows them. */
interface TabsHost {
    /** A view for a new tab: it shows [graph], or the file [path] when there is one; [shell] is its shell. */
    fun view(graph: Graph, path: Path?, shell: Shell): EditorView

    /** The size a new tab will have, if known: a file tree is laid out to fit it. */
    fun screen(): Vector2?

    /** [view] is about to stop being the one shown (another is selected, or it is closed). */
    fun leaving(view: EditorView)

    /** Which tabs there are, their titles or the one shown changed. */
    fun tabsChanged()

    /**
     * Whether [view], which has unsaved changes, may be closed: for an
     * application with a question of its own (one that offers to
     * save). Null: the tabs ask whether to discard the changes.
     */
    fun mayClose(view: EditorView): Boolean? = null

    /** A view mode changed: every view shows it. */
    fun modesChanged()

    /** End the application; the user has agreed to what is lost. */
    fun exit()
}

/**
 * The tabs of a window: which views there are, which one is shown,
 * and what opening, closing and quitting do. An application shows
 * the strip and the current view ([TabsHost]) and asks the user
 * ([Dialogs]).
 */
class Tabs(
    private val host: TabsHost,
    private val dialogs: Dialogs,
    /** The session of the current [Workspace], where the open files are remembered between runs; null remembers nothing. */
    private val sessionOf: (() -> Session)? = null,
) {
    private val open = mutableListOf<EditorView>()

    private var session: Session? = sessionOf?.invoke()

    /** While the tabs of a session are being opened, the session is not written. */
    private var restoring = false

    /**
     * Write which files are open, which one is shown and where each
     * view looks to the workspace's session. Done when a tab is
     * opened or closed and when a view is saved under a name; views
     * without a file are left out, there is nothing to reopen them by.
     */
    fun saveSession() {
        if (!restoring) {
            session?.save(
                Session.Snapshot(
                    files = open.mapNotNull { it.content.currentFile },
                    activeFile = current?.content?.currentFile,
                    viewTransforms = open.mapNotNull { view -> view.content.currentFile?.let { it.toAbsolutePath() to view.viewTransform.matrix } }.toMap(),
                ),
            )
        }
    }

    /**
     * Open what the workspace's session remembers: its files that
     * still exist, each looking where it did, and the one that was
     * shown. Without any, the workspace directory is browsed: a
     * window always has a tab.
     */
    fun restore() {
        HelpFiles.install()
        val snapshot = session?.load() ?: Session.EMPTY
        restoring = true
        try {
            snapshot.files.forEach { path -> if (Fs.exists(path)) restoreTab(path, snapshot) else utils.Platform.warn("sane-graph-edit: session file $path no longer exists, skipping") }
            if (open.isEmpty()) browse(Workspace.dir)
        } finally {
            restoring = false
        }
        // the open and save dialogs start where the user was working
        open.lastOrNull { it.content.currentFile != null }?.content?.currentFile?.toAbsolutePath()?.getParent()?.let {
            AppState.lastOpenDir = it
            AppState.lastSaveDir = it
        }
        val shown = snapshot.activeFile?.let { wanted -> open.indexOfFirst { it.content.currentFile?.toString() == wanted.toString() } } ?: -1
        if (shown >= 0) select(shown)
        host.tabsChanged()
    }

    private fun restoreTab(path: Path, snapshot: Session.Snapshot) {
        try {
            val view = open(path)
            // by their text: paths are not equal to each other on every platform
            val key = path.toAbsolutePath().toString()
            snapshot.viewTransforms.entries.firstOrNull { it.key.toString() == key }?.let { view.viewTransform.matrix = it.value }
        } catch (e: Exception) {
            utils.Platform.warn("sane-graph-edit: failed to restore $path: ${e.message}")
        }
    }

    /**
     * `:cd`: make [dir] the [Workspace]. The open tabs are kept in
     * the session of the workspace being left and replaced by those
     * of [dir]'s session. Asks about unsaved changes first; false if
     * the user did not agree.
     */
    fun switchWorkspace(dir: Path): Boolean {
        val agreed = open.all { mayClose(it) }
        if (agreed) {
            saveSession()
            Workspace.dir = dir.toAbsolutePath().normalize()
            session = sessionOf?.invoke()
            current?.let { host.leaving(it) }
            open.forEach { it.content.close() }
            open.clear()
            currentIndex = -1
            AppState.lastOpenDir = Workspace.dir
            AppState.lastSaveDir = Workspace.dir
            restore()
        }
        return agreed
    }

    val views: List<EditorView> get() = open

    var currentIndex: Int = -1
        private set

    val current: EditorView? get() = open.getOrNull(currentIndex)

    /** The shell of a view in these tabs: the user's answers come from [dialogs], the rest is done here. */
    inner class ViewShell : Shell, Dialogs by dialogs {
        val tabs: Tabs get() = this@Tabs

        override fun stateChanged() = host.tabsChanged()
        override fun sessionChanged() = saveSession()
        override fun newTab(): EditorView = this@Tabs.newTab()
        override fun edit(path: Path): EditorView = this@Tabs.open(path)
        override fun browse(dir: Path): EditorView = this@Tabs.browse(dir)
        override fun switchWorkspace(dir: Path) {
            this@Tabs.switchWorkspace(dir)
        }
        override fun quit(force: Boolean, all: Boolean) {
            if (all) quitAll(force) else quitCurrent(force)
        }
        override fun modesChanged() = host.modesChanged()
        override fun views(): List<EditorView> = this@Tabs.views
    }

    private fun add(graph: Graph, path: Path?): EditorView {
        val view = host.view(graph, path, ViewShell())
        current?.let { host.leaving(it) }
        open += view
        currentIndex = open.size - 1
        host.tabsChanged()
        saveSession()
        return view
    }

    /** A new tab with the help graph, as an untitled document. */
    fun newTab(graph: Graph = Graph.defaultGraph()): EditorView = add(graph, null)

    /** The file or directory [path] in a tab of its own. */
    fun open(path: Path): EditorView =
        if (Fs.isDirectory(path)) browse(path) else add(Graph(), path).also { AppState.lastOpenDir = path.toAbsolutePath().getParent() }

    /** The file tree of [dir] in a tab of its own. */
    fun browse(dir: Path): EditorView =
        add(FileTree.build(dir, host.screen() ?: FileTree.DEFAULT_SCREEN), null).also {
            it.content.browseDir = dir.toAbsolutePath().normalize()
            it.content.untitledName = "browse: ${it.content.browseDir}"
            host.tabsChanged()
        }

    /** Ask for a file or directory and open it in a tab of its own. */
    fun openChosen() {
        dialogs.chooseOpen()?.let { open(it) }
    }

    fun select(index: Int) {
        if (index in open.indices && index != currentIndex) {
            current?.let { host.leaving(it) }
            currentIndex = index
            host.tabsChanged()
        }
    }

    fun selectNext() = select((currentIndex + 1).mod(open.size.coerceAtLeast(1)))

    fun selectPrevious() = select((currentIndex - 1).mod(open.size.coerceAtLeast(1)))

    /** True when [view] has no unsaved changes or the user agrees to lose them. */
    private fun mayClose(view: EditorView): Boolean =
        !view.content.isDirty || (host.mayClose(view) ?: discardAgreed(view))

    /** `:set discard` says not to ask; otherwise the user is asked. */
    private fun discardAgreed(view: EditorView): Boolean =
        graph_tools.ViewModes.discard || dialogs.confirm("Unsaved changes", "Discard the unsaved changes to ${view.content.title.removePrefix("*")}?")

    /**
     * Close the tab at [index], asking first if it has unsaved
     * changes (unless [force]). The last tab is not closed but
     * emptied: a window always has one. True if it was closed or
     * emptied.
     */
    fun close(index: Int = currentIndex, force: Boolean = false): Boolean {
        val view = open.getOrNull(index)
        val closing = view != null && (force || mayClose(view))
        if (view != null && closing) {
            if (open.size == 1) {
                view.content.clearToEmpty()
            } else {
                if (index == currentIndex) host.leaving(view)
                open.removeAt(index)
                view.content.close()
                if (currentIndex > index || currentIndex >= open.size) currentIndex--
            }
            host.tabsChanged()
            saveSession()
        }
        return closing
    }

    /** vim's `:q`: close the current tab, or end the application when it is the last one. */
    fun quitCurrent(force: Boolean) {
        if (open.size <= 1) quitAll(force) else close(currentIndex, force)
    }

    /** End the application, asking about every tab with unsaved changes unless [force]. True if it ends. */
    fun quitAll(force: Boolean): Boolean {
        // and the files nodes keep their text in, when no graph that is asked about covers them
        val files = FileContent.unsaved()
        val filesAgreed = {
            files.isEmpty() || graph_tools.ViewModes.discard ||
                dialogs.confirm("Unsaved changes", "Discard the unsaved changes to ${files.joinToString(", ")}? (:wa saves them)")
        }
        val agreed = force || (open.all { mayClose(it) } && filesAgreed())
        if (agreed) {
            saveSession()
            host.exit()
        }
        return agreed
    }
}

private val EditorView.tabs: Tabs? get() = (shell as? Tabs.ViewShell)?.tabs

/** The commands about tabs, for an application whose tabs are [Tabs]. */
fun registerTabCommands() {
    val r = CommandRegistry
    r.register("browse") { it.tabs?.browse(Workspace.dir) }
    r.register("tabnew") { it.tabs?.browse(Workspace.dir) }
    r.register("tabedit") { it.tabs?.openChosen() }
    r.register("tabclose") { it.tabs?.close() }
    r.register("tabnext") { it.tabs?.selectNext() }
    r.register("tabprevious") { it.tabs?.selectPrevious() }
}
