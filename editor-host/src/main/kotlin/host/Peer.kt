package host

import editor.EditorHub
import sync.ServerMessage
import sync.Wire
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Serves one client of [hub] that lives in another process, whatever
 * carries its messages: JSON, one [sync.ClientMessage] in and one
 * [sync.ServerMessage] out at a time.
 *
 * [read] hands over every incoming message and returns (or throws)
 * when the client is gone; this call returns then too. [write] is
 * called from one thread of its own. The hub is only ever touched
 * through [onEditorThread], which must run the given block on the
 * editor's thread, in order.
 */
internal fun servePeer(hub: EditorHub, onEditorThread: (() -> Unit) -> Unit, read: ((String) -> Unit) -> Unit, write: (String) -> Unit) {
    val out = LinkedBlockingQueue<String>()
    val writer = thread(isDaemon = true, name = "editor-peer-writer") {
        runCatching {
            while (true) write(out.take())
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
        read { text -> if (text.isNotBlank()) handle(hub, onEditorThread, client, text, out) }
    } catch (_: java.io.IOException) {
        // the client went away
    } finally {
        onEditorThread { hub.disconnect(client) }
        writer.interrupt()
    }
}

private fun handle(hub: EditorHub, onEditorThread: (() -> Unit) -> Unit, client: String, text: String, out: LinkedBlockingQueue<String>) {
    val parsed = runCatching { Wire.decodeClient(text) }
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
