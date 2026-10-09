@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The file picker's shell command, as Node.js runs it. */
class NodeShellRunnerTest {
    private val dir = Fs.path("/tmp")

    private fun run(command: String, check: (FilePicker.Output) -> Unit): Promise<Unit> = Promise { resolve, reject ->
        NodeShellRunner.run(command, dir, mapOf("SGE_DIR" to dir.toString(), "SGE_FILE" to "")) { output ->
            try {
                check(output)
                resolve(Unit)
            } catch (e: Throwable) {
                reject(e)
            }
        }
    }

    @Test
    fun `the command runs in the directory, with the environment, and its lines come back`() =
        run("test \"\$SGE_DIR\" = \"\$(pwd)\" && echo ' a.dot:n ' && echo && echo b.dot") { output ->
            assertEquals(listOf("a.dot:n", "b.dot"), output.lines)
            assertNull(output.error)
        }

    @Test
    fun `a command that prints nothing chose nothing`() = run("true") { output ->
        assertEquals(emptyList(), output.lines)
        assertNull(output.error)
    }

    @Test
    fun `a command that fails without printing is an error`() = run("exit 3") { output ->
        assertEquals(emptyList(), output.lines)
        assertTrue(output.error != null)
    }

    @Test
    fun `a command that reads its input gets none`() = run("cat; echo done") { output ->
        assertEquals(listOf("done"), output.lines)
    }
}
