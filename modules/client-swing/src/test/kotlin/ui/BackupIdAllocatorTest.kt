package ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupIdAllocatorTest {

    private fun newDir(): Path = createTempDirectory("sge-backup-test")

    /**
     * Populate [dir] with an empty file per filename so the
     * allocator's scan has something to see.
     */
    private fun touch(dir: Path, vararg names: String) {
        for (name in names) {
            Files.createFile(dir.resolve(name))
        }
    }

    @Test
    fun `fresh directory starts ids at 1`() {
        val alloc = BackupIdAllocator(newDir())
        assertEquals(1, alloc.peek("foo.abcd1234"))
    }

    @Test
    fun `peek does not advance the counter`() {
        val alloc = BackupIdAllocator(newDir())
        assertEquals(1, alloc.peek("foo.abcd1234"))
        assertEquals(1, alloc.peek("foo.abcd1234"))
        assertEquals(1, alloc.peek("foo.abcd1234"))
    }

    @Test
    fun `advance moves the counter forward`() {
        val alloc = BackupIdAllocator(newDir())
        assertEquals(1, alloc.peek("foo.abcd1234"))
        alloc.advance("foo.abcd1234")
        assertEquals(2, alloc.peek("foo.abcd1234"))
        alloc.advance("foo.abcd1234")
        assertEquals(3, alloc.peek("foo.abcd1234"))
    }

    @Test
    fun `prefixes are counted independently`() {
        val alloc = BackupIdAllocator(newDir())
        alloc.advance("foo.aaaa")
        alloc.advance("foo.aaaa")
        // bar.bbbb has never been touched — still starts at 1.
        assertEquals(1, alloc.peek("bar.bbbb"))
        assertEquals(3, alloc.peek("foo.aaaa"))
    }

    @Test
    fun `resumes from the highest existing id for a given prefix`() {
        val dir = newDir()
        touch(
            dir,
            "foo.abcd1234.1.dot",
            "foo.abcd1234.2.dot",
            "foo.abcd1234.5.dot",  // hole at 3, 4 — allocator still skips past
        )
        val alloc = BackupIdAllocator(dir)
        assertEquals(6, alloc.peek("foo.abcd1234"))
    }

    @Test
    fun `scan ignores files of other prefixes`() {
        val dir = newDir()
        touch(
            dir,
            "foo.aaaaaaaa.1.dot",
            "foo.aaaaaaaa.2.dot",
            "bar.bbbbbbbb.7.dot",
            "not-a-backup.txt",
            "readme.md",
        )
        val alloc = BackupIdAllocator(dir)
        assertEquals(3, alloc.peek("foo.aaaaaaaa"))
        assertEquals(8, alloc.peek("bar.bbbbbbbb"))
        // A prefix with no existing files still starts at 1 even
        // when neighbouring prefixes have higher ids.
        assertEquals(1, alloc.peek("baz.cccccccc"))
    }

    @Test
    fun `missing backup dir is treated as empty`() {
        val doesNotExist = newDir().resolve("nested").resolve("missing")
        val alloc = BackupIdAllocator(doesNotExist)
        assertEquals(1, alloc.peek("foo.abcd1234"))
    }
}
