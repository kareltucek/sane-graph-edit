package host

import client.EditorConnection
import editor.EditorCore
import editor.EditorHub
import editor.FileStore
import sync.Event
import sync.Request
import java.nio.file.Files
import java.nio.file.Paths

/** The local file system. */
object DiskFiles : FileStore {
    override fun read(path: String): String = Files.readString(Paths.get(path))

    override fun write(path: String, text: String) {
        Files.writeString(Paths.get(path), text)
    }
}

/**
 * An editor session living in this process. Everything — the
 * clients made by [connect] included — runs on the thread that
 * uses it.
 */
class EditorHost(files: FileStore = DiskFiles, now: () -> Long = System::currentTimeMillis) {
    val hub = EditorHub(EditorCore(files), now)

    /** A connection for a client in this process; the first one is the session owner. */
    fun connect(): EditorConnection = LocalConnection(hub)
}

private class LocalConnection(private val hub: EditorHub) : EditorConnection {
    override var onEvent: (Event) -> Unit = {}
    override val clientId: String = hub.connect { onEvent(it) }

    override fun send(request: Request) = hub.send(clientId, request)

    override fun call(request: Request): Event? = hub.call(clientId, request)

    override fun close() = hub.disconnect(clientId)
}
