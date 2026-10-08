package ui

import client.EditorClient
import host.EditorHost

/**
 * This process's line to the editor session that owns the
 * documents. Null until [start] — unit tests leave it so, and views
 * then edit graphs of their own. The system property
 * `sane-graph-edit.session=true` starts it up front, which is how
 * the `sessionTest` task runs the same tests through a session.
 */
object Editor {
    var client: EditorClient? = if (System.getProperty("sane-graph-edit.session") == "true") local() else null
        private set

    /** Run an editor session in this process and connect to it as its owner. */
    fun start() {
        if (client == null) client = local()
    }

    private fun local(): EditorClient = EditorClient(EditorHost().connect())
}
