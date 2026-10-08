package client

import Graph
import graph_tools.Command
import graph_tools.Committer
import sync.*

/**
 * A client's line to the editor (conn2). Events arrive on the
 * client's own thread, one at a time, in the editor's order.
 */
interface EditorConnection {
    /** This client's id in the session; `origin` of its own changes. */
    val clientId: String

    /** Receives every event that is not the answer of a [call]. */
    var onEvent: (Event) -> Unit

    fun send(request: Request)

    /** Send [request] and wait for the editor's answer to it; null when it has none. */
    fun call(request: Request): Event?

    fun close()
}

class EditorException(message: String) : Exception(message)

/** The documents this client has open, as [DocumentSession]s. */
class EditorClient(private val connection: EditorConnection) {
    private val sessions: MutableMap<String, DocumentSession> = linkedMapOf()
    private var nextOp = 0L

    val id: String get() = connection.clientId

    /** Told about failed requests that had nobody waiting for the answer. */
    var onFailure: (String) -> Unit = {}

    init {
        connection.onEvent = ::dispatch
    }

    /** Open the file at [path] (an absolute path), sharing the document with the clients that already have it open. */
    fun open(path: String): DocumentSession = attach(OpenFile(path))

    /** A new document without a file, starting with the content of [graph]; [graph] itself becomes the replica. */
    fun create(graph: Graph, title: String? = null): DocumentSession = attach(CreateDocument(GraphState.of(graph), title), graph)

    fun attach(document: String): DocumentSession = attach(Attach(document))

    fun documents(): List<DocumentInfo> = (connection.call(ListDocuments) as? Documents)?.documents.orEmpty()

    fun close() {
        connection.close()
    }

    /**
     * The editor is gone. Every open document's graph stays with its
     * view as an ordinary graph with a history of its own, so the
     * work in it can still be saved.
     */
    fun connectionLost() {
        val lost = sessions.values.toList()
        sessions.clear()
        lost.forEach {
            it.graph.committer = null
            it.onLost()
        }
    }

    private fun attach(request: Request, replica: Graph? = null): DocumentSession {
        val answer = connection.call(request)
        val attached = answer as? Attached ?: throw EditorException((answer as? Failed)?.reason ?: "no answer from the editor")
        val session = sessions.getOrPut(attached.info.document) { DocumentSession(this, attached, replica ?: attached.state.toGraph()) }
        session.attachCount++
        return session
    }

    internal fun send(request: Request) = connection.send(request)

    internal fun call(request: Request): Event? = connection.call(request)

    internal fun newOpId(): String = "${id}:${nextOp++}"

    internal fun forget(session: DocumentSession) {
        sessions.remove(session.info.document)
    }

    private fun dispatch(event: Event) {
        when (event) {
            is Patched -> sessions[event.document]?.patched(event)
            is Rejected -> sessions[event.document]?.rejected(event)
            is Attached -> sessions[event.info.document]?.resynced(event)
            is DocumentChanged -> sessions[event.info.document]?.infoChanged(event.info)
            is Failed -> onFailure(event.reason)
            is Documents -> {}
        }
    }
}

/**
 * One open document: a replica [graph] kept equal to the editor's
 * copy. Local changes are applied to the replica at once and sent
 * as patches; the editor's patches, this client's own included,
 * are applied as they arrive.
 */
class DocumentSession internal constructor(private val client: EditorClient, attached: Attached, val graph: Graph) : Committer {
    init {
        graph.committer = this
    }

    var info: DocumentInfo = attached.info
        private set

    /** Last revision of the editor's copy that the replica includes. */
    var revision: Long = attached.revision
        private set

    /** How many times this client opened the document; it is detached when all are closed. */
    internal var attachCount = 0

    /** Sent but not yet answered, oldest first. */
    private val pending: ArrayDeque<Pair<String, Patch>> = ArrayDeque()

    /** The replica changed; `local` when this client's own action did it. */
    var onChange: (local: Boolean) -> Unit = {}

    /** File, title or dirty flag changed. */
    var onInfo: () -> Unit = {}

    /** The editor refused a change of ours; it has been taken back. */
    var onRejected: (reason: String) -> Unit = {}

    /** The editor is gone; [graph] is no longer anybody's replica. */
    var onLost: () -> Unit = {}

    /** The editor's copy as far as this client has seen it. */
    private var base: GraphState = attached.state

    override fun commit(cmd: Command, applied: Boolean) {
        if (applied) cmd.undo()
        val before = GraphState.of(graph)
        cmd.redo()
        val after = GraphState.of(graph)
        val patch = before.diff(after)
        // a command for something its caller already did to the graph changes nothing by running
        submit(if (patch.isEmpty && !applied) effectOf(cmd, after) else patch)
    }

    private fun effectOf(cmd: Command, after: GraphState): Patch {
        cmd.undo()
        val before = GraphState.of(graph)
        cmd.redo()
        return before.diff(after)
    }

    /**
     * Send what the replica has that the editor does not: changes
     * made without a [Command], such as nodes placed by the layout.
     * For before saving.
     */
    fun flush() {
        submit(pending.fold(base) { s, p -> s.apply(p.second) }.diff(GraphState.of(graph)))
    }

    private fun submit(patch: Patch) {
        if (!patch.isEmpty) {
            val opId = client.newOpId()
            pending.addLast(opId to patch)
            onChange(true)
            client.send(Submit(info.document, opId, revision, patch))
        }
    }

    override fun undo() = client.send(Undo(info.document))

    override fun redo() = client.send(Redo(info.document))

    /** Save to the document's file, or to [path]. Throws [EditorException] when it fails. */
    fun save(path: String? = null) = save(path, copy = false)

    /** Write the document to [path]; it keeps its own file and stays as dirty as it was. */
    fun saveCopy(path: String) = save(path, copy = true)

    private fun save(path: String?, copy: Boolean) {
        flush()
        val answer = client.call(Save(info.document, path, copy))
        (answer as? Failed)?.let { throw EditorException(it.reason) }
        (answer as? DocumentChanged)?.let { infoChanged(it.info) }
    }

    /** Replace the content with the file's. */
    fun reload() = client.send(Reload(info.document))

    fun close() {
        attachCount--
        if (attachCount == 0) {
            client.forget(this)
            client.send(Detach(info.document))
        }
    }

    internal fun patched(e: Patched) {
        if (e.revision > revision) {
            val own = e.origin == client.id && e.opId != null
            if (own && pending.firstOrNull()?.first == e.opId) pending.removeFirst()
            revision = e.revision
            base = base.apply(e.patch)
            graph.applyPatch(e.patch)
            // ours are later in the editor's order, so they go on top
            pending.forEach { graph.applyPatch(it.second) }
            onChange(own)
        }
    }

    internal fun rejected(e: Rejected) {
        pending.removeAll { it.first == e.opId }
        // the refused change is still in the replica: take the editor's copy
        client.send(Attach(info.document))
        onRejected(e.reason)
    }

    internal fun resynced(a: Attached) {
        revision = a.revision
        info = a.info
        base = a.state
        graph.applyPatch(GraphState.of(graph).diff(a.state))
        pending.forEach { graph.applyPatch(it.second) }
        onChange(false)
        onInfo()
    }

    internal fun infoChanged(i: DocumentInfo) {
        info = i
        onInfo()
    }
}
