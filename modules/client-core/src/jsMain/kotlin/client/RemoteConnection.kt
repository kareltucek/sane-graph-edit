package client

import org.w3c.dom.WebSocket
import sync.ClientMessage
import sync.Event
import sync.Failed
import sync.Request
import sync.ServerMessage
import sync.Wire

/**
 * A connection to an editor in another process: one JSON message
 * at a time each way, over whatever [WebSocketConnection] or
 * [UnixSocketConnection] connected. JavaScript cannot wait for an
 * answer: [call] always fails here, [request] is the way.
 */
class RemoteConnection internal constructor(
    private val sendText: (String) -> Unit,
    private val closeIt: () -> Unit,
    override val clientId: String,
    /** True if this client is the session owner (it never is: the owner is the process running the editor). */
    val owner: Boolean,
) : EditorConnection {
    private val waiting: MutableMap<Long, Pair<Request, (Event?) -> Unit>> = linkedMapOf()
    private var nextId = 0L
    private var isClosed = false

    override var onEvent: (Event) -> Unit = {}

    /** The editor is gone: its process ended or the connection broke. */
    var onClosed: () -> Unit = {}

    override fun send(request: Request) = sendText(Wire.encode(ClientMessage(nextId++, request)))

    override fun request(request: Request, onAnswer: (Event?) -> Unit) {
        val id = nextId++
        waiting[id] = request to onAnswer
        sendText(Wire.encode(ClientMessage(id, request, call = true)))
    }

    override fun call(request: Request): Event? = throw EditorException("a connection to another process cannot wait for an answer here")

    override fun close() = closeIt()

    internal fun received(m: ServerMessage) {
        val answered = m.replyTo?.let { waiting.remove(it) }
        val event = m.event
        when {
            answered != null -> answered.second(event)
            event != null -> onEvent(event)
        }
    }

    internal fun closed() {
        if (!isClosed) {
            isClosed = true
            val unanswered = waiting.values.toList()
            waiting.clear()
            unanswered.forEach { (request, onAnswer) -> onAnswer(Failed(request, "the editor is gone")) }
            onClosed()
        }
    }
}

/**
 * The two ends of a line to an editor while it is being connected:
 * the editor's first message says who this client is, and only
 * then is there a [RemoteConnection] to hand to [then]. A line that
 * ends before that was refused.
 */
private class Connecting(
    private val sendText: (String) -> Unit,
    private val closeIt: () -> Unit,
    private val then: (Result<RemoteConnection>) -> Unit,
) {
    private var connection: RemoteConnection? = null
    private var refused = false

    fun message(text: String) {
        val m = Wire.decodeServer(text)
        val id = m.client
        val open = connection
        when {
            open != null -> open.received(m)
            id != null -> connection = RemoteConnection(sendText, closeIt, id, m.owner == true).also { then(Result.success(it)) }
            else -> closeIt()
        }
    }

    fun gone() {
        val open = connection
        if (open != null) {
            open.closed()
        } else if (!refused) {
            refused = true
            then(Result.failure(EditorException("no editor answers at that address")))
        }
    }
}

object WebSocketConnection {
    /** Connect to the editor at [url] (`ws://127.0.0.1:<port>/?token=…`); [then] gets the connection once the editor has greeted it. */
    fun connect(url: String, then: (Result<RemoteConnection>) -> Unit) {
        val socket = WebSocket(url)
        val connecting = Connecting({ socket.send(it) }, { socket.close() }, then)
        socket.onmessage = { message -> connecting.message(message.data as String) }
        // a refused connection ends with an error, a close, or both: a browser tells both, Node 22.0 only the error
        socket.onclose = { connecting.gone() }
        socket.onerror = { connecting.gone() }
    }
}

private external fun require(module: String): dynamic

object UnixSocketConnection {
    /**
     * Connect to the editor that listens at the Unix domain socket
     * [path] (see [SessionSocket]), where every message is a line;
     * [then] gets the connection once the editor has greeted it.
     * For Node.js: a browser has no such sockets.
     */
    fun connect(path: String, then: (Result<RemoteConnection>) -> Unit) {
        val socket: dynamic = require("net").createConnection(path)
        socket.setEncoding("utf8")
        val connecting = Connecting({ socket.write(it + "\n") }, { socket.end() }, then)
        val lines = Lines { connecting.message(it) }
        socket.on("data") { chunk: String -> lines.feed(chunk) }
        socket.on("close") { connecting.gone() }
        socket.on("error") { connecting.gone() }
    }
}

/** Text that arrives in pieces, handed on line by line; blank lines are dropped. */
class Lines(private val onLine: (String) -> Unit) {
    private var rest = ""

    fun feed(chunk: String) {
        val parts = (rest + chunk).split("\n")
        rest = parts.last()
        parts.dropLast(1).filter { it.isNotBlank() }.forEach(onLine)
    }
}
