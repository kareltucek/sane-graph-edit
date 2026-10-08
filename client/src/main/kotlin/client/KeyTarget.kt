package client

import java.nio.file.Path

/** Delayed actions on the client's thread; the platform provides it. */
fun interface Scheduler {
    /** Run [action] after [ms] milliseconds; the result cancels it. */
    fun after(ms: Int, action: () -> Unit): () -> Unit
}

/**
 * The view that keys and `:` commands act on, with the application
 * around it, as the [KeyMapper] needs them: the part of a `:`
 * command that is the renderer's or the application's to do.
 */
interface KeyTarget {
    val model: EditorView

    /** Show [text] to the user (`:help`, errors of `:` commands). */
    fun message(text: String)

    /** Save to the view's file, asking for one if needed. False when it was not saved. */
    fun saveFile(): Boolean

    /** Write the document to [path] without making it the view's file. */
    fun saveCopy(path: Path)

    /** Replace the content with the file's, asking first about unsaved changes unless [force]. */
    fun reloadFile(force: Boolean = false)

    /** Open the file or directory [path] in a tab of its own. */
    fun edit(path: Path)

    /** Show the file tree of [dir] in a tab. */
    fun browse(dir: Path)

    /** Make [dir] the workspace, replacing the open tabs by its session's. */
    fun switchWorkspace(dir: Path)

    /** Close this view's tab, or with [all] the whole application; [force] skips the questions about unsaved changes. */
    fun quit(force: Boolean, all: Boolean)

    /** A view mode (`:set unhide` …) changed: every view shows it. */
    fun modesChanged()
}
