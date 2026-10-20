package client

import graph_tools.Command
import graph_tools.CompositeCommand
import graph_tools.EditTextCommand
import graph_tools.Node
import graph_tools.NodeType
import graph_tools.SetNodeTypeCommand

/**
 * Nodes whose text is kept in a file ([NodeType.source]): lines of
 * it, a section of it, or all of it.
 *
 * Every such file has one buffer here, shared by all the views of
 * the application: the file's text with the edits that are not
 * saved yet. A node is a window on lines of the buffer, and its
 * text is what the buffer holds there. So nodes that hold the same
 * lines, or lines of one another, show one text.
 *
 * The graphs are what is edited, undone and shared; the buffer
 * follows them. Whenever a graph changes — an edit, an undo, a
 * change that came from elsewhere — [reconcile] finds the nodes
 * whose text is no longer the buffer's, puts their text into the
 * buffer as an edit of it, moves the windows below, and gives the
 * new text to the other nodes that see those lines. Saving a graph
 * writes the buffers of its files ([save]); only then do the line
 * numbers in the nodes' types change, to what they now are on disk.
 *
 * A graph that is not open is not corrected for lines that moved; a
 * section named by its heading has no such trouble.
 */
object FileContent {
    /** A node's window: lines of the buffer of [path] (from 1), and the [text] that was there when the node last agreed with it. */
    class Kept(val path: Path, val lines: IntRange, val text: String)

    /** Lines [old] became [size] lines. */
    private class Edit(val old: IntRange, val size: Int)

    private class Buffer(val path: Path) {
        var lines: List<String> = emptyList()
        private var ended = true

        /** The file's text as it was last read or written. */
        var disk = ""

        /** What was done to [lines] since: by these, a place in the file is found in the buffer. */
        val edits = mutableListOf<Edit>()

        val text: String get() = if (lines.isEmpty()) "" else lines.joinToString("\n") + if (ended) "\n" else ""

        fun read() {
            disk = Fs.readText(path)
            ended = disk.endsWith("\n")
            lines = if (disk.isEmpty()) emptyList() else disk.removeSuffix("\n").split("\n")
            edits.clear()
        }

        fun written() {
            disk = text
            edits.clear()
        }
    }

    private val buffers = mutableMapOf<String, Buffer>()

    /** The windows of the nodes, by document ([key]) and node id. */
    private val kept = mutableMapOf<Any, MutableMap<String, Kept>>()

    /** While this changes graphs itself, their changes are not news to it. */
    private var busy = false

    /** Views of one shared document are replicas of it and have its windows in common; a view without one is its own. */
    private fun key(view: EditorView): Any =
        view.content.currentFile?.takeIf { view.content.document != null }?.toAbsolutePath()?.normalize()?.toString() ?: view

    /** One view of every open document, [view] for its own. */
    private fun documents(view: EditorView): List<EditorView> = (listOf(view) + view.shell.views()).distinctBy { key(it) }

    private fun bufferOf(path: Path): Buffer? =
        buffers[path.toString()] ?: try {
            Buffer(path).also {
                it.read()
                buffers[path.toString()] = it
            }
        } catch (e: Exception) {
            null
        }

    /** Where the text of [node] is kept, when in a file; [file] is the file of the node's graph. */
    fun of(node: Node, file: Path?): FileLink.Target? =
        NodeType.of(node).let { type ->
            // text, whatever the file is called: of a graph too, lines are meant, not a node
            type.source?.let { source -> resolved.getOrPut("$file\n${type.kind}\n$source") { FileLink.resolve(source, file, FileLink.Type.Text(type.copy(source = null))) } }
        }
            ?.takeIf { it.path != null && it.nodeId == null && !it.help }

    /**
     * What a source written in a graph's file came to. Finding that
     * out looks at the file system (is the whole of it a path, where
     * is the section), and it is asked of every node after every
     * change of a graph; the answer does not change with the graph.
     */
    private val resolved = mutableMapOf<String, FileLink.Target>()

    private fun nodesOf(view: EditorView): List<Pair<Node, FileLink.Target>> =
        view.g.nodes.mapNotNull { n -> of(n, view.content.currentFile)?.let { n to it } }

    private fun textOf(lines: List<String>, range: IntRange): String =
        lines.subList((range.first - 1).coerceIn(0, lines.size), range.last.coerceIn((range.first - 1).coerceIn(0, lines.size), lines.size)).joinToString("\n")

    /** Where the lines [was] are after [edit]: below it they move, around it they grow or shrink with it, and what was within it stays within. */
    private fun moved(was: IntRange, edit: Edit): IntRange {
        val old = edit.old
        val by = edit.size - (old.last - old.first + 1)
        val first = when {
            was.first > old.last -> was.first + by
            was.first > old.first -> minOf(was.first, old.first + edit.size)
            else -> was.first
        }
        val last = when {
            was.last >= old.last -> was.last + by
            was.last >= old.first -> minOf(was.last, old.first + edit.size - 1)
            else -> was.last
        }
        return first..maxOf(last, first - 1)
    }

    /** The lines [source] speaks of, as they are in [buffer] now; null for a section it does not have. */
    private fun window(source: FileLink.Target, buffer: Buffer): IntRange? {
        val size = buffer.lines.size
        val named = source.lines
        return when {
            source.section != null -> Sections.find(buffer.lines, source.section)?.let { it.from..(it.to ?: it.from) }
            source.anchor != null -> source.anchor.find(buffer.lines)?.let { it.from..(it.to ?: it.from) }
            // the type names them as they are in the file
            named != null -> buffer.edits.fold(named.from..(named.to ?: named.from)) { range, edit -> moved(range, edit) }
                .let { now -> now.first.coerceIn(1, size + 1).let { first -> first..now.last.coerceIn(first - 1, size) } }
            else -> 1..size
        }
    }

    private fun windowOf(source: FileLink.Target): Kept? =
        source.path?.let { path -> bufferOf(path)?.let { buffer -> window(source, buffer)?.let { Kept(path, it, textOf(buffer.lines, it)) } } }

    /**
     * Where the text of [node] of [view] is kept, as `:file` tells it:
     * the file, the lines as they are now in its buffer (or the
     * section), `[modified]` when the file has unsaved edits; null for
     * a node that keeps its own text.
     */
    fun describe(view: EditorView, node: Node): String? =
        where(view, node)?.let { place ->
            val unsaved = of(node, view.content.currentFile)?.let { buffers[it.path.toString()] }?.let { it.text != it.disk } == true
            place + if (unsaved) " [modified]" else ""
        }

    /** Where the text of [node] of [view] is kept: the file and the lines as they are now (or the section); null for a node that keeps its own text. */
    fun where(view: EditorView, node: Node): String? =
        of(node, view.content.currentFile)?.let { source ->
            val now = kept[key(view)]?.get(node.id)?.lines?.takeIf { source.lines != null }?.let { ":${it.first}-${it.last}" }
            val part = source.section?.let { "#$it" } ?: source.anchor?.let { ":$it" } ?: now ?: source.lines?.let { ":$it" }.orEmpty()
            "${source.path}$part"
        }

    /** The text [source] speaks of, unsaved edits included, or null when it cannot be had. */
    fun read(source: FileLink.Target): String? = windowOf(source)?.text

    private fun commit(view: EditorView, commands: List<Command>) {
        when (commands.size) {
            0 -> Unit
            1 -> view.g.commit(commands.single())
            else -> view.g.commit(CompositeCommand(commands))
        }
    }

    /** Nodes take what their buffers hold: that is no edit of the graph ([graph_tools.Graph.commitDerived]). */
    private fun follow(view: EditorView, commands: List<Command>) {
        when (commands.size) {
            0 -> Unit
            1 -> view.g.commitDerived(commands.single())
            else -> view.g.commitDerived(CompositeCommand(commands))
        }
    }

    private fun quietly(action: () -> Unit) {
        if (!busy) {
            busy = true
            try {
                action()
            } finally {
                busy = false
            }
        }
    }

    /**
     * A graph of [view] changed, or may have: make the buffers and
     * the nodes of every open view agree again. A node that has no
     * window yet gets one and the buffer's text; a node of [view]
     * whose text is not what its window had has been changed, and
     * its text goes into the buffer.
     */
    fun reconcile(view: EditorView) = quietly {
        documents(view).forEach { v ->
            val windows = kept.getOrPut(key(v)) { mutableMapOf() }
            val taken = nodesOf(v).filter { (n, source) -> windows[n.id]?.path != source.path }.mapNotNull { (n, source) ->
                windowOf(source)?.let { window ->
                    windows[n.id] = window
                    window.text.takeIf { it != n.attributes.text && n !== v.editing }?.let { EditTextCommand(v.g, n, n.attributes.text, it, coalesceWindowMs = 0) }
                }
            }
            follow(v, taken)
        }
        val windows = kept.getValue(key(view))
        val size = { n: Node -> windows.getValue(n.id).lines.let { it.last - it.first } }
        // the smallest first: a node within another is then the edit, and the other one follows from it
        nodesOf(view).map { it.first }.filter { n -> n !== view.editing && windows[n.id]?.let { it.text != n.attributes.text } == true }.sortedBy(size).forEach { n ->
            val window = windows.getValue(n.id)
            val buffer = buffers.getValue(window.path.toString())
            if (textOf(buffer.lines, window.lines) == n.attributes.text) windows[n.id] = Kept(window.path, window.lines, n.attributes.text)
            else change(view, n, window, n.attributes.text, null)
        }
    }

    /**
     * The text of [node] of [view], which has the window [window],
     * is or becomes [text]: put it into the buffer, move the windows
     * and give the other nodes that see those lines their new text.
     * [own] is the command that changes [node] itself, when that is
     * still to be done; it is the undo step. What follows from it is
     * none: an undo of it is a change the others follow again.
     */
    private fun change(view: EditorView, node: Node, window: Kept, text: String, own: Command?) {
        val buffer = buffers.getValue(window.path.toString())
        val now = text.split("\n")
        val edit = Edit(window.lines, now.size)
        buffer.lines = buffer.lines.subList(0, window.lines.first - 1) + now + buffer.lines.subList(window.lines.last, buffer.lines.size)
        buffer.edits += edit
        kept.values.forEach { windows ->
            windows.keys.toList().forEach { id ->
                windows.getValue(id).takeIf { it.path == window.path }?.let { windows[id] = Kept(it.path, moved(it.lines, edit), it.text) }
            }
        }
        kept.getValue(key(view)).let { windows -> windows[node.id] = Kept(window.path, windows.getValue(node.id).lines, text) }
        documents(view).forEach { v ->
            val windows = kept[key(v)].orEmpty().toMutableMap()
            val follows = nodesOf(v).map { it.first }.filter { o -> windows[o.id]?.path == window.path && !(v === view && o === node) }.mapNotNull { o ->
                val was = windows.getValue(o.id)
                val seen = textOf(buffer.lines, was.lines)
                // one with a change of its own keeps it: its turn comes
                val unchanged = o.attributes.text == was.text
                if (unchanged) kept.getValue(key(v))[o.id] = Kept(was.path, was.lines, seen)
                seen.takeIf { unchanged && it != o.attributes.text && o !== v.editing }?.let { EditTextCommand(v.g, o, o.attributes.text, it, coalesceWindowMs = 0) }
            }
            own?.takeIf { v === view }?.let { v.g.commit(it) }
            follow(v, follows)
        }
        (listOf(view) + view.shell.views()).distinct().forEach { it.repaint() }
    }

    /** The user edited [node] of [view] from [before] to [text]: one undo step, which the other nodes of those lines follow. */
    fun edited(view: EditorView, node: Node, before: String, text: String) {
        val window = kept[key(view)]?.get(node.id)
        val command = EditTextCommand(view.g, node, before, text)
        // a node whose text is its own, or one that did not agree with its buffer: the change is found afterwards
        if (busy || window == null || window.text != before) view.g.commit(command) else quietly { change(view, node, window, text, command) }
    }

    /**
     * [node] of [view] is about to be edited: it gets what its
     * buffer holds, and the buffer what the file holds if that was
     * changed by something else while nothing here was unsaved.
     */
    fun entering(view: EditorView, node: Node) {
        reconcile(view)
        val buffer = kept[key(view)]?.get(node.id)?.let { buffers[it.path.toString()] }
        if (buffer != null && stale(buffer)) reread(view, listOf(buffer.path))
        // one whose lines are not found keeps a text of its own, which is what is edited then
        of(node, view.content.currentFile)?.takeIf { kept[key(view)]?.get(node.id) == null }?.let { source ->
            val missing = source.section?.let { "no section `$it`" } ?: source.anchor?.let { "no lines `$it`" } ?: "nothing that can be read"
            view.shell.message("${source.path?.name} has $missing: the node's text is not kept in it")
        }
    }

    /** Nothing in [buffer] is unsaved, and something else changed the file since it was read. */
    private fun stale(buffer: Buffer): Boolean =
        buffer.text == buffer.disk && try {
            Fs.readText(buffer.path) != buffer.disk
        } catch (e: Exception) {
            false
        }

    /** The graph of [view] is read again: its files are too, those that have nothing unsaved; in every open view. */
    fun refresh(view: EditorView) {
        val paths = nodesOf(view).mapNotNull { it.second.path }.distinct().filter { path -> buffers[path.toString()]?.let { stale(it) } == true }
        if (paths.isEmpty()) reconcile(view) else reread(view, paths)
    }

    /** The windows of [nodes] of [view] (of all its nodes by default) are of no use: they were told to keep their text elsewhere, or their graph is read again. */
    fun forget(view: EditorView, nodes: Collection<Node>? = null) {
        if (nodes == null) kept.remove(key(view)) else nodes.forEach { kept[key(view)]?.remove(it.id) }
    }

    /** [view] shows another graph: a view that is its own document starts anew. */
    fun left(view: EditorView) {
        kept.remove(view)
    }

    /** Give up what is unsaved in the files of the nodes of [view], and take their text from the files again; in every open view. */
    fun reload(view: EditorView) = reread(view, nodesOf(view).mapNotNull { it.second.path }.distinct())

    private fun reread(view: EditorView, paths: List<Path>) {
        resolved.clear()
        paths.forEach { path ->
            try {
                buffers[path.toString()]?.read()
            } catch (e: Exception) {
                buffers.remove(path.toString())
            }
            kept.values.forEach { windows -> windows.keys.toList().forEach { id -> if (windows.getValue(id).path == path) windows.remove(id) } }
        }
        reconcile(view)
    }

    /**
     * Write the buffers of the files of the nodes of [view], and
     * make the types of the nodes of every open view name the lines
     * as they now are in the files. A file that something else
     * changed meanwhile is not written, with a message. True when
     * nothing was left unwritten.
     */
    fun save(view: EditorView): Boolean {
        reconcile(view)
        return write(view, nodesOf(view).mapNotNull { it.second.path }.distinct().mapNotNull { buffers[it.toString()] })
    }

    /** Know of no file any more; for tests, which share this object. */
    internal fun dropAll() {
        buffers.clear()
        kept.clear()
        resolved.clear()
    }

    /** The names of the files that have something unsaved in their buffers. */
    fun unsaved(): List<String> = buffers.values.filter { it.text != it.disk }.map { it.path.name }

    /** [save] for every file that has something unsaved, whichever graph it was changed from, and for those no open graph speaks of any more. */
    fun saveAll(view: EditorView): Boolean {
        reconcile(view)
        return write(view, buffers.values.filter { it.text != it.disk })
    }

    private fun write(view: EditorView, files: List<Buffer>): Boolean =
        files.map { buffer ->
            val problem = try {
                when {
                    buffer.text == buffer.disk -> null
                    Fs.readText(buffer.path) != buffer.disk -> "it was changed by something else; `:e!` takes its text again"
                    else -> {
                        Fs.writeText(buffer.path, buffer.text)
                        null
                    }
                }
            } catch (e: Exception) {
                e.message ?: "it cannot be written"
            }
            if (problem == null) {
                buffer.written()
                quietly { renumber(view, buffer.path) }
            } else {
                view.shell.message("${buffer.path.name} was not written: $problem")
            }
            problem == null
        }.all { it }

    /**
     * The buffer of [path] is the file: the types that name lines of
     * it name the lines of the windows. A section or an [Anchor] is
     * not renumbered; one that no longer finds the lines of its node
     * is told of.
     */
    private fun renumber(view: EditorView, path: Path) {
        val lost = mutableListOf<String>()
        documents(view).forEach { v ->
            val windows = kept[key(v)].orEmpty()
            val named = nodesOf(v).filter { (_, source) -> source.path == path }.mapNotNull { (n, source) ->
                val lines = source.lines?.takeIf { source.section == null && source.anchor == null }?.let { it.from..(it.to ?: it.from) }
                val found = buffers[path.toString()]?.let { b -> window(source, b) }
                val window = windows[n.id]?.lines
                val looked = source.section != null || source.anchor != null
                if (looked && window != null && found != window) lost +=source.section?.let { "#$it" } ?: ":${source.anchor}"
                window?.takeIf { lines != null && lines != it }?.let { now ->
                    val type = NodeType.of(n)
                    SetNodeTypeCommand(v.g, mapOf(n to n.attributes.other[NodeType.ATTRIBUTE]), type.copy(source = type.source!!.substringBeforeLast(':') + ":${now.first}-${now.last}"))
                }
            }
            commit(v, named)
        }
        if (lost.isNotEmpty()) view.shell.message("${path.name}: ${lost.distinct().joinToString(", ")} no longer finds the lines of its node")
    }
}
