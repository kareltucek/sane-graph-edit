@file:Suppress("NAME_CONTAINS_ILLEGAL_CHARS")

package client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Paths behave the same on every platform the client runs on. */
class PathTest {
    private val file = Fs.path("/home/me/graphs/a.dot")

    @Test
    fun `name and parent`() {
        assertEquals("a.dot", file.name)
        assertEquals("/home/me/graphs", file.getParent().toString())
        assertEquals("", Fs.path("/").name)
        assertNull(Fs.path("/").getParent())
        assertNull(Fs.path("a.dot").getParent())
    }

    @Test
    fun `resolve keeps an absolute path and appends a relative one`() {
        val dir = Fs.path("/home/me")
        assertEquals("/home/me/graphs/a.dot", dir.resolve("graphs/a.dot").toString())
        assertEquals("/etc/hosts", dir.resolve("/etc/hosts").toString())
        assertEquals(file, dir.resolve(Fs.path("graphs")).resolve("a.dot"))
    }

    @Test
    fun `normalize removes dots`() {
        assertEquals("/home/me/a.dot", Fs.path("/home/me/graphs/../x/./../a.dot").normalize().toString())
    }

    @Test
    fun `a relative path becomes absolute below the working directory`() {
        val absolute = Fs.path("a.dot").toAbsolutePath()
        assertTrue(absolute.toString().startsWith("/"))
        assertEquals("a.dot", absolute.name)
        assertEquals(Workspace.dir, absolute.getParent())
    }

    @Test
    fun `the file system is there`() {
        assertTrue(Fs.isDirectory(Fs.path(Fs.home)))
        assertTrue(Fs.exists(Fs.path("/")))
        assertFalse(Fs.exists(Fs.path("/no/such/file.dot")))
        assertFalse(Fs.isDirectory(Fs.path("/no/such/dir")))
        assertTrue(Fs.list(Fs.path("/")).any { it.toString() == "/tmp" || it.toString() == "/usr" })
    }
}
