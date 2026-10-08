package editor

import sync.Event
import sync.Request

/**
 * Connects clients to an [EditorCore]: passes their requests in and
 * hands every client the events meant for it, in order.
 *
 * Like the core it owns no thread; all calls must come from the one
 * thread the host dedicates to the editor.
 */
class EditorHub(private val core: EditorCore, private val now: () -> Long) {
    private val clients: MutableMap<String, (Event) -> Unit> = linkedMapOf()
    private val queue: ArrayDeque<Outgoing> = ArrayDeque()
    private var draining = false
    private var nextClient = 0

    /** Register a client that receives its events through [deliver]; returns its id. The first one is the session owner. */
    fun connect(deliver: (Event) -> Unit): String {
        val id = "c${nextClient++}"
        clients[id] = deliver
        core.connect(id)
        return id
    }

    fun disconnect(client: String) {
        clients.remove(client)
        core.disconnect(client)
    }

    /** Process [request]; every resulting event is delivered. */
    fun send(client: String, request: Request) {
        queue.addAll(core.handle(client, request, now()))
        drain()
    }

    /**
     * Process [request] and return the editor's answer to [client]
     * (its first event, if any) instead of delivering it. Not to be
     * used while handling an event: events still waiting for the
     * client would be overtaken.
     */
    fun call(client: String, request: Request): Event? {
        check(!draining) { "call() while an event is being delivered" }
        val out = core.handle(client, request, now())
        val reply = out.firstOrNull { it.client == client }
        queue.addAll(out.filter { it !== reply })
        drain()
        return reply?.event
    }

    // a client may send a request while it is being handed an event; its events then wait their turn
    private fun drain() {
        if (!draining) {
            draining = true
            try {
                while (queue.isNotEmpty()) {
                    val o = queue.removeFirst()
                    clients[o.client]?.invoke(o.event)
                }
            } finally {
                draining = false
            }
        }
    }
}
