package client


/** Delayed actions on the client's thread; the platform provides it. */
fun interface Scheduler {
    /** Run [action] after [ms] milliseconds; the result cancels it. */
    fun after(ms: Int, action: () -> Unit): () -> Unit
}

/** What is said to the user and asked of them; some of these wait for the answer. */
interface Dialogs {
    /** Show [text] to the user (`:help`, what went wrong with a command). */
    fun message(text: String)

    /** Tell the user something that needs acknowledging. */
    fun alert(title: String, text: String, error: Boolean = true)

    /** Ask a yes/no question; true for yes. */
    fun confirm(title: String, question: String): Boolean

    /** Let the user choose a graph file or a directory to open. */
    fun chooseOpen(): Path?

    /** Let the user choose where to save the graph. */
    fun chooseSave(defaultName: String): Path?

    /** Let the user choose where to write an SVG export. */
    fun chooseExport(defaultName: String): Path?
}

/**
 * The application around a view (conn1, client → renderer): the
 * questions it puts to the user ([Dialogs]) and the other tabs.
 * Unlike [Renderer], some of these wait for an answer; with the
 * renderer in another process they become a request and its reply.
 */
interface Shell : Dialogs {

    /** The view's title, file or unsaved state changed. */
    fun stateChanged()

    /** What is open where changed: worth remembering for the next start. */
    fun sessionChanged()

    /** A new tab; its view, or null when the application has no tabs. */
    fun newTab(): EditorView?

    /** Open the file or directory [path] in a tab of its own. Fails when the file can't be read. */
    fun edit(path: Path): EditorView?

    /** Show the file tree of [dir] in a tab. */
    fun browse(dir: Path): EditorView?

    /** Make [dir] the workspace, replacing the open tabs by its session's. */
    fun switchWorkspace(dir: Path)

    /** Close this view's tab, or with [all] the whole application; [force] skips the questions about unsaved changes. */
    fun quit(force: Boolean, all: Boolean)

    /** A view mode (`:set unhide` …) changed: every view shows it. */
    fun modesChanged()

    /** Every view the application has open. */
    fun views(): List<EditorView> = emptyList()
}

/** No application: messages are printed, questions are answered with no. */
object NoShell : Shell {
    override fun message(text: String) = print(text)
    override fun alert(title: String, text: String, error: Boolean) = utils.Platform.warn("$title: $text")
    override fun confirm(title: String, question: String): Boolean = false
    override fun chooseOpen(): Path? = null
    override fun chooseSave(defaultName: String): Path? = null
    override fun chooseExport(defaultName: String): Path? = null
    override fun stateChanged() {}
    override fun sessionChanged() {}
    override fun newTab(): EditorView? = null
    override fun edit(path: Path): EditorView? = null
    override fun browse(dir: Path): EditorView? = null
    override fun switchWorkspace(dir: Path) {
        Workspace.dir = dir
    }
    override fun quit(force: Boolean, all: Boolean) {}
    override fun modesChanged() {}
}
