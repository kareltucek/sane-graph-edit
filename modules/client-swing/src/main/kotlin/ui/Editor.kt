package ui

import client.EditorClient
import client.SocketConnection
import host.EditorHost
import host.SessionLock
import host.SocketServer
import host.WebSocketServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
     * With [webSocketPort] it also listens there (0: any free port),
     * for clients that cannot use the socket; the address they need
     * is printed and kept in a file next to the socket.
     */
    fun start(socket: Path? = null, webSocketPort: Int? = null) {
        if (client == null) {
            val host = EditorHost()
            // the window's own client before anybody can connect: the first one is the session's owner
            client = EditorClient(host.connect())
            webSocketPort?.let { port ->
                runCatching { WebSocketServer(host.hub, port) { SwingUtilities.invokeLater(it) }.start() }
                    .onSuccess { server -> announce(server, socket) }
                    .onFailure { System.err.println("sane-graph-edit: no WebSocket on port $port: ${it.message}") }
            }
            // best effort: without the socket the editor still works, alone
            socket?.let { s ->
                runCatching { SessionLock.claim(s) { SocketServer(host.hub, s) { SwingUtilities.invokeLater(it) }.start() } }
                    .onSuccess { server -> server?.let { Runtime.getRuntime().addShutdownHook(Thread { it.close() }) } }
                    .onFailure { System.err.println("sane-graph-edit: no session socket at $s: ${it.message}") }
            }
        }
    }

    // the address holds the token: the file is for this user only, as is the directory it is in
    private fun announce(server: WebSocketServer, socket: Path?) {
        println(server.url)
        socket?.resolveSibling(socket.fileName.toString().removeSuffix(".sock") + ".ws")?.let { file ->
            runCatching {
                Files.createDirectories(file.parent)
                Files.deleteIfExists(file)
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                Files.writeString(file, server.url + "\n")
                Runtime.getRuntime().addShutdownHook(Thread { runCatching { Files.deleteIfExists(file) } })
            }.onFailure { System.err.println("sane-graph-edit: cannot write $file: ${it.message}") }
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
