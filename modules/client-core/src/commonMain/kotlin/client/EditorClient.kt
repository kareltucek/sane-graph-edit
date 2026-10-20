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

    /**
     * Send [request] and wait for the editor's answer to it; null
     * when it has none. Not on every connection: JavaScript cannot
     * wait, and has only [request].
     */
    fun call(request: Request): Event?

    /** Send [request]; the editor's answer to it (null when it has none) goes to [onAnswer], as events do. */
    fun request(request: Request, onAnswer: (Event?) -> Unit) = onAnswer(call(request))

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

    /** Told when somebody asks this client to show a file to its user ([Show]). */
    var onShow: (path: String, here: Boolean) -> Unit = { _, _ -> }

    init {
        connection.onEvent = ::dispatch
    }

    /** Opened but not answered yet, by path: a second opening of the same file meanwhile shares the first one's session. */
    private val opening: MutableMap<String, DocumentSession> = mutableMapOf()

    /**
     * Open the file at [path] (an absolute path), sharing the document
     * with the clients that already have it open.
     *
     * A connection that cannot wait for the editor gives a session
     * that is empty at first ([DocumentSession.isAttached] false) and
     * fills in when the editor answers; that the file cannot be opened
     * is then told to [DocumentSession.onFailed]. Otherwise it throws.
     */
    fun open(path: String): DocumentSession =
        (opening[path] ?: sessions.values.find { it.info.file == path })?.also { it.attachCount++ }
            ?: begin(OpenFile(path), null, DocumentInfo("", path, null, dirty = false, clients = 1), path)

    /** [open] without waiting: [then] gets the document, or the reason there is none. */
    fun open(path: String, then: (Result<DocumentSession>) -> Unit) =
        connection.request(OpenFile(path)) { answer -> then(runCatching { attached(answer, null) }) }

    /**
     * A new document without a file, starting with the content of
     * [graph]; [graph] itself becomes the replica. On a connection
     * that cannot wait, what is done to it before the editor answers
     * is sent then.
     */
    fun create(graph: Graph, title: String? = null): DocumentSession =
        begin(CreateDocument(GraphState.of(graph), title), graph, DocumentInfo("", null, title, dirty = false, clients = 1), null)

    /**
     * Send [request] for a document. If the editor has answered by
     * the time it is sent, that is the session (or the failure);
     * if not, a session as described by [provisional] that
     * [arrived] completes.
     */
    private fun begin(request: Request, replica: Graph?, provisional: DocumentInfo, path: String?): DocumentSession {
        var answered = false
        var answer: Event? = null
        var late: DocumentSession? = null
        connection.request(request) { a ->
            answered = true
            answer = a
            late?.let { arrived(it, a, path) }
        }
        return if (answered) attached(answer, replica) else {
            val graph = replica ?: Graph()
            DocumentSession(this, Attached(provisional, 0, GraphState.of(graph)), graph, isAttached = false, ownContent = replica != null).also {
                it.attachCount++
                late = it
                if (path != null) opening[path] = it
            }
        }
    }

    /** The editor's late answer for [session]. */
    private fun arrived(session: DocumentSession, answer: Event?, path: String?) {
        if (path != null) opening.remove(path)
        val attached = answer as? Attached
        val abandoned = session.attachCount == 0
        when {
            attached == null -> session.failed((answer as? Failed)?.reason ?: "no answer from the editor")
            // closed again before the editor answered
            abandoned -> connection.send(Detach(attached.info.document))
            attached.info.document in sessions -> session.failed("the document is already open here under another name")
            else -> {
                sessions[attached.info.document] = session
                session.attach(attached)
            }
        }
    }

    /**
     * Documents with unsaved changes that no view shows: a view left
     * them for another graph (a link followed, `Ctrl+O`), and going
     * back finds them as they were. Being here keeps them open; the
     * user is asked about them when the client ends.
     */
    private val kept: MutableList<DocumentSession> = mutableListOf()

    /** A view leaves [session], which has unsaved changes: it stays open here. */
    fun keep(session: DocumentSession) {
        if (session in kept) session.close() else kept += session
    }

    /** A view shows [session] again, opened anew: it need not be kept. */
    fun shown(session: DocumentSession) {
        if (kept.remove(session)) session.close()
    }

    fun isKept(document: String): Boolean = kept.any { it.info.document == document }

    /** The kept document [document], for a view to show again; it is the view's then. */
    fun take(document: String): DocumentSession? = kept.find { it.info.document == document }?.also { kept.remove(it) }

    /** The kept documents that have unsaved changes. */
    val unsavedKept: List<DocumentSession> get() = kept.filter { it.info.dirty }

    /** Close the kept documents: what is unsaved in them is lost. */
    fun dropKept() {
        val all = kept.toList()
        kept.clear()
        all.forEach { it.close() }
    }

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

    private fun attach(request: Request, replica: Graph? = null): DocumentSession = attached(connection.call(request), replica)

    private fun attached(answer: Event?, replica: Graph?): DocumentSession {
        val attached = answer as? Attached ?: throw EditorException((answer as? Failed)?.reason ?: "no answer from the editor")
        val session = sessions.getOrPut(attached.info.document) { DocumentSession(this, attached, replica ?: attached.state.toGraph()) }
        session.attachCount++
        return session
    }

    internal fun send(request: Request) = connection.send(request)

    internal fun call(request: Request): Event? = connection.call(request)

    internal fun request(request: Request, onAnswer: (Event?) -> Unit) = connection.request(request, onAnswer)

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
            is ShowRequested -> onShow(event.path, event.here)
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
class DocumentSession internal constructor(
    private val client: EditorClient,
    attached: Attached,
    val graph: Graph,
    isAttached: Boolean = true,
    /** While not attached: the replica holds the document's content already (a new document), it does not wait for it. */
    private val ownContent: Boolean = false,
) : Committer {
    init {
        graph.committer = this
    }

    /**
     * False while the editor has not answered the opening yet: the
     * replica is not the document's content, nothing is sent, and
     * [info] is a guess. Becomes true with [onAttached].
     */
    var isAttached: Boolean = isAttached
        private set

    /** The editor answered: the replica is the document now. */
    var onAttached: () -> Unit = {}

    /** The editor could not open or create the document; [graph] stays as an ordinary graph. */
    var onFailed: (reason: String) -> Unit = { onLost() }

    internal fun attach(a: Attached) {
        isAttached = true
        revision = a.revision
        info = a.info
        base = a.state
        // a new document's content is ours, with what was done to it meanwhile; an opened one's is the editor's
        if (ownContent) flush() else graph.applyPatch(GraphState.of(graph).diff(a.state))
        onChange(false)
        onInfo()
        onAttached()
    }

    internal fun failed(reason: String) {
        graph.committer = null
        onFailed(reason)
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

    override fun commitDerived(cmd: Command) {
        val before = GraphState.of(graph)
        cmd.redo()
        submit(before.diff(GraphState.of(graph)), derived = true)
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

    private fun submit(patch: Patch, derived: Boolean = false) {
        when {
            patch.isEmpty -> {}
            // it stays in the replica, and goes out when the editor has answered
            !isAttached -> onChange(true)
            else -> {
                val opId = client.newOpId()
                pending.addLast(opId to patch)
                onChange(true)
                client.send(Submit(info.document, opId, revision, patch, derived))
            }
        }
    }

    override fun undo() {
        if (isAttached) client.send(Undo(info.document))
    }

    override fun redo() {
        if (isAttached) client.send(Redo(info.document))
    }

    /** Save to the document's file, or to [path]. Throws [EditorException] when it fails. */
    fun save(path: String? = null) = save(path, copy = false)

    /** Write the document to [path]; it keeps its own file and stays as dirty as it was. */
    fun saveCopy(path: String) = save(path, copy = true)

    /** [save] (or with [copy], [saveCopy]) without waiting: [then] is told when it is done, or why it failed. */
    fun save(path: String? = null, copy: Boolean = false, then: (Result<Unit>) -> Unit) {
        if (!isAttached) {
            then(Result.failure(EditorException("the document is not open yet")))
        } else {
            flush()
            // a connection that waits may give up waiting
            try {
                client.request(Save(info.document, path, copy)) { answer -> then(runCatching { saved(answer) }) }
            } catch (e: EditorException) {
                then(Result.failure(e))
            }
        }
    }

    private fun save(path: String?, copy: Boolean) {
        flush()
        saved(client.call(Save(info.document, path, copy)))
    }

    private fun saved(answer: Event?) {
        (answer as? Failed)?.let { throw EditorException(it.reason) }
        (answer as? DocumentChanged)?.let { infoChanged(it.info) }
    }

    /** Replace the content with the file's. */
    fun reload() {
        if (isAttached) client.send(Reload(info.document))
    }

    fun close() {
        attachCount--
        // one that is not attached yet is detached when the editor's answer arrives
        if (attachCount == 0 && isAttached) {
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
