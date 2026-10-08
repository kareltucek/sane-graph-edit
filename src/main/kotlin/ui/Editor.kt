package ui

import client.EditorClient
import client.SocketConnection
import host.EditorHost
import host.SessionLock
import host.SocketServer
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * This process's line to the editor session that owns the
 * documents. Null until [start] or [attach] — unit tests leave it
 * so, and views then edit graphs of their own. The system property
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
     * Run a session of this process's own. It listens at [socket]
     * for tools (`ctl`) and windows started with `--attach`, unless
     * another process already does: then it is simply not reachable.
     */
    fun start(socket: Path? = null) {
        if (client == null) {
            val host = EditorHost()
            // best effort: without the socket the editor still works, alone
            socket?.takeIf { runCatching { SessionLock.acquire(it) }.getOrDefault(false) }?.let { s ->
                runCatching { SocketServer(host.hub, s) { SwingUtilities.invokeLater(it) }.start() }
                    .onSuccess { server -> Runtime.getRuntime().addShutdownHook(Thread { server.close() }) }
                    .onFailure { System.err.println("sane-graph-edit: no session socket at $s: ${it.message}") }
            }
            client = EditorClient(host.connect())
        }
    }

    /** Join the session listening at [socket]. False when there is none. */
    fun attach(socket: Path): Boolean {
        val remote = runCatching { SocketConnection(socket) { SwingUtilities.invokeLater(it) } }.getOrNull()
        if (remote != null) {
            val c = EditorClient(remote)
            client = c
            isOwner = false
            remote.onClosed = {
                client = null
                isOwner = true
                c.connectionLost()
                onOwnerGone()
            }
        }
        return remote != null
    }
}
