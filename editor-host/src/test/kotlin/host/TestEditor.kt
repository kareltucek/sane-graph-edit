package host

import client.EditorClient
import graph_tools.AddNodeCommand
import graph_tools.Node
import utils.Vector2
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * An editor session with a WebSocket, for tests that run in another
 * process (the JavaScript client's). Prints the address to connect
 * to and ends when its input does.
 *
 * Its owner has the file given as the argument open, and adds a node
 * with the text of whatever it is asked to show: that is how a test
 * makes "the other user" change something. Asked to show "hang", it
 * answers nothing any more.
 */
fun main(args: Array<String>) {
    val editorThread = Executors.newSingleThreadExecutor()
    val host = editorThread.submit(Callable { EditorHost() }).get()
    val server = WebSocketServer(host.hub, 0) { editorThread.execute(it) }.start()
    editorThread.submit {
        val owner = EditorClient(host.connect())
        val document = owner.open(args[0])
        // later, as a window does: this arrives while an event is being delivered
        owner.onShow = { text, _ ->
            // "hang" stops the editor for good, so that whatever is asked next stays unanswered
            if (text == "hang") Thread.sleep(Long.MAX_VALUE)
            editorThread.execute { document.graph.commit(AddNodeCommand(document.graph, Node(text, Vector2(0, 0)))) }
        }
    }.get()
    println(server.url)
    while (System.`in`.read() >= 0) {
        // until the test's process is done with us
    }
    exitProcess(0)
}
