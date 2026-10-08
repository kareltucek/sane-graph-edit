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
            val remote = socket?.let { connect(it) }
            client = if (remote != null) join(remote) else own(socket)
        }
    }

    private fun tryConnect(socket: Path): SocketConnection? =
        runCatching { SocketConnection(socket) { SwingUtilities.invokeLater(it) } }.getOrNull()

    /**
     * A connection to the session at [socket], or null when this
     * process is to own it. Two windows started together must not
     * both become owners: the session lock picks one, and the other
     * waits for it to start listening.
     */
    private fun connect(socket: Path): SocketConnection? {
        val existing = tryConnect(socket)
        val ownIt = existing == null && runCatching { SessionLock.acquire(socket) }.getOrDefault(false)
        mayListen = ownIt
        val deadline = System.currentTimeMillis() + OWNER_STARTUP_MS
        var remote = existing
        while (remote == null && !ownIt && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            remote = tryConnect(socket)
        }
        return remote
    }

    /** How long to wait for a session's owner, seen starting up, to listen. */
    private const val OWNER_STARTUP_MS = 10_000L

    /** Whether this process holds the session lock, i.e. may listen on the socket. */
    private var mayListen = false

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
        socket?.takeIf { mayListen }?.let { s ->
            runCatching { SocketServer(host.hub, s) { SwingUtilities.invokeLater(it) }.start() }
                .onSuccess { server -> Runtime.getRuntime().addShutdownHook(Thread { server.close() }) }
                .onFailure { System.err.println("sane-graph-edit: no session socket at $s: ${it.message}") }
        }
        return EditorClient(host.connect())
    }
}
