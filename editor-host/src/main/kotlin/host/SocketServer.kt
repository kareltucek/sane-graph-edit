package host

import editor.EditorHub
import java.io.BufferedReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
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
        val w = Channels.newWriter(ch, StandardCharsets.UTF_8)
        try {
            servePeer(
                hub,
                onEditorThread,
                read = { message -> BufferedReader(Channels.newReader(ch, StandardCharsets.UTF_8)).forEachLine(message) },
                write = {
                    w.write(it)
                    w.write("\n")
                    w.flush()
                },
            )
        } finally {
            runCatching { ch.close() }
        }
    }
}
