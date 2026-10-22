@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import editor.EditorCore
import editor.EditorHub
import editor.FileStore
import graph_tools.AddNodeCommand
import graph_tools.MoveNodesCommand
import graph_tools.Node
import sync.Event
import sync.Request
import utils.Vector2
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Graphs a tab leaves with unsaved changes are kept to go back to; they are asked about when the application ends. */
class KeptGraphsTest {
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

    private val dir: Path = Fs.runtimeDir.resolve("sge-kept-${Random.nextLong().toULong()}").also { Fs.createDirectories(it) }
    private val a: Path = dir.resolve("a.dot").also { Fs.writeText(it, "digraph g {\n  to [label=\"to b\"; link=\"b.dot\"];\n}\n") }
    private val b: Path = dir.resolve("b.dot").also { Fs.writeText(it, "digraph g {\n  back [label=\"b\"];\n}\n") }
    private val client = EditorClient(Local(EditorHub(EditorCore(Disk)) { 0L }))

    private var exited = false
    private val questions = mutableListOf<String>()
    private val host = object : TabsHost {
        override fun view(graph: Graph, path: Path?, shell: Shell): EditorView = EditorView(graph, NoRenderer, shell, client, initialFile = path)
        override fun screen(): Vector2? = null
        override fun leaving(view: EditorView) {}
        override fun tabsChanged() {}
        override fun modesChanged() {}
        override fun exit() {
            exited = true
        }
    }
    private val dialogs = object : Dialogs by NoShell {
        override fun confirm(title: String, question: String): Boolean {
            questions += question
            return false
        }
    }
    private val tabs = Tabs(host, dialogs)

    @AfterTest
    fun forget() = FileContent.dropAll()

    private fun change(view: EditorView) = view.g.commit(AddNodeCommand(view.g, Node("more", Vector2(50, 50))))

    private fun follow(view: EditorView, label: String) {
        view.g.cleanSelect(view.g.nodes.filter { it.attributes.text == label }.toSet())
        view.content.followFileLink()
    }

    private fun open(): List<String?> = client.documents().map { it.file }

    @Test
    fun `a changed graph left by a link is kept, and going back finds it as it was`() {
        val view = tabs.open(a)
        change(view)
        follow(view, "to b")
        assertEquals(b, view.content.currentFile)
        assertEquals(emptyList(), questions)
        assertTrue(a.toString() in open())
        view.content.jumpBack()
        assertEquals(a, view.content.currentFile)
        assertEquals(2, view.g.nodes.size)
        assertTrue(view.content.isDirty)
        // shown again, it is no longer kept: leaving it unchanged would close it
        assertEquals(emptyList(), view.content.unsavedKept().map { it.info.file })
    }

    @Test
    fun `a changed graph without a file comes back too`() {
        val view = tabs.newTab(Graph().also { it.add(Node("to b", Vector2(0, 0)).also { n -> n.attributes.other["link"] = b.toString() }) })
        change(view)
        follow(view, "to b")
        assertEquals(b, view.content.currentFile)
        view.content.jumpBack()
        assertEquals(null, view.content.currentFile)
        assertEquals(setOf("to b", "more"), view.g.nodes.map { it.attributes.text }.toSet())
        assertEquals(emptyList(), questions)
    }

    @Test
    fun `a graph left without changes is closed`() {
        val view = tabs.open(a)
        follow(view, "to b")
        assertFalse(a.toString() in open())
        view.content.jumpBack()
        assertEquals(a, view.content.currentFile)
    }

    @Test
    fun `what the application made is left without asking, and not kept`() {
        val view = tabs.browse(dir)
        val moved = view.g.nodes.first()
        view.g.commit(MoveNodesCommand(view.g, mapOf(moved to moved.position), mutableMapOf(moved to moved.position + Vector2(10, 0))))
        follow(view, "b.dot")
        assertEquals(b, view.content.currentFile)
        assertEquals(emptyList(), questions)
        assertEquals(emptyList(), view.content.unsavedKept())
    }

    @Test
    fun `a browse graph with a node added or deleted is an untitled graph of the user's`() {
        val view = tabs.browse(dir)
        change(view)
        assertEquals(null, view.content.browseDir)
        assertEquals("*(untitled)", view.content.title)
        follow(view, "b.dot")
        assertEquals(1, view.content.unsavedKept().size)
    }

    @Test
    fun `quitting asks about the graphs that were kept, closing a tab about the one it shows`() {
        val view = tabs.open(a)
        change(view)
        follow(view, "to b")
        tabs.quitAll(force = false)
        assertFalse(exited)
        assertEquals(1, questions.size)
        assertTrue("a.dot" in questions.single())
        change(view)
        tabs.close()
        assertEquals(2, questions.size)
        assertTrue("b.dot" in questions.last())
    }

    @Test
    fun `wa saves the graphs that were kept`() {
        val view = tabs.open(a)
        change(view)
        follow(view, "to b")
        assertEquals(0, view.content.saveKept())
        assertTrue("more" in Fs.readText(a))
        assertEquals(emptyList(), view.content.unsavedKept())
    }

    @Test
    fun `without an editor to keep it, leaving a changed graph asks`() {
        val view = EditorView(DotGraphLoader.loadFromFile(a.toString()), NoRenderer, object : Shell by NoShell {
            override fun confirm(title: String, question: String): Boolean {
                questions += question
                return false
            }
        }, initialFile = a)
        change(view)
        follow(view, "to b")
        assertEquals(1, questions.size)
        assertEquals(a, view.content.currentFile)
    }
}
