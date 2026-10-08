package ui

import client.EditorClient
import client.SocketConnection
import host.EditorHost
import host.SocketServer
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * This process's line to the editor session that owns the
 * documents. Null until [start] — unit tests leave it so, and views
 * then edit graphs of their own. The system property
 * `sane-graph-edit.session=true` starts it up front, which is how
 * the `sessionTest` task runs the same tests through a session.
 */
object Editor {
    var client: EditorClient? = if (System.getProperty("sane-graph-edit.session") == "true") EditorClient(EditorHost().connect()) else null
        private set

    /** True when the session's editor runs in this process. */
    var isOwner: Boolean = true
        private set

    /** Told, on the event thread, that the session's owner quit. */
    var onOwnerGone: () -> Unit = {}

    /**
     * Join the session listening at [socket]. If there is none, run
     * the editor in this process and listen there for others. Without
     * a [socket] the session is private to this process.
     */
    fun start(socket: Path? = null) {
        if (client == null) {
            val remote = socket?.let { s -> runCatching { SocketConnection(s) { SwingUtilities.invokeLater(it) } }.getOrNull() }
            client = if (remote != null) join(remote) else own(socket)
        }
    }

    private fun join(remote: SocketConnection): EditorClient {
        val c = EditorClient(remote)
        isOwner = false
        remote.onClosed = {
            client = null
            isOwner = true
            c.connectionLost()
            onOwnerGone()
        }
        return c
    }

    private fun own(socket: Path?): EditorClient {
        val host = EditorHost()
        // best effort: without the socket the editor still works, alone
        socket?.let { s ->
            runCatching { SocketServer(host.hub, s) { SwingUtilities.invokeLater(it) }.start() }
                .onSuccess { server -> Runtime.getRuntime().addShutdownHook(Thread { server.close() }) }
                .onFailure { System.err.println("sane-graph-edit: no session socket at $s: ${it.message}") }
        }
        return EditorClient(host.connect())
    }
}
