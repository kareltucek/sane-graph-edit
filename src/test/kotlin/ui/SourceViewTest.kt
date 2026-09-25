package ui

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

    private fun body(text: String) = text.lines().drop(2)  // header + rule

    @Test
    fun `a line is shown with 50 lines of context and marked`() {
        val f = source(200)
        val node = SourceView.build(f, FileLink.Lines(100, null)).nodes.single()
        val lines = body(node.attributes.text)
        assertEquals(101, lines.size)
        assertTrue(lines.first().contains(" 50 │ line 50"), lines.first())
        assertTrue(lines.last().contains("150 │ line 150"), lines.last())
        assertTrue(lines[50].startsWith("▶100 │ line 100    x"), lines[50])  // tab expanded
        assertEquals("monospace", node.attributes.other["fontname"])
    }

    @Test
    fun `context is clipped at the file's ends, a range is shown as is`() {
        val f = source(30)
        assertEquals(30, body(SourceView.build(f, FileLink.Lines(3, null)).nodes.single().attributes.text).size)
        val range = body(SourceView.build(f, FileLink.Lines(5, 7)).nodes.single().attributes.text)
        assertEquals(listOf(" 5 │ line 5    x", " 6 │ line 6    x", " 7 │ line 7    x"), range)
    }

    @Test
    fun `gf on a line link shows the source, Ctrl+O and Ctrl+I go back and forth`() {
        val src = source(80)
        val dir = Files.createTempDirectory("sv")
        val g = dir.resolve("g.dot").also { it.writeText("digraph g {\n  m [ link=\"$src:40\" ];\n}\n") }
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(g.toString()), initialFile = g)
        gv.g.cleanSelect(gv.g.nodes)

        gv.followFileLink()
        assertEquals(null, gv.currentFile)
        assertEquals(SourceView.title(src, FileLink.Lines(40, null)), gv.untitledName)

        gv.jumpBack()
        assertEquals("g.dot", gv.currentFile?.fileName.toString())
        gv.jumpForward()
        assertEquals(SourceView.title(src, FileLink.Lines(40, null)), gv.untitledName)
    }
}
