package ui

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupPathsTest {

    @Test
    fun `basename strips the dot extension for file-backed tabs`() {
        assertEquals("foo", BackupPaths.basenameFor(Paths.get("/tmp/foo.dot")))
        assertEquals("my.graph", BackupPaths.basenameFor(Paths.get("/tmp/my.graph.dot")))
        assertEquals("untitled", BackupPaths.basenameFor(null))
    }

    @Test
    fun `hash is deterministic for the same absolute path`() {
        val p = Paths.get("/home/user/foo.dot")
        assertEquals(
            BackupPaths.hashFor(p, "ignored"),
            BackupPaths.hashFor(p, "alsoignored"),
        )
    }

    @Test
    fun `hash differs for different paths sharing a basename`() {
        val a = Paths.get("/home/alice/graph.dot")
        val b = Paths.get("/home/bob/graph.dot")
        assertNotEquals(
            BackupPaths.hashFor(a, "x"),
            BackupPaths.hashFor(b, "x"),
        )
    }

    @Test
    fun `untitled hash comes from the autosave uuid, truncated`() {
        // Whatever the scheme, it must take the UUID and be 8 chars.
        val hash = BackupPaths.hashFor(null, "12345678-abcd-efef-0000-111122223333")
        assertEquals(8, hash.length)
        assertTrue(hash.startsWith("12345678"))
    }

    @Test
    fun `prefix combines basename and hash with a dot`() {
        val p = Paths.get("/home/user/foo.dot")
        val prefix = BackupPaths.prefixFor(p, "ignored")
        assertTrue(prefix.startsWith("foo."))
        // basename.hash — exactly one dot in this particular case.
        assertEquals(2, prefix.count { it == '.' } + 1)
    }

    @Test
    fun `filenameFor composes prefix and id`() {
        assertEquals("foo.abcd1234.7.dot", BackupPaths.filenameFor("foo.abcd1234", 7))
    }

    @Test
    fun `parseFilename inverts filenameFor for valid inputs`() {
        val (prefix, id) = BackupPaths.parseFilename("foo.abcd1234.42.dot")!!
        assertEquals("foo.abcd1234", prefix)
        assertEquals(42, id)
    }

    @Test
    fun `parseFilename handles basenames that themselves contain dots`() {
        val (prefix, id) = BackupPaths.parseFilename("my.graph.8f3a1b2c.3.dot")!!
        assertEquals("my.graph.8f3a1b2c", prefix)
        assertEquals(3, id)
    }

    @Test
    fun `parseFilename rejects unrelated names`() {
        assertNull(BackupPaths.parseFilename("foo.dot"))          // missing id section
        assertNull(BackupPaths.parseFilename("foo.bar"))          // not a .dot
        assertNull(BackupPaths.parseFilename("foo.abc.notan.dot")) // non-numeric id
        assertNull(BackupPaths.parseFilename("42.dot"))           // prefix has no inner dot
    }

    @Test
    fun `two tabs editing the same file collide in the backup directory`() {
        // Documented limitation: the hash is a function of the
        // source path alone, so opening foo.dot in two tabs
        // gives them the same prefix. Their numbered snapshots
        // interleave in the same (basename, hash) counter
        // space. Live with it for now; if we ever split them,
        // flip this to assertNotEquals.
        val file = Paths.get("/home/user/shared.dot")
        val a = BackupPaths.prefixFor(file, "uuid-a")
        val b = BackupPaths.prefixFor(file, "uuid-b")
        assertEquals(a, b)
    }
}
