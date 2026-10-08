package client

import Graph
import graph_tools.Attachments
import graph_tools.Node
import utils.Rgb
import utils.Vector2
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Graph for a `<file>:<line>` / `<file>:<from>-<to>` link
 * ([FileLink.Lines]; a single line is a one-line range): a monospaced
 * node with the numbered lines of the range under a header naming the
 * file, and grey context nodes with up to [CONTEXT] lines before and
 * after it, attached above and below ([Attachments]). All lines are
 * padded to one width, so the centred nodes line up column for column.
 * The range node sits at the origin, so a fresh tab centres it.
 */
object SourceView {
    const val CONTEXT = 500

    /** DOT ids of the nodes. */
    const val NODE_ID = "source"
    const val BEFORE_ID = "source-before"
    const val AFTER_ID = "source-after"

    private const val TAB = "    "
    private val contextFill = Rgb(0xEEEEEE)
    private val contextText = Rgb(0x666666)

    fun title(path: Path, lines: FileLink.Lines): String = "view: ${path.fileName}:$lines"

    fun build(path: Path, lines: FileLink.Lines): Graph {
        val (from, to) = range(lines)
        val first = (from - CONTEXT).coerceAtLeast(1)
        val file = read(path, to + CONTEXT)
        val numberWidth = file.size.coerceAtLeast(to).toString().length
        val numbered = { range: IntRange ->
            range.filter { it <= file.size }.map { no ->
                "${no.toString().padStart(numberWidth)} │ ${file[no - 1].replace("\t", TAB)}"
            }
        }
        val header = "$path:$lines"
        val body = numbered(from..to).ifEmpty { listOf("(the file has fewer than $from lines)") }
        val before = numbered(first until from)
        val after = numbered(to + 1..to + CONTEXT)
        val width = (listOf(header) + body + before + after).maxOf { it.length }
        val block = { ls: List<String> -> ls.joinToString("\n") { it.padEnd(width) } }

        val g = Graph()
        val main = sourceNode(NODE_ID, block(listOf(header, "─".repeat(width)) + body))
        g.add(main)
        listOf(BEFORE_ID to before, AFTER_ID to after).filter { it.second.isNotEmpty() }.forEach { (id, ls) ->
            g.add(sourceNode(id, block(ls)).also {
                it.attributes.bg = contextFill
                it.attributes.fg = contextText
                it.attributes.other[Attachments.ATTACH_TO] = NODE_ID
                it.attributes.other[Attachments.ATTACH_SIDE] = if (id == BEFORE_ID) "above" else "below"
            })
        }
        return g
    }

    private fun sourceNode(id: String, text: String): Node =
        Node(name = id).also {
            it.attributes.text = text
            it.attributes.other["fontname"] = "monospace"
            it.position = Vector2(0, 0)
        }

    /** First and last line of the range, 1-based, inclusive; a single line is a one-line range. */
    private fun range(lines: FileLink.Lines): Pair<Int, Int> {
        val to = lines.to ?: lines.from
        val from = minOf(lines.from, to).coerceAtLeast(1)
        return from to maxOf(lines.from, to, from)
    }

    /** The first [upTo] lines of [path]; undecodable bytes become `�` (it may not be UTF-8 text). */
    private fun read(path: Path, upTo: Int): List<String> {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return java.io.InputStreamReader(Files.newInputStream(path), decoder).buffered().useLines { it.take(upTo).toList() }
    }
}
