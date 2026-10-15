package client

import DotGraphLoader
import Graph
import export.SvgWriter
import graph_tools.Node
import graph_tools.ReplaceContentCommand
import graph_tools.ViewModes
import utils.Vector2

/**
 * What a view shows and where it came from: a graph file, the
 * tree of a directory ([FileTree]), lines of a source file
 * ([SourceView]), or a graph without a file. Opening, saving,
 * following links and the jump list change it. Questions to the
 * user and other tabs go through the [Shell].
 */
class ViewContent(private val view: EditorView, private val shell: Shell, private val client: EditorClient?) {
    /**
     * The shared document the view's graph is a replica of, when
     * there is an editor session. Null without one (unit tests):
     * the view then edits a graph of its own.
     */
    var document: DocumentSession? = null
        private set

    /** The file the graph was loaded from or last saved to; null for "untitled". */
    var currentFile: Path? = null
        private set

    /** True if the graph has been changed since the last save or load. */
    var isDirty: Boolean = false
        private set

    private fun dirty(value: Boolean) {
        if (isDirty != value) {
            isDirty = value
            shell.stateChanged()
        }
    }

    /** Title while there is no [currentFile] (e.g. `browse: /home`). */
    var untitledName: String? = null

    /** Directory shown when the view is a [FileTree] (`:browse`, `gf` on a directory). */
    var browseDir: Path? = null

    /** What of which text file is shown when the view is a [SourceView] (a link to one). */
    var sourceView: FileLink.Target? = null

    /** The view was opened to edit its [SourceView] node; that starts once the view is painted. */
    var pendingEdit: Boolean = false

    /** Do what waited for the view to be painted with a size: [pendingFocus], [pendingEdit]. */
    fun painted() {
        pendingFocus?.let {
            pendingFocus = null
            focusNode(it)
        }
        if (pendingEdit) {
            pendingEdit = false
            view.g.nodes.firstOrNull { it.attributes.name == SourceView.NODE_ID }?.let { view.startNodeEdit(it, null) }
        }
    }


    /** Whether [target] is a text file, or part of one, to show as a [SourceView]: not a graph, not a directory. */
    private fun isText(target: FileLink.Target): Boolean {
        val path = target.path
        val whole = path != null && target.nodeId == null && target.section == null && !path.name.endsWith(".dot") && Fs.exists(path) && !Fs.isDirectory(path)
        val told = target.type == FileLink.Type.File && path != null && (target.lines != null || whole)
        return told || (target.type is FileLink.Type.Text && path != null)
    }

    /** What is wrong with following [target] when it is a directory or workspace that is not a directory; null when nothing is. */
    private fun notADirectory(target: FileLink.Target): String? =
        target.path?.takeIf { (target.type == FileLink.Type.Directory || target.type == FileLink.Type.Workspace) && !Fs.isDirectory(it) }
            ?.let { "not a directory: $it" }

    /** This view's jump history (`Ctrl+O` / `Ctrl+I`). */
    var jumps: JumpList = JumpList()

    /** DOT id of the node to focus once the view has a size (a link opened this view). */
    var pendingFocus: String? = null

    /** The file's name, or what else is shown, with `*` in front while there are unsaved changes. */
    val title: String
        get() {
            val base = currentFile?.name ?: untitledName ?: "(untitled)"
            return if (isDirty) "*$base" else base
        }

    /** The graph the view starts with: the document of [file], or a new one holding [graph]. */
    internal fun start(graph: Graph, file: Path?): Graph {
        val g = adopt(graph, file)
        currentFile = file
        watch(g)
        // no notice to the shell: it is still being built
        isDirty = document?.info?.dirty ?: false
        return g
    }

    /**
     * Make the document of [file] — or, without one, a new document
     * holding [graph] — the one this view shows, and return the graph
     * to edit.
     */
    private fun adopt(graph: Graph, file: Path?): Graph {
        val doc = client?.let { c ->
            if (file != null) c.open(file.toAbsolutePath().normalize().toString()) else c.create(graph)
        }
        // only now: opening may have failed, and then the view keeps what it had
        document?.close()
        document = doc
        // our own changes repaint where they are made; these are other clients', undo and reload
        doc?.onChange = { local ->
            if (watching) FileContent.reconcile(view)
            if (!local) view.repaint()
        }
        doc?.onInfo = {
            dirty(doc.info.dirty)
            view.refreshStatus()
        }
        doc?.onRejected = { reason -> shell.message("change refused: $reason") }
        doc?.onLost = { document = null }
        doc?.onAttached = { settled() }
        doc?.onFailed = { reason ->
            // only an editor that answers later gets here; one that answers at once has thrown in [EditorClient.open]
            document = null
            whenLoaded.clear()
            val what = currentFile
            currentFile = null
            shell.alert("Open error", "Could not open ${what ?: "the document"}:\n$reason")
            shell.stateChanged()
            settled()
        }
        return doc?.graph ?: graph
    }

    // --- an editor that answers later ---

    /** Saves the editor has not answered yet. */
    private var saving = 0

    /**
     * True while the view waits for the editor: its document's
     * content has not arrived, or a save is not confirmed. What the
     * user does next is held back until then ([onIdle]), as it
     * assumes the step before it is done.
     */
    val isBusy: Boolean get() = document?.isAttached == false || saving > 0

    /** Told when [isBusy] ends. */
    var onIdle: () -> Unit = {}

    /** What to do with the content once it is there. */
    private val whenLoaded = mutableListOf<() -> Unit>()

    /** Do [action] now, or when the document's content has arrived: for what looks at the content right after opening. */
    fun onceLoaded(action: () -> Unit) {
        if (document?.isAttached == false) whenLoaded += action else action()
    }

    private fun settled() {
        if (document?.isAttached != false) {
            val actions = whenLoaded.toList()
            whenLoaded.clear()
            actions.forEach { it() }
        }
        if (!isBusy) onIdle()
    }

    /**
     * Any change makes the graph "not the saved file any more". An
     * undo back to the saved state is not detected.
     */
    private fun watch(g: Graph) {
        g.history.addListener {
            dirty(true)
            if (watching && view.g === g) FileContent.reconcile(view)
        }
    }

    /** The view is built: its graph's changes are followed ([FileContent]). */
    var watching = false

    /** Show [newGraph] (or the document of [file]) instead of the current graph. */
    private fun replaceGraph(newGraph: Graph, file: Path? = null) {
        FileContent.left(view)
        view.g = adopt(newGraph, file)
        // a graph with depth in it is shown with it
        onceLoaded { view.showDepth() }
        watch(view.g)
        // a file somebody else has open may come with their unsaved changes
        dirty(document?.info?.dirty ?: false)
        shell.stateChanged()
    }

    /** Leave the document; for when the view goes away. */
    fun close() {
        document?.close()
        document = null
    }

    // --- saving ---

    /**
     * Save to [currentFile], asking for a file if there is none.
     * [then] is told whether it was saved: before this returns when
     * the editor answers at once, later when it does not.
     */
    fun save(then: (Boolean) -> Unit = {}) {
        // the texts kept in other files first: the lines they are in afterwards are part of the graph that is saved
        val texts = FileContent.save(view)
        when {
            !texts -> then(false)
            // a view of a text file holds nothing but that text
            sourceView != null -> {
                dirty(false)
                then(true)
            }
            else -> currentFile?.let { writeTo(it, then) } ?: saveAs(then)
        }
    }

    /**
     * [save] for a caller that must know at once (a question to the
     * user that waits for it): true only if it was saved before this
     * returns, so false too while an editor that answers later has
     * not answered.
     */
    fun saveNow(): Boolean {
        var saved = false
        save { saved = it }
        return saved
    }

    /** Ask for a file and save to it; [then] as for [save]. */
    fun saveAs(then: (Boolean) -> Unit = {}) {
        shell.chooseSave(currentFile?.name ?: "untitled.dot")?.let { writeTo(it, then) } ?: then(false)
    }

    /** Write the graph to [path] without making it the view's file; [then] is told when it is done, or why it failed. */
    fun saveCopy(path: Path, then: (Result<Unit>) -> Unit = {}) {
        val doc = document
        if (doc != null) doc.save(path.toString(), copy = true, then = then) else then(runCatching { DotGraphLoader.saveToFile(view.g, path.toString()) })
    }

    private fun writeTo(path: Path, then: (Boolean) -> Unit) {
        val done: (Result<Unit>) -> Unit = { result ->
            saving--
            result.fold(
                onSuccess = {
                    currentFile = path
                    dirty(false)
                    shell.stateChanged()
                    // the first save of an untitled view, or a save under a new name
                    shell.sessionChanged()
                },
                onFailure = { shell.alert("Save error", "Could not save to $path:\n${it.message}") },
            )
            then(result.isSuccess)
            settled()
        }
        saving++
        val doc = document
        if (doc != null) doc.save(path.toAbsolutePath().normalize().toString(), then = done) else done(runCatching { DotGraphLoader.saveToFile(view.g, path.toString()) })
    }

    /** SVG of what is visible, to a file the user chooses. */
    fun exportSvg() {
        val name = currentFile?.name?.removeSuffix(".dot")?.plus(".svg") ?: "untitled.svg"
        // hidden nodes stay hidden in the SVG
        shell.chooseExport(name)?.let { writeExport(it, SvgWriter.write(view.g, view.g.visibleNodes())) }
    }

    /** SVG of the selected nodes and the edges between them; of everything when nothing is selected. */
    fun exportSelection() {
        val selection = view.g.selectedNodes.takeIf { it.isNotEmpty() }
        val name = currentFile?.name?.removeSuffix(".dot")?.plus("-selection.svg") ?: "selection.svg"
        shell.chooseExport(name)?.let { writeExport(it, SvgWriter.write(view.g, selection)) }
    }

    private fun writeExport(path: Path, content: String) {
        try {
            Fs.writeText(path, content)
        } catch (t: Throwable) {
            shell.alert("Export error", "Could not export to $path:\n${t.message}")
        }
    }

    // --- opening ---

    /** Ask for a file or directory and show it in this view. */
    fun open() {
        shell.chooseOpen()
            ?.takeIf { confirmDiscard("open ${it.name}") }
            ?.let { path ->
                val from = currentLocation()
                if (Fs.isDirectory(path)) {
                    jumps.record(from)
                    browseInPlace(path)
                } else if (loadInPlace(path)) {
                    jumps.record(from)
                }
            }
    }

    /** Replace the graph with the one in [path]. True if the file was loaded. */
    private fun loadInPlace(path: Path): Boolean =
        try {
            // with an editor the content is its document's; reading the file here too would only be thrown away
            replaceGraph(if (client == null) DotGraphLoader.loadFromFile(path.toString()) else Graph(), path)
            currentFile = path
            untitledName = null
            browseDir = null
            sourceView = null
            shell.stateChanged()
            onceLoaded {
                FileContent.reconcile(view)
                view.boundScreen()
                view.repaint()
            }
            shell.sessionChanged()
            AppState.lastOpenDir = path.toAbsolutePath().getParent()
            true
        } catch (t: Throwable) {
            shell.alert("Open error", "Could not open $path:\n${t.message}")
            false
        }

    /**
     * Re-read [currentFile], e.g. after an external tool regenerated
     * it or another editor saved it. The reload is a step in the undo
     * history: undo brings back the graph as it was, unsaved changes
     * included. A changed graph still asks first unless [force] is
     * set (`:e!`). The view keeps looking at the same spot.
     */
    fun reload(force: Boolean = false) {
        val path = currentFile
        if (path == null) {
            shell.alert("Reload", "This tab has no file to reload.", error = false)
        } else if (force || confirmDiscard("reload ${path.name}")) {
            try {
                // with a document everyone attached gets the file's content; a failure comes back as a message
                // the nodes whose text is kept in files take it from the files' buffers again: what is unsaved
                // there stays, it is not this graph's alone. `:e!` gives that up too.
                FileContent.forget(view)
                document?.reload() ?: reloadOwn(path)
                onceLoaded { if (force) FileContent.reload(view) else FileContent.reconcile(view) }
                view.repaint()
            } catch (t: Throwable) {
                shell.alert("Reload error", "Could not reload $path:\n${t.message}")
            }
        }
    }

    // one more step in the graph's own history: undo brings back what the reload replaced
    private fun reloadOwn(path: Path) {
        val g = view.g
        g.commit(ReplaceContentCommand(g, g.content(), DotGraphLoader.loadFromFile(path.toString()).content()))
        dirty(false)
    }

    /** What the view shows: the file's full path, the browsed directory, the source lines, or its name. */
    val filePath: String
        get() {
            val shown = sourceView?.let { "${it.path?.getParent()}/${SourceView.title(it)}" }
                ?: currentFile?.toAbsolutePath()?.normalize()?.toString()
                ?: browseDir?.toString()
                ?: untitledName
                ?: "(untitled)"
            return if (isDirty) "$shown [modified]" else shown
        }

    /** `:file` — vim's: show [filePath]. */
    fun showFilePath() = shell.message(filePath)

    /** True when there are no unsaved changes, the user agrees to lose them to [action], or `:set discard` says not to ask. */
    private fun confirmDiscard(action: String): Boolean =
        !isDirty || ViewModes.discard || shell.confirm("Unsaved changes", "Discard unsaved changes and $action?")

    /** An empty graph without a file (`Ctrl+N`, closing the last tab). */
    fun clearToEmpty() {
        replaceGraph(Graph())
        currentFile = null
        shell.stateChanged()
        view.repaint()
    }

    /** Replace the graph with the [FileTree] of [dir], cut to fit the view; it is laid out around the middle of the view. */
    fun browseInPlace(dir: Path) {
        replaceGraph(FileTree.build(dir, view.size.takeIf { it.x > 0 && it.y > 0 } ?: FileTree.DEFAULT_SCREEN))
        currentFile = null
        browseDir = dir.toAbsolutePath().normalize()
        sourceView = null
        untitledName = "browse: $browseDir"
        shell.stateChanged()
        view.setViewTo(Vector2.Zero)
        shell.sessionChanged()
    }

    /**
     * Replace the graph with a [SourceView] of [target], its node
     * selected, and with [edit] start editing it once it is painted.
     * False (and a message) if the file can't be read.
     */
    fun showSourceInPlace(target: FileLink.Target, edit: Boolean = true): Boolean {
        val graph = try {
            SourceView.build(target)
        } catch (e: Exception) {
            shell.message("cannot read ${target.path}: ${e.message}")
            null
        }
        if (graph != null) {
            replaceGraph(graph)
            currentFile = null
            browseDir = null
            sourceView = target
            untitledName = SourceView.title(target)
            graph.nodes.firstOrNull { it.attributes.name == SourceView.NODE_ID }?.let { graph.cleanSelect(setOf(it)) }
            pendingEdit = edit
            // remembers what the file holds: an edit is told from it
            FileContent.reconcile(view)
            dirty(false)
            shell.stateChanged()
            if (view.size.x > 0) view.setViewTo(startOf(graph), view.viewTransform.scale)
            shell.sessionChanged()
        }
        return graph != null
    }

    /**
     * Where to look at the lines of a [SourceView]: at their middle,
     * and along a side they are longer than the view, at where they
     * start, so that a long text is read from its first line.
     */
    private fun startOf(source: Graph): Vector2 {
        source.ensureCachesFresh()
        val node = source.nodes.first { it.attributes.name == SourceView.NODE_ID }
        val half = node.cache.shapeBounds / 2
        val room = view.size / view.viewTransform.scale / 2
        val along = { middle: Double, halfNode: Double, halfRoom: Double -> if (halfNode > halfRoom) middle - halfNode + halfRoom * 0.95 else middle }
        return Vector2(along(node.position.x, half.x, room.x), along(node.position.y, half.y, room.y))
    }

    /** Replace the graph with the built-in help graph (the `help` node of a [FileTree]). */
    /** Replace the graph with the [Workspaces] graph, as an untitled one. */
    fun showWorkspacesInPlace() {
        replaceGraph(Workspaces.build())
        currentFile = null
        browseDir = null
        sourceView = null
        untitledName = Workspaces.TITLE
        shell.stateChanged()
        if (view.size.x > 0) view.setViewTo(Vector2.Zero, view.viewTransform.scale)
        shell.sessionChanged()
    }

    fun showHelpInPlace() {
        replaceGraph(Graph.defaultGraph())
        currentFile = null
        browseDir = null
        sourceView = null
        untitledName = FileLink.HELP_TITLE
        shell.stateChanged()
        if (view.size.x > 0) view.setViewTo(Vector2.Zero, view.viewTransform.scale)
        shell.sessionChanged()
    }

    // --- links ---

    /**
     * `gf`: follow the selected node's `link` attribute ([FileLink])
     * in this view — a file is opened, a directory is shown as a
     * [FileTree], so repeated `gf` walks the filesystem. Needs
     * exactly one selected node. True if there was a link.
     */
    fun followFileLink(letter: Char = 'f', quiet: Boolean = false): Boolean {
        val target = selectedLink("follow-file-link", letter, quiet)
        target?.let { openTarget(it) }
        return target != null
    }

    /** `gF`: like [followFileLink], but into a new tab, which inherits this view's jump list. */
    fun followFileLinkInTab(letter: Char = 'f') {
        selectedLink("follow-file-link-in-tab", letter, quiet = false)?.let { openTargetInTab(it) }
    }

    /**
     * The resolved `link-<letter>` of the single selected node, or
     * null after telling the user why there is none ([command] names
     * the caller; [quiet] skips the telling).
     */
    private fun selectedLink(command: String, letter: Char, quiet: Boolean): FileLink.Target? {
        val node = view.g.selectedNodes.singleOrNull()
        val link = node?.let { FileLink.of(it, letter) }
        val typeName = node?.let { FileLink.typeOf(it, letter) }
        val type = FileLink.Type.parse(typeName)
        val problem = when {
            node == null -> "select exactly one node"
            link == null -> "the node has no `${FileLink.attributeName(letter)}` attribute"
            type == null -> "`${FileLink.typeAttributeName(letter)}` is not a type a link can have: $typeName"
            else -> null
        }
        if (problem != null && !quiet) shell.message("$command: $problem")
        return if (problem == null && link != null && type != null) FileLink.resolve(link, currentFile, type) else null
    }

    /**
     * Show [target] in this view (link following, the picker): a file
     * is loaded, a directory browsed, the node focused. Recorded in the
     * [jumps]; asks first about unsaved changes.
     */
    fun openTarget(target: FileLink.Target) {
        val path = target.path
        val lines = target.lines
        val inThisGraph = lines == null && (path == null || path == currentFile?.toAbsolutePath()?.normalize())
        val from = currentLocation()
        val wrong = notADirectory(target)
        when {
            wrong != null -> shell.message(wrong)
            // as `:cd` does: the tabs are the workspace's, whatever this one held is kept by its session
            target.type == FileLink.Type.Workspace && path != null -> shell.switchWorkspace(path)
            target.help -> if (confirmDiscard("open the help")) {
                jumps.record(from)
                showHelpInPlace()
            }
            target.section != null && lines == null -> shell.message("no section `${target.section}` in ${path?.name}")
            // the text is only shown: editing it starts as anywhere else (a click, Space), or keys meant for the graph,
            // as Ctrl+O back, would go to the editor
            isText(target) -> if (confirmDiscard("open ${SourceView.title(target)}")) {
                if (showSourceInPlace(target, edit = false)) jumps.record(from)
            }
            inThisGraph -> if (target.nodeId != null) {
                jumps.record(from)
                focusNode(target.nodeId)
            }
            path == null || !confirmDiscard("open ${path.getFileName() ?: path}") -> Unit
            Fs.isDirectory(path) -> {
                jumps.record(from)
                browseInPlace(path)
            }
            loadInPlace(path) -> {
                jumps.record(from)
                onceLoaded { focusNode(target.nodeId) }
            }
        }
    }

    /** Like [openTarget], into a new tab that inherits this view's jump list. */
    fun openTargetInTab(target: FileLink.Target) {
        val path = target.path
        val lines = target.lines
        val wrong = notADirectory(target)
        val opened = when {
            wrong != null -> {
                shell.message(wrong)
                null
            }
            // a workspace is not opened in a tab: the tabs become its
            target.type == FileLink.Type.Workspace && path != null -> {
                shell.switchWorkspace(path)
                null
            }
            // a new tab starts with the help graph
            target.help -> shell.newTab()?.also {
                it.content.untitledName = FileLink.HELP_TITLE
                it.shell.stateChanged()
            }
            path == null -> {
                jumps.record(currentLocation())
                focusNode(target.nodeId)
                null
            }
            target.section != null && lines == null -> {
                shell.message("no section `${target.section}` in ${path.name}")
                null
            }
            isText(target) -> shell.newTab()?.takeIf { it.content.showSourceInPlace(target, edit = false) }
            Fs.isDirectory(path) -> shell.browse(path)
            // the new tab has no size yet; its first paint does the focusing
            else -> editInTab(path)?.also { it.content.pendingFocus = target.nodeId }
        }
        opened?.content?.jumps = jumps.copy().also { it.record(currentLocation()) }
    }

    private fun editInTab(path: Path): EditorView? =
        try {
            shell.edit(path)
        } catch (t: Throwable) {
            shell.alert("Open error", "Could not open $path:\n${t.message}")
            null
        }

    /** Select the node whose DOT id is [nodeId] and centre the view on it, keeping the zoom. A message if there is none. */
    fun focusNode(nodeId: String?) {
        val node = nodeId?.let { id -> view.g.nodes.find { it.attributes.name == id } }
        when {
            nodeId == null -> Unit
            node == null -> shell.message("no node `$nodeId` in ${currentFile?.getFileName() ?: "this graph"}")
            else -> {
                view.g.cleanSelect(setOf(node))
                view.viewPoint(node).let { view.setViewTo(it.xy, view.viewTransform.scale, it.z) }
            }
        }
    }

    // --- jump list ---

    /** Where this view is now, as a [JumpList] entry. */
    fun currentLocation(): JumpList.Location =
        JumpList.Location(
            file = currentFile?.toAbsolutePath()?.normalize(),
            browseDir = browseDir,
            selection = view.g.selectedNodes.map { nodeKey(it) }.toSet(),
            transform = view.viewTransform.matrix.toList(),
            sourceView = sourceView,
        )

    /** How a node is recognised in a reloaded graph: its DOT id, or its label if it has none. */
    private fun nodeKey(n: Node): String = n.attributes.name.ifBlank { n.attributes.text }

    /** `Ctrl+O`: back to where the last jump started. */
    fun jumpBack() {
        val before = jumps.position()
        val target = jumps.back(currentLocation())
        if (target != null && !goTo(target)) jumps.restorePosition(before)
    }

    /** `Ctrl+I`: forward again after [jumpBack]. */
    fun jumpForward() {
        val before = jumps.position()
        val target = jumps.forward()
        if (target != null && !goTo(target)) jumps.restorePosition(before)
    }

    /**
     * Show [loc]: reload its file or directory unless the view shows
     * it already (asking first about unsaved changes), then restore its
     * selection and view. False if it couldn't be opened.
     */
    private fun goTo(loc: JumpList.Location): Boolean {
        val here = currentLocation()
        val file = loc.file
        val dir = loc.browseDir
        val source = loc.sourceView
        val sourcePath = source?.path
        val opened = when {
            loc.sameGraph(here) -> true
            file == null && dir == null && source == null -> false  // an untitled graph that is gone
            !confirmDiscard("go back to ${(file ?: dir ?: sourcePath)?.name}") -> false
            source != null -> showSourceInPlace(source, edit = false)
            dir != null -> Fs.isDirectory(dir).also { if (it) browseInPlace(dir) }
            else -> file?.let { loadInPlace(it) } == true
        }
        if (opened) {
            onceLoaded {
                view.viewTransform.matrix = loc.transform
                view.g.cleanSelect(view.g.nodes.filter { nodeKey(it) in loc.selection })
                view.repaint()
            }
        }
        return opened
    }
}
