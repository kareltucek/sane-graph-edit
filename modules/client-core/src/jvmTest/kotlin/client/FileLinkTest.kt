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

    @Test
    fun `lines named by patterns, with a type or without`() {
        val at = { link: String, type: FileLink.Type -> FileLink.resolveIn(link, dir, type) }
        assertEquals(FileLink.Lines(3, 4) to dir.resolve("notes.md"), at("notes.md:/^# Depth/,+2", FileLink.Type.File).let { it.lines to it.path })
        assertEquals("/^# Depth/,/deep/", at("notes.md:/^# Depth/,/deep/", FileLink.Type.File).anchor.toString())
        // in a graph the part after the path is a node; typed as text, lines
        assertEquals(null, at("g.dot:/x/,+1", FileLink.Type.File).anchor)
        assertEquals("/x/,+1", at("g.dot:/x/,+1", FileLink.Type.Text(NodeType(NodeType.Kind.Code, "dot"))).anchor.toString())
        // not found: no lines, but the anchor is known
        assertEquals(null to "/^# None/,+1", at("notes.md:/^# None/,+1", FileLink.Type.File).let { it.lines to it.anchor.toString() })
    }

    @Test
    fun `a link copied into a graph elsewhere leads to where it led`() {
        val from = dir.resolve("a/b/from.dot")
        val moved = { link: String, to: java.nio.file.Path? -> FileLink.moved(link, from, to) }
        assertEquals("../b/notes.md#Depth", moved("notes.md#Depth", dir.resolve("a/c/to.dot")))
        assertEquals("b/src/A.kt:10-20", moved("src/A.kt:10-20", dir.resolve("a/to.dot")))
        assertEquals("../../x.dot:n1", moved("../x.dot:n1", dir.resolve("a/b/c/to.dot")))
        // the graph it was in, by name
        assertEquals("../b/from.dot:n5", moved(":n5", dir.resolve("a/c/to.dot")))
        // what does not depend on where the graph is, and a copy within one graph, stay
        listOf("/etc/hosts", "~/notes.md", "sge:help", "sge:examples").forEach { assertEquals(it, moved(it, dir.resolve("q/to.dot"))) }
        assertEquals("notes.md", moved("notes.md", from))
        assertEquals("../b/notes.md:/^# D\\/x/,+2", moved("notes.md:/^# D\\/x/,+2", dir.resolve("a/c/to.dot")))
        // into an untitled graph: absolute
        assertEquals("${dir.resolve("a/b/notes.md")}:3", moved("notes.md:3", null))
    }

    @Test
    fun `a source view of part of a file links to all of it, shown as the part is`() {
        val links = { link: String, type: FileLink.Type ->
            val node = SourceView.build(FileLink.resolveIn(link, dir, type)).nodes.single()
            FileLink.of(node, 'f')?.let { FileLink.resolveIn(it, dir, FileLink.Type.parse(FileLink.typeOf(node, 'f'))!!) }?.let { it.path to it.lines }
        }
        val whole = dir.resolve("notes.md").toAbsolutePath().normalize() to null
        assertEquals(whole, links("notes.md:2-3", FileLink.Type.File))
        assertEquals(whole, links("notes.md#depth", FileLink.Type.File))
        assertEquals(null, links("notes.md", FileLink.Type.File))
        val node = SourceView.build(FileLink.resolveIn("notes.md:2", dir, FileLink.Type.Text(NodeType.Plain))).nodes.single()
        assertEquals("plain", FileLink.typeOf(node, 'f'))
    }
}
