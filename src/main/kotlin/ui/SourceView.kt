package ui

import Graph
import graph_tools.Node
import utils.Vector2
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Graph for a `<file>:<line>` / `<file>:<from>-<to>` link
 * ([FileLink.Lines]): a single monospaced node with the text of those
 * lines — a line with [CONTEXT] lines around it (the line itself
 * marked `▶`), or exactly the range — numbered, under a header naming
 * the file. The node sits at the origin, so a fresh tab centres it.
 */
object SourceView {
    const val CONTEXT = 50

    /** DOT id of the node, for focusing. */
    const val NODE_ID = "source"

    private const val TAB = "    "

    fun title(path: Path, lines: FileLink.Lines): String = "view: ${path.fileName}:$lines"

    fun build(path: Path, lines: FileLink.Lines): Graph {
        val (from, to) = window(lines)
        val text = read(path, to).drop(from - 1)
        val width = to.toString().length
        val body = text.mapIndexed { i, line ->
            val no = from + i
            val mark = if (lines.to == null && no == lines.from) "▶" else " "
            "$mark${no.toString().padStart(width)} │ ${line.replace("\t", TAB)}"
        }
        val header = "$path:$lines"
        val content = if (body.isEmpty()) listOf("(the file has fewer than $from lines)") else body
        val node = Node(name = NODE_ID).also {
            it.attributes.text = (listOf(header, "─".repeat(header.length)) + content).joinToString("\n")
            it.attributes.other["fontname"] = "monospace"
            it.position = Vector2(0, 0)
        }
        return Graph().also { it.add(node) }
    }

    /** First and last line to show, 1-based, inclusive. */
    private fun window(lines: FileLink.Lines): Pair<Int, Int> {
        val to = lines.to
        return when {
            to == null -> (lines.from - CONTEXT).coerceAtLeast(1) to lines.from + CONTEXT
            to < lines.from -> to.coerceAtLeast(1) to lines.from
            else -> lines.from.coerceAtLeast(1) to to
        }
    }

    /** The first [upTo] lines of [path]; undecodable bytes become `�` (it may not be UTF-8 text). */
    private fun read(path: Path, upTo: Int): List<String> {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return java.io.InputStreamReader(Files.newInputStream(path), decoder).buffered().useLines { it.take(upTo).toList() }
    }
}
