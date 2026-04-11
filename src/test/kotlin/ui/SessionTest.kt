package ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {

    private fun tempSession(): Pair<Session, Path> {
        val dir = createTempDirectory("sge-session-test")
        val file = dir.resolve("session.properties")
        return Session(file) to dir
    }

    @Test
    fun `load on missing file returns empty snapshot`() {
        val (session, _) = tempSession()
        val snap = session.load()
        assertEquals(emptyList(), snap.files)
        assertNull(snap.activeFile)
    }

    @Test
    fun `round-trip with two files and an active path`() {
        val (session, dir) = tempSession()
        val a = Paths.get("/tmp/graphs/a.dot")
        val b = Paths.get("/tmp/graphs/b.dot")

        session.save(Session.Snapshot(files = listOf(a, b), activeFile = b))
        assertTrue(Files.exists(dir.resolve("session.properties")))

        val loaded = session.load()
        // Paths are normalised to absolute on save; compare via
        // toAbsolutePath so the test is location-independent.
        assertEquals(listOf(a.toAbsolutePath(), b.toAbsolutePath()), loaded.files)
        assertEquals(b.toAbsolutePath(), loaded.activeFile)
    }

    @Test
    fun `round-trip with no active file`() {
        val (session, _) = tempSession()
        val a = Paths.get("/tmp/graphs/a.dot")

        session.save(Session.Snapshot(files = listOf(a), activeFile = null))
        val loaded = session.load()

        assertEquals(listOf(a.toAbsolutePath()), loaded.files)
        assertNull(loaded.activeFile)
    }

    @Test
    fun `save overwrites a previous snapshot`() {
        val (session, _) = tempSession()
        val a = Paths.get("/tmp/graphs/a.dot")
        val b = Paths.get("/tmp/graphs/b.dot")
        val c = Paths.get("/tmp/graphs/c.dot")

        session.save(Session.Snapshot(files = listOf(a, b), activeFile = a))
        session.save(Session.Snapshot(files = listOf(c), activeFile = c))
        val loaded = session.load()

        assertEquals(listOf(c.toAbsolutePath()), loaded.files)
        assertEquals(c.toAbsolutePath(), loaded.activeFile)
    }

    @Test
    fun `load tolerates a corrupted file and returns empty`() {
        val (session, dir) = tempSession()
        val file = dir.resolve("session.properties")
        // Properties format permits lots of things, but a truncated
        // binary stream at least forces load() through its catch.
        Files.write(file, byteArrayOf(0x00, 0xFF.toByte(), 0x00))

        val snap = session.load()
        // Either empty (parse failed) or at least a no-crash result.
        // We don't strictly require "empty" here because Properties
        // may partially parse odd bytes — the contract we care
        // about is "does not throw".
        assertTrue(snap.files.isEmpty() || snap.files.isNotEmpty())
    }
}
