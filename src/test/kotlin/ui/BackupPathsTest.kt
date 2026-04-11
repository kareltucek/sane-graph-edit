package ui

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackupPathsTest {

    private val backupDir = Paths.get("/tmp/sge-backups")

    @Test
    fun `file-based paths are deterministic`() {
        val file = Paths.get("/home/user/graphs/foo.dot")
        val a = BackupPaths.fileBased(backupDir, file)
        val b = BackupPaths.fileBased(backupDir, file)
        assertEquals(a, b)
    }

    @Test
    fun `different source files produce different backup paths`() {
        val a = BackupPaths.fileBased(backupDir, Paths.get("/home/user/a.dot"))
        val b = BackupPaths.fileBased(backupDir, Paths.get("/home/user/b.dot"))
        assertNotEquals(a, b)
    }

    @Test
    fun `file-based paths live under the backup dir with an 'f-' prefix`() {
        val p = BackupPaths.fileBased(backupDir, Paths.get("/home/user/foo.dot"))
        assertEquals(backupDir, p.parent)
        assertTrue(p.fileName.toString().startsWith("f-"))
        assertTrue(p.fileName.toString().endsWith(".dot"))
    }

    @Test
    fun `untitled paths use the uuid verbatim with a 'u-' prefix`() {
        val uuid = "abc-123"
        val p = BackupPaths.untitledBased(backupDir, uuid)
        assertEquals(backupDir.resolve("u-abc-123.dot"), p)
    }

    @Test
    fun `forView picks file-based when a path is present, untitled otherwise`() {
        val file = Paths.get("/home/user/foo.dot")
        val withFile = BackupPaths.forView(backupDir, file, "ignored-uuid")
        val without = BackupPaths.forView(backupDir, null, "abc-123")

        assertEquals(BackupPaths.fileBased(backupDir, file), withFile)
        assertEquals(BackupPaths.untitledBased(backupDir, "abc-123"), without)
    }

    @Test
    fun `two tabs with the same file hash to the same backup slot`() {
        // Documents the known limitation: opening the same file in
        // two tabs causes their backups to collide. If we ever fix
        // this (by mixing the autosave UUID into the hash), this
        // test should flip to assertNotEquals.
        val file = Paths.get("/home/user/shared.dot")
        val a = BackupPaths.forView(backupDir, file, "uuid-a")
        val b = BackupPaths.forView(backupDir, file, "uuid-b")
        assertEquals(a, b)
    }
}
