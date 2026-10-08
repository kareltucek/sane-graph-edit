package host

import editor.EditorHub
import sync.ServerMessage
import sync.Wire
import java.io.BufferedReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Lets other processes join the session through a Unix domain
 * socket at [path]: JSON, one [sync.ClientMessage] /
 * [sync.ServerMessage] per line.
 *
 * The hub is only ever touched through [onEditorThread], which must
 * run the given block on the editor's thread, in order.
 */
class SocketServer(
    private val hub: EditorHub,
    val path: Path,
    private val onEditorThread: (() -> Unit) -> Unit,
) {
    private val server: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)

    fun start(): SocketServer {
        Files.createDirectories(path.parent)
        Files.deleteIfExists(path)
        server.bind(UnixDomainSocketAddress.of(path))
        thread(isDaemon = true, name = "editor-socket-accept") {
            runCatching {
                while (true) {
                    val ch = server.accept()
                    thread(isDaemon = true, name = "editor-socket-client") { serve(ch) }
                }
            }
        }
        return this
    }

    fun close() {
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(path) }
    }

    private fun serve(ch: SocketChannel) {
        val out = LinkedBlockingQueue<String>()
        val writer = thread(isDaemon = true, name = "editor-socket-writer") {
            runCatching {
                val w = Channels.newWriter(ch, StandardCharsets.UTF_8)
                while (true) {
                    w.write(out.take())
                    w.write("\n")
                    w.flush()
                }
            }
        }
        val connected = CompletableFuture<String>()
        onEditorThread {
            val id = hub.connect { out.put(Wire.encode(ServerMessage(event = it))) }
            out.put(Wire.encode(ServerMessage(client = id, owner = hub.isOwner(id))))
            connected.complete(id)
        }
        val client = connected.get()
        try {
            BufferedReader(Channels.newReader(ch, StandardCharsets.UTF_8)).forEachLine { line ->
                if (line.isNotBlank()) handle(client, line, out)
            }
        } catch (_: java.io.IOException) {
            // the client went away
        } finally {
            onEditorThread { hub.disconnect(client) }
            writer.interrupt()
            runCatching { ch.close() }
        }
    }

    private fun handle(client: String, line: String, out: LinkedBlockingQueue<String>) {
        val parsed = runCatching { Wire.decodeClient(line) }
        val message = parsed.getOrNull()
        if (message == null) {
            out.put(Wire.encode(ServerMessage(error = "cannot read message: ${parsed.exceptionOrNull()?.message}")))
        } else {
            onEditorThread {
                if (message.call) {
                    out.put(Wire.encode(ServerMessage(event = hub.call(client, message.request), replyTo = message.id)))
                } else {
                    hub.send(client, message.request)
                }
            }
        }
    }
}
