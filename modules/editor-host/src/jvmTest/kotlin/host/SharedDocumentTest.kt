package host

import client.DocumentSession
import client.EditorClient
import client.EditorConnection
import editor.EditorCore
import editor.EditorHub
import editor.FileStore
import graph_tools.AddNodeCommand
import graph_tools.EditTextCommand
import graph_tools.MoveNodesCommand
import graph_tools.Node
import graph_tools.RemoveNodesCommand
import sync.Event
import sync.GraphState
import sync.Request
import utils.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedDocumentTest {
    private class MemoryFiles : FileStore {
        val content = mutableMapOf<String, String>()
        override fun read(path: String): String = content[path] ?: throw IllegalArgumentException("no such file: $path")
        override fun write(path: String, text: String) {
            content[path] = text
        }
    }

    /** A connection whose incoming events wait until [deliver]: a client that lags behind. */
    private class LaggingConnection(private val hub: EditorHub) : EditorConnection {
        private val waiting = ArrayDeque<Event>()
        var lag = false
        override var onEvent: (Event) -> Unit = {}
        override val clientId: String = hub.connect { if (lag) waiting.addLast(it) else onEvent(it) }
        override fun send(request: Request) = hub.send(clientId, request)
        override fun call(request: Request): Event? = hub.call(clientId, request)
        override fun close() = hub.disconnect(clientId)
        fun deliver() {
            while (waiting.isNotEmpty()) onEvent(waiting.removeFirst())
        }
    }

    private val dot = """
        digraph g {
        // the first
        a [ label="A"; pos="0.0,0.0!" ];
        b [ label="B"; pos="100.0,0.0!" ];
        a -> b;
        }
    """.trimIndent()

    private val files = MemoryFiles().also { it.content["/x.dot"] = dot }
    private var time = 0L
    private val hub = EditorHub(EditorCore(files)) { time }
    private val ownerConnection = LaggingConnection(hub)
    private val otherConnection = LaggingConnection(hub)
    private val owner: DocumentSession = EditorClient(ownerConnection).open("/x.dot")
    private val other: DocumentSession = EditorClient(otherConnection).open("/x.dot")

    private fun DocumentSession.byText(text: String): Node = graph.nodes.single { it.attributes.text == text }

    private fun assertInSync() {
        assertEquals(GraphState.of(owner.graph), GraphState.of(other.graph))
    }

    @Test
    fun `both clients get the same document`() {
        assertEquals(owner.info.document, other.info.document)
        assertEquals(2, other.graph.nodes.size)
        assertEquals(1, other.graph.edges.size)
        assertInSync()
        assertFalse(owner.info.dirty)
    }

    @Test
    fun `a change by one client shows up at the other`() {
        val n = Node("C", Vector2(5, 6))
        other.graph.commit(AddNodeCommand(other.graph, n))
        assertNotNull(owner.graph.node(n.id))
        assertInSync()
        assertTrue(owner.info.dirty && other.info.dirty)
    }

    @Test
    fun `undo is per document and reaches everyone`() {
        val n = Node("C", Vector2(5, 6))
        other.graph.commit(AddNodeCommand(other.graph, n))
        owner.graph.undo()
        assertNull(other.graph.node(n.id))
        assertInSync()
        owner.graph.redo()
        assertNotNull(other.graph.node(n.id))
        assertInSync()
    }

    @Test
    fun `typing is one undo step, a pause starts a new one`() {
        val a = owner.byText("A")
        listOf("A" to "Ax", "Ax" to "Axy").forEach { (from, to) ->
            time += 100
            owner.graph.commit(EditTextCommand(owner.graph, a, from, to))
        }
        time += 5000
        owner.graph.commit(EditTextCommand(owner.graph, a, "Axy", "Axyz"))
        owner.graph.undo()
        assertEquals("Axy", other.byText("Axy").attributes.text)
        owner.graph.undo()
        assertEquals("A", a.attributes.text)
        assertInSync()
    }

    @Test
    fun `a gesture that already changed the graph is recorded once`() {
        val a = owner.byText("A")
        val from = a.position
        a.position = Vector2(40, 40)
        owner.graph.commitApplied(MoveNodesCommand(owner.graph, mapOf(a to from), mutableMapOf(a to a.position)))
        assertEquals(Vector2(40, 40), other.byText("A").position)
        other.graph.undo()
        assertEquals(from, a.position)
        assertInSync()
    }

    @Test
    fun `changes of different fields made at the same time both survive`() {
        val a = other.byText("A")
        otherConnection.lag = true
        owner.graph.commit(MoveNodesCommand(owner.graph, mapOf(owner.byText("B") to Vector2(100, 0)), mutableMapOf(owner.byText("B") to Vector2(7, 7))))
        // the other client has not seen the move yet
        other.graph.commit(EditTextCommand(other.graph, a, "A", "A2"))
        otherConnection.lag = false
        otherConnection.deliver()
        assertEquals("A2", owner.byText("A2").attributes.text)
        assertEquals(Vector2(7, 7), other.byText("B").position)
        assertInSync()
    }

    @Test
    fun `the owner wins when both change the same node`() {
        var rejected: String? = null
        other.onRejected = { rejected = it }
        otherConnection.lag = true
        val a = owner.byText("A")
        owner.graph.commit(MoveNodesCommand(owner.graph, mapOf(a to a.position), mutableMapOf(a to Vector2(9, 9))))
        other.graph.commit(EditTextCommand(other.graph, other.byText("A"), "A", "mine"))
        otherConnection.lag = false
        otherConnection.deliver()
        assertNotNull(rejected)
        assertEquals("A", other.graph.node(a.id)!!.attributes.text)
        assertEquals(Vector2(9, 9), other.graph.node(a.id)!!.position)
        assertInSync()
    }

    @Test
    fun `the owner changing what another client changed first is accepted`() {
        ownerConnection.lag = true
        val a = other.byText("A")
        other.graph.commit(MoveNodesCommand(other.graph, mapOf(a to a.position), mutableMapOf(a to Vector2(9, 9))))
        owner.graph.commit(EditTextCommand(owner.graph, owner.byText("A"), "A", "owner's"))
        ownerConnection.lag = false
        ownerConnection.deliver()
        assertEquals("owner's", other.graph.node(a.id)!!.attributes.text)
        assertEquals(Vector2(9, 9), owner.graph.node(a.id)!!.position)
        assertInSync()
    }

    @Test
    fun `a change naming something that is gone is refused, also for the owner`() {
        var rejected: String? = null
        owner.onRejected = { rejected = it }
        ownerConnection.lag = true
        val a = other.byText("A")
        other.graph.commit(RemoveNodesCommand(other.graph, listOf(a)))
        owner.graph.commit(EditTextCommand(owner.graph, owner.byText("A"), "A", "too late"))
        ownerConnection.lag = false
        ownerConnection.deliver()
        assertNotNull(rejected)
        assertNull(owner.graph.node(a.id))
        assertInSync()
    }

    @Test
    fun `saving keeps the file's layout and clears the dirty flag everywhere`() {
        owner.graph.commit(AddNodeCommand(owner.graph, Node("C", Vector2(1, 2))))
        other.save()
        val saved = files.content["/x.dot"]!!
        assertTrue("// the first" in saved)
        assertTrue(saved.indexOf("a [") < saved.indexOf("b ["))
        assertTrue("label=\"C\"" in saved)
        assertFalse(owner.info.dirty || other.info.dirty)
    }

    @Test
    fun `reload replaces the content for everyone`() {
        owner.graph.commit(AddNodeCommand(owner.graph, Node("C", Vector2(1, 2))))
        other.reload()
        assertEquals(setOf("A", "B"), owner.graph.nodes.map { it.attributes.text }.toSet())
        assertFalse(owner.info.dirty)
        assertInSync()
    }

    @Test
    fun `a document is closed when its last client leaves`() {
        val client = EditorClient(LaggingConnection(hub))
        client.open("/x.dot").close()
        assertEquals(1, client.documents().size)
        owner.close()
        other.close()
        assertEquals(0, client.documents().size)
    }
}
