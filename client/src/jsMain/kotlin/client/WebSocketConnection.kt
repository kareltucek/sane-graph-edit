package client

import org.w3c.dom.WebSocket
import sync.ClientMessage
import sync.Event
import sync.Failed
import sync.Request
import sync.ServerMessage
import sync.Wire

/**
 * A connection to an editor in another process, through its
 * WebSocket. Made by [connect]. JavaScript cannot wait for an
 * answer: [call] always fails here, [request] is the way.
 */
class WebSocketConnection private constructor(
    private val socket: WebSocket,
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

    override fun send(request: Request) = socket.send(Wire.encode(ClientMessage(nextId++, request)))

    override fun request(request: Request, onAnswer: (Event?) -> Unit) {
        val id = nextId++
        waiting[id] = request to onAnswer
        socket.send(Wire.encode(ClientMessage(id, request, call = true)))
    }

    override fun call(request: Request): Event? = throw EditorException("a WebSocket cannot wait for an answer")

    override fun close() = socket.close()

    private fun received(m: ServerMessage) {
        val answered = m.replyTo?.let { waiting.remove(it) }
        val event = m.event
        when {
            answered != null -> answered.second(event)
            event != null -> onEvent(event)
        }
    }

    private fun closed() {
        if (!isClosed) {
            isClosed = true
            val unanswered = waiting.values.toList()
            waiting.clear()
            unanswered.forEach { (request, onAnswer) -> onAnswer(Failed(request, "the editor is gone")) }
            onClosed()
        }
    }

    companion object {
        /** Connect to the editor at [url] (`ws://127.0.0.1:<port>/?token=…`); [then] gets the connection once the editor has greeted it. */
        fun connect(url: String, then: (Result<WebSocketConnection>) -> Unit) {
            val socket = WebSocket(url)
            var connection: WebSocketConnection? = null
            var refused = false
            socket.onmessage = { message ->
                val m = Wire.decodeServer(message.data as String)
                val id = m.client
                val open = connection
                when {
                    open != null -> open.received(m)
                    id != null -> connection = WebSocketConnection(socket, id, m.owner == true).also { then(Result.success(it)) }
                    else -> socket.close()
                }
            }
            // a refused connection ends with an error, a close, or both: a browser tells both, Node 22.0 only the error
            val gone = { _: Any? ->
                val open = connection
                if (open != null) {
                    open.closed()
                } else if (!refused) {
                    refused = true
                    then(Result.failure(EditorException("no editor answers at that address")))
                }
            }
            socket.onclose = gone
            socket.onerror = gone
        }
    }
}
