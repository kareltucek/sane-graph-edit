package host

import client.Fs
import client.Lines
import client.Path
import editor.EditorHub
import sync.ServerMessage
import sync.Wire

private external fun require(module: String): dynamic
private external val process: dynamic

/**
 * Lets other processes join the session through a Unix domain
 * socket, as the JVM's `SocketServer` does: JSON, one
 * [sync.ClientMessage] / [sync.ServerMessage] per line. For
 * Node.js; everything runs on its one thread.
 *
 * Who serves a socket is settled the same way by every kind of
 * process, see [start].
 */
object SessionServer {
    /** Somebody who is in the middle of [start] and has not finished for this long has died in it. */
    private const val STARTING_STALE_MS = 5000

    private val fs: dynamic by lazy { require("fs") }
    private val net: dynamic by lazy { require("net") }

    /**
     * Become the one who serves the session at [socket], unless a
     * process already does; [then] is told which. Whether one does
     * is asked of the socket itself: if somebody accepts a
     * connection there, the session is theirs. Asking and starting
     * to listen is done by one process at a time, the one that
     * could create `<socket>.starting`.
     */
    fun start(hub: EditorHub, socket: Path, then: (Boolean) -> Unit) {
        val starting = "$socket.starting"
        val done = { serving: Boolean ->
            runCatching { fs.unlinkSync(starting) }
            then(serving)
        }
        if (!claim(socket, starting)) {
            then(false)
        } else {
            val probe: dynamic = net.createConnection(socket.toString())
            probe.on("connect") {
                probe.destroy()
                done(false)
            }
            probe.on("error") {
                // nobody there: what is left of a socket is a dead process's
                runCatching { fs.unlinkSync(socket.toString()) }
                val server: dynamic = net.createServer { connection: dynamic -> serve(hub, connection) }
                server.on("error") { done(false) }
                server.listen(socket.toString()) {
                    servers[socket.toString()] = server
                    process.on("exit") { runCatching { fs.unlinkSync(socket.toString()) } }
                    done(true)
                }
            }
        }
    }

    /** What this process listens at, by socket path. */
    private val servers = mutableMapOf<String, dynamic>()

    /** Stop serving [socket]: nobody new can join; those who have stay until they leave. */
    fun stop(socket: Path) {
        // no `?.let` on a JavaScript value: that would be a call of its own `let`
        val server: dynamic = servers.remove(socket.toString())
        if (server != null) {
            server.close()
            runCatching { fs.unlinkSync(socket.toString()) }
        }
    }

    /** True if this process may now ask and start: it created [starting], if need be after removing one left by a process that died. */
    private fun claim(socket: Path, starting: String): Boolean {
        runCatching { socket.getParent()?.let { Fs.createDirectories(it) } }
        val create = { runCatching { fs.closeSync(fs.openSync(starting, "wx")) }.isSuccess }
        val stale = { runCatching { (js("Date.now()") as Double) - (fs.statSync(starting).mtimeMs as Double) > STARTING_STALE_MS }.getOrDefault(false) }
        return create() || (stale() && runCatching { fs.unlinkSync(starting) }.isSuccess && create())
    }

    private fun serve(hub: EditorHub, connection: dynamic) {
        connection.setEncoding("utf8")
        val write = { m: ServerMessage -> connection.write(Wire.encode(m) + "\n") }
        val client = hub.connect { write(ServerMessage(event = it)) }
        write(ServerMessage(client = client, owner = hub.isOwner(client)))
        val lines = Lines { text ->
            val parsed = runCatching { Wire.decodeClient(text) }
            val message = parsed.getOrNull()
            when {
                message == null -> write(ServerMessage(error = "cannot read message: ${parsed.exceptionOrNull()?.message}"))
                message.call -> write(ServerMessage(event = hub.call(client, message.request), replyTo = message.id))
                else -> hub.send(client, message.request)
            }
        }
        connection.on("data") { chunk: String -> lines.feed(chunk) }
        connection.on("close") { hub.disconnect(client) }
        // the close that follows does the rest
        connection.on("error") { }
    }
}
