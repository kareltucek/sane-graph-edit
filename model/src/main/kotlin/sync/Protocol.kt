package sync

/**
 * What a client asks of the editor (conn2). Only shared document
 * state crosses here: nothing transient, nothing per user.
 */
sealed interface Request

/** Attach to the document of the file at [path], loading it if no client has it open. */
data class OpenFile(val path: String) : Request

/** A new document without a file, with [state] as its content; the sender is attached to it. */
data class CreateDocument(val state: GraphState, val title: String? = null) : Request

data class Attach(val document: String) : Request

/** A document nobody is attached to any more is closed. */
data class Detach(val document: String) : Request

data object ListDocuments : Request

/**
 * Change a document. [baseRevision] is the last revision the client
 * had seen when it made the change; [opId] comes back in the answer.
 */
data class Submit(val document: String, val opId: String, val baseRevision: Long, val patch: Patch) : Request

data class Undo(val document: String) : Request

data class Redo(val document: String) : Request

/**
 * Write the document to its file, or to [path] and make that its
 * file. With [copy] the document keeps its file and its dirty flag.
 */
data class Save(val document: String, val path: String? = null, val copy: Boolean = false) : Request

/** Replace the content with the file's; drops the undo history. */
data class Reload(val document: String) : Request

/** What the editor tells a client. */
sealed interface Event

data class DocumentInfo(val document: String, val file: String?, val title: String?, val dirty: Boolean)

/** Answer to [OpenFile], [CreateDocument] and [Attach]: the whole content. */
data class Attached(val info: DocumentInfo, val revision: Long, val state: GraphState) : Event

/**
 * The document changed and is now at [revision]. Sent to every
 * attached client, the one that caused it included: [origin] and
 * [opId] say whose [Submit] it was (no [opId] for undo, redo, reload).
 */
data class Patched(val document: String, val revision: Long, val patch: Patch, val origin: String, val opId: String?) : Event

data class Rejected(val document: String, val opId: String, val reason: String) : Event

/** File, title or dirty flag of a document changed. */
data class DocumentChanged(val info: DocumentInfo) : Event

data class Documents(val documents: List<DocumentInfo>) : Event

/** A request that is not a [Submit] failed. */
data class Failed(val request: Request, val reason: String) : Event
