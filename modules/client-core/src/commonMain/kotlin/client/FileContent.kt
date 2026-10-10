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
 * The node's text is a working copy. It is taken from the file when
 * the graph is opened; an edit of it is a change of the graph like
 * any other, undone like any other, and it is written to the file
 * when the graph is saved ([save]). What the file held when the
 * copy was taken, and in which lines, is remembered beside the view
 * ([Kept]): a node whose text differs from that has an edit waiting.
 *
 * Lines are told by number, so a save that changes how many lines a
 * range has moves every range below it in that file. The nodes of
 * the open views are corrected for that; a graph that is not open
 * is not, and neither is anything when the file is changed by
 * something else — a save then refuses to write over it. A section
 * named by its heading has no such trouble.
 */
object FileContent {
    /** What the file held for a node when its text was last the file's: in which lines (from 1), and what. */
    class Kept(val lines: IntRange, val text: String)

    /** Where the text of [node] is kept, when in a file; [file] is the file of the node's graph. */
    fun of(node: Node, file: Path?): FileLink.Target? =
        NodeType.of(node).source?.let { FileLink.resolve(it, file) }?.takeIf { it.path != null && it.nodeId == null && !it.help }

    /** All the lines of [path] and whether the last of them ends with a line break. */
    private fun linesOf(path: Path): Pair<List<String>, Boolean> {
        val text = Fs.readText(path)
        return text.removeSuffix("\n").split("\n").let { if (text.isEmpty()) emptyList() else it } to text.endsWith("\n")
    }

    /** The first and last line [source] speaks of in a file of [size] lines, from 1; null for a section the file does not have. */
    private fun range(source: FileLink.Target, size: Int): IntRange? {
        val lines = source.lines
        return when {
            lines != null -> lines.from.coerceIn(1, size + 1).let { first -> first..(lines.to ?: lines.from).coerceIn(first - 1, size) }
            source.section != null -> null
            else -> 1..size
        }
    }

    private fun textOf(lines: List<String>, range: IntRange): String =
        lines.subList((range.first - 1).coerceIn(0, lines.size), range.last.coerceIn(range.first - 1, lines.size)).joinToString("\n")

    /** What the file holds where [source] says, or null when it cannot be had. */
    private fun held(source: FileLink.Target): Kept? =
        try {
            val (lines, _) = linesOf(source.path!!)
            range(source, lines.size)?.let { Kept(it, textOf(lines, it)) }
        } catch (e: Exception) {
            null
        }

    /** The text [source] speaks of, or null when it cannot be had. */
    fun read(source: FileLink.Target): String? = held(source)?.text

    private fun nodesOf(view: EditorView): List<Pair<Node, FileLink.Target>> =
        view.g.nodes.mapNotNull { n -> of(n, view.content.currentFile)?.let { n to it } }

    /** Whether [node] of [view] has an edit that is not in its file yet. */
    fun isWaiting(view: EditorView, node: Node): Boolean = view.fileTexts[node.id]?.let { it.text != node.attributes.text } ?: false

    /**
     * Take the text of [nodes] of [view] (all of them by default) from
     * their files, where the files can be read. A node with an edit
     * waiting keeps it.
     */
    fun refresh(view: EditorView, nodes: Collection<Node>? = null) {
        val changes = nodesOf(view).filter { (n, _) -> (nodes == null || n in nodes) && !isWaiting(view, n) }.mapNotNull { (n, source) ->
            held(source)?.let { now ->
                view.fileTexts[n.id] = now
                now.text.takeIf { it != n.attributes.text }?.let { EditTextCommand(view.g, n, n.attributes.text, it, coalesceWindowMs = 0) }
            }
        }
        commit(view, changes)
    }

    /** [nodes] of [view] were told to keep their text elsewhere: what is remembered of them is of no use. */
    fun forget(view: EditorView, nodes: Collection<Node>) = nodes.forEach { view.fileTexts.remove(it.id) }

    private fun commit(view: EditorView, commands: List<Command>) {
        when (commands.size) {
            0 -> Unit
            1 -> view.g.commit(commands.single())
            else -> view.g.commit(CompositeCommand(commands))
        }
    }

    /** One node's edit of a file: the lines it replaces and the lines it puts there. */
    private class Edit(val old: IntRange, val now: List<String>) {
        val by: Int get() = now.size - (old.last - old.first + 1)
    }

    /** Where the lines [was] are after [edits]: moved by those above them, longer or shorter by those within them. */
    private fun moved(was: IntRange, edits: List<Edit>): IntRange {
        val above = edits.filter { it.old.last < was.first }.sumOf { it.by }
        val within = edits.filter { it.old.first >= was.first && it.old.last <= was.last }.sumOf { it.by }
        return was.first + above..was.last + above + within
    }

    /**
     * Write the edits waiting in [view] to their files, and correct
     * every open view ([views]) for the lines that moved by it: what
     * is remembered of each node of those files, the lines its type
     * names, and the text of the nodes that had no edit of their own.
     * A file that no longer holds what an edit was made of is not
     * written, with a message. True when nothing was left unwritten.
     */
    fun save(view: EditorView, views: List<EditorView>): Boolean {
        val waiting = nodesOf(view).filter { isWaiting(view, it.first) }.groupBy { it.second.path!! }
        return waiting.map { (path, nodes) -> saveFile(view, path, nodes.map { it.first }, (listOf(view) + views).distinct()) }.all { it }
    }

    private fun saveFile(view: EditorView, path: Path, nodes: List<Node>, views: List<EditorView>): Boolean {
        val problem = try {
            val (lines, ended) = linesOf(path)
            val edits = nodes.map { n -> Edit(view.fileTexts.getValue(n.id).lines, n.attributes.text.split("\n")) }.sortedBy { it.old.first }
            val changedOutside = nodes.any { n -> view.fileTexts.getValue(n.id).let { textOf(lines, it.lines) != it.text } }
            val overlap = edits.zipWithNext().any { (a, b) -> b.old.first <= a.old.last }
            when {
                changedOutside -> "it was changed by something else; `:e` takes its text again"
                overlap -> "two edited nodes hold the same lines"
                else -> {
                    val written = edits.reversed().fold(lines) { now, edit -> now.subList(0, edit.old.first - 1) + edit.now + now.subList(edit.old.last, now.size) }
                    Fs.writeText(path, written.joinToString("\n") + if (ended) "\n" else "")
                    corrected(path, edits, written, views)
                    null
                }
            }
        } catch (e: Exception) {
            e.message ?: "it cannot be written"
        }
        problem?.let { view.shell.message("${path.name} was not written: $it") }
        return problem == null
    }

    /** After [edits] made [path] hold [lines]: see [save]. */
    private fun corrected(path: Path, edits: List<Edit>, lines: List<String>, views: List<EditorView>) {
        // views of one shared document are replicas of it: a change of the graph made in one of them reaches the others
        val first = views.distinctBy { v -> v.content.currentFile?.takeIf { v.content.document != null }?.toAbsolutePath()?.normalize()?.toString() ?: v }
        views.forEach { other ->
            val changes = nodesOf(other).filter { it.second.path == path }.flatMap { (n, source) ->
                val kept = other.fileTexts[n.id]
                val unedited = kept != null && kept.text == n.attributes.text
                val now = kept?.let { moved(it.lines, edits) }?.let { Kept(it, textOf(lines, it)) }
                now?.let { other.fileTexts[n.id] = it }
                val type = NodeType.of(n)
                val named = range(source, Int.MAX_VALUE - 1)?.takeIf { source.section == null && source.lines != null }
                listOfNotNull(
                    now?.takeIf { named != null && named != it.lines }?.let {
                        val there = type.copy(source = type.source!!.substringBeforeLast(':') + ":${it.lines.first}-${it.lines.last}")
                        SetNodeTypeCommand(other.g, mapOf(n to n.attributes.other[NodeType.ATTRIBUTE]), there)
                    },
                    now?.takeIf { unedited && it.text != n.attributes.text }?.let { EditTextCommand(other.g, n, n.attributes.text, it.text, coalesceWindowMs = 0) },
                )
            }
            if (other in first) commit(other, changes)
            other.repaint()
        }
    }
}
