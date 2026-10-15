package ui

import client.*
import DotGraphLoader
import graph_tools.NodeType
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        Files.createTempFile("src", ".scala").also { f -> f.writeText((1..lines).joinToString("\n", postfix = "\n") { "line $it" }) }

    @Test
    fun `the graph of a link to lines is one node whose text is kept in those lines`() {
        val f = source(30)
        val node = SourceView.build(FileLink.Target(f, null, FileLink.Lines(5, 7))).nodes.single()
        assertEquals("line 5\nline 6\nline 7", node.attributes.text)
        assertEquals("code-scala:$f:5-7", node.attributes.other[NodeType.ATTRIBUTE])
        assertEquals(NodeType.Kind.Plain, SourceView.typeOf(FileLink.Target(Paths.get("/src/notes.txt"), null)).kind)
        assertEquals("markdown:/src/R.md#Depth", SourceView.typeOf(FileLink.Target(Paths.get("/src/R.md"), null, FileLink.Lines(3, 9), section = "Depth")).attribute)
    }

    @Test
    fun `gf on a line link opens the lines, selected but not edited, Ctrl+O and Ctrl+I go back and forth`() {
        val src = source(80)
        val dir = Files.createTempDirectory("sv")
        val g = dir.resolve("g.dot").also { it.writeText("digraph g {\n  m [ link=\"$src:40\" ];\n}\n") }
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(g.toString()), initialFile = g)
        gv.g.cleanSelect(gv.g.nodes)
        val title = SourceView.title(FileLink.Target(src, null, FileLink.Lines(40, null)))

        gv.content.followFileLink()
        assertEquals(null, gv.currentFile)
        assertEquals(title, gv.content.untitledName)
        assertEquals(listOf(SourceView.NODE_ID), gv.g.selectedNodes.map { it.attributes.name })
        assertFalse(gv.content.pendingEdit)

        gv.content.jumpBack()
        assertEquals("g.dot", gv.currentFile?.fileName.toString())
        gv.content.jumpForward()
        assertEquals(title, gv.content.untitledName)
    }

    @Test
    fun `an edit of the opened lines is in the file once the view is saved`() {
        val src = source(10)
        val gv = GraphView()
        assertTrue(gv.content.showSourceInPlace(FileLink.Target(src, null, FileLink.Lines(2, 3))))
        val node = gv.g.nodes.single()
        gv.model.textEditStarted(node.id)
        gv.model.textEditEnded("line 2\nmore\nline 3")
        assertTrue(gv.content.isDirty)
        assertEquals("line 3", src.readText().lines()[2])
        assertTrue(gv.content.saveNow())
        assertEquals("line 1\nline 2\nmore\nline 3\nline 4", src.readText().lines().take(5).joinToString("\n"))
        assertEquals("code-scala:$src:2-4", node.attributes.other[NodeType.ATTRIBUTE])
        assertFalse(gv.content.isDirty)
    }

    @Test
    fun `gf on a text file opens all of it`() {
        val src = source(4)
        val dir = Files.createTempDirectory("sv")
        val g = dir.resolve("g.dot").also { it.writeText("digraph g {\n  m [ link=\"$src\" ];\n}\n") }
        val gv = GraphView(initialGraph = DotGraphLoader.loadFromFile(g.toString()), initialFile = g)
        gv.g.cleanSelect(gv.g.nodes)
        gv.content.followFileLink()
        assertEquals("line 1\nline 2\nline 3\nline 4", gv.g.nodes.single().attributes.text)
    }
}
