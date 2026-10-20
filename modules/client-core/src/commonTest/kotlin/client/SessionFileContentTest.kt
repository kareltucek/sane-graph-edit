@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import editor.EditorCore
import editor.EditorHub
import editor.FileStore
import graph_tools.Node
import sync.Event
import sync.Request
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Nodes of files in views that are clients of an editor, as in the application; on the JVM and in JavaScript. */
class SessionFileContentTest {
    private object Disk : FileStore {
        override fun read(path: String): String = Fs.readText(Fs.path(path))
        override fun write(path: String, text: String) = Fs.writeText(Fs.path(path), text)
    }

    private class Local(private val hub: EditorHub) : EditorConnection {
        override var onEvent: (Event) -> Unit = {}
        override val clientId: String = hub.connect { onEvent(it) }
        override fun send(request: Request) = hub.send(clientId, request)
        override fun call(request: Request): Event? = hub.call(clientId, request)
        override fun close() = hub.disconnect(clientId)
    }

    private val dir: Path = Fs.runtimeDir.resolve("sge-probe-${Random.nextLong().toULong()}").also { Fs.createDirectories(it) }
    private val text: Path = dir.resolve("sections.txt").also { Fs.writeText(it, "head\n\nSection A\na1\n\nSection B\nb1\n") }
    private val hub = EditorHub(EditorCore(Disk)) { 0L }
    private val client = EditorClient(Local(hub))

    private val views = mutableListOf<EditorView>()
    private val messages = mutableListOf<String>()
    private val shell = object : Shell by NoShell {
        override fun views(): List<EditorView> = views
        override fun message(text: String) {
            messages += text
        }
    }

    private fun opened(link: String): EditorView =
        EditorView(Graph(), NoRenderer, shell, client).also {
            views += it
            it.content.openTarget(FileLink.resolveIn(link, dir))
        }

    private fun open(name: String, dot: String): EditorView {
        Fs.writeText(dir.resolve(name), dot)
        return opened(name)
    }

    private val graph = open(
        "ranges.dot",
        "digraph g {\n  a [\"content-type\"=\"plain:sections.txt:3-4\"];\n  whole [\"content-type\"=\"plain:sections.txt\"];\n}\n",
    )

    private fun node(view: EditorView, id: String): Node = view.g.nodes.single { it.attributes.name == id }
    private fun text(view: EditorView, id: String): String = node(view, id).attributes.text

    private fun edit(view: EditorView, id: String, text: String) = edit(view, node(view, id), text)

    private fun edit(view: EditorView, n: Node, text: String) {
        view.startNodeEdit(n, null)
        view.textEditStarted(n.id)
        view.textTyped(text)
        view.textEditEnded(text)
    }

    /** `:e`, agreeing to what it asks. */
    private fun readAgain(view: EditorView) {
        graph_tools.ViewModes.discard = true
        try {
            view.content.reload()
        } finally {
            graph_tools.ViewModes.discard = false
        }
    }

    @AfterTest
    fun forget() = FileContent.dropAll()

    @Test
    fun `the graph shows the file, and that is no change of it`() {
        assertEquals("Section A\na1", text(graph, "a"))
        assertFalse(graph.content.isDirty)
        graph.g.undo()
        assertEquals("Section A\na1", text(graph, "a"))
    }

    @Test
    fun `a graph that follows an edit made in another is not changed, and an undo there reaches it`() {
        val other = open("other.dot", "digraph g {\n  twin [\"content-type\"=\"plain:sections.txt:3-4\"];\n}\n")
        edit(graph, "a", "Section A\nedited")
        assertEquals("Section A\nedited", text(other, "twin"))
        assertTrue(graph.content.isDirty)
        assertFalse(other.content.isDirty)
        graph.g.undo()
        assertEquals("Section A\na1", text(graph, "a"))
        assertEquals("Section A\na1", text(other, "twin"))
        assertEquals("head\n\nSection A\na1\n\nSection B\nb1", text(graph, "whole"))
        graph.g.redo()
        assertEquals("Section A\nedited", text(other, "twin"))
    }

    @Test
    fun `an edit in one graph shows in another graph of the same file`() {
        val other = open("other.dot", "digraph g {\n  twin [\"content-type\"=\"plain:sections.txt:3-4\"];\n}\n")
        edit(graph, "a", "Section A\nfrom ranges")
        assertEquals("Section A\nfrom ranges", text(other, "twin"))
        edit(other, "twin", "Section A\nfrom other")
        assertEquals("Section A\nfrom other", text(graph, "a"))
        assertEquals("head\n\nSection A\nfrom other\n\nSection B\nb1", text(graph, "whole"))
    }

    @Test
    fun `the whole file node copied into another graph and edited there shows in the first`() {
        val index = open("index.dot", "digraph g {\n  i [label=\"index\"];\n}\n")
        graph.g.cleanSelect(setOf(node(graph, "whole")))
        Actions.copySelection(graph)
        Actions.pasteClipboard(index)
        val pasted = index.g.nodes.single { it.attributes.name != "i" }
        assertEquals("head\n\nSection A\na1\n\nSection B\nb1", pasted.attributes.text)
        index.startNodeEdit(pasted, null)
        index.textEditStarted(pasted.id)
        index.textTyped("head\n\nSection A\nfrom index\n\nSection B\nb1")
        index.textEditEnded("head\n\nSection A\nfrom index\n\nSection B\nb1")
        assertEquals("head\n\nSection A\nfrom index\n\nSection B\nb1", text(graph, "whole"))
        assertEquals("Section A\nfrom index", text(graph, "a"))
    }

    @Test
    fun `a node copied into a graph in another directory still holds the same file`() {
        Fs.createDirectories(dir.resolve("sub"))
        val elsewhere = open("sub/elsewhere.dot", "digraph g {\n  i [label=\"elsewhere\"];\n}\n")
        graph.g.cleanSelect(setOf(node(graph, "a")))
        Actions.copySelection(graph)
        Actions.pasteClipboard(elsewhere)
        val pasted = elsewhere.g.nodes.single { it.attributes.name != "i" }
        assertEquals("Section A\na1", pasted.attributes.text)
        edit(elsewhere, pasted, "Section A\nfrom elsewhere")
        assertEquals("Section A\nfrom elsewhere", text(graph, "a"))
    }

    @Test
    fun `the same graph in two tabs shows an edit in both`() {
        val twice = opened("ranges.dot")
        edit(twice, "a", "Section A\nfrom the second tab")
        assertEquals("Section A\nfrom the second tab", text(graph, "a"))
        assertEquals("head\n\nSection A\nfrom the second tab\n\nSection B\nb1", text(graph, "whole"))
        assertEquals("head\n\nSection A\nfrom the second tab\n\nSection B\nb1", text(twice, "whole"))
    }

    @Test
    fun `file names the graph, and the file of the one node selected with its lines as they are now`() {
        val file = dir.resolve("ranges.dot").toAbsolutePath().normalize()
        graph.content.showFilePath()
        assertEquals("$file", messages.last())
        edit(graph, "a", "Section A\na1\nmore")
        graph.g.cleanSelect(setOf(node(graph, "whole")))
        graph.content.showFilePath()
        assertEquals("graph: $file [modified]\nnode:  ${text.toAbsolutePath().normalize()} [modified]", messages.last())
        graph.g.cleanSelect(setOf(node(graph, "a")))
        graph.content.showFilePath()
        assertEquals("node:  ${text.toAbsolutePath().normalize()}:3-5 [modified]", messages.last().lines().last())
    }

    @Test
    fun `an edit in a source view shows in the graph`() {
        val source = opened("sections.txt:3-4")
        edit(source, SourceView.NODE_ID, "Section A\nfrom source")
        assertEquals("Section A\nfrom source", text(graph, "a"))
        assertEquals("head\n\nSection A\nfrom source\n\nSection B\nb1", text(graph, "whole"))
    }

    @Test
    fun `an edit in the graph shows in a source view`() {
        val source = opened("sections.txt:3-4")
        edit(graph, "a", "Section A\nfrom graph")
        assertEquals("Section A\nfrom graph", text(source, SourceView.NODE_ID))
    }

    @Test
    fun `a graph read again after its file was saved elsewhere shows what was saved`() {
        val source = opened("sections.txt:3-4")
        edit(source, SourceView.NODE_ID, "Section A\nsaved")
        source.content.saveNow()
        readAgain(graph)
        assertEquals("Section A\nsaved", text(graph, "a"))
    }

    @Test
    fun `a graph read again after its file was changed by something else shows the file`() {
        Fs.writeText(text, "head\n\nSection A\nchanged outside\n\nSection B\nb1\n")
        readAgain(graph)
        assertEquals("Section A\nchanged outside", text(graph, "a"))
        assertEquals("head\n\nSection A\nchanged outside\n\nSection B\nb1", text(graph, "whole"))
    }

    @Test
    fun `a graph read again keeps what is unsaved in its files, although something else changed them`() {
        edit(graph, "a", "Section A\nunsaved")
        Fs.writeText(text, "head\n\nSection A\nchanged outside\n\nSection B\nb1\n")
        readAgain(graph)
        assertEquals("Section A\nunsaved", text(graph, "a"))
    }
}
