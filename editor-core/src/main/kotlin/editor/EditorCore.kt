package editor

import DotGraphLoader
import parser_dot.ParseLog
import sync.*

/** An [Event] and the client it is for. */
data class Outgoing(val client: String, val event: Event)

/** Text files as the editor sees them; the host provides them. */
interface FileStore {
    fun read(path: String): String
    fun write(path: String, text: String)
}

/**
 * The authoritative state of a session: the open documents, their
 * undo histories and who is attached to them.
 *
 * Owns no thread. The host calls [handle] with one request at a
 * time and delivers the returned events; the order of the calls is
 * the order of the changes.
 *
 * Conflicts: a change is applied when everything it names still
 * exists. A change by a client other than the [owner] is rejected
 * when it touches something the owner changed after the revision
 * the change was based on. Changes to the same field otherwise
 * simply overwrite each other in the order they arrive.
 */
class EditorCore(private val files: FileStore) {
    private class HistoryEntry(var forward: Patch, val backward: Patch, val client: String, var time: Long)

    private class Change(val revision: Long, val client: String)

    private class Document(
        val id: String,
        var state: GraphState,
        var file: String?,
        val title: String?,
        /** Name in `digraph <name> {`, kept for saving. */
        var graphName: String?,
        /** Layout of the source file (comments, order), kept for saving. */
        var parseLog: ParseLog?,
    ) {
        var revision: Long = 0
        var dirty: Boolean = false
        val clients: MutableSet<String> = linkedSetOf()
        val undo: ArrayDeque<HistoryEntry> = ArrayDeque()
        val redo: ArrayDeque<HistoryEntry> = ArrayDeque()

        /** Per node / edge id: who changed it last. */
        val lastChange: MutableMap<String, Change> = mutableMapOf()

        val info: DocumentInfo get() = DocumentInfo(id, file, title, dirty)
    }

    private val documents: MutableMap<String, Document> = linkedMapOf()
    private val clients: MutableSet<String> = linkedSetOf()
    private var nextDocument = 0

    /** The client whose changes win conflicts: the first to connect. */
    var owner: String? = null
        private set

    fun connect(client: String) {
        clients.add(client)
        if (owner == null) owner = client
    }

    /** Detaches [client] from everything; documents left without clients are closed. */
    fun disconnect(client: String) {
        clients.remove(client)
        documents.values.toList().forEach { detach(it, client) }
    }

    /** Process [request] of [client] at time [now] (ms). */
    fun handle(client: String, request: Request, now: Long): List<Outgoing> {
        val result = runCatching {
            when (request) {
                is OpenFile -> attach(client, documents.values.find { it.file == request.path } ?: load(request.path))
                is CreateDocument -> attach(client, create(request.state, null, request.title, null, null))
                is Attach -> attach(client, document(request.document))
                is Detach -> detach(document(request.document), client).let { emptyList() }
                is ListDocuments -> listOf(Outgoing(client, Documents(documents.values.map { it.info })))
                is Submit -> submit(client, document(request.document), request, now)
                is Undo -> document(request.document).let { d -> step(client, d, d.undo, d.redo) { it.backward } }
                is Redo -> document(request.document).let { d -> step(client, d, d.redo, d.undo) { it.forward } }
                is Save -> save(document(request.document), request.path)
                is Reload -> reload(client, document(request.document))
            }
        }
        return result.getOrElse { listOf(Outgoing(client, Failed(request, it.message ?: it.toString()))) }
    }

    private fun document(id: String): Document = documents[id] ?: throw IllegalArgumentException("no such document: $id")

    private fun create(state: GraphState, file: String?, title: String?, graphName: String?, parseLog: ParseLog?): Document {
        val d = Document("d${nextDocument++}", state, file, title, graphName, parseLog)
        documents[d.id] = d
        return d
    }

    private fun parse(path: String): Triple<GraphState, String, ParseLog?> {
        val g = DotGraphLoader.parse(DotGraphLoader.tokenize(files.read(path)))
        return Triple(GraphState.of(g), g.id, g.parseLog)
    }

    private fun load(path: String): Document {
        val (state, name, log) = parse(path)
        return create(state, path, null, name, log)
    }

    private fun attach(client: String, d: Document): List<Outgoing> {
        d.clients.add(client)
        return listOf(Outgoing(client, Attached(d.info, d.revision, d.state)))
    }

    private fun detach(d: Document, client: String) {
        d.clients.remove(client)
        if (d.clients.isEmpty()) documents.remove(d.id)
    }

    private fun toAll(d: Document, event: Event): List<Outgoing> = d.clients.map { Outgoing(it, event) }

    private fun submit(client: String, d: Document, r: Submit, now: Long): List<Outgoing> {
        val overridden = (r.patch.touchedNodes + r.patch.setEdges.keys + r.patch.removeEdges).firstOrNull { id ->
            val last = d.lastChange[id]
            client != owner && last != null && last.client == owner && last.revision > r.baseRevision
        }
        val reason = d.state.conflict(r.patch) ?: overridden?.let { "$it was changed by the session owner" }
        if (reason != null) return listOf(Outgoing(client, Rejected(d.id, r.opId, reason)))

        val before = d.state
        val after = before.apply(r.patch)
        val forward = before.diff(after)
        val top = d.undo.lastOrNull()
        val merges = top != null && top.client == client && now - top.time <= COALESCE_MS && forward.setsSameFieldsAs(top.forward)
        if (merges) {
            top!!.forward = forward
            top.time = now
        } else if (!forward.isEmpty) {
            d.undo.addLast(HistoryEntry(forward, after.diff(before), client, now))
            while (d.undo.size > MAX_DEPTH) d.undo.removeFirst()
        }
        d.redo.clear()
        return changed(d, after, forward, client, r.opId)
    }

    /** Move the top of [from] to [to], applying the patch [pick] selects. */
    private fun step(client: String, d: Document, from: ArrayDeque<HistoryEntry>, to: ArrayDeque<HistoryEntry>, pick: (HistoryEntry) -> Patch): List<Outgoing> {
        val entry = from.removeLastOrNull() ?: return emptyList()
        to.addLast(entry)
        // the next change must not merge into an entry that was undone and redone
        entry.time = Long.MIN_VALUE / 2
        return changed(d, d.state.apply(pick(entry)), pick(entry), client, null)
    }

    private fun changed(d: Document, state: GraphState, patch: Patch, client: String, opId: String?): List<Outgoing> {
        val wasDirty = d.dirty
        d.state = state
        d.revision++
        d.dirty = d.dirty || !patch.isEmpty
        val ids = patch.addNodes.keys + patch.setNodes.keys + patch.removeNodes + patch.addEdges.keys + patch.setEdges.keys + patch.removeEdges
        ids.forEach { d.lastChange[it] = Change(d.revision, client) }
        val info = if (d.dirty != wasDirty) toAll(d, DocumentChanged(d.info)) else emptyList()
        return toAll(d, Patched(d.id, d.revision, patch, client, opId)) + info
    }

    private fun save(d: Document, path: String?): List<Outgoing> {
        val target = path ?: d.file ?: throw IllegalStateException("the document has no file")
        val g = d.state.toGraph()
        d.graphName?.let { g.id = it }
        g.parseLog = d.parseLog
        files.write(target, DotGraphLoader.serialize(g))
        d.file = target
        d.dirty = false
        return toAll(d, DocumentChanged(d.info))
    }

    private fun reload(client: String, d: Document): List<Outgoing> {
        val (state, name, log) = parse(d.file ?: throw IllegalStateException("the document has no file"))
        val patch = d.state.diff(state)
        d.graphName = name
        d.parseLog = log
        d.undo.clear()
        d.redo.clear()
        val patched = changed(d, state, patch, client, null).filter { it.event is Patched }
        d.dirty = false
        return patched + toAll(d, DocumentChanged(d.info))
    }

    companion object {
        /** Maximum number of undo steps kept per document. */
        const val MAX_DEPTH = 200

        /** Successive changes of the same fields by one client within this time are one undo step (typing, nudging). */
        const val COALESCE_MS = 1000L
    }
}
