package client

import graph_tools.Command
import graph_tools.CompositeCommand
import graph_tools.EditTextCommand
import graph_tools.Node
import graph_tools.NodeType
import graph_tools.SetNodeTypeCommand

/**
 * Nodes whose text is kept in a file ([NodeType.source]): lines of
 * it, a section of it, or all of it. The node's own text is a copy,
 * taken when the graph is opened and when the node is about to be
 * edited; an edit is written to the file.
 *
 * Lines are told by number, so an edit that changes how many lines
 * a range has moves every range below it in that file. The nodes of
 * the open views are corrected for that ([write]); a graph that is
 * not open is not, and neither is anything when the file is changed
 * by something else. A section named by its heading has no such
 * trouble.
 */
object FileContent {
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

    /** The text [source] speaks of, or null when it cannot be had. */
    fun read(source: FileLink.Target): String? =
        try {
            val (lines, _) = linesOf(source.path!!)
            range(source, lines.size)?.let { lines.subList(it.first - 1, it.last).joinToString("\n") }
        } catch (e: Exception) {
            null
        }

    private fun nodesOf(view: EditorView): List<Pair<Node, FileLink.Target>> =
        view.g.nodes.mapNotNull { n -> of(n, view.content.currentFile)?.let { n to it } }

    /** Make the text of [nodes] of [view] (all of them by default) what their files hold now; a file that cannot be read changes nothing. */
    fun refresh(view: EditorView, nodes: Collection<Node>? = null) {
        val changes = nodesOf(view).filter { nodes == null || it.first in nodes }.mapNotNull { (n, source) ->
            read(source)?.takeIf { it != n.attributes.text }?.let { EditTextCommand(view.g, n, n.attributes.text, it, coalesceWindowMs = 0) }
        }
        commit(view, changes)
    }

    private fun commit(view: EditorView, commands: List<Command>) {
        when (commands.size) {
            0 -> Unit
            1 -> view.g.commit(commands.single())
            else -> view.g.commit(CompositeCommand(commands))
        }
    }

    /**
     * The user edited [node] of [view], whose text is kept at
     * [source], to [text]: write it there, and correct the open views
     * ([views]) for it — the lines the node speaks of, the lines of
     * the nodes below it in the file, and the text of every node of
     * that file. False, with nothing changed, when the file could not
     * be written.
     */
    fun write(view: EditorView, node: Node, source: FileLink.Target, text: String, views: List<EditorView>): Boolean {
        val path = source.path!!
        val written = try {
            val (lines, ended) = linesOf(path)
            range(source, lines.size)?.let { old ->
                val now = text.split("\n")
                Fs.writeText(path, (lines.subList(0, old.first - 1) + now + lines.subList(old.last, lines.size)).joinToString("\n") + if (ended) "\n" else "")
                old to (old.first until old.first + now.size)
            }
        } catch (e: Exception) {
            view.shell.message("cannot write ${path.name}: ${e.message}")
            null
        }
        written?.let { (old, now) ->
            // views of one shared document are replicas of it: what is done in one of them reaches the others
            val documents = (listOf(view) + views).distinctBy { v ->
                v.content.currentFile?.takeIf { v.content.document != null }?.toAbsolutePath()?.normalize()?.toString() ?: v
            }
            documents.forEach { other ->
                val moved = nodesOf(other).filter { it.second.path == path && it.second.lines != null && it.second.section == null }.mapNotNull { (n, at) ->
                    val was = range(at, Int.MAX_VALUE - 1)!!
                    val by = (now.last - now.first) - (old.last - old.first)
                    val becomes = when {
                        n === node -> now
                        was.first > old.last -> was.first + by..was.last + by
                        was.first <= old.first && was.last >= old.last -> was.first..was.last + by
                        else -> was
                    }
                    val type = NodeType.of(n)
                    val named = type.copy(source = type.source!!.substringBeforeLast(':') + ":${becomes.first}-${becomes.last}")
                    named.takeIf { becomes != was || (n === node && at.lines?.to == null && now.first != now.last) }
                        ?.let { SetNodeTypeCommand(other.g, mapOf(n to n.attributes.other[NodeType.ATTRIBUTE]), it) }
                }
                // the ranges first: the texts are read by them
                commit(other, moved)
                refresh(other, nodesOf(other).filter { it.second.path == path }.map { it.first })
                other.repaint()
            }
        }
        return written != null
    }
}
