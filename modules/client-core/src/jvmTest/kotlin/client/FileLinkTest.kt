package client

import graph_tools.NodeType
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class FileLinkTest {
    private val dir = Files.createTempDirectory("links").also { d ->
        d.resolve("notes.md").writeText("# Intro\nhello\n# Depth\ndeep\n")
    }

    @Test
    fun `the types a link can have, and those it cannot`() {
        assertEquals(FileLink.Type.File, FileLink.Type.parse(null))
        assertEquals(FileLink.Type.File, FileLink.Type.parse(" "))
        assertEquals(FileLink.Type.File, FileLink.Type.parse("file"))
        assertEquals(FileLink.Type.Graph, FileLink.Type.parse("Graph"))
        assertEquals(FileLink.Type.Directory, FileLink.Type.parse("directory"))
        assertEquals(FileLink.Type.Workspace, FileLink.Type.parse("workspace"))
        assertEquals(FileLink.Type.Text(NodeType(NodeType.Kind.Code, "scala")), FileLink.Type.parse("code-scala"))
        assertEquals(FileLink.Type.Text(NodeType.Markdown), FileLink.Type.parse("markdown"))
        assertEquals(FileLink.Type.Text(NodeType.Plain), FileLink.Type.parse("plain"))
        assertEquals(listOf(null, null, null), listOf("terminal", "code-scala:a.scala", "folder").map { FileLink.Type.parse(it) })
    }

    @Test
    fun `the types only a link can have are not a node's`() {
        assertEquals(listOf(null, null, null, null), listOf("graph", "directory", "workspace", "file").map { NodeType.parse(it) })
    }

    @Test
    fun `with a type the value is read as that type reads it`() {
        val at = { link: String, type: FileLink.Type -> FileLink.resolveIn(link, dir, type) }
        // no type: in a .dot file a number is a node
        assertEquals("12", at("g.dot:12", FileLink.Type.File).nodeId)
        val code = FileLink.Type.Text(NodeType(NodeType.Kind.Code, "dot"))
        assertEquals(FileLink.Lines(12, null) to null, at("g.dot:12", code).let { it.lines to it.nodeId })
        assertEquals(FileLink.Lines(3, 4), at("notes.md#depth", FileLink.Type.Text(NodeType.Markdown)).lines)
        // a graph, whatever its file is called
        assertEquals(dir.resolve("graph.txt") to "n1", at("graph.txt:n1", FileLink.Type.Graph).let { it.path to it.nodeId })
        assertEquals(null to "n5", at(":n5", FileLink.Type.Graph).let { it.path to it.nodeId })
        // a directory and a workspace have nothing after the path
        assertEquals(dir.resolve("a:b") to null, at("a:b", FileLink.Type.Workspace).let { it.path to it.nodeId })
        assertEquals(FileLink.Type.Directory, at("sub", FileLink.Type.Directory).type)
    }

    @Test
    fun `a source view shows the text as the link says, else as the file's name says`() {
        val md = FileLink.resolveIn("notes.md", dir, FileLink.Type.File)
        assertEquals(NodeType.Kind.Markdown, SourceView.typeOf(md).kind)
        val plain = FileLink.resolveIn("notes.md", dir, FileLink.Type.Text(NodeType.Plain))
        assertEquals(NodeType.Kind.Plain, SourceView.typeOf(plain).kind)
    }
}
