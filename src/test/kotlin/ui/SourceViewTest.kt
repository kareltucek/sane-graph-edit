package ui

import client.*
import DotGraphLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceViewTest {

    private val base = Paths.get("/src")

    @Test
    fun `line and range links of non-dot files`() {
        assertEquals(
            FileLink.Target(Paths.get("/src/A.scala"), null, FileLink.Lines(42, null)),
            FileLink.resolveIn("A.scala:42", base),
        )
        assertEquals(
            FileLink.Target(Paths.get("/src/A.scala"), null, FileLink.Lines(10, 20)),
            FileLink.resolveIn("A.scala:10-20", base),
        )
        // in a DOT file a number is still a node id
        assertEquals(FileLink.Target(Paths.get("/src/g.dot"), "42"), FileLink.resolveIn("g.dot:42", base))
        // and a non-number is a node id anywhere
        assertEquals(FileLink.Target(Paths.get("/src/A.scala"), "foo"), FileLink.resolveIn("A.scala:foo", base))
    }

    private fun source(lines: Int) =
        Files.createTempFile("src", ".scala").also { f -> f.writeText((1..lines).joinToString("\n") { "line $it\tx" }) }

    private fun nodes(f: java.nio.file.Path, lines: FileLink.Lines) =
        SourceView.build(f, lines).nodes.associateBy { it.attributes.name }

    private fun textLines(n: graph_tools.Node?) = n?.attributes?.text?.lines()?.map { it.trimEnd() }.orEmpty()

    @Test
    fun `a single line is a one-line range with 500 lines of context attached above and below`() {
        val f = source(1200)
        val ns = nodes(f, FileLink.Lines(600, null))
        val main = textLines(ns[SourceView.NODE_ID]).drop(2)  // header + rule
        assertEquals(listOf(" 600 │ line 600    x"), main)  // tab expanded, no marker
        val before = textLines(ns[SourceView.BEFORE_ID])
        val after = textLines(ns[SourceView.AFTER_ID])
        assertEquals(500, before.size)
        assertEquals(" 100 │ line 100    x", before.first())
        assertEquals(" 599 │ line 599    x", before.last())
        assertEquals(500, after.size)
        assertEquals("1100 │ line 1100    x", after.last())
        assertEquals("above", ns[SourceView.BEFORE_ID]?.attributes?.other?.get("attachSide"))
        assertEquals(SourceView.NODE_ID, ns[SourceView.AFTER_ID]?.attributes?.other?.get("attachTo"))
        assertEquals("monospace", ns[SourceView.AFTER_ID]?.attributes?.other?.get("fontname"))
        // every line padded to one width, so the centred nodes line up
        val widths = ns.values.flatMap { it.attributes.text.lines() }.map { it.length }.toSet()
        assertEquals(1, widths.size)
    }

    @Test
    fun `context is clipped at the file's ends`() {
        val f = source(30)
        val ns = nodes(f, FileLink.Lines(5, 7))
        assertEquals(listOf(" 5 │ line 5    x", " 6 │ line 6    x", " 7 │ line 7    x"), textLines(ns[SourceView.NODE_ID]).drop(2))
        assertEquals(4, textLines(ns[SourceView.BEFORE_ID]).size)
        assertEquals(23, textLines(ns[SourceView.AFTER_ID]).size)
        assertEquals(null, nodes(f, FileLink.Lines(1, 30))[SourceView.BEFORE_ID])  // nothing before line 1
    }

    @Test
    fun `gf on a line link shows the source, Ctrl+O and Ctrl+I go back and forth`() {
        val src = source(80)
        val dir = Files.createTempDirectory("sv")
        val g = dir.resolve("g.dot").also { it.writeText("digraph g {\n  m [ link=\"$src:40\" ];\n}\n") }
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(g.toString()), initialFile = g)
        gv.g.cleanSelect(gv.g.nodes)

        gv.content.followFileLink()
        assertEquals(null, gv.currentFile)
        assertEquals(SourceView.title(src, FileLink.Lines(40, null)), gv.content.untitledName)

        gv.content.jumpBack()
        assertEquals("g.dot", gv.currentFile?.fileName.toString())
        gv.content.jumpForward()
        assertEquals(SourceView.title(src, FileLink.Lines(40, null)), gv.content.untitledName)
    }
}
