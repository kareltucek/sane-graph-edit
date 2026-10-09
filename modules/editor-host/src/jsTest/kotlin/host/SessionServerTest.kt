@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package host

import client.DocumentSession
import client.EditorClient
import client.Fs
import client.Path
import client.RemoteConnection
import client.UnixSocketConnection
import editor.FileStore
import graph_tools.AddNodeCommand
import graph_tools.Node
import utils.Vector2
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private external fun require(module: String): dynamic
private external val process: dynamic

private var nextSocket = 0

/** The session socket in Node.js: an editor in this process, and clients that come through the socket as another process would. */
class SessionServerTest {
    private class MemoryFiles : FileStore {
        val content = mutableMapOf("/x.dot" to "digraph g {\n  a [ label=\"A\"; pos=\"0.0,0.0!\" ];\n}\n")
        override fun read(path: String): String = content[path] ?: throw IllegalArgumentException("no such file: $path")
        override fun write(path: String, text: String) {
            content[path] = text
        }
    }

    /** A path nobody else has: every test is an object of its own, so a counter here would start again. */
    private fun newSocket(): Path = Fs.path("${require("os").tmpdir()}/sge-test-${process.pid}-${nextSocket++}.sock")

    /** A test that ends when [body] calls its argument, or fails with what [body] or a later step throws. */
    private fun untilDone(body: (done: () -> Unit, step: (() -> Unit) -> Unit) -> Unit): Promise<Unit> = Promise { resolve, reject ->
        val step = { action: () -> Unit ->
            try {
                action()
            } catch (e: Throwable) {
                reject(e)
            }
        }
        step { body({ resolve(Unit) }, step) }
    }

    private fun texts(session: DocumentSession) = session.graph.nodes.map { it.attributes.text }.sorted()

    @Test
    fun `a client joins through the socket, and its change reaches the owner`() = untilDone { done, step ->
        val socket = newSocket()
        val host = EditorHost(MemoryFiles())
        val owner = EditorClient(host.connect()).open("/x.dot")
        SessionServer.start(host.hub, socket) { serving ->
            step {
                assertTrue(serving)
                UnixSocketConnection.connect(socket.toString()) { connected ->
                    step {
                        val connection: RemoteConnection = connected.getOrThrow()
                        assertFalse(connection.owner)
                        val joined = EditorClient(connection).open("/x.dot")
                        joined.onAttached = {
                            step {
                                assertEquals(listOf("A"), texts(joined))
                                owner.onChange = {
                                    step {
                                        if (texts(owner) == listOf("A", "from the socket")) {
                                            connection.close()
                                            SessionServer.stop(socket)
                                            done()
                                        }
                                    }
                                }
                                joined.graph.commit(AddNodeCommand(joined.graph, Node("from the socket", Vector2(10, 10))))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a socket somebody serves is theirs, and one left by a dead process is taken`() = untilDone { done, step ->
        val socket = newSocket()
        // what a process that died leaves: the file, and nobody listening
        Fs.writeText(socket, "")
        val first = EditorHost(MemoryFiles())
        SessionServer.start(first.hub, socket) { serving ->
            step {
                assertTrue(serving)
                SessionServer.start(EditorHost(MemoryFiles()).hub, socket) { second ->
                    step {
                        assertFalse(second)
                        assertFalse(Fs.exists(Fs.path("$socket.starting")))
                        SessionServer.stop(socket)
                        done()
                    }
                }
            }
        }
    }

    @Test
    fun `nobody at the socket is a failed connection`() = untilDone { done, step ->
        UnixSocketConnection.connect(newSocket().toString()) { connected ->
            step {
                assertTrue(connected.isFailure)
                done()
            }
        }
    }
}
