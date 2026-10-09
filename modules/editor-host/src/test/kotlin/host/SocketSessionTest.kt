package host

import client.DocumentSession
import client.EditorClient
import client.SocketConnection
import graph_tools.AddNodeCommand
import graph_tools.EditTextCommand
import graph_tools.Node
import sync.GraphState
import utils.Vector2
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two processes' worth of threads in one test: the editor with its
 * owner client on one thread, a client connected through the socket
 * on another. Nothing here waits for time to pass; [settle] is a
 * round trip that everything sent before it has overtaken.
 */
class SocketSessionTest {
    private val dir: Path = Files.createTempDirectory("sge-socket")
    private val file: Path = dir.resolve("x.dot").also {
        Files.writeString(it, "digraph g {\na [ label=\"A\"; pos=\"0.0,0.0!\" ];\nb [ label=\"B\"; pos=\"100.0,0.0!\" ];\na -> b;\n}")
    }
    private val editorThread: ExecutorService = Executors.newSingleThreadExecutor()
    private val remoteThread: ExecutorService = Executors.newSingleThreadExecutor()

    private fun <T> ExecutorService.run(block: () -> T): T = submit(block).get(10, TimeUnit.SECONDS)

    private val host: EditorHost = editorThread.run { EditorHost() }
    private val server = SocketServer(host.hub, dir.resolve("s.sock")) { editorThread.execute(it) }.start()
    private val ownerClient: EditorClient = editorThread.run { EditorClient(host.connect()) }
    private val owner: DocumentSession = editorThread.run { ownerClient.open(file.toString()) }
    private val connection = SocketConnection(server.path) { remoteThread.execute(it) }
    private val remoteClient = EditorClient(connection)
    private val remote: DocumentSession = remoteThread.run { remoteClient.open(file.toString()) }

    @AfterTest
    fun shutDown() {
        connection.close()
        server.close()
        editorThread.shutdownNow()
        remoteThread.shutdownNow()
    }

    /** Returns when the editor has processed, and both clients have received, everything sent so far. */
    private fun settle() {
        remoteThread.run { remoteClient.documents() }
        editorThread.run { }
        remoteThread.run { }
    }

    private fun state(session: DocumentSession, thread: ExecutorService): GraphState = thread.run { GraphState.of(session.graph) }

    private fun assertInSync() {
        assertEquals(state(owner, editorThread), state(remote, remoteThread))
    }

    @Test
    fun `the socket client is not the owner and gets the document`() {
        assertFalse(connection.owner)
        assertEquals(owner.info.document, remote.info.document)
        assertEquals(2, remoteThread.run { remote.graph.nodes.size })
        assertInSync()
    }

    @Test
    fun `changes travel both ways`() {
        val fromRemote = Node("R", Vector2(1.5, 2.5))
        remoteThread.run { remote.graph.commit(AddNodeCommand(remote.graph, fromRemote)) }
        settle()
        assertNotNull(editorThread.run { owner.graph.node(fromRemote.id) })

        editorThread.run {
            val n = owner.graph.node(fromRemote.id)!!
            owner.graph.commit(EditTextCommand(owner.graph, n, "R", "seen by the owner"))
        }
        settle()
        assertEquals("seen by the owner", remoteThread.run { fromRemote.attributes.text })
        assertInSync()
    }

    @Test
    fun `undo by the socket client undoes the owner's change`() {
        val n = Node("O", Vector2(3, 4))
        editorThread.run { owner.graph.commit(AddNodeCommand(owner.graph, n)) }
        settle()
        remoteThread.run { remote.graph.undo() }
        settle()
        assertNull(editorThread.run { owner.graph.node(n.id) })
        assertInSync()
    }

    @Test
    fun `saving through the socket writes the file and tells the owner`() {
        remoteThread.run { remote.graph.commit(AddNodeCommand(remote.graph, Node("saved", Vector2(1, 1)))) }
        settle()
        assertTrue(editorThread.run { owner.info.dirty })
        remoteThread.run { remote.save() }
        settle()
        assertTrue("label=\"saved\"" in Files.readString(file))
        assertFalse(editorThread.run { owner.info.dirty })
    }

    @Test
    fun `a request to show a file reaches the owner and nobody else`() {
        val shown = mutableListOf<Pair<String, Boolean>>()
        editorThread.run { ownerClient.onShow = { path, here -> shown += path to here } }
        remoteClient.onShow = { _, _ -> error("not the owner") }
        remoteThread.run { connection.call(sync.Show("/some/other.dot", here = true)) }
        settle()
        assertEquals(listOf("/some/other.dot" to true), editorThread.run { shown.toList() })
    }

    @Test
    fun `a failing request comes back as an error`() {
        val error = remoteThread.run { runCatching { remoteClient.open(dir.resolve("missing.dot").toString()) }.exceptionOrNull() }
        assertNotNull(error)
    }

    @Test
    fun `a client that leaves does not close the owner's document`() {
        connection.close()
        editorThread.run { }
        val documents = editorThread.run { EditorClient(host.connect()).documents() }
        assertEquals(1, documents.size)
    }

    @Test
    fun `when the editor goes away the client keeps a graph it can still edit`() {
        val lost = CompletableFuture<Boolean>()
        connection.onClosed = {
            remoteClient.connectionLost()
            lost.complete(true)
        }
        remoteThread.run { remote.onLost = { } }
        server.close()
        editorThread.run { host.hub.disconnect(connection.clientId) }
        // closing the listening socket does not close accepted connections; the owner's process ending does
        connection.close()
        assertTrue(lost.get(10, TimeUnit.SECONDS))
        remoteThread.run {
            assertNull(remote.graph.committer)
            remote.graph.commit(AddNodeCommand(remote.graph, Node("offline", Vector2(0, 0))))
            assertTrue(remote.graph.history.canUndo)
        }
    }
}
