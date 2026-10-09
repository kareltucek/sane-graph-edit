package host

import Graph
import client.DocumentSession
import client.EditorClient
import graph_tools.AddNodeCommand
import graph_tools.Node
import sync.Attached
import sync.ClientMessage
import sync.CreateDocument
import sync.Documents
import sync.GraphState
import sync.ListDocuments
import sync.OpenFile
import sync.Patched
import sync.Request
import sync.ServerMessage
import sync.Wire
import utils.Vector2
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The WebSocket server against the JDK's own WebSocket client, which
 * shares no code with it. The editor and its owner client run on one
 * thread; the remote side speaks the wire protocol by hand.
 */
class WebSocketServerTest {
    private val dir: Path = Files.createTempDirectory("sge-websocket")
    private val file: Path = dir.resolve("x.dot").also {
        Files.writeString(it, "digraph g {\na [ label=\"A\"; pos=\"0.0,0.0!\" ];\nb [ label=\"B\"; pos=\"100.0,0.0!\" ];\na -> b;\n}")
    }
    private val editorThread: ExecutorService = Executors.newSingleThreadExecutor()

    private fun <T> ExecutorService.run(block: () -> T): T = submit(block).get(10, TimeUnit.SECONDS)

    private val host: EditorHost = editorThread.run { EditorHost() }
    private val server = WebSocketServer(host.hub, 0) { editorThread.execute(it) }.start()
    private val ownerClient: EditorClient = editorThread.run { EditorClient(host.connect()) }
    private val owner: DocumentSession = editorThread.run { ownerClient.open(file.toString()) }
    private val http: HttpClient = HttpClient.newHttpClient()
    private val remotes = mutableListOf<Remote>()

    /** A client on the other end of a WebSocket. */
    private inner class Remote(url: String) : WebSocket.Listener {
        val received = LinkedBlockingQueue<ServerMessage>()
        val pongs = LinkedBlockingQueue<String>()
        val closed = CompletableFuture<Int>()
        private val text = StringBuilder()
        private var nextId = 0L
        val socket: WebSocket = http.newWebSocketBuilder().buildAsync(URI(url), this).get(10, TimeUnit.SECONDS)

        init {
            remotes += this
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                received.put(Wire.decodeServer(text.toString()))
                text.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            pongs.put(Charsets.UTF_8.decode(message).toString())
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closed.complete(statusCode)
            return null
        }

        fun next(): ServerMessage = assertNotNull(received.poll(10, TimeUnit.SECONDS), "nothing came from the editor")

        fun call(request: Request): ServerMessage {
            val id = nextId++
            socket.sendText(Wire.encode(ClientMessage(id, request, call = true)), true).get(10, TimeUnit.SECONDS)
            return generateSequence { next() }.first { it.replyTo == id }
        }
    }

    @AfterTest
    fun shutDown() {
        remotes.forEach { it.socket.abort() }
        server.close()
        editorThread.shutdownNow()
    }

    @Test
    fun `a client that knows the token is greeted and is not the owner`() {
        val hello = Remote(server.url).next()
        assertNotNull(hello.client)
        assertEquals(false, hello.owner)
    }

    @Test
    fun `a client without the token is refused`() {
        val port = server.port
        listOf("ws://127.0.0.1:$port/", "ws://127.0.0.1:$port/?token=wrong", "ws://127.0.0.1:$port/?token=${server.token}x").forEach { url ->
            assertTrue(runCatching { Remote(url) }.isFailure, url)
        }
        assertEquals(1, editorThread.run { owner.info.clients })
    }

    @Test
    fun `it opens the owner's document and sees the owner's change`() {
        val remote = Remote(server.url)
        val attached = assertIs<Attached>(remote.call(OpenFile(file.toString())).event)
        assertEquals(owner.info.document, attached.info.document)
        assertEquals(2, attached.state.nodes.size)

        val n = Node("from the owner", Vector2(5, 6))
        editorThread.run { owner.graph.commit(AddNodeCommand(owner.graph, n)) }
        val patched = generateSequence { remote.next().event }.filterIsInstance<Patched>().first()
        assertTrue(n.id in patched.patch.addNodes)
    }

    @Test
    fun `messages too long for a short frame arrive whole, both ways`() {
        val remote = Remote(server.url)
        // 126 and 65536 bytes are where a frame's length field changes size
        listOf(100, 200, 70_000, 300_000).forEach { size ->
            val text = "é".repeat(size)
            val state = GraphState.of(Graph().also { it.add(Node(text, Vector2(0, 0))) })
            val attached = assertIs<Attached>(remote.call(CreateDocument(state, "big")).event)
            assertEquals(state, attached.state, "$size characters")
        }
    }

    @Test
    fun `a ping is answered`() {
        val remote = Remote(server.url)
        remote.socket.sendPing(ByteBuffer.wrap("are you there".toByteArray())).get(10, TimeUnit.SECONDS)
        assertEquals("are you there", remote.pongs.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun `closing is answered and the client is gone from the session`() {
        val remote = Remote(server.url)
        remote.call(OpenFile(file.toString()))
        assertEquals(2, (remote.call(ListDocuments).event as Documents).documents.single().clients)
        remote.socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(10, TimeUnit.SECONDS)
        assertEquals(WebSocket.NORMAL_CLOSURE, remote.closed.get(10, TimeUnit.SECONDS))
        // the editor learns of it after answering; ask until it has
        val clients = generateSequence { editorThread.run { ownerClient.documents().single().clients } }.take(200).onEach { Thread.sleep(10) }.first { it == 1 }
        assertEquals(1, clients)
        assertFalse(editorThread.run { owner.info.dirty })
    }

    @Test
    fun `text that is not a message gets an error and the connection lives on`() {
        val remote = Remote(server.url)
        remote.next()
        remote.socket.sendText("not json", true).get(10, TimeUnit.SECONDS)
        assertNotNull(remote.next().error)
        assertIs<Documents>(remote.call(ListDocuments).event)
    }
}
