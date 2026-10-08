package client

import sync.ClientMessage
import sync.Event
import sync.Request
import sync.ServerMessage
import sync.Wire
import java.io.BufferedReader
import java.io.Writer
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A connection to an editor in another process, through its Unix
 * domain socket at [path]. Throws when nobody listens there.
 *
 * Events are handed over through [onClientThread], which must run
 * the given block on the client's thread, in order. [call] may be
 * used from that thread: answers do not go through it.
 */
class SocketConnection(path: Path, private val onClientThread: (() -> Unit) -> Unit) : EditorConnection {
    private val channel: SocketChannel = SocketChannel.open(UnixDomainSocketAddress.of(path))
    private val reader = BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))
    private val writer: Writer = Channels.newWriter(channel, StandardCharsets.UTF_8)
    private val calls = ConcurrentHashMap<Long, CompletableFuture<ServerMessage>>()
    private val nextId = AtomicLong()

    override var onEvent: (Event) -> Unit = {}

    /** The editor is gone: its process ended or the connection broke. */
    var onClosed: () -> Unit = {}

    override val clientId: String

    /** True if this client is the session owner (it never is: the owner is the process running the editor). */
    val owner: Boolean

    init {
        val hello = Wire.decodeServer(reader.readLine() ?: throw EditorException("the editor closed the connection"))
        clientId = hello.client ?: throw EditorException("unexpected greeting from the editor")
        owner = hello.owner == true
        thread(isDaemon = true, name = "editor-connection") { read() }
    }

    private fun read() {
        runCatching {
            reader.forEachLine { line ->
                val m = Wire.decodeServer(line)
                val waiting = m.replyTo?.let { calls.remove(it) }
                val event = m.event
                when {
                    waiting != null -> waiting.complete(m)
                    event != null -> onClientThread { onEvent(event) }
                }
            }
        }
        calls.values.forEach { it.completeExceptionally(EditorException("the editor is gone")) }
        calls.clear()
        onClientThread { onClosed() }
    }

    private fun write(m: ClientMessage) {
        synchronized(writer) {
            try {
                writer.write(Wire.encode(m))
                writer.write("\n")
                writer.flush()
            } catch (e: java.io.IOException) {
                throw EditorException("the editor is gone")
            }
        }
    }

    override fun send(request: Request) = write(ClientMessage(nextId.getAndIncrement(), request))

    override fun call(request: Request): Event? {
        val id = nextId.getAndIncrement()
        val answer = CompletableFuture<ServerMessage>()
        calls[id] = answer
        write(ClientMessage(id, request, call = true))
        val m = try {
            answer.get(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            calls.remove(id)
            throw EditorException((e.cause ?: e).message ?: "no answer from the editor")
        }
        return m.event
    }

    override fun close() {
        runCatching { channel.close() }
    }

    companion object {
        const val CALL_TIMEOUT_S = 10L
    }
}
