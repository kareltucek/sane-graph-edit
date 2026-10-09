@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsJsTest {
    private val missing = Fs.path("/nonexistent-sane-graph-edit/dir")

    // callers catch Exception, as they do on the JVM; what Node throws is not one by itself
    @Test
    fun `a directory that cannot be listed fails with an Exception`() {
        val caught = try {
            Fs.list(missing)
            null
        } catch (e: Exception) {
            e
        }
        assertTrue(caught != null)
    }

    @Test
    fun `a file tree leaves out a directory it cannot list`() {
        val tree = FileTree.build(missing, FileTree.DEFAULT_SCREEN)
        assertEquals(emptyList(), tree.nodes.filter { it.attributes.text.endsWith(".dot") })
    }
}
