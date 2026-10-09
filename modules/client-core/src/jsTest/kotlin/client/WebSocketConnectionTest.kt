@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import graph_tools.AddNodeCommand
import graph_tools.Node
import sync.Failed
import sync.ListDocuments
import sync.Show
import utils.Vector2
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val childProcess: dynamic = js("require('child_process')")
private val fs: dynamic = js("require('fs')")
private val os: dynamic = js("require('os')")
private val env: dynamic = js("process.env")
private val global: dynamic = js("globalThis")
private const val TIMEOUT_MS = 15_000

/**
 * The JavaScript client against a real editor: a JVM process started
 * for each test (`host.TestEditor`, handed over by the build), whose
 * owner has [file] open.
 */
private class Editor(val url: String, val file: String, private val process: dynamic, private val finish: (Throwable?) -> Unit) {
    fun kill() {
        process.kill()
    }

    fun passed() = finish(null)

    /** Runs a step of the test; what it throws fails the test. */
    fun step(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            finish(e)
        }
    }
}

private fun withEditor(test: (Editor) -> Unit): Promise<Unit> = Promise { resolve, reject ->
    val dir = fs.mkdtempSync(os.tmpdir() + "/sge-ws-") as String
    val file = "$dir/x.dot"
    fs.writeFileSync(file, "digraph g {\na [ label=\"A\"; pos=\"0.0,0.0!\" ];\nb [ label=\"B\"; pos=\"100.0,0.0!\" ];\na -> b;\n}")
    val process = childProcess.spawn(env.SGE_TEST_JAVA, arrayOf("-cp", env.SGE_TEST_EDITOR_CLASSPATH, "host.TestEditorKt", file))
    var printed = ""
    var started = false
    var finished = false
    var timeout: dynamic = null
    val finish = { failure: Throwable? ->
        if (!finished) {
            finished = true
            global.clearTimeout(timeout)
            process.kill()
            if (failure == null) resolve(Unit) else reject(failure)
        }
    }
    // the editor must not outlive a test that never finishes: the test run would wait for it
    timeout = global.setTimeout({ finish(Error("not finished after ${TIMEOUT_MS / 1000} s; the editor printed ${printed.length} characters, started=$started")) }, TIMEOUT_MS)
    // as text: Kotlin's toString() of a Buffer is "[...]"
    process.stdout.setEncoding("utf8")
    process.stderr.setEncoding("utf8")
    process.stderr.on("data") { chunk: String -> console.error(chunk) }
    process.on("error") { e: dynamic -> finish(Error("cannot start the editor: $e")) }
    process.stdout.on("data") { chunk: String ->
        printed += chunk
        if (!started && '\n' in printed) {
            started = true
            val editor = Editor(printed.substringBefore('\n').trim(), file, process, finish)
            editor.step { test(editor) }
        }
    }
}

class WebSocketConnectionTest {
    @Test
    fun `a client without the token is refused`() = withEditor { editor ->
        WebSocketConnection.connect(editor.url.substringBefore("?token=") + "?token=wrong") { connection ->
            editor.step {
                assertTrue(connection.isFailure)
                editor.passed()
            }
        }
    }

    @Test
    fun `it opens the document, changes and saves it, and sees the other user's change`() = withEditor { editor ->
        WebSocketConnection.connect(editor.url) { connection ->
            editor.step {
                assertFalse(connection.getOrThrow().owner)
                EditorClient(connection.getOrThrow()).open(editor.file) { opened ->
                    editor.step {
                        val session = opened.getOrThrow()
                        assertEquals(listOf("A", "B"), session.graph.nodes.map { it.attributes.text })
                        session.onChange = {
                            if (session.graph.nodes.any { it.attributes.text == "from the owner" }) editor.passed()
                        }
                        session.graph.commit(AddNodeCommand(session.graph, Node("from node", Vector2(1, 2))))
                        session.save { saved ->
                            editor.step {
                                saved.getOrThrow()
                                assertTrue("label=\"from node\"" in Fs.readText(Fs.path(editor.file)))
                                assertFalse(session.info.dirty)
                                connection.getOrThrow().send(Show("from the owner"))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a file that is not there comes back as a failure`() = withEditor { editor ->
        WebSocketConnection.connect(editor.url) { connection ->
            editor.step {
                EditorClient(connection.getOrThrow()).open(editor.file + ".missing") { opened ->
                    editor.step {
                        assertTrue(opened.isFailure)
                        editor.passed()
                    }
                }
            }
        }
    }

    @Test
    fun `when the editor goes away the client is told and its unanswered request fails`() = withEditor { editor ->
        WebSocketConnection.connect(editor.url) { connection ->
            editor.step {
                val c = connection.getOrThrow()
                var answer: Any? = null
                c.onClosed = {
                    editor.step {
                        assertIs<Failed>(answer)
                        editor.passed()
                    }
                }
                // the editor stops before it can answer, whenever the kill lands
                c.send(Show("hang"))
                c.request(ListDocuments) { answer = it }
                editor.kill()
            }
        }
    }
}
