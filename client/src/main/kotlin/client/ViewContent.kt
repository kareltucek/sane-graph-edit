package client

import DotGraphLoader
import Graph
import export.SvgWriter
import graph_tools.Node
import graph_tools.ViewModes
import utils.Vector2
import java.nio.file.Files
import java.nio.file.Path

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

    /** Lines shown when the view is a [SourceView] (a `<file>:<line>` link). */
    var sourceView: FileLink.Target? = null

    /** This view's jump history (`Ctrl+O` / `Ctrl+I`). */
    var jumps: JumpList = JumpList()

    /** DOT id of the node to focus once the view has a size (a link opened this view). */
    var pendingFocus: String? = null

    /** The file's name, or what else is shown, with `*` in front while there are unsaved changes. */
    val title: String
        get() {
            val base = currentFile?.fileName?.toString() ?: untitledName ?: "(untitled)"
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
        doc?.onChange = { local -> if (!local) view.repaint() }
        doc?.onInfo = {
            dirty(doc.info.dirty)
            view.refreshStatus()
        }
        doc?.onRejected = { reason -> shell.message("change refused: $reason") }
        doc?.onLost = { document = null }
        return doc?.graph ?: graph
    }

    /**
     * Any change makes the graph "not the saved file any more". An
     * undo back to the saved state is not detected.
     */
    private fun watch(g: Graph) {
        g.history.addListener { dirty(true) }
    }

    /** Show [newGraph] (or the document of [file]) instead of the current graph. */
    private fun replaceGraph(newGraph: Graph, file: Path? = null) {
        view.g = adopt(newGraph, file)
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

    /** Save to [currentFile], asking for a file if there is none. False when it was not saved. */
    fun save(): Boolean = currentFile?.let { writeTo(it) } ?: saveAs()

    /** Ask for a file and save to it. */
    fun saveAs(): Boolean = shell.chooseSave(currentFile?.fileName?.toString() ?: "untitled.dot")?.let { writeTo(it) } ?: false

    /** Write the graph to [path] without making it the view's file. */
    fun saveCopy(path: Path) {
        document?.saveCopy(path.toString()) ?: DotGraphLoader.saveToFile(view.g, path.toString())
    }

    private fun writeTo(path: Path): Boolean =
        try {
            document?.save(path.toAbsolutePath().normalize().toString()) ?: DotGraphLoader.saveToFile(view.g, path.toString())
            currentFile = path
            dirty(false)
            shell.stateChanged()
            // the first save of an untitled view, or a save under a new name
            shell.sessionChanged()
            true
        } catch (t: Throwable) {
            shell.alert("Save error", "Could not save to $path:\n${t.message}")
            false
        }

    /** SVG of what is visible, to a file the user chooses. */
    fun exportSvg() {
        val name = currentFile?.fileName?.toString()?.removeSuffix(".dot")?.plus(".svg") ?: "untitled.svg"
        // hidden nodes stay hidden in the SVG
        shell.chooseExport(name)?.let { writeExport(it, SvgWriter.write(view.g, view.g.visibleNodes())) }
    }

    /** SVG of the selected nodes and the edges between them; of everything when nothing is selected. */
    fun exportSelection() {
        val selection = view.g.selectedNodes.takeIf { it.isNotEmpty() }
        val name = currentFile?.fileName?.toString()?.removeSuffix(".dot")?.plus("-selection.svg") ?: "selection.svg"
        shell.chooseExport(name)?.let { writeExport(it, SvgWriter.write(view.g, selection)) }
    }

    private fun writeExport(path: Path, content: String) {
        try {
            Files.writeString(path, content)
        } catch (t: Throwable) {
            shell.alert("Export error", "Could not export to $path:\n${t.message}")
        }
    }

    // --- opening ---

    /** Ask for a file or directory and show it in this view. */
    fun open() {
        shell.chooseOpen()
            ?.takeIf { confirmDiscard("open ${it.fileName}") }
            ?.let { path ->
                val from = currentLocation()
                if (Files.isDirectory(path)) {
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
            replaceGraph(DotGraphLoader.loadFromFile(path.toString()), path)
            currentFile = path
            untitledName = null
            browseDir = null
            sourceView = null
            shell.stateChanged()
            view.boundScreen()
            view.repaint()
            shell.sessionChanged()
            AppState.lastOpenDir = path.toAbsolutePath().parent
            true
        } catch (t: Throwable) {
            shell.alert("Open error", "Could not open $path:\n${t.message}")
            false
        }

    /**
     * Re-read [currentFile], e.g. after an external tool regenerated
     * it. Unsaved changes are lost, so a changed graph asks first
     * unless [force] is set (`:e!`). The view keeps looking at the
     * same spot.
     */
    fun reload(force: Boolean = false) {
        val path = currentFile
        if (path == null) {
            shell.alert("Reload", "This tab has no file to reload.", error = false)
        } else if (force || confirmDiscard("reload ${path.fileName}")) {
            try {
                // with a document everyone attached gets the file's content; a failure comes back as a message
                document?.reload() ?: replaceGraph(DotGraphLoader.loadFromFile(path.toString()))
                view.repaint()
            } catch (t: Throwable) {
                shell.alert("Reload error", "Could not reload $path:\n${t.message}")
            }
        }
    }

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

    /** Replace the graph with the [FileTree] of [dir]; the directory sits in the middle of the view. */
    fun browseInPlace(dir: Path) {
        replaceGraph(FileTree.build(dir))
        currentFile = null
        browseDir = dir.toAbsolutePath().normalize()
        sourceView = null
        untitledName = "browse: $browseDir"
        shell.stateChanged()
        view.setViewTo(Vector2.Zero)
        shell.sessionChanged()
    }

    /** Replace the graph with a [SourceView] of [lines] of [path]. False (and a message) if the file can't be read. */
    fun showSourceInPlace(path: Path, lines: FileLink.Lines): Boolean {
        val graph = try {
            SourceView.build(path, lines)
        } catch (e: Exception) {
            shell.message("cannot read $path: ${e.message}")
            null
        }
        if (graph != null) {
            replaceGraph(graph)
            currentFile = null
            browseDir = null
            sourceView = FileLink.Target(path, null, lines)
            untitledName = SourceView.title(path, lines)
            shell.stateChanged()
            if (view.size.x > 0) view.setViewTo(Vector2.Zero, view.viewTransform.scale)
            shell.sessionChanged()
        }
        return graph != null
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
        val problem = when {
            node == null -> "select exactly one node"
            link == null -> "the node has no `${FileLink.attributeName(letter)}` attribute"
            else -> null
        }
        if (problem != null && !quiet) shell.message("$command: $problem")
        return link?.let { FileLink.resolve(it, currentFile) }
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
        when {
            path != null && lines != null -> if (confirmDiscard("open ${path.fileName}:$lines")) {
                if (showSourceInPlace(path, lines)) jumps.record(from)
            }
            inThisGraph -> if (target.nodeId != null) {
                jumps.record(from)
                focusNode(target.nodeId)
            }
            path == null || !confirmDiscard("open ${path.fileName ?: path}") -> Unit
            Files.isDirectory(path) -> {
                jumps.record(from)
                browseInPlace(path)
            }
            loadInPlace(path) -> {
                jumps.record(from)
                focusNode(target.nodeId)
            }
        }
    }

    /** Like [openTarget], into a new tab that inherits this view's jump list. */
    fun openTargetInTab(target: FileLink.Target) {
        val path = target.path
        val lines = target.lines
        val opened = when {
            path == null -> {
                jumps.record(currentLocation())
                focusNode(target.nodeId)
                null
            }
            lines != null -> shell.newTab()?.takeIf { it.content.showSourceInPlace(path, lines) }
            Files.isDirectory(path) -> shell.browse(path)
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
            node == null -> shell.message("no node `$nodeId` in ${currentFile?.fileName ?: "this graph"}")
            else -> {
                view.g.cleanSelect(setOf(node))
                view.setViewTo(node.position, view.viewTransform.scale)
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
        val sourceLines = source?.lines
        val opened = when {
            loc.sameGraph(here) -> true
            file == null && dir == null && source == null -> false  // an untitled graph that is gone
            !confirmDiscard("go back to ${(file ?: dir ?: sourcePath)?.fileName}") -> false
            sourcePath != null && sourceLines != null -> showSourceInPlace(sourcePath, sourceLines)
            dir != null -> Files.isDirectory(dir).also { if (it) browseInPlace(dir) }
            else -> file?.let { loadInPlace(it) } == true
        }
        if (opened) {
            view.viewTransform.matrix = loc.transform
            view.g.cleanSelect(view.g.nodes.filter { nodeKey(it) in loc.selection })
            view.repaint()
        }
        return opened
    }
}
