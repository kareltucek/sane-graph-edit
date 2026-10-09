@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import Graph
import editor.EditorCore
import editor.EditorHub
import editor.FileStore
import graph_tools.AddNodeCommand
import graph_tools.Node
import sync.Event
import sync.Request
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A client whose connection cannot wait for the editor, as a
 * JavaScript one cannot: documents are handed out before the
 * editor has answered and fill in afterwards.
 */
class LateAnswerTest {
    private class MemoryFiles : FileStore {
        val content = mutableMapOf<String, String>()
        override fun read(path: String): String = content[path] ?: throw IllegalArgumentException("no such file: $path")
        override fun write(path: String, text: String) {
            content[path] = text
        }
    }

    /** Answers and events wait, in the editor's order, until [arrive]. */
    private class SlowConnection(private val hub: EditorHub) : EditorConnection {
        private val waiting = ArrayDeque<() -> Unit>()
        override var onEvent: (Event) -> Unit = {}
        override val clientId: String = hub.connect { event -> waiting.addLast { onEvent(event) } }
        override fun send(request: Request) = hub.send(clientId, request)
        override fun call(request: Request): Event? = throw EditorException("this connection cannot wait")
        override fun request(request: Request, onAnswer: (Event?) -> Unit) {
            val answer = hub.call(clientId, request)
            waiting.addLast { onAnswer(answer) }
        }
        override fun close() = hub.disconnect(clientId)
        fun arrive() {
            while (waiting.isNotEmpty()) waiting.removeFirst()()
        }
    }

    /** Answers at once, as the editor of the same process does. */
    private class PromptConnection(private val hub: EditorHub) : EditorConnection {
        override var onEvent: (Event) -> Unit = {}
        override val clientId: String = hub.connect { onEvent(it) }
        override fun send(request: Request) = hub.send(clientId, request)
        override fun call(request: Request): Event? = hub.call(clientId, request)
        override fun close() = hub.disconnect(clientId)
    }

    private class RecordingShell : Shell by NoShell {
        val alerts = mutableListOf<String>()
        override fun alert(title: String, text: String, error: Boolean) {
            alerts += "$title: $text"
        }
    }

    private val files = MemoryFiles().also {
        it.content["/a.dot"] = "digraph a {\n  s [ label=\"S\"; pos=\"0.0,0.0!\" ];\n}\n"
        it.content["/b.dot"] = "digraph b {\n  t [ label=\"T\"; pos=\"0.0,0.0!\" ];\n  u [ label=\"U\"; pos=\"100.0,0.0!\" ];\n}\n"
    }
    private val hub = EditorHub(EditorCore(files)) { 0L }
    private val connection = SlowConnection(hub)
    private val client = EditorClient(connection)
    private val prompt = EditorClient(PromptConnection(hub))
    private val shell = RecordingShell()

    private fun texts(g: Graph) = g.nodes.map { it.attributes.text }.sorted()

    @Test
    fun `an opened document is empty until the editor answers, then it is the file's`() {
        val session = client.open("/b.dot")
        var attached = 0
        session.onAttached = { attached++ }
        assertFalse(session.isAttached)
        assertTrue(session.graph.nodes.isEmpty())
        connection.arrive()
        assertTrue(session.isAttached)
        assertEquals(listOf("T", "U"), texts(session.graph))
        assertEquals(1, attached)
        assertEquals("/b.dot", session.info.file)
    }

    @Test
    fun `a file that cannot be opened is told to the session`() {
        val session = client.open("/missing.dot")
        var reason: String? = null
        session.onFailed = { reason = it }
        connection.arrive()
        assertFalse(session.isAttached)
        assertTrue(reason != null)
        assertNull(session.graph.committer)
    }

    @Test
    fun `what is done to a new document before the editor answers reaches the editor afterwards`() {
        val graph = Graph().also { it.add(Node("first", Vector2(0, 0))) }
        val session = client.create(graph, "new")
        graph.commit(AddNodeCommand(graph, Node("second", Vector2(50, 0))))
        assertEquals(listOf("first", "second"), texts(graph))
        connection.arrive()
        assertTrue(session.isAttached)
        assertEquals(listOf("first", "second"), texts(prompt.attach(session.info.document).graph))
    }

    @Test
    fun `changes after the answer are sent as always`() {
        val session = client.open("/b.dot")
        connection.arrive()
        session.graph.commit(AddNodeCommand(session.graph, Node("V", Vector2(50, 50))))
        connection.arrive()
        assertEquals(listOf("T", "U", "V"), texts(prompt.open("/b.dot").graph))
    }

    @Test
    fun `the same file opened again before the answer is the same session`() {
        val first = client.open("/b.dot")
        val second = client.open("/b.dot")
        assertSame(first, second)
        connection.arrive()
        assertEquals(listOf("T", "U"), texts(first.graph))
    }

    @Test
    fun `a document closed before the answer is let go of when it arrives`() {
        val session = client.open("/b.dot")
        session.close()
        connection.arrive()
        assertFalse(session.isAttached)
        assertTrue(prompt.documents().isEmpty())
    }

    @Test
    fun `a view is busy until its document arrives, and then shows it`() {
        val view = EditorView(Graph(), NoRenderer, shell, client, Fs.path("/b.dot"))
        var idle = 0
        view.content.onIdle = { idle++ }
        assertTrue(view.content.isBusy)
        assertTrue(view.g.nodes.isEmpty())
        connection.arrive()
        assertFalse(view.content.isBusy)
        assertEquals(listOf("T", "U"), texts(view.g))
        assertEquals(1, idle)
    }

    @Test
    fun `a link is followed to its node once the file's content is there`() {
        val view = EditorView(Graph(), NoRenderer, shell, client, Fs.path("/a.dot")).also { it.size = Vector2(800, 600) }
        connection.arrive()
        view.content.openTarget(FileLink.Target(Fs.path("/b.dot"), "t"))
        assertTrue(view.content.isBusy)
        connection.arrive()
        assertEquals(listOf("T"), view.g.selectedNodes.map { it.attributes.text })
        assertEquals("b.dot", view.content.title)
    }

    @Test
    fun `a file that cannot be opened leaves the view without one, and says so`() {
        val view = EditorView(Graph(), NoRenderer, shell, client, Fs.path("/missing.dot"))
        connection.arrive()
        assertFalse(view.content.isBusy)
        assertNull(view.content.currentFile)
        assertEquals(1, shell.alerts.size)
        assertTrue(shell.alerts.single().startsWith("Open error"))
    }

    @Test
    fun `a save is confirmed when the editor answers`() {
        val view = EditorView(Graph(), NoRenderer, shell, client, Fs.path("/b.dot"))
        connection.arrive()
        view.g.commit(AddNodeCommand(view.g, Node("V", Vector2(50, 50))))
        var saved: Boolean? = null
        view.content.save { saved = it }
        assertNull(saved)
        assertTrue(view.content.isBusy)
        connection.arrive()
        assertEquals(true, saved)
        assertFalse(view.content.isBusy)
        assertTrue("label=\"V\"" in files.content.getValue("/b.dot"))
    }

    @Test
    fun `keys wait while the view is busy and run in order afterwards`() {
        val view = EditorView(Graph(), NoRenderer, shell, client, Fs.path("/b.dot"))
        val ran = mutableListOf<String>()
        val keys = KeyMapper(mapOf("a" to "first", "b" to "second"), Scheduler { _, _ -> {} }).also {
            it.commandExecutor = { command, _ ->
                ran += "$command with ${view.g.nodes.size} nodes"
                true
            }
            it.activeView = view
        }
        keys.feedKey("a")
        keys.feedKey("b")
        assertTrue(ran.isEmpty())
        connection.arrive()
        assertEquals(listOf("first with 2 nodes", "second with 2 nodes"), ran)
        keys.feedKey("a")
        assertEquals(3, ran.size)
    }
}
