package sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a client asks of the editor (conn2). Only shared document
 * state crosses here: nothing transient, nothing per user.
 */
@Serializable
sealed interface Request

/** Attach to the document of the file at [path], loading it if no client has it open. */
@Serializable
@SerialName("open")
data class OpenFile(val path: String) : Request

/** A new document without a file, with [state] as its content; the sender is attached to it. */
@Serializable
@SerialName("create")
data class CreateDocument(val state: GraphState, val title: String? = null) : Request

@Serializable
@SerialName("attach")
data class Attach(val document: String) : Request

/** A document nobody is attached to any more is closed. */
@Serializable
@SerialName("detach")
data class Detach(val document: String) : Request

@Serializable
@SerialName("documents")
data object ListDocuments : Request

/**
 * Change a document. [baseRevision] is the last revision the client
 * had seen when it made the change; [opId] comes back in the answer.
 */
@Serializable
@SerialName("submit")
data class Submit(val document: String, val opId: String, val baseRevision: Long, val patch: Patch) : Request

@Serializable
@SerialName("undo")
data class Undo(val document: String) : Request

@Serializable
@SerialName("redo")
data class Redo(val document: String) : Request

/**
 * Write the document to its file, or to [path] and make that its
 * file. With [copy] the document keeps its file and its dirty flag.
 */
@Serializable
@SerialName("save")
data class Save(val document: String, val path: String? = null, val copy: Boolean = false) : Request

/** Replace the content with the file's; drops the undo history. */
@Serializable
@SerialName("reload")
data class Reload(val document: String) : Request

/** What the editor tells a client. */
@Serializable
sealed interface Event

/** [clients] is the number of clients attached to the document. */
@Serializable
data class DocumentInfo(val document: String, val file: String?, val title: String?, val dirty: Boolean, val clients: Int = 0)

/** Answer to [OpenFile], [CreateDocument] and [Attach]: the whole content. */
@Serializable
@SerialName("attached")
data class Attached(val info: DocumentInfo, val revision: Long, val state: GraphState) : Event

/**
 * The document changed and is now at [revision]. Sent to every
 * attached client, the one that caused it included: [origin] and
 * [opId] say whose [Submit] it was (no [opId] for undo, redo, reload).
 */
@Serializable
@SerialName("patched")
data class Patched(val document: String, val revision: Long, val patch: Patch, val origin: String, val opId: String?) : Event

@Serializable
@SerialName("rejected")
data class Rejected(val document: String, val opId: String, val reason: String) : Event

/** File, title or dirty flag of a document changed. */
@Serializable
@SerialName("document-changed")
data class DocumentChanged(val info: DocumentInfo) : Event

@Serializable
@SerialName("documents")
data class Documents(val documents: List<DocumentInfo>) : Event

/** A request that is not a [Submit] failed. */
@Serializable
@SerialName("failed")
data class Failed(val request: Request, val reason: String) : Event

/**
 * One line from a client on a byte stream. With [call] the editor
 * answers this very message with a [ServerMessage] carrying its [id].
 */
@Serializable
data class ClientMessage(val id: Long, val request: Request, val call: Boolean = false)

/**
 * One line from the editor: the greeting ([client], [owner]), the
 * answer to a call ([replyTo]; no [event] when there is nothing to
 * say), an [event], or an [error] about a line it could not read.
 */
@Serializable
data class ServerMessage(
    val event: Event? = null,
    val replyTo: Long? = null,
    val client: String? = null,
    val owner: Boolean? = null,
    val error: String? = null,
)

/** The protocol as JSON, one message per line. */
object Wire {
    private val json = Json { encodeDefaults = false }

    fun encode(m: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), m)
    fun encode(m: ServerMessage): String = json.encodeToString(ServerMessage.serializer(), m)
    fun decodeClient(line: String): ClientMessage = json.decodeFromString(ClientMessage.serializer(), line)
    fun decodeServer(line: String): ServerMessage = json.decodeFromString(ServerMessage.serializer(), line)

    fun encode(e: Event): String = json.encodeToString(Event.serializer(), e)
    fun decodePatch(text: String): Patch = json.decodeFromString(Patch.serializer(), text)
}
